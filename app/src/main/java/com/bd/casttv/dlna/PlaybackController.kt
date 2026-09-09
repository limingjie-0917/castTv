package com.bd.casttv.dlna

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central state bridge between the SSDP/SOAP layer (HTTP worker threads),
 * the ExoPlayer-backed [com.bd.casttv.player.PlayerActivity] and the
 * standby UI.
 *
 * Threading model:
 *  - SOAP actions arrive on NanoHTTPD worker threads. They mutate the shared
 *    state fields (guarded by [lock]) and dispatch command callbacks to the
 *    player on the MAIN thread via [main].
 *  - The player reports position/duration/state back on the main thread; those
 *    fields are read (possibly from worker threads) by GetPositionInfo /
 *    GetTransportInfo, so writes/reads are synchronized.
 */
object PlaybackController {

    enum class TransportState(val upnp: String) {
        NO_MEDIA_PRESENT("NO_MEDIA_PRESENT"),
        STOPPED("STOPPED"),
        PLAYING("PLAYING"),
        PAUSED_PLAYBACK("PAUSED_PLAYBACK"),
        TRANSITIONING("TRANSITIONING")
    }

    /** Commands the player (or any renderer) must implement. */
    interface CommandCallback {
        fun onSetUri(uri: String, title: String)
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onSeek(positionMs: Long)
        fun onSetVolume(volume: Int)
        fun onSetMute(mute: Boolean)
    }

    /** State observer for the UI layer (standby screen). */
    interface StateObserver {
        fun onTransportStateChanged(state: TransportState) {}
        /** Fired on SetAVTransportURI so the UI can bring up the player. */
        fun onNewCastRequest(uri: String, title: String, sourceHint: String) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    // TRANSITIONING 超时看门狗：SetAVTransportURI 后进入 TRANSITIONING，
    // 如果 PlayerActivity 启动失败或 ExoPlayer 加载超时，状态会卡在 TRANSITIONING。
    // 抖音轮询 GetTransportInfo 看到一直 TRANSITIONING → 判定连接超时 → 断开。
    // 10s 后自动转为 STOPPED，让控制点知道上一次 URI 无效。
    private val transitioningWatchdog = Runnable {
        if (transportState == TransportState.TRANSITIONING) {
            updateTransportState(TransportState.STOPPED)
        }
    }
    private const val TRANSITIONING_TIMEOUT_MS = 10_000L

    // ---- 投屏历史（仅内存，进程存活期间有效，不持久化到磁盘） ----
    /** 单条投屏历史记录。
     *  thumbPath 为该次投屏首帧截图文件路径（放在 cacheDir/thumbnails_history 目录，jpg 格式）；
     *  截图未完成 / 失败时为 null，UI 层展示默认兜底图。
     *  artworkPath 为投屏元数据（DIDL-Lite `<upnp:albumArtURI>`）异步下载后的本地封面路径；
     *  存在时优先于 thumbPath 展示。 */
    data class HistoryItem(
        val uri: String,
        val title: String,
        val source: String,
        val time: Long,
        val isDouyinCast: Boolean = false,
        val thumbPath: String? = null,
        /** 上次播放位置（毫秒），用于「继续播放」续播与进度条。无记录时为 0。 */
        val positionMs: Long = 0,
        /** 媒体总时长（毫秒），用于计算进度百分比与「重新播放」判定。未知时为 0。 */
        val durationMs: Long = 0,
        /** DIDL-Lite `upnp:albumArtURI` 原始 URL（http/https），投屏元数据里的封面地址。 */
        val artworkUrl: String? = null,
        /** 封面下载并落盘后的本地路径；优先级高于 [thumbPath]。 */
        val artworkPath: String? = null,
        val description: String = "",
        val resolution: String = "",
        val isLive: Boolean = false
    )

    // 线程安全列表：SOAP worker 线程写入、UI 线程读取。最多保留最近 10 条。
    private val historyList = CopyOnWriteArrayList<HistoryItem>()
    private const val MAX_HISTORY = 10
    private const val HISTORY_WRITE_INTERVAL_MS = 5_000L
    private const val MIN_HISTORY_WRITE_INTERVAL_MS = 500L

