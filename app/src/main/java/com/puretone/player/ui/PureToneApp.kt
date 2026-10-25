package com.puretone.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.puretone.player.data.settings.PureToneSettings
import com.puretone.player.playback.toPlayMode
import com.puretone.player.ui.components.MiniPlayer
import com.puretone.player.ui.screens.BrowseScreen
import com.puretone.player.ui.screens.EqScreen
import com.puretone.player.ui.screens.GroupDetailScreen
import com.puretone.player.ui.screens.MeScreen
import com.puretone.player.ui.screens.PlayerScreen
import com.puretone.player.ui.screens.SettingsScreen
import com.puretone.player.ui.screens.StatsScreen
import com.puretone.player.ui.theme.PureToneTheme
import com.puretone.player.ui.theme.PureToneThemeMode

/** 导航路由 */
object Routes {
    const val BROWSE = "browse"
    const val ME = "me"
    const val SETTINGS = "settings"
    const val PLAYER = "player"
    const val EQ = "eq"
    const val STATS = "stats"
    const val GROUP_DETAIL = "group/{type}/{key}"

    /**
     * 分组二级详情页路由（type ∈ {album, artist, folder}，key 经 URI 编码）。
     *
     * P0-1（第二轮复审）：必须用 [Uri.encode] 而非 `URLEncoder`。
     * Navigation 2.8.x 的 `NavDeepLink.getMatchingPathArguments` 在把路径参数放入
     * Bundle **之前已经 `Uri.decode` 解过一次**（已从 navigation-common AAR 字节码确认），
     * 因此这里只能用与之互为逆运算的 `Uri.encode`；旧实现用 `URLEncoder`（form 语义：
     * 空格→`+`、`%`→`%25`）再在取参处 `URLDecoder.decode` 二次解码，会导致：
     * - 含 `%` 的名字（如专辑「100% Rock」）经一次 Uri.decode 后变成 `100%+Rock`，
     *   二次 URLDecoder 遇 `%+R` 非法转义直接抛 IllegalArgumentException → 崩溃；
     * - 含 `+` 的名字（如歌手「+44」「C++」）被静默解成 `" 44"`/`"C  "` → 分组错乱。
     */
    fun groupDetail(type: String, key: String): String =
        "group/$type/${Uri.encode(key)}"
}

/** 应用根：主题 + 底部导航 + 迷你播放条 + 导航宿主 */
@Composable
fun PureToneApp() {
    val playerVm: PlayerViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()

    // P1-10：根部只订阅离散字段 hasCurrentSong（state 含 500ms position tick，
    // 根部直接订阅会 2Hz 重组整棵导航树）；高频进度收集下沉进 MiniPlayer 子树。
    val hasCurrentSong by playerVm.hasCurrentSong.collectAsStateWithLifecycle()
    val settings by settingsVm.state.collectAsStateWithLifecycle()
    // P0-8：迷你条的标题/歌手/封面取 Room 实时当前曲（编辑后立即刷新），
    // 而不是 PlaybackUiState 里设置队列时构建的 QueueEntry 快照。
    val currentSong by playerVm.currentSong.collectAsStateWithLifecycle()

    val nav = rememberNavController()
    val navBackStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val onPlayer = currentRoute == Routes.PLAYER

    PureToneTheme(
        themeMode = resolveThemeMode(settings.themeMode),
        amoledBlack = settings.amoledBlack,
        dynamicColor = settings.dynamicColor
    ) {
        Scaffold(
            bottomBar = {
                if (!onPlayer) {
                    Column {
                        if (hasCurrentSong) {
                            MiniPlayer(
                                stateFlow = playerVm.state,
                                // P3-27：launchSingleTop，避免连点/多入口重复压入多个播放页实例
                                onOpen = { nav.navigate(Routes.PLAYER) { launchSingleTop = true } },
                                onToggle = playerVm::togglePlay,
                                onPrev = playerVm::prev,
                                onNext = playerVm::next,
                                liveTitle = currentSong?.title,
                                liveArtist = currentSong?.artist,
                                liveCoverUri = currentSong?.coverUri?.let { Uri.parse(it) }
                            )
                        }
                        NavigationBar {
                            val items = listOf(
                                Triple(Routes.BROWSE, "音乐", Icons.Default.Headphones),
                                Triple(Routes.ME, "我的", Icons.Default.Album),
                                Triple(Routes.SETTINGS, "设置", Icons.Default.Settings)
                            )
                            val current = navBackStackEntry
                            items.forEach { (route, label, icon) ->
                                NavigationBarItem(
                                    selected = current?.destination?.hierarchy?.any { it.route == route } == true,
                                    onClick = {
                                        nav.navigate(route) {
                                            popUpTo(nav.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = Routes.BROWSE,
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                // playerVm 由根部（Activity 作用域）统一创建并下发：
                // 若各目的地自己 hiltViewModel()，拿到的是 NavBackStackEntry 作用域的
                // 独立实例，其 init 会各自跑一次「恢复上次会话」——恢复以 autoPlay=false
                // 重建队列并暂停，于是「进播放页/我的/统计页」都会打断正在播放的歌。
                // 复用同一实例也避免每个页面各起一份 stateIn 管线（P1-10 同源）。
                composable(Routes.BROWSE) { BrowseScreen(navController = nav, playerVm = playerVm) }
                composable(Routes.ME) { MeScreen(navController = nav, playerVm = playerVm) }
                composable(Routes.SETTINGS) { SettingsScreen(navController = nav) }
                composable(Routes.PLAYER) { PlayerScreen(navController = nav, vm = playerVm) }
                composable(Routes.EQ) { EqScreen(navController = nav) }
                composable(Routes.STATS) { StatsScreen(navController = nav, playerVm = playerVm) }
                composable(
                    route = Routes.GROUP_DETAIL,
                    arguments = listOf(
                        navArgument("type") { type = NavType.StringType },
                        navArgument("key") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val gType = backStackEntry.arguments?.getString("type") ?: "album"
                    // P0-1：Navigation 已自动 Uri.decode 过一次，这里直接取原值，
                    // 绝不能再用 URLDecoder 二次解码（见 Routes.groupDetail 注释）
                    val gKey = backStackEntry.arguments?.getString("key") ?: ""
                    GroupDetailScreen(navController = nav, type = gType, key = gKey, playerVm = playerVm)
                }
            }
        }
    }
}

private fun resolveThemeMode(mode: String): PureToneThemeMode = when (mode) {
    PureToneSettings.THEME_LIGHT -> PureToneThemeMode.LIGHT
    PureToneSettings.THEME_DARK -> PureToneThemeMode.DARK
    PureToneSettings.THEME_BLACK -> PureToneThemeMode.BLACK
    else -> PureToneThemeMode.SYSTEM
}
