package com.puretone.player.data.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal

/**
 * 测试替身：模拟「SQL LIKE 失效」的 MediaStore provider（robolectric 4.15+ 适配）。
 *
 * 背景：4.15+ 新增 `FakeMediaProvider` + `MediaUriMatcher`，用 `SQLiteQueryBuilder`
 * **真执行 selection**，MediaStore authority 的查询被 provider 接管，
 * `ShadowContentResolver.setCursor()` 注入的 `RoboCursor` 不再生效，且 `RoboCursor` /
 * `BaseCursor` 已官方 deprecated → 推荐 `MatrixCursor`。
 *
 * 若直接用 `FakeMediaProvider.insert()` 填数据，SQL 会把录音行提前过滤掉，Kotlin 侧
 * 二次过滤（这组回归测试真正要锁定的兜底层）根本不会被覆盖 —— 假绿灯。因此这里
 * 显式注册一个「忽略 selection」的 provider：用 `MatrixCursor` 原样返回全部行，
 * 完整保留「SQL LIKE 失效、由 Kotlin 兜底」的测试语义。
 *
 * 两类 query 的区分（[MediaStoreScanner.scanChanged] 对同一 URI 发两次）：
 * - 变更行 / 全量扫描：`PROJECTION`（13 列）+ `SORT_TITLE`；
 * - 当前全量路径清单（删除对账用）：`PATH_PROJECTION`（2 列）+ `sortOrder = null`。
 * [pathListUnavailable] 为 true 时，只让后一类查询返回空 cursor，用于模拟
 * 「路径清单不可信 → 安全闸不得删歌」，不影响变更行查询。
 *
 * 所有计数/日志字段供用例做**负向断言**：证明 provider 确实被调用、且确实返回了
 * 预期行数，避免「provider 根本没被走到」也绿灯的假象。
 */
internal class SqlLikeDisabledProvider(
    private val columns: Array<String>,
    private val rows: List<Array<Any?>>,
    private val pathListUnavailable: Boolean = false
) : ContentProvider() {

    val receivedSelections = mutableListOf<String>()
    val queryLog = mutableListOf<String>()

    /** 最近一次 query 实际返回的行数 */
    var returnedRows = 0
        private set

    /** 变更行 / 全量扫描类 query 次数 */
    var fullProjectionQueries = 0
        private set

    /** 路径清单类 query 次数 */
    var pathListQueries = 0
        private set

    /** 最近一次路径清单 query 实际返回的行数 */
    var pathListCursorRows = 0
        private set

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        selection?.let { receivedSelections += it }
        val columnCount = projection?.size ?: -1
        // 两个判据取或：列数（PATH_PROJECTION 固定 2 列）或 sortOrder（路径清单查询传 null）。
        // Robolectric 对 projection 的转发若有差异，sortOrder 仍可兜住；全量查询固定带
        // SORT_TITLE 且是 13 列，两个判据都为 false。
        val isPathListQuery = columnCount == PATH_PROJECTION_SIZE || sortOrder == null
        queryLog += "cols=$columnCount sort=${sortOrder ?: "null"} sel=${selection?.take(48) ?: "null"}"
        if (isPathListQuery) pathListQueries++ else fullProjectionQueries++

        val cursor = MatrixCursor(columns)
        // 生产代码不校验返回的列数与请求 projection 是否一致：readRows/readPaths 都按列名
        // 取值，13 列 cursor 对两类查询都兼容。
        if (!(pathListUnavailable && isPathListQuery)) {
            rows.forEach { cursor.addRow(it.asList()) }
        }
        returnedRows = cursor.count
        if (isPathListQuery) pathListCursorRows = cursor.count
        return cursor
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?
    ): Cursor = query(uri, projection, selection, selectionArgs, sortOrder)

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private companion object {
        const val PATH_PROJECTION_SIZE = 2
    }
}
