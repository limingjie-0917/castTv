package com.bd.casttv.ui.framework

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.FrameLayout

class PageContainer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    interface RootFocusStateListener { fun onRootFocusStateChanged(active: Boolean) }
    interface PageChangeListener { fun onPageChanged(page: BasePage, index: Int) }
    data class PageSpec(val pageId: String, val pageTitle: String, val pageIconRes: Int, val factory: () -> BasePage)

    private data class PageEntry(val spec: PageSpec, var instance: BasePage? = null)

    private var pages = emptyList<PageEntry>()
    val pageCount: Int get() = pages.size
    val currentPage: BasePage? get() = pages.getOrNull(currentIndex)?.instance
    var currentIndex = 0; private set
    var indicator: BottomIndicatorBar? = null
    var rootFocusStateListener: RootFocusStateListener? = null
    var pageChangeListener: PageChangeListener? = null
    /**
     * 首页按 BACK 时的退出回调。若未设置则沿用旧行为（shake 抖动）。
     */
    var onExitRequested: (() -> Unit)? = null
    private var animating = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingFallback: Runnable? = null
    private var swallowNextBackUp = false
    private var bypassPageSwitchIntercept = false

    fun instantiatedPages(): List<BasePage> = pages.mapNotNull { it.instance }
    fun bindPages(list: List<BasePage>) {
        bindLazyPages(list.map { page -> PageSpec(page.pageId, page.pageTitle, page.pageIconRes) { page } })
    }

    fun bindLazyPages(list: List<PageSpec>) {
        val previousPageId = pages.getOrNull(currentIndex)?.spec?.pageId.orEmpty()
        pages.getOrNull(currentIndex)?.instance?.onLeave()
        cancelPendingFallback()
        animating = false
        removeAllViews()
        val oldInstances = pages.mapNotNull { entry -> entry.instance?.let { entry.spec.pageId to it } }.toMap()
        pages = list.map { spec -> PageEntry(spec, oldInstances[spec.pageId]) }
        pages.forEach { entry ->
            entry.instance?.let { attachPage(it) }
        }
        currentIndex = list.indexOfFirst { it.pageId == previousPageId }.takeIf { it >= 0 } ?: 0
        indicator?.bindPages(list.map { BottomIndicatorBar.PageItem(it.pageId, it.pageTitle, it.pageIconRes) })
        indicator?.setCurrentIndex(currentIndex, false)
        pages.getOrNull(currentIndex)?.let { entry ->
            val page = ensurePage(entry)
            page.visibility = VISIBLE
            page.onEnter()
            page.focusToRoot()
            pageChangeListener?.onPageChanged(page, currentIndex)
            notifyRootFocusState(page, true)
        }
    }

    private fun ensurePage(entry: PageEntry): BasePage {
        entry.instance?.let { return it }
        return entry.spec.factory().also { page ->
            entry.instance = page
            attachPage(page)
        }
    }

    private fun attachPage(page: BasePage) {
        if (page.parent != null && page.parent !== this) (page.parent as? android.view.ViewGroup)?.removeView(page)
        page.pageContainer = this
        page.animate().cancel()
        page.translationX = 0f
        page.alpha = 1f
        page.visibility = GONE
        if (page.parent !== this) addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    fun switchBy(direction: Int) = switchTo(currentIndex + direction, direction)

    /** 根据 pageId 直接切页（用于外部 Intent / 兜底跳转）。 */
    fun switchToPageId(pageId: String): Boolean {
        if (pageId.isBlank()) return false
        val idx = pages.indexOfFirst { it.spec.pageId == pageId }
        if (idx < 0) return false
        if (idx == currentIndex) return true
        val dir = if (idx > currentIndex) 1 else -1
        return switchTo(idx, dir)
    }
    fun switchTo(index: Int, direction: Int): Boolean {
        if (animating) return true
        if (index !in pages.indices) { currentPage?.let { BoundaryFocusHandler.shake(it) }; return true }
        if (index == currentIndex) return true
        val current = ensurePage(pages[currentIndex])
        if (!bypassPageSwitchIntercept && current.interceptPageSwitch(index, direction) {
                bypassPageSwitchIntercept = true
                try {
                    switchTo(index, direction)
                } finally {
                    bypassPageSwitchIntercept = false
                }
            }) {
            return true
        }
        animating = true
        val old = current
        val next = ensurePage(pages[index])
        // 清理任何残留动画/位移，避免快速左右切换导致 withEndAction 不触发或状态错乱。
        cancelPendingFallback()
        old.animate().cancel()
        next.animate().cancel()
        old.translationX = 0f
        old.alpha = 1f
        val hideOldImmediately = old.pageId.startsWith("customtab_") && next.pageId.startsWith("customtab_")
        // 自定义 Tab 内部包含预览播放器/复用视图。两个自定义 Tab 之间切换时如果旧页继续参与淡出/滑动，
        // Surface/播放器最后一帧容易在新页绘制前残留，形成“上一页残影”。这里先把旧页从可见层移除，
        // 新页仍保持原切入动画，避免旧自定义页与新自定义页在同一帧同时可见。
        if (hideOldImmediately) {
            old.visibility = GONE
        } else {
            // 启用硬件加速层以提升动画流畅度
            old.setLayerType(LAYER_TYPE_HARDWARE, null)
        }
        next.setLayerType(LAYER_TYPE_HARDWARE, null)
        next.visibility = VISIBLE
        next.translationX = width * direction.toFloat()
        next.alpha = 0.35f
        var oldEnded = hideOldImmediately
        var nextEnded = false
        var finalized = false
        val finalize = fin@ {
            if (finalized) return@fin
            finalized = true
            cancelPendingFallback()
            // 强制归位，保证下一次切换或再次进入时页面元素能正常渲染。
            old.animate().cancel()
            next.animate().cancel()
            old.translationX = 0f
            old.alpha = 1f
            old.visibility = GONE
            // 动画结束后恢复软件渲染，释放硬件层显存
            old.setLayerType(LAYER_TYPE_NONE, null)
            next.setLayerType(LAYER_TYPE_NONE, null)
            next.translationX = 0f
            next.alpha = 1f
            next.visibility = VISIBLE
            old.onLeave()
            currentIndex = index
            next.onEnter()
            next.focusToRoot()
            indicator?.setCurrentIndex(index, true)
            pageChangeListener?.onPageChanged(next, index)
            notifyRootFocusState(next, true)
            animating = false
        }
        val tryFinalize = {
            if (oldEnded && nextEnded) finalize()
        }
        old.animate()
            .translationX(-width * direction * 0.25f)
            .alpha(0.35f)
            .setDuration(220)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { oldEnded = true; tryFinalize() }
                override fun onAnimationCancel(animation: Animator) { oldEnded = true; tryFinalize() }
            })
            .start()
        next.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(220)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { nextEnded = true; tryFinalize() }
                override fun onAnimationCancel(animation: Animator) { nextEnded = true; tryFinalize() }
            })
            .start()
        // 兜底：动画时长 220ms，最多 320ms 后一定要把状态复位；否则 animating 会永远为 true，
        // 表现为再次切换无响应、页面元素/指示栏“不显示”。
        val fallback = Runnable { finalize() }
        pendingFallback = fallback
        mainHandler.postDelayed(fallback, 320)
        return true
    }
    private fun cancelPendingFallback() {
        pendingFallback?.let { mainHandler.removeCallbacks(it) }
        pendingFallback = null
    }
    fun notifyRootFocusState(page: BasePage, active: Boolean) {
        if (pages.getOrNull(currentIndex)?.instance !== page) return
        rootFocusStateListener?.onRootFocusStateChanged(active)
    }

    fun handleBackKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (animating) return true
        val current = currentPage ?: return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                swallowNextBackUp = true
                if (current.hasFocusAwayFromRoot()) {
                    current.focusToRoot()
                    true
                } else {
                    backToHomeOrExit()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (swallowNextBackUp) {
                    swallowNextBackUp = false
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    fun backToHomeOrExit(): Boolean {
        return if (currentIndex != 0) {
            switchTo(0, -1)
        } else {
            val exit = onExitRequested
            if (exit != null) exit.invoke() else BoundaryFocusHandler.shake(this)
            true
        }
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (animating) true else super.dispatchKeyEvent(event)
}
