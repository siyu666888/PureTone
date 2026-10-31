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
 * 交叉淡化（Crossfade）设置数据层回归测试。
 *
 * 锁定：默认关闭（0）、写入往返、以及越界值被钳制到 [CROSSFADE_MIN_MS, CROSSFADE_MAX_MS]。
 * 该设置直接驱动 [com.puretone.player.playback.PlaybackManager] 的交叉淡化时长，
 * 越界钳制错误会导致 UI 滑到端点却写入非法时长。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryCrossfadeTest {

    private lateinit var repo: SettingsRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "crossfade_test_settings.preferences_pb")
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
    fun `默认交叉淡化为关闭（0）`() = runTest {
        assertEquals(0, current().crossfadeMs)
    }

    @Test
    fun `写入与读取往返一致`() = runTest {
        repo.setCrossfadeMs(3000)
        assertEquals(3000, current().crossfadeMs)
        repo.setCrossfadeMs(7500)
        assertEquals(7500, current().crossfadeMs)
    }

    @Test
    fun `超过最大值被钳制到上限`() = runTest {
        repo.setCrossfadeMs(99_999)
        assertEquals(PureToneSettings.CROSSFADE_MAX_MS, current().crossfadeMs)
    }

    @Test
    fun `负数被钳制到下限（关闭）`() = runTest {
        repo.setCrossfadeMs(-500)
        assertEquals(PureToneSettings.CROSSFADE_MIN_MS, current().crossfadeMs)
    }

    @Test
    fun `边界值恰好为上限被保留`() = runTest {
        repo.setCrossfadeMs(PureToneSettings.CROSSFADE_MAX_MS)
        assertEquals(PureToneSettings.CROSSFADE_MAX_MS, current().crossfadeMs)
    }
}
