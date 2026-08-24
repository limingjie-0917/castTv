package com.bd.casttv.ui.framework.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.bd.casttv.R
import com.bd.casttv.dlna.SsdpDiagnostics
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.PhoneHubHost
import com.bd.casttv.util.NetworkUtils
import com.bd.casttv.util.QrCodeGenerator
import com.bd.casttv.util.ThemeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.appcompat.app.AlertDialog
import android.widget.ImageView

/**
 * ⑥ 网络诊断 DiagnosticsPage：
 * - 6.1 SSDP 实时日志（[SsdpDiagnostics]，M-SEARCH / NOTIFY / HTTP 流水）
 * - 6.2 日志实时搜索（关键词过滤）
 * - 6.3 时间戳加粗（复用旧版 formatDiagnosticsBold 正则）
 * - 6.4 一键复制过滤后日志
 * - 6.5 清空日志
 * - 6.6 一键结构化诊断（WiFi / IP / DNS / 外网 / Gitee / 云端 / HTTP服务 / 直播源）
 * - 6.7 直播源可用性抽检（全量 M3U 频道 HEAD/GET）
 * - 6.8 上报日志（写到应用外部日志目录并生成二维码，配合 HTTP 服务给手机端下载）
 */
class DiagnosticsPage(context: Context) : BasePage(context) {
    override val pageId = "diagnostics"
    override val pageTitle = "网络诊断"
    override val pageIconRes = R.drawable.ic_dock_diagnostics
    override val enablePageScroll: Boolean = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true
    override val pageStickerRes: Int get() = R.drawable.sticker_nene_shiro

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private enum class LogTab(val title: String) {
        BIND_INTERFACE("绑定网卡"),
        CAST_EVENTS("投屏连接"),
        SERVICE_HEALTH("服务健康"),
        NOTIFY("NOTIFY"),
        SOAP_RESULTS("SOAP 响应")
    }

    @Volatile private var filterKeyword: String = ""
    @Volatile private var selectedTab: LogTab = LogTab.BIND_INTERFACE

    private var logRenderJob: Job? = null
    private var logUserScrolling: Boolean = false
    private var lastRenderedLog: String = "\u0000"
    private val searchInput = EditText(context).apply {
        hint = "🔍 关键词过滤"; textSize = 15f
        setTextColor(Color.rgb(230, 234, 240)); setHintTextColor(Color.rgb(140, 148, 160))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = softCard()
    }
    private val tabViews = LinkedHashMap<LogTab, TextView>()

