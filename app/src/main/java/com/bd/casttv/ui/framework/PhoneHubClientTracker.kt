package com.bd.casttv.ui.framework

/**
 * 「已连接设备」轻量客户端跟踪器。
 *
 * NanoHTTPD 无法直接列出所有客户端，我们在 PhoneHubServer.serve() 顶端登记一次命中，
 * 由本对象保留最近一次访问时间/UA。默认 3 分钟无请求则视为断开。
 * 完全内存态，进程重启后清空。
 */
object PhoneHubClientTracker {

    data class Client(
        val ip: String,
        val userAgent: String,
        val firstSeen: Long,
        var lastSeen: Long,
        var requestCount: Int
    )

    private const val ACTIVE_WINDOW_MS = 3 * 60 * 1000L

    private val clients = linkedMapOf<String, Client>()
    private val listeners = mutableSetOf<Listener>()

    interface Listener { fun onClientsChanged() }
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    @Synchronized
    fun recordHit(ip: String?, userAgent: String?) {
        if (ip.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val existing = clients[ip]
        if (existing == null) {
            clients[ip] = Client(ip, userAgent.orEmpty(), now, now, 1)
        } else {
            existing.lastSeen = now
            existing.requestCount += 1
            if (existing.userAgent.isBlank() && !userAgent.isNullOrBlank()) {
                clients[ip] = existing.copy(userAgent = userAgent)
            }
        }
        notifyChanged()
    }

    @Synchronized
    fun disconnect(ip: String) {
        if (clients.remove(ip) != null) notifyChanged()
    }

    @Synchronized
    fun clear() {
        if (clients.isNotEmpty()) {
            clients.clear()
            notifyChanged()
        }
    }

    /** 返回当前所有已知客户端；调用方按需在 UI 过滤活跃/离线状态。 */
    @Synchronized
    fun snapshot(): List<Client> = clients.values.map { it.copy() }.sortedByDescending { it.lastSeen }

    fun isActive(client: Client, now: Long = System.currentTimeMillis()): Boolean =
        now - client.lastSeen <= ACTIVE_WINDOW_MS

    private fun notifyChanged() {
        listeners.toList().forEach { runCatching { it.onClientsChanged() } }
    }
}
