package com.example.shuke_audioplay;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 🎧 顺序音频任务播放器：严格按 ID 顺序播放（0,1,2,...）
 * - 缺口等待：只有 expectedNextId 到齐才开始/继续播放
 * - startPlayId：可配置起始 ID，小于它的任务一律忽略
 */
public class AudioQueuePlayer {

    public enum OutputMode { SPEAKER, EARPIECE, BLUETOOTH }

    public interface Listener {
        void onQueued(String id, int queueSize);
        void onStart(String id, int queueSize);
        void onProgress(String id, long positionMs, long durationMs);
        void onComplete(String id, int queueSize);
        void onError(String id, String message);
        void onQueueEmpty();
        void onOutputModeChanged(OutputMode mode);
    }

    private static final String TAG = "AudioQueuePlayer";
    private static final long PROGRESS_INTERVAL = 300L;

    private final Context ctx;
    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 任务池：按 ID 排序存放到达的任务（可能乱序到达） */
    private final TreeMap<Integer, AudioTask> taskMap = new TreeMap<>();

    private MediaPlayer player;
    private Listener listener;

    /** 播放状态控制 */
    private boolean isPlaying = false;
    private boolean isManualClear = false;

    /** 起始 ID 与下一个期望播放的 ID 指针 */
    private int startPlayId = 0;
    private int expectedNextId = 0;

    /** 单个任务结构 */
    private static class AudioTask {
        final int id;
        final String base64;
        AudioTask(int id, String base64) {
            this.id = id;
            this.base64 = base64;
        }
    }

    public AudioQueuePlayer(Context context) {
        this.ctx = context.getApplicationContext();
        this.audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        resetPointers(0);
    }

    public void setListener(Listener l) { this.listener = l; }

    /** 设置起始 ID，并重置指针 */
    public synchronized void setStartPlayId(int id) {
        resetPointers(id);
        Log.i(TAG, "🎯 startPlayId set to " + id + ", expectedNextId=" + expectedNextId);
    }

    private void resetPointers(int startId) {
        this.startPlayId = startId;
        this.expectedNextId = startId;
    }

    /** 入队：只存储，不允许插队播放；等到 expectedNextId 到达才播放 */
    public synchronized void enqueue(String idStr, String base64) {
        if (base64 == null || base64.isEmpty()) {
            if (listener != null) listener.onError(idStr, "empty_input");
            return;
        }

        final int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (Exception e) {
            if (listener != null) listener.onError(idStr, "invalid_id");
            return;
        }

        // 忽略比起点小的任务
        if (id < startPlayId) {
            Log.w(TAG, "⚠️ ignore id=" + id + " < startPlayId=" + startPlayId);
            return;
        }

        taskMap.put(id, new AudioTask(id, base64));
        if (listener != null) listener.onQueued(idStr, taskMap.size());
        Log.i(TAG, "✅ enqueued id=" + id + ", queueSize=" + taskMap.size()
                + ", expectedNextId=" + expectedNextId);

        // 只有当“刚好等到 expectedNextId” 且当前不在播放，才启动
        if (!isPlaying && taskMap.containsKey(expectedNextId)) {
            playNextIfReady();
        }
    }

    /** 仅当 expectedNextId 到齐时才真正开播；否则保持等待 */
    private synchronized void playNextIfReady() {
        if (!taskMap.containsKey(expectedNextId)) {
            // 缺口：等待更小的 ID 到齐；不发 queueEmpty
            Log.i(TAG, "⏳ waiting for id=" + expectedNextId + ", currentTop="
                    + (taskMap.isEmpty() ? "none" : taskMap.firstKey()));
            isPlaying = false;
            return;
        }

        final AudioTask task = taskMap.remove(expectedNextId);
        startPlayTask(task);
    }

    /** 启动播放指定任务 */
    private void startPlayTask(AudioTask task) {
        isManualClear = false;  // 进入播放序列
        stopInternal();         // 停掉上一个
        isPlaying = true;
        Log.i(TAG, "❌ 开始进行播放");

        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            );

            String b64 = task.base64;
            if (b64.startsWith("data:")) {
                int idx = b64.indexOf("base64,");
                if (idx != -1) b64 = b64.substring(idx + 7);
            }

            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            File tmp = File.createTempFile("audio_", ".mp3", ctx.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            Log.i(TAG, "🎧 开始播放任务，ID=" + task.id
                    + " | 临时文件路径=" + tmp.getAbsolutePath()
                    + " | 数据大小=" + data.length);

            player.setDataSource(tmp.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                Log.i(TAG, "▶️ 已准备好，开始播放，ID=" + task.id);
                if (listener != null) listener.onStart(String.valueOf(task.id), taskMap.size());
                mp.start();
                startProgress();
            });

