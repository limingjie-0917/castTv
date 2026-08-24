package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.dlna.DlnaRendererService
import com.bd.casttv.dlna.PlaybackController
import com.bd.casttv.douyin.DouyinCastHistoryStore
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.settings.DouyinDeviceGroup
import com.bd.casttv.settings.DouyinDeviceGroups
import com.bd.casttv.settings.Settings
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.util.Thumbnails
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抖音投屏专属页面。
 *
 * 内容区左右两栏（宽度 1:4）：
 * - 左栏：设备名称组列表，展示 10 组抖音兼容身份，点击切换当前使用组；
 * - 右栏：时间线，最顶部为「正在播放」条目（无正在播放时隐藏），之后按时间倒序
 *        展示 [DouyinCastHistoryStore] 中的历史记录，每条支持删除。
 */
class DouyinCastPage(context: Context) : BasePage(context) {
    override val pageId = Settings.PAGE_ID_DOUYIN_CAST
    override val pageTitle = "抖音投屏"
    override val pageIconRes = R.drawable.ic_history_tv
    override val enablePageScroll = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true

    private val settingsStore by lazy { Settings(context.applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { renderTimelineAndSchedule() }
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val groupsListContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val timelineAdapter = TimelineAdapter()
    private val timelineRecycler = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        adapter = timelineAdapter
        overScrollMode = View.OVER_SCROLL_NEVER
        setHasFixedSize(false)
        clipChildren = false
        clipToPadding = false
        itemAnimator = null
    }
    private val emptyTimeline = TextView(context).apply {
        textSize = 15f; setTextColor(Color.parseColor("#B0B4BE"))
        gravity = Gravity.CENTER; visibility = View.GONE
    }
    private val rightSubtitle = TextView(context).apply {
        textSize = 13f; setTextColor(Color.parseColor("#A0A4AE"))
    }

    private companion object {
        const val TIMELINE_THUMB_TAG = "douyin_timeline_thumb"
    }

    init {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(24), dpi(6), dpi(24), dpi(12))
            weightSum = 5f
        }

