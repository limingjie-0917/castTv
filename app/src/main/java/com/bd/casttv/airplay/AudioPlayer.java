package com.bd.casttv.airplay;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Decodes AAC-ELD audio frames from the AirPlay mirroring stream and plays them
 * through an {@link AudioTrack}.
 *
 * Adapted from caijianxiong/AirplayAndroidReceiver (MIT).
 */
public class AudioPlayer extends Thread {

    private static final String TAG = "AirPlayAudioPlayer";

    private AudioTrack mTrack;
    private final int mChannel = AudioFormat.CHANNEL_OUT_STEREO;
    private final int mSampleRate = 44100;
    private volatile boolean isStopThread = false;
    private final int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private final BlockingQueue<PCMPacket> packets = new LinkedBlockingQueue<>(500);

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private MediaCodec mDecoder;

    public AudioPlayer() {
        this.mTrack = new AudioTrack(AudioManager.STREAM_MUSIC, mSampleRate, mChannel, mAudioFormat,
                AudioTrack.getMinBufferSize(mSampleRate, mChannel, mAudioFormat), AudioTrack.MODE_STREAM);
        this.mTrack.play();
        initDecoder();
    }

    private void initDecoder() {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, 44100, 2);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD);
            // CSD-0 for AAC-ELD (Profile 39), 44100Hz, stereo.
            byte[] csd0 = new byte[]{(byte) 0xF8, (byte) 0xE8, (byte) 0x50, (byte) 0x00};
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));

            mDecoder = MediaCodec.createDecoderByType(MIME_TYPE);
            mDecoder.configure(format, null, null, 0);
            mDecoder.start();
        } catch (Exception e) {
            Log.e(TAG, "Audio decoder init failed", e);
        }
    }

    public void addPacker(PCMPacket pcmPacket) {
        packets.offer(pcmPacket);
    }

    @Override
    public void run() {
        while (!isStopThread) {
            try {
                PCMPacket packet = packets.take();
                if (packets.size() > 400) {
                    Log.w(TAG, "Queue near full, clearing: " + packets.size());
                    packets.clear();
                    continue;
                }
                doPlay(packet);
            } catch (InterruptedException e) {
                // stopping
                break;
            } catch (Exception e) {
                Log.e(TAG, "run error", e);
            }
        }
    }

    private void doPlay(PCMPacket pcmPacket) {
        if (mDecoder != null && mTrack != null && pcmPacket != null && pcmPacket.data != null) {
            decodeAndPlay(pcmPacket.data);
        }
    }

    private int mNoOutputCounter = 0;

    private void decodeAndPlay(byte[] data) {
        int inputIndex = mDecoder.dequeueInputBuffer(10000);
        if (inputIndex >= 0) {
            ByteBuffer buffer = mDecoder.getInputBuffer(inputIndex);
            if (buffer != null) {
                buffer.clear();
                buffer.put(data);
            }
            mDecoder.queueInputBuffer(inputIndex, 0, data.length, 0, 0);
        }

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outputIndex = mDecoder.dequeueOutputBuffer(info, 10000);
        if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            mNoOutputCounter++;
            if (mNoOutputCounter > 50) {
                Log.e(TAG, "Decoder stuck, reinitializing...");
                reinitDecoder();
                mNoOutputCounter = 0;
            }
        } else {
            mNoOutputCounter = 0;
            while (outputIndex >= 0) {
                ByteBuffer buffer = mDecoder.getOutputBuffer(outputIndex);
                if (buffer != null) {
                    byte[] pcm = new byte[info.size];
                    buffer.get(pcm);
                    buffer.clear();
                    if (mTrack != null) {
                        mTrack.write(pcm, 0, pcm.length);
                    }
                }
                mDecoder.releaseOutputBuffer(outputIndex, false);
                outputIndex = mDecoder.dequeueOutputBuffer(info, 0);
            }
        }
    }

    private synchronized void reinitDecoder() {
        try {
            if (mDecoder != null) {
                mDecoder.stop();
                mDecoder.release();
            }
            initDecoder();
        } catch (Exception e) {
            Log.e(TAG, "reinitDecoder failed", e);
        }
    }

    public void stopPlay() {
        isStopThread = true;
        interrupt();
        if (mTrack != null) {
            try {
                mTrack.flush();
                mTrack.stop();
                mTrack.release();
            } catch (Exception e) {
                Log.e(TAG, "stopPlay track error", e);
            }
            packets.clear();
            mTrack = null;
        }
        if (mDecoder != null) {
            try {
                mDecoder.stop();
            } catch (Exception ignored) {
            }
            try {
                mDecoder.release();
            } catch (Exception ignored) {
            }
            mDecoder = null;
        }
    }
}
