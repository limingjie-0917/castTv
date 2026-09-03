package com.bd.casttv.webparse

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class WebParseStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Progress(val sourceIndex: Int, val episodeIndex: Int, val positionSec: Long)
    data class ParseHistory(
        val title: String,
        val url: String,
        val updatedAt: Long,
        val pageType: String = "",
        val siteTitle: String = "",
        val frameworkType: String = "",
        val adapterName: String = "",
        val adapterId: String = "",
        val recordId: String = ""
    )



    fun saveProgress(url: String, sourceIndex: Int, episodeIndex: Int, positionSec: Long) {
        if (url.isBlank()) return
        prefs.edit()
            .putString(KEY_URL, url)
            .putInt(KEY_SOURCE_INDEX, sourceIndex.coerceAtLeast(0))
            .putInt(KEY_EPISODE_INDEX, episodeIndex.coerceAtLeast(0))
            .putLong(KEY_POSITION_SEC, positionSec.coerceAtLeast(0L))
            .apply()
    }

    fun getProgress(url: String): Progress? {
        if (url.isBlank()) return null
        val savedUrl = prefs.getString(KEY_URL, "").orEmpty()
        if (savedUrl != url) return null
        return Progress(
            sourceIndex = prefs.getInt(KEY_SOURCE_INDEX, 0).coerceAtLeast(0),
            episodeIndex = prefs.getInt(KEY_EPISODE_INDEX, 0).coerceAtLeast(0),
            positionSec = prefs.getLong(KEY_POSITION_SEC, 0L).coerceAtLeast(0L)
        )
    }

    fun saveParseHistory(
        title: String,
        url: String,
        pageType: String = "",
        siteTitle: String = "",
        frameworkType: String = "",
        adapterName: String = "",
        adapterId: String = "",
        recordId: String = ""
    ) {
        val normalizedUrl = url.trim()
        if (normalizedUrl.isBlank()) return
        val displayTitle = title.trim().ifBlank { WebParseHtml.shortUrl(normalizedUrl) }
        val normalizedType = normalizePageType(pageType)
        val rid = recordId.ifBlank { generateRecordId(normalizedUrl, normalizedType) }
        val updated = ParseHistory(
            title = displayTitle,
            url = normalizedUrl,
            updatedAt = System.currentTimeMillis(),
            pageType = normalizedType,
            siteTitle = siteTitle.trim(),
            frameworkType = frameworkType.trim(),
            adapterName = adapterName.trim(),
            adapterId = adapterId.trim(),
            recordId = rid
        )
        val histories = getParseHistory()
            .filterNot { it.recordId == rid }
            .toMutableList()
            .apply { add(0, updated) }
            .take(MAX_HISTORY_COUNT)
        prefs.edit().putString(KEY_PARSE_HISTORY, encodeHistory(histories)).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_PARSE_HISTORY).apply()
    }

    fun getParseHistory(): List<ParseHistory> {
        val raw = prefs.getString(KEY_PARSE_HISTORY, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url").trim()
                    if (url.isBlank()) continue
                    val rid = item.optString("recordId", "").trim()
                    add(
                        ParseHistory(
                            title = item.optString("title").trim().ifBlank { WebParseHtml.shortUrl(url) },
                            url = url,
                            updatedAt = item.optLong("updatedAt", 0L),
                            pageType = normalizePageType(item.optString("pageType")),
                            siteTitle = item.optString("siteTitle").trim(),
                            frameworkType = item.optString("frameworkType").trim(),
                            adapterName = item.optString("adapterName").trim(),
                            adapterId = item.optString("adapterId").trim(),
                            recordId = rid
                        )
                    )
                }
            }.distinctBy { it.recordId.ifBlank { it.url } }.take(MAX_HISTORY_COUNT)
        }.getOrElse { emptyList() }
    }

    private fun normalizePageType(value: String): String = when (value.trim().lowercase()) {
        "list" -> "list"
        "detail" -> "detail"
        else -> ""
    }

    private fun encodeHistory(histories: List<ParseHistory>): String {
        val array = JSONArray()
        histories.take(MAX_HISTORY_COUNT).forEach { history ->
            array.put(JSONObject().apply {
                put("title", history.title)
                put("url", history.url)
                put("updatedAt", history.updatedAt)
                put("pageType", history.pageType)
                put("siteTitle", history.siteTitle)
                put("frameworkType", history.frameworkType)
                put("adapterName", history.adapterName)
                put("adapterId", history.adapterId)
                put("recordId", history.recordId)
            })
        }
        return array.toString()
    }

    companion object {
        fun extractSiteTitle(html: String): String = WebParseHtml.tagText(html, "<title[^>]*>([\\s\\S]*?)</title>")
            .replace(Regex("\\s+"), " ")
            .trim()

        fun generateRecordId(url: String, pageType: String): String {
            val raw = "${pageType.trim().lowercase()}|${url.trim().lowercase()}"
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return buildString(bytes.size * 2) {
                for (b in bytes) {
                    val v = b.toInt() and 0xFF
                    if (v < 0x10) append('0')
                    append(Integer.toHexString(v))
                }
            }.take(16)
        }

        private const val PREFS = "web_parse_store"
        private const val KEY_URL = "current_url"
        private const val KEY_SOURCE_INDEX = "source_index"
        private const val KEY_EPISODE_INDEX = "episode_index"
        private const val KEY_POSITION_SEC = "position_sec"
        private const val KEY_PARSE_HISTORY = "parse_history"
        private const val MAX_HISTORY_COUNT = 20
    }
}
