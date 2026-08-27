package com.bd.casttv.airplay

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.bd.casttv.CastApp
import com.bd.casttv.R
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.settings.Settings
import com.bd.casttv.util.LocalCrashLog
import com.bd.casttv.util.NetworkMonitor
import com.bd.casttv.dlna.SsdpDiagnostics
import java.util.concurrent.Executors
import com.github.serezhka.jap2server.AirPlayServer

/**
 * Foreground service hosting the AirPlay 1 mirroring receiver. Runs entirely in
 * parallel with [com.bd.casttv.dlna.DlnaRendererService] (they use different
 * ports and their own multicast/wifi locks, so neither interferes with the
 * other).
 *
 * Responsibilities:
 *  - Advertise `_airplay._tcp` / `_raop._tcp` over mDNS/Bonjour with the same
 *    friendly device name as DLNA ("小新的TV" by default), so it shows up in the
 *    iPhone/iPad Control-Center "屏幕镜像" list.
 *  - Run the Netty-based AirPlay control/mirroring servers via [AirPlayServer].
 *  - Bridge decoded H.264/audio to [AirPlayController], and force-launch the
 *    reused [PlayerActivity] (mirror mode) when a mirroring session begins.
 */
class AirPlayService : LifecycleService(), AirPlayController.Listener {

