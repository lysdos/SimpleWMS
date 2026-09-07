# 厂商 SDK(东集 iData DeviceAPI)
-keep class com.rscja.** { *; }
-keep class com.rscja.team.** { *; }
-dontwarn com.rscja.team.**
-dontwarn vendor.**
-dontwarn com.mediatek.**
-dontwarn android.hardware.**

# DTO
-keep class com.wms.pda.model.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
