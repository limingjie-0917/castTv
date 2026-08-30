package com.bd.casttv.ui.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import androidx.annotation.IntRange
import kotlin.math.roundToInt

/**
 * 动画城设计令牌（CartoonCityPage + CartoonDetailPage 共用）。
 * Premium Media / Apple TV 级电影质感语言：
 *  - 深色基底 + 单一琥珀金强调色，三档冷灰做信息层次（主/次/辅助）
 *  - 一套 Radius 尺度（SM=12 / MD=16 / LG=20 / XL=24），卡片/徽章/面板/浮层各守其值
 *  - Liquid Glass 近似：LayerDrawable 叠层 -> 内高光 + 内阴影 + 描边
 *  - 所有令牌从 DisplayMetrics density 产出 px 值，保证多屏一致性
 *
 * 三档：VARIANCE=8 / MOTION=6 / DENSITY=4
 */
object CartoonDesign {

    /** 半径尺度：12 / 16 / 20 / 24 */
    enum class Radius(val dp: Int) { SM(12), MD(16), LG(20), XL(24) }

    /** 色板 —— 单一琥珀强调色 + 冷灰三档 + 深靛基底。 */
    object Palette {
        @ColorInt val ACCENT: Int = Color.rgb(245, 196, 81)
        @ColorInt val ACCENT_DIM: Int = Color.rgb(198, 152, 48)
        @ColorInt val TEXT_PRIMARY: Int = Color.rgb(244, 246, 252)
        @ColorInt val TEXT_SECONDARY: Int = Color.rgb(198, 206, 226)
        @ColorInt val TEXT_MUTED: Int = Color.rgb(148, 158, 182)
        @ColorInt val SURFACE_0: Int = Color.rgb(12, 14, 24)
        @ColorInt val SURFACE_1: Int = Color.rgb(18, 22, 38)
        @ColorInt val SURFACE_2: Int = Color.rgb(28, 34, 56)
        @ColorInt val STROKE_SOFT: Int = Color.argb(110, 90, 104, 146)
        @ColorInt val STROKE_HARD: Int = Color.argb(190, 170, 182, 214)
        @ColorInt val BADGE_INFO_BG: Int = Color.argb(205, 30, 54, 118)
        @ColorInt val BADGE_INFO_FG: Int = Color.rgb(214, 226, 255)
        @ColorInt val BADGE_SUCCESS_BG: Int = Color.argb(210, 22, 92, 66)
        @ColorInt val BADGE_SUCCESS_FG: Int = Color.rgb(210, 248, 226)
        @ColorInt val BADGE_WARN_BG: Int = Color.argb(215, 130, 70, 18)
        @ColorInt val BADGE_WARN_FG: Int = Color.rgb(255, 236, 210)
        @ColorInt val BADGE_ACCENT_BG: Int = Color.argb(220, 120, 90, 16)
        @ColorInt val BADGE_ACCENT_FG: Int = Color.rgb(255, 244, 214)
        /** 语义纯色（85%~92% 不透明，用于文案/图标/小色块）。 */
        @ColorInt val INFO: Int = Color.rgb(118, 156, 238)
        @ColorInt val SUCCESS: Int = Color.rgb(82, 208, 146)
        @ColorInt val WARN: Int = Color.rgb(240, 172, 76)
        @ColorInt val DANGER: Int = Color.rgb(228, 84, 84)
    }

    /** Typography 层级（sp 值，交给 TextView.textSize 自动按密度转换）。 */
    object Type {
        const val DISPLAY: Float = 32f  // 详情页主标题
        const val TITLE_LG: Float = 24f // 列表卡标题（放大一档）
        const val TITLE_MD: Float = 15f // 次级栏目标题
        const val TITLE_SM: Float = 13f // 列表卡标题 baseline
        const val BODY: Float = 15f     // 详情简介
        const val META: Float = 13f     // 详情元信息
        const val CAPTION: Float = 12f  // 次级行/辅助说明
        const val BADGE: Float = 11f    // 徽章小字
        const val STATUS: Float = 12f   // 状态文字
    }

    // ---- px helpers ----
    fun dp(ctx: Context, dp: Int): Int = (dp * ctx.resources.displayMetrics.density).roundToInt()
    fun dp(ctx: Context, dp: Float): Int = (dp * ctx.resources.displayMetrics.density).roundToInt()
    fun sp(ctx: Context, sp: Float): Float = sp * ctx.resources.displayMetrics.scaledDensity

