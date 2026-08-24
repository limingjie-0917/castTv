package com.bd.casttv.screensaver

import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.MotionEvent

/** Android TV 系统屏保：收藏缩略图海报墙。绘制逻辑复用 [PosterWallView]。 */
class PosterDreamService : DreamService() {
    private lateinit var wall: PosterWallView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        wall = PosterWallView(this)
        setContentView(wall)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        finish()
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        finish()
        return true
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        wall.start()
    }

    override fun onDreamingStopped() {
        wall.stop()
        super.onDreamingStopped()
    }
}
