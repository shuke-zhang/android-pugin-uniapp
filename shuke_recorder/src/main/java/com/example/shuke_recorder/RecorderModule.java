package com.example.shuke_recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.List;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;

/**
 * 🎤 UniApp 原生录音插件模块
 * 支持：
 * - 权限请求/检测
 * - 录音（AEC/NS/AGC）
 * - 路由变更回调
 * - 统一 JS 回调事件流
 */
public class RecorderModule extends UniModule {

    private static final String TAG = "RecorderModule";
    private static final int REQ_CODE_RECORD = 2001;

    private RecorderManager recorderManager;
    private UniJSCallback mCallback;
    private UniJSCallback pendingCb; // 权限请求回调

    private Context getCtx() {
        return mUniSDKInstance != null ? mUniSDKInstance.getContext() : null;
    }

    private Activity getActivity() {
        Context ctx = getCtx();
        if (ctx instanceof Activity) return (Activity) ctx;
        return null;
    }

    // ==================== 录音相关 ====================

    @UniJSMethod(uiThread = true)
    public void startRecord(JSONObject params, UniJSCallback callback) {
        this.mCallback = callback;
        Context ctx = getCtx();
        if (ctx == null) {
            emitError("Context 为 null");
            return;
        }

        if (!hasMicPermission(ctx)) {
            emitError("未获得录音权限");
            return;
        }

        int sampleRate = 16000;
        boolean enableAEC = true, enableNS = true, enableAGC = true;
        try {
            if (params != null) {
                if (params.containsKey("sampleRate")) sampleRate = params.getIntValue("sampleRate");
                if (params.containsKey("enableAEC")) enableAEC = params.getBooleanValue("enableAEC");
                if (params.containsKey("enableNS")) enableNS = params.getBooleanValue("enableNS");
                if (params.containsKey("enableAGC")) enableAGC = params.getBooleanValue("enableAGC");
            }
        } catch (Throwable ignore) {}

        if (recorderManager == null) recorderManager = new RecorderManager(ctx);
        recorderManager.setEffectOptions(enableAEC, enableNS, enableAGC);

        recorderManager.setListener(new RecorderManager.Listener() {
            @Override
            public void onStart() {
                emitEvent("start", null);
            }

            @Override
            public void onProcess(List<int[]> buffers, int volume, long durationMs, int sampleRateCb) {
                JSONObject ev = new JSONObject();
                ev.put("volume", volume);
                ev.put("duration", durationMs);
                ev.put("sampleRate", sampleRateCb);

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

                emitEvent("process", ev);
            }

            @Override
            public void onStop() {
                emitEvent("stop", null);
            }

            @Override
            public void onError(String message) {
                JSONObject e = new JSONObject();
                e.put("message", message);
                emitEvent("error", e);
            }

            @Override
            public void onRoute(RecorderManager.RouteInfo info) {
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

                JSONObject ev = new JSONObject();
                ev.put("data", data);
                emitEvent("route", ev);
            }
        });

        try {
            recorderManager.start("voice", sampleRate);
        } catch (Throwable t) {
            emitError("start 失败: " + t.getMessage());
        }
    }

    @UniJSMethod(uiThread = true)
    public void stopRecord(UniJSCallback cb) {
        if (recorderManager != null) {
            try {
                recorderManager.stop();
            } catch (Throwable ignored) {}
        }
        if (cb != null) {
            JSONObject r = new JSONObject();
            r.put("ok", true);
            cb.invoke(r);
        }
    }

    // ==================== 权限处理 ====================

    @UniJSMethod(uiThread = true)
    public void requestPermission(UniJSCallback cb) {
        Activity act = getActivity();
        JSONObject r = new JSONObject();
        if (act == null) {
            r.put("ok", false);
            r.put("msg", "Activity 为 null");
            cb.invoke(r);
            return;
        }

        if (hasMicPermission(act)) {
            r.put("ok", true);
            r.put("granted", true);
            cb.invoke(r);
            return;
        }

        pendingCb = cb;
        try {
            ActivityCompat.requestPermissions(
                    act,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_CODE_RECORD
            );
        } catch (Throwable t) {
            r.put("ok", false);
            r.put("msg", t.getMessage());
            cb.invoke(r);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQ_CODE_RECORD && pendingCb != null) {
            JSONObject r = new JSONObject();
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            r.put("ok", true);
            r.put("granted", granted);
            pendingCb.invoke(r);
            pendingCb = null;
            Log.i(TAG, "🎯 权限回调结果: " + granted);
        }
    }

