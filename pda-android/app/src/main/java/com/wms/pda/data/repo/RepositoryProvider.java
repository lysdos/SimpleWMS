package com.wms.pda.data.repo;

/**
 * 全局仓库单例。必须通过 {@link #setRepository(WmsRepository)} 显式设置(登录成功后调)。
 * 未设置时调用 get() 会抛 IllegalStateException。
 */
public final class RepositoryProvider {

    private static WmsRepository repo;

    private RepositoryProvider() {}

    public static WmsRepository get() {
        if (repo == null) {
            throw new IllegalStateException("Repository not initialized. Call setRepository() after login.");
        }
        return repo;
    }

    public static void setRepository(WmsRepository r) {
        repo = r;
    }

    /** 退出登录时调用,清除引用,下次进入需重新登录设置。 */
    public static void reset() {
        repo = null;
    }
}
