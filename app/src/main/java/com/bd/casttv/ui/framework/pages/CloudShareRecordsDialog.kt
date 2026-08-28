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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云端共享记录弹窗：
 * - 从云端拉取记录数据，按创建者（creatorId + deviceName）分组展示
 * - 「我的共享」分组排在首位
 * - 勾选记录可下载，增量保存到本地
 * - 底部按钮：全选、取消勾选、下载、取消（右对齐）
 */
class CloudShareRecordsDialog(
    private val context: Context,
    private val onDownloaded: () -> Unit = {}
) {
    private val warm: Int get() = ThemeManager.currentPalette(context).accent
    private var dialog: AlertDialog? = null

    // 全局数据
    private var allRecords = emptyList<SharedRecord>()
    private var allAdapters = emptyList<GiteeShareStore.SharedAdapter>()
    // 选中的 globalRecordId
    private val selected = mutableSetOf<String>()
    // 行视图索引
    private val rowMap = mutableMapOf<String, View>() // globalRecordId -> 行 View
    private val groupMap = mutableMapOf<String, TextView>() // groupKey -> 选中数量提示

    data class CreatorGroup(
        val creatorId: String,
        val deviceName: String,
        val records: List<SharedRecord>
    )

    fun show() {
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

        val statusView = TextView(context).apply {
            text = "正在拉取云端数据..."
            textSize = 13f
            setTextColor(Color.argb(180, 255, 255, 255))
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        content.addView(statusView)

        val loadingBar = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.VISIBLE
        }
        content.addView(loadingBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            topMargin = dp(20)
            bottomMargin = dp(20)
        })

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
        val downloadButton = dialogButton("下载", warning = false) {
            performDownload()
        }
        val selectAllButton = dialogButton("全选") { toggleAll(true) }
        val deselectButton = dialogButton("取消勾选") { toggleAll(false) }
        val countTip = TextView(context).apply {
            text = ""
            textSize = 13f
            setTextColor(warm)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }
        downloadCountTip = countTip
        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        bottomBar.addView(countTip, LinearLayout.LayoutParams(0, dp(40), 1f))
        bottomBar.addView(selectAllButton, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginEnd = dp(8) })
        bottomBar.addView(deselectButton, LinearLayout.LayoutParams(dp(110), dp(40)).apply { marginEnd = dp(8) })
        bottomBar.addView(downloadButton, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginEnd = dp(8) })
        bottomBar.addView(cancelButton, LinearLayout.LayoutParams(dp(100), dp(40)))
        content.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        contentInset.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(contentInset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val focusRows = mutableListOf<View>()
        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnShowListener {
                cancelButton.requestFocus()
                // 拉取云端数据
                fetchCloudData(statusView, loadingBar, listContainer, scroll, focusRows, selectAllButton, deselectButton, downloadButton)
            }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(720), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun fetchCloudData(
        statusView: TextView,
        loadingBar: ProgressBar,
        listContainer: LinearLayout,
        scroll: ScrollView,
        focusRows: MutableList<View>,
        selectAllButton: View,
        deselectButton: View,
        downloadButton: View
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val myCreatorId = withContext(Dispatchers.IO) { CreatorIdProvider.get(context) }
            val result = withContext(Dispatchers.IO) { GiteeShareStore.fetchCloudIndex() }

            loadingBar.visibility = View.GONE

            when (result) {
                is GiteeApi.ApiResult.Success -> {
                    allRecords = result.value.records
                    allAdapters = result.value.adapters

                    if (allRecords.isEmpty()) {
                        statusView.text = "云端暂无共享记录"
                        statusView.setTextColor(Color.argb(180, 255, 255, 255))
                        listContainer.visibility = View.GONE
                        return@launch
                    }

                    // 按 creatorId 分组，我的共享排首位
                    val groups = buildCreatorGroups(allRecords, myCreatorId)
                    statusView.text = "共 ${allRecords.size} 条 · ${groups.size} 位用户共享"
                    statusView.setTextColor(warm)

                    listContainer.visibility = View.VISIBLE
                    rowMap.clear()
                    groupMap.clear()
                    fillGroupViews(listContainer, groups, myCreatorId, focusRows, downloadButton)

                    val allFocusable = focusRows + listOf(selectAllButton, deselectButton, downloadButton)
                    bindBoundary(scroll, allFocusable)

                    updateDownloadButton(downloadButton)
                }
                is GiteeApi.ApiResult.Error -> {
                    statusView.text = "云端数据拉取失败：${result.message}"
                    statusView.setTextColor(Color.rgb(255, 138, 128))
                    listContainer.visibility = View.GONE
                }
                is GiteeApi.ApiResult.NotFound -> {
                    statusView.text = "云端暂无共享记录"
                    listContainer.visibility = View.GONE
                }
            }
        }
    }

    private fun buildCreatorGroups(records: List<SharedRecord>, myCreatorId: String): List<CreatorGroup> {
        val grouped = records.groupBy { it.creatorId }
        val groups = mutableListOf<CreatorGroup>()
        // 我的共享排首位
        grouped[myCreatorId]?.let { mine ->
            groups.add(CreatorGroup(myCreatorId, mine.firstOrNull()?.deviceName.orEmpty(), mine))
        }
        // 其他用户按上传时间排序
        grouped.forEach { (cid, list) ->
            if (cid != myCreatorId) {
                groups.add(CreatorGroup(cid, list.firstOrNull()?.deviceName.orEmpty(), list))
            }
        }
        // 组内按上传时间倒序（最新在前）
        groups.forEach { g -> g.records.sortedByDescending { it.uploadedAt } }
        return groups
    }

    private fun fillGroupViews(
        container: LinearLayout,
        groups: List<CreatorGroup>,
        myCreatorId: String,
        focusRows: MutableList<View>,
        downloadButton: View
    ) {
        groups.forEachIndexed { gi, group ->
            val isMine = group.creatorId == myCreatorId
            // 分组标题
            val titleLine = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                setPadding(dp(6), dp(if (gi == 0) 8 else 18), dp(6), dp(4))
            }
            titleLine.addView(TextView(context).apply {
                val groupTitle = if (isMine) {
                    "我的共享${if (group.deviceName.isNotBlank()) " · ${group.deviceName}" else ""}"
                } else {
                    group.deviceName.ifBlank { "用户共享" }
                }
                text = groupTitle
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isMine) warm else Color.argb(200, 245, 245, 245))
                letterSpacing = 0.05f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val countTip = TextView(context).apply {
                text = "${group.records.size} 条"
                textSize = 12f
                setTextColor(Color.argb(140, 255, 255, 255))
            }
            groupMap[group.creatorId] = countTip
            titleLine.addView(countTip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            container.addView(titleLine)

            // 记录行
            group.records.forEach { record ->
                val row = recordRow(record, isMine) {
                    if (selected.contains(record.globalRecordId)) {
                        selected.remove(record.globalRecordId)
                    } else {
                        selected.add(record.globalRecordId)
                    }
                    updateRowSelection(record.globalRecordId)
                    updateGroupCountTip(group.creatorId)
                    updateDownloadButton(downloadButton)
                }
                rowMap[record.globalRecordId] = row
                focusRows.add(row)
                container.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(if (record.pageType.isBlank()) 66 else 86)
                ).apply { topMargin = dp(6) })
            }
        }
    }

    private fun recordRow(record: SharedRecord, isMine: Boolean, click: () -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rowBg(false)
        setOnFocusChangeListener { v, has ->
            background = rowBg(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 14)
        }
        setOnClickListener { click() }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val marker = TextView(context).apply {
            text = if (selected.contains(record.globalRecordId)) "✓" else "○"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
            setPadding(0, 0, dp(10), 0)
        }
        topRow.addView(marker, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        topRow.addView(TextView(context).apply {
            text = record.title.ifBlank { "无标题" }
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // 我的共享记录增加删除图标
        if (isMine) {
            val deleteIcon = TextView(context).apply {
                text = "✕"
                textSize = 16f
                setTextColor(Color.argb(160, 255, 138, 128))
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { v, has ->
                    setTextColor(if (has) Color.rgb(255, 138, 128) else Color.argb(160, 255, 138, 128))
                    FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 8)
                }
                setOnClickListener { showDeleteConfirm(record) }
            }
            topRow.addView(deleteIcon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(8) })
        }
        addView(topRow)

        val summary = buildList {
            add(shortUrl(record.url))
            if (record.frameworkType.isNotBlank()) add("框架：${record.frameworkType}")
            if (record.adapterName.isNotBlank()) add("适配器：${record.adapterName}")
            add(if (record.adapterKind == "BUILT_IN") "内置" else "自定义")
        }.joinToString("  ·  ")
        addView(TextView(context).apply {
            text = summary
            textSize = 12f
            setTextColor(Color.argb(190, 255, 255, 255))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(56), dp(4), 0, 0)
        })
        if (record.pageType.isNotBlank()) {
            val label = when (record.pageType.lowercase()) {
                "list" -> "列表页"
                "detail" -> "详情页"
                else -> ""
            }
            if (label.isNotBlank()) {
                addView(TextView(context).apply {
                    text = label
                    textSize = 11f
                    setTextColor(Color.argb(165, 255, 255, 255))
                    setPadding(dp(56), dp(3), 0, 0)
                })
            }
        }
    }

    private fun updateRowSelection(globalRecordId: String) {
        val row = rowMap[globalRecordId] as? LinearLayout ?: return
        val topRow = row.getChildAt(0) as? LinearLayout ?: return
        val marker = topRow.getChildAt(0) as? TextView ?: return
        marker.text = if (selected.contains(globalRecordId)) "✓" else "○"
    }

    private fun updateGroupCountTip(groupKey: String) {
        // 简单重绘：暂时不做精确分组计数，保持原计数
    }

    private var downloadCountTip: TextView? = null

    private fun updateDownloadButton(button: View) {
        button.isEnabled = selected.isNotEmpty()
        button.alpha = if (selected.isEmpty()) 0.4f else 1f
        downloadCountTip?.text = if (selected.isEmpty()) "" else "已选 ${selected.size} 条"
    }

    private fun toggleAll(select: Boolean) {
        selected.clear()
        if (select) allRecords.forEach { selected.add(it.globalRecordId) }
        allRecords.forEach { updateRowSelection(it.globalRecordId) }
    }

    private fun showDeleteConfirm(record: SharedRecord) {
        val refCount = GiteeShareStore.countAdapterReferences(record, allRecords)
        val hasAdapter = record.globalAdapterId != null && record.globalAdapterId.isNotBlank()

        val msg = buildString {
            append("确定删除「${record.title.ifBlank { "无标题" }}」吗？")
            if (hasAdapter && refCount > 0) {
                append("\n\n该记录关联的适配器（${record.adapterName}）还有 $refCount 条其他记录在使用，删除后仅移除本记录，适配器保留。")
            } else if (hasAdapter) {
                append("\n\n关联适配器（${record.adapterName}）未被其他记录引用，记录与适配器将一起删除。")
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
            text = "删除共享记录"
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
        val deleteBtn = dialogButton("删除", warning = true) {
            confirmDialog[0]?.dismiss()
            performDelete(record)
        }
        val cancelBtn = dialogButton("取消") {
            confirmDialog[0]?.dismiss()
        }
        val btnBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnBar.addView(cancelBtn, LinearLayout.LayoutParams(dp(100), dp(40)).apply { marginEnd = dp(8) })
        btnBar.addView(deleteBtn, LinearLayout.LayoutParams(dp(100), dp(40)))
        confirmPanel.addView(btnBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(confirmPanel).create().also { d ->
            confirmDialog[0] = d
            d.setOnShowListener { deleteBtn.requestFocus() }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(500), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun performDelete(record: SharedRecord) {
        val progressPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bottomSheetPanelBg()
            setPadding(dp(40), dp(30), dp(40), dp(30))
        }
        val progressText = TextView(context).apply {
            text = "正在删除云端记录..."
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
                GiteeShareStore.deleteSharedRecord(record.globalRecordId, allRecords)
            }
            progressDialog.dismiss()

            when (result) {
                is GiteeApi.ApiResult.Success -> {
                    Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show()
                    // 刷新云端列表
                    dialog?.dismiss()
                    show()
                }
                is GiteeApi.ApiResult.Error -> {
                    Toast.makeText(context, "删除失败：${result.message}", Toast.LENGTH_LONG).show()
                }
                is GiteeApi.ApiResult.NotFound -> {
                    Toast.makeText(context, "记录不存在", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun performDownload() {
        if (selected.isEmpty()) {
            Toast.makeText(context, "请先选择要下载的记录", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedIds = selected.toList()

        val progressPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bottomSheetPanelBg()
            setPadding(dp(40), dp(30), dp(40), dp(30))
        }
        val progressText = TextView(context).apply {
            text = "正在下载并保存到本地..."
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
                GiteeShareStore.downloadShared(context, selectedIds, allRecords, allAdapters)
            }
            progressDialog.dismiss()

            when (result) {
                is GiteeApi.ApiResult.Success -> {
                    val r = result.value
                    val msg = buildString {
                        append("成功下载 ${r.recordsSaved} 条记录")
                        if (r.adaptersSaved > 0) append("，${r.adaptersSaved} 个适配器")
                        if (r.skippedRecords > 0) append("\n跳过 ${r.skippedRecords} 条已存在记录")
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    selected.clear()
                    dialog?.dismiss()
                    onDownloaded()
                }
                is GiteeApi.ApiResult.Error -> {
                    Toast.makeText(context, "下载失败：${result.message}", Toast.LENGTH_LONG).show()
                }
                is GiteeApi.ApiResult.NotFound -> {
                    Toast.makeText(context, "下载失败：NotFound", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shortUrl(url: String): String {
        val host = runCatching {
            val uri = java.net.URI(url.trim())
            (uri.host ?: uri.authority.orEmpty())
                .removePrefix("www.").trim('.')
        }.getOrElse { url }
        return if (host.length <= 30) host else host.take(27) + "..."
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
            text = "云端共享记录"
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

    private fun rowBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (focused) Color.TRANSPARENT else Color.argb(18, 255, 255, 255))
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
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
