package com.hpu.transview.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hpu.transview.BuildConfig
import com.hpu.transview.data.PlaybackRepository
import com.hpu.transview.data.UploadRecordRepository
import com.hpu.transview.data.sync.SyncManager
import com.hpu.transview.model.AspectRatio
import com.hpu.transview.model.ServerMode
import com.hpu.transview.model.SortOrder
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.ServerController
import com.hpu.transview.ui.common.OptionRow
import com.hpu.transview.ui.common.TvButton
import com.hpu.transview.ui.common.requestFocusNextFrame
import com.hpu.transview.ui.common.tvFocus
import com.hpu.transview.ui.theme.DangerRed
import com.hpu.transview.ui.theme.OnDarkDim
import com.hpu.transview.util.FileLocations
import com.hpu.transview.util.FileUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页分组 */
enum class SettingGroup(val title: String) {
    SERVER("服务器与网络"),
    PLAY("播放设置"),
    UI("界面设置"),
    STORAGE("存储与数据"),
    ABOUT("关于")
}

/** 单选弹框数据 */
private data class ChoiceState(
    val title: String,
    val options: List<String>,
    val selectedIndex: Int,
    val onPick: (Int) -> Unit
)

/** 确认弹框数据 */
private data class ConfirmState(
    val title: String,
    val message: String,
    val confirmText: String,
    val destructive: Boolean = false,
    val onConfirm: () -> Unit
)

/** 纯展示弹框数据（版本信息 / 开源许可 / 致谢名单） */
private data class InfoState(val title: String, val body: String)

/** 常见可选端口（电视遥控器无键盘，用预设免输入） */
private val PORT_OPTIONS = listOf("8080", "8081", "8089", "9000")

/** 常见设备名候选 */
private val DEVICE_NAMES = listOf("传视TV", "客厅电视", "卧室电视", "书房电视", "主卧电视")

/** 默认倍速候选项 */
private val SPEED_OPTIONS = listOf("1.0x", "1.25x", "1.5x")

private const val MIT_LICENSE = "MIT License\n\n" +
    "Copyright (c) 2026 TransView 传视TV\n\n" +
    "Permission is hereby granted, free of charge, to any person obtaining a copy " +
    "of this software and associated documentation files (the \"Software\"), to deal " +
    "in the Software without restriction, including without limitation the rights " +
    "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies " +
    "of the Software, and to permit persons to whom the Software is furnished to do so, " +
    "subject to the following conditions:\n\n" +
    "The above copyright notice and this permission notice shall be included in all " +
    "copies or substantial portions of the Software.\n\n" +
    "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, " +
    "INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A " +
    "PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT " +
    "HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION " +
    "OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE " +
    "SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."

private const val CREDITS =
    "本项目独立设计、借助 AI 辅助生成。交互设计参考了以下开源项目的优秀理念，特此致谢：\n\n" +
        "· 阳光快传（SunshineSend）——「零手机端安装、扫码上传」的轻量交互思路\n" +
        "· Kodi —— 媒体中心、网格卡片、播放历史的设计理念\n" +
        "· Ghosten Player —— 媒体中心、网格卡片、播放历史的设计理念\n\n" +
        "本项目未直接复制任何第三方项目源码。"

/** 计算 /sdcard/TransView/ 沙盒已用空间（IO 线程调用） */
private fun sandboxSizeText(): String = runCatching {
    val total = FileLocations.sandboxRoot.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }
    FileUtils.formatSize(total)
}.getOrDefault("未知")

private fun speedLabel(speed: Float): String = when (speed) {
    1.25f -> "1.25x"
    1.5f -> "1.5x"
    else -> "1.0x"
}

/**
 * 设置子页面：左侧分组 + 右侧详情，占据内容区（顶部导航栏保持不变）。
 *
 * 焦点规范（与媒体库/上传页一致，电视端）：
 * - 页面打开（设置标签聚焦）时焦点保持在顶部「设置」标签上；按 ↓ 才进入左侧第一个分组。
 * - 左侧分组上下键切换分组；右键进入右侧详情列表；首分组按上回到「设置」标签。
 * - 右侧详情左键回到左侧当前分组；每组首行按上回到「设置」标签；点击某行弹框，关闭弹框焦点回到刚才那一行。
 * - 返回键先退出设置页，回到主界面。
 */
