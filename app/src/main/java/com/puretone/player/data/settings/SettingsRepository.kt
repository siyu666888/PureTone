package com.puretone.player.data.settings

import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

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

    // ---------- playback_ ----------
    /** 无缝播放（MVP+） */
    val gapless: Boolean = true,
    /** 播放倍速 */
    val playbackSpeed: Float = 1.0f,
    /** 启动时续播上次歌曲 */
    val resumeOnStart: Boolean = true,
    /** 睡眠定时分钟数，0 = 关闭（仅用于 UI 分段选中态与设置展示） */
    val sleepTimerMinutes: Int = 0,
    /**
     * 睡眠定时「到期时刻」，基于 [SystemClock.elapsedRealtime]（毫秒），0 = 无定时。
     * 存时刻而非分钟数：进程重建后仍可算出真实剩余时间，且触发后清零即可防止
     * 「重启进程后定时复活」（旧实现只写分钟数，重启后 appliedSleepMin 归 -1 会重计时）。
     */
    val sleepTimerDeadlineMs: Long = 0L,
    /**
     * 睡眠定时「到期时刻」的墙钟版本（[System.currentTimeMillis]），0 = 无定时。
     *
     * P2-14：elapsedRealtime 在设备重启后归零，长离机重启会算出离谱的剩余时间
     * （定时失效或立刻触发）。墙钟不受重启影响，是更可靠的基准；
     * 这里两者都写，读取时优先墙钟，旧数据（只有 elapsed 基准）仍可兼容。
     */
    val sleepTimerWallDeadlineMs: Long = 0L,
    /** 循环模式：OFF / ALL / ONE */
    // P3-13：默认改为 ALL（列表放完循环）。旧默认 REPEAT_OFF 会让全新安装的
    // 激活态是 OFF——UI 把 OFF 归并为「顺序播放」循环图标，但底层行为是「放完即停」，
    // 图标/状态/行为三者不一致。三态循环（顺序→随机→单曲）本就不含「停止」态，
    // 故 OFF 规整为 ALL：SEQUENTIAL 始终表示「列表循环」，与图标一致。
    val repeatMode: String = REPEAT_ALL,
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
    val virtualizerStrength: Int = 0,
    /**
     * 用户自定义均衡器预设：名称 → 10 段增益（dB，mB）。
     * 非 DataStore 直存字段，由 [Keys.eqCustomPresets] 经 JSON 编解码派生。
     */
    val eqCustomPresets: Map<String, List<Int>> = emptyMap()
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
        // playback_
        val gapless = booleanPreferencesKey("playback_gapless")
        val playbackSpeed = floatPreferencesKey("playback_speed")
        val resumeOnStart = booleanPreferencesKey("playback_resume_on_start")
        val sleepTimerMinutes = intPreferencesKey("playback_sleep_timer_minutes")
        val sleepTimerDeadline = longPreferencesKey("playback_sleep_timer_deadline")
        val sleepTimerWallDeadline = longPreferencesKey("playback_sleep_timer_wall_deadline")
        val repeatMode = stringPreferencesKey("playback_repeat_mode")
        val shuffle = booleanPreferencesKey("playback_shuffle")
        // eq_
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = stringPreferencesKey("eq_preset")
        val eqBandLevels = stringPreferencesKey("eq_band_levels")
        val bassBoost = intPreferencesKey("eq_bass_boost")
        val virtualizer = intPreferencesKey("eq_virtualizer")
        /** 用户自定义均衡器预设（JSON：名称 → 10 段增益列表） */
        val eqCustomPresets = stringPreferencesKey("eq_custom_presets")
    }

    /**
     * 全量设置快照流。
     *
     * 兜底：DataStore 文件损坏 / 不可读时 `map` 抛 IOException 会让所有收集方
     * （stateIn、collect）收到异常，直接表现为冷启动崩溃。这里 catch 后回退
     * 到默认设置，保证 App 可用（用户改动丢失但不会打不开）。
     */
    val settings: Flow<PureToneSettings> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            val defaults = PureToneSettings()
            PureToneSettings(
                themeMode = prefs[Keys.themeMode] ?: defaults.themeMode,
                amoledBlack = prefs[Keys.amoledBlack] ?: defaults.amoledBlack,
                dynamicColor = prefs[Keys.dynamicColor] ?: defaults.dynamicColor,
                gapless = prefs[Keys.gapless] ?: defaults.gapless,
                playbackSpeed = prefs[Keys.playbackSpeed] ?: defaults.playbackSpeed,
                resumeOnStart = prefs[Keys.resumeOnStart] ?: defaults.resumeOnStart,
                sleepTimerMinutes = prefs[Keys.sleepTimerMinutes] ?: defaults.sleepTimerMinutes,
                sleepTimerDeadlineMs = prefs[Keys.sleepTimerDeadline] ?: defaults.sleepTimerDeadlineMs,
                sleepTimerWallDeadlineMs = prefs[Keys.sleepTimerWallDeadline]
                    ?: defaults.sleepTimerWallDeadlineMs,
                repeatMode = prefs[Keys.repeatMode] ?: defaults.repeatMode,
                shuffle = prefs[Keys.shuffle] ?: defaults.shuffle,
                eqEnabled = prefs[Keys.eqEnabled] ?: defaults.eqEnabled,
                eqPreset = prefs[Keys.eqPreset] ?: defaults.eqPreset,
                eqBandLevels = decodeBands(prefs[Keys.eqBandLevels]) ?: defaults.eqBandLevels,
                bassBoostStrength = prefs[Keys.bassBoost] ?: defaults.bassBoostStrength,
                virtualizerStrength = prefs[Keys.virtualizer] ?: defaults.virtualizerStrength,
                eqCustomPresets = decodeCustomPresets(prefs[Keys.eqCustomPresets])
            )
        }

    // ---------- 写入 ----------

    suspend fun setThemeMode(mode: String) = edit { it[Keys.themeMode] = mode }
    suspend fun setAmoledBlack(enabled: Boolean) = edit { it[Keys.amoledBlack] = enabled }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.dynamicColor] = enabled }

    suspend fun setGapless(enabled: Boolean) = edit { it[Keys.gapless] = enabled }
    suspend fun setPlaybackSpeed(speed: Float) = edit { it[Keys.playbackSpeed] = speed }
    suspend fun setResumeOnStart(enabled: Boolean) = edit { it[Keys.resumeOnStart] = enabled }
    /**
     * 设置睡眠定时：同时写入分钟数（UI 选中态）与「到期时刻」（实际计时依据）。
     * minutes <= 0 表示取消，两项都清零。
     */
    suspend fun setSleepTimerMinutes(minutes: Int) = edit {
        if (minutes <= 0) {
            it[Keys.sleepTimerMinutes] = 0
            it[Keys.sleepTimerDeadline] = 0L
            it[Keys.sleepTimerWallDeadline] = 0L
        } else {
            it[Keys.sleepTimerMinutes] = minutes
            it[Keys.sleepTimerDeadline] = SystemClock.elapsedRealtime() + minutes * 60_000L
            // P2-14：同时写墙钟到期时刻（重启后仍然准确）
            it[Keys.sleepTimerWallDeadline] = System.currentTimeMillis() + minutes * 60_000L
        }
    }

    /** 定时到期后调用：只清「到期时刻」，保留分钟数供 UI 显示上次选择 */
    suspend fun clearSleepTimerDeadline() = edit {
        it[Keys.sleepTimerDeadline] = 0L
        it[Keys.sleepTimerWallDeadline] = 0L
    }

    /** 是否存在未到期的定时（用于决定是否需要清理） */
    fun hasSleepTimer(s: PureToneSettings): Boolean =
        s.sleepTimerDeadlineMs > 0L || s.sleepTimerWallDeadlineMs > 0L

    /**
     * 剩余毫秒数（0 表示无定时/已到期）。
     *
     * P2-14：优先取墙钟基准——elapsedRealtime 在重启后归零会让剩余时间失真；
     * 只有旧版本写入的数据（仅有 elapsed 基准）才回退到 elapsed 计算。
     */
    fun sleepRemainingMs(s: PureToneSettings): Long {
        val wallDeadline = s.sleepTimerWallDeadlineMs
        if (wallDeadline > 0L) {
            return (wallDeadline - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        val deadline = s.sleepTimerDeadlineMs
        if (deadline <= 0L) return 0L
        return (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }
    suspend fun setRepeatMode(mode: String) = edit { it[Keys.repeatMode] = mode }
    suspend fun setShuffle(enabled: Boolean) = edit { it[Keys.shuffle] = enabled }

    suspend fun setEqEnabled(enabled: Boolean) = edit { it[Keys.eqEnabled] = enabled }
    suspend fun setEqPreset(preset: String) = edit { it[Keys.eqPreset] = preset }
    suspend fun setEqBandLevels(levels: List<Int>) = edit {
        it[Keys.eqBandLevels] = levels.joinToString(",")
    }

    /**
     * 手动调节单个频段（P2-1）：频段值与「预设切 CUSTOM」一次写入。
     *
     * 旧实现只写频段不动预设：用户手动改完，旧预设胶囊仍高亮，
     * 显示的频段值与预设矩阵早已不符（误导）。拆成两次 edit 还会有
     * 中间态被 UI 看到，故合并为单次事务。
     */
    suspend fun setBandLevelCustom(band: Int, levelMb: Int) = edit { prefs ->
        val current = decodeBands(prefs[Keys.eqBandLevels]) ?: PureToneSettings().eqBandLevels
        if (band in current.indices) {
            val next = current.toMutableList().apply { set(band, levelMb) }
            prefs[Keys.eqBandLevels] = next.joinToString(",")
        }
        prefs[Keys.eqPreset] = PureToneSettings.EQ_CUSTOM
    }

    suspend fun setBassBoostStrength(strength: Int) = edit { it[Keys.bassBoost] = strength }
    suspend fun setVirtualizerStrength(strength: Int) = edit { it[Keys.virtualizer] = strength }

    /** 均衡器预设应用（频段 + 强度一次写入） */
    suspend fun applyEqPreset(preset: String, bandLevels: List<Int>) = edit {
        it[Keys.eqPreset] = preset
        it[Keys.eqBandLevels] = bandLevels.joinToString(",")
    }

    /**
     * 保存当前 10 段曲线为用户自定义预设（按名称覆盖）。
     * 名称去首尾空白；同名视为更新。
     */
    suspend fun saveCustomEqPreset(name: String, levels: List<Int>) = edit { prefs ->
        if (levels.size != PureToneSettings.EQ_BAND_COUNT) return@edit
        val current = decodeCustomPresets(prefs[Keys.eqCustomPresets])
        val next = current.toMutableMap().apply { put(name.trim(), levels) }
        prefs[Keys.eqCustomPresets] = encodeCustomPresets(next)
    }

    /** 删除指定名称的自定义预设（不存在则无操作） */
    suspend fun deleteCustomEqPreset(name: String) = edit { prefs ->
        val current = decodeCustomPresets(prefs[Keys.eqCustomPresets])
        if (name in current) {
            val next = current.toMutableMap().apply { remove(name) }
            prefs[Keys.eqCustomPresets] = encodeCustomPresets(next)
        }
    }

    /** 应用用户自定义预设：写入频段并把 eqPreset 标记为自定义名称 */
    suspend fun applyCustomEqPreset(name: String) = edit { prefs ->
        val bands = decodeCustomPresets(prefs[Keys.eqCustomPresets])[name] ?: return@edit
        prefs[Keys.eqPreset] = name
        prefs[Keys.eqBandLevels] = bands.joinToString(",")
    }

    /** 解码自定义预设 JSON（损坏/为空返回空 Map，不崩溃） */
    private fun decodeCustomPresets(s: String?): Map<String, List<Int>> {
        if (s.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(s)
            val map = LinkedHashMap<String, List<Int>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = obj.getJSONArray(key)
                if (arr.length() == PureToneSettings.EQ_BAND_COUNT) {
                    map[key] = (0 until arr.length()).map { arr.getInt(it) }
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    /** 编码自定义预设为 JSON */
    private fun encodeCustomPresets(map: Map<String, List<Int>>): String =
        JSONObject().apply { map.forEach { (k, v) -> put(k, JSONArray(v)) } }.toString()

    // ---------- 续播会话 ----------

    private val keyLastQueueIds = stringPreferencesKey("playback_last_queue_ids")
    private val keyLastIndex = intPreferencesKey("playback_last_index")
    private val keyLastPosition = longPreferencesKey("playback_last_position")

    /** 上次播放会话（队列歌曲 id、索引、位置），空队列时为 null */
    val playbackSession: Flow<PlaybackSession?> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
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

    // ---------- 曲库扫描 ----------

    private val keyLastScanMs = longPreferencesKey("library_last_scan_ms")

    /**
     * 上次成功同步曲库的时间戳；0 表示从未同步（下次必须走全量）。
     *
     * 增量扫描用它作为「只看这之后改动的行」的下界。
     */
    suspend fun lastScanMs(): Long = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[keyLastScanMs] ?: 0L }
        .first()

    suspend fun setLastScanMs(ms: Long) = edit {
        it[keyLastScanMs] = ms
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
