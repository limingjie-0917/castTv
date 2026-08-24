package com.bd.casttv.health

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.bd.casttv.favorites.HealthStatus
import com.bd.casttv.favorites.SourceHealthRecord
import com.bd.casttv.favorites.SourceHealthScorer

/**
 * 源健康检测器：复用单个 ExoPlayer，在主线程 Looper 上串行探测。
 *
 * 约定：本类返回的 [SourceHealthRecord.failCount]/[SourceHealthRecord.successCount]
 * 仅表示“本次检测结果”（0/1）；调用方写入缓存时应与旧值累加。
 */
class SourceHealthDetector(context: Context) {

    private val appContext = context.applicationContext
    private val ui = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null

    private var uris: List<String> = emptyList()
    private var index: Int = 0

    private var currentUri: String? = null
    private var startMs: Long = 0L

    /** 当前探测序号：用于避免 READY/ERROR/timeout 多次回调导致重复 finish。 */
    private var currentDetectSeq: Int = 0
    private var timeoutDetectSeq: Int = 0

    private var onEach: ((String, SourceHealthRecord) -> Unit)? = null
    private var onDone: (() -> Unit)? = null

    private val timeoutTask = Runnable {
        // 若已进入下一条 / 已 cancel，则忽略。
        if (timeoutDetectSeq == 0 || timeoutDetectSeq != currentDetectSeq) return@Runnable
        val uri = currentUri ?: return@Runnable

        finishOne(
            uri = uri,
            record = SourceHealthRecord(
                status = HealthStatus.UNHEALTHY,
                score = 0,
                hasVideo = false,
                hasAudio = false,
                startupMs = System.currentTimeMillis() - startMs,
                reason = "连接超时/卡顿",
                lastCheckMs = System.currentTimeMillis(),
                failCount = 1,
                successCount = 0,
            )
        )
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_READY) return
            if (currentDetectSeq == 0) return

            val uri = currentUri ?: return
            val p = player ?: return

            val tracks = p.currentTracks
            var hasVideo = false
            var hasAudio = false
            for (g in tracks.groups) {
                when (g.type) {
                    C.TRACK_TYPE_VIDEO -> if (g.length > 0) hasVideo = true
                    C.TRACK_TYPE_AUDIO -> if (g.length > 0) hasAudio = true
                }
            }

            val startup = System.currentTimeMillis() - startMs
            val scored = SourceHealthScorer.score(
                connectable = true,
                hasVideo = hasVideo,
                hasAudio = hasAudio,
                startupMs = startup,
            )

            finishOne(
                uri = uri,
                record = SourceHealthRecord(
                    status = scored.status,
                    score = scored.score,
                    hasVideo = hasVideo,
                    hasAudio = hasAudio,
                    startupMs = startup,
                    reason = scored.reason,
                    lastCheckMs = System.currentTimeMillis(),
                    failCount = 0,
                    successCount = 1,
                )
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            if (currentDetectSeq == 0) return
            val uri = currentUri ?: return

            finishOne(
                uri = uri,
                record = SourceHealthRecord(
                    status = HealthStatus.UNHEALTHY,
                    score = 0,
                    hasVideo = false,
                    hasAudio = false,
                    startupMs = System.currentTimeMillis() - startMs,
                    reason = error.errorCodeName.ifBlank { "无法连接" },
                    lastCheckMs = System.currentTimeMillis(),
                    failCount = 1,
                    successCount = 0,
                )
            )
        }
    }

    fun detect(
        uris: List<String>,
        onEach: (uri: String, record: SourceHealthRecord) -> Unit,
        onDone: () -> Unit,
    ) {
        ui.post {
            startDetectInternal(
                nextUris = uris.map { it.trim() }.filter { it.isNotBlank() },
                onEach = onEach,
                onDone = onDone,
            )
        }
    }

    /** 停止检测并释放播放器。 */
    fun cancel() {
        ui.post {
            ui.removeCallbacks(timeoutTask)
            timeoutDetectSeq = 0
            currentDetectSeq = 0

            currentUri = null
            index = 0
            uris = emptyList()
            onEach = null
            onDone = null

            try {
                player?.removeListener(listener)
            } catch (_: Throwable) {
            }
            try {
                player?.release()
            } catch (_: Throwable) {
            }
            player = null
        }
    }

    private fun startDetectInternal(
        nextUris: List<String>,
        onEach: (uri: String, record: SourceHealthRecord) -> Unit,
        onDone: () -> Unit,
    ) {
        // 若已有检测在跑：直接覆盖为新任务（更符合“重检立即生效”的交互预期）。
        ui.removeCallbacks(timeoutTask)
        timeoutDetectSeq = 0
        currentDetectSeq = 0

        this.uris = nextUris
        this.index = 0
        this.currentUri = null
        this.onEach = onEach
        this.onDone = onDone

        if (nextUris.isEmpty()) {
            onDone.invoke()
            return
        }

        val p = ensurePlayer()
        // 通过 remove+add 的方式清空可能残留的异步回调，避免旧任务事件串扰。
        try { p.removeListener(listener) } catch (_: Throwable) {}
        p.addListener(listener)
        detectNext()
    }

    private fun ensurePlayer(): ExoPlayer {
        val existing = player
        if (existing != null) return existing
        return ExoPlayer.Builder(appContext).build().also { p ->
            p.volume = 0f
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.addListener(listener)
            player = p
        }
    }

    private fun detectNext() {
        if (index >= uris.size) {
            finishAll()
            return
        }
        val uri = uris[index]
        index += 1
        currentUri = uri
        startMs = System.currentTimeMillis()

        val p = ensurePlayer()

        // 每条探测一个 detectSeq，用于兜底去重。
        currentDetectSeq += 1
        timeoutDetectSeq = currentDetectSeq

        ui.removeCallbacks(timeoutTask)
        ui.postDelayed(timeoutTask, TIMEOUT_MS)

        try {
            p.stop()
            p.clearMediaItems()
        } catch (_: Throwable) {
        }

        try {
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            p.playWhenReady = true
        } catch (_: Throwable) {
            finishOne(
                uri = uri,
                record = SourceHealthRecord(
                    status = HealthStatus.UNHEALTHY,
                    score = 0,
                    hasVideo = false,
                    hasAudio = false,
                    startupMs = System.currentTimeMillis() - startMs,
                    reason = "无法连接",
                    lastCheckMs = System.currentTimeMillis(),
                    failCount = 1,
                    successCount = 0,
                )
            )
        }
    }

    private fun finishOne(uri: String, record: SourceHealthRecord) {
        if (currentDetectSeq == 0) return

        ui.removeCallbacks(timeoutTask)
        timeoutDetectSeq = 0
        currentDetectSeq = 0
        currentUri = null

        try {
            player?.stop()
            player?.clearMediaItems()
        } catch (_: Throwable) {
        }

        try {
            onEach?.invoke(uri, record)
        } catch (_: Throwable) {
        }

        // 下一条：避免同一帧连续 stop/prepare 产生偶现串扰，这里轻微 post。
        ui.post { detectNext() }
    }

    private fun finishAll() {
        ui.removeCallbacks(timeoutTask)
        timeoutDetectSeq = 0
        currentDetectSeq = 0
        currentUri = null

        try {
            onDone?.invoke()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        private const val TIMEOUT_MS = 8_000L
    }
}
