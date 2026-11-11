package com.puretone.player.playback.cast

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 投屏文件的 token 注册表（第三轮审查 P2-1）。
 *
 * 旧实现是 `entries[token] = Entry(...)` 只增不减，直到 `stop()` 才清空：连播几小时会
 * 累积上百条，且**旧 token 永久有效**——任何嗅探到旧 URL 的设备都能一直拉旧文件。
 * 这里把「最近访问时刻」纳入登记表，由 [CastHttpServer.urlFor] 在登记新 token 时
 * 顺带淘汰超时条目。
 *
 * ## 为什么按「最近访问」而不是「登记时间」淘汰
 *
 * 换曲后电视仍会重连旧 URL 拉完上一段流：R7 现场里遗留连接与 74MB FLAC 的密集
 * Range 重试都属于这类。按登记时间会误伤**正在供流**的旧 token，直接把长连接掐断。
 * 每次 [find] 命中都刷新时刻，所以「还在被访问」的旧 token 不会被清掉；只有真正
 * 沉寂超过 [ENTRY_TTL_MS] 的条目才淘汰。
 *
 * 时钟可注入，纯 JVM 单测即可锁定边界，不需要起 socket。
 */
internal class CastEntryRegistry(
    private val ttlMs: Long = ENTRY_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 对外只暴露供流需要的两个字段，避免调用方改到时间戳 */
    data class Snapshot(val file: File, val mimeType: String)

    private class TimedEntry(
        val file: File,
        val mimeType: String,
        @Volatile var lastAccessMs: Long,
    )

    private val entries = ConcurrentHashMap<String, TimedEntry>()

    /** 登记新 token；先淘汰超时条目，返回被淘汰的 token（供诊断落盘） */
    fun register(token: String, file: File, mimeType: String): List<String> {
        val nowMs = clock()
        val evicted = prune(nowMs)
        entries[token] = TimedEntry(file, mimeType, nowMs)
        return evicted
    }

    /** 命中则刷新最近访问时刻，避免正在供流的旧 token 被淘汰 */
    fun find(token: String): Snapshot? {
        val entry = entries[token] ?: return null
        entry.lastAccessMs = clock()
        return Snapshot(entry.file, entry.mimeType)
    }

    /** 淘汰「沉寂严格超过 ttlMs」的条目；恰好等于上限仍保留 */
    fun prune(nowMs: Long = clock()): List<String> {
        val evicted = ArrayList<String>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (nowMs - item.value.lastAccessMs > ttlMs) {
                evicted += item.key
                iterator.remove()
            }
        }
        return evicted
    }

    fun clear() = entries.clear()

    /** 当前登记条目数（诊断用） */
    fun size(): Int = entries.size

    companion object {
        /** 30 分钟：远大于 R7 实测最长长连接 645s，又不至于让连播几小时的 map 无限增长 */
        const val ENTRY_TTL_MS: Long = 30 * 60 * 1000L
    }
}
