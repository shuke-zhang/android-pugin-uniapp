package com.example.plugin_shuke;

import com.alibaba.fastjson.JSONObject;

import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;
import io.dcloud.feature.uniapp.annotation.UniJSMethod;


public class test extends UniModule {

    /**
     * 向前端暴露的方法：传入一个名字，返回“你好！xxx”
     *
     * @param name     前端传入的字符串参数（如：舒克）
     * @param callback 用于向前端回调结果
     */
    @UniJSMethod(uiThread = true)
    public void sayHello(String name, UniJSCallback callback) {
        if (callback != null) {
            // 构造返回的数据
            JSONObject data = new JSONObject();
            data.put("reply", "你好！" + name);

            // 通过回调返回给前端
            callback.invoke(data);
        }
    }
}

