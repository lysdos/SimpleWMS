# PDA Android 手持机扫码模块 — 设计文档

**日期**:2026-09-07
**作者**:Mavis / Claude
**状态**:设计待审

## 背景与目标

SimpleWMS 已实现 PC Web 端完整出入库/盘点流程,使用 24 位唯一编码实现"一物一码"。
现需为 Android 工业手持机(PDA)添加扫码 App,支持:

- **光学/激光条码扫描**(PDA 物理扫码头)
- **UHF RFID EPC 读取**(PDA 内置 RFID 模组)
- 入库扫码、出库扫码、库存盘点、扫码查询 4 个核心场景

**关键约束**:
- EPC 与条码完全一致(同一字符串),后端 `inventory_item.code` 已 UNIQUE,无需新增字段
- 不修改后端核心业务逻辑,仅补 4 个查询接口
- 不修改现有 PC 端前端代码
- 默认在线,不实现离线同步

## 目标用户

- 仓库管理员(手持 PDA 在货架间走动扫码)
- 设备:Android 工业 PDA(类 uhf-uart-demo 那种,带光学校 + UHF RFID 模组)

## 非目标(YAGNI)

- 离线草稿 + 重传
- RFID 标签写入/锁/灭活(只读)
- 多语言、推送通知、摄像头扫码
- 批量盘点自动遍历
- Android 以外平台

---

## 1. 架构总览

```
+-----------------+  HTTP/JSON  +-------------------+
|  Android PDA    |  ────────>  |  SimpleWMS 后端   |
|  (com.wms.pda)  |  (JWT)      |  (已有,不动)      |
|                 |             |                   |
|  ┌───────────┐  |             |  现有 API         |
|  │  Activities│ |             |  /api/auth/login  |
|  │ /Fragments │ |             |  /api/products    |
|  ├───────────┤  |             |  /api/inbound     |
|  │  扫码 SDK  │ |             |  /api/outbound    |
│  │  (UHF+光) │ |             |  /api/stock-check │
|  ├───────────┤  |             |  /api/inventory   |
|  │  OkHttp   │ |             |       /items      |
|  │  (HTTP)   | |             |                   |
|  └───────────┘  |             +-------------------+
+-----------------+
```

**新项目位置**:`E:\Code\SimpleWMS\pda-android\`(独立 Android Studio 项目,根目录新建)

**项目结构**:

```
pda-android/
├── build.gradle                    # 项目级
├── settings.gradle
├── gradle/                         # gradle wrapper
├── app/
│   ├── build.gradle                # 应用级
│   ├── proguard-rules.pro
│   ├── libs/                       # 厂商 SDK jar (uhf-sdk.jar, scan-sdk.jar)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/wms/pda/
│       │   ├── PdaApp.java        # Application 入口
│       │   ├── net/
│       │   │   ├── ApiClient.java # OkHttp + JWT 拦截器
│       │   │   └── ApiException.java
│       │   ├── scan/
│       │   │   ├── ScanType.java
│       │   │   ├── ScanResult.java
│       │   │   ├── ScannerService.java
│       │   │   ├── ScannerCallback.java
│       │   │   ├── UhfScannerImpl.java       # UHF RFID 实现
│       │   │   ├── BarcodeScannerImpl.java   # 光学条码实现
│       │   │   └── ScanManager.java          # 单例门面
│       │   ├── data/
│       │   │   └── SessionStore.java         # SharedPreferences 存 JWT
│       │   ├── model/
│       │   │   ├── ApiResponse.java
│       │   │   ├── LoginRequest.java
│       │   │   ├── LoginResponse.java
│       │   │   ├── ProductDto.java
│       │   │   ├── LocationDto.java
│       │   │   ├── InboundRequest.java
│       │   │   ├── OutboundRequest.java
│       │   │   ├── StockCheckDto.java
│       │   │   └── InventoryItemDto.java
│       │   ├── ui/
│       │   │   ├── login/LoginActivity.java
│       │   │   ├── home/HomeActivity.java
│       │   │   ├── inbound/InboundScanActivity.java
│       │   │   ├── outbound/OutboundScanActivity.java
│       │   │   ├── check/StockCheckActivity.java
│       │   │   └── lookup/LookupActivity.java
│       │   └── util/
│       │       ├── BeepUtil.java
│       │       └── ToastUtil.java
│       └── res/
│           ├── layout/             # XML 布局
│           ├── values/             # colors/strings/themes
│           └── drawable/           # 图标
└── app/src/test/java/com/wms/pda/  # 单元测试
```

---

## 2. 扫码/RFID 抽象层

### 2.1 接口

**ScanType**(枚举):
- `BARCODE` — 光学/激光条码
- `RFID_EPC` — UHF RFID(EPC 字符串 = 24 位 code)

**ScanResult**:
```java
public class ScanResult {
    public final String code;       // 扫到的内容
    public final ScanType type;
    public final String rssi;       // RFID 信号强度(条码场景 null)
    public final long timestamp;
}
```

**ScannerService**:
```java
public interface ScannerService {
    void init(Context ctx) throws Exception;   // 打开设备
    void release();                              // 关闭设备
    void startScan(ScanType type);               // 启动单次扫描
    void stopScan();                             // 停止
    boolean isReady();                           // 设备是否就绪
    void setCallback(ScannerCallback cb);        // 设置结果回调
}
interface ScannerCallback {
    void onScanResult(ScanResult result);
    void onError(int code, String msg);
    void onTimeout();                            // 5 秒默认
}
```

### 2.2 UHF RFID 实现

参考 `uhf-uart-demo` 的 `com.example.uhf` 包(基于 `UhfInfo` 和 `Reader` 类):

```java
public class UhfScannerImpl implements ScannerService {
    private UhfInfo uhfInfo;
    private Reader mReader;
    private ScannerCallback cb;

