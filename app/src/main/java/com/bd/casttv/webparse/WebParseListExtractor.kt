package com.bd.casttv.webparse

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

/** Parsed movie item from a web list page. */
data class ParsedListMovie(
    val title: String,
    val coverUrl: String,
    val detailUrl: String
)

/** 列表页解析结果，含影片条目与下一页地址（无下一页时 nextPageUrl 为 null）。 */
data class ParsedListResult(
    val movies: List<ParsedListMovie>,
    val nextPageUrl: String? = null
)

class WebParseListExtractor {
    suspend fun extractList(url: String): ParsedListResult = withContext(Dispatchers.IO) {
        val html = WebParseExtractor.fetchText(url)
        WebParseExtractor.lastParsedUrl = url
        WebParseExtractor.lastParsedHtml = html
        val movies = parseList(url, html)
        val nextPageUrl = extractNextPageUrl(url, html)
        Log.d(TAG, "extractList url=$url htmlLength=${html.length} movies=${movies.size} nextPageUrl=$nextPageUrl")
        if (movies.isEmpty()) error("未从列表页提取到影片条目")
        ParsedListResult(movies, nextPageUrl)
    }

    fun parseWithJsonRule(html: String, jsonRule: String, baseUrl: String): ParsedListResult {
        val json = JSONObject(jsonRule)
        if (json.optString("type") != "list") error("JSON 校验失败：type 必须为 list")
        val titleSelector = json.optString("titleSelector").trim()
        val detailUrlSelector = json.optString("detailUrlSelector").trim()
        val coverSelector = json.optString("coverSelector").trim()
        val nextPageSelector = json.optString("nextPageSelector").trim()
        if (titleSelector.isBlank()) error("JSON 校验失败：titleSelector 不能为空")
        if (detailUrlSelector.isBlank()) error("JSON 校验失败：detailUrlSelector 不能为空")
        val ruleBaseUrl = normalizeBaseUrl(json.optString("baseUrl"), baseUrl)
        val titleElements = selectElements(html, titleSelector)
        val urlElements = selectElements(html, detailUrlSelector)
        val coverElements = if (coverSelector.isBlank()) emptyList() else selectElements(html, coverSelector)
        if (titleElements.isEmpty()) error("JSON 解析失败：titleSelector 未命中任何元素")
        if (urlElements.isEmpty()) error("JSON 解析失败：detailUrlSelector 未命中任何元素")
        val count = maxOf(titleElements.size, urlElements.size)
        val movies = (0 until count).mapNotNull { index ->
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
        // 下一页地址：多级兜底链（L1 用户规则 → L3 数字按钮组 → L3 class/文字）
        val nextPageUrl = extractNextPageUrlChain(ruleBaseUrl, html, nextPageSelector)
        Log.d(TAG, "parseWithJsonRule nextPageSelector='${nextPageSelector.take(40)}' movies=${movies.size} nextPageUrl=$nextPageUrl")
        return ParsedListResult(movies, nextPageUrl)
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
        // :scope 表示「当前元素自身」，返回整个输入 HTML 作为单一元素，供上层在
        // 元素上下文中直接取 text/href/html/outerHtml，不交给 Jsoup 重新解析。
        if (selector == ":scope") return listOf(Element(html, html, emptyMap()))
        // Jsoup 的 select() 支持完整标准 CSS Selector 语法：后代/子/伪类/属性前缀等。
        return Jsoup.parse(html).select(selector).map { el ->
            Element(
                outer = el.outerHtml(),
                inner = el.html(),
                attrs = el.attributes().asList().associate { it.key to it.value }
            )
        }
    }

    // ---- 下一页地址提取 ----

    /**
     * 下一页地址多级兜底链：
     * L1 用户规则 nextPageSelector（Jsoup select）→ 没命中继续回退
     * L3-1 数字按钮组（当前页 N → 取 N+1 的 href，查父 li 的 active）
     * L3-2 class 含 next / 文字含"下一页"（extractNextPageUrl）
     * 任一级命中且通过 isValidNextPageUrl 校验即返回；全未命中返回 null。
     */
    private fun extractNextPageUrlChain(
        baseUrl: String,
        html: String,
        nextPageSelector: String
    ): String? {
        // L1：用户规则 nextPageSelector
        if (nextPageSelector.isNotBlank()) {
            val hit = selectElements(html, nextPageSelector).firstNotNullOfOrNull { el ->
                elementUrl(el).takeIf { it.isNotBlank() }
                    ?.let { WebParseHtml.absolute(baseUrl, it) }
                    ?.takeIf { isValidNextPageUrl(it) }
            }
            if (hit != null) {
                Log.d(TAG, "nextPage L1(selector) hit=$hit")
                return hit
            }
            Log.d(TAG, "nextPage L1(selector) miss, fallback to numeric-pager")
        }
        // L3-1：数字按钮组（当前页 N → N+1）
        extractFromNumericPager(baseUrl, html)?.let {
            Log.d(TAG, "nextPage L3(numeric-pager) hit=$it")
            return it
        }
        Log.d(TAG, "nextPage L3(numeric-pager) miss, fallback to class/text")
        // L3-2：class/文字兜底（现有）
        return extractNextPageUrl(baseUrl, html)
    }

    /**
     * 数字按钮组识别：定位"有序数字排列"的分页按钮，从当前页 N 取 N+1 的 href。
     * 基于 cupfox/netfly（STUI 组件）实测校准：
     * - 按钮文本为纯数字（1-3 位），排除"第1集"/价格/年份(4 位)/"1/314"总页数指示
     * - 仅在分页容器内查找（[class*=pag]/[class*=page]/[class*=pager]/nav/ul），
     *   避免误判详情集数、导航数字等
     * - 当前页 active/current/selected/on 常在父 <li> 上而非 <a> 上，故查 parent
     * - 取 N+1 对应按钮的 href，经 absolute + isValidNextPageUrl 校验
     * - 末页无 N+1 → 返回 null（由上层"列表为空则停"自然停止）
     */
    private fun extractFromNumericPager(baseUrl: String, html: String): String? {
        val numRegex = Regex("^\\d{1,3}$")
        val doc = Jsoup.parse(html)
        val containers = doc.select("[class*=pag], [class*=page], [class*=pager], nav, ul")
        for (container in containers) {
            val numAs = container.select("a").toList().filter { numRegex.matches(it.text().trim()) }
            if (numAs.size < 2) continue
            // 至少存在两个连续递增的数字，排除散落数字链接
            val nums = numAs.mapNotNull { it.text().trim().toIntOrNull() }.sorted()
            val hasAscending = nums.zipWithNext().any { (a, b) -> b == a + 1 }
            if (!hasAscending) continue
            // 当前页识别：active/current/selected/on 在父 <li> 上，或 aria-current
            val currentBtn = numAs.firstOrNull { a ->
                val p = a.parent()
                val pcls = p?.className()?.lowercase().orEmpty()
                pcls.contains("active") || pcls.contains("current") ||
                    pcls.contains("selected") || pcls.contains("on") ||
                    p?.attr("aria-current")?.isNotBlank() == true
            } ?: continue
            val n = currentBtn.text().trim().toIntOrNull() ?: continue
            // 取 N+1 对应按钮的 href
            val next = numAs.firstOrNull { it.text().trim().toIntOrNull() == n + 1 } ?: continue
            val href = next.attr("href").trim()
            if (href.isBlank()) continue
            val abs = WebParseHtml.absolute(baseUrl, href)
            if (isValidNextPageUrl(abs)) return abs
        }
        return null
    }

    /** 从列表页 HTML 中提取下一页地址：优先 class 匹配，其次文字匹配。 */
    private fun extractNextPageUrl(baseUrl: String, html: String): String? {
        // 兜底逻辑保留：优先 class 含 next 关键词，其次文字含"下一页"等。
        // HTML 解析改用 Jsoup，替代原先基于 Regex 的 <a> 标签扫描，更稳健。
        val nextClassKeywords = listOf("next", "page-next", "nextpage", "pagenext", "next-page")
        val nextTextKeywords = listOf("下一页", "下页", "next", "›", "»", "→")
        var textFallback: String? = null
        for (a in Jsoup.parse(html).select("a[href]")) {
            val href = a.attr("href").trim()
            if (href.isBlank()) continue
            val classVal = a.attr("class").lowercase()
            val text = a.text().trim()
            // 优先：class 含 next 关键词
            if (nextClassKeywords.any { classVal.contains(it) }) {
                val abs = WebParseHtml.absolute(baseUrl, href)
                if (isValidNextPageUrl(abs)) return abs
            }
            // 备选：文字内容含"下一页"等（限短文本，避免误命中详情链接）
            if (textFallback == null && text.length <= 12) {
                if (nextTextKeywords.any { text.equals(it, ignoreCase = true) || text.contains(it) }) {
                    val abs = WebParseHtml.absolute(baseUrl, href)
                    if (isValidNextPageUrl(abs)) textFallback = abs
                }
            }
        }
        return textFallback
    }

    private fun isValidNextPageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.contains("javascript:")) return false
        if (lower == "#" || lower.endsWith("#")) return false
        return true
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
