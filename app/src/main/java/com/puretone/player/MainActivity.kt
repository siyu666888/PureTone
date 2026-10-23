package com.puretone.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.puretone.player.ui.permission.PermissionGate
import com.puretone.player.ui.PureToneApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * PureTone 唯一 Activity（Compose 单 Activity 架构）。
 * 启动屏经 installSplashScreen 挂接，随后交棒给 Compose 导航。
 *
 * 注意：不要在这里放「曲库为空就扫描」的 LaunchedEffect——
 * library StateFlow 冷启动首帧必然是空列表，非空库也会触发全库重扫。
 * 扫描由 BrowseScreen 的 autoScanIfEmpty()（查库计数）负责，见 P0-6。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PermissionGate {
                PureToneApp()
            }
        }
    }
}
