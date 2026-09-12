package com.hpu.transview.ui.image

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.hpu.transview.ui.theme.TransViewTheme
import com.hpu.transview.util.isImageFile
import com.hpu.transview.util.naturalCompare
import androidx.core.net.toUri
import java.io.File

/**
 * 图片查看器：
 * - 全屏展示，遥控器中键缩放（1x ↔ 2x），+/- 或菜单键循环缩放
 * - 缩放时方向键平移，未缩放时左右键切换上一张/下一张（自然排序）
 */
class ImageViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        private const val MAX_SCALE = 5f
        private const val ZOOM_STEP = 1f
    }

    private var images: List<File> = emptyList()
    private var startIndex = 0

    // —— Compose 状态 ——
    var index by mutableStateOf(0)
    var scale by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var overlayVisible by mutableStateOf(true)
    var lastInteractionTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val path = intent.getStringExtra(EXTRA_PATH)
        val file = if (path != null) File(path) else null
        if (file == null || !file.isFile) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        images = file.parentFile
            ?.listFiles()
            ?.filter { it.isFile && it.isImageFile() }
            ?.sortedWith { a, b -> naturalCompare(a.name, b.name) }
            ?: listOf(file)
        startIndex = images.indexOfFirst { it.absolutePath == file.absolutePath }.takeIf { it >= 0 } ?: 0
        index = startIndex

        setContent {
            TransViewTheme {
                ViewerScreen()
            }
        }
    }

    private fun switchImage(delta: Int) {
        val next = index + delta
        if (next in images.indices) {
            index = next
            scale = 1f
            offset = Offset.Zero
            overlayVisible = true
        }
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
        val density = androidx.compose.ui.platform.LocalDensity.current

        LaunchedEffect(overlayVisible, lastInteractionTick) {
            if (overlayVisible) {
                kotlinx.coroutines.delay(4000)
                overlayVisible = false
            }
        }
        LaunchedEffect(overlayVisible) {
            if (!overlayVisible) runCatching { rootFocus.requestFocus() }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 屏幕半径（px），用于平移边界约束；在 content 作用域内才能访问 maxWidth/maxHeight
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
                                zoomTo(if (scale > 1f) 1f else 2f)
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
                                if (scale <= 1f) switchImage(-1)
                                else offset = Offset(
                                    (offset.x - 120f).coerceIn(-maxOffsetX, maxOffsetX), offset.y
                                )
                                true
                            }
                            Key.DirectionRight -> {
                                if (scale <= 1f) switchImage(1)
                                else offset = Offset(
                                    (offset.x + 120f).coerceIn(-maxOffsetX, maxOffsetX), offset.y
                                )
                                true
                            }
                            Key.DirectionUp -> {
                                if (scale > 1f) offset = Offset(
                                    offset.x, (offset.y - 120f).coerceIn(-maxOffsetY, maxOffsetY)
                                )
                                true
                            }
                            Key.DirectionDown -> {
                                if (scale > 1f) offset = Offset(
                                    offset.x, (offset.y + 120f).coerceIn(-maxOffsetY, maxOffsetY)
                                )
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
            } // 内层焦点 Box 结束
        }
    }
}
