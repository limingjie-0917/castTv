package com.bd.casttv.ui.framework

import java.util.concurrent.CopyOnWriteArraySet

object SettingsChangeBus {
    interface Listener { fun onSettingsChanged() }
    private val listeners = CopyOnWriteArraySet<Listener>()
    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }
    fun notifyChanged() { listeners.forEach { it.onSettingsChanged() } }
}
