package com.bd.casttv.webparse

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.bd.casttv.R
import org.json.JSONObject
import kotlin.math.abs

/**
 * 资源嗅探弹窗：当列表页无法提取 nextPageUrl 时，通过 WebView 加载页面，
 * 注入 JS 拦截 XHR/fetch 请求（含请求头与 cookie），识别影片列表数据接口。
 *
 * 交互流程：
 * 1. 用户在 WebView 中上滑触发页面 JS 加载更多
 * 2. App 拦截所有 XHR/fetch 请求及响应（含请求头）
 * 3. 分析响应是否为影片列表 JSON（含 title + url 字段数组）
 * 4. 持续累积捕获；同端点出现两次时，diff 定位真实翻页参数/步长/每页条数
 * 5. 已确认则提示并等待用户按 Back 关闭；未确认可继续上滑，或按 Back 用启发式回退
 * 6. 关闭时回调返回最优接口信息（含 cookie/请求头/翻页模板）
 */
class ResourceSniffDialog(
    private val context: Context,
    private val pageUrl: String,
    private val entry: Entry,
    private val onDomSnapshot: (pageUrl: String, html: String, reportNewCount: (Int) -> Unit) -> Unit,
    private val onSniffed: (SniffedApi) -> Unit = {},
    private val onClosed: () -> Unit = {}
) {
    companion object {
        private const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; Chromecast) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /**
         * 在纯 WebView 场景下尽量弱化自动化标记，降低被 Cloudflare 立即识别为 bot 的概率。
         * 该脚本会在页面完成后注入；若站点首屏脚本已先运行，仍建议配合系统浏览器兜底。
         */
        private val automationMitigationScript = """
            (function() {
                try {
                    if (window.__cfAutomationPatched) return;
                    window.__cfAutomationPatched = true;
                    var patch = function(target, key, value) {
                        try {
                            Object.defineProperty(target, key, {
                                configurable: true,
                                enumerable: false,
                                get: function() { return value; }
                            });
                        } catch (e) {}
                    };
                    patch(navigator, 'webdriver', undefined);
                    patch(navigator, 'platform', 'Linux armv8l');
                    patch(navigator, 'maxTouchPoints', 1);
                    patch(navigator, 'pdfViewerEnabled', true);
                    if (!window.chrome) {
                        window.chrome = { runtime: {} };
                    } else if (!window.chrome.runtime) {
                        window.chrome.runtime = {};
                    }
                    if (!navigator.permissions || !navigator.permissions.query) return;
                    var originQuery = navigator.permissions.query.bind(navigator.permissions);
                    navigator.permissions.query = function(parameters) {
                        if (parameters && parameters.name === 'notifications') {
                            return Promise.resolve({ state: Notification.permission });
                        }
                        return originQuery(parameters);
                    };
                } catch (e) {}
            })();
        """.trimIndent()
    }
    /** 嗅探入口决定 DOM 变化后由调用方使用列表适配器还是详情适配器解析。 */
    enum class Entry { LIST, DETAIL }
    /** 嗅探到的影片列表接口信息 */
    data class SniffedApi(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val cookie: String,
        val body: String,
        /** 响应中影片数组所在的 JSON 路径，如 "data.list" / "results" / "list" */
        val dataPath: String,
        val titleField: String,
        val urlField: String,
        /** 影片封面字段名（可选） */
        val coverField: String,
        /** 每页条数（取自响应数组长度） */
        val perPage: Int,
        /** 翻页信息；null 表示未能确认，调用方退回启发式 */
        val paging: PagingInfo?
    )

    /** 翻页信息：把 template 中的 {{PAGE}} 替换为当前页值即可得到下一页 URL */
    data class PagingInfo(
        /** URL 模板，含 {{PAGE}} 占位符 */
        val template: String,
        /** query 模式下的参数名；path 模式为 null */
        val paramName: String?,
        /** "query" 或 "path" */
        val location: String,
        /** path 模式下页码所在的路径段索引（从根 0 起）；query 模式为 -1 */
        val pathIndex: Int,
        /** 步长（page 类=1，offset 类=每页条数） */
        val step: Int,
        /** 首次捕获到的页码值 */
        val startValue: Int
    )

    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var incrementBanner: TextView? = null
    private var bannerHideRunnable: Runnable? = null
    private var winner: SniffedApi? = null
    private var scrollTriggerCount = 0

    /** 已捕获的候选请求（按命中顺序） */
    private val captures = mutableListOf<Capture>()

    /** 单次捕获的原始信息 */
    private data class Capture(
        val url: String,
        val method: String,
        val body: String,
        val headers: Map<String, String>,
        val cookie: String,
        val analysis: ListAnalysisResult
    )

    // JS 注入脚本：hook XMLHttpRequest 与 fetch（含请求头）
    private val injectScript = """
        (function() {
            if (window.__sniffInjected) return;
            window.__sniffInjected = true;

            function safeCall(fn) {
                try { fn(); } catch(e) {}
            }

            function headersToText(hdrs) {
                try {
                    if (!hdrs) return '';
                    var parts = [];
                    if (typeof hdrs.forEach === 'function') {
                        hdrs.forEach(function(v, k){ parts.push(k + ': ' + v); });
                    } else if (Array.isArray(hdrs)) {
                        for (var i = 0; i < hdrs.length; i++) {
                            var h = hdrs[i];
                            parts.push((h[0] || '') + ': ' + (h[1] || ''));
                        }
                    } else {
                        for (var k in hdrs) {
                            if (Object.prototype.hasOwnProperty.call(hdrs, k)) {
                                parts.push(k + ': ' + hdrs[k]);
                            }
                        }
                    }
                    return parts.join('\n');
                } catch(e) { return ''; }
            }

            // 拦截 XMLHttpRequest
            var origOpen = XMLHttpRequest.prototype.open;
            var origSend = XMLHttpRequest.prototype.send;
            var origSetReqHeader = XMLHttpRequest.prototype.setRequestHeader;
            XMLHttpRequest.prototype.open = function(method, url) {
                this._sniffMethod = method || 'GET';
                this._sniffUrl = url || '';
                this._sniffHeaders = '';
                return origOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
                try {
                    if (typeof this._sniffHeaders !== 'string') this._sniffHeaders = '';
                    this._sniffHeaders += ((this._sniffHeaders ? '\n' : '') + name + ': ' + value);
                } catch(e) {}
                return origSetReqHeader.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function(body) {
                var self = this;
                this._sniffBody = body || '';
                this.addEventListener('load', function() {
                    safeCall(function() {
                        var respText = '';
                        try {
                            if (!self.responseType || self.responseType === 'text' || self.responseType === 'json') {
                                respText = self.responseText || '';
                            }
                        } catch(e) {}
                        if (respText && respText.length > 0) {
                            AndroidSniffer.onRequest(self._sniffMethod, self._sniffUrl, self._sniffBody, self._sniffHeaders || '', respText, self.status || 200);
                        }
                    });
                });
                return origSend.apply(this, arguments);
            };

            // 拦截 fetch
            var origFetch = window.fetch;
            if (origFetch) {
                window.fetch = function(input, init) {
                    var url = '';
                    var method = 'GET';
                    var body = '';
                    var headersText = '';
                    try {
                        if (typeof input === 'string') {
                            url = input;
                        } else if (input && input.url) {
                            url = input.url;
                            if (input.method) method = input.method;
                        }
                        if (init) {
                            if (init.method) method = init.method;
                            body = init.body || body;
                            headersText = headersToText(init.headers);
                        }
                        if (!headersText && input && input.headers) {
                            headersText = headersToText(input.headers);
                        }
                    } catch(e) {}
                    return origFetch.apply(this, arguments).then(function(response) {
                        safeCall(function() {
                            response.clone().text().then(function(text) {
                                if (text && text.length > 0) {
                                    AndroidSniffer.onRequest(method, url, body, headersText, text, response.status || 200);
                                }
                            }).catch(function(){});
                        });
                        return response;
                    });
                };
            }

            // 用户在页面内手动翻页/点击加载后，将 DOM 快照节流上报给原生层。
            // 不能使用纯防抖：持续动画/倒计时会不断重置 timer，导致永远没有快照上报。
            var domTimer = null;
            var domDirty = false;
            var lastDomSnapshot = '';
            function flushDomChanged() {
                domTimer = null;
                if (!domDirty) return;
                domDirty = false;
                safeCall(function() {
                    var html = document.documentElement ? document.documentElement.outerHTML : '';
                    if (!html || html === lastDomSnapshot) return;
                    lastDomSnapshot = html;
                    AndroidSniffer.onDomChanged(location.href || '', html);
                });
            }
            function reportDomChanged() {
                domDirty = true;
                if (!domTimer) domTimer = setTimeout(flushDomChanged, 1000);
            }
            window.__sniffReportDomChanged = reportDomChanged;

            // SPA 页面不会触发 WebView.onPageFinished，需要额外监听 History API 和前进/后退。
            var origPushState = history.pushState;
            var origReplaceState = history.replaceState;
            history.pushState = function() {
                var result = origPushState.apply(this, arguments);
                reportDomChanged();
                return result;
            };
            history.replaceState = function() {
                var result = origReplaceState.apply(this, arguments);
                reportDomChanged();
                return result;
            };
            window.addEventListener('popstate', reportDomChanged);
            window.addEventListener('hashchange', reportDomChanged);

            if (document.documentElement && window.MutationObserver) {
                var domObserver = new MutationObserver(function() { reportDomChanged(); });
                domObserver.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    characterData: true
                });
            }

            // 注入完成后主动上报首屏快照，覆盖刷新/跳转后 DOM 不再变化的页面。
            reportDomChanged();
            safeCall(function() { AndroidSniffer.onInjected(); });
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        val dialog = Dialog(context, R.style.Theme_CastTV_Dialog).apply {
            setContentView(buildContentView())
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            window?.apply {
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
            }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)) {
                    dismissAndClose()
                    true
                } else if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_MENU) {
                    openInExternalBrowser()
                    true
                } else if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // 模拟上滑：让 WebView 内容向下滚动，触发页面加载更多
                    webView?.scrollBy(0, 300)
                    onUserScroll()
                    true
                } else if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    webView?.scrollBy(0, -300)
                    true
                } else {
                    // OK/Enter 等按键交给 WebView，用户可手动点击网页中的翻页/加载控件。
                    false
                }
            }
        }
        this.dialog = dialog
        dialog.show()
        webView?.apply {
            requestFocus()
            loadUrl(pageUrl)
        }
    }

    private fun buildContentView(): View {
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(230, 8, 10, 14))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // 顶部状态栏
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = createPanelBg()
            clipChildren = false
            clipToPadding = false
        }

        val title = TextView(context).apply {
            text = if (entry == Entry.LIST) "资源嗅探 · 影片列表" else "资源嗅探 · 影片详情"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(245, 196, 81))
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        progressBar = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.GONE
            val size = dp(22)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) }
        }
        header.addView(progressBar)

        statusText = TextView(context).apply {
            text = "正在加载页面…"
            textSize = 13f
            setTextColor(Color.argb(200, 255, 255, 255))
            maxLines = 2
        }
        header.addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        container.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // WebView 区域
        val webContainer = FrameLayout(context).apply {
            background = createPanelBg()
            setPadding(dp(2), dp(2), dp(2), dp(2))
            clipChildren = true
            clipToPadding = true
        }

        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                loadsImagesAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = MOBILE_USER_AGENT
                cacheMode = WebSettings.LOAD_DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }
            // 允许记录 cookie 以便回放
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            CookieManager.getInstance().flush()
            // 注入 JS 拦截脚本
            addJavascriptInterface(SniffBridge(), "AndroidSniffer")
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    handler.post {
                        progressBar?.visibility = View.VISIBLE
                        updateStatus(if (entry == Entry.LIST) "页面刷新或跳转中，加载完成后将自动解析影片…" else "页面刷新或跳转中，加载完成后将自动解析播放地址…")
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // 覆盖 reload、History API 导航及同文档 URL 变化；脚本尚未注入时安全忽略。
                    view?.evaluateJavascript("window.__sniffReportDomChanged && window.__sniffReportDomChanged();", null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 页面加载完成后先注入自动化特征缓解脚本，再注入嗅探脚本。
                    view?.evaluateJavascript(automationMitigationScript, null)
                    view?.evaluateJavascript(injectScript, null)
                    CookieManager.getInstance().flush()
                    handler.post {
                        progressBar?.visibility = View.GONE
                        updateStatus(
                            if (entry == Entry.LIST) "页面已加载，请手动翻页或加载更多"
                            else "页面已加载，请手动切换剧集或加载资源"
                        )
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    return null
                }
            }
            webChromeClient = WebChromeClient()
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = true
        }
        webContainer.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(webContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部提示
        val footer = TextView(context).apply {
            text = if (entry == Entry.LIST) {
                "请在网页内手动翻页或加载更多 · 新影片会自动追加 · 按菜单键可改用系统浏览器 · 按 Back 退出"
            } else {
                "请在网页内手动切换/加载资源 · 新播放地址会自动追加 · 按菜单键可改用系统浏览器 · 按 Back 退出"
            }
            textSize = 12f
            setTextColor(Color.argb(150, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(container, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { setMargins(dp(24), dp(24), dp(24), dp(24)) })

        incrementBanner = TextView(context).apply {
            visibility = View.GONE
            alpha = 0f
            translationY = -dp(16).toFloat()
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 244, 194))
            setPadding(dp(20), dp(10), dp(20), dp(10))
            elevation = dp(16).toFloat()
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(242, 44, 38, 22))
                setStroke(dp(2), Color.rgb(245, 196, 81))
            }
        }.also { banner ->
            root.addView(banner, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(82) })
        }
        return root
    }

    /** 用户上滑/滚动后触发检查 */
    private fun onUserScroll() {
        scrollTriggerCount++
        handler.post {
            progressBar?.visibility = View.VISIBLE
            updateStatus("正在检查请求…")
        }
        // 延迟 1.5 秒后刷新状态（等待 JS 加载请求完成）
        handler.postDelayed({
            progressBar?.visibility = View.GONE
            reevaluateWinner()
        }, 1500)
    }

    private fun updateStatus(msg: String) {
        statusText?.text = msg
    }

    /** 在 WebView 顶部居中显示原生增量提醒，不注入网页 DOM，也不抢占遥控器焦点。 */
    private fun showIncrementBanner(count: Int) {
        if (count <= 0) return
        handler.post {
            val banner = incrementBanner ?: return@post
            bannerHideRunnable?.let { handler.removeCallbacks(it) }
            banner.animate().cancel()
            banner.text = "解析到 ${count} 条新数据"
            banner.visibility = View.VISIBLE
            banner.alpha = 0f
            banner.translationY = -dp(16).toFloat()
            banner.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .start()
            bannerHideRunnable = Runnable {
                banner.animate()
                    .alpha(0f)
                    .translationY(-dp(12).toFloat())
                    .setDuration(220L)
                    .withEndAction { banner.visibility = View.GONE }
                    .start()
            }.also { handler.postDelayed(it, 2200L) }
        }
    }

    private fun dismissAndClose() {
        bannerHideRunnable?.let { handler.removeCallbacks(it) }
        bannerHideRunnable = null
        incrementBanner?.animate()?.cancel()
        incrementBanner = null
        runCatching { CookieManager.getInstance().flush() }
        webView?.apply {
            stopLoading()
            removeJavascriptInterface("AndroidSniffer")
            destroy()
        }
        webView = null
        dialog?.dismiss()
        dialog = null
        winner?.let { onSniffed(it) }
        onClosed()
    }

    private fun openInExternalBrowser() {
        val targetUrl = webView?.url?.takeIf { it.isNotBlank() } ?: pageUrl
        runCatching { CookieManager.getInstance().flush() }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            Toast.makeText(context, "已切换到系统浏览器，请在浏览器中完成验证", Toast.LENGTH_SHORT).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "当前设备没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createPanelBg(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.argb(90, 22, 24, 30))
            setStroke(dp(1), Color.argb(60, 255, 255, 255))
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    /** JS 回调桥接 */
    private inner class SniffBridge {
        @JavascriptInterface
        fun onInjected() {
            handler.post {
                updateStatus(
                    if (entry == Entry.LIST) "请手动翻页或加载更多，DOM 变化后将自动解析影片"
                    else "请手动切换或加载资源，DOM 变化后将自动解析播放地址"
                )
            }
        }

        @JavascriptInterface
        fun onDomChanged(url: String, html: String) {
            if (html.isBlank()) return
            handler.post {
                if (dialog == null) return@post
                progressBar?.visibility = View.VISIBLE
                updateStatus(if (entry == Entry.LIST) "检测到页面变化，正在解析影片…" else "检测到页面变化，正在解析播放地址…")
                onDomSnapshot(url.ifBlank { pageUrl }, html) { count ->
                    handler.post {
                        progressBar?.visibility = View.GONE
                        if (count > 0) {
                            updateStatus("已追加 $count 条新数据，请继续操作网页或按 Back 退出")
                            showIncrementBanner(count)
                        } else {
                            updateStatus("页面已变化，暂未解析到新数据，请继续操作")
                        }
                    }
                }
            }
        }

        @JavascriptInterface
        fun onRequest(
            method: String,
            url: String,
            body: String,
            headersText: String,
            responseText: String,
            status: Int
        ) {
            if (entry != Entry.LIST || status !in 200..299) return
            // 过滤静态资源（图片/CSS/JS）
            val lowerUrl = url.lowercase()
            if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".png") || lowerUrl.endsWith(".gif") ||
                lowerUrl.endsWith(".css") || lowerUrl.endsWith(".js") || lowerUrl.endsWith(".ico") ||
                lowerUrl.endsWith(".woff") || lowerUrl.endsWith(".svg")
            ) return

            // 分析响应是否为影片列表 JSON
            val result = analyzeListResponse(responseText) ?: return
            val headers = parseHeaders(headersText)
            val cookie = safeGetCookie(url)
            val capture = Capture(url, method.uppercase(), body, headers, cookie, result)
            handler.post { addCapture(capture) }
        }
    }

    /** 新增一次捕获，重新评估最优候选 */
    private fun addCapture(c: Capture) {
        // 完全相同的 url+method 不重复加入
        if (captures.any { it.url == c.url && it.method == c.method }) return
        captures.add(c)
        reevaluateWinner()
    }

    /** 重新评估最优接口：优先 diff 确认的，其次单捕获启发式 */
    private fun reevaluateWinner() {
        val byEndpoint = captures.groupBy { endpointSignature(it) }
        // 1) 优先：同端点出现两次，diff 出翻页参数
        val confirmed = byEndpoint.values
            .filter { it.size >= 2 }
            .mapNotNull { list ->
                val a = list[list.size - 2]
                val b = list[list.size - 1]
                diffPaging(a, b)?.let { buildApi(a, it) }
            }
            .maxByOrNull { it.perPage }
        if (confirmed != null) {
            winner = confirmed
            val p = confirmed.paging ?: return
            updateStatus(
                "已确认接口：${shortUrl(confirmed.url)} | 翻页=${p.paramName ?: "path[${p.pathIndex}]"} " +
                    "步长${p.step} 每页${confirmed.perPage}条，按 Back 使用"
            )
            return
        }
        // 2) 单捕获启发式
        val single = captures.maxByOrNull { it.analysis.count }?.let { buildApi(it, heuristicPaging(it)) }
        winner = single
        val n = captures.size
        updateStatus(
            if (single == null) "未识别到列表接口，请继续上滑触发加载更多"
            else "已识别 $n 个候选接口，请继续上滑触发第 ${n + 1} 次加载以确认翻页参数（或按 Back 直接使用）"
        )
    }

    /** 端点签名：把 URL 中所有数字段替换为 #，使 page=2 与 page=3 归为同端点 */
    private fun endpointSignature(c: Capture): String {
        return c.method + " " + c.url.replace(Regex("\\d+"), "#")
    }

    /** 比较两次同端点请求，定位翻页参数 */
    private fun diffPaging(a: Capture, b: Capture): PagingInfo? {
        if (a.method != b.method) return null
        val ua = Uri.parse(a.url)
        val ub = Uri.parse(b.url)
        if (ua.path != ub.path) return null

        // query 参数对比：找数值不同者
        val names = (ua.queryParameterNames ?: emptySet()).intersect(ub.queryParameterNames ?: emptySet())
        for (name in names) {
            val va = ua.getQueryParameter(name)?.toIntOrNull() ?: continue
            val vb = ub.getQueryParameter(name)?.toIntOrNull() ?: continue
            if (va == vb) continue
            val step = abs(vb - va)
            val start = minOf(va, vb)
            return PagingInfo(
                template = buildTemplateQuery(a.url, name),
                paramName = name,
                location = "query",
                pathIndex = -1,
                step = step,
                startValue = start
            )
        }
        // path 段对比：找数值不同者
        val sa = ua.pathSegments
        val sb = ub.pathSegments
        if (sa.size == sb.size) {
            for (i in sa.indices) {
                val va = sa[i].toIntOrNull() ?: continue
                val vb = sb[i].toIntOrNull() ?: continue
                if (va == vb) continue
                val step = abs(vb - va)
                val start = minOf(va, vb)
                return PagingInfo(
                    template = buildTemplatePath(a.url, i),
                    paramName = null,
                    location = "path",
                    pathIndex = i,
                    step = step,
                    startValue = start
                )
            }
        }
        return null
    }

    /** 单捕获启发式：按参数名猜测翻页字段 */
    private fun heuristicPaging(c: Capture): PagingInfo? {
        val u = Uri.parse(c.url)
        // page 类：步长 1
        val pageNames = listOf("page", "p", "pageNo", "pageIndex", "pageNum", "pn")
        for (name in pageNames) {
            val v = u.getQueryParameter(name)?.toIntOrNull() ?: continue
            return PagingInfo(buildTemplateQuery(c.url, name), name, "query", -1, 1, v)
        }
        // offset 类：步长 = 每页条数
        val offsetNames = listOf("offset", "skip", "start")
        for (name in offsetNames) {
            val v = u.getQueryParameter(name)?.toIntOrNull() ?: continue
            val step = if (c.analysis.count > 0) c.analysis.count else 20
            return PagingInfo(buildTemplateQuery(c.url, name), name, "query", -1, step, v)
        }
        // path 数字段：步长 1
        val segs = u.pathSegments
        for (i in segs.indices) {
            val v = segs[i].toIntOrNull() ?: continue
            return PagingInfo(buildTemplatePath(c.url, i), null, "path", i, 1, v)
        }
        return null
    }

    /** 把 query 中指定参数的值替换为 {{PAGE}} */
    private fun buildTemplateQuery(url: String, name: String): String {
        val regex = Regex("([?&]" + Regex.escape(name) + "=)(\\d+)", RegexOption.IGNORE_CASE)
        return regex.replace(url) { "${it.groupValues[1]}{{PAGE}}" }
    }

    /** 把 path 中指定段的值替换为 {{PAGE}} */
    private fun buildTemplatePath(url: String, index: Int): String {
        val u = Uri.parse(url)
        val segs = u.pathSegments
        if (index !in segs.indices) return url
        val newSegs = segs.toMutableList()
        newSegs[index] = "{{PAGE}}"
        val port = if (u.port != -1) ":" + u.port else ""
        val query = if (u.encodedQuery != null) "?" + u.encodedQuery else ""
        val frag = if (u.encodedFragment != null) "#" + u.encodedFragment else ""
        return (u.scheme ?: "https") + "://" + (u.host ?: "") + port + "/" + newSegs.joinToString("/") + query + frag
    }

    private fun buildApi(c: Capture, paging: PagingInfo?): SniffedApi {
        return SniffedApi(
            url = c.url,
            method = c.method,
            headers = c.headers,
            cookie = c.cookie,
            body = c.body,
            dataPath = c.analysis.path,
            titleField = c.analysis.titleField,
            urlField = c.analysis.urlField,
            coverField = c.analysis.coverField,
            perPage = c.analysis.count,
            paging = paging
        )
    }

    private fun parseHeaders(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        text.split("\n").forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim()
                val v = line.substring(idx + 1).trim()
                if (k.isNotEmpty()) map[k] = v
            }
        }
        return map
    }

    private fun safeGetCookie(url: String): String {
        return try {
            CookieManager.getInstance().flush()
            CookieManager.getInstance().getCookie(url) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun shortUrl(u: String): String {
        return try {
            val p = Uri.parse(u)
            (p.host ?: "") + (p.path ?: "")
        } catch (e: Exception) {
            u
        }
    }

    /**
     * 分析响应文本是否为影片列表 JSON
     */
    private data class ListAnalysisResult(
        val path: String,
        val titleField: String,
        val urlField: String,
        val coverField: String,
        val count: Int
    )

    private fun analyzeListResponse(text: String): ListAnalysisResult? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return try {
            val json = if (trimmed.startsWith("[")) {
                JSONObject().put("__root__", org.json.JSONArray(trimmed))
            } else {
                JSONObject(trimmed)
            }
            findMovieArray(json, "__root__", maxDepth = 4)
        } catch (e: Exception) {
            null
        }
    }

    /** 递归查找 JSON 中的影片数组 */
    private fun findMovieArray(obj: JSONObject, currentPath: String, maxDepth: Int): ListAnalysisResult? {
        if (maxDepth <= 0) return null
        // 详情链接字段优先 detail-ish，避免命中封面/播放直链
        val titleFields = listOf("title", "name", "vod_name", "movieName", "filmName")
        val urlFields = listOf("detailUrl", "vod_url", "url", "link", "vod_play_url", "playUrl", "videoUrl")
        val coverFields = listOf("cover", "pic", "img", "image", "poster", "thumbnail", "vod_pic", "picUrl", "coverUrl")

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val path = if (currentPath == "__root__") key else "$currentPath.$key"
            val value = obj.opt(key) ?: continue

            if (value is org.json.JSONArray && value.length() >= 3) {
                // 检查数组元素是否像影片
                val first = value.optJSONObject(0) ?: continue
                val titleField = titleFields.firstOrNull { first.has(it) }
                val urlField = urlFields.firstOrNull { first.has(it) }
                if (titleField != null && urlField != null) {
                    val coverField = coverFields.firstOrNull { first.has(it) } ?: ""
                    return ListAnalysisResult(
                        path = if (path == "__root__") "" else path,
                        titleField = titleField,
                        urlField = urlField,
                        coverField = coverField,
                        count = value.length()
                    )
                }
            } else if (value is JSONObject) {
                val result = findMovieArray(value, path, maxDepth - 1)
                if (result != null) return result
            }
        }
        return null
    }
}
