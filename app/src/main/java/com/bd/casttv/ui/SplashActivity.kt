package com.bd.casttv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.bd.casttv.R

/**
 * App 启动图页面：随机展示一张启动壁纸，2 秒后进入主界面。
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val jumpToMain = Runnable {
        startActivity(Intent(this, com.bd.casttv.ui.framework.NewMainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val splashImages = listOf(
            R.drawable.splash_bg_2,
            R.drawable.splash_bg_4
        )
        val randomImage = splashImages.random()
        findViewById<ImageView>(R.id.splashImage).setImageResource(randomImage)

        handler.postDelayed(jumpToMain, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(jumpToMain)
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_DURATION_MS = 2_000L
    }
}
