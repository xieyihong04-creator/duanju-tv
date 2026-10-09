package com.duanju.tv.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.duanju.tv.data.local.LocalStore
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.FavoriteEntry
import com.duanju.tv.data.model.HistoryEntry
import com.duanju.tv.ui.components.PosterRow
import com.duanju.tv.ui.components.sourceLabel
import com.duanju.tv.ui.common.graphViewModel
import com.duanju.tv.ui.components.LoadingBox
import com.duanju.tv.ui.components.MessageBox
import com.duanju.tv.ui.theme.Palette
import com.duanju.tv.ui.theme.scaled
import com.duanju.tv.ui.theme.scaledSp
import com.duanju.tv.ui.theme.tvColors
import com.duanju.tv.ui.theme.tvFocusable
import kotlinx.coroutines.delay

/**
 * 首页：热推大图 + 继续观看 + 最热/最新/完结海报行。
 *
 * TV 上首屏必须「一眼有内容」，因此大图自动轮播，
 * 但用户一旦把焦点移进大图行就停下，避免选不中。
 */
@Composable
fun HomeScreen(
    store: LocalStore,
    onDramaClick: (Drama) -> Unit,
    onResumeClick: (HistoryEntry) -> Unit,
) {
    val vm = graphViewModel("home") { graph -> HomeViewModel(graph) }
    val state by vm.state.collectAsState()
    val history by store.history.collectAsState()
    val favorites by store.favorites.collectAsState()
    val settings by store.settings.collectAsState()

    // sourceId 变化（含设置读盘完成）时重新抓取，首屏只跑这一次
    LaunchedEffect(settings.sourceId) { vm.reloadForSource() }

    when {
        state.error.isNotEmpty() && state.hero.isEmpty() -> MessageBox(
            text = state.error,
            action = "重试",
            onAction = { vm.refresh(force = true) },
        )

        state.loading && state.hero.isEmpty() -> LoadingBox()

        else -> HomeRows(
            state = state,
            history = history,
            favorites = favorites,
            onDramaClick = onDramaClick,
            onResumeClick = onResumeClick,
            onRefresh = { vm.refresh(force = true) },
        )
    }
}

@Composable
private fun HomeRows(
    state: HomeUiState,
    history: List<HistoryEntry>,
    favorites: List<FavoriteEntry>,
    onDramaClick: (Drama) -> Unit,
    onResumeClick: (HistoryEntry) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(tvColors().background, tvColors().background, Color(0xFF0E0E16))
                )
            )
            .padding(bottom = 16.scaled()),
        verticalArrangement = Arrangement.spacedBy(10.scaled()),
    ) {
        HeroCarousel(items = state.hero, onDramaClick = onDramaClick)

        val resume = history.filter { it.percent < 95 }.take(12)
        if (resume.isNotEmpty()) {
            ContinueRow(items = resume, onResumeClick = onResumeClick)
        }
        PosterRow(title = "为你推荐", items = state.hot, onDramaClick = onDramaClick, header = "")
        if (favorites.isNotEmpty()) {
            PosterRow(
                title = "我的收藏",
                items = favorites.mapNotNull { it.drama }.take(30),
                onDramaClick = onDramaClick,
                header = "",
            )
        }
        PosterRow(title = "最近更新", items = state.latest, onDramaClick = onDramaClick, header = "")
        if (state.finished.isNotEmpty()) {
            PosterRow(title = "完结好剧", items = state.finished, onDramaClick = onDramaClick, header = "")
        }
        Row(modifier = Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "数据来源为第三方聚合接口，内容可能随时更新",
                color = tvColors().onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 12.scaledSp(),
            )
            Spacer(Modifier.width(12.dp))
            RefreshPill(onRefresh = onRefresh)
        }
    }
}

