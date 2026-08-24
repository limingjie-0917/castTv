import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bd.casttv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bd.casttv"
        minSdk = 21          // Android 5.0 — covers virtually all Android TV boxes
        targetSdk = 34
        versionCode = 401
        versionName = "1.2.126"
    }

    signingConfigs {
        // 正式包（assembleRelease）使用 Android 调试签名，保证产物可直接安装到电视端。
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

// 生成固定命名的 APK 输出
// 约定：执行 assembleDebug 后自动导出到：casttv-receiver/casttv-receiver-v{versionName}-gitee-sync.apk
val exportApk = tasks.register("exportApk") {
    group = "build"
    description = "Copy debug APK to casttv-receiver-v{versionName}-gitee-sync.apk"

    doLast {
        val versionName = android.defaultConfig.versionName ?: "unknown"
        val fromApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val toApk = rootProject.layout.projectDirectory
            .file("casttv-receiver-v${versionName}-gitee-sync.apk")
            .asFile

        if (!fromApk.exists()) {
            throw GradleException("APK not found: ${fromApk.absolutePath} (please run assembleDebug first)")
        }

        toApk.parentFile?.mkdirs()
        fromApk.copyTo(target = toApk, overwrite = true)
        println("Exported APK => ${toApk.absolutePath}")
    }
}

// 正式包导出：执行 assembleRelease 后自动导出到 casttv-receiver/casttv-receiver-v{versionName}.apk
val exportReleaseApk = tasks.register("exportReleaseApk") {
    group = "build"
    description = "Copy release APK to casttv-receiver-v{versionName}.apk"

    doLast {
        val versionName = android.defaultConfig.versionName ?: "unknown"
        val fromApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val toApk = releaseExportApkFile(versionName)

        if (!fromApk.exists()) {
            throw GradleException("APK not found: ${fromApk.absolutePath} (please run assembleRelease first)")
        }

        toApk.parentFile?.mkdirs()
        fromApk.copyTo(target = toApk, overwrite = true)
        println("Exported APK => ${toApk.absolutePath}")
    }
}

// 说明（v1.1.125）：出包只打包，不自动同步 Gitee。
// 原本这里注册了 `syncReleaseToGitee` 任务并通过 `exportReleaseApk.finalizedBy(syncReleaseToGitee)`
// 让 assembleRelease 后自动上传 APK + version.json 到 Gitee，已按需求移除。
// 保留下方 putGiteeXxx / fetchGiteeFileSha / giteeAccessToken 等工具函数与
// Gitee token 相关代码本身，供 App 内「检查更新」等其它功能继续复用。
val syncReleaseToGitee = tasks.register("syncReleaseToGitee") {
    group = "publishing"
    description = "Manually upload exported release APK and version.json to Gitee"

    doLast {
        val versionName = android.defaultConfig.versionName ?: "unknown"
        val apkFile = releaseExportApkFile(versionName)
        val versionFile = rootProject.layout.projectDirectory.file("version.json").asFile
        if (!apkFile.exists()) {
            throw GradleException("APK not found: ${apkFile.absolutePath} (please run assembleRelease first)")
        }
        if (!versionFile.exists()) {
            throw GradleException("version.json not found: ${versionFile.absolutePath}")
        }
        uploadReleaseApk(versionName, apkFile)
        putGiteeTextFile(
            "version.json",
            versionFile.readText(Charsets.UTF_8),
            "release: update version ${versionName}"
        )
    }
}

afterEvaluate {
    // Android Gradle Plugin 的 assembleDebug 任务在变体创建后才出现，这里用 afterEvaluate 防止找不到任务。
    tasks.findByName("assembleDebug")?.finalizedBy(exportApk)
    tasks.findByName("assembleRelease")?.finalizedBy(exportReleaseApk)
}

fun releaseExportApkFile(versionName: String) = rootProject.layout.projectDirectory
    .file("casttv-receiver-v${versionName}.apk")
    .asFile

fun putGiteeTextFile(path: String, content: String, message: String) {
    putGiteeContentsFile(path, content.toByteArray(Charsets.UTF_8), message)
}

fun putGiteeBinaryFile(path: String, bytes: ByteArray, message: String) {
    putGiteeContentsFile(path, bytes, message)
}

// ===================== Gitee Releases API =====================

/**
 * 上传 APK 到 Gitee Releases（contents API 限制 1MB，APK 通常 20MB+，必须走 Releases 附件接口）。
 * 流程：查找或创建 tag=v{version} 的 release → 上传 APK 附件。
 */
fun uploadReleaseApk(versionName: String, apkFile: java.io.File) {
    val tag = "v$versionName"
    val token = giteeAccessToken()
    val releaseId = findOrCreateRelease(token, tag, versionName)
    uploadReleaseAttachment(token, releaseId, apkFile, "casttv-v${versionName}.apk")
    println("Releases: APK uploaded to release $tag (id=$releaseId)")
}

fun findOrCreateRelease(token: String, tag: String, versionName: String): Int {
    val listUrl = URL("https://gitee.com/api/v5/repos/bdCasttv/video-source/releases?tag=$tag")
    val listConn = (listUrl.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Authorization", "Bearer $token")
    }
    try {
        val code = listConn.responseCode
        if (code == 200) {
            val body = listConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            // 用 Regex 从 JSON 中提取 id（与 fetchGiteeFileSha 风格一致，避免引入 org.json）
            val idMatch = Regex("\"id\"\\s*:\\s*(\\d+)").find(body)
            if (idMatch != null) {
                val id = idMatch.groupValues[1].toInt()
                println("Releases: found existing release $tag (id=$id)")
                return id
            }
        }
    } finally {
        listConn.disconnect()
    }
    // Create new release
    val createJson = buildString {
        append('{')
        append("\"tag_name\":\"").append(jsonEscape(tag)).append("\",")
        append("\"name\":\"").append(jsonEscape("v$versionName")).append("\",")
        append("\"body\":\"").append(jsonEscape("casttv-receiver v$versionName")).append("\",")
        append("\"target_commitish\":\"master\"")
        append('}')
    }
    val createConn = (URL("https://gitee.com/api/v5/repos/bdCasttv/video-source/releases").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 30_000
        doInput = true
        doOutput = true
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer $token")
    }
    try {
        val bodyBytes = createJson.toByteArray(Charsets.UTF_8)
        createConn.setFixedLengthStreamingMode(bodyBytes.size)
        createConn.outputStream.use { it.write(bodyBytes) }
        val code = createConn.responseCode
        if (code !in 200..201) {
            throw GradleException("Create release failed: HTTP $code ${createConn.readErrorBodyForGradle()}")
        }
        val resp = createConn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val idMatch = Regex("\"id\"\\s*:\\s*(\\d+)").find(resp)
        val id = idMatch?.groupValues?.getOrNull(1)?.toInt() ?: -1
        if (id <= 0) throw GradleException("Create release failed: no id in response")
        println("Releases: created release $tag (id=$id)")
        return id
    } finally {
        createConn.disconnect()
    }
}

fun uploadReleaseAttachment(token: String, releaseId: Int, file: java.io.File, fileName: String) {
    val boundary = "----CasttvBoundary${System.currentTimeMillis()}"
    val url = URL("https://gitee.com/api/v5/repos/bdCasttv/video-source/releases/$releaseId/attach_files")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 30_000
        readTimeout = 300_000
        doInput = true
        doOutput = true
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        setRequestProperty("Authorization", "Bearer $token")
    }
    try {
        val out = conn.outputStream
        val crlf = "\r\n".toByteArray(Charsets.UTF_8)
        // name field
        out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
        out.write("Content-Disposition: form-data; name=\"name\"$crlf$crlf".toByteArray(Charsets.UTF_8))
        out.write(fileName.toByteArray(Charsets.UTF_8))
        out.write(crlf)
        // file field
        out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"$crlf".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: application/vnd.android.package-archive$crlf$crlf".toByteArray(Charsets.UTF_8))
        file.inputStream().use { it.copyTo(out) }
        out.write(crlf)
        out.write("--$boundary--$crlf".toByteArray(Charsets.UTF_8))
        out.flush()
        val code = conn.responseCode
        if (code !in 200..201) {
            throw GradleException("Upload attachment failed: HTTP $code ${conn.readErrorBodyForGradle()}")
        }
        println("Releases: attachment uploaded ($fileName, ${file.length()} bytes)")
    } finally {
        conn.disconnect()
    }
}

