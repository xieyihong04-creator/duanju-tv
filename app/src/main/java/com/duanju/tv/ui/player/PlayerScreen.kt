package com.duanju.tv.ui.player

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.duanju.tv.AppGraph
import com.duanju.tv.appGraph
import com.duanju.tv.data.local.LocalStore
import com.duanju.tv.data.media.PlayerFactory
import com.duanju.tv.data.model.Drama
import com.duanju.tv.data.model.Episode
import com.duanju.tv.data.model.PlayGroup
import com.duanju.tv.data.model.Resolved
import com.duanju.tv.ui.components.TvButton
import com.duanju.tv.ui.components.TvChip
import com.duanju.tv.ui.theme.Palette
import com.duanju.tv.ui.theme.scaled
import com.duanju.tv.ui.theme.scaledSp
import com.duanju.tv.ui.theme.tvColors
import com.duanju.tv.ui.theme.tvFocusable
import kotlinx.coroutines.delay

/** 播放页所处的阶段 */
private enum class Phase { LOADING, PLAYING, ENDED, ERROR }

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * 播放页。
 *
 * 播放器由本页持有（不做 MediaSessionService）：短剧是全屏单视频场景，
 * 没有锁屏/通知控制中心的硬需求，省掉服务就少一大块无法验证的异步代码。
 *
 * 行为：地址现场解析（share 页带签名）→ 失败自动换线路 → 续播 →
 * 播完接下一集 → 跳过片头/片尾 → 倍速 / 选集 / 线路 / 收藏。
 */
@Composable
fun PlayerScreen(
    drama: Drama,
    startEpisode: Int,
    startPosition: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember(context) { context.appGraph() }
    val settings by graph.store.settings.collectAsState()

    val groups = remember(drama) { drama.playGroups.sortedByDescending { it.episodes.size } }
    var lineIdx by remember { mutableIntStateOf(0) }
    var epIndex by remember { mutableIntStateOf(startEpisode.coerceAtLeast(1)) }
    var phase by remember { mutableStateOf(Phase.LOADING) }
    var message by remember { mutableStateOf("") }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(settings.defaultSpeed) }
    var showPanel by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    val holder = remember { PlayerHolder() }
    val version = holder.version.intValue

    /* 回调与 effect 里都通过 lambda 读状态，避免捕获到组合时的旧值 */
    val currentGroup: () -> PlayGroup = { groups.getOrNull(lineIdx) ?: PlayGroup("", emptyList()) }
    val currentEpisode: () -> Episode? = { currentGroup().episodes.getOrNull(epIndex - 1) }
    val group = currentGroup()
    val episode = currentEpisode()

    // 解析地址并起播：换集、换线路、重试都走这里
    LaunchedEffect(drama.key, epIndex, lineIdx, retry) {
        val ep = currentEpisode()
        if (ep == null) {
            phase = Phase.ERROR
            message = "这一集不存在"
            return@LaunchedEffect
        }
        phase = Phase.LOADING
        message = "正在解析播放地址…"
        // 换集/换线路前先落盘上一集进度，否则那一集的历史会被跳过
        holder.saveCurrent(graph.store, drama)
        when (val resolved = graph.repository.resolveEpisode(ep)) {
            is Resolved.Direct -> {
                holder.start(graph, resolved, "${drama.name} · ${ep.title}", speed, ep)
                val resume = if (epIndex == startEpisode && settings.resumePlayback) startPosition else 0L
                holder.seekFor(resume, settings.skipIntroSeconds)
                phase = Phase.PLAYING
                message = ""
            }
            is Resolved.Failed -> {
                if (lineIdx + 1 < groups.size) {
                    message = "线路「${currentGroup().name}」解析失败，正在切换…"
                    lineIdx += 1
                } else {
                    phase = Phase.ERROR
                    message = resolved.message
                }
            }
        }
    }

    // 进度轮询 + 跳过片尾（CMS 不给片头片尾标记，只能按秒数处理）
    LaunchedEffect(phase, version) {
        while (phase == Phase.PLAYING) {
            val p = holder.player
            if (p != null) {
                positionMs = p.currentPosition.coerceAtLeast(0)
                if (p.duration > 0) {
                    durationMs = p.duration
                    holder.boundDuration = p.duration
                }
                val skipEnd = settings.skipEndingSeconds * 1000L
                if (skipEnd > 0 && durationMs > skipEnd && durationMs - positionMs <= skipEnd) {
                    val g = currentGroup()
                    if (settings.autoPlayNext && epIndex < g.episodes.size) epIndex += 1 else phase = Phase.ENDED
                    break
                }
            }
            delay(500)
        }
    }

    // 播放结束与出错：结束接下一集，出错换线路
    DisposableEffect(version) {
        val p = holder.player
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val g = currentGroup()
                    if (settings.autoPlayNext && epIndex < g.episodes.size) epIndex += 1 else phase = Phase.ENDED
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (lineIdx + 1 < groups.size) {
                    lineIdx += 1
                    message = "播放失败，已切换到「${currentGroup().name}」"
                } else {
                    phase = Phase.ERROR
                    message = error.message ?: "播放失败"
                }
            }
        }
        p?.addListener(listener)
        onDispose { p?.removeListener(listener) }
    }

    // 退到后台暂停、回到前台继续
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> holder.player?.pause()
                Lifecycle.Event.ON_RESUME -> if (phase == Phase.PLAYING) holder.player?.playWhenReady = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 离开页面保存进度并释放播放器
    DisposableEffect(Unit) {
        onDispose {
            holder.saveCurrent(graph.store, drama)
            holder.release()
        }
    }

    LaunchedEffect(speed) { holder.player?.setPlaybackSpeed(speed) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setKeepContentOnPlayerReset(true)
                    setControllerAutoShow(true)
                    holder.view = this
                    player = holder.player
                }
            },
            update = { view ->
                view.player = holder.player
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                view.player = null
                holder.view = null
            },
        )

        if (phase == Phase.LOADING) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = tvColors().primary, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(message, color = Color.White.copy(alpha = 0.8f), fontSize = 14.scaledSp())
                }
            }
        }

        if (phase == Phase.ERROR || phase == Phase.ENDED) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Palette.Scrim)
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (phase == Phase.ENDED) "已播完全部剧集" else "无法播放",
                    color = Color.White,
                    fontSize = 21.scaledSp(),
                    fontWeight = FontWeight.Bold,
                )
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.scaledSp(),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.scaled())) {
                    if (phase == Phase.ERROR) {
                        TvButton(
                            text = "重试",
                            emphasized = true,
                            onClick = {
                                phase = Phase.LOADING
                                retry += 1
                            },
                        )
                    } else {
                        TvButton(text = "重播本集", emphasized = true, onClick = { holder.restart() })
                    }
                    if (groups.size > 1 && phase == Phase.ERROR) {
                        TvButton(
                            text = "切换到「${groups[(lineIdx + 1) % groups.size].name}」",
                            onClick = { lineIdx = (lineIdx + 1) % groups.size },
                        )
                    }
                    TvButton(text = "返回详情", onClick = onBack)
                }
            }
        }

        if (phase != Phase.ERROR && phase != Phase.ENDED) {
            TopInfoBar(
                drama = drama,
                group = group,
                episode = episode,
                positionMs = positionMs,
                durationMs = durationMs,
                panelOpen = showPanel,
                onTogglePanel = { showPanel = !showPanel },
            )
        }

        if (showPanel) {
            ControlPanel(
                modifier = Modifier.align(Alignment.BottomStart),
                drama = drama,
                store = graph.store,
                group = group,
                groups = groups,
                epIndex = epIndex,
                lineIdx = lineIdx,
                speed = speed,
                onBack = onBack,
                onSelectEpisode = { epIndex = it },
                onSelectLine = { lineIdx = it },
                onSpeed = { speed = it },
                onClose = { showPanel = false },
            )
        }
    }
}

