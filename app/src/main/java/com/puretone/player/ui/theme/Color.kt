package com.puretone.player.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * PureTone 品牌静态令牌。
 *
 * 这些色值不随主题切换，用于：
 * - 启动屏 / 图标（res/values/colors.xml 需同步）
 * - 播放页封面光晕等 M3 配色角色覆盖不到的场景
 */
object PureToneBrand {
    /** 品牌主色 #5E6AD2 —— "Pure" 的纯净蓝紫 */
    val Primary: Color = Color(0xFF5E6AD2)
    /** 主色浅阶 */
    val PrimaryLight: Color = Color(0xFF8A93E4)
    /** 主色深阶 */
    val PrimaryDark: Color = Color(0xFF3A449E)
    /** 主色容器（浅色） */
    val PrimaryContainer: Color = Color(0xFFE4E3FA)
    /** 标题色 */
    val Heading: Color = Color(0xFF232965)
    /** 辅助紫 */
    val Tertiary: Color = Color(0xFF7A56C6)
    /** 深空底（深色模式） */
    val DarkSurface: Color = Color(0xFF121218)
}

/**
 * 三套静态配色方案：
 * [PureToneLightScheme] 浅色 / [PureToneDarkScheme] 深色 / [PureToneBlackScheme] 纯黑（AMOLED）。
 *
 * 设计决策 D2：品牌静态色板为全局默认；播放页封面取色默认开、系统壁纸动态色默认关，
 * 因此 [com.puretone.player.ui.theme.PureToneTheme] 的 dynamicColor 参数默认 false。
 */
object PureToneColorSchemes {

    val Light = androidx.compose.material3.lightColorScheme(
        primary = PureToneBrand.Primary,
        onPrimary = Color.White,
        primaryContainer = PureToneBrand.PrimaryContainer,
        onPrimaryContainer = PureToneBrand.Heading,
        secondary = Color(0xFF5D61A4),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE3E3F7),
        onSecondaryContainer = Color(0xFF1B1D5C),
        tertiary = PureToneBrand.Tertiary,
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFEEE4FB),
        onTertiaryContainer = Color(0xFF2C1A5E),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        background = Color(0xFFFAF9FF),
        onBackground = Color(0xFF1B1B21),
        surface = Color(0xFFFAF9FF),
        onSurface = Color(0xFF1B1B21),
        surfaceVariant = Color(0xFFE5E2EC),
        onSurfaceVariant = Color(0xFF47464F),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF4F3FB),
        surfaceContainer = Color(0xFFEEEDF7),
        surfaceContainerHigh = Color(0xFFE8E7F2),
        surfaceContainerHighest = Color(0xFFE2E1ED),
        outline = Color(0xFF78767F),
        outlineVariant = Color(0xFFC9C7D0),
        inverseSurface = Color(0xFF303036),
        inverseOnSurface = Color(0xFFF3EFF7),
        inversePrimary = Color(0xFFBFC2F3),
    )

    val Dark = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFFBFC2F3),
        onPrimary = Color(0xFF2A3170),
        primaryContainer = Color(0xFF414A9C),
        onPrimaryContainer = Color(0xFFE4E3FA),
        secondary = Color(0xFFC3C5F0),
        onSecondary = Color(0xFF2E3270),
        secondaryContainer = Color(0xFF454B8C),
        onSecondaryContainer = Color(0xFFE3E3F7),
        tertiary = Color(0xFFD8C2F5),
        onTertiary = Color(0xFF43296F),
        tertiaryContainer = Color(0xFF5B3F94),
        onTertiaryContainer = Color(0xFFEEE4FB),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        background = PureToneBrand.DarkSurface,
        onBackground = Color(0xFFE4E1E9),
        surface = PureToneBrand.DarkSurface,
        onSurface = Color(0xFFE4E1E9),
        surfaceVariant = Color(0xFF47464F),
        onSurfaceVariant = Color(0xFFC9C7D0),
        surfaceContainerLowest = Color(0xFF0C0C12),
        surfaceContainerLow = Color(0xFF1A1A22),
        surfaceContainer = Color(0xFF1E1E28),
        surfaceContainerHigh = Color(0xFF292932),
        surfaceContainerHighest = Color(0xFF34343E),
        outline = Color(0xFF928F9A),
        outlineVariant = Color(0xFF47464F),
        inverseSurface = Color(0xFFE4E1E9),
        inverseOnSurface = Color(0xFF303036),
        inversePrimary = PureToneBrand.Primary,
    )

    /** AMOLED 纯黑：背景/表面为纯黑，其余沿用深色阶 */
    val Black = Dark.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0A0A0F),
        surfaceContainer = Color(0xFF101016),
        surfaceContainerHigh = Color(0xFF18181F),
        surfaceContainerHighest = Color(0xFF202027),
    )
}
