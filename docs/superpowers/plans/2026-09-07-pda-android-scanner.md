# PDA Android 手持机扫码模块 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Android 工业 PDA 手持机添加扫码 App,支持光学条码 + UHF RFID EPC 扫码,实现入库/出库/盘点/查询 4 大业务场景,与 SimpleWMS 后端通过现有 REST API 集成。

**Architecture:** 独立 Android Studio 项目 `pda-android/`,Android 原生 (Java) + OkHttp + Gson,扫码/RFID 走 `ScannerService` 抽象层(2 个实现,基于 uhf-uart-demo 的厂商 SDK),后端只补 4 个查询接口,业务 API 全部复用现有路径。

**Tech Stack:**
- Java 11 (PDA 老机型兼容,实际工具链是 JDK 17+)
- Android Gradle Plugin 8.1.x
- minSdk 21 / targetSdk 34 / compileSdk 34
- OkHttp 3.12.0 + Gson 2.10.1
- AndroidX AppCompat 1.6.1 + Material 1.10.0
- 厂商 SDK: UHF RFID 串口版 jar (包名 `com.example.uhf`,参考 `uhf-uart-demo/`)
- 后端: Spring Boot 3.3.5(不动业务代码)

## Global Constraints

- **后端业务代码 0 改动**:只允许在 `InventoryItemController` / `ProductController` 末尾追加 GET 方法,不动 Service / Dao 业务逻辑
- **EPC = 24 位 code**:inventory_item.code 已 UNIQUE,扫到啥当啥用,不需新字段
- **API 地址从 BuildConfig 读**:`API_BASE_URL` 在 `app/build.gradle` 的 `buildConfigField` 配置
- **JWT 存 SharedPreferences** key=`auth_token`,启动时 `ApiClient` 自动注入 `Authorization: Bearer <token>`
- **minSdk 21**:覆盖 99% PDA;Android 5.0+
- **包名**:`com.wms.pda`(独立于 `com.wms` 后端)
- **代码风格**:
  - Java,4 空格缩进
  - 公开类 / 方法必须有 Javadoc
  - 异常用 `IllegalArgumentException` / `IllegalStateException` / `ApiException`
- **测试框架**:JUnit 4.13.2 + Mockito 5 + MockWebServer 3.12
- **签名**:复用 `uhf-uart-demo/uhf-serial_release.jks`(storePassword=123456, keyAlias=uhf-serial-key, keyPassword=123456)

---

## Task 1: 后端补 4 个查询接口(给 PDA 用)

**Files:**
- Modify: `src/main/java/com/wms/controller/InventoryItemController.java` (末尾追加 `byCode` / `byEpc`)
- Modify: `src/main/java/com/wms/controller/ProductController.java` (末尾追加 `bySku` / `byCode`)

**Interfaces:**
- Consumes: 已有的 `InventoryItemDao.findByCode(String)`,`ProductDao.findBySku(String)`,`InventoryItemDao.findById(int)`
- Produces:
  - `GET /api/inventory/items/by-code/{code}` → `ApiResponse<InventoryItem>` (200 / 404)
  - `GET /api/inventory/items/by-epc/{epc}` → `ApiResponse<InventoryItem>` (200 / 404)
  - `GET /api/products/by-sku/{sku}` → `ApiResponse<Product>` (200 / 404)
  - `GET /api/products/by-code/{code}` → `ApiResponse<Product>` (200 / 404) — 通过 code 找 inventory_item → 拿 product_id → 查 Product

- [ ] **Step 1: 在 InventoryItemController 追加 byCode**

在 `src/main/java/com/wms/controller/InventoryItemController.java` 的 `barcodePng` 方法之前插入:

```java
/** 通过 24 位编码查库存单元(PDA 扫码查询用) */
@GetMapping("/by-code/{code}")
public ApiResponse<InventoryItem> byCode(@PathVariable String code) {
    InventoryItem it = itemService.findByCode(code);
    if (it == null) return ApiResponse.fail(404, "编码不存在: " + code);
    return ApiResponse.ok(it);
}

/** EPC = 24 位 code,接口独立便于未来扩展(写入 RFID) */
@GetMapping("/by-epc/{epc}")
public ApiResponse<InventoryItem> byEpc(@PathVariable String epc) {
    InventoryItem it = itemService.findByCode(epc);
    if (it == null) return ApiResponse.fail(404, "EPC 不存在: " + epc);
    return ApiResponse.ok(it);
}
```

- [ ] **Step 2: 在 InventoryItemService 加 findByCode**

在 `src/main/java/com/wms/service/InventoryItemService.java` 的 `get(int)` 方法之后插入:

```java
@Transactional(readOnly = true)
public InventoryItem findByCode(String code) {
    if (code == null || code.isBlank()) return null;
    return itemDao.findByCode(code.trim());
}
```

- [ ] **Step 3: 在 ProductController 追加 bySku 和 byCode**

在 `src/main/java/com/wms/controller/ProductController.java` 的 `get(int id)` 方法之后插入:

```java
/** 通过 SKU 查商品(PDA 扫码查询用) */
@GetMapping("/by-sku/{sku}")
public ApiResponse<Product> bySku(@PathVariable String sku) {
    return productService.findBySku(sku)
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail(404, "SKU 不存在: " + sku));
}

/**
 * 通过 24 位编码找对应商品。
 * 流程:code → inventory_item → productId → Product。
 */
@GetMapping("/by-code/{code}")
public ApiResponse<Product> byCode(@PathVariable String code) {
    InventoryItem it = itemService.findByCode(code);
    if (it == null) return ApiResponse.fail(404, "编码不存在: " + code);
    return productService.get(it.getProductId())
            .map(ApiResponse::ok)
            .orElseGet(() -> ApiResponse.fail(404, "商品不存在: id=" + it.getProductId()));
}
```

- [ ] **Step 4: 给 ProductController 注入 InventoryItemService**

修改 `ProductController` 顶部:

```java
import com.wms.service.InventoryItemService;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final InventoryItemService itemService;  // 新增

    @Autowired
    public ProductController(ProductService productService, InventoryItemService itemService) {
        this.productService = productService;
        this.itemService = itemService;
    }
    // ... 其余不变
}
```

- [ ] **Step 5: 在 ProductService 加 findBySku**

在 `src/main/java/com/wms/service/ProductService.java` 找一个 `Optional<Product>` 风格的位置,追加:

```java
@Transactional(readOnly = true)
public java.util.Optional<Product> findBySku(String sku) {
    if (sku == null || sku.isBlank()) return java.util.Optional.empty();
    return productDao.findBySku(sku.trim());
}
```

> 注:如 `ProductDao` 已有 `findBySku`,直接用,无则需在该 dao 加一行 `findBySku`。先 grep 确认。

- [ ] **Step 6: 编译验证**

```bash
cd E:/Code/SimpleWMS && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 7: 手动 smoke test**

启动应用,curl 4 个接口:

```bash
# 拿一个已存在的 24 位 code
curl -s http://localhost:8080/api/inventory/items | head -c 200
# 假设拿到 ABCDEFGHIJKLMNOPQRSTUV1
curl -s http://localhost:8080/api/inventory/items/by-code/ABCDEFGHIJKLMNOPQRSTUV1
curl -s http://localhost:8080/api/inventory/items/by-epc/ABCDEFGHIJKLMNOPQRSTUV1
curl -s http://localhost:8080/api/products/by-code/ABCDEFGHIJKLMNOPQRSTUV1
curl -s http://localhost:8080/api/products/by-sku/SP-001
# 命中失败(不存在的 code)
curl -s -w "%{http_code}\n" http://localhost:8080/api/inventory/items/by-code/INVALIDCODE123
```

Expected: 命中返回 200 + JSON,未命中返回 404

- [ ] **Step 8: 提交**

```bash
cd E:/Code/SimpleWMS
git add src/main/java/com/wms/controller/InventoryItemController.java \
        src/main/java/com/wms/controller/ProductController.java \
        src/main/java/com/wms/service/InventoryItemService.java \
        src/main/java/com/wms/service/ProductService.java
git commit -m "feat(backend): 4 个 PDA 扫码查询接口

- GET /api/inventory/items/by-code/{code}
- GET /api/inventory/items/by-epc/{epc}
- GET /api/products/by-sku/{sku}
- GET /api/products/by-code/{code}

不动现有业务逻辑,供 Android PDA App 调用。

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: 后端单元测试(4 个查询接口)

**Files:**
- Create: `src/test/java/com/wms/controller/InventoryItemControllerQueryTest.java`
- Create: `src/test/java/com/wms/controller/ProductControllerQueryTest.java`

**Interfaces:**
- Consumes: Spring `@WebMvcTest`,`@MockBean` mock service
- Produces: 6 个测试方法(每个接口命中 + 未命中)

- [ ] **Step 1: 确认测试依赖**

查看 `pom.xml` 是否已有 `spring-boot-starter-test`:

```bash
cd E:/Code/SimpleWMS && grep -A 2 "spring-boot-starter-test" pom.xml
```

如已有,跳到 Step 2。如没有,在 `<dependencies>` 加:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: 写 InventoryItem 查询 controller 测试**

`src/test/java/com/wms/controller/InventoryItemControllerQueryTest.java`:

```java
package com.wms.controller;

import com.wms.model.InventoryItem;
import com.wms.service.InventoryItemService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryItemController.class)
class InventoryItemControllerQueryTest {

    @Autowired private MockMvc mvc;
    @MockBean private InventoryItemService service;

    private InventoryItem sampleItem(String code) {
        InventoryItem i = new InventoryItem();
        i.setId(1);
        i.setCode(code);
        i.setProductId(10);
        i.setSku("SP-001");
        i.setProductName("测试商品");
        i.setLocationCode("A-01");
        i.setQuantity(1);
        i.setRemaining(1);
        i.setStatus(InventoryItem.STATUS_IN_STOCK);
        return i;
    }

    @Test
    void byCode_hit() throws Exception {
        when(service.findByCode("ABC123")).thenReturn(sampleItem("ABC123"));
        mvc.perform(get("/api/inventory/items/by-code/ABC123"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(0))
           .andExpect(jsonPath("$.data.code").value("ABC123"));
    }

    @Test
    void byCode_miss() throws Exception {
        when(service.findByCode("NOPE")).thenReturn(null);
        mvc.perform(get("/api/inventory/items/by-code/NOPE"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void byEpc_hit() throws Exception {
        when(service.findByCode("EPC999")).thenReturn(sampleItem("EPC999"));
        mvc.perform(get("/api/inventory/items/by-epc/EPC999"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.code").value("EPC999"));
    }
}
```

- [ ] **Step 3: 写 Product 查询 controller 测试**

`src/test/java/com/wms/controller/ProductControllerQueryTest.java`:

