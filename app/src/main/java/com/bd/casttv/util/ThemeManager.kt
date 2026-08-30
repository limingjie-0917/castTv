package com.bd.casttv.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import com.bd.casttv.R
import kotlin.math.roundToInt

/** 新框架主题切换管理器：提供默认主题 palette 与自定义主题配置。 */
object ThemeManager {

    /**
     * 磨砂玻璃状态栏（Status Bar）染色膜参数。
     *
     * 设计规范请参考 `.trae/skills/taste-ui-statusbar/SKILL.md`：
     *  1) dyeColor 与 SURFACE_0 按 dyeT 混合 → 顶部稍亮、底部稍暗的高透 base；
     *  2) 叠加散射膜、内折射边、顶部高光、底部内阴影、底部分割线；
     *  3) baseAlpha 越小越透；Custom 模式会做"极浅色染料翻倍 + 加厚阴影"的对比度兜底。
     */
    data class StatusBarFrost(
        @ColorInt val dyeColor: Int,
        val dyeT: Float,
        val baseAlphaStart: Int,
        val baseAlphaEnd: Int,
        val strokeAlpha: Int,
        val highlightAlpha: Int,
        val shadowAlpha: Int
    )

    data class ThemePalette(
        val topStatusBarGradient: IntArray,
        val pageHeaderGradient: IntArray,
        val contentPanelGradientA: String,
        val contentPanelGradientB: String,
        val contentPanelTransparency: Int,
        val dialogTitleGradient: IntArray,
        val accent: Int,
        val topStatusBarFrost: StatusBarFrost
    )

    const val STYLE_DARK_GRAY = "dark_gray"
    const val STYLE_ROYAL_BLUE = "royal_blue"
    const val STYLE_CRAYON = "crayon"
    const val STYLE_CLASSIC = "classic"
    const val STYLE_SUNSET = "sunset"
    const val STYLE_CUSTOM = "custom"

    const val CUSTOM_MODE_SOLID = "solid"
    const val CUSTOM_MODE_GRADIENT = "gradient"

    private const val PREFS = "casttv_theme_prefs"
    private const val KEY_THEME_STYLE = "theme_style"
    private const val KEY_CUSTOM_MODE = "custom_mode"
    private const val KEY_CUSTOM_COLOR_A = "custom_color_a"
    private const val KEY_CUSTOM_COLOR_B = "custom_color_b"
    private const val KEY_CUSTOM_TRANSPARENCY = "custom_transparency"

    val DEFAULT_THEMES = listOf(
        STYLE_DARK_GRAY to "深灰暖黄",
        STYLE_ROYAL_BLUE to "宝蓝浅蓝",
        STYLE_CRAYON to "蜡笔小新",
        STYLE_CLASSIC to "经典深蓝",
        STYLE_SUNSET to "暖色夕阳"
    )

    fun getStyle(context: Context): String = prefs(context).getString(KEY_THEME_STYLE, STYLE_DARK_GRAY) ?: STYLE_DARK_GRAY

    fun setStyle(context: Context, style: String) {
        val normalized = when (style) {
            STYLE_DARK_GRAY, STYLE_ROYAL_BLUE, STYLE_CRAYON, STYLE_CLASSIC, STYLE_SUNSET, STYLE_CUSTOM -> style
            else -> STYLE_DARK_GRAY
        }
        prefs(context).edit().putString(KEY_THEME_STYLE, normalized).apply()
    }

    fun getCustomMode(context: Context): String = prefs(context).getString(KEY_CUSTOM_MODE, CUSTOM_MODE_GRADIENT) ?: CUSTOM_MODE_GRADIENT
    fun getCustomColorA(context: Context): String = prefs(context).getString(KEY_CUSTOM_COLOR_A, "#0048BA") ?: "#0048BA"
    fun getCustomColorB(context: Context): String = prefs(context).getString(KEY_CUSTOM_COLOR_B, "#5BB5FF") ?: "#5BB5FF"
    fun getCustomTransparency(context: Context): Int = prefs(context).getInt(KEY_CUSTOM_TRANSPARENCY, 20).coerceIn(0, 100)

    fun setCustomTheme(context: Context, mode: String, colorA: String, colorB: String, transparency: Int) {
        prefs(context).edit()
            .putString(KEY_CUSTOM_MODE, if (mode == CUSTOM_MODE_SOLID) CUSTOM_MODE_SOLID else CUSTOM_MODE_GRADIENT)
            .putString(KEY_CUSTOM_COLOR_A, colorA)
            .putString(KEY_CUSTOM_COLOR_B, colorB)
            .putInt(KEY_CUSTOM_TRANSPARENCY, transparency.coerceIn(0, 100))
            .apply()
    }

