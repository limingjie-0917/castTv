package com.bd.casttv.ui.preview

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * 自定义 Tab 预览播放器全局持有者。
 *
 * 预览页之间复用唯一 ExoPlayer，频道切换只替换 MediaItem，不再频繁 new/release。
 */
object PreviewPlayerHolder {
    @Volatile
    private var player: ExoPlayer? = null
    private var refCount: Int = 0

    fun acquire(context: Context): ExoPlayer = synchronized(this) {
        refCount++
        player ?: ExoPlayer.Builder(context.applicationContext).build().also { created ->
            created.repeatMode = Player.REPEAT_MODE_ONE
            created.volume = 1f
            player = created
        }
    }

    fun current(): ExoPlayer? = player

    fun release() = synchronized(this) {
        if (refCount > 0) refCount--
    }

    fun switchTo(mediaUrl: String, position: Long = 0L) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(mediaUrl))
        p.seekTo(position.coerceAtLeast(0L))
        p.prepare()
        p.playWhenReady = true
    }

    fun pauseAndClear() {
        val p = player ?: return
        try {
            p.playWhenReady = false
            p.pause()
            p.clearMediaItems()
        } catch (_: Throwable) {
        }
    }

    fun pauseForFullscreen(releaseSurface: Boolean = false) {
        val p = player ?: return
        try {
            p.playWhenReady = false
            p.pause()
            p.volume = 0f
            if (releaseSurface) p.clearVideoSurface()
        } catch (_: Throwable) {
        }
    }

    fun releaseIfIdle() {
        val idlePlayer = synchronized(this) {
            if (refCount == 0) player.also { player = null } else null
        }
        idlePlayer?.releaseSafely()
    }

    fun forceRelease() {
        val oldPlayer = synchronized(this) {
            refCount = 0
            player.also { player = null }
        }
        oldPlayer?.releaseSafely()
    }

    private fun ExoPlayer.releaseSafely() {
        try {
            playWhenReady = false
            pause()
            clearMediaItems()
            release()
        } catch (_: Throwable) {
        }
    }
}
