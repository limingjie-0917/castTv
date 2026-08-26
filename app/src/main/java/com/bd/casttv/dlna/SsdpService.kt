package com.bd.casttv.dlna

import android.util.Log
import com.bd.casttv.util.NetworkUtils.LanInterface
import com.bd.casttv.dlna.SsdpDiagnostics
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * SSDP (UPnP discovery) over raw multicast UDP.
 *
 * - Joins 239.255.255.250:1900 on every usable LAN interface, listens for
 *   M-SEARCH and answers matching targets with a unicast 200 OK.
 * - Periodically multicasts NOTIFY ssdp:alive; sends ssdp:byebye on stop.
 *
 * No third-party UPnP library is used — everything is done with
 * [MulticastSocket] and hand-built HTTPU messages.
 */
class SsdpService(
    private val interfaceProvider: () -> List<LanInterface>,
    private val httpPort: Int,
    private val udnProvider: () -> String,
    /**
     * 动态提供 SSDP NOTIFY / 200 OK 中的 SERVER 头。
     * 由设备身份组按品牌给出真机常见字符串（Cling / Allegro / Rygel …），
     * 绝对不要返回 "CastTV"，否则抖音会按字符串黑名单过滤。
     */
    private val serverProvider: () -> String
) {
    companion object {
        private const val TAG = "SsdpService"
        private const val MCAST_ADDR = "239.255.255.250"
        private const val MCAST_PORT = 1900
        private const val MAX_AGE = 1800
        // Douyin is more aggressive than many DLNA control points: if it misses
        // multicast advertisements on Wi-Fi for a short period, it may re-search
        // and re-fetch /description.xml during playback. But 10s was too aggressive
        // — it caused Douyin to constantly re-discover and re-fetch description.xml,
        // flooding diagnostics and wasting CPU/network. 30s is still far below
        // CACHE-CONTROL max-age=1800 while being calm enough to avoid re-discovery storms.
        private const val ALIVE_INTERVAL_MS = 30_000L
        // Number of times each SSDP answer / advertisement is repeated to
        // survive UDP multicast packet loss (unreliable on Wi-Fi).
        // Reduced from 3 to 2: 3 copies × 6 targets per M-SEARCH caused the
        // control point to receive duplicate responses and re-fetch description.xml.
        private const val RESPONSE_REPEATS = 2
        // Initial NOTIFY ssdp:alive burst (rounds) sent quickly on startup so
        // control points that are already searching discover us immediately.
        private const val ALIVE_BURST_ROUNDS = 3
        private const val ALIVE_BURST_INTERVAL_MS = 250L
        private const val BOOTID = 1
        private const val CONFIGID = 1

        // Targets that this MediaRenderer answers on M-SEARCH.
        private val ROOT_DEVICE = "upnp:rootdevice"
        private val SSDP_ALL = "ssdp:all"
    }

    private data class Binding(
        val lan: LanInterface,
        val socket: MulticastSocket,
        var listenThread: Thread? = null
    )

    @Volatile private var running = false
    private val bindings = mutableListOf<Binding>()
    private var aliveThread: Thread? = null
    // Sends M-SEARCH answers off the receive loop, with staggered repeats, so a
    // burst of searches never blocks packet reception.
    private var responder: ScheduledExecutorService? = null
    private val group = InetAddress.getByName(MCAST_ADDR)
    private val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

    fun start() {
        if (running) return
        running = true
        try {
            val interfaces = interfaceProvider().distinctBy { "${it.name}:${it.ip}" }
            try { SsdpDiagnostics.updateInterfaces(interfaces) } catch (_: Throwable) {}
            if (interfaces.isEmpty()) {
                Log.w(TAG, "No LAN interface; SSDP not started")
                SsdpDiagnostics.logServiceHealth("SSDP", SsdpDiagnostics.ServiceHealthEvent.Level.WARN, "start_no_interface", "No LAN interface; SSDP not started")
                running = false
                return
            }

            responder = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "ssdp-responder").apply { isDaemon = true }
            }

            for (lan in interfaces) {
                try {
                    createBinding(lan)?.let { binding ->
                        binding.listenThread = thread(
                            name = "ssdp-listen-${lan.name}",
                            isDaemon = true
                        ) { listenLoop(binding) }
                        bindings.add(binding)
                        Log.i(TAG, "SSDP joined ${lan.name} ${lan.ip}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSDP interface skipped: ${lan.name} ${lan.ip}", e)
                    SsdpDiagnostics.logServiceHealth("SSDP", SsdpDiagnostics.ServiceHealthEvent.Level.WARN, "bind_failed", "interface=${lan.name} ip=${lan.ip}", e)
                }
            }

            if (bindings.isEmpty()) {
                Log.w(TAG, "No multicast-capable LAN interface; SSDP not started")
                SsdpDiagnostics.logServiceHealth("SSDP", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "all_bind_failed", "No multicast-capable LAN interface; SSDP not started")
                running = false
                cleanupSockets(sendByebye = false)
                return
            }

            aliveThread = thread(name = "ssdp-alive", isDaemon = true) { aliveLoop() }
            Log.i(TAG, "SSDP started on ${bindings.joinToString { "${it.lan.name}:${it.lan.ip}" }}")
        } catch (e: Exception) {
            Log.e(TAG, "SSDP start failed", e)
            SsdpDiagnostics.logServiceHealth("SSDP", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "start_failed", "SSDP start failed", e)
            running = false
            cleanupSockets(sendByebye = false)
        }
    }

    private fun createBinding(lan: LanInterface): Binding? {
        val s = MulticastSocket(null)
        try {
            s.reuseAddress = true
            s.timeToLive = 4
            s.networkInterface = lan.networkInterface
            s.bind(InetSocketAddress(MCAST_PORT))
            s.joinGroup(InetSocketAddress(group, MCAST_PORT), lan.networkInterface)
            return Binding(lan, s)
        } catch (e: Exception) {
            SsdpDiagnostics.logServiceHealth("SSDP", SsdpDiagnostics.ServiceHealthEvent.Level.WARN, "create_binding_failed", "interface=${lan.name} ip=${lan.ip}", e)
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    fun stop(sendByebye: Boolean = true) {
        if (!running && bindings.isEmpty()) return
        running = false
        cleanupSockets(sendByebye = sendByebye)
        try { responder?.shutdownNow() } catch (_: Exception) {}
        responder = null
        aliveThread?.interrupt()
        aliveThread = null
        Log.i(TAG, "SSDP stopped sendByebye=$sendByebye")
    }

    fun advertiseAliveNow() {
        if (!running) return
        sendAliveToAllBindings()
    }

    private fun cleanupSockets(sendByebye: Boolean) {
        val snapshot = synchronized(bindings) { bindings.toList().also { bindings.clear() } }
        if (sendByebye) {
            for (binding in snapshot) {
                try { sendByebye(binding) } catch (_: Exception) {}
            }
        }
        for (binding in snapshot) {
            try { binding.socket.leaveGroup(InetSocketAddress(group, MCAST_PORT), binding.lan.networkInterface) } catch (_: Exception) {}
            try { binding.socket.close() } catch (_: Exception) {}
            try { binding.listenThread?.interrupt() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // M-SEARCH listener
    // ------------------------------------------------------------------
    private fun listenLoop(binding: Binding) {
        val buf = ByteArray(2048)
        val s = binding.socket
        val ip = binding.lan.ip
        while (running && !Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                s.receive(packet)
                val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!msg.startsWith("M-SEARCH", ignoreCase = true)) continue
                if (!msg.contains("ssdp:discover")) continue
                val st = parseHeader(msg, "ST")?.trim() ?: continue
                try { SsdpDiagnostics.logMSearch(packet.address.hostAddress ?: "?", st) } catch (_: Throwable) {}
                handleSearch(s, packet.address, packet.port, st, ip)
            } catch (e: Exception) {
                if (!running) break
                // Transient error (e.g., socket closed on stop) — keep looping.
            }
        }
    }

    private fun handleSearch(
        s: MulticastSocket, addr: InetAddress, port: Int, st: String, ip: String
    ) {
        val uuid = udnProvider() // "uuid:xxxx"
        val targets = matchTargets(st, uuid)
        if (targets.isEmpty()) return
        // Pre-build the datagrams once, then fire each answer RESPONSE_REPEATS
        // times with small random offsets. UDP multicast answers are frequently
        // dropped on Wi-Fi, so a single unicast reply is the #1 cause of
        // "sometimes not discovered / works after a few tries".
        val packets = targets.map { (stValue, usn) ->
            val data = buildSearchResponse(stValue, usn, ip).toByteArray(Charsets.UTF_8)
            DatagramPacket(data, data.size, addr, port)
        }
        val exec = responder
        for (repeat in 0 until RESPONSE_REPEATS) {
            // First answer immediately; subsequent ones jittered within ~100ms.
            val delayMs = if (repeat == 0) 0L else Random.nextLong(20L, 100L) + repeat * 30L
            val task = Runnable {
                if (!running) return@Runnable
                for (p in packets) {
                    try {
                        // Diagnostics: log destination + LOCATION for each reply.
                        val loc = try { String(p.data, 0, p.length, Charsets.UTF_8) } catch (_: Throwable) { "" }
                        val location = parseHeader(loc, "LOCATION") ?: ""
                        if (location.isNotBlank()) {
                            try { SsdpDiagnostics.logResponse(addr.hostAddress ?: "?", location) } catch (_: Throwable) {}
                        }
                        s.send(p)
                    } catch (_: Exception) {}
                }
            }
            try {
                if (exec != null && !exec.isShutdown) exec.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                else task.run()
            } catch (_: Exception) { task.run() }
        }
    }

    /**
     * Returns the (ST, USN) pairs to answer for a given search target.
     * ssdp:all triggers one response per advertised type.
     */
    private fun matchTargets(st: String, uuid: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val allTypes = listOf(
            ROOT_DEVICE to "$uuid::$ROOT_DEVICE",
            UpnpXml.DEVICE_TYPE to "$uuid::${UpnpXml.DEVICE_TYPE}",
            UpnpXml.SVC_AVTRANSPORT to "$uuid::${UpnpXml.SVC_AVTRANSPORT}",
            UpnpXml.SVC_RENDERING to "$uuid::${UpnpXml.SVC_RENDERING}",
            UpnpXml.SVC_CONNMGR to "$uuid::${UpnpXml.SVC_CONNMGR}",
            uuid to uuid
        )
        val target = st.trim()
        when {
            target.equals(SSDP_ALL, ignoreCase = true) -> list.addAll(allTypes)
            target.equals(ROOT_DEVICE, ignoreCase = true) -> list.add(ROOT_DEVICE to "$uuid::$ROOT_DEVICE")
            target.equals(UpnpXml.DEVICE_TYPE, ignoreCase = true) -> list.add(UpnpXml.DEVICE_TYPE to "$uuid::${UpnpXml.DEVICE_TYPE}")
            target.equals(UpnpXml.SVC_AVTRANSPORT, ignoreCase = true) -> list.add(UpnpXml.SVC_AVTRANSPORT to "$uuid::${UpnpXml.SVC_AVTRANSPORT}")
            target.equals(UpnpXml.SVC_RENDERING, ignoreCase = true) -> list.add(UpnpXml.SVC_RENDERING to "$uuid::${UpnpXml.SVC_RENDERING}")
            target.equals(UpnpXml.SVC_CONNMGR, ignoreCase = true) -> list.add(UpnpXml.SVC_CONNMGR to "$uuid::${UpnpXml.SVC_CONNMGR}")
            target.equals(uuid, ignoreCase = true) -> list.add(uuid to uuid)
        }
        return list
    }

    private fun buildSearchResponse(st: String, usn: String, ip: String): String {
        val location = "http://$ip:$httpPort/description.xml"
        return "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=$MAX_AGE\r\n" +
            "DATE: ${httpDate()}\r\n" +
            "EXT:\r\n" +
            "LOCATION: $location\r\n" +
            "SERVER: ${serverProvider()}\r\n" +
            "ST: $st\r\n" +
            "USN: $usn\r\n" +
            "BOOTID.UPNP.ORG: $BOOTID\r\n" +
            "CONFIGID.UPNP.ORG: $CONFIGID\r\n" +
            "\r\n"
    }

    // ------------------------------------------------------------------
    // NOTIFY alive / byebye
    // ------------------------------------------------------------------
    private fun aliveLoop() {
        // Fast initial burst so control points already scanning the LAN pick us
        // up within a second, then settle into periodic re-advertising.
        try {
            for (round in 0 until ALIVE_BURST_ROUNDS) {
                if (!running) return
                sendAliveToAllBindings()
                Thread.sleep(ALIVE_BURST_INTERVAL_MS)
            }
        } catch (_: InterruptedException) {
            return
        } catch (_: Exception) { /* ignore, fall through to periodic */ }

        while (running && !Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(ALIVE_INTERVAL_MS)
                sendAliveToAllBindings()
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                try { Thread.sleep(ALIVE_INTERVAL_MS) } catch (_: Exception) { break }
            }
        }
    }

    private fun sendAliveToAllBindings() {
        val snapshot = synchronized(bindings) { bindings.toList() }
        for (binding in snapshot) {
            sendAlive(binding)
        }
    }

    private fun sendAlive(binding: Binding) {
        val uuid = udnProvider()
        val entries = advertiseEntries(uuid)
        for ((nt, usn) in entries) {
            sendNotify(binding, nt, usn, "ssdp:alive")
        }
    }

    private fun sendByebye(binding: Binding) {
        val uuid = udnProvider()
        val entries = advertiseEntries(uuid)
        for ((nt, usn) in entries) {
            sendNotify(binding, nt, usn, "ssdp:byebye")
        }
    }

    private fun advertiseEntries(uuid: String): List<Pair<String, String>> = listOf(
        ROOT_DEVICE to "$uuid::$ROOT_DEVICE",
        uuid to uuid,
        UpnpXml.DEVICE_TYPE to "$uuid::${UpnpXml.DEVICE_TYPE}",
        UpnpXml.SVC_AVTRANSPORT to "$uuid::${UpnpXml.SVC_AVTRANSPORT}",
        UpnpXml.SVC_RENDERING to "$uuid::${UpnpXml.SVC_RENDERING}",
        UpnpXml.SVC_CONNMGR to "$uuid::${UpnpXml.SVC_CONNMGR}"
    )

    private fun sendNotify(binding: Binding, nt: String, usn: String, nts: String) {
        val location = "http://${binding.lan.ip}:$httpPort/description.xml"
        val msg = StringBuilder()
        msg.append("NOTIFY * HTTP/1.1\r\n")
        msg.append("HOST: $MCAST_ADDR:$MCAST_PORT\r\n")
        msg.append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
        msg.append("LOCATION: $location\r\n")
        msg.append("NT: $nt\r\n")
        msg.append("NTS: $nts\r\n")
        msg.append("SERVER: ${serverProvider()}\r\n")
        msg.append("USN: $usn\r\n")
        msg.append("BOOTID.UPNP.ORG: $BOOTID\r\n")
        msg.append("CONFIGID.UPNP.ORG: $CONFIGID\r\n")
        msg.append("\r\n")
        try {
            val data = msg.toString().toByteArray(Charsets.UTF_8)
            binding.socket.networkInterface = binding.lan.networkInterface
            binding.socket.send(DatagramPacket(data, data.size, group, MCAST_PORT))
            try { SsdpDiagnostics.logNotify(nts, nt, binding.lan.ip) } catch (_: Throwable) {}
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------
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

    private fun httpDate(): String = httpDateFormat.format(java.util.Date())
}
