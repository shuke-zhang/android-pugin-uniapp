package com.example.shuke_recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.media.audiofx.AutomaticGainControl;
import android.os.Build;
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
 * 新增：
 *  - RouteInfo 数据结构
 *  - Listener#onRoute(RouteInfo info) 回调：在“开始录音成功”与“路由变更”时回调当前录音通道信息
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

    public static class RouteInfo {
        public String label;           // 前缀描述：比如 "▶️ 开始录音" / "🔄 录音路由变更"
        public String typeName;        // 内置麦克风 / 蓝牙 SCO 麦克风 / USB 设备 ...
        public int deviceType;         // AudioDeviceInfo.TYPE_*
        public int deviceId;           // 设备 ID（API 23+）
        public String productName;     // 设备名
        public String address;         // 设备地址（API 28+ 可能为空）
        public int sampleRate;         // 当前 AudioRecord 采样率
        public int channels;           // 当前通道数
        public int format;             // PCM 编码（如 AudioFormat.ENCODING_PCM_16BIT）

        @Override public String toString() {
            return "RouteInfo{" +
                    "label='" + label + '\'' +
                    ", typeName='" + typeName + '\'' +
                    ", deviceType=" + deviceType +
                    ", deviceId=" + deviceId +
                    ", productName='" + productName + '\'' +
                    ", address='" + address + '\'' +
                    ", sampleRate=" + sampleRate +
                    ", channels=" + channels +
                    ", format=" + format +
                    '}';
        }
    }

    // 监听路由变化（API 24+）
    private final AudioRecord.OnRoutingChangedListener routingListener =
            (audioRouting) -> {
                logCurrentRoute("🔄 录音路由变更");
                emitRouteInfo("🔄 录音路由变更");
            };

    public interface Listener {
        void onStart();
        void onProcess(List<int[]> buffers, int volume, long durationMs, int sampleRate);
        void onStop();
        void onError(String message);

        /** 🆕 新增：当前录音通道信息（开始成功与后续路由变化都会回调一次） */
        void onRoute(RouteInfo info);
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

        // ✅ 打印“当前录音路由/音频通道”信息
        logCurrentRoute("▶️ 开始录音");

        // ✅ 【新增】开始录音成功后，立刻回调一次当前录音通道信息
        emitRouteInfo("▶️ 开始录音");

        // ✅ 监听后续路由变化（插拔耳机、蓝牙连接变化等）
        addRoutingListenerIfSupported();

        if (listener != null) listener.onStart();

        Thread t = new Thread(() -> {
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

                    // ✅ 平滑更新噪声基线
                    noiseBase = 0.95 * noiseBase + 0.05 * db;

                    // ✅ 映射到 0~100
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
        recordThread = t;
        t.start();
    }

    public void stop() {
        isRecording = false;
    }

    private void stopInternal() {
        try {
            // 移除路由监听（API 24+）
            removeRoutingListenerIfSupported();

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

    // ------------------------------
    // 🔎 路由&设备信息打印 + 回调
    // ------------------------------

    private void logCurrentRoute(String prefix) {
        if (recorder == null) return;

        // 设备信息（API 23+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo dev = recorder.getRoutedDevice();
            if (dev != null) {
                String typeStr = deviceTypeToString(dev.getType());
                CharSequence product = dev.getProductName();
                String addr = safeGetAddress(dev);

                int sr = recorder.getSampleRate();
                int ch = safeGetChannelCount(); // API 24+ 有 getChannelCount
                int fmt = recorder.getAudioFormat(); // PCM_16BIT 等（整数）

                Log.i(TAG,
                        prefix + " | 输入设备=" + typeStr +
                                " | 名称=" + product +
                                " | 地址=" + addr +
                                " | 采样率=" + sr +
                                " | 通道数=" + ch +
                                " | 格式=" + fmt);
            } else {
                Log.i(TAG, prefix + " | 未获取到路由设备（可能为系统默认内置麦克风）");
            }
        } else {
            Log.i(TAG, prefix + " | 当前系统版本低于 Android 6.0，无法获取路由设备详情");
        }
    }

    /** 将当前路由信息通过 listener.onRoute 回调给前端（主线程） */
    private void emitRouteInfo(String label) {
        if (listener == null) return;
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            RouteInfo info = buildCurrentRouteInfo(label);
            try {
                listener.onRoute(info);
            } catch (Throwable t) {
                Log.w(TAG, "onRoute callback error: " + t.getMessage());
            }
        });
    }

    /** 构造 RouteInfo（API 检查 + 兼容处理） */
    private RouteInfo buildCurrentRouteInfo(String label) {
        RouteInfo info = new RouteInfo();
        info.label = label;
        info.sampleRate = (recorder != null) ? recorder.getSampleRate() : 0;
        info.channels = safeGetChannelCount();
        info.format = (recorder != null) ? recorder.getAudioFormat() : AudioFormat.ENCODING_INVALID;

        if (recorder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                AudioDeviceInfo dev = recorder.getRoutedDevice();
                if (dev != null) {
                    int type = dev.getType();
                    info.deviceType = type;
                    info.typeName = deviceTypeToString(type);
                    info.deviceId = safeGetDeviceId(dev);
                    info.productName = String.valueOf(dev.getProductName());
                    info.address = safeGetAddress(dev);
                    return info;
                }
            } catch (Throwable ignored) {}
        }
        // 兜底（低版本或拿不到路由时）
        info.deviceType = -1;
        info.typeName = "未知/不支持";
        info.deviceId = -1;
        info.productName = "未知设备";
        info.address = "";
        return info;
    }

    /** API 24+ 有 getChannelCount */
    private int safeGetChannelCount() {
        if (recorder == null) return 1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return recorder.getChannelCount();
            } catch (Throwable ignored) {}
        }
        // 根据构造参数我们是 MONO
        return 1;
    }

    /** API 23+ 的设备 ID */
    private int safeGetDeviceId(AudioDeviceInfo dev) {
        if (dev == null) return -1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { return dev.getId(); } catch (Throwable ignored) {}
        }
        return -1;
    }

    /** API 28+ 才能拿地址；有些设备返回空字符串 */
    private String safeGetAddress(AudioDeviceInfo dev) {
        if (dev == null) return "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { return dev.getAddress(); } catch (Throwable ignored) {}
        }
        return "";
    }

    private void addRoutingListenerIfSupported() {
        if (recorder == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder.addOnRoutingChangedListener(routingListener, new Handler(Looper.getMainLooper()));
            } catch (Throwable e) {
                Log.w(TAG, "添加路由监听失败: " + e.getMessage());
            }
        }
    }

    private void removeRoutingListenerIfSupported() {
        if (recorder == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder.removeOnRoutingChangedListener(routingListener);
            } catch (Throwable ignored) {}
        }
    }

    private String deviceTypeToString(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "内置麦克风";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "有线耳机麦克风";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "有线耳机（无麦）";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "蓝牙 SCO 麦克风";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "蓝牙 A2DP（通常播放用）";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB 设备";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB 耳机麦克风";
            case AudioDeviceInfo.TYPE_FM_TUNER: return "调频收音机";
            case AudioDeviceInfo.TYPE_LINE_ANALOG: return "Line 模拟输入";
            case AudioDeviceInfo.TYPE_LINE_DIGITAL: return "Line 数字输入";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "电话";
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX: return "远程混音";
            case AudioDeviceInfo.TYPE_IP: return "网络音频";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI";
            case AudioDeviceInfo.TYPE_HDMI_ARC: return "HDMI ARC";
            case AudioDeviceInfo.TYPE_TV_TUNER: return "电视调谐器";
            case AudioDeviceInfo.TYPE_AUX_LINE: return "AUX";
            case AudioDeviceInfo.TYPE_DOCK: return "扩展坞";
            case AudioDeviceInfo.TYPE_HEARING_AID: return "助听器";
            case AudioDeviceInfo.TYPE_FM: return "FM";
            default: return "未知类型(" + type + ")";
        }
    }
}
