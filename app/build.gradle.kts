import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// =====================================================================
//  1. 版本号自动递增（满足 AGENT.md §"每次打包必须递增"的硬约束）
//  在任何 assemble* 任务之前，preBuild → bumpVersion：
//    - BASE_VERSION_CODE += 1
//    - BASE_VERSION_NAME 末位十进制 +1（例 "1.2.164" → "1.2.165"）
//    - 结果立刻写回本文件顶部这两行 BASE_VERSION_* 常量，下次 Gradle 同步即生效
//    - 人工禁止手改 BASE_VERSION_* / defaultConfig.versionCode / defaultConfig.versionName
// =====================================================================
// ⚠ 基线：每次构建 bumpVersion 会递增并写回本处这两行
val BASE_VERSION_CODE: Int = 563
val BASE_VERSION_NAME: String = "1.2.288"

val buildGradleFile = layout.projectDirectory.file("build.gradle.kts").asFile

fun readCurrentVersions(): Pair<Int, String>? {
    val text = buildGradleFile.readText(Charsets.UTF_8)
    // 基线常量：BASE_VERSION_CODE / BASE_VERSION_NAME（独立于 defaultConfig，避免被 dsl 混淆值）
    // 允许可选类型标注：val BASE_VERSION_CODE[: Int] = 445 / val BASE_VERSION_NAME[: String] = "1.2.170"
    val vcRe = """val\s+BASE_VERSION_CODE\s*(?::\s*Int)?\s*=\s*(\d+)""".toRegex()
    val vnRe = """val\s+BASE_VERSION_NAME\s*(?::\s*String)?\s*=\s*"([^"]+)"""".toRegex()
    val vc = vcRe.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val vn = vnRe.find(text)?.groupValues?.get(1) ?: return null
    return vc to vn
}