    /**
     * 历史写入后台线程：UI / 播放线程只投递写事件，避免 1s progress tick 直接改历史列表。
     * object 本身是进程级单例，等价于 PlaybackController 内部静态持有。
     */
    private object HistoryWriter {
        private val thread = HandlerThread("playback-history-writer").apply { start() }
        private val handler = Handler(thread.looper)

        fun post(block: () -> Unit) {
            handler.post(block)
        }
    }

    @Volatile private var lastHistoryWriteMs: Long = 0L

    private fun historyWriteIntervalMs(): Long {
        val halfThresholdMs = douyinHistoryThresholdMs.coerceAtLeast(0L) / 2L
        return maxOf(MIN_HISTORY_WRITE_INTERVAL_MS, minOf(HISTORY_WRITE_INTERVAL_MS, halfThresholdMs))
    }

    /** 返回投屏历史快照（最近在前）。 */
    fun history(): List<HistoryItem> = historyList.toList()

    /**
     * 记录一次新投屏。uri 为空则忽略；若与已有记录 uri 相同则移动到最前（去重），
     * 否则加到最前；最多保留最近 [MAX_HISTORY] 条。
     */
    fun recordPlaybackHistory(
        uri: String,
        title: String,
        hint: String,
        artworkUrl: String? = null,
        description: String = "",
        resolution: String = "",
        isDouyinCast: Boolean = false
    ) {
        if (uri.isBlank()) return
        HistoryWriter.post {
            recordPlaybackHistoryInternal(uri, title, hint, artworkUrl, description, resolution, isDouyinCast)
        }
    }

    private fun recordPlaybackHistoryInternal(
        uri: String,
        title: String,
        hint: String,
        artworkUrl: String? = null,
        description: String = "",
        resolution: String = "",
        isDouyinCast: Boolean = false
    ) {
        if (uri.isBlank()) return
        synchronized(historyList) {
            val old = historyList.firstOrNull { it.uri == uri }
            historyList.removeAll { it.uri == uri }
            historyList.add(
                0,
                HistoryItem(
                    uri = uri,
                    title = title,
                    source = hint,
                    time = System.currentTimeMillis(),
                    isDouyinCast = isDouyinCast || old?.isDouyinCast == true,
                    thumbPath = old?.thumbPath,
                    positionMs = old?.positionMs ?: 0L,
                    durationMs = old?.durationMs ?: 0L,
                    artworkUrl = artworkUrl?.takeIf { it.isNotBlank() } ?: old?.artworkUrl,
                    artworkPath = old?.artworkPath,
                    description = description.ifBlank { old?.description.orEmpty() },
                    resolution = resolution.ifBlank { old?.resolution.orEmpty() }
                )
            )
            while (historyList.size > MAX_HISTORY) {
                historyList.removeAt(historyList.size - 1)
            }
        }
        lastHistoryWriteMs = System.currentTimeMillis()
    }

    private fun recordHistory(
        uri: String,
        title: String,
        hint: String,
        artworkUrl: String? = null,
        description: String = "",
        resolution: String = "",
        isDouyinCast: Boolean = false
    ) =
        recordPlaybackHistory(uri, title, hint, artworkUrl, description, resolution, isDouyinCast)

    /**
     * 播放器首帧渲染后回填缩略图路径。若不存在对应 uri 的记录（例如已被挤出 10 条上限）则忽略。
     */
    fun updateHistoryThumb(uri: String, thumbPath: String) {
        if (uri.isBlank() || thumbPath.isBlank()) return
        HistoryWriter.post {
            synchronized(historyList) {
                val idx = historyList.indexOfFirst { it.uri == uri }
                if (idx < 0) return@post
                val old = historyList[idx]
                historyList[idx] = old.copy(thumbPath = thumbPath)
            }
        }
    }

    /**
     * 封面图下载成功后回填本地路径。若不存在对应 uri 的记录则忽略。
     * 展示优先级：[HistoryItem.artworkPath] > [HistoryItem.thumbPath] > 兜底图。
     */
    fun updateHistoryArtwork(uri: String, artworkPath: String) {
        if (uri.isBlank() || artworkPath.isBlank()) return
        HistoryWriter.post {
            synchronized(historyList) {
                val idx = historyList.indexOfFirst { it.uri == uri }
                if (idx < 0) return@post
                val old = historyList[idx]
                historyList[idx] = old.copy(artworkPath = artworkPath)
            }
        }
    }

