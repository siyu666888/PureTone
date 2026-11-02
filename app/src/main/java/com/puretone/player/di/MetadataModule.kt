package com.puretone.player.di

import com.puretone.player.data.metadata.ItunesMetadataProvider
import com.puretone.player.data.metadata.LyricsProvider
import com.puretone.player.data.metadata.MultiChannelLyricsProvider
import com.puretone.player.data.metadata.SongMetadataProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 元数据提供者绑定：接口 [SongMetadataProvider] → 实现 [ItunesMetadataProvider]。
 * 后续若接入其它数据源（MusicBrainz / 网易云等），改此一处即可。
 *
 * 歌词：[LyricsProvider] → [MultiChannelLyricsProvider]（多渠道聚合：本地 .lrc /
 * 内嵌歌词 / AMLL / LRCLIB）。原先的单渠道 `LrcLibLyricsProvider` 已降级为
 * 聚合器内部的渠道之一，不再直接对外暴露。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MetadataModule {

    @Binds
    @Singleton
    abstract fun bindSongMetadataProvider(impl: ItunesMetadataProvider): SongMetadataProvider

    @Binds
    @Singleton
    abstract fun bindLyricsProvider(impl: MultiChannelLyricsProvider): LyricsProvider
}