```java
package com.wms.controller;

import com.wms.model.InventoryItem;
import com.wms.model.Product;
import com.wms.service.InventoryItemService;
import com.wms.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
class ProductControllerQueryTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProductService productService;
    @MockBean private InventoryItemService itemService;

    @Test
    void bySku_hit() throws Exception {
        Product p = new Product();
        p.setId(10);
        p.setSku("SP-001");
        p.setName("测试");
        when(productService.findBySku("SP-001")).thenReturn(Optional.of(p));

        mvc.perform(get("/api/products/by-sku/SP-001"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.sku").value("SP-001"));
    }

    @Test
    void bySku_miss() throws Exception {
        when(productService.findBySku("NOPE")).thenReturn(Optional.empty());
        mvc.perform(get("/api/products/by-sku/NOPE"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void byCode_chainsToInventory() throws Exception {
        InventoryItem it = new InventoryItem();
        it.setProductId(10);
        when(itemService.findByCode("ABC123")).thenReturn(it);

        Product p = new Product();
        p.setId(10);
        p.setSku("SP-001");
        when(productService.get(10)).thenReturn(Optional.of(p));

        mvc.perform(get("/api/products/by-code/ABC123"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.data.sku").value("SP-001"));
    }

    @Test
    void byCode_inventoryMiss() throws Exception {
        when(itemService.findByCode("NOPE")).thenReturn(null);
        mvc.perform(get("/api/products/by-code/NOPE"))
           .andExpect(jsonPath("$.code").value(404));
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
cd E:/Code/SimpleWMS && ./mvnw test -Dtest='InventoryItemControllerQueryTest,ProductControllerQueryTest' -q
```

Expected: Tests run: 7, Failures: 0

- [ ] **Step 5: 提交**

```bash
cd E:/Code/SimpleWMS
git add src/test/java/com/wms/controller/InventoryItemControllerQueryTest.java \
        src/test/java/com/wms/controller/ProductControllerQueryTest.java
git commit -m "test(backend): 4 个 PDA 查询接口的 controller 测试

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: Android Gradle 项目脚手架

**Files:**
- Create: `pda-android/settings.gradle`
- Create: `pda-android/build.gradle`
- Create: `pda-android/gradle.properties`
- Create: `pda-android/gradle/wrapper/gradle-wrapper.properties`
- Create: `pda-android/gradlew` + `gradlew.bat`(从其他 Android 项目复制)
- Create: `pda-android/app/build.gradle`
- Create: `pda-android/app/proguard-rules.pro`
- Create: `pda-android/app/src/main/AndroidManifest.xml`
- Create: `pda-android/app/src/main/java/com/wms/pda/PdaApp.java`
- Create: `pda-android/app/src/main/res/values/strings.xml`
- Create: `pda-android/app/src/main/res/values/colors.xml`
- Create: `pda-android/app/src/main/res/values/themes.xml`
- Create: `pda-android/.gitignore`

**Interfaces:**
- Produces: 空白 Android Studio 项目,能 `./gradlew tasks` 列出 build/installDebug

- [ ] **Step 1: 初始化目录结构**

```bash
cd E:/Code/SimpleWMS
mkdir -p pda-android/app/src/main/java/com/wms/pda/{net,scan,data,model,ui/login,ui/home,ui/inbound,ui/outbound,ui/check,ui/lookup,util}
mkdir -p pda-android/app/src/main/res/{layout,values,drawable,mipmap-hdpi}
mkdir -p pda-android/app/src/test/java/com/wms/pda
mkdir -p pda-android/app/libs
mkdir -p pda-android/gradle/wrapper
```

- [ ] **Step 2: settings.gradle**

`pda-android/settings.gradle`:

```gradle
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "SimpleWMS-PDA"
include ':app'
```

- [ ] **Step 3: 根 build.gradle**

`pda-android/build.gradle`:

```gradle
plugins {
    id 'com.android.application' version '8.1.4' apply false
}
```

- [ ] **Step 4: gradle.properties**

`pda-android/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
```

- [ ] **Step 5: gradle wrapper**

`pda-android/gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

> gradlew / gradlew.bat 二进制文件从 `uhf-uart-demo/gradlew*` 复制(如不存在,从系统其他 Android Studio 项目复制),保持可执行权限不变。

- [ ] **Step 6: app/build.gradle**

`pda-android/app/build.gradle`:

```gradle
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.wms.pda'
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
        debug {
            // debug 用 debug 签名(系统默认)
        }
        release {
            signingConfig signingConfigs.release
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.10.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'com.squareup.okhttp3:okhttp:3.12.0'
    implementation 'com.google.code.gson:gson:2.10.1'
    // 厂商 SDK,空 jar 也行(后期填)
    implementation files('libs/uhf-sdk.jar')
    implementation files('libs/scan-sdk.jar')
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.mockito:mockito-core:5.0.0'
    testImplementation 'com.squareup.okhttp3:mockwebserver:3.12.0'
}
```

- [ ] **Step 7: 临时占位 SDK jar**

```bash
cd E:/Code/SimpleWMS/pda-android/app/libs
# 创建空 jar(避免编译失败)
echo "Manifest-Version: 1.0" > MANIFEST.MF
jar cfm uhf-sdk.jar MANIFEST.MF
jar cfm scan-sdk.jar MANIFEST.MF
```

> 真实 SDK 后期替换为厂商提供的 jar(名字不同就改 build.gradle 引用)。

- [ ] **Step 8: proguard-rules.pro**

`pda-android/app/proguard-rules.pro`:

```proguard
# 厂商 SDK
-keep class com.example.uhf.** { *; }
-keep class com.uhf.** { *; }

# DTO
-keep class com.wms.pda.model.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
```

- [ ] **Step 9: AndroidManifest**

`pda-android/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:name=".PdaApp"
        android:label="@string/app_name"
        android:theme="@style/Theme.SimpleWmsPda"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".ui.login.LoginActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity android:name=".ui.home.HomeActivity" android:exported="false" />
        <activity android:name=".ui.inbound.InboundScanActivity" android:exported="false" />
        <activity android:name=".ui.outbound.OutboundScanActivity" android:exported="false" />
        <activity android:name=".ui.check.StockCheckActivity" android:exported="false" />
        <activity android:name=".ui.lookup.LookupActivity" android:exported="false" />

    </application>
</manifest>
```

- [ ] **Step 10: PdaApp**

`pda-android/app/src/main/java/com/wms/pda/PdaApp.java`:

```java
package com.wms.pda;

import android.app.Application;

/**
 * Application 入口 - 全局初始化(扫码设备 / 蜂鸣 / 主题等)。
 */
public class PdaApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
    }
}
```

- [ ] **Step 11: strings.xml / colors.xml / themes.xml**

`pda-android/app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">SimpleWMS-PDA</string>
    <string name="login">登录</string>
    <string name="username">用户名</string>
    <string name="password">密码</string>
    <string name="inbound">入库扫码</string>
    <string name="outbound">出库扫码</string>
    <string name="stock_check">库存盘点</string>
    <string name="lookup">扫码查询</string>
</resources>
```

`pda-android/app/src/main/res/values/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="primary">#1976D2</color>
    <color name="primary_dark">#0D47A1</color>
    <color name="accent">#03DAC5</color>
    <color name="bg">#F4F6F9</color>
    <color name="text_primary">#212121</color>
    <color name="text_secondary">#757575</color>
    <color name="success">#2E7D32</color>
    <color name="error">#C62828</color>
    <color name="white">#FFFFFF</color>
</resources>
```

`pda-android/app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.SimpleWmsPda" parent="Theme.MaterialComponents.Light.NoActionBar">
        <item name="colorPrimary">@color/primary</item>
        <item name="colorPrimaryDark">@color/primary_dark</item>
        <item name="colorAccent">@color/accent</item>
        <item name="android:windowBackground">@color/bg</item>
    </style>
</resources>
```

- [ ] **Step 12: .gitignore**

`pda-android/.gitignore`:

```gitignore
.gradle/
build/
local.properties
.idea/
*.iml
captures/
.cxx/
```

- [ ] **Step 13: 占位 Activity(只创建空 Activity,后面任务填实现)**

`pda-android/app/src/main/java/com/wms/pda/ui/login/LoginActivity.java`:

```java
package com.wms.pda.ui.login;

import android.app.Activity;
import android.os.Bundle;

public class LoginActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
```

为 HomeActivity / InboundScanActivity / OutboundScanActivity / StockCheckActivity / LookupActivity 创建相同骨架(类名 + 空 onCreate),不需 layout。

- [ ] **Step 14: 验证脚手架能编译**

```bash
cd E:/Code/SimpleWMS/pda-android
chmod +x gradlew
./gradlew tasks --offline 2>&1 | head -30 || ./gradlew tasks 2>&1 | head -30
```

Expected: 列出 "build", "installDebug" 等任务

- [ ] **Step 15: 提交**

```bash
cd E:/Code/SimpleWMS
git add pda-android/
git commit -m "feat(pda): Android Gradle 项目脚手架

- pda-android/ 新项目
- minSdk 21 / targetSdk 34
- OkHttp + Gson + AndroidX + Material
- 复用 uhf-uart-demo 签名密钥
- 6 个空 Activity 占位

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: 扫码/RFID 抽象层

**Files:**
- Create: `pda-android/app/src/main/java/com/wms/pda/scan/ScanType.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/scan/ScanResult.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/scan/ScannerService.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/scan/ScannerCallback.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/scan/UhfScannerImpl.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/scan/BarcodeScannerImpl.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/scan/ScanManager.java`
- Create: `pda-android/app/src/test/java/com/wms/pda/scan/ScanManagerTest.java`

**Interfaces:**
- Consumes: `android.content.Context`
- Produces:
  - `ScanManager.get(Context).startScan(ScanType, ScannerCallback)`
  - `ScanManager.get(Context).stopScan()`

- [ ] **Step 1: ScanType enum**

`pda-android/app/src/main/java/com/wms/pda/scan/ScanType.java`:

```java
package com.wms.pda.scan;

/** 扫码类型。EPC = 24 位 code,后端统一处理。 */
public enum ScanType {
    BARCODE,    // 光学/激光条码
    RFID_EPC    // UHF RFID(EPC 字符串 = inventory_item.code)
}
```

- [ ] **Step 2: ScanResult**

`pda-android/app/src/main/java/com/wms/pda/scan/ScanResult.java`:

```java
package com.wms.pda.scan;

import java.util.Objects;

/** 扫码/RFID 扫描结果。 */
public final class ScanResult {
    private final String code;
    private final ScanType type;
    private final String rssi;       // 光学场景为 null
    private final long timestamp;

    public ScanResult(String code, ScanType type, String rssi, long timestamp) {
        this.code = Objects.requireNonNull(code, "code");
        this.type = Objects.requireNonNull(type, "type");
        this.rssi = rssi;
        this.timestamp = timestamp;
    }

