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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalView
import android.view.ViewTreeObserver.OnTouchModeChangeListener
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** 触屏模式：全局可观察，用于触屏交互时隐藏 TV 风格焦点环/描边，遥控器按键时恢复。 */
val LocalIsTouchMode = compositionLocalOf { false }

/**
 * 提供全局触屏模式状态：监听 View 的 touch mode 变化（触屏交互后转 true，
 * 遥控器/键盘按键后转 false），下发给所有焦点组件，使其在触屏下不显示焦点高亮。
 */
@Composable
fun ProvideTouchMode(content: @Composable () -> Unit) {
    val view = LocalView.current
    var isTouchMode by remember { mutableStateOf(view.isInTouchMode) }
    DisposableEffect(view) {
        val listener = OnTouchModeChangeListener { isTouchMode = it }
        view.viewTreeObserver.addOnTouchModeChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnTouchModeChangeListener(listener) }
    }
    CompositionLocalProvider(LocalIsTouchMode provides isTouchMode) { content() }
}

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
    val isTouchMode = LocalIsTouchMode.current
    val scale by animateFloatAsState(
        targetValue = if (focused && !isTouchMode) focusedScale else 1f,
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
            if (focused && !isTouchMode) MaterialTheme.colorScheme.surfaceVariant
            else Color.Transparent
        )
        .then(
            if (focused && !isTouchMode) Modifier.border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(cornerRadius.dp)
            ) else Modifier
        )
}

/**
 * 遥控器友好的文本按钮。
 *
 * **`enabled = false` 时按钮依然可聚焦**（只是不再响应点击、按灰色禁用样式渲染）。
 *
 * 为什么不能把 `enabled` 直接交给 Material3 的 `Button`：它会把按钮移出焦点候选，
 * 而「正持有焦点的元素突然不可聚焦」会让 Compose 重新做一次焦点搜索，回退到整棵树里
 * 第一个可聚焦元素 —— 顶部导航栏的「上传」标签，而标签是「聚焦即选中」，页面会被立刻
 * 切走（实测：媒体库点「刷新」→ 直接跳到上传页）。
 * 因此这里始终 `enabled = true`，用参数 `enabled` 自己控制「是否响应点击 + 禁用配色」，
 * 既保住焦点，也保住 `focusRequester` / `onPreviewKeyEvent` 的挂载。
 */
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
    val isTouchMode = LocalIsTouchMode.current
    val showFocused = focused && showFocusVisual && !isTouchMode
    val scale by animateFloatAsState(
        targetValue = if (showFocused) 1.06f else 1f,
        animationSpec = tween(120),
        label = "btnScale"
    )
    Button(
        // 禁用态也不能把按键放行给 onClick（Button 本身始终 enabled，见函数注释）
        onClick = { if (enabled) onClick() },
        enabled = true,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        colors = when {
            showFocused -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
            // 禁用配色：Button 始终 enabled，Material3 不会自动套禁用色，这里显式给出
            !enabled -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            else -> ButtonDefaults.buttonColors(
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
    /** 禁用态：**仍可聚焦**（理由同 [TvButton]），只是不响应点击并按灰色渲染 */
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .tvFocus(cornerRadius = 8)
            .clickable { if (enabled) onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = (if (selected) "● " else "○ ") + text,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
}
