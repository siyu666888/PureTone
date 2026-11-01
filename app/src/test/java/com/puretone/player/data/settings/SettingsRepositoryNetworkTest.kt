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
 * 联网元数据总开关（networkMetadataEnabled）数据层回归测试（P2-4）。
 *
 * 该开关是隐私边界：关闭后 App 不得向 iTunes / LRCLIB 发出任何请求。
 * 这里只锁死数据层——键名接线、默认值、写读往返——避免接线笔误导致
 * 「用户关了开关但请求照发」。用例层的短路行为见 NetworkMetadataGateTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryNetworkTest {

    private lateinit var repo: SettingsRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "network_test_settings.preferences_pb")
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
    fun `联网开关默认开启（保持现有功能可用）`() = runTest {
        assertEquals(true, current().networkMetadataEnabled)
    }

    @Test
    fun `关闭后读取为 false`() = runTest {
        repo.setNetworkMetadataEnabled(false)
        assertEquals(false, current().networkMetadataEnabled)
    }

    @Test
    fun `关闭后再开启读取恢复为 true`() = runTest {
        repo.setNetworkMetadataEnabled(false)
        repo.setNetworkMetadataEnabled(true)
        assertEquals(true, current().networkMetadataEnabled)
    }

    @Test
    fun `关闭联网不影响实验室开关（两个开关彼此独立）`() = runTest {
        repo.setNetworkMetadataEnabled(false)
        // 隐私开关与实验室开关分属不同键，不能互相串扰
        assertEquals(true, current().labEnabled)
    }
}
