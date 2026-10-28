package com.puretone.player.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 全局 [ImageLoader]：封面加载的统一入口。
 *
 * **为什么必须显式配置**：Coil 2 的默认 `ImageLoader` **不带磁盘缓存**
 * （只有内存缓存）。曲库页是上百张封面的网格墙，且封面来自 `content://`
 * MediaStore Uri——没有磁盘缓存时，每次冷启动都要重新走一遍「读 Uri → 解码」，
 * 首屏滚动明显掉帧，重复 I/O 与解码开销还会拖慢冷启动。
 *
 * 配置取舍：
 * - 磁盘缓存 150MB：封面原图常达数百 KB，大曲库（数千首）需要足够容量才能
 *   真正命中；放在 cacheDir，系统存储紧张时可回收，不影响用户数据。
 * - 内存缓存 25%：取 Coil 默认值；过低会导致列表来回滚动反复命中磁盘。
 * - `respectCacheHeaders=false`：本地媒体 Uri 没有 HTTP 缓存头语义，
 *   关掉可避免因为「无缓存头」被判定为不可缓存。
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    private const val DISK_CACHE_BYTES = 150L * 1024 * 1024

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(DEFAULT_MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()

    private const val DEFAULT_MEMORY_CACHE_PERCENT = 0.25
}
