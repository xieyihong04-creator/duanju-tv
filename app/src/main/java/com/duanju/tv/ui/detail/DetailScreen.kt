package com.duanju.tv.ui.detail

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.duanju.tv.appGraph
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.Episode
import com.duanju.tv.ui.components.TvButton
import com.duanju.tv.ui.components.TvChip
import com.duanju.tv.ui.components.sourceLabel
import com.duanju.tv.ui.common.graphViewModel
import com.duanju.tv.ui.theme.Palette
import com.duanju.tv.ui.theme.scaled
import com.duanju.tv.ui.theme.scaledSp
import com.duanju.tv.ui.theme.tvColors
import com.duanju.tv.ui.theme.tvFocusable

/**
 * 详情页。
 *
 * 海报做背景（设置里可关），左侧简介与操作，右侧分集网格。
 * 分集超过 [DetailState.PAGE_SIZE] 集时自动分页，
 * 否则几百集的短剧一屏根本划不完，遥控器体验很差。
 */
@Composable
fun DetailScreen(
    drama: Drama,
    onBack: () -> Unit,
    onPlay: (episodeIndex: Int, positionMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph() }
    val vm = graphViewModel("detail_${drama.key}") { g -> DetailViewModel(g) }
    val state by vm.state.collectAsState()
    val settings by graph.store.settings.collectAsState()

    LaunchedEffect(drama.key) { vm.bind(drama) }

    val episodes = state.episodes
    val total = episodes.size
    val window = state.window()

    val startEpisode: (Episode) -> Unit = { ep ->
        val resume = state.progress
        val pos = if (settings.resumePlayback && resume != null && state.key == drama.key) resume.positionMs else 0L
        onPlay(ep.index, if (ep.index == (resume?.episodeIndex ?: -1)) pos else 0L)
    }

    Box(modifier = Modifier.fillMaxSize().background(tvColors().background)) {
        if (settings.immersiveDetail && drama.pic.isNotBlank()) {
            AsyncImage(
                model = drama.pic,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xF20B0B0F), Color(0xD90B0B0F), Color(0x660B0B0F))
                        )
                    ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 30.dp, end = 24.dp, top = 8.dp, bottom = 10.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(360.scaled())
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.scaled()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackChip(onBack = onBack)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = sourceLabel(drama.sourceId),
                        color = tvColors().primary,
                        fontSize = 12.scaledSp(),
                        fontWeight = FontWeight.Bold,
                    )
                }

                Text(
                    text = drama.name,
                    color = tvColors().onSurface,
                    fontSize = 27.scaledSp(),
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                val meta = buildList {
                    if (drama.year.isNotBlank()) add(drama.year)
                    if (drama.area.isNotBlank()) add(drama.area)
                    if (drama.type.isNotBlank()) add(drama.type)
                    if (drama.score.isNotBlank()) add("${drama.score}分")
                    if (total > 0) add("全${total}集")
                }
                Text(
                    text = meta.joinToString("  ·  "),
                    color = tvColors().onSurfaceVariant,
                    fontSize = 13.scaledSp(),
                )

                if (drama.tag.isNotBlank()) {
                    Text(
                        text = drama.tag.replace(",", " / "),
                        color = tvColors().secondary,
                        fontSize = 12.scaledSp(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.scaled())) {
                    val resume = state.progress
                    TvButton(
                        text = if (resume != null && resume.percent in 1..94) "继续播放" else "开始播放",
                        emphasized = true,
                        leading = "▶",
                        onClick = {
                            val target = if (resume != null && resume.percent in 1..94) {
                                episodes.getOrNull((resume.episodeIndex - 1).coerceAtLeast(0))
                            } else {
                                episodes.firstOrNull()
                            }
                            target?.let { startEpisode(it) }
                        },
                        enabled = episodes.isNotEmpty(),
                    )
                    TvButton(
                        text = if (state.favorite) "取消收藏" else "收藏",
                        leading = if (state.favorite) "★" else "☆",
                        emphasized = state.favorite,
                        onClick = { vm.toggleFavorite(drama) },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.scaled()), verticalAlignment = Alignment.CenterVertically) {
                    TvButton(
                        text = if (state.caching) "缓存中…" else "缓存全剧",
                        onClick = {
                            if (state.caching) vm.cancelCache(context, drama) else vm.cacheAll(context, drama)
                        },
                        enabled = episodes.isNotEmpty(),
                    )
                    if (state.cacheMessage.isNotBlank()) {
                        Text(
                            text = state.cacheMessage,
                            color = tvColors().onSurfaceVariant,
                            fontSize = 12.scaledSp(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                val last = state.progress
                if (last != null) {
                    Text(
                        text = "上次看到 ${last.episodeTitle.ifBlank { "第${last.episodeIndex}集" }} · ${last.percent}%",
                        color = tvColors().onSurfaceVariant,
                        fontSize = 12.scaledSp(),
                    )
                }

                val blur = drama.detail.ifBlank { drama.blurb }
                if (blur.isNotBlank()) {
                    Text(
                        text = "剧情简介",
                        color = tvColors().onSurface,
                        fontSize = 15.scaledSp(),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = blur,
                        color = tvColors().onSurfaceVariant,
                        fontSize = 13.scaledSp(),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (drama.actors.isNotBlank() || drama.director.isNotBlank()) {
                    Text(
                        text = buildString {
                            if (drama.director.isNotBlank()) append("导演：${drama.director}\n")
                            if (drama.actors.isNotBlank()) append("主演：${drama.actors}")
                        }.trim(),
                        color = tvColors().onSurfaceVariant,
                        fontSize = 12.scaledSp(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(22.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize(),
            ) {
                if (drama.playGroups.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                        Text("线路", color = tvColors().onSurfaceVariant, fontSize = 13.scaledSp())
                        Spacer(Modifier.width(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.scaled())) {
                            rowItems(drama.playGroups, key = { "line_" + it.name }) { g ->
                                TvChip(
                                    text = "${g.name} · ${g.episodes.size}集",
                                    selected = g.name == state.lineName,
                                    onClick = { vm.selectLine(g.name, drama) },
                                )
                            }
                        }
                    }
                }
                if (total > DetailState.PAGE_SIZE) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.scaled()),
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        rowItems((1..state.pageCount(total)).toList(), key = { "p$it" }) { page ->
                            TvChip(
                                text = "${(page - 1) * DetailState.PAGE_SIZE + 1}-${minOf(page * DetailState.PAGE_SIZE, total)}",
                                selected = page == state.page,
                                onClick = { vm.selectPage(page, total) },
                            )
                        }
                    }
                }

                if (episodes.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = when {
                                state.loadingEpisodes -> "正在加载分集…"
                                state.episodeError.isNotBlank() -> "分集加载失败：${state.episodeError}"
                                else -> "这部剧没有可播放的分集，请换一条线路或返回重新选择"
                            },
                            color = tvColors().onSurfaceVariant,
                            fontSize = 15.scaledSp(),
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 96.scaled()),
                        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.scaled()),
                        verticalArrangement = Arrangement.spacedBy(9.scaled()),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(window, key = { it.index }) { ep ->
                            EpisodeCell(ep = ep, playing = state.progress?.episodeIndex == ep.index) {
                                startEpisode(ep)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackChip(onBack: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .tvFocusable(shape = shape, focusedScale = 1.06f, borderWidth = 1.5.dp, elevation = 6.dp)
            .clip(shape)
            .background(tvColors().surfaceVariant)
            .clickable(onClick = onBack)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text("← 返回", color = tvColors().onSurface, fontSize = 14.scaledSp())
    }
}

/** 选集按钮：短剧集数多，用紧凑方块而非标题文本 */
@Composable
private fun EpisodeCell(ep: Episode, playing: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }
    val colors = tvColors()
    Box(
        modifier = Modifier
            .height(46.scaled())
            .tvFocusable(
                shape = shape,
                focusedScale = 1.08f,
                borderWidth = 2.dp,
                elevation = 8.dp,
                onFocus = { focused = it },
            )
            .clip(shape)
            .background(
                when {
                    focused -> colors.primary
                    playing -> colors.primary.copy(alpha = 0.28f)
                    else -> colors.surfaceVariant
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${ep.index}",
            color = when {
                focused || playing -> colors.onPrimary
                else -> colors.onSurface
            },
            fontSize = 16.scaledSp(),
            fontWeight = if (playing || focused) FontWeight.Bold else FontWeight.Normal,
        )
        if (!ep.isDirectStream) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .width(6.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Gold),
            )
        }
    }
}