    /**
     * 当前播放资源对应的历史缩略图路径；用于首页「投屏状态 - 播放中」封面回显、
     * 手机端虚拟电视等场景。优先使用 DLNA 元数据下载得到的封面（[HistoryItem.artworkPath]），
     * 其次使用本地截图（[HistoryItem.thumbPath]）；都没有时返回 null，UI 层回落到区域原有背景。
     */
    fun currentThumbPath(): String? {
        val uri = currentUri
        if (uri.isBlank()) return null
        val item = historyList.firstOrNull { it.uri == uri } ?: return null
        item.artworkPath?.takeIf { it.isNotBlank() }?.let { return it }
        return item.thumbPath?.takeIf { it.isNotBlank() }
    }

    /** 当前播放资源对应的 DIDL-Lite 封面 URL（未下载 / 无该字段时返回 null）。 */
    fun currentArtworkUrl(): String? {
        val uri = currentUri
        if (uri.isBlank()) return null
        return historyList.firstOrNull { it.uri == uri }?.artworkUrl?.takeIf { it.isNotBlank() }
    }

    /** 当前播放资源对应的本地封面路径（下载完成后才有值）。 */
    fun currentArtworkPath(): String? {
        val uri = currentUri
        if (uri.isBlank()) return null
        return historyList.firstOrNull { it.uri == uri }?.artworkPath?.takeIf { it.isNotBlank() }
    }

    /**
     * 回填 / 更新某条历史记录的播放进度（上次播放位置与总时长），用于历史卡片
     * 「继续播放」续播、进度条与「上次观看到 X小时X分」副标题。
     * 找不到对应 uri 的记录（例如已被挤出上限）则忽略。
     */
    fun updateHistoryProgress(uri: String, positionMs: Long, durationMs: Long) {
        updateHistoryProgress(uri, positionMs, durationMs, force = false)
    }

    private fun updateHistoryProgress(uri: String, positionMs: Long, durationMs: Long, force: Boolean) {
        if (uri.isBlank() || positionMs < 0) return
        val thresholdMs = douyinHistoryThresholdMs
        val shouldReportThreshold = thresholdMs > 0 && currentIsDouyinCast && positionMs >= thresholdMs && douyinReportedUri != uri
        val now = System.currentTimeMillis()
        if (!force && !shouldReportThreshold && now - lastHistoryWriteMs < historyWriteIntervalMs()) return
        lastHistoryWriteMs = now
        HistoryWriter.post {
            updateHistoryProgressInternal(uri, positionMs, durationMs, shouldReportThreshold)
        }
    }

    private fun updateHistoryProgressInternal(
        uri: String,
        positionMs: Long,
        durationMs: Long,
        shouldReportThreshold: Boolean
    ) {
        if (uri.isBlank() || positionMs < 0) return
        synchronized(historyList) {
            val idx = historyList.indexOfFirst { it.uri == uri }
            if (idx < 0) return
            val old = historyList[idx]
            historyList[idx] = old.copy(
                positionMs = positionMs,
                durationMs = if (durationMs > 0) durationMs else old.durationMs
            )
        }
        lastHistoryWriteMs = System.currentTimeMillis()
        // 抖音投屏播放记录：当同一 uri 播放到达阈值时，向监听器上报一次。
        if (shouldReportThreshold && douyinReportedUri != uri) {
            douyinReportedUri = uri
            try { douyinThresholdListener?.onReached(uri, positionMs, if (durationMs > 0) durationMs else this.durationMs) } catch (_: Throwable) {}
        }
    }

    /** 抖音投屏播放记录阈值（毫秒），0 表示禁用。 */
    @Volatile var douyinHistoryThresholdMs: Long = 15_000L
    /** 用于避免同一 uri 反复触发阈值回调。 */
    @Volatile private var douyinReportedUri: String = ""

    fun interface DouyinThresholdListener {
        fun onReached(uri: String, positionMs: Long, durationMs: Long)
    }
    @Volatile var douyinThresholdListener: DouyinThresholdListener? = null

