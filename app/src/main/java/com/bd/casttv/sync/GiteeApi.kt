package com.bd.casttv.sync

import android.util.Base64
import android.util.Log
import com.bd.casttv.dlna.SsdpDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
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

    /** 只暴露令牌状态摘要（长度+前缀），用于诊断 UI，绝不输出明文。 */
    fun tokenStateSummary(): String {
        val raw = accessToken
        return when {
            raw.isBlank() -> "NO_TOKEN"
            raw.length < 8 -> "WEAK(len=${raw.length})"
            else -> "OK(${raw.length}B, ${raw.take(3)}***${raw.takeLast(2)})"
        }
    }

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000
    private const val TAG = "GiteeApi"

    data class Repository(
        val owner: String,
        val repo: String,
        val branch: String = "",
    )

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
            val msg = "GET $path 异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}"
            SsdpDiagnostics.logCloudSync(msg)
            // 404 只有响应码 404 才算；异常（网络/解析/鉴权缺失）向上抛出 Error，让上层显示真实原因（不静默变空列表）
            ApiResult.Error(msg)
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

    fun getFileResult(path: String, repository: Repository): ApiResult<FileResult> {
        val url = contentsUrl(path, repository)
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
                SsdpDiagnostics.logCloudSync("请求结果：GET ${repository.repo}/$path 失败，HTTP $code：$errorBody")
                return ApiResult.NotFound
            }
            if (code != 200) {
                val errorBody = conn.readErrorBody().take(200)
                return ApiResult.Error("GET ${repository.repo}/$path 失败，HTTP $code：$errorBody")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = parseFileObjectOrNull(body) ?: return ApiResult.NotFound
            val contentB64 = json.optString("content", "").replace("\n", "")
            val sha = json.optString("sha", "")
            val content = if (contentB64.isBlank()) "" else String(Base64.decode(contentB64, Base64.DEFAULT), Charsets.UTF_8)
            ApiResult.Success(FileResult(content = content, sha = sha))
        } catch (e: Exception) {
            ApiResult.Error("GET ${repository.repo}/$path 异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
        } finally {
            conn?.disconnect()
        }
    }

    fun putFileResult(path: String, content: String, sha: String?, repository: Repository, commitMessage: String = ""): ApiResult<Unit> {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return putEncodedFileResult(
            path = path,
            contentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            sha = sha,
            repository = repository,
            commitMessage = commitMessage,
            readTimeoutMs = READ_TIMEOUT,
        )
    }

    fun putBinaryFileResult(path: String, bytes: ByteArray, sha: String?, repository: Repository, commitMessage: String = ""): ApiResult<Unit> {
        if (bytes.isEmpty()) return ApiResult.Error("PUT ${repository.repo}/$path 失败：文件内容为空")
        return putEncodedFileResult(
            path = path,
            contentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            sha = sha,
            repository = repository,
            commitMessage = commitMessage,
            readTimeoutMs = 120_000,
        )
    }

    fun publicRawUrl(repository: Repository, path: String): String {
        val normalizedPath = path.trimStart('/')
        val encodedBranch = encodeSegment(repository.branch.ifBlank { "master" })
        return "https://gitee.com/${encodeSegment(repository.owner)}/${encodeSegment(repository.repo)}/raw/$encodedBranch/${encodePath(normalizedPath)}"
    }

    private fun putEncodedFileResult(
        path: String,
        contentBase64: String,
        sha: String?,
        repository: Repository,
        commitMessage: String = "",
        readTimeoutMs: Int = READ_TIMEOUT,
    ): ApiResult<Unit> {
        val normalizedSha = sha?.takeIf { it.isNotBlank() }
        // Gitee Contents API 的 ref 查询参数仅用于 GET；创建/更新时分支必须放在 body.branch。
        val url = contentsWriteUrl(path, repository)
        var conn: HttpURLConnection? = null
        return try {
            val commitMsg = commitMessage.ifBlank {
                "sync: ${if (normalizedSha == null) "create" else "update"} $path"
            }
            val json = JSONObject().apply {
                put("message", commitMsg)
                put("content", contentBase64)
                if (normalizedSha != null) put("sha", normalizedSha)
                if (repository.branch.isNotBlank()) put("branch", repository.branch)
            }
            val requestBody = json.toString().toByteArray(Charsets.UTF_8)
            val method = if (normalizedSha == null) "POST" else "PUT"
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = readTimeoutMs
                doInput = true
                doOutput = true
                setFixedLengthStreamingMode(requestBody.size)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setAuthHeader()
            }
            logHttpExchange(
                stage = "写入仓库文件 $path",
                method = method,
                url = url,
                headers = "Accept=application/json; Content-Type=application/json; Authorization=Bearer ***; User-Agent=casttv-receiver-android",
                requestSummary = "body=${requestBody.size}B, branch=${repository.branch.ifBlank { "default" }}, sha=${normalizedSha?.take(8) ?: "none"}",
            )
            conn.outputStream.use { it.write(requestBody) }
            val code = conn.responseCode
            val responseBody = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.readErrorBody()
            }
            logHttpExchange("写入仓库文件 $path", method, url, responseCode = code, responseBody = responseBody)
            if (code in 200..201) {
                ApiResult.Success(Unit)
            } else {
                ApiResult.Error("$method ${repository.repo}/$path 失败，HTTP $code：${sanitizeErrorBody(responseBody)}")
            }
        } catch (e: Exception) {
            val method = conn?.requestMethod ?: if (normalizedSha == null) "POST" else "PUT"
            val httpDetail = conn?.readHttpErrorSafely()
            val exceptionMessage = "${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}"
            val errorMsg = if (httpDetail.isNullOrBlank()) {
                "$method ${repository.repo}/$path 异常：$exceptionMessage"
            } else {
                "$method ${repository.repo}/$path 异常：$exceptionMessage；$httpDetail"
            }
            ApiResult.Error(errorMsg)
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

    private fun contentsUrl(path: String, repository: Repository): String {
        val base = contentsWriteUrl(path, repository)
        return if (repository.branch.isBlank()) base else "$base?ref=${encodeSegment(repository.branch)}"
    }

    private fun contentsWriteUrl(path: String, repository: Repository): String {
        val normalizedPath = path.trimStart('/')
        return "https://gitee.com/api/v5/repos/${encodeSegment(repository.owner)}/${encodeSegment(repository.repo)}/contents/${encodePath(normalizedPath)}"
    }

    private fun rawUrl(path: String): String {
        val normalizedPath = path.trimStart('/')
        return "$RAW_BASE_URL/${encodePath(normalizedPath)}"
    }

    private fun encodePath(path: String): String {
        return path.split("/").joinToString("/") { segment -> encodeSegment(segment) }
    }

    private fun encodeSegment(segment: String): String {
        return URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    private fun redactToken(url: String): String {
        return url.replace(Regex("access_token=[^&]+"), "access_token=***")
    }

    private fun HttpURLConnection.setAuthHeader() {
        // 统一通过 Bearer 请求头下发令牌；URL / 请求体不再携带明文 token。
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("User-Agent", "casttv-receiver-android")
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

    /**
     * 将私有 Gitee Release 的浏览器下载地址转换成可供 Media3 播放的短期签名地址。
     *
     * Release 页面地址对私有仓库会直接返回 403，即使附带 Bearer；必须先走 Open API：
     * release tag -> attach_files -> attachment download，再从 302 Location 取得 foruda 签名 URL。
     */
    fun resolvePlayableAssetUrl(assetUrl: String): ApiResult<String> {
        val parsed = try {
            URL(assetUrl)
        } catch (e: Exception) {
            return ApiResult.Error("音频下载地址无效：${e.javaClass.simpleName}")
        }
        val segments = parsed.path.split('/').filter { it.isNotBlank() }
        if (!parsed.host.equals("gitee.com", ignoreCase = true) ||
            segments.size < 6 || segments[2] != "releases" || segments[3] != "download"
        ) {
            return ApiResult.Success(assetUrl)
        }

        val decode: (String) -> String = { URLDecoder.decode(it, "UTF-8") }
        val repository = Repository(owner = decode(segments[0]), repo = decode(segments[1]))
        val tag = decode(segments[4])
        val fileName = decode(segments.drop(5).joinToString("/"))
        val releaseUrl = "${repositoryApiBase(repository)}/releases/tags/${encodeSegment(tag)}"

        val releaseId = when (val release = getJsonObject(releaseUrl, "查询音乐 Release")) {
            is ApiResult.Success -> release.value.optLong("id").takeIf { it > 0L }
                ?: return ApiResult.Error("音乐 Release 响应缺少 id")
            is ApiResult.Error -> return release
            ApiResult.NotFound -> return ApiResult.Error("音乐 Release 不存在：$tag")
        }

        var matchedId = 0L
        var page = 1
        while (matchedId <= 0L) {
            val listUrl = "${repositoryApiBase(repository)}/releases/$releaseId/attach_files?page=$page&per_page=100"
            val attachments = when (val result = getJsonArray(listUrl, "查询音乐附件")) {
                is ApiResult.Success -> result.value
                is ApiResult.Error -> return result
                ApiResult.NotFound -> return ApiResult.Error("音乐附件列表不存在")
            }
            for (i in 0 until attachments.length()) {
                val attachment = attachments.optJSONObject(i) ?: continue
                if (attachment.optString("name") == fileName) {
                    matchedId = attachment.optLong("id")
                    break
                }
            }
            if (matchedId > 0L) break
            if (attachments.length() < 100) {
                return ApiResult.Error("音乐附件不存在：$fileName")
            }
            page++
            if (page > 100) return ApiResult.Error("音乐附件数量过多，未找到：$fileName")
        }

        val apiDownloadUrl = "${repositoryApiBase(repository)}/releases/$releaseId/attach_files/$matchedId/download"
        return resolveSignedDownloadUrl(apiDownloadUrl)
    }

    /**
     * 批量删除指定 Release 下的附件。文件已不存在（404）同样视为删除成功，
     * 方便上层继续清理 playlist.json 中的失效记录。
     */
    fun deleteReleaseAssetsResult(
        fileNames: Set<String>,
        repository: Repository,
        releaseTag: String,
    ): ApiResult<ReleaseAssetDeleteResult> {
        val targets = fileNames.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (targets.isEmpty()) return ApiResult.Success(ReleaseAssetDeleteResult(emptySet(), emptyMap()))

        val releaseUrl = "${repositoryApiBase(repository)}/releases/tags/${encodeSegment(releaseTag)}"
        val releaseId = when (val release = getJsonObject(releaseUrl, "查询待删除音乐 Release")) {
            is ApiResult.Success -> release.value.optLong("id").takeIf { it > 0L }
                ?: return ApiResult.Error("音乐 Release 响应缺少 id")
            is ApiResult.Error -> return release
            ApiResult.NotFound -> return ApiResult.Success(ReleaseAssetDeleteResult(targets, emptyMap()))
        }

        val attachmentIds = linkedMapOf<String, Long>()
        var page = 1
        while (attachmentIds.keys.containsAll(targets).not()) {
            val listUrl = "${repositoryApiBase(repository)}/releases/$releaseId/attach_files?page=$page&per_page=100"
            val attachments = when (val result = getJsonArray(listUrl, "查询待删除音乐附件")) {
                is ApiResult.Success -> result.value
                is ApiResult.Error -> return result
                ApiResult.NotFound -> break
            }
            for (i in 0 until attachments.length()) {
                val attachment = attachments.optJSONObject(i) ?: continue
                val name = attachment.optString("name")
                val id = attachment.optLong("id")
                if (name in targets && id > 0L) attachmentIds[name] = id
            }
            if (attachments.length() < 100 || page >= 100) break
            page++
        }

        val deletedOrMissing = targets.filterTo(linkedSetOf()) { it !in attachmentIds }
        val failures = linkedMapOf<String, String>()
        attachmentIds.forEach { (fileName, assetId) ->
            var conn: HttpURLConnection? = null
            try {
                val deleteUrl = "${repositoryApiBase(repository)}/releases/$releaseId/attach_files/$assetId"
                conn = (URL(deleteUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    setRequestProperty("Accept", "application/json")
                    setAuthHeader()
                }
                val code = conn.responseCode
                val body = if (code in 200..299) "" else conn.readErrorBody()
                logHttpExchange("删除音乐附件", "DELETE", deleteUrl, responseCode = code, responseBody = body)
                if (code in 200..299 || code == 404) {
                    deletedOrMissing += fileName
                } else {
                    failures[fileName] = "HTTP $code：${sanitizeErrorBody(body)}"
                }
            } catch (e: Exception) {
                failures[fileName] = "${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}"
            } finally {
                conn?.disconnect()
            }
        }
        return ApiResult.Success(ReleaseAssetDeleteResult(deletedOrMissing, failures))
    }

    private fun getJsonObject(url: String, stage: String): ApiResult<JSONObject> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Accept", "application/json")
                setAuthHeader()
            }
            val code = conn.responseCode
            if (code == 404) return ApiResult.NotFound
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.readErrorBody()
            }
            logHttpExchange(stage, "GET", url, responseCode = code, responseBody = body)
            if (code !in 200..299) ApiResult.Error("$stage 失败，HTTP $code：${sanitizeErrorBody(body)}")
            else parseFileObjectOrNull(body)?.let { ApiResult.Success(it) }
                ?: ApiResult.Error("$stage 响应格式异常")
        } catch (e: Exception) {
            ApiResult.Error("$stage 异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun getJsonArray(url: String, stage: String): ApiResult<JSONArray> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Accept", "application/json")
                setAuthHeader()
            }
            val code = conn.responseCode
            if (code == 404) return ApiResult.NotFound
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.readErrorBody()
            }
            logHttpExchange(stage, "GET", url, responseCode = code, responseBody = body)
            if (code !in 200..299) ApiResult.Error("$stage 失败，HTTP $code：${sanitizeErrorBody(body)}")
            else try {
                ApiResult.Success(JSONArray(body))
            } catch (_: Exception) {
                ApiResult.Error("$stage 响应格式异常")
            }
        } catch (e: Exception) {
            ApiResult.Error("$stage 异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun resolveSignedDownloadUrl(apiDownloadUrl: String): ApiResult<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiDownloadUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("Range", "bytes=0-0")
                setAuthHeader()
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")?.trim().orEmpty()
                if (location.isBlank()) ApiResult.Error("音频下载重定向缺少 Location，HTTP $code")
                else ApiResult.Success(URL(URL(apiDownloadUrl), location).toString())
            } else if (code == 200 || code == 206) {
                ApiResult.Success(apiDownloadUrl)
            } else {
                ApiResult.Error("获取音频下载地址失败，HTTP $code：${sanitizeErrorBody(conn.readErrorBody())}")
            }
        } catch (e: Exception) {
            ApiResult.Error("获取音频下载地址异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 上传较大的二进制文件到仓库固定 Release。Contents API 仅适合 playlist.json 等小文本，
     * 音频文件改走 multipart Release 附件上传，避免 Base64 请求体大小限制。
     */
    fun uploadReleaseAssetResult(
        fileName: String,
        fileSize: Long,
        inputStreamProvider: () -> java.io.InputStream?,
        repository: Repository,
        releaseTag: String = "music-library",
    ): ApiResult<ReleaseAssetResult> {
        if (fileSize <= 0L) return ApiResult.Error("上传附件失败：文件内容为空或大小未知")
        val releaseId = when (val release = getOrCreateRelease(repository, releaseTag)) {
            is ApiResult.Success -> release.value
            is ApiResult.Error -> return release
            ApiResult.NotFound -> return ApiResult.Error("创建音乐 Release 失败：仓库或分支不存在")
        }
        val boundary = "----CastTvMusic${System.currentTimeMillis()}"
        val url = "${repositoryApiBase(repository)}/releases/$releaseId/attach_files"
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').replace('"', '_')
        val encodedName = encodeSegment(safeName)
        val mimeType = java.net.URLConnection.guessContentTypeFromName(safeName) ?: "application/octet-stream"
        val preamble = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"; filename*=UTF-8''$encodedName\r\n")
            append("Content-Type: $mimeType\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val trailer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val contentLength = preamble.size.toLong() + fileSize + trailer.size.toLong()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = 300_000
                doInput = true
                doOutput = true
                setFixedLengthStreamingMode(contentLength)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setAuthHeader()
            }
            logHttpExchange(
                stage = "上传 Release 音频附件",
                method = "POST",
                url = url,
                headers = "Accept=application/json; Content-Type=multipart/form-data; Authorization=Bearer ***; User-Agent=casttv-receiver-android",
                requestSummary = "filename=$safeName, mime=$mimeType, fileSize=$fileSize, contentLength=$contentLength",
            )
            conn.outputStream.buffered(64 * 1024).use { output ->
                output.write(preamble)
                val input = inputStreamProvider() ?: throw java.io.IOException("无法打开待上传音频")
                val copied = input.buffered(64 * 1024).use { it.copyTo(output, 64 * 1024) }
                if (copied != fileSize) {
                    throw java.io.IOException("音频读取长度发生变化：预期 $fileSize 字节，实际 $copied 字节")
                }
                output.write(trailer)
            }
            val code = conn.responseCode
            val responseBody = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.readErrorBody()
            }
            logHttpExchange("上传 Release 音频附件", "POST", url, responseCode = code, responseBody = responseBody)
            if (code == 201) {
                val json = JSONObject(responseBody)
                val downloadUrl = json.optString("browser_download_url")
                if (downloadUrl.isBlank()) {
                    ApiResult.Error("附件上传成功但响应缺少下载地址，HTTP 201：${sanitizeErrorBody(responseBody)}")
                } else {
                    ApiResult.Success(ReleaseAssetResult(downloadUrl, json.optLong("id")))
                }
            } else {
                ApiResult.Error("Release 附件上传失败，HTTP $code：${sanitizeErrorBody(responseBody)}")
            }
        } catch (e: Exception) {
            val detail = conn?.readHttpErrorSafely()?.let(::sanitizeErrorBody)
            ApiResult.Error("Release 附件上传异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}${detail?.let { "；$it" }.orEmpty()}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun getOrCreateRelease(repository: Repository, tag: String): ApiResult<Long> {
        val tagUrl = "${repositoryApiBase(repository)}/releases/tags/${encodeSegment(tag)}"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(tagUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("Accept", "application/json")
                setAuthHeader()
            }
            logHttpExchange(
                stage = "查询音乐 Release",
                method = "GET",
                url = tagUrl,
                headers = "Accept=application/json; Authorization=Bearer ***; User-Agent=casttv-receiver-android",
            )
            val code = conn.responseCode
            val responseBody = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.readErrorBody()
            }
            logHttpExchange("查询音乐 Release", "GET", tagUrl, responseCode = code, responseBody = responseBody)
            if (code == 200) {
                val releaseJson = parseFileObjectOrNull(responseBody)
                val id = releaseJson?.optLong("id") ?: 0L
                if (id > 0) return ApiResult.Success(id)
                val normalized = responseBody.trim()
                val releaseMissing = normalized.isBlank() || normalized == "null" || normalized == "[]"
                if (!releaseMissing) {
                    return ApiResult.Error("音乐 Release 响应缺少 id，HTTP 200：${sanitizeErrorBody(responseBody)}")
                }
                // Gitee 对“标签对应的 Release 不存在”返回 HTTP 200 + literal null，
                // 与常见的 404 行为不同；按首次上传处理，继续走创建 Release。
            } else if (code != 404) {
                return ApiResult.Error("查询音乐 Release 失败，HTTP $code：${sanitizeErrorBody(responseBody)}")
            }
        } catch (e: Exception) {
            return ApiResult.Error("查询音乐 Release 异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}")
        } finally {
            conn?.disconnect()
        }

        val createUrl = "${repositoryApiBase(repository)}/releases"
        return try {
            val body = JSONObject().apply {
                put("tag_name", tag)
                put("name", "Music Library")
                put("body", "casttv-receiver 音乐附件")
                put("prerelease", false)
                put("target_commitish", repository.branch.ifBlank { "master" })
            }.toString().toByteArray(Charsets.UTF_8)
            conn = (URL(createUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doInput = true
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setAuthHeader()
            }
            logHttpExchange(
                stage = "创建音乐 Release",
                method = "POST",
                url = createUrl,
                headers = "Accept=application/json; Content-Type=application/json; Authorization=Bearer ***; User-Agent=casttv-receiver-android",
                requestSummary = body.toString(Charsets.UTF_8),
            )
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val responseBody = if (code in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                conn.readErrorBody()
            }
            logHttpExchange("创建音乐 Release", "POST", createUrl, responseCode = code, responseBody = responseBody)
            if (code == 201) {
                val id = JSONObject(responseBody).optLong("id")
                if (id > 0) ApiResult.Success(id) else ApiResult.Error("创建音乐 Release 成功但响应缺少 id，HTTP 201")
            } else {
                ApiResult.Error("创建音乐 Release 失败，HTTP $code：${sanitizeErrorBody(responseBody)}")
            }
        } catch (e: Exception) {
            val detail = conn?.readHttpErrorSafely()?.let(::sanitizeErrorBody)
            ApiResult.Error("创建音乐 Release 异常：${e.javaClass.simpleName}${e.message?.let { ": $it" }.orEmpty()}${detail?.let { "；$it" }.orEmpty()}")
        } finally {
            conn?.disconnect()
        }
    }

    private fun logHttpExchange(
        stage: String,
        method: String,
        url: String,
        headers: String? = null,
        requestSummary: String? = null,
        responseCode: Int? = null,
        responseBody: String? = null,
    ) {
        val message = buildString {
            append("[$stage] method=$method, url=${redactToken(url)}")
            headers?.let { append(", headers=$it") }
            requestSummary?.let { append(", request=$it") }
            responseCode?.let { append(", responseCode=$it") }
            responseBody?.let { append(", responseBody=${sanitizeErrorBody(it)}") }
        }
        Log.i(TAG, message)
        SsdpDiagnostics.logCloudSync(message)
    }

    private fun repositoryApiBase(repository: Repository): String =
        "https://gitee.com/api/v5/repos/${encodeSegment(repository.owner)}/${encodeSegment(repository.repo)}"

    private fun sanitizeErrorBody(body: String): String {
        var safe = body.replace(accessToken, "***")
        safe = safe.replace(Regex("(?i)(access_token[=\\\": ]+)[^&\\\"\\s]+"), "$1***")
        return safe.take(4_000)
    }

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
    data class ReleaseAssetResult(val downloadUrl: String, val assetId: Long)
    data class ReleaseAssetDeleteResult(
        val deletedOrMissingFileNames: Set<String>,
        val failedMessages: Map<String, String>,
    )

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
