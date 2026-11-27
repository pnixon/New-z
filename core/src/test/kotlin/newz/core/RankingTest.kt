package newz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L
private const val NOW = 1_795_000_000_000L

class RankingTest {
    private var nextId = 1L

    private fun item(
        title: String,
        source: String = "NPR",
        hoursOld: Double = 1.0,
        trust: TrustLevel = TrustLevel.NEUTRAL,
        read: Boolean = false,
        summary: String? = null,
        tags: List<String> = emptyList(),
        storyCheck: CheckSummary? = null,
    ) = RankItem(
        id = nextId++,
        title = title,
        url = "https://${source.lowercase().replace(" ", "")}.example.com/${nextId}",
        sourceId = source.hashCode().toLong(),
        sourceName = source,
        trust = trust,
        timestamp = NOW - (hoursOld * HOUR).toLong(),
        read = read,
        summary = summary,
        tags = tags,
        storyCheck = storyCheck,
    )

    private fun close(expected: Double, actual: Double) =
        assertTrue(kotlin.math.abs(expected - actual) < 1e-9, "expected $expected but was $actual")

    @Test
    fun scoreIsTheSumOfItsVisibleParts() {
        val config = RankingConfig()
        val result = Ranker.rank(listOf(item("City council approves transit plan", hoursOld = 12.0, read = true)), config, NOW)
        val scored = result.visible.single()

        val byName = scored.contributions.associateBy { it.signal }
        close(0.5, byName.getValue("Recency").raw) // one half-life
        close(0.5 * config.recencyWeight, byName.getValue("Recency").points)
        close(0.5 * config.trustWeight, byName.getValue("Source trust").points)
        close(0.0, byName.getValue("Coverage").points)
        close(config.readWeight, byName.getValue("Already read").points)
        close(scored.contributions.sumOf { it.points }, scored.total)
        for (c in scored.contributions) close(c.raw * c.weight, c.points)
    }

    @Test
    fun chronologicalPresetIsNewestFirst() {
        val items = listOf(
            item("Oldest story about farming subsidies", hoursOld = 30.0, trust = TrustLevel.TRUSTED),
            item("Newest story about harbour dredging", hoursOld = 1.0, trust = TrustLevel.LOW, read = true),
            item("Middle story about museum funding", hoursOld = 10.0),
        )
        val result = Ranker.rank(items, RankingConfig.CHRONOLOGICAL, NOW)
        assertEquals(listOf(1.0, 10.0, 30.0), result.visible.map { (NOW - it.item.timestamp).toDouble() / HOUR })
    }

    @Test
    fun trustBreaksATieBetweenEquallyFreshArticles() {
        val low = item("Regional election results announced tonight", source = "Tabloid", trust = TrustLevel.LOW)
        val trusted = item("Scientists publish ocean temperature survey", source = "NPR", trust = TrustLevel.TRUSTED)
        val result = Ranker.rank(listOf(low, trusted), RankingConfig(), NOW)
        assertEquals(listOf("NPR", "Tabloid"), result.visible.map { it.item.sourceName })
    }

    @Test
    fun blockedSourcesAndHideRulesLandInFilteredWithAReason() {
        val config = RankingConfig(rules = listOf(Rule("No celebrity news", RuleField.TITLE, "celebrity", RuleAction.HIDE)))
        val items = listOf(
            item("Budget vote scheduled for Friday morning"),
            item("Celebrity chef opens new restaurant downtown"),
            item("Anything at all from this outlet today", source = "Spam Daily", trust = TrustLevel.BLOCKED),
            item("Ancient story from the archives resurfaces", hoursOld = 24.0 * 9),
        )
        val result = Ranker.rank(items, config, NOW)
        assertEquals(1, result.visible.size)
        val reasons = result.hidden.associate { it.item.title to it.hiddenReason }
        assertEquals("Hidden by your rule \"No celebrity news\"", reasons["Celebrity chef opens new restaurant downtown"])
        assertEquals("Spam Daily is blocked", reasons["Anything at all from this outlet today"])
        assertEquals("Older than 7 days", reasons["Ancient story from the archives resurfaces"])
    }

