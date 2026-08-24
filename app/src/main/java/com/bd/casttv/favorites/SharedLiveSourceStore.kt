package com.bd.casttv.favorites

import android.content.Context
import com.bd.casttv.sync.GiteeApi
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 「☁️ 云端共享」直播源数据源（已联通 Gitee 云端存储）。
 *
 * 数据结构与合集数据完全隔离：
 *  - 合集数据：仓库根目录 `index.json` + `collections/` 目录下的 JSON（不受本类影响）；
 *  - 共享直播源：独立文件 `shared_sources/index.json`。
 *
 * 云端 `shared_sources/index.json` 为一个 JSON 数组，每个元素形如：
 * ```json
 * {
 *   "id": "uuid",
 *   "name": "北京移动IPTV",
 *   "url": "https://...",
 *   "groupCount": 1,
 *   "channelCount": 168,
 *   "uploadedAt": "2026-07-31T21:30:00+08:00",
 *   "likeCount": 0,
 *   "tags": ["推荐", "高清"]
 * }
 * ```
 *
 * 兼容性：文件不存在按空列表处理；字段缺失用默认值兜底；解析单条异常时跳过该条，整体不崩溃。
 *
 * 网络操作（[fetchSources] / [shareSource] / [updateLikeCount] / [updateSource] / [deleteSource]）均为**阻塞式**，
 * 必须在后台线程调用；本设备「是否已点赞」仍用 SharedPreferences 本地持久化。
 */
object SharedLiveSourceStore {

    /** 云端共享直播源独立存放路径，与合集数据隔离。 */
    private const val REMOTE_PATH = "shared_sources/index.json"

    private const val PREFS = "casttv_shared_live"
    private const val KEY_LIKED = "liked_ids"

    /** 一条社区共享直播源。 */
    data class SharedSource(
        val id: String,
        val name: String,
        val url: String,
        val groupCount: Int,
        val channelCount: Int,
        /** ISO-8601 上传时间，如 2026-07-31T21:30:00+08:00。 */
        val uploadedAt: String,
        /** 云端点赞总数（权威值）。 */
        var likeCount: Int,
        /** 展示在名称右侧的标签。 */
        val tags: List<String> = emptyList()
    )

    /** 云端列表加载结果。 */
    sealed class LoadResult {
        data class Success(val sources: List<SharedSource>) : LoadResult()
        data class Error(val message: String) : LoadResult()
    }

    /** 分享到云端结果。 */
    sealed class ShareResult {
        object Success : ShareResult()
        /** 相同 url 已存在，按去重处理（视为已分享）。 */
        object AlreadyShared : ShareResult()
        data class Error(val message: String) : ShareResult()
    }

    /** 点赞同步结果。 */
    sealed class LikeResult {
        data class Success(val newCount: Int) : LikeResult()
        data class Error(val message: String) : LikeResult()
    }

    /** 编辑结果。 */
    sealed class UpdateResult {
        object Success : UpdateResult()
        data class Error(val message: String) : UpdateResult()
    }

    /** 删除结果。 */
    sealed class DeleteResult {
        object Success : DeleteResult()
        data class Error(val message: String) : DeleteResult()
    }

    // ------------------------------------------------------------------
    // 本地「是否已点赞」记录（SharedPreferences）
    // ------------------------------------------------------------------

