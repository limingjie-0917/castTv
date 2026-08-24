package com.bd.casttv.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import com.bd.casttv.R

/** 新框架主题切换管理器：提供默认主题 palette 与自定义主题配置。 */
object ThemeManager {

    data class ThemePalette(
        val topStatusBarGradient: IntArray,
        val pageHeaderGradient: IntArray,
        val contentPanelGradientA: String,
        val contentPanelGradientB: String,
        val contentPanelTransparency: Int,
        val dialogTitleGradient: IntArray,
        val accent: Int
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
        STYLE_ROYAL_BLUE -> palette("#0048BA", "#5BB5FF", 20, intArrayOf(Color.rgb(0, 58, 150), Color.rgb(0, 92, 220), Color.rgb(91, 181, 255)))
        STYLE_CRAYON -> ThemePalette(
            topStatusBarGradient = intArrayOf(Color.rgb(39, 91, 180), Color.rgb(245, 196, 81), Color.rgb(237, 106, 90)),
            pageHeaderGradient = intArrayOf(Color.rgb(39, 91, 180), Color.rgb(245, 196, 81), Color.rgb(237, 106, 90)),
            contentPanelGradientA = "#F5C451",
            contentPanelGradientB = "#5BB5FF",
            contentPanelTransparency = 22,
            dialogTitleGradient = intArrayOf(Color.rgb(245, 196, 81), Color.rgb(237, 106, 90)),
            accent = Color.rgb(245, 196, 81)
        )
        STYLE_CLASSIC -> palette("#142F6B", "#3E63D8", 22, intArrayOf(Color.rgb(15, 42, 92), Color.rgb(28, 78, 150), Color.rgb(10, 32, 76)))
        STYLE_SUNSET -> palette("#FF8A3D", "#7A3DFF", 22, intArrayOf(Color.rgb(109, 56, 150), Color.rgb(255, 138, 61), Color.rgb(237, 106, 90)))
        STYLE_CUSTOM -> {
            val a = getCustomColorA(context)
            val b = if (getCustomMode(context) == CUSTOM_MODE_SOLID) a else getCustomColorB(context)
            val gradient = intArrayOf(parseColor(a, Color.rgb(0,72,186)), parseColor(b, Color.rgb(91,181,255)))
            ThemePalette(gradient, gradient, a, b, getCustomTransparency(context), gradient, Color.rgb(245, 196, 81))
        }
        else -> palette("#242832", "#4D5668", 20, intArrayOf(Color.rgb(18, 22, 30), Color.rgb(42, 47, 60), Color.rgb(22, 54, 111)))
    }

    private fun palette(a: String, b: String, transparency: Int, gradient: IntArray): ThemePalette = ThemePalette(
        topStatusBarGradient = gradient,
        pageHeaderGradient = gradient,
        contentPanelGradientA = a,
        contentPanelGradientB = b,
        contentPanelTransparency = transparency,
        dialogTitleGradient = gradient,
        accent = Color.rgb(245, 196, 81)
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

    private fun parseColor(hex: String, fallback: Int): Int = try { Color.parseColor(hex) } catch (_: Throwable) { fallback }
    private fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
