package com.example.shuke_recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.List;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * UniApp 原生桥接模块：
 * - startRecord(params, callback)
 * - stopRecord(callback)
 * - requestPermission(callback)
 * - hasPermission(callback)
 * - checkPermission(callback)
 *
 * 回调事件：
 * - {event:'start'}
 * - {event:'process', volume, duration, sampleRate, buffers:[{...firstFrame...}]}
 * - {event:'stop'}
 * - {event:'error', message}
 * - {event:'route', data:{label,typeName,deviceType,deviceId,productName,address,sampleRate,channels,format}}
 */
public class RecorderModule extends UniModule {

    private static final String TAG = "RecorderModule";
    private static final int REQ_CODE_RECORD = 2001;

    private RecorderManager recorderManager;
    /** 持久回调，使用 invokeAndKeepAlive 推流事件 */
    private UniJSCallback mCallback;

    private Context getCtx() {
        return mUniSDKInstance != null ? mUniSDKInstance.getContext() : null;
    }

    private Activity getActivity() {
        Context ctx = getCtx();
        if (ctx instanceof Activity) return (Activity) ctx;
        return null;
    }

    // --------------------- JS API ---------------------

    /**
     * 开始录音
     * params: { sampleRate: number=16000, enableAEC: boolean, enableNS: boolean, enableAGC: boolean }
     */
    @UniJSMethod(uiThread = true)
    public void startRecord(JSONObject params, UniJSCallback callback) {
        this.mCallback = callback;

        Context ctx = getCtx();
        if (ctx == null) {
            emitError("Context 为 null");
            return;
        }

        // 权限检查
        if (!hasMicPermission(ctx)) {
            emitError("未获得录音权限");
            return;
        }

        int sampleRate = 16000;
        boolean enableAEC = true, enableNS = true, enableAGC = true;
        try {
            if (params != null) {
                if (params.containsKey("sampleRate")) sampleRate = params.getIntValue("sampleRate");
                if (params.containsKey("enableAEC"))  enableAEC  = params.getBooleanValue("enableAEC");
                if (params.containsKey("enableNS"))   enableNS   = params.getBooleanValue("enableNS");
                if (params.containsKey("enableAGC"))  enableAGC  = params.getBooleanValue("enableAGC");
            }
        } catch (Throwable ignore) {}

        if (recorderManager == null) {
            recorderManager = new RecorderManager(ctx);
        }

        // 配置监听器（包含 onRoute）
        recorderManager.setListener(new RecorderManager.Listener() {
            @Override
            public void onStart() {
                JSONObject ev = new JSONObject();
                ev.put("event", "start");
                safeEmit(ev);
            }

            @Override
            public void onProcess(List<int[]> buffers, int volume, long durationMs, int sampleRateCb) {
                JSONObject ev = new JSONObject();
                ev.put("event", "process");
                ev.put("volume", volume);
                ev.put("duration", durationMs);
                ev.put("sampleRate", sampleRateCb);

                // 可选：把第一帧发出去（注意体积）
                if (buffers != null && !buffers.isEmpty()) {
                    int[] first = buffers.get(0);
                    JSONObject b0 = new JSONObject();
                    for (int i = 0; i < first.length; i++) {
                        b0.put(String.valueOf(i), first[i]);
                    }
                    JSONArray arr = new JSONArray();
                    arr.add(b0);
                    ev.put("buffers", arr);
                }
                safeEmit(ev);
            }

            @Override
            public void onStop() {
                JSONObject ev = new JSONObject();
                ev.put("event", "stop");
                safeEmit(ev);
            }

            @Override
            public void onError(String message) {
                JSONObject ev = new JSONObject();
                ev.put("event", "error");
                ev.put("message", message);
                safeEmit(ev);
            }

            /** 🔴 新增：录音通道信息（开始成功 & 路由变更时都会触发） */
            @Override
            public void onRoute(RecorderManager.RouteInfo info) {
                try {
                    JSONObject ev = new JSONObject();
                    ev.put("event", "route");

                    JSONObject data = new JSONObject();
                    data.put("label", info.label);
                    data.put("typeName", info.typeName);
                    data.put("deviceType", info.deviceType);
                    data.put("deviceId", info.deviceId);
                    data.put("productName", info.productName);
                    data.put("address", info.address);
                    data.put("sampleRate", info.sampleRate);
                    data.put("channels", info.channels);
                    data.put("format", info.format);

                    ev.put("data", data);
                    safeEmit(ev);
                } catch (Throwable t) {
                    Log.w(TAG, "emit route failed: " + t.getMessage());
                }
            }
        });

        recorderManager.setEffectOptions(enableAEC, enableNS, enableAGC);
        try {
            recorderManager.start("voice", sampleRate);
        } catch (Throwable t) {
            emitError("start 失败: " + t.getMessage());
        }
    }

    /** 停止录音 */
    @UniJSMethod(uiThread = true)
    public void stopRecord(UniJSCallback cb) {
        if (recorderManager != null) {
            try {
                recorderManager.stop();
            } catch (Throwable ignore) {}
        }
        if (cb != null) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            cb.invoke(r);
        }
    }

    /** 拉起系统权限弹窗 */
    @UniJSMethod(uiThread = true)
    public void requestPermission(UniJSCallback cb) {
        Activity act = getActivity();
        JSONObject r = new JSONObject();
        if (act == null) {
            r.put("ok", false);
            r.put("msg", "Activity 为 null");
            if (cb != null) cb.invoke(r);
            return;
        }
        try {
            ActivityCompat.requestPermissions(act, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_CODE_RECORD);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermissions error: " + t.getMessage());
        }
        // 直接返回当前状态（最终结果请在前端轮询或 resume 时再查）
        boolean granted = hasMicPermission(act);
        r.put("ok", true);
        r.put("granted", granted);
        if (cb != null) cb.invoke(r);
    }

    /** 返回是否已授权（与 checkPermission 等价） */
    @UniJSMethod(uiThread = true)
    public void hasPermission(UniJSCallback cb) {
        JSONObject r = new JSONObject();
        boolean granted = hasMicPermission(getCtx());
        r.put("ok", true);
        r.put("granted", granted);
        if (cb != null) cb.invoke(r);
    }

    /** 返回是否已授权（别名） */
    @UniJSMethod(uiThread = true)
    public void checkPermission(UniJSCallback cb) {
        hasPermission(cb);
    }

    // --------------------- 辅助方法 ---------------------

    private boolean hasMicPermission(Context ctx) {
        if (ctx == null) return false;
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void emitError(String msg) {
        JSONObject ev = new JSONObject();
        ev.put("event", "error");
        ev.put("message", msg);
        safeEmit(ev);
    }

    /** 统一使用 invokeAndKeepAlive，保证回调可持续推送 */
    private void safeEmit(JSONObject ev) {
        try {
            if (mCallback != null) mCallback.invokeAndKeepAlive(ev);
        } catch (Throwable t) {
            Log.w(TAG, "emit callback failed: " + t.getMessage());
        }
    }
}
