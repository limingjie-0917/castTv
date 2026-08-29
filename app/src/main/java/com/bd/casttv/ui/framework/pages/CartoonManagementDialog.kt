package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.sync.GiteeApi
import com.bd.casttv.sync.GiteeShareStore
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
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

/**
 * 动画城管理对话框（多选 → 删除二次确认）。
 *
 * 严格遵循 [DIALOG_SPEC.md] 弹窗设计规范 + 接入 [ThemeManager] 主题风格：
 *  · 面板背景 → ThemeManager.dialogPanelBg（跟随当前主题 palette / 自定义面板设置）
 *  · 标题 → ThemeManager.dialogTitleGradient 渐变文字
 *  · 行卡 → bg_dialog_focus_item 语义：默认白色虚线描边，焦点暖黄实线 2dp，圆角 18dp
 *  · 按钮 → DIALOG_SPEC §四 标准型：暗底白描边 / 焦点暖黄半透明+描边 / 按下橙色
 *  · 颜色 → DIALOG_SPEC §八 颜色速查表（crayon_yellow #F6C445, text_primary #E8EAED 等）
 *  · DANGER 按钮 → error #C4463E 文字色 + 红色描边
 *  · 窗口 → Theme_CastTV_Dialog + 透明背景 + 居中 + dimAmount
 */