    companion object {
        private const val TAG = "AirPlayService"
        private const val NOTIF_ID = 0x0CA7

        /** Port advertised for `_airplay._tcp`; also the mirror data channel. */
        const val AIRPLAY_PORT = 7000
        /** RTSP control port, advertised for `_raop._tcp`. */
        const val AIRTUNES_PORT = 49152

        /**
         * 轻量运行态标记：供首页「服务状态」面板读取，判断 AirPlay 是否已在跑。
         * onCreate 置 true、onDestroy 置 false，不影响 Service 自身逻辑。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var temporarilyDisabled: Boolean = false

        fun isTemporarilyDisabled(): Boolean = temporarilyDisabled

        fun start(context: Context) {
            val intent = Intent(context, AirPlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AirPlayService::class.java))
        }
    }

    private var airPlayServer: AirPlayServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var serverThread: Thread? = null
    @Volatile private var deviceName: String = Settings.DEFAULT_DEVICE_NAME
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var restartInProgress = false
    @Volatile private var startInProgress = false
    @Volatile private var airPlayTemporarilyDisabled = false
    @Volatile private var startFailureCount = 0
    @Volatile private var pendingRebind = false
    @Volatile private var rebindRunning = false
    @Volatile private var destroyed = false
    private val rebindLock = Any()
    private val airPlayNetworkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "airplay-network-rebind").apply { isDaemon = true }
    }
    private var pendingStartRetryRunnable: Runnable? = null
    private var networkMonitor: NetworkMonitor? = null

    private fun logD(message: String) {
        Log.d(TAG, message)
        LocalCrashLog.d(this, TAG, message)
    }

    private fun logE(message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
        LocalCrashLog.e(this, TAG, message, throwable)
    }

    private fun disableAirPlayTemporarily(reason: String, throwable: Throwable? = null) {
        airPlayTemporarilyDisabled = true
        temporarilyDisabled = true
        SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "temporarily_disabled", reason, throwable)
        val wrapped = throwable ?: IllegalStateException(reason)
        logE("disableAirPlayTemporarily: $reason", wrapped)
        try {
            stopAirPlayServer()
        } catch (_: Throwable) {
        }
    }

    private fun scheduleStartRetry(delayMs: Long, reason: String) {
        if (airPlayTemporarilyDisabled) return
        pendingStartRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            if (airPlayTemporarilyDisabled) return@Runnable
            try {
                logD("scheduleStartRetry.run: reason=$reason delayMs=$delayMs failureCount=$startFailureCount")
                submitAirPlayNetworkTask("start_retry_$reason", forceRestart = false, resetDisable = false)
            } catch (t: Throwable) {
                logE("scheduleStartRetry.submit failed", t)
            }
        }
        pendingStartRetryRunnable = task
        mainHandler.postDelayed(task, delayMs)
    }

    private val restartRunnable = Runnable {
        submitAirPlayNetworkTask("scheduled_restart", forceRestart = true, resetDisable = false)
    }

    override fun onCreate() {
        super.onCreate()
        logD("onCreate: begin")
        destroyed = false
        isRunning = true
        if (airPlayTemporarilyDisabled) {
            logD("onCreate: AirPlay temporarily disabled, skip startup")
            return
        }
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
            logE("startForeground failed", t)
        }
        acquireLocks()
        AirPlayController.addListener(this)
        deviceName = try {
            Settings.normalizeDeviceName(Settings(this).deviceName)
        } catch (t: Throwable) {
            logE("read deviceName failed in onCreate", t)
            Settings.DEFAULT_DEVICE_NAME
        }
        try {
            startAirPlayServer()
        } catch (t: Throwable) {
            logE("startAirPlayServer failed in onCreate", t)
        }
        startNetworkMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        logD("onStartCommand: flags=$flags startId=$startId currentDeviceName=$deviceName")
        if (airPlayTemporarilyDisabled) {
            logD("onStartCommand: skipped because AirPlay temporarily disabled")
            return START_STICKY
        }
        val latest = try {
            Settings.normalizeDeviceName(Settings(this).deviceName)
        } catch (t: Throwable) {
            logE("read deviceName failed in onStartCommand", t)
            Settings.normalizeDeviceName(deviceName)
        }
        if ((latest != deviceName || airPlayServer == null) && !restartInProgress && !startInProgress) {
            deviceName = latest
            scheduleRestartAirPlayServer()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ------------------------------------------------------------------
    // AirPlay server lifecycle
    // ------------------------------------------------------------------

    private fun startAirPlayServer() {
        if (airPlayTemporarilyDisabled) {
            logD("startAirPlayServer: skipped because AirPlay temporarily disabled")
            return
        }
        if (airPlayServer != null || startInProgress) return
        startInProgress = true
        val safeName = try {
            Settings.normalizeDeviceName(deviceName)
        } catch (t: Throwable) {
            logE("startAirPlayServer: invalid deviceName", t)
            Settings.DEFAULT_DEVICE_NAME
        }
        val thread = Thread({
            try {
                logD("AirPlay server thread: starting name=$safeName ports=$AIRPLAY_PORT/$AIRTUNES_PORT")
                val consumer = try {
                    CastAirplayConsumer()
                } catch (t: Throwable) {
                    SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "consumer_create_failed", "create CastAirplayConsumer failed", t)
                    disableAirPlayTemporarily("create CastAirplayConsumer failed", t)
                    return@Thread
                }
                val server = try {
                    AirPlayServer(safeName, AIRPLAY_PORT, AIRTUNES_PORT, consumer)
                } catch (t: Throwable) {
                    startFailureCount += 1
                    SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "server_construct_failed", "count=$startFailureCount", t)
                    logE("construct AirPlayServer failed count=$startFailureCount", t)
                    airPlayServer = null
                    if (startFailureCount >= 3) disableAirPlayTemporarily("construct AirPlayServer failed too many times", t)
                    else scheduleStartRetry(1500L, "construct_failed")
                    return@Thread
                }
                airPlayServer = server
                try {
                    // 设置 mDNS 注册回调，写入诊断日志（方便定位苹果设备搜索不到的问题）
                    server.setBonjourListener(object : com.github.serezhka.jap2lib.AirPlayBonjour.BonjourListener {
                        override fun onRegistered(serverName: String?, airPlayPort: Int, airTunesPort: Int, networkInfo: String?) {
                            val name = serverName ?: "(unknown)"
                            val net = networkInfo ?: "(unknown)"
                            SsdpDiagnostics.logCastEvent(
                                SsdpDiagnostics.CastEvent.Kind.AIRPLAY_MDNS_REGISTER,
                                "mDNS 注册成功：$name，_airplay._tcp=$airPlayPort，_raop._tcp=$airTunesPort，网络接口=$net"
                            )
                        }
                        override fun onRegisterFailed(reason: String?, error: Throwable?) {
                            val msg = reason ?: "unknown"
                            SsdpDiagnostics.logCastEvent(
                                SsdpDiagnostics.CastEvent.Kind.AIRPLAY_MDNS_FAILED,
                                "mDNS 注册失败：$msg（苹果设备将搜索不到本机）"
                            )
                        }
                    })
                    server.start()
                    startFailureCount = 0
                    temporarilyDisabled = false
                    SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.INFO, "server_start_success", "ports=$AIRPLAY_PORT/$AIRTUNES_PORT")
                    logD("AirPlay server thread: started successfully")
                } catch (t: Throwable) {
                    startFailureCount += 1
                    SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "server_start_failed", "count=$startFailureCount", t)
                    logE("AirPlayServer.start failed count=$startFailureCount", t)
                    try {
                        server.stop()
                    } catch (stopErr: Throwable) {
                        logE("AirPlayServer.stop after start failure failed", stopErr)
                    }
                    airPlayServer = null
                    if (startFailureCount >= 3) disableAirPlayTemporarily("AirPlayServer.start failed too many times", t)
                    else scheduleStartRetry(1500L, "start_failed")
                }
            } catch (t: Throwable) {
                SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "server_thread_crash", "unexpected AirPlay server thread crash", t)
                disableAirPlayTemporarily("unexpected AirPlay server thread crash", t)
            } finally {
                startInProgress = false
            }
        }, "AirPlayServerThread")
        thread.isDaemon = true
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { th, err ->
            SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "server_thread_uncaught", "thread=${th.name}", err)
            disableAirPlayTemporarily("uncaught exception in ${th.name}", err)
        }
        serverThread = thread
        thread.start()
    }

    private fun scheduleRestartAirPlayServer() {
        if (airPlayTemporarilyDisabled) return
        logD("scheduleRestartAirPlayServer: deviceName=$deviceName restartInProgress=$restartInProgress")
        mainHandler.removeCallbacks(restartRunnable)
        mainHandler.postDelayed(restartRunnable, 350L)
    }

    private fun restartAirPlayServerInternal() {
        if (airPlayTemporarilyDisabled) return
        logD("restartAirPlayServerInternal: stop current server then delayed start")
        stopAirPlayServer()
        // 给底层 server/socket 一点释放时间，降低快速 stop/start 竞态概率。
        mainHandler.postDelayed({
            try {
                startAirPlayServer()
            } catch (t: Throwable) {
                logE("delayed startAirPlayServer failed", t)
            } finally {
                restartInProgress = false
            }
        }, 150L)
    }

    private fun stopAirPlayServer() {
        pendingStartRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStartRetryRunnable = null
        try {
            airPlayServer?.stop()
        } catch (e: Exception) {
            logE("error stopping AirPlay server", e)
        }
        airPlayServer = null
        serverThread = null
    }

    // ------------------------------------------------------------------
    // AirPlayController.Listener — mirror session lifecycle (main thread)
    // ------------------------------------------------------------------

    override fun onMirrorStart() {
        logD("mirror session started -> launching player")
        try {
            bringUpMirrorPlayer()
        } catch (t: Throwable) {
            logE("bringUpMirrorPlayer failed", t)
        }
    }

    override fun onMirrorSize(width: Int, height: Int) {
        // PlayerActivity observes AirPlayController directly for resize.
    }

    override fun onMirrorStop() {
        // PlayerActivity finishes itself; nothing required here.
    }

    /**
     * Force-launch [PlayerActivity] in mirror mode. Mirrors the DLNA renderer's
     * two-pronged approach: a direct [startActivity] plus a full-screen-intent
     * notification that fires even from the background / lock-screen.
     */
    private fun bringUpMirrorPlayer() {
        val launch = try {
            Intent(this, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(PlayerActivity.EXTRA_AIRPLAY_MIRROR, true)
                putExtra(PlayerActivity.EXTRA_SOURCE, getString(R.string.airplay_mirror_source))
            }
        } catch (t: Throwable) {
            logE("build mirror launch intent failed", t)
            return
        }
        try {
            startActivity(launch)
        } catch (e: Exception) {
            logE("direct startActivity blocked; using full-screen intent", e)
        }

        val fsIntent = try {
            PendingIntent.getActivity(
                this, 0x0A11,
                Intent(this, PlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(PlayerActivity.EXTRA_AIRPLAY_MIRROR, true)
                    putExtra(PlayerActivity.EXTRA_SOURCE, getString(R.string.airplay_mirror_source))
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } catch (t: Throwable) {
            logE("build full-screen pending intent failed", t)
            return
        }
        val notif = NotificationCompat.Builder(this, CastApp.CAST_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.airplay_mirror_incoming))
            .setContentText(getString(R.string.airplay_mirror_source))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fsIntent, true)
            .setAutoCancel(true)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(0x0CA8, notif)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify blocked (missing POST_NOTIFICATIONS)", e)
        }
    }

