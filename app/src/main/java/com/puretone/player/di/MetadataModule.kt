package com.puretone.player.di

import com.puretone.player.data.metadata.ItunesMetadataProvider
import com.puretone.player.data.metadata.LrcLibLyricsProvider
import com.puretone.player.data.metadata.LyricsProvider
import com.puretone.player.data.metadata.SongMetadataProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 元数据提供者绑定：接口 [SongMetadataProvider] → 实现 [ItunesMetadataProvider]。
 * 后续若接入其它数据源（MusicBrainz / 网易云等），改此一处即可。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MetadataModule {

    @Binds
    @Singleton
    abstract fun bindSongMetadataProvider(impl: ItunesMetadataProvider): SongMetadataProvider

    @Binds
    @Singleton
    abstract fun bindLyricsProvider(impl: LrcLibLyricsProvider): LyricsProvider
}
