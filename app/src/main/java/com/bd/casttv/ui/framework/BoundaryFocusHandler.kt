package com.bd.casttv.ui.framework

import android.view.KeyEvent
import android.view.View
import android.view.animation.CycleInterpolator

object BoundaryFocusHandler {
    fun onRootKey(page: BasePage, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { page.pageContainer?.switchBy(-1) ?: false }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { page.pageContainer?.switchBy(1) ?: false }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (!page.focusToFirstContent()) shake(page); true }
            KeyEvent.KEYCODE_DPAD_UP -> { shake(page); true }
            KeyEvent.KEYCODE_BACK -> { page.pageContainer?.backToHomeOrExit() ?: false }
            else -> false
        }
    }
    fun onContentBoundary(view: View, event: KeyEvent, direction: Int, page: BasePage): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (direction) {
            View.FOCUS_LEFT, View.FOCUS_RIGHT -> { page.focusToRoot(); true }
            View.FOCUS_UP, View.FOCUS_DOWN -> { shake(view); true }
            else -> false
        }
    }
    fun shake(view: View) {
        val d = 6f * view.resources.displayMetrics.density
        view.animate()
            .translationX(d)
            .setInterpolator(CycleInterpolator(2f))
            .setDuration(200)
            .withEndAction { view.translationX = 0f }
            .start()
    }

    fun cancelShake(view: View) {
        view.animate().cancel()
        view.clearAnimation()
        view.translationX = 0f
    }
}
