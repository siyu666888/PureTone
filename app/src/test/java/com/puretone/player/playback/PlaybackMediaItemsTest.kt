package com.puretone.player.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.puretone.player.data.local.entity.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlaybackMediaItems] 行为测试（P1-1 拆分第一刀）。
 *
 * 这一簇是从 `PlaybackManager` 里搬出来的纯映射，此前零覆盖。它出错的表现都很隐蔽：
 * - `rawPathUri` 不转义 `#` / `?` → APE 源路径被 `Uri.parse` 截断，静默播不出来（P3-23）；
 * - `toMediaItem` 的 mediaId 不是 id → 切歌回调拿 `mediaId.toLongOrNull()` 全为 null，
 *   「当前曲目」永远不更新；
 * - `toQueueEntry` 丢了 `key` → 同一首歌多次入队时拖拽重排串位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class PlaybackMediaItemsTest {

    private fun song(
        id: Long = 1L,
        mediaStoreId: Long = 100L,
        title: String = "曲名",
        path: String = "/sdcard/Music/a.mp3",
        mime: String = "audio/mpeg",
        cover: String? = "content://media/external/audio/albumart/7",
        duration: Long = 180_000L,
        rgTrack: Float? = -3.5f,
        rgAlbum: Float? = -2.0f,
    ) = SongEntity(
        id = id,
        mediaStoreId = mediaStoreId,
        title = title,
        artist = "歌手",
        album = "专辑",
        albumId = 7L,
        duration = duration,
        path = path,
        size = 1024L,
        coverUri = cover,
        mimeType = mime,
        rgTrackGain = rgTrack,
        rgAlbumGain = rgAlbum,
    )

    // ------------------------------------------------------------------ URI 构造

    @Test
    fun `mediaStoreUri 使用 mediaStoreId 拼 content uri`() {
        val uri = PlaybackMediaItems.mediaStoreUri(song(mediaStoreId = 4242L))
        assertEquals("content://media/external/audio/media/4242", uri.toString())
    }

    @Test
    fun `rawPathUri 转义井号与问号`() {
        // 不转义时 Uri.parse 会把 # 之后当 fragment、? 之后当 query，路径被截断
        val uri = PlaybackMediaItems.rawPathUri("/sdcard/Music/A#B?C.ape")
        assertEquals("/sdcard/Music/A%23B%3FC.ape", uri.toString())
    }

    @Test
    fun `rawPathUri 对普通路径原样返回`() {
        assertEquals(
            "/sdcard/Music/01 - song.flac",
            PlaybackMediaItems.rawPathUri("/sdcard/Music/01 - song.flac").toString()
        )
    }

    @Test
    fun `rawPathUri 保留中文与空格`() {
        val uri = PlaybackMediaItems.rawPathUri("/sdcard/音乐/晴天.ape")
        assertTrue(uri.toString().contains("晴天"))
        assertTrue(uri.toString().contains("/sdcard/音乐/"))
    }

    // ---------------------------------------------------------------- 媒体项映射

    @Test
    fun `toMediaItem 映射媒体项标识与元数据`() {
        val item = PlaybackMediaItems.toMediaItem(
            song(id = 99L, duration = 200_000L),
            Uri.parse("content://media/external/audio/media/100")
        )

        assertEquals("mediaId 必须是歌曲 id（切歌回调靠它解析当前曲目）", "99", item.mediaId)
        assertEquals("content://media/external/audio/media/100", item.localConfiguration?.uri.toString())
        assertEquals("曲名", item.mediaMetadata.title)
        assertEquals("歌手", item.mediaMetadata.artist)
        assertEquals("专辑", item.mediaMetadata.albumTitle)
        assertEquals(200_000L, item.mediaMetadata.durationMs)
        assertEquals(
            "content://media/external/audio/albumart/7",
            item.mediaMetadata.artworkUri.toString()
        )
    }

    @Test
    fun `toMediaItem 在无封面时不设置 artwork`() {
        val item = PlaybackMediaItems.toMediaItem(song(cover = null), Uri.parse("content://x/1"))
        assertNull(item.mediaMetadata.artworkUri)
    }

    @Test
    fun `toMediaItem 的 playUri 为空时回退到源路径`() {
        val item = PlaybackMediaItems.toMediaItem(song(path = "/sdcard/Music/x.ape"), null)
        assertEquals("/sdcard/Music/x.ape", item.localConfiguration?.uri.toString())
    }

    // ------------------------------------------------------------ 惰性媒体项分流

    @Test
    fun `非 APE 的惰性媒体项直连 MediaStore`() {
        val item = PlaybackMediaItems.lazyMediaItem(song(mediaStoreId = 777L, mime = "audio/mpeg"))
        assertEquals("content://media/external/audio/media/777", item.localConfiguration?.uri.toString())
    }

    @Test
    fun `APE 的惰性媒体项用源路径占位`() {
        val item = PlaybackMediaItems.lazyMediaItem(
            song(path = "/sdcard/Music/x.ape", mime = "audio/ape")
        )
        assertEquals("/sdcard/Music/x.ape", item.localConfiguration?.uri.toString())
    }

    @Test
    fun `APE 判定同时看 mime 与扩展名`() {
        // 只给了 .ape 扩展名，mime 是通用值，也要走占位路径
        val byExt = PlaybackMediaItems.lazyMediaItem(song(path = "/sdcard/Music/y.ape", mime = "audio/*"))
        assertEquals("/sdcard/Music/y.ape", byExt.localConfiguration?.uri.toString())
        // 只给了 audio/ape，路径没有扩展名，同样算 APE
        val byMime = PlaybackMediaItems.lazyMediaItem(song(path = "/sdcard/Music/z", mime = "audio/ape"))
        assertEquals("/sdcard/Music/z", byMime.localConfiguration?.uri.toString())
    }

    // ---------------------------------------------------------------- 队列项映射

    @Test
    fun `toQueueEntry 完整映射字段并保留外部传入的 key`() {
        val entry = PlaybackMediaItems.toQueueEntry(song(id = 5L, mediaStoreId = 500L), key = 12L)

        assertEquals(5L, entry.songId)
        assertEquals("曲名", entry.title)
        assertEquals("歌手", entry.artist)
        assertEquals(180_000L, entry.durationMs)
        assertEquals("/sdcard/Music/a.mp3", entry.filePath)
        assertEquals("key 必须用调用方给的实例序号（同曲多次入队不能撞）", 12L, entry.key)
        assertEquals(-3.5f, entry.rgTrackGain)
        assertEquals(-2.0f, entry.rgAlbumGain)
        assertEquals(false, entry.isApe)
    }

    @Test
    fun `toQueueEntry 对 APE 标记 isApe 并带上源路径`() {
        val entry = PlaybackMediaItems.toQueueEntry(
            song(path = "/sdcard/Music/alb/01.ape", mime = "audio/ape"),
            key = 1L
        )
        assertTrue(entry.isApe)
        assertEquals("/sdcard/Music/alb/01.ape", entry.filePath)
    }

    @Test
    fun `toQueueEntry 对缺失封面给 null`() {
        val entry = PlaybackMediaItems.toQueueEntry(song(cover = null), key = 1L)
        assertNull(entry.coverUri)
    }
}
