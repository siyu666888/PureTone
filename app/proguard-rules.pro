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
# 显式保留整个 media3 包（session / exoplayer / common 等）。
# 虽然 media3 AAR 自带 consumer proguard 规则，但升级到 1.6.0 后播放栈（双 ExoPlayer +
# 自定义 DefaultAudioSink / DefaultRenderersFactory + AudioProcessor 链）在 release 包里若被
# R8 误删内部类，会在 MediaSessionService 启动期抛 NoClassDefFoundError / VerifyError 直接闪退。
# 一次性全量 keep，彻底排除「版本升级引入的 R8 剥离」这一类兼容性冲突。
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---------- Glance 桌面小部件 ----------
# ActionCallback 由 Glance 的 ActionCallbackBroadcastReceiver 按类名反射实例化，
# 构造函数与类名都必须保留——否则 release 包里点桌面按钮毫无反应（还在发展需要的日志都没有）。
-keep class androidx.glance.appwidget.action.ActionCallback
-keep class * extends androidx.glance.appwidget.action.ActionCallback { *; }
-keep class com.puretone.player.widget.** { *; }
-dontwarn androidx.glance.**

# ---------- 通用 ----------
# 保留行号，便于崩溃栈还原
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- DLNA 投屏（UPnPCast） ----------
# 库内含 SSDP 组播、NanoHTTPD 本地文件服务与 SOAP 控制；保留其全部类，
# 防止 R8 误删反射/枚举/回调入口导致投屏功能在 release 包失效。
-keep class com.yinnho.upnpcast.** { *; }
-dontwarn com.yinnho.**

# Kotlin 协程
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
