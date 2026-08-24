package com.bd.casttv.ui.framework.demo

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.bd.casttv.settings.Settings
import com.bd.casttv.ui.framework.BottomIndicatorBar
import com.bd.casttv.ui.framework.GlobalTopStatusBar
import com.bd.casttv.ui.framework.PageContainer
import com.bd.casttv.ui.framework.WallpaperLayer

class FrameworkDemoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        val wallpaper = WallpaperLayer(this)
        val pages = PageContainer(this)
        val top = GlobalTopStatusBar(this).apply { setDeviceName(Settings(this@FrameworkDemoActivity).deviceName) }
        val indicator = BottomIndicatorBar(this)
        pages.indicator = indicator
        root.addView(wallpaper, FrameLayout.LayoutParams(-1, -1))
        root.addView(pages, FrameLayout.LayoutParams(-1, -1))
        root.addView(top, FrameLayout.LayoutParams(-1, dp(44), Gravity.TOP))
        root.addView(indicator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(86), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24) })
        setContentView(root)
        pages.bindPages(listOf(HomeDemoPage(this), FavoritesDemoPage(this), SettingsDemoPage(this)))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
