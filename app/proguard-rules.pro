# PureTone ProGuard / R8 规则

# ---------- ffmpeg-kit（APE 转码回退） ----------
# AAR 以 files() 方式引入，其自带的 consumer proguard.txt 不会生效，规则须在此重述。
# FFmpegKitConfig / AbiDetect 含 JNI 回调方法（native <methods> 与 statistics/log/safOpen/safClose
# 由 native 代码反射回调），R8 一旦重命名或删除即导致 UnsatisfiedLinkError / 回调丢失。
-keep class com.arthenica.ffmpegkit.FFmpegKitConfig {
    native <methods>;
    void log(long, int, byte[]);
    void statistics(long, int, float, float, long, double, double, double);
    int safOpen(int);
    int safClose(int);
}
-keep class com.arthenica.ffmpegkit.AbiDetect {
    native <methods>;
}
# smartexception 传递依赖（注意实际包名 com.arthenica.smartexception，无点）
-keep class com.arthenica.smartexception.** { *; }
-dontwarn com.arthenica.**

# ---------- Media3 ----------
# MediaSessionService 等组件经 Manifest 引用，默认保留；
# 这里显式保留回调接口，防止 R8 误删反射调用入口
-keep class androidx.media3.session.** { *; }
-dontwarn androidx.media3.**

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---------- 通用 ----------
# 保留行号，便于崩溃栈还原
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin 协程
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