    @Test
    fun pointRulesAppearAsNamedContributions() {
        val config = RankingConfig(
            rules = listOf(
                Rule("Boost climate", RuleField.TITLE, "climate", points = 2.0),
                Rule("Bury sports", RuleField.TITLE, "playoff", points = -3.0),
                Rule("Disabled", RuleField.ANY, "climate", points = 50.0, enabled = false),
            )
        )
        val result = Ranker.rank(
            listOf(item("Playoff schedule released for the season"), item("New CLIMATE report warns of flooding")),
            config, NOW,
        )
        val top = result.visible.first()
        assertEquals("New CLIMATE report warns of flooding", top.item.title)
        assertEquals(listOf("Rule: Boost climate"), top.contributions.filter { it.signal.startsWith("Rule") }.map { it.signal })
        close(-3.0, result.visible.last().contributions.single { it.signal == "Rule: Bury sports" }.points)
    }

    @Test
    fun sameStoryIsCollapsedToTheHighestScorerAndCountsAsCoverage() {
        val npr = item("Senate passes budget bill, avoiding government shutdown", source = "NPR", trust = TrustLevel.TRUSTED)
        val bbc = item("Senate passes budget bill to avoid government shutdown", source = "BBC News")
        val other = item("Volcano erupts in Iceland forcing evacuations", source = "BBC News")
        val result = Ranker.rank(listOf(bbc, other, npr), RankingConfig(), NOW)

        assertEquals(2, result.visible.size)
        val lead = result.visible.first()
        assertEquals("NPR", lead.item.sourceName)
        assertEquals(listOf("BBC News"), lead.alsoReportedBy)
        close(1.0 / 3 * 0.3, lead.contributions.single { it.signal == "Coverage" }.points)

        val dup = result.hidden.single()
        assertEquals("BBC News", dup.item.sourceName)
        assertNotNull(dup.hiddenReason)
        assertTrue(dup.hiddenReason!!.startsWith("Same story as"))
        assertTrue(dup.contributions.isNotEmpty(), "a collapsed duplicate still shows its score")
    }

    @Test
    fun duplicatesStayVisibleWhenCollapsingIsOff() {
        val a = item("Senate passes budget bill, avoiding government shutdown", source = "NPR")
        val b = item("Senate passes budget bill to avoid government shutdown", source = "BBC News")
        val result = Ranker.rank(listOf(a, b), RankingConfig(collapseDuplicates = false), NOW)
        assertEquals(2, result.visible.size)
        assertTrue(result.visible.all { it.alsoReportedBy.size == 1 })
    }

    @Test
    fun unrelatedHeadlinesSharingAWordAreNotMerged() {
        val a = item("Senate passes budget bill after long debate", source = "NPR")
        val b = item("Senate hearing examines airline safety rules", source = "BBC News")
        assertEquals(2, Ranker.rank(listOf(a, b), RankingConfig(), NOW).visible.size)
    }

    @Test
    fun cutOffHidesLowScoresAndSaysSo() {
        val result = Ranker.rank(
            listOf(item("Fresh report on housing costs released", hoursOld = 0.0), item("Stale report on parking fees released", hoursOld = 96.0)),
            RankingConfig(minScore = 0.5), NOW,
        )
        assertEquals(1, result.visible.size)
        assertTrue(result.hidden.single().hiddenReason!!.contains("below your cut-off of 0.50"))
    }

    @Test
    fun orderIsDeterministic() {
        val items = (1..40).map { item("Distinct headline $it covering topic number $it", hoursOld = (it % 5).toDouble()) }
        val a = Ranker.rank(items, RankingConfig(), NOW).visible.map { it.item.id }
        val b = Ranker.rank(items.shuffled(java.util.Random(7)), RankingConfig(), NOW).visible.map { it.item.id }
        assertEquals(a, b)
    }

