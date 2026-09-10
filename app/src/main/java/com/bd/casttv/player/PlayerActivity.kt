package com.bd.casttv.player

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.bd.casttv.R
import com.bd.casttv.databinding.ActivityPlayerBinding
import com.bd.casttv.databinding.DialogFavoriteAddBinding
import com.bd.casttv.dlna.PlaybackController
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.queue.PlayQueueStore
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.util.Thumbnails
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen ExoPlayer surface driven by DLNA control commands.
 *
 * Media source type (Progressive / HLS / DASH) is auto-selected by media3's
 * [androidx.media3.exoplayer.source.DefaultMediaSourceFactory]. Web parse playback
 * can pass MIME type and HTTP headers for protected CDN links.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SOURCE = "extra_source"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_HTTP_REFERER = "extra_http_referer"
        const val EXTRA_HTTP_USER_AGENT = "extra_http_user_agent"
        const val EXTRA_RETURN_SKIP_PAGE_RELOAD = "extra_return_skip_page_reload"
        /** 起播位置（毫秒）；历史「继续播放」续播用，0 表示从头播放。 */
        const val EXTRA_START_POSITION = "extra_start_position"
        /** When true, the activity renders an AirPlay mirroring stream instead of ExoPlayer. */
        const val EXTRA_AIRPLAY_MIRROR = "extra_airplay_mirror"

        private const val CONTROL_HIDE_MS = 5_000L
        private const val VOLUME_HIDE_MS = 2_000L
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val SEEK_STEP_MS = 10_000L
        private const val STREAM_END_TIMEOUT_MS = 15_000L
        private const val HISTORY_SCREENSHOT_DELAY_MS = 4_000L
        private const val USER_AGENT = "CastTV/1.0 (Linux; Android) ExoPlayer"
        // P0-3 network-jitter reconnect: bounded retries, fixed 2s delay per attempt.
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val PLAYER_TARGET_BUFFER_BYTES = 64 * 1024 * 1024
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    /**
     * 当前实际用于渲染的 PlayerView：默认使用 TextureView，用户在设置里勾选
     * 「使用 SurfaceView」后改为 SurfaceView。仅在 `onCreate` 时按设置绑定一次，
     * 中途切换需要重启播放页才会生效。
     */
    private lateinit var activePlayerView: androidx.media3.ui.PlayerView

    /** 系统媒体音量管理器：起播时用它读取当前媒体音量，让播放音量跟随系统。 */
    private val audioManager: android.media.AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    }

    private val ui = Handler(Looper.getMainLooper())
    private var seeking = false

    private var title: String = ""
    private var sourceHint: String = ""
    private var currentUri: String = ""
    private var currentMimeType: String = ""
    private var httpReferer: String = ""
    private var httpUserAgent: String = ""
    private var httpDataSourceFactory: DefaultHttpDataSource.Factory? = null
    private var returnSkipPageReload: Boolean = false
    /** 起播位置（毫秒），来自历史「继续播放」；下一次 initPlayer 时消费一次后清零。 */
    private var startPositionMs: Long = 0L

    /** 收藏数据存储（filesDir 私有目录 JSON 持久化）。 */
    private val favoritesStore: FavoritesStore by lazy { FavoritesStore(this) }

    /** 稍后播放队列，播放器内电视台式侧边列表和自动衔接复用这一份进程单例。 */
    private val queueStore: PlayQueueStore by lazy { PlayQueueStore.get(this) }
    private val queueAdapter = QueueAdapter { item -> playQueueItem(item) }
    private val queueChangedListener: () -> Unit = { ui.post { refreshQueuePanel() } }

    /** 本地文件访问权限请求器（best-effort，低版本系统生效；被拒绝也不影响私有目录存储）。 */
    private val storagePermLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { /* non-fatal：收藏始终写入 App 私有目录 */ }

    /** 缓存最近一次首帧截图（用于收藏时立即取用），仅内存，画面切换即失效。 */
    @Volatile private var lastCapturedBitmap: android.graphics.Bitmap? = null

    /** 后台线程：JPEG 编码 / 磁盘写入，避免占用主线程。 */
    private val thumbSaveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "thumb-save").apply { isDaemon = true }
    }

    // P0-3: reconnect bookkeeping. Reset to 0 whenever playback becomes READY.
    private var reconnectAttempts = 0
    private val reconnectRunnable = Runnable { doReconnect() }

    // --- runnables ---
    private val hideControlsRunnable = Runnable { setControlBarVisible(false) }
    private val hideVolumeRunnable = Runnable { binding.volumeOverlay.visibility = View.GONE }
    private val historyScreenshotRunnable = Runnable { captureHistoryThumbnail() }
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            // Only keep the 1s timer alive while actually playing so a paused /
            // stopped player consumes no CPU (PRD: background CPU < 5%).
            if (player?.isPlaying == true) ui.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }
    // Auto-return to standby when playback is stopped/idle for too long.
    private val streamEndRunnable = Runnable { finish() }

    // 方案 1：进入播放器时短暂展示的"正在接收投屏"横幅，1.5s 后淡出。
    private val hideBannerTask = Runnable { hideIncomingCastBanner() }

    // ---- AirPlay mirroring mode ----
    private var mirrorMode = false
    private var videoPlayer: com.bd.casttv.airplay.VideoPlayer? = null
    private var audioPlayer: com.bd.casttv.airplay.AudioPlayer? = null
    private var mirrorDecodersRunning = false

    private val mirrorListener = object : com.bd.casttv.airplay.AirPlayController.Listener {
        override fun onMirrorStart() { /* already on screen */ }
        override fun onMirrorSize(width: Int, height: Int) {
            ui.post { binding.mirrorSurface.setMeasure(width.toFloat(), height.toFloat()) }
        }
        override fun onMirrorStop() { ui.post { finish() } }
    }

    private val mirrorHolderCallback = object : android.view.SurfaceHolder.Callback {
        override fun surfaceCreated(holder: android.view.SurfaceHolder) {}
        override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {
            startMirrorDecoders(holder.surface)
        }
        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
            stopMirrorDecoders()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show over the lock screen and wake the display so a cast that arrives
        // while the TV is idle/locked lands directly on the player.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 常亮：无论 SDK 走哪个分支都必须加 FLAG_KEEP_SCREEN_ON（O_MR1+ 的
        // setShowWhenLocked/setTurnScreenOn 只负责"锁屏上显示/点亮屏幕"，不含常亮）。
        // 旧代码把 KEEP_SCREEN_ON 放在 <27 分支里 → 新系统播放时系统熄屏超时照常触发。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 按 Settings.useSurfaceView 挑选一个 PlayerView 作为渲染面，另一个隐藏
        // 并解除 player 绑定（避免残留 SurfaceView 抢占硬件通道）。
        val useSurfaceView = com.bd.casttv.settings.Settings(this).useSurfaceView
        activePlayerView = if (useSurfaceView) binding.playerView else binding.playerViewTexture
        binding.playerView.visibility = if (useSurfaceView) View.VISIBLE else View.GONE
        binding.playerViewTexture.visibility = if (useSurfaceView) View.GONE else View.VISIBLE

        readIntent(intent)
        setupSeekBar()
        setupTouchGestures()

        // 侧边弹窗按钮沿用稍后播放/推荐页风格：银白描边、暖白焦点边框，
        // 点击时只切换视觉选中态，原有收藏弹窗/稍后播放列表功能保持不变。
        val sidebarIconTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(ThemeManager.currentPalette(this).accent, Color.WHITE)
        )
        binding.iconSidebarFavorite.imageTintList = sidebarIconTint
        binding.iconSidebarQueue.imageTintList = sidebarIconTint
        binding.textSidebarFavorite.setTextColor(sidebarIconTint)
        binding.textSidebarQueue.setTextColor(sidebarIconTint)
        binding.btnSidebarFavorite.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) hideQueuePanel()
            FocusFxHelper.applyFocusFxState(view, hasFocus, cornerRadiusDp = 18)
        }
        binding.btnSidebarFavorite.setOnClickListener {
            binding.btnSidebarFavorite.isSelected = !binding.btnSidebarFavorite.isSelected
            hideQueuePanel()
            showFavoriteAddDialog()
        }
        binding.btnSidebarQueue.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) showQueuePanel(focusList = false)
            FocusFxHelper.applyFocusFxState(view, hasFocus, cornerRadiusDp = 18)
        }
        binding.btnSidebarQueue.setOnClickListener {
            binding.btnSidebarQueue.isSelected = !binding.btnSidebarQueue.isSelected
            showQueuePanel(focusList = true)
        }
        setupQueuePanel()

        if (mirrorMode) setupMirrorUi()

        // 方案 1：进入播放器时短暂展示"正在接收投屏"横幅，1.5s 淡出，
        // 让用户感知到"投屏已到达即将播放"，避免 MainActivity 上的横幅被 PlayerActivity 秒盖没看清。
        showIncomingCastBanner()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A new cast arrived while already playing (multi-source switch).
        val previousUri = currentUri
        readIntent(intent)
        cancelStreamEndTimer()
        // P0-3: only re-prepare when the source actually changed (or nothing is
        // playing); a duplicate intent for the same URI must not restart playback.
        val active = player?.let {
            it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_BUFFERING
        } == true
        if (currentUri != previousUri || !active) {
            reconnectAttempts = 0
            lastCapturedBitmap = null
            ui.removeCallbacks(historyScreenshotRunnable)
            ui.removeCallbacks(reconnectRunnable)
            player?.let { p ->
                p.setMediaItem(buildMediaItem(currentUri))
                p.prepare()
                p.playWhenReady = true
            }
        }
        updateSourceLabel()
        showControlsTemporarily()
    }

    private fun readIntent(intent: android.content.Intent?) {
        // Prefer explicit extras; fall back to the controller's current values.
        currentUri = intent?.getStringExtra(EXTRA_URI)
            ?: PlaybackController.currentUri
        title = intent?.getStringExtra(EXTRA_TITLE)
            ?: PlaybackController.currentTitle
        sourceHint = intent?.getStringExtra(EXTRA_SOURCE)
            ?: PlaybackController.sourceHint
        currentMimeType = intent?.getStringExtra(EXTRA_MIME_TYPE).orEmpty()
        httpReferer = intent?.getStringExtra(EXTRA_HTTP_REFERER).orEmpty()
        httpUserAgent = intent?.getStringExtra(EXTRA_HTTP_USER_AGENT).orEmpty()
        updateHttpRequestHeaders()
        returnSkipPageReload = intent?.getBooleanExtra(EXTRA_RETURN_SKIP_PAGE_RELOAD, false) ?: false
        startPositionMs = intent?.getLongExtra(EXTRA_START_POSITION, 0L) ?: 0L
        mirrorMode = intent?.getBooleanExtra(EXTRA_AIRPLAY_MIRROR, false) ?: false
    }

    // ------------------------------------------------------------------
    // Lifecycle: create/release the player in onStart/onStop (TV best practice)
    // ------------------------------------------------------------------
    override fun onStart() {
        super.onStart()
        if (mirrorMode) {
            updateSourceLabel()
            return
        }
        initPlayer()
        // 冷启动一次：如果 controller 里已经有 DIDL-Lite 封面 URL，也要触发下载，
        // 让首页/收藏能拿到封面。onSetUri 会在同 URI 时短路，这里补一次兜底触发。
        downloadArtworkIfPresent(currentUri)
        updateSourceLabel()
        // One-shot refresh; the repeating timer starts only when playback begins.
        updateProgress()
        showControlsTemporarily()
    }

    override fun onStop() {
        super.onStop()
        if (mirrorMode) {
            stopMirrorDecoders()
            return
        }
        updateProgress(forceHistoryWrite = true)
        releasePlayer()
        try { queueStore.removeListener(queueChangedListener) } catch (_: Throwable) {}
        stopProgressUpdates()
        ui.removeCallbacks(hideControlsRunnable)
        ui.removeCallbacks(hideVolumeRunnable)
        ui.removeCallbacks(historyScreenshotRunnable)
        ui.removeCallbacks(reconnectRunnable)
        cancelStreamEndTimer()
    }

    override fun onDestroy() {
        if (mirrorMode) {
            com.bd.casttv.airplay.AirPlayController.removeListener(mirrorListener)
            stopMirrorDecoders()
        }
        ui.removeCallbacks(historyScreenshotRunnable)
        ui.removeCallbacks(hideBannerTask)
        thumbSaveExecutor.shutdown()
        lastCapturedBitmap = null
        super.onDestroy()
    }

    /**
     * 无论从哪条路径退出播放器（BACK 键、流结束、镜像停止、侧栏关闭等），
     * 都保证回到新版主页 [com.bd.casttv.ui.framework.NewMainActivity]。
     * 用 CLEAR_TOP | SINGLE_TOP | NEW_TASK 保证：若新主页已在栈内则复用并弹回该实例；
     * 若不在（例如通过老 MainActivity 的 EXTRA_CAST_PENDING 路径进入播放器）则新建。
     */
    override fun finish() {
        try {
            val i = Intent(this, com.bd.casttv.ui.framework.NewMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                val returnPageId = intent?.getStringExtra(com.bd.casttv.ui.framework.NewMainActivity.EXTRA_OPEN_PAGE_ID).orEmpty()
                if (returnPageId.isNotBlank()) putExtra(com.bd.casttv.ui.framework.NewMainActivity.EXTRA_OPEN_PAGE_ID, returnPageId)
                if (returnSkipPageReload) {
                    putExtra(EXTRA_RETURN_SKIP_PAGE_RELOAD, true)
                }
            }
            startActivity(i)
        } catch (_: Throwable) { /* ignore, still call super.finish() */ }
        super.finish()
    }

    // ------------------------------------------------------------------
    // 方案 1：播放器顶部"正在接收投屏"短提示横幅（1.5s 自动淡出，不参与焦点）
    // ------------------------------------------------------------------
    /** 进入播放器时展示接收横幅：常规投屏带标题，镜像/无标题回退纯文案。 */
    private fun showIncomingCastBanner() {
        val label = title.trim()
        val text = if (mirrorMode || label.isEmpty()) {
            getString(R.string.cast_receiving_banner)
        } else {
            getString(R.string.player_cast_receiving_banner, label)
        }
        binding.playerCastReceivingText.text = text
        binding.playerCastReceivingBanner.apply {
            alpha = 0f
            translationY = -20f
            visibility = View.VISIBLE
            animate().cancel()
            animate().alpha(1f).translationY(0f).setDuration(180L).start()
        }
        ui.removeCallbacks(hideBannerTask)
        ui.postDelayed(hideBannerTask, 1_500L)
    }

    /** 隐藏接收横幅：淡出 150ms 后 GONE，与 MainActivity 动画曲线一致；幂等安全。 */
    private fun hideIncomingCastBanner() {
        val v = binding.playerCastReceivingBanner
        if (v.visibility != View.VISIBLE) return
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .translationY(-20f)
            .setDuration(150L)
            .withEndAction { v.visibility = View.GONE }
            .start()
    }

    // ------------------------------------------------------------------
    // AirPlay mirroring rendering (reuses this activity's full-screen surface)
    // ------------------------------------------------------------------

    private fun setupMirrorUi() {
        binding.playerView.visibility = View.GONE
        binding.playerViewTexture.visibility = View.GONE
        binding.mirrorSurface.visibility = View.VISIBLE
        binding.mirrorSurface.setMeasure(
            com.bd.casttv.airplay.AirPlayController.videoWidth.toFloat(),
            com.bd.casttv.airplay.AirPlayController.videoHeight.toFloat()
        )
        binding.mirrorSurface.holder.addCallback(mirrorHolderCallback)
        com.bd.casttv.airplay.AirPlayController.addListener(mirrorListener)
    }

    private fun startMirrorDecoders(surface: android.view.Surface) {
        if (mirrorDecodersRunning) return
        mirrorDecodersRunning = true
        val ctrl = com.bd.casttv.airplay.AirPlayController
        val vp = com.bd.casttv.airplay.VideoPlayer(surface, ctrl.videoWidth, ctrl.videoHeight)
        vp.start()
        videoPlayer = vp
        ctrl.attachVideoPlayer(vp)
        try {
            val ap = com.bd.casttv.airplay.AudioPlayer()
            ap.start()
            audioPlayer = ap
            ctrl.attachAudioPlayer(ap)
        } catch (e: Exception) {
            // Audio is best-effort; video mirroring continues regardless.
        }
    }

    private fun stopMirrorDecoders() {
        if (!mirrorDecodersRunning) return
        mirrorDecodersRunning = false
        com.bd.casttv.airplay.AirPlayController.detachPlayers()
        try { videoPlayer?.stopVideoPlay() } catch (_: Exception) {}
        try { audioPlayer?.stopPlay() } catch (_: Exception) {}
        videoPlayer = null
        audioPlayer = null
    }

    override fun onResume() {
        super.onResume()
        // Mark the UI as foreground so the renderer service won't try to
        // re-launch through MainActivity while the player already handles
        // incoming SetAVTransportURI switches directly via its command callback.
        PlaybackController.uiInForeground = true
    }

    override fun onPause() {
        super.onPause()
        PlaybackController.uiInForeground = false
    }

    private fun startProgressUpdates() {
        ui.removeCallbacks(progressRunnable)
        ui.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        ui.removeCallbacks(progressRunnable)
    }

    private fun buildMediaItem(uri: String): MediaItem {
        val inferredMimeType = currentMimeType.ifBlank { inferMimeType(uri) }
        val builder = MediaItem.Builder().setUri(uri)
        if (inferredMimeType.isNotBlank()) builder.setMimeType(inferredMimeType)
        return builder.build()
    }

    private fun inferMimeType(uri: String): String {
        val lower = uri.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        return when {
            lower.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            lower.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            lower.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            else -> ""
        }
    }

    private fun updateHttpRequestHeaders() {
        val headers = linkedMapOf<String, String>()
        val ua = httpUserAgent.ifBlank { USER_AGENT }
        if (ua.isNotBlank()) headers["User-Agent"] = ua
        if (httpReferer.isNotBlank()) {
            headers["Referer"] = httpReferer
            originFromUrl(httpReferer)?.let { headers["Origin"] = it }
        }
        httpDataSourceFactory?.setDefaultRequestProperties(headers)
    }

    private fun originFromUrl(url: String): String? = runCatching {
        val u = java.net.URL(url)
        val port = if (u.port > 0 && u.port != u.defaultPort) ":${u.port}" else ""
        "${u.protocol}://${u.host}$port"
    }.getOrNull()

    private fun initPlayer() {
        if (player != null) return

        // Aggressive start-up buffering for fast first frame: play as soon as
        // ~0.7s is buffered instead of the default 2.5s, and prioritise time
        // over size so playback begins before large chunks are fetched.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 700,
                /* bufferForPlaybackAfterRebufferMs = */ 1_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(PLAYER_TARGET_BUFFER_BYTES)
            .build()

        // HTTP source with sane connect/read timeouts, cross-protocol redirects
        // (http<->https is common on CDN links) and a UA some CDNs require.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(httpUserAgent.ifBlank { USER_AGENT })
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)
        httpDataSourceFactory = httpFactory
        updateHttpRequestHeaders()

        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            // 小米/HyperOS 连续进入退出时，底层解码器释放偶发阻塞；限制 release 等待时间，避免主线程长时间卡住。
            .setReleaseTimeoutMs(500)
            .build()
        player = exo
        // 音量完全跟随系统：ExoPlayer 自身保持满输出，实际响度由系统媒体音量决定。
        exo.volume = 1f
        activePlayerView.player = exo
        exo.addListener(playerListener)

        // 如果当前 URI 来自 intent（用户主动从收藏/历史等入口点击播放），
        // 先同步更新 PlaybackController 的全局状态，避免 registerCommandCallback 触发
        // 的 flushPending 用旧的投屏 URI（如抖音投屏）覆盖当前要播放的 URI。
        if (currentUri.isNotBlank()) {
            PlaybackController.setCurrentUriAndTitle(currentUri, title, sourceHint)
        }
        PlaybackController.registerCommandCallback(commandCallback)

        if (currentUri.isNotBlank()) {
            exo.setMediaItem(buildMediaItem(currentUri))
            exo.prepare()
            // 历史「继续播放」：从上次位置续播；消费一次后清零，避免后续误用。
            if (startPositionMs > 0) {
                exo.seekTo(startPositionMs)
                startPositionMs = 0L
            }
            exo.playWhenReady = true
            PlaybackController.updateTransportState(PlaybackController.TransportState.TRANSITIONING)
        } else {
            // P0-4: no valid URI — show a friendly hint instead of a black screen,
            // then fall back to standby after the watchdog timeout.
            showCenterStatus(getString(R.string.player_error_no_uri), false)
            PlaybackController.updateTransportState(PlaybackController.TransportState.STOPPED)
            scheduleStreamEndTimer()
        }
    }

    private fun releasePlayer() {
        PlaybackController.registerCommandCallback(null)
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        binding.playerView.player = null
        binding.playerViewTexture.player = null
    }

    // ------------------------------------------------------------------
    // ExoPlayer listener -> controller state reporting + UI
    // ------------------------------------------------------------------
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> {
                    showCenterStatus(getString(R.string.player_buffering), true)
                    PlaybackController.updateTransportState(PlaybackController.TransportState.TRANSITIONING)
                }
                Player.STATE_READY -> {
                    hideCenterStatus()
                    cancelStreamEndTimer()
                    // P0-3: playback recovered — clear any pending reconnect attempts.
                    reconnectAttempts = 0
                    ui.removeCallbacks(reconnectRunnable)
                    val p = player
                    val st = if (p?.playWhenReady == true)
                        PlaybackController.TransportState.PLAYING
                    else PlaybackController.TransportState.PAUSED_PLAYBACK
                    PlaybackController.updateTransportState(st)
                    updatePlayStateLabel()
                }
                Player.STATE_ENDED -> {
                    updateProgress(forceHistoryWrite = true)
                    if (PlaybackController.currentIsDouyinCast) {
                        // 抖音自动连播依赖 STOPPED 状态触发下一条视频：
                        // 控制点轮询 GetTransportInfo 看到 STOPPED → 发送下一条 SetAVTransportURI。
                        // 之前上报 PAUSED_PLAYBACK 导致抖音判定为「用户暂停」，不触发自动连播。
                        // 不调用 scheduleStreamEndTimer，保持播放器存活等待下一条 URI。
                        PlaybackController.updateTransportState(PlaybackController.TransportState.STOPPED)
                        showCenterStatus("等待下一条短视频…", false)
                        return
                    }
                    if (maybePromptContinueQueue()) return
                    // 若当前播放来自「稍后播放队列」，尝试自动播放下一条。
                    if (tryAdvanceQueueOnEnded()) return
                    PlaybackController.updateTransportState(PlaybackController.TransportState.STOPPED)
                    scheduleStreamEndTimer()
                }
                Player.STATE_IDLE -> {
                    PlaybackController.updateTransportState(PlaybackController.TransportState.STOPPED)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayStateLabel()
            if (isPlaying) {
                PlaybackController.updateTransportState(PlaybackController.TransportState.PLAYING)
                cancelStreamEndTimer()
                startProgressUpdates()
            } else {
                // Paused / stopped: refresh once, then halt the polling timer.
                updateProgress(forceHistoryWrite = true)
                stopProgressUpdates()
            }
        }

        /**
         * 首帧渲染回调：播放开始后异步用 MediaMetadataRetriever 提取第 3 秒画面，
         * 保存为历史缩略图；失败时再保留默认兜底图，不影响播放主流程。
         */
        override fun onRenderedFirstFrame() {
            if (currentUri.isBlank()) return
            ui.removeCallbacks(historyScreenshotRunnable)
            ui.postDelayed(historyScreenshotRunnable, HISTORY_SCREENSHOT_DELAY_MS)
        }

        override fun onPlayerError(error: PlaybackException) {
            when (error.errorCode) {
                // P0-4: DRM-protected content (e.g. 爱奇艺/腾讯 高清) can never be
                // decrypted by a standard DLNA renderer — tell the user plainly.
                PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
                PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
                PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
                PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
                PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
                PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
                PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
                PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> {
                    showCenterStatus(getString(R.string.player_error_drm), false)
                    PlaybackController.updateTransportState(PlaybackController.TransportState.STOPPED)
                    scheduleStreamEndTimer()
                }
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    recoverBehindLiveWindow()
                }
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> {
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        scheduleReconnect()
                    } else {
                        showRetryCenterStatus(getString(R.string.player_error_decode))
                        PlaybackController.updateTransportState(PlaybackController.TransportState.STOPPED)
                    }
                }
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                    // P0-3: transient network jitter — retry with bounded 2s reconnects instead of giving up after one attempt.
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        scheduleReconnect()
                    } else {
                        showRetryCenterStatus(getString(R.string.player_error_network))
                        PlaybackController.updateTransportState(PlaybackController.TransportState.STOPPED)
                    }
                }
                else -> {
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        scheduleReconnect()
                    } else {
                        showRetryCenterStatus(getString(R.string.player_error_generic, error.errorCodeName))
                        PlaybackController.updateTransportState(PlaybackController.TransportState.STOPPED)
                    }
                }
            }
        }
    }

    private fun captureHistoryThumbnail() {
        val uri = currentUri
        if (uri.isBlank() || isFinishing || isDestroyed || player == null) return
        // 已经拿到 DLNA 元数据封面时，视觉展示已经用封面回填，无需再截图覆写。
        if (!PlaybackController.currentArtworkPath().isNullOrBlank()) return
        // TV 端 SoC 硬解实例受限，MMR 独立开路解码 / 网络 seek 常常失败。
        // 主路径：直接从 ExoPlayer 已渲染的 PlayerView 抓当前画面：
        //   - TextureView 走 `TextureView.getBitmap()`（默认路径，TV 兼容性最好）
        //   - SurfaceView 走 `PixelCopy`（用户主动开启 SurfaceView 时）
        // 兜底：抓帧失败（如镜像模式 GONE / API<26 / surface 无效）再走 MMR。
        Thumbnails.captureFromPlayerView(activePlayerView) { bmp ->
            if (isFinishing || isDestroyed || player == null || uri != currentUri) {
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
                return@captureFromPlayerView
            }
            if (bmp != null) {
                // 保留一份引用给收藏立即取用；bitmap 由 Thumbnails.captureFromPlayerView 保证已缩放到目标尺寸。
                val prev = lastCapturedBitmap
                lastCapturedBitmap = bmp
                if (prev != null && prev !== bmp && !prev.isRecycled) prev.recycle()
                val file = Thumbnails.newHistoryFile(cacheDir)
                thumbSaveExecutor.execute {
                    val savedPath = try {
                        Thumbnails.saveJpeg(bmp, file)
                    } catch (_: Throwable) { null }
                    ui.post {
                        if (savedPath == null || isFinishing || isDestroyed || player == null || uri != currentUri) return@post
                        PlaybackController.updateHistoryThumb(uri, savedPath)
                    }
                }
            } else {
                // PixelCopy 失败：兜底走 MMR
                val file = Thumbnails.newHistoryFile(cacheDir)
                Thumbnails.extractThirdSecondFrameToFile(uri, file) { path ->
                    if (path == null || isFinishing || isDestroyed || player == null || uri != currentUri) return@extractThirdSecondFrameToFile
                    PlaybackController.updateHistoryThumb(uri, path)
                }
            }
        }
    }

    /**
     * 若投屏元数据中带有封面 URL（DIDL-Lite `upnp:albumArtURI`），异步下载到本地，
     * 成功后：
     *  1. 通过 [PlaybackController.updateHistoryArtwork] 写回历史条目 → 首页
     *     「投屏状态 - 播放中」和历史列表都会自然回显封面；
     *  2. 若当前节目已在收藏中，也同步刷新收藏条目的 thumbPath（同 URI 复用一份文件）。
     *
     * 下载参数：5s 超时、2MB 上限、UA=`CastTV/1.0`，由 [Thumbnails.downloadArtworkToFile] 保证。
     * 下载失败 / 无该字段时静默降级，交给后续截图流程处理。
     */
    private fun downloadArtworkIfPresent(uri: String) {
        if (uri.isBlank()) return
        val artworkUrl = PlaybackController.currentArtworkUrl() ?: return
        // 封面落盘到 filesDir 持久化目录（低存储被系统清 cache 时历史/收藏封面依然可用）。
        // 老 cacheDir 中已有文件不做迁移，下次覆盖式下载到 filesDir 即可。
        val file = Thumbnails.artworkFileFor(Thumbnails.artworkDirPersistent(filesDir), artworkUrl)
        Thumbnails.downloadArtworkToFile(artworkUrl, file) { localPath ->
            if (localPath.isNullOrBlank()) return@downloadArtworkToFile
            if (isFinishing || isDestroyed) return@downloadArtworkToFile
            // 播放已切换到新 URI 就不再写回旧条目。
            if (uri != currentUri && uri != PlaybackController.currentUri) return@downloadArtworkToFile
            PlaybackController.updateHistoryArtwork(uri, localPath)
            // 同 URI 若在收藏中，把封面写入收藏 artworkPath（只写 artworkPath，不覆盖截图 thumbPath）。
            try {
                if (favoritesStore.contains(uri)) {
                    favoritesStore.updateItemArtwork(uri, localPath)
                }
            } catch (_: Throwable) { /* 收藏兜底：忽略异常 */ }
        }
    }

    /**
     * ExoPlayer 官方推荐：直播窗口落后时直接跳到 live edge 并重新 prepare，避免提示错误后卡死。
     */
    private fun recoverBehindLiveWindow() {
        val p = player ?: return
        cancelStreamEndTimer()
        reconnectAttempts = 0
        showCenterStatus(getString(R.string.player_reconnecting_attempt, 1, MAX_RECONNECT_ATTEMPTS), true)
        PlaybackController.updateTransportState(PlaybackController.TransportState.TRANSITIONING)
        p.seekToDefaultPosition()
        p.prepare()
        p.playWhenReady = true
    }

    /**
     * P0-3: schedule the next reconnect attempt with a fixed 2s delay.
     * While retrying we stay in TRANSITIONING and do NOT arm the stream-end watchdog,
     * so a brief Wi-Fi drop no longer bounces to standby.
     */
    private fun scheduleReconnect() {
        reconnectAttempts++
        val delay = RECONNECT_DELAY_MS
        cancelStreamEndTimer()
        showCenterStatus(
            getString(R.string.player_reconnecting_attempt, reconnectAttempts, MAX_RECONNECT_ATTEMPTS),
            true
        )
        PlaybackController.updateTransportState(PlaybackController.TransportState.TRANSITIONING)
        ui.removeCallbacks(reconnectRunnable)
        ui.postDelayed(reconnectRunnable, delay)
    }

    private fun doReconnect() {
        val p = player ?: return
        if (currentUri.isBlank()) return
        val resumePosition = max(0L, p.currentPosition)
        val wasLive = p.isCurrentMediaItemLive
        p.setMediaItem(buildMediaItem(currentUri))
        if (wasLive) {
            p.seekToDefaultPosition()
        } else if (resumePosition > 0L) {
            p.seekTo(resumePosition)
        }
        p.prepare()
        p.playWhenReady = true
    }

    // ------------------------------------------------------------------
    // DLNA command callbacks (invoked on the main thread by the controller)
    // ------------------------------------------------------------------
    private val commandCallback = object : PlaybackController.CommandCallback {
        override fun onSetUri(uri: String, title: String) {
            returnSkipPageReload = false
            this@PlayerActivity.title = title
            // P0-3: ignore a redundant SetAVTransportURI for the same source that
            // is already loading/playing, to avoid a restart flicker ("重复起播").
            val sameSource = uri == this@PlayerActivity.currentUri
            val active = player?.let {
                it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_BUFFERING
            } == true
            if (sameSource && active) {
                updateSourceLabel()
                return
            }
            if (PlaybackController.queuePlaybackActive && this@PlayerActivity.currentUri.isNotBlank() && uri != this@PlayerActivity.currentUri) {
                PlaybackController.interruptedQueueUri = this@PlayerActivity.currentUri
                PlaybackController.queuePlaybackActive = false
                queueStore.setStatusByUri(this@PlayerActivity.currentUri, PlayQueueStore.Status.PENDING)
            }
            this@PlayerActivity.currentUri = uri
            currentMimeType = ""
            httpReferer = ""
            httpUserAgent = ""
            updateHttpRequestHeaders()
            reconnectAttempts = 0
            // 新 URI：丢弃旧首帧截图缓存和待执行的延迟截图任务，等待新的 onRenderedFirstFrame 回填。
            lastCapturedBitmap = null
            ui.removeCallbacks(historyScreenshotRunnable)
            ui.removeCallbacks(reconnectRunnable)
            cancelStreamEndTimer()
            // 优先按 DLNA 元数据的封面 URL 下载封面图；成功后回填历史 & 收藏，
            // 首页 castStatus 卡片会自然通过 currentThumbPath() 拿到封面。
            downloadArtworkIfPresent(uri)
            player?.let {
                it.setMediaItem(buildMediaItem(uri))
                it.prepare()
                it.playWhenReady = true
            }
            updateSourceLabel()
        }

        override fun onPlay() { player?.play() }
        override fun onPause() { player?.pause() }
        override fun onStop() {
            // 抖音等控制端切换视频时常按 Stop -> SetAVTransportURI -> Play 连续下发。
            // Stop 不能立即 finish 播放页，否则新 URI 可能落在 Activity 销毁/重建窗口内，
            // 导致 command callback 丢失或起播超时。这里只停止当前播放器并保留页面，
            // 等后续 SetAVTransportURI + Play 在同一播放器实例上完成快速切换。
            ui.removeCallbacks(reconnectRunnable)
            cancelStreamEndTimer()
            player?.stop()
            PlaybackController.updateTransportState(PlaybackController.TransportState.STOPPED)
        }
        override fun onSeek(positionMs: Long) { player?.seekTo(positionMs) }
        // 音量完全跟随系统：手机端 DLNA SetVolume/SetMute 也直接作用于系统媒体音量。
        override fun onSetVolume(volume: Int) { applySystemVolumePercent(volume) }
        override fun onSetMute(mute: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(
                    android.media.AudioManager.STREAM_MUSIC,
                    if (mute) android.media.AudioManager.ADJUST_MUTE
                    else android.media.AudioManager.ADJUST_UNMUTE,
                    0
                )
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(android.media.AudioManager.STREAM_MUSIC, mute)
            }
            showVolume(systemVolumeRatio())
        }
    }

    // ------------------------------------------------------------------
    // UI updates
    // ------------------------------------------------------------------
    private fun updateSourceLabel() {
        val text = if (sourceHint.isBlank()) {
            title.ifBlank { getString(R.string.standby_ready) }
        } else {
            val from = getString(R.string.player_source_from, sourceHint)
            if (title.isBlank()) from else "$from - $title"
        }
        binding.textSource.text = text
    }

    private fun updatePlayStateLabel() {
        val playing = player?.isPlaying == true
        binding.textPlayState.text = if (playing) "▶ 播放中" else "⏸ 已暂停"
    }

    private fun updateProgress(forceHistoryWrite: Boolean = false) {
        val p = player ?: return
        val dur = max(0L, p.duration.let { if (it == androidx.media3.common.C.TIME_UNSET) 0L else it })
        val pos = max(0L, p.currentPosition)
        // UI 仍保持 1s tick；历史进度写入交给 PlaybackController 在后台线程按阈值节流。
        PlaybackController.updateProgress(pos, dur, forceHistoryWrite)
        if (!seeking) {
            binding.textPosition.text = formatTime(pos)
            binding.textDuration.text = formatTime(dur)
            if (dur > 0) {
                binding.seekBar.max = (dur / 1000).toInt()
                binding.seekBar.progress = (pos / 1000).toInt()
            }
        }
    }

    private fun setupSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.textPosition.text = formatTime(progress * 1000L)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) { seeking = true }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                seeking = false
                player?.seekTo(sb.progress * 1000L)
            }
        })
    }

    private fun showCenterStatus(text: String, showSpinner: Boolean) {
        binding.centerStatus.isFocusable = false
        binding.centerStatus.isClickable = false
        binding.centerStatus.setOnClickListener(null)
        binding.centerStatus.setOnKeyListener(null)
        binding.textCenterStatus.text = text
        binding.progressBuffering.visibility = if (showSpinner) View.VISIBLE else View.GONE
        binding.centerStatus.visibility = View.VISIBLE
    }

    private fun showRetryCenterStatus(text: String) {
        cancelStreamEndTimer()
        binding.textCenterStatus.text = "$text\n按 OK 重试"
        binding.progressBuffering.visibility = View.GONE
        binding.centerStatus.visibility = View.VISIBLE
        binding.centerStatus.isFocusable = true
        binding.centerStatus.isClickable = true
        val retryAction = {
            reconnectAttempts = 0
            doReconnect()
            true
        }
        binding.centerStatus.setOnClickListener { retryAction() }
        binding.centerStatus.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) retryAction() else false
        }
        binding.centerStatus.requestFocus()
    }

    private fun hideCenterStatus() {
        binding.centerStatus.visibility = View.GONE
    }

    // ------------------------------------------------------------------
    // Touch gestures for phone/tablet receiver mode
    // ------------------------------------------------------------------
    private fun setupTouchGestures() {
        fun bindTouchTarget(view: View) {
            view.isClickable = true
            view.isLongClickable = true
            view.setOnClickListener {
                if (isSidebarVisible() || isQueuePanelVisible()) return@setOnClickListener
                if (binding.controlBar.visibility == View.VISIBLE) {
                    ui.removeCallbacks(hideControlsRunnable)
                    setControlBarVisible(false)
                } else {
                    showControlsTemporarily()
                }
            }
            view.setOnLongClickListener {
                if (!mirrorMode) showMobileActionMenu()
                true
            }
        }
        bindTouchTarget(binding.playerView)
        bindTouchTarget(binding.playerViewTexture)
        bindTouchTarget(binding.mirrorSurface)
    }

    private fun showMobileActionMenu() {
        if (currentUri.isBlank()) {
            android.widget.Toast.makeText(this, R.string.favorite_no_uri, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        setControlBarVisible(false)
        ui.removeCallbacks(hideControlsRunnable)
        hideQueuePanel()
        hideSidebar()

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun menuItem(icon: String, label: String, accentColor: Int, action: () -> Unit): android.widget.TextView {
            return android.widget.TextView(this).apply {
                text = "$icon  $label"
                textSize = 16f
                setTextColor(ContextCompat.getColor(this@PlayerActivity, accentColor))
                setBackgroundResource(R.drawable.bg_dialog_focus_item)
                isFocusable = true
                isClickable = true
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                setOnClickListener { action() }
            }
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            setBackgroundResource(R.drawable.bg_card)
        }
        val titleView = android.widget.TextView(this).apply {
            text = "播放操作"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_dialog_crayon_header)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        container.addView(titleView, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        var dialog: android.app.AlertDialog? = null
        val favoriteItem = menuItem("⭐", "收藏到合集", R.color.text_primary) {
            dialog?.dismiss()
            showFavoriteAddDialog()
        }
        val queueItem = menuItem("🕘", "稍后播放", R.color.crayon_yellow) {
            dialog?.dismiss()
            addCurrentToQueueFromTouchMenu()
        }
        val itemLp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        container.addView(favoriteItem, itemLp)
        container.addView(queueItem, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        dialog = android.app.AlertDialog.Builder(this)
            .setView(container)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        favoriteItem.requestFocus()
    }

    private fun addCurrentToQueueFromTouchMenu() {
        if (currentUri.isBlank()) {
            android.widget.Toast.makeText(this, R.string.favorite_no_uri, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val itemTitle = title.ifBlank { sourceHint.ifBlank { "未命名视频" } }
        val id = queueStore.add(itemTitle, currentUri, sourceHint.ifBlank { "player" })
        val toastText = if (id.isNotBlank()) "已加入稍后播放" else "稍后播放添加失败"
        refreshQueuePanel()
        updateSidebarActionStates()
        android.widget.Toast.makeText(this, toastText, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // Control bar auto-hide
    // ------------------------------------------------------------------
    private fun setControlBarVisible(visible: Boolean) {
        binding.controlBar.visibility = if (visible) View.VISIBLE else View.GONE
        binding.topOverlay.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showControlsTemporarily() {
        setControlBarVisible(true)
        ui.removeCallbacks(hideControlsRunnable)
        ui.postDelayed(hideControlsRunnable, CONTROL_HIDE_MS)
    }

    // ------------------------------------------------------------------
    // Volume overlay
    // ------------------------------------------------------------------
    /** 读取系统当前媒体音量占最大值的比例（0..1），用于让播放音量跟随系统。 */
    private fun systemVolumeRatio(): Float {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val maxVol = audioManager.getStreamMaxVolume(stream)
        if (maxVol <= 0) return 1f
        val curVol = audioManager.getStreamVolume(stream)
        return (curVol.toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * 完全跟随系统：遥控器音量键直接调节系统媒体音量（STREAM_MUSIC），
     * 退出 App 后系统音量保持一致。用自绘浮层反馈，不弹系统默认 UI。
     */
    private fun changeSystemVolume(raise: Boolean) {
        val direction = if (raise) android.media.AudioManager.ADJUST_RAISE
        else android.media.AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, 0)
        showVolume(systemVolumeRatio())
    }

    /** 将 0..100 的目标音量应用到系统媒体音量（供 DLNA 手机端 SetVolume 使用）。 */
    private fun applySystemVolumePercent(percent: Int) {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val maxVol = audioManager.getStreamMaxVolume(stream)
        if (maxVol <= 0) return
        val target = Math.round(percent.coerceIn(0, 100) / 100f * maxVol)
        audioManager.setStreamVolume(stream, target, 0)
        showVolume(systemVolumeRatio())
    }

    /** Show the auto-hiding volume overlay reflecting the current 0..1 volume. */
    private fun showVolume(volume: Float) {
        val percent = (volume * 100f).toInt()
        binding.volumeBar.progress = percent
        binding.textVolume.text = "$percent%"
        binding.imgVolume.setImageResource(
            if (percent == 0) R.drawable.ic_volume_mute else R.drawable.ic_volume
        )
        binding.volumeOverlay.visibility = View.VISIBLE
        ui.removeCallbacks(hideVolumeRunnable)
        ui.postDelayed(hideVolumeRunnable, VOLUME_HIDE_MS)
    }

    // ------------------------------------------------------------------
    // Stream-end / stall watchdog -> return to standby
    // ------------------------------------------------------------------
    private fun scheduleStreamEndTimer() {
        cancelStreamEndTimer()
        ui.postDelayed(streamEndRunnable, STREAM_END_TIMEOUT_MS)
    }

    private fun cancelStreamEndTimer() {
        ui.removeCallbacks(streamEndRunnable)
    }

    // ------------------------------------------------------------------
    // Remote control handling
    // ------------------------------------------------------------------
    private fun isFocusInside(container: View): Boolean {
        val focused = currentFocus ?: return false
        if (focused === container) return true
        var parent: android.view.ViewParent? = focused.parent
        while (parent is View) {
            if (parent === container) return true
            parent = (parent as View).parent
        }
        return false
    }

    private fun seekByStep(forward: Boolean) {
        val p = player ?: return
        val current = max(0L, p.currentPosition)
        if (forward) {
            val duration = if (p.duration == androidx.media3.common.C.TIME_UNSET) Long.MAX_VALUE else p.duration
            p.seekTo(min(duration, current + SEEK_STEP_MS))
        } else {
            p.seekTo(max(0L, current - SEEK_STEP_MS))
        }
        updateProgress()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            // 部分 TV 遥控器的 MENU 键会连续派发 repeat ACTION_DOWN；如果每次都 toggle，
            // 侧边菜单会在一次按键中被打开又立刻收起，看起来像“没有反应”。
            // 这里只在首个 DOWN 触发一次，UP 继续消费，避免系统菜单或播放器控件抢走事件。
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !mirrorMode) {
                toggleSidebar()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && !isSidebarVisible() && !isQueuePanelVisible()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    // 全屏播放区获得焦点时，左右键必须由播放器优先处理为 seek；
                    // 若焦点已在控制栏内部，则保留系统默认焦点移动/SeekBar 行为。
                    if (!isFocusInside(binding.controlBar)) {
                        showControlsTemporarily()
                        seekByStep(forward = false)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (!isFocusInside(binding.controlBar)) {
                        showControlsTemporarily()
                        seekByStep(forward = true)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 菜单键：呼出 / 收起右侧侧边弹窗（镜像模式无可收藏内容，忽略）。
        // dispatchKeyEvent 正常会先消费；这里保留兜底，同样过滤 repeat，避免一次按键内反复开关。
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event?.repeatCount == 0 && !mirrorMode) toggleSidebar()
            return true
        }
        if (isQueuePanelVisible()) {
            val inList = isFocusInside(binding.queueList)
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideQueuePanel()
                    binding.btnSidebarQueue.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // v1.1.105：稍后播放面板在右侧菜单左侧展开，焦点还在菜单/主区域时按 ← 进入队列列表。
                    if (!inList) {
                        focusQueueList()
                        return true
                    }
                    return super.onKeyDown(keyCode, event)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 焦点已在列表内：→ 收起面板并回到菜单入口；否则也尝试进入列表。
                    if (inList) {
                        hideQueuePanel()
                        binding.btnSidebarQueue.requestFocus()
                    } else {
                        focusQueueList()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                    return super.onKeyDown(keyCode, event)
                else -> return true
            }
        }
        // 侧边弹窗展开时：让焦点/点击由弹窗内控件处理，仅拦截返回键用于收起弹窗，
        // 其余按键统一吞掉，避免误触发快进/音量等播放操作。
        if (isSidebarVisible()) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    hideQueuePanel()
                    hideSidebar()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    return super.onKeyDown(keyCode, event)
                else -> return true
            }
        }
        showControlsTemporarily()
        val p = player
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                p?.let { it.seekTo(max(0L, it.currentPosition - SEEK_STEP_MS)) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                p?.let {
                    val dur = if (it.duration == androidx.media3.common.C.TIME_UNSET) Long.MAX_VALUE else it.duration
                    it.seekTo(min(dur, it.currentPosition + SEEK_STEP_MS))
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> {
                changeSystemVolume(raise = true)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                changeSystemVolume(raise = false)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                p?.let { if (it.isPlaying) it.pause() else it.play() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p?.pause(); return true }
            KeyEvent.KEYCODE_BACK -> {
                // 主动退出播放器 UI（用户按返回键）：
                // - 记录退出时进度（毫秒）以便首页/历史续播；
                // - 不发送 STOP、不清空媒体状态，避免被误判为投屏结束。
                // 说明：退出后播放器会在 onStop 释放 ExoPlayer，因此这里只保存状态用于「首页续播」。
                updateProgress(forceHistoryWrite = true)
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------
    // 收藏侧边弹窗 & 收藏保存
    // ------------------------------------------------------------------
    private fun isSidebarVisible(): Boolean =
        binding.sidebarPanel.visibility == View.VISIBLE

    private fun toggleSidebar() {
        if (isSidebarVisible()) hideSidebar() else showSidebar()
    }

    /**
     * best-effort 申请本地文件读取权限。仅在 Android 9（API 28）及以下发起请求，
     * 高版本系统采用分区存储、收藏写入 App 私有目录，无需该权限。被拒绝也不影响功能。
     */
    private fun maybeRequestStoragePermission() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return
        val perm = android.Manifest.permission.READ_EXTERNAL_STORAGE
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(this, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            try {
                storagePermLauncher.launch(perm)
            } catch (e: Exception) {
                // 部分 TV 设备无权限弹窗，忽略即可。
            }
        }
    }

    /** 从屏幕右侧滑出侧边弹窗，并把焦点落到收藏按钮上。 */
    private fun showSidebar() {
        maybeRequestStoragePermission()
        val panel = binding.sidebarPanel
        // 展开侧边栏时收起底部控制条，避免遥控器焦点冲突。
        setControlBarVisible(false)
        ui.removeCallbacks(hideControlsRunnable)
        panel.visibility = View.VISIBLE
        // 打开菜单时刷新「收藏 / 稍后播放」按钮选中态：已收藏 / 已在队列则文字图标暖黄。
        updateSidebarActionStates()
        panel.post {
            panel.translationX = panel.width.toFloat()
            panel.animate().translationX(0f).setDuration(220L).start()
            binding.btnSidebarFavorite.requestFocus()
        }
    }

    /**
     * 根据「当前视频是否已收藏 / 是否已在稍后播放队列」刷新侧边栏两个按钮的选中态。
     * 选中态由 isSelected 驱动，配合 duplicateParentState 让子 ImageView/TextView 呈现暖黄色。
     * 队列判断为内存操作直接同步；收藏判断涉及索引读取，放后台线程避免主线程 I/O。
     */
    private fun updateSidebarActionStates() {
        val uriSnapshot = currentUri
        if (uriSnapshot.isBlank()) {
            binding.btnSidebarFavorite.isSelected = false
            binding.btnSidebarQueue.isSelected = false
            return
        }
        binding.btnSidebarQueue.isSelected =
            try { queueStore.findByUri(uriSnapshot) != null } catch (_: Throwable) { false }
        Thread({
            val fav = try { favoritesStore.contains(uriSnapshot) } catch (_: Throwable) { false }
            ui.post {
                if (!isFinishing && currentUri == uriSnapshot) {
                    binding.btnSidebarFavorite.isSelected = fav
                }
            }
        }, "sidebar-action-state").start()
    }

    /** 向右滑出收起侧边弹窗。 */
    private fun hideSidebar() {
        hideQueuePanel()
        val panel = binding.sidebarPanel
        if (panel.visibility != View.VISIBLE) return
        panel.animate()
            .translationX(panel.width.toFloat())
            .setDuration(200L)
            .withEndAction {
                panel.visibility = View.GONE
                panel.translationX = 0f
            }
            .start()
    }

    /** 屏幕中心弹出收藏保存弹窗：自定义标题（必填）+ 资源地址只读展示。 */
    private fun showFavoriteAddDialog() {
        if (currentUri.isBlank()) {
            android.widget.Toast.makeText(this, R.string.favorite_no_uri,
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 小米/HyperOS 对输入法弹起时的主线程卡顿非常敏感。收藏弹窗需要读取 index.json / 默认合集，
        // 这些 IO 不能和 EditText 首次聚焦、键盘弹起抢主线程，先放到后台线程完成后再展示弹窗。
        val uriSnapshot = currentUri
        Thread({
            val collections = try { favoritesStore.collectionsInfo() } catch (_: Throwable) { emptyList() }
            val defaultId = try { favoritesStore.defaultCollectionId() } catch (_: Throwable) { "" }
            ui.post {
                if (!isFinishing && currentUri == uriSnapshot) {
                    showFavoriteAddDialogLoaded(collections, defaultId)
                }
            }
        }, "favorite-dialog-load").start()
    }

    private fun dialogButton(label: String, click: () -> Unit): android.widget.TextView = android.widget.TextView(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        val accent = ThemeManager.currentPalette(this@PlayerActivity).accent
        fun refresh(focused: Boolean) {
            val selected = isSelected
            setTextColor(if (selected) accent else Color.argb(235, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = 10.dp().toFloat()
                setStroke(if (focused) 2.dp() else 1.dp(), if (focused) accent else Color.argb(170, 210, 214, 222))
                setColor(Color.argb(52, 32, 34, 40))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, hasFocus ->
            refresh(hasFocus)
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 10)
        }
        setOnClickListener { click() }
    }

    private fun showFavoriteAddDialogLoaded(
        collections: List<com.bd.casttv.favorites.FavoritesStore.CollectionInfo>,
        defaultId: String
    ) {
        if (currentUri.isBlank()) return
        // 收藏缩略图统一改为 MediaMetadataRetriever 抽取视频第 3 秒画面；
        // 异步保存，保存成功后回填收藏数据。
        val dialogBinding = DialogFavoriteAddBinding.inflate(layoutInflater)
        val favoritePalette = ThemeManager.currentPalette(this)
        val favoriteAccent = favoritePalette.accent
        // 对齐壁纸设置弹窗：主题渐变外框、暖色强调与深色内容控件均跟随当前主题。
        dialogBinding.root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            favoritePalette.dialogTitleGradient
        ).apply {
            cornerRadius = 18.dp().toFloat()
            setStroke(2.dp(), favoriteAccent)
        }
        dialogBinding.favoriteAddHeader.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            favoritePalette.dialogTitleGradient
        ).apply { cornerRadius = 10.dp().toFloat() }
        dialogBinding.favoriteAddHeader.setTextColor(Color.WHITE)
        dialogBinding.favoriteTitleLabel.setTextColor(favoriteAccent)
        dialogBinding.favoriteCollectionLabel.setTextColor(favoriteAccent)
        dialogBinding.favoriteUriLabel.setTextColor(favoriteAccent)
        fun favoriteInputBackground(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = 10.dp().toFloat()
            setColor(Color.argb(52, 32, 34, 40))
            setStroke(if (focused) 3.dp() else 1.dp(), if (focused) favoriteAccent else Color.argb(170, 210, 214, 222))
        }
        dialogBinding.inputFavoriteTitle.background = favoriteInputBackground(false)
        dialogBinding.inputFavoriteTitle.setOnFocusChangeListener { view, hasFocus ->
            view.background = favoriteInputBackground(hasFocus)
            FocusFxHelper.applyFocusFxState(view, hasFocus, cornerRadiusDp = 10)
        }
        dialogBinding.textFavoriteUri.background = favoriteInputBackground(false)
        dialogBinding.textFavoriteUri.text = currentUri
        // 预填一个建议标题（当前节目名 / 来源），方便用户直接确认或修改。
        val suggested = title.ifBlank { sourceHint }
        if (suggested.isNotBlank()) {
            dialogBinding.inputFavoriteTitle.setText(suggested)
            dialogBinding.inputFavoriteTitle.setSelection(suggested.length)
        }
        // 小米/HyperOS 上 EditText 默认的 DPAD 左右键处理会经过系统 Editor/IME 链路；
        // 在播放器弹窗里连续移动光标时，容易与播放页按键分发、输入法同步调用叠加造成主线程卡顿甚至 ANR。
        // 标题输入框内的左右键只需要移动光标，这里直接在 View 层轻量消费，避免继续冒泡到 Dialog/Activity
        // 或触发额外系统输入法处理；不做任何收藏数据读写、SharedPreferences、网络或数据库操作。
        fun moveTitleCursor(keyCode: Int): Boolean {
            val input = dialogBinding.inputFavoriteTitle
            val editable = input.text ?: return true
            val length = editable.length
            val start = input.selectionStart.coerceIn(0, length)
            val end = input.selectionEnd.coerceIn(0, length)
            val current = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) min(start, end) else max(start, end)
            val next = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> (current - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> (current + 1).coerceAtMost(length)
                else -> current
            }
            if (next != start || next != end) input.setSelection(next)
            return true
        }
        dialogBinding.inputFavoriteTitle.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            ) {
                moveTitleCursor(keyCode)
            } else {
                false
            }
        }

        // 合集选择：回显所有合集，默认选中「默认合集」。以横向蜡笔小新 chip 内联展示，
        // 遥控器可直接左右移动聚焦、OK 选择——不再使用嵌套单选弹窗（在 TV 上会丢焦点）。
        var selectedIndex = collections.indexOfFirst { it.id == defaultId }.coerceAtLeast(0)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val chipViews = ArrayList<android.widget.TextView>(collections.size)
        fun styleChip(view: android.widget.TextView, focused: Boolean) {
            view.setTextColor(if (view.isSelected) favoriteAccent else Color.WHITE)
            view.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(if (focused) 2 else 1), if (focused) favoriteAccent else Color.argb(170, 210, 214, 222))
            }
        }
        fun refreshChipSelection() {
            chipViews.forEachIndexed { i, v ->
                v.isSelected = (i == selectedIndex)
                styleChip(v, v.hasFocus())
            }
        }
        var defaultChip: android.widget.TextView? = null
        collections.forEachIndexed { index, c ->
            val chip = android.widget.TextView(this).apply {
                text = c.name
                textSize = 15f
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                maxLines = 1
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isSelected = index == selectedIndex
                setOnClickListener {
                    selectedIndex = index
                    refreshChipSelection()
                }
                // v1.1.105：合集列表焦点即选中，遥控器左右移动到哪即选到哪，无需再按 OK。
                setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        selectedIndex = index
                        refreshChipSelection()
                    } else {
                        styleChip(view as android.widget.TextView, false)
                    }
                    FocusFxHelper.applyFocusFxState(view, hasFocus, cornerRadiusDp = 10)
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(10) }
            dialogBinding.collectionChipContainer.addView(chip, lp)
            chipViews.add(chip)
            styleChip(chip, false)
            if (index == selectedIndex) defaultChip = chip
        }
        // 让默认合集 chip 进入可见区域，方便遥控器直接从这里开始选择。
        defaultChip?.let { chip ->
            dialogBinding.collectionChipsScroll.post {
                dialogBinding.collectionChipsScroll.smoothScrollTo((chip.left - dp(40)).coerceAtLeast(0), 0)
            }
        }

        var dialog: android.app.AlertDialog? = null
        val actionRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val cancelButton = dialogButton("取消") { dialog?.dismiss() }
        val saveButton = dialogButton(getString(R.string.btn_save)) { }
        val buttonLp = android.widget.LinearLayout.LayoutParams(dp(118), dp(44)).apply {
            leftMargin = dp(12)
        }
        actionRow.addView(cancelButton, buttonLp)
        actionRow.addView(saveButton, android.widget.LinearLayout.LayoutParams(dp(132), dp(44)).apply {
            leftMargin = dp(12)
        })
        dialogBinding.root.addView(actionRow, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })

        dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogBinding.inputFavoriteTitle.requestFocus()
        // 弹窗底部内置保存按钮：标题必填校验通过后才保存并关闭。
        saveButton.setOnClickListener {
            val customTitle = dialogBinding.inputFavoriteTitle.text.toString().trim()
            if (customTitle.isBlank()) {
                android.widget.Toast.makeText(this, R.string.favorite_title_required,
                    android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 合集非必填：默认已选「默认合集」；异常兜底写入默认合集。
            val targetCollection = collections.getOrNull(selectedIndex)
            // 收藏缩略图：同 URI 复用同名文件；不存在时先保存收藏明细，随后异步抽取第 3 秒帧并回填 thumbPath。
            val favFile = Thumbnails.favoriteFileFor(filesDir, currentUri)
            val existingThumbPath = favFile.takeIf { it.exists() && it.length() > 0 }?.absolutePath
            // 优先复用 DLNA 元数据下载得到的封面；有则直接作为收藏 thumbPath，跳过后续截图。
            val artworkPath = PlaybackController.currentArtworkPath()?.takeIf {
                it.isNotBlank() && java.io.File(it).exists() && java.io.File(it).length() > 0
            }
            val favThumbPath: String? = existingThumbPath ?: artworkPath
            val shouldExtractFavoriteThumb = favThumbPath == null
            val favDurationMs = PlaybackController.durationMs.coerceAtLeast(0L)
            val favDescription = PlaybackController.currentDescription
            val favResolution = PlaybackController.currentResolution
            // ANR 优化（v1.1.131）：收藏写入（全局锁 + 写盘）移到后台线程；
            // 结果与后续 UI（Toast / 侧栏刷新 / 缩略图抽取 / 关闭弹窗）回到主线程执行。
            Thread {
                val result = if (targetCollection != null) {
                    favoritesStore.addToCollection(
                        targetCollection.id,
                        customTitle,
                        currentUri,
                        sourceHint,
                        favThumbPath,
                        favDurationMs,
                        favDescription,
                        favResolution
                    )
                } else {
                    favoritesStore.addToDefault(
                        customTitle,
                        currentUri,
                        sourceHint,
                        favThumbPath,
                        favDurationMs,
                        favDescription,
                        favResolution
                    )
                }
                ui.post {
                    val message = when (result) {
                        FavoritesStore.OpResult.SUCCESS -> getString(R.string.favorite_saved)
                        FavoritesStore.OpResult.LIMIT_TOTAL ->
                            getString(R.string.collection_total_limit_reached, FavoritesStore.MAX_TOTAL_FAVORITES)
                        FavoritesStore.OpResult.LIMIT_COLLECTION_ITEMS ->
                            getString(R.string.collection_item_limit_reached, FavoritesStore.MAX_ITEMS_PER_COLLECTION)
                        else -> getString(R.string.favorite_save_failed)
                    }
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
                    if (result == FavoritesStore.OpResult.SUCCESS) {
                        updateSidebarActionStates()
                        if (shouldExtractFavoriteThumb) {
                            val savedUri = currentUri
                            // 优先复用刚刚 PixelCopy 抓到的 lastCapturedBitmap（TV 端最稳，无需再申请硬解 / 二次网络请求）。
                            val cachedBmp = lastCapturedBitmap
                            if (cachedBmp != null && !cachedBmp.isRecycled) {
                                thumbSaveExecutor.execute {
                                    val savedPath = try {
                                        Thumbnails.saveJpeg(cachedBmp, favFile)
                                    } catch (_: Throwable) { null }
                                    if (!savedPath.isNullOrBlank()) {
                                        favoritesStore.updateItemThumb(savedUri, savedPath)
                                    }
                                }
                            } else {
                                // 无缓存则再尝试当前画面 PixelCopy / TextureView.getBitmap；仍失败最后兜底 MMR。
                                Thumbnails.captureFromPlayerView(activePlayerView) { bmp ->
                                    if (bmp != null) {
                                        thumbSaveExecutor.execute {
                                            val savedPath = try {
                                                Thumbnails.saveJpeg(bmp, favFile)
                                            } catch (_: Throwable) { null }
                                            // 收藏拿到画面后也同步缓存，方便下次立即复用。
                                            ui.post {
                                                val prev = lastCapturedBitmap
                                                if (prev == null || prev.isRecycled) {
                                                    lastCapturedBitmap = bmp
                                                } else if (!bmp.isRecycled && bmp !== prev) {
                                                    bmp.recycle()
                                                }
                                            }
                                            if (!savedPath.isNullOrBlank()) {
                                                favoritesStore.updateItemThumb(savedUri, savedPath)
                                            }
                                        }
                                    } else {
                                        Thumbnails.extractThirdSecondFrameToFile(savedUri, favFile) { path ->
                                            if (!path.isNullOrBlank()) favoritesStore.updateItemThumb(savedUri, path)
                                        }
                                    }
                                }
                            }
                        }
                        dialog?.dismiss()
                        hideSidebar()
                    }
                }
            }.start()
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    // ---------------- 「稍后播放」队列联动 ----------------
    private fun setupQueuePanel() {
        binding.queueList.layoutManager = LinearLayoutManager(this)
        binding.queueList.adapter = queueAdapter
        try { queueStore.addListener(queueChangedListener) } catch (_: Throwable) {}
        refreshQueuePanel()
    }

    private fun isQueuePanelVisible(): Boolean = binding.queuePanel.visibility == View.VISIBLE

    private fun showQueuePanel(focusList: Boolean) {
        refreshQueuePanel()
        val panel = binding.queuePanel
        if (panel.visibility != View.VISIBLE) {
            panel.visibility = View.VISIBLE
            panel.post {
                panel.translationX = panel.width.toFloat()
                panel.animate().translationX(0f).setDuration(220L).start()
                if (focusList) focusQueueList()
            }
        } else if (focusList) {
            focusQueueList()
        }
    }

    private fun hideQueuePanel() {
        val panel = binding.queuePanel
        if (panel.visibility != View.VISIBLE) return
        panel.animate()
            .translationX(panel.width.toFloat())
            .setDuration(200L)
            .withEndAction {
                panel.visibility = View.GONE
                panel.translationX = 0f
            }
            .start()
    }

    private fun focusQueueList() {
        binding.queueList.post {
            val currentIndex = queueStore.all().indexOfFirst { it.uri == currentUri }.coerceAtLeast(0)
            val holder = binding.queueList.findViewHolderForAdapterPosition(currentIndex)
            if (holder != null) holder.itemView.requestFocus() else binding.queueList.requestFocus()
        }
    }

    private fun refreshQueuePanel() {
        val items = queueStore.all()
        queueAdapter.submit(items, currentUri)
        binding.textQueueHint.text = if (items.isEmpty()) "队列为空，可从收藏/历史/手机端加入" else "返回收起 · 当前播放会高亮"
    }

    private fun playQueueItem(item: PlayQueueStore.QueueItem) {
        // 手动切换保持既有语义：被选中的变 PLAYING，原 PLAYING 会在 PlayQueueStore 内转为 FINISHED。
        queueStore.setStatus(item.id, PlayQueueStore.Status.PLAYING)
        playQueueItemInternal(item, startPositionMs = 0L, toastText = "▶️ 已切换：${item.title}")
    }

    /**
     * 切换到稍后播放队列中的某条资源。
     * startPositionMs > 0 时用于「播放结束后继续 PLAYING 状态资源」的续播；
     * PENDING 资源始终从 0 开始。
     */
    private fun playQueueItemInternal(
        item: PlayQueueStore.QueueItem,
        startPositionMs: Long,
        toastText: String
    ) {
        PlaybackController.queuePlaybackActive = true
        PlaybackController.interruptedQueueUri = ""
        currentUri = item.uri
        title = item.title
        sourceHint = item.source.ifBlank { "queue" }
        currentMimeType = ""
        httpReferer = ""
        httpUserAgent = ""
        updateHttpRequestHeaders()
        reconnectAttempts = 0
        lastCapturedBitmap = null
        ui.removeCallbacks(historyScreenshotRunnable)
        ui.removeCallbacks(reconnectRunnable)
        cancelStreamEndTimer()
        player?.let {
            it.setMediaItem(buildMediaItem(item.uri), startPositionMs.coerceAtLeast(0L))
            it.prepare()
            it.playWhenReady = true
        }
        updateSourceLabel()
        refreshQueuePanel()
        android.widget.Toast.makeText(this, toastText, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun maybePromptContinueQueue(): Boolean {
        val interrupted = PlaybackController.interruptedQueueUri
        if (interrupted.isBlank()) return false
        val pending = queueStore.pending()
        if (pending.isEmpty()) {
            PlaybackController.interruptedQueueUri = ""
            PlaybackController.queuePlaybackActive = false
            return false
        }
        cancelStreamEndTimer()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("继续播放队列？")
            .setMessage("外部投屏已结束，要继续播放刚才的稍后播放队列吗？")
            .setPositiveButton("继续") { d, _ ->
                PlaybackController.interruptedQueueUri = ""
                val next = queueStore.nextPending()
                if (next != null) playQueueItem(next)
                d.dismiss()
            }
            .setNegativeButton("取消") { d, _ ->
                PlaybackController.interruptedQueueUri = ""
                PlaybackController.queuePlaybackActive = false
                d.dismiss()
                scheduleStreamEndTimer()
            }
            .setOnCancelListener {
                PlaybackController.interruptedQueueUri = ""
                PlaybackController.queuePlaybackActive = false
                scheduleStreamEndTimer()
            }
            .show()
        return true
    }

    private inner class QueueAdapter(
        private val onSelect: (PlayQueueStore.QueueItem) -> Unit
    ) : RecyclerView.Adapter<QueueAdapter.VH>() {
        private val items = mutableListOf<PlayQueueStore.QueueItem>()
        private var playingUri: String = ""

        fun submit(newItems: List<PlayQueueStore.QueueItem>, current: String) {
            items.clear()
            items.addAll(newItems)
            playingUri = current
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val text = android.widget.TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
                background = ContextCompat.getDrawable(parent.context, R.drawable.bg_queue_item)
                isClickable = true
                isFocusable = true
                setPadding(16.dp(), 13.dp(), 16.dp(), 13.dp())
                setTextColor(ContextCompat.getColor(parent.context, R.color.text_primary))
                textSize = 14f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            return VH(text)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val selected = item.uri == playingUri || item.status == PlayQueueStore.Status.PLAYING
            val prefix = if (selected) "▶  " else "   "
            holder.text.text = prefix + item.title.ifBlank { item.uri }
            holder.text.isSelected = selected
            refreshQueueItemVisual(holder.text)
            holder.text.setOnClickListener { onSelect(item) }
            holder.text.setOnFocusChangeListener { view, hasFocus ->
                refreshQueueItemVisual(holder.text)
                if (hasFocus) {
                    view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), false)
                }
            }
        }

        private fun refreshQueueItemVisual(text: android.widget.TextView) {
            text.setTextColor(
                ContextCompat.getColor(
                    text.context,
                    if (text.isSelected) R.color.crayon_yellow else R.color.text_primary
                )
            )
            text.refreshDrawableState()
        }

        inner class VH(val text: android.widget.TextView) : RecyclerView.ViewHolder(text)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /**
     * 当前节目播放结束（STATE_ENDED）时被调用：
     * 1) 先把当前已播完的资源标记为 FINISHED；
     * 2) 若「稍后播放列表」仍有数据，按持久化顺序取下一条（优先 PLAYING，其次第一条 PENDING）自动续播；
     * 3) 若正在续播的是 PLAYING 资源，则从历史 positionMs 续播，否则从头播放；
     * 4) 队列已无待播内容：保持结束态（若本次本就是队列连播，则给出「队列播放完毕」提示并退出）。
     *
     * v1.1.105：不再强制要求本次播放来自队列——只要稍后播放列表里还有未播完的数据，
     * 播放结束后就按持久化顺序自动播放下一条。
     */
    private fun tryAdvanceQueueOnEnded(): Boolean {
        return try {
            val store = com.bd.casttv.queue.PlayQueueStore.get(this)
            val wasQueuePlayback = PlaybackController.queuePlaybackActive

            // 当前视频已经自然播完：若它属于队列，标记为 FINISHED。
            if (currentUri.isNotBlank()) {
                store.setStatusByUri(currentUri, com.bd.casttv.queue.PlayQueueStore.Status.FINISHED)
            }

            // 方案 B：队列播放顺序按当前 SortConfig 排序偏好计算，与 WatchLaterPage 展示顺序保持一致，
            // 保证 UI 上第 1 条 = 实际续播时的下一条。
            //   1) 优先 PLAYING（正常情况下只有一条，若多条则按排序偏好取首个）；
            //   2) 否则从排序后的列表里取第一条 PENDING。
            val sortedAll = com.bd.casttv.queue.PlayQueueStore.SortConfig.apply(store.all())
            val playing = sortedAll.firstOrNull { it.status == com.bd.casttv.queue.PlayQueueStore.Status.PLAYING }
            val next = playing
                ?: sortedAll.firstOrNull { it.status == com.bd.casttv.queue.PlayQueueStore.Status.PENDING }
            if (next == null || next.uri == currentUri) {
                // 稍后播放列表已无待播内容：保持结束态。
                if (wasQueuePlayback) {
                    showQueueFinishedOverlay()
                    PlaybackController.queuePlaybackActive = false
                    return true
                }
                return false
            }

            PlaybackController.queuePlaybackActive = true
            val startPositionMs = if (playing != null) {
                PlaybackController.history()
                    .firstOrNull { it.uri == next.uri }
                    ?.positionMs
                    ?.coerceAtLeast(0L) ?: 0L
            } else {
                // PENDING 资源从头开始，并切为 PLAYING。
                store.setStatus(next.id, com.bd.casttv.queue.PlayQueueStore.Status.PLAYING)
                0L
            }

            val toast = if (playing != null && startPositionMs > 0L) {
                "▶️ 继续播放：${next.title}"
            } else {
                "▶️ 播放下一条：${next.title}"
            }
            playQueueItemInternal(next, startPositionMs, toast)
            true
        } catch (t: Throwable) {
            android.util.Log.e("PlayerActivity", "tryAdvanceQueueOnEnded failed", t)
            false
        }
    }

    private fun showQueueFinishedOverlay() {
        try {
            val toast = android.widget.Toast.makeText(
                this, "🎉 队列播放完毕", android.widget.Toast.LENGTH_LONG
            )
            toast.show()
            ui.postDelayed({
                try { finish() } catch (_: Throwable) {}
            }, 2600L)
        } catch (_: Throwable) {}
    }
}
