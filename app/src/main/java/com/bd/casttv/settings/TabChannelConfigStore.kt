package com.bd.casttv.settings

import android.content.Context
import com.bd.casttv.favorites.ChannelOverride
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义 Tab 的频道配置存储（仅保存用户覆盖项，不保存频道本身）。
 *
 * 频道每次都由「绑定合集资源」实时聚合得到，这里只持久化用户的：
 * - 主源指定（primary）
 * - 异常打标（abnormal）
 *
 * JSON 结构（SharedPreferences `casttv_settings` → key `custom_tab_channel_config`）：
 * ```
 * {
 *   "<tabId>": {
 *     "<channelKey>": { "primary": "<sourceId>", "abnormal": ["<sourceId>", ...] }
 *   }
 * }
 * ```
 */
class TabChannelConfigStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun overrides(tabId: String): Map<String, ChannelOverride> {
        if (tabId.isBlank()) return emptyMap()
        val tabObj = readRoot().optJSONObject(tabId) ?: return emptyMap()
        val out = HashMap<String, ChannelOverride>()
        val keys = tabObj.keys()
        while (keys.hasNext()) {
            val channelKey = keys.next()
            val obj = tabObj.optJSONObject(channelKey) ?: continue
            val primary = obj.optString("primary").ifBlank { null }
            val abnormal = HashSet<String>()
            obj.optJSONArray("abnormal")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isNotBlank()) abnormal.add(id)
                }
            }
            if (primary != null || abnormal.isNotEmpty()) {
                out[channelKey] = ChannelOverride(primary, abnormal)
            }
        }
        return out
    }

    fun setPrimary(tabId: String, channelKey: String, sourceId: String) {
        edit(tabId, channelKey) { it.put("primary", sourceId) }
    }

    fun setAbnormal(tabId: String, channelKey: String, sourceId: String, abnormal: Boolean) {
        edit(tabId, channelKey) { obj ->
            val arr = obj.optJSONArray("abnormal") ?: JSONArray()
            val kept = JSONArray()
            var existed = false
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id == sourceId) { existed = true; if (abnormal) kept.put(id) } else if (id.isNotBlank()) kept.put(id)
            }
            if (abnormal && !existed) kept.put(sourceId)
            obj.put("abnormal", kept)
            // 若一个源被恢复，且它恰好是主源指定，无需清理：主源仍可继续指向它。
        }
    }

    fun removeTab(tabId: String) {
        val root = readRoot()
        if (!root.has(tabId)) return
        root.remove(tabId)
        save(root)
    }

    private fun edit(tabId: String, channelKey: String, block: (JSONObject) -> Unit) {
        if (tabId.isBlank() || channelKey.isBlank()) return
        val root = readRoot()
        val tabObj = root.optJSONObject(tabId) ?: JSONObject().also { root.put(tabId, it) }
        val channelObj = tabObj.optJSONObject(channelKey) ?: JSONObject().also { tabObj.put(channelKey, it) }
        block(channelObj)
        save(root)
    }

    private fun readRoot(): JSONObject {
        val raw = prefs.getString(KEY, null).orEmpty()
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Throwable) {
            JSONObject()
        }
    }

    private fun save(root: JSONObject) {
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    companion object {
        private const val PREFS = "casttv_settings"
        private const val KEY = "custom_tab_channel_config"
    }
}
