package com.bd.casttv.webparse

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.bd.casttv.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 资源嗅探弹窗：当列表页无法提取 nextPageUrl 时，通过 WebView 加载页面，
 * 注入 JS 拦截 XHR/fetch 请求，识别影片列表数据接口。
 *
 * 交互流程：
 * 1. 用户在 WebView 中上滑触发页面 JS 加载更多
 * 2. App 拦截所有 XHR/fetch 请求及响应
 * 3. 分析响应是否为影片列表 JSON（含 title + url 字段数组）
 * 4. 每次上滑后给出结果提示（未识别到/已识别）
 * 5. 识别成功后 Toast 提示并关闭弹窗，回调返回接口信息
 */
class ResourceSniffDialog(
    private val context: Context,
    private val listUrl: String,
    private val onSniffed: (SniffedApi) -> Unit,
    private val onClosed: () -> Unit = {}
) {
    /** 嗅探到的影片列表接口信息 */
    data class SniffedApi(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String,
        /** 响应中影片数组所在的 JSON 路径，如 "data.list" / "results" / "list" */
        val dataPath: String,
        /** 影片标题字段名 */
        val titleField: String,
        /** 影片详情链接字段名 */
        val urlField: String,
        /** 影片封面字段名（可选） */
        val coverField: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    private var webView: WebView? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var sniffedApi: SniffedApi? = null
    private var scrollTriggerCount = 0

    // JS 注入脚本：hook XMLHttpRequest 和 fetch
    private val injectScript = """
        (function() {
            if (window.__sniffInjected) return;
            window.__sniffInjected = true;

            function safeCall(fn) {
                try { fn(); } catch(e) {}
            }

            // 拦截 XMLHttpRequest
            var origOpen = XMLHttpRequest.prototype.open;
            var origSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this._sniffMethod = method || 'GET';
                this._sniffUrl = url || '';
                return origOpen.apply(this, arguments);
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
                            AndroidSniffer.onRequest(self._sniffMethod, self._sniffUrl, self._sniffBody, respText, self.status || 200);
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
                    try {
                        if (typeof input === 'string') {
                            url = input;
                        } else if (input && input.url) {
                            url = input.url;
                        }
                        if (init) {
                            method = init.method || 'GET';
                            body = init.body || '';
                        }
                    } catch(e) {}
                    return origFetch.apply(this, arguments).then(function(response) {
                        safeCall(function() {
                            response.clone().text().then(function(text) {
                                if (text && text.length > 0) {
                                    AndroidSniffer.onRequest(method, url, body, text, response.status || 200);
                                }
                            }).catch(function(){});
                        });
                        return response;
                    });
                };
            }

            // 通知原生层注入完成
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
                } else if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // 模拟上滑：让 WebView 滚动
                    webView?.scrollBy(0, 300)
                    onUserScroll()
                    true
                } else if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    webView?.scrollBy(0, -300)
                    true
                } else if (event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    // OK 键也触发滚动
                    webView?.scrollBy(0, 400)
                    onUserScroll()
                    true
                } else {
                    false
                }
            }
        }
        this.dialog = dialog
        dialog.show()
        // 加载列表页 URL
        webView?.loadUrl(listUrl)
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
            text = "资源嗅探"
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
            maxLines = 1
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
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            }
            // 注入 JS 拦截脚本
            addJavascriptInterface(SniffBridge(), "AndroidSniffer")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 页面加载完成后注入拦截脚本
                    view?.evaluateJavascript(injectScript, null)
                    handler.post {
                        progressBar?.visibility = View.GONE
                        updateStatus("页面已加载，请按↓键或OK键上滑触发加载更多")
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
            text = "按↓/OK键上滑触发加载 · 按 Back 键退出嗅探"
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
        return root
    }

    /** 用户上滑/滚动后触发检查 */
    private fun onUserScroll() {
        scrollTriggerCount++
        handler.post {
            progressBar?.visibility = View.VISIBLE
            updateStatus("正在检查请求…")
        }
        // 延迟 1.5 秒后检查嗅探结果（等待 JS 加载请求完成）
        handler.postDelayed({
            progressBar?.visibility = View.GONE
            val api = sniffedApi
            if (api != null) {
                updateStatus("已识别到列表接口")
                Toast.makeText(context, "已获取到刷新接口，关闭嗅探弹窗", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ dismissAndClose() }, 800)
            } else {
                updateStatus("未识别到列表接口，请继续上滑触发加载更多")
            }
        }, 1500)
    }

    private fun updateStatus(msg: String) {
        statusText?.text = msg
    }

    private fun dismissAndClose() {
        webView?.apply {
            stopLoading()
            removeJavascriptInterface("AndroidSniffer")
            destroy()
        }
        webView = null
        dialog?.dismiss()
        dialog = null
        sniffedApi?.let { onSniffed(it) }
        onClosed()
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
                updateStatus("已注入嗅探脚本，请上滑触发加载更多")
            }
        }

        @JavascriptInterface
        fun onRequest(method: String, url: String, body: String, responseText: String, status: Int) {
            if (sniffedApi != null) return // 已识别，跳过
            if (status !in 200..299) return
            // 过滤静态资源（图片/CSS/JS）
            val lowerUrl = url.lowercase()
            if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".png") || lowerUrl.endsWith(".gif") ||
                lowerUrl.endsWith(".css") || lowerUrl.endsWith(".js") || lowerUrl.endsWith(".ico") ||
                lowerUrl.endsWith(".woff") || lowerUrl.endsWith(".svg")
            ) return

            // 分析响应是否为影片列表 JSON
            val result = analyzeListResponse(responseText, url) ?: return
            sniffedApi = SniffedApi(
                url = url,
                method = method.uppercase(),
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                ),
                body = body,
                dataPath = result.path,
                titleField = result.titleField,
                urlField = result.urlField,
                coverField = result.coverField
            )
            handler.post {
                progressBar?.visibility = View.GONE
                updateStatus("已识别到列表接口：$url")
            }
        }
    }

    /**
     * 分析响应文本是否为影片列表 JSON
     * @return Triple(path, titleField, urlField, coverField) 或 null
     */
    private data class ListAnalysisResult(
        val path: String,
        val titleField: String,
        val urlField: String,
        val coverField: String
    )

    private fun analyzeListResponse(text: String, requestUrl: String): ListAnalysisResult? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        return try {
            val json = if (trimmed.startsWith("[")) {
                // 直接是数组
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
        val titleFields = listOf("title", "name", "vod_name", "movieName", "filmName")
        val urlFields = listOf("url", "link", "detailUrl", "playUrl", "vod_url", "vod_play_url", "videoUrl", "cover", "pic")
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
                        coverField = coverField
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
