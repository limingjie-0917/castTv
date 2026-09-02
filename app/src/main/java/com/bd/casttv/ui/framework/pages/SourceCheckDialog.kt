package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.favorites.TabChannel
import com.bd.casttv.favorites.TabChannelSource
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.ThemeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SourceCheckDialog(
    private val context: Context,
    private val channels: List<TabChannel>,
    private val callback: OnCheckResult,
) {
    interface OnCheckResult {
        fun onSkip()
        fun onFilter(unavailableIds: Set<String>)
    }

    private data class UrlProbeResult(
        val sourceId: String,
        val url: String,
        val success: Boolean,
        val elapsedMs: Long?,
    )

    private data class ChannelCheckResult(
        val updatedChannel: TabChannel,
        val availableCount: Int,
    )

    private data class FoldPanel(
        val root: LinearLayout,
        val header: LinearLayout,
        val titleView: TextView,
        val indicatorView: TextView,
        val scrollView: ScrollView,
        val contentView: LinearLayout,
        var expanded: Boolean = false,
    )

    private val palette get() = ThemeManager.currentPalette(context)
    private val warm get() = palette.accent
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val workingChannels: MutableList<TabChannel> = (channels as? MutableList<TabChannel>) ?: channels.toMutableList()

    private var dialog: AlertDialog? = null
    private var checkJob: kotlinx.coroutines.Job? = null
    private val unavailableIds = linkedSetOf<String>()
    private var checkedCount = 0
    // 异常频道记录（name + 失败源数）
    private val abnormalChannels = mutableListOf<Pair<String, Int>>()

    private var contentRoot: LinearLayout? = null
    private var summaryView: TextView? = null
    private var buttonHost: LinearLayout? = null
    // 内容切换区域（检测中 / 检测完成）
    private var contentArea: LinearLayout? = null
    private var loadingView: View? = null
    private var progressText: TextView? = null
    private var resultView: View? = null
    private var resultSummary: TextView? = null
    private var resultScrollView: ScrollView? = null
    private var resultListContainer: LinearLayout? = null

    fun show() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(20), dp(16), dp(20), dp(18))
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val contentInset = FrameLayout(context).apply {
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        contentRoot = content
        val summary = messageView("")
        summaryView = summary

        // 内容切换区域（检测中 / 检测完成），默认 GONE
        val area = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }
        contentArea = area

        // 检测中视图：圆环 loading + 进度文案
        val loading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(30), 0, dp(30))
        }
        val progressBar = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = true
            indeterminateDrawable?.setColorFilter(warm, android.graphics.PorterDuff.Mode.SRC_IN)
        }
        val progText = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.argb(210, 220, 225, 235))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
            text = "检测中（0/${workingChannels.size}）"
        }
        progressText = progText
        loading.addView(progressBar, LinearLayout.LayoutParams(dp(52), dp(52)))
        loading.addView(progText, lpMatch())
        loadingView = loading

        // 检测完成视图：异常频道数量 + 列表
        val resultLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(20), 0, dp(10))
        }
        val summaryTv = TextView(context).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(238, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(14))
        }
        resultSummary = summaryTv
        val listScroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(132, 18, 22, 30))
            }
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        resultListContainer = listContainer
        resultScrollView = listScroll
        listScroll.addView(listContainer, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        resultLayout.addView(summaryTv, lpMatch())
        resultLayout.addView(listScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        resultView = resultLayout

        area.addView(loading, lpMatch())
        area.addView(resultLayout, lpMatch())

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        buttonHost = buttons

        content.addView(titleView("播放源检测"))
        content.addView(summary, lpMatch().apply { topMargin = dp(18) })
        content.addView(area, lpMatch().apply { topMargin = dp(14) })
        content.addView(buttons, lpMatch().apply { topMargin = dp(22) })
        contentInset.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(contentInset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
            .also { d ->
                d.setOnDismissListener {
                    checkJob?.cancel()
                    scope.cancel()
                }
                d.setOnShowListener { renderAskStage() }
                d.show()
                d.window?.apply {
                    setGravity(Gravity.CENTER)
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setLayout(dp(680), WindowManager.LayoutParams.WRAP_CONTENT)
                }
            }
    }

    private fun renderAskStage() {
        checkJob?.cancel()
        checkedCount = 0
        unavailableIds.clear()
        abnormalChannels.clear()
        summaryView?.text = "是否检测频道可用性？不可用的频道将在本次会话中被隐藏"
        contentArea?.visibility = View.GONE
        val skip = dialogButton("跳过") {
            callback.onSkip()
            dialog?.dismiss()
        }
        val start = dialogButton("开始检测") { startChecking() }
        setButtons(skip, start)
    }

    private fun startChecking() {
        checkJob?.cancel()
        checkedCount = 0
        unavailableIds.clear()
        abnormalChannels.clear()
        // 切换到检测中视图
        contentArea?.visibility = View.VISIBLE
        loadingView?.visibility = View.VISIBLE
        resultView?.visibility = View.GONE
        progressText?.text = "检测中（0/${workingChannels.size}）"
        summaryView?.text = "正在逐频道检测，并会把响应最快的可用源自动设为主源。"
        val skip = dialogButton("跳过") {
            checkJob?.cancel()
            callback.onSkip()
            dialog?.dismiss()
        }
        setButtons(skip)

        checkJob = scope.launch {
            try {
                for (index in workingChannels.indices) {
                    ensureActive()
                    val channel = workingChannels[index]
                    val result = detectSingleChannel(channel, index)
                    withContext(Dispatchers.Main) {
                        checkedCount++
                        replaceChannel(index, result.updatedChannel)
                        if (result.availableCount == 0) {
                            unavailableIds += channelId(result.updatedChannel)
                            abnormalChannels.add(result.updatedChannel.displayName to result.availableCount)
                        } else {
                            // 有可用源但部分源失败也算异常
                            val totalSources = result.updatedChannel.playableSources.count { it.item.uri.trim().isNotBlank() }
                            if (result.availableCount < totalSources) {
                                val failedCount = totalSources - result.availableCount
                                abnormalChannels.add(result.updatedChannel.displayName to failedCount)
                            }
                        }
                        progressText?.text = "检测中（${checkedCount}/${workingChannels.size}）"
                    }
                }
                withContext(Dispatchers.Main) { showCompletedStage() }
            } catch (_: CancellationException) {
                // 跳过或 dismiss 时直接结束，不再切到完成态。
            }
        }
    }

    private suspend fun detectSingleChannel(channel: TabChannel, index: Int): ChannelCheckResult = coroutineScope {
        val playableSources = channel.playableSources.filter { it.item.uri.trim().isNotBlank() }
        if (playableSources.isEmpty()) return@coroutineScope ChannelCheckResult(channel, 0)

        val results = playableSources.map { source ->
            async(Dispatchers.IO) {
                probeSource(source)
            }
        }.awaitAll()

        ChannelCheckResult(
            updatedChannel = reorderChannel(channel, results),
            availableCount = results.count { it.success },
        )
    }

    private fun showCompletedStage() {
        // 切换到检测完成视图
        loadingView?.visibility = View.GONE
        resultView?.visibility = View.VISIBLE
        summaryView?.text = ""

        val total = workingChannels.size
        val abnormalCount = abnormalChannels.size
        val normalCount = total - abnormalCount

        resultSummary?.text = if (abnormalCount == 0) {
            "本次检测共发现 0 个播放源异常频道\n全部 $total 个频道均可正常播放"
        } else {
            "本次检测共发现 $abnormalCount 个播放源异常频道\n$normalCount 个频道播放正常"
        }

        // 填充异常频道列表
        resultListContainer?.apply {
            removeAllViews()
            if (abnormalChannels.isEmpty()) {
                addView(statusLine("✅ 全部频道播放源正常", success = true))
            } else {
                abnormalChannels.forEach { (name, failedCount) ->
                    val text = if (failedCount == 0) {
                        "❌ $name · 全部播放源失败"
                    } else {
                        "⚠ $name · $failedCount 个播放源异常"
                    }
                    addView(statusLine(text, failure = failedCount == 0))
                }
            }
        }

        val keepAll = dialogButton("不过滤") {
            callback.onSkip()
            dialog?.dismiss()
        }
        val filter = if (unavailableIds.isNotEmpty()) {
            dialogButton("过滤不可用") {
                callback.onFilter(unavailableIds.toSet())
                dialog?.dismiss()
            }
        } else null
        if (filter != null) setButtons(keepAll, filter) else setButtons(keepAll)
    }

    private fun reorderChannel(channel: TabChannel, results: List<UrlProbeResult>): TabChannel {
        val successResults = results.filter { it.success }
        if (successResults.isEmpty()) return channel

        val successCost = successResults.associate { it.sourceId to (it.elapsedMs ?: Long.MAX_VALUE) }
        val playableOrder = channel.playableSources.withIndex().associate { it.value.sourceId to it.index }
        val sortedPlayable = channel.playableSources.sortedWith(
            compareBy<TabChannelSource>(
                { if (it.sourceId in successCost) 0 else 1 },
                { successCost[it.sourceId] ?: Long.MAX_VALUE },
                { playableOrder[it.sourceId] ?: Int.MAX_VALUE },
            ),
        )
        val newPrimaryId = sortedPlayable.firstOrNull { it.sourceId in successCost }?.sourceId
        val rebuiltPlayable = sortedPlayable.map { source ->
            source.copy(isPrimary = source.sourceId == newPrimaryId)
        }
        val nonPlayable = channel.sources
            .filterNot { it.playable }
            .map { it.copy(isPrimary = false) }
        return channel.copy(sources = rebuiltPlayable + nonPlayable)
    }

    private fun replaceChannel(index: Int, updated: TabChannel) {
        if (index in workingChannels.indices) {
            workingChannels[index] = updated
        }
    }

    private fun createFoldPanel(title: String): FoldPanel {
        val titleView = TextView(context).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(244, 244, 244))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val indicatorView = TextView(context).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
            text = "▶"
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panelHeaderBg(false)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(indicatorView)
        }
        val contentView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(10))
            clipChildren = false
            clipToPadding = false
        }
        val scrollView = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(dp(180), View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = true
            clipToPadding = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.argb(132, 18, 22, 30))
            }
            visibility = View.GONE
            addView(contentView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = panelCardBg(false)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            clipChildren = false
            clipToPadding = false
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        header.setOnFocusChangeListener { _, hasFocus ->
            root.background = panelCardBg(hasFocus)
            header.background = panelHeaderBg(hasFocus)
            titleView.setTextColor(if (hasFocus) warm else Color.rgb(244, 244, 244))
            FocusFxHelper.applyFocusFxState(root, hasFocus, cornerRadiusDp = 8)
        }
        val panel = FoldPanel(root, header, titleView, indicatorView, scrollView, contentView)
        header.setOnClickListener { setPanelExpanded(panel, !panel.expanded) }
        return panel
    }

    private fun setPanelTitle(panel: FoldPanel?, title: String) {
        panel?.titleView?.text = title
    }

    private fun setPanelExpanded(panel: FoldPanel, expanded: Boolean) {
        panel.expanded = expanded
        panel.scrollView.visibility = if (expanded) View.VISIBLE else View.GONE
        panel.indicatorView.text = if (expanded) "▼" else "▶"
    }

    private fun setButtons(vararg buttons: View) {
        val host = buttonHost ?: return
        host.removeAllViews()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
            clipToPadding = false
        }
        buttons.forEachIndexed { index, button ->
            row.addView(
                button,
                LinearLayout.LayoutParams(if (buttons.size == 1) dp(128) else dp(138), dp(42)).apply {
                    if (index > 0) marginStart = dp(14)
                },
            )
        }
        host.addView(row, lpMatch())
        val focusables = mutableListOf<View>().apply {
            buttons.forEach { add(it) }
        }
        contentRoot?.let { bindBoundary(it, focusables) }
    }

    private fun probeSource(source: TabChannelSource): UrlProbeResult {
        val rawUrl = source.item.uri.trim()
        val start = SystemClock.elapsedRealtime()
        val success = runCatching { checkUrl(rawUrl, preferHead = true) }.getOrDefault(false)
        val elapsed = SystemClock.elapsedRealtime() - start
        return UrlProbeResult(
            sourceId = source.sourceId,
            url = rawUrl,
            success = success,
            elapsedMs = if (success) elapsed else null,
        )
    }

    private fun checkUrl(raw: String, preferHead: Boolean): Boolean {
        val conn = (URL(raw).openConnection() as HttpURLConnection).apply {
            requestMethod = if (preferHead) "HEAD" else "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CastTV-Receiver/SourceCheck")
        }
        return try {
            val code = conn.responseCode
            when {
                code in 200..299 -> true
                preferHead && raw.lowercase().contains(".m3u8") -> checkUrl(raw, preferHead = false)
                preferHead && code == HttpURLConnection.HTTP_BAD_METHOD -> checkUrl(raw, preferHead = false)
                else -> false
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun updateStatusLine(view: TextView, result: UrlProbeResult) {
        when {
            result.success -> {
                view.text = "✅ ${result.url} (${result.elapsedMs ?: 0}ms)"
                view.setTextColor(Color.rgb(206, 247, 210))
            }
            else -> {
                view.text = "❌ ${result.url}"
                view.setTextColor(Color.rgb(255, 198, 198))
            }
        }
    }

    private fun buildCheckingText(url: String): String = "⏳ $url"

    private fun statusLine(text: String, success: Boolean = false, failure: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(
            when {
                success -> Color.rgb(206, 247, 210)
                failure -> Color.rgb(255, 198, 198)
                else -> Color.rgb(235, 235, 235)
            },
        )
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        isFocusable = false
        isClickable = false
    }

    private fun infoLine(text: String): TextView = statusLine(text).apply {
        tag = "placeholder"
        setTextColor(Color.argb(210, 220, 225, 235))
        maxLines = 3
        ellipsize = null
    }

    private fun channelId(channel: TabChannel): String {
        return channel.activeSource?.sourceId
            ?: channel.activeSource?.item?.uri?.trim().orEmpty().ifBlank { channel.channelKey }
    }

    private fun titleView(title: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(
            ClippedImageView(context).apply {
                setCircle(true)
                setImageResource(R.drawable.sticker_shinchan)
                scaleType = ImageView.ScaleType.CENTER_CROP
                foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
            },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) },
        )
        addView(
            TextView(context).apply {
                text = title
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(warm)
                gravity = Gravity.CENTER_VERTICAL
                setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun messageView(msg: String): TextView = TextView(context).apply {
        text = msg
        textSize = 16f
        setTextColor(Color.rgb(245, 245, 245))
        gravity = Gravity.CENTER_VERTICAL
        setLineSpacing(dp(2).toFloat(), 1.0f)
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            setTextColor(Color.rgb(238, 238, 238))
            background = GradientDrawable().apply {
                cornerRadius = dp(60).toFloat()
                setColor(Color.argb(51, 40, 40, 40))
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has ->
            refresh(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 60)
        }
        setOnClickListener { click() }
    }

    private fun bindBoundary(root: ViewGroup, focusables: List<View>) {
        val listener = View.OnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            val direction = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                else -> return@OnKeyListener false
            }
            val next = view.focusSearch(direction)
            if (next != null && next !== view && next.visibility == View.VISIBLE && next.isFocusable && isChildOf(next, root)) {
                return@OnKeyListener false
            }
            BoundaryFocusHandler.shake(view)
            true
        }
        focusables.forEach { it.setOnKeyListener(listener) }
    }

    private fun isChildOf(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun panelBg() = GradientDrawable(GradientDrawable.Orientation.TL_BR, palette.dialogTitleGradient).apply {
        cornerRadius = dp(26).toFloat()
    }

    private fun panelCardBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(Color.argb(44, 16, 18, 24))
        setStroke(dp(if (focused) 2 else 1), if (focused) warm else Color.argb(130, 210, 214, 222))
    }

    private fun panelHeaderBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(if (focused) Color.argb(96, 28, 32, 40) else Color.argb(72, 24, 28, 36))
    }

    private fun lpMatch() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