/** 大图轮播：横向 LazyRow，无焦点时每 6 秒前进一张 */
@Composable
private fun HeroCarousel(items: List<Drama>, onDramaClick: (Drama) -> Unit) {
    if (items.isEmpty()) return
    val listState = rememberLazyListState()
    var userActive by remember { mutableStateOf(false) }

    LaunchedEffect(userActive, items.size) {
        while (!userActive && items.size > 1) {
            delay(6000)
            if (userActive) break
            val next = (listState.firstVisibleItemIndex + 1) % items.size
            runCatching { listState.animateScrollToItem(next) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { userActive = it.hasFocus }
    ) {
        Text(
            text = "今日热推",
            modifier = Modifier.padding(start = 32.dp, top = 4.dp, bottom = 8.dp),
            color = tvColors().onSurface,
            fontSize = 21.scaledSp(),
            fontWeight = FontWeight.Bold,
        )
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.scaled()),
        ) {
            items(items, key = { it.key }) { drama ->
                HeroCard(drama = drama, onClick = { onDramaClick(drama) })
            }
        }
    }
}

@Composable
private fun HeroCard(drama: Drama, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .width(420.scaled())
            .height(210.scaled())
            .tvFocusable(shape = shape, focusedScale = 1.03f, elevation = 16.dp, borderWidth = 2.5.dp)
            .clip(shape)
            .background(tvColors().surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = drama.pic,
            contentDescription = drama.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Scrim, Color(0xEE000000))))
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 28.dp),
        ) {
            Column {
                Text(
                    text = drama.name,
                    color = Color.White,
                    fontSize = 21.scaledSp(),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val line = buildString {
                    append(drama.infoLine)
                    if (drama.score.isNotBlank()) append("  |  ${drama.score} 分")
                    if (drama.remarks.isNotBlank()) append("  |  ${drama.remarks}")
                }
                Text(
                    text = line,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.scaledSp(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.SoftOverlay)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = sourceLabel(drama.sourceId),
                color = Color.White,
                fontSize = 11.scaledSp(),
            )
        }
    }
}

/** 继续观看：横向卡片带进度条，TV 上最直接的回看入口 */
@Composable
private fun ContinueRow(items: List<HistoryEntry>, onResumeClick: (HistoryEntry) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "继续观看",
            modifier = Modifier.padding(start = 32.dp, bottom = 8.dp),
            color = tvColors().onSurface,
            fontSize = 21.scaledSp(),
            fontWeight = FontWeight.Bold,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.scaled()),
        ) {
            items(items, key = { it.key }) { entry ->
                ContinueCard(entry = entry, onClick = { onResumeClick(entry) })
            }
        }
    }
}

@Composable
private fun ContinueCard(entry: HistoryEntry, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val colors = tvColors()
    var focused by remember { mutableStateOf(false) }
    val titleColor by animateColorAsState(if (focused) colors.primary else colors.onSurface, label = "contTitle")
    Column(
        modifier = Modifier
            .width(210.scaled())
            .tvFocusable(shape = shape, onFocus = { focused = it })
            .clip(shape)
            .background(colors.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.scaled())
                .background(colors.background),
        ) {
            AsyncImage(
                model = entry.pic,
                contentDescription = entry.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(colors.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(entry.percent.coerceIn(0, 100) / 100f)
                        .height(4.dp)
                        .background(colors.primary),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = entry.name,
                color = titleColor,
                fontSize = 15.scaledSp(),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "看到 ${entry.episodeTitle.ifBlank { "第${entry.episodeIndex}集" }}",
                color = colors.onSurfaceVariant,
                fontSize = 12.scaledSp(),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RefreshPill(onRefresh: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .tvFocusable(shape = shape, focusedScale = 1.06f, borderWidth = 1.5.dp, elevation = 6.dp)
            .clip(shape)
            .background(tvColors().surfaceVariant)
            .clickable(onClick = onRefresh)
            .padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Text("换一批", color = tvColors().onSurface, fontSize = 12.scaledSp())
    }
}
