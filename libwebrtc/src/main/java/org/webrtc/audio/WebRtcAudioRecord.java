/*
 *  Copyright (c) 2015 The WebRTC project authors. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */

package org.webrtc.audio;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder.AudioSource;
import android.os.Build;
import android.os.Process;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.webrtc.CalledByNative;
import org.webrtc.Logging;
import org.webrtc.ThreadUtils;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordErrorCallback;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStateCallback;
import org.webrtc.audio.JavaAudioDeviceModule.AudioRecordStartErrorCode;
import org.webrtc.audio.JavaAudioDeviceModule.SamplesReadyCallback;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

class WebRtcAudioRecord {
    private static final String TAG = "WebRtcAudioRecordExternal";

    // Requested size of each recorded buffer provided to the client.
    private static final int CALLBACK_BUFFER_SIZE_MS = 10;

    // Average number of callbacks per second.
    private static final int BUFFERS_PER_SECOND = 1000 / CALLBACK_BUFFER_SIZE_MS;

    // We ask for a native buffer size of BUFFER_SIZE_FACTOR * (minimum required
    // buffer size). The extra space is allocated to guard against glitches under
    // high load.
    private static final int BUFFER_SIZE_FACTOR = 2;

    // The AudioRecordJavaThread is allowed to wait for successful call to join()
    // but the wait times out afther this amount of time.
    private static final long AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS = 2000;

    public static final int DEFAULT_AUDIO_SOURCE = AudioSource.VOICE_COMMUNICATION;

    // Default audio data format is PCM 16 bit per sample.
    // Guaranteed to be supported by all devices.
    public static final int DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static final int AUDIO_RECORD_START = 0;
    private static final int AUDIO_RECORD_STOP = 1;
    private static final int CHECK_REC_STATUS_DELAY_MS = 100;

    private final Context context;
    private final AudioManager audioManager;
    private final int audioSource;
    private final int audioFormat;

    private long nativeAudioRecord;

    private final WebRtcAudioEffects effects;

    private ByteBuffer byteBuffer;

    private AudioRecord audioRecord;
    private AudioRecordThread audioThread;
    @Nullable
    private AudioDeviceInfo preferredDevice;
    @Nullable
    private ScheduledExecutorService executor;
    @Nullable
    private ScheduledFuture<String> future;
    private volatile boolean microphoneMute;
    private boolean audioSourceMatchesRecordingSession;
    private boolean isAudioConfigVerified;
    private byte[] emptyBytes;

    private final AudioRecordErrorCallback errorCallback;
    private final AudioRecordStateCallback stateCallback;
    private final SamplesReadyCallback audioSamplesReadyCallback;
    private final boolean isAcousticEchoCancelerSupported;
    private final boolean isNoiseSuppressorSupported;

    /**
     * Audio thread which keeps calling ByteBuffer.read() waiting for audio
     * to be recorded. Feeds recorded data to the native counterpart as a
     * periodic sequence of callbacks using DataIsRecorded().
     * This thread uses a Process.THREAD_PRIORITY_URGENT_AUDIO priority.
     */
    private class AudioRecordThread extends Thread {
        private volatile boolean keepAlive = true;

        public AudioRecordThread(String name) {
            super(name);
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            Logging.d(TAG, "AudioRecordThread" + WebRtcAudioUtils.getThreadInfo());
            assertTrue(audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING);
            WebRtcAudioRecord.this.doAudioRecordStateCallback(AUDIO_RECORD_START);

            long lastTime = System.nanoTime();
            while (keepAlive) {
                int bytesRead = audioRecord.read(byteBuffer, byteBuffer.capacity());
                if (bytesRead == byteBuffer.capacity()) {
                    if (microphoneMute) {
                        byteBuffer.clear();
                        byteBuffer.put(emptyBytes);
                    }
                    // It's possible we've been shut down during the read, and stopRecording() tried and
                    // failed to join this thread. To be a bit safer, try to avoid calling any native methods
                    // in case they've been unregistered after stopRecording() returned.
                    if (keepAlive) {
                        nativeDataIsRecorded(nativeAudioRecord, bytesRead);
                    }
                    if (audioSamplesReadyCallback != null) {
                        // Copy the entire byte buffer array. The start of the byteBuffer is not necessarily
                        // at index 0.
                        byte[] data = Arrays.copyOfRange(byteBuffer.array(), byteBuffer.arrayOffset(),
                                byteBuffer.capacity() + byteBuffer.arrayOffset());
                        audioSamplesReadyCallback.onWebRtcAudioRecordSamplesReady(
                                new JavaAudioDeviceModule.AudioSamples(audioRecord.getAudioFormat(),
                                        audioRecord.getChannelCount(), audioRecord.getSampleRate(), data));
                    }
                } else {
                    String errorMessage = "AudioRecord.read failed: " + bytesRead;
                    Logging.e(TAG, errorMessage);
                    if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                        keepAlive = false;
                        reportWebRtcAudioRecordError(errorMessage);
                    }
                }
            }

