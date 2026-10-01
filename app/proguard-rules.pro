# 《道枢罗盘》混淆规则

# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.daoshu.compass.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.daoshu.compass.data.update.**$$serializer { *; }
-keepclassmembers class com.daoshu.compass.data.update.** {
    *** Companion;
}
-keepclasseswithmembers class com.daoshu.compass.data.update.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 传感器与定位数据模型在反射/调试中保持可读
-keep class com.daoshu.compass.data.sensor.** { *; }
-keep class com.daoshu.compass.data.location.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Hilt / Dagger 由官方规则处理，这里仅保留内置
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# 保留行号，便于线上崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
