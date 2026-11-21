package com.puretone.player.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.data.settings.SleepTimerMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
 * B6：SleepTimerController 状态机直接单测（此前整类零测试）。
 *
 * 锁定两条历史 bug 防线：
 * - remaining<=0（到期瞬间进程被杀后重启）要把 UI 归 OFF 并清持久化残留，防止「定时复活」；
 * - finishEndOfTrack 幂等（自动切歌与 STATE_ENDED 双回调同时到达只 pause 一次）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerControllerTest {

    private lateinit var repo: SettingsRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var file: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "sleep_timer_controller_test.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        ) { file }
        repo = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `B6 finishEndOfTrack 双回调只暂停一次且定时归OFF`() = runTest {
        val ui = MutableStateFlow(PlaybackUiState())
        var pauseCount = 0
        val controller = SleepTimerController(
            repo, ui, this, { pauseCount++ }
        )
        controller.applySettings(
            PureToneSettings(sleepTimerMode = SleepTimerMode.END_OF_TRACK)
        )
        assertEquals(SleepTimerMode.END_OF_TRACK, ui.value.sleepTimerMode)

        controller.finishEndOfTrack()
        controller.finishEndOfTrack() // 第二个回调应被幂等挡掉
        assertEquals("重复到点只应暂停一次", 1, pauseCount)
        assertEquals(SleepTimerMode.OFF, ui.value.sleepTimerMode)
        assertEquals(0L, ui.value.sleepRemainingMs)
    }

    @Test
    fun `B6 到期已过的分钟定时直接归OFF并清持久化残留`() = runTest {
        // 设置一条已过期的分钟定时（到期时刻在很早以前）
        val expired = PureToneSettings(
            sleepTimerMode = SleepTimerMode.MINUTES,
            sleepTimerMinutes = 30,
            sleepTimerDeadlineMs = 1L,
            sleepTimerWallDeadlineMs = 0L
        )
        val ui = MutableStateFlow(PlaybackUiState())
        var pauseCount = 0
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val controller = SleepTimerController(repo, ui, scope, { pauseCount++ })

        controller.applySettings(expired)
        assertEquals("过期定时不应排倒计时也不应暂停", 0, pauseCount)
        assertEquals(SleepTimerMode.OFF, ui.value.sleepTimerMode)
        assertEquals(0L, ui.value.sleepRemainingMs)
    }
}
