package com.bd.casttv.ui.framework

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.widget.AppCompatImageView
import com.bd.casttv.settings.Settings
import java.io.File

class WallpaperLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), WallpaperManager.Listener {

    private val imageView = WallpaperImageView(context)
    private val dimOverlay = DimOverlayView(context)

    init {
        isFocusable = false
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dimOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        WallpaperManager.addListener(this)
        applyConfig(WallpaperManager.currentConfig(context))
    }

    override fun onDetachedFromWindow() {
        WallpaperManager.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onWallpaperChanged(config: WallpaperManager.Config) = applyConfig(config)

    private fun applyConfig(config: WallpaperManager.Config) {
        visibility = if (config.enabled) View.VISIBLE else View.GONE
        if (!config.enabled) return

        if (config.bgType == Settings.BG_TYPE_COLOR) {
            // 背景色模式
            imageView.setImageDrawable(null)
            if (config.colorMode == Settings.COLOR_MODE_GRADIENT) {
                val colorA = try { Color.parseColor(config.gradientA) } catch (_: Throwable) { Color.parseColor("#1A1A2E") }
                val colorB = try { Color.parseColor(config.gradientB) } catch (_: Throwable) { Color.parseColor("#16213E") }
                imageView.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(colorA, colorB)
                )
            } else {
                val color = try { Color.parseColor(config.solidColor) } catch (_: Throwable) { Color.parseColor("#1A1A2E") }
                imageView.setBackgroundColor(color)
            }
        } else {
            // 壁纸图片模式
            imageView.background = null
            if (config.source == WallpaperManager.SOURCE_NONE) {
                imageView.setImageDrawable(null)
            } else if (config.source.startsWith("custom:")) {
                // 自定义壁纸
                val fileName = config.source.removePrefix("custom:")
                val file = File(WallpaperManager.customWallpaperDir(context), fileName)
                if (file.exists()) {
                    try {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        imageView.setImageBitmap(bmp)
                    } catch (_: Throwable) {
                        imageView.setImageResource(WallpaperManager.resolveDrawable(config.source))
                    }
                } else {
                    imageView.setImageResource(WallpaperManager.resolveDrawable("wallpaper_kids_1"))
                }
            } else {
                imageView.setImageResource(WallpaperManager.resolveDrawable(config.source))
            }
        }

        dimOverlay.setDim(config.dim)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            imageView.setRenderEffect(
                if (config.blur > 0) android.graphics.RenderEffect.createBlurEffect(
                    config.blur.toFloat(), config.blur.toFloat(), android.graphics.Shader.TileMode.CLAMP
                ) else null
            )
        }
    }
}

class WallpaperImageView(context: Context) : AppCompatImageView(context) {
    init { scaleType = ScaleType.CENTER_CROP }
}

class DimOverlayView(context: Context) : View(context) {
    fun setDim(percent: Int) {
        val alpha = (255 * percent.coerceIn(0, 80) / 100f).toInt()
        setBackgroundColor(Color.argb(alpha, 0, 0, 0))
    }
}
