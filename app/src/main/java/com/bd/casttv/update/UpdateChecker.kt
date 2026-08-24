package com.bd.casttv.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.bd.casttv.BuildConfig
import com.bd.casttv.sync.GiteeApi
import org.json.JSONObject

/**
 * version.json 版本检查器。
 *
 * 注意：Gitee raw 公共文件对本仓库返回 403，无法匿名读取；改为复用 [GiteeApi] 的
 * contents 接口读取仓库根目录 version.json。令牌由 GiteeApi 内部处理并在日志中脱敏，
 * 检查更新流程不会在任何提示 / 日志中暴露 token。
 */
object UpdateChecker {
    private const val VERSION_PATH = "version.json"

    fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    fun check(context: Context): CheckResult {
        if (!hasNetwork(context)) return CheckResult.NoNetwork
        return try {
            when (val result = GiteeApi.getFileResult(VERSION_PATH)) {
                is GiteeApi.ApiResult.Success -> {
                    val body = result.value.content
                    if (body.isBlank()) return CheckResult.Error("版本信息为空，请稍后再试")
                    val info = VersionInfo.fromJson(JSONObject(body))
                    if (info.versionCode > BuildConfig.VERSION_CODE) {
                        CheckResult.HasUpdate(info)
                    } else {
                        CheckResult.Latest(BuildConfig.VERSION_NAME)
                    }
                }
                // 均不透出仓库地址 / token，仅返回通用文案。
                GiteeApi.ApiResult.NotFound -> CheckResult.Error("未找到版本信息，请稍后再试")
                is GiteeApi.ApiResult.Error -> CheckResult.Error("版本信息获取失败，请稍后再试")
            }
        } catch (t: Throwable) {
            CheckResult.Error("版本检查失败，请稍后再试")
        }
    }

    data class VersionInfo(
        val versionCode: Int,
        val versionName: String,
        val releaseNote: String,
        val apkUrl: String,
        val forceUpdate: Boolean,
    ) {
        companion object {
            fun fromJson(json: JSONObject): VersionInfo = VersionInfo(
                versionCode = json.optInt("versionCode", 0),
                versionName = json.optString("versionName", ""),
                releaseNote = json.optString("releaseNote", ""),
                apkUrl = json.optString("apkUrl", ""),
                forceUpdate = json.optBoolean("forceUpdate", false),
            )
        }
    }

    sealed class CheckResult {
        data class HasUpdate(val info: VersionInfo) : CheckResult()
        data class Latest(val currentVersionName: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
        object NoNetwork : CheckResult()
    }
}
