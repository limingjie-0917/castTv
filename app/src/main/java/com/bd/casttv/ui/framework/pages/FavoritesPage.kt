package com.bd.casttv.ui.framework.pages

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.favorites.displayThumbPath
import com.bd.casttv.dlna.FavoriteTransferServer
import com.bd.casttv.dlna.PlaybackController
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.queue.PlayQueueStore
import com.bd.casttv.sync.CloudSyncDialog
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.GlowUnderlineView
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.NaturalSorter
import com.bd.casttv.util.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * 收藏页 - 上下布局：
 *   顶部：标题行。
 *   第二行：[合集管理(齿轮)] [合集1] [合集2] [合集3]... 水平铺开，可滚动；合集获焦即刷新内容。
 *   下方：[合集名称（共X集）] [编辑] [批量操作] + 3 列视频卡片网格。
 */
class FavoritesPage(context: Context) : BasePage(context) {
    override val pageId = "favorites"
    override val pageTitle = "小新的收藏哦！"
    override val pageIconRes = R.drawable.ic_dock_favorite
    override val enablePageScroll: Boolean = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true
    override val pageStickerRes: Int get() = R.drawable.sticker_bochan

    private val store = FavoritesStore(context)

    private val btnCollectionManage = mkSideManageButton()

    private val sideScroll = ScrollView(context)
    private val sideList = LinearLayout(context)

