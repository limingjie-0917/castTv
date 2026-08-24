package com.bd.casttv.webparse

import android.os.Handler
import android.os.Looper

enum class WebParsePageType {
    LIST,
    DETAIL;

    companion object {
        fun from(value: String?): WebParsePageType {
            return when (value?.trim()?.lowercase()) {
                "list", "list_page", "列表页", "影片列表页" -> LIST
                else -> DETAIL
            }
        }
    }
}

data class WebParseRequest(
    val url: String,
    val pageType: WebParsePageType = WebParsePageType.DETAIL
)

object WebParseRequestBus {
    interface Listener {
        fun onWebParseUrl(url: String)
        fun onWebParseRequest(request: WebParseRequest) { onWebParseUrl(request.url) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()
    @Volatile private var pendingRequest: WebParseRequest? = null

    fun addListener(listener: Listener, replayPending: Boolean = true) {
        listeners.add(listener)
        if (replayPending) {
            val request = pendingRequest
            if (request != null && request.url.isNotBlank()) main.post { listener.onWebParseRequest(request) }
        }
    }

    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun submit(url: String, pageType: WebParsePageType = WebParsePageType.DETAIL) {
        val normalized = url.trim()
        if (normalized.isBlank()) return
        val request = WebParseRequest(normalized, pageType)
        pendingRequest = request
        main.post { listeners.toList().forEach { it.onWebParseRequest(request) } }
    }
}
