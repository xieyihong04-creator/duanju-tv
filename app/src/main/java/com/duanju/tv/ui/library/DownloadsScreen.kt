package com.duanju.tv.ui.library

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.duanju.tv.data.media.DljDownloadManager
import com.duanju.tv.data.media.MediaCache
import com.duanju.tv.ui.components.MessageBox
import com.duanju.tv.ui.components.TvButton
import com.duanju.tv.ui.theme.Palette
import com.duanju.tv.ui.theme.scaled
import com.duanju.tv.ui.theme.scaledSp
import com.duanju.tv.ui.theme.tvColors
import com.duanju.tv.ui.theme.tvFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 缓存管理页：展示下载任务列表，支持暂停/继续/取消/清理。
 */
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // flow 必须 remember：每次重组新建冷流会立刻重订阅，形成刷新死循环
    val downloadsFlow = remember { DljDownloadManager.downloadsFlow(context) }
    val downloads by downloadsFlow.collectAsState(initial = emptyList())

    // 清理缓存二次确认
    var confirmClear by remember { mutableStateOf(false) }
    var pendingClear by remember { mutableStateOf(false) }
    // 缓存体积要扫目录，只在任务数变化时重算
    val cacheBytes = produceState(0L, downloads.size, pendingClear) {
        value = withContext(Dispatchers.IO) { MediaCache.usedBytes(context) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tvColors().background),
    ) {
        // 顶栏
        DownloadsTopBar(
            onBack = onBack,
            cacheBytes = cacheBytes.value,
            onPauseAll = { DljDownloadManager.pause(context) },
            onResumeAll = { DljDownloadManager.resume(context) },
            onClearCache = {
                if (confirmClear) {
                    // 顺序不能反：先停下载并丢弃旧的 DownloadManager，再释放缓存目录
                    DljDownloadManager.reset()
                    MediaCache.clear(context)
                    confirmClear = false
                    pendingClear = !pendingClear
                } else {
                    confirmClear = true
                }
            },
            confirmClear = confirmClear,
        )

        Spacer(Modifier.height(8.scaled()))

        if (downloads.isEmpty()) {
            MessageBox(text = "暂无缓存任务\n去详情页点「缓存全剧」")
        } else {
            DownloadList(
                downloads = downloads,
                onCancel = { id -> DljDownloadManager.cancel(context, id) },
            )
        }
    }
}

/** 顶部工具栏 */
@Composable
private fun DownloadsTopBar(
    onBack: () -> Unit,
    cacheBytes: Long,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClearCache: () -> Unit,
    confirmClear: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 返回按钮
        Box(
            modifier = Modifier
                .tvFocusable(shape = RoundedCornerShape(50), focusedScale = 1.05f)
                .clip(RoundedCornerShape(50))
                .background(tvColors().surfaceVariant)
                .clickable(onClick = onBack)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text(
                text = "← 返回",
                color = tvColors().onSurface,
                fontSize = 14.scaledSp(),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = "缓存管理",
            color = tvColors().onSurface,
            fontSize = 24.scaledSp(),
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        // 缓存大小提示
        Text(
            text = "已用 ${DljDownloadManager.humanSize(cacheBytes)}",
            color = tvColors().onSurfaceVariant,
            fontSize = 13.scaledSp(),
        )
        Spacer(Modifier.width(12.dp))
        TvButton(text = "全部暂停", onClick = onPauseAll)
        Spacer(Modifier.width(8.dp))
        TvButton(text = "全部继续", onClick = onResumeAll, emphasized = true)
        Spacer(Modifier.width(8.dp))
        TvButton(
            text = if (confirmClear) "再点一次确认清理" else "清理缓存",
            onClick = onClearCache,
        )
    }
}

/** 下载任务列表 */
@Composable
private fun DownloadList(
    downloads: List<Download>,
    onCancel: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 28.dp,
            vertical = 4.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.scaled()),
    ) {
        items(downloads, key = { it.request.id }) { download ->
            DownloadItem(
                download = download,
                onCancel = { onCancel(download.request.id) },
            )
        }
    }
}

/** 单条下载任务 */
@Composable
private fun DownloadItem(
    download: Download,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val label = remember(download.request.id) {
        DljDownloadManager.labelOf(download.request.id)
    }
    val state = remember(download) { DljDownloadManager.stateOf(download) }
    val progress = remember(download) { DljDownloadManager.progressOf(download) }
    val bytes = remember(download) { DljDownloadManager.bytesOf(download) }
    val total = remember(download) { DljDownloadManager.sizeOf(download) }

    val dramaName = label?.dramaName ?: "未知剧集"
    val episodeInfo = label?.let { it.episodeTitle.ifBlank { "第${it.episodeIndex}集" } } ?: download.request.id

    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = shape, focusedScale = 1.01f, elevation = 4.dp, onFocus = { focused = it })
            .clip(shape)
            .background(tvColors().surface)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：剧名 + 集数 + 进度条 + 大小
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dramaName,
                    color = tvColors().onSurface,
                    fontSize = 16.scaledSp(),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = episodeInfo,
                    color = tvColors().onSurfaceVariant,
                    fontSize = 13.scaledSp(),
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                // 进度条
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = when (state) {
                        DljDownloadManager.DljState.DONE -> Palette.Success
                        DljDownloadManager.DljState.FAILED -> Palette.Danger
                        DljDownloadManager.DljState.PAUSED -> Palette.Gold
                        else -> Palette.Primary
                    },
                    trackColor = tvColors().surfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 状态文字
                    Text(
                        text = stateLabel(state),
                        color = stateColor(state),
                        fontSize = 12.scaledSp(),
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${progress}%",
                        color = tvColors().onSurfaceVariant,
                        fontSize = 12.scaledSp(),
                    )
                    Spacer(Modifier.width(12.dp))
                    // 已下/总大小
                    val sizeText = if (total > 0) {
                        "${DljDownloadManager.humanSize(bytes)} / ${DljDownloadManager.humanSize(total)}"
                    } else {
                        "${DljDownloadManager.humanSize(bytes)} / —"
                    }
                    Text(
                        text = sizeText,
                        color = tvColors().onSurfaceVariant,
                        fontSize = 12.scaledSp(),
                    )
                }
            }
            // 右侧：取消按钮
            if (focused) {
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Danger)
                        .tvFocusable(shape = RoundedCornerShape(50), focusedScale = 1.08f)
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("取消", color = Color.White, fontSize = 13.scaledSp())
                }
            }
        }
    }
}

private fun stateLabel(state: DljDownloadManager.DljState): String = when (state) {
    DljDownloadManager.DljState.QUEUED -> "排队中"
    DljDownloadManager.DljState.DOWNLOADING -> "下载中"
    DljDownloadManager.DljState.PAUSED -> "已暂停"
    DljDownloadManager.DljState.DONE -> "已完成"
    DljDownloadManager.DljState.FAILED -> "失败"
}

private fun stateColor(state: DljDownloadManager.DljState): Color = when (state) {
    DljDownloadManager.DljState.DONE -> Palette.Success
    DljDownloadManager.DljState.FAILED -> Palette.Danger
    DljDownloadManager.DljState.PAUSED -> Palette.Gold
    DljDownloadManager.DljState.DOWNLOADING -> Palette.Primary
    DljDownloadManager.DljState.QUEUED -> Color(0xFFA9A9B6)
}
