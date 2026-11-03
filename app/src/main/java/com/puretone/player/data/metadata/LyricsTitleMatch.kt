package com.puretone.player.data.metadata

/**
 * 歌词候选的曲名一致性校验。
 *
 * ## 为什么需要它
 *
 * 「下载歌词」在找不到精确匹配时会退到**搜索兜底**，而搜索接口是模糊的：
 * 用歌手名检索会返回该歌手的**所有**歌曲。若不校验曲名，就会出现
 * 「给《晴天》下载到了同一歌手的《告白气球》」这种结果——
 * **给用户错误的歌词比明确告诉他「没找到」糟糕得多**：
 * 用户会以为歌词是对的，对着错的词唱/看，直到发现才回来投诉。
 *
 * 真机实测（2026-11-03）正是踩到了这个坑：曲名 `Qing Tian`、歌手 `Jay Chou`，
 * AMLL 的 `musicName+artistName` 与 `q` 两个查询都返回空，
 * 而「用歌手名再搜一次」的兜底逻辑捞回了同歌手的《告白气球》并写进了 .lrc。
 *
 * ## 判定规则
 *
 * 只接受「归一化后完全相等」或「一方包含另一方」的曲名：
 * - 相等 → 强匹配（100 分）；
 * - 包含 → 匹配（60 分），用于 `晴天 (Live)` / `晴天（Live版）` 这类同曲变体；
 * - 其余 → **判为不匹配**（0 分），宁可返回 null 让 UI 提示「未找到」。
 *
 * 归一化会去掉空格、标点、全角半角差异与大小写，使
 * `Qing Tian` / `qingtian` / `Qing-Tian` 视为同一曲名。
 */
object LyricsTitleMatch {

    /** 强匹配：归一化后完全相等 */
    const val SCORE_EXACT = 100

    /** 弱匹配：一方包含另一方（同曲变体） */
    const val SCORE_CONTAINS = 60

    /** 不匹配 */
    const val SCORE_NONE = 0

    /**
     * 计算候选曲名与请求曲名的匹配分。
     *
     * @param candidate 词库返回的曲名
     * @param title 用户请求的曲名
     */
    fun score(candidate: String, title: String): Int {
        val c = normalize(candidate)
        val t = normalize(title)
        if (c.isBlank() || t.isBlank()) return SCORE_NONE
        return when {
            c == t -> SCORE_EXACT
            c.contains(t) || t.contains(c) -> SCORE_CONTAINS
            else -> SCORE_NONE
        }
    }

    /** 是否达到可接受的匹配阈值（相等或包含） */
    fun isAcceptable(candidate: String, title: String): Boolean =
        score(candidate, title) >= SCORE_CONTAINS

    /**
     * 归一化：转小写、去掉所有非字母数字与中日韩字符（含空格、标点、全角符号）。
     *
     * 保留中日韩是为了让中文曲名也能做包含判定（如 `晴天（Live）` → `晴天live`）。
     */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            when {
                ch.isLetterOrDigit() -> sb.append(ch)
                ch.code in 0x4E00..0x9FFF -> sb.append(ch)
                ch.code in 0x3040..0x30FF -> sb.append(ch)
                ch.code in 0xAC00..0xD7AF -> sb.append(ch)
                else -> Unit
            }
        }
        return sb.toString()
    }
}
