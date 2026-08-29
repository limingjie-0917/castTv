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

    /** 把液态玻璃卡切到聚焦态：粗描边 2px 琥珀 + 高光层加亮。 */
    fun liquidGlassToFocused(ctx: Context, card: View, focused: Boolean) {
        val layers = card.background as? LayerDrawable ?: return
        val base = layers.getDrawable(0) as? GradientDrawable ?: return
        val glow = layers.getDrawable(1) as? GradientDrawable ?: return
        if (focused) {
            base.setStroke(Math.max(2, dp(ctx, 2)), Palette.ACCENT)
            glow.alpha = 110
        } else {
            base.setStroke(Math.max(1, dp(ctx, 1)), Palette.STROKE_SOFT)
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
