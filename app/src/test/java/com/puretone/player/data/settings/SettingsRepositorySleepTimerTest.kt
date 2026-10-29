package com.puretone.player.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
 * 睡眠定时「定时结束后重启，档位未恢复到关」回归测试。
 *
 * 背景：旧实现在定时到期/失效后只清「到期时刻」，保留 sleepTimerMinutes
 * 「供 UI 显示上次选择」。结果定时结束后重启，设置页分段控件仍高亮上次档位（如 30m），
 * 与「定时已关」不符。
 *
 * 修复：读取层把过期定时归一化为「关」（分钟数 + 两个到期时刻全 0），播放层过期分支
 * 也改用 setSleepTimerMinutes(0) 做持久化兜底。这里锁死：
 * - 未过期定时读取时保留档位与到期时刻（不误伤有效定时）；
 * - 已过期定时读取时三字段全部归零（核心回归）；
 * - 归一化依赖的 hasSleepTimer / sleepRemainingMs 判定口径正确；
 * - 取消定时后三字段全清；
 * - 无定时默认读取为「关」且不抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositorySleepTimerTest {

    private lateinit var repo: SettingsRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "sleep_timer_test_settings.preferences_pb")
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
    fun `未过期的定时读取时保留档位与到期时刻`() = runTest {
        repo.setSleepTimerMinutes(30)
        val s = current()
        assertEquals(30, s.sleepTimerMinutes)
        assertTrue(s.sleepTimerWallDeadlineMs > System.currentTimeMillis())
        assertTrue(repo.sleepRemainingMs(s) > 0L)
    }

    @Test
    fun `已过期的定时读取时归一化为关`() = runTest {
        // 模拟「定时到期瞬间进程被杀、异步清理没落库」后的残留：分钟数 + 已过期到期时刻
        dataStore.edit {
            it[intPreferencesKey("playback_sleep_timer_minutes")] = 30
            it[longPreferencesKey("playback_sleep_timer_wall_deadline")] =
                System.currentTimeMillis() - 60_000L
            // elapsed 基准也留一个正数占位，保证 hasSleepTimer 为真
            it[longPreferencesKey("playback_sleep_timer_deadline")] = 1L
        }
        val s = current()
        // 关键断言：三个字段全部归零，设置页分段控件回到「关」
        assertEquals(0, s.sleepTimerMinutes)
        assertEquals(0L, s.sleepTimerWallDeadlineMs)
        assertEquals(0L, s.sleepTimerDeadlineMs)
        assertEquals(0L, repo.sleepRemainingMs(s))
    }

    @Test
    fun `过期判定的纯函数依据正确`() = runTest {
        // 归一化依赖 hasSleepTimer && sleepRemainingMs<=0，单独锁死这两个判定口径
        val expired = PureToneSettings(
            sleepTimerMinutes = 45,
            sleepTimerWallDeadlineMs = System.currentTimeMillis() - 1_000L,
            sleepTimerDeadlineMs = 1L
        )
        assertTrue(repo.hasSleepTimer(expired))
        assertEquals(0L, repo.sleepRemainingMs(expired))
    }

    @Test
    fun `取消定时后三字段全部归零`() = runTest {
        repo.setSleepTimerMinutes(60)
        repo.setSleepTimerMinutes(0)
        val s = current()
        assertEquals(0, s.sleepTimerMinutes)
        assertEquals(0L, s.sleepTimerWallDeadlineMs)
        assertEquals(0L, s.sleepTimerDeadlineMs)
    }

    @Test
    fun `无定时默认读取为关且不抛异常`() = runTest {
        val s = current()
        assertEquals(0, s.sleepTimerMinutes)
        assertEquals(0L, s.sleepTimerWallDeadlineMs)
        assertEquals(0L, s.sleepTimerDeadlineMs)
    }
}
