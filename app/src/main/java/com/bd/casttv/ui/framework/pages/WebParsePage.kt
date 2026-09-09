package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MimeTypes
import com.bd.casttv.R
import com.bd.casttv.dlna.PlaybackController
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.ui.GlowUnderlineView
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.PhoneHubHost
import com.bd.casttv.webparse.AdapterKind
import com.bd.casttv.webparse.AdapterInfo
import com.bd.casttv.webparse.AdapterSelectResult
import com.bd.casttv.webparse.AdapterSelector
import com.bd.casttv.webparse.BuiltInAdapters
import com.bd.casttv.webparse.JsonAdapterEventBus
import com.bd.casttv.webparse.ParsePageKind
import com.bd.casttv.webparse.ResourceSniffDialog
import com.bd.casttv.webparse.ParseStep
import com.bd.casttv.webparse.ParsedListMovie
import com.bd.casttv.webparse.ParsedListResult
import com.bd.casttv.webparse.ParsedMovie
import com.bd.casttv.webparse.ParsedSource
import com.bd.casttv.webparse.RuleBasedAdapter
import com.bd.casttv.webparse.WebFrameworkType
import com.bd.casttv.webparse.WebFrameworkDetector
import com.bd.casttv.sync.GiteeShareStore
import com.bd.casttv.webparse.WebParseAdapterStore
import com.bd.casttv.webparse.WebParseExtractor
import com.bd.casttv.webparse.WebParseHtml
import com.bd.casttv.webparse.WebParseListExtractor
import com.bd.casttv.webparse.WebParsePageType
import com.bd.casttv.webparse.WebParseRequest
import com.bd.casttv.webparse.WebParseRequestBus
import com.bd.casttv.webparse.WebParseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

import com.bd.casttv.webparse.FetchMode

class WebParsePage(context: Context) : BasePage(context), WebParseRequestBus.Listener, JsonAdapterEventBus.Listener {
    private companion object {
        const val TAG = "WebParsePage"
        const val WEB_PARSE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private data class WebParseContext(
        val pageType: WebParsePageType,
        val fetchMode: FetchMode,
        val originalUrl: String,
        var currentUrl: String,
        var html: String = ""
    )
    private var parseContext: WebParseContext? = null

    override val pageId = com.bd.casttv.settings.Settings.PAGE_ID_WEB_PARSE
    override val pageTitle = "网页解析播放"
    override val pageIconRes = R.drawable.ic_web_parse
    override val enablePageScroll: Boolean = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true
    override val pageStickerRes: Int get() = R.drawable.sticker_shinchan

    private val warm = Color.parseColor("#FFD700")
    private val card = Color.parseColor("#12FFFFFF")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = WebParseStore(context)
    private val adapterStore = WebParseAdapterStore(context)
    private var currentUrl: String = ""
    private var movie: ParsedMovie? = null
    private var listMovies: List<ParsedListMovie> = emptyList()
    private var listNextPageUrl: String? = null
    private var listNextPageJob: Job? = null
    private var sniffDomParseJob: Job? = null
    private var listJsonRule: String? = null
    private var listCountView: TextView? = null
    private var listGrid: LinearLayout? = null
    private var listFooter: LinearLayout? = null
    private var sniffedApi: ResourceSniffDialog.SniffedApi? = null
    private var sniffNextPageValue: Int = 0
    private var sniffExhausted: Boolean = false
    private var listBackUrl: String = ""
    private var listBackMovies: List<ParsedListMovie> = emptyList()
    private var detailFromList = false
    /** 动画城上下文：来自 [WebParseRequestBus] 的动画城请求，非空时解析成功后回写云端 cartoons。 */
    private var cartoonContext: CartoonContext? = null
    /** 最近一次解析用到的适配器信息，供「添加到动画城」判断是否要上传自定义适配器。 */
    private var lastAdapterMeta: AdapterMeta? = null
    /** 列表页解析信息快照，供列表页「收藏网站」按钮使用。 */
    private var listSiteInfo: SiteBookmarkInfo? = null
    /** 详情页解析信息快照，供详情页「收藏网站」按钮使用。 */
    private var detailSiteInfo: SiteBookmarkInfo? = null
    private var selectedSourceIndex = 0
    private var selectedEpisodeIndex = 0
    private var extractor = WebParseExtractor(context.applicationContext)
    private val listExtractor = WebParseListExtractor()
    private var parseJob: Job? = null
    private var progressJob: Job? = null
    private var phoneHubUrl: String = ""
    private var descriptionView: TextView? = null

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val contentScroll = ScrollView(context).apply {
        isFillViewport = true
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        overScrollMode = ScrollView.OVER_SCROLL_NEVER
        // 列表结果卡片自身仍允许焦点放大绘制；父层容器边界负责裁剪溢出区域。
        clipChildren = true
        clipToPadding = false
        setPadding(dp(4), dp(4), dp(4), dp(4))
        // 滚动到底部自动加载下一页（TV 端也可通过 footer 焦点触发）
        viewTreeObserver.addOnScrollChangedListener {
            if (listNextPageUrl == null) return@addOnScrollChangedListener
            if (listNextPageJob?.isActive == true) return@addOnScrollChangedListener
            val view = getChildAt(childCount - 1) ?: return@addOnScrollChangedListener
            val diff = view.bottom - (height + scrollY)
            if (diff <= dp(120)) loadNextPage()
        }
    }
    private val contentArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val emptyView = TextView(context).apply {
        text = "在手机端输入影片列表页或详情页网址开始解析"
        textSize = 20f
        setTextColor(Color.argb(220, 255, 255, 255))
        gravity = Gravity.CENTER
        isFocusable = true
        background = panelBg(false)
        setOnKeyListener { v, _, e -> boundaryKey(v, e) }
    }
    private val inputEdit: EditText = EditText(context).apply {
        hint = "请输入影片列表页或详情页网址"
        textSize = 16f
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setTextColor(Color.WHITE)
        setHintTextColor(Color.argb(170, 255, 255, 255))
        setPadding(dp(14), 0, dp(14), 0)
        background = inputBg(false)
        isFocusable = true
        isFocusableInTouchMode = true
        showSoftInputOnFocus = false
        setOnFocusChangeListener { v, has ->
            background = inputBg(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
        }
        setOnClickListener { selectAllAndShowKeyboard() }
        setOnEditorActionListener { _, _, _ -> showPageTypeDialogFromInput(); true }
        setOnKeyListener { v, keyCode, e ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (e.action == KeyEvent.ACTION_UP) selectAllAndShowKeyboard()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        if (backToListButton.visibility == View.VISIBLE) backToListButton.requestFocus() else scanButton.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        parseButton.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                else -> boundaryKey(v, e)
            }
        }
    }
    private val inputBox: FrameLayout = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        addView(inputEdit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private val backToListButton = backToListButton { returnToListResult() }.apply {
        visibility = View.GONE
        contentDescription = "返回上一级"
    }
    private val scanButton = dialogButton("扫码") { WebParseQrDialog(context, phoneHubUrl.ifBlank { buildPhoneHubUrl(PhoneHubHost.port()) }).show() }
    private val parseButton = dialogButton("立即解析") { showPageTypeDialogFromInput() }
    private val historyButton = iconButton(R.drawable.ic_dock_favorite) {
        WebParseHistoryDialog(context) { history ->
            val url = history.url
            inputEdit.setText(url)
            inputEdit.setSelection(inputEdit.text?.length ?: 0)
            inputEdit.requestFocus()
            when (history.pageType.trim().lowercase()) {
                "list" -> startParseList(url, FetchMode.from(history.fetchMode))
                "detail" -> startParse(url, fetchMode = FetchMode.from(history.fetchMode))
                else -> showPageTypeDialogForUrl(url, parseButton)
            }
        }.show()
    }.apply {
        contentDescription = "收藏"
    }
    private val adapterSettingsButton = iconButton(R.drawable.ic_settings_tv) {
        WebParseAdapterSettingsDialog(
            context = context,
            currentUrlProvider = { inputEdit.text?.toString()?.trim().orEmpty().ifBlank { currentUrl } },
            uploadPageUrlProvider = { buildJsonUploadUrl(PhoneHubHost.port()) },
            onUseRule = { fileName ->
                val url = inputEdit.text?.toString()?.trim().orEmpty().ifBlank { currentUrl }
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) startParseWithRule(url, fileName)
            }
        ).show()
    }
    private val saveButton = dialogButton("保存到合集") { movie?.let { WebParseSaveDialog(context, it) { url -> extractor.resolve(url) }.show() } }
    private val addToCartoonButton = dialogButton("添加到动画城") { onAddToCartoon() }
    private val detailSniffButton = dialogButton("资源嗅探") { showResourceSniffDialog(ResourceSniffDialog.Entry.DETAIL) }
    private val jsonButton = dialogButton("JSON解析") { showJsonAdapterDialog() }
    private val listSniffButton = dialogButton("资源嗅探") { showResourceSniffDialog(ResourceSniffDialog.Entry.LIST) }
    private val listJsonButton = dialogButton("JSON 解析") { showListJsonAdapterDialog() }
    private val bookmarkButton = dialogButton("收藏网站") { detailSiteInfo?.let { showBookmarkConfirmDialog(it) } }
    private val listBookmarkButton = dialogButton("收藏网站") { listSiteInfo?.let { showBookmarkConfirmDialog(it) } }
    private val bottomButtons = LinearLayout(context).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        visibility = View.GONE
        addView(detailSniffButton, LinearLayout.LayoutParams(dp(118), dp(42)).apply { marginEnd = dp(10) })
        addView(jsonButton, LinearLayout.LayoutParams(dp(118), dp(42)).apply { marginEnd = dp(10) })
        addView(addToCartoonButton, LinearLayout.LayoutParams(dp(150), dp(42)).apply { marginEnd = dp(10) })
        addView(saveButton, LinearLayout.LayoutParams(dp(132), dp(42)).apply { marginEnd = dp(10) })
        addView(bookmarkButton, LinearLayout.LayoutParams(dp(132), dp(42)))
    }

    init {
        buildLayout()
        WebParseRequestBus.addListener(this)
        JsonAdapterEventBus.addListener(this)
        startPhoneHubAndUpdateInputArea()
    }

