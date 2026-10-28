package com.puretone.player.data.media

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.puretone.player.util.UnknownText
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
 * 增量扫描结果。
 *
 * [changed] 只含「上次扫描之后有改动」的行（新增 / 文件被改写）；
 * [currentPaths] 是当前媒体库里**全部**有效音乐路径——删除对账必须以它为准，
 * 否则「文件被删」这类变化在增量下永远发现不了（增量查询看不到已消失的行）。
 */
data class IncrementalScanResult(
    val changed: List<ScannedSong>,
    val currentPaths: Set<String>,
    val skippedNonMusic: Int
)

/**
 * 系统媒体库扫描器。
 *
 * 覆盖面（P1-6）：
 * - 多卷查询：主外部存储 + SD 卡等所有外部卷。API 29+ 用
 *   [MediaStore.getExternalVolumeNames] 逐卷查；同时保留旧版
 *   `EXTERNAL_CONTENT_URI`（"external" 是主卷别名），两条路径按
 *   文件路径去重，任何机型都不会漏也不会重；
 * - 过滤 `IS_PENDING`（正在写入，API 29+）与 `IS_TRASHED`（回收站，API 30+）。
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
 * 已知取舍（审查报告 P1-6，暂不做）：
 * - `DATA` 列在 API 29 被标记 deprecated，但持有读媒体权限的应用仍可读到
 *   真实路径；全量切 `RELATIVE_PATH + content URI` 会波及播放/转码/文件夹
 *   分组/同步锚点，属架构级改造，留待专门迭代；
 * - albumart 逐专辑 openInputStream 校验（同专辑单次扫描内缓存）；
 * - 查询级增量扫描（DATE_MODIFIED > lastScan）暂不做——删除检测需要全量
 *   路径清单，而 P1-3 的增量对账已避免"文件没变也重写整表"。
 *
 * 全程 IO 线程，不阻塞主线程。
 */
