package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.webparse.ParsePageKind
import com.bd.casttv.webparse.ParseStep

class WebParseProgressDialog(
    private val context: Context,
    private val pageKind: ParsePageKind = ParsePageKind.DETAIL,
    private val onTryJsonParse: (() -> Unit)? = null,
    private val onCancel: () -> Unit
) {
    private data class RowViews(
        val container: LinearLayout,
        val progress: ProgressBar,
        val resultIcon: ImageView,
        val label: TextView
    )

    private val warm = Color.parseColor("#FFD700")
    private val rows = linkedMapOf<ParseStep, RowViews>()
    private var currentStep: ParseStep = ParseStep.RECEIVED
    private var failedStep: ParseStep? = null
    private var errorText: String = ""
    private var dialog: AlertDialog? = null
    private var tryJsonButton: TextView? = null

    fun show() {
        rows.clear()
        currentStep = ParseStep.RECEIVED
        failedStep = null
        errorText = ""
        tryJsonButton = null
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
        content.addView(titleView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val steps = listOf(
            ParseStep.RECEIVED to "接收到链接",
            ParseStep.FETCHING_HTML to "获取详情页",
            ParseStep.PARSING_INFO to "解析影片信息",
            ParseStep.LOADING_DONE to "加载完成"
        )
        steps.forEachIndexed { index, pair ->
            val row = stepRow(pair.second)
            rows[pair.first] = row
            content.addView(row.container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = if (index == 0) dp(18) else dp(8) })
        }
        tryJsonButton = dialogButton("尝试 JSON 解析") {
            dialog?.dismiss()
            onTryJsonParse?.invoke()
        }.apply {
            visibility = View.GONE
        }
        content.addView(tryJsonButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(18) })
        val cancel = dialogButton("取消") { onCancel(); dialog?.dismiss() }
        cancel.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                BoundaryFocusHandler.shake(v); true
            } else false
        }
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.END
            addView(cancel, LinearLayout.LayoutParams(dp(104), dp(42)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        contentInset.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(contentInset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnShowListener { cancel.requestFocus() }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(520), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
        update(ParseStep.RECEIVED)
    }

    fun update(step: ParseStep, error: String = "") {
        currentStep = step
        if (step == ParseStep.ERROR) {
            failedStep = rows.keys.firstOrNull { it != ParseStep.LOADING_DONE && rows[it]?.progress?.visibility == View.VISIBLE } ?: ParseStep.PARSING_INFO
            errorText = error
        }
        val shouldShowTryJson = step == ParseStep.ERROR && pageKind == ParsePageKind.LIST && onTryJsonParse != null
        tryJsonButton?.visibility = if (shouldShowTryJson) View.VISIBLE else View.GONE
        if (shouldShowTryJson) tryJsonButton?.post { tryJsonButton?.requestFocus() }
        val order = rows.keys.toList()
        val currentIndex = order.indexOf(step)
        rows.forEach { (s, row) ->
            val base = when (s) {
                ParseStep.RECEIVED -> "接收到链接"
                ParseStep.FETCHING_HTML -> "获取详情页"
                ParseStep.PARSING_INFO -> "解析影片信息"
                ParseStep.LOADING_DONE -> "加载完成"
                ParseStep.ERROR -> "失败"
            }
            val failed = failedStep == s
            val done = currentIndex >= 0 && order.indexOf(s) < currentIndex || step == ParseStep.LOADING_DONE
            val loading = s == step && step != ParseStep.LOADING_DONE && !failed
            row.label.text = if (failed && errorText.isNotBlank()) "$base：$errorText" else base
            row.label.setTextColor(if (failed || loading || done) warm else Color.argb(220, 255, 255, 255))
            updateIndicator(row, loading = loading, success = done || s == ParseStep.LOADING_DONE && step == ParseStep.LOADING_DONE, failed = failed)
        }
    }

    fun dismissDelayed() { rows.values.firstOrNull()?.label?.postDelayed({ dialog?.dismiss() }, 500L) }
    fun dismiss() { dialog?.dismiss() }

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
            text = "正在解析影片信息"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun stepRow(label: String): RowViews {
        val progress = ProgressBar(context).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        val resultIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            visibility = View.INVISIBLE
        }
        val indicator = FrameLayout(context).apply {
            addView(progress, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            addView(resultIcon, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        val text = TextView(context).apply {
            this.text = label
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            background = rowBg()
            addView(indicator, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return RowViews(container, progress, resultIcon, text)
    }

    private fun updateIndicator(row: RowViews, loading: Boolean, success: Boolean, failed: Boolean) {
        row.progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        row.resultIcon.visibility = if (!loading && (success || failed)) View.VISIBLE else View.INVISIBLE
        if (!loading && (success || failed)) {
            row.resultIcon.setImageResource(if (failed) R.drawable.ic_parse_fail else R.drawable.ic_parse_success)
        }
    }

    private fun bottomSheetPanelBg() = GradientDrawable().apply {
        cornerRadius = dp(26).toFloat()
        setColor(0xFF4169E1.toInt())
    }

    private fun rowBg() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(18, 255, 255, 255))
        setStroke(dp(1), Color.argb(170, 210, 214, 222))
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
                setColor(if (focused) Color.TRANSPARENT else Color.argb(18, 255, 255, 255))
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has -> refresh(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 60) }
        setOnClickListener { click() }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
