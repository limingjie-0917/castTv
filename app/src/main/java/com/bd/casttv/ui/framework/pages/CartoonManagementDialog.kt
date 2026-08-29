package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
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
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.DrawableCompat
import com.bd.casttv.R
import com.bd.casttv.sync.GiteeApi
import com.bd.casttv.sync.GiteeShareStore
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.theme.CartoonDesign
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
 * 动画城管理对话框（多选 → 删除确认）。
 *
 * Taste 定向风格：【简约未来科技感 Minimalist Futuristic Sci-Fi】
 *   · 基调：午夜深蓝 SAPPHIRE_900 纯实底（不是液态玻璃），不做毛玻璃朦胧——锐利、克制、HUD。
 *   · 描边：只在顶部有一条 1px NEON_CYAN 硬边（发光霓虹）+ 1px SOFT_STROKE 全框；
 *          其余 UI 元素使用同样的「Outline 线框」而不是填充——赛博朋克里的「UI 是投影出来的」。
 *   · 装饰：扫描线网格（1/24 密度水平线 + 1/48 垂直线，3% alpha）；技术标签统一 monospace。
 *   · CTA：outline 胶囊（默认描边字色 = 霓虹青 50%，焦点 100% 发光 + 1px glow stroke，
 *           危险操作把描边切 NEON_MAGENTA，字色品红 90%）。
 *   · 焦点光：不再用 FocusFx 缩放——直接调 `liquidGlassToFocused` 的 2px 霓虹描边替代。
 *   · 删除确认：改用品红 neon 描边 + 「破坏栅格摘要」；默认焦点仍落"再想想"，BACK 最高优先级消费。
 *
 *  设计令牌（Sci-Fi 子主题，只在本文件使用）：
 *   SAPPHIRE_900 / SAPPHIRE_800 / SAPPHIRE_700  —— 3 档冷靛层级
 *   NEON_CYAN = #33E6FF, NEON_CYAN_SOFT         —— 主霓虹青 + 柔光 50% alpha
 *   NEON_MAGENTA = #FF3EA5                      —— 危险品红
 *   DATA_GREEN = #7DFFB2                        —— 技术 OK 绿（仅用于 monospace 数值）
 *   PANEL_R = 14dp                              —— 介于 SM/MD 之间，锐利但不割眼
 *   INNER_R = 10dp                              —— 行/按钮胶囊半径
 */
