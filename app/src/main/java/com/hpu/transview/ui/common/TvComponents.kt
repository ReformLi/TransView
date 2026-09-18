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
import kotlinx.coroutines.delay

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
 *
 * ⚠️ **返回值不是「是否聚焦成功」**：`FocusRequester.requestFocus()` 只在请求器未附着到焦点节点时
 * 抛异常，**被焦点系统静默丢弃时既不抛也不返回 false**，因此这里几乎恒为 `true`。
 * 需要「确保真的落焦」的场景请用 [requestFocusVerified]，或照 `MediaCard` 的写法：
 * 循环调用本函数、并用**真实焦点状态**（`focused` / `rowFocused`）判定成败。
 */
suspend fun FocusRequester.requestFocusNextFrame(): Boolean {
    withFrameNanos { }
    return runCatching { requestFocus() }.isSuccess
}

/** [requestFocusVerified] 相邻两次尝试之间的间隔：让被丢弃的请求有跨帧生效的机会。 */
private const val RETRY_GAP_MS = 16L

/**
 * 帧门控重试地把焦点送到 [this]，用 [isFocused] **校验真实结果**，确认落焦才返回 `true`。
 *
 * ## 为什么必须有「校验」这一层
 * `FocusRequester.requestFocus()` 的失败是**静默**的：请求被焦点系统丢弃时既不抛异常、
 * 也没有返回值可看，于是 `runCatching { requestFocus() }.isSuccess` 几乎恒为 true。
 * 历史写法 `repeat(10) { if (requestFocusNextFrame()) return@launch }` 因此在**第一次**尝试后
 * 就返回了 —— 那 10 次重试是死代码，一旦首次请求被丢弃，焦点就停在「无人持有」状态。
 *
 * ## 真机上为什么会被丢弃（本函数存在的直接原因）
 * 遥控器按返回键时，**系统会在派发 Back 的过程中先清空焦点**（本工程在设置页实测过：
 * `hasFocus` 在 `BackHandler` 执行那一刻已经翻转）。清空与「把焦点送回标签栏」的请求落在
 * 同一帧前后，首次请求会被随后生效的清空覆盖掉。症状完全对得上用户反馈：
 * 按返回键后焦点消失（既不在标签栏也不在内容区），**再按一次**返回或上键才回到标签栏、
 * 而且可能落在错的标签上 —— 因为第二次按返回时焦点本就是空的，没有清空可覆盖，请求才生效；
 * 而「没焦点时按上键」只能交给框架的兜底几何搜索，落到哪个标签不由我们决定。
 * 模拟器上清空时机不同，且模拟器上这条路径恰好不复现。
 *
 * ## 用法约束
 * - [isFocused] 必须读**真实焦点状态**：节点的 `isFocused` / `hasFocus` 状态，或
 *   「最后聚焦过谁」的记录（如主界面的 `focusedTabIndex`）。
 *   **不要用 `hasFocus` 直接当判据** —— 理由同上，Back 会把它清空。
 * - 用「记录型」判据时，调用前必须**先清空该记录**，否则「旧值恰好等于目标」会让第一次
 *   尝试就误判成功（这正是返回键场景最容易踩的坑）。
 * - 请求必须在**帧回调内**发起（帧间隙调用会被静默丢弃，实测踩过），故每次尝试都先
 *   [withFrameNanos]；相邻尝试之间再让出 [RETRY_GAP_MS]。
 *
 * @param attempts 最大尝试次数（每次约一帧 + [RETRY_GAP_MS]）
 * @return 是否**确认**落焦成功。返回 `false` 时调用方必须走兜底路径（例如把「聚焦首行」
 *         票据投给内容区），**绝不能放任「焦点无人持有」** —— 那正是本 bug 的现场。
 */
suspend fun FocusRequester.requestFocusVerified(
    attempts: Int = 10,
    isFocused: () -> Boolean
): Boolean {
    repeat(attempts) {
        withFrameNanos { }
        runCatching { requestFocus() }
        if (isFocused()) return true
        delay(RETRY_GAP_MS)
    }
    return false
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
