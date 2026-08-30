package com.bd.casttv.ui

import android.Manifest
import android.animation.ObjectAnimator
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bd.casttv.R
import com.bd.casttv.BuildConfig
import com.bd.casttv.databinding.ActivityMainBinding
import com.bd.casttv.databinding.DialogFavoriteAddBinding
import com.bd.casttv.databinding.DialogDeviceNameEditBinding
import com.bd.casttv.databinding.DialogFavTransferBinding
import com.bd.casttv.databinding.DialogFavoritesBinding
import com.bd.casttv.databinding.DialogImportLiveSourceBinding
import com.bd.casttv.databinding.DialogImportLiveProgressBinding
import com.bd.casttv.databinding.DialogImportLivePreviewBinding
import com.bd.casttv.databinding.DialogSharePromptBinding
import com.bd.casttv.databinding.DialogShareRemarkBinding
import com.bd.casttv.databinding.DialogUpdateAvailableBinding
import com.bd.casttv.databinding.ItemSharedLiveSourceBinding
import com.bd.casttv.databinding.DialogHelpBinding
import com.bd.casttv.databinding.DialogHistoryBinding
import com.bd.casttv.databinding.DialogPhoneHubBinding
import com.bd.casttv.databinding.DialogSettingsBinding
import com.bd.casttv.databinding.ItemFavoriteGridBinding
import com.bd.casttv.databinding.ItemFavoriteMiniBinding
import com.bd.casttv.databinding.ItemFavoritesSidebarBinding
import com.bd.casttv.databinding.ItemHistoryBinding
import com.bd.casttv.dlna.DlnaRendererService
import com.bd.casttv.dlna.FavoriteTransferServer
import com.bd.casttv.dlna.PlaybackController
import com.bd.casttv.dlna.displayThumbPath
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.favorites.LiveSourceImporter
import com.bd.casttv.favorites.LiveSourceSubmitServer
import com.bd.casttv.favorites.SharedLiveSourceStore
import com.bd.casttv.favorites.displayThumbPath
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.receiver.CastReceiverService
import com.bd.casttv.screensaver.PosterWallActivity
import com.bd.casttv.settings.CustomDockTabsStore
import com.bd.casttv.settings.DeviceNames
import com.bd.casttv.settings.Settings
import com.bd.casttv.util.LocalCrashLog
import com.bd.casttv.util.NetworkUtils
import com.bd.casttv.util.QrCodeGenerator
import com.bd.casttv.util.Thumbnails
import com.bd.casttv.util.ThemeManager
import com.bd.casttv.sync.CloudSyncDialog
import com.bd.casttv.sync.GiteeApi
import com.bd.casttv.ui.GlowUnderlineView
import com.bd.casttv.update.UpdateChecker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Standby / status screen. Starts the renderer service, shows connection info,
 * rotates cast tutorials, and reacts to incoming cast requests by launching
 * [PlayerActivity] (optionally behind a confirm/password dialog).
 */
