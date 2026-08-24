package com.bd.casttv.favorites

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 收藏页「导入直播源」手机扫码提交服务。
 *
 * 仅在局域网内暴露极简 HTTP 表单：GET / 展示输入页，POST /submit 接收链接，GET /poll 供电视端轮询。
 */
class LiveSourceSubmitServer(private val preferredPort: Int = 18888) {
    private val running = AtomicBoolean(false)
    private val latestUrl = AtomicReference<String?>(null)
    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    val port: Int get() = serverSocket?.localPort ?: preferredPort

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            serverSocket = try {
                ServerSocket(preferredPort)
            } catch (_: Throwable) {
                ServerSocket(0)
            }
            running.set(true)
            worker = Thread({ loop() }, "LiveSourceSubmitServer").apply {
                isDaemon = true
                start()
            }
            true
        } catch (_: Throwable) {
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        worker = null
        latestUrl.set(null)
    }

    fun consumeSubmittedUrl(): String? = latestUrl.getAndSet(null)

    private fun loop() {
        val socket = serverSocket ?: return
        while (running.get()) {
            try {
                socket.accept()?.use { handle(it) }
            } catch (_: Throwable) {
                if (running.get()) continue else break
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 3000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val requestLine = reader.readLine().orEmpty()
        if (requestLine.isBlank()) return
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0).orEmpty().uppercase()
        val path = parts.getOrNull(1).orEmpty()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }
        val body = if (contentLength > 0) {
            val chars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(chars, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            String(chars, 0, read)
        } else ""

        when {
            method == "GET" && path.startsWith("/poll") -> {
                val url = latestUrl.get().orEmpty()
                writeText(socket.getOutputStream(), if (url.isBlank()) "" else url, "text/plain; charset=utf-8")
            }
            method == "POST" && path.startsWith("/submit") -> {
                val link = parseFormValue(body, "url").trim()
                if (link.isNotBlank()) latestUrl.set(link)
                writeText(socket.getOutputStream(), successHtml(), "text/html; charset=utf-8")
            }
            else -> writeText(socket.getOutputStream(), indexHtml(), "text/html; charset=utf-8")
        }
    }

    private fun parseFormValue(body: String, key: String): String {
        return body.split('&').firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@firstNotNullOfOrNull null
            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            if (k == key) URLDecoder.decode(pair.substring(idx + 1), "UTF-8") else null
        }.orEmpty()
    }

    private fun indexHtml(): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>发送直播源到电视</title><style>body{font-family:sans-serif;background:#151821;color:#fff;padding:24px}textarea{box-sizing:border-box;width:100%;height:150px;border-radius:12px;border:1px solid #f6c445;padding:12px;font-size:16px}button{margin-top:16px;width:100%;height:48px;border:0;border-radius:12px;background:#f6c445;color:#1b1f26;font-size:18px;font-weight:700}.tip{color:#c9cdd4;margin:12px 0 18px}</style></head>
        <body><h2>📡 发送直播源到电视端</h2><p class="tip">粘贴 M3U/M3U8 或直播源 JSON 直链，电视端可直接导入</p><form method="post" action="/submit"><textarea name="url" placeholder="粘贴 M3U/M3U8 或直播源 JSON 直链"></textarea><button type="submit">提交</button></form></body></html>
    """.trimIndent()

    private fun successHtml(): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>已发送</title><style>body{font-family:sans-serif;background:#151821;color:#fff;display:flex;align-items:center;justify-content:center;min-height:90vh;text-align:center}h2{color:#f6c445}</style></head><body><h2>已发送到电视 ✅</h2></body></html>
    """.trimIndent()

    private fun writeText(out: OutputStream, text: String, contentType: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