    @UniJSMethod(uiThread = true)
    public void hasPermission(UniJSCallback cb) {
        JSONObject r = new JSONObject();
        boolean granted = hasMicPermission(getCtx());
        r.put("ok", true);
        r.put("granted", granted);
        cb.invoke(r);
    }

    @UniJSMethod(uiThread = true)
    public void checkPermission(UniJSCallback cb) {
        hasPermission(cb);
    }

    private boolean hasMicPermission(Context ctx) {
        if (ctx == null) return false;
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ==================== 辅助方法 ====================

    private void emitEvent(String type, JSONObject data) {
        JSONObject ev = new JSONObject();
        ev.put("event", type);
        if (data != null) ev.putAll(data);
        if (mCallback != null) {
            mCallback.invokeAndKeepAlive(ev);
        }
    }

    private void emitError(String msg) {
        JSONObject ev = new JSONObject();
        ev.put("event", "error");
        ev.put("message", msg);
        if (mCallback != null) {
            mCallback.invokeAndKeepAlive(ev);
        }
    }


    /**
     * ==========================================
     * 🎵 文件保存与删除 (兼容 Android 5 - 14)
     * ==========================================
     */
    @UniJSMethod(uiThread = false)
    public void uniSaveLocalFile(String name, String base64Data, UniJSCallback cb) {
        JSONObject result = new JSONObject();
        try {
            if (name == null || name.trim().isEmpty()) {
                result.put("ok", false);
                result.put("msg", "文件名不能为空");
                if (cb != null) cb.invoke(result);
                return;
            }

            Context ctx = getCtx();
            if (ctx == null) {
                result.put("ok", false);
                result.put("msg", "Context 为 null");
                if (cb != null) cb.invoke(result);
                return;
            }

            // ✅ 获取安全的可写目录（App 私有文件夹）
            java.io.File dir = ctx.getExternalFilesDir("recorder");
            if (dir == null) dir = ctx.getFilesDir(); // Android 5 兜底
            if (!dir.exists()) dir.mkdirs();

            // ✅ Base64 解码后写入文件
            byte[] data = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT);
            java.io.File file = new java.io.File(dir, name);
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(data);
            fos.flush();
            fos.close();

            String absPath = file.getAbsolutePath();
            Log.i(TAG, "✅ 文件已保存到: " + absPath);

            result.put("ok", true);
            result.put("path", absPath);
            result.put("msg", "文件保存成功");
        } catch (Throwable e) {
            Log.e(TAG, "❌ 文件保存失败: " + e.getMessage());
            result.put("ok", false);
            result.put("msg", e.getMessage());
        }

        if (cb != null) cb.invoke(result);
    }

    /**
     * 删除应用私有目录下的文件
     */
    @UniJSMethod(uiThread = false)
    public void uniRemoveLocalFile(String path, UniJSCallback cb) {
        JSONObject result = new JSONObject();
        try {
            if (path == null || path.trim().isEmpty()) {
                result.put("ok", false);
                result.put("msg", "路径不能为空");
                if (cb != null) cb.invoke(result);
                return;
            }

            Context ctx = getCtx();
            if (ctx == null) {
                result.put("ok", false);
                result.put("msg", "Context 为 null");
                if (cb != null) cb.invoke(result);
                return;
            }

            java.io.File file = new java.io.File(path);

            // ✅ 防止删除外部目录文件（安全限制）
            String safeRoot = ctx.getExternalFilesDir(null).getAbsolutePath();
            if (!file.getAbsolutePath().startsWith(safeRoot)) {
                result.put("ok", false);
                result.put("msg", "拒绝删除非应用私有目录文件");
                Log.w(TAG, "⚠️ 拒绝删除路径: " + path);
                if (cb != null) cb.invoke(result);
                return;
            }

            if (file.exists()) {
                boolean deleted = file.delete();
                result.put("ok", deleted);
                result.put("msg", deleted ? "文件已删除" : "删除失败");
                Log.i(TAG, deleted ? "🗑️ 删除成功: " + path : "⚠️ 删除失败: " + path);
            } else {
                result.put("ok", false);
                result.put("msg", "文件不存在");
                Log.w(TAG, "⚠️ 文件不存在: " + path);
            }
        } catch (Throwable e) {
            result.put("ok", false);
            result.put("msg", e.getMessage());
            Log.e(TAG, "❌ 删除异常: " + e.getMessage());
        }

        if (cb != null) cb.invoke(result);
    }

}