@Composable
private fun TopInfoBar(
    drama: Drama,
    group: PlayGroup,
    episode: Episode?,
    positionMs: Long,
    durationMs: Long,
    panelOpen: Boolean,
    onTogglePanel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Palette.Scrim, Color.Transparent)))
                .padding(start = 26.dp, end = 26.dp, top = 8.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = drama.name,
                    color = Color.White,
                    fontSize = 17.scaledSp(),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(340.scaled()),
                )
                Spacer(Modifier.width(12.dp))
                if (episode != null) {
                    Text(
                        text = "第${episode.index}集 / 共${group.episodes.size}集",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 13.scaledSp(),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(group.name, color = tvColors().primary, fontSize = 12.scaledSp())
                Spacer(Modifier.width(12.dp))
                Text(
                    text = formatTime(positionMs) + " / " + formatTime(durationMs),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.scaledSp(),
                )
                Spacer(Modifier.weight(1f))
                ToggleButton(open = panelOpen, onClick = onTogglePanel)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ToggleButton(open: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .tvFocusable(shape = shape, focusedScale = 1.06f, borderWidth = 1.5.dp, elevation = 6.dp)
            .clip(shape)
            .background(if (open) tvColors().primary else Color.White.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text = if (open) "收起面板" else "选集 / 线路 / 倍速",
            color = if (open) tvColors().onPrimary else Color.White,
            fontSize = 13.scaledSp(),
        )
    }
}

/** 控制面板：占屏幕下半部分，遥控器可直接下行进入 */
@Composable
private fun ControlPanel(
    modifier: Modifier = Modifier,
    drama: Drama,
    store: LocalStore,
    group: PlayGroup,
    groups: List<PlayGroup>,
    epIndex: Int,
    lineIdx: Int,
    speed: Float,
    onBack: () -> Unit,
    onSelectEpisode: (Int) -> Unit,
    onSelectLine: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
    onClose: () -> Unit,
) {
    var fav by remember { mutableStateOf(store.isFavorite(drama)) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
            .padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.scaled()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.scaled())) {
            TvButton(text = "← 返回", onClick = onBack)
            TvButton(
                text = if (fav) "已收藏" else "收藏",
                leading = if (fav) "★" else "☆",
                emphasized = fav,
                onClick = { fav = store.toggleFavorite(drama) },
            )
            Spacer(Modifier.weight(1f))
            TvButton(text = "关闭面板", onClick = onClose)
        }

        if (groups.size > 1) {
            ScrollRow(label = "播放线路") {
                items(groups, key = { "line_" + it.name }) { g ->
                    TvChip(
                        text = "${g.name} · ${g.episodes.size}集",
                        selected = g.name == groups.getOrElse(lineIdx) { group }.name,
                        onClick = { onSelectLine(groups.indexOf(g)) },
                    )
                }
            }
        }

        ScrollRow(label = "倍速") {
            items(SPEEDS, key = { "speed_$it" }) { s ->
                TvChip(text = formatSpeed(s), selected = speed == s, onClick = { onSpeed(s) })
            }
        }

        ScrollRow(label = "选集") {
            items(group.episodes, key = { "ep_" + it.index }) { ep ->
                EpisodeButton(ep = ep, current = ep.index == epIndex) { onSelectEpisode(ep.index) }
            }
        }
    }
}

