# PDA 客户端设计 — SimpleWMS Android 扫码 App

> 项目目录:`pda-android/`
> 包名:`com.wms.pda` (applicationId 相同)
> 基线:`main` 分支当前 HEAD(P4 CameraX + P5b 入库 + P6a 后端 + P6b 出库盘点 全部合入)

## 1. 项目定位

| 项 | 值 |
|---|---|
| 目标设备 | Android 工业 PDA(优博讯 UROVO,iData 也可) |
| 编译 SDK | 34 |
| minSdk | 21(Android 5.0) |
| targetSdk | 34 |
| Java | 11(源码 + 字节码兼容) |
| 包结构 | `com.wms.pda.{net, scan, model, data, ui.{login, home, inbound, outbound, check, lookup}, util}` |
| 形态 | 单 Activity-per-screen,无 Fragment / Compose / Hilt / KMM |
| 联网后端 | `BuildConfig.API_BASE_URL`(默认 `http://10.0.2.2:8080` 模拟器映射) |
| 物理能力 | UHF RFID(SDK)+ 摄像头扫码(CameraX + ML Kit) |

## 2. 模块结构

```
pda-android/
├── build.gradle (根)
├── settings.gradle
├── gradle/wrapper/                # Gradle 9.1.0 wrapper
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   ├── libs/
│   │   └── DeviceAPI_ver20230301_release.aar  # UROVO RFID SDK
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/wms/pda/
│       │   ├── PdaApp.java                    # Application 空壳
│       │   ├── data/
│       │   │   └── SessionStore.java           # SharedPreferences:token + baseUrl
│       │   ├── model/                          # DTO 集合(Gson)
│       │   │   ├── ApiResponse.java
│       │   │   ├── LoginRequest.java
│       │   │   ├── LoginResponse.java
│       │   │   ├── ProductDto.java
│       │   │   ├── LocationDto.java
│       │   │   ├── InventoryItemDto.java
│       │   │   ├── InboundOrderDto.java      # 含 items + codes
│       │   │   ├── InboundOrderItemDto.java
│       │   │   ├── OutboundOrderDto.java
│       │   │   └── StockCheckDto.java
│       │   ├── net/
│       │   │   ├── ApiClient.java             # OkHttp + JWT + ApiResponse 解析
│       │   │   └── ApiException.java
│       │   ├── scan/
│       │   │   ├── ScanType.java               # BARCODE / RFID_EPC
│       │   │   ├── ScanResult.java
│       │   │   ├── ScannerService.java         # 接口
│       │   │   ├── ScannerCallback.java
│       │   │   ├── UhfScannerImpl.java        # UROVO RFID
│       │   │   ├── BarcodeScannerImpl.java    # CameraX + ML Kit
│       │   │   └── ScanManager.java            # 单例门面
│       │   ├── ui/
│       │   │   ├── login/LoginActivity.java
│       │   │   ├── home/HomeActivity.java
│       │   │   ├── inbound/InboundScanActivity.java
│       │   │   ├── outbound/OutboundScanActivity.java
│       │   │   ├── check/StockCheckActivity.java
│       │   │   └── lookup/LookupActivity.java
│       │   └── util/
│       │       ├── BeepUtil.java               # ToneGenerator 成功/失败蜂鸣
│       │       └── ToastUtil.java
│       └── res/
│           ├── layout/                        # 每个 Activity 一个 layout
│           ├── values/strings.xml
│           ├── values/colors.xml
│           ├── values/themes.xml
│           └── drawable/
└── local.properties (sdk.dir, gitignore)
```

## 3. 6 个 Activity 职责

| Activity | 入口 | 职责 | 关键交互 |
|---|---|---|---|
| `LoginActivity` | LAUNCHER | 登录表单 + 服务器地址配置 | 用户名/密码 + 服务器地址;`SessionStore` 持久化 |
| `HomeActivity` | Login → | 4 个入口 + RFID/扫码器状态 | `ScanManager.ensureReady` 主动探测;失败 Toast 真实原因 |
| `InboundScanActivity` | Home → 入库登记 | 选/建草稿单 → 选商品+数量+库位+备注 → 逐行 POST /items → 提交 → 显示 24 位码列表 + 打印/CSV | `setContentView` 在登记页 / 码打印页之间切换 |
| `OutboundScanActivity` | Home → 出库扫码 | 选/建出库单 → 全屏 PreviewView 实时扫码 → `POST /scan` → 列表从响应 items 重建 → 提交/取消 | onResume 绑摄像头;onPause/onDestroy 解绑 |
| `StockCheckActivity` | Home → 库存盘点 | 选/建盘点单 → 全屏PreviewView扫码 → `POST /scan` 累加 countedQty → 提交显示差异数 | scope 选 ALL/SKU/LOCATION |
| `LookupActivity` | Home → 扫码查询 | 通过 24 位码查 `InventoryItem` | (待 P2 完整实现) |

