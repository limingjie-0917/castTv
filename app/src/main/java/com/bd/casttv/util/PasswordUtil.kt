package com.bd.casttv.util

import java.security.MessageDigest

/**
 * 云同步合集密码工具：仅提供轻量 SHA-256 摘要与匹配校验。
 * 加密仅用于客户端下载/上传时的身份校验，不是真正的内容加密。
 */
object PasswordUtil {

    private const val SALT = "casttv-cloud-sync-v1180-fixed-salt"

    /** 将明文密码与固定 Salt 拼接后转换为 SHA-256 小写十六进制哈希。空串返回空串。 */
    fun sha256(input: String): String {
        if (input.isEmpty()) return ""
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((input + SALT).toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 0x10) append('0')
                append(Integer.toHexString(v))
            }
        }
    }

    /** 校验明文是否匹配已存哈希；哈希为空时视为「未设置密码」，明文任意都返回 true。 */
    fun matches(input: String, hash: String): Boolean {
        if (hash.isBlank()) return true
        return sha256(input).equals(hash.trim(), ignoreCase = true)
    }
}
