package com.puretone.player.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.puretone.player.data.local.entity.SongEntity

/**
 * 歌单-歌曲关联行：显式 JOIN 的查询结果。
 *
 * 替代旧版 `@Relation + Junction`——Room 生成的关联查询既不带 ORDER BY
 * 也不返回 position，歌单顺序实际取决于 SQLite 行序（P1-4）。这里用
 * `ps.position` 排序，行序即用户/导入设定的顺序。
 *
 * 歌曲列全部加 `song_` 前缀：JOIN 后 `id` 等列名与 playlists 冲突，
 * 前缀让 Room 的 `@Embedded` 映射无歧义。
 */
data class PlaylistSongRow(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,

    val position: Int,

    @Embedded(prefix = "song_")
    val song: SongEntity
)
