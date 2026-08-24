package com.bd.casttv.util

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * 为本地合集生成创建者标识：
 * - 主方案使用 ANDROID_ID，满足同设备/同用户/同签名应用下尽量稳定；
 * - 极端情况下回退到本地持久化 UUID。
 */
object CreatorIdProvider {

    private const val PREFS_NAME = "casttv_creator_identity"
    private const val KEY_FALLBACK_INSTALL_ID = "fallback_install_id"
    private const val HASH_SALT = "casttv-creator-id-v1"

    @Volatile
    private var cachedCreatorId: String? = null

    fun get(context: Context): String {
        cachedCreatorId?.let { return it }
        val appContext = context.applicationContext
        val rawId = readAndroidId(appContext).ifBlank { readOrCreateFallbackInstallId(appContext) }
        val creatorId = "creator_" + sha256("$HASH_SALT:$rawId").take(24)
        cachedCreatorId = creatorId
        return creatorId
    }

    private fun readAndroidId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.trim()
                .orEmpty()
                .takeUnless { it.isBlank() || it.equals("9774d56d682e549c", ignoreCase = true) }
                .orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun readOrCreateFallbackInstallId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_FALLBACK_INSTALL_ID, "").orEmpty().trim()
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_FALLBACK_INSTALL_ID, created).apply()
        return created
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val value = b.toInt() and 0xFF
                if (value < 0x10) append('0')
                append(Integer.toHexString(value))
            }
        }
    }
}
