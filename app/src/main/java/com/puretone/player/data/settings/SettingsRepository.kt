package com.puretone.player.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PureTone 全量设置快照。
 *
 * 键名前缀分组（与《主题偏好存储模块设计说明》对齐）：
 * - `theme_`   主题模式 / AMOLED 纯黑 / 动态取色 / 封面取色
 * - `playback_` 无缝播放 / 倍速 / 续播 / 睡眠定时 / 播放模式
 * - `eq_`      均衡器开关 / 预设 / 频段 / 重低音 / 环绕
 */
data class PureToneSettings(
    // ---------- theme_ ----------
    /** SYSTEM / LIGHT / DARK / BLACK */
    val themeMode: String = THEME_SYSTEM,
    /** 深色模式下表面用纯黑（OLED 省电） */
    val amoledBlack: Boolean = false,
    /** 系统壁纸动态取色（决策 D2：默认关） */
    val dynamicColor: Boolean = false,
    /** 播放页封面取色（决策 D2：默认开） */
    val coverPalette: Boolean = true,

    // ---------- playback_ ----------
    /** 无缝播放（MVP+） */
    val gapless: Boolean = true,
    /** 播放倍速 */
    val playbackSpeed: Float = 1.0f,
    /** 启动时续播上次歌曲 */
    val resumeOnStart: Boolean = true,
    /** 睡眠定时分钟数，0 = 关闭 */
    val sleepTimerMinutes: Int = 0,
    /** 循环模式：OFF / ALL / ONE */
    val repeatMode: String = REPEAT_OFF,
    val shuffle: Boolean = false,

    // ---------- eq_ ----------
    val eqEnabled: Boolean = false,
    /** 预设名：CUSTOM / FLAT / POP / ROCK / JAZZ / CLASSICAL / VOCAL / BASS */
    val eqPreset: String = EQ_CUSTOM,
    /** 10 段增益，单位 dB，范围 -15 ~ +15 */
    val eqBandLevels: List<Int> = List(10) { 0 },
    /** 重低音强度 0 ~ 1000 */
    val bassBoostStrength: Int = 0,
    /** 环绕声强度 0 ~ 1000 */
    val virtualizerStrength: Int = 0
) {
    companion object {
        const val THEME_SYSTEM = "SYSTEM"
        const val THEME_LIGHT = "LIGHT"
        const val THEME_DARK = "DARK"
        const val THEME_BLACK = "BLACK"

        const val REPEAT_OFF = "OFF"
        const val REPEAT_ALL = "ALL"
        const val REPEAT_ONE = "ONE"

        const val EQ_CUSTOM = "CUSTOM"
        const val EQ_FLAT = "FLAT"
        const val EQ_POP = "POP"
        const val EQ_ROCK = "ROCK"
        const val EQ_JAZZ = "JAZZ"
        const val EQ_CLASSICAL = "CLASSICAL"
        const val EQ_VOCAL = "VOCAL"
        const val EQ_BASS = "BASS"

        const val EQ_BAND_COUNT = 10
    }
}

