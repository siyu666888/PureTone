package com.puretone.player.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 播放控制图标尺寸单一真源（R2 可维护性建议 3，2026-11-11 落地）。
 *
 * 此前 `buttonSize` / `iconSize` 的裸 `N.dp` 散落在 [PlayerComposables]、[MiniPlayerParts]、
 * `PlayerScreen` / `PlayerCoverFlip` / `CastRemoteParts` 五处，同一个 48dp 命中区被写了十几遍，
 * 调一处漏三处就会破坏「严格对称」的观感。这里按**尺寸档位**收敛，数值与原代码逐处相等
 * （见 `dist` 复核说明里的行多重集对照），本次是纯搬运、零行为改动。
 *
 * ⚠️ 每档都被多个页面共用，改任一档都会同时影响播放页、迷你条、封面翻页与投屏遥控页。
 * 调整前请整体验证 UI 对称，不要只为单页面改这里的值；若某页面确实需要独立尺寸，
 * 应新增一档语义常量而不是就地改共用档。
 */
internal object ControlIconSizes {

    /** 常规控制按钮命中区：播放页次操作 / 队列 / 播放模式、迷你条三键、封面翻页字号键 */
    val Button: Dp = 48.dp

    /** 主操作按钮命中区：播放页上一首 / 播放暂停 / 下一首、投屏遥控页左右键 */
    val ButtonLarge: Dp = 56.dp

    /** 投屏遥控页中央播放键命中区 */
    val ButtonHero: Dp = 72.dp

    /** 播放页中央 `PlayPauseButton` 命中区（唯一超大档，勿与其它按钮混用） */
    val PlayPause: Dp = 84.dp

    /** 封面翻页字号加减图标 */
    val IconTiny: Dp = 18.dp

    /** 播放页均衡器入口图标 */
    val IconSmall: Dp = 22.dp

    /** 迷你播放条三键图标 */
    val IconMini: Dp = 24.dp

    /** 播放页播放模式 / 队列浮动按钮图标 */
    val IconCompact: Dp = 26.dp

    /** `ControlIconButton` 默认图标尺寸 / 播放页队列键 */
    val IconStandard: Dp = 28.dp

    /** 投屏遥控页左右键图标 */
    val IconRemote: Dp = 30.dp

    /** 投屏遥控页中央播放键图标 */
    val IconRemoteHero: Dp = 36.dp

    /** 播放页上一首 / 播放暂停 / 下一首图标 */
    val IconLarge: Dp = 40.dp
}
