package com.example.shuke_microphone;

import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.lang.reflect.Method;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

public class AudioDeviceLister extends UniModule {

    private static final String TAG = "AudioDeviceLister";

    private Context getCtx() {
        return mUniSDKInstance != null ? mUniSDKInstance.getContext() : null;
    }

    private AudioManager getAm() {
        Context ctx = getCtx();
        return ctx != null ? (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE) : null;
    }

    /***
     * 获取设备列表
     */
    @UniJSMethod(uiThread = true)
    public void getInputDevices(UniJSCallback callback) {
        JSONObject result = new JSONObject();
        JSONArray arr = new JSONArray();

        try {
            AudioManager am = getAm();
            if (am != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Android 6.0 以上使用新 API
                    for (AudioDeviceInfo info : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                        JSONObject o = new JSONObject();
                        o.put("id", info.getId());
                        o.put("type", info.getType());
                        o.put("typeName", mapType(info.getType()));
                        o.put("productName", info.getProductName() != null ? info.getProductName().toString() : "未知设备");
                        o.put("isSource", info.isSource());
                        arr.add(o);
                    }
                } else {
                    // Android 6.0 以下不支持 AudioDeviceInfo，只能返回一个空数组
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

    /***
     * 新增函数：设置音频输入通道
     * route 值支持 bluetooth / usb / wired / builtin
     */
    @android.annotation.SuppressLint("NewApi")
    @UniJSMethod(uiThread = true)
    public void setInputRoute(String route, UniJSCallback callback) {
        JSONObject res = new JSONObject();

        try {
            AudioManager am = getAm();
            if (am == null) throw new IllegalStateException("AudioManager is null");
            if (route == null) route = "";

            String key = route.trim().toLowerCase(); // bluetooth / usb / wired / builtin

            // 🔍 获取输入设备列表
            AudioDeviceInfo[] inputs = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            }

            // 匹配目标设备
            AudioDeviceInfo target = pickDeviceForKey(key, inputs);

            // ✅ 对于 bluetooth / usb / wired，必须存在设备才继续
            boolean mustHaveDevice = "bluetooth".equals(key) || "usb".equals(key) || "wired".equals(key);
            if (mustHaveDevice && target == null) {
                String notFoundMsg;
                switch (key) {
                    case "bluetooth":
                        notFoundMsg = "切换失败：未检测到蓝牙麦克风（请确保蓝牙已连接并支持通话）";
                        break;
                    case "usb":
                        notFoundMsg = "切换失败：未检测到 USB 外置麦克风";
                        break;
                    case "wired":
                        notFoundMsg = "切换失败：未检测到有线耳机麦克风";
                        break;
                    default:
                        notFoundMsg = "切换失败，未找到目标设备";
                        break;
                }
                res.put("ok", false);
                res.put("method", "none");
                res.put("msg", notFoundMsg);
                callback.invoke(res);
                return;
            }

            boolean applied = false;
            String method = "";

            // ✅ Android 12+ 使用官方 API 优先切换
            if (Build.VERSION.SDK_INT >= 31 && target != null) {
                try {
                    Method setCommDev = AudioManager.class.getMethod("setCommunicationDevice", AudioDeviceInfo.class);
                    Object r = setCommDev.invoke(am, target);
                    applied = !(r instanceof Boolean) || (Boolean) r;
                    method = "setCommunicationDevice()";
                } catch (Throwable t) {
                    // 忽略异常
                }
            }

            // ✅ 回退方案：仅在 target 存在时才进入通信模式
            if (!applied && target != null) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                switch (key) {
                    case "bluetooth":
                        try {
                            am.startBluetoothSco();
                            am.setBluetoothScoOn(true);
                            applied = true;
                            method = "SCO";
                        } catch (Throwable ignored) {}
                        break;

                    case "builtin":
                        try {
                            am.stopBluetoothSco();
                            am.setBluetoothScoOn(false);
                            am.setSpeakerphoneOn(true);
                            am.setMode(AudioManager.MODE_NORMAL); // ✅ 恢复普通模式
                            applied = true;
                            method = "builtin-fallback";
                        } catch (Throwable ignored) {}
                        break;

                    case "usb":
                    case "wired":
                        // Android 12 以下系统无法强制路由到 USB / 有线
                        applied = false;
                        method = "not-supported-pre31";
                        break;
                }
            }

            // ✅ 当目标设备不存在时（例如 USB 不存在），不修改音频模式
            if (target == null && "builtin".equals(key)) {
                try {
                    am.setMode(AudioManager.MODE_NORMAL);
                    am.setBluetoothScoOn(false);
                    am.setSpeakerphoneOn(true);
                    applied = true;
                    method = "restore-normal";
                } catch (Throwable ignored) {}
            }

            // ✅ 生成结果提示
            String msg;
            if (applied) {
                switch (key) {
                    case "bluetooth":
                        msg = "已切换至蓝牙麦克风";
                        break;
                    case "usb":
                        msg = "已切换至 USB 外置麦克风";
                        break;
                    case "wired":
                        msg = "已切换至有线耳机麦克风";
                        break;
                    case "builtin":
                        msg = "已切换至内置麦克风";
                        break;
                    default:
                        msg = "已切换至目标输入设备";
                        break;
                }
            } else {
                switch (key) {
                    case "usb":
                    case "wired":
                        msg = (Build.VERSION.SDK_INT >= 31)
                                ? "切换失败：未能设置到目标设备"
                                : "切换失败：当前系统版本不支持强制切换到该输入通道（建议 Android 12+）";
                        break;
                    case "builtin":
                        msg = "已恢复至默认麦克风模式";
                        applied = true; // ✅ 视为成功恢复
                        break;
                    default:
                        msg = "切换失败：未能设置到目标设备";
                        break;
                }
            }

            // ✅ 组装返回数据
            res.put("ok", applied);
            res.put("method", method);
            res.put("msg", msg);

            if (target != null) {
                JSONObject dev = new JSONObject();
                dev.put("id", target.getId());
                dev.put("type", target.getType());
                dev.put("name", String.valueOf(target.getProductName()));
                res.put("device", dev);
            }

        } catch (Exception e) {
            res.put("ok", false);
            res.put("method", "exception");
            res.put("msg", "异常：" + e.getMessage());
        }

        callback.invoke(res);
    }

    @android.annotation.SuppressLint("NewApi")
    private AudioDeviceInfo pickDeviceForKey(String key, AudioDeviceInfo[] arr) {
        if (arr == null) return null;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Android 6.0 以下系统不支持 AudioDeviceInfo，直接返回 null
            return null;
        }

        for (AudioDeviceInfo info : arr) {
            try {
                int t = info.getType();

                if ("bluetooth".equalsIgnoreCase(key)
                        && t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return info;
                }

                if ("usb".equalsIgnoreCase(key)
                        && (t == AudioDeviceInfo.TYPE_USB_DEVICE
                        || t == AudioDeviceInfo.TYPE_USB_HEADSET)) {
                    return info;
                }

                if ("wired".equalsIgnoreCase(key)
                        && t == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    return info;
                }

                if ("builtin".equalsIgnoreCase(key)
                        && t == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    return info;
                }
            } catch (Throwable ignored) {
                // 某些 ROM 会在 getType() 时报 SecurityException，这里捕获避免崩溃
            }
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
}
