package com.bd.casttv.webparse

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

class ZyPlayerAdapter : SiteAdapter {
    private companion object {
        const val TAG = "ZyPlayerAdapter"
    }

    override fun canHandle(url: String, html: String): Boolean {
        val lowerUrl = url.lowercase()
        val lowerHtml = html.lowercase()
        return lowerUrl.contains("/index.php/vod/detail/id/") ||
            lowerUrl.contains("/api/") ||
            lowerUrl.contains("api.php/provide/vod") ||
            lowerHtml.contains("zy-player") ||
            lowerHtml.contains("zyplayer") ||
            lowerHtml.contains("/index.php/ajax/data") ||
            (lowerHtml.contains("window.config") && lowerHtml.contains("vod") && !lowerHtml.contains("snail") && !lowerHtml.contains("nemo"))
    }

    override suspend fun parseDetail(url: String, html: String): ParsedMovie = withContext(Dispatchers.IO) {
        val jsonMovie = parseFromJsonSources(url, html)
        if (jsonMovie != null && jsonMovie.sources.any { it.episodes.isNotEmpty() }) {
            Log.d(TAG, "parseDetail json title=${jsonMovie.title} sources=${jsonMovie.sources.size}")
            return@withContext jsonMovie
        }

        val sources = parseHtmlSources(url, html).ifEmpty {
            val playerUrl = extractPlayerUrl(html)?.let { WebParseHtml.absolute(url, it) }
            if (playerUrl.isNullOrBlank()) emptyList() else listOf(
                ParsedSource("默认线路", listOf(ParsedEpisode("播放", playerUrl, playerUrl.takeIf { WebParseHtml.looksPlayable(it) })))
            )
        }.ifEmpty {
            listOf(ParsedSource("默认线路", listOf(ParsedEpisode("播放", url))))
        }

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
            ?: findPlayableInText(html)?.let { WebParseHtml.absolute(playPageUrl, it) }
    }

    private fun parseFromJsonSources(url: String, html: String): ParsedMovie? {
        val jsonTexts = mutableListOf<String>()
        if (looksJson(html)) jsonTexts.add(html)
        extractWindowConfig(html)?.let { jsonTexts.add(it) }
        extractPlayerJson(html, "window.config")?.let { jsonTexts.add(it) }
        extractPlayerJson(html, "config")?.let { jsonTexts.add(it) }

        buildApiCandidates(url, html).forEach { apiUrl ->
            runCatching { WebParseExtractor.fetchText(apiUrl, timeoutMs = 8_000) }
                .onSuccess { body -> if (looksJson(body)) jsonTexts.add(body) }
                .onFailure { Log.w(TAG, "fetch api failed url=$apiUrl error=${it.message}") }
        }

        jsonTexts.forEach { text ->
            val vod = extractVodObject(text) ?: return@forEach
            val sources = parseVodSources(url, vod)
            val title = firstNonBlank(vod.optString("vod_name"), vod.optString("name"), extractTitle(html))
            return ParsedMovie(
                title = title.ifBlank { "未命名影片" },
                coverUrl = WebParseHtml.absolute(url, firstNonBlank(vod.optString("vod_pic"), vod.optString("pic"), vod.optString("cover"), vod.optString("poster"))),
                description = firstNonBlank(vod.optString("vod_content"), vod.optString("content"), vod.optString("vod_blurb"), vod.optString("description"), extractDescription(html)),
                category = firstNonBlank(vod.optString("vod_class"), vod.optString("class"), extractInfo(html, "类型|分类|类别")),
                year = firstNonBlank(vod.optString("vod_year"), vod.optString("year"), extractInfo(html, "年份|年代")),
                area = firstNonBlank(vod.optString("vod_area"), vod.optString("area"), extractInfo(html, "地区|区域|国家")),
                director = firstNonBlank(vod.optString("vod_director"), vod.optString("director"), extractInfo(html, "导演")),
                actors = firstNonBlank(vod.optString("vod_actor"), vod.optString("actor"), vod.optString("actors"), extractInfo(html, "主演|演员")),
                sources = sources.ifEmpty { parseHtmlSources(url, html) }
            )
        }
        return null
    }

