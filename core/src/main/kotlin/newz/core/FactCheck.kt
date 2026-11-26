package newz.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

@Serializable
enum class ClaimStatus { CORROBORATED, DISPUTED, UNCONFIRMED }

@Serializable
enum class Stance { SUPPORTS, CONTRADICTS, UNRELATED }

/** One other site's page that a search returned for a claim. */
@Serializable
data class ClaimSource(
    val url: String,
    val title: String,
    val site: String,
    val trust: TrustLevel,
    val stance: Stance,
)

@Serializable
data class ClaimCheck(
    val claim: String,
    val status: ClaimStatus,
    /** The model's one-line reason. */
    val note: String,
    val sources: List<ClaimSource>,
    /** Distinct other sites reporting the same fact. */
    val supportingSites: Int,
    /** How many of those you have marked trusted. */
    val trustedSupportingSites: Int,
    /** The supporting reports look like copies of one original. */
    val sameOrigin: Boolean,
)

/**
 * What a story check found. It shows how widely the article's claims are
 * reported elsewhere; it does not establish that they are true.
 */
@Serializable
data class FactCheck(
    val articleUrl: String,
    val checkedAt: Long,
    val model: String,
    val claims: List<ClaimCheck>,
    val searches: Int = 0,
    val error: String? = null,
)

/**
 * Checks an article against other coverage: a model picks its main claims,
 * each is searched for, and a model labels what the results say. The article's
 * own site and blocked sites are left out of the evidence.
 */
