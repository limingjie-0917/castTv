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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.webparse.ParsedMovie
import com.bd.casttv.webparse.WebParseHtml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebParseSaveDialog(
    private val context: Context,
    private val movie: ParsedMovie,
    private val resolve: suspend (String) -> String?
) {
    private val warm = Color.parseColor("#FFD700")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = FavoritesStore(context)
    private var dialog: AlertDialog? = null

    fun show() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bottomSheetPanelBg()
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
            clipChildren = true
            clipToPadding = true
        }
        content.addView(titleView(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val input = EditText(context).apply {
            setText(movie.title.ifBlank { "网页解析合集" })
            setSelection(text.length)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(180, 255, 255, 255))
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = inputBg(false)
            setOnFocusChangeListener { _, has -> background = inputBg(has) }
        }
        content.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(16) })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = true; clipToPadding = true }
        val focusRows = mutableListOf<View>()
        val episodeDescViews = mutableMapOf<String, TextView>()
        movie.sources.forEach { source ->
            source.episodes.forEach { episode ->
                val name = if (movie.sources.size > 1) "${episode.name} · ${source.name}" else episode.name
                val descView = TextView(context).apply {
                    text = WebParseHtml.shortUrl(episode.resolvedUrl ?: episode.playPageUrl)
                    textSize = 12f
                    setTextColor(Color.argb(190, 255, 255, 255))
                    maxLines = 1
                }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    isFocusable = true
                    isClickable = true
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    background = rowBg(false)
                    setOnFocusChangeListener { _, has -> background = rowBg(has) }
                    addView(TextView(context).apply { text = name; textSize = 15f; setTextColor(Color.WHITE); maxLines = 1 })
                    addView(descView)
                }
                episodeDescViews[episode.playPageUrl] = descView
                focusRows.add(row)
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(8) })
            }
        }
        val scroll = ScrollView(context).apply {
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            clipChildren = true
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(list)
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)).apply { topMargin = dp(2) })

        val progress = TextView(context).apply {
            text = "正在批量解析播放地址…"
            textSize = 13f
            setTextColor(Color.argb(220, 255, 255, 255))
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }

        val cancel = dialogButton("取消") { dialog?.dismiss() }
        val confirm = dialogButton("确认保存") { save(input.text?.toString()?.trim().orEmpty()) }.apply {
            isEnabled = false
            alpha = 0.55f
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(cancel, LinearLayout.LayoutParams(dp(104), dp(42)))
            addView(confirm, LinearLayout.LayoutParams(dp(128), dp(42)).apply { marginStart = dp(14) })
        }
        // 进度提示与底部按钮同一行：提示在左，按钮在右，底部对齐。
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(progress, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            addView(buttons)
        }
        content.addView(bottomRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        contentInset.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(contentInset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create().also { d ->
            d.setOnDismissListener { scope.cancel() }
            d.setOnShowListener {
                input.requestFocus()
                resolveAllEpisodes(progress, confirm, episodeDescViews)
            }
            bindBoundary(panel, listOf(input, cancel, confirm) + focusRows)
            d.show()
            d.window?.apply {
                setGravity(Gravity.CENTER)
                setBackgroundDrawableResource(android.R.color.transparent)
                setLayout(dp(720), WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
    }

    private fun resolveAllEpisodes(
        progress: TextView,
        confirm: TextView,
        episodeDescViews: Map<String, TextView>
    ) {
        val episodes = movie.sources.flatMap { it.episodes }
        val total = episodes.size
        if (total == 0) {
            progress.text = "没有可保存的剧集"
            return
        }
        scope.launch {
            var success = 0
            var failed = 0
            episodes.forEachIndexed { index, episode ->
                val current = index + 1
                progress.text = "正在批量解析播放地址 $current/$total"
                episodeDescViews[episode.playPageUrl]?.text = "解析中…"
                val uri = withContext(Dispatchers.IO) {
                    val cached = episode.resolvedUrl?.takeIf { WebParseHtml.looksPlayable(it) }
                    cached ?: runCatching { resolve(episode.playPageUrl) }
                        .getOrNull()
                        ?.takeIf { WebParseHtml.looksPlayable(it) }
                }
                if (uri.isNullOrBlank()) {
                    failed++
                    episode.resolvedUrl = null
                    episodeDescViews[episode.playPageUrl]?.apply {
                        text = "解析失败：${WebParseHtml.shortUrl(episode.playPageUrl)}"
                        setTextColor(Color.argb(230, 255, 120, 120))
                    }
                } else {
                    success++
                    episode.resolvedUrl = uri
                    episodeDescViews[episode.playPageUrl]?.apply {
                        text = WebParseHtml.shortUrl(uri)
                        setTextColor(Color.argb(190, 255, 255, 255))
                    }
                }
            }
            if (failed == 0) {
                progress.text = "播放地址已全部解析完成，共 $success 集"
                confirm.isEnabled = true
                confirm.alpha = 1f
            } else {
                progress.text = "解析完成：成功 $success 集，失败 $failed 集；请换线路或稍后重试"
                confirm.isEnabled = false
                confirm.alpha = 0.55f
            }
        }
    }

    private fun save(name: String) {
        if (name.isBlank()) { toast("合集名称不能为空"); return }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val pair = store.createCollection(name.ifBlank { movie.title.ifBlank { "网页解析合集" } })
                if (pair.first != FavoritesStore.OpResult.SUCCESS || pair.second.isNullOrBlank()) return@withContext pair.first
                val cid = pair.second!!
                var finalResult = FavoritesStore.OpResult.SUCCESS
                movie.sources.forEach { source ->
                    source.episodes.forEach { episode ->
                        val uri = episode.resolvedUrl?.takeIf { WebParseHtml.looksPlayable(it) } ?: return@withContext FavoritesStore.OpResult.INVALID
                        val title = if (movie.sources.size > 1) "${episode.name} · ${source.name}" else episode.name
                        val res = store.addToCollection(
                            collectionId = cid,
                            title = title.ifBlank { movie.title },
                            uri = uri,
                            source = WebParseHtml.shortUrl(uri),
                            description = movie.description
                        )
                        if (res != FavoritesStore.OpResult.SUCCESS) finalResult = res
                    }
                }
                finalResult
            }
            if (result == FavoritesStore.OpResult.SUCCESS) {
                toast("已保存到合集")
                dialog?.dismiss()
            } else {
                toast("保存失败：$result")
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
            text = "保存到合集"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(warm)
            gravity = Gravity.CENTER_VERTICAL
            setShadowLayer(2f, 0f, 1f, Color.argb(130, 0, 0, 0))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun bottomSheetPanelBg() = GradientDrawable().apply {
        cornerRadius = dp(26).toFloat()
        setColor(0xFF4169E1.toInt())
    }

    private fun inputBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.argb(18, 255, 255, 255))
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
    }

    private fun rowBg(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(if (focused) Color.TRANSPARENT else Color.argb(18, 255, 255, 255))
        setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
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
                setColor(if (focused) Color.TRANSPARENT else Color.argb(18, 255, 255, 255))
                setStroke(dp(if (focused) 3 else 1), if (focused) warm else Color.argb(170, 210, 214, 222))
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