    @Override
    public void init(Context ctx) {
        uhfInfo = new UhfInfo();
        mReader = uhfInfo.getReader();   // 打开串口
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.RFID_EPC) return;
        new Thread(() -> {
            try {
                List<HashMap<String, String>> tags = mReader.inventoryRealTime();
                for (HashMap<String, String> tag : tags) {
                    String epc = tag.get("EPC");
                    String rssi = tag.get("RSSI");
                    cb.onScanResult(new ScanResult(
                        epc, ScanType.RFID_EPC, rssi, System.currentTimeMillis()));
                }
                if (tags.isEmpty()) cb.onTimeout();
            } catch (Exception e) {
                cb.onError(-1, e.getMessage());
            }
        }).start();
    }
}
```

### 2.3 光学条码实现

```java
public class BarcodeScannerImpl implements ScannerService {
    private BarCodeManager barCodeManager;
    private ScannerCallback cb;
    private BroadcastReceiver barcodeReceiver;

    @Override
    public void init(Context ctx) {
        IntentFilter filter = new IntentFilter("com.example.uhf.action.BARCODE");
        barcodeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String code = intent.getStringExtra("BARCODE");
                cb.onScanResult(new ScanResult(
                    code, ScanType.BARCODE, null, System.currentTimeMillis()));
            }
        };
        ctx.registerReceiver(barcodeReceiver, filter);
        barCodeManager.open(ctx);
    }
}
```

### 2.4 ScanManager 单例门面

```java
public class ScanManager {
    private static ScanManager INSTANCE;
    private final UhfScannerImpl uhf;
    private final BarcodeScannerImpl barcode;

    public static ScanManager get(Context ctx) {
        if (INSTANCE == null) {
            INSTANCE = new ScanManager(ctx.getApplicationContext());
        }
        return INSTANCE;
    }

    public void startScan(ScanType type, ScannerCallback cb) {
        ScannerService svc = (type == ScanType.RFID_EPC) ? uhf : barcode;
        if (!svc.isReady()) {
            try { svc.init(currentCtx); }
            catch (Exception e) {
                cb.onError(-100, "扫码设备未就绪: " + e.getMessage());
                return;
            }
        }
        svc.setCallback(cb);
        svc.startScan(type);
    }
}
```

**关键点**:
- UI 层永远只调 `ScanManager.get(this).startScan(...)`,不知道底层是哪个厂
- EPC 文本直接当 24 位 code 用(后端 UNIQUE 索引已存在)
- UHF 默认 5 秒超时,回调 `onTimeout`
- 换品牌只改 `UhfScannerImpl` / `BarcodeScannerImpl` 两个文件

---

## 3. 4 个核心 Activity

### 3.1 全局导航

```
LoginActivity
    │
    ▼
HomeActivity  (4 个大按钮:入库/出库/盘点/查询)
    │
    ├──> InboundScanActivity     (入库扫码)
    ├──> OutboundScanActivity    (出库扫码)
    ├──> StockCheckActivity      (库存盘点)
    └──> LookupActivity          (扫码查询)
