package com.bd.casttv.ui.framework.pages

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.queue.PlayQueueStore
import com.bd.casttv.sync.GiteeSyncManager
import com.bd.casttv.sync.RecommendationsStore
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.NewMainActivity
import com.bd.casttv.util.ThemeManager

/** 启动台模式下承接原 HomePage bottom sheet 的「稍后播放 / 推荐内容 / 热门内容」完整页面。 */
class WatchLaterPage(context: Context) : BasePage(context) {
    override val pageId: String = "watch_later"
    override val pageTitle: String = "稍后播放/推荐"
    override val pageIconRes: Int = R.drawable.ic_history_tv
    override val enablePageScroll: Boolean = false
    override val showPageHeader: Boolean get() = true
    override val useContentPanel: Boolean get() = true
    override val pageStickerRes: Int get() = R.drawable.sticker_shinchan

    private val handler = Handler(Looper.getMainLooper())
    private val queueStore: PlayQueueStore by lazy { PlayQueueStore.get(context.applicationContext) }
    private val favoritesStore: FavoritesStore by lazy { FavoritesStore(context.applicationContext) }
    private val syncManager: GiteeSyncManager by lazy { GiteeSyncManager(favoritesStore) }
    private val recommendationsStore: RecommendationsStore by lazy { RecommendationsStore(context.applicationContext) }

    private val tabQueue = tabButton("稍后播放")
    private val tabRecommend = tabButton("推荐内容")
    private val tabPopular = tabButton("热门内容")
    private val tabs = listOf(tabQueue, tabRecommend, tabPopular)
    private val adapter = RowAdapter()
    private var listView: RecyclerView? = null
    private var selectedTab = 0
    private var pendingRestoreTab: Int = -1
    private var pendingRestorePosition: Int = RecyclerView.NO_POSITION
    /** 播放器返回兜底：用 QueueItem 稳定 id 反查 position，避免播放期间列表变动导致纯 position 错位。 */
    private var pendingRestoreQueueItemId: String? = null
    private var recommendations: List<RecommendationsStore.Recommendation> = emptyList()
    private var popularCollections: List<GiteeSyncManager.CloudCollection> = emptyList()

    private val onQueueChanged = { if (selectedTab == 0) renderCurrentTab() }

    // ---- 右侧功能按钮栏（稍后播放 Tab 专用） ----
    private var btnPlay: TextView? = null
    private var btnClear: TextView? = null
    private var capsuleSortBy: LinearLayout? = null        // 名称 / 状态 胶囊组
    private var capsuleSortByLabel1: TextView? = null     // 「按名称」
    private var capsuleSortByLabel2: TextView? = null     // 「按状态」
    private var capsuleOrder: LinearLayout? = null        // 正序 / 倒叙 胶囊组
    private var capsuleOrderLabel1: TextView? = null      // 「正序」
    private var capsuleOrderLabel2: TextView? = null      // 「倒叙」
    private var buttonBar: LinearLayout? = null           // 按钮栏容器（右对齐），Tab!=0 时 GONE
    private var rightPanel: LinearLayout? = null          // 右侧容器：VERTICAL = 按钮栏 + 列表

