package com.puretone.player.ui

import com.puretone.player.data.repository.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SyncResult.toNotice] 纯判定单测（纯 JVM，不依赖 Robolectric / Android Context / 资源）。
 *
 * 两条底线：
 * ① 判据**只能**是 `SyncResult.suppressed`，不能用 `removed == 0`、added、total、
 *    skippedNonMusic 等字段反推「删除被抑制」——正常同步里 removed == 0 极其常见；
 * ② 抑制提示携带的行数必须是事务提交后的 `total`，不是 added / removed。
 *
 * 用例里刻意构造了「removed == 0 但未抑制」「removed > 0 但未抑制」
 * 「suppressed 为真但 removed > 0」三类组合，任何偷换判据的实现都会变红。
 */
class SyncNoticeTest {

    /** 其余字段固定且与 total 不同，便于抓出「行数取错字段」的变异 */
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

    @Test
    fun `suppressed 为真时给出抑制提示`() {
        assertEquals(
            SyncNotice.DeletionSuppressed(137),
            result(suppressed = true).toNotice()
        )
    }

    @Test
    fun `提示携带的行数取自 total 而非 added 或 removed`() {
        val notice = result(
            suppressed = true,
            total = 2048,
            added = 7,
            removed = 5,
            skippedNonMusic = 99
        ).toNotice()
        assertEquals(SyncNotice.DeletionSuppressed(2048), notice)
    }

    @Test
    fun `suppressed 为假时不提示`() {
        assertEquals(SyncNotice.None, result(suppressed = false).toNotice())
    }

    @Test
    fun `removed 为 0 且未抑制时仍不提示（防止用 removed 反推）`() {
        // 这是最常见的正常同步：没有任何文件需要删除，绝不能误报「防误删已介入」
        assertEquals(
            SyncNotice.None,
            result(suppressed = false, removed = 0, added = 0, skippedNonMusic = 0).toNotice()
        )
    }

    @Test
    fun `removed 大于 0 且未抑制时不提示`() {
        // 正常清理录音 / 已删除文件，安全闸未介入
        assertEquals(
            SyncNotice.None,
            result(suppressed = false, removed = 42, added = 0, skippedNonMusic = 8).toNotice()
        )
    }

    @Test
    fun `suppressed 为真时即使 removed 大于 0 也以 suppressed 为准`() {
        // 生产侧抑制时 removed 必为 0，这里构造不一致组合，专抓
        // 「改成 removed == 0 才提示」这类偷换判据的变异
        assertEquals(
            SyncNotice.DeletionSuppressed(137),
            result(suppressed = true, removed = 9).toNotice()
        )
    }

    @Test
    fun `total 为 0 的抑制仍然提示`() {
        // 极端场景（库被清空 / 事务异常）也不能静默吞掉抑制事实
        assertEquals(
            SyncNotice.DeletionSuppressed(0),
            result(suppressed = true, total = 0).toNotice()
        )
    }

    @Test
    fun `SyncResult 默认参数不触发提示`() {
        assertEquals(
            SyncNotice.None,
            SyncResult(added = 5, removed = 5, total = 500).toNotice()
        )
    }
}
