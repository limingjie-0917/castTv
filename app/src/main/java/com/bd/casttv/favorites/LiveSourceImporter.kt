package com.bd.casttv.favorites

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 直播源导入器：负责下载并解析 M3U/M3U8 或直播源 JSON 直播源文件，
 * 将频道按 `group-title` 分组，并转换为可写入 [FavoritesStore] 的合集数据。
 *
 * 使用方式：
 *   1. UI 层收集用户粘贴的 URL；
 *   2. 在后台线程调用 [LiveSourceImporter.parseUrl]（推荐 IO / worker 线程）；
 *   3. 拿到 [ParseResult] 后 UI 层弹出预览确认；
 *   4. 用户确认后再调用 [FavoritesStore.importLiveSourceGroups] 写入。
 *
 * 单纯的解析层，不依赖 Android Context / FavoritesStore。
 */
object LiveSourceImporter {

    private const val TAG = "LiveSourceImporter"

    /** M3U 单频道条目。 */
    data class LiveChannel(
        val title: String,
        val url: String,
        val logo: String? = null
    )

    /** 按 group-title 分组后的一个直播合集。 */
    data class LiveGroup(
        /** 前缀化后的展示名，形如 "📡 央视"、"📡 未分组"。 */
        val displayName: String,
        /** 原始 group-title；未分组时为空串。 */
        val rawGroupTitle: String,
        val channels: List<LiveChannel>
    )

    /** 完整解析结果。 */
    data class ParseResult(
        val sourceUrl: String,
        val groups: List<LiveGroup>,
        val totalChannels: Int,
        val fileType: String = "未知",
        val downloadedBytes: Int = 0
    )

    /** UI 进度回调：step 从 0 到 5，对应导入弹窗里的 6 个步骤。 */
    fun interface ProgressListener {
        fun onStep(step: Int, running: Boolean, detail: String?)
    }

    private data class DownloadedText(val text: String, val bytes: Int)

    /** 解析过程中可能的失败原因。 */
    sealed class Failure : Throwable() {
        /** github.com 主页 / 目录之类非直链，无法直接下载。 */
        object NonDirectDownload : Failure()
        /** 网络错误（连接失败、超时、非 2xx）。 */
        data class Network(val statusCode: Int, val messageText: String) : Failure()
        /** 内容无法识别为 M3U / 直播源 JSON。 */
        data class Unparsable(val reason: String) : Failure()
        /** URL 空 / 格式非法。 */
        object InvalidUrl : Failure()

        override val message: String
            get() = when (this) {
                NonDirectDownload -> "请粘贴直播源文件的直接下载地址（如 raw.githubusercontent.com/... ）"
                is Network -> "网络请求失败（$statusCode）：$messageText"
                is Unparsable -> "无法解析该直播源文件：$reason"
                InvalidUrl -> "请输入有效的直播源地址"
            }
    }