```

### 3.2 LoginActivity

- 用户名/密码输入 → `POST /api/auth/login`
- JWT 存 `SessionStore`(SharedPreferences,key=`auth_token`)
- 成功 → `HomeActivity` + `finish()`
- 失败 → Toast + 字段高亮

### 3.3 HomeActivity

- 4 个大按钮,布局 2x2
- 顶栏:用户名 + 退出 + RFID 状态图标(就绪/未就绪)
- 进入时 `ScanManager.get(this)` 触发 UHF 初始化(失败不阻塞,可继续用光学校)

### 3.4 InboundScanActivity(入库扫码)

**流程**:
1. 顶部固定扫码框(`ScanManager.startScan` 触发) + 手动输入框(备选)
2. 扫到 code → `GET /api/inventory/items/by-code/{code}`
3. 命中 → 弹"已存在此码,不能重复入库"
4. 未命中 → `GET /api/products/by-code/{code}` 解析 SKU
5. 累计扫描列表(同 SKU 多次扫累加数量)
6. 「提交入库」→ `POST /api/inbound` (operator 来自 JWT username)

**布局**:
```
┌──────────────────────────┐
│  📥 入库扫码        [×]  │
├──────────────────────────┤
│ ┌──────────────────────┐ │
│ │  扫描 / 输入 24 位码 │ │
│ └──────────────────────┘ │
│ [开始扫码]  [手动输入]    │
├──────────────────────────┤
│ 已扫 3 件:                │
│ ┌────────────────────┐   │
│ │ ABC001  商品A ×2   │   │
│ │ ABC002  商品B ×1   │   │
│ └────────────────────┘   │
├──────────────────────────┤
│ 库位: [A-01 ▼]           │
│ 备注: [          ]       │
│      [提交入库]           │
└──────────────────────────┘
```

### 3.5 OutboundScanActivity(出库扫码)

**流程**:
1. 扫码 → `GET /api/inventory/items/by-code/{code}`
2. 命中 + `status=IN_STOCK` → 加入出库列表
3. 命中 + 已出库 → Toast 红字"该码已出库"
4. 「提交出库」→ `POST /api/outbound`(批量)
5. 提交后该 `inventory_item.status` 自动变 OUT(后端现有逻辑)

**附加**:支持「按 SKU 出 N 件」(FIFO 自动选单元,不需扫码)

### 3.6 StockCheckActivity(库存盘点)

**流程**:
1. 选择盘点范围:全部 / 按 SKU / 按库位 → `POST /api/stock-check` 创建盘点单
2. 后端自动生成 `stock_check_item` 列表(系统数量)
3. 逐个扫实物 → `PUT /api/stock-check/{id}/items/{itemId}` 提交实盘数
4. 全部扫完 → `POST /api/stock-check/{id}/commit` 生成差异
5. 页面显示进度 X/Y + 差异数

**布局**:
```
┌──────────────────────────┐
│  🔍 库存盘点      [×]    │
├──────────────────────────┤
│ 盘点单: SC-20260907-001  │
│ 范围: [库位 A-01 ▼]      │
│ 进度: 12/45 (差 3)       │
├──────────────────────────┤
│ 待扫:                     │
│ ┌────────────────────┐   │
│ │ ABC001  商品A  5件 │   │
│ │  实盘: [  ]  确认   │   │
│ ├────────────────────┤   │
│ │ ABC002  商品B  2件 │   │
│ │  实盘: [ 5]  ✓    │   │
│ └────────────────────┘   │
├──────────────────────────┤
│   [提交盘点]              │
└──────────────────────────┘
```

### 3.7 LookupActivity(扫码查询)

**流程**:
1. 扫码 → `GET /api/inventory/items/by-code/{code}`
2. 显示该 inventory_item 完整信息:
   - 24 位 code / EPC
   - SKU / 商品名 / 库位 / 数量
   - 状态(IN_STOCK / OUT / SCRAPPED / TRANSFERRED)
   - 入库单号 / 入库时间
   - 出库单号 / 出库时间(若已出库)
3. 附加按钮:「查看入库单」「查看出库单」(Intent 打开 web 详情页)

---

## 4. API 客户端

```java
public class ApiClient {
    private static final String BASE_URL = BuildConfig.API_BASE_URL;
    private final OkHttpClient http;
    private final Gson gson = new Gson();

    public ApiClient(Context ctx) {
        SessionStore session = new SessionStore(ctx);
        http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(chain -> {
                Request req = chain.request();
                if (session.getToken() != null) {
                    req = req.newBuilder()
                        .addHeader("Authorization", "Bearer " + session.getToken())
                        .build();
                }
                return chain.proceed(req);
            })
            .build();
    }

