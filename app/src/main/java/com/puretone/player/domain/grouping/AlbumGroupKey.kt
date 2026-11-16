package com.puretone.player.domain.grouping

/**
 * 专辑分组详情页的路由 key 编解码（domain 层纯函数，无 Android 依赖）。
 *
 * 背景：音乐页跳专辑详情用三段 key `"volumeName|albumId|album"` 定位
 * （P2-4：卷名隔离 SD 卡与内部存储的同名 album_id）。此前这段契约被复制成
 * 4 份字符串拼接/切分，散落在 BrowseScreen / BrowseSections / LibraryViewModel /
 * GroupDetailScreen：任一处改分隔符、字段顺序或 `limit`，跳详情就会静默取错专辑，
 * 而这类错误只有真机点进去才看得见。收敛到本文件后可纯 JVM round-trip 单测锁定。
 *
 * 既有语义**逐项保留，勿擅自改动**：
 * - `encode` 固定三段顺序 `volumeName|albumId|album`，无转义、无 URL 编码
 *   （Uri.encode 由 Routes.groupDetail 在外层负责，勿在此重复）；
 * - `decode` 用 `split("|", limit = 3)`：专辑名本身含 `|` 时，多余分隔符全部
 *   归入第三段（limit=3 的语义），不会丢失专辑名；
 * - 字段兜底与原先内联代码一致：卷名缺失→""、`albumId` 非数字/缺失→0L
 *   （0 表示未知专辑，仓库层按专辑名匹配）、第三段专辑名缺失→null
 *   （null 让详情页回退到首曲的 album 名；注意空字符串 "" 不等于缺失，
 *   不能把 "" 归一成 null，否则会改变 fallback 行为）。
 */
data class AlbumGroupKey(
    val volumeName: String,
    val albumId: Long,
    /** 第三段专辑名；仅当 key 不足三段时为 null。空串 "" 是合法值，不得转成 null。 */
    val album: String?
) {
    companion object {

        /** 编码为路由 key；字段顺序固定为 卷名|专辑ID|专辑名。 */
        fun encode(volumeName: String, albumId: Long, album: String): String =
            "$volumeName|$albumId|$album"

        /** 解码路由 key；缺段/非法数字按上述既有兜底处理，专辑名缺失返回 null。 */
        fun decode(key: String): AlbumGroupKey {
            val seg = key.split("|", limit = 3)
            return AlbumGroupKey(
                volumeName = seg.getOrNull(0) ?: "",
                albumId = seg.getOrNull(1)?.toLongOrNull() ?: 0L,
                album = seg.getOrNull(2)
            )
        }
    }
}