class CartoonManagementDialog(
    private val context: Context,
    private val cartoons: List<GiteeShareStore.SharedCartoon>,
    private val onChanged: () -> Unit
) {
    // ================= DIALOG_SPEC 颜色令牌 =================
    private val accent: Int get() = ThemeManager.accentColor(context)
    private val textPrimary = Color.rgb(0xEE, 0xFF, 0xFF)     // #EEFFFFFF
    private val textSecondary = Color.rgb(0x98, 0xA0, 0xAC)    // #98A0AC
    private val textMuted = Color.rgb(0x5E, 0x67, 0x73)       // #5E6773
    private val dangerColor = Color.rgb(0xC4, 0x46, 0x3E)     // #C4463E
    private val successColor = Color.rgb(0x6A, 0xBE, 0x73)    // 成功绿
    private val bgDark = Color.rgb(0x07, 0x08, 0x09)          // #070809

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    // ================= 运行状态 =================
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dialog: AlertDialog? = null
    private var deleteJob: Job? = null
    private val selected = LinkedHashSet<String>()
    private lateinit var titleCount: TextView
    private lateinit var bottomCount: TextView
    private lateinit var deleteBtn: TextView

    fun show() {
        // ===== 根面板：ThemeManager.dialogPanelBg（DIALOG_SPEC §二 主面板） =====
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.dialogPanelBg(context, 26)
            setPadding(dp(26), dp(22), dp(26), dp(22))
            clipChildren = true
            clipToPadding = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        // ===== Header：贴纸 + 标题渐变 + 右侧计数 =====
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // 贴纸：44dp 圆形 + 动画城图标
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.ic_more_cartoon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        // 标题：渐变文字
        titleCount = object : TextView(context) {
            init {
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w <= 0 || h <= 0) return
                val grad = ThemeManager.dialogTitleGradient(context)
                if (grad.isEmpty()) return
                paint.shader = LinearGradient(0f, h * 0.5f, w.toFloat(), h * 0.5f, grad, null, Shader.TileMode.CLAMP)
            }
        }
        header.addView(titleCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 右侧计数徽章
        header.addView(TextView(context).apply {
            text = "共 ${cartoons.size} 部"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
        })
        panel.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 操作提示行
        panel.addView(TextView(context).apply {
            text = "方向键移动焦点 · 确定键勾选条目 · 勾选后点「删除选中」"
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(10), 0, dp(6))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ===== 列表（bg_dialog_focus_item 行）=====
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        val focusRows = mutableListOf<View>()
        cartoons.forEachIndexed { i, c ->
            val row = buildRow(i, c)
            focusRows += row
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(78)
            ).apply { topMargin = if (i == 0) dp(2) else dp(8) })
        }
        val scroll = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(list)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(340)
        ).apply { topMargin = dp(4) })

        // ===== Bottom：计数左 + 按钮簇右 =====
        bottomCount = TextView(context).apply {
            textSize = 14f
            setTextColor(textSecondary)
        }
        val cancel = dialogButton("取消") {
            if (deleteJob?.isActive == true) return@dialogButton
            dialog?.dismiss()
        }
        deleteBtn = dialogButton("删除选中", danger = true) {
            if (deleteJob?.isActive == true) return@dialogButton
            onDeleteClicked()
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(bottomCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            addView(cancel, LinearLayout.LayoutParams(dp(130), dp(44)))
            addView(deleteBtn, LinearLayout.LayoutParams(dp(170), dp(44)).apply { marginStart = dp(14) })
        }
        panel.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnDismissListener { scope.cancel() }
            d.setOnShowListener { focusRows.firstOrNull()?.requestFocus() }
            bindBoundary(panel, focusRows + listOf(cancel, deleteBtn))
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(760), WindowManager.LayoutParams.WRAP_CONTENT)
                val attrs = attributes
                attrs.dimAmount = 0.32f
                attributes = attrs
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        updateCounts()
    }

    // ================= 按钮工厂（DIALOG_SPEC §四 标准型） =================

    private fun dialogButton(label: String, danger: Boolean = false, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            val stroke = ThemeManager.strokeFor(context, focused)
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (focused) Color.argb(51, Color.red(accent), Color.green(accent), Color.blue(accent))
                         else Color.argb(34, 255, 255, 255))
                setStroke(dp(stroke.first), if (danger && focused) dangerColor else stroke.second)
            }
            setTextColor(if (danger) Color.rgb(255, 200, 200) else if (focused) accent else textPrimary)
        }
        refresh(false)
        setOnFocusChangeListener { v, has -> refresh(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 18) }
        setOnClickListener { click() }
    }

    // ================= 行 builder（bg_dialog_focus_item 语义） =================

    private fun buildRow(index: Int, cartoon: GiteeShareStore.SharedCartoon): View {
        val row = FrameLayout(context).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            clipChildren = false
            clipToPadding = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(14), dp(10), dp(16), dp(10))
        }
        // 选中态：左侧垂直指示条
        val selectBar = View(context).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(accent)
            }
        }
        row.addView(selectBar, FrameLayout.LayoutParams(
            dp(3), ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.CENTER_VERTICAL
        ).apply { leftMargin = dp(4); topMargin = dp(10); bottomMargin = dp(10) })

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // 选择框
        val check = TextView(context).apply {
            text = "\u2610"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(textMuted)
            setTypeface(null, Typeface.BOLD)
        }
        body.addView(check, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        // 缩略图
        val thumb = ClippedImageView(context).apply {
            setCircle(false)
            setCornerRadius(dp(12).toFloat())
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_thumb_default)
        }
        body.addView(thumb, LinearLayout.LayoutParams(dp(48), dp(62)).apply { marginEnd = dp(12) })
        loadThumb(cartoon.cover, thumb)
        // 信息列
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isFocusable = false }
        val titleView = TextView(context).apply {
            text = cartoon.title.ifBlank { cartoon.detailUrl }
            textSize = 16f
            setTextColor(textPrimary)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(titleView)
        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(buildStatusChip(cartoon.episodeCount))
        meta.addView(TextView(context).apply {
            text = " · ${cartoon.adapterName.ifBlank { "通用网页解析" }}"
            textSize = 12f
            setTextColor(textSecondary)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(meta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        info.addView(TextView(context).apply {
            text = cartoon.detailUrl
            textSize = 12f
            setTextColor(textMuted)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        body.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL
        ).apply { leftMargin = dp(6) })

        fun refresh() {
            val on = selected.contains(cartoon.cartoonId)
            check.text = if (on) "\u2611" else "\u2610"
            check.setTextColor(if (on) accent else textMuted)
            selectBar.visibility = if (on) View.VISIBLE else View.GONE
            titleView.setTextColor(if (on) accent else textPrimary)
        }
        refresh()
        row.setOnFocusChangeListener { _, has ->
            refreshRowFocus(row, has)
            if (has) FocusFxHelper.applyFocusFxState(row, true, cornerRadiusDp = 18)
            else row.foreground = null
        }
        row.setOnKeyListener { _, keyCode, e ->
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && e.action == KeyEvent.ACTION_UP) {
                toggle(cartoon.cartoonId, ::refresh); true
            } else false
        }
        row.setOnClickListener { toggle(cartoon.cartoonId, ::refresh) }
        refreshRowFocus(row, false)
        return row
    }

    private fun refreshRowFocus(row: View, focused: Boolean) {
        val stroke = ThemeManager.strokeFor(context, focused)
        row.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(if (focused) Color.argb(28, Color.red(accent), Color.green(accent), Color.blue(accent))
                     else Color.argb(16, 255, 255, 255))
            setStroke(dp(stroke.first), stroke.second)
        }
    }

    /** 集数胶囊徽章 — DIALOG_SPEC §四 可点击标签/徽章 */
    private fun buildStatusChip(count: Int): View {
        val (bg, fg, txt) = when {
            count <= 0 -> Triple(Color.argb(51, 0xF6, 0xC4, 0x45), accent, "待解析")
            count >= 120 -> Triple(Color.argb(40, 0x6A, 0xBE, 0x73), successColor, "全 ${count} 集 · 已完结")
            else -> Triple(Color.argb(51, 0xF6, 0xC4, 0x45), accent, "更新至第 $count 集")
        }
        return TextView(context).apply {
            text = txt
            textSize = 12f
            setTextColor(fg)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(50).toFloat()
                setColor(bg)
                setStroke(dp(1), fg)
            }
            val ph = dp(10); val pv = dp(5)
            setPadding(ph, pv, ph, pv)
        }
    }

    // ================= 勾选计数 =================

    private fun toggle(id: String, refresh: () -> Unit) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        refresh(); updateCounts()
    }

    private fun updateCounts() {
        val s = selected.size; val t = cartoons.size
        titleCount.text = buildString {
            append("我的动画城")
            if (s > 0) append(" · 已选 $s")
        }
        // 标题渐变需要重绘
        titleCount.invalidate()
        val sb = SpannableStringBuilder()
        val a0 = "已选 "; val a1 = "$s"
        val b0 = "  /  共 "; val b1 = "$t"; val b2 = " 部"
        sb.append(a0).append(a1).append(b0).append(b1).append(b2)
        var p = 0
        sb.setSpan(ForegroundColorSpan(textSecondary), p, p + a0.length, 0); p += a0.length
        sb.setSpan(ForegroundColorSpan(accent), p, p + a1.length, 0); p += a1.length
        sb.setSpan(ForegroundColorSpan(textSecondary), p, p + b0.length, 0); p += b0.length
        sb.setSpan(ForegroundColorSpan(successColor), p, p + b1.length, 0); p += b1.length
        sb.setSpan(ForegroundColorSpan(textSecondary), p, sb.length, 0)
        bottomCount.text = sb

        val enabled = s > 0 && deleteJob?.isActive != true
        deleteBtn.isEnabled = enabled
        deleteBtn.alpha = if (enabled) 1f else 0.4f
    }

    // ================= 删除流 =================

    private fun onDeleteClicked() {
        if (selected.isEmpty()) { toast("请先勾选条目"); return }
        deleteBtn.isEnabled = false; deleteBtn.alpha = 0.4f
        val sel = cartoons.filter { selected.contains(it.cartoonId) }
        deleteJob = scope.launch {
            val preview = withContext(Dispatchers.IO) { buildDeletionPreview(sel) }
            withContext(Dispatchers.Main) { showConfirmDialog(sel.size, preview) }
        }
    }

    private data class Preview(val adapterDrops: Int, val adapterKept: Int)

    private suspend fun buildDeletionPreview(sel: List<GiteeShareStore.SharedCartoon>): Preview {
        val remaining = cartoons.filterNot { selected.contains(it.cartoonId) }
        val allRecords = (GiteeShareStore.fetchRecordsIndex() as? GiteeApi.ApiResult.Success)?.value.orEmpty()
        val aids = sel.mapNotNull { it.globalAdapterId }.distinct().filter { it.isNotBlank() }
        var d = 0; var k = 0
        for (aid in aids) {
            val ref = remaining.count { it.globalAdapterId == aid } +
                    allRecords.count { it.globalAdapterId == aid }
            if (ref == 0) d++ else k++
        }
        return Preview(d, k)
    }

    // ================= 删除二次确认（DIALOG_SPEC 紧凑型） =================

    private fun showConfirmDialog(count: Int, preview: Preview) {
        val parent = dialog ?: return
        // 根面板：ThemeManager.dialogPanelBg + DANGER 色描边
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.dialogPanelBg(context, 26).also { gd ->
                gd.setStroke(dp(2), Color.argb(200, Color.red(dangerColor), Color.green(dangerColor), Color.blue(dangerColor)))
            }
            setPadding(dp(26), dp(22), dp(26), dp(22))
            clipChildren = true
            clipToPadding = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        // Header：DANGER 图标 + 标题
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_parse_fail)
            imageTintList = android.content.res.ColorStateList.valueOf(dangerColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(2), 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val tb = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isFocusable = false }
        tb.addView(TextView(context).apply {
            text = "确定删除选中的 $count 部动画吗？"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary)
            maxLines = 2
        })
        tb.addView(TextView(context).apply {
            text = "此操作将同步修改 Gitee 云端数据，无法撤销"
            textSize = 13f
            setTextColor(textSecondary)
        })
        header.addView(tb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 分隔线
        panel.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.argb(0, Color.red(dangerColor), Color.green(dangerColor), Color.blue(dangerColor)),
                Color.argb(180, Color.red(dangerColor), Color.green(dangerColor), Color.blue(dangerColor)),
                Color.argb(0, Color.red(dangerColor), Color.green(dangerColor), Color.blue(dangerColor))
            ))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(16); bottomMargin = dp(16) })

        // 摘要行
        if (preview.adapterDrops > 0) {
            panel.addView(summaryLine("清理", "将删除 ${preview.adapterDrops} 个无人引用的自定义解析规则", successColor),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })
        }
        if (preview.adapterKept > 0) {
            panel.addView(summaryLine("保留", "${preview.adapterKept} 个解析规则仍被其他条目引用，不会删除", accent),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })
        }
        panel.addView(summaryLine("云端", "将写入 Gitee 仓库 bdCasttv/video-source · 不可逆", dangerColor),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))

        // 提示
        panel.addView(TextView(context).apply {
            text = "按返回键自动取消 · 默认焦点在「再想想」"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(dangerColor)
            setPadding(dp(2), dp(10), 0, 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 按钮簇
        val btns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val abort = dialogButton("再想想") { /* dismiss */ }
        val confirm = dialogButton("确认删除", danger = true) { /* confirm */ }
        btns.addView(abort, LinearLayout.LayoutParams(dp(150), dp(44)))
        btns.addView(confirm, LinearLayout.LayoutParams(dp(170), dp(44)).apply { marginStart = dp(14) })
        panel.addView(btns, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })

        val dlg = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        abort.setOnClickListener { dlg.dismiss() }
        confirm.setOnClickListener { dlg.dismiss(); doDelete() }
        dlg.setOnShowListener { abort.requestFocus() }
        dlg.window?.takeIf { parent.isShowing }?.let { win ->
            dlg.show()
            win.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(700), WindowManager.LayoutParams.WRAP_CONTENT)
                val attrs = attributes
                attrs.dimAmount = 0.40f
                attributes = attrs
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            win.decorView.isFocusable = true
            win.decorView.setOnKeyListener { _, keyCode, ev ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (ev.action == KeyEvent.ACTION_DOWN) dlg.dismiss()
                    true
                } else false
            }
        }
    }

    /** 摘要行：标签 + 文案 */
    private fun summaryLine(tag: String, body: String, tagColor: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(context).apply {
            text = tag
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tagColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(50).toFloat()
                setColor(Color.argb(30, Color.red(tagColor), Color.green(tagColor), Color.blue(tagColor)))
                setStroke(dp(1), tagColor)
            }
            val ph = dp(8); val pv = dp(4)
            setPadding(ph, pv, ph, pv)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(10) })
        row.addView(TextView(context).apply {
            text = body
            textSize = 14f
            setTextColor(textPrimary)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun doDelete() {
        val ids = selected.toList(); if (ids.isEmpty()) return
        deleteBtn.isEnabled = false; deleteBtn.alpha = 0.4f
        bottomCount.text = "正在删除 ${ids.size} 部…"
        bottomCount.setTextColor(accent)
        bottomCount.setTypeface(null, Typeface.BOLD)
        deleteJob = scope.launch {
            val (ok, fail) = withContext(Dispatchers.IO) {
                val latest = (GiteeShareStore.fetchCartoonsIndex() as? GiteeApi.ApiResult.Success)?.value ?: cartoons
                val recs = (GiteeShareStore.fetchRecordsIndex() as? GiteeApi.ApiResult.Success)?.value.orEmpty()
                var o = 0; var f = 0
                ids.forEachIndexed { i, id ->
                    val working = latest.filterNot { c ->
                        val removed = ids.subList(0, i)
                        removed.contains(c.cartoonId)
                    }
                    when (GiteeShareStore.deleteCartoon(id, working, recs)) {
                        is GiteeApi.ApiResult.Success -> o++
                        else -> f++
                    }
                }
                if (o > 0) runCatching {
                    com.bd.casttv.cartoon.CartoonStore(context.applicationContext)
                        .removeLocal(ids.take(o))
                }
                o to f
            }
            withContext(Dispatchers.Main) {
                val (s, c) = when {
                    ok > 0 && fail == 0 -> "✓ 已删除 $ok 部" to successColor
                    ok == 0 -> "× 全部失败，请检查网络" to dangerColor
                    else -> "成功 $ok / 失败 $fail" to accent
                }
                bottomCount.text = s; bottomCount.setTextColor(c)
                bottomCount.setTypeface(null, Typeface.BOLD)
                deleteBtn.isEnabled = selected.isNotEmpty()
                deleteBtn.alpha = if (deleteBtn.isEnabled) 1f else 0.4f
                if (deleteBtn.isEnabled) updateCounts()
                if (ok > 0) {
                    onChanged()
                    val d = dialog
                    FrameLayout(context).postDelayed({ if (d?.isShowing == true) d.dismiss() }, 800)
                }
            }
        }
    }

    // ================= Helpers =================

    private fun loadThumb(url: String, target: ImageView) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val u = URL(url)
                    val c = (u.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 3500; readTimeout = 3500
                        setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    }
                    c.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp != null) target.setImageBitmap(bmp)
        }
    }

    private fun bindBoundary(root: ViewGroup, focusables: List<View>) {
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
            if (next != null && next !== view && next.visibility == View.VISIBLE && next.isFocusable
                && isChildOf(next, root)) return@OnKeyListener false
            BoundaryFocusHandler.shake(view); true
        }
        focusables.forEach { it.setOnKeyListener(listener) }
    }

    private fun isChildOf(view: View, root: View): Boolean {
        var cur: View? = view
        while (cur != null) { if (cur === root) return true; cur = cur.parent as? View }
        return false
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
}
