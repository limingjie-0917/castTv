package com.bd.casttv.webparse

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NemoAdapter : SiteAdapter {
    private companion object {
        const val TAG = "NemoAdapter"
    }

    override fun canHandle(url: String, html: String): Boolean {
        val lowerUrl = url.lowercase()
        val lowerHtml = html.lowercase()
        return (lowerHtml.contains("player_data") && (lowerHtml.contains("nemo") || lowerHtml.contains("anthology") || lowerHtml.contains("playlist"))) ||
            (lowerUrl.contains("/video/") && (lowerHtml.contains("player_data") || lowerHtml.contains("anthology") || lowerHtml.contains("playlist"))) ||
            lowerHtml.contains("cybernemo")
    }

    override suspend fun parseDetail(url: String, html: String): ParsedMovie = withContext(Dispatchers.Default) {
        val playerUrl = extractPlayerUrl(html)?.let { WebParseHtml.absolute(url, it) }
        val sources = parseSources(url, html).ifEmpty {
            if (playerUrl.isNullOrBlank()) emptyList() else listOf(
                ParsedSource("默认线路", listOf(ParsedEpisode("播放", playerUrl, playerUrl.takeIf { WebParseHtml.looksPlayable(it) })))
            )
        }.ifEmpty {
            listOf(ParsedSource("默认线路", listOf(ParsedEpisode("播放", url))))
        }
        Log.d(TAG, "parseDetail title=${extractTitle(html)} sources=${sources.size}")
        ParsedMovie(
            title = extractTitle(html),
            coverUrl = extractCover(url, html),
            description = extractDescription(html),
            category = extractInfo(html, "类型|分类|类别"),
            year = extractInfo(html, "年份|年代"),
            area = extractInfo(html, "地区|区域|国家"),
            director = extractInfo(html, "导演"),
            actors = extractInfo(html, "主演|演员"),
            sources = sources
        )
    }

    override suspend fun resolvePlayUrl(playPageUrl: String): String? = withContext(Dispatchers.IO) {
        if (WebParseHtml.looksPlayable(playPageUrl)) return@withContext playPageUrl
        val html = WebParseExtractor.fetchText(playPageUrl)
        extractPlayerUrl(html)?.let { WebParseHtml.absolute(playPageUrl, it) }
            ?: Regex("https?://[^\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv)(?:[^\"'<>\\s]*)?", RegexOption.IGNORE_CASE).find(html)?.value
    }

    private fun parseSources(baseUrl: String, html: String): List<ParsedSource> {
        val blocks = Regex("<(?:div|ul|ol)[^>]+(?:class|id)=[\"'][^\"']*(?:anthology|playlist|play-list|episode|video-list)[^\"']*[\"'][^>]*>[\\s\\S]*?</(?:div|ul|ol)>", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value }
            .toList()
            .ifEmpty { listOf(html) }
        return blocks.mapIndexedNotNull { index, block ->
            val sourceName = firstNonBlank(
                WebParseHtml.tagText(block, "<h[2-4][^>]*>([\\s\\S]*?)</h[2-4]>"),
                WebParseHtml.tagText(block, "<span[^>]+class=[\"'][^\"']*(?:title|source|from|tab)[^\"']*[\"'][^>]*>([\\s\\S]*?)</span>"),
                "线路${index + 1}"
            )
            val episodes = Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
                .findAll(block)
                .mapNotNull { m ->
                    val href = m.groupValues[1].trim()
                    val name = WebParseHtml.clean(m.groupValues[2]).ifBlank { "播放" }
                    val lower = href.lowercase()
                    val isEpisode = lower.contains("/video/") || lower.contains("play") || lower.contains("episode") || WebParseHtml.looksPlayable(href) || Regex("第\\s*\\d+\\s*集").containsMatchIn(name)
                    if (href.isBlank() || href.startsWith("javascript", true) || !isEpisode || name.length > 60) null
                    else ParsedEpisode(name, WebParseHtml.absolute(baseUrl, href))
                }
                .distinctBy { it.playPageUrl }
                .toList()
            if (episodes.isEmpty()) null else ParsedSource(sourceName, episodes)
        }
    }

    private fun extractPlayerUrl(html: String): String? {
        val json = extractJsonVariable(html, "player_data") ?: extractJsonVariable(html, "playerData")
        json?.let {
            runCatching {
                JSONObject(it).let { obj ->
                    firstNonBlank(
                        obj.optString("url"),
                        obj.optString("playUrl"),
                        obj.optString("play_url"),
                        obj.optString("src"),
                        obj.optJSONObject("data")?.optString("url").orEmpty()
                    )
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return WebParseHtml.decodeMaybeBase64(it) }
        }
        return Regex("[\"'](?:url|playUrl|play_url|src)[\"']\\s*:\\s*[\"']([^\"']+(?:m3u8|mp4|flv)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let { WebParseHtml.decodeMaybeBase64(it) }
    }

    private fun extractJsonVariable(html: String, name: String): String? {
        val marker = Regex("(?:var\\s+|let\\s+|const\\s+)?${Regex.escape(name)}\\s*=\\s*", RegexOption.IGNORE_CASE).find(html) ?: return null
        val start = html.indexOf('{', marker.range.last + 1)
        if (start < 0) return null
        return balancedObject(html, start)
    }

    private fun balancedObject(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var quote = '\u0000'
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == quote) inString = false
                continue
            }
            when (ch) {
                '\'', '"' -> { inString = true; quote = ch }
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun extractTitle(html: String): String = firstNonBlank(
        WebParseHtml.tagText(html, "<h1[^>]*>([\\s\\S]*?)</h1>"),
        WebParseHtml.tagText(html, "<h2[^>]+class=[\"'][^\"']*(?:title|name)[^\"']*[\"'][^>]*>([\\s\\S]*?)</h2>"),
        WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        WebParseHtml.tagText(html, "<title[^>]*>([\\s\\S]*?)</title>").replace(Regex("[-_].*$"), "").trim(),
        "未命名影片"
    )

    private fun extractCover(baseUrl: String, html: String): String = firstNonBlank(
        WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        WebParseHtml.attr(html, "<img[^>]+(?:src|data-src|data-original)=[\"']([^\"']+)[\"'][^>]*(?:cover|poster|pic|vod|lazy)[^>]*>"),
        WebParseHtml.attr(html, "<img[^>]+(?:cover|poster|pic|vod|lazy)[^>]+(?:src|data-src|data-original)=[\"']([^\"']+)[\"']")
    ).let { WebParseHtml.absolute(baseUrl, it) }

    private fun extractDescription(html: String): String = firstNonBlank(
        WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        WebParseHtml.attr(html, "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        WebParseHtml.tagText(html, "<(?:p|div|span)[^>]+class=[\"'][^\"']*(?:desc|intro|content|plot|summary)[^\"']*[\"'][^>]*>([\\s\\S]*?)</(?:p|div|span)>")
    )

    private fun extractInfo(html: String, labelRegex: String): String {
        return Regex("(?:$labelRegex)\\s*[:：]\\s*</?[^>]*>?(?:\\s*<[^>]+>)*([^<\\n]{1,80})", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let { WebParseHtml.clean(it) }.orEmpty()
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()
}
