package com.puretone.player.util

/**
 * 元数据「未知」占位符归一（审查报告 P2-9）。
 *
 * MediaStore / 文件标签对缺失的歌手、专辑常写入 `<unknown>`（大小写不一）、
 * `unknown`、`未知` 或空串；播放器统一显示「未知艺术家 / 未知专辑」。
 * 旧实现只把空串归一，`<unknown>` 会原样出现在列表/播放页/队列/迷你条。
 *
 * 用法：入库前先 [normalizeArtist] / [normalizeAlbum]，返回空串表示缺失，
 * 由上层 `ifBlank { UNKNOWN }` 兜底；展示层同样可用它做二次兜底。
 */
object UnknownText {

    /** 视为「未知」的占位符（比较时忽略大小写与首尾空白） */
    private val PLACEHOLDERS = setOf("", "<unknown>", "unknown", "未知")

    private fun isPlaceholder(value: String): Boolean =
        value.trim().lowercase() in PLACEHOLDERS || value.trim() in PLACEHOLDERS

    /** 归一歌手字段：占位符 → 空串 */
    fun normalizeArtist(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (isPlaceholder(trimmed)) "" else trimmed
    }

    /** 归一专辑字段：占位符 → 空串 */
    fun normalizeAlbum(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (isPlaceholder(trimmed)) "" else trimmed
    }

    /** 展示兜底：空/占位符 → 给定默认值 */
    fun displayArtist(value: String?, fallback: String): String =
        normalizeArtist(value).ifBlank { fallback }

    /** 展示兜底：空/占位符 → 给定默认值 */
    fun displayAlbum(value: String?, fallback: String): String =
        normalizeAlbum(value).ifBlank { fallback }
}
