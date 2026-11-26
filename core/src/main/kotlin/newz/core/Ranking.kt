package newz.core

import kotlinx.serialization.Serializable
import kotlin.math.pow

@Serializable
enum class RuleField {
    TITLE, SOURCE, URL,
    /** The article's topics and the source's own tags. */
    TAG,
    /** Title, source name or address. */
    ANY,
}

@Serializable
enum class RuleAction { ADD_POINTS, HIDE }

/**
 * A user rule: when [field] contains [contains] (ignoring case), either add
 * [points] to the score (negative buries) or hide the article.
 */
@Serializable
data class Rule(
    val name: String,
    val field: RuleField = RuleField.ANY,
    val contains: String,
    val action: RuleAction = RuleAction.ADD_POINTS,
    val points: Double = 1.0,
    val enabled: Boolean = true,
)

/**
 * A topic is a name and a list of keywords. An article belongs to it when any
 * keyword appears as a whole word or phrase (ignoring case) in the title, the
 * summary, or the source's own tags.
 */
@Serializable
data class Topic(val name: String, val keywords: List<String>)

/** A topic an article was put in, and the keyword that put it there. */
data class TopicMatch(val topic: String, val keyword: String)

object Topics {
    val DEFAULT = listOf(
        Topic("World", listOf("world", "international", "foreign", "united nations", "diplomat", "embassy", "europe", "asia", "africa", "middle east", "latin america")),
        Topic("Politics", listOf("politics", "election", "congress", "senate", "parliament", "president", "prime minister", "white house", "lawmakers", "supreme court", "campaign", "ballot", "legislation")),
        Topic("Business", listOf("business", "economy", "economic", "markets", "stocks", "inflation", "interest rates", "earnings", "trade", "tariff", "jobs report", "bank", "merger")),
        Topic("Technology", listOf("technology", "tech", "software", "artificial intelligence", "ai", "chip", "cybersecurity", "hack", "smartphone", "startup", "social media", "data breach")),
        Topic("Science", listOf("science", "scientists", "research", "study", "space", "nasa", "physics", "astronomy", "fossil", "telescope", "biology")),
        Topic("Health", listOf("health", "medical", "medicine", "hospital", "disease", "vaccine", "virus", "cancer", "mental health", "drug", "outbreak")),
        Topic("Climate", listOf("climate", "environment", "emissions", "global warming", "wildfire", "drought", "flood", "hurricane", "renewable", "carbon", "pollution")),
        Topic("Sports", listOf("sport", "sports", "football", "soccer", "basketball", "baseball", "tennis", "olympics", "championship", "league", "world cup", "playoff")),
        Topic("Culture", listOf("culture", "arts", "music", "film", "movie", "television", "book", "books", "theatre", "theater", "museum", "celebrity", "album")),
    )

    /** Finds each topic's first matching keyword in an article's text. */
    class Matcher(topics: List<Topic>) {
        private val compiled = topics
            .filter { it.name.isNotBlank() }
            .map { topic ->
                val words = topic.keywords.map { it.trim() }.filter { it.isNotEmpty() }
                topic.name.trim() to if (words.isEmpty()) null else Regex(
                    "(?<![\\p{L}\\p{Nd}])(?:" + words.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{Nd}])",
                    RegexOption.IGNORE_CASE,
                )
            }

        fun match(item: RankItem): List<TopicMatch> {
            val text = buildString {
                append(item.title)
                item.summary?.let { append(" \n ").append(it) }
                item.tags.forEach { append(" \n ").append(it) }
            }
            return compiled.mapNotNull { (name, regex) ->
                regex?.find(text)?.let { TopicMatch(name, it.value.lowercase()) }
            }
        }
    }
}

/**
 * Every number the front page order depends on.
 *
 * score = recencyWeight  x 0.5^(age in hours / recencyHalfLifeHours)
 *       + trustWeight    x trustValues[source trust level]
 *       + coverageWeight x min(other sources with the story, coverageSaturation) / coverageSaturation
 *       + readWeight     x (1 if already read, else 0)
 *       + points from each matching rule
 */