    override fun onDetachedFromWindow() {
        WebParseRequestBus.removeListener(this)
        JsonAdapterEventBus.removeListener(this)
        parseJob?.cancel()
        progressJob?.cancel()
        sniffDomParseJob?.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    override fun focusToFirstContent(): Boolean {
        val ok = scanButton.requestFocus()
        if (ok) onFocusEnterContent()
        return ok
    }

    override fun onWebParseUrl(url: String) {
        onWebParseRequest(WebParseRequest(url, WebParsePageType.DETAIL))
    }

    override fun onWebParseRequest(request: WebParseRequest) {
        val url = request.url
        if (url.isBlank()) return
        inputEdit.setText(url)
        inputEdit.setSelection(inputEdit.text?.length ?: 0)
        // 动画城上下文：携带 adapterId/adapterName 供解析成功后回写云端 cartoons。
        cartoonContext = request.cartoonId?.let {
            CartoonContext(it, url, request.adapterId, request.adapterName)
        }
        when (request.pageType) {
            WebParsePageType.LIST -> startParseList(url, request.fetchMode)
            WebParsePageType.DETAIL -> startParse(url, fetchMode = request.fetchMode)
        }
    }

    /** 动画城解析上下文，解析成功后据此全量覆盖回写云端 cartoons（集数等）。 */
    private data class CartoonContext(
        val cartoonId: String,
        val detailUrl: String,
        val adapterId: String?,
        val adapterName: String?
    )

    /** 最近一次详情解析的适配器快照，用于「添加到动画城」时决定是否上传规则。 */
    private data class AdapterMeta(
        val adapterId: String,
        val adapterName: String,
        val kind: AdapterKind,
        val ruleFileName: String? = null,
        val host: String = "",
        val frameworkType: WebFrameworkType = WebFrameworkType.UNKNOWN
    ) {
        val isCustomJson: Boolean get() = kind == AdapterKind.CUSTOM_JSON
    }

    /** 「收藏网站」按钮所需的解析信息快照。 */
    private data class SiteBookmarkInfo(
        val title: String,
        val url: String,
        val pageType: String,
        val siteTitle: String,
        val frameworkType: String,
        val adapterName: String,
        val adapterId: String
    )

    /**
     * 解析成功后回写云端 cartoons：以 detailUrl 为主键 upsert，更新 title/cover/集数。
     * 仅当 cartoonContext 非空（来自动画城请求）时触发，回写后清空上下文，避免后续手动解析误回写。
     */
    private fun maybeWritebackCartoon(parsed: ParsedMovie) {
        val ctx = cartoonContext ?: return
        cartoonContext = null
        val episodeCount = parsed.sources.sumOf { it.episodes.size }
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    GiteeShareStore.upsertCartoon(
                        context,
                        title = parsed.title,
                        detailUrl = ctx.detailUrl,
                        cover = parsed.coverUrl,
                        globalAdapterId = ctx.adapterId,
                        adapterName = ctx.adapterName.orEmpty(),
                        episodeCount = episodeCount,
                        fetchMode = parseContext?.fetchMode?.name ?: "HTTP"
                    )
                }.onFailure { Log.w(TAG, "cartoon writeback failed: ${it.message}") }
            }
        }
    }

    /** 「添加到动画城」按钮：仅在解析成功后可用。 */
    private fun onAddToCartoon() {
        val data = movie ?: run { toast("先解析出影片详情后才能添加"); return }
        if (currentUrl.isBlank() || !currentUrl.startsWith("http", true)) {
            toast("当前网址无效"); return
        }
        val m = lastAdapterMeta
        AddToCartoonDialog(
            context = context,
            title = data.title,
            coverUrl = data.coverUrl,
            detailUrl = currentUrl,
            episodeCount = data.sources.sumOf { it.episodes.size },
            adapterIsCustom = m?.isCustomJson == true,
            adapterId = m?.adapterId.orEmpty(),
            adapterName = m?.adapterName.orEmpty(),
            adapterRuleFileName = m?.ruleFileName,
            adapterHost = m?.host.orEmpty(),
            adapterFramework = m?.frameworkType ?: WebFrameworkType.UNKNOWN,
            fetchMode = parseContext?.fetchMode?.name ?: "HTTP"
        ).show()
    }

    override fun onJsonAdapterImported(fileName: String, pageKind: ParsePageKind) {
        val url = inputEdit.text?.toString()?.trim().orEmpty().ifBlank { currentUrl }
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            toast("JSON 规则导入成功，正在重新解析")
            when (pageKind) {
                ParsePageKind.LIST -> startParseListWithJsonRule(url, fileName)
                ParsePageKind.DETAIL -> startParseWithRule(url, fileName)
            }
        } else {
            toast("JSON 规则导入成功，请先输入网址")
        }
    }

    private fun buildLayout() {
        root.setPadding(dp(10), dp(8), dp(10), dp(8))
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = panelBg(false)
            clipChildren = false
            clipToPadding = false
            addView(scanButton, LinearLayout.LayoutParams(dp(86), dp(46)).apply { marginEnd = dp(12) })
            addView(backToListButton, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) })
            addView(inputBox, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(12) })
            addView(parseButton, LinearLayout.LayoutParams(dp(122), dp(46)).apply { marginEnd = dp(12) })
            addView(historyButton, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(10) })
            addView(adapterSettingsButton, LinearLayout.LayoutParams(dp(46), dp(46)))
        }
        root.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)))
        root.addView(FrameLayout(context).apply {
            // 结果区域最外层裁剪，避免列表卡片焦点放大溢出到输入区或页面其他区域。
            clipChildren = true
            clipToPadding = true
            val resultPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                // 结果面板作为滚动容器父层也启用裁剪；内部列表/卡片仍保持不裁剪，保证焦点态边框完整。
                clipChildren = true
                clipToPadding = true
                contentScroll.addView(contentArea, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(contentScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(bottomButtons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(8) })
            }
            addView(resultPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        contentContainer.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        render()
    }

    private fun startPhoneHubAndUpdateInputArea() {
        phoneHubUrl = buildPhoneHubUrl(PhoneHubHost.port())
        PhoneHubHost.startAsync(context.applicationContext) { phoneHubUrl = buildPhoneHubUrl(PhoneHubHost.port()) }
    }

    private fun buildPhoneHubUrl(port: Int): String {
        val ip = localIp()
        return if (port > 0 && ip.contains('.')) "http://$ip:$port/parse" else ""
    }

    private fun buildJsonUploadUrl(port: Int): String {
        val ip = localIp()
        return if (port > 0 && ip.contains('.')) "http://$ip:$port/upload-json-adapter" else ""
    }

    private fun selectAllAndShowKeyboard() {
        if (!inputEdit.text.isNullOrEmpty()) inputEdit.selectAll()
        showKeyboard()
    }

    private fun showKeyboard() {
        inputEdit.postDelayed({
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(inputEdit, InputMethodManager.SHOW_IMPLICIT)
        }, 120L)
    }

    private fun showPageTypeDialogFromInput() {
        val url = normalizedInputUrl() ?: return
        showPageTypeDialogForUrl(url, parseButton)
    }

    private fun showPageTypeDialogForUrl(url: String, returnFocusView: View?) {
        WebParsePageTypeDialog(
            context = context,
            returnFocusView = returnFocusView,
            onListPage = { mode -> startParseList(url, mode) },
            onDetailPage = { mode -> startParse(url, fetchMode = mode) }
        ).show()
    }

    private fun returnToListResult() {
        val items = listBackMovies
        if (items.isEmpty()) {
            toast("暂无可返回的列表结果")
            backToListButton.visibility = View.GONE
            return
        }
        parseJob?.cancel()
        progressJob?.cancel()
        detailFromList = false
        movie = null
        currentUrl = listBackUrl
        listMovies = items
        if (listBackUrl.isNotBlank()) {
            inputEdit.setText(listBackUrl)
            inputEdit.setSelection(inputEdit.text?.length ?: 0)
        }
        renderList(items)
    }

    private fun startParseFromList(item: ParsedListMovie) {
        listBackUrl = currentUrl
        listBackMovies = listMovies
        detailFromList = true
        startParse(item.detailUrl, fromList = true, fetchMode = parseContext?.fetchMode ?: FetchMode.HTTP)
    }

    private fun startParseFromInput() {
        val url = normalizedInputUrl() ?: return
        startParse(url)
    }

    private fun normalizedInputUrl(): String? {
        val url = inputEdit.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            toast("请先输入影片列表页或详情页网址")
            inputEdit.requestFocus()
            return null
        }
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            toast("请输入 http/https 开头的网址")
            inputEdit.requestFocus()
            return null
        }
        return url
    }

    private fun startParseList(url: String, fetchMode: FetchMode = FetchMode.HTTP) {
        detailFromList = false
        backToListButton.visibility = View.GONE
        currentUrl = url
        movie = null
        listMovies = emptyList()
        listNextPageUrl = null
        listNextPageJob?.cancel()
        listJsonRule = null
        listGrid = null
        listFooter = null
        sniffedApi = null
        sniffNextPageValue = 0
        sniffExhausted = false
        selectedSourceIndex = 0
        selectedEpisodeIndex = 0
        sniffDomParseJob?.cancel()
        parseJob?.cancel()
        progressJob?.cancel()

        parseContext = WebParseContext(WebParsePageType.LIST, fetchMode, url, url)
        if (fetchMode == FetchMode.WEBVIEW) {
            showResourceSniffDialog(ResourceSniffDialog.Entry.LIST, ResourceSniffDialog.Mode.INITIAL_PARSE)
            return
        }

        val progressDialog = WebParseProgressDialog(
            context = context,
            pageKind = ParsePageKind.LIST,
            onTryJsonParse = { showListJsonAdapterDialog() },
            onCancel = { parseJob?.cancel() }
        )
        progressDialog.show()
        parseJob = scope.launch {
            try {
                progressDialog.update(ParseStep.RECEIVED)
                val preselectedAdapter = AdapterSelector.select(context.applicationContext, url, "", ParsePageKind.LIST)
                val parsedList: List<ParsedListMovie>
                val nextPageUrl: String?
                val adapterSelection: AdapterSelectResult
                val htmlForHistory: String
                if (preselectedAdapter.source == AdapterSelectResult.SelectSource.DOMAIN_BINDING &&
                    preselectedAdapter.adapterInfo.kind == AdapterKind.CUSTOM_JSON
                ) {
                    val ruleFileName = preselectedAdapter.adapterInfo.description
                        .ifBlank { preselectedAdapter.adapterInfo.id }
                    val jsonRule = RuleBasedAdapter.readRuleText(context.applicationContext, ruleFileName)
                    if (jsonRule.isBlank()) error("已绑定的列表页自定义适配器规则文件不存在")
                    progressDialog.update(ParseStep.FETCHING_HTML)
                    val html = if (parseContext?.fetchMode == FetchMode.WEBVIEW && parseContext?.html?.isNotBlank() == true) {
                        parseContext?.html.orEmpty()
                    } else {
                        withContext(Dispatchers.IO) { WebParseExtractor.fetchText(url) }
                    }
                    WebParseExtractor.lastParsedUrl = url
                    WebParseExtractor.lastParsedHtml = html
                    progressDialog.update(ParseStep.PARSING_INFO)
                    val result = withContext(Dispatchers.Default) { listExtractor.parseWithJsonRule(html, jsonRule, url) }
                    if (result.movies.isEmpty()) error("未按已绑定的自定义适配器解析到影片条目")
                    parsedList = result.movies
                    nextPageUrl = result.nextPageUrl
                    listJsonRule = jsonRule
                    adapterSelection = preselectedAdapter
                    htmlForHistory = html
                } else {
                    progressDialog.update(ParseStep.FETCHING_HTML)
                    val result = if (parseContext?.fetchMode == FetchMode.WEBVIEW && parseContext?.html?.isNotBlank() == true) {
                        listExtractor.parseDom(url, parseContext?.html.orEmpty())
                    } else {
                        listExtractor.extractList(url)
                    }
                    parsedList = result.movies
                    nextPageUrl = result.nextPageUrl
                    adapterSelection = selectAdapterForHistory(url, ParsePageKind.LIST)
                    progressDialog.update(ParseStep.PARSING_INFO)
                    htmlForHistory = WebParseExtractor.lastParsedHtml.orEmpty()
                }
                listMovies = parsedList
                listNextPageUrl = nextPageUrl
                listSiteInfo = SiteBookmarkInfo(
                    title = "列表页 · ${parsedList.size} 个条目",
                    url = url,
                    pageType = "list",
                    siteTitle = WebParseStore.extractSiteTitle(htmlForHistory),
                    frameworkType = frameworkDisplayName(adapterSelection),
                    adapterName = adapterSelection.adapterInfo.name,
                    adapterId = adapterSelection.adapterInfo.id
                )
                renderList(parsedList)
                progressDialog.update(ParseStep.LOADING_DONE)
                progressDialog.dismissDelayed()
            } catch (t: Throwable) {
                progressDialog.update(ParseStep.ERROR, t.message ?: "未知错误")
            }
        }
    }

    private fun startParse(url: String, fromList: Boolean = false, fetchMode: FetchMode = FetchMode.HTTP) {
        detailFromList = fromList
        backToListButton.visibility = if (fromList && listBackMovies.isNotEmpty()) View.VISIBLE else View.GONE
        currentUrl = url
        if (inputEdit.text?.toString()?.trim() != url) {
            inputEdit.setText(url)
            inputEdit.setSelection(inputEdit.text?.length ?: 0)
        }
        listMovies = emptyList()
        selectedSourceIndex = 0
        selectedEpisodeIndex = 0
        sniffDomParseJob?.cancel()
        parseJob?.cancel()

        if (fromList) {
            parseContext = parseContext?.copy(
                pageType = WebParsePageType.DETAIL,
                fetchMode = fetchMode,
                originalUrl = url,
                currentUrl = url,
                html = ""
            )
        } else {
            parseContext = WebParseContext(WebParsePageType.DETAIL, fetchMode, url, url)
        }
        if (fetchMode == FetchMode.WEBVIEW) {
            showResourceSniffDialog(ResourceSniffDialog.Entry.DETAIL, ResourceSniffDialog.Mode.INITIAL_PARSE)
            return
        }

        val progressDialog = WebParseProgressDialog(context) { parseJob?.cancel() }
        progressDialog.show()
        extractor = WebParseExtractor(context.applicationContext) { p ->
            post {
                if (p.step == ParseStep.ERROR) progressDialog.update(ParseStep.ERROR, p.error) else progressDialog.update(p.step)
            }
        }
        parseJob = scope.launch {
            try {
                val preselectedAdapter = AdapterSelector.select(context.applicationContext, url, "", ParsePageKind.DETAIL)
                val parsed: ParsedMovie
                val adapterSelection: AdapterSelectResult
                if (preselectedAdapter.source == AdapterSelectResult.SelectSource.DOMAIN_BINDING &&
                    preselectedAdapter.adapterInfo.kind == AdapterKind.CUSTOM_JSON
                ) {
                    val ruleFileName = preselectedAdapter.adapterInfo.description
                        .ifBlank { preselectedAdapter.adapterInfo.id }
                    val jsonRule = RuleBasedAdapter.readRuleText(context.applicationContext, ruleFileName)
                    if (jsonRule.isBlank()) error("已绑定的详情页自定义适配器规则文件不存在")
                    parsed = if (parseContext?.fetchMode == FetchMode.WEBVIEW && parseContext?.html?.isNotBlank() == true) {
                        extractor.extractWithHtml(url, parseContext?.html.orEmpty(), ruleFileName)
                    } else {
                        extractor.extractWithRule(url, ruleFileName)
                    }
                    adapterSelection = preselectedAdapter
                } else {
                    parsed = if (parseContext?.fetchMode == FetchMode.WEBVIEW && parseContext?.html?.isNotBlank() == true) {
                        extractor.extractWithHtml(url, parseContext?.html.orEmpty())
                    } else {
                        extractor.extract(url)
                    }
                    adapterSelection = selectAdapterForHistory(url, ParsePageKind.DETAIL)
                }
                movie = parsed
                // 记录适配器快照（CUSTOM_JSON 时附带 ruleFileName + host，便于添加到动画城时上传规则）
                val host = adapterStore.normalizeHost(url)
                val binding = if (preselectedAdapter.source == AdapterSelectResult.SelectSource.DOMAIN_BINDING &&
                    preselectedAdapter.adapterInfo.kind == AdapterKind.CUSTOM_JSON
                ) {
                    adapterStore.getBinding(ParsePageKind.DETAIL, host)
                } else null
                lastAdapterMeta = AdapterMeta(
                    adapterId = adapterSelection.adapterInfo.id,
                    adapterName = adapterSelection.adapterInfo.name,
                    kind = adapterSelection.adapterInfo.kind,
                    ruleFileName = binding?.ruleFileName,
                    host = binding?.host.orEmpty().ifBlank { host },
                    frameworkType = binding?.frameworkType ?: WebFrameworkType.UNKNOWN
                )
                detailSiteInfo = SiteBookmarkInfo(
                    title = parsed.title,
                    url = url,
                    pageType = "detail",
                    siteTitle = "",
                    frameworkType = frameworkDisplayName(adapterSelection),
                    adapterName = adapterSelection.adapterInfo.name,
                    adapterId = adapterSelection.adapterInfo.id
                )
                val progress = store.getProgress(url)
                selectedSourceIndex = progress?.sourceIndex?.coerceIn(0, parsed.sources.lastIndex.coerceAtLeast(0)) ?: 0
                selectedEpisodeIndex = progress?.episodeIndex?.coerceIn(0, (parsed.sources.getOrNull(selectedSourceIndex)?.episodes?.lastIndex ?: 0).coerceAtLeast(0)) ?: 0
                render()
                maybeWritebackCartoon(parsed)
                progressDialog.update(ParseStep.LOADING_DONE)
                progressDialog.dismissDelayed()
            } catch (t: Throwable) {
                progressDialog.update(ParseStep.ERROR, t.message ?: "未知错误")
            }
        }
    }

    private fun startParseWithRule(url: String, fileName: String) {
        currentUrl = url
        detailFromList = false
        backToListButton.visibility = View.GONE
        listMovies = emptyList()
        inputEdit.setText(url)
        inputEdit.setSelection(inputEdit.text?.length ?: 0)
        selectedSourceIndex = 0
        selectedEpisodeIndex = 0
        sniffDomParseJob?.cancel()
        parseJob?.cancel()
        val progressDialog = WebParseProgressDialog(context) { parseJob?.cancel() }
        progressDialog.show()
        extractor = WebParseExtractor(context.applicationContext) { p ->
            post { if (p.step == ParseStep.ERROR) progressDialog.update(ParseStep.ERROR, p.error) else progressDialog.update(p.step) }
        }
        parseJob = scope.launch {
            try {
                val parsed = if (parseContext?.fetchMode == FetchMode.WEBVIEW && parseContext?.html?.isNotBlank() == true) {
                    extractor.extractWithHtml(url, parseContext?.html.orEmpty(), fileName)
                } else {
                    extractor.extractWithRule(url, fileName)
                }
                val ruleName = RuleBasedAdapter.listRuleInfos(context).firstOrNull { it.fileName == fileName }?.name.orEmpty().ifBlank { fileName }
                val host = adapterStore.normalizeHost(url)
                val binding = adapterStore.getBinding(ParsePageKind.DETAIL, host)
                lastAdapterMeta = AdapterMeta(
                    adapterId = binding?.adapterId ?: fileName,
                    adapterName = ruleName,
                    kind = AdapterKind.CUSTOM_JSON,
                    ruleFileName = binding?.ruleFileName ?: fileName,
                    host = binding?.host.orEmpty().ifBlank { host },
                    frameworkType = binding?.frameworkType ?: WebFrameworkType.UNKNOWN
                )
                movie = parsed
                detailSiteInfo = SiteBookmarkInfo(
                    title = parsed.title,
                    url = url,
                    pageType = "detail",
                    siteTitle = "",
                    frameworkType = WebFrameworkType.CUSTOM.displayName,
                    adapterName = ruleName,
                    adapterId = fileName
                )
                store.saveParseHistory(
                    title = parsed.title,
                    url = url,
                    pageType = "detail",
                    siteTitle = "",
                    frameworkType = WebFrameworkType.CUSTOM.displayName,
                    adapterName = ruleName,
                    adapterId = fileName,
                    recordId = WebParseStore.generateRecordId(url, "detail"),
                    fetchMode = parseContext?.fetchMode?.name ?: "HTTP"
                )
                selectedSourceIndex = 0
                selectedEpisodeIndex = 0
                render()
                maybeWritebackCartoon(parsed)
                progressDialog.update(ParseStep.LOADING_DONE)
                progressDialog.dismissDelayed()
            } catch (t: Throwable) {
                progressDialog.update(ParseStep.ERROR, t.message ?: "未知错误")
            }
        }
    }

    private fun showJsonAdapterDialog() {
        JsonAdapterDialog(
            context = context,
            currentUrlProvider = { inputEdit.text?.toString()?.trim().orEmpty().ifBlank { currentUrl } },
            uploadPageUrlProvider = { buildJsonUploadUrl(PhoneHubHost.port()) },
            pageKindProvider = { ParsePageKind.DETAIL },
            onUseRawJson = { json -> startParseDetailWithJsonRule(json) },
            onUseRule = { fileName ->
                val url = inputEdit.text?.toString()?.trim().orEmpty().ifBlank { currentUrl }
                startParseWithRule(url, fileName)
            }
        ).show()
    }

    private fun showListJsonAdapterDialog() {
        JsonAdapterDialog(
            context = context,
            currentUrlProvider = { inputEdit.text?.toString()?.trim().orEmpty().ifBlank { currentUrl } },
            uploadPageUrlProvider = { buildJsonUploadUrl(PhoneHubHost.port()) },
            pageKindProvider = { ParsePageKind.LIST },
            onUseRawJson = { json -> startParseListWithJsonRule(json) },
            onUseRule = { }
        ).show()
    }

    private fun showBookmarkConfirmDialog(info: SiteBookmarkInfo) {
        val pageTypeLabel = if (info.pageType == "list") "列表页" else "详情页"
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(245, 28, 30, 38))
                setStroke(dp(1), Color.argb(80, 255, 215, 0))
            }
            clipChildren = false
            clipToPadding = false
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val sticker = ImageView(context).apply {
                setImageResource(R.drawable.sticker_shinchan)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2), Color.argb(180, 255, 215, 0))
                }
            }
            val stickerSize = dp(40)
            addView(sticker, LinearLayout.LayoutParams(stickerSize, stickerSize).apply { marginEnd = dp(12) })
            addView(TextView(context).apply {
                text = "收藏网站"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(warm)
            })
        }
        panel.addView(titleRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })

        fun infoRow(label: String, value: String) {
            panel.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                addView(TextView(context).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Color.argb(180, 180, 185, 195))
                    minWidth = dp(80)
                })
                addView(TextView(context).apply {
                    text = value
                    textSize = 14f
                    setTextColor(Color.argb(230, 240, 240, 245))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                })
            })
        }

        infoRow("影片标题", info.title)
        infoRow("网页类型", pageTypeLabel)
        infoRow("网址", info.url)
        if (info.siteTitle.isNotBlank()) infoRow("网站标题", info.siteTitle)
        infoRow("框架类型", info.frameworkType)
        infoRow("适配器", info.adapterName)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val cancel = dialogButton("取消") { }
        val confirm = dialogButton("确认收藏") { }
        btnRow.addView(cancel, LinearLayout.LayoutParams(dp(100), dp(42)).apply { marginEnd = dp(12) })
        btnRow.addView(confirm, LinearLayout.LayoutParams(dp(120), dp(42)))
        panel.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        val dlg = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        cancel.setOnClickListener { dlg.dismiss() }
        confirm.setOnClickListener {
            dlg.dismiss()
            store.saveParseHistory(
                title = info.title,
                url = info.url,
                pageType = info.pageType,
                siteTitle = info.siteTitle,
                frameworkType = info.frameworkType,
                adapterName = info.adapterName,
                adapterId = info.adapterId,
                recordId = WebParseStore.generateRecordId(info.url, info.pageType),
                fetchMode = parseContext?.fetchMode?.name ?: "HTTP"
            )
            toast("已收藏到解析记录")
        }
        dlg.show()
        dlg.window?.apply {
            setGravity(Gravity.CENTER)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setBackgroundColor(Color.TRANSPARENT)
            setLayout(dp(400), WindowManager.LayoutParams.WRAP_CONTENT)
            val attrs = attributes
            attrs.dimAmount = 0.32f
            attributes = attrs
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            decorView.isFocusable = true
            decorView.setOnKeyListener { _, keyCode, ev ->
                if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_DOWN) {
                    cancel.performClick(); true
                } else false
            }
            decorView.post { cancel.requestFocus() }
        }
    }

    private fun startParseDetailWithJsonRule(jsonRule: String) {
        val url = inputEdit.text?.toString()?.trim().orEmpty().ifBlank { currentUrl }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            toast("请先输入 http/https 开头的网址")
            return
        }
        val host = adapterStore.normalizeHost(url)
        if (host.isBlank()) {
            toast("无法识别当前网址域名")
            return
        }
        currentUrl = url
        detailFromList = false
        backToListButton.visibility = View.GONE
        listMovies = emptyList()
        selectedSourceIndex = 0
        selectedEpisodeIndex = 0
        sniffDomParseJob?.cancel()
        parseJob?.cancel()
        val progressDialog = WebParseProgressDialog(context) { parseJob?.cancel() }
        progressDialog.show()
        parseJob = scope.launch {
            var savedFileName: String? = null
            try {
                val info = RuleBasedAdapter.saveRule(context.applicationContext, jsonRule, host, ParsePageKind.DETAIL)
                savedFileName = info.fileName
                extractor = WebParseExtractor(context.applicationContext) { p ->
                    post { if (p.step == ParseStep.ERROR) progressDialog.update(ParseStep.ERROR, p.error) else progressDialog.update(p.step) }
                }
                val parsed = if (parseContext?.fetchMode == FetchMode.WEBVIEW && parseContext?.html?.isNotBlank() == true) {
                    extractor.extractWithHtml(url, parseContext?.html.orEmpty(), info.fileName)
                } else {
                    extractor.extractWithRule(url, info.fileName)
                }
                saveCustomAdapterBinding(url, info.name, info.fileName, ParsePageKind.DETAIL)
                movie = parsed
                detailSiteInfo = SiteBookmarkInfo(
                    title = parsed.title,
                    url = url,
                    pageType = "detail",
                    siteTitle = "",
                    frameworkType = WebFrameworkType.CUSTOM.displayName,
                    adapterName = info.name,
                    adapterId = info.fileName
                )
                store.saveParseHistory(
                    title = parsed.title,
                    url = url,
                    pageType = "detail",
                    siteTitle = "",
                    frameworkType = WebFrameworkType.CUSTOM.displayName,
                    adapterName = info.name,
                    adapterId = info.fileName,
                    recordId = WebParseStore.generateRecordId(url, "detail"),
                    fetchMode = parseContext?.fetchMode?.name ?: "HTTP"
                )
                render()
                progressDialog.update(ParseStep.LOADING_DONE)
                progressDialog.dismissDelayed()
                toast("详情页 JSON 规则已保存为自定义适配器：${info.name}")
            } catch (t: Throwable) {
                savedFileName?.let { RuleBasedAdapter.deleteRule(context.applicationContext, it) }
                val msg = t.message ?: "详情页 JSON 解析失败"
                progressDialog.update(ParseStep.ERROR, msg)
                toast(msg)
            }
        }
    }

    private fun startParseListWithJsonRule(url: String, fileName: String) {
        inputEdit.setText(url)
        inputEdit.setSelection(inputEdit.text?.length ?: 0)
        val jsonRule = RuleBasedAdapter.readRuleText(context.applicationContext, fileName)
        if (jsonRule.isBlank()) {
            toast("列表页 JSON 规则文件不存在")
            return
        }
        startParseListWithJsonRule(jsonRule)
    }

    private fun startParseListWithJsonRule(jsonRule: String) {
        val url = inputEdit.text?.toString()?.trim().orEmpty().ifBlank { currentUrl }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            toast("请先输入 http/https 开头的网址")
            return
        }
        try {
            val obj = JSONObject(jsonRule)
            if (obj.optString("type") != "list") {
                toast("JSON 校验失败：type 必须为 list")
                return
            }
            if (obj.optString("titleSelector").isBlank()) {
                toast("JSON 校验失败：titleSelector 不能为空")
                return
            }
            if (obj.optString("detailUrlSelector").isBlank()) {
                toast("JSON 校验失败：detailUrlSelector 不能为空")
                return
            }
        } catch (t: Throwable) {
            toast("JSON 格式错误，请检查内容")
            return
        }
        currentUrl = url
        detailFromList = false
        backToListButton.visibility = View.GONE
        movie = null
        listMovies = emptyList()
        listNextPageUrl = null
        listNextPageJob?.cancel()
        listJsonRule = null
        listGrid = null
        listFooter = null
        sniffedApi = null
        sniffNextPageValue = 0
        sniffExhausted = false
        selectedSourceIndex = 0
        selectedEpisodeIndex = 0
        sniffDomParseJob?.cancel()
        parseJob?.cancel()
        progressJob?.cancel()
        val progressDialog = WebParseProgressDialog(context) { parseJob?.cancel() }
        progressDialog.show()
        parseJob = scope.launch {
            try {
                progressDialog.update(ParseStep.RECEIVED)
                progressDialog.update(ParseStep.FETCHING_HTML)
                val html = if (parseContext?.fetchMode == FetchMode.WEBVIEW && parseContext?.html?.isNotBlank() == true) {
                    parseContext?.html.orEmpty()
                } else {
                    withContext(Dispatchers.IO) { WebParseExtractor.fetchText(url) }
                }
                WebParseExtractor.lastParsedUrl = url
                WebParseExtractor.lastParsedHtml = html
                progressDialog.update(ParseStep.PARSING_INFO)
                val result = withContext(Dispatchers.Default) { listExtractor.parseWithJsonRule(html, jsonRule, url) }
                val parsedList = result.movies
                if (parsedList.isEmpty()) error("未按 JSON 规则解析到影片条目")
                val host = adapterStore.normalizeHost(url)
                val info = RuleBasedAdapter.saveRule(context.applicationContext, jsonRule, host, ParsePageKind.LIST)
                saveCustomAdapterBinding(url, info.name, info.fileName, ParsePageKind.LIST)
                listMovies = parsedList
                listNextPageUrl = result.nextPageUrl
                listJsonRule = jsonRule
                listSiteInfo = SiteBookmarkInfo(
                    title = "列表页 JSON · ${parsedList.size} 个条目",
                    url = url,
                    pageType = "list",
                    siteTitle = WebParseStore.extractSiteTitle(html),
                    frameworkType = WebFrameworkType.CUSTOM.displayName,
                    adapterName = info.name,
                    adapterId = info.fileName
                )
                store.saveParseHistory(
                    title = "列表页 JSON · ${parsedList.size} 个条目",
                    url = url,
                    pageType = "list",
                    siteTitle = WebParseStore.extractSiteTitle(html),
                    frameworkType = WebFrameworkType.CUSTOM.displayName,
                    adapterName = info.name,
                    adapterId = info.fileName,
                    recordId = WebParseStore.generateRecordId(url, "list"),
                    fetchMode = parseContext?.fetchMode?.name ?: "HTTP"
                )
                renderList(parsedList)
                progressDialog.update(ParseStep.LOADING_DONE)
                progressDialog.dismissDelayed()
            } catch (t: Throwable) {
                val msg = t.message ?: "列表页 JSON 解析失败"
                progressDialog.update(ParseStep.ERROR, msg)
                toast(msg)
            }
        }
    }

    private fun selectAdapterForHistory(url: String, pageKind: ParsePageKind): AdapterSelectResult {
        val html = WebParseExtractor.lastParsedHtml.orEmpty()
        return AdapterSelector.select(context.applicationContext, url, html, pageKind)
    }

    private fun saveCustomAdapterBinding(url: String, adapterName: String, ruleFileName: String, pageKind: ParsePageKind) {
        adapterStore.forceUpdateBinding(
            WebParseAdapterStore.DomainBinding(
                pageKind = pageKind,
                host = url,
                adapterId = ruleFileName,
                adapterKind = AdapterKind.CUSTOM_JSON,
                adapterName = adapterName,
                ruleFileName = ruleFileName,
                frameworkType = WebFrameworkType.CUSTOM,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun frameworkDisplayName(selection: AdapterSelectResult): String {
        val framework = if (selection.detectedFramework == WebFrameworkType.UNKNOWN) selection.adapterInfo.frameworkType else selection.detectedFramework
        return framework.displayName
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun render() {
        contentArea.removeAllViews()
        backToListButton.visibility = if (detailFromList && listBackMovies.isNotEmpty()) View.VISIBLE else View.GONE
        descriptionView = null
        val data = movie
        emptyView.visibility = if (data == null) View.VISIBLE else View.GONE
        contentScroll.visibility = if (data == null) View.GONE else View.VISIBLE
        bottomButtons.visibility = if (data == null) View.GONE else View.VISIBLE
        contentArea.visibility = if (data == null) View.GONE else View.VISIBLE
        if (data == null) return
        Log.d(TAG, "render title=${data.title} sources=${data.sources.size} selectedSourceIndex=$selectedSourceIndex episodeCounts=${data.sources.map { it.episodes.size }}")

        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val cover = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = panelBg(false); setImageResource(R.drawable.ic_thumb_default) }
        top.addView(cover, LinearLayout.LayoutParams(dp(150), dp(210)).apply { marginEnd = dp(16) })
        loadCover(data.coverUrl, cover)

        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = panelBg(false); clipChildren = false; clipToPadding = false }
        val title = TextView(context).apply { text = data.title; textSize = 26f; typeface = Typeface.DEFAULT_BOLD; setTextColor(warm); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val meta = listOf(data.category, data.year, data.area).filter { it.isNotBlank() }.joinToString(" · ")
        info.addView(title)
        info.addView(TextView(context).apply { text = meta.ifBlank { "网页解析影片" }; textSize = 15f; setTextColor(Color.argb(220, 255, 255, 255)); setPadding(0, dp(8), 0, 0) })
        if (data.director.isNotBlank()) info.addView(infoLine("导演", data.director))
        if (data.actors.isNotBlank()) info.addView(infoLine("主演", data.actors))
        val rawDesc = data.description.ifBlank { "暂无简介" }
        var descExpanded = false
        val desc = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = panelBg(false)
            setOnFocusChangeListener { v, has ->
                background = panelBg(has)
                FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 14)
            }
            fun refresh() {
                text = if (descExpanded) "$rawDesc △收起" else collapsedDescriptionText(rawDesc)
                maxLines = if (descExpanded) Int.MAX_VALUE else 2
                ellipsize = null
            }
            refresh()
            setOnClickListener { descExpanded = !descExpanded; refresh() }
            setOnKeyListener { v, keyCode, e ->
                if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && e.action == KeyEvent.ACTION_UP) {
                    descExpanded = !descExpanded
                    refresh()
                    true
                } else {
                    boundaryKey(v, e)
                }
            }
        }
        descriptionView = desc
        info.addView(desc)
        top.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        contentArea.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (data.sources.isEmpty()) {
            contentArea.addView(emptyEpisodeView("未解析到播放线路，请尝试 JSON解析"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { topMargin = dp(12) })
        } else {
            if (data.sources.size > 1) contentArea.addView(sourceTabs(data), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(12) })
            contentArea.addView(episodeList(data), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
    }

    private fun renderList(items: List<ParsedListMovie>) {
        detailFromList = false
        backToListButton.visibility = View.GONE
        contentArea.removeAllViews()
        descriptionView = null
        movie = null
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        contentScroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        bottomButtons.visibility = View.GONE
        contentArea.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) return

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = panelBg(false)
            clipChildren = false
            clipToPadding = false
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(TextView(context).apply {
                    text = "已解析到 ${items.size} 个影片条目"
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(warm)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }.also { listCountView = it }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                detachFromParent(listSniffButton)
                addView(listSniffButton, LinearLayout.LayoutParams(dp(118), dp(36)).apply { marginStart = dp(12) })
                detachFromParent(listJsonButton)
                addView(listJsonButton, LinearLayout.LayoutParams(dp(118), dp(36)).apply { marginStart = dp(10) })
                detachFromParent(listBookmarkButton)
                addView(listBookmarkButton, LinearLayout.LayoutParams(dp(118), dp(36)).apply { marginStart = dp(10) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(context).apply {
                text = "请选择要播放的影片，确认后会进入详情页解析流程"
                textSize = 13f
                setTextColor(Color.argb(180, 255, 255, 255))
                setPadding(0, dp(4), 0, 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        contentArea.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val grid = listMovieGrid(items)
        listGrid = grid
        contentArea.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 底部加载更多区域
        val footer = listFooterView()
        listFooter = footer
        contentArea.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentScroll.post { grid.findViewWithTag<View>("list_movie_0")?.requestFocus() }
    }

    // ---- 分页加载更多 ----

    private fun listFooterView(
        loading: Boolean = false,
        hasError: Boolean = false,
        message: String? = null
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(0, dp(20), 0, dp(20))
        clipChildren = false
        clipToPadding = false
        val hasMore = listNextPageUrl != null
        val canSniff = listNextPageUrl == null && sniffedApi == null && !sniffExhausted
        val hasSniffedApi = sniffedApi != null && !sniffExhausted
        val msg = when {
            message != null -> message
            loading -> "正在加载更多…"
            hasError -> "加载失败，按确认重试"
            hasMore -> "上滑或按↓键加载更多"
            hasSniffedApi -> "上滑或按↓键加载更多"
            canSniff -> "上滑或按OK键进入资源嗅探"
            else -> "没有更多了"
        }
        val canClick = (hasMore || hasSniffedApi || canSniff) && !loading
        addView(ProgressBar(context).apply {
            isIndeterminate = true
            visibility = if (loading) View.VISIBLE else View.GONE
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { bottomMargin = dp(6) })
        addView(TextView(context).apply {
            text = msg
            textSize = 14f
            setTextColor(Color.argb(180, 255, 255, 255))
            isFocusable = canClick
            isClickable = canClick
            setOnClickListener {
                if (!canClick) return@setOnClickListener
                when {
                    hasMore && !loading -> loadNextPage()
                    hasSniffedApi && !loading -> loadNextPage()
                    canSniff -> showResourceSniffDialog(ResourceSniffDialog.Entry.LIST)
                }
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus || !canClick) return@setOnFocusChangeListener
                when {
                    hasMore && !loading -> loadNextPage()
                    hasSniffedApi && !loading -> loadNextPage()
                    canSniff -> showResourceSniffDialog(ResourceSniffDialog.Entry.LIST)
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun updateListFooter(loading: Boolean = false, hasError: Boolean = false, message: String? = null) {
        val old = listFooter ?: return
        val parent = old.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(old)
        parent.removeView(old)
        val newFooter = listFooterView(loading = loading, hasError = hasError, message = message)
        listFooter = newFooter
        parent.addView(newFooter, index)
    }

    private fun loadNextPage() {
        if (listNextPageJob?.isActive == true) return
        val url = listNextPageUrl
        val api = sniffedApi
        if (url == null && api == null) return
        updateListFooter(loading = true)
        listNextPageJob = scope.launch {
            try {
                val existing = listMovies
                var newMovies: List<ParsedListMovie>
                when {
                    // 路径1：有 nextPageUrl，走 HTML 解析
                    url != null && listJsonRule != null -> {
                        val html = withContext(Dispatchers.IO) { WebParseExtractor.fetchText(url) }
                        val result = withContext(Dispatchers.Default) { listExtractor.parseWithJsonRule(html, listJsonRule!!, url) }
                        newMovies = result.movies.filter { newItem ->
                            existing.none { it.detailUrl == newItem.detailUrl }
                        }
                        listNextPageUrl = result.nextPageUrl
                    }
                    url != null -> {
                        val result = listExtractor.extractList(url)
                        newMovies = result.movies.filter { newItem ->
                            existing.none { it.detailUrl == newItem.detailUrl }
                        }
                        listNextPageUrl = result.nextPageUrl
                    }
                    // 路径2：用嗅探到的接口加载
                    api != null -> {
                        val (raw, nextVal) = loadFromSniffedApi(api, existing.size)
                        newMovies = raw.filter { newItem ->
                            existing.none { it.detailUrl == newItem.detailUrl }
                        }
                        if (newMovies.isNotEmpty()) {
                            sniffNextPageValue = nextVal
                        } else {
                            sniffExhausted = true
                        }
                    }
                    else -> {
                        newMovies = emptyList()
                    }
                }
                if (newMovies.isEmpty()) {
                    listNextPageUrl = null
                    updateListFooter(message = "没有更多了")
                    return@launch
                }
                val merged = existing + newMovies
                listMovies = merged
                // 重建 grid（保留滚动位置）
                val scrollY = contentScroll.scrollY
                val oldGrid = listGrid
                val oldFooter = listFooter
                if (oldGrid != null) contentArea.removeView(oldGrid)
                if (oldFooter != null) contentArea.removeView(oldFooter)
                val newGrid = listMovieGrid(merged)
                listGrid = newGrid
                val footerView = listFooterView()
                listFooter = footerView
                contentArea.addView(newGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                contentArea.addView(footerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                contentScroll.scrollTo(0, scrollY)
                contentScroll.post { newGrid.findViewWithTag<View>("list_movie_${existing.size}")?.requestFocus() }
            } catch (t: Throwable) {
                val msg = t.message ?: "网络异常"
                val isNoMore = msg.contains("未从列表页提取到影片条目")
                if (isNoMore) {
                    listNextPageUrl = null
                    updateListFooter(message = "没有更多了")
                } else {
                    updateListFooter(hasError = true, message = "加载失败：$msg")
                }
            }
        }
    }

    // ---- 资源嗅探 ----

    private fun showResourceSniffDialog(entry: ResourceSniffDialog.Entry, mode: ResourceSniffDialog.Mode = ResourceSniffDialog.Mode.SNIFF) {
        val expectedEntry = if (movie != null) ResourceSniffDialog.Entry.DETAIL else ResourceSniffDialog.Entry.LIST
        if (entry != expectedEntry && mode == ResourceSniffDialog.Mode.SNIFF) {
            toast("当前解析结果已变化，请重新打开资源嗅探")
            return
        }
        ResourceSniffDialog(
            context = context,
            pageUrl = currentUrl,
            entry = entry,
            mode = mode,
            onDomSnapshot = { domUrl, html, reportNewCount ->
                if (mode == ResourceSniffDialog.Mode.INITIAL_PARSE) {
                    parseContext?.currentUrl = domUrl
                    parseContext?.html = html
                }
                parseSniffedDom(entry, domUrl, html, reportNewCount)
            },
            onSniffed = { api ->
                if (entry == ResourceSniffDialog.Entry.LIST) {
                    sniffedApi = api
                    sniffNextPageValue = api.paging?.startValue ?: 0
                    sniffExhausted = false
                    updateListFooter()
                }
            },
            onClosed = {
                sniffDomParseJob?.cancel()
                sniffDomParseJob = null
            }
        ).show()
    }

    /** DOM 变化只负责触发；新增内容统一交给当前页面对应的适配器解析。 */
    private fun parseSniffedDom(
        entry: ResourceSniffDialog.Entry,
        domUrl: String,
        html: String,
        reportNewCount: (Int) -> Unit
    ) {
        // DOM 持续变化时不能取消正在进行的重解析，否则重页面上的解析任务会一直被饿死。
        // 串行等待上一轮完成，确保每次已上报的稳定快照最终都能进入适配器。
        val previousJob = sniffDomParseJob
        sniffDomParseJob = scope.launch {
            previousJob?.join()

            val isInitial = parseContext?.fetchMode == FetchMode.WEBVIEW &&
                    ((entry == ResourceSniffDialog.Entry.LIST && listGrid == null) ||
                            (entry == ResourceSniffDialog.Entry.DETAIL && movie == null))
            var progressDialog: WebParseProgressDialog? = null

            if (isInitial) {
                progressDialog = WebParseProgressDialog(
                    context = context,
                    pageKind = if (entry == ResourceSniffDialog.Entry.LIST) ParsePageKind.LIST else ParsePageKind.DETAIL,
                    onTryJsonParse = if (entry == ResourceSniffDialog.Entry.LIST) ({ showListJsonAdapterDialog() }) else null,
                    onCancel = { sniffDomParseJob?.cancel() }
                )
                progressDialog.show()
                // 立即更新到解析信息阶段，使得前两个节点直接显示成功
                progressDialog.update(ParseStep.PARSING_INFO)
            }

            val count = runCatching {
                val pageKind = if (entry == ResourceSniffDialog.Entry.LIST) ParsePageKind.LIST else ParsePageKind.DETAIL
                val binding = adapterStore.getBinding(pageKind, domUrl)
                    ?: parseContext?.originalUrl?.let { adapterStore.getBinding(pageKind, it) }

                val preselectedAdapter = if (binding != null) {
                    val adapter = BuiltInAdapters.findById(binding.adapterId) ?: AdapterInfo(
                        id = binding.adapterId,
                        name = binding.adapterName.ifBlank { binding.ruleFileName.ifBlank { "自定义解析" } },
                        kind = binding.adapterKind,
                        frameworkType = binding.frameworkType,
                        supportedPageKinds = setOf(pageKind),
                        priority = 100,
                        description = binding.ruleFileName
                    )
                    AdapterSelectResult(
                        adapterInfo = adapter,
                        detectedFramework = binding.frameworkType,
                        source = AdapterSelectResult.SelectSource.DOMAIN_BINDING,
                        confidence = 1f
                    )
                } else {
                    AdapterSelector.select(context.applicationContext, domUrl, html, pageKind)
                }

                when (entry) {
                    ResourceSniffDialog.Entry.LIST -> {
                        var usedBoundAdapter = false
                        val parsedResult = withContext(Dispatchers.Default) {
                            var ruleResult: ParsedListResult? = null
                            if (preselectedAdapter.source == AdapterSelectResult.SelectSource.DOMAIN_BINDING) {
                                if (preselectedAdapter.adapterInfo.kind == AdapterKind.CUSTOM_JSON) {
                                    val ruleFileName = preselectedAdapter.adapterInfo.description.ifBlank { preselectedAdapter.adapterInfo.id }
                                    val rule = RuleBasedAdapter.readRuleText(context.applicationContext, ruleFileName)
                                    if (rule.isNotBlank()) {
                                        try {
                                            val res = listExtractor.parseWithJsonRule(html, rule, domUrl)
                                            if (res.movies.isNotEmpty()) {
                                                if (isInitial) listJsonRule = rule
                                                ruleResult = res
                                                usedBoundAdapter = true
                                            } else {
                                                Log.w(TAG, "已绑定的列表页自定义适配器解析结果为空，文件名: $ruleFileName")
                                            }
                                        } catch (t: Throwable) {
                                            Log.w(TAG, "已绑定的列表页自定义适配器解析抛错: ${t.message}, 文件名: $ruleFileName")
                                        }
                                    } else {
                                        Log.w(TAG, "已绑定的列表页自定义适配器规则文件不存在: $ruleFileName")
                                    }
                                } else if (preselectedAdapter.adapterInfo.kind == AdapterKind.BUILT_IN) {
                                    usedBoundAdapter = true
                                }
                            }
                            ruleResult ?: listExtractor.parseDom(domUrl, html)
                        }

                        if (isInitial) {
                            // INITIAL_PARSE 路径：执行首次完整列表渲染
                            listMovies = parsedResult.movies
                            if (listMovies.isEmpty()) error("未从 DOM 快照中解析到影片条目")
                            listNextPageUrl = parsedResult.nextPageUrl

                            // 确定实际使用的适配器元信息
                            val actualAdapter = if (usedBoundAdapter) {
                                preselectedAdapter
                            } else {
                                selectAdapterForHistory(domUrl, ParsePageKind.LIST)
                            }

                            listSiteInfo = SiteBookmarkInfo(
                                title = "列表页(WV) · ${listMovies.size} 个条目",
                                url = domUrl,
                                pageType = "list",
                                siteTitle = WebParseStore.extractSiteTitle(html),
                                frameworkType = frameworkDisplayName(actualAdapter),
                                adapterName = actualAdapter.adapterInfo.name,
                                adapterId = actualAdapter.adapterInfo.id
                            )
                            // WebView 模式不自动保存到收藏，用户可通过「收藏网站」按钮手动收藏
                            renderList(listMovies)
                            1
                        } else {
                            appendSniffedListMovies(parsedResult.movies)
                        }
                    }
                    ResourceSniffDialog.Entry.DETAIL -> {
                        if (isInitial) {
                            extractor = WebParseExtractor(context.applicationContext)
                            val detailParsed: ParsedMovie = if (preselectedAdapter.source == AdapterSelectResult.SelectSource.DOMAIN_BINDING) {
                                if (preselectedAdapter.adapterInfo.kind == AdapterKind.CUSTOM_JSON) {
                                    val ruleFileName = preselectedAdapter.adapterInfo.description.ifBlank { preselectedAdapter.adapterInfo.id }
                                    try {
                                        extractor.extractWithHtml(domUrl, html, ruleFileName)
                                    } catch (t: Throwable) {
                                        Log.w(TAG, "绑定自定义适配器解析失败，回退自动识别: ${t.message}")
                                        extractor.extractWithHtml(domUrl, html)
                                    }
                                } else {
                                    // 强制使用内置绑定适配器
                                    extractor.extractWithHtml(domUrl, html, adapterId = preselectedAdapter.adapterInfo.id)
                                }
                            } else {
                                extractor.extractWithHtml(domUrl, html)
                            }

                            movie = detailParsed
                            val actualAdapter = extractor.lastAdapter
                            val usedBoundAdapter = extractor.lastUsedRequestedAdapter
                            val host = adapterStore.normalizeHost(domUrl)

                            val adapterForMeta = if (usedBoundAdapter) {
                                preselectedAdapter.adapterInfo
                            } else {
                                val detected = WebFrameworkDetector.detect(html)
                                BuiltInAdapters.all.firstOrNull { it.frameworkType == detected && it.supportedPageKinds.contains(ParsePageKind.DETAIL) }
                                    ?: BuiltInAdapters.findById(BuiltInAdapters.ID_DETAIL_GENERIC)!!
                            }

                            lastAdapterMeta = if (usedBoundAdapter) {
                                val b = binding ?: adapterStore.getBinding(ParsePageKind.DETAIL, host)
                                AdapterMeta(
                                    adapterId = adapterForMeta.id,
                                    adapterName = adapterForMeta.name,
                                    kind = adapterForMeta.kind,
                                    ruleFileName = b?.ruleFileName,
                                    host = b?.host.orEmpty().ifBlank { host },
                                    frameworkType = b?.frameworkType ?: preselectedAdapter.detectedFramework
                                )
                            } else {
                                AdapterMeta(
                                    adapterId = actualAdapter?.let { it.javaClass.simpleName } ?: adapterForMeta.id,
                                    adapterName = actualAdapter?.let { it.javaClass.simpleName } ?: adapterForMeta.name,
                                    kind = AdapterKind.BUILT_IN,
                                    host = host,
                                    frameworkType = WebFrameworkDetector.detect(html)
                                )
                            }

                            detailSiteInfo = SiteBookmarkInfo(
                                title = detailParsed.title,
                                url = domUrl,
                                pageType = "detail",
                                siteTitle = "",
                                frameworkType = if (usedBoundAdapter) adapterForMeta.frameworkType.displayName else (actualAdapter?.let { "自动识别" } ?: "通用"),
                                adapterName = if (usedBoundAdapter) adapterForMeta.name else (actualAdapter?.javaClass?.simpleName ?: "Generic"),
                                adapterId = if (usedBoundAdapter) adapterForMeta.id else (actualAdapter?.javaClass?.simpleName ?: "generic")
                            )
                            // WebView 模式不自动保存到收藏，用户可通过「收藏网站」按钮手动收藏
                            render()
                            maybeWritebackCartoon(detailParsed)
                            1
                        } else {
                            val adapter = extractor.lastAdapter ?: error("详情页适配器不可用，请先完成详情解析")
                            val parsed = withContext(Dispatchers.Default) { adapter.parseDetail(domUrl, html) }
                            appendSniffedPlayAddresses(parsed.sources)
                        }
                    }
                }
            }.onFailure {
                Log.w(TAG, "parse sniffed DOM failed entry=$entry: ${it.message}")
                if (isInitial) {
                    progressDialog?.update(ParseStep.ERROR, it.message ?: "解析失败")
                }
            }.getOrDefault(0)

            if (isInitial && count > 0) {
                progressDialog?.update(ParseStep.LOADING_DONE)
                progressDialog?.dismissDelayed()
            }
            reportNewCount(count)
        }
    }

    /** 将列表适配器识别出的新影片增量追加到底层数据与卡片列表。 */
    private fun appendSniffedListMovies(parsed: List<ParsedListMovie>): Int {
        val existing = listMovies
        val existingUrls = existing.map { it.detailUrl }.toHashSet()
        val incremental = parsed.filter { it.detailUrl.isNotBlank() && existingUrls.add(it.detailUrl) }
        if (incremental.isEmpty()) return 0

        val merged = existing + incremental
        listMovies = merged
        listCountView?.text = "已解析到 ${merged.size} 个影片条目"
        val oldGrid = listGrid
        val oldFooter = listFooter
        if (oldGrid != null) contentArea.removeView(oldGrid)
        if (oldFooter != null) contentArea.removeView(oldFooter)
        val newGrid = listMovieGrid(merged)
        val newFooter = listFooterView()
        listGrid = newGrid
        listFooter = newFooter
        contentArea.addView(newGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentArea.addView(newFooter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return incremental.size
    }

    /** 详情页使用当前适配器解析 DOM，只按播放地址去重并追加线路/剧集。 */
    private fun appendSniffedPlayAddresses(parsedSources: List<ParsedSource>): Int {
        val current = movie ?: return 0
        val knownAddresses = current.sources
            .flatMap { it.episodes }
            .map { it.resolvedUrl.orEmpty().ifBlank { it.playPageUrl } }
            .filter { it.isNotBlank() }
            .toHashSet()
        var added = 0
        val mergedSources = current.sources.toMutableList()

        parsedSources.forEach { parsedSource ->
            val newEpisodes = parsedSource.episodes.filter { episode ->
                val address = episode.resolvedUrl.orEmpty().ifBlank { episode.playPageUrl }
                address.isNotBlank() && knownAddresses.add(address)
            }
            if (newEpisodes.isEmpty()) return@forEach
            added += newEpisodes.size
            val sourceIndex = mergedSources.indexOfFirst { it.name == parsedSource.name }
            if (sourceIndex >= 0) {
                val oldSource = mergedSources[sourceIndex]
                mergedSources[sourceIndex] = oldSource.copy(episodes = oldSource.episodes + newEpisodes)
            } else {
                mergedSources.add(parsedSource.copy(episodes = newEpisodes))
            }
        }

        if (added > 0) {
            movie = current.copy(sources = mergedSources)
            render()
        }
        return added
    }

    /**
     * 用嗅探到的接口请求更多数据
     * 优先用 diff 确认的翻页模板；未确认时退回启发式 incrementPageParam
     * 回放 cookie 与捕获的请求头，适配需会话的接口
     * @return (本页影片, 下一个页码值)
     */
    private suspend fun loadFromSniffedApi(
        api: ResourceSniffDialog.SniffedApi,
        loadedCount: Int
    ): Pair<List<ParsedListMovie>, Int> = withContext(Dispatchers.IO) {
        val currentPage = sniffNextPageValue
        val nextUrl = nextSniffUrl(api, currentPage, loadedCount)
        val conn = (URL(nextUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = api.method
            connectTimeout = 15_000
            readTimeout = 15_000
            // 回放捕获的请求头（排除由连接自管理的头，避免冲突）
            api.headers.forEach { (k, v) ->
                val lk = k.lowercase()
                if (lk != "user-agent" && lk != "accept" && lk != "content-type" &&
                    lk != "cookie" && lk != "host" && lk != "content-length" && lk != "connection") {
                    setRequestProperty(k, v)
                }
            }
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (api.cookie.isNotBlank()) setRequestProperty("Cookie", api.cookie)
            if (api.method == "POST" && api.body.isNotBlank()) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (api.method == "POST" && api.body.isNotBlank()) {
            conn.outputStream.use { it.write(api.body.toByteArray()) }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            error("接口请求失败 HTTP $code")
        }
        val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        parseMoviesFromJson(text, api) to (currentPage + (api.paging?.step ?: 0))
    }

    /** 根据翻页信息生成下一页 URL；paging 为空时退回启发式 */
    private fun nextSniffUrl(
        api: ResourceSniffDialog.SniffedApi,
        currentPage: Int,
        loadedCount: Int
    ): String {
        val paging = api.paging
        if (paging != null) {
            return paging.template.replace("{{PAGE}}", currentPage.toString())
        }
        return incrementPageParam(api.url, loadedCount, api.perPage)
    }

    /** 递增 URL 中的分页参数（启发式回退路径，用真实 perPage 替代硬编码 20） */
    private fun incrementPageParam(url: String, loadedCount: Int, perPage: Int): String {
        val pp = if (perPage > 0) perPage else 20
        val pageParams = listOf("page", "p", "pageNo", "pageIndex", "pageNum")
        val offsetParams = listOf("offset", "skip", "start")
        // page 类
        for (param in pageParams) {
            val regex = Regex("([?&]$param=)(\\d+)", RegexOption.IGNORE_CASE)
            val match = regex.find(url)
            if (match != null) {
                val nextPage = (loadedCount / pp) + 1
                return url.replaceRange(match.range, "${match.groupValues[1]}$nextPage")
            }
        }
        // offset 类
        for (param in offsetParams) {
            val regex = Regex("([?&]$param=)(\\d+)", RegexOption.IGNORE_CASE)
            val match = regex.find(url)
            if (match != null) {
                return url.replaceRange(match.range, "${match.groupValues[1]}$loadedCount")
            }
        }
        // 无分页参数，追加 page
        val separator = if (url.contains("?")) "&" else "?"
        return "$url${separator}page=${loadedCount / pp + 1}"
    }

    /** 从 JSON 响应中解析影片列表 */
    private fun parseMoviesFromJson(
        text: String,
        api: ResourceSniffDialog.SniffedApi
    ): List<ParsedListMovie> {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        val root = if (trimmed.startsWith("[")) {
            JSONObject().put("__root__", org.json.JSONArray(trimmed)).let { JSONObject(it.toString()) }
        } else {
            JSONObject(trimmed)
        }
        // 按 dataPath 定位数组
        val array = if (api.dataPath.isBlank()) {
            root.opt("__root__") as? org.json.JSONArray
                ?: root.keys().asSequence().mapNotNull { root.opt(it) as? org.json.JSONArray }.firstOrNull()
        } else {
            var node: Any = root
            for (seg in api.dataPath.split(".")) {
                node = (node as? JSONObject)?.opt(seg) ?: return emptyList()
            }
            node as? org.json.JSONArray
        } ?: return emptyList()

        val baseUrl = api.url.substringBefore("://") + "://" + URL(api.url).host
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val title = obj.optString(api.titleField).ifBlank { return@mapNotNull null }
            var detailUrl = obj.optString(api.urlField).ifBlank { return@mapNotNull null }
            // 补全相对 URL
            if (!detailUrl.startsWith("http")) {
                detailUrl = if (detailUrl.startsWith("/")) "$baseUrl$detailUrl" else "$baseUrl/$detailUrl"
            }
            val cover = if (api.coverField.isNotBlank()) obj.optString(api.coverField) else ""
            ParsedListMovie(title = title, coverUrl = cover, detailUrl = detailUrl)
        }.distinctBy { it.detailUrl }
    }

    private fun listMovieGrid(items: List<ParsedListMovie>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(4), dp(16), dp(4), dp(4))
        val spanCount = 4
        val cardHeight = dp(220)
        val itemGap = dp(8)
        items.chunked(spanCount).forEachIndexed { rowIndex, rowItems ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            rowItems.forEachIndexed { columnIndex, item ->
                val index = rowIndex * spanCount + columnIndex
                row.addView(
                    listMovieCard(item, index, items.size),
                    LinearLayout.LayoutParams(0, cardHeight, 1f).apply {
                        if (columnIndex > 0) marginStart = itemGap
                    }
                )
            }
            repeat(spanCount - rowItems.size) {
                row.addView(
                    FrameLayout(context),
                    LinearLayout.LayoutParams(0, cardHeight, 1f).apply { marginStart = itemGap }
                )
            }
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cardHeight).apply { if (rowIndex > 0) topMargin = itemGap })
        }
    }

    private fun listMovieCard(item: ParsedListMovie, index: Int, total: Int): LinearLayout = LinearLayout(context).apply {
        tag = "list_movie_$index"
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isClickable = true
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = listMovieCardBg(false)
        clipChildren = false
        clipToPadding = false
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            setImageResource(R.drawable.ic_thumb_default)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(42, 255, 255, 255))
            }
        }
        addView(cover, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        loadThumb(item.coverUrl, cover)
        addView(TextView(context).apply {
            text = item.title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(3), dp(7), dp(3), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        setOnFocusChangeListener { v, has ->
            background = listMovieCardBg(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 14)
        }
        setOnClickListener { startParseFromList(item) }
        setOnKeyListener { v, _, e -> listMovieBoundaryKey(v, e, index, total) }
    }

    private fun listMovieBoundaryKey(v: View, event: KeyEvent, index: Int, total: Int): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val spanCount = 4
            val column = index % spanCount
            val lastColumn = minOf(spanCount - 1, (total - 1) % spanCount)
            val lastRowStart = ((total - 1) / spanCount) * spanCount
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && column == 0 && !hasFocusableInDirection(v, View.FOCUS_LEFT)) {
                BoundaryFocusHandler.shake(v)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && (column == spanCount - 1 || (index >= lastRowStart && column == lastColumn)) && !hasFocusableInDirection(v, View.FOCUS_RIGHT)) {
                BoundaryFocusHandler.shake(v)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && index < spanCount && !hasFocusableInDirection(v, View.FOCUS_UP)) {
                BoundaryFocusHandler.shake(v)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && index >= lastRowStart && !hasFocusableInDirection(v, View.FOCUS_DOWN)) {
                // 最后一行按【下】：优先加载更多（HTML 翻页 / 嗅探接口），其次进入资源嗅探，
                // 均不可行（无更多内容）时触发边界抖动拦截
                val loading = listNextPageJob?.isActive == true
                val hasMore = listNextPageUrl != null
                val hasSniffedApi = sniffedApi != null && !sniffExhausted
                val canSniff = listNextPageUrl == null && sniffedApi == null && !sniffExhausted
                when {
                    loading -> { /* 加载中，忽略重复触发 */ }
                    hasMore -> loadNextPage()
                    hasSniffedApi -> loadNextPage()
                    canSniff -> showResourceSniffDialog(ResourceSniffDialog.Entry.LIST)
                    else -> BoundaryFocusHandler.shake(v)
                }
                return true
            }
        }
        return boundaryKey(v, event)
    }

    private fun sourceTabs(data: ParsedMovie): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        data.sources.forEachIndexed { index, source ->
            row.addView(tabButton(source.name, index == selectedSourceIndex) {
                selectedSourceIndex = index
                selectedEpisodeIndex = 0
                render()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        return row
    }

    private fun episodeList(data: ParsedMovie): View {
        val source = data.sources.getOrNull(selectedSourceIndex)
        if (source == null) {
            Log.w(TAG, "episodeList no source selectedSourceIndex=$selectedSourceIndex sources=${data.sources.size}")
            return emptyEpisodeView("未解析到播放线路，请尝试 JSON解析")
        }
        Log.d(TAG, "episodeList source=${source.name} episodes=${source.episodes.size}")
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        var row: LinearLayout? = null
        source.episodes.forEachIndexed { index, ep ->
            if (index % 5 == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { if (index > 0) topMargin = dp(8) })
            }
            row?.addView(episodeButton(ep.name, index, source.episodes.size, index == selectedEpisodeIndex, store.getProgress(currentUrl)?.let { it.sourceIndex == selectedSourceIndex && it.episodeIndex == index && it.positionSec > 0 } == true) {
                selectedEpisodeIndex = index
                launchEpisode(index)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (index % 5 > 0) marginStart = dp(8) })
        }
        return grid
    }

    private fun emptyEpisodeView(message: String): TextView = TextView(context).apply {
        text = message
        textSize = 16f
        setTextColor(Color.argb(220, 255, 255, 255))
        gravity = Gravity.CENTER
        isFocusable = true
        background = panelBg(false)
        setOnKeyListener { v, _, e -> boundaryKey(v, e) }
    }

    private fun launchEpisode(index: Int) {
        val data = movie ?: return
        val source = data.sources.getOrNull(selectedSourceIndex) ?: return
        val ep = source.episodes.getOrNull(index) ?: return
        scope.launch {
            val progressDialog = WebParseProgressDialog(context) { }
            progressDialog.show()
            progressDialog.update(ParseStep.RECEIVED)
            progressDialog.update(ParseStep.FETCHING_HTML)
            val uri = withContext(Dispatchers.IO) { ep.resolvedUrl ?: extractor.resolve(ep.playPageUrl) }
            if (uri.isNullOrBlank() || !WebParseHtml.looksPlayable(uri)) {
                progressDialog.update(ParseStep.ERROR, "未解析到有效视频直链")
                delay(1200L)
                progressDialog.dismissDelayed()
                toast("未解析到 m3u8/mp4 视频直链，请换一集或换线路")
                Log.w(TAG, "launchEpisode resolve failed playPageUrl=${ep.playPageUrl} resolved=$uri")
                return@launch
            }
            ep.resolvedUrl = uri
            progressDialog.update(ParseStep.LOADING_DONE)
            progressDialog.dismissDelayed()
            val startMs = store.getProgress(currentUrl)
                ?.takeIf { it.sourceIndex == selectedSourceIndex && it.episodeIndex == index }
                ?.positionSec?.times(1000L) ?: 0L
            val title = if (data.sources.size > 1) "${ep.name} · ${source.name}" else ep.name
            val referer = ep.playPageUrl.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: currentUrl
            val mimeType = inferMimeType(uri)
            PlaybackController.recordPlaybackHistory(uri, title, "网页解析播放", data.coverUrl)
            context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(PlayerActivity.EXTRA_URI, uri)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_SOURCE, "网页解析播放")
                putExtra(PlayerActivity.EXTRA_MIME_TYPE, mimeType)
                putExtra(PlayerActivity.EXTRA_HTTP_REFERER, referer)
                putExtra(PlayerActivity.EXTRA_HTTP_USER_AGENT, WEB_PARSE_USER_AGENT)
                putExtra(PlayerActivity.EXTRA_START_POSITION, startMs)
                putExtra(com.bd.casttv.ui.framework.NewMainActivity.EXTRA_OPEN_PAGE_ID, pageId)
            })
            startProgressPoll(uri, selectedSourceIndex, index)
        }
    }

    private fun startProgressPoll(uri: String, sourceIndex: Int, episodeIndex: Int) {
        progressJob?.cancel()
        progressJob = scope.launch {
            repeat(720) {
                delay(5000L)
                if (PlaybackController.currentUri == uri) {
                    store.saveProgress(currentUrl, sourceIndex, episodeIndex, PlaybackController.positionMs / 1000L)
                }
            }
        }
    }

    private fun inferMimeType(uri: String): String {
        val lower = uri.substringBefore('#').substringBefore('?').lowercase(java.util.Locale.US)
        return when {
            lower.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.endsWith(".mp4") || lower.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
            lower.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
            lower.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
            else -> ""
        }
    }

    private fun loadCover(url: String, image: ImageView) {
        loadBitmapInto(url, image) { image.setImageBitmap(it) }
    }

    private fun loadThumb(url: String, image: ImageView) {
        loadBitmapInto(url, image) { image.setImageBitmap(it) }
    }

    private fun loadBitmapInto(url: String, image: ImageView, applyBitmap: (Bitmap) -> Unit) {
        if (url.isBlank()) return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { downloadBitmap(url) }
            if (bmp != null) {
                applyBitmap(bmp)
            } else {
                image.setImageDrawable(null)
                image.background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#333333"))
                }
            }
        }
    }

    private fun downloadBitmap(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) { null }

    private fun collapsedDescriptionText(value: String): String {
        val normalized = value.trim()
        val limit = 72
        return if (normalized.length > limit) "${normalized.take(limit).trimEnd()}… ▽展开" else "$normalized ▽展开"
    }

    private fun infoLine(label: String, value: String): TextView = TextView(context).apply { text = "$label：$value"; textSize = 14f; setTextColor(Color.argb(220, 255, 255, 255)); setPadding(0, dp(8), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    private fun tabButton(text: String, selected: Boolean, click: () -> Unit): FrameLayout {
        val frame = FrameLayout(context).apply { isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false; setOnClickListener { click() } }
        val glow = GlowUnderlineView(context).apply { applyVisualState(false, false) }
        val tv = TextView(context).apply { this.text = text; gravity = Gravity.CENTER; textSize = 15f; setTextColor(if (selected) warm else Color.WHITE); includeFontPadding = false }
        frame.addView(glow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.setOnFocusChangeListener { v, has -> tv.setTextColor(if (selected || has) warm else Color.WHITE); glow.applyVisualState(false, has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        frame.setOnKeyListener { v, _, e -> boundaryKey(v, e) }
        return frame
    }

    private fun episodeButton(text: String, index: Int, total: Int, selected: Boolean, played: Boolean, click: () -> Unit): TextView = dialogButton(if (played) "▶ $text" else text, click).apply {
        isSelected = selected
        setTextColor(if (selected || played) warm else Color.WHITE)
        setOnKeyListener { v, _, e -> episodeBoundaryKey(v, e, index, total) }
    }

    private fun episodeBoundaryKey(v: View, event: KeyEvent, index: Int, total: Int): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val lastRowStart = ((total - 1) / 5) * 5
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && index < 5) {
                val desc = descriptionView
                if (desc != null && desc.visibility == View.VISIBLE && desc.isFocusable) {
                    desc.requestFocus()
                    return true
                }
                BoundaryFocusHandler.shake(v)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && index >= lastRowStart && !hasFocusableInDirection(v, View.FOCUS_DOWN)) {
                BoundaryFocusHandler.shake(v)
                return true
            }
        }
        return boundaryKey(v, event)
    }

    private fun hasFocusableInDirection(v: View, direction: Int): Boolean {
        val next = v.focusSearch(direction)
        return next != null && next !== v && next.visibility == View.VISIBLE && next.isFocusable && isChildOf(next, contentContainer)
    }

    private fun backToListButton(click: () -> Unit): TextView = TextView(context).apply {
        text = "←"
        textSize = 24f
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        gravity = Gravity.CENTER
        includeFontPadding = false
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            setTextColor(Color.argb(235, 245, 245, 245))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has ->
            refresh(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 23)
        }
        setOnClickListener { click() }
        setOnKeyListener { v, _, e -> boundaryKey(v, e) }
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            val selected = isSelected
            setTextColor(if (selected) warm else Color.argb(235, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has -> refresh(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        setOnClickListener { click() }
        setOnKeyListener { v, _, e -> boundaryKey(v, e) }
    }

    private fun iconButton(iconRes: Int, click: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(iconRes)
        scaleType = ImageView.ScaleType.CENTER
        setPadding(dp(7), dp(7), dp(7), dp(7))
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            setColorFilter(if (focused) warm else Color.argb(220, 210, 214, 222))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has -> refresh(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 19) }
        setOnClickListener { click() }
        setOnKeyListener { v, _, e -> boundaryKey(v, e) }
    }

    private fun inputBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(card)
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(95, 255, 255, 255))
    }

    private fun boundaryKey(v: View, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return false
        }
        val next = v.focusSearch(direction)
        if (next != null && next !== v && next.visibility == View.VISIBLE && next.isFocusable && isChildOf(next, contentContainer)) return false
        BoundaryFocusHandler.shake(v)
        return true
    }

    private fun panelBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(card)
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(90, 255, 255, 255))
    }

    private fun listMovieCardBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(card)
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(120, 210, 214, 222))
    }

    private fun localIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                ?.hostAddress.orEmpty()
        } catch (_: Throwable) { "" }
    }

    private fun isChildOf(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) { if (current === root) return true; current = current.parent as? View }
        return false
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
