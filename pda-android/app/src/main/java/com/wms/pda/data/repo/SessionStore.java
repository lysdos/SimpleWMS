package com.wms.pda.data.repo;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

import java.io.File;

/**
 * 登录态存储:用户名、JWT token、角色。
 *
 * **token 落盘时加密**(2026-09-28 起用 EncryptedSharedPreferences,密钥在 Android Keystore 里)。
 * 以前是明文 SharedPreferences:token 有效期 24h,拿到它等于拿到这个人全部权限;
 * 设备被 root / 被 adb backup 拉走一份 xml 就泄了。
 *
 * 注意它与旧版**文件格式不兼容**(明文 xml vs 加密文件),所以读到旧文件会失败 ——
 * 见 init() 里的降级处理:删档重建,代价只是重新登录一次。
 * (这里只加密会话;SettingsStore 里的服务器地址不是秘密,保持明文以免升级时把用户
 *  配好的地址清掉。)
 */
public final class SessionStore {

    private static final String FILE = "wms_session";
    /**
     * Keystore 不可用时的降级文件,**故意用另一个文件名**。
     *
     * 不能沿用 FILE:那样下次启动时 {@link #deleteLegacyPlaintextFile} 会把它当成
     * "旧版遗留的明文档"删掉 —— 于是每条启动都登出、而 token 又在两次启动之间明文躺着,
     * 既没保住可用性也没保住加密,还悄无声息。换个文件后,降级档不受迁移逻辑管辖。
     */
    private static final String FALLBACK_FILE = "wms_session_fallback";
    private static final String KEY_USER = "username";
    private static final String KEY_ROLE = "role";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_LOGGED = "logged_in";

    private static final String TAG = "SessionStore";

    /** 加密存储必然写入的 keyset 键名;旧版明文文件里没有它 —— 用它区分"旧明文档" */
    private static final String KEYSET_MARKER = "__androidx_security_crypto_encrypted_prefs_key_keyset__";

    private static SharedPreferences prefs;

    private SessionStore() {}

    public static void init(Context ctx) {
        Context app = ctx.getApplicationContext();
        prefs = openEncrypted(app);
    }

    private static SharedPreferences openEncrypted(Context app) {
        // 先把旧版的**明文**会话文件删掉,再开加密存储。
        //
        // 为什么必须显式删、不能靠 catch 兜底:旧版用的是同名的普通 SharedPreferences,
        // 而 EncryptedSharedPreferences.create() 遇到这种文件**不会抛异常** —— 它照常打开,
        // 于是旧的明文 username / token 原样留在 xml 里,只有升级后新写的键才是密文。
        // (真机实测:升级后文件里同时存在 `token -> eyJ...` 明文和一批加密条目;
        //  那条明文 token 是一个仍然可用的凭据,等于加密白做了。)
        deleteLegacyPlaintextFile(app);
        try {
            return createEncrypted(app);
        } catch (Exception e) {
            // 走到这里通常是 Keystore 不可用(极罕见)。删档再试一次,再不行退回明文,
            // 保证 App 至少能起来(可用性优先于这一项加固)。
            Log.w(TAG, "加密会话存储打不开,删档重试: " + e.getMessage());
            deleteLegacyFile(app);
            try {
                return createEncrypted(app);
            } catch (Exception retryFailed) {
                // 降级用独立文件名(见 FALLBACK_FILE 的说明),并**记一条日志** ——
                // 以前这里静默,外人看不出 token 其实是明文存的。
                Log.w(TAG, "Keystore 不可用,会话降级为明文存储(" + FALLBACK_FILE + ")",
                        retryFailed);
                return app.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE);
            }
        }
    }

    private static SharedPreferences createEncrypted(Context app) throws Exception {
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        return EncryptedSharedPreferences.create(
                FILE, masterKeyAlias, app,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /**
     * 旧版明文会话文件 → 删掉(代价是升级后要重新登录一次)。
     *
     * 判据不能只看"有没有 keyset":真机上遇到的正是**混合**文件 —— 升级后
     * EncryptedSharedPreferences 已经往同一个文件里写了 keyset 和几个加密条目,
     * 而旧版留下的明文 `token`/`username` 原封不动躺着。所以只要出现任何
     * **明文遗留键名**,就说明这个文件里有未经加密的会话数据,整档删掉重来。
     * (加密存储写出的条目,键名是 SIV 密文,绝不会是这些字面名。)
     */
    private static void deleteLegacyPlaintextFile(Context app) {
        try {
            File f = prefsFile(app);
            if (!f.exists()) return;
            String content = readHead(f, 64 * 1024);
            boolean hasKeysets = content.contains(KEYSET_MARKER);
            boolean hasPlaintextLegacyKey = content.contains("name=\"" + KEY_TOKEN + "\"")
                    || content.contains("name=\"" + KEY_USER + "\"")
                    || content.contains("name=\"" + KEY_ROLE + "\"")
                    || content.contains("name=\"" + KEY_LOGGED + "\"");
            if (!hasKeysets || hasPlaintextLegacyKey) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) { /* 读不到/删不掉都不该挡住启动 */ }
    }

    private static void deleteLegacyFile(Context app) {
        try {
            File f = prefsFile(app);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) { }
    }

    private static File prefsFile(Context app) {
        return new File(app.getFilesDir().getParentFile(), "shared_prefs/" + FILE + ".xml");
    }

    private static String readHead(File f, int maxBytes) throws java.io.IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[maxBytes];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public static boolean isLoggedIn() {
        ensure();
        return prefs.getBoolean(KEY_LOGGED, false);
    }

    public static void login(String username, String token, String role) {
        ensure();
        prefs.edit()
                .putString(KEY_USER, username)
                .putString(KEY_TOKEN, token == null ? "" : token)
                .putString(KEY_ROLE, role == null ? "" : role)
                .putBoolean(KEY_LOGGED, true)
                .apply();
    }

    public static void logout() {
        ensure();
        prefs.edit()
                .remove(KEY_USER)
                .remove(KEY_TOKEN)
                .remove(KEY_ROLE)
                .putBoolean(KEY_LOGGED, false)
                .apply();
    }

    /** 当前登录账号。异常态(有登录标记但没存下账号)返回 null,由调用方给占位符 */
    public static String currentUser() {
        ensure();
        return prefs.getString(KEY_USER, null);
    }

    public static String token() {
        ensure();
        return prefs.getString(KEY_TOKEN, null);
    }

    public static String role() {
        ensure();
        return prefs.getString(KEY_ROLE, "OPERATOR");
    }

    private static void ensure() {
        if (prefs == null) {
            throw new IllegalStateException("SessionStore not initialized, call init() in Application");
        }
    }
}