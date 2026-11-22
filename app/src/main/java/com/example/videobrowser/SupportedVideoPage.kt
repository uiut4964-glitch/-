package com.example.videobrowser

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * URL-level identity gate for the two sites whose page extractors are enabled.
 * Listing, search, category, channel and profile pages must never be promoted to
 * a downloadable video merely because they contain preview <video> elements.
 */
object SupportedVideoPage {
    private val xhamsterDomains = setOf(
        "xhamster.com", "xhamster.one", "xhamster.desi", "xhms.pro", "xhday.com", "xhvid.com"
    )
    private val pornhubDomains = setOf("pornhub.com", "pornhubpremium.com", "pornhub.net", "pornhub.org")
    private val thumbzillaDomains = setOf("thumbzilla.com")
    private val gaypornDomains = setOf("gayporn.com")

    fun isKnownSite(rawUrl: String): Boolean {
        val uri = try { URI(rawUrl.trim()) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return false
        return (xhamsterDomains + pornhubDomains + thumbzillaDomains + gaypornDomains).any { host == it || host.endsWith(".$it") } ||
            Regex("^xhamster\\d+\\.(?:com|desi)$").matches(host)
    }

    fun isSupported(rawUrl: String): Boolean {
        val uri = try { URI(rawUrl.trim()) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return false
        val path = (uri.rawPath ?: "/").lowercase(Locale.ROOT)
        val query = parseQuery(uri.rawQuery)

        val isPornhub = pornhubDomains.any { host == it || host.endsWith(".$it") }
        if (isPornhub) {
            val viewKey = query["viewkey"].orEmpty()
            if (viewKey.matches(Regex("[a-z0-9]+", RegexOption.IGNORE_CASE)) &&
                (path == "/view_video.php" || path == "/video/show")) return true
            if (Regex("^/(?:embed|shorties)/[a-z0-9]+/?$").matches(path)) return true
        }

        val isThumbzilla = host == "thumbzilla.com" || host.endsWith(".thumbzilla.com")
        if (isThumbzilla && Regex("^/video/[a-z0-9]+/?$").matches(path)) return true

        // GayPorn uses canonical single-video pages at /video/<slug>. Keep the
        // route narrow so listing/search pages are never promoted to video pages.
        val isGayPorn = gaypornDomains.any { host == it || host.endsWith(".$it") }
        if (isGayPorn && Regex("^/video/[^/]+/?$").matches(path)) return true

        val isXhamster = xhamsterDomains.any { host == it || host.endsWith(".$it") } ||
            Regex("^xhamster\\d+\\.(?:com|desi)$").matches(host)
        if (isXhamster) {
            if (Regex("^/videos/[^/]+-[a-z0-9]+/?$").matches(path)) return true
            if (Regex("^/movies/[a-z0-9]+/[^/]*\\.html$").matches(path)) return true
            if (path == "/xembed.php" && query["video"].orEmpty().matches(Regex("\\d+"))) return true
        }
        return false
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split('&').forEach { pair ->
            val split = pair.split('=', limit = 2)
            val key = decode(split[0]).lowercase(Locale.ROOT)
            if (key.isNotBlank()) out[key] = decode(split.getOrElse(1) { "" })
        }
        return out
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) { value }
}
