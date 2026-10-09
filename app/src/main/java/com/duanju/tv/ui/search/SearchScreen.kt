package com.duanju.tv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.duanju.tv.appGraph
import com.duanju.tv.data.model.Drama
import com.duanju.tv.ui.components.LoadingBox
import com.duanju.tv.ui.components.MessageBox
import com.duanju.tv.ui.components.PosterCard
import com.duanju.tv.ui.components.TvButton
import com.duanju.tv.ui.components.TvChip
import com.duanju.tv.ui.components.sourceLabel
import com.duanju.tv.ui.common.graphViewModel
import com.duanju.tv.ui.theme.scaled
import com.duanju.tv.ui.theme.scaledSp
import com.duanju.tv.ui.theme.tvColors

/** 短剧高频题材，让遥控器免打字就能搜 */
private val HotWords = listOf("总裁", "逆袭", "重生", "甜宠", "古装", "复仇", "萌宝", "战神", "马甲", "穿越")

/**
 * 搜索页。
 *
 * TV 上没有物理键盘，输入成本高，所以除输入框外还给出「热门题材」「最近搜索」
 * 两排直达按钮；回车键（含遥控器确定键）直接触发搜索。
 */
@Composable
fun SearchScreen(onDramaClick: (Drama) -> Unit) {
    val vm = graphViewModel("search") { graph -> SearchViewModel(graph) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val store = remember(context) { context.appGraph().store }
    val settings by store.settings.collectAsState()

    var input by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()

    val submit: () -> Unit = {
        val word = input.trim()
        if (word.isNotEmpty()) {
            store.rememberKeyword(word)
            vm.search(word)
            focusManager.clearFocus()
        }
    }

    val nearEnd by remember(gridState) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, state.items.size) {
        if (nearEnd && state.hasMore) vm.loadMore()
    }

    Column(modifier = Modifier.fillMaxSize().background(tvColors().background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 30.dp, end = 30.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shape = RoundedCornerShape(12.dp)
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .width(430.scaled())
                    .height(54.scaled())
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = {
                    Text("搜索短剧名称", fontSize = 16.scaledSp(), color = tvColors().onSurfaceVariant)
                },
                textStyle = TextStyle(color = tvColors().onSurface, fontSize = 17.scaledSp()),
                singleLine = true,
                shape = shape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = tvColors().surfaceVariant,
                    unfocusedContainerColor = tvColors().surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = tvColors().primary,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
            )
            Spacer(Modifier.width(14.dp))
            TvButton(text = "搜索", onClick = submit, emphasized = true)
            Spacer(Modifier.width(16.dp))
            Text(
                text = if (state.total > 0) "${state.total} 个结果" else "",
                color = tvColors().onSurfaceVariant,
                fontSize = 13.scaledSp(),
            )
        }

        if (state.items.isEmpty() && !state.loading) {
            WordRow(label = "热门题材", words = HotWords) { word ->
                input = word
                store.rememberKeyword(word)
                vm.search(word)
            }
            if (settings.recentKeywords.isNotEmpty()) {
                WordRow(label = "最近搜索", words = settings.recentKeywords) { word ->
                    input = word
                    vm.search(word)
                }
            }
        }

        when {
            state.error.isNotEmpty() && state.items.isEmpty() -> MessageBox(
                text = state.error,
                action = "重试",
                onAction = { vm.search(input.trim()) },
            )

            state.loading -> LoadingBox()

            state.isEmpty -> MessageBox(
                text = if (input.isBlank()) "输入关键词，或点上方热门题材" else "没有找到「$input」相关短剧",
            )

            else -> LazyVerticalGrid(
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
                if (state.hasMore || state.loadingMore) {
                    item(span = { GridItemSpan(6) }) {
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
                                    "方向键下移加载更多",
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

@Composable
private fun WordRow(label: String, words: List<String>, onWord: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.scaled()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Text(label, color = tvColors().onSurfaceVariant, fontSize = 13.scaledSp(), fontWeight = FontWeight.Medium)
        }
        items(words, key = { "${label}_$it" }) { word ->
            TvChip(text = word, selected = false, onClick = { onWord(word) })
        }
    }
}
