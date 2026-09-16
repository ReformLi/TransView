package com.hpu.transview.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.hpu.transview.ui.common.LocalIsTouchMode
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hpu.transview.ui.common.TvButton

/**
 * 播放器控件（全部 Canvas 手绘，与项目既有 FileTypeIcon / CopyIcon 风格一致，不引图标库）。
 * 设计基准：图标画在 24×24 的逻辑网格内，按实际尺寸等比缩放。
 */
enum class PlayerIconType { PREV, REWIND, PLAY, PAUSE, FORWARD, NEXT, AUDIO, SUBTITLE, SETTINGS }

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

        // 设置（音轨/字幕）：双滑杆样式（YouTube 系设置图标的简化画法）
        PlayerIconType.SETTINGS -> {
            drawLine(
                tint, Offset(4f * u, 8.5f * u), Offset(20f * u, 8.5f * u),
                strokeWidth = 1.8f * u, cap = StrokeCap.Round
            )
            drawLine(
                tint, Offset(4f * u, 15.5f * u), Offset(20f * u, 15.5f * u),
                strokeWidth = 1.8f * u, cap = StrokeCap.Round
            )
            drawCircle(tint, radius = 2.8f * u, center = Offset(14.5f * u, 8.5f * u))
            drawCircle(tint, radius = 2.8f * u, center = Offset(9.5f * u, 15.5f * u))
        }
    }
}

/**
 * 图标按钮：46dp 方块（emphasized 用于播放/暂停，60dp），聚焦时放大 + 主色填充。
 *
 * **`enabled = false` 时按钮依然可聚焦**（只是不再响应确定键、按灰色禁用样式渲染、聚焦时
 * 用灰色描边而不是主色填充）。理由与 `TvButton` 相同：Material3/`clickable` 在 `enabled = false`
 * 时会把元素移出焦点候选，若它**正持有焦点**（典型：在「下一集」上按确定键切到最后一集，`hasNext`
 * 立刻变 false），Compose 会丢弃焦点并回退到整棵树第一个可聚焦元素——播放器里就是根节点，
 * 表现为控制栏上一个高亮都没有、之后左右键直接变成快进快退。详见 ARCHITECTURE §3.6。
 */
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
        targetValue = if (focused && !LocalIsTouchMode.current) 1.1f else 1f,
        animationSpec = tween(120),
        label = "playerIconBtnScale"
    )
    val shape = RoundedCornerShape(if (emphasized) 14.dp else 10.dp)
    val tint = when {
        !enabled -> Color.White.copy(alpha = if (focused && !LocalIsTouchMode.current) 0.40f else 0.28f)
        focused && !LocalIsTouchMode.current -> MaterialTheme.colorScheme.onPrimary
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
                    // 禁用态不给主色填充（暗图标压在亮蓝上会糊成一片），改用灰底
                    !enabled && focused && !LocalIsTouchMode.current -> Color.White.copy(alpha = 0.14f)
                    !enabled -> Color.White.copy(alpha = 0.06f)
                    focused && !LocalIsTouchMode.current -> MaterialTheme.colorScheme.primary
                    emphasized -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else -> Color.White.copy(alpha = 0.10f)
                }
            )
            .then(
                when {
                    // 禁用态的焦点提示：灰色描边（焦点位置依然看得见，但一眼可辨「不可用」）
                    !enabled && focused && !LocalIsTouchMode.current -> Modifier.border(2.dp, Color.White.copy(alpha = 0.45f), shape)
                    !enabled -> Modifier
                    focused && !LocalIsTouchMode.current -> Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                    emphasized -> Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        shape
                    )
                    else -> Modifier
                }
            )
            // clickable 恒 enabled：禁用态必须**保留焦点候选资格**（见函数注释），
            // 因此由自己吞掉点击，而不是交给 clickable(enabled = false)
            .clickable(enabled = true) { if (enabled) onClick() },
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
        targetValue = if (focused && !LocalIsTouchMode.current) 1.08f else 1f,
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
                if (focused && !LocalIsTouchMode.current) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.10f)
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
            color = if (focused && !LocalIsTouchMode.current) MaterialTheme.colorScheme.onPrimary else Color.White
        )
    }
}

