package com.bd.casttv.ui.framework.pages

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.File
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import com.bd.casttv.R
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.screensaver.PosterWallActivity
import com.bd.casttv.settings.DeviceNames
import com.bd.casttv.settings.Settings
import com.bd.casttv.sync.GiteeApi
import com.bd.casttv.sync.VisibleUsersStore
import com.bd.casttv.ui.DockManagePage
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.BoundaryFocusHandler
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.SettingsChangeBus
import com.bd.casttv.ui.framework.WallpaperSettingsDialog
import com.bd.casttv.ui.ClippedImageView
import com.bd.casttv.update.UpdateChecker
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.util.Thumbnails

class SettingsPage(context: Context) : BasePage(context) {
    override val pageId = "settings"
    override val pageTitle = "设置"
    override val pageIconRes = R.drawable.ic_settings_tv
    override val enablePageScroll: Boolean = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true
    override val pageStickerRes: Int get() = R.drawable.sticker_masao
    private val settings = Settings(context)
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val focusables = mutableListOf<View>()
    private var pendingFocusIndex: Int = -1

    init {
        val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false; clipChildren = false; overScrollMode = View.OVER_SCROLL_NEVER }
        // 页头由 BasePage 统一提供，页面内容顶部内边距缩小到 dp(20)，避免与页头间隔过大。
        list.setPadding(dp(86), dp(20), dp(86), dp(40))
        scroll.addView(list)
        contentContainer.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        build()
    }

    private fun build() {
        group("通用")
        row("设备名称", settings.deviceName) { showDeviceNameEditDialog() }
        // === 抖音投屏适配 ===
        switchRow(
            "抖音投屏适配",
            settings.douyinCastEnabled,
            summary = if (settings.douyinCastEnabled)
                "已开启，DLNA 对外名：${settings.dlnaDeviceName}（手机抖音选此名投屏）"
            else "开启后仅切换 DLNA 对外名以兼容抖音过滤；App 内自定义设备名不变"
        ) { on -> onDouyinCastToggle(on) }
        if (settings.douyinCastEnabled) {
            val group = com.bd.casttv.settings.DouyinDeviceGroups.findGroup(settings.douyinDeviceGroupId)
            row("抖音兼容设备名称组", group.label + " · " + group.memberAt(settings.douyinDeviceMemberIndex).friendlyName) {
                showDouyinDeviceGroupPicker()
            }
        }
        // === 抖音投屏适配 END ===
        switchRow(
            "网页解析播放",
            settings.webParseEnabled,
            summary = if (settings.webParseEnabled) "已开启，可在收藏页后进入网页解析播放页" else "开启后在收藏页后新增解析页面，手机端可粘贴影片详情页网址"
        ) { on ->
            settings.webParseEnabled = on
            SettingsChangeBus.notifyChanged()
            refresh()
        }
        switchRow("投屏密码模式", settings.passwordMode) { settings.passwordMode = it }
        if (settings.passwordMode) {
            row("投屏密码", maskPassword(settings.password).ifBlank { "未设置，点击设置 4-8 位数字密码" }) { showPasswordEditDialog() }
        }
        switchRow("投屏静音启动", settings.muteOnCast) { settings.muteOnCast = it }
        switchRow("开机自启", settings.bootAutoStart) { isOn ->
            settings.bootAutoStart = isOn
            if (isOn) openAutoStartPermissionPage()
        }
        group("播放")
        switchRow("播放内核切换", settings.useSurfaceView, "开启=SurfaceView，关闭=TextureView") { settings.useSurfaceView = it }
        group("外观")
        row("主题风格切换", themeSummary()) { showThemeStyleDialog() }
        row("壁纸设置", "壁纸来源、模糊度、遮罩浓度、背景色") { showWallpaperSettingsDialog() }
        row("页面排序管理", "拖拽简化为上移/下移 + 显示隐藏") { showPageOrderDialog() }
        row(
            "页面布局模式",
            if (settings.pageLayoutMode == Settings.PAGE_LAYOUT_LAUNCHER) "启动台模式" else "经典模式"
        ) { showPageLayoutModeDialog() }
        row("指示栏缩放", "${settings.indicatorScale}x") { showIndicatorScaleDialog() }
        row("屏保设置", "屏保风格、海报墙预览") { showScreensaverSettingsDialog() }
        group("自定义")
        row("自定义 Tab 管理", "打开管理弹窗") { showDockManage() }
        switchRow(
            "推荐可见",
            settings.recommendationVisible,
            summary = "开启后，其他用户在推荐视频时可以看到你，并向你推荐内容"
        ) { on -> onRecommendationVisibleToggle(on) }
        row("页面内容区域背景设置", "自定义页面内容区域背景色、支持渐变") { showPageContentPanelDialog() }
        group("高级 & 关于")
        row("缓存一键清理", "可同时清空历史缩略图") { showClearCacheDialog() }
        row("检查更新", "当前为新架构入口") { checkUpdate() }
        bindBoundary()
    }

    private fun openAutoStartPermissionPage() {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val candidateGroups = listOf(
            setOf("xiaomi", "redmi", "miui") to listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"))
            ),
            setOf("huawei", "honor") to listOf(
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
                Intent().setComponent(ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
            ),
            setOf("oppo", "oneplus", "realme") to listOf(
                Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")),
                Intent().setComponent(ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
            ),
            setOf("vivo", "iqoo") to listOf(
                Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
            ),
            setOf("meizu") to listOf(
                Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")),
                Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"))
            )
        )
        val orderedCandidates = candidateGroups
            .sortedBy { (vendors, _) -> if (vendors.any { manufacturer.contains(it) }) 0 else 1 }
            .flatMap { it.second }

        if (orderedCandidates.any { startResolvedActivity(it) }) return

        toast("请在系统设置中允许本应用自启动或后台运行")
        startResolvedActivity(Intent(AndroidSettings.ACTION_SETTINGS))
    }

    private fun startResolvedActivity(intent: Intent): Boolean {
        val runnableIntent = Intent(intent).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runnableIntent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(runnableIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    // 分组标题：小号字 + 半透明暖黄 + 大上间距，现代简约风格不抢视觉焦点
    private fun group(title: String) {
        list.addView(TextView(context).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(180, 245, 196, 81))
            setPadding(dp(4), dp(24), 0, dp(6))
            includeFontPadding = false
            letterSpacing = 0.08f
        })
    }
    private fun row(title: String, summary: String = "", click: () -> Unit) {
        val v = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            clipChildren = false
            clipToPadding = false
            background = card(false)
            // 水平内边距微增到 20dp，给标题和摘要更多呼吸空间
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener {
                pendingFocusIndex = focusables.indexOf(this)
                click()
            }
            setOnFocusChangeListener { view, has ->
                view.background = card(has)
                FocusFxHelper.applyFocusFxState(view, has, cornerRadiusDp = 16)
            }
        }
        v.addView(settingTitle(title))
        if (summary.isNotBlank()) v.addView(settingSummary(summary), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        // 行高 68dp + 间距 10dp，更舒展的呼吸感
        list.addView(v, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(68)).apply { bottomMargin = dp(10) })
        focusables.add(v)
    }
    private fun switchRow(title: String, checked: Boolean, summary: String = "", on: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            isSelected = checked
            clipChildren = false
            clipToPadding = false
            background = card(focused = false)
            setPadding(dp(20), 0, dp(20), 0)
            setOnFocusChangeListener { view, has ->
                view.background = card(focused = has)
                FocusFxHelper.applyFocusFxState(view, has, cornerRadiusDp = 16)
            }
        }
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(settingTitle(title))
            if (summary.isNotBlank()) addView(settingSummary(summary), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        }
        val switch = SwitchCompat(context).apply {
            isFocusable = false
            isClickable = false
            isChecked = checked
            showText = false
            textOn = ""
            textOff = ""
            splitTrack = false
            setThumbResource(R.drawable.switch_ios_thumb)
            setTrackResource(R.drawable.switch_ios_track)
            minWidth = dp(51)
            minimumWidth = dp(51)
            minHeight = dp(31)
            setOnCheckedChangeListener { _, isOn ->
                row.isSelected = isOn
                row.background = card(focused = row.hasFocus())
                on(isOn)
                toast(if (isOn) "已开启" else "已关闭")
                // 局部通知，避免整页 refresh 重建导致闪屏与焦点跳失
                SettingsChangeBus.notifyChanged()
            }
        }
        row.setOnClickListener { switch.isChecked = !switch.isChecked }
        row.addView(text, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        // SwitchCompat(iOS 风格 thumb/track) 实际绘制宽度可能超过 51dp，固定宽度会导致左侧被截断
        row.addView(switch, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(31)))
        // 行高 68dp + 间距 10dp，与 row() 保持一致
        list.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(68)).apply { bottomMargin = dp(10) })
        focusables.add(row)
    }
    private fun choiceRow(title: String, current: String, choices: List<Pair<String,String>>, on: (String)->Unit) { row(title, choices.firstOrNull{it.first==current}?.second ?: current) { dialog().setTitle(title).setItems(choices.map{it.second}.toTypedArray()) { d, which -> on(choices[which].first); d.dismiss(); refresh() }.show() } }
    private fun seekRow(title: String, value: Int, min: Int, max: Int, step: Int, on: (Int)->Unit) { row(title, if (title.contains("缩放")) "${value/10f}x" else "$value") { val bar=SeekBar(context).apply{ this.max=(max-min)/step; progress=(value-min)/step }; dialog().setTitle(title).setView(bar).setPositiveButton("确定"){_,_-> on(min+bar.progress*step); refresh() }.show() } }
    // 标题：字号略降至 17f，粗体保留层级；色彩改用略带暖色的米白，与暖黄主题呼应且更柔和
    private fun settingTitle(title: String) = TextView(context).apply {
        text = title
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.argb(238, 250, 248, 244))
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        letterSpacing = 0.02f
    }
    // 摘要：小一号 13f，冷调灰白降低至 150 不透明度，作为辅助信息不抢视觉焦点，与标题形成层次
    private fun settingSummary(summary: String) = TextView(context).apply {
        text = summary
        textSize = 13f
        typeface = Typeface.DEFAULT
        setTextColor(Color.argb(150, 218, 220, 226))
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        letterSpacing = 0.01f
    }
    private fun refresh() {
        val idx = pendingFocusIndex
        list.removeAllViews()
        focusables.clear()
        build()
        SettingsChangeBus.notifyChanged()
        if (idx >= 0) {
            list.post {
                focusables.getOrNull(idx)?.requestFocus()
                pendingFocusIndex = -1
            }
        }
    }

    private fun restorePendingRowFocus() {
        val idx = pendingFocusIndex
        if (idx >= 0) {
            list.post {
                focusables.getOrNull(idx)?.requestFocus()
                pendingFocusIndex = -1
            }
        }
    }
    private fun showDeviceNameEditDialog() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = context.getString(R.string.settings_device_name_dialog_title)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        panel.addView(TextView(context).apply {
            text = context.getString(R.string.settings_device_name_summary)
            textSize = 14f
            setTextColor(Color.argb(220, 255, 255, 255))
            setLineSpacing(dp(2).toFloat(), 1.0f)
        })

        val input = EditText(context).apply {
            setText(settings.deviceName.ifBlank { Settings.DEFAULT_DEVICE_NAME })
            setSelection(text?.length ?: 0)
            hint = context.getString(R.string.settings_device_name)
            textSize = 16f
            setSingleLine(true)
            setTextColor(Color.argb(235, 245, 245, 245))
            setHintTextColor(Color.argb(150, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(52, 32, 34, 40))
                setStroke(dp(1), Color.argb(170, 210, 214, 222))
            }
            setPadding(dp(12), 0, dp(12), 0)
        }
        panel.addView(input, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })

        val randomBtn = dialogButton(context.getString(R.string.settings_random_name)) {
            val next = DeviceNames.random(input.text.toString())
            input.setText(next)
            input.setSelection(next.length)
        }
        panel.addView(randomBtn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(dp(560), LayoutParams.WRAP_CONTENT)
            input.requestFocus()
        }

        val cancelBtn = dialogButton("取消") {
            dialog.dismiss()
            restorePendingRowFocus()
        }
        val confirmBtn = dialogButton("确定") {
            settings.deviceName = input.text.toString().trim().ifBlank { Settings.DEFAULT_DEVICE_NAME }
            toast("设备名称已更新")
            // 重启 DLNA 服务让新名称生效（关闭抖音适配时 dlnaDeviceName 跟随 deviceName）
            triggerDlnaIdentityRestart()
            SettingsChangeBus.notifyChanged()
            refresh()
            dialog.dismiss()
        }
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(confirmBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })

        dialog.show()
    }
    private fun showPasswordEditDialog() { val input=EditText(context).apply{ setText(settings.password); setSelection(text.length); inputType = InputType.TYPE_CLASS_NUMBER; hint = "请输入 4-8 位数字密码" }; dialog().setTitle("投屏密码").setView(input).setPositiveButton("保存") { _, _ -> settings.password = input.text.toString().trim(); toast("投屏密码已更新"); refresh() }.setNegativeButton("取消") { d, _ -> d.dismiss(); restorePendingRowFocus() }.show() }
    private fun showPageOrderDialog() {
        val allIds = Settings.DEFAULT_PAGE_ORDER.toMutableList()
        val order = settings.pageOrder.toMutableList().apply { allIds.filter { it !in this }.forEach { add(it) } }
        val disabled = settings.disabledPageIds.toMutableSet()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            isFocusable = false
            setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        // 顶部标题栏（圆形贴纸 + 标题）
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "页面排序管理"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        panel.addView(TextView(context).apply {
            text = "调整页面顺序或切换显示/隐藏，点击「显示」列图标即可切换。"
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = true
            clipToPadding = true
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(listContainer)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(360)))

        fun pageLabel(id: String): String = when (id) {
            "home" -> "首页"
            "favorites" -> "收藏"
            "customtabs" -> "自定义 Tab"
            "phonehub" -> "连接手机"
            "history" -> "历史记录"
            "diagnostics" -> "网络诊断"
            "help" -> "帮助"
            "settings" -> "设置"
            else -> id
        }

        var cancelBtn: TextView? = null
        var doneBtn: TextView? = null
        var pendingFocus: Pair<Int, String>? = 0 to "visibility"

        fun focusBottomAction(target: String): Boolean {
            val view = if (target == "done") doneBtn else cancelBtn
            return view?.requestFocus() == true
        }

        fun refreshList(restoreFocus: Boolean = true) {
            listContainer.removeAllViews()
            val rowButtons = mutableListOf<Map<String, TextView>>()

            order.forEachIndexed { idx, id ->
                val rowV = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    clipChildren = false
                    clipToPadding = false
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.argb(38, 255, 255, 255))
                        setStroke(dp(1), Color.argb(140, 255, 255, 255))
                    }
                    setPadding(dp(14), dp(8), dp(10), dp(8))
                }
                val seq = TextView(context).apply {
                    text = (idx + 1).toString().padStart(2, '0')
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(30, 26, 18))
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(WARM) }
                }
                val name = TextView(context).apply {
                    text = pageLabel(id)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                }
                val hidden = id in disabled
                val buttons = linkedMapOf<String, TextView>()

                fun requestNeighbor(targetIndex: Int, targetType: String): Boolean {
                    if (targetIndex !in rowButtons.indices) return false
                    val candidate = rowButtons[targetIndex][targetType]
                        ?: rowButtons[targetIndex]["visibility"]
                        ?: rowButtons[targetIndex]["up"]
                        ?: rowButtons[targetIndex]["down"]
                    return candidate?.requestFocus() == true
                }

                fun rowActionButton(type: String, label: String, enabled: Boolean, selected: Boolean, onClick: () -> Unit): TextView {
                    val lightText = Color.parseColor("#F1F1F5")
                    val lightBorder = Color.parseColor("#66FFFFFF")
                    val disabledText = Color.argb(110, 255, 255, 255)
                    val disabledBorder = Color.argb(90, 255, 255, 255)
                    return TextView(context).apply {
                        text = label
                        textSize = 13f
                        gravity = Gravity.CENTER
                        typeface = Typeface.DEFAULT_BOLD
                        isFocusable = enabled
                        isFocusableInTouchMode = enabled
                        isClickable = enabled
                        fun refresh(focused: Boolean) {
                            val textColor = when {
                                !enabled -> disabledText
                                focused -> WARM
                                selected -> WARM
                                else -> lightText
                            }
                            val borderColor = when {
                                !enabled -> disabledBorder
                                focused -> WARM
                                else -> lightBorder
                            }
                            setTextColor(textColor)
                            background = GradientDrawable().apply {
                                cornerRadius = dp(8).toFloat()
                                setColor(Color.argb(51, 27, 31, 38))
                                setStroke(dp(if (focused) 2 else 1), borderColor)
                            }
                        }
                        refresh(false)
                        setOnFocusChangeListener { view, hasFocus ->
                            refresh(hasFocus)
                            FocusFxHelper.applyFocusFxState(view, hasFocus, cornerRadiusDp = 8)
                        }
                        if (enabled) {
                            setOnClickListener { onClick() }
                            setOnKeyListener { v, keyCode, event ->
                                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        val leftType = when (type) {
                                            "up" -> "visibility"
                                            "down" -> if (buttons["up"]?.isFocusable == true) "up" else "visibility"
                                            else -> null
                                        }
                                        if (leftType != null) {
                                            (buttons[leftType]?.requestFocus() == true) || run {
                                                BoundaryFocusHandler.shake(v)
                                                true
                                            }
                                        } else {
                                            BoundaryFocusHandler.shake(v)
                                            true
                                        }
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        val rightType = when (type) {
                                            "visibility" -> if (buttons["up"]?.isFocusable == true) "up" else if (buttons["down"]?.isFocusable == true) "down" else null
                                            "up" -> if (buttons["down"]?.isFocusable == true) "down" else null
                                            else -> null
                                        }
                                        if (rightType != null) {
                                            (buttons[rightType]?.requestFocus() == true) || run {
                                                BoundaryFocusHandler.shake(v)
                                                true
                                            }
                                        } else {
                                            BoundaryFocusHandler.shake(v)
                                            true
                                        }
                                    }
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        when {
                                            idx > 0 && requestNeighbor(idx - 1, type) -> true
                                            idx == 0 -> {
                                                BoundaryFocusHandler.shake(v)
                                                true
                                            }
                                            else -> {
                                                BoundaryFocusHandler.shake(v)
                                                true
                                            }
                                        }
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        when {
                                            idx < order.lastIndex && requestNeighbor(idx + 1, type) -> true
                                            idx == order.lastIndex -> {
                                                val targetAction = if (type == "down") "done" else "cancel"
                                                focusBottomAction(targetAction) || run {
                                                    BoundaryFocusHandler.shake(v)
                                                    true
                                                }
                                            }
                                            else -> {
                                                BoundaryFocusHandler.shake(v)
                                                true
                                            }
                                        }
                                    }
                                    else -> false
                                }
                            }
                        }
                    }
                }

                val visibilityBtn = rowActionButton("visibility", if (hidden) "☐ 隐藏" else "☑ 显示", enabled = true, selected = !hidden) {
                    pendingFocus = idx to "visibility"
                    if (!disabled.add(id)) disabled.remove(id)
                    refreshList()
                }
                val upBtn = rowActionButton("up", "↑", enabled = idx > 0, selected = false) {
                    if (idx > 0) {
                        pendingFocus = (idx - 1) to "up"
                        val t = order[idx]
                        order[idx] = order[idx - 1]
                        order[idx - 1] = t
                        refreshList()
                    }
                }
                val downBtn = rowActionButton("down", "↓", enabled = idx < order.size - 1, selected = false) {
                    if (idx < order.size - 1) {
                        pendingFocus = (idx + 1) to "down"
                        val t = order[idx]
                        order[idx] = order[idx + 1]
                        order[idx + 1] = t
                        refreshList()
                    }
                }
                buttons["visibility"] = visibilityBtn
                buttons["up"] = upBtn
                buttons["down"] = downBtn
                rowButtons += buttons

                rowV.addView(seq, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(10) })
                rowV.addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                rowV.addView(visibilityBtn, LinearLayout.LayoutParams(dp(76), dp(32)).apply { rightMargin = dp(8) })
                rowV.addView(upBtn, LinearLayout.LayoutParams(dp(38), dp(32)).apply { rightMargin = dp(6) })
                rowV.addView(downBtn, LinearLayout.LayoutParams(dp(38), dp(32)))
                val lp = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(8)
                listContainer.addView(rowV, lp)
            }

            if (restoreFocus) {
                listContainer.post {
                    val (targetIndex, targetType) = pendingFocus ?: (0 to "visibility")
                    val clampedIndex = targetIndex.coerceIn(0, rowButtons.lastIndex.coerceAtLeast(0))
                    val restored = if (rowButtons.isNotEmpty()) {
                        rowButtons.getOrNull(clampedIndex)?.get(targetType)?.takeIf { it.isFocusable }?.requestFocus() == true
                            || rowButtons.getOrNull(clampedIndex)?.get("visibility")?.requestFocus() == true
                    } else false
                    if (!restored) {
                        cancelBtn?.requestFocus()
                    }
                }
            }
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)) }
        cancelBtn = dialogButton("取消") {
            dialog.dismiss()
            restorePendingRowFocus()
        }
        doneBtn = dialogButton("完成") {
            settings.pageOrder = order
            settings.disabledPageIds = disabled
            SettingsChangeBus.notifyChanged()
            refresh()
            dialog.dismiss()
        }
        cancelBtn.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    BoundaryFocusHandler.shake(v)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> doneBtn.requestFocus()
                KeyEvent.KEYCODE_DPAD_UP -> {
                    pendingFocus = order.lastIndex.coerceAtLeast(0) to "visibility"
                    refreshList()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    BoundaryFocusHandler.shake(v)
                    true
                }
                else -> false
            }
        }
        doneBtn.setOnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> cancelBtn.requestFocus()
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    BoundaryFocusHandler.shake(v)
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    pendingFocus = order.lastIndex.coerceAtLeast(0) to "down"
                    refreshList()
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    BoundaryFocusHandler.shake(v)
                    true
                }
                else -> false
            }
        }
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(doneBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }
        panel.addView(actionRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        refreshList(restoreFocus = false)
        dialog.show()
        dialog.window?.setLayout(dp(560), LayoutParams.WRAP_CONTENT)
        listContainer.post {
            pendingFocus = 0 to "visibility"
            refreshList()
        }
    }

    private fun showIndicatorScaleDialog() {
        val min = 7; val max = 16; val step = 1
        val current = (settings.indicatorScale * 10).toInt().coerceIn(min, max)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "指示栏缩放"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        panel.addView(TextView(context).apply {
            text = "调整页面底部指示栏的显示大小，用于控制底部页码圆点/当前页提示的整体缩放，可用范围 0.7x – 1.6x。"
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })

        val valueLabel = TextView(context).apply {
            text = "${current / 10f}x"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(WARM)
        }
        panel.addView(valueLabel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })

        val bar = SeekBar(context).apply {
            this.max = (max - min) / step
            progress = (current - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    valueLabel.text = "${(min + p * step) / 10f}x"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        panel.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)) }
        val cancelBtn = dialogButton("取消") {
            dialog.dismiss()
            restorePendingRowFocus()
        }
        val okBtn = dialogButton("确定") {
            val v = min + bar.progress * step
            settings.indicatorScale = v / 10f
            SettingsChangeBus.notifyChanged()
            refresh()
            dialog.dismiss()
        }
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(okBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }
        panel.addView(actionRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })
        dialog.show()
        dialog.window?.setLayout(dp(500), LayoutParams.WRAP_CONTENT)
    }

    private fun dialogButton(label: String, click: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        fun refresh(focused: Boolean) {
            val selected = isSelected
            setTextColor(if (selected) WARM else Color.argb(235, 245, 245, 245))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(170, 210, 214, 222))
                setColor(Color.argb(52, 32, 34, 40))
            }
        }
        refresh(false)
        setOnFocusChangeListener { v, has ->
            refresh(has)
            FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
        }
        setOnClickListener { click() }
    }
    private fun showScreensaverSettingsDialog() {
        val styles = listOf(
            Settings.SCREENSAVER_TRACK to "横向轨道流（默认）",
            Settings.SCREENSAVER_WATERFALL to "多列瀑布流",
            Settings.SCREENSAVER_MOSAIC to "随机砖块网格"
        )
        val currentIndex = styles.indexOfFirst { it.first == settings.screensaverStyle }.coerceAtLeast(0)
        var selectedIndex = currentIndex
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "屏保设置"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        panel.addView(TextView(context).apply {
            text = "屏保风格"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.argb(235, 245, 245, 245))
        })
        val radioGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            styles.forEachIndexed { index, pair ->
                addView(RadioButton(context).apply {
                    text = pair.second
                    textSize = 15f
                    setTextColor(Color.argb(235, 245, 245, 245))
                    id = 1000 + index
                    isChecked = index == currentIndex
                })
            }
            setOnCheckedChangeListener { _, checkedId -> selectedIndex = checkedId - 1000 }
        }
        panel.addView(radioGroup)
        val previewButton = dialogButton("立即预览海报墙") { context.startActivity(Intent(context, PosterWallActivity::class.java)) }
        panel.addView(previewButton, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)) }
        val cancelButton = dialogButton("取消") {
            dialog.dismiss()
            restorePendingRowFocus()
        }
        val applyButton = dialogButton("应用") {
            settings.screensaverStyle = styles[selectedIndex.coerceIn(styles.indices)].first
            dialog.dismiss()
            refresh()
        }
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(cancelButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(applyButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        dialog.show()
        radioGroup.getChildAt(currentIndex)?.requestFocus()
    }

    /**
     * 页面布局模式弹窗。
     * 遵循 AGENT.md 弹窗规范：Theme_CastTV_Dialog + 渐变面板背景（随主题配色联动）+ 圆形贴纸 +
     * 4dp 内容内边距 + 统一 dialogButton；单选项选中态高亮暖黄。
     */
    private fun showPageLayoutModeDialog() {
        val options = listOf(
            Settings.PAGE_LAYOUT_CLASSIC to ("经典模式" to "多页翻页布局"),
            Settings.PAGE_LAYOUT_LAUNCHER to ("启动台模式" to "启动台聚合布局")
        )
        var selected = settings.pageLayoutMode

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 弹窗内容区 4dp 内边距（叠加视觉留白），符合弹窗设计规范。
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }

        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "页面布局模式"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        panel.addView(TextView(context).apply {
            text = "选择主页整体布局形态，切换后需重启 App 生效。"
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        // 单选项容器：选中项文字高亮暖黄，聚焦时边框变暖黄。
        val optionViews = mutableMapOf<String, TextView>()
        fun paintOption(tv: TextView, mode: String, focused: Boolean) {
            val isSel = mode == selected
            tv.setTextColor(if (isSel) WARM else Color.argb(235, 245, 245, 245))
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(if (isSel) 60 else 40, 32, 34, 40))
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(170, 210, 214, 222))
            }
        }
        fun refreshOptions(focusedMode: String?) {
            optionViews.forEach { (mode, tv) -> paintOption(tv, mode, mode == focusedMode) }
        }

        options.forEach { (mode, labelPair) ->
            val optionRow = TextView(context).apply {
                text = "${labelPair.first}    ${labelPair.second}"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnFocusChangeListener { v, has ->
                    refreshOptions(if (has) mode else null)
                    FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
                }
                setOnClickListener {
                    selected = mode
                    refreshOptions(mode)
                }
            }
            optionViews[mode] = optionRow
            panel.addView(optionRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        refreshOptions(null)

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(dp(520), LayoutParams.WRAP_CONTENT)
        }

        val cancelButton = dialogButton("取消") {
            dialog.dismiss()
            restorePendingRowFocus()
        }
        val confirmButton = dialogButton("确认") {
            if (settings.pageLayoutMode != selected) {
                settings.pageLayoutMode = selected
                toast("重启后生效")
            }
            dialog.dismiss()
            refresh()
        }
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(cancelButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(confirmButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })

        dialog.show()
        optionViews[selected]?.requestFocus()
    }

    private fun themeSummary(): String {
        val style = ThemeManager.getStyle(context)
        val label = ThemeManager.DEFAULT_THEMES.firstOrNull { it.first == style }?.second ?: "自定义主题"
        return if (style == ThemeManager.STYLE_CUSTOM) {
            "自定义：${if (ThemeManager.getCustomMode(context) == ThemeManager.CUSTOM_MODE_SOLID) "纯色" else "渐变色"}，透明度 ${ThemeManager.getCustomTransparency(context)}%"
        } else {
            "$label（状态栏、标题栏、内容容器、弹窗标题联动）"
        }
    }

    private fun showThemeStyleDialog() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "主题风格设置"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        panel.addView(TextView(context).apply {
            text = "切换后会联动全局状态栏、页面标题栏、内容新容器与弹窗标题背景。页面内容区域背景开关开启且已自定义时，会优先覆盖内容容器；开关关闭时内容容器跟随主题配色。"
            textSize = 13.5f
            setTextColor(Color.argb(220, 255, 255, 255))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        val presetStyles = ThemeManager.DEFAULT_THEMES
        val currentStyle = ThemeManager.getStyle(context)
        var mode = if (currentStyle == ThemeManager.STYLE_CUSTOM) "custom" else "preset"
        var selectedPresetStyle =
            if (currentStyle == ThemeManager.STYLE_CUSTOM) presetStyles.first().first else currentStyle

        // 顶部：预置主题 / 自定义主题 二选一
        val modeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        fun modeTab(label: String): TextView = TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isFocusable = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnFocusChangeListener { v, has ->
                FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
            }
        }
        val presetTab = modeTab("预置主题配色")
        val customTab = modeTab("自定义主题")
        fun renderModeTabs() {
            fun paintTab(tv: TextView, active: Boolean) {
                tv.setTextColor(if (active) WARM else Color.argb(220, 255, 255, 255))
                tv.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(if (active) Color.argb(60, 245, 196, 81) else Color.argb(30, 255, 255, 255))
                    setStroke(dp(if (active) 2 else 1), if (active) WARM else Color.argb(160, 255, 255, 255))
                }
            }
            paintTab(presetTab, mode == "preset")
            paintTab(customTab, mode == "custom")
        }
        modeRow.addView(presetTab, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        modeRow.addView(customTab, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(8) })
        panel.addView(modeRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        // 预置主题子面板：包含下拉选择器
        val presetPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        presetPanel.addView(TextView(context).apply {
            text = "预置主题配色"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(6))
        })
        val themeDropdown = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isFocusable = true
            isFocusableInTouchMode = false
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(60, 255, 255, 255))
                setStroke(dp(1), Color.argb(180, 255, 255, 255))
            }
        }
        fun refreshThemeDropdown() {
            val label = presetStyles.firstOrNull { it.first == selectedPresetStyle }?.second
                ?: presetStyles.first().second
            themeDropdown.text = "$label   ▼"
        }
        refreshThemeDropdown()
        themeDropdown.setOnFocusChangeListener { _, hasFocus ->
            themeDropdown.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(if (hasFocus) 110 else 60, 255, 255, 255))
                setStroke(dp(if (hasFocus) 2 else 1), if (hasFocus) WARM else Color.argb(180, 255, 255, 255))
            }
            FocusFxHelper.applyFocusFxState(themeDropdown, hasFocus, cornerRadiusDp = 10)
        }
        themeDropdown.setOnClickListener {
            showThemePickerPopup(presetStyles, selectedPresetStyle) { picked ->
                selectedPresetStyle = picked
                refreshThemeDropdown()
            }
        }
        presetPanel.addView(themeDropdown, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))
        panel.addView(presetPanel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 自定义主题子面板：模式 + 颜色 + 透明度
        val customPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        customPanel.addView(TextView(context).apply {
            text = "自定义主题"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            setPadding(0, dp(4), 0, dp(4))
        })
        var customMode = ThemeManager.getCustomMode(context)
        val modeGroup = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            val solidId = View.generateViewId()
            val gradientId = View.generateViewId()
            val radioFocusListener = View.OnFocusChangeListener { v, has ->
                FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 8)
            }
            addView(RadioButton(context).apply { id = solidId; text = "纯色"; textSize = 14f; setTextColor(Color.WHITE); isChecked = customMode == ThemeManager.CUSTOM_MODE_SOLID; onFocusChangeListener = radioFocusListener })
            addView(RadioButton(context).apply { id = gradientId; text = "渐变色"; textSize = 14f; setTextColor(Color.WHITE); isChecked = customMode != ThemeManager.CUSTOM_MODE_SOLID; onFocusChangeListener = radioFocusListener })
            setOnCheckedChangeListener { _, checkedId -> customMode = if (checkedId == solidId) ThemeManager.CUSTOM_MODE_SOLID else ThemeManager.CUSTOM_MODE_GRADIENT }
        }
        customPanel.addView(modeGroup)
        val customA = colorInput("颜色 A", ThemeManager.getCustomColorA(context))
        val customB = colorInput("颜色 B", ThemeManager.getCustomColorB(context))
        customA.second.setOnFocusChangeListener { v, has -> FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        customB.second.setOnFocusChangeListener { v, has -> FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        customPanel.addView(customA.first)
        customPanel.addView(customB.first)
        val alphaLabel = TextView(context).apply {
            text = "主题透明度：${ThemeManager.getCustomTransparency(context)}%"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(4))
        }
        val alphaBar = SeekBar(context).apply {
            max = 100
            progress = ThemeManager.getCustomTransparency(context)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { alphaLabel.text = "主题透明度：${progress}%" }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            setOnFocusChangeListener { v, has -> FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10) }
        }
        customPanel.addView(alphaLabel)
        customPanel.addView(alphaBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))
        panel.addView(customPanel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        fun renderMode() {
            renderModeTabs()
            presetPanel.visibility = if (mode == "preset") View.VISIBLE else View.GONE
            customPanel.visibility = if (mode == "custom") View.VISIBLE else View.GONE
        }
        presetTab.setOnClickListener { mode = "preset"; renderMode() }
        customTab.setOnClickListener { mode = "custom"; renderMode() }
        renderMode()

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)) }
        val cancelButton = dialogButton("取消") {
            dialog.dismiss()
            restorePendingRowFocus()
        }
        val applyButton = dialogButton("应用") {
            val a = normalizeHex(customA.second.text.toString(), ThemeManager.getCustomColorA(context))
            val b = normalizeHex(customB.second.text.toString(), ThemeManager.getCustomColorB(context))
            if (a == null || b == null) {
                toast("请输入正确的颜色格式，如 #0048BA")
                return@dialogButton
            }
            ThemeManager.setCustomTheme(context, customMode, a, b, alphaBar.progress)
            val targetStyle = if (mode == "custom") ThemeManager.STYLE_CUSTOM else selectedPresetStyle
            ThemeManager.setStyle(context, targetStyle)
            SettingsChangeBus.notifyChanged()
            toast("主题已应用")
            dialog.dismiss()
            refresh()
        }
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(cancelButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(applyButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        dialog.show()
        themeDropdown.requestFocus()
    }

    /**
     * 自定义深色暖黄卡片下拉浮层：用于「预置主题配色」单选。
     * 遵循 AGENT.md 弹窗规范：Theme_CastTV_Dialog + 深色渐变背景 + 暖黄描边/强调色，禁止系统 AlertDialog 浅色样式。
     */
    private fun showThemePickerPopup(
        options: List<Pair<String, String>>,
        currentStyle: String,
        onPicked: (String) -> Unit
    ) {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        panel.addView(TextView(context).apply {
            text = "选择预置主题"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(10))
        })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        options.forEach { (styleId, label) ->
            val isSelected = styleId == currentStyle
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isFocusable = true
                isFocusableInTouchMode = false
                clipChildren = false
                clipToPadding = false
            }
            val name = TextView(context).apply {
                text = label
                textSize = 15f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isSelected) WARM else Color.WHITE)
            }
            val check = TextView(context).apply {
                text = if (isSelected) "✓" else ""
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(WARM)
            }
            row.addView(name, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(check)
            fun renderBg(focused: Boolean) {
                row.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(if (focused) 70 else 30, 255, 255, 255))
                    setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(120, 255, 255, 255))
                }
            }
            renderBg(false)
            row.setOnFocusChangeListener { _, has ->
                renderBg(has)
                FocusFxHelper.applyFocusFxState(row, has, cornerRadiusDp = 10)
            }
            row.setOnClickListener {
                onPicked(styleId)
                dialog.dismiss()
            }
            listContainer.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(listContainer)
        }
        panel.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val cancel = TextView(context).apply {
            text = "取消"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isFocusable = true
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        fun renderCancelBg(focused: Boolean) {
            cancel.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.argb(if (focused) 90 else 40, 255, 255, 255))
                setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(140, 255, 255, 255))
            }
            cancel.setTextColor(if (focused) WARM else Color.WHITE)
        }
        renderCancelBg(false)
        cancel.setOnFocusChangeListener { _, has ->
            renderCancelBg(has)
            FocusFxHelper.applyFocusFxState(cancel, has, cornerRadiusDp = 10)
        }
        cancel.setOnClickListener { dialog.dismiss() }
        panel.addView(cancel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(12) })

        dialog.show()
        dialog.window?.setLayout(dp(360), LayoutParams.WRAP_CONTENT)
        // 默认聚焦到当前选中项，找不到时退到第一项
        val selectedIndex = options.indexOfFirst { it.first == currentStyle }.takeIf { it >= 0 } ?: 0
        listContainer.getChildAt(selectedIndex)?.requestFocus()
    }

    private fun showPageContentPanelDialog() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_squad_wall)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "页面内容区域背景设置"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })

        val enabledSwitch = SwitchCompat(context).apply {
            isChecked = settings.pageContentPanelEnabled
            thumbDrawable = context.getDrawable(R.drawable.switch_ios_thumb)
            trackDrawable = context.getDrawable(R.drawable.switch_ios_track)
            showText = false
            splitTrack = false
            minWidth = dp(51)
            minimumWidth = dp(51)
            minHeight = dp(31)
        }
        val switchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "启用页面内容区域背景"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.argb(235, 245, 245, 245))
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(enabledSwitch)
        }
        panel.addView(switchRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))

        val colorA = colorInput("渐变色 A", settings.pageContentPanelGradientA)
        val colorB = colorInput("渐变色 B", settings.pageContentPanelGradientB)
        panel.addView(colorA.first)
        panel.addView(colorB.first)

        val alphaLabel = TextView(context).apply {
            text = "背景透明度：${settings.pageContentPanelTransparency}%"
            textSize = 15f
            setTextColor(Color.argb(235, 245, 245, 245))
            setPadding(0, dp(10), 0, dp(4))
        }
        val alphaBar = SeekBar(context).apply {
            max = 100
            progress = settings.pageContentPanelTransparency
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { alphaLabel.text = "背景透明度：${progress}%" }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        panel.addView(alphaLabel)
        panel.addView(alphaBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(42)))

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        dialog.setOnShowListener { dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)) }
        val cancelButton = dialogButton("取消") {
            dialog.dismiss()
            restorePendingRowFocus()
        }
        val applyButton = dialogButton("应用") {
            val a = normalizeHex(colorA.second.text.toString(), "#0048BA")
            val b = normalizeHex(colorB.second.text.toString(), "#5BB5FF")
            if (a == null || b == null) {
                toast("请输入正确的颜色格式，如 #0048BA")
                return@dialogButton
            }
            settings.pageContentPanelEnabled = enabledSwitch.isChecked
            settings.pageContentPanelGradientA = a
            settings.pageContentPanelGradientB = b
            settings.pageContentPanelTransparency = alphaBar.progress
            settings.pageContentPanelCustomized = true
            SettingsChangeBus.notifyChanged()
            dialog.dismiss()
            restorePendingRowFocus()
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(cancelButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(applyButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }
        panel.addView(buttonRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        dialog.show()
        applyButton.requestFocus()
    }

    private fun colorInput(label: String, value: String): Pair<LinearLayout, EditText> {
        val input = EditText(context).apply {
            setText(value)
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.argb(235, 245, 245, 245))
            setHintTextColor(Color.argb(150, 245, 245, 245))
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.argb(52, 32, 34, 40)); setStroke(dp(1), Color.argb(170, 210, 214, 222)) }
            setPadding(dp(12), 0, dp(12), 0)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(Color.argb(235, 245, 245, 245)) }, LinearLayout.LayoutParams(dp(92), LayoutParams.WRAP_CONTENT))
            addView(input, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        return row to input
    }

    private fun normalizeHex(raw: String, fallback: String): String? {
        val value = raw.trim().ifBlank { fallback }
        return if (Regex("^#[0-9a-fA-F]{6}$").matches(value)) value.uppercase() else null
    }

    private fun showWallpaperSettingsDialog() {
        WallpaperSettingsDialog(
            context,
            onChanged = { SettingsChangeBus.notifyChanged(); refresh() },
            onClosed = { restorePendingRowFocus() }
        ).show()
    }
    private fun showDockManage() {
        var dialog: AlertDialog? = null
        val page = DockManagePage(
            android.view.LayoutInflater.from(context),
            FavoritesStore(context),
            com.bd.casttv.settings.CustomDockTabsStore(context),
            playBoundaryShake = {
                it.animate().translationX(dp(8).toFloat()).setDuration(60)
                    .withEndAction { it.animate().translationX(0f).setDuration(90).start() }
                    .start()
            },
            onDockTabsChanged = { SettingsChangeBus.notifyChanged() },
            onRequestClose = { dialog?.dismiss() }
        )
        val view = page.ensureInflated(this)
        dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(view)
            .create()
        dialog.setOnShowListener {
            page.configureDialogWindow(dialog)
            page.requestInitialFocus()
        }
        dialog.setOnDismissListener { restorePendingRowFocus() }
        dialog.show()
    }
    private fun checkUpdate() {
        Thread({
            when (val result = UpdateChecker.check(context)) {
                is UpdateChecker.CheckResult.HasUpdate -> post { showUpdateDialog(result.info) }
                is UpdateChecker.CheckResult.Latest -> post {
                    toast("已是最新版本 v${result.currentVersionName}")
                }
                is UpdateChecker.CheckResult.Error -> post {
                    toast(result.message.ifBlank { "检查更新失败，请稍后再试" })
                }
                UpdateChecker.CheckResult.NoNetwork -> Unit
            }
        }, "settings-update-check").start()
    }

    private fun showUpdateDialog(info: UpdateChecker.VersionInfo) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "发现新版本"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        panel.addView(TextView(context).apply {
            text = "v${info.versionName} 已经准备好啦"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            setPadding(0, dp(12), 0, dp(4))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val releaseNote = TextView(context).apply {
            text = info.releaseNote.ifBlank { "这次更新优化了使用体验。" }
            textSize = 14f
            setTextColor(Color.argb(230, 255, 255, 255))
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        panel.addView(releaseNote, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val laterButton = dialogButton("稍后再说") { }
        laterButton.visibility = if (info.forceUpdate) View.GONE else View.VISIBLE
        val updateButton = dialogButton("立即更新") { }
        if (!info.forceUpdate) {
            buttonRow.addView(laterButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            buttonRow.addView(updateButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        } else {
            buttonRow.addView(updateButton, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        }
        panel.addView(buttonRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(18) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        dialog.setCanceledOnTouchOutside(!info.forceUpdate)
        dialog.setOnKeyListener { _, keyCode, event ->
            info.forceUpdate && keyCode == KeyEvent.KEYCODE_BACK &&
                (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
        }
        var downloadedApkFile: File? = null
        laterButton.setOnClickListener {
            dialog.dismiss()
            restorePendingRowFocus()
        }
        updateButton.setOnClickListener {
            val readyApk = downloadedApkFile?.takeIf { it.exists() }
            if (readyApk != null) {
                installDownloadedApk(readyApk)
            } else {
                downloadAndInstall(info, updateButton) { downloadedApkFile = it }
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(dp(520), WindowManager.LayoutParams.WRAP_CONTENT)
            updateButton.requestFocus()
        }
        dialog.show()
    }

    private fun downloadAndInstall(info: UpdateChecker.VersionInfo, button: TextView, onDownloaded: (File) -> Unit) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        button.isEnabled = false
        button.text = "下载中..."
        Thread({
            when (val result = GiteeApi.downloadReleaseApk(info.versionName)) {
                is GiteeApi.ApiResult.Success -> {
                    try {
                        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
                        val apkFile = File(updateDir, "casttv-v${info.versionName}.apk")
                        apkFile.writeBytes(result.value.bytes)
                        val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                        if (archiveInfo == null) {
                            apkFile.delete()
                            post {
                                button.isEnabled = true
                                button.text = "立即更新"
                                toast("安装包校验失败，请重新下载")
                            }
                            return@Thread
                        }
                        post {
                            onDownloaded(apkFile)
                            button.isEnabled = true
                            button.text = "立即安装"
                            installDownloadedApk(apkFile)
                        }
                    } catch (_: Throwable) {
                        post {
                            button.isEnabled = true
                            button.text = "立即更新"
                            toast("下载保存失败，请稍后再试")
                        }
                    }
                }
                GiteeApi.ApiResult.NotFound -> post {
                    button.isEnabled = true
                    button.text = "立即更新"
                    toast("安装包不存在，请稍后再试")
                }
                is GiteeApi.ApiResult.Error -> post {
                    button.isEnabled = true
                    button.text = "立即更新"
                    toast("安装包下载失败，请稍后再试")
                }
            }
        }, "settings-update-apk-download").start()
    }

    private fun installDownloadedApk(apkFile: File) {
        val activity = context as? Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                toast("请先允许安装未知来源应用")
                context.startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    )
                )
                return
            }
            val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
            toast("无法打开安装器，请稍后再试")
        }
    }

    private fun showClearCacheDialog() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "缓存一键清理"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        val tvHistory = TextView(context).apply {
            text = "历史记录图片缓存：计算中…"
            textSize = 14f
            setTextColor(Color.argb(220, 255, 255, 255))
        }
        val tvFavorite = TextView(context).apply {
            text = "收藏数据图片缓存：计算中…"
            textSize = 14f
            setTextColor(Color.argb(220, 255, 255, 255))
        }
        panel.addView(tvHistory)
        panel.addView(tvFavorite, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // 选择项：使用自绘的可聚焦行，避免系统 CheckBox 在深色弹窗里样式突兀
        fun toggleRow(label: String, initChecked: Boolean): Pair<LinearLayout, () -> Boolean> {
            var checked = initChecked
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                clipChildren = false
                clipToPadding = false
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val text = TextView(context).apply {
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                text = label
            }
            val check = TextView(context).apply {
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(text, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(check)

            fun render(focused: Boolean) {
                check.text = if (checked) "✓" else ""
                check.setTextColor(WARM)
                text.setTextColor(if (checked) WARM else Color.WHITE)
                row.background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.argb(if (focused) 70 else 35, 255, 255, 255))
                    setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(120, 255, 255, 255))
                }
            }
            render(false)
            row.setOnFocusChangeListener { v, has ->
                render(has)
                FocusFxHelper.applyFocusFxState(v, has, cornerRadiusDp = 10)
            }
            row.setOnClickListener {
                checked = !checked
                render(row.hasFocus())
            }
            return row to { checked }
        }

        val historyRow = toggleRow("清空历史缩略图", initChecked = true)
        val favoriteRow = toggleRow("清空收藏缩略图", initChecked = false)
        panel.addView(historyRow.first, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        panel.addView(favoriteRow.first, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(panel)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(dp(560), LayoutParams.WRAP_CONTENT)
            historyRow.first.requestFocus()
        }

        // 后台计算目录大小，避免阻塞主线程
        Thread {
            val historyDir = Thumbnails.historyDir(context.cacheDir)
            val favoriteDir = Thumbnails.favoriteDir(context.filesDir)
            val historyStr = "历史记录图片缓存：${dirSizeStr(historyDir)}"
            val favoriteStr = "收藏数据图片缓存：${dirSizeStr(favoriteDir)}"
            panel.post {
                tvHistory.text = historyStr
                tvFavorite.text = favoriteStr
            }
        }.start()

        val cancelBtn = dialogButton("取消") {
            dialog.dismiss()
            restorePendingRowFocus()
        }
        lateinit var confirmBtn: TextView
        confirmBtn = dialogButton("清理") {
            confirmBtn.isEnabled = false
            Thread {
                if (historyRow.second()) {
                    Thumbnails.clearFolder(Thumbnails.historyDir(context.cacheDir))
                }
                if (favoriteRow.second()) {
                    Thumbnails.clearFolder(Thumbnails.favoriteDir(context.filesDir))
                }
                panel.post {
                    toast("缓存已清理")
                    dialog.dismiss()
                    restorePendingRowFocus()
                }
            }.start()
        }
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(cancelBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(confirmBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })

        dialog.show()
    }

    private fun dirSizeStr(dir: File?): String {
        if (dir == null || !dir.exists()) return "0 KB"
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return if (bytes >= 1024 * 1024) {
            "%.1f MB".format(bytes / 1024.0 / 1024.0)
        } else {
            "${bytes / 1024} KB"
        }
    }
    private fun onRecommendationVisibleToggle(visible: Boolean) {
        settings.recommendationVisible = visible
        // 每次开启都从 Settings 重新读取最新设备名称，并随 visible=true 一起覆盖写入云端。
        val latestDeviceName = if (visible) settings.deviceName else null
        Thread({
            val ok = try {
                VisibleUsersStore(context.applicationContext).updateCurrentUserVisible(visible, latestDeviceName)
            } catch (_: Throwable) {
                false
            }
            post {
                if (ok) {
                    toast(if (visible) "已同步推荐可见状态" else "已关闭推荐可见")
                } else {
                    settings.recommendationVisible = !visible
                    toast("推荐可见状态同步失败，请稍后重试")
                    refresh()
                }
            }
        }, "visible-user-update").start()
    }

    private fun bindBoundary() { val l = View.OnKeyListener { v, code, e -> if(e.action==KeyEvent.ACTION_DOWN && ((code==KeyEvent.KEYCODE_DPAD_UP && v===focusables.firstOrNull()) || (code==KeyEvent.KEYCODE_DPAD_DOWN && v===focusables.lastOrNull()))) { v.animate().translationY(if(code==KeyEvent.KEYCODE_DPAD_UP) -dp(8).toFloat() else dp(8).toFloat()).setDuration(60).withEndAction{v.animate().translationY(0f).setDuration(90).start()}.start(); true } else false }; focusables.forEach{it.setOnKeyListener(l)} }
    // 卡片背景：低不透明度 + 16dp 圆角 + 轻描边，现代简约不过度装饰
    private fun card(focused: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(if (focused) Color.argb(90, 28, 30, 38) else Color.argb(70, 18, 20, 26))
        setStroke(dp(if (focused) 2 else 1), if (focused) WARM else Color.argb(45, 255, 255, 255))
    }
    private fun maskPassword(value: String) = if (value.isBlank()) "" else "已设置：" + "•".repeat(value.length.coerceAtMost(8))
    private fun dialog() = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
    private fun toast(s:String)=Toast.makeText(context,s,Toast.LENGTH_SHORT).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()

    // -------------------- 抖音投屏适配 --------------------
    // 注意：本适配只影响 DLNA 对外身份（description.xml / SSDP）以及顶部状态栏展示的
    // 「设备名」（跟随 dlnaDeviceName 切换）。不修改 App 内的 settings.deviceName，
    // 因此设置页「设备名称」编辑行始终显示用户自定义名，用于关闭抖音适配后回退。
    private fun onDouyinCastToggle(enable: Boolean) {
        settings.douyinCastEnabled = enable
        if (enable) {
            // 首次开启时，若组/成员未初始化，落到该组默认成员
            val g = com.bd.casttv.settings.DouyinDeviceGroups.findGroup(settings.douyinDeviceGroupId)
            if (settings.douyinDeviceMemberIndex !in 0 until g.members.size) {
                settings.douyinDeviceMemberIndex = g.defaultIndex
            }
        }
        triggerDlnaIdentityRestart()
        showDeviceNameChangeGuide()
        refresh()
    }

    private fun showDouyinDeviceGroupPicker() {
        val groups = com.bd.casttv.settings.DouyinDeviceGroups.ALL
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "选择抖音兼容设备名称组"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        panel.addView(TextView(context).apply {
            text = "选择一个电视/投影厂商名称组作为对外 DLNA 身份，切换后请在手机抖音重新选择。"
            textSize = 13f
            setTextColor(Color.argb(210, 255, 255, 255))
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14); bottomMargin = dp(12)
        })

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(40), dp(8), dp(40), dp(8))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            clipChildren = true
            clipToPadding = false
            setPadding(dp(10), dp(0), dp(10), dp(0))
            addView(listContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(280)))

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        val currentGroupId = settings.douyinDeviceGroupId
        var firstFocusView: View? = null
        groups.forEachIndexed { idx, g ->
            val member = g.memberAt(g.defaultIndex)
            val isCurrent = g.id == currentGroupId
            val label = (if (isCurrent) "✓ " else "") + g.label + "  ·  " + member.friendlyName
            val row = dialogButton(label) {
                settings.douyinDeviceGroupId = g.id
                settings.douyinDeviceMemberIndex = g.defaultIndex
                triggerDlnaIdentityRestart()
                dialog.dismiss()
                showDeviceNameChangeGuide()
                refresh()
            }
            (row as? TextView)?.apply { gravity = Gravity.CENTER; setPadding(dp(14), 0, dp(14), 0) }
            listContainer.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
                topMargin = if (idx == 0) 0 else dp(8)
            })
            if (isCurrent && firstFocusView == null) firstFocusView = row
            if (firstFocusView == null && idx == 0) firstFocusView = row
        }

        val cancelBtn = dialogButton("取消") {
            dialog.dismiss()
            restorePendingRowFocus()
        }
        panel.addView(cancelBtn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(16) })

        try {
            dialog.show()
            dialog.window?.setLayout(dp(560), LayoutParams.WRAP_CONTENT)
            firstFocusView?.requestFocus()
        } catch (_: Throwable) {}
    }

    private fun showDeviceNameChangeGuide() {
        val name = settings.dlnaDeviceName
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(18))
            clipChildren = false
            clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, ThemeManager.currentPalette(context).dialogTitleGradient).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(2), WARM)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        header.addView(ClippedImageView(context).apply {
            setCircle(true)
            setImageResource(R.drawable.sticker_shinchan)
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = context.getDrawable(R.drawable.fg_sticker_circle_border)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) })
        header.addView(TextView(context).apply {
            text = "设备名称已切换"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        panel.addView(TextView(context).apply {
            text = "当前 DLNA 名称"
            textSize = 13f
            setTextColor(Color.argb(210, 255, 255, 255))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        panel.addView(TextView(context).apply {
            text = name
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WARM)
            setPadding(0, dp(4), 0, dp(4))
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(TextView(context).apply {
            text = "请在手机抖音投屏面板中重新选择上方设备名。如果 30 秒内仍找不到，可关闭再打开抖音的投屏面板刷新列表。"
            textSize = 14f
            setTextColor(Color.argb(230, 255, 255, 255))
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog).setView(panel).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        val changeBtn = dialogButton("换一个名称组") {
            dialog.dismiss()
            showDouyinDeviceGroupPicker()
        }
        val okBtn = dialogButton("我已在手机端选好") { dialog.dismiss() }
        panel.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(changeBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(okBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(18) })

        try {
            dialog.show()
            dialog.window?.setLayout(dp(560), LayoutParams.WRAP_CONTENT)
            okBtn.requestFocus()
        } catch (_: Throwable) {}
    }

    private fun triggerDlnaIdentityRestart() {
        try {
            val intent = Intent(context, com.bd.casttv.dlna.DlnaRendererService::class.java)
                .setAction(com.bd.casttv.dlna.DlnaRendererService.ACTION_RESTART_IDENTITY)
            context.startService(intent)
        } catch (_: Throwable) {}
        // 同步 PlaybackController 的抖音阈值（避免用户手动改过阈值）
        try {
            com.bd.casttv.dlna.PlaybackController.douyinHistoryThresholdMs = settings.douyinHistoryThresholdSec * 1000L
        } catch (_: Throwable) {}
    }
    companion object { private val WARM=Color.rgb(245,196,81) }
}
