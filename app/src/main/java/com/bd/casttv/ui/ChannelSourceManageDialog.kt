package com.bd.casttv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.favorites.HealthStatus
import com.bd.casttv.favorites.TabChannel
import com.bd.casttv.favorites.TabChannelSource
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.ThemeManager

/**
 * 频道「源管理」弹窗。
 *
 * 两级弹窗（源列表 + 操作菜单）均使用自定义面板，样式与设置弹窗保持一致，
 * 并跟随当前主题的 dialogTitleGradient 配色。
 */
class ChannelSourceManageDialog(
    private val context: Context,
    private val onSetPrimary: (channelKey: String, sourceId: String) -> Unit,
    private val onSetAbnormal: (channelKey: String, sourceId: String, abnormal: Boolean) -> Unit,
    private val onRecheck: (channel: TabChannel) -> Unit,
) {

    fun show(channel: TabChannel) {
        if (channel.sources.isEmpty()) {
            Toast.makeText(context, "该频道暂无源", Toast.LENGTH_SHORT).show()
            return
        }

        lateinit var dialog: AlertDialog
        val closeButton = dialogButton("关闭") { dialog.dismiss() }
        val panel = buildDialogPanel(
            title = "源管理 · ${channel.displayName}",
            subtitle = "选择一个源查看操作菜单。主源会以暖黄色文字高亮显示，异常源会保留异常标签与恢复提示。"
        )

        val sourceList = createScrollList(maxHeightDp = 360)
        // 源管理弹窗滚动容器：裁剪超出部分并增加 4dp 内边距
        sourceList.scroll.apply {
            clipChildren = true
            clipToPadding = true
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        channel.sources.forEachIndexed { index, source ->
            val sourceButton = sourceItemButton(source) {
                showActions(channel, source)
            }
            sourceButton.setOnKeyListener(verticalListKeyListener(index, channel.sources.lastIndex) { targetIndex ->
                if (targetIndex in 0..channel.sources.lastIndex) {
                    sourceList.list.getChildAt(targetIndex)?.requestFocus() == true
                } else {
                    closeButton.requestFocus()
                }
            })
            sourceList.list.addView(sourceButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                if (index > 0) topMargin = dp(8)
            })
        }
        panel.addView(sourceList.scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        })

        panel.addView(closeButton, LinearLayout.LayoutParams(
            dp(310),
            dp(44)
        ).apply {
            topMargin = dp(12)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        dialog = createPanelDialog(panel)
        closeButton.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> sourceList.list.getChildAt(channel.sources.lastIndex)?.requestFocus() == true
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    BoundaryFocusHandler.shake(v)
                    true
                }
                else -> false
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(620), WindowManager.LayoutParams.WRAP_CONTENT)
        sourceList.scroll.layoutParams = (sourceList.scroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (channel.sources.size > 5) dp(sourceList.maxHeightDp) else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        sourceList.scroll.requestLayout()
        sourceList.list.post { sourceList.list.getChildAt(0)?.requestFocus() }
    }

    private fun showActions(channel: TabChannel, source: TabChannelSource) {
        val actions = buildActionItems(channel, source)

        lateinit var dialog: AlertDialog
        val cancelButton = dialogButton("取消") { dialog.dismiss() }
        val panel = buildDialogPanel(
            title = source.item.title.ifBlank { "源操作" },
            subtitle = rowLabel(source)
        )

        val actionList = createScrollList(maxHeightDp = 280)
        actions.forEachIndexed { index, item ->
            val button = dialogButton(item.label) {
                dialog.dismiss()
                item.action.invoke()
            }.apply {
                isSelected = item.selected
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                applyDialogButtonStyle(this, focused = false)
            }
            button.setOnKeyListener(verticalListKeyListener(index, actions.lastIndex) { targetIndex ->
                if (targetIndex in 0..actions.lastIndex) {
                    actionList.list.getChildAt(targetIndex)?.requestFocus() == true
                } else {
                    cancelButton.requestFocus()
                }
            })
            actionList.list.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                if (index > 0) topMargin = dp(8)
            })
        }
        panel.addView(actionList.scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        })
        panel.addView(cancelButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply {
            topMargin = dp(12)
        })

        dialog = createPanelDialog(panel)
        cancelButton.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> actionList.list.getChildAt(actions.lastIndex)?.requestFocus() == true
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    BoundaryFocusHandler.shake(v)
                    true
                }
                else -> false
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(520), WindowManager.LayoutParams.WRAP_CONTENT)
        actionList.scroll.layoutParams = (actionList.scroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (actions.size > 4) dp(actionList.maxHeightDp) else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        actionList.scroll.requestLayout()
        actionList.list.post { actionList.list.getChildAt(0)?.requestFocus() }
    }

    private fun buildActionItems(channel: TabChannel, source: TabChannelSource): List<ActionItem> {
        val actions = ArrayList<ActionItem>()

        if (!source.abnormal && !source.isPrimary && source.item.uri.isNotBlank()) {
            actions += ActionItem(label = "设为主源", selected = true) {
                onSetPrimary(channel.channelKey, source.sourceId)
                Toast.makeText(context, "已设为当前频道主源", Toast.LENGTH_SHORT).show()
            }
        }
        if (source.abnormal) {
            val recoverable = detectedUsable(source)
            actions += ActionItem(
                label = if (recoverable) "恢复为可用源（检测正常）" else "恢复为可用源",
                selected = recoverable
            ) {
                onSetAbnormal(channel.channelKey, source.sourceId, false)
                Toast.makeText(context, "已恢复为可用源，可重新设为主源", Toast.LENGTH_SHORT).show()
            }
        } else {
            actions += ActionItem(label = "标记为异常源") {
                onSetAbnormal(channel.channelKey, source.sourceId, true)
                Toast.makeText(context, "已标记为异常源，将不参与播放", Toast.LENGTH_SHORT).show()
            }
        }

        if (channel.sources.any { it.item.uri.isNotBlank() }) {
            actions += ActionItem(label = "重新检测该频道") {
                onRecheck(channel)
                Toast.makeText(context, "已开始重新检测该频道", Toast.LENGTH_SHORT).show()
            }
        }
        return actions
    }

    private fun buildDialogPanel(title: String, subtitle: String): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                ThemeManager.currentPalette(context).dialogTitleGradient
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(82, 20, 22, 28))
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            rightMargin = dp(12)
        })
        header.addView(TextView(context).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header)

        if (subtitle.isNotBlank()) {
            content.addView(TextView(context).apply {
                text = subtitle
                textSize = 13.5f
                setTextColor(Color.argb(220, 255, 255, 255))
                setLineSpacing(dp(2).toFloat(), 1f)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            })
        }

        panel.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return panel
    }

    private fun createScrollList(maxHeightDp: Int): ScrollListViews {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        return ScrollListViews(scroll = scroll, list = list, maxHeightDp = maxHeightDp)
    }

    private fun createPanelDialog(panel: View): AlertDialog {
        return AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.window?.apply {
                        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                        setGravity(Gravity.CENTER)
                    }
                }
            }
    }

    private fun sourceItemButton(source: TabChannelSource, click: () -> Unit): TextView {
        return dialogButton(rowLabel(source), click).apply {
            isSelected = source.isPrimary
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), 0, dp(14), 0)
            applyDialogButtonStyle(this, focused = false)
        }
    }

    private fun verticalListKeyListener(index: Int, lastIndex: Int, move: (Int) -> Boolean): View.OnKeyListener {
        return View.OnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val target = index - 1
                    if (target >= 0) {
                        move(target)
                    } else {
                        BoundaryFocusHandler.shake(v)
                        true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val target = index + 1
                    if (target <= lastIndex) {
                        move(target)
                    } else {
                        move(target)
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    BoundaryFocusHandler.shake(v)
                    true
                }
                else -> false
            }
        }
    }

    private fun applyDialogButtonStyle(view: TextView, focused: Boolean) {
        val selected = view.isSelected
        view.setTextColor(if (selected) WARM else Color.argb(235, 245, 245, 245))
        view.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(170, 210, 214, 222))
            setColor(Color.argb(52, 32, 34, 40))
        }
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        applyDialogButtonStyle(this, focused = false)
        setOnFocusChangeListener { v, has ->
            applyDialogButtonStyle(this, focused = has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
        }
        setOnClickListener { click() }
    }

    private fun rowLabel(source: TabChannelSource): String {
        val tag = when {
            source.abnormal -> "异常"
            source.isPrimary -> "✓ 主源"
            else -> "备源"
        }
        val extra = buildString {
            if (source.item.source.isNotBlank()) append(source.item.source)
            if (source.item.resolution.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(source.item.resolution)
            }
        }
        val health = when (source.health.status) {
            HealthStatus.HEALTHY -> "稳定"
            HealthStatus.DEGRADED -> if (source.health.reason.contains("无声")) "无声音" else "卡顿/较慢"
            HealthStatus.UNHEALTHY -> "无法连接"
            HealthStatus.UNKNOWN -> "未检测"
        }
        val recoverHint = if (source.abnormal && detectedUsable(source)) " · 检测正常，可恢复" else ""
        val detail = buildString {
            if (extra.isNotBlank()) append(extra)
            if (isNotEmpty()) append(" · ")
            append(health)
        }
        return "[$tag] ${source.item.title}（$detail$recoverHint）"
    }

    /** 后台检测判定为「可播放」（稳定或有瑕疵但能播）即视为可恢复。 */
    private fun detectedUsable(source: TabChannelSource): Boolean {
        return source.health.status == HealthStatus.HEALTHY ||
            source.health.status == HealthStatus.DEGRADED
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private data class ActionItem(
        val label: String,
        val selected: Boolean = false,
        val action: () -> Unit,
    )

    private data class ScrollListViews(
        val scroll: ScrollView,
        val list: LinearLayout,
        val maxHeightDp: Int,
    )

    companion object {
        private val WARM = Color.rgb(245, 196, 81)
    }
}