## 4. 扫码抽象层

### 4.1 接口

```java
public interface ScannerService {
    void init(Context ctx) throws Exception;
    void release();
    void startScan(ScanType type);
    void stopScan();
    boolean isReady();
    void setCallback(ScannerCallback cb);
}

public enum ScanType { BARCODE, RFID_EPC }

public final class ScanResult {
    public final String code;       // 扫到的内容
    public final ScanType type;
    public final String rssi;       // RFID 信号强度(光学为 null)
    public final long timestamp;
}

public interface ScannerCallback {
    void onScanResult(ScanResult result);
    void onError(int code, String msg);
    default void onTimeout() {}
}
```

### 4.2 两个实现

**`BarcodeScannerImpl`**(P4 提交,3460240):
- 基于 `CameraX 1.3.4` + `ML Kit barcode-scanning 17.2.0`(unbundled)
- 构造时 `BarcodeScanning.getClient(CODE_128/CODE_39/CODE_93/EAN_13/EAN_8/QR/UPC_A/UPC_E)`
- 单线程 `analysisExecutor`,`STRATEGY_KEEP_ONLY_LATEST`
- 暴露**额外**方法 `attachCamera(LifecycleOwner, PreviewView)` / `detachCamera()`(违反 `ScannerService` 接口契约,见 `prd-input-pda.md` 第 5 节脚注)
- 抓到一码自动 `scanning.set(false)` 并回调 `onScanResult`;Activity 处理完需 `resumeScanning()`(500ms 延迟)重启

**`UhfScannerImpl`**(P4 fix2 提交,3232b3d):
- 基于 UROVO `URFIDLibrary-v2.4.1226.aar`
- 单例 `RFIDSDKManager.getInstance()`(`init(InitListener)` 异步回调 `onStatus(boolean ready)`)
- 5 秒 `scanThread` 超时回调 `onTimeout`
- `startInventory(0)` 单步盘点,抓到 1 个 EPC 立即 `stopInventory()`
- `power(false) → disConnect() → release()` 链路释放

### 4.3 `ScanManager`(单例门面)

```java
public class ScanManager {
    private static volatile ScanManager INSTANCE;
    private final Context appCtx;
    private final UhfScannerImpl uhf;
    private final BarcodeScannerImpl barcode;

    public static ScanManager get(Context ctx);                       // DCL 单例
    public void ensureReady(ScanType type);                        // HomeActivity 用,主动 init
    public void startScan(ScanType type, ScannerCallback cb);       // UI 层只调这里
    public void stopScan();
    public void release();
    public boolean isUhfReady();
    public boolean isBarcodeReady();
    public void attachBarcodeCamera(LifecycleOwner, PreviewView);   // 摄像头专用
    public void detachBarcodeCamera();
}
```

**关键点**:
- UI 层永远只调 `ScanManager.get(ctx).startScan(ScanType, cb)`,不知道底层是哪个厂
- 跨 Activity 共享单例(进出 Activity 不重 init)
- `ensureReady` 主动探测,**避免第一次扫码才暴露硬件问题**

## 5. 网络层

### 5.1 `ApiClient`(OkHttp + JWT 拦截器)

```java
public class ApiClient {
    public ApiClient();                       // 内部读 SessionStore 的 baseUrl
    public void refreshBaseUrl();             // 用户改了服务器地址后调用
    public void setToken(String token);

    public <T> void get(String path, Class<T> dataType, Callback<T> cb);
    public <T> void get(String path, Type dataType, Callback<T> cb);   // 泛型集合用
    public <T> void post(String path, Object body, Class<T> dataType, Callback<T> cb);
    public <T> void put(String path, Object body, Class<T> dataType, Callback<T> cb);
    public <T> void delete(String path, Class<T> dataType, Callback<T> cb);
}
```

构造流程:
1. `baseUrl = trimTrailingSlash(SessionStore.baseUrl ?: BuildConfig.API_BASE_URL)`
2. `OkHttpClient` + 拦截器自动加 `Authorization: Bearer <token>`
3. connect 10s / read 30s

