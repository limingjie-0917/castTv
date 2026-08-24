package com.bd.casttv.webparse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLDecoder

class GenericAdapter : SiteAdapter {
    override fun canHandle(url: String, html: String): Boolean = true

    override suspend fun parseDetail(url: String, html: String): ParsedMovie = withContext(Dispatchers.Default) {
        val title = firstNonBlank(
            WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']"),
            WebParseHtml.tagText(html, "<title[^>]*>([\\s\\S]*?)</title>"),
            "未命名页面"
        )
        val cover = firstNonBlank(
            WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']"),
            WebParseHtml.attr(html, "<img[^>]+(?:src|data-src|data-original)=[\"']([^\"']+)[\"'][^>]*>")
        ).let { WebParseHtml.absolute(url, it) }
        val desc = firstNonBlank(
            WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']"),
            WebParseHtml.attr(html, "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']")
        )
        val playable = Regex("https?://[^\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv)(?:[^\"'<>\\s]*)?", RegexOption.IGNORE_CASE)
            .find(html)?.value
        val episodes = parseLinks(url, html).ifEmpty {
            listOf(ParsedEpisode("播放", playable ?: url, playable))
        }
        ParsedMovie(
            title = WebParseHtml.clean(title),
            coverUrl = cover,
            description = desc,
            category = "",
            year = "",
            area = "",
            director = "",
            actors = "",
            sources = listOf(ParsedSource("默认线路", episodes))
        )
    }

    override suspend fun resolvePlayUrl(playPageUrl: String): String? = withContext(Dispatchers.IO) {
        unwrapJianpianUrl(playPageUrl)?.let { return@withContext it }
        if (WebParseHtml.looksPlayable(playPageUrl)) return@withContext playPageUrl
        val html = WebParseExtractor.fetchText(playPageUrl)
        Regex("https?://[^\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv)(?:[^\"'<>\\s]*)?", RegexOption.IGNORE_CASE)
            .find(html)?.value
    }

    private fun parseLinks(baseUrl: String, html: String): List<ParsedEpisode> {
        return Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", setOf(RegexOption.IGNORE_CASE))
            .findAll(html)
            .mapNotNull { m ->
                val href = WebParseHtml.absolute(baseUrl, m.groupValues[1])
                val realHref = unwrapJianpianUrl(href)
                val name = WebParseHtml.clean(m.groupValues[2]).ifBlank { WebParseHtml.shortUrl(realHref ?: href) }
                val lower = href.lowercase()
                val looksEpisode = realHref != null || WebParseHtml.looksPlayable(href) || lower.contains("play") || lower.contains("video") || Regex("第\\s*\\d+\\s*集").containsMatchIn(name)
                if (href.isBlank() || !looksEpisode || name.length > 60) null else ParsedEpisode(name, href, realHref ?: href.takeIf { WebParseHtml.looksPlayable(it) })
            }
            .distinctBy { it.playPageUrl }
            .take(80)
            .toList()
    }

    private fun unwrapJianpianUrl(url: String): String? {
        val raw = WebParseHtml.decodeEntities(url).trim()
        if (!raw.startsWith("jianpian://", ignoreCase = true)) return null
        val pathStart = raw.indexOf("path=", ignoreCase = true)
        if (pathStart < 0) return null
        val pathValue = raw.substring(pathStart + "path=".length).trim()
        if (!pathValue.startsWith("http", ignoreCase = true)) return null
        val decoded = runCatching { URLDecoder.decode(pathValue, "UTF-8") }.getOrDefault(pathValue).trim()
        return decoded.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()
}
