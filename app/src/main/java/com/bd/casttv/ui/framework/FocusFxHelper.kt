package com.bd.casttv.ui.framework

import android.view.View
import android.view.ViewGroup
import com.bd.casttv.R

/**
 * 全局按钮焦点态视觉增强 Helper（方案 A）。
 *
 *  - **A 物理层次感**：焦点态 `scaleX/scaleY = 1.05f` + `translationZ` 抬升（外发光效果）。
 *
 * 目标：在不叠加暖黄前景遮罩、不改变页面原 background drawable 的前提下，
 * 通过轻微放大和浮起发光强化焦点可见性。
 *
 * 使用约束：
 *  - 不再使用 [View.setForeground] 叠加暖黄透明层，页面自定义的边框 / 选中态背景保持原样。
 *  - 提供两种调用方式：
 *      1. [applyFocusFx]：自管理焦点监听器（内部链式合并 [View.getOnFocusChangeListener]，
 *         对同一 View 幂等），适合"焦点监听器不会被后续代码覆盖"的按钮工厂。
 *      2. [applyFocusFxState]：无侧作用的状态刷新函数，供页面自定义 focus listener 直接调用，
 *         适合"页面有自己的 refreshXxxButton(focused=?) 主逻辑"的按钮。
 *  - 缩放/发光需要父容器 `clipChildren=false / clipToPadding=false`，helper 会向上关闭最多 3 层。
 */
object FocusFxHelper {

    /** 焦点态外发光颜色（暖黄，用于 outlineAmbient/SpotShadow，Android P+ 生效）。 */
    private const val GLOW_ARGB = 0xB3F5C451.toInt()

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

    /**
     * 自管理焦点态 fx 安装：内部保留旧 [View.getOnFocusChangeListener] 并链式合并；
     * 通过 [R.id.tag_focus_fx_applied] 标记幂等，避免同一 View 被重复挂载。
     *
     * 注意：若调用后有代码再次调用 `view.setOnFocusChangeListener(...)`，会覆盖 helper 的链式监听器。
     * 这种场景请改用 [applyFocusFxState]，在自定义 focus listener 里显式调用它。
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

        // 1. 不再叠加暖黄前景，确保焦点态只保留页面自身背景/边框 + 浮起/缩放。
        view.foreground = null

        // 2. 外发光：translationZ 抬升 + 暖黄阴影颜色（P+ 生效）。
        view.translationZ = if (hasFocus) elevationDp * density else 0f
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            try {
                view.outlineAmbientShadowColor = GLOW_ARGB
                view.outlineSpotShadowColor = GLOW_ARGB
            } catch (_: Throwable) {
                // 部分厂商 ROM 可能不支持定制 shadow 颜色，忽略即可。
            }
        }

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
     * 主动关闭 view 的父容器 clip，保证 scale 1.05 / translationZ 发光不被裁剪。
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