    // ---- 手机端 HTTP 可编辑历史：支持删除单条 / 清空 ----
    /** 按 uri 删除一条历史，返回是否命中。仅内存操作，不影响缩略图缓存文件。 */
    fun removeHistoryByUri(uri: String): Boolean {
        if (uri.isBlank()) return false
        val removed: Boolean
        synchronized(historyList) {
            removed = historyList.removeAll { it.uri == uri }
        }
        return removed
    }

    /** 清空全部历史记录（内存）。 */
    fun clearHistoryAll() {
        synchronized(historyList) { historyList.clear() }
    }

    // ---- 「稍后播放」外部投屏中断标记 ----
    /**
     * 记录进入播放器时是否属于「稍后播放队列」触发的一次播放。
     * 播放器在收到新的 [onSetAvTransportUri]（外部投屏）时，可根据此标记
     * 判断是否要在外部投屏结束后弹出「继续播放队列？」提示。
     */
    @Volatile var queuePlaybackActive: Boolean = false
    /** 上一次被外部投屏中断的队列 uri，用于恢复。 */
    @Volatile var interruptedQueueUri: String = ""


    // ---- shared transport state (read by GetTransportInfo/GetPositionInfo) ----
    @Volatile var transportState: TransportState = TransportState.NO_MEDIA_PRESENT
        private set
    @Volatile var currentUri: String = ""
        private set
    @Volatile var currentTitle: String = ""
        private set
    @Volatile var sourceHint: String = ""
        private set
    @Volatile var currentIsDouyinCast: Boolean = false
        private set
    @Volatile var currentDescription: String = ""
        private set
    @Volatile var currentResolution: String = ""
        private set
    @Volatile var nextUri: String = ""
        private set
    @Volatile var nextTitle: String = ""
        private set
    @Volatile var nextSourceHint: String = ""
        private set
    @Volatile private var nextArtworkUrl: String? = null
    @Volatile private var nextDescription: String = ""
    @Volatile private var nextResolution: String = ""
    @Volatile private var nextIsDouyinCast: Boolean = false
    @Volatile var durationMs: Long = 0
    @Volatile var positionMs: Long = 0
    @Volatile var volume: Int = 50
    @Volatile var mute: Boolean = false
    @Volatile private var genaEventManager: GenaEventManager? = null
    @Volatile private var lastAvTransportNotifyMs: Long = 0L

    fun attachGenaEventManager(manager: GenaEventManager?) {
        genaEventManager = manager
        notifyAvTransportLastChange(force = true)
        notifyRenderingControlLastChange()
    }

    /**
     * True while the standby UI ([com.bd.casttv.ui.MainActivity]) is in the
     * foreground. The renderer service uses this to decide whether it needs to
     * force-launch the player itself (background / locked screen case).
     */
    @Volatile var uiInForeground: Boolean = false

    // ---- pending intent for commands that arrive before the player exists ----
    // SetAVTransportURI / Play / Pause / Seek can arrive on the HTTP thread
    // before PlayerActivity has registered its callback. We remember the last
    // desired state and replay it once a callback registers, so no control
    // command is silently dropped (a cause of "cast succeeds but won't play").
    @Volatile private var desiredPlaying: Boolean = true
    @Volatile private var pendingSeekMs: Long = -1

    private var command: CommandCallback? = null
    private val observers = mutableListOf<StateObserver>()

    // ---------- registration ----------

    /**
     * 由 PlayerActivity 在主动启动（从收藏/历史/网页解析等入口点击播放）时调用，
     * 同步更新 PlaybackController 的全局 URI/标题/来源，避免后续 registerCommandCallback
     * 触发的 flushPending 用旧的投屏 URI 覆盖当前要播放的内容。
     */
    fun setCurrentUriAndTitle(uri: String, title: String, hint: String = "") {
        synchronized(lock) {
            currentUri = uri
            currentTitle = title
            sourceHint = hint
            currentIsDouyinCast = false
            positionMs = 0
            transportState = TransportState.TRANSITIONING
        }
    }

    fun registerCommandCallback(cb: CommandCallback?) {
        synchronized(lock) { command = cb }
        // Replay the latest desired state onto a freshly-attached player so a
        // Pause/Seek that arrived during activity start-up is not lost.
        // 投屏启动路径：插队到主线程队列最前，确保回放启动指令立即执行。
        if (cb != null) main.postAtFrontOfQueue { flushPending(cb) }
    }

