package newz.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything that decides what the app shows, as one readable file:
 * the ranking numbers, the rules, the model settings and prompts, and every
 * source with its recipe and trust.
 */
@Serializable
data class AppConfig(
    val version: Int = 1,
    val ranking: RankingConfig = RankingConfig(),
    /** Remote model settings and prompts. The API key is never part of this file. */
    val models: ModelSettings = ModelSettings(),
    /** The web search connector used by topic searches. The API key is never part of this file. */
    val search: SearchSettings = SearchSettings(),
    /** Where your shared-articles feed is published. Secrets and the signing key are never part of this file. */
    val publish: PublishSettings = PublishSettings(),
    /** The in-app browser, filter lists and your own blocking rules. */
    val browser: BrowserSettings = BrowserSettings(),
    val sources: List<SourceConfig> = emptyList(),
)

object ConfigJson {
    val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun encode(config: AppConfig): String = json.encodeToString(AppConfig.serializer(), config)
    fun decode(text: String): AppConfig = json.decodeFromString(AppConfig.serializer(), text)

    fun encodeRanking(config: RankingConfig): String = json.encodeToString(RankingConfig.serializer(), config)
    fun decodeRanking(text: String): RankingConfig = json.decodeFromString(RankingConfig.serializer(), text)

    fun encodeModels(settings: ModelSettings): String = json.encodeToString(ModelSettings.serializer(), settings)
    fun decodeModels(text: String): ModelSettings = json.decodeFromString(ModelSettings.serializer(), text)

    fun encodeRecipe(recipe: Recipe): String = json.encodeToString(Recipe.serializer(), recipe)
    fun decodeRecipe(text: String): Recipe = json.decodeFromString(Recipe.serializer(), text)
}

/** The sources a fresh install starts with. All are the outlets' own public feeds. */
object DefaultSources {
    val all = listOf(
        feed("NPR", "https://www.npr.org/", "https://feeds.npr.org/1001/rss.xml"),
        feed("BBC News", "https://www.bbc.com/news", "https://feeds.bbci.co.uk/news/rss.xml"),
        feed("PBS NewsHour", "https://www.pbs.org/newshour/", "https://www.pbs.org/newshour/feeds/rss/headlines"),
        feed("The Guardian", "https://www.theguardian.com/world", "https://www.theguardian.com/world/rss"),
    )

    private fun feed(name: String, home: String, url: String) =
        SourceConfig(name = name, homeUrl = home, recipe = Recipe(kind = FetchKind.FEED, url = url))
}
