package com.puretone.player.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.playback.PlaybackManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/** 封面边长（像素）：AppWidget 的 bitmap 走 binder 传输，超过 1MB 会抛 TransactionTooLarge */
private const val COVER_PX = 96

/**
 * 小部件内容源：把「播放状态 + 曲库 + 设置」合成一份快照。
 *
 * 两个读取细节：
 * - 歌曲信息允许回退到上次会话（详见 [WidgetStateResolver.pickSongId]）：
 *   进程被回收后 AppWidget 还挂在桌面上，不能显示空白；
 * - [PlaybackManager.uiState] 是进程内的 StateFlow，进程重启后 isPlaying 为 false——
 *   此时确实是「什么都没在播」，与真实状态一致，无需额外持久化播放状态。
 */
@Singleton
class WidgetStateProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playback: PlaybackManager,
    private val musicRepository: MusicRepository,
    private val settings: SettingsRepository
) {

    suspend fun snapshot(): WidgetState {
        val ui = playback.uiState.value
        val session = runCatching { settings.playbackSession.first() }.getOrNull()
        val songId = WidgetStateResolver.pickSongId(
            currentSongId = ui.currentSongId,
            sessionSongIds = session?.songIds.orEmpty(),
            sessionIndex = session?.index ?: 0
        )
        val song = songId?.let { runCatching { musicRepository.getSong(it) }.getOrNull() }
            ?: return WidgetState()
        return WidgetState(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            isPlaying = ui.isPlaying,
            cover = loadCover(song)
        )
    }

    /** 配色由 App 的主题设置决定，而非系统（详见 [WidgetPalette] 注释） */
    suspend fun palette(): WidgetPalette {
        val s = runCatching { settings.settings.first() }.getOrDefault(PureToneSettings())
        return resolvePalette(s, isSystemNight())
    }

    private fun isSystemNight(): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun loadCover(song: SongEntity): Bitmap? = runCatching {
        val uri = song.coverUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@runCatching null
        context.contentResolver.openInputStream(uri)?.use { input ->
            // inSampleSize=2：封面原图动辄 1000px+，桌面只需要 ~96px，直接解码太浪费
            val raw = BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply { inSampleSize = 2 }
            ) ?: return@runCatching null
            centerSquare(raw, COVER_PX)
        }
    }.getOrNull()

    /** 居中裁方 + 缩放 */
    private fun centerSquare(src: Bitmap, size: Int): Bitmap {
        val side = min(src.width, src.height)
        if (side <= 0) return src
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, left, top, side, side)
        return Bitmap.createScaledBitmap(cropped, size, size, true)
    }
}

/** 主题设置 → 小部件配色；SYSTEM 跟随系统夜间模式 */
fun resolvePalette(s: PureToneSettings, systemNight: Boolean): WidgetPalette = when (s.themeMode) {
    PureToneSettings.THEME_BLACK -> WidgetPalette.Black
    PureToneSettings.THEME_DARK -> WidgetPalette.Dark
    PureToneSettings.THEME_LIGHT -> WidgetPalette.Light
    else -> if (systemNight) WidgetPalette.Dark else WidgetPalette.Light
}
