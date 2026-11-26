package newz.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Everything about remote model use except the API key, which is stored
 * separately and never exported. All of it is user-editable.
 */
@Serializable
data class ModelSettings(
    /** Any OpenAI-compatible endpoint. */
    val baseUrl: String = "https://openrouter.ai/api/v1",
    /** Model ID exactly as the provider lists it. Blank means remote models are off. */
    val model: String = "",
    /** Used for the last attempt when the first model keeps failing. Blank means no escalation. */
    val fallbackModel: String = "",
    val temperature: Double = 0.0,
    val maxTokens: Int = 1000,
    /** How many times to ask for a recipe before giving up. */
    val maxAttempts: Int = 3,
    /** A JSON object merged into every request body; its keys win over the app's. */
    val extraBodyJson: String = "{}",
    /** How much simplified page HTML is sent, in characters. */
    val htmlBudgetChars: Int = 60_000,
    val systemPrompt: String = Prompts.SYSTEM,
    val addPrompt: String = Prompts.ADD,
    val repairPrompt: String = Prompts.REPAIR,
    /** Ask the model for a new recipe when a scraped source breaks. */
    val autoRepair: Boolean = true,
    /** Wait at least this long between repair attempts on one source. */
    val repairIntervalHours: Int = 24,
) {
    val enabled: Boolean get() = model.isNotBlank() && baseUrl.isNotBlank()
}

object Prompts {
    const val SYSTEM =
        "You write scraping recipes for a news reader. You are given the simplified HTML of a news site's " +
            "listing page. Reply with one JSON object and nothing else."

    private const val FIELDS = """Reply with a JSON object with these string fields, each a CSS selector:
- "item": required. Matches one element per article in the main article list.
- "title": inside the item, the headline element. Empty string if the item's first heading is right.
- "link": inside the item, the <a> that links to the article. Empty string if it is the headline's link.
- "summary": inside the item, the teaser text. Empty string if there is none.
- "image": inside the item, the <img>. Empty string to take the first image.
- "date": inside the item, the element holding the publish time. Empty string if there is none.
- "exclude": elements to delete from the page first, such as sponsored blocks. Empty string if none.

Ignore navigation, footers, "most read" lists, adverts and sponsored or promoted items.
Use only tags, classes, ids and attributes that appear in the HTML. Prefer short, stable selectors and avoid class names that look machine-generated.
The HTML has been shortened: text is truncated and long runs of similar elements are cut, marked by comments.

HTML:
{html}"""

    const val ADD = "Page address: {url}\n\nFind the repeating element that wraps one news article each (headline plus link).\n\n$FIELDS"

    const val REPAIR = "Page address: {url}\n\n" +
        "This recipe used to work on the page and now matches nothing, probably because the site changed its layout:\n" +
        "{old_recipe}\n\nHeadlines it used to extract, to show the kind of block to look for:\n{old_titles}\n\n" +
        "Write a new recipe for the page as it is now.\n\n$FIELDS"

    const val RETRY = "\n\nYour previous answer was:\n{answer}\n\n" +
        "It was run against the page and rejected: {problems}\nReply with a corrected JSON object."
}

data class LlmRequest(
    val model: String,
    val system: String,
    val user: String,
    val temperature: Double,
    val maxTokens: Int,
    val extraBodyJson: String = "{}",
)

data class LlmReply(val text: String, val promptTokens: Int? = null, val completionTokens: Int? = null)

class LlmException(message: String) : Exception(message)

fun interface LlmClient {
    /** Blocking. Throws [LlmException] with a message fit to show the user. */
    fun complete(request: LlmRequest): LlmReply
}

/** Talks to any OpenAI-compatible chat completions endpoint with the user's own key. */
class OpenAiCompatClient(
    private val poster: HttpPoster,
    private val baseUrl: String,
    private val apiKey: String,
) : LlmClient {
    private val root = baseUrl.trim().trimEnd('/')

    private fun headers(): Map<String, String> =
        if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${apiKey.trim()}")

    override fun complete(request: LlmRequest): LlmReply {
        val body = buildBody(request).toString()
        val response = try {
            poster.postJson("$root/chat/completions", headers(), body)
        } catch (e: Exception) {
            throw LlmException("Could not reach the model provider: ${e.message ?: e.javaClass.simpleName}")
        }
        return parseReply(response)
    }

    /** The model IDs the provider offers, sorted. */
    fun listModels(fetcher: HttpFetcher): List<String> {
        val response = try {
            fetcher.get("$root/models", headers())
        } catch (e: Exception) {
            throw LlmException("Could not reach the model provider: ${e.message ?: e.javaClass.simpleName}")
        }
        if (response.code !in 200..299) throw LlmException(errorMessage(response))
        return try {
            val parsed = Json.parseToJsonElement(response.body)
            val list = (parsed as? JsonObject)?.get("data") as? JsonArray ?: parsed as? JsonArray ?: JsonArray(emptyList())
            list.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }.sorted()
        } catch (e: Exception) {
            throw LlmException("The provider's model list could not be read")
        }
    }

    companion object {
        fun buildBody(request: LlmRequest): JsonObject {
            val base = buildJsonObject {
                put("model", request.model)
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", request.system) })
                    add(buildJsonObject { put("role", "user"); put("content", request.user) })
                })
                put("temperature", request.temperature)
                put("max_tokens", request.maxTokens)
            }
            val extra = parseExtra(request.extraBodyJson)
            return JsonObject(base + extra)
        }

        /** The user's extra request fields. Throws [LlmException] if they are not a JSON object. */
        fun parseExtra(text: String): JsonObject {
            if (text.isBlank()) return JsonObject(emptyMap())
            return try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw LlmException("The extra request fields are not a valid JSON object")
            }
        }

        fun parseReply(response: HttpResponse): LlmReply {
            if (response.code !in 200..299) throw LlmException(errorMessage(response))
            try {
                val root = Json.parseToJsonElement(response.body).jsonObject
                root["error"]?.let { throw LlmException(errorText(it as? JsonObject) ?: "The provider reported an error") }
                val message = root.getValue("choices").jsonArray.first().jsonObject.getValue("message").jsonObject
                val content = message["content"]
                val text = when (content) {
                    is JsonPrimitive -> content.contentOrNull.orEmpty()
                    is JsonArray -> content.joinToString("") { part ->
                        (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    }
                    else -> ""
                }
                val usage = root["usage"] as? JsonObject
                return LlmReply(
                    text = text,
                    promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull,
                    completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull,
                )
            } catch (e: LlmException) {
                throw e
            } catch (e: Exception) {
                throw LlmException("The provider's reply was not in the expected format")
            }
        }

        private fun errorMessage(response: HttpResponse): String {
            val detail = runCatching {
                errorText(Json.parseToJsonElement(response.body).jsonObject["error"] as? JsonObject)
            }.getOrNull()
            return "HTTP ${response.code}" + (detail?.let { ": $it" } ?: "")
        }

        private fun errorText(error: JsonObject?): String? =
            error?.get("message")?.jsonPrimitive?.contentOrNull?.take(300)
    }
}
