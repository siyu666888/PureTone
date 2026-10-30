package com.puretone.player.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * 睡眠定时「细粒度模式」测试（2026-10-30）。
 *
 * 在原有分钟档位之外新增 [SleepTimerMode.END_OF_TRACK]（播完本曲停止）与自定义分钟数，
 * 读写契约必须锁死：
 * - 模式、分钟数、到期时刻三者始终自洽（不存在「模式是关但分钟数还在」的中间态）；
 * - 旧版本数据（无 mode 键）能就地迁移为倒计时模式，老用户升级不丢档位；
 * - 脏数据（未知模式串 / 半截数据）不会让 App 卡在无法理解的状态；
 * - 倒计时到期后连同模式一起归一化为「关」（延续已修过的「定时结束后必须回到关」）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositorySleepTimerModeTest {

    private lateinit var repo: SettingsRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "sleep_timer_mode_test_settings.preferences_pb")
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
    fun `播完本曲模式写入后跨进程读取仍保留`() = runTest {
        repo.setSleepTimerEndOfTrack()
        val s = current()
        assertEquals(SleepTimerMode.END_OF_TRACK, s.sleepTimerMode)
        assertEquals(SleepTimerMode.END_OF_TRACK, repo.resolveSleepTimerMode(s))
        // 该模式没有倒计时：分钟数与两个到期时刻都必须为空
        assertEquals(0, s.sleepTimerMinutes)
        assertEquals(0L, s.sleepTimerDeadlineMs)
        assertEquals(0L, s.sleepTimerWallDeadlineMs)
    }

    @Test
    fun `播完本曲模式不会被过期判定误清`() = runTest {
        repo.setSleepTimerEndOfTrack()
        // 反复读取（每次都会走一遍归一化），没有到期时刻就不该被判定为过期
        repeat(3) { assertEquals(SleepTimerMode.END_OF_TRACK, current().sleepTimerMode) }
    }

    @Test
    fun `自定义分钟数按 1 到 600 分钟钳制`() = runTest {
        repo.setSleepTimerMinutes(7)
        assertEquals(7, current().sleepTimerMinutes)
        assertEquals(SleepTimerMode.MINUTES, current().sleepTimerMode)

        repo.setSleepTimerMinutes(99_999)
        assertEquals(PureToneSettings.SLEEP_MAX_MINUTES, current().sleepTimerMinutes)
        assertTrue(current().sleepTimerWallDeadlineMs > System.currentTimeMillis())

        repo.setSleepTimerMinutes(-5)
        assertEquals(0, current().sleepTimerMinutes)
        assertEquals(SleepTimerMode.OFF, current().sleepTimerMode)
    }

    @Test
    fun `旧版本数据无模式键时推断为倒计时模式`() = runTest {
        dataStore.edit {
            it[intPreferencesKey("playback_sleep_timer_minutes")] = 45
            it[longPreferencesKey("playback_sleep_timer_wall_deadline")] =
                System.currentTimeMillis() + 45 * 60_000L
        }
        val s = current()
        assertEquals(SleepTimerMode.MINUTES, s.sleepTimerMode)
        assertEquals(45, s.sleepTimerMinutes)
        assertTrue(repo.sleepRemainingMs(s) > 0L)
    }

    @Test
    fun `未知模式字符串回退为关并按旧字段重推断`() = runTest {
        dataStore.edit {
            it[stringPreferencesKey("playback_sleep_timer_mode")] = "END_OF_SONG"
            it[intPreferencesKey("playback_sleep_timer_minutes")] = 20
            it[longPreferencesKey("playback_sleep_timer_wall_deadline")] =
                System.currentTimeMillis() + 60_000L
        }
        val s = current()
        // 脏串不能被当成有效模式；但分钟数 + 到期时刻仍在，应回到倒计时而非丢掉
        assertEquals(SleepTimerMode.MINUTES, s.sleepTimerMode)
        assertEquals(20, s.sleepTimerMinutes)
    }

    @Test
    fun `半截数据（只有分钟数没有到期时刻）视为关`() = runTest {
        dataStore.edit {
            it[intPreferencesKey("playback_sleep_timer_minutes")] = 30
        }
        val s = current()
        assertEquals(SleepTimerMode.OFF, s.sleepTimerMode)
        assertEquals(0, s.sleepTimerMinutes)
    }

    @Test
    fun `倒计时到期后模式与分钟数一并回到关`() = runTest {
        repo.setSleepTimerMinutes(15)
        assertEquals(SleepTimerMode.MINUTES, current().sleepTimerMode)
        // 模拟「到期瞬间进程被杀、异步清理没落库」：模式与分钟数还在，到期时刻已过
        dataStore.edit {
            it[longPreferencesKey("playback_sleep_timer_wall_deadline")] =
                System.currentTimeMillis() - 1_000L
            it[longPreferencesKey("playback_sleep_timer_deadline")] = 1L
        }
        val s = current()
        assertEquals(SleepTimerMode.OFF, s.sleepTimerMode)
        assertEquals(0, s.sleepTimerMinutes)
        assertEquals(0L, s.sleepTimerWallDeadlineMs)
        assertEquals(0L, s.sleepTimerDeadlineMs)
    }

    @Test
    fun `取消定时清空模式与到期时刻`() = runTest {
        repo.setSleepTimerEndOfTrack()
        repo.clearSleepTimer()
        val s = current()
        assertEquals(SleepTimerMode.OFF, s.sleepTimerMode)
        assertEquals(0, s.sleepTimerMinutes)
        assertEquals(0L, s.sleepTimerWallDeadlineMs)
    }

    @Test
    fun `模式纯函数的迁移口径与读数一致`() = runTest {
        val now = System.currentTimeMillis()
        val legacy = PureToneSettings(
            sleepTimerMinutes = 30,
            sleepTimerWallDeadlineMs = now + 60_000L
        )
        assertEquals(SleepTimerMode.MINUTES, repo.resolveSleepTimerMode(legacy))
        assertEquals(SleepTimerMode.OFF, repo.resolveSleepTimerMode(PureToneSettings()))
        assertEquals(
            SleepTimerMode.END_OF_TRACK,
            repo.resolveSleepTimerMode(PureToneSettings(sleepTimerMode = SleepTimerMode.END_OF_TRACK))
        )
    }
}
