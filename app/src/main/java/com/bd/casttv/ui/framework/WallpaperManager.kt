package com.bd.casttv.ui.framework

import android.content.Context
import com.bd.casttv.R
import com.bd.casttv.settings.Settings
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

object WallpaperManager {
    data class Config(val enabled: Boolean, val source: String, val dim: Int, val blur: Int,
                      val bgType: String, val colorMode: String,
                      val solidColor: String, val gradientA: String, val gradientB: String)
    interface Listener { fun onWallpaperChanged(config: Config) }

    private val listeners = CopyOnWriteArraySet<Listener>()

    /** 预置壁纸列表（按顺序） */
    const val SOURCE_NONE = "none"

    val PRESET_WALLPAPERS = listOf(
        "wallpaper_kids_1",
        "wallpaper_kids_2",
        "wallpaper_kids_3",
        "wallpaper_shinchan_user"
    )

    /** 自定义壁纸最大数量 */
    const val MAX_CUSTOM_WALLPAPERS = 13

    fun currentConfig(context: Context): Config = Settings(context).run {
        Config(wallpaperEnabled, wallpaperSource, wallpaperDim, wallpaperBlur,
            wallpaperBgType, wallpaperColorMode, wallpaperSolidColor,
            wallpaperGradientColorA, wallpaperGradientColorB)
    }

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun setEnabled(context: Context, enabled: Boolean) { Settings(context).wallpaperEnabled = enabled; notifyChanged(context) }
    fun setSource(context: Context, source: String) { Settings(context).wallpaperSource = source; notifyChanged(context) }
    fun setDim(context: Context, dim: Int) { Settings(context).wallpaperDim = dim; notifyChanged(context) }
    fun setBlur(context: Context, blur: Int) { Settings(context).wallpaperBlur = blur; notifyChanged(context) }
    fun setBgType(context: Context, type: String) { Settings(context).wallpaperBgType = type; notifyChanged(context) }
    fun setColorMode(context: Context, mode: String) { Settings(context).wallpaperColorMode = mode; notifyChanged(context) }
    fun setSolidColor(context: Context, color: String) { Settings(context).wallpaperSolidColor = color; notifyChanged(context) }
    fun setGradientA(context: Context, color: String) { Settings(context).wallpaperGradientColorA = color; notifyChanged(context) }
    fun setGradientB(context: Context, color: String) { Settings(context).wallpaperGradientColorB = color; notifyChanged(context) }

    fun notifyChanged(context: Context) {
        val config = currentConfig(context.applicationContext)
        listeners.forEach { it.onWallpaperChanged(config) }
    }

    fun resolveDrawable(source: String): Int = when (source) {
        "wallpaper_kids_1" -> R.drawable.wallpaper_kids_1
        "wallpaper_kids_2" -> R.drawable.wallpaper_kids_2
        "wallpaper_kids_3" -> R.drawable.wallpaper_kids_3
        "wallpaper_shinchan_user" -> R.drawable.wallpaper_shinchan_user
        "preset_starry" -> R.drawable.wallpaper_preset_starry
        "preset_gradient" -> R.drawable.wallpaper_preset_gradient
        "preset_dark" -> R.drawable.wallpaper_preset_dark
        "preset_forest" -> R.drawable.wallpaper_preset_forest
        "preset_warm" -> R.drawable.wallpaper_preset_warm
        "preset_cool" -> R.drawable.wallpaper_preset_cool
        "preset_shinchan_user" -> R.drawable.wallpaper_shinchan_user
        else -> R.drawable.wallpaper_kids_1
    }

    /** 获取自定义壁纸目录 */
    fun customWallpaperDir(context: Context): File {
        val dir = File(context.filesDir, "wallpapers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 列出已有的自定义壁纸文件 */
    fun listCustomWallpapers(context: Context): List<File> {
        val dir = customWallpaperDir(context)
        return (1..MAX_CUSTOM_WALLPAPERS)
            .map { File(dir, "custom_wallpaper_$it.jpg") }
            .filter { it.exists() }
    }

    /** 获取下一个可用的自定义壁纸文件名；超出限制返回 null */
    fun nextCustomSlot(context: Context): File? {
        val dir = customWallpaperDir(context)
        for (i in 1..MAX_CUSTOM_WALLPAPERS) {
            val f = File(dir, "custom_wallpaper_$i.jpg")
            if (!f.exists()) return f
        }
        return null
    }

    /** 删除自定义壁纸 */
    fun deleteCustomWallpaper(context: Context, file: File): Boolean {
        val deleted = file.delete()
        if (deleted) notifyChanged(context)
        return deleted
    }
}