/**
 * 播放进度条：已缓冲段（浅白）+ 已播放段（主色）+ 当前圆点。
 * 高度 20dp 但默认轨道只占 6dp，多出的空间用于容纳圆点，避免圆点被裁切。
 *
 * [focused] = 时间轴持有焦点（拖动模式）：轨道 6→10dp、圆点 7→11dp 并出现主色光环，
 * 全部经 [animateFloatAsState] 平滑过渡 —— 时间轴是控制栏的视觉主角，
 * 「可拖动」这个状态必须一眼可辨。
 */
@Composable
fun PlayerProgressBar(
    position: Long,
    buffered: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    focused: Boolean = false
) {
    val playedFraction =
        if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction =
        if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary
    val focusLevel by animateFloatAsState(
        targetValue = if (focused && !LocalIsTouchMode.current) 1f else 0f,
        animationSpec = tween(160),
        label = "progressBarFocus"
    )

    Canvas(
        modifier
            .fillMaxWidth()
            .height(20.dp)
    ) {
        val trackHeight = (6.dp.toPx() + 4.dp.toPx() * focusLevel)
        val radius = trackHeight / 2f
        val centerY = size.height / 2f
        val knobRadius = 7.dp.toPx() + 4.dp.toPx() * focusLevel

        // 焦点光环（拖动模式的主色柔光，先画在最底层）
        if (focusLevel > 0f) {
            val knobX = (size.width * playedFraction)
                .coerceIn(knobRadius, (size.width - knobRadius).coerceAtLeast(knobRadius))
            drawCircle(
                color = primary.copy(alpha = 0.30f * focusLevel),
                radius = knobRadius + 6.dp.toPx(),
                center = Offset(knobX, centerY)
            )
        }

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
 * 快进/快退预览卡：缩略图（异步加载，未就绪时为暗色占位）+ 方向图标 +「目标位置 / 总时长」。
 * 取代旧的纯文字中央徽标 —— 拖动不再是盲跳，用户能看到「将要跳到的那一帧」。
 */
@Composable
fun PlayerScrubCard(
    forward: Boolean,
    text: String,
    thumb: ImageBitmap?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xB30A0E14))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(width = 272.dp, height = 153.dp)   // 16:9
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF171C24)),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // 占位：缩略图还在解码（或该格式取不出帧）
                Text(
                    "…",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.35f)
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PlayerIconGlyph(
                if (forward) PlayerIconType.FORWARD else PlayerIconType.REWIND,
                Color.White,
                Modifier.size(34.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleLarge.copy(
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

/**
 * 内联选择器胶囊（倍速 / 画面比例，v1.10）：取代旧模态弹框。
 * 选中态 = 主色淡填充 + 主色描边（一眼可辨「当前值」）；
 * 聚焦态 = 主色实填充 + 轻微放大（遥控器高亮）。
 */
@Composable
fun SelectorPill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused && !LocalIsTouchMode.current) 1.08f else 1f,
        animationSpec = tween(120),
        label = "selectorPillScale"
    )
    val shape = RoundedCornerShape(50)   // 50% = 胶囊
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(
                when {
                    focused -> primary
                    selected -> primary.copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.10f)
                }
            )
            .then(
                if (selected && !focused) {
                    Modifier.border(1.5.dp, primary.copy(alpha = 0.7f), shape)
                } else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused && !LocalIsTouchMode.current) MaterialTheme.colorScheme.onPrimary else Color.White
        )
    }
}

/**
 * 下一集预告卡（v1.10）：缩略图 + 标题 + 倒计时 +「立即播放 / 取消」。
 * 播完且自动连播开启时弹出，倒计时归零自动切集；取消则停在片尾由用户接管。
 */
@Composable
fun PlayerNextCard(
    title: String,
    indexLabel: String,
    countdownSec: Int,
    thumb: ImageBitmap?,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF20B0F16))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(width = 200.dp, height = 112.dp)   // 16:9
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF171C24)),
            contentAlignment = Alignment.Center
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // 占位：封面帧还在解码（或该格式取不出帧）
                Text(
                    "…",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White.copy(alpha = 0.35f)
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.width(300.dp)) {
            Text(
                "下一集",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = indexLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${countdownSec} 秒后自动播放",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f)
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("立即播放", modifier = Modifier.focusRequester(playFocus), onClick = onPlay)
                TvButton("取消", onClick = onCancel)
            }
        }
    }
}
