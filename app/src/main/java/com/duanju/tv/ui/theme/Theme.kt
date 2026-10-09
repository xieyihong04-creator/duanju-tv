package com.duanju.tv.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 界面缩放：不同盒子观感差异大，允许 50%~150%（设置项） */
val LocalUiScale = staticCompositionLocalOf { 1f }

@Composable
fun Int.scaled(): Dp = (this * LocalUiScale.current).dp

@Composable
fun Int.scaledSp(): androidx.compose.ui.unit.TextUnit =
    (this * LocalUiScale.current).sp

object Palette {
    val Primary = Color(0xFFFF5C7A)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryDark = Color(0xFFD94A68)
    val Secondary = Color(0xFFFFB74D)
    val OnSecondary = Color(0xFF2A1700)

    val BackgroundDark = Color(0xFF0B0B0F)
    val SurfaceDark = Color(0xFF15151C)
    val SurfaceVariantDark = Color(0xFF23232E)
    val OnSurfaceDark = Color(0xFFF2F2F5)
    val OnSurfaceVariantDark = Color(0xFFA9A9B6)

    val BackgroundLight = Color(0xFFF5F4F7)
    val SurfaceLight = Color(0xFFFFFFFF)
    val SurfaceVariantLight = Color(0xFFE6E4EA)
    val OnSurfaceLight = Color(0xFF17171B)
    val OnSurfaceVariantLight = Color(0xFF5C5C66)

    val Scrim = Color(0xCC000000)
    val Overlay = Color(0x99000000)
    val SoftOverlay = Color(0x66000000)
    val Success = Color(0xFF4CAF7D)
    val Danger = Color(0xFFE05C5C)
    val Gold = Color(0xFFFFC93C)
}

/**
 * 自管的配色集合。
 * 不用 tv-material：它的 API 需要按其内部 DSL 构造，跨版本不稳；
 * 这里用普通 Compose 组件 + 自绘焦点效果，行为完全可控。
 */
data class TvColors(
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val focusRing: Color,
    val onScrim: Color,
)

private val DarkColors = TvColors(
    primary = Palette.Primary,
    onPrimary = Palette.OnPrimary,
    secondary = Palette.Secondary,
    onSecondary = Palette.OnSecondary,
    background = Palette.BackgroundDark,
    surface = Palette.SurfaceDark,
    surfaceVariant = Palette.SurfaceVariantDark,
    onSurface = Palette.OnSurfaceDark,
    onSurfaceVariant = Palette.OnSurfaceVariantDark,
    focusRing = Color.White,
    onScrim = Palette.OnSurfaceDark,
)

private val LightColors = TvColors(
    primary = Palette.PrimaryDark,
    onPrimary = Color.White,
    secondary = Palette.Secondary,
    onSecondary = Palette.OnSecondary,
    background = Palette.BackgroundLight,
    surface = Palette.SurfaceLight,
    surfaceVariant = Palette.SurfaceVariantLight,
    onSurface = Palette.OnSurfaceLight,
    onSurfaceVariant = Palette.OnSurfaceVariantLight,
    focusRing = Palette.PrimaryDark,
    onScrim = Color.White,
)

val LocalTvColors = staticCompositionLocalOf { DarkColors }

@Composable
fun tvColors(): TvColors = LocalTvColors.current

@Composable
fun DuanjuTheme(dark: Boolean = true, uiScale: Float = 1f, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalUiScale provides uiScale,
        LocalTvColors provides if (dark) DarkColors else LightColors,
    ) { content() }
}

@Composable
fun isTvDark(): Boolean = isSystemInDarkTheme()

/**
 * 焦点反馈：放大 + 描边 + 阴影。TV 上用户唯一的光标就是焦点，
 * 因此这个修饰符是整个界面可用性的基础。
 */
fun Modifier.tvFocusable(
    focusedScale: Float = 1.05f,
    shape: Shape = RoundedCornerShape(10.dp),
    borderWidth: Dp = 2.dp,
    elevation: Dp = 12.dp,
    onFocus: ((Boolean) -> Unit)? = null,
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 420f),
        label = "tvScale",
    )
    val ring by animateColorAsState(
        targetValue = if (isFocused) tvColors().focusRing else Color.Transparent,
        animationSpec = spring(stiffness = 520f),
        label = "tvRing",
    )
    this
        .onFocusChanged { st ->
            isFocused = st.isFocused
            onFocus?.invoke(st.isFocused)
        }
        .scale(scale)
        .shadow(if (isFocused) elevation else 0.dp, shape, clip = false)
        .drawWithContent {
            drawContent()
            if (ring.alpha > 0.02f) {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, color = ring, style = Stroke(width = borderWidth.toPx()))
            }
        }
}

@Composable
fun TvTitle(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 20,
    bold: Boolean = true,
    maxLines: Int = 1,
    color: Color = tvColors().onSurface,
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize.scaledSp(),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = maxLines,
    )
}

@Composable
fun TvBody(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    maxLines: Int = 2,
    color: Color = tvColors().onSurfaceVariant,
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize.scaledSp(),
        maxLines = maxLines,
    )
}
