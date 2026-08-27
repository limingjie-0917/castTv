package com.bd.casttv.ui.framework

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.airplay.AirPlayService
import com.bd.casttv.dlna.DlnaRendererService
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.queue.PlayQueueStore
import com.bd.casttv.receiver.CastReceiverService
import com.bd.casttv.settings.CustomDockTabsStore
import com.bd.casttv.settings.Settings
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.CustomDockIconPresets
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.ui.framework.pages.CustomTabPage
import com.bd.casttv.ui.framework.pages.DiagnosticsPage
import com.bd.casttv.ui.framework.pages.DouyinCastPage
import com.bd.casttv.ui.framework.pages.FavoritesPage
import com.bd.casttv.ui.framework.pages.HelpPage
import com.bd.casttv.ui.framework.pages.HistoryPage
import com.bd.casttv.ui.framework.pages.HomePage
import com.bd.casttv.ui.framework.pages.MoreFunctionsPage
import com.bd.casttv.ui.framework.pages.PhoneHubPage
import com.bd.casttv.ui.framework.pages.SettingsPage
import com.bd.casttv.ui.framework.pages.WatchLaterPage
import com.bd.casttv.ui.framework.pages.WebParsePage

class NewMainActivity : AppCompatActivity(), SettingsChangeBus.Listener, PageContainer.RootFocusStateListener, com.bd.casttv.dlna.PlaybackController.StateObserver {
    companion object {
        /** 与老 [com.bd.casttv.ui.MainActivity.EXTRA_CAST_PENDING] 保持同一字符串，兼容通知栏兜底路径。 */
        const val EXTRA_CAST_PENDING = "extra_cast_pending"

        /** 外部唤起时，要求主页直接切到某个 pageId（例如 "settings"）。 */
        const val EXTRA_OPEN_PAGE_ID = "extra_open_page_id"

        private const val SIDE_PAGE_BUTTON_BREATH_MIN_ALPHA = 0.7f
        private const val SIDE_PAGE_BUTTON_BREATH_MAX_ALPHA = 1.0f
        private const val SIDE_PAGE_BUTTON_BREATH_MIN_SCALE = 1.05f
        private const val SIDE_PAGE_BUTTON_BREATH_MAX_SCALE = 1.15f
        private const val SIDE_PAGE_BUTTON_BREATH_DURATION_MS = 1400L
        private const val SIDE_PAGE_BUTTON_SIZE_DP = 62
        private const val SIDE_PAGE_BUTTON_MARGIN_DP = 8
        private const val SIDE_EDGE_GLOW_WIDTH_DP = 84
    }

    private lateinit var pageContainer: PageContainer
    private lateinit var indicator: BottomIndicatorBar
    private lateinit var rootLayout: FrameLayout

    private lateinit var settings: Settings
    private lateinit var topStatusBar: GlobalTopStatusBar
    private var leftPageBtn: View? = null
    private var rightPageBtn: View? = null
    private var leftEdgeGlow: View? = null
    private var rightEdgeGlow: View? = null
    private var sideBreathAnimator: AnimatorSet? = null
    private var isPageRootFocused = false
    private var skipNextResumePageRebind = false
    private var refreshDouyinTimelineOnNextResume = false

    private val WARM = Color.rgb(245, 196, 81)

    /** 冷启动进入 App 时的「稍后播放队列未播完」弹窗只展示一次。 */
    private var queueStartupDialogShown = false

    /** 冷启动进入 App 时的「云端推荐」检测只执行一次；有新增则弹窗，且本次启动不再检测稍后播放列表。 */
    private var incomingRecommendationsCheckDone = false

    /** 进入播放器后回到主页时，将焦点重置到当前页根节点（只执行一次）。 */
    private var focusRootOnNextResume = false

    /** 冷启动检测稍后播放队列期间的全局 Loading 遮罩。 */
    private var startupLoadingOverlay: View? = null

    /** 冷启动队列弹窗（避免重复创建）。 */
    private var queueStartupDialog: AlertDialog? = null

    /** 当前已注册 Page 懒工厂：只维护顺序与构造入口，首次切入时由 PageContainer 实例化。 */
    private val pageFactories: LinkedHashMap<String, () -> BasePage> = linkedMapOf()

    /** 已实例化 Page 缓存；与 PageContainer 懒工厂共用，便于开关移除时清理指定实例。 */
    private val pageInstances: MutableMap<String, BasePage> = mutableMapOf()

    private fun getPage(pageId: String): BasePage = pageInstances.getOrPut(pageId) { pageFactories[pageId]!!.invoke() }

    /** 当前叠加在最顶层的浮层页面栈；BACK/侧滑/翻页都会优先落到栈顶，避免误触底层 pageContainer。 */
    private val overlayPages = ArrayDeque<BasePage>()
    private val launcherOverlayReturnFocus = mutableMapOf<BasePage, View>()
    private val launcherOverlayOpenRects = mutableMapOf<BasePage, Rect>()
    private val launcherOverlayInterpolator = DecelerateInterpolator()
    private val overlayBlockedFocusability = mutableMapOf<ViewGroup, Int>()
    private var overlaySwallowNextBackUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        settings = Settings(this)
        startCastServicesSafely()

