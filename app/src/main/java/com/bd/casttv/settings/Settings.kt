package com.bd.casttv.settings

import android.content.Context
import com.bd.casttv.dlna.DeviceIdentity

/**
 * Thin SharedPreferences wrapper for user-configurable renderer settings.
 */
class Settings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        val raw = prefs.getString(KEY_DEVICE_NAME, null)
        val normalized = normalizeDeviceName(raw)
        if (raw != normalized) {
            prefs.edit().putString(KEY_DEVICE_NAME, normalized).apply()
        }
    }

    /** Friendly name shown in the phone's cast device list. */
    var deviceName: String
        get() {
            val raw = prefs.getString(KEY_DEVICE_NAME, null)
            val normalized = normalizeDeviceName(raw)
            if (raw != normalized) {
                prefs.edit().putString(KEY_DEVICE_NAME, normalized).apply()
            }
            return normalized
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, normalizeDeviceName(value)).apply()

    /**
     * DLNA discovery name advertised to control points.
     *
     * Douyin has become strict about renderer device identity again: only changing
     * manufacturer/model while advertising a themed/custom friendlyName can make
     * the device disappear from Douyin's cast list. Keep the app UI name user
     * configurable, but always expose a Xiaomi-compatible DLNA friendlyName.
     *
     * v1.2.21+: 当抖音投屏适配开关开启时，DLNA 对外身份切换到用户所选设备名称组；
     * 关闭时回退到「用户自定义 deviceName + Xiaomi 兜底」。
     */
    val dlnaDeviceName: String
        get() = currentDlnaIdentity().friendlyName

    /** 完整 DLNA/UPnP 设备身份（friendlyName + manufacturer + model…），按开关状态动态计算。 */
    fun currentDlnaIdentity(): DeviceIdentity {
        return if (douyinCastEnabled) {
            // 优先从自定义组里查（含用户克隆/扩展的成员），再回退到内置组
            val group = DouyinDeviceGroups.findGroupIncludingCustom(appContext, douyinDeviceGroupId)
            group.memberAt(douyinDeviceMemberIndex)
        } else {
            // 关闭抖音适配：friendlyName 跟随用户自定义 deviceName，其余字段用 Xiaomi 兜底
            DeviceIdentity.XIAOMI_FALLBACK.copy(friendlyName = deviceName)
        }
    }

    /** 抖音投屏适配开关（默认关闭）。 */
    var douyinCastEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOUYIN_CAST_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DOUYIN_CAST_ENABLED, value).apply()

    /** 网页解析播放页面开关（默认关闭）。 */
    var webParseEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_PARSE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WEB_PARSE_ENABLED, value).apply()

    /** 页面布局模式：classic=经典多页翻页，launcher=启动台模式；默认 classic。 */
    var pageLayoutMode: String
        get() = normalizePageLayoutMode(prefs.getString(KEY_PAGE_LAYOUT_MODE, PAGE_LAYOUT_CLASSIC))
        set(value) = prefs.edit().putString(KEY_PAGE_LAYOUT_MODE, normalizePageLayoutMode(value)).apply()

    /** 推荐可见开关（默认关闭）：开启后其他用户可在定向推荐时看到本设备。 */
    var recommendationVisible: Boolean
        get() = prefs.getBoolean(KEY_RECOMMENDATION_VISIBLE, false)
        set(value) = prefs.edit().putBoolean(KEY_RECOMMENDATION_VISIBLE, value).apply()

    /** 当前使用的设备名称组 id（默认 xiaomi）。 */
    var douyinDeviceGroupId: String
        get() = prefs.getString(KEY_DOUYIN_GROUP_ID, DouyinDeviceGroups.DEFAULT_GROUP_ID)
            ?: DouyinDeviceGroups.DEFAULT_GROUP_ID
        set(value) = prefs.edit().putString(KEY_DOUYIN_GROUP_ID, value.ifBlank { DouyinDeviceGroups.DEFAULT_GROUP_ID }).apply()

    /** 组内成员轮换索引（用于同一名称组下切换 Redmi 电视 / 小米盒子 等备选）。 */
    var douyinDeviceMemberIndex: Int
        get() = prefs.getInt(KEY_DOUYIN_MEMBER_INDEX, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_DOUYIN_MEMBER_INDEX, value.coerceAtLeast(0)).apply()

    /**
     * 开启抖音投屏适配前，用户自定义的 deviceName 备份，用于关闭时无损恢复。
     * 空字符串代表尚未备份（首次开启前的初始状态）。
     */
    var deviceNameBackup: String
        get() = prefs.getString(KEY_DEVICE_NAME_BACKUP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME_BACKUP, value).apply()

    /** 抖音投屏页「播放记录生成」的累计秒数阈值（默认 3 秒）。 */
    var douyinHistoryThresholdSec: Int
        get() = prefs.getInt(KEY_DOUYIN_HISTORY_THRESHOLD, DEFAULT_DOUYIN_HISTORY_THRESHOLD_SEC)
            .coerceIn(3, 600)
        set(value) = prefs.edit().putInt(KEY_DOUYIN_HISTORY_THRESHOLD, value.coerceIn(3, 600)).apply()

    /** true = require a password before accepting a cast; false = password-free. */
    var passwordMode: Boolean
        get() = prefs.getBoolean(KEY_PASSWORD_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PASSWORD_MODE, value).apply()

    /** 4-8 digit numeric password used when [passwordMode] is on. */
    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    /** Mute the TV output when a cast starts. */
    var muteOnCast: Boolean
        get() = prefs.getBoolean(KEY_MUTE_ON_CAST, false)
        set(value) = prefs.edit().putBoolean(KEY_MUTE_ON_CAST, value).apply()

    /** Preferred quality hint: "auto" | "1080" | "4k". */
    var quality: String
        get() = prefs.getString(KEY_QUALITY, QUALITY_AUTO) ?: QUALITY_AUTO
        set(value) = prefs.edit().putString(KEY_QUALITY, value).apply()

    /** Whether the receiver should auto-start after boot. Default: off. */
    var bootAutoStart: Boolean
        get() = prefs.getBoolean(KEY_BOOT_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_AUTO_START, value).apply()

    /**
     * 视频渲染面是否使用 SurfaceView。默认 false（即使用 TextureView）。
     *
     * 背景：TV 端 SurfaceView 由厂商 Overlay 直出，`PixelCopy` 常常黑图 / 失败，
     * 导致收藏与历史缩略图无法生成。改用 TextureView 后可通过 `TextureView.getBitmap()`
     * 稳定截取当前画面，兼容性显著提升；但少数机型硬解性能会略降，因此保留开关，
     * 用户遇到卡顿/性能问题时可手动切回 SurfaceView。
     */
    var useSurfaceView: Boolean
        get() = prefs.getBoolean(KEY_USE_SURFACE_VIEW, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_SURFACE_VIEW, value).apply()

    /** Android TV DreamService poster wall style: track | waterfall | mosaic. */
    var screensaverStyle: String
        get() = normalizeScreensaverStyle(prefs.getString(KEY_SCREENSAVER_STYLE, SCREENSAVER_TRACK))
        set(value) = prefs.edit().putString(KEY_SCREENSAVER_STYLE, normalizeScreensaverStyle(value)).apply()

    /** Bottom indicator scale. */
    var indicatorScale: Float
        get() = prefs.getFloat(KEY_INDICATOR_SCALE, 1.0f).coerceIn(0.7f, 1.6f)
        set(value) = prefs.edit().putFloat(KEY_INDICATOR_SCALE, value.coerceIn(0.7f, 1.6f)).apply()

    /** TV 端 App 内“移动模式”：开启后展示页面两侧的切页按钮（仅影响 TV App）。 */
    var tvMobileModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_TV_MOBILE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_TV_MOBILE_MODE, value).apply()

    /** 手机端网页使用“移动端模式”展示（独立开关，不与 TV 端 App 的移动模式绑定）。 */
    var phoneMobileModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PHONE_MOBILE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PHONE_MOBILE_MODE, value).apply()

    /** Whether global wallpaper layer is enabled. */
    var wallpaperEnabled: Boolean
        get() = prefs.getBoolean(KEY_WALLPAPER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WALLPAPER_ENABLED, value).apply()

    /** Preset or custom wallpaper source id. */
    var wallpaperSource: String
        get() = prefs.getString(KEY_WALLPAPER_SOURCE, WALLPAPER_PRESET_DEFAULT) ?: WALLPAPER_PRESET_DEFAULT
        set(value) = prefs.edit().putString(KEY_WALLPAPER_SOURCE, value.ifBlank { WALLPAPER_PRESET_DEFAULT }).apply()

    /** Wallpaper dim percent, 0-80. */
    var wallpaperDim: Int
        get() = prefs.getInt(KEY_WALLPAPER_DIM, 35).coerceIn(0, 80)
        set(value) = prefs.edit().putInt(KEY_WALLPAPER_DIM, value.coerceIn(0, 80)).apply()

    /** Wallpaper blur radius placeholder, 0-30. */
    var wallpaperBlur: Int
        get() = prefs.getInt(KEY_WALLPAPER_BLUR, 0).coerceIn(0, 30)
        set(value) = prefs.edit().putInt(KEY_WALLPAPER_BLUR, value.coerceIn(0, 30)).apply()

    /** Background type: "image" or "color". */
    var wallpaperBgType: String
        get() = prefs.getString(KEY_WALLPAPER_BG_TYPE, BG_TYPE_IMAGE) ?: BG_TYPE_IMAGE
        set(value) = prefs.edit().putString(KEY_WALLPAPER_BG_TYPE, value).apply()

    /** Color mode: "solid" or "gradient". */
    var wallpaperColorMode: String
        get() = prefs.getString(KEY_WALLPAPER_COLOR_MODE, COLOR_MODE_SOLID) ?: COLOR_MODE_SOLID
        set(value) = prefs.edit().putString(KEY_WALLPAPER_COLOR_MODE, value).apply()

    /** Solid background color hex (e.g. "#1A1A2E"). */
    var wallpaperSolidColor: String
        get() = prefs.getString(KEY_WALLPAPER_SOLID_COLOR, "#1A1A2E") ?: "#1A1A2E"
        set(value) = prefs.edit().putString(KEY_WALLPAPER_SOLID_COLOR, value).apply()

    /** Gradient color A hex. */
    var wallpaperGradientColorA: String
        get() = prefs.getString(KEY_WALLPAPER_GRADIENT_A, "#1A1A2E") ?: "#1A1A2E"
        set(value) = prefs.edit().putString(KEY_WALLPAPER_GRADIENT_A, value).apply()

    /** Gradient color B hex. */
    var wallpaperGradientColorB: String
        get() = prefs.getString(KEY_WALLPAPER_GRADIENT_B, "#16213E") ?: "#16213E"
        set(value) = prefs.edit().putString(KEY_WALLPAPER_GRADIENT_B, value).apply()

    /** Page content panel background switch. */
    var pageContentPanelEnabled: Boolean
        get() = prefs.getBoolean(KEY_PAGE_CONTENT_PANEL_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PAGE_CONTENT_PANEL_ENABLED, value).apply()

    /** Page content panel gradient start color. */
    var pageContentPanelGradientA: String
        get() = prefs.getString(KEY_PAGE_CONTENT_PANEL_GRADIENT_A, "#0048BA") ?: "#0048BA"
        set(value) = prefs.edit().putString(KEY_PAGE_CONTENT_PANEL_GRADIENT_A, value).apply()

    /** Page content panel gradient end color. */
    var pageContentPanelGradientB: String
        get() = prefs.getString(KEY_PAGE_CONTENT_PANEL_GRADIENT_B, "#5BB5FF") ?: "#5BB5FF"
        set(value) = prefs.edit().putString(KEY_PAGE_CONTENT_PANEL_GRADIENT_B, value).apply()

    /** Page content panel transparency percent, 0=opaque, 100=fully transparent. */
    var pageContentPanelTransparency: Int
        get() = prefs.getInt(KEY_PAGE_CONTENT_PANEL_TRANSPARENCY, 10).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_PAGE_CONTENT_PANEL_TRANSPARENCY, value.coerceIn(0, 100)).apply()

    /** Whether user has explicitly customized page content panel colors. */
    var pageContentPanelCustomized: Boolean
        get() = prefs.getBoolean(KEY_PAGE_CONTENT_PANEL_CUSTOMIZED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAGE_CONTENT_PANEL_CUSTOMIZED, value).apply()

    /** Ordered page ids for the new full-screen page architecture. */
    var pageOrder: List<String>
        get() = (prefs.getString(KEY_PAGE_ORDER, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }) ?: DEFAULT_PAGE_ORDER
        set(value) = prefs.edit().putString(KEY_PAGE_ORDER, value.joinToString(",")).apply()

    /** Disabled page ids for the new full-screen page architecture. */
    var disabledPageIds: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED_PAGE_IDS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_DISABLED_PAGE_IDS, value).apply()

    companion object {
        private const val PREFS = "casttv_settings"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_PASSWORD_MODE = "password_mode"
        private const val KEY_PASSWORD = "password"
        private const val KEY_MUTE_ON_CAST = "mute_on_cast"
        private const val KEY_QUALITY = "quality"
        private const val KEY_BOOT_AUTO_START = "boot_auto_start"
        private const val KEY_USE_SURFACE_VIEW = "use_surface_view"
        private const val KEY_SCREENSAVER_STYLE = "screensaver_style"
        private const val KEY_INDICATOR_SCALE = "indicator_scale"
        private const val KEY_TV_MOBILE_MODE = "tv_mobile_mode"
        private const val KEY_PHONE_MOBILE_MODE = "phone_mobile_mode"
        private const val KEY_WALLPAPER_ENABLED = "wallpaper_enabled"
        private const val KEY_WALLPAPER_SOURCE = "wallpaper_source"
        private const val KEY_WALLPAPER_DIM = "wallpaper_dim"
        private const val KEY_WALLPAPER_BLUR = "wallpaper_blur"
        private const val KEY_WALLPAPER_BG_TYPE = "wallpaper_bg_type"
        private const val KEY_WALLPAPER_COLOR_MODE = "wallpaper_color_mode"
        private const val KEY_WALLPAPER_SOLID_COLOR = "wallpaper_solid_color"
        private const val KEY_WALLPAPER_GRADIENT_A = "wallpaper_gradient_a"
        private const val KEY_WALLPAPER_GRADIENT_B = "wallpaper_gradient_b"
        private const val KEY_PAGE_CONTENT_PANEL_ENABLED = "page_content_panel_enabled"
        private const val KEY_PAGE_CONTENT_PANEL_GRADIENT_A = "page_content_panel_gradient_a"
        private const val KEY_PAGE_CONTENT_PANEL_GRADIENT_B = "page_content_panel_gradient_b"
        private const val KEY_PAGE_CONTENT_PANEL_TRANSPARENCY = "page_content_panel_transparency"
        private const val KEY_PAGE_CONTENT_PANEL_CUSTOMIZED = "page_content_panel_customized"
        private const val KEY_PAGE_ORDER = "page_order"
        private const val KEY_DISABLED_PAGE_IDS = "disabled_page_ids"
        private const val KEY_DOUYIN_CAST_ENABLED = "douyin_cast_enabled"
        private const val KEY_WEB_PARSE_ENABLED = "web_parse_enabled"
        private const val KEY_RECOMMENDATION_VISIBLE = "recommendation_visible"
        private const val KEY_DOUYIN_GROUP_ID = "douyin_group_id"
        private const val KEY_DOUYIN_MEMBER_INDEX = "douyin_member_index"
        private const val KEY_DEVICE_NAME_BACKUP = "device_name_backup"
        private const val KEY_DOUYIN_HISTORY_THRESHOLD = "douyin_history_threshold_sec"
        private const val KEY_PAGE_LAYOUT_MODE = "page_layout_mode"
        const val DEFAULT_DOUYIN_HISTORY_THRESHOLD_SEC = 3
        const val PAGE_ID_DOUYIN_CAST = "douyin_cast"
        const val PAGE_ID_WEB_PARSE = "web_parse"

        fun normalizeDeviceName(value: String?): String {
            return value
                ?.trim()
                ?.replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
                ?.take(40)
                ?.ifBlank { DEFAULT_DEVICE_NAME }
                ?: DEFAULT_DEVICE_NAME
        }

        fun normalizeScreensaverStyle(value: String?): String = when (value) {
            SCREENSAVER_WATERFALL -> SCREENSAVER_WATERFALL
            SCREENSAVER_MOSAIC -> SCREENSAVER_MOSAIC
            else -> SCREENSAVER_TRACK
        }
 
        fun normalizePageLayoutMode(value: String?): String = when (value) {
            PAGE_LAYOUT_LAUNCHER -> PAGE_LAYOUT_LAUNCHER
            else -> PAGE_LAYOUT_CLASSIC
        }
 
        const val DEFAULT_DEVICE_NAME = "小米电视"
        const val QUALITY_AUTO = "auto"
        const val QUALITY_1080 = "1080"
        const val QUALITY_4K = "4k"
        const val SCREENSAVER_TRACK = "track"
        const val SCREENSAVER_WATERFALL = "waterfall"
        const val SCREENSAVER_MOSAIC = "mosaic"
        const val WALLPAPER_PRESET_DEFAULT = "preset_default"
        const val WALLPAPER_PRESET_SHINCHAN_USER = "preset_shinchan_user"
        const val BG_TYPE_IMAGE = "image"
        const val BG_TYPE_COLOR = "color"
        const val COLOR_MODE_SOLID = "solid"
        const val COLOR_MODE_GRADIENT = "gradient"
        const val PAGE_LAYOUT_CLASSIC = "classic"
        const val PAGE_LAYOUT_LAUNCHER = "launcher"
        val DEFAULT_PAGE_ORDER = listOf(
            "home",
            PAGE_ID_DOUYIN_CAST,
            "favorites",
            PAGE_ID_WEB_PARSE,
            "customtabs",
            "phonehub",
            "history",
            "diagnostics",
            "help",
            "settings"
        )
    }
}