class MainActivity : AppCompatActivity(), PlaybackController.StateObserver,
    com.bd.casttv.ui.framework.SettingsChangeBus.Listener {

    companion object {
        private const val TAG = "MainActivity"
        private const val STARTUP_READY_DELAY_MS = 0L
        private const val RENDERER_RETRY_DELAY_MS = 1200L
        private const val MAX_RENDERER_START_ATTEMPTS = 3
        /** 前台空闲触发海报墙的超时时长：5 分钟无任何用户交互。 */
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        /** Set by the renderer service's full-screen intent to signal that a
         *  cast is pending and should be launched using the latest controller state. */
        const val EXTRA_CAST_PENDING = "extra_cast_pending"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var customDockTabsStore: CustomDockTabsStore
    private lateinit var tabChannelConfigStore: com.bd.casttv.settings.TabChannelConfigStore
    private lateinit var sourceHealthStore: com.bd.casttv.settings.SourceHealthStore
    private lateinit var favoritesStore: FavoritesStore
    private var favoritesDialogAdapter: FavoriteAdapter? = null

    private var dockManagePage: DockManagePage? = null
    private var dockManageContentView: View? = null

    private var customDockTabPage: CustomDockTabPage? = null

    private val isTvDevice: Boolean by lazy {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        mode == Configuration.UI_MODE_TYPE_TELEVISION || packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    /** 收藏弹窗当前选中的合集 id。 */
    private var currentCollectionId: String? = null
    @Volatile private var renderCollectionRequestSeq: Int = 0
    private val ui = Handler(Looper.getMainLooper())
    private val statusTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val statusTimeTask = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                binding.textStatusTime.text = statusTimeFormat.format(Date())
                ui.postDelayed(this, 1_000L)
            }
        }
    }

    /** 前台空闲计时：到点后尝试拉起 App 内海报墙（[triggerPosterWallIfIdle]）。 */
    private val idleTimerTask = Runnable { triggerPosterWallIfIdle() }

    /** 收藏弹窗当前 binding，供导入完成后刷新右侧网格；弹窗关闭时置空。 */
    private var favoritesDialogBinding: DialogFavoritesBinding? = null
    private var importLiveInlineBinding: DialogImportLiveSourceBinding? = null
    private var importLiveSubmitServer: LiveSourceSubmitServer? = null
    private var importLivePollRunnable: Runnable? = null

    // 收藏「导入 / 导出」局域网传输相关状态。
    /** 传输服务候选端口：优先 8896，占用时回退 8085（均避开 7000/49152/8895/1900）。 */
    private val transferPortCandidates = intArrayOf(8896, 8085)
    private var transferServer: FavoriteTransferServer? = null
    private var transferDialog: AlertDialog? = null
    private var transferBinding: DialogFavTransferBinding? = null
    private var transferMode: FavoriteTransferServer.Mode? = null
    private var transferPort: Int = 0
    private var transferNetworkCallback: ConnectivityManager.NetworkCallback? = null
    /** 传输（导入/导出）进行中：期间拦截投屏自动跳转，避免打断传输流程。 */
    @Volatile private var transferInProgress = false

    // 「手机交互中心」HTTP 服务（PhoneHubServer）。服务与弹窗解耦：
    // 开关打开后后台常驻运行，播放页也能继续被手机端推送/读取状态。
    private val phoneHubPortCandidates = intArrayOf(8899, 8090)
    @Volatile private var phoneHubServer: com.bd.casttv.dlna.PhoneHubServer? = null
    private var phoneHubDialog: AlertDialog? = null
    private var exitConfirmDialog: AlertDialog? = null
    @Volatile private var phoneHubPort: Int = 0
    @Volatile private var phoneHubStarting = false
    private val phoneHubPrefs by lazy { getSharedPreferences("phone_hub_service", MODE_PRIVATE) }

    // ------------------------------------------------------------------
    // 程序坞（Dock）一级菜单 + 全屏内容区（二级菜单）状态
    // ------------------------------------------------------------------
    private enum class DockTab { HOME, FAVORITES, CUSTOM, PHONE, HISTORY, SETTINGS, HELP, DIAGNOSTICS }

    private var currentTab: DockTab = DockTab.HOME
    /** 当前内容区实际已渲染/展示的 Tab，用于避免相同 Tab 重复 showContent（见 selectTab）。 */
    private var shownTab: DockTab? = null

    /** 当前选中的自定义 Dock Tab（仅当 currentTab == [DockTab.CUSTOM] 有意义）。 */
    private var currentCustomDockTabId: String? = null

    /** 当前内容区已渲染/展示的自定义 Dock Tab，用于避免重复刷新。 */
    private var shownCustomDockTabId: String? = null
    /** 焦点是否已进入内容区（二级菜单）；用于返回键判定回到 Dock。 */
    private var contentEntered = false

    private lateinit var dockItemViews: List<View>
    private lateinit var dockTabs: List<DockTab>
    /** DockTab.CUSTOM 对应的 tabId；非自定义项为 null。与 dockTabs/dockItemViews 同步对齐。 */
    private lateinit var dockCustomTabIds: List<String?>
    private var isDockCollapsed = false
    private val contentOriginalPadding = java.util.WeakHashMap<View, IntArray>()
    private val collapseDockRunnable = Runnable {
        if (!isFocusInsideDock()) setDockCollapsed(true)
    }

    // 各 Tab 的全屏内容视图（懒加载后常驻，仅切换可见性）。
    private var favoritesContentView: View? = null
    private var customDockTabContentView: View? = null
    private var historyContentView: View? = null
    private var settingsContentView: View? = null
    private var helpContentView: View? = null
    private var diagnosticsContentView: View? = null
    private var phoneContentView: View? = null
    private var historyContentBinding: DialogHistoryBinding? = null
    private var settingsContentBinding: DialogSettingsBinding? = null
    private var phoneContentBinding: DialogPhoneHubBinding? = null
    private var diagnosticsContentBinding: com.bd.casttv.databinding.DialogSsdpDiagnosticsBinding? = null
    private var helpContentBinding: com.bd.casttv.databinding.DialogHelpBinding? = null
    // v1.1.120：网络诊断日志搜索关键词（实时过滤，空则展示全量）。
    @Volatile private var diagFilterKeyword: String = ""
    private val diagnosticsRefreshTask = object : Runnable {
        override fun run() {
            if (currentTab != DockTab.DIAGNOSTICS) return
            try {
                diagnosticsContentBinding?.textDiagnostics?.text = formatDiagnosticsBold(currentDiagnosticsText())
            } catch (_: Throwable) {
            }
            binding.root.postDelayed(this, 800L)
        }
    }

    /**
     * v1.1.125：把「帮助 / 网络诊断」日志文本里的每条日志「日期 / 时间戳」加粗。
     * 支持两类前缀：
     *   1) 事件流水行：`[HH:mm:ss.SSS] xxx`
     *   2) 明细行：`- HH:mm:ss.SSS  yyy` 或 `- HH:mm:ss.SSS?`
     * 若没有匹配到时间戳，返回原文本。
     */
    private fun formatDiagnosticsBold(text: String): CharSequence {
        if (text.isEmpty()) return text
        val ssb = SpannableStringBuilder(text)
        // 事件流水行前缀 [HH:mm:ss.SSS]
        val eventRegex = Regex("""(?m)^\[\d{2}:\d{2}:\d{2}\.\d{3}\]""")
        for (m in eventRegex.findAll(text)) {
            ssb.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                m.range.first, m.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        // 明细行「- HH:mm:ss.SSS」前缀（不含破折号本身，只加粗日期部分）
        val detailRegex = Regex("""(?m)^- (\d{2}:\d{2}:\d{2}\.\d{3})""")
        for (m in detailRegex.findAll(text)) {
            val group = m.groups[1] ?: continue
            ssb.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                group.range.first, group.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return ssb
    }

    /**
     * v1.1.120：根据搜索关键词返回要展示/复制的日志文本。
     * 无关键词时返回全量日志；有关键词时仅保留包含关键词的行（忽略大小写）。
     */
    private fun currentDiagnosticsText(): String {
        val full = com.bd.casttv.dlna.SsdpDiagnostics.snapshotText()
        val kw = diagFilterKeyword.trim()
        if (kw.isEmpty()) return full
        return full.lineSequence()
            .filter { it.contains(kw, ignoreCase = true) }
            .joinToString("\n")
    }
    private var suppressSettingsSwitchUpdates = false

    private var tutorialIndex = 0
    @Volatile private var tutorialRunning = false
    @Volatile private var startupLaunchReady = false
    private var rendererStartAttempt = 0
    private var pendingCastRequest: PendingCastRequest? = null

    // ------------------------------------------------------------------
    // v1.1.21 首页「投屏状态」四态状态机 + 全局悬浮横幅
    // ------------------------------------------------------------------
    /** 首页投屏状态四态：待机 / 收到请求过渡态 / 播放中。 */
    private enum class CastPhase { IDLE, RECEIVING, PLAYING }

    /** 当前状态源；RECEIVING 是短暂过渡态，配 [receivingFallbackTask] 8s 兜底回落。 */
    @Volatile private var castPhase: CastPhase = CastPhase.IDLE
    /** RECEIVING 过渡态最长驻留时长（毫秒）；超时后按 currentUri 自动回落到 IDLE/PLAYING。 */
    private val CAST_RECEIVING_TIMEOUT_MS = 8_000L
    /** 非密码模式投屏不再为横幅固定等待，避免抖音切换视频时触发响应超时。 */
    private val CAST_LAUNCH_DELAY_MS = 0L

    /** 全局悬浮横幅根 View，通过 addContentView 附加到 Activity 顶层容器。 */
    private var castBannerView: View? = null

    /** RECEIVING 过渡态超时兜底：即使 launchPlayer 出错也不会常驻「接收中」。 */
    private val receivingFallbackTask = Runnable {
        if (castPhase == CastPhase.RECEIVING) {
            castPhase = if (PlaybackController.currentUri.isBlank())
                CastPhase.IDLE else CastPhase.PLAYING
        }
        hideCastReceivingBanner()
        refreshCastState()
    }

    /** 悬浮横幅自动隐藏兜底：与 receivingFallbackTask 独立，覆盖异常路径。 */
    private val castBannerAutoHideTask = Runnable { hideCastReceivingBanner() }

    /** 方案 2：延迟拉起播放器的待处理请求（仅非密码模式普通投屏路径使用）。 */
    private var pendingCastLaunch: PendingCastRequest? = null
    /** 方案 2：延时拉起播放器任务，防抖后只保留最后一次目标 URI。 */
    private val castLaunchTask = Runnable { launchPendingCast() }

    /** 历史播放列表适配器（数据来自 PlaybackController.history()）。 */
    private val historyAdapter = HistoryAdapter { item ->
        launchPlayer(item.uri, item.title, item.source)
    }
    @Volatile private var historyRefreshInFlight = false
    private var lastHistorySignature: String = ""

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* non-fatal */ }

    private data class PendingCastRequest(
        val uri: String,
        val title: String,
        val sourceHint: String,
    )

    private fun logD(message: String) {
        android.util.Log.d(TAG, message)
        LocalCrashLog.d(this, TAG, message)
    }

    private fun logE(message: String, throwable: Throwable) {
        android.util.Log.e(TAG, message, throwable)
        LocalCrashLog.e(this, TAG, message, throwable)
    }

    private val tutorialRunnable = object : Runnable {
        override fun run() {
            logD("tutorialRunnable.run: index=$tutorialIndex running=$tutorialRunning finishing=$isFinishing destroyed=$isDestroyed")
            if (!tutorialRunning || isFinishing || isDestroyed) return
            try {
                rotateTutorial()
            } catch (t: Throwable) {
                logE("rotateTutorial failed", t)
                tutorialRunning = false
                ui.removeCallbacks(this)
                forceShowTutorialFallback()
                return
            }
            if (tutorialRunning && !isFinishing && !isDestroyed) {
                ui.postDelayed(this, 4_000L)
            }
        }
    }

    private val startTutorialTask = Runnable {
        logD("startTutorialTask.run: finishing=$isFinishing destroyed=$isDestroyed running=$tutorialRunning")
        if (isFinishing || isDestroyed) return@Runnable
        startTutorialRotationSafely()
    }

    /** 打开 App / 回到前台后补刷服务状态，等待前台服务与网络端口完成异步启动。 */
    private val serviceStatusRefreshTask = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (currentTab == DockTab.HOME) refreshUi()
        }
    }

    private val startupReadyTask = Runnable {
        startupLaunchReady = true
        logD("startupReadyTask.run: startupLaunchReady=true")
        val pending = pendingCastRequest
        if (pending != null && !settings.passwordMode) {
            pendingCastRequest = null
            logD("startupReadyTask.run: direct launch queued cast uri=${pending.uri}")
            launchPlayer(pending.uri, pending.title, pending.sourceHint)
            return@Runnable
        }
        flushPendingCastIfReady()
    }

    private val rendererRetryTask = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            rendererStartAttempt += 1
            logD("rendererRetryTask.run: attempt=$rendererStartAttempt")
            startRendererServiceSafelyInternal(rendererStartAttempt)
            if (rendererStartAttempt < MAX_RENDERER_START_ATTEMPTS) {
                ui.postDelayed(this, RENDERER_RETRY_DELAY_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // v1.1.116 「主题风格」入口：按 SharedPreferences 应用 DarkGray/Classic 主题，
        // 必须放在 setTheme(默认) 与 super.onCreate 之前，保证 windowBackground 也切换。
        ThemeManager.applyTo(this, R.style.Theme_CastTV)
        super.onCreate(savedInstanceState)
        logD("onCreate: savedInstanceState=$savedInstanceState")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fitCastStatusCard16x9()
        forceShowTutorialFallback()

        // 用户从桌面手动重新打开 App 时，优先清理上一轮投屏残留状态，
        // 避免 singleTask 任务复用 / 旧播放状态干扰首页启动。
        if (isManualLauncherOpen(intent) && intent?.getBooleanExtra(EXTRA_CAST_PENDING, false) != true) {
            PlaybackController.clearMedia()
        }

        settings = Settings(this)
        customDockTabsStore = CustomDockTabsStore(this)
        tabChannelConfigStore = com.bd.casttv.settings.TabChannelConfigStore(this)
        sourceHealthStore = com.bd.casttv.settings.SourceHealthStore(this)
        favoritesStore = FavoritesStore(this)
        prewarmFavoritesDataAsync()

        // —— 顶部 HomeStatusBar：运行时按主题替换成「透明磨砂毛玻璃」背景，与 GlobalTopStatusBar 视觉 1:1。
        // XML 中 `@drawable/bg_home_status_bar` 仍保留为默认深灰磨砂兜底（避免 inflate 瞬间白闪）。
        applyHomeStatusBarTheme()

        requestNotificationPermission()
        if (isPhoneHubEnabled()) startPhoneHubServer()
        scheduleRendererStartupWarmup()
        scheduleServiceStatusRefreshes()
        checkUpdate(manual = false)

        // 左侧侧边栏 Dock 初始化：上下切换入口，右键进入内容区，返回键回到 Dock。
        setupDock()

        // v1.1.23：主页投屏状态卡片在「播放中」态可聚焦/OK 续播。
        setupHomeCastCard()

        // 进入 App 默认选中「主页」，并把焦点落在主页对应的 Dock 入口上。
        // post 到首帧布局完成后再抢焦点，避免 onCreate 阶段焦点尚未就绪导致失效。
        selectTab(DockTab.HOME)
        binding.dockHome.post { binding.dockHome.requestFocus() }

        // 订阅 SettingsChangeBus：主题 / 设备名 / 自定义透明度等变更后即时重绘 HomeStatusBar。
        // （Dock 内部的主题切换走 recreate()，这里只负责「非 recreate 场景」的实时刷新。）
        com.bd.casttv.ui.framework.SettingsChangeBus.addListener(this)

        // Launched by the renderer service's full-screen intent for a cast that
        // arrived while backgrounded. 冷启动时延后放行，避免服务/页面未就绪就拉起播放器。
        queuePendingCastFromIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isManualLauncherOpen(intent) && intent.getBooleanExtra(EXTRA_CAST_PENDING, false) != true) {
            PlaybackController.clearMedia()
        }
        queuePendingCastFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        logD("onResume: start tutorial scheduling")
        startupLaunchReady = false
        PlaybackController.uiInForeground = true
        PlaybackController.registerObserver(this)
        // 返回主页时确保悬浮横幅已隐藏（播放器可能异步启动/被返回）；
        // 如仍处于 RECEIVING 短暂窗口，则按 currentUri 收敛，避免文案卡死。
        hideCastReceivingBanner()
        if (castPhase == CastPhase.RECEIVING) {
            castPhase = if (PlaybackController.currentUri.isBlank())
                CastPhase.IDLE else CastPhase.PLAYING
        }
        refreshUi()
        if (currentTab == DockTab.CUSTOM && customDockTabContentView?.visibility == View.VISIBLE) {
            customDockTabPage?.resumePreview()
        }
        ui.removeCallbacks(statusTimeTask)
        ui.post(statusTimeTask)
        scheduleServiceStatusRefreshes()
        forceShowTutorialFallback()
        ui.removeCallbacks(startupReadyTask)
        ui.postDelayed(startupReadyTask, STARTUP_READY_DELAY_MS)
        binding.tutorialText.removeCallbacks(startTutorialTask)
        binding.tutorialText.postDelayed(startTutorialTask, 300L)
        scheduleIdleTimer() // 回到前台，重新计时空闲海报墙
    }

    override fun onPause() {
        super.onPause()
        logD("onPause: removeCallbacks startTutorialTask+tutorialRunnable")
        startupLaunchReady = false
        PlaybackController.uiInForeground = false
        PlaybackController.unregisterObserver(this)
        tutorialRunning = false
        ui.removeCallbacks(startupReadyTask)
        ui.removeCallbacks(serviceStatusRefreshTask)
        ui.removeCallbacks(statusTimeTask)
        cancelIdleTimer()
        // 自定义 Tab 预览播放器：进入后台（通常是 PlayerActivity 顶上来）时释放，避免后台占用解码资源。
        customDockTabPage?.stopPreview()
        // MainActivity 进入后台（通常是 PlayerActivity 顶上来）时兜底隐藏横幅。
        hideCastReceivingBanner()
        // 离开主页时放弃延时拉起，避免后台残留触发。
        ui.removeCallbacks(castLaunchTask)
        pendingCastLaunch = null
        binding.tutorialText.removeCallbacks(startTutorialTask)
        ui.removeCallbacks(tutorialRunnable)
    }

    override fun onStop() {
        super.onStop()
        logD("onStop: removeCallbacks startTutorialTask+tutorialRunnable")
        tutorialRunning = false
        startupLaunchReady = false
        ui.removeCallbacks(startupReadyTask)
        ui.removeCallbacks(serviceStatusRefreshTask)
        ui.removeCallbacks(statusTimeTask)
        binding.tutorialText.removeCallbacks(startTutorialTask)
        ui.removeCallbacks(tutorialRunnable)
        cancelIdleTimer()
        ui.removeCallbacks(castLaunchTask)
        pendingCastLaunch = null
        // Activity 不可见时只关闭收藏导入/导出临时传输服务；
        // 手机交互 HTTP 服务由弹窗内开关控制，保持后台运行，播放页也可推送/读状态。
        stopFavTransfer()
        transferDialog?.dismiss()
        phoneHubDialog?.dismiss()
    }

    override fun onDestroy() {
        logD("onDestroy: removeCallbacks startTutorialTask+tutorialRunnable+allMessages")
        tutorialRunning = false
        startupLaunchReady = false
        ui.removeCallbacks(startupReadyTask)
        ui.removeCallbacks(serviceStatusRefreshTask)
        ui.removeCallbacks(statusTimeTask)
        ui.removeCallbacks(rendererRetryTask)
        ui.removeCallbacks(receivingFallbackTask)
        ui.removeCallbacks(castBannerAutoHideTask)
        binding.tutorialText.removeCallbacks(startTutorialTask)
        ui.removeCallbacks(tutorialRunnable)
        cancelIdleTimer()
        ui.removeCallbacks(castLaunchTask)
        pendingCastLaunch = null
        com.bd.casttv.ui.framework.SettingsChangeBus.removeListener(this)
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSettingsChanged() {
        // 非 recreate 的设置变更（设备名、内容面板自定义、状态栏染色膜透明度）：
        // 立即把 HomeStatusBar 的磨砂背景 / 文字色 / 设备名同步到最新主题。
        runOnUiThread {
            applyHomeStatusBarTheme()
            binding.textDeviceName.text = settings.deviceName
            renderServiceStatus()
        }
    }

    /**
     * Home 页顶部状态栏：用 [ThemeManager.frostedStatusBarBackground] 替换 XML 默认背景，
     * 并同步刷新所有文本 / 图标色 token，保证老 Home 与 NewMainActivity.GlobalTopStatusBar 视觉一致。
     * 左侧结构与新框架同构：<页面标题>｜<竖分隔线>｜设备名称：<设备名>。
     */
    private fun applyHomeStatusBarTheme() {
        val palette = ThemeManager.currentPalette(this@MainActivity)
        binding.homeStatusBar.background = ThemeManager.frostedStatusBarBackground(
            this@MainActivity, palette, withDivider = true
        )
        val tokens = ThemeManager.statusBarTextTokens(this@MainActivity)
        // 页面标题（老框架首页固定显示「投屏大厅」，与新框架的"首页空标题"差异化，但结构一致）
        binding.textStatusBarPageTitle.setTextColor(tokens.textPrimary)
        binding.textStatusBarPageTitle.text = "投屏大厅"
        // 标题与设备名之间的竖分隔线：按 textSecondary 半透明（与 GlobalTopStatusBar 同步）
        val sc = tokens.textSecondary
        val dividerColor = android.graphics.Color.argb(160, android.graphics.Color.red(sc), android.graphics.Color.green(sc), android.graphics.Color.blue(sc))
        binding.statusBarTitleDivider.setBackgroundColor(dividerColor)
        // 设备名
        binding.labelDeviceName.setTextColor(tokens.textSecondary)
        binding.textDeviceName.setTextColor(tokens.textPrimary)
        // 时间用主信息色；在线 DLNA/AirPlay 图标色在 renderServiceStatus() 内已经按 tokens.iconOn/iconOff 走。
        binding.textStatusTime.setTextColor(tokens.textPrimary)
    }

    /**
     * When brought up by the service's cast-pending intent, replay the launch
     * using the latest target held by the controller (which the full-screen
     * intent could not carry). 冷启动时只入队，待首页和服务稳定后再放行。
     */
    private fun queuePendingCastFromIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CAST_PENDING, false) != true) return
        intent.removeExtra(EXTRA_CAST_PENDING)
        val uri = PlaybackController.currentUri
        if (uri.isBlank()) return
        pendingCastRequest = PendingCastRequest(
            uri = uri,
            title = PlaybackController.currentTitle,
            sourceHint = PlaybackController.sourceHint,
        )
        logD("queuePendingCastFromIntent: queued uri=$uri startupReady=$startupLaunchReady")
        flushPendingCastIfReady()
    }

    private fun flushPendingCastIfReady() {
        val pending = pendingCastRequest ?: return
        if (!startupLaunchReady || isFinishing || isDestroyed) {
            logD("flushPendingCastIfReady: skipped startupReady=$startupLaunchReady finishing=$isFinishing destroyed=$isDestroyed")
            return
        }
        pendingCastRequest = null
        logD("flushPendingCastIfReady: dispatch queued cast uri=${pending.uri}")
        onNewCastRequest(pending.uri, pending.title, pending.sourceHint)
    }

    /** 判断这次启动是否来自桌面图标点击（ACTION_MAIN + CATEGORY_LAUNCHER）。 */
    private fun isManualLauncherOpen(intent: android.content.Intent?): Boolean {
        if (intent == null) return false
        return intent.action == android.content.Intent.ACTION_MAIN &&
            intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)
    }

    // ------------------------------------------------------------------
    private fun scheduleRendererStartupWarmup() {
        rendererStartAttempt = 0
        ui.removeCallbacks(rendererRetryTask)
        ui.post(rendererRetryTask)
    }

    /**
     * 服务启动、端口绑定和局域网 IP 获取都可能晚于首帧渲染；打开 App / 回到前台后连续补刷几次，
     * 确保首页服务状态区域最终展示真实运行态与 IP/端口，而不是启动瞬间的空状态。
     */
    private fun scheduleServiceStatusRefreshes() {
        ui.removeCallbacks(serviceStatusRefreshTask)
        ui.post(serviceStatusRefreshTask)
        ui.postDelayed(serviceStatusRefreshTask, 500L)
        ui.postDelayed(serviceStatusRefreshTask, 1_500L)
        ui.postDelayed(serviceStatusRefreshTask, 3_000L)
    }

    private fun fitCastStatusCard16x9() {
        binding.castStatusCard.post {
            val parent = binding.castStatusCard.parent as? View ?: return@post
            val maxWidth = parent.width
            val maxHeight = parent.height
            if (maxWidth <= 0 || maxHeight <= 0) return@post
            var targetWidth = maxWidth
            var targetHeight = targetWidth * 9 / 16
            if (targetHeight > maxHeight) {
                targetHeight = maxHeight
                targetWidth = targetHeight * 16 / 9
            }
            val lp = binding.castStatusCard.layoutParams as FrameLayout.LayoutParams
            lp.width = min(targetWidth, maxWidth)
            lp.height = min(targetHeight, maxHeight)
            lp.gravity = Gravity.CENTER
            binding.castStatusCard.layoutParams = lp
        }
    }

    private fun prewarmFavoritesDataAsync() {
        Thread({
            try {
                favoritesStore.collectionsInfo()
                favoritesStore.defaultCollectionId()
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "favorites prewarm failed", t)
            }
        }, "favorites-prewarm").start()
    }

    private fun startRendererServiceSafely() {
        startRendererServiceSafelyInternal(rendererStartAttempt)
    }

    private fun startRendererServiceSafelyInternal(attempt: Int) {
        logD("startRendererServiceSafelyInternal: attempt=$attempt")
        try {
            startRendererService()
        } catch (t: Throwable) {
            // 首页稳态优先：服务重复启动或系统限制都不应把主页面带崩。
            logE("startRendererService failed on attempt=$attempt", t)
        }
    }

    private fun startRendererService() {
        val intent = android.content.Intent(this, DlnaRendererService::class.java)
        ContextCompat.startForegroundService(this, intent)
        // Start the AirPlay 1 mirroring receiver in parallel with DLNA.
        com.bd.casttv.airplay.AirPlayService.start(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun refreshUi() {
        if (isFinishing || isDestroyed) {
            logD("refreshUi: skipped because finishing=$isFinishing destroyed=$isDestroyed")
            return
        }
        logD("refreshUi: deviceName=${settings.deviceName} currentUri=${PlaybackController.currentUri}")
        try {
            binding.textDeviceName.text = settings.deviceName
            // 逐协议渲染「服务状态」：真实运行态优先（Service.isRunning），文案 / 状态点均按协议独立。
            renderServiceStatus()
            refreshLanAddressAsync()
            refreshCastState()
            refreshHistoryAsync()
        } catch (t: Throwable) {
            logE("refreshUi failed", t)
            // 兜底展示：即使异常也保证两行协议 + IP 占位可见，避免主页留白。
            binding.textDeviceName.text = settings.deviceName
            renderServiceStatusFallback()
            binding.textIp.text = buildLanAddressText(null)
            binding.textCastState.text = getString(R.string.cast_waiting)
        }
    }

    /** IP 获取可能触发网络枚举，放到后台避免一级 Tab 焦点切换时卡主主线程。 */
    private fun refreshLanAddressAsync() {
        Thread({
            val ip = try { NetworkUtils.getLocalIpAddress() } catch (_: Throwable) { null }
            ui.post {
                if (!isFinishing && !isDestroyed) binding.textIp.text = buildLanAddressText(ip)
            }
        }, "casttv-ip-refresh").start()
    }

    private fun buildLanAddressText(ip: String?): String {
        if (ip == null) return getString(R.string.status_ip, "—")
        val port = phoneHubPort
        return if (port > 0) {
            getString(R.string.status_ip_with_phone_port, ip, port)
        } else {
            getString(R.string.status_ip_with_phone_port_off, ip, phoneHubPortCandidates[0])
        }
    }

    /** 顶部状态栏服务信号：运行中亮色；启动补刷期间半亮；未运行灰色。 */
    private fun renderServiceStatus() {
        val dlnaOn = try { DlnaRendererService.isRunning } catch (_: Throwable) { false }
        val airplayOn = try {
            com.bd.casttv.airplay.AirPlayService.isRunning
        } catch (_: Throwable) { false }
        val starting = rendererStartAttempt < MAX_RENDERER_START_ATTEMPTS
        tintSignal(binding.dlnaSignalIcon, dlnaOn, starting)
        tintSignal(binding.airplaySignalIcon, airplayOn, starting)
    }

    private fun tintSignal(icon: ImageView, running: Boolean, starting: Boolean) {
        val tokens = ThemeManager.statusBarTextTokens(this@MainActivity)
        val color = when {
            running -> tokens.iconOn
            starting -> withAlphaCompat(tokens.iconOn, 140)
            else -> tokens.iconOff
        }
        icon.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
        icon.alpha = if (running) 1f else if (starting) 0.55f else 0.46f
    }

    private fun withAlphaCompat(color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255) and 0xFF
        return (0x00FFFFFF and color) or (a shl 24)
    }

    /** 兜底：异常路径下也要给出稳定的双协议展示（均按「未开启」渲染，避免误导）。 */
    private fun renderServiceStatusFallback() {
        tintSignal(binding.dlnaSignalIcon, running = false, starting = false)
        tintSignal(binding.airplaySignalIcon, running = false, starting = false)
    }

    /** 首页收藏区已移入「收藏」Tab，主页不再展示迷你收藏，保留空实现兼容旧调用点。 */
    private fun refreshFavoritesHome() {
        // no-op：收藏内容改由 Dock「收藏」一级菜单全屏展示。
    }

    /** 刷新历史数据缓存，后台读取，主线程只在内容变化时提交 UI。 */
    private fun refreshHistoryAsync() {
        if (historyRefreshInFlight) return
        historyRefreshInFlight = true
        Thread({
            val items = try { PlaybackController.history() } catch (_: Throwable) { emptyList() }
            val signature = items.joinToString("|") {
                "${it.uri}#${it.time}#${it.positionMs}#${it.durationMs}#${it.thumbPath.orEmpty()}#${it.artworkPath.orEmpty()}"
            }
            ui.post {
                historyRefreshInFlight = false
                if (signature != lastHistorySignature) {
                    lastHistorySignature = signature
                    historyAdapter.submit(items)
                    historyContentBinding?.let { syncHistoryDialogState(it) }
                } else {
                    historyContentBinding?.let { syncHistoryDialogState(it) }
                }
            }
        }, "casttv-history-refresh").start()
    }

    /**
     * 左侧卡片「投屏状态」四态渲染：
     *   IDLE      → "等待投屏连接..."（cast_waiting）
     *   RECEIVING → "📡 正在接收投屏数据，即将播放..."（cast_receiving，短暂过渡态）
     *   PLAYING   → "▶ 正在播放 · {资源名}"（cast_playing，标题超宽时跑马灯滚动）
     * 状态源综合 [castPhase] 与 [PlaybackController.currentUri]：
     *   - RECEIVING 强制显示（覆盖 currentUri 已被设置的过渡窗口）；
     *   - 其余按 currentUri 是否为空决定 IDLE 还是 PLAYING（onResume 恢复也稳）。
     */
    private fun refreshCastState() {
        val uri = PlaybackController.currentUri
        val phase = when {
            castPhase == CastPhase.RECEIVING -> CastPhase.RECEIVING
            uri.isBlank() -> CastPhase.IDLE
            else -> CastPhase.PLAYING
        }
        val playing = phase == CastPhase.PLAYING

        binding.textCastState.text = when (phase) {
            CastPhase.IDLE -> getString(R.string.cast_waiting)
            CastPhase.RECEIVING -> getString(R.string.cast_receiving)
            CastPhase.PLAYING -> {
                // 播放中展示「正在播放 · 资源名（视频标题）」；标题为空时回退到设备名兜底。
                val title = PlaybackController.currentTitle.ifBlank {
                    settings.deviceName.ifBlank { Settings.DEFAULT_DEVICE_NAME }
                }
                getString(R.string.cast_playing, title)
            }
        }
        // 资源名可能超出卡片宽度：设为 selected 触发跑马灯滚动（无需获取焦点，不进入 D-pad 焦点链）。
        binding.textCastState.isSelected = playing

        // v1.1.24：按四态交互标准统一整理。
        // 仅「播放中」态允许投屏卡片进入 D-pad 焦点链并响应 OK 续播；
        // 待机 / 接收中 / 结束断开态都保持不可聚焦、不可点击，避免无效误触。
        val canResume = playing && PlaybackController.currentUri.isNotBlank()
        binding.castStatusCard.isFocusable = canResume
        binding.castStatusCard.isFocusableInTouchMode = false
        binding.castStatusCard.isClickable = canResume
        binding.castStatusCard.isEnabled = canResume
        binding.imgCastPlayOverlay.visibility = if (canResume) View.VISIBLE else View.GONE
        binding.textReady.text = if (canResume) getString(R.string.cast_playing_subtitle)
        else getString(R.string.card_cast_status)

        if (canResume) {
            Thumbnails.load(binding.imgTv, PlaybackController.currentThumbPath(), R.drawable.cast_status_bg)
        } else {
            binding.imgTv.setImageResource(R.drawable.cast_status_bg)
        }
    }

    /** 从首页恢复当前投屏会话：使用内存中的 uri/title/source + 退出时记录的 positionMs 续播。 */
    private fun resumeCurrentCastFromHome() {
        val uri = PlaybackController.currentUri
        if (uri.isBlank()) return
        launchPlayer(
            uri = uri,
            title = PlaybackController.currentTitle,
            sourceHint = PlaybackController.sourceHint,
            startPositionMs = PlaybackController.positionMs.coerceAtLeast(0L)
        )
    }

    /** 主页投屏卡片的点击/OK 事件绑定；真正是否可触发由 refreshCastState 按四态严格控制。 */
    private fun setupHomeCastCard() {
        binding.castStatusCard.setOnClickListener {
            if (binding.castStatusCard.isEnabled && binding.castStatusCard.isClickable) {
                resumeCurrentCastFromHome()
            }
        }
        binding.castStatusCard.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (!binding.castStatusCard.isEnabled || !binding.castStatusCard.isClickable) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    resumeCurrentCastFromHome(); true
                }
                else -> false
            }
        }
    }

    // ------------------------------------------------------------------
    // Tutorial rotation
    // ------------------------------------------------------------------
    private fun forceShowTutorialFallback() {
        logD("forceShowTutorialFallback: begin")
        try {
            val name = try {
                settings.deviceName.ifBlank { Settings.DEFAULT_DEVICE_NAME }
            } catch (_: Throwable) {
                Settings.DEFAULT_DEVICE_NAME
            }
            val fallback = getString(R.string.tutorial_static_with_name, name)
            binding.tutorialText.text = fallback.ifBlank { getString(R.string.tutorial_static) }
            binding.tutorialText.visibility = View.VISIBLE
            binding.tutorialTitle.visibility = View.VISIBLE
            binding.tutorialAccent.visibility = View.VISIBLE
        } catch (t: Throwable) {
            logE("forceShowTutorialFallback failed", t)
            try {
                binding.tutorialText.text = "通用方式：打开视频 → 点击 TV / 投屏 → 选择当前电视设备"
                binding.tutorialText.visibility = View.VISIBLE
            } catch (_: Throwable) {
                // no-op：兜底中的兜底，绝不影响首页启动。
            }
        }
    }

    private fun startTutorialRotationSafely() {
        logD("startTutorialRotationSafely: finishing=$isFinishing destroyed=$isDestroyed currentIndex=$tutorialIndex")
        if (isFinishing || isDestroyed) return
        ui.removeCallbacks(tutorialRunnable)
        forceShowTutorialFallback()
        val tutorials = buildTutorialMessages()
        if (tutorials.isEmpty()) {
            tutorialRunning = false
            tutorialIndex = 0
            forceShowTutorialFallback()
            return
        }
        try {
            binding.tutorialText.text = tutorials.first()
            binding.tutorialText.visibility = View.VISIBLE
        } catch (t: Throwable) {
            logE("show first tutorial failed", t)
            tutorialRunning = false
            tutorialIndex = 0
            forceShowTutorialFallback()
            return
        }
        if (tutorials.size <= 1) {
            tutorialRunning = false
            tutorialIndex = 0
            return
        }
        tutorialRunning = true
        tutorialIndex = 1
        ui.postDelayed(tutorialRunnable, 4_000L)
    }

    private fun buildTutorialMessages(): List<String> {
        val name = try {
            settings.deviceName.ifBlank { Settings.DEFAULT_DEVICE_NAME }
        } catch (t: Throwable) {
            logE("read device name for tutorial failed", t)
            Settings.DEFAULT_DEVICE_NAME
        }
        val tutorials = listOfNotNull(
            safeTutorialString(R.string.tutorial_iqiyi, name),
            safeTutorialString(R.string.tutorial_bilibili, name),
            safeTutorialString(R.string.tutorial_tencent, name),
            safeTutorialString(R.string.tutorial_youku, name),
            safeTutorialString(R.string.tutorial_static)
        ).map { it.trim() }.filter { it.isNotBlank() }
        logD("buildTutorialMessages: deviceName=$name count=${tutorials.size}")
        return tutorials.distinct()
    }

    private fun safeTutorialString(resId: Int, vararg args: Any): String? {
        return try {
            if (args.isEmpty()) getString(resId) else getString(resId, *args)
        } catch (t: Throwable) {
            logE("read tutorial string failed: $resId", t)
            null
        }
    }

    private fun rotateTutorial() {
        logD("rotateTutorial: before index=$tutorialIndex running=$tutorialRunning finishing=$isFinishing destroyed=$isDestroyed")
        if (!tutorialRunning || isFinishing || isDestroyed) return
        val tutorials = buildTutorialMessages()
        if (tutorials.isEmpty()) {
            tutorialRunning = false
            forceShowTutorialFallback()
            return
        }
        val safeIndex = ((tutorialIndex % tutorials.size) + tutorials.size) % tutorials.size
        try {
            binding.tutorialText.text = tutorials[safeIndex]
        } catch (t: Throwable) {
            logE("update tutorial text failed", t)
            tutorialRunning = false
            forceShowTutorialFallback()
            return
        }
        tutorialIndex = (safeIndex + 1) % tutorials.size
    }

    // ------------------------------------------------------------------
    // Incoming cast handling
    // ------------------------------------------------------------------
    override fun onNewCastRequest(uri: String, title: String, sourceHint: String) {
        if (!startupLaunchReady) {
            pendingCastRequest = PendingCastRequest(uri, title, sourceHint)
            logD("onNewCastRequest: queued because startup not ready uri=$uri")
            return
        }
        // 收藏导入 / 导出进行中：暂存投屏请求，避免打断传输流程（不自动跳转到播放器）。
        if (transferInProgress) {
            pendingCastRequest = PendingCastRequest(uri, title, sourceHint)
            logD("onNewCastRequest: queued because favorite transfer in progress uri=$uri")
            return
        }
        // 当前停留在自定义 Tab 时，外部投屏一到达就立即暂停预览播放器，避免与正式投屏播放器抢音视频资源。
        if (currentTab == DockTab.CUSTOM && customDockTabContentView?.visibility == View.VISIBLE) {
            customDockTabPage?.pausePreviewForExternalCast()
        }
        // 进入 RECEIVING 过渡态：立即刷新主页文案；非密码模式弹出全局悬浮横幅
        // （密码模式下需要等用户输入，横幅语义不匹配，直接跳过展示以免误导）。
        castPhase = CastPhase.RECEIVING
        refreshCastState()
        ui.removeCallbacks(receivingFallbackTask)
        ui.postDelayed(receivingFallbackTask, CAST_RECEIVING_TIMEOUT_MS)
        if (!settings.passwordMode) {
            // 先刷新横幅，再立即排队拉起播放器；重复请求防抖只保留最后一次。
            // 不再固定等待 800ms，避免 Stop -> SetAVTransportURI -> Play 连续指令期间超时。
            pendingCastLaunch = PendingCastRequest(uri, title, sourceHint)
            showCastReceivingBanner()
            ui.removeCallbacks(castLaunchTask)
            if (CAST_LAUNCH_DELAY_MS <= 0L) ui.post(castLaunchTask)
            else ui.postDelayed(castLaunchTask, CAST_LAUNCH_DELAY_MS)
        } else {
            showPasswordDialog(uri, title, sourceHint)
        }
    }

    /** 方案 2：延时到点后真正拉起播放器；期间离开主页 / 传输占用则放弃。 */
    private fun launchPendingCast() {
        val p = pendingCastLaunch ?: return
        pendingCastLaunch = null
        if (isFinishing || isDestroyed) return
        if (transferInProgress) return
        launchPlayer(p.uri, p.title, p.sourceHint)
    }

    private fun launchPlayer(uri: String, title: String, sourceHint: String, startPositionMs: Long = 0L) {
        // 真实拉起播放器：清掉 RECEIVING 过渡态与悬浮横幅，主页恢复为 PLAYING 文案；
        // 若 MainActivity 尚未进入后台，onPause 也会兜底再隐藏一次。
        castPhase = CastPhase.PLAYING
        ui.removeCallbacks(receivingFallbackTask)
        hideCastReceivingBanner()
        refreshCastState()
        PlaybackController.recordPlaybackHistory(uri, title, sourceHint, PlaybackController.currentArtworkUrl())
        if (PlaybackController.hasActiveCommandCallback()) {
            // 已有 PlayerActivity 在前台/栈内接管 DLNA 指令时，SetAVTransportURI 会直接
            // 通过 commandCallback 切源。这里不能再次 startActivity，否则会出现退出播放页再重进。
            logD("launchPlayer: active PlayerActivity command callback, skip startActivity for seamless cast switch")
            return
        }
        val intent = android.content.Intent(this, PlayerActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PlayerActivity.EXTRA_URI, uri)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_SOURCE, sourceHint)
            putExtra(PlayerActivity.EXTRA_START_POSITION, startPositionMs)
        }
        startActivity(intent)
    }

    /**
     * v1.1.21：监听播放结束/停止/无媒体态，主页文案自动回到「等待投屏连接...」。
     * 观察者在主线程回调，这里再 post 一次以避免与 registerObserver 时序竞争。
     */
    override fun onTransportStateChanged(state: PlaybackController.TransportState) {
        ui.post {
            when (state) {
                PlaybackController.TransportState.STOPPED,
                PlaybackController.TransportState.NO_MEDIA_PRESENT -> {
                    castPhase = CastPhase.IDLE
                    ui.removeCallbacks(receivingFallbackTask)
                    hideCastReceivingBanner()
                }
                PlaybackController.TransportState.PLAYING -> {
                    if (castPhase != CastPhase.PLAYING) castPhase = CastPhase.PLAYING
                    ui.removeCallbacks(receivingFallbackTask)
                    hideCastReceivingBanner()
                }
                else -> { /* TRANSITIONING / PAUSED_PLAYBACK 不改主页态 */ }
            }
            refreshCastState()
        }
    }

    // ------------------------------------------------------------------
    // v1.1.21 全局悬浮横幅（不参与 D-pad 焦点）
    // ------------------------------------------------------------------
    /**
     * 通过 [addContentView] 把横幅附加到 android.R.id.content（Activity 顶层 FrameLayout），
     * 位于所有 Tab 内容之上；全链路禁用 focusable/clickable，绝不进入 D-pad 焦点链，
     * 不影响 dock/内容区遥控器交互与按键分发。
     */
    private fun ensureCastReceivingBanner(): View {
        castBannerView?.let { return it }
        val v = layoutInflater.inflate(R.layout.banner_cast_receiving, null, false)
        v.isFocusable = false
        v.isFocusableInTouchMode = false
        v.isClickable = false
        v.visibility = View.GONE
        // 与顶部保持一段安全距离，居中横向。addContentView 的 parent 为
        // FrameLayout（android.R.id.content），此处 LayoutParams 走 FrameLayout。
        val topMarginPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics
        ).toInt()
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = topMarginPx
        }
        addContentView(v, lp)
        castBannerView = v
        return v
    }

    /** 显示悬浮横幅，带淡入+下滑入场动画；同时启动 8s 兜底自动隐藏。 */
    private fun showCastReceivingBanner() {
        if (isFinishing || isDestroyed) return
        val v = try { ensureCastReceivingBanner() } catch (t: Throwable) {
            logE("ensureCastReceivingBanner failed", t); return
        }
        if (v.visibility == View.VISIBLE) return
        v.alpha = 0f
        v.translationY = -20f
        v.visibility = View.VISIBLE
        v.animate().cancel()
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()
        ui.removeCallbacks(castBannerAutoHideTask)
        ui.postDelayed(castBannerAutoHideTask, CAST_RECEIVING_TIMEOUT_MS)
    }

    /** 隐藏悬浮横幅（若存在），带轻微淡出动画；幂等安全。 */
    private fun hideCastReceivingBanner() {
        val v = castBannerView ?: return
        ui.removeCallbacks(castBannerAutoHideTask)
        if (v.visibility != View.VISIBLE) return
        v.animate().cancel()
        v.animate()
            .alpha(0f)
            .translationY(-20f)
            .setDuration(150L)
            .withEndAction { v.visibility = View.GONE }
            .start()
    }

    private fun showPasswordDialog(uri: String, title: String, sourceHint: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.password_title)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.connect_request_title))
            .setMessage(getString(R.string.connect_request_msg, sourceHint.ifBlank { title }))
            .setView(input)
            .setPositiveButton(R.string.btn_allow) { d, _ ->
                if (input.text.toString() == settings.password || settings.password.isBlank()) {
                    launchPlayer(uri, title, sourceHint)
                } else {
                    android.widget.Toast.makeText(this, R.string.password_wrong,
                        android.widget.Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.btn_reject) { d, _ -> d.dismiss() }
            .show()
    }

    // ------------------------------------------------------------------
    // App update check
    // ------------------------------------------------------------------
    private fun checkUpdate(manual: Boolean) {
        // 网络状态读取与 Gitee API 均放到后台线程，避免 Android 手机端启动/手动检查时阻塞主线程。
        Thread({
            if (!UpdateChecker.hasNetwork(this)) return@Thread
            val result = UpdateChecker.check(this)
            ui.post {
                when (result) {
                    is UpdateChecker.CheckResult.HasUpdate -> showUpdateDialog(result.info)
                    is UpdateChecker.CheckResult.Latest -> if (manual) {
                        android.widget.Toast.makeText(
                            this,
                            "已是最新版本 v${result.currentVersionName}",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    is UpdateChecker.CheckResult.Error -> if (manual) {
                        // result.message 已为脱敏后的通用文案，不含仓库地址 / token。
                        android.widget.Toast.makeText(
                            this,
                            result.message.ifBlank { "检查更新失败，请稍后再试" },
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    UpdateChecker.CheckResult.NoNetwork -> Unit
                }
            }
        }, if (manual) "manual-update-check" else "startup-update-check").start()
    }

    private fun showUpdateDialog(info: UpdateChecker.VersionInfo) {
        if (isFinishing || isDestroyed) return
        val dialogBinding = DialogUpdateAvailableBinding.inflate(layoutInflater)
        dialogBinding.textUpdateVersion.text = "新版本 v${info.versionName}"
        dialogBinding.textReleaseNote.text = info.releaseNote.ifBlank { "这次更新优化了使用体验。" }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.setCanceledOnTouchOutside(!info.forceUpdate)
        dialog.setOnKeyListener { _, keyCode, event ->
            info.forceUpdate && keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
        }
        dialogBinding.btnUpdateLater.visibility = if (info.forceUpdate) View.GONE else View.VISIBLE
        dialogBinding.btnUpdateLater.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnUpdateDownload.setOnClickListener {
            downloadUpdateFromGitee(info, dialogBinding)
        }
        dialog.setOnShowListener { dialogBinding.btnUpdateDownload.requestFocus() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    private fun downloadUpdateFromGitee(
        info: UpdateChecker.VersionInfo,
        dialogBinding: DialogUpdateAvailableBinding
    ) {
        val button = dialogBinding.btnUpdateDownload
        button.isEnabled = false
        button.text = "下载中..."
        Thread({
            val remotePath = "releases/casttv-v${info.versionName}.apk"
            when (val result = GiteeApi.getBinaryFileResult(remotePath)) {
                is GiteeApi.ApiResult.Success -> {
                    try {
                        val updateDir = File(cacheDir, "updates").apply { mkdirs() }
                        val apkFile = File(updateDir, "casttv-v${info.versionName}.apk")
                        apkFile.writeBytes(result.value.bytes)
                        val archiveInfo = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                        if (archiveInfo == null) {
                            apkFile.delete()
                            ui.post {
                                button.isEnabled = true
                                button.text = "立即更新"
                                android.widget.Toast.makeText(this, "安装包校验失败，请重新下载", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return@Thread
                        }
                        ui.post {
                            button.isEnabled = true
                            button.text = "立即安装"
                            installDownloadedApk(apkFile)
                        }
                    } catch (_: Throwable) {
                        ui.post {
                            button.isEnabled = true
                            button.text = "立即更新"
                            android.widget.Toast.makeText(this, "下载保存失败，请稍后再试", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                GiteeApi.ApiResult.NotFound -> ui.post {
                    button.isEnabled = true
                    button.text = "立即更新"
                    android.widget.Toast.makeText(this, "安装包不存在，请稍后再试", android.widget.Toast.LENGTH_SHORT).show()
                }
                is GiteeApi.ApiResult.Error -> ui.post {
                    button.isEnabled = true
                    button.text = "立即更新"
                    android.widget.Toast.makeText(this, "安装包下载失败，请稍后再试", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }, "update-apk-download").start()
    }

    private fun installDownloadedApk(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                android.widget.Toast.makeText(this, "请先允许安装未知来源应用", android.widget.Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
            val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Throwable) {
            android.widget.Toast.makeText(this, "无法打开安装器，请稍后再试", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Settings / Help dialogs
    // ------------------------------------------------------------------
    private fun showSettingsContent() {
        try {
            if (settingsContentView == null) {
                val dialogBinding = DialogSettingsBinding.inflate(layoutInflater, binding.contentContainer, false)
                settingsContentBinding = dialogBinding
                settingsContentView = dialogBinding.root
                addFullscreenContent(dialogBinding.root)
                setupSettingsContent(dialogBinding)
            }
            settingsContentBinding?.let { populateSettings(it) }
            settingsContentView?.visibility = View.VISIBLE
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "showSettingsContent failed", t)
        }
    }

    /** 读取当前设置值填充设置页（每次进入设置 Tab 时刷新）。 */
    private fun populateSettings(dialogBinding: DialogSettingsBinding) {
        suppressSettingsSwitchUpdates = true
        dialogBinding.currentDeviceName.text = settings.deviceName.ifBlank { Settings.DEFAULT_DEVICE_NAME }
        dialogBinding.textCurrentVersion.text = "版本 v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        dialogBinding.pwdMode.isChecked = settings.passwordMode
        dialogBinding.pwdInput.setText(settings.password)
        dialogBinding.pwdInput.inputType = InputType.TYPE_CLASS_NUMBER
        dialogBinding.mute.isChecked = settings.muteOnCast
        dialogBinding.autoStart.isChecked = settings.bootAutoStart
        dialogBinding.useSurfaceView.isChecked = settings.useSurfaceView
        when (settings.screensaverStyle) {
            Settings.SCREENSAVER_WATERFALL -> dialogBinding.screensaverWaterfall.isChecked = true
            Settings.SCREENSAVER_MOSAIC -> dialogBinding.screensaverMosaic.isChecked = true
            else -> dialogBinding.screensaverTrack.isChecked = true
        }
        // v1.1.116 「主题风格」入口：按当前持久化的主题设置回填 RadioGroup。
        when (ThemeManager.getStyle(this)) {
            ThemeManager.STYLE_CLASSIC -> dialogBinding.themeStyleClassic.isChecked = true
            else -> dialogBinding.themeStyleDarkGray.isChecked = true
        }
        suppressSettingsSwitchUpdates = false
        refreshCacheSizes(dialogBinding)
    }

    /** 异步刷新缓存占用展示。 */
    private fun refreshCacheSizes(dialogBinding: DialogSettingsBinding) {
        Thread({
            val histBytes = Thumbnails.folderSize(Thumbnails.historyDir(cacheDir))
            val favBytes = Thumbnails.folderSize(Thumbnails.favoriteDir(filesDir))
            val totalBytes = histBytes + favBytes
            ui.post {
                dialogBinding.cacheSizeHistory.text = getString(
                    R.string.settings_cache_history_line, Thumbnails.formatSize(histBytes)
                )
                dialogBinding.cacheSizeFavorite.text = getString(
                    R.string.settings_cache_favorite_line, Thumbnails.formatSize(favBytes)
                )
                dialogBinding.cacheSizeTotal.text = getString(
                    R.string.settings_cache_total_line, Thumbnails.formatSize(totalBytes)
                )
            }
        }, "cache-size").start()
    }

    /** 首次构建设置内容时绑定各按钮/交互（仅执行一次）。 */
    private fun setupSettingsContent(dialogBinding: DialogSettingsBinding) {
        dialogBinding.autoStart.text = getString(
            R.string.settings_autostart
        ) + "\n" + getString(R.string.settings_autostart_summary)

        dialogBinding.useSurfaceView.text = getString(
            R.string.settings_use_surface_view
        ) + "\n" + getString(R.string.settings_use_surface_view_summary)

        dialogBinding.deviceNameCard.setOnClickListener { showDeviceNameEditDialog(dialogBinding) }
        dialogBinding.pwdMode.setOnCheckedChangeListener { _, _ -> applySettingsSwitches(dialogBinding) }
        dialogBinding.mute.setOnCheckedChangeListener { _, _ -> applySettingsSwitches(dialogBinding) }
        dialogBinding.autoStart.setOnCheckedChangeListener { _, _ -> applySettingsSwitches(dialogBinding) }
        dialogBinding.useSurfaceView.setOnCheckedChangeListener { _, _ -> applySettingsSwitches(dialogBinding) }
        dialogBinding.pwdInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applySettingsSwitches(dialogBinding)
        }
        dialogBinding.screensaverStyleGroup.setOnCheckedChangeListener { _, _ -> applySettingsSwitches(dialogBinding) }
        // v1.1.116 「主题风格」切换：写入 SharedPreferences 后调用 recreate() 立即生效。
        dialogBinding.themeStyleGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressSettingsSwitchUpdates) return@setOnCheckedChangeListener
            val target = when (checkedId) {
                R.id.themeStyleClassic -> ThemeManager.STYLE_CLASSIC
                else -> ThemeManager.STYLE_DARK_GRAY
            }
            val current = ThemeManager.getStyle(this)
            if (current == target) return@setOnCheckedChangeListener
            ThemeManager.setStyle(this, target)
            android.widget.Toast.makeText(
                this,
                if (target == ThemeManager.STYLE_DARK_GRAY) "已切换到「深灰主题」" else "已切换到「经典」主题",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            // recreate() 是最稳的重建方案：会重新走 onCreate → setTheme，
            // 全部 windowBackground / ?attr/... 都会按新主题重建。
            recreate()
        }
        bindSettingsBoundaryShake(dialogBinding)

        dialogBinding.btnPreviewScreensaver.setOnClickListener {
            startActivity(Intent(this, com.bd.casttv.screensaver.PosterWallActivity::class.java))
            // 用户主动预览：不关设置弹窗、也不改任何 settings；预览界面按任意键 finish 自行返回
        }

        dialogBinding.btnDockManage.setOnClickListener {
            showDockManageContent(dialogBinding)
        }

        dialogBinding.btnCheckUpdate.setOnClickListener {
            checkUpdate(manual = true)
        }

        dialogBinding.clearCacheBtn.setOnClickListener {
            val clearHistory = dialogBinding.clearHistoryCheck.isChecked
            val clearFavorite = dialogBinding.clearFavoriteCheck.isChecked
            if (!clearHistory && !clearFavorite) {
                android.widget.Toast.makeText(
                    this, R.string.settings_cache_none_selected,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_cache_confirm_title)
                .setMessage(R.string.settings_cache_confirm_message)
                .setPositiveButton(R.string.settings_cache_confirm_ok) { confirmDlg, _ ->
                    Thread({
                        var released = 0L
                        if (clearHistory) {
                            val dir = Thumbnails.historyDir(cacheDir)
                            released += Thumbnails.folderSize(dir)
                            Thumbnails.clearFolder(dir)
                        }
                        if (clearFavorite) {
                            val dir = Thumbnails.favoriteDir(filesDir)
                            released += Thumbnails.folderSize(dir)
                            Thumbnails.clearFolder(dir)
                        }
                        ui.post {
                            android.widget.Toast.makeText(
                                this,
                                getString(R.string.settings_cache_cleared, Thumbnails.formatSize(released)),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            refreshCacheSizes(dialogBinding)
                            // 刷新列表 UI，让清理后已丢失文件的项立即回退到默认图。
                            historyAdapter.notifyDataSetChanged()
                            favoritesDialogAdapter?.notifyDataSetChanged()
                        }
                    }, "cache-clear").start()
                    confirmDlg.dismiss()
                }
                .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
                .create()
                .also { it.setOnShowListener { _ ->
                    // TV 焦点：确保确认弹窗默认焦点落在「清理」按钮上，避免焦点丢失。
                    it.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
                } }
                .show()
        }
    }

    private fun bindSettingsBoundaryShake(dialogBinding: DialogSettingsBinding) {
        val boundaryRoot = dialogBinding.root
        val listener = View.OnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP &&
                keyCode != KeyEvent.KEYCODE_DPAD_DOWN &&
                keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
            ) return@OnKeyListener false
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                playBoundaryShake(view)
                return@OnKeyListener true
            }
            val focusables = listOf(
                dialogBinding.deviceNameCard,
                dialogBinding.pwdMode,
                dialogBinding.pwdInput,
                dialogBinding.mute,
                dialogBinding.autoStart,
                dialogBinding.useSurfaceView,
                dialogBinding.screensaverTrack,
                dialogBinding.screensaverWaterfall,
                dialogBinding.screensaverMosaic,
                dialogBinding.btnPreviewScreensaver,
                dialogBinding.btnDockManage,
                dialogBinding.clearHistoryCheck,
                dialogBinding.clearFavoriteCheck,
                dialogBinding.clearCacheBtn,
                dialogBinding.btnCheckUpdate
            ).filter { it.visibility == View.VISIBLE && it.isEnabled && it.isFocusable }
            val topView = focusables.firstOrNull()
            val bottomView = focusables.lastOrNull()
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && view === topView) {
                playBoundaryShake(view)
                return@OnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && view === bottomView) {
                playBoundaryShake(view)
                return@OnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                // TV 焦点兜底：清理缩略图区域上方隔着标题/分割线等不可聚焦 View，
                // 部分设备/滚动状态下系统 focusSearch 偶现找不到「云同步」，直接稳定接管。
                when (view) {
                    dialogBinding.clearHistoryCheck -> {
                        dialogBinding.btnDockManage.requestFocus()
                        return@OnKeyListener true
                    }
                    dialogBinding.clearFavoriteCheck -> {
                        dialogBinding.clearHistoryCheck.requestFocus()
                        return@OnKeyListener true
                    }
                }
            }
            val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
            val next = view.focusSearch(direction)
            val hasNextInSettings = next != null &&
                next !== view &&
                next !== boundaryRoot &&
                next.visibility == View.VISIBLE &&
                next.isEnabled &&
                next.isFocusable &&
                isDescendantOf(next, boundaryRoot)
            if (hasNextInSettings) return@OnKeyListener false
            playBoundaryShake(view)
            true
        }
        listOf(
            dialogBinding.deviceNameCard,
            dialogBinding.pwdMode,
            dialogBinding.pwdInput,
            dialogBinding.mute,
            dialogBinding.autoStart,
            dialogBinding.useSurfaceView,
            dialogBinding.screensaverTrack,
            dialogBinding.screensaverWaterfall,
            dialogBinding.screensaverMosaic,
            dialogBinding.btnPreviewScreensaver,
            dialogBinding.btnDockManage,
            dialogBinding.clearHistoryCheck,
            dialogBinding.clearFavoriteCheck,
            dialogBinding.clearCacheBtn,
            dialogBinding.btnCheckUpdate
        ).forEach { it.setOnKeyListener(listener) }
    }

    /** Switch/Radio 状态改变后立即写入设置，不再依赖统一保存按钮。 */
    private fun applySettingsSwitches(dialogBinding: DialogSettingsBinding) {
        if (suppressSettingsSwitchUpdates) return
        settings.passwordMode = dialogBinding.pwdMode.isChecked
        settings.password = dialogBinding.pwdInput.text.toString()
        settings.muteOnCast = dialogBinding.mute.isChecked
        val bootChanged = settings.bootAutoStart != dialogBinding.autoStart.isChecked
        settings.bootAutoStart = dialogBinding.autoStart.isChecked
        settings.useSurfaceView = dialogBinding.useSurfaceView.isChecked
        settings.screensaverStyle = when (dialogBinding.screensaverStyleGroup.checkedRadioButtonId) {
            R.id.screensaverWaterfall -> Settings.SCREENSAVER_WATERFALL
            R.id.screensaverMosaic -> Settings.SCREENSAVER_MOSAIC
            else -> Settings.SCREENSAVER_TRACK
        }
        refreshUi()
        ui.post {
            if (bootChanged) {
                if (settings.bootAutoStart) CastReceiverService.start(this) else CastReceiverService.stop(this)
            }
            startRendererServiceSafely()
        }
    }

    /** 修改设备名称弹窗：沿用设置弹窗的面板、标题条、输入框和按钮风格。 */
    private fun showDeviceNameEditDialog(settingsBinding: DialogSettingsBinding) {
        val dialogBinding = DialogDeviceNameEditBinding.inflate(layoutInflater)
        dialogBinding.nameInput.setText(settings.deviceName.ifBlank { Settings.DEFAULT_DEVICE_NAME })
        dialogBinding.nameInput.setSelection(dialogBinding.nameInput.text?.length ?: 0)
        dialogBinding.randomBtn.setOnClickListener {
            val next = DeviceNames.random(dialogBinding.nameInput.text.toString())
            dialogBinding.nameInput.setText(next)
            dialogBinding.nameInput.setSelection(next.length)
        }
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        dialogBinding.cancelBtn.setOnClickListener { dialog.dismiss() }
        dialogBinding.confirmBtn.setOnClickListener {
            val newDeviceName = dialogBinding.nameInput.text.toString().ifBlank { Settings.DEFAULT_DEVICE_NAME }
            settings.deviceName = newDeviceName
            settingsBinding.currentDeviceName.text = newDeviceName
            refreshUi()
            ui.post { startRendererServiceSafely() }
            android.widget.Toast.makeText(this, "设备名称已更新", android.widget.Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            settingsBinding.deviceNameCard.requestFocus()
        }
        dialog.setOnShowListener { dialogBinding.nameInput.requestFocus() }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    /** 云同步弹窗（Gitee）。 */
    private var cloudSyncDialog: CloudSyncDialog? = null
    private fun showCloudSyncDialog() {
        if (cloudSyncDialog == null) {
            cloudSyncDialog = CloudSyncDialog(this, favoritesStore) {
                // 同步完成后刷新收藏列表
                favoritesDialogAdapter?.notifyDataSetChanged()
            }
        }
        cloudSyncDialog!!.show()
    }

    // ------------------------------------------------------------------
    // 设置（二级页）：Dock 管理
    // ------------------------------------------------------------------

    private fun showDockManageContent(settingsBinding: DialogSettingsBinding) {
        try {
            var dialogRef: androidx.appcompat.app.AlertDialog? = null
            val page = DockManagePage(
                inflater = layoutInflater,
                favoritesStore = favoritesStore,
                store = customDockTabsStore,
                playBoundaryShake = { v -> playBoundaryShake(v) },
                onDockTabsChanged = { onCustomDockTabsChanged() },
                onRequestClose = { dialogRef?.dismiss() }
            )
            val view = page.ensureInflated(binding.contentContainer)
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_CastTV_Dialog)
                .setView(view)
                .create()
            dialogRef = dialog
            dockManagePage = page
            dockManageContentView = null
            dialog.setOnShowListener {
                page.configureDialogWindow(dialog)
                page.requestInitialFocus()
            }
            dialog.setOnDismissListener {
                dockManagePage = null
                settingsBinding.btnDockManage.requestFocus()
            }
            dialog.show()
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "showDockManageContent failed", t)
            android.widget.Toast.makeText(this, "首页侧边栏管理暂时无法打开", android.widget.Toast.LENGTH_SHORT).show()
            settingsBinding.btnDockManage.requestFocus()
        }
    }

    /**
     * 从「Dock 管理」返回到设置页。
     * @return 是否实际发生了返回（即当前确实处于 Dock 管理页）。
     */
    private fun hideDockManageContent(restoreFocus: Boolean): Boolean {
        val view = dockManageContentView ?: return false
        if (view.visibility != View.VISIBLE) return false
        view.visibility = View.GONE
        dockManagePage?.setVisible(false)
        settingsContentView?.visibility = View.VISIBLE
        if (restoreFocus) {
            settingsContentBinding?.btnDockManage?.requestFocus()
        }
        return true
    }

    // ------------------------------------------------------------------
    // 自定义 Dock Tab（内容页）
    // ------------------------------------------------------------------

    private fun onCustomDockTabsChanged() {
        // 仅负责刷新 Dock 结构与当前自定义 Tab 选择，不主动改用户当前焦点。
        val tabs = customDockTabsStore.list()

        if (tabs.isEmpty()) {
            currentCustomDockTabId = null
            shownCustomDockTabId = null
            if (currentTab == DockTab.CUSTOM) {
                // 当前正停留在自定义 Tab：配置被清空后避免残留页面。
                customDockTabContentView?.visibility = View.GONE
                customDockTabPage?.stopPreview()
            }
        } else {
            if (currentCustomDockTabId.isNullOrBlank() || tabs.none { it.id == currentCustomDockTabId }) {
                currentCustomDockTabId = tabs.first().id
            }
        }

        setupDock()

        // 若自定义 Tab 内容页已展示，更新标题/绑定信息。
        if (currentTab == DockTab.CUSTOM && customDockTabContentView?.visibility == View.VISIBLE) {
            showCustomDockTabContent()
        }
    }

    private fun showCustomDockTabContent() {
        try {
            val tabs = customDockTabsStore.list()
            val tab = tabs.firstOrNull { it.id == currentCustomDockTabId } ?: tabs.firstOrNull()

            if (tab != null) {
                currentCustomDockTabId = tab.id
            }

            if (customDockTabPage == null) {
                customDockTabPage = CustomDockTabPage(
                    inflater = layoutInflater,
                    favoritesStore = favoritesStore,
                    configStore = tabChannelConfigStore,
                    healthStore = sourceHealthStore,
                    useSurfaceView = settings.useSurfaceView,
                    playBoundaryShake = { v -> playBoundaryShake(v) },
                    onOpenFullscreen = { item ->
                        if (item.uri.isNotBlank()) {
                            customDockTabPage?.stopPreview()
                            launchPlayer(item.uri, item.title, item.source)
                        }
                    }
                )
            }

            val page = customDockTabPage!!
            if (customDockTabContentView == null) {
                customDockTabContentView = page.ensureInflated(binding.contentContainer)
                addFullscreenContent(customDockTabContentView!!)
            }

            customDockTabContentView?.visibility = View.VISIBLE

            if (tab == null) {
                page.bindTab(tabId = "", tabName = "自定义 Tab", collectionId = "")
                return
            }

            page.bindTab(tabId = tab.id, tabName = tab.name, collectionId = tab.collectionId)
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "showCustomDockTabContent failed", t)
            android.widget.Toast.makeText(this, "自定义 Tab 暂时无法打开", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHelpContent() {
        try {
            if (helpContentView == null) {
                val dialogBinding = DialogHelpBinding.inflate(layoutInflater, binding.contentContainer, false)
                helpContentView = dialogBinding.root
                helpContentBinding = dialogBinding
                // v1.1.120：帮助页右侧滚动容器上下滚动，到顶/底再按对应方向键抖动一下、不切换焦点。
                dialogBinding.helpStepsScroll.setOnKeyListener { v, keyCode, event ->
                    handleScrollViewBoundaryShake(v as android.widget.ScrollView, keyCode, event)
                }
                addFullscreenContent(dialogBinding.root)
            }
            helpContentView?.visibility = View.VISIBLE
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "showHelpContent failed", t)
        }
    }

    private fun showDiagnosticsContent() {
        try {
            if (diagnosticsContentView == null) {
                val dialogBinding = com.bd.casttv.databinding.DialogSsdpDiagnosticsBinding
                    .inflate(layoutInflater, binding.contentContainer, false)
                diagnosticsContentView = dialogBinding.root
                diagnosticsContentBinding = dialogBinding
                addFullscreenContent(dialogBinding.root)
                // v1.1.120：工具栏三个可聚焦控件（搜索框 / 复制日志 / 清空日志）的 compound drawable 图标
                // 使用 selector 着色，随各自 focused/selected 状态自动在白色 / 暖黄之间切换。
                val toolbarIconTint = androidx.core.content.ContextCompat.getColorStateList(
                    this, R.color.diag_toolbar_btn_text
                )
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                    dialogBinding.searchDiagnostics, toolbarIconTint
                )
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                    dialogBinding.btnCopyDiagnostics, toolbarIconTint
                )
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                    dialogBinding.btnClearDiagnostics, toolbarIconTint
                )
                // v1.1.120：搜索框实时过滤日志——输入关键词后仅展示包含关键词的行，清空后恢复全量。
                dialogBinding.searchDiagnostics.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        diagFilterKeyword = s?.toString() ?: ""
                        // 有关键词时搜索框进入「选中态」（暖黄图标+文字），提示当前正在过滤。
                        dialogBinding.searchDiagnostics.isSelected = diagFilterKeyword.trim().isNotEmpty()
                        diagnosticsContentBinding?.textDiagnostics?.text = formatDiagnosticsBold(currentDiagnosticsText())
                    }
                })
                // v1.1.123：搜索框默认按 TV 焦点态处理，OK/点击进入编辑态并主动唤起系统键盘；
                // 返回键在键盘弹出时先关闭键盘并退出编辑态，不退出网络诊断页。
                dialogBinding.searchDiagnostics.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                dialogBinding.searchDiagnostics.isCursorVisible = false
                fun enterDiagnosticsSearchEdit() {
                    dialogBinding.searchDiagnostics.requestFocus()
                    dialogBinding.searchDiagnostics.isCursorVisible = true
                    dialogBinding.searchDiagnostics.post {
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(
                            dialogBinding.searchDiagnostics,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
                        )
                    }
                }
                fun exitDiagnosticsSearchEdit(): Boolean {
                    if (!dialogBinding.searchDiagnostics.isCursorVisible) return false
                    dialogBinding.searchDiagnostics.isCursorVisible = false
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(dialogBinding.searchDiagnostics.windowToken, 0)
                    dialogBinding.searchDiagnostics.clearFocus()
                    dialogBinding.searchDiagnostics.requestFocus()
                    return true
                }
                dialogBinding.searchDiagnostics.setOnFocusChangeListener { view, hasFocus ->
                    if (!hasFocus) (view as EditText).isCursorVisible = false
                }
                dialogBinding.searchDiagnostics.setOnClickListener { enterDiagnosticsSearchEdit() }
                dialogBinding.searchDiagnostics.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                                enterDiagnosticsSearchEdit()
                                return@setOnKeyListener true
                            }
                            KeyEvent.KEYCODE_BACK -> {
                                if (exitDiagnosticsSearchEdit()) return@setOnKeyListener true
                            }
                        }
                    }
                    false
                }
                // v1.1.105：网络诊断内容可上下滚动，滚到顶/底再按对应方向键时抖动一下、不首尾循环。
                dialogBinding.diagnosticsScroll.setOnKeyListener { v, keyCode, event ->
                    handleScrollViewBoundaryShake(v as android.widget.ScrollView, keyCode, event)
                }
                // v1.1.120：复制日志——无关键词复制全量、有关键词仅复制过滤结果，复制成功 Toast 提示。
                dialogBinding.btnCopyDiagnostics.setOnClickListener {
                    try {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("diagnostics", currentDiagnosticsText())
                        )
                        android.widget.Toast.makeText(
                            this, R.string.diag_copy_toast, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } catch (t: Throwable) {
                        android.util.Log.e("MainActivity", "copy diagnostics failed", t)
                    }
                }
                // v1.1.118：「清空日志」按钮，一键清空所有诊断日志（M-SEARCH / 200 OK / description GET / 云同步 / 投屏事件流水）。
                dialogBinding.btnClearDiagnostics.setOnClickListener {
                    com.bd.casttv.dlna.SsdpDiagnostics.clearAll()
                    diagnosticsContentBinding?.textDiagnostics?.text = formatDiagnosticsBold(currentDiagnosticsText())
                    android.widget.Toast.makeText(this, "已清空日志", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            diagnosticsContentView?.visibility = View.VISIBLE
            // First paint + periodic refresh while visible.
            diagnosticsContentBinding?.textDiagnostics?.text = formatDiagnosticsBold(currentDiagnosticsText())
            binding.root.removeCallbacks(diagnosticsRefreshTask)
            binding.root.post(diagnosticsRefreshTask)
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "showDiagnosticsContent failed", t)
        }
    }

    // ------------------------------------------------------------------
    // 程序坞（Dock）一级菜单 + 全屏内容区导航
    // ------------------------------------------------------------------

    private fun ensureDockItemVisible(view: View, smooth: Boolean = false) {
        val dockBar = binding.dockBar
        dockBar.post {
            val rect = android.graphics.Rect()
            view.getDrawingRect(rect)
            dockBar.offsetDescendantRectToMyCoords(view, rect)
            if (smooth) {
                dockBar.smoothScrollTo(0, (rect.top - dp(12)).coerceAtLeast(0))
            } else {
                dockBar.requestChildRectangleOnScreen(view, rect, false)
            }
        }
    }

    /** 初始化 Dock：绑定焦点联动（焦点到哪个入口，就切到对应内容）与点击进入内容区。 */
    private fun setupDock() {
        // 1) 动态渲染自定义 Tab（插入到「收藏」后、「手机」前）。
        val customTabs = customDockTabsStore.list()
        if (currentCustomDockTabId.isNullOrBlank() || customTabs.none { it.id == currentCustomDockTabId }) {
            currentCustomDockTabId = customTabs.firstOrNull()?.id
        }
        binding.dockCustomTabsContainer.removeAllViews()
        val customViews = ArrayList<View>(customTabs.size)
        val customDecors = ArrayList<DockItemDecor>(customTabs.size)
        val customIds = ArrayList<String>(customTabs.size)
        customTabs.forEach { tab ->
            val v = layoutInflater.inflate(R.layout.item_dock_custom, binding.dockCustomTabsContainer, false)
            val glow = v.findViewById<GlowUnderlineView>(R.id.dockCustomGlow)
            val icon = v.findViewById<ImageView>(R.id.dockCustomIcon)
            val label = v.findViewById<TextView>(R.id.dockCustomLabel)
            label.text = tab.name
            icon.setImageResource(CustomDockIconPresets.iconFor(tab.iconKey).drawableRes)
            binding.dockCustomTabsContainer.addView(v)
            customViews.add(v)
            customDecors.add(DockItemDecor(glow, icon, label))
            customIds.add(tab.id)
        }

        dockItemViews =
            listOf(binding.dockHome, binding.dockFavorites) +
                customViews +
                listOf(
                    binding.dockPhone,
                    binding.dockHistory,
                    binding.dockSettings,
                    binding.dockHelp,
                    binding.dockDiagnostics
                )

        dockTabs =
            listOf(DockTab.HOME, DockTab.FAVORITES) +
                List(customViews.size) { DockTab.CUSTOM } +
                listOf(DockTab.PHONE, DockTab.HISTORY, DockTab.SETTINGS, DockTab.HELP, DockTab.DIAGNOSTICS)

        dockCustomTabIds =
            listOf<String?>(null, null) +
                customIds +
                listOf(null, null, null, null, null)

        // 2) v1.1.80 焦点态改造：Dock 每一项内含 GlowUnderlineView。
        val dockDecors =
            listOf(
                DockItemDecor(binding.dockHomeGlow, binding.dockHomeIcon, binding.dockHomeLabel),
                DockItemDecor(binding.dockFavoritesGlow, binding.dockFavoritesIcon, binding.dockFavoritesLabel),
            ) +
                customDecors +
                listOf(
                    DockItemDecor(binding.dockPhoneGlow, binding.dockPhoneIcon, binding.dockPhoneLabel),
                    DockItemDecor(binding.dockHistoryGlow, binding.dockHistoryIcon, binding.dockHistoryLabel),
                    DockItemDecor(binding.dockSettingsGlow, binding.dockSettingsIcon, binding.dockSettingsLabel),
                    DockItemDecor(binding.dockHelpGlow, binding.dockHelpIcon, binding.dockHelpLabel),
                    DockItemDecor(binding.dockDiagnosticsGlow, binding.dockDiagnosticsIcon, binding.dockDiagnosticsLabel)
                )

        this.dockItemDecors = dockDecors
        dockDecors.forEach { decor ->
            decor.glow.isFocusable = false
            decor.glow.isClickable = false
            // 焦点态由 GlowUnderlineView 承载，不再显示常态下划线。
            decor.glow.setIdleLineVisible(false)
        }

        // 3) 绑定焦点联动（焦点到哪个入口，就切到对应内容）与点击进入内容区。
        dockItemViews.forEachIndexed { index, view ->
            val tab = dockTabs[index]
            val decor = dockDecors[index]
            val customId = dockCustomTabIds[index]

            val selected =
                if (tab == DockTab.CUSTOM) currentTab == DockTab.CUSTOM && customId == currentCustomDockTabId
                else tab == currentTab

            if (view.isSelected != selected) view.isSelected = selected
            // 首次进入统一刷新一次，防止 selectTab 提前 return 时视觉残缺。
            applyDockDecorState(decor, view.isSelected, view.isFocused)

            // 焦点变化即切换内容（类似 tab，无需点击）——遵循 TV「切换即刷新」交互。
            view.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    cancelDockCollapse()
                    setDockCollapsed(false)
                    ensureDockItemVisible(view)
                    contentEntered = false
                    if (tab == DockTab.CUSTOM && !customId.isNullOrBlank()) {
                        selectCustomDockTab(customId)
                    } else {
                        selectTab(tab)
                    }
                }
                applyDockDecorState(decor, view.isSelected, hasFocus)
            }

            // 点击（OK）则进入内容区（二级菜单）。
            view.setOnClickListener {
                if (tab == DockTab.CUSTOM && !customId.isNullOrBlank()) {
                    selectCustomDockTab(customId)
                } else {
                    selectTab(tab)
                }
                enterContentArea()
            }
        }
    }

    /** Dock 一项的装饰控件（光晕 + 图标 + 文字）。 */
    private data class DockItemDecor(
        val glow: GlowUnderlineView,
        val icon: ImageView,
        val label: TextView
    )

    private var dockItemDecors: List<DockItemDecor> = emptyList()

    /** 同帧刷新 Dock 一项：选中态只改图标/文字色；下划线/光晕仅随焦点显示。 */
    private fun applyDockDecorState(decor: DockItemDecor, selected: Boolean, focused: Boolean) {
        // Dock 三态严格解耦：GlowUnderlineView 的 selected 会绘制下划线，因此这里只传 focused。
        decor.glow.applyVisualState(selected = focused, active = focused)
        decor.glow.visibility = if (focused) View.VISIBLE else View.GONE
        decor.glow.alpha = if (focused) 1f else 0f
        val tint = if (selected) {
            ContextCompat.getColor(this, R.color.crayon_yellow)
        } else {
            ContextCompat.getColor(this, R.color.text_primary)
        }
        decor.icon.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        decor.label.setTextColor(tint)
    }

    private fun scheduleDockCollapse() {
        ui.removeCallbacks(collapseDockRunnable)
        ui.postDelayed(collapseDockRunnable, 2500L)
    }

    private fun cancelDockCollapse() {
        ui.removeCallbacks(collapseDockRunnable)
    }

    private fun setDockCollapsed(collapsed: Boolean) {
        if (isDockCollapsed == collapsed) return
        isDockCollapsed = collapsed
        val targetWidth = dp(if (collapsed) 72 else 158)
        val targetRightMargin = dp(if (collapsed) 6 else 10)
        (binding.dockBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.width = targetWidth
            lp.rightMargin = targetRightMargin
            binding.dockBar.layoutParams = lp
        }
        dockItemDecors.forEach { decor ->
            decor.label.visibility = if (collapsed) View.GONE else View.VISIBLE
            (decor.icon.parent as? android.widget.LinearLayout)?.apply {
                gravity = if (collapsed) Gravity.CENTER else Gravity.CENTER_VERTICAL
                setPadding(if (collapsed) 0 else dp(12), 0, if (collapsed) 0 else dp(10), 0)
            }
        }
        applyContentExpandedRatio(if (collapsed) 1.08f else 1f)
        dockItemViews.forEachIndexed { index, item ->
            applyDockDecorState(dockItemDecors[index], item.isSelected, item.isFocused)
        }
    }

    private fun applyContentExpandedRatio(ratio: Float) {
        val content = currentContentView() ?: binding.contentContainer.getChildAt(0) ?: return
        val original = contentOriginalPadding.getOrPut(content) {
            intArrayOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom)
        }
        content.setPadding(
            (original[0] / ratio).toInt(),
            (original[1] / ratio).toInt(),
            (original[2] / ratio).toInt(),
            (original[3] / ratio).toInt()
        )
    }

    private fun expandDockAndFocusSelected() {
        cancelDockCollapse()
        setDockCollapsed(false)
        binding.dockBar.post {
            val index = dockItemViews.indexOfFirst { it.isSelected }.takeIf { it >= 0 } ?: 0
            dockItemViews.getOrNull(index)?.requestFocus()
        }
    }

    private fun selectCustomDockTab(tabId: String) {
        currentCustomDockTabId = tabId
        selectTab(DockTab.CUSTOM)
    }

    /** 切换一级菜单：更新 Dock 选中态并切换上方内容区。 */
    private fun selectTab(tab: DockTab) {
        currentTab = tab

        // 1) Dock 选中态（含自定义 Tab 的逐项选中态）。
        dockItemViews.forEachIndexed { i, v ->
            val itemTab = dockTabs[i]
            val customId = dockCustomTabIds[i]
            val selected =
                if (itemTab == DockTab.CUSTOM) tab == DockTab.CUSTOM && customId == currentCustomDockTabId
                else itemTab == tab

            if (v.isSelected != selected) v.isSelected = selected
            if (selected) ensureDockItemVisible(v, smooth = !v.isFocused)
            // v1.1.80：同步刷新 Dock 装饰层的暖黄下划线 + 光晕、图标 tint、文字色。
            dockItemDecors.getOrNull(i)?.let { applyDockDecorState(it, selected, v.isFocused) }
        }

        // 2) 内容区渲染去重：自定义 Tab 需要同时比较 tabId。
        val nextShownCustomId = if (tab == DockTab.CUSTOM) currentCustomDockTabId else null
        if (shownTab == tab && shownCustomDockTabId == nextShownCustomId) return

        val oldTab = shownTab
        shownTab = tab
        shownCustomDockTabId = nextShownCustomId
        showContent(tab, oldTab)
    }

    /** 显示指定 Tab 的全屏内容，隐藏其余内容。 */
    private fun showContent(tab: DockTab, oldTab: DockTab?) {
        binding.contentHome.visibility = if (tab == DockTab.HOME) View.VISIBLE else View.GONE
        if (tab != DockTab.FAVORITES) favoritesContentView?.visibility = View.GONE
        if (tab != DockTab.CUSTOM) {
            customDockTabContentView?.visibility = View.GONE
            customDockTabPage?.stopPreview()
        }
        if (tab != DockTab.PHONE) phoneContentView?.visibility = View.GONE
        if (tab != DockTab.HISTORY) historyContentView?.visibility = View.GONE
        if (tab != DockTab.SETTINGS) {
            settingsContentView?.visibility = View.GONE
            dockManageContentView?.visibility = View.GONE
            dockManagePage?.setVisible(false)
        }
        if (tab != DockTab.HELP) helpContentView?.visibility = View.GONE
        if (tab != DockTab.DIAGNOSTICS) {
            diagnosticsContentView?.visibility = View.GONE
            binding.root.removeCallbacks(diagnosticsRefreshTask)
        }
        when (tab) {
            DockTab.HOME -> refreshUi()
            DockTab.FAVORITES -> showFavoritesContent()
            DockTab.CUSTOM -> showCustomDockTabContent()
            DockTab.PHONE -> showPhoneContent()
            DockTab.HISTORY -> showHistoryContent()
            DockTab.SETTINGS -> showSettingsContent()
            DockTab.HELP -> showHelpContent()
            DockTab.DIAGNOSTICS -> showDiagnosticsContent()
        }
        if (oldTab != null) {
            currentContentView()?.let { view ->
                view.animate().cancel()
                view.alpha = 0.92f
                view.animate().alpha(1f).setDuration(90L).start()
            }
        }
        applyContentExpandedRatio(if (isDockCollapsed) 1.08f else 1f)
    }

    /** 把内容视图以全屏（match_parent）方式加入内容容器。 */
    private fun addFullscreenContent(view: View) {
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        binding.contentContainer.addView(view, lp)
    }

    /** 当前 Tab 对应的内容视图（用于 Dock → 内容区的焦点跳转）。 */
    private fun currentContentView(): View? = when (currentTab) {
        // v1.1.23：主页投屏「播放中」态支持 mini 播放卡片聚焦/OK 续播，因此主页也需要返回内容根。
        DockTab.HOME -> binding.contentHome
        DockTab.FAVORITES -> favoritesContentView
        DockTab.CUSTOM -> customDockTabContentView
        DockTab.PHONE -> phoneContentView
        DockTab.HISTORY -> historyContentView
            DockTab.SETTINGS -> if (dockManageContentView?.visibility == View.VISIBLE) {
                dockManageContentView
            } else {
                settingsContentView
            }
            DockTab.HELP -> helpContentView
            DockTab.DIAGNOSTICS -> diagnosticsContentView
    }

    /**
     * 从 Dock 进入内容区（二级菜单）：把焦点落到当前内容的第一个可聚焦元素上。
     * @return 是否成功进入（无可聚焦元素时返回 false）。
     */
    private fun enterContentArea(): Boolean {
        val content = currentContentView() ?: return false
        // 历史列表需等待 RecyclerView 完成布局后再抢焦点。
        if (currentTab == DockTab.HISTORY) {
            val list = historyContentBinding?.historyList
            if (list != null && historyAdapter.itemCount > 0) {
                contentEntered = true
                scheduleDockCollapse()
                focusFirstHistoryItem(list)
                return true
            }
        }
        // 自定义 Tab：默认落到资源列表（首条）。
        if (currentTab == DockTab.CUSTOM) {
            showCustomDockTabContent()
            contentEntered = true
            scheduleDockCollapse()
            customDockTabPage?.requestInitialFocus()
            return true
        }

        // 收藏：按 OK/右键进入内容区时，优先聚焦「默认合集」，不触发「新建合集」抢焦点。
        if (currentTab == DockTab.FAVORITES) {
            val dialog = favoritesDialogBinding
            val sidebarList = dialog?.sidebarCollectionList
            val adapter = sidebarAdapter
            if (sidebarList != null && adapter != null && adapter.itemCount > 0) {
                val defaultId = favoritesStore.defaultCollectionId()
                val targetPos = adapter.positionOf(defaultId).takeIf { it >= 0 } ?: 0
                contentEntered = true
                scheduleDockCollapse()
                sidebarList.scrollToPosition(targetPos)
                sidebarList.post {
                    val vh = sidebarList.findViewHolderForAdapterPosition(targetPos)
                    if (vh != null) {
                        vh.itemView.requestFocus()
                    } else {
                        sidebarList.post {
                            sidebarList.findViewHolderForAdapterPosition(targetPos)?.itemView?.requestFocus()
                                ?: sidebarList.requestFocus()
                        }
                    }
                }
                return true
            }
        }
        val target = findFirstFocusable(content)
        return if (target != null) {
            contentEntered = true
            scheduleDockCollapse()
            target.post { target.requestFocus() }
            true
        } else {
            false
        }
    }

    /** 返回 Dock：把焦点还给当前一级菜单入口。 */
    private fun focusCurrentDockItem() {
        contentEntered = false
        val idx = if (currentTab == DockTab.CUSTOM) {
            val targetId = currentCustomDockTabId
            if (targetId.isNullOrBlank()) -1 else dockCustomTabIds.indexOf(targetId)
        } else {
            dockTabs.indexOf(currentTab)
        }
        cancelDockCollapse()
        setDockCollapsed(false)
        binding.dockBar.post { dockItemViews.getOrNull(idx.coerceAtLeast(0))?.requestFocus() }
    }

    /** 当前焦点是否位于一级 Dock 任意入口（含入口内部子 View）。 */
    private fun isFocusInsideDock(): Boolean {
        if (!::dockItemViews.isInitialized) return false
        val focused = currentFocus ?: return false
        return dockItemViews.any { dock -> focused === dock || isDescendantOf(focused, dock) }
    }

    /** Dock 上按返回键时显示退出确认，取消后把焦点还给触发弹窗的 Dock item。 */
    private fun showExitConfirmDialog(restoreFocus: View?) {
        if (isFinishing || isDestroyed) return
        exitConfirmDialog?.takeIf { it.isShowing }?.let { return }
        // v1.1.105：退出确认改用暖色蜡笔主题自定义视图，并复用「新建合集」页同款贴纸装饰。
        val exitBinding = com.bd.casttv.databinding.DialogExitConfirmBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(exitBinding.root)
            .create()
        exitConfirmDialog = dialog
        exitBinding.btnExitConfirm.setOnClickListener {
            dialog.dismiss()
            finish()
        }
        exitBinding.btnExitCancel.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            if (exitConfirmDialog === dialog) exitConfirmDialog = null
            if (!isFinishing && !isDestroyed) {
                restoreFocus?.post { restoreFocus.requestFocus() }
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            exitBinding.btnExitCancel.requestFocus()
        }
        dialog.show()
    }

    // ===== v1.1.71 收藏页三级导航返回逻辑收敛 =====
    /** 判断 [view] 是否为 [ancestor] 的后代（含自身）。 */
    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    /**
     * 当前焦点是否落在收藏页右侧「三级内容区」(contentPanel) 内。
     * 仅在收藏 Tab 且焦点确实位于内容面板时为 true；二级合集 tab（sidebar）不算三级区。
     * 用于在三级区拦截 BACK / DPAD_LEFT，使其收敛回二级合集 tab，而非穿透到一级 Dock。
     */
    private fun isFocusInFavoritesContentPanel(): Boolean {
        if (currentTab != DockTab.FAVORITES) return false
        val panel = favoritesDialogBinding?.contentPanel ?: return false
        val focused = currentFocus ?: return false
        return isDescendantOf(focused, panel)
    }

    // ===== v1.1.72 收藏页二级导航返回一级 Dock「收藏」入口 =====
    /**
     * 当前焦点是否落在收藏页左侧「二级导航」(sidebarPanel) 内。
     * 覆盖：合集 tab 列表(sidebarCollectionList)、新建合集(btnSidebarNewCollection)、
     * 导入(btnFavImport)、导出(btnFavExport)。仅收藏 Tab 且焦点确实在 sidebarPanel 内时为 true。
     * 用于在二级导航拦截 BACK / DPAD_LEFT，明确回退到一级 Dock「收藏」入口（而非几何搜索误落到相邻 Dock）。
     */
    private fun isFocusInFavoritesSidebarPanel(): Boolean {
        if (currentTab != DockTab.FAVORITES) return false
        val panel = favoritesDialogBinding?.sidebarPanel ?: return false
        val focused = currentFocus ?: return false
        return isDescendantOf(focused, panel)
    }

    /**
     * 把焦点明确收敛回一级 Dock「收藏」入口，并保持其选中高亮。
     * 不依赖几何 focusSearch（会误落到上下相邻的 Dock 项），也不依赖 contentEntered 状态是否准确。
     * @return 是否成功发起聚焦。
     */
    private fun focusDockFavoritesTab(): Boolean {
        if (!::dockItemViews.isInitialized || !::dockTabs.isInitialized) return false
        val idx = dockTabs.indexOf(DockTab.FAVORITES)
        val dockView = dockItemViews.getOrNull(idx) ?: return false
        contentEntered = false
        // 主动兜底刷新选中态：selectTab 在同 Tab 时会提前 return，这里确保「收藏」一级 tab 高亮。
        dockItemViews.forEachIndexed { i, v ->
            val selected = dockTabs[i] == DockTab.FAVORITES
            if (v.isSelected != selected) v.isSelected = selected
        }
        ensureDockItemVisible(dockView)
        dockView.post { dockView.requestFocus() }
        return true
    }

    /**
     * 把焦点明确收敛回一级 Dock「主页」入口，并保持其选中高亮。
     * 用于删除/移动/点赞等操作重试失败后的兜底，逻辑与 focusDockFavoritesTab 对称。
     * @return 是否成功发起聚焦。
     */
    private fun focusDockHomeTab(): Boolean {
        if (!::dockItemViews.isInitialized || !::dockTabs.isInitialized) return false
        val idx = dockTabs.indexOf(DockTab.HOME)
        val dockView = dockItemViews.getOrNull(idx) ?: return false
        contentEntered = false
        // 主动兜底刷新选中态：selectTab 在同 Tab 时会提前 return，这里确保「主页」一级 tab 高亮。
        dockItemViews.forEachIndexed { i, v ->
            val selected = dockTabs[i] == DockTab.HOME
            if (v.isSelected != selected) v.isSelected = selected
        }
        ensureDockItemVisible(dockView)
        dockView.post { dockView.requestFocus() }
        return true
    }

    /**
     * v1.1.124：焦点兜底 —— 删除/移动/点赞等操作后，若多档重试仍让焦点逃逸到非收藏区域
     * （含焦点为空 / 落到一级 Dock 其它入口），则强制把焦点落到 Dock「主页」tab。
     * 已稳定落在收藏二级导航 sidebar 或收藏内容区时不干预。
     */
    private fun fallbackFocusToDockHomeIfEscaped() {
        if (favoritesDialogBinding == null) return
        // 已稳定落在二级导航 sidebar 或收藏内容区 → 不干预。
        if (isFocusInFavoritesSidebarPanel() || isFocusInFavoritesContentPanel()) return
        // 其它情况（焦点为空 / 落到一级 Dock 其它入口 / 其它非收藏区域）→ 强制落到 Dock「主页」。
        focusDockHomeTab()
    }

    /**
     * v1.1.105：不移动焦点，仅确保一级 Dock「收藏」入口保持选中高亮（暖黄文字/图标）。
     * 用于「解析直播源」等独立弹窗弹出/关闭期间，避免 Dock「收藏」选中态被丢失。
     */
    private fun ensureDockFavoritesSelected() {
        if (!::dockItemViews.isInitialized || !::dockTabs.isInitialized) return
        if (currentTab != DockTab.FAVORITES) return
        dockItemViews.forEachIndexed { i, v ->
            val selected = dockTabs[i] == DockTab.FAVORITES
            if (v.isSelected != selected) v.isSelected = selected
            dockItemDecors.getOrNull(i)?.let { applyDockDecorState(it, selected, v.isFocused) }
        }
    }

    /**
     * 把焦点收敛回当前选中的二级合集 tab（sidebarCollectionList 中 currentCollectionId 对应项）。
     * 只回到当前 isSelected=true 的合集项；找不到选中项时不默认落到第一项，避免返回层级时焦点跳错。
     * @return 是否成功发起聚焦。
     */
    private fun focusCurrentFavoriteCollectionTab(): Boolean {
        val dialog = favoritesDialogBinding ?: return false
        val sidebarList = dialog.sidebarCollectionList
        val adapter = sidebarAdapter ?: return false
        if (adapter.itemCount == 0) return false
        val targetPos = adapter.positionOf(currentCollectionId).takeIf { it >= 0 } ?: return false
        // 焦点回到二级 sidebar，仍属于「已进入内容区」状态：再按一次 BACK 才回一级 Dock。
        contentEntered = true
        sidebarList.scrollToPosition(targetPos)
        // v1.1.124：收藏页是 inline 加到 Activity 布局的，一级 Dock 在同一层级且更靠前；
        // renderSidebar 走 notifyDataSetChanged，ViewHolder 布局是异步的（还伴随 item 动画），
        // 过早 requestFocus 会失败，导致系统默认 focusSearch 把焦点逃逸到靠前的一级 Dock。
        // 这里用「多档延时重试 + 每档校验 hasFocus 抢回 + 面板内兜底」确保焦点稳定落在当前选中 tab。
        focusSidebarTabWithRetry(sidebarList, targetPos, intArrayOf(0, 80, 180, 320), 0)
        // v1.1.124：所有档位（0/80/180/320）重试完成后再做一次兜底：若焦点仍逃逸到非收藏区，
        // 则强制落到 Dock「主页」。
        sidebarList.postDelayed({ fallbackFocusToDockHomeIfEscaped() }, 420)
        return true
    }

    /**
     * v1.1.124：逐档延时重试聚焦 sidebar 指定位置的 tab。
     * 每档若发现目标 ViewHolder 已布局完成且未持有焦点（可能被 Dock 抢走）则抢回；
     * 所有档位仍拿不到 ViewHolder 时，兜底把焦点留在 sidebar 列表内，绝不让其回退到一级 Dock。
     */
    private fun focusSidebarTabWithRetry(
        sidebarList: androidx.recyclerview.widget.RecyclerView,
        targetPos: Int,
        delays: IntArray,
        index: Int
    ) {
        if (index >= delays.size) return
        val attempt = Runnable {
            if (favoritesDialogBinding == null) return@Runnable
            val target = sidebarList.findViewHolderForAdapterPosition(targetPos)?.itemView
            when {
                target != null && target.isFocusable && target.isShown -> {
                    if (!target.hasFocus()) target.requestFocus()
                    // 已定位到目标：仍继续后续档位，用于抢回被其它逻辑（如窗口焦点恢复）夺走的焦点。
                    if (index + 1 < delays.size) {
                        focusSidebarTabWithRetry(sidebarList, targetPos, delays, index + 1)
                    }
                }
                index + 1 < delays.size -> focusSidebarTabWithRetry(sidebarList, targetPos, delays, index + 1)
                else -> if (!sidebarList.hasFocus()) sidebarList.requestFocus()
            }
        }
        if (delays[index] <= 0) sidebarList.post(attempt) else sidebarList.postDelayed(attempt, delays[index].toLong())
    }

    /** 深度优先查找第一个可见且可聚焦的视图（优先子元素，如 RecyclerView 内的按钮）。 */
    private fun findFirstFocusable(root: View): View? {
        if (root.visibility != View.VISIBLE) return null
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findFirstFocusable(root.getChildAt(i))
                if (found != null) return found
            }
            if (root.isFocusable) return root
            return null
        }
        return if (root.isFocusable && root.isEnabled) root else null
    }

    // ===== 前台空闲海报墙触发（5 分钟无交互） =====
    /** 重置空闲计时：任何用户交互 / onResume 都会调用。 */
    private fun scheduleIdleTimer() {
        ui.removeCallbacks(idleTimerTask)
        ui.postDelayed(idleTimerTask, IDLE_TIMEOUT_MS)
    }

    /** 取消空闲计时：Activity 不在前台（onPause/onStop/onDestroy）时调用。 */
    private fun cancelIdleTimer() {
        ui.removeCallbacks(idleTimerTask)
    }

    /** 空闲到点：非播放/续播态才拉起海报墙（[PlaybackController.currentUri] 为双保险）。 */
    private fun triggerPosterWallIfIdle() {
        if (isFinishing || isDestroyed) return
        if (PlaybackController.currentUri.isNotBlank()) return
        startActivity(Intent(this, PosterWallActivity::class.java))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        scheduleIdleTimer() // 任何按键都重置空闲计时
        if (event.action == KeyEvent.ACTION_DOWN) {
            val onDock = ::dockItemViews.isInitialized && dockItemViews.any { it.isFocused }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    // 焦点在左侧侧边栏 Dock 上时，右键进入内容区（二级菜单）。
                    // v1.1.23：主页在「播放中」态也有可聚焦的 mini 播放卡片，因此不再一律吞掉。
                    if (onDock) {
                        // 无可聚焦元素（例如主页无投屏会话）时仍消费按键，避免焦点跑丢。
                        enterContentArea()
                        return true
                    }
                    // v1.1.120：网络诊断工具栏横向切换 + 边界抖动。
                    // 搜索框 → 复制日志 → 清空日志；清空日志已到最右边界抖动；文本容器内右键抖动不切换焦点。
                    if (currentTab == DockTab.DIAGNOSTICS && contentEntered) {
                        val b = diagnosticsContentBinding
                        val focused = currentFocus
                        if (b != null && focused != null) {
                            when {
                                focused === b.searchDiagnostics -> { b.btnCopyDiagnostics.requestFocus(); return true }
                                focused === b.btnCopyDiagnostics -> { b.btnClearDiagnostics.requestFocus(); return true }
                                focused === b.btnClearDiagnostics -> { playBoundaryShake(b.diagToolbar); return true }
                                isDescendantOf(focused, b.diagnosticsScroll) -> { playBoundaryShake(b.diagnosticsScroll); return true }
                            }
                        }
                    }
                    // v1.1.120：帮助页右侧滚动容器右键离开容器 → 回一级 Dock「帮助」。
                    if (currentTab == DockTab.HELP && contentEntered) {
                        val scroll = helpContentBinding?.helpStepsScroll
                        val focused = currentFocus
                        if (scroll != null && focused != null && isDescendantOf(focused, scroll)) {
                            binding.dockHelp.requestFocus()
                            contentEntered = false
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // v1.1.121：网络诊断工具栏横向反向链路：清空日志 → 复制日志 → 搜索框；
                    // 搜索框非编辑态按左键返回一级 Dock「网络诊断」。
                    if (currentTab == DockTab.DIAGNOSTICS && contentEntered) {
                        val b = diagnosticsContentBinding
                        val focused = currentFocus
                        if (b != null && focused != null) {
                            when {
                                focused === b.btnClearDiagnostics -> { b.btnCopyDiagnostics.requestFocus(); return true }
                                focused === b.btnCopyDiagnostics -> { b.searchDiagnostics.requestFocus(); return true }
                                focused === b.searchDiagnostics && !b.searchDiagnostics.isCursorVisible -> {
                                    binding.dockDiagnostics.requestFocus()
                                    contentEntered = false
                                    return true
                                }
                            }
                        }
                    }
                    // v1.1.72：收藏页二级导航（合集 tab / 新建合集 / 导入 / 导出）左键明确回一级 Dock「收藏」，
                    // 不依赖几何 focusSearch（会误落到上下相邻的 Dock 项）。
                    if (isFocusInFavoritesSidebarPanel()) {
                        focusDockFavoritesTab()
                        return true
                    }
                    // v1.1.71：收藏页三级内容区，左键仅在「已到内容区最左侧边缘」时才收敛回二级合集 tab，
                    // 避免穿透到一级 Dock；仍保留卡片内 / 两列网格间的正常左键导航。
                    if (isFocusInFavoritesContentPanel()) {
                        val focused = currentFocus
                        val panel = favoritesDialogBinding?.contentPanel
                        if (focused != null && panel != null) {
                            val next = focused.focusSearch(View.FOCUS_LEFT)
                            if (next == null || !isDescendantOf(next, panel)) {
                                focusCurrentFavoriteCollectionTab()
                                return true
                            }
                        }
                    }
                    // 自定义 Tab 内容区：左键仅在「已到内容区最左侧边缘」时才回到 Dock，
                    // 避免预览区 LEFT 误穿透到 Dock。
                    if (currentTab == DockTab.CUSTOM && contentEntered) {
                        val focused = currentFocus
                        val panel = customDockTabContentView
                        if (focused != null && panel != null && panel.visibility == View.VISIBLE &&
                            isDescendantOf(focused, panel)
                        ) {
                            val next = focused.focusSearch(View.FOCUS_LEFT)
                            if (next == null || !isDescendantOf(next, panel)) {
                                focusCurrentDockItem()
                                return true
                            }
                        }
                    }
                    // 设置页二级页面导航：
                    // - Dock 管理页：LEFT 优先在页内左右切换；若无可去焦点则返回设置页
                    // - 设置页本身：LEFT 回一级 Dock「设置」
                    if (currentTab == DockTab.SETTINGS && contentEntered) {
                        val focused = currentFocus
                        val dockManageRoot = dockManageContentView
                        if (dockManageRoot != null && dockManageRoot.visibility == View.VISIBLE && focused != null &&
                            isDescendantOf(focused, dockManageRoot)
                        ) {
                            val next = focused.focusSearch(View.FOCUS_LEFT)
                            if (next != null && isDescendantOf(next, dockManageRoot)) {
                                return super.dispatchKeyEvent(event)
                            }
                            hideDockManageContent(restoreFocus = true)
                            return true
                        }
                        val settingsRoot = settingsContentView
                        if (settingsRoot != null && focused != null && isDescendantOf(focused, settingsRoot)) {
                            binding.dockSettings.requestFocus()
                            contentEntered = false
                            return true
                        }
                    }
                    // v1.1.106：网络诊断日志文本容器左键回一级 Dock「网络诊断」。
                    // 工具栏内部左右链路已在上方单独处理，避免搜索框编辑态被误打断。
                    if (currentTab == DockTab.DIAGNOSTICS && contentEntered) {
                        val scroll = diagnosticsContentBinding?.diagnosticsScroll
                        val focused = currentFocus
                        if (scroll != null && focused != null && isDescendantOf(focused, scroll)) {
                            binding.dockDiagnostics.requestFocus()
                            contentEntered = false
                            return true
                        }
                    }
                    // v1.1.120：帮助页右侧滚动容器左键离开容器 → 回一级 Dock「帮助」。
                    if (currentTab == DockTab.HELP && contentEntered) {
                        val helpRoot = helpContentView
                        val focused = currentFocus
                        if (helpRoot != null && focused != null && isDescendantOf(focused, helpRoot)) {
                            binding.dockHelp.requestFocus()
                            contentEntered = false
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // v1.1.120：网络诊断工具栏（搜索框 / 复制 / 清空）上/下键处理：
                    // 上键在顶部触发边界抖动、不切换焦点；下键进入下方日志文本容器。
                    if (currentTab == DockTab.DIAGNOSTICS && contentEntered) {
                        val b = diagnosticsContentBinding
                        val focused = currentFocus
                        if (b != null && focused != null && isDescendantOf(focused, b.diagToolbar)) {
                            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                                playBoundaryShake(b.diagToolbar)
                                return true
                            } else {
                                b.diagnosticsScroll.requestFocus()
                                return true
                            }
                        }
                    }
                    // v1.1.121：网络诊断日志文本容器上键分两段：未到顶继续滚动；已到顶再上键回工具栏搜索框。
                    if (currentTab == DockTab.DIAGNOSTICS && contentEntered) {
                        val b = diagnosticsContentBinding
                        val scroll = b?.diagnosticsScroll
                        val focused = currentFocus
                        if (b != null && scroll != null && focused != null && isDescendantOf(focused, scroll)) {
                            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && event.action == KeyEvent.ACTION_DOWN && scroll.scrollY <= 0) {
                                b.searchDiagnostics.isCursorVisible = false
                                b.searchDiagnostics.requestFocus()
                                return true
                            }
                            if (handleScrollViewBoundaryShake(scroll, event.keyCode, event)) return true
                        }
                    }
                    // v1.1.120：帮助页右侧滚动容器上/下键在顶/底边界触发抖动、不切换焦点。
                    if (currentTab == DockTab.HELP && contentEntered) {
                        val scroll = helpContentBinding?.helpStepsScroll
                        val focused = currentFocus
                        if (scroll != null && focused != null && isDescendantOf(focused, scroll)) {
                            if (handleScrollViewBoundaryShake(scroll, event.keyCode, event)) return true
                        }
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    // v1.1.123：网络诊断搜索框处于编辑态时，BACK 优先关闭系统键盘并退出编辑态，不能退出整页。
                    diagnosticsContentBinding?.let { b ->
                        if (currentTab == DockTab.DIAGNOSTICS && contentEntered && b.searchDiagnostics.isCursorVisible) {
                            b.searchDiagnostics.isCursorVisible = false
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.hideSoftInputFromWindow(b.searchDiagnostics.windowToken, 0)
                            b.searchDiagnostics.requestFocus()
                            return true
                        }
                    }
                    if (isFocusInsideDock()) {
                        showExitConfirmDialog(currentFocus)
                        return true
                    }
                    // v1.1.72：收藏页二级导航（合集 tab / 新建合集 / 导入 / 导出）BACK 明确回一级 Dock「收藏」，
                    // 保证高亮 + 焦点落在收藏入口，不穿透、不误落其它 Dock 项。
                    if (isFocusInFavoritesSidebarPanel()) {
                        focusDockFavoritesTab()
                        return true
                    }
                    // v1.1.71：收藏页三级内容区，BACK 收敛回二级合集 tab，严禁直接跳到一级 Dock。
                    // 改名/移动/删除弹窗是独立 Dialog 窗口，自行消费 BACK，此处不受影响。
                    if (isFocusInFavoritesContentPanel()) {
                        if (focusCurrentFavoriteCollectionTab()) return true
                    }
                    // 设置页二级：Dock 管理页 BACK 先回到设置页，不直接回 Dock。
                    if (currentTab == DockTab.SETTINGS && contentEntered) {
                        val focused = currentFocus
                        val dockManageRoot = dockManageContentView
                        if (dockManageRoot != null && dockManageRoot.visibility == View.VISIBLE && focused != null &&
                            isDescendantOf(focused, dockManageRoot)
                        ) {
                            hideDockManageContent(restoreFocus = true)
                            return true
                        }
                    }
                    // 焦点在内容区时，返回键回到 Dock（一级菜单）。
                    if (!onDock && ::dockItemViews.isInitialized && contentEntered) {
                        focusCurrentDockItem()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        scheduleIdleTimer() // 任何触摸都重置空闲计时
        return super.dispatchTouchEvent(ev)
    }

    private fun showHistoryContent() {
        try {
            refreshHistoryAsync()
            if (historyContentView == null) {
                val dialogBinding = DialogHistoryBinding.inflate(layoutInflater, binding.contentContainer, false)
                historyContentBinding = dialogBinding
                historyContentView = dialogBinding.root
                addFullscreenContent(dialogBinding.root)
                dialogBinding.historyList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
                dialogBinding.historyList.isFocusable = true
                dialogBinding.historyList.isFocusableInTouchMode = false
                dialogBinding.historyList.clipToPadding = false
                dialogBinding.historyList.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                // 性能优化：列表自身尺寸固定（不随内容变化），避免每次数据变更触发 RV 重新测量布局；
                // 历史最多 10 条，加大离屏缓存让横向来回滑动直接命中缓存、减少重新 bind。
                dialogBinding.historyList.setHasFixedSize(true)
                dialogBinding.historyList.setItemViewCacheSize(10)
                dialogBinding.historyList.adapter = historyAdapter
                historyAdapter.onItemClick = { item -> launchPlayer(item.uri, item.title, item.source) }
                historyAdapter.onWatchLaterClick = { item ->
                    toggleQueueSafe(item.title, item.uri, item.source ?: "history")
                }
            }
            historyContentView?.visibility = View.VISIBLE
            historyContentBinding?.let { syncHistoryDialogState(it) }
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "showHistoryContent failed", t)
        }
    }

    /**
     * 通用「加入稍后播放队列」入口：兼容收藏/历史按钮点击、DLNA 投屏结束提示等场景。
     * 使用应用级 [com.bd.casttv.queue.PlayQueueStore] 单例，避免多份状态不一致。
     */
    private fun addToQueueSafe(title: String, uri: String, source: String) {
        try {
            val store = com.bd.casttv.queue.PlayQueueStore.get(this)
            val id = store.add(title, uri, source)
            val msg = if (id.isNotBlank()) "已加入稍后播放" else "该内容已经在队列中"
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            refreshQueueButtons()
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "addToQueueSafe failed", t)
            android.widget.Toast.makeText(this, "加入失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 收藏/历史卡片「稍后播放」按钮切换：已在队列中则移出，否则加入。 */
    private fun toggleQueueSafe(title: String, uri: String, source: String) {
        try {
            val store = com.bd.casttv.queue.PlayQueueStore.get(this)
            val queued = store.findByUri(uri) != null
            if (queued) {
                val removed = store.removeByUri(uri)
                android.widget.Toast.makeText(
                    this,
                    if (removed) getString(R.string.queue_removed) else getString(R.string.queue_remove_failed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                val id = store.add(title, uri, source)
                val msg = if (id.isNotBlank()) "已加入稍后播放" else "该内容已经在队列中"
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            refreshQueueButtons()
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "toggleQueueSafe failed", t)
            android.widget.Toast.makeText(this, "操作失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 队列变化后刷新收藏/历史卡片按钮文案。 */
    private fun refreshQueueButtons() {
        historyAdapter.notifyDataSetChanged()
        favoritesDialogAdapter?.notifyDataSetChanged()
    }

    /** 展示「连接手机」全屏内容：QR 码 + 服务开关，服务生命周期与内容解耦（后台常驻）。 */
    private fun showPhoneContent() {
        try {
            if (isPhoneHubEnabled()) startPhoneHubServer()
            if (phoneContentView == null) {
                val dialogBinding = DialogPhoneHubBinding.inflate(layoutInflater, binding.contentContainer, false)
                phoneContentBinding = dialogBinding
                phoneContentView = dialogBinding.root
                addFullscreenContent(dialogBinding.root)
                dialogBinding.phoneHubSwitch.setOnClickListener {
                    val wantOn = dialogBinding.phoneHubSwitch.isChecked
                    if (wantOn) {
                        val ok = startPhoneHubServer()
                        setPhoneHubEnabled(ok)
                        if (!ok) android.widget.Toast.makeText(this, "启动手机交互服务失败", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        setPhoneHubEnabled(false)
                        stopPhoneHubServer()
                    }
                    renderPhoneContent()
                }
                bindPhoneContentBoundaryShake(dialogBinding)
            }
            phoneContentView?.visibility = View.VISIBLE
            renderPhoneContent()
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "showPhoneContent failed", t)
        }
    }

    /** 连接手机页首尾上下键边界抖动，避免焦点穿出页面或首尾循环。 */
    private fun bindPhoneContentBoundaryShake(dialogBinding: DialogPhoneHubBinding) {
        val focusables = listOf(dialogBinding.phoneHubSwitch)
        val listener = View.OnKeyListener { view, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) {
                return@OnKeyListener false
            }
            val visibleFocusables = focusables.filter { it.visibility == View.VISIBLE && it.isEnabled && it.isFocusable }
            val topView = visibleFocusables.firstOrNull()
            val bottomView = visibleFocusables.lastOrNull()
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && view === topView) {
                playBoundaryShake(view)
                return@OnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && view === bottomView) {
                playBoundaryShake(view)
                return@OnKeyListener true
            }
            false
        }
        focusables.forEach { it.setOnKeyListener(listener) }
    }

    /** 根据当前服务状态刷新「连接手机」内容区的 QR 码/地址/开关文案。 */
    private fun renderPhoneContent() {
        val b = phoneContentBinding ?: return
        val isRunning = phoneHubServer != null
        val activePort = phoneHubPort
        b.phoneHubSwitch.isChecked = isRunning
        b.phoneHubSwitch.text = if (isRunning) "HTTP 服务：已开启" else "HTTP 服务：已关闭"
        if (isRunning && activePort > 0) {
            val ip = com.bd.casttv.util.NetworkUtils.getLocalIpAddress()
            if (ip != null) {
                val url = "http://$ip:$activePort"
                b.phoneHubUrl.text = url
                val bmp = try {
                    com.bd.casttv.util.QrCodeGenerator.encode(url, 480)
                } catch (t: Throwable) {
                    null
                }
                if (bmp != null) {
                    b.phoneHubQr.setImageBitmap(bmp)
                    b.phoneHubQr.visibility = View.VISIBLE
                    b.phoneHubQrFallback.visibility = View.GONE
                } else {
                    b.phoneHubQr.visibility = View.GONE
                    b.phoneHubQrFallback.visibility = View.VISIBLE
                }
                b.phoneHubStatus.text = "手机扫码或浏览器打开上面的地址，即可在手机上收藏/管理内容"
            } else {
                b.phoneHubUrl.text = "未获取到局域网 IP"
                b.phoneHubQr.visibility = View.GONE
                b.phoneHubQrFallback.visibility = View.VISIBLE
                b.phoneHubStatus.text = "请确认电视已连接 Wi‑Fi / 有线网络"
            }
        } else {
            b.phoneHubUrl.text = ""
            b.phoneHubQr.visibility = View.GONE
            b.phoneHubQrFallback.visibility = View.VISIBLE
            b.phoneHubStatus.text = "打开开关后，手机即可扫码连接电视"
        }
    }

    private fun isPhoneHubEnabled(): Boolean = phoneHubPrefs.getBoolean("enabled", false)

    private fun setPhoneHubEnabled(enabled: Boolean) {
        phoneHubPrefs.edit().putBoolean("enabled", enabled).apply()
    }

    private fun startPhoneHubServer(): Boolean {
        if (phoneHubServer != null) return true
        if (phoneHubStarting) return true
        // 小米/HyperOS 对主线程卡顿非常敏感：端口探测、NanoHTTPD bind/start、Store 初始化都放到后台线程，
        // 避免启动或打开「连接手机」页面时触发“应用无响应”。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            phoneHubStarting = true
            Thread({
                val ok = startPhoneHubServerBlocking()
                ui.post {
                    phoneHubStarting = false
                    if (!ok) setPhoneHubEnabled(false)
                    renderPhoneContent()
                }
            }, "phone-hub-start").start()
            return true
        }
        return startPhoneHubServerBlocking()
    }

    private fun startPhoneHubServerBlocking(): Boolean {
        if (phoneHubServer != null) return true
        val store = com.bd.casttv.queue.PlayQueueStore.get(this)
        val fav = com.bd.casttv.favorites.FavoritesStore(this)
        val settings = com.bd.casttv.settings.Settings(this)
        val startedPort = phoneHubPortCandidates.firstOrNull { port ->
            try {
                val srv = com.bd.casttv.dlna.PhoneHubServer(
                    port = port,
                    favoritesStore = fav,
                    queueStore = store,
                    settings = settings,
                    pushPlayHandler = { mode, trigger -> onPhoneHubPushPlay(mode, trigger) },
                    playSingleHandler = { uri, title -> runOnUiThread { if (uri.isNotBlank()) launchPlayer(uri, title, "phone_hub") } },
                    settingsChangeHandler = { key, needRestart -> onPhoneHubSettingsChanged(key, needRestart) }
                )
                srv.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                phoneHubServer = srv
                phoneHubPort = port
                true
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "phone-hub start on $port failed", t)
                false
            }
        }
        return startedPort != null
    }

    private fun stopPhoneHubServer() {
        try { phoneHubServer?.stop() } catch (_: Throwable) {}
        phoneHubServer = null
        phoneHubPort = 0
    }

    /** 手机端推送播放：override=覆盖并从头开始，append=追加不打断当前播放。 */
    private fun onPhoneHubPushPlay(mode: String, triggerPlayFirst: Boolean) {
        runOnUiThread {
            val store = com.bd.casttv.queue.PlayQueueStore.get(this)
            if (store.size() == 0) {
                android.widget.Toast.makeText(this, "队列为空，无法播放", android.widget.Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }
            if (mode == "override") {
                // 重置所有状态为 PENDING（清除历史 FINISHED），从第一条开始
                for (item in store.all()) {
                    if (item.status != com.bd.casttv.queue.PlayQueueStore.Status.PENDING) {
                        store.setStatus(item.id, com.bd.casttv.queue.PlayQueueStore.Status.PENDING)
                    }
                }
                val first = store.nextPending() ?: return@runOnUiThread
                store.setStatus(first.id, com.bd.casttv.queue.PlayQueueStore.Status.PLAYING)
                PlaybackController.queuePlaybackActive = true
                PlaybackController.interruptedQueueUri = ""
                launchPlayer(first.uri, first.title, first.source.ifBlank { "queue" })
            } else {
                // append：不打断正在播放的内容；若 TV 当前空闲/队列已播完，则主动衔接第一条待播放。
                val idle = PlaybackController.transportState == PlaybackController.TransportState.NO_MEDIA_PRESENT ||
                    PlaybackController.transportState == PlaybackController.TransportState.STOPPED
                val next = store.nextPending()
                if (idle && next != null) {
                    store.setStatus(next.id, com.bd.casttv.queue.PlayQueueStore.Status.PLAYING)
                    PlaybackController.queuePlaybackActive = true
                    PlaybackController.interruptedQueueUri = ""
                    launchPlayer(next.uri, next.title, next.source.ifBlank { "queue" })
                    android.widget.Toast.makeText(this, "已追加并开始播放队列", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "已追加到电视队列末尾", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onPhoneHubSettingsChanged(key: String, needRestart: Boolean) {
        if (!needRestart) return
        runOnUiThread {
            android.widget.Toast.makeText(
                this,
                "设置已更新，重启服务生效",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun syncHistoryDialogState(dialogBinding: DialogHistoryBinding) {
        val hasItems = historyAdapter.itemCount > 0
        dialogBinding.historyEmpty.visibility = if (hasItems) View.GONE else View.VISIBLE
        dialogBinding.historyList.visibility = if (hasItems) View.VISIBLE else View.GONE
    }

    private fun focusFirstHistoryItem(historyList: RecyclerView) {
        historyList.post {
            historyList.post {
                val firstItem = historyList.findViewHolderForAdapterPosition(0)?.itemView
                val firstButton = firstItem?.findViewById<View>(R.id.itemPlayButton)
                if (firstButton != null) {
                    firstButton.isFocusable = true
                    firstButton.isFocusableInTouchMode = false
                    firstButton.requestFocus()
                } else if (firstItem != null) {
                    firstItem.requestFocus()
                } else {
                    historyList.requestFocus()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 历史播放列表适配器
    // ------------------------------------------------------------------
    /** 将播放位置毫秒数格式化为「X小时X分」（不足 1 分钟显示「不到1分钟」）。 */
    private fun formatWatchPosition(positionMs: Long): String {
        if (positionMs <= 0) return "0分"
        val totalMinutes = positionMs / 60000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分"
            minutes > 0 -> "${minutes}分"
            else -> "不到1分钟"
        }
    }

    private inner class HistoryAdapter(
        var onItemClick: (PlaybackController.HistoryItem) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        /** 「稍后播放 / 移出队列」按钮回调，由外部 (showHistoryDialog) 注入。 */
        var onWatchLaterClick: (PlaybackController.HistoryItem) -> Unit = { item ->
            toggleQueueSafe(item.title, item.uri, item.source ?: "history")
        }

        private val items = mutableListOf<PlaybackController.HistoryItem>()
        private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

        init {
            // 稳定 id：notifyDataSetChanged 后 RecyclerView 可按 id 复用同一 ViewHolder，
            // 减少无谓重新 bind，并更好地保持 D-pad 焦点位置。历史按 uri 去重，uri 即稳定键。
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long =
            items[position].uri.hashCode().toLong()

        fun submit(newItems: List<PlaybackController.HistoryItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val itemBinding = ItemHistoryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            val screenWidth = parent.resources.displayMetrics.widthPixels
            val dialogContentWidth = (screenWidth * 0.72f).toInt()
            val horizontalChrome = (12 * 3) * parent.resources.displayMetrics.density
            val adaptiveWidth = ((dialogContentWidth - horizontalChrome) / 3f)
                .toInt()
                .coerceIn(
                    (260 * parent.resources.displayMetrics.density).toInt(),
                    (300 * parent.resources.displayMetrics.density).toInt()
                )
            itemBinding.root.layoutParams = RecyclerView.LayoutParams(
                adaptiveWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (12 * parent.resources.displayMetrics.density).toInt()
            }
            return VH(itemBinding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.itemTitle.text = item.title.ifBlank { item.uri }
            // 加载历史缩略图，取值口径统一：artworkPath(封面) > thumbPath(截图) > 兜底图（均判存在）。
            Thumbnails.load(holder.binding.itemThumb, item.displayThumbPath())
            holder.binding.itemLiveBadge.visibility = if (item.isLive) View.VISIBLE else View.GONE

            // 播放进度：pct = positionMs / durationMs（做除零保护）。
            val duration = item.durationMs
            val positionMs = item.positionMs
            val hasProgress = duration > 0 && positionMs > 0 && positionMs < duration * 0.98
            val watchedEnd = duration > 0 && positionMs >= duration * 0.98
            val pct = if (duration > 0) {
                ((positionMs.toDouble() / duration) * 100).toInt().coerceIn(0, 100)
            } else 0
            holder.binding.itemProgress.progress = pct

            // 副标题：有进度 → 「上次观看到：X小时X分」；否则合理降级。
            holder.binding.itemMeta.text = when {
                hasProgress -> getString(R.string.history_last_watched, formatWatchPosition(positionMs))
                watchedEnd -> getString(R.string.history_last_watched, formatWatchPosition(positionMs))
                else -> getString(R.string.history_not_started)
            }

            // 主按钮：有进度显示「继续播放」并从 positionMs 续播；否则「重新播放」从 0 开始。
            holder.binding.itemPlayButton.text =
                getString(if (hasProgress) R.string.history_continue_play else R.string.history_replay)
            val startPos = if (hasProgress) positionMs else 0L
            val playAction = View.OnClickListener {
                launchPlayer(item.uri, item.title, item.source, startPos)
            }
            holder.binding.root.isFocusable = false
            holder.binding.root.isFocusableInTouchMode = false
            holder.binding.root.isClickable = false
            holder.binding.itemPlayButton.isFocusable = true
            holder.binding.itemPlayButton.isFocusableInTouchMode = false
            holder.binding.itemPlayButton.isClickable = true
            holder.binding.itemPlayButton.nextFocusDownId = R.id.itemBtnLater
            holder.binding.itemBtnLater.isFocusable = true
            holder.binding.itemBtnLater.isFocusableInTouchMode = false
            holder.binding.itemBtnLater.isClickable = true
            holder.binding.itemBtnLater.text = getString(
                if (com.bd.casttv.queue.PlayQueueStore.get(this@MainActivity).findByUri(item.uri) != null)
                    R.string.queue_remove else R.string.queue_watch_later
            )
            holder.binding.itemBtnLater.nextFocusUpId = R.id.itemPlayButton
            // 历史列表是横向 RecyclerView，卡片内部又是上下两个按钮。
            // 左右键只在历史列表内部按同一按钮行切换；但首张卡片左键应返回一级「历史记录」Dock。
            // 返回键在任一历史卡片按钮上也统一回到一级导航。
            val bindHorizontalHistoryFocus = { focusView: View, buttonId: Int ->
                focusView.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val from = holder.bindingAdapterPosition
                    if (from == RecyclerView.NO_POSITION) return@setOnKeyListener true
                    // 历史卡片内部上下边界：上方「重新/继续播放」按 ↑、下方「稍后播放」按 ↓ 时
                    // 仅播放项目统一的边界抖动并消费事件，避免焦点穿透到其它区域。
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && focusView === holder.binding.itemPlayButton) {
                        playBoundaryShake(focusView)
                        return@setOnKeyListener true
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focusView === holder.binding.itemBtnLater) {
                        playBoundaryShake(focusView)
                        return@setOnKeyListener true
                    }
                    when (keyCode) {
                        KeyEvent.KEYCODE_BACK -> {
                            focusCurrentDockItem()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (from == 0) {
                                focusCurrentDockItem()
                                return@setOnKeyListener true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
                        else -> return@setOnKeyListener false
                    }
                    val delta = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        else -> return@setOnKeyListener false
                    }
                    val target = from + delta
                    if (target !in 0 until itemCount) return@setOnKeyListener true
                    val list = holder.itemView.parent as? RecyclerView ?: return@setOnKeyListener true
                    list.smoothScrollToPosition(target)
                    list.post {
                        val vh = list.findViewHolderForAdapterPosition(target)
                        val targetView = vh?.itemView?.findViewById<View>(buttonId)
                        if (targetView != null) {
                            targetView.requestFocus()
                        } else {
                            list.post {
                                list.findViewHolderForAdapterPosition(target)
                                    ?.itemView
                                    ?.findViewById<View>(buttonId)
                                    ?.requestFocus()
                            }
                        }
                    }
                    true
                }
            }
            bindHorizontalHistoryFocus(holder.binding.itemPlayButton, R.id.itemPlayButton)
            bindHorizontalHistoryFocus(holder.binding.itemBtnLater, R.id.itemBtnLater)
            holder.binding.itemPlayButton.setOnClickListener(playAction)
            holder.binding.itemBtnLater.setOnClickListener { onWatchLaterClick(item) }
            val childFocusListener = View.OnFocusChangeListener { view, hasFocus ->
                holder.binding.root.isSelected = hasFocus
                holder.binding.root.animate()
                    .scaleX(if (hasFocus) 1.05f else 1f)
                    .scaleY(if (hasFocus) 1.05f else 1f)
                    .setDuration(140L)
                    .start()
                if (hasFocus) {
                    holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { focusedPosition ->
                        holder.itemView.post {
                            (holder.itemView.parent as? RecyclerView)?.smoothScrollToPosition(focusedPosition)
                            holder.itemView.requestRectangleOnScreen(
                                android.graphics.Rect(0, 0, holder.itemView.width, holder.itemView.height),
                                false
                            )
                        }
                    }
                }
            }
            holder.binding.itemPlayButton.onFocusChangeListener = childFocusListener
            holder.binding.itemBtnLater.onFocusChangeListener = childFocusListener
        }

        inner class VH(val binding: ItemHistoryBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    // ------------------------------------------------------------------
    // 收藏弹窗（合集架构 · 左侧栏 + 右侧网格 / 新建合集表单）
    // ------------------------------------------------------------------
    /** 侧边栏当前是否位于「新建合集」项（true 时右侧展示表单，false 时展示合集内容）。 */
    private var sidebarInCreateMode: Boolean = false

    /** 侧边栏当前是否位于「导入直播源」项（用于统一 Tab 选中态，不改变右侧内容）。 */
    private var sidebarInImportLiveMode: Boolean = false

    /** 侧边栏合集列表 Adapter，供切换 / 高亮 / 焦点联动。 */
    private var sidebarAdapter: CollectionSidebarAdapter? = null

    /** 收藏页：合集列表与内容列表的上下切换频控（避免短时间连续按键触发多次切换导致焦点/加载抖动）。 */
    private var lastFavoritesSidebarNavTimeMs: Long = 0L
    private var lastFavoritesGridNavTimeMs: Long = 0L
    private val favoritesNavThrottleMs: Long = 180L

    /** 收藏页视频卡片（内容列表）上下切换频控：卡片切换更重，节流阈值更大，避免连按切换过快。 */
    private val favoritesGridNavThrottleMs: Long = 340L

    private fun showFavoritesContent() {
        try {
            if (favoritesContentView == null) {
                val dialogBinding = DialogFavoritesBinding.inflate(layoutInflater, binding.contentContainer, false)
                favoritesContentView = dialogBinding.root
                favoritesDialogBinding = dialogBinding
                addFullscreenContent(dialogBinding.root)

                // 右侧内容列表 Adapter：一行一条横向宽卡片，避免多列网格压缩后看起来像竖向大卡片。
                val adapter = FavoriteAdapter(
                    onPlay = { item -> launchPlayer(item.uri, item.title, item.source) },
                    onEdit = { item -> showFavoriteEditDialog(item) { reloadFavoritesDialog(dialogBinding) } },
                    onMove = { item, position -> showFavoriteMoveDialog(item, position) },
                    onDelete = { item, position -> showFavoriteDeleteDialog(item, position) }
                )
                dialogBinding.favoritesGrid.layoutManager = GridLayoutManager(this, 1)
                dialogBinding.favoritesGrid.clipChildren = false
                dialogBinding.favoritesGrid.clipToPadding = false
                dialogBinding.favoritesGrid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                dialogBinding.favoritesGrid.isFocusable = true
                // 性能优化：网格容器尺寸固定，跳过内容变更时的 RV 自测量；加大离屏缓存改善纵向滑动。
                dialogBinding.favoritesGrid.setHasFixedSize(true)
                dialogBinding.favoritesGrid.setItemViewCacheSize(12)
                dialogBinding.favoritesGrid.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                    ) {
                        val now = event.eventTime
                        if (now - lastFavoritesGridNavTimeMs < favoritesGridNavThrottleMs) {
                            return@setOnKeyListener true
                        }
                        lastFavoritesGridNavTimeMs = now
                    }
                    handleFocusedListBoundaryShake(dialogBinding.favoritesGrid, keyCode, event)
                }
                dialogBinding.favoritesGrid.adapter = adapter
                favoritesDialogAdapter = adapter

                // 侧边栏合集列表 Adapter。
                val sidebar = CollectionSidebarAdapter { id ->
                    // TV 侧边栏切换遵循“焦点到哪，右侧内容立即刷新”的交互，不能等 OK/点击。
                    if (!sidebarInCreateMode && !sidebarInImportLiveMode && currentCollectionId == id) return@CollectionSidebarAdapter
                    currentCollectionId = id
                    sidebarInCreateMode = false
                    sidebarInImportLiveMode = false
                    renderCurrentCollection(dialogBinding)
                    // v1.1.54 归归位：从固定入口切到普通合集后，同步清掉「新建合集 / 导入直播源」的选中态高亮，
                    // 避免它们因为在失焦时状态未统一刷新而残留 isActivated=true。
                    applyFavoritesSidebarFixedTabState(dialogBinding)
                    // 二级选中态由 CollectionSidebarAdapter.setSelected() 做安全定点刷新，
                    // 避免在 RecyclerView 布局/焦点计算中全量刷新。
                }
                dialogBinding.sidebarCollectionList.layoutManager = LinearLayoutManager(this)
                dialogBinding.sidebarCollectionList.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                    ) {
                        val now = event.eventTime
                        if (now - lastFavoritesSidebarNavTimeMs < favoritesNavThrottleMs) {
                            return@setOnKeyListener true
                        }
                        lastFavoritesSidebarNavTimeMs = now
                    }
                    handleFocusedListBoundaryShake(dialogBinding.sidebarCollectionList, keyCode, event)
                }
                dialogBinding.sidebarCollectionList.adapter = sidebar
                sidebarAdapter = sidebar

                // 初始进入收藏页不选中任何合集，右侧先展示兜底引导页；由用户 OK/右键进入后再聚焦默认合集。
                currentCollectionId = null
                sidebarInCreateMode = false
                sidebarInImportLiveMode = false

                // 「＋ 新建合集」入口：焦点或点击均切换为「新建合集」表单。
                dialogBinding.btnSidebarNewCollection.newCollectionGlowUnderline.isFocusable = false
                dialogBinding.btnSidebarNewCollection.newCollectionGlowUnderline.isClickable = false
                dialogBinding.btnSidebarNewCollection.root.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) switchToCreateMode(dialogBinding)
                    applyFavoritesSidebarFixedTabState(dialogBinding)
                }
                dialogBinding.btnSidebarNewCollection.root.setOnKeyListener { view, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        playBoundaryShake(view)
                        true
                    } else {
                        false
                    }
                }
                dialogBinding.btnSidebarNewCollection.root.setOnClickListener {
                    switchToCreateMode(dialogBinding)
                    dialogBinding.inputCreateCollectionName.requestFocus()
                }

                // 「📡 导入直播源」入口：焦点到达时同步切换二级 Tab 选中态；点击后弹出 URL 输入框，解析并预览后写入合集。
                dialogBinding.importLiveGlowUnderline.isFocusable = false
                dialogBinding.importLiveGlowUnderline.isClickable = false
                dialogBinding.btnSidebarImportLive.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) switchToImportLiveTab(dialogBinding)
                    applyFavoritesSidebarFixedTabState(dialogBinding)
                }
                dialogBinding.btnSidebarImportLive.setOnClickListener {
                    switchToImportLiveTab(dialogBinding)
                    enterFavoritesThirdLevelDefaultFocus()
                }
                dialogBinding.btnSidebarImportLive.setOnKeyListener { view, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    ) {
                        // v1.1.80：从二级 sidebar「导入直播源」项 OK/→ 进入三级，焦点直接落到 tabInputLink。
                        switchToImportLiveTab(dialogBinding)
                        enterFavoritesThirdLevelDefaultFocus()
                        true
                    } else {
                        false
                    }
                }

                // 新建合集表单：确认按钮
                dialogBinding.btnConfirmCreateCollection.setOnClickListener {
                    onConfirmCreateCollection(dialogBinding)
                }

                // 合集操作按钮
                // v1.1.115：点击「批量选择」直接打开批量操作弹窗（替代原悬浮菜单浮层方案）。
                dialogBinding.btnCollectionSelect.setOnClickListener {
                    showBatchOperateDialog(dialogBinding)
                }
                dialogBinding.btnCollectionRename.setOnClickListener { showCollectionRenameDialog(dialogBinding) }
                dialogBinding.btnCollectionDelete.setOnClickListener { showCollectionDeleteDialog(dialogBinding) }
                val toolbarDownKeyListener = View.OnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        focusFirstFavoriteGridItem(dialogBinding)
                    } else {
                        false
                    }
                }
                // v1.1.80：三级顶部工具栏按 ↑ 抖动 + 消费；最右按钮按 → 抖动 + 消费。
                // 最左按钮按 ← 已由 dispatchKeyEvent 中 isFocusInFavoritesContentPanel 分支收敛回二级 sidebar，
                // 这里不再重复处理，避免重复消费。
                val toolbarButtons = listOf(
                    dialogBinding.btnCollectionSelect,
                    dialogBinding.btnCollectionRename,
                    dialogBinding.btnCollectionDelete
                )
                val toolbarBoundaryKeyListener = View.OnKeyListener { view, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            playBoundaryShake(view)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val visible = toolbarButtons.filter { it.visibility == View.VISIBLE }
                            if (visible.isNotEmpty() && view === visible.last()) {
                                playBoundaryShake(view)
                                true
                            } else false
                        }
                        else -> false
                    }
                }
                // 顶部工具栏：UP 抖动、RIGHT 最右抖动、DOWN 进入卡片。
                toolbarButtons.forEach {
                    it.setOnKeyListener { view, keyCode, event ->
                        val consumed = toolbarBoundaryKeyListener.onKey(view, keyCode, event)
                        if (consumed) return@setOnKeyListener true
                        toolbarDownKeyListener.onKey(view, keyCode, event)
                    }
                }
                // 底部「导入 / 导出」入口：局域网 HTTP + 二维码传输。
                dialogBinding.favExportGlowUnderline.isFocusable = false
                dialogBinding.favExportGlowUnderline.isClickable = false
                dialogBinding.favImportGlowUnderline.isFocusable = false
                dialogBinding.favImportGlowUnderline.isClickable = false
                dialogBinding.btnFavExport.setOnFocusChangeListener { _, _ ->
                    applyFavoritesSidebarFixedTabState(dialogBinding)
                }
                dialogBinding.btnFavImport.setOnFocusChangeListener { _, _ ->
                    applyFavoritesSidebarFixedTabState(dialogBinding)
                }
                applyFavoritesSidebarFixedTabState(dialogBinding)
                dialogBinding.btnFavExport.setOnClickListener {
                    openFavTransfer(FavoriteTransferServer.Mode.EXPORT)
                }
                dialogBinding.btnFavImport.setOnClickListener {
                    openFavTransfer(FavoriteTransferServer.Mode.IMPORT)
                }
                // v1.1.80：二级 sidebar 底部按 ↓ 若同 sidebar 内无更下方可聚焦元素则抖动 + 消费事件，
                // 严禁焦点穿透到 Dock 或右侧内容区。导入/导出按钮位于 sidebar 竖列最底部，二者都要拦截。
                val sidebarBottomDownShakeListener = View.OnKeyListener { view, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        val panel = dialogBinding.sidebarPanel
                        val next = view.focusSearch(View.FOCUS_DOWN)
                        if (next == null || !isDescendantOf(next, panel)) {
                            playBoundaryShake(view)
                            true
                        } else false
                    } else false
                }
                dialogBinding.btnFavExport.setOnKeyListener(sidebarBottomDownShakeListener)
                dialogBinding.btnFavImport.setOnKeyListener(sidebarBottomDownShakeListener)

                // v1.1.125：合集管理入口。三态样式与相邻的「导入 / 导出」保持一致；点击弹出合集管理弹窗。
                dialogBinding.favCollectionManageGlowUnderline.isFocusable = false
                dialogBinding.favCollectionManageGlowUnderline.isClickable = false
                dialogBinding.btnFavCollectionManage.setOnFocusChangeListener { _, _ ->
                    applyFavoritesSidebarFixedTabState(dialogBinding)
                }
                dialogBinding.btnFavCollectionManage.setOnClickListener {
                    showCollectionManageDialog(dialogBinding)
                }
                dialogBinding.btnFavCollectionManage.setOnKeyListener(sidebarBottomDownShakeListener)
            }

            favoritesContentView?.visibility = View.VISIBLE
            favoritesDialogBinding?.let {
                // 重新进入收藏 Tab 时只刷新内容并停留在 Dock；不自动聚焦「新建合集」或任何内容区 item。
                if (!contentEntered) currentCollectionId = null
                sidebarInCreateMode = false
                reloadFavoritesDialog(it)
            }
        } catch (t: Throwable) {
            android.util.Log.e("MainActivity", "showFavoritesContent failed", t)
            android.widget.Toast.makeText(this, "收藏列表暂时无法打开", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 收藏「导入 / 导出」：局域网 HTTP 服务 + 二维码
    // ------------------------------------------------------------------
    /**
     * 打开导入 / 导出二维码弹窗：按需在候选端口上启动 [FavoriteTransferServer]，
     * 生成访问地址二维码，并监听网络变化刷新地址。弹窗关闭即停止服务，不常驻。
     */
    private fun openFavTransfer(mode: FavoriteTransferServer.Mode) {
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip == null) {
            toastMsg("未连接到局域网，请先连接 Wi-Fi / 网线")
            return
        }
        // 先清理可能残留的上一次服务 / 弹窗。
        stopFavTransfer()
        transferDialog?.dismiss()

        val started = startTransferServer(mode)
        if (started == null) {
            toastMsg("传输服务启动失败，请稍后重试")
            return
        }
        transferMode = mode
        transferInProgress = true
        showTransferDialog(mode)
        registerTransferNetworkCallback()
    }

    /** 在候选端口上依次尝试启动传输服务；全部失败返回 null。 */
    private fun startTransferServer(mode: FavoriteTransferServer.Mode): FavoriteTransferServer? {
        val fileName = "casttv_favorites.csv"
        for (port in transferPortCandidates) {
            try {
                val server = FavoriteTransferServer(
                    port = port,
                    mode = mode,
                    exportFileName = fileName,
                    exportProvider = { favoritesStore.exportToCsv() },
                    importHandler = { content -> handleIncomingImport(content) }
                )
                server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                transferServer = server
                transferPort = port
                logD("startTransferServer: started mode=$mode port=$port")
                return server
            } catch (e: Exception) {
                logD("startTransferServer: port $port unavailable (${e.message})")
            }
        }
        return null
    }

    /** 构建并显示二维码弹窗（导出 / 导入共用布局，文案按模式设置）。 */
    private fun showTransferDialog(mode: FavoriteTransferServer.Mode) {
        val b = DialogFavTransferBinding.inflate(layoutInflater)
        transferBinding = b
        if (mode == FavoriteTransferServer.Mode.EXPORT) {
            b.favTransferTitle.text = "🖍 导出收藏"
            b.favTransferSubtitle.text = "用手机扫描下方二维码，在浏览器中下载收藏备份文件（CSV）。"
            b.favTransferStatus.text = "等待手机扫码下载…"
        } else {
            b.favTransferTitle.text = "🖍 导入收藏"
            b.favTransferSubtitle.text = "用手机扫描下方二维码，在浏览器中上传收藏备份文件；电视会先校验格式再确认导入。"
            b.favTransferStatus.text = "等待手机扫码上传…"
        }
        val dialog = AlertDialog.Builder(this)
            .setView(b.root)
            .setNegativeButton("关闭") { d, _ -> d.dismiss() }
            .create()
        dialog.setOnDismissListener {
            stopFavTransfer()
            transferBinding = null
            transferDialog = null
        }
        transferDialog = dialog
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        refreshTransferQr()
    }

    /** 依据当前局域网 IP 重新生成访问地址与二维码；网络变化 / 首次展示时调用。 */
    private fun refreshTransferQr() {
        val b = transferBinding ?: return
        val ip = NetworkUtils.getLocalIpAddress()
        if (ip == null) {
            b.favTransferUrl.text = "网络已断开"
            b.favTransferStatus.text = "⚠ 局域网连接已断开，请重新连接后再试"
            b.favTransferQr.setImageBitmap(null)
            return
        }
        val url = "http://$ip:$transferPort/"
        b.favTransferUrl.text = url
        val bmp = QrCodeGenerator.encode(url, 480)
        if (bmp != null) {
            b.favTransferQr.setImageBitmap(bmp)
        } else {
            b.favTransferQr.setImageBitmap(null)
            b.favTransferStatus.text = "二维码生成失败，请手动在手机浏览器打开：$url"
        }
    }

    /** 监听网络变化：IP 变动时刷新二维码地址，避免手机扫到失效地址。 */
    private fun registerTransferNetworkCallback() {
        if (transferNetworkCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ui.post { refreshTransferQr() }
            }

            override fun onLost(network: Network) {
                ui.post { refreshTransferQr() }
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            transferNetworkCallback = cb
        } catch (e: Exception) {
            logD("registerTransferNetworkCallback failed: ${e.message}")
        }
    }

    /** 停止传输服务、注销网络监听，并放行期间被暂存的投屏请求。 */
    private fun stopFavTransfer() {
        transferInProgress = false
        try {
            transferServer?.stop()
        } catch (e: Exception) {
            logD("stopFavTransfer: stop server failed ${e.message}")
        }
        transferServer = null
        transferMode = null
        transferPort = 0
        transferNetworkCallback?.let { cb ->
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        transferNetworkCallback = null
        // 传输结束后，放行期间被暂存的投屏请求（如有）。
        flushPendingCastIfReady()
    }

    /**
     * 处理手机上传的收藏 CSV（运行在 NanoHTTPD 工作线程）：先严格校验表头与非空字段，
     * 校验通过则切回主线程弹确认框（真正写入需用户在电视上确认），并回显给手机对应提示。
     */
    private fun handleIncomingImport(content: String): FavoriteTransferServer.ImportOutcome {
        return when (val preview = favoritesStore.validateImportCsv(content)) {
            is FavoritesStore.ImportPreview.Invalid -> {
                ui.post {
                    transferBinding?.favTransferStatus?.text = "❌ 收到无效文件：${preview.reason}"
                }
                FavoriteTransferServer.ImportOutcome(false, "文件格式校验未通过：${preview.reason}")
            }
            is FavoritesStore.ImportPreview.Valid -> {
                ui.post { promptImportConfirm(preview) }
                FavoriteTransferServer.ImportOutcome(
                    true,
                    "已发送到电视，请在电视上确认导入（${preview.collectionCount} 个合集 / ${preview.itemCount} 条收藏）"
                )
            }
        }
    }

    /** 校验通过后，在电视上弹确认框；确认后覆盖导入并刷新界面。 */
    private fun promptImportConfirm(preview: FavoritesStore.ImportPreview.Valid) {
        if (isFinishing || isDestroyed) return
        transferBinding?.favTransferStatus?.text =
            "📥 已收到备份（${preview.collectionCount} 个合集 / ${preview.itemCount} 条），请确认导入"
        AlertDialog.Builder(this)
            .setTitle("确认导入收藏")
            .setMessage(
                "手机上传了一份收藏备份：\n· 合集 ${preview.collectionCount} 个\n· 收藏 ${preview.itemCount} 条\n\n" +
                    "导入后将【覆盖】当前电视上的全部收藏，确定继续吗？"
            )
            .setPositiveButton("覆盖导入") { d, _ ->
                d.dismiss()
                // ANR 优化（v1.1.131）：整份收藏 CSV/JSON 解析 + 覆盖写盘较重，
                // 放到后台线程执行，结果回到主线程刷新 UI。
                transferBinding?.favTransferStatus?.text = "⏳ 正在导入…"
                Thread {
                    val result = favoritesStore.applyImportedCsv(preview.normalizedJson)
                    ui.post {
                        if (result == FavoritesStore.OpResult.SUCCESS) {
                            toastMsg("导入成功 🎉 已更新收藏")
                            refreshFavoritesHome()
                            favoritesDialogBinding?.let { reloadFavoritesDialog(it) }
                            transferDialog?.dismiss()
                        } else {
                            toastMsg("导入失败，请重试")
                            transferBinding?.favTransferStatus?.text = "❌ 导入写入失败，请重试"
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消") { d, _ ->
                transferBinding?.favTransferStatus?.text = "已取消本次导入，可重新上传"
                d.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * v1.1.115 批量操作弹窗：点击顶部「批量选择」直接打开（替代原悬浮菜单浮层）。
     * 5 列网格展示当前合集全部视频，OK 选中/反选，底部动态动作按钮；
     * 操作完成或取消后关闭弹窗并把焦点落回合集列表当前选中 Tab。
     */
    private fun showBatchOperateDialog(dialogBinding: DialogFavoritesBinding) {
        val collectionId = currentCollectionId ?: return
        val allItems = favoritesStore.collection(collectionId)?.items.orEmpty()
        if (allItems.isEmpty()) {
            toastMsg("当前合集暂无视频")
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_batch_operate, null)
        // v1.1.115：套用 App 深色弹窗主题，避免系统默认浅色 AlertDialog（白底黑字）闪现。
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_CastTV_Dialog)
            .setView(view).create()

        val grid = view.findViewById<RecyclerView>(R.id.batchGrid)
        val countText = view.findViewById<TextView>(R.id.batchDialogCount)
        val btnSelectAll = view.findViewById<TextView>(R.id.batchBtnSelectAll)
        val btnUnselect = view.findViewById<TextView>(R.id.batchBtnUnselect)
        val btnDelete = view.findViewById<TextView>(R.id.batchBtnDelete)
        val btnMove = view.findViewById<TextView>(R.id.batchBtnMove)
        val btnLater = view.findViewById<TextView>(R.id.batchBtnLater)
        val btnCancel = view.findViewById<TextView>(R.id.batchBtnCancel)

        val selectedItemIds = linkedSetOf<String>()

        fun updateBottomButtons() {
            val sel = selectedItemIds.size
            val total = allItems.size
            countText.text = "已选择 $sel 项"
            val hasSel = sel > 0
            val allSel = total > 0 && sel >= total
            btnUnselect.visibility = if (hasSel) View.VISIBLE else View.GONE
            btnDelete.visibility = if (hasSel) View.VISIBLE else View.GONE
            btnMove.visibility = if (hasSel) View.VISIBLE else View.GONE
            btnLater.visibility = if (hasSel) View.VISIBLE else View.GONE
            // 全选：始终展示，已全选时置灰不可点/不可聚焦
            btnSelectAll.isEnabled = !allSel
            btnSelectAll.isFocusable = !allSel
            btnSelectAll.alpha = if (allSel) 0.4f else 1f
        }

        val adapter = BatchVideoAdapter(
            data = allItems,
            isSelected = { id -> id in selectedItemIds },
            onToggle = { item, pos ->
                if (!selectedItemIds.add(item.itemId)) selectedItemIds.remove(item.itemId)
                grid.adapter?.notifyItemChanged(pos)
                updateBottomButtons()
            }
        )
        grid.layoutManager = GridLayoutManager(this, 5)
        grid.adapter = adapter
        (grid.parent as? ViewGroup)?.clipChildren = false

        btnSelectAll.setOnClickListener {
            if (!btnSelectAll.isEnabled) return@setOnClickListener
            selectedItemIds.clear()
            allItems.forEach { selectedItemIds.add(it.itemId) }
            adapter.notifyDataSetChanged()
            updateBottomButtons()
        }
        btnUnselect.setOnClickListener {
            selectedItemIds.clear()
            adapter.notifyDataSetChanged()
            updateBottomButtons()
            btnSelectAll.post { btnSelectAll.requestFocus() }
        }
        btnDelete.setOnClickListener {
            val ids = selectedItemIds.toList()
            if (ids.isEmpty()) return@setOnClickListener
            // ANR 优化（v1.1.131）：批量删除涉及全局锁 + 文件写盘，移到后台线程执行，
            // 结果回到主线程刷新 UI，避免大合集删除时阻塞主线程。
            Thread {
                val result = favoritesStore.removeItemsByItemId(collectionId, ids)
                ui.post {
                    if (result == FavoritesStore.OpResult.SUCCESS) {
                        toastMsg("已删除 ${ids.size} 个内容")
                        dialog.dismiss()
                        reloadFavoritesDialog(dialogBinding)
                        focusCurrentCollectionTabAfterBatch(dialogBinding)
                    } else {
                        toastMsg(opResultMessage(result))
                    }
                }
            }.start()
        }
        btnMove.setOnClickListener {
            val ids = selectedItemIds.toList()
            if (ids.isEmpty()) return@setOnClickListener
            showBatchMoveTargetDialog(dialogBinding, collectionId, ids) { dialog.dismiss() }
        }
        btnLater.setOnClickListener {
            val ids = selectedItemIds.toSet()
            val items = allItems.filter { it.itemId in ids }
            if (items.isEmpty()) return@setOnClickListener
            val queue = com.bd.casttv.queue.PlayQueueStore.get(this)
            items.forEach { queue.add(it.title, it.uri, it.source.ifBlank { "favorite" }) }
            toastMsg("已加入稍后播放：${items.size} 个")
            dialog.dismiss()
            focusCurrentCollectionTabAfterBatch(dialogBinding)
        }
        btnCancel.setOnClickListener {
            dialog.dismiss()
            focusCurrentCollectionTabAfterBatch(dialogBinding)
        }
        dialog.setOnCancelListener { focusCurrentCollectionTabAfterBatch(dialogBinding) }

        updateBottomButtons()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val dm = resources.displayMetrics
            setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.9f).toInt())
        }
        // 焦点落在第一个视频卡片（三级重试）
        grid.post {
            val vh = grid.findViewHolderForAdapterPosition(0)
            if (vh?.itemView?.requestFocus() != true) {
                grid.postDelayed({
                    grid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: grid.requestFocus()
                }, 100L)
            }
        }
    }

    /** 批量「移动」目标合集选择器：Android 原生 AlertDialog 列表（非 Material），套用 App 深色主题。 */
    private fun showBatchMoveTargetDialog(
        dialogBinding: DialogFavoritesBinding,
        fromCollectionId: String,
        itemIds: List<String>,
        onDone: () -> Unit
    ) {
        // 复用旧批量移动的数据源：轻量合集元信息，排除当前合集。
        val targets = favoritesStore.collectionsInfo().filter { it.id != fromCollectionId }
        if (targets.isEmpty()) {
            toastMsg(getString(R.string.favorite_move_no_target))
            return
        }
        val names = targets.map { it.name }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_CastTV_Dialog)
            .setTitle("移动到合集")
            .setItems(names) { d, which ->
                val target = targets[which]
                // ANR 优化（v1.1.131）：批量移动同样走后台线程写盘，结果回主线程刷新。
                Thread {
                    val result = favoritesStore.moveItemsByItemId(fromCollectionId, itemIds, target.id)
                    ui.post {
                        if (result == FavoritesStore.OpResult.SUCCESS) {
                            toastMsg("已移动 ${itemIds.size} 个内容到 ${target.name}")
                            d.dismiss()
                            onDone()
                            reloadFavoritesDialog(dialogBinding)
                            focusCurrentCollectionTabAfterBatch(dialogBinding)
                        } else {
                            toastMsg(opResultMessage(result))
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 批量操作弹窗关闭后：把焦点落回合集列表（左侧侧栏）当前选中 Tab，三级重试兜底。
     * 复用侧栏 CollectionSidebarAdapter.positionOf 定位当前合集，找不到时兜底「批量选择」按钮。
     */
    private fun focusCurrentCollectionTabAfterBatch(dialogBinding: DialogFavoritesBinding) {
        val list = dialogBinding.sidebarCollectionList
        val adapter = sidebarAdapter
        val pos = adapter?.positionOf(currentCollectionId)?.takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        // 焦点回到二级 sidebar，仍属于「已进入内容区」状态。
        contentEntered = true
        fun focusTab(): Boolean {
            if (pos != RecyclerView.NO_POSITION) {
                list.scrollToPosition(pos)
                val vh = list.findViewHolderForAdapterPosition(pos)
                if (vh?.itemView?.requestFocus() == true) return true
            }
            return false
        }
        list.post {
            if (!focusTab()) {
                list.postDelayed({
                    if (!focusTab()) {
                        dialogBinding.btnCollectionSelect.requestFocus()
                    }
                }, 100L)
            }
        }
    }

    /** 批量弹窗内的小视频卡片 Adapter（5 列网格）。 */
    private inner class BatchVideoAdapter(
        private val data: List<FavoritesStore.FavoriteItem>,
        private val isSelected: (String) -> Boolean,
        private val onToggle: (FavoritesStore.FavoriteItem, Int) -> Unit
    ) : RecyclerView.Adapter<BatchVideoAdapter.VH>() {
        inner class VH(val root: View) : RecyclerView.ViewHolder(root) {
            val thumb: ImageView = root.findViewById(R.id.batchThumb)
            val seq: TextView = root.findViewById(R.id.batchSeq)
            val title: TextView = root.findViewById(R.id.batchTitle)
            val check: TextView = root.findViewById(R.id.batchCheck)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_batch_video, parent, false)
            return VH(v)
        }
        override fun getItemCount(): Int = data.size
        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.seq.text = (position + 1).toString()
            holder.title.text = item.title.ifBlank { item.uri }
            Thumbnails.load(holder.thumb, item.displayThumbPath())
            val selected = isSelected(item.itemId)
            holder.root.isSelected = selected
            holder.check.visibility = if (selected) View.VISIBLE else View.GONE
            holder.root.setOnClickListener { onToggle(item, holder.bindingAdapterPosition) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * v1.1.80：二级 → 三级切换时，把焦点收敛到三级默认元素：
     * - 普通合集：顶部工具栏「批量选择」按钮 btnCollectionSelect；
     * - 「导入直播源」固定 tab：右侧内嵌 tabInputLink 子 tab。
     * 通过 post 等一帧，确保右侧内容已完成布局（renderCurrentCollection / switchToImportLiveTab
     * 都是同步刷新可见性，post 一次即可）。若目标视图不可见/不可聚焦则回退到内容区首个可聚焦元素。
     */
    private fun enterFavoritesThirdLevelDefaultFocus() {
        val dialog = favoritesDialogBinding ?: return
        contentEntered = true
        dialog.root.post {
            // 导入直播源 Tab：优先聚焦「输入链接」子 Tab。
            if (sidebarInImportLiveMode) {
                val target = importLiveInlineBinding?.tabInputLink
                if (target != null && target.visibility == View.VISIBLE && target.isFocusable) {
                    target.requestFocus()
                    return@post
                }
            }
            // 新建合集模式：焦点保留在表单内，交给右侧原有逻辑处理，不强抢。
            if (sidebarInCreateMode) return@post
            // 普通合集：聚焦 btnCollectionSelect（若合集为空则可能 GONE，退化到内容区首个可聚焦元素）。
            val select = dialog.btnCollectionSelect
            if (select.visibility == View.VISIBLE && select.isFocusable) {
                select.requestFocus()
                return@post
            }
            findFirstFocusable(dialog.contentPanel)?.requestFocus()
        }
    }

    private fun focusFirstFavoriteGridItem(dialogBinding: DialogFavoritesBinding): Boolean {
        return focusFavoriteGridItemFirstButton(dialogBinding, 0)
    }

    private fun focusFavoriteGridItemFirstButton(
        dialogBinding: DialogFavoritesBinding,
        position: Int,
        delays: IntArray = intArrayOf(0, 80, 180)
    ): Boolean {
        val grid = dialogBinding.favoritesGrid
        val count = grid.adapter?.itemCount ?: 0
        if (grid.visibility != View.VISIBLE || count <= 0) return false
        val targetPosition = position.coerceIn(0, count - 1)
        contentEntered = true
        grid.scrollToPosition(targetPosition)
        fun request(): Boolean {
            val holder = grid.findViewHolderForAdapterPosition(targetPosition) as? FavoriteAdapter.VH ?: return false
            val target = holder.binding.gridBtnPlay.takeIf { it.visibility == View.VISIBLE && it.isEnabled && it.isFocusable }
                ?: holder.binding.favoriteGridItemRoot
            target.requestFocus()
            return true
        }
        delays.forEach { delay ->
            grid.postDelayed({ request() }, delay.toLong())
        }
        return true
    }

    private fun applyFavoriteLocalRemovalResult(
        dialogBinding: DialogFavoritesBinding,
        focusPosition: Int
    ) {
        val adapter = favoritesDialogAdapter ?: return
        val remaining = adapter.itemCount
        dialogBinding.currentCollectionCount.text = "(共${remaining}集)"
        val hasItems = remaining > 0
        dialogBinding.btnCollectionSelect.visibility = if (hasItems) View.VISIBLE else View.GONE
        if (hasItems) {
            dialogBinding.favoritesDialogEmpty.visibility = View.GONE
            dialogBinding.favoritesGrid.visibility = View.VISIBLE
            dialogBinding.favoritesGrid.alpha = 1f
            focusFavoriteGridItemFirstButton(dialogBinding, focusPosition)
        } else {
            dialogBinding.favoritesGrid.visibility = View.GONE
            dialogBinding.favoritesDialogEmpty.visibility = View.VISIBLE
            focusCurrentFavoriteCollectionTab()
        }
    }

    /**
     * 渲染侧边栏合集列表：焦点/点击时切换右侧内容；预置默认合集排在最前。
     */
    private fun renderSidebar(dialogBinding: DialogFavoritesBinding, collections: List<FavoritesStore.CollectionInfo>) {
        if (!sidebarInCreateMode && currentCollectionId != null && collections.none { it.id == currentCollectionId }) {
            currentCollectionId = null
        }
        sidebarAdapter?.submit(collections, currentCollectionId.takeUnless { sidebarInCreateMode || sidebarInImportLiveMode })

        applyFavoritesSidebarFixedTabState(dialogBinding)
    }

    /**
     * 统一刷新收藏页左侧固定 Tab（＋新建合集 / 📡导入直播源）的选中态与光晕下划线。
     * 普通合集 Tab 的选中态由 [CollectionSidebarAdapter] 维护；切到固定 Tab 时会统一清空普通合集选中态。
     */
    private fun applyFavoritesSidebarFixedTabState(dialogBinding: DialogFavoritesBinding) {
        val createSelected = sidebarInCreateMode
        val importSelected = sidebarInImportLiveMode

        applyGlowNavState(
            dialogBinding.btnSidebarNewCollection.root,
            dialogBinding.btnSidebarNewCollection.newCollectionText,
            dialogBinding.btnSidebarNewCollection.newCollectionGlowUnderline,
            createSelected
        )
        applyGlowNavState(
            dialogBinding.btnSidebarImportLive,
            dialogBinding.importLiveText,
            dialogBinding.importLiveGlowUnderline,
            importSelected
        )
        applyGlowNavState(
            dialogBinding.btnFavExport,
            dialogBinding.favExportText,
            dialogBinding.favExportGlowUnderline,
            dialogBinding.btnFavExport.isSelected
        )
        applyGlowNavState(
            dialogBinding.btnFavImport,
            dialogBinding.favImportText,
            dialogBinding.favImportGlowUnderline,
            dialogBinding.btnFavImport.isSelected
        )
        // v1.1.125：合集管理按钮为纯图标（ImageView），无 TextView label，
        // 但焦点/选中态的下划线光晕仍需与相邻按钮完全一致。
        applyGlowNavStateIconOnly(
            dialogBinding.btnFavCollectionManage,
            dialogBinding.favCollectionManageGlowUnderline,
            dialogBinding.btnFavCollectionManage.isSelected
        )
    }

    /** v1.1.125：图标类固定 Tab 的三态样式，与 [applyGlowNavState] 保持一致，只是不改变文字色。 */
    private fun applyGlowNavStateIconOnly(root: View, glow: GlowUnderlineView, selected: Boolean) {
        val focused = root.hasFocus()
        root.isSelected = selected
        glow.setIdleLineVisible(false)
        glow.applyVisualState(selected = focused, active = focused)
        glow.visibility = if (focused) View.VISIBLE else View.GONE
        glow.alpha = if (focused) 1f else 0f
    }

    /**
     * 收藏页二级导航统一三态：选中态只影响文字色；GlowUnderlineView 的下划线/光晕只随焦点显示。
     * 注意 applyVisualState 的 selected 会绘制下划线，因此这里必须传 focused，避免失焦选中残留下划线。
     */
    private fun applyGlowNavState(root: View, label: TextView, glow: GlowUnderlineView, selected: Boolean) {
        val focused = root.hasFocus() || label.hasFocus()
        root.isSelected = selected
        label.isSelected = selected
        glow.setIdleLineVisible(false)
        glow.applyVisualState(selected = focused, active = focused)
        glow.visibility = if (focused) View.VISIBLE else View.GONE
        glow.alpha = if (focused) 1f else 0f
        val color = if (selected || focused) R.color.crayon_yellow else R.color.text_primary
        label.setTextColor(ContextCompat.getColor(this, color))
    }

    /** 切换到「导入直播源」固定 Tab：清空新建合集与普通合集选中态，仅保留导入 Tab 选中。 */
    private fun switchToImportLiveTab(dialogBinding: DialogFavoritesBinding) {
        if (sidebarInImportLiveMode) {
            applyFavoritesSidebarFixedTabState(dialogBinding)
            return
        }
        sidebarInCreateMode = false
        sidebarInImportLiveMode = true
        dialogBinding.favoritesContentLoading.visibility = View.GONE
        ++renderCollectionRequestSeq
        sidebarAdapter?.setSelected(null)
        showImportLiveSourceInputDialog(dialogBinding)
        applyFavoritesSidebarFixedTabState(dialogBinding)
    }

    /**
     * 渲染右侧当前合集内容（非创建模式）。
     * - 更新合集名、重命名/删除按钮的可见性；
     * - 提交条目到网格 Adapter，处理空态；
     * - 若当前处于创建模式，则改为展示新建合集表单，隐藏内容区域。
     */
    private fun renderCurrentCollection(dialogBinding: DialogFavoritesBinding) {
        // 处于「导入直播源」Tab 时（含解析/导入完成后触发的 reload），保持内联导入视图与当前子 Tab
        // （输入链接 / 云端共享），不切回合集内容、也不重置子 Tab，避免从「云端共享」被切走。
        if (sidebarInImportLiveMode) {
            dialogBinding.contentModeContainer.visibility = View.GONE
            dialogBinding.createModeContainer.visibility = View.GONE
            importLiveInlineBinding?.root?.visibility = View.VISIBLE
            applyFavoritesSidebarFixedTabState(dialogBinding)
            return
        }
        if (sidebarInCreateMode) {
            dialogBinding.contentModeContainer.visibility = View.GONE
            dialogBinding.favoritesContentLoading.visibility = View.GONE
            dialogBinding.createModeContainer.visibility = View.VISIBLE
            stopImportLiveSubmitServer()
            importLiveInlineBinding?.root?.visibility = View.GONE
            dialogBinding.btnSidebarNewCollection.root.isSelected = true
            sidebarAdapter?.setSelected(null)
            applyFavoritesSidebarFixedTabState(dialogBinding)
            return
        }
        dialogBinding.contentModeContainer.visibility = View.VISIBLE
        dialogBinding.createModeContainer.visibility = View.GONE
        stopImportLiveSubmitServer()
        importLiveInlineBinding?.root?.visibility = View.GONE
        applyFavoritesSidebarFixedTabState(dialogBinding)

        val id = currentCollectionId
        if (id == null) {
            dialogBinding.currentCollectionTitle.text = "我的收藏"
            dialogBinding.currentCollectionCount.visibility = View.GONE
            favoritesDialogAdapter?.submit(emptyList())
            dialogBinding.favoritesLandingGuide.visibility = View.VISIBLE
            dialogBinding.favoritesContentLoading.visibility = View.GONE
            dialogBinding.favoritesDialogEmpty.visibility = View.GONE
            dialogBinding.favoritesGrid.visibility = View.GONE
            dialogBinding.btnCollectionSelect.visibility = View.GONE
            dialogBinding.btnCollectionRename.visibility = View.GONE
            dialogBinding.btnCollectionDelete.visibility = View.GONE
            sidebarAdapter?.setSelected(null)
            return
        }

        dialogBinding.favoritesLandingGuide.visibility = View.GONE
        dialogBinding.favoritesDialogEmpty.visibility = View.GONE
        dialogBinding.favoritesGrid.visibility = View.GONE
        dialogBinding.favoritesContentLoading.visibility = View.VISIBLE
        dialogBinding.favoritesContentLoading.alpha = 1f
        dialogBinding.favoritesGrid.alpha = 0f
        dialogBinding.btnCollectionSelect.visibility = View.GONE
        dialogBinding.btnCollectionRename.visibility = View.GONE
        dialogBinding.btnCollectionDelete.visibility = View.GONE
        sidebarAdapter?.setSelected(id)

        val requestSeq = ++renderCollectionRequestSeq
        val loadingStartTime = System.currentTimeMillis()
        Thread({
            val collection = try { favoritesStore.collection(id) } catch (_: Throwable) { null }
            val sortedItems = (collection?.items ?: emptyList()).sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.trim() }
            )
            ui.post {
                if (requestSeq != renderCollectionRequestSeq || currentCollectionId != id || sidebarInCreateMode || sidebarInImportLiveMode) return@post
                val itemCount = sortedItems.size
                dialogBinding.currentCollectionTitle.text = collection?.name ?: getString(R.string.favorites_title)
                dialogBinding.currentCollectionCount.text = "(共${itemCount}集)"
                dialogBinding.currentCollectionCount.visibility = View.VISIBLE
                favoritesDialogAdapter?.submit(sortedItems)
                val hasItems = sortedItems.isNotEmpty()
                dialogBinding.favoritesGrid.visibility = View.GONE
                dialogBinding.favoritesDialogEmpty.visibility = View.GONE
                // 多选入口仅在当前合集有内容时显示；空合集不展示批量选择入口。
                dialogBinding.btnCollectionSelect.visibility = if (hasItems) View.VISIBLE else View.GONE

                // 预置合集（含默认合集）：名称不可修改、且不可删除，直接隐藏对应按钮。
                val isPreset = collection?.isPreset ?: false
                dialogBinding.btnCollectionRename.visibility = if (isPreset) View.GONE else View.VISIBLE
                dialogBinding.btnCollectionDelete.visibility = if (isPreset) View.GONE else View.VISIBLE
                finishFavoritesCollectionLoading(dialogBinding, requestSeq, id, hasItems, loadingStartTime)
            }
        }, "favorite-collection-load").start()
    }

    private fun finishFavoritesCollectionLoading(
        dialogBinding: DialogFavoritesBinding,
        requestSeq: Int,
        collectionId: String,
        hasItems: Boolean,
        loadingStartTime: Long
    ) {
        val elapsed = System.currentTimeMillis() - loadingStartTime
        val delay = (200L - elapsed).coerceAtLeast(0L)
        dialogBinding.root.postDelayed({
            if (requestSeq != renderCollectionRequestSeq || currentCollectionId != collectionId || sidebarInCreateMode || sidebarInImportLiveMode) return@postDelayed
            dialogBinding.favoritesContentLoading.visibility = View.GONE
            if (hasItems) {
                dialogBinding.favoritesDialogEmpty.visibility = View.GONE
                dialogBinding.favoritesGrid.animate().cancel()
                dialogBinding.favoritesGrid.alpha = 0f
                dialogBinding.favoritesGrid.visibility = View.VISIBLE
                dialogBinding.favoritesGrid.animate().alpha(1f).setDuration(140L).start()
            } else {
                dialogBinding.favoritesGrid.visibility = View.GONE
                dialogBinding.favoritesGrid.alpha = 1f
                dialogBinding.favoritesDialogEmpty.visibility = View.VISIBLE
            }
        }, delay)
    }

    /** 切换到「新建合集」表单模式：右侧显示表单、清空并预填默认名。 */
    private fun switchToCreateMode(dialogBinding: DialogFavoritesBinding) {
        if (sidebarInCreateMode) return
        sidebarInCreateMode = true
        sidebarInImportLiveMode = false
        dialogBinding.inputCreateCollectionName.setText(FavoritesStore.NEW_COLLECTION_NAME)
        dialogBinding.inputCreateCollectionName.setSelection(
            dialogBinding.inputCreateCollectionName.text.length
        )
        renderCurrentCollection(dialogBinding)
        // 从普通合集 → 新建合集：同步清掉左侧当前合集的归属选中态，避免旧 tab 因 onFocusChanged 先于
        // switchToCreateMode 执行而残留 isActivated=true（v1.1.56）。
        dialogBinding.sidebarCollectionList.post {
            val rv = dialogBinding.sidebarCollectionList
            if (!rv.isComputingLayout) sidebarAdapter?.notifyDataSetChanged()
        }
        // 进入创建模式后保持「新建合集」选中态的厚实暖黄下划线；焦点仍在该项时才叠加 Glow。
        applyFavoritesSidebarFixedTabState(dialogBinding)
    }

    /** 执行新建合集：成功后停留在「新建合集」页面，不自动跳转到新合集内容。 */
    private fun onConfirmCreateCollection(dialogBinding: DialogFavoritesBinding) {
        val name = dialogBinding.inputCreateCollectionName.text.toString().trim()
            .ifBlank { FavoritesStore.NEW_COLLECTION_NAME }
        val (result, _) = favoritesStore.createCollection(name)
        if (result == FavoritesStore.OpResult.SUCCESS) {
            toastMsg(getString(R.string.collection_created))
            // 需求：新建成功后保持在创建模式，重置输入为默认名并刷新左侧列表（新合集会出现在侧栏），
            // 不改变 currentCollectionId、不切到内容区。
            sidebarInCreateMode = true
            sidebarInImportLiveMode = false
            dialogBinding.inputCreateCollectionName.setText(FavoritesStore.NEW_COLLECTION_NAME)
            dialogBinding.inputCreateCollectionName.setSelection(
                dialogBinding.inputCreateCollectionName.text.length
            )
            reloadFavoritesDialog(dialogBinding)
            dialogBinding.inputCreateCollectionName.requestFocus()
        } else {
            toastMsg(opResultMessage(result))
        }
    }

    private fun reloadFavoritesDialog(
        dialogBinding: DialogFavoritesBinding,
        onRendered: (() -> Unit)? = null
    ) {
        val requestSeq = ++renderCollectionRequestSeq
        Thread({
            val collections = try { favoritesStore.collectionsInfo() } catch (_: Throwable) { emptyList() }
            ui.post {
                if (requestSeq != renderCollectionRequestSeq && currentCollectionId != null) return@post
                try {
                    renderSidebar(dialogBinding, collections)
                    renderCurrentCollection(dialogBinding)
                    refreshFavoritesHome()
                    // 侧栏与内容区重建完成后再执行收尾回调（如删除合集后收敛焦点到「默认合集」tab）。
                    onRendered?.invoke()
                } catch (t: Throwable) {
                    android.util.Log.e("MainActivity", "reloadFavoritesDialog failed", t)
                }
            }
        }, "favorites-sidebar-load").start()
    }

    // ------------------------------------------------------------------
    // v1.1.125 合集管理弹窗（勾选 / 上下移动 / 批量删除 / 保存排序）
    // ------------------------------------------------------------------

    /**
     * 展示「合集管理」弹窗：
     * - 仅展示非预置合集（预置合集与默认合集无法排序或删除）。
     * - 支持勾选、全选 / 取消勾选、上移 / 下移、批量删除。
     * - 点击「确认」保存排序并关闭；点击「取消 / Back」直接关闭且不生效。
     */
    private fun showCollectionManageDialog(dialogBinding: DialogFavoritesBinding) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_collection_manage, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_CastTV_Dialog)
            .setView(view).create()

        val listView = view.findViewById<RecyclerView>(R.id.collectionManageList)
        val emptyView = view.findViewById<TextView>(R.id.collectionManageEmpty)
        val countView = view.findViewById<TextView>(R.id.collectionManageCount)
        val btnMoveUp = view.findViewById<TextView>(R.id.btnCollectionManageMoveUp)
        val btnMoveDown = view.findViewById<TextView>(R.id.btnCollectionManageMoveDown)
        val btnSelectAll = view.findViewById<TextView>(R.id.btnCollectionManageSelectAll)
        val btnClear = view.findViewById<TextView>(R.id.btnCollectionManageClear)
        val btnDelete = view.findViewById<TextView>(R.id.btnCollectionManageDelete)
        val btnCancel = view.findViewById<TextView>(R.id.btnCollectionManageCancel)
        val btnConfirm = view.findViewById<TextView>(R.id.btnCollectionManageConfirm)

        // 数据：仅取「非默认 && 非预置」的合集参与排序 / 删除。
        val items = try {
            favoritesStore.collectionsInfo()
                .filter { !it.isDefault && !it.isPreset }
                .toMutableList()
        } catch (_: Throwable) { mutableListOf() }
        val selected = LinkedHashSet<String>()

        listView.layoutManager = LinearLayoutManager(this)
        val adapter = CollectionManageAdapter(items, selected)
        listView.adapter = adapter

        // 判断勾选是否为「单个」或「连续多个」——连续多个即被勾选的行号在 items 中形成连续区间。
        fun isContinuousSelection(): Boolean {
            if (selected.isEmpty()) return false
            val indices = items.mapIndexedNotNull { idx, info ->
                if (info.id in selected) idx else null
            }
            if (indices.isEmpty()) return false
            return indices.last() - indices.first() == indices.size - 1
        }

        fun canMoveUp(): Boolean {
            if (!isContinuousSelection()) return false
            val first = items.indexOfFirst { it.id in selected }
            return first > 0
        }

        fun canMoveDown(): Boolean {
            if (!isContinuousSelection()) return false
            val last = items.indexOfLast { it.id in selected }
            return last in 0 until items.size - 1
        }

        fun updateButtonsState() {
            val moveUpEnabled = canMoveUp()
            val moveDownEnabled = canMoveDown()
            btnMoveUp.isEnabled = moveUpEnabled
            btnMoveUp.alpha = if (moveUpEnabled) 1f else 0.4f
            btnMoveUp.isFocusable = moveUpEnabled
            btnMoveDown.isEnabled = moveDownEnabled
            btnMoveDown.alpha = if (moveDownEnabled) 1f else 0.4f
            btnMoveDown.isFocusable = moveDownEnabled

            val anySelected = selected.isNotEmpty()
            btnDelete.isEnabled = anySelected
            btnDelete.alpha = if (anySelected) 1f else 0.4f

            countView.text = getString(R.string.fav_collection_manage_selected_count, selected.size)
        }

        adapter.onSelectionChanged = { updateButtonsState() }

        if (items.isEmpty()) {
            listView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            listView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
        updateButtonsState()

        btnMoveUp.setOnClickListener {
            if (!canMoveUp()) return@setOnClickListener
            val indices = items.mapIndexedNotNull { idx, info -> if (info.id in selected) idx else null }
            for (idx in indices) {
                val tmp = items[idx - 1]
                items[idx - 1] = items[idx]
                items[idx] = tmp
            }
            adapter.notifyDataSetChanged()
            updateButtonsState()
            // 已移动到顶部：上移按钮会被禁用而丢焦，把焦点交给「下移」按钮。
            if (!canMoveUp()) btnMoveDown.requestFocus()
        }
        btnMoveDown.setOnClickListener {
            if (!canMoveDown()) return@setOnClickListener
            val indices = items.mapIndexedNotNull { idx, info -> if (info.id in selected) idx else null }
            // 从末尾开始交换，避免下移时相互覆盖。
            for (idx in indices.reversed()) {
                val tmp = items[idx + 1]
                items[idx + 1] = items[idx]
                items[idx] = tmp
            }
            adapter.notifyDataSetChanged()
            updateButtonsState()
            // 已移动到底部：下移按钮会被禁用而丢焦，把焦点交给「上移」按钮。
            if (!canMoveDown()) btnMoveUp.requestFocus()
        }
        btnSelectAll.setOnClickListener {
            selected.clear()
            selected.addAll(items.map { it.id })
            adapter.notifyDataSetChanged()
            updateButtonsState()
        }
        btnClear.setOnClickListener {
            selected.clear()
            adapter.notifyDataSetChanged()
            updateButtonsState()
        }
        btnDelete.setOnClickListener {
            if (selected.isEmpty()) return@setOnClickListener
            val toDelete = items.filter { it.id in selected }.map { it.id }
            if (toDelete.isEmpty()) return@setOnClickListener
            showCollectionManageDeleteConfirm(toDelete.size) {
                val ok = favoritesStore.deleteCollections(toDelete)
                toastMsg(getString(R.string.fav_collection_manage_delete_result, ok))
                // 删除后从本地列表移除，同步刷新弹窗和收藏页 sidebar。
                items.removeAll { it.id in toDelete }
                selected.clear()
                adapter.notifyDataSetChanged()
                if (items.isEmpty()) {
                    listView.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                }
                updateButtonsState()
                reloadFavoritesDialog(dialogBinding)
            }
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            // 只保存排序，删除已单独执行。
            val orderedIds = items.map { it.id }
            val result = favoritesStore.reorderCollections(orderedIds)
            if (result == FavoritesStore.OpResult.SUCCESS) {
                toastMsg(getString(R.string.fav_collection_manage_reorder_saved))
            } else if (result != FavoritesStore.OpResult.NOT_FOUND) {
                toastMsg(getString(R.string.fav_collection_manage_reorder_failed))
            }
            dialog.dismiss()
            reloadFavoritesDialog(dialogBinding)
        }

        // Back 键关闭（等同取消）。
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                dialog.dismiss()
                true
            } else false
        }
        dialog.setOnCancelListener { /* no-op：取消视作放弃修改 */ }

        // 列表区上下抖动：到边界抖动一下，不跳出列表；仅移动焦点消费事件。
        listView.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val itemCount = adapter.itemCount
            if (itemCount == 0) return@setOnKeyListener false
            val lm = listView.layoutManager as? LinearLayoutManager
            val focused = listView.findFocus()
            val vh = focused?.let { listView.findContainingViewHolder(it) }
            val pos = vh?.bindingAdapterPosition ?: RecyclerView.NO_POSITION
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (pos == 0) {
                        shakeViewHorizontally(focused ?: listView)
                        return@setOnKeyListener true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (pos == itemCount - 1) {
                        shakeViewHorizontally(focused ?: listView)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        dialog.show()
        // 默认焦点：首个列表项或「取消」按钮（空列表时）。
        listView.post {
            if (items.isNotEmpty()) {
                val vh = listView.findViewHolderForAdapterPosition(0)
                vh?.itemView?.requestFocus()
            } else {
                btnCancel.requestFocus()
            }
        }
    }

    /** v1.1.125：合集管理弹窗内的批量删除二次确认弹窗（深色主题）。 */
    private fun showCollectionManageDeleteConfirm(count: Int, onConfirm: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_CastTV_Dialog)
            .setTitle(R.string.fav_collection_manage_delete_title)
            .setMessage(getString(R.string.fav_collection_manage_delete_confirm, count))
            .setPositiveButton(R.string.fav_collection_manage_delete_ok) { d, _ ->
                d.dismiss()
                onConfirm()
            }
            .setNegativeButton(R.string.fav_collection_manage_delete_cancel) { d, _ -> d.dismiss() }
            .setCancelable(true)
            .show()
    }

    /** v1.1.125：轻量抖动动画（水平方向），复用于合集管理弹窗列表越界反馈。 */
    private fun shakeViewHorizontally(target: View) {
        val anim = ObjectAnimator.ofFloat(target, "translationX", 0f, -8f, 8f, -6f, 6f, -3f, 3f, 0f)
        anim.duration = 260
        anim.start()
    }

    /** v1.1.125：合集管理弹窗的 RecyclerView 适配器。 */
    private inner class CollectionManageAdapter(
        private val data: MutableList<FavoritesStore.CollectionInfo>,
        private val selected: MutableSet<String>
    ) : RecyclerView.Adapter<CollectionManageAdapter.VH>() {

        var onSelectionChanged: (() -> Unit)? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_collection_manage, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = data[position]
            holder.name.text = c.name
            holder.itemCount.text = holder.itemView.context.getString(
                R.string.fav_collection_manage_item_count, c.itemCount
            )
            val isChecked = c.id in selected
            holder.checkBox.isSelected = isChecked
            holder.checkMark.visibility = if (isChecked) View.VISIBLE else View.GONE
            holder.root.isSelected = isChecked

            val toggle = View.OnClickListener {
                val id = data[holder.bindingAdapterPosition].id
                if (id in selected) selected.remove(id) else selected.add(id)
                notifyItemChanged(holder.bindingAdapterPosition)
                onSelectionChanged?.invoke()
            }
            holder.root.setOnClickListener(toggle)
            holder.root.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    toggle.onClick(holder.root)
                    true
                } else false
            }
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val root: View = view
            val checkBox: View = view.findViewById(R.id.collectionManageCheckBox)
            val checkMark: View = view.findViewById(R.id.collectionManageCheckMark)
            val name: TextView = view.findViewById(R.id.collectionManageName)
            val itemCount: TextView = view.findViewById(R.id.collectionManageItemCount)
        }
    }


    // ------------------------------------------------------------------
    // 合集管理弹窗（重命名 / 删除）
    // ------------------------------------------------------------------
    private fun showCollectionRenameDialog(dialogBinding: DialogFavoritesBinding) {
        val id = currentCollectionId ?: return
        val collection = favoritesStore.collection(id) ?: return
        if (collection.isPreset) {
            toastMsg(getString(R.string.collection_preset_rename_locked))
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(collection.name)
            setSelection(text.length)
            hint = getString(R.string.collection_name_hint)
        }
        // v1.1.71：弹窗关闭后把焦点还给触发它的合集操作按钮，避免焦点丢失后 BACK 穿透到一级 Dock。
        val restoreFocus = currentFocus
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.collection_rename_header)
            .setView(input)
            .setPositiveButton(R.string.btn_confirm, null)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setOnDismissListener { restoreFocus?.requestFocus() }
            .create()
        dialog.show()
        input.requestFocus()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isBlank()) {
                toastMsg(getString(R.string.collection_name_required))
                return@setOnClickListener
            }
            val result = favoritesStore.renameCollection(id, name)
            if (result == FavoritesStore.OpResult.SUCCESS) {
                toastMsg(getString(R.string.collection_renamed))
                dialog.dismiss()
                reloadFavoritesDialog(dialogBinding)
            } else {
                toastMsg(opResultMessage(result))
            }
        }
    }

    private fun showCollectionDeleteDialog(dialogBinding: DialogFavoritesBinding) {
        val id = currentCollectionId ?: return
        val collection = favoritesStore.collection(id) ?: return
        if (collection.isPreset) {
            toastMsg(getString(R.string.collection_preset_delete_locked))
            return
        }
        // v1.1.71：删除弹窗关闭后把焦点还给合集删除按钮；
        // v1.1.124：若合集被成功删除，删除按钮已随内容区重建而消失，此时焦点必须收敛到
        //           「默认合集」tab（第一个 tab），不能停留在被删除位置或落到其它区域。
        val restoreFocus = currentFocus
        var collectionDeleted = false
        AlertDialog.Builder(this)
            .setTitle(R.string.collection_delete_header)
            .setMessage(getString(R.string.collection_delete_confirm, collection.name, collection.items.size))
            .setPositiveButton(R.string.btn_delete) { d, _ ->
                val result = favoritesStore.deleteCollection(id)
                if (result == FavoritesStore.OpResult.SUCCESS) {
                    toastMsg(getString(R.string.collection_deleted))
                    collectionDeleted = true
                    currentCollectionId = favoritesStore.defaultCollectionId()
                    // 侧栏/内容区重建完成后，把焦点收敛到「默认合集」tab（currentCollectionId 已切为默认合集）。
                    reloadFavoritesDialog(dialogBinding) { focusCurrentFavoriteCollectionTab() }
                } else {
                    toastMsg(opResultMessage(result))
                }
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setOnDismissListener {
                // 删除成功时不恢复到已消失的删除按钮，交由 reloadFavoritesDialog 回调聚焦「默认合集」tab。
                if (!collectionDeleted) restoreFocus?.requestFocus()
            }
            .show()
    }

    // ------------------------------------------------------------------
    // 导入直播源（M3U / 直播源 JSON）
    // ------------------------------------------------------------------
    /**
     * 收藏页内嵌直播源导入入口：右侧区域展示子 Tab；「输入链接」通过手机扫码提交。
     */
    private fun showImportLiveSourceInputDialog(
        dialogBinding: DialogFavoritesBinding,
        initialUrl: String? = null
    ) {
        val createdNow = importLiveInlineBinding == null
        val b = importLiveInlineBinding ?: DialogImportLiveSourceBinding.inflate(layoutInflater, dialogBinding.contentPanel, false).also { binding ->
            importLiveInlineBinding = binding
            binding.root.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            dialogBinding.contentPanel.addView(binding.root)
        }
        dialogBinding.contentModeContainer.visibility = View.GONE
        dialogBinding.createModeContainer.visibility = View.GONE
        b.root.visibility = View.VISIBLE

        fun syncChildTabVisuals(inputSelected: Boolean) {
            applyGlowNavState(b.tabInputLink, b.tabInputLinkText, b.tabInputLinkGlowUnderline, inputSelected)
            applyGlowNavState(b.tabCloudShare, b.tabCloudShareText, b.tabCloudShareGlowUnderline, !inputSelected)
        }

        var selectedInputTab = b.sectionInputLink.visibility != View.GONE
        fun selectTab(input: Boolean) {
            selectedInputTab = input
            syncChildTabVisuals(input)
            if (input) {
                b.sectionInputLink.visibility = View.VISIBLE
                b.sectionCloudShare.visibility = View.GONE
                startImportLiveSubmitServer(dialogBinding, b)
            } else {
                stopImportLiveSubmitServer()
                b.sectionInputLink.visibility = View.GONE
                b.sectionCloudShare.visibility = View.VISIBLE
                renderCloudShareList(dialogBinding, b, null)
            }
        }

        if (createdNow) {
            b.tabInputLinkGlowUnderline.isFocusable = false
            b.tabInputLinkGlowUnderline.isClickable = false
            b.tabCloudShareGlowUnderline.isFocusable = false
            b.tabCloudShareGlowUnderline.isClickable = false
            b.tabInputLink.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) selectTab(true) else syncChildTabVisuals(selectedInputTab)
            }
            b.tabCloudShare.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) selectTab(false) else syncChildTabVisuals(selectedInputTab)
            }
            b.tabInputLink.setOnClickListener { selectTab(true) }
            b.tabCloudShare.setOnClickListener { selectTab(false) }
            b.tabInputLink.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    selectTab(true)
                    true
                } else false
            }
            b.tabCloudShare.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                    selectTab(false)
                    true
                } else false
            }
        }

        if (initialUrl?.trim().isNullOrBlank()) {
            selectTab(true)
        } else {
            stopImportLiveSubmitServer()
            b.root.visibility = View.GONE
            showImportLiveSourceProgressDialog(dialogBinding, initialUrl!!.trim(), fromCloudShare = false)
        }
    }

    /**
     * 解析 / 预览弹窗关闭（取消或失败）后，重新显示「导入直播源」内联视图并停留在原来的子 Tab。
     * 解析期间只隐藏了内联视图 root、未改动子 Tab 分区可见性，故按当前分区可见性恢复：
     * 云端共享来源仍停留在「☁️ 云端共享」Tab 并刷新列表；输入链接来源恢复局域网提交服务。
     */
    private fun restoreImportLiveTabAfterParse(dialogBinding: DialogFavoritesBinding) {
        if (!sidebarInImportLiveMode) return
        val b = importLiveInlineBinding ?: return
        dialogBinding.contentModeContainer.visibility = View.GONE
        dialogBinding.createModeContainer.visibility = View.GONE
        b.root.visibility = View.VISIBLE
        if (b.sectionCloudShare.visibility == View.VISIBLE) {
            renderCloudShareList(dialogBinding, b, null)
            b.tabCloudShare.requestFocus()
        } else {
            startImportLiveSubmitServer(dialogBinding, b)
            b.tabInputLink.requestFocus()
        }
        applyFavoritesSidebarFixedTabState(dialogBinding)
        // v1.1.105：解析弹窗关闭回到导入 Tab 后，兜底保持 Dock「收藏」选中高亮。
        ensureDockFavoritesSelected()
    }

    private fun startImportLiveSubmitServer(dialogBinding: DialogFavoritesBinding, b: DialogImportLiveSourceBinding) {
        val current = importLiveSubmitServer
        val server = if (current != null) current else LiveSourceSubmitServer().also { importLiveSubmitServer = it }
        if (!server.start()) {
            b.importLiveQr.setImageDrawable(null)
            b.importLiveQrFallback.visibility = View.VISIBLE
            b.importLiveQrFallback.text = "局域网服务启动失败"
            b.importLiveAddress.text = "请检查网络后重试"
            b.importLiveStatus.text = "暂时无法通过手机提交直播源。"
            return
        }
        val ip = NetworkUtils.getLocalIpAddress().orEmpty()
        if (ip.isBlank()) {
            b.importLiveQr.setImageDrawable(null)
            b.importLiveQrFallback.visibility = View.VISIBLE
            b.importLiveQrFallback.text = "未获取到局域网 IP"
            b.importLiveAddress.text = "请确认电视已连接局域网"
            b.importLiveStatus.text = "连接 Wi‑Fi 或有线网络后重新进入此页。"
            return
        }
        val url = "http://$ip:${server.port}"
        val qr = QrCodeGenerator.encode(url, 420)
        b.importLiveQr.setImageBitmap(qr)
        b.importLiveQr.visibility = if (qr == null) View.GONE else View.VISIBLE
        b.importLiveQrFallback.visibility = if (qr == null) View.VISIBLE else View.GONE
        b.importLiveQrFallback.text = if (qr == null) "二维码生成失败\n请手动输入下方地址" else ""
        b.importLiveAddress.text = "或在手机浏览器输入 $url"
        b.importLiveStatus.text = "等待手机提交直播源链接…"
        startImportLivePolling(dialogBinding, b)
    }

    private fun startImportLivePolling(dialogBinding: DialogFavoritesBinding, b: DialogImportLiveSourceBinding) {
        importLivePollRunnable?.let { ui.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                val url = importLiveSubmitServer?.consumeSubmittedUrl()?.trim().orEmpty()
                if (url.isNotBlank()) {
                    stopImportLiveSubmitServer()
                    b.importLiveStatus.text = "已收到链接，正在解析…"
                    b.root.visibility = View.GONE
                    showImportLiveSourceProgressDialog(dialogBinding, url, fromCloudShare = false)
                    return
                }
                if (sidebarInImportLiveMode && b.root.visibility == View.VISIBLE && b.sectionInputLink.visibility == View.VISIBLE) {
                    ui.postDelayed(this, 1000L)
                }
            }
        }
        importLivePollRunnable = task
        ui.postDelayed(task, 1000L)
    }

    private fun stopImportLiveSubmitServer() {
        importLivePollRunnable?.let { ui.removeCallbacks(it) }
        importLivePollRunnable = null
        importLiveSubmitServer?.stop()
        importLiveSubmitServer = null
    }

    /**
     * 渲染「☁️ 云端共享」列表：从 Gitee 拉取 `shared_sources/index.json` 展示真实数据。
     * 加载中显示进度提示，失败时显示错误并可点击重试，空列表显示占位文字。
     */
    private fun renderCloudShareList(
        dialogBinding: DialogFavoritesBinding,
        b: DialogImportLiveSourceBinding,
        parentDialog: AlertDialog?
    ) {
        val container = b.cloudShareListContainer
        container.removeAllViews()
        // 加载中状态
        b.cloudShareEmpty.visibility = View.VISIBLE
        b.cloudShareEmpty.text = "正在加载云端共享直播源…"
        b.cloudShareEmpty.setOnClickListener(null)

        Thread({
            val result = SharedLiveSourceStore.fetchSources()
            ui.post {
                // 弹窗已关闭或已切回输入 Tab 则不再更新
                if ((parentDialog != null && !parentDialog.isShowing) || b.sectionCloudShare.visibility != View.VISIBLE) {
                    return@post
                }
                container.removeAllViews()
                when (result) {
                    is SharedLiveSourceStore.LoadResult.Success -> {
                        val sources = result.sources
                        if (sources.isEmpty()) {
                            b.cloudShareEmpty.visibility = View.VISIBLE
                            b.cloudShareEmpty.text = "暂无共享直播源"
                            b.cloudShareEmpty.setOnClickListener(null)
                        } else {
                            b.cloudShareEmpty.visibility = View.GONE
                            for (source in sources) {
                                bindCloudShareItem(dialogBinding, b, parentDialog, container, source)
                            }
                        }
                    }
                    is SharedLiveSourceStore.LoadResult.Error -> {
                        b.cloudShareEmpty.visibility = View.VISIBLE
                        b.cloudShareEmpty.text = "加载失败：${result.message}\n\n点击此处重试"
                        b.cloudShareEmpty.setOnClickListener {
                            renderCloudShareList(dialogBinding, b, parentDialog)
                        }
                    }
                }
            }
        }, "shared-live-fetch").start()
    }

    /**
     * 绑定单条云端共享直播源：名称 / 分组+频道 / 上传时间 / 👍 点赞（云端同步）/ [导入]。
     */
    private fun bindCloudShareItem(
        dialogBinding: DialogFavoritesBinding,
        b: DialogImportLiveSourceBinding,
        parentDialog: AlertDialog?,
        container: ViewGroup,
        source: SharedLiveSourceStore.SharedSource
    ) {
        val itemBinding = ItemSharedLiveSourceBinding.inflate(layoutInflater, container, false)
        itemBinding.sharedItemName.text = source.name
        val tagText = source.tags.joinToString(" · ")
        itemBinding.sharedItemTags.text = tagText
        itemBinding.sharedItemTags.visibility = if (tagText.isBlank()) View.GONE else View.VISIBLE
        itemBinding.sharedItemMeta.text = "${source.groupCount} 个分组，${source.channelCount} 个频道"
        itemBinding.sharedItemTime.text = "上传于 ${SharedLiveSourceStore.displayTime(source.uploadedAt)}"

        // 热度数与点赞图标拆成独立 View：点赞完成后只刷新这两个 View，不触发任何列表刷新、不主动改焦点。
        fun renderLike() {
            val liked = SharedLiveSourceStore.isLiked(this, source.id)
            itemBinding.sharedItemHeatCount.text = source.likeCount.toString()
            itemBinding.sharedItemLikeButton.isSelected = liked
            itemBinding.sharedItemLikeButton.setImageResource(
                if (liked) R.drawable.ic_thumb_up_filled else R.drawable.ic_thumb_up_outline
            )
        }
        renderLike()
        var likeRequestRunning = false
        itemBinding.sharedItemLikeButton.setOnClickListener {
            if (likeRequestRunning) return@setOnClickListener
            val willLike = !SharedLiveSourceStore.isLiked(this, source.id)
            likeRequestRunning = true
            Thread({
                val res = SharedLiveSourceStore.updateLikeCount(source.id, if (willLike) 1 else -1)
                ui.post {
                    likeRequestRunning = false
                    when (res) {
                        is SharedLiveSourceStore.LikeResult.Success -> {
                            SharedLiveSourceStore.setLiked(this, source.id, willLike)
                            source.likeCount = res.newCount
                            renderLike()
                            toastMsg(if (willLike) "已点赞 👍" else "已取消点赞")
                        }
                        is SharedLiveSourceStore.LikeResult.Error -> {
                            toastMsg("操作失败：${res.message}")
                        }
                    }
                }
            }, "shared-live-like").start()
        }

        // 导入按钮：走现有 M3U/JSON 解析导入流程
        itemBinding.sharedItemImport.setOnClickListener {
            parentDialog?.setOnDismissListener(null)
            parentDialog?.dismiss()
            importLiveInlineBinding?.root?.visibility = View.GONE
            showImportLiveSourceProgressDialog(dialogBinding, source.url, fromCloudShare = true)
        }
        container.addView(itemBinding.root)
    }

    /** 点击解析后关闭输入弹窗，并在独立进度弹窗中展示 6 个步骤节点。 */
    private fun showImportLiveSourceProgressDialog(
        dialogBinding: DialogFavoritesBinding,
        url: String,
        fromCloudShare: Boolean
    ) {
        val b = DialogImportLiveProgressBinding.inflate(layoutInflater)
        val progressDialog = AlertDialog.Builder(this)
            .setView(b.root)
            .setCancelable(false)
            .create()
        progressDialog.setOnShowListener {
            progressDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // v1.1.105：解析弹窗弹出期间，保持一级 Dock「收藏」选中高亮。
            ensureDockFavoritesSelected()
        }
        progressDialog.setOnDismissListener {
            // 弹窗关闭后再兜底一次，确保 Dock「收藏」选中态不被丢失。
            ensureDockFavoritesSelected()
        }
        progressDialog.show()
        startImportLiveSource(dialogBinding, url, fromCloudShare, progressDialog, b)
    }

    /** 后台线程下载并解析直播源，成功后回到主线程弹出预览确认。 */
    private fun startImportLiveSource(
        dialogBinding: DialogFavoritesBinding,
        url: String,
        fromCloudShare: Boolean,
        progressDialog: AlertDialog,
        b: DialogImportLiveProgressBinding
    ) {
        val stepTitles = listOf(
            "校验链接格式",
            "下载文件",
            "识别文件类型",
            "解析内容",
            "整理分组",
            "完成"
        )
        val stepDetails = MutableList(stepTitles.size) { "等待" }
        val stepStates = MutableList(stepTitles.size) { 0 } // 0=等待，1=执行中，2=成功，3=失败
        val stepViews = stepTitles.map { title ->
            TextView(this).apply {
                text = "○ $title：等待"
                textSize = 15f
                setTextColor(0xFFBFC4CC.toInt())
                val padV = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, padV, 0, padV)
                b.importLiveProgressStepList.addView(this)
            }
        }

        fun renderSteps() {
            for (i in stepViews.indices) {
                val view = stepViews[i]
                val title = stepTitles[i]
                val detail = stepDetails[i]
                when (stepStates[i]) {
                    1 -> {
                        view.text = "⏳ $title：执行中"
                        view.setTextColor(0xFFFFD75E.toInt())
                    }
                    2 -> {
                        view.text = "✅ $title：$detail"
                        view.setTextColor(0xFF70E08A.toInt())
                    }
                    3 -> {
                        view.text = "❌ $title：$detail"
                        view.setTextColor(0xFFFF6B6B.toInt())
                    }
                    else -> {
                        view.text = "○ $title：等待"
                        view.setTextColor(0xFFBFC4CC.toInt())
                    }
                }
            }
        }

        fun updateStep(index: Int, running: Boolean, detail: String? = null, failed: Boolean = false) {
            ui.post {
                if (index !in stepViews.indices) return@post
                stepStates[index] = when {
                    failed -> 3
                    running -> 1
                    else -> 2
                }
                stepDetails[index] = detail ?: when {
                    failed -> "执行失败"
                    running -> "执行中"
                    else -> "成功"
                }
                renderSteps()
            }
        }
        renderSteps()

        // 解析失败后展示「取消 / 重新输入」两个操作：取消关闭弹窗并回到来源子 Tab；重新输入保留来源重新解析。
        fun showFailureActions() {
            b.btnImportLiveCancel.visibility = View.VISIBLE
            b.btnImportLiveRetryInput.visibility = View.VISIBLE
            b.btnImportLiveRetryInput.requestFocus()
            b.btnImportLiveRetryInput.setOnClickListener {
                progressDialog.dismiss()
                showImportLiveSourceProgressDialog(dialogBinding, url, fromCloudShare)
            }
            b.btnImportLiveCancel.setOnClickListener {
                progressDialog.dismiss()
                restoreImportLiveTabAfterParse(dialogBinding)
            }
        }

        Thread({
            var result: LiveSourceImporter.ParseResult? = null
            var failMsg: String? = null
            var failedStep = 0
            try {
                result = LiveSourceImporter.parseUrl(url, progress = LiveSourceImporter.ProgressListener { step, running, detail ->
                    failedStep = step
                    updateStep(step, running, detail)
                })
            } catch (f: LiveSourceImporter.Failure) {
                failMsg = f.message
            } catch (t: Throwable) {
                failMsg = t.message ?: "解析失败"
            }
            ui.post {
                val r = result
                if (r == null) {
                    updateStep(failedStep, running = false, detail = failMsg ?: "解析失败", failed = true)
                    showFailureActions()
                    return@post
                }
                if (r.groups.isEmpty()) {
                    updateStep(5, running = false, detail = "直播源未解析到任何频道", failed = true)
                    showFailureActions()
                    return@post
                }
                progressDialog.dismiss()
                showImportLiveSourcePreviewDialog(dialogBinding, r, fromCloudShare)
            }
        }, "live-source-parse").start()
    }

    /** 预览弹窗展示前先在后台读取本地合集索引，避免在主线程触发文件 IO / JSON 解析。 */
    private fun showImportLiveSourcePreviewDialog(
        dialogBinding: DialogFavoritesBinding,
        parsed: LiveSourceImporter.ParseResult,
        fromCloudShare: Boolean
    ) {
        Thread({
            val existingInfo = try { favoritesStore.collectionsInfo() } catch (_: Throwable) { emptyList() }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                showImportLiveSourcePreviewDialog(dialogBinding, parsed, existingInfo, fromCloudShare)
            }
        }, "live-source-preview-index").start()
    }

    /**
     * 弹出预览确认弹窗：展示每个分组的合集名称与频道数，允许用户勾选/取消。
     * 若目标合集名已存在则显示「将覆盖更新」提示。
     */
    private fun showImportLiveSourcePreviewDialog(
        dialogBinding: DialogFavoritesBinding,
        parsed: LiveSourceImporter.ParseResult,
        existingInfo: List<FavoritesStore.CollectionInfo>,
        fromCloudShare: Boolean
    ) {
        val existingNames: Set<String> = existingInfo.map { it.name }.toSet()
        val localCollectionCount = existingInfo.size
        val maxCollections = FavoritesStore.MAX_COLLECTIONS
        val groups = parsed.groups
        val checkedState = BooleanArray(groups.size) { true }
        val skipState = BooleanArray(groups.size) { false }
        val rowViews = mutableListOf<android.widget.CheckBox>()

        val b = DialogImportLivePreviewBinding.inflate(layoutInflater)
        b.importLivePreviewSummary.text = "共解析出 ${groups.size} 个分组、${parsed.totalChannels} 个频道。\n可勾选需要导入的分组："

        fun buildGroupLabel(index: Int): String {
            val g = groups[index]
            return buildString {
                append(g.displayName)
                append("  (")
                append(g.channels.size)
                append(" 频道)")
                if (existingNames.contains(g.displayName)) append("  · 将覆盖更新")
                if (skipState[index]) append("  · 将自动跳过")
            }
        }

        val checkboxTint = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf()
            ),
            intArrayOf(
                0xFF45E08A.toInt(),
                0xFFFFD75E.toInt(),
                0xFF98A0AC.toInt()
            )
        )
        fun applyPreviewCheckVisual(cb: android.widget.CheckBox, checked: Boolean, skipped: Boolean) {
            cb.setTextColor(
                when {
                    skipped -> 0xFF8A8F98.toInt()
                    checked -> 0xFF45E08A.toInt()
                    else -> 0xFFF2F2F2.toInt()
                }
            )
            cb.setTypeface(null, if (checked && !skipped) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        fun refreshImportQuotaStatus() {
            val checkedCount = checkedState.count { it }
            val afterCount = localCollectionCount + checkedCount
            val overflowCount = (afterCount - maxCollections).coerceAtLeast(0)
            var importableCheckedCount = 0
            for (i in groups.indices) {
                skipState[i] = false
                if (checkedState[i]) {
                    importableCheckedCount += 1
                    if (localCollectionCount + importableCheckedCount > maxCollections) {
                        skipState[i] = true
                    }
                }
            }
            val isFull = localCollectionCount >= maxCollections
            b.importLivePreviewQuotaStatus.text = when {
                isFull -> "合集已满（$maxCollections/$maxCollections），无法导入"
                overflowCount > 0 -> "当前本地合集：$localCollectionCount 个 / 上限 $maxCollections 个，本次勾选导入：$checkedCount 个，导入后共：$afterCount 个\n导入后共 $afterCount 个，超出上限 $overflowCount 个，将自动跳过后 $overflowCount 个分组"
                else -> "当前本地合集：$localCollectionCount 个 / 上限 $maxCollections 个，本次勾选导入：$checkedCount 个，导入后共：$afterCount 个"
            }
            b.importLivePreviewQuotaStatus.setTextColor(
                if (isFull || overflowCount > 0) 0xFFFF6B6B.toInt() else 0xFFFFD75E.toInt()
            )
            for ((idx, cb) in rowViews.withIndex()) {
                val skipped = skipState[idx] || isFull
                cb.isEnabled = !skipped
                cb.alpha = if (skipped) 0.45f else 1f
                applyPreviewCheckVisual(cb, checkedState[idx], skipped)
                cb.text = buildGroupLabel(idx)
            }
            b.btnConfirmImportLivePreview.isEnabled = !isFull && checkedCount > 0
            b.btnConfirmImportLivePreview.alpha = if (b.btnConfirmImportLivePreview.isEnabled) 1f else 0.45f
            b.btnToggleAllImportLivePreview.text = if (checkedCount == groups.size) "取消全选" else "全选"
        }

        val density = resources.displayMetrics.density
        val rowPadH = (10 * density).toInt()
        val rowPadV = (8 * density).toInt()
        for (idx in groups.indices) {
            val cb = android.widget.CheckBox(this).apply {
                isChecked = true
                text = buildGroupLabel(idx)
                buttonTintList = checkboxTint
                background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_dialog_focus_item)
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(rowPadH, rowPadV, rowPadH, rowPadV)
                setTextColor(0xFF45E08A.toInt())
                textSize = 15f
                setOnCheckedChangeListener { _, checked ->
                    checkedState[idx] = checked
                    refreshImportQuotaStatus()
                }
            }
            rowViews.add(cb)
            b.importLivePreviewList.addView(cb)
        }
        refreshImportQuotaStatus()

        val restoreFocus = currentFocus
        val dialog = AlertDialog.Builder(this)
            .setView(b.root)
            .setOnDismissListener { restoreFocus?.requestFocus() }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            b.btnConfirmImportLivePreview.requestFocus()
        }
        b.btnCancelImportLivePreview.setOnClickListener {
            dialog.dismiss()
            // 取消预览后回到来源子 Tab（云端共享来源停留在「云端共享」Tab）。
            restoreImportLiveTabAfterParse(dialogBinding)
        }
        b.btnToggleAllImportLivePreview.setOnClickListener {
            val target = checkedState.any { !it }
            for (i in checkedState.indices) checkedState[i] = target
            rowViews.forEach { it.isChecked = target }
            refreshImportQuotaStatus()
        }
        b.btnConfirmImportLivePreview.setOnClickListener {
            val picked = groups.filterIndexed { i, _ -> checkedState[i] && !skipState[i] }
            if (picked.isEmpty()) {
                toastMsg(if (localCollectionCount >= maxCollections) "合集已满（$maxCollections/$maxCollections），无法导入" else "请至少选择一个分组")
                return@setOnClickListener
            }
            dialog.dismiss()
            executeImportLiveSourceGroups(dialogBinding, picked, parsed.sourceUrl, fromCloudShare)
        }
        dialog.show()
    }

    /**
     * 执行导入：按顺序把选中的分组写入合集。同名合集覆盖更新，新增合集受 MAX_COLLECTIONS 限制，
     * 超出剩余空间的分组按顺序丢弃并在 Toast 中提示。
     */
    private fun executeImportLiveSourceGroups(
        dialogBinding: DialogFavoritesBinding,
        selected: List<LiveSourceImporter.LiveGroup>,
        sourceUrl: String,
        fromCloudShare: Boolean
    ) {
        Thread({
            val existingInfo = try { favoritesStore.collectionsInfo() } catch (_: Throwable) { emptyList() }
            val nameToId = existingInfo.associate { it.name to it.id }
            val currentCount = existingInfo.size
            val remainingSlots = (FavoritesStore.MAX_COLLECTIONS - currentCount).coerceAtLeast(0)

            var importedCollections = 0
            var importedChannels = 0
            var skippedGroups = 0
            var newlyCreated = 0

            val now = System.currentTimeMillis()
            for (group in selected) {
                val existingId = nameToId[group.displayName]
                if (existingId == null && newlyCreated >= remainingSlots) {
                    // 已无剩余空间，跳过后续新增
                    skippedGroups += 1
                    continue
                }
                // 频道数量按单合集上限截断
                val maxItems = FavoritesStore.MAX_ITEMS_PER_COLLECTION
                val takeCount = group.channels.size.coerceAtMost(maxItems)
                // v1.1.116 item_id 兼容性补全：导入直播源入口显式生成稳定 item_id
                //（毫秒时间戳 16 进制 + 组内下标），保证批量操作/云同步/HTTP 页面下发指令都能命中。
                val items = group.channels.take(takeCount).mapIndexed { idx, ch ->
                    FavoritesStore.FavoriteItem(
                        itemId = java.lang.Long.toHexString(now) + "-" + idx,
                        title = ch.title.ifBlank { ch.url },
                        uri = ch.url,
                        source = "livesource",
                        time = now,
                        thumbPath = null,
                        artworkPath = null,
                        durationMs = 0L,
                        description = "",
                        resolution = "",
                        isLive = true
                    )
                }
                val targetId = existingId ?: java.util.UUID.randomUUID().toString()
                val result = favoritesStore.importCloudCollection(
                    id = targetId,
                    name = group.displayName,
                    type = FavoritesStore.TYPE_SHARED,
                    items = items
                )
                when (result) {
                    FavoritesStore.OpResult.SUCCESS -> {
                        importedCollections += 1
                        importedChannels += items.size
                        if (existingId == null) newlyCreated += 1
                    }
                    FavoritesStore.OpResult.LIMIT_COLLECTIONS -> {
                        skippedGroups += 1
                    }
                    else -> {
                        skippedGroups += 1
                    }
                }
            }

            ui.post {
                if (importedCollections == 0) {
                    if (remainingSlots == 0 && selected.all { nameToId[it.displayName] == null }) {
                        toastMsg("合集已满（最多${FavoritesStore.MAX_COLLECTIONS}个），请先删除部分合集再导入")
                    } else {
                        toastMsg("导入未成功，请稍后重试")
                    }
                } else {
                    val msg = buildString {
                        append("已导入 ")
                        append(importedCollections)
                        append(" 个合集，共 ")
                        append(importedChannels)
                        append(" 个频道")
                        if (skippedGroups > 0) {
                            append("；")
                            append(skippedGroups)
                            append(" 个分组因合集已满被跳过")
                        }
                    }
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                }
                reloadFavoritesDialog(dialogBinding)
                // 导入成功后：普通来源询问是否将该直播源分享到云端；云端共享来源避免二次上传提示。
                if (importedCollections > 0 && !fromCloudShare) {
                    val groupCount = selected.size
                    val channelCount = selected.sumOf { it.channels.size }
                    showSharePromptDialog(sourceUrl, groupCount, channelCount)
                }
            }
        }, "live-source-import").start()
    }

    // ------------------------------------------------------------------
    // 共享直播源：分享到云端（写入 Gitee 的 shared_sources/index.json）
    // ------------------------------------------------------------------
    /**
     * 导入成功后的分享提示弹窗：
     *  - [分享到云端]：先做局域网 IP 检测，再让用户填写备注名；
     *  - [不了，谢谢]：直接关闭。
     */
    private fun showSharePromptDialog(sourceUrl: String, groupCount: Int, channelCount: Int) {
        if (isFinishing || isDestroyed) return
        val b = DialogSharePromptBinding.inflate(layoutInflater)
        val restoreFocus = currentFocus
        val dialog = AlertDialog.Builder(this)
            .setView(b.root)
            .setOnDismissListener { restoreFocus?.requestFocus() }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            b.btnShareConfirm.requestFocus()
        }
        b.btnShareDecline.setOnClickListener { dialog.dismiss() }
        b.btnShareConfirm.setOnClickListener {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            // 分享前检测是否为局域网地址
            if (isPrivateLanUrl(sourceUrl)) {
                showLanWarningDialog(sourceUrl, groupCount, channelCount)
            } else {
                showShareRemarkDialog(sourceUrl, groupCount, channelCount)
            }
        }
        dialog.show()
    }

    /**
     * 局域网地址提示：其他用户可能无法访问，确认仍要分享？
     */
    private fun showLanWarningDialog(sourceUrl: String, groupCount: Int, channelCount: Int) {
        if (isFinishing || isDestroyed) return
        val restoreFocus = currentFocus
        val dialog = AlertDialog.Builder(this)
            .setTitle("⚠️ 局域网地址提醒")
            .setMessage("该链接为局域网地址，其他用户可能无法访问，确认仍要分享？")
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setPositiveButton("确认分享") { d, _ ->
                d.dismiss()
                showShareRemarkDialog(sourceUrl, groupCount, channelCount)
            }
            .setOnDismissListener { restoreFocus?.requestFocus() }
            .create()
        dialog.show()
    }

    /**
     * 备注名输入弹窗（可选）。确认后写入 Gitee 的 shared_sources/index.json，
     * 成功后 Toast「感谢分享！」。
     */
    private fun showShareRemarkDialog(sourceUrl: String, groupCount: Int, channelCount: Int) {
        if (isFinishing || isDestroyed) return
        val b = DialogShareRemarkBinding.inflate(layoutInflater)
        val restoreFocus = currentFocus
        val dialog = AlertDialog.Builder(this)
            .setView(b.root)
            .setOnDismissListener { restoreFocus?.requestFocus() }
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            b.inputShareRemark.requestFocus()
        }
        b.btnCancelShareRemark.setOnClickListener { dialog.dismiss() }
        b.btnConfirmShareRemark.setOnClickListener {
            val remark = b.inputShareRemark.text.toString().trim()
            // 上传中：禁用按钮，避免重复提交
            b.btnConfirmShareRemark.isEnabled = false
            b.btnCancelShareRemark.isEnabled = false
            b.btnConfirmShareRemark.text = "上传中…"
            Thread({
                val result = SharedLiveSourceStore.shareSource(
                    name = remark,
                    url = sourceUrl,
                    groupCount = groupCount,
                    channelCount = channelCount
                )
                ui.post {
                    when (result) {
                        SharedLiveSourceStore.ShareResult.Success -> {
                            dialog.dismiss()
                            toastMsg("感谢分享！")
                        }
                        SharedLiveSourceStore.ShareResult.AlreadyShared -> {
                            dialog.dismiss()
                            toastMsg("该直播源已在云端共享，感谢支持！")
                        }
                        is SharedLiveSourceStore.ShareResult.Error -> {
                            b.btnConfirmShareRemark.isEnabled = true
                            b.btnCancelShareRemark.isEnabled = true
                            b.btnConfirmShareRemark.text = "确认分享"
                            toastMsg("分享失败：${result.message}")
                        }
                    }
                }
            }, "shared-live-upload").start()
        }
        dialog.show()
    }

    /**
     * 检测 URL 的 host 是否为私有（局域网）IP：
     * 10.x.x.x / 192.168.x.x / 172.16.x.x ~ 172.31.x.x。
     */
    private fun isPrivateLanUrl(url: String): Boolean {
        val host = try {
            java.net.URL(url.trim()).host
        } catch (_: Throwable) {
            return false
        }
        // 仅对纯 IPv4 字面量判定，域名不算局域网
        val parts = host.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val (a, bb) = nums[0] to nums[1]
        return when {
            a == 10 -> true
            a == 192 && bb == 168 -> true
            a == 172 && bb in 16..31 -> true
            else -> false
        }
    }

    // ------------------------------------------------------------------
    // 收藏条目弹窗（改名 / 移动 / 删除）
    // ------------------------------------------------------------------
    private fun showFavoriteEditDialog(item: FavoritesStore.FavoriteItem, onDone: () -> Unit) {
        val collectionId = currentCollectionId ?: return
        val dialogBinding = DialogFavoriteAddBinding.inflate(layoutInflater)
        dialogBinding.favoriteAddHeader.setText(R.string.favorite_edit_header)
        dialogBinding.inputFavoriteTitle.setText(item.title)
        dialogBinding.inputFavoriteTitle.setSelection(item.title.length)
        dialogBinding.textFavoriteUri.text = item.uri

        // v1.1.71：编辑弹窗关闭后把焦点还给触发它的卡片按钮，避免焦点丢失后 BACK 穿透到一级 Dock。
        val restoreFocus = currentFocus
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.btn_confirm, null)
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setOnDismissListener { restoreFocus?.requestFocus() }
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogBinding.inputFavoriteTitle.requestFocus()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newTitle = dialogBinding.inputFavoriteTitle.text.toString().trim()
            if (newTitle.isBlank()) {
                toastMsg(getString(R.string.favorite_title_required))
                return@setOnClickListener
            }
            val result = favoritesStore.updateItemTitle(collectionId, item.uri, newTitle)
            if (result == FavoritesStore.OpResult.SUCCESS) {
                toastMsg(getString(R.string.favorite_title_updated))
                dialog.dismiss()
                onDone()
            } else {
                toastMsg(opResultMessage(result))
            }
        }
    }

    private fun showFavoriteMoveDialog(item: FavoritesStore.FavoriteItem, position: Int) {
        val fromId = currentCollectionId ?: return
        val targets = favoritesStore.collectionsInfo().filter { it.id != fromId }
        if (targets.isEmpty()) {
            toastMsg(getString(R.string.favorite_move_no_target))
            return
        }
        val names = targets.map { it.name }.toTypedArray()
        // v1.1.71：移动弹窗关闭后把焦点还给触发它的卡片按钮，避免焦点丢失后 BACK 穿透到一级 Dock。
        // v1.1.124：移动成功后卡片会随网格重建被回收，旧按钮已失效，改由 onDone 回调聚焦内容区，
        //           此处不再恢复到已失效的旧按钮，避免焦点回跳到二级导航 tab。
        val restoreFocus = currentFocus
        var contentChanged = false
        AlertDialog.Builder(this)
            .setTitle(R.string.favorite_move_header)
            .setItems(names) { d, which ->
                val target = targets[which]
                val result = favoritesStore.moveItem(fromId, item.uri, target.id)
                if (result == FavoritesStore.OpResult.SUCCESS) {
                    toastMsg(getString(R.string.favorite_moved, target.name))
                    contentChanged = true
                    d.dismiss()
                    val dialogBinding = favoritesDialogBinding
                    val adapter = favoritesDialogAdapter
                    if (dialogBinding != null && adapter?.removeItemAt(position, item.uri) == true) {
                        applyFavoriteLocalRemovalResult(dialogBinding, 0)
                    } else if (dialogBinding != null) {
                        reloadFavoritesDialog(dialogBinding) {
                            val renderedDialog = favoritesDialogBinding ?: return@reloadFavoritesDialog
                            if (!focusFavoriteGridItemFirstButton(renderedDialog, 0)) focusCurrentFavoriteCollectionTab()
                        }
                    }
                } else {
                    toastMsg(opResultMessage(result))
                    d.dismiss()
                }
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setOnDismissListener { if (!contentChanged) restoreFocus?.requestFocus() }
            .show()
    }

    private fun showFavoriteDeleteDialog(item: FavoritesStore.FavoriteItem, position: Int) {
        val collectionId = currentCollectionId ?: return
        // v1.1.71：删除弹窗关闭后把焦点还给触发它的卡片按钮，避免焦点丢失后 BACK 穿透到一级 Dock。
        // v1.1.124：删除成功后卡片会随网格重建被回收，旧按钮已失效，改由 onDone 回调聚焦内容区，
        //           此处不再恢复到已失效的旧按钮，避免焦点回跳到二级导航 tab。
        val restoreFocus = currentFocus
        var contentChanged = false
        AlertDialog.Builder(this)
            .setTitle(R.string.favorite_delete_header)
            .setMessage(getString(R.string.favorite_delete_confirm, item.title.ifBlank { item.uri }))
            .setPositiveButton(R.string.btn_delete) { d, _ ->
                val result = favoritesStore.removeItem(collectionId, item.uri)
                if (result == FavoritesStore.OpResult.SUCCESS) {
                    toastMsg(getString(R.string.favorite_deleted))
                    contentChanged = true
                    d.dismiss()
                    val dialogBinding = favoritesDialogBinding
                    val adapter = favoritesDialogAdapter
                    if (dialogBinding != null && adapter?.removeItemAt(position, item.uri) == true) {
                        val remaining = adapter.itemCount
                        val focusPosition = if (position < remaining) position else remaining - 1
                        applyFavoriteLocalRemovalResult(dialogBinding, focusPosition)
                    } else if (dialogBinding != null) {
                        reloadFavoritesDialog(dialogBinding) {
                            val renderedDialog = favoritesDialogBinding ?: return@reloadFavoritesDialog
                            if (!focusFavoriteGridItemFirstButton(renderedDialog, position)) focusCurrentFavoriteCollectionTab()
                        }
                    }
                } else {
                    toastMsg(opResultMessage(result))
                    d.dismiss()
                }
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .setOnDismissListener { if (!contentChanged) restoreFocus?.requestFocus() }
            .show()
    }

    private fun toastMsg(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 将存储层操作结果映射为用户可读提示。 */
    private fun opResultMessage(result: FavoritesStore.OpResult): String = when (result) {
        FavoritesStore.OpResult.LIMIT_TOTAL ->
            getString(R.string.collection_total_limit_reached, FavoritesStore.MAX_TOTAL_FAVORITES)
        FavoritesStore.OpResult.LIMIT_COLLECTION_ITEMS ->
            getString(R.string.collection_item_limit_reached, FavoritesStore.MAX_ITEMS_PER_COLLECTION)
        FavoritesStore.OpResult.LIMIT_COLLECTIONS ->
            getString(R.string.collection_limit_reached, FavoritesStore.MAX_COLLECTIONS)
        FavoritesStore.OpResult.NOT_ALLOWED -> getString(R.string.collection_default_locked)
        FavoritesStore.OpResult.INVALID -> getString(R.string.favorite_title_required)
        else -> getString(R.string.collection_op_failed)
    }

    private fun handleFocusedListBoundaryShake(
        recyclerView: RecyclerView,
        keyCode: Int,
        event: KeyEvent
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        val adapterCount = recyclerView.adapter?.itemCount ?: return false
        if (adapterCount <= 0) return false
        val focused = currentFocus ?: recyclerView.focusedChild ?: return false
        val itemView = recyclerView.findContainingItemView(focused) ?: recyclerView.focusedChild ?: return false
        val position = recyclerView.getChildAdapterPosition(itemView)
        if (position == RecyclerView.NO_POSITION) return false
        val atTop = keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0
        val atBottom = keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == adapterCount - 1
        if (!atTop && !atBottom) return false
        if (hasFocusableTargetInDirection(focused, recyclerView, keyCode)) return false
        playBoundaryShake(focused)
        return true
    }

    private fun handleItemBoundaryShake(
        focusedView: View,
        keyCode: Int,
        event: KeyEvent,
        position: Int,
        itemCount: Int
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        if (position == RecyclerView.NO_POSITION || itemCount <= 0) return false
        val atTop = keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0
        val atBottom = keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == itemCount - 1
        if (!atTop && !atBottom) return false
        val recyclerView = findAncestorRecyclerView(focusedView)
        if (recyclerView != null && hasFocusableTargetInDirection(focusedView, recyclerView, keyCode)) return false
        playBoundaryShake(focusedView)
        return true
    }

    private fun findAncestorRecyclerView(view: View): RecyclerView? {
        var parent: android.view.ViewParent? = view.parent
        while (parent is View) {
            if (parent is RecyclerView) return parent
            parent = (parent as View).parent
        }
        return null
    }

    private fun hasFocusableTargetInDirection(focused: View, recyclerView: RecyclerView, keyCode: Int): Boolean {
        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
        val next = focused.focusSearch(direction)
        val boundaryPanel = when {
            favoritesDialogBinding?.sidebarPanel?.let { isDescendantOf(recyclerView, it) } == true -> favoritesDialogBinding?.sidebarPanel
            favoritesDialogBinding?.contentPanel?.let { isDescendantOf(recyclerView, it) } == true -> favoritesDialogBinding?.contentPanel
            else -> recyclerView.parent as? View
        } ?: return false
        if (next != null && next !== focused && next.visibility == View.VISIBLE && next.isEnabled && next.isFocusable && isDescendantOf(next, boundaryPanel)) {
            return true
        }
        return hasFocusableTargetByGeometry(focused, boundaryPanel, direction)
    }

    private fun hasFocusableTargetByGeometry(focused: View, boundaryPanel: View, direction: Int): Boolean {
        val focusedRect = android.graphics.Rect()
        if (!focused.getGlobalVisibleRect(focusedRect)) return false
        var found = false
        fun visit(view: View) {
            if (found || view.visibility != View.VISIBLE || !view.isEnabled) return
            if (view !== focused && view.isFocusable) {
                val rect = android.graphics.Rect()
                if (view.getGlobalVisibleRect(rect)) {
                    val verticalMatch = when (direction) {
                        View.FOCUS_UP -> rect.bottom <= focusedRect.top
                        View.FOCUS_DOWN -> rect.top >= focusedRect.bottom
                        else -> false
                    }
                    val horizontalOverlap = rect.left < focusedRect.right && rect.right > focusedRect.left
                    if (verticalMatch && horizontalOverlap) {
                        found = true
                        return
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(boundaryPanel)
        return found
    }

    /**
     * v1.1.105：ScrollView（如网络诊断内容）滚动到顶/底时，再按对应方向键抖动一下、不首尾循环。
     * 未到边界时返回 false，交给系统继续滚动。
     */
    private fun handleScrollViewBoundaryShake(
        scrollView: android.widget.ScrollView,
        keyCode: Int,
        event: KeyEvent
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        val child = scrollView.getChildAt(0) ?: return false
        val maxScroll = (child.height - (scrollView.height - scrollView.paddingTop - scrollView.paddingBottom))
            .coerceAtLeast(0)
        val atTop = keyCode == KeyEvent.KEYCODE_DPAD_UP && scrollView.scrollY <= 0
        val atBottom = keyCode == KeyEvent.KEYCODE_DPAD_DOWN && scrollView.scrollY >= maxScroll
        if (atTop || atBottom) {
            playBoundaryShake(scrollView)
            return true
        }
        return false
    }

    private fun playBoundaryShake(view: View) {
        view.animate().cancel()
        val distance = dp(8).toFloat()
        ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -distance, distance, -distance * 0.7f, distance * 0.7f, 0f).apply {
            duration = 220L
            start()
        }
    }

    /**
     * v1.1.80：判断两个视图是否位于同一水平行（用于卡片按钮按 → 时鉴别是否落到「同一行」的下一个按钮）。
     * 采用视图在窗口坐标系的 top 与高度的中心重合度判定：若中心 Y 差小于任一视图高度的一半即视为同一行。
     */
    private fun isInSameRow(a: View, b: View): Boolean {
        val ra = android.graphics.Rect().also { a.getGlobalVisibleRect(it) }
        val rb = android.graphics.Rect().also { b.getGlobalVisibleRect(it) }
        if (ra.isEmpty || rb.isEmpty) return false
        val ay = (ra.top + ra.bottom) / 2f
        val by = (rb.top + rb.bottom) / 2f
        val threshold = minOf(ra.height(), rb.height()) / 2f
        return kotlin.math.abs(ay - by) <= threshold
    }

    private inner class FavoriteAdapter(
        val onPlay: (FavoritesStore.FavoriteItem) -> Unit,
        val onEdit: (FavoritesStore.FavoriteItem) -> Unit,
        val onMove: (FavoritesStore.FavoriteItem, Int) -> Unit,
        val onDelete: (FavoritesStore.FavoriteItem, Int) -> Unit
    ) : RecyclerView.Adapter<FavoriteAdapter.VH>() {

        private val items = mutableListOf<FavoritesStore.FavoriteItem>()

        init {
            // 稳定 id 必须使用真正唯一的 itemId，避免重复 uri 导致 RecyclerView 发生 ID 冲突。
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long =
            items[position].itemId.hashCode().toLong()

        fun submit(newItems: List<FavoritesStore.FavoriteItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun removeItemAt(position: Int, uri: String): Boolean {
            if (position !in items.indices || items[position].uri != uri) return false
            items.removeAt(position)
            notifyItemRemoved(position)
            return true
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return if (hours > 0L) {
                "%02d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val itemBinding = ItemFavoriteGridBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(itemBinding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.binding.gridTitle.text = item.title.ifBlank { item.uri }
            if (item.resolution.isNotBlank()) {
                holder.binding.gridResolution.text = item.resolution
                holder.binding.gridResolution.visibility = View.VISIBLE
            } else {
                holder.binding.gridResolution.text = ""
                holder.binding.gridResolution.visibility = View.GONE
            }
            if (item.description.isNotBlank()) {
                holder.binding.gridDescription.text = item.description
                holder.binding.gridDescription.visibility = View.VISIBLE
            } else {
                holder.binding.gridDescription.text = ""
                holder.binding.gridDescription.visibility = View.GONE
            }
            if (item.durationMs > 0L) {
                holder.binding.gridDuration.text = formatDuration(item.durationMs)
                holder.binding.gridDuration.visibility = View.VISIBLE
            } else {
                holder.binding.gridDuration.text = ""
                holder.binding.gridDuration.visibility = View.GONE
            }
            // 加载收藏缩略图，取值口径统一：artworkPath(封面) > thumbPath(截图) > 兜底图（均判存在）。
            Thumbnails.load(holder.binding.gridThumb, item.displayThumbPath())
            holder.binding.gridLiveBadge.visibility = if (item.isLive) View.VISIBLE else View.GONE
            // v1.1.115：批量选择已改为独立弹窗，卡片本体不再切换批量态外观；始终展示操作按钮、卡片点击=播放。
            holder.binding.favoriteGridItemRoot.isSelected = false
            holder.binding.gridSelectBox.visibility = View.GONE
            holder.binding.gridBtnPlayContainer.visibility = View.VISIBLE
            holder.binding.gridBtnEditContainer.visibility = View.VISIBLE
            holder.binding.gridBtnMoveContainer.visibility = View.VISIBLE
            holder.binding.gridBtnDeleteContainer.visibility = View.VISIBLE
            holder.binding.gridBtnLaterContainer.visibility = View.VISIBLE
            holder.binding.favoriteGridItemRoot.isFocusable = false
            holder.binding.favoriteGridItemRoot.setOnClickListener { onPlay(item) }
            val boundaryKeyListener = View.OnKeyListener { view, keyCode, event ->
                handleFavoriteGridVerticalFocus(view, keyCode, event, holder.bindingAdapterPosition)
            }
            // v1.1.80：三级视频卡片按钮按 → 若同行/同容器右侧无可聚焦元素则抖动 + 消费。
            // gridBtnMove（第一行最右）与 gridBtnLater（第二行最右）在正常布局下右侧无同行按钮。
            val rightBoundaryShakeListener = View.OnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val next = view.focusSearch(View.FOCUS_RIGHT)
                    val panel = favoritesDialogBinding?.contentPanel
                    val ok = next != null && panel != null && isDescendantOf(next, panel) &&
                        isInSameRow(view, next)
                    if (!ok) {
                        playBoundaryShake(view)
                        return@OnKeyListener true
                    }
                }
                false
            }
            // 组合：优先边界抖动（→ 最右），再走上下垂直路由。
            val play = holder.binding.gridBtnPlay
            val edit = holder.binding.gridBtnEdit
            val move = holder.binding.gridBtnMove
            val delete = holder.binding.gridBtnDelete
            val later = holder.binding.gridBtnLater
            val card = holder.binding.favoriteGridItemRoot
            val actionButtons = listOf(play, edit, move, delete, later)
            card.animate().cancel()
            card.scaleX = 1f
            card.scaleY = 1f
            // v1.1.116 主题化：DarkGray = 暖黄描边卡片；Classic = 蓝色描边卡片。
            card.setBackgroundResource(
                ThemeManager.resolveDrawableAttr(
                    this@MainActivity,
                    R.attr.themeFavoriteGridCardBg,
                    R.drawable.bg_favorite_grid_card
                )
            )
            bindFavoriteCardFocusScale(card, actionButtons)
            holder.binding.favoriteGridItemRoot.setOnKeyListener(boundaryKeyListener)
            play.setOnKeyListener(boundaryKeyListener)
            edit.setOnKeyListener(boundaryKeyListener)
            move.setOnKeyListener { view, keyCode, event ->
                if (rightBoundaryShakeListener.onKey(view, keyCode, event)) return@setOnKeyListener true
                boundaryKeyListener.onKey(view, keyCode, event)
            }
            delete.setOnKeyListener(boundaryKeyListener)
            later.setOnKeyListener { view, keyCode, event ->
                if (rightBoundaryShakeListener.onKey(view, keyCode, event)) return@setOnKeyListener true
                boundaryKeyListener.onKey(view, keyCode, event)
            }
            holder.binding.gridBtnPlay.setOnClickListener { onPlay(item) }
            holder.binding.gridBtnEdit.setOnClickListener { onEdit(item) }
            holder.binding.gridBtnMove.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onMove(item, pos)
            }
            holder.binding.gridBtnDelete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onDelete(item, pos)
            }
            bindGlowUnderline(holder.binding.gridBtnPlayUnderline)
            bindGlowUnderline(holder.binding.gridBtnEditUnderline)
            bindGlowUnderline(holder.binding.gridBtnMoveUnderline)
            bindGlowUnderline(holder.binding.gridBtnDeleteUnderline)
            bindGlowUnderline(holder.binding.gridBtnLaterUnderline)
            holder.binding.gridBtnLater.text = getString(
                if (com.bd.casttv.queue.PlayQueueStore.get(this@MainActivity).findByUri(item.uri) != null)
                    R.string.queue_remove else R.string.queue_watch_later
            )
            holder.binding.gridBtnLater.setOnClickListener {
                toggleQueueSafe(item.title, item.uri, item.source ?: "favorite")
            }
        }

        private fun bindFavoriteCardFocusScale(card: View, buttons: List<View>) {
            buttons.forEach { button ->
                button.setOnFocusChangeListener { _, hasFocus ->
                    card.animate().cancel()
                    if (hasFocus) {
                        card.setBackgroundResource(R.drawable.bg_favorite_grid_card_button_focused)
                        card.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120L).start()
                    } else {
                        card.post {
                            if (buttons.none { it.hasFocus() }) {
                                card.animate().cancel()
                                // v1.1.116 主题化：失焦回到当前主题对应的默认卡片背景。
                                card.setBackgroundResource(
                                    ThemeManager.resolveDrawableAttr(
                                        this@MainActivity,
                                        R.attr.themeFavoriteGridCardBg,
                                        R.drawable.bg_favorite_grid_card
                                    )
                                )
                                card.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                            }
                        }
                    }
                }
            }
        }

        private fun handleFavoriteGridVerticalFocus(
            focusedView: View,
            keyCode: Int,
            event: KeyEvent,
            position: Int
        ): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
            if (position == RecyclerView.NO_POSITION || itemCount <= 0) return false

            // v1.1.80：识别按钮所在行——第一行 gridBtnPlay/Edit/Move；第二行 gridBtnDelete/Later。
            val isFirstRow = focusedView.id == R.id.gridBtnPlay ||
                focusedView.id == R.id.gridBtnEdit ||
                focusedView.id == R.id.gridBtnMove
            val isSecondRow = focusedView.id == R.id.gridBtnDelete ||
                focusedView.id == R.id.gridBtnLater

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // 第一行按 ↓：交给系统焦点搜索，让焦点自然落到同一张卡片的第二行按钮。
                    if (isFirstRow) return false
                    // 第二行按 ↓：非最后一张卡片跳到下一张卡片的第一行；最后一张卡片则抖动 + 消费。
                    if (isSecondRow) {
                        val targetPosition = position + 1
                        if (targetPosition in 0 until itemCount) {
                            val recyclerView = findAncestorRecyclerView(focusedView) ?: return false
                            val targetSourceId = when (focusedView.id) {
                                R.id.gridBtnLater -> R.id.gridBtnMove
                                else -> R.id.gridBtnPlay
                            }
                            recyclerView.smoothScrollToPosition(targetPosition)
                            recyclerView.post {
                                requestFavoriteItemFocus(recyclerView, targetPosition, targetSourceId)
                            }
                            return true
                        }
                        // 最后一张卡片第二行 ↓：抖动。
                        return handleItemBoundaryShake(focusedView, keyCode, event, position, itemCount)
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    // 第二行按 ↑：交给系统焦点搜索，让焦点自然回到同一张卡片的第一行按钮。
                    if (isSecondRow) return false
                    // 第一行按 ↑：第一张卡片直接回到顶部工具栏「批量选择」；其他卡片跳到上一张卡片的第二行。
                    if (isFirstRow) {
                        if (position == 0) {
                            favoritesDialogBinding?.btnCollectionSelect?.let { selectButton ->
                                if (selectButton.visibility == View.VISIBLE && selectButton.isFocusable && selectButton.isEnabled) {
                                    selectButton.requestFocus()
                                    return true
                                }
                            }
                            return false
                        }
                        val targetPosition = position - 1
                        if (targetPosition in 0 until itemCount) {
                            val recyclerView = findAncestorRecyclerView(focusedView) ?: return false
                            val targetSourceId = when (focusedView.id) {
                                R.id.gridBtnMove -> R.id.gridBtnLater
                                else -> R.id.gridBtnDelete
                            }
                            recyclerView.smoothScrollToPosition(targetPosition)
                            recyclerView.post {
                                requestFavoriteItemFocus(recyclerView, targetPosition, targetSourceId)
                            }
                            return true
                        }
                        return false
                    }
                }
            }

            // 兜底路径：按 ↑/↓ 时保留原按位置跳卡的行为。
            val targetPosition = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> position - 1
                KeyEvent.KEYCODE_DPAD_DOWN -> position + 1
                else -> position
            }
            if (targetPosition in 0 until itemCount) {
                val recyclerView = findAncestorRecyclerView(focusedView) ?: return false
                recyclerView.smoothScrollToPosition(targetPosition)
                recyclerView.post {
                    requestFavoriteItemFocus(recyclerView, targetPosition, focusedView.id)
                }
                return true
            }
            return handleItemBoundaryShake(focusedView, keyCode, event, position, itemCount)
        }

        private fun requestFavoriteItemFocus(recyclerView: RecyclerView, position: Int, sourceViewId: Int) {
            val holder = recyclerView.findViewHolderForAdapterPosition(position) as? VH
            if (holder == null) {
                recyclerView.scrollToPosition(position)
                recyclerView.post { requestFavoriteItemFocus(recyclerView, position, sourceViewId) }
                return
            }
            val target = when (sourceViewId) {
                R.id.gridBtnPlay -> holder.binding.gridBtnPlay
                R.id.gridBtnEdit -> holder.binding.gridBtnEdit
                R.id.gridBtnMove -> holder.binding.gridBtnMove
                R.id.gridBtnDelete -> holder.binding.gridBtnDelete
                R.id.gridBtnLater -> holder.binding.gridBtnLater
                R.id.favoriteGridItemRoot -> holder.binding.favoriteGridItemRoot
                else -> holder.binding.gridBtnPlay
            }
            val focusTarget = if (target.visibility == View.VISIBLE && target.isEnabled && target.isFocusable) {
                target
            } else if (holder.binding.favoriteGridItemRoot.isFocusable) {
                holder.binding.favoriteGridItemRoot
            } else {
                holder.binding.gridBtnPlay
            }
            focusTarget.requestFocus()
        }

        inner class VH(val binding: ItemFavoriteGridBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private fun bindGlowUnderline(underline: com.bd.casttv.ui.GlowUnderlineView) {
        // 视频卡片按钮三态优化：焦点态改为 XML selector 暖黄边框，禁用旧下划线/光晕层。
        underline.visibility = View.GONE
        underline.isEnabled = false
        underline.isFocusable = false
        underline.isClickable = false
    }

    /**
     * 收藏弹窗左侧侧边栏合集列表 Adapter。
     * - 焦点变化：立即回调 [onSelect]，触发右侧内容切换（复用现有切换逻辑，与 OK 键行为一致）。
     * - 选中态：白色圆角背景 + 深色字（在 bg_sidebar_item / sidebar_item_text selector 中实现）。
     */
    private inner class CollectionSidebarAdapter(
        val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<CollectionSidebarAdapter.VH>() {

        private val data = mutableListOf<FavoritesStore.CollectionInfo>()
        private var selectedId: String? = null
        private var attachedRecyclerView: RecyclerView? = null

        init {
            // 稳定 id：合集 id 唯一，作为稳定键复用 ViewHolder。
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long =
            data[position].id.hashCode().toLong()

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)
            attachedRecyclerView = recyclerView
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            if (attachedRecyclerView === recyclerView) attachedRecyclerView = null
            super.onDetachedFromRecyclerView(recyclerView)
        }

        fun positionOf(id: String?): Int = data.indexOfFirst { it.id == id }

        fun submit(collections: List<FavoritesStore.CollectionInfo>, selected: String?) {
            data.clear()
            data.addAll(collections)
            selectedId = selected
            notifyDataSetChanged()
        }

        fun setSelected(id: String?) {
            if (selectedId == id) return
            val oldId = selectedId
            selectedId = id
            val rv = attachedRecyclerView
            val refreshChangedItems = {
                val oldPos = positionOf(oldId)
                val newPos = positionOf(id)
                if (rv != null) {
                    updateVisibleHolderSelection(rv, oldPos)
                    updateVisibleHolderSelection(rv, newPos)
                }
                if (oldPos != RecyclerView.NO_POSITION) notifyItemChanged(oldPos)
                if (newPos != RecyclerView.NO_POSITION && newPos != oldPos) notifyItemChanged(newPos)
            }
            // 同一区域 Tab 切换时必须在当前帧同步旧/新 holder 的 Glow 状态，避免 post 到下一帧造成
            // 旧 Tab 先隐藏、新 Tab 尚未显示的可见空白帧；notifyItemChanged 仅作为复用/离屏兜底。
            refreshChangedItems()
        }

        private fun selectPositionImmediately(position: Int) {
            val id = data.getOrNull(position)?.id ?: return
            if (selectedId == id) return
            val oldId = selectedId
            selectedId = id
            attachedRecyclerView?.let { rv ->
                updateVisibleHolderSelection(rv, positionOf(oldId))
                updateVisibleHolderSelection(rv, position)
            }
        }

        private fun updateVisibleHolderSelection(recyclerView: RecyclerView, position: Int) {
            if (position == RecyclerView.NO_POSITION) return
            val holder = recyclerView.findViewHolderForAdapterPosition(position) as? VH ?: return
            val item = data.getOrNull(position) ?: return
            bindSelectionState(holder, item.id == selectedId)
        }

        private fun bindSelectionState(holder: VH, selected: Boolean) {
            applyGlowNavState(
                holder.binding.root,
                holder.binding.sidebarItemText,
                holder.binding.sidebarGlowUnderline,
                selected
            )
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemFavoritesSidebarBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = data[position]
            val root = holder.binding.root
            val tv = holder.binding.sidebarItemText
            tv.text = c.name

            val selected = c.id == selectedId
            // v1.1.52：合集归属选中态需要在 TV 端也生效。
            bindSelectionState(holder, selected)
            holder.binding.sidebarGlowUnderline.isFocusable = false
            holder.binding.sidebarGlowUnderline.isClickable = false

            val selectAction = {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < data.size) onSelect(data[pos].id)
            }
            root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) selectPositionImmediately(pos)
                    selectAction()
                }
                bindSelectionState(holder, c.id == selectedId)
            }
            root.setOnClickListener {
                // v1.1.80：从二级 sidebar 合集 tab 按 OK/DPAD_CENTER 明确进入三级内容区，
                // 默认焦点落到顶部工具栏「批量选择」按钮 btnCollectionSelect。
                selectAction()
                enterFavoritesThirdLevelDefaultFocus()
            }
            root.setOnKeyListener { view, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // v1.1.80：按 → 从二级切进三级，默认焦点到 btnCollectionSelect。
                    selectAction()
                    enterFavoritesThirdLevelDefaultFocus()
                    return@setOnKeyListener true
                }
                handleItemBoundaryShake(view, keyCode, event, holder.bindingAdapterPosition, itemCount)
            }

            // v1.1.70 焦点越界修复：合集侧栏是 sidebarPanel(垂直 LinearLayout) 内 weight=1 的
            // RecyclerView，其下方还有底部「导出 / 导入」按钮。当焦点落在列表最后一个 tab 上再按
            // 「下」键时，RecyclerView 内部已无更多可聚焦 item，会把 focusSearch 交还给父容器做
            // 几何搜索；由于收藏页是叠在主 Activity 之上的内嵌面板，几何最近的可聚焦目标可能落到
            // 主 Activity Dock 的「帮助」tab，导致焦点穿透面板。这里为「最后一个 item」显式指定
            // 下 / forward 方向落到底部导出按钮；非最后一个 item 复位为 NO_ID，避免 ViewHolder
            // 复用后残留错误的焦点指向。
            if (position == data.size - 1) {
                root.nextFocusDownId = R.id.btnFavExport
                root.nextFocusForwardId = R.id.btnFavExport
            } else {
                root.nextFocusDownId = View.NO_ID
                root.nextFocusForwardId = View.NO_ID
            }
        }

        inner class VH(val binding: ItemFavoritesSidebarBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
