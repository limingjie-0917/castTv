package com.bd.casttv.ui.framework.pages

import androidx.appcompat.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.bd.casttv.BuildConfig
import com.bd.casttv.R
import com.bd.casttv.ui.framework.BasePage
import com.bd.casttv.ui.framework.FocusFxHelper
import com.bd.casttv.ui.framework.PhoneHubHost
import com.bd.casttv.update.UpdateChecker
import com.bd.casttv.util.NetworkUtils
import com.bd.casttv.util.QrCodeGenerator
import com.bd.casttv.util.ThemeManager
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⑦ 帮助 HelpPage：
 * - 7.1 投屏教程分步（图文，复用 R.string.help_dialog_*）
 * - 7.2 边界抖动反馈（通过 BoundaryFocusHandler 触发）
 * - 7.3 遥控器按键说明（新架构焦点/按键规则）
 * - 7.5 更新日志（本地硬编码 + 允许在线拉取兜底提示）
 * - 7.6 联系反馈（一键上报日志 + 二维码联系方式）
 * - 7.7 关于（版本号 / 构建号 / 隐私 / 开源许可）
 */
class HelpPage(context: Context) : BasePage(context) {
    override val pageId = "help"
    override val pageTitle = "帮助"
    override val pageIconRes = R.drawable.ic_help_tv
    override val enablePageScroll = false
    override val useContentPanel: Boolean get() = true
    override val showPageHeader: Boolean get() = true
    override val pageStickerRes: Int get() = R.drawable.sticker_squad_wall

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 二级 Tab 内容区（Frame，包含五个子面板）。 */
    private val tabTutorial = focusButton("投屏教程")
    private val tabRemote = focusButton("遥控器按键")
    private val tabChangelog = focusButton("更新日志")
    private val tabFeedback = focusButton("反馈")
    private val tabAbout = focusButton("关于")
    private val tabs = listOf(tabTutorial, tabRemote, tabChangelog, tabFeedback, tabAbout)

