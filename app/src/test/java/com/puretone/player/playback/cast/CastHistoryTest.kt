package com.puretone.player.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史投屏列表的纯逻辑测试（编解码 + 合并排重 + 裁剪）。
 *
 * 这些行为都不会崩，只会让用户觉得「我明明投过，怎么没了」——所以必须用测试钉死：
 * - 排重主键（IP 优先于名称）选错 → 同一台电视出现两条；
 * - 裁剪顺序错 → 刚投过的那台被裁掉；
 * - 编码没转义 → 设备名带 `%`/分隔符时整条记录解不出来。
 */
class CastHistoryTest {

    private fun device(
        name: String = "客厅极光TV",
        address: String = "192.168.101.20",
        id: String = "http://192.168.101.20:49152/description.xml",
        isTV: Boolean = true
    ) = CastDevice(id = id, name = name, address = address, isTV = isTV)

    @Test
    fun `record 新增一台设备`() {
        val result = CastHistoryRules.record(emptyList(), device(), now = 1000L)
        assertEquals(1, result.size)
        assertEquals("客厅极光TV", result[0].name)
        assertEquals(1000L, result[0].lastUsedAt)
        assertEquals(1, result[0].useCount)
        assertTrue(result[0].isTV)
    }

    @Test
    fun `同 IP 再次投屏：更新而不是新增，次数递增`() {
        val first = CastHistoryRules.record(emptyList(), device(), now = 1000L)
        val second = CastHistoryRules.record(
            first,
            device(name = "客厅极光TV（改名）", id = "http://192.168.101.20:5/x.xml"),
            now = 2000L
        )
        assertEquals(1, second.size)
        assertEquals("客厅极光TV（改名）", second[0].name)
        assertEquals("http://192.168.101.20:5/x.xml", second[0].id)
        assertEquals(2000L, second[0].lastUsedAt)
        assertEquals(2, second[0].useCount)
    }

    @Test
    fun `同名换了 IP（DHCP 重新分配）：仍算同一台，跟随新 IP`() {
        val first = CastHistoryRules.record(emptyList(), device(), now = 1000L)
        val second = CastHistoryRules.record(
            first,
            device(address = "192.168.101.99", id = "http://192.168.101.99/desc.xml"),
            now = 2000L
        )
        assertEquals(1, second.size)
        assertEquals("192.168.101.99", second[0].address)
        assertEquals(2, second[0].useCount)
    }

    @Test
    fun `超过上限时裁掉最久没用的，保留最近使用的`() {
        var list = emptyList<CastHistoryEntry>()
        repeat(CastHistoryRules.MAX_ENTRIES + 2) { i ->
            list = CastHistoryRules.record(
                list,
                device(name = "TV$i", address = "192.168.101.$i", id = "http://192.168.101.$i/d.xml"),
                now = 1000L + i
            )
        }
        assertEquals(CastHistoryRules.MAX_ENTRIES, list.size)
        // 最早两台（TV0/TV1）被裁掉
        assertTrue(list.none { it.name == "TV0" || it.name == "TV1" })
        // 最新那台一定还在，且排第一
        assertEquals("TV9", list.first().name)
    }

    @Test
    fun `结果按最近使用倒序`() {
        var list = CastHistoryRules.record(emptyList(), device(name = "A", address = "1.1.1.1"), now = 1000L)
        list = CastHistoryRules.record(list, device(name = "B", address = "2.2.2.2"), now = 3000L)
        list = CastHistoryRules.record(list, device(name = "A", address = "1.1.1.1"), now = 2000L)
        assertEquals(listOf("B", "A"), list.map { it.name })
    }

    @Test
    fun `空 IP 或空名称不写入历史`() {
        assertTrue(CastHistoryRules.record(emptyList(), device(address = ""), now = 1L).isEmpty())
        assertTrue(CastHistoryRules.record(emptyList(), device(name = "  "), now = 1L).isEmpty())
    }

    @Test
    fun `forget 按 IP 移除`() {
        val list = CastHistoryRules.record(emptyList(), device(), now = 1L)
        assertTrue(CastHistoryRules.forget(list, "192.168.101.20").isEmpty())
        // 不存在的 IP 不会误删
        assertEquals(1, CastHistoryRules.forget(list, "10.0.0.1").size)
    }

    @Test
    fun `编解码往返：中文名 + URL + 特殊字符`() {
        val entries = listOf(
            CastHistoryEntry(
                id = "http://192.168.101.20:49152/description.xml?x=1&y=%20",
                name = "客厅极光TV / 50% 音量",
                address = "192.168.101.20",
                isTV = true,
                lastUsedAt = 1_700_000_000_000L,
                useCount = 3
            ),
            CastHistoryEntry(
                id = "http://192.168.101.21/d.xml",
                name = "卧室音箱",
                address = "192.168.101.21",
                isTV = false,
                lastUsedAt = 1_699_000_000_000L,
                useCount = 1
            )
        )
        val decoded = CastHistoryRules.decode(CastHistoryRules.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun `空列表编码后解码仍为空`() {
        assertTrue(CastHistoryRules.decode(CastHistoryRules.encode(emptyList())).isEmpty())
        assertTrue(CastHistoryRules.decode("").isEmpty())
        assertTrue(CastHistoryRules.decode(null).isEmpty())
    }

    @Test
    fun `损坏或版本不符的数据一律回退空列表，不抛异常`() {
        assertTrue(CastHistoryRules.decode("garbage").isEmpty())
        assertTrue(CastHistoryRules.decode("v1").isEmpty())
        assertTrue(CastHistoryRules.decode("v0\u001Efoo\u001Fbar").isEmpty())
        // 字段不足（少 useCount）→ 丢弃该条
        assertTrue(CastHistoryRules.decode("v1\u001Ea\u001Fb\u001F1.2.3.4\u001F1\u001F100").isEmpty())
    }

    @Test
    fun `单条记录坏掉只丢那一条`() {
        val good = CastHistoryEntry(
            id = "http://192.168.101.20/d.xml",
            name = "客厅极光TV",
            address = "192.168.101.20",
            isTV = true,
            lastUsedAt = 5000L,
            useCount = 2
        )
        val raw = CastHistoryRules.encode(listOf(good)) + "\u001Ebroken"
        val decoded = CastHistoryRules.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("客厅极光TV", decoded[0].name)
    }
}
