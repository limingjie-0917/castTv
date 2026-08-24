package com.bd.casttv

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.bd.casttv.routine.RoutineReminderManager
import com.bd.casttv.settings.Settings
import com.bd.casttv.util.LocalCrashLog
import com.bd.casttv.util.NetworkUtils

/**
 * Application entry point: sets up the notification channel used by the
 * foreground renderer service, initialises Settings + the persisted device
 * UDN, and forces dark mode (this is a lean-back TV app).
 */
class CastApp : Application() {

    companion object {
        private const val TAG = "CastApp"
        const val CHANNEL_ID = "casttv_renderer"
        /** High-importance channel used only for the full-screen "incoming cast"
         *  notification that force-launches the player from background/lockscreen. */
        const val CAST_CHANNEL_ID = "casttv_cast"

        /** App context provider for the vendored AirPlay lib (reads FairPlay assets). */
        @JvmStatic
        lateinit var appContext: android.content.Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        installGlobalCrashLogger()
        appContext = applicationContext
        LocalCrashLog.markAppStart(
            this,
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageManager.getPackageInfo(packageName, 0).longVersionCode else packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
        )
        Log.d(TAG, "onCreate: app init start")
        LocalCrashLog.d(this, TAG, "onCreate: app init start")
        // Must be set before any Netty class is initialised on Android.
        System.setProperty("io.netty.noNative", "true")
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        // Initialise persistent identity + settings early.
        NetworkUtils.getDeviceUdn(this)
        Settings(this)

        createNotificationChannel()

        // 全局「系统提示气泡」（24 小时生活作息 + ≥2h 健康提醒）：统计前台使用时长并按会话调度展示。
        RoutineReminderManager.init(this)

        Log.d(TAG, "onCreate: app init complete")
        LocalCrashLog.d(this, TAG, "onCreate: app init complete")
    }

    private fun installGlobalCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT_EXCEPTION thread=${thread.name}", throwable)
            LocalCrashLog.e(this, TAG, "UNCAUGHT_EXCEPTION thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_title)
                setShowBadge(false)
            }
            nm?.createNotificationChannel(channel)

            // High importance so the full-screen intent is honoured and the
            // player is brought up even when the app is in the background.
            val castChannel = NotificationChannel(
                CAST_CHANNEL_ID,
                getString(R.string.notif_cast_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_cast_channel_name)
                setShowBadge(false)
            }
            nm?.createNotificationChannel(castChannel)
        }
    }
}
