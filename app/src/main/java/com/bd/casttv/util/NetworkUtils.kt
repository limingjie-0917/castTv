package com.bd.casttv.util

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Helpers for LAN discovery: local IPv4 lookup + a stable, persisted device UDN.
 */
object NetworkUtils {

    private const val PREFS = "casttv_network"
    private const val KEY_UDN = "device_udn"

    /** IPv4 LAN interface suitable for DLNA/SSDP advertisement. */
    data class LanInterface(
        val name: String,
        val ip: String,
        val networkInterface: NetworkInterface
    )

    /**
     * Enumerates LAN IPv4 interfaces that are suitable for DLNA/AirPlay discovery.
     *
     * v1.1.29: phones/tablets may expose Wi‑Fi with non-TV style interface names,
     * so do not rely on the old eth/wlan/en prefix whitelist only. Instead collect all
     * effective non-loopback IPv4 interfaces, then rank likely LAN/Wi‑Fi addresses
     * ahead of AP / P2P / generic interfaces. Cellular/VPN/docker style interfaces
     * remain excluded because their IPs are not reachable from peer devices on Wi‑Fi.
     */
    fun getLanInterfaces(): List<LanInterface> {
        val candidates = mutableListOf<LanInterface>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in interfaces) {
                if (!isUsableInterface(nif)) continue
                val name = nif.name.orEmpty()
                for (addr in nif.inetAddresses) {
                    if (!isUsableLanAddress(addr)) continue
                    val host = addr.hostAddress ?: continue
                    candidates.add(LanInterface(name.ifBlank { "unknown" }, host, nif))
                }
            }
        } catch (_: Exception) {
            // Ignore and return whatever has been collected.
        }
        return candidates
            .distinctBy { "${it.name}:${it.ip}" }
            .sortedWith(
                compareBy<LanInterface> { interfaceRank(it.name) }
                    .thenBy { if (isPrivateLanIp(it.ip)) 0 else 1 }
                    .thenBy { it.name }
                    .thenBy { it.ip }
            )
    }

    private fun isUsableInterface(nif: NetworkInterface): Boolean {
        if (!nif.isUp || nif.isLoopback || nif.isVirtual) return false
        val name = nif.name?.lowercase().orEmpty()
        if (name.isBlank()) return false
        // Exclude interfaces that cannot be reached by another device on the same Wi‑Fi.
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") ||
            name.startsWith("ppp") || name.startsWith("dummy") || name.startsWith("lo") ||
            name.contains("docker") || name.contains("veth") || name.contains("bridge") ||
            name.contains("virbr")
        ) return false
        return true
    }

    private fun isUsableLanAddress(addr: java.net.InetAddress): Boolean {
        if (addr !is Inet4Address) return false
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) return false
        val host = addr.hostAddress ?: return false
        if (host.startsWith("127.") || host.startsWith("169.254.") || host == "0.0.0.0") return false
        return true
    }

    private fun isPrivateLanIp(ip: String): Boolean =
        ip.startsWith("192.168.") || ip.startsWith("10.") ||
            ip.substringBefore('.', "").toIntOrNull()?.let { first ->
                first == 172 && ip.split('.').getOrNull(1)?.toIntOrNull()?.let { it in 16..31 } == true
            } == true

    private fun interfaceRank(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.startsWith("eth") -> 0
            lower.startsWith("wlan") || lower.startsWith("wifi") || lower.contains("wifi") -> 1
            lower.startsWith("en") -> 2
            lower.startsWith("ap") || lower.contains("softap") -> 3
            lower.startsWith("p2p") || lower.contains("p2p") -> 4
            // Non-standard tablet Wi‑Fi names land here instead of being filtered out.
            else -> 5
        }
    }

    /**
     * Returns the primary LAN IPv4 address of this device.
     *
     * Kept for UI display and legacy callers. SSDP should use [getLanInterfaces]
     * directly so every usable interface can advertise its own LOCATION URL.
     */
    fun getLocalIpAddress(): String? = getLanInterfaces().firstOrNull()?.ip

    /**
     * Returns a stable device UDN in the form "uuid:<uuid>". The UUID is
     * generated once on first use and persisted, so the device keeps the same
     * identity across restarts (important: control points cache USN/UDN).
     */
    fun getDeviceUdn(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var udn = prefs.getString(KEY_UDN, null)
        if (udn.isNullOrEmpty()) {
            udn = "uuid:" + UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UDN, udn).apply()
        }
        return udn
    }

    /** 强制重置 UDN，返回新值。用于抖音投屏开关/名称组切换时刷新设备身份。 */
    fun resetDeviceUdn(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val udn = "uuid:" + UUID.randomUUID().toString()
        prefs.edit().putString(KEY_UDN, udn).apply()
        return udn
    }

    /** Bare UUID (without the leading "uuid:") — handy for USN/ST matching. */
    fun getRawUuid(context: Context): String =
        getDeviceUdn(context).removePrefix("uuid:")
}