    /**
     * 是否属于「github.com 主页 / 目录 / 仓库页」这类非直链，需要提示用户改用 raw 直链。
     * 命中规则：host 为 github.com（或 www.github.com）且路径不以 raw / releases/download 之类明确文件资源开头。
     */
    fun isGithubNonDirectUrl(url: String): Boolean {
        val u = url.trim()
        if (u.isBlank()) return false
        return try {
            val parsed = URL(u)
            val host = parsed.host.lowercase()
            if (host != "github.com" && host != "www.github.com") return false
            val path = parsed.path.lowercase()
            // 常见可视为「文件直链」的 github 域路径：release download、raw 文件、blob raw
            val looksLikeDirectFile = path.endsWith(".m3u") ||
                    path.endsWith(".m3u8") ||
                    path.endsWith(".json") ||
                    path.endsWith(".txt") ||
                    path.contains("/releases/download/")
            !looksLikeDirectFile
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 同步解析入口。必须在后台线程调用。
     * @throws Failure
     */
    fun parseUrl(url: String, depth: Int = 0, progress: ProgressListener? = null): ParseResult {
        val trimmed = url.trim()
        if (depth == 0) progress?.onStep(0, true, null)
        if (trimmed.isEmpty()) throw Failure.InvalidUrl
        if (isGithubNonDirectUrl(trimmed)) throw Failure.NonDirectDownload
        // 简单校验协议
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw Failure.InvalidUrl
        }
        if (depth > 3) throw Failure.Unparsable("嵌套深度过大")
        if (depth == 0) progress?.onStep(0, false, "链接格式正确")

        if (depth == 0) progress?.onStep(1, true, null)
        val downloaded = download(trimmed)
        val body = downloaded.text
        val trimmedBody = body.trim()
        if (depth == 0) progress?.onStep(1, false, "下载完成，${formatSize(downloaded.bytes)}")

        if (depth == 0) progress?.onStep(2, true, null)
        val isM3u = trimmedBody.startsWith("#EXTM3U") || looksLikeM3u(trimmedBody)
        val isJson = trimmedBody.startsWith("{") || trimmedBody.startsWith("[")
        val fileType = when {
            isM3u -> "M3U"
            isJson -> "直播源 JSON"
            else -> "未知"
        }
        if (depth == 0) progress?.onStep(2, false, "识别为 $fileType")

        // 优先按 M3U 头部识别
        if (isM3u) {
            if (depth == 0) progress?.onStep(3, true, null)
            val groups = parseM3uText(trimmedBody)
            val total = groups.sumOf { it.channels.size }
            if (total == 0) throw Failure.Unparsable("未从 M3U 内容中解析出频道")
            if (depth == 0) progress?.onStep(3, false, "M3U 解析完成")
            if (depth == 0) progress?.onStep(4, true, null)
            if (depth == 0) progress?.onStep(4, false, "识别到 ${groups.size} 个分组，$total 个频道")
            if (depth == 0) progress?.onStep(5, true, null)
            if (depth == 0) progress?.onStep(5, false, "准备展示预览列表")
            return ParseResult(trimmed, groups, total, fileType, downloaded.bytes)
        }

        // 再尝试按 直播源 JSON 处理
        if (isJson) {
            if (depth == 0) progress?.onStep(3, true, null)
            val liveUrls = extractTvBoxLiveUrls(trimmedBody)
            if (liveUrls.isNotEmpty()) {
                // 汇总所有嵌套 M3U 的分组：同名合集自动合并频道
                val agg = LinkedHashMap<String, MutableList<LiveChannel>>()
                val orderedKeys = LinkedHashMap<String, String>() // rawGroupTitle -> displayName
                for (subUrl in liveUrls) {
                    try {
                        val sub = parseUrl(subUrl, depth + 1)
                        for (g in sub.groups) {
                            val key = g.rawGroupTitle
                            val display = g.displayName
                            orderedKeys.putIfAbsent(key, display)
                            agg.getOrPut(key) { mutableListOf() }.addAll(g.channels)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "sub live source parse failed: $subUrl -> ${t.message}")
                    }
                }
                val groups = orderedKeys.map { (raw, disp) ->
                    LiveGroup(displayName = disp, rawGroupTitle = raw, channels = agg[raw].orEmpty())
                }.filter { it.channels.isNotEmpty() }
                val total = groups.sumOf { it.channels.size }
                if (total == 0) throw Failure.Unparsable("直播源 JSON 中未能解析出可用频道")
                if (depth == 0) progress?.onStep(3, false, "已提取 lives[].url 并处理 M3U")
                if (depth == 0) progress?.onStep(4, true, null)
                if (depth == 0) progress?.onStep(4, false, "识别到 ${groups.size} 个分组，$total 个频道")
                if (depth == 0) progress?.onStep(5, true, null)
                if (depth == 0) progress?.onStep(5, false, "准备展示预览列表")
                return ParseResult(trimmed, groups, total, fileType, downloaded.bytes)
            }
            // 兜底：可能是 JSON 数组直接列频道
            val direct = parseFlatJsonChannels(trimmedBody)
            if (direct.isNotEmpty()) {
                val total = direct.sumOf { it.channels.size }
                if (depth == 0) progress?.onStep(3, false, "JSON 频道列表解析完成")
                if (depth == 0) progress?.onStep(4, true, null)
                if (depth == 0) progress?.onStep(4, false, "识别到 ${direct.size} 个分组，$total 个频道")
                if (depth == 0) progress?.onStep(5, true, null)
                if (depth == 0) progress?.onStep(5, false, "准备展示预览列表")
                return ParseResult(trimmed, direct, total, fileType, downloaded.bytes)
            }
            throw Failure.Unparsable("JSON 中未识别出直播频道")
        }

        // 兜底也当 M3U 尝试
        if (depth == 0) progress?.onStep(3, true, null)
        val fallback = parseM3uText(trimmedBody)
        val total = fallback.sumOf { it.channels.size }
        if (total > 0) {
            if (depth == 0) progress?.onStep(3, false, "按 M3U 兼容模式解析完成")
            if (depth == 0) progress?.onStep(4, true, null)
            if (depth == 0) progress?.onStep(4, false, "识别到 ${fallback.size} 个分组，$total 个频道")
            if (depth == 0) progress?.onStep(5, true, null)
            if (depth == 0) progress?.onStep(5, false, "准备展示预览列表")
            return ParseResult(trimmed, fallback, total, "M3U", downloaded.bytes)
        }
        throw Failure.Unparsable("既非 M3U 也非 直播源 JSON")
    }

    // ------------------------------------------------------------------
    // 网络下载
    // ------------------------------------------------------------------
    private fun download(url: String): DownloadedText {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CastTV-LiveImport/1.0")
                setRequestProperty("Accept", "*/*")
            }
        } catch (t: Throwable) {
            throw Failure.Network(-1, t.message.orEmpty())
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = try { conn.responseMessage.orEmpty() } catch (_: Throwable) { "" }
                throw Failure.Network(code, msg)
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            return DownloadedText(bytes.toString(Charsets.UTF_8), bytes.size)
        } catch (f: Failure) {
            throw f
        } catch (t: Throwable) {
            throw Failure.Network(-1, t.message.orEmpty())
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun formatSize(bytes: Int): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${String.format(Locale.US, "%.0f", kb)}KB"
        return "${String.format(Locale.US, "%.1f", kb / 1024.0)}MB"
    }

