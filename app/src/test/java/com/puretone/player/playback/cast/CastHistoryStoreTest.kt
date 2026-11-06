package com.puretone.player.playback.cast

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CastHistoryStore] 的 DataStore 编排层单测。
 *
 * [CastHistoryTest] 已经把 `CastHistoryRules` 的编解码 / 排重 / 裁剪钉死了，
 * 但「写没写进磁盘、读坏了会不会崩、空格地址会不会污染列表」这几件事只在
 * Store 这一层发生，纯函数测试覆盖不到。这里用一个内存 [DataStore] 替身
 * 把 IO 边界补齐——不依赖 Robolectric、不碰真实文件，纯 JVM 跑。
 *
 * 为什么值得测：历史投屏列表是 SSDP 首搜空手时唯一的兜底入口，
 * 一次写坏就变成「我明明投过，怎么列表里没有」，且不会有任何崩溃日志。
 */
class CastHistoryStoreTest {

    private fun device(name: String = "客厅极光TV", address: String = "192.168.101.20") =
        CastDevice(
            id = "http://$address:49152/description.xml",
            name = name,
            address = address,
            isTV = true
        )

    /**
     * 内存版 Preferences DataStore。
     *
     * `edit()` 扩展内部就是 `updateData { toMutablePreferences()...toPreferences() }`，
     * 所以只要实现 `data` + `updateData` 两个成员即可驱动真实的 Store 逻辑。
     * [throwOnRead] 用来模拟文件损坏，验证 `entries` 的 IOException 兜底。
     */
    private class InMemoryPreferencesDataStore(seed: String? = null) : DataStore<Preferences> {
        private val key = stringPreferencesKey("cast_history")
        private val backing: MutableStateFlow<Preferences> = MutableStateFlow(
            mutablePreferencesOf().apply { if (seed != null) this[key] = seed }
        )

        var throwOnRead = false
        /** 记录 updateData 次数：`record` 短路时应当完全不写盘 */
        var writeCount = 0

        override val data: Flow<Preferences> = backing.map { prefs ->
            if (throwOnRead) throw IOException("模拟 DataStore 文件损坏")
            prefs
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences
        ): Preferences {
            val next = transform(backing.value)
            writeCount++
            backing.value = next
            return next
        }
    }

    @Test
    fun `record 落盘后 entries 能读回设备`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val subject = CastHistoryStore(store)

        subject.record(device())

        val entries = subject.entries.first()
        assertEquals(1, entries.size)
        assertEquals("客厅极光TV", entries[0].name)
        assertEquals("192.168.101.20", entries[0].address)
        assertTrue(entries[0].isTV)
        assertEquals(1, entries[0].useCount)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun `空地址不落盘也不污染已有历史`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val subject = CastHistoryStore(store)
        subject.record(device())

        subject.record(device(address = "   "))

        assertEquals(1, store.writeCount)
        assertEquals(1, subject.entries.first().size)
    }

    @Test
    fun `同 IP 再投更新为一条且累计次数`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val subject = CastHistoryStore(store)

        subject.record(device())
        subject.record(device(name = "客厅极光TV（改名）"))

        val entries = subject.entries.first()
        assertEquals(1, entries.size)
        assertEquals("客厅极光TV（改名）", entries[0].name)
        assertEquals(2, entries[0].useCount)
    }

    @Test
    fun `超过上限只保留 MAX_ENTRIES 条`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val subject = CastHistoryStore(store)

        repeat(CastHistoryRules.MAX_ENTRIES + 2) { i ->
            subject.record(device(name = "电视$i", address = "192.168.101.${20 + i}"))
        }

        val entries = subject.entries.first()
        assertEquals(CastHistoryRules.MAX_ENTRIES, entries.size)
        // 编码函数会再裁一次，Store 读回的条数不应超过上限
        assertTrue(entries.all { it.address.isNotBlank() })
    }

    @Test
    fun `forget 按 IP 移除指定设备`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val subject = CastHistoryStore(store)
        subject.record(device(name = "电视A", address = "192.168.101.20"))
        subject.record(device(name = "电视B", address = "192.168.101.21"))

        subject.forget("192.168.101.20")

        val entries = subject.entries.first()
        assertEquals(1, entries.size)
        assertEquals("192.168.101.21", entries[0].address)
    }

    @Test
    fun `设备名含百分号与分隔符时仍能往返`() = runTest {
        val store = InMemoryPreferencesDataStore()
        val subject = CastHistoryStore(store)
        val tricky = "客厅%电视\u001F\u001E&name=1"

        subject.record(device(name = tricky))

        val entries = subject.entries.first()
        assertEquals(1, entries.size)
        assertEquals(tricky, entries[0].name)
    }

    @Test
    fun `已存的历史能被解码读出`() = runTest {
        val seed = CastHistoryRules.encode(
            listOf(
                CastHistoryEntry(
                    id = "http://192.168.101.20:49152/description.xml",
                    name = "上次那台电视",
                    address = "192.168.101.20",
                    isTV = true,
                    lastUsedAt = 1_000L,
                    useCount = 3
                )
            )
        )
        val subject = CastHistoryStore(InMemoryPreferencesDataStore(seed))

        val entries = subject.entries.first()
        assertEquals(1, entries.size)
        assertEquals("上次那台电视", entries[0].name)
        assertEquals(3, entries[0].useCount)
    }

    @Test
    fun `文件损坏 IOException 回退空列表而不是崩溃`() = runTest {
        val store = InMemoryPreferencesDataStore().apply { throwOnRead = true }
        val subject = CastHistoryStore(store)

        assertTrue(subject.entries.first().isEmpty())
    }

    @Test
    fun `非 IOException 的读取异常不吞`() = runTest {
        class BrokenDataStore : DataStore<Preferences> {
            override val data: Flow<Preferences> =
                MutableStateFlow<Preferences>(mutablePreferencesOf())
                    .map { throw IllegalStateException("不该被吞掉") }
            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences
            ): Preferences = error("not used")
        }

        val result = runCatching { CastHistoryStore(BrokenDataStore()).entries.first() }
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is IOException)
    }
}
