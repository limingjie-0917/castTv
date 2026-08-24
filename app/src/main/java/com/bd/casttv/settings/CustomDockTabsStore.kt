package com.bd.casttv.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 自定义 Dock Tab 配置（只负责“入口 → 绑定合集”的映射，不修改收藏数据）。
 */
class CustomDockTabsStore(context: Context) {

    data class CustomDockTab(
        val id: String,
        val name: String,
        val collectionId: String,
        val iconKey: String = DEFAULT_ICON_KEY,
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<CustomDockTab> {
        val raw = prefs.getString(KEY_TABS, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<CustomDockTab>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").orEmpty()
                val name = obj.optString("name").orEmpty()
                val collectionId = obj.optString("collectionId").orEmpty()
                val iconKey = obj.optString("iconKey", DEFAULT_ICON_KEY).ifBlank { DEFAULT_ICON_KEY }
                if (id.isBlank() || name.isBlank() || collectionId.isBlank()) continue
                out.add(CustomDockTab(id = id, name = name, collectionId = collectionId, iconKey = iconKey))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun add(name: String, collectionId: String, iconKey: String = DEFAULT_ICON_KEY): CustomDockTab {
        val tab = CustomDockTab(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "新 Tab" },
            collectionId = collectionId.trim(),
            iconKey = iconKey.ifBlank { DEFAULT_ICON_KEY },
        )
        val next = list().toMutableList().apply { add(tab) }
        save(next)
        return tab
    }

    fun update(updated: CustomDockTab) {
        val next = list().map {
            if (it.id == updated.id) updated else it
        }
        save(next)
    }

    fun remove(id: String) {
        val next = list().filterNot { it.id == id }
        save(next)
    }

    private fun save(list: List<CustomDockTab>) {
        val arr = JSONArray()
        list.forEach { tab ->
            arr.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("name", tab.name)
                    .put("collectionId", tab.collectionId)
                    .put("iconKey", tab.iconKey)
            )
        }
        prefs.edit().putString(KEY_TABS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "casttv_settings"
        private const val KEY_TABS = "custom_dock_tabs"
        const val DEFAULT_ICON_KEY = "collection"
    }
}
