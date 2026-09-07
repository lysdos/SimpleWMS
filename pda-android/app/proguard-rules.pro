# 厂商 SDK(优博讯 UROVO RFID,URFIDLibrary v2.4.1226)
-keep class com.ubx.** { *; }
-keep class com.ubx.usdk.** { *; }
-dontwarn com.ubx.usdk.**
-dontwarn vendor.**
-dontwarn com.mediatek.**
-dontwarn android.hardware.**
# UROVO SDK 引用 UROVO ROM 自带的隐藏类(普通 Android 没有)
-dontwarn android.device.**
-dontwarn com.ubx.rfid.**
-dontwarn com.ubx.utils.**

# DTO
-keep class com.wms.pda.model.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
