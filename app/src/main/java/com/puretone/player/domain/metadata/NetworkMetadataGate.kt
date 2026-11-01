package com.puretone.player.domain.metadata

import com.puretone.player.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 联网元数据总开关守卫（P2-4）。
 *
 * 用户可在设置页关闭「联网补全元数据与歌词」。关闭后 App **完全不发**任何
 * 出站请求到 iTunes Search / LRCLIB——不搜索、不自动填充、不下载歌词、
 * 不批量补封面。
 *
 * **为什么守卫放在 domain 用例层而不是 UI 层**：
 * - UI 层短路挡不住后台/自动触发的路径（如批量补全封面、启动后的自动填充）；
 * - provider 层短路会让「开关为关」与「网络失败」返回同一种结果，UI 无法给出
 *   准确提示（应提示「已关闭联网功能」而非「网络异常」）；
 * - 用例层是所有联网动作的**唯一收口点**，在这里拦一次即可覆盖全部入口。
 *
 * 读取方式：每次判定时读一次 [SettingsRepository.settings] 的当前快照。
 * 开关是低频操作（用户手动点），且 DataStore 已有缓存流，代价可忽略；
 * 换来的是「关掉开关后立即生效」——无需重启、无需等进程重建。
 */
@Singleton
class NetworkMetadataGate @Inject constructor(
    private val settings: SettingsRepository
) {

    /**
     * 当前是否允许联网补全元数据与歌词。
     *
     * DataStore 读取异常时**按「允许」处理**：这是「隐私开关」而非「功能开关」，
     * 读取失败时保守地关掉联网会让用户困惑（功能莫名失效），而多发一次请求
     * 的代价远小于功能不可用。真正的隐私边界由用户主动关闭开关来界定。
     */
    suspend fun isEnabled(): Boolean =
        runCatching { settings.settings.first().networkMetadataEnabled }.getOrDefault(true)
}
