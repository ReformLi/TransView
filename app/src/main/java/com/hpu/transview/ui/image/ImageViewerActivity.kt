package com.hpu.transview.ui.image

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.hpu.transview.model.MediaRef
import com.hpu.transview.ui.common.requestFocusNextFrame
import com.hpu.transview.ui.theme.PrimaryBlue
import com.hpu.transview.ui.theme.TransViewTheme
import com.hpu.transview.util.FileLocations
import com.hpu.transview.util.nameIsImageFile
import com.hpu.transview.util.naturalCompare
import com.hpu.transview.util.toUri
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * 图片查看器：
 * - 全屏展示，遥控器中键缩放（1x ↔ 2x），+/- 或菜单键循环缩放
 * - 缩放时方向键平移，未缩放时左右键切换上一张/下一张（自然排序）
 * - 非放大时：↑ 全屏（隐藏顶部文件名栏，再按 ↑ 恢复）；
 *   ↓ 唤出底部同目录缩略图轮播并进入取景框模式（固定取景框恒定居中不动，
 *   左右键缩略图从框下滑过，两端自然留白且不可再划；
 *   确定键切换主图且轮播保持，↑/返回收起）
 * - 返回键分层：轮播可见先收轮播 → 图片放大态先复原 → 再按才退出回列表
 */
class ImageViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        /** 返回给媒体库：最后浏览的图片路径，用于焦点定位 */
        const val EXTRA_RESULT_PATH = "last_viewed_path"
        private const val MAX_SCALE = 5f
        private const val THUMB_SIZE = 110          // 缩略图边长 dp
        private const val THUMB_GAP = 12            // 缩略图水平间距 dp（左右各 6）
        private const val STRIP_HPAD = 24           // 轮播条容器水平内边距 dp
        private const val STRIP_VPAD = 14           // 轮播条容器垂直内边距 dp
    }

    private var images: List<MediaRef> = emptyList()
    private var startIndex = 0

    // —— Compose 状态 ——
    var index by mutableStateOf(0)
    var scale by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var overlayVisible by mutableStateOf(true)
    var lastInteractionTick by mutableStateOf(0)

    // 缩略图轮播：visible=轮播唤出且取景框模式生效（↓ 一次即进入，无二段状态）
    var carouselVisible by mutableStateOf(false)

    // 取景框当前对准的缩略图下标（焦点框不动，滑动的是缩略图）
    var cursorIndex by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !FileLocations.existsForPath(path)) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 仅同目录图片参与切换（自然排序）；走活动存储的兄弟条目（v1.14 恒为本地 File）
        images = FileLocations.siblings(path)
            .filter { !it.isDirectory && nameIsImageFile(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            .map { MediaRef(it.path, it.name) }
            .ifEmpty { listOf(MediaRef(path, path.substringAfterLast('/'))) }
        startIndex = images.indexOfFirst { it.path == path }.takeIf { it >= 0 } ?: 0
        index = startIndex
        cursorIndex = startIndex
        postResult()

        setContent {
            TransViewTheme {
                ViewerScreen()
            }
        }
    }

    /**
     * 把当前浏览的图片路径写入返回结果。
     * 注意必须在 finish() 之前调用——系统在 finish 时就按当时的 result 封装返回值，
     * 拖到 onPause 再 setResult 会来不及（实测拿到 null）。
     */
    private fun postResult() {
        images.getOrNull(index)?.let {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, it.path))
        }
    }

    private fun switchImage(delta: Int) {
        val next = index + delta
        if (next in images.indices) {
            index = next
            scale = 1f
            offset = Offset.Zero
            overlayVisible = true
            postResult()
        }
    }

    /** 确定键选中取景框里的缩略图：切图，轮播与取景框保持，不隐藏 */
    private fun selectImage(target: Int) {
        if (target in images.indices) {
            index = target
            scale = 1f
            offset = Offset.Zero
            postResult()
        }
    }

    /** 收起轮播（↑ / 返回键），焦点交还根容器 */
    private fun hideCarousel() {
        carouselVisible = false
        lastInteractionTick++
    }

    private fun zoomTo(target: Float) {
        scale = target.coerceIn(1f, MAX_SCALE)
        if (scale <= 1f) {
            scale = 1f
            offset = Offset.Zero
        } else {
            // 保持缩放中心在屏幕中心附近
            offset = Offset.Zero
        }
    }

    @Composable
    private fun ViewerScreen() {
        val rootFocus = remember { FocusRequester() }
        val stripFocus = remember { FocusRequester() }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scrollState = rememberScrollState()

        // 返回键分层处理：轮播可见→先收起轮播；图片放大态→先复原；
        // 都不是→退出回图片列表
        BackHandler {
            when {
                carouselVisible -> hideCarousel()
                scale > 1f -> zoomTo(1f)
                else -> finish()
            }
        }

        LaunchedEffect(overlayVisible, lastInteractionTick) {
            if (overlayVisible) {
                kotlinx.coroutines.delay(4000)
                overlayVisible = false
            }
        }
        LaunchedEffect(overlayVisible) {
            if (!overlayVisible) runCatching { rootFocus.requestFocus() }
        }
        // 轮播焦点管理：唤出后等一帧把焦点固定到轮播条上；收起时交还根容器
        // （轮播被移出组合，避免焦点回退异常）
        LaunchedEffect(carouselVisible) {
            runCatching { withFrameNanos { } }
            if (carouselVisible) stripFocus.requestFocusNextFrame()
            else rootFocus.requestFocusNextFrame()
        }
        // 取景框跟随：cursorIndex 变化时把该缩略图滚动到屏幕正中（两端自然留白）
        LaunchedEffect(carouselVisible, cursorIndex) {
            if (carouselVisible) {
                runCatching {
                    // 等轮播完成首次布局：maxValue 就绪前钳制会把目标错定到 0（永远第一张）
                    if (scrollState.maxValue <= 0) {
                        snapshotFlow { scrollState.maxValue }.first { it > 0 }
                    }
                    val itemStridePx = with(density) { (THUMB_SIZE + THUMB_GAP).dp.toPx() }
                    val target = (cursorIndex * itemStridePx).roundToInt()
                        .coerceIn(0, scrollState.maxValue)
                    scrollState.animateScrollTo(target)
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 屏幕半径（px），用于平移边界约束；在 content 作用域内才能访问 maxWidth/maxHeight
            val screenW = maxWidth
            val maxOffsetX = with(density) { (scale - 1f) * maxWidth.toPx() / 2f }
            val maxOffsetY = with(density) { (scale - 1f) * maxHeight.toPx() / 2f }

            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(rootFocus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        lastInteractionTick++
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter -> {
                                if (carouselVisible) {
                                    selectImage(cursorIndex)
                                } else {
                                    zoomTo(if (scale > 1f) 1f else 2f)
                                }
                                true
                            }
                            Key.Menu -> {
                                // 循环缩放：1 → 1.5 → 2 → 3 → 1
                                val next = when {
                                    scale < 1.5f -> 1.5f
                                    scale < 2f -> 2f
                                    scale < 3f -> 3f
                                    else -> 1f
                                }
                                zoomTo(next)
                                true
                            }
                            Key.DirectionLeft -> {
                                when {
                                    scale > 1f -> offset = Offset(
                                        (offset.x - 120f).coerceIn(-maxOffsetX, maxOffsetX), offset.y
                                    )
                                    carouselVisible -> { // 取景框不动，光标左移（起点钳制留白）
                                        cursorIndex = (cursorIndex - 1).coerceAtLeast(0)
                                    }
                                    else -> switchImage(-1)
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                when {
                                    scale > 1f -> offset = Offset(
                                        (offset.x + 120f).coerceIn(-maxOffsetX, maxOffsetX), offset.y
                                    )
                                    carouselVisible -> { // 光标右移（终点钳制留白）
                                        cursorIndex = (cursorIndex + 1).coerceAtMost(images.lastIndex)
                                    }
                                    else -> switchImage(1)
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                when {
                                    scale > 1f -> offset = Offset(
                                        offset.x, (offset.y - 120f).coerceIn(-maxOffsetY, maxOffsetY)
                                    )
                                    carouselVisible -> hideCarousel() // 轮播唤出时 ↑ 收起
                                    else -> overlayVisible = !overlayVisible // ↑ 全屏 ↔ 恢复顶栏
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                when {
                                    scale > 1f -> offset = Offset(
                                        offset.x, (offset.y + 120f).coerceIn(-maxOffsetY, maxOffsetY)
                                    )
                                    carouselVisible -> Unit // 已进入取景框模式，原地不动
                                    else -> {
                                        // ↓ 一次：唤出同目录缩略图轮播并进入取景框模式
                                        cursorIndex = index
                                        carouselVisible = true
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    }
            ) {
            images.getOrNull(index)?.let { image ->
                AsyncImage(
                    model = image.toUri(),
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            }

            if (overlayVisible) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))
                        )
                        .padding(horizontal = 40.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1} / ${images.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        images.getOrNull(index)?.name ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.weight(1f))
                    if (scale > 1f) {
                        Text(
                            "${scale}x",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // —— 底部同目录缩略图轮播（固定取景框模式）——
            if (carouselVisible) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color(0x99000000))
                        .zIndex(1f)
                ) {
                    // 前后各留「半屏 - 半个缩略图」的空白，让任意一张都能滚到正中央
                    val edgePad = (screenW - (STRIP_HPAD * 2).dp - THUMB_SIZE.dp) / 2
                    Row(
                        Modifier
                            // 焦点节点必须在滚动容器外侧：若在内侧，requestFocus 会触发
                            // bring-into-view 把整条 Row 滚回起点，打断初始居中定位
                            .focusRequester(stripFocus)
                            .focusable()
                            .padding(
                                horizontal = STRIP_HPAD.dp,
                                vertical = STRIP_VPAD.dp
                            )
                            .horizontalScroll(scrollState, enabled = false) // 仅程序化滚动
                    ) {
                        Spacer(Modifier.width(edgePad))
                        images.forEachIndexed { i, img ->
                            val isCurrent = i == index
                            AsyncImage(
                                model = img.toUri(),
                                contentDescription = img.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .padding(horizontal = (THUMB_GAP / 2).dp)
                                    .width(THUMB_SIZE.dp)
                                    .height(THUMB_SIZE.dp)
                                    .border(
                                        width = if (isCurrent) 3.dp else 1.dp,
                                        color = if (isCurrent) Color.White else Color(0x33FFFFFF)
                                    )
                            )
                        }
                        Spacer(Modifier.width(edgePad))
                    }
                    // 固定取景框：恒定居中，不随缩略图移动
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .width((THUMB_SIZE + 14).dp)
                            .height((THUMB_SIZE + 14).dp)
                            .border(3.dp, PrimaryBlue)
                    )
                }
            }
            } // 内层焦点 Box 结束
        }
    }
}
