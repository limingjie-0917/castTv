package com.bd.casttv.ui.framework

import android.view.View
import android.view.ViewGroup
import com.bd.casttv.R

/**
 * 全局按钮焦点态视觉增强 Helper。
 *
 *  - **物理层次感**：焦点态 `scaleX/scaleY = 1.05f` + `translationZ` 抬升
 *  - 去掉光晕：不再叠加暖黄朦胧光晕 overlay 和 P+ 阴影色，保持简洁
 *
 * 使用约束：
 *  - 不使用 [View.setForeground] 叠加暖黄透明层，页面自定义的边框 / 选中态背景保持原样。
 *  - 提供两种调用方式：
 *      1. [applyFocusFx]：自管理焦点监听器（内部链式合并 [View.getOnFocusChangeListener]）
 *      2. [applyFocusFxState]：无侧作用的状态刷新函数，供页面自定义 focus listener 直接调用
 *  - 缩放需要父容器 `clipChildren=false / clipToPadding=false`，helper 会向上关闭最多 3 层。
 */
object FocusFxHelper {

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

    /** 默认焦点抬升（dp），用于制造浮起层次感。 */
    const val DEFAULT_ELEVATION_DP: Int = 8

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
     * - 焦点态：`scale = [scale]`、`translationZ = [elevationDp]dp`。
     * - 失焦态：`scale = 1f`、`translationZ = 0f`。
     * - [highlightAlpha] / [cornerRadiusDp] 仅为兼容旧调用保留。
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

        // 2. 焦点态抬升（translationZ），提供层次感。
        view.translationZ = if (hasFocus) elevationDp * density else 0f

        // 3. 缩放动画（cancel 再 start，避免焦点快速切换时抖动）。
        view.animate().cancel()
        val target = if (hasFocus) scale else 1f
        view.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(ANIM_DURATION_MS)
            .start()
    }

    /**
     * 主动关闭 view 的父容器 clip，保证 scale 1.05 / translationZ 不被裁剪。
     * 独立暴露，方便页面在自定义 RecyclerView.ViewHolder / 容器初始化时手动调用。
     */
    @JvmOverloads
    fun disableClippingUp(view: View, maxDepth: Int = 3) {
        var current: ViewGroup? = view.parent as? ViewGroup
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (current.clipChildren) current.clipChildren = false
            if (current.clipToPadding) current.clipToPadding = false
            current = current.parent as? ViewGroup
            depth++
        }
    }
}
