package com.duanju.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.duanju.tv.appGraph
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.FavoriteEntry
import com.duanju.tv.data.model.HistoryEntry
import com.duanju.tv.ui.components.MessageBox
import com.duanju.tv.ui.components.TvButton
import com.duanju.tv.ui.components.TvChip
import com.duanju.tv.ui.theme.Palette
import com.duanju.tv.ui.theme.scaled
import com.duanju.tv.ui.theme.scaledSp
import com.duanju.tv.ui.theme.tvColors
import com.duanju.tv.ui.theme.tvFocusable

/**
 * 资料库主页：收藏 + 历史两个标签页，右上角缓存管理入口。
 */
@Composable
fun LibraryScreen(
    onDramaClick: (Drama) -> Unit,
    onBackToHome: () -> Unit,
    onOpenDownloads: () -> Unit,
    onResumeClick: (HistoryEntry) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { context.appGraph().store }
    val favorites by store.favorites.collectAsStateWithLifecycle()
    val history by store.history.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(LibTab.Favorites) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tvColors().background),
    ) {
        // 顶部栏：标签切换 + 右侧操作
        LibraryTopBar(
            selectedTab = tab,
            onTabChange = { tab = it },
            onOpenDownloads = onOpenDownloads,
            onClearHistory = if (tab == LibTab.History && history.isNotEmpty()) {
                { store.clearHistory() }
            } else null,
        )

        Spacer(Modifier.height(8.scaled()))

        // 内容区
        when (tab) {
            LibTab.Favorites -> FavoritesContent(
                entries = favorites,
                onDramaClick = onDramaClick,
                onRemove = { store.removeFavorite(it) },
            )
            LibTab.History -> HistoryContent(
                entries = history,
                onDramaClick = onDramaClick,
                onResumeClick = onResumeClick,
                onRemove = { store.removeHistory(it) },
            )
        }

        // 底部回首页按钮
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            TvButton(text = "返回首页", onClick = onBackToHome, leading = "←")
        }
    }
}

private enum class LibTab { Favorites, History }

/** 顶栏：标签 + 操作按钮 */
@Composable
private fun LibraryTopBar(
    selectedTab: LibTab,
    onTabChange: (LibTab) -> Unit,
    onOpenDownloads: () -> Unit,
    onClearHistory: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "资料库",
            color = tvColors().onSurface,
            fontSize = 24.scaledSp(),
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(24.dp))
        TvChip(
            text = "我的收藏",
            selected = selectedTab == LibTab.Favorites,
            onClick = { onTabChange(LibTab.Favorites) },
        )
        Spacer(Modifier.width(10.dp))
        TvChip(
            text = "最近观看",
            selected = selectedTab == LibTab.History,
            onClick = { onTabChange(LibTab.History) },
        )
        Spacer(Modifier.weight(1f))
        if (onClearHistory != null) {
            TvButton(text = "清空历史", onClick = onClearHistory)
            Spacer(Modifier.width(10.dp))
        }
        TvButton(text = "缓存管理", onClick = onOpenDownloads, leading = "⬇")
    }
}

/** 收藏列表 */
@Composable
private fun FavoritesContent(
    entries: List<FavoriteEntry>,
    onDramaClick: (Drama) -> Unit,
    onRemove: (key: String) -> Unit,
) {
    if (entries.isEmpty()) {
        MessageBox(text = "还没有收藏的短剧\n去首页或详情页点击 ♡ 加入收藏")
        return
    }
    // 将 FavoriteEntry 转为 Drama；drama 为 null 时用 entry 字段构造最小 Drama
    val dramas = entries.map { entry ->
        entry.drama ?: Drama(
            id = entry.dramaId,
            sourceId = entry.sourceId,
            name = entry.name,
            pic = entry.pic,
            type = "",
            typeId = 0,
            remarks = entry.remarks,
            year = "",
            area = "",
            director = "",
            actors = "",
            blurb = "",
            detail = "",
            tag = "",
            score = "",
            updated = "",
            playGroups = emptyList(),
            backendId = entry.backendId,
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.scaled()),
        verticalArrangement = Arrangement.spacedBy(4.scaled()),
    ) {
        items(dramas, key = { it.key }) { drama ->
            FavoritePosterItem(
                drama = drama,
                onClick = { onDramaClick(drama) },
                onRemove = { onRemove(drama.key) },
            )
        }
    }
}

