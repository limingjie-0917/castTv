package com.bd.casttv.dlna

import android.content.Context
import android.util.Log
import android.util.Xml
import com.bd.casttv.settings.DouyinDeviceGroup
import com.bd.casttv.settings.DouyinDeviceGroups
import com.bd.casttv.util.NetworkUtils
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 局域网主动 DLNA 设备扫描器 —— "克隆真电视"功能的核心。
 *
 * 与 [SsdpService] 互补：SsdpService 是被动应答 M-SEARCH 的服务端，
 * 本类是主动发起 M-SEARCH + 拉取 description.xml 的客户端。
 *
 * 流程（总扫描时长约 [SCAN_TOTAL_MS]）：
 *   1. 在每个 LAN 接口上发 [MSEARCH_ROUNDS] 轮 M-SEARCH（ST = MediaRenderer:1）
 *   2. 监听单播 200 OK，解析 LOCATION，按 host:port 去重
 *   3. 对每个 LOCATION 拉 /description.xml（超时 [HTTP_TIMEOUT_MS]）
 *   4. XmlPullParser 解析出 DeviceIdentity + UDN
 *   5. 包装为 DouyinDeviceGroup 回调 onComplete
 *
 * 过滤：排除本机自己广播的设备（按 UDN）、排除已克隆过的设备、
 * 只保留 deviceType == urn:schemas-upnp-org:device:MediaRenderer:1。
 *
 * 设计要点：
 *   - 单线程扫描调度（避免多 socket 同时收发混乱）
 *   - 拉 description.xml 用 5 个 worker 并发，避免老电视 HTTP 被压垮
 *   - 主回调在调用方传入的 [callbackExecutor] 上，UI 切换需在主线程
 */
class LanDeviceScanner {

    companion object {
        private const val TAG = "LanDeviceScanner"
        private const val MCAST_ADDR = "239.255.255.250"
        private const val MCAST_PORT = 1900
        private const val SCAN_TOTAL_MS = 6_000L
        private const val MSEARCH_ROUNDS = 3
        private const val MSEARCH_INTERVAL_MS = 1_000L
        private const val HTTP_TIMEOUT_MS = 2_000
        private const val HTTP_MAX_PARALLEL = 5
        private const val DEVICE_TYPE_MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

        private const val MSEARCH = (
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $MCAST_ADDR:$MCAST_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "ST: $DEVICE_TYPE_MEDIA_RENDERER\r\n" +
                "MX: 3\r\n" +
                "\r\n"
            )
    }

    /** 单台被发现的设备（未拉 description.xml 之前，identity 为空）。 */
    private data class DiscoveredDevice(
        val location: String,
        val ssdpServer: String?
    )

    /** 扫描结果回调。回调线程不保证主线程，调用方自行切线程。 */
    interface Callback {
        /** 扫描进度更新（已发现多少台，可能未拉 description.xml）。 */
        fun onProgress(foundCount: Int) {}
        /** 扫描完成。groups 已按 UDN 去重 + 过滤本机。 */
        fun onComplete(groups: List<DouyinDeviceGroup>, aborted: Boolean)
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lan-scanner").apply { isDaemon = true }
    }
    @Volatile private var running = false

    /** 启动一次扫描。重复调用会拒绝（返回 false）。 */
    fun startScan(
        context: Context,
        excludeUdns: Set<String>,
        callback: Callback,
        callbackExecutor: java.util.concurrent.Executor
    ): Boolean {
        if (running) {
            Log.w(TAG, "scan already running, ignore")
            return false
        }
        running = true
        executor.execute {
            try {
                doScan(context, excludeUdns, callback, callbackExecutor)
            } catch (t: Throwable) {
                Log.e(TAG, "scan failed", t)
                callbackExecutor.execute { callback.onComplete(emptyList(), aborted = true) }
            } finally {
                running = false
            }
        }
        return true
    }

    /** 主动中止扫描（不保证立即，但尽快完成）。 */
    fun abort() { running = false }

    private fun doScan(
        context: Context,
        excludeUdns: Set<String>,
        callback: Callback,
        callbackExecutor: java.util.concurrent.Executor
    ) {
        val selfUdn = try { NetworkUtils.getDeviceUdn(context) } catch (_: Throwable) { null }
        val interfaces = try { NetworkUtils.getLanInterfaces() } catch (_: Throwable) { emptyList() }
        if (interfaces.isEmpty()) {
            callbackExecutor.execute { callback.onComplete(emptyList(), aborted = false) }
            return
        }

        val discovered = ConcurrentHashMap<String, DiscoveredDevice>()
        val progressCount = AtomicInteger(0)

        // 监听 socket：每个接口一个，全程收 200 OK
        val listenSockets = mutableListOf<Pair<MulticastSocket, NetworkInterface>>()
        try {
            for (lan in interfaces) {
                try {
                    val s = MulticastSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(MCAST_PORT))
                        soTimeout = SCAN_TOTAL_MS.toInt()
                        joinGroup(InetSocketAddress(InetAddress.getByName(MCAST_ADDR), MCAST_PORT), lan.networkInterface)
                    }
                    listenSockets.add(s to lan.networkInterface)
                } catch (e: Exception) {
                    Log.w(TAG, "listen socket init failed on ${lan.name}: ${e.message}")
                }
            }
            if (listenSockets.isEmpty()) {
                callbackExecutor.execute { callback.onComplete(emptyList(), aborted = false) }
                return
            }