            try {
                if (audioRecord != null) {
                    audioRecord.stop();
                    WebRtcAudioRecord.this.doAudioRecordStateCallback(AUDIO_RECORD_START);
                }
            } catch (IllegalStateException e) {
                Logging.e(TAG, "AudioRecord.stop failed: " + e.getMessage());
            }
        }

        // Stops the inner thread loop and also calls AudioRecord.stop().
        // Does not block the calling thread.
        public void stopThread() {
            Logging.d(TAG, "stopThread");
            keepAlive = false;
        }
    }

    @CalledByNative
    WebRtcAudioRecord(Context context, AudioManager audioManager) {
        this(context, audioManager, DEFAULT_AUDIO_SOURCE, DEFAULT_AUDIO_FORMAT,
                null /* errorCallback */,
                null /*AudioRecordStateCallback*/,
                null /* audioSamplesReadyCallback */,
                WebRtcAudioEffects.isAcousticEchoCancelerSupported(),
                WebRtcAudioEffects.isNoiseSuppressorSupported());
    }

    public WebRtcAudioRecord(Context context, AudioManager audioManager, int audioSource, int audioFormat,
                             AudioRecordErrorCallback errorCallback,
                             AudioRecordStateCallback stateCallback,
                             SamplesReadyCallback audioSamplesReadyCallback,
                             boolean isAcousticEchoCancelerSupported,
                             boolean isNoiseSuppressorSupported) {
        if (isAcousticEchoCancelerSupported && !WebRtcAudioEffects.isAcousticEchoCancelerSupported()) {
            throw new IllegalArgumentException("HW AEC not supported");
        }
        if (isNoiseSuppressorSupported && !WebRtcAudioEffects.isNoiseSuppressorSupported()) {
            throw new IllegalArgumentException("HW NS not supported");
        }
        this.effects = new WebRtcAudioEffects();
        this.context = context;
        this.audioManager = audioManager;
        this.audioSource = audioSource;
        this.audioFormat = audioFormat;
        this.errorCallback = errorCallback;
        this.stateCallback = stateCallback;
        this.audioSamplesReadyCallback = audioSamplesReadyCallback;
        this.isAcousticEchoCancelerSupported = isAcousticEchoCancelerSupported;
        this.isNoiseSuppressorSupported = isNoiseSuppressorSupported;
        Logging.d("WebRtcAudioRecordExternal", "ctor" + WebRtcAudioUtils.getThreadInfo());
    }

    @CalledByNative
    public void setNativeAudioRecord(long nativeAudioRecord) {
        this.nativeAudioRecord = nativeAudioRecord;
    }

    @CalledByNative
    boolean isAcousticEchoCancelerSupported() {
        return isAcousticEchoCancelerSupported;
    }

    @CalledByNative
    boolean isNoiseSuppressorSupported() {
        return isNoiseSuppressorSupported;
    }

    @CalledByNative
    boolean isAudioConfigVerified() {
        return this.isAudioConfigVerified;
    }

    @CalledByNative
    boolean isAudioSourceMatchingRecordingSession() {
        if (!this.isAudioConfigVerified) {
            Logging.w("WebRtcAudioRecordExternal", "Audio configuration has not yet been verified");
            return false;
        } else {
            return this.audioSourceMatchesRecordingSession;
        }
    }

    @CalledByNative
    private boolean enableBuiltInAEC(boolean enable) {
        Logging.d(TAG, "enableBuiltInAEC(" + enable + ")");
        return effects.setAEC(enable);
    }

    @CalledByNative
    private boolean enableBuiltInNS(boolean enable) {
        Logging.d(TAG, "enableBuiltInNS(" + enable + ")");
        return effects.setNS(enable);
    }

    @CalledByNative
    private int initRecording(int sampleRate, int channels) {
        Logging.d(TAG, "initRecording(sampleRate=" + sampleRate + ", channels=" + channels + ")");
        if (audioRecord != null) {
            reportWebRtcAudioRecordInitError("InitRecording called twice without StopRecording.");
            return -1;
        }
        final int bytesPerFrame = channels * getBytesPerSample(audioFormat);
        final int framesPerBuffer = sampleRate / BUFFERS_PER_SECOND;
        byteBuffer = ByteBuffer.allocateDirect(bytesPerFrame * framesPerBuffer);
        if (!(byteBuffer.hasArray())) {
            reportWebRtcAudioRecordInitError("ByteBuffer does not have backing array.");
            return -1;
        }
        Logging.d(TAG, "byteBuffer.capacity: " + byteBuffer.capacity());
        emptyBytes = new byte[byteBuffer.capacity()];
        // Rather than passing the ByteBuffer with every callback (requiring
        // the potentially expensive GetDirectBufferAddress) we simply have the
        // the native class cache the address to the memory once.
        nativeCacheDirectBufferAddress(nativeAudioRecord, byteBuffer);

        // Get the minimum buffer size required for the successful creation of
        // an AudioRecord object, in byte units.
        // Note that this size doesn't guarantee a smooth recording under load.
        final int channelConfig = channelCountToConfiguration(channels);
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            reportWebRtcAudioRecordInitError("AudioRecord.getMinBufferSize failed: " + minBufferSize);
            return -1;
        }
        Logging.d(TAG, "AudioRecord.getMinBufferSize: " + minBufferSize);

        // Use a larger buffer size than the minimum required when creating the
        // AudioRecord instance to ensure smooth recording under load. It has been
        // verified that it does not increase the actual recording latency.
        int bufferSizeInBytes = Math.max(BUFFER_SIZE_FACTOR * minBufferSize, byteBuffer.capacity());
        Logging.d(TAG, "bufferSizeInBytes: " + bufferSizeInBytes);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                this.audioRecord = createAudioRecordOnMOrHigher(this.audioSource, sampleRate, channelConfig, this.audioFormat, bufferSizeInBytes);
                if (this.preferredDevice != null) {
                    this.setPreferredDevice(this.preferredDevice);
                }
            } else {
                this.audioRecord = createAudioRecordOnLowerThanM(this.audioSource, sampleRate, channelConfig, this.audioFormat, bufferSizeInBytes);
            }
        } catch (IllegalArgumentException e) {
            reportWebRtcAudioRecordInitError("AudioRecord ctor error: " + e.getMessage());
            releaseAudioResources();
            return -1;
        }

        if (this.audioRecord != null && this.audioRecord.getState() == 1) {
            this.effects.enable(this.audioRecord.getAudioSessionId());
            this.logMainParameters();
            this.logMainParametersExtended();
            int numActiveRecordingSessions = this.logRecordingConfigurations(false);
            if (numActiveRecordingSessions != 0) {
                Logging.w("WebRtcAudioRecordExternal", "Potential microphone conflict. Active sessions: " + numActiveRecordingSessions);
            }
            return framesPerBuffer;
        } else {
            this.reportWebRtcAudioRecordInitError("Creation or initialization of audio recorder failed.");
            this.releaseAudioResources();
            return -1;
        }
    }

    @RequiresApi(23)
    @TargetApi(23)
    void setPreferredDevice(@Nullable AudioDeviceInfo preferredDevice) {
        Logging.d("WebRtcAudioRecordExternal", "setPreferredDevice " + (preferredDevice != null ? preferredDevice.getId() : null));
        this.preferredDevice = preferredDevice;
        if (this.audioRecord != null && !this.audioRecord.setPreferredDevice(preferredDevice)) {
            Logging.e("WebRtcAudioRecordExternal", "setPreferredDevice failed");
        }
    }

    @CalledByNative
    private boolean startRecording() {
        Logging.d(TAG, "startRecording");
        assertTrue(audioRecord != null);
        assertTrue(audioThread == null);
        try {
            audioRecord.startRecording();
        } catch (IllegalStateException e) {
            reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode.AUDIO_RECORD_START_EXCEPTION,
                    "AudioRecord.startRecording failed: " + e.getMessage());
            return false;
        }
        if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            reportWebRtcAudioRecordStartError(AudioRecordStartErrorCode.AUDIO_RECORD_START_STATE_MISMATCH,
                    "AudioRecord.startRecording failed - incorrect state :"
                            + audioRecord.getRecordingState());
            return false;
        }
        audioThread = new AudioRecordThread("AudioRecordJavaThread");
        audioThread.start();
        this.scheduleLogRecordingConfigurationsTask();
        return true;
    }

    @CalledByNative
    private boolean stopRecording() {
        Logging.d(TAG, "stopRecording");
        assertTrue(audioThread != null);
        if (this.future != null) {
            if (!this.future.isDone()) {
                this.future.cancel(true);
            }
            this.future = null;
        }

        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }

        audioThread.stopThread();
        if (!ThreadUtils.joinUninterruptibly(audioThread, AUDIO_RECORD_THREAD_JOIN_TIMEOUT_MS)) {
            Logging.e(TAG, "Join of AudioRecordJavaThread timed out");
            WebRtcAudioUtils.logAudioState(TAG, context, audioManager);
        }
        audioThread = null;
        effects.release();
        releaseAudioResources();
        return true;
    }

    @TargetApi(23)
    private static AudioRecord createAudioRecordOnMOrHigher(int audioSource, int sampleRate, int channelConfig, int audioFormat, int bufferSizeInBytes) {
        Logging.d("WebRtcAudioRecordExternal", "createAudioRecordOnMOrHigher");
        return (new AudioRecord.Builder()).setAudioSource(audioSource).setAudioFormat((new android.media.AudioFormat.Builder()).setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(channelConfig).build()).setBufferSizeInBytes(bufferSizeInBytes).build();
    }

    private static AudioRecord createAudioRecordOnLowerThanM(int audioSource, int sampleRate, int channelConfig, int audioFormat, int bufferSizeInBytes) {
        Logging.d("WebRtcAudioRecordExternal", "createAudioRecordOnLowerThanM");
        return new AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSizeInBytes);
    }

    private void logMainParameters() {
        Logging.d(TAG,
                "AudioRecord: "
                        + "session ID: " + audioRecord.getAudioSessionId() + ", "
                        + "channels: " + audioRecord.getChannelCount() + ", "
                        + "sample rate: " + audioRecord.getSampleRate());
    }

    private void logMainParametersExtended() {
        if (Build.VERSION.SDK_INT >= 23) {
            Logging.d(TAG,
                    "AudioRecord: "
                            // The frame count of the native AudioRecord buffer.
                            + "buffer size in frames: " + audioRecord.getBufferSizeInFrames());
        }
    }

    @TargetApi(24)
    private int logRecordingConfigurations(boolean verifyAudioConfig) {
        if (Build.VERSION.SDK_INT < 24) {
            Logging.w("WebRtcAudioRecordExternal", "AudioManager#getActiveRecordingConfigurations() requires N or higher");
            return 0;
        } else if (this.audioRecord == null) {
            return 0;
        } else {
            List<AudioRecordingConfiguration> configs = this.audioManager.getActiveRecordingConfigurations();
            int numActiveRecordingSessions = configs.size();
            Logging.d("WebRtcAudioRecordExternal", "Number of active recording sessions: " + numActiveRecordingSessions);
            if (numActiveRecordingSessions > 0) {
                logActiveRecordingConfigs(this.audioRecord.getAudioSessionId(), configs);
                if (verifyAudioConfig) {
                    this.audioSourceMatchesRecordingSession = verifyAudioConfig(this.audioRecord.getAudioSource(), this.audioRecord.getAudioSessionId(), this.audioRecord.getFormat(), this.audioRecord.getRoutedDevice(), configs);
                    this.isAudioConfigVerified = true;
                }
            }

            return numActiveRecordingSessions;
        }
    }

    // Helper method which throws an exception  when an assertion has failed.
    private static void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Expected condition to be true");
        }
    }

    private int channelCountToConfiguration(int channels) {
        return (channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO);
    }

    private native void nativeCacheDirectBufferAddress(
            long nativeAudioRecordJni, ByteBuffer byteBuffer);

    private native void nativeDataIsRecorded(long nativeAudioRecordJni, int bytes);

    // Sets all recorded samples to zero if |mute| is true, i.e., ensures that
    // the microphone is muted.
    public void setMicrophoneMute(boolean mute) {
        Logging.w(TAG, "setMicrophoneMute(" + mute + ")");
        microphoneMute = mute;
    }

    // Releases the native AudioRecord resources.
    private void releaseAudioResources() {
        Logging.d(TAG, "releaseAudioResources");
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
    }

    private void reportWebRtcAudioRecordInitError(String errorMessage) {
        Logging.e(TAG, "Init recording error: " + errorMessage);
        WebRtcAudioUtils.logAudioState(TAG, context, audioManager);
        if (errorCallback != null) {
            errorCallback.onWebRtcAudioRecordInitError(errorMessage);
        }
    }

    private void reportWebRtcAudioRecordStartError(
            AudioRecordStartErrorCode errorCode, String errorMessage) {
        Logging.e(TAG, "Start recording error: " + errorCode + ". " + errorMessage);
        WebRtcAudioUtils.logAudioState(TAG, context, audioManager);
        if (errorCallback != null) {
            errorCallback.onWebRtcAudioRecordStartError(errorCode, errorMessage);
        }
    }

    private void reportWebRtcAudioRecordError(String errorMessage) {
        Logging.e(TAG, "Run-time recording error: " + errorMessage);
        WebRtcAudioUtils.logAudioState(TAG, context, audioManager);
        if (errorCallback != null) {
            errorCallback.onWebRtcAudioRecordError(errorMessage);
        }
    }

    private void doAudioRecordStateCallback(int audioState) {
        Logging.d(TAG, "doAudioRecordStateCallback: " + audioStateToString(audioState));
        if (this.stateCallback != null) {
            if (audioState == AUDIO_RECORD_START) {
                this.stateCallback.onWebRtcAudioRecordStart();
            } else if (audioState == AUDIO_RECORD_STOP) {
                this.stateCallback.onWebRtcAudioRecordStop();
            } else {
                Logging.e("WebRtcAudioRecordExternal", "Invalid audio state");
            }
        }
    }

    // Reference from Android code, AudioFormat.getBytesPerSample. BitPerSample / 8
    // Default audio data format is PCM 16 bits per sample.
    // Guaranteed to be supported by all devices
    private static int getBytesPerSample(int audioFormat) {
        switch (audioFormat) {
            case AudioFormat.ENCODING_PCM_8BIT:
                return 1;
            case AudioFormat.ENCODING_PCM_16BIT:
            case AudioFormat.ENCODING_IEC61937:
            case AudioFormat.ENCODING_DEFAULT:
                return 2;
            case AudioFormat.ENCODING_PCM_FLOAT:
                return 4;
            case AudioFormat.ENCODING_INVALID:
            default:
                throw new IllegalArgumentException("Bad audio format " + audioFormat);
        }
    }

    private void scheduleLogRecordingConfigurationsTask() {
        Logging.d("WebRtcAudioRecordExternal", "scheduleLogRecordingConfigurationsTask");
        if (Build.VERSION.SDK_INT >= 24) {
            if (this.executor != null) {
                this.executor.shutdownNow();
            }

            this.executor = Executors.newSingleThreadScheduledExecutor();
            Callable<String> callable = () -> {
                this.logRecordingConfigurations(true);
                return "Scheduled task is done";
            };
            if (this.future != null && !this.future.isDone()) {
                this.future.cancel(true);
            }

            this.future = this.executor.schedule(callable, 100L, TimeUnit.MILLISECONDS);
        }
    }

    @TargetApi(24)
    private static boolean logActiveRecordingConfigs(int session, List<AudioRecordingConfiguration> configs) {
        assertTrue(!configs.isEmpty());
        Iterator<AudioRecordingConfiguration> it = configs.iterator();
        Logging.d(TAG, "AudioRecordingConfigurations: ");

        StringBuilder conf;
        for (; it.hasNext(); Logging.d(TAG, conf.toString())) {
            AudioRecordingConfiguration config = (AudioRecordingConfiguration) it.next();
            conf = new StringBuilder();
            int audioSource = config.getClientAudioSource();
            conf.append("  client audio source=").append(WebRtcAudioUtils.audioSourceToString(audioSource)).append(", client session id=").append(config.getClientAudioSessionId()).append(" (").append(session).append(")").append("\n");
            AudioFormat format = config.getFormat();
            conf.append("  Device AudioFormat: ").append("channel count=").append(format.getChannelCount()).append(", channel index mask=").append(format.getChannelIndexMask()).append(", channel mask=").append(WebRtcAudioUtils.channelMaskToString(format.getChannelMask())).append(", encoding=").append(WebRtcAudioUtils.audioEncodingToString(format.getEncoding())).append(", sample rate=").append(format.getSampleRate()).append("\n");
            format = config.getClientFormat();
            conf.append("  Client AudioFormat: ").append("channel count=").append(format.getChannelCount()).append(", channel index mask=").append(format.getChannelIndexMask()).append(", channel mask=").append(WebRtcAudioUtils.channelMaskToString(format.getChannelMask())).append(", encoding=").append(WebRtcAudioUtils.audioEncodingToString(format.getEncoding())).append(", sample rate=").append(format.getSampleRate()).append("\n");
            AudioDeviceInfo device = config.getAudioDevice();
            if (device != null) {
                assertTrue(device.isSource());
                conf.append("  AudioDevice: ").append("type=").append(WebRtcAudioUtils.deviceTypeToString(device.getType())).append(", id=").append(device.getId());
            }
        }
        return true;
    }

    @TargetApi(24)
    private static boolean verifyAudioConfig(int source, int session, AudioFormat format, AudioDeviceInfo device, List<AudioRecordingConfiguration> configs) {
        assertTrue(!configs.isEmpty());
        Iterator it = configs.iterator();

        AudioRecordingConfiguration config;
        AudioDeviceInfo configDevice;
        do {
            do {
                do {
                    do {
                        do {
                            do {
                                do {
                                    do {
                                        do {
                                            do {
                                                do {
                                                    if (!it.hasNext()) {
                                                        Logging.e(TAG, "verifyAudioConfig: FAILED");
                                                        return false;
                                                    }
                                                    config = (AudioRecordingConfiguration) it.next();
                                                    configDevice = config.getAudioDevice();
                                                } while (configDevice == null);
                                            } while (config.getClientAudioSource() != source);
                                        } while (config.getClientAudioSessionId() != session);
                                    } while (config.getClientFormat().getEncoding() != format.getEncoding());
                                } while (config.getClientFormat().getSampleRate() != format.getSampleRate());
                            } while (config.getClientFormat().getChannelMask() != format.getChannelMask());
                        } while (config.getClientFormat().getChannelIndexMask() != format.getChannelIndexMask());
                    } while (config.getFormat().getEncoding() == 0);
                } while (config.getFormat().getSampleRate() <= 0);
            } while (config.getFormat().getChannelMask() == 0 && config.getFormat().getChannelIndexMask() == 0);
        } while (!checkDeviceMatch(configDevice, device));

        Logging.d("WebRtcAudioRecordExternal", "verifyAudioConfig: PASS");
        return true;
    }

    @TargetApi(24)
    private static boolean checkDeviceMatch(AudioDeviceInfo devA, AudioDeviceInfo devB) {
        return devA.getId() == devB.getId() && devA.getType() == devB.getType();
    }

    private static String audioStateToString(int state) {
        switch (state) {
            case 0:
                return "START";
            case 1:
                return "STOP";
            default:
                return "INVALID";
        }
    }
}
