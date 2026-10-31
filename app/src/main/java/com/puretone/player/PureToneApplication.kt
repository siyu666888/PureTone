package com.puretone.player

import android.app.Application
import android.util.Log
import coil.Coil
import coil.ImageLoader
import com.puretone.player.BuildConfig
import com.puretone.player.playback.cast.DlnaCastManager
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

    /** DLNA 投屏引擎（M1）：Application 级初始化一次，进程退出由 onTerminate 释放 */
    @Inject
    lateinit var dlnaCastManager: DlnaCastManager

    override fun onCreate() {
        // Hilt 在 super.onCreate() 之前完成 Application 的字段注入，此处可安全使用
        super.onCreate()
        appInstance = this
        Coil.setImageLoader(imageLoader)
        dlnaCastManager.init(this)
        installCrashLogger()
    }

    override fun onTerminate() {
        dlnaCastManager.release()
        super.onTerminate()
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
            writeCrashInternal("uncaught", thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 落盘实现（同时供协程/流异常处理器复用，确保「不闪退也能拿到栈」） */
    private fun writeCrashInternal(tag: String, thread: Thread?, throwable: Throwable) {
        val path = runCatching {
            val file = File(filesDir, CRASH_FILE)
            PrintWriter(file).use { pw ->
                pw.println("time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                pw.println("thread: " + (thread?.name ?: Thread.currentThread().name))
                pw.println("version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                pw.println("tag: " + tag)
                pw.println()
                throwable.printStackTrace(pw)
            }
            file.absolutePath
        }.getOrNull()
        Log.e(CRASH_TAG, "Exception [$tag] on " + (thread?.name ?: Thread.currentThread().name) +
            "; trace: " + (path ?: "n/a"), throwable)
    }

    companion object {
        const val CRASH_TAG = "PureToneCrash"
        const val CRASH_FILE = "crash_last.txt"

        @Volatile
        private var appInstance: PureToneApplication? = null

        /**
         * 供协程异常处理器 / 流 catch 调用：把异常写入同一份 crash_last.txt，
         * 并打 PureToneCrash 日志。调用方在捕获后**不**重新抛出，避免闪退，
         * 但异常已被完整记录，真机可 pull 该文件定位根因。
         */
        fun writeCrash(tag: String, throwable: Throwable) {
            appInstance?.writeCrashInternal(tag, null, throwable)
                ?: Log.e(CRASH_TAG, "writeCrash before Application.onCreate ($tag)", throwable)
        }
    }
}