    /**
     * 液态玻璃近似：返回 LayerDrawable，按 radius + 层级渲染。
     *  - base：顶部稍亮 → 底部稍暗的深靛渐变
     *  - inner highlight：上半 1px 白色高光（16% 透明度）
     *  - inner shadow：下半柔和内阴影（黑色 24%）
     *  - stroke：聚焦态外画 2px 高光（淡琥珀/冷白二选一）
     *  - tintMode：TRUE = 深色卡片、ELEVATED = 浮层面板、ACCENT = 强调卡（管理卡/CTA 底色）
     */
    enum class TintMode { BASE, ELEVATED, ACCENT }

    /**
     * 磨砂玻璃（Frosted Glass）染色膜：5 档语义。
     * 磨砂玻璃 ≠ 液态玻璃：液态玻璃是"厚实不透明玻璃片（高对比 + 实底）"，
     * 磨砂玻璃是"高透 base + 染色膜 + 软白折射描边 + veil 微粒 + 加厚外阴影"——
     * 视觉上"浮在内容之上"，适合弹窗 / 二次确认浮层。
     *
     * 透明度基线：base alpha ≈ 0.42~0.48（107~122），文字对比度靠 veil + 染色膜双保险。
     * dye（染色膜）：低饱和单色偏光 14%~18% 与 SURFACE_0 混色，避免"AI 彩虹毛玻璃"。
     */
    enum class FrostKind {
        /** 主浮层：冷青染色膜。用于管理弹窗主面板、详情浮层面板。 */
        COOL,
        /** 琥珀暖浮层：ACCENT 染色膜。用于动画城主页面板、管理弹窗 CTA 背景。 */
        WARM,
        /** 危险浮层：冷玫红染色膜。用于删除二次确认根面板。 */
        DANGER,
        /** 信息浮层：浅冷蓝染色膜。用于 status chip / 浮层内摘要小卡（信息类）。 */
        INFO,
        /** 纯净浮层：几乎不染，只做冷灰 8% 微染色。用于中性摘要 chip / 空态背景。 */
        PURE
    }

