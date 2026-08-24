package com.bd.casttv.ui.framework.pages

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.dlna.PlaybackController
import com.bd.casttv.dlna.displayThumbPath
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.queue.PlayQueueStore
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.util.Thumbnails
import com.bd.casttv.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ⑤ 历史记录 HistoryPage：
 * - 5.1 历史列表（缩略图 + 时长 + 最后播放时间），数据源 [PlaybackController.history]
 * - 5.2 续播（有 positionMs → 从上次位置续播）
 * - 5.3 清空历史（页顶独立按钮，同时保留在设置页）
 * - 5.4 加入收藏（一键入默认合集）
 * - 5.5 观看进度条（卡片底部）
 *
 * P0-4：由 ScrollView + LinearLayout 全量 inflate，改为 RecyclerView 单列布局 + ViewHolder 复用。
 * 保持「单列」（不改多列/网格）；仅缩小卡片内部尺寸以避免 TV 遥控器焦点跳跃。
 * 历史数据获取改异步：主线程只订阅结果并 submitList。
 */
class HistoryPage(context: Context) : BasePage(context) {
    override val pageId = "history"
    override val pageTitle = "历史播放"
    override val pageIconRes = R.drawable.ic_history_tv
    override val enablePageScroll = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true
    override val pageStickerRes: Int get() = R.drawable.sticker_kazama

    private val emptyView = TextView(context).apply { text = "还没有观看历史，快去投屏或播放试试～"; textSize = 16f; setTextColor(Color.rgb(210, 214, 220)); gravity = Gravity.CENTER; visibility = View.GONE }
    private val clearBtn = focusButton("🗑 清空历史")
    private val refreshBtn = focusButton("🔄 刷新")

