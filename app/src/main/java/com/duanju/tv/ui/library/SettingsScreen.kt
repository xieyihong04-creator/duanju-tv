package com.duanju.tv.ui.library

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.duanju.tv.appGraph
import com.duanju.tv.data.remote.DefaultSources
import com.duanju.tv.data.remote.SourceSpec
import com.duanju.tv.ui.components.TvChip
import com.duanju.tv.ui.theme.scaled
import com.duanju.tv.ui.theme.scaledSp
import com.duanju.tv.ui.theme.tvColors
import com.duanju.tv.ui.theme.tvFocusable

/**
 * 设置页：TV 友好的分组列表，每行左标题+说明、右控件。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { context.appGraph().store }
    val settings by store.settings.collectAsStateWithLifecycle()

    // 版本号：用 packageManager 获取，不依赖 BuildConfig 开关
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrDefault("1.0.0")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tvColors().background),
    ) {
        SettingsTopBar(onBack = onBack)

        Spacer(Modifier.height(4.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 28.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // ── 播放设置 ──
            item { SettingsGroupHeader("播放设置") }
            item {
                SourceSelectorRow(
                    currentSourceId = settings.sourceId,
                    onSelect = { newId -> store.updateSettings { s -> s.copy(sourceId = newId) } },
                )
            }
            item {
                SpeedSelectorRow(
                    currentSpeed = settings.defaultSpeed,
                    onSelect = { newSpeed -> store.updateSettings { s -> s.copy(defaultSpeed = newSpeed) } },
                )
            }
            item {
                ToggleRow(
                    title = "自动播放下一集",
                    description = "播完当前集自动播放下一集",
                    checked = settings.autoPlayNext,
                    onToggle = { store.updateSettings { s -> s.copy(autoPlayNext = !s.autoPlayNext) } },
                )
            }
            item {
                ToggleRow(
                    title = "记住播放进度",
                    description = "下次打开从上次位置继续",
                    checked = settings.resumePlayback,
                    onToggle = { store.updateSettings { s -> s.copy(resumePlayback = !s.resumePlayback) } },
                )
            }
            item {
                StepRow(
                    title = "跳过片头",
                    description = "自动跳过片头秒数（0~60）",
                    value = settings.skipIntroSeconds,
                    unit = "秒",
                    step = 5,
                    range = 0..60,
                    onChange = { store.updateSettings { s -> s.copy(skipIntroSeconds = it) } },
                )
            }
            item {
                StepRow(
                    title = "跳过片尾",
                    description = "自动跳过片尾秒数（0~60）",
                    value = settings.skipEndingSeconds,
                    unit = "秒",
                    step = 5,
                    range = 0..60,
                    onChange = { store.updateSettings { s -> s.copy(skipEndingSeconds = it) } },
                )
            }

            // ── 界面设置 ──
            item { Spacer(Modifier.height(12.dp)) }
            item { SettingsGroupHeader("界面设置") }
            item {
                StepRow(
                    title = "界面缩放",
                    description = "调整整体界面大小（50%~150%）",
                    value = (settings.uiScale * 100).toInt(),
                    unit = "%",
                    step = 10,
                    range = 50..150,
                    onChange = { store.updateSettings { s -> s.copy(uiScale = it / 100f) } },
                )
            }
            item {
                ToggleRow(
                    title = "显示来源角标",
                    description = "海报左上角显示数据源名称",
                    checked = settings.showSourceBadge,
                    onToggle = { store.updateSettings { s -> s.copy(showSourceBadge = !s.showSourceBadge) } },
                )
            }
            item {
                ToggleRow(
                    title = "沉浸式详情页",
                    description = "详情页背景使用海报模糊",
                    checked = settings.immersiveDetail,
                    onToggle = { store.updateSettings { s -> s.copy(immersiveDetail = !s.immersiveDetail) } },
                )
            }
            item {
                ToggleRow(
                    title = "调暗背景",
                    description = "播放时调暗周围环境",
                    checked = settings.dimBackground,
                    onToggle = { store.updateSettings { s -> s.copy(dimBackground = !s.dimBackground) } },
                )
            }
            item {
                ToggleRow(
                    title = "保持屏幕常亮",
                    description = "播放时不自动息屏",
                    checked = settings.keepScreenOn,
                    onToggle = { store.updateSettings { s -> s.copy(keepScreenOn = !s.keepScreenOn) } },
                )
            }
        }

        // 底部版本号
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "短剧 TV v$versionName",
                color = tvColors().onSurfaceVariant,
                fontSize = 13.scaledSp(),
            )
        }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .tvFocusable(shape = RoundedCornerShape(50), focusedScale = 1.05f)
                .clip(RoundedCornerShape(50))
                .background(tvColors().surfaceVariant)
                .clickable(onClick = onBack)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            Text("← 返回", color = tvColors().onSurface, fontSize = 14.scaledSp())
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = "设置",
            color = tvColors().onSurface,
            fontSize = 24.scaledSp(),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(vertical = 8.dp),
        color = tvColors().primary,
        fontSize = 14.scaledSp(),
        fontWeight = FontWeight.Bold,
    )
}

/** 开关行：material3 Switch */
@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = shape, focusedScale = 1.01f, elevation = 4.dp, onFocus = { focused = it })
            .clip(shape)
            .background(if (focused) tvColors().surfaceVariant else Color.Transparent)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = tvColors().onSurface, fontSize = 16.scaledSp(), fontWeight = FontWeight.Medium)
            Text(description, color = tvColors().onSurfaceVariant, fontSize = 13.scaledSp())
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = tvColors().primary,
                checkedTrackColor = tvColors().primary.copy(alpha = 0.3f),
                uncheckedThumbColor = tvColors().onSurfaceVariant,
                uncheckedTrackColor = tvColors().surfaceVariant,
            ),
        )
    }
}

