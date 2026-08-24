package com.bd.casttv.airplay

import android.util.Log
import com.bd.casttv.CastApp
import com.bd.casttv.util.LocalCrashLog
import com.github.serezhka.jap2lib.rtsp.AudioStreamInfo
import com.github.serezhka.jap2lib.rtsp.VideoStreamInfo
import com.github.serezhka.jap2server.AirplayDataConsumer

/**
 * Receives decrypted media + lifecycle callbacks from the AirPlay server
 * (running on Netty worker threads) and forwards everything to
 * [AirPlayController].
 *
 * The server invokes [onVideoFormat] once at RTSP SETUP for the mirroring
 * stream — we treat that as "mirror started". [onMirroringStopped] is invoked
 * on RTSP TEARDOWN (see the patched RTSPHandler).
 */
class CastAirplayConsumer : AirplayDataConsumer {

    private companion object {
        const val TAG = "CastAirplayConsumer"

        private fun logE(message: String, throwable: Throwable) {
            Log.e(TAG, message, throwable)
            LocalCrashLog.e(CastApp.appContext, TAG, message, throwable)
        }
    }

    override fun onVideo(video: ByteArray) {
        try {
            AirPlayController.pushVideo(video)
        } catch (t: Throwable) {
            logE("pushVideo failed", t)
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        try {
            AirPlayController.onVideoSize(width, height)
        } catch (t: Throwable) {
            logE("onVideoSize failed", t)
        }
    }

    override fun onVideoFormat(videoStreamInfo: VideoStreamInfo?) {
        Log.i(TAG, "onVideoFormat -> mirror session begins")
        try {
            AirPlayController.onMirroringStarted()
        } catch (t: Throwable) {
            logE("onMirroringStarted failed", t)
        }
    }

    override fun onAudio(audio: ByteArray) {
        try {
            AirPlayController.pushAudio(audio)
        } catch (t: Throwable) {
            logE("pushAudio failed", t)
        }
    }

    override fun onAudioFormat(audioInfo: AudioStreamInfo?) {
        // Audio format negotiated; the AudioPlayer uses a fixed AAC-ELD config.
    }

    override fun onMirroringStopped() {
        try {
            AirPlayController.onMirroringStopped()
        } catch (t: Throwable) {
            logE("onMirroringStopped failed", t)
        }
    }
}