/**
 * 设置仓库：DataStore 单文件 + 全量快照 Flow。
 *
 * UI 层只消费 [settings] 一个 Flow；写入方法为 suspend，冲突由
 * DataStore 的串行化保证。
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    private object Keys {
        // theme_
        val themeMode = stringPreferencesKey("theme_mode")
        val amoledBlack = booleanPreferencesKey("theme_amoled_black")
        val dynamicColor = booleanPreferencesKey("theme_dynamic_color")
        val coverPalette = booleanPreferencesKey("theme_cover_palette")
        // playback_
        val gapless = booleanPreferencesKey("playback_gapless")
        val playbackSpeed = floatPreferencesKey("playback_speed")
        val resumeOnStart = booleanPreferencesKey("playback_resume_on_start")
        val sleepTimerMinutes = intPreferencesKey("playback_sleep_timer_minutes")
        val repeatMode = stringPreferencesKey("playback_repeat_mode")
        val shuffle = booleanPreferencesKey("playback_shuffle")
        // eq_
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = stringPreferencesKey("eq_preset")
        val eqBandLevels = stringPreferencesKey("eq_band_levels")
        val bassBoost = intPreferencesKey("eq_bass_boost")
        val virtualizer = intPreferencesKey("eq_virtualizer")
    }

    val settings: Flow<PureToneSettings> = dataStore.data.map { prefs ->
        val defaults = PureToneSettings()
        PureToneSettings(
            themeMode = prefs[Keys.themeMode] ?: defaults.themeMode,
            amoledBlack = prefs[Keys.amoledBlack] ?: defaults.amoledBlack,
            dynamicColor = prefs[Keys.dynamicColor] ?: defaults.dynamicColor,
            coverPalette = prefs[Keys.coverPalette] ?: defaults.coverPalette,
            gapless = prefs[Keys.gapless] ?: defaults.gapless,
            playbackSpeed = prefs[Keys.playbackSpeed] ?: defaults.playbackSpeed,
            resumeOnStart = prefs[Keys.resumeOnStart] ?: defaults.resumeOnStart,
            sleepTimerMinutes = prefs[Keys.sleepTimerMinutes] ?: defaults.sleepTimerMinutes,
            repeatMode = prefs[Keys.repeatMode] ?: defaults.repeatMode,
            shuffle = prefs[Keys.shuffle] ?: defaults.shuffle,
            eqEnabled = prefs[Keys.eqEnabled] ?: defaults.eqEnabled,
            eqPreset = prefs[Keys.eqPreset] ?: defaults.eqPreset,
            eqBandLevels = decodeBands(prefs[Keys.eqBandLevels]) ?: defaults.eqBandLevels,
            bassBoostStrength = prefs[Keys.bassBoost] ?: defaults.bassBoostStrength,
            virtualizerStrength = prefs[Keys.virtualizer] ?: defaults.virtualizerStrength
        )
    }

    // ---------- 写入 ----------

    suspend fun setThemeMode(mode: String) = edit { it[Keys.themeMode] = mode }
    suspend fun setAmoledBlack(enabled: Boolean) = edit { it[Keys.amoledBlack] = enabled }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.dynamicColor] = enabled }
    suspend fun setCoverPalette(enabled: Boolean) = edit { it[Keys.coverPalette] = enabled }

    suspend fun setGapless(enabled: Boolean) = edit { it[Keys.gapless] = enabled }
    suspend fun setPlaybackSpeed(speed: Float) = edit { it[Keys.playbackSpeed] = speed }
    suspend fun setResumeOnStart(enabled: Boolean) = edit { it[Keys.resumeOnStart] = enabled }
    suspend fun setSleepTimerMinutes(minutes: Int) = edit { it[Keys.sleepTimerMinutes] = minutes }
    suspend fun setRepeatMode(mode: String) = edit { it[Keys.repeatMode] = mode }
    suspend fun setShuffle(enabled: Boolean) = edit { it[Keys.shuffle] = enabled }

    suspend fun setEqEnabled(enabled: Boolean) = edit { it[Keys.eqEnabled] = enabled }
    suspend fun setEqPreset(preset: String) = edit { it[Keys.eqPreset] = preset }
    suspend fun setEqBandLevels(levels: List<Int>) = edit {
        it[Keys.eqBandLevels] = levels.joinToString(",")
    }

    suspend fun setBassBoostStrength(strength: Int) = edit { it[Keys.bassBoost] = strength }
    suspend fun setVirtualizerStrength(strength: Int) = edit { it[Keys.virtualizer] = strength }

    /** 均衡器预设应用（频段 + 强度一次写入） */
    suspend fun applyEqPreset(preset: String, bandLevels: List<Int>) = edit {
        it[Keys.eqPreset] = preset
        it[Keys.eqBandLevels] = bandLevels.joinToString(",")
    }

    // ---------- 续播会话 ----------

    private val keyLastQueueIds = stringPreferencesKey("playback_last_queue_ids")
    private val keyLastIndex = intPreferencesKey("playback_last_index")
    private val keyLastPosition = longPreferencesKey("playback_last_position")

    /** 上次播放会话（队列歌曲 id、索引、位置），空队列时为 null */
    val playbackSession: Flow<PlaybackSession?> = dataStore.data.map { prefs ->
        val raw = prefs[keyLastQueueIds] ?: return@map null
        val ids = raw.split(",").mapNotNull { it.trim().toLongOrNull() }
        if (ids.isEmpty()) null else PlaybackSession(
            songIds = ids,
            index = prefs[keyLastIndex] ?: 0,
            positionMs = prefs[keyLastPosition] ?: 0L
        )
    }

    suspend fun savePlaybackSession(songIds: List<Long>, index: Int, positionMs: Long) = edit {
        it[keyLastQueueIds] = songIds.joinToString(",")
        it[keyLastIndex] = index
        it[keyLastPosition] = positionMs
    }

    suspend fun clearPlaybackSession() = edit {
        it.remove(keyLastQueueIds)
        it.remove(keyLastIndex)
        it.remove(keyLastPosition)
    }

    /** 上次播放会话快照 */
    data class PlaybackSession(
        val songIds: List<Long>,
        val index: Int,
        val positionMs: Long
    )

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun decodeBands(raw: String?): List<Int>? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (parsed.size == PureToneSettings.EQ_BAND_COUNT) parsed else null
    }
}
