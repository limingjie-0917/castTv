package com.bd.casttv.screensaver

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * App 内海报墙（前台空闲触发）。
 * 与系统屏保 [PosterDreamService] 共用 [PosterWallView] 绘制；任意按键/触摸即退出。
 * 仅用 AppCompatActivity + 自绘 View，禁用 Material 组件，规避主题 inflate 崩溃。
 */
class PosterWallActivity : AppCompatActivity() {
    private lateinit var posterWallView: PosterWallView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 沉浸式全屏：隐藏状态栏/导航栏（兼容 API 21）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        posterWallView = PosterWallView(this)
        setContentView(posterWallView)
    }

    override fun onResume() {
        super.onResume()
        posterWallView.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        finish()
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        finish()
        return true
    }

    override fun onPause() {
        super.onPause()
        posterWallView.stop()
    }
}
