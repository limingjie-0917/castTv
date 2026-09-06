package com.bd.casttv.ui.framework.pages

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.cartoon.CartoonStore
import com.bd.casttv.sync.GiteeApi
import com.bd.casttv.sync.GiteeShareStore
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.webparse.AdapterKind
import com.bd.casttv.webparse.ParsePageKind
import com.bd.casttv.webparse.RuleBasedAdapter
import com.bd.casttv.webparse.WebFrameworkType
import com.bd.casttv.webparse.WebParseAdapterStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「添加到动画城」对话框。
 *
 * 流程：标题可编辑 → 预览封面/集数/适配器信息 → 确认 →
 *   1) 自定义适配器时先 upsertSharedAdapter 上传规则到云端
 *   2) upsertCartoon 写入 cartoons 表（以 detailUrl 生成 cartoonId 为主键，全量覆盖）
 *
 * 视觉风格参考 [WebParseSaveDialog]，TV 焦点三态 + 暗玻璃面板。
 */
class AddToCartoonDialog(
    private val context: Context,
    private val title: String,
    private val coverUrl: String,
    private val detailUrl: String,
    private val episodeCount: Int,
    private val adapterIsCustom: Boolean,
    private val adapterId: String,
    private val adapterName: String,
    private val adapterRuleFileName: String?,
    private val adapterHost: String,
    private val adapterFramework: WebFrameworkType,
    private val fetchMode: String = "HTTP"
) {
    private val warm: Int get() = ThemeManager.accentColor(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var dialog: AlertDialog? = null
    private var submitJob: kotlinx.coroutines.Job? = null
    private var confirmButton: TextView? = null

    fun show() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.dialogPanelBg(context, 26)
            setPadding(dp(20), dp(16), dp(20), dp(18))
            clipChildren = false
            clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
        }

        // Header
        content.addView(titleView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Cover + summary row
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(12), 0, 0)
        }
        val cover = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = innerPanelBg()
            setImageResource(R.drawable.ic_thumb_default)
        }
        top.addView(cover, LinearLayout.LayoutParams(dp(112), dp(158)).apply { marginEnd = dp(14) })
        loadCover(cover)

        val summary = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleInput = EditText(context).apply {
            setText(title.ifBlank { "未命名动画" })
            setSelection(text.length)
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(180, 255, 255, 255))
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = inputBg(false)
            setOnFocusChangeListener { _, has -> background = inputBg(has) }
        }
        summary.addView(titleInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        summary.addView(
            metaLine("集数", "$episodeCount 集"),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        )
        val adapterLabel = when {
            adapterId.isBlank() && adapterName.isBlank() -> "未记录适配器（将走内置通用解析）"
            adapterIsCustom -> "自定义：${adapterName.ifBlank { adapterId }}"
            else -> "内置：${adapterName.ifBlank { adapterId }}"
        }
        summary.addView(
            metaLine("适配器", adapterLabel),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        )
        summary.addView(
            metaLine("网址", detailUrl, maxLines = 2),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        )
        top.addView(summary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Progress
        val progress = TextView(context).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Buttons row
        val cancel = dialogButton("取消") {
            if (submitJob?.isActive == true) return@dialogButton
            dialog?.dismiss()
        }
        val confirm = dialogButton("添加") {
            if (submitJob?.isActive == true) return@dialogButton
            submit(titleInput.text?.toString()?.trim().orEmpty(), progress)
        }.also { confirmButton = it }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancel, LinearLayout.LayoutParams(dp(104), dp(42)))
            addView(confirm, LinearLayout.LayoutParams(dp(112), dp(42)).apply { marginStart = dp(14) })
        }
        content.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        panel.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnDismissListener { scope.cancel() }
            d.setOnShowListener { titleInput.requestFocus() }
            bindBoundary(panel, listOf(titleInput, cancel, confirm))
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(700), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun submit(name: String, progress: TextView) {
        if (name.isBlank()) { toast("标题不能为空"); return }
        if (detailUrl.isBlank()) { toast("详情页网址无效"); return }
        val btn = confirmButton ?: return
        submitJob = scope.launch {
            btn.isEnabled = false
            btn.alpha = 0.55f
            val result = withContext(Dispatchers.IO) { doSubmit(name, progress) }
            btn.isEnabled = true
            btn.alpha = 1f
            when (result) {
                SubmitResult.Ok -> {
                    progress.text = "已添加到动画城 ✅"
                    progress.setTextColor(Color.argb(230, 140, 255, 170))
                    btn.postDelayed({ dialog?.dismiss() }, 700)
                }
                is SubmitResult.Err -> {
                    progress.text = result.msg
                    progress.setTextColor(Color.argb(230, 255, 120, 120))
                }
            }
        }
    }

    private sealed class SubmitResult {
        object Ok : SubmitResult()
        data class Err(val msg: String) : SubmitResult()
    }

    private suspend fun doSubmit(name: String, progress: TextView): SubmitResult {
        setProgress(progress, "准备上传到云端…")

        // Step1: 自定义 JSON 适配器 -> 先 upsertSharedAdapter 上传规则
        var globalAdapterId: String? = null
        if (adapterIsCustom && adapterRuleFileName != null && adapterHost.isNotBlank()) {
            setProgress(progress, "上传自定义适配器规则…")
            val binding = WebParseAdapterStore.DomainBinding(
                pageKind = ParsePageKind.DETAIL,
                host = adapterHost,
                adapterId = adapterId,
                adapterKind = AdapterKind.CUSTOM_JSON,
                adapterName = adapterName.ifBlank { adapterId },
                ruleFileName = adapterRuleFileName,
                frameworkType = adapterFramework,
                updatedAt = System.currentTimeMillis()
            )
            val ruleText = runCatching { RuleBasedAdapter.readRuleText(context, adapterRuleFileName!!) }.getOrDefault("")
            when (val r = GiteeShareStore.upsertSharedAdapter(context, binding, ruleText)) {
                is GiteeApi.ApiResult.Success -> globalAdapterId = r.value.takeIf { it.isNotBlank() }
                is GiteeApi.ApiResult.Error -> return SubmitResult.Err("适配器上传失败：${r.message}")
                is GiteeApi.ApiResult.NotFound -> return SubmitResult.Err("适配器上传失败：NotFound")
            }
        }

        // Step2: upsertCartoon 写入云端 cartoons 表（以 detailUrl 生成的 cartoonId 为主键，全量覆盖）
        setProgress(progress, "写入动画城卡片…")
        val finalAdapterName = adapterName.ifBlank {
            adapterId.ifBlank { if (globalAdapterId != null) "自定义JSON" else "内置适配器" }
        }
        val r = GiteeShareStore.upsertCartoon(
            context,
            title = name,
            detailUrl = detailUrl,
            cover = coverUrl,
            globalAdapterId = globalAdapterId,
            adapterName = finalAdapterName,
            episodeCount = episodeCount,
            fetchMode = fetchMode
        )
        return when (r) {
            is GiteeApi.ApiResult.Success -> {
                // 立即补本地缓存：下次进入动画城秒开即见，不受 Gitee 写后读延迟影响。
                runCatching { CartoonStore(context.applicationContext).upsertLocal(r.value) }
                SubmitResult.Ok
            }
            is GiteeApi.ApiResult.Error -> SubmitResult.Err("写入失败：${r.message}")
            is GiteeApi.ApiResult.NotFound -> SubmitResult.Err("写入失败：NotFound")
        }
    }

    private suspend fun setProgress(v: TextView, text: String) {
        withContext(Dispatchers.Main) {
            v.text = text
        }
    }

    private fun loadCover(target: ImageView) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val url = URL(coverUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 5000
                        readTimeout = 5000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bmp != null) target.setImageBitmap(bmp)
        }
    }

    private fun titleView(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        val sticker = ClippedImageView(context).apply {
            setCircle(true)
            // 固定使用动画城图标贴纸（《小糊涂神》太阳公公形象）
            setImageResource(R.drawable.ic_more_cartoon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }
        addView(sticker, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })
        addView(object : TextView(context) {
            init {
                text = "添加到动画城"
                textSize = 20f
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

    private fun metaLine(label: String, value: String, maxLines: Int = 1): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(context).apply {
            text = "$label："
            textSize = 13f
            setTextColor(ThemeManager.accentColor(context))
            setMaxLines(1)
        })
        row.addView(TextView(context).apply {
            text = value.ifBlank { "-" }
            textSize = 13f
            setTextColor(Color.argb(210, 255, 255, 255))
            setMaxLines(maxLines)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun innerPanelBg() = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(24, 255, 255, 255))
    }

    private fun inputBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(24, 255, 255, 255))
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
            setTextColor(Color.rgb(238, 238, 238))
            background = GradientDrawable().apply {
                cornerRadius = dp(60).toFloat()
                setColor(if (focused) Color.TRANSPARENT else Color.argb(20, 255, 255, 255))
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
            BoundaryFocusHandler.shake(view); true
        }
        focusables.forEach { it.setOnKeyListener(listener) }
    }

    private fun isChildOf(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) { if (current === root) return true; current = current.parent as? View }
        return false
    }

    private fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
