package com.puretone.player.lyrics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
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
