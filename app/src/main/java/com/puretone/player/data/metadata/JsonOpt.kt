package com.puretone.player.data.metadata

import org.json.JSONArray
import org.json.JSONObject

/**
 * 外部 JSON 的字符串字段统一取法（P1-2）。
 *
 * Android 框架 `org.json` 的 `optString` 对**显式 JSON null** 返回字面量 `"null"`
 * 而非空串（R2 P1-1 已用 android-all 字节码实证，API 26–34 行为一致）：
 * `JSONObject.NULL.toString()` = "null" → `JSON.toString` = `String.valueOf(...)` →
 * `optString` 的 `result != null` 判定使其通过。LRCLIB 对缺失字段正是返回
 * `"plainLyrics":null`，裸 `takeIf { it.isNotBlank() }` 会把四个字母 "null"
 * 当成歌词写进 .lrc，界面提示「已保存」却永远不显示。
 *
 * iTunes / LRCLIB / AMLL 三个渠道都吃外部 JSON，原先各写一份防护（AMLL 干脆漏了），
 * 故统一收敛到这里：显式 null、缺失、空串、纯空白一律返回 `null`。
 *
 * [normalizeJsonString] 是纯 Kotlin 判定，供纯 JVM 单测直接锁定，不必依赖
 * Robolectric 里的 Android `org.json` 实现。
 */
internal fun normalizeJsonString(raw: String?): String? =
    raw?.takeIf { it.isNotBlank() && it != "null" }

/** 取非空、非 `"null"` 的字符串字段；`JSONArray` 下标版本见同名重载。 */
internal fun JSONObject.optNonNullString(name: String): String? =
    normalizeJsonString(optString(name))

/** AMLL 的 `musicNames` 是数组，下标取值同样会踩显式 null。 */
internal fun JSONArray.optNonNullString(index: Int): String? =
    normalizeJsonString(optString(index))
