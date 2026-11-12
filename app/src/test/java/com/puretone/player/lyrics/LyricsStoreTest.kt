package com.puretone.player.lyrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LyricsStore] 文件名约束测试（P2-10）。
 * 验证超长歌名会被截断，使最终文件名（含 12 位哈希后缀 + "_" + ".lrc"）不超 255 字节，
 * 避免 ext4/FUSE 的 ENAMETOOLONG 导致歌词静默保存失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = LyricsStore(context)

    @Test
    fun `超长歌名被截断使文件名不超过 255 字节`() {
        val longName = "歌".repeat(200) // 200 个汉字 ≈ 600 字节，远超限制
        val audioPath = "/storage/emulated/0/Music/$longName.mp3"

        val file = store.fileFor(audioPath)

        val nameBytes = file.name.toByteArray(Charsets.UTF_8)
        assertTrue("文件名应 ≤ 255 字节，实际 ${nameBytes.size}", nameBytes.size <= 255)
        // 后缀固定为 18 字节（"_"+12 位哈希+".lrc"），前缀应被截到 220 字节内
        assertTrue("base 过长：${file.name}", file.name.length <= 220 + 18)
    }

    @Test
    fun `正常长度歌名不截断`() {
        val audioPath = "/storage/emulated/0/Music/青花瓷.mp3"
        val file = store.fileFor(audioPath)
        assertEquals("青花瓷_${hash(audioPath)}.lrc", file.name)
    }

    @Test
    fun `含代理对的长歌名按真实 UTF-8 字节截断`() {
        val note = "\uD83C\uDFB5" // 🎵，增补平面：2 个 Char / UTF-8 占 4 字节
        val audioPath = "/storage/emulated/0/Music/${note.repeat(200)}.mp3"

        val file = store.fileFor(audioPath)
        val nameBytes = file.name.toByteArray(Charsets.UTF_8)

        // 旧实现按 Char 迭代、半个代理只记 1 字节，会放行 110 个 emoji（440 字节），
        // ext4/FUSE 上 ENAMETOOLONG 被 save() 的 runCatching 吞掉 → 歌词静默存不上。
        assertTrue("含代理对文件名应 ≤ 255 字节，实际 ${nameBytes.size}", nameBytes.size <= 255)
        assertTrue("歌词应能真正落盘", store.save(audioPath, "[00:00.00]词\n"))
        assertEquals("[00:00.00]词\n", store.existing(audioPath)?.readText(Charsets.UTF_8))
    }

    @Test
    fun `代理对不会被从中间截断成孤立代理`() {
        val note = "\uD83C\uDFB5"
        // 先放一个 3 字节 BMP 汉字，让 220 字节预算落在奇数个代理 Char 上：
        // 旧实现会在高代理之后 break，留下半个字符写进文件名。
        val audioPath = "/storage/emulated/0/Music/歌${note.repeat(200)}.mp3"
        val base = store.fileFor(audioPath).name.substringBeforeLast('_')

        assertTrue(base.toByteArray(Charsets.UTF_8).size <= 220)
        assertFalse("截断结果含孤立高代理", base.indices.any { i ->
            java.lang.Character.isHighSurrogate(base[i]) &&
                (i + 1 >= base.length || !java.lang.Character.isLowSurrogate(base[i + 1]))
        })
        assertFalse("截断结果含孤立低代理", base.indices.any { i ->
            java.lang.Character.isLowSurrogate(base[i]) &&
                (i == 0 || !java.lang.Character.isHighSurrogate(base[i - 1]))
        })
    }

    @Test
    fun `无扩展名但有名时以名为前缀`() {
        val file = store.fileFor("/storage/emulated/0/Music/track")
        assertTrue(file.name.startsWith("track_"))
        assertTrue(file.name.endsWith(".lrc"))
    }

    @Test
    fun `空文件名时回退为 lyrics 前缀`() {
        // 防御性分支：文件名取不到（空路径）→ ifBlank 回退 lyrics
        val file = store.fileFor("")
        assertTrue(file.name.startsWith("lyrics_"))
        assertTrue(file.name.endsWith(".lrc"))
    }

    private fun hash(path: String): String {
        val md = java.security.MessageDigest.getInstance("MD5").digest(path.toByteArray())
        // P3-22：哈希后缀已从 8 位加长到 12 位（降低碰撞概率），后缀总长随之
        // 从 14 字节变成 18 字节（"_" + 12 位哈希 + ".lrc"）。
        return md.joinToString("") { "%02x".format(it) }.take(12)
    }
}
