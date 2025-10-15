package com.example.shuke_recorder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 🎙️ PCM实时录音管理器
 * 功能：
 * - 申请录音权限
 * - 开始/停止录音
 * - 实时返回buffers、功率、时长、采样率
 */
public class RecorderManager {
    private static final String TAG = "RecorderManager";

    public interface Listener {
        void onProcess(List<int[]> buffers, double powerLevel, long durationMs, int sampleRate);
        void onError(String message);
        void onStart();
        void onStop();
    }

    private final Context context;
    private AudioRecord recorder;
    private Thread recordThread;
    private boolean isRecording = false;
    private Listener listener;

    private int sampleRate = 16000;
    private int bufferSize;
    private long startTime;

    public RecorderManager(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 检查是否有录音权限 */
    public boolean hasPermission() {
        int result = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO);
        return result == PackageManager.PERMISSION_GRANTED;
    }

    /** 申请录音权限 */
    public void requestPermission(Activity activity) {
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.RECORD_AUDIO}, 1001);
    }

    /** 开始录音 */
    @SuppressLint("MissingPermission")
    public void start(String type, int sampleRate) {
        if (isRecording) return;

        this.sampleRate = sampleRate > 0 ? sampleRate : 16000;

        bufferSize = AudioRecord.getMinBufferSize(
                this.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        if (bufferSize <= 0) bufferSize = this.sampleRate;

        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    this.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
        } catch (SecurityException e) {
            if (listener != null) listener.onError("未授予录音权限");
            return;
        } catch (Exception e) {
            if (listener != null) listener.onError("初始化录音失败: " + e.getMessage());
            return;
        }

        if (recorder == null || recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            if (listener != null) listener.onError("录音器初始化失败");
            return;
        }

        try {
            recorder.startRecording();
        } catch (SecurityException e) {
            if (listener != null) listener.onError("无录音权限");
            return;
        } catch (Exception e) {
            if (listener != null) listener.onError("启动录音失败");
            return;
        }

        isRecording = true;
        startTime = System.currentTimeMillis();

        if (listener != null) listener.onStart();

        recordThread = new Thread(() -> {
            short[] buffer = new short[bufferSize / 2];
            while (isRecording && recorder != null) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    int[] chunk = new int[read];
                    double sum = 0;
                    for (int i = 0; i < read; i++) {
                        chunk[i] = buffer[i];
                        sum += buffer[i] * buffer[i];
                    }
                    double rms = Math.sqrt(sum / read);
                    double db = 20 * Math.log10(rms / 32768.0);
                    long duration = System.currentTimeMillis() - startTime;

                    List<int[]> list = new ArrayList<>();
                    list.add(chunk);

                    if (listener != null) {
                        double powerLevel = Math.max(-120, db);
                        new Handler(Looper.getMainLooper()).post(() ->
                                listener.onProcess(list, powerLevel, duration, this.sampleRate)
                        );
                    }
                }
            }
        });
        recordThread.start();
    }

    /** 停止录音 */
    public void stop() {
        if (!isRecording) return;

        isRecording = false;
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Exception e) {
            Log.w(TAG, "停止录音异常: " + e.getMessage());
        }
        recorder = null;

        if (listener != null) listener.onStop();
    }
}
