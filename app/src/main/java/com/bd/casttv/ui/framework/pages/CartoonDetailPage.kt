package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MimeTypes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.cartoon.CartoonStore
import com.bd.casttv.dlna.PlaybackController
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.settings.Settings
import com.bd.casttv.sync.GiteeApi
import com.bd.casttv.sync.GiteeShareStore
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.NewMainActivity
import com.bd.casttv.ui.theme.CartoonDesign
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.webparse.AdapterKind
import com.bd.casttv.webparse.AdapterSelectResult
import com.bd.casttv.webparse.AdapterSelector
import com.bd.casttv.webparse.ParsePageKind
import com.bd.casttv.webparse.ParseProgress
import com.bd.casttv.webparse.ParseStep
import com.bd.casttv.webparse.ParsedMovie
import com.bd.casttv.webparse.ParsedSource
import com.bd.casttv.webparse.RuleBasedAdapter
import com.bd.casttv.webparse.WebFrameworkType
import com.bd.casttv.webparse.WebParseAdapterStore
import com.bd.casttv.webparse.WebParseExtractor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 动画城自建结果页（替代跳 WebParsePage 整页）。
 * 需求点：
 *  - 展示封面图、标题、简介、集数
 *  - 复用原网页解析器（WebParseExtractor + AdapterSelector + 云端 JSON 规则下载/绑定）执行解析
 *  - 解析完成后自动调用 GiteeShareStore.upsertCartoon 更新云端 title/cover/description/episodeCount/globalAdapterId
 *  - 提供选集播放（解析 URL → PlayerActivity）
 *  - BACK 键按 NewMainActivity overlay 栈行为自动返回动画城列表
 */
