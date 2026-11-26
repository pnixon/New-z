package newz.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** A guess at where a page's article list is, with what it would extract. */
data class RecipeCandidate(val recipe: Recipe, val items: List<RawItem>, val score: Double)

/**
 * Finds a listing page's article list without a model.
 *
 * It looks for headline-like links on the page's own site, takes the largest
 * element around each that holds no other headline (the article's "block"),
 * and groups blocks that share a tag and classes. The groups with the most
 * headline text are returned as recipes, best first.
 */
object HeuristicDetector {
    private const val MIN_HEADLINE_CHARS = 25
    private const val MIN_ITEMS = 4
    private val safeClass = Regex("[A-Za-z_][A-Za-z0-9_-]*")

    fun detect(html: String, pageUrl: String, now: Long = System.currentTimeMillis(), limit: Int = 3): List<RecipeCandidate> {
        val doc = Jsoup.parse(html, pageUrl)
        doc.select("script, style, noscript, template, nav, footer, form, svg").remove()
        val body = doc.body()
        val siteHost = Urls.host(pageUrl) ?: return emptyList()
        val pageCanonical = Urls.canonical(pageUrl)

        // Headline-like links, keyed by the article they point to.
        val anchors = LinkedHashMap<Element, String>()
        for (a in body.select("a[href]")) {
            val url = Urls.resolve(pageUrl, a.attr("href")) ?: continue
            if (!Urls.sameSite(url, siteHost)) continue
            val canonical = Urls.canonical(url)
            if (canonical == pageCanonical) continue
            if (a.text().trim().length >= MIN_HEADLINE_CHARS) anchors[a] = canonical
        }
        if (anchors.size < MIN_ITEMS) return emptyList()

        // Which article links each element contains.
        val linksUnder = HashMap<Element, MutableSet<String>>()
        for ((a, url) in anchors) {
            var el: Element? = a
            while (el != null && el !== body) {
                linksUnder.getOrPut(el) { HashSet() }.add(url)
                el = el.parent()
            }
        }

        // The block for a link is the largest element holding only that link.
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (a in anchors.keys) {
            var block: Element = a
            while (true) {
                val parent = block.parent() ?: break
                if (parent === body || (linksUnder[parent]?.size ?: 0) > 1) break
                block = parent
            }
            groups.getOrPut(selectorFor(block)) { ArrayList() }.add(block)
        }

        return groups.entries
            .filter { it.value.size >= MIN_ITEMS }
            .mapNotNull { (selector, _) ->
                val recipe = Recipe(kind = FetchKind.SELECTORS, url = pageUrl, item = selector)
                val items = try {
                    RecipeExtractor.extract(doc.clone(), recipe, now)
                } catch (e: RecipeException) {
                    return@mapNotNull null
                }
                if (items.size < MIN_ITEMS) return@mapNotNull null
                val headlineChars = items.sumOf { minOf(it.title.length, 120) }
                RecipeCandidate(recipe, items, headlineChars.toDouble())
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun selectorFor(el: Element): String {
        val own = simple(el)
        if ('.' in own) return own
        val parent = el.parent() ?: return own
        if (parent.tagName() == "body") return own
        return "${simple(parent)} > $own"
    }

    private fun simple(el: Element): String {
        val classes = el.classNames().filter { safeClass.matches(it) }.take(2)
        return el.tagName() + classes.joinToString("") { ".$it" }
    }
}
