package com.bd.casttv.webparse

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Parsed movie item from a web list page. */
data class ParsedListMovie(
    val title: String,
    val coverUrl: String,
    val detailUrl: String
)

class WebParseListExtractor {
    suspend fun extractList(url: String): List<ParsedListMovie> = withContext(Dispatchers.IO) {
        val html = WebParseExtractor.fetchText(url)
        WebParseExtractor.lastParsedUrl = url
        WebParseExtractor.lastParsedHtml = html
        val movies = parseList(url, html)
        Log.d(TAG, "extractList url=$url htmlLength=${html.length} movies=${movies.size}")
        if (movies.isEmpty()) error("未从列表页提取到影片条目")
        movies
    }

    fun parseWithJsonRule(html: String, jsonRule: String, baseUrl: String): List<ParsedListMovie> {
        val json = JSONObject(jsonRule)
        if (json.optString("type") != "list") error("JSON 校验失败：type 必须为 list")
        val titleSelector = json.optString("titleSelector").trim()
        val detailUrlSelector = json.optString("detailUrlSelector").trim()
        val coverSelector = json.optString("coverSelector").trim()
        if (titleSelector.isBlank()) error("JSON 校验失败：titleSelector 不能为空")
        if (detailUrlSelector.isBlank()) error("JSON 校验失败：detailUrlSelector 不能为空")
        val ruleBaseUrl = normalizeBaseUrl(json.optString("baseUrl"), baseUrl)
        val titleElements = selectElements(html, titleSelector)
        val urlElements = selectElements(html, detailUrlSelector)
        val coverElements = if (coverSelector.isBlank()) emptyList() else selectElements(html, coverSelector)
        if (titleElements.isEmpty()) error("JSON 解析失败：titleSelector 未命中任何元素")
        if (urlElements.isEmpty()) error("JSON 解析失败：detailUrlSelector 未命中任何元素")
        val count = maxOf(titleElements.size, urlElements.size)
        return (0 until count).mapNotNull { index ->
            val titleElement = titleElements.getOrNull(index) ?: titleElements.firstOrNull()
            val urlElement = urlElements.getOrNull(index) ?: return@mapNotNull null
            val title = elementText(titleElement).trim()
            val detailUrl = WebParseHtml.absolute(ruleBaseUrl, elementUrl(urlElement)).trim()
            if (title.isBlank() || detailUrl.isBlank()) return@mapNotNull null
            val cover = coverElements.getOrNull(index)?.let { elementImageUrl(it) }.orEmpty()
                .ifBlank { elementImageUrl(urlElement) }
                .let { WebParseHtml.absolute(ruleBaseUrl, it) }
            ParsedListMovie(title = title, coverUrl = cover, detailUrl = detailUrl)
        }.distinctBy { it.detailUrl }
    }

    private fun parseList(baseUrl: String, html: String): List<ParsedListMovie> {
        return anchorPattern.findAll(html)
            .mapNotNull { match ->
                val anchorHtml = match.value
                val href = match.groupValues[2].trim()
                if (!isLikelyDetailHref(href)) return@mapNotNull null
                val detailUrl = WebParseHtml.absolute(baseUrl, href)
                if (detailUrl.isBlank() || !isLikelyDetailHref(detailUrl)) return@mapNotNull null

                val title = firstNonBlank(
                    WebParseHtml.attr(anchorHtml, "<a[^>]+title=[\"']([^\"']+)[\"']"),
                    WebParseHtml.attr(anchorHtml, "<img[^>]+alt=[\"']([^\"']+)[\"']"),
                    WebParseHtml.clean(anchorHtml)
                ).trim()

                if (title.isBlank()) return@mapNotNull null

                val cover = firstNonBlank(
                    WebParseHtml.attr(anchorHtml, "<img[^>]+data-original=[\"']([^\"']+)[\"']"),
                    WebParseHtml.attr(anchorHtml, "<img[^>]+data-src=[\"']([^\"']+)[\"']"),
                    WebParseHtml.attr(anchorHtml, "<img[^>]+src=[\"']([^\"']+)[\"']"),
                    WebParseHtml.attr(anchorHtml, "<a[^>]+data-original=[\"']([^\"']+)[\"']"),
                    WebParseHtml.attr(anchorHtml, "<a[^>]+data-src=[\"']([^\"']+)[\"']")
                ).let { WebParseHtml.absolute(baseUrl, it) }

                ParsedListMovie(title = title, coverUrl = cover, detailUrl = detailUrl)
            }
            .distinctBy { it.detailUrl }
            .toList()
    }

    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun normalizeBaseUrl(rawBaseUrl: String, fallbackBaseUrl: String): String {
        val raw = WebParseHtml.decodeEntities(rawBaseUrl).trim().trim('`', '"', '\'')
        val markdownUrl = Regex("""^\[[^]]*]\((https?://[^\s)]+)\)$""", RegexOption.IGNORE_CASE)
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val candidate = markdownUrl.ifBlank { raw }.trim()
        return candidate.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: fallbackBaseUrl
    }

