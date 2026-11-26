package newz.core

import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class FilterList(val name: String, val url: String, val enabled: Boolean = true)

/** In-app browser and blocking settings. All user-editable. */
@Serializable
data class BrowserSettings(
    /** Open articles in the app's own browser instead of the phone's. */
    val openInApp: Boolean = true,
    /** Block requests to hosts on the filter lists. */
    val blockRequests: Boolean = true,
    /** Hide leftover ad elements using the lists' element rules. */
    val hideElements: Boolean = true,
    val filterLists: List<FilterList> = DEFAULT_LISTS,
    /** Your own rules, one per line, in the same syntax as the lists. */
    val customRules: String = "",
    /** Sites where blocking is switched off. */
    val allowedSites: List<String> = emptyList(),
    /** How often the lists are downloaded again. */
    val updateDays: Int = 7,
) {
    companion object {
        val DEFAULT_LISTS = listOf(
            FilterList("EasyList", "https://easylist.to/easylist/easylist.txt"),
            FilterList("EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt"),
            FilterList("Peter Lowe's list", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"),
        )
    }
}

/** A filter list's text, with the name shown when one of its rules blocks something. */
data class FilterSource(val name: String, val text: String)

/** Why a request was blocked: the rule as written in the list, and which list. */
data class BlockDecision(val rule: String, val list: String)

/**
 * Blocks requests and hides elements using standard filter lists.
 *
 * Understood: host rules (`||ads.example.com^`), host-and-path rules
 * (`||example.com/ads/`), the `third-party` option, hosts-file lines, plain
 * host lines, host exceptions (`@@||example.com^`), and element-hiding rules
 * (`##.ad`, `example.com##.ad`). Rules that need more than this (regular
 * expressions, wildcards in the middle, per-site options, scriptlets) are
 * skipped and counted, so the numbers shown to the user are honest.
 */
class AdBlocker private constructor(
    private val hosts: Map<String, HostRule>,
    private val paths: Map<String, List<PathRule>>,
    private val allowed: Set<String>,
    private val genericSelectors: List<String>,
    private val siteSelectors: Map<String, List<String>>,
    val skippedRules: Int,
) {
    private class HostRule(val rule: String, val list: String, val thirdPartyOnly: Boolean)
    private class PathRule(val prefix: String, val rule: String, val list: String, val thirdPartyOnly: Boolean)

    val requestRuleCount: Int get() = hosts.size + paths.values.sumOf { it.size }
    val elementRuleCount: Int get() = genericSelectors.size + siteSelectors.values.sumOf { it.size }

    /** The rule blocking [requestUrl] when loaded by [pageUrl], or null if it is allowed. */
    fun check(requestUrl: String, pageUrl: String?): BlockDecision? {
        val uri = try {
            URI(requestUrl)
        } catch (e: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        val pageHost = pageUrl?.let { Urls.host(it) }
        val thirdParty = pageHost == null || Urls.site(host) != Urls.site(pageHost)

        val chain = suffixes(host)
        if (chain.any { it in allowed }) return null
        for (h in chain) {
            hosts[h]?.let { if (!it.thirdPartyOnly || thirdParty) return BlockDecision(it.rule, it.list) }
            val rules = paths[h] ?: continue
            val target = (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
            for (r in rules) {
                if ((!r.thirdPartyOnly || thirdParty) && target.startsWith(r.prefix)) return BlockDecision(r.rule, r.list)
            }
        }
        return null
    }

    /** A stylesheet hiding ad elements on a page of [pageHost]. Empty when there is nothing to hide. */
    fun elementHidingCss(pageHost: String?): String {
        val selectors = ArrayList<String>(genericSelectors.size + 16)
        selectors.addAll(genericSelectors)
        if (pageHost != null) for (h in suffixes(pageHost.lowercase().removePrefix("www."))) siteSelectors[h]?.let { selectors.addAll(it) }
        if (selectors.isEmpty()) return ""
        // Small groups, so one selector the browser rejects only loses its own group.
        return selectors.chunked(20).joinToString("\n") { it.joinToString(",") + "{display:none!important}" }
    }

    companion object {
        /** A few of the largest ad and tracking hosts, so blocking works before any list is downloaded. */
        val BUILT_IN = FilterSource(
            "Built in",
            listOf(
                "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
                "googletagmanager.com", "googletagservices.com", "adservice.google.com", "amazon-adsystem.com",
                "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "pubmatic.com",
                "rubiconproject.com", "openx.net", "scorecardresearch.com", "quantserve.com", "moatads.com",
                "adsrvr.org", "casalemedia.com", "chartbeat.com", "chartbeat.net", "hotjar.com",
                "facebook.net", "connect.facebook.net", "ads-twitter.com", "bidswitch.net", "smartadserver.com",
                "media.net", "zedo.com", "teads.tv", "sharethrough.com", "3lift.com", "indexww.com",
            ).joinToString("\n") { "||$it^\$third-party" },
        )

        private val hostPattern = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")
        private val hostsLine = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1)\\s+(\\S+)")
        private val typeOptions = setOf(
            "script", "image", "stylesheet", "xmlhttprequest", "xhr", "subdocument", "object", "media", "font",
            "ping", "websocket", "other", "all", "important", "document", "doc", "popup",
        )
        private val unsupportedSelectorParts = listOf(
            "+js(", ":-abp-", ":contains(", ":has-text(", ":xpath(", ":matches-", ":style(", ":remove(", ":upward(",
            ":min-text-length(", ":watch-attr(", ":others(", "{", "}", "<",
        )
        private const val MAX_GENERIC_SELECTORS = 15_000

        private fun suffixes(host: String): List<String> {
            val out = ArrayList<String>(4)
            var h = host
            while (true) {
                out.add(h)
                val dot = h.indexOf('.')
                if (dot < 0 || h.indexOf('.', dot + 1) < 0) break
                h = h.substring(dot + 1)
            }
            return out
        }

        /** Later sources do not override earlier ones; the first rule for a host is the one reported. */
        fun build(sources: List<FilterSource>): AdBlocker {
            val hosts = HashMap<String, HostRule>()
            val paths = HashMap<String, MutableList<PathRule>>()
            val allowed = HashSet<String>()
            val generic = LinkedHashSet<String>()
            val site = HashMap<String, MutableList<String>>()
            var skipped = 0

            for (source in sources) {
                for (raw in source.text.lineSequence()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue

                    // Element hiding: [domains]##selector
                    val hash = line.indexOf("##")
                    if (hash >= 0 && !line.startsWith("||") && !line.startsWith("@@")) {
                        val selector = line.substring(hash + 2).trim()
                        if (selector.isEmpty() || unsupportedSelectorParts.any { it in selector }) {
                            skipped++
                            continue
                        }
                        val domains = line.substring(0, hash).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        when {
                            domains.isEmpty() -> if (generic.size < MAX_GENERIC_SELECTORS) generic.add(selector) else skipped++
                            domains.any { it.startsWith("~") } -> skipped++
                            else -> domains.forEach { d -> site.getOrPut(d.removePrefix("www.")) { ArrayList() }.add(selector) }
                        }
                        continue
                    }
                    if ("#@#" in line || "#?#" in line || "#$#" in line || "#%#" in line) {
                        skipped++
                        continue
                    }
                    if (line[0] == '#') continue // comment in a hosts file

                    hostsLine.find(line)?.let { m ->
                        val h = m.groupValues[1].lowercase()
                        if (h != "localhost" && hostPattern.matches(h)) hosts.putIfAbsent(h, HostRule(line, source.name, false))
                        continue
                    }
                    if (hostsLine.containsMatchIn(line)) continue

                    if (line.startsWith("@@||")) {
                        val body = line.substring(4).substringBefore('$')
                        val h = body.trimEnd('^', '|').lowercase()
                        if (hostPattern.matches(h)) allowed.add(h) else skipped++
                        continue
                    }

                    if (line.startsWith("||")) {
                        val dollar = line.lastIndexOf('$')
                        val pattern = (if (dollar > 2) line.substring(2, dollar) else line.substring(2))
                        val options = if (dollar > 2) line.substring(dollar + 1).split(',').map { it.trim().lowercase() } else emptyList()

                        var thirdParty = false
                        var usable = true
                        for (o in options) {
                            when {
                                o == "third-party" || o == "3p" -> thirdParty = true
                                o in typeOptions -> Unit
                                else -> usable = false
                            }
                        }
                        // A rule that only concerns pop-ups or whole pages does not apply to page resources.
                        if (options.isNotEmpty() && options.all { it == "popup" || it == "document" || it == "doc" }) usable = false

                        val end = pattern.indexOfFirst { it == '^' || it == '/' || it == '*' || it == '|' }
                        val h = (if (end < 0) pattern else pattern.substring(0, end)).lowercase()
                        val rest = if (end < 0) "" else pattern.substring(end)
                        if (!usable || !hostPattern.matches(h)) {
                            skipped++
                            continue
                        }
                        if (rest.isEmpty() || rest == "^" || rest == "^|" || rest == "/" || rest == "^*") {
                            hosts.putIfAbsent(h, HostRule(line, source.name, thirdParty))
                            continue
                        }
                        val path = rest.removePrefix("^")
                        val cut = path.indexOfFirst { it == '*' || it == '^' || it == '|' }
                        // Only a plain prefix is supported; a wildcard part-way through would over-block.
                        if (!path.startsWith("/") || (cut >= 0 && cut < path.length - 1) ) {
                            skipped++
                            continue
                        }
                        val prefix = if (cut >= 0) path.substring(0, cut) else path
                        if (prefix.length < 2) {
                            skipped++
                            continue
                        }
                        paths.getOrPut(h) { ArrayList() }.add(PathRule(prefix, line, source.name, thirdParty))
                        continue
                    }

                    val bare = line.lowercase()
                    if (hostPattern.matches(bare)) hosts.putIfAbsent(bare, HostRule(line, source.name, false)) else skipped++
                }
            }
            return AdBlocker(hosts, paths, allowed, generic.toList(), site, skipped)
        }
    }
}