    fun currentPalette(context: Context): ThemePalette = when (getStyle(context)) {
        STYLE_ROYAL_BLUE -> palette(
            a = "#0048BA", b = "#5BB5FF", transparency = 20,
            gradient = intArrayOf(Color.rgb(0, 58, 150), Color.rgb(0, 92, 220), Color.rgb(91, 181, 255)),
            frost = StatusBarFrost(Color.rgb(96, 152, 220), 0.16f, 90, 104, 114, 60, 86)
        )
        STYLE_CRAYON -> ThemePalette(
            topStatusBarGradient = intArrayOf(Color.rgb(39, 91, 180), Color.rgb(245, 196, 81), Color.rgb(237, 106, 90)),
            pageHeaderGradient = intArrayOf(Color.rgb(39, 91, 180), Color.rgb(245, 196, 81), Color.rgb(237, 106, 90)),
            contentPanelGradientA = "#F5C451",
            contentPanelGradientB = "#5BB5FF",
            contentPanelTransparency = 22,
            dialogTitleGradient = intArrayOf(Color.rgb(245, 196, 81), Color.rgb(237, 106, 90)),
            accent = Color.rgb(245, 196, 81),
            topStatusBarFrost = StatusBarFrost(Color.rgb(245, 196, 81), 0.14f, 88, 100, 112, 62, 84)
        )
        STYLE_CLASSIC -> palette(
            a = "#142F6B", b = "#3E63D8", transparency = 22,
            gradient = intArrayOf(Color.rgb(15, 42, 92), Color.rgb(28, 78, 150), Color.rgb(10, 32, 76)),
            frost = StatusBarFrost(Color.rgb(64, 104, 196), 0.16f, 92, 108, 116, 60, 88)
        )
        STYLE_SUNSET -> palette(
            a = "#FF8A3D", b = "#7A3DFF", transparency = 22,
            gradient = intArrayOf(Color.rgb(109, 56, 150), Color.rgb(255, 138, 61), Color.rgb(237, 106, 90)),
            frost = StatusBarFrost(Color.rgb(255, 162, 96), 0.15f, 90, 106, 114, 60, 88)
        )
        STYLE_CUSTOM -> {
            val a = getCustomColorA(context)
            val b = if (getCustomMode(context) == CUSTOM_MODE_SOLID) a else getCustomColorB(context)
            val gradient = intArrayOf(parseColor(a, Color.rgb(0,72,186)), parseColor(b, Color.rgb(91,181,255)))
            val dye = parseColor(a, Color.rgb(0, 72, 186))
            val lum = (0.299 * Color.red(dye) + 0.587 * Color.green(dye) + 0.114 * Color.blue(dye)).toInt()
            val (start, end, shadowBoost) = if (lum > 200) {
                // 极浅色 dye：base 透明度翻倍 + 加厚阴影，保证文字不被「白叠白」淹没（WCAG AA 兜底）
                Triple(180, 204, 124)
            } else {
                val t = (100 - getCustomTransparency(context).coerceIn(0, 100)) / 100f
                val start = (84 + (168 - 84) * t).toInt().coerceIn(48, 210)
                val end = (96 + (216 - 96) * t).toInt().coerceIn(54, 220)
                Triple(start, end, 86)
            }
            ThemePalette(
                topStatusBarGradient = gradient,
                pageHeaderGradient = gradient,
                contentPanelGradientA = a,
                contentPanelGradientB = b,
                contentPanelTransparency = getCustomTransparency(context),
                dialogTitleGradient = gradient,
                accent = Color.rgb(245, 196, 81),
                topStatusBarFrost = StatusBarFrost(dye, 0.14f, start, end, 108, 58, shadowBoost)
            )
        }
        else -> palette(
            a = "#242832", b = "#4D5668", transparency = 20,
            gradient = intArrayOf(Color.rgb(18, 22, 30), Color.rgb(42, 47, 60), Color.rgb(22, 54, 111)),
            frost = StatusBarFrost(Color.rgb(170, 175, 180), 0.12f, 84, 96, 106, 56, 82)
        )
    }

    private fun palette(
        a: String, b: String, transparency: Int, gradient: IntArray, frost: StatusBarFrost
    ): ThemePalette = ThemePalette(
        topStatusBarGradient = gradient,
        pageHeaderGradient = gradient,
        contentPanelGradientA = a,
        contentPanelGradientB = b,
        contentPanelTransparency = transparency,
        dialogTitleGradient = gradient,
        accent = Color.rgb(245, 196, 81),
        topStatusBarFrost = frost
    )

    fun applyTo(activity: Activity, @StyleRes defaultStyle: Int = R.style.Theme_CastTV) {
        val target = if (getStyle(activity) == STYLE_CLASSIC) R.style.Theme_CastTV_Classic else defaultStyle
        activity.setTheme(target)
    }