/** 数据源选择行 */
@Composable
private fun SourceSelectorRow(
    currentSourceId: String,
    onSelect: (String) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }

    // 全网聚合 + 各独立源
    val sources = remember {
        listOf(
            SourceSpec(id = SourceSpec.AGGREGATE_ID, name = "全网聚合", listApi = ""),
        ) + DefaultSources.ALL
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = shape, focusedScale = 1.01f, elevation = 4.dp, onFocus = { focused = it })
            .clip(shape)
            .background(if (focused) tvColors().surfaceVariant else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("数据源", color = tvColors().onSurface, fontSize = 16.scaledSp(), fontWeight = FontWeight.Medium)
            Text("选择短剧数据来源", color = tvColors().onSurfaceVariant, fontSize = 13.scaledSp())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { spec ->
                TvChip(
                    text = spec.name,
                    selected = currentSourceId == spec.id,
                    onClick = { onSelect(spec.id) },
                )
            }
        }
    }
}

/** 倍速选择行 */
@Composable
private fun SpeedSelectorRow(
    currentSpeed: Float,
    onSelect: (Float) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }
    val speeds = remember { listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = shape, focusedScale = 1.01f, elevation = 4.dp, onFocus = { focused = it })
            .clip(shape)
            .background(if (focused) tvColors().surfaceVariant else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("默认倍速", color = tvColors().onSurface, fontSize = 16.scaledSp(), fontWeight = FontWeight.Medium)
            Text("播放时的默认速度", color = tvColors().onSurfaceVariant, fontSize = 13.scaledSp())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            speeds.forEach { speed ->
                TvChip(
                    text = "${speed}x",
                    selected = currentSpeed == speed,
                    onClick = { onSelect(speed) },
                )
            }
        }
    }
}

/** 步进调节行：± 按钮 + 当前值 */
@Composable
private fun StepRow(
    title: String,
    description: String,
    value: Int,
    unit: String,
    step: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(shape = shape, focusedScale = 1.01f, elevation = 4.dp, onFocus = { focused = it })
            .clip(shape)
            .background(if (focused) tvColors().surfaceVariant else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = tvColors().onSurface, fontSize = 16.scaledSp(), fontWeight = FontWeight.Medium)
            Text(description, color = tvColors().onSurfaceVariant, fontSize = 13.scaledSp())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(label = "−", onClick = { onChange((value - step).coerceIn(range)) })
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$value$unit",
                color = tvColors().onSurface,
                fontSize = 15.scaledSp(),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(60.dp),
            )
            Spacer(Modifier.width(8.dp))
            StepButton(label = "+", onClick = { onChange((value + step).coerceIn(range)) })
        }
    }
}

/** 步进 ± 按钮 */
@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .tvFocusable(shape = RoundedCornerShape(50), focusedScale = 1.1f)
            .clip(RoundedCornerShape(50))
            .background(tvColors().surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = tvColors().onSurface, fontSize = 16.scaledSp())
    }
}