    private val rightPanel = LinearLayout(context)
    private val rightHeader = LinearLayout(context)
    private val rightHeaderTitle = TextView(context)
    // 切换合集时的 loading 指示（位于右侧视频内容区域中心）
    private val collectionLoading = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
        visibility = View.GONE
        indeterminateTintList = ColorStateList.valueOf(Color.rgb(245, 196, 81))
    }
    private val hideCollectionLoading = Runnable { collectionLoading.visibility = View.GONE }
    private val btnEdit = mkIconButton(R.drawable.ic_fav_header_edit).apply { contentDescription = "编辑合集" }
    private val btnMultiSelect = mkIconButton(R.drawable.ic_fav_header_batch).apply { contentDescription = "批量操作" }
    // 收藏页顶部工具栏「推荐」入口：文字按钮，位于批量操作按钮右侧。
    // - 仅当前合集有内容时显示；
    // - private / shared 合集都允许正常进入推荐流程，不影响焦点导航。
    private val btnRecommend = mkTextButton("推荐").apply { contentDescription = "推荐合集到云端" }
    private val gridRecycler = RecyclerView(context)
    private val emptyFallbackContainer = FrameLayout(context)

    private var collections: List<FavoritesStore.CollectionInfo> = emptyList()
    private var selectedCollectionId: String = ""
    private var multiSelectMode: Boolean = false
    private var lastPlayedItemIndex: Int = -1
    private var lastPlayedItemUri: String = ""
    private var lastReloadSignature: String = ""
    private var selectedCollectionItemsSignature: String = ""
    private var localChangeDialogShowing: Boolean = false
    private var pendingVideoCardFocusView: View? = null
    private var pendingVideoCardFocusKeyCode: Int = 0
    private var pendingVideoCardFocusIndex: Int = -1

    // ---------------- P0-3：异步加载 + 焦点防抖 ----------------

    /** 页面独立协程作用域，用于把 store 读盘挪出主线程；页面 detach 时统一取消。 */
    private var pageScopeJob = SupervisorJob()
    private var pageScope = CoroutineScope(pageScopeJob + Dispatchers.Main.immediate)

    /** 焦点变化触发合集切换的防抖延时：避免连续按方向键时同步读盘。 */
    private val focusDebounceDelayMs: Long = 300L

    /** 挂起的合集切换任务，用于防抖：新事件到来时先取消旧任务。 */
    private val focusDebounceRunnable = Runnable {
        val pendingId = pendingFocusCollectionId
        pendingFocusCollectionId = null
        if (pendingId != null && pendingId != selectedCollectionId) {
            selectedCollectionId = pendingId
            showCollectionLoading()
            renderRight()
            sideItemViews.forEach { (id, tv) ->
                val selected = id == selectedCollectionId
                tv.isSelected = selected
                updateSideButtonVisual(tv, selected, tv.isFocused)
            }
        }
    }
    private var pendingFocusCollectionId: String? = null

    /** 当前正在执行的异步右侧内容加载任务，切合集时先取消上一个。 */
    private var currentLoadJob: Job? = null
    /** 收藏页异步读取 generation：播放器返回/切合集时丢弃旧回调，避免连续播放退出后乱序刷新。 */
    private val asyncGeneration = AtomicInteger(0)
    /** 播放器返回后仅刷新左侧合集数量的 generation，独立于右侧内容 asyncGeneration，避免互相抢占。 */
    private val sidebarCountGeneration = AtomicInteger(0)
    private val selectedItemIds: MutableSet<String> = mutableSetOf()
    private val sideItemViews = mutableListOf<Pair<String, View>>()

    init {
        buildLayout()
        reload()
    }

    override fun onEnter() {
        refreshAfterPlayerReturn()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // P0-3：页面 detach 时取消所有异步加载任务，避免泄漏
        currentLoadJob?.cancel()
        currentLoadJob = null
        pageScopeJob.cancel()
    }

    fun refreshAfterPlayerReturn() {
        refreshSidebarCountsAfterPlayerReturn()
        checkSelectedCollectionLocalChangesAsync()
    }

    private fun refreshSidebarCountsAfterPlayerReturn() {
        ensurePageScopeActive()
        val generation = sidebarCountGeneration.incrementAndGet()
        val selectedIdSnapshot = selectedCollectionId
        pageScope.launch {
            val freshCollections = withContext(Dispatchers.IO) {
                runCatching { store.collectionsInfo() }.getOrElse { emptyList() }
            }
            if (generation != sidebarCountGeneration.get()) return@launch

            val oldSignature = buildReloadSignature(selectedIdSnapshot, collections)
            val newSignature = buildReloadSignature(selectedIdSnapshot, freshCollections)
            if (oldSignature == newSignature) return@launch

            collections = freshCollections
            if (selectedCollectionId.isNotBlank() && collections.none { it.id == selectedCollectionId }) {
                selectedCollectionId = ""
            }
            renderSidebar()
            renderRightHeader(
                collections.firstOrNull { it.id == selectedCollectionId },
                itemsSizeOverride = collections.firstOrNull { it.id == selectedCollectionId }?.itemCount ?: 0,
                isLoading = currentLoadJob != null
            )
            lastReloadSignature = buildReloadSignature(selectedCollectionId, collections)
        }
    }

    private fun buildReloadSignature(
        selectedId: String,
        cols: List<FavoritesStore.CollectionInfo>
    ): String {
        val sb = StringBuilder()
        sb.append(selectedId).append('|')
        cols.forEach { sb.append(it.id).append('#').append(it.itemCount).append(',') }
        return sb.toString()
    }

    private fun computeReloadSignature(): String {
        // 注意：这里不要再同步读盘（collectionsInfo）——否则会在页面切换时造成卡顿。
        return buildReloadSignature(selectedCollectionId, collections)
    }

    private fun buildSelectedCollectionItemsSignature(
        collectionId: String,
        items: List<FavoritesStore.FavoriteItem>
    ): String = buildString {
        append(collectionId).append('|').append(items.size).append('|')
        items.forEach { item ->
            append(item.itemId).append('\u001F')
                .append(item.uri).append('\u001F')
                .append(item.title).append('\u001F')
                .append(item.source).append('\u001F')
                .append(item.thumbPath).append('\u001E')
        }
    }

    private fun computeSelectedCollectionItemsSignature(): String {
        val collectionId = selectedCollectionId
        if (collectionId.isBlank()) return ""
        val collection = try { store.collection(collectionId) } catch (_: Throwable) { null } ?: return "missing:$collectionId"
        return buildSelectedCollectionItemsSignature(collectionId, collection.items)
    }

    private fun checkSelectedCollectionLocalChangesAsync() {
        val collectionId = selectedCollectionId
        val before = selectedCollectionItemsSignature
        if (collectionId.isBlank() || before.isBlank() || localChangeDialogShowing) {
            post { restoreLastPlayedFocusIfNeeded() }
            return
        }
        ensurePageScopeActive()
        val generation = asyncGeneration.incrementAndGet()
        pageScope.launch {
            val after = withContext(Dispatchers.IO) {
                val collection = runCatching { store.collection(collectionId) }.getOrNull()
                if (collection == null) "missing:$collectionId" else buildSelectedCollectionItemsSignature(collectionId, collection.items)
            }
            if (generation != asyncGeneration.get() || collectionId != selectedCollectionId) return@launch
            if (after == before) {
                restoreLastPlayedFocusIfNeeded()
            } else if (!localChangeDialogShowing) {
                showLocalDataChangedDialog(after)
            }
        }
    }

    private fun showLocalDataChangedDialog(newSignature: String) {
        localChangeDialogShowing = true
        val shouldRestoreVideoFocus = lastPlayedItemUri.isNotBlank() || lastPlayedItemIndex >= 0
        val focusBeforeDialog = findFocus()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = context.getDrawable(R.drawable.bg_dialog_crayon_panel)
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            clipChildren = false
            clipToPadding = false
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val sticker = ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(pageStickerRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
            alpha = 0.92f
            rotation = -8f
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val title = TextView(context).apply {
            text = "本地数据有更新"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
        }
        titleRow.addView(sticker, LinearLayout.LayoutParams(dp(42), dp(42)))
        titleRow.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        val message = TextView(context).apply {
            text = "检测到当前合集的视频数据已发生变化。是否现在刷新收藏内容？"
            textSize = 15f
            setTextColor(context.getColor(R.color.text_primary))
            gravity = Gravity.START
            setPadding(0, dp(16), 0, 0)
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
        }
        lateinit var dialog: AlertDialog
        val cancel = dialogButton("取消") {
            selectedCollectionItemsSignature = newSignature
            dialog.dismiss()
            focusBeforeDialog?.post { focusBeforeDialog.requestFocus() }
        }
        val refresh = dialogButton("刷新") {
            dialog.dismiss()
            refreshSelectedCollectionAfterLocalChange(shouldRestoreVideoFocus)
        }
        cancel.nextFocusRightId = View.NO_ID
        refresh.nextFocusLeftId = View.NO_ID
        cancel.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { BoundaryFocusHandler.shake(v); true }
                else -> false
            }
        }
        refresh.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { BoundaryFocusHandler.shake(v); true }
                else -> false
            }
        }
        buttonRow.addView(cancel, LinearLayout.LayoutParams(dp(96), dp(42)))
        buttonRow.addView(refresh, LinearLayout.LayoutParams(dp(96), dp(42)).apply { marginStart = dp(14) })
        content.addView(titleRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(message, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(buttonRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        panel.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnDismissListener { localChangeDialogShowing = false }
        dialog.setOnShowListener { cancel.requestFocus() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(dp(500), LayoutParams.WRAP_CONTENT)
        }
    }

    private fun refreshSelectedCollectionAfterLocalChange(restoreVideoFocus: Boolean) {
        // 注意：这里不能同步读盘，否则从播放器返回时仍可能产生明显卡顿。
        // 统一复用 reload 的 IO 路径，并在 reload 完成后再做焦点恢复。
        val keepSelectedId = selectedCollectionId
        reload {
            val stillExists = keepSelectedId.isNotBlank() && collections.any { it.id == keepSelectedId }
            if (!stillExists) {
                post { focusSelectedSideTab() }
                return@reload
            }
            // reload 完成后，右侧会由 renderRight() 异步加载 items。
            // 这里仅重置签名，避免后续本地变更判断用旧值。
            selectedCollectionItemsSignature = ""
            post {
                if (restoreVideoFocus) restoreLastPlayedFocusIfNeeded() else focusSelectedSideTab()
            }
        }
    }

    override fun focusToFirstContent(): Boolean {
        val ok = btnCollectionManage.requestFocus()
        if (ok) onFocusEnterContent()
        return ok
    }

    // ---------------- 布局构建 ----------------

    private fun buildLayout() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 页头由 BasePage 统一提供；进一步缩小内容与容器边框距离，保留极小安全边距。
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false; clipToPadding = false
        }

        // 顶部原「👦 🖍️ 小新的收藏夹」标题行 + titleUnderline 已合并到 BasePage 统一页头，
        // 焦点态渐变分隔线由 PageHeaderView 300ms 动画统一驱动，此处不再复用旧的自绘 pageRootFocus 焦点回调。

        // 左侧竖排侧边栏：合集管理 + 合集列表项纵向铺开
        // 目标：滚动内容不超出边框，但 Tab 获焦时的光晕/放大允许溢出到父容器范围
        sideList.orientation = LinearLayout.VERTICAL
        sideList.gravity = Gravity.CENTER_HORIZONTAL

        val sideTabContainer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.argb(51, 0x41, 0x69, 0xE1))
                cornerRadius = dp(16).toFloat()
            }
        }

        sideScroll.apply {
            isFillViewport = true
            setPadding(0, 0, 0, 0)
            clipToPadding = true
            clipChildren = true
            overScrollMode = View.OVER_SCROLL_NEVER
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

            (parent as? ViewGroup)?.removeView(this)
            addView(sideList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        sideTabContainer.addView(
            sideScroll,
            FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        root.addView(
            sideTabContainer,
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = dp(4) }
        )

        val verticalDivider = View(context).apply {
            setBackgroundColor(Color.argb(80, 245, 196, 81))
        }
        root.addView(verticalDivider, LinearLayout.LayoutParams(dp(1), LayoutParams.MATCH_PARENT).apply { marginEnd = dp(4) })

        // 右侧内容区：合集名称所属区域 + 视频卡片列表
        // 目标：外层容器提供灰色半透明背景，且 rightHeader 固定不随视频卡片滚动；同时允许焦点光晕/放大溢出
        rightPanel.apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
        }

        // 新容器：包裹 rightHeader + gridRecycler
        val contentWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(51, 0x41, 0x69, 0xE1))
                cornerRadius = dp(14).toFloat()
            }
            // 滚动内容（gridRecycler 的卡片）必须裁剪在容器内，避免上滑时越界到容器外
            clipChildren = true
            clipToPadding = true
        }

        rightHeader.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            // 背景上移到 contentWrapper，避免双重背景叠加太深
            background = null
            clipChildren = false; clipToPadding = false
        }
        rightHeaderTitle.apply {
            textSize = 20f
            setTextColor(WARM)
            setPadding(dp(4), 0, dp(16), 0)
        }
        rightHeader.addView(rightHeaderTitle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        listOf(btnEdit, btnMultiSelect).forEach { b ->
            rightHeader.addView(b, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(12) })
        }
        rightHeader.addView(btnRecommend, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(12) })

        // 让「合集名称所属区域」固定在顶部
        contentWrapper.addView(rightHeader, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        gridRecycler.apply {
            layoutManager = GridLayoutManager(context, GRID_COLUMNS)
            clipToPadding = false; clipChildren = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(2), 0, dp(2), dp(18))
            addItemDecoration(GridSpacingDecoration(dp(8)))
        }
        emptyFallbackContainer.apply {
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
        }
        val gridArea = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            addView(gridRecycler, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(emptyFallbackContainer, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(collectionLoading, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
        }
        // 视频卡片占满剩余高度
        contentWrapper.addView(gridArea, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        rightPanel.addView(contentWrapper, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(rightPanel, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 4f))

        contentContainer.addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 工具栏按钮点击
        btnCollectionManage.setOnClickListener { openCollectionManagePage() }

        // 右侧头部按钮点击
        btnEdit.setOnClickListener { renameSelectedCollection() }
        btnMultiSelect.setOnClickListener { toggleMultiSelect() }
        btnRecommend.setOnClickListener { openRecommendDialog() }

        // 边界抖动 & 焦点方向拦截
        listOf(btnCollectionManage).forEach { b ->
            b.setOnKeyListener { v, _, e -> handleSideActionKey(v, e) }
        }
        listOf(btnEdit, btnMultiSelect, btnRecommend).forEach { b ->
            b.setOnKeyListener { v, _, e -> handleRightHeaderKey(v, e) }
        }
    }

    // ---------------- 数据渲染 ----------------

    private fun reload(onDone: (() -> Unit)? = null) {
        // v1.2.53 后收藏页切换卡顿：这里如果在主线程同步读盘（collectionsInfo/collection），
        // 会直接阻塞页面切换/动画。统一把读盘挪到 Dispatchers.IO，并用 generation 丢弃旧回调。
        ensurePageScopeActive()
        val generation = asyncGeneration.incrementAndGet()

        // 首次进入/刷新时先给一个轻量的 UI 更新，避免白屏。
        // 先渲染侧边栏（至少保证“合集管理”按钮可聚焦），避免异步期间焦点无处可落。
        renderSidebar()
        // 注意：showCollectionLoading() 会在 300ms 后自动隐藏，不适合 reload 这种不确定耗时的 IO。
        collectionLoading.removeCallbacks(hideCollectionLoading)
        collectionLoading.visibility = View.VISIBLE

        pageScope.launch {
            val cols = withContext(Dispatchers.IO) {
                runCatching { store.collectionsInfo() }.getOrElse { emptyList() }
            }
            if (generation != asyncGeneration.get()) return@launch

            collections = cols
            if (selectedCollectionId.isNotBlank() && collections.none { it.id == selectedCollectionId }) {
                selectedCollectionId = ""
            }
            selectedItemIds.clear()
            multiSelectMode = false

            renderSidebar()
            renderRight()

            // 用刚拉到的 collections 计算签名，避免再次同步读盘。
            lastReloadSignature = buildReloadSignature(selectedCollectionId, collections)
            // 当前合集 items 签名由 renderRight() 的 IO 协程读取 items 后回填，避免 reload 在主线程重复读盘。
            selectedCollectionItemsSignature = ""

            onDone?.invoke()
        }
    }

    private fun renderSidebar() {
        sideList.removeAllViews()
        sideItemViews.clear()
        addSideActionButton(btnCollectionManage)
        if (collections.isEmpty()) sideList.addView(mkEmptyHint("暂无合集"))
        collections.forEach { sideList.addView(mkSideItem(it)) }
        linkSideFocusOrder()
    }

    private fun addSideActionButton(button: View) {
        (button.parent as? ViewGroup)?.removeView(button)
        sideList.addView(button, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(4) })
    }

    private fun linkSideFocusOrder() {
        val focusViews = sideItemViews.map { it.second }
        btnCollectionManage.id = if (btnCollectionManage.id == View.NO_ID) View.generateViewId() else btnCollectionManage.id
        btnCollectionManage.nextFocusUpId = btnCollectionManage.id
        btnCollectionManage.nextFocusDownId = focusViews.firstOrNull()?.id ?: btnCollectionManage.id
        btnCollectionManage.nextFocusLeftId = btnCollectionManage.id
        btnCollectionManage.nextFocusRightId = rightHeaderPreferredForSelected()?.id ?: gridRecycler.getChildAt(0)?.id ?: btnCollectionManage.id
        linkRightHeaderFocusOrder()

        focusViews.forEachIndexed { index, item ->
            item.id = if (item.id == View.NO_ID) View.generateViewId() else item.id
            item.nextFocusUpId = when {
                index == 0 -> btnCollectionManage.id
                else -> focusViews[index - 1].id
            }
            item.nextFocusDownId = focusViews.getOrNull(index + 1)?.id ?: item.id
            item.nextFocusLeftId = item.id
            item.nextFocusRightId = rightHeaderPreferredForSelected()?.id ?: gridRecycler.getChildAt(0)?.id ?: item.id
        }
    }

    private fun renderRight() {
        val info = collections.firstOrNull { it.id == selectedCollectionId }
        // P0-3.2：读盘挪到 Dispatchers.IO，主线程先渲染骨架 + Loading，再回主线程 submitList。
        ensurePageScopeActive()
        currentLoadJob?.cancel()
        currentLoadJob = null
        val generation = asyncGeneration.incrementAndGet()
        // 先做一次「不依赖 items 内容」的 UI 更新（标题、按钮可见性等）
        renderRightHeader(info, itemsSizeOverride = info?.itemCount ?: 0, isLoading = info != null)
        // 若尚未选择合集，直接渲染 fallback 并返回
        if (info == null) {
            collectionLoading.removeCallbacks(hideCollectionLoading)
            collectionLoading.visibility = View.GONE
            renderRightBody(info, items = emptyList())
            return
        }
        // 切换合集进入 loading 时，必须先隐藏兜底页；避免异步结果未返回前误显示空状态。
        emptyFallbackContainer.visibility = View.GONE
        emptyFallbackContainer.removeAllViews()
        gridRecycler.visibility = View.GONE
        // 展示 loading，等待异步结果决定显示列表还是兜底页
        collectionLoading.removeCallbacks(hideCollectionLoading)
        collectionLoading.visibility = View.VISIBLE
        val targetCollectionId = info.id
        lateinit var loadJobSnapshot: Job
        loadJobSnapshot = pageScope.launch(start = CoroutineStart.LAZY) {
            try {
                val items = withContext(Dispatchers.IO) {
                    runCatching { sortedFavoriteItems(store.collection(targetCollectionId)?.items.orEmpty()) }.getOrElse { emptyList() }
                }
                // 用户可能已经切走，或播放器返回检查/下一次加载已经更新 generation：旧结果直接丢弃
                if (generation != asyncGeneration.get() || targetCollectionId != selectedCollectionId) return@launch
                selectedCollectionItemsSignature = buildSelectedCollectionItemsSignature(targetCollectionId, items)
                renderRightBody(info, items)
            } finally {
                // 用 launch 前创建、启动前赋值的不可变 job snapshot 做身份校验，避免 launch 后再赋值 currentLoadJob 的竞态。
                if (currentLoadJob == loadJobSnapshot) {
                    collectionLoading.removeCallbacks(hideCollectionLoading)
                    collectionLoading.visibility = View.GONE
                    currentLoadJob = null
                }
            }
        }
        currentLoadJob = loadJobSnapshot
        loadJobSnapshot.start()
    }

    private fun ensurePageScopeActive() {
        if (!pageScopeJob.isActive) {
            pageScopeJob = SupervisorJob()
            pageScope = CoroutineScope(pageScopeJob + Dispatchers.Main.immediate)
        }
    }

    private fun sortedFavoriteItems(items: List<FavoritesStore.FavoriteItem>): List<FavoritesStore.FavoriteItem> =
        items.sortedWith { left, right ->
            val leftTitle = left.title.ifBlank { left.uri }
            val rightTitle = right.title.ifBlank { right.uri }
            val titleCompare = NaturalSorter.compare(leftTitle, rightTitle)
            if (titleCompare != 0) titleCompare else left.uri.compareTo(right.uri)
        }


    /** 渲染右侧头部（标题、按钮），不依赖具体 items 内容，只用 itemCount。 */
    private fun renderRightHeader(
        info: FavoritesStore.CollectionInfo?,
        itemsSizeOverride: Int,
        isLoading: Boolean = false
    ) {
        val showFallback = info == null || (!isLoading && itemsSizeOverride == 0)
        rightHeaderTitle.text = when {
            showFallback -> "🖍️ 功能介绍"
            else -> "🧸 ${info!!.name}（共${info.itemCount}集）"
        }
        val hasSelection = info != null
        val isProtected = info?.isPreset == true || info?.isDefault == true
        btnEdit.visibility = if (isProtected || !hasSelection) View.GONE else View.VISIBLE
        btnMultiSelect.visibility = if (showFallback) View.GONE else View.VISIBLE
        btnMultiSelect.isEnabled = hasSelection && !showFallback
        btnMultiSelect.alpha = if (hasSelection && !showFallback) 1f else 0.5f
        btnMultiSelect.contentDescription = if (multiSelectMode) "退出批量操作" else "批量操作"
        refreshIconButtonVisual(btnMultiSelect, selected = multiSelectMode, focused = btnMultiSelect.isFocused)

        btnRecommend.visibility = if (showFallback) View.GONE else View.VISIBLE
        btnRecommend.isEnabled = !showFallback
        btnRecommend.alpha = if (showFallback) 0.5f else 1f
        btnRecommend.isSelected = false
        refreshTextButtonVisual(btnRecommend, selected = false, focused = btnRecommend.isFocused)
        linkRightHeaderFocusOrder()
    }

    /** 渲染右侧内容区（items 或 fallback）。 */
    private fun renderRightBody(info: FavoritesStore.CollectionInfo?, items: List<FavoritesStore.FavoriteItem>) {
        val showFallback = info == null || items.isEmpty()
        if (showFallback && multiSelectMode) {
            multiSelectMode = false
            selectedItemIds.clear()
        }
        // 头部按钮的 fallback 状态可能因为 items 为空而变化，重新刷一次
        renderRightHeader(info, itemsSizeOverride = items.size)

        gridRecycler.visibility = if (showFallback) View.GONE else View.VISIBLE
        emptyFallbackContainer.visibility = if (showFallback) View.VISIBLE else View.GONE
        emptyFallbackContainer.removeAllViews()
        if (showFallback) {
            emptyFallbackContainer.addView(
                buildEmptyFallback(noSelection = info == null),
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            )
        }
        // P0-3.3：如已有 FavGridAdapter，则只 submitList（借助内部 DiffUtil-like 计算），否则新建。
        val existing = gridRecycler.adapter as? FavGridAdapter
        if (existing != null) {
            existing.submitList(items)
        } else {
            gridRecycler.adapter = FavGridAdapter(items)
        }
        linkSideFocusOrder()
    }

    /**
     * 切换合集时在右侧视频内容区域中心展示 loading spinner。
     * 保证快速切换时也不会出现闪现，同时给用户一个"正在加载"的视觉反馈。
     */
    private fun showCollectionLoading() {
        collectionLoading.removeCallbacks(hideCollectionLoading)
        collectionLoading.visibility = View.VISIBLE
        collectionLoading.postDelayed(hideCollectionLoading, 300L)
    }

    // ---------------- 左侧条目 ----------------

    private fun mkGroupHeader(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(WARM)
        setPadding(dp(4), dp(4), dp(4), dp(4))
        isFocusable = false
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private fun mkEmptyHint(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        setTextColor(Color.argb(140, 255, 255, 255))
        setPadding(dp(8), dp(2), dp(6), dp(8))
        isFocusable = false
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    private fun formatSideCollectionName(name: String): String {
        val cleanName = name.ifBlank { "未命名" }
        return if (cleanName.length > 8) cleanName.take(8) + "..." else cleanName
    }

    private fun mkSideItem(info: FavoritesStore.CollectionInfo): View {
        val item = mkSideButton("${formatSideCollectionName(info.name)} (${info.itemCount})")
        item.apply {
            isFocusable = true
            isFocusableInTouchMode = false
            isSelected = info.id == selectedCollectionId
            setOnFocusChangeListener { v, has ->
                if (has && info.id != selectedCollectionId) {
                    // P0-3.1：防抖，避免连续方向键切换时每一步都读盘。
                    pendingFocusCollectionId = info.id
                    v.removeCallbacks(focusDebounceRunnable)
                    v.postDelayed(focusDebounceRunnable, focusDebounceDelayMs)
                }
                updateSideButtonVisual(v, info.id == selectedCollectionId, has)
            }
            setOnClickListener {
                if (info.id != selectedCollectionId) {
                    // 点击直接切换（不走防抖），但仍走异步加载路径
                    pendingFocusCollectionId = null
                    (it as View).removeCallbacks(focusDebounceRunnable)
                    selectedCollectionId = info.id
                    showCollectionLoading()
                    renderRight()
                    sideItemViews.forEach { (id, tv) ->
                        val selected = id == selectedCollectionId
                        tv.isSelected = selected
                        updateSideButtonVisual(tv, selected, tv.isFocused)
                    }
                }
            }
            setOnLongClickListener { collectionMenu(info); true }
            setOnKeyListener { v, _, e -> handleSideKey(v, e) }
        }
        sideItemViews.add(info.id to item)
        val lp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { bottomMargin = dp(4) }
        item.layoutParams = lp
        return item
    }

    // ---------------- 右侧视频卡片 ----------------

    /**
     * 空合集/未选合集时的兜底页面：展示"如何在播放器收藏内容"的引导。
     *  - 未选合集：仅提示"请选择左侧合集"。
     *  - 已选但为空合集：展示 3 步引导（投屏 → 播放器右上角收藏图标 → 选择合集）。
     * 布局风格与卡片区一致：暖色蜡笔面板 + 浅色文字 + 暖黄强调。
     */
    private fun buildEmptyFallback(noSelection: Boolean): View {
        val WARM = Color.rgb(245, 196, 81)
        val LIGHT = Color.argb(242, 255, 250, 232)
        val DIM = Color.argb(205, 238, 232, 218)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(34), dp(44), dp(34))
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                com.bd.casttv.util.ThemeManager.currentPalette(context).pageHeaderGradient
            ).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(2), Color.argb(190, 255, 238, 158))
            }
        }

        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        hero.addView(ImageView(context).apply {
            setImageResource(if (noSelection) R.drawable.sticker_shinchan else R.drawable.sticker_nene_shiro)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginEnd = dp(18) })
        hero.addView(TextView(context).apply {
            text = if (noSelection) "先选一个合集，动感光波才能发射哦～" else "收藏夹空空的，快把喜欢的视频装进来吧～"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(3f, 0f, 2f, Color.argb(150, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(hero, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val hint = TextView(context).apply {
            text = if (noSelection) {
                "小新提示：左边选中一个合集后，右边就会显示里面收藏的视频啦。"
            } else {
                "小新教你怎么收藏：先让手机把视频投到电视上，播放页里呼出右侧菜单，点「收藏到合集」，选好合集并保存，咻——下次就能在这里直接打开啦！"
            }
            textSize = 15f
            setTextColor(LIGHT)
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(105, 18, 22, 35))
                setStroke(dp(1), Color.argb(150, 255, 255, 255))
            }
        }
        container.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })

        if (noSelection) return container

        val steps = listOf(
            Triple("1", "手机投屏到电视", "打开手机里的视频 App，选择投到这台电视，先让视频正常播放起来。"),
            Triple("2", "呼出播放器菜单", "用遥控器按 OK / 菜单键，打开播放器右侧菜单，找到「收藏到合集」。"),
            Triple("3", "选择合集并保存", "在弹窗里可以改标题、选择目标合集，也可以新建合集，确认后就收藏成功啦。")
        )
        for ((num, name, detail) in steps) {
            container.addView(buildStepRow(num, name, detail, WARM, LIGHT, DIM),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(5)
                    bottomMargin = dp(5)
                })
        }

        val tail = TextView(context).apply {
            text = "小新补充：左侧齿轮「合集管理」可以新建、改名、导入直播源；收藏成功后，这里就会出现视频卡片啦～"
            textSize = 13f
            setTextColor(Color.argb(225, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(16), dp(12), 0)
        }
        container.addView(tail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        return container
    }

    private fun buildStepRow(num: String, name: String, detail: String, warm: Int, light: Int, dim: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(140, 34, 36, 44))
            }
        }
        val numBadge = TextView(context).apply {
            text = num
            textSize = 22f
            setTextColor(warm)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, dp(16), 0)
        }
        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val nameTv = TextView(context).apply {
            text = name
            textSize = 15f
            setTextColor(light)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val detailTv = TextView(context).apply {
            text = detail
            textSize = 12f
            setTextColor(dim)
            setPadding(0, dp(2), 0, 0)
        }
        textCol.addView(nameTv)
        textCol.addView(detailTv)
        row.addView(numBadge)
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun launchFavoritePlayer(item: FavoritesStore.FavoriteItem) {
        val uri = item.uri.trim()
        if (uri.isBlank()) {
            Toast.makeText(context, "播放地址为空，无法播放", Toast.LENGTH_SHORT).show()
            return
        }
        val title = item.title.ifBlank { uri }
        val source = item.source.ifBlank { "favorites" }
        lastPlayedItemIndex = (gridRecycler.adapter as? FavGridAdapter)?.items
            ?.indexOfFirst { it.itemId == item.itemId && it.uri == item.uri }
            ?: -1
        lastPlayedItemUri = item.uri
        try {
            PlaybackController.recordPlaybackHistory(uri, title, source, PlaybackController.currentArtworkUrl())
        } catch (_: Throwable) { /* 记录历史失败不影响播放启动 */ }
        val intent = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PlayerActivity.EXTRA_URI, uri)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_SOURCE, source)
            putExtra(PlayerActivity.EXTRA_RETURN_SKIP_PAGE_RELOAD, true)
            putExtra(com.bd.casttv.ui.framework.NewMainActivity.EXTRA_OPEN_PAGE_ID, pageId)
        }
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(context, "启动播放失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun focusSelectedSideTab(): Boolean {
        val target = sideItemViews.firstOrNull { it.first == selectedCollectionId }?.second
            ?: sideItemViews.firstOrNull()?.second
            ?: btnCollectionManage
        val ok = target.requestFocus()
        if (ok) onFocusEnterContent()
        return ok
    }

    private fun focusRightContentFromSide(): Boolean {
        return focusFirstVideoPlayButton()
            || rightHeaderPreferredForSelected()?.requestFocus() == true
    }

    private fun focusFirstVideoPlayButton(): Boolean {
        val adapterItems = (gridRecycler.adapter as? FavGridAdapter)?.items.orEmpty()
        if (adapterItems.isEmpty()) return false
        gridRecycler.scrollToPosition(0)
        gridRecycler.findViewHolderForAdapterPosition(0)
            ?.itemView
            ?.findViewWithTag<View>(TAG_FAV_GRID_PLAY_BUTTON)
            ?.let { if (it.requestFocus()) return true }
        gridRecycler.post {
            gridRecycler.findViewHolderForAdapterPosition(0)
                ?.itemView
                ?.findViewWithTag<View>(TAG_FAV_GRID_PLAY_BUTTON)
                ?.requestFocus()
        }
        return true
    }

    private fun visibleRightHeaderButtons(): List<View> = listOf(btnEdit, btnMultiSelect, btnRecommend)
        .filter { it.visibility == View.VISIBLE && it.isEnabled }

    private fun rightHeaderPreferredForSelected(): View? = btnRecommend
        .takeIf { it.visibility == View.VISIBLE && it.isEnabled }
        ?: visibleRightHeaderButtons().lastOrNull()

    private fun rightHeaderLeftmost(): View? = visibleRightHeaderButtons().firstOrNull()

    private fun linkRightHeaderFocusOrder() {
        visibleRightHeaderButtons().forEachIndexed { index, button ->
            button.id = if (button.id == View.NO_ID) View.generateViewId() else button.id
            button.nextFocusLeftId = visibleRightHeaderButtons().getOrNull(index - 1)?.id ?: button.id
            button.nextFocusRightId = visibleRightHeaderButtons().getOrNull(index + 1)?.id ?: button.id
            button.nextFocusDownId = gridRecycler.findViewHolderForAdapterPosition(0)
                ?.itemView
                ?.findViewWithTag<View>(TAG_FAV_GRID_PLAY_BUTTON)
                ?.id
                ?: button.id
        }
    }

    private fun refreshSideSelectionVisuals() {
        sideItemViews.forEach { (id, tv) ->
            val selected = id == selectedCollectionId
            tv.isSelected = selected
            updateSideButtonVisual(tv, selected, tv.isFocused)
        }
    }

    private fun restoreLastPlayedFocusIfNeeded() {
        // 优先用 uri 匹配（更稳，避免因排序变化导致 index 失效）
        val targetUri = lastPlayedItemUri
        val items = (gridRecycler.adapter as? FavGridAdapter)?.items.orEmpty()
        val targetIndex = when {
            targetUri.isNotBlank() -> items.indexOfFirst { it.uri == targetUri }
            else -> lastPlayedItemIndex
        }
        if (targetIndex < 0) {
            lastPlayedItemIndex = -1
            lastPlayedItemUri = ""
            return
        }
        gridRecycler.scrollToPosition(targetIndex)
        // 分多次尝试等待 ViewHolder 完成 bind（RecyclerView 布局是异步的）
        var attempts = 0
        val maxAttempts = 6
        val retry = object : Runnable {
            override fun run() {
                val holder = gridRecycler.findViewHolderForAdapterPosition(targetIndex)
                val playButton = holder?.itemView?.findViewWithTag<View>(TAG_FAV_GRID_PLAY_BUTTON)
                    ?: holder?.itemView?.findViewById(R.id.gridBtnPlay)
                if (playButton != null && playButton.requestFocus()) {
                    onFocusEnterContent()
                    lastPlayedItemIndex = -1
                    lastPlayedItemUri = ""
                    return
                }
                attempts++
                if (attempts < maxAttempts) {
                    gridRecycler.postDelayed(this, 100L)
                } else {
                    lastPlayedItemIndex = -1
                    lastPlayedItemUri = ""
                }
            }
        }
        gridRecycler.postDelayed(retry, 80L)
    }

    // ---------------- 焦点 / 方向键处理 ----------------

    private fun handleSideActionKey(v: View, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_UP, this)
            KeyEvent.KEYCODE_DPAD_LEFT -> BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_LEFT, this)
            KeyEvent.KEYCODE_DPAD_RIGHT -> focusRightContentFromSide()
            KeyEvent.KEYCODE_DPAD_DOWN -> sideItemViews.firstOrNull()?.second?.requestFocus()
                ?: BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_DOWN, this)
            else -> false
        }
    }

    private fun handleRightHeaderKey(v: View, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        val headerButtons = visibleRightHeaderButtons()
        val index = headerButtons.indexOf(v)
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val target = headerButtons.getOrNull(index - 1)
                when {
                    target != null -> target.requestFocus()
                    v === rightHeaderLeftmost() -> focusSelectedSideTab()
                    else -> false
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val target = headerButtons.getOrNull(index + 1)
                when {
                    target != null -> target.requestFocus()
                    index == headerButtons.lastIndex -> BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_RIGHT, this)
                    else -> false
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> focusFirstVideoPlayButton()
            KeyEvent.KEYCODE_DPAD_UP -> BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_UP, this)
            KeyEvent.KEYCODE_BACK -> focusSelectedSideTab()
            else -> false
        }
    }

    private fun handleSideKey(v: View, e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> focusAdjacentSideItem(v, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> focusAdjacentSideItem(v, 1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> focusRightContentFromSide()
            KeyEvent.KEYCODE_DPAD_LEFT -> BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_LEFT, this)
            KeyEvent.KEYCODE_MENU -> { collections.firstOrNull { it.id == selectedCollectionId }?.let { collectionMenu(it) }; true }
            else -> false
        }
    }

    private fun focusTopRowItem(current: View, offset: Int): Boolean {
        val focusViews = listOf(btnCollectionManage) + sideItemViews.map { it.second }
        val index = focusViews.indexOfFirst { it === current }
        val target = focusViews.getOrNull(index + offset)
        return if (target != null) {
            target.requestFocus()
            sideScroll.post {
                target.requestFocus()
                sideScroll.smoothScrollTo((target.left - dp(12)).coerceAtLeast(0), 0)
            }
            true
        } else {
            BoundaryFocusHandler.onContentBoundary(
                current,
                KeyEvent(KeyEvent.ACTION_DOWN, if (offset < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT),
                if (offset < 0) View.FOCUS_LEFT else View.FOCUS_RIGHT,
                this
            )
        }
    }

    private fun focusAdjacentSideItem(current: View, offset: Int): Boolean {
        val index = sideItemViews.indexOfFirst { it.second === current }
        val target = when {
            index == 0 && offset < 0 -> btnCollectionManage
            index >= 0 -> sideItemViews.getOrNull(index + offset)?.second
            else -> null
        }
        return if (target != null) {
            target.requestFocus()
            sideScroll.post {
                target.requestFocus()
                sideScroll.smoothScrollTo(0, (target.top - dp(12)).coerceAtLeast(0))
            }
            true
        } else {
            BoundaryFocusHandler.onContentBoundary(current, KeyEvent(KeyEvent.ACTION_DOWN, if (offset < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN), if (offset < 0) View.FOCUS_UP else View.FOCUS_DOWN, this)
        }
    }

    private fun handleCardKey(v: View, e: KeyEvent, cardIndex: Int): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return false
        return when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> enqueueVideoCardFocusMove(v, e.keyCode, cardIndex)
            KeyEvent.KEYCODE_BACK -> focusSelectedSideTab()
            else -> false
        }
    }

    private fun enqueueVideoCardFocusMove(v: View, keyCode: Int, cardIndex: Int): Boolean {
        val isFirstKeyInWindow = pendingVideoCardFocusKeyCode == 0
        pendingVideoCardFocusView = v
        pendingVideoCardFocusKeyCode = keyCode
        pendingVideoCardFocusIndex = cardIndex
        if (isFirstKeyInWindow) {
            postDelayed({ flushPendingVideoCardFocusMove() }, VIDEO_CARD_FOCUS_DELAY_MS)
        }
        return true
    }

    private fun flushPendingVideoCardFocusMove() {
        val targetView = pendingVideoCardFocusView
        val keyCode = pendingVideoCardFocusKeyCode
        val cardIndex = pendingVideoCardFocusIndex
        pendingVideoCardFocusView = null
        pendingVideoCardFocusKeyCode = 0
        pendingVideoCardFocusIndex = -1
        if (targetView == null || !targetView.isAttachedToWindow) return
        performVideoCardFocusMove(targetView, keyCode, cardIndex)
    }

    private fun performVideoCardFocusMove(v: View, keyCode: Int, cardIndex: Int): Boolean {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        val col = cardIndex % GRID_COLUMNS
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && cardIndex in 0 until GRID_COLUMNS && isFirstRowCardActionButton(v)) {
            return rightHeaderPreferredForSelected()?.requestFocus() == true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && col == 0 && v.focusSearch(View.FOCUS_LEFT)?.let { isInSameCard(v, it) } != true) {
            return (sideItemViews.firstOrNull { it.first == selectedCollectionId }?.second ?: sideItemViews.firstOrNull()?.second)?.requestFocus() ?: false
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            findSameCardDownTarget(v)?.let { return it.requestFocus() }
            if (cardIndex + GRID_COLUMNS >= ((gridRecycler.adapter as? FavGridAdapter)?.items?.size ?: 0)) {
                return BoundaryFocusHandler.onContentBoundary(v, KeyEvent(KeyEvent.ACTION_DOWN, keyCode), View.FOCUS_DOWN, this)
            }
        }
        return v.focusSearch(direction)?.requestFocus() ?: false
    }

    private fun findSameCardDownTarget(view: View): View? {
        val targetId = when (view.id) {
            R.id.gridBtnPlay -> R.id.gridBtnLater
            R.id.gridBtnEdit -> R.id.gridBtnLater
            R.id.gridBtnMove -> R.id.gridBtnDelete
            else -> return view.focusSearch(View.FOCUS_DOWN)?.takeIf { isInSameCard(view, it) }
        }
        val cardRoot = findCardRoot(view) ?: return null
        return cardRoot.findViewById<View>(targetId)?.takeIf { it.isFocusable && it.visibility == View.VISIBLE }
    }

    private fun findCardRoot(view: View): View? {
        var p: View? = view
        while (p != null) {
            if (p.id == R.id.favoriteGridItemRoot) return p
            p = p.parent as? View
        }
        return null
    }

    private fun isFirstRowCardActionButton(view: View): Boolean = when (view.id) {
        R.id.gridBtnPlay, R.id.gridBtnEdit, R.id.gridBtnMove -> true
        else -> false
    }

    private fun isInSameCard(a: View, b: View): Boolean {
        var p: View? = b
        while (p != null) { if (p === a.parent?.parent?.parent) return true; p = p.parent as? View }
        return false
    }

    private fun firstFocusableIn(view: View): View? {
        if (view.isFocusable && view.visibility == View.VISIBLE) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) firstFocusableIn(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun rightHeaderFirst(): View? = listOf(btnEdit, btnMultiSelect, btnRecommend).firstOrNull { it.isEnabled && it.visibility == View.VISIBLE }

    // ---------------- 操作 / 弹窗 ----------------

    private fun createCollection() = crayonNewCollectionDialog { name ->
        val (result, id) = store.createCollection(name.ifBlank { FavoritesStore.NEW_COLLECTION_NAME })
        if (result == FavoritesStore.OpResult.SUCCESS && !id.isNullOrBlank()) {
            selectedCollectionId = id; reload()
        } else toast("新建失败：$result")
    }

    /** 蜡笔小新配色的「新建合集」弹窗（暖黄描边深色面板 + 名称输入框 + 新建按钮）。 */
    private fun crayonNewCollectionDialog(onCreate: (String) -> Unit) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 26, 34))
                setStroke(dp(2), WARM)
                cornerRadius = dp(18).toFloat()
            }
        }
        val title = TextView(context).apply {
            text = "🎨 新建合集"
            textSize = 20f
            setTextColor(WARM)
            setPadding(0, 0, 0, dp(14))
        }
        val edit = EditText(context).apply {
            hint = "输入合集名称"
            setText(FavoritesStore.NEW_COLLECTION_NAME); setSelection(text.length)
            setTextColor(Color.WHITE); setHintTextColor(Color.argb(150, 255, 255, 255))
            background = GradientDrawable().apply { setColor(Color.argb(60, 20, 24, 32)); setStroke(dp(1), Color.argb(120, 255, 255, 255)); cornerRadius = dp(10).toFloat() }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val btnRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(20), 0, 0) }
        val btnCancel = mkTextButton("取消")
        val btnCreate = mkTextButton("新建")
        btnRow.addView(btnCancel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(12) })
        btnRow.addView(btnCreate, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(40)))
        panel.addView(title)
        panel.addView(edit, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(btnRow)
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnCreate.setOnClickListener { onCreate(edit.text.toString().trim()); dialog.dismiss() }
        dialog.show()
    }

    private fun renameSelectedCollection() {
        val c = collections.firstOrNull { it.id == selectedCollectionId } ?: return
        if (c.isPreset || c.isDefault) { toast("预置/默认合集不允许改名"); return }
        input(
            title = "修改合集名称",
            initial = c.name,
            dialogHeightDp = 360,
            inputWidthPercent = 0.5f
        ) { newName ->
            val r = store.renameCollection(c.id, newName)
            if (r == FavoritesStore.OpResult.SUCCESS) reload() else toast("改名失败：$r")
        }
    }

    private fun deleteSelectedCollection() {
        val c = collections.firstOrNull { it.id == selectedCollectionId } ?: return
        if (c.isPreset || c.isDefault) { toast("预置/默认合集不允许删除"); return }
        showCrayonConfirmDialog(
            title = "删除合集",
            message = "确定删除合集「${c.name}」？此操作不可撤销。",
            confirmText = "删除",
            widthDp = 380
        ) {
            val r = store.deleteCollection(c.id)
            if (r == FavoritesStore.OpResult.SUCCESS) reload() else toast("删除失败：$r")
        }
    }

    private fun toggleMultiSelect() {
        // 新框架下不再走"卡片本体切批量态"的方案，直接照旧版打开批量操作弹窗（5 列网格）。
        multiSelectMode = false
        selectedItemIds.clear()
        renderRight()
        showMultiSelectDialog()
    }

    private fun showMultiSelectDialog() {
        val cid = selectedCollectionId
        if (cid.isBlank()) { toast("请先选择合集"); return }
        BatchOperateDialog(
            context = context,
            store = store,
            collectionId = cid,
            onDone = { reload() }
        ).show()
    }

    /**
     * 打开「设为推荐」弹窗（[RecommendDialog]）：
     *  - 无合集/合集为空：不响应（按钮此时也已隐藏，双保险）；
     *  - private / shared 合集：都允许加载完整 items 后打开多选弹窗；
     *  - 推荐仅写入云端 `recommendations.json`，不修改云端合集列表数据。
     */
    private fun openRecommendDialog() {
        val info = collections.firstOrNull { it.id == selectedCollectionId } ?: run { toast("请先选择合集"); return }
        if (info.itemCount <= 0) { toast("当前合集暂无视频"); return }
        val full = store.collection(info.id) ?: run { toast("加载合集失败"); return }
        val sortedItems = sortedFavoriteItems(full.items)
        if (sortedItems.isEmpty()) { toast("当前合集暂无视频"); return }
        RecommendDialog(
            context = context,
            store = store,
            collection = full.copy(items = sortedItems),
            onDone = { /* 云端 recommendations.json 变化不影响本地合集，本地无需 reload */ }
        ).show()
    }

    private fun renameItem(item: FavoritesStore.FavoriteItem) = input("重命名视频", item.title) { newTitle ->
        val r = store.updateItemTitle(selectedCollectionId, item.uri, newTitle)
        if (r == FavoritesStore.OpResult.SUCCESS) reload() else toast("改名失败：$r")
    }

    private fun moveItem(item: FavoritesStore.FavoriteItem) {
        val targets = collections.filter { it.id != selectedCollectionId }
        if (targets.isEmpty()) { toast("没有可移动到的合集"); return }
        showCrayonListDialog(
            title = "移动到",
            items = targets.map { it.name },
            widthDp = 480
        ) { w ->
            val r = store.moveItem(selectedCollectionId, item.uri, targets[w].id)
            if (r == FavoritesStore.OpResult.SUCCESS) reload() else toast("移动失败：$r")
        }
    }

    private fun deleteItem(item: FavoritesStore.FavoriteItem) {
        showCrayonConfirmDialog(
            title = "删除视频",
            message = "确定删除「${item.title.ifBlank { item.uri }}」？",
            confirmText = "删除",
            widthDp = 380
        ) {
            val r = store.removeItem(selectedCollectionId, item.uri)
            if (r == FavoritesStore.OpResult.SUCCESS) reload() else toast("删除失败：$r")
        }
    }

    private fun collectionMenu(c: FavoritesStore.CollectionInfo) {
        val actions = mutableListOf<String>()
        if (!c.isPreset && !c.isDefault) { actions.add("重命名"); actions.add("删除") }
        if (store.canModifyCollectionType(c.id)) actions.add("Shared/Private 切换")
        actions.add("置顶排序")
        showCrayonListDialog(
            title = c.name,
            items = actions,
            widthDp = 400
        ) { w ->
            when (actions[w]) {
                "重命名" -> input("重命名合集", c.name) { n -> store.renameCollection(c.id, n); reload() }
                "删除" -> { store.deleteCollection(c.id); reload() }
                "Shared/Private 切换" -> {
                    val result = store.updateCollectionType(c.id, if (c.type == FavoritesStore.TYPE_SHARED) FavoritesStore.TYPE_PRIVATE else FavoritesStore.TYPE_SHARED)
                    if (result == FavoritesStore.OpResult.SUCCESS) reload() else toast("仅管理员或合集创建者可修改合集类型")
                }
                "置顶排序" -> { store.reorderCollections(listOf(c.id) + collections.map { it.id }.filter { it != c.id }); reload() }
            }
        }
    }

    private fun importLive() {
        ImportLiveSourceDialog(context, store, selectedCollectionId, onImported = { reload() }).show()
    }
    private fun transferExport() {
        FavTransferDialog(context, store, FavoriteTransferServer.Mode.EXPORT, onImported = { reload() }).show()
    }
    private fun transferImport() {
        FavTransferDialog(context, store, FavoriteTransferServer.Mode.IMPORT, onImported = { reload() }).show()
    }

    /**
     * 打开新框架下的独立「合集管理」页面（v1.1.x 从 [showCollectionManageMenu] 弹窗迁移）：
     *  - 通过 [com.bd.casttv.ui.framework.NewMainActivity.pushOverlayPage] 挂到 Activity 根 FrameLayout；
     *  - 返回/保存后 [reload] 收藏页数据，并 post 到主线程把焦点还给 btnCollectionManage。
     */
    private fun openCollectionManagePage() {
        val activity = context as? com.bd.casttv.ui.framework.NewMainActivity ?: run {
            // 兜底：非 NewMainActivity 环境（demo / 单元测试）时仍走原弹窗逻辑，保持向下兼容。
            showCollectionManageMenu()
            return
        }
        val page = CollectionManagePage(context, store) { _ ->
            reload()
            btnCollectionManage.post { btnCollectionManage.requestFocus() }
        }
        activity.pushOverlayPage(page)
    }

    private fun showCollectionManageMenu() {
        val originalNormalCollections = store.collectionsInfo().filter { !it.isDefault && !it.isPreset }
        val visibleItems = originalNormalCollections
            .filter { it.type == FavoritesStore.TYPE_SHARED }
            .toMutableList()
        val selected = linkedSetOf<String>()
        val pendingDeleteIds = linkedSetOf<String>()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = context.getDrawable(R.drawable.bg_dialog_crayon_panel)
            clipChildren = false
            clipToPadding = false
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(12))
        }
        val title = TextView(context).apply {
            text = "合集管理"
            textSize = 22f
            setTextColor(WARM)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val selectedCount = TextView(context).apply {
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setTextColor(WARM)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(selectedCount, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        panel.addView(titleRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val functionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        panel.addView(functionRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val listHolder = FrameLayout(context).apply {
            background = crayonPanelBg(alpha = 120, radiusDp = 16, strokeColor = Color.argb(70, 255, 215, 0))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val listView = ListView(context).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            divider = null
            cacheColorHint = Color.TRANSPARENT
            selector = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(70, 245, 196, 81))
                setStroke(dp(2), WARM)
            }
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            itemsCanFocus = false
        }
        val emptyView = TextView(context).apply {
            text = "暂无共享合集"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(170, 238, 232, 218))
        }
        listHolder.addView(listView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        listHolder.addView(emptyView, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentRow.addView(listHolder, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        val moveColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, 0, 0)
        }
        val btnMoveUp = mkCollectionMoveButton("↑上移")
        val btnMoveDown = mkCollectionMoveButton("↓下移")
        moveColumn.addView(btnMoveUp, LinearLayout.LayoutParams(dp(104), dp(46)))
        moveColumn.addView(btnMoveDown, LinearLayout.LayoutParams(dp(104), dp(46)).apply { topMargin = dp(12) })
        contentRow.addView(moveColumn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        panel.addView(contentRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        val btnSelectAll = mkCollectionManageDialogButton("全选")
        val btnClear = mkCollectionManageDialogButton("取消勾选")
        val btnDelete = mkCollectionManageDialogButton("删除")
        val btnCancel = mkCollectionManageDialogButton("取消")
        val btnConfirm = mkCollectionManageDialogButton("确认")
        listOf(btnSelectAll, btnClear, btnDelete, btnCancel, btnConfirm).forEachIndexed { index, button ->
            bottomRow.addView(button, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(42)).apply {
                if (index > 0) marginStart = dp(10)
            })
        }
        panel.addView(bottomRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        val adapter = CollectionManageSharedAdapter(visibleItems, selected)
        listView.adapter = adapter

        fun syncListCheckedStates() {
            for (i in 0 until listView.count) {
                val item = visibleItems.getOrNull(i)
                listView.setItemChecked(i, item != null && item.id in selected)
            }
            adapter.notifyDataSetChanged()
        }

        fun selectedIndices(): List<Int> = visibleItems.mapIndexedNotNull { index, info -> if (info.id in selected) index else null }
        fun isContinuousSelection(): Boolean {
            val indices = selectedIndices()
            if (indices.isEmpty()) return false
            return indices.last() - indices.first() + 1 == indices.size
        }
        fun canMoveUp(): Boolean = selected.isNotEmpty() && isContinuousSelection() && selectedIndices().first() > 0
        fun canMoveDown(): Boolean = selected.isNotEmpty() && isContinuousSelection() && selectedIndices().last() < visibleItems.lastIndex
        fun setButtonEnabled(button: TextView, enabled: Boolean, isMoveButton: Boolean = false) {
            button.isEnabled = enabled
            button.isFocusable = enabled
            button.alpha = if (enabled) 1f else 0.38f
            if (isMoveButton) {
                button.isSelected = enabled
                button.refreshDrawableState()
            } else {
                refreshCollectionManageButton(button, focused = button.isFocused && enabled)
            }
        }
        fun refreshState() {
            selectedCount.text = "已选 ${selected.size} 个合集 →"
            emptyView.visibility = if (visibleItems.isEmpty()) View.VISIBLE else View.GONE
            listView.visibility = if (visibleItems.isEmpty()) View.GONE else View.VISIBLE
            setButtonEnabled(btnMoveUp, canMoveUp(), isMoveButton = true)
            setButtonEnabled(btnMoveDown, canMoveDown(), isMoveButton = true)
            setButtonEnabled(btnClear, selected.isNotEmpty())
            setButtonEnabled(btnDelete, selected.isNotEmpty())
            setButtonEnabled(btnSelectAll, visibleItems.isNotEmpty() && selected.size < visibleItems.size)
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val id = visibleItems.getOrNull(position)?.id ?: return@setOnItemClickListener
            if (listView.isItemChecked(position)) selected.add(id) else selected.remove(id)
            syncListCheckedStates()
            refreshState()
        }

        fun moveSelected(direction: Int) {
            if (!isContinuousSelection()) {
                Toast.makeText(context, "请勾选连续合集后再移动", Toast.LENGTH_SHORT).show()
                return
            }
            if (direction < 0 && !canMoveUp()) { btnMoveDown.requestFocus(); return }
            if (direction > 0 && !canMoveDown()) { btnMoveUp.requestFocus(); return }
            val indices = selectedIndices()
            val first = indices.first()
            val last = indices.last()
            if (direction < 0) {
                val above = visibleItems.removeAt(first - 1)
                visibleItems.add(last, above)
            } else {
                val below = visibleItems.removeAt(last + 1)
                visibleItems.add(first, below)
            }
            syncListCheckedStates()
            refreshState()
            if (direction < 0 && !canMoveUp()) btnMoveDown.requestFocus()
            if (direction > 0 && !canMoveDown()) btnMoveUp.requestFocus()
        }

        fun mergedOrderAfterPendingChanges(): List<String> {
            val remainingShared = visibleItems.map { it.id }.toMutableList()
            val merged = mutableListOf<String>()
            for (info in originalNormalCollections) {
                if (info.id in pendingDeleteIds) continue
                if (info.type == FavoritesStore.TYPE_SHARED) {
                    if (remainingShared.isNotEmpty()) merged.add(remainingShared.removeAt(0))
                } else {
                    merged.add(info.id)
                }
            }
            merged.addAll(remainingShared)
            return merged
        }

        val functionActions = listOf(
            "新建合集" to { createCollection() },
            "导入直播源" to { importLive() },
            "云同步" to { CloudSyncDialog(context, store) { reload() }.show() },
            "导入" to { transferImport() },
            "导出" to { transferExport() }
        )
        functionActions.forEachIndexed { index, (label, action) ->
            val button = mkCollectionManageDialogButton(label).apply {
                setOnClickListener { dialog.dismiss(); action.invoke() }
                setOnKeyListener { v, _, e ->
                    if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_BACK) {
                        dialog.dismiss(); true
                    } else if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_UP, this@FavoritesPage)
                    } else false
                }
            }
            functionRow.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (index > 0) marginStart = dp(8)
            })
        }

        btnMoveUp.setOnClickListener { moveSelected(-1) }
        btnMoveDown.setOnClickListener { moveSelected(1) }
        btnSelectAll.setOnClickListener {
            selected.clear()
            visibleItems.forEach { selected.add(it.id) }
            syncListCheckedStates()
            refreshState()
        }
        btnClear.setOnClickListener {
            selected.clear()
            syncListCheckedStates()
            refreshState()
        }
        btnDelete.setOnClickListener {
            if (selected.isEmpty()) return@setOnClickListener
            pendingDeleteIds.addAll(selected)
            visibleItems.removeAll { it.id in selected }
            selected.clear()
            syncListCheckedStates()
            refreshState()
            Toast.makeText(context, "已加入待删除，点击确认后生效", Toast.LENGTH_SHORT).show()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val deletedCount = if (pendingDeleteIds.isNotEmpty()) store.deleteCollections(pendingDeleteIds) else 0
            val orderResult = store.reorderCollections(mergedOrderAfterPendingChanges())
            if (deletedCount > 0) Toast.makeText(context, "已删除 ${deletedCount} 个合集", Toast.LENGTH_SHORT).show()
            if (orderResult != FavoritesStore.OpResult.SUCCESS && orderResult != FavoritesStore.OpResult.NOT_FOUND) {
                Toast.makeText(context, "排序保存失败：$orderResult", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
            reload()
        }
        listOf(btnSelectAll, btnClear, btnDelete, btnCancel, btnConfirm, btnMoveUp, btnMoveDown).forEach { button ->
            button.setOnKeyListener { _, _, e ->
                if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_BACK) { dialog.dismiss(); true } else false
            }
        }

        dialog.setOnShowListener {
            val metrics = resources.displayMetrics
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout((metrics.widthPixels * 0.78f).toInt(), (metrics.heightPixels * 0.8f).toInt())
            panel.layoutParams = panel.layoutParams?.apply {
                width = LayoutParams.MATCH_PARENT
                height = LayoutParams.MATCH_PARENT
            }
            (functionRow.getChildAt(0) ?: btnCancel).requestFocus()
        }
        refreshState()
        syncListCheckedStates()
        dialog.show()
    }

    private fun mkCollectionManageDialogButton(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        minWidth = dp(78)
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(Color.WHITE)
        isFocusable = true
        isFocusableInTouchMode = false
        refreshCollectionManageButton(this, focused = false)
        setOnFocusChangeListener { v, has -> refreshCollectionManageButton(v as TextView, has) }
    }

    private fun mkCollectionMoveButton(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        minWidth = dp(78)
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_selected),
                intArrayOf()
            ),
            intArrayOf(Color.argb(120, 255, 255, 255), WARM, Color.WHITE)
        ))
        setBackgroundResource(R.drawable.bg_collection_move_button_selector)
        isFocusable = true
        isFocusableInTouchMode = false
    }

    private fun refreshCollectionManageButton(button: TextView, focused: Boolean) {
        val active = button.isEnabled && focused
        button.setTextColor(if (active) WARM else Color.WHITE)
        button.paint.isFakeBoldText = active
        button.background = GradientDrawable().apply {
            cornerRadius = dp(60).toFloat()
            setColor(if (active) Color.argb(78, 245, 196, 81) else BUTTON_DEFAULT_BG)
            setStroke(dp(2), if (active) WARM else SILVER)
        }
        // 全局焦点 fx（A：scale + translationZ 发光；不叠加暖黄前景）。
        FocusFxHelper.applyFocusFxState(button, active, cornerRadiusDp = 60)
        button.invalidate()
    }

    private inner class CollectionManageSharedAdapter(
        private val data: MutableList<FavoritesStore.CollectionInfo>,
        private val selected: MutableSet<String>
    ) : BaseAdapter() {
        override fun getCount(): Int = data.size
        override fun getItem(position: Int): FavoritesStore.CollectionInfo = data[position]
        override fun getItemId(position: Int): Long = data[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: VH
            val row: LinearLayout
            if (convertView is LinearLayout && convertView.tag is VH) {
                row = convertView
                holder = convertView.tag as VH
            } else {
                row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    clipChildren = false
                    clipToPadding = false
                }
                val check = CheckBox(parent.context).apply {
                    isFocusable = false
                    isClickable = false
                    buttonTintList = ColorStateList.valueOf(WARM)
                }
                val name = TextView(parent.context).apply {
                    textSize = 16f
                    setTextColor(Color.argb(238, 238, 232, 218))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                }
                val count = TextView(parent.context).apply {
                    textSize = 14f
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    setTextColor(Color.argb(210, 238, 232, 218))
                }
                row.addView(check, LinearLayout.LayoutParams(dp(42), LayoutParams.MATCH_PARENT))
                row.addView(name, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                row.addView(count, LinearLayout.LayoutParams(dp(112), LayoutParams.MATCH_PARENT))
                holder = VH(check, name, count)
                row.tag = holder
            }
            val item = getItem(position)
            val checked = item.id in selected
            holder.check.isChecked = checked
            holder.name.text = item.name
            holder.count.text = "${item.itemCount}个视频"
            refreshRow(row, checked)
            return row
        }

        private fun refreshRow(row: View, checked: Boolean) {
            row.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (checked) Color.argb(76, 245, 196, 81) else Color.argb(70, 28, 31, 40))
                setStroke(dp(2), if (checked) Color.argb(190, 245, 196, 81) else Color.TRANSPARENT)
            }
        }

        private inner class VH(
            val check: CheckBox,
            val name: TextView,
            val count: TextView
        )
    }

    private fun input(
        title: String,
        initial: String,
        dialogHeightDp: Int? = null,
        inputWidthPercent: Float = 1f,
        ok: (String) -> Unit
    ) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = crayonDialogPanelBg()
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val content = crayonDialogContent()
        val titleView = crayonDialogTitle(title)
        val edit = EditText(context).apply {
            setText(initial)
            setSelection(text.length)
            textSize = 17f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(190, 255, 255, 255))
            background = crayonDialogInputBg(focused = false)
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnFocusChangeListener { _, hasFocus ->
                background = crayonDialogInputBg(focused = hasFocus)
            }
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancel = crayonDialogButton("取消")
        val confirm = crayonDialogButton("确定")
        buttonRow.addView(cancel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        buttonRow.addView(confirm, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) })

        content.addView(titleView)
        content.addView(
            edit,
            LinearLayout.LayoutParams(
                if (inputWidthPercent >= 1f) LayoutParams.MATCH_PARENT else dp((560 * inputWidthPercent).toInt()),
                LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        content.addView(buttonRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
        panel.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener { ok(edit.text.toString().trim()); dialog.dismiss() }
        bindDialogBoundaryFocus(panel, listOf(edit, cancel, confirm))
        applyCrayonDialogWindow(dialog, widthDp = 480, heightDp = dialogHeightDp)
        dialog.show()
        edit.requestFocus()
    }

    private fun bindDialogBoundaryFocus(boundaryRoot: ViewGroup, focusables: List<View>) {
        val listener = View.OnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> return@OnKeyListener false
            }
            val next = view.focusSearch(direction)
            val hasNextInDialog = next != null &&
                next !== view &&
                next.visibility == View.VISIBLE &&
                next.isEnabled &&
                next.isFocusable &&
                isDescendantOf(next, boundaryRoot)
            if (hasNextInDialog) return@OnKeyListener false
            BoundaryFocusHandler.shake(view)
            true
        }
        focusables.forEach { it.setOnKeyListener(listener) }
    }

    private fun isDescendantOf(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun showCrayonConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        widthDp: Int,
        onConfirm: () -> Unit
    ) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = crayonDialogPanelBg()
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val content = crayonDialogContent()
        val messageView = TextView(context).apply {
            text = message
            textSize = 15f
            setTextColor(Color.argb(238, 255, 255, 255))
            gravity = Gravity.START
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancel = crayonDialogButton("取消")
        val confirm = crayonDialogButton(confirmText)
        buttonRow.addView(cancel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        buttonRow.addView(confirm, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(14) })

        content.addView(crayonDialogTitle(title))
        content.addView(messageView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        content.addView(buttonRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })
        panel.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        cancel.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener { onConfirm(); dialog.dismiss() }
        bindDialogBoundaryFocus(panel, listOf(cancel, confirm))
        applyCrayonDialogWindow(dialog, widthDp = widthDp)
        dialog.show()
        cancel.requestFocus()
    }

    private fun showCrayonListDialog(title: String, items: List<String>, widthDp: Int, onSelect: (Int) -> Unit) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = crayonDialogPanelBg()
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val content = crayonDialogContent()
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val focusRows = mutableListOf<View>()
        lateinit var dialog: AlertDialog
        items.forEachIndexed { index, label ->
            val row = TextView(context).apply {
                text = label
                textSize = 16f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                background = crayonDialogListItemBg(focused = false)
                setPadding(dp(16), 0, dp(16), 0)
                setOnFocusChangeListener { _, hasFocus ->
                    background = crayonDialogListItemBg(focused = hasFocus)
                }
                setOnClickListener {
                    onSelect(index)
                    dialog.dismiss()
                }
            }
            focusRows.add(row)
            list.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
                if (index > 0) topMargin = dp(10)
            })
        }
        val screenHeight = context.resources.displayMetrics.heightPixels
        val maxListHeight = (screenHeight * 0.6f).toInt()
        val scrollView = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val constrainedHeight = MeasureSpec.makeMeasureSpec(maxListHeight, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, constrainedHeight)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            isFocusable = false
            isFocusableInTouchMode = false
            addView(list, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        content.addView(crayonDialogTitle(title))
        content.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        panel.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        bindDialogBoundaryFocus(panel, focusRows)
        applyCrayonDialogWindow(dialog, widthDp = widthDp)
        dialog.show()
        list.getChildAt(0)?.requestFocus()
    }

    private fun applyCrayonDialogWindow(dialog: AlertDialog, widthDp: Int, heightDp: Int? = null) {
        dialog.window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setBackgroundColor(Color.TRANSPARENT)
            setLayout(dp(widthDp), heightDp?.let { dp(it) } ?: LayoutParams.WRAP_CONTENT)
        }
    }

    private fun crayonDialogPanelBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.parseColor("#4169E1"))
    }

    private fun crayonDialogInputBg(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (focused) Color.TRANSPARENT else CARD_BG)
        setStroke(
            dp(if (focused) 3 else 1),
            if (focused) WARM else Color.argb(95, 255, 255, 255)
        )
    }

    private fun crayonDialogListItemBg(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(if (focused) Color.TRANSPARENT else CARD_BG)
        setStroke(
            dp(if (focused) 3 else 1),
            if (focused) WARM else Color.TRANSPARENT
        )
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
        setPadding(0, 0, 0, 0)
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
            setTextColor(WARM)
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun crayonDialogButton(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        minWidth = dp(92)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        fun refresh(focused: Boolean) {
            val selected = isSelected
            setTextColor(if (selected) WARM else Color.argb(235, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(if (focused) 3 else 1), if (focused) WARM else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, hasFocus ->
            refresh(hasFocus)
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 10)
        }
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()

    // ---------------- 按钮 / 背景样式 ----------------

    /**
     * 侧边栏无边框文字按钮：
     *  默认：浅灰字体，无边框无背景
     *  选中：暖黄字体
     *  焦点：底部暖黄下划线 + 向上发光
     *  选中+焦点：字体暖黄 + 底部下划线发光
     */
    private fun mkSideButton(text: String): FrameLayout {
        val fl = FrameLayout(context).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            background = null
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, 0)
        }
        val glow = GlowUnderlineView(context).apply {
            id = R.id.sideActionGlowUnderline
            isFocusable = false
            isClickable = false
            applyVisualState(selected = false, active = false)
        }
        val tv = TextView(context).apply {
            id = R.id.sideActionText
            this.text = text
            textSize = 14f
            // Tab 名称垂直居中：避免仅设置 bottom padding 导致视觉偏移
            gravity = Gravity.CENTER
            includeFontPadding = false
            // 缩小文字与下划线间距：减少上下 padding（目标 ≤ 2~4dp）
            setPadding(dp(14), dp(1), dp(14), dp(1))
            isDuplicateParentStateEnabled = true
            setTextColor(Color.WHITE)
        }
        fl.addView(glow, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fl.addView(tv, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        updateSideButtonVisual(fl, selected = false, focused = false)
        fl.setOnFocusChangeListener { v, has -> updateSideButtonVisual(v, v.isSelected, has) }
        return fl
    }

    /**
     * 侧边栏纯图标按钮（齿轮）：无边框，焦点时底部暖黄下划线发光。
     */
    private fun mkSideIconButton(iconRes: Int): FrameLayout {
        val fl = FrameLayout(context).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            background = null
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, 0)
        }
        val glow = GlowUnderlineView(context).apply {
            id = R.id.sideActionGlowUnderline
            isFocusable = false
            isClickable = false
            applyVisualState(selected = false, active = false)
        }
        val iv = ImageView(context).apply {
            id = R.id.sideActionIcon
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(LIGHT_ICON_GRAY)
            isDuplicateParentStateEnabled = true
        }
        fl.addView(glow, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fl.addView(iv, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        updateSideButtonVisual(fl, selected = false, focused = false)
        fl.setOnFocusChangeListener { v, has -> updateSideButtonVisual(v, v.isSelected, has) }
        return fl
    }

    /**
     * 收藏页顶部：合集管理入口（齿轮 + 文案 + 分割线）。
     *  - 与合集 Tab 同行展示
     *  - 文案右侧增加竖向分割线，和右侧 Tab 列表隔开
     */
    private fun mkSideManageButton(): FrameLayout {
        val fl = FrameLayout(context).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            background = null
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, 0)
        }

        val glow = GlowUnderlineView(context).apply {
            id = R.id.sideActionGlowUnderline
            isFocusable = false
            isClickable = false
            applyVisualState(selected = false, active = false)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(10), 0, dp(10), 0)
        }

        val iv = ImageView(context).apply {
            id = R.id.sideActionIcon
            setImageResource(R.drawable.ic_gear_manage)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(LIGHT_ICON_GRAY)
            isDuplicateParentStateEnabled = true
        }

        val label = TextView(context).apply {
            id = R.id.sideActionText
            text = "合集管理"
            textSize = 14f
            setTextColor(Color.WHITE)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            isDuplicateParentStateEnabled = true
        }

        content.addView(iv, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) })
        content.addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        fl.addView(glow, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        fl.addView(content, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))

        updateSideButtonVisual(fl, selected = false, focused = false)
        fl.setOnFocusChangeListener { v, has -> updateSideButtonVisual(v, v.isSelected, has) }
        return fl
    }

    private fun updateSideButtonVisual(view: View, selected: Boolean, focused: Boolean) {
        view.isSelected = selected
        val color = if (selected) WARM else Color.WHITE
        val iconColor = if (selected) WARM else LIGHT_ICON_GRAY
        view.findViewById<TextView>(R.id.sideActionText)?.apply {
            setTextColor(color)
            paint.isFakeBoldText = selected
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            invalidate()
        }
        view.findViewById<ImageView>(R.id.sideActionIcon)?.setColorFilter(iconColor)
        view.findViewById<GlowUnderlineView>(R.id.sideActionGlowUnderline)?.applyVisualState(
            selected = false,
            active = focused
        )
    }

    /** 右侧头部操作按钮（AGENT.md 通用样式）：默认浅灰字+浅灰边框，选中暖黄字，焦点暖黄边框。 */
    private fun mkTextButton(text: String): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.textSize = 15f
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(16), 0, dp(16), 0)
        tv.isFocusable = true
        tv.isFocusableInTouchMode = false
        refreshTextButtonVisual(tv, selected = false, focused = false)
        tv.setOnFocusChangeListener { _, has -> refreshTextButtonVisual(tv, tv.isSelected, has) }
        return tv
    }

    private fun refreshTextButtonVisual(button: TextView, selected: Boolean, focused: Boolean) {
        button.isSelected = selected
        button.background = textButtonBg(selected, focused)
        button.setTextColor(if (selected) WARM else Color.WHITE)
        // 全局焦点 fx（A：scale + translationZ 发光；不叠加暖黄前景）。
        FocusFxHelper.applyFocusFxState(button, focused, cornerRadiusDp = 60)
    }

    /** 弹窗按钮统一样式：默认浅灰文字+浅灰边框+深灰半透明背景，焦点态仅边框暖黄加粗。 */
    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = false
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

    /** 纯图标按钮（AGENT.md 通用样式）：默认浅灰图标+浅灰整圆边框，选中/焦点态遵循规则。 */
    private fun mkIconButton(iconRes: Int): FrameLayout {
        val fl = FrameLayout(context)
        fl.isFocusable = true
        fl.isFocusableInTouchMode = false
        fl.clipChildren = false
        fl.clipToPadding = false
        val iv = ImageView(context).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        fl.minimumWidth = dp(40)
        fl.minimumHeight = dp(40)
        fl.addView(iv, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        refreshIconButtonVisual(fl, selected = false, focused = false)
        fl.setOnFocusChangeListener { _, has -> refreshIconButtonVisual(fl, fl.isSelected, has) }
        return fl
    }

    private fun refreshIconButtonVisual(button: FrameLayout, selected: Boolean, focused: Boolean) {
        button.isSelected = selected
        button.background = iconButtonBg(selected, focused)
        button.getChildAt(0)?.let { child ->
            if (child is ImageView) child.setColorFilter(if (selected) WARM else LIGHT_ICON_GRAY)
        }
        // 图标按钮为整圆，圆角给一个够大的半径以覆盖对角线，前景视觉即为整圆。
        FocusFxHelper.applyFocusFxState(button, focused, cornerRadiusDp = 40)
    }

    private fun textButtonBg(@Suppress("UNUSED_PARAMETER") selected: Boolean, focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(60).toFloat()
        setColor(BUTTON_DEFAULT_BG)
        setStroke(dp(if (focused) 3 else 2), if (focused) WARM else SILVER)
    }

    private fun iconButtonBg(@Suppress("UNUSED_PARAMETER") selected: Boolean, focused: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(BUTTON_DEFAULT_BG)
        setStroke(dp(if (focused) 3 else 2), if (focused) WARM else SILVER)
    }

    private fun cardBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(CARD_BG)
        setStroke(dp(1), Color.argb(45, 255, 255, 255))
    }

    private fun crayonPanelBg(alpha: Int, radiusDp: Int, strokeColor: Int = Color.TRANSPARENT): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(Color.argb(alpha.coerceIn(0, 255), 34, 34, 58))
        if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
    }

    private fun cardFocusBorder(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), WARM_SOFT)
    }

    /** 标题下划线：默认浅灰细线；获得根焦点时呈现蜡笔小新暖黄→红→蓝渐变。 */
    private fun underlineDrawable(focused: Boolean): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        if (focused) intArrayOf(
            Color.rgb(245, 196, 81),   // 暖黄
            Color.rgb(237, 106, 90),   // 红
            Color.rgb(64, 137, 253),   // 宝蓝
            Color.rgb(245, 196, 81)
        ) else intArrayOf(Color.argb(120, 200, 200, 200), Color.argb(120, 200, 200, 200))
    ).apply { cornerRadius = dp(1).toFloat() }

    // ---------------- 瀑布流 RecyclerView Adapter ----------------

    private inner class GridSpacingDecoration(
        private val spacing: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val halfSpacing = spacing / 2
            outRect.set(halfSpacing, halfSpacing, halfSpacing, halfSpacing)
        }
    }

    private inner class FavGridAdapter(
        initialItems: List<FavoritesStore.FavoriteItem>
    ) : RecyclerView.Adapter<FavGridAdapter.VH>() {

        /**
         * P0-3.3：Adapter 数据源从 immutable val 改为 mutable，支持通过 [submitList] 增量更新。
         * 保留 `items` 名字与只读 API，兼容既有调用点。
         */
        var items: List<FavoritesStore.FavoriteItem> = initialItems
            private set

        fun submitList(newItems: List<FavoritesStore.FavoriteItem>) {
            val old = items
            if (old === newItems || old == newItems) return
            val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object :
                androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = old[oldPos]
                    val b = newItems[newPos]
                    // 使用稳定 id 判断是否同一 item（itemId 缺失时退化为 uri）。
                    val aKey = a.itemId.ifBlank { a.uri }
                    val bKey = b.itemId.ifBlank { b.uri }
                    return aKey == bKey
                }
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = old[oldPos]
                    val b = newItems[newPos]
                    return a.title == b.title
                        && a.uri == b.uri
                        && a.source == b.source
                        && a.thumbPath == b.thumbPath
                        && a.artworkPath == b.artworkPath
                }
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        inner class VH(val root: ViewGroup) : RecyclerView.ViewHolder(root) {
            val thumb: ImageView = root.findViewById(R.id.gridThumb)
            val title: TextView = root.findViewById(R.id.gridTitle)
            val selectBox: CheckBox = root.findViewById(R.id.gridSelectBox)
            val btnPlay: TextView = root.findViewById(R.id.gridBtnPlay)
            val btnEdit: TextView = root.findViewById(R.id.gridBtnEdit)
            val btnMove: TextView = root.findViewById(R.id.gridBtnMove)
            val btnDelete: TextView = root.findViewById(R.id.gridBtnDelete)
            val btnLater: TextView = root.findViewById(R.id.gridBtnLater)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LayoutInflater.from(context).inflate(R.layout.item_favorite_grid, parent, false) as ViewGroup
            root.background = cardBg()
            return VH(root)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val itemKey = item.itemId.ifBlank { item.uri }
            holder.btnPlay.text = "播放"
            holder.btnPlay.tag = TAG_FAV_GRID_PLAY_BUTTON
            holder.btnEdit.text = "改名"
            holder.btnMove.text = "移动"
            val queueStore = PlayQueueStore.get(context)
            fun refreshLaterButtonText() {
                holder.btnLater.text = if (queueStore.findByUri(item.uri) != null) "移出队列" else "稍后播放"
            }
            refreshLaterButtonText()
            holder.btnDelete.text = "删除"
            holder.title.text = item.title.ifBlank { item.uri }
            Thumbnails.load(holder.thumb, item.displayThumbPath())

            holder.selectBox.visibility = if (multiSelectMode) View.VISIBLE else View.GONE
            holder.selectBox.isChecked = selectedItemIds.contains(itemKey)

            val onPlayLike = View.OnClickListener {
                if (multiSelectMode) {
                    if (!selectedItemIds.add(itemKey)) selectedItemIds.remove(itemKey)
                    holder.selectBox.isChecked = selectedItemIds.contains(itemKey)
                } else {
                    launchFavoritePlayer(item)
                }
            }
            holder.btnPlay.setOnClickListener(onPlayLike)
            holder.thumb.apply {
                isClickable = true
                isFocusable = false
                setOnClickListener(onPlayLike)
            }
            holder.btnEdit.setOnClickListener { renameItem(item) }
            holder.btnMove.setOnClickListener { moveItem(item) }
            holder.btnDelete.setOnClickListener { deleteItem(item) }
            holder.btnLater.setOnClickListener {
                val wasQueued = queueStore.findByUri(item.uri) != null
                val ok = if (wasQueued) {
                    queueStore.removeByUri(item.uri)
                } else {
                    queueStore.add(item.title.ifBlank { item.uri }, item.uri, item.source).isNotBlank()
                }
                refreshLaterButtonText()
                Toast.makeText(
                    context,
                    when {
                        wasQueued && ok -> "已移出队列"
                        wasQueued -> "移出队列失败"
                        ok -> "已加入稍后播放"
                        else -> "稍后播放添加失败"
                    },
                    Toast.LENGTH_SHORT
                ).show()
            }

            // 卡片内按钮焦点 → 卡片轻微放大 + 浅黄边框
            val cardButtons = listOf(holder.btnPlay, holder.btnEdit, holder.btnMove, holder.btnDelete, holder.btnLater)
            fun refreshCardFocus() {
                val any = cardButtons.any { it.isFocused && it.isAttachedToWindow }
                try {
                    holder.root.animate().cancel()
                    if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION && holder.root.isAttachedToWindow) {
                        holder.root.animate()
                            .scaleX(if (any) 1.03f else 1f)
                            .scaleY(if (any) 1.03f else 1f)
                            .setDuration(80)
                            .start()
                    } else {
                        holder.root.scaleX = 1f
                        holder.root.scaleY = 1f
                    }
                    holder.root.foreground = if (any) cardFocusBorder() else null
                } catch (_: Throwable) {
                    holder.root.scaleX = 1f
                    holder.root.scaleY = 1f
                    holder.root.foreground = null
                }
                cardButtons.forEach { button ->
                    button.setTextColor(when {
                        button === holder.btnDelete -> DANGER_RED
                        button === holder.btnLater -> WARM
                        button.isFocused || button.isSelected -> WARM
                        button === holder.btnPlay || button === holder.btnEdit || button === holder.btnMove -> Color.WHITE
                        else -> SOFT_GRAY
                    })
                }
            }
            cardButtons.forEach { button ->
                button.setTextColor(when (button) {
                    holder.btnDelete -> DANGER_RED
                    holder.btnLater -> WARM
                    holder.btnPlay, holder.btnEdit, holder.btnMove -> Color.WHITE
                    else -> SOFT_GRAY
                })
                button.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
                    refreshCardFocus()
                    // 全局焦点 fx（A）：小卡片按钮 scale 稍小、圆角 8dp，不叠加暖黄前景。
                    FocusFxHelper.applyFocusFxState(v, has, scale = 1.04f, cornerRadiusDp = 8, elevationDp = 6)
                }
            }

            // 边界方向键
            val boundaryKey = View.OnKeyListener { v, _, e -> handleCardKey(v, e, position) }
            cardButtons.forEach { it.setOnKeyListener(boundaryKey) }
        }

        override fun onViewRecycled(holder: VH) {
            super.onViewRecycled(holder)
            try {
                holder.root.animate().cancel()
                holder.root.scaleX = 1f
                holder.root.scaleY = 1f
                holder.root.foreground = null
                listOf(holder.btnPlay, holder.btnEdit, holder.btnMove, holder.btnDelete, holder.btnLater).forEach { button ->
                    button.onFocusChangeListener = null
                    button.setOnKeyListener(null)
                    button.setOnClickListener(null)
                    FocusFxHelper.applyFocusFxState(button, false, scale = 1.04f, cornerRadiusDp = 8, elevationDp = 6)
                }
                holder.thumb.setOnClickListener(null)
            } catch (_: Throwable) {}
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val GRID_COLUMNS = 4
        private const val VIDEO_CARD_FOCUS_DELAY_MS = 100L
        private const val TAG_FAV_GRID_PLAY_BUTTON = "fav_grid_play_button"
        private val BUTTON_DEFAULT_BG = Color.argb(51, 27, 31, 38)
        private val SILVER = Color.rgb(192, 192, 192)
        private val LIGHT_ICON_GRAY = Color.rgb(191, 195, 204)
        private val CARD_BG = Color.argb(18, 255, 255, 255)
        private val WARM = Color.rgb(255, 215, 0)
        private val WARM_SOFT = Color.argb(220, 255, 215, 100)
        private val SOFT_GRAY = Color.rgb(170, 170, 170)
        private val DANGER_RED = Color.rgb(255, 68, 68)
    }
}
