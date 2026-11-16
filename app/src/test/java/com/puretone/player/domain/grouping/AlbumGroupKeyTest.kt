package com.puretone.player.domain.grouping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 专辑分组 key 编解码纯函数单测（domain 层，无 Android 依赖）。
 *
 * 锁定既有 4 处内联实现逐字一致的语义，任一处偷换都会变红：
 * - 三段顺序固定 `volumeName|albumId|album`，无额外转义；
 * - `split("|", limit = 3)`：专辑名含 `|` 时多余分隔符归入第三段，不丢专辑名；
 * - 卷名缺失→""、`albumId` 非法→0L（未知专辑按名匹配）、第三段缺失→null；
 * - 空串专辑名 "" 是合法值，不得归一为 null（否则详情页 fallback 行为改变）。
 */
class AlbumGroupKeyTest {

    @Test
    fun `正常三段 round-trip`() {
        val key = AlbumGroupKey.encode("external_primary", 42L, "Greatest Hits")
        assertEquals("external_primary|42|Greatest Hits", key)
        assertEquals(AlbumGroupKey("external_primary", 42L, "Greatest Hits"), AlbumGroupKey.decode(key))
    }

    @Test
    fun `专辑名含竖线时第三段保留完整（limit=3 语义）`() {
        // 专辑名里带 | 是真实存在的（如 "Rock|Pop 精选"），多余分隔符必须全归第三段，
        // 否则跳详情会按截断后的名字匹配不到歌。
        val key = AlbumGroupKey.encode("external_primary", 7L, "Rock|Pop|精选")
        assertEquals("external_primary|7|Rock|Pop|精选", key)
        val decoded = AlbumGroupKey.decode(key)
        assertEquals("external_primary", decoded.volumeName)
        assertEquals(7L, decoded.albumId)
        assertEquals("Rock|Pop|精选", decoded.album)
    }

    @Test
    fun `空卷名 round-trip（内部存储默认卷名为空串）`() {
        val key = AlbumGroupKey.encode("", 12L, "Unknown Album")
        assertEquals("|12|Unknown Album", key)
        assertEquals(AlbumGroupKey("", 12L, "Unknown Album"), AlbumGroupKey.decode(key))
    }

    @Test
    fun `albumId 为 0 的未知专辑 round-trip（仓库按名匹配）`() {
        val key = AlbumGroupKey.encode("external_primary", 0L, "No Id Album")
        assertEquals(AlbumGroupKey("external_primary", 0L, "No Id Album"), AlbumGroupKey.decode(key))
    }

    @Test
    fun `中文与特殊字符专辑名 round-trip`() {
        val key = AlbumGroupKey.encode("存储卡", 99L, "十年·精选（上）")
        assertEquals(AlbumGroupKey("存储卡", 99L, "十年·精选（上）"), AlbumGroupKey.decode(key))
    }

    @Test
    fun `第三段为空串时保持空串且不触发 null fallback`() {
        val key = AlbumGroupKey.encode("vol", 1L, "")
        assertEquals("vol|1|", key)
        val decoded = AlbumGroupKey.decode(key)
        assertEquals("", decoded.album)
        assertEquals(AlbumGroupKey("vol", 1L, ""), decoded)
    }

    @Test
    fun `只有两段 key 时专辑名为 null（详情页回退首曲 album）`() {
        val decoded = AlbumGroupKey.decode("vol|42")
        assertEquals("vol", decoded.volumeName)
        assertEquals(42L, decoded.albumId)
        assertNull(decoded.album)
    }

    @Test
    fun `只有一段 key 时专辑名与 albumId 走兜底`() {
        val decoded = AlbumGroupKey.decode("vol")
        assertEquals("vol", decoded.volumeName)
        assertEquals(0L, decoded.albumId)
        assertNull(decoded.album)
    }

    @Test
    fun `空 key 全字段兜底`() {
        assertEquals(AlbumGroupKey("", 0L, null), AlbumGroupKey.decode(""))
    }

    @Test
    fun `albumId 非数字时回退 0 且专辑名仍保留`() {
        val decoded = AlbumGroupKey.decode("vol|not-a-number|Album X")
        assertEquals("vol", decoded.volumeName)
        assertEquals(0L, decoded.albumId)
        assertEquals("Album X", decoded.album)
    }

    @Test
    fun `负数 albumId 原样保留（仓库层按非正 id 决定按名匹配）`() {
        val decoded = AlbumGroupKey.decode("vol|-5|Album Y")
        assertEquals(-5L, decoded.albumId)
        assertEquals("Album Y", decoded.album)
    }

    @Test
    fun `encode 与 decode 多组数据一致（往返封闭性）`() {
        val samples = listOf(
            Triple("v", 1L, "A"),
            Triple("", 0L, ""),
            Triple("sdcard", 99999L, "A|B|C"),
            Triple("内部", 3L, "中文专辑")
        )
        samples.forEach { (vol, id, album) ->
            val decoded = AlbumGroupKey.decode(AlbumGroupKey.encode(vol, id, album))
            assertEquals(vol, decoded.volumeName)
            assertEquals(id, decoded.albumId)
            assertEquals(album, decoded.album)
        }
    }
}