请求流程(全部异步,回调切回主线程):
1. 非 2xx → 先尝试把 body 当 `ApiResponse` 解析,取到 `message` 就用它,否则退回 `HTTP <code>: <body>` → `onError(-2, msg)`
2. 2xx → 解析外层 `ApiResponse`;解析失败 → `onError(-3, ...)`
3. `ar.code != 0` → `onError(ar.code, ar.message)`(业务码原样透传)
4. `gson.toJsonTree(data)` 再二次解析成 `dataType` → `onSuccess(data)`

### 5.2 `Callback` 与错误码约定

```java
public interface Callback<T> {
    void onSuccess(T data);
    void onError(int code, String msg);
}
```

| code | 含义 |
|---|---|
| 后端业务码(`400/404/409/422` 等) | HTTP 2xx,但 body 里 `code != 0` |
| `-1` | 网络不可达 |
| `-2` | 非 2xx(已尽量取 body 里的 `message`) |
| `-3` | 响应读取 / JSON 解析失败 |

⚠️ **当前没有 401 特殊处理**:令牌过期不会清 session、也不会跳登录页,只是和其它非 2xx 一样回调一条错误消息(此时 body 的 `message` 是"未登录"/"token 无效或已过期")。若要做"过期自动登出",需要在 `ApiClient` 里对 401 单独处理并清 `SessionStore` + 跳登录 —— **尚未实现,文档此前描述的 `ApiException` 类并不存在**。

## 6. DTO 列表(`com.wms.pda.model`)

所有 DTO 用 public 字段(Gson 直读直写),**无 getter/setter**。

| 类 | 关键字段 | 说明 |
|---|---|---|
| `ApiResponse<T>` | `code, message, data` | 镜像后端壳 |
| `LoginRequest/Response` | username, password, token, role, mustChangePw | |
| `ProductDto` | id, sku, name, category, unit, location, quantity, unitPrice, lowStock, remark | 数量类型 = Integer 包装类,Gson 安全 |
| `LocationDto` | id, code, name, remark | |
| `InventoryItemDto` | id, code, productId, sku, productName, locationCode, quantity, remaining, status, inboundOrderId, outboundOrderId, createdBy, createdAt, updatedAt | 时间 ISO 字符串 |
| `InboundOrderDto` | id, orderNo, externalNo, remark, operator, totalQuantity, itemCount, status, createdAt, `items:List<InboundOrderItemDto>` | |
| `InboundOrderItemDto` | id, orderId, productId, sku, productName, supplier, quantity, `unitPrice:String`, beforeQty, afterQty, location, rowRemark, createdAt, **`codes:List<String>`** | commit 后由后端填;PDA 端可读 |
| `OutboundOrderDto` | id, orderNo, customer, externalNo, remark, operator, totalQuantity, itemCount, status, createdAt, `items:List<OutboundOrderItemDto>` | 含内部类 |
| `StockCheckDto` | id, checkNo, scope, scopeKeyword, remark, status, totalCount, countedCount, diffCount, items | 内部类 Item |

**BigDecimal 兼容**:服务端用 Jackson 序列化 `BigDecimal` 字段时默认输出字符串,PDA 端声明 `String` 即可。若端对端都期望数字可改 `BigDecimal`(需测试)。

## 7. 本地存储 `SessionStore`

`SharedPreferences` 文件 `wms_pda_session`(`MODE_PRIVATE`)字段:

| Key | 类型 | 用途 |
|---|---|---|
| `auth_token` | String | JWT,`ApiClient` 拦截器读 |
| `username` | String | HomeActivity 顶栏显示 |
| `role` | String | 预留(未消费) |
| `base_url` | String | 覆盖 `BuildConfig.API_BASE_URL`(登录页服务器地址输入框) |

**方法**:
- `clearAuth()`:仅清 token/username/role,保留 baseUrl(下次直接登录)
- `clear()`:全清(重置 App 用)

## 8. 关键依赖(`app/build.gradle`)

| 库 | 版本 | 用途 |
|---|---|---|
| `androidx.appcompat` | 1.6.1 | AppCompatActivity |
| `com.google.android.material` | 1.10.0 | MaterialButton / TextInputEditText / AlertDialog |
| `androidx.constraintlayout` | 2.1.4 | |
| `androidx.recyclerview` | 1.3.2 | 列表 |
| `com.squareup.okhttp3` | 3.12.0 | HTTP |
| `com.google.code.gson` | 2.10.1 | JSON |
| `androidx.camera:camera-{core,camera2,lifecycle,view}` | 1.3.4 | 摄像头预览 + 图像分析 |
| `com.google.mlkit:barcode-scanning` | 17.2.0 | 实时条码识别(unbundled) |
| `files('libs/URFIDLibrary-v2.4.1226.aar')` | — | UROVO RFID |

