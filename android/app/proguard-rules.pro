# LinNAS release 混淆规则（R8）
# release 构建启用 isMinifyEnabled；资源压缩保持关闭，避免按名称动态引用的资源被移除。

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions, SourceFile, LineNumberTable, MethodParameters
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Kotlin 元数据 / 协程
-keep class kotlin.Metadata { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# 应用数据模型（org.json 手动映射；保留便于兼容与排查）
-keep class com.linnas.app.Models { *; }
-keep class com.linnas.app.Models$* { *; }

# Android 组件入口（manifest 引用；显式兜底）
-keep class com.linnas.app.LinNasApp { *; }
-keep class com.linnas.app.MainActivity { *; }
-keep class com.linnas.app.LoginActivity { *; }
-keep class com.linnas.app.PlayerActivity { *; }
-keep class com.linnas.app.CrashActivity { *; }
-keep class com.linnas.app.transfer.TransferService { *; }
-keep class com.linnas.app.upload.UploadService { *; }

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil
-dontwarn coil.**

# ZXing（扫码）
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# 反射 / 序列化兜底
-keepclassmembers enum * { *; }
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclasseswithmembernames class * {
    native <methods>;
}
