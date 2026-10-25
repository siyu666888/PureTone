package com.puretone.player.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 曲库歌曲实体。
 *
 * [mediaStoreId] 对应 MediaStore.Audio.Media._ID，用于与系统媒体库对账；
 * [path] 文件绝对路径，全库唯一，作为幂等 upsert 与"文件已删除"清理的锚点。
 */
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["path"], unique = true),
        Index("album_id"),
        Index("artist"),
        // P2-3：曲库页四个排序 Flow（按添加时间 / 播放次数 / 时长）与
        // 「最爱歌曲榜」的支撑列。无索引时任意 songs 写操作（每次完整播放的
        // play_count+1、收藏、封面更新）都触发全表扫描 + 临时排序，大库掉帧。
        // Room 不会为既有装机自动补索引，v5 迁移负责创建（见 MIGRATION_4_5）。
        Index("date_added"),
        Index(value = ["play_count", "last_played_at"]),
        Index("duration")
    ]
)
data class SongEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,

    val title: String,
    val artist: String,
    val album: String,

    @ColumnInfo(name = "album_id")
    val albumId: Long,

    /** 时长（毫秒） */
    val duration: Long,

    /** 文件绝对路径（唯一） */
    val path: String,

    /** 文件大小（字节） */
    val size: Long,

    /** 封面 content:// URI（可空，如系统未生成缩略图） */
    @ColumnInfo(name = "cover_uri")
    val coverUri: String? = null,

    val mimeType: String,

    val year: Int = 0,

    @ColumnInfo(name = "track_number")
    val trackNumber: Int = 0,

    @ColumnInfo(name = "date_added")
    val dateAdded: Long = 0L,

    @ColumnInfo(name = "date_modified")
    val dateModified: Long = 0L,

    /** 收藏（「我喜欢」）。曲库同步对账时必须从旧行保留，否则 upsert 会冲掉用户标记 */
    val favorite: Boolean = false,

    /** 累计播放次数（听歌统计 / 「按播放次数」排序用；同步对账时保留旧值） */
    @ColumnInfo(name = "play_count")
    val playCount: Int = 0,

    /** 最近一次播放时间（epoch 毫秒，0 表示从未播放） */
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long = 0L,

    /**
     * 用户是否手动编辑过信息（标题/歌手/专辑/封面，含联网匹配）。
     * 为 true 时曲库同步不再用 MediaStore 覆盖这些展示字段，保留用户修正。
     */
    @ColumnInfo(name = "user_edited")
    val userEdited: Boolean = false
) {
    /** 是否 APE 格式（Monkey's Audio，需 FFmpeg 转码回退播放） */
    val isApe: Boolean
        get() = mimeType.equals("audio/ape", ignoreCase = true) ||
                path.endsWith(".ape", ignoreCase = true)

    /** 是否无损格式（用于 UI 标识） */
    val isLossless: Boolean
        get() = isApe || mimeType.equals("audio/x-wav", ignoreCase = true) ||
                mimeType.equals("audio/flac", ignoreCase = true) ||
                path.endsWith(".flac", ignoreCase = true) ||
                path.endsWith(".wav", ignoreCase = true)
}
