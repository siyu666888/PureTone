package com.puretone.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.puretone.player.data.local.entity.SongEntity

/**
 * 播放引擎的「媒体项构造」纯函数集（P1-1 拆分第一刀）。
 *
 * 从 [PlaybackManager] 抽出的理由：这一簇是纯映射（SongEntity → Uri / MediaItem /
 * QueueEntry），不碰播放器实例、不读写 UI 状态、不含时序，却长期埋在 1400 行巨类里
 * 完全没有测试覆盖。而它恰恰是「改错一个字符只在真机上表现为串封面 / 路径被截断 /
 * 队列项错位」的高危区：
 * - [rawPathUri] 的 `#` / `?` 转义是 P3-23 的真实修复点，回归后 APE 会静默播不出来；
 * - [toMediaItem] 的 metadata 决定了通知栏 / 锁屏 / 车机显示的曲目信息；
 * - [toQueueEntry] 的 `key` 是拖拽重排的稳定标识（同一首歌可多次入队）。
 *
 * 抽成 `object` 而非注入类：无状态、无依赖，调用处零成本，测试也无需任何替身。
 */
internal object PlaybackMediaItems {

    /**
     * MediaStore 音频 content URI。
     *
     * 用 `mediaStoreId` 而不是文件路径：MediaStore 的 URI 由系统解析，能正确处理
     * 存储卷切换（内部存储 / SD 卡）与 scoped storage，直连文件路径在 Android 10+
     * 对非本应用目录会失败。
     */
    fun mediaStoreUri(song: SongEntity): Uri =
        Uri.parse("content://media/external/audio/media/${song.mediaStoreId}")

    /**
     * 裸文件路径 → URI，转义 `#` / `?`（P3-23）。
     *
     * 这两个字符会被 [Uri.parse] 误判为 fragment / query，导致路径被截断或解析异常。
     * 用于 APE 占位源（本就设计为解码失败）与「playUri 缺失」的回退路径，
     * 使错误类型更准确、避免偶发的路径截断误播。
     */
    fun rawPathUri(path: String): Uri =
        Uri.parse(path.replace("#", "%23").replace("?", "%3F"))

    /** 由歌曲 + 实际播放 URI 构造媒体项；[playUri] 为空时回退到源文件路径 */
    fun toMediaItem(song: SongEntity, playUri: Uri?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.coverUri?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setDurationMs(song.duration)
            .build()
        return MediaItem.Builder()
            .setUri(playUri ?: rawPathUri(song.path))
            .setMediaId(song.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * 惰性构建媒体项：
     * - 非 APE：直连 MediaStore content URI（本地文件，打开即播）；
     * - APE：先以源文件路径占位，ExoPlayer 无法解码时会走
     *   `PlaybackManager.handlePlayerError` → 转码 → `replaceMediaItemUri` 重试，
     *   或由 `playAtIndex` / `warmUpFollowingApe` 提前按需转码替换。
     *
     * 这样「追加/插播」不必等待整队转码，APE 队列也能秒级入队。
     */
    fun lazyMediaItem(song: SongEntity): MediaItem =
        toMediaItem(song, if (song.isApe) rawPathUri(song.path) else mediaStoreUri(song))

    /**
     * 歌曲实体 → 队列项快照。
     *
     * [key] 由调用方分配（`PlaybackManager` 用自增序号）：同一首歌可以在队列里出现
     * 多次（「下一首播放」+「加入队列」），Compose 列表与拖拽重排需要一个
     * **实例级**唯一标识，用 songId 会撞车。
     */
    fun toQueueEntry(song: SongEntity, key: Long): QueueEntry = QueueEntry(
        songId = song.id,
        title = song.title,
        artist = song.artist,
        coverUri = song.coverUri?.let { Uri.parse(it) },
        durationMs = song.duration,
        isApe = song.isApe,
        filePath = song.path,
        key = key,
        rgTrackGain = song.rgTrackGain,
        rgAlbumGain = song.rgAlbumGain
    )
}
