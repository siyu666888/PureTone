package com.puretone.player.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 实验室总开关（labEnabled）数据层回归测试。
 *
 * 该开关是「实验室」分类（含响度 ReplayGain）的显隐总闸：UI 层据它决定
 * 是否渲染实验室子分类（入口 + 设置项）。这里只锁死数据层——
 * 键名接线、默认值、写读往返——避免接线笔误导致开关失效。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryLabTest {

    private lateinit var repo: SettingsRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "lab_test_settings.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(
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
    fun `实验室开关默认开启（保留现有响度设置可见性）`() = runTest {
        assertEquals(true, current().labEnabled)
    }

    @Test
    fun `关闭后读取为 false`() = runTest {
        repo.setLabEnabled(false)
        assertEquals(false, current().labEnabled)
    }

    @Test
    fun `关闭后再开启读取恢复为 true`() = runTest {
        repo.setLabEnabled(false)
        repo.setLabEnabled(true)
        assertEquals(true, current().labEnabled)
    }
}
