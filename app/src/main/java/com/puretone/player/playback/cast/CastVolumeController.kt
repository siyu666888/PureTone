package com.puretone.player.playback.cast

import android.util.Log
import com.yinnho.upnpcast.DLNACast
import com.yinnho.upnpcast.DLNACast.Device
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 投屏音量控制协作者（从 [DlnaCastManager] 抽出，行为零改动）。
 *
 * 合并下发、初值同步回读、能力探针、下发后回读取证原本都写在管理器里，占用约 130 行。
 * 抽离时保持四条语义不变：
 * - 下发通道仍在 [DlnaCastManager] 的 init 阶段由 [start] 启动，启动时机不变；
 * - [state] 直接持有管理器的同一个 MutableStateFlow，不复制、不转发；
 * - [transportProvider] 是惰性取值：调用时才读当前通道，换通道后立刻生效；
 * - 日志 TAG 仍为 "DlnaCastManager"，既有日志过滤规则不变。
 *
 * 类本身不持有 Android Context，也不新建协程作用域，二者都由构造注入。
 */
internal class CastVolumeController(
    private val state: MutableStateFlow<CastState>,
    private val net: CastNetworkBridge,
    private val scope: CoroutineScope,
    private val transportProvider: () -> DlnaTransport?,
) {
    /**
     * 用户最近一次请求的投射音量（0~100）。
     *
     * 滑块 `onValueChange` 每像素触发一次，一次拖动能连发十几条 `SetVolume`；
     * 直接往下发既刷爆日志又会让电视来不及执行（回读必然读到旧值）。改为
     * 「只保留最新值 + [drainVolumeRequests] 合并」，一次拖动只发一条。
     */
    private val requestedVolume = MutableStateFlow<Int?>(null)

    /** 用户本会话是否已主动调过音量：为真时不再用设备回读值覆盖 UI 初始值 */
    @Volatile
    private var volumeTouchedByUser = false

    /** 启动常驻下发通道；由 [DlnaCastManager] 的 init 调用，时机与拆分前一致 */
    fun start() {
        scope.launch { drainVolumeRequests() }
    }

    /** 会话结束：复位待下发请求与「用户已调过」标志（原 stopCast 内两行） */
    fun reset() {
        requestedVolume.value = null
        volumeTouchedByUser = false
    }

    /**
     * 投送链路内**同步**回读真实音量，必须在 `isCasting=true` 之前完成：否则 MediaSession
     * 建 `VolumeProviderCompat` 时读到的仍是假 100，按一下音量键就把电视从 15 拉到 100。
     * 库通道的 `DLNACast.getVolume()` 返回值量纲不明，不做同步校准，交给 [CastProbe] 折算。
     */
    suspend fun syncInitialVolumeNow() {
        val client = transportProvider() ?: return
        val actual = try {
            client.volume()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "initial volume read failed", e)
            null
        }
        if (actual != null) {
            // GetVolume 成功 = RenderingControl 确实可用，是能力事实而非探测冒充。
            // 必须在路由切换前落定，否则 remoteVolume 会在 null/true 间翻转。
            state.update {
                if (CastVolumePolicy.shouldSetSupport(it.volumeSupported)) {
                    it.copy(volumeSupported = true)
                } else {
                    it
                }
            }
            applyInitialVolume(actual)
            CastNetworkLog.log("cast-vol init actual=$actual")
        }
    }

    /** 同步设备真实音量；用户已调过或已校准过就不再覆盖（防止多个来源互相冲掉） */
    private fun applyInitialVolume(volume: Int?) {
        if (volume == null) return
        if (!CastVolumePolicy.shouldApplyInitialVolume(volumeTouchedByUser, state.value.castVolume)) return
        state.update { it.copy(castVolume = volume.coerceIn(0, 100)) }
    }

    /**
     * 投送后异步取证：问电视「控制地址在哪、状态码是什么、支持哪些格式、能不能调音量」。
     *
     * 刻意放在协程里而不是在 [DlnaCastManager.castSong] 里同步等：探针最多要跑三次 HTTP，
     * 同步跑会把「回退到本地播放」推迟数秒——用户点投屏后最不能忍的就是这个。
     * 探针期间自己绑 Wi-Fi 再还原，避免默认网络落到蜂窝时取证也失败。
     *
     * 副作用：把 [CastState.volumeSupported] 落到真实能力值（仅当描述取到才写，
     * 取不到时保持 null=未知，不能用「探测失败」冒充「不支持」）。
     */
    fun launchProbe(device: Device) {
        scope.launch {
            // 探针只是临时借用：投送已 beginCastSession 时复用现成绑定，归还不会释放；
            // 万一投送失败会话已结束、探针才跑起来，则借完自己还，避免保活锁泄漏。
            val owned = net.holdCastNetwork()
            try {
                val report = CastProbe.inspect(device.id, device.name)
                if (report.descriptionOk) {
                    // 能力判定只写一次：反复改写会让 remoteVolume 翻转、Media3 重建
                    // VolumeProvider，音量键在远程/本地两套档位间来回跳。
                    if (CastVolumePolicy.shouldSetSupport(state.value.volumeSupported)) {
                        state.update { it.copy(volumeSupported = report.hasRenderingControl) }
                    }
                    // 库通道没有自己的 GetVolume 回读，用探针折算后的音量补校准初值
                    applyInitialVolume(report.currentVolume)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "cast probe failed", e)
            } finally {
                net.releaseBorrowedNetwork(owned)
            }
        }
    }

    /** 音量下发通道（常驻）：合并连发请求，只对最终值发一次 SOAP。
     *  [collectLatest] 刻意用于取消上一轮，避免滑块拖动时十几个中间值刷屏。 */
    private suspend fun drainVolumeRequests() {
        requestedVolume.filterNotNull().collectLatest { target ->
            delay(VOLUME_COALESCE_MS)
            // stopCast 已把 isCasting 置 false：不能让延迟中的请求在会话结束后还写状态
            if (!state.value.isCasting) return@collectLatest
            val client = transportProvider()
            val accepted = if (client != null) {
                client.setVolume(target) == true
            } else {
                runCatching { DLNACast.setVolume(target) }.getOrDefault(false)
            }
            if (accepted) state.update { it.copy(castVolume = target) }
            if (client == null) return@collectLatest
            delay(VOLUME_SETTLE_MS)
            if (!state.value.isCasting) return@collectLatest
            verifyVolume(client, target, accepted)
        }
    }

    /** 回读只做取证：`SetVolume` 返回 200 不代表电视真变了。
     *  绝不把回读值写回 castVolume——量程折算的 ±2 误差会成为下次按键的基准，
     *  连按几次就一路漂移；也不改 volumeSupported，避免 Media3 反复重建 VolumeProvider。 */
    private suspend fun verifyVolume(client: DlnaTransport, requested: Int, accepted: Boolean) {
        try {
            val actual = client.volume()
            CastNetworkLog.log("own-vol readback req=$requested actual=${actual ?: "none"} accepted=$accepted")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "volume verify failed", e)
        }
    }

    /** 受理一次音量请求：值交给 [drainVolumeRequests] 合并下发，不在这里同步发 SOAP。
     *  [adjustVolume] 以「最近一次请求值」而非滞后的状态值为基准，连按音量键才不丢档。 */
    fun setVolume(volume: Int): Boolean {
        val target = volume.coerceIn(0, 100)
        CastNetworkLog.log("cast-vol req=$target")
        volumeTouchedByUser = true
        requestedVolume.value = target
        return true
    }

    /** 相对调节（音量键 +/-）：以最后一次请求值为基准，避免连按时读到旧档位 */
    fun adjustVolume(delta: Int): Boolean = setVolume(
        CastVolumePolicy.resolveRelative(requestedVolume.value, state.value.castVolume, delta)
    )

    private companion object {
        // 与原 DlnaCastManager 保持同一 TAG，既有日志过滤规则不变
        private const val TAG = "DlnaCastManager"

        /**
         * 音量合并窗口：滑块一次拖动会连发十几条 setVolume，等这么久让后续值
         * 覆盖前面的，只对最终值发一次 SOAP。
         */
        private const val VOLUME_COALESCE_MS = 120L

        /** 下发后到回读取证之间的等待：渲染器执行音量是异步的，立刻读只会读到旧值 */
        private const val VOLUME_SETTLE_MS = 700L
    }
}
