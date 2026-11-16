package com.puretone.player.ui

import com.puretone.player.data.repository.SyncResult

/**
 * 曲库同步结果的一次性提示语义（纯判定，不依赖 Android Context / 资源）。
 *
 * 背景：`MusicRepository.syncLibrary()` 的防误删保险在「疑似大量音乐被移除」时会
 * 抑制本次删除并落盘诊断日志，[SyncResult.suppressed] 随之带出；但此前该字段只被
 * 写进 `LibraryViewModel.lastSync`，UI 没有任何渲染入口 —— 用户点完扫描只看到列表
 * 没变化，无法区分「本来就没有需要删的文件」与「删除被安全闸拦下」，容易反复重试
 * 或误判为扫描失效。
 *
 * 把「要不要提示、提示哪一类」抽成纯函数，单测无需 Robolectric / 字符串资源即可
 * 锁定判据；文案与资源 id 由 `SyncNotice.toMessage()`（见 SyncNoticeMessage.kt）
 * 承载，保持可国际化，ViewModel 只保留一行转发、不含判定逻辑。
 *
 * 判据红线：**只认 [SyncResult.suppressed]**。正常同步里 `removed == 0` 极其常见
 * （没有文件被删），`added` / `skippedNonMusic` 也与抑制无关，均不得用于反推。
 */
sealed interface SyncNotice {

    /** 正常同步：不打扰用户 */
    data object None : SyncNotice

    /**
     * 防误删保险抑制了本次删除：一个都没删，曲库仍是 [total] 首。
     * 提示需说明「本次未清理」并给出可操作指引，避免用户误判为 App 故障。
     */
    data class DeletionSuppressed(val total: Int) : SyncNotice
}

/** 由同步结果推导一次性提示；判据只取 [SyncResult.suppressed]，行数取 [SyncResult.total]。 */
fun SyncResult.toNotice(): SyncNotice =
    if (suppressed) SyncNotice.DeletionSuppressed(total) else SyncNotice.None