            player.setOnCompletionListener(mp -> {
                stopProgress();
                Log.i(TAG, "✅ 播放任务完成，ID=" + task.id);
                if (listener != null) listener.onComplete(String.valueOf(task.id), taskMap.size());
                // 当前任务完成，推进期望 ID
                synchronized (AudioQueuePlayer.this) {
                    expectedNextId++;
                    // 尝试继续播下一个（如果下一个期望 ID 已经到齐）
                    if (taskMap.containsKey(expectedNextId)) {
                        playNextIfReady();
                    } else {
                        // 没有下一个期望 ID 的任务，当前不在播放，等待后续入队
                        isPlaying = false;
                        // 如果任务池也为空，且没有缺口（此时“缺口”定义：expectedNextId 不存在于 taskMap，且 taskMap 为空）
                        // 则自然结束发 queueEmpty
                        if (taskMap.isEmpty() && !isManualClear) {
                            if (listener != null) listener.onQueueEmpty();
                            Log.i(TAG, "🎉 queue empty (natural)");
                        }
                    }
                }
            });

            player.setOnErrorListener((mp, what, extra) -> {
                stopProgress();
                if (listener != null) listener.onError(String.valueOf(task.id), "MediaPlayer error: " + what);
                synchronized (AudioQueuePlayer.this) {
                    expectedNextId++; // 出错也推进，避免卡住
                    if (taskMap.containsKey(expectedNextId)) {
                        playNextIfReady();
                    } else {
                        isPlaying = false;
                        if (taskMap.isEmpty() && !isManualClear) {
                            if (listener != null) listener.onQueueEmpty();
                            Log.i(TAG, "🎉 queue empty (after error)");
                        }
                    }
                }
                return true;
            });

            player.prepareAsync();

        } catch (IOException e) {
            Log.e(TAG, "播放失败: " + e.getMessage());
            if (listener != null) listener.onError(String.valueOf(task.id), e.getMessage());
            synchronized (AudioQueuePlayer.this) {
                expectedNextId++;
                if (taskMap.containsKey(expectedNextId)) {
                    playNextIfReady();
                } else {
                    isPlaying = false;
                    if (taskMap.isEmpty() && !isManualClear) {
                        if (listener != null) listener.onQueueEmpty();
                        Log.i(TAG, "🎉 queue empty (after exception)");
                    }
                }
            }
        }
    }

    /** 外部 STOP：不触发 queueEmpty（手动语义） */
    public synchronized void clear() {
        isManualClear = true;
        stopInternal();
        taskMap.clear();
        // 重置为起点等待
        expectedNextId = startPlayId;
        isPlaying = false;
        Log.i(TAG, "🧹 cleared. reset expectedNextId=" + expectedNextId);
    }

    public synchronized void release() {
        stopInternal();
        taskMap.clear();
        Log.i(TAG, "🧩 released");
    }

    public synchronized int getQueueSize() { return taskMap.size(); }

    /** 音频路由 */
    public void setOutputMode(OutputMode mode) {
        try {
            switch (mode) {
                case SPEAKER:
                    audioManager.setMode(AudioManager.MODE_NORMAL);
                    audioManager.setSpeakerphoneOn(true);
                    break;
                case EARPIECE:
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    audioManager.setSpeakerphoneOn(false);
                    break;
                case BLUETOOTH:
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    audioManager.setSpeakerphoneOn(false);
                    tryStartBluetoothSco();
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "切换音频输出失败: " + t.getMessage());
        }
        if (listener != null) listener.onOutputModeChanged(mode);
    }

    private void tryStartBluetoothSco() {
        try {
            if (!audioManager.isBluetoothScoOn()) {
                audioManager.startBluetoothSco();
                audioManager.setBluetoothScoOn(true);
            }
        } catch (Throwable ignored) {}
    }

    /** 只做内部停止，不改 expectedNextId */
    private void stopInternal() {
        stopProgress();
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Throwable ignored) {}
            player = null;
        }
    }

    // 进度上报
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.isPlaying()) {
                long pos = player.getCurrentPosition();
                long dur = player.getDuration();
                if (listener != null) listener.onProgress("playing", pos, dur);
                handler.postDelayed(this, PROGRESS_INTERVAL);
            }
        }
    };

    private void startProgress() {
        handler.post(tick);
    }

    private void stopProgress() {
        handler.removeCallbacks(tick);
    }
}
