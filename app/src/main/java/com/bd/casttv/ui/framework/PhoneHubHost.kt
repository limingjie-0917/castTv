package com.bd.casttv.ui.framework

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.bd.casttv.dlna.PhoneHubServer
import com.bd.casttv.dlna.PlaybackController
import com.bd.casttv.favorites.FavoritesStore
import com.bd.casttv.player.PlayerActivity
import com.bd.casttv.queue.PlayQueueStore
import com.bd.casttv.settings.Settings

/**
 * 新框架下的手机交互中心宿主，将老 MainActivity 中 phoneHubServer 的启停/回调逻辑抽取到这里。
 *
 * - `start()` / `stop()` 与老 MainActivity 端口候选（8899、8090）保持一致
 * - 服务持久化开关走 SharedPreferences `phone_hub_service`（复用老配置）
 * - 已连接客户端统计由 [PhoneHubClientTracker] 承担，PhoneHubServer 在 serve() 顶部记录一次命中
 */
object PhoneHubHost {

    private val portCandidates = intArrayOf(8899, 8090)

    @Volatile private var server: PhoneHubServer? = null
    @Volatile private var activePort: Int = 0
    @Volatile private var starting: Boolean = false

    private val listeners = mutableSetOf<Listener>()
    private val main = Handler(Looper.getMainLooper())

    interface Listener {
        fun onPhoneHubStateChanged(running: Boolean, port: Int)
    }

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun isRunning(): Boolean = server != null
    fun port(): Int = activePort

    fun isEnabledPref(context: Context): Boolean =
        context.applicationContext.getSharedPreferences("phone_hub_service", Context.MODE_PRIVATE)
            .getBoolean("enabled", false)

    fun setEnabledPref(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences("phone_hub_service", Context.MODE_PRIVATE)
            .edit().putBoolean("enabled", enabled).apply()
    }

    /** 若已开启，则确保后台服务运行；从 [NewMainActivity.onCreate] / PhoneHubPage.onEnter 调用。 */
    fun ensureRunning(context: Context) {
        if (isEnabledPref(context)) startAsync(context.applicationContext) { }
    }

    /** 异步启动（不阻塞主线程），最终结果通过 [callback] 回调；返回 true 表示已在运行或已被安排启动。 */
    fun startAsync(context: Context, callback: (Boolean) -> Unit) {
        if (server != null) { callback(true); return }
        if (starting) { callback(true); return }
        starting = true
        val appContext = context.applicationContext
        Thread({
            val ok = startBlocking(appContext)
            main.post {
                starting = false
                if (!ok) setEnabledPref(appContext, false) else setEnabledPref(appContext, true)
                notifyChanged()
                callback(ok)
            }
        }, "phone-hub-start").start()
    }

    fun stop(context: Context) {
        try { server?.stop() } catch (_: Throwable) {}
        server = null
        activePort = 0
        PhoneHubClientTracker.clear()
        setEnabledPref(context.applicationContext, false)
        notifyChanged()
    }

    private fun notifyChanged() {
        val running = server != null
        val port = activePort
        listeners.toList().forEach { it.onPhoneHubStateChanged(running, port) }
    }

    private fun startBlocking(appContext: Context): Boolean {
        if (server != null) return true
        val store = PlayQueueStore.get(appContext)
        val fav = FavoritesStore(appContext)
        val settings = Settings(appContext)
        val started = portCandidates.firstOrNull { port ->
            try {
                val srv = PhoneHubServer(
                    port = port,
                    favoritesStore = fav,
                    queueStore = store,
                    settings = settings,
                    pushPlayHandler = { mode, triggerPlayFirst -> onPushPlay(appContext, mode, triggerPlayFirst) },
                    playSingleHandler = { uri, title -> onPlaySingle(appContext, uri, title) },
                    settingsChangeHandler = { _, _ -> /* 新架构里通过 SettingsChangeBus 感知，忽略 */ }
                )
                srv.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = srv
                activePort = port
                true
            } catch (t: Throwable) {
                android.util.Log.w("PhoneHubHost", "phone-hub start on $port failed", t)
                false
            }
        }
        return started != null
    }

    private fun onPushPlay(appContext: Context, mode: String, triggerPlayFirst: Boolean) {
        main.post {
            val store = PlayQueueStore.get(appContext)
            if (store.size() == 0) return@post
            if (mode == "override") {
                for (item in store.all()) {
                    if (item.status != PlayQueueStore.Status.PENDING) {
                        store.setStatus(item.id, PlayQueueStore.Status.PENDING)
                    }
                }
            }
            if (triggerPlayFirst) {
                val first = store.nextPending() ?: return@post
                store.setStatus(first.id, PlayQueueStore.Status.PLAYING)
                launchPlayer(appContext, first.uri, first.title, "phone_hub")
            }
        }
    }

    private fun onPlaySingle(appContext: Context, uri: String, title: String) {
        if (uri.isBlank()) return
        main.post { launchPlayer(appContext, uri, title, "phone_hub") }
    }

    private fun launchPlayer(appContext: Context, uri: String, title: String, sourceHint: String) {
        PlaybackController.recordPlaybackHistory(uri, title, sourceHint, PlaybackController.currentArtworkUrl())
        val intent = Intent(appContext, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PlayerActivity.EXTRA_URI, uri)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_SOURCE, sourceHint)
        }
        try { appContext.startActivity(intent) } catch (_: Throwable) {}
    }
}