        // 左栏：设备组列表（1/5）
        val leftPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(10), dpi(10), dpi(10), dpi(10))
            background = GradientDrawable().apply {
                cornerRadius = dpi(16).toFloat()
                setColor(Color.parseColor("#331A1A1E"))
                setStroke(dpi(1), Color.parseColor("#33FFFFFF"))
            }
        }
        leftPanel.addView(TextView(context).apply {
            text = "设备名称组"; textSize = 16f; setTextColor(Color.WHITE)
        })
        leftPanel.addView(TextView(context).apply {
            text = "切换后请到手机抖音选择新名称"; textSize = 12f
            setTextColor(Color.parseColor("#A0A4AE"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4) })
        val leftScroll = ScrollView(context).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        leftScroll.addView(groupsListContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        leftPanel.addView(leftScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dpi(10) })

        outer.addView(leftPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        // 右栏：时间线（4/5）
        val rightPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
            background = GradientDrawable().apply {
                cornerRadius = dpi(16).toFloat()
                setColor(Color.parseColor("#331A1A1E"))
                setStroke(dpi(1), Color.parseColor("#33FFFFFF"))
            }
        }
        val rightHeader = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(context).apply {
            text = "抖音投屏时间线"; textSize = 18f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val clearHistoryBtn = buttonView("🧹 清空记录") { onClearHistoryClicked() }
        titleRow.addView(clearHistoryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(34)).apply {
            leftMargin = dpi(12)
            clearHistoryBtn.setPadding(dpi(14), 0, dpi(14), 0)
        })
        rightHeader.addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rightHeader.addView(rightSubtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4) })
        rightPanel.addView(rightHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rightScroll = FrameLayout(context)
        rightScroll.addView(timelineRecycler, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        rightScroll.addView(emptyTimeline, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(80); gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL })
        rightPanel.addView(rightScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dpi(10) })

        outer.addView(rightPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 4f).apply { leftMargin = dpi(8) })

        contentContainer.addView(outer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onEnter() {
        renderAll()
        focusFirstDeviceGroup()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 3000L)
    }

    override fun focusToFirstContent(): Boolean {
        renderAll()
        return focusFirstDeviceGroup()
    }

    override fun onLeave() { handler.removeCallbacks(refresh) }

    fun refreshTimelineAfterPlayerReturn() {
        renderTimelineAndSchedule()
    }

    private fun renderAll() {
        renderGroups()
        renderTimeline()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 3000L)
    }

    private fun renderTimelineAndSchedule() {
        renderTimeline()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 3000L)
    }

    private fun focusFirstDeviceGroup(): Boolean {
        val target = groupsListContainer.getChildAt(0) ?: return false
        return if (target.requestFocus()) true else {
            target.post { target.requestFocus() }
            false
        }
    }

    private fun focusDeviceGroupById(groupId: String): Boolean {
        for (i in 0 until groupsListContainer.childCount) {
            val target = groupsListContainer.getChildAt(i)
            if (target.tag == groupId) {
                return if (target.requestFocus()) true else {
                    target.post { target.requestFocus() }
                    false
                }
            }
        }
        return false
    }

    private fun focusFirstTimelineThumb(): Boolean {
        val holder = timelineRecycler.findViewHolderForAdapterPosition(0) as? TimelineViewHolder
        val target = holder?.thumbBox
        if (target != null) {
            return if (target.requestFocus()) true else {
                target.post { target.requestFocus() }
                false
            }
        }
        // 尚未布局：滚动到 0 后延迟请求焦点
        timelineRecycler.scrollToPosition(0)
        timelineRecycler.post {
            val h2 = timelineRecycler.findViewHolderForAdapterPosition(0) as? TimelineViewHolder
            h2?.thumbBox?.requestFocus()
        }
        return timelineAdapter.itemCount > 0
    }

    private fun focusAdjacentDeviceGroup(current: View, offset: Int): Boolean {
        val currentIndex = groupsListContainer.indexOfChild(current)
        if (currentIndex < 0) return false
        val targetIndex = currentIndex + offset
        if (targetIndex !in 0 until groupsListContainer.childCount) {
            BoundaryFocusHandler.shake(current)
            return true
        }
        val target = groupsListContainer.getChildAt(targetIndex) ?: return false
        return target.requestFocus()
    }

    private fun findTaggedView(view: View, tag: String): View? {
        if (view.tag == tag && view.isFocusable && view.visibility == View.VISIBLE) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTaggedView(view.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    // -------------------- 左栏：设备组列表 --------------------

    private fun renderGroups() {
        groupsListContainer.removeAllViews()
        val currentId = settingsStore.douyinDeviceGroupId
        val sorted = DouyinDeviceGroups.ALL.sortedWith(compareByDescending { it.id == currentId })
        sorted.forEachIndexed { idx, group ->
            val selected = group.id == currentId
            val row = buildGroupRow(group, selected)
            groupsListContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpi(if (idx == 0) 0 else 8)
            })
        }
    }

    private fun buildGroupRow(group: DouyinDeviceGroup, selected: Boolean): View {
        lateinit var row: LinearLayout
        lateinit var nameView: TextView
        fun refreshRow(focused: Boolean) {
            row.background = GradientDrawable().apply {
                cornerRadius = dpi(12).toFloat()
                setColor(if (selected) Color.parseColor("#33FFD07A") else Color.parseColor("#22FFFFFF"))
                setStroke(dpi(if (focused) 2 else 1), if (focused) Color.parseColor("#FFD07A") else Color.parseColor("#33FFFFFF"))
            }
            nameView.setTextColor(if (selected) Color.parseColor("#FFD07A") else Color.WHITE)
        }
        row = LinearLayout(context).apply {
            tag = group.id
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
            isFocusable = true; isFocusableInTouchMode = true
            setOnFocusChangeListener { _, hasFocus -> refreshRow(hasFocus) }
            setOnKeyListener { v, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        focusAdjacentDeviceGroup(v, -1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusAdjacentDeviceGroup(v, 1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!focusFirstTimelineThumb()) BoundaryFocusHandler.shake(v)
                        true
                    }
                    else -> false
                }
            }
        }
        if (selected) {
            row.addView(TextView(context).apply {
                text = "使用中"; textSize = 11f
                setTextColor(Color.parseColor("#1A1A1E"))
                setPadding(dpi(6), dpi(2), dpi(6), dpi(2))
                background = GradientDrawable().apply {
                    cornerRadius = dpi(8).toFloat()
                    setColor(Color.parseColor("#FFD07A"))
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(5) })
        }
        nameView = TextView(context).apply {
            text = group.label; textSize = 15f
        }
        row.addView(nameView)
        val member = group.memberAt(group.defaultIndex)
        row.addView(TextView(context).apply {
            text = member.friendlyName; textSize = 12f
            setTextColor(Color.parseColor("#B0B4BE"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4) })
        refreshRow(false)

        row.setOnClickListener {
            if (group.id == settingsStore.douyinDeviceGroupId && settingsStore.douyinCastEnabled) {
                toastMsg("当前已在使用「${group.label}」")
                return@setOnClickListener
            }
            settingsStore.douyinDeviceGroupId = group.id
            settingsStore.douyinDeviceMemberIndex = group.defaultIndex
            if (!settingsStore.douyinCastEnabled) settingsStore.douyinCastEnabled = true
            triggerDlnaIdentityRestart()
            toastMsg("已切换为「${group.label} · ${member.friendlyName}」，请在手机抖音重新选择该设备名")
            renderAll()
            post { focusDeviceGroupById(group.id) }
        }
        return row
    }

    // -------------------- 右栏：时间线 --------------------

    private var lastTimelineSignature: String = ""

    private data class TimelineItem(
        val kind: Int, // 0=now playing, 1=history
        val timeLabel: String,
        val isLast: Boolean,
        val title: String,
        val uri: String,
        val thumbPath: String?,
        val historyRef: DouyinCastHistoryStore.Item? = null
    )

    private var timelineItems: List<TimelineItem> = emptyList()

    private fun renderTimeline() {
        val threshold = settingsStore.douyinHistoryThresholdSec
        rightSubtitle.text = "当前 DLNA 名：${settingsStore.dlnaDeviceName}   ·   播放满 ${threshold} 秒后记录"

        val nowPlaying = buildNowPlayingItemOrNull()
        val history = try { DouyinCastHistoryStore.list(context) } catch (_: Throwable) { emptyList() }
            .let { list -> if (nowPlaying != null) list.filter { it.uri != nowPlaying.uri } else list }

        // 内容签名：只有真正变化时才刷新 RecyclerView，避免 3 秒周期刷新期间反复重建视图。
        val signature = buildString {
            append(nowPlaying?.uri ?: "-"); append('#')
            append(nowPlaying?.title ?: "-"); append('#')
            append(threshold); append('#')
            append(settingsStore.dlnaDeviceName); append('#')
            history.forEach { append(it.uri); append('|'); append(it.firstPlayedAt); append(';') }
        }
        if (signature == lastTimelineSignature && timelineAdapter.itemCount > 0) {
            emptyTimeline.visibility = if (nowPlaying == null && history.isEmpty()) View.VISIBLE else View.GONE
            return
        }
        lastTimelineSignature = signature

        if (nowPlaying == null && history.isEmpty()) {
            timelineItems = emptyList()
            timelineAdapter.submit(timelineItems)
            emptyTimeline.text = "还没有抖音投屏记录\n请在手机抖音选择「${settingsStore.dlnaDeviceName}」投屏，播放满 ${threshold} 秒后自动记录～"
            emptyTimeline.visibility = View.VISIBLE
            return
        }
        emptyTimeline.visibility = View.GONE

        val list = mutableListOf<TimelineItem>()
        val total = (if (nowPlaying != null) 1 else 0) + history.size
        var index = 0
        if (nowPlaying != null) {
            list.add(
                TimelineItem(
                    kind = 0,
                    timeLabel = "正在播放",
                    isLast = (index == total - 1),
                    title = nowPlaying.title.ifBlank { "抖音投屏中" },
                    uri = nowPlaying.uri,
                    thumbPath = nowPlaying.thumbPath
                )
            )
            index++
        }
        history.forEach { item ->
            val ts = try { timeFmt.format(Date(item.firstPlayedAt)) } catch (_: Throwable) { "--" }
            list.add(
                TimelineItem(
                    kind = 1,
                    timeLabel = ts,
                    isLast = (index == total - 1),
                    title = item.title.ifBlank { "抖音投屏视频" },
                    uri = item.uri,
                    thumbPath = item.artworkPath.takeIf { it.isNotBlank() },
                    historyRef = item
                )
            )
            index++
        }
        timelineItems = list
        timelineAdapter.submit(list)
    }

    /** RecyclerView Adapter：只渲染可见项，滑动时复用 ViewHolder，避免主线程一次性重建全部卡片。 */
    private inner class TimelineAdapter : RecyclerView.Adapter<TimelineViewHolder>() {
        private val items = mutableListOf<TimelineItem>()

        fun submit(newItems: List<TimelineItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimelineViewHolder {
            return TimelineViewHolder(FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                clipChildren = false
            })
        }

        override fun onBindViewHolder(holder: TimelineViewHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class TimelineViewHolder(private val root: FrameLayout) : RecyclerView.ViewHolder(root) {
        var thumbBox: View? = null
            private set

        fun bind(item: TimelineItem) {
            root.removeAllViews()
            val card = if (item.kind == 0) {
                buildVideoCard(item.title, item.uri, item.thumbPath, highlighted = true, showDelete = false, onDelete = {})
            } else {
                buildVideoCard(item.title, item.uri, item.thumbPath, highlighted = false, showDelete = true, onDelete = {
                    item.historyRef?.let { confirmDelete(it) }
                })
            }
            val row = buildTimelineRow(item.timeLabel, item.kind == 0, item.isLast, card)
            root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            thumbBox = findTaggedView(card, TIMELINE_THUMB_TAG)
        }
    }

    /** 时间线单行：左侧 rail（时间点圆点 + 连接竖线） + 时间标签 + 卡片。 */
    private fun buildTimelineRow(timeLabel: String, isNow: Boolean, isLast: Boolean, card: View): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpi(if (isLast) 0 else 14))
        }
        // 左侧 rail: 上竖线（除首行）+ 圆点 + 下竖线（除末行）
        val rail = FrameLayout(context)
        val topLine = View(context).apply {
            setBackgroundColor(Color.parseColor("#33FFD07A"))
        }
        val botLine = View(context).apply {
            setBackgroundColor(Color.parseColor("#33FFD07A"))
            visibility = if (isLast) View.INVISIBLE else View.VISIBLE
        }
        rail.addView(topLine, FrameLayout.LayoutParams(dpi(2), dpi(12), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 0 })
        rail.addView(botLine, FrameLayout.LayoutParams(dpi(2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(20) })
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isNow) Color.parseColor("#FFD07A") else Color.parseColor("#8AFFFFFF"))
                if (isNow) setStroke(dpi(2), Color.parseColor("#66FFD07A"))
            }
        }
        rail.addView(dot, FrameLayout.LayoutParams(dpi(12), dpi(12), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(10) })
        row.addView(rail, LinearLayout.LayoutParams(dpi(18), ViewGroup.LayoutParams.MATCH_PARENT))

        // 时间标签
        val label = TextView(context).apply {
            text = timeLabel; textSize = 12f
            setTextColor(if (isNow) Color.parseColor("#FFD07A") else Color.parseColor("#A0A4AE"))
            setPadding(dpi(8), dpi(8), dpi(4), 0)
            gravity = Gravity.TOP or Gravity.START
            minWidth = dpi(96)
        }
        row.addView(label, LinearLayout.LayoutParams(dpi(112), ViewGroup.LayoutParams.WRAP_CONTENT))

        row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private data class NowPlaying(
        val uri: String,
        val title: String,
        val thumbPath: String?
    )

    private fun buildNowPlayingItemOrNull(): NowPlaying? {
        val uri = PlaybackController.currentUri
        if (uri.isBlank() || !PlaybackController.currentIsDouyinCast) return null
        val title = PlaybackController.currentTitle
        val thumb = try { PlaybackController.currentArtworkPath() ?: PlaybackController.currentThumbPath() } catch (_: Throwable) { null }
        return NowPlaying(uri, title, thumb)
    }

    private fun buildNowPlayingCard(np: NowPlaying): View {
        return buildVideoCard(
            title = np.title.ifBlank { "抖音投屏中" },
            uri = np.uri,
            thumbPath = np.thumbPath,
            highlighted = true,
            showDelete = false,
            onDelete = {}
        )
    }

    private fun buildHistoryCard(item: DouyinCastHistoryStore.Item): View {
        return buildVideoCard(
            title = item.title.ifBlank { "抖音投屏视频" },
            uri = item.uri,
            thumbPath = item.artworkPath.takeIf { it.isNotBlank() },
            highlighted = false,
            showDelete = true,
            onDelete = { confirmDelete(item) }
        )
    }

    private fun buildVideoCard(
        title: String,
        uri: String,
        thumbPath: String?,
        highlighted: Boolean,
        showDelete: Boolean,
        onDelete: () -> Unit
    ): View {
        val thumbHeight = dpi(84)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12), dpi(10), dpi(12), dpi(10))
            minimumHeight = thumbHeight + dpi(20)
            background = GradientDrawable().apply {
                cornerRadius = dpi(14).toFloat()
                setColor(Color.parseColor(if (highlighted) "#33FFD07A" else "#332A2A32"))
                setStroke(dpi(1), Color.parseColor(if (highlighted) "#66FFD07A" else "#33FFFFFF"))
            }
        }

        val thumbWidth = (thumbHeight * 16f / 9f).toInt()
        val thumbBox = FrameLayout(context).apply {
            tag = TIMELINE_THUMB_TAG
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val thumb = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = dpi(8).toFloat()
                setColor(Color.parseColor("#22FFFFFF"))
            }
        }
        Thumbnails.load(thumb, thumbPath)
        thumbBox.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        thumbBox.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_play_overlay_circle)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isFocusable = false
            isClickable = false
        }, FrameLayout.LayoutParams(dpi(42), dpi(42), Gravity.CENTER))
        card.addView(thumbBox, LinearLayout.LayoutParams(thumbWidth, thumbHeight).apply { rightMargin = dpi(14) })

        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = if (showDelete) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val playBtn = buttonView(if (highlighted) "▶ 全屏播放" else "▶ 播放", blockRight = !showDelete) { launchPlayer(uri, title) }
        buttons.addView(playBtn, LinearLayout.LayoutParams(0, dpi(38), 1f).apply { rightMargin = if (showDelete) dpi(8) else 0 })
        if (showDelete) {
            val delBtn = buttonView("🗑 删除", danger = true, rightToRoot = true) { onDelete() }
            buttons.addView(delBtn, LinearLayout.LayoutParams(0, dpi(38), 1f))
        }
        info.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(38)))
        card.addView(info, LinearLayout.LayoutParams(0, thumbHeight, 1f))
        bindImmediateAction(thumbBox, onFocusChange = { hasFocus -> refreshThumbFocus(thumbBox, hasFocus) }, onKeyDown = { keyCode, _ ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    playBtn.requestFocus()
                    true
                }
                else -> false
            }
        }) { launchPlayer(uri, title) }
        return card
    }

    private fun onClearHistoryClicked() {
        try {
            DouyinCastHistoryStore.clearAll(context)
        } catch (_: Throwable) {}
        renderTimeline()
        toastMsg("已清空所有抖音投屏记录")
    }

    private fun confirmDelete(item: DouyinCastHistoryStore.Item) {
        val warm = Color.rgb(245, 196, 81)
        val lightText = 0xFFEEE8DA.toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(24), dpi(20), dpi(24), dpi(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                ThemeManager.currentPalette(context).dialogTitleGradient
            ).apply {
                cornerRadius = dpi(18).toFloat()
                setStroke(dpi(2), warm)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dpi(44), dpi(44)).apply { rightMargin = dpi(12) })
        header.addView(TextView(context).apply {
            text = "删除记录"; textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(12) })

        panel.addView(TextView(context).apply {
            text = "确定要删除该条抖音投屏记录吗？\n\n${item.title.ifBlank { "抖音投屏视频" }}"
            textSize = 14f
            setTextColor(lightText)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(18) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.RIGHT
        }
        val cancelBtn = dialogActionButton("取消")
        val confirmBtn = dialogActionButton("删除")
        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            try { DouyinCastHistoryStore.removeByUri(context, item.uri) } catch (_: Throwable) {}
            toastMsg("已删除该条记录")
            renderTimeline()
            dialog.dismiss()
        }
        row.addView(cancelBtn, LinearLayout.LayoutParams(dpi(96), dpi(44)).apply { rightMargin = dpi(10) })
        row.addView(confirmBtn, LinearLayout.LayoutParams(dpi(96), dpi(44)))
        panel.addView(row)

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            confirmBtn.requestFocus()
        }
        try {
            dialog.show()
            dialog.window?.setLayout(dpi(460), ViewGroup.LayoutParams.WRAP_CONTENT)
        } catch (_: Throwable) {
            try { DouyinCastHistoryStore.removeByUri(context, item.uri) } catch (_: Throwable) {}
            renderTimeline()
        }
    }

    private fun dialogActionButton(label: String): TextView {
        val warm = Color.rgb(245, 196, 81)
        return TextView(context).apply {
            text = label; textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            fun refresh(focused: Boolean) {
                setTextColor(if (focused) warm else Color.parseColor("#F1F1F5"))
                background = GradientDrawable().apply {
                    cornerRadius = dpi(8).toFloat()
                    setColor(Color.argb(51, 27, 31, 38))
                    setStroke(dpi(if (focused) 2 else 1), if (focused) warm else Color.parseColor("#66FFFFFF"))
                }
            }
            refresh(false)
            setOnFocusChangeListener { _, hasFocus -> refresh(hasFocus) }
        }
    }

    // -------------------- 通用工具 --------------------

    private fun buttonView(label: String, danger: Boolean = false, blockRight: Boolean = false, rightToRoot: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label; textSize = 14f
            gravity = Gravity.CENTER
            isFocusable = true; isFocusableInTouchMode = true
            fun refresh(focused: Boolean) {
                setTextColor(if (danger) Color.parseColor("#FF8B8B") else Color.parseColor("#F1F1F5"))
                background = GradientDrawable().apply {
                    cornerRadius = dpi(20).toFloat()
                    setColor(Color.parseColor("#33FFFFFF"))
                    setStroke(dpi(if (focused) 2 else 1), if (focused) Color.parseColor("#FFD07A") else if (danger) Color.parseColor("#66FF8B8B") else Color.parseColor("#66FFD07A"))
                }
            }
            refresh(false)
            bindImmediateAction(this, onFocusChange = { hasFocus -> refresh(hasFocus) }, onKeyDown = { keyCode, _ ->
                when {
                    rightToRoot && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        focusToRoot()
                        true
                    }
                    blockRight && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        BoundaryFocusHandler.shake(this)
                        true
                    }
                    else -> false
                }
            }) { onClick() }
        }
    }

    private fun refreshThumbFocus(thumbBox: FrameLayout, focused: Boolean) {
        thumbBox.foreground = GradientDrawable().apply {
            cornerRadius = dpi(8).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dpi(if (focused) 3 else 0), if (focused) Color.parseColor("#FFD07A") else Color.TRANSPARENT)
        }
    }

    private fun bindImmediateAction(
        view: View,
        onFocusChange: ((Boolean) -> Unit)? = null,
        onKeyDown: ((Int, KeyEvent) -> Boolean)? = null,
        action: () -> Unit
    ) {
        view.setOnClickListener { action() }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && onKeyDown?.invoke(keyCode, event) == true) return@setOnKeyListener true
            val isConfirmKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!isConfirmKey) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                action()
            }
            true
        }
        view.setOnFocusChangeListener { _, hasFocus ->
            onFocusChange?.invoke(hasFocus)
            handler.removeCallbacks(refresh)
            if (!hasFocus) handler.postDelayed(refresh, 3000L)
        }
        onFocusChange?.invoke(false)
    }

    private fun launchPlayer(uri: String, title: String) {
        if (uri.isBlank()) { toastMsg("暂无播放地址"); return }
        try {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URI, uri)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SOURCE, "抖音投屏记录")
                putExtra(PlayerActivity.EXTRA_RETURN_SKIP_PAGE_RELOAD, true)
                putExtra(com.bd.casttv.ui.framework.NewMainActivity.EXTRA_OPEN_PAGE_ID, pageId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) { toastMsg("无法打开播放器") }
    }

    private fun triggerDlnaIdentityRestart() {
        try {
            val intent = Intent(context, DlnaRendererService::class.java)
                .setAction(DlnaRendererService.ACTION_RESTART_IDENTITY)
            context.startService(intent)
        } catch (_: Throwable) {}
        try {
            PlaybackController.douyinHistoryThresholdMs = settingsStore.douyinHistoryThresholdSec * 1000L
        } catch (_: Throwable) {}
    }

    private fun toastMsg(msg: String) {
        try { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) {}
    }

    private fun dpi(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
