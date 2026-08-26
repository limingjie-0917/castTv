package com.bd.casttv.dlna

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import com.bd.casttv.CastApp
import com.bd.casttv.R
import com.bd.casttv.settings.Settings
import com.bd.casttv.ui.MainActivity
import com.bd.casttv.util.LocalCrashLog
import com.bd.casttv.util.NetworkMonitor
import com.bd.casttv.util.NetworkUtils
import java.util.concurrent.Executors

/**
 * Foreground service hosting the DLNA renderer core: the NanoHTTPD server and
 * the SSDP discovery service. Holds a MulticastLock + WifiLock so multicast
 * discovery keeps working while the screen is off.
 *
 * It also observes [PlaybackController] and, when a cast arrives while the app
 * is NOT in the foreground, force-brings-up the player: a plain
 * [startActivity] is silently blocked on Android 10+ from a background/service
 * context, so we additionally post a high-priority notification carrying a
 * `setFullScreenIntent`, which the system honours from background / lockscreen.
 */
class DlnaRendererService : LifecycleService() {

    companion object {
        private const val TAG = "DlnaRendererService"
        const val HTTP_PORT = 8895
        private const val NOTIF_ID = 0x0CA5
        private const val CAST_NOTIF_ID = 0x0CA6
        const val ACTION_RESTART_IDENTITY = "com.bd.casttv.action.RESTART_IDENTITY"

        /**
         * 轻量运行态标记：供首页「服务状态」面板读取，判断 DLNA 是否已在跑。
         * onCreate 置 true、onDestroy 置 false，不影响 Service 自身逻辑。
         */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var httpServer: DlnaHttpServer? = null
    private var ssdp: SsdpService? = null
    private var genaEventManager: GenaEventManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var settings: Settings
    private var networkMonitor: NetworkMonitor? = null
    @Volatile private var lastBoundSignature: String? = null
    @Volatile private var advertisedIdentity: DeviceIdentity? = null
    @Volatile private var advertisedUdn: String? = null
    @Volatile private var pendingIdentityRestart: Boolean = false
    @Volatile private var pendingRebind: Boolean = false
    @Volatile private var rebindRunning: Boolean = false
    @Volatile private var destroyed: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rebindLock = Any()
    private val dlnaNetworkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "dlna-network-rebind").apply { isDaemon = true }
    }

    private fun logD(message: String) {
        Log.d(TAG, message)
        LocalCrashLog.d(this, TAG, message)
    }

    private fun logE(message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
        LocalCrashLog.e(this, TAG, message, throwable)
    }

    /** Force-launches the player when a cast arrives and the UI is backgrounded. */
    private val castObserver = object : PlaybackController.StateObserver {
        override fun onNewCastRequest(uri: String, title: String, sourceHint: String) {
            // A fresh cast request means the control point is actively using us;
            // send an immediate alive to bridge Wi-Fi multicast loss between
            // periodic advertisements.
            try { ssdp?.advertiseAliveNow() } catch (_: Throwable) {}
            // When an existing PlayerActivity has registered command callback,
            // SetAVTransportURI is already delivered directly to that instance.
            // Do not route through MainActivity again, otherwise TV exits player
            // and re-enters instead of switching in-place.
            if (PlaybackController.hasActiveCommandCallback()) return
            // When MainActivity is in the foreground it already handles this
            // (including the optional password dialog); avoid a double launch.
            if (PlaybackController.uiInForeground) return
            bringUpPlayback(title, sourceHint)
        }

        override fun onTransportStateChanged(state: PlaybackController.TransportState) {
            // 不在每次状态变化时发送 SSDP alive burst。
            // 原实现每次 PLAYING/PAUSED/TRANSITIONING 都调用 advertiseAliveNow()，
            // 导致抖音收到重复 NOTIFY 后重新拉取 description.xml，刷屏日志并可能触发重连。
            // SSDP 周期广播（ALIVE_INTERVAL_MS=30s）已足够维持设备在线状态。
            if (state == PlaybackController.TransportState.NO_MEDIA_PRESENT ||
                state == PlaybackController.TransportState.STOPPED
            ) {
                applyPendingIdentityRestartIfNeeded()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        logD("onCreate: begin")
        destroyed = false
        isRunning = true
        settings = Settings(this)
        acquireLocks()
        // 抖音投屏播放记录：把阈值同步到 PlaybackController，并注册命中回调写入独立 store。
        PlaybackController.douyinHistoryThresholdMs = settings.douyinHistoryThresholdSec * 1000L
        PlaybackController.douyinThresholdListener = PlaybackController.DouyinThresholdListener { uri, positionMs, durationMs ->
            if (!PlaybackController.currentIsDouyinCast) return@DouyinThresholdListener
            try {
                val item = com.bd.casttv.douyin.DouyinCastHistoryStore.Item(
                    uri = uri,
                    title = PlaybackController.currentTitle,
                    sourceHint = PlaybackController.sourceHint,
                    artworkUrl = try { PlaybackController.currentArtworkUrl() ?: "" } catch (_: Throwable) { "" },
                    artworkPath = try { PlaybackController.currentArtworkPath() ?: "" } catch (_: Throwable) { "" },
                    firstPlayedAt = System.currentTimeMillis(),
                    lastPositionMs = positionMs,
                    durationMs = durationMs,
                    playedSec = (positionMs / 1000L),
                    isDouyinCast = true
                )
                com.bd.casttv.douyin.DouyinCastHistoryStore.add(this, item)
            } catch (t: Throwable) {
                logE("write DouyinCastHistoryStore failed", t)
            }
        }
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            logE("startForeground failed", t)
        }
        try {
            PlaybackController.registerObserver(castObserver)
        } catch (t: Throwable) {
            logE("registerObserver failed", t)
        }
        try {
            startRenderer()
        } catch (t: Throwable) {
            logE("startRenderer failed", t)
        }
        startNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        logD("onStartCommand: flags=$flags startId=$startId action=${intent?.action} deviceName=${settings.deviceName} dlnaIdentity=${settings.currentDlnaIdentity()}")
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            logE("refresh foreground notification failed", t)
        }
        if (intent?.action == ACTION_RESTART_IDENTITY) {
            try {
                restartForIdentityChange()
            } catch (t: Throwable) {
                logE("restartForIdentityChange failed", t)
            }
        }
        return START_STICKY
    }

    private fun startRenderer() {
        val identity = advertisedIdentity ?: settings.currentDlnaIdentity().also { advertisedIdentity = it }
        val udn = advertisedUdn ?: NetworkUtils.getDeviceUdn(this).also { advertisedUdn = it }
        val gena = genaEventManager ?: GenaEventManager().also {
            genaEventManager = it
            PlaybackController.attachGenaEventManager(it)
        }
        logD("startRenderer: deviceName=${settings.deviceName} dlnaIdentity=$identity udn=$udn")
        try {
            val server = DlnaHttpServer(
                port = HTTP_PORT,
                identityProvider = { advertisedIdentity ?: identity },
                udnProvider = { advertisedUdn ?: udn },
                controller = PlaybackController,
                genaEventManager = gena
            )
            server.start(NanoTimeout.SOCKET_READ_TIMEOUT_MS, false)
            httpServer = server
            Log.i(TAG, "HTTP server started on $HTTP_PORT")
            LocalCrashLog.d(this, TAG, "HTTP server started on $HTTP_PORT")
            SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.INFO, "http_start_success", "HTTP server started on $HTTP_PORT")
        } catch (e: Exception) {
            SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "http_start_failed", "HTTP server start failed", e)
            logE("Failed to start HTTP server", e)
        }

        try {
            val interfaces = NetworkUtils.getLanInterfaces()
            val ssdpIdentityRef: () -> DeviceIdentity = { advertisedIdentity ?: identity }
            val ssdpService = SsdpService(
                interfaceProvider = { interfaces },
                httpPort = HTTP_PORT,
                udnProvider = { advertisedUdn ?: udn },
                serverProvider = { ssdpIdentityRef().ssdpServer.ifBlank { DeviceIdentity.DEFAULT_SSDP_SERVER } }
            )
            ssdpService.start()
            ssdpService.advertiseAliveNow()
            ssdp = ssdpService
            lastBoundSignature = lanSignature(interfaces)
            SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.INFO, "ssdp_start_success", "interfaces=${interfaces.size}")
        } catch (t: Throwable) {
            SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "ssdp_start_failed", "SSDP service start failed", t)
            logE("Failed to start SSDP service", t)
            ssdp = null
        }
    }

    /**
     * 当抖音投屏开关或设备名称组发生变化时调用。
     *
     * 已建立投屏连接时，不立刻发送 ssdp:byebye、不重置 UDN，也不重启 HTTP 服务；
     * 否则抖音端会把当前设备判定为下线/新设备，从而重新拉取 /description.xml 并触发断线重连。
     * 设置值先保存到 SharedPreferences，待当前投屏结束后再统一切换对外身份。
     */
    @Synchronized
    private fun restartForIdentityChange() {
        val newIdentity = settings.currentDlnaIdentity()
        logD("restartForIdentityChange: begin, new identity=$newIdentity")
        if (isCastSessionActive()) {
            pendingIdentityRestart = true
            try { ssdp?.advertiseAliveNow() } catch (_: Exception) {}
            logD("restartForIdentityChange: active cast session, defer byebye/UDN reset/HTTP restart")
            try { startForeground(NOTIF_ID, buildNotification()) } catch (_: Throwable) {}
            return
        }
        applyIdentityRestartNow(newIdentity)
    }

    private fun isCastSessionActive(): Boolean {
        if (PlaybackController.currentUri.isBlank()) return false
        return when (PlaybackController.transportState) {
            PlaybackController.TransportState.PLAYING,
            PlaybackController.TransportState.PAUSED_PLAYBACK,
            PlaybackController.TransportState.TRANSITIONING -> true
            // 抖音自动连播：视频播完上报 STOPPED 后，currentUri 仍在、等待下一条 URI。
            // 此时不应判定为会话结束，否则会触发身份重启/服务重建，打断自动连播。
            PlaybackController.TransportState.STOPPED ->
                PlaybackController.currentIsDouyinCast
            PlaybackController.TransportState.NO_MEDIA_PRESENT -> false
        }
    }

    @Synchronized
    private fun applyPendingIdentityRestartIfNeeded() {
        if (!pendingIdentityRestart || isCastSessionActive()) return
        applyIdentityRestartNow(settings.currentDlnaIdentity())
    }

    private fun applyIdentityRestartNow(newIdentity: DeviceIdentity) {
        pendingIdentityRestart = false
        try { ssdp?.stop(sendByebye = true) } catch (_: Exception) {}
        ssdp = null
        try { httpServer?.stop() } catch (_: Exception) {}
        httpServer = null
        lastBoundSignature = null
        advertisedIdentity = newIdentity
        advertisedUdn = try { NetworkUtils.resetDeviceUdn(this) } catch (_: Exception) { null }
        try {
            startRenderer()
        } catch (t: Throwable) {
            logE("restartForIdentityChange: startRenderer failed", t)
        }
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {}
        logD("restartForIdentityChange: done, current identity=$newIdentity udn=$advertisedUdn")
    }

    /**
     * (Re)binds the SSDP discovery listeners to the current LAN interface set.
     *
     * Called when connectivity becomes available or changes. This is what makes
     * boot auto-start work: at boot the network is not ready yet, so the initial
     * [startRenderer] leaves SSDP unstarted (no LAN interface). Once the network
     * is up we start it here; if any interface is added, removed, or gets a new
     * IP address we rebind all SSDP sockets.
     */
    @Synchronized
    private fun restartSsdpForNetwork() {
        val identity = advertisedIdentity ?: settings.currentDlnaIdentity().also { advertisedIdentity = it }
        val udn = advertisedUdn ?: NetworkUtils.getDeviceUdn(this).also { advertisedUdn = it }
        val interfaces = NetworkUtils.getLanInterfaces()
        if (interfaces.isEmpty()) {
            logD("restartSsdpForNetwork: no LAN interface, stopping SSDP without byebye to avoid transient offline on network jitter")
            SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.WARN, "network_no_interface", "No available LAN interface during SSDP rebind")
            try { ssdp?.stop(sendByebye = false) } catch (_: Exception) {}
            ssdp = null
            lastBoundSignature = null
            return
        }
        val signature = lanSignature(interfaces)
        // Ensure the HTTP server (device/service descriptions) is alive; it binds
        // to 0.0.0.0 so it does not depend on a specific interface, but it may
        // have failed to start earlier for other reasons.
        if (httpServer == null) {
            try {
                val server = DlnaHttpServer(
                    port = HTTP_PORT,
                    identityProvider = { advertisedIdentity ?: identity },
                    udnProvider = { advertisedUdn ?: udn },
                    controller = PlaybackController,
                    genaEventManager = genaEventManager
                )
                server.start(NanoTimeout.SOCKET_READ_TIMEOUT_MS, false)
                httpServer = server
                logD("restartSsdpForNetwork: HTTP server (re)started on $HTTP_PORT")
                SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.INFO, "http_recover_success", "HTTP server fallback started on $HTTP_PORT")
            } catch (t: Throwable) {
                SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "http_recover_failed", "HTTP fallback start failed", t)
                logE("restartSsdpForNetwork: HTTP server start failed", t)
            }
        }
        if (ssdp != null && signature == lastBoundSignature) {
            logD("restartSsdpForNetwork: SSDP already bound to $signature, skip")
            return
        }
        logD("restartSsdpForNetwork: (re)binding SSDP to $signature (was $lastBoundSignature) without ssdp:byebye")
        // Network capability callbacks can fire during Douyin video switching. Sending
        // ssdp:byebye on a transient rebind makes the control point mark us offline,
        // so only close old sockets here and let the new service immediately alive-burst.
        try { ssdp?.stop(sendByebye = false) } catch (_: Exception) {}
        ssdp = null
        try {
            val ssdpIdentityRef: () -> DeviceIdentity = { advertisedIdentity ?: identity }
            val ssdpService = SsdpService(
                interfaceProvider = { interfaces },
                httpPort = HTTP_PORT,
                udnProvider = { udn },
                serverProvider = { ssdpIdentityRef().ssdpServer.ifBlank { DeviceIdentity.DEFAULT_SSDP_SERVER } }
            )
            ssdpService.start()
            ssdpService.advertiseAliveNow()
            ssdp = ssdpService
            lastBoundSignature = signature
            SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.INFO, "ssdp_rebind_success", "signature=$signature")
        } catch (t: Throwable) {
            SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "ssdp_rebind_failed", "SSDP rebind failed", t)
            logE("restartSsdpForNetwork: SSDP restart failed", t)
            ssdp = null
        }
    }

    private fun lanSignature(interfaces: List<NetworkUtils.LanInterface>): String =
        interfaces.map { "${it.name}:${it.ip}" }.sorted().joinToString("|")

    private fun requestNetworkRebind(reason: String) {
        // 投屏会话活跃时跳过 SSDP rebind：网络抖动（即使瞬间恢复）会触发 socket 重建，
        // 期间抖音如果正好在搜索会丢失设备响应，可能导致连接中断。
        // SSDP 周期广播（30s）足以在网络稳定后恢复设备发现。
        if (isCastSessionActive()) {
            logD("requestNetworkRebind: skipped, cast session active (reason=$reason)")
            return
        }
        synchronized(rebindLock) {
            if (rebindRunning) {
                pendingRebind = true
                logD("requestNetworkRebind: merge latest request reason=$reason")
                return
            }
            rebindRunning = true
            pendingRebind = false
        }
        try {
            dlnaNetworkExecutor.execute {
                var again: Boolean
                do {
                    again = false
                    try {
                        if (!destroyed) restartSsdpForNetwork()
                    } catch (t: Throwable) {
                        logE("network rebind task failed", t)
                        SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "network_rebind_task_failed", "reason=$reason", t)
                    } finally {
                        synchronized(rebindLock) {
                            again = pendingRebind && !destroyed
                            pendingRebind = false
                            if (!again) rebindRunning = false
                        }
                    }
                } while (again)
            }
        } catch (t: Throwable) {
            synchronized(rebindLock) {
                rebindRunning = false
                pendingRebind = false
            }
            SsdpDiagnostics.logServiceHealth("DLNA", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "network_rebind_submit_failed", "reason=$reason", t)
            logE("requestNetworkRebind submit failed", t)
        }
    }

    private fun startNetworkMonitor() {
        if (networkMonitor != null) return
        networkMonitor = NetworkMonitor(
            context = this,
            onAvailable = {
                mainHandler.post { requestNetworkRebind("network_available") }
            },
            onLost = {
                logD("network lost; keeping SSDP, will rebind when a network returns")
            }
        ).also { it.start() }
    }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("casttv:multicast").apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) {}
        }
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "casttv:wifi").apply {
            setReferenceCounted(false)
            try { acquire() } catch (_: Exception) {}
        }
    }

    private fun releaseLocks() {
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        multicastLock = null
        wifiLock = null
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, com.bd.casttv.ui.framework.NewMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, intent, flags)

        return NotificationCompat.Builder(this, CastApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, settings.dlnaDeviceName))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Brings the player to the foreground for a cast that arrived while the app
     * was backgrounded. Routes through [MainActivity] (singleTask) with a
     * cast-pending marker so the existing password / launch logic still applies;
     * MainActivity reads the latest target from [PlaybackController].
     *
     * Two mechanisms are used together for reliability:
     *  1. A direct [startActivity] (works pre-Android 10 and when the app still
     *     has a background-start exemption).
     *  2. A high-priority notification with a full-screen intent, which the
     *     system honours from background / lockscreen when (1) is blocked.
     */
    private fun bringUpPlayback(title: String, sourceHint: String) {
        val launch = Intent(this, com.bd.casttv.ui.framework.NewMainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(com.bd.casttv.ui.framework.NewMainActivity.EXTRA_CAST_PENDING, true)
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 1, launch, piFlags)

        // (1) Best-effort direct launch.
        try { startActivity(launch) } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity blocked; relying on full-screen intent", e)
        }

        // (2) Full-screen-intent notification fallback.
        val label = title.ifBlank { sourceHint.ifBlank { getString(R.string.standby_ready) } }
        val notif = NotificationCompat.Builder(this, CastApp.CAST_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_cast_title))
            .setContentText(getString(R.string.notif_cast_text, label))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(CAST_NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post cast full-screen notification", e)
        }
    }

    override fun onDestroy() {
        logD("onDestroy: unregister observer and stop renderer services")
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        synchronized(rebindLock) {
            pendingRebind = false
            rebindRunning = false
        }
        try { dlnaNetworkExecutor.shutdownNow() } catch (_: Throwable) {}
        PlaybackController.unregisterObserver(castObserver)
        try { networkMonitor?.stop() } catch (_: Exception) {}
        networkMonitor = null
        try { ssdp?.stop() } catch (_: Exception) {}
        try { httpServer?.stop() } catch (_: Exception) {}
        try { genaEventManager?.shutdown() } catch (_: Exception) {}
        PlaybackController.attachGenaEventManager(null)
        ssdp = null
        httpServer = null
        genaEventManager = null
        releaseLocks()
        isRunning = false
        super.onDestroy()
    }

    /** Socket read timeout constants for NanoHTTPD. */
    private object NanoTimeout {
        const val SOCKET_READ_TIMEOUT_MS = 15_000
    }
}