class FactChecker(
    private val llm: LlmClient,
    private val search: (String) -> SearchOutcome,
    private val settings: ModelSettings,
    private val trustOf: (site: String) -> TrustLevel,
) {
    data class Result(val check: FactCheck, val calls: List<ModelCall>, val searches: List<Pair<String, SearchOutcome>>)

    fun check(title: String, text: String, articleUrl: String, now: Long = System.currentTimeMillis()): Result {
        val model = settings.model.trim()
        val calls = ArrayList<ModelCall>()
        val searches = ArrayList<Pair<String, SearchOutcome>>()
        fun failed(message: String) = Result(FactCheck(articleUrl, now, model, emptyList(), searches.size, message), calls, searches)

        // 1. Pick the claims.
        val claimsPrompt = settings.claimsPrompt
            .replace("{max}", settings.maxClaims.coerceIn(1, 8).toString())
            .replace("{title}", title)
            .replace("{text}", text.take(MAX_ARTICLE_CHARS))
        val claimsReply = ask("claims", claimsPrompt, calls) ?: return failed(calls.last().outcome.removePrefix("Error: "))
        val claims = parseClaims(claimsReply).take(settings.maxClaims.coerceIn(1, 8))
        calls[calls.lastIndex] = calls.last().copy(
            outcome = if (claims.isEmpty()) "Rejected: no claims in the reply" else "Accepted: ${claims.size} claims",
            accepted = claims.isNotEmpty(),
        )
        if (claims.isEmpty()) return failed("The model found no checkable claims in this article")

        // 2. Search for each, dropping the article's own site and blocked sites.
        val ownSite = Urls.host(articleUrl)?.let { Urls.site(it) }
        val evidence = claims.map { claim ->
            val outcome = search(claim)
            searches.add(claim to outcome)
            outcome.results
                .mapNotNull { r -> Urls.host(r.url)?.let { Triple(r, Urls.site(it), trustOf(Urls.site(it))) } }
                .filter { (_, site, trust) -> site != ownSite && trust != TrustLevel.BLOCKED }
                .distinctBy { it.second }
                .take(MAX_SOURCES_PER_CLAIM)
        }
        val searchError = searches.firstNotNullOfOrNull { it.second.error }
        if (evidence.all { it.isEmpty() }) {
            return failed(searchError?.let { "Web search failed: $it" } ?: "The searches returned no other coverage to compare with")
        }

        // 3. Have the model label what the results say.
        val listing = claims.mapIndexed { i, claim ->
            buildString {
                append("Claim ${i + 1}: $claim\n")
                if (evidence[i].isEmpty()) append("  (no results)\n")
                evidence[i].forEachIndexed { j, (r, site, _) ->
                    append("  [${j + 1}] $site: ${r.title}")
                    r.snippet?.let { append(" — ${it.take(300)}") }
                    append("\n")
                }
            }
        }.joinToString("\n")
        val compareReply = ask("compare", settings.comparePrompt.replace("{claims}", listing), calls)
            ?: return failed(calls.last().outcome.removePrefix("Error: "))
        val verdicts = parseVerdicts(compareReply)
        calls[calls.lastIndex] = calls.last().copy(
            outcome = if (verdicts.isEmpty()) "Rejected: no verdicts in the reply" else "Accepted: ${verdicts.size} verdicts",
            accepted = verdicts.isNotEmpty(),
        )
        if (verdicts.isEmpty()) return failed("The model's comparison could not be read")

        val checks = claims.mapIndexed { i, claim ->
            val verdict = verdicts[i + 1]
            val sources = evidence[i].mapIndexed { j, (r, site, trust) ->
                val stance = when {
                    verdict != null && (j + 1) in verdict.supports -> Stance.SUPPORTS
                    verdict != null && (j + 1) in verdict.contradicts -> Stance.CONTRADICTS
                    else -> Stance.UNRELATED
                }
                ClaimSource(r.url, r.title, site, trust, stance)
            }
            val supporting = sources.filter { it.stance == Stance.SUPPORTS }
            val contradicting = sources.filter { it.stance == Stance.CONTRADICTS }
            // The status must be backed by listed sources, whatever label the model gave.
            val status = when {
                contradicting.isNotEmpty() && verdict?.status == ClaimStatus.DISPUTED -> ClaimStatus.DISPUTED
                supporting.isNotEmpty() && verdict?.status != ClaimStatus.UNCONFIRMED -> ClaimStatus.CORROBORATED
                contradicting.isNotEmpty() -> ClaimStatus.DISPUTED
                else -> ClaimStatus.UNCONFIRMED
            }
            ClaimCheck(
                claim = claim,
                status = status,
                note = verdict?.note.orEmpty(),
                sources = sources,
                supportingSites = supporting.size,
                trustedSupportingSites = supporting.count { it.trust == TrustLevel.TRUSTED },
                sameOrigin = verdict?.sameOrigin == true && supporting.size > 1,
            )
        }
        return Result(FactCheck(articleUrl, now, model, checks, searches.size, null), calls, searches)
    }

    /** Sends one prompt and logs it. Null when the provider failed; the log entry then holds the error. */
    private fun ask(task: String, user: String, calls: MutableList<ModelCall>): String? {
        val model = settings.model.trim()
        val started = System.nanoTime()
        return try {
            val reply = llm.complete(LlmRequest(model, Prompts.CHECK_SYSTEM, user, settings.temperature, settings.maxTokens, settings.extraBodyJson))
            calls.add(ModelCall(task, model, Prompts.CHECK_SYSTEM, user, reply.text, "Replied", true, (System.nanoTime() - started) / 1_000_000, reply.promptTokens, reply.completionTokens))
            reply.text
        } catch (e: LlmException) {
            calls.add(ModelCall(task, model, Prompts.CHECK_SYSTEM, user, null, "Error: ${e.message}", false, (System.nanoTime() - started) / 1_000_000))
            null
        }
    }

    private class Verdict(val status: ClaimStatus?, val supports: Set<Int>, val contradicts: Set<Int>, val sameOrigin: Boolean, val note: String)

    companion object {
        private const val MAX_ARTICLE_CHARS = 8_000
        private const val MAX_SOURCES_PER_CLAIM = 6

        private fun jsonObject(reply: String): JsonObject? {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { Json.parseToJsonElement(reply.substring(start, end + 1)).jsonObject }.getOrNull()
        }

        fun parseClaims(reply: String): List<String> {
            val list = jsonObject(reply)?.get("claims") as? JsonArray ?: return emptyList()
            return list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.take(300) }.filter { it.length >= 8 }.distinct()
        }

        private fun ints(element: Any?): Set<Int> =
            (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toSet() ?: emptySet()

        private fun parseVerdicts(reply: String): Map<Int, Verdict> {
            val list = jsonObject(reply)?.get("claims") as? JsonArray ?: return emptyMap()
            val out = HashMap<Int, Verdict>()
            list.forEachIndexed { index, element ->
                val o = element as? JsonObject ?: return@forEachIndexed
                val number = (o["claim"] as? JsonPrimitive)?.intOrNull ?: (index + 1)
                val status = when ((o["status"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {
                    "corroborated" -> ClaimStatus.CORROBORATED
                    "disputed" -> ClaimStatus.DISPUTED
                    "unconfirmed" -> ClaimStatus.UNCONFIRMED
                    else -> null
                }
                out[number] = Verdict(
                    status = status,
                    supports = ints(o["supports"]),
                    contradicts = ints(o["contradicts"]),
                    sameOrigin = (o["same_origin"] as? JsonPrimitive)?.booleanOrNull == true,
                    note = (o["note"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(400).orEmpty(),
                )
            }
            return out
        }
    }
}