    private fun isLikelyDetailHref(href: String): Boolean {
        val normalized = href.trim().lowercase()
        if (normalized.isBlank()) return false
        if (navigationKeywords.any { normalized.contains(it) }) return false
        if (playPagePattern.containsMatchIn(normalized)) return false
        return true
    }

    private data class Element(val outer: String, val inner: String, val attrs: Map<String, String>) {
        val text: String get() = WebParseHtml.clean(inner.ifBlank { outer })
    }

    private fun elementText(element: Element?): String {
        if (element == null) return ""
        return firstNonBlank(element.text, element.attrs["title"].orEmpty(), element.attrs["alt"].orEmpty())
    }

    private fun elementUrl(element: Element): String = firstNonBlank(
        element.attrs["href"].orEmpty(),
        element.attrs["data-href"].orEmpty(),
        element.attrs["data-url"].orEmpty(),
        element.attrs["src"].orEmpty()
    )

    private fun elementImageUrl(element: Element): String = firstNonBlank(
        element.attrs["data-original"].orEmpty(),
        element.attrs["data-src"].orEmpty(),
        element.attrs["src"].orEmpty(),
        element.attrs["poster"].orEmpty()
    )

    private fun selectElements(html: String, selector: String): List<Element> {
        return selector.split(',').flatMap { selectSimple(html, it.trim()) }
    }

    private fun selectSimple(html: String, selector: String): List<Element> {
        if (selector.isBlank()) return emptyList()
        if (selector == ":scope") return listOf(Element(html, html, emptyMap()))
        val tag = Regex("^[a-zA-Z][a-zA-Z0-9_-]*").find(selector)?.value ?: "[a-zA-Z][a-zA-Z0-9_-]*"
        val cls = Regex("\\.([a-zA-Z0-9_-]+)").find(selector)?.groupValues?.getOrNull(1)
        val attrMatch = Regex("\\[([a-zA-Z_:][-a-zA-Z0-9_:.]*)(\\^?=)?['\"]?([^'\"]*)?['\"]?]").find(selector)
        val attrName = attrMatch?.groupValues?.getOrNull(1).orEmpty()
        val attrOp = attrMatch?.groupValues?.getOrNull(2).orEmpty()
        val attrValue = attrMatch?.groupValues?.getOrNull(3).orEmpty()
        val paired = Regex("<($tag)\\b([^>]*)>([\\s\\S]*?)</\\1>", setOf(RegexOption.IGNORE_CASE))
            .findAll(html).map { Element(it.value, it.groupValues[3], parseAttrs(it.groupValues[2])) }
        val single = Regex("<($tag)\\b([^>]*)/?>", setOf(RegexOption.IGNORE_CASE))
            .findAll(html).map { Element(it.value, "", parseAttrs(it.groupValues[2])) }
        return (paired + single).filter { e ->
            val classOk = cls.isNullOrBlank() || e.attrs["class"].orEmpty().split(Regex("\\s+")).any { it.equals(cls, true) }
            val attrOk = attrName.isBlank() || when (attrOp) {
                "=" -> e.attrs[attrName].orEmpty() == attrValue
                "^=" -> e.attrs[attrName].orEmpty().startsWith(attrValue)
                else -> e.attrs.containsKey(attrName)
            }
            classOk && attrOk
        }.toList()
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        Regex("([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(['\"])(.*?)\\2", RegexOption.DOT_MATCHES_ALL)
            .findAll(raw).forEach { map[it.groupValues[1]] = WebParseHtml.decodeEntities(it.groupValues[3]) }
        return map
    }

    private companion object {
        const val TAG = "WebParseListExtractor"
        val navigationKeywords = listOf("page", "list", "fenlei", "category", "search", "tag", "type", "sort", "#", "javascript")
        val playPagePattern = Regex("-\\d+-\\d+")
        val anchorPattern = Regex(
            "<a\\b([^>]*?)href=[\"']([^\"']*/\\d+(?:\\.html)?(?:/|[?#][^\"']*)?)[\"']([^>]*)>[\\s\\S]*?</a>",
            setOf(RegexOption.IGNORE_CASE)
        )
    }
}
