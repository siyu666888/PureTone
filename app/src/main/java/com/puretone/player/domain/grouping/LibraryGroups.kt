package com.puretone.player.domain.grouping

import com.puretone.player.data.local.entity.SongEntity

/**
 * 曲库分组聚合（domain 层纯函数）。
 *
 * 旧实现把 AlbumSummary / ArtistSummary / FolderSummary 三个数据类与
 * groupAlbums/groupArtists/groupFolders 三个分组函数直接写进
 * [com.puretone.player.ui.LibraryViewModel]，既让 VM 膨胀，又无法脱离
 * Android/Robolectric 单测（纯 Kotlin 逻辑却被绑在 UI 层）。
 *
 * 抽到 domain 层后：
 * - VM 只负责「观察 Flow → 调本文件函数 → stateIn」，不再内含分组算法；
 * - 分组逻辑可纯 JVM 单测（见 LibraryGrouperTest），不依赖数据库/上下文。
 *
 * 分组口径（与历史行为一致，勿随意改动）：
 * - 专辑：复合键 (volumeName, albumId>0 ? albumId : album)，SD 卡与内部存储
 *   同名 album_id 不再被误并（P2-4）；
 * - 歌手：按 artist 字段；
 * - 文件夹：按 path 父目录（folderOf）。
 */

/** 专辑分组汇总（封面网格墙 / 二级详情页用） */
data class AlbumSummary(
    val volumeName: String = "",
    val albumId: Long,
    val album: String,
    val artist: String,
    val coverUri: String?,
    val songs: List<SongEntity>
) {
    val songCount: Int get() = songs.size
}

/** 歌手分组汇总 */
data class ArtistSummary(
    val artist: String,
    val coverUri: String?,
    val songs: List<SongEntity>
) {
    val songCount: Int get() = songs.size
}

/** 文件夹分组汇总（文件夹由 path 父目录派生） */
data class FolderSummary(
    val folder: String,
    val songs: List<SongEntity>
) {
    val songCount: Int get() = songs.size
    /** 展示名：取路径最后一段 */
    val name: String
        get() = folder.substringAfterLast('/').ifBlank { folder }
}

/** 专辑分组：复合键 (volumeName, albumId>0?albumId:album)（P2-4）。 */
fun groupAlbums(songs: List<SongEntity>): List<AlbumSummary> {
    return songs.groupBy { s -> s.volumeName to (s.albumId.takeIf { it > 0 } ?: s.album) }
        .map { (_, group) ->
            val first = group.first()
            AlbumSummary(
                volumeName = first.volumeName,
                albumId = first.albumId,
                album = first.album,
                artist = first.artist,
                coverUri = group.firstNotNullOfOrNull { it.coverUri },
                songs = group.sortedWith(compareBy({ it.trackNumber }, { it.title.lowercase() }))
            )
        }
        .sortedBy { it.album.lowercase() }
}

/** 歌手分组 */
fun groupArtists(songs: List<SongEntity>): List<ArtistSummary> {
    return songs.groupBy { it.artist }
        .map { (artist, group) ->
            ArtistSummary(
                artist = artist,
                coverUri = group.firstNotNullOfOrNull { it.coverUri },
                songs = group.sortedWith(
                    compareBy({ it.album.lowercase() }, { it.trackNumber }, { it.title.lowercase() })
                )
            )
        }
        .sortedBy { it.artist.lowercase() }
}

/** 文件夹分组（path 父目录） */
fun groupFolders(songs: List<SongEntity>): List<FolderSummary> {
    return songs.groupBy { folderOf(it.path) }
        .map { (folder, group) ->
            FolderSummary(
                folder = folder,
                songs = group.sortedWith(compareBy({ it.album.lowercase() }, { it.title.lowercase() }))
            )
        }
        .sortedBy { it.folder.lowercase() }
}

/**
 * 由文件绝对路径取所在文件夹（父目录）。
 * Android MediaStore 路径统一用 '/' 分隔，按最后一次 '/' 切分即可，与平台无关。
 */
private fun folderOf(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx > 0) path.substring(0, idx) else path
}
