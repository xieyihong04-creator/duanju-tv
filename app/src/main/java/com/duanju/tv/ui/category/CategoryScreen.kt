package com.duanju.tv.ui.category

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.duanju.tv.data.model.Drama
import com.duanju.tv.ui.components.LoadingBox
import com.duanju.tv.ui.components.MessageBox
import com.duanju.tv.ui.components.PosterCard
import com.duanju.tv.ui.components.TvChip
import com.duanju.tv.ui.common.graphViewModel
import com.duanju.tv.ui.components.sourceLabel
import com.duanju.tv.ui.theme.scaled
import com.duanju.tv.ui.theme.scaledSp
import com.duanju.tv.ui.theme.tvColors

/**
 * 分类页：顶部标签筛选 + 无限滚动海报网格。
 *
 * CMS 只有「短剧」这一个有效分类，真正的题材维度在 vod_class（逗号分隔的 tag）里，
 * 所以这里把 tag 提取成筛选条，比按站点分类更贴合短剧的实际浏览习惯。
 */
@Composable
fun CategoryScreen(onDramaClick: (Drama) -> Unit) {
    val vm = graphViewModel("category") { graph -> CategoryViewModel(graph) }
    val state by vm.state.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) { vm.load(reset = true) }

    // 滚到接近底部就翻页
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= gridState.layoutInfo.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, state.items.size) {
        if (nearEnd && state.hasMore && !state.loading && !state.loadingMore) vm.loadMore()
    }

    val tags = remember(state.items) { popularTags(state.items, vm.tagUniverse()) }

    Column(modifier = Modifier.fillMaxSize().background(tvColors().background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "短剧分类",
                color = tvColors().onSurface,
                fontSize = 22.scaledSp(),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = if (state.total > 0) "共 ${state.total} 部" else "",
                color = tvColors().onSurfaceVariant,
                fontSize = 13.scaledSp(),
            )
        }

        if (tags.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 26.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.scaled()),
            ) {
                item {
                    TvChip(text = "全部", selected = vm.selectedTag() == null, onClick = { vm.selectTag(null) })
                }
                rowItems(tags, key = { "tag_" + it.value }) { tag ->
                    TvChip(
                        text = tag.label(),
                        selected = vm.selectedTag() == tag.value,
                        onClick = { vm.selectTag(tag.value) },
                    )
                }
            }
        }

        when {
            state.error.isNotEmpty() && state.items.isEmpty() -> MessageBox(
                text = state.error,
                action = "重试",
                onAction = { vm.load(reset = true) },
            )

            state.loading && state.items.isEmpty() -> LoadingBox()

            state.isEmpty -> MessageBox(text = "该标签下暂无剧集")

            else -> Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(6),
                    contentPadding = PaddingValues(horizontal = 26.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.scaled()),
                    verticalArrangement = Arrangement.spacedBy(4.scaled()),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    gridItems(state.items, key = { it.key }) { drama ->
                        PosterCard(
                            drama = drama,
                            onClick = { onDramaClick(drama) },
                            header = sourceLabel(drama.sourceId),
                        )
                    }
                    if (state.loadingMore || state.hasMore) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(6) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.loadingMore) {
                                    CircularProgressIndicator(
                                        color = tvColors().primary,
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.width(30.dp),
                                    )
                                } else {
                                    Text(
                                        text = "按遥控器方向键下移继续加载",
                                        color = tvColors().onSurfaceVariant.copy(alpha = 0.7f),
                                        fontSize = 12.scaledSp(),
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class TagCount(val value: String, val count: Int) {
    fun label(): String = if (count > 0) "$value · $count" else value
}

/** 标签按出现次数取前 N，冷门标签不占焦点位 */
private fun popularTags(items: List<Drama>, universe: List<Pair<String, Int>>): List<TagCount> {
    val source = if (universe.isNotEmpty()) universe else countTags(items)
    return source.filter { it.second >= 3 }.take(14).map { TagCount(it.first, it.second) }
}

private fun countTags(items: List<Drama>): List<Pair<String, Int>> {
    val map = LinkedHashMap<String, Int>()
    items.forEach { d ->
        d.tag.split(",", "，", "、", "/").map { it.trim() }.filter { it.length in 2..8 }.forEach {
            map[it] = (map[it] ?: 0) + 1
        }
    }
    return map.entries.sortedByDescending { it.value }.map { it.key to it.value }
}