@Serializable
data class RankingConfig(
    val recencyWeight: Double = 1.0,
    val recencyHalfLifeHours: Double = 12.0,
    val trustWeight: Double = 0.5,
    val trustValues: Map<TrustLevel, Double> = mapOf(
        TrustLevel.TRUSTED to 1.0,
        TrustLevel.NEUTRAL to 0.5,
        TrustLevel.UNRATED to 0.3,
        TrustLevel.LOW to 0.0,
    ),
    val coverageWeight: Double = 0.3,
    val coverageSaturation: Int = 3,
    val readWeight: Double = -0.5,
    /** Articles scoring below this are hidden. Null means no cut-off. */
    val minScore: Double? = null,
    /** Articles older than this are hidden. */
    val maxAgeDays: Int = 7,
    /** Show one article per story, the highest scoring. */
    val collapseDuplicates: Boolean = true,
    /** Share of headline words two articles must have in common to count as one story (0 to 1). */
    val duplicateSimilarity: Double = 0.6,
    val rules: List<Rule> = emptyList(),
    /** The topics articles are sorted into, for filtering and for rules. */
    val topics: List<Topic> = Topics.DEFAULT,
) {
    companion object {
        val DEFAULT = RankingConfig()

        /** Newest first and nothing else. */
        val CHRONOLOGICAL = RankingConfig(
            recencyHalfLifeHours = 24.0,
            trustWeight = 0.0,
            coverageWeight = 0.0,
            readWeight = 0.0,
            collapseDuplicates = false,
        )
    }
}

data class RankItem(
    val id: Long,
    val title: String,
    val url: String,
    val sourceId: Long,
    val sourceName: String,
    val trust: TrustLevel,
    /** Publish time, or when the app first saw it if the source gives none. */
    val timestamp: Long,
    val read: Boolean,
    val summary: String? = null,
    /** The source's own categories for the article. */
    val tags: List<String> = emptyList(),
)

/** One line of the score: [points] = [raw] x [weight]. */
data class Contribution(
    val signal: String,
    val detail: String,
    val raw: Double,
    val weight: Double,
    val points: Double,
)

data class Scored(
    val item: RankItem,
    val total: Double,
    val contributions: List<Contribution>,
    /** Why the article is not on the front page; null when it is shown. */
    val hiddenReason: String? = null,
    /** Other sources carrying the same story. */
    val alsoReportedBy: List<String> = emptyList(),
    /** The topics the article was put in, each with the keyword that matched. */
    val topics: List<TopicMatch> = emptyList(),
)

data class RankResult(val visible: List<Scored>, val hidden: List<Scored>)

/** Orders the front page. Pure and deterministic: the same inputs give the same order. */
object Ranker {
    private const val HOUR = 3_600_000.0

    fun rank(items: List<RankItem>, config: RankingConfig, now: Long): RankResult {
        val hidden = ArrayList<Scored>()
        val candidates = ArrayList<RankItem>()
        val activeRules = config.rules.filter { it.enabled && it.contains.isNotBlank() }
        val maxAgeMillis = config.maxAgeDays * 24 * HOUR

        val matcher = Topics.Matcher(config.topics)
        val topicsOf = HashMap<Long, List<TopicMatch>>(items.size * 2)
        for (item in items) topicsOf[item.id] = matcher.match(item)

        for (item in items) {
            val itemTopics = topicsOf[item.id].orEmpty()
            val hideRule = activeRules.firstOrNull { it.action == RuleAction.HIDE && matches(it, item, itemTopics) }
            val reason = when {
                item.trust == TrustLevel.BLOCKED -> "${item.sourceName} is blocked"
                hideRule != null -> "Hidden by your rule \"${hideRule.name}\""
                now - item.timestamp > maxAgeMillis -> "Older than ${config.maxAgeDays} days"
                else -> null
            }
            if (reason != null) hidden.add(Scored(item, 0.0, emptyList(), reason, topics = itemTopics)) else candidates.add(item)
        }

        val clusters = StoryClusterer.cluster(candidates, config.duplicateSimilarity)
        val scored = ArrayList<Scored>()
        for (cluster in clusters) {
            val scoredCluster = cluster.map { item ->
                val others = cluster.filter { it.sourceId != item.sourceId }.map { it.sourceName }.distinct().sorted()
                score(item, others, topicsOf[item.id].orEmpty(), config, activeRules, now)
            }
            if (config.collapseDuplicates && scoredCluster.size > 1) {
                val ordered = scoredCluster.sortedWith(order)
                val lead = ordered.first()
                scored.add(lead)
                for (other in ordered.drop(1)) {
                    hidden.add(
                        other.copy(
                            hiddenReason = "Same story as \"${lead.item.title}\" from ${lead.item.sourceName}, which scored higher",
                        )
                    )
                }
            } else {
                scored.addAll(scoredCluster)
            }
        }

        val visible = ArrayList<Scored>()
        val minScore = config.minScore
        for (s in scored) {
            if (minScore != null && s.total < minScore) {
                hidden.add(s.copy(hiddenReason = "Score ${fmt(s.total)} is below your cut-off of ${fmt(minScore)}"))
            } else {
                visible.add(s)
            }
        }
        return RankResult(visible.sortedWith(order), hidden.sortedByDescending { it.item.timestamp })
    }

