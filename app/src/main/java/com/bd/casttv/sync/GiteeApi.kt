package com.bd.casttv.sync

import android.util.Base64
import android.util.Log
import com.bd.casttv.dlna.SsdpDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Gitee API 工具类：封装仓库文件的读写操作。
 *
 * 令牌加固（v1.1.124）：
 * - 令牌以「XOR + 分片」混淆存储：拆成 3 个分片，各字节与滚动密钥异或后以整型数组保存，
 *   运行时动态拼接、异或还原，明文不出现在源码常量中，提高静态逆向成本。
 * - 令牌统一通过 `Authorization: Bearer <token>` 请求头下发，URL / 请求体中不再出现明文 token。
 * - 任何日志 / Toast / 界面均不输出 token（URL 打印前仍做 redact 兜底）。
 */
object GiteeApi {

    private const val OWNER = "bdCasttv"
    private const val REPO = "video-source"
    private const val BASE_URL = "https://gitee.com/api/v5/repos/bdCasttv/video-source/contents"
    private const val RAW_BASE_URL = "https://gitee.com/api/v5/repos/bdCasttv/video-source/raw"

    // 令牌以 XOR + 分片混淆存储：3 个分片各字节与 TOKEN_XOR_KEY 滚动异或，运行时拼接还原。
    // 密钥跨全部分片连续滚动（全局下标 % 密钥长度）。
    private val TOKEN_XOR_KEY = intArrayOf(0x5A, 0x3C, 0x71, 0xE9, 0x2D, 0x84, 0xB6, 0x1F, 0xC3, 0x47)
    private val TOKEN_PART_1 = intArrayOf(0x69, 0x0F, 0x12, 0x8F, 0x1E, 0xE1, 0xD3, 0x7E, 0xA1, 0x22, 0x6C)
    private val TOKEN_PART_2 = intArrayOf(0x0D, 0x10, 0x88, 0x4B, 0xB5, 0x83, 0x7C, 0xA1, 0x26, 0x3E, 0x0C)
    private val TOKEN_PART_3 = intArrayOf(0x14, 0xDE, 0x15, 0xB3, 0xD7, 0x2A, 0xA6, 0x25, 0x3C, 0x58)

    /** 运行时把混淆分片异或还原并拼接为明文令牌；密钥跨分片连续滚动。 */
    private fun deobfuscateToken(): String {
        val sb = StringBuilder()
        var k = 0
        for (part in arrayOf(TOKEN_PART_1, TOKEN_PART_2, TOKEN_PART_3)) {
            for (enc in part) {
                val c = (enc xor TOKEN_XOR_KEY[k % TOKEN_XOR_KEY.size]) and 0xFF
                sb.append(c.toChar())
                k++
            }
        }
        return sb.toString()
    }

    private val accessToken: String by lazy { deobfuscateToken() }

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val TAG = "GiteeApi"

    /**
     * 获取文件内容和 sha。
     * @return Pair(content, sha) 或 null（文件不存在/网络错误）
     */
    fun getFile(path: String): FileResult? {
        return getFileResult(path).getOrNull()
    }

