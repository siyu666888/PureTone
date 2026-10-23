package com.puretone.player.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * 歌单-歌曲关联（多对多中间表）。
 *
 * 复合主键保证同一首歌不会重复加入同一歌单；
 * [position] 支持歌单内手动排序（DAO 用显式 JOIN + ORDER BY position 读取）。
 *
 * 外键级联（P1-4）：
 * - 删除歌单 → 关联行自动清理（旧版只删 playlists 行，junction 永久残留）；
 * - 删除歌曲（文件没了/被过滤）→ 关联行自动清理（旧版留下悬空 song_id）。
 * [songId] 建索引：关系查询与级联查找都走它，否则全表扫描（KSP 警告）。
 */
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlist_id", "song_id"],
    indices = [Index(value = ["song_id"])],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
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
