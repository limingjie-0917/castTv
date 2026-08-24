package com.bd.casttv.webparse

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SnailCmsAdapter : SiteAdapter {
    private companion object {
        const val TAG = "SnailCmsAdapter"
    }

    override fun canHandle(url: String, html: String): Boolean {
        val lowerUrl = url.lowercase()
        val lowerHtml = html.lowercase()
        return (lowerHtml.contains("snail") && (lowerHtml.contains("window.config") || lowerHtml.contains("var config") || lowerHtml.contains("player_data"))) ||
            lowerUrl.contains("snail") ||
            lowerHtml.contains("snail-player")
    }

    override suspend fun parseDetail(url: String, html: String): ParsedMovie = withContext(Dispatchers.Default) {
        val config = extractConfigObject(html)
        val sources = parseSources(url, html, config).ifEmpty {
            val playerUrl = extractPlayerUrl(html, config)?.let { WebParseHtml.absolute(url, it) }
            if (playerUrl.isNullOrBlank()) emptyList() else listOf(
                ParsedSource("默认线路", listOf(ParsedEpisode("播放", playerUrl, playerUrl.takeIf { WebParseHtml.looksPlayable(it) })))
            )
        }.ifEmpty {
            listOf(ParsedSource("默认线路", listOf(ParsedEpisode("播放", url))))
        }
        Log.d(TAG, "parseDetail title=${extractTitle(html, config)} sources=${sources.size}")
        ParsedMovie(
            title = extractTitle(html, config),
            coverUrl = extractCover(url, html, config),
            description = extractDescription(html, config),
            category = firstNonBlank(config?.optString("category").orEmpty(), config?.optString("type").orEmpty(), extractInfo(html, "类型|分类|类别")),
            year = firstNonBlank(config?.optString("year").orEmpty(), extractInfo(html, "年份|年代")),
            area = firstNonBlank(config?.optString("area").orEmpty(), extractInfo(html, "地区|区域|国家")),
            director = firstNonBlank(config?.optString("director").orEmpty(), extractInfo(html, "导演")),
            actors = firstNonBlank(config?.optString("actors").orEmpty(), config?.optString("actor").orEmpty(), extractInfo(html, "主演|演员")),
            sources = sources
        )
    }

    override suspend fun resolvePlayUrl(playPageUrl: String): String? = withContext(Dispatchers.IO) {
        if (WebParseHtml.looksPlayable(playPageUrl)) return@withContext playPageUrl
        val html = WebParseExtractor.fetchText(playPageUrl)
        extractPlayerUrl(html, extractConfigObject(html))?.let { WebParseHtml.absolute(playPageUrl, it) }
            ?: Regex("https?://[^\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv)(?:[^\"'<>\\s]*)?", RegexOption.IGNORE_CASE).find(html)?.value
    }

    private fun parseSources(baseUrl: String, html: String, config: JSONObject?): List<ParsedSource> {
        parseConfigSources(baseUrl, config).takeIf { it.isNotEmpty() }?.let { return it }
        val blocks = Regex("<(?:div|ul|ol)[^>]+(?:class|id)=[\"'][^\"']*(?:snail|playlist|play-list|anthology|episode|vod-play)[^\"']*[\"'][^>]*>[\\s\\S]*?</(?:div|ul|ol)>", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.value }
            .toList()
            .ifEmpty { listOf(html) }
        return blocks.mapIndexedNotNull { index, block ->
            val episodes = Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
                .findAll(block)
                .mapNotNull { m ->
                    val href = m.groupValues[1].trim()
                    val name = WebParseHtml.clean(m.groupValues[2]).ifBlank { "播放" }
                    val lower = href.lowercase()
                    val isEpisode = lower.contains("play") || lower.contains("video") || lower.contains("episode") || WebParseHtml.looksPlayable(href) || Regex("第\\s*\\d+\\s*集").containsMatchIn(name)
                    if (href.isBlank() || href.startsWith("javascript", true) || !isEpisode || name.length > 60) null
                    else ParsedEpisode(name, WebParseHtml.absolute(baseUrl, href))
                }
                .distinctBy { it.playPageUrl }
                .toList()
            if (episodes.isEmpty()) null else ParsedSource("线路${index + 1}", episodes)
        }
    }

    private fun parseConfigSources(baseUrl: String, config: JSONObject?): List<ParsedSource> {
        if (config == null) return emptyList()
        val arrays = listOf("episodes", "playlist", "playList", "list", "urls")
        arrays.forEach { key ->
            val arr = config.optJSONArray(key) ?: return@forEach
            val episodes = mutableListOf<ParsedEpisode>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    val link = firstNonBlank(obj.optString("url"), obj.optString("href"), obj.optString("playUrl"), obj.optString("play_url"))
                    val name = firstNonBlank(obj.optString("name"), obj.optString("title"), "第${i + 1}集")
                    val finalUrl = WebParseHtml.absolute(baseUrl, WebParseHtml.decodeMaybeBase64(link))
                    if (finalUrl.isNotBlank()) episodes.add(ParsedEpisode(name, finalUrl, finalUrl.takeIf { WebParseHtml.looksPlayable(it) }))
                } else {
                    parseEpisodeItem(baseUrl, arr.optString(i), i)?.let { episodes.add(it) }
                }
            }
            if (episodes.isNotEmpty()) return listOf(ParsedSource("默认线路", episodes.distinctBy { it.playPageUrl }))
        }

        val playUrl = firstNonBlank(config.optString("url"), config.optString("playUrl"), config.optString("play_url"), config.optString("src"), config.optJSONObject("player")?.optString("url").orEmpty())
        if (playUrl.isNotBlank()) {
            val finalUrl = WebParseHtml.absolute(baseUrl, WebParseHtml.decodeMaybeBase64(playUrl))
            return listOf(ParsedSource("默认线路", listOf(ParsedEpisode("播放", finalUrl, finalUrl.takeIf { WebParseHtml.looksPlayable(it) }))))
        }
        return emptyList()
    }

    private fun parseEpisodeItem(baseUrl: String, item: String, index: Int): ParsedEpisode? {
        val raw = item.trim()
        if (raw.isBlank()) return null
        val parts = raw.split("$", limit = 2)
        val name = if (parts.size == 2) parts[0].trim().ifBlank { "第${index + 1}集" } else "第${index + 1}集"
        val link = if (parts.size == 2) parts[1].trim() else raw
        val finalUrl = WebParseHtml.absolute(baseUrl, WebParseHtml.decodeMaybeBase64(link))
        return if (finalUrl.isBlank()) null else ParsedEpisode(name, finalUrl, finalUrl.takeIf { WebParseHtml.looksPlayable(it) })
    }

    private fun extractPlayerUrl(html: String, config: JSONObject?): String? {
        firstNonBlank(
            config?.optString("url").orEmpty(),
            config?.optString("playUrl").orEmpty(),
            config?.optString("play_url").orEmpty(),
            config?.optString("src").orEmpty(),
            config?.optJSONObject("player")?.optString("url").orEmpty()
        ).takeIf { it.isNotBlank() }?.let { return WebParseHtml.decodeMaybeBase64(it) }
        return Regex("[\"'](?:url|playUrl|play_url|src)[\"']\\s*:\\s*[\"']([^\"']+(?:m3u8|mp4|flv)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let { WebParseHtml.decodeMaybeBase64(it) }
    }

    private fun extractConfigObject(html: String): JSONObject? {
        val json = extractJsonVariable(html, "window.config") ?: extractJsonVariable(html, "config") ?: extractJsonVariable(html, "player_data")
        return json?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun extractJsonVariable(html: String, name: String): String? {
        val pattern = if (name.startsWith("window.")) Regex("${Regex.escape(name)}\\s*=\\s*", RegexOption.IGNORE_CASE) else Regex("(?:var\\s+|let\\s+|const\\s+)?${Regex.escape(name)}\\s*=\\s*", RegexOption.IGNORE_CASE)
        val marker = pattern.find(html) ?: return null
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

    private fun extractTitle(html: String, config: JSONObject?): String = firstNonBlank(
        config?.optString("title").orEmpty(),
        config?.optString("name").orEmpty(),
        WebParseHtml.tagText(html, "<h1[^>]*>([\\s\\S]*?)</h1>"),
        WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        WebParseHtml.tagText(html, "<title[^>]*>([\\s\\S]*?)</title>").replace(Regex("[-_].*$"), "").trim(),
        "未命名影片"
    )

    private fun extractCover(baseUrl: String, html: String, config: JSONObject?): String = firstNonBlank(
        config?.optString("cover").orEmpty(),
        config?.optString("poster").orEmpty(),
        config?.optString("pic").orEmpty(),
        config?.optString("image").orEmpty(),
        WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        WebParseHtml.attr(html, "<img[^>]+(?:src|data-src|data-original)=[\"']([^\"']+)[\"'][^>]*(?:cover|poster|pic|vod|lazy|snail)[^>]*>")
    ).let { WebParseHtml.absolute(baseUrl, it) }

    private fun extractDescription(html: String, config: JSONObject?): String = firstNonBlank(
        config?.optString("description").orEmpty(),
        config?.optString("desc").orEmpty(),
        config?.optString("content").orEmpty(),
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
