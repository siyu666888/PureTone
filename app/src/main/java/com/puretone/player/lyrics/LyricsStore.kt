package com.puretone.player.lyrics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载歌词的本地存储。
 *
 * 为什么不写到歌曲同目录：App 只申请了读权限（READ_MEDIA_AUDIO /
 * READ_EXTERNAL_STORAGE），Android 11+ 无法写 /sdcard 任意目录。
 * 这里统一写到 App 外部 Files 目录（免权限、随 App 卸载清理）：
 * `<externalFilesDir>/lyrics/<歌曲名>_<路径哈希8位>.lrc`。
 *
 * 加载优先级见 [com.puretone.player.ui.PlayerViewModel.loadLyrics]：
 * 歌曲同目录同名 .lrc（用户手动放置）优先，其次本目录的下载歌词。
 */
@Singleton
class LyricsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "lyrics")
            .apply { runCatching { mkdirs() } }

    /** 下载歌词文件路径（与音频绝对路径稳定映射，避免同名歌曲冲突） */
    fun fileFor(audioPath: String): File {
        val base = File(audioPath).nameWithoutExtension.ifBlank { "lyrics" }
        return File(dir, "${base}_${hash(audioPath)}.lrc")
    }

    /** 已下载的歌词文件（存在才返回） */
    fun existing(audioPath: String): File? = fileFor(audioPath).takeIf { it.isFile }

    /** 保存歌词文本；成功返回 true */
    fun save(audioPath: String, lrc: String): Boolean = runCatching {
        fileFor(audioPath).writeText(lrc, Charsets.UTF_8)
    }.isSuccess

    private fun hash(path: String): String {
        val md = MessageDigest.getInstance("MD5").digest(path.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(8)
    }
}
