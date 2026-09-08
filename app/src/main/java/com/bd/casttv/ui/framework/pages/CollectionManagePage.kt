package com.bd.casttv.ui.framework.pages

import android.animation.ObjectAnimator
import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.sync.CloudSyncDialog
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.NewMainActivity
import com.bd.casttv.util.ThemeManager

/**
 * 合集管理独立页面：
 *  - 顶部固定「← 返回」按钮 + 标题 + 当前状态；
 *  - 功能按钮行：新建合集 / 导入直播源 / 云同步 / 导入 / 导出；
 *  - 合集展示区：5 列文件夹卡片网格，卡片展示文件夹图标、序号、合集名称、视频数量副标题；
 *  - 底部行：全选 / 取消勾选 / 排序 / 删除 / 保存；
 *  - 排序模式：以最近聚焦合集为目标，方向键交换位置，OK 确认位置，BACK 退出排序模式；
 *  - 「保存」按钮：调用 [FavoritesStore.reorderCollections] 落盘后返回收藏页。
 */
class CollectionManagePage(
    context: Context,
    private val store: FavoritesStore,
    private val onExit: (saved: Boolean) -> Unit
) : BasePage(context), com.bd.casttv.ui.framework.SettingsChangeBus.Listener {

    override val pageId: String = "collection_manage"
    override val pageTitle: String = "合集管理"
    override val pageIconRes: Int = 0
    override val enablePageScroll: Boolean = false
    override val showPageHeader: Boolean get() = false

    // ---------------- 数据 ----------------

    /** 与原页面保持一致：只展示「非预置 && 非默认 && shared」的合集参与排序 / 删除。 */
    private val originalNormalCollections: List<FavoritesStore.CollectionInfo> =
        store.collectionsInfo().filter { !it.isDefault && !it.isPreset }
    private val visibleItems: MutableList<FavoritesStore.CollectionInfo> =
        originalNormalCollections
            .filter { it.type == FavoritesStore.TYPE_SHARED }
            .toMutableList()
    private val selectedIds: LinkedHashSet<String> = linkedSetOf()
    private val pendingDeleteIds: LinkedHashSet<String> = linkedSetOf()

    private var lastFocusedCollectionId: String? = visibleItems.firstOrNull()?.id
    private var isSortMode: Boolean = false
    private var sortingCollectionId: String? = null

    // ---------------- 视图 ----------------

    private lateinit var btnBack: FrameLayout
    private lateinit var selectedCountView: TextView
    private lateinit var hintBanner: TextView
    private lateinit var gridRecycler: RecyclerView
    private lateinit var gridHolder: FrameLayout
    private lateinit var emptyView: TextView
    private lateinit var btnCreate: TextView
    private lateinit var btnImportLive: TextView
    private lateinit var btnCloudSync: TextView
    private lateinit var btnImport: TextView
    private lateinit var btnExport: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnClear: TextView
    private lateinit var btnSort: TextView
    private lateinit var btnDelete: TextView
    private lateinit var btnSave: TextView
    private lateinit var adapter: CollectionCardAdapter
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var reloadGeneration = 0

    init {
        applyThemeBackground()
        buildLayout()
        refreshState()
    }

    // ---------------- 主题联动 ----------------

    /** 合集管理页背景接入主题风格设置：使用 contentPanelBg() 取主题渐变色。 */
    private fun applyThemeBackground() {
        background = contentPanelBg()
        if (::gridHolder.isInitialized) {
            gridHolder.background = contentPanelBg().apply {
                setStroke(dp(1), Color.argb(70, 255, 215, 0))
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        com.bd.casttv.ui.framework.SettingsChangeBus.addListener(this)
    }

    override fun onDetachedFromWindow() {
        com.bd.casttv.ui.framework.SettingsChangeBus.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSettingsChanged() {
        super.onSettingsChanged()
        applyThemeBackground()
    }

    // ---------------- 生命周期 & 焦点 ----------------

    override fun focusToFirstContent(): Boolean {
        val ok = btnCreate.requestFocus()
        if (ok) onFocusEnterContent()
        return ok
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (isSortMode) {
                if (event.action == KeyEvent.ACTION_DOWN) exitSortMode(confirm = false)
                return true
            }
            // BACK 键：不直接退出页面，只把焦点收回到「新建合集」按钮；
            // 用户可再按 ↑ 到顶部「返回」按钮，点击后退出到收藏主页。
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (findFocus() !== btnCreate) {
                    btnCreate.requestFocus()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun exitWithoutSaving() {
        val activity = context as? NewMainActivity
        if (activity != null) {
            activity.popOverlayPage(this)
        }
        onExit(false)
    }

    private fun exitWithSave() {
        if (isSortMode) exitSortMode(confirm = true)
        val deletedCount = if (pendingDeleteIds.isNotEmpty()) store.deleteCollections(pendingDeleteIds) else 0
        val orderResult = store.reorderCollections(mergedOrderAfterPendingChanges())
        if (deletedCount > 0) toast("已删除 $deletedCount 个合集")
        if (orderResult != FavoritesStore.OpResult.SUCCESS && orderResult != FavoritesStore.OpResult.NOT_FOUND) {
            toast("排序保存失败：$orderResult")
        } else {
            toast("已保存")
        }
        val activity = context as? NewMainActivity
        activity?.popOverlayPage(this)
        onExit(true)
    }

    // ---------------- 布局构建 ----------------

    private fun buildLayout() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(8), dp(56), dp(12))
            clipChildren = false
            clipToPadding = false
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        btnBack = mkBackButton().apply {
            setOnClickListener { exitWithoutSaving() }
            setOnKeyListener { _, _, e -> handleTopKey(e) }
        }
        topRow.addView(btnBack, LayoutParams(dp(44), dp(44)))

        val title = TextView(context).apply {
            text = "合集管理"
            textSize = 22f
            setTextColor(WARM)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), 0, 0, 0)
        }
        topRow.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        selectedCountView = TextView(context).apply {
            textSize = 15f
            setTextColor(WARM)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        topRow.addView(selectedCountView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        root.addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val separator = View(context).apply {
            setBackgroundColor(Color.argb(0x40, 0xFF, 0xFF, 0xFF))
        }
        root.addView(separator, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6)
            bottomMargin = dp(8)
        })

        val functionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        btnCreate = mkActionButton("新建合集").apply { setOnClickListener { createCollection() } }
        btnImportLive = mkActionButton("导入直播源").apply { setOnClickListener { importLive() } }
        btnCloudSync = mkActionButton("云同步").apply { setOnClickListener { openCloudSync() } }
        btnImport = mkActionButton("导入").apply { setOnClickListener { transferImport() } }
        btnExport = mkActionButton("导出").apply { setOnClickListener { transferExport() } }
        listOf(btnCreate, btnImportLive, btnCloudSync, btnImport, btnExport).forEachIndexed { index, b ->
            b.setOnKeyListener { v, _, e -> handleFunctionKey(v, e) }
            functionRow.addView(b, LayoutParams(0, dp(42), 1f).apply { if (index > 0) marginStart = dp(10) })
        }
        root.addView(functionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        hintBanner = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.argb(235, 255, 232, 150))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(54, 255, 215, 0))
                setStroke(dp(1), Color.argb(118, 255, 215, 0))
            }
        }
        root.addView(hintBanner, LayoutParams(LayoutParams.MATCH_PARENT, dp(38)).apply {
            bottomMargin = dp(6)
        })

        gridHolder = FrameLayout(context).apply {
            background = contentPanelBg().apply {
                setStroke(dp(1), Color.argb(70, 255, 215, 0))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            // 列表滚动区域需要由直接父容器裁剪，避免 RecyclerView 滑动内容溢出面板；
            // 卡片内部仍保留 clip=false，以保障焦点放大与边框完整显示。
            clipChildren = true
            clipToPadding = true
        }
        gridRecycler = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, GRID_COLUMNS)
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = false
            itemAnimator = null
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addItemDecoration(GridGapDecoration(dp(10), dp(16), GRID_COLUMNS))
        }
        emptyView = TextView(context).apply {
            text = "暂无共享合集"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(170, 238, 232, 218))
        }
        gridHolder.addView(gridRecycler, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        gridHolder.addView(emptyView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(gridHolder, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        btnSelectAll = mkActionButton("全选").apply {
            setOnClickListener {
                if (isSortMode) return@setOnClickListener
                selectedIds.clear()
                visibleItems.forEach { selectedIds.add(it.id) }
                adapter.notifyDataSetChanged()
                refreshState()
            }
        }
        btnClear = mkActionButton("取消勾选").apply {
            setOnClickListener {
                if (isSortMode) return@setOnClickListener
                selectedIds.clear()
                adapter.notifyDataSetChanged()
                refreshState()
            }
        }
        btnSort = mkActionButton("排序").apply {
            setOnClickListener { startSortModeFromLastFocused() }
            setOnFocusChangeListener { v, has ->
                refreshActionButton(v as TextView, has)
                updateHintForCurrentFocus(v)
            }
        }
        btnDelete = mkActionButton("删除").apply {
            setOnClickListener { onDeleteClicked() }
        }
        btnSave = mkActionButton("保存").apply {
            setOnClickListener { exitWithSave() }
        }
        listOf(btnSelectAll, btnClear, btnSort, btnDelete, btnSave).forEachIndexed { index, b ->
            if (b !== btnSort) {
                b.setOnFocusChangeListener { v, has ->
                    refreshActionButton(v as TextView, has)
                    updateHintForCurrentFocus(v)
                }
            }
            b.setOnKeyListener { v, _, e -> handleBottomKey(v, e) }
            bottomRow.addView(b, LayoutParams(LayoutParams.WRAP_CONTENT, dp(42)).apply {
                if (index > 0) marginStart = dp(10)
            })
        }
        root.addView(bottomRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        contentContainer.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        adapter = CollectionCardAdapter()
        gridRecycler.adapter = adapter
    }

    // ---------------- 焦点导航 ----------------

    private fun handleTopKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                btnCreate.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                shake(btnBack)
                true
            }
            else -> false
        }
    }

    private fun handleFunctionKey(view: View, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                btnBack.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                focusCard(lastFocusedIndex().takeIf { it >= 0 } ?: 0)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val buttons = functionButtons()
                val index = buttons.indexOf(view)
                when {
                    index > 0 -> buttons[index - 1].requestFocus()
                    else -> shake(view)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val buttons = functionButtons()
                val index = buttons.indexOf(view)
                when {
                    index in 0 until buttons.lastIndex -> buttons[index + 1].requestFocus()
                    else -> {
                        // 顶部功能行末尾按 → :依次跳到「合集卡片区」→「底部按钮首个」
                        if (visibleItems.isNotEmpty()) {
                            focusCard(lastFocusedIndex().takeIf { it >= 0 } ?: 0)
                        } else {
                            focusBottomButton(0)
                        }
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun handleCardKey(position: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (position !in visibleItems.indices) return false
        if (isSortMode) return handleSortModeCardKey(position, event)

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                toggleSelection(position)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (position % GRID_COLUMNS == 0) { shakeFocusedCard(position); true } else false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (position == visibleItems.lastIndex) {
                    // 合集卡片最后一张按 → :跳到底部按钮首个
                    focusBottomButton(0)
                    true
                } else if (position % GRID_COLUMNS == GRID_COLUMNS - 1) {
                    shakeFocusedCard(position); true
                } else false
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                val target = position - GRID_COLUMNS
                if (target >= 0) {
                    focusCard(target)
                } else {
                    focusFunctionButton(position % GRID_COLUMNS)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val target = position + GRID_COLUMNS
                if (target < visibleItems.size) {
                    focusCard(target)
                } else {
                    focusBottomButton(position % GRID_COLUMNS)
                }
                true
            }
            else -> false
        }
    }

    private fun handleSortModeCardKey(position: Int, event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                exitSortMode(confirm = true)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveSortingCard(position, position - 1, sameRowOnly = true)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveSortingCard(position, position + 1, sameRowOnly = true)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveSortingCard(position, position - GRID_COLUMNS, sameRowOnly = false)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSortingCard(position, position + GRID_COLUMNS, sameRowOnly = false)
                true
            }
            else -> false
        }
    }

    private fun handleBottomKey(view: View, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                focusCard(lastFocusedIndex().takeIf { it >= 0 } ?: visibleItems.lastIndex)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                shake(view)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (view === btnSelectAll) { shake(view); true } else false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (view === btnSave) { shake(view); true } else false
            }
            else -> false
        }
    }

    private fun functionButtons(): List<TextView> = listOf(btnCreate, btnImportLive, btnCloudSync, btnImport, btnExport)

    private fun focusFunctionButton(column: Int) {
        val buttons = functionButtons()
        buttons[column.coerceIn(0, buttons.lastIndex)].requestFocus()
    }

    private fun focusBottomButton(column: Int) {
        val buttons = listOf(btnSelectAll, btnClear, btnSort, btnDelete, btnSave).filter { it.isEnabled && it.visibility == View.VISIBLE }
        if (buttons.isEmpty()) return
        buttons[column.coerceIn(0, buttons.lastIndex)].requestFocus()
    }

    private fun focusCard(position: Int) {
        if (visibleItems.isEmpty()) return
        val target = position.coerceIn(0, visibleItems.lastIndex)
        gridRecycler.scrollToPosition(target)
        gridRecycler.post {
            val view = gridRecycler.findViewHolderForAdapterPosition(target)?.itemView
            if (view?.requestFocus() != true) {
                gridRecycler.postDelayed({ gridRecycler.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }, 80L)
            }
        }
    }

    private fun shakeFocusedCard(position: Int) {
        val view = gridRecycler.findViewHolderForAdapterPosition(position)?.itemView
        if (view != null) shake(view)
    }

    // ---------------- 排序 & 选择 ----------------

    private fun toggleSelection(position: Int) {
        if (isSortMode) return
        val item = visibleItems.getOrNull(position) ?: return
        if (!selectedIds.add(item.id)) selectedIds.remove(item.id)
        adapter.notifyItemChanged(position)
        refreshState()
    }

    private fun startSortModeFromLastFocused() {
        val id = selectedIds.singleOrNull()
        val index = visibleItems.indexOfFirst { it.id == id }
        if (id == null || index < 0) {
            toast("请选择 1 个合集后再排序")
            return
        }
        enterSortMode(id, index)
    }

    private fun enterSortMode(id: String, index: Int) {
        isSortMode = true
        sortingCollectionId = id
        lastFocusedCollectionId = id
        adapter.notifyDataSetChanged()
        refreshState()
        toast("正在排序：${visibleItems.getOrNull(index)?.name.orEmpty()}")
        focusCard(index)
    }

    private fun exitSortMode(confirm: Boolean) {
        val id = sortingCollectionId
        isSortMode = false
        sortingCollectionId = null
        adapter.notifyDataSetChanged()
        refreshState()
        if (confirm) toast("已确认位置，点击保存后生效") else toast("已退出排序模式，点击保存后生效")
        val index = visibleItems.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: lastFocusedIndex()
        if (index >= 0) focusCard(index)
    }

    private fun moveSortingCard(currentPosition: Int, targetPosition: Int, sameRowOnly: Boolean) {
        val sortingId = sortingCollectionId ?: return
        val actualPosition = visibleItems.indexOfFirst { it.id == sortingId }
        if (actualPosition < 0) return
        val from = actualPosition.takeIf { it == currentPosition } ?: actualPosition
        val target = targetPosition
        val crossesRow = sameRowOnly && target / GRID_COLUMNS != from / GRID_COLUMNS
        if (target !in visibleItems.indices || crossesRow) {
            shakeFocusedCard(from)
            return
        }
        val item = visibleItems.removeAt(from)
        visibleItems.add(target, item)
        lastFocusedCollectionId = item.id
        adapter.notifyDataSetChanged()
        refreshState()
        focusCard(target)
    }

    private fun lastFocusedIndex(): Int = visibleItems.indexOfFirst { it.id == lastFocusedCollectionId }

    private fun onDeleteClicked() {
        if (isSortMode) { toast("请先退出排序模式"); return }
        if (selectedIds.isEmpty()) { toast("请先勾选要删除的合集"); return }
        showDeleteConfirmDialog()
    }

    private fun showDeleteConfirmDialog() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 弹窗内容区域统一保留 4dp padding，避免贴边并符合通用弹窗规范。
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = context.getDrawable(R.drawable.bg_dialog_crayon_panel)
            clipChildren = false
            clipToPadding = false
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            clipChildren = false
            clipToPadding = false
        }
        panel.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "删除合集"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })

        content.addView(TextView(context).apply {
            text = "确认删除已勾选的 ${selectedIds.size} 个合集吗？\n点击「保存」后才会正式生效。"
            textSize = 15f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(Color.argb(224, 238, 232, 218))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(20)
        })

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
        }
        val cancelBtn = makeDialogButton("取消")
        val deleteBtn = makeDialogButton("删除")
        actionRow.addView(cancelBtn, LayoutParams(dp(112), dp(44)).apply { rightMargin = dp(12) })
        actionRow.addView(deleteBtn, LayoutParams(dp(112), dp(44)))
        content.addView(actionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            cancelBtn.requestFocus()
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        deleteBtn.setOnClickListener {
            pendingDeleteIds.addAll(selectedIds)
            visibleItems.removeAll { it.id in selectedIds }
            selectedIds.clear()
            if (lastFocusedCollectionId !in visibleItems.map { it.id }) {
                lastFocusedCollectionId = visibleItems.firstOrNull()?.id
            }
            adapter.notifyDataSetChanged()
            refreshState()
            toast("已加入待删除，点击保存后生效")
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setLayout(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 合并「排序后 visibleItems + 未变化的 private 项 + 排除待删除项」，得到最终写回顺序。 */
    private fun mergedOrderAfterPendingChanges(): List<String> {
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

    private fun refreshState() {
        selectedCountView.text = if (isSortMode) "排序模式" else "已选 ${selectedIds.size} 个合集"
        emptyView.visibility = if (visibleItems.isEmpty()) View.VISIBLE else View.GONE
        gridRecycler.visibility = if (visibleItems.isEmpty()) View.GONE else View.VISIBLE
        setButtonEnabled(btnClear, !isSortMode && selectedIds.isNotEmpty())
        setButtonEnabled(btnDelete, !isSortMode && selectedIds.isNotEmpty())
        setButtonEnabled(btnSelectAll, !isSortMode && visibleItems.isNotEmpty() && selectedIds.size < visibleItems.size)
        val canSortSelectedSingle = !isSortMode && selectedIds.size == 1
        setButtonEnabled(btnSort, canSortSelectedSingle)
        btnSort.isSelected = canSortSelectedSingle || isSortMode
        refreshActionButton(btnSort, focused = btnSort.isFocused && btnSort.isEnabled)
        updateHintForCurrentFocus(findFocus())
    }

    private fun setButtonEnabled(button: TextView, enabled: Boolean) {
        button.isEnabled = enabled
        button.isFocusable = enabled
        button.alpha = if (enabled) 1f else 0.38f
        refreshActionButton(button, focused = button.isFocused && enabled)
    }

    private fun updateHintForCurrentFocus(focused: View?) {
        hintBanner.text = when {
            isSortMode -> {
                val name = visibleItems.firstOrNull { it.id == sortingCollectionId }?.name.orEmpty()
                "排序模式：方向键移动「$name」位置，OK 确认，BACK 退出｜移动后点击保存生效"
            }
            focused === btnSort -> {
                val name = selectedIds.singleOrNull()?.let { selectedId -> visibleItems.firstOrNull { it.id == selectedId }?.name }
                if (name.isNullOrBlank()) "请选择 1 个合集后再排序" else "将排序：$name｜点击 OK 进入排序模式"
            }
            focused === btnSelectAll || focused === btnClear || focused === btnDelete || focused === btnSave ->
                "方向键返回合集卡片；选择单个合集排序"
            focused === btnCreate || focused === btnImportLive || focused === btnCloudSync || focused === btnImport || focused === btnExport ->
                "方向键浏览功能按钮，下键进入合集卡片网格；选择单个合集排序"
            else -> "方向键选择合集，OK 勾选；选择单个合集排序"
        }
    }

    // ---------------- 功能按钮 action ----------------

    private fun createCollection() {
        val WARM_ACCENT = Color.rgb(245, 196, 81)
        val GREY = 0xFFB8B8B8.toInt()
        val LIGHT_TEXT = 0xFFEEE8DA.toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                ThemeManager.currentPalette(context).dialogTitleGradient
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM_ACCENT)
            }
        }

        // 顶部标题栏（圆形贴纸 + 标题）
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "新建合集"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        panel.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        // 描述
        panel.addView(TextView(context).apply {
            text = "为你的新合集取个名字吧～创建后会自动加入共享合集列表。"
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        // 输入框
        val input = EditText(context).apply {
            hint = "请输入合集名称"
            textSize = 14f
            setSingleLine(true)
            setTextColor(LIGHT_TEXT)
            setHintTextColor(GREY)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), WARM_ACCENT)
                setColor(Color.argb(32, 255, 255, 255))
            }
            setPadding(dp(12), 0, dp(12), 0)
        }
        panel.addView(input, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))

        // 操作行
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
        }
        val cancelBtn = makeDialogButton("取消")
        val createBtn = makeDialogButton("创建")
        actionRow.addView(cancelBtn, LayoutParams(dp(112), dp(44)).apply { rightMargin = dp(10) })
        actionRow.addView(createBtn, LayoutParams(dp(112), dp(44)))
        panel.addView(actionRow)

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        createBtn.setOnClickListener {
            val name = input.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) { toast("名称不能为空"); return@setOnClickListener }
            val (result, id) = store.createCollection(name, FavoritesStore.TYPE_SHARED)
            if (result == FavoritesStore.OpResult.SUCCESS && id != null) {
                val info = store.collectionsInfo().firstOrNull { it.id == id }
                if (info != null && !info.isDefault && !info.isPreset && info.type == FavoritesStore.TYPE_SHARED) {
                    visibleItems.add(info)
                    lastFocusedCollectionId = info.id
                    adapter.notifyDataSetChanged()
                    refreshState()
                    focusCard(visibleItems.lastIndex)
                }
                toast("已创建：${info?.name.orEmpty()}")
            } else {
                toast("创建失败：$result")
            }
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setLayout(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT)
        input.requestFocus()
    }

    private fun makeDialogButton(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        isFocusable = true
        isClickable = true
        refreshDialogButton(this, focused = false)
        setOnFocusChangeListener { v, has -> refreshDialogButton(v as TextView, has) }
    }

    private fun refreshDialogButton(button: TextView, focused: Boolean) {
        button.setTextColor(Color.WHITE)
        button.background = GradientDrawable().apply {
            cornerRadius = dp(60).toFloat()
            setStroke(dp(if (focused) 2 else 1), if (focused) WARM else SILVER)
            setColor(if (focused) Color.argb(58, 245, 196, 81) else BUTTON_DEFAULT_BG)
        }
        FocusFxHelper.applyFocusFxState(button, focused, cornerRadiusDp = 12)
    }

    private fun importLive() {
        val currentDefault = store.collectionsInfo().firstOrNull { it.isDefault }?.id.orEmpty()
        ImportLiveSourceDialog(context, store, currentDefault, onImported = {
            reloadVisibleItems()
        }).show()
    }

    private fun openCloudSync() {
        CloudSyncDialog(context, store, onSyncComplete = { reloadVisibleItems() }).show()
    }

    private fun transferImport() {
        FavTransferDialog(context, store, com.bd.casttv.dlna.FavoriteTransferServer.Mode.IMPORT, onImported = {
            reloadVisibleItems()
        }).show()
    }

    private fun transferExport() {
        FavTransferDialog(context, store, com.bd.casttv.dlna.FavoriteTransferServer.Mode.EXPORT, onImported = {
            reloadVisibleItems()
        }).show()
    }

    /** 弹窗内新增/导入合集后，重新拉取数据，同时保留当前 selectedIds / pendingDeleteIds 的合法项。 */
    private fun reloadVisibleItems() {
        val generation = ++reloadGeneration
        val previousOrder = visibleItems.map { it.id }
        val previousIds = previousOrder.toSet()
        val pendingDeletes = pendingDeleteIds.toSet()
        val previousFocusId = lastFocusedCollectionId
        if (isSortMode) exitSortMode(confirm = true)

        Thread {
            val fresh = try {
                store.collectionsInfo().filter { !it.isDefault && !it.isPreset && it.type == FavoritesStore.TYPE_SHARED }
            } catch (_: Throwable) {
                emptyList()
            }
            val freshById = fresh.associateBy { it.id }
            val ordered = ArrayList<FavoritesStore.CollectionInfo>(fresh.size)
            previousOrder.forEach { id ->
                freshById[id]?.let { ordered.add(it) }
            }
            fresh.forEach { item ->
                if (item.id !in previousIds && item.id !in pendingDeletes) ordered.add(item)
            }
            val validIds = ordered.map { it.id }.toSet()

            mainHandler.post {
                if (generation != reloadGeneration || !isAttachedToWindow) return@post
                visibleItems.clear()
                visibleItems.addAll(ordered)
                selectedIds.retainAll(validIds)
                if (previousFocusId !in validIds) {
                    lastFocusedCollectionId = visibleItems.firstOrNull()?.id
                }
                adapter.notifyDataSetChanged()
                refreshState()
            }
        }.start()
    }

    // ---------------- 视图构造工具 ----------------

    private fun mkBackButton(): FrameLayout = FrameLayout(context).apply {
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        contentDescription = "返回"
        clipChildren = false
        clipToPadding = false
        minimumWidth = dp(44)
        minimumHeight = dp(44)
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_collection_header_back)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(LIGHT_ICON_GRAY)
        }
        addView(icon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        refreshIconButton(this, focused = false)
        setOnFocusChangeListener { v, has ->
            refreshIconButton(v as FrameLayout, has)
            updateHintForCurrentFocus(v)
        }
    }

    private fun refreshIconButton(button: FrameLayout, focused: Boolean) {
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (focused) Color.argb(58, 245, 196, 81) else BUTTON_DEFAULT_BG)
            setStroke(dp(2), if (focused) WARM else SILVER)
        }
        button.getChildAt(0)?.let { child ->
            if (child is ImageView) child.setColorFilter(LIGHT_ICON_GRAY)
        }
        FocusFxHelper.applyFocusFxState(button, focused, cornerRadiusDp = 40)
    }

    private fun mkActionButton(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        minWidth = dp(78)
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(Color.WHITE)
        isFocusable = true
        isFocusableInTouchMode = false
        refreshActionButton(this, focused = false)
        setOnFocusChangeListener { v, has ->
            refreshActionButton(v as TextView, has)
            updateHintForCurrentFocus(v)
        }
    }

    private fun refreshActionButton(button: TextView, focused: Boolean) {
        val selected = button.isSelected && button.isEnabled
        button.setTextColor(if (selected) WARM else Color.WHITE)
        button.paint.isFakeBoldText = selected
        button.background = GradientDrawable().apply {
            cornerRadius = dp(60).toFloat()
            setColor(CARD_TRANSLUCENT_WHITE)
            setStroke(dp(2), if (focused) WARM else SILVER)
        }
        // 全局焦点 fx（A：scale 1.05 + translationZ 发光；不叠加暖黄前景）。
        FocusFxHelper.applyFocusFxState(button, focused, cornerRadiusDp = 12)
        button.invalidate()
    }

    private fun cardBackground(focused: Boolean, sorting: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        val fill = when {
            sorting -> Color.argb(78, 255, 215, 0)
            else -> CARD_TRANSLUCENT_WHITE
        }
        setColor(fill)
        val strokeColor = when {
            sorting -> WARM
            focused -> WARM
            else -> Color.argb(60, 255, 255, 255)
        }
        setStroke(dp(if (sorting || focused) 3 else 1), strokeColor)
    }

    private fun crayonPanelBg(alpha: Int, radiusDp: Int, strokeColor: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(Color.argb(alpha, 20, 24, 32))
        setStroke(dp(1), strokeColor)
    }

    private fun shake(v: View) {
        ObjectAnimator.ofFloat(v, "translationX", 0f, -8f, 8f, -6f, 6f, -3f, 3f, 0f).setDuration(260).start()
    }

    private fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- 合集卡片 Adapter ----------------

    private inner class CollectionCardAdapter : RecyclerView.Adapter<CollectionCardAdapter.VH>() {
        override fun getItemCount(): Int = visibleItems.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(10), dp(10), dp(2))
                isFocusable = true
                isFocusableInTouchMode = false
                clipChildren = false
                clipToPadding = false
                minimumHeight = dp(136)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(148))
            }

            val fileBox = FrameLayout(parent.context).apply {
                clipChildren = false
                clipToPadding = false
            }
            val iconWrap = FrameLayout(parent.context).apply {
                clipChildren = false
                clipToPadding = false
            }
            val folder = ImageView(parent.context).apply {
                setImageResource(R.drawable.ic_collection)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                contentDescription = "合集图标"
            }
            iconWrap.addView(folder, FrameLayout.LayoutParams(dp(96), dp(78), Gravity.CENTER))
            fileBox.addView(iconWrap, FrameLayout.LayoutParams(dp(112), dp(92), Gravity.CENTER))
            root.addView(fileBox, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

            val name = TextView(parent.context).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Color.argb(238, 238, 232, 218))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            root.addView(name, LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))

            val hint = TextView(parent.context).apply {
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.argb(178, 238, 232, 218))
                maxLines = 1
            }
            root.addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, dp(16)))

            val checkMark = ImageView(parent.context).apply {
                setImageResource(R.drawable.ic_collection_manage_hand_check)
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
                contentDescription = "已选中"
            }
            val sequenceBadge = TextView(parent.context).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = null
            }
            val wrapper = FrameLayout(parent.context).apply {
                clipChildren = false
                clipToPadding = false
                addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                addView(sequenceBadge, FrameLayout.LayoutParams(dp(36), dp(30), Gravity.TOP or Gravity.START).apply {
                    topMargin = dp(4)
                    leftMargin = dp(6)
                })
                addView(checkMark, FrameLayout.LayoutParams(dp(34), dp(30), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(1)
                    rightMargin = dp(2)
                })
            }
            return VH(wrapper, root, folder, name, hint, checkMark, sequenceBadge)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = visibleItems[position]
            holder.bind(item, position)
        }

        inner class VH(
            item: View,
            val card: LinearLayout,
            val folder: ImageView,
            val name: TextView,
            val hint: TextView,
            val checkMark: ImageView,
            val sequenceBadge: TextView
        ) : RecyclerView.ViewHolder(item) {
            fun bind(item: FavoritesStore.CollectionInfo, position: Int) {
                val checked = item.id in selectedIds
                val sorting = isSortMode && item.id == sortingCollectionId
                val focused = itemView.hasFocus() || card.hasFocus()
                sequenceBadge.text = (position + 1).toString().padStart(2, '0')
                sequenceBadge.visibility = View.VISIBLE
                name.text = item.name
                hint.text = if (sorting) "排序中｜共${item.itemCount}集" else "共${item.itemCount}集"
                hint.visibility = View.VISIBLE
                hint.setTextColor(if (sorting) WARM else Color.argb(178, 238, 232, 218))
                name.setTextColor(if (sorting) WARM else Color.argb(238, 238, 232, 218))
                name.paint.isFakeBoldText = sorting
                checkMark.visibility = if (checked) View.VISIBLE else View.GONE
                folder.translationY = if (sorting || focused) -dp(4).toFloat() else 0f
                folder.alpha = if (sorting || focused) 1f else 0.96f
                refreshCardVisual(this, focused = focused, sorting = sorting)

                card.setOnClickListener { toggleSelection(bindingAdapterPosition) }
                card.setOnLongClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) {
                        val current = visibleItems[p]
                        enterSortMode(current.id, p)
                    }
                    true
                }
                card.setOnFocusChangeListener { _, has ->
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION && has) {
                        lastFocusedCollectionId = visibleItems[p].id
                    }
                    refreshCardVisual(this, focused = has, sorting = isSortMode && item.id == sortingCollectionId)
                    hint.visibility = View.VISIBLE
                    hint.text = if (isSortMode && item.id == sortingCollectionId) "排序中｜共${item.itemCount}集" else "共${item.itemCount}集"
                    hint.setTextColor(if (isSortMode && item.id == sortingCollectionId) WARM else Color.argb(178, 238, 232, 218))
                    updateHintForCurrentFocus(card)
                }
                card.setOnKeyListener { _, _, e ->
                    val p = bindingAdapterPosition
                    p != RecyclerView.NO_POSITION && handleCardKey(p, e)
                }
            }
        }
    }

    private fun refreshCardVisual(holder: CollectionCardAdapter.VH, focused: Boolean, sorting: Boolean) {
        holder.card.background = cardBackground(focused = focused, sorting = sorting)
        val scale = when {
            sorting -> 1.08f
            focused -> 1.04f
            else -> 1f
        }
        holder.itemView.animate().cancel()
        holder.itemView.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        holder.card.elevation = dp(if (sorting) 16 else if (focused) 10 else 0).toFloat()
        holder.folder.animate().cancel()
        holder.folder.animate()
            .translationY(if (sorting || focused) -dp(4).toFloat() else 0f)
            .alpha(if (sorting || focused) 1f else 0.96f)
            .setDuration(120)
            .start()
    }

    private class GridGapDecoration(
        private val horizontal: Int,
        private val vertical: Int,
        private val columns: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val column = position % columns
            outRect.left = horizontal / 2
            outRect.right = horizontal / 2
            outRect.top = if (position < columns) 0 else vertical / 2
            outRect.bottom = vertical / 2
            if (column == 0) outRect.left = 0
            if (column == columns - 1) outRect.right = 0
        }
    }

    companion object {
        private const val GRID_COLUMNS = 5
        private val WARM = Color.rgb(255, 215, 0)
        private val ROYAL_BLUE_PANEL = Color.rgb(65, 105, 225)
        private val CARD_TRANSLUCENT_WHITE = Color.argb(18, 255, 255, 255)
        private val SILVER = Color.rgb(192, 192, 192)
        private val LIGHT_ICON_GRAY = Color.rgb(191, 195, 204)
        private val BUTTON_DEFAULT_BG = Color.argb(51, 27, 31, 38)
    }
}
