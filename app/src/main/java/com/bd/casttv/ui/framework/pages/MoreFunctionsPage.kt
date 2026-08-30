package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.settings.CustomDockTabsStore
import com.bd.casttv.settings.Settings
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.NewMainActivity
import com.bd.casttv.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 启动台模式下从首页底部 Home Indicator 拉起的「更多功能」页面。 */
class MoreFunctionsPage(
    context: Context,
    private val homeIndicatorFocus: View? = null
) : BasePage(context) {
    override val pageId: String = "more_functions"
    override val pageTitle: String = "更多功能"
    override val pageIconRes: Int = R.drawable.ic_dock_home
    override val enablePageScroll: Boolean = false

    private val settings = Settings(context)

    /** 页面独立协程作用域：避免在主线程同步读盘/计算导致滑动卡顿或 ANR。 */
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadCardsJob: Job? = null

    private val adapter = FunctionAdapter()
    private var grid: RecyclerView? = null
    private var selectedPageId: String? = "watch_later"

    init {
        setBackground()
        isFocusable = true
        isFocusableInTouchMode = true

        val root = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, 0)
        }
        val title = TextView(context).apply {
            text = "更多功能"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = true
        }
        root.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46), Gravity.TOP or Gravity.START))

        val scrollContainer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 4)
            adapter = this@MoreFunctionsPage.adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            // RecyclerView 只作为卡片容器，不抢默认焦点；焦点应落到内部卡片并由系统按网格顺序移动。
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            // RecyclerView 保留现有 8dp 内边距，并允许 padding 区域内容可见；
            // 超出标题下方滚动区域的内容统一交给父容器裁剪。
            clipChildren = false
            clipToPadding = false
            // 卡片左右各 9dp margin → 4 列卡片间 18dp 间隙，且首尾卡片距屏幕两侧也 = rootPad(12)+9=21dp 对称
            setPadding(dp(0), dp(8), dp(0), dp(8))
        }
        this.grid = grid
        scrollContainer.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(scrollContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(58)
        })
        contentContainer.addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        refreshCards()
    }

    override fun refreshTheme() {
        super.refreshTheme()
        setBackground()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            return handleLauncherBack()
        }
        return super.dispatchKeyEvent(event)
    }

    fun handleLauncherBack(): Boolean {
        (context as? NewMainActivity)?.closeLauncherMoreFunctions(this, homeIndicatorFocus)
        return true
    }

    /**
     * 页面重新变为可见（从设置等子浮层返回）时调用：
     * 重新从 [CustomDockTabsStore] 读取最新自定义 Tab 列表并 diff 更新卡片，
     * 新增/删除的自定义 Tab 卡片会即时插入/移除到正确位置（抖音投屏之后、连接手机之前）。
     * 刷新时记录当前聚焦卡片 pageId，刷新后按 pageId 恢复焦点，避免焦点丢失。
     */
    fun refreshCards() {
        val g = grid

        // 1) 取消上一次未完成的加载，避免快速切换/滑动时堆积任务。
        loadCardsJob?.cancel()

        // 2) 把可能的读盘（自定义 Tab 列表、Settings）放到 IO 线程。
        loadCardsJob = pageScope.launch {
            val cards = withContext(Dispatchers.IO) {
                buildCards()
            }
            adapter.submit(cards)
            if (g != null) {
                g.post {
                    val restorePageId = selectedPageId ?: currentFocusedPageId()
                    if (restorePageId != null) {
                        restoreFocus(g, restorePageId)
                    } else if (hasFocus()) {
                        focusToFirstContent()
                    }
                }
            }
        }
    }

    override fun onEnter() {
        super.onEnter()
        refreshCards()
    }

    override fun focusToFirstContent(): Boolean {
        val g = grid ?: return false
        val targetPos = selectedPageId?.let { adapter.indexOfPageId(it) }?.takeIf { it >= 0 } ?: 0
        val target = g.findViewHolderForAdapterPosition(targetPos)?.itemView
        if (target?.requestFocus() == true) {
            onFocusEnterContent()
            return true
        }
        if (adapter.itemCount <= 0) return false
        g.scrollToPosition(targetPos)
        g.post {
            if (g.findViewHolderForAdapterPosition(targetPos)?.itemView?.requestFocus() == true) {
                onFocusEnterContent()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        // 页面销毁时取消协程，避免泄漏。
        pageScope.cancel()
        super.onDetachedFromWindow()
    }

    private fun currentFocusedPageId(): String? {
        val g = grid ?: return null
        val focused = findFocus() ?: return null
        var current: View? = focused
        while (current != null && current !== g) {
            val holder = g.findContainingViewHolder(current)
            if (holder != null) return adapter.pageIdAt(holder.bindingAdapterPosition)
            current = current.parent as? View
        }
        return null
    }

    private fun restoreFocus(g: RecyclerView, pageId: String?) {
        pageId ?: return
        val pos = adapter.indexOfPageId(pageId)
        if (pos < 0) return
        val vh = g.findViewHolderForAdapterPosition(pos)
        if (vh != null) {
            vh.itemView.requestFocus()
        } else {
            g.scrollToPosition(pos)
            g.post { g.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        }
    }

    private fun selectAndOpen(item: FunctionCard, sourceView: View) {
        val oldSelected = selectedPageId
        selectedPageId = item.pageId
        // 局部刷新：只更新选中态变化的两个卡片，避免全量 rebind 销毁焦点。
        val oldPos = oldSelected?.let { adapter.indexOfPageId(it) } ?: -1
        val newPos = adapter.indexOfPageId(item.pageId)
        if (oldPos >= 0 && oldPos != newPos) adapter.notifyItemChanged(oldPos)
        if (newPos >= 0) adapter.notifyItemChanged(newPos)
        (context as? NewMainActivity)?.openLauncherFunctionPage(item.pageId, sourceView)
    }

    private fun setBackground() {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            ThemeManager.currentPalette(context).dialogTitleGradient
        ).apply {
            cornerRadius = 0f
        }
    }

    private fun buildCards(): List<FunctionCard> {
        val cards = mutableListOf<FunctionCard>()
        cards += FunctionCard("watch_later", "稍后播放/推荐", intArrayOf(Color.rgb(255, 153, 102), Color.rgb(220, 93, 68)), Color.rgb(61, 53, 128), R.drawable.ic_more_watch_later)
        cards += FunctionCard("favorites", "我的收藏", intArrayOf(Color.rgb(255, 190, 88), Color.rgb(214, 109, 55)), Color.rgb(192, 87, 26), R.drawable.ic_more_favorites)
        if (settings.webParseEnabled) {
            cards += FunctionCard(Settings.PAGE_ID_WEB_PARSE, "网页解析播放", intArrayOf(Color.rgb(83, 190, 255), Color.rgb(42, 112, 214)), Color.rgb(26, 110, 110), R.drawable.ic_more_web_parse)
            cards += FunctionCard("cartoon_city", "动画城", intArrayOf(Color.rgb(255, 140, 200), Color.rgb(180, 60, 140)), Color.rgb(80, 26, 64), R.drawable.ic_more_cartoon)
        }
        if (settings.douyinCastEnabled) {
            cards += FunctionCard(Settings.PAGE_ID_DOUYIN_CAST, "抖音投屏", intArrayOf(Color.rgb(255, 110, 150), Color.rgb(180, 40, 96)), Color.rgb(26, 26, 46), R.drawable.ic_more_douyin_cast)
        }
        CustomDockTabsStore(context).list().forEachIndexed { index, tab ->
            val tabGradient = customTabGradient(index)
            cards += FunctionCard("customtab_${tab.id}", tab.name, tabGradient, tabGradient.lastOrNull() ?: Color.rgb(74, 74, 106), R.drawable.ic_more_custom_tab)
        }
        cards += FunctionCard("phonehub", "连接手机", intArrayOf(Color.rgb(86, 220, 180), Color.rgb(38, 128, 112)), Color.rgb(26, 92, 58), R.drawable.ic_more_phonehub)
        cards += FunctionCard("history", "历史记录", intArrayOf(Color.rgb(168, 142, 255), Color.rgb(82, 77, 190)), Color.rgb(42, 53, 80), R.drawable.ic_more_history)
        cards += FunctionCard("diagnostics", "网络诊断", intArrayOf(Color.rgb(102, 210, 255), Color.rgb(0, 114, 188)), Color.rgb(92, 42, 26), R.drawable.ic_more_diagnostics)
        cards += FunctionCard("help", "帮助", intArrayOf(Color.rgb(118, 220, 132), Color.rgb(50, 145, 82)), Color.rgb(42, 26, 92), R.drawable.ic_more_help)
        cards += FunctionCard("settings", "设置", intArrayOf(Color.rgb(82, 118, 190), Color.rgb(28, 48, 96)), Color.rgb(42, 53, 64), R.drawable.ic_more_settings)
        return cards
    }

    private fun customTabGradient(index: Int): IntArray {
        val presets = arrayOf(
            intArrayOf(Color.rgb(255, 182, 193), Color.rgb(196, 86, 128)),
            intArrayOf(Color.rgb(255, 214, 102), Color.rgb(178, 132, 36)),
            intArrayOf(Color.rgb(136, 204, 255), Color.rgb(70, 118, 210)),
            intArrayOf(Color.rgb(160, 230, 160), Color.rgb(60, 150, 100))
        )
        return presets[index % presets.size]
    }

    private inner class FunctionAdapter : RecyclerView.Adapter<FunctionAdapter.VH>() {
        private val data = mutableListOf<FunctionCard>()

        // 缓存卡片渐变缩略图，避免滑动/复用时频繁 new Drawable 造成主线程抖动。
        private val thumbCache = HashMap<String, LayerDrawable>()

        fun submit(list: List<FunctionCard>) {
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = data.size
                override fun getNewListSize(): Int = list.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    data[oldPos].pageId == list[newPos].pageId

                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                    val a = data[oldPos]
                    val b = list[newPos]
                    return a.title == b.title && a.iconRes == b.iconRes &&
                        a.logoBgColor == b.logoBgColor && a.colors.contentEquals(b.colors)
                }
            })
            data.clear()
            data.addAll(list)
            // 仅由 DiffUtil 派发增量更新；不再调 notifyDataSetChanged，
            // 否则全量 rebind 会销毁当前焦点，导致返回时焦点先落左上角再跳回选中卡片。
            diff.dispatchUpdatesTo(this)
        }

        fun pageIdAt(position: Int): String? = data.getOrNull(position)?.pageId
        fun indexOfPageId(pageId: String): Int = data.indexOfFirst { it.pageId == pageId }

        override fun getItemCount(): Int = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val screenW = resources.displayMetrics.widthPixels
            // scrollContainerPad(16*2=32) + 卡片左右 margin(12*2*4=96) = 128dp
            val horizontal = dp(32) + dp(12) * 8
            val cardW = ((screenW - horizontal) / 4f).toInt()
            val cardH = (cardW * 9f / 16f).toInt()

            val outer = FrameLayout(parent.context).apply {
                isFocusable = true
                isFocusableInTouchMode = false
                isClickable = true
                clipChildren = false
                clipToPadding = false
                setPadding(0, 0, 0, 0)
                layoutParams = RecyclerView.LayoutParams(cardW, cardH + dp(18)).apply {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                    bottomMargin = dp(18)
                }
            }

            val card = FrameLayout(parent.context).apply {
                clipChildren = true
                clipToPadding = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }

            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            val logo = FrameLayout(parent.context).apply {
                clipChildren = false
                clipToPadding = false
            }

            val logoBg = View(parent.context)
            val logoIcon = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = ColorStateList.valueOf(Color.rgb(232, 234, 240))
            }
            logo.addView(logoBg, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER))
            logo.addView(logoIcon, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))

            val shade = View(parent.context).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.TRANSPARENT, Color.argb(190, 8, 10, 16))
                )
            }

            val name = TextView(parent.context).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                // 文字阴影：在渐变缩略图上增强可读性（Taste skill: contrast check）
                setShadowLayer(4f, 0f, 2f, Color.argb(160, 0, 0, 0))
                setPadding(dp(10), 0, dp(10), dp(10))
                gravity = Gravity.BOTTOM or Gravity.START
            }

            card.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            card.addView(logo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            card.addView(shade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (cardH * 0.42f).toInt(), Gravity.BOTTOM))
            card.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            outer.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardH, Gravity.CENTER))

            return VH(outer, card, image, logo, logoBg, logoIcon, name)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(data[position])

        private fun thumbFor(item: FunctionCard): LayerDrawable {
            val key = "${item.pageId}_${item.colors.contentHashCode()}"
            return thumbCache.getOrPut(key) {
                gradientThumb(item.colors)
            }
        }

        inner class VH(
            private val outer: FrameLayout,
            private val card: FrameLayout,
            private val image: ImageView,
            private val logo: FrameLayout,
            private val logoBg: View,
            private val logoIcon: ImageView,
            private val name: TextView
        ) : RecyclerView.ViewHolder(outer) {

            private var current: FunctionCard? = null

            private val outerBorder = GradientDrawable().apply {
                cornerRadius = dp(CARD_CORNER_DP).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(0, Color.TRANSPARENT)
            }

            private val cardBg = GradientDrawable().apply {
                cornerRadius = dp(CARD_CORNER_DP).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(0, Color.TRANSPARENT)
            }

            private val logoBgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(30, 180, 190, 210))
                setStroke(dp(1), Color.argb(90, 180, 190, 210))
            }

            private var lastCardWidth: Int = 0

            init {
                outer.background = LayerDrawable(arrayOf(GlassCardDrawable(resources.displayMetrics.density), outerBorder))
                card.background = cardBg
                logoBg.background = logoBgDrawable

                // 监听一次布局变化：卡片宽度确定后再计算 logo 尺寸。
                outer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    updateLogoSizeIfNeeded()
                }

                outer.setOnFocusChangeListener { _, has ->
                    if (!has) {
                        BoundaryFocusHandler.cancelShake(outer)
                    }
                    updateBorder(has)
                    applyVisualState(animated = true)
                }

                outer.setOnClickListener {
                    val item = current ?: return@setOnClickListener
                    selectAndOpen(item, outer)
                }

                outer.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val item = current ?: return@setOnKeyListener false

                    val position = bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnKeyListener false

                    /**
                     * 真·边界判断：以系统实际 [View.focusSearch] 结果为准，
                     * 不依赖纯 position 静态推导，避免「最后一行不足 4 列」
                     * 或 GridLayoutManager 几何找焦结果与 pos 线性不一致时，
                     * 出现"明明能移动焦点却还触发抖动/拦截"的误报。
                     *
                     * 判定规则：next == null / 还是自己 / 不在 grid 容器内（被父页
                     * 截胡到标题/页面根节点）→ 本方向无可移动卡片，才 shake+拦截。
                     */
                    fun hitBoundary(direction: Int): Boolean {
                        val g = grid ?: return true
                        val next = outer.focusSearch(direction) ?: return true
                        if (next === outer) return true
                        // 下一个焦点必须仍在本 RecyclerView 内才算"能在卡片之间继续移动"。
                        var cur: View? = next
                        while (cur != null && cur !== g) {
                            if (g.findContainingViewHolder(cur) != null) return false
                            cur = cur.parent as? View
                        }
                        return true
                    }

                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (hitBoundary(View.FOCUS_UP)) {
                                BoundaryFocusHandler.shake(outer)
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (hitBoundary(View.FOCUS_DOWN)) {
                                BoundaryFocusHandler.shake(outer)
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (hitBoundary(View.FOCUS_LEFT)) {
                                BoundaryFocusHandler.shake(outer)
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (hitBoundary(View.FOCUS_RIGHT)) {
                                BoundaryFocusHandler.shake(outer)
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            selectAndOpen(item, outer)
                            true
                        }
                        else -> false
                    }
                }
            }

            fun bind(item: FunctionCard) {
                current = item

                name.text = item.title
                image.setImageDrawable(null)

                // 图标 chip 按卡品牌色渐变填充，每卡获得色彩身份（复活死代码 colors）。
                logoIcon.setImageResource(item.iconRes)
                // 2026-08：动画城按参考太阳笑脸画成了全彩色图标，必须移除 imageTint 才能显示蓝/橙/黄/红；
                // 其他功能卡片（设置/帮助/收藏…）仍是浅色线稿，继续走统一浅灰 Tint，不互相污染。
                // RecyclerView 复用：非动画城卡必须把 tint 重新写回去。
                val cartoonTint = item.pageId == "cartoon_city"
                logoIcon.imageTintList = if (cartoonTint) {
                    null
                } else {
                    ColorStateList.valueOf(Color.rgb(232, 234, 240))
                }
                // logoIcon 本身还是浅灰的半透描边盘（保持玻璃盘质感），彩色太阳落在盘心。
                if (item.colors.isNotEmpty()) {
                    logoBgDrawable.colors = item.colors
                    logoBgDrawable.orientation = GradientDrawable.Orientation.TL_BR
                }

                updateBorder(outer.hasFocus())
                applyVisualState(animated = true)
                updateLogoSizeIfNeeded()
            }

            private fun applyVisualState(animated: Boolean) {
                val selected = current?.pageId == selectedPageId
                val focused = outer.hasFocus()
                if (focused && !selected) {
                    FocusFxHelper.applyFocusFxState(outer, true, cornerRadiusDp = CARD_CORNER_DP)
                    return
                }

                outer.foreground = null
                outer.animate().cancel()
                val targetScale = if (selected) 1.04f else 1f
                val targetTranslationZ = if (selected) dp(4).toFloat() else 0f
                if (animated) {
                    outer.animate()
                        .scaleX(targetScale)
                        .scaleY(targetScale)
                        .translationZ(targetTranslationZ)
                        .setDuration(140L)
                        .start()
                } else {
                    outer.scaleX = targetScale
                    outer.scaleY = targetScale
                    outer.translationZ = targetTranslationZ
                }
            }

            private fun updateBorder(focused: Boolean) {
                if (focused) {
                    outerBorder.setStroke(dp(3), WARM)
                } else {
                    outerBorder.setStroke(0, Color.TRANSPARENT)
                }
            }

            private fun updateLogoSizeIfNeeded() {
                val cardW = card.width
                if (cardW <= 0 || cardW == lastCardWidth) return
                lastCardWidth = cardW

                val bgSize = (cardW * 0.4f).toInt()
                val iconSize = (bgSize * 0.55f).toInt()

                val bgLp = logoBg.layoutParams
                if (bgLp.width != bgSize || bgLp.height != bgSize) {
                    bgLp.width = bgSize
                    bgLp.height = bgSize
                    logoBg.layoutParams = bgLp
                }

                val iconLp = logoIcon.layoutParams
                if (iconLp.width != iconSize || iconLp.height != iconSize) {
                    iconLp.width = iconSize
                    iconLp.height = iconSize
                    logoIcon.layoutParams = iconLp
                }
            }
        }
    }

    private fun gradientThumb(colors: IntArray): LayerDrawable {
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(CARD_CORNER_DP).toFloat()
        }
        val shine = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(70, 255, 255, 255), Color.TRANSPARENT)
        ).apply { cornerRadius = dp(CARD_CORNER_DP).toFloat() }
        return LayerDrawable(arrayOf(bg, shine))
    }

    private fun normalBorder(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(CARD_CORNER_DP).toFloat()
        setColor(Color.TRANSPARENT)
        if (focused) {
            setStroke(dp(3), WARM)
        } else {
            setStroke(0, Color.TRANSPARENT)
        }
    }

    private data class FunctionCard(
        val pageId: String,
        val title: String,
        val colors: IntArray,
        val logoBgColor: Int,
        val iconRes: Int
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val CARD_CORNER_DP = 16
        private val WARM = Color.rgb(245, 196, 81)
    }
}