    private val order = compareByDescending<Scored> { it.total }
        .thenByDescending { it.item.timestamp }
        .thenBy { it.item.id }

    private fun score(
        item: RankItem,
        otherSources: List<String>,
        topics: List<TopicMatch>,
        config: RankingConfig,
        rules: List<Rule>,
        now: Long,
    ): Scored {
        val parts = ArrayList<Contribution>()

        val ageHours = ((now - item.timestamp).coerceAtLeast(0)) / HOUR
        val recency = 0.5.pow(ageHours / config.recencyHalfLifeHours.coerceAtLeast(0.01))
        parts.add(
            Contribution(
                "Recency",
                "${fmt(ageHours)} hours old, halving every ${fmt(config.recencyHalfLifeHours)} hours",
                recency, config.recencyWeight, recency * config.recencyWeight,
            )
        )

        val trust = config.trustValues[item.trust] ?: 0.0
        parts.add(
            Contribution(
                "Source trust",
                "${item.sourceName} is ${item.trust.name.lowercase()}",
                trust, config.trustWeight, trust * config.trustWeight,
            )
        )

        val saturation = config.coverageSaturation.coerceAtLeast(1)
        val coverage = minOf(otherSources.size, saturation).toDouble() / saturation
        parts.add(
            Contribution(
                "Coverage",
                if (otherSources.isEmpty()) "No other source of yours has this story"
                else "Also reported by ${otherSources.joinToString(", ")}",
                coverage, config.coverageWeight, coverage * config.coverageWeight,
            )
        )

        val read = if (item.read) 1.0 else 0.0
        parts.add(
            Contribution(
                "Already read",
                if (item.read) "You opened this" else "Not opened yet",
                read, config.readWeight, read * config.readWeight,
            )
        )

        for (rule in rules) {
            if (rule.action == RuleAction.ADD_POINTS && matches(rule, item, topics)) {
                parts.add(
                    Contribution(
                        "Rule: ${rule.name}",
                        (if (rule.field == RuleField.TAG) "topics or tags contain" else "${rule.field.name.lowercase()} contains") +
                            " \"${rule.contains}\"",
                        1.0, rule.points, rule.points,
                    )
                )
            }
        }
        return Scored(item, parts.sumOf { it.points }, parts, alsoReportedBy = otherSources, topics = topics)
    }

    private fun matches(rule: Rule, item: RankItem, topics: List<TopicMatch>): Boolean {
        val needle = rule.contains.trim()
        fun has(text: String) = text.contains(needle, ignoreCase = true)
        return when (rule.field) {
            RuleField.TITLE -> has(item.title)
            RuleField.SOURCE -> has(item.sourceName)
            RuleField.URL -> has(item.url)
            RuleField.TAG -> topics.any { has(it.topic) } || item.tags.any { has(it) }
            RuleField.ANY -> has(item.title) || has(item.sourceName) || has(item.url)
        }
    }

    fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}

/**
 * Groups articles that are the same story, judged by shared headline words
 * (Jaccard similarity) among articles published within two days of each other.
 */
object StoryClusterer {
    private const val WINDOW_MILLIS = 48 * 3_600_000L
    private val stopwords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "has", "have", "had",
        "but", "not", "you", "his", "her", "its", "their", "they", "who", "what", "when", "why", "how",
        "will", "says", "say", "said", "after", "over", "into", "new", "more", "than", "about", "amid", "as",
    )
    private val wordSplit = Regex("[^\\p{L}\\p{Nd}]+")

    fun words(title: String): Set<String> =
        title.lowercase().split(wordSplit).filter { it.length >= 3 && it !in stopwords }.toSet()

    fun cluster(items: List<RankItem>, similarity: Double): List<List<RankItem>> {
        val n = items.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        if (similarity > 0.0 && similarity <= 1.0) {
            val wordSets = items.map { words(it.title) }
            val index = HashMap<String, MutableList<Int>>()
            for (i in 0 until n) {
                val shared = HashMap<Int, Int>()
                for (w in wordSets[i]) {
                    val postings = index.getOrPut(w) { ArrayList() }
                    for (j in postings) shared[j] = (shared[j] ?: 0) + 1
                    postings.add(i)
                }
                for ((j, count) in shared) {
                    if (wordSets[i].size < 3 || wordSets[j].size < 3) continue
                    if (kotlin.math.abs(items[i].timestamp - items[j].timestamp) > WINDOW_MILLIS) continue
                    val union = wordSets[i].size + wordSets[j].size - count
                    if (count.toDouble() / union >= similarity) parent[find(i)] = find(j)
                }
            }
        }

        val groups = LinkedHashMap<Int, MutableList<RankItem>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { ArrayList() }.add(items[i])
        return groups.values.toList()
    }
}