    private val logList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        clipChildren = false
        clipToPadding = false
    }
    private val logScroll = ScrollView(context).apply {
        isFillViewport = true
        clipChildren = true
        clipToPadding = true
        overScrollMode = View.OVER_SCROLL_NEVER
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> logUserScrolling = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    logUserScrolling = false
                    postDelayed({ refreshLog() }, 180L)
                }
            }
            false
        }
    }
    private val checkOutput = TextView(context).apply {
        textSize = 13f
        setTextColor(Color.rgb(220, 226, 234))
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }
    private val checkScroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = true; overScrollMode = View.OVER_SCROLL_NEVER }
    private var leftCard: LinearLayout? = null
    private var rightCard: LinearLayout? = null

    private var refreshJob: Job? = null

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(12))
            clipChildren = false
            clipToPadding = false
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterKeyword = s?.toString().orEmpty(); refreshLog() }
        })

        // 左侧：顶部搜索/日志操作，下方实时流水日志，占比 3。
        leftCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = softCard()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            clipChildren = false
            clipToPadding = false
        }
        val leftCard = leftCard ?: LinearLayout(context)
        val leftToolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        leftToolbar.addView(searchInput, LinearLayout.LayoutParams(0, dp(42), 1f))
        listOf<Pair<TextView, (View) -> Unit>>(
            focusButton("复制日志") to { copyLog() },
            focusButton("删除日志") to { clearLog() }
        ).forEach { (btn, act) ->
            btn.layoutParams = LinearLayout.LayoutParams(dp(84), dp(42)).apply { leftMargin = dp(6) }
            btn.setOnClickListener { trigger -> act(trigger) }
            leftToolbar.addView(btn)
        }
        leftCard.addView(leftToolbar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 日志分类 Tab（仅展示：绑定网卡 / 投屏事件流水 / NOTIFY / SOAP 响应）
        val tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        LogTab.entries.forEach { tab ->
            val tv = tabButton(tab)
            tabViews[tab] = tv
            tabBar.addView(tv, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (tab != LogTab.entries.first()) leftMargin = dp(6)
            })
        }
        leftCard.addView(tabBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        leftCard.addView(TextView(context).apply {
            text = "当前 Tab 日志（关键词过滤全局生效）"
            setTextColor(Color.rgb(200, 205, 215))
            textSize = 13f
            setPadding(dp(2), dp(12), dp(2), dp(6))
        })
        updateTabUi()
        val logViewport = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }
        logScroll.addView(logList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        logViewport.addView(logScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        leftCard.addView(logViewport, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(leftCard, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))

        // 右侧：顶部诊断动作，下方结构化诊断结果，占比 2；去掉「上报日志」入口。
        rightCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = softCard()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            clipChildren = true
            clipToPadding = true
        }
        val rightCard = rightCard ?: LinearLayout(context)
        val rightToolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        listOf(
            focusButton("结构化诊断") to { runStructuredCheck() },
            focusButton("直播源抽检") to { runLiveSourceProbe() }
        ).forEach { (btn, act) ->
            btn.layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(6); rightMargin = dp(6) }
            btn.setOnClickListener { act() }
            rightToolbar.addView(btn)
        }
        rightCard.addView(rightToolbar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rightCard.addView(TextView(context).apply { text = "结构化诊断结果"; setTextColor(Color.rgb(200, 205, 215)); textSize = 13f; setPadding(dp(2), dp(14), dp(2), dp(6)) })
        checkScroll.addView(checkOutput, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rightCard.addView(checkScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(rightCard, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 2f).apply { leftMargin = dp(14) })

        contentContainer.addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        checkOutput.text = "点击「结构化诊断」运行八项检查；\n点击「直播源抽检」对 M3U 频道逐条 HEAD 探活。"
    }

    override fun onEnter() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) { refreshLog(); delay(1500L) }
        }
    }
    override fun onLeave() {
        refreshJob?.cancel(); refreshJob = null
        logRenderJob?.cancel(); logRenderJob = null
    }

    override fun refreshTheme() {
        super.refreshTheme()
        leftCard?.background = softCard()
        rightCard?.background = softCard()
    }

    // -------------------------------------------------------------- 6.1~6.3
    private fun refreshLog() {
        if (logUserScrolling) return
        logRenderJob?.cancel()
        logRenderJob = scope.launch {
            val keyword = filterKeyword
            val (rawDisplay, formatted) = withContext(Dispatchers.Default) {
                val prepared = prepareDisplayLog(snapshotCurrentTabText(), keyword)
                prepared to formatBold(prepared)
            }
            if (logUserScrolling || rawDisplay == lastRenderedLog) return@launch
            lastRenderedLog = rawDisplay
            renderLogLines(rawDisplay, formatted)
        }
    }

    private fun snapshotCurrentTabText(): String {
        return when (selectedTab) {
            LogTab.BIND_INTERFACE -> SsdpDiagnostics.snapshotInterfacesText()
            LogTab.CAST_EVENTS -> SsdpDiagnostics.snapshotCastEventsText()
            LogTab.SERVICE_HEALTH -> SsdpDiagnostics.snapshotServiceHealthText()
            LogTab.NOTIFY -> SsdpDiagnostics.snapshotNotifyText()
            LogTab.SOAP_RESULTS -> SsdpDiagnostics.snapshotSoapResultsText()
        }
    }

    private fun updateTabUi() {
        tabViews.forEach { (tab, tv) ->
            val selected = tab == selectedTab
            tv.text = tab.title
            tv.setTextColor(if (selected) WARM else Color.rgb(180, 188, 198))
            tv.isSelected = selected
        }
    }

    private fun prepareDisplayLog(raw: String, keyword: String): String {
        if (raw.isBlank()) return raw
        val sourceLines = raw.lineSequence().toList().takeLast(MAX_LOG_SOURCE_LINES)
        val filtered = if (keyword.isBlank()) {
            sourceLines
        } else {
            sourceLines.filter { it.contains(keyword, ignoreCase = true) }
        }
        val clipped = filtered.takeLast(MAX_LOG_DISPLAY_LINES)
        val omitted = filtered.size - clipped.size
        return buildString {
            if (omitted > 0) append("已折叠较早日志 ${omitted} 行，仅展示最近 ${MAX_LOG_DISPLAY_LINES} 行，避免滑动日志时卡顿/ANR。\n")
            append(clipped.joinToString("\n"))
        }
    }

    /** 复用旧版 formatDiagnosticsBold 正则：事件行 [HH:mm:ss.SSS] 与明细行「- HH:mm:ss.SSS」加粗时间戳。 */
    private fun formatBold(text: String): CharSequence {
        if (text.isEmpty()) return text
        val ssb = SpannableStringBuilder(text)
        val eventRegex = Regex("""(?m)^\[\d{2}:\d{2}:\d{2}\.\d{3}\]""")
        for (m in eventRegex.findAll(text)) ssb.setSpan(StyleSpan(Typeface.BOLD), m.range.first, m.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val detailRegex = Regex("""(?m)^- (\d{2}:\d{2}:\d{2}\.\d{3})""")
        for (m in detailRegex.findAll(text)) {
            val g = m.groups[1] ?: continue
            ssb.setSpan(StyleSpan(Typeface.BOLD), g.range.first, g.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return ssb
    }

    private fun renderLogLines(rawText: String, formattedText: CharSequence) {
        logList.removeAllViews()
        val formattedLines = formattedText.toString().split('\n')
        val rawLines = rawText.split('\n')
        if (rawLines.isEmpty() || (rawLines.size == 1 && rawLines.first().isBlank())) {
            logList.addView(buildLogItem("暂无日志"))
            return
        }
        rawLines.forEachIndexed { index, line ->
            val displayLine = formattedLines.getOrNull(index) ?: line
            logList.addView(
                buildLogItem(displayLine).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (index > 0) topMargin = dp(6)
                    }
                }
            )
        }
    }

    private fun buildLogItem(text: CharSequence): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(220, 226, 234))
        typeface = Typeface.MONOSPACE
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(Color.argb(30, 200, 200, 200))
        }
    }

    // -------------------------------------------------------------- 6.4 / 6.5
    private fun copyLog() {
        val current = lastRenderedLog
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("CastTV diagnostics", current))
        toast("日志已复制（含当前过滤条件）")
    }

    private fun clearLog() {
        try { SsdpDiagnostics.clearAll() } catch (_: Throwable) {}
        lastRenderedLog = "\u0000"
        refreshLog()
        toast("日志已删除")
    }

    // -------------------------------------------------------------- 6.6
    private fun runStructuredCheck() {
        checkOutput.text = "结构化诊断进行中…\n"
        scope.launch {
            val sb = StringBuilder()
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            fun append(line: String) { sb.append("[").append(fmt.format(Date())).append("] ").append(line).append('\n'); checkOutput.text = sb.toString() }

            append("① WiFi/网卡…")
            val ifs = withContext(Dispatchers.IO) { runCatching { NetworkUtils.getLanInterfaces() }.getOrDefault(emptyList()) }
            if (ifs.isEmpty()) append("  ⚠️ 未探测到可用局域网网卡") else ifs.forEach { append("  ✅ ${it.name}  ${it.ip}") }

            append("② 本机 IP…")
            val ip = withContext(Dispatchers.IO) { runCatching { NetworkUtils.getLocalIpAddress() }.getOrNull() }
            append(if (ip.isNullOrBlank()) "  ⚠️ 未获取到 IP（可能未连接 WiFi）" else "  ✅ 本机 IP：$ip")

            append("③ DNS 解析（baidu.com / gitee.com）…")
            val dnsResults = withContext(Dispatchers.IO) {
                listOf("baidu.com", "gitee.com").map { host ->
                    val addrs = runCatching { InetAddress.getAllByName(host).joinToString { it.hostAddress ?: "" } }.getOrDefault("")
                    host to addrs
                }
            }
            dnsResults.forEach { (h, a) -> append(if (a.isBlank()) "  ⚠️ $h 解析失败" else "  ✅ $h → $a") }

            append("④ 外网 HTTP（http://www.baidu.com）…")
            val (extOk, extMsg) = withContext(Dispatchers.IO) { httpProbe("http://www.baidu.com") }
            append(if (extOk) "  ✅ $extMsg" else "  ⚠️ $extMsg")

            append("⑤ Gitee 云端仓库（https://gitee.com）…")
            val (gtOk, gtMsg) = withContext(Dispatchers.IO) { httpProbe("https://gitee.com") }
            append(if (gtOk) "  ✅ $gtMsg" else "  ⚠️ $gtMsg")

            append("⑥ 云端 index.json（Gitee）…")
            val (cIdxOk, cIdxMsg) = withContext(Dispatchers.IO) {
                runCatching {
                    val r = com.bd.casttv.sync.GiteeApi.getFile("index.json")
                    if (r != null) true to "index.json 可访问 · size=${r.content.length}"
                    else false to "index.json 404 或无权限（首次上传前属正常）"
                }.getOrDefault(false to "index.json 请求异常")
            }
            append(if (cIdxOk) "  ✅ $cIdxMsg" else "  ⚠️ $cIdxMsg")

            append("⑦ 本机 HTTP 服务（PhoneHubHost）…")
            val phoneOn = PhoneHubHost.isRunning()
            if (!phoneOn) append("  ⏸ HTTP 服务未开启（请在「连接手机」中启动）")
            else {
                val port = PhoneHubHost.port()
                val myIp = ip ?: "127.0.0.1"
                val (pOk, pMsg) = withContext(Dispatchers.IO) { httpProbe("http://$myIp:$port/") }
                append(if (pOk) "  ✅ $pMsg" else "  ⚠️ $pMsg")
            }

            append("⑧ 直播源合集数量…")
            val cnt = withContext(Dispatchers.IO) {
                runCatching {
                    val store = FavoritesStore(context.applicationContext)
                    store.collectionsInfo().count { it.name.contains("直播", true) || it.name.contains("live", true) }
                }.getOrDefault(0)
            }
            append(if (cnt > 0) "  ✅ 检测到 $cnt 个直播源合集" else "  ⏭ 未检测到明显的直播源合集")

            append("诊断完成 ✅")
        }
    }

    private fun httpProbe(url: String): Pair<Boolean, String> {
        return try {
            val start = System.currentTimeMillis()
            val u = URL(url)
            val conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000; requestMethod = "GET"; instanceFollowRedirects = true
            }
            val code = conn.responseCode
            val cost = System.currentTimeMillis() - start
            conn.disconnect()
            (code in 200..399) to "$url → HTTP $code · ${cost}ms"
        } catch (t: Throwable) { false to "$url → ${t.javaClass.simpleName}: ${t.message}" }
    }

    // -------------------------------------------------------------- 6.7
    private fun runLiveSourceProbe() {
        checkOutput.text = "直播源抽检进行中…\n"
        scope.launch {
            val sb = StringBuilder()
            fun append(line: String) { sb.append(line).append('\n'); checkOutput.text = sb.toString() }
            val urls = withContext(Dispatchers.IO) {
                runCatching {
                    val store = FavoritesStore(context.applicationContext)
                    val infos = store.collectionsInfo()
                    infos.flatMap { info ->
                        runCatching { store.collection(info.id)?.items.orEmpty() }.getOrDefault(emptyList())
                    }.filter { it.isLive && it.uri.isNotBlank() }
                }.getOrDefault(emptyList())
            }
            if (urls.isEmpty()) { append("⏭ 未检测到直播条目（M3U 频道）"); return@launch }
            append("共发现 ${urls.size} 个直播条目，开始 HEAD/GET 探活…")
            var ok = 0; var fail = 0
            for ((i, item) in urls.withIndex()) {
                val (r, msg) = withContext(Dispatchers.IO) { httpProbe(item.uri) }
                if (r) ok++ else fail++
                append("[${i + 1}/${urls.size}] ${if (r) "✅" else "❌"} ${item.title.ifBlank { item.uri }} — $msg")
            }
            append("完成：${ok} 可用 / ${fail} 失败 · 总计 ${urls.size}")
        }
    }

    // -------------------------------------------------------------- 6.8
    private fun exportAndShareLog() {
        scope.launch {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val (file, err) = withContext(Dispatchers.IO) {
                try {
                    val dir = File(context.applicationContext.filesDir, "casttv_reports").apply { mkdirs() }
                    val f = File(dir, "diagnostics_$ts.txt")
                    f.writeText(SsdpDiagnostics.snapshotText())
                    f to null
                } catch (t: Throwable) { null to (t.message ?: "写入失败") }
            }
            if (file == null) { toast("导出失败：$err"); return@launch }

            val ip = withContext(Dispatchers.IO) { runCatching { NetworkUtils.getLocalIpAddress() }.getOrNull() }
            val port = PhoneHubHost.port()
            val downloadUrl = if (PhoneHubHost.isRunning() && !ip.isNullOrBlank() && port > 0) {
                "http://$ip:$port/diagnostics/${file.name}"
            } else null

            val shareInfo = JSONObject().apply {
                put("file", file.absolutePath)
                put("size", file.length())
                if (downloadUrl != null) put("http", downloadUrl) else put("http", "HTTP 服务未开启")
            }.toString(2)
            checkOutput.text = "📄 日志已导出：\n${file.absolutePath}\n大小：${file.length()} bytes\n$shareInfo\n\n" +
                (if (downloadUrl != null) "扫描右侧二维码可在手机端下载（PhoneHubServer 未内置该路由时，请在电视上打开文件管理复制）"
                 else "尚未开启「连接手机」HTTP 服务，无法生成二维码；可先复制上述路径到 U 盘 / adb 拉取。")
            if (downloadUrl != null) showLogQrDialog(downloadUrl)
            toast("日志已导出")
        }
    }

    private fun showLogQrDialog(url: String) {
        val bmp = QrCodeGenerator.encode(url, dp(320)) ?: return
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(32), dp(24), dp(32), dp(20))
        }
        container.addView(TextView(context).apply { text = "扫码下载诊断日志"; textSize = 20f; setTextColor(WARM); gravity = Gravity.CENTER })
        val qr = ImageView(context).apply { setImageBitmap(bmp) }
        container.addView(qr, LinearLayout.LayoutParams(dp(320), dp(320)).apply { topMargin = dp(16) })
        container.addView(TextView(context).apply { text = url; textSize = 14f; setTextColor(Color.rgb(214, 219, 226)); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(container)
            .setPositiveButton("关闭", null)
            .show()
    }

    // -------------------------------------------------------------- 样式
    private fun softCard(): GradientDrawable {
        val palette = ThemeManager.currentPalette(context)
        val start = parseThemeColor(palette.contentPanelGradientA, 176, Color.rgb(20, 24, 32))
        val end = parseThemeColor(palette.contentPanelGradientB, 144, Color.rgb(48, 54, 68))
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(start, end)
        ).apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.argb(80, 255, 255, 255))
        }
    }
    private fun tabButton(tab: LogTab): TextView {
        val tv = TextView(context)
        tv.text = tab.title
        tv.textSize = 14f
        tv.setTextColor(Color.rgb(180, 188, 198))
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true
        tv.isFocusableInTouchMode = true
        tv.isClickable = true

        val bg = { focused: Boolean ->
            GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(120, 24, 28, 36))
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(110, 210, 210, 220))
            }
        }
        tv.background = bg(false)
        tv.setOnFocusChangeListener { v, has ->
            v.background = bg(has)
            // 选中态只影响文字暖黄；焦点态用边框 + FocusFx 强化。
            val isSelectedTab = (v as TextView).isSelected
            v.setTextColor(if (isSelectedTab) WARM else Color.rgb(180, 188, 198))
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 12)
        }
        tv.setOnClickListener {
            selectedTab = tab
            updateTabUi()
            lastRenderedLog = "\u0000"
            refreshLog()
        }
        return tv
    }

    private fun focusButton(initial: String): TextView {
        val tv = TextView(context)
        tv.text = initial; tv.textSize = 14f; tv.setTextColor(Color.rgb(220, 226, 236)); tv.gravity = Gravity.CENTER
        tv.isFocusable = true; tv.isFocusableInTouchMode = true; tv.isClickable = true
        val bg = { focused: Boolean ->
            GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(180, 24, 28, 36))
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(120, 200, 200, 210))
            }
        }
        tv.background = bg(false)
        tv.setOnFocusChangeListener { v, has ->
            v.background = bg(has)
            (v as TextView).setTextColor(if (has) WARM else Color.rgb(220, 226, 236))
            // 全局焦点 fx（A：scale + translationZ 发光；不叠加暖黄前景）。
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
        }
        return tv
    }
    private fun toast(s: String) { Toast.makeText(context, s, Toast.LENGTH_SHORT).show() }
    private fun parseThemeColor(hex: String, alpha: Int, fallback: Int): Int = try {
        val rgb = Color.parseColor(hex)
        Color.argb(alpha.coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    } catch (_: Throwable) {
        Color.argb(alpha.coerceIn(0, 255), Color.red(fallback), Color.green(fallback), Color.blue(fallback))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val WARM = Color.rgb(245, 196, 81)
        private const val MAX_LOG_SOURCE_LINES = 1000
        private const val MAX_LOG_DISPLAY_LINES = 300
    }
}
