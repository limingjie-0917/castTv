package com.bd.casttv.routine

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「当天累计前台使用时长」统计器。
 *
 * 口径（用户定稿）：
 * - 统计对象：App 处于前台可见状态的累计时长。
 * - 明确计入：**正在投屏播放但用户没有任何操作** 的前台停留时间（只要 App 在前台即计入）。
 * - 不计入：App 退到后台 / 不可见 / 被挂起。
 * - 清零：按本地时间每天 00:00 自动清零（跨天清零）。
 *
 * 累计值持久化到 SharedPreferences，避免进程被杀后丢失。使用 [SystemClock.elapsedRealtime]
 * 计算时段增量（不受用户改系统时间影响），日期归属则用本地墙钟时间判定。
 */
class UsageTimeTracker(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 本次前台会话起点（elapsedRealtime）；0 表示当前不在前台。 */
    private var foregroundStartRealtime = 0L

    /** 进入前台：开始计时。 */
    fun onForeground() {
        rolloverIfNeeded()
        foregroundStartRealtime = SystemClock.elapsedRealtime()
    }

    /** 退到后台：把本次前台时长累加并落盘。 */
    fun onBackground() {
        if (foregroundStartRealtime > 0L) {
            val delta = SystemClock.elapsedRealtime() - foregroundStartRealtime
            rolloverIfNeeded()
            val accum = prefs.getLong(KEY_ACCUM_MS, 0L) + delta.coerceAtLeast(0L)
            prefs.edit()
                .putString(KEY_DATE, today())
                .putLong(KEY_ACCUM_MS, accum)
                .apply()
            foregroundStartRealtime = 0L
        }
    }

    /** 当前「当天累计前台使用时长」（含正在进行的前台会话）。单位：毫秒。 */
    fun currentTotalMs(): Long {
        rolloverIfNeeded()
        val accum = prefs.getLong(KEY_ACCUM_MS, 0L)
        val running = if (foregroundStartRealtime > 0L) {
            (SystemClock.elapsedRealtime() - foregroundStartRealtime).coerceAtLeast(0L)
        } else 0L
        return accum + running
    }

    /** 跨天检测：若持久化日期不是今天，则把累计清零并把日期切到今天。 */
    private fun rolloverIfNeeded() {
        val today = today()
        if (prefs.getString(KEY_DATE, null) != today) {
            prefs.edit()
                .putString(KEY_DATE, today)
                .putLong(KEY_ACCUM_MS, 0L)
                .apply()
            // 若跨天时仍在前台，把计时基线重置到当下，丢弃跨天前的运行增量。
            if (foregroundStartRealtime > 0L) {
                foregroundStartRealtime = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun today(): String = DATE_FMT.format(Date())

    companion object {
        private const val PREFS = "casttv_usage"
        private const val KEY_DATE = "usage_date"
        private const val KEY_ACCUM_MS = "usage_accum_ms"
        private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
