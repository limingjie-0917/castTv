package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.webparse.WebParseRequestBus
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class WebParseQrDialog(
    private val context: Context,
    private val pageUrl: String
) : WebParseRequestBus.Listener {
    private val warm = Color.parseColor("#FFD700")
    private var dialog: AlertDialog? = null

    fun show() {
        WebParseRequestBus.addListener(this, replayPending = false)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = dialogPanelBg()
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            clipChildren = false
            clipToPadding = false
        }
        content.addView(titleView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val qrBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = cardBg()
        }
        val qr = ImageView(context).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            val bitmap = makeQr(pageUrl.ifBlank { "http://TV_IP:端口/parse" }, dp(220), dp(220))
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(R.drawable.ic_phone_qrcode)
        }
        qrBox.addView(qr, LinearLayout.LayoutParams(dp(224), dp(224)))
        qrBox.addView(TextView(context).apply {
            text = pageUrl.ifBlank { "手机交互服务启动中，请稍后重试" }
            textSize = 14f
            setTextColor(Color.parseColor("#F0F8FF"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        qrBox.addView(TextView(context).apply {
            text = "扫码后在手机端粘贴网址并点击解析"
            textSize = 15f
            setTextColor(Color.parseColor("#B0C4DE"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(qrBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

        val close = dialogButton("关闭") { dialog?.dismiss() }
        close.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                BoundaryFocusHandler.shake(v)
                true
            } else false
        }
        content.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(close, LinearLayout.LayoutParams(dp(112), dp(42)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        panel.addView(content)

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnDismissListener { WebParseRequestBus.removeListener(this) }
            d.setOnShowListener { close.requestFocus() }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(560), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    override fun onWebParseUrl(url: String) {
        if (url.isNotBlank()) dialog?.dismiss()
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
            text = "手机扫码输入"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            val selected = isSelected
            setTextColor(if (selected) warm else Color.argb(238, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has -> refresh(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        setOnClickListener { click() }
    }

    private fun dialogPanelBg() = GradientDrawable().apply {
        cornerRadius = dp(26).toFloat()
        setColor(Color.parseColor("#4169E1"))
        setStroke(dp(1), Color.argb(90, 255, 255, 255))
    }

    private fun cardBg() = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.argb(18, 255, 255, 255))
        setStroke(dp(1), Color.argb(61, 255, 255, 255))
    }

    private fun makeQr(text: String, width: Int, height: Int): Bitmap? = try {
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, width, height, hints)
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
        }
    } catch (_: Throwable) { null }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
