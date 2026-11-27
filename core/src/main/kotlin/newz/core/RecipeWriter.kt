package newz.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

/** Decides whether what a recipe extracted looks like a real article list. */
object RecipeValidator {
    const val MIN_ITEMS = 4

    /** Empty when the result is acceptable; otherwise what is wrong with it. */
    fun problems(items: List<RawItem>, pageUrl: String): List<String> {
        if (items.isEmpty()) return listOf("the selectors matched no articles")
        val found = ArrayList<String>()
        if (items.size < MIN_ITEMS) found.add("only ${items.size} articles were found; a listing page should give at least $MIN_ITEMS")

        val titles = items.map { it.title.trim().lowercase() }
        if (titles.distinct().size < items.size * 0.8) found.add("many items have the same title, so the title selector is probably wrong")
        val averageLength = items.sumOf { it.title.trim().length }.toDouble() / items.size
        if (averageLength < 15) found.add("the titles are too short to be headlines (average ${averageLength.toInt()} characters)")

        val siteHost = Urls.host(pageUrl)
        if (siteHost != null) {
            val onSite = items.count { Urls.sameSite(it.url, siteHost) }
            if (onSite < items.size * 0.8) found.add("most links point away from $siteHost, so these are not the site's own articles")
        }
        val pageCanonical = Urls.canonical(pageUrl)
        if (items.count { Urls.canonical(it.url) == pageCanonical } > items.size / 2) {
            found.add("most links point back to the listing page itself")
        }
        return found
    }
}

/** One request to a model and what came of it, kept for the model log. */
data class ModelCall(
    val task: String,
    val model: String,
    val system: String,
    val user: String,
    val response: String?,
    val outcome: String,
    val accepted: Boolean,
    val durationMs: Long,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

data class RepairContext(val oldRecipe: Recipe, val oldTitles: List<String>)

data class WriteResult(
    val recipe: Recipe?,
    val items: List<RawItem>,
    val calls: List<ModelCall>,
    /** Why no recipe was produced; null on success. */
    val failure: String?,
)

/**
 * Asks a model to write a recipe for a listing page, runs each answer against
 * the page, and feeds failures back until one passes or attempts run out.
 *
 * The model's only influence is the selector strings: the address is fixed by
 * the caller, and nothing is accepted unless it extracts a plausible article
 * list from the real page.
 */
class RecipeWriter(private val llm: LlmClient, private val settings: ModelSettings) {
    fun write(
        pageUrl: String,
        html: String,
        repair: RepairContext? = null,
        now: Long = System.currentTimeMillis(),
    ): WriteResult {
        val task = if (repair == null) "add" else "repair"
        val reduced = HtmlReducer.reduce(html, pageUrl, settings.htmlBudgetChars)
        val template = if (repair == null) settings.addPrompt else settings.repairPrompt
        val basePrompt = template
            .replace("{url}", pageUrl)
            .replace("{old_recipe}", repair?.let { describe(it.oldRecipe) }.orEmpty())
            .replace("{old_titles}", repair?.oldTitles?.joinToString("\n") { "- $it" }.orEmpty())
            .replace("{html}", reduced)

        val calls = ArrayList<ModelCall>()
        val attempts = settings.maxAttempts.coerceIn(1, 6)
        var feedback = ""
        var lastProblem = "no attempt was made"

        for (attempt in 1..attempts) {
            val useFallback = attempt == attempts && attempts > 1 && settings.fallbackModel.isNotBlank()
            val model = if (useFallback) settings.fallbackModel.trim() else settings.model.trim()
            val user = basePrompt + feedback
            val started = System.nanoTime()
            fun elapsed() = (System.nanoTime() - started) / 1_000_000

            val reply = try {
                llm.complete(LlmRequest(model, settings.systemPrompt, user, settings.temperature, settings.maxTokens, settings.extraBodyJson))
            } catch (e: LlmException) {
                val message = e.message ?: "The model request failed"
                calls.add(ModelCall(task, model, settings.systemPrompt, user, null, "Error: $message", false, elapsed()))
                // A provider error will not be cured by asking again with feedback.
                return WriteResult(null, emptyList(), calls, message)
            }

            val recipe = parse(reply.text, pageUrl)
            val (items, problems) = if (recipe == null) {
                emptyList<RawItem>() to listOf("the reply was not a JSON object with an \"item\" selector")
            } else {
                try {
                    val extracted = RecipeExtractor.extract(Jsoup.parse(html, pageUrl), recipe, now)
                    extracted to RecipeValidator.problems(extracted, pageUrl)
                } catch (e: RecipeException) {
                    emptyList<RawItem>() to listOf(e.message ?: "a selector is invalid")
                }
            }

            val accepted = recipe != null && problems.isEmpty()
            val outcome = if (accepted) "Accepted: ${items.size} articles extracted" else "Rejected: ${problems.joinToString("; ")}"
            calls.add(ModelCall(task, model, settings.systemPrompt, user, reply.text, outcome, accepted, elapsed(), reply.promptTokens, reply.completionTokens))
            if (accepted) return WriteResult(recipe, items, calls, null)

            lastProblem = problems.joinToString("; ")
            feedback = Prompts.RETRY.replace("{answer}", reply.text.take(2000)).replace("{problems}", lastProblem)
        }
        return WriteResult(null, emptyList(), calls, "The model's recipes did not work on the page: $lastProblem")
    }

    companion object {
        private const val MAX_SELECTOR = 300
        private val fields = listOf("item", "title", "link", "summary", "image", "date", "tags", "exclude")

        fun describe(recipe: Recipe): String = buildString {
            append("{")
            append(
                listOf(recipe.item, recipe.title, recipe.link, recipe.summary, recipe.image, recipe.date, recipe.tags, recipe.exclude)
                    .mapIndexed { i, value -> "\"${fields[i]}\": ${JsonPrimitive(value)}" }
                    .joinToString(", ")
            )
            append("}")
        }

        /** Reads the model's reply, tolerating code fences and surrounding prose. Null if unusable. */
        fun parse(reply: String, pageUrl: String): Recipe? {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj: JsonObject = try {
                Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject
            } catch (e: Exception) {
                return null
            }
            fun field(name: String): String =
                (obj[name] as? JsonPrimitive)?.contentOrNull?.trim()?.take(MAX_SELECTOR).orEmpty()
            val item = field("item")
            if (item.isEmpty()) return null
            return Recipe(
                kind = FetchKind.SELECTORS,
                url = pageUrl,
                item = item,
                title = field("title"),
                link = field("link"),
                summary = field("summary"),
                image = field("image"),
                date = field("date"),
                tags = field("tags"),
                exclude = field("exclude"),
            )
        }
    }
}
