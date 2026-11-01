package com.puretone.player.util

/**
 * 繁体中文 → 简体中文（离线字符级转换）。
 *
 * 用途：联网元数据（歌名 / 歌手 / 专辑）与歌词在展示前统一转为简体，
 * 避免繁体结果混入界面。映射表由 zhconv 生成，覆盖 4056 个简繁有差异的常用汉字。
 *
 * 说明：仅做逐字映射，不做词组级语境纠错（如“乾隆”类罕见词可能被逐字转换）；
 * 对歌名 / 歌词等短文本足够，且无网络、无额外依赖。
 *
 * ⚠️ 实现要点：映射表含 631 个增补平面汉字（CJK 扩展 B），在 JVM 里占两个
 * UTF-16 代理码元。**必须按 Unicode 码点成对解读**，用 `String[i]`（UTF-16
 * 下标）会整体错位一格，把「繁體中文測試」转成「緑體中文测試」、把「周杰伦」
 * 转成「周傖倲」。详见 [MAP] 注释。
 */
object ChineseText {


    /**
     * 「繁体→简体」映射表，键/值均为 Unicode **码点**（code point）。
     *
     * 关键：映射表里含有 631 个增补平面汉字（CJK 扩展 B，如 `𦈏`），
     * 它们在 Java/Kotlin 的 `String` 里由 **两个 UTF-16 代理码元** 组成。
     * 因此本表必须按码点成对解读；若用 `T2S_PAIRS[i]`（UTF-16 下标）取字符，
     * 首个增补字之后的每一对都会错位一格，导致「繁體中文測試」被转成
     * 「緑體中文测試」这类错误结果。故此处统一用 `Character.codePointAt` 遍历。
     */
    private val MAP: Map<Int, Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        HashMap<Int, Int>(T2S_PAIRS.length / 2).apply {
            var i = 0
            while (i < T2S_PAIRS.length) {
                val key = Character.codePointAt(T2S_PAIRS, i)
                val keyLen = Character.charCount(key)
                if (i + keyLen >= T2S_PAIRS.length) break
                val value = Character.codePointAt(T2S_PAIRS, i + keyLen)
                put(key, value)
                i += keyLen + Character.charCount(value)
            }
        }
    }

    /**
     * 逐字转换为简体。
     * - null / 空串原样返回；
     * - 不含繁体字时直接返回原引用（零拷贝）。
     */
    fun toSimplified(input: String?): String? {
        if (input.isNullOrEmpty()) return input
        var changed = false
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = Character.codePointAt(input, i)
            val mapped = MAP[cp]
            if (mapped != null && mapped != cp) {
                sb.appendCodePoint(mapped)
                changed = true
            } else {
                sb.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        return if (changed) sb.toString() else input
    }
}