@Singleton
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 增量扫描：只回传 [sinceMs] 之后改动过的行，外加当前全量路径清单。
     *
     * 为什么要两份数据：
     * - **变更行**（带时间条件）：只有这些行需要构建对象、校验专辑封面。
     *   全量扫描里最贵的一步正是封面校验（每个专辑一次 `openInputStream`），
     *   大曲库下增量能把它从「全部专辑」降到「变动的专辑」；
     * - **路径清单**（不带时间条件）：被删除的文件不会出现在任何增量查询里，
     *   只有拿到当前全量路径，才能算出「库里有、媒体库里没了」的差集。
     *   该查询只取 `DATA`/`MIME_TYPE` 两列（不取 14 列、不做封面校验），成本低得多。
     *
     * [sinceMs] <= 0 时退化为全量（所有行都算变更）。
     */
    suspend fun scanChanged(sinceMs: Long): IncrementalScanResult = withContext(Dispatchers.IO) {
        val sinceSec = if (sinceMs <= 0L) null else sinceMs / 1000
        val changedByPath = LinkedHashMap<String, ScannedSong>()
        val candidatePaths = HashSet<String>()
        val albumArtCache = HashMap<String, Boolean>()
        val allPaths = HashSet<String>()

        externalVolumeQueries().forEach { (uri, volumeName) ->
            // 1) 变更行
            context.contentResolver.query(
                uri,
                PROJECTION,
                buildSelection(sinceSec),
                buildSelectionArgs(sinceSec),
                SORT_TITLE
            )?.use { cursor ->
                candidatePaths += readRows(cursor, volumeName, changedByPath, albumArtCache)
            }
            // 2) 当前全量路径（删除对账用）
            context.contentResolver.query(
                uri,
                PATH_PROJECTION,
                buildSelection(null),
                buildSelectionArgs(null),
                null
            )?.use { cursor ->
                allPaths += readPaths(cursor)
            }
        }

        IncrementalScanResult(
            changed = changedByPath.values.toList(),
            currentPaths = allPaths,
            skippedNonMusic = (candidatePaths.size - changedByPath.size).coerceAtLeast(0)
        )
    }

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        // 按路径去重：主卷可能同时经旧版 URI 与命名卷 URI 返回
        val songsByPath = LinkedHashMap<String, ScannedSong>()
        val candidatePaths = HashSet<String>()
        // albumart 占位 URI 存在性缓存：同一专辑只需校验一次。
        // P1-6：键必须带卷名——ALBUM_ID 是「每卷独立」编号，SD 卡与内部存储会撞号，
        // 只用 albumId 做键会把「A 卷有图」的结论错用到 B 卷同名 id 上
        //（写入实际打不开的封面 URI → 列表封面空白；反之漏记可用封面）。
        val albumArtCache = HashMap<String, Boolean>()

        externalVolumeQueries().forEach { (uri, volumeName) ->
            context.contentResolver.query(
                uri,
                PROJECTION,
                buildSelection(),
                buildSelectionArgs(),
                SORT_TITLE
            )?.use { cursor ->
                candidatePaths += readRows(cursor, volumeName, songsByPath, albumArtCache)
            }
        }

        // 跳过的非音乐文件 = 候选行数（去重后） - 最终入库数。
        // 旧实现为算这个数字多跑一次全表查询，现在直接从扫描光标统计。
        ScanResult(
            songs = songsByPath.values.toList(),
            skippedNonMusic = (candidatePaths.size - songsByPath.size).coerceAtLeast(0)
        )
    }

    /**
     * 需要查询的外部卷清单：`(URI, 卷名)`。
     *
     * API 29+ 返回全部外部卷（external_primary + SD 卡等）；
     * 旧版（或卷名取不到时）回退主卷 `EXTERNAL_CONTENT_URI`。
     * 无论哪种情况都带上旧版 URI——29+ 设备上它是主卷别名，
     * 与命名卷的结果按路径去重；老设备/测试环境则只有它有效。
     */
    private fun externalVolumeQueries(): List<Pair<Uri, String>> {
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getExternalVolumeNames(context) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val queries = buildList {
            add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to VOLUME_EXTERNAL)
            volumes.forEach { name ->
                val uri = runCatching { MediaStore.Audio.Media.getContentUri(name) }.getOrNull()
                if (uri != null) add(uri to name)
            }
        }
        return queries.distinctBy { it.first }
    }

    /** 读一个卷的光标：返回本卷候选路径集合，音乐行并入 [songsByPath]（按路径去重，跨卷不重复入库） */
    internal fun readRows(
        cursor: Cursor,
        volumeName: String,
        songsByPath: LinkedHashMap<String, ScannedSong>,
        albumArtCache: MutableMap<String, Boolean>
    ): Set<String> {
        val candidatePaths = HashSet<String>()
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

        while (cursor.moveToNext()) {
            val path = cursor.getString(dataIdx)
            if (path.isNullOrBlank()) continue
            candidatePaths += path
            val albumId = cursor.getLong(albumIdIdx)
            val song = ScannedSong(
                mediaStoreId = cursor.getLong(idIdx),
                title = cursor.getString(titleIdx) ?: "",
                // P2-9：<unknown>/unknown/未知 占位符归一为空串，
                // 由 Repository 的 ifBlank 兜底为「未知艺术家/未知专辑」，
                // 避免截图里 <unknown> 原样出现在播放页/队列/迷你条
                artist = UnknownText.normalizeArtist(cursor.getString(artistIdx)),
                album = UnknownText.normalizeAlbum(cursor.getString(albumIdx)),
                albumId = albumId,
                duration = cursor.getLong(durationIdx),
                path = path,
                size = cursor.getLong(sizeIdx),
                // 仅当 albumart URI 真能解析出图才写入；否则置空——
                // 无内嵌封面的专辑也会生成占位 URI，直接入库会导致：
                // 1) 列表/播放页封面空白（URI 解析失败）；
                // 2) 「补全缺失封面」永远判不中（cover_uri 非空）。
                // URI 带卷名：SD 卡专辑的图在主卷 URI 下解析不到。
                coverUri = if (albumId > 0 && albumArtExists(albumId, volumeName, albumArtCache)) {
                    "content://media/$volumeName/audio/albumart/$albumId"
                } else null,
                mimeType = cursor.getString(mimeIdx) ?: "",
                year = cursor.getInt(yearIdx),
                // P4：不再对 TRACK 取 %1000。MediaStore 对多碟片专辑的常见写法是
                // 「碟号*1000 + 轨号」（如 2003 = 第 2 碟第 3 轨），取模会把碟号抹掉，
                // 导致多碟专辑在「按轨号排序」时两碟交错。保留原值，排序自然按碟号分段。
                trackNumber = cursor.getInt(trackIdx),
                dateAdded = cursor.getLong(addedIdx) * 1000,
                dateModified = cursor.getLong(modifiedIdx) * 1000
            )
            // Kotlin 侧二次过滤：SQL LIKE 之外的兜底（大小写/扩展名/MIME）
            if (!song.isNonMusic()) songsByPath.putIfAbsent(path, song)
        }
        return candidatePaths
    }

    /**
     * 读取路径清单（删除对账用）：只取 `DATA`/`MIME_TYPE`，不构建对象、不校验封面。
     *
     * 与应用侧过滤保持一致：被判定为非音乐的路径不进清单，否则旧版本误入的
     * 录音文件会一直留在库里（清单里有它 → 永远不算「已删除」）。
     */
    internal fun readPaths(cursor: Cursor): Set<String> {
        val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        // 路径查询的投影固定带 MIME，但防御性处理：列缺失时按空 MIME 处理
        val mimeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
        val paths = HashSet<String>()
        while (cursor.moveToNext()) {
            val path = cursor.getString(dataIdx)
            if (path.isNullOrBlank()) continue
            val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) ?: "" else ""
            if (!isNonMusicPath(path, mime)) paths += path
        }
        return paths
    }

    /**
     * 扫描条件（所有卷共用）。
     * IS_PENDING / IS_TRASHED 是较新版本的列，老设备上加了会直接抛
     * "no such column"，按 API 级别条件拼接。
     *
     * [sinceSec] 非空时追加时间条件（MediaStore 的 DATE_MODIFIED 单位是**秒**）。
     */
    internal fun buildSelection(sinceSec: Long? = null): String = buildString {
        append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
        append(" AND ${MediaStore.Audio.Media.DURATION} >= ?")
        if (sinceSec != null) {
            append(" AND ${MediaStore.Audio.Media.DATE_MODIFIED} >= ?")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            append(" AND ${MediaStore.MediaColumns.IS_TRASHED} = 0")
        }
        EXCLUDED_DIRS.forEach {
            append(" AND ${MediaStore.Audio.Media.DATA} NOT LIKE ?")
        }
        if (RECORDING_MIME_TYPES.isNotEmpty()) {
            val placeholders = RECORDING_MIME_TYPES.joinToString(",") { "?" }
            append(" AND ${MediaStore.Audio.Media.MIME_TYPE} NOT IN ($placeholders)")
        }
    }

    /** 与 [buildSelection] 的 `?` 顺序严格对应：时长 → 时间下界 → 目录 → MIME */
    internal fun buildSelectionArgs(sinceSec: Long? = null): Array<String> = arrayOf(
        MIN_DURATION_MS.toString(),
        *(if (sinceSec != null) arrayOf(sinceSec.toString()) else emptyArray()),
        *EXCLUDED_DIRS.map { "%/$it/%" }.toTypedArray(),
        *RECORDING_MIME_TYPES.toTypedArray()
    )

    /**
     * 校验专辑封面 URI 是否真的能解析出图（按 albumId 缓存，同专辑只查一次）。
     *
     * MediaStore 对无内嵌封面的专辑同样会生成 `content://media/<volume>/audio/albumart/$albumId`
     * 占位 URI，但该 URI 实际打不开（openInputStream 返回 null 或 0 字节）。
     * 这里以「能打开且至少 1 字节」为判据，解析不了的按无封面处理。
     */
    private fun albumArtExists(
        albumId: Long,
        volumeName: String,
        cache: MutableMap<String, Boolean>
    ): Boolean {
        // P1-6：键带卷名（ALBUM_ID 每卷独立，见 scan() 里的缓存声明）
        val key = "$volumeName:$albumId"
        cache[key]?.let { return it }
        val uri = Uri.parse("content://media/$volumeName/audio/albumart/$albumId")
        val exists = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> input.read() != -1 } ?: false
        }.getOrDefault(false)
        cache[key] = exists
        return exists
    }

    companion object {
        /** 时长下限 30 秒 */
        const val MIN_DURATION_MS = 30_000L

        /** 旧版主卷 URI 的卷名（albumart URI 拼装用） */
        private const val VOLUME_EXTERNAL = "external"

        /** 删除对账用的轻量投影：只要路径与 MIME（判定排除规则需要） */
        private val PATH_PROJECTION = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE
        )

        /** 扫描排序：标题（不区分大小写） */
        private const val SORT_TITLE = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        private val PROJECTION = arrayOf(
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

        /**
         * 排除的系统/录音目录（路径片段）。
         * 覆盖各厂商与录音 App 常见落盘位置：铃声、通知、闹钟、系统音效、
         * 录音机（Recordings / Recorder / VoiceRecorder / Sounds / CallRecordings 等）。
         *
         * 注意：Sounds 等偏泛的目录按原始需求保留——它同时是部分厂商
         * 提示音的落点；若用户把正常音乐放进这些目录会被跳过（已知取舍，
         * 审查报告 P1-6 建议做成可配置，待产品决策）。
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

/**
 * 非音乐文件判定（路径大小写不敏感）。
 *
 * 抽取为独立函数：全量扫描（[ScannedSong]）与增量路径对账（只有路径+MIME）
 * 必须用**同一套**规则，否则两份数据口径不一——被过滤的路径若进了路径清单，
 * 旧版本误入的录音就永远不会被判定为「已删除」。
 */
internal fun isNonMusicPath(path: String, mimeType: String): Boolean {
    val lowerPath = path.lowercase()
    return MediaStoreScanner.EXCLUDED_DIRS.any { lowerPath.contains("/${it.lowercase()}/") } ||
        MediaStoreScanner.RECORDING_EXTENSIONS.any { lowerPath.endsWith(".$it") } ||
        MediaStoreScanner.RECORDING_MIME_TYPES.any { mimeType.equals(it, ignoreCase = true) }
}

private fun ScannedSong.isNonMusic(): Boolean = isNonMusicPath(path, mimeType)
