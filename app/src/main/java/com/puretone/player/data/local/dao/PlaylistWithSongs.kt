package com.puretone.player.data.local.dao

import com.puretone.player.data.local.entity.PlaylistEntity
import com.puretone.player.data.local.entity.SongEntity

/**
 * 歌单 + 歌曲的查询结果。
 *
 * [songs] 由 [PlaylistDao] 的显式 JOIN 查询按 `position` 排序后组装
 * （替代旧版 @Relation——Room 生成的关联查询不保证 position 顺序）。
 */
data class PlaylistWithSongs(
    val playlist: PlaylistEntity,
    val songs: List<SongEntity>
)
