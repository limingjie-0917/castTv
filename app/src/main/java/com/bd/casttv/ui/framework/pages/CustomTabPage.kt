package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.settings.CustomDockTabsStore.CustomDockTab
import com.bd.casttv.settings.Settings
import com.bd.casttv.settings.SourceHealthStore
import com.bd.casttv.settings.TabChannelConfigStore
import com.bd.casttv.ui.CustomDockIconPresets
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.preview.PreviewPlayerHolder

/**
 * 新框架下的自定义 Tab 页面：
 *  - 复用旧版 `CustomDockTabPage` 承载真实业务（频道列表、预览、源管理）；
 *  - 去掉最外层 crayon 面板边框与外边距，让页面直接铺满页容器；
 *  - 顶部为 `GlobalTopStatusBar` 预留 35dp 高度（由 BasePage wrapperTopPaddingDp 统一承担）；
 *  - 页面标题已收敛进 GlobalTopStatusBar 左侧，本页不再绘制独立 PageHeader（API 保留，实际渲染强制 GONE）；
 *  - 频道列表左边界 LEFT 键统一回落到 pageRootFocus，触发 Dock 页切换（LEFT/RIGHT）。
 */
class CustomTabPage(context: Context, private val tab: CustomDockTab) : BasePage(context) {
    override val pageId: String = "customtab_${tab.id}"
    override val pageTitle: String = tab.name
    override val pageIconRes: Int = CustomDockIconPresets.iconFor(tab.iconKey).drawableRes
    override val enablePageScroll: Boolean = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true // 保留 API；BasePage init 强制 GONE
    override val pageStickerRes: Int get() = CustomDockIconPresets.iconFor(tab.iconKey).drawableRes

