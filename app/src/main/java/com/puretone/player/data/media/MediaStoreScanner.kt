package com.puretone.player.data.media

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 扫描得到的原始歌曲（转换为 SongEntity 由 Repository 负责） */
data class ScannedSong(
    val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val path: String,
    val size: Long,
    val coverUri: String?,
    val mimeType: String,
    val year: Int,
    val trackNumber: Int,
    val dateAdded: Long,
    val dateModified: Long
)

/** 扫描结果：音乐文件 + 被跳过的非音乐文件数量 */
data class ScanResult(
    val songs: List<ScannedSong>,
    val skippedNonMusic: Int
)

/**
 * 系统媒体库扫描器。
 *
 * 排除规则（与需求文档对齐）：
 * 1. 仅 IS_MUSIC != 0 的音乐文件；
 * 2. 时长 >= 30s（过滤提示音/音效）；
 * 3. 排除系统目录与录音目录：Ringtones / Notifications / Alarms /
 *    Recordings / Recorder / VoiceRecorder / Sounds / CallRecordings 等；
 * 4. 排除录音类 MIME（audio/amr、audio/amr-wb、audio/3gpp、audio/gsm）与
 *    录音类扩展名（.amr/.awb/.3gp/.gsm 等），即便文件落在普通目录也一律跳过；
 * 5. Kotlin 侧对路径再做一次大小写不敏感复核（SQL LIKE 兜底），
 *    防止个别机型 MediaStore 未按预期归一化路径。
 * 6. 封面校验：albumart 占位 URI 仅在真能解析出图时才写入 coverUri，
 *    无内嵌封面的歌曲按缺失处理（供「补全缺失封面」识别）。
 *
 * 全程 IO 线程，不阻塞主线程。
 */
@Singleton
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val songs = mutableListOf<ScannedSong>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" AND ${MediaStore.Audio.Media.DURATION} >= ?")
            EXCLUDED_DIRS.forEach {
                append(" AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?")
            }
            if (RECORDING_MIME_TYPES.isNotEmpty()) {
                val placeholders = RECORDING_MIME_TYPES.joinToString(",") { "?" }
                append(" AND ${MediaStore.Audio.Media.MIME_TYPE} NOT IN ($placeholders)")
            }
        }
        val selectionArgs = arrayOf(
            MIN_DURATION_MS.toString(),
            *EXCLUDED_DIRS.map { "%/$it/%" }.toTypedArray(),
            *RECORDING_MIME_TYPES.toTypedArray()
        )

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val yearIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val trackIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val addedIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

            // albumart 占位 URI 存在性缓存：同一专辑只需校验一次
            val albumArtCache = HashMap<Long, Boolean>()
            while (cursor.moveToNext()) {
                val albumId = cursor.getLong(albumIdIdx)
                val song = ScannedSong(
                    mediaStoreId = cursor.getLong(idIdx),
                    title = cursor.getString(titleIdx) ?: "",
                    artist = cursor.getString(artistIdx) ?: "",
                    album = cursor.getString(albumIdx) ?: "",
                    albumId = albumId,
                    duration = cursor.getLong(durationIdx),
                    path = cursor.getString(dataIdx) ?: continue,
                    size = cursor.getLong(sizeIdx),
                    // 仅当 albumart URI 真能解析出图才写入；否则置空——
                    // 无内嵌封面的专辑也会生成占位 URI，直接入库会导致：
                    // 1) 列表/播放页封面空白（URI 解析失败）；
                    // 2) 「补全缺失封面」永远判不中（cover_uri 非空）。
                    coverUri = if (albumId > 0 && albumArtExists(albumId, albumArtCache)) {
                        "content://media/external/audio/albumart/$albumId"
                    } else null,
                    mimeType = cursor.getString(mimeIdx) ?: "",
                    year = cursor.getInt(yearIdx),
                    trackNumber = cursor.getInt(trackIdx) % 1000,
                    dateAdded = cursor.getLong(addedIdx) * 1000,
                    dateModified = cursor.getLong(modifiedIdx) * 1000
                )
                // Kotlin 侧二次过滤：SQL LIKE 之外的兜底（大小写/扩展名/MIME）
                if (!song.isNonMusic()) songs += song
            }
        }

        // 统计被跳过的非音乐文件：候选（IS_MUSIC!=0 且 >=30s）总数 - 最终入库数
        val candidates = countCandidates()
        ScanResult(
            songs = songs,
            skippedNonMusic = (candidates - songs.size).coerceAtLeast(0)
        )
    }

    /** 未应用排除规则的候选音频条数，用于统计"跳过多少非音乐文件" */
    private fun countCandidates(): Int {
        var count = 0
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?",
            arrayOf(MIN_DURATION_MS.toString()),
            null
        )?.use { cursor -> count = cursor.count }
        return count
    }

    /**
     * 校验专辑封面 URI 是否真的能解析出图（按 albumId 缓存，同专辑只查一次）。
     *
     * MediaStore 对无内嵌封面的专辑同样会生成 `content://media/external/audio/albumart/$albumId`
     * 占位 URI，但该 URI 实际打不开（openInputStream 返回 null 或 0 字节）。
     * 这里以「能打开且至少 1 字节」为判据，解析不了的按无封面处理。
     */
    private fun albumArtExists(albumId: Long, cache: MutableMap<Long, Boolean>): Boolean {
        cache[albumId]?.let { return it }
        val uri = Uri.parse("content://media/external/audio/albumart/$albumId")
        val exists = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> input.read() != -1 } ?: false
        }.getOrDefault(false)
        cache[albumId] = exists
        return exists
    }

    companion object {
        /** 时长下限 30 秒 */
        const val MIN_DURATION_MS = 30_000L

        /**
         * 排除的系统/录音目录（路径片段）。
         * 覆盖各厂商与录音 App 常见落盘位置：铃声、通知、闹钟、系统音效、
         * 录音机（Recordings / Recorder / VoiceRecorder / Sounds / CallRecordings 等）。
         */
        val EXCLUDED_DIRS = listOf(
            "Ringtones",
            "Notifications",
            "Alarms",
            "Recordings",
            "Recorder",
            "VoiceRecorder",
            "Sounds",
            "CallRecordings",
            "Call Recorder",
            "WhatsApp Voice Notes",
            "Telegram Audio",
            "Audio Recorder",
            "Sound Recordings"
        )

        /**
         * 录音类 MIME 类型（即使落在普通目录也一律排除）。
         * AMR / 3GPP 是手机录音最典型的封装格式。
         */
        val RECORDING_MIME_TYPES = listOf(
            "audio/amr",
            "audio/amr-wb",
            "audio/3gpp",
            "audio/gsm"
        )

        /**
         * 录音类文件扩展名（无后缀 MIME 不可靠时的兜底）。
         * 不含 .m4a——它既是录音封装也是常见音乐格式，交由目录/MIME 规则判定。
         */
        val RECORDING_EXTENSIONS = listOf(
            "amr", "awb", "3gp", "3gpp", "gsm", "evrc", "qcp", "dcs"
        )
    }
}

/** 非音乐文件判定（路径大小写不敏感） */
private fun ScannedSong.isNonMusic(): Boolean {
    val lowerPath = path.lowercase()
    return MediaStoreScanner.EXCLUDED_DIRS.any { lowerPath.contains("/${it.lowercase()}/") } ||
        MediaStoreScanner.RECORDING_EXTENSIONS.any { lowerPath.endsWith(".$it") } ||
        MediaStoreScanner.RECORDING_MIME_TYPES.any { mimeType.equals(it, ignoreCase = true) }
}
