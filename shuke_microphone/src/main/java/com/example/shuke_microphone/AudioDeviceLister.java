package com.example.shuke_microphone;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * 🎤 MicDeviceModule
 * 用于列出 Android 设备上所有可用的音频输入通道
 * 包括：内置麦克风、有线耳机、蓝牙、USB 等
 */
public class MicDeviceModule extends UniModule {

    private static final String TAG = "MicDeviceModule";

    /** ✅ 列出所有音频输入设备并返回 JSON 数组 */
    @UniJSMethod(uiThread = false)
    public void listInputDevices(UniJSCallback callback) {
        Context ctx = mUniSDKInstance.getContext();
        JSONArray result = new JSONArray();

        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);

            for (AudioDeviceInfo dev : inputs) {
                JSONObject obj = new JSONObject();
                obj.put("id", dev.getId());
                obj.put("productName", dev.getProductName().toString());
                obj.put("type", dev.getType());
                obj.put("typeName", getTypeName(dev.getType()));
                obj.put("address", dev.getAddress());
                result.add(obj);

                Log.i(TAG, "🎧 输入设备: " + dev.getProductName() + " | " + getTypeName(dev.getType()));
            }
        } catch (Exception e) {
            Log.e(TAG, "listInputDevices error", e);
        }

        if (callback != null) callback.invoke(result);
    }

    /** 🔍 将系统类型码转为可读名称 */
    private String getTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "内置麦克风";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "有线耳机麦克风";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB 麦克风";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB 耳机麦克风";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "蓝牙麦克风";
            case AudioDeviceInfo.TYPE_HDMI: return "HDMI 输入";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "电话输入";
            default: return "未知类型(" + type + ")";
        }
    }
}
