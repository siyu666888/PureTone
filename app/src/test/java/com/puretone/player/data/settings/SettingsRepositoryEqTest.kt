package com.puretone.player.data.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 用户自定义 EQ 预设持久化的回归测试（② 均衡器自定义）。
 *
 * 背景：EQ 原本只有 7 个内置预设，用户手动调出的曲线无法保存复用。
 * 现通过 DataStore 的 [SettingsRepository.eqCustomPresets]（JSON 编码）支持
 * 保存/覆盖/删除/应用。这里锁定：
 * - 保存后能从设置流读出；
 * - 同名覆盖而非新增；
 * - 删除（含删除不存在项）安全；
 * - 频段长度不符（非 10 段）被忽略，不写库也不崩溃；
 * - 应用预设：写入频段并把 eqPreset 标记为预设名；
 * - 应用不存在的预设无操作。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryEqTest {

    private lateinit var repo: SettingsRepository
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "eq_test_settings.preferences_pb")
        file.delete()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + Job())
        ) { file }
        repo = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    private suspend fun current(): PureToneSettings = repo.settings.first()

    @Test
    fun `保存自定义预设后可从设置流读出`() = runTest {
        val bands = List(10) { it * 100 }
        repo.saveCustomEqPreset("我的低音", bands)
        val presets = current().eqCustomPresets
        assertEquals(bands, presets["我的低音"])
    }

    @Test
    fun `同名预设覆盖而非新增`() = runTest {
        repo.saveCustomEqPreset("X", List(10) { 0 })
        repo.saveCustomEqPreset("X", List(10) { 300 })
        val presets = current().eqCustomPresets
        assertEquals(1, presets.size)
        assertEquals(List(10) { 300 }, presets["X"])
    }

    @Test
    fun `删除自定义预设`() = runTest {
        repo.saveCustomEqPreset("X", List(10) { 0 })
        repo.deleteCustomEqPreset("X")
        assertTrue(current().eqCustomPresets.isEmpty())
    }

    @Test
    fun `删除不存在的预设不崩溃`() = runTest {
        repo.deleteCustomEqPreset("不存在")
        assertTrue(current().eqCustomPresets.isEmpty())
    }

    @Test
    fun `频段长度不符时保存被忽略`() = runTest {
        // 9 段（非 10）应当被忽略，不写库也不抛异常
        repo.saveCustomEqPreset("短", List(9) { 0 })
        assertTrue(current().eqCustomPresets.isEmpty())
    }

    @Test
    fun `应用自定义预设写入频段并把预设名设为该名称`() = runTest {
        val bands = List(10) { -200 + it * 50 }
        repo.saveCustomEqPreset("曲线A", bands)
        repo.applyCustomEqPreset("曲线A")
        val s = current()
        assertEquals("曲线A", s.eqPreset)
        assertEquals(bands, s.eqBandLevels)
    }

    @Test
    fun `应用不存在的预设无操作且不抛异常`() = runTest {
        val before = current()
        repo.applyCustomEqPreset("不存在")
        val after = current()
        assertEquals(before.eqPreset, after.eqPreset)
        assertEquals(before.eqBandLevels, after.eqBandLevels)
    }
}
