package com.puretone.player.data.metadata

import java.util.concurrent.atomic.AtomicReference

/**
 * 歌词渠道「命中 + 尝试轨迹」的原子快照（P3-3）。
 *
 * ## 为什么单独抽一个类
 *
 * 原先 `lastHitChannel` / `lastAttemptedChannels` 是两个独立的 `@Volatile var`，
 * 在取词过程中分两步写入：并发取词时 UI 可能读到「A 次命中 + B 次轨迹」的错配状态；
 * 而把 `MutableList` 直接赋给公开属性，快照发布后仍会被后续 `add` 改掉。
 *
 * 现在收敛为**单个 [AtomicReference]**：一次 [record] 原子替换整份快照，
 * 轨迹用 `toList()` 做防御性拷贝，快照一旦发布即不可变。
 * 本类是纯 JVM 类（无 Android / Hilt 依赖），可直接单测原子性。
 */
internal class LyricsAttemptLog {

    /** 一次取词的完整现场：命中渠道 + 按顺序尝试过的渠道 */
    data class Snapshot(
        val hit: MultiChannelLyricsProvider.Channel? = null,
        val attempted: List<MultiChannelLyricsProvider.Channel> = emptyList(),
    )

    private val ref = AtomicReference(Snapshot())

    /** 最近一次命中的渠道（无命中 / 取词进行中为 null） */
    val hit: MultiChannelLyricsProvider.Channel? get() = ref.get().hit

    /** 最近一次按优先级尝试过的渠道轨迹 */
    val attempted: List<MultiChannelLyricsProvider.Channel> get() = ref.get().attempted

    /** 读取同一份原子快照，保证 `hit` 与 `attempted` 永远配套（单测/诊断用） */
    fun snapshot(): Snapshot = ref.get()

    /** 新一轮取词开始前清空现场 */
    fun reset() {
        ref.set(Snapshot())
    }

    /** 一次发布完整结果，`attempted` 做防御性拷贝 */
    fun record(
        hit: MultiChannelLyricsProvider.Channel?,
        attempted: Collection<MultiChannelLyricsProvider.Channel>,
    ) {
        ref.set(Snapshot(hit, attempted.toList()))
    }
}
