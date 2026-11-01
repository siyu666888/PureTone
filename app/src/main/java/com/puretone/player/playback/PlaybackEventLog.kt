package com.puretone.player.playback

import android.util.Log
import androidx.media3.common.Player
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 播放事件环形日志（P3-6）。
 *
 * ## 为什么需要它
 *
 * 「播着播着自动停了」这类反馈最大的问题不是修，而是**没法定位**：
 * - Java 层没有异常（`crash_last.txt` 只会是旧的 / 无关的崩溃）；
 * - Logcat 在用户手机上取不到；
 * - 原来的诊断报告只有「崩溃堆栈 + 设备信息 + FFmpeg 输出」，
 *   里面**根本没有播放链路的任何一条记录**，所以报告再全也答不出「为什么停」。
 *
 * 这里补上这条缺失的证据链：把「暂停 / 播放失败 / 睡眠定时触发 / 空闲停服 /
 * 曲间停顿」等关键状态跃迁写成一行行带时间戳的记录，落在
 * `filesDir/playback_events.txt`，并随诊断报告一起导出。
 *
 * ## 关键判读方法
 *
 * - `paused-with-queue`：**队列非空却变成暂停**——这正是「自动停止」的现场。
 *   看它前面紧跟的那条记录即可区分成因：
 *   `user-pause` = 用户自己按的；`sleep-timer` = 睡眠定时到点；
 *   `player-error` = 当前曲目解不出来；
 *   其余（没有任何前置记录）= 音频焦点被夺 / 输出设备变化（系统侧行为）。
 * - `error-skip`：播放失败后自动跳过下一首（自愈路径已生效）。
 *
 * ## 取舍
 *
 * - **内存 + 文件双写**：读报告时不必依赖磁盘 IO 成功，且写盘失败不影响播放；
 * - **环形截断**（保留最近 [MAX_LINES] 行）：日志永远有界，不会无限增长；
 * - **不含任何曲库内容**：只记 songId（内部主键）与错误码，契合同一立场。
 */
object PlaybackEventLog {

    /** 落盘文件名（诊断导出按此读取） */
    const val FILE_NAME = "playback_events.txt"

    /** 环形上限：约 40~60 KB，足够覆盖一次「播放到自动停止」的完整上下文 */
    private const val MAX_LINES = 240

    /** 触达该长度时才做一次截断重写，避免每写一行都读全文件 */
    private const val TRIM_THRESHOLD_BYTES = 96 * 1024

    private val lock = Any()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Application.filesDir，由 [com.puretone.player.PureToneApplication] 在 onCreate 注入 */
    @Volatile
    private var dir: File? = null

    private val buffer = ArrayDeque<String>()

    fun init(filesDir: File) {
        dir = filesDir
        synchronized(lock) {
            buffer.clear()
            runCatching {
                val f = File(filesDir, FILE_NAME)
                if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach(buffer::addLast)
            }
        }
    }

    /**
     * 「队列还有歌，但已经不在播了」——自动停止的现场快照。
     *
     * 过滤掉 buffering 抖动（此时 playWhenReady 仍为 true）与出错/停止
     * （playbackState 已落 IDLE，各自有专门记录），只保留真正可疑的
     * 「READY/BUFFERING 且 playWhenReady=false」这一种。
     * 有了它，诊断报告里就能把「自动停止」和它前面那条成因记录对起来。
     */
    fun recordPauseWithQueueIfAny(player: Player?, songId: Long?) {
        val p = player ?: return
        if (p.playWhenReady || p.mediaItemCount == 0) return
        if (p.playbackState == Player.STATE_IDLE) return
        record("paused-with-queue", "idx=${p.currentMediaItemIndex} state=${p.playbackState} song=$songId")
    }

    /** 记一条事件。任何异常都被吞掉——日志永远不该影响播放。 */
    fun record(event: String, detail: String = "") {
        val line = buildString {
            append(stamp.format(Date()))
            append(' ')
            append(event)
            if (detail.isNotEmpty()) {
                append(" | ")
                append(detail)
            }
        }
        runCatching { Log.d(TAG, line) }
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            val base = dir ?: return
            runCatching {
                val f = File(base, FILE_NAME)
                if (f.length() > TRIM_THRESHOLD_BYTES) {
                    f.writeText(buffer.joinToString("\n", postfix = "\n"))
                } else {
                    f.appendText(line + "\n")
                }
            }
        }
    }

    /** 读取全部记录（诊断报告用）。无记录时返回空串，由调用方决定占位文案。 */
    fun read(): String = synchronized(lock) {
        if (buffer.isNotEmpty()) return buffer.joinToString("\n")
        val base = dir ?: return ""
        runCatching {
            val f = File(base, FILE_NAME)
            if (f.exists()) f.readText().trimEnd() else ""
        }.getOrDefault("")
    }

    /** 清空（设置页「清空播放日志」/ 测试用） */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            dir?.let { base -> runCatching { File(base, FILE_NAME).delete() } }
        }
    }

    private const val TAG = "PureTonePlayback"
}
