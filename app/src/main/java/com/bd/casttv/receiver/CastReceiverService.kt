package com.bd.casttv.receiver

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.bd.casttv.CastApp
import com.bd.casttv.R
import com.bd.casttv.airplay.AirPlayService
import com.bd.casttv.dlna.DlnaRendererService
import com.bd.casttv.dlna.SsdpDiagnostics
import com.bd.casttv.settings.Settings
import com.bd.casttv.ui.MainActivity

/**
 * Boot-time foreground orchestrator.
 *
 * When "开机自启动" is enabled, [BootReceiver] starts this service after boot so
 * the DLNA/AirPlay listeners come up quietly in the background without opening
 * the UI. Incoming cast requests are still responsible for bringing up
 * [MainActivity] via the existing renderer-service logic.
 */
class CastReceiverService : LifecycleService() {

    companion object {
        private const val NOTIF_ID = 0x0CA4
        private const val HEALTH_CHECK_INTERVAL_MS = 45_000L
        private const val RESTART_COOLDOWN_MS = 60_000L

        @JvmStatic
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, CastReceiverService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastReceiverService::class.java))
        }
    }

    private data class RestartState(
        var failureCount: Int = 0,
        var lastFailureMs: Long = 0L
    )

    private lateinit var settings: Settings
    private var healthThread: HandlerThread? = null
    private var healthHandler: Handler? = null
    private val dlnaRestartState = RestartState()
    private val airPlayRestartState = RestartState()

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            checkAndRestartChildServices()
            healthHandler?.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        settings = Settings(this)
        startForegroundCompat()
        startChildServices()
        startHealthCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat()
        startChildServices()
        startHealthCheck()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
            // best effort
        }
    }

    private fun startChildServices() {
        tryStartDlna("initial_start")
        tryStartAirPlay("initial_start")
    }

    private fun startHealthCheck() {
        if (healthThread != null) return
        val thread = HandlerThread("cast-receiver-health-check").apply { start() }
        healthThread = thread
        healthHandler = Handler(thread.looper).also {
            it.removeCallbacksAndMessages(null)
            it.post(healthCheckRunnable)
        }
        SsdpDiagnostics.logServiceHealth("CastReceiver", SsdpDiagnostics.ServiceHealthEvent.Level.INFO, "health_check_started", "interval=${HEALTH_CHECK_INTERVAL_MS}ms")
    }

    private fun stopHealthCheck() {
        healthHandler?.removeCallbacksAndMessages(null)
        healthHandler = null
        try { healthThread?.quitSafely() } catch (_: Throwable) {}
        healthThread = null
        SsdpDiagnostics.logServiceHealth("CastReceiver", SsdpDiagnostics.ServiceHealthEvent.Level.INFO, "health_check_stopped", "")
    }

    private fun checkAndRestartChildServices() {
        if (!DlnaRendererService.isRunning) {
            tryStartDlna("health_check_restart")
        }
        if (!AirPlayService.isRunning && !AirPlayService.isTemporarilyDisabled()) {
            tryStartAirPlay("health_check_restart")
        } else if (AirPlayService.isTemporarilyDisabled()) {
            SsdpDiagnostics.logServiceHealth("CastReceiver", SsdpDiagnostics.ServiceHealthEvent.Level.WARN, "airplay_restart_skipped", "AirPlay temporarily disabled")
        }
    }

    private fun tryStartDlna(event: String) {
        if (inCooldown("DLNA", dlnaRestartState, event)) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, DlnaRendererService::class.java))
            markRestartSuccess("DLNA", dlnaRestartState, event)
        } catch (t: Throwable) {
            markRestartFailure("DLNA", dlnaRestartState, event, t)
        }
    }

    private fun tryStartAirPlay(event: String) {
        if (AirPlayService.isTemporarilyDisabled()) {
            SsdpDiagnostics.logServiceHealth("CastReceiver", SsdpDiagnostics.ServiceHealthEvent.Level.WARN, "airplay_start_skipped", "AirPlay temporarily disabled")
            return
        }
        if (inCooldown("AirPlay", airPlayRestartState, event)) return
        try {
            AirPlayService.start(this)
            markRestartSuccess("AirPlay", airPlayRestartState, event)
        } catch (t: Throwable) {
            markRestartFailure("AirPlay", airPlayRestartState, event, t)
        }
    }

    private fun inCooldown(module: String, state: RestartState, event: String): Boolean {
        val now = System.currentTimeMillis()
        val cooling = state.failureCount > 0 && now - state.lastFailureMs < RESTART_COOLDOWN_MS
        if (cooling) {
            SsdpDiagnostics.logServiceHealth(module, SsdpDiagnostics.ServiceHealthEvent.Level.WARN, "${event}_cooldown", "failureCount=${state.failureCount}")
        }
        return cooling
    }

    private fun markRestartSuccess(module: String, state: RestartState, event: String) {
        state.failureCount = 0
        state.lastFailureMs = 0L
        SsdpDiagnostics.logServiceHealth(module, SsdpDiagnostics.ServiceHealthEvent.Level.INFO, event, "start request accepted")
    }

    private fun markRestartFailure(module: String, state: RestartState, event: String, throwable: Throwable) {
        state.failureCount += 1
        state.lastFailureMs = System.currentTimeMillis()
        SsdpDiagnostics.logServiceHealth(module, SsdpDiagnostics.ServiceHealthEvent.Level.ERROR, event, "start request failed count=${state.failureCount}", throwable)
    }

    override fun onDestroy() {
        stopHealthCheck()
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, com.bd.casttv.ui.framework.NewMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(this, 0x0A14, launchIntent, flags)
        return NotificationCompat.Builder(this, CastApp.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_boot_title))
            .setContentText(getString(R.string.notif_boot_text, settings.deviceName))
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
