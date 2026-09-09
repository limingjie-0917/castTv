package com.bd.casttv.douyin

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 全局视频串行下载管理器（抖音 + 收藏共享同一个队列）。
 *
 * 特点：
 * - 全局单例，串行执行（同一时间只下载一个任务）
 * - 支持多来源：抖音投屏、收藏视频
 * - 支持进度回调（有 Content-Length 时百分比，无则显示"下载中"）
 * - 任务持久化，App 重启后恢复队列
 * - 状态变更通过监听器通知 UI
 */
object DouyinDownloadManager {

    interface Listener {
        fun onTaskAdded(task: DouyinDownloadStore.Task) {}
        fun onTaskProgress(task: DouyinDownloadStore.Task) {}
        fun onTaskCompleted(task: DouyinDownloadStore.Task) {}
        fun onTaskFailed(task: DouyinDownloadStore.Task) {}
        fun onTaskRemoved(taskId: String) {}
        fun onQueueChanged() {}
    }

    private val listeners = mutableListOf<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    private var currentTaskId: String? = null

    @Volatile
    private var isRunning = false

    /** 下载根路径配置（收藏视频使用），由 ContentDownloadSettings 提供 */
    var customRootPath: String = ""
        set(value) {
            field = value
        }

    /** 初始化：启动后检查队列，恢复未完成任务 */
    fun init(context: Context) {
        if (isRunning) return
        isRunning = true
        val prefs = context.applicationContext.getSharedPreferences(
            "casttv_douyin_download", Context.MODE_PRIVATE
        )
        val tasks = DouyinDownloadStore.getAllTasks(context).toMutableList()
        var changed = false
        for (i in tasks.indices) {
            if (tasks[i].status == DouyinDownloadStore.Status.DOWNLOADING) {
                tasks[i] = tasks[i].copy(
                    status = DouyinDownloadStore.Status.PENDING,
                    progress = 0,
                    downloadedBytes = 0
                )
                changed = true
            }
        }
        if (changed) {
            val arr = org.json.JSONArray()
            tasks.forEach { arr.put(it.toJson()) }
            prefs.edit().putString("tasks", arr.toString()).apply()
        }
        scope.launch { processQueue(context.applicationContext) }
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** 添加抖音下载任务 */
    fun enqueueDouyin(context: Context, uri: String, title: String, artworkUrl: String = "", artworkPath: String = ""): DouyinDownloadStore.Task {
        val id = DouyinDownloadStore.generateTaskId(uri)
        val existing = DouyinDownloadStore.getTask(context, id)
        if (existing != null && existing.status == DouyinDownloadStore.Status.COMPLETED) {
            return existing
        }
        val now = System.currentTimeMillis()
        val task = DouyinDownloadStore.Task(
            id = id,
            uri = uri,
            title = title,
            artworkUrl = artworkUrl,
            artworkPath = artworkPath,
            status = DouyinDownloadStore.Status.PENDING,
            progress = 0,
            totalBytes = -1,
            downloadedBytes = 0,
            localPath = "",
            createdAt = now,
            updatedAt = now,
            sourceType = DouyinDownloadStore.SourceType.DOUYIN
        )
        return enqueueTask(context, task)
    }

    /** 添加收藏视频下载任务 */
    fun enqueueFavorite(
        context: Context,
        uri: String,
        title: String,
        collectionId: String,
        collectionName: String,
        durationMs: Long = 0L,
        thumbPath: String = "",
        artworkPath: String = ""
    ): DouyinDownloadStore.Task {
        val id = DouyinDownloadStore.generateTaskId(uri)
        val existing = DouyinDownloadStore.getTask(context, id)
        if (existing != null && existing.status == DouyinDownloadStore.Status.COMPLETED) {
            val file = File(existing.localPath)
            if (file.exists()) return existing
        }
        val now = System.currentTimeMillis()
        val task = DouyinDownloadStore.Task(
            id = id,
            uri = uri,
            title = title,
            artworkUrl = "",
            artworkPath = artworkPath,
            status = DouyinDownloadStore.Status.PENDING,
            progress = 0,
            totalBytes = -1,
            downloadedBytes = 0,
            localPath = "",
            createdAt = now,
            updatedAt = now,
            sourceType = DouyinDownloadStore.SourceType.FAVORITE,
            collectionId = collectionId,
            collectionName = collectionName,
            durationMs = durationMs,
            thumbPath = thumbPath
        )
        return enqueueTask(context, task)
    }

    private fun enqueueTask(context: Context, task: DouyinDownloadStore.Task): DouyinDownloadStore.Task {
        DouyinDownloadStore.upsertTask(context, task)
        notifyAdded(task)
        notifyQueueChanged()
        if (currentJob == null || currentJob?.isActive != true) {
            scope.launch { processQueue(context.applicationContext) }
        }
        return task
    }

    /** 重试失败任务 */
    fun retry(context: Context, taskId: String) {
        val task = DouyinDownloadStore.getTask(context, taskId) ?: return
        val reset = task.copy(
            status = DouyinDownloadStore.Status.PENDING,
            progress = 0,
            totalBytes = -1,
            downloadedBytes = 0,
            errorMsg = "",
            updatedAt = System.currentTimeMillis()
        )
        DouyinDownloadStore.upsertTask(context, reset)
        notifyAdded(reset)
        notifyQueueChanged()
        if (currentJob == null || currentJob?.isActive != true) {
            scope.launch { processQueue(context.applicationContext) }
        }
    }

    /** 取消/删除下载任务 */
    fun remove(context: Context, taskId: String, deleteFile: Boolean = true) {
        val task = DouyinDownloadStore.getTask(context, taskId) ?: return
        if (deleteFile && task.localPath.isNotBlank()) {
            runCatching { File(task.localPath).delete() }
        }
        DouyinDownloadStore.removeTask(context, taskId)
        notifyRemoved(taskId)
        notifyQueueChanged()
    }

    /** 获取当前下载中的任务 ID */
    fun getCurrentTaskId(): String? = currentTaskId

    /** 检查本地文件是否存在 */
    fun isLocalFileExists(context: Context, uri: String): Boolean {
        val task = DouyinDownloadStore.getTaskByUri(context, uri) ?: return false
        if (task.status != DouyinDownloadStore.Status.COMPLETED) return false
        if (task.localPath.isBlank()) return false
        return File(task.localPath).exists()
    }

    /** 获取队列剩余任务数（等待中 + 下载中 - 1 个当前任务） */
    fun getQueueRemainingCount(context: Context): Int {
        val pending = DouyinDownloadStore.getPendingAndDownloading(context).size
        return (pending - 1).coerceAtLeast(0)
    }

    private suspend fun processQueue(context: Context) {
        while (true) {
            val pending = DouyinDownloadStore.getPendingAndDownloading(context)
                .sortedBy { it.createdAt }
            if (pending.isEmpty()) {
                currentJob = null
                currentTaskId = null
                notifyQueueChanged()
                return
            }
            val task = pending.first()
            currentTaskId = task.id
            currentJob = scope.launch {
                downloadFile(context, task)
            }
            currentJob?.join()
        }
    }

    private suspend fun downloadFile(context: Context, task: DouyinDownloadStore.Task) {
        var connection: HttpURLConnection? = null
        var outputStream: FileOutputStream? = null
        var tempFile: File? = null

        try {
            // 更新状态为下载中
            val downloadingTask = task.copy(
                status = DouyinDownloadStore.Status.DOWNLOADING,
                updatedAt = System.currentTimeMillis()
            )
            DouyinDownloadStore.upsertTask(context, downloadingTask)
            notifyProgress(downloadingTask)
            notifyQueueChanged()

            // 确定下载目录
            val downloadDir = when (task.sourceType) {
                DouyinDownloadStore.SourceType.FAVORITE -> {
                    val root = customRootPath.ifBlank {
                        // 默认路径：Movies/castTvDownload
                        val movies = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_MOVIES
                        )
                        File(movies, "castTvDownload").absolutePath
                    }
                    DouyinDownloadStore.getFavoriteDownloadDir(root, task.collectionName)
                }
                else -> DouyinDownloadStore.getDouyinDownloadDir()
            }

            if (!downloadDir.exists()) {
                withContext(Dispatchers.IO) { downloadDir.mkdirs() }
            }

            // 创建临时文件
            val fileName = DouyinDownloadStore.buildFileName(task.title, task.uri)
            tempFile = File(downloadDir, "$fileName.tmp")
            val finalFile = File(downloadDir, fileName)

            // 如果最终文件已存在，直接完成
            if (finalFile.exists() && finalFile.length() > 0) {
                val completed = task.copy(
                    status = DouyinDownloadStore.Status.COMPLETED,
                    progress = 100,
                    localPath = finalFile.absolutePath,
                    totalBytes = finalFile.length(),
                    downloadedBytes = finalFile.length(),
                    updatedAt = System.currentTimeMillis()
                )
                DouyinDownloadStore.upsertTask(context, completed)
                notifyCompleted(completed)
                notifyQueueChanged()
                return
            }

            // HEAD 请求获取 Content-Length
            var contentLength = -1L
            try {
                val headConn = withContext(Dispatchers.IO) {
                    URL(task.uri).openConnection() as HttpURLConnection
                }
                headConn.requestMethod = "HEAD"
                headConn.connectTimeout = 10000
                headConn.readTimeout = 10000
                headConn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                withContext(Dispatchers.IO) { headConn.connect() }
                if (headConn.responseCode in 200..299) {
                    contentLength = headConn.contentLengthLong
                }
                headConn.disconnect()
            } catch (_: Throwable) {
                // HEAD 失败不影响下载
            }

            // 开始 GET 下载
            connection = withContext(Dispatchers.IO) {
                URL(task.uri).openConnection() as HttpURLConnection
            }
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
            withContext(Dispatchers.IO) { connection.connect() }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw Exception("HTTP $responseCode")
            }

            val total = if (contentLength > 0) contentLength else connection.contentLengthLong
            val inputStream = connection.inputStream
            outputStream = withContext(Dispatchers.IO) { FileOutputStream(tempFile) }

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L
            var lastNotify = 0L
            val notifyInterval = 500L

            while (true) {
                bytesRead = withContext(Dispatchers.IO) { inputStream.read(buffer) }
                if (bytesRead == -1) break
                withContext(Dispatchers.IO) { outputStream.write(buffer, 0, bytesRead) }
                totalRead += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastNotify > notifyInterval || total == totalRead) {
                    lastNotify = now
                    val progress = if (total > 0) ((totalRead * 100) / total).toInt().coerceIn(0, 100) else 0
                    val progressTask = task.copy(
                        status = DouyinDownloadStore.Status.DOWNLOADING,
                        progress = progress,
                        totalBytes = total,
                        downloadedBytes = totalRead,
                        updatedAt = now
                    )
                    DouyinDownloadStore.upsertTask(context, progressTask)
                    notifyProgress(progressTask)
                }
            }

