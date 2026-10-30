package com.puretone.player.widget

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color

/** 小部件一次要展示的内容快照 */
data class WidgetState(
    val songId: Long? = null,
    val title: String = NOT_PLAYING_TITLE,
    val artist: String = NOT_PLAYING_SUBTITLE,
    val isPlaying: Boolean = false,
    /** 已缩放的小封面；无封面时为 null（退化为占位图标） */
    val cover: Bitmap? = null
)

const val NOT_PLAYING_TITLE = "未在播放"
const val NOT_PLAYING_SUBTITLE = "点击打开 PureTone"

/**
 * 决定小部件展示哪首歌（纯函数，便于脱离 Android 单测）。
 *
 * 优先级：
 * 1. 进程内存活时的播放状态（最准，含用户刚点的歌）；
 * 2. 退化为上次播放会话的「当前索引」——进程被回收后 AppWidget 仍在桌面上，
 *    不能显示空白，读 DataStore 的会话快照即可拿到歌；
 * 3. 都没有 → null（显示「未在播放」占位文案）。
 */
object WidgetStateResolver {
    fun pickSongId(
        currentSongId: Long?,
        sessionSongIds: List<Long>,
        sessionIndex: Int
    ): Long? {
        if (currentSongId != null) return currentSongId
        if (sessionSongIds.isEmpty()) return null
        val index = sessionIndex.coerceIn(0, sessionSongIds.lastIndex)
        return sessionSongIds.getOrNull(index) ?: sessionSongIds.first()
    }
}

/**
 * 小部件配色。
 *
 * 不走 GlanceTheme（跟着系统主题走），改由 App 自己的主题设置决定：
 * 用户在 App 里选了纯黑/深色，桌面小部件就该跟着黑，否则会出现
 * 「App 全黑、桌面上一块白板」的割裂感。
 */
data class WidgetPalette(
    val background: Color,
    val onBackground: Color,
    val muted: Color
) {
    companion object {
        val Light = WidgetPalette(
            background = Color(0xFFF3EDF7),
            onBackground = Color(0xFF1D1B20),
            muted = Color(0xFF625B71)
        )
        val Dark = WidgetPalette(
            background = Color(0xFF211F26),
            onBackground = Color(0xFFE6E0E9),
            muted = Color(0xFFCAC4D0)
        )
        /** AMOLED 纯黑：背景绝对黑，文字用中灰而非纯白（OLED 上更省电、更护眼） */
        val Black = WidgetPalette(
            background = Color(0xFF000000),
            onBackground = Color(0xFFDAD7DF),
            muted = Color(0xFF9A96A2)
        )
    }
}
