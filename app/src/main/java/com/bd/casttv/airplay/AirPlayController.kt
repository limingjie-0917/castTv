package com.bd.casttv.airplay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.bd.casttv.CastApp
import com.bd.casttv.dlna.SsdpDiagnostics
import com.bd.casttv.util.LocalCrashLog
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central bridge between the AirPlay mirroring server (Netty worker threads,
 * via [CastAirplayConsumer]) and the [com.bd.casttv.player.PlayerActivity]
 * mirror surface / MediaCodec decoders.
 *
 * Mirrors the design of [com.bd.casttv.dlna.PlaybackController] used by DLNA:
 *  - The AirPlay server pushes NAL/PCM data on network threads.
 *  - Data is forwarded to the attached [VideoPlayer]/[AudioPlayer]; anything
 *    arriving before the surface is ready is buffered (bounded).
 *  - Lifecycle callbacks are delivered to listeners on the MAIN thread so the
 *    service can launch the player and the player can finish itself.
 */
object AirPlayController {

    private const val TAG = "AirPlayController"
    private const val MAX_PENDING = 300
    private const val WATCHDOG_MS = 4000L

    private fun logD(message: String) {
        Log.d(TAG, message)
        LocalCrashLog.d(CastApp.appContext, TAG, message)
    }

    private fun logE(message: String, throwable: Throwable) {
        Log.e(TAG, message, throwable)
        LocalCrashLog.e(CastApp.appContext, TAG, message, throwable)
    }

    interface Listener {
        /** Mirroring session started (RTSP SETUP video). Launch the mirror UI. */
        fun onMirrorStart()
        /** Native resolution of the mirrored screen became known. */
        fun onMirrorSize(width: Int, height: Int)
        /** Mirroring session ended (TEARDOWN or stream timeout). Tear down UI. */
        fun onMirrorStop()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var isMirroring: Boolean = false
        private set

    @Volatile
    var videoWidth: Int = 1920
        private set

    @Volatile
    var videoHeight: Int = 1080
        private set

    @Volatile
    private var videoPlayer: VideoPlayer? = null

    @Volatile
    private var audioPlayer: AudioPlayer? = null

    private val lock = Any()
    private val pendingVideo = ArrayDeque<NALPacket>()

    @Volatile
    private var lastFrameAt: Long = 0L

    private val watchdog = Runnable { checkTimeout() }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // ---------------------------------------------------------------------
    // Called by CastAirplayConsumer on Netty worker threads
    // ---------------------------------------------------------------------

    fun onMirroringStarted() {
        logD("mirroring started")
        isMirroring = true
        lastFrameAt = SystemClock.elapsedRealtime()
        SsdpDiagnostics.logCastEvent(
            SsdpDiagnostics.CastEvent.Kind.AIRPLAY_SESSION_START,
            "AirPlay 镜像会话开始（RTSP SETUP video）"
        )
        main.post {
            listeners.forEach {
                try {
                    it.onMirrorStart()
                } catch (t: Throwable) {
                    logE("listener.onMirrorStart failed", t)
                }
            }
        }
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    fun onVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoWidth = width
        videoHeight = height
        main.post {
            listeners.forEach {
                try {
                    it.onMirrorSize(width, height)
                } catch (t: Throwable) {
                    logE("listener.onMirrorSize failed", t)
                }
            }
        }
    }

    fun pushVideo(data: ByteArray) {
        lastFrameAt = SystemClock.elapsedRealtime()
        val packet = NALPacket().apply { nalData = data }
        val vp = videoPlayer
        if (vp != null) {
            vp.addPacker(packet)
        } else {
            synchronized(lock) {
                if (pendingVideo.size >= MAX_PENDING) pendingVideo.pollFirst()
                pendingVideo.addLast(packet)
            }
        }
    }

    fun pushAudio(data: ByteArray) {
        audioPlayer?.addPacker(PCMPacket().apply { this.data = data })
    }

    fun onMirroringStopped() {
        if (!isMirroring) return
        logD("mirroring stopped")
        isMirroring = false
        SsdpDiagnostics.logCastEvent(
            SsdpDiagnostics.CastEvent.Kind.AIRPLAY_SESSION_STOP,
            "AirPlay 镜像会话停止（TEARDOWN 或超时）"
        )
        main.removeCallbacks(watchdog)
        main.post {
            listeners.forEach {
                try {
                    it.onMirrorStop()
                } catch (t: Throwable) {
                    logE("listener.onMirrorStop failed", t)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Called by PlayerActivity on the main thread
    // ---------------------------------------------------------------------

    fun attachVideoPlayer(vp: VideoPlayer) {
        videoPlayer = vp
        synchronized(lock) {
            while (pendingVideo.isNotEmpty()) {
                vp.addPacker(pendingVideo.pollFirst())
            }
        }
    }

    fun attachAudioPlayer(ap: AudioPlayer) {
        audioPlayer = ap
    }

    fun detachPlayers() {
        videoPlayer = null
        audioPlayer = null
        synchronized(lock) { pendingVideo.clear() }
    }

    private fun checkTimeout() {
        if (!isMirroring) return
        if (SystemClock.elapsedRealtime() - lastFrameAt > WATCHDOG_MS) {
            logD("no frames for ${WATCHDOG_MS}ms — treating mirror as stopped")
            onMirroringStopped()
        } else {
            main.postDelayed(watchdog, WATCHDOG_MS)
        }
    }
}
