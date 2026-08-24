package com.bd.casttv.ui.framework

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.bd.casttv.settings.Settings
import com.bd.casttv.util.ThemeManager

abstract class BasePage @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs), SettingsChangeBus.Listener {
    abstract val pageId: String
    abstract val pageTitle: String
    abstract val pageIconRes: Int

    /**
     * 是否启用整页 ScrollView 兜底：当屏幕比例/分辨率导致一屏放不下时可滚动。
     * 复杂页面（如收藏页/自定义 Tab）可关闭，自己管理滚动。
     */
    open val enablePageScroll: Boolean = true

    /**
     * 是否将页头下方的业务内容放入统一内容面板。
     * 默认关闭，避免影响首页、收藏页、自定义 Tab 等复杂布局；简单信息页可按需开启。
     */
    open val useContentPanel: Boolean get() = false
    open val contentPanelPaddingDp: Int get() = 18
    open val contentPanelTopPaddingDp: Int get() = 8

    /**
     * 是否展示统一的顶部标题栏（贴纸 + [pageTitle] + 焦点感知渐变分隔线）。
     * 主页 HomePage / 自定义 Tab 页 CustomTabPage 保持关闭，其他 6 个非主页开启。
     * 用 `get()` 写法可安全绕过 Kotlin 父类 init 读取子类字段时机的问题。
     */
    open val showPageHeader: Boolean get() = false

    /**
     * 页头贴纸资源。取值为 0 时不显示贴纸。默认 0，由具体页面通过 `override val ... get() = R.drawable.xxx` 指定。
     */
    open val pageStickerRes: Int get() = 0

