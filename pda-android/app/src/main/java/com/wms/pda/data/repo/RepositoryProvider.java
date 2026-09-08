package com.wms.pda.data.repo;

import com.wms.pda.data.mock.MockWmsRepository;

/**
 * 全局仓库单例。默认 Mock,可通过 {@link #setRepository(WmsRepository)} 切换为 RemoteWmsRepository。
 */
public final class RepositoryProvider {

    private static WmsRepository repo = MockWmsRepository.get();

    private RepositoryProvider() {}

    public static WmsRepository get() {
        return repo;
    }

    /** 切换实现(登录成功后由 LoginActivity 切到 RemoteWmsRepository)。 */
    public static void setRepository(WmsRepository r) {
        repo = r;
    }

    /** 回退到 Mock(退出登录时调用,避免下次打开页面用 stale token 拉接口失败)。 */
    public static void resetToMock() {
        repo = MockWmsRepository.get();
    }
}