package com.bd.casttv.ui.framework.pages

import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.bd.casttv.ui.ClippedImageView
import androidx.appcompat.widget.SwitchCompat
import com.bd.casttv.R
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.PhoneHubClientTracker
import com.bd.casttv.ui.framework.PhoneHubHost
import com.bd.casttv.util.NetworkUtils
import com.bd.casttv.util.QrCodeGenerator
import com.bd.casttv.util.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ④ 连接手机 PhoneHubPage：
 * - 4.1 HTTP 服务开关（端口 8899/8090，复用 [PhoneHubHost] 承接老 MainActivity 的启停逻辑）
 * - 4.2 扫码连接（QR 码，[QrCodeGenerator] 生成，展示 http://<lanIp>:<port>/）
 * - 4.3 局域网地址显示（可手动输入用）
 * - 4.4 手机端 HTTP 页面路由由 PhoneHubServer / HtmlPages 提供，收藏/历史/云同步 Tab AJAX 刷新
 * - 4.5 手机端功能与 TV 端对齐（在 PhoneHubServer 里维护）
 * - 4.6 已连接设备列表（[PhoneHubClientTracker] 记录）
 */
class PhoneHubPage(context: Context) : BasePage(context) {
    override val pageId = "phonehub"
    override val pageTitle = "连接手机"
    override val pageIconRes = R.drawable.ic_phone_hub
    override val enablePageScroll: Boolean = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true
    override val pageStickerRes: Int get() = R.drawable.sticker_shinchan

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(72), dp(8), dp(72), dp(32))
    }
    private var suppressSwitchChange = false
    private val serviceSwitch = SwitchCompat(context).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        showText = false
        textOn = ""
        textOff = ""
        splitTrack = false
        setThumbResource(R.drawable.switch_ios_thumb)
        setTrackResource(R.drawable.switch_ios_track)
        minWidth = dp(54)
        minimumWidth = dp(54)
        minHeight = dp(34)
        minimumHeight = dp(34)
        includeFontPadding = false
        clipToOutline = false
        setOnCheckedChangeListener { _, checked -> if (!suppressSwitchChange) toggleService(checked) }
    }
    private val serviceSwitchRow = switchRow()
    private val urlLabel = TextView(context).apply { textSize = 12f; setTextColor(Color.rgb(180, 187, 200)); gravity = Gravity.CENTER }
    private val statusLabel = TextView(context).apply { textSize = 16f; setTextColor(Color.rgb(214, 219, 226)); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
    private val qrImage = ImageView(context).apply { visibility = View.GONE }
    private val qrFallback = TextView(context).apply {
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(214, 219, 226))
        text = "📡\n请先开启 HTTP 服务\n开启后这里会显示二维码"
        setPadding(dp(16), dp(24), dp(16), dp(24))
        background = softCard()
    }
    private val copyUrlBtn = focusButton("复制局域网地址")
    private val viewClientsBtn = focusButton("查看已连接设备").apply {
        visibility = View.GONE
        setOnClickListener { showConnectedDevicesDialog() }
    }

    private val clientListContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val clientEmpty = TextView(context).apply { text = "暂无已连接设备"; textSize = 15f; setTextColor(Color.rgb(180, 187, 200)); setPadding(0, dp(8), 0, dp(8)) }

    private val hostListener = object : PhoneHubHost.Listener { override fun onPhoneHubStateChanged(running: Boolean, port: Int) { post { render() } } }
    private val clientListener = object : PhoneHubClientTracker.Listener { override fun onClientsChanged() { post { renderClients() } } }

    init {
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(72), dp(8), dp(72), dp(32))

        val centerSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        centerSection.addView(sectionTitle("手机交互中心"))

        val topContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        val qrWrap = FrameLayout(context).apply { foregroundGravity = Gravity.CENTER }
        qrWrap.addView(qrImage, FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER))
        qrWrap.addView(qrFallback, FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER))
        topContent.addView(qrWrap, LinearLayout.LayoutParams(dp(240), dp(240)))

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), 0, 0, 0)
        }
        infoCol.addView(serviceSwitchRow, LinearLayout.LayoutParams(dp(320), dp(52)))
        infoCol.addView(viewClientsBtn, LinearLayout.LayoutParams(dp(320), dp(44)).apply { topMargin = dp(10) })
        infoCol.addView(TextView(context).apply {
            text = "打开后即使关闭页面或进入视频播放页，手机端也能继续推送队列和读取播放状态。"
            textSize = 12f
            setTextColor(Color.rgb(180, 187, 200))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), 0)
        }, LinearLayout.LayoutParams(dp(360), LayoutParams.WRAP_CONTENT))
        infoCol.addView(statusLabel, LinearLayout.LayoutParams(dp(420), LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        infoCol.addView(urlLabel, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        topContent.addView(infoCol, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        val centerScroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        centerScroll.addView(topContent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        centerSection.addView(centerScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })

        root.addView(centerSection, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        contentContainer.addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onEnter() {
        PhoneHubHost.addListener(hostListener)
        PhoneHubClientTracker.addListener(clientListener)
        PhoneHubHost.ensureRunning(context)
        render()
    }
    override fun onLeave() {
        PhoneHubHost.removeListener(hostListener)
        PhoneHubClientTracker.removeListener(clientListener)
    }

    override fun refreshTheme() {
        super.refreshTheme()
        qrFallback.background = softCard()
    }

    // -------------------------------------------------------------- 交互
    private fun toggleService(checked: Boolean) {
        if (!checked) {
            PhoneHubHost.stop(context)
            toast("HTTP 服务已关闭")
        } else {
            serviceSwitch.isEnabled = false
            PhoneHubHost.startAsync(context) { ok ->
                serviceSwitch.isEnabled = true
                toast(if (ok) "HTTP 服务已开启" else "启动手机交互服务失败")
                if (!ok) setSwitchChecked(false)
                render()
            }
        }
    }

    private fun copyUrl() {
        val url = currentUrl() ?: run { toast("请先开启 HTTP 服务"); return }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("PhoneHub URL", url))
        toast("局域网地址已复制")
    }

    // -------------------------------------------------------------- 渲染
    private fun currentUrl(): String? {
        if (!PhoneHubHost.isRunning()) return null
        val ip = try { NetworkUtils.getLocalIpAddress() } catch (_: Throwable) { null }
        val port = PhoneHubHost.port()
        if (ip.isNullOrBlank() || port <= 0) return null
        return "http://$ip:$port/"
    }

    private fun render() {
        val running = PhoneHubHost.isRunning()
        val port = PhoneHubHost.port()
        val ip = try { NetworkUtils.getLocalIpAddress() } catch (_: Throwable) { null }
        setSwitchChecked(running)

        if (running && !ip.isNullOrBlank() && port > 0) {
            val url = "http://$ip:$port/"
            urlLabel.text = "局域网地址：$url"
            statusLabel.text = "服务状态：运行中"
            statusLabel.setTextColor(Color.rgb(67, 185, 127))
            viewClientsBtn.visibility = View.VISIBLE
            val bmp = QrCodeGenerator.encode(url, dp(240))
            if (bmp != null) {
                qrImage.setImageBitmap(bmp); qrImage.visibility = View.VISIBLE; qrFallback.visibility = View.GONE
            } else {
                qrImage.visibility = View.GONE
                qrFallback.visibility = View.VISIBLE
                qrFallback.text = "⚠️\n二维码生成失败\n请手动输入上方地址"
            }
        } else {
            urlLabel.text = if (!ip.isNullOrBlank()) "局域网 IP：$ip · 端口未启用（候选：8899 / 8090）" else "未获取到局域网 IP"
            statusLabel.text = "服务状态：未开启"
            statusLabel.setTextColor(Color.rgb(246, 196, 69))
            viewClientsBtn.visibility = View.GONE
            qrImage.visibility = View.GONE
            qrFallback.visibility = View.VISIBLE
            qrFallback.text = "📡\n请先开启 HTTP 服务\n开启后这里会显示二维码"
        }
        renderClients()
    }

    private fun setSwitchChecked(checked: Boolean) {
        suppressSwitchChange = true
        serviceSwitch.isChecked = checked
        // 注意：SwitchCompat 继承 TextView，setText 会在开关左侧显示，
        // 由于 row 宽度紧凑会被裁剪成一条竖线视觉伪影，因此这里显式清空 text。
        serviceSwitch.text = ""
        suppressSwitchChange = false
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private fun showConnectedDevicesDialog() {
        detachFromParent(clientListContainer)
        detachFromParent(clientEmpty)

        val outerPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            // 弹窗规范：内容区域 4dp 内边距（在 panel 层设置）
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                ThemeManager.currentPalette(context).dialogTitleGradient
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        outerPanel.addView(panel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 顶部标题栏（圆形贴纸 + 标题），与设置弹窗风格对齐
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
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "已连接设备"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val listCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = softCard()
            addView(clientListContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(clientEmpty)
        }
        scroll.addView(listCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(360)))

        val refreshBtn = dialogButton("刷新") {
            renderClients()
            toast("已刷新")
        }
        val closeBtn = dialogButton("关闭") { /* dialog.dismiss() 在 setOnShowListener 里绑定 */ }

        // 先创建 dialog，方便 closeBtn 绑定 dismiss
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(outerPanel)
            .create()

        closeBtn.setOnClickListener { dialog.dismiss() }

        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(closeBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(refreshBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(14)
        })

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(dp(560), LayoutParams.WRAP_CONTENT)
            renderClients()
            refreshBtn.requestFocus()
        }
        dialog.show()
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun renderClients() {
        clientListContainer.removeAllViews()
        val list = PhoneHubClientTracker.snapshot()
        val now = System.currentTimeMillis()
        clientEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEach { c ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val active = PhoneHubClientTracker.isActive(c, now)
            val info = TextView(context).apply {
                text = buildString {
                    append(c.ip)
                    append(if (active) "  🟢 活跃" else "  ⚪ 离线")
                    append("\n")
                    append("UA：").append(c.userAgent.ifBlank { "unknown" }.take(60))
                    append("\n首次：").append(timeFmt.format(Date(c.firstSeen)))
                    append("  最近：").append(timeFmt.format(Date(c.lastSeen)))
                    append("  请求：").append(c.requestCount)
                }
                textSize = 14f
                setTextColor(Color.rgb(230, 234, 240))
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
            val disconnect = focusButton("断开").apply {
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(40)).apply { leftMargin = dp(12) }
                setOnClickListener {
                    AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
                        .setTitle("断开设备")
                        .setMessage("确定要在列表中移除 ${c.ip} 吗？（对方仍可再次访问 HTTP 服务）")
                        .setPositiveButton("移除") { d, _ -> PhoneHubClientTracker.disconnect(c.ip); d.dismiss() }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            row.addView(info)
            row.addView(disconnect)
            clientListContainer.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    // -------------------------------------------------------------- 样式辅助
    private fun sectionTitle(text: String) = TextView(context).apply { this.text = text; textSize = 22f; setTextColor(WARM); gravity = Gravity.CENTER_HORIZONTAL }
    private fun softCard(): GradientDrawable {
        val palette = ThemeManager.currentPalette(context)
        val start = parseThemeColor(palette.contentPanelGradientA, 176, Color.rgb(20, 24, 32))
        val end = parseThemeColor(palette.contentPanelGradientB, 140, Color.rgb(48, 54, 68))
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(start, end)
        ).apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.argb(80, 255, 255, 255))
        }
    }
    private fun focusButton(initial: String = ""): TextView {
        val tv = TextView(context)
        tv.text = initial; tv.textSize = 16f; tv.setTextColor(Color.rgb(220, 224, 232))
        tv.gravity = Gravity.CENTER
        tv.isFocusable = true; tv.isFocusableInTouchMode = true; tv.isClickable = true
        val bg = { focused: Boolean, selected: Boolean ->
            GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(180, 24, 28, 36))
                setStroke(dp(if (focused) 2 else 1), when { focused -> WARM; selected -> WARM; else -> Color.argb(120, 200, 200, 210) })
            }
        }
        tv.background = bg(false, false)
        tv.setOnFocusChangeListener { v, has ->
            v.background = bg(has, v.isSelected)
            (v as TextView).setTextColor(if (has) WARM else Color.rgb(220, 224, 232))
            // 全局焦点 fx（A：scale + translationZ 发光；不叠加暖黄前景）。
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 12)
        }
        return tv
    }

    /**
     * 弹窗/面板统一按钮工厂：默认态不手写暖黄，仅在选中态/焦点态使用暖黄。
     * 样式与设置弹窗保持一致（SettingsPage.dialogButton）。
     */
    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true

        fun refresh(focused: Boolean) {
            val selected = isSelected
            setTextColor(if (selected) WARM else Color.argb(235, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(170, 210, 214, 222))
                setColor(Color.argb(52, 32, 34, 40))
            }
        }

        refresh(false)
        setOnFocusChangeListener { v, has ->
            refresh(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
        }
        setOnClickListener { click() }
    }

    private fun switchRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            background = switchBg(false)
            setOnClickListener { serviceSwitch.performClick() }
            setOnFocusChangeListener { _, has -> background = switchBg(has); if (has) serviceSwitch.requestFocus() }
        }
        row.addView(TextView(context).apply { text = "HTTP 服务"; textSize = 15f; setTextColor(Color.rgb(220, 224, 232)); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        row.addView(serviceSwitch, LinearLayout.LayoutParams(dp(60), dp(36)))
        serviceSwitch.setOnFocusChangeListener { _, has -> row.background = switchBg(has) }
        return row
    }
    private fun switchBg(focused: Boolean) = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.argb(180, 24, 28, 36)); setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(120, 200, 200, 210)) }
    private fun toast(s: String) { Toast.makeText(context, s, Toast.LENGTH_SHORT).show() }
    private fun parseThemeColor(hex: String, alpha: Int, fallback: Int): Int = try {
        val rgb = Color.parseColor(hex)
        Color.argb(alpha.coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    } catch (_: Throwable) {
        Color.argb(alpha.coerceIn(0, 255), Color.red(fallback), Color.green(fallback), Color.blue(fallback))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object { private val WARM = Color.rgb(245, 196, 81) }
}
