package com.bd.casttv.douyin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bd.casttv.ui.MainActivity

/**
 * 抖音视频下载前台服务。
 *
 * 职责：
 * - 持有前台通知，保持进程活跃
 * - 管理 DouyinDownloadManager 的生命周期
 * - 更新下载进度通知
 */
class DouyinDownloadService : Service(), DouyinDownloadManager.Listener {

    companion object {
        private const val CHANNEL_ID = "douyin_download_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.bd.casttv.douyin.START_DOWNLOAD"
        private const val ACTION_STOP = "com.bd.casttv.douyin.STOP_DOWNLOAD"

        fun start(context: Context) {
            val intent = Intent(context, DouyinDownloadService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DouyinDownloadService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }

    private var currentTaskTitle = ""
    private var currentProgress = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        DouyinDownloadManager.addListener(this)
        DouyinDownloadManager.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("准备下载…", 0))
                DouyinDownloadManager.init(applicationContext)
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onTaskProgress(task: DouyinDownloadStore.Task) {
        currentTaskTitle = task.title
        currentProgress = task.progress
        updateNotification()
    }

    override fun onTaskCompleted(task: DouyinDownloadStore.Task) {
        val pending = DouyinDownloadStore.getPendingAndDownloading(applicationContext)
        if (pending.isEmpty()) {
            stopForeground(true)
            stopSelf()
        } else {
            currentTaskTitle = pending.firstOrNull()?.title ?: ""
            currentProgress = 0
            updateNotification()
        }
    }

    override fun onTaskFailed(task: DouyinDownloadStore.Task) {
        val pending = DouyinDownloadStore.getPendingAndDownloading(applicationContext)
        if (pending.isEmpty()) {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun updateNotification() {
        val notification = buildNotification(currentTaskTitle, currentProgress)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, progress: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("下载中：${title.ifBlank { "抖音视频" }}")
            .setContentText(if (progress > 0) "$progress%" else "正在准备…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setProgress(100, progress, progress <= 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "抖音视频下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "抖音投屏视频下载进度通知"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DouyinDownloadManager.removeListener(this)
        super.onDestroy()
    }
}
