package com.puretone.player.data.playlist

import com.puretone.player.data.local.entity.SongEntity
import com.puretone.player.lyrics.M3uEntry

/**
 * M3U 导入解析器：把歌单文件条目匹配为曲库歌曲。
 *
 * 匹配优先级：
 * 1. 绝对路径完全一致；
 * 2. 路径以相同片段结尾（处理导出方/导入方挂载点不同，如 /sdcard vs /storage/emulated/0）；
 * 3. 文件名（不含扩展名）唯一匹配。
 *
 * 纯 JVM 逻辑，可单测。
 *
 * R8-11：后缀层旧实现对每个条目线性过滤全库（O(n×m)）。改为一次构建
 * 「路径段后缀 → 库路径」倒排索引，每个条目只查索引（近似 O(d)，d=路径深度）。
 * 匹配语义（含段边界、歧义唯一性、精确/后缀/文件名三级优先）完全不变，
 * 由 PlaylistImportResolverTest 的新旧实现等价性测试锁定。
 */
object PlaylistImportResolver {

    /**
     * 一次构建的匹配索引。[byPath] 供精确层与后缀层取值，[bySuffix] 供后缀层按路径判歧义，
     * [byName] 供文件名层，[suffixToPaths] 是 R8-11 新增的倒排表。
     */
    private data class ImportIndex(
        val byPath: Map<String, SongEntity>,
        val bySuffix: Map<String, List<SongEntity>>,
        val byName: Map<String, List<SongEntity>>,
        val suffixToPaths: Map<String, Set<String>>
    )

    fun resolve(entries: List<M3uEntry>, library: List<SongEntity>): List<SongEntity> {
        if (entries.isEmpty() || library.isEmpty()) return emptyList()

        val index = buildIndex(library)
        val result = mutableListOf<SongEntity>()
        entries.forEach { entry ->
            // P2-8：导入条目可能带 file:// 前缀或 URL 编码（空格→%20 等）。
            // 先剥前缀、再按 '/' 归一化、最后 URL 解码，否则与曲库里的真实路径无法匹配。
            val normalized = urlDecode(entry.path)
                .removePrefix("file://")
                .replace('\\', '/')

            // 后缀层：收集所有满足「路径段后缀互含」的库路径，数量必须唯一才匹配。
            // P2-12：必须以「路径段边界」结尾匹配，倒排表的 key 正是每个 '/' 之后的子串，
            // 天然满足段边界，裸 endsWith 命中 "mymusic/a.mp3" 的问题不会出现。
            val candidates = HashSet<String>()
            // 条件2：库路径以 normalized 为段后缀（libPath.endsWith("/$normalized")）。
            index.suffixToPaths[normalized]?.let { candidates.addAll(it) }
            // 条件1：完全相等（exact 层优先返回，此处保留仅为候选集合语义完整）。
            if (normalized in index.byPath) candidates += normalized
            // 条件3：normalized 以库路径为段后缀（normalized.endsWith("/$libPath")）。
            pathSegmentSuffixes(normalized).forEach { suffix ->
                if (suffix in index.bySuffix) candidates += suffix
            }

            val match = index.byPath[normalized]
                ?: candidates.singleOrNull()?.let { index.bySuffix[it]?.firstOrNull() }
                ?: index.byName[fileNameKey(normalized)]?.takeIf { it.size == 1 }?.firstOrNull()
                ?: return@forEach
            if (result.none { it.id == match.id }) result += match
        }
        return result
    }

    /** 构建一次索引，复杂度 O(n×d)。 */
    private fun buildIndex(library: List<SongEntity>): ImportIndex {
        val byPath = library.associateBy { it.path }
        val bySuffix = library.groupBy { it.path }
        val byName = library.groupBy { fileNameKey(it.path) }
        val suffixToPaths = HashMap<String, MutableSet<String>>()
        bySuffix.keys.forEach { path ->
            pathSegmentSuffixes(path).forEach { suffix ->
                suffixToPaths.getOrPut(suffix) { HashSet() } += path
            }
        }
        return ImportIndex(byPath, bySuffix, byName, suffixToPaths)
    }

    /**
     * 枚举一个路径的所有「路径段后缀」：每个 '/' 之后到结尾的子串。
     * 与 `path.endsWith("/$x")` 严格等价（x 非空且不含前导 '/'），用于倒排表构建与查询。
     */
    private fun pathSegmentSuffixes(path: String): Sequence<String> = sequence {
        var start = 0
        while (start < path.length) {
            val slash = path.indexOf('/', start)
            if (slash < 0) break
            val suffix = path.substring(slash + 1)
            if (suffix.isNotEmpty()) yield(suffix)
            start = slash + 1
        }
    }

    private fun fileNameKey(path: String): String =
        path.substringAfterLast('/').substringBeforeLast('.').lowercase()

    /**
     * URL 解码（%XX 十六进制 → UTF-8 字节 → 字符串），纯 JVM 可测（P2-8 / 第七轮 R8-7）。
     * R8-7：旧实现逐 %XX 转单个 Char，把中文 UTF-8 三字节 `%E6%AD%8C` 解成三个 Latin-1
     * 乱码字符，导致中文路径全部匹配失效；改为按字节收集后整体 UTF-8 解码。
     * 不直接用 URLDecoder：它把 '+' 当空格，而文件路径里 '+' 是合法字符。
     */
    private fun urlDecode(s: String): String {
        if ('%' !in s) return s
        val bytes = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val b = hex.toIntOrNull(16)
                if (b != null) {
                    bytes.write(b)
                    i += 3
                    continue
                }
            }
            // 非 %XX：按 UTF-8 字节写入（中文原文路径同样正确）
            bytes.write(s[i].toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
