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
    /**
     * 睡眠定时模式（2026-10-30 细粒度定时）。
     *
     * - [SleepTimerMode.OFF]：关；
     * - [SleepTimerMode.MINUTES]：按分钟倒计时（自定义分钟数，用 [sleepTimerMinutes] 记录档位）；
     * - [SleepTimerMode.END_OF_TRACK]：当前这首播完就停（无时间上限）。
     *
     * 旧版本没有这个键：读取层按「分钟数 + 到期时刻」推断出 [SleepTimerMode.MINUTES]，
     * 老用户升级后原档位仍然生效。
     */
    val sleepTimerMode: SleepTimerMode = SleepTimerMode.OFF,
    /** 睡眠定时分钟数，0 = 关闭（UI 分段选中态）。定时到期/过期后由 [settings] 读取层归一化为 0。 */
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
    /**
     * 交叉淡化（Crossfade）时长（毫秒）。0 = 关闭（默认）。
     * 仅对无损/有损常规格式生效；APE 等需转码的格式在边界处退化为无缝/曲间停顿，
     * 不参与淡入淡出（转码是异步挂起操作，无法在边界精确对齐）。
     */
    val crossfadeMs: Int = 0,

    // ---------- replaygain_ ----------
    /** ReplayGain 总开关 */
    val replayGainEnabled: Boolean = false,
    /** ReplayGain 模式：TRACK（按曲目） / ALBUM（按专辑） */
    val replayGainMode: String = RG_TRACK,
    /** ReplayGain preamp（dB），范围 -12 ~ +12，叠加在 RG 增益之上 */
    val replayGainPreampDb: Float = 0.0f,

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
    val eqCustomPresets: Map<String, List<Int>> = emptyMap(),

    // ---------- lab_ ----------
    /**
     * 实验室（实验性功能）总开关。
     *
     * 关闭时，设置页「实验室」分类下的所有子项（入口 + 设置项，如响度 ReplayGain）
     * 全部隐藏。默认值取 true：**保留现有 ReplayGain 可见性、不回归**，
     * 用户可主动关闭以收起实验性功能。开关本身常驻可见，便于重新开启。
     */
    val labEnabled: Boolean = true
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

        const val RG_TRACK = "TRACK"
        const val RG_ALBUM = "ALBUM"
        const val RG_PREAMP_MIN_DB = -12f
        const val RG_PREAMP_MAX_DB = 12f

        /** 自定义睡眠定时的分钟数下限（1 分钟） */
        const val SLEEP_MIN_MINUTES = 1
        /** 自定义睡眠定时的分钟数上限（10 小时） */
        const val SLEEP_MAX_MINUTES = 600

        /** 交叉淡化时长下限（0 = 关） */
        const val CROSSFADE_MIN_MS = 0
        /** 交叉淡化时长上限（12 秒，过长会吞掉整首短歌） */
        const val CROSSFADE_MAX_MS = 12_000
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
        val sleepTimerMode = stringPreferencesKey("playback_sleep_timer_mode")
        val sleepTimerMinutes = intPreferencesKey("playback_sleep_timer_minutes")
        val sleepTimerDeadline = longPreferencesKey("playback_sleep_timer_deadline")
        val sleepTimerWallDeadline = longPreferencesKey("playback_sleep_timer_wall_deadline")
        val repeatMode = stringPreferencesKey("playback_repeat_mode")
        val shuffle = booleanPreferencesKey("playback_shuffle")
        val crossfadeMs = intPreferencesKey("playback_crossfade_ms")
        // replaygain_
        val replayGainEnabled = booleanPreferencesKey("replaygain_enabled")
        val replayGainMode = stringPreferencesKey("replaygain_mode")
        val replayGainPreamp = floatPreferencesKey("replaygain_preamp_db")
        // eq_
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqPreset = stringPreferencesKey("eq_preset")
        val eqBandLevels = stringPreferencesKey("eq_band_levels")
        val bassBoost = intPreferencesKey("eq_bass_boost")
        val virtualizer = intPreferencesKey("eq_virtualizer")
        /** 用户自定义均衡器预设（JSON：名称 → 10 段增益列表） */
        val eqCustomPresets = stringPreferencesKey("eq_custom_presets")
        // lab_
        val labEnabled = booleanPreferencesKey("lab_enabled")
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
            // 定时四要素先拆出来：旧数据迁移（无 mode 键）与过期归一化都要基于它们判定
            val rawMinutes = prefs[Keys.sleepTimerMinutes] ?: defaults.sleepTimerMinutes
            val deadlineMs = prefs[Keys.sleepTimerDeadline] ?: defaults.sleepTimerDeadlineMs
            val wallDeadlineMs = prefs[Keys.sleepTimerWallDeadline]
                ?: defaults.sleepTimerWallDeadlineMs
            PureToneSettings(
                themeMode = prefs[Keys.themeMode] ?: defaults.themeMode,
                amoledBlack = prefs[Keys.amoledBlack] ?: defaults.amoledBlack,
                dynamicColor = prefs[Keys.dynamicColor] ?: defaults.dynamicColor,
                gapless = prefs[Keys.gapless] ?: defaults.gapless,
                playbackSpeed = prefs[Keys.playbackSpeed] ?: defaults.playbackSpeed,
                resumeOnStart = prefs[Keys.resumeOnStart] ?: defaults.resumeOnStart,
                sleepTimerMode = SleepTimerMode.parse(prefs[Keys.sleepTimerMode]),
                sleepTimerMinutes = rawMinutes,
                sleepTimerDeadlineMs = deadlineMs,
                sleepTimerWallDeadlineMs = wallDeadlineMs,
                repeatMode = prefs[Keys.repeatMode] ?: defaults.repeatMode,
                shuffle = prefs[Keys.shuffle] ?: defaults.shuffle,
                crossfadeMs = prefs[Keys.crossfadeMs] ?: defaults.crossfadeMs,
                replayGainEnabled = prefs[Keys.replayGainEnabled] ?: defaults.replayGainEnabled,
                replayGainMode = prefs[Keys.replayGainMode] ?: defaults.replayGainMode,
                replayGainPreampDb = prefs[Keys.replayGainPreamp] ?: defaults.replayGainPreampDb,
                eqEnabled = prefs[Keys.eqEnabled] ?: defaults.eqEnabled,
                eqPreset = prefs[Keys.eqPreset] ?: defaults.eqPreset,
                eqBandLevels = decodeBands(prefs[Keys.eqBandLevels]) ?: defaults.eqBandLevels,
                bassBoostStrength = prefs[Keys.bassBoost] ?: defaults.bassBoostStrength,
                virtualizerStrength = prefs[Keys.virtualizer] ?: defaults.virtualizerStrength,
                eqCustomPresets = decodeCustomPresets(prefs[Keys.eqCustomPresets]),
                labEnabled = prefs[Keys.labEnabled] ?: defaults.labEnabled
            ).let { s ->
                // 定时状态归一化（此前只处理 MINUTES 的过期，现在多一种 END_OF_TRACK）：
                // 1) 倒计时型定时已过期 → 四个字段全部归零。老数据中「到期时刻还在、
                //    分钟数也还在」的残留会让设置页重启后仍高亮上次档位（已修过的同类 bug），
                //    读取层统一归一化为「关」，UI 与播放层拿到的口径永远一致；
                // 2) 写了分钟数却没有到期时刻（半截数据）→ 同样视为「关」；
                // 3) 旧版本数据没有 mode 键 → 按「分钟数 + 到期时刻」补出 MINUTES，
                //    老用户升级后原有档位继续生效。
                val mode = resolveSleepTimerMode(s)
                when {
                    mode == SleepTimerMode.MINUTES && sleepRemainingMs(s) <= 0L -> s.copy(
                        sleepTimerMode = SleepTimerMode.OFF,
                        sleepTimerMinutes = 0,
                        sleepTimerDeadlineMs = 0L,
                        sleepTimerWallDeadlineMs = 0L
                    )
                    mode == SleepTimerMode.OFF && s.sleepTimerMinutes > 0 -> s.copy(sleepTimerMinutes = 0)
                    mode != s.sleepTimerMode -> s.copy(sleepTimerMode = mode)
                    else -> s
                }
            }
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
            it[Keys.sleepTimerMode] = SleepTimerMode.OFF.name
            it[Keys.sleepTimerMinutes] = 0
            it[Keys.sleepTimerDeadline] = 0L
            it[Keys.sleepTimerWallDeadline] = 0L
        } else {
            val safe = minutes.coerceIn(PureToneSettings.SLEEP_MIN_MINUTES, PureToneSettings.SLEEP_MAX_MINUTES)
            it[Keys.sleepTimerMode] = SleepTimerMode.MINUTES.name
            it[Keys.sleepTimerMinutes] = safe
            it[Keys.sleepTimerDeadline] = SystemClock.elapsedRealtime() + safe * 60_000L
            // P2-14：同时写墙钟到期时刻（重启后仍然准确）
            it[Keys.sleepTimerWallDeadline] = System.currentTimeMillis() + safe * 60_000L
        }
    }

    /**
     * 「播完本曲停止」：不限时长，当前这首自然结束（含单曲循环再次走完、队列末首播完）即暂停。
     *
     * 不写任何到期时刻——它没有时间上限，靠播放器回调触发（见 PlaybackManager）。
     * 清空分钟数与到期时刻：避免与倒计时型定时的残留数据混用。
     */
    suspend fun setSleepTimerEndOfTrack() = edit {
        it[Keys.sleepTimerMode] = SleepTimerMode.END_OF_TRACK.name
        it[Keys.sleepTimerMinutes] = 0
        it[Keys.sleepTimerDeadline] = 0L
        it[Keys.sleepTimerWallDeadline] = 0L
    }

    /** 关闭定时（等价于 [setSleepTimerMinutes] 传 0，语义更直白） */
    suspend fun clearSleepTimer() = setSleepTimerMinutes(0)

    /**
     * 解析快照的定时模式。
     *
     * 旧版本只写分钟数与到期时刻、没有 mode 键：这里按「分钟数 > 0 且存在到期时刻」
     * 补出 [SleepTimerMode.MINUTES]，老用户升级后原档位继续生效；
     * 半截数据（只有分钟数、没有到期时刻）视为关。
     */
    fun resolveSleepTimerMode(s: PureToneSettings): SleepTimerMode {
        if (s.sleepTimerMode != SleepTimerMode.OFF) return s.sleepTimerMode
        return if (s.sleepTimerMinutes > 0 && hasSleepTimer(s)) SleepTimerMode.MINUTES
        else SleepTimerMode.OFF
    }

    /**
     * 是否存在「到期时刻」（倒计时型定时的持久化残留标志）。
     *
     * 注意：只判断到期时刻是否存在，**不代表定时仍未到期**——是否已过期要看
     * [sleepRemainingMs]。[SleepTimerMode.END_OF_TRACK] 不写到期时刻，故这里为 false。
     */
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
    /**
     * 交叉淡化时长（毫秒）。0 = 关闭；其余钳制到 [CROSSFADE_MIN_MS, CROSSFADE_MAX_MS]。
     * 经 DataStore 流回 [applySettings]，冷启动/设置变更都实时生效。
     */
    suspend fun setCrossfadeMs(ms: Int) = edit {
        it[Keys.crossfadeMs] = ms.coerceIn(PureToneSettings.CROSSFADE_MIN_MS, PureToneSettings.CROSSFADE_MAX_MS)
    }

    // ---------- lab_ ----------
    suspend fun setLabEnabled(enabled: Boolean) = edit { it[Keys.labEnabled] = enabled }

    // ---------- replaygain_ ----------
    suspend fun setReplayGainEnabled(enabled: Boolean) = edit { it[Keys.replayGainEnabled] = enabled }
    suspend fun setReplayGainMode(mode: String) = edit {
        if (mode in setOf(PureToneSettings.RG_TRACK, PureToneSettings.RG_ALBUM)) it[Keys.replayGainMode] = mode
    }
    suspend fun setReplayGainPreampDb(db: Float) = edit {
        it[Keys.replayGainPreamp] = db.coerceIn(PureToneSettings.RG_PREAMP_MIN_DB, PureToneSettings.RG_PREAMP_MAX_DB)
    }

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
