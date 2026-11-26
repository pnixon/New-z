package newz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NOW = 1_795_000_000_000L
private const val DAY = 86_400_000L

class LearningTest {
    private val topics = listOf(
        Topic("Science", listOf("space", "nasa")),
        Topic("Sports", listOf("playoff"), followed = true),
        Topic("Politics", listOf("senate")),
    )

    private fun opens(n: Int, title: (Int) -> String, topics: List<String> = emptyList(), daysAgo: Int = 1) =
        (1..n).map { OpenEvent(NOW - daysAgo * DAY - it, title(it), topics, "NPR") }

    private val offered = (1..200).map { "Ordinary headline number $it about assorted matters" }

    @Test
    fun nothingIsSuggestedFromTooFewOpens() {
        val few = opens(Learner.MIN_OPENS - 1, { "Rover update $it" }, listOf("Science"))
        assertEquals(emptyList(), Learner.suggest(few, offered, topics, emptySet(), NOW))
    }

    @Test
    fun suggestsFollowingATopicYouKeepOpening() {
        val events = opens(8, { "Rover update $it" }, listOf("Science")) + opens(8, { "Assorted item $it" })
        val suggestions = Learner.suggest(events, offered, topics, emptySet(), NOW)
        val follow = suggestions.single { it.kind == SuggestionKind.FOLLOW_TOPIC }
        assertEquals("Science", follow.topic)
        assertEquals("8 of the 16 articles you opened in the last 30 days were in Science, and you do not follow it.", follow.reason)
        assertTrue(Learner.apply(follow, topics).single { it.name == "Science" }.followed)
        // Politics was never opened and is not followed: nothing to say about it.
        assertFalse(suggestions.any { it.topic == "Politics" })
    }

    @Test
    fun suggestsUnfollowingATopicYouNeverOpenOnlyWithEnoughEvidence() {
        val some = opens(Learner.MIN_OPENS, { "Assorted item $it" })
        assertFalse(Learner.suggest(some, offered, topics, emptySet(), NOW).any { it.kind == SuggestionKind.UNFOLLOW_TOPIC })

        val many = opens(2 * Learner.MIN_OPENS, { "Assorted item $it" })
        val unfollow = Learner.suggest(many, offered, topics, emptySet(), NOW).single { it.kind == SuggestionKind.UNFOLLOW_TOPIC }
        assertEquals("Sports", unfollow.topic)
        assertFalse(Learner.apply(unfollow, topics).single { it.name == "Sports" }.followed)
    }

    @Test
    fun suggestsANewTopicFromWordsOverRepresentedInWhatYouOpen() {
        // The other opens use words that are just as common in what was on offer.
        val events = opens(7, { "Volcano headline number $it" }) + opens(9, { "Ordinary headline number $it" })
        val withSome = offered + listOf("Volcano erupts", "Quiet volcano")
        val add = Learner.suggest(events, withSome, topics, emptySet(), NOW).first { it.kind == SuggestionKind.ADD_TOPIC }
        assertEquals("Volcano", add.topic)
        assertTrue("7 of the 16 articles you opened" in add.reason && "only 2 of the 202 on offer" in add.reason, add.reason)

        val updated = Learner.apply(add, topics)
        assertEquals(Topic("Volcano", listOf("volcano"), followed = true), updated.last())
        assertEquals(updated, Learner.apply(add, updated), "accepting twice adds it once")
    }

    @Test
    fun wordsAlreadyCoveredOrCommonEverywhereAreNotSuggested() {
        // "space" is a Science keyword; "ordinary" is in every offered headline.
        val events = opens(8, { "Space station news $it" }) + opens(8, { "Ordinary story $it" })
        val added = Learner.suggest(events, offered, topics, emptySet(), NOW).filter { it.kind == SuggestionKind.ADD_TOPIC }.map { it.topic }
        assertFalse("Space" in added)
        assertFalse("Ordinary" in added)
    }

    @Test
    fun oldOpensAndDismissedSuggestionsAreIgnored() {
        val old = opens(20, { "Rover update $it" }, listOf("Science"), daysAgo = 45)
        assertEquals(emptyList(), Learner.suggest(old, offered, topics, emptySet(), NOW))

        val events = opens(8, { "Rover update $it" }, listOf("Science")) + opens(8, { "Assorted item $it" })
        val key = Learner.suggest(events, offered, topics, emptySet(), NOW).single { it.kind == SuggestionKind.FOLLOW_TOPIC }.key
        assertEquals("FOLLOW_TOPIC:science", key)
        assertFalse(Learner.suggest(events, offered, topics, setOf(key), NOW).any { it.kind == SuggestionKind.FOLLOW_TOPIC })
    }

    @Test
    fun trackRecordTalliesCompletedChecksBySite() {
        fun claim(status: ClaimStatus) = ClaimCheck("c", status, "", emptyList(), 0, 0, false)
        val checks = listOf(
            FactCheck("https://www.npr.org/a", NOW, "m", listOf(claim(ClaimStatus.CORROBORATED), claim(ClaimStatus.CORROBORATED), claim(ClaimStatus.UNCONFIRMED))),
            FactCheck("https://text.npr.org/b", NOW, "m", listOf(claim(ClaimStatus.DISPUTED))),
            FactCheck("https://tabloid.example.com/c", NOW, "m", listOf(claim(ClaimStatus.DISPUTED), claim(ClaimStatus.UNCONFIRMED))),
            FactCheck("https://www.npr.org/failed", NOW, "m", emptyList(), error = "HTTP 401"),
        )
        val records = TrackRecord.bySite(checks)
        assertEquals(TrackRecord(articlesChecked = 2, corroborated = 2, disputed = 1, unconfirmed = 1), records["npr.org"])
        assertEquals(4, records.getValue("npr.org").claims)
        assertEquals(TrackRecord(1, 0, 1, 1), records["example.com"])
        assertEquals(2, records.size, "a failed check counts for nothing")
    }
}