    fun hasActiveCommandCallback(): Boolean = synchronized(lock) { command != null }

    private fun flushPending(cb: CommandCallback) {
        // 重放 pending URI：如果 SetAVTransportURI 在 PlayerActivity 注册 callback 之前到达，
        // URI 已存入 currentUri 但 onSetUri 未被调用。PlayerActivity 启动后必须先 setUri 再 play，
        // 否则播放器会尝试播放旧 URI 或空 URI。
        if (currentUri.isNotBlank()) {
            cb.onSetUri(currentUri, currentTitle)
        }
        val seek = pendingSeekMs
        if (seek >= 0) {
            cb.onSeek(seek)
            pendingSeekMs = -1
        }
        if (desiredPlaying) cb.onPlay() else cb.onPause()
    }

    fun registerObserver(o: StateObserver) = synchronized(lock) {
        if (!observers.contains(o)) observers.add(o)
    }

    fun unregisterObserver(o: StateObserver) = synchronized(lock) { observers.remove(o) }

    // ---------- inbound: SOAP -> controller -> player/UI ----------

    /**
     * Handle SetAVTransportURI. Saves the target and notifies the UI to bring
     * up the player, then forwards the URI to whatever command callback exists.
     *
     * @param artworkUrl DIDL-Lite `<upnp:albumArtURI>` 中的封面 URL；为空表示投屏元数据里没带。
     */
    fun onSetAvTransportUri(
        uri: String,
        title: String,
        hint: String,
        artworkUrl: String? = null,
        description: String = "",
        resolution: String = "",
        isDouyinCast: Boolean = false
    ) {
        synchronized(lock) {
            currentUri = uri
            currentTitle = title
            sourceHint = hint
            currentIsDouyinCast = isDouyinCast
            currentDescription = description
            currentResolution = resolution
            positionMs = 0
            douyinReportedUri = ""
            transportState = TransportState.TRANSITIONING
        }
        // 记录媒体地址已下发，这是投屏连接链路中的核心节点。
        notifyAvTransportLastChange(force = true)
        SsdpDiagnostics.logCastEvent(
            SsdpDiagnostics.CastEvent.Kind.MEDIA_URI_SET,
            "收到 SetAVTransportURI：$title（来源：$hint）"
        )
        // 记录一条投屏历史（仅内存，进程存活期间有效）。
        recordHistory(uri, title, hint, artworkUrl, description, resolution, isDouyinCast)
        // A new URI implies "start playing from the beginning".
        desiredPlaying = true
        pendingSeekMs = -1
        val obs = snapshotObservers()
        // 投屏启动路径：插队到主线程队列最前，避免被其他排队任务延迟（~3s 才出现「接收到投屏」提示）。
        main.postAtFrontOfQueue {
            obs.forEach { it.onNewCastRequest(uri, title, hint) }
            command?.onSetUri(uri, title)
        }
    }

    fun onSetNextAvTransportUri(
        uri: String,
        title: String,
        hint: String,
        artworkUrl: String? = null,
        description: String = "",
        resolution: String = "",
        isDouyinCast: Boolean = false
    ) {
        synchronized(lock) {
            nextUri = uri
            nextTitle = title
            nextSourceHint = hint
            nextArtworkUrl = artworkUrl
            nextDescription = description
            nextResolution = resolution
            nextIsDouyinCast = isDouyinCast
        }
        try {
            notifyAvTransportLastChange(force = true)
            SsdpDiagnostics.logCastEvent(
                SsdpDiagnostics.CastEvent.Kind.MEDIA_URI_SET,
                "收到 SetNextAVTransportURI：$title（来源：$hint）"
            )
        } catch (_: Throwable) {}
    }

