package com.puretone.player.ui

import com.puretone.player.R

/**
 * [SyncNotice] → [UiMessage] 的资源化映射：把「要不要提示、用哪条文案、带什么参数」
 * 从 ViewModel 里抽成可单测的纯函数，VM 只保留一行转发。
 *
 * 背景：`LibraryViewModel.scan()` 原本内联一段 `when (toNotice())` 直接构造
 * [UiMessage.Res]。ViewModel 依赖 5 个 final 具体类、工程又无 mock 框架，
 * 无法轻量构造来做构造级集成测试；把这段唯一含逻辑的映射下沉后，判据
 * （`SyncResult.toNotice()`）、资源 id 与参数（本函数）、最终文案
 * （`UiMessage.resolve()`）都能在单测里被逐层锁定，避免「改了 VM 而测试不知情」。
 *
 * 约定：
 * - [SyncNotice.None] 必须返回 `null`（清空上一次未消费的提示），不能发空消息；
 * - 抑制提示的资源固定为 `pt_browse_052`，参数**只取** [SyncNotice.DeletionSuppressed.total]
 *   （事务提交后 Room `songs` 表的真实行数），不得用 added / removed / 常量替代；
 * - 文案保持可国际化，本层不碰 Android Context，解析交给 [UiMessage.resolve]。
 */
fun SyncNotice.toMessage(): UiMessage? = when (this) {
    SyncNotice.None -> null
    is SyncNotice.DeletionSuppressed -> UiMessage.Res(
        R.string.pt_browse_052,
        listOf<Any>(total)
    )
}