    // ------------------------------------------------------------------
    // Wi-Fi / multicast locks (jmDNS needs multicast on many devices)
    // ------------------------------------------------------------------

    private fun acquireLocks() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi == null) {
                SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "acquireLocks_failed", "WifiManager unavailable")
                return
            }
            multicastLock = wifi.createMulticastLock("casttv-airplay-mcast").apply {
                setReferenceCounted(false)
                acquire()
            }
            val mcastHeld = multicastLock?.isHeld ?: false
            SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.INFO, "multicast_lock", "held=$mcastHeld")

            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "casttv-airplay-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wifiHeld = wifiLock?.isHeld ?: false
            SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.INFO, "wifi_lock", "held=$wifiHeld")

            if (!mcastHeld) {
                SsdpDiagnostics.logCastEvent(
                    SsdpDiagnostics.CastEvent.Kind.AIRPLAY_MDNS_FAILED,
                    "multicast lock 未获取成功，mDNS 广播包可能无法收发（苹果设备搜索不到）"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to acquire wifi/multicast locks", e)
            SsdpDiagnostics.logCastEvent(
                SsdpDiagnostics.CastEvent.Kind.AIRPLAY_MDNS_FAILED,
                "acquireLocks 异常：${e.message}（苹果设备将搜索不到本机）"
            )
        }
    }

    private fun releaseLocks() {
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null
        wifiLock = null
    }

    // ------------------------------------------------------------------
    // Network-availability driven (re)binding
    // ------------------------------------------------------------------

    private fun startNetworkMonitor() {
        if (networkMonitor != null) return
        networkMonitor = NetworkMonitor(
            context = this,
            onAvailable = { onNetworkAvailable() },
            onLost = { logD("network lost; AirPlay will rebind when a network returns") }
        ).also { it.start() }
    }

    /**
     * Called (on the main thread) when a usable network becomes available or
     * changes. Critical for boot auto-start: the service starts before Wi-Fi /
     * Ethernet is up, so the very first bind attempts fail and may permanently
     * disable AirPlay. Clear that state and (re)bind to the now-available
     * interface; also rebinds jmDNS when the IP changes on a Wi-Fi reconnect.
     */
    private fun onNetworkAvailable() {
        logD("onNetworkAvailable: submit AirPlay network rebind")
        mainHandler.post { submitAirPlayNetworkTask("network_available", forceRestart = true, resetDisable = true) }
    }

    private fun submitAirPlayNetworkTask(reason: String, forceRestart: Boolean, resetDisable: Boolean) {
        synchronized(rebindLock) {
            if (rebindRunning) {
                pendingRebind = true
                logD("submitAirPlayNetworkTask: merge latest request reason=$reason")
                return
            }
            rebindRunning = true
            pendingRebind = false
        }
        try {
            airPlayNetworkExecutor.execute {
                var again: Boolean
                do {
                    again = false
                    try {
                        if (!destroyed) {
                            restartInProgress = true
                            if (resetDisable) {
                                airPlayTemporarilyDisabled = false
                                temporarilyDisabled = false
                                startFailureCount = 0
                                pendingStartRetryRunnable?.let { mainHandler.removeCallbacks(it) }
                                pendingStartRetryRunnable = null
                            }
                            if (forceRestart || airPlayServer != null) {
                                stopAirPlayServer()
                                Thread.sleep(150L)
                            }
                            if (!airPlayTemporarilyDisabled && airPlayServer == null) startAirPlayServer()
                        }
                    } catch (t: Throwable) {
                        logE("AirPlay network task failed", t)
                        SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "network_rebind_failed", "reason=$reason", t)
                    } finally {
                        restartInProgress = false
                        synchronized(rebindLock) {
                            again = pendingRebind && !destroyed
                            pendingRebind = false
                            if (!again) rebindRunning = false
                        }
                    }
                } while (again)
            }
        } catch (t: Throwable) {
            restartInProgress = false
            synchronized(rebindLock) {
                rebindRunning = false
                pendingRebind = false
            }
            SsdpDiagnostics.logServiceHealth("AirPlay", SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, "network_submit_failed", "reason=$reason", t)
            logE("submitAirPlayNetworkTask failed", t)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0x0A12,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, PlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CastApp.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.airplay_status_running))
            .setContentText(getString(R.string.airplay_status_hint))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    override fun onDestroy() {
        logD("onDestroy: remove restart callbacks and stop server")
        destroyed = true
        mainHandler.removeCallbacks(restartRunnable)
        pendingStartRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingStartRetryRunnable = null
        synchronized(rebindLock) {
            pendingRebind = false
            rebindRunning = false
        }
        try { airPlayNetworkExecutor.shutdownNow() } catch (_: Throwable) {}
        try { networkMonitor?.stop() } catch (_: Exception) {}
        networkMonitor = null
        AirPlayController.removeListener(this)
        stopAirPlayServer()
        releaseLocks()
        isRunning = false
        super.onDestroy()
    }
}
