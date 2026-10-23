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
 * schema JSON 由 KSP room.schemaLocation 导出至 app/schemas，供后续迁移校验。
 */
@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        PlayHistoryEntity::class
    ],
    version = 3,
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
    }
}
