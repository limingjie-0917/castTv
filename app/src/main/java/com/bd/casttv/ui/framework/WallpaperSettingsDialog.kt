package com.bd.casttv.ui.framework

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.bd.casttv.R
import com.bd.casttv.settings.Settings
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.util.NetworkUtils
import com.bd.casttv.util.QrCodeGenerator
import com.bd.casttv.util.ThemeManager
import java.io.File

/**
 * 壁纸设置弹窗：
 * - 使用项目弹窗规范（渐变面板、圆形贴纸、统一按钮样式、4dp 内容边距）
 * - 所有配置先写入本地草稿，只有点击「应用」后才真正落库并刷新壁纸
 * - 点击「取消」或直接返回关闭时，不保存任何改动
 */
class WallpaperSettingsDialog(
    private val context: Context,
    private val onChanged: () -> Unit,
    private val onClosed: () -> Unit = {}
) {

    private val settings = Settings(context)
    private val WARM = Color.rgb(245, 196, 81)
    private val CARD_BG = Color.argb(135, 20, 24, 32)
    private val STROKE_NORMAL = Color.argb(170, 210, 214, 222)

    private data class DraftConfig(
        var enabled: Boolean,
        var source: String,
        var dim: Int,
        var blur: Int,
        var bgType: String,
        var colorMode: String,
        var solidColor: String,
        var gradientA: String,
        var gradientB: String,
    ) {
        companion object {
            fun from(config: WallpaperManager.Config): DraftConfig = DraftConfig(
                enabled = config.enabled,
                source = config.source,
                dim = config.dim,
                blur = config.blur,
                bgType = config.bgType,
                colorMode = config.colorMode,
                solidColor = config.solidColor,
                gradientA = config.gradientA,
                gradientB = config.gradientB,
            )
        }
    }

    // 预置色块颜色
    private val PRESET_SOLID_COLORS = listOf(
        "#1A1A2E", "#16213E", "#0F3460", "#533483",
        "#2C3333", "#1B1B2F", "#162447", "#1F4068",
        "#0D0D0D", "#121212", "#1E1E2E", "#2D2D3A"
    )

    fun show() {
        val originalConfig = DraftConfig.from(WallpaperManager.currentConfig(context))
        val draft = DraftConfig.from(WallpaperManager.currentConfig(context))
        val pendingDeletedFiles = linkedSetOf<File>()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = themedDialogBackground()
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            clipChildren = true
            clipToPadding = true
        }
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "壁纸设置"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        content.addView(TextView(context).apply {
            text = "在这里先调整壁纸草稿，只有点击“应用”后才会真正保存并刷新；直接返回或点“取消”都不会生效。"
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        val scrollView = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            clipChildren = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        scrollView.addView(root)
        content.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))

        fun visibleCustomWallpapers(): List<File> {
            return WallpaperManager.listCustomWallpapers(context).filterNot { file ->
                pendingDeletedFiles.any { it.absolutePath == file.absolutePath }
            }
        }

        fun selectedCustomFile(): File? {
            val source = draft.source
            if (!source.startsWith("custom:")) return null
            val fileName = source.removePrefix("custom:")
            return visibleCustomWallpapers().firstOrNull { it.name == fileName }
        }

        val switchRow = makeSwitchRow("全屏壁纸开关", draft.enabled) { isOn ->
            draft.enabled = isOn
        }
        root.addView(switchRow)

        root.addView(makeLabel("背景类型"))
        val bgTypeGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val rbImage = RadioButton(context).apply {
            text = "壁纸图片"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        val rbColor = RadioButton(context).apply {
            text = "背景色"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        bgTypeGroup.addView(rbImage)
        bgTypeGroup.addView(rbColor)
        bgTypeGroup.check(if (draft.bgType == Settings.BG_TYPE_COLOR) rbColor.id else rbImage.id)
        root.addView(bgTypeGroup)

        val imageContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (draft.bgType == Settings.BG_TYPE_IMAGE) View.VISIBLE else View.GONE
        }
        val colorContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (draft.bgType == Settings.BG_TYPE_COLOR) View.VISIBLE else View.GONE
        }

        imageContainer.addView(makeLabel("预置壁纸"))
        val presetScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        presetScroll.addView(presetRow)
        imageContainer.addView(presetScroll)

        imageContainer.addView(makeLabel("自定义壁纸"))
        val customScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val customRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        customScroll.addView(customRow)
        imageContainer.addView(customScroll)

        val blurLabel = makeLabel("模糊度: ${draft.blur}")
        imageContainer.addView(blurLabel)
        val blurSeek = SeekBar(context).apply {
            max = 30
            progress = draft.blur
            setPadding(0, dp(4), 0, dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    draft.blur = progress
                    blurLabel.text = "模糊度: $progress"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        imageContainer.addView(blurSeek)

        val dimLabel = makeLabel("遮罩浓度: ${draft.dim}")
        imageContainer.addView(dimLabel)
        val dimSeek = SeekBar(context).apply {
            max = 80
            progress = draft.dim
            setPadding(0, dp(4), 0, dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    draft.dim = progress
                    dimLabel.text = "遮罩浓度: $progress"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        imageContainer.addView(dimSeek)
        root.addView(imageContainer)

        val colorModeGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        val rbSolid = RadioButton(context).apply {
            text = "纯色"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        val rbGradient = RadioButton(context).apply {
            text = "渐变色"
            setTextColor(Color.WHITE)
            id = View.generateViewId()
        }
        colorModeGroup.addView(rbSolid)
        colorModeGroup.addView(rbGradient)
        colorModeGroup.check(if (draft.colorMode == Settings.COLOR_MODE_GRADIENT) rbGradient.id else rbSolid.id)
        colorContainer.addView(colorModeGroup)

        val solidPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (draft.colorMode == Settings.COLOR_MODE_SOLID) View.VISIBLE else View.GONE
        }
        solidPanel.addView(makeLabel("纯色选择"))
        solidPanel.addView(makeColorPalette({ draft.solidColor }) { color -> draft.solidColor = color })
        solidPanel.addView(makeHexInput({ draft.solidColor }) { color -> draft.solidColor = color })
        colorContainer.addView(solidPanel)

        val gradientPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (draft.colorMode == Settings.COLOR_MODE_GRADIENT) View.VISIBLE else View.GONE
        }
        gradientPanel.addView(makeLabel("渐变色 A"))
        gradientPanel.addView(makeColorPalette({ draft.gradientA }) { color -> draft.gradientA = color })
        gradientPanel.addView(makeHexInput({ draft.gradientA }) { color -> draft.gradientA = color })
        gradientPanel.addView(makeLabel("渐变色 B"))
        gradientPanel.addView(makeColorPalette({ draft.gradientB }) { color -> draft.gradientB = color })
        gradientPanel.addView(makeHexInput({ draft.gradientB }) { color -> draft.gradientB = color })
        colorContainer.addView(gradientPanel)
        root.addView(colorContainer)

        lateinit var refreshPresetWallpapers: () -> Unit
        lateinit var refreshCustomWallpapers: () -> Unit

        refreshPresetWallpapers = {
            presetRow.removeAllViews()
            presetRow.addView(makeNoWallpaperThumb(draft) {
                draft.source = WallpaperManager.SOURCE_NONE
                refreshPresetWallpapers()
                refreshCustomWallpapers()
            })
            WallpaperManager.PRESET_WALLPAPERS.take(3).forEach { presetId ->
                presetRow.addView(makePresetThumb(presetId, draft) {
                    draft.source = presetId
                    refreshPresetWallpapers()
                    refreshCustomWallpapers()
                })
            }
        }

        refreshCustomWallpapers = {
            customRow.removeAllViews()
            visibleCustomWallpapers().forEach { file ->
                customRow.addView(makeCustomThumb(file, draft) {
                    showCustomWallpaperActions(
                        file = file,
                        draft = draft,
                        onSelect = {
                            draft.source = "custom:${file.name}"
                            refreshPresetWallpapers()
                            refreshCustomWallpapers()
                            toast("已选中 ${file.name}，点击“应用”后生效")
                        },
                        onDelete = {
                            pendingDeletedFiles.add(file)
                            if (draft.source == "custom:${file.name}") {
                                draft.source = WallpaperManager.SOURCE_NONE
                            }
                            refreshPresetWallpapers()
                            refreshCustomWallpapers()
                            toast("已从草稿中移除 ${file.name}，点击“应用”后删除")
                        }
                    )
                })
            }
            customRow.addView(makeUploadSlot {
                showUploadQrDialog {
                    refreshCustomWallpapers()
                }
            })
            if (visibleCustomWallpapers().isEmpty()) {
                customRow.addView(TextView(context).apply {
                    text = "暂无自定义壁纸，可从右侧入口上传"
                    textSize = 13f
                    setTextColor(Color.argb(150, 255, 255, 255))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                })
            }
        }

        refreshPresetWallpapers()
        refreshCustomWallpapers()

        bgTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            val type = if (checkedId == rbColor.id) Settings.BG_TYPE_COLOR else Settings.BG_TYPE_IMAGE
            draft.bgType = type
            imageContainer.visibility = if (type == Settings.BG_TYPE_IMAGE) View.VISIBLE else View.GONE
            colorContainer.visibility = if (type == Settings.BG_TYPE_COLOR) View.VISIBLE else View.GONE
        }

        colorModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == rbGradient.id) Settings.COLOR_MODE_GRADIENT else Settings.COLOR_MODE_SOLID
            draft.colorMode = mode
            solidPanel.visibility = if (mode == Settings.COLOR_MODE_SOLID) View.VISIBLE else View.GONE
            gradientPanel.visibility = if (mode == Settings.COLOR_MODE_GRADIENT) View.VISIBLE else View.GONE
        }

        lateinit var dialog: AlertDialog
        fun restoreDraftToOriginal() {
            draft.enabled = originalConfig.enabled
            draft.source = originalConfig.source
            draft.dim = originalConfig.dim
            draft.blur = originalConfig.blur
            draft.bgType = originalConfig.bgType
            draft.colorMode = originalConfig.colorMode
            draft.solidColor = originalConfig.solidColor
            draft.gradientA = originalConfig.gradientA
            draft.gradientB = originalConfig.gradientB
            pendingDeletedFiles.clear()
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val cancelBtn = dialogButton("取消") {
            restoreDraftToOriginal()
            dialog.dismiss()
        }
        val applyBtn = dialogButton("应用") {
            persistDraft(draft)
            pendingDeletedFiles.forEach { file ->
                WallpaperManager.deleteCustomWallpaper(context, file)
            }
            WallpaperManager.notifyChanged(context)
            onChanged()
            dialog.dismiss()
        }
        buttonRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        buttonRow.addView(applyBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        content.addView(buttonRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(dp(680), dp(540))
            switchRow.getChildAt(1)?.requestFocus()
        }
        dialog.setOnDismissListener { onClosed() }
        dialog.setOnCancelListener {
            restoreDraftToOriginal()
        }
        dialog.show()
    }

    private fun persistDraft(draft: DraftConfig) {
        settings.wallpaperEnabled = draft.enabled
        settings.wallpaperSource = draft.source
        settings.wallpaperDim = draft.dim
        settings.wallpaperBlur = draft.blur
        settings.wallpaperBgType = draft.bgType
        settings.wallpaperColorMode = draft.colorMode
        settings.wallpaperSolidColor = draft.solidColor
        settings.wallpaperGradientColorA = draft.gradientA
        settings.wallpaperGradientColorB = draft.gradientB
    }

    private fun makeLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.argb(200, 255, 255, 255))
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun makeSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeCardBg(false)
            setPadding(dp(16), 0, dp(12), 0)
            clipChildren = false
            clipToPadding = false
        }
        val tv = TextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
        }
        val sw = SwitchCompat(context).apply {
            isFocusable = true
            isClickable = true
            isChecked = checked
            showText = false
            textOn = ""
            textOff = ""
            splitTrack = false
            setThumbResource(R.drawable.switch_ios_thumb)
            setTrackResource(R.drawable.switch_ios_track)
            setOnFocusChangeListener { _, has -> row.background = makeCardBg(has) }
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        lp.bottomMargin = dp(8)
        row.layoutParams = lp
        return row
    }

    private fun makeNoWallpaperThumb(draft: DraftConfig, onClick: () -> Unit): FrameLayout {
        val frame = FrameLayout(context).apply {
            isFocusable = true
            val lp = LinearLayout.LayoutParams(dp(96), dp(56))
            lp.marginEnd = dp(8)
            layoutParams = lp
            background = makeNoWallpaperBg(draft.source == WallpaperManager.SOURCE_NONE)
        }
        val tv = TextView(context).apply {
            text = "无壁纸"
            textSize = 14f
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(tv)
        frame.setOnClickListener { onClick() }
        frame.setOnFocusChangeListener { _, hasFocus ->
            frame.background = makeNoWallpaperBg(draft.source == WallpaperManager.SOURCE_NONE || hasFocus)
        }
        return frame
    }

    private fun makePresetThumb(presetId: String, draft: DraftConfig, onClick: () -> Unit): FrameLayout {
        val frame = FrameLayout(context).apply {
            isFocusable = true
            val lp = LinearLayout.LayoutParams(dp(96), dp(56))
            lp.marginEnd = dp(8)
            layoutParams = lp
        }
        // 壁纸图片：1dp 内边距 + 圆角裁剪，使聚焦时描边与图片之间有清晰间隙。
        val pad = dp(1)
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(WallpaperManager.resolveDrawable(presetId))
            setPadding(pad, pad, pad, pad)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    val r = dp(8).toFloat()
                    outline.setRoundRect(pad, pad, view.width - pad, view.height - pad, r)
                }
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val border = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            background = makeThumbBorder(draft.source == presetId)
        }
        frame.addView(img)
        frame.addView(border)
        frame.setOnClickListener { onClick() }
        frame.setOnFocusChangeListener { _, hasFocus ->
            border.background = makeThumbBorder(draft.source == presetId || hasFocus)
        }
        return frame
    }

    private fun makeUploadSlot(onClick: () -> Unit): FrameLayout {
        val frame = FrameLayout(context).apply {
            isFocusable = true
            val lp = LinearLayout.LayoutParams(dp(96), dp(56))
            lp.marginEnd = dp(8)
            layoutParams = lp
            background = makeDashedUploadBg(false)
        }
        val tv = TextView(context).apply {
            text = "＋上传"
            textSize = 14f
            setTextColor(WARM)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(tv)
        frame.setOnClickListener { onClick() }
        frame.setOnFocusChangeListener { _, hasFocus -> frame.background = makeDashedUploadBg(hasFocus) }
        return frame
    }

    private fun makeCustomThumb(file: File, draft: DraftConfig, onClick: () -> Unit): FrameLayout {
        val frame = FrameLayout(context).apply {
            isFocusable = true
            val lp = LinearLayout.LayoutParams(dp(96), dp(56))
            lp.marginEnd = dp(8)
            layoutParams = lp
        }
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                setImageBitmap(BitmapFactory.decodeFile(file.absolutePath, opts))
            } catch (_: Throwable) {
            }
        }
        val border = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            background = makeThumbBorder(draft.source == "custom:${file.name}")
        }
        frame.addView(img)
        frame.addView(border)
        frame.setOnClickListener { onClick() }
        frame.setOnFocusChangeListener { _, hasFocus ->
            border.background = makeThumbBorder(draft.source == "custom:${file.name}" || hasFocus)
        }
        return frame
    }

    private fun showCustomWallpaperActions(
        file: File,
        draft: DraftConfig,
        onSelect: () -> Unit,
        onDelete: () -> Unit,
    ) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = themedDialogBackground()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(TextView(context).apply {
            text = file.name
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        content.addView(TextView(context).apply {
            text = if (draft.source == "custom:${file.name}") "当前已在草稿中选中这张壁纸。" else "你可以先把它设为当前草稿，或加入待删除列表。"
            textSize = 13.5f
            setTextColor(Color.argb(210, 255, 255, 255))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        lateinit var dialog: AlertDialog
        val selectBtn = dialogButton("设为当前") {
            onSelect()
            dialog.dismiss()
        }
        val deleteBtn = dialogButton("删除") {
            onDelete()
            dialog.dismiss()
        }
        val cancelBtn = dialogButton("取消") {
            dialog.dismiss()
        }
        content.addView(selectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        content.addView(deleteBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        content.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT)
            selectBtn.requestFocus()
        }
        dialog.show()
    }

    private fun showUploadQrDialog(onDismissRefresh: () -> Unit) {
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip.isNullOrBlank()) {
            toast("无法获取局域网 IP，请确认网络连接")
            return
        }
        val url = "http://$ip:8899/wallpaper"
        val qrBitmap = QrCodeGenerator.encode(url, 480)

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = themedDialogBackground()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        content.addView(TextView(context).apply {
            text = "扫码上传壁纸"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        content.addView(TextView(context).apply {
            text = "手机连接同一局域网后，扫描二维码上传图片。上传成功后会出现在“自定义壁纸”区域，仍需点击主弹窗“应用”才会切换为当前壁纸。\n$url"
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        if (qrBitmap != null) {
            content.addView(ImageView(context).apply {
                setImageBitmap(qrBitmap)
            }, LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(14) })
        }

        lateinit var dialog: AlertDialog
        val closeBtn = dialogButton("关闭") {
            dialog.dismiss()
            onDismissRefresh()
        }
        content.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT)
            closeBtn.requestFocus()
        }
        dialog.show()
    }

    private fun makeColorPalette(selectedProvider: () -> String, onPick: (String) -> Unit): HorizontalScrollView {
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(8))
        }
        fun refreshSwatches() {
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                val childHex = PRESET_SOLID_COLORS.getOrNull(i) ?: continue
                child.background = makeSwatchBg(childHex, childHex.equals(selectedProvider(), ignoreCase = true))
            }
        }
        PRESET_SOLID_COLORS.forEach { hex ->
            val swatch = View(context).apply {
                val lp = LinearLayout.LayoutParams(dp(36), dp(36))
                lp.marginEnd = dp(8)
                layoutParams = lp
                isFocusable = true
                background = makeSwatchBg(hex, hex.equals(selectedProvider(), ignoreCase = true))
                setOnClickListener {
                    onPick(hex)
                    refreshSwatches()
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.background = makeSwatchBg(hex, hex.equals(selectedProvider(), ignoreCase = true) || hasFocus)
                }
            }
            row.addView(swatch)
        }
        scroll.addView(row)
        return scroll
    }

    private fun makeHexInput(valueProvider: () -> String, onApply: (String) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
            clipChildren = false
            clipToPadding = false
        }
        val input = EditText(context).apply {
            setText(valueProvider())
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(100, 255, 255, 255))
            hint = "#RRGGBB"
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(1), STROKE_NORMAL)
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = dp(8)
            layoutParams = lp
        }
        val setBtn = dialogButton("设置") {
            val hex = input.text.toString().trim()
            if (hex.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                val normalized = hex.uppercase()
                onApply(normalized)
                input.setText(normalized)
                input.setSelection(normalized.length)
                toast("已更新草稿颜色 $normalized，点击“应用”后生效")
            } else {
                toast("请输入有效的十六进制颜色值 #RRGGBB")
            }
        }
        row.addView(input)
        row.addView(setBtn, LinearLayout.LayoutParams(dp(92), dp(40)))
        return row
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = false
        fun refresh(focused: Boolean) {
            val selected = isSelected
            setTextColor(if (selected) WARM else Color.argb(235, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else STROKE_NORMAL)
                setColor(Color.argb(52, 32, 34, 40))
            }
        }
        refresh(false)
        setOnFocusChangeListener { _, hasFocus ->
            refresh(hasFocus)
        }
        setOnClickListener { click() }
    }

    private fun themedDialogBackground(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        ThemeManager.currentPalette(context).dialogTitleGradient
    ).apply {
        cornerRadius = dp(18).toFloat()
        setStroke(dp(2), WARM)
    }

    private fun makeCardBg(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(CARD_BG)
        setStroke(dp(if (focused) 2 else 1), if (focused) WARM else STROKE_NORMAL)
    }

    private fun makeDashedUploadBg(focused: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Color.argb(70, 30, 34, 44))
        setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(130, 245, 196, 81), dp(8).toFloat(), dp(5).toFloat())
    }

    private fun makeNoWallpaperBg(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(Color.argb(85, 16, 18, 24))
        setStroke(dp(if (selected) 2 else 1), if (selected) WARM else STROKE_NORMAL)
    }

    private fun makeThumbBorder(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(dp(if (selected) 2 else 1), if (selected) WARM else Color.TRANSPARENT)
    }

    private fun makeSwatchBg(hex: String, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(8).toFloat()
        try {
            setColor(Color.parseColor(hex))
        } catch (_: Throwable) {
            setColor(Color.DKGRAY)
        }
        setStroke(dp(if (selected) 2 else 1), if (selected) WARM else STROKE_NORMAL)
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
