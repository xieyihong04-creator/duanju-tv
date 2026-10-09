package com.duanju.tv.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.duanju.tv.data.local.LocalStore
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.HistoryEntry
import com.duanju.tv.ui.category.CategoryScreen
import com.duanju.tv.ui.detail.DetailScreen
import com.duanju.tv.ui.home.HomeScreen
import com.duanju.tv.ui.library.DownloadsScreen
import com.duanju.tv.ui.library.LibraryScreen
import com.duanju.tv.ui.library.SettingsScreen
import com.duanju.tv.ui.player.PlayerScreen
import com.duanju.tv.ui.search.SearchScreen
import com.duanju.tv.ui.theme.scaled
import com.duanju.tv.ui.theme.scaledSp
import com.duanju.tv.ui.theme.tvColors
import com.duanju.tv.ui.theme.tvFocusable

/**
 * 路由表。
 *
 * 剧集对象不进导航参数：长剧含几百条分集地址，Bundle 上限 512KB 会直接
 * TransactionTooLargeException，只传 [DramaRegistry] 的 key。
 */
object Routes {
    const val HOME = "home"
    const val CATEGORY = "category"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"

    const val DETAIL = "detail/{key}"
    const val PLAYER = "player/{key}/{episode}/{position}"

    fun detail(key: String): String = "detail/${Uri.encode(key)}"

    fun player(key: String, episode: Int, position: Long): String =
        "player/${Uri.encode(key)}/$episode/$position"

    val TOP_LEVEL = setOf(HOME, CATEGORY, SEARCH, LIBRARY, DOWNLOADS, SETTINGS)
}

private data class NavTab(val route: String, val label: String)

private val Tabs = listOf(
    NavTab(Routes.HOME, "首页"),
    NavTab(Routes.CATEGORY, "分类"),
    NavTab(Routes.SEARCH, "搜索"),
    NavTab(Routes.LIBRARY, "我的"),
    NavTab(Routes.DOWNLOADS, "缓存"),
    NavTab(Routes.SETTINGS, "设置"),
)

@Composable
fun DuanjuApp(store: LocalStore) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBar = currentRoute in Routes.TOP_LEVEL

    val openDrama: (Drama) -> Unit = { d ->
        DramaRegistry.put(d)
        nav.navigate(Routes.detail(d.key))
    }
    val play: (Drama, Int, Long) -> Unit = { d, ep, pos ->
        DramaRegistry.put(d)
        nav.navigate(Routes.player(d.key, ep, pos))
    }
    val resumeHistory: (HistoryEntry) -> Unit = { h ->
        val d = h.drama
        if (d != null && d.playGroups.isNotEmpty()) {
            play(d, h.episodeIndex, h.positionMs)
        } else {
            // 只存了摘要、没有分集地址：回详情页重新拉取
            DramaRegistry.put(d ?: Drama.EMPTY)
            nav.navigate(Routes.detail(h.key))
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(tvColors().background)) {
        if (showBar) {
            TopBar(
                current = currentRoute ?: Routes.HOME,
                onSelect = { route ->
                    if (route != currentRoute) {
                        nav.navigate(route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            restoreState = true
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    store = store,
                    onDramaClick = openDrama,
                    onResumeClick = resumeHistory,
                )
            }
            composable(Routes.CATEGORY) {
                CategoryScreen(onDramaClick = openDrama)
            }
            composable(Routes.SEARCH) {
                SearchScreen(onDramaClick = openDrama)
            }
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onDramaClick = openDrama,
                    onResumeClick = resumeHistory,
                    onOpenDownloads = { nav.navigate(Routes.DOWNLOADS) },
                    onBackToHome = { nav.navigate(Routes.HOME) },
                )
            }
            composable(Routes.DOWNLOADS) {
                DownloadsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(
                Routes.DETAIL,
                arguments = listOf(navArgument("key") { type = NavType.StringType }),
            ) { entry ->
                val drama = DramaRegistry.get(entry.arguments?.getString("key"))
                if (drama == null) {
                    DeadEnd(message = "内容已失效，请返回首页重新选择", onBack = { nav.popBackStack() })
                } else {
                    DetailScreen(
                        drama = drama,
                        onBack = { nav.popBackStack() },
                        onPlay = { ep, pos -> play(drama, ep, pos) },
                    )
                }
            }
            composable(
                Routes.PLAYER,
                arguments = listOf(
                    navArgument("key") { type = NavType.StringType },
                    navArgument("episode") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("position") { type = NavType.LongType; defaultValue = 0L },
                ),
            ) { entry ->
                val args = entry.arguments
                val drama = DramaRegistry.get(args?.getString("key"))
                if (drama == null) {
                    DeadEnd(message = "播放对象已失效", onBack = { nav.popBackStack() })
                } else {
                    PlayerScreen(
                        drama = drama,
                        startEpisode = args?.getInt("episode") ?: 1,
                        startPosition = args?.getLong("position") ?: 0L,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}

/** 登记表被回收（进程重启）时的兜底页，避免用户卡在空白界面 */
@Composable
private fun DeadEnd(message: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(tvColors().background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = tvColors().onSurfaceVariant, fontSize = 17.scaledSp())
            Spacer(Modifier.height(18.dp))
            FocusableButton(text = "返回", onClick = onBack)
        }
    }
}

@Composable
private fun TopBar(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, end = 28.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "短剧TV",
            color = tvColors().primary,
            fontSize = 22.scaledSp(),
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.width(24.scaled()))
        Row(horizontalArrangement = Arrangement.spacedBy(6.scaled()), verticalAlignment = Alignment.CenterVertically) {
            Tabs.forEach { tab ->
                NavItem(label = tab.label, selected = tab.route == current, onClick = { onSelect(tab.route) })
            }
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .tvFocusable(shape = shape, focusedScale = 1.08f, borderWidth = 1.5.dp, elevation = 8.dp)
            .clip(shape)
            .background(if (selected) tvColors().primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) tvColors().onPrimary else tvColors().onSurface,
            fontSize = 17.scaledSp(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 通用可聚焦按钮，供错误态/确认态使用 */
@Composable
fun FocusableButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .size(width = 140.scaled(), height = 44.scaled())
            .tvFocusable(shape = shape)
            .clip(shape)
            .background(tvColors().surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = tvColors().onSurface, fontSize = 16.scaledSp(), fontWeight = FontWeight.SemiBold)
    }
}
