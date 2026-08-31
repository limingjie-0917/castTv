package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.bd.casttv.R
import com.bd.casttv.dlna.PlaybackController
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.queue.PlayQueueStore
import com.bd.casttv.settings.CustomDockTabsStore
import com.bd.casttv.settings.Settings
import com.bd.casttv.sync.GiteeSyncManager
import com.bd.casttv.sync.RecommendationsStore
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.NewMainActivity

class HomePage(context: Context) : BasePage(context), PlaybackController.StateObserver {
    override val pageId = "home"
    override val pageTitle = "首页"
    override val pageIconRes = R.drawable.ic_dock_home

    private val handler = Handler(Looper.getMainLooper())
    private val settings = Settings(context)
    private val tutorialText = TextView(context)
    private val stateCard = LinearLayout(context)
    private val stateTitle = TextView(context)
    private val stateDesc = TextView(context)
    private val guideArrow = TextView(context)
    private val bottomGuide = LinearLayout(context)
    private val functionBar = HorizontalScrollView(context)
    private val launcherMode = settings.pageLayoutMode == Settings.PAGE_LAYOUT_LAUNCHER
    private var guideAnimating = false
    private var tutorialIndex = 0
    private var playing = false

    // -------------------- 主页：底部「查看更多」弹窗 --------------------

    private val queueStore: PlayQueueStore by lazy { PlayQueueStore.get(context.applicationContext) }
    private val favoritesStore: FavoritesStore by lazy { FavoritesStore(context.applicationContext) }
    private val syncManager: GiteeSyncManager by lazy { GiteeSyncManager(favoritesStore) }
    private val recommendationsStore: RecommendationsStore by lazy { RecommendationsStore(context.applicationContext) }
    private var homeBodyRoot: ViewGroup? = null
    private var homeBodyRootDescendantFocusability: Int = ViewGroup.FOCUS_BEFORE_DESCENDANTS

    private var bottomSheetOpen = false
    private var bottomSheetTriggerFocus: View? = null
    private var bottomSheetLastColumn = 0
    private val bottomSheetFirstItems = arrayOfNulls<View>(3)
    private val bottomSheetHeaderFirstActions = arrayOfNulls<View>(3)
    private var bottomSheetRecommendations: List<RecommendationsStore.Recommendation> = emptyList()
    private var bottomSheetRecommendationsCleared = false
    private var bottomSheetPopularCollections: List<GiteeSyncManager.CloudCollection> = emptyList()

