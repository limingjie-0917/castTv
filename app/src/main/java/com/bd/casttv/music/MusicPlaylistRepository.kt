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

    suspend fun loadLyrics(url: String?): List<MusicLrcLine> = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext emptyList()
        val text = runCatching { downloadText(url) }.getOrNull().orEmpty()
        MusicLrcParser.parse(text)
    }

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

    private fun downloadText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
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
}
