package com.puretone.player.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 播放历史实体。
 *
 * 每次开始播放一首歌时插入一条记录，供"最近播放"列表与
 * 智能推荐（M5 后置）使用。保留 7 天窗口由 DAO 层清理策略控制。
 */
@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "song_id")
    val songId: Long,

    /** 播放开始时间（epoch 毫秒） */
    @ColumnInfo(name = "played_at")
    val playedAt: Long = System.currentTimeMillis(),

    /** 本次播放歌曲时长（毫秒），用于「累计听歌时长」估算（听歌统计） */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L
)