@Composable
fun SettingsScreen(
    onExit: () -> Unit,
    focusTicket: Int = 0,
    onFocusTabs: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SettingsStore.init(context)
    ServerController.init(context) // 确保保活策略持久化可用

    val mode by ServerBus.mode.collectAsState()

    var selectedGroupIndex by remember { mutableIntStateOf(0) }
    val groupFocusers = remember { List(SettingGroup.entries.size) { FocusRequester() } }
    val rowFocusMap = remember { mutableStateMapOf<String, FocusRequester>() }
    // 各行当前是否获得焦点，用于聚焦重试时确认落焦成功（见 detailTicket / dialog 关闭的 retry）
    val rowFocused = remember { mutableStateMapOf<String, Boolean>() }
    var detailTicket by remember { mutableIntStateOf(0) }

    // 三个弹框状态（同一时刻最多开一个）
    var choiceState by remember { mutableStateOf<ChoiceState?>(null) }
    var confirmState by remember { mutableStateOf<ConfirmState?>(null) }
    var infoState by remember { mutableStateOf<InfoState?>(null) }

    // 弹框关闭后把焦点还给打开它的那行
    var pendingFocusReturn by remember { mutableStateOf<String?>(null) }
    val dialogOpen = choiceState != null || confirmState != null || infoState != null
    var prevDialogOpen by remember { mutableStateOf(false) }
    LaunchedEffect(dialogOpen) {
        if (prevDialogOpen && !dialogOpen && pendingFocusReturn != null) {
            val key = pendingFocusReturn ?: return@LaunchedEffect
            // 帧门控重试：requestFocus 必须在帧回调内发起才生效，单次可能被静默丢弃
            // （媒体库/上传页实测经验），所以循环请求并用 rowFocused 确认落焦成功后退出。
            rowFocused[key] = false
            repeat(10) {
                rowFocusMap[key]?.requestFocusNextFrame()
                if (rowFocused[key] == true) return@repeat
            }
            pendingFocusReturn = null
        }
        prevDialogOpen = dialogOpen
    }

    // 存储空间占用（IO 计算）
    var storageLabel by remember { mutableStateOf("计算中…") }
    var storageComputing by remember { mutableStateOf(false) }
    val refreshStorage: () -> Unit = {
        scope.launch {
            storageComputing = true
            storageLabel = withContext(Dispatchers.IO) { sandboxSizeText() }
            storageComputing = false
        }
    }
    LaunchedEffect(Unit) { refreshStorage() }

    val uploadRepo = remember { UploadRecordRepository(context) }
    val playbackRepo = remember { PlaybackRepository(context) }
    val syncManager = remember { SyncManager.getInstance(context) }

    // 右侧每行的焦点入口（goToDetail 触发后请求当前分组第一行）
    val backToGroups: () -> Unit = { runCatching { groupFocusers[selectedGroupIndex].requestFocus() } }
    val goToDetail: (Int) -> Unit = { i ->
        selectedGroupIndex = i
        detailTicket++
    }
    LaunchedEffect(detailTicket) {
        if (detailTicket == 0) return@LaunchedEffect
        val key = "${SettingGroup.entries[selectedGroupIndex].title}:0"
        // 等待 rowFocusMap 被填充（右侧 SettingRow 渲染完成后才会设置）
        repeat(10) {
            if (rowFocusMap.containsKey(key)) {
                // 帧门控重试：与弹框关闭/媒体库首行聚焦一致，单次 requestFocusNextFrame 可能被
                // 静默丢弃（帧间隙请求不生效），改用循环 + rowFocused 确认成功。
                rowFocused[key] = false
                repeat(10) {
                    rowFocusMap[key]?.requestFocusNextFrame()
                    if (rowFocused[key] == true) return@LaunchedEffect
                }
                return@LaunchedEffect
            }
            delay(16) // 等待一帧
        }
    }

    val openChoice: (String, ChoiceState) -> Unit = { key, s -> pendingFocusReturn = key; choiceState = s }
    val openConfirm: (String, ConfirmState) -> Unit = { key, s -> pendingFocusReturn = key; confirmState = s }
    val openInfo: (String, InfoState) -> Unit = { key, s -> pendingFocusReturn = key; infoState = s }

    val pickIndex = { list: List<String>, current: String ->
        val i = list.indexOf(current)
        if (i >= 0) i else 0
    }

    BackHandler { onExit() }

    // 设置标签按 ↓ 时进入内容区并聚焦首个分组（与媒体页「标签按↓进首行」一致）；
    // 进入页面默认焦点保持在顶部「设置」标签上，不在这里抢焦点。
    LaunchedEffect(focusTicket) {
        if (focusTicket > 0) groupFocusers[0].requestFocusNextFrame()
    }

    // ————— 局部可调用组件（共享上面状态，避免大量传参） —————
    @Composable
    fun LeftGroup(idx: Int, title: String, selected: Boolean) {
        val lastIdx = SettingGroup.entries.size - 1
        Box(
            Modifier
                .fillMaxWidth()
                .focusRequester(groupFocusers[idx])
                .onFocusChanged { if (it.isFocused) selectedGroupIndex = idx }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        // 左侧分组左端按左：焦点留在原地（不环绕到顶部标签）
                        Key.DirectionLeft -> true
                        // 首个分组按上：回到顶部「设置」标签（与媒体页内容区按上回标签一致）
                        Key.DirectionUp -> { if (idx == 0) onFocusTabs(); idx == 0 }
                        // 末个分组按下：吃掉按键，防止环绕
                        Key.DirectionDown -> idx == lastIdx
                        // 右键进入右侧详情列表
                        Key.DirectionRight -> { goToDetail(idx); true }
                        else -> false
                    }
                }
                .tvFocus()
                .clickable { goToDetail(idx) }
                .padding(horizontal = 18.dp, vertical = 15.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp, 22.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    @Composable
    fun SettingRow(
        focusKey: String,
        label: String,
        value: String,
        pending: Boolean = false,
        onClick: () -> Unit
    ) {
        val requester = remember(focusKey) { FocusRequester() }
        rowFocusMap[focusKey] = requester
        // 分组首行：按上键吃掉，防止环绕到顶部标签把页面切走
        val topEdge = focusKey.endsWith(":0")
        Box(
            Modifier
                .fillMaxWidth()
                .focusRequester(requester)
                .onFocusChanged { rowFocused[focusKey] = it.isFocused }
                // clickable 必须在 onPreviewKeyEvent 之前：与媒体库网格项一致，
                // clickable 会让元素可聚焦并处理 Enter/Center 激活，onPreviewKeyEvent
                // 放在它之后可以拦截方向键（Left/Right/Up）而不影响点击激活
                .clickable(onClick = onClick)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        // 详情左端按左 → 回到左侧当前分组
                        Key.DirectionLeft -> { backToGroups(); true }
                        // 右键即「进入该项选值」：调出选值/确认/信息弹框（与遥控器确认键一致）；
                        // 同时因行内容已撑满右侧宽，按右也顺带防止环绕到右上角「设置」/标签
                        Key.DirectionRight -> { onClick(); true }
                        // 每组的首行按上 → 回到顶部「设置」标签（与媒体页内容区按上回标签一致）
                        Key.DirectionUp -> { if (topEdge) onFocusTabs(); topEdge }
                        else -> false
                    }
                }
                .tvFocus()
                .padding(horizontal = 22.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                if (pending) {
                    Text("待实现", style = MaterialTheme.typography.bodySmall, color = OnDarkDim)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (pending) OnDarkDim else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("▸", style = MaterialTheme.typography.bodyLarge, color = OnDarkDim)
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    // ————— 页面主体：左侧分组 + 右侧详情 —————
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 16.dp)
    ) {
        // 左侧分组
        Column(
            Modifier
                .width(280.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(18.dp))
            SettingGroup.entries.forEachIndexed { i, g ->
                LeftGroup(i, g.title, selected = selectedGroupIndex == i)
            }
        }

        Spacer(Modifier.width(28.dp))

        // 右侧详情
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                SettingGroup.entries[selectedGroupIndex].title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    val g = SettingGroup.entries[selectedGroupIndex]
                    when (g) {
                        SettingGroup.SERVER -> {
                            SettingRow("${g.title}:0", "保活策略", mode.label) {
                                openChoice("${g.title}:0", ChoiceState(
                                    "保活策略",
                                    ServerMode.entries.map { it.label },
                                    ServerMode.entries.indexOf(mode),
                                    { i -> ServerController.setMode(ServerMode.entries[i]) }
                                ))
                            }
                            SettingRow(
                                "${g.title}:1", "服务器端口",
                                "${SettingsStore.serverPort}", pending = true
                            ) {
                                openChoice("${g.title}:1", ChoiceState(
                                    "服务器端口（待实现）",
                                    PORT_OPTIONS,
                                    pickIndex(PORT_OPTIONS, SettingsStore.serverPort.toString()),
                                    { i -> SettingsStore.serverPort = PORT_OPTIONS[i].toInt() }
                                ))
                            }
                            SettingRow(
                                "${g.title}:2", "开机自启",
                                if (SettingsStore.bootAutostart) "开启" else "关闭", pending = true
                            ) {
                                openChoice("${g.title}:2", ChoiceState(
                                    "开机自启（待实现）",
                                    listOf("开启", "关闭"),
                                    if (SettingsStore.bootAutostart) 0 else 1,
                                    { i -> SettingsStore.bootAutostart = i == 0 }
                                ))
                            }
                            SettingRow(
                                "${g.title}:3", "设备名称",
                                SettingsStore.deviceName, pending = true
                            ) {
                                openChoice("${g.title}:3", ChoiceState(
                                    "设备名称（待实现）",
                                    DEVICE_NAMES,
                                    pickIndex(DEVICE_NAMES, SettingsStore.deviceName),
                                    { i -> SettingsStore.deviceName = DEVICE_NAMES[i] }
                                ))
                            }
                        }

                        SettingGroup.PLAY -> {
                            SettingRow(
                                "${g.title}:0", "自动续播提示",
                                if (SettingsStore.autoResumePrompt) "开启" else "关闭", pending = true
                            ) {
                                openChoice("${g.title}:0", ChoiceState(
                                    "自动续播提示（待实现）",
                                    listOf("开启", "关闭"),
                                    if (SettingsStore.autoResumePrompt) 0 else 1,
                                    { i -> SettingsStore.autoResumePrompt = i == 0 }
                                ))
                            }
                            SettingRow(
                                "${g.title}:1", "自动连播",
                                if (SettingsStore.autoPlayNext) "开启" else "关闭", pending = true
                            ) {
                                openChoice("${g.title}:1", ChoiceState(
                                    "自动连播（待实现）",
                                    listOf("开启", "关闭"),
                                    if (SettingsStore.autoPlayNext) 0 else 1,
                                    { i -> SettingsStore.autoPlayNext = i == 0 }
                                ))
                            }
                            SettingRow(
                                "${g.title}:2", "默认倍速",
                                speedLabel(SettingsStore.defaultSpeed), pending = true
                            ) {
                                openChoice("${g.title}:2", ChoiceState(
                                    "默认倍速（待实现）",
                                    SPEED_OPTIONS,
                                    pickIndex(SPEED_OPTIONS, speedLabel(SettingsStore.defaultSpeed)),
                                    { i -> SettingsStore.defaultSpeed = when (i) { 1 -> 1.25f; 2 -> 1.5f; else -> 1.0f } }
                                ))
                            }
                            SettingRow(
                                "${g.title}:3", "默认画面比例",
                                SettingsStore.defaultAspect.label, pending = true
                            ) {
                                openChoice("${g.title}:3", ChoiceState(
                                    "默认画面比例（待实现）",
                                    AspectRatio.entries.map { it.label },
                                    AspectRatio.entries.indexOf(SettingsStore.defaultAspect),
                                    { i -> SettingsStore.defaultAspect = AspectRatio.entries[i] }
                                ))
                            }
                        }

                        SettingGroup.UI -> {
                            SettingRow(
                                "${g.title}:0", "网格列数",
                                "${SettingsStore.gridColumns} 列", pending = true
                            ) {
                                openChoice("${g.title}:0", ChoiceState(
                                    "网格列数（待实现）",
                                    listOf("4 列", "5 列", "6 列"),
                                    SettingsStore.gridColumns - 4,
                                    { i -> SettingsStore.gridColumns = i + 4 }
                                ))
                            }
                            SettingRow(
                                "${g.title}:1", "默认排序方式",
                                SettingsStore.defaultSort.label, pending = true
                            ) {
                                openChoice("${g.title}:1", ChoiceState(
                                    "默认排序方式（待实现）",
                                    SortOrder.entries.map { it.label },
                                    SortOrder.entries.indexOf(SettingsStore.defaultSort),
                                    { i -> SettingsStore.defaultSort = SortOrder.entries[i] }
                                ))
                            }
                        }

                        SettingGroup.STORAGE -> {
                            SettingRow(
                                "${g.title}:0", "存储空间占用",
                                if (storageComputing) "…" else storageLabel,
                                pending = false,
                                onClick = refreshStorage
                            )
                            SettingRow("${g.title}:1", "清空上传记录", "仅清记录") {
                                openConfirm("${g.title}:1", ConfirmState(
                                    "清空上传记录",
                                    "确定清空全部上传记录吗？\n仅删除历史日志，本地文件将全部保留。",
                                    "全部清空"
                                ) {
                                    scope.launch { uploadRepo.clearAll() }
                                    Toast.makeText(context, "已清空上传记录，本地文件已保留", Toast.LENGTH_SHORT).show()
                                })
                            }
                            SettingRow("${g.title}:2", "清空播放历史", "清空历史") {
                                openConfirm("${g.title}:2", ConfirmState(
                                    "清空播放历史",
                                    "确定清空全部播放历史吗？\n仅删除播放进度记录，已上传的视频/图片不受影响。",
                                    "全部清空"
                                ) {
                                    scope.launch { playbackRepo.clearAllHistory() }
                                    Toast.makeText(context, "已清空播放历史", Toast.LENGTH_SHORT).show()
                                })
                            }
                            SettingRow("${g.title}:3", "手动触发对账", "刷新媒体库") {
                                scope.launch {
                                    val r = syncManager.sync()
                                    Toast.makeText(
                                        context,
                                        "对账完成：新增 ${r.inserted}，更新 ${r.updated}，" +
                                            "清理失效 ${r.deletedMissing}，空文件夹 ${r.removedEmptyFolders}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }

                        SettingGroup.ABOUT -> {
                            val versionBody =
                                "传视TV 局域网媒体中心与传输工具\n\n版本：v${BuildConfig.VERSION_NAME}\n" +
                                    "版本号：${BuildConfig.VERSION_CODE}\n构建时间：${BuildConfig.BUILD_TIME}\n\n" +
                                    "电视端接收与媒体中心，手机扫码即可上传，无需安装 App。\n" +
                                    "详见 GitHub README。"
                            SettingRow(
                                "${g.title}:0", "版本信息",
                                "v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TIME}"
                            ) {
                                openInfo("${g.title}:0", InfoState("版本信息", versionBody))
                            }
                            SettingRow("${g.title}:1", "开源许可", "MIT License") {
                                openInfo("${g.title}:1", InfoState("开源许可", MIT_LICENSE))
                            }
                            SettingRow("${g.title}:2", "致谢名单", "3 个开源项目") {
                                openInfo("${g.title}:2", InfoState("致谢名单", CREDITS))
                            }
                        }
                    }
                }
            }
        }
    }

    // ————————————————— 弹框 —————————————————
    choiceState?.let { cs ->
        Dialog(onDismissRequest = { choiceState = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(28.dp).width(380.dp)) {
                    Text(
                        cs.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(14.dp))
                    cs.options.forEachIndexed { i, opt ->
                        OptionRow(opt, selected = i == cs.selectedIndex) {
                            choiceState = null
                            cs.onPick(i)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }

    confirmState?.let { cf ->
        Dialog(onDismissRequest = { confirmState = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(28.dp).width(440.dp)) {
                    Text(
                        cf.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (cf.destructive) DangerRed else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(cf.message, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(cf.confirmText) {
                            confirmState = null
                            cf.onConfirm()
                        }
                        TvButton("取消") { confirmState = null }
                    }
                }
            }
        }
    }

    infoState?.let { info ->
        Dialog(onDismissRequest = { infoState = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(28.dp).width(560.dp)) {
                    Text(
                        info.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        info.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    Spacer(Modifier.height(22.dp))
                    TvButton("关闭") { infoState = null }
                }
            }
        }
    }
}