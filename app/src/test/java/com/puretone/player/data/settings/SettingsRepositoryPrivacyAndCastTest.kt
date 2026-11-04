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
 * 两个新增布尔偏好的数据层回归：
 *
 * - `listeningStatsEnabled`：隐私边界，默认开（不回归既有听歌数据），关闭后两个写库
 *   收口必须短路（写入侧见 ListeningStatsGateTest）。
 * - `castVolumeFollowPhone`：投屏音量跟随手机音量键，默认开。
 *
 * 这里只锁键名接线 / 默认值 / 写读往返 / 互不串扰，避免「用户关了但键写错」这类
 * 静态检查发现不了的接线笔误。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryPrivacyAndCastTest {

    private lateinit var repo: SettingsRepository
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "privacy_cast_test_settings.preferences_pb")
        file.delete()
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
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
    fun `统计开关默认开启（保持既有听歌数据可用）`() = runTest {
        assertEquals(true, current().listeningStatsEnabled)
    }

    @Test
    fun `统计开关关闭后读取为 false`() = runTest {
        repo.setListeningStatsEnabled(false)
        assertEquals(false, current().listeningStatsEnabled)
    }

    @Test
    fun `统计开关关闭后再开启恢复为 true`() = runTest {
        repo.setListeningStatsEnabled(false)
        repo.setListeningStatsEnabled(true)
        assertEquals(true, current().listeningStatsEnabled)
    }

    @Test
    fun `投屏音量跟随默认开启`() = runTest {
        assertEquals(true, current().castVolumeFollowPhone)
    }

    @Test
    fun `投屏音量跟随可关闭再开启`() = runTest {
        repo.setCastVolumeFollowPhone(false)
        assertEquals(false, current().castVolumeFollowPhone)
        repo.setCastVolumeFollowPhone(true)
        assertEquals(true, current().castVolumeFollowPhone)
    }

    @Test
    fun `三个隐私与投屏开关互不串扰`() = runTest {
        repo.setListeningStatsEnabled(false)
        repo.setCastVolumeFollowPhone(false)
        val s = current()
        assertEquals(false, s.listeningStatsEnabled)
        assertEquals(false, s.castVolumeFollowPhone)
        assertEquals(true, s.networkMetadataEnabled)
        assertEquals(true, s.labEnabled)
    }
}
