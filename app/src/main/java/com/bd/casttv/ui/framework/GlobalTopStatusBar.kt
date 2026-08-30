package com.bd.casttv.ui.framework

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.TypefaceSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.bd.casttv.R
import com.bd.casttv.airplay.AirPlayService
import com.bd.casttv.dlna.DlnaRendererService
import com.bd.casttv.receiver.CastReceiverService
import com.bd.casttv.util.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 全局顶部状态栏（35dp × 全屏宽）。原 40dp，按 UI 优化要求收窄 5dp。
 *
 * 设计遵循 [taste-ui-statusbar Skill](.trae/skills/taste-ui-statusbar/SKILL.md)：
 *  - 背景：6 层 LayerDrawable 叠加「染色膜 base + veil 散射膜 + 内折射描边 + 顶部高光 + 底部阴影 + 底部分割线」，
 *    模拟透明磨砂毛玻璃效果，不依赖 RenderEffect/BlurMaskFilter（TV 老盒子兼容）；
 *  - 主题联动：从 [ThemeManager.currentPalette] 的 [ThemeManager.StatusBarFrost] 取染色膜参数，
 *    支持 5 套预设（深灰/宝蓝/蜡笔/经典/夕阳）+ 自定义（含极浅色染料 WCAG 对比度兜底）；
 *  - 文字/图标：深底冷白 / 浅底深色，自动按染色膜亮度切换；在线色跟随 palette.accent；
 *  - 排版密度：左（<页面标题>｜<竖分隔线>｜设备名称 xxx）· 中（时间，等宽 BOLD 16sp）· 右（DLNA / AirPlay / 网络 / Cast 指示）三列；
 *    页面标题通过 [setPageTitle] 注入；首页/更多功能页传空串时「标题 + 竖分隔线」整体隐藏。
 *  - 动效克制：主题切换 crossfade 220ms、服务状态 2 帧混色过渡、Cast 脉冲 withLayer()。
 */
class GlobalTopStatusBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null)
    : FrameLayout(context, attrs), SettingsChangeBus.Listener {

    enum class NetworkState { WIFI, ETHERNET, OFFLINE, UNKNOWN }

    private val handler = Handler(Looper.getMainLooper())
    private val pageTitle = label(13f, false).also {
        it.text = ""
        it.maxLines = 1
        it.ellipsize = android.text.TextUtils.TruncateAt.END
        it.setPadding(0, 0, 0, 0)
    }
    private val titleDeviceDivider = View(context).also {
        it.isFocusable = false
    }
    private val deviceLabel = label(11f, true).also { it.text = "设备名称：" }
    private val device = label(12f, false).also { it.text = "小新的TV" }
    private val time = label(16f, false, Typeface.MONOSPACE).also {
        it.text = "--:--"
        it.letterSpacing = 0.02f
    }
    private val dlnaIcon = statusIcon(R.drawable.ic_status_service)
    private val dlnaText = label(11f, false).also { it.text = "DLNA" }
    private val airplayIcon = statusIcon(R.drawable.ic_status_service)
    private val airplayText = label(11f, false).also { it.text = "AirPlay" }
    private val networkIcon = statusIcon(R.drawable.ic_status_wifi)
    private val networkText = label(11f, false).also { it.text = "网络" }
    private val cast = label(16f, false).also { it.text = "●" }
    private var castActive = false
    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { post { refreshNetwork() } }
        override fun onLost(network: Network) { post { refreshNetwork() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { post { refreshNetwork() } }
    }
    private val tick = object : Runnable {
        override fun run() {
            updateTime(); refreshServiceStates()
            handler.postDelayed(this, 60_000L)
        }
    }

    init {
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setPadding(dp(18), dp(4), dp(18), dp(4))
        refreshTheme()

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        // 页面标题：有则显示，无则占位隐藏；最长受 weight 限制，避免与右侧状态图标/居中时间争抢。
        val pageTitleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(12)
        }
        left.addView(pageTitle, pageTitleLp)
        // 分隔线：标题有内容时才显示（首页空标题时避免遗留一根竖线）。
        titleDeviceDivider.apply {
            val tokens = ThemeManager.statusBarTextTokens(this@GlobalTopStatusBar.context)
            val c = tokens.textSecondary
            setBackgroundColor(Color.argb(160, Color.red(c), Color.green(c), Color.blue(c)))
        }
        left.addView(
            titleDeviceDivider,
            LinearLayout.LayoutParams(dp(1), dp(18)).apply {
                marginEnd = dp(12)
                gravity = Gravity.CENTER_VERTICAL
            }
        )
        // 设备名部分：label + device 合并 announcement
        val deviceGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            contentDescription = "设备名称 小新的TV"
        }
        deviceGroup.addView(deviceLabel, llWrap())
        deviceGroup.addView(device, llWrap().apply { marginStart = dp(4) })
        left.addView(deviceGroup, llWrap())

        val right = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            orientation = LinearLayout.HORIZONTAL
            isFocusable = false
        }
        listOf(
            statusGroup(dlnaText, dlnaIcon, "DLNA 服务"),
            statusGroup(airplayText, airplayIcon, "AirPlay 服务"),
            statusGroup(networkText, networkIcon, "网络连接"),
            cast
        ).forEachIndexed { i, v ->
            val lp = llWrap()
            if (i > 0) lp.leftMargin = dp(10)
            right.addView(v, lp)
        }

        addView(left, frameWrap(Gravity.START or Gravity.CENTER_VERTICAL))
        addView(right, frameWrap(Gravity.END or Gravity.CENTER_VERTICAL))
        addView(time, frameWrap(Gravity.CENTER))

        setDlnaOnline(false)
        setAirplayOnline(false)
        setNetworkState(NetworkState.UNKNOWN)
        setCastActive(false)
        updateTime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        SettingsChangeBus.addListener(this)
        registerNetworkCallback()
        refreshNetwork()
        refreshServiceStates()
        tick.run()
    }

    override fun onDetachedFromWindow() {
        SettingsChangeBus.removeListener(this)
        unregisterNetworkCallback()
        handler.removeCallbacks(tick)
        cast.animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onSettingsChanged() {
        // 仅与主题/设置联动刷新；设备名由 Activity 通过 setDeviceName() 主动通知。
        refreshTheme()
    }

    fun setDeviceName(name: String) {
        device.text = name
        val p = device.parent as? LinearLayout
        if (p != null) p.contentDescription = "设备名称 $name"
        syncAccessibility()
    }

    /**
     * 设置状态栏最左侧的「当前页面标题」。
     * 传空 / 空白时：隐藏标题 + 分隔线，仅保留设备名（首页用）。
     * 长标题自动 ellipsize=END，避免挤压时间/右侧服务状态图标。
     */
    fun setPageTitle(title: CharSequence?) {
        val t = title?.toString().orEmpty().trim()
        pageTitle.text = t
        val hasTitle = t.isNotEmpty()
        pageTitle.visibility = if (hasTitle) View.VISIBLE else View.GONE
        titleDeviceDivider.visibility = if (hasTitle) View.VISIBLE else View.GONE
        syncAccessibility()
    }

    private fun syncAccessibility() {
        val title = pageTitle.text?.toString().orEmpty().trim()
        val dev = device.text?.toString().orEmpty()
        contentDescription = buildString {
            if (title.isNotEmpty()) append(title).append("；")
            append("设备名称 ").append(dev)
        }
    }

    fun refreshTheme() {
        // 主题切换：crossfade 220ms，不做位移/缩放（Taste Skill §2.5）
        runCatching {
            TransitionManager.beginDelayedTransition(this, AutoTransition().setDuration(220))
        }
        background = ThemeManager.frostedStatusBarBackground(context)

        // 同步重染文字 / 图标（深底↔浅底切换时文本要随之翻转）
        val tokens = ThemeManager.statusBarTextTokens(context)
        run {
            pageTitle.setTextColor(tokens.textPrimary)
            val sc = tokens.textSecondary
            titleDeviceDivider.setBackgroundColor(Color.argb(160, Color.red(sc), Color.green(sc), Color.blue(sc)))
            deviceLabel.setTextColor(tokens.textSecondary)
            device.setTextColor(tokens.textPrimary)
            time.setTextColor(tokens.textPrimary)
            dlnaText.setTextColor(tokens.textPrimary)
            airplayText.setTextColor(tokens.textPrimary)
            networkText.setTextColor(tokens.textPrimary)
        }
        // 在线/离线色被 statusColor 覆盖，触发一次刷新确保应用最新 iconOn/iconOff
        refreshNetwork()
        refreshServiceStates()
        setCastActive(castActive)
    }

    fun setDlnaOnline(online: Boolean) {
        val tokens = ThemeManager.statusBarTextTokens(context)
        tintStatusIcon(dlnaIcon, statusColor(online, tokens))
        dlnaText.setTextColor(tokens.textPrimary)
        dlnaText.announceForAccessibility("DLNA ${if (online) "在线" else "离线"}")
    }

    fun setAirplayOnline(online: Boolean) {
        val tokens = ThemeManager.statusBarTextTokens(context)
        tintStatusIcon(airplayIcon, statusColor(online, tokens))
        airplayText.setTextColor(tokens.textPrimary)
        airplayText.announceForAccessibility("AirPlay ${if (online) "在线" else "离线"}")
    }

    fun setNetworkState(state: NetworkState) {
        val iconRes = when (state) {
            NetworkState.WIFI -> R.drawable.ic_status_wifi
            NetworkState.ETHERNET -> R.drawable.ic_status_ethernet
            NetworkState.OFFLINE, NetworkState.UNKNOWN -> R.drawable.ic_status_network_off
        }
        networkIcon.setImageResource(iconRes)
        val tokens = ThemeManager.statusBarTextTokens(context)
        val online = state == NetworkState.WIFI || state == NetworkState.ETHERNET
        tintStatusIcon(networkIcon, if (online) tokens.iconOn else tokens.iconOff)
        networkText.setTextColor(tokens.textPrimary)
        val desc = when (state) {
            NetworkState.WIFI -> "无线网络已连接"
            NetworkState.ETHERNET -> "有线网络已连接"
            NetworkState.OFFLINE -> "网络已断开"
            NetworkState.UNKNOWN -> "网络状态未知"
        }
        (networkIcon.parent as? LinearLayout)?.announceForAccessibility(desc)
    }

    fun setCastActive(active: Boolean) {
        castActive = active
        cast.setTextColor(if (active) Color.parseColor("#4CAF50") else Color.TRANSPARENT)
        cast.animate().cancel()
        cast.setLayerType(LAYER_TYPE_HARDWARE, null)
        if (active) {
            cast.animate().withLayer().alpha(0.25f).setDuration(450).withEndAction {
                if (castActive) cast.animate().withLayer().alpha(1f).setDuration(450).start()
            }.start()
        } else {
            cast.alpha = 1f
        }
    }

    private fun refreshNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val state = when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkState.OFFLINE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkState.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkState.WIFI
            else -> NetworkState.UNKNOWN
        }
        setNetworkState(state)
    }

    private fun refreshServiceStates() {
        setDlnaOnline(runCatching { DlnaRendererService.isRunning }.getOrDefault(false))
        setAirplayOnline(runCatching { AirPlayService.isRunning }.getOrDefault(false))
        setCastActive(runCatching { CastReceiverService.isRunning }.getOrDefault(false))
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
        }
    }

    private fun unregisterNetworkCallback() {
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        connectivityManager = null
    }

    private fun updateTime() {
        val s = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        // 用等宽 span 再叠一层保险，避免 1/0 抖动
        val ss = SpannableString(s)
        ss.setSpan(TypefaceSpan("monospace"), 0, ss.length, 0)
        ss.setSpan(AbsoluteSizeSpan(16, true), 0, ss.length, 0)
        time.text = ss
    }

    // ---- factory helpers ----

    private fun label(sp: Float, secondary: Boolean, tf: Typeface = Typeface.DEFAULT_BOLD) =
        TextView(context).apply {
            textSize = sp
            gravity = Gravity.CENTER_VERTICAL
            typeface = tf
            setTextColor(
                if (secondary) {
                    ThemeManager.statusBarTextTokens(this@GlobalTopStatusBar.context).textSecondary
                } else {
                    ThemeManager.statusBarTextTokens(this@GlobalTopStatusBar.context).textPrimary
                }
            )
            includeFontPadding = false
            isFocusable = false
            isClickable = false
        }

    private fun statusIcon(drawableRes: Int) = AppCompatImageView(context).apply {
        setImageResource(drawableRes)
        scaleType = ImageView.ScaleType.FIT_CENTER
        minimumWidth = dp(18); minimumHeight = dp(18)
        val lp = LinearLayout.LayoutParams(dp(18), dp(18))
        lp.gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp
        isFocusable = false
        isClickable = false
    }

    private fun statusGroup(
        text: TextView,
        icon: AppCompatImageView,
        contentDesc: String
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        this.contentDescription = contentDesc
        addView(text, llWrap())
        addView(icon, (icon.layoutParams as? LinearLayout.LayoutParams ?: llWrap()).apply {
            leftMargin = dp(4)
            gravity = Gravity.CENTER_VERTICAL
        })
    }

    private fun tintStatusIcon(view: AppCompatImageView, color: Int) {
        view.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
    }

    private fun statusColor(ok: Boolean, tokens: ThemeManager.StatusBarTextTokens): Int =
        if (ok) tokens.iconOn else tokens.iconOff

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun llWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )
    private fun frameWrap(gravity: Int) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        gravity
    )
}