    init {
        // 确保 SortConfig 已初始化（若 queueStore 单例首次在这里被 lazy 创建）。
        queueStore.let { it }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(26), dp(16), dp(26), dp(26))
            clipChildren = false
            clipToPadding = false
        }
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(16), dp(4))
        }
        tabs.forEachIndexed { index, tab ->
            tab.setOnFocusChangeListener { v, has ->
                if (has) switchTab(index)
                refreshTab(v as TextView, has, v.isSelected)
            }
            tab.setOnClickListener { switchTab(index) }
            tab.setOnKeyListener { v, keyCode, event -> handleTabKey(v, keyCode, event, index) }
            left.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(12) })
        }
        root.addView(left, LinearLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.MATCH_PARENT))

        // 右侧区域：VERTICAL = [功能按钮栏] + [RecyclerView]
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        rightPanel = right

        val bar = buildButtonBar()
        buttonBar = bar
        right.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })

        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@WatchLaterPage.adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(dp(10), dp(2), dp(10), dp(20))
        }
        listView = list
        // VERTICAL LinearLayout：宽度=MATCH_PARENT，高度=0 + weight=1（填满剩余空间）。
        // 三参构造函数 LinearLayout.LayoutParams(width, height, weight)，VERTICAL 方向 weight 作用在 height，
        // 所以 width 必须是 MATCH_PARENT，之前写 (0, MATCH_PARENT, 1f) 会把 width=0 导致列表完全不可见。
        right.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        contentContainer.addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 根据当前排序偏好刷新胶囊组显示（默认按名称 + 正序）
        refreshSortCapsules()

        switchTab(0)
        loadAsyncContent()
    }

    // ---------------------------------------------------------------
    // 功能按钮栏：播放 / 清除 / 名称状态胶囊 / 正序倒叙胶囊 (右对齐)
    // ---------------------------------------------------------------
    private fun buildButtonBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }

        // [播放]
        val play = roundedButton("播放").apply {
            setOnClickListener { onPlayFirstClicked() }
            setOnFocusChangeListener { v, h -> applyFocusFx(v, h, 18); refreshActionButtonsEnabled() }
            setOnKeyListener listener@{ _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@listener false
                when (keyCode) {
                    // 【播放】左键：退出按钮栏，回到左侧当前选中 Tab
                    KeyEvent.KEYCODE_DPAD_LEFT -> focusSelectedTab()
                    // 任意按钮 DOWN → 进入列表第 1 项
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusFirstRowOrShake(this@apply)
                    KeyEvent.KEYCODE_DPAD_UP -> { BoundaryFocusHandler.shake(this@apply); true }
                    else -> false
                }
            }
        }
        btnPlay = play
        bar.addView(play, LinearLayout.LayoutParams(dp(92), dp(40)).apply { marginEnd = dp(10) })

        // [清除]
        val clear = roundedButton("清除").apply {
            setOnClickListener { onClearAllClicked() }
            setOnFocusChangeListener { v, h -> applyFocusFx(v, h, 18) }
            setOnKeyListener listener@{ _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@listener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusFirstRowOrShake(this@apply)
                    KeyEvent.KEYCODE_DPAD_UP -> { BoundaryFocusHandler.shake(this@apply); true }
                    else -> false
                }
            }
        }
        btnClear = clear
        bar.addView(clear, LinearLayout.LayoutParams(dp(92), dp(40)).apply { marginEnd = dp(14) })

        // 组A：按名称 / 按状态（胶囊）——点击(OK)切换，左/右键移动焦点不触发切换
        val gBy = buildCapsuleGroup(
            labels = listOf("按名称", "按状态"),
            onToggle = { switchSortBy(1) }
        )
        capsuleSortBy = gBy.first
        capsuleSortByLabel1 = gBy.second[0]
        capsuleSortByLabel2 = gBy.second[1]
        bar.addView(capsuleSortBy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(10) })

        // 组B：正序 / 倒叙（胶囊）——点击(OK)切换，左/右键移动焦点不触发切换
        val gOrder = buildCapsuleGroup(
            labels = listOf("正序", "倒叙"),
            onToggle = { switchOrder(1) }
        )
        capsuleOrder = gOrder.first
        capsuleOrderLabel1 = gOrder.second[0]
        capsuleOrderLabel2 = gOrder.second[1]
        bar.addView(capsuleOrder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))

        // 把按钮栏作为 Tab 方向右键的 FOCUS_RIGHT 目的地（通过 NextFocusForwardId 有点冗余，
        // 用 KeyListener 更直接，handleTabKey 在这里会处理 Tab[0] 右键进入按钮栏）。
        return bar
    }

    /**
     * 单个圆角按钮：16dp 暖灰描边 + 点击/聚焦时 FocusFx。
     */
    private fun roundedButton(text: String) = TextView(context).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Color.argb(240, 245, 245, 245))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(30, 255, 255, 255))
            setStroke(dp(1), Color.argb(150, 210, 214, 222))
        }
    }

    /**
     * 胶囊组（横向两标签并列，共享一个外层焦点）。
     * @param onToggle 点击(OK/ENTER)时触发切换；左/右键仅移动焦点不触发切换。
     * @return (容器, 两个 TextView 文本标签)
     */
    private fun buildCapsuleGroup(
        labels: List<String>,
        onToggle: () -> Unit
    ): Pair<LinearLayout, List<TextView>> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(25, 255, 255, 255))
                setStroke(dp(1), Color.argb(140, 210, 214, 222))
            }
            setPadding(dp(4), 0, dp(4), 0)
            setOnFocusChangeListener { v, h -> applyFocusFx(v, h, 18) }
            setOnKeyListener listener@{ _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@listener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        onToggle()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        BoundaryFocusHandler.shake(this@apply)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        return@listener focusFirstRowOrShake(this@apply)
                    }
                    // 左/右键不拦截，让系统正常移动焦点到相邻控件，不触发切换
                    else -> false
                }
            }
        }
        val textViews = labels.mapIndexed { idx, label ->
            TextView(context).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = false           // 焦点只停留在外层 container，不单独移动
                setPadding(dp(14), 0, dp(14), 0)
                setTextColor(Color.argb(220, 245, 245, 245))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Color.TRANSPARENT)
                }
                setOnClickListener { onToggle() }
                setLayoutParams(LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
            }.also { container.addView(it) }
        }
        return container to textViews
    }

    private fun applyFocusFx(v: View, focused: Boolean, cornerRadiusDp: Int) {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(cornerRadiusDp).toFloat()
            setColor(when {
                focused -> Color.argb(42, 255, 255, 255)
                else -> Color.argb(25, 255, 255, 255)
            })
            setStroke(dp(if (focused) 3 else 1), if (focused) WARM else Color.argb(140, 210, 214, 222))
        }
        // 胶囊组容器：自身作为焦点载体（LinearLayout），不覆盖文字样式。
        v.background = bg
        FocusFxHelper.applyFocusFxState(v, focused, cornerRadiusDp = cornerRadiusDp)
    }

    /**
     * 胶囊组点击切换：2选1循环 toggle，dir=1 切到另一个。
     */
    private fun switchSortBy(dir: Int): Boolean {
        val cur = when (PlayQueueStore.SortConfig.currentBy()) {
            PlayQueueStore.SortBy.NAME -> 0
            PlayQueueStore.SortBy.STATUS -> 1
        }
        val next = (cur + dir + 2) % 2
        if (next == cur) return false
        PlayQueueStore.SortConfig.set(
            if (next == 0) PlayQueueStore.SortBy.NAME else PlayQueueStore.SortBy.STATUS,
            PlayQueueStore.SortConfig.currentAsc()
        )
        refreshSortCapsules()
        renderCurrentTab()
        return true
    }

    private fun switchOrder(dir: Int): Boolean {
        val cur = if (PlayQueueStore.SortConfig.currentAsc()) 0 else 1
        val next = (cur + dir + 2) % 2
        if (next == cur) return false
        PlayQueueStore.SortConfig.set(PlayQueueStore.SortConfig.currentBy(), next == 0)
        refreshSortCapsules()
        renderCurrentTab()
        return true
    }

    /**
     * 把当前 SortConfig 映射到胶囊组两个子标签的颜色（选中=暖黄字体 + 实胶囊背景）。
     */
    private fun refreshSortCapsules() {
        val by = PlayQueueStore.SortConfig.currentBy()
        val asc = PlayQueueStore.SortConfig.currentAsc()
        capsuleSortByLabel1?.applyCapsuleLabel(by == PlayQueueStore.SortBy.NAME)
        capsuleSortByLabel2?.applyCapsuleLabel(by == PlayQueueStore.SortBy.STATUS)
        capsuleOrderLabel1?.applyCapsuleLabel(asc)
        capsuleOrderLabel2?.applyCapsuleLabel(!asc)
        refreshActionButtonsEnabled()
    }

    private fun TextView.applyCapsuleLabel(selected: Boolean) {
        setTextColor(if (selected) WARM else Color.argb(220, 245, 245, 245))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            if (selected) {
                setColor(Color.argb(45, 245, 196, 81))
                setStroke(dp(1), WARM)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(dp(0), Color.TRANSPARENT)
            }
        }
    }

    /**
     * 空列表时播放按钮 disabled（不可聚焦+灰字），其他按钮保持可操作。
     */
    private fun refreshActionButtonsEnabled() {
        val items = queueStore.all()
        val play = btnPlay ?: return
        play.isFocusable = items.isNotEmpty()
        play.isClickable = items.isNotEmpty()
        play.alpha = if (items.isNotEmpty()) 1f else 0.45f
        play.setTextColor(if (items.isNotEmpty()) Color.argb(240, 245, 245, 245)
                            else Color.argb(120, 210, 214, 222))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        queueStore.addListener(onQueueChanged)
    }

    override fun onDetachedFromWindow() {
        queueStore.removeListener(onQueueChanged)
        super.onDetachedFromWindow()
    }

    override fun focusToFirstContent(): Boolean {
        return tabQueue.requestFocus().also { if (it) onFocusEnterContent() }
    }

    fun refreshAfterPlayerReturn() {
        if (pendingRestoreTab != 0 || selectedTab != 0) return
        pendingRestoreTab = -1
        // 1) 快路径：position 仍合法（列表内容未发生增删/顺序未变）直接用。
        // 2) 兜底：用 QueueItem 稳定 id 在当前列表反查最新 position（播放期间有删/增依然能正确找回）。
        var position = pendingRestorePosition
        val queueItemId = pendingRestoreQueueItemId
        pendingRestorePosition = RecyclerView.NO_POSITION
        pendingRestoreQueueItemId = null
        if (position !in 0 until adapter.itemCount) {
            if (queueItemId != null) {
                position = adapter.indexOfQueueRowKey(queueItemId)
            }
            if (position !in 0 until adapter.itemCount) return
        }
        // 回到页面时把"上一次点击播放"的列表项维持为选中（互斥），
        // 让用户直观知道焦点恢复是从哪条开始、避免和普通焦点移动混淆。
        adapter.setSelectedQueueRowKey(adapter.queueRowKeyAt(position))
        restoreRowFocus(position)
    }

    private fun restoreRowFocus(position: Int) {
        val list = listView ?: return
        if (position !in 0 until adapter.itemCount) return
        val manager = list.layoutManager as? LinearLayoutManager
        fun requestVisibleRowFocus(): Boolean {
            val row = manager?.findViewByPosition(position) ?: list.findViewHolderForAdapterPosition(position)?.itemView
            val ok = row?.requestFocus() == true
            if (ok) onFocusEnterContent()
            return ok
        }
        list.post firstAttempt@{
            if (requestVisibleRowFocus()) return@firstAttempt
            list.scrollToPosition(position)
            list.post secondAttempt@{
                if (requestVisibleRowFocus()) return@secondAttempt
                list.postDelayed({ requestVisibleRowFocus() }, 100L)
            }
        }
    }

    private fun handleRowClick(position: Int, item: RowItem) {
        if (item.onClick == null) return
        // 稍后播放 Tab：互斥选中 + 播放器返回焦点恢复（position 快路径 + QueueItem.id 兜底）
        if (selectedTab == 0 && position != RecyclerView.NO_POSITION) {
            pendingRestoreTab = selectedTab
            pendingRestorePosition = position
            pendingRestoreQueueItemId = item.queueRowKey
            adapter.setSelectedQueueRowKey(item.queueRowKey)
        }
        item.onClick.invoke()
    }

    private fun handleTabKey(v: View, keyCode: Int, event: KeyEvent, index: Int): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (index == 0) {
                    BoundaryFocusHandler.shake(v)
                    true
                } else false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (index == tabs.lastIndex) {
                    BoundaryFocusHandler.shake(v)
                    true
                } else false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                BoundaryFocusHandler.shake(v)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Tab[0] 稍后播放：右键进入「功能按钮栏」（首个可聚焦控件）
                // Tab[1..N]：右键进入列表第 1 项
                if (index == 0 && selectedTab == 0) focusButtonBarFirstOrShake(v)
                else focusFirstRowOrShake(v)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                switchTab(index)
                true
            }
            else -> false
        }
    }

    /**
     * 焦点从左侧 Tab/Tab0 右键，或列表第 1 项 UP 时，进入按钮栏第一个可聚焦控件。
     * 优先【播放】（若列表为空播放 disabled，则【清除】）。
     */
    private fun focusButtonBarFirstOrShake(anchor: View): Boolean {
        val target: View? = btnPlay?.takeIf { it.isShown && it.isFocusable }
            ?: btnClear?.takeIf { it.isShown && it.isFocusable }
            ?: capsuleSortBy?.takeIf { it.isShown && it.isFocusable }
            ?: capsuleOrder?.takeIf { it.isShown && it.isFocusable }
        if (target?.requestFocus() == true) return true
        // 按钮栏无焦点（理论不会，至少清除可聚焦），落到列表第 1 项。
        return focusFirstRowOrShake(anchor)
    }

    private fun focusFirstRowOrShake(anchor: View): Boolean {
        val list = listView ?: return false
        val row = list.findViewHolderForAdapterPosition(0)?.itemView
        if (row?.requestFocus() == true) return true
        if (adapter.itemCount <= 0) {
            BoundaryFocusHandler.shake(anchor)
            return true
        }
        list.scrollToPosition(0)
        list.post {
            if (list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() != true) {
                BoundaryFocusHandler.shake(anchor)
            }
        }
        return true
    }

    private fun focusSelectedTab(): Boolean {
        return tabs.getOrNull(selectedTab)?.requestFocus() == true
    }

    private fun handleRowKey(v: View, keyCode: Int, event: KeyEvent, position: Int): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> focusSelectedTab()
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (position == 0) {
                    // 列表第 1 项 UP：
                    //  稍后播放 Tab → 回到按钮栏（首个可聚焦控件）；
                    //  其他 Tab → 按钮栏不可见，顶部边界抖动。
                    if (selectedTab == 0) focusButtonBarFirstOrShake(v)
                    else { BoundaryFocusHandler.shake(v); true }
                } else false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (position == adapter.itemCount - 1) {
                    BoundaryFocusHandler.shake(v)
                    true
                } else false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                BoundaryFocusHandler.shake(v)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                adapter.rowAt(position)?.let { handleRowClick(position, it) }
                true
            }
            else -> false
        }
    }

    private fun switchTab(index: Int) {
        selectedTab = index.coerceIn(0, tabs.lastIndex)
        tabs.forEachIndexed { i, tab ->
            tab.isSelected = i == selectedTab
            refreshTab(tab, tab.hasFocus(), tab.isSelected)
        }
        // 仅稍后播放 Tab 显示功能按钮栏；推荐 / 热门 Tab 隐藏按钮栏，让列表独占高度。
        buttonBar?.visibility = if (selectedTab == 0) View.VISIBLE else View.GONE
        renderCurrentTab()
    }

    private fun renderCurrentTab() {
        val rows = when (selectedTab) {
            0 -> buildQueueRows()
            1 -> buildRecommendationRows()
            else -> buildPopularRows()
        }
        adapter.submit(rows)
        refreshActionButtonsEnabled()
    }

    private fun buildQueueRows(): List<RowItem> {
        val raw = queueStore.all()
        if (raw.isEmpty()) return listOf(RowItem(null, "暂无稍后播放内容", "可以从推荐内容或收藏页加入", "", null))
        val sorted = PlayQueueStore.SortConfig.apply(raw)
        return sorted.map { item ->
            RowItem(
                queueRowKey = item.id,
                title = item.title.ifBlank { item.uri },
                subtitle = "${statusLabel(item.status)} · ${item.source.ifBlank { "queue" }}",
                action = "按 OK 播放",
                onClick = {
                    pendingRestoreTab = 0
                    pendingRestoreQueueItemId = item.id
                    val pos = sorted.indexOfFirst { it.id == item.id }
                    if (pos >= 0) pendingRestorePosition = pos
                    adapter.setSelectedQueueRowKey(item.id)
                    (context as? NewMainActivity)?.startQueuePlayback(item, toastText = "已切换：${item.title}", focusRootOnReturn = false)
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // 功能按钮栏：播放 / 清除 动作
    // ------------------------------------------------------------------
    /**
     * 播放：按排序后第 1 条非 FINISHED 项起播。
     *  - 场景 H：若排序后第 1 条是 FINISHED，跳过；全部都是 FINISHED → toast。
     *  - 若有正在 PLAYING 的项，仍优先按排序后第 1 条非 FINISHED 启动，符合用户「从列表顶部播放」的直觉。
     */
    private fun onPlayFirstClicked() {
        val sorted = PlayQueueStore.SortConfig.apply(queueStore.all())
        val first = sorted.firstOrNull { it.status != PlayQueueStore.Status.FINISHED }
        if (first == null) {
            Toast.makeText(context, "队列内容均已播放完毕，可切换排序或清空列表", Toast.LENGTH_SHORT).show()
            return
        }
        // 记录选中 + 焦点恢复位置（从播放器返回后落到第 1 条）
        pendingRestoreTab = 0
        pendingRestoreQueueItemId = first.id
        pendingRestorePosition = sorted.indexOfFirst { it.id == first.id }.coerceAtLeast(0)
        adapter.setSelectedQueueRowKey(first.id)
        (context as? NewMainActivity)?.startQueuePlayback(
            first,
            toastText = "▶ 播放：${first.title}",
            focusRootOnReturn = false
        )
    }

    /**
     * 清除：用户明确无需确认，立即删除队列全部数据。
     *  - 场景 C：清除成功后，焦点回到【播放】按钮（首个可聚焦控件）。
     *  - 场景 I：即使当前有项处于 PLAYING，也直接清除内存+持久化队列。
     *    不会强制打断正在播放的 PlayerActivity，它播完下一条 tryAdvanceQueueOnEnded()
     *    找不到下一项时会自然显示「队列播放完毕」。
     */
    private fun onClearAllClicked() {
        if (queueStore.size() == 0) {
            Toast.makeText(context, "列表已为空", Toast.LENGTH_SHORT).show()
            return
        }
        queueStore.clear()
        Toast.makeText(context, "稍后播放列表已清空", Toast.LENGTH_SHORT).show()
        adapter.setSelectedQueueRowKey(null)
        pendingRestoreQueueItemId = null
        pendingRestorePosition = RecyclerView.NO_POSITION
        // 焦点回落到【播放】按钮；如果此时播放按钮 disabled，则落到【清除】按钮。
        val fallback: View? = btnPlay?.takeIf { it.isFocusable } ?: btnClear
        fallback?.post { fallback.requestFocus() }
    }

    private fun buildRecommendationRows(): List<RowItem> {
        val flat = recommendations.flatMap { rec -> rec.videos.map { rec.collectionName to it } }.filter { it.second.url.isNotBlank() }
        if (flat.isEmpty()) return listOf(RowItem(null, "暂无推荐缓存", "收到云端推荐后会展示在这里", "", null))
        return flat.map { (collectionName, video) ->
            RowItem(
                queueRowKey = null,
                title = video.title.ifBlank { video.url },
                subtitle = collectionName.ifBlank { "云端推荐" },
                action = "+ 加入稍后播放",
                onClick = {
                    queueStore.add(video.title, video.url, "cloud_recommend")
                    Toast.makeText(context, "已加入稍后播放", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun buildPopularRows(): List<RowItem> {
        if (popularCollections.isEmpty()) return listOf(RowItem(null, "暂无热门内容", "云端合集下载后会累积热度", "", null))
        return popularCollections.map { collection ->
            RowItem(
                queueRowKey = null,
                title = collection.name.ifBlank { collection.id },
                subtitle = "下载量 ${collection.downloadCount} · ${collection.itemCount} 个内容",
                action = "下载/查看",
                onClick = { downloadPopularCollection(collection) }
            )
        }
    }

    private fun loadAsyncContent() {
        adapter.submit(listOf(RowItem(null, "正在读取内容…", "推荐内容和热门内容将在后台加载", "", null)))
        Thread({
            val rec = try { recommendationsStore.fetchIncomingForToday().orEmpty() } catch (_: Throwable) { emptyList() }
            val popular = try {
                syncManager.fetchCloudIndexConfig()?.collections.orEmpty()
                    .sortedWith(compareByDescending<GiteeSyncManager.CloudCollection> { it.downloadCount }.thenBy { it.name })
            } catch (_: Throwable) { emptyList() }
            post {
                recommendations = rec
                popularCollections = popular
                renderCurrentTab()
            }
        }, "watch-later-load").start()
    }

    private fun downloadPopularCollection(collection: GiteeSyncManager.CloudCollection) {
        if (collection.passwordHash.isNotBlank()) {
            Toast.makeText(context, "该合集已加锁，请到云同步弹窗解锁下载", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, "正在下载「${collection.name}」…", Toast.LENGTH_SHORT).show()
        Thread({
            val count = try { syncManager.downloadCollections(listOf(collection.id), popularCollections) } catch (_: Throwable) { 0 }
            if (count > 0) syncManager.incrementDownloadCount(listOf(collection.id))
            handler.post {
                val ctx = context
                if (ctx is android.app.Activity && (ctx.isFinishing || ctx.isDestroyed)) return@post
                if (count > 0) {
                    Toast.makeText(context, "本地合集数据已更新，请前往【我的收藏】查看", Toast.LENGTH_LONG).show()
                    loadAsyncContent()
                } else {
                    Toast.makeText(context, "❌ 下载失败", Toast.LENGTH_SHORT).show()
                }
            }
        }, "watch-later-popular-download").start()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        private val data = mutableListOf<RowItem>()
        /** 稍后播放 Tab 下的单选互斥选中态；仅 queueRowKey != null（即稍后播放行）参与。 */
        var selectedQueueRowKey: String? = null
            private set

        /** 设置选中项（互斥），返回 true 表示发生实际变更并已局部刷新。 */
        fun setSelectedQueueRowKey(key: String?): Boolean {
            val oldKey = selectedQueueRowKey
            if (oldKey == key) return false
            selectedQueueRowKey = key
            val oldPos = if (oldKey != null) indexOfQueueRowKey(oldKey) else -1
            val newPos = if (key != null) indexOfQueueRowKey(key) else -1
            if (oldPos in data.indices) notifyItemChanged(oldPos)
            if (newPos in data.indices && newPos != oldPos) notifyItemChanged(newPos)
            return true
        }

        fun queueRowKeyAt(position: Int): String? = data.getOrNull(position)?.queueRowKey
        fun indexOfQueueRowKey(key: String): Int = data.indexOfFirst { it.queueRowKey == key }

        fun submit(list: List<RowItem>) {
            data.clear()
            data.addAll(list)
            // 提交新列表后，若当前选中的 key 在新列表里已不存在（比如稍后播放项被删除），清空选中态避免"幽灵选中"。
            if (selectedQueueRowKey != null && selectedQueueRowKey !in data.asSequence().mapNotNull { it.queueRowKey }) {
                selectedQueueRowKey = null
            }
            notifyDataSetChanged()
        }
        fun rowAt(position: Int): RowItem? = data.getOrNull(position)
        override fun getItemCount(): Int = data.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                setPadding(dp(18), dp(12), dp(18), dp(12))
                clipChildren = false
                clipToPadding = false
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                }
            }
            val title = TextView(parent.context).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val sub = TextView(parent.context).apply {
                textSize = 13f
                setTextColor(Color.argb(205, 225, 230, 238))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(5), 0, 0)
            }
            val action = TextView(parent.context).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(WARM)
                gravity = Gravity.END
                setPadding(0, dp(6), 0, 0)
            }
            root.addView(title)
            root.addView(sub)
            root.addView(action)
            return VH(root, title, sub, action)
        }
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])
        inner class VH(private val root: LinearLayout, private val title: TextView, private val sub: TextView, private val action: TextView) : RecyclerView.ViewHolder(root) {
            fun bind(item: RowItem) {
                title.text = item.title
                sub.text = item.subtitle
                action.text = item.action
                action.visibility = if (item.action.isBlank()) View.GONE else View.VISIBLE
                root.alpha = if (item.onClick == null) 0.82f else 1f
                val selected = item.queueRowKey != null && item.queueRowKey == selectedQueueRowKey
                root.background = rowBg(false, selected)
                root.setOnFocusChangeListener { v, has ->
                    val isSelected = item.queueRowKey != null && item.queueRowKey == selectedQueueRowKey
                    v.background = rowBg(has, isSelected)
                    FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 16)
                }
                root.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) handleRowClick(pos, item)
                }
                root.setOnKeyListener { v, keyCode, event ->
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnKeyListener false
                    handleRowKey(v, keyCode, event, pos)
                }
            }
        }
    }

    private fun tabButton(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        refreshTab(this, false, false)
    }

    private fun refreshTab(tab: TextView, focused: Boolean, selected: Boolean) {
        tab.setTextColor(if (selected) WARM else Color.argb(235, 245, 245, 245))
        tab.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (focused) Color.argb(42, 255, 255, 255) else Color.argb(28, 255, 255, 255))
            setStroke(dp(if (focused) 3 else 1), if (focused) WARM else Color.argb(145, 210, 214, 222))
        }
        FocusFxHelper.applyFocusFxState(tab, focused, cornerRadiusDp = 16)
    }

    /**
     * 行背景二态：
     *  - focused：聚焦（3dp 暖黄描边 + 透明底，保证 TV 焦点可见）
     *  - 其他：普通默认态（1dp 灰描边 + 微透明白底）
     * 选中态不再添加额外的边框与背景样式，保持列表项默认状态。
     */
    private fun rowBg(focused: Boolean, selected: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        when {
            focused -> {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), WARM)
            }
            else -> {
                setColor(Color.argb(28, 255, 255, 255))
                setStroke(dp(1), Color.argb(150, 210, 214, 222))
            }
        }
    }

    override fun refreshTheme() {
        super.refreshTheme()
        contentContainer.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(70, 255, 255, 255))
        }
    }

    private fun statusLabel(status: PlayQueueStore.Status): String = when (status) {
        PlayQueueStore.Status.PENDING -> "待播放"
        PlayQueueStore.Status.PLAYING -> "播放中"
        PlayQueueStore.Status.FINISHED -> "已播完"
    }

    /**
     * @param queueRowKey 稍后播放 Tab 的稳定键（QueueItem.id），用于互斥选中 + 播放器返回焦点恢复；
     *        推荐/热门/空态填 null，不参与选中态。
     */
    private data class RowItem(
        val queueRowKey: String?,
        val title: String,
        val subtitle: String,
        val action: String,
        val onClick: (() -> Unit)?
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val WARM = Color.rgb(245, 196, 81)
    }
}
