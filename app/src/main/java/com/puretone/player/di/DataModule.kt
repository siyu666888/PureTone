package com.puretone.player.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.puretone.player.data.local.PureToneDatabase
import com.puretone.player.data.local.dao.PlayHistoryDao
import com.puretone.player.data.local.dao.PlaylistDao
import com.puretone.player.data.local.dao.SongDao
import com.puretone.player.data.local.dao.StatsDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据层依赖注入模块。
 *
 * 单例：数据库（昂贵资源）与 DataStore（进程级单文件）均全局唯一；
 * 播放服务与 UI 通过 Repository 间接访问，避免直接持有。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PureToneDatabase =
        Room.databaseBuilder(
            context,
            PureToneDatabase::class.java,
            PureToneDatabase.DATABASE_NAME
        )
            .addMigrations(
                PureToneDatabase.MIGRATION_1_2,
                PureToneDatabase.MIGRATION_2_3,
                PureToneDatabase.MIGRATION_3_4
            )
            .build()

    @Provides
    fun provideSongDao(db: PureToneDatabase): SongDao = db.songDao()

    @Provides
    fun providePlaylistDao(db: PureToneDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun providePlayHistoryDao(db: PureToneDatabase): PlayHistoryDao = db.playHistoryDao()

    @Provides
    fun provideStatsDao(db: PureToneDatabase): StatsDao = db.statsDao()

    /**
     * 偏好 DataStore：主题 / 播放 / 音效设置，前缀分组见 SettingsRepository。
     * 文件损坏时用空偏好替换（回退默认设置），避免冷启动直接崩溃。
     */
    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { context.preferencesDataStoreFile("puretone_settings") }
        )
}
