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
    UPLOAD("上传与解压"),
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

/**
 * 「关于」页声明文案。
 *
 * 「关于」不再用弹框展示（内容较长、弹框在 720p 下要滚动且遮住页面），改为在设置页右侧
 * 详情区**内联**成若干可聚焦条目，随焦点上下移动自动滚动，见 [SettingsScreen] 的 AboutEntry。
 */
private const val DECLARATION =
    "本项目为AI工具开发，仅供学习、交流与个人使用，请勿用于商业倒卖。" +
        "播放及上传的媒体内容版权归各自版权方所有。"

/** 常见可选端口（电视遥控器无键盘，用预设免输入；首项 = 默认端口 2333） */
private val PORT_OPTIONS = listOf("2333", "5210", "8080", "8888", "9527")

/** 常见设备名候选（遥控器无键盘，候选选择；首项为默认值） */
private val DEVICE_NAMES = listOf(
    "传视TV", "客厅电视", "卧室电视", "主卧电视", "次卧电视", "书房电视", "影音室", "投影仪"
)

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
 * - 右侧详情左键回到左侧当前分组；每组首行按上回到「设置」标签；设置行点击后弹框，关闭弹框焦点回到刚才那一行。
 * - 「关于」组特殊：信息**直接内联**在详情区（不再弹框），每条信息都是一个可聚焦条目，
 *   按确定/右键都没有动作；内容超出可视区时随焦点上下移动自动滚动。
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

    // ——— 「服务器与网络」三项（已接线）的界面显示值 ———
    // 端口切换需要重启服务器（异步），自启/设备名写盘后本页也不会自动重组，
    // 因此用本地状态承载显示值：先更新界面，副作用（重启/写盘）失败时再回滚。
    var portValue by remember { mutableIntStateOf(SettingsStore.serverPort) }
    var bootAutostartValue by remember { mutableStateOf(SettingsStore.bootAutostart) }
    var deviceNameValue by remember { mutableStateOf(SettingsStore.deviceName) }

    // ——— 「上传与解压」两项（已接线）的界面显示值 ———
    var autoUnzipValue by remember { mutableStateOf(SettingsStore.autoUnzipZip) }
    var keepZipValue by remember { mutableStateOf(SettingsStore.keepOriginalZip) }

    // ——— 「播放设置」四项（已接线）的界面显示值 ———
    // 写 SharedPreferences 不会触发本页重组，用本地状态承载显示值，选完立刻刷新。
    var autoResumeValue by remember { mutableStateOf(SettingsStore.autoResumePrompt) }
    var autoPlayNextValue by remember { mutableStateOf(SettingsStore.autoPlayNext) }
    var defaultSpeedValue by remember { mutableStateOf(SettingsStore.defaultSpeed) }
    var defaultAspectValue by remember { mutableStateOf(SettingsStore.defaultAspect) }

    // ——— 「界面设置」两项（已接线）的界面显示值 ———
    var gridColumnsValue by remember { mutableIntStateOf(SettingsStore.gridColumns) }
    var defaultSortValue by remember { mutableStateOf(SettingsStore.defaultSort) }

    var selectedGroupIndex by remember { mutableIntStateOf(0) }
    val groupFocusers = remember { List(SettingGroup.entries.size) { FocusRequester() } }
    val rowFocusMap = remember { mutableStateMapOf<String, FocusRequester>() }
    // 各行当前是否获得焦点，用于聚焦重试时确认落焦成功（见 detailTicket / dialog 关闭的 retry）
    val rowFocused = remember { mutableStateMapOf<String, Boolean>() }
    var detailTicket by remember { mutableIntStateOf(0) }
    // 「关于」页信息较长，用独立滚动状态：内容超出可视区时随焦点上下移动自动滚动
    // （可聚焦节点在滚动容器内会带上 bring-into-view 行为）。
    val aboutScrollState = rememberScrollState()

    // 两个弹框状态（同一时刻最多开一个）
    var choiceState by remember { mutableStateOf<ChoiceState?>(null) }
    var confirmState by remember { mutableStateOf<ConfirmState?>(null) }

    // 弹框关闭后把焦点还给打开它的那行
    var pendingFocusReturn by remember { mutableStateOf<String?>(null) }
    val dialogOpen = choiceState != null || confirmState != null
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
        onClick: () -> Unit
    ) {
        val requester = remember(focusKey) { FocusRequester() }
        rowFocusMap[focusKey] = requester
        // 分组首行：按上键吃掉，防止环绕到顶部标签把页面切走
        val topEdge = focusKey.endsWith(":0")
        Box(
            Modifier
                .fillMaxWidth()
                // 左右留白，理由同 AboutEntry：条目与卡片同宽时，tvFocus 焦点描边的左右两条会被
                // 卡片（带圆角的 Surface）裁掉，只剩上下两条。留白 12dp + 内容内边距 10dp = 22dp，
                // 与原先的内容缩进完全一致（文本位置不动），只是描边不再贴着卡片边缘。
                .padding(horizontal = 12.dp)
                .focusRequester(requester)
                .onFocusChanged { rowFocused[focusKey] = it.isFocused }
                // tvFocus 必须挂在可聚焦修饰符（clickable）的**上游**：它内部的 onFocusChanged
                // 只能观察到「下游」的焦点节点，写在 clickable 之后会观察不到 → 焦点高亮整行不显示
                // （实测：设置页所有设置行按方向键移动时看不到任何高亮）。
                .tvFocus()
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
                .padding(horizontal = 10.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("▸", style = MaterialTheme.typography.bodyLarge, color = OnDarkDim)
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    /**
     * 「关于」页的信息条目：**可聚焦，但没有动作**（遥控器确定 / 右键都不会弹框）。
     *
     * 内容较长时（开源许可全文 / 致谢名单），整块作为一个焦点单元，随焦点上下移动自动
     * 滚动（可聚焦节点在 verticalScroll 容器内自带 bring-into-view）。
     *
     * 焦点边界与 [SettingRow] 保持一致：左键回左侧分组、首条上键回顶部「设置」标签、
     * 右键吃掉（否则会环绕到右上角「设置」）；末条下键也吃掉（否则环绕到顶部标签并切页）。
     */
    @Composable
    fun AboutEntry(
        focusKey: String,
        title: String,
        value: String? = null,
        body: String? = null,
        isLast: Boolean = false
    ) {
        val requester = remember(focusKey) { FocusRequester() }
        rowFocusMap[focusKey] = requester
        val topEdge = focusKey.endsWith(":0")
        Box(
            Modifier
                .fillMaxWidth()
                // 左右留白：tvFocus 的焦点描边画在条目边界上，而外层卡片（带圆角的 Surface）会裁剪
                // 超出卡片的部分 —— 条目与卡片同宽时只能看到上下两条线，左右两条被裁掉。
                // 留出 12dp 后四边都可见（tvFocus 的 scale(1.03) 放大只占去约 8dp，仍在留白内）。
                .padding(horizontal = 12.dp)
                .focusRequester(requester)
                .onFocusChanged { rowFocused[focusKey] = it.isFocused }
                // tvFocus 放在 focusable 之前（上游），理由同 SettingRow：其 onFocusChanged
                // 只观察下游焦点节点，写在下游会导致条目聚焦时没有任何视觉反馈。
                .tvFocus()
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { backToGroups(); true }
                        Key.DirectionRight -> true
                        Key.DirectionUp -> { if (topEdge) onFocusTabs(); topEdge }
                        Key.DirectionDown -> isLast
                        else -> false
                    }
                }
                // 内容内边距：与上面 12dp 留白合计 24dp，和设置行（SettingRow）的内容缩进基本对齐
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (value != null) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (body != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
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
        val group = SettingGroup.entries[selectedGroupIndex]
        // 「关于」内容较长：内容靠上排列、卡片撑满剩余高度并在卡片内滚动（焦点下移自动滚动）；
        // 其余分组内容少，维持垂直居中。
        val aboutMode = group == SettingGroup.ABOUT
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = if (aboutMode) Arrangement.Top else Arrangement.Center
        ) {
            Text(
                group.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = if (aboutMode) Modifier.fillMaxWidth().weight(1f)
                else Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .then(
                            if (aboutMode) Modifier.verticalScroll(aboutScrollState) else Modifier
                        )
                        .padding(vertical = 8.dp)
                ) {
                    val g = group
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
                                "$portValue"
                            ) {
                                openChoice("${g.title}:1", ChoiceState(
                                    "服务器端口",
                                    PORT_OPTIONS,
                                    pickIndex(PORT_OPTIONS, portValue.toString())
                                ) { i ->
                                    val target = PORT_OPTIONS[i].toInt()
                                    if (target != portValue) {
                                        // 切换端口 = 停旧端口、在新端口重建监听（NanoHTTPD 端口构造时固定）。
                                        // 成功才落地显示值；失败（多为端口被占用）保持原端口并提示。
                                        ServerController.setPort(target) { ok ->
                                            if (ok) {
                                                portValue = target
                                                Toast.makeText(
                                                    context,
                                                    "端口已切换为 $target，手机端请用新地址访问",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    "端口 $target 启动失败（可能已被占用），仍使用 $portValue",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            }
                                        }
                                    }
                                })
                            }
                            SettingRow(
                                "${g.title}:2", "开机自启",
                                if (bootAutostartValue) "开启" else "关闭"
                            ) {
                                openChoice("${g.title}:2", ChoiceState(
                                    "开机自启",
                                    listOf("开启", "关闭"),
                                    if (bootAutostartValue) 0 else 1
                                ) { i ->
                                    val on = i == 0
                                    bootAutostartValue = on
                                    SettingsStore.bootAutostart = on
                                    Toast.makeText(
                                        context,
                                        if (on) "已开启：开机后自动启动文件服务器"
                                        else "已关闭开机自启",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                })
                            }
                            SettingRow(
                                "${g.title}:3", "设备名称",
                                deviceNameValue
                            ) {
                                openChoice("${g.title}:3", ChoiceState(
                                    "设备名称",
                                    DEVICE_NAMES,
                                    pickIndex(DEVICE_NAMES, deviceNameValue)
                                ) { i ->
                                    val name = DEVICE_NAMES[i]
                                    deviceNameValue = name
                                    SettingsStore.deviceName = name
                                    Toast.makeText(
                                        context,
                                        "设备名称已改为「$name」，手机端上传页将显示该名称",
                                        Toast.LENGTH_LONG
                                    ).show()
                                })
                            }
                        }

                        SettingGroup.UPLOAD -> {
                            SettingRow(
                                "${g.title}:0", "自动解压压缩包",
                                if (autoUnzipValue) "开启" else "关闭"
                            ) {
                                openChoice("${g.title}:0", ChoiceState(
                                    "自动解压压缩包",
                                    listOf("开启", "关闭"),
                                    if (autoUnzipValue) 0 else 1
                                ) { i ->
                                    val on = i == 0
                                    autoUnzipValue = on
                                    SettingsStore.autoUnzipZip = on
                                    Toast.makeText(
                                        context,
                                        if (on) "已开启：上传到「视频 / 图片」的 zip 将自动解压，只保留对应分类的文件"
                                        else "已关闭：zip 将作为普通文件直接保存，不再自动解压",
                                        Toast.LENGTH_LONG
                                    ).show()
                                })
                            }
                            SettingRow(
                                "${g.title}:1", "保留原压缩包",
                                if (keepZipValue) "保留" else "解压后删除"
                            ) {
                                openChoice("${g.title}:1", ChoiceState(
                                    "保留原压缩包",
                                    listOf("开启（保留到「其他」）", "关闭（解压后删除）"),
                                    if (keepZipValue) 0 else 1
                                ) { i ->
                                    val on = i == 0
                                    keepZipValue = on
                                    SettingsStore.keepOriginalZip = on
                                    Toast.makeText(
                                        context,
                                        if (on) "已开启：解压成功后原 zip 保留在「其他」分类"
                                        else "已关闭：解压成功后删除原 zip（解压失败时仍会保留原包）",
                                        Toast.LENGTH_LONG
                                    ).show()
                                })
                            }
                        }

                        SettingGroup.PLAY -> {
                            SettingRow(
                                "${g.title}:0", "自动续播提示",
                                if (autoResumeValue) "开启" else "关闭"
                            ) {
                                openChoice("${g.title}:0", ChoiceState(
                                    "自动续播提示",
                                    listOf("开启", "关闭"),
                                    if (autoResumeValue) 0 else 1
                                ) { i ->
                                    val on = i == 0
                                    autoResumeValue = on
                                    SettingsStore.autoResumePrompt = on
                                    Toast.makeText(
                                        context,
                                        if (on) "已开启：打开视频时弹窗询问是否继续播放"
                                        else "已关闭：打开视频时直接从上次位置继续，不再询问",
                                        Toast.LENGTH_LONG
                                    ).show()
                                })
                            }
                            SettingRow(
                                "${g.title}:1", "自动连播",
                                if (autoPlayNextValue) "开启" else "关闭"
                            ) {
                                openChoice("${g.title}:1", ChoiceState(
                                    "自动连播",
                                    listOf("开启", "关闭"),
                                    if (autoPlayNextValue) 0 else 1
                                ) { i ->
                                    val on = i == 0
                                    autoPlayNextValue = on
                                    SettingsStore.autoPlayNext = on
                                    Toast.makeText(
                                        context,
                                        if (on) "已开启：一集播完自动播放同目录下一个视频"
                                        else "已关闭：一集播完停在片尾，等您选择重播或返回",
                                        Toast.LENGTH_LONG
                                    ).show()
                                })
                            }
                            SettingRow(
                                "${g.title}:2", "默认倍速",
                                speedLabel(defaultSpeedValue)
                            ) {
                                openChoice("${g.title}:2", ChoiceState(
                                    "默认倍速",
                                    SPEED_OPTIONS,
                                    pickIndex(SPEED_OPTIONS, speedLabel(defaultSpeedValue))
                                ) { i ->
                                    val sp = when (i) {
                                        1 -> 1.25f
                                        2 -> 1.5f
                                        else -> 1.0f
                                    }
                                    defaultSpeedValue = sp
                                    SettingsStore.defaultSpeed = sp
                                    Toast.makeText(
                                        context,
                                        "已设默认倍速 ${speedLabel(sp)}，下次打开视频生效",
                                        Toast.LENGTH_LONG
                                    ).show()
                                })
                            }
                            SettingRow(
                                "${g.title}:3", "默认画面比例",
                                defaultAspectValue.label
                            ) {
                                openChoice("${g.title}:3", ChoiceState(
                                    "默认画面比例",
                                    AspectRatio.entries.map { it.label },
                                    AspectRatio.entries.indexOf(defaultAspectValue)
                                ) { i ->
                                    val a = AspectRatio.entries[i]
                                    defaultAspectValue = a
                                    SettingsStore.defaultAspect = a
                                    Toast.makeText(
                                        context,
                                        "已设默认画面比例「${a.label}」，下次打开视频生效",
                                        Toast.LENGTH_LONG
                                    ).show()
                                })
                            }
                        }

                        SettingGroup.UI -> {
                            SettingRow(
                                "${g.title}:0", "网格列数",
                                "$gridColumnsValue 列"
                            ) {
                                openChoice("${g.title}:0", ChoiceState(
                                    "网格列数",
                                    listOf("4 列", "5 列", "6 列"),
                                    gridColumnsValue - 4
                                ) { i ->
                                    val cols = i + 4
                                    gridColumnsValue = cols
                                    SettingsStore.gridColumns = cols
                                    Toast.makeText(
                                        context,
                                        "媒体库网格已改为 $cols 列，返回媒体库即生效",
                                        Toast.LENGTH_LONG
                                    ).show()
                                })
                            }
                            SettingRow(
                                "${g.title}:1", "默认排序方式",
                                defaultSortValue.label
                            ) {
                                openChoice("${g.title}:1", ChoiceState(
                                    "默认排序方式",
                                    SortOrder.entries.map { it.label },
                                    SortOrder.entries.indexOf(defaultSortValue)
                                ) { i ->
                                    val order = SortOrder.entries[i]
                                    defaultSortValue = order
                                    SettingsStore.defaultSort = order
                                    Toast.makeText(
                                        context,
                                        "默认排序已设为「${order.label}」，返回媒体库即生效",
                                        Toast.LENGTH_LONG
                                    ).show()
                                })
                            }
                        }

                        SettingGroup.STORAGE -> {
                            SettingRow(
                                "${g.title}:0", "存储空间占用",
                                if (storageComputing) "…" else storageLabel,
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
                            // 直接内联展示：不弹框，每条信息可聚焦，内容超出可视区时随焦点滚动。
                            AboutEntry(
                                "${g.title}:0", "传视 TransView",
                                value = "版本：v${BuildConfig.VERSION_NAME}"
                            )
                            AboutEntry("${g.title}:1", "声明", body = DECLARATION)
                            AboutEntry("${g.title}:2", "开源许可", body = MIT_LICENSE)
                            AboutEntry("${g.title}:3", "致谢名单", body = CREDITS, isLast = true)
                        }
                    }
                }
            }
        }
    }

    // ————————————————— 弹框 —————————————————
    choiceState?.let { cs ->
        // 打开弹框时把焦点放到**当前选中项**上，而不是第一项：
        // 否则「● 当前值」与聚焦高亮分别停在两行，视觉上像两个选中项；
        // 更实际的风险是用户直接按确定会静默改成第一项（实测踩到过：
        // 网格列数当前 5 列，弹框焦点在「4 列」，直接确定就把列数改成了 4）。
        val selectedOptionFocus = remember { FocusRequester() }
        LaunchedEffect(cs.title, cs.selectedIndex) {
            runCatching { selectedOptionFocus.requestFocusNextFrame() }
        }
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
                        OptionRow(
                            text = opt,
                            selected = i == cs.selectedIndex,
                            modifier = if (i == cs.selectedIndex) {
                                Modifier.focusRequester(selectedOptionFocus)
                            } else Modifier
                        ) {
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
}