    val pageRootFocus = View(context).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        alpha = 0f
    }

    val contentContainer = FrameLayout(context)
    var pageContainer: PageContainer? = null
    private var contentHost: FrameLayout? = null

    /** 页头视图：由 BasePage 统一插入，pages 无需再自行绘制标题；可通过 [showPageHeader] 关闭。 */
    val pageHeader: PageHeaderView = PageHeaderView(context).apply { visibility = View.GONE }

    private var isRootFocusState = false

    init {
        // 纵向包一层：[pageHeader (顶部, 反白状态栏预留)] + [ScrollView(contentContainer) 或 contentContainer 直接铺满]
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // 页头在最顶，为顶部 40dp GlobalTopStatusBar 预留空间，并额外留 8dp 小间距，避免标题栏贴住全局状态栏。
        wrapper.addView(
            pageHeader,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(48)
                bottomMargin = dp(0)
                leftMargin = dp(56)
                rightMargin = dp(56)
            }
        )

        contentHost = if (useContentPanel) {
            FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
                val pad = dp(contentPanelPaddingDp)
                val topPad = dp(contentPanelTopPaddingDp)
                setPadding(pad, topPad, pad, pad)
                background = contentPanelBg()
            }
        } else {
            contentContainer
        }
        val contentHost = contentHost ?: contentContainer

        if (useContentPanel) {
            contentHost.addView(
                contentContainer,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
        }

        if (enablePageScroll) {
            val scroll = ScrollView(context).apply {
                isFillViewport = true
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            scroll.addView(
                contentHost,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            wrapper.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                leftMargin = if (useContentPanel) dp(56) else 0
                rightMargin = if (useContentPanel) dp(56) else 0
                topMargin = if (showPageHeader) dp(1) else 0
                bottomMargin = if (useContentPanel) dp(18) else 0
            })
        } else {
            wrapper.addView(contentHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                leftMargin = if (useContentPanel) dp(56) else 0
                rightMargin = if (useContentPanel) dp(56) else 0
                topMargin = if (showPageHeader) dp(1) else 0
                bottomMargin = if (useContentPanel) dp(18) else 0
            })
        }

        addView(wrapper, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        addView(pageRootFocus, FrameLayout.LayoutParams(1, 1))
        pageRootFocus.setOnFocusChangeListener { _, has -> if (has) onFocusEnterRoot() else onFocusLeaveRoot() }
        pageRootFocus.setOnKeyListener { _, _, event -> BoundaryFocusHandler.onRootKey(this, event) }

        viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            updateRootFocusState(
                when {
                    newFocus === pageRootFocus -> true
                    newFocus != null && isDescendantOfContent(newFocus) -> false
                    else -> null
                }
            )
        }

        // 页头初始化：延后到子类 init 完成后执行，避免读到未初始化的 override 字段。
        post {
            if (showPageHeader) {
                pageHeader.visibility = View.VISIBLE
                pageHeader.bind(pageStickerRes, pageTitle)
                pageHeader.setPageFocused(hasFocusAwayFromRoot())
            } else {
                pageHeader.visibility = View.GONE
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            val focused = findFocus()
            if (focused != null && focused !== pageRootFocus && isDescendantOfContent(focused)) {
                focusToRoot()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SettingsChangeBus.addListener(this)
    }

    override fun onDetachedFromWindow() {
        SettingsChangeBus.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSettingsChanged() {
        refreshTheme()
    }

    open fun onEnter() {}
    open fun onLeave() {}
    open fun interceptPageSwitch(targetIndex: Int, direction: Int, proceed: () -> Unit): Boolean = false
    open fun refreshTheme() {
        if (useContentPanel) {
            contentHost?.background = contentPanelBg()
        }
        pageHeader.refreshTheme()
    }

    open fun onFocusEnterRoot() {
        pageContainer?.indicator?.setRootFocusActive(true)
        updateRootFocusState(true)
        // 焦点回到页根（未进入内容），页头分隔线回退浅灰细线。
        pageHeader.setPageFocused(false)
    }

    open fun onFocusLeaveRoot() {
        pageContainer?.indicator?.setRootFocusActive(false)
    }

    open fun onFocusEnterContent() {
        updateRootFocusState(false)
        // 焦点进入内容区，页头分隔线切换到暖色渐变发光态（300ms 过渡）。
        pageHeader.setPageFocused(true)
    }

    open fun onFocusLeaveContent() {}

    fun focusToRoot() {
        pageRootFocus.requestFocus()
        onFocusEnterRoot()
    }

    fun isRootFocused(): Boolean = findFocus() === pageRootFocus

    fun hasFocusAwayFromRoot(): Boolean {
        val focused = findFocus() ?: return false
        return focused !== pageRootFocus && isDescendantOfPage(focused)
    }

    open fun focusToFirstContent(): Boolean {
        val view = findFocusable(contentContainer)
        val ok = view?.requestFocus() == true
        if (ok) onFocusEnterContent()
        return ok
    }

    private fun updateRootFocusState(active: Boolean?) {
        if (active == null || isRootFocusState == active) return
        isRootFocusState = active
        pageContainer?.notifyRootFocusState(this, active)
        // 同步页头分隔线状态：active=true(页根获焦) -> 浅灰细线；false(内容获焦) -> 渐变发光。
        pageHeader.setPageFocused(!active)
    }

    private fun isDescendantOfContent(view: View): Boolean {
        var current: View? = view
        while (current != null && current !== this) {
            if (current === contentContainer) return true
            current = current.parent as? View
        }
        return false
    }

    private fun isDescendantOfPage(view: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === this) return true
            current = current.parent as? View
        }
        return false
    }

    private fun findFocusable(view: View): View? {
        if (view.isFocusable && view.visibility == VISIBLE) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findFocusable(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    protected fun contentPanelBg(): GradientDrawable {
        val settings = Settings(context)
        val palette = ThemeManager.currentPalette(context)
        // 「启用页面内容区域背景」关闭时，必须完全回退到当前主题 palette。
        // 只有开关开启且用户确实保存过自定义颜色时，才读取自定义背景配置。
        val shouldUseCustomPanel = settings.pageContentPanelEnabled && settings.pageContentPanelCustomized
        val gradientA = if (shouldUseCustomPanel) settings.pageContentPanelGradientA else palette.contentPanelGradientA
        val gradientB = if (shouldUseCustomPanel) settings.pageContentPanelGradientB else palette.contentPanelGradientB
        val transparency = if (shouldUseCustomPanel) settings.pageContentPanelTransparency else palette.contentPanelTransparency
        val alpha = ((100 - transparency).coerceIn(0, 100) * 255 / 100)
        val colorA = parsePanelColor(gradientA, alpha, Color.rgb(0, 72, 186))
        val colorB = parsePanelColor(gradientB, alpha, Color.rgb(91, 181, 255))
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(colorA, colorB)
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.argb(72, 210, 235, 255))
        }
    }

    private fun parsePanelColor(hex: String, alpha: Int, fallbackRgb: Int): Int = try {
        val rgb = Color.parseColor(hex)
        Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    } catch (_: Throwable) {
        Color.argb(alpha, Color.red(fallbackRgb), Color.green(fallbackRgb), Color.blue(fallbackRgb))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
