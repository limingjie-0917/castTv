package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.sync.GiteeApi
import com.bd.casttv.sync.GiteeShareStore
import com.bd.casttv.sync.GiteeShareStore.SharedRecord
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.CreatorIdProvider
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.webparse.WebParseHtml
import com.bd.casttv.webparse.WebParseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * 上传收藏到云端弹窗：
 * - 展示本地收藏记录，已上传的标记「已共享」+「取消共享」按钮
 * - 多选记录，底部按钮：全选、取消勾选、上传、取消
 */
class WebParseShareDialog(
    private val context: Context,
    private val onClosed: () -> Unit = {}
) {
    private val warm: Int get() = ThemeManager.currentPalette(context).accent
    private val store = WebParseStore(context)
    private var dialog: AlertDialog? = null

    // 选中的记录索引
    private val selected = mutableSetOf<Int>()
    // 已共享的记录索引（云端已存在）
    private val sharedInCloud = mutableSetOf<Int>()
    // 记录行视图
    private val rowViews = mutableListOf<View>()
    // 云端全部记录（用于取消共享时重建索引和判断适配器引用）
    private var allCloudRecords = emptyList<SharedRecord>()
    // 本用户云端记录的 url -> SharedRecord 映射
    private val cloudRecordMap = mutableMapOf<String, SharedRecord>()

    fun show() {
        val histories = store.getParseHistory()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bottomSheetPanelBg()
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

        content.addView(titleView())

        // 状态提示
        val statusView = TextView(context).apply {
            text = "正在拉取云端数据..."
            textSize = 13f
            setTextColor(Color.argb(180, 255, 255, 255))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        content.addView(statusView)

        // loading 指示器
        val loadingBar = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        content.addView(loadingBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            topMargin = dp(20)
            bottomMargin = dp(20)
        })

        // 记录列表容器（加载完成后填充）
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
        }
        val scroll = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            clipChildren = true
            clipToPadding = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(listContainer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))

        // 底部按钮
        val cancelButton = dialogButton("取消") { dialog?.dismiss() }
        val uploadButton = dialogButton("上传", warning = false) {
            performUpload(histories)
        }
        val selectAllButton = dialogButton("全选") {
            toggleAll(histories, true)
        }
        val deselectButton = dialogButton("取消勾选") {
            toggleAll(histories, false)
        }
        val countTip = TextView(context).apply {
            text = ""
            textSize = 13f
            setTextColor(warm)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }
        uploadCountTip = countTip
        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        bottomBar.addView(countTip, LinearLayout.LayoutParams(0, dp(40), 1f))
        bottomBar.addView(selectAllButton, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginEnd = dp(8) })
        bottomBar.addView(deselectButton, LinearLayout.LayoutParams(dp(110), dp(40)).apply { marginEnd = dp(8) })
        bottomBar.addView(uploadButton, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginEnd = dp(8) })
        bottomBar.addView(cancelButton, LinearLayout.LayoutParams(dp(100), dp(40)))
        content.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        contentInset.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(contentInset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val focusRows = mutableListOf<View>()
        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnShowListener {
                cancelButton.requestFocus()
                // 后台拉取云端索引
                fetchCloudIndex(histories, statusView, loadingBar, listContainer, scroll, focusRows, selectAllButton, deselectButton, uploadButton)
            }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(720), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun fetchCloudIndex(
        histories: List<WebParseStore.ParseHistory>,
        statusView: TextView,
        loadingBar: ProgressBar,
        listContainer: LinearLayout,
        scroll: ScrollView,
        focusRows: MutableList<View>,
        selectAllButton: View,
        deselectButton: View,
        uploadButton: View
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val creatorId = withContext(Dispatchers.IO) { CreatorIdProvider.get(context) }
            val result = withContext(Dispatchers.IO) { GiteeShareStore.fetchCloudIndex() }

            loadingBar.visibility = View.GONE

            when (result) {
                is GiteeApi.ApiResult.Success -> {
                    allCloudRecords = result.value.records
                    cloudRecordMap.clear()
                    result.value.records
                        .filter { it.creatorId == creatorId }
                        .forEach { cloudRecordMap[it.url] = it }
                    val cloudUrls = cloudRecordMap.keys

                    // 标记已共享的记录
                    histories.forEachIndexed { index, history ->
                        if (cloudUrls.contains(history.url)) {
                            sharedInCloud.add(index)
                        }
                    }

                    val sharedCount = sharedInCloud.size
                    val shareableCount = histories.size - sharedCount
                    statusView.text = "本地 ${histories.size} 条 · 已共享 $sharedCount 条 · 可共享 $shareableCount 条"
                    statusView.setTextColor(if (shareableCount > 0) warm else Color.argb(180, 255, 255, 255))

                    if (histories.isEmpty()) {
                        statusView.text = "暂无解析记录可共享"
                        listContainer.visibility = View.GONE
                        return@launch
                    }

                    // 填充记录列表
                    listContainer.visibility = View.VISIBLE
                    rowViews.clear()
                    histories.forEachIndexed { index, history ->
                        val isShared = sharedInCloud.contains(index)
                        val row = shareRow(history, index, isShared) {
                            if (isShared) {
                                showCancelShareConfirm(history)
                            } else {
                                if (selected.contains(index)) selected.remove(index) else selected.add(index)
                                updateRowSelection(index)
                                updateUploadButton(uploadButton, histories)
                            }
                        }
                        rowViews.add(row)
                        focusRows.add(row)
                        listContainer.addView(row, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(if (history.pageType.isBlank()) 66 else 86)
                        ).apply { topMargin = if (index == 0) dp(8) else dp(6) })
                    }

                    // 焦点边界绑定
                    val allFocusable = focusRows + listOf(selectAllButton, deselectButton, uploadButton)
                    bindBoundary(scroll, allFocusable)

                    selectAllButton.isFocusable = shareableCount > 0
                    deselectButton.isFocusable = selected.isNotEmpty()
                    updateUploadButton(uploadButton, histories)
                }
                is GiteeApi.ApiResult.Error -> {
                    statusView.text = "云端数据拉取失败：${result.message}"
                    statusView.setTextColor(Color.rgb(255, 138, 128))
                    listContainer.visibility = View.GONE
                }
                is GiteeApi.ApiResult.NotFound -> {
                    // 云端无数据，全部可共享
                    statusView.text = "本地 ${histories.size} 条 · 全部可共享"
                    statusView.setTextColor(warm)
                    if (histories.isEmpty()) {
                        statusView.text = "暂无解析记录可共享"
                        return@launch
                    }
                    listContainer.visibility = View.VISIBLE
                    rowViews.clear()
                    histories.forEachIndexed { index, history ->
                        val row = shareRow(history, index, false) {
                            if (selected.contains(index)) selected.remove(index) else selected.add(index)
                            updateRowSelection(index)
                            updateUploadButton(uploadButton, histories)
                        }
                        rowViews.add(row)
                        focusRows.add(row)
                        listContainer.addView(row, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(if (history.pageType.isBlank()) 66 else 86)
                        ).apply { topMargin = if (index == 0) dp(8) else dp(6) })
                    }
                    val allFocusable = focusRows + listOf(selectAllButton, deselectButton, uploadButton)
                    bindBoundary(scroll, allFocusable)
                    updateUploadButton(uploadButton, histories)
                }
            }
        }
    }

    private fun shareRow(
        history: WebParseStore.ParseHistory,
        index: Int,
        isShared: Boolean,
        click: () -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rowBg(false, isShared)
        setOnFocusChangeListener { v, has ->
            background = rowBg(has, isShared)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 14)
        }
        setOnClickListener { click() }

        // 顶部行：选中标记 + 类型标签 + 标题 + 取消共享按钮
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // 选中标记
        val marker = TextView(context).apply {
            text = if (isShared) "已共享" else if (selected.contains(index)) "✓" else "○"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isShared) Color.argb(120, 255, 255, 255) else warm)
            setPadding(0, 0, dp(8), 0)
        }
        topRow.addView(marker, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        // 类型标签
        pageTypeTag(history.pageType)?.let { tag ->
            topRow.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22)).apply { marginEnd = dp(8) })
        }
        topRow.addView(TextView(context).apply {
            text = displayTitle(history)
            textSize = 15f
            setTextColor(if (isShared) Color.argb(130, 255, 255, 255) else Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 已共享记录：右侧显示「取消共享」按钮
        if (isShared) {
            val cancelBtn = TextView(context).apply {
                text = "取消共享"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.argb(180, 255, 138, 128))
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), Color.argb(160, 255, 138, 128))
                    setColor(Color.argb(28, 255, 138, 128))
                }
            }
            topRow.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply { marginStart = dp(8) })
        }
        addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 底部摘要行
        val summary = buildList {
            add(WebParseHtml.shortUrl(history.url))
            if (history.frameworkType.isNotBlank()) add("框架：${history.frameworkType}")
            if (history.adapterName.isNotBlank()) add("适配器：${history.adapterName}")
            val isBuiltIn = history.adapterId.isBlank() || GiteeShareStore.isBuiltInAdapter(history.adapterId)
            add(if (isBuiltIn) "内置适配器" else "自定义适配器")
        }.joinToString("  ·  ")
        addView(TextView(context).apply {
            text = summary
            textSize = 12f
            setTextColor(Color.argb(if (isShared) 90 else 190, 255, 255, 255))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(56), dp(4), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun updateRowSelection(index: Int) {
        if (index >= rowViews.size) return
        val row = rowViews[index] as? LinearLayout ?: return
        val topRow = row.getChildAt(0) as? LinearLayout ?: return
        val marker = topRow.getChildAt(0) as? TextView ?: return
        marker.text = if (selected.contains(index)) "✓" else "○"
        marker.setTextColor(warm)
    }

    private var uploadCountTip: TextView? = null

    private fun updateUploadButton(button: View, histories: List<WebParseStore.ParseHistory>) {
        val customCount = selected.count { index ->
            val h = histories.getOrNull(index) ?: return@count false
            h.adapterId.isNotBlank() && !GiteeShareStore.isBuiltInAdapter(h.adapterId)
        }
        button.isEnabled = selected.isNotEmpty()
        button.alpha = if (selected.isEmpty()) 0.4f else 1f
        uploadCountTip?.text = if (selected.isEmpty()) "" else "已选 ${selected.size} 条 · ${customCount} 个自定义适配器"
    }

    private fun toggleAll(histories: List<WebParseStore.ParseHistory>, select: Boolean) {
        selected.clear()
        if (select) {
            histories.forEachIndexed { index, _ ->
                if (!sharedInCloud.contains(index)) selected.add(index)
            }
        }
        // 更新所有行的选中标记
        histories.forEachIndexed { index, _ ->
            updateRowSelection(index)
        }
    }

    private fun performUpload(histories: List<WebParseStore.ParseHistory>) {
        if (selected.isEmpty()) {
            Toast.makeText(context, "请先选择要共享的记录", Toast.LENGTH_SHORT).show()
            return
        }

        val items = histories.mapIndexed { index, history ->
            GiteeShareStore.UploadItem(history, selected.contains(index))
        }

        // 显示上传中弹窗
        val progressPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bottomSheetPanelBg()
            setPadding(dp(40), dp(30), dp(40), dp(30))
        }
        val progressText = TextView(context).apply {
            text = "正在上传到云端..."
            textSize = 15f
            setTextColor(warm)
            gravity = Gravity.CENTER
        }
        val progressBar = ProgressBar(context).apply { isIndeterminate = true }
        progressPanel.addView(progressText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
        progressPanel.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val progressDialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(progressPanel).create().also { d ->
            d.setCancelable(false)
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(360), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { GiteeShareStore.uploadShared(context, items) }
            progressDialog.dismiss()

            when (result) {
                is GiteeApi.ApiResult.Success -> {
                    val r = result.value
                    val msg = buildString {
                        append("成功上传 ${r.successCount} 条记录")
                        if (r.adaptersUploaded > 0) append("，${r.adaptersUploaded} 个适配器")
                        if (r.failCount > 0) append("\n失败 ${r.failCount} 条")
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    selected.clear()
                    dialog?.dismiss()
                    onClosed()
                }
                is GiteeApi.ApiResult.Error -> {
                    Toast.makeText(context, "上传失败：${result.message}", Toast.LENGTH_LONG).show()
                }
                is GiteeApi.ApiResult.NotFound -> {
                    Toast.makeText(context, "上传失败：NotFound", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCancelShareConfirm(history: WebParseStore.ParseHistory) {
        val cloudRecord = cloudRecordMap[history.url] ?: return
        val refCount = GiteeShareStore.countAdapterReferences(cloudRecord, allCloudRecords)
        val hasAdapter = cloudRecord.globalAdapterId != null && cloudRecord.globalAdapterId.isNotBlank()

        val msg = buildString {
            append("确定取消共享「${displayTitle(history)}」吗？")
            if (hasAdapter && refCount > 0) {
                append("\n\n该记录关联的适配器（${cloudRecord.adapterName}）还有 $refCount 条其他记录在使用，取消后仅移除本记录，适配器保留。")
            } else if (hasAdapter) {
                append("\n\n关联适配器（${cloudRecord.adapterName}）未被其他记录引用，记录与适配器将一起删除。")
            }
        }

        val confirmPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bottomSheetPanelBg()
            setPadding(dp(24), dp(18), dp(24), dp(20))
            clipChildren = false
            clipToPadding = false
        }
        confirmPanel.addView(TextView(context).apply {
            text = "取消共享"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
            setPadding(0, 0, 0, dp(12))
        })
        confirmPanel.addView(TextView(context).apply {
            text = msg
            textSize = 14f
            setTextColor(Color.argb(220, 255, 255, 255))
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })

        val confirmDialog = arrayOf<AlertDialog?>(null)
        val confirmBtn = dialogButton("确认", warning = true) {
            confirmDialog[0]?.dismiss()
            performCancelShare(cloudRecord)
        }
        val cancelBtn = dialogButton("取消") {
            confirmDialog[0]?.dismiss()
        }
        val btnBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnBar.addView(cancelBtn, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginEnd = dp(8) })
        btnBar.addView(confirmBtn, LinearLayout.LayoutParams(dp(100), dp(40)))
        confirmPanel.addView(btnBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(confirmPanel).create().also { d ->
            confirmDialog[0] = d
            d.setOnShowListener { confirmBtn.requestFocus() }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(500), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun performCancelShare(cloudRecord: SharedRecord) {
        val progressPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bottomSheetPanelBg()
            setPadding(dp(40), dp(30), dp(40), dp(30))
        }
        val progressText = TextView(context).apply {
            text = "正在取消共享..."
            textSize = 15f
            setTextColor(warm)
            gravity = Gravity.CENTER
        }
        val progressBar = ProgressBar(context).apply { isIndeterminate = true }
        progressPanel.addView(progressText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
        progressPanel.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val progressDialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(progressPanel).create().also { d ->
            d.setCancelable(false)
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(360), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                GiteeShareStore.deleteSharedRecord(cloudRecord.globalRecordId, allCloudRecords)
            }
            progressDialog.dismiss()

            when (result) {
                is GiteeApi.ApiResult.Success -> {
                    Toast.makeText(context, "已取消共享", Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                    show()
                }
                is GiteeApi.ApiResult.Error -> {
                    Toast.makeText(context, "取消共享失败：${result.message}", Toast.LENGTH_LONG).show()
                }
                is GiteeApi.ApiResult.NotFound -> {
                    Toast.makeText(context, "记录不存在", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun pageTypeTag(pageType: String): TextView? {
        val label = when (pageType.trim().lowercase()) {
            "list" -> "列表页"
            "detail" -> "详情页"
            else -> return null
        }
        val color = if (pageType.trim().lowercase() == "list") Color.rgb(255, 152, 56) else Color.rgb(76, 217, 100)
        return TextView(context).apply {
            text = label
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(7), 0, dp(7), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(5).toFloat()
                setColor(color)
            }
        }
    }

    private fun displayTitle(history: WebParseStore.ParseHistory): String {
        val title = history.title.trim()
        if (history.pageType.trim().lowercase() != "list") return title
        val siteName = displaySiteName(history).trim()
        if (siteName.isBlank() || title.startsWith("「$siteName」")) return title
        return "「$siteName」$title"
    }

    private fun displaySiteName(history: WebParseStore.ParseHistory): String {
        val fromTitle = history.siteTitle.trim()
            .split(" - ", " – ", " — ", " | ", "｜", "_", "-")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .lastOrNull()
            .orEmpty()
            .take(16)
        if (fromTitle.isNotBlank()) return fromTitle
        return runCatching {
            val host = URI(history.url).host.orEmpty().removePrefix("www.").trim('.')
            val parts = host.split('.').filter { it.isNotBlank() }
            if (parts.size >= 2) parts[parts.size - 2] else host
        }.getOrDefault("")
    }

    // ===================== 样式 =====================

    private fun titleView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        addView(TextView(context).apply {
            text = "上传收藏"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun dialogButton(label: String, warning: Boolean = false, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            setTextColor(if (warning) Color.rgb(255, 138, 128) else Color.argb(235, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(if (focused) 2 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
                setColor(Color.argb(52, 32, 34, 40))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has ->
            refresh(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
        }
        setOnClickListener { click() }
    }

    private fun bottomSheetPanelBg() = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
        cornerRadius = dp(26).toFloat()
        setStroke(dp(2), warm)
    }

    private fun rowBg(focused: Boolean, disabled: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        when {
            disabled -> setColor(Color.argb(10, 255, 255, 255))
            focused -> setColor(Color.TRANSPARENT)
            else -> setColor(Color.argb(18, 255, 255, 255))
        }
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else if (disabled) Color.argb(60, 210, 214, 222) else Color.argb(170, 210, 214, 222))
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
            if (next != null && next !== view && next.visibility == View.VISIBLE && next.isFocusable && isChildOf(next, root)) return@OnKeyListener false
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

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
