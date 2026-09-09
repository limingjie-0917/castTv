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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.webparse.WebParseHtml
import com.bd.casttv.webparse.WebParseStore
import java.net.URI

class WebParseHistoryDialog(
    private val context: Context,
    private val onSelected: (WebParseStore.ParseHistory) -> Unit
) {
    private val warm: Int get() = ThemeManager.currentPalette(context).accent
    private val store = WebParseStore(context)
    private var dialog: AlertDialog? = null
    private var batchMode = false
    private val selectedHistoryKeys = linkedSetOf<String>()

    private val orangeTag = Color.rgb(255, 152, 56)
    private val greenTag = Color.rgb(76, 217, 100)

    fun show() {
        val histories = store.getParseHistory()
        val validKeys = histories.mapTo(hashSetOf()) { historyKey(it) }
        selectedHistoryKeys.retainAll(validKeys)

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
        val focusRows = mutableListOf<View>()
        val downloadButton = dialogButton("下载") { showCloudFetchDialog() }
        val batchButton = batchModeButton(batchMode) {
            batchMode = !batchMode
            selectedHistoryKeys.clear()
            show()
        }
        content.addView(
            titleView(downloadButton, batchButton),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val uploadButton = dialogButton("上传") {
            val selectedHistories = histories.filter { historyKey(it) in selectedHistoryKeys }
            if (selectedHistories.isNotEmpty()) {
                WebParseShareDialog(context) {
                    selectedHistoryKeys.clear()
                    batchMode = false
                    show()
                }.uploadHistories(selectedHistories)
            }
        }
        val deleteButton = dialogButton("删除", warning = true) {
            val count = selectedHistoryKeys.size
            if (count > 0) {
                store.deleteHistories(selectedHistoryKeys)
                selectedHistoryKeys.clear()
                batchMode = false
                Toast.makeText(context, "已删除 $count 条收藏", Toast.LENGTH_SHORT).show()
                show()
            }
        }
        fun refreshBatchActions() {
            val enabled = selectedHistoryKeys.isNotEmpty()
            listOf(uploadButton, deleteButton).forEach { button ->
                button.isEnabled = enabled
                button.isFocusable = enabled
                button.alpha = if (enabled) 1f else 0.4f
            }
        }
        refreshBatchActions()

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        if (histories.isEmpty()) {
            val empty = TextView(context).apply {
                text = "暂无收藏\n解析成功后可点击「收藏网站」手动保存"
                textSize = 15f
                setTextColor(Color.argb(210, 255, 255, 255))
                gravity = Gravity.CENTER
                background = rowBg(false)
                isFocusable = true
            }
            focusRows.add(empty)
            list.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)).apply { topMargin = dp(16) })
        } else {
            histories.forEachIndexed { index, history ->
                val key = historyKey(history)
                lateinit var refreshRow: () -> Unit
                val row = historyRow(
                    history = history,
                    showCheckBox = batchMode,
                    selected = key in selectedHistoryKeys,
                    click = {
                        if (batchMode) {
                            if (!selectedHistoryKeys.add(key)) selectedHistoryKeys.remove(key)
                            refreshRow()
                            refreshBatchActions()
                        } else {
                            dialog?.dismiss()
                            onSelected(history)
                        }
                    },
                    onRefreshReady = { refreshRow = it }
                )
                focusRows.add(row)
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)).apply { topMargin = if (index == 0) dp(16) else dp(8) })
            }
        }

        val scroll = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            clipChildren = true
            clipToPadding = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))

        if (batchMode) {
            val cancelButton = dialogButton("取消") {
                batchMode = false
                selectedHistoryKeys.clear()
                show()
            }
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(cancelButton, LinearLayout.LayoutParams(dp(96), dp(42)).apply { marginEnd = dp(8) })
                addView(uploadButton, LinearLayout.LayoutParams(dp(96), dp(42)).apply { marginEnd = dp(8) })
                addView(deleteButton, LinearLayout.LayoutParams(dp(96), dp(42)))
            }
            content.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(12) })
            focusRows.add(cancelButton)
            focusRows.add(uploadButton)
            focusRows.add(deleteButton)
        } else {
            focusRows.add(downloadButton)
        }
        focusRows.add(batchButton)

        contentInset.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(contentInset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val currentDialog = dialog
        if (currentDialog?.isShowing == true) {
            // 批量模式切换时直接刷新当前弹窗内容，避免 dismiss 后导致「我的收藏」关闭。
            currentDialog.setContentView(panel)
            bindBoundary(panel, focusRows)
            currentDialog.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(680), WindowManager.LayoutParams.WRAP_CONTENT)
            }
            panel.post { focusRows.firstOrNull()?.requestFocus() }
        } else {
            dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
                bindBoundary(panel, focusRows)
                d.setOnShowListener { focusRows.firstOrNull()?.requestFocus() }
                d.show()
                d.window?.apply {
                    setGravity(Gravity.CENTER)
                    setBackgroundDrawableResource(android.R.color.transparent)
                    setLayout(dp(680), WindowManager.LayoutParams.WRAP_CONTENT)
                }
            }
        }
    }

    private fun historyRow(
        history: WebParseStore.ParseHistory,
        showCheckBox: Boolean,
        selected: Boolean,
        click: () -> Unit,
        onRefreshReady: (() -> Unit) -> Unit
    ): LinearLayout {
        var checked = selected
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rowBg(false)
        }
        val checkMark = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = if (checked) "已选中" else "未选中"
        }
        val check = FrameLayout(context).apply {
            addView(checkMark, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        fun refreshCheck() {
            checkMark.setImageResource(
                if (checked) R.drawable.ic_warm_radio_checked else R.drawable.ic_warm_radio_unchecked
            )
            checkMark.contentDescription = if (checked) "已选中" else "未选中"
        }
        refreshCheck()
        if (showCheckBox) {
            root.addView(check, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        }

        val textContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        pageTypeTag(history.pageType)?.let { tag ->
            topRow.addView(tag, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)).apply { marginEnd = dp(8) })
        }
        topRow.addView(TextView(context).apply {
            text = displayTitle(history)
            textSize = 15.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        textContent.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textContent.addView(TextView(context).apply {
            text = "网址：${WebParseHtml.shortUrl(history.url)}"
            textSize = 12.5f
            setTextColor(Color.argb(200, 255, 255, 255))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val line2 = buildList {
            if (history.frameworkType.isNotBlank()) add("框架：${history.frameworkType}")
            if (history.adapterName.isNotBlank()) add("适配器：${history.adapterName}")
        }
        if (line2.isNotEmpty()) {
            textContent.addView(TextView(context).apply {
                text = line2.joinToString("  ·  ")
                textSize = 12f
                setTextColor(Color.argb(170, 255, 255, 255))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        }
        root.addView(textContent, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.setOnFocusChangeListener { v, has ->
            root.background = rowBg(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 14)
        }
        root.setOnClickListener {
            click()
            checked = historyKey(history) in selectedHistoryKeys
            refreshCheck()
        }
        onRefreshReady {
            checked = historyKey(history) in selectedHistoryKeys
            refreshCheck()
        }
        return root
    }

    private fun pageTypeTag(pageType: String): TextView? {
        val label = when (pageType.trim().lowercase()) {
            "list" -> "列表页"
            "detail" -> "详情页"
            else -> return null
        }
        val color = if (pageType.trim().lowercase() == "list") orangeTag else greenTag
        return TextView(context).apply {
            text = label
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(color)
            }
        }
    }

    private fun historyKey(history: WebParseStore.ParseHistory): String = history.recordId.ifBlank { history.url }

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
            val host = URI(history.url).host.orEmpty()
                .removePrefix("www.")
                .trim('.')
            val parts = host.split('.').filter { it.isNotBlank() }
            when {
                parts.size >= 2 -> parts[parts.size - 2]
                else -> host
            }
        }.getOrDefault("")
    }

    private fun titleView(downloadButton: View, batchButton: View): View = LinearLayout(context).apply {
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
            text = "我的收藏"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!batchMode) {
            addView(downloadButton, LinearLayout.LayoutParams(dp(88), dp(40)).apply { marginEnd = dp(8) })
        }
        addView(batchButton, LinearLayout.LayoutParams(dp(112), dp(40)))
    }

    private fun batchModeButton(showExit: Boolean, click: () -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setPadding(dp(8), 0, dp(8), 0)
        addView(TextView(context).apply {
            text = if (showExit) "退出" else "批量操作"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(235, 245, 245, 245))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun refresh(focused: Boolean) {
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

    private fun showCloudFetchDialog() {
        // 保留「我的收藏」作为底层弹窗，下载弹窗关闭后直接回到收藏列表。
        CloudShareRecordsDialog(context) {
            show()
        }.show()
    }

    private fun showClearConfirm() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bottomSheetPanelBg()
            setPadding(dp(20), dp(16), dp(20), dp(18))
            clipChildren = false
            clipToPadding = false
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        header.addView(TextView(context).apply {
            text = "清空收藏"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(TextView(context).apply {
            text = "确认清空全部收藏吗？清空后将无法恢复。"
            textSize = 14f
            setTextColor(Color.argb(224, 255, 255, 255))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14); bottomMargin = dp(16) })

        var confirmDialog: AlertDialog? = null
        val cancelBtn = dialogButton("取消") { confirmDialog?.dismiss() }
        val okBtn = dialogButton("确认清空", warning = true) {
            store.clearHistory()
            confirmDialog?.dismiss()
            dialog?.dismiss()
            Toast.makeText(context, "收藏已清空", Toast.LENGTH_SHORT).show()
            show()
        }
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
            addView(okBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        confirmDialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnShowListener { cancelBtn.requestFocus() }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(460), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun dialogButton(label: String, warning: Boolean = false, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 14f
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
