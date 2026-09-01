package com.bd.casttv.ui.framework

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import com.bd.casttv.R

/**
 * 全局按钮焦点态视觉增强 Helper。
 *
 *  - **物理层次感**：焦点态 `scaleX/scaleY = 1.05f` + `translationZ` 抬升
 *  - **朦胧光晕**：焦点态叠加多层半透明暖黄圆角（柔焦光晕），通过 ViewOverlay 叠加，
 *    不改变页面原有 background / foreground / 边框样式
 *  - **P+ 阴影色**：outlineAmbient/SpotShadow 同样使用暖黄色，高版本设备额外发光
 *
 * 使用约束：
 *  - 不使用 [View.setForeground] 叠加暖黄透明层，页面自定义的边框 / 选中态背景保持原样。
 *  - 提供两种调用方式：
 *      1. [applyFocusFx]：自管理焦点监听器（内部链式合并 [View.getOnFocusChangeListener]）
 *      2. [applyFocusFxState]：无侧作用的状态刷新函数，供页面自定义 focus listener 直接调用
 *  - 缩放/发光需要父容器 `clipChildren=false / clipToPadding=false`，helper 会向上关闭最多 3 层。
 */
object FocusFxHelper {

    /** 焦点态外发光颜色（暖黄，用于 outlineAmbient/SpotShadow，Android P+ 生效）。 */
    private const val GLOW_ARGB = 0xB3F5C451.toInt()

    /** 焦点态朦胧光晕：暖黄主色（F5C451）的三种透明度。 */
    private const val GLOW_ALPHA_INNER = 0x66 // 内层：~40%（贴近边框的柔边）
    private const val GLOW_ALPHA_MID   = 0x33 // 中层：~20%
    private const val GLOW_ALPHA_OUTER = 0x14 // 外层：~8%（远处的漫射光）

    private const val WARM_R = 0xF5
    private const val WARM_G = 0xC4
    private const val WARM_B = 0x51

    /** 焦点态默认动效时长（毫秒）。 */
    const val ANIM_DURATION_MS: Long = 120L

    /** 默认焦点缩放系数。 */
    const val DEFAULT_SCALE: Float = 1.05f

    /**
     * 兼容旧调用签名的保留参数；当前全局焦点态不再叠加暖黄前景。
     */
    const val DEFAULT_HIGHLIGHT_ALPHA: Int = 0

    /** 默认圆角（dp），与项目通用按钮圆角保持一致。 */
    const val DEFAULT_CORNER_RADIUS_DP: Int = 12

    /** 默认焦点抬升（dp），用于制造发光/浮起层次感。 */
    const val DEFAULT_ELEVATION_DP: Int = 8

    /** 朦胧光晕扩散半径（dp）：光晕整体向外扩张的尺寸，越大越"朦胧"。 */
    private const val GLOW_SPREAD_DP: Int = 14

    /**
     * 自管理焦点态 fx 安装：内部保留旧 [View.getOnFocusChangeListener] 并链式合并；
     * 通过 [R.id.tag_focus_fx_applied] 标记幂等，避免同一 View 被重复挂载。
     */
    @JvmOverloads
    fun applyFocusFx(
        view: View,
        scale: Float = DEFAULT_SCALE,
        highlightAlpha: Int = DEFAULT_HIGHLIGHT_ALPHA,
        cornerRadiusDp: Int = DEFAULT_CORNER_RADIUS_DP,
        elevationDp: Int = DEFAULT_ELEVATION_DP,
        ensureClipDisabled: Boolean = true,
    ) {
        if (view.getTag(R.id.tag_focus_fx_applied) == java.lang.Boolean.TRUE) return
        view.setTag(R.id.tag_focus_fx_applied, java.lang.Boolean.TRUE)

        if (ensureClipDisabled) disableClippingUp(view, maxDepth = 3)

        val prev = view.onFocusChangeListener
        view.setOnFocusChangeListener { v, hasFocus ->
            prev?.onFocusChange(v, hasFocus)
            applyFocusFxState(v, hasFocus, scale, highlightAlpha, cornerRadiusDp, elevationDp)
        }
        // 初始同步一次（例如刚安装时已带焦点）。
        applyFocusFxState(view, view.isFocused, scale, highlightAlpha, cornerRadiusDp, elevationDp)
    }

