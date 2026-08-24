package com.bd.casttv.ui.framework.pages

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.databinding.DialogExitConfirmBinding
import com.bd.casttv.ui.framework.FocusFxHelper

/**
 * 新框架下的 App 退出确认弹窗：
 *  - 保留现有贴纸装饰与布局结构；
 *  - 整体配色改为与 WebParseQrDialog 一致的皇家蓝风格；
 *  - 默认焦点落在「取消」按钮；「确认退出」执行 [Activity.finish]。
 */
object ExitConfirmDialog {
    private const val PANEL_BLUE = "#4169E1"
    private const val TITLE_WARM = "#FFD700"
    private const val BODY_TEXT = "#F0F8FF"
    private const val BUTTON_TEXT = "#F5F5F5"

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val binding = DialogExitConfirmBinding.inflate(LayoutInflater.from(activity))
        applyRoyalBlueTheme(binding)
        val dialog = AlertDialog.Builder(activity, R.style.Theme_CastTV_Dialog)
            .setView(binding.root)
            .create()
        binding.btnExitConfirm.setOnClickListener {
            dialog.dismiss()
            activity.finish()
        }
        binding.btnExitCancel.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            binding.root.post {
                resizeTitleSticker(binding)
            }
            binding.btnExitCancel.requestFocus()
        }
        dialog.show()
    }

    private fun resizeTitleSticker(binding: DialogExitConfirmBinding) {
        val cardWidth = binding.exitDialogCard.width
        if (cardWidth <= 0) return
        val stickerWidth = (cardWidth * 0.8f).toInt()
        val stickerHeight = (stickerWidth * 27f / 96f).toInt()
        binding.ivExitTitleSticker.layoutParams = binding.ivExitTitleSticker.layoutParams.also {
            it.width = stickerWidth
            it.height = stickerHeight
        }
    }

    private fun applyRoyalBlueTheme(binding: DialogExitConfirmBinding) {
        val warm = Color.parseColor(TITLE_WARM)
        val body = Color.parseColor(BODY_TEXT)
        val buttonText = Color.parseColor(BUTTON_TEXT)
        binding.exitDialogCard.background = panelBg()
        binding.tvExitTitle.setTextColor(warm)
        binding.tvExitMessage.setTextColor(body)
        styleDialogButton(binding.btnExitCancel, warm, buttonText)
        styleDialogButton(binding.btnExitConfirm, warm, buttonText)
    }

    private fun styleDialogButton(button: TextView, warm: Int, buttonText: Int) {
        button.typeface = Typeface.DEFAULT_BOLD
        fun refresh(focused: Boolean) {
            button.setTextColor(buttonText)
            button.background = GradientDrawable().apply {
                cornerRadius = dp(button, 10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(button, if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        button.setOnFocusChangeListener { v, hasFocus ->
            refresh(hasFocus)
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 10)
        }
    }

    private fun panelBg(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 26f.dpPx.toFloat()
        setColor(Color.parseColor(PANEL_BLUE))
        setStroke(1f.dpPx, Color.argb(90, 255, 255, 255))
    }

    private fun dp(view: TextView, value: Int): Int = (value * view.resources.displayMetrics.density).toInt()

    private val Float.dpPx: Int
        get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
