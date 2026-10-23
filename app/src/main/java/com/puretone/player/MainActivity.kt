package com.puretone.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.puretone.player.ui.LibraryViewModel
import com.puretone.player.ui.permission.PermissionGate
import com.puretone.player.ui.PureToneApp
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * PureTone 唯一 Activity（Compose 单 Activity 架构）。
 * 启动屏经 installSplashScreen 挂接，随后交棒给 Compose 导航。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val libVm: LibraryViewModel = hiltViewModel()
            PermissionGate {
                PureToneApp()
                // 首次进入且曲库为空时自动扫描本地音乐
                LaunchedEffect(Unit) {
                    if (libVm.library.value.isEmpty()) libVm.scan()
                }
            }
        }
    }
}
