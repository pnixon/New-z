package newz.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

data class ParsedFeed(val title: String?, val items: List<RawItem>)

/** Reads RSS 2.0, RSS 1.0 (RDF) and Atom. */
object FeedParser {
    fun looksLikeFeed(body: String): Boolean {
        val head = body.take(2000).lowercase()
        return "<rss" in head || "<feed" in head || "<rdf:rdf" in head
    }

    fun parse(xml: String, baseUrl: String, now: Long = System.currentTimeMillis()): ParsedFeed {
        val doc = Jsoup.parse(xml, baseUrl, Parser.xmlParser())
        val entries = doc.getAllElements().filter { it.named("item") || it.named("entry") }
        val channel = doc.getAllElements().firstOrNull { it.named("channel") || it.named("feed") }
        val feedTitle = channel?.child("title")?.text()?.trim()?.ifEmpty { null }

        val seen = HashSet<String>()
        val items = entries.mapNotNull { entry ->
            val title = entry.child("title")?.let { plain(it.text()) }.orEmpty()
            val link = linkOf(entry, baseUrl) ?: return@mapNotNull null
            if (title.isEmpty() || !seen.add(Urls.canonical(link))) return@mapNotNull null

            val bodyHtml = entry.child("description", "summary")?.text()
                ?: entry.child("content:encoded", "content")?.text()
            val date = entry.child("pubDate", "published", "dc:date", "updated", "date")?.text()
            RawItem(
                url = link,
                title = title,
                summary = bodyHtml?.let { plain(it) }?.take(500)?.ifEmpty { null },
                imageUrl = imageOf(entry, baseUrl),
                publishedAt = Dates.parse(date, now),
                tags = tagsOf(entry),
            )
        }
        return ParsedFeed(feedTitle, items)
    }

    private fun Element.named(name: String) = tagName().equals(name, ignoreCase = true)

    private fun Element.child(vararg names: String): Element? {
        for (name in names) {
            children().firstOrNull { it.named(name) }?.let { return it }
        }
        return null
    }

    private fun linkOf(entry: Element, baseUrl: String): String? {
        val links = entry.children().filter { it.named("link") }
        // Atom: <link rel="alternate" href="..."/>. RSS: <link>...</link>.
        val atom = links.firstOrNull { it.hasAttr("href") && it.attr("rel").let { r -> r.isEmpty() || r == "alternate" } }
            ?: links.firstOrNull { it.hasAttr("href") }
        val raw = atom?.attr("href")
            ?: links.firstOrNull()?.text()
            ?: entry.child("guid")?.takeIf { it.attr("isPermaLink") != "false" }?.text()
            ?: return null
        return Urls.resolve(baseUrl, raw)
    }

    /** The feed's own categories: RSS <category>, Atom <category term="...">, <dc:subject>. */
    private fun tagsOf(entry: Element): List<String> =
        entry.children()
            .filter { it.named("category") || it.named("dc:subject") }
            .flatMap { el -> el.attr("term").ifBlank { plain(el.text()) }.split(Regex("\\s*[,;/|]\\s*")) }
            .map { it.trim() }
            .filter { it.length in 2..40 }
            .distinctBy { it.lowercase() }
            .take(12)

    private fun imageOf(entry: Element, baseUrl: String): String? {
        val media = entry.getAllElements().firstOrNull {
            (it.named("media:thumbnail") || it.named("media:content")) && it.hasAttr("url") &&
                (it.attr("medium").isEmpty() || it.attr("medium") == "image") &&
                (it.attr("type").isEmpty() || it.attr("type").startsWith("image"))
        }?.attr("url")
        val enclosure = entry.children().firstOrNull {
            it.named("enclosure") && it.attr("type").startsWith("image")
        }?.attr("url")
        val inline = entry.child("content:encoded", "content", "description", "summary")?.text()
            ?.takeIf { "<img" in it }
            ?.let { Jsoup.parseBodyFragment(it).selectFirst("img[src]")?.attr("src") }
        val raw = media ?: enclosure ?: inline ?: return null
        return Urls.resolve(baseUrl, raw)
    }

    /** Strips markup from a title or description. */
    private fun plain(html: String): String =
        if ('<' in html || '&' in html) Jsoup.parseBodyFragment(html).text().trim() else html.trim()
}
