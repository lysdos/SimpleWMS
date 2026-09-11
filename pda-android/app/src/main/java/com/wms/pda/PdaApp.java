package com.wms.pda;

import android.app.Application;

import com.wms.pda.data.api.ApiClient;
import com.wms.pda.data.remote.RemoteWmsRepository;
import com.wms.pda.data.repo.RepositoryProvider;
import com.wms.pda.data.repo.SessionStore;
import com.wms.pda.data.repo.SettingsStore;
import com.wms.pda.util.BeepUtil;

/**
 * Application 入口 - 全局初始化。
 * - 初始化 SessionStore / SettingsStore
 * - 如果 session 有 token,启动即切到 RemoteWmsRepository 并恢复 token
 */
public class PdaApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        SessionStore.init(this);
        SettingsStore.init(this);
        BeepUtil.init(this);

        String token = SessionStore.token();
        if (token != null && !token.isEmpty()) {
            ApiClient api = new ApiClient();
            api.setToken(token);
            RepositoryProvider.setRepository(new RemoteWmsRepository(api));
        }
    }
}