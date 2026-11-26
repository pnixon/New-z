package newz.core

import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

data class ReaderArticle(val title: String, val byline: String?, val contentHtml: String, val textLength: Int)

/** Pulls the article out of a page and renders it as a plain, script-free page. */
object Reader {
    private const val MIN_TEXT = 300

    /** Null when the page has no article-sized body of text. */
    fun extract(html: String, url: String): ReaderArticle? {
        val article = try {
            Readability4J(url, html).parse()
        } catch (e: Exception) {
            return null
        }
        val content = article.content ?: return null
        val text = article.textContent?.trim().orEmpty()
        if (text.length < MIN_TEXT) return null
        // Keep text, links and images; drop scripts, frames, forms and inline handlers.
        val safe = Jsoup.clean(content, url, Safelist.relaxed().preserveRelativeLinks(false))
        return ReaderArticle(
            title = article.title?.trim().orEmpty(),
            byline = article.byline?.trim()?.ifEmpty { null },
            contentHtml = safe,
            textLength = text.length,
        )
    }

    fun page(article: ReaderArticle, sourceUrl: String, dark: Boolean): String {
        val background = if (dark) "#121212" else "#fdfcf9"
        val text = if (dark) "#e4e2dd" else "#1c1b1a"
        val muted = if (dark) "#a09d96" else "#66625b"
        val link = if (dark) "#8ab4f8" else "#1a56b0"
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{margin:0;padding:20px 20px 64px;background:$background;color:$text;font:19px/1.6 Georgia,'Noto Serif',serif;overflow-wrap:break-word}
main{max-width:42em;margin:0 auto}
h1{font-size:1.5em;line-height:1.25;margin:0 0 .3em}
h2,h3{line-height:1.3}
.meta{color:$muted;font:14px/1.4 sans-serif;margin-bottom:1.5em}
a{color:$link}
img,video{max-width:100%;height:auto;border-radius:6px}
figure{margin:1.2em 0}
figcaption{color:$muted;font:14px/1.4 sans-serif}
blockquote{margin:1em 0;padding-left:1em;border-left:3px solid $muted;color:$muted}
pre{overflow-x:auto}
</style></head><body><main>
<h1>${esc(article.title)}</h1>
<div class="meta">${article.byline?.let { esc(it) + " · " }.orEmpty()}${esc(Urls.host(sourceUrl) ?: sourceUrl)}</div>
${article.contentHtml}
</main></body></html>"""
    }
}
