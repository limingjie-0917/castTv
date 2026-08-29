package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
 * 动画城管理对话框（多选 → 删除二次确认）。
 *
 * Taste 定向风格：【Frosted Glass · 磨砂玻璃】
 *  方向：Premium Media / Apple TV 级浮层。严格对齐 CartoonDesign.kt 磨砂令牌；
 *  不搞 template AI 毛玻璃（不靠 RenderScript / blur），用 frostedGlassDrawable
 *  的「高透 base 0.42~0.48 + 单色低饱和染色膜 + 软白折射描边 + veil 散射微粒 + 加厚
 *  高光/内阴影」5 层公式。语义染色膜：
 *   · 管理弹窗根面板 → COOL（冷青 dye）
 *   · 删除二次确认根面板 → DANGER（冷玫红 dye + DANGER 软折射描边）
 *   · 摘要小卡 → 各语义 dye 独立（INFO / SUCCESS→INFO 档 / DANGER / PURE）
 *  行卡 / 按钮仍保留液态玻璃形态：磨砂 + 液态玻璃"两材对比"是 Taste 分层语言，
 *  避免"一块大板里面又套毛玻璃"的材质堆叠滥用。
 *
 *  材质分配（更新后）：
 *   · 根面板            → frostedGlassDrawable(Radius.XL, FrostKind.COOL/DANGER) 磨砂
 *   · 行卡 / 取消按钮     → liquidGlassDrawable(Radius.MD, TintMode.BASE)          液态玻璃
 *   · 主 CTA            → liquidGlassDrawable(Radius.MD, TintMode.ACCENT)
 *   · 删除确认 CTA      → dangerGlassDrawable(Radius.MD)
 *   · 删除摘要小卡       → frostedGlassDrawable(Radius.MD, Info/Pure/Danger) 磨砂 + 染色
 *
 *  焦点态三线索：frosted panel 走 frostedGlassToFocused；liquid card 走 liquidGlassToFocused
 *                + FocusFxHelper 光边 + 字色升阶。
 *  BACK 键：删除弹窗消费最高优先级（与行为契约一致）。
 */