fun bumpVersionWriteBack(newCode: Int, newName: String) {
    val text = buildGradleFile.readText(Charsets.UTF_8)
    var out = text.replaceFirst(Regex("""val\s+BASE_VERSION_CODE\s*(?::\s*Int)?\s*=\s*\d+"""), "val BASE_VERSION_CODE: Int = $newCode")
    out = out.replaceFirst(Regex("""val\s+BASE_VERSION_NAME\s*(?::\s*String)?\s*=\s*"[^"]+""""), "val BASE_VERSION_NAME: String = \"$newName\"")
    if (out == text) error("未能在 app/build.gradle.kts 找到 BASE_VERSION_CODE/BASE_VERSION_NAME 基线常量，版本号递增失败")
    buildGradleFile.writeText(out, Charsets.UTF_8)
}

fun nextVersionName(cur: String): String {
    val parts = cur.split(".")
    require(parts.size >= 3) { "versionName 必须为三段式 (如 1.2.164)，实际=$cur" }
    val last = parts.last().toIntOrNull() ?: error("versionName 末位不是数字：$cur")
    return (parts.dropLast(1) + (last + 1).toString()).joinToString(".")
}

// 新版本号（配置阶段立即计算并通过 androidComponents.onVariants 写入 manifest；执行阶段 bumpVersion 再落盘写回文件字面量）
data class VersionBump(val fromCode: Int, val toCode: Int, val fromName: String, val toName: String)
val versionBump: VersionBump = run {
    val (vc, vn) = readCurrentVersions()
        ?: throw GradleException("读取 app/build.gradle.kts 中 versionCode/versionName 字面量失败，请检查格式")
    VersionBump(fromCode = vc, toCode = vc + 1, fromName = vn, toName = nextVersionName(vn))
}

val bumpVersion = tasks.register("bumpVersion") {
    group = "build"
    description = "Auto-increment versionCode+versionName before every assemble* build (conventions.md 约束)"
    outputs.upToDateWhen { false }  // 必须每次都跑：不能因为是 UP-TO-DATE 就跳
    doFirst {
        // ponytail: CI 环境（GitHub Actions 等）不写回版本号，避免污染构建工作树；
        //           仍会把 +1 的 versionBump.toCode/toName 注入 APK manifest，不影响版本展示。
        val isCi = providers.environmentVariable("CI").orNull.toBoolean() ||
                providers.environmentVariable("GITHUB_ACTIONS").orNull.toBoolean()
        if (!isCi) {
            // 真正把新版本号写回 build.gradle.kts 文件字面量（下次配置期就直接用新版本作为 old 值）
            bumpVersionWriteBack(versionBump.toCode, versionBump.toName)
            println("bumpVersion: versionCode ${versionBump.fromCode} → ${versionBump.toCode} ; versionName ${versionBump.fromName} → ${versionBump.toName} (已写回 app/build.gradle.kts)")
        } else {
            println("bumpVersion: CI 环境跳过写回 build.gradle.kts (manifest versionCode=${versionBump.toCode} versionName=${versionBump.toName} 仍生效)")
        }
    }
}

// 让任何 assemble* 先过 bumpVersion：preBuild 被所有 assemble* 依赖
tasks.named("preBuild") { dependsOn(bumpVersion) }

android {
    namespace = "com.bd.casttv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bd.casttv"
        minSdk = 21          // Android 5.0 — covers virtually all Android TV boxes
        targetSdk = 34
        // ⚠ 注意：字面量 versionCode/versionName 仅作为「上次已写回」的基线，真正写入 APK manifest / 文件名的是下面这两行：
        //   versionBump.toCode = versionCode（字面量） + 1；versionBump.toName = versionName 末位十进制 +1
        //   bumpVersion 任务在 preBuild 时才把 toCode/toName 写回成新的字面量。人工不得直接手改。
        versionCode = versionBump.toCode
        versionName = versionBump.toName
    }

    signingConfigs {
        // 项目内统一签名：debug + release 共用，确保不同设备构建的 APK 签名一致，可直接覆盖安装。
        // keystore 文件随 git 同步，所有构建环境共享同一签名。
        create("release") {
            storeFile = file("casttv.keystore")
            storePassword = "casttv123"
            keyAlias = "casttv"
            keyPassword = "casttv123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
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

// =====================================================================
//  2. APK 输出命名 + 文件树镜像（conventions.md 约束：文件树形式展示路径）
//  约定：
//    a) app/build/outputs/apk/<buildType>/casttv-receiver-v<ver>-<buildType>.apk
//    b) 根目录 builds/<versionName>/casttv-receiver-v<ver>-<buildType>.apk
//    c) 为兼容历史：debug 仍额外复制一份 casttv-receiver-v<ver>-gitee-sync.apk 到根目录
//    d) release 仍额外复制一份 casttv-receiver-v<ver>.apk 到根目录
// =====================================================================
android.applicationVariants.configureEach {
    val variant = this
    val buildType = variant.buildType.name
    // 注意：版本号已经在 androidComponents.onVariants 里覆盖为 versionBump.toCode/toName
    fun effectiveVersions(): Pair<Int, String> {
        return versionBump.toCode to versionBump.toName
    }

    val apkBaseNameProvider = project.provider {
        val (_, vn) = effectiveVersions()
        "casttv-receiver-v${vn}-${buildType}"
    }

    variant.outputs.configureEach {
        if (this is com.android.build.gradle.internal.api.ApkVariantOutputImpl) {
            // 改写默认 outputs/apk/<buildType>/app-<buildType>.apk → casttv-receiver-v<ver>-<buildType>.apk
            outputFileName = apkBaseNameProvider.get() + ".apk"
        }
    }
    // 变体打包完成后：复制 builds/<ver>/ 镜像 + 兼容根目录快捷文件
    variant.assembleProvider.configure {
        doLast {
            val (vc, vn) = effectiveVersions()
            val baseName = apkBaseNameProvider.get()
            val variantOutputDir = layout.buildDirectory.dir("outputs/apk/${buildType}").get().asFile
            val canonical = variantOutputDir.resolve("${baseName}.apk")
            if (!canonical.exists()) {
                val fallback = variantOutputDir.listFiles()?.firstOrNull { f -> f.extension == "apk" }
                if (fallback != null) {
                    println("assemble${buildType.replaceFirstChar(Char::titlecase)}: canonical 名未命中，使用 fallback APK=${fallback.name}")
                    fallback.renameTo(canonical)
                } else {
                    throw GradleException("assemble${buildType.replaceFirstChar(Char::titlecase)} 完成但找不到预期 APK：${canonical.absolutePath}，目录内容：${variantOutputDir.list()?.joinToString(",")}")
                }
            }
            // (b) builds/<ver>/ 镜像
            val mirrorDir = rootProject.layout.projectDirectory.dir("builds/${vn}").asFile.apply { mkdirs() }
            val mirror = mirrorDir.resolve("${baseName}.apk")
            canonical.copyTo(target = mirror, overwrite = true)

            // (c)/(d) 兼容：debug → root/casttv-receiver-v<ver>-gitee-sync.apk；release → root/casttv-receiver-v<ver>.apk
            when (buildType) {
                "debug" -> {
                    val legacy = rootProject.layout.projectDirectory
                        .file("casttv-receiver-v${vn}-gitee-sync.apk").asFile
                    canonical.copyTo(target = legacy, overwrite = true)
                    println("debugAPK: canonical=${canonical.absolutePath}")
                    println("debugAPK: mirror   =${mirror.absolutePath}")
                    println("debugAPK: legacy   =${legacy.absolutePath}")
                }
                "release" -> {
                    val legacy = releaseExportApkFile(vn)
                    canonical.copyTo(target = legacy, overwrite = true)
                    println("releaseAPK: canonical=${canonical.absolutePath}")
                    println("releaseAPK: mirror   =${mirror.absolutePath}")
                    println("releaseAPK: legacy   =${legacy.absolutePath}")
                }
            }
            println("assemble${variant.name.replaceFirstChar(Char::titlecase)}: versionCode=${vc}, versionName=${vn}, size=${canonical.length()} bytes")
        }
    }
}

// 保留现有 `exportApk`/`exportReleaseApk` 任务：作为 assemble* 的 finalizedBy 二次导出。
// ⚠️ 注意：必须读取 `versionBump`（配置阶段已计算好的「本次构建版本」），
// 而不能在 doLast 里再次读文件字面量 — bumpVersion 在 preBuild.doFirst 里已经把文件写回成 +1，
// 配置期读取的 BASE_VERSION_* 与执行期文件内容会因为配置快照不同步导致
// 「assembleDebug 已按 197 命名 APK，exportApk 却去找 198」的越位错误。
val exportApk = tasks.register("exportApk") {
    group = "build"
    description = "Alias: copies debug APK mirror (no-op unless assembleDebug ran)"
    doLast {
        val vn = versionBump.toName
        val fromApk = layout.buildDirectory
            .file("outputs/apk/debug/casttv-receiver-v${vn}-debug.apk").get().asFile
        if (!fromApk.exists()) {
            throw GradleException("请先运行 assembleDebug，APK not found: ${fromApk.absolutePath}")
        }
        val mirrorDir = rootProject.layout.projectDirectory.dir("builds/${vn}").asFile.apply { mkdirs() }
        val mirror = mirrorDir.resolve("casttv-receiver-v${vn}-debug.apk")
        val legacy = rootProject.layout.projectDirectory
            .file("casttv-receiver-v${vn}-gitee-sync.apk").asFile
        fromApk.copyTo(target = mirror, overwrite = true)
        fromApk.copyTo(target = legacy, overwrite = true)
        println("Exported debug APK => ${mirror.absolutePath} + ${legacy.absolutePath}")
    }
}

val exportReleaseApk = tasks.register("exportReleaseApk") {
    group = "build"
    description = "Alias: copies release APK mirror (no-op unless assembleRelease ran)"
    doLast {
        val vn = versionBump.toName
        val fromApk = layout.buildDirectory
            .file("outputs/apk/release/casttv-receiver-v${vn}-release.apk").get().asFile
        if (!fromApk.exists()) {
            throw GradleException("请先运行 assembleRelease，APK not found: ${fromApk.absolutePath}")
        }
        val mirrorDir = rootProject.layout.projectDirectory.dir("builds/${vn}").asFile.apply { mkdirs() }
        val mirror = mirrorDir.resolve("casttv-receiver-v${vn}-release.apk")
        val legacy = releaseExportApkFile(vn)
        fromApk.copyTo(target = mirror, overwrite = true)
        fromApk.copyTo(target = legacy, overwrite = true)
        println("Exported release APK => ${mirror.absolutePath} + ${legacy.absolutePath}")
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

    // Jsoup — 标准 CSS Selector 引擎，用于 webparse 模块的 HTML 解析与元素选择，
    // 取代自研的轻量选择器 selectSimple()，支持后代/子/伪类/属性前缀等全部标准语法。
    implementation("org.jsoup:jsoup:1.18.1")

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