    @DrawableRes
    fun resolveDrawableAttr(context: Context, @AttrRes attr: Int, @DrawableRes fallback: Int): Int {
        val tv = TypedValue()
        val ok = context.theme.resolveAttribute(attr, tv, true)
        return if (ok && tv.resourceId != 0) tv.resourceId else fallback
    }

    fun accentColor(context: Context): Int = currentPalette(context).accent
    fun dialogTitleGradient(context: Context): IntArray = currentPalette(context).dialogTitleGradient

    /** 弹窗外层面板背景：对齐 [BasePage.contentPanelBg] 的语义（读自定义面板设置 + 当前 palette）。 */
    fun dialogPanelBg(context: Context, cornerRadiusDp: Int = 26): GradientDrawable {
        val palette = currentPalette(context)
        val s = com.bd.casttv.settings.Settings(context)
        val shouldUseCustom = s.pageContentPanelEnabled && s.pageContentPanelCustomized
        val gradA = if (shouldUseCustom) s.pageContentPanelGradientA else palette.contentPanelGradientA
        val gradB = if (shouldUseCustom) s.pageContentPanelGradientB else palette.contentPanelGradientB
        val transparency = if (shouldUseCustom) s.pageContentPanelTransparency else palette.contentPanelTransparency
        val alpha = ((100 - transparency.coerceIn(0, 100)) * 255 / 100)
        val colorA = withAlpha(parseColor(gradA, Color.rgb(0, 72, 186)), alpha)
        val colorB = withAlpha(parseColor(gradB, Color.rgb(91, 181, 255)), alpha)
        val r = (context.resources.displayMetrics.density * cornerRadiusDp).toFloat()
        val strokeW = (context.resources.displayMetrics.density * 1).toInt()
        val strokeColor = withAlpha(Color.WHITE, (72f * 255f / 100f).toInt())
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colorA, colorB)).apply {
            cornerRadius = r
            setStroke(strokeW, strokeColor)
        }
    }

    /** 面板内卡片/输入框/按钮聚焦边框色：跟随 accent，默认态 1px 浅灰，聚焦 3px accent。 */
    fun strokeFor(context: Context, focused: Boolean): Pair<Int, Int> {
        val accent = accentColor(context)
        val defaultStroke = Color.argb(170, 210, 214, 222)
        return ((if (focused) 3 else 1) to if (focused) accent else defaultStroke)
    }

    // ================================================================
    // 全局状态栏 · 透明磨砂玻璃背景（taste-ui-statusbar Skill §2）
    // ================================================================

    /**
     * 构造「状态栏用 · 6 层透明毛玻璃」背景。
     *
     * 与 [CartoonDesign.frostedGlassDrawable] 语义一致，但针对顶部贴边条做了 4 处微调：
     *   1) 半径强制 0（贴边不做圆角）；
     *   2) 顶部高光仅 12dp 厚、底部内阴影仅 18dp 厚（bar 高度 35dp，避免层叠饱和）；
     *   3) 新增第 6 层「底部分割线」：半透明白细线，让 bar 与页面内容自然分界；
     *   4) 散射膜颗粒保持 TL_BR，但 alpha 从 veil 的 8~26% 下调到 4~14%，避免细条纹感过重。
     *
     * @param withDivider 底部 0.5dp 软白分割线；true（默认）= 与 Page 内容视觉分界。
     */
    fun frostedStatusBarBackground(
        context: Context,
        palette: ThemePalette = currentPalette(context),
        withDivider: Boolean = true
    ): LayerDrawable {
        val frost = palette.topStatusBarFrost
        val d = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).roundToInt()

        // 染色膜：按 dyeT ∈ [0.12..0.18] 与 SURFACE_0 混合 → 顶稍亮、底稍暗
        val s0 = Color.rgb(12, 14, 24)
        val dyed = mixColor(s0, frost.dyeColor, frost.dyeT.coerceIn(0f, 1f))
        val topRaw = setLum(dyed, (lum(dyed) + 14).coerceIn(0, 255))
        val botRaw = setLum(dyed, (lum(dyed) - 6).coerceIn(0, 255))
        val colorStart = withAlpha(topRaw, frost.baseAlphaStart.coerceIn(0, 255))
        val colorEnd = withAlpha(botRaw, frost.baseAlphaEnd.coerceIn(0, 255))

        // Layer 0 · 磨砂 base 染色渐变（高透）
        val base = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colorStart, colorEnd)
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
        }

        // Layer 1 · veil 散射膜（磨砂颗粒感，TL_BR 四色渐变，降低 alpha 避免条纹）
        val veil = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(14, 255, 255, 255),
                Color.argb(6, 255, 255, 255),
                Color.argb(10, 255, 255, 255),
                Color.argb(4, 255, 255, 255)
            )
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
        }

        // Layer 2 · 内折射描边（向内 1px 软白边，inset 1px）
        val innerStroke = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(Color.TRANSPARENT)
            setStroke(Math.max(1, dp(1)), Color.argb(frost.strokeAlpha.coerceIn(0, 255), 255, 255, 255))
        }

        // Layer 3 · 顶部高光（12dp 厚度）
        val h = frost.highlightAlpha.coerceIn(0, 255)
        val topGlow = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(h, 255, 255, 255), Color.argb(0, 255, 255, 255))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setSize(-1, dp(12))
        }

        // Layer 4 · 底部内阴影（18dp 厚度）
        val s = frost.shadowAlpha.coerceIn(0, 255)
        val bottomShadow = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.argb(s, 0, 0, 0), Color.argb(0, 0, 0, 0))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setSize(-1, dp(18))
        }

        val layers = mutableListOf(base, veil, innerStroke, topGlow, bottomShadow)

        // Layer 5 (可选) · 底部 0.5dp 软白分割线
        if (withDivider) {
            val divider = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(Math.max(24, frost.strokeAlpha - 72).coerceIn(0, 255), 255, 255, 255)
                )
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 0f
                // 0.5dp 无法用 setStroke，靠 setSize 做一个贴底 1dp 软渐变条
                setSize(-1, Math.max(1, dp(1)))
            }
            layers.add(divider)
        }

        val layerArr = layers.toTypedArray()
        return LayerDrawable(layerArr).apply {
            val h1 = Math.max(1, dp(1))
            val h2 = Math.max(1, dp(2))
            // veil 铺满
            setLayerGravity(1, Gravity.FILL)
            // innerStroke：inset 1px 让描边向内
            setLayerGravity(2, Gravity.FILL)
            setLayerInset(2, h1, h1, h1, h1)
            // topGlow：贴顶
            setLayerGravity(3, Gravity.TOP)
            setLayerInset(3, h2, h2, h2, 0)
            // bottomShadow：贴底
            setLayerGravity(4, Gravity.BOTTOM)
            setLayerInset(4, h2, 0, h2, h2)
            // divider：贴底，inset 只在垂直方向留 1
            if (withDivider) {
                setLayerGravity(5, Gravity.BOTTOM)
                setLayerInset(5, 0, 0, 0, 0)
            }
        }
    }

    /**
     * 状态栏文字 / 图标 token（根据当前 palette 的染色膜亮度自动切亮/暗文本）。
     * 返回：<textPrimary, textSecondary, iconOn, iconOff>
     *  - 当 bar 基色极浅（lum > 210，Custom 浅白染料），用深色文本；
     *  - 否则一律冷白文本（与 TV 10ft UI 的暗色壁纸更匹配）。
     */
    fun statusBarTextTokens(context: Context): StatusBarTextTokens {
        val frost = currentPalette(context).topStatusBarFrost
        val s0 = Color.rgb(12, 14, 24)
        val base = mixColor(s0, frost.dyeColor, frost.dyeT)
        val baseLum = lum(withAlpha(base, (frost.baseAlphaStart + frost.baseAlphaEnd) / 2))
        return if (baseLum > 210) {
            // 浅底 → 深色文本
            StatusBarTextTokens(
                textPrimary = Color.rgb(24, 28, 38),
                textSecondary = Color.rgb(72, 78, 92),
                iconOn = currentPalette(context).accent,
                iconOff = Color.rgb(108, 114, 130)
            )
        } else {
            // 深底 → 冷白文本（与现有 GlobalTopStatusBar 文本色兼容并略有加深）
            StatusBarTextTokens(
                textPrimary = Color.argb(246, 230, 234, 240),
                textSecondary = Color.argb(230, 170, 175, 180),
                iconOn = currentPalette(context).accent,
                iconOff = Color.argb(230, 148, 158, 182)
            )
        }
    }

    data class StatusBarTextTokens(
        @ColorInt val textPrimary: Int,
        @ColorInt val textSecondary: Int,
        @ColorInt val iconOn: Int,
        @ColorInt val iconOff: Int
    )

    // ---- 颜色辅助（mixColor/lum/setLum/withAlpha）----
    @ColorInt
    fun mixColor(@ColorInt a: Int, @ColorInt b: Int, t: Float): Int {
        fun m(x: Int, y: Int): Int = (x + (y - x) * t.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return Color.argb(
            m(Color.alpha(a), Color.alpha(b)),
            m(Color.red(a), Color.red(b)),
            m(Color.green(a), Color.green(b)),
            m(Color.blue(a), Color.blue(b))
        )
    }

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

    private fun withAlpha(rgb: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255) and 0xFF
        return (0x00FFFFFF and rgb) or (a shl 24)
    }

    private fun parseColor(hex: String, fallback: Int): Int = try { Color.parseColor(hex) } catch (_: Throwable) { fallback }
    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