@Composable
private fun ScrollRow(label: String, content: LazyListScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 13.scaledSp(),
            modifier = Modifier.width(74.scaled()),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.scaled()),
            contentPadding = PaddingValues(end = 6.dp),
            modifier = Modifier.weight(1f),
        ) {
            content()
        }
    }
}

@Composable
private fun EpisodeButton(ep: Episode, current: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val colors = tvColors()
    Box(
        modifier = Modifier
            .size(width = 58.scaled(), height = 40.scaled())
            .tvFocusable(shape = shape, focusedScale = 1.1f, borderWidth = 1.5.dp, elevation = 6.dp)
            .clip(shape)
            .background(if (current) colors.primary else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${ep.index}",
            color = if (current) colors.onPrimary else Color.White,
            fontSize = 15.scaledSp(),
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * 播放器持有者。
 *
 * 换集时用新的 headers 重建 ExoPlayer —— share 地址的签名与 Referer 绑定，
 * 复用旧 factory 会 403；旧实例立刻 release，避免盒子硬件解码器被占满。
 */
private class PlayerHolder {
    var player: Player? = null
    var view: PlayerView? = null
    val version = mutableIntStateOf(0)

    /** 当前正在播的集，用于切集/退出时写进度 */
    var boundEpisode: Episode? = null
    var boundDuration: Long = 0L

    fun start(graph: AppGraph, resolved: Resolved.Direct, title: String, speed: Float, episode: Episode) {
        boundEpisode = episode
        release()
        val exo = graph.playerFactory.create(resolved.headers)
        exo.setMediaItem(PlayerFactory.mediaItem(resolved.url, title))
        exo.playWhenReady = true
        exo.setPlaybackSpeed(speed)
        exo.prepare()
        player = exo
        view?.player = exo
        version.intValue = version.intValue + 1
    }

    /** 把当前播放位置写进历史；时长未知（还没缓冲够）时跳过，避免写出 0% 记录 */
    fun saveCurrent(store: LocalStore, drama: Drama) {
        val p = player ?: return
        val ep = boundEpisode ?: return
        val dur = if (p.duration > 0) p.duration else boundDuration
        if (dur <= 0) return
        store.saveProgress(
            drama = drama,
            episodeIndex = ep.index,
            episodeTitle = ep.title,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = dur,
        )
    }

    /** 续播优先；落在片头区间内则跳过片头 */
    fun seekFor(resumeMs: Long, skipIntroSeconds: Int) {
        val p = player ?: return
        val intro = skipIntroSeconds * 1000L
        val target = when {
            resumeMs > 3_000 && resumeMs < intro -> resumeMs
            intro > 0 -> intro
            resumeMs > 3_000 -> resumeMs
            else -> 0L
        }
        if (target > 0) p.seekTo(target)
    }

    fun restart() {
        player?.seekTo(0)
        player?.playWhenReady = true
    }

    fun release() {
        view?.player = null
        (player as? ExoPlayer)?.release()
        player = null
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "--:--"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatSpeed(v: Float): String = if (v == v.toLong().toFloat()) "${v.toLong()}x" else "${v}x"
