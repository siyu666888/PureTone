package com.puretone.player.playback

import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.data.repository.MusicRepository

/**
 * [PlaybackSessionArchive] 的 Room 实现（P1-1 拆分收尾：把匿名 object 提成具名类）。
 *
 * [MusicRepository] 是 final 类无法替身，故它是存档能力的唯一真实来源；本类只是把
 * 它的四个方法映射成 [SavedSession] / 歌曲列表，便于 [PlaybackSessionStore] 在纯 JVM 上用替身测试。
 */
internal class RoomPlaybackSessionArchive(
    private val musicRepository: MusicRepository
) : PlaybackSessionArchive {

    override suspend fun load(): SavedSession? =
        musicRepository.loadPlaybackSession()?.let {
            SavedSession(songIds = it.songIds, index = it.index, positionMs = it.positionMs)
        }

    override suspend fun songsByIds(ids: List<Long>): List<SongEntity> =
        musicRepository.getSongs(ids)

    override suspend fun save(songIds: List<Long>, index: Int, positionMs: Long) {
        musicRepository.savePlaybackSession(songIds, index, positionMs)
    }

    override suspend fun clear() {
        musicRepository.clearPlaybackSession()
    }
}