    public String getCode() { return code; }
    public ScanType getType() { return type; }
    public String getRssi() { return rssi; }
    public long getTimestamp() { return timestamp; }

    @Override public String toString() {
        return type + "(" + code + (rssi != null ? ",rssi=" + rssi : "") + ")";
    }
}
```

- [ ] **Step 3: ScannerService 接口 + Callback**

`pda-android/app/src/main/java/com/wms/pda/scan/ScannerService.java`:

```java
package com.wms.pda.scan;

import android.content.Context;

/** 扫码设备抽象接口(UHF / 光学共用)。 */
public interface ScannerService {
    /** 打开设备。失败抛 RuntimeException。 */
    void init(Context ctx) throws Exception;
    /** 关闭设备。 */
    void release();
    /** 启动一次扫描(单次或短轮询)。 */
    void startScan(ScanType type);
    /** 主动停止。 */
    void stopScan();
    /** 设备是否就绪。 */
    boolean isReady();
    /** 设置结果回调。 */
    void setCallback(ScannerCallback cb);
}
```

`pda-android/app/src/main/java/com/wms/pda/scan/ScannerCallback.java`:

```java
package com.wms.pda.scan;

/** 扫码结果回调。 */
public interface ScannerCallback {
    void onScanResult(ScanResult result);
    void onError(int code, String msg);
    /** 默认 5 秒无标签回调(实现可空)。 */
    default void onTimeout() {}
}
```

- [ ] **Step 4: UhfScannerImpl(基于 uhf-uart-demo API 风格)**

`pda-android/app/src/main/java/com/wms/pda/scan/UhfScannerImpl.java`:

```java
package com.wms.pda.scan;

import android.content.Context;
import android.util.Log;

import com.example.uhf.UhfInfo;
import com.example.uhf.Reader;

import java.util.HashMap;
import java.util.List;

/**
 * UHF RFID 实现 - 走厂商 SDK。
 * 厂商 SDK 参考 uhf-uart-demo/ (com.example.uhf.UhfInfo + Reader)。
 * 若厂商 SDK 类不存在(普通 Android 设备),init() 抛异常,UI 层降级。
 */
public class UhfScannerImpl implements ScannerService {

    private static final String TAG = "UhfScannerImpl";
    private UhfInfo uhfInfo;
    private Reader mReader;
    private ScannerCallback cb;
    private boolean ready;
    private volatile boolean scanning;
    private Thread scanThread;

    @Override
    public void init(Context ctx) throws Exception {
        uhfInfo = new UhfInfo();
        mReader = uhfInfo.getReader();
        if (mReader == null) {
            throw new IllegalStateException("UHF 模组初始化失败:Reader 为 null");
        }
        ready = true;
        Log.i(TAG, "UHF initialized");
    }

    @Override
    public void release() {
        stopScan();
        if (mReader != null) {
            try { mReader.free(); } catch (Exception e) { Log.w(TAG, "free failed", e); }
            mReader = null;
        }
        ready = false;
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.RFID_EPC) return;
        if (!ready || mReader == null) {
            if (cb != null) cb.onError(-100, "UHF 未就绪");
            return;
        }
        stopScan();
        scanning = true;
        scanThread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 5_000;
            try {
                while (scanning && System.currentTimeMillis() < deadline) {
                    List<HashMap<String, String>> tags = mReader.inventoryRealTime();
                    if (tags != null) {
                        for (HashMap<String, String> tag : tags) {
                            String epc = tag.get("EPC");
                            if (epc == null || epc.isEmpty()) continue;
                            String rssi = tag.get("RSSI");
                            if (cb != null) {
                                cb.onScanResult(new ScanResult(
                                    epc, ScanType.RFID_EPC, rssi, System.currentTimeMillis()));
                            }
                            stopScan();
                            return;
                        }
                    }
                    Thread.sleep(150);
                }
                if (scanning && cb != null) cb.onTimeout();
            } catch (Exception e) {
                Log.e(TAG, "scan failed", e);
                if (cb != null) cb.onError(-1, e.getMessage());
            }
        }, "UhfScanThread");
        scanThread.start();
    }

    @Override
    public void stopScan() {
        scanning = false;
        if (scanThread != null) {
            scanThread.interrupt();
            scanThread = null;
        }
    }

    @Override public boolean isReady() { return ready; }
    @Override public void setCallback(ScannerCallback cb) { this.cb = cb; }
}
```

- [ ] **Step 5: BarcodeScannerImpl(光学扫码)**

`pda-android/app/src/main/java/com/wms/pda/scan/BarcodeScannerImpl.java`:

```java
package com.wms.pda.scan;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * 光学条码实现 - 监听厂家扫码广播。
 * 厂家广播 action 通常为 "com.example.uhf.action.BARCODE"(参考 uhf-uart-demo)。
 */
public class BarcodeScannerImpl implements ScannerService {

    private static final String TAG = "BarcodeScannerImpl";
    private static final String ACTION_BARCODE = "com.example.uhf.action.BARCODE";
    private static final String EXTRA_BARCODE = "BARCODE";

    private Context appCtx;
    private ScannerCallback cb;
    private BroadcastReceiver receiver;
    private boolean ready;

    @Override
    public void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent == null) return;
                String code = intent.getStringExtra(EXTRA_BARCODE);
                if (code != null && !code.isEmpty() && cb != null) {
                    cb.onScanResult(new ScanResult(
                        code, ScanType.BARCODE, null, System.currentTimeMillis()));
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_BARCODE);
        appCtx.registerReceiver(receiver, filter);
        ready = true;
        Log.i(TAG, "Barcode listener registered");
    }

    @Override
    public void release() {
        if (receiver != null) {
            try { appCtx.unregisterReceiver(receiver); } catch (Exception e) { Log.w(TAG, e); }
            receiver = null;
        }
        ready = false;
    }

    @Override
    public void startScan(ScanType type) {
        if (type != ScanType.BARCODE) return;
        if (!ready) {
            if (cb != null) cb.onError(-100, "扫码设备未就绪");
            return;
        }
        // 光学扫码是「按扫描键」触发,无需主动 startScan
        // 这里仅做超时提示
        new Thread(() -> {
            try { Thread.sleep(5_000); }
            catch (InterruptedException ignored) {}
            if (cb != null) cb.onTimeout();
        }, "BarcodeTimeout").start();
    }

    @Override
    public void stopScan() { /* 光学扫码无后台线程 */ }

    @Override public boolean isReady() { return ready; }
    @Override public void setCallback(ScannerCallback cb) { this.cb = cb; }
}
```

- [ ] **Step 6: ScanManager 单例门面**

`pda-android/app/src/main/java/com/wms/pda/scan/ScanManager.java`:

```java
package com.wms.pda.scan;

import android.content.Context;
import android.util.Log;

/** 扫码门面 - UI 层只调这里,不知道底层是哪个厂。 */
public class ScanManager {

    private static final String TAG = "ScanManager";
    private static volatile ScanManager INSTANCE;

    private final Context appCtx;
    private final UhfScannerImpl uhf;
    private final BarcodeScannerImpl barcode;

    private ScanManager(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.uhf = new UhfScannerImpl();
        this.barcode = new BarcodeScannerImpl();
    }

    public static ScanManager get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (ScanManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ScanManager(ctx);
                }
            }
        }
        return INSTANCE;
    }

    /** 启动一次扫描。type 决定走 UHF 还是光学。 */
    public void startScan(ScanType type, ScannerCallback cb) {
        ScannerService svc = (type == ScanType.RFID_EPC) ? uhf : barcode;
        if (!svc.isReady()) {
            try {
                svc.init(appCtx);
            } catch (Throwable e) {
                Log.e(TAG, "init failed", e);
                cb.onError(-100, "扫码设备未就绪: " + e.getMessage());
                return;
            }
        }
        svc.setCallback(cb);
        svc.startScan(type);
    }

    public void stopScan() {
        if (uhf.isReady()) uhf.stopScan();
        if (barcode.isReady()) barcode.stopScan();
    }

    /** 释放(应用退出时调用)。 */
    public void release() {
        uhf.release();
        barcode.release();
    }

    /** 设备状态 - 用于 UI 显示。 */
    public boolean isUhfReady() { return uhf.isReady(); }
    public boolean isBarcodeReady() { return barcode.isReady(); }
}
```

- [ ] **Step 7: 单元测试 ScanManager**

`pda-android/app/src/test/java/com/wms/pda/scan/ScanManagerTest.java`:

```java
package com.wms.pda.scan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ScanManagerTest {

    @Test
    public void scanType_enumValues() {
        assertEquals(2, ScanType.values().length);
        assertNotNull(ScanType.valueOf("BARCODE"));
        assertNotNull(ScanType.valueOf("RFID_EPC"));
    }

    @Test
    public void scanResult_fieldsImmutable() {
        ScanResult r = new ScanResult("ABC123", ScanType.BARCODE, null, 1000L);
        assertEquals("ABC123", r.getCode());
        assertEquals(ScanType.BARCODE, r.getType());
        assertEquals(1000L, r.getTimestamp());
    }

    @Test(expected = NullPointerException.class)
    public void scanResult_nullCodeThrows() {
        new ScanResult(null, ScanType.BARCODE, null, 0L);
    }
}
```

- [ ] **Step 8: 编译 + 测试**

```bash
cd E:/Code/SimpleWMS/pda-android
./gradlew :app:compileDebugJavaWithJavac :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, 3 tests pass

- [ ] **Step 9: 提交**

```bash
cd E:/Code/SimpleWMS
git add pda-android/app/src/main/java/com/wms/pda/scan/ \
        pda-android/app/src/test/java/com/wms/pda/scan/
git commit -m "feat(pda): 扫码/RFID 抽象层

- ScannerService 接口 + UhfScannerImpl + BarcodeScannerImpl
- ScanManager 单例门面
- EPC = 24 位 code,后端无需新字段
- JUnit 测试 ScanType/ScanResult

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: API 客户端(OkHttp + JWT)

**Files:**
- Create: `pda-android/app/src/main/java/com/wms/pda/data/SessionStore.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/net/ApiException.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/net/ApiClient.java`
- Create: `pda-android/app/src/test/java/com/wms/pda/net/ApiClientTest.java`

**Interfaces:**
- Produces:
  - `ApiClient.get(String path, Class<T> type) throws ApiException`
  - `ApiClient.post(String path, Object body, Class<T> type) throws ApiException`
  - `ApiClient.put(String path, Object body, Class<T> type) throws ApiException`
  - `SessionStore.getToken() / setToken(String) / clear() / getUsername() / setUsername(String)`

- [ ] **Step 1: SessionStore**

`pda-android/app/src/main/java/com/wms/pda/data/SessionStore.java`:

```java
package com.wms.pda.data;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences 存 JWT + 用户名。 */
public class SessionStore {

