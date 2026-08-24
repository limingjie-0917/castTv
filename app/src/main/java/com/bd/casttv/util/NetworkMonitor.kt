package com.bd.casttv.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Observes LAN connectivity (Wi-Fi / Ethernet) and notifies a listener when a
 * usable network becomes available or is lost.
 *
 * WHY THIS EXISTS — boot auto-start race:
 * When the TV boots, [com.bd.casttv.receiver.BootReceiver] starts the renderer
 * services *before* Wi-Fi / Ethernet has finished connecting. At that moment
 * there is no local IP, so SSDP refuses to start and the jmDNS-based AirPlay
 * server fails to bind and permanently disables itself. The device then never
 * advertises itself and the phone "can't find the TV" after a reboot — even
 * though it works fine when the app is opened manually later.
 *
 * This monitor lets each service (re)bind its discovery listeners the moment a
 * real network shows up, and again whenever connectivity changes (Wi-Fi
 * reconnect / IP change), which makes boot auto-start reliable.
 */
class NetworkMonitor(
    context: Context,
    /** Invoked (on the main thread) when a usable network becomes available or changes. */
    private val onAvailable: () -> Unit,
    /** Invoked (on the main thread) when connectivity is lost. */
    private val onLost: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var registered = false

    // Debounce rapid onAvailable / onCapabilitiesChanged bursts so we don't
    // restart the listeners several times in a row while the network settles.
    private val debounceRunnable = Runnable {
        try {
            onAvailable()
        } catch (t: Throwable) {
            Log.w(TAG, "onAvailable callback threw", t)
        }
    }

    fun start() {
        if (registered) return
        val manager = cm ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleAvailable()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    scheduleAvailable()
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    try {
                        onLost()
                    } catch (t: Throwable) {
                        Log.w(TAG, "onLost callback threw", t)
                    }
                }
            }
        }
        try {
            // Listen to Wi-Fi and Ethernet explicitly instead of only the default
            // network, because Android TV boxes may keep multiple LAN transports
            // active at the same time and SSDP must re-evaluate all interfaces.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            manager.registerNetworkCallback(request, cb)
            callback = cb
            registered = true
            Log.i(TAG, "NetworkMonitor registered")
        } catch (t: Throwable) {
            Log.w(TAG, "registerNetworkCallback failed", t)
        }
    }

    fun stop() {
        mainHandler.removeCallbacks(debounceRunnable)
        val manager = cm
        val cb = callback
        if (manager != null && cb != null) {
            try {
                manager.unregisterNetworkCallback(cb)
            } catch (t: Throwable) {
                Log.w(TAG, "unregisterNetworkCallback failed", t)
            }
        }
        callback = null
        registered = false
    }

    private fun scheduleAvailable() {
        mainHandler.removeCallbacks(debounceRunnable)
        mainHandler.postDelayed(debounceRunnable, DEBOUNCE_MS)
    }

    companion object {
        private const val TAG = "NetworkMonitor"
        private const val DEBOUNCE_MS = 600L
    }
}
