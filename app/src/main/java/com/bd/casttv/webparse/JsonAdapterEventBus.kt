package com.bd.casttv.webparse

import android.os.Handler
import android.os.Looper

object JsonAdapterEventBus {
    interface Listener {
        fun onJsonAdapterImported(fileName: String, pageKind: ParsePageKind = ParsePageKind.DETAIL)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun notifyImported(fileName: String, pageKind: ParsePageKind = ParsePageKind.DETAIL) {
        if (fileName.isBlank()) return
        main.post { listeners.toList().forEach { it.onJsonAdapterImported(fileName, pageKind) } }
    }
}
