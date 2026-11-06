package com.puretone.player.playback.cast

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 历史投屏设备仓库（跨重启持久化）。
 *
 * 为什么需要它：DLNA 发现依赖电视对 SSDP 的应答，而真机上「电视刚唤醒不回 M-SEARCH」
 * 是常态（本项目实测连续多轮 0 设备）。用户此前**投成功过**的设备是确定存在的资产，
 * 存在本地就能在搜索空手时直接把上次那台电视列出来，不必「重启 Wi-Fi 再试一次」。
 *
 * 存储复用 [SettingsRepository] 同一个 DataStore 单例（同一文件、不同 key）：
 * 这类数据量极小（≤8 条），单独开文件只会多一份 IO 与迁移负担；
 * 键名独立成 `cast_history`，与设置项不冲突。
 *
 * 与 [CastDeviceDiscovery] 内 knownDevices 缓存的分工：那个是**进程内**的 IP 缓存，用于空结果时
 * 发 unicast M-SEARCH 唤醒设备；这里存的是**跨会话**的「投成功过」清单，用于 UI 展示。
 */
@Singleton
class CastHistoryStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    private val key = stringPreferencesKey("cast_history")

    /** 历史设备列表（最近使用的在前，最多 [CastHistoryRules.MAX_ENTRIES] 条） */
    val entries: Flow<List<CastHistoryEntry>> = dataStore.data
        // 文件损坏 / 不可读时回退空列表：历史列表丢了不影响播放，绝不能因此崩溃
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> CastHistoryRules.decode(prefs[key]) }
        .distinctUntilChanged()

    /** 记一次投屏成功（由 [DlnaCastManager] 在投送真正成功后调用） */
    suspend fun record(device: CastDevice) {
        if (device.address.isBlank()) return
        dataStore.edit { prefs ->
            val current = CastHistoryRules.decode(prefs[key])
            val next = CastHistoryRules.record(current, device, System.currentTimeMillis())
            prefs[key] = CastHistoryRules.encode(next)
        }
    }

    /** 从历史里移除一台设备（按 IP 定位） */
    suspend fun forget(address: String) {
        dataStore.edit { prefs ->
            val current = CastHistoryRules.decode(prefs[key])
            prefs[key] = CastHistoryRules.encode(CastHistoryRules.forget(current, address))
        }
    }
}
