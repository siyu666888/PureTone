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
 * 智能推荐（M5 后置）使用。保留窗口由 DAO 层清理策略控制。
 *
 * v8 起（需求4 听歌报告）扩出 4 个维度列，支撑周/月/年报的精确聚合：
 * - [dayKey]：插入时用设备本地时区算好的 `yyyy-MM-dd`，查询聚合直接复用，
 *   避免在 SQL 里跑 `localtime` 转换带来的跨日/跨周边界漂移；
 * - [playedMs]：实际收听毫秒（暂停/切歌结算，播放完成=整曲时长）；
 * - [endAt]：结算时间戳（epoch 毫秒），0 = 该行尚未结算（仍在播放或历史旧数据）；
 * - [completed]：是否完整播放完成（自然播完/单曲循环）。
 *
 * [durationMs] 保留作旧数据兜底：v8 前装机升级后 `played_ms=0 且 end_at=0`，
 * 报告层回退到 [durationMs] 并标注「含估算」。
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

    /** 本次播放歌曲时长（毫秒），用于「累计听歌时长」估算（听歌统计），以及 v8 前的旧数据兜底 */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    /** 本地时区下的日期键 yyyy-MM-dd，插入时一次性算好（需求4 周/月/年报周期聚合用） */
    @ColumnInfo(name = "day_key")
    val dayKey: String = "",

    /** 实际收听毫秒（暂停/切歌结算；完成=整曲时长）。0 且 [endAt]=0 表示旧数据/未结算 */
    @ColumnInfo(name = "played_ms")
    val playedMs: Long = 0L,

    /** 结算时间戳（epoch 毫秒）。0 = 该行尚未结算（仍在播放中或历史旧数据） */
    @ColumnInfo(name = "end_at")
    val endAt: Long = 0L,

    /** 是否完整播放完成（自然播完/单曲循环）：0=否/未结算，1=是 */
    @ColumnInfo(name = "completed")
    val completed: Int = 0
)