    @Test
    fun configSurvivesExportAndImport() {
        val config = AppConfig(
            ranking = RankingConfig(minScore = 0.2, rules = listOf(Rule("r", RuleField.SOURCE, "npr", RuleAction.HIDE))),
            sources = DefaultSources.all,
        )
        val text = ConfigJson.encode(config)
        assertTrue("\"recencyHalfLifeHours\"" in text)
        assertEquals(config, ConfigJson.decode(text))
        // A hand-edited file may omit fields and carry unknown ones.
        val partial = ConfigJson.decode("""{"ranking": {"trustWeight": 2.0, "futureField": 1}}""")
        assertEquals(2.0, partial.ranking.trustWeight)
        assertEquals(RankingConfig().recencyWeight, partial.ranking.recencyWeight)
    }

    @Test
    fun topicsMatchWholeWordsInTitleSummaryAndTags() {
        val items = listOf(
            item("Senate votes on the new budget"),
            item("A quiet day at the party", summary = "Nothing about art or the smart home"),
            item("Rover lands safely", tags = listOf("Space", "NASA")),
            item("Regulators question AI chip exports", summary = "The trade rules change next month"),
        )
        val scored = Ranker.rank(items, RankingConfig(), NOW).visible.associateBy { it.item.title }

        assertEquals(listOf(TopicMatch("Politics", "senate")), scored.getValue("Senate votes on the new budget").topics)
        // "party", "art" and "smart" contain no keyword as a whole word.
        assertEquals(emptyList(), scored.getValue("A quiet day at the party").topics)
        assertEquals(listOf("Science"), scored.getValue("Rover lands safely").topics.map { it.topic })
        assertEquals(
            listOf("Business" to "trade", "Technology" to "ai"),
            scored.getValue("Regulators question AI chip exports").topics.map { it.topic to it.keyword },
        )
    }

    @Test
    fun editedTopicsReclassifyImmediately() {
        val config = RankingConfig(topics = listOf(Topic("Local", listOf("city council", "transit")), Topic("Empty", emptyList())))
        val scored = Ranker.rank(
            listOf(item("City Council approves transit plan"), item("Senate votes on the new budget")), config, NOW,
        ).visible.associateBy { it.item.title }
        assertEquals(listOf(TopicMatch("Local", "city council")), scored.getValue("City Council approves transit plan").topics)
        assertEquals(emptyList(), scored.getValue("Senate votes on the new budget").topics)
    }

    @Test
    fun rulesCanTargetTopicsAndTags() {
        val config = RankingConfig(
            rules = listOf(
                Rule("No sports", RuleField.TAG, "sports", RuleAction.HIDE),
                Rule("More space", RuleField.TAG, "nasa", points = 2.0),
            )
        )
        val result = Ranker.rank(
            listOf(
                item("Playoff schedule released for the season"),
                item("Rover lands safely", tags = listOf("NASA")),
                item("Budget vote scheduled for Friday morning"),
            ),
            config, NOW,
        )
        assertEquals("Rover lands safely", result.visible.first().item.title)
        assertEquals("topics or tags contain \"nasa\"", result.visible.first().contributions.single { it.signal == "Rule: More space" }.detail)
        val hidden = result.hidden.single()
        assertEquals("Hidden by your rule \"No sports\"", hidden.hiddenReason)
        assertEquals(listOf("Sports"), hidden.topics.map { it.topic }, "hidden articles still show their topics")
    }

    @Test
    fun topicsTravelInTheConfigFile() {
        val config = AppConfig(ranking = RankingConfig(topics = listOf(Topic("Local", listOf("transit")))))
        assertEquals(config, ConfigJson.decode(ConfigJson.encode(config)))
        // A file saved before topics existed gets the defaults.
        assertEquals(Topics.DEFAULT, ConfigJson.decode("""{"ranking": {"trustWeight": 1.0}}""").ranking.topics)
    }

