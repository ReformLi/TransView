package com.hpu.transview.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 下一帧再请求焦点。
 *
 * Lazy 列表/网格的项在组合完成的同一帧里可能还没完成布局，此时 [FocusRequester.requestFocus]
 * 会抛 IllegalStateException 被 `runCatching` 静默吞掉 —— 表现就是「焦点还原/焦点跳转没反应」。
 * 让出一帧等布局稳定后再请求，即可稳定命中。媒体库与上传页共用。
 */
suspend fun FocusRequester.requestFocusNextFrame(): Boolean {
    withFrameNanos { }
    return runCatching { requestFocus() }.isSuccess
}

/** 遥控器焦点效果：轻微放大 + 主色描边 + 底色变化，应用于任意可聚焦容器 */fun Modifier.tvFocus(
    cornerRadius: Int = 10,
    focusedScale: Float = 1.03f
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(120),
        label = "focusScale"
    )
    this
        .onFocusChanged { focused = it.hasFocus }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(RoundedCornerShape(cornerRadius.dp))
        .background(
            if (focused) MaterialTheme.colorScheme.surfaceVariant
            else Color.Transparent
        )
        .then(
            if (focused) Modifier.border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(cornerRadius.dp)
            ) else Modifier
        )
}

/** 遥控器友好的文本按钮 */
@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** false 时即使持有焦点也按未聚焦渲染：用于焦点「过渡停靠」时隐藏高亮闪烁 */
    showFocusVisual: Boolean = true,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val showFocused = focused && showFocusVisual
    val scale by animateFloatAsState(
        targetValue = if (showFocused) 1.06f else 1f,
        animationSpec = tween(120),
        label = "btnScale"
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = if (showFocused) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        }
    ) {
        Text(text = text)
    }
}

/** 可点击的选项行（对话框内使用） */
@Composable
fun OptionRow(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .tvFocus(cornerRadius = 8)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = (if (selected) "● " else "○ ") + text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
