package com.bd.casttv.ui.framework.pages

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
import com.bd.casttv.util.QrCodeGenerator
import com.bd.casttv.webparse.AdapterKind
import com.bd.casttv.webparse.JsonAdapterSpecBuilder
import com.bd.casttv.webparse.ParsePageKind
import com.bd.casttv.webparse.RuleBasedAdapter
import com.bd.casttv.webparse.WebFrameworkType
import com.bd.casttv.webparse.WebParseAdapterStore
import java.io.File

class JsonAdapterDialog(
    private val context: Context,
    private val currentUrlProvider: () -> String,
    private val uploadPageUrlProvider: () -> String,
    private val pageKindProvider: () -> ParsePageKind = { ParsePageKind.DETAIL },
    private val onUseRawJson: ((String) -> Unit)? = null,
    private val onUseRule: (String) -> Unit
) {
    private val warm = Color.parseColor("#FFD700")
    private val adapterStore = WebParseAdapterStore(context)
    private var dialog: AlertDialog? = null
    private lateinit var listBox: LinearLayout
    private lateinit var pasteInput: EditText

    fun show() {
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
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
        }
        content.addView(titleView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(16))
            clipChildren = false
            clipToPadding = false
        }
        val stepsScroll = ScrollView(context).apply {
            isFocusable = false
            isFillViewport = false
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(16))
            addView(scrollContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(stepsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })

        val copyBtn = dialogButton("下载 AI 规范") { saveAiSpecToDownloads() }
        val openDownloadsBtn = dialogButton("📁") { openDownloadsDir() }
        scrollContent.addView(stepBlock().apply {
            addView(stepTitle("① 第一步：下载 AI 规范"))
            addView(stepDesc("将当前页面源码 + 规范文档保存到 Downloads 目录，发给 AI"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                gravity = Gravity.START
                clipChildren = false
                clipToPadding = false
                addView(copyBtn, LinearLayout.LayoutParams(dp(148), dp(44)))
                addView(openDownloadsBtn, LinearLayout.LayoutParams(dp(52), dp(44)).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scrollContent.addView(stepBlock().apply {
            addView(stepTitle("② 第二步：让 AI 生成 JSON 规则"))
            addView(stepDesc("打开 DeepSeek / ChatGPT 等 AI，粘贴内容，要求 AI 生成 JSON 解析规则"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val savePasteBtn = dialogButton("保存并解析") { savePastedJson() }
        pasteInput = EditText(context).apply {
            hint = "将 AI 生成的 JSON 规则粘贴到这里…"
            textSize = 13.5f
            minLines = 4
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setTextColor(Color.argb(238, 245, 245, 245))
            setHintTextColor(Color.argb(200, 210, 214, 222))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = inputBg(false)
            isFocusable = true
            isFocusableInTouchMode = true
            showSoftInputOnFocus = false
            setOnFocusChangeListener { v, has ->
                background = inputBg(has)
                FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
            }
            setOnClickListener { showKeyboard(this) }
            setOnKeyListener { _, keyCode, e ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (e.action == KeyEvent.ACTION_UP) showKeyboard(this)
                    true
                } else {
                    false
                }
            }
        }
        scrollContent.addView(stepBlock().apply {
            addView(stepTitle("③ 第三步：粘贴 JSON 规则并解析"))
            addView(stepDesc("复制 AI 返回的 JSON，粘贴到下方输入框"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(pasteInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)).apply { topMargin = dp(8) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                clipChildren = false
                clipToPadding = false
                addView(savePasteBtn, LinearLayout.LayoutParams(dp(132), dp(42)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val importBtn = dialogButton("手机扫码") { dialog?.dismiss(); showUploadQrDialog() }
        scrollContent.addView(stepBlock().apply {
            addView(stepTitle("④ 或者，手机扫码操作"))
            addView(stepDesc("在手机页面粘贴 JSON 或下载规范文档"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(context).apply {
                gravity = Gravity.START
                clipChildren = false
                clipToPadding = false
                addView(importBtn, LinearLayout.LayoutParams(dp(132), dp(44)))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        scrollContent.addView(TextView(context).apply {
            text = "已导入规则"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
            setPadding(0, dp(18), 0, dp(8))
        })
        listBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        scrollContent.addView(listBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val close = dialogButton("关闭") { dialog?.dismiss() }
        content.addView(LinearLayout(context).apply { gravity = Gravity.END; addView(close, LinearLayout.LayoutParams(dp(112), dp(42))) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        refreshRules()

        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnShowListener { copyBtn.requestFocus() }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                val screenHeight = context.resources.displayMetrics.heightPixels
                setLayout(dp(720), (screenHeight * 0.85f).toInt())
            }
        }
    }

    private fun refreshRules() {
        listBox.removeAllViews()
        val rules = try { RuleBasedAdapter.listRuleInfos(context) } catch (t: Throwable) { emptyList() }
        if (rules.isEmpty()) {
            listBox.addView(TextView(context).apply {
                text = "暂无已导入规则"
                textSize = 15f
                setTextColor(Color.argb(200, 210, 214, 222))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(42), dp(12), dp(42))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        rules.forEach { rule ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rowBg()
                clipChildren = false
                clipToPadding = false
            }
            val kindLabel = if (rule.pageKind == ParsePageKind.LIST) "列表页" else "详情页"
            row.addView(TextView(context).apply {
                text = "${rule.name}  ·  $kindLabel  ·  ${rule.version}"
                textSize = 15f
                setTextColor(Color.argb(238, 245, 245, 245))
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(dialogButton("使用") {
                val url = currentUrlProvider().trim()
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    toast("请先输入 http/https 开头的网址")
                } else {
                    dialog?.dismiss()
                    onUseRule(rule.fileName)
                }
            }, LinearLayout.LayoutParams(dp(84), dp(38)).apply { marginStart = dp(8) })
            row.addView(dialogButton("删除") {
                RuleBasedAdapter.deleteRule(context, rule.fileName)
                refreshRules()
            }, LinearLayout.LayoutParams(dp(84), dp(38)).apply { marginStart = dp(8) })
            listBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(8) })
        }
    }

    private fun showKeyboard(view: View) {
        view.postDelayed({
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 120L)
    }

    private fun saveCustomAdapterBinding(url: String, adapterName: String, ruleFileName: String, pageKind: ParsePageKind) {
        adapterStore.forceUpdateBinding(
            WebParseAdapterStore.DomainBinding(
                pageKind = pageKind,
                host = url,
                adapterId = ruleFileName,
                adapterKind = AdapterKind.CUSTOM_JSON,
                adapterName = adapterName,
                ruleFileName = ruleFileName,
                frameworkType = WebFrameworkType.CUSTOM,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun savePastedJson() {
        val text = pasteInput.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            toast("请先粘贴 JSON 内容")
            return
        }
        try {
            val rawHandler = onUseRawJson
            if (rawHandler != null) {
                val url = currentUrlProvider().trim()
                val kind = pageKindProvider()
                val host = adapterStore.normalizeHost(url)
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    toast("请先输入 http/https 开头的网址")
                    return
                }
                if (host.isBlank()) {
                    toast("无法识别当前网址域名")
                    return
                }
                val info = RuleBasedAdapter.saveRule(context, text, host, kind)
                saveCustomAdapterBinding(url, info.name, info.fileName, kind)
                toast("规则已保存，正在按 JSON 规则解析…")
                refreshRules()
                rawHandler(text)
            } else {
                val info = RuleBasedAdapter.saveRule(context, text)
                val url = currentUrlProvider().trim()
                val kind = pageKindProvider()
                val host = adapterStore.normalizeHost(url)
                if (host.isNotBlank()) saveCustomAdapterBinding(url, info.name, info.fileName, kind)
                toast("规则已保存，正在解析…")
                refreshRules()
                dialog?.dismiss()
                onUseRule(info.fileName)
            }
        } catch (t: Throwable) {
            toast(t.message ?: "JSON 格式错误，请检查内容")
        }
    }

    private fun saveAiSpecToDownloads() {
        val fileName = "casttv-adapter-spec.md"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val displayPath = File(downloadsDir, fileName).absolutePath
        try {
            val text = JsonAdapterSpecBuilder.build(context, currentUrlProvider().trim(), pageKindProvider())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(fileName, text)
            } else {
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                File(downloadsDir, fileName).writeText(text, Charsets.UTF_8)
            }
            toast("AI 规范已保存：$displayPath")
        } catch (t: Throwable) {
            toast("保存 AI 规范失败")
        }
    }

    private fun saveWithMediaStore(fileName: String, text: String) {
        val resolver = context.applicationContext.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(fileName, Environment.DIRECTORY_DOWNLOADS + "/")
        runCatching { resolver.delete(collection, selection, selectionArgs) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("create download file failed")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("open download file failed")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun openDownloadsDir() {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(downloadsDir), "resource/folder")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            toast("无法打开 Downloads 目录")
        }
    }

    private fun showUploadQrDialog() {
        val url = uploadPageUrlProvider().trim()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = dialogPanelBg()
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        box.addView(titleView("扫码导入 JSON 规则"))
        val qr = ImageView(context).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            val bmp = QrCodeGenerator.encode(url.ifBlank { "http://TV_IP:端口/upload-json-adapter" }, dp(260))
            if (bmp != null) setImageBitmap(bmp) else setImageResource(R.drawable.ic_phone_qrcode)
        }
        box.addView(qr, LinearLayout.LayoutParams(dp(276), dp(276)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(18) })
        box.addView(TextView(context).apply {
            text = if (url.isBlank()) "手机交互服务启动中，请稍后重试" else "$url\n手机扫码后选择 JSON 文件上传，电视端会自动使用新规则解析当前网址。"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(238, 245, 245, 245))
            setPadding(dp(12), dp(12), dp(12), 0)
        })
        val close = dialogButton("关闭") { dialog?.dismiss() }
        box.addView(LinearLayout(context).apply { gravity = Gravity.CENTER; addView(close, LinearLayout.LayoutParams(dp(112), dp(42))) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create().also { d ->
            d.setOnShowListener { close.requestFocus() }
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(560), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun stepBlock(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = stepBg()
        clipChildren = false
        clipToPadding = false
    }

    private fun stepTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(warm)
    }

    private fun stepDesc(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14.5f
        setTextColor(Color.argb(238, 245, 245, 245))
        setLineSpacing(0f, 1.15f)
        setPadding(0, dp(6), 0, 0)
    }

    private fun titleView(title: String = "JSON 解析说明"): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        addView(TextView(context).apply {
            text = title
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 14.5f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            setTextColor(Color.argb(238, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(18, 255, 255, 255))
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has -> refresh(has); FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        setOnClickListener { click() }
        setOnKeyListener { v, keyCode, e ->
            if (e.action == KeyEvent.ACTION_DOWN && keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
                val next = v.focusSearch(when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                    else -> View.FOCUS_RIGHT
                })
                if (next == null || next === v) { BoundaryFocusHandler.shake(v); true } else false
            } else false
        }
    }

    private fun dialogPanelBg() = GradientDrawable().apply {
        cornerRadius = dp(26).toFloat()
        setColor(Color.parseColor("#4169E1"))
        setStroke(dp(1), Color.argb(90, 255, 255, 255))
    }

    private fun stepBg() = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(Color.argb(18, 255, 255, 255))
        setStroke(dp(1), Color.argb(60, 255, 255, 255))
    }

    private fun inputBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(Color.argb(18, 255, 255, 255))
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(60, 255, 255, 255))
    }

    private fun rowBg() = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Color.argb(18, 255, 255, 255))
        setStroke(dp(1), Color.argb(60, 255, 255, 255))
    }

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
