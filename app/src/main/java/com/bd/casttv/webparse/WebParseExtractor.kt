package com.bd.casttv.webparse

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

class WebParseExtractor(
    private val context: Context? = null,
    private val progress: (ParseProgress) -> Unit = {}
) {
    constructor(progress: (ParseProgress) -> Unit = {}) : this(null, progress)

    private val adapters: List<SiteAdapter> = buildList {
        add(MacCmsAdapter())
        add(ZyPlayerAdapter())
        add(NemoAdapter())
        add(SnailCmsAdapter())
        context?.applicationContext?.let { add(RuleBasedAdapter(it)) }
        add(GenericAdapter())
    }
    var lastAdapter: SiteAdapter? = null
        private set

    var lastUsedRequestedAdapter: Boolean = false
        private set

    suspend fun extract(url: String): ParsedMovie = withContext(Dispatchers.IO) {
        progress(ParseProgress(ParseStep.RECEIVED, "接收到链接"))
        try {
            progress(ParseProgress(ParseStep.FETCHING_HTML, "正在获取详情页"))
            val html = fetchText(url)
            lastParsedUrl = url
            lastParsedHtml = html
            Log.d(TAG, "extract fetched url=$url htmlLength=${html.length}")
            progress(ParseProgress(ParseStep.PARSING_INFO, "正在解析影片信息"))
            val adapter = adapters.firstOrNull { it.canHandle(url, html) } ?: GenericAdapter()
            lastAdapter = adapter
            Log.d(TAG, "extract adapter=${adapter.javaClass.simpleName}")
            val movie = adapter.parseDetail(url, html)
            Log.d(TAG, "extract parsed title=${movie.title} sources=${movie.sources.size} episodeCounts=${movie.sources.map { it.episodes.size }}")
            progress(ParseProgress(ParseStep.LOADING_DONE, "加载完成"))
            movie
        } catch (t: Throwable) {
            progress(ParseProgress(ParseStep.ERROR, "解析失败", t.message ?: "未知错误"))
            throw t
        }
    }

    suspend fun extractWithRule(url: String, fileName: String): ParsedMovie = withContext(Dispatchers.IO) {
        val appContext = context?.applicationContext ?: error("当前页面不支持 JSON 规则解析")
        progress(ParseProgress(ParseStep.RECEIVED, "接收到链接"))
        try {
            progress(ParseProgress(ParseStep.FETCHING_HTML, "正在获取详情页"))
            val html = fetchText(url)
            lastParsedUrl = url
            lastParsedHtml = html
            progress(ParseProgress(ParseStep.PARSING_INFO, "正在使用 JSON 规则解析"))
            val adapter = RuleBasedAdapter(appContext, fileName)
            lastAdapter = adapter
            val movie = adapter.parseSpecified(url, html, fileName)
            progress(ParseProgress(ParseStep.LOADING_DONE, "加载完成"))
            movie
        } catch (t: Throwable) {
            progress(ParseProgress(ParseStep.ERROR, "解析失败", t.message ?: "未知错误"))
            throw t
        }
    }

    suspend fun extractWithHtml(url: String, html: String, fileName: String? = null, adapterId: String? = null): ParsedMovie = withContext(Dispatchers.Default) {
        val appContext = context?.applicationContext
        progress(ParseProgress(ParseStep.RECEIVED, "接收到 DOM 快照"))
        lastUsedRequestedAdapter = false
        try {
            lastParsedUrl = url
            lastParsedHtml = html
            progress(ParseProgress(ParseStep.PARSING_INFO, "正在解析影片信息"))

            // 尝试按指定 adapterId 强制解析
            if (adapterId != null) {
                val forcedAdapter = when (adapterId) {
                    BuiltInAdapters.ID_DETAIL_MAC_CMS -> adapters.filterIsInstance<MacCmsAdapter>().firstOrNull()
                    BuiltInAdapters.ID_DETAIL_GENERIC -> adapters.filterIsInstance<GenericAdapter>().firstOrNull()
                    else -> null
                }
                if (forcedAdapter != null) {
                    try {
                        val movie = forcedAdapter.parseDetail(url, html)
                        lastAdapter = forcedAdapter
                        lastUsedRequestedAdapter = true
                        progress(ParseProgress(ParseStep.LOADING_DONE, "加载完成"))
                        return@withContext movie
                    } catch (t: Throwable) {
                        Log.w(TAG, "强制使用绑定适配器 $adapterId 解析失败，将尝试自动识别: ${t.message}")
                    }
                }
            }

            val adapter = when {
                fileName != null && appContext != null -> {
                    val a = RuleBasedAdapter(appContext, fileName)
                    if (a.canHandle(url, html)) {
                        lastUsedRequestedAdapter = true
                    }
                    a
                }
                else -> adapters.firstOrNull { it.canHandle(url, html) } ?: GenericAdapter()
            }
            lastAdapter = adapter
            val movie = if (adapter is RuleBasedAdapter && fileName != null) {
                adapter.parseSpecified(url, html, fileName)
            } else {
                adapter.parseDetail(url, html)
            }
            progress(ParseProgress(ParseStep.LOADING_DONE, "加载完成"))
            movie
        } catch (t: Throwable) {
            progress(ParseProgress(ParseStep.ERROR, "解析失败", t.message ?: "未知错误"))
            throw t
        }
    }

    suspend fun resolve(playPageUrl: String): String? = withContext(Dispatchers.IO) {
        val adapter = lastAdapter ?: adapters.first()
        adapter.resolvePlayUrl(playPageUrl)
    }


    companion object {
        private const val TAG = "WebParseExtractor"
        @Volatile var lastParsedUrl: String? = null
        @Volatile var lastParsedHtml: String? = null

        fun fetchText(url: String, timeoutMs: Int = 15_000): String {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            }
            return try {
                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val bytes = BufferedInputStream(stream).use { it.readBytes() }
                Log.d(TAG, "fetchText url=$url status=$code bytes=${bytes.size} contentType=${conn.contentType}")
                val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                    .find(conn.contentType.orEmpty())?.groupValues?.getOrNull(1)?.trim('"')
                val cs = runCatching { charset?.let { CharsetCompat.forName(it) } }.getOrNull() ?: Charsets.UTF_8
                String(bytes, cs)
            } finally {
                conn.disconnect()
            }
        }
    }
}

private object CharsetCompat {
    fun forName(name: String): java.nio.charset.Charset = java.nio.charset.Charset.forName(name)
}
