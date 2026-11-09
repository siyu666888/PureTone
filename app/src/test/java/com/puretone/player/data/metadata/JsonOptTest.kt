package com.puretone.player.data.metadata

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [normalizeJsonString] 纯判据单测（P1-2）。
 *
 * 这一层不依赖 Android 的 `org.json` 实现，纯 JVM 即可跑；真实平台行为由下面
 * [JsonOptAndroidJsonTest] 用 Robolectric 锁死。
 */
class JsonOptTest {

    @Test
    fun `JSON null字面量被过滤`() {
        assertNull(normalizeJsonString("null"))
    }

    @Test
    fun `大小写不同的NULL是合法文本不过滤`() {
        // JSON 的字面量只有小写 null；"NULL" 可能是真实歌名，不能误杀
        assertEquals("NULL", normalizeJsonString("NULL"))
    }

    @Test
    fun `空串与纯空白被过滤`() {
        assertNull(normalizeJsonString(""))
        assertNull(normalizeJsonString("   \t "))
    }

    @Test
    fun `null输入返回null`() {
        assertNull(normalizeJsonString(null))
    }

    @Test
    fun `合法文本原样保留`() {
        assertEquals("晴天 (Live)", normalizeJsonString("晴天 (Live)"))
        assertEquals(" 带前后空格的歌词 ", normalizeJsonString(" 带前后空格的歌词 "))
    }
}

/**
 * 平台行为实证：Android `org.json` 对显式 JSON null 确实返回 `"null"` 字符串，
 * 三个渠道共用的扩展必须把它挡掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JsonOptAndroidJsonTest {

    @Test
    fun `JSONObject显式null字段不返回字面量null`() {
        val obj = JSONObject("""{"lyrics":null,"musicName":"晴天"}""")
        assertNull(obj.optNonNullString("lyrics"))
        assertEquals("晴天", obj.optNonNullString("musicName"))
    }

    @Test
    fun `JSONArray下标显式null同样过滤`() {
        val arr = JSONArray("""["晴天",null]""")
        assertEquals("晴天", arr.optNonNullString(0))
        assertNull(arr.optNonNullString(1))
    }

    @Test
    fun `缺失字段返回null`() {
        assertNull(JSONObject("{}").optNonNullString("missing"))
    }
}
