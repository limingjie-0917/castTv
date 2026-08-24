package com.bd.casttv.routine

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.bd.casttv.R
import java.lang.ref.WeakReference
import java.util.Calendar

/**
 * 全局「系统提示气泡」管理器（生活作息 + 健康提醒）。
 *
 * 定稿行为：
 * 1) 展示位置：屏幕顶部、水平居中、距顶部留边距（手机 24dp / TV 32dp），系统提示风格；
 *    全局 Overlay，附加到当前 Activity 的 android.R.id.content，覆盖在页面内容之上。
 * 2) 常规状态（当天累计前台使用 < 2h）：前台时每到整点触发一次（每小时一次，不再包含半点），
 *    显示 30s 自动隐藏；文案取表 A（随时间段命中）。
 * 3) 健康提醒状态（当天累计前台使用 ≥ 2h）：气泡切为全局常驻（不自动消失），
 *    文案取表 B，并随时间段实时更新。
 * 4) 关闭：气泡带关闭按钮；用户关闭后本次前台会话内不再出现；下次重新打开 App（新会话）恢复。
 *
 * 「前台会话」以「已启动的 Activity 计数从 0 变 1 / 从 1 变 0」界定，并对退后台做短暂防抖，
 * 从而在 App 内部 Activity 跳转（MainActivity ↔ PlayerActivity）时不会误判为会话结束。
 */
object RoutineReminderManager : Application.ActivityLifecycleCallbacks {

    private const val TAG = "RoutineReminder"


    /** 常规气泡展示时长（30s）。 */
    private const val TEMP_VISIBLE_MS = 30_000L

    /** 周期巡检间隔：用于检测「累计满 2h」以及常驻文案随时间段更新。 */
    private const val TICK_MS = 30_000L

    /** 退后台防抖：Activity 跳转的瞬时空档不算离开前台。 */
    private const val BG_CONFIRM_MS = 700L

    /** 健康提醒阈值：当天累计前台使用 2 小时。 */
    private const val THRESHOLD_MS = 2L * 60L * 60L * 1000L

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var usage: UsageTimeTracker
    private var initialized = false

    // 前台会话状态 ------------------------------------------------------------
    private var startedCount = 0
    private var inForeground = false
    private var currentActivity: WeakReference<Activity>? = null


    /** 本次会话内用户是否已手动关闭（关闭后本会话不再出现）。 */
    private var closedThisSession = false

    // 气泡视图状态 ------------------------------------------------------------
    private var visible = false
    private var persistent = false
    private var bubbleView: View? = null
    private var bubbleActivity: WeakReference<Activity>? = null

    fun init(app: Application) {
        if (initialized) return
        initialized = true
        usage = UsageTimeTracker(app)
        app.registerActivityLifecycleCallbacks(this)
    }

    // region 前后台判定 -------------------------------------------------------

    override fun onActivityStarted(activity: Activity) {
        handler.removeCallbacks(bgConfirmTask)
        startedCount++
        if (!inForeground) onEnterForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        if (startedCount == 0) handler.postDelayed(bgConfirmTask, BG_CONFIRM_MS)
    }

