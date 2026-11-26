package newz.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Shrinks a page to what a model needs to write selectors: the tag, class and
 * id skeleton with short text. Scripts and styling go, attributes other than
 * the useful few go, text is truncated, and long runs of look-alike siblings
 * are cut to the first few.
 */
object HtmlReducer {
    private val keepAttributes = setOf("class", "id", "href", "src", "datetime", "data-src", "role", "itemprop", "rel")
    private const val MAX_TEXT = 80
    private const val MAX_ATTRIBUTE = 120
    private const val KEEP_SIBLINGS = 5

    fun reduce(html: String, pageUrl: String, budgetChars: Int = 60_000): String {
        val doc = Jsoup.parse(html, pageUrl)
        doc.outputSettings(Document.OutputSettings().prettyPrint(false))
        doc.select("script, style, noscript, template, svg, iframe, link, meta, head, picture > source, video, audio, canvas").remove()
        val body = doc.body()
        clean(body)
        trimRuns(body)
        val out = body.html().replace(Regex("\\s{2,}"), " ")
        if (out.length <= budgetChars) return out
        // Cut at the last tag boundary inside the budget.
        val cut = out.lastIndexOf('>', budgetChars.coerceAtLeast(1) - 1)
        return out.substring(0, if (cut > 0) cut + 1 else budgetChars) + "<!-- page cut here to fit the size limit -->"
    }

    private fun clean(element: Element) {
        for (node in element.childNodes().toList()) {
            when (node) {
                is Comment -> node.remove()
                is TextNode -> {
                    val text = node.text().trim()
                    if (text.isEmpty()) node.remove()
                    else if (text.length > MAX_TEXT) node.text(text.take(MAX_TEXT) + "…")
                }
                is Element -> {
                    for (attribute in node.attributes().asList().toList()) {
                        val key = attribute.key.lowercase()
                        when {
                            key !in keepAttributes -> node.removeAttr(attribute.key)
                            key == "src" && attribute.value.startsWith("data:") -> node.attr(attribute.key, "data:…")
                            attribute.value.length > MAX_ATTRIBUTE -> node.attr(attribute.key, attribute.value.take(MAX_ATTRIBUTE) + "…")
                        }
                    }
                    clean(node)
                }
                else -> node.remove()
            }
        }
    }

    private fun signature(element: Element) = element.tagName() + "." + element.className()

    /** Keeps the first few of each run of consecutive siblings that share a tag and classes. */
    private fun trimRuns(element: Element) {
        val children = element.children().toList()
        var i = 0
        while (i < children.size) {
            var j = i
            while (j + 1 < children.size && signature(children[j + 1]) == signature(children[i])) j++
            val run = j - i + 1
            if (run > KEEP_SIBLINGS) {
                val dropped = run - KEEP_SIBLINGS
                val marker: Node = Comment(" $dropped more <${signature(children[i]).trimEnd('.')}> elements like the ones above ")
                children[i + KEEP_SIBLINGS - 1].after(marker)
                for (k in i + KEEP_SIBLINGS..j) children[k].remove()
            }
            i = j + 1
        }
        for (child in element.children()) trimRuns(child)
    }
}
