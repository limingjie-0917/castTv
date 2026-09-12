package com.bd.casttv.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.bd.casttv.sync.GiteeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class MusicPlaylistRepository(context: Context) {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    suspend fun loadMergedPlaylist(): MusicPlaylistLoadResult = coroutineScope {
        val repoResults = MusicRepoCatalog.repos.map { repo ->
            async(Dispatchers.IO) { repo to loadRepoPlaylist(repo) }
        }.awaitAll()

        val merged = mutableListOf<MusicTrack>()
        val skipped = mutableListOf<String>()
        val repoCounts = linkedMapOf<String, Int>()
        repoResults.forEach { (repo, result) ->
            when (result) {
                is RepoPlaylistResult.Success -> {
                    merged += result.tracks
                    repoCounts[repo.id] = result.tracks.size
                }
                is RepoPlaylistResult.Error -> {
                    skipped += "${repo.displayName}：${result.message}"
                    repoCounts[repo.id] = 0
                }
            }
        }
        MusicPlaylistLoadResult(
            tracks = merged.sortedWith(TITLE_CASE_INSENSITIVE_COMPARATOR),
            skippedRepos = skipped,
            repoTrackCounts = repoCounts,
        )
    }

    /**
     * 歌词优先级：playlist.json 指定地址 > LRCLIB 自动匹配 > 空歌词。
     * 指定地址存在时严格使用该地址；仅当 lrc 字段为空时才访问 LRCLIB。
     */
    suspend fun loadLyrics(track: MusicTrack): List<MusicLrcLine> = withContext(Dispatchers.IO) {
        if (!track.lrc.isNullOrBlank()) {
            val text = runCatching { downloadText(track.lrc) }.getOrNull().orEmpty()
            return@withContext MusicLrcParser.parse(text)
        }
        val syncedLyrics = runCatching { fetchLrclibSyncedLyrics(track.title, track.artist) }
            .getOrNull()
            .orEmpty()
        MusicLrcParser.parse(syncedLyrics)
    }

    suspend fun deleteTracks(tracksToDelete: List<MusicTrack>): MusicDeleteResult = withContext(Dispatchers.IO) {
        val requested = tracksToDelete.distinctBy { "${it.repo}|${it.url}" }
        if (requested.isEmpty()) return@withContext MusicDeleteResult(0, 0, emptyList())

        var deletedCount = 0
        val failures = mutableListOf<String>()
        requested.groupBy { it.repo }.forEach { (repoId, tracks) ->
            val repoConfig = MusicRepoCatalog.byId(repoId)
            if (repoConfig == null) {
                failures += "$repoId：未找到仓库配置"
                return@forEach
            }

            val trackFiles = tracks.mapNotNull { track ->
                releaseFileName(track.url)?.let { fileName -> track to fileName }
                    ?: run {
                        failures += "${track.title}：无法识别云端附件地址"
                        null
                    }
            }
            if (trackFiles.isEmpty()) return@forEach

            val deleteResult = when (
                val result = GiteeApi.deleteReleaseAssetsResult(
                    fileNames = trackFiles.map { it.second }.toSet(),
                    repository = repoConfig.repository,
                    releaseTag = "music-library",
                )
            ) {
                is GiteeApi.ApiResult.Success -> result.value
                is GiteeApi.ApiResult.Error -> {
                    failures += "${repoConfig.displayName}：${result.message}"
                    return@forEach
                }
                GiteeApi.ApiResult.NotFound -> {
                    failures += "${repoConfig.displayName}：音乐 Release 不存在"
                    return@forEach
                }
            }

            deleteResult.failedMessages.forEach { (fileName, reason) ->
                val title = trackFiles.firstOrNull { it.second == fileName }?.first?.title ?: fileName
                failures += "$title：附件删除失败（$reason）"
            }
            val removableTracks = trackFiles
                .filter { it.second in deleteResult.deletedOrMissingFileNames }
                .map { it.first }
            if (removableTracks.isEmpty()) return@forEach

            val playlistUpdateError = removeTracksFromPlaylistWithRetry(repoConfig, removableTracks.map { it.url }.toSet())
            if (playlistUpdateError == null) {
                deletedCount += removableTracks.size
            } else {
                failures += "${repoConfig.displayName}：附件已删除，但 playlist.json 更新失败（$playlistUpdateError）"
            }
        }
        MusicDeleteResult(
            requestedCount = requested.size,
            deletedCount = deletedCount,
            failedMessages = failures,
        )
    }

    private fun removeTracksFromPlaylistWithRetry(repoConfig: MusicRepoConfig, urls: Set<String>): String? {
        var lastError = "未知错误"
        repeat(3) {
            val current = when (val result = GiteeApi.getFileResult(repoConfig.playlistPath, repoConfig.repository)) {
                is GiteeApi.ApiResult.Success -> result.value
                is GiteeApi.ApiResult.NotFound -> return "playlist.json 不存在"
                is GiteeApi.ApiResult.Error -> {
                    lastError = result.message
                    return@repeat
                }
            }
            val parsed = when (val result = parsePlaylist(current.content, repoConfig)) {
                is PlaylistParseResult.Success -> result.tracks
                is PlaylistParseResult.Error -> return result.message
            }
            val retained = parsed.filterNot { it.url in urls }
            if (retained.size == parsed.size) return null
            val playlistJson = JSONArray().apply {
                retained.forEach { track ->
                    put(JSONObject().apply {
                        put("title", track.title)
                        put("artist", track.artist)
                        put("url", track.url)
                        track.cover?.takeIf { it.isNotBlank() }?.let { put("cover", it) }
                        track.lrc?.takeIf { it.isNotBlank() }?.let { put("lrc", it) }
                    })
                }
            }.toString(2)
            when (
                val update = GiteeApi.putFileResult(
                    path = repoConfig.playlistPath,
                    content = playlistJson,
                    sha = current.sha,
                    repository = repoConfig.repository,
                    commitMessage = "chore: remove deleted music tracks",
                )
            ) {
                is GiteeApi.ApiResult.Success -> return null
                is GiteeApi.ApiResult.Error -> lastError = update.message
                GiteeApi.ApiResult.NotFound -> lastError = "playlist.json 更新目标不存在"
            }
        }
        return lastError
    }

    private fun releaseFileName(assetUrl: String): String? = runCatching {
        URLDecoder.decode(URL(assetUrl).path.substringAfterLast('/'), "UTF-8").takeIf { it.isNotBlank() }
    }.getOrNull()

    suspend fun uploadAudio(uri: Uri, repoConfig: MusicRepoConfig): MusicUploadResult = withContext(Dispatchers.IO) {
        try {
            val fileName = resolveDisplayName(uri)
            val metadata = resolveLocalAudioMetadata(uri, fileName)
            val extension = fileName.substringAfterLast('.', "").ifBlank { guessExtension(uri) }
            val remoteFileName = buildRemoteFileName(metadata.title, extension)

            // 先读取并严格解析歌单，格式异常时立即中止，绝不把损坏内容当作空歌单覆盖。
            val playlistResult = GiteeApi.getFileResult(repoConfig.playlistPath, repoConfig.repository)
            val existingTracks = when (playlistResult) {
                is GiteeApi.ApiResult.Success -> when (val parsed = parsePlaylist(playlistResult.value.content, repoConfig)) {
                    is PlaylistParseResult.Success -> parsed.tracks
                    is PlaylistParseResult.Error -> return@withContext MusicUploadResult.Error("playlist.json 解析失败，已保留原有数据：${parsed.message}")
                }
                GiteeApi.ApiResult.NotFound -> emptyList()
                is GiteeApi.ApiResult.Error -> return@withContext MusicUploadResult.Error("读取 playlist.json 失败：${playlistResult.message}")
            }
            val playlistSha = (playlistResult as? GiteeApi.ApiResult.Success)?.value?.sha

            // SAF Uri 可能拿不到稳定长度。先用小缓冲区流式落到缓存文件，再以 FileInputStream
            // 直接写入 multipart；内存始终只保留固定大小缓冲区，不随音频体积增长。
            val stagedFile = stageAudioToCache(uri, remoteFileName)
                ?: return@withContext MusicUploadResult.Error("无法读取所选音频")
            try {
                if (stagedFile.length() <= 0L) return@withContext MusicUploadResult.Error("所选音频为空，无法上传")
                val uploadedAsset = when (
                    val upload = GiteeApi.uploadReleaseAssetResult(
                        fileName = remoteFileName,
                        fileSize = stagedFile.length(),
                        inputStreamProvider = { FileInputStream(stagedFile) },
                        repository = repoConfig.repository,
                    )
                ) {
                    is GiteeApi.ApiResult.Error -> return@withContext MusicUploadResult.Error(upload.message)
                    GiteeApi.ApiResult.NotFound -> return@withContext MusicUploadResult.Error("上传音频失败：目标仓库不可用")
                    is GiteeApi.ApiResult.Success -> upload.value
                }

                val uploadedTrack = MusicTrack(
                    title = metadata.title,
                    artist = metadata.artist,
                    url = uploadedAsset.downloadUrl,
                    cover = null,
                    lrc = null,
                    repo = repoConfig.id,
                )
                val nextTracks = (existingTracks + uploadedTrack)
                    .distinctBy { it.url }
                    .sortedWith(TITLE_CASE_INSENSITIVE_COMPARATOR)
                val playlistJson = JSONArray().apply {
                    nextTracks.forEach { track ->
                        put(JSONObject().apply {
                            put("title", track.title)
                            put("artist", track.artist)
                            put("url", track.url)
                            if (!track.cover.isNullOrBlank()) put("cover", track.cover)
                            if (!track.lrc.isNullOrBlank()) put("lrc", track.lrc)
                            put("repo", track.repo)
                        })
                    }
                }
                return@withContext when (
                    val put = GiteeApi.putFileResult(
                        path = repoConfig.playlistPath,
                        content = playlistJson.toString(2),
                        sha = playlistSha,
                        repository = repoConfig.repository,
                        commitMessage = "music: update playlist ${repoConfig.id}",
                    )
                ) {
                    is GiteeApi.ApiResult.Success -> MusicUploadResult.Success(uploadedTrack, repoConfig)
                    is GiteeApi.ApiResult.Error -> MusicUploadResult.PartialSuccess(
                        uploadedTrack,
                        repoConfig,
                        "音频附件已上传，但 playlist.json 更新失败：${put.message}",
                    )
                    GiteeApi.ApiResult.NotFound -> MusicUploadResult.PartialSuccess(
                        uploadedTrack,
                        repoConfig,
                        "音频附件已上传，但 playlist.json 更新失败：仓库不可用",
                    )
                }
            } finally {
                runCatching { stagedFile.delete() }
            }
        } catch (t: Throwable) {
            MusicUploadResult.Error(t.message ?: "上传失败")
        }
    }

    private fun loadRepoPlaylist(repoConfig: MusicRepoConfig): RepoPlaylistResult {
        return when (val result = GiteeApi.getFileResult(repoConfig.playlistPath, repoConfig.repository)) {
            is GiteeApi.ApiResult.Success -> when (val parsed = parsePlaylist(result.value.content, repoConfig)) {
                is PlaylistParseResult.Success -> RepoPlaylistResult.Success(parsed.tracks)
                is PlaylistParseResult.Error -> RepoPlaylistResult.Error("playlist.json 解析失败：${parsed.message}")
            }
            GiteeApi.ApiResult.NotFound -> RepoPlaylistResult.Error("playlist.json 不存在或仓库不可访问")
            is GiteeApi.ApiResult.Error -> RepoPlaylistResult.Error(result.message)
        }
    }

    private fun parsePlaylist(raw: String, repoConfig: MusicRepoConfig): PlaylistParseResult {
        if (raw.isBlank()) return PlaylistParseResult.Success(emptyList())
        val array = try {
            val trimmed = raw.trim()
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed).optJSONArray("items")
                    ?: return PlaylistParseResult.Error("根对象缺少 items 数组")
                else -> return PlaylistParseResult.Error("根节点必须是数组或包含 items 的对象")
            }
        } catch (error: Exception) {
            return PlaylistParseResult.Error(error.message ?: "JSON 格式错误")
        }
        val tracks = ArrayList<MusicTrack>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index)
                ?: return PlaylistParseResult.Error("第 ${index + 1} 项不是对象")
            val url = obj.optString("url").trim()
            if (url.isBlank()) return PlaylistParseResult.Error("第 ${index + 1} 项缺少 url")
            val title = obj.optString("title").trim().ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "未知歌曲" } }
            tracks += MusicTrack(
                title = title,
                artist = obj.optString("artist").trim().ifBlank { "未知歌手" },
                url = url,
                cover = obj.optString("cover").trim().ifBlank { null },
                lrc = obj.optString("lrc").trim().ifBlank { null },
                repo = obj.optString("repo").trim().ifBlank { repoConfig.id },
            )
        }
        return PlaylistParseResult.Success(tracks)
    }

    /** 供外部 UI 显示的本地文件名（优先 DISPLAY_NAME，回退到 URI path）。 */
    fun displayNameOf(uri: Uri): String = resolveDisplayName(uri)

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    return cursor.getString(index).orEmpty().ifBlank { fallbackFileName(uri) }
                }
            }
        }
        return fallbackFileName(uri)
    }

    private fun fallbackFileName(uri: Uri): String {
        val fromPath = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
        return fromPath.ifBlank { "music_${System.currentTimeMillis()}.mp3" }
    }

    private fun guessExtension(uri: Uri): String {
        val mime = contentResolver.getType(uri).orEmpty()
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty().ifBlank { "mp3" }
    }

    private fun stageAudioToCache(uri: Uri, remoteFileName: String): File? {
        val uploadDir = File(appContext.cacheDir, "music-upload").apply { mkdirs() }
        val target = File(uploadDir, remoteFileName)
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered(64 * 1024).use { output ->
                    input.buffered(64 * 1024).copyTo(output, 64 * 1024)
                }
            } ?: return null
            target
        } catch (_: Exception) {
            runCatching { target.delete() }
            null
        }
    }

    private fun resolveLocalAudioMetadata(uri: Uri, fileName: String): LocalAudioMetadata {
        val fallbackTitle = fileName.substringBeforeLast('.').trim().ifBlank { "未知歌曲" }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                .orEmpty().trim().ifBlank { fallbackTitle }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                .orEmpty().trim().ifBlank { "未知歌手" }
            LocalAudioMetadata(title = title, artist = artist)
        } catch (_: Throwable) {
            LocalAudioMetadata(title = fallbackTitle, artist = "未知歌手")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun buildRemoteFileName(title: String, extension: String): String {
        val normalizedTitle = title.trim().ifBlank { "未知歌曲" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "未知歌曲" }
        val safeExt = extension.trim().lowercase(Locale.ROOT).takeIf { it.isNotBlank() } ?: "mp3"
        return "${System.currentTimeMillis()}_${normalizedTitle}.$safeExt"
    }

    /** 参考 musicVM-android：先精确 get，未命中时用 search?q 回退。 */
    private fun fetchLrclibSyncedLyrics(title: String, artist: String): String? {
        val cleanTitle = cleanLyricsQuery(title)
        val cleanArtist = artist.trim().takeUnless { it.equals("未知歌手", ignoreCase = true) }.orEmpty()
        if (cleanTitle.isBlank()) return null

        val exactUrl = buildString {
            append("$LRCLIB_API/get?track_name=${encodeQuery(cleanTitle)}")
            if (cleanArtist.isNotBlank()) append("&artist_name=${encodeQuery(cleanArtist)}")
        }
        runCatching {
            val record = JSONObject(downloadText(exactUrl, LRCLIB_USER_AGENT))
            record.optString("syncedLyrics").trim().takeIf { it.isNotBlank() }
        }.getOrNull()?.let { return it }

        val query = listOf(cleanTitle, cleanArtist).filter { it.isNotBlank() }.joinToString(" ")
        val searchUrl = "$LRCLIB_API/search?q=${encodeQuery(query)}"
        val results = runCatching {
            JSONArray(downloadText(searchUrl, LRCLIB_USER_AGENT))
        }.getOrNull() ?: return null

        val targetTitle = normalizeLyricsMatch(cleanTitle)
        val targetArtist = normalizeLyricsMatch(cleanArtist)
        return (0 until results.length())
            .mapNotNull { index -> results.optJSONObject(index) }
            .filter { it.optString("syncedLyrics").isNotBlank() }
            .maxByOrNull { record ->
                val candidateTitle = normalizeLyricsMatch(record.optString("trackName"))
                val candidateArtist = normalizeLyricsMatch(record.optString("artistName"))
                var score = 0
                if (candidateTitle == targetTitle) score += 8
                else if (candidateTitle.contains(targetTitle) || targetTitle.contains(candidateTitle)) score += 4
                if (targetArtist.isNotBlank()) {
                    if (candidateArtist == targetArtist) score += 6
                    else if (candidateArtist.contains(targetArtist) || targetArtist.contains(candidateArtist)) score += 3
                }
                score
            }
            ?.optString("syncedLyrics")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun cleanLyricsQuery(value: String): String = value
        .replace(Regex("(?i)\\s*[（(\\[].*?(official|lyrics?|audio|video|mv|伴奏|歌词).*?[）)\\]]"), " ")
        .replace(Regex("(?i)\\s*[-–—]\\s*(topic|official)$"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeLyricsMatch(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun downloadText(url: String, userAgent: String? = null): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (!userAgent.isNullOrBlank()) setRequestProperty("User-Agent", userAgent)
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty().take(120)
                throw IllegalStateException("HTTP $code ${error.ifBlank { urlMask(url) }}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun urlMask(url: String): String = runCatching {
        val u = URL(url)
        val path = u.path.split('/').takeLast(2).joinToString("/")
        "${u.host}/$path"
    }.getOrDefault(url)

    private sealed class PlaylistParseResult {
        data class Success(val tracks: List<MusicTrack>) : PlaylistParseResult()
        data class Error(val message: String) : PlaylistParseResult()
    }

    private sealed class RepoPlaylistResult {
        data class Success(val tracks: List<MusicTrack>) : RepoPlaylistResult()
        data class Error(val message: String) : RepoPlaylistResult()
    }

    private data class LocalAudioMetadata(
        val title: String,
        val artist: String,
    )

    private companion object {
        const val LRCLIB_API = "https://lrclib.net/api"
        const val LRCLIB_USER_AGENT = "casttv-receiver/1.2 (Android TV; https://gitee.com/bdCasttv/casttv-receiver)"
    }
}
