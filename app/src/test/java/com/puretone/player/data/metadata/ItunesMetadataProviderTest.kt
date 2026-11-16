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
 * A2 第一道校验的接线测试：直接喂 JSON 给解析层，证明 Provider **真的**调用了
 * [CoverUrlPolicy]，而不是只把策略类摆在旁边。删掉 Provider 里的 sanitize 调用，
 * 下面两条「恶意 URL」用例必须变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItunesMetadataProviderTest {

    private val provider = ItunesMetadataProvider()

    private fun parseOne(artworkUrl: String?): MetadataCandidate? {
        val item = JSONObject()
            .put("trackName", "Song")
            .put("artistName", "Artist")
            .put("artworkUrl100", artworkUrl ?: JSONObject.NULL)
        val body = JSONObject().put("results", JSONArray().put(item)).toString()
        return provider.parse(body).firstOrNull()
    }

    @Test
    fun `合法iTunes封面被升级为600x600`() {
        val candidate = parseOne(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/abc/100x100bb.jpg"
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/abc/600x600bb.jpg",
            candidate?.artworkUrl
        )
    }

    @Test
    fun `http明文封面被丢弃`() {
        assertNull(parseOne("http://is1-ssl.mzstatic.com/a.jpg")?.artworkUrl)
    }

    @Test
    fun `非白名单主机封面被丢弃`() {
        assertNull(parseOne("https://evil.example.com/a.jpg")?.artworkUrl)
        assertNull(parseOne("https://mzstatic.com.evil.com/a.jpg")?.artworkUrl)
    }

    @Test
    fun `字段缺失或为JSONnull时封面为null且其它字段仍解析`() {
        val candidate = parseOne(null)
        assertNull(candidate?.artworkUrl)
        assertEquals("Song", candidate?.title)
    }
}
