package com.puretone.player.data.playlist

import android.content.Context
import android.net.Uri
import com.puretone.player.lyrics.LrcParser
import com.puretone.player.lyrics.M3uCodec
import com.puretone.player.lyrics.M3uEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** 从 SAF Uri 导入歌单（保留原始路径，匹配由调用方负责） */
    suspend fun import(uri: Uri): List<M3uEntry> = withContext(Dispatchers.IO) {
        val content = context.contentResolver.openInputStream(uri)?.use { stream ->
            LrcParser.decode(stream.readBytes())
        } ?: return@withContext emptyList()
        M3uCodec.parse(content)
    }

    /** 导出歌单到应用外部目录，返回文件 */
    suspend fun export(playlistName: String, entries: List<M3uEntry>): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.getExternalFilesDir(null), "playlists").apply { mkdirs() }
            val safeName = playlistName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val file = File(dir, "$safeName.m3u")
            file.writeText(M3uCodec.serialize(entries), Charsets.UTF_8)
            file
        }

    /** 应用目录下已导出的歌单文件列表 */
    suspend fun listExported(): List<File> = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "playlists")
        dir.listFiles()?.filter { it.extension.equals("m3u", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()
    }
}
