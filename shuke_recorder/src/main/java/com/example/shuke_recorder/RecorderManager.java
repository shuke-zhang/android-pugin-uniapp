package com.example.shuke_recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.media.audiofx.AutomaticGainControl;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 🎧 高级录音管理器（支持 AEC/NS/AGC + 平滑音量计算）
 */
public class RecorderManager {
    private static final String TAG = "RecorderManager";

    private final Context context;
    private AudioRecord recorder;
    private boolean isRecording = false;
    private Thread recordThread;
    private Listener listener;

    private boolean enableAEC = false;
    private boolean enableNS = false;
    private boolean enableAGC = false;

    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private AutomaticGainControl agc;

    public interface Listener {
        void onStart();
        void onProcess(List<int[]> buffers, int volume, long durationMs, int sampleRate);
        void onStop();
        void onError(String message);
    }

    public RecorderManager(Context context) {
        this.context = context;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setEffectOptions(boolean aec, boolean ns, boolean agc) {
        this.enableAEC = aec;
        this.enableNS = ns;
        this.enableAGC = agc;
    }

    public void toggleAEC(boolean enable) {
        if (aec != null) aec.setEnabled(enable);
        enableAEC = enable;
    }

    public boolean hasPermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void requestPermission(Activity activity) {
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, 2001);
    }

    public void start(String type, int sampleRate) {
        stop();

        if (!hasPermission()) {
            if (listener != null) listener.onError("未获得录音权限");
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // ✅ 支持边录边播
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            if (listener != null) listener.onError("AudioRecord 初始化失败");
            return;
        }

        // ✅ 初始化音效模块
        int sessionId = recorder.getAudioSessionId();
        if (enableAEC && AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId);
            if (aec != null) aec.setEnabled(true);
        }
        if (enableNS && NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId);
            if (ns != null) ns.setEnabled(true);
        }
        if (enableAGC && AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(sessionId);
            if (agc != null) agc.setEnabled(true);
        }

        recorder.startRecording();
        isRecording = true;
        if (listener != null) listener.onStart();

        recordThread = new Thread(() -> {
            short[] buffer = new short[bufferSize];
            long startTime = SystemClock.elapsedRealtime();
            double noiseBase = -50; // 环境噪声基线
            Handler main = new Handler(Looper.getMainLooper());

            while (isRecording) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0 && listener != null) {
                    int[] frame = new int[read];
                    double sum = 0;
                    for (int i = 0; i < read; i++) {
                        frame[i] = buffer[i];
                        sum += buffer[i] * buffer[i];
                    }

                    double rms = Math.sqrt(sum / read);
                    double db = 20 * Math.log10(rms / 32768.0 + 1e-6);

                    // ✅ 平滑更新噪声基线（取过去几秒的平均噪声）
                    noiseBase = 0.95 * noiseBase + 0.05 * db; // 慢速跟随环境变化

                    // ✅ 根据噪声基线动态调整映射区间
                    double rangeTop = noiseBase + 40; // 从噪声基线往上 40dB
                    double mapped = (db - noiseBase) * (100.0 / 40);

                    int volume = (int) mapped;
                    if (volume < 0) volume = 0;
                    if (volume > 100) volume = 100;

                    List<int[]> out = new ArrayList<>();
                    out.add(frame);
                    long duration = SystemClock.elapsedRealtime() - startTime;

                    int finalVolume = volume;
                    main.post(() -> listener.onProcess(out, finalVolume, duration, sampleRate));
                }
            }
            stopInternal();
        });
        recordThread.start();
    }

    public void stop() {
        isRecording = false;
    }

    private void stopInternal() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Throwable ignored) {}
        recorder = null;

        if (aec != null) { aec.release(); aec = null; }
        if (ns != null) { ns.release(); ns = null; }
        if (agc != null) { agc.release(); agc = null; }

        if (listener != null) {
            new Handler(Looper.getMainLooper()).post(listener::onStop);
        }
    }
}
