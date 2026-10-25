package com.puretone.player.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 主题模式：跟随系统 / 浅色 / 深色 / 纯黑（AMOLED） */
enum class PureToneThemeMode {
    SYSTEM, LIGHT, DARK, BLACK
}

/**
 * PureTone 应用主题。
 *
 * 取色三层优先级（设计决策 D2）：
 * 1. 品牌静态色板 —— 全局默认（本函数 dynamicColor 默认 false）；
 * 2. 播放页封面取色 —— 默认开，由播放页局部光晕实现（M4）；
 * 3. 系统壁纸动态色 —— 默认关，仅在用户显式开启且 Android 12+ 时生效。
 *
 * @param themeMode 主题模式，默认跟随系统
 * @param amoledBlack 深色模式下是否启用纯黑表面（OLED 省电）
 * @param dynamicColor 是否启用系统动态取色（默认关，遵循 D2）
 */
@Composable
fun PureToneTheme(
    themeMode: PureToneThemeMode = PureToneThemeMode.SYSTEM,
    amoledBlack: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        PureToneThemeMode.SYSTEM -> isSystemInDarkTheme()
        PureToneThemeMode.LIGHT -> false
        PureToneThemeMode.DARK, PureToneThemeMode.BLACK -> true
    }
    val useBlack = amoledBlack || themeMode == PureToneThemeMode.BLACK

    val colorScheme: ColorScheme = when {
        // P2-5：纯黑（BLACK 模式或 AMOLED 开关）优先级高于系统动态取色，确保 OLED 省电生效
        dark && useBlack -> PureToneColorSchemes.Black

        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        dark -> PureToneColorSchemes.Dark
        else -> PureToneColorSchemes.Light
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // P3-23：旧实现强转 Activity——在预览、或非 Activity context（如某些
            // 嵌入场景）下会直接 ClassCastException 崩掉；系统栏配色属于锦上添花，
            // 取不到 window 就跳过即可。
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PureToneTypography,
        shapes = PureToneShapes,
        content = content
    )
}
