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
        val raw = File(audioPath).nameWithoutExtension.ifBlank { "lyrics" }
        // P2-10：ext4/FUSE 文件名上限约 255 字节；base 过长会令「base_<hash>.lrc」
        // 超出上限导致 writeText 抛 ENAMETOOLONG（被 runCatching 吞掉，歌词静默存不上）。
        // 截到 220 字节前缀 + 8 位哈希后缀 + "_" + ".lrc"(13 字节) = 233 ≤ 255，留足余量。
        // 哈希后缀已保证不同路径唯一，截断前缀不影响去重。
        val base = truncateToBytes(raw, 220)
        return File(dir, "${base}_${hash(audioPath)}.lrc")
    }

    /** 按 UTF-8 字节数截断，避免长歌名产生超长文件名（P2-10） */
    private fun truncateToBytes(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val sb = StringBuilder()
        var bytes = 0
        for (ch in s) {
            val len = ch.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + len > maxBytes) break
            sb.append(ch)
            bytes += len
        }
        return sb.toString()
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
