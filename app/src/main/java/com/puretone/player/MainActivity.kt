package com.puretone.player

import android.content.Intent
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

    /**
     * P2-1：Baseline Profile 采集用的初始路由 extra。
     *
     * 采集需要一条**不依赖曲库数据**的确定路径：
     * 若靠 UI 点击进入播放页，就要求设备上恰好有可点的歌曲——采集环境不可控，
     * 路径就会 flaky 或干脆走不到播放页（而播放页恰恰是最重的路径：
     * MediaSessionService 初始化 + ExoPlayer 构建 + 歌词面板 Compose 树）。
     *
     * 契约：`am start -n <pkg>/com.puretone.player.MainActivity --es puretone_route player`
     * 仅当 extra 存在时生效，正常启动（LAUNCHER intent）完全不受影响。
     */
    private var initialRoute: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initialRoute = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            PermissionGate {
                PureToneApp(initialRoute = initialRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 采集脚本可能复用已启动的 Activity（launchMode=singleTop），
        // 此时 extra 只在 onNewIntent 里，必须一并取。
        initialRoute = intent.getStringExtra(EXTRA_ROUTE)
    }

    companion object {
        /** Intent extra 名（与采集脚本 scripts/collect-baseline-profile.sh 保持一致）。 */
        const val EXTRA_ROUTE = "puretone_route"
    }
}
