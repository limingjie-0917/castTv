package com.bd.casttv.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「收藏页推荐合集」接收端本地已见推荐记录（SharedPreferences 持久化）。
 *
 * 存储形态（JSON）：
 * ```
 * {
 *   "date": "YYYY-MM-DD (BJT)",
 *   "items": [ {"collectionId": "col_A", "videoId": "v1"}, ... ]
 * }
 * ```
 *
 * 规则：
 *  - key: `last_seen_today_recommendations`；
 *  - date 与 [RecommendationsStore.todayBeijing] 不一致时视为空（跨天自动过期）；
 *  - 「云端推荐」弹窗 onShow 时立刻用当天全量新增视频覆盖写入，保证同一次启动内不会重复触发。
 */
class SeenRecommendationsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class SeenItem(val collectionId: String, val videoId: String)

    /** 当前保存的已见集合（若 date != today 则视为空，且顺带清理旧数据）。 */
    fun currentForToday(): Set<Pair<String, String>> {
        val raw = prefs.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return emptySet()
        return try {
            val obj = JSONObject(raw)
            val date = obj.optString("date")
            if (date != RecommendationsStore.todayBeijing()) {
                // 跨天自动过期。清理旧数据后返回空集合。
                prefs.edit().remove(KEY).apply()
                return emptySet()
            }
            val arr = obj.optJSONArray("items") ?: JSONArray()
            val result = mutableSetOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val c = o.optString("collectionId")
                val v = o.optString("videoId")
                if (c.isNotBlank() && v.isNotBlank()) result.add(c to v)
            }
            result
        } catch (_: Throwable) {
            prefs.edit().remove(KEY).apply()
            emptySet()
        }
    }

    /** 覆盖写入为「今天 + 传入集合」；用于弹窗 onShow 时把新增视频作为新的基线全量覆盖。 */
    fun overwriteToToday(items: Collection<SeenItem>) {
        val obj = JSONObject()
        obj.put("date", RecommendationsStore.todayBeijing())
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().apply {
                put("collectionId", it.collectionId)
                put("videoId", it.videoId)
            })
        }
        obj.put("items", arr)
        prefs.edit().putString(KEY, obj.toString()).apply()
    }

    companion object {
        private const val PREFS = "casttv_recommender"
        private const val KEY = "last_seen_today_recommendations"
    }
}