    public <T> T get(String path, Class<T> type) { ... }
    public <T> T post(String path, Object body, Class<T> type) { ... }
    public <T> T put(String path, Object body, Class<T> type) { ... }
}
```

**异常**:
- 网络失败 → `ApiException(ERR_NETWORK, "网络不可达")`
- HTTP 4xx/5xx → `ApiException(ERR_HTTP, status + ": " + body)`
- JSON 解析失败 → `ApiException(ERR_PARSE, "数据格式错误")`

**后端 ApiResponse 包装**:
```java
public class ApiResponse<T> {
    public int code;       // 0 = 成功
    public String msg;
    public T data;
}
```
- `code == 0` → 返回 `data`
- `code == 401` → 清 JWT + 广播跳登录
- 其他 → 抛 `ApiException(code, msg)`

---

## 5. 后端需要补的接口(只新增,不破坏现有)

为支持 PDA 场景,后端需要 4 个查询接口,都是简单 GET:

| 方法 | 路径 | 用途 | 返回 |
|---|---|---|---|
| GET | `/api/inventory/items/by-code/{code}` | 通过 24 位码查 inventory_item | `InventoryItem` |
| GET | `/api/inventory/items/by-epc/{epc}` | EPC 查 inventory_item(同 by-code) | `InventoryItem` |
| GET | `/api/products/by-sku/{sku}` | 通过 SKU 查商品 | `Product` |
| GET | `/api/products/by-code/{code}` | 解析 code 找对应 SKU | `Product` |

**实现**:
- 在 `InventoryItemController` 加 `byCode` / `byEpc` 方法
- 在 `ProductController` 加 `bySku` / `byCode` 方法
- `InventoryItemDao` 加 `findByCode(String code)` 方法
- 全部命中返回对象,未命中返回 404

**现有 API 全部复用,不修改**:
- `POST /api/inbound` — 入库
- `POST /api/outbound` — 出库
- `POST /api/stock-check` — 创建盘点单
- `PUT /api/stock-check/{id}/items/{itemId}` — 提交实盘数
- `POST /api/stock-check/{id}/commit` — 提交盘点
- `GET /api/auth/login` — 登录

---

## 6. 错误处理

### 6.1 分层

| 层 | 错误类型 | 处理 |
|---|---|---|
| 网络 | 连接超时/DNS/拒绝 | `ApiException(ERR_NETWORK)` + Toast"网络不可达" |
| HTTP | 4xx/5xx | `ApiException(ERR_HTTP)` + 显示 status + body |
| 业务 | 400 库存不足 | Toast"库存不足" |
| 业务 | 401 Token 过期 | 清 JWT + 跳 LoginActivity |
| 业务 | 404 条码未找到 | Toast"该码未在系统中" |
| 业务 | 409 重复 | Toast"已存在此码" |
| 扫码 | init 失败 | "扫码设备未就绪" + 重试按钮 |
| 扫码 | onTimeout | "未扫到标签,请重试" |
| 扫码 | onError | 显示具体原因 |

### 6.2 用户反馈

- 成功 → 短促"哔" + Toast "已扫 ABC001"
- 失败 → 较长"哔哔" + Toast 红字

### 6.3 异常场景

| 场景 | 处理 |
|---|---|
| 启动时网络不通 | 可进登录页,登录按钮提示"网络不可达" |
| 扫码中途断网 | Toast 提示,暂存本地(本期不重传) |
| JWT 过期 | 自动清 token + 跳登录页 |
| UHF 模组断开 | 顶栏红字"RFID 设备断开" + "重新连接"按钮 |
| 重复扫同一码 | 入库:弹"已存在";出库:弹"已出库" |
| 扫到非 24 位 | 后端 404,UI 提示"无效条码: XXX" |

---

## 7. 测试策略

### 7.1 Android 单元测试(`app/src/test/java/`)

- `ApiClientTest`:MockWebServer 模拟后端,验证 JWT 注入、错误码解析、超时
- `ScanManagerTest`:Mock 两个 ScannerService,验证 startScan 路由
- `InboundScanViewModelTest`:模拟连续扫码,验证累加逻辑、提交 payload

### 7.2 后端单元测试(`src/test/java/`)

只测**新增**的 4 个查询接口:
- `InventoryItemControllerTest`:`byCode` / `byEpc` 命中/未命中
- `ProductControllerTest`:`bySku` / `byCode` 命中/未命中
- 用 Spring `@WebMvcTest` + MockBean,跑 `./mvnw test`

### 7.3 手动测试清单

- [ ] 登录(admin / change-me-now)
- [ ] 扫码:扫已入库的码 → 查询显示正常
- [ ] 入库:扫码 3 次 → 提交 → 库存增加
- [ ] 出库:扫码 → 提交 → 状态变 OUT
- [ ] 盘点:创建 → 扫实物 → 提交 → 看到差异
- [ ] 断网:登录页提示,扫到码时提示
- [ ] RFID 模组断开:顶栏红字 + 重连按钮可用
- [ ] 横竖屏切换:不丢失已扫列表

---

## 8. 构建与部署

### 8.1 Gradle 配置

`app/build.gradle`:
```gradle
android {
    compileSdk 34
    defaultConfig {
        applicationId "com.wms.pda"
        minSdk 21
        targetSdk 34
        versionCode 1
        versionName "1.0.0"
        buildConfigField "String", "API_BASE_URL", "\"http://10.0.2.2:8080\""
    }
    signingConfigs {
        release {
            storeFile file("../uhf-uart-demo/uhf-serial_release.jks")
            storePassword "123456"
            keyAlias "uhf-serial-key"
            keyPassword "123456"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro'
        }
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.10.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'com.squareup.okhttp3:okhttp:3.12.0'
    implementation 'com.google.code.gson:gson:2.10.1'
    implementation files('libs/uhf-sdk.jar')
    implementation files('libs/scan-sdk.jar')
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.mockito:mockito-core:5.0.0'
    testImplementation 'com.squareup.okhttp3:mockwebserver:3.12.0'
}
```

### 8.2 ProGuard

`proguard-rules.pro`:
```
-keep class com.example.uhf.** { *; }        # 厂商 SDK
-keep class com.wms.pda.model.** { *; }      # DTO
-keepattributes Signature                      # Gson 反射
-keepattributes *Annotation*
```

### 8.3 构建命令

```bash
# 调试 APK
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk

# 发布 APK
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

### 8.4 部署到 PDA

- USB 连接 PDA,`adb install app-release.apk`
- 覆盖安装:`adb install -r app-release.apk`
- 签名复用 `uhf-uart-demo/uhf-serial_release.jks`,可与 demo 共存

### 8.5 API 地址

- 模拟器:`http://10.0.2.2:8080`(emulator → host)
- 真机:`http://192.168.1.100:8080`(PDA 与后端同网段)
- 生产:`https://wms.example.com`
- 提供「设置」页面允许运行时改 API 地址

### 8.6 Docker 集成

- 后端 / 前端 / MySQL 都不变
- PDA App 是独立 Android 项目,不进 docker-compose
- 在 `README.md` 加"PDA App 构建"段

---

## 9. 风险与缓解

| 风险 | 概率 | 缓解 |
|---|---|---|
| 厂商 SDK API 与 demo 不一致 | 高 | 封装 ScannerService 接口,差异只在 Impl 内调整 |
| 24 位 code 含字母,UHF 写不进去 | 中 | 本期只读不写,无影响 |
| PDA 性能差,UI 卡顿 | 中 | ViewBinding + RecyclerView 简单列表,避免 Compose |
| PDA 屏幕 4 寸,布局挤 | 中 | 单列、字号 16sp+、按钮 48dp+ |
| PDA 没装厂商 SDK(纯 Android 设备) | 低 | 启动检测 UhfInfo 类,无则只走光学校 |
| 后端补 4 个接口影响现有契约 | 极低 | 全部 GET 新路径,不修改任何现有方法 |

---

## 10. 范围控制(YAGNI)

**本期不做**:
- 离线草稿 + 重传(只做"网络不可达"提示)
- 多语言(只做中文)
- 推送通知
- RFID 标签写入/锁/灭活(只读)
- 摄像头扫码(走 PDA 物理扫码头)
- 批量盘点自动遍历
- WebSocket 实时同步

**本期做**:
- 4 个核心 Activity(登录/主页/入库/出库/盘点/查询 = 6 个,含 Login/Home)
- 扫码抽象层 + UHF/Barcode 两个实现
- 4 个后端查询接口
- 完整错误处理 + 单元测试
- 签名 APK + USB 部署

---

## 11. 实施步骤概要

1. **后端**:加 4 个查询接口 + 单元测试(1-2 小时)
2. **Android 项目脚手架**:Gradle + Manifest + Application + 主题(2-3 小时)
3. **扫码抽象层**:ScannerService 接口 + ScanManager 单例(2 小时)
4. **扫码实现**:`UhfScannerImpl` + `BarcodeScannerImpl`(基于 uhf-uart-demo 改写,4-6 小时)
5. **API 客户端**:`ApiClient` + `SessionStore` + DTO(2 小时)
6. **6 个 Activity 布局 + 逻辑**(8-12 小时)
7. **单元测试**(3-4 小时)
8. **构建 + 签名 + 部署到 PDA 测试**(2 小时)

**总预估**:25-35 小时(分阶段交付)
