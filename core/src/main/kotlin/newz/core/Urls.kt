package newz.core

import java.net.URI

object Urls {
    private val trackingParams = setOf(
        "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid",
        "at_medium", "at_campaign", "ref_src", "cmpid", "ocid",
    )

    /** Resolves [href] against [base]; null if the result is not an http(s) URL. */
    fun resolve(base: String, href: String): String? {
        val trimmed = href.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        return try {
            val resolved = URI(base).resolve(URI(trimmed.replace(" ", "%20")))
            if (resolved.scheme == "http" || resolved.scheme == "https") resolved.toString() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The form used to recognise the same article fetched twice: fragment and
     * tracking parameters removed, host lower-cased.
     */
    fun canonical(url: String): String {
        return try {
            val uri = URI(url.trim())
            val host = uri.host?.lowercase() ?: return url.trim()
            val query = uri.rawQuery
                ?.split("&")
                ?.filter { part ->
                    val key = part.substringBefore("=").lowercase()
                    key.isNotEmpty() && !key.startsWith("utm_") && key !in trackingParams
                }
                ?.joinToString("&")
                ?.ifEmpty { null }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }
            buildString {
                append(uri.scheme.lowercase()).append("://").append(host).append(port).append(path)
                if (query != null) append("?").append(query)
            }
        } catch (e: Exception) {
            url.trim()
        }
    }

    fun host(url: String): String? = try {
        URI(url).host?.lowercase()?.removePrefix("www.")
    } catch (e: Exception) {
        null
    }

    fun origin(url: String): String? = try {
        val uri = URI(url)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        if (uri.host == null) null else "${uri.scheme}://${uri.host}$port"
    } catch (e: Exception) {
        null
    }

    /** True when [url] is on [siteHost] or one of its subdomains (or the reverse). */
    fun sameSite(url: String, siteHost: String): Boolean {
        val h = host(url) ?: return false
        return h == siteHost || h.endsWith(".$siteHost") || siteHost.endsWith(".$h")
    }
}