    private val bgConfirmTask = Runnable {
        if (startedCount == 0 && inForeground) onEnterBackground()
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
        // 页面切换后若气泡逻辑上应可见，则在新页面重新挂载。
        if (visible) attachAndShow(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        // 离开该页面时仅摘除视图，保留 visible/persistent 逻辑态，便于下个页面重挂。
        detachFrom(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (bubbleActivity?.get() === activity) {
            bubbleView = null
            bubbleActivity = null
        }
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    // endregion

    // region 会话生命周期 -----------------------------------------------------

    private fun onEnterForeground() {
        inForeground = true
        usage.onForeground()
        // 新会话：重置会话内关闭标记。
        closedThisSession = false
        persistent = false
        visible = false
        handler.removeCallbacks(boundaryTask)
        scheduleNextBoundary()
        handler.removeCallbacks(tickTask)
        handler.postDelayed(tickTask, TICK_MS)
    }

    private fun onEnterBackground() {
        inForeground = false
        usage.onBackground()
        handler.removeCallbacks(boundaryTask)
        handler.removeCallbacks(tickTask)
        handler.removeCallbacks(autoHideTask)
        // 退后台移除视图并复位可见态（会话结束）。
        currentActivity?.get()?.let { detachFrom(it) }
        visible = false
        persistent = false
    }

    // endregion

    // region 展示调度 ---------------------------------------------------------

    /**
     * 整点触发：在前台时精确对齐到 xx:00（每小时一次，不再包含半点）。
     * 触发后再次调度下一次，避免 drift。
     */
    private val boundaryTask = object : Runnable {
        override fun run() {
            if (inForeground && !closedThisSession) {
                if (usage.currentTotalMs() >= THRESHOLD_MS) {
                    showPersistent()
                } else {
                    showTempNow()
                }
            }
            scheduleNextBoundary()
        }
    }

    private fun scheduleNextBoundary() {
        handler.removeCallbacks(boundaryTask)
        if (!inForeground) return

        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            // 只对齐到下一个 xx:00；即便当前正好是 xx:00，也顺延到下一个整点，避免刚触发后再次立即触发。
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val delay = (next.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
        handler.postDelayed(boundaryTask, delay)
    }

    private val tickTask = object : Runnable {
        override fun run() {
            if (!inForeground) return
            // 累计满 2h → 切换/保持常驻；常驻中随时间段刷新文案。
            if (!closedThisSession && usage.currentTotalMs() >= THRESHOLD_MS) {
                showPersistent()
            } else if (visible && persistent) {
                updateBubbleContent()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val autoHideTask = Runnable {
        if (!persistent) hideInternal()
    }

    /** 常规气泡：显示 30s 后自动隐藏（整点/半点触发）。 */
    private fun showTempNow() {
        val activity = currentActivity?.get() ?: return
        persistent = false
        visible = true
        attachAndShow(activity)
        handler.removeCallbacks(autoHideTask)
        handler.postDelayed(autoHideTask, TEMP_VISIBLE_MS)
    }

    /** 健康提醒气泡：全局常驻，不自动隐藏。 */
    private fun showPersistent() {
        if (visible && persistent) {
            updateBubbleContent()
            return
        }
        val activity = currentActivity?.get() ?: return
        persistent = true
        visible = true
        handler.removeCallbacks(autoHideTask)
        attachAndShow(activity)
    }

    private fun onUserClose() {
        closedThisSession = true
        handler.removeCallbacks(autoHideTask)
        hideInternal()
    }

    /** 逻辑隐藏：摘除视图并复位可见态（不影响会话内的一次性标记）。 */
    private fun hideInternal() {
        visible = false
        persistent = false
        currentActivity?.get()?.let { detachFrom(it) }
    }

    // endregion

    // region 视图挂载 ---------------------------------------------------------

    private fun attachAndShow(activity: Activity) {
        if (activity.isFinishing) return
        try {
            // 已挂载在当前 Activity：仅刷新文案。
            if (bubbleView != null && bubbleActivity?.get() === activity &&
                bubbleView?.parent != null
            ) {
                updateBubbleContent()
                bubbleView?.visibility = View.VISIBLE
                return
            }
            // 切换了 Activity：先从旧宿主摘除。
            bubbleActivity?.get()?.let { if (it !== activity) detachFrom(it) }

            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val view = activity.layoutInflater
                .inflate(R.layout.overlay_routine_bubble, content, false)
            view.findViewById<View>(R.id.routineBubbleClose)?.setOnClickListener { onUserClose() }

            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(activity, if (isTv(activity)) 32f else 24f)
            }
            content.addView(view, lp)
            bubbleView = view
            bubbleActivity = WeakReference(activity)
            updateBubbleContent()
            animateIn(view)
        } catch (t: Throwable) {
            Log.w(TAG, "attachAndShow failed", t)
        }
    }

    private fun detachFrom(activity: Activity) {
        val view = bubbleView ?: return
        if (bubbleActivity?.get() !== activity) return
        (view.parent as? ViewGroup)?.removeView(view)
        bubbleView = null
        bubbleActivity = null
    }

    private fun updateBubbleContent() {
        val view = bubbleView ?: return
        val bubble = RoutineContent.bubbleFor(Calendar.getInstance(), health = persistent)
        // 布局已去掉左侧图标与二级小字，这里仅刷新主文案。
        view.findViewById<TextView>(R.id.routineBubbleMain)?.text = bubble.main
    }

    private fun animateIn(view: View) {
        view.alpha = 0f
        view.translationY = -dp(view.context, 12f).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(220L).start()
    }

    private fun isTv(activity: Activity): Boolean {
        val mode = activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return mode == Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun dp(context: android.content.Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        ).toInt()

    // endregion
}
