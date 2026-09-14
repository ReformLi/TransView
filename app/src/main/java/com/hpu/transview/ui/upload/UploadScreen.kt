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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.hpu.transview.ui.common.requestFocusNextFrame
import com.hpu.transview.ui.common.tvFocus
import com.hpu.transview.ui.settings.SettingsStore
import com.hpu.transview.ui.theme.DangerRed
import com.hpu.transview.ui.theme.OnDarkDim
import com.hpu.transview.ui.theme.SuccessGreen
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
 *
 * 上下键层级（与媒体库一致）：记录列表**第一行**按上键 → 列表头的「清空所有记录」（本页工具条），
 * 再按上键 → 顶部导航栏的当前标签（上传页即「上传」）；标签按下键 → 直接回到记录第一行。
 * 必须显式指定：记录区在屏幕右侧，其正上方就是导航栏右上角的「设置」，
 * 交给 Compose 默认就近搜索会把焦点送进「设置」，而不是回到本页标签。
 */
@Composable
fun UploadScreen(
    onFocusTabs: () -> Unit = {},
    focusListTicket: Int = 0
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    SettingsStore.init(context)

    var ip by remember { mutableStateOf(NetUtils.getLocalIpAddress()) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    val running by ServerBus.running.collectAsState()
    val hibernated by ServerBus.hibernated.collectAsState()
    val mode by ServerBus.mode.collectAsState()
    // 监听端口来自设置（设置页改端口后经总线即时同步）；二维码与地址必须跟着变，
    // 否则会显示一个已经没人监听的旧端口
    val port by ServerBus.port.collectAsState()

    val recordsRepo = remember { UploadRecordRepository(context) }
    val records by recordsRepo.observeRecent().collectAsState(initial = emptyList())

    var pendingDelete by remember { mutableStateOf<UploadRecordEntity?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    // 本页「工具条」的焦点入口 = 列表头的「清空所有记录」按钮。
    // 记录列表第一行按「上键」落到这里，再按一次「上键」才把焦点交给顶部导航栏的「上传」标签。
    val clearAllFocus = remember { FocusRequester() }

    // ——— 焦点安全港（与媒体库同机制，见 LibraryScreen 的 parkFocusSafe）———
    // 记录区（含行内「删除」按钮）是否持有焦点
    var listHasFocus by remember { mutableStateOf(false) }
    // 「焦点过渡停靠中」：删除后焦点先停靠在「清空所有记录」上（1~2 帧后由下一条记录抢回），
    // 停靠期间抑制该按钮的聚焦高亮，避免「按钮闪一下再跳到记录行」的感官跳动
    var focusParking by remember { mutableStateOf(false) }
    // 待聚焦的记录 id（删除后要落到的下一条记录）；落定后置空
    var pendingFocusId by remember { mutableStateOf<Long?>(null) }
    // 打开删除确认框的**那一刻**列表是否持有焦点。
    // 必须在触发删除时捕获：确认框会取走焦点，等回到 onConfirm 再读就永远是 false（实测踩过）。
    var deleteHadFocus by remember { mutableStateOf(false) }

    // 顶部标签按「下键」→ 直接聚焦记录第一行（见 MainScreen.contentFocusTicket）。
    // consumedFocusTicket 初始化为当前值：切标签进来会重建本页组合，
    // 若从 0 开始会让 LaunchedEffect 立刻跑一次抢走焦点，用户就没法继续按 → 切下一个标签。
    var consumedFocusTicket by remember { mutableIntStateOf(focusListTicket) }
    var focusFirstRow by remember { mutableStateOf(false) }
    LaunchedEffect(focusListTicket) {
        if (focusListTicket == consumedFocusTicket) return@LaunchedEffect
        consumedFocusTicket = focusListTicket
        if (records.isNotEmpty()) focusFirstRow = true
    }

    // 停靠超时兜底：下一条记录正常会在 1~2 帧内抢回焦点并清除标记；
    // 若抢回失败（如记录被并发清空），800ms 后恢复按钮的聚焦高亮，避免它永远不亮
    LaunchedEffect(focusParking) {
        if (focusParking) {
            kotlinx.coroutines.delay(800)
            focusParking = false
        }
    }

    // 网络可能在后台变化（Wi-Fi 重连等），回到前台时刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ip = NetUtils.getLocalIpAddress()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ip, port) {
        qr = if (ip != null) {
            withContext(Dispatchers.Default) {
                QrCode.generate("http://$ip:$port", 480)
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
            ip = ip,
            port = port
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
                    TvButton(
                        "清空所有记录",
                        modifier = Modifier
                            .focusRequester(clearAllFocus)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    // 工具条再按「上键」→ 顶部导航栏的「上传」标签
                                    Key.DirectionUp -> { onFocusTabs(); true }
                                    // 最右端按「右」：焦点留在原地（否则会环绕到顶部导航栏的标签上误切页）
                                    Key.DirectionRight -> true
                                    else -> false
                                }
                            },
                        // 删除后焦点在此过渡停靠时不显示高亮（否则会有「按钮闪一下」的感官跳动）
                        showFocusVisual = !focusParking
                    ) { showClearAll = true }
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
                    Modifier
                        .weight(1f)
                        // hasFocus = 记录区自身或其中任一行（含行内「删除」按钮）持有焦点。
                        // 删除前据此决定是否需要「焦点安全港」：遥控器路径必须先停靠，
                        // touch 路径焦点本来为空时可跳过。
                        .onFocusChanged { listHasFocus = it.hasFocus },
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(records, key = { _, record -> record.id }) { index, record ->
                        UploadRecordRow(
                            record = record,
                            isFirstRow = index == 0,
                            autoFocus = (focusFirstRow && index == 0) || record.id == pendingFocusId,
                            onAutoFocused = {
                                focusFirstRow = false
                                pendingFocusId = null
                                focusParking = false
                            },
                            // 第一行按「上键」→ 列表头的「清空所有记录」；该按钮始终存在（有记录时），
                            // 请求失败则直接回导航栏「上传」标签，避免掉进右上角的「设置」。
                            onNavigateUp = {
                                runCatching { clearAllFocus.requestFocus() }
                                    .onFailure { onFocusTabs() }
                                true
                            },
                            // 删除前先记下列表是否持有焦点（确认框会取走焦点，见 deleteHadFocus 注释）
                            onDelete = {
                                deleteHadFocus = listHasFocus
                                pendingDelete = record
                            }
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
                // 被删的这条记录马上会被移出组合。若焦点正持有在记录区里，Compose 无法把焦点
                // 交还给一个已消失的行，会回退到整棵树第一个可聚焦元素 —— 顶部「上传」标签
                // （媒体库删除卡片是同一机制，见 LibraryScreen 的「焦点安全港」）。
                // 修法：先把焦点停靠到列表头的「清空所有记录」上，再由「下一条记录」抢回。
                // 目标是下一条；已是最后一条则退到上一条；列表即将清空时没有可落点，
                // 交给自然回退（页面仍是「上传」页，不会切走）。
                val visible = records
                val index = visible.indexOfFirst { it.id == target.id }
                val nextId = when {
                    index < 0 -> null
                    index + 1 < visible.size -> visible[index + 1].id
                    index - 1 >= 0 -> visible[index - 1].id
                    else -> null
                }
                if (deleteHadFocus && nextId != null) {
                    focusParking = true
                    runCatching { clearAllFocus.requestFocus() }
                    pendingFocusId = nextId
                }
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
    ip: String?,
    port: Int
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

                val address = if (ip != null) "http://$ip:$port" else null
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
                // 注意：本行在 720p 电视上会被面板高度裁掉（二维码 180dp 撑满，属既有问题，
                // 与设备名/端口无关）。端口信息在上方地址里已可见，设备名在手机端网页展示，
                // 因此这里不新增行（新增行只会更容易被裁）。
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
private fun UploadRecordRow(
    record: UploadRecordEntity,
    isFirstRow: Boolean,
    autoFocus: Boolean,
    onAutoFocused: () -> Unit,
    onNavigateUp: () -> Boolean,
    onDelete: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocusNextFrame()
            onAutoFocused()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .tvFocus()
            // focusRequester 必须在 focusable **之前**，否则关联不到本行的焦点目标，
            // 程序化请求会落到行内的「删除」按钮上（实测）。
            .focusRequester(focusRequester)
            .focusable()
            // 菜单键 / 删除键 → 删除该条记录；第一行按「上键」→ 本页工具条
            // （preview 阶段从祖先到焦点，焦点落在行内「删除」按钮时同样生效）
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Menu || event.key == Key.Delete -> {
                        onDelete()
                        true
                    }
                    isFirstRow && event.key == Key.DirectionUp -> onNavigateUp()
                    // 记录行已撑满记录区宽度，右侧没有可聚焦元素：吃掉右键让焦点留在原地。
                    // 否则 Compose 的二维搜索失败后会环绕到顶部导航栏的标签，触发「聚焦即选中」把页面切走。
                    event.key == Key.DirectionRight -> true
                    else -> false
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
