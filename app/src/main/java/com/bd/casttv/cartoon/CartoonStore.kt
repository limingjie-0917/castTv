package com.bd.casttv.cartoon

import android.content.Context
import com.bd.casttv.sync.GiteeShareStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 动画城本地缓存：镜像云端 [GiteeShareStore.SharedCartoon] 列表到 SharedPreferences，
 * 供 CartoonCityPage 离线展示和快速查找卡片元信息。
 */
class CartoonStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 用云端拉取的列表覆盖本地缓存。 */
    fun cacheCartoons(cartoons: List<GiteeShareStore.SharedCartoon>) {
        val array = JSONArray()
        cartoons.forEach { c ->
            array.put(encodeCartoon(c))
        }
        prefs.edit().putString(KEY_CARTOONS, array.toString()).apply()
    }

    fun getCachedCartoons(): List<GiteeShareStore.SharedCartoon> {
        val raw = prefs.getString(KEY_CARTOONS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(decodeCartoon(obj))
                }
            }
        }.getOrElse { emptyList() }
    }

    fun getCachedCartoon(cartoonId: String): GiteeShareStore.SharedCartoon? {
        return getCachedCartoons().firstOrNull { it.cartoonId == cartoonId }
    }

    /** 写入或更新本地缓存里的单条记录（P4 添加成功后立刻补一条，避免下次进入还需等云端返回）。 */
    fun upsertLocal(cartoon: GiteeShareStore.SharedCartoon) {
        val list = getCachedCartoons().toMutableList()
        val idx = list.indexOfFirst { it.cartoonId == cartoon.cartoonId }
        if (idx >= 0) list[idx] = cartoon else list.add(cartoon)
        cacheCartoons(list)
    }

    /** 从本地缓存移除指定 cartoonId（P5 删除成功后立刻移除）。 */
    fun removeLocal(cartoonIds: Collection<String>) {
        if (cartoonIds.isEmpty()) return
        val list = getCachedCartoons().filterNot { it.cartoonId in cartoonIds }
        cacheCartoons(list)
    }

    fun clearCache() { prefs.edit().remove(KEY_CARTOONS).apply() }

    private fun encodeCartoon(c: GiteeShareStore.SharedCartoon): JSONObject {
        return JSONObject().apply {
            put("cartoonId", c.cartoonId)
            put("title", c.title)
            put("detailUrl", c.detailUrl)
            put("cover", c.cover)
            put("globalAdapterId", c.globalAdapterId ?: JSONObject.NULL)
            put("adapterName", c.adapterName)
            put("episodeCount", c.episodeCount)
            put("creatorId", c.creatorId)
            put("deviceName", c.deviceName)
            put("uploadedAt", c.uploadedAt)
            if (c.description.isNotEmpty()) put("description", c.description)
        }
    }

    private fun decodeCartoon(obj: JSONObject): GiteeShareStore.SharedCartoon = decodeSharedCartoon(obj)

    companion object {
        private const val PREFS = "cartoon_store"
        private const val KEY_CARTOONS = "cartoons"

        /**
         * 共享解析：把云端 / 本地 JSON 对象译成 SharedCartoon。
         * 容错点（ponytail: 历史遗留脏数据兜底）：
         *  1. "globalAdapterId" 可能是字符串 "null" / "" / JSON null / 缺失 → 统一 null
         *  2. uploadedAt 键名可能误写为 uploadeat / uploaded_at / UploadedAt → 多键 fallback
         */
        @JvmStatic
        fun decodeSharedCartoon(obj: JSONObject): GiteeShareStore.SharedCartoon {
            val globalAdapterIdRaw = obj.opt("globalAdapterId")
            val adapterId = when {
                globalAdapterIdRaw == null || globalAdapterIdRaw == JSONObject.NULL -> null
                globalAdapterIdRaw is String -> {
                    val s = globalAdapterIdRaw.trim()
                    if (s.isBlank() || s.equals("null", ignoreCase = true)) null else s
                }
                else -> globalAdapterIdRaw.toString().trim().takeIf { it.isNotBlank() }
            }
            val uploadedAt = sequenceOf("uploadedAt", "uploadeat", "uploaded_at", "UploadedAt")
                .map { obj.optLong(it, -1L) }
                .firstOrNull { it > 0L } ?: 0L
            return GiteeShareStore.SharedCartoon(
                cartoonId = obj.optString("cartoonId"),
                title = obj.optString("title"),
                detailUrl = obj.optString("detailUrl"),
                cover = obj.optString("cover"),
                globalAdapterId = adapterId,
                adapterName = obj.optString("adapterName"),
                episodeCount = obj.optInt("episodeCount", 0),
                creatorId = obj.optString("creatorId"),
                deviceName = obj.optString("deviceName"),
                uploadedAt = uploadedAt,
                // 老数据可能缺失该字段（云端在 2026-08 前的写入没有简介），按空串兜底
                description = obj.optString("description").orEmpty()
            )
        }
    }
}
