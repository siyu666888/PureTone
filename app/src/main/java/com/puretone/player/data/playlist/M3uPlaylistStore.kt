package com.puretone.player.data.playlist

import android.content.Context
import android.net.Uri
import com.puretone.player.lyrics.LrcParser
import com.puretone.player.lyrics.M3uCodec
import com.puretone.player.lyrics.M3uEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M3U 歌单文件存取。
 *
 * - 导入：SAF（ACTION_OPEN_DOCUMENT）返回的 Uri，经 ContentResolver 读取；
 * - 导出：应用外部目录 `Android/data/<pkg>/files/playlists/`（无需存储权限）。
 *
 * 导入的相对路径由 [PlaylistImportResolver] 与曲库按 路径 → 文件名 二级匹配，
 * 不依赖导出方目录结构。
 */
@Singleton
class M3uPlaylistStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** R8-8：歌单导入大小上限（1 MiB）——SAF 可选任意大文件，防 readBytes OOM */
    private val maxImportBytes = 1024 * 1024

    /** 从 SAF Uri 导入歌单（保留原始路径，匹配由调用方负责） */
    suspend fun import(uri: Uri): List<M3uEntry> = withContext(Dispatchers.IO) {
        // R8-8：先查 SIZE 早拒（云盘/视频等大文件）；SIZE 不可靠时下面流式读取仍限量兜底。
        val declaredSize = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
        } catch (_: Exception) { -1L }
        if (declaredSize > maxImportBytes) return@withContext emptyList()

        val content = context.contentResolver.openInputStream(uri)?.use { stream ->
            // 流式限量读取：读到 MAX+1 即判超限（不用 readNBytes，那是 API 33）。
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > maxImportBytes) return@withContext emptyList()
                buffer.write(chunk, 0, n)
            }
            LrcParser.decode(buffer.toByteArray())
        } ?: return@withContext emptyList()
        M3uCodec.parse(content)
    }

    /** 导出歌单目录（P3-9：getExternalFilesDir 可能为 null，回退到内部 filesDir 避免变成相对路径） */
    private fun playlistsDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "playlists").apply { mkdirs() }

    /** 导出歌单到应用外部目录，成功返回文件；写入失败返回 null（R8-10：不再假成功） */
    suspend fun export(playlistName: String, entries: List<M3uEntry>): File? =
        withContext(Dispatchers.IO) {
            // P3-9：getExternalFilesDir(null) 返回 null 时（无外部存储的极少数设备）
            // File(null, "playlists") 会变成相对路径，写入位置不可控；统一回退内部目录。
            val dir = playlistsDir()
            val safeName = playlistName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val file = File(dir, "$safeName.m3u")
            // P3-6：先写 .tmp 再原子 rename，进程中途崩溃不残留半截歌单文件。
            val tmp = File(dir, "$safeName.m3u.tmp")
            tmp.writeText(M3uCodec.serialize(entries), Charsets.UTF_8)
            val ok = tmp.renameTo(file) || runCatching {
                tmp.copyTo(file, overwrite = true).also { tmp.delete() }
                true
            }.getOrDefault(false)
            if (!ok) {
                tmp.delete()
                return@withContext null // R8-10：双失败须暴露，不能返回不存在的 file 让玩家以为成功
            }
            file
        }

    /** 应用目录下已导出的歌单文件列表 */
    suspend fun listExported(): List<File> = withContext(Dispatchers.IO) {
        val dir = playlistsDir()
        dir.listFiles()?.filter { it.extension.equals("m3u", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * R8-17：在 IO 线程校验 SAF Uri 扩展名是否 .m3u/.m3u8。
     * 从 UI 主线程回调挪进来，避免慢 provider（云文档）query 卡主线程。
     */
    suspend fun isM3uUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) { null }
        name?.let {
            it.endsWith(".m3u", ignoreCase = true) || it.endsWith(".m3u8", ignoreCase = true)
        } ?: false
    }

    /** 从 SAF Uri 取出歌单文件名（去掉 .m3u 后缀），用于导入后命名（P2：M3U 导入导出） */
    suspend fun nameOf(uri: Uri): String? = withContext(Dispatchers.IO) {
        val name = try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        } catch (_: Exception) { null }
        name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
    }
}
