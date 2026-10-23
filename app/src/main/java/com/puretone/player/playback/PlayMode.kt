package com.puretone.player.playback

/**
 * 播放顺序（播放页左侧按钮 / 迷你条共用）——三态循环，无「关闭」态：
 * 顺序播放 → 随机播放 → 单曲循环 → 顺序播放。
 *
 * 与底层 [RepeatMode] + shuffle 的映射：
 * - SEQUENTIAL 顺序播放：repeatMode=ALL, shuffle=false（列表放完循环）；
 * - SHUFFLE    随机播放：repeatMode=ALL, shuffle=true；
 * - REPEAT_ONE 单曲循环：repeatMode=ONE, shuffle=false。
 *
 * 注意：底层 repeatMode=OFF 且未随机时，UI 归并为 SEQUENTIAL（顺序播放）展示。
 */
enum class PlayMode { SEQUENTIAL, SHUFFLE, REPEAT_ONE }

/** 由播放状态派生当前播放顺序（OFF 归并为顺序播放） */
fun PlaybackUiState.toPlayMode(): PlayMode = when {
    shuffle -> PlayMode.SHUFFLE
    repeatMode == RepeatMode.ONE -> PlayMode.REPEAT_ONE
    else -> PlayMode.SEQUENTIAL
}

/** 三态循环的下一态 */
fun PlayMode.next(): PlayMode = when (this) {
    PlayMode.SEQUENTIAL -> PlayMode.SHUFFLE
    PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
    PlayMode.REPEAT_ONE -> PlayMode.SEQUENTIAL
}
