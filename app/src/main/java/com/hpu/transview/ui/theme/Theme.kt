package com.hpu.transview.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val TvColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = PrimaryDark,
    secondary = FolderAmber,
    background = BgDark,
    onBackground = OnDark,
    surface = SurfaceDark,
    onSurface = OnDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnDark,
    error = DangerRed
)

/** 电视恒定深色主题（弱光环境观看友好） */
@Composable
fun TransViewTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TvColorScheme,
        typography = Typography
    ) {
        // 深色主题下提供浅色默认文字色。
        // MaterialTheme 本身不设置 LocalContentColor，其默认值为纯黑 Color.Black；
        // 若不给整个树兜底，任何未显式指定 color 的 Text 都会黑字画在深色背景上"隐身"
        // （表现为文件名/标题等一片空白）。此处统一兜底为 onBackground。
        CompositionLocalProvider(
            LocalContentColor provides TvColorScheme.onBackground
        ) {
            content()
        }
    }
}
