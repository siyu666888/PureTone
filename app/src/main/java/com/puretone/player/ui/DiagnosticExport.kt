package com.puretone.player.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.puretone.player.BuildConfig
import com.puretone.player.PureToneApplication
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
        sb.appendLine("==== PureTone 诊断报告 ====")
        sb.appendLine("生成时间: $now")
        sb.appendLine()
        sb.appendLine("---- 应用 ----")
        sb.appendLine("包名: ${context.packageName}")
        sb.appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("构建类型: ${BuildConfig.BUILD_TYPE}")
        sb.appendLine("调试构建: ${BuildConfig.DEBUG}")
        sb.appendLine()
        sb.appendLine("---- 设备 ----")
        sb.appendLine("厂商: ${Build.MANUFACTURER}")
        sb.appendLine("型号: ${Build.MODEL}")
        sb.appendLine("产品: ${Build.PRODUCT}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        sb.appendLine("区域: ${Locale.getDefault()}")
        sb.appendLine()
        sb.appendLine("---- 最近崩溃记录 (${PureToneApplication.CRASH_FILE}) ----")
        sb.appendLine(readCrash(context) ?: "(无崩溃记录)")
        sb.appendLine()
        sb.appendLine("---- 最近一次 FFmpeg 命令输出 ----")
        sb.appendLine(readLastFfmpegOutput())
        return sb.toString()
    }

    private fun readCrash(context: Context): String? = runCatching {
        val file = File(context.filesDir, PureToneApplication.CRASH_FILE)
        if (file.exists()) file.readText() else null
    }.getOrNull()

    private fun readLastFfmpegOutput(): String = runCatching {
        val out = FFmpegKitConfig.getLastSession()?.output
        if (out.isNullOrBlank()) "(无)" else out.take(4000) // 截断，避免报告过长
    }.getOrDefault("(读取失败)")

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
        val chooser = Intent.createChooser(intent, "导出诊断日志")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }
}