    private val recycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        setHasFixedSize(false)
        // 单列布局 + ViewHolder 复用：避免全量 inflate 卡片
    }
    private val adapter = HistoryAdapter()

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    private val fav by lazy { FavoritesStore(context.applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { renderList() }

    // P0-4.3：异步获取历史数据，主线程只订阅结果
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentLoadJob: Job? = null

    init {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(12), dp(64), dp(32))
        }
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(View(context), LinearLayout.LayoutParams(0, dp(1), 1f))
        topBar.addView(refreshBtn.apply {
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(44)).apply { rightMargin = dp(12) }
            setOnClickListener { renderList(); toast("已刷新") }
        })
        topBar.addView(clearBtn.apply {
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(44))
            setOnClickListener { confirmClear() }
        })
        outer.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        body.addView(emptyView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(40) })
        recycler.adapter = adapter
        recycler.clipChildren = false
        body.addView(recycler, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        outer.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        contentContainer.addView(outer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onEnter() {
        renderList()
        post { focusToRoot() }
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 3000L)
    }
    override fun onLeave() {
        handler.removeCallbacks(refreshRunnable)
        currentLoadJob?.cancel()
        currentLoadJob = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        currentLoadJob?.cancel()
        currentLoadJob = null
        pageScope.coroutineContext[Job]?.cancel()
    }

    /**
     * P0-4.3：历史数据获取改异步；主线程只订阅结果并 submitList。
     */
    private fun renderList() {
        currentLoadJob?.cancel()
        currentLoadJob = pageScope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { PlaybackController.history() }.getOrElse { emptyList() }
            }
            emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitList(items)
        }
    }

    // -------------------------------------------------------------- Adapter / ViewHolder

    /**
     * P0-4.1/4.2：RecyclerView 单列 Adapter，卡片复用 ViewHolder，onBind 内不做 IO。
     */
    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryViewHolder>() {
        private var items: List<PlaybackController.HistoryItem> = emptyList()

        fun submitList(newItems: List<PlaybackController.HistoryItem>) {
            val old = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = old.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    old[oldPos].uri == newItems[newPos].uri
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = old[oldPos]
                    val b = newItems[newPos]
                    return a.title == b.title
                        && a.time == b.time
                        && a.positionMs == b.positionMs
                        && a.durationMs == b.durationMs
                        && a.source == b.source
                        && a.thumbPath == b.thumbPath
                }
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
            return HistoryViewHolder(buildCardShell())
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class HistoryViewHolder(itemView: LinearLayout) : RecyclerView.ViewHolder(itemView) {
        private val root: LinearLayout = itemView
        private val thumb: ImageView = itemView.findViewById(ID_THUMB)
        private val titleTv: TextView = itemView.findViewById(ID_TITLE)
        private val subtitleTv: TextView = itemView.findViewById(ID_SUBTITLE)
        private val posTv: TextView = itemView.findViewById(ID_POS)
        private val progress: ProgressBar = itemView.findViewById(ID_PROGRESS)
        private val playBtn: TextView = itemView.findViewById(ID_BTN_PLAY)
        private val favBtn: TextView = itemView.findViewById(ID_BTN_FAV)
        private val laterBtn: TextView = itemView.findViewById(ID_BTN_LATER)

        fun bind(item: PlaybackController.HistoryItem) {
            Thumbnails.load(thumb, item.displayThumbPath())
            titleTv.text = item.title.ifBlank { item.uri }
            val subtitle = buildString {
                append("最后播放：")
                append(timeFmt.format(Date(item.time)))
                if (item.durationMs > 0) {
                    append("  ·  时长 ")
                    append(formatDuration(item.durationMs))
                }
                if (!item.source.isNullOrBlank()) append("  ·  来源 ").append(item.source)
            }
            subtitleTv.text = subtitle

            val hasProgress = item.durationMs > 0 && item.positionMs in 1 until (item.durationMs * 0.98).toLong()
            val watchedEnd = item.durationMs > 0 && item.positionMs >= item.durationMs * 0.98
            val pct = if (item.durationMs > 0) ((item.positionMs.toDouble() / item.durationMs) * 100).toInt().coerceIn(0, 100) else 0
            posTv.text = when {
                hasProgress -> "上次观看到：${formatDuration(item.positionMs)}"
                watchedEnd -> "已观看完成"
                else -> "尚未开始观看"
            }
            progress.progress = pct

            playBtn.text = if (hasProgress) "继续播放" else "重新播放"
            playBtn.setOnClickListener { launchPlayer(item, if (hasProgress) item.positionMs else 0L) }
            favBtn.setOnClickListener { addToFavorites(item) }

            val queueStore = PlayQueueStore.get(context.applicationContext)
            fun refreshLaterText() {
                laterBtn.text = if (queueStore.findByUri(item.uri) != null) "移出队列" else "稍后播放"
            }
            refreshLaterText()
            laterBtn.setOnClickListener {
                val wasQueued = queueStore.findByUri(item.uri) != null
                val ok = if (wasQueued) {
                    queueStore.removeByUri(item.uri)
                } else {
                    queueStore.add(item.title.ifBlank { item.uri }, item.uri, item.source.ifBlank { "history" }).isNotBlank()
                }
                refreshLaterText()
                toast(when {
                    wasQueued && ok -> "已移出队列"
                    wasQueued -> "移出队列失败"
                    ok -> "已加入稍后播放"
                    else -> "加入稍后播放失败"
                })
            }
            // 卡片之间的间距：在 root 上直接设置 topMargin
            (root.layoutParams as? RecyclerView.LayoutParams)?.let {
                it.topMargin = if (bindingAdapterPosition == 0) 0 else dp(12)
                root.layoutParams = it
            }
        }
    }

    /**
     * 构建单个历史卡片外壳视图，供 ViewHolder 复用。
     * 使用 setId 分配控件 id，避免 findViewById 中依赖 R.id 资源常量。
     */
    private fun buildCardShell(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val thumbHeight = dp(136)
        val thumb = ImageView(context).apply {
            id = ID_THUMB
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(120, 0, 0, 0)) }
        }
        row.addView(thumb, LinearLayout.LayoutParams((thumbHeight * 16f / 9f).toInt(), thumbHeight))

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(14) }
        }
        info.addView(TextView(context).apply {
            id = ID_TITLE
            textSize = 17f; setTextColor(Color.WHITE)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(context).apply {
            id = ID_SUBTITLE
            textSize = 13f; setTextColor(Color.rgb(200, 205, 215)); setPadding(0, dp(6), 0, 0)
        })
        info.addView(TextView(context).apply {
            id = ID_POS
            textSize = 13f; setTextColor(Color.rgb(180, 195, 220)); setPadding(0, dp(4), 0, dp(6))
        })
        info.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = ID_PROGRESS
            max = 100
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(6)))
        row.addView(info)

        val btnCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(200), LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(14) }
        }
        val playBtn = focusButton("重新播放").apply {
            id = ID_BTN_PLAY
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40))
        }
        val favBtn = focusButton("加入收藏").apply {
            id = ID_BTN_FAV
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) }
        }
        val laterBtn = focusButton("稍后播放").apply {
            id = ID_BTN_LATER
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(8) }
        }
        btnCol.addView(playBtn); btnCol.addView(favBtn); btnCol.addView(laterBtn)
        row.addView(btnCol)
        return row
    }

    private fun launchPlayer(item: PlaybackController.HistoryItem, startPositionMs: Long) {
        try {
            PlaybackController.recordPlaybackHistory(item.uri, item.title, item.source, PlaybackController.currentArtworkUrl())
        } catch (_: Throwable) { /* 记录历史失败不影响播放启动 */ }
        val intent = Intent(context, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PlayerActivity.EXTRA_URI, item.uri)
            putExtra(PlayerActivity.EXTRA_TITLE, item.title)
            putExtra(PlayerActivity.EXTRA_SOURCE, item.source)
            if (startPositionMs > 0) putExtra(PlayerActivity.EXTRA_START_POSITION, startPositionMs)
        }
        try { context.startActivity(intent) } catch (t: Throwable) { toast("启动播放失败：${t.message}") }
    }

    private fun addToFavorites(item: PlaybackController.HistoryItem) {
        pageScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    fav.addToDefault(
                        title = item.title.ifBlank { item.uri },
                        uri = item.uri,
                        source = item.source.ifBlank { "history" },
                        thumbPath = item.displayThumbPath(),
                        durationMs = item.durationMs
                    )
                }.getOrElse { FavoritesStore.OpResult.INVALID }
            }
            val msg = when (r) {
                FavoritesStore.OpResult.SUCCESS -> "已加入默认收藏合集"
                FavoritesStore.OpResult.LIMIT_TOTAL -> "收藏总数已满，无法继续添加"
                FavoritesStore.OpResult.LIMIT_COLLECTION_ITEMS -> "默认合集条目已满"
                FavoritesStore.OpResult.LIMIT_COLLECTIONS -> "合集数量已满"
                FavoritesStore.OpResult.INVALID -> "参数无效，无法加入收藏"
                FavoritesStore.OpResult.NOT_FOUND -> "默认合集未找到"
                FavoritesStore.OpResult.NOT_ALLOWED -> "默认合集操作被拒"
                FavoritesStore.OpResult.FAILED -> "写入失败，请稍后再试"
            }
            toast(msg)
        }
    }

    private fun confirmClear() {
        val palette = ThemeManager.currentPalette(context)
        // 外层面板：主题渐变背景 + 暖黄描边（禁止纯深色实色背景）
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        // 内容区：按弹窗设计规范设置 4dp 内边距
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        // 顶部标题栏：圆形贴纸（fg_sticker_circle_border）+ 标题
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_kazama)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "清空历史"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        content.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        content.addView(TextView(context).apply {
            text = "确认清空所有历史播放记录？清空后将无法恢复（不影响收藏）。"
            textSize = 14f
            setTextColor(Color.argb(224, 255, 255, 255))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        val cancelBtn = dialogButton("取消") { dialog.dismiss() }
        val okBtn = dialogButton("确认") {
            pageScope.launch {
                withContext(Dispatchers.IO) {
                    try { PlaybackController.clearHistoryAll() } catch (_: Throwable) {
                        try { PlaybackController.history().forEach { PlaybackController.removeHistoryByUri(it.uri) } } catch (_: Throwable) {}
                    }
                }
                dialog.dismiss(); renderList(); toast("历史已清空")
            }
        }
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(okBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }
        content.addView(actionRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        panel.addView(content, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        dialog.show()
        dialog.window?.setLayout(dp(460), LayoutParams.WRAP_CONTENT)
        // 默认焦点落在「取消」，防止误操作
        cancelBtn.post { cancelBtn.requestFocus() }
    }

    /** 统一 dialogButton 样式工厂：默认态浅灰文字+浅灰边框+深灰半透明背景，焦点态仅边框变暖黄并接入 FocusFxHelper */
    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        fun refresh(focused: Boolean) {
            setTextColor(Color.argb(235, 245, 245, 245))
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

    // -------------------------------------------------------------- 辅助
    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0L)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.getDefault(), "%02d:%02d", m, sec)
    }

    private fun cardBg() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(180, 22, 26, 34))
        setStroke(dp(1), Color.argb(60, 255, 255, 255))
    }

    private fun focusButton(initial: String): TextView {
        val tv = TextView(context)
        tv.text = initial; tv.textSize = 15f; tv.setTextColor(Color.rgb(224, 228, 236))
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true; tv.isFocusableInTouchMode = true; tv.isClickable = true
        val bg = { focused: Boolean ->
            GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(180, 24, 28, 36))
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(120, 200, 200, 210))
            }
        }
        tv.background = bg(false)
        tv.setOnFocusChangeListener { v, has ->
            v.background = bg(has)
            (v as TextView).setTextColor(if (has) WARM else Color.rgb(224, 228, 236))
        }
        return tv
    }
    private fun toast(s: String) { Toast.makeText(context, s, Toast.LENGTH_SHORT).show() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val WARM = Color.rgb(245, 196, 81)

        // 自定义 View id，避免 R.id 资源冲突
        private const val ID_THUMB = 0x7f090001
        private const val ID_TITLE = 0x7f090002
        private const val ID_SUBTITLE = 0x7f090003
        private const val ID_POS = 0x7f090004
        private const val ID_PROGRESS = 0x7f090005
        private const val ID_BTN_PLAY = 0x7f090006
        private const val ID_BTN_FAV = 0x7f090007
        private const val ID_BTN_LATER = 0x7f090008
    }
}