测试:`junit 4.13.2` / `mockito 5.0.0` / `mockwebserver 3.12.0` / `robolectric 4.10.3`

## 9. ProGuard 规则(`app/proguard-rules.pro`)

```proguard
# UROVO 优博讯 RFID
-keep class com.ubx.** { *; }
-keep class com.ubx.usdk.** { *; }
-dontwarn com.ubx.usdk.**
-dontwarn vendor.**
-dontwarn com.mediatek.**
-dontwarn android.hardware.**
# UROVO ROM 自带隐藏类
-dontwarn android.device.**
-dontwarn com.ubx.rfid.**
-dontwarn com.ubx.utils.**

# DTO(Gson 反射)
-keep class com.wms.pda.model.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
```

## 10. 扫码抽象层数据流(顺序图)

```
Activity (OutboundScanActivity)              ScanManager (单例)         BarcodeScannerImpl
    │                                              │                          │
    │  attachBarcodeCamera(this, previewView)       │                          │
    │ ───────────────────────────────────────────> │  attachCamera(...)         │
    │                                              │ ──────────────────────>  │
    │                                              │                          │  ProcessCameraProvider.bindToLifecycle
    │  startScan(ScanType.BARCODE, this)            │                          │
    │ ───────────────────────────────────────────> │  startScan                │
    │                                              │ ──────────────────────>  │  BarcodeScanning.process (后台分析)
    │                                              │                          │
    │                              [onScanResult]  │                          │
    │ <─────────────────────────────────────────── │ <──────────────────────  │
    │  handleScannedCode(code)                      │                          │
    │  ─ api.post /scan                              │                          │
    │  ─ refresh UI + beep                          │                          │
    │  ─ resumeScanning()                           │                          │
    │ ───────────────────────────────────────────> │  startScan                │  ← 500ms 后重启
```

## 11. Manifest 配置

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.CAMERA" />

<uses-feature android:name="android.hardware.camera" android:required="false" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />

<application android:usesCleartextTraffic="true">
    <activity android:name=".ui.login.LoginActivity" android:exported="true">  <!-- LAUNCHER -->
    <activity android:name=".ui.home.HomeActivity" android:exported="false" />
    <activity android:name=".ui.inbound.InboundScanActivity" android:exported="false" />
    <activity android:name=".ui.outbound.OutboundScanActivity" android:exported="false" />
    <activity android:name=".ui.check.StockCheckActivity" android:exported="false" />
    <activity android:name=".ui.lookup.LookupActivity" android:exported="false" />
</application>
```

## 12. 编译与发布

```bash
cd pda-android
./gradlew assembleDebug       # debug APK
./gradlew assembleRelease     # 签名 + minify
adb install -r app/build/outputs/apk/release/app-release.apk
```

签名:`pda-release.jks`(仓库内,`storePassword=change-me-now`,`keyAlias=pda-release`)
minify:release 开启 `minifyEnabled` + `shrinkResources`
调试版使用 Android 默认 debug keystore

## 13. 当前遗留问题(详细见 `prd-input-pda.md` §9 + review 报告)

### Blocker(影响功能)
- B1:LoginActivity 实际**未调用 /api/auth/login**,代码注释说"原型阶段"
- B2:`BarcodeScannerImpl.attachCamera` 失败只 onError 不重试
- B3:`ApiClient` 401 自动 `clear()` 但**不广播回登录页**
- B4:`InboundScanActivity.LineAdapter` 行删除按钮无后端接口
- B5:出库选单 dialog `setCancelable(false)`,网络失败时用户无法退出
- B6:盘点选单 dialog 布局 ID 可能错

### Medium
- M1:`ensureReady` 失败只 Toast 不阻止进入
- M2:低端机扫描密集码偶发丢帧
- M3:`UhfScannerImpl.init` 异步未等待,乐观 ready 标志
- M4:OkHttp 3.12 无 `Call.cancel()` 调用
- M5:`showOrderPicker` 手工 Map→DTO 转换脆弱
- M6:摄像头资源释放路径不完整
- M7:`SessionStore.role` UI 未消费

## 14. 后续方向

- 库存 iData SDK 适配(目前仅 UROVO,多厂家需要 Impl 切换)
- 离线缓存(扫码本地暂存,网络恢复后批量提交)
- 摄像头扫码防抖(同码 500ms 内不重复回调)
- 扫码权限运行时申请(目前只 Manifest 声明)
- 完整单元测试(扫码抽象层 + 网络层 + 业务流)
