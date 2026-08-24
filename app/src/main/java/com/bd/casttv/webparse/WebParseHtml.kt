package com.bd.casttv.webparse

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal object WebParseHtml {
    fun tagText(html: String, pattern: String): String = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(html)?.groupValues?.getOrNull(1)?.let { clean(it) }.orEmpty()

    fun attr(html: String, pattern: String): String = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(html)?.groupValues?.getOrNull(1)?.let { decodeEntities(it.trim()) }.orEmpty()

    fun clean(value: String): String = decodeEntities(
        value.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    )

    fun decodeEntities(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString().orEmpty() }
        .trim()

    fun absolute(baseUrl: String, maybeUrl: String): String {
        val raw = decodeEntities(maybeUrl).trim()
        if (raw.isBlank()) return ""
        return try { URI(baseUrl).resolve(raw).toString() } catch (_: Throwable) { raw }
    }

    fun shortUrl(url: String): String = try {
        val u = URI(url)
        val path = (u.path ?: "").trim('/').split('/').takeLast(2).joinToString("/")
        listOfNotNull(u.host, path.takeIf { it.isNotBlank() }).joinToString("/")
    } catch (_: Throwable) { url.take(48) }

    fun decodeMaybeBase64(value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) return raw
        val urlDecoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val candidates = listOf(urlDecoded, raw)
        for (c in candidates) {
            if (!Regex("^[A-Za-z0-9+/=_-]{16,}$").matches(c)) continue
            val normalized = c.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val decoded = runCatching { String(android.util.Base64.decode(padded, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
            if (!decoded.isNullOrBlank() && (decoded.startsWith("http") || decoded.contains(".m3u8") || decoded.contains(".mp4"))) return decoded
        }
        return urlDecoded
    }

    fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".flv") || lower.contains(".mkv")
    }
}
