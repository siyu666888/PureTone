package com.puretone.player.ui

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
import java.net.URLDecoder

/** 导航路由 */
object Routes {
    const val BROWSE = "browse"
    const val ME = "me"
    const val SETTINGS = "settings"
    const val PLAYER = "player"
    const val EQ = "eq"
    const val STATS = "stats"
    const val GROUP_DETAIL = "group/{type}/{key}"

    /** 分组二级详情页路由（type ∈ {album, artist, folder}，key 经 URL 编码） */
    fun groupDetail(type: String, key: String): String =
        "group/$type/${java.net.URLEncoder.encode(key, "UTF-8")}"
}

/** 应用根：主题 + 底部导航 + 迷你播放条 + 导航宿主 */
@Composable
fun PureToneApp() {
    val playerVm: PlayerViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()

    val playerState by playerVm.state.collectAsStateWithLifecycle()
    val settings by settingsVm.state.collectAsStateWithLifecycle()

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
                        if (playerState.currentSongId != null) {
                            MiniPlayer(
                                state = playerState,
                                onOpen = { nav.navigate(Routes.PLAYER) },
                                onToggle = playerVm::togglePlay,
                                onPrev = playerVm::prev,
                                onNext = playerVm::next
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
                composable(Routes.BROWSE) { BrowseScreen(navController = nav) }
                composable(Routes.ME) { MeScreen(navController = nav) }
                composable(Routes.SETTINGS) { SettingsScreen(navController = nav) }
                composable(Routes.PLAYER) { PlayerScreen(navController = nav) }
                composable(Routes.EQ) { EqScreen(navController = nav) }
                composable(Routes.STATS) { StatsScreen(navController = nav) }
                composable(
                    route = Routes.GROUP_DETAIL,
                    arguments = listOf(
                        navArgument("type") { type = NavType.StringType },
                        navArgument("key") { type = NavType.StringType }
                    )
                ) { backStackEntry ->
                    val gType = backStackEntry.arguments?.getString("type") ?: "album"
                    val gKey = backStackEntry.arguments?.getString("key")
                        ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""
                    GroupDetailScreen(navController = nav, type = gType, key = gKey)
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