private class GlassCardDrawable(
    private val density: Float
) : Drawable() {
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        val radius = dp(16).toFloat()

        // 基底：深蓝半透填充（液态玻璃质感）
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 16, 28, 68)
        canvas.drawRoundRect(rect, radius, radius, paint)

        // 外层蓝光：收敛 alpha 60→28，避免与暖黄焦点边框抢色（Color Consistency Lock）
        paint.style = Paint.Style.STROKE
        paint.shader = null
        paint.strokeWidth = dp(3).toFloat()
        paint.color = Color.argb(28, 80, 140, 255)
        canvas.drawRoundRect(rect.insetCopy(dp(1).toFloat()), radius, radius, paint)

        // 内层描边：1px 蓝白细线，保持玻璃边缘锐利
        paint.strokeWidth = dp(1).toFloat()
        paint.color = Color.argb(180, 80, 140, 255)
        canvas.drawRoundRect(rect.insetCopy(dp(1).toFloat()), radius, radius, paint)

        // 顶部内高光：1px 白色半透线，模拟玻璃折射光（Taste skill glassmorphism: inner border highlight）
        paint.strokeWidth = dp(1).toFloat()
        paint.color = Color.argb(20, 255, 255, 255)
        val highlightRect = RectF(
            rect.left + dp(2).toFloat(),
            rect.top + dp(1).toFloat(),
            rect.right - dp(2).toFloat(),
            rect.top + dp(2).toFloat()
        )
        canvas.drawRoundRect(highlightRect, radius * 0.6f, radius * 0.6f, paint)

        // 对角微光渐变：保持原有斜向光泽
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            intArrayOf(Color.TRANSPARENT, Color.argb(26, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun RectF.insetCopy(inset: Float): RectF = RectF(left + inset, top + inset, right - inset, bottom - inset)
}
