package com.puretone.player.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放历史实体。
 *
 * 每次开始播放一首歌时插入一条记录，供"最近播放"列表与
 * 智能推荐（M5 后置）使用。保留 7 天窗口由 DAO 层清理策略控制。
 *
 * [songId] 带外键级联：歌曲从曲库删除时（文件被删/被过滤），
 * 对应历史行自动清理，避免悬空引用（P1-4）。
 */
@Entity(
    tableName = "play_history",
    // P2-2：played_at 索引支撑「最近播放」倒序 LIMIT、统计页时间窗聚合与
    // 小时级清理 DELETE——这三条全是 Flow，订阅期间每插一条历史就重算一次，
    // 无索引时每次都是全表扫描 + 重排。v5 迁移负责给既有装机补上。
    indices = [Index(value = ["song_id"]), Index("played_at")],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
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