    private val panelWrapper = android.widget.FrameLayout(context)
    private val panels = mutableMapOf<TextView, View>()
    private val themedParagraphCards = mutableListOf<TextView>()
    private var rightScroll: ScrollView? = null

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(60), dp(12), dp(60), dp(28))
            clipChildren = false
            clipToPadding = false
        }

        val leftColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(10), dp(2))
            clipChildren = false
            clipToPadding = false
        }
        leftColumn.addView(TextView(context).apply {
            text = "📚 帮助中心"
            textSize = 22f
            setTextColor(WARM)
            setPadding(0, 0, 0, dp(12))
        })
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            clipChildren = false
            clipToPadding = false
        }
        tabs.forEachIndexed { i, tv ->
            tv.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(if (i == 0) 0 else 10) }
            tv.setOnFocusChangeListener { v, has ->
                if (has) switchTo(v as TextView)
                applyBtnBg(v as TextView, has, v.isSelected)
            }
            tv.setOnClickListener { switchTo(tv) }
            nav.addView(tv)
        }
        val navScroll = ScrollView(context).apply {
            isFillViewport = true
            setPadding(dp(8), dp(4), dp(8), dp(4))
            clipToPadding = true
            clipChildren = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(nav, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        leftColumn.addView(navScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(leftColumn, LinearLayout.LayoutParams(dp(220), LayoutParams.MATCH_PARENT))

        rightScroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = true
            clipChildren = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rightContentBg()
        }
        val rightScroll = rightScroll ?: ScrollView(context)
        // 预构建每个面板
        panels[tabTutorial] = buildTutorialPanel()
        panels[tabRemote] = buildRemotePanel()
        panels[tabChangelog] = buildChangelogPanel()
        panels[tabFeedback] = buildFeedbackPanel()
        panels[tabAbout] = buildAboutPanel()
        panelWrapper.clipChildren = true
        panelWrapper.clipToPadding = true
        panels.values.forEach { panelWrapper.addView(it, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)) }
        rightScroll.addView(panelWrapper, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(rightScroll, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = dp(20) })

        contentContainer.addView(root, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        switchTo(tabTutorial)
    }

    override fun refreshTheme() {
        super.refreshTheme()
        rightScroll?.background = rightContentBg()
        themedParagraphCards.forEach { it.background = softCard() }
    }

    private fun switchTo(target: TextView) {
        tabs.forEach { tv -> tv.isSelected = (tv === target); applyBtnBg(tv, tv.isFocused, tv.isSelected) }
        panels.forEach { (t, v) -> v.visibility = if (t === target) View.VISIBLE else View.GONE }
    }

    // -------------------------------------------------------------- 7.1 教程
    private fun buildTutorialPanel(): View {
        val scroll = ScrollView(context).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        col.addView(sectionHeader("🐾 小新教你投屏"))
        col.addView(paragraph(
            "嘿嘿～我是小新！想把手机画面丢到电视上？跟着我做，超简单！\n\n" +
                "0）先检查一下（很重要哦）\n" +
                "· 电视端已经打开 CastTV（保持在前台也可以，后台也能接收）\n" +
                "· 手机和电视在同一个 Wi‑Fi（别一个连热点一个连路由器啦）\n\n" +
                "1）在手机上找到『投屏/投放/Cast』入口\n" +
                "· 一般在视频播放页右上角，像一个小电视的图标\n" +
                "· 或者从手机系统的『控制中心/快捷开关』里找『投屏/无线显示』\n\n" +
                "2）开始搜索电视（设备列表会跳出来）\n" +
                "· 等 1～3 秒，列表里会出现一个电视设备名字\n" +
                "· 看到和电视上显示的名字一样的那个，就选它！\n\n" +
                "3）点一下设备名，等它连上（成功就开演啦）\n" +
                "· 电视上会出现正在连接/正在播放的提示\n" +
                "· 有些 App 还会问你要不要『投屏/镜像』，选你想要的就行\n\n" +
                "4）想停止投屏？也超容易\n" +
                "· 手机上再点一次投屏图标 → 选择『断开/停止投屏』\n" +
                "· 或者直接退出播放页也可以（不同 App 行为会有一点点不一样）\n\n" +
                "5）如果搜不到电视，小新来救你！\n" +
                "· 先确认手机/电视真的是同一个 Wi‑Fi\n" +
                "· 把手机 Wi‑Fi 关一下再开，或者重启路由器\n" +
                "· 电视端打开『网络诊断』看看 SSDP/DLNA 是否正常\n" +
                "· 还不行就退出 App 重新打开一次（嘿嘿，重启大法好）"
        ))
        col.addView(sectionHeader("📱 手机也能当遥控器（连接手机页面）"))
        col.addView(paragraph("在新框架里，「连接手机」是独立页面：打开后会启动手机端 HTTP 服务。手机扫码或输入地址进入页面，就能导入直播源、管理收藏/历史、做云同步等操作；页面内 Tab 切换是局部刷新，不会整页重载。"))
        col.addView(sectionHeader("🧭 页面怎么切换（新首页）"))
        col.addView(paragraph("新版首页使用左右侧翻页按钮和底部页码，在『首页/收藏/历史/网络诊断/帮助』等页面间切换；页面内二级导航获得焦点时会自动刷新右侧内容，通常不需要再按一次 OK 才更新。"))
        scroll.addView(col)
        return scroll
    }

    // -------------------------------------------------------------- 7.3 遥控器
    private fun buildRemotePanel(): View {
        val scroll = ScrollView(context).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sectionHeader("🎮 遥控器按键（新架构 PageContainer）"))
        listOf(
            "◀ ▶ 左右方向键" to "在页面根节点或底部页码区域切换 PageContainer 页面；进入页面内容后，左 / 右按键优先在当前页面内部导航，只有无可去焦点时才回到上一级或触发边界反馈。",
            "▲ ▼ 上下方向键" to "在当前页面内容区、列表、二级导航中移动焦点；到达顶部或底部且同区域没有其他可聚焦元素时，触发边界抖动并消费按键。",
            "OK / 中键" to "确认当前焦点项。一级页面入口、二级 Tab、列表按钮都以当前焦点为准；部分二级导航在获得焦点时已自动刷新内容，OK 仅用于进入或执行操作。",
            "返回键" to "当前页内容区任意子元素获焦时，第一次 BACK 回到当前页根节点并展示底部页码；当前页根节点再次 BACK 回到首页根节点；首页根节点继续 BACK 才显示退出确认。",
            "长按 OK / 菜单" to "在支持批量操作或源管理的页面打开对应浮层；浮层展开后 LEFT / RIGHT 主要用于收起浮层并回到列表首个可聚焦按钮，BACK 由页面最高优先级消费。",
            "焦点刷新" to "Dock、二级导航、帮助页左侧选项栏均绑定 onFocus 刷新右侧内容，不需要先按 OK 再更新说明。",
            "边界抖动" to "只有确认当前方向已无同区域可聚焦元素时才抖动，避免拦截正常焦点移动。"
        ).forEach { (k, v) -> col.addView(kvRow(k, v)) }
        scroll.addView(col)
        return scroll
    }

    // -------------------------------------------------------------- 7.5 更新日志
    private fun buildChangelogPanel(): View {
        val scroll = ScrollView(context).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sectionHeader("🆕 更新日志"))
        col.addView(paragraph("当前版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        LOCAL_CHANGELOG.forEach { (ver, notes) ->
            col.addView(TextView(context).apply { text = "· $ver"; textSize = 17f; setTextColor(WARM); setPadding(0, dp(12), 0, dp(4)) })
            notes.forEach { col.addView(TextView(context).apply { text = "  – $it"; textSize = 14f; setTextColor(Color.rgb(220, 224, 230)); setPadding(0, dp(2), 0, dp(2)) }) }
        }
        val pull = focusButton("🌐 从 Gitee 拉取最新变更").apply {
            layoutParams = LinearLayout.LayoutParams(dp(280), dp(44)).apply { topMargin = dp(16) }
            setOnClickListener { pullRemoteChangelog(col) }
        }
        col.addView(pull)
        scroll.addView(col)
        return scroll
    }

    private fun pullRemoteChangelog(container: LinearLayout) {
        val hint = TextView(context).apply { text = "📥 拉取中…"; setTextColor(Color.rgb(210, 215, 225)); setPadding(0, dp(8), 0, 0); textSize = 13f }
        container.addView(hint)
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    when (val changelogResult = com.bd.casttv.sync.GiteeApi.getFileResult("CHANGELOG.md")) {
                        is com.bd.casttv.sync.GiteeApi.ApiResult.Success -> {
                            val text = changelogResult.value.content.trim()
                            if (text.isNotBlank()) {
                                "☁️ 云端 CHANGELOG.md 摘要（前 4000 字）：\n${text.take(4000)}"
                            } else {
                                loadRemoteVersionNote()
                            }
                        }
                        else -> loadRemoteVersionNote()
                    }
                }.getOrElse { "⚠️ 获取更新日志失败，请稍后再试" }
            }
            hint.text = message
        }
    }

    private fun loadRemoteVersionNote(): String {
        return when (val versionResult = com.bd.casttv.sync.GiteeApi.getFileResult("version.json")) {
            is com.bd.casttv.sync.GiteeApi.ApiResult.Success -> {
                val json = JSONObject(versionResult.value.content)
                val info = UpdateChecker.VersionInfo.fromJson(json)
                val note = info.releaseNote.trim()
                if (note.isBlank()) {
                    "⚠️ 云端 version.json 暂无更新说明"
                } else {
                    "☁️ 云端最新版本：v${info.versionName}\n\n更新说明：\n$note"
                }
            }
            com.bd.casttv.sync.GiteeApi.ApiResult.NotFound -> "⚠️ 未找到云端更新日志或版本信息"
            is com.bd.casttv.sync.GiteeApi.ApiResult.Error -> "⚠️ 获取更新日志失败，请稍后再试"
        }
    }

    // -------------------------------------------------------------- 7.6 联系反馈
    private fun buildFeedbackPanel(): View {
        val scroll = ScrollView(context).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sectionHeader("📨 反馈"))
        col.addView(paragraph("本 App 展示的资源均来源于互联网，相关版权归原作者或权利人所有。若您认为其中内容涉嫌侵权，请通过以下邮箱联系我们，我们将在收到通知后尽快核实并处理，如情况属实将及时删除相关内容。\n\n联系邮箱：1657185040@qq.com"))
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(16), dp(4), dp(4))
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val reportBtn = focusButton("📄 一键上报日志").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(7) }
            setOnClickListener { reportLog(col) }
        }
        val qrBtn = focusButton("📱 生成二维码联系方式").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(7) }
            setOnClickListener { showFeedbackQr() }
        }
        row.addView(reportBtn); row.addView(qrBtn)
        col.addView(row)
        col.addView(paragraph("提示：\n" +
            "· 上报日志会把 SSDP / HTTP / 云同步流水导出到应用外部日志目录\n" +
            "· 若已开启「连接手机」HTTP 服务，可直接扫码在手机端下载\n" +
            "· 未开启 HTTP 服务时，也可通过 U 盘 / adb pull 拷贝到电脑上"))
        scroll.addView(col)
        return scroll
    }

    private fun reportLog(container: LinearLayout) {
        val hint = TextView(context).apply { text = "📤 正在导出诊断日志…"; textSize = 13f; setTextColor(Color.rgb(210, 215, 225)); setPadding(0, dp(10), 0, 0) }
        container.addView(hint)
        scope.launch {
            val (file, err) = withContext(Dispatchers.IO) {
                try {
                    val dir = java.io.File(context.applicationContext.filesDir, "casttv_reports").apply { mkdirs() }
                    val name = "help_report_" + System.currentTimeMillis() + ".txt"
                    val f = java.io.File(dir, name)
                    f.writeText(buildString {
                        append("CastTV Report · ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})\n")
                        append("Time: ").append(java.util.Date()).append("\n")
                        append("\n---- SSDP / HTTP Snapshot ----\n")
                        append(com.bd.casttv.dlna.SsdpDiagnostics.snapshotText())
                    })
                    f to null
                } catch (t: Throwable) { null to (t.message ?: "写入失败") }
            }
            if (file == null) { hint.text = "❌ 导出失败：$err"; return@launch }
            val ip = withContext(Dispatchers.IO) { runCatching { NetworkUtils.getLocalIpAddress() }.getOrNull() }
            val port = PhoneHubHost.port()
            val urlLine = if (PhoneHubHost.isRunning() && !ip.isNullOrBlank() && port > 0)
                "\n手机端下载：http://$ip:$port/reports/${file.name}"
            else "\n（HTTP 服务未开启，暂无可下载链接）"
            hint.text = "✅ 日志已导出\n路径：${file.absolutePath}\n大小：${file.length()} bytes$urlLine"
        }
    }

    private fun showFeedbackQr() {
        val bmp = QrCodeGenerator.encode("mailto:1657185040@qq.com", dp(300)) ?: run { toast("生成二维码失败"); return }
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(24), dp(20), dp(24), dp(20)) }
        wrap.addView(TextView(context).apply { text = "扫码发起邮件反馈"; textSize = 20f; setTextColor(WARM); gravity = Gravity.CENTER })
        wrap.addView(ImageView(context).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(dp(300), dp(300)).apply { topMargin = dp(14) })
        wrap.addView(TextView(context).apply { text = "1657185040@qq.com"; textSize = 14f; setTextColor(Color.rgb(214, 219, 226)); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
        AlertDialog.Builder(context, R.style.Theme_CastTV_Dialog)
            .setView(wrap)
            .setPositiveButton("关闭", null)
            .show()
    }

    // -------------------------------------------------------------- 7.7 关于
    private fun buildAboutPanel(): View {
        val scroll = ScrollView(context).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false }
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sectionHeader("ℹ️ 关于 CastTV"))
        col.addView(kvRow("版本号", BuildConfig.VERSION_NAME))
        col.addView(kvRow("App所属类别", "投屏接收类"))
        scroll.addView(col)
        return scroll
    }

    // -------------------------------------------------------------- 样式
    private fun sectionHeader(text: String) = TextView(context).apply {
        this.text = text; textSize = 20f; setTextColor(WARM); setPadding(0, dp(6), 0, dp(8))
    }
    private fun paragraph(text: String) = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.rgb(224, 228, 236))
        setLineSpacing(dp(2).toFloat(), 1.2f)
        setPadding(dp(6), dp(4), dp(6), dp(6))
        isSingleLine = false
        setHorizontallyScrolling(false)
        maxLines = Int.MAX_VALUE
        breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
        background = softCard()
        themedParagraphCards += this
    }
    private fun kvRow(k: String, v: String): View {
        val ll = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        ll.addView(TextView(context).apply { text = k; textSize = 14f; setTextColor(Color.rgb(180, 220, 240)); layoutParams = LinearLayout.LayoutParams(dp(160), LayoutParams.WRAP_CONTENT) })
        ll.addView(TextView(context).apply {
            text = v
            textSize = 14f
            setTextColor(Color.rgb(224, 228, 236))
            isSingleLine = false
            setHorizontallyScrolling(false)
            maxLines = Int.MAX_VALUE
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        return ll
    }
    private fun softCard(): GradientDrawable {
        val palette = ThemeManager.currentPalette(context)
        val start = parseThemeColor(palette.contentPanelGradientA, 168, Color.rgb(20, 24, 32))
        val end = parseThemeColor(palette.contentPanelGradientB, 132, Color.rgb(48, 54, 68))
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(start, end)
        ).apply {
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.argb(76, 255, 255, 255))
        }
    }
    private fun rightContentBg(): GradientDrawable {
        val palette = ThemeManager.currentPalette(context)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                parseThemeColor(palette.contentPanelGradientA, 188, Color.rgb(34, 38, 46)),
                parseThemeColor(palette.contentPanelGradientB, 154, Color.rgb(92, 98, 110))
            )
        ).apply {
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), Color.argb(96, 210, 215, 225))
        }
    }
    private fun applyBtnBg(tv: TextView, focused: Boolean, selected: Boolean) {
        tv.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(180, 24, 28, 36))
            setStroke(dp(if (focused) 2 else 1), when { focused -> WARM; selected -> WARM; else -> Color.argb(120, 200, 200, 210) })
        }
        tv.setTextColor(if (focused || selected) WARM else Color.rgb(220, 226, 236))
        // 全局焦点 fx（A：scale + translationZ 发光；不叠加暖黄前景）。
        FocusFxHelper.applyFocusFxState(tv, focused, cornerRadiusDp = 10)
    }
    private fun focusButton(initial: String): TextView {
        val tv = TextView(context)
        tv.text = initial; tv.textSize = 15f; tv.gravity = Gravity.CENTER
        tv.isFocusable = true; tv.isFocusableInTouchMode = true; tv.isClickable = true
        applyBtnBg(tv, false, false)
        return tv
    }
    private fun toast(s: String) { Toast.makeText(context, s, Toast.LENGTH_SHORT).show() }
    private fun parseThemeColor(hex: String, alpha: Int, fallback: Int): Int = try {
        val rgb = Color.parseColor(hex)
        Color.argb(alpha.coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    } catch (_: Throwable) {
        Color.argb(alpha.coerceIn(0, 255), Color.red(fallback), Color.green(fallback), Color.blue(fallback))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private val WARM = Color.rgb(245, 196, 81)
        private val LOCAL_CHANGELOG: List<Pair<String, List<String>>> = listOf(
            "v1.1.169" to listOf(
                "新架构 PageContainer 下继续完善模块 ④/⑤/⑥/⑦：连接手机 / 历史 / 网络诊断 / 帮助。",
                "PhoneHubHost 抽出后台服务启停，新增已连接设备列表（PhoneHubClientTracker）。",
                "HistoryPage 支持续播 / 一键加入收藏 / 观看进度条 / 页顶清空历史。",
                "DiagnosticsPage 新增 8 项结构化诊断、直播源可用性抽检、上报日志二维码。",
                "HelpPage 拆五个二级 Tab：教程 / 遥控器 / 更新日志 / 联系反馈 / 关于。"
            ),
            "v1.1.168 及以前" to listOf(
                "GlobalTopStatusBar、HomePage、FavoritesPage、CustomTabPage、SettingsPage 迁移至新框架。",
                "云同步：管理员可修改 maxNonPresetDownload；下载解锁改为「弹窗会话级」通用密码。",
                "视频卡片焦点态：轻微放大 + 暖黄边框；父容器已开 clipChildren=false。",
                "TV Dock 由底部程序坞改为左侧侧边栏，onFocus 即刷新右侧内容。"
            )
        )
    }
}
