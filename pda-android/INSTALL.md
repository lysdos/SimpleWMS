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