    /**
     * 无侧作用的焦点态刷新函数：供页面自定义 focus listener（或 refreshXxxButton）直接调用。
     *
     * - 焦点态：`scale = [scale]`、`translationZ = [elevationDp]dp`、叠加暖黄朦胧光晕。
     * - 失焦态：`scale = 1f`、`translationZ = 0f`、移除光晕。
     * - [highlightAlpha] / [cornerRadiusDp] 仅为兼容旧调用保留，当前不会生成暖黄前景遮罩。
     */
    @Suppress("UNUSED_PARAMETER")
    @JvmOverloads
    fun applyFocusFxState(
        view: View,
        hasFocus: Boolean,
        scale: Float = DEFAULT_SCALE,
        highlightAlpha: Int = DEFAULT_HIGHLIGHT_ALPHA,
        cornerRadiusDp: Int = DEFAULT_CORNER_RADIUS_DP,
        elevationDp: Int = DEFAULT_ELEVATION_DP,
    ) {
        val density = view.resources.displayMetrics.density

        // 1. 清空前景遮罩（保持焦点态只显示页面自身背景/边框）。
        view.foreground = null

        // 2. 朦胧光晕：ViewOverlay + 三层半透明暖黄圆角叠加
        //    用 overlay 保证不改变页面原有 background / foreground / 边框。
        val glowTag = R.id.tag_focus_glow_drawable
        if (hasFocus) {
            if (view.getTag(glowTag) == null) {
                val glow = buildHazyGlowDrawable(cornerRadiusDp, density)
                view.overlay.add(glow)
                view.setTag(glowTag, glow)
            }
            // 同步光晕尺寸（View 尺寸可能在 onMeasure 后变化）
            (view.getTag(glowTag) as? HazyGlowDrawable)?.layoutTo(view)
        } else {
            (view.getTag(glowTag) as? HazyGlowDrawable)?.let {
                view.overlay.remove(it)
                view.setTag(glowTag, null)
            }
        }

        // 3. 外发光：translationZ 抬升 + 暖黄阴影颜色（P+ 生效，柔边补充）。
        view.translationZ = if (hasFocus) elevationDp * density else 0f
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                view.outlineAmbientShadowColor = GLOW_ARGB
                view.outlineSpotShadowColor = GLOW_ARGB
            } catch (_: Throwable) {
                // 部分厂商 ROM 可能不支持定制 shadow 颜色，忽略即可。
            }
        }

        // 4. 缩放动画（cancel 再 start，避免焦点快速切换时抖动）。
        view.animate().cancel()
        val target = if (hasFocus) scale else 1f
        view.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(ANIM_DURATION_MS)
            .start()
    }

    // ------------------------------------------------------------------
    //  暖黄朦胧光晕 Drawable：三层半透明暖黄圆角（内→外透明度递减）
    //  绘制时向外扩张 GLOW_SPREAD_DP，制造"光从边框渗出"的柔焦效果
    // ------------------------------------------------------------------

    private fun buildHazyGlowDrawable(cornerRadiusDp: Int, density: Float): HazyGlowDrawable {
        val radius = cornerRadiusDp * density
        val spreadPx = (GLOW_SPREAD_DP * density).toInt()

        // 三层：内层（贴近边框+柔边）、中层、外层（最远漫射）
        // 每层独立 GradientDrawable，圆角略大于 View 圆角避免硬边
        val inner = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius + spreadPx * 0.15f
            setColor(Color.argb(GLOW_ALPHA_INNER, WARM_R, WARM_G, WARM_B))
        }
        val mid = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius + spreadPx * 0.35f
            setColor(Color.argb(GLOW_ALPHA_MID, WARM_R, WARM_G, WARM_B))
        }
        val outer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius + spreadPx * 0.7f
            setColor(Color.argb(GLOW_ALPHA_OUTER, WARM_R, WARM_G, WARM_B))
        }
        val layers = LayerDrawable(arrayOf(outer, mid, inner))
        return HazyGlowDrawable(layers, spreadPx)
    }

    /**
     * 光晕的外层包装：负责把 LayerDrawable 绘制在 View 外扩区域。
     * 通过覆写 setBounds 向四周扩张 spreadPx，让光晕"溢出"到 View 外。
     */
    private class HazyGlowDrawable(
        private val layers: LayerDrawable,
        private val spreadPx: Int,
    ) : android.graphics.drawable.Drawable() {

        /** 对齐到目标 View 的外扩边界，在 applyFocusFxState 中和 View size 变化后调用。 */
        fun layoutTo(view: View) {
            val w = view.width
            val h = view.height
            if (w > 0 && h > 0) {
                setBounds(-spreadPx, -spreadPx, w + spreadPx, h + spreadPx)
            }
        }

        override fun draw(canvas: android.graphics.Canvas) {
            layers.bounds = bounds
            layers.draw(canvas)
        }

        override fun setAlpha(alpha: Int) { layers.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { layers.colorFilter = cf }
        override fun getOpacity(): Int = layers.opacity
    }

    /**
     * 主动关闭 view 的父容器 clip，保证 scale 1.05 / translationZ / 朦胧光晕不被裁剪。
     * 独立暴露，方便页面在自定义 RecyclerView.ViewHolder / 容器初始化时手动调用。
     *
     * 注意：因为光晕扩张到 View 外，需要额外关闭 1 层父容器的 clip（光晕向外 ~14dp）。
     */
    @JvmOverloads
    fun disableClippingUp(view: View, maxDepth: Int = 3) {
        var current: ViewGroup? = view.parent as? ViewGroup
        var depth = 0
        // 光晕扩散 14dp，再多关 1 层保证外层父容器也不裁剪
        val effectiveDepth = maxDepth + 1
        while (current != null && depth < effectiveDepth) {
            if (current.clipChildren) current.clipChildren = false
            if (current.clipToPadding) current.clipToPadding = false
            current = current.parent as? ViewGroup
            depth++
        }
    }
}