    private fun buildApiCandidates(url: String, html: String): List<String> {
        val result = linkedSetOf<String>()
        if (isApiUrl(url)) result.add(url)
        val base = runCatching {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
        }.getOrNull() ?: return result.toList()
        val id = firstNonBlank(
            Regex("/id/(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1).orEmpty(),
            Regex("(?:vod_id|mid|id)[\\s=:]+[\"']?(\\d+)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1).orEmpty()
        )
        if (id.isNotBlank()) {
            result.add("$base/index.php/ajax/data?mid=${encode(id)}")
            result.add("$base/api.php/provide/vod/?ac=detail&ids=${encode(id)}")
            result.add("$base/index.php/api/vod?ac=detail&ids=${encode(id)}")
            result.add("$base/api.php/v1.vod/detail?vod_id=${encode(id)}")
        }
        Regex("[\"']([^\"']*(?:/index\\.php/ajax/data|/api(?:\\.php)?/provide/vod|/index\\.php/api/vod)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { WebParseHtml.absolute(url, it.groupValues[1]) }
            .filter { it.isNotBlank() }
            .forEach { result.add(it) }
        return result.toList()
    }

    private fun isApiUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/api/") || lower.contains("api.php") || lower.contains("/ajax/data")
    }

    private fun parseVodSources(baseUrl: String, vod: JSONObject): List<ParsedSource> {
        val fromParts = splitMulti(vod.optString("vod_play_from")).ifEmpty { splitMulti(vod.optString("play_from")) }
        val urlParts = splitMulti(vod.optString("vod_play_url")).ifEmpty { splitMulti(vod.optString("play_url")) }
        val sources = mutableListOf<ParsedSource>()
        urlParts.forEachIndexed { index, part ->
            val episodes = part.split("#")
                .mapIndexedNotNull { epIndex, item -> parseEpisodeItem(baseUrl, item, epIndex) }
                .distinctBy { it.playPageUrl }
            if (episodes.isNotEmpty()) {
                sources.add(ParsedSource(fromParts.getOrNull(index)?.ifBlank { null } ?: "线路${index + 1}", episodes))
            }
        }
        val directUrl = firstNonBlank(vod.optString("url"), vod.optString("playUrl"), vod.optString("play_url"))
        if (sources.isEmpty() && directUrl.isNotBlank()) {
            val finalUrl = WebParseHtml.absolute(baseUrl, WebParseHtml.decodeMaybeBase64(directUrl))
            sources.add(ParsedSource("默认线路", listOf(ParsedEpisode("播放", finalUrl, finalUrl.takeIf { WebParseHtml.looksPlayable(it) }))))
        }
        return sources
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

    private fun parseHtmlSources(baseUrl: String, html: String): List<ParsedSource> {
        val blocks = Regex("<(?:div|ul|ol)[^>]+(?:class|id)=[\"'][^\"']*(?:zy-player|playlist|anthology|play-list|vod-play|episode)[^\"']*[\"'][^>]*>[\\s\\S]*?</(?:div|ul|ol)>", RegexOption.IGNORE_CASE)
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
                    val isEpisode = lower.contains("play") || lower.contains("video") || WebParseHtml.looksPlayable(href) || Regex("第\\s*\\d+\\s*集").containsMatchIn(name)
                    if (href.isBlank() || href.startsWith("javascript", true) || !isEpisode || name.length > 60) null
                    else ParsedEpisode(name, WebParseHtml.absolute(baseUrl, href))
                }
                .distinctBy { it.playPageUrl }
                .toList()
            if (episodes.isEmpty()) null else ParsedSource("线路${index + 1}", episodes)
        }
    }

    private fun extractVodObject(text: String): JSONObject? {
        val root = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return null
        root.optJSONArray("list")?.firstObject()?.let { return it }
        root.optJSONArray("data")?.firstObject()?.let { return it }
        root.optJSONObject("data")?.let { data ->
            data.optJSONArray("list")?.firstObject()?.let { return it }
            if (data.length() > 0) return data
        }
        root.optJSONObject("vod")?.let { return it }
        root.optJSONObject("info")?.let { return it }
        return root.takeIf { it.has("vod_name") || it.has("vod_play_url") || it.has("url") }
    }

    private fun JSONArray.firstObject(): JSONObject? {
        for (i in 0 until length()) optJSONObject(i)?.let { return it }
        return null
    }

    private fun extractPlayerUrl(html: String): String? {
        val json = extractPlayerJson(html, "player_data") ?: extractPlayerJson(html, "playerData") ?: extractPlayerJson(html, "window.config") ?: extractPlayerJson(html, "config")
        json?.let {
            runCatching { JSONObject(it).let { obj -> firstNonBlank(obj.optString("url"), obj.optString("playUrl"), obj.optString("play_url"), obj.optString("src")) } }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return WebParseHtml.decodeMaybeBase64(it) }
        }
        return Regex("[\"'](?:url|playUrl|play_url|src)[\"']\\s*:\\s*[\"']([^\"']+(?:m3u8|mp4|flv)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let { WebParseHtml.decodeMaybeBase64(it) }
    }

    private fun extractPlayerJson(html: String, name: String): String? {
        val pattern = if (name.startsWith("window.")) Regex("${Regex.escape(name)}\\s*=\\s*", RegexOption.IGNORE_CASE) else Regex("(?:var\\s+|let\\s+|const\\s+)?${Regex.escape(name)}\\s*=\\s*", RegexOption.IGNORE_CASE)
        val marker = pattern.find(html) ?: return null
        val start = html.indexOf('{', marker.range.last + 1)
        if (start < 0) return null
        return balancedObject(html, start)
    }

    private fun extractWindowConfig(html: String): String? = extractPlayerJson(html, "window.config")

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

    private fun findPlayableInText(text: String): String? = Regex("https?://[^\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv)(?:[^\"'<>\\s]*)?", RegexOption.IGNORE_CASE)
        .find(text)?.value

    private fun looksJson(text: String): Boolean = text.trimStart().startsWith("{")

    private fun splitMulti(value: String): List<String> = value.split("$$$").map { it.trim() }.filter { it.isNotBlank() }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun extractTitle(html: String): String = firstNonBlank(
        WebParseHtml.tagText(html, "<h1[^>]*>([\\s\\S]*?)</h1>"),
        WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        WebParseHtml.tagText(html, "<title[^>]*>([\\s\\S]*?)</title>").replace(Regex("[-_].*$"), "").trim(),
        "未命名影片"
    )

    private fun extractCover(baseUrl: String, html: String): String = firstNonBlank(
        WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        WebParseHtml.attr(html, "<img[^>]+(?:src|data-src|data-original)=[\"']([^\"']+)[\"'][^>]*(?:cover|poster|pic|vod)[^>]*>"),
        WebParseHtml.attr(html, "<img[^>]+(?:cover|poster|pic|vod)[^>]+(?:src|data-src|data-original)=[\"']([^\"']+)[\"']")
    ).let { WebParseHtml.absolute(baseUrl, it) }

    private fun extractDescription(html: String): String = firstNonBlank(
        WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        WebParseHtml.attr(html, "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']"),
        WebParseHtml.tagText(html, "<(?:p|div|span)[^>]+class=[\"'][^\"']*(?:desc|intro|content|plot)[^\"']*[\"'][^>]*>([\\s\\S]*?)</(?:p|div|span)>")
    )

    private fun extractInfo(html: String, labelRegex: String): String {
        return Regex("(?:$labelRegex)\\s*[:：]\\s*</?[^>]*>?(?:\\s*<[^>]+>)*([^<\\n]{1,80})", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let { WebParseHtml.clean(it) }.orEmpty()
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()
}
