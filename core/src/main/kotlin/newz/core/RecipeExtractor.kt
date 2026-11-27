package newz.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class RecipeException(message: String) : Exception(message)

/**
 * Runs a SELECTORS recipe against a listing page.
 *
 * Inside each block matched by `item`:
 *  - title: the `title` selector's text. Blank means the first heading
 *    (h1 to h4) that has text, otherwise the link with the longest text.
 *  - link: the `link` selector's href. Blank means the link the title sits in
 *    or contains, otherwise the first link in the block.
 *  - summary, image, date, tags: only read when a selector is given, except
 *    the image, where blank means the first image in the block.
 */
object RecipeExtractor {
    fun extract(html: String, recipe: Recipe, now: Long = System.currentTimeMillis()): List<RawItem> =
        extract(Jsoup.parse(html, recipe.url), recipe, now)

    fun extract(doc: Document, recipe: Recipe, now: Long = System.currentTimeMillis()): List<RawItem> {
        if (recipe.item.isBlank()) throw RecipeException("The recipe has no item selector")
        try {
            if (recipe.exclude.isNotBlank()) doc.select(recipe.exclude).remove()
            val seen = HashSet<String>()
            return doc.select(recipe.item).mapNotNull { block ->
                val titleEl = titleElement(block, recipe) ?: return@mapNotNull null
                val title = titleEl.text().trim()
                val href = linkElement(block, titleEl, recipe)?.attr("href") ?: return@mapNotNull null
                val url = Urls.resolve(recipe.url, href) ?: return@mapNotNull null
                if (title.isEmpty() || !seen.add(Urls.canonical(url))) return@mapNotNull null

                RawItem(
                    url = url,
                    title = title,
                    summary = recipe.summary.takeIf { it.isNotBlank() }
                        ?.let { block.selectFirst(it)?.text()?.trim() }?.take(500)?.ifEmpty { null },
                    imageUrl = imageOf(block, recipe),
                    publishedAt = recipe.date.takeIf { it.isNotBlank() }
                        ?.let { block.selectFirst(it) }
                        ?.let { el -> Dates.parse(el.attr("datetime").ifEmpty { el.text() }, now) },
                    tags = recipe.tags.takeIf { it.isNotBlank() }
                        ?.let { sel -> block.select(sel).map { it.text().trim() } }
                        ?.filter { it.length in 2..40 }
                        ?.distinctBy { it.lowercase() }
                        ?.take(8)
                        ?: emptyList(),
                )
            }
        } catch (e: org.jsoup.select.Selector.SelectorParseException) {
            throw RecipeException("Invalid selector: ${e.message}")
        }
    }

    private fun titleElement(block: Element, recipe: Recipe): Element? {
        if (recipe.title.isNotBlank()) return block.selectFirst(recipe.title)
        block.select("h1, h2, h3, h4").firstOrNull { it.text().isNotBlank() }?.let { return it }
        val links = block.select("a[href]").toMutableList()
        if (block.tagName() == "a" && block.hasAttr("href")) links.add(block)
        return links.maxByOrNull { it.text().trim().length }?.takeIf { it.text().isNotBlank() }
    }

    private fun linkElement(block: Element, titleEl: Element, recipe: Recipe): Element? {
        if (recipe.link.isNotBlank()) return block.selectFirst(recipe.link)
        if (titleEl.tagName() == "a" && titleEl.hasAttr("href")) return titleEl
        titleEl.selectFirst("a[href]")?.let { return it }
        var up: Element? = titleEl.parent()
        while (up != null) {
            if (up.tagName() == "a" && up.hasAttr("href")) return up
            if (up === block) break
            up = up.parent()
        }
        if (block.tagName() == "a" && block.hasAttr("href")) return block
        return block.selectFirst("a[href]")
    }

    private fun imageOf(block: Element, recipe: Recipe): String? {
        val img = if (recipe.image.isNotBlank()) block.selectFirst(recipe.image) else block.selectFirst("img")
        img ?: return null
        val raw = listOf("src", "data-src", "data-lazy-src", "data-original")
            .map { img.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            ?: img.attr("srcset").ifBlank { img.attr("data-srcset") }
                .split(",").firstOrNull()?.trim()?.substringBefore(" ")?.ifBlank { null }
            ?: return null
        return Urls.resolve(recipe.url, raw)
    }
}
