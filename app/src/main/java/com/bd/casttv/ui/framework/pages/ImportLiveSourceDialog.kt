package com.bd.casttv.ui.framework.pages

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.favorites.LiveSourceImporter
import com.bd.casttv.favorites.LiveSourceSubmitServer
import com.bd.casttv.favorites.SharedLiveSourceStore
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.ui.GlowUnderlineView
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.util.NetworkUtils
import com.bd.casttv.util.QrCodeGenerator
import com.bd.casttv.util.ThemeManager

/**
 * 导入直播源弹窗（真实逻辑）：
 *  - Tab 1「输入链接」：局域网手机端扫码提交 URL（LiveSourceSubmitServer）+ TV 端直接输入 URL；
 *  - Tab 2「云端共享」：从 Gitee 云端 `shared_sources/index.json` 拉取社区共享直播源列表。
 */
class ImportLiveSourceDialog(
    private val context: Context,
    private val store: FavoritesStore,
    private val defaultCollectionId: String,
    private val onImported: () -> Unit
) {
    private val ui = Handler(Looper.getMainLooper())
    private var submitServer: LiveSourceSubmitServer? = null
    private var pollTask: Runnable? = null
    private var submitEntryUrl: String? = null
    private var mainDialog: AlertDialog? = null
    private var returnFocusView: View? = null

    private val WARM = Color.rgb(245, 196, 81)
    private val GREY = 0xFFB8B8B8.toInt()
    private val LIGHT_TEXT = 0xFFEEE8DA.toInt()

    fun show() {
        returnFocusView = (context as? android.app.Activity)?.currentFocus
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }

        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "导入直播源"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        panel.addView(TextView(context).apply {
            text = "可手动输入 M3U / 直播源链接，也可用手机扫码打开局域网页面提交到电视。"
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(root)

        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val tabInput = makeTab("输入链接")
        val tabCloud = makeTab("☁️ 云端共享")
        tabRow.addView(tabInput.root, LinearLayout.LayoutParams(0, dp(42), 1f))
        tabRow.addView(tabCloud.root, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(tabRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))

        val info = TextView(context).apply { textSize = 14f; setPadding(0, dp(14), 0, dp(8)); setTextColor(LIGHT_TEXT) }
        val edit = EditText(context).apply {
            hint = "粘贴 M3U / 直播源链接"
            textSize = 14f
            setSingleLine(true)
            setTextColor(LIGHT_TEXT)
            setHintTextColor(GREY)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setStroke(dp(1), WARM); setColor(Color.argb(32, 255, 255, 255)) }
            setPadding(dp(12), 0, dp(12), 0)
        }
        val scanBtn = makeButton("扫码输入")
        val inputRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        inputRow.addView(edit, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(10) })
        inputRow.addView(scanBtn, LinearLayout.LayoutParams(dp(126), dp(46)))
        val panelInput = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(info); addView(inputRow) }

        val cloudHeader = TextView(context).apply { textSize = 14f; setPadding(0, dp(14), 0, dp(6)); setTextColor(LIGHT_TEXT); text = "☁️ 云端共享" }
        val cloudEmpty = TextView(context).apply { textSize = 13f; setPadding(0, dp(6), 0, dp(6)); setTextColor(GREY); text = "正在加载云端共享直播源…" }
        val cloudListContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val cloudScroll = ScrollView(context).apply { addView(cloudListContainer) }
        val panelCloud = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(cloudHeader); addView(cloudEmpty); addView(cloudScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
            visibility = View.GONE
        }
        root.addView(panelInput); root.addView(panelCloud)

        val actionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT; setPadding(0, dp(16), 0, 0) }
        val closeBtn = makeButton("关闭")
        val importBtn = makeButton("解析并导入")
        actionRow.addView(closeBtn, LinearLayout.LayoutParams(dp(112), dp(44)).apply { rightMargin = dp(10) })
        actionRow.addView(importBtn, LinearLayout.LayoutParams(dp(150), dp(44)))

        var cloudLoaded = false
        fun activateTab(input: Boolean) {
            updateTabVisual(tabInput, selected = input, focused = tabInput.root.isFocused)
            updateTabVisual(tabCloud, selected = !input, focused = tabCloud.root.isFocused)
            panelInput.visibility = if (input) View.VISIBLE else View.GONE
            panelCloud.visibility = if (input) View.GONE else View.VISIBLE
            importBtn.visibility = if (input) View.VISIBLE else View.GONE
            closeBtn.layoutParams = (closeBtn.layoutParams as LinearLayout.LayoutParams).apply {
                rightMargin = if (input) dp(10) else 0
            }
            if (!input && !cloudLoaded) { cloudLoaded = true; loadCloudSources(cloudEmpty, cloudListContainer) }
        }
        tabInput.root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) activateTab(true) else updateTabVisual(tabInput, selected = panelInput.visibility == View.VISIBLE, focused = false)
        }
        tabCloud.root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) activateTab(false) else updateTabVisual(tabCloud, selected = panelCloud.visibility == View.VISIBLE, focused = false)
        }
        tabInput.root.setOnClickListener { activateTab(true) }
        tabCloud.root.setOnClickListener { activateTab(false) }
        activateTab(true)

        startSubmitServer(info, edit)
        scanBtn.setOnClickListener { showQrSubmitDialog() }
        panel.addView(actionRow)

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        mainDialog = dialog
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
        dialog.setOnDismissListener {
            pollTask?.let { ui.removeCallbacks(it) }
            submitServer?.stop()
            submitServer = null
            if (mainDialog === dialog) mainDialog = null
            returnFocusView?.post { returnFocusView?.requestFocus() }
        }
        closeBtn.setOnClickListener { dialog.dismiss() }
        importBtn.setOnClickListener { val url = edit.text.toString().trim(); if (url.isNotBlank()) parseAndImport(url, fromCloudShare = false) else Toast.makeText(context, "请先输入直播源链接", Toast.LENGTH_SHORT).show() }
        dialog.show()
        dialog.window?.setLayout(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT)

        // 焦点导航：
        // - 默认焦点：输入链接 Tab
        // - 输入框/扫码输入 按【上】回到输入链接 Tab
        // - 底部「关闭 / 解析并导入」按【下】触发抖动拦截，不移动焦点
        tabInput.root.setOnKeyListener { _, _, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                panelInput.visibility == View.VISIBLE) {
                edit.requestFocus(); true
            } else false
        }
        val upToInputTab = View.OnKeyListener { _, _, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                tabInput.root.requestFocus(); true
            } else false
        }
        edit.setOnKeyListener(upToInputTab)
        scanBtn.setOnKeyListener(upToInputTab)
        val bottomBoundaryShake = View.OnKeyListener { v, _, ev ->
            if (ev.action == KeyEvent.ACTION_DOWN && ev.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                com.bd.casttv.ui.framework.BoundaryFocusHandler.shake(v); true
            } else false
        }
        closeBtn.setOnKeyListener(bottomBoundaryShake)
        importBtn.setOnKeyListener(bottomBoundaryShake)
        tabInput.root.requestFocus()
    }

    private data class TabViewHolder(
        val root: FrameLayout,
        val label: TextView,
        val glow: GlowUnderlineView
    )

    private fun makeTab(textValue: String): TabViewHolder {
        val root = FrameLayout(context).apply {
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = null
            clipChildren = false
            clipToPadding = false
        }
        val glow = GlowUnderlineView(context).apply {
            isFocusable = false
            isClickable = false
            applyVisualState(selected = false, active = false)
        }
        val label = TextView(context).apply {
            text = textValue
            textSize = 15f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(12), dp(1), dp(12), dp(1))
            setTextColor(GREY)
            isDuplicateParentStateEnabled = true
        }
        root.addView(glow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        return TabViewHolder(root, label, glow)
    }

    private fun updateTabVisual(tab: TabViewHolder, selected: Boolean, focused: Boolean) {
        tab.root.isSelected = selected
        tab.label.setTextColor(if (selected) WARM else GREY)
        tab.label.paint.isFakeBoldText = selected
        tab.label.invalidate()
        tab.glow.applyVisualState(selected = false, active = focused)
    }

    private fun makeButton(textValue: String): TextView = TextView(context).apply {
        text = textValue
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isFocusable = true
        isClickable = true
        fun refresh(focused: Boolean) {
            setTextColor(if (isSelected) WARM else Color.argb(235, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(170, 210, 214, 222))
                setColor(Color.argb(52, 32, 34, 40))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, hasFocus ->
            refresh(hasFocus)
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = 10)
        }
    }

    private fun startSubmitServer(info: TextView, edit: EditText) {
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip != null) {
            submitServer = LiveSourceSubmitServer().also { s ->
                val ok = s.start()
                if (ok) {
                    submitEntryUrl = "http://$ip:${s.port}/import-live-source"
                    info.text = "点击右侧「扫码输入」，用手机扫码在手机上提交后会自动填入下方输入框。"
                    startPolling(s, edit)
                } else info.text = "⚠️ 局域网提交服务启动失败，请手动输入链接"
            }
        } else info.text = "⚠️ 未连接到局域网，可直接输入链接"
    }

    private fun showQrSubmitDialog() {
        val url = submitEntryUrl
        if (url.isNullOrBlank()) { Toast.makeText(context, "局域网提交入口不可用，请手动输入链接", Toast.LENGTH_SHORT).show(); return }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(2), WARM) }
        }
        box.addView(TextView(context).apply { text = "扫码输入直播源"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        box.addView(TextView(context).apply { text = "用手机扫描此二维码，在手机上填写并提交直播源地址。"; textSize = 13.5f; setTextColor(Color.argb(220,255,255,255)); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(12)) })
        val bmp = QrCodeGenerator.encode(url, dp(260))
        if (bmp != null) box.addView(ImageView(context).apply { setImageBitmap(bmp); setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(8), dp(8), dp(8)) }, LinearLayout.LayoutParams(dp(276), dp(276)))
        box.addView(TextView(context).apply {
            text = "手机扫码或浏览器打开：$url"
            textSize = 13f
            setTextColor(WARM)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(2))
        })
        box.addView(TextView(context).apply {
            text = "在手机页面提交后会自动填入下方输入框。"
            textSize = 12f
            setTextColor(Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        val d = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(box).create()
        d.setOnShowListener { d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
        d.show(); d.window?.setLayout(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun loadCloudSources(emptyText: TextView, container: LinearLayout) {
        emptyText.text = "正在加载云端共享直播源…"; emptyText.visibility = View.VISIBLE; container.removeAllViews()
        Thread {
            val result = SharedLiveSourceStore.fetchSources()
            ui.post {
                when (result) {
                    is SharedLiveSourceStore.LoadResult.Success -> {
                        if (result.sources.isEmpty()) emptyText.text = "云端暂无共享直播源\n可先在旧版收藏页把常用直播源分享上来～" else { emptyText.visibility = View.GONE; for (src in result.sources) container.addView(buildCloudRow(src)) }
                    }
                    is SharedLiveSourceStore.LoadResult.Error -> { emptyText.text = "云端加载失败：${result.message}\n请稍后重试或改用「输入链接」"; emptyText.visibility = View.VISIBLE }
                }
            }
        }.start()
    }

    private fun buildCloudRow(source: SharedLiveSourceStore.SharedSource): View {
        val item = android.view.LayoutInflater.from(context)
            .inflate(R.layout.item_shared_live_source, null, false)

        // 名称 / 标签 / 分组+频道 / 上传时间
        val name = item.findViewById<TextView>(R.id.sharedItemName)
        val tags = item.findViewById<TextView>(R.id.sharedItemTags)
        val meta = item.findViewById<TextView>(R.id.sharedItemMeta)
        val time = item.findViewById<TextView>(R.id.sharedItemTime)
        val heatCount = item.findViewById<TextView>(R.id.sharedItemHeatCount)
        val likeButton = item.findViewById<ImageView>(R.id.sharedItemLikeButton)
        val importBtn = item.findViewById<TextView>(R.id.sharedItemImport)

        name.text = source.name.ifBlank { source.url }
        val tagText = source.tags.joinToString(" · ")
        tags.text = tagText
        tags.visibility = if (tagText.isBlank()) View.GONE else View.VISIBLE
        meta.text = "${source.groupCount} 个分组，${source.channelCount} 个频道"
        time.text = "上传于 ${SharedLiveSourceStore.displayTime(source.uploadedAt)}"

        // 为 xml 里的点赞 / 导入按钮补齐与本类代码按钮一致的焦点描边效果。
        applyActionFocusFx(likeButton, cornerRadiusDp = 10)
        applyActionFocusFx(importBtn, cornerRadiusDp = 10)

        // 点赞渲染 + 云端同步（与 MainActivity#bindCloudShareItem 行为保持一致）。
        fun renderLike() {
            val liked = SharedLiveSourceStore.isLiked(context, source.id)
            heatCount.text = source.likeCount.toString()
            likeButton.isSelected = liked
            likeButton.setImageResource(
                if (liked) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up_outline
            )
        }
        renderLike()
        var likeRequestRunning = false
        likeButton.setOnClickListener {
            if (likeRequestRunning) return@setOnClickListener
            val willLike = !SharedLiveSourceStore.isLiked(context, source.id)
            likeRequestRunning = true
            Thread({
                val res = SharedLiveSourceStore.updateLikeCount(source.id, if (willLike) 1 else -1)
                ui.post {
                    likeRequestRunning = false
                    when (res) {
                        is SharedLiveSourceStore.LikeResult.Success -> {
                            SharedLiveSourceStore.setLiked(context, source.id, willLike)
                            source.likeCount = res.newCount
                            renderLike()
                            Toast.makeText(
                                context,
                                if (willLike) "已点赞 \uD83D\uDC4D" else "已取消点赞",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is SharedLiveSourceStore.LikeResult.Error -> {
                            Toast.makeText(context, "操作失败：${res.message}", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            }, "shared-live-like").start()
        }

        importBtn.setOnClickListener { parseAndImport(source.url, fromCloudShare = true) }
        return item
    }

    /** 给 xml 版「点赞 / 导入」等操作按钮补齐与 makeButton 一致的焦点描边 + FocusFx。 */
    private fun applyActionFocusFx(view: View, cornerRadiusDp: Int) {
        fun refresh(focused: Boolean) {
            view.background = GradientDrawable().apply {
                cornerRadius = dp(cornerRadiusDp).toFloat()
                setStroke(
                    dp(if (focused) 2 else 1),
                    if (focused) WARM else Color.argb(170, 210, 214, 222)
                )
                setColor(Color.argb(52, 32, 34, 40))
            }
        }
        refresh(view.isFocused)
        view.setOnFocusChangeListener { v, hasFocus ->
            refresh(hasFocus)
            FocusFxHelper.applyFocusFxState(v, hasFocus, cornerRadiusDp = cornerRadiusDp)
        }
    }

    private fun showSharePromptDialog(parsed: LiveSourceImporter.ParseResult) {
        val panel = dialogPanel()
        panel.addView(dialogHeader("上传云端共享"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        panel.addView(TextView(context).apply {
            text = "是否将直播源地址上传到云端共享？上传成功后，其他用户可在「云端共享」Tab 中看到并导入该直播源。"
            textSize = 14f
            setTextColor(Color.argb(225, 255, 255, 255))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        })
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT; setPadding(0, dp(18), 0, 0) }
        val cancelBtn = makeButton("取消")
        val confirmBtn = makeButton("确认共享")
        actions.addView(cancelBtn, LinearLayout.LayoutParams(dp(112), dp(44)).apply { rightMargin = dp(10) })
        actions.addView(confirmBtn, LinearLayout.LayoutParams(dp(132), dp(44)))
        panel.addView(actions)
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); cancelBtn.requestFocus() }
        dialog.setOnDismissListener { returnFocusView?.post { returnFocusView?.requestFocus() } }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            dialog.dismiss()
            showShareTitleDialog(parsed)
        }
        dialog.show()
        dialog.window?.setLayout(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showShareTitleDialog(parsed: LiveSourceImporter.ParseResult) {
        val panel = dialogPanel()
        panel.addView(dialogHeader("填写直播源标题"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        panel.addView(TextView(context).apply {
            text = "请为这条直播源填写一个便于识别的标题。"
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(dp(4), dp(4), dp(4), dp(8))
        })
        val titleInput = EditText(context).apply {
            hint = "例如：北京移动 IPTV"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            textSize = 14f
            setTextColor(LIGHT_TEXT)
            setHintTextColor(GREY)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setStroke(dp(1), WARM); setColor(Color.argb(32, 255, 255, 255)) }
            setPadding(dp(12), 0, dp(12), 0)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        panel.addView(titleInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT; setPadding(0, dp(18), 0, 0) }
        val cancelBtn = makeButton("取消")
        val uploadBtn = makeButton("上传")
        actions.addView(cancelBtn, LinearLayout.LayoutParams(dp(112), dp(44)).apply { rightMargin = dp(10) })
        actions.addView(uploadBtn, LinearLayout.LayoutParams(dp(112), dp(44)))
        panel.addView(actions)
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); titleInput.requestFocus() }
        dialog.setOnDismissListener { returnFocusView?.post { returnFocusView?.requestFocus() } }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        uploadBtn.setOnClickListener {
            val title = titleInput.text?.toString().orEmpty().trim()
            if (title.isEmpty()) {
                Toast.makeText(context, "请先填写直播源标题", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            uploadBtn.isEnabled = false
            cancelBtn.isEnabled = false
            uploadBtn.text = "上传中…"
            Thread {
                val result = SharedLiveSourceStore.shareSource(title, parsed.sourceUrl, parsed.groups.size, parsed.totalChannels)
                ui.post {
                    if (!dialog.isShowing) return@post
                    when (result) {
                        SharedLiveSourceStore.ShareResult.Success -> {
                            Toast.makeText(context, "已上传到云端共享", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        }
                        SharedLiveSourceStore.ShareResult.AlreadyShared -> {
                            Toast.makeText(context, "该直播源已在云端共享中", Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                        }
                        is SharedLiveSourceStore.ShareResult.Error -> {
                            uploadBtn.isEnabled = true
                            cancelBtn.isEnabled = true
                            uploadBtn.text = "上传"
                            Toast.makeText(context, "上传失败：${result.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.start()
        }
        dialog.show()
        dialog.window?.setLayout(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dialogPanel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(20), dp(24), dp(18))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(2), WARM)
        }
    }

    private fun dialogHeader(title: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        addView(TextView(context).apply {
            text = title
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun startPolling(server: LiveSourceSubmitServer, edit: EditText) {
        val task = object : Runnable {
            override fun run() {
                val url = server.consumeSubmittedUrl()
                if (!url.isNullOrBlank()) { edit.setText(url); Toast.makeText(context, "已收到手机提交的链接，点击「解析并导入」继续", Toast.LENGTH_LONG).show(); return }
                ui.postDelayed(this, 1200)
            }
        }
        pollTask = task; ui.postDelayed(task, 1200)
    }

    private fun parseAndImport(url: String, fromCloudShare: Boolean) {
        showParseProgressDialog(url, fromCloudShare)
    }

    private fun showParseProgressDialog(url: String, fromCloudShare: Boolean) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        panel.addView(TextView(context).apply {
            text = "解析直播源"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        val summary = TextView(context).apply {
            text = "正在准备解析，请稍候…"
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(0, dp(10), 0, dp(10))
        }
        panel.addView(summary)
        val stepBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(stepBox)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT; setPadding(0, dp(14), 0, 0) }
        val cancelBtn = makeButton("取消")
        val retryBtn = makeButton("重新解析").apply { visibility = View.GONE }
        actions.addView(cancelBtn, LinearLayout.LayoutParams(dp(112), dp(44)).apply { rightMargin = dp(10) })
        actions.addView(retryBtn, LinearLayout.LayoutParams(dp(132), dp(44)))
        panel.addView(actions)

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        retryBtn.setOnClickListener {
            dialog.dismiss()
            showParseProgressDialog(url, fromCloudShare)
        }
        dialog.show()
        dialog.window?.setLayout(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT)

        val stepTitles = listOf("获取直播源链接", "下载直播源内容", "解析频道分组", "生成可导入合集")
        val stepViews = stepTitles.mapIndexed { index, title ->
            TextView(context).apply {
                text = "○ ${index + 1}. $title"
                textSize = 14f
                setTextColor(LIGHT_TEXT)
                setPadding(0, dp(5), 0, dp(5))
                stepBox.addView(this)
            }
        }
        fun updateStep(index: Int, running: Boolean = false, detail: String? = null, failed: Boolean = false) {
            ui.post {
                if (!dialog.isShowing) return@post
                val prefix = when {
                    failed -> "✕"
                    running -> "…"
                    else -> "✓"
                }
                stepViews.getOrNull(index)?.apply {
                    text = "$prefix ${index + 1}. ${stepTitles[index]}${detail?.let { "\n   $it" } ?: ""}"
                    setTextColor(if (failed) Color.rgb(255, 107, 107) else if (running) WARM else LIGHT_TEXT)
                }
            }
        }

        Thread {
            var result: LiveSourceImporter.ParseResult? = null
            var failure: String? = null
            var failedStep = 0
            try {
                updateStep(0, running = true, detail = "已拿到直播源链接")
                result = LiveSourceImporter.parseUrl(url, progress = LiveSourceImporter.ProgressListener { step, running, detail ->
                    val safeStep = (step - 1).coerceIn(0, stepTitles.lastIndex)
                    failedStep = safeStep
                    updateStep(safeStep, running = running, detail = detail)
                })
            } catch (f: LiveSourceImporter.Failure) {
                failure = f.message
            } catch (t: Throwable) {
                failure = t.message ?: t::class.simpleName ?: "解析失败"
            }
            ui.post {
                if (!dialog.isShowing) return@post
                val parsed = result
                if (parsed == null) {
                    summary.text = "解析失败，请查看异常节点原因后重试。"
                    updateStep(failedStep, failed = true, detail = failure ?: "解析失败")
                    retryBtn.visibility = View.VISIBLE
                    retryBtn.requestFocus()
                    return@post
                }
                if (parsed.groups.isEmpty()) {
                    summary.text = "解析失败，请查看异常节点原因后重试。"
                    updateStep(2, failed = true, detail = "未解析到任何频道")
                    retryBtn.visibility = View.VISIBLE
                    retryBtn.requestFocus()
                    return@post
                }
                updateStep(3, detail = "解析出 ${parsed.groups.size} 个分组、${parsed.totalChannels} 个频道")
                dialog.dismiss()
                showPreviewDialog(parsed, fromCloudShare)
            }
        }.start()
    }

    private fun showPreviewDialog(parsed: LiveSourceImporter.ParseResult, fromCloudShare: Boolean) {
        Thread {
            val existingInfo = try { store.collectionsInfo() } catch (_: Throwable) { emptyList() }
            ui.post { showPreviewDialog(parsed, existingInfo, fromCloudShare) }
        }.start()
    }

    private fun showPreviewDialog(parsed: LiveSourceImporter.ParseResult, existingInfo: List<FavoritesStore.CollectionInfo>, fromCloudShare: Boolean) {
        val groups = parsed.groups
        val existingNames = existingInfo.map { it.name }.toSet()
        val localCount = existingInfo.size
        val maxCollections = FavoritesStore.MAX_COLLECTIONS
        val checked = BooleanArray(groups.size) { true }
        val skipped = BooleanArray(groups.size) { false }
        val rows = mutableListOf<android.widget.CheckBox>()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        panel.addView(TextView(context).apply { text = "选择导入分组"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
        val summary = TextView(context).apply {
            text = "共解析出 ${groups.size} 个分组、${parsed.totalChannels} 个频道。请选择要生成合集导入的分组："
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(0, dp(10), 0, dp(8))
        }
        panel.addView(summary)
        val quota = TextView(context).apply { textSize = 13f; setTextColor(WARM); setPadding(0, 0, 0, dp(8)) }
        panel.addView(quota)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)))
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.RIGHT; setPadding(0, dp(14), 0, 0) }
        val cancelBtn = makeButton("取消")
        val toggleBtn = makeButton("取消全选")
        val confirmBtn = makeButton("导入选中")
        actions.addView(cancelBtn, LinearLayout.LayoutParams(dp(104), dp(44)).apply { rightMargin = dp(10) })
        actions.addView(toggleBtn, LinearLayout.LayoutParams(dp(120), dp(44)).apply { rightMargin = dp(10) })
        actions.addView(confirmBtn, LinearLayout.LayoutParams(dp(132), dp(44)))
        panel.addView(actions)

        fun label(i: Int): String {
            val group = groups[i]
            return buildString {
                append(group.displayName)
                append("  (${group.channels.size} 频道)")
                if (existingNames.contains(group.displayName)) append("  · 将覆盖更新")
                if (skipped[i]) append("  · 将自动跳过")
            }
        }
        fun refresh() {
            var importable = 0
            val selectedCount = checked.count { it }
            for (i in groups.indices) {
                skipped[i] = false
                if (checked[i]) {
                    importable += 1
                    if (localCount + importable > maxCollections && !existingNames.contains(groups[i].displayName)) skipped[i] = true
                }
            }
            val full = localCount >= maxCollections
            quota.text = if (full) "合集已满（$maxCollections/$maxCollections），只能覆盖已有同名合集" else "当前合集：$localCount/$maxCollections，本次勾选：$selectedCount"
            rows.forEachIndexed { i, cb ->
                val disabled = skipped[i]
                cb.text = label(i)
                cb.alpha = if (disabled) 0.45f else 1f
                cb.setTextColor(if (checked[i] && !disabled) WARM else LIGHT_TEXT)
            }
            confirmBtn.isEnabled = checked.indices.any { checked[it] && !skipped[it] }
            confirmBtn.alpha = if (confirmBtn.isEnabled) 1f else 0.45f
            toggleBtn.text = if (selectedCount == groups.size) "取消全选" else "全选"
        }
        groups.forEachIndexed { i, _ ->
            val cb = android.widget.CheckBox(context).apply {
                isChecked = true
                text = label(i)
                textSize = 14f
                setTextColor(WARM)
                isFocusable = true
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnCheckedChangeListener { _, value -> checked[i] = value; refresh() }
            }
            rows.add(cb)
            list.addView(cb)
        }
        refresh()

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); confirmBtn.requestFocus() }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        toggleBtn.setOnClickListener {
            val target = checked.any { !it }
            checked.indices.forEach { checked[it] = target; rows[it].isChecked = target }
            refresh()
        }
        confirmBtn.setOnClickListener {
            val selected = groups.filterIndexed { i, _ -> checked[i] && !skipped[i] }
            if (selected.isEmpty()) { Toast.makeText(context, "请至少选择一个分组", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            dialog.dismiss()
            executeImportGroups(selected, parsed, fromCloudShare)
        }
        dialog.show()
        dialog.window?.setLayout(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun executeImportGroups(selected: List<LiveSourceImporter.LiveGroup>, parsed: LiveSourceImporter.ParseResult, fromCloudShare: Boolean) {
        Thread {
            val existingInfo = try { store.collectionsInfo() } catch (_: Throwable) { emptyList() }
            val nameToId = existingInfo.associate { it.name to it.id }
            val remainingSlots = (FavoritesStore.MAX_COLLECTIONS - existingInfo.size).coerceAtLeast(0)
            var created = 0
            var importedGroups = 0
            var importedChannels = 0
            var skippedGroups = 0
            val now = System.currentTimeMillis()
            for (group in selected) {
                val existingId = nameToId[group.displayName]
                if (existingId == null && created >= remainingSlots) { skippedGroups++; continue }
                val items = group.channels.take(FavoritesStore.MAX_ITEMS_PER_COLLECTION).mapIndexed { index, channel ->
                    FavoritesStore.FavoriteItem(
                        itemId = java.lang.Long.toHexString(now) + "-" + index,
                        title = channel.title.ifBlank { channel.url },
                        uri = channel.url,
                        source = "livesource",
                        time = now,
                        thumbPath = null,
                        artworkPath = null,
                        durationMs = 0L,
                        description = "",
                        resolution = "",
                        isLive = true
                    )
                }
                val result = store.importCloudCollection(
                    id = existingId ?: java.util.UUID.randomUUID().toString(),
                    name = group.displayName,
                    type = FavoritesStore.TYPE_SHARED,
                    items = items
                )
                if (result == FavoritesStore.OpResult.SUCCESS) {
                    importedGroups++
                    importedChannels += items.size
                    if (existingId == null) created++
                } else skippedGroups++
            }
            ui.post {
                val msg = if (importedGroups > 0) "已导入 $importedGroups 个合集，共 $importedChannels 个频道${if (skippedGroups > 0) "；$skippedGroups 个分组被跳过" else ""}" else "导入未成功，请稍后重试"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                onImported()
                if (importedGroups > 0 && !fromCloudShare) {
                    mainDialog?.dismiss()
                    showSharePromptDialog(parsed)
                } else {
                    returnFocusView?.post { returnFocusView?.requestFocus() }
                }
            }
        }.start()
    }
}
