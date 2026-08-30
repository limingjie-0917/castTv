package com.bd.casttv.ui.framework.pages

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.dlna.DlnaRendererService
import com.bd.casttv.dlna.LanDeviceScanner
import com.bd.casttv.dlna.PlaybackController
import com.bd.casttv.douyin.DouyinCastHistoryStore
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.settings.CustomDouyinDeviceGroupsStore
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
            setPadding(dpi(2), dpi(2), dpi(2), dpi(2))
            weightSum = 5f
        }

        // 左栏：设备组列表（1/5）
        val leftPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(8), dpi(8), dpi(8), dpi(8))
            background = GradientDrawable().apply {
                cornerRadius = dpi(16).toFloat()
                setColor(Color.parseColor("#331A1A1E"))
                setStroke(dpi(1), Color.parseColor("#33FFFFFF"))
            }
        }
        // 左栏标题区：第一行（主标题 + 添加按钮）+ 第二行（副标题 整行）
        val titleHeader = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val leftTitleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        leftTitleRow.addView(TextView(context).apply {
            text = "设备名称组"; textSize = 16f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val addBtn = buildAddDeviceGroupButton()
        leftTitleRow.addView(addBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dpi(28)).apply { leftMargin = dpi(8) })
        titleHeader.addView(leftTitleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        titleHeader.addView(TextView(context).apply {
            text = "切换后请到手机抖音选择新名称"; textSize = 12f
            setTextColor(Color.parseColor("#A0A4AE"))
            maxLines = 1
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(4) })
        leftPanel.addView(titleHeader, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val leftScroll = ScrollView(context).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        leftScroll.addView(groupsListContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        leftPanel.addView(leftScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dpi(10) })

        outer.addView(leftPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.3f))

        // 右栏：时间线（4/5）
        val rightPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(10), dpi(8), dpi(10), dpi(8))
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

        outer.addView(rightPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3.7f).apply { leftMargin = dpi(4) })

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
            if (offset < 0) {
                // 顶部边界（第一个设备组按「上」）：不拦截、不 shake，
                // 返回 false 让 KeyEvent 走系统默认焦点搜索 → 落到列表上方的「＋ 添加」按钮
                return false
            }
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
        val allGroups = DouyinDeviceGroups.allIncludingCustom(context)
        // 当前使用组置顶，其他按原顺序
        val sorted = allGroups.sortedWith(compareByDescending { it.id == currentId })
        sorted.forEachIndexed { idx, group ->
            val selected = group.id == currentId
            val isCustom = DouyinDeviceGroups.isCustomGroupId(context, group.id)
            val row = buildGroupRow(group, selected, isCustom)
            groupsListContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpi(if (idx == 0) 0 else 8)
            })
        }
    }

    private fun buildGroupRow(group: DouyinDeviceGroup, selected: Boolean, isCustom: Boolean): View {
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
                    // 向上/向下直接返回 focusAdjacentDeviceGroup 的结果：
                    // 列表内正常移动 = true（已消费）；顶部边界 = false（放行给系统默认焦点搜索，
                    // 让焦点落到列表上方的「＋ 添加」按钮，而不是被 shake 拦截吞掉）
                    KeyEvent.KEYCODE_DPAD_UP -> focusAdjacentDeviceGroup(v, -1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> focusAdjacentDeviceGroup(v, 1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!focusFirstTimelineThumb()) BoundaryFocusHandler.shake(v)
                        true
                    }
                    else -> false
                }
            }
        }
        // 顶部角标行：使用中 + 克隆 + 删除按钮
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (selected || isCustom) View.VISIBLE else View.GONE
        }
        if (selected) {
            topBar.addView(TextView(context).apply {
                text = "使用中"; textSize = 11f
                setTextColor(Color.parseColor("#1A1A1E"))
                setPadding(dpi(6), dpi(2), dpi(6), dpi(2))
                background = GradientDrawable().apply {
                    cornerRadius = dpi(8).toFloat()
                    setColor(Color.parseColor("#FFD07A"))
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(6) })
        }
        if (isCustom) {
            topBar.addView(TextView(context).apply {
                text = "克隆"; textSize = 11f
                setTextColor(Color.parseColor("#FFD07A"))
                setPadding(dpi(6), dpi(2), dpi(6), dpi(2))
                background = GradientDrawable().apply {
                    cornerRadius = dpi(8).toFloat()
                    setColor(Color.parseColor("#22FFD07A"))
                    setStroke(dpi(1), Color.parseColor("#66FFD07A"))
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(6) })
        }
        // 删除按钮（仅克隆组）—— 占据右侧空间
        if (isCustom) {
            val spacer = View(context)
            topBar.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
            val delBtn = TextView(context).apply {
                text = "删除"; textSize = 11f
                setTextColor(Color.parseColor("#FF8B8B"))
                setPadding(dpi(8), dpi(2), dpi(8), dpi(2))
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = dpi(8).toFloat()
                    setColor(Color.parseColor("#22FF8B8B"))
                    setStroke(dpi(1), Color.parseColor("#66FF8B8B"))
                }
                fun refresh(focused: Boolean) {
                    background = GradientDrawable().apply {
                        cornerRadius = dpi(8).toFloat()
                        setColor(Color.parseColor("#33FF8B8B"))
                        setStroke(dpi(if (focused) 2 else 1), Color.parseColor("#FF8B8B"))
                    }
                }
                refresh(false)
                setOnFocusChangeListener { _, hasFocus -> refresh(hasFocus) }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        confirmDeleteCustomGroup(group)
                        true
                    } else false
                }
            }
            topBar.addView(delBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (topBar.childCount > 0) {
            row.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpi(5)
            })
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

    private fun confirmDeleteCustomGroup(group: DouyinDeviceGroup) {
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
        panel.addView(TextView(context).apply {
            text = "删除克隆组"; textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(12) })
        panel.addView(TextView(context).apply {
            text = "确定要删除克隆组「${group.label}」吗？\n\n删除后该身份不再可用。"
            textSize = 14f
            setTextColor(lightText)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(18) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT }
        val cancelBtn = dialogActionButton("取消")
        val confirmBtn = dialogActionButton("删除")
        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            try { CustomDouyinDeviceGroupsStore.removeGroup(context, group.id) } catch (_: Throwable) {}
            if (settingsStore.douyinDeviceGroupId == group.id) {
                settingsStore.douyinDeviceGroupId = DouyinDeviceGroups.DEFAULT_GROUP_ID
                settingsStore.douyinDeviceMemberIndex = 0
                triggerDlnaIdentityRestart()
            }
            toastMsg("已删除克隆组「${group.label}」")
            renderAll()
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
            dialog.window?.setLayout(dpi(420), ViewGroup.LayoutParams.WRAP_CONTENT)
        } catch (_: Throwable) {}
    }

    // -------------------- 克隆真电视弹窗 --------------------

    private val deviceScanner = LanDeviceScanner()
    @Volatile private var cloneDialog: AlertDialog? = null

    private fun buildAddDeviceGroupButton(): View {
        val warm = Color.parseColor("#FFD07A")
        val silver = Color.parseColor("#C6CDD6")   // 银白描边（默认态）
        fun TextView.refresh(focused: Boolean) {
            background = GradientDrawable().apply {
                cornerRadius = dpi(14).toFloat()
                // 默认态：透明底（去背景色）+ 银白描边；焦点态：暖黄半透底 + 暖黄描边
                setColor(if (focused) Color.parseColor("#55FFD07A") else Color.TRANSPARENT)
                setStroke(dpi(if (focused) 2 else 1), if (focused) warm else silver)
            }
            setTextColor(Color.WHITE)   // 默认/焦点均为白色字体（默认态由银白描边区分）
        }
        return TextView(context).apply {
            text = "＋ 添加"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dpi(12), 0, dpi(12), 0)
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            refresh(false)
            setOnFocusChangeListener { v, hasFocus -> (v as TextView).refresh(hasFocus) }
            setOnClickListener { showCloneDeviceDialog() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                    showCloneDeviceDialog()
                    true
                } else false
            }
        }
    }

    private fun showCloneDeviceDialog() {
        if (cloneDialog?.isShowing == true) return
        val warm = Color.parseColor("#FFD07A")
        val lightText = 0xFFEEE8DA.toInt()

        // 状态
        val scanResults = mutableListOf<DouyinDeviceGroup>()
        val visibleList = mutableListOf<DouyinDeviceGroup>()
        // 选中状态：index of visibleList，-1 表示未选中
        var selectedIndex = -1
        // 添加类型：0 = 新组，1 = 加入现有组（选中项生效）
        var selectedMode = 0
        val builtinGroups = DouyinDeviceGroups.ALL
        var selectedParentGroup = builtinGroups.first()

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
        // 标题（带蜡笔小新圆形贴纸，去掉✕关闭按钮）
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dpi(44), dpi(44)).apply { rightMargin = dpi(12) })
        header.addView(TextView(context).apply {
            text = "添加设备组"; textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(12) })

        // 副标题
        panel.addView(TextView(context).apply {
            text = "扫描局域网内的可接收投屏设备，把设备的身份字段克隆到本机。"
            textSize = 11f
            setTextColor(lightText)
            maxLines = 2
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(12) })

        fun buildParentChips(row: LinearLayout, onSelect: () -> Unit) {
            builtinGroups.forEach { g ->
                val chip = TextView(context).apply {
                    text = g.label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(dpi(10), dpi(4), dpi(10), dpi(4))
                    isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                    setOnClickListener {
                        selectedParentGroup = g
                        onSelect()
                    }
                    val isSel = (selectedParentGroup.id == g.id)
                    background = GradientDrawable().apply {
                        cornerRadius = dpi(8).toFloat()
                        setColor(if (isSel) Color.parseColor("#55FFD07A") else Color.parseColor("#22FFFFFF"))
                        setStroke(dpi(if (isSel) 2 else 1), Color.parseColor(if (isSel) "#FFD07A" else "#44FFFFFF"))
                    }
                    setTextColor(if (isSel) Color.WHITE else lightText)
                    setOnFocusChangeListener { _, hasFocus ->
                        val selected = (selectedParentGroup.id == g.id)
                        background = GradientDrawable().apply {
                            cornerRadius = dpi(8).toFloat()
                            setColor(if (selected) Color.parseColor("#55FFD07A") else Color.parseColor("#22FFFFFF"))
                            setStroke(dpi(if (hasFocus || selected) 2 else 1), Color.parseColor(if (selected) "#FFD07A" else "#44FFFFFF"))
                        }
                    }
                }
                row.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(6) })
            }
        }

        // 原「左上角扫描状态行」—— 已移除（见居中 Loading 遮罩）
        // 替代：在弹窗中部覆盖 loading 遮罩（动画 + 文案），扫描结束后隐藏
        // 设备列表外裹一层 FrameLayout 方便叠放 loading 遮罩
        val listWrap = FrameLayout(context)
        // 设备列表（ScrollView + LinearLayout，最多 280dp 高度）
        val listScroll = ScrollView(context).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listScroll.addView(listContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        listWrap.addView(listScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(280)))
        panel.addView(listWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(280)).apply { bottomMargin = dpi(10) })

        // 居中 Loading 遮罩（扫描时显示在 listWrap 正中）
        val loadingMask = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val warmColor = Color.parseColor("#FFD07A")
        val spinner = ProgressBar(context, null, android.R.attr.progressBarStyle).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(warmColor)
        }
        loadingMask.addView(spinner, LinearLayout.LayoutParams(dpi(48), dpi(48)))
        val loadingText = TextView(context).apply {
            text = "正在扫描局域网设备（已发现0台）"
            textSize = 12f
            setTextColor(lightText)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        loadingMask.addView(loadingText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(10) })
        listWrap.addView(loadingMask, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        // 底部按钮：取消 / 刷新 / 添加
        val bottomRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT }
        val cancelBtn = dialogActionButton("取消")
        val refreshBtn = dialogActionButton("刷新")
        val addBtn = dialogActionButton("添加").apply {
            // 主按钮样式：金色填充
            val warmColor = Color.parseColor("#FFD07A")
            background = GradientDrawable().apply {
                cornerRadius = dpi(12).toFloat()
                setColor(Color.parseColor("#66FFD07A"))
                setStroke(dpi(2), warmColor)
            }
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setOnFocusChangeListener { _, hasFocus ->
                background = GradientDrawable().apply {
                    cornerRadius = dpi(12).toFloat()
                    setColor(if (hasFocus) Color.parseColor("#88FFD07A") else Color.parseColor("#66FFD07A"))
                    setStroke(dpi(if (hasFocus) 3 else 2), warmColor)
                }
            }
        }
        bottomRow.addView(cancelBtn, LinearLayout.LayoutParams(dpi(120), dpi(44)).apply { rightMargin = dpi(10) })
        bottomRow.addView(refreshBtn, LinearLayout.LayoutParams(dpi(120), dpi(44)).apply { rightMargin = dpi(10) })
        bottomRow.addView(addBtn, LinearLayout.LayoutParams(dpi(120), dpi(44)))
        panel.addView(bottomRow)

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        cloneDialog = dialog

        val listItemViews = mutableListOf<LinearLayout>()

        fun refreshList() {
            listContainer.removeAllViews()
            listItemViews.clear()
            visibleList.clear()
            // ⚠ 不再在 UI 层用 knownIds 过滤（LanDeviceScanner 已经为"已添加过"的设备打了 isAlreadyAdded 标记，
            // 仍显示在列表里告诉用户"已添加"。若有漏打标记，这里也不会让用户看不到扫描结果 —— 直接按全量展示。
            visibleList.addAll(scanResults)

            if (visibleList.isEmpty()) {
                listContainer.addView(TextView(context).apply {
                    text = "未发现可用设备"
                    textSize = 12f; gravity = Gravity.CENTER
                    setTextColor(lightText)
                    setPadding(0, dpi(40), 0, 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                return
            }
            // 如果之前选中的索引已越界（比如刷新后列表变小），重置
            if (selectedIndex !in 0 until visibleList.size) {
                selectedIndex = -1
                selectedMode = 0
            }
            visibleList.forEachIndexed { idx, g ->
                val member = g.memberAt(0)
                fun View.refreshRow(focused: Boolean, isSelected: Boolean) {
                    background = GradientDrawable().apply {
                        cornerRadius = dpi(10).toFloat()
                        setColor(
                            when {
                                isSelected -> Color.parseColor("#66FFD07A")
                                focused -> Color.parseColor("#55FFD07A")
                                else -> Color.parseColor("#22FFFFFF")
                            }
                        )
                        setStroke(
                            dpi(if (focused || isSelected) 2 else 1),
                            if (focused || isSelected) warm else Color.parseColor("#33FFFFFF")
                        )
                    }
                }

                // 行主体（左侧信息 + 右侧单选区域占位）
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpi(10), dpi(10), dpi(10), dpi(10))
                    isFocusable = true; isFocusableInTouchMode = true; isClickable = true
                    tag = idx
                }
                listItemViews.add(row)

                // 左侧图标：简约液晶电视（白色加粗线条 — 长方形屏幕框 + 圆角长方形线条底座）
                val tvIcon = View(context).apply {
                    // 屏幕外描边（只描边不填充）
                    val screenStroke = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpi(2).toFloat()
                        setColor(Color.TRANSPARENT)
                        setStroke(dpi(2), Color.WHITE, 0f, 0f)
                    }
                    // 底座（圆角线条方框）
                    val standStroke = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpi(2).toFloat()
                        setColor(Color.TRANSPARENT)
                        setStroke(dpi(2), Color.WHITE, 0f, 0f)
                    }
                    // LayerDrawable 3 层：index0=底座, index1=屏幕描边
                    // 用 setLayerInset 做整体偏移，不裁剪描边宽度
                    val layers = arrayOf<android.graphics.drawable.Drawable>(
                        standStroke,
                        screenStroke
                    )
                    background = LayerDrawable(layers).apply {
                        // 屏幕层：顶 2dp / 左右 0dp / 底 8dp — 保证下方 2dp 的描边正好在底线上不被裁
                        setLayerInset(1, dpi(0), dpi(2), dpi(0), dpi(8))
                        // 底座层：左右各 6dp（居中 16dp），顶 22dp，底 2dp
                        setLayerInset(0, dpi(6), dpi(22), dpi(6), dpi(2))
                    }
                }
                row.addView(tvIcon, LinearLayout.LayoutParams(dpi(28), dpi(28)).apply { rightMargin = dpi(10) })
                val infoBlock = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                // 标题行：设备名 + 本机/已添加标签
                val titleLine = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                titleLine.addView(TextView(context).apply {
                    text = member.friendlyName; textSize = 14f; maxLines = 1
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (g.isLocalDevice) {
                    val tag = TextView(context).apply {
                        text = "本机"; textSize = 10f
                        setTextColor(Color.WHITE); gravity = Gravity.CENTER
                        setPadding(dpi(6), dpi(1), dpi(6), dpi(1))
                        background = GradientDrawable().apply {
                            cornerRadius = dpi(6).toFloat()
                            setColor(Color.parseColor("#4400C853"))
                            setStroke(dpi(1), Color.parseColor("#6600C853"))
                        }
                    }
                    titleLine.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(6) })
                }
                if (g.isAlreadyAdded) {
                    val tag = TextView(context).apply {
                        text = "已添加"; textSize = 10f
                        setTextColor(Color.parseColor("#FFD07A")); gravity = Gravity.CENTER
                        setPadding(dpi(6), dpi(1), dpi(6), dpi(1))
                        background = GradientDrawable().apply {
                            cornerRadius = dpi(6).toFloat()
                            setColor(Color.parseColor("#22FFD07A"))
                            setStroke(dpi(1), Color.parseColor("#66FFD07A"))
                        }
                    }
                    titleLine.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(6) })
                }
                infoBlock.addView(titleLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                infoBlock.addView(TextView(context).apply {
                    text = "${member.manufacturer} · ${member.modelName}"
                    textSize = 10f; maxLines = 1
                    setTextColor(lightText)
                })
                row.addView(infoBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                // 右侧：仅选中时显示的两个单选框（本机/已添加禁用）
                val radioDisabled = g.isLocalDevice
                val radioBar = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    visibility = if (idx == selectedIndex) View.VISIBLE else View.GONE
                }
                // 单选：新组
                val radioNew = TextView(context).apply {
                    text = "新组"; textSize = 11f
                    setPadding(dpi(10), dpi(4), dpi(10), dpi(4))
                    isFocusable = !radioDisabled; isFocusableInTouchMode = !radioDisabled; isClickable = !radioDisabled
                    val selected = !radioDisabled && (idx == selectedIndex && selectedMode == 0)
                    background = GradientDrawable().apply {
                        cornerRadius = dpi(8).toFloat()
                        if (radioDisabled) {
                            setColor(Color.parseColor("#11FFFFFF"))
                            setStroke(dpi(1), Color.parseColor("#22FFFFFF"))
                        } else {
                            setColor(if (selected) Color.parseColor("#66FFD07A") else Color.parseColor("#1AFFFFFF"))
                            setStroke(dpi(if (selected) 2 else 1), Color.parseColor(if (selected) "#FFD07A" else "#55FFFFFF"))
                        }
                    }
                    setTextColor(when {
                        radioDisabled -> Color.parseColor("#66EEE8DA")
                        selected -> Color.WHITE
                        else -> lightText
                    })
                    if (radioDisabled) {
                        text = "本机不可克隆"
                    }
                    setOnClickListener {
                        if (radioDisabled) { toastMsg("本机设备不可克隆"); return@setOnClickListener }
                        if (idx != selectedIndex) return@setOnClickListener
                        selectedMode = 0
                        refreshList()
                    }
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            if (radioDisabled) { toastMsg("本机设备不可克隆"); return@setOnKeyListener true }
                            if (idx == selectedIndex) {
                                selectedMode = 0
                                refreshList()
                            }
                            true
                        } else false
                    }
                }
                // 单选：加入现有组
                val radioJoin = TextView(context).apply {
                    text = "加入现有组"; textSize = 11f
                    setPadding(dpi(10), dpi(4), dpi(10), dpi(4))
                    isFocusable = !radioDisabled; isFocusableInTouchMode = !radioDisabled; isClickable = !radioDisabled
                    val selected = !radioDisabled && (idx == selectedIndex && selectedMode == 1)
                    background = GradientDrawable().apply {
                        cornerRadius = dpi(8).toFloat()
                        if (radioDisabled) {
                            setColor(Color.parseColor("#11FFFFFF"))
                            setStroke(dpi(1), Color.parseColor("#22FFFFFF"))
                        } else {
                            setColor(if (selected) Color.parseColor("#66FFD07A") else Color.parseColor("#1AFFFFFF"))
                            setStroke(dpi(if (selected) 2 else 1), Color.parseColor(if (selected) "#FFD07A" else "#55FFFFFF"))
                        }
                    }
                    setTextColor(when {
                        radioDisabled -> Color.parseColor("#33EEE8DA")
                        selected -> Color.WHITE
                        else -> lightText
                    })
                    if (radioDisabled) {
                        visibility = View.GONE
                    }
                    setOnClickListener {
                        if (radioDisabled) { toastMsg("本机设备不可克隆"); return@setOnClickListener }
                        if (idx != selectedIndex) return@setOnClickListener
                        selectedMode = 1
                        refreshList()
                    }
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN &&
                            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                            if (radioDisabled) { toastMsg("本机设备不可克隆"); return@setOnKeyListener true }
                            if (idx == selectedIndex) {
                                selectedMode = 1
                                refreshList()
                            }
                            true
                        } else false
                    }
                }
                radioBar.addView(radioNew, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dpi(6) })
                radioBar.addView(radioJoin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(radioBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

                // 行刷新 + 选中
                val isSel = idx == selectedIndex
                row.refreshRow(focused = false, isSelected = isSel)
                row.setOnClickListener {
                    if (idx == selectedIndex) {
                        // 重复点击 → 保持
                    } else {
                        selectedIndex = idx
                        selectedMode = 0
                        refreshList()
                        // 把焦点移到新的 radio 上，方便 DPAD 继续走
                        row.findFocus() ?: run {
                            handler.post { (radioBar.getChildAt(0) as? View)?.requestFocus() }
                        }
                    }
                }
                row.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            selectedIndex = idx
                            selectedMode = 0
                            refreshList()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (idx == selectedIndex && radioBar.visibility == View.VISIBLE) {
                                radioNew.requestFocus()
                                true
                            } else {
                                BoundaryFocusHandler.shake(row)
                                true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // 如果当前是"加入现有组"，把焦点交给下方父组选择器的首个 chip（如果存在）
                            if (idx == selectedIndex && selectedMode == 1) {
                                val next = listContainer.getChildAt(listContainer.indexOfChild(row) + 1)
                                if (next is LinearLayout) {
                                    for (i in 0 until next.childCount) {
                                        val c = next.getChildAt(i)
                                        if (c is View && c.isFocusable && c.visibility == View.VISIBLE) {
                                            c.requestFocus()
                                            return@setOnKeyListener true
                                        }
                                    }
                                }
                            }
                            false
                        }
                        else -> false
                    }
                }
                listContainer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = if (idx == 0) 0 else dpi(6)
                })

                // 当该项被选中 且 选择了"加入现有组"，在该行下方再插入一行：现有设备组名称（横滑chip，单选）
                if (idx == selectedIndex && selectedMode == 1) {
                    val parentBlock = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dpi(38), dpi(8), dpi(10), dpi(8))
                        background = GradientDrawable().apply {
                            cornerRadius = dpi(10).toFloat()
                            setColor(Color.parseColor("#11FFD07A"))
                            setStroke(dpi(1), Color.parseColor("#33FFD07A"))
                        }
                    }
                    parentBlock.addView(TextView(context).apply {
                        text = "加入现有组 → 请选择目标父组："
                        textSize = 11f; setTextColor(lightText)
                        setPadding(0, 0, 0, dpi(4))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val hsv = HorizontalScrollView(context).apply {
                        isFillViewport = true
                        overScrollMode = View.OVER_SCROLL_NEVER
                    }
                    val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    buildParentChips(chipRow) { refreshList() }
                    hsv.addView(chipRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    parentBlock.addView(hsv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    listContainer.addView(parentBlock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dpi(4)
                        marginStart = dpi(12)
                        marginEnd = dpi(12)
                    })
                }
            }
        }

        fun doScan() {
            // 显示居中 Loading
            loadingMask.visibility = View.VISIBLE
            loadingText.text = "正在扫描局域网设备（已发现0台）"
            listScroll.visibility = View.INVISIBLE
            scanResults.clear()
            selectedIndex = -1
            selectedMode = 0
            refreshList()
            val excludeIds = try { CustomDouyinDeviceGroupsStore.list(context).map { it.id }.toSet() } catch (_: Throwable) { emptySet() }
            val mainExecutor = java.util.concurrent.Executor { cmd -> handler.post(cmd) }
            deviceScanner.startScan(context, excludeIds, object : LanDeviceScanner.Callback {
                override fun onProgress(foundCount: Int) {
                    handler.post { loadingText.text = "正在扫描局域网设备（已发现 $foundCount 台）" }
                }
                override fun onComplete(groups: List<DouyinDeviceGroup>, aborted: Boolean) {
                    handler.post {
                        scanResults.addAll(groups)
                        loadingMask.visibility = View.GONE
                        listScroll.visibility = View.VISIBLE
                        refreshList()
                    }
                }
            }, mainExecutor)
        }

        // 按钮绑定
        refreshBtn.setOnClickListener { doScan() }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        addBtn.setOnClickListener {
            if (selectedIndex !in 0 until visibleList.size) {
                toastMsg("请先选择要克隆的设备")
                return@setOnClickListener
            }
            val g = visibleList[selectedIndex]
            when {
                g.isLocalDevice -> {
                    toastMsg("本机设备不可克隆")
                    return@setOnClickListener
                }
                g.isAlreadyAdded -> {
                    toastMsg("该设备已添加过，无需重复添加")
                    return@setOnClickListener
                }
            }
            val member = g.memberAt(0)
            cloneDeviceGroup(g, selectedMode, selectedParentGroup.id, member)
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(dpi(560), ViewGroup.LayoutParams.WRAP_CONTENT)
            // 初始焦点：取消按钮
            cancelBtn.requestFocus()
            doScan()
        }
        dialog.setOnDismissListener {
            deviceScanner.abort()
        }
        try { dialog.show() } catch (_: Throwable) {}
    }

    /** 把扫描到的设备克隆到自定义组存储，并切换为当前使用组。 */
    private fun cloneDeviceGroup(
        group: DouyinDeviceGroup,
        mode: Int,
        parentGroupId: String,
        member: com.bd.casttv.dlna.DeviceIdentity
    ) {
        try {
            if (mode == 0) {
                // 作为新组：直接保存 + 切换
                CustomDouyinDeviceGroupsStore.addGroup(context, group)
                settingsStore.douyinDeviceGroupId = group.id
                settingsStore.douyinDeviceMemberIndex = 0
                toastMsg("已添加克隆组「${group.label}」并切换为当前使用组")
            } else {
                // 加入现有组
                val newIdx = CustomDouyinDeviceGroupsStore.addMemberToGroup(context, parentGroupId, member)
                if (newIdx < 0) {
                    toastMsg("该设备已存在于目标组，未重复添加")
                    return
                }
                settingsStore.douyinDeviceGroupId = parentGroupId
                settingsStore.douyinDeviceMemberIndex = newIdx
                toastMsg("已作为成员添加到现有组并切换")
            }
            if (!settingsStore.douyinCastEnabled) settingsStore.douyinCastEnabled = true
            triggerDlnaIdentityRestart()
            renderAll()
            post { focusDeviceGroupById(settingsStore.douyinDeviceGroupId) }
        } catch (t: Throwable) {
            toastMsg("添加失败：${t.message ?: "未知错误"}")
        }
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