fun putGiteeContentsFile(path: String, bytes: ByteArray, message: String) {
    val sha = fetchGiteeFileSha(path)
    val contentB64 = Base64.getEncoder().encodeToString(bytes)
    val requestJson = buildString {
        append('{')
        append("\"message\":\"").append(jsonEscape(message)).append("\",")
        append("\"content\":\"").append(contentB64).append('"')
        if (!sha.isNullOrBlank()) {
            append(",\"sha\":\"").append(jsonEscape(sha)).append('"')
        }
        append('}')
    }
    val method = if (sha.isNullOrBlank()) "POST" else "PUT"
    val url = URL(giteeContentsUrl(path))
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 60_000
        doInput = true
        doOutput = true
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer ${giteeAccessToken()}")
    }
    try {
        val bodyBytes = requestJson.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bodyBytes.size)
        conn.outputStream.use { it.write(bodyBytes) }
        val code = conn.responseCode
        if (code !in 200..201) {
            throw GradleException("Gitee $method $path failed: HTTP $code ${conn.readErrorBodyForGradle()}")
        }
        println("Gitee $method $path success")
    } finally {
        conn.disconnect()
    }
}

fun fetchGiteeFileSha(path: String): String? {
    val conn = (URL(giteeContentsUrl(path)).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Authorization", "Bearer ${giteeAccessToken()}")
    }
    return try {
        val code = conn.responseCode
        when (code) {
            200 -> {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                Regex("\"sha\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1)
            }
            404 -> null
            else -> throw GradleException("Gitee GET $path failed: HTTP $code ${conn.readErrorBodyForGradle()}")
        }
    } finally {
        conn.disconnect()
    }
}

fun giteeContentsUrl(path: String): String {
    val normalizedPath = path.trimStart('/')
    val encodedPath = normalizedPath.split("/").joinToString("/") { segment ->
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
    return "https://gitee.com/api/v5/repos/bdCasttv/video-source/contents/$encodedPath"
}

fun giteeAccessToken(): String {
    val key = intArrayOf(0x5A, 0x3C, 0x71, 0xE9, 0x2D, 0x84, 0xB6, 0x1F, 0xC3, 0x47)
    val parts = arrayOf(
        intArrayOf(0x69, 0x0F, 0x12, 0x8F, 0x1E, 0xE1, 0xD3, 0x7E, 0xA1, 0x22, 0x6C),
        intArrayOf(0x0D, 0x10, 0x88, 0x4B, 0xB5, 0x83, 0x7C, 0xA1, 0x26, 0x3E, 0x0C),
        intArrayOf(0x14, 0xDE, 0x15, 0xB3, 0xD7, 0x2A, 0xA6, 0x25, 0x3C, 0x58)
    )
    val sb = StringBuilder()
    var k = 0
    parts.forEach { part ->
        part.forEach { enc ->
            sb.append(((enc xor key[k % key.size]) and 0xFF).toChar())
            k++
        }
    }
    return sb.toString()
}

fun jsonEscape(value: String): String = buildString {
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
}

fun HttpURLConnection.readErrorBodyForGradle(): String {
    val stream = errorStream ?: return responseMessage.orEmpty()
    return try {
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.take(500)
    } catch (_: Exception) {
        responseMessage.orEmpty()
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Android TV / Leanback (launcher entry + focus handling)
    implementation("androidx.leanback:leanback:1.0.0")

    // Media3 (ExoPlayer) — playback engine for MP4 / HLS / DASH streams
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")

    // Lightweight embedded HTTP server used to host the UPnP/DLNA device &
    // service descriptions plus the SOAP control endpoints.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // ZXing core — 生成收藏「导入/导出」二维码（局域网 HTTP 传输入口）
    implementation("com.google.zxing:core:3.5.2")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // AirPlay 1 mirroring receiver stack (serezhka java-airplay-lib/server, vendored
    // under com.github.serezhka.*). These are the runtime deps it needs.
    implementation("io.netty:netty-all:4.1.77.Final") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("org.jmdns:jmdns:3.5.7")
    implementation("com.googlecode.plist:dd-plist:1.23")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.whispersystems:curve25519-java:0.5.0")
    implementation("org.slf4j:slf4j-simple:1.7.36")
}
