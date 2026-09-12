package com.bd.casttv.ui.framework.pages

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
    private var cloudSongsDialog: AlertDialog? = null
    private var cloudDeleteJob: Job? = null
    private var uploadDialogActions: LinearLayout? = null
    private var uploadDialogLoading: LinearLayout? = null
    private var uploadDialogLoadingText: TextView? = null
    private var uploadDialogCancelButton: TextView? = null
    private var pendingBatchCancel: Boolean = false
    private var playlistReloadRequired = false
    private var coverRotationAnimator: ObjectAnimator? = null
    private var showingBackdropA = true

    private var allPlaylist: List<MusicTrack> = emptyList()
    private var playlist: List<MusicTrack> = emptyList()
    private var selectedArtist: String? = null
    private var lastSkippedRepos: List<String> = emptyList()
    private var currentTrackIndex: Int = -1
    private var activeTrack: MusicTrack? = null
    private var focusedListIndex: Int = 0
    private var loopMode: MusicLoopMode = MusicLoopMode.SHUFFLE
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
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 1
    }
    private val headerDivider = View(context)
    private val artistTabBottomDivider = View(context)
    private val artistTabRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val listStatus = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.argb(190, 255, 255, 255))
        ellipsize = TextUtils.TruncateAt.END
        maxLines = 2
        gravity = Gravity.START
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
    private val uploadButton = HeaderActionButton()

    private val trackRecycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        itemAnimator = null
        overScrollMode = View.OVER_SCROLL_NEVER
        clipChildren = true
        clipToPadding = true
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
    private val coverView = MusicCoverArtView(context).apply {
        setAccentColor(ThemeManager.accentColor(context))
    }
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
    private val progressBar = PlaybackProgressView(context).apply {
        setProgress(0f)
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            refreshPlaybackUi()
            mainHandler.postDelayed(this, 200L)
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
        cloudDeleteJob?.cancel()
        cloudDeleteJob = null
        cloudSongsDialog?.dismiss()
        cloudSongsDialog = null
        mainHandler.removeCallbacks(progressRunnable)
        pageScope.cancel()
        super.onDetachedFromWindow()
    }

    override fun refreshTheme() {
        super.refreshTheme()
        val palette = ThemeManager.currentPalette(context)
        coverView.setAccentColor(palette.accent)
        lyricsView.setAccentColor(palette.accent)
        progressBar.setColors(
            playedColor = palette.accent,
            trackColor = Color.argb(72, 255, 255, 255),
        )
        listHeaderTitle.setTextColor(Color.WHITE)
        val dividerColor = themedDividerColor()
        headerDivider.setBackgroundColor(dividerColor)
        artistTabBottomDivider.setBackgroundColor(dividerColor)
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
        refreshActionButtonVisual(loopButton, loopMode.iconRes)
        refreshActionButtonVisual(uploadButton, R.drawable.ic_music_upload)
        trackAdapter.notifyDataSetChanged()
        rebuildArtistTabs()
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
            setPadding(dp(16), dp(10), dp(16), dp(12))
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
        actionRow.addView(loopButton, LinearLayout.LayoutParams(dp(34), dp(32)))
        actionRow.addView(uploadButton, LinearLayout.LayoutParams(dp(34), dp(32)).apply { marginStart = dp(5) })
        headerRow.addView(listHeaderTitle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(actionRow)
        panel.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(headerDivider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(5)
        })
        val artistTabViewport = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BOUNDS
            isFocusable = false
        }
        artistTabViewport.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            clipChildren = true
            clipToPadding = false
            addView(artistTabRow, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        panel.addView(artistTabViewport, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)).apply {
            topMargin = dp(4)
        })
        panel.addView(artistTabBottomDivider, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        rebuildArtistTabs()
        panel.addView(listStatus, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })

        val listContainer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        listContainer.addView(trackRecycler, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(8)
        })
        listContainer.addView(loadingView, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        listContainer.addView(emptyView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        panel.addView(listContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(buildCloudDataSection(), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
        return panel
    }

    private fun buildCloudDataSection(): View {
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            fun updateBackground(focused: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.argb(if (focused) 42 else 24, 255, 255, 255))
                    setStroke(dp(if (focused) 2 else 1), if (focused) warmColor() else Color.argb(72, 220, 224, 232))
                }
            }
            updateBackground(false)
            setOnFocusChangeListener { view, focused ->
                updateBackground(focused)
                cloudDataTitle.setTextColor(if (focused) warmColor() else Color.WHITE)
                FocusFxHelper.applyFocusFxState(view, focused, cornerRadiusDp = 16)
            }
            setOnClickListener { showCloudSongsDialog() }
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
        val coverArea = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val h = View.MeasureSpec.getSize(heightMeasureSpec)
                if (h > 0) {
                    super.onMeasure(
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                    )
                } else {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                }
            }
        }.apply {
            clipChildren = true
            clipToPadding = true
            addView(coverRotator, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        val metadataArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = true
            clipToPadding = true
            songTitleView.gravity = Gravity.CENTER
            artistView.gravity = Gravity.CENTER
            addView(songTitleView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(artistView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        val topArea = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
            // 歌曲信息以整个右侧区域为基准居中；封面不参与排版，叠加在左上角。
            addView(metadataArea, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(coverArea, FrameLayout.LayoutParams(1, LayoutParams.MATCH_PARENT, Gravity.START or Gravity.TOP))
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
        )
        setupHeaderActionButton(
            button = uploadButton,
            iconRes = { R.drawable.ic_music_upload },
            onClick = { showUploadRepoDialog() },
        )
        uploadButton.contentDescription = "上传音乐"
    }

    private fun setupHeaderActionButton(
        button: HeaderActionButton,
        iconRes: () -> Int,
        onClick: () -> Unit,
    ) {
        button.setOnFocusChangeListener { v, hasFocus ->
            refreshActionButtonVisual(button, iconRes())
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 10)
        }
        button.setOnClickListener { onClick() }
        button.setOnKeyListener { view, keyCode, event -> handleTopActionKey(view, keyCode, event) }
        refreshActionButtonVisual(button, iconRes())
    }

    private fun handleTopActionKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                BoundaryFocusHandler.shake(view)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val tab = findArtistTabForFocus()
                if (tab != null) {
                    tab.requestFocus()
                } else {
                    val target = findRowViewForFocus(focusedListIndex)
                        ?: findRowViewForFocus(currentTrackIndex)
                    if (target == null) BoundaryFocusHandler.shake(view) else target.requestFocus()
                }
                true
            }
            else -> false
        }
    }

    private fun refreshActionButtonVisual(button: HeaderActionButton, iconRes: Int) {
        val focused = button.isFocused
        val (strokeDp, strokeColor) = ThemeManager.strokeFor(context, focused)
        button.setContentVisual(iconRes, Color.argb(220, 255, 255, 255))
        button.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(52, 32, 34, 40))
            setStroke(dp(strokeDp), strokeColor)
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
        allPlaylist = result.tracks
        lastSkippedRepos = result.skippedRepos
        val artists = distinctArtists()
        if (selectedArtist != null && artists.none { it.equals(selectedArtist, ignoreCase = true) }) {
            selectedArtist = null
        }
        rebuildArtistTabs()
        MusicRepoCatalog.repos.forEachIndexed { index, repo ->
            val count = result.repoTrackCounts[repo.id] ?: 0
            cloudRepoRows[repo.id]?.text = "music${index + 1}  $count 首"
        }
        applyArtistFilter(selectedArtist, preserveUrl)
    }

    private fun distinctArtists(): List<String> {
        val uniqueArtists = mutableListOf<String>()
        allPlaylist.forEach { track ->
            val artist = track.artist.trim().ifBlank { "未知歌手" }
            if (uniqueArtists.none { it.equals(artist, ignoreCase = true) }) uniqueArtists += artist
        }
        val mergedKeywords = mutableListOf<String>()
        uniqueArtists
            .sortedWith(compareBy<String> { it.length }.then(String.CASE_INSENSITIVE_ORDER))
            .forEach { artist ->
                if (mergedKeywords.none { keyword -> artist.contains(keyword, ignoreCase = true) }) {
                    mergedKeywords += artist
                }
            }
        return mergedKeywords.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun rebuildArtistTabs() {
        artistTabRow.removeAllViews()
        val tabs = listOf<String?>(null) + distinctArtists()
        tabs.forEachIndexed { index, artist ->
            if (index > 0) {
                artistTabRow.addView(View(context).apply {
                    setBackgroundColor(themedDividerColor())
                }, LinearLayout.LayoutParams(dp(1), dp(18)).apply {
                    marginStart = dp(7)
                    marginEnd = dp(7)
                })
            }
            val selected = if (artist == null) selectedArtist == null else artist.equals(selectedArtist, ignoreCase = true)
            val tab = TextView(context).apply {
                text = artist ?: "全部"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
                isFocusable = true
                isClickable = true
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(if (selected) warmColor() else Color.argb(210, 255, 255, 255))
                background = artistTabBackground(selected, false)
                setOnClickListener {
                    val preserveUrl = currentTrack()?.url
                    selectedArtist = artist
                    rebuildArtistTabs()
                    requestArtistTabFocus(index)
                    applyArtistFilter(artist, preserveUrl)
                }
                setOnFocusChangeListener { view, focused ->
                    background = artistTabBackground(selected, focused)
                    setTextColor(
                        when {
                            selected || focused -> warmColor()
                            else -> Color.argb(210, 255, 255, 255)
                        }
                    )
                    FocusFxHelper.applyFocusFxState(view, focused, cornerRadiusDp = 6)
                }
            }
            artistTabRow.addView(tab, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(32)))
        }
    }

    private fun requestArtistTabFocus(tabIndex: Int) {
        val childIndex = tabIndex * 2
        val tab = artistTabRow.getChildAt(childIndex) ?: return
        if (!tab.requestFocus()) {
            artistTabRow.post { artistTabRow.getChildAt(childIndex)?.requestFocus() }
        }
    }

    private fun artistTabBackground(selected: Boolean, focused: Boolean): GradientDrawable = GradientDrawable().apply {
        val accent = warmColor()
        cornerRadius = dp(if (focused) 6 else 14).toFloat()
        setColor(
            when {
                focused -> Color.TRANSPARENT
                selected -> Color.argb(54, Color.red(accent), Color.green(accent), Color.blue(accent))
                else -> Color.argb(18, 255, 255, 255)
            }
        )
        if (focused) setStroke(dp(1), accent)
    }

    private fun themedDividerColor(): Int {
        val accent = warmColor()
        return Color.argb(90, Color.red(accent), Color.green(accent), Color.blue(accent))
    }

    private fun applyArtistFilter(artist: String?, preserveUrl: String?) {
        playlist = if (artist == null) {
            allPlaylist
        } else {
            allPlaylist.filter {
                it.artist.trim().ifBlank { "未知歌手" }.contains(artist, ignoreCase = true)
            }
        }
        trackAdapter.notifyDataSetChanged()
        loadingView.visibility = View.GONE
        emptyView.visibility = if (playlist.isEmpty()) View.VISIBLE else View.GONE
        trackRecycler.visibility = if (playlist.isEmpty()) View.GONE else View.VISIBLE
        lastLoadSummary = when {
            allPlaylist.isEmpty() && lastSkippedRepos.isNotEmpty() -> lastSkippedRepos.joinToString("\n")
            lastSkippedRepos.isNotEmpty() -> "共 ${playlist.size} 首歌曲，跳过 ${lastSkippedRepos.size} 个仓库"
            else -> "共 ${playlist.size} 首歌曲"
        }
        updateListStatus(lastLoadSummary)

        val playingUrl = player?.currentMediaItem?.localConfiguration?.uri?.toString()
        val activeUrl = activeTrack?.url ?: preserveUrl
        val resolvedActiveTrack = activeUrl?.let { url -> allPlaylist.firstOrNull { it.url == url } }
            ?: allPlaylist.firstOrNull()
        activeTrack = resolvedActiveTrack

        if (playlist.isEmpty()) {
            emptyView.text = when {
                allPlaylist.isEmpty() && lastSkippedRepos.isNotEmpty() -> "暂无可播放歌曲\n${lastSkippedRepos.first()}"
                artist != null -> "暂无该歌手歌曲"
                else -> "暂无歌曲"
            }
            currentTrackIndex = -1
            focusedListIndex = 0
            if (resolvedActiveTrack == null) bindTrack(null)
            refreshPlaybackUi()
            return
        }

        currentTrackIndex = resolvedActiveTrack?.let { active ->
            playlist.indexOfFirst { it.url == active.url }
        } ?: -1
        focusedListIndex = focusedListIndex.coerceIn(0, playlist.lastIndex)
        if (resolvedActiveTrack != null && playingUrl == null) {
            bindTrack(resolvedActiveTrack, refreshArtwork = true)
        }
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
            progressBar.setProgress(0f)
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
            val lines = playlistRepository.loadLyrics(track)
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
        activeTrack = track
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
        refreshActionButtonVisual(loopButton, loopMode.iconRes)
        loopButton.contentDescription = loopMode.label
    }

    private fun handleTrackEnded() {
        if (playlist.isEmpty() || currentTrackIndex !in playlist.indices) return
        when (loopMode) {
            MusicLoopMode.ALL -> {
                val next = if (currentTrackIndex >= playlist.lastIndex) 0 else currentTrackIndex + 1
                selectTrack(next, autoPlay = true)
            }
            MusicLoopMode.SHUFFLE -> {
                val next = playlist.indices
                    .filter { it != currentTrackIndex }
                    .randomOrNull()
                    ?: currentTrackIndex
                selectTrack(next, autoPlay = true)
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
        val progressFraction = if (duration > 0) {
            (position.toDouble() / duration.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        progressBar.setProgress(progressFraction)
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

    private fun currentTrack(): MusicTrack? = activeTrack

    /** 查找当前选中的歌手 tab（或默认「全部」tab），供 DPAD 导航跳转使用。 */
    private fun findArtistTabForFocus(): View? {
        if (artistTabRow.childCount == 0) return null
        val artists = distinctArtists()
        val tabIndex = if (selectedArtist == null) 0
            else artists.indexOfFirst { it.equals(selectedArtist, ignoreCase = true) }
                .let { if (it < 0) 0 else it + 1 }
        return artistTabRow.getChildAt(tabIndex * 2) ?: artistTabRow.getChildAt(0)
    }

    private fun findRowViewForFocus(index: Int): View? {
        if (playlist.isEmpty()) return null
        val safeIndex = index.coerceIn(0, playlist.lastIndex)
        val holder = trackRecycler.findViewHolderForAdapterPosition(safeIndex)
        if (holder != null) return holder.itemView
        trackRecycler.scrollToPosition(safeIndex)
        return null
    }

    /** 播放列表专用状态图标：使用贝塞尔 Path 绘制圆角三角形，避免尖锐顶角。 */
    private class RoundedPlaybackIconView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val path = Path()
        private val barRect = RectF()
        private var playing = false

        fun setPlaybackState(isPlaying: Boolean, color: Int) {
            playing = isPlaying
            paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val w = width.toFloat()
            val h = height.toFloat()
            if (playing) {
                // 暂停态同步使用圆角矩形，保持与圆角播放图标一致的视觉语言。
                val barWidth = w * 0.20f
                val radius = barWidth * 0.46f
                barRect.set(w * 0.25f, h * 0.20f, w * 0.25f + barWidth, h * 0.80f)
                canvas.drawRoundRect(barRect, radius, radius, paint)
                barRect.set(w * 0.55f, h * 0.20f, w * 0.55f + barWidth, h * 0.80f)
                canvas.drawRoundRect(barRect, radius, radius, paint)
                return
            }

            // 三个顶角均以三次贝塞尔曲线收圆；主体略向右偏，保证视觉居中。
            path.reset()
            path.moveTo(w * 0.36f, h * 0.21f)
            path.cubicTo(w * 0.28f, h * 0.16f, w * 0.22f, h * 0.23f, w * 0.22f, h * 0.33f)
            path.lineTo(w * 0.22f, h * 0.67f)
            path.cubicTo(w * 0.22f, h * 0.77f, w * 0.28f, h * 0.84f, w * 0.36f, h * 0.79f)
            path.lineTo(w * 0.73f, h * 0.59f)
            path.cubicTo(w * 0.84f, h * 0.53f, w * 0.84f, h * 0.47f, w * 0.73f, h * 0.41f)
            path.lineTo(w * 0.36f, h * 0.21f)
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    /**
     * 自绘播放进度条。系统 ProgressBar 在替换为单层 GradientDrawable 后，
     * 不再区分 background/progress 图层，导致数值变化但已播放区域不可见。
     */
    private class PlaybackProgressView(context: Context) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val drawRect = RectF()
        private var progressFraction = 0f

        init {
            isFocusable = false
            setColors(
                playedColor = Color.rgb(245, 196, 81),
                trackColor = Color.argb(72, 255, 255, 255),
            )
        }

        fun setColors(playedColor: Int, trackColor: Int) {
            playedPaint.color = playedColor
            trackPaint.color = trackColor
            invalidate()
        }

        fun setProgress(fraction: Float) {
            val normalized = fraction.coerceIn(0f, 1f)
            if (progressFraction == normalized) return
            progressFraction = normalized
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val contentWidth = (width - paddingLeft - paddingRight).toFloat()
            val contentHeight = (height - paddingTop - paddingBottom).toFloat()
            if (contentWidth <= 0f || contentHeight <= 0f) return
            val left = paddingLeft.toFloat()
            val top = paddingTop.toFloat()
            val right = left + contentWidth
            val bottom = top + contentHeight
            val trackRadius = contentHeight / 2f

            drawRect.set(left, top, right, bottom)
            canvas.drawRoundRect(drawRect, trackRadius, trackRadius, trackPaint)

            if (progressFraction <= 0f) return
            val playedRight = left + contentWidth * progressFraction
            val playedRadius = minOf(trackRadius, (playedRight - left) / 2f)
            drawRect.set(left, top, playedRight, bottom)
            canvas.drawRoundRect(drawRect, playedRadius, playedRadius, playedPaint)
        }
    }

    private inner class HeaderActionButton : FrameLayout(context) {
        private val iconView = HeaderActionIconView(context).apply {
            isFocusable = false
            isClickable = false
        }

        init {
            isFocusable = true
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(iconView, FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        }

        fun setContentVisual(iconRes: Int, color: Int) {
            iconView.setIcon(iconRes, color)
        }
    }

    private inner class HeaderActionIconView(context: Context) : View(context) {
        private var iconRes: Int = R.drawable.ic_music_shuffle
        private var iconColor: Int = Color.WHITE
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 2.35f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val path = Path()

        fun setIcon(iconRes: Int, color: Int) {
            this.iconRes = iconRes
            iconColor = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            strokePaint.color = iconColor
            fillPaint.color = iconColor
            when (iconRes) {
                R.drawable.ic_music_repeat_all -> drawRepeat(canvas)
                R.drawable.ic_music_upload -> drawUpload(canvas)
                else -> drawShuffle(canvas)
            }
        }

        private fun drawShuffle(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()
            path.moveTo(w * 0.14f, h * 0.28f)
            path.cubicTo(w * 0.34f, h * 0.28f, w * 0.44f, h * 0.72f, w * 0.68f, h * 0.72f)
            path.lineTo(w * 0.75f, h * 0.72f)
            canvas.drawPath(path, strokePaint)
            drawArrowHead(canvas, w * 0.86f, h * 0.72f, pointingRight = true)

            path.reset()
            path.moveTo(w * 0.14f, h * 0.72f)
            path.cubicTo(w * 0.34f, h * 0.72f, w * 0.44f, h * 0.28f, w * 0.68f, h * 0.28f)
            path.lineTo(w * 0.75f, h * 0.28f)
            canvas.drawPath(path, strokePaint)
            drawArrowHead(canvas, w * 0.86f, h * 0.28f, pointingRight = true)
        }

        private fun drawRepeat(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()
            path.moveTo(w * 0.22f, h * 0.36f)
            path.cubicTo(w * 0.28f, h * 0.24f, w * 0.40f, h * 0.22f, w * 0.55f, h * 0.22f)
            path.lineTo(w * 0.74f, h * 0.22f)
            canvas.drawPath(path, strokePaint)
            drawArrowHead(canvas, w * 0.84f, h * 0.22f, pointingRight = true)

            path.reset()
            path.moveTo(w * 0.78f, h * 0.64f)
            path.cubicTo(w * 0.72f, h * 0.76f, w * 0.60f, h * 0.78f, w * 0.45f, h * 0.78f)
            path.lineTo(w * 0.26f, h * 0.78f)
            canvas.drawPath(path, strokePaint)
            drawArrowHead(canvas, w * 0.16f, h * 0.78f, pointingRight = false)
        }

        private fun drawUpload(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            // 上半部分：完整的云朵轮廓。
            path.reset()
            path.moveTo(w * 0.27f, h * 0.50f)
            path.cubicTo(w * 0.16f, h * 0.50f, w * 0.12f, h * 0.41f, w * 0.18f, h * 0.33f)
            path.cubicTo(w * 0.19f, h * 0.24f, w * 0.28f, h * 0.19f, w * 0.37f, h * 0.22f)
            path.cubicTo(w * 0.44f, h * 0.10f, w * 0.61f, h * 0.11f, w * 0.68f, h * 0.26f)
            path.cubicTo(w * 0.80f, h * 0.24f, w * 0.88f, h * 0.32f, w * 0.86f, h * 0.41f)
            path.cubicTo(w * 0.92f, h * 0.45f, w * 0.86f, h * 0.50f, w * 0.77f, h * 0.50f)
            path.lineTo(w * 0.27f, h * 0.50f)
            canvas.drawPath(path, strokePaint)

            // 下半部分：与云朵分离的向上箭头，明确表达“上传到云端”。
            path.reset()
            path.moveTo(w * 0.52f, h * 0.88f)
            path.lineTo(w * 0.52f, h * 0.59f)
            path.moveTo(w * 0.38f, h * 0.72f)
            path.lineTo(w * 0.52f, h * 0.58f)
            path.lineTo(w * 0.66f, h * 0.72f)
            canvas.drawPath(path, strokePaint)
        }

        private fun drawArrowHead(canvas: Canvas, tipX: Float, tipY: Float, pointingRight: Boolean) {
            val direction = if (pointingRight) -1f else 1f
            val size = minOf(width, height) * 0.14f
            path.reset()
            path.moveTo(tipX, tipY)
            path.lineTo(tipX + direction * size, tipY - size * 0.72f)
            path.lineTo(tipX + direction * size, tipY + size * 0.72f)
            path.close()
            canvas.drawPath(path, fillPaint)
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
            val icon = RoundedPlaybackIconView(parent.context).apply { id = View.generateViewId() }
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
        private val iconView: RoundedPlaybackIconView,
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
            iconView.setPlaybackState(
                isPlaying = isPlaying,
                color = if (isCurrent) accent else Color.argb(186, 255, 255, 255),
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
                    val tab = findArtistTabForFocus()
                    if (tab != null) tab.requestFocus() else loopButton.requestFocus()
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

    private fun showCloudSongsDialog() {
        if (cloudDeleteJob?.isActive == true) {
            toast("云端歌曲删除中，请稍候")
            return
        }
        cloudSongsDialog?.takeIf { it.isShowing }?.let {
            it.window?.decorView?.requestFocus()
            return
        }
        if (allPlaylist.isEmpty()) {
            toast("暂无云端歌曲")
            return
        }

        val selectedUrls = linkedSetOf<String>()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.dialogPanelBg(context, cornerRadiusDp = 18)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = null
        }
        header.addView(ClippedImageView(context).apply {
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setCircle(true)
            foreground = ContextCompat.getDrawable(context, R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        header.addView(TextView(context).apply {
            text = "云端歌曲"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val summary = TextView(context).apply {
            text = "共 ${allPlaylist.size} 首，可多选后从云端彻底删除"
            textSize = 14f
            setTextColor(Color.argb(206, 255, 255, 255))
            setPadding(0, dp(12), 0, dp(10))
        }
        panel.addView(summary)

        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            clipChildren = false
            clipToPadding = false
        }
        val focusables = mutableListOf<View>()
        lateinit var deleteButton: TextView
        allPlaylist.forEachIndexed { index, track ->
            val indicator = CloudSelectionIndicator(context)
            val title = TextView(context).apply {
                text = track.title
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val artist = TextView(context).apply {
                text = "歌手：${track.artist}"
                textSize = 13f
                setTextColor(Color.argb(182, 255, 255, 255))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                addView(artist, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(3)
                })
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                clipChildren = false
                clipToPadding = false
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = cloudSongRowBackground(false, false)
                addView(indicator, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(14) })
                addView(texts, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener {
                    if (!selectedUrls.add(track.url)) selectedUrls.remove(track.url)
                    indicator.checked = track.url in selectedUrls
                    background = cloudSongRowBackground(hasFocus(), indicator.checked)
                    deleteButton.text = "删除选中（${selectedUrls.size}）"
                    deleteButton.isEnabled = selectedUrls.isNotEmpty()
                    deleteButton.alpha = if (selectedUrls.isNotEmpty()) 1f else 0.5f
                }
                setOnFocusChangeListener { view, focused ->
                    background = cloudSongRowBackground(focused, indicator.checked)
                    title.setTextColor(if (focused) warmColor() else Color.WHITE)
                    FocusFxHelper.applyFocusFxState(view, focused, cornerRadiusDp = 14)
                }
            }
            rows.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(64)).apply {
                if (index > 0) topMargin = dp(6)
            })
            focusables += row
        }
        val maxListHeight = minOf(
            dp(320),
            (resources.displayMetrics.heightPixels - dp(310)).coerceAtLeast(dp(150)),
        )
        panel.addView(ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BOUNDS
            addView(rows, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, maxListHeight))

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(16), 0, 0)
        }
        lateinit var dialog: AlertDialog
        val cancelButton = dialogButton("取消") { dialog.dismiss() }
        deleteButton = dialogButton("删除选中（0）") {
            val selectedTracks = allPlaylist.filter { it.url in selectedUrls }
            if (selectedTracks.isNotEmpty()) showDeleteConfirmation(dialog, panel, selectedTracks)
        }.apply {
            isEnabled = false
            alpha = 0.5f
        }
        actions.addView(cancelButton, LinearLayout.LayoutParams(dp(150), dp(48)))
        actions.addView(deleteButton, LinearLayout.LayoutParams(dp(190), dp(48)).apply { marginStart = dp(12) })
        panel.addView(actions)
        focusables += cancelButton
        focusables += deleteButton

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { created ->
            cloudSongsDialog = created
            created.setOnShowListener { focusables.firstOrNull()?.requestFocus() }
            created.setOnDismissListener { if (cloudSongsDialog === created) cloudSongsDialog = null }
            created.show()
            created.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(560), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun showDeleteConfirmation(parentDialog: AlertDialog, panel: LinearLayout, tracks: List<MusicTrack>) {
        val palette = ThemeManager.currentPalette(context)
        val confirmPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.dialogPanelBg(context, cornerRadiusDp = 18)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(14), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                palette.dialogTitleGradient,
            ).apply { cornerRadius = dp(10).toFloat() }
        }
        header.addView(ClippedImageView(context).apply {
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setCircle(true)
            foreground = ContextCompat.getDrawable(context, R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(14) })
        header.addView(TextView(context).apply {
            text = "确认彻底删除？"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        confirmPanel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        confirmPanel.addView(TextView(context).apply {
            text = "将删除选中的 ${tracks.size} 首歌曲及其云端音频文件，此操作不可恢复。"
            textSize = 15f
            setTextColor(Color.argb(206, 255, 255, 255))
            setPadding(0, dp(14), 0, 0)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        lateinit var confirmDialog: AlertDialog
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(20), 0, 0)
        }
        val cancelButton = dialogButton("取消") { confirmDialog.dismiss() }
        val confirmButton = dialogButton("确认删除") {
            confirmDialog.dismiss()
            performCloudDelete(parentDialog, panel, tracks)
        }
        actions.addView(cancelButton, LinearLayout.LayoutParams(dp(140), dp(48)))
        actions.addView(confirmButton, LinearLayout.LayoutParams(dp(168), dp(48)).apply { marginStart = dp(12) })
        confirmPanel.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        confirmDialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(confirmPanel)
            .create()
        confirmDialog.setOnShowListener { cancelButton.requestFocus() }
        confirmDialog.show()
        confirmDialog.window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(520), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun performCloudDelete(dialog: AlertDialog, panel: LinearLayout, tracks: List<MusicTrack>) {
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        panel.removeAllViews()
        panel.gravity = Gravity.CENTER
        panel.addView(ProgressBar(context, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(warmColor())
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        panel.addView(TextView(context).apply {
            text = "正在从云端彻底删除 ${tracks.size} 首歌曲…"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(14), 0, 0)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        cloudDeleteJob = pageScope.launch {
            val result = withContext(Dispatchers.IO) { playlistRepository.deleteTracks(tracks) }
            val message = when {
                result.isCompleteSuccess -> "已从云端删除 ${result.deletedCount} 首歌曲"
                result.deletedCount > 0 -> "已删除 ${result.deletedCount}/${result.requestedCount} 首；${result.failedMessages.firstOrNull().orEmpty()}"
                else -> "删除失败：${result.failedMessages.firstOrNull() ?: "未知错误"}"
            }
            toast(message)
            dialog.dismiss()
            cloudDeleteJob = null
            loadPlaylist(showLoading = false, preserveUrl = currentTrack()?.url)
        }
    }

    private fun cloudSongRowBackground(focused: Boolean, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(if (focused) 38 else 18, 255, 255, 255))
        val stroke = if (focused || selected) warmColor() else Color.argb(82, 210, 214, 222)
        setStroke(dp(if (focused) 2 else 1), stroke)
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
        val batchCancel = dialogButton("取消剩余") {
            pendingBatchCancel = true
            uploadDialogLoadingText?.text = "正在结束当前文件…"
            uploadDialogCancelButton?.isEnabled = false
            uploadDialogCancelButton?.alpha = 0.5f
        }.apply { visibility = View.GONE }
        uploadDialogCancelButton = batchCancel

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
        panel.addView(batchCancel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(14)
        })

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { created ->
            uploadDialog = created
            created.setOnShowListener { focusables.firstOrNull()?.requestFocus() }
            created.setOnDismissListener {
                if (uploadDialog === created) {
                    uploadDialog = null
                    uploadDialogActions = null
                    uploadDialogLoading = null
                    uploadDialogLoadingText = null
                    uploadDialogCancelButton = null
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

    private fun setUploadDialogProgress(text: String, showCancel: Boolean) {
        if (uploadDialog?.isShowing != true) showUploadRepoDialog()
        uploadDialogActions?.visibility = View.GONE
        uploadDialogLoading?.visibility = View.VISIBLE
        uploadDialogLoadingText?.text = text
        uploadDialogCancelButton?.let {
            it.visibility = if (showCancel) View.VISIBLE else View.GONE
            it.isEnabled = showCancel
            it.alpha = if (showCancel) 1f else 0.5f
        }
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
            val (strokeDp, strokeColor) = ThemeManager.strokeFor(context, false)
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(52, 32, 34, 40))
            setStroke(dp(strokeDp), strokeColor)
        }
        setOnFocusChangeListener { v, hasFocus ->
            background = GradientDrawable().apply {
                val (strokeDp, strokeColor) = ThemeManager.strokeFor(context, hasFocus)
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(strokeDp), strokeColor)
            }
            setTextColor(if (hasFocus) warmColor() else Color.WHITE)
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 10)
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

    fun onMusicAudioDocumentResult(uris: List<android.net.Uri>, repoId: String) {
        val repoConfig = MusicRepoCatalog.byId(repoId) ?: run {
            closeUploadDialog()
            toast("上传失败：目标仓库不存在")
            return
        }
        if (uris.isEmpty()) {
            toast("已取消选择")
            return
        }
        val activity = context as? NewMainActivity
        uris.forEach { uri ->
            runCatching {
                activity?.contentResolver?.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startBatchUpload(uris, repoConfig)
    }

    private fun startBatchUpload(uris: List<android.net.Uri>, repoConfig: MusicRepoConfig) {
        if (uploadJob?.isActive == true) return
        pendingBatchCancel = false
        val total = uris.size
        setUploadDialogProgress("准备上传 $total 首歌曲…", showCancel = total > 1)
        uploadJob = pageScope.launch {
            var successCount = 0
            var partialCount = 0
            val failures = mutableListOf<String>()
            var cancelledAfter = -1
            uris.forEachIndexed { index, uri ->
                if (pendingBatchCancel) {
                    cancelledAfter = index
                    return@forEachIndexed
                }
                val displayName = runCatching { playlistRepository.displayNameOf(uri) }.getOrNull().orEmpty()
                    .ifBlank { "音频文件 ${index + 1}" }
                setUploadDialogProgress(
                    "第 ${index + 1}/$total 首：$displayName\n正在上传到 ${repoConfig.displayName}…",
                    showCancel = total > 1 && index < total - 1,
                )
                val result = withContext(Dispatchers.IO) { playlistRepository.uploadAudio(uri, repoConfig) }
                when (result) {
                    is MusicUploadResult.Success -> successCount++
                    is MusicUploadResult.PartialSuccess -> {
                        partialCount++
                        failures += "$displayName（部分成功：${result.message.ifBlank { "回写失败" }}）"
                    }
                    is MusicUploadResult.Error -> {
                        failures += "$displayName（${result.message.ifBlank { "未知原因" }}）"
                    }
                }
            }
            val skipped = if (cancelledAfter in 0 until total) total - cancelledAfter else 0
            val summary = buildString {
                append("上传结束：成功 $successCount")
                if (partialCount > 0) append("，部分成功 $partialCount")
                val failCount = failures.size - partialCount
                if (failCount > 0) append("，失败 $failCount")
                if (skipped > 0) append("，已跳过 $skipped")
            }
            val detail = failures.take(2).joinToString("；")
            toast(if (detail.isBlank()) summary else "$summary\n$detail")
            uploadJob = null
            pendingBatchCancel = false
            closeUploadDialog()
        }
    }

    private fun startUpload(uri: android.net.Uri, repoConfig: MusicRepoConfig) {
        startBatchUpload(listOf(uri), repoConfig)
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

    private inner class CloudSelectionIndicator(context: Context) : View(context) {
        var checked: Boolean = false
            set(value) {
                field = value
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = (minOf(width, height) / 2f) - dp(2)
            strokePaint.color = warmColor()
            canvas.drawCircle(width / 2f, height / 2f, radius, strokePaint)
            if (checked) {
                paint.color = warmColor()
                val innerRadius = (radius - strokePaint.strokeWidth - dp(3)).coerceAtLeast(dp(3).toFloat())
                canvas.drawCircle(width / 2f, height / 2f, innerRadius, paint)
            }
        }
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
