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

    /**
     * 退出登录时调用,清除引用,下次进入需重新登录设置。
     * 清引用前先 shutdown():把在途请求取消掉,否则它们的回调回来时会再取一次 get(),
     * 撞上下面的 IllegalStateException 崩在主线程(退出登录后恰好有个详情页请求在飞就很常见)。
     */
    public static void reset() {
        WmsRepository r = repo;
        repo = null;
        if (r != null) {
            try {
                r.shutdown();
            } catch (Exception ignored) { /* 清理失败不该挡住退出登录 */ }
        }
    }
}
