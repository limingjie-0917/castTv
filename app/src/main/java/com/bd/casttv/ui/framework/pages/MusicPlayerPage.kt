package com.bd.casttv.ui.framework.pages

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.music.MusicArtworkLoader
import com.bd.casttv.music.MusicBlurUtils
import com.bd.casttv.music.MusicLoopMode
import com.bd.casttv.music.MusicPlaylistLoadResult
import com.bd.casttv.music.MusicPlaylistRepository
import com.bd.casttv.music.MusicRepoCatalog
import com.bd.casttv.music.MusicRepoConfig
import com.bd.casttv.music.MusicTrack
import com.bd.casttv.music.MusicUploadResult
import com.bd.casttv.music.view.MusicCoverArtView
import com.bd.casttv.music.view.MusicLyricsView
import com.bd.casttv.sync.GiteeApi
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.NewMainActivity
import com.bd.casttv.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class MusicPlayerPage(context: Context) : BasePage(context) {
    override val pageId: String = PAGE_ID
    override val pageTitle: String = "音乐"
    override val pageIconRes: Int = R.drawable.ic_more_music
    override val enablePageScroll: Boolean = false
    override val contentHorizontalMarginDp: Int get() = 0

    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playlistRepository = MusicPlaylistRepository(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var playlistLoadJob: Job? = null
    private var coverLoadJob: Job? = null
    private var lyricsLoadJob: Job? = null
    private var uploadJob: Job? = null
    private var playbackPrepareJob: Job? = null
    private var uploadDialog: AlertDialog? = null
    private var uploadDialogActions: LinearLayout? = null
    private var uploadDialogLoading: LinearLayout? = null
    private var uploadDialogLoadingText: TextView? = null
    private var playlistReloadRequired = false
    private var coverRotationAnimator: ObjectAnimator? = null
    private var showingBackdropA = true

    private var playlist: List<MusicTrack> = emptyList()
    private var currentTrackIndex: Int = -1
    private var focusedListIndex: Int = 0
    private var loopMode: MusicLoopMode = MusicLoopMode.OFF
    private var lastLoadSummary: String = ""

    private val backdropA = AppCompatImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 1f
    }
    private val backdropB = AppCompatImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
    }
    private val backdropGradient = View(context)
    private val backdropScrim = View(context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(120, 0, 0, 0), Color.argb(210, 0, 0, 0), Color.argb(226, 0, 0, 0)),
        )
    }

    private val listHeaderTitle = TextView(context).apply {
        text = "播放列表"
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
    }
    private val listStatus = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.argb(190, 255, 255, 255))
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 2
        visibility = View.GONE
    }
    private val cloudDataTitle = TextView(context).apply {
        text = "☁️ 云端数据"
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
    }
    private val cloudRepoRows = linkedMapOf<String, TextView>()
    private val loopButton = HeaderActionButton()
    private val uploadButton = HeaderActionButton("上传")

    private val trackRecycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = false
        clipToPadding = false
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        setPadding(0, 0, 0, dp(12))
    }
    private val trackAdapter = TrackAdapter()
    private val loadingView = ProgressBar(context, null, android.R.attr.progressBarStyleLarge).apply {
        visibility = View.GONE
    }
    private val emptyView = TextView(context).apply {
        text = "暂无歌曲"
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(Color.argb(210, 255, 255, 255))
        visibility = View.GONE
    }

    private val coverRotator = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
    }
    private val coverView = MusicCoverArtView(context)
    private val songTitleView = TextView(context).apply {
        textSize = 31f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val artistView = TextView(context).apply {
        textSize = 17f
        setTextColor(Color.argb(186, 255, 255, 255))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val lyricsView = MusicLyricsView(context)
    private val progressCurrent = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.argb(214, 255, 255, 255))
        text = "00:00"
    }
    private val progressTotal = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.argb(164, 255, 255, 255))
        text = "--:--"
        gravity = Gravity.END
    }
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progress = 0
        progressDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(999).toFloat()
            setColor(Color.argb(110, 255, 255, 255))
        }
        progressTintList = android.content.res.ColorStateList.valueOf(warmColor())
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.argb(72, 255, 255, 255))
        isFocusable = false
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            refreshPlaybackUi()
            mainHandler.postDelayed(this, 300L)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshTrackRows()
            refreshPlaybackUi()
            if (playbackState == Player.STATE_ENDED) handleTrackEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshTrackRows()
            refreshPlaybackUi()
        }

        override fun onPlayerError(error: PlaybackException) {
            refreshTrackRows()
            refreshPlaybackUi()
            toast(error.errorCodeName.takeIf { it.isNotBlank() } ?: (error.message ?: "播放失败"))
        }
    }

    init {
        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        root.addView(backdropGradient, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        root.addView(backdropA, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        root.addView(backdropB, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        root.addView(backdropScrim, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        content.addView(buildLeftPanel(), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 0.4f))
        content.addView(buildRightPanel(), LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 0.6f).apply {
            marginStart = dp(8)
        })
        root.addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentContainer.addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        trackRecycler.adapter = trackAdapter
        configureActionButtons()
        refreshTheme()
        updateLoopButton()
        loadPlaylist(showLoading = true)
    }

    override fun onEnter() {
        super.onEnter()
        if (playlistReloadRequired || (loadingView.visibility == View.VISIBLE && playlistLoadJob?.isActive != true)) {
            loadPlaylist(showLoading = true)
        }
        mainHandler.removeCallbacks(progressRunnable)
        mainHandler.post(progressRunnable)
    }

    override fun onLeave() {
        pauseAndReleasePlayer()
        cancelTransientJobs(cancelUpload = false)
        if (uploadJob?.isActive != true) restoreListContentState()
        mainHandler.removeCallbacks(progressRunnable)
        super.onLeave()
    }

    fun onHostActivityPaused() {
        pauseAndReleasePlayer()
    }

    override fun onDetachedFromWindow() {
        pauseAndReleasePlayer()
        cancelTransientJobs(cancelUpload = true)
        closeUploadDialog()
        mainHandler.removeCallbacks(progressRunnable)
        pageScope.cancel()
        super.onDetachedFromWindow()
    }

    override fun refreshTheme() {
        super.refreshTheme()
        val palette = ThemeManager.currentPalette(context)
        coverView.setAccentColor(palette.accent)
        lyricsView.setAccentColor(palette.accent)
        progressBar.progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
        listHeaderTitle.setTextColor(Color.WHITE)
        listStatus.setTextColor(Color.argb(198, 255, 255, 255))
        cloudDataTitle.setTextColor(palette.accent)
        cloudRepoRows.values.forEach { it.setTextColor(Color.argb(218, 255, 255, 255)) }
        songTitleView.setTextColor(Color.WHITE)
        artistView.setTextColor(Color.argb(186, 255, 255, 255))
        val pageColors = palette.pageHeaderGradient.map { color ->
            Color.argb(255, max(18, Color.red(color) / 2), max(20, Color.green(color) / 2), max(28, Color.blue(color) / 2))
        }.toIntArray()
        // BasePage 的顶部预留区不在 contentContainer 内，必须同步设置整页背景，避免露出 Activity 黑底。
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, pageColors)
        backdropGradient.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, pageColors)
        val accent = palette.accent
        backdropScrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.argb(58, Color.red(accent), Color.green(accent), Color.blue(accent)),
                Color.argb(118, 0, 0, 0),
                Color.argb(156, 0, 0, 0),
            ),
        )
        loadingView.indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.accent)
        emptyView.setTextColor(Color.argb(210, 255, 255, 255))
        refreshActionButtonVisual(loopButton, loopMode.iconRes, active = loopMode != MusicLoopMode.OFF)
        refreshActionButtonVisual(uploadButton, R.drawable.ic_music_upload, active = false)
        trackAdapter.notifyDataSetChanged()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            pauseAndReleasePlayer()
            (context as? NewMainActivity)?.onBackPressedDispatcher?.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun focusToFirstContent(): Boolean {
        val target = findRowViewForFocus(focusedListIndex)
            ?: findRowViewForFocus(currentTrackIndex)
            ?: uploadButton.takeIf { playlist.isEmpty() || it.isShown }
        val ok = target?.requestFocus() == true
        if (ok) onFocusEnterContent()
        return ok
    }

    private fun buildLeftPanel(): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(12))
            background = ThemeManager.dialogPanelBg(context, 28)
            clipChildren = false
            clipToPadding = false
        }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        actionRow.addView(loopButton, LinearLayout.LayoutParams(dp(96), dp(44)))
        actionRow.addView(uploadButton, LinearLayout.LayoutParams(dp(80), dp(44)).apply { marginStart = dp(6) })
        headerRow.addView(listHeaderTitle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(actionRow)
        panel.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(listStatus, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        val listContainer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        listContainer.addView(trackRecycler, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(16)
        })
        listContainer.addView(loadingView, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        listContainer.addView(emptyView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        panel.addView(listContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(buildCloudDataSection(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        return panel
    }

    private fun buildCloudDataSection(): View {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(24, 255, 255, 255))
                setStroke(dp(1), Color.argb(72, 220, 224, 232))
            }
        }
        section.addView(cloudDataTitle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val repoDataRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        MusicRepoCatalog.repos.forEachIndexed { index, repo ->
            if (index > 0) {
                repoDataRow.addView(View(context).apply {
                    setBackgroundColor(Color.argb(86, 220, 224, 232))
                }, LinearLayout.LayoutParams(dp(1), dp(22)))
            }
            val row = TextView(context).apply {
                text = "music${index + 1}  0 首"
                textSize = 13f
                setTextColor(Color.argb(218, 255, 255, 255))
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            cloudRepoRows[repo.id] = row
            repoDataRow.addView(row, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        section.addView(repoDataRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return section
    }

    private fun buildRightPanel(): View {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(30), dp(20), dp(30), dp(22))
            background = ThemeManager.dialogPanelBg(context, 28)
            clipChildren = true
            clipToPadding = true
        }
        coverRotator.addView(coverView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val coverArea = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
            addView(coverRotator, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val metadataArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = true
            clipToPadding = true
            addView(songTitleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(artistView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        val topArea = object : LinearLayout(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (h <= 0) return
                coverArea.layoutParams = LinearLayout.LayoutParams(h, h)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = true
            clipToPadding = true
            addView(coverArea, LinearLayout.LayoutParams(1, LayoutParams.MATCH_PARENT))
            addView(metadataArea, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(18)
            })
        }
        panel.addView(topArea, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.32f))

        val detailsArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            clipChildren = true
            clipToPadding = true
        }
        detailsArea.addView(lyricsView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(12)
        })

        val progressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(progressCurrent, LinearLayout.LayoutParams(dp(70), LayoutParams.WRAP_CONTENT))
            addView(progressBar, LinearLayout.LayoutParams(0, dp(6), 1f).apply {
                marginStart = dp(14)
                marginEnd = dp(14)
            })
            addView(progressTotal, LinearLayout.LayoutParams(dp(70), LayoutParams.WRAP_CONTENT))
        }
        detailsArea.addView(progressRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(detailsArea, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.68f))
        return panel
    }

    private fun configureActionButtons() {
        setupHeaderActionButton(
            button = loopButton,
            iconRes = { loopMode.iconRes },
            onClick = { cycleLoopMode() },
            isActive = { loopMode != MusicLoopMode.OFF },
        )
        setupHeaderActionButton(
            button = uploadButton,
            iconRes = { R.drawable.ic_music_upload },
            onClick = { showUploadRepoDialog() },
            isActive = { false },
        )
    }

    private fun setupHeaderActionButton(
        button: HeaderActionButton,
        iconRes: () -> Int,
        onClick: () -> Unit,
        isActive: () -> Boolean,
    ) {
        button.setOnFocusChangeListener { v, hasFocus ->
            refreshActionButtonVisual(button, iconRes(), active = isActive())
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 22)
        }
        button.setOnClickListener { onClick() }
        button.setOnKeyListener { view, keyCode, event -> handleTopActionKey(view, keyCode, event) }
        refreshActionButtonVisual(button, iconRes(), active = isActive())
    }

    private fun handleTopActionKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                BoundaryFocusHandler.shake(view)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val target = findRowViewForFocus(focusedListIndex)
                    ?: findRowViewForFocus(currentTrackIndex)
                if (target == null) {
                    BoundaryFocusHandler.shake(view)
                    true
                } else {
                    target.requestFocus()
                    true
                }
            }
            else -> false
        }
    }

    private fun refreshActionButtonVisual(button: HeaderActionButton, iconRes: Int, active: Boolean) {
        val focused = button.isFocused
        val accent = warmColor()
        val contentColor = when {
            active -> Color.WHITE
            focused -> accent
            else -> Color.argb(220, 255, 255, 255)
        }
        button.setContentVisual(iconRes, contentColor)
        button.background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(
                when {
                    active -> Color.argb(if (focused) 96 else 70, Color.red(accent), Color.green(accent), Color.blue(accent))
                    focused -> Color.argb(42, 255, 255, 255)
                    else -> Color.argb(18, 255, 255, 255)
                }
            )
            setStroke(dp(if (focused) 2 else 1), if (focused || active) accent else Color.argb(118, 210, 214, 222))
        }
    }

    private fun loadPlaylist(showLoading: Boolean, preserveUrl: String? = currentTrack()?.url) {
        playlistLoadJob?.cancel()
        playlistReloadRequired = false
        if (showLoading) showLoadingState("正在加载歌单…")
        playlistLoadJob = pageScope.launch {
            val result = withContext(Dispatchers.IO) { playlistRepository.loadMergedPlaylist() }
            applyPlaylistResult(result, preserveUrl)
        }
    }

    private fun applyPlaylistResult(result: MusicPlaylistLoadResult, preserveUrl: String?) {
        playlist = result.tracks
        trackAdapter.notifyDataSetChanged()
        loadingView.visibility = View.GONE
        emptyView.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
        trackRecycler.visibility = if (playlist.isEmpty()) View.GONE else View.VISIBLE
        lastLoadSummary = when {
            playlist.isEmpty() && result.skippedRepos.isNotEmpty() -> result.skippedRepos.joinToString("\n")
            result.skippedRepos.isNotEmpty() -> "已加载 ${playlist.size} 首，跳过 ${result.skippedRepos.size} 个仓库"
            else -> "共 ${playlist.size} 首歌曲"
        }
        updateListStatus(lastLoadSummary)
        MusicRepoCatalog.repos.forEachIndexed { index, repo ->
            val count = result.repoTrackCounts[repo.id] ?: 0
            cloudRepoRows[repo.id]?.text = "music${index + 1}  $count 首"
        }

        if (playlist.isEmpty()) {
            emptyView.text = if (result.skippedRepos.isNotEmpty()) {
                "暂无可播放歌曲\n${result.skippedRepos.first()}"
            } else {
                "暂无歌曲"
            }
            currentTrackIndex = -1
            focusedListIndex = 0
            bindTrack(null)
            return
        }

        val playingUrl = player?.currentMediaItem?.localConfiguration?.uri?.toString()
        val targetUrl = playingUrl ?: preserveUrl
        val restoreIndex = targetUrl?.let { url -> playlist.indexOfFirst { it.url == url } }?.takeIf { it >= 0 }
            ?: currentTrackIndex.takeIf { it in playlist.indices }
            ?: 0
        if (playingUrl != null && playlist.none { it.url == playingUrl }) {
            pauseAndReleasePlayer()
        }
        currentTrackIndex = restoreIndex
        focusedListIndex = focusedListIndex.coerceIn(0, playlist.lastIndex)
        bindTrack(playlist[currentTrackIndex], refreshArtwork = true)
        restoreListFocusIfNeeded()
        refreshPlaybackUi()
    }

    private fun showLoadingState(message: String) {
        loadingView.visibility = View.VISIBLE
        trackRecycler.visibility = View.INVISIBLE
        emptyView.visibility = View.GONE
        updateListStatus(message)
    }

    private fun updateListStatus(message: String) {
        listStatus.text = message
        listStatus.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun bindTrack(track: MusicTrack?, refreshArtwork: Boolean = false) {
        songTitleView.text = track?.title ?: "暂无歌曲"
        artistView.text = track?.artist ?: "请选择或上传音频"
        if (track == null) {
            lyricsView.setLyrics(emptyList())
            progressCurrent.text = "00:00"
            progressTotal.text = "--:--"
            progressBar.progress = 0
            coverView.setArtwork(null)
            updateBackdrop(null)
            stopCoverRotation()
            return
        }
        if (refreshArtwork) {
            loadLyricsFor(track)
            loadArtworkFor(track)
        }
    }

    private fun loadLyricsFor(track: MusicTrack) {
        lyricsLoadJob?.cancel()
        lyricsView.setLyrics(emptyList(), "歌词加载中…")
        lyricsLoadJob = pageScope.launch {
            val lines = withContext(Dispatchers.IO) { playlistRepository.loadLyrics(track.lrc) }
            if (track.url != currentTrack()?.url) return@launch
            lyricsView.setLyrics(lines)
            refreshPlaybackUi()
        }
    }

    private fun loadArtworkFor(track: MusicTrack) {
        coverLoadJob?.cancel()
        // 先立即恢复兜底音符，避免切歌时短暂显示上一首歌曲的封面。
        coverView.setArtwork(null)
        updateBackdrop(null)
        coverLoadJob = pageScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                if (!track.cover.isNullOrBlank()) {
                    downloadBitmap(track.cover)
                } else {
                    val audioUrl = resolveAudioUrlForMetadata(track.url) ?: return@withContext null
                    MusicArtworkLoader.extractEmbeddedArtwork(audioUrl)
                }
            }
            if (track.url != currentTrack()?.url) return@launch
            coverView.setArtwork(bitmap)
            updateBackdrop(bitmap)
        }
    }

    /** 私有 Gitee Release 音频需先换取短期签名地址，MediaMetadataRetriever 才能读取 ID3。 */
    private fun resolveAudioUrlForMetadata(audioUrl: String): String? {
        if (!audioUrl.contains("gitee.com/") || !audioUrl.contains("/releases/download/")) return audioUrl
        return when (val resolved = GiteeApi.resolvePlayableAssetUrl(audioUrl)) {
            is GiteeApi.ApiResult.Success -> resolved.value
            is GiteeApi.ApiResult.Error, GiteeApi.ApiResult.NotFound -> null
        }
    }

    private fun updateBackdrop(bitmap: Bitmap?) {
        val hidden = if (showingBackdropA) backdropB else backdropA
        val front = if (showingBackdropA) backdropA else backdropB
        if (bitmap == null) {
            front.animate().alpha(0f).setDuration(220L).start()
            hidden.animate().alpha(0f).setDuration(220L).start()
            backdropGradient.alpha = 1f
            return
        }
        backdropGradient.alpha = 0.62f
        applyBackdropBitmap(hidden, bitmap)
        hidden.visibility = View.VISIBLE
        hidden.alpha = 0f
        hidden.animate().alpha(1f).setDuration(360L).start()
        front.animate().alpha(0f).setDuration(360L).withEndAction {
            front.setImageDrawable(null)
        }.start()
        showingBackdropA = !showingBackdropA
    }

    private fun applyBackdropBitmap(target: AppCompatImageView, source: Bitmap) {
        if (Build.VERSION.SDK_INT >= 31) {
            target.setImageBitmap(MusicArtworkLoader.scaleCenterCrop(source, 720, 405))
            target.setRenderEffect(RenderEffect.createBlurEffect(42f, 42f, Shader.TileMode.CLAMP))
        } else {
            target.setImageBitmap(MusicBlurUtils.createBackdropBitmap(source, 520, 292, radius = 20))
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        val existing = player
        if (existing != null) return existing
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("casttv-receiver-android")
            .setAllowCrossProtocolRedirects(true)
        return ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build()
            .also { created ->
            created.addListener(playerListener)
            player = created
        }
    }

    private fun selectTrack(index: Int, autoPlay: Boolean) {
        val track = playlist.getOrNull(index) ?: return
        val existingPlayer = player
        val trackChanged = currentTrack()?.url != track.url || existingPlayer?.currentMediaItem == null
        currentTrackIndex = index
        focusedListIndex = index
        bindTrack(track, refreshArtwork = trackChanged)
        trackAdapter.notifyDataSetChanged()
        ensureRowVisible(index)
        if (trackChanged) {
            playbackPrepareJob?.cancel()
            playbackPrepareJob = pageScope.launch {
                val playableUrl = if (track.url.contains("gitee.com/") && track.url.contains("/releases/download/")) {
                    when (val resolved = withContext(Dispatchers.IO) { GiteeApi.resolvePlayableAssetUrl(track.url) }) {
                        is GiteeApi.ApiResult.Success -> resolved.value
                        is GiteeApi.ApiResult.Error -> {
                            if (currentTrack()?.url == track.url) toast("播放失败：${resolved.message}")
                            return@launch
                        }
                        GiteeApi.ApiResult.NotFound -> {
                            if (currentTrack()?.url == track.url) toast("播放失败：音频文件不存在")
                            return@launch
                        }
                    }
                } else {
                    track.url
                }
                if (currentTrack()?.url != track.url) return@launch
                val exo = ensurePlayer()
                exo.setMediaItem(MediaItem.fromUri(playableUrl))
                exo.playWhenReady = autoPlay
                exo.prepare()
                if (autoPlay) exo.play()
                refreshPlaybackUi()
            }
        } else {
            val exo = existingPlayer ?: ensurePlayer()
            if (autoPlay) {
                exo.playWhenReady = true
                exo.play()
            } else {
                exo.playWhenReady = false
            }
        }
        refreshPlaybackUi()
    }

    private fun toggleCurrentTrackPlayback() {
        if (playlist.isEmpty()) return
        val exo = ensurePlayer()
        val current = currentTrack() ?: playlist.getOrNull(focusedListIndex)
        if (current == null) return
        if (exo.currentMediaItem == null || currentTrackIndex !in playlist.indices || current.url != currentTrack()?.url) {
            selectTrack(focusedListIndex.coerceIn(0, playlist.lastIndex), autoPlay = true)
            return
        }
        if (exo.isPlaying) exo.pause() else exo.play()
        refreshPlaybackUi()
    }

    private fun cycleLoopMode() {
        loopMode = loopMode.next()
        updateLoopButton()
        toast(loopMode.label)
    }

    private fun updateLoopButton() {
        loopButton.text = loopMode.label
        refreshActionButtonVisual(loopButton, loopMode.iconRes, active = loopMode != MusicLoopMode.OFF)
        loopButton.contentDescription = loopMode.label
    }

    private fun handleTrackEnded() {
        if (playlist.isEmpty() || currentTrackIndex !in playlist.indices) return
        when (loopMode) {
            MusicLoopMode.ALL -> {
                val next = if (currentTrackIndex >= playlist.lastIndex) 0 else currentTrackIndex + 1
                selectTrack(next, autoPlay = true)
            }
            MusicLoopMode.OFF -> {
                if (currentTrackIndex < playlist.lastIndex) {
                    selectTrack(currentTrackIndex + 1, autoPlay = true)
                } else {
                    player?.pause()
                    player?.seekTo(0)
                    refreshPlaybackUi()
                }
            }
        }
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(target)
        refreshPlaybackUi()
    }

    private fun refreshPlaybackUi() {
        val exo = player
        val duration = exo?.duration?.takeIf { it > 0 } ?: 0L
        val position = exo?.currentPosition?.coerceAtLeast(0L) ?: 0L
        progressCurrent.text = formatTime(position)
        progressTotal.text = if (duration > 0) formatTime(duration) else "--:--"
        progressBar.progress = if (duration > 0) ((position * 1000L / duration).toInt().coerceIn(0, 1000)) else 0
        lyricsView.updatePosition(position)
        if (exo?.isPlaying == true) startCoverRotation() else stopCoverRotation()
    }

    private fun refreshTrackRows() {
        val index = currentTrackIndex
        if (index in playlist.indices) {
            trackAdapter.notifyItemChanged(index)
        }
    }

    private fun startCoverRotation() {
        if (coverRotationAnimator?.isRunning == true) return
        val start = coverRotator.rotation % 360f
        coverRotationAnimator = ObjectAnimator.ofFloat(coverRotator, View.ROTATION, start, start + 360f).apply {
            duration = 28_000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopCoverRotation() {
        coverRotationAnimator?.cancel()
        coverRotationAnimator = null
    }

    private fun pauseAndReleasePlayer() {
        playbackPrepareJob?.cancel()
        playbackPrepareJob = null
        stopCoverRotation()
        player?.let { exo ->
            exo.removeListener(playerListener)
            runCatching {
                exo.pause()
                exo.release()
            }
        }
        player = null
    }

    private fun cancelTransientJobs(cancelUpload: Boolean) {
        if (playlistLoadJob?.isActive == true) playlistReloadRequired = true
        playlistLoadJob?.cancel()
        playlistLoadJob = null
        coverLoadJob?.cancel()
        coverLoadJob = null
        lyricsLoadJob?.cancel()
        lyricsLoadJob = null
        if (cancelUpload) {
            uploadJob?.cancel()
            uploadJob = null
        }
    }

    private fun restoreListContentState() {
        loadingView.visibility = View.GONE
        emptyView.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
        trackRecycler.visibility = if (playlist.isEmpty()) View.GONE else View.VISIBLE
        updateListStatus(lastLoadSummary)
    }

    private fun restoreListFocusIfNeeded() {
        if (!isAttachedToWindow || playlist.isEmpty()) return
        post {
            if (findFocus() == null || findFocus() === pageRootFocus) {
                focusToFirstContent()
            }
        }
    }

    private fun ensureRowVisible(index: Int) {
        if (index !in playlist.indices) return
        trackRecycler.post { trackRecycler.scrollToPosition(index) }
    }

    private fun currentTrack(): MusicTrack? = playlist.getOrNull(currentTrackIndex)

    private fun findRowViewForFocus(index: Int): View? {
        if (playlist.isEmpty()) return null
        val safeIndex = index.coerceIn(0, playlist.lastIndex)
        val holder = trackRecycler.findViewHolderForAdapterPosition(safeIndex)
        if (holder != null) return holder.itemView
        trackRecycler.scrollToPosition(safeIndex)
        return null
    }

    private inner class HeaderActionButton(initialText: String = "") : LinearLayout(context) {
        private val iconView = AppCompatImageView(context).apply {
            isFocusable = false
            isClickable = false
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        private val labelView = TextView(context).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            isFocusable = false
            isClickable = false
        }

        var text: CharSequence
            get() = labelView.text
            set(value) {
                labelView.text = value
            }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(3), 0, dp(3), 0)
            addView(iconView, LinearLayout.LayoutParams(dp(18), dp(18)))
            addView(labelView, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(1)
            })
            text = initialText
        }

        fun setContentVisual(iconRes: Int, color: Int) {
            iconView.setImageResource(iconRes)
            iconView.setColorFilter(color)
            labelView.setTextColor(color)
        }
    }

    private inner class TrackAdapter : RecyclerView.Adapter<TrackViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(dp(18), dp(14), dp(18), dp(14))
                clipChildren = false
                clipToPadding = false
            }
            val icon = ImageView(parent.context).apply { id = View.generateViewId() }
            val textColumn = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            val title = TextView(parent.context).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val artist = TextView(parent.context).apply {
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            textColumn.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            textColumn.addView(artist, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            root.addView(icon, LinearLayout.LayoutParams(dp(26), dp(26)))
            root.addView(textColumn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
            })
            val layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            root.layoutParams = layoutParams
            return TrackViewHolder(root, icon, title, artist)
        }

        override fun getItemCount(): Int = playlist.size

        override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
            holder.bind(playlist[position], position)
        }
    }

    private inner class TrackViewHolder(
        itemView: LinearLayout,
        private val iconView: ImageView,
        private val titleView: TextView,
        private val artistTextView: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(track: MusicTrack, position: Int) {
            val isCurrent = position == currentTrackIndex
            val isPlaying = isCurrent && player?.isPlaying == true
            titleView.text = track.title
            artistTextView.text = "歌手：${track.artist}"
            val accent = warmColor()
            titleView.setTextColor(if (isCurrent) accent else Color.WHITE)
            artistTextView.setTextColor(if (isCurrent) accent else Color.argb(186, 255, 255, 255))
            iconView.setImageResource(if (isPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play)
            iconView.imageTintList = android.content.res.ColorStateList.valueOf(
                if (isCurrent) accent else Color.argb(186, 255, 255, 255),
            )
            refreshBackground(itemView, isCurrent, itemView.isFocused)

            itemView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    focusedListIndex = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
                    onFocusEnterContent()
                }
                refreshBackground(v, isCurrent = bindingAdapterPosition == currentTrackIndex, isFocused = hasFocus)
                FocusFxHelper.applyFocusFxState(v, hasFocus, scale = 1.03f, cornerRadiusDp = 18, elevationDp = 6)
            }
            itemView.setOnClickListener { onRowConfirmed(bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position) }
            itemView.setOnKeyListener { v, keyCode, event -> handleRowKey(v, keyCode, event, position) }
        }

        private fun refreshBackground(view: View, isCurrent: Boolean, isFocused: Boolean) {
            val accent = warmColor()
            view.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(
                    when {
                        isCurrent -> Color.argb(if (isFocused) 116 else 82, Color.red(accent), Color.green(accent), Color.blue(accent))
                        isFocused -> Color.argb(28, 255, 255, 255)
                        else -> Color.argb(16, 255, 255, 255)
                    }
                )
            }
        }
    }

    private fun handleRowKey(view: View, keyCode: Int, event: KeyEvent, fallbackPosition: Int): Boolean {
        val position = (trackRecycler.findContainingViewHolder(view)?.bindingAdapterPosition ?: fallbackPosition)
            .takeIf { it != RecyclerView.NO_POSITION } ?: fallbackPosition

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> true
                KeyEvent.ACTION_UP -> {
                    onRowConfirmed(position)
                    true
                }
                else -> false
            }
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (position == 0) {
                    loopButton.requestFocus()
                } else {
                    focusTrackRow(position - 1)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (position >= playlist.lastIndex) {
                    BoundaryFocusHandler.shake(view)
                } else {
                    focusTrackRow(position + 1)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBy(-10_000L)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBy(10_000L)
                true
            }
            else -> false
        }
    }

    /** 遥控器上下键只移动列表焦点，不切歌、不改变播放状态。 */
    private fun focusTrackRow(position: Int): Boolean {
        if (position !in playlist.indices) return false
        focusedListIndex = position
        findRowViewForFocus(position)?.let { return it.requestFocus() }
        trackRecycler.scrollToPosition(position)
        trackRecycler.post {
            findRowViewForFocus(position)?.requestFocus()
        }
        return true
    }

    private fun onRowConfirmed(position: Int) {
        if (position !in playlist.indices) return
        if (position == currentTrackIndex) {
            toggleCurrentTrackPlayback()
        } else {
            selectTrack(position, autoPlay = true)
        }
    }

    private fun showUploadRepoDialog() {
        if (uploadJob?.isActive == true) {
            toast("上传进行中，请稍候")
            return
        }
        uploadDialog?.takeIf { it.isShowing }?.let {
            it.window?.decorView?.requestFocus()
            return
        }
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(48, 53, 66), Color.rgb(28, 31, 40)),
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(2), Color.argb(155, 255, 255, 255))
            }
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ClippedImageView(context).apply {
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setCircle(true)
            foreground = ContextCompat.getDrawable(context, R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        val title = TextView(context).apply {
            text = "选择上传仓库"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        header.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val desc = TextView(context).apply {
            text = "选择目标仓库后挑选本地音频，上传完成前弹窗会保持打开。"
            textSize = 14f
            setTextColor(Color.argb(206, 255, 255, 255))
            setPadding(0, dp(10), 0, 0)
        }
        panel.addView(desc)

        val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        uploadDialogActions = actions
        val loadingText = TextView(context).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, 0)
        }
        uploadDialogLoadingText = loadingText
        val loading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(24), 0, dp(10))
            addView(ProgressBar(context, null, android.R.attr.progressBarStyleLarge).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(warmColor())
            }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(loadingText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        uploadDialogLoading = loading

        lateinit var dialog: AlertDialog
        val focusables = mutableListOf<View>()
        MusicRepoCatalog.repos.forEach { repo ->
            val button = dialogButton(repo.displayName) {
                launchAudioPicker(repo)
            }
            actions.addView(button, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(14)
            })
            focusables += button
        }
        val cancel = dialogButton("取消") { dialog.dismiss() }
        actions.addView(cancel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(16)
        })
        focusables += cancel
        panel.addView(actions)
        panel.addView(loading, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { created ->
            uploadDialog = created
            created.setOnShowListener { focusables.firstOrNull()?.requestFocus() }
            created.setOnDismissListener {
                if (uploadDialog === created) {
                    uploadDialog = null
                    uploadDialogActions = null
                    uploadDialogLoading = null
                    uploadDialogLoadingText = null
                    // 无论取消、上传成功或失败，关闭弹窗后都重新拉取三个仓库，
                    // 由同一次结果同时更新播放列表与云端歌曲数量。
                    if (isAttachedToWindow) {
                        loadPlaylist(showLoading = false, preserveUrl = currentTrack()?.url)
                    }
                }
            }
            created.show()
            created.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(520), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun setUploadDialogLoading(repoConfig: MusicRepoConfig) {
        if (uploadDialog?.isShowing != true) showUploadRepoDialog()
        uploadDialogActions?.visibility = View.GONE
        uploadDialogLoading?.visibility = View.VISIBLE
        uploadDialogLoadingText?.text = "正在上传到 ${repoConfig.displayName}…"
        uploadDialog?.setCancelable(false)
        uploadDialog?.setCanceledOnTouchOutside(false)
    }

    private fun closeUploadDialog() {
        uploadDialog?.dismiss()
        uploadDialog = null
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(18, 255, 255, 255))
            setStroke(dp(1), Color.argb(110, 210, 214, 222))
        }
        setOnFocusChangeListener { v, hasFocus ->
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(if (hasFocus) 34 else 18, 255, 255, 255))
                setStroke(dp(if (hasFocus) 2 else 1), if (hasFocus) warmColor() else Color.argb(110, 210, 214, 222))
            }
            setTextColor(if (hasFocus) warmColor() else Color.WHITE)
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 18)
        }
        setOnClickListener { click() }
        setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    BoundaryFocusHandler.shake(v)
                    true
                }
                else -> false
            }
        }
    }

    private fun launchAudioPicker(repoConfig: MusicRepoConfig) {
        val activity = context as? NewMainActivity ?: run {
            toast("当前页面暂不支持文件选择")
            return
        }
        activity.launchMusicAudioPicker(repoConfig.id)
    }

    fun onMusicAudioDocumentResult(uri: android.net.Uri?, repoId: String) {
        val repoConfig = MusicRepoCatalog.byId(repoId) ?: run {
            closeUploadDialog()
            toast("上传失败：目标仓库不存在")
            return
        }
        if (uri == null) {
            toast("已取消选择")
            return
        }
        val activity = context as? NewMainActivity
        runCatching {
            activity?.contentResolver?.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setUploadDialogLoading(repoConfig)
        startUpload(uri, repoConfig)
    }

    private fun startUpload(uri: android.net.Uri, repoConfig: MusicRepoConfig) {
        if (uploadJob?.isActive == true) return
        uploadJob = pageScope.launch {
            when (val result = withContext(Dispatchers.IO) { playlistRepository.uploadAudio(uri, repoConfig) }) {
                is MusicUploadResult.Success -> {
                    closeUploadDialog()
                    toast("上传成功")
                }
                is MusicUploadResult.PartialSuccess -> {
                    closeUploadDialog()
                    toast("部分成功：${result.message}")
                }
                is MusicUploadResult.Error -> {
                    closeUploadDialog()
                    val reason = result.message.ifBlank { "未知原因" }
                    toast("上传失败：$reason")
                    restoreListContentState()
                }
            }
        }
    }

    private fun downloadBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }

    private fun warmColor(): Int = ThemeManager.currentPalette(context).accent

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val PAGE_ID = "music_player"
    }
}
