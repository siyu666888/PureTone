package com.puretone.player.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.puretone.player.R
import com.puretone.player.data.repository.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SyncNotice.toMessage] 映射层测试：锁死「VM 里那一段唯一含逻辑的映射」。
 *
 * 覆盖三层，任意一层偷换都会变红：
 * ① 判据：只认 [SyncResult.suppressed]，`removed == 0` 的正常同步不得发提示；
 * ② 结构：[UiMessage.Res] 的资源 id 必须是 `pt_browse_052`，参数**只取** total；
 * ③ 文案：[UiMessage.resolve] 用真实资源解析后必须是完整提示，且不残留 `%1$s`。
 *
 * 用 Robolectric 而非纯 JVM，是为了让 `resolve()` 真的走一遍 Android 资源格式化
 * （`formatted="false"` 的 `%1$s` 传 Int 是否生效，静态断言证明不了）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncNoticeMessageTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** 其余字段刻意与 total 不同，专抓「参数取错字段」的变异 */
    private fun result(
        suppressed: Boolean,
        total: Int = 137,
        added: Int = 3,
        removed: Int = 0,
        skippedNonMusic: Int = 11
    ) = SyncResult(
        added = added,
        removed = removed,
        total = total,
        skippedNonMusic = skippedNonMusic,
        suppressed = suppressed
    )

    private fun expectedText(total: Int) =
        "疑似大量歌曲被移除，已暂停清理以防误删（当前保留 $total 首）；" +
            "请检查存储权限与文件后再扫描"

    @Test
    fun `None 不产生提示（返回 null 以清空上一次未消费消息）`() {
        assertNull(SyncNotice.None.toMessage())
    }

    @Test
    fun `抑制提示携带正确的资源 id 与 total 参数`() {
        val message = SyncNotice.DeletionSuppressed(137).toMessage()
        assertTrue(message is UiMessage.Res)
        val res = message as UiMessage.Res
        assertEquals(R.string.pt_browse_052, res.resId)
        assertEquals(listOf<Any>(137), res.args)
    }

    @Test
    fun `真实资源解析出完整抑制文案`() {
        val message = SyncNotice.DeletionSuppressed(137).toMessage()
        assertEquals(expectedText(137), message?.resolve(context))
    }

    @Test
    fun `文案里不残留格式化占位符`() {
        val text = SyncNotice.DeletionSuppressed(137).toMessage()?.resolve(context).orEmpty()
        assertFalse(text.contains("%1\$s"))
        assertTrue(text.contains("137"))
    }

    @Test
    fun `total 为 0 的抑制仍提示且参数为 0`() {
        val message = SyncNotice.DeletionSuppressed(0).toMessage() as UiMessage.Res
        assertEquals(listOf<Any>(0), message.args)
        assertEquals(expectedText(0), message.resolve(context))
    }

    @Test
    fun `大曲库行数随 total 走而不是 added 或 removed`() {
        val message = SyncNotice.DeletionSuppressed(2048).toMessage() as UiMessage.Res
        assertEquals(listOf<Any>(2048), message.args)
        assertEquals(expectedText(2048), message.resolve(context))
    }

    @Test
    fun `端到端 suppressed 为真时发出抑制提示`() {
        val message = result(suppressed = true, total = 512, added = 7, removed = 5)
            .toNotice().toMessage() as UiMessage.Res
        assertEquals(R.string.pt_browse_052, message.resId)
        assertEquals(listOf<Any>(512), message.args)
        assertEquals(expectedText(512), message.resolve(context))
    }

    @Test
    fun `端到端 removed 为 0 但未抑制时不发提示`() {
        // 最常见的正常同步：没有文件要删。绝不能误报「防误删已介入」。
        assertNull(
            result(suppressed = false, removed = 0, added = 0, skippedNonMusic = 0)
                .toNotice().toMessage()
        )
    }

    @Test
    fun `端到端 removed 大于 0 但未抑制时不发提示`() {
        assertNull(
            result(suppressed = false, removed = 42, added = 0, skippedNonMusic = 8)
                .toNotice().toMessage()
        )
    }

    @Test
    fun `端到端 suppressed 为真时即使 removed 大于 0 仍以 suppressed 为准`() {
        val message = result(suppressed = true, removed = 9, total = 137)
            .toNotice().toMessage() as UiMessage.Res
        assertEquals(listOf<Any>(137), message.args)
    }

    @Test
    fun `SyncResult 默认参数不触发提示`() {
        assertNull(
            SyncResult(added = 5, removed = 5, total = 500).toNotice().toMessage()
        )
    }
}