            outputStream.flush()

            // 重命名临时文件为最终文件
            withContext(Dispatchers.IO) {
                if (finalFile.exists()) finalFile.delete()
                tempFile.renameTo(finalFile)
            }

            val completed = task.copy(
                status = DouyinDownloadStore.Status.COMPLETED,
                progress = 100,
                totalBytes = if (total > 0) total else totalRead,
                downloadedBytes = totalRead,
                localPath = finalFile.absolutePath,
                updatedAt = System.currentTimeMillis()
            )
            DouyinDownloadStore.upsertTask(context, completed)
            notifyCompleted(completed)
            notifyQueueChanged()

        } catch (e: Throwable) {
            val failed = task.copy(
                status = DouyinDownloadStore.Status.FAILED,
                errorMsg = e.message ?: "下载失败",
                updatedAt = System.currentTimeMillis()
            )
            DouyinDownloadStore.upsertTask(context, failed)
            notifyFailed(failed)
            notifyQueueChanged()
            tempFile?.let { runCatching { if (it.exists()) it.delete() } }
        } finally {
            runCatching { outputStream?.close() }
            runCatching { connection?.disconnect() }
        }
    }

    private fun notifyAdded(task: DouyinDownloadStore.Task) {
        mainHandler.post {
            listeners.toList().forEach { it.onTaskAdded(task) }
        }
    }

    private fun notifyProgress(task: DouyinDownloadStore.Task) {
        mainHandler.post {
            listeners.toList().forEach { it.onTaskProgress(task) }
        }
    }

    private fun notifyCompleted(task: DouyinDownloadStore.Task) {
        mainHandler.post {
            listeners.toList().forEach { it.onTaskCompleted(task) }
        }
    }

    private fun notifyFailed(task: DouyinDownloadStore.Task) {
        mainHandler.post {
            listeners.toList().forEach { it.onTaskFailed(task) }
        }
    }

    private fun notifyRemoved(taskId: String) {
        mainHandler.post {
            listeners.toList().forEach { it.onTaskRemoved(taskId) }
        }
    }

    private fun notifyQueueChanged() {
        mainHandler.post {
            listeners.toList().forEach { it.onQueueChanged() }
        }
    }

    fun destroy() {
        scope.cancel()
        isRunning = false
    }
}