    @Test
    fun followedTopicsAddTheInterestWeight() {
        val topics = listOf(Topic("Space", listOf("rover", "nasa"), followed = true), Topic("Sports", listOf("playoff")))
        val config = RankingConfig(topics = topics, interestWeight = 0.75)
        val result = Ranker.rank(
            listOf(item("Playoff schedule released for the season"), item("Rover lands safely on the plain")), config, NOW,
        )
        val rover = result.visible.first()
        assertEquals("Rover lands safely on the plain", rover.item.title)
        val interest = rover.contributions.single { it.signal == "Interest" }
        close(0.75, interest.points)
        assertEquals("In topics you follow: Space", interest.detail)
        val other = result.visible.last().contributions.single { it.signal == "Interest" }
        close(0.0, other.points)
        assertEquals("In none of the topics you follow", other.detail)
        // With nothing followed the line is still shown, and says why it is zero.
        val none = Ranker.rank(listOf(item("Rover lands safely on the plain")), RankingConfig(), NOW).visible.single()
        assertEquals("You follow no topics yet", none.contributions.single { it.signal == "Interest" }.detail)
    }

    @Test
    fun anArticleFoundByATopicsSearchBelongsToThatTopic() {
        val topics = listOf(Topic("Rust language", listOf("rustlang"), followed = true, search = true))
        val found = item("Borrow checker gets faster in the next release", tags = listOf(Topics.SEARCH_TAG_PREFIX + "Rust language"))
        val scored = Ranker.rank(listOf(found), RankingConfig(topics = topics), NOW).visible.single()
        assertEquals(listOf(TopicMatch("Rust language", "found by this topic's web search")), scored.topics)
        close(RankingConfig().interestWeight, scored.contributions.single { it.signal == "Interest" }.points)
        assertEquals("Rust language", topics[0].query)
        assertEquals("rust 2026", topics[0].copy(searchQuery = " rust 2026 ").query)
    }

    @Test
    fun aSiteThatIsOnlySearchedIsNeverFetched() {
        val source = SourceConfig("Found", "https://found.example.com/", Recipe(FetchKind.NONE, "https://found.example.com/"))
        val outcome = SourceFetcher.fetch(source, { _, _ -> error("must not fetch") }, now = NOW)
        assertTrue(outcome is FetchOutcome.Failed)
    }

    @Test
    fun aStoryCheckMovesAnArticleByItsOutcome() {
        val config = RankingConfig(storyCheckWeight = 0.6)
        val backed = item("Bridge reopens after repairs finish early", storyCheck = CheckSummary(corroborated = 3, disputed = 0, unconfirmed = 0))
        val unchecked = item("Harbour dredging begins next month")
        val contradicted = item("Mayor resigns over parking dispute", storyCheck = CheckSummary(corroborated = 0, disputed = 1, unconfirmed = 1))
        val result = Ranker.rank(listOf(contradicted, unchecked, backed), config, NOW)
        assertEquals(listOf(backed.id, unchecked.id, contradicted.id), result.visible.map { it.item.id })

        fun line(s: Scored) = s.contributions.single { it.signal == "Story check" }
        close(0.6, line(result.visible[0]).points)
        assertEquals("Of 3 claims: 3 reported elsewhere, 0 contradicted, 0 not found", line(result.visible[0]).detail)
        close(0.0, line(result.visible[1]).points)
        assertEquals("You have not checked this story", line(result.visible[1]).detail)
        close(-0.3, line(result.visible[2]).points)
    }

    @Test
    fun checkSummaryIgnoresFailedChecks() {
        fun claim(status: ClaimStatus) = ClaimCheck("c", status, "", emptyList(), 0, 0, false)
        val ok = FactCheck("https://a.example.com/x", NOW, "m", listOf(claim(ClaimStatus.CORROBORATED), claim(ClaimStatus.UNCONFIRMED)))
        assertEquals(CheckSummary(1, 0, 1), CheckSummary.of(ok))
        close(0.5, CheckSummary.of(ok)!!.value)
        assertEquals(null, CheckSummary.of(ok.copy(error = "HTTP 401")))
        assertEquals(null, CheckSummary.of(ok.copy(claims = emptyList())))
    }
}
