package com.bd.casttv.douyin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 抖音投屏专属播放记录（区别于 PlaybackController 的通用历史）。
 *
 * 落库规则：仅当同一 uri 累计播放时长达到 [com.bd.casttv.settings.Settings.douyinHistoryThresholdSec]
 * 秒后才由 [PlaybackController] 调用 [add] 落库，避免误触/预加载/短暂切集造成的噪音。
 *
 * 存储：SharedPreferences 单键 JSON 数组，FIFO 200 条。
 */
object DouyinCastHistoryStore {

    private const val PREFS = "casttv_douyin_cast_history"
    private const val KEY_LIST = "list"
    private const val MAX_ITEMS = 200

    data class Item(
        val uri: String,
        val title: String,
        val sourceHint: String,
        val artworkUrl: String,
        val artworkPath: String,
        val firstPlayedAt: Long,
        val lastPositionMs: Long,
        val durationMs: Long,
        val playedSec: Long,
        val isDouyinCast: Boolean = true
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("uri", uri)
            .put("title", title)
            .put("sourceHint", sourceHint)
            .put("artworkUrl", artworkUrl)
            .put("artworkPath", artworkPath)
            .put("firstPlayedAt", firstPlayedAt)
            .put("lastPositionMs", lastPositionMs)
            .put("durationMs", durationMs)
            .put("playedSec", playedSec)
            .put("isDouyinCast", isDouyinCast)

        companion object {
            fun fromJson(o: JSONObject): Item = Item(
                uri = o.optString("uri"),
                title = o.optString("title"),
                sourceHint = o.optString("sourceHint"),
                artworkUrl = o.optString("artworkUrl"),
                artworkPath = o.optString("artworkPath"),
                firstPlayedAt = o.optLong("firstPlayedAt"),
                lastPositionMs = o.optLong("lastPositionMs"),
                durationMs = o.optLong("durationMs"),
                playedSec = o.optLong("playedSec"),
                isDouyinCast = if (o.has("isDouyinCast")) o.optBoolean("isDouyinCast", false) else isLikelyDouyin(o)
            )

            private fun isLikelyDouyin(o: JSONObject): Boolean {
                val text = listOf(
                    o.optString("uri"),
                    o.optString("sourceHint"),
                    o.optString("artworkUrl")
                ).joinToString("\n").lowercase()
                val markers = listOf("douyin", "抖音", "aweme", "amemv", "snssdk1128", "iesdouyin", "douyinvod", "douyinpic", "douyinstatic")
                return markers.any { it in text }
            }
        }
    }

    fun list(context: Context): List<Item> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Item.fromJson(o))
            }
            out.filter { it.isDouyinCast }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 新增/合并一条记录：同 uri 只保留一条并更新到最新，插到最前。 */
    fun add(context: Context, item: Item) {
        if (item.uri.isBlank()) return
        val current = list(context).toMutableList()
        current.removeAll { it.uri == item.uri }
        current.add(0, item)
        while (current.size > MAX_ITEMS) current.removeAt(current.size - 1)
        save(context, current)
    }

    fun removeByUri(context: Context, uri: String): Boolean {
        if (uri.isBlank()) return false
        val current = list(context).toMutableList()
        val removed = current.removeAll { it.uri == uri }
        if (removed) save(context, current)
        return removed
    }

    fun clearAll(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, arr.toString())
            .apply()
    }
}
