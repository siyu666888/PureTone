package com.puretone.player.data.local.dao

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.puretone.player.data.local.entity.PlaylistEntity
import com.puretone.player.data.local.entity.PlaylistSongEntity
import com.puretone.player.data.local.entity.SongEntity

/** 歌单 + 歌曲多对多关联查询结果 */
data class PlaylistWithSongs(
    @Embedded
    val playlist: PlaylistEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PlaylistSongEntity::class,
            parentColumn = "playlist_id",
            entityColumn = "song_id"
        )
    )
    val songs: List<SongEntity>
)
