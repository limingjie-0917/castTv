package com.bd.casttv.ui.framework.demo

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler

open class HelloPage(context: Context, override val pageId: String, override val pageTitle: String) : BasePage(context) {
    override val pageIconRes: Int = android.R.drawable.star_big_on
    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(96), dp(60), dp(96), dp(120)) }
        root.addView(TextView(context).apply { text = "$pageTitle Demo"; textSize = 34f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        repeat(4) { i -> row.addView(Button(context).apply { text = "按钮 ${i + 1}"; setOnClickListener { Toast.makeText(context, "$pageTitle / 按钮 ${i + 1}", Toast.LENGTH_SHORT).show() }; setOnKeyListener { v, _, e -> handleButtonKey(v, e, i) } }, LinearLayout.LayoutParams(dp(150), dp(64)).apply { setMargins(dp(10), dp(28), dp(10), 0) }) }
        root.addView(row)
        contentContainer.addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private fun handleButtonKey(v: View, e: KeyEvent, index: Int): Boolean = when (e.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0) BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_LEFT, this) else false
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (index == 3) BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_RIGHT, this) else false
        KeyEvent.KEYCODE_DPAD_UP -> BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_UP, this)
        KeyEvent.KEYCODE_DPAD_DOWN -> BoundaryFocusHandler.onContentBoundary(v, e, View.FOCUS_DOWN, this)
        KeyEvent.KEYCODE_BACK -> { focusToRoot(); true }
        else -> false
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
class HomeDemoPage(context: Context) : HelloPage(context, "home", "首页")
class FavoritesDemoPage(context: Context) : HelloPage(context, "favorites", "收藏")
class SettingsDemoPage(context: Context) : HelloPage(context, "settings", "设置")