        val root = FrameLayout(this)
        rootLayout = root
        root.setBackgroundColor(android.graphics.Color.rgb(10, 12, 18))
        root.addView(WallpaperLayer(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        pageContainer = PageContainer(this)
        pageContainer.rootFocusStateListener = this
        pageContainer.pageChangeListener = object : PageContainer.PageChangeListener {
            override fun onPageChanged(page: BasePage, index: Int) {
                updateSidePageButtons()
            }
        }
        root.addView(pageContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 根节点/底部指示栏展示时，在屏幕左右边缘叠加宝蓝色侧边光晕；位于页面内容之上、状态栏与翻页按钮之下。
        leftEdgeGlow = SideEdgeGlowView(this, true)
        rightEdgeGlow = SideEdgeGlowView(this, false)
        root.addView(leftEdgeGlow, FrameLayout.LayoutParams(dp(SIDE_EDGE_GLOW_WIDTH_DP), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        root.addView(rightEdgeGlow, FrameLayout.LayoutParams(dp(SIDE_EDGE_GLOW_WIDTH_DP), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))

        topStatusBar = GlobalTopStatusBar(this).apply { setDeviceName(settings.dlnaDeviceName.ifBlank { Settings.DEFAULT_DEVICE_NAME }) }
        root.addView(topStatusBar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(40), Gravity.TOP))

        indicator = BottomIndicatorBar(this).apply { setBackgroundColor(android.graphics.Color.TRANSPARENT) }
        root.addView(indicator, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(66), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(6) })
        pageContainer.indicator = indicator

        // 侧边翻页按钮：默认常驻显示（仅在首页/末页对应方向隐藏），不参与焦点（用于触摸点击切页）。
        leftPageBtn = buildSidePageButton("‹").apply { setOnClickListener { pageContainer.switchBy(-1) } }
        rightPageBtn = buildSidePageButton("›").apply { setOnClickListener { pageContainer.switchBy(1) } }
        root.addView(leftPageBtn, FrameLayout.LayoutParams(dp(SIDE_PAGE_BUTTON_SIZE_DP), dp(SIDE_PAGE_BUTTON_SIZE_DP), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(SIDE_PAGE_BUTTON_MARGIN_DP) })
        root.addView(rightPageBtn, FrameLayout.LayoutParams(dp(SIDE_PAGE_BUTTON_SIZE_DP), dp(SIDE_PAGE_BUTTON_SIZE_DP), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(SIDE_PAGE_BUTTON_MARGIN_DP) })

        setContentView(root)
        SettingsChangeBus.addListener(this)

        pageContainer.onExitRequested = { com.bd.casttv.ui.framework.pages.ExitConfirmDialog.show(this) }
        pageContainer.bindLazyPages(buildPageSpecs(settings))
        if (isLauncherLayoutMode()) indicator.visibility = View.GONE
        updateSidePageButtons()

        handlePlayerReturnIntent(intent)
        handleCastPendingIntent(intent)
        handleOpenPageIntent(intent)

        runIncomingRecommendationsCheckAsync()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlayerReturnIntent(intent)
        handleCastPendingIntent(intent)
        handleOpenPageIntent(intent)
    }

    private fun handlePlayerReturnIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(com.bd.casttv.player.PlayerActivity.EXTRA_RETURN_SKIP_PAGE_RELOAD, false) == true) {
            skipNextResumePageRebind = true
            if (intent.getStringExtra(EXTRA_OPEN_PAGE_ID) == Settings.PAGE_ID_DOUYIN_CAST) {
                refreshDouyinTimelineOnNextResume = true
            }
            intent.removeExtra(com.bd.casttv.player.PlayerActivity.EXTRA_RETURN_SKIP_PAGE_RELOAD)
        }
    }

    private fun handleOpenPageIntent(intent: Intent?) {
        val pageId = intent?.getStringExtra(EXTRA_OPEN_PAGE_ID).orEmpty()
        if (pageId.isBlank()) return
        try {
            intent?.removeExtra(EXTRA_OPEN_PAGE_ID)
        } catch (_: Throwable) {
        }
        // 让 PageContainer 自己根据 pageId 找索引并切换。
        pageContainer.switchToPageId(pageId)
    }

    /**
     * 进入播放器后回到主页时，将焦点重置到当前页根节点（只执行一次）。
     * 用于「启动弹窗/侧边栏」跳转播放后，回到主页不再停留在列表项上。
     */
    fun requestFocusRootOnNextResume() {
        focusRootOnNextResume = true
    }

    fun isLauncherLayoutMode(): Boolean = settings.pageLayoutMode == Settings.PAGE_LAYOUT_LAUNCHER

    private fun currentPage(): BasePage? = pageContainer.currentPage

