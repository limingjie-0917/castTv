package com.bd.casttv.settings

import android.content.Context
import com.bd.casttv.favorites.HealthStatus
import com.bd.casttv.favorites.SourceHealthRecord
import org.json.JSONObject

/**
 * 自定义 Tab 源健康缓存。
 *
 * SharedPreferences: casttv_settings
 * Key: source_health_cache
 * JSON:
 * {
 *   "<uri.trim()>": { status, score, hasVideo, hasAudio, startupMs, reason, lastCheckMs, failCount, successCount }
 * }
 */
class SourceHealthStore(context: Context) {

    private val prefs = context.getSharedPreferences("casttv_settings", Context.MODE_PRIVATE)

    private val lock = Any()

    fun get(uri: String): SourceHealthRecord? {
        val key = uri.trim()
        if (key.isEmpty()) return null
        synchronized(lock) {
            val root = readRootLocked() ?: return null
            val obj = root.optJSONObject(key) ?: return null
            return parseRecord(obj)
        }
    }

    fun all(): Map<String, SourceHealthRecord> {
        synchronized(lock) {
            val root = readRootLocked() ?: return emptyMap()
            val out = LinkedHashMap<String, SourceHealthRecord>()
            val it = root.keys()
            while (it.hasNext()) {
                val uri = it.next() ?: continue
                val obj = root.optJSONObject(uri) ?: continue
                out[uri] = parseRecord(obj)
            }
            return out
        }
    }

    fun put(uri: String, record: SourceHealthRecord) {
        val key = uri.trim()
        if (key.isEmpty()) return
        synchronized(lock) {
            val root = readRootLocked() ?: JSONObject()
            root.put(key, recordToJson(record))
            prefs.edit().putString(KEY_CACHE, root.toString()).apply()
        }
    }

    /**
     * TTL 规则：
     * - 以直播源地址 uri.trim() 为缓存 key，地址变化后自然不会复用旧结果
     * - 无记录 / UNKNOWN / lastCheckMs 无效 → 需要检测
     * - 距上次检测超过 2 小时 → 需要复检
     * - 2 小时以内直接复用上次检测结果，避免短时间重复检测影响播放体验
     */
    fun needsCheck(uri: String): Boolean {
        val key = uri.trim()
        if (key.isEmpty()) return false
        val record = get(key) ?: return true
        if (record.status == HealthStatus.UNKNOWN) return true
        if (record.lastCheckMs <= 0L) return true

        val elapsed = System.currentTimeMillis() - record.lastCheckMs
        return elapsed > TTL_SOURCE_MS
    }

    private fun readRootLocked(): JSONObject? {
        val raw = prefs.getString(KEY_CACHE, null) ?: return null
        return try {
            if (raw.isBlank()) null else JSONObject(raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseRecord(obj: JSONObject): SourceHealthRecord {
        val status = try {
            HealthStatus.valueOf(obj.optString("status", HealthStatus.UNKNOWN.name))
        } catch (_: Throwable) {
            HealthStatus.UNKNOWN
        }
        return SourceHealthRecord(
            status = status,
            score = obj.optInt("score", SourceHealthRecord.UNKNOWN.score),
            hasVideo = obj.optBoolean("hasVideo", false),
            hasAudio = obj.optBoolean("hasAudio", false),
            startupMs = obj.optLong("startupMs", -1L),
            reason = obj.optString("reason", ""),
            lastCheckMs = obj.optLong("lastCheckMs", 0L),
            failCount = obj.optInt("failCount", 0),
            successCount = obj.optInt("successCount", 0),
        )
    }

    private fun recordToJson(record: SourceHealthRecord): JSONObject {
        return JSONObject().apply {
            put("status", record.status.name)
            put("score", record.score)
            put("hasVideo", record.hasVideo)
            put("hasAudio", record.hasAudio)
            put("startupMs", record.startupMs)
            put("reason", record.reason)
            put("lastCheckMs", record.lastCheckMs)
            put("failCount", record.failCount)
            put("successCount", record.successCount)
        }
    }

    private companion object {
        private const val KEY_CACHE = "source_health_cache"
        private const val TTL_SOURCE_MS = 2 * 60 * 60 * 1000L
    }
}
