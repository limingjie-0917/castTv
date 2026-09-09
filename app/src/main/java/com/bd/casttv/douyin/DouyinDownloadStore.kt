package com.bd.casttv.douyin

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 视频下载记录存储：SharedPreferences 持久化下载任务状态。
 *
 * 支持多来源：抖音投屏、收藏视频等，共享同一个下载队列。
 *
 * 下载目录规则：
 * - 抖音来源：公共存储/Movies/抖音收藏/{文件名}
 * - 收藏来源：{配置的根路径}/{合集名称}/{文件名}
 */
object DouyinDownloadStore {

    private const val PREFS = "casttv_douyin_download"
    private const val KEY_TASKS = "tasks"
    private const val DOUYIN_FOLDER = "抖音收藏"

    /** 下载来源类型 */
    object SourceType {
        const val DOUYIN = "douyin"      // 抖音投屏
        const val FAVORITE = "favorite"   // 收藏视频
    }

    enum class Status {
        PENDING,        // 等待中
        DOWNLOADING,    // 下载中
        COMPLETED,      // 下载完成
        FAILED          // 下载失败
    }

    data class Task(
        val id: String,              // 唯一ID = uri 的 SHA-256 前12位
        val uri: String,             // 视频地址
        val title: String,           // 视频标题
        val artworkUrl: String,      // 封面 URL
        val artworkPath: String,     // 封面本地路径
        val status: Status,          // 下载状态
        val progress: Int,           // 进度百分比 0-100
        val totalBytes: Long,        // 总字节数（-1 表示未知）
        val downloadedBytes: Long,   // 已下载字节数
        val localPath: String,       // 本地文件路径（下载完成后填充）
        val createdAt: Long,         // 创建时间
        val updatedAt: Long,         // 最后更新时间
        val errorMsg: String = "",   // 失败原因
        val sourceType: String = SourceType.DOUYIN, // 来源类型
        val collectionId: String = "", // 合集ID（收藏来源）
        val collectionName: String = "", // 合集名称（收藏来源）
        val durationMs: Long = 0L,   // 视频时长（毫秒）
        val thumbPath: String = ""   // 缩略图路径
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("uri", uri)
            .put("title", title)
            .put("artworkUrl", artworkUrl)
            .put("artworkPath", artworkPath)
            .put("status", status.name)
            .put("progress", progress)
            .put("totalBytes", totalBytes)
            .put("downloadedBytes", downloadedBytes)
            .put("localPath", localPath)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("errorMsg", errorMsg)
            .put("sourceType", sourceType)
            .put("collectionId", collectionId)
            .put("collectionName", collectionName)
            .put("durationMs", durationMs)
            .put("thumbPath", thumbPath)

        companion object {
            fun fromJson(o: JSONObject): Task = Task(
                id = o.optString("id"),
                uri = o.optString("uri"),
                title = o.optString("title"),
                artworkUrl = o.optString("artworkUrl"),
                artworkPath = o.optString("artworkPath"),
                status = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.FAILED),
                progress = o.optInt("progress", 0),
                totalBytes = o.optLong("totalBytes", -1),
                downloadedBytes = o.optLong("downloadedBytes", 0),
                localPath = o.optString("localPath"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                errorMsg = o.optString("errorMsg"),
                sourceType = o.optString("sourceType", SourceType.DOUYIN),
                collectionId = o.optString("collectionId", ""),
                collectionName = o.optString("collectionName", ""),
                durationMs = o.optLong("durationMs", 0L),
                thumbPath = o.optString("thumbPath", "")
            )
        }
    }

    /** 获取抖音下载目录 */
    fun getDouyinDownloadDir(): File {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dir = File(moviesDir, DOUYIN_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 获取收藏视频下载目录（根路径/合集名） */
    fun getFavoriteDownloadDir(rootPath: String, collectionName: String): File {
        val safeName = collectionName.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "未分组" }
        val dir = File(File(rootPath), safeName)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 根据 URL 生成安全的文件名 */
    fun buildFileName(title: String, uri: String): String {
        val ext = runCatching {
            val path = uri.substringBefore('?').substringBefore('#')
            val dot = path.lastIndexOf('.')
            if (dot > 0) path.substring(dot).take(8) else ".mp4"
        }.getOrDefault(".mp4")
        val safeTitle = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(80)
            .ifBlank { "video_${System.currentTimeMillis()}" }
        return "$safeTitle$ext"
    }

    /** 生成任务 ID */
    fun generateTaskId(uri: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.trim().toByteArray(Charsets.UTF_8))
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 0x10) append('0')
                append(Integer.toHexString(v))
            }
        }.take(12)
    }

    fun getAllTasks(context: Context): List<Task> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<Task>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Task.fromJson(o))
            }
            out
        }.getOrDefault(emptyList())
    }

    fun getTasksBySource(context: Context, sourceType: String): List<Task> {
        return getAllTasks(context).filter { it.sourceType == sourceType }
    }

    fun getTasksByCollection(context: Context, collectionId: String): List<Task> {
        return getAllTasks(context).filter { it.collectionId == collectionId }
    }

    fun getTask(context: Context, id: String): Task? {
        return getAllTasks(context).firstOrNull { it.id == id }
    }

    fun getTaskByUri(context: Context, uri: String): Task? {
        return getAllTasks(context).firstOrNull { it.uri == uri }
    }

    fun upsertTask(context: Context, task: Task) {
        val current = getAllTasks(context).toMutableList()
        current.removeAll { it.id == task.id }
        current.add(0, task.copy(updatedAt = System.currentTimeMillis()))
        save(context, current)
    }

    fun removeTask(context: Context, id: String) {
        val current = getAllTasks(context).toMutableList()
        current.removeAll { it.id == id }
        save(context, current)
    }

    fun getPendingAndDownloading(context: Context): List<Task> {
        return getAllTasks(context).filter { it.status == Status.PENDING || it.status == Status.DOWNLOADING }
    }

    fun getCurrentDownloading(context: Context): Task? {
        return getAllTasks(context).firstOrNull { it.status == Status.DOWNLOADING }
    }

    private fun save(context: Context, tasks: List<Task>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TASKS, arr.toString())
            .apply()
    }
}
