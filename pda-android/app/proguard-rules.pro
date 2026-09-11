# ============================================================================
# CHAINWAY DeviceAPI SDK (libs/DeviceAPI_ver20230301_release.aar)
# UHF RFID + 硬件条码扫把头
# ============================================================================

# 核心 API
-keep class com.rscja.deviceapi.** { *; }

# 平台实现层 — 反射 + ServiceLoader 加载, R8 看不到直接引用会剥掉
-keep class com.rscja.team.** { *; }
-keep class com.rscja.utility.** { *; }
-keep class com.rscja.CWDeviceInfo { *; }
-keep class com.rscja.system.** { *; }

# 硬件扫码 SDK (BarcodeDecoder / BarcodeFactory / BarcodeUtility)
-keep class com.rscja.barcode.** { *; }

# SDK 内部引用的其他平台条码引擎(反射加载, 设备上只跑其中一套, 其它是死引用)
-dontwarn com.datalogic.iptech.evl.**
-dontwarn com.imagealgorithmlab.barcode.**
-dontwarn com.rscja.cwq.decodeapi.**
-dontwarn vendor.mediatek.hardware.zebra.**

# DTO
-keep class com.wms.pda.model.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
