package com.hpu.transview.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hpu.transview.ui.theme.FolderAmber
import com.hpu.transview.ui.theme.ImageGreen
import com.hpu.transview.ui.theme.OtherGray
import com.hpu.transview.ui.theme.VideoBlue

/** 文件类型图标（Canvas 手绘，避免引入额外图标库） */
@Composable
fun FileTypeIcon(
    isDirectory: Boolean,
    isVideo: Boolean,
    isImage: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp
) {
    val tint = when {
        isDirectory -> FolderAmber
        isVideo -> VideoBlue
        isImage -> ImageGreen
        else -> OtherGray
    }
    Canvas(modifier = modifier.size(iconSize)) {
        val stroke = Stroke(width = size.width / 12f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        when {
            isDirectory -> {
                // 文件夹
                drawRoundRect(
                    color = tint, style = stroke,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.22f),
                    size = Size(size.width * 0.76f, size.height * 0.58f),
                    cornerRadius = CornerRadius(size.width * 0.08f)
                )
                drawLine(
                    color = tint, strokeWidth = stroke.width,
                    start = Offset(size.width * 0.12f, size.height * 0.38f),
                    end = Offset(size.width * 0.88f, size.height * 0.38f)
                )
            }
            isVideo -> {
                // 播放三角
                val path = Path().apply {
                    moveTo(size.width * 0.32f, size.height * 0.22f)
                    lineTo(size.width * 0.78f, size.height * 0.5f)
                    lineTo(size.width * 0.32f, size.height * 0.78f)
                    close()
                }
                drawPath(path, color = tint)
            }
            isImage -> {
                // 山与太阳
                drawCircle(
                    color = tint,
                    radius = size.width * 0.1f,
                    center = Offset(size.width * 0.32f, size.height * 0.32f)
                )
                val path = Path().apply {
                    moveTo(size.width * 0.15f, size.height * 0.8f)
                    lineTo(size.width * 0.42f, size.height * 0.45f)
                    lineTo(size.width * 0.62f, size.height * 0.68f)
                    lineTo(size.width * 0.75f, size.height * 0.55f)
                    lineTo(size.width * 0.88f, size.height * 0.8f)
                    close()
                }
                drawPath(path, color = tint)
            }
            else -> {
                // 文档
                drawRoundRect(
                    color = tint, style = stroke,
                    topLeft = Offset(size.width * 0.24f, size.height * 0.12f),
                    size = Size(size.width * 0.52f, size.height * 0.76f),
                    cornerRadius = CornerRadius(size.width * 0.06f)
                )
                drawLine(
                    color = tint, strokeWidth = stroke.width * 0.7f,
                    start = Offset(size.width * 0.36f, size.height * 0.38f),
                    end = Offset(size.width * 0.64f, size.height * 0.38f)
                )
                drawLine(
                    color = tint, strokeWidth = stroke.width * 0.7f,
                    start = Offset(size.width * 0.36f, size.height * 0.52f),
                    end = Offset(size.width * 0.64f, size.height * 0.52f)
                )
            }
        }
    }
}

/** 图标 + 底色容器 */
@Composable
fun TypeBadge(
    isDirectory: Boolean,
    isVideo: Boolean,
    isImage: Boolean,
    modifier: Modifier = Modifier,
    iconSize: Dp = 32.dp
) {
    val bg = when {
        isDirectory -> FolderAmber.copy(alpha = 0.15f)
        isVideo -> VideoBlue.copy(alpha = 0.15f)
        isImage -> ImageGreen.copy(alpha = 0.15f)
        else -> OtherGray.copy(alpha = 0.15f)
    }
    Box(
        modifier = modifier.background(bg, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        FileTypeIcon(isDirectory, isVideo, isImage, iconSize = iconSize)
    }
}