    /** 获取文件内容和 sha，并保留失败原因，便于上传流程展示明确错误。 */
    fun getFileResult(path: String): ApiResult<FileResult> {
        val url = contentsUrl(path)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Accept", "application/json")
                setAuthHeader()
            }
            SsdpDiagnostics.logCloudSync("请求前：GET ${redactToken(url)}，body=0B")
            val code = conn.responseCode
            if (code == 404) {
                val errorBody = conn.readErrorBody().take(200)
                SsdpDiagnostics.logCloudSync("请求结果：GET $path 失败，HTTP $code：$errorBody")
                return ApiResult.NotFound
            }
            if (code != 200) {
                val errorBody = conn.readErrorBody().take(200)
                SsdpDiagnostics.logCloudSync("请求结果：GET $path 失败，HTTP $code：$errorBody")
                return ApiResult.Error("GET $path 失败，HTTP $code：$errorBody")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            SsdpDiagnostics.logCloudSync("请求结果：GET $path 成功，HTTP $code：${body.take(200)}")
            val json = parseFileObjectOrNull(body)
            if (json == null) {
                SsdpDiagnostics.logCloudSync("请求结果：GET $path 响应不是有效文件对象，按新文件处理")
                return ApiResult.NotFound
            }
            val contentB64 = json.optString("content", "").replace("\n", "")
            val sha = json.optString("sha", "")
            val content = if (contentB64.isBlank()) "" else String(Base64.decode(contentB64, Base64.DEFAULT), Charsets.UTF_8)
            ApiResult.Success(FileResult(content = content, sha = sha))
        } catch (e: Exception) {
            SsdpDiagnostics.logCloudSync("请求异常：GET $path，${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}，按新文件处理")
            ApiResult.NotFound
        } finally {
            conn?.disconnect()
        }
    }

    /** 获取二进制文件内容，用于 App 内下载私有仓库 APK。 */
    fun getBinaryFileResult(path: String): ApiResult<BinaryFileResult> {
        val url = rawUrl(path)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = 120_000
                setRequestProperty("Accept", "application/octet-stream")
                setAuthHeader()
            }
            SsdpDiagnostics.logCloudSync("请求前：GET ${redactToken(url)}，raw binary")
            val code = conn.responseCode
            if (code == 404) {
                val errorBody = conn.readErrorBody().take(200)
                SsdpDiagnostics.logCloudSync("请求结果：GET raw $path 失败，HTTP $code：$errorBody")
                return ApiResult.NotFound
            }
            if (code != 200) {
                val errorBody = conn.readErrorBody().take(200)
                SsdpDiagnostics.logCloudSync("请求结果：GET raw $path 失败，HTTP $code：$errorBody")
                return ApiResult.Error("GET raw $path 失败，HTTP $code：$errorBody")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return ApiResult.Error("GET raw $path 失败：文件内容为空")
            SsdpDiagnostics.logCloudSync("请求结果：GET raw $path 成功，HTTP $code，bytes=${bytes.size}")
            ApiResult.Success(BinaryFileResult(bytes = bytes, sha = ""))
        } catch (e: Exception) {
            SsdpDiagnostics.logCloudSync("请求异常：GET raw $path，${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
            ApiResult.Error("GET raw $path 异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 创建或更新文件。
     * @param path 文件路径
     * @param content 文件内容（UTF-8 文本）
     * @param sha 已有文件的 sha（更新时必须），新建时传 null 或空字符串
     * @return true=成功
     */
    fun putFile(path: String, content: String, sha: String?): Boolean {
        return putFileResult(path, content, sha).isSuccess
    }

    /**
     * 创建或更新文件，并保留 HTTP 状态码/错误体。新文件必须用 POST，已有文件才用 PUT+sha。
     *
     * @param commitMessage 可选自定义提交信息；为空则沿用默认的 "sync: create/update {path}"。
     */
    fun putFileResult(path: String, content: String, sha: String?, commitMessage: String = ""): ApiResult<Unit> {
        val normalizedSha = sha?.takeIf { it.isNotBlank() }
        val url = contentsUrl(path)
        var conn: HttpURLConnection? = null
        try {
            val contentB64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val commitMsg = commitMessage.ifBlank {
                "sync: ${if (normalizedSha == null) "create" else "update"} $path"
            }
            val json = JSONObject().apply {
                put("message", commitMsg)
                put("content", contentB64)
                if (normalizedSha != null) put("sha", normalizedSha)
            }
            val requestBody = json.toString().toByteArray(Charsets.UTF_8)
            val method = if (normalizedSha == null) "POST" else "PUT"
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doInput = true
                doOutput = true
                setFixedLengthStreamingMode(requestBody.size)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setAuthHeader()
            }
            Log.i(TAG, "${conn.requestMethod} $path start, url=${redactToken(url)}, bodyBytes=${requestBody.size}, contentBase64Length=${contentB64.length}")
            SsdpDiagnostics.logCloudSync("请求前：$method ${redactToken(url)}，body=${requestBody.size}B")
            conn.outputStream.use { it.write(requestBody) }
            val code = conn.responseCode
            return if (code in 200..201) {
                val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.take(200)
                Log.i(TAG, "${conn.requestMethod} $path success, code=$code, bytes=${content.toByteArray(Charsets.UTF_8).size}")
                SsdpDiagnostics.logCloudSync("请求结果：$method $path 成功，HTTP $code：$responseBody")
                ApiResult.Success(Unit)
            } else {
                val errorBody = conn.readErrorBody()
                val errorMsg = "${conn.requestMethod} $path 失败，HTTP $code：$errorBody"
                Log.e(TAG, "$errorMsg, url=${redactToken(url)}, bodyKeys=message/content${if (normalizedSha != null) "/sha" else ""}, contentBase64Length=${contentB64.length}")
                SsdpDiagnostics.logCloudSync("请求结果：$method $path 失败，HTTP $code：$errorBody")
                ApiResult.Error(errorMsg)
            }
        } catch (e: Exception) {
            val method = conn?.requestMethod ?: if (normalizedSha == null) "POST" else "PUT"
            val httpDetail = conn?.readHttpErrorSafely()
            val exceptionMessage = "${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}"
            val errorMsg = if (httpDetail.isNullOrBlank()) {
                "$method $path 异常：$exceptionMessage"
            } else {
                "$method $path 异常：$exceptionMessage；$httpDetail"
            }
            Log.e(TAG, "$errorMsg, url=${redactToken(url)}", e)
            SsdpDiagnostics.logCloudSync("请求异常：$method $path，$exceptionMessage")
            return ApiResult.Error(errorMsg)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 删除文件（管理员操作）。
     * @param path 文件路径
     * @param sha 文件当前 sha
     * @return true=成功
     */
    fun deleteFile(path: String, sha: String): Boolean {
        val url = contentsUrl(path)
        var conn: HttpURLConnection? = null
        return try {
            val json = JSONObject().apply {
                put("sha", sha)
                put("message", "sync: delete $path")
            }
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setAuthHeader()
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(json.toString()) }
            conn.responseCode in 200..204
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseFileObjectOrNull(body: String): JSONObject? {
        return try {
            val trimmed = body.trim()
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() == 0) null else arr.optJSONObject(0)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun contentsUrl(path: String): String {
        val normalizedPath = path.trimStart('/')
        // 令牌统一走 Authorization: Bearer 请求头，URL 不再拼接明文 access_token。
        return "$BASE_URL/${encodePath(normalizedPath)}"
    }

    private fun rawUrl(path: String): String {
        val normalizedPath = path.trimStart('/')
        return "$RAW_BASE_URL/${encodePath(normalizedPath)}"
    }

    private fun encodePath(path: String): String {
        return path.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
    }

    private fun redactToken(url: String): String {
        return url.replace(Regex("access_token=[^&]+"), "access_token=***")
    }

    private fun HttpURLConnection.setAuthHeader() {
        // 统一通过 Bearer 请求头下发令牌；URL / 请求体不再携带明文 token。
        setRequestProperty("Authorization", "Bearer $accessToken")
    }

    private fun HttpURLConnection.readErrorBody(): String {
        val stream = errorStream ?: return responseMessage.orEmpty()
        return try {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.take(500)
        } catch (_: Exception) {
            responseMessage.orEmpty()
        }
    }

    private fun HttpURLConnection.readHttpErrorSafely(): String? {
        return try {
            val code = responseCode
            "HTTP $code：${readErrorBody()}"
        } catch (_: Exception) {
            null
        }
    }

    // ===================== Releases API =====================

    private const val RELEASES_URL = "https://gitee.com/api/v5/repos/bdCasttv/video-source/releases"

    /**
     * 从 Gitee Releases 下载指定版本的 APK。
     * 流程：列出所有 release → 找到 tag_name == "v{versionName}" 的 release → 下载其 .apk 附件。
     */
    fun downloadReleaseApk(versionName: String): ApiResult<BinaryFileResult> {
        val tag = "v$versionName"
        var conn: HttpURLConnection? = null
        return try {
            // 1. 列出 releases，找到目标 tag
            conn = (URL(RELEASES_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Accept", "application/json")
                setAuthHeader()
            }
            val code = conn.responseCode
            if (code != 200) {
                return ApiResult.Error("列出 releases 失败，HTTP $code")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val releases = JSONArray(body)
            var downloadUrl: String? = null
            for (i in 0 until releases.length()) {
                val release = releases.optJSONObject(i) ?: continue
                if (release.optString("tag_name") == tag) {
                    val assets = release.optJSONArray("assets")
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val asset = assets.optJSONObject(j) ?: continue
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                downloadUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }
                    break
                }
            }

            if (downloadUrl.isNullOrBlank()) {
                return ApiResult.NotFound
            }

            // 2. 下载 APK 附件
            conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = 300_000
                setRequestProperty("Accept", "application/octet-stream")
                setAuthHeader()
            }
            val dlCode = conn.responseCode
            if (dlCode != 200) {
                return ApiResult.Error("下载 APK 失败，HTTP $dlCode")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) {
                return ApiResult.Error("下载 APK 失败：文件内容为空")
            }
            SsdpDiagnostics.logCloudSync("Releases 下载 APK 成功：$tag，bytes=${bytes.size}")
            ApiResult.Success(BinaryFileResult(bytes = bytes, sha = ""))
        } catch (e: Exception) {
            SsdpDiagnostics.logCloudSync("Releases 下载 APK 异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
            ApiResult.Error("下载 APK 异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
        } finally {
            conn?.disconnect()
        }
    }

    data class FileResult(val content: String, val sha: String)
    data class BinaryFileResult(val bytes: ByteArray, val sha: String)

    sealed class ApiResult<out T> {
        data class Success<T>(val value: T) : ApiResult<T>()
        data class Error(val message: String) : ApiResult<Nothing>()
        object NotFound : ApiResult<Nothing>()

        val isSuccess: Boolean get() = this is Success

        fun getOrNull(): T? = when (this) {
            is Success -> value
            else -> null
        }

        fun errorMessage(defaultMessage: String): String = when (this) {
            is Error -> message
            NotFound -> defaultMessage
            is Success -> ""
        }
    }
}