    /** 磨砂玻璃：6 层 LayerDrawable 骨架。与 liquidGlassDrawable 语义分离，不混实现。 */
    fun frostedGlassDrawable(
        ctx: Context,
        radius: Radius,
        frost: FrostKind = FrostKind.COOL,
        @ColorInt stroke: Int = Color.argb(170, 220, 228, 246),  // 软白折射描边
        strokePx: Int = Math.max(1, dp(ctx, 1))
    ): LayerDrawable {
        val rPx = dp(ctx, radius.dp).toFloat()
        // ---- 染色膜（语义偏光 14%~18%，与 SURFACE_0 混色）----
        val (dyeColor, topBoost, baseAlpha) = when (frost) {
            FrostKind.COOL -> Triple(Color.rgb(96, 152, 220), 10, 112)
            FrostKind.WARM -> Triple(Color.rgb(220, 170, 72), 16, 118)
            FrostKind.DANGER -> Triple(Color.rgb(200, 90, 112), 12, 122)
            FrostKind.INFO -> Triple(Color.rgb(120, 164, 230), 8, 108)
            FrostKind.PURE -> Triple(Color.rgb(140, 152, 176), 4, 107)
        }
        val s0 = Palette.SURFACE_0
        val top = mixColor(s0, dyeColor, 0.18f)
        val bot = mixColor(s0, dyeColor, 0.12f)
        val topA = withAlpha(setLum(top, lum(top) + topBoost), baseAlpha + 12)
        val botA = withAlpha(bot, baseAlpha - 6)
        // Layer 0：磨砂 base（高透 + 染色膜渐变）
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(topA, botA)).apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = rPx
            setStroke(strokePx, stroke)
        }
        // Layer 1：软白折射"内描边"（靠外 1px inset 的全圆角透明图 + 42% 白边）——
        // 这一层是磨砂和液态玻璃的视觉分水岭：磨砂的描边是"向内折射的软白边"，液态玻璃是硬外描边。
        val innerStroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = rPx
            setColor(Color.argb(0, 0, 0, 0))
            setStroke(Math.max(1, dp(ctx, 1)), Color.argb(106, 255, 255, 255))
        }
        // Layer 2：top highlight（加厚 26dp 内高光，磨砂要"显厚"，所以高光比液态玻璃更宽）
        val topGlow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.argb(64, 255, 255, 255),
            Color.argb(0, 255, 255, 255)
        )).apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = rPx
            setSize(-1, dp(ctx, 26))
        }
        // Layer 3：bottom inner shadow（加厚 30dp，黑色 32%）——磨砂比液态玻璃"体积感更强"
        val bottomShadow = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            Color.argb(96, 0, 0, 0),
            Color.argb(0, 0, 0, 0)
        )).apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = rPx
            setSize(-1, dp(ctx, 30))
        }
        // Layer 4：veil 微粒膜（用斜向 128° 细线性渐变模拟磨砂散射，避免纯平透明）
        val veil = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            Color.argb(26, 255, 255, 255),
            Color.argb(10, 255, 255, 255),
            Color.argb(20, 255, 255, 255),
            Color.argb(8, 255, 255, 255)
        )).apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = rPx
        }
        val layers = arrayOf(base, veil, innerStroke, topGlow, bottomShadow)
        return LayerDrawable(layers).apply {
            val h1 = dp(ctx, 1); val h2 = dp(ctx, 2)
            // veil 贴满全矩形，不 inset，保证散射膜连续
            setLayerGravity(2, Gravity.FILL)            // innerStroke 用 inset 1px 内缩
            setLayerInset(2, h1, h1, h1, h1)
            setLayerGravity(3, Gravity.TOP)             // topGlow 贴顶
            setLayerInset(3, h2, h2, h2, 0)
            setLayerGravity(4, Gravity.BOTTOM)          // bottomShadow 贴底
            setLayerInset(4, h2, 0, h2, h2)
        }
    }

    /** 把磨砂玻璃面板切到聚焦态：软白描边 → 粗 2px 琥珀 + veil 加亮 + innerHighlight 提升 alpha。 */
    fun frostedGlassToFocused(ctx: Context, card: View, focused: Boolean) {
        val layers = card.background as? LayerDrawable ?: return
        val base = layers.getDrawable(0) as? GradientDrawable ?: return
        val veil = layers.getDrawable(1) as? GradientDrawable ?: return
        val glow = layers.getDrawable(3) as? GradientDrawable ?: return
        if (focused) {
            base.setStroke(Math.max(2, dp(ctx, 2)), Palette.ACCENT)
            veil.alpha = 78
            glow.alpha = 140
        } else {
            base.setStroke(Math.max(1, dp(ctx, 1)), Color.argb(170, 220, 228, 246))
            veil.alpha = 255
            glow.alpha = 255
        }
    }

    // ---- 颜色辅助（lum/setLum 给磨砂染色膜计算用）----
    private fun lum(@ColorInt c: Int): Int =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()
    @ColorInt
    private fun setLum(@ColorInt c: Int, targetLum: Int): Int {
        val cur = lum(c).coerceAtLeast(1)
        val tl = targetLum.coerceIn(0, 255)
        val r = (Color.red(c) * tl / cur).coerceIn(0, 255)
        val g = (Color.green(c) * tl / cur).coerceIn(0, 255)
        val b = (Color.blue(c) * tl / cur).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    fun liquidGlassDrawable(
        ctx: Context,
        radius: Radius,
        tint: TintMode = TintMode.BASE,
        @ColorInt stroke: Int = Palette.STROKE_SOFT,
        strokePx: Int = Math.max(1, dp(ctx, 1))
    ): LayerDrawable {
        val rPx = dp(ctx, radius.dp).toFloat()
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, when (tint) {
            TintMode.BASE -> intArrayOf(
                Color.argb(255, 34, 42, 68),
                Color.argb(255, 18, 22, 40)
            )
            TintMode.ELEVATED -> intArrayOf(
                Color.argb(230, 44, 52, 82),
                Color.argb(225, 22, 26, 48)
            )
            TintMode.ACCENT -> intArrayOf(
                Color.argb(240, 78, 56, 16),
                Color.argb(240, 38, 26, 8)
            )
        }).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rPx
            setStroke(strokePx, stroke)
        }
        // 上半部：内高光（白色 16%） —— 用大半径的顶部内描边近似
        val topGlow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.argb(48, 255, 255, 255),
            Color.argb(0, 255, 255, 255)
        )).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rPx
            setSize(-1, dp(ctx, 22))
        }
        // 下半部：内阴影（黑色 24%）
        val bottomShadow = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(
            Color.argb(72, 0, 0, 0),
            Color.argb(0, 0, 0, 0)
        )).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rPx
            setSize(-1, dp(ctx, 26))
        }
        // LayerDrawable 图层顺序：0 = base，1 = topGlow（贴顶），2 = bottomShadow（贴底）
        val layers = arrayOf(base, topGlow, bottomShadow)
        return LayerDrawable(layers).apply {
            val insetH = dp(ctx, 1)
            val insetV = dp(ctx, 1)
            setLayerInset(1, insetH, insetV, insetH, 0)          // topGlow 贴顶部
            setLayerGravity(1, Gravity.TOP)
            setLayerInset(2, insetH, 0, insetH, insetV)         // bottomShadow 贴底部
            setLayerGravity(2, Gravity.BOTTOM)
        }
    }

    /** 胶囊徽章：全圆角 + 1px 内描边（海报式徽标/标签统一用这个）。 */
    fun capsuleBadge(ctx: Context, @ColorInt bg: Int, @ColorInt stroke: Int = Palette.STROKE_SOFT): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 9999f
            setColor(bg)
            setStroke(Math.max(1, dp(ctx, 1)), stroke)
        }
    }

    /**
     * 把液态玻璃卡切到聚焦态：
     *  · 聚焦：4px 暖黄实描边（base 层唯一边框）+ 高光层满亮度
     *  · 默认：1px STROKE_SOFT 描边 + 高光层 48 透明度
     *
     * 2026-08-30 修复：之前聚焦态同时在 base(4px) 和 glow(1px stroke+半透填充) 画了
     * 两层暖黄边框，导致"两个重叠的边框"。现在聚焦态 glow 层完全透明（无描边/无填充），
     * 只保留 base 的 4px 单层边框，视觉干净清晰。
     */
    fun liquidGlassToFocused(ctx: Context, card: View, focused: Boolean) {
        val layers = card.background as? LayerDrawable ?: return
        val base = layers.getDrawable(0) as? GradientDrawable ?: return
        val glow = layers.getDrawable(1) as? GradientDrawable ?: return
        if (focused) {
            // 唯一边框：4px 暖黄实描边（base 层）
            base.setStroke(dp(ctx, 4), Palette.ACCENT)
            // glow 层聚焦态完全透明：不画描边/不画填充，避免与 base 边框重叠
            glow.mutate()
            (glow as? GradientDrawable)?.apply {
                setStroke(0, Color.TRANSPARENT)
                setColor(Color.TRANSPARENT)
            }
            glow.alpha = 0
        } else {
            base.setStroke(Math.max(1, dp(ctx, 1)), Palette.STROKE_SOFT)
            // 还原 topGlow：默认贴顶部的线性内高光条（22dp 高），避免丢失液态玻璃"上亮下暗"质感
            glow.mutate()
            // 与 liquidGlassDrawable() 生产路径一致，用 base 实际圆角保证一致
            val baseCorner = (base as? GradientDrawable)?.cornerRadius?.takeIf { it > 0f }
                ?: dp(ctx, Radius.SM.dp).toFloat()
            (glow as? GradientDrawable)?.apply {
                // 先还原渐变方向与颜色（setColors 会触发 re-init；不清理 setStroke 会残留 4px）
                colors = intArrayOf(
                    Color.argb(48, 255, 255, 255),
                    Color.argb(0, 255, 255, 255)
                )
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(0, Color.TRANSPARENT)
                cornerRadius = baseCorner
                setSize(-1, dp(ctx, 22))
                setColor(Color.argb(0, 0, 0, 0))
            }
            val insetH = dp(ctx, 1); val insetV = dp(ctx, 1)
            layers.setLayerInset(1, insetH, insetV, insetH, 0)
            layers.setLayerGravity(1, Gravity.TOP)
            glow.alpha = 48
        }
    }

    /** 颜色线性插值，用于"混合半透明强调色"等场景。 */
    @ColorInt
    fun mixColor(@ColorInt a: Int, @ColorInt b: Int, @FloatRange(from = 0.0, to = 1.0) t: Float): Int {
        fun m(x: Int, y: Int): Int = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.argb(
            m(Color.alpha(a), Color.alpha(b)),
            m(Color.red(a), Color.red(b)),
            m(Color.green(a), Color.green(b)),
            m(Color.blue(a), Color.blue(b)),
        )
    }

    /** 给一个打包好的颜色设置不透明度（alpha ∈ [0,255]）。 */
    @ColorInt
    fun withAlpha(@ColorInt color: Int, @IntRange(from = 0, to = 255) alpha: Int): Int =
        (alpha shl 24) or (0x00ffffff and color)
}
