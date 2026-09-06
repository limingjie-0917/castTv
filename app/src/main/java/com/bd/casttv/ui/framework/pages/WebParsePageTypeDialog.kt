package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
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
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.webparse.FetchMode

class WebParsePageTypeDialog(
    private val context: Context,
    private val returnFocusView: View?,
    private val onListPage: (FetchMode) -> Unit,
    private val onDetailPage: (FetchMode) -> Unit
) {
    private var dialog: AlertDialog? = null
    private var selectedMode = FetchMode.HTTP

    fun show() {
        val focusables = mutableListOf<View>()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.dialogPanelBg(context, 26)
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
        content.addView(TextView(context).apply {
            text = "请选择当前粘贴的网址类型。列表页会先展示影片条目，详情页会直接进入现有解析流程。"
            textSize = 14f
            setTextColor(Color.argb(210, 255, 255, 255))
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val listButton = optionButton(
            title = "影片列表页",
            desc = "从当前页面提取影片条目，选择影片后再解析详情页"
        ) {
            dialog?.dismiss()
            onListPage(selectedMode)
        }
        val detailButton = optionButton(
            title = "影片详情页",
            desc = "直接复用原有详情页解析流程"
        ) {
            dialog?.dismiss()
            onDetailPage(selectedMode)
        }
        focusables.add(listButton)
        focusables.add(detailButton)
        content.addView(listButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { topMargin = dp(18) })
        content.addView(detailButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { topMargin = dp(10) })

        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
            addView(TextView(context).apply {
                text = "获取方式："
                textSize = 14f
                setTextColor(Color.argb(180, 255, 255, 255))
            })
        }

        var httpBtn: TextView? = null
        var webViewBtn: TextView? = null

        fun refreshModeButtons() {
            val hBtn = httpBtn ?: return
            val wBtn = webViewBtn ?: return
            val accent = ThemeManager.accentColor(context)

            fun updateBtn(btn: TextView, checked: Boolean) {
                btn.setTextColor(
                    when {
                        checked -> accent
                        btn.hasFocus() -> Color.WHITE
                        else -> Color.argb(190, 230, 234, 240)
                    }
                )
                btn.background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    if (checked) {
                        setColor(Color.argb(70, Color.red(accent), Color.green(accent), Color.blue(accent)))
                    } else {
                        setColor(Color.argb(18, 255, 255, 255))
                    }
                    ThemeManager.strokeFor(context, btn.hasFocus()).let { setStroke(dp(it.first), it.second) }
                }
            }
            updateBtn(hBtn, selectedMode == FetchMode.HTTP)
            updateBtn(wBtn, selectedMode == FetchMode.WEBVIEW)
        }

        httpBtn = TextView(context).apply {
            text = "HTTP (默认)"
            textSize = 13f
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setOnClickListener {
                selectedMode = FetchMode.HTTP
                refreshModeButtons()
            }
            setOnFocusChangeListener { v, has ->
                refreshModeButtons()
                FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 8)
            }
        }

        webViewBtn = TextView(context).apply {
            text = "WebView (高级)"
            textSize = 13f
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setOnClickListener {
                selectedMode = FetchMode.WEBVIEW
                refreshModeButtons()
            }
            setOnFocusChangeListener { v, has ->
                refreshModeButtons()
                FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 8)
            }
        }

        refreshModeButtons()

        modeRow.addView(httpBtn, LinearLayout.LayoutParams(dp(130), dp(36)).apply { marginStart = dp(8) })
        modeRow.addView(webViewBtn, LinearLayout.LayoutParams(dp(130), dp(36)).apply { marginStart = dp(10) })
        content.addView(modeRow)
        focusables.add(httpBtn)
        focusables.add(webViewBtn)

        val cancelBtn = dialogButton("取消") { dialog?.dismiss() }
        focusables.add(cancelBtn)
        content.addView(cancelBtn, LinearLayout.LayoutParams(dp(104), dp(42)).apply {
            topMargin = dp(20)
            gravity = Gravity.END
        })

        contentInset.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(contentInset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            bindBoundary(panel, focusables)
            d.setOnDismissListener { returnFocusView?.post { returnFocusView.requestFocus() } }
            d.setOnShowListener { listButton.requestFocus() }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(560), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

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
        addView(object : TextView(context) {
            init {
                text = "选择网页类型"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w <= 0 || h <= 0) return
                val grad = ThemeManager.dialogTitleGradient(context)
                if (grad.isEmpty()) return
                paint.shader = LinearGradient(0f, h * 0.5f, w.toFloat(), h * 0.5f, grad, null, Shader.TileMode.CLAMP)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun optionButton(title: String, desc: String, click: () -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setPadding(dp(16), 0, dp(16), 0)
        fun refresh(focused: Boolean) {
            background = optionBg(focused)
            FocusFxHelper.applyFocusFxState(this, focused, cornerRadiusDp = 14)
        }
        refresh(false)
        setOnFocusChangeListener { _, has -> refresh(has) }
        setOnClickListener { click() }
        addView(TextView(context).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(245, 245, 245))
            maxLines = 1
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = desc
            textSize = 13f
            setTextColor(Color.argb(195, 255, 255, 255))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
    }

    private fun optionBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (focused) Color.TRANSPARENT else Color.argb(18, 255, 255, 255))
        ThemeManager.strokeFor(context, focused).let { setStroke(dp(it.first), it.second) }
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            setTextColor(Color.argb(230, 238, 238, 238))
            background = GradientDrawable().apply {
                cornerRadius = dp(60).toFloat()
                setColor(if (focused) Color.TRANSPARENT else Color.argb(18, 255, 255, 255))
                ThemeManager.strokeFor(context, focused).let { setStroke(dp(it.first), it.second) }
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has -> refresh(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 60) }
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
