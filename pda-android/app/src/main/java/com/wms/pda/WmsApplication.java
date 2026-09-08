package com.wms.pda;

import android.app.Application;

import com.wms.pda.data.repo.SessionStore;
import com.wms.pda.data.repo.SettingsStore;

/**
 * 应用入口。初始化全局配置(服务器地址、登录态)。
 * 原型阶段只维护内存 + SharedPreferences,接入真实后端时把 ApiClient 注入即可。
 */
public class WmsApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SettingsStore.init(this);
        SessionStore.init(this);
    }
}
