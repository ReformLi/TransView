package com.hpu.transview.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.hpu.transview.model.Category
import com.hpu.transview.model.MainTab
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.ServerController
import com.hpu.transview.ui.library.LibraryScreen
import com.hpu.transview.ui.settings.ServerModeDialog
import com.hpu.transview.ui.common.tvFocus
import com.hpu.transview.ui.theme.OnDarkDim
import com.hpu.transview.ui.theme.SuccessGreen
import com.hpu.transview.ui.theme.DangerRed
import com.hpu.transview.ui.upload.UploadScreen

/** 主界面：顶部四标签导航 + 内容区 */
@Composable
fun MainScreen() {
    var selected by rememberSaveable { mutableStateOf(MainTab.UPLOAD) }
    var showSettings by remember { mutableStateOf(false) }
    val tabFocusRequesters = remember { MainTab.entries.map { FocusRequester() } }

    // 省电模式：仅上传页可见时允许服务器运行
    LaunchedEffect(selected) {
        ServerController.setUploadPageVisible(selected == MainTab.UPLOAD)
    }

    // 返回键：先交给内容区（媒体库在子目录时返回上一级，它注册的 BackHandler 优先级更高），
    // 内容区不处理时回到顶部导航栏。
    // 必须用 BackHandler 而非 Modifier.onKeyEvent —— onKeyEvent 只在焦点路径上才收得到事件，
    // 从播放页返回后内容区焦点为空时会漏掉返回键，Activity 被系统直接 finish（表现为「返回键退出 App」）。
    BackHandler {
        runCatching { tabFocusRequesters[selected.ordinal].requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部导航栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 40.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "传视TV",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(28.dp))
            MainTab.entries.forEachIndexed { index, tab ->
                TabChip(
                    tab = tab,
                    selected = tab == selected,
                    modifier = Modifier
                        .focusRequester(tabFocusRequesters[index])
                        .onFocusChanged { if (it.isFocused && tab != selected) selected = tab }
                ) { selected = tab }
                Spacer(Modifier.width(14.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                "设置",
                style = MaterialTheme.typography.titleMedium,
                color = OnDarkDim,
                modifier = Modifier
                    .tvFocus()
                    .clickable { showSettings = true }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            )
            Spacer(Modifier.width(20.dp))
            ServerStatusBadge()
        }

        // 内容区（返回键见上面的 BackHandler）
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selected) {
                MainTab.UPLOAD -> UploadScreen()
                MainTab.VIDEO -> LibraryScreen(Category.VIDEO)
                MainTab.IMAGE -> LibraryScreen(Category.IMAGE)
                MainTab.OTHER -> LibraryScreen(Category.OTHER)
            }
        }

        if (showSettings) {
            ServerModeDialog(onDismiss = { showSettings = false })
        }
    }
}

/** 导航标签：聚焦即选中（遥控器左右切换） */
@Composable
private fun TabChip(tab: MainTab, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    selected -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                }
            )
            .border(
                width = 1.dp,
                color = if (selected || focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(50)
            )
            .clickable { onClick() }
            .padding(horizontal = 30.dp, vertical = 10.dp)
    ) {
        Text(
            tab.title,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) MaterialTheme.colorScheme.onPrimary
            else if (selected) MaterialTheme.colorScheme.primary
            else OnDarkDim
        )
    }
}

/** 右上角服务器状态徽标（运行状态 + 当前模式） */
@Composable
private fun ServerStatusBadge() {
    val running by ServerBus.running.collectAsState()
    val mode by ServerBus.mode.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (running) SuccessGreen else DangerRed)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (running) "服务器运行中" else "服务器已停止",
            style = MaterialTheme.typography.bodyMedium,
            color = if (running) SuccessGreen else DangerRed
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "· ${mode.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = OnDarkDim
        )
    }
}
