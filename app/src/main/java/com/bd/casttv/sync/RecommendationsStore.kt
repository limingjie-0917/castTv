package com.bd.casttv.sync

import android.content.Context
import com.bd.casttv.dlna.SsdpDiagnostics
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.settings.Settings
import com.bd.casttv.util.CreatorIdProvider
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 「收藏页推荐合集」云端数据读写：
 *
 * ```
 * recommendations.json (与 index.json 同级)
 * {
 *   "version": 1,
 *   "recommendations": [
 *     {
 *       "collectionId": "...",
 *       "collectionName": "...",
 *       "recommendedAt": "2026-08-10T21:30:00+08:00",
 *       "recommendedDate": "2026-08-10",
 *       "createdAt": "...",
 *       "updatedAt": "...",
 *       "recommenderList": [ {"creatorId":"...","name":"..."} ],
 *       "videos": [ {"videoId":"...","title":"...","url":"...","cover":"...","duration":0,"sourceCollectionName":"..."} ]
 *     }
 *   ]
 * }
 * ```
 *
 * - 写侧 GC：每次上传前剔除所有 `recommendedDate != today`（北京时区）的历史条目。
 * - 乐观并发：使用 Gitee 文件 sha 做 CAS，最多重试 3 次。
 * - 视频唯一键：优先 `videoId`，兜底 `url`。
 */
class RecommendationsStore(private val context: Context) {

    companion object {
        const val REMOTE_PATH = "recommendations.json"
        private const val TAG = "RecommendationsStore"
        private const val MAX_UPLOAD_RETRIES = 3
        private val BJ_TZ: TimeZone = TimeZone.getTimeZone("GMT+08:00")

        /** 北京时区当日 YYYY-MM-DD。 */
        fun todayBeijing(): String {
            val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            f.timeZone = BJ_TZ
            return f.format(Date())
        }

        /** ISO-8601 时间戳 (北京时区)：2026-08-10T21:30:00+08:00 */
        fun nowIsoBeijing(): String {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            f.timeZone = BJ_TZ
            return f.format(Date())
        }

        /** 从 (collectionId, videoId, url) 生成稳定的唯一键：优先 videoId，兜底 url。 */
        fun videoKey(videoId: String, url: String): String {
            return videoId.trim().ifBlank { url.trim() }
        }
    }

    // ------------------------------------------------------------------
    // 数据结构
    // ------------------------------------------------------------------

    data class Recommender(
        val creatorId: String,
        val name: String,
        /** 为空表示推荐给所有人；非空表示仅推荐给这些 creatorId。 */
        val targetUsers: List<String> = emptyList()
    )

    data class RecommendationVideo(
        val videoId: String,
        val title: String,
        val url: String,
        val cover: String,
        val duration: Long,
        val sourceCollectionName: String
    ) {
        fun uniqueKey(): String = videoKey(videoId, url)
    }

    data class Recommendation(
        val collectionId: String,
        val collectionName: String,
        val recommendedAt: String,
        val recommendedDate: String,
        val createdAt: String,
        val updatedAt: String,
        val recommenderList: List<Recommender>,
        val videos: List<RecommendationVideo>
    )

    data class RecommendationsFile(
        val version: Int,
        val recommendations: List<Recommendation>
    )

    /** 拉取云端 recommendations.json；文件不存在视为空。返回 null 表示网络/解析失败。 */
    fun fetch(): Pair<RecommendationsFile, String?>? {
        val result = GiteeApi.getFileResult(REMOTE_PATH)
        return when (result) {
            is GiteeApi.ApiResult.Success -> parse(result.value.content) to result.value.sha.takeIf { it.isNotBlank() }
            GiteeApi.ApiResult.NotFound -> RecommendationsFile(1, emptyList()) to null
            is GiteeApi.ApiResult.Error -> {
                SsdpDiagnostics.logCloudSync("$TAG fetch 失败：${result.message}")
                null
            }
        }
    }

