package com.bd.casttv.webparse

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

class RuleBasedAdapter(
    private val context: Context,
    private val specifiedFileName: String? = null
) : SiteAdapter {
    private var activeRule: AdapterRule? = null

    override fun canHandle(url: String, html: String): Boolean {
        val matched = findRule(url, html, specifiedFileName, force = specifiedFileName != null)
        activeRule = matched
        return matched != null
    }

    suspend fun parseSpecified(url: String, html: String, fileName: String): ParsedMovie = withContext(Dispatchers.Default) {
        val rule = findRule(url, html, fileName, force = true) ?: error("未找到 JSON 规则：$fileName")
        activeRule = rule
        parseWithRule(rule, url, html)
    }

    override suspend fun parseDetail(url: String, html: String): ParsedMovie = withContext(Dispatchers.Default) {
        val rule = activeRule ?: findRule(url, html, specifiedFileName, force = specifiedFileName != null)
        ?: error("没有匹配的 JSON 解析规则")
        activeRule = rule
        parseWithRule(rule, url, html)
    }

    override suspend fun resolvePlayUrl(playPageUrl: String): String? = withContext(Dispatchers.IO) {
        val rule = activeRule ?: specifiedFileName?.let { loadRuleFile(context, it) }
        val playResolve = rule?.json?.optJSONObject("playResolve") ?: return@withContext null
        runCatching { resolveWithRule(playResolve, playPageUrl, playPageUrl) }
            .onFailure { Log.w(TAG, "resolve rule failed: ${it.message}") }
            .getOrNull()
    }

    private fun parseWithRule(rule: AdapterRule, url: String, html: String): ParsedMovie {
        val json = rule.json
        val fields = json.optJSONObject("detail")?.optJSONObject("fields") ?: JSONObject()
        val title = fieldValue(fields.optJSONObject("title"), html, url, "未命名影片", true).ifBlank { "未命名影片" }
        val cover = fieldValue(fields.optJSONObject("cover"), html, url, "", false)
        val desc = fieldValue(fields.optJSONObject("description"), html, url, "", false)
        val category = firstNonBlank(
            fieldValue(fields.optJSONObject("category"), html, url, "", false),
            fieldValue(fields.optJSONObject("type"), html, url, "", false)
        )
        val year = fieldValue(fields.optJSONObject("year"), html, url, "", false)
        val area = fieldValue(fields.optJSONObject("area"), html, url, "", false)
        val director = fieldValue(fields.optJSONObject("director"), html, url, "", false)
        val actors = fieldValue(fields.optJSONObject("actors"), html, url, "", false)
        val sources = parseSources(json.optJSONObject("sources"), url, html).ifEmpty {
            listOf(ParsedSource("默认线路", listOf(ParsedEpisode("播放", url))))
        }
        return ParsedMovie(title, cover, desc, category, year, area, director, actors, sources)
    }

    private fun parseSources(cfg: JSONObject?, baseUrl: String, html: String): List<ParsedSource> {
        if (cfg == null) return emptyList()
        return when (cfg.optString("mode")) {
            "single" -> parseSingleSource(cfg, baseUrl, html)
            "json_fields" -> parseJsonFieldSources(cfg, baseUrl, html)
            else -> parseHtmlBlockSources(cfg, baseUrl, html)
        }
    }

    private fun parseSingleSource(cfg: JSONObject, baseUrl: String, html: String): List<ParsedSource> {
        val sourceName = cfg.optString("sourceName", "默认线路").ifBlank { "默认线路" }
        val episodeName = cfg.optString("episodeName", "播放").ifBlank { "播放" }
        val url = fieldValue(cfg.optJSONObject("playPageUrl"), html, baseUrl, baseUrl, false)
            .replace("{{currentUrl}}", baseUrl)
            .ifBlank { baseUrl }
        val resolved = if (cfg.optBoolean("resolvedUrlWhenPlayable", true) && WebParseHtml.looksPlayable(url)) url else null
        return listOf(ParsedSource(sourceName, listOf(ParsedEpisode(episodeName, url, resolved))))
    }

    private fun parseHtmlBlockSources(cfg: JSONObject, baseUrl: String, html: String): List<ParsedSource> {
        val blockCfg = cfg.optJSONObject("sourceBlocks") ?: JSONObject()
        val selectors = blockCfg.optJSONArray("selectors")?.strings().orEmpty()
        val matchedBlocks = selectors.flatMap { selectElements(html, it).map { e -> e.outer } }
        val blocks = matchedBlocks.ifEmpty { listOf(html) }
        val episodesCfg = cfg.optJSONObject("episodes") ?: return emptyList()
        val result = mutableListOf<ParsedSource>()
        blocks.forEachIndexed { sourceIndex, block ->
            val sourceName = sourceName(cfg.optJSONObject("sourceName"), block, sourceIndex)
            val episodes = parseEpisodesFromBlock(block, episodesCfg, baseUrl, relaxUrlAndNameFilters = false)
                .ifEmpty { parseEpisodesFromBlock(block, episodesCfg, baseUrl, relaxUrlAndNameFilters = true) }
            if (episodes.isNotEmpty()) result.add(ParsedSource(sourceName, episodes))
        }
        if (result.isNotEmpty()) return result
        val fallback = cfg.optJSONObject("fallback") ?: return emptyList()
        return parseSingleSource(fallback, baseUrl, html)
    }

    private fun sourceName(cfg: JSONObject?, block: String, index: Int): String {
        val defaultName = template(cfg?.optString("defaultTemplate"), index, "线路${index + 1}")
        return fieldValue(cfg, block, "", defaultName, false).ifBlank { defaultName }
    }

    private fun parseEpisodesFromBlock(
        block: String,
        episodesCfg: JSONObject,
        baseUrl: String,
        relaxUrlAndNameFilters: Boolean
    ): List<ParsedEpisode> {
        val itemSelector = episodesCfg.optString("itemSelector", "a[href]")
        return selectElements(block, itemSelector).mapIndexedNotNull { epIndex, element ->
            val nameCfg = episodesCfg.optJSONObject("name")
            val urlCfg = episodesCfg.optJSONObject("url")
            val defaultName = element.text.ifBlank { template(nameCfg?.optString("defaultTemplate"), epIndex, "播放") }
            val name = if (nameCfg != null) {
                fieldValueForElement(nameCfg, element, baseUrl, defaultName, false)
            } else {
                defaultName
            }.ifBlank { "播放" }
            val epUrl = if (urlCfg != null) {
                fieldValueForElement(urlCfg, element, baseUrl, "", true)
            } else {
                WebParseHtml.absolute(baseUrl, element.attrs["href"].orEmpty())
            }
            if (acceptEpisode(epUrl, name, episodesCfg.optJSONObject("filters"), relaxUrlAndNameFilters)) {
                ParsedEpisode(name, epUrl, epUrl.takeIf { WebParseHtml.looksPlayable(it) })
            } else null
        }.distinctBy { it.playPageUrl }
    }

    private fun acceptEpisode(url: String, name: String, filters: JSONObject?, relaxUrlAndNameFilters: Boolean = false): Boolean {
        if (filters == null) return url.isNotBlank()
        if (filters.optBoolean("skipEmptyUrl", true) && url.isBlank()) return false
        if (filters.optBoolean("skipJavascriptUrl", true) && url.startsWith("javascript", true)) return false
        val maxName = filters.optInt("maxNameLength", 60)
        if (maxName > 0 && name.length > maxName) return false
        if (relaxUrlAndNameFilters) return true
        val contains = filters.optJSONArray("urlContainsAny")?.strings().orEmpty().any { url.contains(it, ignoreCase = true) }
        val urlRegex = filters.optJSONArray("urlRegexAny")?.strings().orEmpty().any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(url) }
        val nameRegex = filters.optJSONArray("nameRegexAny")?.strings().orEmpty().any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(name) }
        val playable = filters.optBoolean("allowPlayableUrl", true) && WebParseHtml.looksPlayable(url)
        val hasUrlContainsRule = (filters.optJSONArray("urlContainsAny")?.length() ?: 0) > 0
        val hasStrictRule = (filters.optJSONArray("urlRegexAny")?.length() ?: 0) > 0 ||
            (filters.optJSONArray("nameRegexAny")?.length() ?: 0) > 0 || filters.has("allowPlayableUrl")
        val hasAnyRule = hasUrlContainsRule || hasStrictRule
        if (!hasAnyRule) return true
        return contains || urlRegex || nameRegex || playable || !hasStrictRule
    }

    private fun parseJsonFieldSources(cfg: JSONObject, baseUrl: String, html: String): List<ParsedSource> {
        val jsonObj = jsonSources(cfg.optJSONArray("jsonSources"), baseUrl, html).firstOrNull() ?: return emptyList()
        val vodPaths = cfg.optJSONArray("vodObjectPaths")?.strings().orEmpty()
        val vod = vodPaths.firstNotNullOfOrNull { jsonPathAny(jsonObj, it) as? JSONObject } ?: jsonObj
        val fromCfg = cfg.optJSONObject("sourceNameField") ?: JSONObject()
        val urlCfg = cfg.optJSONObject("episodeUrlField") ?: JSONObject()
        val fromText = firstPath(vod, fromCfg.optJSONArray("paths")?.strings().orEmpty())
        val playText = firstPath(vod, urlCfg.optJSONArray("paths")?.strings().orEmpty())
        val fromParts = fromText.split(fromCfg.optString("split", "$$$"))
            .map { it.trim() }.filter { it.isNotBlank() }
        val sourceParts = playText.split(urlCfg.optString("sourceSplit", "$$$"))
            .map { it.trim() }.filter { it.isNotBlank() }
        val episodeSplit = urlCfg.optString("episodeSplit", "#")
        val nameUrlSplit = urlCfg.optString("nameUrlSplit", "$")
        return sourceParts.mapIndexedNotNull { sourceIndex, part ->
            val episodes = part.split(episodeSplit).mapIndexedNotNull episodeLoop@{ epIndex, item ->
                val raw = item.trim()
                if (raw.isBlank()) return@episodeLoop null
                val pieces = raw.split(nameUrlSplit, limit = 2)
                val name = if (pieces.size == 2) pieces[0].trim().ifBlank { "第${epIndex + 1}集" } else "第${epIndex + 1}集"
                val link = if (pieces.size == 2) pieces[1].trim() else raw
                val finalUrl = postprocess(link, cfg.optJSONArray("episodeUrlPostprocess"), baseUrl).ifBlank { return@episodeLoop null }
                ParsedEpisode(name, finalUrl, finalUrl.takeIf { WebParseHtml.looksPlayable(it) })
            }.distinctBy { it.playPageUrl }
            if (episodes.isEmpty()) null else ParsedSource(fromParts.getOrNull(sourceIndex)?.ifBlank { null } ?: "线路${sourceIndex + 1}", episodes)
        }
    }

    private fun jsonSources(arr: JSONArray?, baseUrl: String, html: String): List<JSONObject> {
        if (arr == null) return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val src = arr.optJSONObject(i) ?: continue
            val text = when (src.optString("type")) {
                "body_json" -> html.trim().takeIf { it.startsWith("{") }
                "js_var" -> extractJsVar(html, src.optString("name"), src.optString("valueType", "object"))
                "http_api" -> runCatching { WebParseExtractor.fetchText(applyTemplate(src.optString("urlTemplate"), baseUrl, baseUrl, emptyMap()), src.optInt("timeoutMs", 15000)) }.getOrNull()
                else -> null
            }
            runCatching { JSONObject(text ?: "") }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    private fun fieldValue(cfg: JSONObject?, input: String, baseUrl: String, defaultValue: String, required: Boolean): String {
        if (cfg == null) return defaultValue
        val rules = cfg.optJSONArray("rules") ?: JSONArray().put(cfg)
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val value = extractRule(rule, input, baseUrl)
            if (value.isNotBlank()) return value
        }
        if (required || cfg.optBoolean("required", false)) {
            if (cfg.has("default")) return cfg.optString("default")
        }
        return cfg.optString("default", defaultValue)
    }

    private fun fieldValueForElement(cfg: JSONObject?, element: Element, baseUrl: String, defaultValue: String, required: Boolean): String {
        if (cfg == null) return defaultValue
        val rules = cfg.optJSONArray("rules") ?: JSONArray().put(cfg)
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val value = extractRuleForElement(rule, element, baseUrl)
            if (value.isNotBlank()) return value
        }
        if (required || cfg.optBoolean("required", false)) {
            if (cfg.has("default")) return cfg.optString("default")
        }
        return cfg.optString("default", defaultValue)
    }

    private fun extractRuleForElement(rule: JSONObject, element: Element, baseUrl: String): String {
        if (rule.optString("type") == "css_selector" && rule.optString("selector") == ":scope") {
            val attr = rule.optString("attr", "text")
            val raw = when (attr) {
                "text" -> element.text
                "html" -> element.inner
                "outerHtml" -> element.outer
                else -> element.attrs[attr].orEmpty()
            }
            return postprocess(raw, rule.optJSONArray("postprocess"), baseUrl)
        }
        return extractRule(rule, element.outer, baseUrl)
    }

    private fun extractRule(rule: JSONObject, input: String, baseUrl: String): String {
        val raw = when (rule.optString("type")) {
            "fixed" -> rule.optString("value")
            "meta_og" -> meta(rule.optString("property"), input)
            "regex" -> regexExtract(input, rule)
            "css_selector" -> {
                val element = selectElements(input, rule.optString("selector")).getOrNull(rule.optInt("index", 0))
                val attr = rule.optString("attr", "text")
                if (attr == "text") element?.text.orEmpty() else element?.attrs?.get(attr).orEmpty()
            }
            "js_var" -> {
                val js = extractJsVar(input, rule.optString("name"), rule.optString("valueType", "object"))
                val path = rule.optString("path")
                if (path.isNotBlank() && js != null) runCatching { jsonPathAny(JSONObject(js), path)?.toString().orEmpty() }.getOrDefault("") else js.orEmpty()
            }
            "json_path" -> runCatching { jsonPathAny(JSONObject(input), rule.optString("path"))?.toString().orEmpty() }.getOrDefault("")
            else -> ""
        }
        return postprocess(raw, rule.optJSONArray("postprocess"), baseUrl)
    }

    private suspend fun resolveWithRule(cfg: JSONObject, playPageUrl: String, detailUrl: String): String? {
        val variables = buildVariables(cfg.optJSONObject("variables"), playPageUrl).toMutableMap()
        var current = playPageUrl
        val steps = cfg.optJSONArray("pipeline") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            when (step.optString("type")) {
                "direct", "return_if_playable" -> if (WebParseHtml.looksPlayable(current)) return current
                "jianpian_unwrap" -> current = unwrapJianpian(current) ?: current
                "fetch_html" -> current = WebParseExtractor.fetchText(current)
                "url_decode" -> current = runCatching { URLDecoder.decode(current, "UTF-8") }.getOrDefault(current)
                "base64_decode" -> current = decodeMaybeBase64(current)
                "absolute_url" -> current = WebParseHtml.absolute(playPageUrl, current)
                "regex", "js_var", "json_path" -> current = extractRule(step, current, playPageUrl)
                "http_api" -> {
                    variables.putAll(buildVariables(cfg.optJSONObject("variables"), current))
                    val apiUrl = applyTemplate(step.optString("urlTemplate"), playPageUrl, detailUrl, variables)
                    val body = WebParseExtractor.fetchText(apiUrl, step.optInt("timeoutMs", 15000))
                    current = fieldValue(step.optJSONObject("extract"), body, apiUrl, "", true)
                }
            }
        }
        return current.takeIf { WebParseHtml.looksPlayable(it) }
    }

    private fun buildVariables(cfg: JSONObject?, playPageUrl: String): Map<String, String> {
        if (cfg == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        cfg.keys().forEach { key ->
            val rule = cfg.optJSONObject(key)
            val v = fieldValue(rule, playPageUrl, playPageUrl, "", rule?.optBoolean("required", false) == true)
            if (v.isNotBlank()) out[key] = v
        }
        return out
    }

    private fun applyTemplate(tpl: String, currentUrl: String, detailUrl: String, vars: Map<String, String>): String {
        val uri = runCatching { URI(currentUrl) }.getOrNull()
        val origin = uri?.let { "${it.scheme}://${it.host}${if (it.port > 0) ":${it.port}" else ""}" }.orEmpty()
        var out = tpl.replace("{{currentUrl}}", currentUrl)
            .replace("{{detailUrl}}", detailUrl)
            .replace("{{origin}}", origin)
            .replace("{{host}}", uri?.host.orEmpty())
            .replace("{{path}}", uri?.path.orEmpty())
        vars.forEach { (k, v) -> out = out.replace("{{$k}}", v) }
        return out
    }

    private fun findRule(url: String, html: String, fileName: String?, force: Boolean): AdapterRule? {
        val rules = if (fileName.isNullOrBlank()) loadRules(context) else listOfNotNull(loadRuleFile(context, fileName))
        return if (force) rules.firstOrNull() else rules.firstOrNull { match(it.json.optJSONObject("match"), url, html) }
    }

    private fun match(cfg: JSONObject?, url: String, html: String): Boolean {
        if (cfg == null) return false
        val op = cfg.optString("operator", "OR").uppercase(Locale.US)
        val conditions = cfg.optJSONArray("conditions") ?: return false
        val results = (0 until conditions.length()).map { matchCondition(conditions.optJSONObject(it), url, html) }
        return if (op == "AND") results.all { it } else results.any { it }
    }

    private fun matchCondition(c: JSONObject?, url: String, html: String): Boolean {
        if (c == null) return false
        val case = c.optBoolean("caseSensitive", false)
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        fun norm(s: String) = if (case) s else s.lowercase(Locale.US)
        return when (c.optString("type")) {
            "group" -> match(c, url, html)
            "domainContains" -> norm(host).contains(norm(c.optString("value")))
            "domainEquals" -> norm(host) == norm(c.optString("value"))
            "domainRegex" -> Regex(c.optString("pattern"), if (case) emptySet() else setOf(RegexOption.IGNORE_CASE)).containsMatchIn(host)
            "urlRegex" -> Regex(c.optString("pattern"), if (case) emptySet() else setOf(RegexOption.IGNORE_CASE)).containsMatchIn(url)
            "htmlRegex" -> Regex(c.optString("pattern"), setOfNotNull(if (case) null else RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(html)
            "htmlContains" -> {
                val values = c.optJSONArray("values")?.strings().orEmpty()
                val op = c.optString("operator", "OR").uppercase(Locale.US)
                val target = norm(html)
                val res = values.map { target.contains(norm(it)) }
                if (op == "AND") res.all { it } else res.any { it }
            }
            else -> false
        }
    }

    private data class Element(val outer: String, val inner: String, val attrs: Map<String, String>) {
        val text: String get() = WebParseHtml.clean(inner.ifBlank { outer })
    }

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

    private fun meta(property: String, html: String): String {
        val key = property.substringAfter("og:", property)
        return WebParseHtml.attr(html, "<meta[^>]+property=[\"']og:${Regex.escape(key)}[\"'][^>]+content=[\"']([^\"']+)[\"']")
    }

    private fun regexExtract(input: String, rule: JSONObject): String {
        val opts = mutableSetOf<RegexOption>()
        val flags = rule.optJSONArray("flags")?.strings() ?: listOf("IGNORE_CASE", "DOT_MATCHES_ALL")
        if ("IGNORE_CASE" in flags) opts.add(RegexOption.IGNORE_CASE)
        if ("DOT_MATCHES_ALL" in flags) opts.add(RegexOption.DOT_MATCHES_ALL)
        if ("MULTILINE" in flags) opts.add(RegexOption.MULTILINE)
        val m = Regex(rule.optString("pattern"), opts).find(input) ?: return ""
        return m.groupValues.getOrNull(rule.optInt("group", 1)).orEmpty()
    }

    private fun extractJsVar(html: String, name: String, valueType: String): String? {
        if (name.isBlank()) return null
        val pattern = if (name.startsWith("window.")) Regex("${Regex.escape(name)}\\s*=\\s*", RegexOption.IGNORE_CASE) else Regex("(?:var\\s+|let\\s+|const\\s+)?${Regex.escape(name)}\\s*=\\s*", RegexOption.IGNORE_CASE)
        val marker = pattern.find(html) ?: return null
        if (valueType == "string") {
            return Regex("['\"]([^'\"]+)['\"]").find(html.substring(marker.range.last + 1))?.groupValues?.getOrNull(1)
        }
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

    private fun jsonPathAny(root: Any?, path: String): Any? {
        if (root == null || !path.startsWith("$")) return null
        var current: Any? = root
        Regex("\\.([A-Za-z0-9_]+)|\\[(\\d+)]").findAll(path.drop(1)).forEach { m ->
            current = if (m.groupValues[1].isNotBlank()) (current as? JSONObject)?.opt(m.groupValues[1]) else (current as? JSONArray)?.opt(m.groupValues[2].toInt())
        }
        return current
    }

    private fun firstPath(obj: JSONObject, paths: List<String>): String = paths.firstNotNullOfOrNull { jsonPathAny(obj, it)?.toString()?.takeIf { v -> v.isNotBlank() } }.orEmpty()

    private fun postprocess(value: String, steps: JSONArray?, baseUrl: String): String {
        var out = value
        if (steps == null) return WebParseHtml.decodeEntities(out).trim()
        for (i in 0 until steps.length()) {
            val item = steps.opt(i)
            out = when (item) {
                is String -> when (item) {
                    "trim" -> out.trim()
                    "decode_html_entities" -> WebParseHtml.decodeEntities(out)
                    "url_decode" -> runCatching { URLDecoder.decode(out, "UTF-8") }.getOrDefault(out)
                    "absolute_url" -> WebParseHtml.absolute(baseUrl, out)
                    "base64_decode" -> decodeMaybeBase64(out)
                    else -> out
                }
                is JSONObject -> when (item.optString("op")) {
                    "replace" -> out.replace(Regex(item.optString("pattern")), item.optString("replacement"))
                    "substring_before" -> out.substringBefore(item.optString("value"), out)
                    "substring_after" -> out.substringAfter(item.optString("value"), out)
                    else -> out
                }
                else -> out
            }
        }
        return out.trim()
    }

    private fun decodeMaybeBase64(value: String): String {
        val raw = value.trim()
        if (raw.isBlank()) return raw
        val urlDecoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val candidates = listOf(urlDecoded, raw)
        for (c in candidates) {
            if (!Regex("^[A-Za-z0-9+/=_-]{16,}$").matches(c)) continue
            val normalized = c.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            val decoded = runCatching { String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
            if (!decoded.isNullOrBlank() && (decoded.startsWith("http") || decoded.contains(".m3u8") || decoded.contains(".mp4"))) return decoded
        }
        return urlDecoded
    }

    private fun unwrapJianpian(url: String): String? {
        val raw = WebParseHtml.decodeEntities(url).trim()
        if (!raw.startsWith("jianpian://", ignoreCase = true)) return null
        val pathStart = raw.indexOf("path=", ignoreCase = true)
        if (pathStart < 0) return null
        val pathValue = raw.substring(pathStart + "path=".length).trim()
        if (!pathValue.startsWith("http", ignoreCase = true)) return null
        val decoded = runCatching { URLDecoder.decode(pathValue, "UTF-8") }.getOrDefault(pathValue).trim()
        return decoded.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun template(tpl: String?, index: Int, fallback: String): String = (tpl ?: fallback).replace("{{index}}", index.toString()).replace("{{index1}}", (index + 1).toString())
    private fun firstNonBlank(vararg values: String): String = values.firstOrNull { it.isNotBlank() }.orEmpty()
    private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

    companion object {
        private const val TAG = "RuleBasedAdapter"
        private const val DIR = "json_adapters"

        data class RuleInfo(val name: String, val version: String, val fileName: String, val pageKind: ParsePageKind)
        data class AdapterRule(val file: File, val json: JSONObject) {
            val name: String get() = json.optJSONObject("meta")?.optString("name").orEmpty().ifBlank { file.nameWithoutExtension }
            val version: String get() = json.optJSONObject("meta")?.optString("version").orEmpty().ifBlank { "1.0.0" }
            val pageKind: ParsePageKind get() = if (json.optString("type").equals("list", true)) ParsePageKind.LIST else ParsePageKind.DETAIL
        }

        fun adapterDir(context: Context): File = File(context.applicationContext.filesDir, DIR).apply { if (!exists()) mkdirs() }

        fun listRuleInfos(context: Context): List<RuleInfo> = loadRules(context).map { RuleInfo(it.name, it.version, it.file.name, it.pageKind) }

        fun deleteRule(context: Context, fileName: String): Boolean {
            val safe = fileName.substringAfterLast('/').substringAfterLast('\\')
            return File(adapterDir(context), safe).takeIf { it.exists() && it.extension.equals("json", true) }?.delete() == true
        }

        fun readRuleText(context: Context, fileName: String): String {
            val safe = fileName.substringAfterLast('/').substringAfterLast('\\')
            val file = File(adapterDir(context), safe)
            return file.takeIf { it.exists() && it.extension.equals("json", true) }?.readText(Charsets.UTF_8).orEmpty()
        }

        fun validateRuleJson(text: String): JSONObject {
            val obj = JSONObject(text)
            val missing = listOf("match", "sources", "playResolve").filter { !obj.has(it) || obj.optJSONObject(it) == null }
            if (missing.isNotEmpty()) error("缺少必要字段：${missing.joinToString("、")}")
            return obj
        }

        fun saveRule(context: Context, text: String): RuleInfo {
            val obj = validateRuleJson(text)
            val meta = obj.optJSONObject("meta") ?: JSONObject()
            val name = (meta.optString("name").ifBlank { obj.optString("name") }).ifBlank { "json_adapter_${System.currentTimeMillis()}" }
            val version = (meta.optString("version").ifBlank { obj.optString("version") }).ifBlank { "1.0.0" }
            val pageKind = if (obj.optString("type").equals("list", true)) ParsePageKind.LIST else ParsePageKind.DETAIL
            val safeName = sanitizeFileName(name).ifBlank { "json_adapter_${System.currentTimeMillis()}" }
            val file = File(adapterDir(context), "$safeName.json")
            file.writeText(obj.toString(2), Charsets.UTF_8)
            return RuleInfo(name, version, file.name, pageKind)
        }

        fun saveRule(context: Context, text: String, displayName: String, pageKind: ParsePageKind): RuleInfo {
            val obj = if (pageKind == ParsePageKind.LIST) JSONObject(text) else validateRuleJson(text)
            if (pageKind == ParsePageKind.LIST && !obj.optString("type").equals("list", true)) error("JSON 校验失败：type 必须为 list")
            val meta = obj.optJSONObject("meta") ?: JSONObject().also { obj.put("meta", it) }
            val name = displayName.trim().ifBlank { meta.optString("name") }.ifBlank { "json_adapter_${System.currentTimeMillis()}" }
            val version = meta.optString("version").ifBlank { obj.optString("version") }.ifBlank { "1.0.0" }
            meta.put("name", name)
            if (meta.optString("version").isBlank()) meta.put("version", version)
            if (pageKind == ParsePageKind.LIST) obj.put("type", "list")
            val suffix = if (pageKind == ParsePageKind.LIST) "list" else "detail"
            val safeName = sanitizeFileName("${name}_$suffix").ifBlank { "json_adapter_${System.currentTimeMillis()}_$suffix" }
            val file = File(adapterDir(context), "$safeName.json")
            file.writeText(obj.toString(2), Charsets.UTF_8)
            return RuleInfo(name, version, file.name, pageKind)
        }

        private fun loadRules(context: Context): List<AdapterRule> = adapterDir(context)
            .listFiles { f -> f.isFile && f.extension.equals("json", true) }
            ?.sortedBy { it.name }
            ?.mapNotNull { f -> runCatching { AdapterRule(f, JSONObject(f.readText(Charsets.UTF_8))) }.getOrNull() }
            .orEmpty()

        private fun loadRuleFile(context: Context, fileName: String): AdapterRule? {
            val safe = fileName.substringAfterLast('/').substringAfterLast('\\')
            val f = File(adapterDir(context), safe)
            if (!f.exists() || !f.extension.equals("json", true)) return null
            return runCatching { AdapterRule(f, JSONObject(f.readText(Charsets.UTF_8))) }.getOrNull()
        }

        private fun sanitizeFileName(name: String): String = name.replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]+"), "_").trim('_').take(80)
    }
}
