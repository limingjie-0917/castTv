package com.bd.casttv.ui.framework

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import com.bd.casttv.R
import com.bd.casttv.airplay.AirPlayService
import com.bd.casttv.dlna.DlnaRendererService
import com.bd.casttv.receiver.CastReceiverService
import com.bd.casttv.util.ThemeManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GlobalTopStatusBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    enum class NetworkState { WIFI, ETHERNET, OFFLINE, UNKNOWN }

    private val handler = Handler(Looper.getMainLooper())
    private val deviceLabel = label("设备名称：", 12f, LIGHT_GREY)
    private val device = label("小新的TV", 12f, TEXT)
    private val time = label("--:--", 16f, TEXT)
    private val dlnaIcon = statusIcon(R.drawable.ic_status_service)
    private val dlnaText = label("DLNA", 11f, TEXT)
    private val airplayIcon = statusIcon(R.drawable.ic_status_service)
    private val airplayText = label("AirPlay", 11f, TEXT)
    private val networkIcon = statusIcon(R.drawable.ic_status_wifi)
    private val networkText = label("网络", 11f, TEXT)
    private val cast = label("●", 16f, Color.TRANSPARENT)
    private var castActive = false
    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { post { refreshNetwork() } }
        override fun onLost(network: Network) { post { refreshNetwork() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { post { refreshNetwork() } }
    }
    private val tick = object : Runnable { override fun run() { updateTime(); refreshServiceStates(); handler.postDelayed(this, 60_000L) } }

    init {
        isFocusable = false
        setPadding(dp(18), dp(4), dp(18), dp(4))
        refreshTheme()

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        left.addView(deviceLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        left.addView(device, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val right = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            orientation = LinearLayout.HORIZONTAL
            isFocusable = false
        }
        listOf(
            statusGroup(dlnaText, dlnaIcon),
            statusGroup(airplayText, airplayIcon),
            statusGroup(networkText, networkIcon),
            cast
        ).forEach { right.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) }) }

        addView(left, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        addView(right, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        addView(time, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        setDlnaOnline(false)
        setAirplayOnline(false)
        setNetworkState(NetworkState.UNKNOWN)
        setCastActive(false)
        updateTime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerNetworkCallback()
        refreshNetwork()
        refreshServiceStates()
        tick.run()
    }

    override fun onDetachedFromWindow() {
        unregisterNetworkCallback()
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    fun setDeviceName(name: String) { device.text = name }

    fun refreshTheme() {
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            ThemeManager.currentPalette(context).topStatusBarGradient
        )
    }

    fun setDlnaOnline(online: Boolean) {
        tintStatusIcon(dlnaIcon, statusColor(online))
        dlnaText.setTextColor(TEXT)
    }

    fun setAirplayOnline(online: Boolean) {
        tintStatusIcon(airplayIcon, statusColor(online))
        airplayText.setTextColor(TEXT)
    }

    fun setNetworkState(state: NetworkState) {
        val iconRes = when (state) {
            NetworkState.WIFI -> R.drawable.ic_status_wifi
            NetworkState.ETHERNET -> R.drawable.ic_status_ethernet
            NetworkState.OFFLINE, NetworkState.UNKNOWN -> R.drawable.ic_status_network_off
        }
        networkIcon.setImageResource(iconRes)
        tintStatusIcon(networkIcon, if (state == NetworkState.WIFI || state == NetworkState.ETHERNET) ONLINE else OFFLINE)
        networkText.setTextColor(TEXT)
    }

    fun setCastActive(active: Boolean) {
        castActive = active
        cast.setTextColor(if (active) Color.parseColor("#4CAF50") else Color.TRANSPARENT)
        if (active) {
            cast.animate().alpha(0.25f).setDuration(450).withEndAction {
                if (castActive) cast.animate().alpha(1f).setDuration(450).start()
            }.start()
        } else {
            cast.animate().cancel()
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
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                networkCallback
            )
        }
    }

    private fun unregisterNetworkCallback() {
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        connectivityManager = null
    }

    private fun updateTime() {
        time.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun label(s: String, sp: Float, color: Int) = TextView(context).apply {
        text = s
        textSize = sp
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(color)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        isFocusable = false
    }

    private fun statusIcon(drawableRes: Int) = AppCompatImageView(context).apply {
        setImageResource(drawableRes)
        scaleType = ImageView.ScaleType.FIT_CENTER
        minimumWidth = dp(18)
        minimumHeight = dp(18)
        isFocusable = false
    }

    private fun statusGroup(text: TextView, icon: AppCompatImageView) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = false
        addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(icon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(4) })
    }

    private fun tintStatusIcon(view: AppCompatImageView, color: Int) {
        view.setColorFilter(color)
    }

    private fun statusColor(ok: Boolean) = if (ok) ONLINE else OFFLINE
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val ONLINE = Color.WHITE
        private val OFFLINE = Color.rgb(102, 102, 102)
        private val TEXT = Color.rgb(214, 219, 226)
        private val LIGHT_GREY = Color.rgb(170, 175, 180)
        private val ROYAL_BLUE = Color.rgb(46, 99, 196)
        private val ROYAL_BLUE_DARK = Color.rgb(33, 73, 143)
        private val ROYAL_BLUE_DEEP = Color.rgb(22, 54, 111)
    }
}
