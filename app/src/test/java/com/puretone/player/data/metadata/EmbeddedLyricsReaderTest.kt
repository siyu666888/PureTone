package com.puretone.player.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * ID3v2 内嵌歌词解析测试。
 *
 * 用手工构造的 ID3 字节样本覆盖 [EmbeddedLyricsReader] 的解析路径：
 * v2.3 的 USLT、v2.4 的 SYLT、以及各种边界（无 ID3 头 / 空帧 / 非 mp3）。
 */
class EmbeddedLyricsReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val reader = EmbeddedLyricsReader()

    @Test
    fun `解析 v2_3 的 USLT 帧`() {
        val file = temp.newFile("a.mp3")
        file.writeBytes(id3v23(usltFrame(text = "这是一句歌词", encoding = 3)))

        assertEquals("这是一句歌词", reader.read(file.absolutePath))
    }

    @Test
    fun `USLT 帧带内容描述时正确跳过`() {
        val file = temp.newFile("b.mp3")
        file.writeBytes(id3v23(usltFrame(text = "hello", encoding = 3, description = "desc")))

        assertEquals("hello", reader.read(file.absolutePath))
    }

    @Test
    fun `解析 v2_4 的 SYLT 逐词帧`() {
        val file = temp.newFile("c.mp3")
        file.writeBytes(id3v24(syltFrame(entries = listOf("第一行" to 1000L, "第二行" to 5000L))))

        val result = reader.read(file.absolutePath)

        assertTrue(result != null)
        assertTrue(result!!.contains("[00:01.00]第一行"))
        assertTrue(result.contains("[00:05.00]第二行"))
    }

    @Test
    fun `非 ID3 文件返回 null`() {
        val file = temp.newFile("d.mp3")
        file.writeBytes(ByteArray(64) { 0x41 })

        assertNull(reader.read(file.absolutePath))
    }

    @Test
    fun `只有 ID3 头但没有歌词帧返回 null`() {
        val file = temp.newFile("e.mp3")
        file.writeBytes(id3v23(ByteArray(0)))

        assertNull(reader.read(file.absolutePath))
    }

    @Test
    fun `不存在的文件返回 null`() {
        assertNull(reader.read("/no/such/file.mp3"))
        assertNull(reader.read(""))
    }

    /** 构造一个 ID3v2.3 文件：header + 帧数据 */
    private fun id3v23(frames: ByteArray): ByteArray = id3(frames, version = 3)

    /** 构造一个 ID3v2.4 文件：header（syncsafe 大小）+ 帧数据 */
    private fun id3v24(frames: ByteArray): ByteArray = id3(frames, version = 4)

    /**
     * 构造 ID3 文件头 + 帧区。
 *
     * 帧区是**原始字节**（不是字符串）：ID3 的时间戳等字段是二进制，
     * 若整段走字符编码会被破坏（如 0x88 被 UTF-8 编成 0xC2 0x88）。
     * 故帧区统一用 ByteArray 拼接，只有文本部分才编码。
     */
    private fun id3(frames: ByteArray, version: Int): ByteArray {
        val body = frames
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = version.toByte()
        header[4] = 0
        header[5] = 0
        if (version >= 4) {
            // v2.4：总大小用 syncsafe（每字节只用低 7 位）
            header[6] = ((body.size shr 21) and 0x7F).toByte()
            header[7] = ((body.size shr 14) and 0x7F).toByte()
            header[8] = ((body.size shr 7) and 0x7F).toByte()
            header[9] = (body.size and 0x7F).toByte()
        } else {
            // v2.3：总大小是普通大端
            header[6] = ((body.size shr 24) and 0xFF).toByte()
            header[7] = ((body.size shr 16) and 0xFF).toByte()
            header[8] = ((body.size shr 8) and 0xFF).toByte()
            header[9] = (body.size and 0xFF).toByte()
        }
        return header + body
    }

    /**
     * USLT 帧：编码(1) + 语言(3) + 内容描述(0 结尾) + 文本。
     *
     * 帧头为：4 字节 ID + 4 字节 size（普通大端，v2.3）+ 2 字节 flags。
     */
    private fun usltFrame(text: String, encoding: Int, description: String = ""): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(encoding)
            write("chi".toByteArray(Charsets.ISO_8859_1))
            write(description.toByteArray(Charsets.UTF_8))
            write(0)
            write(text.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        return "USLT".toByteArray(Charsets.ISO_8859_1) + frameHeader(payload) + payload
    }

    /**
     * SYLT 帧：编码(1) + 语言(3) + 时间戳格式(1) + 内容类型(1) + 描述(0) + 文本/时间戳序列。
     *
     * 注意：文本按 UTF-8 编码，但 4 字节时间戳是**原始字节**，不经编码。
     */
    private fun syltFrame(entries: List<Pair<String, Long>>): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(3) // 编码：UTF-8
            write("chi".toByteArray(Charsets.ISO_8859_1))
            write(2) // 时间戳格式：毫秒
            write(1) // 内容类型：歌词
            write(0) // 空的内容描述
            entries.forEach { (text, ms) ->
                write(text.toByteArray(Charsets.UTF_8))
                write(0)
                write(
                    byteArrayOf(
                        ((ms shr 24) and 0xFF).toByte(),
                        ((ms shr 16) and 0xFF).toByte(),
                        ((ms shr 8) and 0xFF).toByte(),
                        (ms and 0xFF).toByte()
                    )
                )
            }
        }.toByteArray()
        return "SYLT".toByteArray(Charsets.ISO_8859_1) + frameHeader(payload) + payload
    }

    /** 帧头：4 字节 size（普通大端）+ 2 字节 flags */
    private fun frameHeader(payload: ByteArray): ByteArray {
        val size = payload.size
        return byteArrayOf(
            ((size shr 24) and 0xFF).toByte(),
            ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
            0,
            0
        )
    }
}
