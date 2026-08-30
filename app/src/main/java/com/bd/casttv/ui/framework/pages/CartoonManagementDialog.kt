package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.children
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
 * 严格遵循 [DIALOG_SPEC.md] 弹窗设计规范 V1：
 *  §一 容器：Theme_CastTV_Dialog，透明背景、标准尺寸、dimAmount
 *  §二 面板：bg_dialog_crayon_panel（root） / ThemeManager.dialogPanelBg（主题联动）
 *       标题牌：bg_dialog_crayon_header 语义 + 渐变文字
 *       徽章：bg_dialog_badge_focus 三态胶囊
 *       行：bg_dialog_focus_item 语义（默认白虚线 1dp / 焦点暖黄 2dp / 按下橙色 2dp）
 *  §三 标题行：标题牌尺寸、左右 padding 16~18dp、18~22sp bold #101217、副标题 accent 15sp bold
 *  §四 按钮：标准型（BatchBottomButton 语义）— 三态色值、圆角 18dp、字号 15sp bold、最小宽 88dp
 *       危险按钮：默认 #C4463E 描边 + 文字
 *  §五 内容区：说明 15sp text_secondary、行 16sp 白色
 *  §六 尺寸：管理弹窗 560dp 标准型 / 确认弹窗 460dp 紧凑型
 *  §七 padding：四周 26dp
 *  §八 颜色：crayon_yellow #F6C445 / crayon_orange #F2913D / text_primary #E8EAED /
 *            text_secondary #98A0AC / text_hint #5E6773 / danger #C4463E
 *
 * 主题风格接入（ThemeManager）：
 *  · 面板背景 → dialogPanelBg()（跟随 palette / 自定义透明度 / 内容面板色）
 *  · 标题渐变 → dialogTitleGradient()（主题渐变色）
 *  · 焦点描边 → strokeFor(context, focused)（统一粗细与 accent 色）
 *  · 主色 → accentColor(context)（选中、徽章、计数高亮）
 */