    fun next(): Boolean {
        val target = synchronized(lock) {
            if (nextUri.isBlank()) return false
            NextMedia(
                nextUri,
                nextTitle.ifBlank { nextUri.substringAfterLast('/').substringBefore('?').ifBlank { "下一条视频" } },
                nextSourceHint,
                nextArtworkUrl,
                nextDescription,
                nextResolution,
                nextIsDouyinCast
            ).also {
                nextUri = ""
                nextTitle = ""
                nextSourceHint = ""
                nextArtworkUrl = null
                nextDescription = ""
                nextResolution = ""
                nextIsDouyinCast = false
            }
        }
        onSetAvTransportUri(target.uri, target.title, target.hint, target.artworkUrl, target.description, target.resolution, target.isDouyinCast)
        return true
    }

    fun previous(): Boolean = false

    private data class NextMedia(
        val uri: String,
        val title: String,
        val hint: String,
        val artworkUrl: String?,
        val description: String,
        val resolution: String,
        val isDouyinCast: Boolean
    )

    fun play() {
        // 抖音等控制点会在 Play 后立刻轮询 GetTransportInfo。
        // 为了避免「Play 已收到但 CurrentTransportState 仍是 TRANSITIONING」导致的误判，
        // 这里先在 controller 内同步更新状态，再异步投递给播放器执行。
        desiredPlaying = true
        if (currentUri.isNotBlank()) updateTransportState(TransportState.PLAYING)
        notifyAvTransportLastChange(force = true)
        // 投屏启动路径：插队到主线程队列最前，确保 Play 指令到达后立即执行，不被其他任务延迟。
        main.postAtFrontOfQueue { command?.onPlay() }
    }

    fun pause() {
        desiredPlaying = false
        if (currentUri.isNotBlank()) updateTransportState(TransportState.PAUSED_PLAYBACK)
        notifyAvTransportLastChange(force = true)
        main.post { command?.onPause() }
    }

    fun stop() {
        desiredPlaying = false
        if (currentUri.isNotBlank()) updateTransportState(TransportState.STOPPED)
        notifyAvTransportLastChange(force = true)
        main.post { command?.onStop() }
    }

    fun seek(positionMs: Long) {
        pendingSeekMs = positionMs
        this.positionMs = positionMs.coerceAtLeast(0)
        notifyAvTransportLastChange(force = true)
        main.post { command?.onSeek(positionMs) }
    }

    fun applyVolume(v: Int) {
        val clamped = v.coerceIn(0, 100)
        volume = clamped
        notifyRenderingControlLastChange()
        main.post { command?.onSetVolume(clamped) }
    }

    fun applyMute(m: Boolean) {
        mute = m
        notifyRenderingControlLastChange()
        main.post { command?.onSetMute(m) }
    }

    // ---------- outbound: player -> controller (state reporting) ----------

    /** Called by the player to publish a new transport state. */
    fun updateTransportState(state: TransportState) {
        val changed: Boolean
        val previous: TransportState
        val uriSnapshot: String
        val positionSnapshot: Long
        val durationSnapshot: Long
        synchronized(lock) {
            previous = transportState
            changed = previous != state
            transportState = state
            uriSnapshot = currentUri
            positionSnapshot = positionMs
            durationSnapshot = durationMs
        }
        // 看门狗：进入 TRANSITIONING 时启动超时定时器，离开时取消。
        if (state == TransportState.TRANSITIONING) {
            main.removeCallbacks(transitioningWatchdog)
            main.postDelayed(transitioningWatchdog, TRANSITIONING_TIMEOUT_MS)
        } else {
            main.removeCallbacks(transitioningWatchdog)
        }
        if (changed) {
            notifyAvTransportLastChange(force = true)
            try {
                SsdpDiagnostics.logPlayerState(previous.upnp, state.upnp, uriSnapshot, positionSnapshot, durationSnapshot)
            } catch (_: Throwable) {}
            val obs = snapshotObservers()
            main.post { obs.forEach { it.onTransportStateChanged(state) } }
            // 播放 / 暂停 / 停止等状态变化时立即落一次当前进度，不受节流间隔限制。
            updateHistoryProgress(currentUri, positionMs, durationMs, force = true)
        }
    }

    fun updateProgress(positionMs: Long, durationMs: Long) {
        updateProgress(positionMs, durationMs, forceHistoryWrite = false)
    }