class CartoonManagementDialog(
    private val context: Context,
    private val cartoons: List<GiteeShareStore.SharedCartoon>,
    private val onChanged: () -> Unit
) {
    // ================= Sci-Fi 设计令牌（本地子主题） =================
    @ColorInt private val SAPPHIRE_900: Int = Color.rgb(10, 15, 36)
    @ColorInt private val SAPPHIRE_800: Int = Color.rgb(18, 25, 54)
    @ColorInt private val SAPPHIRE_700: Int = Color.rgb(28, 38, 74)
    @ColorInt private val NEON_CYAN: Int    = Color.rgb(51, 230, 255)
    @ColorInt private val NEON_MAGENTA: Int = Color.rgb(255, 62, 165)
    @ColorInt private val DATA_GREEN: Int   = Color.rgb(125, 255, 178)
    @ColorInt private val SOFT_STROKE: Int  = Color.argb(90, 90, 120, 180)
    @ColorInt private val TEXT_0: Int       = Color.rgb(236, 242, 255)
    @ColorInt private val TEXT_1: Int       = Color.argb(230, 220, 228, 255)
    @ColorInt private val TEXT_2: Int       = Color.argb(165, 170, 185, 230)
    @ColorInt private val TEXT_3: Int       = Color.argb(110, 140, 155, 210)

    private val PANEL_R = 14
    private val INNER_R = 10
    private fun dp(v: Int): Int = CartoonDesign.dp(context, v)
    private val MONO: Typeface = Typeface.MONOSPACE

    private fun rectFill(@ColorInt color: Int, cornerDp: Int, strokePx: Int = 0, @ColorInt stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(color)
            if (strokePx > 0) setStroke(strokePx, stroke)
        }

    /**
     * HUD Panel Drawable：
     *  Layer[0] = 午夜蓝实底
     *  Layer[1] = 1px SOFT_STROKE 全框
     *  Layer[2] = 顶部 1.5dp 霓虹青 / 品红硬边（InsetDrawable 只贴 top）
     */
    private fun hudPanel(@ColorInt accent: Int = NEON_CYAN, @ColorInt baseColor: Int = SAPPHIRE_900): LayerDrawable {
        val base = rectFill(baseColor, PANEL_R)
        val frame = rectFill(Color.TRANSPARENT, PANEL_R, dp(1), SOFT_STROKE)
        val r = dp(PANEL_R).toFloat()
        val accentBar = rectFill(accent, PANEL_R).apply {
            // 顶圆角仍贴合外框；底部切断做成只贴顶的 2px 条
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        val topBar = InsetDrawable(accentBar,
            /* left= */dp(2), /* top= */dp(1),
            /* right= */dp(2), /* bottom= */dp(PANEL_R) - dp(2) - dp(1))
        val ld = LayerDrawable(arrayOf(base, frame, topBar))
        return ld
    }

    /** 扫描线网格底（1dp 行线每 26dp + 1dp 列线每 52dp）。使用 ShapeDrawable + TileMode 的位图层做法太重，改用 drawable 叠加：画两条重复的线条使用 GradientDrawable 的 stroke 带 gap。实际上取一条半透明横线居中即可；再叠一个 1px 竖线，对整体产生轻微赛博栅格。 */
    private fun scanlineOverlay(): Drawable {
        val hLine = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(0,51,230,255), Color.argb(18,51,230,255), Color.argb(0,51,230,255)))
        hLine.setSize(1, dp(1))
        val vLine = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(0,51,230,255), Color.argb(10,51,230,255), Color.argb(0,51,230,255)))
        vLine.setSize(dp(1), 1)
        return LayerDrawable(arrayOf(vLine, hLine)).apply {
            setLayerInset(0, 0, dp(6), 0, dp(6))
            setLayerInset(1, dp(6), 0, dp(6), 0)
        }
    }

    /** Sci-Fi 行/按钮胶囊：纯色实底 SAPPHIRE_800 + 1px 软描边，聚焦后 2px accent 描边 + 内发光 1dp。 */
    private fun hudCapsule(
        accent: Int,
        base: Int = SAPPHIRE_800,
        cornerDp: Int = INNER_R,
        focused: Boolean = false,
        alphaFill: Int = 255
    ): GradientDrawable {
        val c = Color.argb(alphaFill, Color.red(base), Color.green(base), Color.blue(base))
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(c)
            setStroke(
                if (focused) dp(2) else dp(1),
                if (focused) accent else SOFT_STROKE
            )
        }
    }

    private fun monospaced(@ColorInt c: Int, size: Float, txt: CharSequence): TextView = TextView(context).apply {
        typeface = MONO; textSize = size; setTextColor(c); text = txt; gravity = Gravity.CENTER_VERTICAL
    }

    // ================= 运行状态 =================
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dialog: AlertDialog? = null
    private var deleteJob: Job? = null
    private val selected = LinkedHashSet<String>()
    private lateinit var titleCount: TextView
    private lateinit var bottomCount: TextView
    private lateinit var deleteBtn: TextView

    fun show() {
        // ===== 根面板：HUD panel =====
        val panel = FrameLayout(context).apply {
            background = hudPanel(NEON_CYAN)
            clipChildren = true; clipToPadding = true
            foreground = scanlineOverlay()
            val padX = dp(24); val padY = dp(20)
            setPadding(padX, padY, padX, padY)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        // ===== Header：sticker + title + monospace 右侧计数 =====
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        // 圆形霓虹贴纸：48dp 圆 + 2px 霓虹青描边 + 居中图标 + cyan tint
        header.addView(FrameLayout(context).apply {
            val stickerPad = dp(8)
            val iconSize = dp(48)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(14) }
            setBackground(rectFill(SAPPHIRE_800, 999, dp(2), NEON_CYAN))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_more_cartoon)
                imageTintList = android.content.res.ColorStateList.valueOf(NEON_CYAN)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(stickerPad, stickerPad, stickerPad, stickerPad)
            })
        })
        val titleBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = false
        }
        titleCount = TextView(context).apply {
            text = "MY CARTOONS"; typeface = MONOSPACE_BOLD; textSize = 18f
            setTextColor(TEXT_0); letterSpacing = 0.04f; maxLines = 1
        }
        titleBox.addView(titleCount)
        titleBox.addView(monospaced(TEXT_3, CartoonDesign.Type.STATUS, "CartoonCity Library · v2.0 · HUD MODE"))
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val totalBadge = FrameLayout(context).apply {
            background = hudCapsule(NEON_CYAN, SAPPHIRE_700)
            val ph = dp(12); val pv = dp(6); setPadding(ph, pv, ph, pv)
        }.also { frame ->
            frame.addView(monospaced(NEON_CYAN, CartoonDesign.Type.BADGE, "TOTAL ${cartoons.size}"))
        }
        header.addView(totalBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 技术提示：monospace 小字，用 DATA_GREEN 做提示符
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), 0, dp(6))
            addView(monospaced(DATA_GREEN, CartoonDesign.Type.STATUS, "> "))
            addView(monospaced(TEXT_3, CartoonDesign.Type.STATUS, "DPAD 移动焦点 · OK 勾选 · SELECTED 条目后按 [DELETE]"))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ===== List =====
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = true; clipToPadding = true }
        val focusRows = mutableListOf<View>()
        cartoons.forEachIndexed { i, c ->
            val row = buildRow(i, c)
            focusRows += row
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(82)
            ).apply { topMargin = if (i == 0) 0 else dp(8) })
        }
        val scroll = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(list)
        }
        content.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(340)
        ).apply { topMargin = dp(2) })

        // ===== Bottom: mono count 左 + 按钮簇右 =====
        bottomCount = monospaced(TEXT_2, CartoonDesign.Type.BODY, "")
        val cancel = buildFooterButton("CANCEL", accent = NEON_CYAN, primary = false) {
            if (deleteJob?.isActive == true) return@buildFooterButton
            dialog?.dismiss()
        }
        deleteBtn = buildFooterButton("DELETE SELECTED", accent = NEON_CYAN, primary = true) {
            if (deleteJob?.isActive == true) return@buildFooterButton
            onDeleteClicked()
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
            addView(bottomCount, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(12) })
            addView(cancel, LinearLayout.LayoutParams(
                dp(130), dp(44)
            ))
            addView(deleteBtn, LinearLayout.LayoutParams(
                dp(210), dp(44)
            ).apply { marginStart = dp(14) })
        }
        content.addView(buttons, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })

        panel.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnDismissListener { scope.cancel() }
            d.setOnShowListener { focusRows.firstOrNull()?.requestFocus() }
            bindBoundary(panel, focusRows + listOf(cancel, deleteBtn))
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER); setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(760), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
        updateCounts()
    }

    // ================= Row builder =================

    private fun buildRow(index: Int, cartoon: GiteeShareStore.SharedCartoon): View {
        // Row 胶囊：实底 sapphire_800 + 1px soft；选中态 = 2px NEON_CYAN 描边 + 左侧霓虹竖条 + 字色 cyan
        val row = FrameLayout(context).apply {
            isFocusable = true; isFocusableInTouchMode = false; isClickable = true
            clipChildren = false; clipToPadding = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = hudCapsule(NEON_CYAN, SAPPHIRE_800)
            setPadding(dp(12), dp(8), dp(14), dp(8))
        }
        // 选中态：左侧 2dp 霓虹青发光竖条（5%~10% 行高）
        val selectBar = View(context).apply {
            visibility = View.GONE
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(40,51,230,255), NEON_CYAN, Color.argb(40,51,230,255)))
        }
        row.addView(selectBar, FrameLayout.LayoutParams(
            dp(2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL
        ).apply { leftMargin = dp(4); topMargin = dp(10); bottomMargin = dp(10) })

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        // checkbox: monospace ☐/☑ 字色 TEXT_2 -> NEON_CYAN
        val check = monospaced(TEXT_2, 22f, "\u2610").apply {
            setPadding(0, 0, dp(10), 0); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        body.addView(check, LinearLayout.LayoutParams(dp(34), dp(34)))
        // thumbnail 12dp clipped
        val thumb = ClippedImageView(context).apply {
            setCircle(false)
            setCornerRadius(dp(12).toFloat())
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_thumb_default)
        }
        body.addView(thumb, LinearLayout.LayoutParams(dp(50), dp(66)).apply { marginEnd = dp(12) })
        loadThumb(cartoon.cover, thumb)
        // info stack: title (mono+bold title_sm) + meta line (count badge + adapter mono) + url (TEXT_3)
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isFocusable = false }
        info.addView(TextView(context).apply {
            text = cartoon.title.ifBlank { cartoon.detailUrl }
            textSize = CartoonDesign.Type.TITLE_SM
            typeface = MONOSPACE_BOLD
            setTextColor(TEXT_0); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(buildStatusChip(cartoon.episodeCount))
        meta.addView(monospaced(TEXT_2, CartoonDesign.Type.CAPTION,
            "  ·  ${cartoon.adapterName.ifBlank { "generic" }}"
        ).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        info.addView(meta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        info.addView(monospaced(TEXT_3, CartoonDesign.Type.STATUS, cartoon.detailUrl).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        body.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL
        ).apply { leftMargin = dp(20) })

        fun refresh() {
            val on = selected.contains(cartoon.cartoonId)
            check.text = if (on) "\u2611" else "\u2610"
            check.setTextColor(if (on) NEON_CYAN else TEXT_2)
            selectBar.visibility = if (on) View.VISIBLE else View.GONE
        }
        refresh()
        row.setOnFocusChangeListener { _, has ->
            row.background = hudCapsule(NEON_CYAN, SAPPHIRE_800, focused = has)
            if (has) FocusFxHelper.applyFocusFxState(
                row, true, cornerRadiusDp = INNER_R
            ) else row.foreground = null
        }
        row.setOnKeyListener { _, keyCode, e ->
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                && e.action == KeyEvent.ACTION_UP) {
                toggle(cartoon.cartoonId, ::refresh); true
            } else false
        }
        row.setOnClickListener { toggle(cartoon.cartoonId, ::refresh) }
        return row
    }

    private fun buildStatusChip(count: Int): View {
        val c: Int; val bg: Int; val txt: String
        when {
            count <= 0    -> { c = NEON_CYAN; bg = Color.argb(170, 14, 70, 90); txt = "STREAMING" }
            count >= 120  -> { c = DATA_GREEN; bg = Color.argb(170, 14, 70, 50); txt = "DONE · $count" }
            else          -> { c = NEON_CYAN; bg = Color.argb(170, 14, 70, 90); txt = "EP $count" }
        }
        return FrameLayout(context).apply {
            background = hudCapsule(c, base = bg, cornerDp = 999)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            addView(monospaced(c, CartoonDesign.Type.BADGE, txt))
        }
    }

    // ================= Footer button factory =================

    private fun buildFooterButton(
        label: String,
        accent: Int,
        primary: Boolean,
        click: () -> Unit
    ): TextView = TextView(context).apply {
        text = label; typeface = MONOSPACE_BOLD; gravity = Gravity.CENTER
        textSize = CartoonDesign.Type.CAPTION; letterSpacing = 0.06f
        val disabledFg = TEXT_3
        setTextColor(if (primary) accent else TEXT_0)
        isFocusable = true; isClickable = true
        // Primary: outline 2px (filled in sapphire_700 only when focus, accent never filled)
        background = hudCapsule(
            accent = accent,
            base = if (primary) SAPPHIRE_900 else SAPPHIRE_800,
            focused = false,
            alphaFill = if (primary) 0 else 255
        ).also { bg ->
            if (primary) {
                // Primary 按钮永远是 outline 风：先画 1px 霓虹描边，底是全透 HUD
                bg.setColor(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)))
                bg.setStroke(dp(1), Color.argb(200, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
        }
        tag = Triple(accent, primary, disabledFg)

        setOnFocusChangeListener { v, has ->
            @Suppress("UNCHECKED_CAST")
            val tagTriple = v.tag as Triple<Int, Boolean, Int>
            val (a, pri, df) = tagTriple
            val acc: Int = a
            if (v.isEnabled.not()) {
                setTextColor(df); return@setOnFocusChangeListener
            }
            setTextColor(if (pri) acc else if (has) acc else TEXT_0)
            val newBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(INNER_R).toFloat()
                val baseTint = if (pri) Color.argb(if (has) 90 else 40, Color.red(acc), Color.green(acc), Color.blue(acc))
                               else SAPPHIRE_800
                setColor(baseTint)
                setStroke(if (has) dp(2) else dp(1),
                    if (has) acc else (if (pri) Color.argb(180, Color.red(acc), Color.green(acc), Color.blue(acc)) else SOFT_STROKE))
            }
            v.background = newBg
            if (has) FocusFxHelper.applyFocusFxState(v, true, cornerRadiusDp = INNER_R)
            else v.foreground = null
        }
        setOnClickListener { click() }
    }

    private fun toggle(id: String, refresh: () -> Unit) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        refresh(); updateCounts()
    }

    private fun updateCounts() {
        val s = selected.size; val t = cartoons.size
        val hdr = "MY CARTOONS" + if (s > 0) " · SELECTED $s" else ""
        titleCount.text = hdr
        val sb = SpannableStringBuilder()
        val a = "SEL "; val b = "$s"; val c = " / "; val d = "$t"; val e = " ENTRIES"
        sb.append(a).append(b).append(c).append(d).append(e)
        val p1 = a.length; val p2 = p1 + b.length; val p3 = p2 + c.length; val p4 = p3 + d.length
        sb.setSpan(ForegroundColorSpan(TEXT_3), 0, p1, 0)
        sb.setSpan(ForegroundColorSpan(NEON_CYAN), p1, p2, 0)
        sb.setSpan(ForegroundColorSpan(TEXT_3), p2, p3, 0)
        sb.setSpan(ForegroundColorSpan(DATA_GREEN), p3, p4, 0)
        sb.setSpan(ForegroundColorSpan(TEXT_3), p4, sb.length, 0)
        bottomCount.text = sb
        val enabled = s > 0 && deleteJob?.isActive != true
        deleteBtn.isEnabled = enabled
        @Suppress("UNCHECKED_CAST")
        val tagTriple2 = deleteBtn.tag as Triple<Int, Boolean, Int>
        val (accent, primary, _) = tagTriple2
        val acc: Int = accent
        deleteBtn.alpha = if (enabled) 1f else 0.4f
        deleteBtn.setTextColor(if (enabled) acc else TEXT_3)
        // 禁用时用 soft stroke，启用时回到 outline 风
        deleteBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(INNER_R).toFloat()
            if (enabled && primary as Boolean) {
                setColor(Color.argb(40, Color.red(acc), Color.green(acc), Color.blue(acc)))
                setStroke(dp(1), Color.argb(200, Color.red(acc), Color.green(acc), Color.blue(acc)))
            } else {
                setColor(SAPPHIRE_900)
                setStroke(dp(1), SOFT_STROKE)
            }
        }
    }

    // ================= Delete flow =================

    private fun onDeleteClicked() {
        if (selected.isEmpty()) { toast("Please select entries first"); return }
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

    // ================= 删除二次确认：Magenta Danger Sci-Fi =================
    private fun showConfirmDialog(count: Int, preview: Preview) {
        val parent = dialog ?: return
        // 面板：hudPanel with MAGENTA accent + SAPPHIRE_900 底层
        val panel = FrameLayout(context).apply {
            background = hudPanel(NEON_MAGENTA, SAPPHIRE_900)
            foreground = scanlineOverlay()
            clipChildren = true; clipToPadding = true
            val padX = dp(24); val padY = dp(20)
            setPadding(padX, padY, padX, padY)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        }
        // Header: magenta rounded square warning (not circle)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        header.addView(FrameLayout(context).apply {
            val size = dp(48); layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(14) }
            background = hudCapsule(NEON_MAGENTA, SAPPHIRE_800, INNER_R)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_parse_fail)
                imageTintList = android.content.res.ColorStateList.valueOf(NEON_MAGENTA)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
        })
        val tb = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isFocusable = false }
        tb.addView(TextView(context).apply {
            text = "PURGE $count ENTRIES?"; typeface = MONOSPACE_BOLD; textSize = 20f
            setTextColor(TEXT_0); letterSpacing = 0.05f; maxLines = 1
        })
        tb.addView(monospaced(TEXT_3, CartoonDesign.Type.STATUS, "Destructive · Gitee Cloud Write · No Rollback"))
        header.addView(tb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 分隔：霓虹品红 1px 横线（中间粗两端渐变）
        content.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(0, 255, 62, 165), NEON_MAGENTA, Color.argb(0, 255, 62, 165)))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(14); bottomMargin = dp(14) })

        // 破坏栅格摘要：三行 grid 风格 summary cards
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val builder = mutableListOf<Triple<CharSequence, CharSequence, Int>>()  // flag, text, accent
        if (preview.adapterDrops > 0) {
            builder += Triple("[-]", "drop unused adapter rules × ${preview.adapterDrops}", DATA_GREEN)
        }
        if (preview.adapterKept > 0) {
            builder += Triple("[!]", "keep shared adapter files × ${preview.adapterKept} (in use)", NEON_CYAN)
        }
        if (builder.isEmpty()) {
            builder += Triple("[·]", "no adapter effect — only index + cache affected", TEXT_3)
        }
        builder += Triple("[!]", "will write Gitee repo `bdCasttv/video-source` — irreversible", NEON_MAGENTA)

        builder.forEach { (flag, text, ac) ->
            grid.addView(FrameLayout(context).apply {
                background = hudCapsule(SOFT_STROKE, base = SAPPHIRE_800, cornerDp = INNER_R)
                val pv = dp(8); val ph = dp(10)
                setPadding(ph, pv, ph, pv)
                val row = LinearLayout(this@CartoonManagementDialog.context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                // monospace colored flag
                row.addView(monospaced(ac, CartoonDesign.Type.CAPTION, flag).apply {
                    minWidth = dp(40); setPadding(0, 0, dp(8), 0)
                })
                row.addView(monospaced(TEXT_1, CartoonDesign.Type.BODY, text).apply {
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(row)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        content.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        // monospace 验证码风格行："CONFIRM > BACK = CANCEL" 用 magenta 单色 monospace
        content.addView(monospaced(NEON_MAGENTA, CartoonDesign.Type.CAPTION,
            "⚠ BACK key cancels automatically · focus defaults to [ABORT]"
        ).apply {
            setPadding(dp(2), dp(6), 0, 0); typeface = MONOSPACE_BOLD
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Buttons: ABORT (cancel) + EXECUTE (danger primary magenta outline)
        val btns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        val abort = buildFooterButton("ABORT", NEON_CYAN, primary = false) { /* dismiss */ }
        val confirm = buildFooterButton("EXECUTE PURGE", NEON_MAGENTA, primary = true) { /* confirm */ }
        btns.addView(abort, LinearLayout.LayoutParams(dp(140), dp(44)))
        btns.addView(confirm, LinearLayout.LayoutParams(dp(230), dp(44)).apply { marginStart = dp(14) })
        content.addView(btns, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(20); gravity = Gravity.END })

        panel.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dlg = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        abort.setOnClickListener { dlg.dismiss() }
        confirm.setOnClickListener { dlg.dismiss(); doDelete() }
        dlg.setOnShowListener { abort.requestFocus() }
        dlg.window?.takeIf { parent.isShowing }?.let { win ->
            dlg.show()
            win.setBackgroundDrawableResource(android.R.color.transparent)
            win.setLayout(dp(700), WindowManager.LayoutParams.WRAP_CONTENT)
            win.setGravity(Gravity.CENTER)
            win.decorView.isFocusable = true
            win.decorView.setOnKeyListener { _, keyCode, ev ->
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (ev.action == KeyEvent.ACTION_DOWN) dlg.dismiss()
                    true
                } else false
            }
        }
    }

    private fun doDelete() {
        val ids = selected.toList(); if (ids.isEmpty()) return
        deleteBtn.isEnabled = false; deleteBtn.alpha = 0.4f
        bottomCount.text = monospaced(NEON_CYAN, CartoonDesign.Type.BODY, "> PURGING ${ids.size} ...").text
        bottomCount.setTextColor(TEXT_0); bottomCount.typeface = MONOSPACE_BOLD
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
                    ok > 0 && fail == 0 -> "✓ PURGED $ok" to DATA_GREEN
                    ok == 0 -> "× ALL FAILED - CHECK NETWORK" to NEON_MAGENTA
                    else -> "OK $ok / FAIL $fail" to NEON_CYAN
                }
                bottomCount.text = s; bottomCount.setTextColor(c); bottomCount.typeface = MONOSPACE_BOLD
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

    companion object {
        private val MONOSPACE_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
}

// === 私有的 DrawableCompat/ColorInt 存根：不使用 CartoonDesign 外部依赖 ===
private fun Drawable.tinted(@ColorInt c: Int): Drawable = mutate().also { d -> DrawableCompat.setTint(d, c) }