    // ------------------------------------------------------------------
    // M3U 解析
    // ------------------------------------------------------------------
    private fun looksLikeM3u(text: String): Boolean {
        // 无 #EXTM3U 但仍以 #EXTINF 打头的宽松兼容
        val idx = text.indexOf("#EXTINF")
        return idx in 0..2048
    }

    private fun parseM3uText(text: String): List<LiveGroup> {
        val lines = text.split('\n')
        val groups = LinkedHashMap<String, MutableList<LiveChannel>>()
        val orderedDisplay = LinkedHashMap<String, String>()

        var pendingGroup: String? = null
        var pendingTitle: String? = null
        var pendingLogo: String? = null

        for (raw in lines) {
            val line = raw.trim().trimEnd('\r')
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTM3U")) continue
            if (line.startsWith("#EXTINF")) {
                pendingGroup = extractAttr(line, "group-title")
                pendingLogo = extractAttr(line, "tvg-logo")
                pendingTitle = line.substringAfterLast(',', "").trim().ifBlank { null }
                continue
            }
            if (line.startsWith("#")) continue
            // 到此为 URL 行
            val url = line
            if (url.isBlank()) continue
            val rawGroup = (pendingGroup ?: "").trim()
            val groupKey = if (rawGroup.isEmpty()) "" else rawGroup
            val displayName = if (rawGroup.isEmpty()) "📡 未分组" else "📡 $rawGroup"
            orderedDisplay.putIfAbsent(groupKey, displayName)
            val title = (pendingTitle ?: url.substringAfterLast('/'))
                .ifBlank { url }
            groups.getOrPut(groupKey) { mutableListOf() }.add(
                LiveChannel(title = title, url = url, logo = pendingLogo)
            )
            pendingGroup = null
            pendingTitle = null
            pendingLogo = null
        }

        return orderedDisplay.map { (key, display) ->
            LiveGroup(
                displayName = display,
                rawGroupTitle = key,
                channels = groups[key].orEmpty()
            )
        }.filter { it.channels.isNotEmpty() }
    }

    private fun extractAttr(line: String, key: String): String? {
        // 匹配 key="value"
        val prefix = "$key=\""
        val start = line.indexOf(prefix)
        if (start < 0) return null
        val end = line.indexOf('"', start + prefix.length)
        if (end <= start) return null
        return line.substring(start + prefix.length, end)
    }

    // ------------------------------------------------------------------
    // 直播源 JSON 解析
    // ------------------------------------------------------------------
    /** 递归提取 直播源 JSON 中所有 lives[].url 字段（M3U/M3U8 直链）。 */
    private fun extractTvBoxLiveUrls(text: String): List<String> {
        return try {
            val trimmed = text.trim()
            val root: Any = when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> return emptyList()
            }
            val out = mutableListOf<String>()
            walkForLives(root, out)
            out.distinct()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun walkForLives(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                // 命中 lives 字段：可能是 JSONArray 或对象
                val lives = node.opt("lives")
                when (lives) {
                    is JSONArray -> {
                        for (i in 0 until lives.length()) {
                            val e = lives.opt(i)
                            if (e is JSONObject) {
                                val url = e.optString("url", "").trim()
                                if (url.isNotEmpty()) out.add(url)
                            } else if (e is String && e.isNotBlank()) {
                                out.add(e.trim())
                            }
                        }
                    }
                    is JSONObject -> {
                        val url = lives.optString("url", "").trim()
                        if (url.isNotEmpty()) out.add(url)
                    }
                    else -> {}
                }
                // 继续深挖其他字段（有些 JSON 会把 lives 嵌套在 sites/... 里）
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "lives") continue
                    walkForLives(node.opt(k), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) walkForLives(node.opt(i), out)
            }
            else -> {}
        }
    }

    /**
     * 兜底：形如
     *   [ {"group":"央视","name":"CCTV1","url":"http://..."}, ... ]
     * 的扁平 JSON 直接抽取。
     */
    private fun parseFlatJsonChannels(text: String): List<LiveGroup> {
        return try {
            val root = JSONArray(text.trim())
            val groups = LinkedHashMap<String, MutableList<LiveChannel>>()
            val display = LinkedHashMap<String, String>()
            for (i in 0 until root.length()) {
                val o = root.optJSONObject(i) ?: continue
                val name = o.optString("name", "").ifBlank { o.optString("title", "") }
                val url = o.optString("url", "")
                if (name.isBlank() || url.isBlank()) continue
                val group = o.optString("group", "").ifBlank { o.optString("group-title", "") }
                val key = group
                val disp = if (group.isBlank()) "📡 未分组" else "📡 $group"
                display.putIfAbsent(key, disp)
                groups.getOrPut(key) { mutableListOf() }.add(
                    LiveChannel(title = name, url = url, logo = o.optString("logo", "").ifBlank { null })
                )
            }
            display.map { (k, d) ->
                LiveGroup(displayName = d, rawGroupTitle = k, channels = groups[k].orEmpty())
            }.filter { it.channels.isNotEmpty() }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