    private static final String PREF = "wms_pda_session";
    private static final String K_TOKEN = "auth_token";
    private static final String K_USERNAME = "username";
    private static final String K_ROLE = "role";
    private static final String K_BASE_URL = "base_url";

    private final SharedPreferences sp;

    public SessionStore(Context ctx) {
        this.sp = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public String getToken() { return sp.getString(K_TOKEN, null); }
    public void setToken(String token) {
        sp.edit().putString(K_TOKEN, token).apply();
    }

    public String getUsername() { return sp.getString(K_USERNAME, null); }
    public void setUsername(String u) {
        sp.edit().putString(K_USERNAME, u).apply();
    }

    public String getRole() { return sp.getString(K_ROLE, null); }
    public void setRole(String r) {
        sp.edit().putString(K_ROLE, r).apply();
    }

    public String getBaseUrl() {
        return sp.getString(K_BASE_URL, null);
    }
    public void setBaseUrl(String url) {
        sp.edit().putString(K_BASE_URL, url).apply();
    }

    public void clear() {
        sp.edit().clear().apply();
    }
}
```

- [ ] **Step 2: ApiException**

`pda-android/app/src/main/java/com/wms/pda/net/ApiException.java`:

```java
package com.wms.pda.net;

/** API 调用异常。 */
public class ApiException extends Exception {
    public static final int ERR_NETWORK = -1;
    public static final int ERR_HTTP = -2;
    public static final int ERR_PARSE = -3;
    public static final int ERR_AUTH = 401;

    private final int code;
    public ApiException(int code, String msg) {
        super(msg);
        this.code = code;
    }
    public int getCode() { return code; }
}
```

- [ ] **Step 3: ApiClient**

`pda-android/app/src/main/java/com/wms/pda/net/ApiClient.java`:

```java
package com.wms.pda.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * API 客户端 - OkHttp + JWT 拦截器 + 后端 ApiResponse 解析。
 * 失败抛 ApiException(网络 / HTTP / 业务 / 鉴权)。
 */
public class ApiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final SessionStore session;
    private final OkHttpClient http;
    private final Gson gson = new Gson();
    private final String baseUrl;

    public ApiClient(SessionStore session) {
        this.session = session;
        this.baseUrl = resolveBaseUrl(session);
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(authInterceptor())
                .build();
    }

    private static String resolveBaseUrl(SessionStore session) {
        String override = session.getBaseUrl();
        if (override != null && !override.isEmpty()) return override;
        return com.wms.pda.BuildConfig.API_BASE_URL;
    }

    private Interceptor authInterceptor() {
        return chain -> {
            Request req = chain.request();
            String token = session.getToken();
            if (token != null && !token.isEmpty()) {
                req = req.newBuilder()
                        .addHeader("Authorization", "Bearer " + token)
                        .build();
            }
            return chain.proceed(req);
        };
    }

    public <T> T get(String path, Class<T> type) throws ApiException {
        return execute(buildRequest(path, null, "GET"), type);
    }

    public <T> T post(String path, Object body, Class<T> type) throws ApiException {
        return execute(buildRequest(path, body, "POST"), type);
    }

    public <T> T put(String path, Object body, Class<T> type) throws ApiException {
        return execute(buildRequest(path, body, "PUT"), type);
    }

    private Request buildRequest(String path, Object body, String method) {
        Request.Builder b = new Request.Builder().url(baseUrl + path);
        if (body != null) {
            String json = gson.toJson(body);
            b.method(method, RequestBody.create(JSON, json));
        } else {
            b.method(method, body == null && "GET".equals(method) ? null : null);
        }
        return b.build();
    }

    private <T> T execute(Request req, Class<T> type) throws ApiException {
        try (Response resp = http.newCall(req).execute()) {
            ResponseBody rb = resp.body();
            String bodyStr = rb == null ? "" : rb.string();
            if (!resp.isSuccessful()) {
                throw new ApiException(ApiException.ERR_HTTP,
                        "HTTP " + resp.code() + ": " + bodyStr);
            }
            ApiResponse<?> ar;
            try {
                ar = gson.fromJson(bodyStr, ApiResponse.class);
            } catch (Exception e) {
                throw new ApiException(ApiException.ERR_PARSE, "JSON 解析失败: " + e.getMessage());
            }
            if (ar == null) throw new ApiException(ApiException.ERR_PARSE, "空响应");
            if (ar.code == 401) {
                session.clear();
                throw new ApiException(ApiException.ERR_AUTH, ar.msg);
            }
            if (ar.code != 0) {
                throw new ApiException(ar.code, ar.msg);
            }
            if (type == Void.class || ar.data == null) return null;
            JsonObject dataJson = gson.toJsonTree(ar.data).getAsJsonObject();
            return gson.fromJson(dataJson, type);
        } catch (IOException e) {
            throw new ApiException(ApiException.ERR_NETWORK, "网络不可达: " + e.getMessage());
        }
    }

    public String getBaseUrl() { return baseUrl; }
}
```

- [ ] **Step 4: ApiResponse DTO(先放这里,Task 6 一起完善)**

`pda-android/app/src/main/java/com/wms/pda/model/ApiResponse.java`:

```java
package com.wms.pda.model;

/** 后端 ApiResponse 镜像。 */
public class ApiResponse<T> {
    public int code;
    public String msg;
    public T data;
}
```

- [ ] **Step 5: 单元测试**

`pda-android/app/src/test/java/com/wms/pda/net/ApiClientTest.java`:

```java
package com.wms.pda.net;

import com.wms.pda.data.SessionStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ApiClientTest {

    private MockWebServer server;
    private SessionStore store;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        store = new SessionStore(org.robolectric.RuntimeEnvironment.application);
        // 覆盖 baseUrl 为 mock server
        store.setBaseUrl(server.url("").toString());
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void get_success() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"code\":0,\"msg\":\"\",\"data\":{\"id\":1,\"sku\":\"SP-001\"}}"));

        ApiClient client = new ApiClient(store);
        TestProduct p = client.get("/api/products/1", TestProduct.class);
        assertNotNull(p);
        assertEquals(1, p.id);
        assertEquals("SP-001", p.sku);

        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/api/products/1", req.getPath());
    }

    @Test
    public void get_authHeaderInjected() throws Exception {
        store.setToken("jwt-abc");
        server.enqueue(new MockResponse().setBody("{\"code\":0,\"msg\":\"\",\"data\":{}}"));

        ApiClient client = new ApiClient(store);
        client.get("/api/auth/me", Object.class);

        RecordedRequest req = server.takeRequest();
        assertEquals("Bearer jwt-abc", req.getHeader("Authorization"));
    }

    @Test
    public void get_businessError() {
        server.enqueue(new MockResponse().setBody(
                "{\"code\":404,\"msg\":\"未找到\",\"data\":null}"));

        ApiClient client = new ApiClient(store);
        try {
            client.get("/api/missing", Object.class);
            fail("应抛 ApiException");
        } catch (ApiException e) {
            assertEquals(404, e.getCode());
            assertTrue(e.getMessage().contains("未找到"));
        }
    }

    @Test
    public void get_authError401_clearsSession() {
        store.setToken("old-jwt");
        server.enqueue(new MockResponse().setBody(
                "{\"code\":401,\"msg\":\"token 过期\",\"data\":null}"));

        ApiClient client = new ApiClient(store);
        try {
            client.get("/api/auth/me", Object.class);
            fail("应抛 ApiException");
        } catch (ApiException e) {
            assertEquals(ApiException.ERR_AUTH, e.getCode());
        }
        // token 被清
        assertEquals(null, store.getToken());
    }

    public static class TestProduct {
        public int id;
        public String sku;
    }
}
```

- [ ] **Step 6: 在 app/build.gradle 加 Robolectric 依赖**

修改 `pda-android/app/build.gradle` 的 `testImplementation` 段:

```gradle
    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.mockito:mockito-core:5.0.0'
    testImplementation 'com.squareup.okhttp3:mockwebserver:3.12.0'
    testImplementation 'org.robolectric:robolectric:4.10.3'
```

- [ ] **Step 7: 运行测试**

```bash
cd E:/Code/SimpleWMS/pda-android
./gradlew :app:testDebugUnitTest --tests "com.wms.pda.net.*"
```

Expected: 4 tests pass

- [ ] **Step 8: 提交**

```bash
cd E:/Code/SimpleWMS
git add pda-android/app/src/main/java/com/wms/pda/data/ \
        pda-android/app/src/main/java/com/wms/pda/net/ \
        pda-android/app/src/main/java/com/wms/pda/model/ApiResponse.java \
        pda-android/app/src/test/java/com/wms/pda/net/ \
        pda-android/app/build.gradle
git commit -m "feat(pda): API 客户端 (OkHttp + JWT + ApiException)

- SessionStore (SharedPreferences)
- ApiClient: GET/POST/PUT + 5 类异常
- MockWebServer 单元测试 (4 个 case)

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 6: 业务 DTO(8 个)

**Files:**
- Create: `pda-android/app/src/main/java/com/wms/pda/model/LoginRequest.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/model/LoginResponse.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/model/ProductDto.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/model/LocationDto.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/model/InventoryItemDto.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/model/InboundRequest.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/model/OutboundRequest.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/model/StockCheckDto.java`
- Create: `pda-android/app/src/test/java/com/wms/pda/model/DtoSerializationTest.java`

- [ ] **Step 1: LoginRequest / LoginResponse**

`pda-android/app/src/main/java/com/wms/pda/model/LoginRequest.java`:

```java
package com.wms.pda.model;

public class LoginRequest {
    public String username;
    public String password;
    public LoginRequest() {}
    public LoginRequest(String u, String p) { this.username = u; this.password = p; }
}
```

`pda-android/app/src/main/java/com/wms/pda/model/LoginResponse.java`:

```java
package com.wms.pda.model;

public class LoginResponse {
    public String token;
    public String username;
    public String role;
    public boolean mustChangePw;
}
```

- [ ] **Step 2: ProductDto**

`pda-android/app/src/main/java/com/wms/pda/model/ProductDto.java`:

```java
package com.wms.pda.model;

import java.math.BigDecimal;

public class ProductDto {
    public Integer id;
    public String sku;
    public String name;
    public String category;
    public String unit;
    public String location;
    public Integer quantity;
    public BigDecimal unitPrice;
    public Integer lowStock;
    public String remark;
}
```

- [ ] **Step 3: LocationDto**

`pda-android/app/src/main/java/com/wms/pda/model/LocationDto.java`:

```java
package com.wms.pda.model;

public class LocationDto {
    public Integer id;
    public String code;
    public String name;
    public String remark;
}
```

- [ ] **Step 4: InventoryItemDto**

