package com.example.shuke_recorder;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.List;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * 🔌 录音模块 - UniApp 插件入口
 * 前端使用：
 * const plugin = uni.requireNativePlugin('plugin_shuke')
 */
public class RecorderModule extends UniModule {
    private static final String TAG = "RecorderModule";
    private RecorderManager recorderManager;

    /** 请求录音权限 */
    @UniJSMethod(uiThread = true)
    public void requestPermission(UniJSCallback callback) {
        Context ctx = mUniSDKInstance.getContext();
        Activity act = (Activity) mUniSDKInstance.getContext();

        if (recorderManager == null) recorderManager = new RecorderManager(ctx);

        if (!recorderManager.hasPermission()) {
            recorderManager.requestPermission(act);
            if (callback != null) callback.invoke("正在请求录音权限...");
        } else {
            if (callback != null) callback.invoke("权限已授予");
        }
    }

    /** 开始录音 */
    @UniJSMethod(uiThread = true)
    public void startRecord(JSONObject params, UniJSCallback callback) {
        Context ctx = mUniSDKInstance.getContext();
        if (recorderManager == null) recorderManager = new RecorderManager(ctx);

        String type = params.getString("type");
        int sampleRate = params.getIntValue("sampleRate");

        if (!recorderManager.hasPermission()) {
            Activity act = (Activity) mUniSDKInstance.getContext();
            recorderManager.requestPermission(act);
            if (callback != null)
                callback.invoke("未授权录音，请允许权限后再试");
            return;
        }

        recorderManager.setListener(new RecorderManager.Listener() {
            @Override
            public void onProcess(List<int[]> buffers, double powerLevel, long durationMs, int sampleRate) {
                JSONObject payload = new JSONObject();
                JSONArray bufArr = new JSONArray();
                for (int[] arr : buffers) {
                    JSONObject obj = new JSONObject();
                    for (int i = 0; i < arr.length; i++) {
                        obj.put(String.valueOf(i), arr[i]);
                    }
                    bufArr.add(obj);
                }
                payload.put("buffers", bufArr);
                payload.put("powerLevel", powerLevel);
                payload.put("duration", durationMs);
                payload.put("sampleRate", sampleRate);
                payload.put("type", type);

                if (callback != null)
                    callback.invokeAndKeepAlive(payload);
            }

            @Override
            public void onError(String message) {
                if (callback != null) {
                    JSONObject err = new JSONObject();
                    err.put("error", message);
                    callback.invoke(err);
                }
            }

            @Override
            public void onStart() {
                Log.i(TAG, "录音已开始");
            }

            @Override
            public void onStop() {
                Log.i(TAG, "录音已停止");
            }
        });

        recorderManager.start(type, sampleRate);
    }

    /** 停止录音 */
    @UniJSMethod(uiThread = true)
    public void stopRecord(UniJSCallback callback) {
        if (recorderManager != null) {
            recorderManager.stop();
            if (callback != null) callback.invoke("录音已停止");
        } else if (callback != null) {
            callback.invoke("录音未开始");
        }
    }
}
