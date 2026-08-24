package com.bd.casttv.webparse

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MacCmsAdapter : SiteAdapter {
    private companion object {
        const val TAG = "MacCmsAdapter"
    }

    override fun canHandle(url: String, html: String): Boolean {
        return html.contains("player_aaaa", ignoreCase = true) ||
            html.contains("MacPlayer", ignoreCase = true) ||
            html.contains("vodlist", ignoreCase = true) ||
            html.contains("vod_play", ignoreCase = true) ||
            html.contains("maccms", ignoreCase = true) ||
            html.contains("stui-content__playlist", ignoreCase = true) ||
            html.contains("ewave-playlist", ignoreCase = true)
    }

    override suspend fun parseDetail(url: String, html: String): ParsedMovie = withContext(Dispatchers.Default) {
        val playerVodData = extractPlayerVodData(html)
        val title = firstNonBlank(
            WebParseHtml.tagText(html, "<h1[^>]*class=[\"'][^\"']*(?:title|name)[^\"']*[\"'][^>]*>([\\s\\S]*?)</h1>"),
            WebParseHtml.tagText(html, "<h1[^>]*>([\\s\\S]*?)</h1>"),
            WebParseHtml.tagText(html, "<h2[^>]*class=[\"'][^\"']*(?:detail-info-title|title|name)[^\"']*[\"'][^>]*>([\\s\\S]*?)</h2>"),
            WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']"),
            playerVodData?.optString("vod_name").orEmpty(),
            WebParseHtml.tagText(html, "<title[^>]*>([\\s\\S]*?)</title>").replace(Regex("[-_].*$"), "").trim(),
            "未命名影片"
        )
        val cover = firstNonBlank(
            WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']"),
            WebParseHtml.attr(html, "<span[^>]+class=[\"'][^\"']*mac_history_set[^\"']*[\"'][^>]+data-pic=[\"']([^\"']+)[\"']"),
            WebParseHtml.attr(html, "<img[^>]+(?:src|data-src|data-original)=[\"']([^\"']+)[\"'][^>]+(?:class|id)=[\"'][^\"']*(?:vod|pic|poster|cover|lazyload)[^\"']*[\"']"),
            WebParseHtml.attr(html, "<img[^>]+(?:class|id)=[\"'][^\"']*(?:vod|pic|poster|cover|lazyload)[^\"']*[\"'][^>]+(?:data-original|data-src|src)=[\"']([^\"']+)[\"']"),
            WebParseHtml.attr(html, "<a[^>]+class=[\"'][^\"']*(?:pic|thumb|poster|cover)[^\"']*[\"'][^>]+data-original=[\"']([^\"']+)[\"']"),
            playerVodData?.optString("vod_pic").orEmpty()
        ).let { WebParseHtml.absolute(url, it) }
        val desc = firstNonBlank(
            WebParseHtml.tagText(html, "<span[^>]+class=[\"'][^\"']*detail-content[^\"']*[\"'][^>]*>([\\s\\S]*?)</span>"),
            WebParseHtml.tagText(html, "<span[^>]+class=[\"'][^\"']*detail-sketch[^\"']*[\"'][^>]*>([\\s\\S]*?)</span>"),
            WebParseHtml.tagText(html, "<p[^>]+class=[\"'][^\"']*(?:desc|intro|plot)[^\"']*[\"'][^>]*>([\\s\\S]*?)</p>"),
            WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']"),
            WebParseHtml.attr(html, "<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']"),
            playerVodData?.optString("vod_content").orEmpty(),
            playerVodData?.optString("vod_blurb").orEmpty()
        )
        val category = firstNonBlank(extractInfo(html, "类型|分类|类别"), playerVodData?.optString("vod_class").orEmpty())
        val year = firstNonBlank(extractInfo(html, "年份|年代"), playerVodData?.optString("vod_year").orEmpty())
        val area = firstNonBlank(extractInfo(html, "地区|区域|国家"), playerVodData?.optString("vod_area").orEmpty())
        val director = firstNonBlank(extractInfo(html, "导演"), playerVodData?.optString("vod_director").orEmpty())
        val actors = firstNonBlank(extractInfo(html, "主演|演员"), playerVodData?.optString("vod_actor").orEmpty())
        val sources = parseSources(url, html).ifEmpty {
            Log.w(TAG, "parseDetail no episodes parsed, fallback to detail page. url=$url htmlLength=${html.length}")
            listOf(ParsedSource("默认线路", listOf(ParsedEpisode("播放", url))))
        }
        Log.d(TAG, "parseDetail done title=$title sources=${sources.size} episodeCounts=${sources.map { it.episodes.size }}")
        ParsedMovie(title, cover, desc, category, year, area, director, actors, sources)
    }

    override suspend fun resolvePlayUrl(playPageUrl: String): String? = withContext(Dispatchers.IO) {
        val html = WebParseExtractor.fetchText(playPageUrl)
        extractPlayerUrl(html)?.let { WebParseHtml.absolute(playPageUrl, it) }
            ?: Regex("https?://[^\"'<>\\s]+(?:\\.m3u8|\\.mp4|\\.flv)(?:[^\"'<>\\s]*)?", RegexOption.IGNORE_CASE)
                .find(html)?.value
    }

    private fun parseSources(baseUrl: String, html: String): List<ParsedSource> {
        val sourceNames = parseSourceNames(html)
        Log.d(TAG, "parseSources htmlLength=${html.length} sourceNames=$sourceNames")
        val playlistBlocks = parsePlaylistBlocks(html)
        Log.d(TAG, "parseSources playlistBlocks=${playlistBlocks.size} ids=${playlistBlocks.map { it.idSuffix }}")
        val result = mutableListOf<ParsedSource>()
        playlistBlocks.forEachIndexed { blockIndex, playlist ->
            val normalizedId = playlist.idSuffix.trim().trimStart('-')
            val sourceName = firstNonBlank(
                sourceNames[playlist.idSuffix.trim()] ?: "",
                sourceNames["playlist$normalizedId"] ?: "",
                WebParseHtml.tagText(playlist.html, "<h[2-4][^>]*>([\\s\\S]*?)</h[2-4]>"),
                WebParseHtml.tagText(playlist.html, "<span[^>]+(?:class|id)=[\"'][^\"']*(?:title|from|source|tab)[^\"']*[\"'][^>]*>([\\s\\S]*?)</span>"),
                "线路${blockIndex + 1}"
            )
            val episodes = parseEpisodes(baseUrl, playlist.html)
            Log.d(TAG, "parseSources block=$blockIndex id=${playlist.idSuffix} name=$sourceName blockLength=${playlist.html.length} episodes=${episodes.size} first=${episodes.firstOrNull()?.playPageUrl}")
            if (episodes.isNotEmpty()) result.add(ParsedSource(sourceName, episodes))
        }
        val fallback = if (result.isEmpty()) parseFromPlayerData(baseUrl, html) else emptyList()
        if (result.isEmpty()) Log.w(TAG, "parseSources no playlist episodes, playerDataSources=${fallback.size}")
        return result.ifEmpty { fallback }
    }

    private fun parsePlaylistBlocks(html: String): List<PlaylistBlock> {
        val tabPaneBlocks = Regex("<div[^>]+id=[\"']((?:playlist|play|stab|vod_play)[^\"']*)[\"'][^>]*class=[\"'][^\"']*(?:tab-pane|playlist|play)[^\"']*[\"'][^>]*>([\\s\\S]*?)(?=<div[^>]+id=[\"'](?:playlist|play|stab|vod_play)[^\"']*[\"']|</div>\\s*</div>\\s*(?:<script|<style|<div|</body>)|$)", setOf(RegexOption.IGNORE_CASE))
            .findAll(html)
            .map { m -> PlaylistBlock(m.groupValues[1], m.value) }
            .toList()
        if (tabPaneBlocks.isNotEmpty()) return tabPaneBlocks

        val legacyBlocks = Regex("<div[^>]+id=[\"'](?:playlist|play|stab|vod_play)([^\"']*)[\"'][^>]*>([\\s\\S]*?)(?=<div[^>]+id=[\"'](?:playlist|play|stab|vod_play)|</div>\\s*</div>\\s*(?:<script|<style|<div class=[\"']stui-vodlist__head)|</body>|$)", setOf(RegexOption.IGNORE_CASE))
            .findAll(html)
            .map { m -> PlaylistBlock(m.groupValues[1], m.value) }
            .toList()
        if (legacyBlocks.isNotEmpty()) return legacyBlocks

        val playlistUls = Regex("<ul[^>]+class=[\"'][^\"']*(?:stui-content__playlist|ewave-playlist-content|playlist|vod-play|play-list|anthology-list-play|anthology-list|player-anthology)[^\"']*[\"'][^>]*>[\\s\\S]*?</ul>", setOf(RegexOption.IGNORE_CASE))
            .findAll(html)
            .mapIndexed { index, m ->
                val id = firstNonBlank(
                    WebParseHtml.attr(m.value, "<ul[^>]+id=[\"']([^\"']+)[\"']"),
                    WebParseHtml.attr(m.value, "<ul[^>]+data-form=[\"']([^\"']+)[\"']")
                )
                PlaylistBlock(id.ifBlank { "playlist${index + 1}" }, m.value)
            }
            .toList()
        if (playlistUls.isNotEmpty()) return playlistUls

        val anthologyBlocks = Regex("<div[^>]+class=[\"'][^\"']*(?:anthology-list-play|anthology-list|player-anthology)[^\"']*[\"'][^>]*>[\\s\\S]*?</div>", setOf(RegexOption.IGNORE_CASE))
            .findAll(html)
            .mapIndexed { index, m ->
                val id = firstNonBlank(
                    WebParseHtml.attr(m.value, "<div[^>]+id=[\"']([^\"']+)[\"']"),
                    WebParseHtml.attr(m.value, "<div[^>]+data-form=[\"']([^\"']+)[\"']")
                )
                PlaylistBlock(id.ifBlank { "playlist${index + 1}" }, m.value)
            }
            .toList()
        return anthologyBlocks.ifEmpty { listOf(PlaylistBlock("", html)) }
    }

    private fun parseSourceNames(html: String): Map<String, String> {
        val hrefTabNames = Regex("<a[^>]+href=[\"']#([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", setOf(RegexOption.IGNORE_CASE))
            .findAll(html)
            .mapNotNull { m ->
                val id = m.groupValues[1].trim()
                val name = WebParseHtml.clean(m.groupValues[2])
                if (id.isBlank() || name.isBlank()) null else id to name
            }
        val dataTargetTabNames = Regex("<li[^>]+data-target=[\"']#([^\"']+)[\"'][^>]*>[\\s\\S]*?<a[^>]*>([\\s\\S]*?)</a>", setOf(RegexOption.IGNORE_CASE))
            .findAll(html)
            .mapNotNull { m ->
                val id = m.groupValues[1].trim()
                val name = WebParseHtml.clean(m.groupValues[2])
                if (id.isBlank() || name.isBlank()) null else id to name
            }
        val vodPlayerUrlTabNames = Regex("<a[^>]+class=[\"'][^\"']*vod-playerUrl[^\"']*[\"'][^>]*>[\\s\\S]*?</a>", setOf(RegexOption.IGNORE_CASE))
            .findAll(html)
            .flatMapIndexed { index, m ->
                val anchor = m.value
                val id = WebParseHtml.attr(anchor, "<a[^>]+data-form=[\"']([^\"']+)[\"']")
                val name = cleanSourceName(anchor)
                val keys = listOf(id, "playlist${index + 1}", (index + 1).toString()).filter { it.isNotBlank() }
                if (name.isBlank()) emptyList() else keys.map { it to name }
            }
        return (hrefTabNames + dataTargetTabNames + vodPlayerUrlTabNames).toMap()
    }

    private fun parseEpisodes(baseUrl: String, block: String): List<ParsedEpisode> {
        val playlistMatches = Regex("<ul[^>]+class=[\"'][^\"']*(?:stui-content__playlist|ewave-playlist-content|playlist|vod-play|play-list|anthology-list-play|anthology-list|player-anthology)[^\"']*[\"'][^>]*>([\\s\\S]*?)</ul>", setOf(RegexOption.IGNORE_CASE))
            .findAll(block)
            .map { it.groupValues[1] }
            .toList()
        val episodeArea = playlistMatches.joinToString("\n").ifBlank { block }
        val episodes = Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", setOf(RegexOption.IGNORE_CASE))
            .findAll(episodeArea)
            .mapNotNull { m ->
                val href = m.groupValues[1].trim()
                val name = WebParseHtml.clean(m.groupValues[2]).ifBlank { "播放" }
                val isPlayLink = href.contains("/play/", ignoreCase = true) ||
                    href.contains("play", ignoreCase = true) ||
                    Regex("/info/\\d+/\\d+-\\d+-\\d+\\.html", RegexOption.IGNORE_CASE).containsMatchIn(href) ||
                    Regex("/[a-z0-9]+/\\d+-\\d+-\\d+\\.html(?:[?#][^\\s]*)?$", RegexOption.IGNORE_CASE).containsMatchIn(href)
                if (href.isBlank() || href.startsWith("javascript", true) || !isPlayLink || name.length > 50) null
                else ParsedEpisode(name, WebParseHtml.absolute(baseUrl, href))
            }
            .distinctBy { it.playPageUrl }
            .toList()
        Log.d(TAG, "parseEpisodes blockLength=${block.length} playlistUlCount=${playlistMatches.size} areaLength=${episodeArea.length} episodes=${episodes.size}")
        return episodes
    }

    private fun parseFromPlayerData(baseUrl: String, html: String): List<ParsedSource> {
        val url = extractPlayerUrl(html) ?: return emptyList()
        val finalUrl = WebParseHtml.absolute(baseUrl, url)
        return listOf(ParsedSource("默认线路", listOf(ParsedEpisode("播放", finalUrl, if (WebParseHtml.looksPlayable(finalUrl)) finalUrl else null))))
    }

    private fun extractPlayerVodData(html: String): JSONObject? {
        return extractPlayerJson(html)?.let { json ->
            runCatching { JSONObject(json).optJSONObject("vod_data") }.getOrNull()
        }
    }

    private fun extractPlayerUrl(html: String): String? {
        val raw = extractPlayerJson(html)?.let { json ->
            runCatching { JSONObject(json).optString("url") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        } ?: Regex("[\"']url[\"']\\s*:\\s*[\"']([^\"']+(?:m3u8|mp4|flv)[^\"']*)[\"']", setOf(RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.getOrNull(1)
        return raw?.let { WebParseHtml.decodeMaybeBase64(it) }
    }

    private fun extractPlayerJson(html: String): String? {
        val marker = Regex("(?:var\\s+)?player_aaaa\\s*=\\s*", RegexOption.IGNORE_CASE).find(html) ?: return null
        val start = html.indexOf('{', marker.range.last + 1)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var quote = '\u0000'
        var escaped = false
        for (i in start until html.length) {
            val ch = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    inString = false
                }
                continue
            }
            when (ch) {
                '\'', '"' -> {
                    inString = true
                    quote = ch
                }
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun extractInfo(html: String, labelRegex: String): String {
        return Regex("(?:$labelRegex)\\s*[:：]\\s*</?[^>]*>?(?:\\s*<[^>]+>)*([^<\\n]{1,80})", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let { WebParseHtml.clean(it) }.orEmpty()
    }

    private fun cleanSourceName(anchorHtml: String): String {
        val withoutChildSpans = anchorHtml.replace(
            Regex("<span[^>]*>[\\s\\S]*?</span>", setOf(RegexOption.IGNORE_CASE)),
            ""
        )
        return WebParseHtml.clean(withoutChildSpans)
    }

    private data class PlaylistBlock(val idSuffix: String, val html: String)

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()
}