    fun updateProgress(positionMs: Long, durationMs: Long, forceHistoryWrite: Boolean) {
        this.positionMs = positionMs
        if (durationMs > 0) this.durationMs = durationMs
        notifyAvTransportProgressIfNeeded()
        // 同步写入当前资源对应的历史记录，供历史卡片续播 / 进度条使用；内部会按阈值节流。
        updateHistoryProgress(currentUri, positionMs, if (durationMs > 0) durationMs else this.durationMs, forceHistoryWrite)
    }

    fun clearMedia() {
        // 清空媒体前先强制保存一次最终状态进度。
        updateHistoryProgress(currentUri, positionMs, durationMs, force = true)
        val previousState: TransportState
        val previousUri: String
        val previousPosition: Long
        val previousDuration: Long
        synchronized(lock) {
            previousState = transportState
            previousUri = currentUri
            previousPosition = positionMs
            previousDuration = durationMs
            currentUri = ""
            currentTitle = ""
            sourceHint = ""
            currentIsDouyinCast = false
            currentDescription = ""
            currentResolution = ""
            positionMs = 0
            durationMs = 0
            transportState = TransportState.NO_MEDIA_PRESENT
        }
        try {
            notifyAvTransportLastChange(force = true)
            val obs = snapshotObservers()
            main.post { obs.forEach { it.onTransportStateChanged(TransportState.NO_MEDIA_PRESENT) } }
            SsdpDiagnostics.logPlayerState(previousState.upnp, TransportState.NO_MEDIA_PRESENT.upnp, previousUri, previousPosition, previousDuration)
        } catch (_: Throwable) {}
        // 清空媒体等价于连接中断/结束。
        SsdpDiagnostics.logCastEvent(
            SsdpDiagnostics.CastEvent.Kind.INTERRUPTED,
            "clearMedia：投屏结束/中断"
        )
    }

    private fun notifyAvTransportProgressIfNeeded() {
        if (transportState != TransportState.PLAYING) return
        val now = System.currentTimeMillis()
        if (now - lastAvTransportNotifyMs < AV_TRANSPORT_PROGRESS_NOTIFY_INTERVAL_MS) return
        notifyAvTransportLastChange(force = false)
    }

    private fun notifyAvTransportLastChange(force: Boolean) {
        val manager = genaEventManager ?: return
        val now = System.currentTimeMillis()
        if (!force && transportState == TransportState.PLAYING && now - lastAvTransportNotifyMs < AV_TRANSPORT_PROGRESS_NOTIFY_INTERVAL_MS) return
        lastAvTransportNotifyMs = now
        try {
            manager.sendNotify(UpnpXml.SVC_AVTRANSPORT, buildAvTransportLastChange())
        } catch (_: Throwable) {}
    }

    private fun notifyRenderingControlLastChange() {
        val manager = genaEventManager ?: return
        try {
            manager.sendNotify(UpnpXml.SVC_RENDERING, UpnpXml.renderingControlLastChange(volume, mute))
        } catch (_: Throwable) {}
    }

    fun currentTransportActions(): String {
        val actions = mutableListOf("Play", "Pause", "Stop", "Seek")
        if (nextUri.isNotBlank()) actions.add("Next")
        return actions.joinToString(",")
    }

    fun buildAvTransportLastChange(): String = UpnpXml.avTransportLastChange(
        state = transportState.upnp,
        status = "OK",
        currentUri = currentUri,
        nextUri = nextUri,
        duration = msToHmsCompat(durationMs),
        position = msToHmsCompat(positionMs),
        actions = currentTransportActions()
    )

    fun buildRenderingControlLastChange(): String = UpnpXml.renderingControlLastChange(volume, mute)

    private fun msToHmsCompat(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
    }

    private fun snapshotObservers(): List<StateObserver> =
        synchronized(lock) { observers.toList() }

    private const val AV_TRANSPORT_PROGRESS_NOTIFY_INTERVAL_MS = 2_000L
}

/**
 * 历史卡片缩略图取值口径统一入口：
 *  优先展示 DLNA 元数据封面（[PlaybackController.HistoryItem.artworkPath]），
 *  其次回落到本地首帧截图（[PlaybackController.HistoryItem.thumbPath]）。
 * 两者都要求路径非空且文件真实存在，否则返回 null（UI 层展示默认兜底图）。
 */
fun PlaybackController.HistoryItem.displayThumbPath(): String? =
    artworkPath?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
        ?: thumbPath?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