/** 单个收藏海报卡片，带删除按钮 */
@Composable
private fun FavoritePosterItem(
    drama: Drama,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.width(150.scaled()),
    ) {
        Box {
            Column(
                modifier = Modifier
                    .tvFocusable(shape = shape, onFocus = { focused = it })
                    .clip(shape)
                    .clickable(onClick = onClick),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(tvColors().surfaceVariant),
                ) {
                    AsyncImage(
                        model = drama.pic,
                        contentDescription = drama.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // 无播放源标记
                    if (drama.playGroups.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Palette.Danger)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text("无源", color = Color.White, fontSize = 11.scaledSp())
                        }
                    }
                    // 底部备注
                    if (drama.remarks.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Palette.Overlay)
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = drama.remarks,
                                color = Color.White,
                                fontSize = 12.scaledSp(),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.scaled()))
                Text(
                    text = drama.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    color = if (focused) tvColors().primary else tvColors().onSurface,
                    fontSize = 15.scaledSp(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.scaled()))
            }
            // 聚焦时显示删除按钮
            if (focused) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Danger)
                        .tvFocusable(shape = RoundedCornerShape(50), focusedScale = 1.1f)
                        .clickable { onRemove() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("✕", color = Color.White, fontSize = 12.scaledSp())
                }
            }
        }
    }
}

/** 历史列表 */
@Composable
private fun HistoryContent(
    entries: List<HistoryEntry>,
    onDramaClick: (Drama) -> Unit,
    onResumeClick: (HistoryEntry) -> Unit,
    onRemove: (key: String) -> Unit,
) {
    if (entries.isEmpty()) {
        MessageBox(text = "还没有观看记录\n去首页挑选喜欢的短剧吧")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.scaled()),
        verticalArrangement = Arrangement.spacedBy(4.scaled()),
    ) {
        items(entries, key = { it.key }) { entry ->
            HistoryPosterItem(
                entry = entry,
                onClick = {
                    val drama = entry.drama
                    if (drama != null) {
                        onResumeClick(entry)
                    } else {
                        // 无完整 Drama 信息，走普通点击
                        val minimal = Drama(
                            id = entry.dramaId,
                            sourceId = entry.sourceId,
                            name = entry.name,
                            pic = entry.pic,
                            type = "",
                            typeId = 0,
                            remarks = entry.remarks,
                            year = "",
                            area = "",
                            director = "",
                            actors = "",
                            blurb = "",
                            detail = "",
                            tag = "",
                            score = "",
                            updated = "",
                            playGroups = emptyList(),
                            backendId = entry.backendId,
                        )
                        onDramaClick(minimal)
                    }
                },
                onRemove = { onRemove(entry.key) },
            )
        }
    }
}

/** 历史海报卡片：带进度条和集数信息 */
@Composable
private fun HistoryPosterItem(
    entry: HistoryEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }

    Column(modifier = Modifier.width(150.scaled())) {
        Box {
            Column(
                modifier = Modifier
                    .tvFocusable(shape = shape, onFocus = { focused = it })
                    .clip(shape)
                    .clickable(onClick = onClick),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .background(tvColors().surfaceVariant),
                ) {
                    AsyncImage(
                        model = entry.pic,
                        contentDescription = entry.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // 右上角集数标记
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Palette.Overlay)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (entry.episodeTitle.isNotBlank()) entry.episodeTitle
                            else "第${entry.episodeIndex}集",
                            color = Color.White,
                            fontSize = 11.scaledSp(),
                        )
                    }
                    // 底部进度条
                    if (entry.percent > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(3.dp),
                        ) {
                            LinearProgressIndicator(
                                progress = { entry.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Palette.Primary,
                                trackColor = Color.White.copy(alpha = 0.2f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.scaled()))
                Text(
                    text = entry.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    color = if (focused) tvColors().primary else tvColors().onSurface,
                    fontSize = 15.scaledSp(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 进度文字
                Text(
                    text = buildString {
                        append("看到")
                        append(if (entry.episodeTitle.isNotBlank()) entry.episodeTitle else "第${entry.episodeIndex}集")
                        if (entry.percent > 0) append(" · ${entry.percent}%")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                    color = tvColors().onSurfaceVariant,
                    fontSize = 12.scaledSp(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.scaled()))
            }
            // 聚焦时显示删除按钮
            if (focused) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Danger)
                        .tvFocusable(shape = RoundedCornerShape(50), focusedScale = 1.1f)
                        .clickable { onRemove() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("✕", color = Color.White, fontSize = 12.scaledSp())
                }
            }
        }
    }
}
