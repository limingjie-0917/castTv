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
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper

class WebParsePageTypeDialog(
    private val context: Context,
    private val returnFocusView: View?,
    private val onListPage: () -> Unit,
    private val onDetailPage: () -> Unit
) {
    private val warm = Color.parseColor("#FFD700")
    private var dialog: AlertDialog? = null

    fun show() {
        val focusables = mutableListOf<View>()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_dialog_crayon_panel)
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
            setTextColor(Color.argb(218, 255, 255, 255))
            setPadding(0, dp(12), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val listButton = optionButton(
            title = "影片列表页",
            desc = "从当前页面提取影片条目，选择影片后再解析详情页"
        ) {
            dialog?.dismiss()
            onListPage()
        }
        val detailButton = optionButton(
            title = "影片详情页",
            desc = "直接复用原有详情页解析流程"
        ) {
            dialog?.dismiss()
            onDetailPage()
        }
        focusables.add(listButton)
        focusables.add(detailButton)
        content.addView(listButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { topMargin = dp(18) })
        content.addView(detailButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { topMargin = dp(10) })

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
        addView(TextView(context).apply {
            text = "选择网页类型"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
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
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
    }

    private fun optionBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(42, 32, 34, 40))
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
