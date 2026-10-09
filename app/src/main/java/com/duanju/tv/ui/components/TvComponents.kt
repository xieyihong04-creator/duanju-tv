package com.duanju.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.duanju.tv.data.model.Drama
import com.duanju.tv.ui.theme.Palette
import com.duanju.tv.ui.theme.scaled
import com.duanju.tv.ui.theme.scaledSp
import com.duanju.tv.ui.theme.tvColors
import com.duanju.tv.ui.theme.tvFocusable

/** 海报卡片：TV 浏览界面的基本单元 */
@Composable
fun PosterCard(
    drama: Drama,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Int = 150,
    showBadge: Boolean = true,
    header: String = "",
) {
    val shape = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .width(width.scaled())
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
            if (drama.remarks.isNotBlank() || drama.episodeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Palette.Overlay)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = drama.remarks.ifBlank { "全${drama.episodeCount}集" },
                        color = Color.White,
                        fontSize = 12.scaledSp(),
                    )
                }
            }
            if (showBadge && drama.sourceId.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (focused) Palette.Primary else Palette.SoftOverlay)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = header.ifBlank { sourceLabel(drama.sourceId) },
                        color = Color.White,
                        fontSize = 11.scaledSp(),
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
}

fun sourceLabel(sourceId: String): String = when (sourceId) {
    "ffzy" -> "非凡"
    "bfzy" -> "暴风"
    else -> sourceId
}

/** 一行海报（带标题），首页信息流用 */
@Composable
fun PosterRow(
    title: String,
    items: List<Drama>,
    onDramaClick: (Drama) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 28.dp),
    header: String = "",
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 30.dp, bottom = 8.scaled()),
                color = tvColors().onSurface,
                fontSize = 20.scaledSp(),
                fontWeight = FontWeight.Bold,
            )
        }
        LazyRow(
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(12.scaled()),
        ) {
            rowItems(items, key = { it.key }) { d ->
                PosterCard(drama = d, onClick = { onDramaClick(d) }, header = header)
            }
        }
    }
}

/** 网格页（分类/搜索/收藏/历史），统一列数以适配 TV 屏宽 */
@Composable
fun PosterGrid(
    items: List<Drama>,
    onDramaClick: (Drama) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 6,
    showBadge: Boolean = true,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.scaled()),
        verticalArrangement = Arrangement.spacedBy(4.scaled()),
    ) {
        gridItems(items, key = { it.key }) { d ->
            PosterCard(drama = d, onClick = { onDramaClick(d) }, showBadge = showBadge)
        }
    }
}

/** 主操作按钮（详情页「播放」、弹窗「确定」等） */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String = "",
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    val colors = tvColors()
    Box(
        modifier = modifier
            .tvFocusable(shape = shape, focusedScale = if (enabled) 1.04f else 1f)
            .clip(shape)
            .background(if (emphasized) colors.primary else colors.surfaceVariant)
            .border(1.dp, if (emphasized) colors.primary else Color.White.copy(alpha = 0.14f), shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 26.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading.isNotBlank()) {
                Text(leading, color = if (emphasized) colors.onPrimary else colors.onSurface, fontSize = 15.scaledSp())
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                color = when {
                    !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                    emphasized -> colors.onPrimary
                    else -> colors.onSurface
                },
                fontSize = 16.scaledSp(),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 小号切换按钮组（分类、倍速、清晰度） */
@Composable
fun TvChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    val colors = tvColors()
    Box(
        modifier = modifier
            .tvFocusable(shape = shape, focusedScale = 1.05f, borderWidth = 1.5.dp, elevation = 6.dp)
            .clip(shape)
            .background(if (selected) colors.primary else colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            color = if (selected) colors.onPrimary else colors.onSurface,
            fontSize = 14.scaledSp(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = tvColors().primary, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(14.dp))
            Text("加载中…", color = tvColors().onSurfaceVariant, fontSize = 14.scaledSp())
        }
    }
}

@Composable
fun MessageBox(text: String, modifier: Modifier = Modifier, action: String = "", onAction: () -> Unit = {}) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                color = tvColors().onSurfaceVariant,
                fontSize = 16.scaledSp(),
                textAlign = TextAlign.Center,
            )
            if (action.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                TvButton(text = action, onClick = onAction)
            }
        }
    }
}

/** 底部渐变遮罩上的提示条 */
@Composable
fun HintBar(text: String, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = text.isNotBlank(), enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Scrim)))
                .padding(horizontal = 40.dp, vertical = 14.dp),
        ) {
            Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 13.scaledSp())
        }
    }
}
