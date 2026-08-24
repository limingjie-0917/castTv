package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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

    init {
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
            setPadding(dp(10), dp(6), dp(10), dp(20))
        }
        listView = list
        root.addView(list, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        contentContainer.addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        switchTab(0)
        loadAsyncContent()
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
            KeyEvent.KEYCODE_DPAD_RIGHT -> focusFirstRowOrShake(v)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                switchTab(index)
                true
            }
            else -> false
        }
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
                    BoundaryFocusHandler.shake(v)
                    true
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
        renderCurrentTab()
    }

    private fun renderCurrentTab() {
        val rows = when (selectedTab) {
            0 -> buildQueueRows()
            1 -> buildRecommendationRows()
            else -> buildPopularRows()
        }
        adapter.submit(rows)
    }

    private fun buildQueueRows(): List<RowItem> {
        val items = queueStore.all()
        if (items.isEmpty()) return listOf(RowItem(null, "暂无稍后播放内容", "可以从推荐内容或收藏页加入", "", null))
        return items.map { item ->
            RowItem(
                queueRowKey = item.id,
                title = item.title.ifBlank { item.uri },
                subtitle = "${statusLabel(item.status)} · ${item.source.ifBlank { "queue" }}",
                action = "按 OK 播放",
                onClick = { (context as? NewMainActivity)?.startQueuePlayback(item, toastText = "已切换：${item.title}", focusRootOnReturn = false) }
            )
        }
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
            post {
                Toast.makeText(context, if (count > 0) "✅ 已下载「${collection.name}」" else "❌ 下载失败", Toast.LENGTH_SHORT).show()
                if (count > 0) loadAsyncContent()
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
     * 行背景三态：
     *  - focused：聚焦（最强提示，3dp 暖黄描边 + 透明底，优先保证 TV 焦点可见）
     *  - selected && !focused：选中未焦（"上次点击的项"的稳定锚点：暖黄半透明底 + 2dp 暖黄描边）
     *  - 其他：普通未选未焦（1dp 灰描边）
     */
    private fun rowBg(focused: Boolean, selected: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        when {
            focused -> {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), WARM)
            }
            selected -> {
                setColor(Color.argb(52, 245, 196, 81))
                setStroke(dp(2), WARM)
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
