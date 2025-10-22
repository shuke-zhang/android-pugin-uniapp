package com.example.shuke_microphone;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.lang.reflect.Method;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

public class AudioDeviceLister extends UniModule {

    private static final String TAG = "AudioDeviceLister";

    // 首选输入设备持久化（供正式录音使用）
    private static final String SP_NAME = "audio_route_prefs";
    private static final String SP_KEY_PREFERRED_INPUT_ID = "preferred_input_device_id";

    // 探针录音参数（通话友好，适配 A10）
    private static final int PROBE_SR    = 16000;
    private static final int PROBE_FMT   = AudioFormat.ENCODING_PCM_16BIT;
    private static final int PROBE_CH    = AudioFormat.CHANNEL_IN_MONO;
    private static final int PROBE_MS    = 250;   // 每次探针等待时长
    private static final int PROBE_RETRY = 4;     // 重试次数
    private static final int STEP_WAIT   = 120;   // 重试间隔

    private Context getCtx() {
        return mUniSDKInstance != null ? mUniSDKInstance.getContext() : null;
    }

    private AudioManager getAm() {
        Context ctx = getCtx();
        return ctx != null ? (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE) : null;
    }

    private SharedPreferences sp() {
        Context ctx = getCtx();
        return ctx != null ? ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE) : null;
    }

    private void savePreferredDeviceId(int id) {
        SharedPreferences s = sp();
        if (s != null) { s.edit().putInt(SP_KEY_PREFERRED_INPUT_ID, id).apply(); }
    }

    public static Integer getSavedPreferredDeviceId(Context ctx) {
        if (ctx == null) return null;
        SharedPreferences s = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        if (!s.contains(SP_KEY_PREFERRED_INPUT_ID)) return null;
        return s.getInt(SP_KEY_PREFERRED_INPUT_ID, -1);
    }

    // ========================= 设备枚举 =========================

    /***
     * 获取输入设备列表
     */
    @UniJSMethod(uiThread = true)
    public void getInputDevices(UniJSCallback callback) {
        JSONObject result = new JSONObject();
        JSONArray arr = new JSONArray();

        try {
            AudioManager am = getAm();
            if (am != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioDeviceInfo[] infos = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
                    for (AudioDeviceInfo info : infos) {
                        JSONObject o = new JSONObject();
                        int id = safeGetId(info);
                        int type = safeGetType(info);
                        o.put("id", id);
                        o.put("type", type);
                        o.put("typeName", mapType(type));
                        o.put("productName", safeGetName(info));
                        o.put("address", safeGetAddress(info));
                        o.put("isSource", true); // 只有输入才会被枚举到
                        arr.add(o);
                    }
                } else {
                    JSONObject o = new JSONObject();
                    o.put("typeName", "当前系统版本不支持设备枚举");
                    arr.add(o);
                }
            }
            result.put("ok", true);
            result.put("devices", arr);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("msg", e.toString());
        }

        if (callback != null) callback.invoke(result);
    }

    // ========================= 设备切换（真实验证） =========================

    /**
     * 目标：bluetooth / usb / wired / builtin
     * 规则：只有当“探针录音实测路由 == 目标设备”（优先 id 匹配，其次 type 匹配）才返回 ok=true
     */
    @SuppressLint("NewApi")
    @UniJSMethod(uiThread = true)
    public void setInputRoute(String route, UniJSCallback callback) {
        JSONObject res = new JSONObject();

        try {
            AudioManager am = getAm();
            if (am == null) throw new IllegalStateException("AudioManager is null");
            if (route == null) route = "";

            String key = route.trim().toLowerCase(); // bluetooth / usb / wired / builtin

            AudioDeviceInfo[] inputs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            }

            AudioDeviceInfo target = pickDeviceForKey(key, inputs);

            // 目标必须存在（蓝牙/USB/有线）
            boolean mustHaveDevice = "bluetooth".equals(key) || "usb".equals(key) || "wired".equals(key);
            if (mustHaveDevice && target == null) {
                String notFoundMsg = key.equals("bluetooth") ? "切换失败：未检测到蓝牙麦克风（请确保蓝牙已连接并支持通话）"
                        : key.equals("usb") ? "切换失败：未检测到 USB 外置麦克风"
                        : "切换失败：未检测到有线耳机麦克风";
                res.put("ok", false);
                res.put("method", "none");
                res.put("msg", notFoundMsg);
                res.put("devicesSnapshot", toSnapshot(inputs));
                callback.invoke(res);
                return;
            }

            boolean applied;
            String method = "";
            JSONObject actual = null;

            // Android 12+：优先使用 setCommunicationDevice，再做探针验证
            if (Build.VERSION.SDK_INT >= 31 && target != null) {
                try {
                    Method setCommDev = AudioManager.class.getMethod("setCommunicationDevice", AudioDeviceInfo.class);
                    Object r = setCommDev.invoke(am, target);
                    boolean invokeOk = !(r instanceof Boolean) || (Boolean) r;
                    if (invokeOk) method = "setCommunicationDevice()";
                } catch (Throwable ignore) {}
            }

            // 统一先进入通话模式（更容易命中麦克风路由）
            try { am.setMode(AudioManager.MODE_IN_COMMUNICATION); } catch (Throwable ignored) {}

            // 蓝牙打开 SCO，其它通道关闭 SCO/外放
            if ("bluetooth".equals(key)) {
                try {
                    am.startBluetoothSco();
                    am.setBluetoothScoOn(true);
                    SystemClock.sleep(200);
                } catch (Throwable ignored) {}
            } else {
                try {
                    am.stopBluetoothSco();
                    am.setBluetoothScoOn(false);
                    am.setSpeakerphoneOn(false);
                } catch (Throwable ignored) {}
            }

            // —— 探针录音 + setPreferredDevice + 验证 —— //
            VerifyResult vr;
            if ("builtin".equals(key)) {
                try {
                    am.setMode(AudioManager.MODE_NORMAL);
                    am.setBluetoothScoOn(false);
                    am.setSpeakerphoneOn(true);
                    SystemClock.sleep(120);
                } catch (Throwable ignored) {}
                vr = verifyRouteWithProbe(null); // 期望回到内置麦
            } else {
                vr = verifyRouteWithProbe(target);
            }

            applied = vr.matched;
            actual  = vr.actualJson;

            if (applied) {
                // 保存首选 ID，便于“正式录音”沿用硬路由
                if (target != null) savePreferredDeviceId(safeGetId(target)); else savePreferredDeviceId(-1);
            }

            String msg = applied
                    ? ("bluetooth".equals(key) ? "已切换至蓝牙麦克风（实测成功）"
                    : "usb".equals(key) ? "已切换至 USB 外置麦克风（实测成功）"
                    : "wired".equals(key) ? "已切换至有线耳机麦克风（实测成功）"
                    : "已切换至内置麦克风（实测成功）")
                    : "切换失败：实测路由与目标不一致";

            res.put("ok", applied);
            res.put("method", method.isEmpty() ? "probe-verify@A10" : method + "+probe-verify");
            res.put("msg", msg);
            res.put("devicesSnapshot", toSnapshot(inputs));
            if (target != null) {
                res.put("target", toJson(target));
                res.put("preferredDeviceId", safeGetId(target));
            }
            if (actual != null) {
                res.put("actualRouted", actual);
            }

            logSwitchDetail(key, target, inputs, res);

        } catch (Exception e) {
            res.put("ok", false);
            res.put("method", "exception");
            res.put("msg", "异常：" + e.getMessage());
        }

        callback.invoke(res);
    }

    // ========================= 探针录音验证 =========================

    private static class VerifyResult {
        boolean matched;
        JSONObject actualJson;
    }

    /**
     * 通过短暂的 AudioRecord：
     * - preferred != null 时先 setPreferredDevice(preferred)（硬路由）
     * - startRecording 后读取 getRoutedDevice()
     * - 仅在（id 相等）或（type 相等）时认为匹配成功
     * - builtin 情况：preferred==null，要求 routed.type == BUILTIN_MIC
     */
    @SuppressLint("MissingPermission")
    private VerifyResult verifyRouteWithProbe(AudioDeviceInfo preferred) {
        VerifyResult vr = new VerifyResult();
        vr.matched = false;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // M 以下没有 AudioDeviceInfo 能力，直接失败（不会走到这步，前面已做早退）
            return vr;
        }

        int minBuf = AudioRecord.getMinBufferSize(PROBE_SR, PROBE_CH, PROBE_FMT);
        if (minBuf <= 0) minBuf = PROBE_SR * 2;

        for (int attempt = 0; attempt < PROBE_RETRY; attempt++) {
            AudioRecord rec = null;
            try {
                rec = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        PROBE_SR,
                        PROBE_CH,
                        PROBE_FMT,
                        minBuf
                );

                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "探针录音未初始化，重试 attempt=" + attempt);
                    safeRelease(rec);
                    SystemClock.sleep(STEP_WAIT);
                    continue;
                }

                if (preferred != null) {
                    try {
                        boolean ok = rec.setPreferredDevice(preferred);
                        Log.i(TAG, "setPreferredDevice(" + safeGetId(preferred) + ") -> " + ok);
                    } catch (Throwable e) {
                        Log.w(TAG, "setPreferredDevice 异常: " + e.getMessage());
                    }
                }

                rec.startRecording();
                SystemClock.sleep(PROBE_MS);

                AudioDeviceInfo routed = rec.getRoutedDevice();
                JSONObject actual = (routed != null) ? toJson(routed) : null;

                boolean match;
                if (preferred == null) {
                    match = (routed != null && safeGetType(routed) == AudioDeviceInfo.TYPE_BUILTIN_MIC);
                } else {
                    match = (routed != null) &&
                            (safeGetId(routed) == safeGetId(preferred)
                                    || safeGetType(routed) == safeGetType(preferred));
                }

                String expectedStr = (preferred == null)
                        ? "BUILTIN"
                        : (safeGetId(preferred) + "/" + mapType(safeGetType(preferred)));
                String routedStr = (routed == null)
                        ? "null"
                        : (safeGetId(routed) + "/" + mapType(safeGetType(routed)));

                Log.i(TAG, "探针验证 attempt=" + attempt +
                        " | 期望=" + expectedStr +
                        " | 实测=" + routedStr +
                        " | matched=" + match);

                vr.matched = match;
                vr.actualJson = actual;

                try { rec.stop(); } catch (Throwable ignored) {}
                try { rec.release(); } catch (Throwable ignored) {}

                if (match) break;
            } catch (Throwable e) {
                Log.w(TAG, "探针录音异常 attempt=" + attempt + " : " + e.getMessage());
                safeRelease(rec);
            }

            SystemClock.sleep(STEP_WAIT);
        }

        return vr;
    }

    private void safeRelease(AudioRecord r) {
        try { if (r != null) { r.release(); } } catch (Throwable ignored) {}
    }

    // ========================= 设备匹配/序列化/日志 =========================

    @SuppressLint("NewApi")
    private AudioDeviceInfo pickDeviceForKey(String key, AudioDeviceInfo[] arr) {
        if (arr == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        for (AudioDeviceInfo info : arr) {
            try {
                int t = safeGetType(info);
                if ("bluetooth".equals(key) && t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return info;
                if ("usb".equals(key) && (t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_USB_HEADSET)) return info;
                if ("wired".equals(key) && t == AudioDeviceInfo.TYPE_WIRED_HEADSET) return info;
                if ("builtin".equals(key) && t == AudioDeviceInfo.TYPE_BUILTIN_MIC) return info;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String mapType(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "内置麦克风";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "蓝牙麦克风";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB外置麦克风";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "有线耳机麦克风";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "听筒麦克风";
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX: return "系统回环";
            default: return "其他设备(" + type + ")";
        }
    }

    // ---------- 安全访问（解决 minSdk 21 Lint 告警） ----------
    private static int safeGetId(AudioDeviceInfo d) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && d != null) {
            return d.getId();
        }
        return -1;
    }

    private static int safeGetType(AudioDeviceInfo d) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && d != null) {
            return d.getType();
        }
        return -1;
    }

    private static String safeGetName(AudioDeviceInfo d) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && d != null && d.getProductName() != null) {
            return d.getProductName().toString();
        }
        return "未知设备";
    }

    private static String safeGetAddress(AudioDeviceInfo d) {
        // getAddress 仅 API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d != null) {
            try { return d.getAddress(); } catch (Throwable ignored) {}
        }
        return "";
    }

    private static JSONObject toJson(AudioDeviceInfo d) {
        JSONObject o = new JSONObject();
        int id = safeGetId(d);
        int type = safeGetType(d);
        o.put("id", id);
        o.put("type", type);
        o.put("typeName", mapType(type));
        o.put("productName", safeGetName(d));
        o.put("address", safeGetAddress(d));
        return o;
    }

    private static String toSnapshot(AudioDeviceInfo[] arr) {
        if (arr == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            AudioDeviceInfo d = arr[i];
            int id = safeGetId(d);
            int type = safeGetType(d);
            sb.append("{id=").append(id)
                    .append(", type=").append(type).append("(").append(mapType(type)).append(")")
                    .append(", name=").append(safeGetName(d))
                    .append(", addr=").append(safeGetAddress(d))
                    .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private void logSwitchDetail(String key, AudioDeviceInfo target, AudioDeviceInfo[] inputs, JSONObject res) {
        Log.i(TAG, "====== 切换请求（真实验证） ======");
        Log.i(TAG, "目标路由: " + key);
        Log.i(TAG, "目标设备: " + (target == null ? "null" : toJson(target).toJSONString()));
        Log.i(TAG, "可用输入列表: " + toSnapshot(inputs));
        Log.i(TAG, "结果: " + res.toJSONString());
        Log.i(TAG, "================================");
    }
}
