package com.bd.casttv.ui

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.bd.casttv.R
import com.bd.casttv.util.NetworkUtils
import com.bd.casttv.util.QrCodeGenerator

/**
 * 手机交互中心弹窗辅助类。仅负责渲染 `dialog_phone_hub.xml` 与生成 QR 码。
 * HTTP 服务启停由调用方提供回调，避免弹窗 dismiss 时误停止后台服务。
 */
object PhoneHubDialog {

    fun show(
        context: Context,
        port: Int,
        running: Boolean,
        onStart: () -> Int,
        onStop: () -> Unit,
    ): AlertDialog {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_phone_hub, null, false)
        val qr = view.findViewById<ImageView>(R.id.phoneHubQr)
        val qrFallback = view.findViewById<TextView>(R.id.phoneHubQrFallback)
        val urlText = view.findViewById<TextView>(R.id.phoneHubUrl)
        val status = view.findViewById<TextView>(R.id.phoneHubStatus)
        val serviceSwitch = view.findViewById<SwitchCompat>(R.id.phoneHubSwitch)
        val serviceSwitchRow = view.findViewById<LinearLayout>(R.id.phoneHubSwitchRow)
        serviceSwitch.showText = false
        serviceSwitch.textOn = ""
        serviceSwitch.textOff = ""
        serviceSwitch.splitTrack = false
        serviceSwitch.setThumbResource(R.drawable.switch_ios_thumb)
        serviceSwitch.setTrackResource(R.drawable.switch_ios_track)
        serviceSwitch.minWidth = dp(context, 51)
        serviceSwitch.minimumWidth = dp(context, 51)
        serviceSwitch.minHeight = dp(context, 31)

        fun render(isRunning: Boolean, activePort: Int) {
            serviceSwitch.text = if (isRunning) "已开启" else "已关闭"
            if (isRunning && activePort > 0) {
                val ip = try {
                    NetworkUtils.getLocalIpAddress() ?: "0.0.0.0"
                } catch (_: Throwable) { "0.0.0.0" }
                val url = "http://$ip:$activePort/"
                urlText.text = url
                val bmp = QrCodeGenerator.encode(url, 480)
                if (bmp != null) {
                    qr.setImageBitmap(bmp)
                    qr.visibility = View.VISIBLE
                    qrFallback.visibility = View.GONE
                } else {
                    qr.setImageDrawable(null)
                    qr.visibility = View.GONE
                    qrFallback.text = "⚠️\n二维码生成失败\n请手动输入上方地址"
                    qrFallback.visibility = View.VISIBLE
                }
                qr.alpha = 1f
                status.text = "✅ 服务已启动 · 关闭弹窗后仍在后台运行"
                status.setTextColor(0xFF43B97F.toInt())
            } else {
                urlText.text = "服务未开启，打开上方开关后生成地址"
                qr.setImageDrawable(null)
                qr.visibility = View.GONE
                qrFallback.text = "📡\n请先开启 HTTP 服务\n开启后这里会显示二维码"
                qrFallback.visibility = View.VISIBLE
                qr.alpha = 0.22f
                status.text = "⏸ 服务已关闭 · 手机端暂时无法访问电视"
                status.setTextColor(0xFFF6C445.toInt())
            }
        }

        serviceSwitch.isChecked = running
        render(running, port)
        serviceSwitch.setOnCheckedChangeListener { button, checked ->
            if (checked) {
                val newPort = onStart()
                if (newPort > 0) {
                    render(true, newPort)
                } else {
                    button.isChecked = false
                    render(false, 0)
                }
            } else {
                onStop()
                render(false, 0)
            }
        }

        serviceSwitch.isFocusable = true
        serviceSwitch.isFocusableInTouchMode = true
        serviceSwitch.isClickable = true
        serviceSwitchRow.setOnClickListener { serviceSwitch.performClick() }
        serviceSwitchRow.setOnFocusChangeListener { _, has -> if (has) serviceSwitch.requestFocus() }
        serviceSwitch.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_UP) serviceSwitch.toggle()
                true
            } else {
                false
            }
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(view)
            .create()
        dialog.setOnShowListener {
            val lp = dialog.window?.attributes
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                dialog.window?.attributes = lp
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                serviceSwitch.post { serviceSwitch.requestFocus() }
            }
        }
        dialog.show()
        return dialog
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