class CartoonManagementDialog(
    private val context: Context,
    private val cartoons: List<GiteeShareStore.SharedCartoon>,
    private val onChanged: () -> Unit
) {
    // ================= 设计（文件内唯一新增：DANGER 液态玻璃 + 辅助 dp()） =================
    private fun dp(v: Int): Int = CartoonDesign.dp(context, v)
    private fun dp(v: Float): Int = CartoonDesign.dp(context, v)

    /** 危险操作玻璃：完全复刻 liquidGlassDrawable 的 4 层骨架，用 DANGER 语义玫深色调 + 琥珀 ACCENT 聚焦态。 */
    private fun dangerGlassDrawable(
        radius: CartoonDesign.Radius = CartoonDesign.Radius.MD
    ): LayerDrawable {
        val rPx = dp(radius.dp).toFloat()
        val strokePx = dp(1)
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            // 顶层：深玫红微透 → 底层：冷灰 95%，符合玻璃"上亮下暗"
            Color.argb(235, 96, 22, 38),
            Color.argb(245, 28, 20, 40)
        )).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rPx
            setStroke(strokePx, CartoonDesign.Palette.DANGER)
        }
        val topGlow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.argb(60, 255, 255, 255),
            Color.argb(0, 255, 255, 255)
        )).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rPx
            setSize(-1, dp(22))
        }
        val bottomShadow = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            Color.argb(84, 0, 0, 0),
            Color.argb(0, 0, 0, 0)
        )).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rPx
            setSize(-1, dp(26))
        }
        return LayerDrawable(arrayOf(base, topGlow, bottomShadow)).apply {
            val insetH = dp(1); val insetV = dp(1)
            setLayerInset(1, insetH, insetV, insetH, 0); setLayerGravity(1, Gravity.TOP)
            setLayerInset(2, insetH, 0, insetH, insetV); setLayerGravity(2, Gravity.BOTTOM)
        }
    }

    /** 把 dangerGlass 切到聚焦态：2px DANGER 粗描边 + 高光加亮。 */
    private fun dangerGlassToFocused(card: View, focused: Boolean) {
        val layers = card.background as? LayerDrawable ?: return
        val base = layers.getDrawable(0) as? GradientDrawable ?: return
        val glow = layers.getDrawable(1) as? GradientDrawable ?: return
        if (focused) {
            base.setStroke(dp(2), CartoonDesign.Palette.DANGER)
            glow.alpha = 140
        } else {
            base.setStroke(dp(1), CartoonDesign.Palette.DANGER)
            glow.alpha = 60
        }
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
        // ===== 根面板：XL 磨砂玻璃（FrostKind.COOL = 冷青染色膜 · 高透浮层）
        // 外面再套一层 dim 容器：Android TV Dialog 默认背景是透明，浮层与底图之间缺"景深层"
        // 会让磨砂 base 0.48 看起来像"幽灵面板"。加一个 28% 黑 soft dim + 加厚外阴影，
        // 让磨砂染色膜的冷青光能正确"挂在"底图之上，而不是漂在空气中。
        val panel = FrameLayout(context).apply {
            background = CartoonDesign.frostedGlassDrawable(
                context, CartoonDesign.Radius.XL, CartoonDesign.FrostKind.COOL
            )
            // 加厚外阴影：磨砂玻璃浮层必须和液态玻璃面板在"z 方向"差一个档位，
            // 阴影用染色膜冷青 tint（taste rule: shadow tinted to bg hue）。
            elevation = dp(12).toFloat()
            clipChildren = true; clipToPadding = true
            val padX = dp(24); val padY = dp(22)
            setPadding(padX, padY, padX, padY)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        // ===== Header：琥珀玻璃圆形 sticker + 标题 + 右侧 TOTAL 胶囊徽章 =====
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        // 贴纸：48dp 圆形 ACCENT 液态玻璃 + 居中卡通图标
        header.addView(FrameLayout(context).apply {
            val stickerPad = dp(10)
            val iconSize = dp(48)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(14) }
            // Radius 999 = 圆，用 ACCENT 液态玻璃 + 让 cornerRadius 满圆
            background = CartoonDesign.liquidGlassDrawable(
                context, CartoonDesign.Radius.LG, CartoonDesign.TintMode.ACCENT
            ).also { ld ->
                // 基础层的 cornerRadius 切到 LG 20dp，但 48x48 框里我们要真正圆 → 手动把 layer 0 cornerRadius 拉满 24dp
                (ld.getDrawable(0) as? GradientDrawable)?.cornerRadius = 9999f
                (ld.getDrawable(1) as? GradientDrawable)?.cornerRadius = 9999f
                (ld.getDrawable(2) as? GradientDrawable)?.cornerRadius = 9999f
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_more_cartoon)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    CartoonDesign.Palette.BADGE_ACCENT_FG
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(stickerPad, stickerPad, stickerPad, stickerPad) })
        })
        val titleBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = false
        }
        titleCount = TextView(context).apply {
            text = "我的动画城"
            textSize = CartoonDesign.Type.TITLE_LG
            setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        titleBox.addView(titleCount)
        // 副标题：STATUS 辅助文案
        titleBox.addView(TextView(context).apply {
            text = "管理云端收藏 · 多选后批量删除"
            textSize = CartoonDesign.Type.STATUS
            setTextColor(CartoonDesign.Palette.TEXT_MUTED)
        })
        header.addView(titleBox, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        // TOTAL 胶囊徽章
        val totalBadge = FrameLayout(context).apply {
            background = CartoonDesign.capsuleBadge(
                context, CartoonDesign.Palette.BADGE_INFO_BG
            )
            val ph = dp(14); val pv = dp(6); setPadding(ph, pv, ph, pv)
        }.also { frame ->
            frame.addView(TextView(context).apply {
                text = "共 ${cartoons.size} 部"
                textSize = CartoonDesign.Type.BADGE
                setTextColor(CartoonDesign.Palette.BADGE_INFO_FG)
                gravity = Gravity.CENTER
            })
        }
        header.addView(totalBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 操作提示行：STATUS 字，软提示色，不再用 monospace 命令提示符风
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(10), 0, dp(6))
            addView(TextView(context).apply {
                text = "方向键"
                textSize = CartoonDesign.Type.STATUS
                setTextColor(CartoonDesign.Palette.INFO)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = " 移动焦点 · "
                textSize = CartoonDesign.Type.STATUS
                setTextColor(CartoonDesign.Palette.TEXT_MUTED)
            })
            addView(TextView(context).apply {
                text = "确定键"
                textSize = CartoonDesign.Type.STATUS
                setTextColor(CartoonDesign.Palette.ACCENT)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = " 勾选条目 · 勾选后点「删除选中」"
                textSize = CartoonDesign.Type.STATUS
                setTextColor(CartoonDesign.Palette.TEXT_MUTED)
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ===== 列表（MD 液态玻璃行）=====
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = true; clipToPadding = true
        }
        val focusRows = mutableListOf<View>()
        cartoons.forEachIndexed { i, c ->
            val row = buildRow(i, c)
            focusRows += row
            list.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84)
            ).apply { topMargin = if (i == 0) dp(2) else dp(8) })
        }
        val scroll = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(list)
        }
        content.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(340)
        ).apply { topMargin = dp(4) })

        // ===== Bottom：计数左 + 玻璃按钮簇右 =====
        bottomCount = TextView(context).apply {
            textSize = CartoonDesign.Type.BODY
            setTextColor(CartoonDesign.Palette.TEXT_MUTED)
        }
        val cancel = buildGlassButton(
            label = "取消",
            kind = GlassKind.BASE,
            focusCorner = CartoonDesign.Radius.MD,
        ) {
            if (deleteJob?.isActive == true) return@buildGlassButton
            dialog?.dismiss()
        }
        deleteBtn = buildGlassButton(
            label = "删除选中",
            kind = GlassKind.ACCENT,
            focusCorner = CartoonDesign.Radius.MD,
        ) {
            if (deleteJob?.isActive == true) return@buildGlassButton
            onDeleteClicked()
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
            addView(bottomCount, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = dp(12) })
            addView(cancel, LinearLayout.LayoutParams(dp(130), dp(44)))
            addView(deleteBtn, LinearLayout.LayoutParams(dp(170), dp(44)).apply { marginStart = dp(14) })
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
                setGravity(Gravity.CENTER)
                // 磨砂玻璃必须有一层"背景暗化 veil"才能让染色膜的冷青光锚定住，
                // 否则 base alpha≈0.45 的面板直接叠在亮内容上会让文字对比度崩盘。
                // 用 Window dimAmount 做系统级景深层（0.32 = 淡柔 32% 暗化），
                // 这比在 decorView 上包一层 FrameLayout 更稳，而且不干扰焦点分发。
                val dimColor = CartoonDesign.mixColor(
                    Color.rgb(6, 8, 14), Color.rgb(96, 152, 220), 0.10f
                )
                // Dialog 级 tinted dim：先铺一个 fullscreen 透明冷青 dim 层。
                setBackgroundDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(CartoonDesign.withAlpha(dimColor, 82))
                })
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                val attrs = attributes
                attrs.dimAmount = 0.32f
                attributes = attrs
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                // 让根磨砂面板只占 760dp，其余空间由上方 tinted dim 负责（看起来就像 Frosted
                // Glass 背后是柔化的场景，不是纯黑）。
                (panel.layoutParams as? FrameLayout.LayoutParams)?.apply {
                    width = dp(760)
                    height = FrameLayout.LayoutParams.WRAP_CONTENT
                    gravity = Gravity.CENTER
                }
            }
        }
        updateCounts()
    }

    // ================= 玻璃按钮工厂 =================
    private enum class GlassKind { BASE, ACCENT, DANGER }

    private fun buildGlassButton(
        label: String,
        kind: GlassKind,
        focusCorner: CartoonDesign.Radius = CartoonDesign.Radius.MD,
        click: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = CartoonDesign.Type.TITLE_SM
        setTypeface(null, Typeface.BOLD)
        when (kind) {
            GlassKind.BASE -> {
                background = CartoonDesign.liquidGlassDrawable(
                    context, CartoonDesign.Radius.MD, CartoonDesign.TintMode.BASE
                )
                setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
            }
            GlassKind.ACCENT -> {
                background = CartoonDesign.liquidGlassDrawable(
                    context, CartoonDesign.Radius.MD, CartoonDesign.TintMode.ACCENT
                )
                setTextColor(CartoonDesign.Palette.BADGE_ACCENT_FG)
            }
            GlassKind.DANGER -> {
                background = dangerGlassDrawable(CartoonDesign.Radius.MD)
                setTextColor(Color.rgb(255, 236, 236))  // 冷白微红，与 DANGER 底配合 4.5:1+
            }
        }
        isFocusable = true; isClickable = true
        tag = kind
        setOnFocusChangeListener { v, has ->
            val k = v.tag as GlassKind
            when (k) {
                GlassKind.DANGER -> {
                    dangerGlassToFocused(v, has)
                    if (has) FocusFxHelper.applyFocusFxState(
                        v, true, cornerRadiusDp = focusCorner.dp
                    ) else v.foreground = null
                }
                else -> {
                    CartoonDesign.liquidGlassToFocused(context, v, has)
                    if (has) FocusFxHelper.applyFocusFxState(
                        v, true, cornerRadiusDp = focusCorner.dp
                    ) else v.foreground = null
                }
            }
            // 字色焦点升阶：BASE → TEXT_PRIMARY -> ACCENT；ACCENT/DANGER 本身强调字色保持但 bold already
            if (v.isEnabled) {
                when (k) {
                    GlassKind.BASE -> setTextColor(
                        if (has) CartoonDesign.Palette.ACCENT
                        else CartoonDesign.Palette.TEXT_PRIMARY
                    )
                    GlassKind.ACCENT, GlassKind.DANGER -> {
                        // 聚焦 + 粗体，保持原前景色（本来就属于强强调色），不切换
                    }
                }
            }
        }
        setOnClickListener { click() }
    }

    // ================= 行 builder =================

    private fun buildRow(index: Int, cartoon: GiteeShareStore.SharedCartoon): View {
        // 行：MD 液态玻璃 BASE
        val row = FrameLayout(context).apply {
            isFocusable = true; isFocusableInTouchMode = false; isClickable = true
            clipChildren = false; clipToPadding = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = CartoonDesign.liquidGlassDrawable(
                context, CartoonDesign.Radius.MD, CartoonDesign.TintMode.BASE
            )
            setPadding(dp(12), dp(9), dp(14), dp(9))
        }
        // 选中态：左侧琥珀色垂直指示条（与 ACCENT 主色呼应，36dp 长竖胶囊贴在 padding 内）
        val selectBar = View(context).apply {
            visibility = View.GONE
            background = CartoonDesign.capsuleBadge(
                context, CartoonDesign.Palette.ACCENT, CartoonDesign.Palette.ACCENT_DIM
            )
        }
        row.addView(selectBar, FrameLayout.LayoutParams(
            dp(3), ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.START or Gravity.CENTER_VERTICAL
        ).apply { leftMargin = dp(4); topMargin = dp(10); bottomMargin = dp(10) })

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        // 选择框：34x34 BASE 液态玻璃胶囊 sticker，内放 ☐/☑ Unicode 图标
        val checkSticker = FrameLayout(context).apply {
            background = CartoonDesign.liquidGlassDrawable(
                context, CartoonDesign.Radius.SM, CartoonDesign.TintMode.BASE,
                CartoonDesign.Palette.STROKE_HARD
            )
        }
        val check = TextView(context).apply {
            text = "\u2610"; textSize = 18f; gravity = Gravity.CENTER
            setTextColor(CartoonDesign.Palette.TEXT_MUTED)
            setTypeface(null, Typeface.BOLD)
        }
        checkSticker.addView(check, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        body.addView(checkSticker, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
            marginEnd = dp(12)
        })
        // 缩略图 ClippedImageView SM(12) 真裁
        val thumb = ClippedImageView(context).apply {
            setCircle(false)
            setCornerRadius(dp(CartoonDesign.Radius.SM.dp).toFloat())
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.ic_thumb_default)
        }
        body.addView(thumb, LinearLayout.LayoutParams(dp(52), dp(66)).apply {
            marginEnd = dp(12)
        })
        loadThumb(cartoon.cover, thumb)
        // Info 列：TITLE_SM 标题 + 徽章 CAPTION 行 + STATUS URL
        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = false
        }
        val titleView = TextView(context).apply {
            text = cartoon.title.ifBlank { cartoon.detailUrl }
            textSize = CartoonDesign.Type.TITLE_SM
            setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
            setTypeface(null, Typeface.BOLD); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(titleView)
        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(buildStatusChip(cartoon.episodeCount))
        // 适配器名：CAPTION 软辅助 + 小圆点色 + 真实字（不再 monospace 拼接 "· 适配器名"）
        meta.addView(View(context).apply {
            val s = dp(4)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                marginStart = dp(10); marginEnd = dp(8)
            }
            background = CartoonDesign.capsuleBadge(
                context, CartoonDesign.withAlpha(CartoonDesign.Palette.TEXT_MUTED, 255)
            )
        })
        meta.addView(TextView(context).apply {
            text = cartoon.adapterName.ifBlank { "通用网页解析" }
            textSize = CartoonDesign.Type.CAPTION
            setTextColor(CartoonDesign.Palette.TEXT_SECONDARY)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(meta, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        info.addView(TextView(context).apply {
            text = cartoon.detailUrl
            textSize = CartoonDesign.Type.STATUS
            setTextColor(CartoonDesign.Palette.TEXT_MUTED)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        body.addView(info, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        row.addView(body, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL
        ).apply { leftMargin = dp(6) })

        fun refresh() {
            val on = selected.contains(cartoon.cartoonId)
            check.text = if (on) "\u2611" else "\u2610"
            if (on) {
                check.setTextColor(CartoonDesign.Palette.ACCENT)
                (checkSticker.background as? LayerDrawable)?.let { ld ->
                    (ld.getDrawable(0) as? GradientDrawable)?.setStroke(
                        dp(2), CartoonDesign.Palette.ACCENT
                    )
                    (ld.getDrawable(1) as? GradientDrawable)?.alpha = 110
                }
            } else {
                check.setTextColor(CartoonDesign.Palette.TEXT_MUTED)
                (checkSticker.background as? LayerDrawable)?.let { ld ->
                    (ld.getDrawable(0) as? GradientDrawable)?.setStroke(
                        dp(1), CartoonDesign.Palette.STROKE_HARD
                    )
                    (ld.getDrawable(1) as? GradientDrawable)?.alpha = 48
                }
            }
            selectBar.visibility = if (on) View.VISIBLE else View.GONE
            titleView.setTextColor(
                if (on) CartoonDesign.Palette.ACCENT
                else CartoonDesign.Palette.TEXT_PRIMARY
            )
        }
        refresh()
        row.setOnFocusChangeListener { _, has ->
            CartoonDesign.liquidGlassToFocused(context, row, has)
            if (has) FocusFxHelper.applyFocusFxState(
                row, true, cornerRadiusDp = CartoonDesign.Radius.MD.dp
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

    /** 集数胶囊徽章 — 严格走 CartoonDesign.capsuleBadge + BADGE 语义调色板。 */
    private fun buildStatusChip(count: Int): View {
        val (bg, fg, txt) = when {
            count <= 0 -> Triple(
                CartoonDesign.Palette.BADGE_WARN_BG,
                CartoonDesign.Palette.BADGE_WARN_FG,
                "待解析"
            )
            count >= 120 -> Triple(
                CartoonDesign.Palette.BADGE_SUCCESS_BG,
                CartoonDesign.Palette.BADGE_SUCCESS_FG,
                "全 ${count} 集 · 已完结"
            )
            else -> Triple(
                CartoonDesign.Palette.BADGE_ACCENT_BG,
                CartoonDesign.Palette.BADGE_ACCENT_FG,
                "更新至第 $count 集"
            )
        }
        return FrameLayout(context).apply {
            background = CartoonDesign.capsuleBadge(context, bg)
            val ph = dp(10); val pv = dp(5); setPadding(ph, pv, ph, pv)
            addView(TextView(context).apply {
                text = txt
                textSize = CartoonDesign.Type.BADGE
                setTextColor(fg)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })
        }
    }

    // ================= 勾选计数 =================

    private fun toggle(id: String, refresh: () -> Unit) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        refresh(); updateCounts()
    }

    private fun updateCounts() {
        val s = selected.size; val t = cartoons.size
        // 标题栏副标 + 选中
        titleCount.text = buildString {
            append("我的动画城")
            if (s > 0) append(" · 已选 $s")
        }
        // 底部计数：多段 Spannable
        val sb = SpannableStringBuilder()
        val a0 = "已选 "; val a1 = "$s"
        val b0 = "  /  共 "; val b1 = "$t"; val b2 = " 部"
        sb.append(a0).append(a1).append(b0).append(b1).append(b2)
        var p = 0
        sb.setSpan(ForegroundColorSpan(CartoonDesign.Palette.TEXT_SECONDARY), p, p + a0.length, 0); p += a0.length
        sb.setSpan(ForegroundColorSpan(CartoonDesign.Palette.ACCENT), p, p + a1.length, 0); p += a1.length
        sb.setSpan(ForegroundColorSpan(CartoonDesign.Palette.TEXT_SECONDARY), p, p + b0.length, 0); p += b0.length
        sb.setSpan(ForegroundColorSpan(CartoonDesign.Palette.SUCCESS), p, p + b1.length, 0); p += b1.length
        sb.setSpan(ForegroundColorSpan(CartoonDesign.Palette.TEXT_SECONDARY), p, sb.length, 0)
        bottomCount.text = sb

        val enabled = s > 0 && deleteJob?.isActive != true
        deleteBtn.isEnabled = enabled
        // ACCENT 玻璃按钮禁用态：退回到 BASE 软 + MUTED 字 + 0.45 alpha
        val kind = deleteBtn.tag as GlassKind
        if (kind == GlassKind.ACCENT) {
            deleteBtn.alpha = if (enabled) 1f else 0.45f
            deleteBtn.setTextColor(
                if (enabled) CartoonDesign.Palette.BADGE_ACCENT_FG
                else CartoonDesign.Palette.TEXT_MUTED
            )
            deleteBtn.background = if (enabled) {
                CartoonDesign.liquidGlassDrawable(
                    context, CartoonDesign.Radius.MD, CartoonDesign.TintMode.ACCENT
                )
            } else {
                CartoonDesign.liquidGlassDrawable(
                    context, CartoonDesign.Radius.MD, CartoonDesign.TintMode.BASE
                )
            }
        }
    }

    // ================= 删除流 =================

    private fun onDeleteClicked() {
        if (selected.isEmpty()) { toast("请先勾选条目"); return }
        deleteBtn.isEnabled = false; deleteBtn.alpha = 0.45f
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

    /** 磨砂玻璃摘要小卡（用于删除确认的四象限）。Taste 分层："小卡用磨砂 + 液态玻璃 sticker"。 */
    private fun glassSummaryChip(
        @ColorInt bgTint: Int,
        @ColorInt fgColor: Int,
        iconText: String,
        body: CharSequence
    ): View {
        // 语义 dye：把 4 种语义色映射到 FrostKind，不走 frosted base + 再次混色——
        // 这样每个小卡都是"真正磨砂配方"，不是液态玻璃基上叠一个 25% 染色的怪胎。
        val frost = when {
            bgTint == CartoonDesign.Palette.SUCCESS -> CartoonDesign.FrostKind.INFO      // SUCCESS 没有磨砂档，INFO 冷蓝与"保留/清理"语义最搭
            bgTint == CartoonDesign.Palette.INFO -> CartoonDesign.FrostKind.INFO
            bgTint == CartoonDesign.Palette.DANGER -> CartoonDesign.FrostKind.DANGER
            else -> CartoonDesign.FrostKind.PURE
        }
        return FrameLayout(context).apply {
            background = CartoonDesign.frostedGlassDrawable(
                context, CartoonDesign.Radius.MD, frost
            )
            elevation = dp(4).toFloat()
            val ph = dp(12); val pv = dp(10); setPadding(ph, pv, ph, pv)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            // 语义图标贴纸（液态玻璃胶囊 sticker，磨砂卡上叠液态 => 两材对比）
            row.addView(FrameLayout(context).apply {
                background = CartoonDesign.capsuleBadge(
                    context, CartoonDesign.withAlpha(bgTint, 200), CartoonDesign.withAlpha(fgColor, 255)
                )
                val ph2 = dp(6); val pv2 = dp(4); setPadding(ph2, pv2, ph2, pv2)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(10) }
                addView(TextView(context).apply {
                    text = iconText; textSize = CartoonDesign.Type.BADGE
                    setTextColor(fgColor); gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                })
            })
            row.addView(TextView(context).apply {
                text = body
                textSize = CartoonDesign.Type.TITLE_SM
                setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(row)
        }
    }

    // ================= 删除二次确认（Frosted + DANGER 染色膜） =================
    private fun showConfirmDialog(count: Int, preview: Preview) {
        val parent = dialog ?: return
        // 根面板：XL 磨砂玻璃（FrostKind.DANGER = 冷玫红染色膜）+ 软冷玫红折射描边
        // 视觉信号层级：主弹窗冷青（中性管理）→ 删除确认冷玫红（危险浮层），ACCENT 只有按钮。
        val panel = FrameLayout(context).apply {
            background = CartoonDesign.frostedGlassDrawable(
                context, CartoonDesign.Radius.XL, CartoonDesign.FrostKind.DANGER,
                stroke = Color.argb(190, 250, 178, 188)
            )
            elevation = dp(14).toFloat()
            clipChildren = true; clipToPadding = true
            val padX = dp(24); val padY = dp(22)
            setPadding(padX, padY, padX, padY)
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        }

        // Header：DANGER 玻璃方贴纸 + 标题行
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        header.addView(FrameLayout(context).apply {
            val size = dp(48)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(14) }
            background = dangerGlassDrawable(CartoonDesign.Radius.MD)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_parse_fail)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    Color.rgb(255, 236, 236)
                )
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        })
        val tb = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; isFocusable = false }
        tb.addView(TextView(context).apply {
            text = "确定删除选中的 $count 部动画吗？"
            textSize = CartoonDesign.Type.TITLE_LG
            setTypeface(null, Typeface.BOLD)
            setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
            maxLines = 2
        })
        tb.addView(TextView(context).apply {
            text = "此操作将同步修改 Gitee 云端数据，无法撤销"
            textSize = CartoonDesign.Type.STATUS
            setTextColor(CartoonDesign.Palette.TEXT_MUTED)
        })
        header.addView(tb, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        content.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 分隔：1px DANGER 软渐变横线（中间色 DANGER，两端透）
        content.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                Color.argb(0, Color.red(CartoonDesign.Palette.DANGER),
                    Color.green(CartoonDesign.Palette.DANGER),
                    Color.blue(CartoonDesign.Palette.DANGER)),
                CartoonDesign.withAlpha(CartoonDesign.Palette.DANGER, 220),
                Color.argb(0, Color.red(CartoonDesign.Palette.DANGER),
                    Color.green(CartoonDesign.Palette.DANGER),
                    Color.blue(CartoonDesign.Palette.DANGER))
            ))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(16); bottomMargin = dp(16) })

        // 玻璃摘要小卡 2~4 枚：每枚都是 BASE 玻璃 + 语义染色膜 28% 混色
        val cards = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false
        }
        if (preview.adapterDrops > 0) {
            cards.addView(glassSummaryChip(
                bgTint = CartoonDesign.Palette.SUCCESS,
                fgColor = CartoonDesign.Palette.BADGE_SUCCESS_FG,
                iconText = "清理",
                body = "将删除 ${preview.adapterDrops} 个无人引用的自定义解析规则"
            ), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        if (preview.adapterKept > 0) {
            cards.addView(glassSummaryChip(
                bgTint = CartoonDesign.Palette.INFO,
                fgColor = CartoonDesign.Palette.BADGE_INFO_FG,
                iconText = "保留",
                body = "${preview.adapterKept} 个解析规则仍被其他条目引用，不会删除"
            ), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        if (preview.adapterDrops == 0 && preview.adapterKept == 0) {
            cards.addView(glassSummaryChip(
                bgTint = CartoonDesign.Palette.TEXT_MUTED,
                fgColor = CartoonDesign.Palette.TEXT_PRIMARY,
                iconText = "缓存",
                body = "仅清理动画城索引与本地缓存，不涉及解析规则"
            ), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        cards.addView(glassSummaryChip(
            bgTint = CartoonDesign.Palette.DANGER,
            fgColor = Color.rgb(255, 236, 236),
            iconText = "云端",
            body = "将写入 Gitee 仓库 bdCasttv/video-source · 不可逆"
        ), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        content.addView(cards, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 验证码行：粗体 DANGER 色小字（不再 monospace 命令风）
        content.addView(TextView(context).apply {
            text = "按返回键自动取消 · 默认焦点在「再想想」"
            textSize = CartoonDesign.Type.CAPTION
            setTypeface(null, Typeface.BOLD)
            setTextColor(CartoonDesign.Palette.DANGER)
            setPadding(dp(2), dp(10), 0, 0)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 按钮簇：BASE「再想想」+ DANGER 玻璃「确认删除」
        val btns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        val abort = buildGlassButton("再想想", GlassKind.BASE, CartoonDesign.Radius.MD) { /* dismiss */ }
        val confirm = buildGlassButton(
            "确认删除", GlassKind.DANGER, CartoonDesign.Radius.MD
        ) { /* confirm */ }
        btns.addView(abort, LinearLayout.LayoutParams(dp(150), dp(44)))
        btns.addView(confirm, LinearLayout.LayoutParams(dp(170), dp(44)).apply {
            marginStart = dp(14)
        })
        content.addView(btns, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(22) })

        panel.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dlg = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        abort.setOnClickListener { dlg.dismiss() }
        confirm.setOnClickListener { dlg.dismiss(); doDelete() }
        dlg.setOnShowListener { abort.requestFocus() }
        dlg.window?.takeIf { parent.isShowing }?.let { win ->
            dlg.show()
            // 删除确认 dim = 冷玫红染色膜 tinted 暗化层。危险弹窗的景深层应当比主管理弹窗
            // 再厚 8%（dimAmount 0.40），信号感更强。
            val dimColor = CartoonDesign.mixColor(
                Color.rgb(10, 4, 8), Color.rgb(200, 90, 112), 0.14f
            )
            win.setBackgroundDrawable(GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(CartoonDesign.withAlpha(dimColor, 100))
            })
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attrs = win.attributes
            attrs.dimAmount = 0.40f
            win.attributes = attrs
            win.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            win.setGravity(Gravity.CENTER)
            (panel.layoutParams as? FrameLayout.LayoutParams)?.apply {
                width = dp(700)
                height = FrameLayout.LayoutParams.WRAP_CONTENT
                gravity = Gravity.CENTER
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

    private fun doDelete() {
        val ids = selected.toList(); if (ids.isEmpty()) return
        deleteBtn.isEnabled = false; deleteBtn.alpha = 0.45f
        // 计数行切到「删除中…」琥珀强调色
        bottomCount.text = "正在删除 ${ids.size} 部…"
        bottomCount.setTextColor(CartoonDesign.Palette.ACCENT)
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
                    ok > 0 && fail == 0 -> "✓ 已删除 $ok 部" to CartoonDesign.Palette.SUCCESS
                    ok == 0 -> "× 全部失败，请检查网络" to CartoonDesign.Palette.DANGER
                    else -> "成功 $ok / 失败 $fail" to CartoonDesign.Palette.ACCENT
                }
                bottomCount.text = s; bottomCount.setTextColor(c)
                bottomCount.setTypeface(null, Typeface.BOLD)
                deleteBtn.isEnabled = selected.isNotEmpty()
                deleteBtn.alpha = if (deleteBtn.isEnabled) 1f else 0.45f
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
