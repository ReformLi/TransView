package com.hpu.transview.ui.upload

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hpu.transview.data.UploadRecordRepository
import com.hpu.transview.data.UploadStateCode
import com.hpu.transview.data.db.UploadRecordEntity
import com.hpu.transview.model.ServerMode
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.ServerController
import com.hpu.transview.ui.common.TvButton
import com.hpu.transview.ui.common.TypeBadge
import com.hpu.transview.ui.common.tvFocus
import com.hpu.transview.ui.theme.DangerRed
import com.hpu.transview.ui.theme.OnDarkDim
import com.hpu.transview.ui.theme.SuccessGreen
import com.hpu.transview.util.Constants
import com.hpu.transview.util.FileUtils
import com.hpu.transview.util.NetUtils
import com.hpu.transview.util.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 上传页：宽屏左右分栏 —— 左侧固定区（二维码 + 地址 + 服务器状态，不滚动），
 * 右侧为数据库驱动的上传记录列表（可滚动）。
 * 切换标签不中断上传（上传在 HTTP 服务器线程进行），记录经 Room Flow 实时刷新。
 */
@Composable
fun UploadScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var ip by remember { mutableStateOf(NetUtils.getLocalIpAddress()) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    val running by ServerBus.running.collectAsState()
    val hibernated by ServerBus.hibernated.collectAsState()
    val mode by ServerBus.mode.collectAsState()

    val recordsRepo = remember { UploadRecordRepository(context) }
    val records by recordsRepo.observeRecent().collectAsState(initial = emptyList())

    var pendingDelete by remember { mutableStateOf<UploadRecordEntity?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    // 网络可能在后台变化（Wi-Fi 重连等），回到前台时刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ip = NetUtils.getLocalIpAddress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ip) {
        qr = if (ip != null) {
            withContext(Dispatchers.Default) {
                QrCode.generate("http://$ip:${Constants.PORT}", 480)
            }
        } else null
    }

    // 宽屏左右分栏：左侧固定区（二维码 + 地址 + 服务器状态）不滚动，右侧上传记录可滚动
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // ——— 左侧固定区：二维码 + 地址 + 服务器状态 ———
        ServerPanel(
            modifier = Modifier
                .weight(0.9f)
                .fillMaxHeight(),
            running = running,
            hibernated = hibernated,
            mode = mode,
            qr = qr,
            ip = ip
        )

        // ——— 右侧：上传记录（可滚动） ———
        Column(
            Modifier
                .weight(2f)
                .fillMaxHeight()
        ) {
            // 列表头：标题 + 清空入口
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "上传记录",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (records.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "共 ${records.size} 条",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDarkDim
                    )
                }
                Spacer(Modifier.weight(1f))
                if (records.isNotEmpty()) {
                    TvButton("清空所有记录") { showClearAll = true }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 主体：纯上传记录列表
            if (records.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无上传记录\n手机扫码或输入左侧地址即可开始传输",
                        style = MaterialTheme.typography.titleMedium,
                        color = OnDarkDim,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(records, key = { it.id }) { record ->
                        UploadRecordRow(
                            record = record,
                            onDelete = { pendingDelete = record }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    // ——— 删除确认（仅删数据库记录，本地文件保留） ———
    pendingDelete?.let { record ->
        ConfirmDialog(
            title = "删除上传记录",
            message = "确定删除「${record.fileName}」的上传记录吗？\n仅删除历史日志，本地文件将保留。",
            confirmText = "删除记录",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                val target = record
                pendingDelete = null
                coroutineScope.launch {
                    recordsRepo.deleteById(target.id)
                    Toast.makeText(context, "已删除记录，本地文件已保留", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // ——— 清空确认 ———
    if (showClearAll) {
        ConfirmDialog(
            title = "清空所有记录",
            message = "确定清空全部 ${records.size} 条上传记录吗？\n仅删除历史日志，本地文件将全部保留。",
            confirmText = "全部清空",
            onDismiss = { showClearAll = false },
            onConfirm = {
                showClearAll = false
                coroutineScope.launch {
                    recordsRepo.clearAll()
                    Toast.makeText(context, "已清空所有记录，本地文件均已保留", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

// ————————————————— 左侧固定区：二维码 + 地址 + 服务器状态 —————————————————

@Composable
private fun ServerPanel(
    modifier: Modifier,
    running: Boolean,
    hibernated: Boolean,
    mode: ServerMode,
    qr: Bitmap?,
    ip: String?
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        if (running) {
            // ——— 运行中：二维码 + 地址 + 复制 + 提示 ———
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "手机扫码上传",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(18.dp))

                qr?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "上传地址二维码",
                        modifier = Modifier
                            .size(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                } ?: Box(
                    Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )

                Spacer(Modifier.height(20.dp))

                val address = if (ip != null) "http://$ip:${Constants.PORT}" else null
                if (address != null) {
                    Text(
                        address,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(10.dp))
                    CopyAddressButton(address)
                } else {
                    Text(
                        "无法获取网络地址\n请检查网络连接",
                        style = MaterialTheme.typography.titleMedium,
                        color = DangerRed,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    "手机与电视连接同一 Wi-Fi，\n扫码或输入上方地址即可上传",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnDarkDim,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前模式：${mode.label}（右上角「设置」可切换）",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkDim,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // ——— 未运行：状态 + 唤醒/启动 ———
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                val (title, desc) = when {
                    mode == ServerMode.POWER_SAVER ->
                        "服务器未启动" to "省电模式：需要上传时点击下方按钮启动"
                    hibernated ->
                        "服务器已休眠" to "15 分钟无上传，已自动休眠省电"
                    else ->
                        "服务器已暂停" to "正在播放视频或屏幕休眠，结束后自动恢复"
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = OnDarkDim,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnDarkDim,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(26.dp))
                if (mode == ServerMode.POWER_SAVER) {
                    TvButton("启动服务器") { ServerController.wake() }
                } else if (hibernated) {
                    TvButton("唤醒服务器") { ServerController.wake() }
                }
            }
        }
    }
}

// ————————————————— 记录行 —————————————————

/** 状态码 → 显示文案 */
private fun stateLabel(state: Int): String = when (state) {
    UploadStateCode.WAITING -> "等待中"
    UploadStateCode.RUNNING -> "上传中"
    UploadStateCode.SUCCESS -> "成功"
    else -> "失败"
}

@Composable
private fun stateColor(state: Int): Color = when (state) {
    UploadStateCode.WAITING -> OnDarkDim
    UploadStateCode.RUNNING -> MaterialTheme.colorScheme.primary
    UploadStateCode.SUCCESS -> SuccessGreen
    else -> DangerRed
}

private fun categoryLabel(category: Int): String = when (category) {
    0 -> "视频"
    1 -> "图片"
    else -> "其他"
}

@Composable
private fun UploadRecordRow(record: UploadRecordEntity, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .tvFocus()
            .focusable()
            // 菜单键 / 删除键 → 删除该条记录（焦点在本行时）
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Menu || event.key == Key.Delete)
                ) {
                    onDelete()
                    true
                } else {
                    false
                }
            }
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(
                isDirectory = false,
                isVideo = record.category == 0,
                isImage = record.category == 1,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (record.fileSize > 0) append(FileUtils.formatSize(record.fileSize))
                        append("　").append(categoryLabel(record.category))
                        append("　").append(FileUtils.formatDate(record.time))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkDim
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stateLabel(record.state),
                color = stateColor(record.state),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.width(14.dp))
            TvButton("删除", onClick = onDelete)
        }

        // 上传中：进度条 + 百分比
        if (record.state == UploadStateCode.RUNNING) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { record.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.weight(1f).height(5.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${record.progress.coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ————————————————— 通用确认对话框 —————————————————

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier
                    .padding(28.dp)
                    .width(420.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(confirmText, onClick = onConfirm)
                    TvButton("取消", onClick = onDismiss)
                }
            }
        }
    }
}

// ————————————————— 复制地址按钮 —————————————————

/** 复制地址按钮：图标 + 点击复制到剪贴板，Toast 确认 */
@Composable
private fun CopyAddressButton(address: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Row(
        Modifier
            .tvFocus(cornerRadius = 8)
            .clickable {
                clipboard.setText(AnnotatedString(address))
                copied = true
                Toast.makeText(context, "地址已复制：$address", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CopyIcon(
            tint = if (copied) SuccessGreen else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            if (copied) "已复制" else "复制",
            style = MaterialTheme.typography.bodyLarge,
            color = if (copied) SuccessGreen else MaterialTheme.colorScheme.primary
        )
    }
}

/** 手绘复制图标（两张叠角卡片） */
@Composable
private fun CopyIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(
            width = size.width / 9f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        // 背后一张卡片（左上）
        drawRoundRect(
            color = tint,
            style = stroke,
            topLeft = Offset(size.width * 0.08f, size.height * 0.08f),
            size = Size(size.width * 0.55f, size.height * 0.55f),
            cornerRadius = CornerRadius(size.width * 0.08f)
        )
        // 前面一张卡片（右下）
        drawRoundRect(
            color = tint,
            style = stroke,
            topLeft = Offset(size.width * 0.37f, size.height * 0.37f),
            size = Size(size.width * 0.55f, size.height * 0.55f),
            cornerRadius = CornerRadius(size.width * 0.08f)
        )
    }
}