    private val legacyPage = com.bd.casttv.ui.CustomDockTabPage(
        inflater = LayoutInflater.from(context),
        favoritesStore = FavoritesStore(context),
        configStore = TabChannelConfigStore(context),
        healthStore = SourceHealthStore(context),
        useSurfaceView = Settings(context).useSurfaceView,
        playBoundaryShake = { v -> v.animate().translationX(10f).setDuration(40).withEndAction { v.animate().translationX(0f).setDuration(80).start() }.start() },
        onOpenFullscreen = { item ->
            PreviewPlayerHolder.pauseForFullscreen()
            val uri = item.uri.trim()
            if (uri.isBlank()) {
                android.widget.Toast.makeText(context, "播放地址为空，无法播放", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val title = item.title.ifBlank { uri }
                val source = item.source.ifBlank { "custom_tab" }
                try {
                    com.bd.casttv.dlna.PlaybackController.recordPlaybackHistory(uri, title, source, com.bd.casttv.dlna.PlaybackController.currentArtworkUrl())
                } catch (_: Throwable) { /* 记录历史失败不影响播放启动 */ }
                context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(PlayerActivity.EXTRA_URI, uri)
                    putExtra(PlayerActivity.EXTRA_TITLE, title)
                    putExtra(PlayerActivity.EXTRA_SOURCE, source)
                    putExtra(PlayerActivity.EXTRA_RETURN_SKIP_PAGE_RELOAD, true)
                    putExtra(com.bd.casttv.ui.framework.NewMainActivity.EXTRA_OPEN_PAGE_ID, pageId)
                })
            }
        },
    )

    /** 顶部 GlobalTopStatusBar 高度补偿（与 NewMainActivity.TOP_STATUS_BAR_HEIGHT_DP 同步；占位对齐参考，不再叠加 padding）。 */
    private val topStatusBarInset = dp(35)
    private val pageHandler = Handler(Looper.getMainLooper())
    private var previewResumeQualified = false
    private var pageVisible = false
    private var previewAcquired = false
    private var hasShownCheckDialog = false
    private var checkDialogRetryCount = 0
    private val showCheckDialogRunnable = object : Runnable {
        override fun run() {
            if (!pageVisible || hasShownCheckDialog) return
            val channels = legacyPage.currentChannelsSnapshot()
            if (channels.isEmpty()) {
                if (checkDialogRetryCount++ < 10) pageHandler.postDelayed(this, 300L)
                return
            }
            hasShownCheckDialog = true
            SourceCheckDialog(context, channels, object : SourceCheckDialog.OnCheckResult {
                override fun onSkip() {
                    // 本次不做过滤，保持现有频道展示逻辑。
                }

                override fun onFilter(unavailableIds: Set<String>) {
                    legacyPage.applySessionUnavailableFilter(unavailableIds)
                }
            }).show()
        }
    }
    private val delayedPreviewStarter = Runnable {
        if (!pageVisible) return@Runnable
        previewResumeQualified = true
        legacyPage.resumePreview()
    }

    private var resourceList: RecyclerView? = null

    init {
        // 外壳容器：pageHeader 已由 BasePage 统一处理顶部预留，此处不再额外内边距。
        val shell = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        contentContainer.addView(shell, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 内容纵向排列：标题/操作提示同一行 + 旧版 legacy 页面。
        // 注意：标题没有置顶的真实原因是 legacy 内容容器 XML 仍带 26dp 顶部内边距，
        // 即使外层 stack 已经置顶，标题仍会被 legacy 自身 paddingTop 顶下来；根布局 margin 需要保留。
        val stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        shell.addView(stack, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // 挂载旧版 legacy 页面视图，并去掉最外层 crayon 面板边框/外边距。
        val legacyView = legacyPage.ensureInflated(stack) as ViewGroup
        legacyView.background = null
        (legacyView.getChildAt(0) as? ViewGroup)?.apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
        }
        // legacy 根 FrameLayout 的 XML margin 保留，只清掉首层内容容器的 paddingTop。
        // 标题行保持 wrap_content 置顶，legacy 内容区用 weight 撑满剩余高度。
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        stack.addView(legacyView, lp)

        legacyPage.bindTab(tab.id, tab.name, tab.collectionId)
        resourceList = legacyView.findViewById(R.id.resourceList)
    }

    override fun onEnter() {
        pageVisible = true
        acquirePreviewRef()
        legacyPage.setVisible(true)
        armPreviewResume()
        if (!hasShownCheckDialog) {
            checkDialogRetryCount = 0
            pageHandler.postDelayed(showCheckDialogRunnable, 500L)
        }
    }

    override fun onLeave() {
        pageVisible = false
        pageHandler.removeCallbacks(delayedPreviewStarter)
        pageHandler.removeCallbacks(showCheckDialogRunnable)
        legacyPage.pausePreviewForPageSwitch()
        releasePreviewRef()
        legacyPage.setVisible(false)
    }

    fun refreshAfterPlayerReturn() {
        pageVisible = true
        acquirePreviewRef()
        legacyPage.setVisible(true)
        previewResumeQualified = true
        pageHandler.removeCallbacks(delayedPreviewStarter)
        pageHandler.removeCallbacks(showCheckDialogRunnable)
        legacyPage.resumePreview()
    }

    fun pausePreviewForExternalCast() {
        pageVisible = false
        pageHandler.removeCallbacks(delayedPreviewStarter)
        pageHandler.removeCallbacks(showCheckDialogRunnable)
        legacyPage.pausePreviewForExternalCast()
    }

    override fun interceptPageSwitch(targetIndex: Int, direction: Int, proceed: () -> Unit): Boolean {
        if (!pageVisible) return false
        pageVisible = false
        pageHandler.removeCallbacks(delayedPreviewStarter)
        pageHandler.removeCallbacks(showCheckDialogRunnable)
        legacyPage.pausePreviewForPageSwitch()
        legacyPage.setVisible(false)
        releasePreviewRef()
        pageHandler.postDelayed({ proceed() }, PAGE_SWITCH_PAUSE_DELAY_MS)
        return true
    }

    override fun onDetachedFromWindow() {
        pageVisible = false
        pageHandler.removeCallbacks(delayedPreviewStarter)
        pageHandler.removeCallbacks(showCheckDialogRunnable)
        releasePreviewRef()
        legacyPage.destroyPreviewPage()
        super.onDetachedFromWindow()
    }

    override fun focusToFirstContent(): Boolean {
        legacyPage.requestInitialFocus()
        onFocusEnterContent()
        return true
    }

    /**
     * 左边界拦截：焦点位于频道列表内且按 LEFT 时，回落到 pageRootFocus，
     * 由 BoundaryFocusHandler 触发 Dock 左右切页；其他键交给 legacy 默认处理。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            val focused = findFocus()
            val list = resourceList
            if (focused != null && list != null && isDescendantOf(focused, list)) {
                focusToRoot()
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = findFocus()
            val list = resourceList
            if (focused != null && list != null && isDescendantOf(focused, list)) {
                focused.dispatchKeyEvent(event)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isDescendantOf(child: View, ancestor: View): Boolean {
        var p: View? = child
        while (p != null) { if (p === ancestor) return true; p = p.parent as? View }
        return false
    }

    private fun armPreviewResume() {
        pageHandler.removeCallbacks(delayedPreviewStarter)
        pageHandler.removeCallbacks(showCheckDialogRunnable)
        if (previewResumeQualified) {
            legacyPage.resumePreview()
        } else {
            pageHandler.postDelayed(delayedPreviewStarter, PREVIEW_STAY_THRESHOLD_MS)
        }
    }

    private fun acquirePreviewRef() {
        if (!previewAcquired) {
            legacyPage.acquirePreviewPlayer()
            previewAcquired = true
        }
    }

    private fun releasePreviewRef() {
        if (previewAcquired) {
            PreviewPlayerHolder.release()
            previewAcquired = false
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        private const val PREVIEW_STAY_THRESHOLD_MS = 3_000L
        private const val PAGE_SWITCH_PAUSE_DELAY_MS = 160L
    }
}
