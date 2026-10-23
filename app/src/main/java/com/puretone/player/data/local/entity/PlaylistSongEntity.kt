package com.puretone.player.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 歌单-歌曲关联（多对多中间表）。
 *
 * 复合主键保证同一首歌不会重复加入同一歌单；
 * [position] 支持歌单内手动排序。
 */
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlist_id", "song_id"]
)
data class PlaylistSongEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,

    @ColumnInfo(name = "song_id")
    val songId: Long,

    val position: Int = 0,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis()
)
