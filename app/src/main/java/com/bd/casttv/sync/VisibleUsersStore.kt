package com.bd.casttv.sync

import android.content.Context
import com.bd.casttv.dlna.SsdpDiagnostics
import com.bd.casttv.settings.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推荐可见用户云端数据读写。
 *
 * visible_users.json:
 * [
 *   { "creatorId": "creator_xxx", "deviceName": "王冉的电视", "visible": true }
 * ]
 */
class VisibleUsersStore(private val context: Context) {

    data class VisibleUser(
        val creatorId: String,
        val deviceName: String,
        val visible: Boolean
    )

    companion object {
        const val REMOTE_PATH = "visible_users.json"
        private const val TAG = "VisibleUsersStore"
        private const val MAX_UPDATE_RETRIES = 3
    }

    fun fetch(): Pair<List<VisibleUser>, String?>? {
        val result = GiteeApi.getFileResult(REMOTE_PATH)
        return when (result) {
            is GiteeApi.ApiResult.Success -> parse(result.value.content) to result.value.sha.takeIf { it.isNotBlank() }
            GiteeApi.ApiResult.NotFound -> emptyList<VisibleUser>() to null
            is GiteeApi.ApiResult.Error -> {
                SsdpDiagnostics.logCloudSync("$TAG fetch 失败：${result.message}")
                null
            }
        }
    }

    fun fetchVisibleUsers(): List<VisibleUser>? {
        return fetch()?.first
            ?.filter { it.visible && it.creatorId.isNotBlank() }
            ?.distinctBy { it.creatorId }
    }

    fun updateCurrentUserVisible(visible: Boolean, latestDeviceName: String? = null): Boolean {
        val creatorId = RecommenderIdentity.creatorId(context).trim()
        if (creatorId.isBlank()) return false
        val deviceName = latestDeviceName
            ?.trim()
            ?.ifBlank { null }
            ?: runCatching { Settings(context).deviceName }.getOrDefault(Settings.DEFAULT_DEVICE_NAME)
                .ifBlank { Settings.DEFAULT_DEVICE_NAME }

        var attempt = 0
        while (attempt < MAX_UPDATE_RETRIES) {
            attempt++
            val pair = fetch() ?: return false
            val (remote, sha) = pair
            val map = linkedMapOf<String, VisibleUser>()
            remote.forEach { user ->
                val id = user.creatorId.trim()
                if (id.isNotBlank()) map[id] = user.copy(creatorId = id)
            }
            map[creatorId] = VisibleUser(
                creatorId = creatorId,
                deviceName = deviceName,
                visible = visible
            )
            val json = serialize(map.values.toList())
            val put = GiteeApi.putFileResult(
                REMOTE_PATH,
                json,
                sha,
                commitMessage = "recommend: update visible user $creatorId"
            )
            when (put) {
                is GiteeApi.ApiResult.Success -> {
                    SsdpDiagnostics.logCloudSync("$TAG update 成功：creatorId=$creatorId, visible=$visible, attempt=$attempt")
                    return true
                }
                is GiteeApi.ApiResult.Error -> {
                    SsdpDiagnostics.logCloudSync("$TAG update 重试 attempt=$attempt，错误：${put.message.take(160)}")
                    if (attempt >= MAX_UPDATE_RETRIES) return false
                }
                GiteeApi.ApiResult.NotFound -> if (attempt >= MAX_UPDATE_RETRIES) return false
            }
        }
        return false
    }

    private fun parse(content: String): List<VisibleUser> {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return emptyList()
        return try {
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("users") ?: JSONArray()
                else -> JSONArray()
            }
            val list = mutableListOf<VisibleUser>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val creatorId = obj.optString("creatorId").trim()
                if (creatorId.isBlank()) continue
                list.add(
                    VisibleUser(
                        creatorId = creatorId,
                        deviceName = obj.optString("deviceName").ifBlank { "未知设备" },
                        visible = obj.optBoolean("visible", false)
                    )
                )
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun serialize(users: List<VisibleUser>): String {
        val arr = JSONArray()
        users.forEach { user ->
            val id = user.creatorId.trim()
            if (id.isBlank()) return@forEach
            arr.put(JSONObject().apply {
                put("creatorId", id)
                put("deviceName", user.deviceName.ifBlank { "未知设备" })
                put("visible", user.visible)
            })
        }
        return arr.toString()
    }
}
