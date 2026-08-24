package com.bd.casttv.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Very small local diagnostic logger used for TV-side crash investigation.
 *
 * The file lives in app private storage: filesDir/crash.log.
 * It is append-only and intentionally best-effort: logging must never crash the app.
 *
 * ANR 优化（v1.1.131）：落盘改为单线程后台 Executor 异步串行执行，
 * 调用方（含主线程的生命周期 logD/logE）不再被磁盘 IO 阻塞。
 */
object LocalCrashLog {
    private const val TAG = "LocalCrashLog"
    const val FILE_NAME = "crash.log"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 专用单线程后台落盘：保证写入顺序，且不阻塞调用线程。 */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "crash-log-io").apply { isDaemon = true }
    }

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    fun markAppStart(context: Context, versionName: String, versionCode: Long) {
        append(context, "\n================ APP START ${timestamp()} version=$versionName($versionCode) ================")
    }

    fun d(context: Context?, tag: String, message: String) {
        append(context, "${timestamp()} D/$tag: $message")
    }

    fun e(context: Context?, tag: String, message: String, throwable: Throwable? = null) {
        append(context, buildString {
            append("${timestamp()} E/$tag: $message")
            if (throwable != null) {
                append('\n')
                append(Log.getStackTraceString(throwable))
            }
        })
    }

    fun append(context: Context?, line: String) {
        if (context == null) return
        val appContext = context.applicationContext
        // 异步串行写盘：即使从主线程调用也不阻塞 UI。
        ioExecutor.execute {
            try {
                file(appContext).appendText(line + "\n")
            } catch (t: Throwable) {
                Log.e(TAG, "append local crash log failed", t)
            }
        }
    }

    private fun timestamp(): String = fmt.format(Date())
}
