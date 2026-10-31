package com.puretone.player.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.MusicRepository
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.data.settings.SettingsRepository
import com.puretone.player.playback.PlaybackManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.InputStream
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

    /**
     * 封面加载：多级回退，任一环节失败只是退到下一级，不会因为单点异常变成空白方块。
     *
     * 1. [song.coverUri]：可能是 content:// 的系统专辑封面 URI，也可能是用户自己
     *    匹配/下载封面后的**裸绝对路径**——裸路径用 `Uri.parse` 得到的是无 scheme 的
     *    URI，`contentResolver.openInputStream` 会直接抛异常导致封面整块消失，故单独走 [File]；
     * 2. 音频文件内嵌封面（[MediaMetadataRetriever]）：这才是本地曲库的大头——
     *    [com.puretone.player.data.media.MediaStoreScanner] 只在系统真的生成过专辑缩略图时
     *    才写 coverUri，大量歌曲（尤其自建抓轨/下载库）在 MediaStore 里没有记录，
     *    但文件本身早带着 ID3/FLAC 封面。缺了这一级，小部件就只能显示占位唱片图标。
     */
    private fun loadCover(song: SongEntity): Bitmap? =
        decodeCoverUri(song.coverUri) ?: decodeEmbeddedCover(song.path)

    private fun decodeCoverUri(raw: String?): Bitmap? {
        val value = raw?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val uri = Uri.parse(value)
            val stream = if (uri.scheme.isNullOrBlank()) {
                // 无 scheme = 绝对路径（用户手动/联网匹配写入的封面文件）
                val file = File(value)
                if (!file.canRead()) return@runCatching null
                file.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            } ?: return@runCatching null
            stream.use { decodeScaled(it) }
        }.getOrNull()
    }

    /** 直接从音频文件里取内嵌封面（MP3 的 ID3 APIC / FLAC picture / MP4 covr 等） */
    private fun decodeEmbeddedCover(path: String): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val art = retriever.embeddedPicture ?: return@runCatching null
            val raw = BitmapFactory.decodeByteArray(art, 0, art.size) ?: return@runCatching null
            centerSquare(raw, COVER_PX)
        } finally {
            // APE 等不支持的格式会抛 IOException / RuntimeException；无论成败都要释放资源
            retriever.release()
        }
    }.getOrNull()

    /**
     * 解码并按目标边长选取采样率。
     *
     * 先用 inJustDecodeBounds 只读尺寸算 inSampleSize：封面原图动辄上千像素，
     * 直接全量解码再缩放既慢又占内存（AppWidget 渲染有时间窗，别在这儿卡主线程）。
     */
    private fun decodeScaled(input: InputStream): Bitmap? {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, COVER_PX) }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return centerSquare(raw, COVER_PX)
    }

    /** 取 2 的幂采样率，使解码结果略大于目标边长（保证缩放后不糊） */
    private fun sampleSizeFor(bounds: BitmapFactory.Options, target: Int): Int {
        val minSide = min(bounds.outWidth, bounds.outHeight)
        if (minSide <= 0) return 1
        var sample = 1
        while (minSide / (sample * 2) >= target) sample *= 2
        return sample.coerceAtLeast(1)
    }

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