    /** 本设备已点赞的条目 id 集合。 */
    fun likedIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_LIKED, emptySet())
            ?.toSet()
            ?: emptySet()

    /** 本设备是否已点赞某条目。 */
    fun isLiked(context: Context, id: String): Boolean = likedIds(context).contains(id)

    /** 持久化本设备对某条目的点赞状态。 */
    fun setLiked(context: Context, id: String, liked: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_LIKED, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (liked) current.add(id) else current.remove(id)
        prefs.edit().putStringSet(KEY_LIKED, current).apply()
    }

    // ------------------------------------------------------------------
    // 云端读写（阻塞式，需在后台线程调用）
    // ------------------------------------------------------------------

    /** 从 Gitee 拉取共享直播源列表；文件不存在时返回空列表。 */
    fun fetchSources(): LoadResult = when (val r = GiteeApi.getFileResult(REMOTE_PATH)) {
        is GiteeApi.ApiResult.Success -> LoadResult.Success(parseArray(r.value.content))
        GiteeApi.ApiResult.NotFound -> LoadResult.Success(emptyList())
        is GiteeApi.ApiResult.Error -> LoadResult.Error(r.message)
    }

    /**
     * 分享一条直播源到云端：读取 -> 按 url 去重追加 -> 写回。
     * @param name 备注名（为空时使用链接域名兜底）
     */
    fun shareSource(
        name: String,
        url: String,
        groupCount: Int,
        channelCount: Int
    ): ShareResult {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return ShareResult.Error("直播源地址为空")

        val fileResult = GiteeApi.getFileResult(REMOTE_PATH)
        val list: MutableList<SharedSource>
        val sha: String?
        when (fileResult) {
            is GiteeApi.ApiResult.Success -> {
                list = parseArray(fileResult.value.content).toMutableList()
                sha = fileResult.value.sha
            }
            GiteeApi.ApiResult.NotFound -> {
                list = mutableListOf()
                sha = null
            }
            is GiteeApi.ApiResult.Error -> return ShareResult.Error(fileResult.message)
        }

        // 按 url 去重：已存在则不重复追加
        if (list.any { it.url.trim() == trimmedUrl }) return ShareResult.AlreadyShared

        val finalName = name.trim().ifBlank { extractDomain(trimmedUrl) }
        list.add(
            SharedSource(
                id = UUID.randomUUID().toString(),
                name = finalName,
                url = trimmedUrl,
                groupCount = groupCount,
                channelCount = channelCount,
                uploadedAt = nowIso8601(),
                likeCount = 0,
                tags = emptyList()
            )
        )

        return when (val put = GiteeApi.putFileResult(REMOTE_PATH, toJsonArray(list).toString(2), sha)) {
            is GiteeApi.ApiResult.Success -> ShareResult.Success
            is GiteeApi.ApiResult.Error -> ShareResult.Error(put.message)
            GiteeApi.ApiResult.NotFound -> ShareResult.Error("写入 $REMOTE_PATH 失败")
        }
    }

    /**
     * 点赞/取消点赞：更新对应条目的 likeCount 并写回 Gitee。
     * @param delta +1 表示点赞，-1 表示取消
     * @return 成功时返回该条目的新点赞数
     */
    fun updateLikeCount(id: String, delta: Int): LikeResult {
        val fileResult = GiteeApi.getFileResult(REMOTE_PATH)
        val list: MutableList<SharedSource>
        val sha: String?
        when (fileResult) {
            is GiteeApi.ApiResult.Success -> {
                list = parseArray(fileResult.value.content).toMutableList()
                sha = fileResult.value.sha
            }
            GiteeApi.ApiResult.NotFound -> return LikeResult.Error("云端暂无共享直播源数据")
            is GiteeApi.ApiResult.Error -> return LikeResult.Error(fileResult.message)
        }

        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return LikeResult.Error("未找到该直播源，请刷新后重试")

        val newCount = (list[idx].likeCount + delta).coerceAtLeast(0)
        list[idx] = list[idx].copy(likeCount = newCount)

        return when (val put = GiteeApi.putFileResult(REMOTE_PATH, toJsonArray(list).toString(2), sha)) {
            is GiteeApi.ApiResult.Success -> LikeResult.Success(newCount)
            is GiteeApi.ApiResult.Error -> LikeResult.Error(put.message)
            GiteeApi.ApiResult.NotFound -> LikeResult.Error("写入 $REMOTE_PATH 失败")
        }
    }

    /** 编辑名称 / 地址 / 标签。 */
    fun updateSource(
        id: String,
        name: String,
        url: String,
        tags: List<String>
    ): UpdateResult {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return UpdateResult.Error("直播源地址不能为空")
        val normalizedTags = normalizeTags(tags)
        val fileResult = GiteeApi.getFileResult(REMOTE_PATH)
        val list: MutableList<SharedSource>
        val sha: String?
        when (fileResult) {
            is GiteeApi.ApiResult.Success -> {
                list = parseArray(fileResult.value.content).toMutableList()
                sha = fileResult.value.sha
            }
            GiteeApi.ApiResult.NotFound -> return UpdateResult.Error("云端暂无共享直播源数据")
            is GiteeApi.ApiResult.Error -> return UpdateResult.Error(fileResult.message)
        }
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return UpdateResult.Error("未找到该直播源，请刷新后重试")
        if (list.anyIndexed { index, item -> index != idx && item.url.trim() == trimmedUrl }) {
            return UpdateResult.Error("已存在相同地址的直播源")
        }
        val old = list[idx]
        list[idx] = old.copy(
            name = trimmedName.ifBlank { extractDomain(trimmedUrl) },
            url = trimmedUrl,
            tags = normalizedTags
        )
        return when (val put = GiteeApi.putFileResult(REMOTE_PATH, toJsonArray(list).toString(2), sha)) {
            is GiteeApi.ApiResult.Success -> UpdateResult.Success
            is GiteeApi.ApiResult.Error -> UpdateResult.Error(put.message)
            GiteeApi.ApiResult.NotFound -> UpdateResult.Error("写入 $REMOTE_PATH 失败")
        }
    }

    /** 删除单条直播源。 */
    fun deleteSource(id: String): DeleteResult {
        val fileResult = GiteeApi.getFileResult(REMOTE_PATH)
        val list: MutableList<SharedSource>
        val sha: String?
        when (fileResult) {
            is GiteeApi.ApiResult.Success -> {
                list = parseArray(fileResult.value.content).toMutableList()
                sha = fileResult.value.sha
            }
            GiteeApi.ApiResult.NotFound -> return DeleteResult.Error("云端暂无共享直播源数据")
            is GiteeApi.ApiResult.Error -> return DeleteResult.Error(fileResult.message)
        }
        val removed = list.removeAll { it.id == id }
        if (!removed) return DeleteResult.Error("未找到该直播源，请刷新后重试")
        return when (val put = GiteeApi.putFileResult(REMOTE_PATH, toJsonArray(list).toString(2), sha)) {
            is GiteeApi.ApiResult.Success -> DeleteResult.Success
            is GiteeApi.ApiResult.Error -> DeleteResult.Error(put.message)
            GiteeApi.ApiResult.NotFound -> DeleteResult.Error("写入 $REMOTE_PATH 失败")
        }
    }

    // ------------------------------------------------------------------
    // 展示辅助
    // ------------------------------------------------------------------

    /** 将 ISO-8601 上传时间格式化为「yyyy-MM-dd HH:mm」（北京时间）；异常时原样返回。 */
    fun displayTime(uploadedAt: String): String {
        if (uploadedAt.isBlank()) return "未知时间"
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            val date = parser.parse(uploadedAt) ?: return uploadedAt
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }
                .format(date)
        } catch (_: Exception) {
            uploadedAt
        }
    }

    // ------------------------------------------------------------------
    // JSON 解析 / 序列化（带兜底，保证新旧格式均可解析）
    // ------------------------------------------------------------------

    private fun parseArray(content: String): List<SharedSource> {
        if (content.isBlank()) return emptyList()
        val arr = try {
            JSONArray(content)
        } catch (_: Exception) {
            return emptyList()
        }
        val result = ArrayList<SharedSource>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url", "").trim()
            if (url.isEmpty()) continue
            val id = obj.optString("id", "").ifBlank { url }
            result.add(
                SharedSource(
                    id = id,
                    name = obj.optString("name", "").ifBlank { extractDomain(url) },
                    url = url,
                    groupCount = obj.optInt("groupCount", 0),
                    channelCount = obj.optInt("channelCount", 0),
                    uploadedAt = obj.optString("uploadedAt", ""),
                    likeCount = obj.optInt("likeCount", 0).coerceAtLeast(0),
                    tags = normalizeTags(obj.optJSONArray("tags"), obj.optString("tag", ""))
                )
            )
        }
        return result
    }

    private fun toJsonArray(list: List<SharedSource>): JSONArray {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("url", s.url)
                    put("groupCount", s.groupCount)
                    put("channelCount", s.channelCount)
                    put("uploadedAt", s.uploadedAt)
                    put("likeCount", s.likeCount)
                    put("tags", JSONArray(normalizeTags(s.tags)))
                }
            )
        }
        return arr
    }

    private fun nowIso8601(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT+08:00") }
            .format(Date())

    private fun extractDomain(url: String): String = try {
        java.net.URL(url.trim()).host.ifBlank { url.trim() }
    } catch (_: Exception) {
        url.trim()
    }

    private fun normalizeTags(vararg source: String): List<String> =
        normalizeTags(source.asList())

    private fun normalizeTags(array: JSONArray?, fallback: String): List<String> {
        val tags = mutableListOf<String>()
        if (array != null) {
            for (i in 0 until array.length()) {
                tags += array.optString(i)
            }
        }
        if (fallback.isNotBlank()) tags += fallback
        return normalizeTags(tags)
    }

    private fun normalizeTags(tags: List<String>): List<String> =
        tags.asSequence()
            .flatMap { raw ->
                raw.split('、', '，', ',', '|', '/', '\n', '\t')
                    .asSequence()
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)
            .toList()

    private inline fun <T> List<T>.anyIndexed(predicate: (index: Int, item: T) -> Boolean): Boolean {
        for (index in indices) {
            if (predicate(index, this[index])) return true
        }
        return false
    }
}
