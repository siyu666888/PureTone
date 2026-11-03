package com.puretone.player.playback.cast

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 一条历史投屏记录（用户投成功过的接收端）。
 *
 * 存在意义：SSDP 首搜经常空手（电视刚唤醒时不回 M-SEARCH，真机上出现过连续多轮 0 设备），
 * 但用户此前投过的设备是**确定存在**的。把「投成功过」这件事持久化下来，
 * 下次打开弹窗即使一条广播都没回来，也能直接把上次投过的电视列出来点。
 *
 * @property id 设备描述 URL（SSDP LOCATION），投送链路按它反查底层 [com.yinnho.upnpcast.DLNACast.Device]
 * @property name 设备显示名（friendlyName）
 * @property address 设备 IPv4；**排重主键**（同一台电视换名不换 IP，比 name 可信）
 * @property isTV 是否识别为电视（仅影响图标）
 * @property lastUsedAt 最近一次投屏成功的时间戳（毫秒）
 * @property useCount 累计投屏成功次数（同名换 IP 时用来保留历史权重）
 */
data class CastHistoryEntry(
    val id: String,
    val name: String,
    val address: String,
    val isTV: Boolean,
    val lastUsedAt: Long,
    val useCount: Int = 1
) {
    /** 还原成 UI 用的设备对象（id 可能在设备重启后失效，但投送会按 address 重解析） */
    fun toDevice(): CastDevice = CastDevice(id = id, name = name, address = address, isTV = isTV)
}

/**
 * 历史投屏列表的**纯逻辑**（编解码 + 合并裁剪 + 排重），不含任何 IO。
 *
 * 抽成纯函数而不是塞进 [CastHistoryStore]，是为了让「排重键选错」「上限裁剪把最近用的
 * 那台裁掉」这类问题能被单测直接钉死——它们不会崩，只会让用户觉得「我明明投过，怎么没了」。
 */
object CastHistoryRules {

    /** 最多保留几条：够覆盖「客厅电视 / 卧室电视 / 音箱 / 备用」这类真实场景，又不至于刷满弹窗 */
    const val MAX_ENTRIES = 8

    private const val FIELD_SEP = '\u001F'
    private const val RECORD_SEP = '\u001E'
    private const val FORMAT_TAG = "v1"
    private const val ON = "1"
    private const val OFF = "0"

    /**
     * 记录一次投屏成功。
     *
     * 排重顺序（不可调换）：
     * 1. **同 address** → 视为同一台设备，更新名称/id（电视重装 DMR、端口变化都能跟上）；
     * 2. **同名但换了 IP**（DHCP 重新分配）→ 仍视为同一台，跟随新 IP；
     * 3. 都没有 → 新增。
     *
     * 结果按 [CastHistoryEntry.lastUsedAt] 倒序裁到 [MAX_ENTRIES] 条——
     * 裁的永远是「最久没用」的那条，不会把刚投过的自己裁掉。
     */
    fun record(
        current: List<CastHistoryEntry>,
        device: CastDevice,
        now: Long
    ): List<CastHistoryEntry> {
        if (device.address.isBlank() || device.name.isBlank()) return current

        val byAddress = current.indexOfFirst { it.address == device.address }
        val byName = if (byAddress >= 0) -1 else current.indexOfFirst { it.name == device.name }
        val hit = if (byAddress >= 0) byAddress else byName

        val updated = current.toMutableList()
        val previous = if (hit >= 0) updated[hit] else null
        val entry = CastHistoryEntry(
            id = device.id,
            name = device.name,
            address = device.address,
            isTV = device.isTV,
            lastUsedAt = now,
            useCount = (previous?.useCount ?: 0) + 1
        )
        if (hit >= 0) updated[hit] = entry else updated += entry
        return updated.sortedByDescending { it.lastUsedAt }.take(MAX_ENTRIES)
    }

    /** 移除一条历史（用户主动「忘记」某台设备时用） */
    fun forget(current: List<CastHistoryEntry>, address: String): List<CastHistoryEntry> =
        current.filterNot { it.address == address }

    /**
     * 编码成可存 DataStore 的字符串。
     *
     * 字段用 `\u001F`（Unit Separator）、记录用 `\u001E`（Record Separator）——
     * 这两个控制字符不可能出现在设备名里，比 JSON 少一层解析依赖；
     * 每个字段仍过一遍 URL 编码，防止设备名里的 `%`/换行等把格式撑破。
     */
    fun encode(entries: List<CastHistoryEntry>): String {
        if (entries.isEmpty()) return ""
        return entries.joinToString(RECORD_SEP.toString()) { e ->
            listOf(
                e.id,
                e.name,
                e.address,
                if (e.isTV) ON else OFF,
                e.lastUsedAt.toString(),
                e.useCount.toString()
            ).joinToString(FIELD_SEP.toString()) { encodeField(it) }
        }.let { "$FORMAT_TAG$RECORD_SEP$it" }
    }

    /**
     * 解码；任何一条记录坏掉只丢那一条，整体不抛异常。
     *
     * 历史列表属于「丢了不影响播放」的辅助数据，绝不能因为一次格式变更让冷启动崩掉；
     * 同理也不做版本升级迁移——旧格式解不出来就当没有，重新投一次就有了。
     */
    fun decode(raw: String?): List<CastHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val parts = raw.split(RECORD_SEP)
        if (parts.size < 2 || parts[0] != FORMAT_TAG) return emptyList()
        return parts.drop(1)
            .mapNotNull(::decodeRecord)
            .filter { it.address.isNotBlank() && it.name.isNotBlank() }
            .sortedByDescending { it.lastUsedAt }
            .take(MAX_ENTRIES)
    }

    private fun decodeRecord(record: String): CastHistoryEntry? {
        val f = record.split(FIELD_SEP)
        if (f.size < 6) return null
        return runCatching {
            CastHistoryEntry(
                id = decodeField(f[0]),
                name = decodeField(f[1]),
                address = decodeField(f[2]),
                isTV = f[3] == ON,
                lastUsedAt = f[4].toLongOrNull() ?: 0L,
                useCount = f[5].toIntOrNull() ?: 1
            )
        }.getOrNull()
    }

    private fun encodeField(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault("")

    private fun decodeField(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault("")
}