    /**
     * 从「稍后播放」队列播放某条资源。
     * 默认从启动弹窗/侧边栏等入口进入播放器后，回到主页时将焦点重置到页面根节点；
     * 页面内列表项播放可传入 [focusRootOnReturn] = false，自行恢复原列表项焦点。
     */
    fun startQueuePlayback(item: PlayQueueStore.QueueItem, toastText: String? = null, focusRootOnReturn: Boolean = true) {
        val store = PlayQueueStore.get(this)
        store.setStatus(item.id, PlayQueueStore.Status.PLAYING)
        com.bd.casttv.dlna.PlaybackController.queuePlaybackActive = true
        com.bd.casttv.dlna.PlaybackController.interruptedQueueUri = ""
        if (focusRootOnReturn) requestFocusRootOnNextResume()
        try {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(PlayerActivity.EXTRA_URI, item.uri)
                putExtra(PlayerActivity.EXTRA_TITLE, item.title)
                putExtra(PlayerActivity.EXTRA_SOURCE, item.source)
                // focusRootOnReturn=false 表示由页面自行恢复焦点，需要跳过页面重建，
                // 否则 onResume 走 else 分支不会调用 refreshCurrentPageAfterPlayerReturn()。
                if (!focusRootOnReturn) {
                    putExtra(PlayerActivity.EXTRA_RETURN_SKIP_PAGE_RELOAD, true)
                }
            })
            if (!toastText.isNullOrBlank()) {
                Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
        }
    }

    private fun runQueueStartupCheckAsync() {
        if (queueStartupDialogShown) return
        queueStartupDialogShown = true
        showStartupLoading(true)
        Thread {
            val store = PlayQueueStore.get(this)
            val needDialog = store.all().any { it.status != PlayQueueStore.Status.FINISHED }
            runOnUiThread {
                showStartupLoading(false)
                // 检测结束：焦点回到页面根节点。若需要弹窗，再由弹窗接管焦点。
                currentPage()?.focusToRoot()
                if (needDialog) showQueueStartupDialog()
            }
        }.start()
    }

    /**
     * 冷启动阶段先执行「云端推荐」检测（PRD 要求：早于稍后播放列表检测）：
     *  - 拉取云端 `recommendations.json` 失败/无新增 → 直接回退到 [runQueueStartupCheckAsync]；
     *  - 有新增 → 弹出 [com.bd.casttv.ui.framework.pages.IncomingRecommendationsDialog]，
     *    本次启动不再触发稍后播放列表检测（PRD：两个弹窗只能弹一个）。
     *
     * 本地已见集合的差集计算在后台线程完成；UI 层弹窗 onShow 时会把当天新增覆盖回本地 SharedPreferences，
     * 保证同一次冷启动内不会重复触发。
     */
    private fun runIncomingRecommendationsCheckAsync() {
        if (incomingRecommendationsCheckDone) return
        incomingRecommendationsCheckDone = true
        showStartupLoading(true)
        Thread({
            val recStore = com.bd.casttv.sync.RecommendationsStore(this)
            val seenStore = com.bd.casttv.sync.SeenRecommendationsStore(this)
            val incoming = try { recStore.fetchIncomingForToday() } catch (_: Throwable) { null }
            val seen = try { seenStore.currentForToday() } catch (_: Throwable) { emptySet() }
            val filtered = incoming?.mapNotNull { rec ->
                val newVideos = rec.videos.filter { v ->
                    (rec.collectionId to v.uniqueKey()) !in seen
                }
                if (newVideos.isEmpty()) null else rec.copy(videos = newVideos)
            }.orEmpty()

            runOnUiThread {
                showStartupLoading(false)
                if (filtered.isEmpty()) {
                    if (incoming != null) {
                        Toast.makeText(this, "今日暂无推荐内容更新！", Toast.LENGTH_SHORT).show()
                    }
                    // 无新增或拉取失败：继续走原有稍后播放列表检测。
                    runQueueStartupCheckAsync()
                    return@runOnUiThread
                }
                currentPage()?.focusToRoot()
                showIncomingRecommendationsDialog(filtered)
            }
        }, "recommend-check").start()
    }

    private fun showIncomingRecommendationsDialog(filtered: List<com.bd.casttv.sync.RecommendationsStore.Recommendation>) {
        if (isFinishing || isDestroyed) return
        com.bd.casttv.ui.framework.pages.IncomingRecommendationsDialog(
            context = this,
            incoming = filtered,
            onDone = {
                currentPage()?.focusToRoot()
            },
            onPlayVideo = { title, uri, source ->
                // 「播放推荐内容」：首条已在弹窗内 prepend 到队首，这里复用启动播放的统一入口拉起播放器。
                val queue = PlayQueueStore.get(this)
                val item = queue.findByUri(uri)
                    ?: PlayQueueStore.QueueItem(
                        id = "",
                        title = title,
                        uri = uri,
                        source = source,
                        status = PlayQueueStore.Status.PENDING,
                        addedAt = System.currentTimeMillis()
                    )
                if (item.id.isNotBlank()) {
                    startQueuePlayback(item, toastText = "播放推荐：${item.title}")
                } else {
                    // 兜底：队列 store 未持久化成功时也直接拉起播放器，不阻塞用户操作。
                    onNewCastRequest(uri, title, source)
                }
            }
        ).show()
    }

    private fun ensureStartupLoadingOverlay(): View {
        val existed = startupLoadingOverlay
        if (existed != null) return existed
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(196, 10, 12, 18))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setOnKeyListener { _, _, _ -> true }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        inner.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(44), dp(44)))
        inner.addView(TextView(this).apply {
            text = "正在获取推荐"
            textSize = 16f
            gravity = Gravity.CENTER
            includeFontPadding = true
            setSingleLine(false)
            setTextColor(Color.argb(235, 245, 245, 245))
            setPadding(dp(24), dp(14), dp(24), dp(6))
        }, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT))
        overlay.addView(inner, FrameLayout.LayoutParams(dp(260), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        rootLayout.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        overlay.visibility = View.GONE
        startupLoadingOverlay = overlay
        return overlay
    }

    private fun showStartupLoading(show: Boolean) {
        val overlay = ensureStartupLoadingOverlay()
        overlay.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            overlay.bringToFront()
            overlay.requestFocus()
        }
    }

    private fun showQueueStartupDialog() {
        if (isFinishing || isDestroyed) return
        if (queueStartupDialog?.isShowing == true) return

        val store = PlayQueueStore.get(this)
        val items = PlayQueueStore.SortConfig.apply(store.all()).take(50)
        if (items.none { it.status != PlayQueueStore.Status.FINISHED }) return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 与新版统一弹窗保持接近的横向留白；底部额外预留主页 dock 安全区，
            // 避免按钮压到 Home / 设置齿轮图标。
            setPadding(dp(28), dp(20), dp(28), dp(34))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(this@NewMainActivity).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(ClippedImageView(this).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        header.addView(TextView(this).apply {
            text = "「稍后播放列表」有未播放内容"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(header)

        panel.addView(TextView(this).apply {
            text = "稍后播放列表还有未播放完的内容。\n提示：可在主页底部打开「上滑查看更多」，从底部弹窗查看/切换。"
            textSize = 14f
            setTextColor(Color.argb(235, 220, 224, 235))
            setPadding(0, dp(10), 0, dp(8))
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        val queueCardWidth = (resources.displayMetrics.widthPixels * 0.62f * 0.7f).toInt()
        val adapter = QueueStartupAdapter(queueCardWidth) { item ->
            startQueuePlayback(item, toastText = "已切换：${item.title}")
            queueStartupDialog?.dismiss()
        }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NewMainActivity)
            this.adapter = adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            setPadding(dp(8), dp(4), dp(8), dp(4))
            clipChildren = true
            clipToPadding = true
        }
        adapter.submit(items)
        panel.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { topMargin = dp(6) })

        val closeBtn = dialogButton("关闭") { queueStartupDialog?.dismiss() }
        val continueBtn = dialogButton("继续播放") {
            val next = store.nextPending()
            if (next == null) {
                Toast.makeText(this, "队列为空", Toast.LENGTH_SHORT).show()
                queueStartupDialog?.dismiss()
                return@dialogButton
            }
            startQueuePlayback(next, toastText = "继续播放：${next.title}")
            queueStartupDialog?.dismiss()
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(4))
            addView(closeBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(continueBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }
        panel.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })

        val dialog = AlertDialog.Builder(this, R.style.Theme_CastTV_Dialog).setView(panel).create()
        queueStartupDialog = dialog

        val listener = {
            runOnUiThread {
                if (!isFinishing && !isDestroyed && queueStartupDialog?.isShowing == true) {
                    adapter.submit(PlayQueueStore.SortConfig.apply(store.all()).take(50))
                }
            }
        }
        store.addListener(listener)

        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val dm = resources.displayMetrics
                // 弹窗垂直居中，采用固定比例宽度
                setGravity(Gravity.CENTER)
                setLayout((dm.widthPixels * 0.62f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
                (decorView as? ViewGroup)?.apply {
                    clipChildren = false
                    clipToPadding = false
                }
            }
            FocusFxHelper.disableClippingUp(closeBtn, maxDepth = 5)
            FocusFxHelper.disableClippingUp(continueBtn, maxDepth = 5)
            // 默认焦点落在「继续播放」按钮
            continueBtn.post { continueBtn.requestFocus() }
        }
        dialog.setOnDismissListener {
            store.removeListener(listener)
            queueStartupDialog = null
            currentPage()?.focusToRoot()
        }
        try {
            dialog.show()
        } catch (_: Throwable) {
            store.removeListener(listener)
            queueStartupDialog = null
        }
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        fun refresh(focused: Boolean) {
            val selected = isSelected
            setTextColor(if (selected) WARM else Color.argb(235, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(170, 210, 214, 222))
                setColor(Color.argb(52, 32, 34, 40))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has ->
            refresh(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
        }
        setOnClickListener { click() }
    }

    private inner class QueueStartupAdapter(
        private val cardWidth: Int,
        private val onClick: (PlayQueueStore.QueueItem) -> Unit
    ) : RecyclerView.Adapter<QueueStartupAdapter.VH>() {
        private val data = mutableListOf<PlayQueueStore.QueueItem>()

        fun submit(items: List<PlayQueueStore.QueueItem>) {
            data.clear(); data.addAll(items)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = FrameLayout(this@NewMainActivity).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isFocusable = true
                isFocusableInTouchMode = false
                clipChildren = true
                clipToPadding = true
                setPadding(0, dp(8), 0, dp(8))
            }
            val card = LinearLayout(this@NewMainActivity).apply {
                orientation = LinearLayout.VERTICAL
                clipChildren = true
                clipToPadding = true
                // 内容卡片内部使用与统一弹窗相近的左右留白，避免标题/副文案贴边或被焦点描边裁切。
                setPadding(dp(24), dp(10), dp(24), dp(10))
            }
            val title = TextView(this@NewMainActivity).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.argb(235, 245, 245, 245))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val sub = TextView(this@NewMainActivity).apply {
                textSize = 13f
                setTextColor(Color.argb(200, 210, 214, 222))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(title)
            card.addView(sub)
            root.addView(
                card,
                FrameLayout.LayoutParams(
                    if (cardWidth > 0) cardWidth else ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL
                )
            )
            return VH(root, card, title, sub)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.bind(position, item)
        }

        private fun statusLabel(status: PlayQueueStore.Status): String = when (status) {
            PlayQueueStore.Status.PENDING -> "待播放"
            PlayQueueStore.Status.PLAYING -> "播放中"
            PlayQueueStore.Status.FINISHED -> "已播完"
        }

        inner class VH(
            private val root: FrameLayout,
            private val card: LinearLayout,
            private val title: TextView,
            private val sub: TextView
        ) : RecyclerView.ViewHolder(root) {
            private var boundStatus: PlayQueueStore.Status = PlayQueueStore.Status.PENDING

            fun bind(position: Int, item: PlayQueueStore.QueueItem) {
                boundStatus = item.status
                val index = position + 1
                title.text = String.format("%02d  %s", index, item.title.ifBlank { item.uri })
                sub.text = "${statusLabel(item.status)} · ${item.source.ifBlank { "queue" }} | 按 OK 键播放"
                root.isSelected = (item.status == PlayQueueStore.Status.PLAYING)
                refresh(false)
                root.setOnFocusChangeListener { _, has ->
                    refresh(has)
                    FocusFxHelper.applyFocusFxState(card, has, cornerRadiusDp = 14)
                }
                root.setOnKeyListener { _, keyCode, event ->
                    val isOkKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    if (!isOkKey) return@setOnKeyListener false
                    if (event.action == KeyEvent.ACTION_UP) onClick(item)
                    true
                }
                root.setOnClickListener { onClick(item) }
            }


            fun refresh(focused: Boolean) {
                val selected = root.isSelected
                title.setTextColor(if (selected) WARM else Color.argb(235, 245, 245, 245))
                card.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(170, 210, 214, 222))
                    setColor(Color.argb(40, 32, 34, 40))
                }
                card.alpha = if (selected || boundStatus != PlayQueueStore.Status.FINISHED) 1f else 0.72f
            }
        }
    }

    /**
     * 若通知栏兜底 intent 带 [EXTRA_CAST_PENDING]，且 [com.bd.casttv.dlna.PlaybackController] 中有可播放的 uri，
     * 则复用其状态自动拉起 [com.bd.casttv.player.PlayerActivity]（等价于老 MainActivity.queuePendingCastFromIntent 行为）。
     */
    private fun handleCastPendingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CAST_PENDING, false) != true) return
        intent.removeExtra(EXTRA_CAST_PENDING)
        val uri = com.bd.casttv.dlna.PlaybackController.currentUri
        if (uri.isBlank()) return
        try {
            val playerIntent = Intent(this, com.bd.casttv.player.PlayerActivity::class.java).apply {
                setData(android.net.Uri.parse(uri))
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(playerIntent)
        } catch (_: Throwable) { /* ignore start failure */ }
    }

    fun setTvMobileModeEnabled(enabled: Boolean) {
        settings.tvMobileModeEnabled = enabled
        updateSidePageButtons()
    }

    /**
     * 侧边翻页按钮（‹ ›）默认常驻显示，不再挂靠"移动模式/切换按钮"任何开关；
     * 仅受当前页索引边界约束：首页隐藏 ‹，末页隐藏 ›。
     */
    private fun updateSidePageButtons() {
        if (isLauncherLayoutMode()) {
            leftPageBtn?.visibility = View.GONE
            rightPageBtn?.visibility = View.GONE
            updateSideEdgeGlowVisibility()
            applySidePageButtonFocusStyle()
            return
        }
        val count = pageContainer.pageCount
        val idx = pageContainer.currentIndex

        leftPageBtn?.visibility = if (count > 0 && idx > 0) View.VISIBLE else View.GONE
        rightPageBtn?.visibility = if (count > 0 && idx < count - 1) View.VISIBLE else View.GONE
        updateSideEdgeGlowVisibility()
        applySidePageButtonFocusStyle()
    }

    private fun buildSidePageButton(text: String): View {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 33f
            setTextColor(android.graphics.Color.argb(185, 255, 255, 255))
            background = null
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = true
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
        }
    }

    private fun updateSideEdgeGlowVisibility() {
        val visible = if (isLauncherLayoutMode()) {
            isPageRootFocused || overlayPages.isNotEmpty()
        } else {
            isPageRootFocused && overlayPages.isEmpty() && indicator.visibility == View.VISIBLE
        }
        leftEdgeGlow?.visibility = if (visible) View.VISIBLE else View.GONE
        rightEdgeGlow?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applySidePageButtonFocusStyle() {
        sideBreathAnimator?.cancel()
        sideBreathAnimator = null

        val visibleButtons = listOf(leftPageBtn, rightPageBtn)
            .mapNotNull { it as? TextView }
            .filter { it.visibility == View.VISIBLE }

        listOf(leftPageBtn, rightPageBtn).forEach { view ->
            val button = view as? TextView ?: return@forEach
            button.animate().cancel()
            button.setTextColor(android.graphics.Color.argb(185, 255, 255, 255))
            button.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
            button.alpha = 1f
            button.scaleX = 1f
            button.scaleY = 1f
        }

        if (!isPageRootFocused || visibleButtons.isEmpty()) return

        val animators = visibleButtons.flatMap { button ->
            button.setTextColor(android.graphics.Color.rgb(255, 214, 102))
            button.setShadowLayer(dp(16).toFloat(), 0f, 0f, android.graphics.Color.argb(230, 255, 190, 58))
            button.alpha = SIDE_PAGE_BUTTON_BREATH_MAX_ALPHA
            button.scaleX = SIDE_PAGE_BUTTON_BREATH_MAX_SCALE
            button.scaleY = SIDE_PAGE_BUTTON_BREATH_MAX_SCALE
            listOf(
                ObjectAnimator.ofFloat(
                    button,
                    View.ALPHA,
                    SIDE_PAGE_BUTTON_BREATH_MIN_ALPHA,
                    SIDE_PAGE_BUTTON_BREATH_MAX_ALPHA
                ),
                ObjectAnimator.ofFloat(
                    button,
                    View.SCALE_X,
                    SIDE_PAGE_BUTTON_BREATH_MIN_SCALE,
                    SIDE_PAGE_BUTTON_BREATH_MAX_SCALE
                ),
                ObjectAnimator.ofFloat(
                    button,
                    View.SCALE_Y,
                    SIDE_PAGE_BUTTON_BREATH_MIN_SCALE,
                    SIDE_PAGE_BUTTON_BREATH_MAX_SCALE
                )
            )
        }.onEach { animator ->
            animator.duration = SIDE_PAGE_BUTTON_BREATH_DURATION_MS
            animator.repeatCount = ValueAnimator.INFINITE
            animator.repeatMode = ValueAnimator.REVERSE
        }

        sideBreathAnimator = AnimatorSet().apply {
            playTogether(animators)
            start()
        }
    }

    private fun startCastServicesSafely() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, DlnaRendererService::class.java))
        } catch (_: Throwable) {
        }
        try {
            AirPlayService.start(this)
        } catch (_: Throwable) {
        }
        try {
            CastReceiverService.start(this)
        } catch (_: Throwable) {
        }
    }

    private fun buildPageSpecs(settings: Settings): List<PageContainer.PageSpec> {
        rebuildPageFactories(settings)
        if (settings.pageLayoutMode == Settings.PAGE_LAYOUT_LAUNCHER) {
            return listOf(pageSpecFor("home"), pageSpecFor("more_functions"))
        }
        val customSpecs = pageFactories.keys.filter { it.startsWith("customtab_") }.map { pageSpecFor(it) }
        val fixed = linkedMapOf<String, List<PageContainer.PageSpec>>(
            "home" to listOf(pageSpecFor("home")),
            "favorites" to listOf(pageSpecFor("favorites")),
            Settings.PAGE_ID_WEB_PARSE to emptyList(),
            "customtabs" to customSpecs,
            "phonehub" to listOf(pageSpecFor("phonehub")),
            "history" to listOf(pageSpecFor("history")),
            "diagnostics" to listOf(pageSpecFor("diagnostics")),
            "help" to listOf(pageSpecFor("help")),
            "settings" to listOf(pageSpecFor("settings"))
        )
        val disabled = settings.disabledPageIds
        val orderedPageIds = settings.pageOrder.filter { it != Settings.PAGE_ID_DOUYIN_CAST }
        val ordered = orderedPageIds.flatMap { id -> if (disabled.contains(id)) emptyList() else fixed[id].orEmpty() }
        val missing = fixed.filterKeys { it !in orderedPageIds && it !in disabled }.values.flatten()
        val specs = (ordered + missing).ifEmpty { listOf(pageSpecFor("home")) }.toMutableList()
        if (settings.douyinCastEnabled && pageFactories.containsKey(Settings.PAGE_ID_DOUYIN_CAST)) {
            specs.removeAll { it.pageId == Settings.PAGE_ID_DOUYIN_CAST }
            val insertIndex = specs.indexOfFirst { it.pageId == "home" }.takeIf { it >= 0 }?.plus(1) ?: 1.coerceAtMost(specs.size)
            specs.add(insertIndex, pageSpecFor(Settings.PAGE_ID_DOUYIN_CAST))
        }
        if (settings.webParseEnabled && pageFactories.containsKey(Settings.PAGE_ID_WEB_PARSE)) {
            specs.removeAll { it.pageId == Settings.PAGE_ID_WEB_PARSE }
            val insertAfterIndex = listOf(Settings.PAGE_ID_DOUYIN_CAST, "home")
                .map { pageId -> specs.indexOfFirst { it.pageId == pageId } }
                .firstOrNull { it >= 0 }
            val insertIndex = insertAfterIndex?.plus(1) ?: 1.coerceAtMost(specs.size)
            specs.add(insertIndex, pageSpecFor(Settings.PAGE_ID_WEB_PARSE))
        }
        return specs
    }

    private fun rebuildPageFactories(settings: Settings) {
        val oldDouyinEnabled = pageFactories.containsKey(Settings.PAGE_ID_DOUYIN_CAST)
        val oldWebParseEnabled = pageFactories.containsKey(Settings.PAGE_ID_WEB_PARSE)
        pageFactories.clear()
        pageFactories["home"] = { HomePage(this) }
        pageFactories["watch_later"] = { WatchLaterPage(this) }
        pageFactories["more_functions"] = { MoreFunctionsPage(this) }
        if (settings.douyinCastEnabled) {
            pageFactories[Settings.PAGE_ID_DOUYIN_CAST] = { DouyinCastPage(this) }
        } else if (oldDouyinEnabled) {
            detachAndRemovePageInstance(Settings.PAGE_ID_DOUYIN_CAST)
        }
        pageFactories["favorites"] = { FavoritesPage(this) }
        if (settings.webParseEnabled) {
            pageFactories[Settings.PAGE_ID_WEB_PARSE] = { WebParsePage(this) }
        } else if (oldWebParseEnabled) {
            detachAndRemovePageInstance(Settings.PAGE_ID_WEB_PARSE)
        }
        CustomDockTabsStore(this).list().forEach { tab ->
            pageFactories["customtab_${tab.id}"] = { CustomTabPage(this, tab) }
        }
        pageFactories["phonehub"] = { PhoneHubPage(this) }
        pageFactories["history"] = { HistoryPage(this) }
        pageFactories["diagnostics"] = { DiagnosticsPage(this) }
        pageFactories["help"] = { HelpPage(this) }
        pageFactories["settings"] = { SettingsPage(this) }
        pageInstances.keys.filter { it !in pageFactories }.forEach { detachAndRemovePageInstance(it) }
    }

    private fun pageSpecFor(pageId: String): PageContainer.PageSpec = when {
        pageId == "home" -> PageContainer.PageSpec(pageId, "首页", R.drawable.ic_dock_home) { getPage(pageId) }
        pageId == "watch_later" -> PageContainer.PageSpec(pageId, "稍后播放/推荐", R.drawable.ic_history_tv) { getPage(pageId) }
        pageId == "more_functions" -> PageContainer.PageSpec(pageId, "更多功能", R.drawable.ic_dock_home) { getPage(pageId) }
        pageId == Settings.PAGE_ID_DOUYIN_CAST -> PageContainer.PageSpec(pageId, "抖音投屏", R.drawable.ic_history_tv) { getPage(pageId) }
        pageId == Settings.PAGE_ID_WEB_PARSE -> PageContainer.PageSpec(pageId, "网页解析播放", R.drawable.ic_web_parse) { getPage(pageId) }
        pageId == "favorites" -> PageContainer.PageSpec(pageId, "小新的收藏哦！", R.drawable.ic_dock_favorite) { getPage(pageId) }
        pageId.startsWith("customtab_") -> {
            val tabId = pageId.removePrefix("customtab_")
            val tab = CustomDockTabsStore(this).list().firstOrNull { it.id == tabId }
            PageContainer.PageSpec(
                pageId,
                tab?.name.orEmpty(),
                CustomDockIconPresets.iconFor(tab?.iconKey.orEmpty()).drawableRes
            ) { getPage(pageId) }
        }
        pageId == "phonehub" -> PageContainer.PageSpec(pageId, "连接手机", R.drawable.ic_phone_hub) { getPage(pageId) }
        pageId == "history" -> PageContainer.PageSpec(pageId, "历史播放", R.drawable.ic_history_tv) { getPage(pageId) }
        pageId == "diagnostics" -> PageContainer.PageSpec(pageId, "网络诊断", R.drawable.ic_dock_diagnostics) { getPage(pageId) }
        pageId == "help" -> PageContainer.PageSpec(pageId, "帮助", R.drawable.ic_help_tv) { getPage(pageId) }
        pageId == "settings" -> PageContainer.PageSpec(pageId, "设置", R.drawable.ic_settings_tv) { getPage(pageId) }
        else -> PageContainer.PageSpec(pageId, pageId, 0) { getPage(pageId) }
    }

    private fun detachAndRemovePageInstance(pageId: String) {
        val page = pageInstances.remove(pageId) ?: return
        (page.parent as? ViewGroup)?.removeView(page)
    }

    override fun onResume() {
        super.onResume()
        com.bd.casttv.dlna.PlaybackController.uiInForeground = true
        com.bd.casttv.dlna.PlaybackController.registerObserver(this)
        if (skipNextResumePageRebind) {
            skipNextResumePageRebind = false
            refreshChromeWithoutPageRebind()
            refreshCurrentPageAfterPlayerReturn()
        } else {
            // 普通锁屏/解锁也会触发 onResume。这里不能重建全部 Page：
            // FavoritesPage 构造时会同步读取收藏文件并渲染列表，若此时后台 HTTP/云同步/收藏写入
            // 正持有 FavoritesStore 全局锁，主线程会在点击恢复后的首个事件前被锁等待，容易触发 ANR。
            // 设置变更已由 SettingsChangeBus.onSettingsChanged() 主动触发重建；普通恢复只刷新轻量 chrome。
            refreshChromeWithoutPageRebind()
        }
        if (focusRootOnNextResume) {
            focusRootOnNextResume = false
            pageContainer.post { currentPage()?.focusToRoot() }
        }
    }

    private fun refreshChromeWithoutPageRebind() {
        if (::topStatusBar.isInitialized) {
            topStatusBar.setDeviceName(settings.dlnaDeviceName.ifBlank { Settings.DEFAULT_DEVICE_NAME })
            topStatusBar.refreshTheme()
        }
        indicator.setScale(settings.indicatorScale)
        indicator.visibility = if (isLauncherLayoutMode()) View.GONE else View.VISIBLE
        WallpaperManager.notifyChanged(this)
        updateSidePageButtons()
    }

    private fun refreshCurrentPageAfterPlayerReturn() {
        (currentPage() as? FavoritesPage)?.refreshAfterPlayerReturn()
        (currentPage() as? CustomTabPage)?.refreshAfterPlayerReturn()
        (currentPage() as? WatchLaterPage)?.refreshAfterPlayerReturn()
        if (!refreshDouyinTimelineOnNextResume) return
        refreshDouyinTimelineOnNextResume = false
        (currentPage() as? DouyinCastPage)?.refreshTimelineAfterPlayerReturn()
    }

    override fun onDestroy() {
        sideBreathAnimator?.cancel()
        sideBreathAnimator = null
        SettingsChangeBus.removeListener(this)
        com.bd.casttv.dlna.PlaybackController.unregisterObserver(this)
        super.onDestroy()
    }

    override fun onPause() {
        com.bd.casttv.dlna.PlaybackController.uiInForeground = false
        com.bd.casttv.dlna.PlaybackController.unregisterObserver(this)
        super.onPause()
    }

    override fun onNewCastRequest(uri: String, title: String, sourceHint: String) {
        if (isFinishing || isDestroyed || uri.isBlank()) return
        (currentPage() as? CustomTabPage)?.pausePreviewForExternalCast()
        val playerIntent = Intent(this, com.bd.casttv.player.PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(com.bd.casttv.player.PlayerActivity.EXTRA_URI, uri)
            putExtra(com.bd.casttv.player.PlayerActivity.EXTRA_TITLE, title)
            putExtra(com.bd.casttv.player.PlayerActivity.EXTRA_SOURCE, sourceHint)
            if (currentPage()?.pageId == Settings.PAGE_ID_DOUYIN_CAST) {
                putExtra(com.bd.casttv.player.PlayerActivity.EXTRA_RETURN_SKIP_PAGE_RELOAD, true)
                putExtra(EXTRA_OPEN_PAGE_ID, Settings.PAGE_ID_DOUYIN_CAST)
            }
        }
        try {
            startActivity(playerIntent)
        } catch (_: Throwable) {
        }
    }

    override fun onSettingsChanged() {
        // 统一用 Activity 持有的 settings，避免开关修改后被新实例覆盖。
        if (::topStatusBar.isInitialized) {
            topStatusBar.setDeviceName(settings.dlnaDeviceName.ifBlank { Settings.DEFAULT_DEVICE_NAME })
            topStatusBar.refreshTheme()
        }
        indicator.setScale(settings.indicatorScale)
        pageContainer.bindLazyPages(buildPageSpecs(settings))
        indicator.visibility = if (isLauncherLayoutMode()) View.GONE else View.VISIBLE
        pageContainer.instantiatedPages().forEach { it.refreshTheme() }
        WallpaperManager.notifyChanged(this)
        updateSidePageButtons()
    }

    override fun onRootFocusStateChanged(active: Boolean) {
        isPageRootFocused = active
        indicator.setRootFocusVisible(active)
        updateSideEdgeGlowVisibility()
        applySidePageButtonFocusStyle()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 冷启动检测队列期间禁止任何按键操作（避免用户切换页面 / 触发业务）。
        if (startupLoadingOverlay?.visibility == View.VISIBLE) return true

        // 存在浮层时，BACK 优先由浮层体系处理，避免误触底层 pageContainer 的返回/退出逻辑。
        if (overlayPages.isNotEmpty()) {
            val top = overlayPages.last()
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && overlaySwallowNextBackUp) {
                    overlaySwallowNextBackUp = false
                    return true
                }
                if (event.action == KeyEvent.ACTION_DOWN) {
                    overlaySwallowNextBackUp = true
                    if (top is MoreFunctionsPage) {
                        return top.handleLauncherBack()
                    }
                    if (top.hasFocusAwayFromRoot()) {
                        top.focusToRoot()
                    } else {
                        closeLauncherFunctionPage(top)
                    }
                    return true
                }
            }
            // 非 BACK 键优先交给栈顶浮层页处理；若浮层未消费，放行给系统焦点框架，
            // 让方向键在当前 overlay 页面内部正常移动。防穿透由下层页面的焦点隔离保证。
            if (top.dispatchKeyEvent(event)) return true
            return super.dispatchKeyEvent(event)
        }
        if (::pageContainer.isInitialized && event.keyCode == KeyEvent.KEYCODE_BACK && pageContainer.currentPage?.pageId == "home") {
            if (pageContainer.currentPage?.dispatchKeyEvent(event) == true) return true
        }
        if (::pageContainer.isInitialized && pageContainer.handleBackKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        if (overlayPages.isNotEmpty()) {
            popOverlayPage(overlayPages.last())
            return
        }
        if (!pageContainer.backToHomeOrExit()) super.onBackPressed()
    }

    /**
     * 启动台模式：首页底部 Indicator 获焦后打开「更多功能」浮层，并让主页轻微缩小上移。
     */
    fun openLauncherMoreFunctions(triggerFocus: View?) {
        if (!isLauncherLayoutMode()) return
        if (overlayPages.any { it is MoreFunctionsPage }) return
        val page = MoreFunctionsPage(this, triggerFocus)
        animateLauncherHome(open = true)
        pushOverlayPage(page)
        page.post {
            val h = page.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            page.translationY = h.toFloat()
            page.animate().translationY(0f).setDuration(280L).start()
        }
    }

    fun closeLauncherMoreFunctions(page: MoreFunctionsPage, focusTarget: View?) {
        val h = page.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        page.animate()
            .translationY(h.toFloat())
            .setDuration(240L)
            .withEndAction {
                popOverlayPage(page)
                animateLauncherHome(open = false)
                focusTarget?.tag = "suppress_launcher_open_once"
                focusTarget?.post { focusTarget.requestFocus() }
            }
            .start()
    }

    fun openLauncherFunctionPage(pageId: String, triggerFocus: View?) {
        val factory = pageFactories[pageId]
        if (factory == null) {
            Toast.makeText(this, "功能暂不可用", Toast.LENGTH_SHORT).show()
            return
        }
        val page = factory.invoke()
        val trigger = triggerFocus ?: overlayPages.lastOrNull()?.findFocus() ?: pageContainer
        launcherOverlayReturnFocus[page] = trigger
        val openRect = viewRectInRoot(trigger)
        if (openRect != null) launcherOverlayOpenRects[page] = Rect(openRect)
        page.alpha = 0f
        pushOverlayPage(page, requestFocusAfterAttach = false)
        page.post {
            val startRect = launcherOverlayOpenRects[page]
            if (startRect != null && page.width > 0 && page.height > 0) {
                applyOverlayTransformFromRect(page, startRect)
                page.animate().cancel()
                page.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(280L)
                    .setInterpolator(launcherOverlayInterpolator)
                    .withEndAction { page.focusToFirstContent() }
                    .start()
            } else {
                page.pivotX = page.width / 2f
                page.pivotY = page.height / 2f
                page.scaleX = 0.94f
                page.scaleY = 0.94f
                page.animate().cancel()
                page.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(280L)
                    .setInterpolator(launcherOverlayInterpolator)
                    .withEndAction { page.focusToFirstContent() }
                    .start()
            }
        }
    }

    private fun closeLauncherFunctionPage(page: BasePage) {
        val returnFocus = launcherOverlayReturnFocus.remove(page)
        if (isLauncherLayoutMode() && overlayPages.size >= 2 && page !is MoreFunctionsPage) {
            val endRect = returnFocus?.let { viewRectInRoot(it) } ?: launcherOverlayOpenRects[page]
            page.animate().cancel()
            if (endRect != null && page.width > 0 && page.height > 0) {
                val transform = computeOverlayTransform(page, endRect)
                page.animate()
                    .translationX(transform.translationX)
                    .translationY(transform.translationY)
                    .scaleX(transform.scaleX)
                    .scaleY(transform.scaleY)
                    .alpha(0f)
                    .setDuration(280L)
                    .setInterpolator(launcherOverlayInterpolator)
                    .withStartAction {
                        page.pivotX = transform.pivotX
                        page.pivotY = transform.pivotY
                    }
                    .withEndAction {
                        page.pivotX = page.width / 2f
                        page.pivotY = page.height / 2f
                        page.scaleX = 1f
                        page.scaleY = 1f
                        page.translationX = 0f
                        page.translationY = 0f
                        page.alpha = 1f
                        launcherOverlayOpenRects.remove(page)
                        popOverlayPage(page)
                        returnFocus?.post { returnFocus.requestFocus() }
                    }
                    .start()
            } else {
                page.pivotX = page.width / 2f
                page.pivotY = page.height / 2f
                page.animate()
                    .scaleX(0.94f)
                    .scaleY(0.94f)
                    .alpha(0f)
                    .setDuration(240L)
                    .setInterpolator(launcherOverlayInterpolator)
                    .withEndAction {
                        page.scaleX = 1f
                        page.scaleY = 1f
                        page.alpha = 1f
                        launcherOverlayOpenRects.remove(page)
                        popOverlayPage(page)
                        returnFocus?.post { returnFocus.requestFocus() }
                    }
                    .start()
            }
        } else {
            launcherOverlayOpenRects.remove(page)
            popOverlayPage(page)
            returnFocus?.post { returnFocus.requestFocus() }
        }
    }

    private fun animateLauncherHome(open: Boolean) {
        pageContainer.animate().cancel()
        pageContainer.animate()
            .scaleX(if (open) 0.98f else 1f)
            .scaleY(if (open) 0.95f else 1f)
            .translationY(if (open) -dp(34).toFloat() else 0f)
            .setDuration(280L)
            .start()
    }

    private fun viewRectInRoot(view: View): Rect? {
        if (view.width <= 0 || view.height <= 0 || !view.isAttachedToWindow) return null
        val viewLoc = IntArray(2)
        val rootLoc = IntArray(2)
        view.getLocationOnScreen(viewLoc)
        rootLayout.getLocationOnScreen(rootLoc)
        val left = (viewLoc[0] - rootLoc[0]).coerceAtLeast(0)
        val top = (viewLoc[1] - rootLoc[1]).coerceAtLeast(0)
        return Rect(left, top, left + view.width, top + view.height)
    }

    private data class OverlayTransform(
        val pivotX: Float,
        val pivotY: Float,
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float
    )

    private fun computeOverlayTransform(page: View, rect: Rect): OverlayTransform {
        val pageW = page.width.coerceAtLeast(1)
        val pageH = page.height.coerceAtLeast(1)
        val scaleX = (rect.width().toFloat() / pageW).coerceIn(0.01f, 1f)
        val scaleY = (rect.height().toFloat() / pageH).coerceIn(0.01f, 1f)
        val pivotX = rect.exactCenterX()
        val pivotY = rect.exactCenterY()
        val translationX = rect.left - pivotX * (1f - scaleX)
        val translationY = rect.top - pivotY * (1f - scaleY)
        return OverlayTransform(pivotX, pivotY, scaleX, scaleY, translationX, translationY)
    }

    private fun applyOverlayTransformFromRect(page: View, rect: Rect) {
        val transform = computeOverlayTransform(page, rect)
        page.pivotX = transform.pivotX
        page.pivotY = transform.pivotY
        page.scaleX = transform.scaleX
        page.scaleY = transform.scaleY
        page.translationX = transform.translationX
        page.translationY = transform.translationY
    }

    private fun isolateCurrentTopForOverlay() {
        val target: ViewGroup = overlayPages.lastOrNull() ?: pageContainer
        if (!overlayBlockedFocusability.containsKey(target)) {
            overlayBlockedFocusability[target] = target.descendantFocusability
        }
        target.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        target.clearFocus()
    }

    private fun restoreOverlayFocusIsolationAfterPop() {
        if (overlayPages.isEmpty()) {
            overlayBlockedFocusability.remove(pageContainer)?.let { pageContainer.descendantFocusability = it }
            return
        }
        val newTop = overlayPages.last()
        overlayBlockedFocusability.remove(newTop)?.let { newTop.descendantFocusability = it }
    }

    /**
     * 将 [page] 作为浮层压入 Activity 根 FrameLayout（在 pageContainer / topStatusBar / 侧翻页按钮之上，但仍在系统 UI 之下）：
     *  - 挂载后立即请求焦点到浮层的首个可聚焦内容，防止焦点仍落在被遮挡的 pageContainer 里。
     *  - 隐藏底部指示器与侧翻页按钮，避免和浮层功能按钮混淆。
     */
    fun pushOverlayPage(page: BasePage, requestFocusAfterAttach: Boolean = true) {
        if (page.parent != null) (page.parent as ViewGroup).removeView(page)
        page.animate().cancel()
        page.translationX = 0f
        page.translationY = 0f
        page.x = 0f
        page.y = 0f
        val overlayLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            leftMargin = 0
            topMargin = 0
            rightMargin = 0
            bottomMargin = 0
            gravity = Gravity.FILL
        }
        page.layoutParams = overlayLp
        page.isFocusable = true
        page.isFocusableInTouchMode = true
        page.isClickable = true
        // 当前浮层页自身必须允许内部子 View 获焦；点击穿透防护只隔离下层页面，不能误拦截当前层焦点链。
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        isolateCurrentTopForOverlay()
        if (page.background == null) {
            val palette = ThemeManager.currentPalette(this)
            page.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                palette.dialogTitleGradient.map { Color.argb(244, Color.red(it), Color.green(it), Color.blue(it)) }.toIntArray()
            ).apply {
                cornerRadius = 0f
            }
        }
        rootLayout.addView(page, overlayLp)
        overlayPages.addLast(page)
        indicator.visibility = View.GONE
        leftPageBtn?.visibility = View.GONE
        rightPageBtn?.visibility = View.GONE
        updateSideEdgeGlowVisibility()
        // 浮层页不经过 PageContainer.switchTo，因此这里必须显式补齐页面进入生命周期。
        // 自定义 Tab 的 3 秒停留后预览播放、预览播放器 acquire/resume 都依赖 onEnter 启动。
        page.onEnter()
        if (requestFocusAfterAttach) {
            page.post { page.focusToFirstContent() }
        }
    }

    /**
     * 弹出并回收 [page]（若不是栈顶或未挂载则忽略）；栈空后恢复底部指示器与侧翻页按钮，并把焦点交还给当前 pageContainer 的活动页。
     */
    fun popOverlayPage(page: BasePage) {
        launcherOverlayReturnFocus.remove(page)
        launcherOverlayOpenRects.remove(page)
        // 与 pushOverlayPage 中的显式 onEnter 配对，避免自定义 Tab 预览播放器离开浮层后继续占用资源。
        page.onLeave()
        if (overlayPages.isEmpty() || overlayPages.last() !== page) {
            // 允许幂等调用：即便不是栈顶也把它从栈里移除，避免脏数据。
            overlayPages.remove(page)
            if (page.parent != null) (page.parent as ViewGroup).removeView(page)
        } else {
            overlayPages.removeLast()
            if (page.parent != null) (page.parent as ViewGroup).removeView(page)
        }
        restoreOverlayFocusIsolationAfterPop()
        if (overlayPages.isEmpty()) {
            indicator.visibility = if (isLauncherLayoutMode()) View.GONE else View.VISIBLE
            updateSidePageButtons()
        } else {
            updateSideEdgeGlowVisibility()
            // 子浮层（如设置页）关闭后，若重新露出的栈顶是「更多功能」页，
            // 刷新其功能卡片列表，使在设置页新增/删除的自定义 Tab 卡片即时同步。
            (overlayPages.last() as? MoreFunctionsPage)?.let { top ->
                top.post { top.refreshCards() }
            }
        }
    }

    private class SideEdgeGlowView(context: Context, private val isLeft: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            visibility = GONE
            alpha = 0.92f
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return

            val royalBlue = Color.rgb(65, 105, 225)
            val transparentBlue = Color.argb(0, 65, 105, 225)
            val edgeBlue = Color.argb(215, Color.red(royalBlue), Color.green(royalBlue), Color.blue(royalBlue))
            val midBlue = Color.argb(96, Color.red(royalBlue), Color.green(royalBlue), Color.blue(royalBlue))
            paint.shader = if (isLeft) {
                LinearGradient(
                    0f,
                    0f,
                    width.toFloat(),
                    0f,
                    intArrayOf(edgeBlue, midBlue, transparentBlue),
                    floatArrayOf(0f, 0.38f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    width.toFloat(),
                    0f,
                    0f,
                    0f,
                    intArrayOf(edgeBlue, midBlue, transparentBlue),
                    floatArrayOf(0f, 0.38f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
