package com.bd.casttv.dlna

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight GENA event subscription manager for DLNA AVTransport / RenderingControl.
 *
 * Control points subscribe to event endpoints and receive NOTIFY requests carrying LastChange.
 * Network delivery is best-effort: failures are logged but never block SOAP handling.
 */
class GenaEventManager {
    data class SubscriberInfo(
        val sid: String,
        val callbackUrl: String,
        val service: String,
        @Volatile var expireAt: Long,
        val seq: AtomicInteger = AtomicInteger(0)
    )

    private val subscribers = ConcurrentHashMap<String, SubscriberInfo>()
    // 2 线程池：抖音同时订阅 AVTransport + RenderingControl 两个服务，
    // 单线程串行发送 NOTIFY 时如果 callback URL 响应慢会排队延迟，
    // 控制点可能因收不到事件而判定设备离线。
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "gena-notify").apply { isDaemon = true }
    }

    fun subscribe(callbackHeader: String, service: String, timeoutSeconds: Int, initialLastChangeXml: String): SubscriberInfo? {
        val callbackUrl = parseCallbackUrl(callbackHeader) ?: return null
        cleanupExpired()
        val sid = "uuid:${UUID.randomUUID()}"
        val info = SubscriberInfo(
            sid = sid,
            callbackUrl = callbackUrl,
            service = service,
            expireAt = System.currentTimeMillis() + timeoutSeconds.coerceAtLeast(1) * 1000L
        )
        subscribers[sid] = info
        sendNotifyToSubscriber(info, initialLastChangeXml)
        return info
    }

    fun renew(sid: String, timeoutSeconds: Int): SubscriberInfo? {
        cleanupExpired()
        val info = subscribers[sid] ?: return null
        info.expireAt = System.currentTimeMillis() + timeoutSeconds.coerceAtLeast(1) * 1000L
        return info
    }

    fun unsubscribe(sid: String): Boolean = subscribers.remove(sid) != null

    fun sendNotify(service: String, lastChangeXml: String) {
        cleanupExpired()
        subscribers.values
            .filter { it.service == service && it.expireAt > System.currentTimeMillis() }
            .forEach { sendNotifyToSubscriber(it, lastChangeXml) }
    }

    fun shutdown() {
        subscribers.clear()
        try { executor.shutdownNow() } catch (_: Throwable) {}
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        subscribers.entries.removeIf { it.value.expireAt <= now }
    }

    private fun sendNotifyToSubscriber(info: SubscriberInfo, body: String) {
        executor.execute {
            val seq = info.seq.getAndIncrement().coerceAtLeast(0)
            var conn: HttpURLConnection? = null
            try {
                val data = body.toByteArray(Charsets.UTF_8)
                conn = (URL(info.callbackUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "NOTIFY"
                    connectTimeout = 1_000
                    readTimeout = 1_000
                    doOutput = true
                    setRequestProperty("HOST", URL(info.callbackUrl).let { "${it.host}:${if (it.port > 0) it.port else it.defaultPort}" })
                    setRequestProperty("CONTENT-TYPE", "text/xml; charset=\"utf-8\"")
                    setRequestProperty("NT", "upnp:event")
                    setRequestProperty("NTS", "upnp:propchange")
                    setRequestProperty("SID", info.sid)
                    setRequestProperty("SEQ", seq.toString())
                    setRequestProperty("CONTENT-LENGTH", data.size.toString())
                }
                conn.outputStream.use { it.write(data) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "GENA NOTIFY non-2xx service=${info.service} code=$code")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "GENA NOTIFY failed service=${info.service}", t)
                try {
                    SsdpDiagnostics.logServiceHealth(
                        module = "GENA",
                        level = SsdpDiagnostics.ServiceHealthEvent.Level.WARN,
                        event = "notify_failed",
                        message = "service=${info.service}",
                        throwable = t
                    )
                } catch (_: Throwable) {}
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
    }

    private fun parseCallbackUrl(header: String): String? {
        val trimmed = header.trim()
        if (trimmed.isBlank()) return null
        val match = Regex("<([^>]+)>").find(trimmed)
        val value = (match?.groupValues?.getOrNull(1) ?: trimmed.split(',').firstOrNull().orEmpty()).trim().trim('<', '>')
        if (!value.lowercase(Locale.US).startsWith("http://") && !value.lowercase(Locale.US).startsWith("https://")) return null
        return value
    }

    companion object {
        private const val TAG = "GenaEventManager"
    }
}
