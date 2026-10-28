package com.puretone.player.di

import com.puretone.player.playback.ApeCachePolicy
import com.puretone.player.playback.FfmpegKitTranscoder
import com.puretone.player.playback.Transcoder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 转码器绑定：接口 [Transcoder] → 实现 [FfmpegKitTranscoder]。
 *
 * **这里是全项目唯一决定「用哪个转码引擎」的地方。** ffmpeg-kit 已归档停维护、
 * Maven Central 下架（当前依赖仓库内的本地 AAR），若日后要替换成自编译精简
 * ffmpeg 或其它后端，新增一个实现类并把下面的 @Binds 改指过去即可——
 * 播放层的缓存 / 并发 / LRU 逻辑（`ApeTranscoder`）无需任何改动。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TranscoderModule {

    @Binds
    @Singleton
    abstract fun bindTranscoder(impl: FfmpegKitTranscoder): Transcoder

    companion object {

        /** 缓存上限策略；抽成 Provider 便于按 BuildConfig / 设备存储调整 */
        @JvmStatic
        @Provides
        @Singleton
        fun provideApeCachePolicy(): ApeCachePolicy = ApeCachePolicy()
    }
}