`pda-android/app/src/main/java/com/wms/pda/model/InventoryItemDto.java`:

```java
package com.wms.pda.model;

public class InventoryItemDto {
    public Integer id;
    public String code;
    public Integer productId;
    public String sku;
    public String productName;
    public String locationCode;
    public Integer quantity;
    public Integer remaining;
    public String status;
    public Integer inboundOrderId;
    public Integer outboundOrderId;
    public String createdBy;
    public String createdAt;     // ISO 字符串,Gson 直接吃
    public String updatedAt;
}
```

- [ ] **Step 5: InboundRequest**

`pda-android/app/src/main/java/com/wms/pda/model/InboundRequest.java`:

```java
package com.wms.pda.model;

import java.util.List;

public class InboundRequest {
    public String externalNo;
    public String remark;
    public List<Item> items;

    public static class Item {
        public Integer productId;
        public Integer quantity;
        public String supplier;
        public String rowRemark;
    }
}
```

- [ ] **Step 6: OutboundRequest**

`pda-android/app/src/main/java/com/wms/pda/model/OutboundRequest.java`:

```java
package com.wms.pda.model;

import java.util.List;

public class OutboundRequest {
    public String customer;
    public String externalNo;
    public String remark;
    public List<Item> items;

    public static class Item {
        public Integer productId;
        public Integer quantity;
        public String rowRemark;
    }
}
```

- [ ] **Step 7: StockCheckDto**

`pda-android/app/src/main/java/com/wms/pda/model/StockCheckDto.java`:

```java
package com.wms.pda.model;

import java.util.List;

public class StockCheckDto {
    public Integer id;
    public String checkNo;
    public String scope;
    public String scopeKeyword;
    public String remark;
    public String status;
    public Integer totalCount;
    public Integer countedCount;
    public Integer diffCount;
    public List<Item> items;

    public static class Item {
        public Integer id;
        public Integer inventoryItemId;
        public String code;
        public String sku;
        public String productName;
        public String locationCode;
        public Integer systemQty;
        public Integer countedQty;
        public Integer diff;
        public String rowRemark;
    }
}
```

- [ ] **Step 8: DTO Gson 测试**

`pda-android/app/src/test/java/com/wms/pda/model/DtoSerializationTest.java`:

```java
package com.wms.pda.model;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DtoSerializationTest {

    private final Gson gson = new Gson();

    @Test
    public void loginRequest() {
        String json = gson.toJson(new LoginRequest("admin", "change-me-now"));
        assertEquals("{\"username\":\"admin\",\"password\":\"change-me-now\"}", json);
    }

    @Test
    public void inventoryItemDto() {
        String src = "{\"id\":1,\"code\":\"ABC123\",\"sku\":\"SP-001\",\"status\":\"IN_STOCK\"}";
        InventoryItemDto it = gson.fromJson(src, InventoryItemDto.class);
        assertEquals(1, it.id.intValue());
        assertEquals("ABC123", it.code);
        assertEquals("IN_STOCK", it.status);
    }

    @Test
    public void inboundRequest_nested() {
        InboundRequest.Item it = new InboundRequest.Item();
        it.productId = 10;
        it.quantity = 5;
        InboundRequest r = new InboundRequest();
        r.remark = "test";
        r.items = Arrays.asList(it);
        String json = gson.toJson(r);
        assertNotNull(json);
        assertEquals(true, json.contains("\"productId\":10"));
        assertEquals(true, json.contains("\"quantity\":5"));
    }

    @Test
    public void stockCheckItem() {
        String src = "{\"id\":1,\"code\":\"X1\",\"systemQty\":3,\"countedQty\":2,\"diff\":-1}";
        StockCheckDto.Item it = gson.fromJson(src, StockCheckDto.Item.class);
        assertEquals(-1, it.diff.intValue());
    }
}
```

- [ ] **Step 9: 运行测试**

```bash
cd E:/Code/SimpleWMS/pda-android
./gradlew :app:testDebugUnitTest --tests "com.wms.pda.model.*"
```

Expected: 4 tests pass

- [ ] **Step 10: 提交**

```bash
cd E:/Code/SimpleWMS
git add pda-android/app/src/main/java/com/wms/pda/model/ \
        pda-android/app/src/test/java/com/wms/pda/model/
git commit -m "feat(pda): 业务 DTO (8 个) + Gson 序列化测试

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 7: LoginActivity + HomeActivity(2 个页面 + 布局 + 工具)

**Files:**
- Create: `pda-android/app/src/main/res/layout/activity_login.xml`
- Create: `pda-android/app/src/main/res/layout/activity_home.xml`
- Create: `pda-android/app/src/main/res/values/strings.xml` (追加字符串)
- Create: `pda-android/app/src/main/java/com/wms/pda/util/BeepUtil.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/util/ToastUtil.java`
- Modify: `pda-android/app/src/main/java/com/wms/pda/ui/login/LoginActivity.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/ui/home/HomeActivity.java`
- Create: `pda-android/app/src/test/java/com/wms/pda/util/BeepUtilTest.java`

**Interfaces:**
- LoginActivity 调 `ApiClient.post("/api/auth/login", new LoginRequest(u,p), LoginResponse.class)`,成功存 token + 跳 HomeActivity
- HomeActivity 4 个大按钮入口,顶栏显示用户名 + RFID 状态

- [ ] **Step 1: 追加 strings**

修改 `pda-android/app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">SimpleWMS-PDA</string>
    <string name="login">登录</string>
    <string name="username">用户名</string>
    <string name="password">密码</string>
    <string name="login_btn">登录</string>
    <string name="logout">退出</string>
    <string name="inbound">入库扫码</string>
    <string name="outbound">出库扫码</string>
    <string name="stock_check">库存盘点</string>
    <string name="lookup">扫码查询</string>
    <string name="rfid_ready">📶 RFID 就绪</string>
    <string name="rfid_offline">⚠ RFID 未就绪</string>
    <string name="login_failed">登录失败</string>
    <string name="username_required">请输入用户名</string>
    <string name="password_required">请输入密码</string>
</resources>
```

- [ ] **Step 2: activity_login.xml**

`pda-android/app/src/main/res/layout/activity_login.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="32dp"
    android:gravity="center">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textSize="28sp"
        android:textColor="@color/primary_dark"
        android:textStyle="bold"
        android:layout_marginBottom="40dp" />

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/username"
        android:layout_marginBottom="16dp">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/etUsername"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="text"
            android:textSize="18sp" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/password"
        android:layout_marginBottom="24dp">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/etPassword"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:inputType="textPassword"
            android:textSize="18sp" />
    </com.google.android.material.textfield.TextInputLayout>

    <Button
        android:id="@+id/btnLogin"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:text="@string/login_btn"
        android:textSize="18sp" />

</LinearLayout>
```

- [ ] **Step 3: activity_home.xml**

`pda-android/app/src/main/res/layout/activity_home.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:background="@color/primary"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="0 16dp">

        <TextView
            android:id="@+id/tvUser"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textColor="@color/white"
            android:textSize="16sp" />

        <TextView
            android:id="@+id/tvRfid"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/white"
            android:textSize="13sp"
            android:layout_marginEnd="16dp" />

        <Button
            android:id="@+id/btnLogout"
            android:layout_width="wrap_content"
            android:layout_height="40dp"
            android:text="@string/logout"
            android:textSize="13sp" />
    </LinearLayout>

    <GridLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:columnCount="2"
        android:rowCount="2"
        android:padding="16dp"
        android:useDefaultMargins="true">

        <Button
            android:id="@+id/btnInbound"
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:layout_columnWeight="1"
            android:layout_rowWeight="1"
            android:layout_margin="8dp"
            android:text="📥\n入库扫码"
            android:textSize="22sp" />

        <Button
            android:id="@+id/btnOutbound"
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:layout_columnWeight="1"
            android:layout_rowWeight="1"
            android:layout_margin="8dp"
            android:text="📤\n出库扫码"
            android:textSize="22sp" />

        <Button
            android:id="@+id/btnCheck"
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:layout_columnWeight="1"
            android:layout_rowWeight="1"
            android:layout_margin="8dp"
            android:text="🔍\n库存盘点"
            android:textSize="22sp" />

        <Button
            android:id="@+id/btnLookup"
            android:layout_width="0dp"
            android:layout_height="0dp"
            android:layout_columnWeight="1"
            android:layout_rowWeight="1"
            android:layout_margin="8dp"
            android:text="🔎\n扫码查询"
            android:textSize="22sp" />
    </GridLayout>
</LinearLayout>
```

- [ ] **Step 4: BeepUtil**

`pda-android/app/src/main/java/com/wms/pda/util/BeepUtil.java`:

```java
package com.wms.pda.util;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;

/** 蜂鸣提示:成功/失败两音。 */
public class BeepUtil {

    private static ToneGenerator tone;

    public static void init(Context ctx) {
        if (tone == null) {
            try {
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            } catch (RuntimeException ignored) {}
        }
    }

    public static void success() {
        if (tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
    }

    public static void error() {
        if (tone != null) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200);
            try { Thread.sleep(220); } catch (InterruptedException ignored) {}
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 200);
        }
    }

    public static void release() {
        if (tone != null) { tone.release(); tone = null; }
    }
}
```

- [ ] **Step 5: ToastUtil**

`pda-android/app/src/main/java/com/wms/pda/util/ToastUtil.java`:

```java
package com.wms.pda.util;

import android.content.Context;
import android.widget.Toast;

/** 统一 Toast,避免页面到处写 Toast.makeText。 */
public class ToastUtil {
    public static void show(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }
    public static void error(Context ctx, String msg) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
    }
}
```

- [ ] **Step 6: LoginActivity(完整实现)**

`pda-android/app/src/main/java/com/wms/pda/ui/login/LoginActivity.java`:

```java
package com.wms.pda.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.LoginRequest;
import com.wms.pda.model.LoginResponse;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;
import com.wms.pda.ui.home.HomeActivity;

