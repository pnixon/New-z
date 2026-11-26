package newz.core

import kotlinx.serialization.Serializable

/** One article the user opened: what the app remembers in order to make suggestions. */
@Serializable
data class OpenEvent(val at: Long, val title: String, val topics: List<String>, val sourceName: String)

@Serializable
enum class SuggestionKind { FOLLOW_TOPIC, UNFOLLOW_TOPIC, ADD_TOPIC }

/**
 * A change the app suggests to your topics, with the counts behind it.
 * Nothing changes unless you accept it.
 */
data class Suggestion(
    val kind: SuggestionKind,
    /** The topic concerned, or the name of the topic to create. */
    val topic: String,
    /** Why, in numbers. */
    val reason: String,
) {
    /** Identifies the suggestion so a dismissal is remembered. */
    val key: String get() = "${kind.name}:${topic.lowercase()}"
}

/**
 * Looks at what you opened and suggests topic changes. It is plain counting
 * over a fixed window, so every suggestion can state exactly why it was made.
 */
object Learner {
    const val WINDOW_DAYS = 30
    /** Opens needed before anything is suggested. */
    const val MIN_OPENS = 12
    private const val DAY = 86_400_000L

    fun suggest(
        opens: List<OpenEvent>,
        /** Titles of the articles currently stored, as the baseline for what you were offered. */
        offeredTitles: List<String>,
        topics: List<Topic>,
        dismissed: Set<String>,
        now: Long,
    ): List<Suggestion> {
        val recent = opens.filter { now - it.at <= WINDOW_DAYS * DAY }
        if (recent.size < MIN_OPENS) return emptyList()
        val total = recent.size
        val out = ArrayList<Suggestion>()

        val opensByTopic = HashMap<String, Int>()
        for (event in recent) for (topic in event.topics.distinct()) opensByTopic[topic] = (opensByTopic[topic] ?: 0) + 1

        for (topic in topics) {
            val name = topic.name.trim()
            val count = opensByTopic[name] ?: 0
            if (!topic.followed && count >= 5 && count * 100 >= total * 20) {
                out.add(
                    Suggestion(
                        SuggestionKind.FOLLOW_TOPIC, name,
                        "$count of the $total articles you opened in the last $WINDOW_DAYS days were in $name, and you do not follow it.",
                    )
                )
            }
            if (topic.followed && count == 0 && total >= 2 * MIN_OPENS) {
                out.add(
                    Suggestion(
                        SuggestionKind.UNFOLLOW_TOPIC, name,
                        "You follow $name, but none of the $total articles you opened in the last $WINDOW_DAYS days were in it.",
                    )
                )
            }
        }

        // Words that turn up in what you open far more often than in what you were offered.
        val known = topics.flatMap { t -> t.keywords.flatMap { StoryClusterer.words(it) } + StoryClusterer.words(t.name) }.toSet()
        val openedWith = HashMap<String, Int>()
        for (event in recent) for (word in StoryClusterer.words(event.title)) openedWith[word] = (openedWith[word] ?: 0) + 1
        val offeredWith = HashMap<String, Int>()
        for (title in offeredTitles) for (word in StoryClusterer.words(title)) offeredWith[word] = (offeredWith[word] ?: 0) + 1
        val offeredTotal = offeredTitles.size.coerceAtLeast(1)

        openedWith.entries
            .filter { (word, count) -> count >= 5 && word.length >= 4 && word !in known }
            .mapNotNull { (word, count) ->
                val offered = offeredWith[word] ?: 0
                val shareOpened = count.toDouble() / total
                val shareOffered = offered.toDouble() / offeredTotal
                // At least three times as common in what you opened as in what was on offer.
                if (shareOpened >= 3 * shareOffered.coerceAtLeast(1.0 / offeredTotal)) Triple(word, count, offered) else null
            }
            .sortedByDescending { it.second }
            .take(3)
            .forEach { (word, count, offered) ->
                val name = word.replaceFirstChar { it.uppercase() }
                out.add(
                    Suggestion(
                        SuggestionKind.ADD_TOPIC, name,
                        "\"$word\" was in the headline of $count of the $total articles you opened, but only $offered of the $offeredTotal on offer. " +
                            "Accepting adds a followed topic with that keyword.",
                    )
                )
            }

        return out.filter { it.key !in dismissed }
    }

    /** The topic list after accepting [suggestion]. */
    fun apply(suggestion: Suggestion, topics: List<Topic>): List<Topic> = when (suggestion.kind) {
        SuggestionKind.FOLLOW_TOPIC -> topics.map { if (it.name.trim() == suggestion.topic) it.copy(followed = true) else it }
        SuggestionKind.UNFOLLOW_TOPIC -> topics.map { if (it.name.trim() == suggestion.topic) it.copy(followed = false) else it }
        SuggestionKind.ADD_TOPIC ->
            if (topics.any { it.name.trim().equals(suggestion.topic, ignoreCase = true) }) topics
            else topics + Topic(suggestion.topic, listOf(suggestion.topic.lowercase()), followed = true)
    }
}

/** How a source's articles have fared in the story checks you ran. */
data class TrackRecord(
    val articlesChecked: Int = 0,
    val corroborated: Int = 0,
    val disputed: Int = 0,
    val unconfirmed: Int = 0,
) {
    val claims: Int get() = corroborated + disputed + unconfirmed

    companion object {
        /** Tallies completed checks by the site of the article that was checked. */
        fun bySite(checks: List<FactCheck>): Map<String, TrackRecord> {
            val out = HashMap<String, TrackRecord>()
            for (check in checks) {
                if (check.error != null || check.claims.isEmpty()) continue
                val site = Urls.host(check.articleUrl)?.let { Urls.site(it) } ?: continue
                val old = out[site] ?: TrackRecord()
                out[site] = TrackRecord(
                    articlesChecked = old.articlesChecked + 1,
                    corroborated = old.corroborated + check.claims.count { it.status == ClaimStatus.CORROBORATED },
                    disputed = old.disputed + check.claims.count { it.status == ClaimStatus.DISPUTED },
                    unconfirmed = old.unconfirmed + check.claims.count { it.status == ClaimStatus.UNCONFIRMED },
                )
            }
            return out
        }
    }
}
