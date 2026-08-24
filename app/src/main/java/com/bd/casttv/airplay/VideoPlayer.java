package com.bd.casttv.airplay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Hardware H.264 decoder for the AirPlay mirroring video stream. Feeds NAL
 * units (delivered by the AirPlay server on Netty worker threads) into a
 * {@link MediaCodec} instance rendering directly onto the given {@link Surface}.
 *
 * Adapted from caijianxiong/AirplayAndroidReceiver (MIT).
 */
public class VideoPlayer {
    private static final String TAG = "AirPlayVideoPlayer";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    private int mVideoWidth;
    private int mVideoHeight;

    private MediaCodec mDecoder = null;
    private final Surface mSurface;
    private final BlockingQueue<NALPacket> packets = new LinkedBlockingQueue<>(500);
    private HandlerThread mDecodeThread;

    public VideoPlayer(Surface surface, int width, int height) {
        this.mSurface = surface;
        this.mVideoWidth = width > 0 ? width : 1920;
        this.mVideoHeight = height > 0 ? height : 1080;
    }

    private final MediaCodec.Callback mDecoderCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            try {
                // Never block inside the callback — use poll(), not take().
                NALPacket packet = packets.poll();
                if (packet != null && packet.nalData != null) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(index);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        inputBuffer.put(packet.nalData);
                        codec.queueInputBuffer(index, 0, packet.nalData.length, packet.pts, 0);
                    } else {
                        codec.queueInputBuffer(index, 0, 0, 0, 0);
                    }
                } else {
                    // Nothing to feed right now; submit an empty buffer so the
                    // codec keeps requesting input.
                    codec.queueInputBuffer(index, 0, 0, 0, 0);
                }
            } catch (Exception e) {
                Log.e(TAG, "onInputBufferAvailable error", e);
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            try {
                codec.releaseOutputBuffer(index, true);
            } catch (Exception e) {
                Log.e(TAG, "onOutputBufferAvailable error", e);
            }
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.e(TAG, "MediaCodec onError", e);
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            Log.i(TAG, "onOutputFormatChanged: " + format);
        }
    };

    public void start() {
        mDecodeThread = new HandlerThread("AirPlayVideoDecoder");
        mDecodeThread.start();
        try {
            mDecoder = MediaCodec.createDecoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mVideoWidth, mVideoHeight);
            // Some chipsets need an explicit max input size to avoid CORRUPTED frames.
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024);
            mDecoder.setCallback(mDecoderCallback, new Handler(mDecodeThread.getLooper()));
            if (mSurface != null && mSurface.isValid()) {
                mDecoder.configure(format, mSurface, null, 0);
                mDecoder.start();
                Log.i(TAG, "AirPlay video decoder started " + mVideoWidth + "x" + mVideoHeight);
            } else {
                Log.e(TAG, "Surface invalid, decoder not started");
            }
        } catch (Exception e) {
            Log.e(TAG, "initDecoder failed", e);
        }
    }

    public void addPacker(NALPacket nalPacket) {
        if (nalPacket != null) {
            packets.offer(nalPacket);
        }
    }

    public void stopVideoPlay() {
        try {
            if (mDecoder != null) {
                mDecoder.stop();
                mDecoder.release();
                mDecoder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping decoder", e);
        } finally {
            if (mDecodeThread != null) {
                mDecodeThread.quitSafely();
                mDecodeThread = null;
            }
            packets.clear();
        }
    }
}
