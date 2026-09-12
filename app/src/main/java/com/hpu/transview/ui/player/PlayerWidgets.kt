package com.hpu.transview.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 播放器控件（全部 Canvas 手绘，与项目既有 FileTypeIcon / CopyIcon 风格一致，不引图标库）。
 * 设计基准：图标画在 24×24 的逻辑网格内，按实际尺寸等比缩放。
 */
enum class PlayerIconType { PREV, REWIND, PLAY, PAUSE, FORWARD, NEXT, AUDIO, SUBTITLE }

@Composable
fun PlayerIconGlyph(icon: PlayerIconType, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { drawPlayerIcon(icon, tint) }
}

private fun DrawScope.drawPlayerIcon(icon: PlayerIconType, tint: Color) {
    val u = size.minDimension / 24f

    fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val path = Path().apply {
            moveTo(x1 * u, y1 * u)
            lineTo(x2 * u, y2 * u)
            lineTo(x3 * u, y3 * u)
            close()
        }
        drawPath(path, tint, style = Fill)
    }

    when (icon) {
        PlayerIconType.PLAY -> triangle(8f, 5f, 19.5f, 12f, 8f, 19f)

        PlayerIconType.PAUSE -> {
            drawRoundRect(tint, Offset(8f * u, 5f * u), Size(3f * u, 14f * u), CornerRadius(1.2f * u))
            drawRoundRect(tint, Offset(13f * u, 5f * u), Size(3f * u, 14f * u), CornerRadius(1.2f * u))
        }

        PlayerIconType.PREV -> {
            drawLine(
                tint, Offset(6f * u, 5f * u), Offset(6f * u, 19f * u),
                strokeWidth = 2f * u, cap = StrokeCap.Round
            )
            triangle(19f, 5.5f, 9.5f, 12f, 19f, 18.5f)
        }

        PlayerIconType.NEXT -> {
            drawLine(
                tint, Offset(18f * u, 5f * u), Offset(18f * u, 19f * u),
                strokeWidth = 2f * u, cap = StrokeCap.Round
            )
            triangle(5f, 5.5f, 14.5f, 12f, 5f, 18.5f)
        }

        PlayerIconType.REWIND -> {
            triangle(11.5f, 5.5f, 3.5f, 12f, 11.5f, 18.5f)
            triangle(20.5f, 5.5f, 12.5f, 12f, 20.5f, 18.5f)
        }

        PlayerIconType.FORWARD -> {
            triangle(3.5f, 5.5f, 11.5f, 12f, 3.5f, 18.5f)
            triangle(12.5f, 5.5f, 20.5f, 12f, 12.5f, 18.5f)
        }

        PlayerIconType.AUDIO -> {
            val body = Path().apply {
                moveTo(3f * u, 9.5f * u)
                lineTo(6.6f * u, 9.5f * u)
                lineTo(10.5f * u, 6f * u)
                lineTo(10.5f * u, 18f * u)
                lineTo(6.6f * u, 14.5f * u)
                lineTo(3f * u, 14.5f * u)
                close()
            }
            drawPath(body, tint, style = Fill)
            drawArc(
                color = tint, startAngle = -50f, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(10f * u, 8.5f * u), size = Size(6f * u, 7f * u),
                style = Stroke(width = 1.8f * u, cap = StrokeCap.Round)
            )
            drawArc(
                color = tint, startAngle = -50f, sweepAngle = 100f, useCenter = false,
                topLeft = Offset(10f * u, 5.5f * u), size = Size(11f * u, 13f * u),
                style = Stroke(width = 1.8f * u, cap = StrokeCap.Round)
            )
        }

        PlayerIconType.SUBTITLE -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(3f * u, 5f * u),
                size = Size(18f * u, 14f * u),
                cornerRadius = CornerRadius(2.5f * u),
                style = Stroke(width = 1.8f * u)
            )
            drawLine(
                tint, Offset(6.5f * u, 11.5f * u), Offset(11.5f * u, 11.5f * u),
                strokeWidth = 1.8f * u, cap = StrokeCap.Round
            )
            drawLine(
                tint, Offset(6.5f * u, 15f * u), Offset(15f * u, 15f * u),
                strokeWidth = 1.8f * u, cap = StrokeCap.Round
            )
        }
    }
}

