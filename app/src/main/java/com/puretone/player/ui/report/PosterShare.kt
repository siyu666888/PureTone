package com.puretone.player.ui.report

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.puretone.player.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 听歌报告海报分享（需求4 阶段2 6.6）。
 *
 * 渲染 → 写 cacheDir/posters/（免存储权限）→ 经系统分享面板导出 PNG。
 * 复用 [com.puretone.player.ui.DiagnosticExport] 的 FileProvider 模式，仅在
 * `res/xml/file_paths.xml` 多了 `posters` 一条 cache-path。
 *
 * 隐私（方案 6.7）：PNG 落在 cacheDir，随系统缓存清理；不写外部存储、不申请权限；
 * 分享文本附「数据仅保存在本机 · 未上传」一句说明，不含可反推用户的标识。
 */
object PosterShare {

    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val POSTER_DIR = "posters"

    /** 渲染并写入 [Context.getCacheDir]/posters/，返回文件（IO 线程执行，不阻塞 UI） */
    suspend fun renderToFile(context: Context, content: PosterContent, theme: PosterTheme): File {
        val bitmap = withContext(Dispatchers.IO) { PosterRenderer.render(content, theme) }
        val dir = File(context.cacheDir, POSTER_DIR).apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "puretone_report_${content.data.type.name.lowercase(Locale.US)}_$ts.png")
        withContext(Dispatchers.IO) {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        bitmap.recycle()
        return file
    }

    /** 渲染并经系统分享面板分享 PNG（主线程拉起 chooser） */
    suspend fun share(context: Context, content: PosterContent, theme: PosterTheme) {
        val file = renderToFile(context, content, theme)
        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, content.footer)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, context.getString(R.string.pt_report_share_title))
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(chooser)
        }
    }
}