class CartoonDetailPage(
    context: Context,
    private val cartoon: GiteeShareStore.SharedCartoon,
    private val onCloudUpdated: ((GiteeShareStore.SharedCartoon) -> Unit)? = null
) : BasePage(context) {

    override val pageId = "cartoon_detail_${cartoon.cartoonId}"
    override val pageTitle = cartoon.title.ifBlank { "动画详情" }
    override val pageIconRes = R.drawable.ic_more_cartoon
    override val enablePageScroll = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cartoonStore = CartoonStore(context)
    private val adapterStore = WebParseAdapterStore(context.applicationContext)
    private val errHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "async failed", t)
        showStatus("执行失败", t.message ?: "未知错误", success = false)
    }

    private var parseJob: Job? = null
    private var playJob: Job? = null
    private var extractor: WebParseExtractor? = null
    private var parsed: ParsedMovie? = null
    private var sources: List<ParsedSource> = emptyList()
    private var sourceIdx: Int = 0
    private var descExpanded: Boolean = false

    // Views
    private val coverView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageResource(R.drawable.ic_thumb_default)
    }
    // 海报式胶囊徽章（浮在封面左下角，语义化）
    private val posterBadge = TextView(context).apply {
        textSize = CartoonDesign.Type.BADGE
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
        gravity = Gravity.CENTER
        val ph = CartoonDesign.dp(context, 10); val pv = CartoonDesign.dp(context, 6)
        setPadding(ph, pv, ph, pv)
        background = CartoonDesign.capsuleBadge(
            context, CartoonDesign.Palette.BADGE_ACCENT_BG,
            CartoonDesign.withAlpha(CartoonDesign.Palette.ACCENT_DIM, 220)
        )
    }
    private val eyebrowView = TextView(context).apply {
        text = "已加入我的动画城"
        textSize = CartoonDesign.Type.BADGE
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(CartoonDesign.Palette.ACCENT)
        setPadding(0, 0, 0, CartoonDesign.dp(context, 6))
        letterSpacing = 0.04f
    }
    private val titleView = TextView(context).apply {
        textSize = CartoonDesign.Type.DISPLAY
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 0.98f)
    }
    private val metaView = TextView(context).apply {
        textSize = CartoonDesign.Type.META
        setTextColor(CartoonDesign.Palette.TEXT_SECONDARY)
        setLineSpacing(CartoonDesign.dp(context, 2).toFloat(), 1f)
    }
    private val descView = TextView(context).apply {
        textSize = CartoonDesign.Type.BODY
        setTextColor(CartoonDesign.Palette.TEXT_SECONDARY)
        setLineSpacing(CartoonDesign.dp(context, 4).toFloat(), 1f)
        maxLines = 4; ellipsize = TextUtils.TruncateAt.END
    }
    private val descToggle = TextView(context).apply {
        text = "展开"
        textSize = CartoonDesign.Type.TITLE_SM
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        visibility = View.GONE
        isFocusable = true
        isClickable = true
        val ph = CartoonDesign.dp(context, 10); val pv = CartoonDesign.dp(context, 4)
        setPadding(ph, pv, ph, pv)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 9999f
            setStroke(Math.max(1, CartoonDesign.dp(context, 1)), Color.argb(198, 198, 214, 230))
            setColor(Color.TRANSPARENT)
        }
        setOnClickListener { toggleDesc() }
    }
    // 状态：胶囊徽 + spinner 行（按钮簇下）
    private val statusChip = TextView(context).apply {
        textSize = CartoonDesign.Type.STATUS
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        val ph = CartoonDesign.dp(context, 14); val pv = CartoonDesign.dp(context, 8)
        setPadding(ph, pv, ph, pv)
        background = CartoonDesign.capsuleBadge(
            context, CartoonDesign.Palette.BADGE_INFO_BG,
            Color.argb(180, 130, 160, 230)
        )
    }
    private val reparseButton = primaryButton("重新解析") { startParse(forcePreferAdapter = true) }
    private val sourceTabs = mutableListOf<TextView>()
    private val sourceContainer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val episodeAdapter = EpisodeAdapter()
    private var episodeGrid: RecyclerView? = null

    init {
        buildLayout()
        // 首次进入：用当前云端数据把标题/简介/集数先铺出来，用户可立即看到
        applyStaticCartoonSnapshot()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // attach 后立刻走解析流程（避免 view 未布局时网络完成 UI 回调崩溃）
        if (parsed == null) startParse(forcePreferAdapter = false)
    }

    override fun onDetachedFromWindow() {
        parseJob?.cancel()
        playJob?.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    override fun focusToFirstContent(): Boolean {
        // 焦点优先落在：选集区第 0 集 → 失败时回退到重新解析
        return (episodeGrid?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true)
            || (reparseButton.requestFocus())
    }

    // ---------------- UI 构建 ----------------

    private fun buildLayout() {
        val outer = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            // 收紧外层 padding：给 18/8/18/16，卡片占比更饱满，不压内容。
            setPadding(CartoonDesign.dp(context, 18), CartoonDesign.dp(context, 8),
                CartoonDesign.dp(context, 18), CartoonDesign.dp(context, 16))
            isFocusable = false
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 顶部：封面（左 3:4，液态玻璃卡 + 左下角语义胶囊徽章）+ 右侧排版簇
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val coverWrap = FrameLayout(context).apply {
            background = CartoonDesign.liquidGlassDrawable(
                context, CartoonDesign.Radius.LG, CartoonDesign.TintMode.ELEVATED,
                CartoonDesign.Palette.STROKE_HARD
            )
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            clipChildren = false
        }
        // 216 × 4/3（上一版保持缩小尺寸），圆角 20（Radius.LG）。
        val coverW = CartoonDesign.dp(context, 216)
        val coverH = (coverW * 4f / 3f).toInt()
        coverWrap.addView(coverView, FrameLayout.LayoutParams(coverW, coverH))
        // 左下角胶囊徽章（海报式浮雕）
        val badgeLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply {
            setMargins(CartoonDesign.dp(context, 10), 0, 0, CartoonDesign.dp(context, 10))
        }
        coverWrap.addView(posterBadge, badgeLp)
        header.addView(coverWrap, LinearLayout.LayoutParams(coverW, coverH))

        val rightPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(CartoonDesign.dp(context, 20), 0, 0, 0)
        }
        rightPanel.addView(eyebrowView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        rightPanel.addView(titleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = CartoonDesign.dp(context, 2) })
        rightPanel.addView(metaView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = CartoonDesign.dp(context, 10) })
        // 简介区：description + 展开/收起 toggle（胶囊）
        val descWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        descWrap.addView(descView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        descWrap.addView(descToggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = CartoonDesign.dp(context, 8) })
        rightPanel.addView(descWrap, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = CartoonDesign.dp(context, 12) })

        // 按钮簇 + 胶囊状态行：主 CTA（琥珀底液态玻璃 ACCENT）+ 次 CTA + 小 spinner + 状态胶囊
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(reparseButton)
        actions.addView(separator(CartoonDesign.dp(context, 10)))
        val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true; indeterminateDrawable?.setColorFilter(
                CartoonDesign.Palette.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN
            ); visibility = View.GONE; tag = "spinner"
        }
        actions.addView(spinner)
        actions.addView(separator(CartoonDesign.dp(context, 10)))
        actions.addView(statusChip, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        rightPanel.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = CartoonDesign.dp(context, 18) })

        header.addView(rightPanel, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ).apply { gravity = Gravity.TOP })
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 播放源 Tab + 选集区：分区标题升级为大号胶囊徽 + TITLE_MD 文案
        root.addView(sectionHeader("播放源", CartoonDesign.Palette.BADGE_INFO_BG,
            Color.argb(180, 130, 160, 230)), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = CartoonDesign.dp(context, 22); bottomMargin = CartoonDesign.dp(context, 8) })

        val sourceScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; isFocusable = false
        }
        sourceContainer.setPadding(0, 0, 0, 0)
        sourceScroller.addView(sourceContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(sourceScroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(sectionHeader("选集", CartoonDesign.Palette.BADGE_ACCENT_BG,
            CartoonDesign.withAlpha(CartoonDesign.Palette.ACCENT_DIM, 220)), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = CartoonDesign.dp(context, 18); bottomMargin = CartoonDesign.dp(context, 8) })

        val rv = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 8)
            adapter = episodeAdapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            clipToPadding = false
            setPadding(CartoonDesign.dp(context, 2), CartoonDesign.dp(context, 4),
                CartoonDesign.dp(context, 2), CartoonDesign.dp(context, 4))
            addItemDecoration(EpisodeItemDecoration(CartoonDesign.dp(context, 10),
                CartoonDesign.dp(context, 10), 8))
        }
        episodeGrid = rv
        root.addView(rv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, CartoonDesign.dp(context, 320)
        ).apply { topMargin = CartoonDesign.dp(context, 4) })

        outer.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        contentContainer.addView(outer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 封面预加载：先用 cartoon.cover，解析完成后再用 parsed.coverUrl 覆盖
        loadCover(cartoon.cover, coverView)
        renderPosterBadge(cartoon.episodeCount, cartoon.description)
    }

    private fun separator(w: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(w, 0)
    }

    /** 分区标题：小胶囊色块 + 大号粗体文案（Apple TV 节目单风格）。 */
    private fun sectionHeader(label: String, @androidx.annotation.ColorInt bg: Int,
                               @androidx.annotation.ColorInt stroke: Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val chip = View(context).apply {
            background = CartoonDesign.capsuleBadge(context, bg, stroke)
            val w = CartoonDesign.dp(context, 4); val h = CartoonDesign.dp(context, 16)
            layoutParams = LinearLayout.LayoutParams(w, h)
        }
        val txt = TextView(context).apply {
            text = label
            textSize = CartoonDesign.Type.TITLE_MD
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
            setPadding(CartoonDesign.dp(context, 10), 0, 0, 0)
        }
        row.addView(chip); row.addView(txt)
        return row
    }

    private fun renderPosterBadge(count: Int, description: String) {
        val ctx = context
        when {
            count <= 0 -> {
                posterBadge.text = "更新中"
                posterBadge.setTextColor(CartoonDesign.Palette.BADGE_WARN_FG)
                posterBadge.background = CartoonDesign.capsuleBadge(
                    ctx, CartoonDesign.Palette.BADGE_WARN_BG,
                    Color.argb(180, 220, 156, 68)
                )
                posterBadge.visibility = View.VISIBLE
            }
            count >= 120 -> {
                posterBadge.text = "全${count}集·已完结"
                posterBadge.setTextColor(CartoonDesign.Palette.BADGE_SUCCESS_FG)
                posterBadge.background = CartoonDesign.capsuleBadge(
                    ctx, CartoonDesign.Palette.BADGE_SUCCESS_BG,
                    Color.argb(170, 72, 186, 128)
                )
                posterBadge.visibility = View.VISIBLE
            }
            else -> {
                posterBadge.text = "更新至第${count}集"
                posterBadge.setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
                posterBadge.background = CartoonDesign.capsuleBadge(
                    ctx, CartoonDesign.Palette.BADGE_ACCENT_BG,
                    CartoonDesign.withAlpha(CartoonDesign.Palette.ACCENT_DIM, 220)
                )
                posterBadge.visibility = View.VISIBLE
            }
        }
        if (description.isBlank() && count <= 0) {
            posterBadge.text = "待解析"
        }
    }

    // ---------------- 静态/解析结果渲染 ----------------

    private fun applyStaticCartoonSnapshot() {
        titleView.text = cartoon.title
        val epText = if (cartoon.episodeCount > 0) "共 ${cartoon.episodeCount} 集" else "集数解析中"
        val catText = listOfNotNull(
            cartoon.adapterName.ifBlank { null }?.let { "解析器 · $it" },
            cartoon.deviceName.ifBlank { null }?.let { "同步自 · $it" }
        ).joinToString("   |   ")
        metaView.text = listOf(epText, catText).filter { it.isNotBlank() }.joinToString("\n")
        renderDescription(cartoon.description.ifBlank { "简介解析中，稍等片刻即可展示剧情介绍。" })
        renderPosterBadge(cartoon.episodeCount, cartoon.description)
        showStatus("准备解析", "如果启用了该站点的云端 JSON 规则，会优先用规则；否则用内置 MacCMS/ZyPlayer 等解析器。", null)
    }

    private fun applyParsedMovie(movie: ParsedMovie) {
        parsed = movie
        titleView.text = movie.title.ifBlank { cartoon.title }
        val totalEps = movie.sources.sumOf { it.episodes.size }
        val extras = mutableListOf<String>()
        extras += if (totalEps > 0) "共 $totalEps 集" else "暂无剧集"
        if (movie.category.isNotBlank()) extras += "分类 · ${movie.category}"
        if (movie.year.isNotBlank()) extras += "年份 · ${movie.year}"
        if (movie.area.isNotBlank()) extras += "地区 · ${movie.area}"
        if (movie.director.isNotBlank()) extras += "导演 · ${movie.director}"
        if (movie.actors.isNotBlank()) extras += "主演 · ${movie.actors}"
        // 按 2 行显示：第一行集数+分类+年份，第二行主创（投影仪上更易扫读）
        metaView.text = if (extras.size <= 4) extras.joinToString("   |   ")
        else {
            val row1 = extras.take(4).joinToString("   |   ")
            val row2 = extras.drop(4).joinToString("   |   ")
            "$row1\n$row2"
        }
        renderDescription(movie.description.ifBlank { "（该站点未返回剧情介绍）" })
        if (movie.coverUrl.isNotBlank() && movie.coverUrl != cartoon.cover) {
            loadCover(movie.coverUrl, coverView)
        }
        renderPosterBadge(totalEps, movie.description)
        sources = movie.sources
        sourceIdx = 0
        renderSourceTabs()
        renderEpisodes()
    }

    private fun renderDescription(text: String) {
        descView.text = text
        // 判断是否需要"展开/收起"：按 4 行 vs 全文 vs 全文行数
        descView.post {
            val lp = descView.layoutParams
            val measureW = descView.measuredWidth - descView.compoundPaddingLeft - descView.compoundPaddingRight
            if (measureW <= 0 || text.isBlank()) {
                descToggle.visibility = View.GONE
                return@post
            }
            val paint = descView.paint
            val density = resources.displayMetrics.density
            val textSizePx = CartoonDesign.Type.BODY * density
            val paintCopy = android.text.TextPaint(paint).apply { textSize = textSizePx }
            val layout = android.text.StaticLayout(
                text, paintCopy, measureW,
                android.text.Layout.Alignment.ALIGN_NORMAL,
                1f + CartoonDesign.dp(context, 4) / textSizePx, 0f, false
            )
            val total = layout.lineCount
            val collapsedLines = 4
            if (total <= collapsedLines) {
                descToggle.visibility = View.GONE
                descView.maxLines = Int.MAX_VALUE
                descView.ellipsize = null
            } else {
                descToggle.visibility = View.VISIBLE
                applyDescLines(expanded = descExpanded)
                descToggle.text = if (descExpanded) "收起" else "展开全部"
            }
        }
    }

    private fun toggleDesc() {
        descExpanded = !descExpanded
        applyDescLines(descExpanded)
        descToggle.text = if (descExpanded) "收起" else "展开全部"
    }

    private fun applyDescLines(expanded: Boolean) {
        if (expanded) {
            descView.maxLines = Int.MAX_VALUE
            descView.ellipsize = null
        } else {
            descView.maxLines = 4
            descView.ellipsize = TextUtils.TruncateAt.END
        }
    }

    private fun renderSourceTabs() {
        sourceContainer.removeAllViews()
        sourceTabs.clear()
        sources.forEachIndexed { idx, src ->
            val v = TextView(context).apply {
                text = if (src.name.isBlank()) "源${idx + 1}" else src.name
                textSize = CartoonDesign.Type.TITLE_SM
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
                gravity = Gravity.CENTER
                isFocusable = true
                val ph = CartoonDesign.dp(context, 14); val pv = CartoonDesign.dp(context, 8)
                setPadding(ph, pv, ph, pv)
                updateSourceTabStyle(this, idx, idx == sourceIdx, focused = false)
                setOnClickListener { selectSource(idx) }
                setOnKeyListener { _, k, e ->
                    if (e.action == KeyEvent.ACTION_DOWN &&
                        (k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_DPAD_CENTER)
                    ) { selectSource(idx); true } else false
                }
                setOnFocusChangeListener { _, has ->
                    BoundaryFocusHandler.cancelShake(this)
                    updateSourceTabStyle(this, idx, idx == sourceIdx, has)
                    if (has) FocusFxHelper.applyFocusFxState(this, true, cornerRadiusDp = CartoonDesign.Radius.MD.dp)
                    else foreground = null
                }
            }
            sourceContainer.addView(v, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = CartoonDesign.dp(context, 10) })
            sourceTabs += v
        }
    }

    private fun updateSourceTabStyle(view: TextView, idx: Int, active: Boolean, focused: Boolean) {
        val ctx = context
        when {
            focused -> {
                view.background = CartoonDesign.liquidGlassDrawable(
                    ctx, CartoonDesign.Radius.MD, CartoonDesign.TintMode.ACCENT,
                    CartoonDesign.Palette.ACCENT
                )
                CartoonDesign.liquidGlassToFocused(ctx, view, true)
                view.setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
            }
            active -> {
                view.background = CartoonDesign.liquidGlassDrawable(
                    ctx, CartoonDesign.Radius.MD, CartoonDesign.TintMode.ELEVATED,
                    CartoonDesign.Palette.ACCENT
                )
                CartoonDesign.liquidGlassToFocused(ctx, view, false)
                view.setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
            }
            else -> {
                view.background = CartoonDesign.liquidGlassDrawable(
                    ctx, CartoonDesign.Radius.MD, CartoonDesign.TintMode.BASE,
                    CartoonDesign.Palette.STROKE_SOFT
                )
                CartoonDesign.liquidGlassToFocused(ctx, view, false)
                view.setTextColor(CartoonDesign.Palette.TEXT_SECONDARY)
            }
        }
    }

    private fun selectSource(idx: Int) {
        if (idx !in sources.indices) return
        sourceIdx = idx
        sources.forEachIndexed { i, _ ->
            val v = sourceTabs.getOrNull(i) ?: return@forEachIndexed
            updateSourceTabStyle(v, i, active = i == idx, focused = v.hasFocus())
        }
        renderEpisodes()
        episodeGrid?.post {
            val target = (episodeGrid?.layoutManager as? GridLayoutManager)?.findViewByPosition(0)
            target?.requestFocus()
        }
    }

    private fun renderEpisodes() {
        val list = sources.getOrNull(sourceIdx)?.episodes.orEmpty()
            .mapIndexed { i, ep -> EpisodeItem(i, ep.name.ifBlank { "第${i + 1}集" }, ep.playPageUrl) }
        episodeAdapter.submit(list)
    }

    // ---------------- 解析核心（复用 WebParsePage 等效链路） ----------------

    private fun startParse(forcePreferAdapter: Boolean) {
        val settings = Settings(context.applicationContext)
        if (!settings.webParseEnabled) {
            showStatus("无法解析", "请先在设置中启用网页解析功能", success = false)
            return
        }
        parseJob?.cancel()
        showStatus("开始解析", cartoon.detailUrl, spinner = true)
        parseJob = scope.launch(errHandler + Dispatchers.Main.immediate) {
            // Step1: 如果有 globalAdapterId → 下载 JSON 规则文件并 forceUpdateBinding 到 host
            if (!cartoon.globalAdapterId.isNullOrBlank()) {
                val host = runCatching { URL(cartoon.detailUrl).host }.getOrDefault("")
                val meta = GiteeShareStore.SharedAdapter(
                    globalAdapterId = cartoon.globalAdapterId,
                    localAdapterId = "",
                    name = cartoon.adapterName.ifBlank { cartoon.globalAdapterId },
                    host = host, pageKind = "DETAIL", frameworkType = "UNKNOWN",
                    creatorId = "", uploadedAt = 0L
                )
                val download = withContext(Dispatchers.IO) {
                    GiteeShareStore.downloadAdapterById(context.applicationContext, cartoon.globalAdapterId, meta)
                }
                when (download) {
                    is GiteeApi.ApiResult.Success -> {
                        val ruleFileName = download.value
                        adapterStore.forceUpdateBinding(
                            WebParseAdapterStore.DomainBinding(
                                pageKind = ParsePageKind.DETAIL,
                                host = host,
                                adapterId = cartoon.globalAdapterId,
                                adapterKind = AdapterKind.CUSTOM_JSON,
                                adapterName = cartoon.adapterName.ifBlank { cartoon.globalAdapterId },
                                ruleFileName = ruleFileName,
                                frameworkType = WebFrameworkType.UNKNOWN,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        showStatus("准备解析", "已加载云端适配器：${cartoon.adapterName.ifBlank { cartoon.globalAdapterId }}", spinner = true)
                    }
                    else -> {
                        showStatus("适配器下载失败", "将使用默认解析器继续", success = false, spinner = true)
                    }
                }
            } else if (forcePreferAdapter) {
                // 强制重新解析时，如果没有绑定适配器，让 AdapterSelector 自由选择
            }

            // Step2: 选择适配器 → 提取 ParsedMovie
            val appCtx = context.applicationContext
            val preselected = AdapterSelector.select(appCtx, cartoon.detailUrl, "", ParsePageKind.DETAIL)
            val localExtractor = WebParseExtractor(appCtx) { p ->
                scope.launch(Dispatchers.Main.immediate) {
                    if (p.step == ParseStep.ERROR) {
                        showStatus("解析失败", p.error.ifBlank { p.message }, success = false, spinner = false)
                    } else {
                        showStatus("解析中", p.message, spinner = true)
                    }
                }
            }
            extractor = localExtractor
            val movie = withContext(Dispatchers.Default) {
                if (preselected.source == AdapterSelectResult.SelectSource.DOMAIN_BINDING &&
                    preselected.adapterInfo.kind == AdapterKind.CUSTOM_JSON
                ) {
                    val ruleFileName = preselected.adapterInfo.description.ifBlank { preselected.adapterInfo.id }
                    val rule = withContext(Dispatchers.IO) {
                        RuleBasedAdapter.readRuleText(appCtx, ruleFileName)
                    }
                    if (rule.isBlank()) error("绑定的自定义适配器规则文件不存在")
                    localExtractor.extractWithRule(cartoon.detailUrl, ruleFileName)
                } else {
                    localExtractor.extract(cartoon.detailUrl)
                }
            }
            applyParsedMovie(movie)

            // Step3: upsertCartoon 到云端，title/cover/description/episodeCount 更新，
            //        使用既有 cartoonId（不按 URL 重算），避免用户在云端改 ID 后幂等错位。
            val totalEps = movie.sources.sumOf { it.episodes.size }
            val newTitle = movie.title.ifBlank { cartoon.title }
            val newCover = movie.coverUrl.ifBlank { cartoon.cover }
            val newDesc = movie.description
            val adapterId = cartoon.globalAdapterId
                ?: preselected.adapterInfo.id.takeIf { preselected.adapterInfo.kind == AdapterKind.CUSTOM_JSON }
            val adapterName = when {
                cartoon.adapterName.isNotBlank() -> cartoon.adapterName
                preselected.adapterInfo.name.isNotBlank() -> preselected.adapterInfo.name
                else -> ""
            }
            val cloud = withContext(Dispatchers.IO) {
                GiteeShareStore.upsertCartoon(
                    context = appCtx,
                    title = newTitle,
                    detailUrl = cartoon.detailUrl,
                    cover = newCover,
                    globalAdapterId = adapterId,
                    adapterName = adapterName,
                    episodeCount = totalEps,
                    description = newDesc,
                    cartoonId = cartoon.cartoonId
                )
            }
            when (cloud) {
                is GiteeApi.ApiResult.Success -> {
                    val refreshed = cloud.value
                    cartoonStore.upsertLocal(refreshed)
                    onCloudUpdated?.invoke(refreshed)
                    showStatus(
                        "已同步云端",
                        buildString {
                            append("版本：").append(refreshed.title)
                            if (totalEps > 0) append(" · 集数 $totalEps")
                            if (newDesc.isNotBlank()) append(" · 简介已更新")
                        },
                        success = true
                    )
                }
                is GiteeApi.ApiResult.Error -> {
                    showStatus("云端写入失败", cloud.message, success = false)
                }
                else -> {
                    showStatus("云端写入失败", "服务返回异常", success = false)
                }
            }
        }
    }

    // ---------------- 播放 ----------------

    private fun playEpisode(item: EpisodeItem) {
        playJob?.cancel()
        showStatus("解析播放地址", item.name, spinner = true)
        val epUrl = item.playPageUrl
        playJob = scope.launch(errHandler) {
            val resolved = withContext(Dispatchers.Default) {
                val e = extractor
                when {
                    e != null -> e.resolve(epUrl)
                    // 极少数情况 extractor 已被 null 化，兜底新建通用 extractor
                    else -> WebParseExtractor(context.applicationContext).run {
                        // 仅 resolve：不指定适配器，会走内置适配器的 resolvePlayUrl，通常需要 lastAdapter；
                        // 兜底失败返回 null，由 UI 报“请先完成解析”。
                        runCatching { extract(cartoon.detailUrl) }.getOrNull()
                        resolve(epUrl)
                    }
                }
            }
            if (resolved.isNullOrBlank()) {
                showStatus("播放地址解析失败", "可能站点未支持解析此集；试试「重新解析」。", success = false)
                return@launch
            }
            val uri = resolved
            val mime = inferMimeType(uri)
            val title = "${cartoon.title.ifBlank { "动画城剧集" }} - ${item.name}"
            PlaybackController.recordPlaybackHistory(uri, title, "动画城", cartoon.cover)
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(PlayerActivity.EXTRA_URI, uri)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SOURCE, "动画城")
                putExtra(PlayerActivity.EXTRA_MIME_TYPE, mime)
                putExtra(PlayerActivity.EXTRA_HTTP_USER_AGENT, WEB_PARSE_USER_AGENT)
                putExtra(NewMainActivity.EXTRA_OPEN_PAGE_ID, pageId)
            })
        }
    }

    // ---------------- 工具 ----------------

    private fun showStatus(head: String, detail: String, success: Boolean? = true, spinner: Boolean = false) {
        val ctx = context
        val (bg, stroke) = when (success) {
            true -> CartoonDesign.Palette.BADGE_SUCCESS_BG to Color.argb(170, 72, 186, 128)
            false -> CartoonDesign.Palette.BADGE_WARN_BG to Color.argb(200, 220, 156, 68)
            null -> CartoonDesign.Palette.BADGE_INFO_BG to Color.argb(180, 130, 160, 230)
        }
        val (fg, textAccent) = when (success) {
            true -> CartoonDesign.Palette.BADGE_SUCCESS_FG to Color.argb(220, 160, 250, 192)
            false -> CartoonDesign.Palette.BADGE_WARN_FG to Color.argb(230, 255, 224, 180)
            null -> CartoonDesign.Palette.BADGE_INFO_FG to Color.argb(230, 210, 226, 255)
        }
        statusChip.background = CartoonDesign.capsuleBadge(ctx, bg, stroke)
        statusChip.setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
        val prefixTxt = android.text.SpannableStringBuilder().apply {
            val headSpan = android.text.style.ForegroundColorSpan(textAccent)
            val headT = "$head  · "
            append(headT)
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
            setSpan(headSpan, 0, headT.length, 0)
            append(detail)
        }
        statusChip.text = prefixTxt
        val actions = (reparseButton.parent as? LinearLayout) ?: return
        val spinnerView = actions.findViewWithTag<View>("spinner") ?: return
        spinnerView.visibility = if (spinner) View.VISIBLE else View.GONE
    }

    private fun loadCover(url: String, into: ImageView) {
        into.setImageResource(R.drawable.ic_thumb_default)
        if (url.isBlank()) return
        scope.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000; readTimeout = 8000
                        setRequestProperty("User-Agent", WEB_PARSE_USER_AGENT)
                    }
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp != null) into.setImageBitmap(bmp)
        }
    }

    /** 主 CTA：琥珀底液态玻璃卡（胶囊圆角 60dp / TintMode.ACCENT），焦点态自动升级描边和高光。 */
    private fun primaryButton(text: String, onClick: () -> Unit): TextView {
        val accent = CartoonDesign.Palette.ACCENT
        val cornerDp = 60
        return TextView(context).apply {
            this.text = text
            textSize = CartoonDesign.Type.TITLE_SM
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            val ph = CartoonDesign.dp(context, 10); val pv = CartoonDesign.dp(context, 6)
            setPadding(ph, pv, ph, pv)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = CartoonDesign.dp(context, cornerDp).toFloat()
                setStroke(Math.max(1, CartoonDesign.dp(context, 1)), Color.argb(198, 198, 214, 230))
                setColor(Color.TRANSPARENT)
            }
            setOnClickListener { onClick() }
            setOnKeyListener { _, k, e ->
                if (e.action == KeyEvent.ACTION_DOWN &&
                    (k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_DPAD_CENTER)) {
                    onClick(); true
                } else false
            }
            setOnFocusChangeListener { _, has ->
                val bg = background as? GradientDrawable
                bg?.setStroke(
                    CartoonDesign.dp(context, if (has) 3 else 1),
                    if (has) accent else Color.argb(198, 198, 214, 230)
                )
                BoundaryFocusHandler.cancelShake(this@apply)
                if (has) FocusFxHelper.applyFocusFxState(
                    this@apply, true, cornerRadiusDp = cornerDp
                ) else foreground = null
            }
        }
    }

    private fun inferMimeType(uri: String): String {
        val lower = uri.substringBefore('#').substringBefore('?').lowercase(Locale.US)
        return when {
            lower.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            lower.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            lower.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            else -> ""
        }
    }

    // ---------------- 子组件 ----------------

    private data class EpisodeItem(val index: Int, val name: String, val playPageUrl: String)

    private class EpisodeDiff : DiffUtil.ItemCallback<EpisodeItem>() {
        override fun areItemsTheSame(a: EpisodeItem, b: EpisodeItem) = a.index == b.index
        override fun areContentsTheSame(a: EpisodeItem, b: EpisodeItem) =
            a.name == b.name && a.playPageUrl == b.playPageUrl
    }

    private inner class EpisodeAdapter : ListAdapter<EpisodeItem, EpVH>(EpisodeDiff()) {
        fun submit(list: List<EpisodeItem>) = submitList(list)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpVH {
            val spanCount = 8
            val hGap = CartoonDesign.dp(parent.context, 10)
            val rvPadH = parent.paddingLeft + parent.paddingRight
            val totalGap = hGap * (spanCount - 1)
            val cellW = ((parent.width - rvPadH - totalGap).toFloat() / spanCount).toInt()
                .coerceAtLeast(CartoonDesign.dp(parent.context, 64))
            val cellH = CartoonDesign.dp(parent.context, 44)
            val ctx = parent.context
            val (defaultStrokePx, defaultStrokeColor) = ThemeManager.strokeFor(ctx, false)
            val outer = FrameLayout(ctx).apply {
                isFocusable = true; isClickable = true
                clipChildren = false; clipToPadding = false
                layoutParams = RecyclerView.LayoutParams(cellW, cellH)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = CartoonDesign.dp(ctx, CartoonDesign.Radius.SM.dp).toFloat()
                    setStroke(Math.max(1, CartoonDesign.dp(ctx, defaultStrokePx)), defaultStrokeColor)
                    setColor(Color.TRANSPARENT)
                }
                // RecyclerView 内的全局焦点态：关闭向上最多 3 层父容器 clip，保证 scale / translationZ 发光不被裁切。
                FocusFxHelper.disableClippingUp(this, maxDepth = 3)
            }
            val txt = TextView(ctx).apply {
                textSize = CartoonDesign.Type.TITLE_SM
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(CartoonDesign.Palette.TEXT_SECONDARY)
                gravity = Gravity.CENTER
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            outer.addView(txt, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            return EpVH(outer, txt)
        }

        override fun onBindViewHolder(h: EpVH, pos: Int) {
            val item = getItem(pos) ?: return
            h.bind(item)
        }
    }

    private inner class EpVH(private val outer: FrameLayout, private val label: TextView) :
        RecyclerView.ViewHolder(outer) {
        private var cur: EpisodeItem? = null

        /** 根据状态重绘剧集卡片背景：透明底 + 银白/暖黄描边；失焦态区分"当前/上次播放"与普通态。 */
        private fun applyBg(ctx: Context, focused: Boolean) {
            val (strokePxDp, strokeColor) = ThemeManager.strokeFor(ctx, focused)
            val baseStrokePx = Math.max(if (focused) CartoonDesign.dp(ctx, strokePxDp) else CartoonDesign.dp(ctx, strokePxDp), 1)
            val bgStroke = when {
                focused -> strokeColor
                isCurrentOrLastPlayed(cur) -> {
                    val accent = ThemeManager.accentColor(ctx)
                    Color.argb(192, Color.red(accent), Color.green(accent), Color.blue(accent))
                }
                else -> strokeColor
            }
            val rPx = CartoonDesign.dp(ctx, CartoonDesign.Radius.SM.dp).toFloat()
            outer.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = rPx
                setStroke(baseStrokePx, bgStroke)
                setColor(Color.TRANSPARENT)
            }
        }

        init {
            outer.setOnFocusChangeListener { _, has ->
                val ctx = outer.context
                applyBg(ctx, has)
                // 全局焦点 fx：两种状态都同步，保证 scale / translationZ 完整回退（避免失焦后仍抬升/放大）
                FocusFxHelper.applyFocusFxState(
                    outer, has,
                    scale = FocusFxHelper.DEFAULT_SCALE,
                    cornerRadiusDp = CartoonDesign.Radius.SM.dp,
                    elevationDp = FocusFxHelper.DEFAULT_ELEVATION_DP,
                )
                if (has) {
                    label.setTextColor(CartoonDesign.Palette.TEXT_PRIMARY)
                } else {
                    BoundaryFocusHandler.cancelShake(outer)
                    label.setTextColor(
                        if (isCurrentOrLastPlayed(cur)) CartoonDesign.Palette.TEXT_PRIMARY
                        else CartoonDesign.Palette.TEXT_SECONDARY
                    )
                }
            }
            outer.setOnClickListener { cur?.let { onEpisodeSelected(it); playEpisode(it) } }
            outer.setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                    cur?.let { onEpisodeSelected(it); playEpisode(it) }; true
                } else false
            }
        }
        fun bind(item: EpisodeItem) {
            cur = item
            label.text = item.name
            val ctx = itemView.context
            if (!outer.hasFocus()) {
                applyBg(ctx, false)
                label.setTextColor(
                    if (isCurrentOrLastPlayed(item)) CartoonDesign.Palette.TEXT_PRIMARY
                    else CartoonDesign.Palette.TEXT_SECONDARY
                )
            }
            // 绑定同步一次焦点 fx 状态，避免 VH 复用时残留放大 / 抬升
            FocusFxHelper.applyFocusFxState(
                outer, outer.hasFocus(),
                scale = FocusFxHelper.DEFAULT_SCALE,
                cornerRadiusDp = CartoonDesign.Radius.SM.dp,
                elevationDp = FocusFxHelper.DEFAULT_ELEVATION_DP,
            )
        }
    }

    private fun isCurrentOrLastPlayed(item: EpisodeItem?): Boolean {
        if (item == null) return false
        val srcName = sources.getOrNull(sourceIdx)?.name.orEmpty()
        return lastPlayedEpisodeIndex[srcName] == item.index
    }
    private val lastPlayedEpisodeIndex = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun onEpisodeSelected(item: EpisodeItem) {
        val srcName = sources.getOrNull(sourceIdx)?.name.orEmpty()
        lastPlayedEpisodeIndex[srcName] = item.index
    }

    private fun dp(v: Int): Int = CartoonDesign.dp(context, v)

    private class EpisodeItemDecoration(
        private val hGap: Int,
        private val vGap: Int,
        private val spanCount: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect, v: View, p: RecyclerView, s: RecyclerView.State
        ) {
            val pos = p.getChildAdapterPosition(v).takeIf { it != RecyclerView.NO_POSITION } ?: return
            val col = pos % spanCount
            val each = hGap / spanCount
            outRect.left = hGap - col * each
            outRect.right = (col + 1) * each
            outRect.top = if (pos < spanCount) 0 else vGap
        }
    }

    companion object {
        private const val TAG = "CartoonDetailPage"
        private const val WEB_PARSE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * 以浮层（overlay）方式打开详情页，BACK 键自动返回到动画城列表。
         */
        fun open(activity: NewMainActivity, cartoon: GiteeShareStore.SharedCartoon,
                 triggerView: View?,
                 onCloudUpdated: ((GiteeShareStore.SharedCartoon) -> Unit)? = null) {
            val page = CartoonDetailPage(activity, cartoon, onCloudUpdated)
            val trigger = triggerView ?: page
            val openRect = runCatching {
                val pos = IntArray(2); trigger.getLocationInWindow(pos); null
            }.getOrNull()
            page.alpha = 0f
            activity.pushOverlayPage(page, requestFocusAfterAttach = false)
            page.post {
                page.pivotX = page.width / 2f; page.pivotY = page.height / 2f
                page.scaleX = 0.96f; page.scaleY = 0.96f
                page.animate().cancel()
                page.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240L).withEndAction {
                    page.focusToFirstContent()
                }.start()
            }
        }
    }
}
