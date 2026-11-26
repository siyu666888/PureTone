package com.puretone.player.data.playlist

import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.lyrics.M3uEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * R8-11 新旧实现等价性测试：把优化前的 O(n×m) 后缀扫描作为 test-only 参考实现，
 * 对固定边界用例与批量随机用例断言「倒排实现结果 id 列表 == 参考实现」。
 *
 * 这是防「静默导错歌」的最强保险：匹配语义（段边界 / 歧义唯一 / 三级优先 / URL 解码）
 * 只要有一处偏差，等价性断言即失败。
 */
class PlaylistImportResolverEquivalenceTest {

    private fun song(id: Long, path: String) = SongEntity(
        id = id,
        mediaStoreId = id,
        title = path.substringAfterLast('/').substringBeforeLast('.'),
        artist = "A",
        album = "B",
        albumId = 1,
        duration = 1000,
        path = path,
        size = 1,
        coverUri = null,
        mimeType = "audio/mpeg"
    )

    /** 优化前的实现：每条目对全库 endsWith 线性过滤，作为语义黄金参考。 */
    private fun referenceResolve(entries: List<M3uEntry>, library: List<SongEntity>): List<Long> {
        if (entries.isEmpty() || library.isEmpty()) return emptyList()
        val byPath = library.associateBy { it.path }
        val bySuffix = library.groupBy { it.path }
        val byName = library.groupBy {
            it.path.substringAfterLast('/').substringBeforeLast('.').lowercase()
        }
        fun fileNameKey(path: String) =
            path.substringAfterLast('/').substringBeforeLast('.').lowercase()
        fun urlDecode(s: String): String {
            if ('%' !in s) return s
            val bytes = java.io.ByteArrayOutputStream(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] == '%' && i + 2 < s.length) {
                    val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (b != null) {
                        bytes.write(b)
                        i += 3
                        continue
                    }
                }
                bytes.write(s[i].toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
        val result = mutableListOf<Long>()
        entries.forEach { entry ->
            val normalized = urlDecode(entry.path).removePrefix("file://").replace('\\', '/')
            val suffixMatches = bySuffix.entries.filter { (libPath, _) ->
                libPath == normalized ||
                    libPath.endsWith("/$normalized") ||
                    normalized.endsWith("/$libPath")
            }
            val match = byPath[normalized]
                ?: suffixMatches.singleOrNull()?.value?.firstOrNull()
                ?: byName[fileNameKey(normalized)]?.takeIf { it.size == 1 }?.firstOrNull()
                ?: return@forEach
            if (result.none { it == match.id }) result += match.id
        }
        return result
    }

    private fun assertEquivalent(entries: List<M3uEntry>, library: List<SongEntity>) {
        val expected = referenceResolve(entries, library)
        val actual = PlaylistImportResolver.resolve(entries, library).map { it.id }
        assertEquals("entries=$entries", expected, actual)
    }

    @Test
    fun `固定边界用例 新旧实现等价`() {
        data class Case(val name: String, val entries: List<String>, val paths: List<String>)
        val cases = listOf(
            Case("精确匹配", listOf("/a/b/song.mp3"), listOf("/a/b/song.mp3")),
            Case(
                "挂载点不同仅文件名唯一",
                listOf("/sdcard/Music/a.mp3"),
                listOf("/storage/emulated/0/Music/a.mp3")
            ),
            Case("裸文件名唯一", listOf("Music/a.mp3"), listOf("/x/Music/a.mp3")),
            Case(
                "段边界 不命中mymusic",
                listOf("music/a.mp3"),
                listOf("/x/mymusic/a.mp3", "/x/other/a.mp3")
            ),
            Case(
                "同名两首 文件名歧义跳过",
                listOf("a.mp3"),
                listOf("/x/Music/a.mp3", "/x/Download/a.mp3")
            ),
            Case("重复条目去重", listOf("/a/b.mp3", "/a/b.mp3"), listOf("/a/b.mp3")),
            Case("无匹配", listOf("/nowhere/b.mp3"), listOf("/a/b.mp3")),
            Case(
                "file前缀与URL编码",
                listOf("file:///a/my%20song.mp3"),
                listOf("/a/my song.mp3")
            ),
            Case(
                "多字节UTF8中文",
                listOf("file:///%E9%9F%B3%E4%B9%90/%E6%AD%8C.mp3"),
                listOf("/音乐/歌.mp3", "/x/other.mp3")
            ),
            Case(
                "后缀层多路径歧义",
                listOf("Music/a.mp3"),
                listOf("/x/Music/a.mp3", "/y/Music/a.mp3")
            ),
            Case(
                "条件3 条目长于库路径",
                listOf("/x/y/Music/a.mp3"),
                listOf("Music/a.mp3")
            ),
            Case(
                "条件3多命中歧义",
                listOf("/x/y/Music/a.mp3"),
                listOf("y/Music/a.mp3", "Music/a.mp3")
            ),
            Case("空条目", listOf(""), listOf("/a/b.mp3")),
            Case("含双斜杠", listOf("/a//b.mp3"), listOf("/a/b.mp3", "/b.mp3")),
            Case("大小写不同文件名", listOf("/a/SONG.MP3"), listOf("/b/song.mp3"))
        )
        cases.forEach { c ->
            val library = c.paths.mapIndexed { idx, p -> song(idx + 1L, p) }
            val entries = c.entries.map(::M3uEntry)
            assertEquals(c.name, referenceResolve(entries, library),
                PlaylistImportResolver.resolve(entries, library).map { it.id })
        }
    }

    @Test
    fun `批量随机用例 新旧实现等价`() {
        val random = Random(20261122)
        val dirs = listOf("Music", "mymusic", "Download", "音乐", "other", "a", "b")
        val names = listOf("song.mp3", "a.mp3", "歌.mp3", "track.flac", "b.mp3")
        val roots = listOf("/storage/emulated/0/", "/sdcard/", "", "/")

        repeat(200) { round ->
            val libSize = 1 + random.nextInt(12)
            val library = (1..libSize).map { id ->
                val path = roots.random(random) +
                    (1..random.nextInt(1, 4)).joinToString("/") { dirs.random(random) } +
                    "/" + names.random(random)
                song(id.toLong(), path)
            }.distinctBy { it.path }.mapIndexed { idx, s -> s.copy(id = idx + 1L) }

            val entryCount = 1 + random.nextInt(6)
            val entries = (1..entryCount).map {
                val base = roots.random(random) +
                    (1..random.nextInt(1, 4)).joinToString("/") { dirs.random(random) } +
                    "/" + names.random(random)
                M3uEntry(
                    when (random.nextInt(3)) {
                        0 -> base
                        1 -> base.replace('/', '\\')
                        else -> "file://" + base
                    }
                )
            }
            assertEquivalent(entries, library)
            // assertEquals 里已带 round 无关的 entries 提示；额外断言保证随机轮次可追踪
            assertEquals(
                "round=$round",
                referenceResolve(entries, library),
                PlaylistImportResolver.resolve(entries, library).map { it.id }
            )
        }
    }
}
