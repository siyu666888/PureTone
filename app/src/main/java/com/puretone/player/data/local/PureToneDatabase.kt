package com.puretone.player.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.puretone.player.data.local.dao.PlayHistoryDao
import com.puretone.player.data.local.dao.PlaylistDao
import com.puretone.player.data.local.dao.SongDao
import com.puretone.player.data.local.dao.StatsDao
import com.puretone.player.data.local.entity.PlayHistoryEntity
import com.puretone.player.data.local.entity.PlaylistEntity
import com.puretone.player.data.local.entity.PlaylistSongEntity
import com.puretone.player.data.local.entity.SongEntity

/**
 * PureTone 本地数据库。
 *
 * version 1 —— 初始 schema（曲库 / 歌单 / 关联 / 历史）。
 * version 2 —— 曲库新增 [SongEntity.favorite] 收藏列（迁移见 [MIGRATION_1_2]）。
 * version 3 —— 听歌统计：曲库新增 play_count / last_played_at，历史新增 duration_ms
 *              （迁移见 [MIGRATION_2_3]）。
 * version 4 —— 歌单关联补索引与外键级联：playlist_songs 加 song_id 索引 +
 *              playlist_id / song_id 两个 CASCADE 外键；play_history 加 song_id
 *              索引 + CASCADE 外键（迁移见 [MIGRATION_3_4]）。
 *              外键执行无需额外配置：Room 生成的 openHelper 在 onOpen 时
 *              自动 `PRAGMA foreign_keys = ON`（迁移期间临时关闭）。
 * version 5 —— 排序/时间窗查询补索引（P2-2 / P2-3）：
 *              - play_history(played_at)：「最近播放」倒序 LIMIT、统计页
 *                时间窗聚合、小时级清理 DELETE 的支撑列（全是 Flow，无索引时
 *                每次写入都全表扫描 + 重排）；
 *              - songs(date_added / (play_count, last_played_at) / duration)：
 *                曲库页四个排序 Flow 与「最爱歌曲榜」的支撑列（迁移见
 *                [MIGRATION_4_5]）。纯 CREATE INDEX，无表重建、无数据搬运。
 * schema JSON 由 KSP room.schemaLocation 导出至 app/schemas，供后续迁移校验。
 */
@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlayHistoryEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class PureToneDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun statsDao(): StatsDao

    companion object {
        const val DATABASE_NAME = "puretone.db"

        /** v1 → v2：曲库表新增收藏列，默认 0（未收藏） */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `songs` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 → v3：听歌统计。
         * - songs 新增 play_count / last_played_at；
         * - play_history 新增 duration_ms（累计听歌时长估算）。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `songs` ADD COLUMN `play_count` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `songs` ADD COLUMN `last_played_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `songs` ADD COLUMN `user_edited` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `play_history` ADD COLUMN `duration_ms` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3 → v4：歌单关联补索引 + 外键级联（P1-4）。
         *
         * SQLite 无法 ALTER 加约束/索引，需重建两张子表（建新表 → 拷数据 →
         * 删旧表 → 改名 → 补索引）。建表语句与 Room 按实体注解生成的
         * v4 schema 逐字一致（列类型/顺序/外键顺序），否则运行期 schema 校验会崩。
         *
         * 另先清理历史遗留的悬空引用：旧版删除歌曲（或那个"删反方向"的 bug）
         * 会在 playlist_songs / play_history 留下指向不存在歌曲的行。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 0) 清理悬空引用（外键加上后它们永远是约束隐患）
                db.execSQL(
                    "DELETE FROM `playlist_songs` WHERE `song_id` NOT IN (SELECT `id` FROM `songs`)"
                )
                db.execSQL(
                    "DELETE FROM `play_history` WHERE `song_id` NOT IN (SELECT `id` FROM `songs`)"
                )

                // 1) playlist_songs：song_id 索引 + playlist_id / song_id 两个 CASCADE
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playlist_songs_new` (
                        `playlist_id` INTEGER NOT NULL,
                        `song_id` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `added_at` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`, `song_id`),
                        FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`song_id`) REFERENCES `songs`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `playlist_songs_new` (`playlist_id`, `song_id`, `position`, `added_at`)
                    SELECT `playlist_id`, `song_id`, `position`, `added_at` FROM `playlist_songs`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `playlist_songs`")
                db.execSQL("ALTER TABLE `playlist_songs_new` RENAME TO `playlist_songs`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_songs_song_id` " +
                        "ON `playlist_songs` (`song_id`)"
                )

                // 2) play_history：song_id 索引 + CASCADE（删歌曲时历史一并清理）
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `play_history_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `song_id` INTEGER NOT NULL,
                        `played_at` INTEGER NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        FOREIGN KEY(`song_id`) REFERENCES `songs`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `play_history_new` (`id`, `song_id`, `played_at`, `duration_ms`)
                    SELECT `id`, `song_id`, `played_at`, `duration_ms` FROM `play_history`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `play_history`")
                db.execSQL("ALTER TABLE `play_history_new` RENAME TO `play_history`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_play_history_song_id` " +
                        "ON `play_history` (`song_id`)"
                )
            }
        }

        /**
         * v4 → v5：排序/时间窗查询补索引（P2-2 / P2-3）。
         *
         * 纯 CREATE INDEX——SQLite 支持在线建索引，无需重建表、无数据搬运。
         * 索引名必须与 Room 按实体 @Index 注解生成的名字逐字一致，否则运行期
         * schema 校验（TableInfo 比对）会崩：
         * - songs(date_added)              → index_songs_date_added
         * - songs(play_count, last_played_at) → index_songs_play_count_last_played_at
         * - songs(duration)                → index_songs_duration
         * - play_history(played_at)        → index_play_history_played_at
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_songs_date_added` " +
                        "ON `songs` (`date_added`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_songs_play_count_last_played_at` " +
                        "ON `songs` (`play_count`, `last_played_at`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_songs_duration` " +
                        "ON `songs` (`duration`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_play_history_played_at` " +
                        "ON `play_history` (`played_at`)"
                )
            }
        }
    }
}
