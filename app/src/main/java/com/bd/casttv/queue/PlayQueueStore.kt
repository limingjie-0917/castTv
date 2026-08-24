package com.bd.casttv.queue

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「稍后播放」播放队列的读写管理器。
 *
 * 存储位置：App 私有目录 [Context.getFilesDir]/play_queue.json，属应用沙盒，
 * 卸载 App 自动清理，不需要额外权限。
 *
 * 数据结构：
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "items": [
 *     { "id": "uuid", "title": "", "uri": "", "source": "",
 *       "status": "PENDING|PLAYING|FINISHED", "addedAt": 0 }
 *   ]
 * }
 * ```
 *
 * 线程安全：
 * - 内存列表使用 [CopyOnWriteArrayList]；
 * - 任何变更调用后立即写盘，写盘串行执行；
 * - UI / HTTP worker 线程都可以安全调用读接口。
 *
 * 与收藏、历史存储互相隔离，任何异常都不会污染现有 favorites.json / 历史内存列表。
 */
class PlayQueueStore(context: Context) {

    enum class Status(val raw: String) {
        PENDING("PENDING"),   // 待播放
        PLAYING("PLAYING"),   // 播放中
        FINISHED("FINISHED"); // 播放结束

        companion object {
            fun parse(s: String?): Status = values().firstOrNull { it.raw == s } ?: PENDING
        }
    }

    data class QueueItem(
        val id: String,
        val title: String,
        val uri: String,
        val source: String,
        val status: Status,
        val addedAt: Long
    )

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val items = CopyOnWriteArrayList<QueueItem>()
    private val ioLock = Any()

    /**
     * 专用单线程后台写盘（ANR 优化 v1.1.131）：
     * 队列的增删改会频繁触发写盘，若在主线程同步 writeText 会造成卡顿。
     * 交给单线程 Executor 串行执行，既避免阻塞 UI，又保证写入顺序。
     */
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "play-queue-io").apply { isDaemon = true }
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    init { load() }

    // -------- 读取接口 --------
    fun all(): List<QueueItem> = items.toList()
    fun pending(): List<QueueItem> = items.filter { it.status == Status.PENDING }
    fun currentPlaying(): QueueItem? = items.firstOrNull { it.status == Status.PLAYING }
    /** 按 uri 查询队列项；收藏/历史卡片用它实时判断「稍后播放 / 移出队列」状态。 */
    fun findByUri(uri: String): QueueItem? {
        val trimmed = uri.trim()
        if (trimmed.isBlank()) return null
        return items.firstOrNull { it.uri == trimmed }
    }
    fun size(): Int = items.size

    // -------- 变更接口 --------
    /** 追加一条到队列末尾。若相同 uri 已经存在，则不做任何事，返回既有 id。 */
    fun add(title: String, uri: String, source: String): String {
        val trimmedUri = uri.trim()
        if (trimmedUri.isBlank()) return ""
        val existing = items.firstOrNull { it.uri == trimmedUri }
        if (existing != null) return existing.id
        val item = QueueItem(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { trimmedUri },
            uri = trimmedUri,
            source = source,
            status = Status.PENDING,
            addedAt = System.currentTimeMillis()
        )
        items.add(item)
        persistAndNotify()
        return item.id
    }

    /**
     * 将一条视频插入到队列首位并返回它的 id：
     *  - 若相同 uri 已经在队列，则把它移到首位并清空 PLAYING 状态（改为 PENDING），保留原 id/title/source；
     *  - 否则新建一条 PENDING 项插入到首位。
     *
     * 用于「云端推荐」弹窗中的『播放推荐内容』：立即插到队首后再交由 `NewMainActivity.startQueuePlayback` 拉起播放器。
     */
    fun prepend(title: String, uri: String, source: String): String {
        val trimmedUri = uri.trim()
        if (trimmedUri.isBlank()) return ""
        val existingIdx = items.indexOfFirst { it.uri == trimmedUri }
        val moved: QueueItem = if (existingIdx >= 0) {
            val cur = items.removeAt(existingIdx)
            // 若之前是 PLAYING，插回队首后我们仍以 PENDING 语义交给调用方发起 startQueuePlayback。
            cur.copy(status = Status.PENDING)
        } else {
            QueueItem(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { trimmedUri },
                uri = trimmedUri,
                source = source,
                status = Status.PENDING,
                addedAt = System.currentTimeMillis()
            )
        }
        items.add(0, moved)
        persistAndNotify()
        return moved.id
    }

    /** 批量追加（返回真正新增的 id 列表）。 */
    fun addAll(entries: List<Triple<String, String, String>>): List<String> {
        val ids = mutableListOf<String>()
        for ((t, u, s) in entries) {
            val id = add(t, u, s)
            if (id.isNotEmpty()) ids.add(id)
        }
        return ids
    }

    fun remove(id: String): Boolean {
        val removed = items.removeAll { it.id == id }
        if (removed) persistAndNotify()
        return removed
    }

    /** 按 uri 移出队列；用于收藏/历史卡片的「移出队列」按钮。 */
    fun removeByUri(uri: String): Boolean {
        val trimmed = uri.trim()
        if (trimmed.isBlank()) return false
        val removed = items.removeAll { it.uri == trimmed }
        if (removed) persistAndNotify()
        return removed
    }

    fun clear() {
        if (items.isEmpty()) return
        items.clear()
        persistAndNotify()
    }

    /** 按新的 id 顺序重排；未包含 id 保持相对次序追加在末尾。 */
    fun reorder(newIds: List<String>) {
        val snap = items.toMutableList()
        val byId = snap.associateBy { it.id }
        val ordered = mutableListOf<QueueItem>()
        for (id in newIds) byId[id]?.let { ordered.add(it) }
        for (existing in snap) if (ordered.none { it.id == existing.id }) ordered.add(existing)
        items.clear()
        items.addAll(ordered)
        persistAndNotify()
    }

    /** 覆盖式替换队列（用于「覆盖队列」推送）。 */
    fun replaceAll(entries: List<Triple<String, String, String>>) {
        items.clear()
        for ((t, u, s) in entries) {
            val trimmed = u.trim()
            if (trimmed.isBlank()) continue
            if (items.any { it.uri == trimmed }) continue
            items.add(
                QueueItem(
                    id = UUID.randomUUID().toString(),
                    title = t.ifBlank { trimmed },
                    uri = trimmed,
                    source = s,
                    status = Status.PENDING,
                    addedAt = System.currentTimeMillis()
                )
            )
        }
        persistAndNotify()
    }

    fun setStatus(id: String, status: Status) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        val old = items[idx]
        if (old.status == status) return
        // PLAYING 具有唯一性：设置某项为 PLAYING 时，将其他 PLAYING 转为 FINISHED。
        if (status == Status.PLAYING) {
            for (i in items.indices) {
                val cur = items[i]
                if (cur.status == Status.PLAYING && cur.id != id) {
                    items[i] = cur.copy(status = Status.FINISHED)
                }
            }
        }
        items[idx] = old.copy(status = status)
        persistAndNotify()
    }

    /** 按 uri 匹配设置状态（TV 端 ExoPlayer 只能拿到 uri）。返回命中的 item。 */
    fun setStatusByUri(uri: String, status: Status): QueueItem? {
        val idx = items.indexOfFirst { it.uri == uri }
        if (idx < 0) return null
        setStatus(items[idx].id, status)
        return items[idx].copy(status = status)
    }

    /** 从队列中取下一条待播放。 */
    fun nextPending(): QueueItem? = items.firstOrNull { it.status == Status.PENDING }

    fun addListener(cb: () -> Unit) { listeners.add(cb) }
    fun removeListener(cb: () -> Unit) { listeners.remove(cb) }

    // -------- 持久化 --------
    private fun persistAndNotify() {
        // 写盘异步化：内存态（items）已在调用前更新，监听器可立即基于内存刷新 UI，
        // 磁盘写入交给单线程后台 Executor，避免阻塞主线程造成 ANR。
        ioExecutor.execute { save() }
        for (cb in listeners) {
            try { cb() } catch (_: Throwable) {}
        }
    }

    private fun load() {
        synchronized(ioLock) {
            if (!file.exists()) return
            try {
                val text = file.readText(Charsets.UTF_8)
                if (text.isBlank()) return
                val root = JSONObject(text)
                val arr = root.optJSONArray("items") ?: return
                var normalizedChanged = false
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val title = o.optString("title")
                    val uri = o.optString("uri")
                    if (uri.isBlank()) continue
                    val source = o.optString("source")
                    val status = Status.parse(o.optString("status"))
                    val addedAt = o.optLong("addedAt", System.currentTimeMillis())
                    // 冷启动进程时若上次残留「PLAYING」，统一回退为「PENDING」，避免误导为仍在播放，
                    // 同时保证用户可以通过「继续播放」从队列中断点恢复（从第一条待播放开始）。
                    val normalized = if (status == Status.PLAYING) {
                        normalizedChanged = true
                        Status.PENDING
                    } else {
                        status
                    }
                    items.add(QueueItem(id, title, uri, source, normalized, addedAt))
                }
                if (normalizedChanged) {
                    // 冷启动修正后尽快写回落盘，避免下次再次残留 PLAYING。
                    ioExecutor.execute { save() }
                }
            } catch (_: Throwable) {
                // 数据损坏时安静回落到空队列。
            }
        }
    }

    private fun save() {
        synchronized(ioLock) {
            try {
                val arr = JSONArray()
                for (item in items) {
                    val o = JSONObject()
                    o.put("id", item.id)
                    o.put("title", item.title)
                    o.put("uri", item.uri)
                    o.put("source", item.source)
                    o.put("status", item.status.raw)
                    o.put("addedAt", item.addedAt)
                    arr.put(o)
                }
                val root = JSONObject()
                root.put("schemaVersion", 1)
                root.put("items", arr)
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(root.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    // 兜底：复制 + 删除。
                    file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                    tmp.delete()
                }
            } catch (_: Throwable) {
                // 磁盘不可写时忽略：内存态仍然正确，下次变更再重试。
            }
        }
    }

    companion object {
        private const val FILE_NAME = "play_queue.json"

        /**
         * 进程范围内的单例访问入口。
         *
         * 使用单例避免多个 [PlayQueueStore] 实例的内存状态不一致——HTTP 服务、
         * MainActivity 与 PlayerActivity 都需要读写同一份队列。
         */
        @Volatile private var instance: PlayQueueStore? = null
        fun get(context: Context): PlayQueueStore {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                val current = instance
                if (current != null) current else {
                    val created = PlayQueueStore(context.applicationContext)
                    instance = created
                    created
                }
            }
        }
    }
}