    private val bottomSheetQueueColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        clipChildren = false
        clipToPadding = false
    }
    private val bottomSheetRecommendColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        clipChildren = false
        clipToPadding = false
    }
    private val bottomSheetPopularColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        clipChildren = false
        clipToPadding = false
    }

    private val bottomSheetCloseButton = crayonTextButton("收起") { closeBottomSheet() }.apply {
        setOnKeyListener { _, _, event -> handleBottomSheetCloseKey(event) }
    }

    private val bottomSheet = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(0xFF4169E1.toInt())
        }
        setPadding(dp(20), dp(16), dp(20), dp(18))
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = false
        setOnKeyListener { _, _, event -> handleBottomSheetKey(event) }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(crayonDialogTitle("查看更多"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bottomSheetCloseButton, LinearLayout.LayoutParams(dp(104), dp(42)))
        }
        addView(header)

        val columns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
            addView(bottomSheetColumn("稍后播放", null, bottomSheetQueueColumn, 0, onClear = { clearBottomSheetQueue() }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(8) })
            addView(bottomSheetColumn("推荐内容", "(本地缓存)", bottomSheetRecommendColumn, 1, onClear = { clearBottomSheetRecommendations() }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            addView(bottomSheetColumn("热门内容", null, bottomSheetPopularColumn, 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = dp(8) })
        }
        addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
    }

    private val bottomSheetOverlay = FrameLayout(context).apply {
        visibility = View.GONE
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isFocusableInTouchMode = true
        setOnKeyListener { _, _, event -> handleBottomSheetKey(event) }
        addView(
            bottomSheet,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(430), Gravity.BOTTOM).apply {
                leftMargin = dp(46)
                rightMargin = dp(46)
                bottomMargin = dp(18)
            }
        )
    }

    private val onQueueChanged = {
        if (bottomSheetOpen) renderBottomSheetQueueColumn()
    }

    // -------------------- 原有主页逻辑（保持不变） --------------------

    private val tutorialRunnable = object : Runnable {
        override fun run() {
            val list = buildTutorialMessages()
            if (list.isNotEmpty()) {
                tutorialIndex = (tutorialIndex + 1) % list.size
                tutorialText.animate().alpha(0f).setDuration(160).withEndAction {
                    tutorialText.text = list[tutorialIndex]
                    tutorialText.animate().alpha(1f).setDuration(180).start()
                }.start()
                handler.postDelayed(this, 4_000L)
            }
        }
    }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(96), dp(12), dp(96), dp(48))
        }
        homeBodyRoot = root
        homeBodyRootDescendantFocusability = root.descendantFocusability
        tutorialText.apply {
            textSize = 16f
            setTextColor(Color.rgb(238, 232, 218))
            gravity = Gravity.CENTER
        }
        stateCard.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            background = cardBg(false)
            setPadding(dp(27), dp(19), dp(27), dp(19))
            setOnFocusChangeListener { v, has ->
                applyStateCardBackground(has)
                v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(140).start()
            }
            setOnClickListener {
                if (playing) context.startActivity(Intent(context, PlayerActivity::class.java)) else showExitConfirmDialog()
            }
            setOnKeyListener { v, _, e -> handleCardKey(v, e) }
        }
        stateTitle.apply { textSize = 24f; setTextColor(Color.parseColor("#FFD700")); gravity = Gravity.CENTER }
        stateDesc.apply {
            textSize = 14f
            setTextColor(Color.rgb(214, 219, 226))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        stateCard.addView(stateTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        stateCard.addView(stateDesc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(tutorialText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // 移动端开关/切换按钮已按需求移除：屏幕两侧的翻页按钮（‹ ›）默认常驻，无需入口按钮。
        root.addView(stateCard, LinearLayout.LayoutParams(dp(448), dp(252)).apply { topMargin = dp(13) })

        if (launcherMode) {
            buildFunctionBar()
        } else {
            buildBottomGuide()
        }

        val full = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            if (launcherMode) {
                addView(functionBar, FrameLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.8f).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(28)
                })
            } else {
                addView(bottomGuide, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(28)
                })
                addView(bottomSheetOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }

        contentContainer.addView(full, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        renderCastState()
    }

    override fun onEnter() {
        startTutorial()
        if (!launcherMode) {
            startGuideAnimation()
            queueStore.addListener(onQueueChanged)
        }
        PlaybackController.registerObserver(this)
        // 进入首页时补刷一次，覆盖投屏状态在页面进入前已变化的场景。
        renderCastState()
    }

    override fun onLeave() {
        handler.removeCallbacks(tutorialRunnable)
        if (!launcherMode) {
            stopGuideAnimation()
            queueStore.removeListener(onQueueChanged)
            closeBottomSheet()
        }
        PlaybackController.unregisterObserver(this)
    }

    override fun onDetachedFromWindow() {
        PlaybackController.unregisterObserver(this)
        super.onDetachedFromWindow()
    }

    override fun onTransportStateChanged(state: PlaybackController.TransportState) {
        renderCastState()
    }

    override fun onNewCastRequest(uri: String, title: String, sourceHint: String) {
        renderCastState()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (bottomSheetOpen) {
            if (handleBottomSheetKey(event)) return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK && bottomSheetOpen) {
            closeBottomSheet()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private data class ShortcutItem(val pageId: String, val title: String, val iconRes: Int)

    private fun buildFunctionCards(): List<ShortcutItem> {
        val cards = mutableListOf<ShortcutItem>()
        cards += ShortcutItem("watch_later", "稍后播放", R.drawable.ic_more_watch_later)
        cards += ShortcutItem("favorites", "我的收藏", R.drawable.ic_more_favorites)
        if (settings.webParseEnabled) {
            cards += ShortcutItem(Settings.PAGE_ID_WEB_PARSE, "网页解析", R.drawable.ic_more_web_parse)
            cards += ShortcutItem("cartoon_city", "动画城", R.drawable.ic_more_cartoon)
        }
        if (settings.douyinCastEnabled) {
            cards += ShortcutItem(Settings.PAGE_ID_DOUYIN_CAST, "抖音投屏", R.drawable.ic_more_douyin_cast)
        }
        CustomDockTabsStore(context).list().forEach { tab ->
            cards += ShortcutItem("customtab_${tab.id}", tab.name, R.drawable.ic_more_custom_tab)
        }
        cards += ShortcutItem("phonehub", "连接手机", R.drawable.ic_more_phonehub)
        cards += ShortcutItem("history", "历史记录", R.drawable.ic_more_history)
        cards += ShortcutItem("diagnostics", "网络诊断", R.drawable.ic_more_diagnostics)
        cards += ShortcutItem("help", "帮助", R.drawable.ic_more_help)
        cards += ShortcutItem("settings", "设置", R.drawable.ic_more_settings)
        return cards
    }

    private fun buildFunctionBar() {
        val items = buildFunctionCards()
        if (items.isEmpty()) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        items.forEachIndexed { index, card ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                setPadding(dp(12), dp(8), dp(12), dp(8))
                if (index == 0) id = View.generateViewId()
                setOnFocusChangeListener { v, has ->
                    background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (has) Color.argb(40, 255, 255, 255) else Color.TRANSPARENT)
                        if (has) setStroke(dp(2), WARM) else setStroke(0, Color.TRANSPARENT)
                    }
                    FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 12)
                }
                setOnClickListener {
                    (context as? NewMainActivity)?.openLauncherFunctionPage(card.pageId, this)
                }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> { stateCard.requestFocus(); true }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (index == 0) { BoundaryFocusHandler.shake(this); true } else false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (index == items.size - 1) { BoundaryFocusHandler.shake(this); true } else false
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            (context as? NewMainActivity)?.openLauncherFunctionPage(card.pageId, this); true
                        }
                        else -> false
                    }
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(card.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            item.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            val label = TextView(context).apply {
                text = card.title
                textSize = 12f
                setTextColor(Color.argb(200, 255, 255, 255))
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            item.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                gravity = Gravity.CENTER_HORIZONTAL
            })
            container.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(4)
            })
        }

        functionBar.apply {
            isFocusable = false
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        stateCard.nextFocusDownId = container.getChildAt(0).id
    }

    private fun buildBottomGuide() {
        guideArrow.apply {
            text = "⬆"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(WARM)
            includeFontPadding = false
        }
        val label = TextView(context).apply {
            text = "上滑查看更多"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(238, 232, 218))
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, 0, 0)
        }
        bottomGuide.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = guideBg(false)
            addView(guideArrow, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnFocusChangeListener { v, has ->
                v.background = guideBg(has)
                FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 60)
            }
            setOnClickListener { openBottomSheet() }
            setOnKeyListener { v, _, e -> handleGuideKey(v, e) }
        }
    }

    private fun guideBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(60).toFloat()
        setColor(Color.argb(if (focused) 90 else 46, 32, 34, 40))
        setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(150, 210, 214, 222))
    }

    private fun startGuideAnimation() {
        if (guideAnimating) return
        guideAnimating = true
        animateGuideArrowLoop()
    }

    private fun animateGuideArrowLoop() {
        if (!guideAnimating || !isAttachedToWindow) return
        guideArrow.translationY = dp(8).toFloat()
        guideArrow.alpha = 0.35f
        guideArrow.animate()
            .translationY((-dp(6)).toFloat())
            .alpha(1f)
            .setDuration(720L)
            .withEndAction {
                guideArrow.animate()
                    .translationY((-dp(14)).toFloat())
                    .alpha(0.2f)
                    .setDuration(520L)
                    .withEndAction { handler.postDelayed({ animateGuideArrowLoop() }, 180L) }
                    .start()
            }
            .start()
    }

    private fun stopGuideAnimation() {
        guideAnimating = false
        guideArrow.animate().cancel()
        guideArrow.translationY = 0f
        guideArrow.alpha = 1f
    }

    private fun handleGuideKey(v: View, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                openBottomSheet()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                stateCard.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                openBottomSheet()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_RIGHT, this)
            else -> false
        }
    }

    private fun openBottomSheet() {
        if (bottomSheetOpen) return
        bottomSheetTriggerFocus = findFocus()?.takeIf { it !== bottomSheet && it !== bottomSheetOverlay && !isDescendantOfBottomSheet(it) }
        homeBodyRoot?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        bottomGuide.isFocusable = false
        bottomGuide.isClickable = false
        bottomSheetOpen = true
        renderBottomSheetInitialContent()
        bottomSheetOverlay.visibility = View.VISIBLE
        bottomSheetOverlay.requestFocus()
        bottomSheetOverlay.post {
            val h = bottomSheet.height.takeIf { it > 0 } ?: dp(430)
            bottomSheet.translationY = (h + dp(30)).toFloat()
            bottomSheet.animate().translationY(0f).setDuration(240L).start()
            bottomSheetCloseButton.post { bottomSheetCloseButton.requestFocus() }
        }
        loadBottomSheetAsyncContent()
    }

    private fun closeBottomSheet() {
        if (!bottomSheetOpen) return
        bottomSheetOpen = false
        bottomSheetOverlay.post {
            val h = bottomSheet.height.takeIf { it > 0 } ?: dp(430)
            bottomSheet.animate()
                .translationY((h + dp(30)).toFloat())
                .setDuration(200L)
                .withEndAction {
                    bottomSheetOverlay.visibility = View.GONE
                    bottomSheet.translationY = 0f
                    homeBodyRoot?.descendantFocusability = homeBodyRootDescendantFocusability
                    bottomGuide.isFocusable = true
                    bottomGuide.isClickable = true
                }
                .start()
        }
        restoreBottomSheetTriggerFocus()
    }

    private fun restoreBottomSheetTriggerFocus() {
        val trigger = bottomSheetTriggerFocus
        bottomSheetTriggerFocus = null
        if (trigger?.isAttachedToWindow == true && trigger.visibility == View.VISIBLE && trigger.requestFocus()) return
        stateCard.requestFocus()
    }

    private fun isDescendantOfBottomSheet(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === bottomSheet || current === bottomSheetOverlay) return true
            current = current.parent as? View
        }
        return false
    }

    private fun handleBottomSheetKey(event: KeyEvent): Boolean {
        if (!bottomSheetOpen) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) closeBottomSheet()
            return true
        }
        return false
    }

    private fun handleBottomSheetCloseKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                closeBottomSheet()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!focusBottomSheetHeaderFirstAction(bottomSheetLastColumn)) {
                    if (!focusBottomSheetColumnFirst(bottomSheetLastColumn)) {
                        bottomSheetCloseButton.requestFocus()
                    }
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                closeBottomSheet()
                true
            }
            else -> false
        }
    }

    private fun handleBottomSheetClearKey(columnIndex: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        bottomSheetLastColumn = columnIndex
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> false
            KeyEvent.KEYCODE_DPAD_UP -> {
                bottomSheetCloseButton.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!focusBottomSheetColumnFirst(columnIndex)) {
                    bottomSheetCloseButton.requestFocus()
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                closeBottomSheet()
                true
            }
            else -> false
        }
    }

    private fun clearBottomSheetQueue() {
        queueStore.clear()
        renderBottomSheetQueueColumn()
        Toast.makeText(context, "已清空稍后播放", Toast.LENGTH_SHORT).show()
    }

    private fun playBottomSheetQueue() {
        val next = queueStore.all().firstOrNull { it.status != PlayQueueStore.Status.FINISHED }
        if (next == null) {
            Toast.makeText(context, "稍后播放列表已全部播完", Toast.LENGTH_SHORT).show()
            return
        }
        (context as? NewMainActivity)?.startQueuePlayback(next, toastText = "继续播放：${next.title}")
    }

    private fun clearBottomSheetRecommendations() {
        bottomSheetRecommendationsCleared = true
        bottomSheetRecommendations = emptyList()
        renderRecommendationColumn(bottomSheetRecommendations)
        Toast.makeText(context, "已清空推荐内容缓存", Toast.LENGTH_SHORT).show()
    }

    private fun bottomSheetColumn(title: String, subtitle: String?, content: LinearLayout, columnIndex: Int, onClear: (() -> Unit)? = null): View {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(18, 255, 255, 255))
                setStroke(dp(1), Color.argb(60, 255, 255, 255))
            }
            // 三列自身作为滚动区父容器，需要裁剪超出列可视范围的滚动内容；
            // 列内具体按钮仍保持自身 clip=false，避免焦点态被按钮内部裁剪。
            clipChildren = true
            clipToPadding = true
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (!subtitle.isNullOrBlank()) {
            header.addView(TextView(context).apply {
                text = " $subtitle"
                textSize = 12f
                setTextColor(Color.argb(200, 210, 214, 222))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        var firstAction: View? = null
        if (onClear != null) {
            if (columnIndex == 0) {
                val playButton = crayonTextButton("播放") { playBottomSheetQueue() }.apply {
                    textSize = 12f
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnKeyListener { _, _, event -> handleBottomSheetClearKey(columnIndex, event) }
                }
                firstAction = firstAction ?: playButton
                header.addView(playButton, LinearLayout.LayoutParams(dp(60), dp(34)).apply { marginStart = dp(8) })
            }

            val clearButton = crayonTextButton("清空") { onClear() }.apply {
                textSize = 12f
                setPadding(dp(12), 0, dp(12), 0)
                setOnKeyListener { _, _, event -> handleBottomSheetClearKey(columnIndex, event) }
            }
            firstAction = firstAction ?: clearButton
            header.addView(clearButton, LinearLayout.LayoutParams(dp(60), dp(34)).apply { marginStart = dp(8) })
        }
        bottomSheetHeaderFirstActions[columnIndex] = firstAction
        wrapper.addView(header)
        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            // 列包装容器仍负责裁剪弹窗边界；ScrollView 内层放开裁剪，避免条目焦点放大被截断。
            clipChildren = false
            clipToPadding = false
            isFillViewport = false
            isScrollContainer = true
            // 避免焦点优先落在滚动容器本身，导致绕过 header 操作按钮。
            isFocusable = false
            isFocusableInTouchMode = false
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        bottomSheetLastColumn = columnIndex
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        bottomSheet.requestDisallowInterceptTouchEvent(true)
                        bottomSheetOverlay.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                        bottomSheet.requestDisallowInterceptTouchEvent(false)
                        bottomSheetOverlay.requestDisallowInterceptTouchEvent(false)
                    }
                }
                false
            }
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrapper.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        return wrapper
    }

    private fun renderBottomSheetInitialContent() {
        bottomSheetFirstItems.fill(null)
        // header 区按钮在 bottomSheet 初始化构建时已经创建并写入 bottomSheetHeaderFirstActions。
        // 弹窗每次展开只会重刷列表内容，不能清空这些稳定引用，否则「收起」↓ 与列表首项 ↑
        // 都会找不到稍后播放/推荐内容 header 上的播放/清空按钮，导致遥控器无法选中它们。
        bottomSheetRecommendations = emptyList()
        bottomSheetRecommendationsCleared = false
        renderBottomSheetQueueColumn()
        setColumnLoading(bottomSheetRecommendColumn, 1, "正在读取推荐缓存…")
        setColumnLoading(bottomSheetPopularColumn, 2, "正在读取热门合集…")
    }

    private fun renderBottomSheetQueueColumn() {
        bottomSheetQueueColumn.removeAllViews()
        val items = queueStore.all()
        if (items.isEmpty()) {
            addBottomSheetEmpty(bottomSheetQueueColumn, 0, "暂无稍后播放内容\n可以从推荐内容或收藏页加入")
            return
        }
        items.take(20).forEachIndexed { index, item ->
            val row = bottomSheetItem(
                columnIndex = 0,
                indexInColumn = index,
                title = String.format("%02d  %s", index + 1, item.title.ifBlank { item.uri }),
                subtitle = "${statusLabel(item.status)} · ${item.source.ifBlank { "queue" }}",
                actionText = null
            ) {
                (context as? NewMainActivity)?.startQueuePlayback(item, toastText = "已切换：${item.title}")
                closeBottomSheet()
            }
            bottomSheetQueueColumn.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun loadBottomSheetAsyncContent() {
        Thread({
            val recommendations = try {
                recommendationsStore.fetchIncomingForToday().orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }
            val popular = try {
                syncManager.fetchCloudIndexConfig()?.collections.orEmpty()
                    .sortedWith(compareByDescending<GiteeSyncManager.CloudCollection> { it.downloadCount }.thenBy { it.name })
            } catch (_: Throwable) {
                emptyList()
            }
            handler.post {
                if (!bottomSheetOpen) return@post
                if (!bottomSheetRecommendationsCleared) {
                    bottomSheetRecommendations = recommendations
                }
                renderRecommendationColumn(bottomSheetRecommendations)
                renderPopularColumn(popular)
            }
        }, "home-bottom-sheet-load").start()
    }

    private fun renderRecommendationColumn(recommendations: List<RecommendationsStore.Recommendation>) {
        bottomSheetRecommendColumn.removeAllViews()
        val flat = recommendations.flatMap { rec ->
            rec.videos.map { video -> rec.collectionName to video }
        }.filter { it.second.url.isNotBlank() }
        if (flat.isEmpty()) {
            addBottomSheetEmpty(bottomSheetRecommendColumn, 1, "暂无推荐缓存\n收到云端推荐后会展示在这里")
            return
        }
        flat.take(20).forEachIndexed { index, (collectionName, video) ->
            val row = bottomSheetItem(
                columnIndex = 1,
                indexInColumn = index,
                title = video.title.ifBlank { video.url },
                subtitle = collectionName.ifBlank { "云端推荐" },
                actionText = "+ 加入稍后播放"
            ) {
                queueStore.add(video.title, video.url, "cloud_recommend")
                Toast.makeText(context, "已加入稍后播放", Toast.LENGTH_SHORT).show()
                renderBottomSheetQueueColumn()
            }
            bottomSheetRecommendColumn.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun renderPopularColumn(collections: List<GiteeSyncManager.CloudCollection>) {
        bottomSheetPopularColumn.removeAllViews()
        bottomSheetPopularCollections = collections
        if (collections.isEmpty()) {
            addBottomSheetEmpty(bottomSheetPopularColumn, 2, "暂无热门内容\n云端合集下载后会累积热度")
            return
        }
        collections.take(20).forEachIndexed { index, collection ->
            val row = bottomSheetItem(
                columnIndex = 2,
                indexInColumn = index,
                title = collection.name.ifBlank { collection.id },
                subtitle = "下载量 ${collection.downloadCount} · ${collection.itemCount} 个内容",
                actionText = "下载/查看"
            ) {
                downloadPopularCollection(collection)
            }
            bottomSheetPopularColumn.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
    }

    private fun downloadPopularCollection(collection: GiteeSyncManager.CloudCollection) {
        if (collection.passwordHash.isNotBlank()) {
            Toast.makeText(context, "该合集已加锁，请到云同步弹窗解锁下载", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, "正在下载「${collection.name}」…", Toast.LENGTH_SHORT).show()
        Thread({
            val count = try {
                syncManager.downloadCollections(listOf(collection.id), bottomSheetPopularCollections)
            } catch (_: Throwable) {
                0
            }
            if (count > 0) syncManager.incrementDownloadCount(listOf(collection.id))
            handler.post {
                Toast.makeText(context, if (count > 0) "✅ 已下载「${collection.name}」" else "❌ 下载失败", Toast.LENGTH_SHORT).show()
                if (bottomSheetOpen && count > 0) loadBottomSheetAsyncContent()
            }
        }, "home-popular-download").start()
    }

    private fun setColumnLoading(column: LinearLayout, columnIndex: Int, text: String) {
        column.removeAllViews()
        addBottomSheetEmpty(column, columnIndex, text)
    }

    private fun addBottomSheetEmpty(column: LinearLayout, columnIndex: Int, text: String) {
        val empty = crayonDialogButton(TextView(context).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(215, 210, 214, 222))
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(dp(12), dp(18), dp(12), dp(18))
            setOnKeyListener { _, _, event -> handleBottomSheetItemKey(columnIndex, 0, event) }
        })
        bottomSheetFirstItems[columnIndex] = empty
        column.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun bottomSheetItem(
        columnIndex: Int,
        indexInColumn: Int,
        title: String,
        subtitle: String,
        actionText: String?,
        onClick: () -> Unit
    ): View {
        val root = crayonDialogButton(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            clipChildren = false
            clipToPadding = false
        })
        root.setOnKeyListener { _, _, event -> handleBottomSheetItemKey(columnIndex, indexInColumn, event) }
        root.setOnClickListener { onClick() }
        val titleView = TextView(context).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(238, 245, 245, 245))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subView = TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.argb(200, 210, 214, 222))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(titleView)
        root.addView(subView)
        if (!actionText.isNullOrBlank()) {
            root.addView(TextView(context).apply {
                text = actionText
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(WARM)
                gravity = Gravity.END
                setPadding(0, dp(6), 0, 0)
            })
        }
        root.setOnFocusChangeListener { v, hasFocus ->
            bottomSheetLastColumn = columnIndex
            v.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (hasFocus) Color.TRANSPARENT else Color.argb(18, 255, 255, 255))
                setStroke(dp(if (hasFocus) 3 else 1), if (hasFocus) Color.parseColor("#FFD700") else Color.argb(170, 210, 214, 222))
            }
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 14)
        }
        if (indexInColumn == 0) bottomSheetFirstItems[columnIndex] = root
        return root
    }

    private fun handleBottomSheetItemKey(columnIndex: Int, indexInColumn: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        bottomSheetLastColumn = columnIndex
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                closeBottomSheet()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (indexInColumn == 0) {
                    // 列表首项按 ↑：优先回到 header 操作按钮；
                    // 若 header 按钮不可见/被移除，则兜底回到列表首项；若列表首项也不存在，再回到「收起」。
                    if (focusBottomSheetHeaderFirstAction(columnIndex)) return true

                    val first = bottomSheetFirstItems.getOrNull(columnIndex)
                    if (first?.isAttachedToWindow == true && first.visibility == View.VISIBLE && first.requestFocus()) return true

                    bottomSheetCloseButton.requestFocus()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun focusBottomSheetHeaderFirstAction(columnIndex: Int): Boolean {
        val target = bottomSheetHeaderFirstActions.getOrNull(columnIndex)
        if (!isBottomSheetFocusableTarget(target)) return false
        return target?.requestFocus() == true
    }

    private fun focusBottomSheetColumnFirst(columnIndex: Int): Boolean {
        val target = bottomSheetFirstItems.getOrNull(columnIndex) ?: bottomSheetFirstItems.firstOrNull { it != null }
        if (!isBottomSheetFocusableTarget(target)) return false
        return target?.requestFocus() == true
    }

    private fun isBottomSheetFocusableTarget(target: View?): Boolean {
        return target?.isAttachedToWindow == true &&
            target.visibility == View.VISIBLE &&
            target.isShown &&
            target.isEnabled &&
            target.isFocusable
    }

    private fun startTutorial() {
        val list = buildTutorialMessages()
        tutorialIndex = 0
        tutorialText.text = list.firstOrNull() ?: "通用方式：打开视频 → 点击 TV / 投屏 → 选择当前电视设备"
        handler.removeCallbacks(tutorialRunnable)
        if (list.size > 1) handler.postDelayed(tutorialRunnable, 4_000L)
    }

    private fun buildTutorialMessages(): List<String> {
        val name = settings.deviceName.ifBlank { Settings.DEFAULT_DEVICE_NAME }
        return listOf(
            context.getString(R.string.tutorial_iqiyi, name),
            context.getString(R.string.tutorial_bilibili, name),
            context.getString(R.string.tutorial_tencent, name),
            context.getString(R.string.tutorial_youku, name),
            context.getString(R.string.tutorial_static)
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    private fun renderCastState() {
        playing = com.bd.casttv.dlna.PlaybackController.currentUri.isNotBlank()
        stateTitle.text = if (playing) "正在播放" else "等待投屏"
        stateDesc.text = if (playing) "按 OK 继续进入播放器" else "DLNA / AirPlay 接收已就绪，按 OK 可打开退出确认"
        if (playing) {
            loadStateThumbBackground(PlaybackController.currentThumbPath())
        } else {
            stateThumbDrawable = null
            applyStateCardBackground(stateCard.isFocused)
        }
    }

    /** 缩略图充满状态区域时缓存的填充 Drawable（CENTER_CROP 语义）。null 表示无缩略图。 */
    private var stateThumbDrawable: Drawable? = null

    /**
     * 异步加载缩略图并转为可铺满 stateCard 的 CENTER_CROP 背景。
     * 不引入 Glide，复用项目内的轻量位图解码，与 [Thumbnails] 保持一致的离线兼容策略。
     */
    private fun loadStateThumbBackground(path: String?) {
        if (path.isNullOrBlank()) {
            stateThumbDrawable = null
            applyStateCardBackground(stateCard.isFocused)
            return
        }
        val file = java.io.File(path)
        if (!file.exists() || file.length() <= 0L) {
            stateThumbDrawable = null
            applyStateCardBackground(stateCard.isFocused)
            return
        }
        // 后台线程解码，主线程回设背景，避免阻塞主线程。
        Thread {
            val bmp: Bitmap? = try {
                val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                BitmapFactory.decodeFile(file.absolutePath, opt)
            } catch (t: Throwable) {
                null
            }
            handler.post {
                // 解码期间可能已切走播放状态或缩略图已变化，丢弃过期结果。
                if (!playing || com.bd.casttv.dlna.PlaybackController.currentThumbPath() != path) return@post
                if (bmp != null && !bmp.isRecycled) {
                    stateThumbDrawable = RoundedCenterCropDrawable(bmp, dp(18).toFloat())
                } else {
                    stateThumbDrawable = null
                }
                applyStateCardBackground(stateCard.isFocused)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 应用 stateCard 背景：
     * - 播放中且有缩略图：缩略图作为填充层铺满 448×252dp，叠加圆角边框（焦点态高亮）。
     * - 其余情况：恢复原来的 [cardBg]。
     */
    private fun applyStateCardBackground(focused: Boolean) {
        val thumb = stateThumbDrawable
        if (playing && thumb != null) {
            // 缩略图铺满填充层 + 仅描边的边框层（透明填充，避免遮挡缩略图）。
            val border = cardBorderOverlay(focused)
            val layer = LayerDrawable(arrayOf(thumb, border))
            stateCard.background = layer
        } else {
            stateCard.background = cardBg(focused)
        }
    }

    /** 仅圆角描边、填充透明的边框层，用于叠加在缩略图之上保留卡片边框与焦点高亮。 */
    private fun cardBorderOverlay(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(dp(if (focused) 3 else 1), if (focused) Color.parseColor("#FFD700") else Color.argb(170, 210, 214, 222))
    }

    /**
     * 将 Bitmap 以 CENTER_CROP 语义铺满自身 bounds，并按 [cornerRadius] 圆角裁剪的 Drawable。
     * 用于让缩略图充满整个 stateCard 状态区域，同时保持与卡片一致的圆角。
     */
    private class RoundedCenterCropDrawable(
        private val bitmap: Bitmap,
        private val cornerRadius: Float
    ) : Drawable() {
        private val shader = android.graphics.BitmapShader(
            bitmap,
            android.graphics.Shader.TileMode.CLAMP,
            android.graphics.Shader.TileMode.CLAMP
        )
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            shader = this@RoundedCenterCropDrawable.shader
        }
        private val rectF = android.graphics.RectF()
        private val matrix = android.graphics.Matrix()

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            super.onBoundsChange(bounds)
            rectF.set(bounds)
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()
            if (bw <= 0f || bh <= 0f) return
            val vw = bounds.width().toFloat()
            val vh = bounds.height().toFloat()
            // CENTER_CROP：取较大缩放比铺满，居中裁剪多余部分。
            val scale = maxOf(vw / bw, vh / bh)
            val dx = (vw - bw * scale) / 2f + bounds.left
            val dy = (vh - bh * scale) / 2f + bounds.top
            matrix.setScale(scale, scale)
            matrix.postTranslate(dx, dy)
            shader.setLocalMatrix(matrix)
        }

        override fun draw(canvas: android.graphics.Canvas) {
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun handleCardKey(v: View, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_RIGHT, this)
            KeyEvent.KEYCODE_DPAD_UP -> BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_UP, this)
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (launcherMode) {
                    val firstItem = (functionBar.getChildAt(0) as? LinearLayout)?.getChildAt(0)
                    firstItem?.requestFocus()
                } else {
                    openBottomSheet()
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                showExitConfirmDialog()
                true
            }
            else -> false
        }
    }

    private fun showExitConfirmDialog() {
        val act = context as? android.app.Activity ?: return
        ExitConfirmDialog.show(act)
    }

    private fun cardBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(0xFF4169E1.toInt())
        setStroke(dp(if (focused) 3 else 1), if (focused) Color.parseColor("#FFD700") else Color.argb(170, 210, 214, 222))
    }

    private fun crayonDialogContent(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        clipChildren = false
        clipToPadding = false
    }

    private fun crayonDialogTitle(title: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        addView(TextView(context).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFD700"))
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun crayonTextButton(text: String, onClick: () -> Unit): TextView {
        return crayonDialogButton(TextView(context).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(238, 238, 238))
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { onClick() }
        }, cornerRadiusDp = 60)
    }

    private fun <T : View> crayonDialogButton(view: T, cornerRadiusDp: Int = 14): T = view.apply {
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        fun refresh(focused: Boolean) {
            background = GradientDrawable().apply {
                cornerRadius = dp(cornerRadiusDp).toFloat()
                setColor(if (focused) Color.TRANSPARENT else Color.argb(18, 255, 255, 255))
                setStroke(dp(if (focused) 3 else 1), if (focused) Color.parseColor("#FFD700") else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, hasFocus ->
            refresh(hasFocus)
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = cornerRadiusDp)
        }
    }

    private fun statusLabel(status: PlayQueueStore.Status): String = when (status) {
        PlayQueueStore.Status.PENDING -> "待播放"
        PlayQueueStore.Status.PLAYING -> "播放中"
        PlayQueueStore.Status.FINISHED -> "已播完"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val WARM = Color.rgb(245, 196, 81)
    }
}
