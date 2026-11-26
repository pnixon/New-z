package newz.core

import kotlinx.serialization.Serializable

/** How much the user trusts a source. The user sets this; the app never changes it. */
@Serializable
enum class TrustLevel { TRUSTED, NEUTRAL, LOW, UNRATED, BLOCKED }

@Serializable
enum class FetchKind { FEED, SELECTORS }

/**
 * How to turn a source into articles.
 *
 * FEED: [url] is an RSS or Atom feed and the selector fields are unused.
 * SELECTORS: [url] is a listing page. [item] selects one block per article.
 * The other selectors are evaluated inside each block; a blank one means
 * "work it out" (see [RecipeExtractor]).
 */
@Serializable
data class Recipe(
    val kind: FetchKind,
    val url: String,
    val item: String = "",
    val title: String = "",
    val link: String = "",
    val summary: String = "",
    val image: String = "",
    val date: String = "",
    /** Elements matching this are removed from the page before extraction. */
    val exclude: String = "",
)

@Serializable
data class SourceConfig(
    val name: String,
    val homeUrl: String,
    val recipe: Recipe,
    val trust: TrustLevel = TrustLevel.NEUTRAL,
    val enabled: Boolean = true,
    val refreshMinutes: Int = 60,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** One article as extracted from a source, before storage. */
data class RawItem(
    val url: String,
    val title: String,
    val summary: String? = null,
    val imageUrl: String? = null,
    val publishedAt: Long? = null,
)
