package com.bd.casttv.music

import com.bd.casttv.R
import com.bd.casttv.sync.GiteeApi
import java.util.Locale

data class MusicTrack(
    val title: String,
    val artist: String,
    val url: String,
    val cover: String? = null,
    val lrc: String? = null,
    val repo: String,
)

data class MusicRepoConfig(
    val id: String,
    val displayName: String,
    val repository: GiteeApi.Repository,
    val playlistPath: String = "playlist.json",
) {
    val playlistRawUrl: String = GiteeApi.publicRawUrl(repository, playlistPath)
}

object MusicRepoCatalog {
    private const val OWNER = "bdCasttv"
    private const val BRANCH = "master"

    /**
     * 当前线上音乐仓配置：
     * 1) https://gitee.com/bdCasttv/music-repo-1/raw/master/playlist.json
     * 2) https://gitee.com/bdCasttv/music-repo-2/raw/master/playlist.json
     * 3) https://gitee.com/bdCasttv/music-repo-3/raw/master/playlist.json
     * 音频文件通过固定 music-library Release 的附件接口上传，playlist.json 仍存放于仓库根目录。
     */
    val repos: List<MusicRepoConfig> = listOf(
        MusicRepoConfig(
            id = "music-repo-1",
            displayName = "music1",
            repository = GiteeApi.Repository(owner = OWNER, repo = "music-repo-1", branch = BRANCH),
        ),
        MusicRepoConfig(
            id = "music-repo-2",
            displayName = "music2",
            repository = GiteeApi.Repository(owner = OWNER, repo = "music-repo-2", branch = BRANCH),
        ),
        MusicRepoConfig(
            id = "music-repo-3",
            displayName = "music3",
            repository = GiteeApi.Repository(owner = OWNER, repo = "music-repo-3", branch = BRANCH),
        ),
    )

    fun byId(id: String): MusicRepoConfig? = repos.firstOrNull { it.id == id }
}

enum class MusicLoopMode(
    val label: String,
    val iconRes: Int,
) {
    SHUFFLE("随机播放", R.drawable.ic_music_shuffle),
    ALL("循环播放", R.drawable.ic_music_repeat_all),
    ;

    fun next(): MusicLoopMode = when (this) {
        SHUFFLE -> ALL
        ALL -> SHUFFLE
    }
}

data class MusicPlaylistLoadResult(
    val tracks: List<MusicTrack>,
    val skippedRepos: List<String>,
    val repoTrackCounts: Map<String, Int>,
)

data class MusicDeleteResult(
    val requestedCount: Int,
    val deletedCount: Int,
    val failedMessages: List<String>,
) {
    val isCompleteSuccess: Boolean get() = deletedCount == requestedCount && failedMessages.isEmpty()
}

sealed class MusicUploadResult {
    data class Success(
        val track: MusicTrack,
        val repo: MusicRepoConfig,
    ) : MusicUploadResult()

    data class PartialSuccess(
        val track: MusicTrack,
        val repo: MusicRepoConfig,
        val message: String,
    ) : MusicUploadResult()

    data class Error(val message: String) : MusicUploadResult()
}

internal val TITLE_CASE_INSENSITIVE_COMPARATOR = Comparator<MusicTrack> { a, b ->
    val titleCompare = a.title.lowercase(Locale.ROOT).compareTo(b.title.lowercase(Locale.ROOT))
    if (titleCompare != 0) return@Comparator titleCompare
    val artistCompare = a.artist.lowercase(Locale.ROOT).compareTo(b.artist.lowercase(Locale.ROOT))
    if (artistCompare != 0) return@Comparator artistCompare
    a.repo.lowercase(Locale.ROOT).compareTo(b.repo.lowercase(Locale.ROOT))
}
