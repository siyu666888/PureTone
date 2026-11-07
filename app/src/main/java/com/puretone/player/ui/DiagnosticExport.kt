package com.puretone.player.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.puretone.player.BuildConfig
import com.puretone.player.PureToneApplication
import com.puretone.player.R
import com.puretone.player.playback.PlaybackEventLog
import com.puretone.player.playback.cast.CastNetworkLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P3-1 崩溃诊断导出。
 *
 * 把 `crash_last.txt`（Java 层未捕获异常 / 协程 / 流异常落盘，见 [PureToneApplication]）
 * 连同设备与应用元信息、最近一次 FFmpeg 命令输出打包成一份可读报告，经系统分享面板导出。
 *
 * 设计取舍：
 * - 经 FileProvider + 系统分享面板导出，**无需任何存储权限**、**不依赖任何统计/崩溃上报 SDK**，
 *   符合 PRIVACY.md 的「无崩溃上报」立场；普通用户也能把报告发给开发者协助定位。
 * - native 崩溃（FFmpeg / 音频底层 SIGSEGV）不走 Java 的 UncaughtExceptionHandler，
 *   应用内无法捕获；报告仅附「最近一次 FFmpeg 命令输出」以辅助定位 APE/转码相关问题，
 *   真正的 native 栈仍需 logcat 或系统 tombstone。
 */
object DiagnosticExport {
    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val DIAG_DIR = "diagnostics"

    /** 生成诊断报告纯文本（不写盘，便于预览/测试）。 */
    fun buildReport(context: Context): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.appendLine(context.getString(R.string.pt_diag_001))
        sb.appendLine(context.getString(R.string.pt_diag_002, now))
        sb.appendLine()
        sb.appendLine(context.getString(R.string.pt_diag_003))
        sb.appendLine(context.getString(R.string.pt_diag_004, context.packageName))
        sb.appendLine(context.getString(R.string.pt_diag_005, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        sb.appendLine(context.getString(R.string.pt_diag_006, BuildConfig.BUILD_TYPE))
        sb.appendLine(context.getString(R.string.pt_diag_007, BuildConfig.DEBUG))
        sb.appendLine()
        sb.appendLine(context.getString(R.string.pt_diag_008))
        sb.appendLine(context.getString(R.string.pt_diag_009, Build.MANUFACTURER))
        sb.appendLine(context.getString(R.string.pt_diag_010, Build.MODEL))
        sb.appendLine(context.getString(R.string.pt_diag_011, Build.PRODUCT))
        sb.appendLine(context.getString(R.string.pt_diag_012, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
        sb.appendLine(context.getString(R.string.pt_diag_013, Build.SUPPORTED_ABIS.joinToString()))
        sb.appendLine(context.getString(R.string.pt_diag_014, Locale.getDefault()))
        sb.appendLine()
        appendBackgroundSelfCheck(context, sb)
        sb.appendLine()
        // 本地播放音频自检（2026-11-07 蓝牙后台「自动暂停」）：焦点事件类型、
        // 输出设备类型与音量三态。判读说明见 LocalAudioDiagnostics / pt_diag_038。
        LocalAudioDiagnostics.append(context, sb)
        sb.appendLine()
        sb.appendLine(context.getString(R.string.pt_diag_015, PureToneApplication.CRASH_FILE))
        sb.appendLine(readCrash(context) ?: context.getString(R.string.pt_diag_016))
        sb.appendLine()
        sb.appendLine(context.getString(R.string.pt_diag_017))
        sb.appendLine(readLastFfmpegOutput(context))
        sb.appendLine()
        // 播放事件记录（P3-6）：崩溃堆栈回答不了「为什么播放自己停了」，
        // 只有这条时间线能。判读方法见 PlaybackEventLog 类注释。
        sb.appendLine(context.getString(R.string.pt_diag_021, PlaybackEventLog.FILE_NAME))
        sb.appendLine(readPlaybackEvents(context))
        sb.appendLine()
        // 投屏网络诊断：回答「为什么真机发现不到设备」。
        // 关键事实（网络类型/本机 IPv4/组播锁/发现数）全在这一段里，
        // 没有它就只能靠猜。判读方法见 CastNetworkLog 类注释。
        sb.appendLine(context.getString(R.string.pt_diag_024, CastNetworkLog.FILE_NAME))
        sb.appendLine(readCastNetwork(context))
        return sb.toString()
    }

    /**
     * 后台投屏自检（2026-11-06 20:08 真机）。
     *
     * 投屏是 push 模式，手机同时是电视的 HTTP 音源与 1Hz 轮询遥控器；进程一旦被
     * 系统冻结，电视会缓冲耗尽后 PAUSED / STOPPED（用户看到的「电视退出投屏」），
     * 曲末也不会自动续投，直到切回前台。WakeLock 只保证 CPU 不睡，Doze 应用待机
     * 与国产 ROM 的后台冻结都不看 WakeLock，必须靠电池优化白名单放行。
     * 那次诊断里四个保活锁全 true、`own-poll-null quiet=176747ms failures=1`，
     * 就是「锁在但进程仍被冻结」——把这两项直接写进报告，下次一眼可判。
     */
    private fun appendBackgroundSelfCheck(context: Context, sb: StringBuilder) {
        // 诊断文本用「是/否/未知」三态：读取失败不能伪装成「否」，否则会误导排查
        fun label(value: Boolean?): String = when (value) {
            true -> "是"
            false -> "否"
            null -> "未知"
        }
        val pm = runCatching {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        }.getOrNull()
        val ignored = runCatching {
            pm?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()
        val idle = runCatching { pm?.isDeviceIdleMode }.getOrNull()
        sb.appendLine(context.getString(R.string.pt_diag_026))
        sb.appendLine(context.getString(R.string.pt_diag_027, label(ignored)))
        sb.appendLine(context.getString(R.string.pt_diag_028, label(idle)))
        sb.appendLine(context.getString(R.string.pt_diag_029))
    }

    private fun readCastNetwork(context: Context): String = runCatching {
        CastNetworkLog.read(context).orEmpty().ifBlank { context.getString(R.string.pt_diag_025) }
    }.getOrDefault(context.getString(R.string.pt_diag_025))

    private fun readPlaybackEvents(context: Context): String = runCatching {
        PlaybackEventLog.read().ifBlank { context.getString(R.string.pt_diag_022) }
    }.getOrDefault(context.getString(R.string.pt_diag_023))

    private fun readCrash(context: Context): String? = runCatching {
        val file = File(context.filesDir, PureToneApplication.CRASH_FILE)
        if (file.exists()) file.readText() else null
    }.getOrNull()

    private fun readLastFfmpegOutput(context: Context): String = runCatching {
        val out = FFmpegKitConfig.getLastSession()?.output
        if (out.isNullOrBlank()) context.getString(R.string.pt_diag_018) else out.take(4000) // 截断，避免报告过长
    }.getOrDefault(context.getString(R.string.pt_diag_019))

    /** 写报告到 cacheDir/diagnostics/，返回文件；失败抛异常由调用方处理。 */
    fun writeReport(context: Context): File {
        val dir = File(context.cacheDir, DIAG_DIR).apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "puretone_diagnostic_$ts.txt")
        file.writeText(buildReport(context))
        return file
    }

    /** 经系统分享面板导出报告文件（授予目标应用临时读权限）。 */
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.pt_diag_020))
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }
}