            // 启动 3 个监听线程
            val listenThreads = listenSockets.map { (s, nif) ->
                thread(name = "lan-scanner-listen-${nif.name}", isDaemon = true) {
                    val buf = ByteArray(4096)
                    while (running) {
                        try {
                            val pkt = DatagramPacket(buf, buf.size)
                            s.receive(pkt)
                            val msg = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                            if (!msg.startsWith("HTTP/1.", ignoreCase = true)) continue
                            val location = parseHeader(msg, "LOCATION") ?: continue
                            val server = parseHeader(msg, "SERVER")
                            if (discovered.putIfAbsent(location, DiscoveredDevice(location, server)) == null) {
                                val n = progressCount.incrementAndGet()
                                callbackExecutor.execute { callback.onProgress(n) }
                            }
                        } catch (e: Exception) {
                            if (!running) break
                            // so_timeout 也会进这里，继续循环即可
                        }
                    }
                }
            }

            // 发 3 轮 M-SEARCH
            val msearchPkt = MSEARCH.toByteArray(Charsets.UTF_8)
            for (round in 0 until MSEARCH_ROUNDS) {
                if (!running) break
                for ((s, nif) in listenSockets) {
                    try {
                        s.networkInterface = nif
                        s.send(DatagramPacket(msearchPkt, msearchPkt.size, InetAddress.getByName(MCAST_ADDR), MCAST_PORT))
                    } catch (e: Exception) {
                        Log.w(TAG, "M-SEARCH send failed: ${e.message}")
                    }
                }
                if (round < MSEARCH_ROUNDS - 1) {
                    try {
                        Thread.sleep(MSEARCH_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }

            // 等待 SCAN_TOTAL_MS 让响应收齐
            val deadline = System.currentTimeMillis() + SCAN_TOTAL_MS
            while (running && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
            running = false
            // 让监听线程退出（so_timeout 会兜底）
            listenThreads.forEach { try { it.interrupt() } catch (_: Throwable) {} }
        } finally {
            listenSockets.forEach { (s, nif) ->
                try { s.leaveGroup(InetSocketAddress(InetAddress.getByName(MCAST_ADDR), MCAST_PORT), nif) } catch (_: Throwable) {}
                try { s.close() } catch (_: Throwable) {}
            }
        }

        // 拉 description.xml
        val locations = discovered.values.toList()
        val groups = mutableListOf<DouyinDeviceGroup>()
        val fetchExecutor = Executors.newFixedThreadPool(HTTP_MAX_PARALLEL) { r ->
            Thread(r, "lan-scanner-fetch").apply { isDaemon = true }
        }
        val lock = Any()
        val futures = locations.map { dev ->
            fetchExecutor.submit {
                if (!running && groups.isNotEmpty()) return@submit
                val parsed = try {
                    fetchAndParse(dev.location, dev.ssdpServer)
                } catch (t: Throwable) {
                    Log.w(TAG, "fetch/parse failed for ${dev.location}: ${t.message}")
                    null
                }
                parsed?.let { (identity, udn) ->
                    // 过滤：本机自身、已克隆、非 MediaRenderer
                    if (udn.isBlank()) return@let
                    if (udn == selfUdn) return@let
                    if (udn in excludeUdns) return@let
                    val group = wrapAsGroup(identity, udn)
                    if (group != null) {
                        synchronized(lock) { groups.add(group) }
                        val n = synchronized(lock) { groups.size }
                        callbackExecutor.execute { callback.onProgress(n) }
                    }
                }
            }
        }
        fetchExecutor.shutdown()
        try { fetchExecutor.awaitTermination(SCAN_TOTAL_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        futures.forEach { try { it.get() } catch (_: Throwable) {} }
        fetchExecutor.shutdownNow()

        callbackExecutor.execute { callback.onComplete(groups, aborted = false) }
    }

    /** 拉 description.xml 并解析。返回 (DeviceIdentity, UDN)；失败返回 null。 */
    private fun fetchAndParse(location: String, ssdpServerHint: String?): Pair<DeviceIdentity, String>? {
        val url = try { URL(location) } catch (_: Throwable) { return null }
        val xmlBytes = try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CastTV-Scanner/1.0")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes() }
        } catch (t: Throwable) {
            Log.w(TAG, "HTTP GET $location failed: ${t.message}")
            return null
        }

        return parseDeviceDescription(xmlBytes, ssdpServerHint, location)
    }

    /**
     * XmlPullParser 解析 /description.xml。
     * 只取 device 节点下的关键字段；遇到 deviceType 不是 MediaRenderer 直接放弃。
     */
    private fun parseDeviceDescription(
        xml: ByteArray,
        ssdpServerHint: String?,
        location: String
    ): Pair<DeviceIdentity, String>? {
        var deviceType: String? = null
        var friendlyName: String? = null
        var manufacturer: String? = null
        var manufacturerURL: String? = null
        var modelDescription: String? = null
        var modelName: String? = null
        var modelNumber: String? = null
        var modelURL: String? = null
        var presentationURL: String? = null
        var udn: String? = null
        try {
            val parser = Xml.newPullParser()
            parser.setInput(ByteArrayInputStream(xml), "UTF-8")
            var event = parser.eventType
            // 简单状态机：仅关注 device 节点下的子节点
            var inDevice = false
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        if (name == "device") {
                            inDevice = true
                        } else if (inDevice) {
                            when (name) {
                                "deviceType" -> deviceType = parser.nextText()
                                "friendlyName" -> friendlyName = parser.nextText()
                                "manufacturer" -> manufacturer = parser.nextText()
                                "manufacturerURL" -> manufacturerURL = parser.nextText()
                                "modelDescription" -> modelDescription = parser.nextText()
                                "modelName" -> modelName = parser.nextText()
                                "modelNumber" -> modelNumber = parser.nextText()
                                "modelURL" -> modelURL = parser.nextText()
                                "presentationURL" -> presentationURL = parser.nextText()
                                "UDN" -> udn = parser.nextText()
                                // serviceList / iconList 等子树不在本扫描器关注范围
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "device") inDevice = false
                    }
                }
                event = try { parser.next() } catch (t: Throwable) {
                    Log.w(TAG, "xml parse break: ${t.message}")
                    return null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "xml parse failed: ${t.message}")
            return null
        }

        // 只接受 MediaRenderer（避免拉到 MediaServer / 路由器）
        if (deviceType.isNullOrBlank() || !deviceType!!.contains("MediaRenderer")) return null
        if (friendlyName.isNullOrBlank()) return null

        val udnNorm = udn?.trim().orEmpty()
        val identity = DeviceIdentity(
            friendlyName = friendlyName!!.trim(),
            manufacturer = manufacturer?.trim()?.ifBlank { "Unknown" } ?: "Unknown",
            manufacturerUrl = manufacturerURL?.trim()?.ifBlank { "https://casttv.local" } ?: "https://casttv.local",
            modelName = modelName?.trim()?.ifBlank { "Unknown" } ?: "Unknown",
            modelDescription = modelDescription?.trim()?.ifBlank { "DLNA Media Renderer" } ?: "DLNA Media Renderer",
            modelNumber = modelNumber?.trim()?.ifBlank { "1.0" } ?: "1.0",
            modelUrl = modelURL?.trim()?.ifBlank { manufacturerURL?.trim()?.ifBlank { "https://casttv.local" } ?: "https://casttv.local" } ?: "https://casttv.local",
            ssdpServer = ssdpServerHint?.trim()?.ifBlank { DeviceIdentity.DEFAULT_SSDP_SERVER } ?: DeviceIdentity.DEFAULT_SSDP_SERVER,
            dlnaProfiles = DeviceIdentity.DEFAULT_DLNA_PROFILES,
            icons = emptyList(),
            presentationUrl = presentationURL?.trim()?.ifBlank { "/" } ?: "/"
        )
        return identity to udnNorm
    }

    /** 包装为 DouyinDeviceGroup（单 member，id = custom_<udn 短哈希>）。 */
    private fun wrapAsGroup(identity: DeviceIdentity, udn: String): DouyinDeviceGroup? {
        if (udn.isBlank()) return null
        val hash = udn.removePrefix("uuid:").hashCode().toLong() and 0xFFFFFFFFL
        val id = "custom_" + hash.toString(16).padStart(8, '0').lowercase()
        val label = "克隆 · ${identity.friendlyName}"
        return DouyinDeviceGroup(
            id = id,
            label = label,
            members = listOf(identity),
            defaultIndex = 0
        )
    }

    private fun parseHeader(msg: String, name: String): String? {
        for (line in msg.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                if (key.equals(name, ignoreCase = true)) {
                    return line.substring(idx + 1).trim().trim('"')
                }
            }
        }
        return null
    }
}