    private fun parse(content: String): RecommendationsFile {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return RecommendationsFile(1, emptyList())
        return try {
            val obj = JSONObject(trimmed)
            val version = obj.optInt("version", 1)
            val arr = obj.optJSONArray("recommendations") ?: JSONArray()
            val list = mutableListOf<Recommendation>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val recArr = o.optJSONArray("recommenderList") ?: JSONArray()
                val recs = mutableListOf<Recommender>()
                for (j in 0 until recArr.length()) {
                    val r = recArr.optJSONObject(j) ?: continue
                    val id = r.optString("creatorId").ifBlank { r.optString("deviceId") }.trim()
                    if (id.isBlank()) continue
                    val targetArr = r.optJSONArray("targetUsers") ?: JSONArray()
                    val targetUsers = mutableListOf<String>()
                    for (k in 0 until targetArr.length()) {
                        val targetId = targetArr.optString(k).trim()
                        if (targetId.isNotBlank()) targetUsers.add(targetId)
                    }
                    recs.add(Recommender(id, r.optString("name"), targetUsers.distinct()))
                }
                val vArr = o.optJSONArray("videos") ?: JSONArray()
                val videos = mutableListOf<RecommendationVideo>()
                for (j in 0 until vArr.length()) {
                    val v = vArr.optJSONObject(j) ?: continue
                    videos.add(
                        RecommendationVideo(
                            videoId = v.optString("videoId"),
                            title = v.optString("title"),
                            url = v.optString("url"),
                            cover = v.optString("cover"),
                            duration = v.optLong("duration", 0L),
                            sourceCollectionName = v.optString("sourceCollectionName")
                        )
                    )
                }
                list.add(
                    Recommendation(
                        collectionId = o.optString("collectionId"),
                        collectionName = o.optString("collectionName"),
                        recommendedAt = o.optString("recommendedAt"),
                        recommendedDate = o.optString("recommendedDate"),
                        createdAt = o.optString("createdAt"),
                        updatedAt = o.optString("updatedAt"),
                        recommenderList = recs,
                        videos = videos
                    )
                )
            }
            RecommendationsFile(version, list)
        } catch (_: Throwable) {
            RecommendationsFile(1, emptyList())
        }
    }

    private fun serialize(file: RecommendationsFile): String {
        val root = JSONObject()
        root.put("version", file.version)
        val arr = JSONArray()
        for (r in file.recommendations) {
            val o = JSONObject()
            o.put("collectionId", r.collectionId)
            o.put("collectionName", r.collectionName)
            o.put("recommendedAt", r.recommendedAt)
            o.put("recommendedDate", r.recommendedDate)
            o.put("createdAt", r.createdAt)
            o.put("updatedAt", r.updatedAt)
            val recArr = JSONArray()
            for (rec in r.recommenderList) {
                recArr.put(JSONObject().apply {
                    put("creatorId", rec.creatorId)
                    put("name", rec.name)
                    put("targetUsers", JSONArray().apply {
                        rec.targetUsers.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { put(it) }
                    })
                })
            }
            o.put("recommenderList", recArr)
            val vArr = JSONArray()
            for (v in r.videos) {
                vArr.put(JSONObject().apply {
                    if (v.videoId.isNotBlank()) put("videoId", v.videoId)
                    put("title", v.title)
                    put("url", v.url)
                    if (v.cover.isNotBlank()) put("cover", v.cover)
                    if (v.duration > 0) put("duration", v.duration)
                    if (v.sourceCollectionName.isNotBlank()) put("sourceCollectionName", v.sourceCollectionName)
                })
            }
            o.put("videos", vArr)
            arr.put(o)
        }
        root.put("recommendations", arr)
        return root.toString()
    }

    // ------------------------------------------------------------------
    // 上传（发起推荐）
    // ------------------------------------------------------------------

    /**
     * 将本地合集内选中的视频推荐到云端。
     *
     * 流程：
     *  1. 拉取云端；
     *  2. 剔除所有 `recommendedDate != today` 的历史条目（写侧 GC）；
     *  3. 按 collectionId 合并/追加当前推荐；
     *  4. 使用 sha 做 CAS 写回；409/写入失败最多重试 3 次。
     *
     * @return true 表示成功；false 表示 3 次重试后仍失败。
     */
    fun recommend(
        collection: FavoritesStore.FavoriteCollection,
        videos: List<FavoritesStore.FavoriteItem>,
        targetUsers: List<String> = emptyList()
    ): Boolean {
        if (videos.isEmpty() || collection.id.isBlank()) return false
        val creatorId = RecommenderIdentity.creatorId(context)
        val deviceName = RecommenderIdentity.deviceName(context)
        val safeTargetUsers = targetUsers.map { it.trim() }.filter { it.isNotBlank() && it != creatorId }.distinct().take(5)
        val today = todayBeijing()
        val now = nowIsoBeijing()

        var attempt = 0
        while (attempt < MAX_UPLOAD_RETRIES) {
            attempt++
            val pair = fetch() ?: run {
                SsdpDiagnostics.logCloudSync("$TAG recommend attempt=$attempt fetch=null，中止")
                return false
            }
            val (remote, sha) = pair

            // 写侧 GC：剔除非今日推荐。
            val kept = remote.recommendations.filter { it.recommendedDate == today }

            val existingIndex = kept.indexOfFirst { it.collectionId == collection.id }
            val newRec = if (existingIndex >= 0) {
                val old = kept[existingIndex]
                val videoMap = linkedMapOf<String, RecommendationVideo>()
                old.videos.forEach { videoMap[it.uniqueKey()] = it }
                val incoming = videos.map { it.toRecommendationVideo(collection.name) }
                incoming.forEach { videoMap[it.uniqueKey()] = it }
                val recMap = linkedMapOf<String, Recommender>()
                old.recommenderList.forEach { recMap[it.creatorId] = it }
                recMap[creatorId] = Recommender(creatorId, deviceName, safeTargetUsers)
                old.copy(
                    collectionName = collection.name,
                    recommendedAt = now,
                    recommendedDate = today,
                    updatedAt = now,
                    recommenderList = recMap.values.toList(),
                    videos = videoMap.values.toList()
                )
            } else {
                Recommendation(
                    collectionId = collection.id,
                    collectionName = collection.name,
                    recommendedAt = now,
                    recommendedDate = today,
                    createdAt = now,
                    updatedAt = now,
                    recommenderList = listOf(Recommender(creatorId, deviceName, safeTargetUsers)),
                    videos = videos.map { it.toRecommendationVideo(collection.name) }
                )
            }

            val newList = kept.toMutableList()
            if (existingIndex >= 0) {
                newList[existingIndex] = newRec
            } else {
                newList.add(newRec)
            }
            val newFile = RecommendationsFile(version = remote.version.coerceAtLeast(1), recommendations = newList)
            val json = serialize(newFile)
            val message = "recommend: ${collection.name}, ${videos.size} videos, $now"
            val put = GiteeApi.putFileResult(REMOTE_PATH, json, sha, commitMessage = message)
            when (put) {
                is GiteeApi.ApiResult.Success -> {
                    SsdpDiagnostics.logCloudSync("$TAG recommend 成功：collection=${collection.name}, videos=${videos.size}, attempt=$attempt")
                    return true
                }
                is GiteeApi.ApiResult.Error -> {
                    // 409/其他错误一律走重试逻辑（重新 fetch 拿最新 sha）。
                    SsdpDiagnostics.logCloudSync("$TAG recommend 重试 attempt=$attempt，错误：${put.message.take(160)}")
                    if (attempt >= MAX_UPLOAD_RETRIES) return false
                }
                GiteeApi.ApiResult.NotFound -> {
                    SsdpDiagnostics.logCloudSync("$TAG recommend NotFound，attempt=$attempt")
                    if (attempt >= MAX_UPLOAD_RETRIES) return false
                }
            }
        }
        return false
    }

    private fun FavoritesStore.FavoriteItem.toRecommendationVideo(sourceCollectionName: String): RecommendationVideo {
        // itemId 兜底：老数据可能为空，则用本地 id。
        val vid = itemId.ifBlank { id.ifBlank { UUID.randomUUID().toString() } }
        // 本地 thumbPath / artworkPath 是绝对路径，跨设备无意义，只有形如 http 开头才作为封面地址上传。
        val remoteCover = when {
            artworkPath?.startsWith("http", ignoreCase = true) == true -> artworkPath.orEmpty()
            thumbPath?.startsWith("http", ignoreCase = true) == true -> thumbPath.orEmpty()
            else -> ""
        }
        return RecommendationVideo(
            videoId = vid,
            title = title,
            url = uri,
            cover = remoteCover,
            duration = durationMs / 1000L,
            sourceCollectionName = sourceCollectionName
        )
    }

    fun recommendVideos(
        collectionId: String,
        collectionName: String,
        videos: List<RecommendationVideo>,
        targetUsers: List<String> = emptyList()
    ): Boolean {
        if (videos.isEmpty() || collectionId.isBlank()) return false
        val creatorId = RecommenderIdentity.creatorId(context)
        val deviceName = RecommenderIdentity.deviceName(context)
        val safeTargetUsers = targetUsers.map { it.trim() }.filter { it.isNotBlank() && it != creatorId }.distinct().take(5)
        val today = todayBeijing()
        val now = nowIsoBeijing()
        var attempt = 0
        while (attempt < MAX_UPLOAD_RETRIES) {
            attempt++
            val pair = fetch() ?: return false
            val (remote, sha) = pair
            val kept = remote.recommendations.filter { it.recommendedDate == today }
            val existingIndex = kept.indexOfFirst { it.collectionId == collectionId }
            val newRec = if (existingIndex >= 0) {
                val old = kept[existingIndex]
                val videoMap = linkedMapOf<String, RecommendationVideo>()
                old.videos.forEach { videoMap[it.uniqueKey()] = it }
                videos.forEach { videoMap[it.uniqueKey()] = it }
                val recMap = linkedMapOf<String, Recommender>()
                old.recommenderList.forEach { recMap[it.creatorId] = it }
                recMap[creatorId] = Recommender(creatorId, deviceName, safeTargetUsers)
                old.copy(collectionName = collectionName, recommendedAt = now, recommendedDate = today, updatedAt = now, recommenderList = recMap.values.toList(), videos = videoMap.values.toList())
            } else {
                Recommendation(collectionId, collectionName, now, today, now, now, listOf(Recommender(creatorId, deviceName, safeTargetUsers)), videos)
            }
            val newList = kept.toMutableList()
            if (existingIndex >= 0) newList[existingIndex] = newRec else newList.add(newRec)
            val put = GiteeApi.putFileResult(REMOTE_PATH, serialize(RecommendationsFile(remote.version.coerceAtLeast(1), newList)), sha, commitMessage = "recommend: $collectionName, ${videos.size} videos, $now")
            when (put) {
                is GiteeApi.ApiResult.Success -> return true
                is GiteeApi.ApiResult.Error -> if (attempt >= MAX_UPLOAD_RETRIES) return false
                GiteeApi.ApiResult.NotFound -> if (attempt >= MAX_UPLOAD_RETRIES) return false
            }
        }
        return false
    }

    /** 拉取今天当前用户在指定合集已推荐的视频唯一键。 */
    fun fetchMyRecommendedVideoKeysForToday(collectionId: String): Set<String>? {
        if (collectionId.isBlank()) return emptySet()
        val remote = fetch()?.first ?: return null
        val today = todayBeijing()
        val myCreatorId = RecommenderIdentity.creatorId(context)
        return remote.recommendations
            .filter { rec ->
                rec.collectionId == collectionId &&
                    rec.recommendedDate == today &&
                    rec.recommenderList.any { it.creatorId == myCreatorId }
            }
            .flatMap { it.videos }
            .map { it.uniqueKey() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** 从今天当前用户在指定合集的推荐数据中删除对应视频。 */
    fun cancelMyRecommendations(collectionId: String, videoKeys: Set<String>): Boolean {
        if (collectionId.isBlank() || videoKeys.isEmpty()) return false
        val today = todayBeijing()
        val myCreatorId = RecommenderIdentity.creatorId(context)
        val now = nowIsoBeijing()

        var attempt = 0
        while (attempt < MAX_UPLOAD_RETRIES) {
            attempt++
            val pair = fetch() ?: run {
                SsdpDiagnostics.logCloudSync("$TAG cancel recommend attempt=$attempt fetch=null，中止")
                return false
            }
            val (remote, sha) = pair
            var changed = false
            val newList = remote.recommendations.mapNotNull { rec ->
                if (rec.collectionId == collectionId && rec.recommendedDate == today && rec.recommenderList.any { it.creatorId == myCreatorId }) {
                    val keptVideos = rec.videos.filter { it.uniqueKey() !in videoKeys }
                    if (keptVideos.size != rec.videos.size) changed = true
                    if (keptVideos.isEmpty()) {
                        null
                    } else {
                        rec.copy(updatedAt = now, videos = keptVideos)
                    }
                } else {
                    rec
                }
            }
            if (!changed) return true
            val newFile = RecommendationsFile(version = remote.version.coerceAtLeast(1), recommendations = newList)
            val json = serialize(newFile)
            val put = GiteeApi.putFileResult(REMOTE_PATH, json, sha, commitMessage = "cancel recommend: $collectionId, ${videoKeys.size} videos, $now")
            when (put) {
                is GiteeApi.ApiResult.Success -> {
                    SsdpDiagnostics.logCloudSync("$TAG cancel recommend 成功：collection=$collectionId, videos=${videoKeys.size}, attempt=$attempt")
                    return true
                }
                is GiteeApi.ApiResult.Error -> {
                    SsdpDiagnostics.logCloudSync("$TAG cancel recommend 重试 attempt=$attempt，错误：${put.message.take(160)}")
                    if (attempt >= MAX_UPLOAD_RETRIES) return false
                }
                GiteeApi.ApiResult.NotFound -> {
                    SsdpDiagnostics.logCloudSync("$TAG cancel recommend NotFound，attempt=$attempt")
                    if (attempt >= MAX_UPLOAD_RETRIES) return false
                }
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // 接收端拉取
    // ------------------------------------------------------------------

    /**
     * 启动时拉取云端推荐并做读侧过滤：
     *  - 过滤本机发起的推荐（`recommenderList` 包含当前 creatorId）；
     *  - 按 recommendedDate == today (北京时区) 过滤；不写云端。
     *
     * @return 过滤后的推荐列表；网络失败或解析失败返回 null。
     */
    fun fetchIncomingForToday(): List<Recommendation>? {
        val remote = fetch()?.first ?: return null
        val today = todayBeijing()
        val myCreatorId = RecommenderIdentity.creatorId(context)
        return remote.recommendations.mapNotNull { rec ->
            if (rec.recommendedDate != today || rec.videos.isEmpty()) return@mapNotNull null
            val visibleRecommenders = rec.recommenderList.filter { recommender ->
                recommender.creatorId != myCreatorId &&
                    (recommender.targetUsers.isEmpty() || myCreatorId in recommender.targetUsers)
            }
            if (visibleRecommenders.isEmpty()) null else rec.copy(recommenderList = visibleRecommenders)
        }
    }
}

/**
 * 稳定的本机推荐者身份：
 *  - `creatorId`：复用合集创建者标识，优先基于 ANDROID_ID，极端场景回退本地 UUID；
 *  - `deviceName`：复用现有 [Settings.deviceName]（用户在设置页可自定义）。
 */
object RecommenderIdentity {
    fun creatorId(context: Context): String = CreatorIdProvider.get(context.applicationContext)

    fun deviceName(context: Context): String {
        val name = try { Settings(context).deviceName } catch (_: Throwable) { "" }
        return name.ifBlank { Settings.DEFAULT_DEVICE_NAME }
    }
}