/** 登录页 - 用户名/密码 → JWT → 存 SessionStore → 跳 Home。 */
public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etUsername, etPassword;
    private Button btnLogin;
    private SessionStore session;
    private ApiClient api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        session = new SessionStore(this);
        api = new ApiClient(session);

        // 已登录直接跳走
        if (session.getToken() != null) {
            goHome();
            return;
        }

        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);

        btnLogin.setOnClickListener(v -> doLogin());
    }

    private void doLogin() {
        String u = etUsername.getText() == null ? "" : etUsername.getText().toString().trim();
        String p = etPassword.getText() == null ? "" : etPassword.getText().toString();
        if (u.isEmpty()) { Toast.makeText(this, R.string.username_required, Toast.LENGTH_SHORT).show(); return; }
        if (p.isEmpty()) { Toast.makeText(this, R.string.password_required, Toast.LENGTH_SHORT).show(); return; }

        btnLogin.setEnabled(false);
        new Thread(() -> {
            try {
                LoginRequest req = new LoginRequest(u, p);
                // 后端返回 ApiResponse<LoginData>,我们用 Map 中转避开 DTO 嵌套
                ApiResponse<java.util.Map<String, Object>> resp =
                    api.post("/api/auth/login", req,
                        (Class) java.util.HashMap.class);
                if (resp == null || resp.data == null) {
                    throw new ApiException(ApiException.ERR_PARSE, "登录响应为空");
                }
                String token = (String) resp.data.get("token");
                String username = (String) resp.data.get("username");
                String role = (String) resp.data.get("role");
                session.setToken(token);
                session.setUsername(username);
                session.setRole(role);
                runOnUiThread(this::goHome);
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);
                    Toast.makeText(LoginActivity.this,
                        getString(R.string.login_failed) + ": " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void goHome() {
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }
}
```

- [ ] **Step 7: HomeActivity**

`pda-android/app/src/main/java/com/wms/pda/ui/home/HomeActivity.java`:

```java
package com.wms.pda.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.scan.ScanManager;
import com.wms.pda.ui.check.StockCheckActivity;
import com.wms.pda.ui.inbound.InboundScanActivity;
import com.wms.pda.ui.lookup.LookupActivity;
import com.wms.pda.ui.outbound.OutboundScanActivity;

/** 主页 - 4 个入口 + RFID 状态。 */
public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        SessionStore session = new SessionStore(this);
        TextView tvUser = findViewById(R.id.tvUser);
        TextView tvRfid = findViewById(R.id.tvRfid);
        tvUser.setText("👤 " + (session.getUsername() == null ? "?" : session.getUsername()));

        ScanManager mgr = ScanManager.get(this);
        tvRfid.setText(mgr.isUhfReady() ? R.string.rfid_ready : R.string.rfid_offline);

        findViewById(R.id.btnInbound).setOnClickListener(v ->
            startActivity(new Intent(this, InboundScanActivity.class)));
        findViewById(R.id.btnOutbound).setOnClickListener(v ->
            startActivity(new Intent(this, OutboundScanActivity.class)));
        findViewById(R.id.btnCheck).setOnClickListener(v ->
            startActivity(new Intent(this, StockCheckActivity.class)));
        findViewById(R.id.btnLookup).setOnClickListener(v ->
            startActivity(new Intent(this, LookupActivity.class)));

        findViewById(R.id.btnLogout).setOnClickListener(v -> {
            session.clear();
            startActivity(new Intent(this, com.wms.pda.ui.login.LoginActivity.class));
            finish();
        });
    }
}
```

- [ ] **Step 8: BeepUtil 测试**

`pda-android/app/src/test/java/com/wms/pda/util/BeepUtilTest.java`:

```java
package com.wms.pda.util;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class BeepUtilTest {
    @Test
    public void class_loadable() {
        assertNotNull(BeepUtil.class);
    }
}
```

- [ ] **Step 9: 编译 + 测**

```bash
cd E:/Code/SimpleWMS/pda-android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL,所有测试通过

- [ ] **Step 10: 提交**

```bash
cd E:/Code/SimpleWMS
git add pda-android/
git commit -m "feat(pda): LoginActivity + HomeActivity + 工具类

- 登录页 (用户名/密码 → JWT)
- 主页 (4 个大按钮 + RFID 状态显示)
- BeepUtil / ToastUtil

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 8: InboundScanActivity + OutboundScanActivity

**Files:**
- Create: `pda-android/app/src/main/res/layout/activity_inbound_scan.xml`
- Create: `pda-android/app/src/main/res/layout/activity_outbound_scan.xml`
- Create: `pda-android/app/src/main/res/layout/item_scan_row.xml`
- Create: `pda-android/app/src/main/java/com/wms/pda/ui/inbound/InboundScanActivity.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/ui/outbound/OutboundScanActivity.java`
- Modify: `pda-android/app/src/main/res/values/strings.xml` (追加)

- [ ] **Step 1: 追加 strings**

在 `strings.xml` 末尾追加:

```xml
    <string name="start_scan">开始扫码</string>
    <string name="manual_input">手动输入</string>
    <string name="submit">提交</string>
    <string name="location">库位</string>
    <string name="remark">备注</string>
    <string name="scanned_count">已扫 %1$d 件</string>
    <string name="code_duplicate">该码已存在</string>
    <string name="code_already_out">该码已出库</string>
    <string name="code_invalid">无效条码: %1$s</string>
    <string name="network_unavailable">网络不可达</string>
    <string name="scan_device_offline">扫码设备未就绪</string>
    <string name="scan_no_tag">未扫到标签,请重试</string>
    <string name="inbound_success">入库成功</string>
    <string name="outbound_success">出库成功</string>
```

- [ ] **Step 2: activity_inbound_scan.xml**

`pda-android/app/src/main/res/layout/activity_inbound_scan.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <com.google.android.material.textfield.TextInputLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="扫描 / 输入 24 位码">
            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/etCode"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text" />
        </com.google.android.material.textfield.TextInputLayout>

        <Button
            android:id="@+id/btnScan"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center_vertical"
            android:layout_marginStart="8dp"
            android:text="@string/start_scan" />
    </LinearLayout>

    <TextView
        android:id="@+id/tvCount"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:textSize="16sp"
        android:textStyle="bold" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvItems"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:layout_marginTop="8dp" />

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/remark">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/etRemark"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />
    </com.google.android.material.textfield.TextInputLayout>

    <Button
        android:id="@+id/btnSubmit"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:layout_marginTop="12dp"
        android:text="@string/submit" />
</LinearLayout>
```

- [ ] **Step 3: item_scan_row.xml(共用)**

`pda-android/app/src/main/res/layout/item_scan_row.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="8dp">

    <TextView
        android:id="@+id/tvSku"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="14sp" />

    <TextView
        android:id="@+id/tvName"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="2"
        android:textSize="14sp" />

    <TextView
        android:id="@+id/tvQty"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="14sp" />
</LinearLayout>
```

- [ ] **Step 4: InboundScanActivity**

`pda-android/app/src/main/java/com/wms/pda/ui/inbound/InboundScanActivity.java`:

```java
package com.wms.pda.ui.inbound;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.InboundRequest;
import com.wms.pda.model.InventoryItemDto;
import com.wms.pda.model.ProductDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;
import com.wms.pda.scan.ScanManager;
import com.wms.pda.scan.ScanType;
import com.wms.pda.scan.ScannerCallback;
import com.wms.pda.scan.ScanResult;
import com.wms.pda.util.BeepUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 入库扫码:扫 code → 查 Product → 累加数量 → 提交。 */
public class InboundScanActivity extends AppCompatActivity {

    private TextInputEditText etCode, etRemark;
    private Button btnScan, btnSubmit;
    private TextView tvCount;
    private RecyclerView rv;
    private ScanAdapter adapter;
    private final Map<Integer, ScanRow> rows = new LinkedHashMap<>();  // productId -> row

    private ApiClient api;
    private SessionStore session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inbound_scan);

        session = new SessionStore(this);
        api = new ApiClient(session);
        BeepUtil.init(this);

        etCode = findViewById(R.id.etCode);
        etRemark = findViewById(R.id.etRemark);
        btnScan = findViewById(R.id.btnScan);
        btnSubmit = findViewById(R.id.btnSubmit);
        tvCount = findViewById(R.id.tvCount);
        rv = findViewById(R.id.rvItems);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ScanAdapter();
        rv.setAdapter(adapter);
        updateCount();

        btnScan.setOnClickListener(v -> doScan());
        etCode.setOnEditorActionListener((v, id, e) -> { doScan(); return true; });
        btnSubmit.setOnClickListener(v -> doSubmit());
    }

    private void doScan() {
        String code = etCode.getText() == null ? "" : etCode.getText().toString().trim();
        if (code.isEmpty()) return;
        etCode.setText("");
        new Thread(() -> {
            try {
                // 1) 先查 inventory_item 是否已有此码(防重复)
                ApiResponse<InventoryItemDto> itemResp =
                    api.get("/api/inventory/items/by-code/" + code, InventoryItemDto.class);
                if (itemResp != null && itemResp.data != null) {
                    runOnUiThread(() -> {
                        BeepUtil.error();
                        Toast.makeText(this, R.string.code_duplicate, Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                // 2) 查 Product
                ApiResponse<ProductDto> prodResp =
                    api.get("/api/products/by-code/" + code, ProductDto.class);
                if (prodResp == null || prodResp.data == null) {
                    throw new ApiException(404, "商品未找到");
                }
                ProductDto p = prodResp.data;
                runOnUiThread(() -> {
                    BeepUtil.success();
                    ScanRow row = rows.get(p.id);
                    if (row == null) {
                        row = new ScanRow();
                        row.productId = p.id;
                        row.sku = p.sku;
                        row.name = p.name;
                        row.qty = 0;
                        rows.put(p.id, row);
                    }
                    row.qty += 1;
                    adapter.notifyDataSetChanged();
                    updateCount();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    BeepUtil.error();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void doSubmit() {
        if (rows.isEmpty()) {
            Toast.makeText(this, "请先扫码", Toast.LENGTH_SHORT).show();
            return;
        }
        btnSubmit.setEnabled(false);
        new Thread(() -> {
            try {
                InboundRequest req = new InboundRequest();
                req.remark = etRemark.getText() == null ? null : etRemark.getText().toString();
                req.items = new ArrayList<>();
                for (ScanRow r : rows.values()) {
                    InboundRequest.Item it = new InboundRequest.Item();
                    it.productId = r.productId;
                    it.quantity = r.qty;
                    req.items.add(it);
                }
                ApiResponse<Object> resp = api.post("/api/inbound/batch", req, Object.class);
                runOnUiThread(() -> {
                    BeepUtil.success();
                    Toast.makeText(this, R.string.inbound_success, Toast.LENGTH_LONG).show();
                    rows.clear();
                    adapter.notifyDataSetChanged();
                    updateCount();
                    btnSubmit.setEnabled(true);
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    BeepUtil.error();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    btnSubmit.setEnabled(true);
                });
            }
        }).start();
    }

    private void updateCount() {
        int total = 0;
        for (ScanRow r : rows.values()) total += r.qty;
        tvCount.setText(getString(R.string.scanned_count, total));
    }

    private static class ScanRow {
        Integer productId;
        String sku, name;
        int qty;
    }

    private class ScanAdapter extends RecyclerView.Adapter<ScanAdapter.VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup parent, int vt) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_scan_row, parent, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int pos) {
            ScanRow r = (ScanRow) rows.values().toArray()[pos];
            h.sku.setText(r.sku);
            h.name.setText(r.name);
            h.qty.setText("×" + r.qty);
        }
        @Override public int getItemCount() { return rows.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView sku, name, qty;
            VH(View v) {
                super(v);
                sku = v.findViewById(R.id.tvSku);
                name = v.findViewById(R.id.tvName);
                qty = v.findViewById(R.id.tvQty);
            }
        }
    }
}
```

- [ ] **Step 5: activity_outbound_scan.xml(在 inbound 基础上加 etCustomer)**

`pda-android/app/src/main/res/layout/activity_outbound_scan.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <com.google.android.material.textfield.TextInputLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="扫描 / 输入 24 位码">
            <com.google.android.material.textfield.TextInputEditText
                android:id="@+id/etCode"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:inputType="text" />
        </com.google.android.material.textfield.TextInputLayout>

        <Button
            android:id="@+id/btnScan"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center_vertical"
            android:layout_marginStart="8dp"
            android:text="@string/start_scan" />
    </LinearLayout>

    <TextView
        android:id="@+id/tvCount"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="12dp"
        android:textSize="16sp"
        android:textStyle="bold" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvItems"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:layout_marginTop="8dp" />

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="领用方(客户)">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/etCustomer"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />
    </com.google.android.material.textfield.TextInputLayout>

    <com.google.android.material.textfield.TextInputLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="@string/remark">
        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/etRemark"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />
    </com.google.android.material.textfield.TextInputLayout>

    <Button
        android:id="@+id/btnSubmit"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:layout_marginTop="12dp"
        android:text="@string/submit" />
</LinearLayout>
```

- [ ] **Step 6: OutboundScanActivity**

`pda-android/app/src/main/java/com/wms/pda/ui/outbound/OutboundScanActivity.java`:

```java
package com.wms.pda.ui.outbound;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.InventoryItemDto;
import com.wms.pda.model.OutboundRequest;
import com.wms.pda.model.ProductDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;
import com.wms.pda.util.BeepUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** 出库扫码:扫 code → 查 inventory_item → IN_STOCK 加入 → 提交。 */
public class OutboundScanActivity extends AppCompatActivity {

    private TextInputEditText etCode, etCustomer, etRemark;
    private Button btnSubmit;
    private TextView tvCount;
    private RecyclerView rv;
    private ScanAdapter adapter;
    private final Map<Integer, ScanRow> rows = new LinkedHashMap<>();

    private ApiClient api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_outbound_scan);

        api = new ApiClient(new SessionStore(this));
        BeepUtil.init(this);

        etCode = findViewById(R.id.etCode);
        etCustomer = findViewById(R.id.etCustomer);
        etRemark = findViewById(R.id.etRemark);
        btnSubmit = findViewById(R.id.btnSubmit);
        tvCount = findViewById(R.id.tvCount);
        rv = findViewById(R.id.rvItems);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ScanAdapter();
        rv.setAdapter(adapter);
        updateCount();

        findViewById(R.id.btnScan).setOnClickListener(v -> doScan());
        btnSubmit.setOnClickListener(v -> doSubmit());
    }

    private void doScan() {
        String code = etCode.getText() == null ? "" : etCode.getText().toString().trim();
        if (code.isEmpty()) return;
        etCode.setText("");
        new Thread(() -> {
            try {
                ApiResponse<InventoryItemDto> resp =
                    api.get("/api/inventory/items/by-code/" + code, InventoryItemDto.class);
                if (resp == null || resp.data == null) {
                    throw new ApiException(404, "编码不存在: " + code);
                }
                InventoryItemDto it = resp.data;
                if (!"IN_STOCK".equals(it.status)) {
                    throw new ApiException(409, "该码已出库或已报废");
                }
                runOnUiThread(() -> {
                    BeepUtil.success();
                    ScanRow row = rows.get(it.productId);
                    if (row == null) {
                        row = new ScanRow();
                        row.productId = it.productId;
                        row.sku = it.sku;
                        row.name = it.productName;
                        row.qty = 0;
                        rows.put(it.productId, row);
                    }
                    row.qty += 1;
                    adapter.notifyDataSetChanged();
                    updateCount();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    BeepUtil.error();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void doSubmit() {
        if (rows.isEmpty()) { Toast.makeText(this, "请先扫码", Toast.LENGTH_SHORT).show(); return; }
        btnSubmit.setEnabled(false);
        new Thread(() -> {
            try {
                OutboundRequest req = new OutboundRequest();
                req.customer = etCustomer.getText() == null ? null : etCustomer.getText().toString();
                req.remark = etRemark.getText() == null ? null : etRemark.getText().toString();
                req.items = new ArrayList<>();
                for (ScanRow r : rows.values()) {
                    OutboundRequest.Item it = new OutboundRequest.Item();
                    it.productId = r.productId;
                    it.quantity = r.qty;
                    req.items.add(it);
                }
                ApiResponse<Object> resp = api.post("/api/outbound/batch", req, Object.class);
                runOnUiThread(() -> {
                    BeepUtil.success();
                    Toast.makeText(this, R.string.outbound_success, Toast.LENGTH_LONG).show();
                    rows.clear();
                    adapter.notifyDataSetChanged();
                    updateCount();
                    btnSubmit.setEnabled(true);
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    BeepUtil.error();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    btnSubmit.setEnabled(true);
                });
            }
        }).start();
    }

    private void updateCount() {
        int total = 0;
        for (ScanRow r : rows.values()) total += r.qty;
        tvCount.setText(getString(R.string.scanned_count, total));
    }

    private static class ScanRow {
        Integer productId;
        String sku, name;
        int qty;
    }

    private class ScanAdapter extends RecyclerView.Adapter<ScanAdapter.VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup parent, int vt) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_scan_row, parent, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int pos) {
            ScanRow r = (ScanRow) rows.values().toArray()[pos];
            h.sku.setText(r.sku);
            h.name.setText(r.name);
            h.qty.setText("×" + r.qty);
        }
        @Override public int getItemCount() { return rows.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView sku, name, qty;
            VH(View v) {
                super(v);
                sku = v.findViewById(R.id.tvSku);
                name = v.findViewById(R.id.tvName);
                qty = v.findViewById(R.id.tvQty);
            }
        }
    }
}
```

- [ ] **Step 7: 编译**

```bash
cd E:/Code/SimpleWMS/pda-android
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 提交**

```bash
cd E:/Code/SimpleWMS
git add pda-android/
git commit -m "feat(pda): 入库 + 出库扫码 Activity

- InboundScanActivity: 扫 code → 查 Product → 累加 → 批量入库
- OutboundScanActivity: 扫 code → 查 inventory_item → IN_STOCK 加入 → 批量出库
- 共用 item_scan_row layout

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 9: StockCheckActivity + LookupActivity

**Files:**
- Create: `pda-android/app/src/main/res/layout/activity_stock_check.xml`
- Create: `pda-android/app/src/main/res/layout/activity_lookup.xml`
- Create: `pda-android/app/src/main/res/layout/item_stock_check_row.xml`
- Create: `pda-android/app/src/main/java/com/wms/pda/ui/check/StockCheckActivity.java`
- Create: `pda-android/app/src/main/java/com/wms/pda/ui/lookup/LookupActivity.java`

- [ ] **Step 1: activity_stock_check.xml**

`pda-android/app/src/main/res/layout/activity_stock_check.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <Spinner
            android:id="@+id/spScope"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1" />

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/etKeyword"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="关键字(库位/SKU)" />

        <Button
            android:id="@+id/btnCreate"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="创建盘点" />
    </LinearLayout>

    <TextView
        android:id="@+id/tvProgress"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="8dp"
        android:textSize="16sp"
        android:textStyle="bold" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rv"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:layout_marginTop="8dp" />

    <Button
        android:id="@+id/btnCommit"
        android:layout_width="match_parent"
        android:layout_height="56dp"
        android:text="提交盘点" />
</LinearLayout>
```

- [ ] **Step 2: item_stock_check_row.xml**

`pda-android/app/src/main/res/layout/item_stock_check_row.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="8dp">

    <TextView
        android:id="@+id/tvCode"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="2"
        android:fontFamily="monospace"
        android:textSize="13sp" />

    <TextView
        android:id="@+id/tvSku"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="2"
        android:textSize="13sp" />

    <TextView
        android:id="@+id/tvSystem"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="系统 0" />

    <com.google.android.material.textfield.TextInputEditText
        android:id="@+id/etCounted"
        android:layout_width="60dp"
        android:layout_height="wrap_content"
        android:inputType="number"
        android:textSize="13sp" />
</LinearLayout>
```

- [ ] **Step 3: StockCheckActivity**

`pda-android/app/src/main/java/com/wms/pda/ui/check/StockCheckActivity.java`:

```java
package com.wms.pda.ui.check;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.StockCheckDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 库存盘点:创建盘点单 → 逐条填实盘数 → 提交。 */
public class StockCheckActivity extends AppCompatActivity {

    private Spinner spScope;
    private TextInputEditText etKeyword;
    private Button btnCreate, btnCommit;
    private TextView tvProgress;
    private RecyclerView rv;
    private CheckAdapter adapter;
    private final List<StockCheckDto.Item> items = new ArrayList<>();
    private StockCheckDto currentCheck;
    private ApiClient api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stock_check);

        api = new ApiClient(new SessionStore(this));

        spScope = findViewById(R.id.spScope);
        etKeyword = findViewById(R.id.etKeyword);
        btnCreate = findViewById(R.id.btnCreate);
        btnCommit = findViewById(R.id.btnCommit);
        tvProgress = findViewById(R.id.tvProgress);
        rv = findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CheckAdapter();
        rv.setAdapter(adapter);

        spScope.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"全部", "按SKU", "按库位"}));
        btnCreate.setOnClickListener(v -> createCheck());
        btnCommit.setOnClickListener(v -> commit());
    }

    private void createCheck() {
        String[] scopes = {"ALL", "SKU", "LOCATION"};
        String scope = scopes[spScope.getSelectedItemPosition()];
        String keyword = etKeyword.getText() == null ? null : etKeyword.getText().toString().trim();

        Map<String, String> body = new HashMap<>();
        body.put("scope", scope);
        if (keyword != null && !keyword.isEmpty()) body.put("keyword", keyword);

        btnCreate.setEnabled(false);
        new Thread(() -> {
            try {
                ApiResponse<StockCheckDto> resp =
                    api.post("/api/stock-checks", body, StockCheckDto.class);
                currentCheck = resp.data;
                items.clear();
                if (currentCheck != null && currentCheck.items != null) {
                    items.addAll(currentCheck.items);
                }
                runOnUiThread(() -> {
                    adapter.notifyDataSetChanged();
                    btnCreate.setEnabled(true);
                    updateProgress();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    btnCreate.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void commit() {
        if (currentCheck == null) {
            Toast.makeText(this, "请先创建盘点单", Toast.LENGTH_SHORT).show();
            return;
        }
        btnCommit.setEnabled(false);
        new Thread(() -> {
            try {
                // 逐条提交实盘数
                for (int i = 0; i < items.size(); i++) {
                    StockCheckDto.Item it = items.get(i);
                    if (it.countedQty == null) continue;
                    Map<String, Object> body = new HashMap<>();
                    body.put("countedQty", it.countedQty);
                    api.post("/api/stock-checks/" + currentCheck.id +
                            "/items/" + it.id + "/count", body, Object.class);
                }
                // 提交盘点
                api.post("/api/stock-checks/" + currentCheck.id + "/commit", null, Object.class);
                runOnUiThread(() -> {
                    Toast.makeText(this, "盘点已提交", Toast.LENGTH_LONG).show();
                    btnCommit.setEnabled(true);
                    finish();
                });
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    btnCommit.setEnabled(true);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void updateProgress() {
        if (currentCheck == null) { tvProgress.setText(""); return; }
        int counted = 0;
        for (StockCheckDto.Item it : items) if (it.countedQty != null) counted++;
        tvProgress.setText(String.format("进度 %d/%d 差 %d",
                counted, currentCheck.totalCount, currentCheck.diffCount));
    }

    private class CheckAdapter extends RecyclerView.Adapter<CheckAdapter.VH> {
        @Override public VH onCreateViewHolder(ViewGroup p, int vt) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.item_stock_check_row, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int pos) {
            StockCheckDto.Item it = items.get(pos);
            h.code.setText(it.code);
            h.sku.setText(it.sku);
            h.system.setText("系统 " + it.systemQty);
            h.counted.setText(it.countedQty == null ? "" : String.valueOf(it.countedQty));
            h.counted.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    try {
                        it.countedQty = s.toString().isEmpty() ? null : Integer.parseInt(s.toString());
                    } catch (NumberFormatException e) {
                        it.countedQty = null;
                    }
                    updateProgress();
                }
            });
        }
        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView code, sku, system;
            TextInputEditText counted;
            VH(View v) {
                super(v);
                code = v.findViewById(R.id.tvCode);
                sku = v.findViewById(R.id.tvSku);
                system = v.findViewById(R.id.tvSystem);
                counted = v.findViewById(R.id.etCounted);
            }
        }
    }
}
```

- [ ] **Step 4: activity_lookup.xml**

`pda-android/app/src/main/res/layout/activity_lookup.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <com.google.android.material.textfield.TextInputEditText
            android:id="@+id/etCode"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="24 位码 / EPC" />

        <Button
            android:id="@+id/btnQuery"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="查询" />
    </LinearLayout>

    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:layout_marginTop="16dp">

        <TextView
            android:id="@+id/tvResult"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:fontFamily="monospace"
            android:textSize="14sp" />
    </ScrollView>
</LinearLayout>
```

- [ ] **Step 5: LookupActivity**

`pda-android/app/src/main/java/com/wms/pda/ui/lookup/LookupActivity.java`:

```java
package com.wms.pda.ui.lookup;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;
import com.wms.pda.R;
import com.wms.pda.data.SessionStore;
import com.wms.pda.model.ApiResponse;
import com.wms.pda.model.InventoryItemDto;
import com.wms.pda.net.ApiClient;
import com.wms.pda.net.ApiException;

/** 扫码查询:输入或扫码 → 查 inventory_item → 显示详情。 */
public class LookupActivity extends AppCompatActivity {

    private TextInputEditText etCode;
    private TextView tvResult;
    private ApiClient api;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lookup);
        api = new ApiClient(new SessionStore(this));
        etCode = findViewById(R.id.etCode);
        tvResult = findViewById(R.id.tvResult);
        findViewById(R.id.btnQuery).setOnClickListener(v -> doQuery());
        etCode.setOnEditorActionListener((v, id, e) -> { doQuery(); return true; });
    }

    private void doQuery() {
        String code = etCode.getText() == null ? "" : etCode.getText().toString().trim();
        if (code.isEmpty()) return;
        etCode.setText("");
        new Thread(() -> {
            try {
                ApiResponse<InventoryItemDto> resp =
                    api.get("/api/inventory/items/by-code/" + code, InventoryItemDto.class);
                if (resp == null || resp.data == null) {
                    throw new ApiException(404, "编码不存在: " + code);
                }
                InventoryItemDto it = resp.data;
                StringBuilder sb = new StringBuilder();
                sb.append("编码:     ").append(it.code).append('\n');
                sb.append("SKU:      ").append(it.sku).append('\n');
                sb.append("商品:     ").append(it.productName).append('\n');
                sb.append("库位:     ").append(it.locationCode).append('\n');
                sb.append("数量:     ").append(it.quantity).append(" (剩 ").append(it.remaining).append(")\n");
                sb.append("状态:     ").append(statusLabel(it.status)).append('\n');
                sb.append("入库单:   ").append(it.inboundOrderId == null ? "-" : it.inboundOrderId).append('\n');
                sb.append("出库单:   ").append(it.outboundOrderId == null ? "-" : it.outboundOrderId).append('\n');
                sb.append("创建人:   ").append(it.createdBy == null ? "-" : it.createdBy).append('\n');
                sb.append("创建时间: ").append(it.createdAt == null ? "-" : it.createdAt).append('\n');
                runOnUiThread(() -> tvResult.setText(sb.toString()));
            } catch (ApiException e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                    tvResult.setText("");
                });
            }
        }).start();
    }

    private static String statusLabel(String s) {
        if (s == null) return "-";
        switch (s) {
            case "IN_STOCK":    return "在库";
            case "OUT":         return "已出库";
            case "SCRAPPED":    return "已报废";
            case "TRANSFERRED": return "已调拨";
            default: return s;
        }
    }
}
```

- [ ] **Step 6: 编译 + 测**

```bash
cd E:/Code/SimpleWMS/pda-android
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL,所有测试通过

- [ ] **Step 7: 提交**

```bash
cd E:/Code/SimpleWMS
git add pda-android/
git commit -m "feat(pda): StockCheckActivity + LookupActivity

- 库存盘点: 选范围 → 创建盘点单 → 填实盘数 → 提交
- 扫码查询: 输码或扫码 → 查 inventory_item → 显示完整信息

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 10: 构建 + 签名 + 部署文档

**Files:**
- Modify: `README.md` (追加 PDA App 构建段)
- Create: `pda-android/INSTALL.md` (PDA 安装手册)

- [ ] **Step 1: 追加 README**

在 `E:\Code\SimpleWMS\README.md` 末尾追加:

```markdown
## 📱 PDA Android 手持机 App

仓库管理员用 Android 工业 PDA(PDA/UHF 模组)扫码出入库。

### 构建

```bash
cd pda-android
./gradlew assembleDebug       # 调试 APK
./gradlew assembleRelease     # 发布 APK(签名)
```

### 部署到 PDA

```bash
# USB 连接 PDA(开启 USB 调试)
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 配置后端地址

`pda-android/app/build.gradle`:
```gradle
buildConfigField "String", "API_BASE_URL", "\"http://192.168.1.100:8080\""
```

模拟器用 `http://10.0.2.2:8080`,真机改成后端实际 IP。

### 业务流程

- **入库扫码**:扫 code → 查 Product → 累加数量 → 批量入库
- **出库扫码**:扫 code → 查 inventory_item → IN_STOCK 加入 → 批量出库
- **库存盘点**:选范围 → 创建盘点单 → 填实盘数 → 提交
- **扫码查询**:输码或扫码 → 显示库存单元完整信息

EPC = 24 位 code,后端 `inventory_item.code` UNIQUE 索引已支持。
```

- [ ] **Step 2: 写 INSTALL.md**

`pda-android/INSTALL.md`:

```markdown
# PDA 安装手册

## 1. 准备工作

- 一台 Android 工业 PDA(支持 UHF RFID 模组)
- USB 数据线
- 电脑装好 Android SDK Platform Tools(`adb` 命令)
- 后端服务已启动,WiFi 或 USB 共享网络可通

## 2. 启用 USB 调试

PDA → 设置 → 关于本机 → 连点 7 次「版本号」→ 打开「开发者选项」→ 开启「USB 调试」

## 3. 安装 APK

```bash
adb install -r app-release.apk
```

第一次安装会请求存储 / 网络权限,允许。

## 4. 配置后端地址

启动 App → 设置(暂未提供 UI 入口)→ 手动改 `pda-android/app/build.gradle` 的 `API_BASE_URL` 后重装。

或用 `adb` 直接写 SharedPreferences:

```bash
adb shell run-as com.wms.pda \
    sh -c 'echo "base_url=http://192.168.1.100:8080" >> /data/data/com.wms.pda/shared_prefs/wms_pda_session.xml'
```

(注:此操作需要在 debug 模式下)

## 5. 登录

默认账号:
- 用户名:`admin`
- 密码:`change-me-now`

## 6. 故障排查

- **登录失败 / 网络不可达** — 检查 PDA 与后端是否同网段
- **扫码无反应** — 检查 UHF 模组是否上电(部分 PDA 需长按扫描键启动)
- **入库时"该码已存在"** — 24 位 code 必须唯一;已存在的码属于历史入库
- **盘点失败 "无权限"** — PDA 用户需 ADMIN 角色,在 PC 端「用户管理」改
```

- [ ] **Step 3: 构建 release APK**

```bash
cd E:/Code/SimpleWMS/pda-android
./gradlew clean assembleRelease
ls -la app/build/outputs/apk/release/
```

Expected: `app-release.apk` 存在

- [ ] **Step 4: 用 apksigner 验证签名**

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs \
    pda-android/app/build/outputs/apk/release/app-release.apk
```

Expected: 显示证书指纹(对应 uhf-serial-key)

- [ ] **Step 5: 提交**

```bash
cd E:/Code/SimpleWMS
git add README.md pda-android/INSTALL.md
git commit -m "docs: PDA App 构建 + 安装手册

- README 追加 PDA 章节
- INSTALL.md 详细部署步骤
- 验证 release APK 签名通过

Co-Authored-By: Claude <noreply@anthropic.com>"
```

- [ ] **Step 6: 端到端手动测试**

按 INSTALL.md 流程,装到真实 PDA(或模拟器):
- [ ] 登录 admin / change-me-now
- [ ] 入库扫码:扫 3 个不同 code → 提交 → 后端库存增加
- [ ] 出库扫码:扫 1 个 code → 提交 → 后端库存减少
- [ ] 库存盘点:创建盘点 → 填实盘数 → 提交 → 看到差异
- [ ] 扫码查询:输码 → 显示完整信息

---

## 总结

- 10 个任务,每个独立可测可提交
- 后端只补 4 个 GET,不破坏现有
- Android 独立项目,Gradle wrapper 自带
- 签名复用 uhf-uart-demo 密钥
- EPC = 24 位 code,无需 schema 变更