/** 图标按钮：46dp 方块（emphasized 用于播放/暂停，60dp），聚焦时放大 + 主色填充 */
@Composable
fun PlayerIconButton(
    icon: PlayerIconType,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 46.dp,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.1f else 1f,
        animationSpec = tween(120),
        label = "playerIconBtnScale"
    )
    val shape = RoundedCornerShape(if (emphasized) 14.dp else 10.dp)
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.28f)
        focused -> MaterialTheme.colorScheme.onPrimary
        emphasized -> MaterialTheme.colorScheme.primary
        else -> Color.White
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = label }
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    emphasized -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.10f)
                }
            )
            .then(
                when {
                    focused -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    emphasized -> Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        shape
                    )
                    else -> Modifier
                }
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        PlayerIconGlyph(icon, tint, Modifier.size(size * 0.44f))
    }
}

/** 纯文字胶囊按钮（当前用于倍速显示，如「1.0x」） */
@Composable
fun PlayerTextButton(
    text: String,
    modifier: Modifier = Modifier,
    minWidth: Dp = 76.dp,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(120),
        label = "playerTextBtnScale"
    )
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .height(46.dp)
            .widthIn(min = minWidth)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.10f)
            )
            .then(
                if (focused) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) MaterialTheme.colorScheme.onPrimary else Color.White
        )
    }
}

/**
 * 播放进度条：已缓冲段（浅白）+ 已播放段（主色）+ 当前圆点。
 * 高度 16dp 但轨道只占 6dp，多出的空间用于容纳圆点，避免圆点被裁切。
 */
@Composable
fun PlayerProgressBar(
    position: Long,
    buffered: Long,
    duration: Long,
    modifier: Modifier = Modifier
) {
    val playedFraction =
        if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction =
        if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary

    Canvas(
        modifier
            .fillMaxWidth()
            .height(16.dp)
    ) {
        val trackHeight = 6.dp.toPx()
        val radius = trackHeight / 2f
        val centerY = size.height / 2f
        val knobRadius = 7.dp.toPx()

        drawRoundRect(
            color = Color.White.copy(alpha = 0.22f),
            topLeft = Offset(0f, centerY - radius),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(radius)
        )

        if (bufferedFraction > 0f) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.34f),
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width * bufferedFraction, trackHeight),
                cornerRadius = CornerRadius(radius)
            )
        }

        if (playedFraction > 0f) {
            drawRoundRect(
                color = primary,
                topLeft = Offset(0f, centerY - radius),
                size = Size(size.width * playedFraction, trackHeight),
                cornerRadius = CornerRadius(radius)
            )
        }

        val knobX = (size.width * playedFraction)
            .coerceIn(knobRadius, (size.width - knobRadius).coerceAtLeast(knobRadius))
        drawCircle(color = Color.White, radius = knobRadius, center = Offset(knobX, centerY))
        drawCircle(
            color = primary,
            radius = knobRadius,
            center = Offset(knobX, centerY),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

/**
 * 屏幕中央的反馈（快进/快退时间、播放、暂停、播放结束），由调用方控制显隐。
 * 无底色面板，只画图标 + 文字；文字带柔和投影，保证亮画面上也能看清。
 */
@Composable
fun PlayerCentralBadge(icon: PlayerIconType?, text: String) {
    Column(
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            PlayerIconGlyph(icon, Color.White, Modifier.size(56.dp))
        }
        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineSmall.copy(
                    shadow = Shadow(
                        color = Color(0xB3000000),
                        offset = Offset(0f, 2f),
                        blurRadius = 10f
                    )
                ),
                color = Color.White
            )
        }
    }
}