class CartoonManagementDialog(
    private val context: Context,
    private val cartoons: List<GiteeShareStore.SharedCartoon>,
    private val onChanged: () -> Unit
) {
    // ================= DIALOG_SPEC §八 颜色令牌（精确值） =================
    private val accent: Int get() = ThemeManager.accentColor(context)
    private val crayonYellow = Color.rgb(0xF6, 0xC4, 0x45)   // #F6C445（默认 accent，作为 fallback）
    private val crayonOrange = Color.rgb(0xF2, 0x91, 0x3D)   // #F2913D 按下态描边
    private val textPrimary = Color.rgb(0xE8, 0xEA, 0xED)    // #E8EAED 主文字
    private val textSecondary = Color.rgb(0x98, 0xA0, 0xAC)  // #98A0AC 副文字
    private val textHint = Color.rgb(0x5E, 0x67, 0x73)       // #5E6773 占位 / 弱文字
    private val dangerColor = Color.rgb(0xC4, 0x46, 0x3E)    // #C4463E 危险操作
    private val successColor = Color.rgb(0x6A, 0xBE, 0x73)   // 成功绿（成功徽章辅助色）
    private val bgDark = Color.rgb(0x07, 0x08, 0x09)         // #070809 对话框色底色参考

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    // ================= 运行状态 =================
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dialog: AlertDialog? = null
    private var deleteJob: Job? = null
    private val selected = LinkedHashSet<String>()
    /** 每一行 checkbox/selectBar/title 状态刷新回调，供「再想想 → 清空勾选」批量回滚 UI 用。 */
    private val rowRefreshers = mutableListOf<() -> Unit>()
    private lateinit var titleCount: TextView
    private lateinit var bottomCount: TextView
    private lateinit var deleteBtn: TextView

    fun show() {
        rowRefreshers.clear()
        selected.clear()
        // ===== §一 容器尺寸 + §七 padding（标准型 560dp，四周 26dp） =====
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // §二 主面板：ThemeManager.dialogPanelBg（跟随主题），圆角 26dp
            background = ThemeManager.dialogPanelBg(context, 26)
            setPadding(dp(26), dp(26), dp(26), dp(26))
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        // ===== §三 标题行 =====
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        // 贴纸：44dp 圆形 + 动画城图标 + fg_sticker_circle_border 与 AddToCartoonDialog 一致
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.ic_more_cartoon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = ResourcesCompat.getDrawable(context.resources, R.drawable.fg_sticker_circle_border, null)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        // 标题文字：去掉渐变背景牌与描边，改为纯文字（参考 FavoritesPage.crayonDialogTitle）
        // 焦点态/主题感仅通过文字色 + 投影保留，避免与底部操作按钮争抢视觉层级。
        titleCount = object : TextView(context) {
            init {
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                setShadowLayer(2f, 0f, 1f, Color.argb(140, 0, 0, 0))
                setTextColor(textPrimary)
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w <= 0 || h <= 0) return
                val grad = ThemeManager.dialogTitleGradient(context)
                if (grad.isEmpty()) return
                paint.shader = LinearGradient(
                    0f, h * 0.5f, w.toFloat(), h * 0.5f, grad, null, Shader.TileMode.CLAMP
                )
            }
        }
        header.addView(titleCount, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        // 右侧副标题/计数（§三：crayon_yellow 15sp bold；按主题走 accent）
        header.addView(TextView(context).apply {
            text = "共 ${cartoons.size} 部"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
        })
        panel.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // §五 说明文字（15sp，text_secondary，marginTop 对应 10~16dp）
        panel.addView(TextView(context).apply {
            text = "方向键移动焦点 · 确定键勾选条目 · 勾选后点「删除选中」"
            textSize = 15f
            setTextColor(textSecondary)
            setPadding(0, dp(12), 0, dp(6))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ===== §二 通用可聚焦选项行（bg_dialog_focus_item 语义，三态） =====
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 2026-08 UI 调整：滚动容器与其内层都开启默认裁剪，
            // 避免滚动到边缘时行卡片/焦点辉光溢出 340dp 高容器，与底部按钮撞边。
        }
        val focusRows = mutableListOf<View>()
        lateinit var refresher: () -> Unit
        cartoons.forEachIndexed { i, c ->
            val row = buildRow(i, c) { cb -> refresher = cb }
            focusRows += row
            rowRefreshers += refresher
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(78)
            ).apply { topMargin = if (i == 0) dp(2) else dp(8) })
        }
        val scroll = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            // 2026-08 UI 要求：滑动时内容必须限制在容器区域内，溢出隐藏。
            // ScrollView 默认会按自身绘制区域 canvas.clipRect 裁剪子 View，但为了阻止运行期
            // FocusFxHelper.disableClippingUp / setClipChildren(false) 等副作用，这里显式锁死
            // clipChildren（针对子 View 超界绘制的运行期开关）与 clipToPadding（针对 padding 内边距区的超界）。
            isVerticalScrollBarEnabled = false
            clipChildren = true
            clipToPadding = true
            addView(list)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(340)
        ).apply { topMargin = dp(4) })
        // 防御：等 view 首次布局后，再把 ScrollView + 行列表两层的裁剪开关强制锁回 true；
        // 避免任何 disableClippingUp（maxDepth 误传）在挂载前后的调用"溢出"到滚动容器。
        list.clipChildren = true; list.clipToPadding = true
        scroll.post {
            scroll.clipChildren = true; scroll.clipToPadding = true
            list.clipChildren = true; list.clipToPadding = true
        }

        // ===== §四 底部操作按钮（BatchBottomButton 语义） =====
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
            clipChildren = false; clipToPadding = false
            addView(bottomCount, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(12) })
            addView(cancel)
            addView(deleteBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(14) })  // §四：间距 6dp（标准）；确认/取消之间 14dp 更强区分
        }
        panel.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        // §一 AlertDialog：Theme_CastTV_Dialog + 透明背景 + 居中 + dimAmount + §六 560dp
        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnDismissListener { scope.cancel() }
            d.setOnShowListener { focusRows.firstOrNull()?.requestFocus() }
            bindBoundary(panel, focusRows + listOf(cancel, deleteBtn))
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(560), WindowManager.LayoutParams.WRAP_CONTENT) // §六 标准型 560dp
                val attrs = attributes
                attrs.dimAmount = 0.32f
                attributes = attrs
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        updateCounts()
    }

    // ================= §二 标题牌 Drawable（橙→黄→蓝 横向渐变 18dp 圆角） =================

    private fun buildHeaderDrawable(): GradientDrawable {
        // §二 + colors.xml crayon_orange → crayon_yellow → crayon_blue（angle=0 横向）
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(crayonOrange, crayonYellow, Color.rgb(0x3E, 0x9B, 0xE8))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            // 1dp 暗色描边（对齐 bg_dialog_crayon_header）
            setStroke(Math.max(1, dp(1)), Color.argb(0x66, 0x10, 0x12, 0x17))
        }
    }

    // ================= §四 底部操作按钮工厂（标准型 BatchBottomButton 语义） =================

    /**
     * §四 标准型（BatchBottomButton）语义更新（2026-08 UI 优化）：
     *  · 默认（非危险）：暗底 #22FFFFFF + 浅灰白描边 1dp，文字 #EEFFFFFF（冷白 主文字）
     *  · 默认（危险 — 删除按钮）：透明底 + **浅灰白 1dp 描边 + 纯白文字**（与危险填充底色剥离，
     *    焦点/按下才切换为暖黄/橙色渐变以提示动作）
     *  · 焦点：accent 半透明 #33 + accent 描边 2dp，文字 accent（与 FavoritesPage.crayonDialogButton 对齐）
     *  · 按下：橙色半透明 #33 + 橙色描边 2dp，文字 crayon_yellow
     *  · 禁用：alpha = 0.4（updateCounts 负责）
     *  · 宽 wrap_content，minWidth 88dp，padding 18dp / 10dp，字号 15sp bold，圆角 18dp
     */
    private fun dialogButton(label: String, danger: Boolean = false, click: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumWidth = dp(88)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            var pressed = false
            fun refresh(focused: Boolean) {
                // 危险按钮默认态：浅灰 1dp 描边 + 白色文字（无红底/红字）
                val defaultStrokeColor = Color.argb(255, 210, 214, 222) // 浅灰白 ≈ Favorites crayonDialogButton 默边
                val defaultFillColor = Color.argb(
                    if (danger) 0 else 34,
                    255, 255, 255
                ) // danger 默认透明底；非 danger 保留原 13% 白膜
                val defaultTextColor = if (danger) Color.WHITE else textPrimary
                val (bgFill, strokeW, strokeColor, textColor) = when {
                    pressed -> Quad(
                        Color.argb(51, Color.red(crayonOrange), Color.green(crayonOrange), Color.blue(crayonOrange)),
                        2, crayonOrange, crayonYellow
                    )
                    focused -> {
                        val a = accent
                        Quad(
                            Color.argb(51, Color.red(a), Color.green(a), Color.blue(a)),
                            2, a, a
                        )
                    }
                    else -> Quad(defaultFillColor, 1, defaultStrokeColor, defaultTextColor)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(bgFill)
                    setStroke(dp(strokeW), strokeColor)
                }
                setTextColor(textColor)
            }
            refresh(false)
            // 全局焦点 fx：两种状态同步；圆角 18dp
            setOnFocusChangeListener { v, has ->
                refresh(has)
                FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 18)
            }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { pressed = true; refresh(v.hasFocus()); false }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressed = false; refresh(v.hasFocus()); false }
                    else -> false
                }
            }
            setOnClickListener { click() }
            // 初始化同步 fx 状态
            FocusFxHelper.applyFocusFxState(this, false, cornerRadiusDp = 18)
        }

    private data class Quad(val fill: Int, val strokeW: Int, val strokeColor: Int, val textColor: Int)

    // ================= §二 bg_dialog_focus_item 语义行：默认白虚线/焦点暖黄/按下橙色（圆角 18dp） =================

    private fun buildRow(index: Int, cartoon: GiteeShareStore.SharedCartoon, registerRefresher: (() -> Unit) -> Unit): View {
        val row = FrameLayout(context).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            clipChildren = false; clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            setPadding(dp(14), dp(10), dp(16), dp(10))
        }
        // 选中态：左侧垂直 accent 指示条
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
            clipChildren = false; clipToPadding = false
            isFocusable = false
        }
        // 选择框（☐/☑）— 文字色跟随：未选 text_hint / 已选 accent
        val check = TextView(context).apply {
            text = "\u2610"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(textHint)
            setTypeface(null, Typeface.BOLD)
        }
        body.addView(check, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        // 缩略图：ClippedImageView 圆角 12dp，海报比例
        val thumb = ClippedImageView(context).apply {
            setCircle(false)
            setCornerRadius(dp(12).toFloat())
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_thumb_default)
        }
        body.addView(thumb, LinearLayout.LayoutParams(dp(48), dp(62)).apply { marginEnd = dp(12) })
        loadThumb(cartoon.cover, thumb)
        // 信息列
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = false
        }
        val titleView = TextView(context).apply {
            text = cartoon.title.ifBlank { cartoon.detailUrl }
            textSize = 16f       // §五 选项行 16sp
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
            setTextColor(textHint)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        body.addView(info, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        row.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL
        ).apply { leftMargin = dp(6) })

        var pressed = false
        fun refreshBg(has: Boolean) {
            applyDialogFocusItemBg(row, has, pressed)
        }
        fun refreshSelection() {
            val on = selected.contains(cartoon.cartoonId)
            check.text = if (on) "\u2611" else "\u2610"
            check.setTextColor(if (on) accent else textHint)
            selectBar.visibility = if (on) View.VISIBLE else View.GONE
            titleView.setTextColor(if (on) accent else textPrimary)
        }
        fun refreshAll(has: Boolean) {
            refreshBg(has); refreshSelection()
        }
        // 对外暴露勾选刷新句柄（给「再想想 → 清空勾选」批量用）
        registerRefresher { refreshSelection() }
        refreshAll(false)

        row.setOnFocusChangeListener { _, has ->
            refreshAll(has)
            FocusFxHelper.applyFocusFxState(row, has, cornerRadiusDp = 18)
        }
        row.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { pressed = true; refreshBg(row.hasFocus()); false }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressed = false; refreshBg(row.hasFocus()); false }
                else -> false
            }
        }
        row.setOnKeyListener { _, keyCode, e ->
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && e.action == KeyEvent.ACTION_UP) {
                toggle(cartoon.cartoonId, ::refreshSelection); true
            } else false
        }
        row.setOnClickListener { toggle(cartoon.cartoonId, ::refreshSelection) }
        // maxDepth=1：只对直接父容器（行父 FrameLayout/横向 LinearLayout list 内部）关闭裁剪，
        // 避免沿父链关掉外层 ScrollView / panel 的 clip → 滑动时内容跑出 340dp 容器外。
        FocusFxHelper.disableClippingUp(row, maxDepth = 1)
        FocusFxHelper.applyFocusFxState(row, false, cornerRadiusDp = 18)
        return row
    }

    /**
     * §二 通用可聚焦选项行（bg_dialog_focus_item 语义）：
     *  - 默认：深灰半透明底 + 白色虚线 1dp（dash 7/4），圆角 18dp
     *  - 焦点：透明底 + 暖黄实线 2dp
     *  - 按下：透明底 + 橙色实线 2dp
     */
    private fun applyDialogFocusItemBg(row: View, focused: Boolean, pressed: Boolean) {
        val density = context.resources.displayMetrics.density
        val radius = 18f * density
        row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            when {
                pressed -> {
                    setColor(Color.TRANSPARENT)
                    setStroke(Math.max(2, (2 * density).toInt()), crayonOrange)
                }
                focused -> {
                    val a = accent
                    setColor(Color.argb(28, Color.red(a), Color.green(a), Color.blue(a)))
                    setStroke(Math.max(2, (2 * density).toInt()), a)
                }
                else -> {
                    // 默认：浅半透明深灰底 + 白色虚线描边（dashWidth=7dp / dashGap=4dp）
                    setColor(Color.argb(0x22, 0x1B, 0x1F, 0x26))
                    val dashW = 7f * density
                    val dashG = 4f * density
                    val strokeW = Math.max(1, (1 * density).toInt())
                    setStroke(strokeW, Color.argb(0x33, 0xFF, 0xFF, 0xFF), dashW, dashG)
                }
            }
        }
    }

    // ================= §二 / §四 徽章（bg_dialog_badge_focus 语义）：胶囊 + 15sp bold =================

    /** 集数胶囊徽章：全圆角 + 1dp 描边，按状态染色 */
    private fun buildStatusChip(count: Int): View {
        val (bg, fg, txt) = when {
            count <= 0 -> Triple(
                Color.argb(51, Color.red(crayonYellow), Color.green(crayonYellow), Color.blue(crayonYellow)),
                crayonYellow, "待解析"
            )
            count >= 120 -> Triple(
                Color.argb(40, Color.red(successColor), Color.green(successColor), Color.blue(successColor)),
                successColor, "全 ${count} 集 · 已完结"
            )
            else -> Triple(
                Color.argb(51, Color.red(crayonYellow), Color.green(crayonYellow), Color.blue(crayonYellow)),
                crayonYellow, "更新至第 $count 集"
            )
        }
        return TextView(context).apply {
            text = txt
            textSize = 12f
            setTextColor(fg)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = 9999f   // 胶囊
                setColor(bg)
                setStroke(Math.max(1, dp(1)), fg)
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
        // 重绘触发渐变 shader 重算
        titleCount.post {
            titleCount.invalidate()
            titleCount.requestLayout()
        }
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
        deleteBtn.alpha = if (enabled) 1f else 0.4f  // §四 禁用 alpha=0.4
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

    // ================= 删除二次确认（参考 FavoritesPage.crayonConfirmDialog） =================

    // 与 Favorites 删除视频卡片确认弹窗风格 1:1 对齐
    private val CRAYON_PANEL_BG = Color.parseColor("#4169E1")  // 皇家蓝蜡笔面板
    private val CRAYON_CARD_BG = Color.argb(18, 255, 255, 255) // 卡片/按钮暗底
    private val CRAYON_WARM = Color.rgb(245, 196, 81)           // 焦点/强调暖色（与 Favorites. crayonDialogButton 同色）
    private val CRAYON_BTN_BORDER = Color.argb(170, 210, 214, 222) // 默认态浅灰白描边

    private fun showConfirmDialog(count: Int, preview: Preview) {
        val parent = dialog ?: return

        // —— 面板层（与 FavoritesPage.showCrayonConfirmDialog 同构：18dp 圆角 + 皇家蓝面板 + 16dp 外边距）
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(CRAYON_PANEL_BG)
            }
            clipChildren = false; clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        // —— 内容层（4dp 内垫 + clipChildren=false）
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false; clipToPadding = false
        }
        // —— 标题（与 Favorites.crayonDialogTitle 一致：44dp 贴纸 + 20sp WARM 字 + 阴影）
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = ResourcesCompat.getDrawable(context.resources, R.drawable.fg_sticker_circle_border, null)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "删除动画"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CRAYON_WARM)
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // —— 主提示文案（与 Favorites confirm message 一致：15sp 白色，top=16dp）
        content.addView(TextView(context).apply {
            text = "确定删除选中的 $count 部动画吗？\n此操作将同步修改 Gitee 云端数据，无法撤销。"
            textSize = 15f
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextColor(Color.argb(238, 255, 255, 255))
            gravity = Gravity.START
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        // —— 摘要行（仅当预览有实质信息时追加，保持视觉紧凑）
        val hasPreviewInfo = preview.adapterDrops > 0 || preview.adapterKept > 0
        if (hasPreviewInfo) {
            content.addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(120, 210, 214, 222),
                    Color.argb(0, 255, 255, 255)
                ))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { topMargin = dp(14); bottomMargin = dp(10) })
            if (preview.adapterDrops > 0) {
                content.addView(summaryLine("清理", "将删除 ${preview.adapterDrops} 个无人引用的自定义解析规则", successColor),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) })
            }
            if (preview.adapterKept > 0) {
                content.addView(summaryLine("保留", "${preview.adapterKept} 个解析规则仍被其他条目引用，不会删除", CRAYON_WARM),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) })
            }
            content.addView(summaryLine("云端", "将写入 Gitee 仓库 bdCasttv/video-source · 不可逆", dangerColor),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
        }

        // —— 底部按钮（右对齐，间距 14dp，默认焦点在「再想想」，与 Favorites showCrayonConfirmDialog 一致）
        val btns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancel = crayonConfirmButton("再想想")
        val confirm = crayonConfirmButton("确认删除")
        btns.addView(cancel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        btns.addView(confirm, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(14) })
        content.addView(btns, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })

        panel.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dlg = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        cancel.setOnClickListener {
            // Bug fix：用户点「再想想」应视为放弃本次批量删除意图，
            // 清空已勾选并把管理弹窗底部「删除选中」置灰不可点击（alpha=0.4）
            selected.clear()
            deleteBtn.isEnabled = false
            deleteBtn.alpha = 0.4f
            // 计数与行样式同步，避免 UI 残留"已选 N"或勾选标记
            updateCounts()
            refreshAllRowChecks()
            dlg.dismiss()
        }
        confirm.setOnClickListener { dlg.dismiss(); doDelete() }
        dlg.window?.takeIf { parent.isShowing }?.let { win ->
            // —— 与 applyCrayonDialogWindow 同：居中 + 透明 + 宽度 380dp（与 Favorites 删除视频卡片弹窗一致）
            dlg.show()
            win.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                decorView.setBackgroundColor(Color.TRANSPARENT)
                setLayout(dp(380), WindowManager.LayoutParams.WRAP_CONTENT)
                val attrs = attributes
                attrs.dimAmount = 0.32f
                attributes = attrs
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            win.decorView.isFocusable = true
            win.decorView.setOnKeyListener { _, keyCode, ev ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (ev.action == KeyEvent.ACTION_DOWN) cancel.performClick()
                    true
                } else false
            }
            bindDialogBoundaryFocus(panel, listOf(cancel, confirm))
            win.decorView.post { cancel.requestFocus() }
        }
    }

    // 与 FavoritesPage.crayonDialogButton 同构：
    //  默认：浅灰白描边 1dp + 暗卡片底 + 冷白字；焦点：暖黄 WARM 3dp 描边 + 同色字；圆角 10dp + FocusFx
    private fun crayonConfirmButton(label: String): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true; isFocusableInTouchMode = false; isClickable = true
        minWidth = dp(92)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        fun refresh(focused: Boolean) {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(CRAYON_CARD_BG)
                setStroke(dp(if (focused) 3 else 1), if (focused) CRAYON_WARM else CRAYON_BTN_BORDER)
            }
            setTextColor(if (focused) CRAYON_WARM else Color.argb(235, 245, 245, 245))
        }
        refresh(false)
        setOnFocusChangeListener { v, has ->
            refresh(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
        }
    }

    private fun buildDangerPanelBg(): GradientDrawable {
        val base = ThemeManager.dialogPanelBg(context, 26)
        // LayerDrawable 不可用（dialogPanelBg 返回 GradientDrawable），所以 clone 并叠加 danger 描边
        return base.constantState?.newDrawable()?.mutate()?.let { it as GradientDrawable }?.apply {
            // danger 2dp 描边（与主题原有的 1dp 描边叠加语义：只替换为统一的 danger 色更粗）
            setStroke(dp(2), Color.argb(220, Color.red(dangerColor), Color.green(dangerColor), Color.blue(dangerColor)))
        } ?: base
    }

    private fun buildDangerHeaderDrawable(): GradientDrawable {
        // danger → dark danger 渐变，18dp 圆角
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(0xC4, 0x46, 0x3E), Color.rgb(0x8A, 0x2B, 0x26))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setStroke(Math.max(1, dp(1)), Color.argb(0x55, 0xFF, 0xFF, 0xFF))
        }
    }

    /** 摘要行：胶囊标签 + 文案 */
    private fun summaryLine(tag: String, body: String, tagColor: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        row.addView(TextView(context).apply {
            text = tag
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(tagColor)
            background = GradientDrawable().apply {
                cornerRadius = 9999f
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

    /** 按「管理弹窗 → 确认弹窗」层级分别绑定的边界焦点（与 FavoritesPage.bindDialogBoundaryFocus 同构，防止焦点逃出确认弹窗进入管理弹窗）。 */
    private fun bindDialogBoundaryFocus(root: ViewGroup, focusables: List<View>) = bindBoundary(root, focusables)

    /** 批量刷新所有行的勾选/选中指示（复选框字符、左侧 accent 条、标题颜色）。 */
    private fun refreshAllRowChecks() {
        rowRefreshers.forEach { it.invoke() }
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
}
