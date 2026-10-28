package com.puretone.player

import android.app.Application
import android.util.Log
import coil.Coil
import coil.ImageLoader
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * PureTone 应用入口。
 * 使用 Hilt 依赖注入（@HiltAndroidApp），并安装崩溃日志落盘。
 */
@HiltAndroidApp
class PureToneApplication : Application() {

    /** 封面加载器：全局注册后，`AsyncImage` 无需显式传 model loader 即走缓存 */
    @Inject
    lateinit var imageLoader: ImageLoader

    override fun onCreate() {
        // Hilt 在 super.onCreate() 之前完成 Application 的字段注入，此处可安全使用
        super.onCreate()
        Coil.setImageLoader(imageLoader)
        installCrashLogger()
    }

    /**
     * 捕获未处理异常并写入 filesDir/crash_last.txt。
     *
     * 真机闪退时通常没有 IDE 连着、Logcat 不好取；落盘后可通过
     * adb pull /data/data/com.puretone.player/files/crash_last.txt
     * 或 `adb logcat -s PureToneCrash` 取回堆栈。
     *
     * 注意：仅能捕获 Java 层异常；native 崩溃（如音频底层 SIGSEGV）不会走这里。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val path = runCatching {
                val file = File(filesDir, CRASH_FILE)
                PrintWriter(file).use { pw ->
                    pw.println("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    pw.println("thread: " + thread.name)
                    pw.println("version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                    pw.println()
                    throwable.printStackTrace(pw)
                }
                file.absolutePath
            }.getOrNull()

            Log.e(CRASH_TAG, "Uncaught exception on " + thread.name + "; trace: " + path, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_TAG = "PureToneCrash"
        const val CRASH_FILE = "crash_last.txt"
    }
}
