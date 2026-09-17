package com.hpu.transview.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hpu.transview.BuildConfig
import com.hpu.transview.data.MediaRepository
import com.hpu.transview.data.PlaybackRepository
import com.hpu.transview.data.UploadRecordRepository
import com.hpu.transview.data.sync.SyncManager
import com.hpu.transview.model.AspectRatio
import com.hpu.transview.model.ServerMode
import com.hpu.transview.model.SortOrder
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.ServerController
import com.hpu.transview.server.TransHttpServer
import com.hpu.transview.ui.common.COMPACT_CONTENT_DENSITY_SCALE
import com.hpu.transview.ui.common.COMPACT_SCREEN_HEIGHT_DP
import com.hpu.transview.ui.common.OptionRow
import com.hpu.transview.ui.common.TvButton
import com.hpu.transview.ui.common.requestFocusNextFrame
import com.hpu.transview.ui.common.tvFocus
import com.hpu.transview.ui.theme.DangerRed
import com.hpu.transview.ui.theme.OnDarkDim
import com.hpu.transview.util.AppLogger
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
    /** 可选的逐项说明：长度与 options 一致时，每项下方渲染一行灰色小字（如保活策略模式说明） */
    val descriptions: List<String>? = null,
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
    "传视", "客厅电视", "卧室电视", "主卧电视", "次卧电视", "书房电视", "影音室", "投影仪"
)

/** 默认倍速候选项 */
private val SPEED_OPTIONS = listOf("1.0x", "1.25x", "1.5x")

private const val MIT_LICENSE = "MIT License\n\n" +
    "Copyright (c) 2026 TransView 传视\n\n" +
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

/**
 * 左栏分组在「内容区最后聚焦节点」里的键前缀。
 *
 * 右栏行的键是 `"分组标题:序号"`（如 `"存储与数据:6"`），左栏用 `"@group:序号"` 与之区分 ——
 * 两者都塞进同一个 `lastContentAnchor` 字符串里，靠前缀判断该锚回去哪一栏。
 */
private const val GROUP_ANCHOR_PREFIX = "@group:"

/** 本页日志标签（AppLogger 落盘用） */
private const val TAG = "SettingsScreen"

private fun speedLabel(speed: Float): String = when (speed) {
    1.25f -> "1.25x"
    1.5f -> "1.5x"
    else -> "1.0x"
}

/** 设置子页面：左侧分组 + 右侧详情，占据内容区（顶部导航栏保持不变）。
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

    // ——— 「播放设置」四项（已接线）的界面显示值 ———
    // 写 SharedPreferences 不会触发本页重组，用本地状态承载显示值，选完立刻刷新。
    var autoResumeValue by remember { mutableStateOf(SettingsStore.autoResumePrompt) }
    var autoPlayNextValue by remember { mutableStateOf(SettingsStore.autoPlayNext) }
    var defaultSpeedValue by remember { mutableStateOf(SettingsStore.defaultSpeed) }
    var defaultAspectValue by remember { mutableStateOf(SettingsStore.defaultAspect) }

    // ——— 「界面设置」两项（已接线）的界面显示值 ———
    var gridColumnsValue by remember { mutableIntStateOf(SettingsStore.gridColumns) }
    var defaultSortValue by remember { mutableStateOf(SettingsStore.defaultSort) }

    // ——— 存储位置（首选存储）：切换后经 FileLocations.refresh() 立即推导活动存储，
    // ——— StateFlow 驱动本行 value 与下方「当前存储」信息条目自动刷新 ———
    val storageState by FileLocations.storageState.collectAsState()

    // 「App 调试日志」开关的界面显示值（写 SharedPreferences 不会触发本页重组）
    var appLogValue by remember { mutableStateOf(SettingsStore.appLogEnabled) }
    // 「详细日志」开关的界面显示值（v1.31）：只在上面总闸开启时有意义
    var detailedLogValue by remember { mutableStateOf(SettingsStore.detailedLogEnabled) }
    // 日志目录提示文案：随活动存储（内部存储 / U 盘）变化
    val appLogDir = AppLogger.logDirLabel()

    var selectedGroupIndex by remember { mutableIntStateOf(0) }
    // 焦点是否在设置页内容区（左侧分组/右侧详情）。顶部「设置」标签持有焦点时为 false ——
    // 此时左侧分组**不显示**选中高亮，避免用户误以为「焦点自动跳进了内容区」
    //（实测：进入设置页时「服务器与网络」默认带竖条+主色文字高亮，被误读为焦点跳转）。
    var contentFocused by remember { mutableStateOf(false) }
    // 内容区**最后聚焦的那个节点**：左栏分组记 "$GROUP_ANCHOR_PREFIX序号"，右栏行直接记 focusKey。
    // 触摸点按空白处 / 滑动时触点不在任何可聚焦节点上，没人把焦点抢回内容区，150ms 后的票据兜底
    // 此前是**无条件**投给首个分组 —— 用户实测「点设置页空白处焦点总是跳到服务器与网络」（还会
    // 连带把左栏选中项改成第 0 个、右栏内容一起切走）。现在据此锚回原处，语义等价于
    // 「点空白 / 划空白不改动焦点位置」；没有任何聚焦记忆时才保持原行为落到首个分组。
    var lastContentAnchor by remember { mutableStateOf<String?>(null) }
    val groupFocusers = remember { List(SettingGroup.entries.size) { FocusRequester() } }
    val rowFocusMap = remember { mutableStateMapOf<String, FocusRequester>() }
    // 各行当前是否获得焦点，用于聚焦重试时确认落焦成功（见 detailTicket / dialog 关闭的 retry）
    val rowFocused = remember { mutableStateMapOf<String, Boolean>() }
    var detailTicket by remember { mutableIntStateOf(0) }
    // 左/右两栏各自一份滚动状态。矮屏（手机横屏）下**两栏**都可能高于可视区：
    // 左栏是「设置」标题 + 5 个分组，右栏「存储与数据」有 10 行设置 + 小字提示 + 信息条目。
    // 原先两栏都是固定高度布局（左栏 `Arrangement.Center`、右栏仅「关于」组内部滚动），
    // 内容一超出就被裁掉、且无法滚动（用户实测：左侧末项「关于」看不到，
    // 右侧「存储与数据」只能看到「存储空间占用」为止）。
    // 现在两栏都可滚动：触摸可直接拖动，遥控器焦点移到被遮挡的行时
    // 由滚动容器的 bring-into-view 自动滚入视野（内容不超出时不滚动，观感与改造前一致）。
    val leftScrollState = rememberScrollState()
    // 右侧按分组各自重置滚动位置：切换分组时回到顶部，不沿用上一分组的偏移
    val detailScrollState = remember(selectedGroupIndex) { ScrollState(0) }

    // 三个弹框状态（同一时刻最多开一个）
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
            // 帧门控重试：requestFocus 必须在帧回调内发起才生效，且 rowFocused 由 onFocusChanged
            // 在**下一帧**才更新 —— 必须跨帧等待（delay）才有意义。
            // ⚠️ 这里不能用 `return@repeat`：它只结束**当前这次迭代**（等价 continue），
            // 循环仍会跑满 10 次；要真正提前退出必须用 for + break。
            for (attempt in 0 until 10) {
                rowFocusMap[key]?.requestFocusNextFrame()
                delay(16)
                if (rowFocused[key] == true) break
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
    // 以「活动存储身份」为 key：切换首选存储、外接盘拔出自动降级、插回自动恢复时
    // `FileLocations.refresh()` 会整体替换 StorageState（activeKey 随之变化），
    // 这里跟着重算一次 —— 否则「存储空间占用」只在进页面时算过一次，之后一直显示旧值，
    // 与同一组里「当前存储」自动刷新的行为不一致（用户报告）。
    LaunchedEffect(storageState.activeKey) { refreshStorage() }

    val uploadRepo = remember { UploadRecordRepository(context) }
    val playbackRepo = remember { PlaybackRepository(context) }
    val mediaRepo = remember { MediaRepository(context) }
    val syncManager = remember { SyncManager.getInstance(context) }

    // 应用缓存占用（cacheDir：Coil 图片/视频首帧磁盘缓存等）。
    // 它不在媒体沙盒里，「存储空间占用」统计不到，媒体库也看不到 —— 单独给一行可查可清。
    var cacheLabel by remember { mutableStateOf("计算中…") }
    var cacheComputing by remember { mutableStateOf(false) }
    val refreshCache: () -> Unit = {
        scope.launch {
            cacheComputing = true
            cacheLabel = withContext(Dispatchers.IO) {
                FileUtils.formatSize(FileUtils.cacheSizeBytes(context))
            }
            cacheComputing = false
        }
    }
    LaunchedEffect(Unit) { refreshCache() }


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

    // 返回键分层：焦点在设置内容区（左侧分组/右侧详情）时先回到顶部「设置」标签（设置页
    // 保持打开，焦点移回标签）；焦点在「设置」标签上时再退出设置页、回到主界面。与媒体页
    //「内容区 → 标签 → 退出」的层级一致（用户实测：内容区直接返回会跳到「其他」标签）。
    BackHandler {
        if (contentFocused) onFocusTabs() else onExit()
    }

    // 消费过的「↓ 进入内容」票据：记录已处理的值。与媒体库 consumedFocusTicket 同一模式——
    // 设置页关闭后再次打开时，残留的旧票据若仍 >0，会让下面的 LaunchedEffect 在初始组合
    // 阶段就把焦点投进「服务器与网络」（用户实测：设置页退出后从「其他」右键回到「设置」
    // 标签会直接跳进内容区）。
    var consumedFocusTicket by remember { mutableIntStateOf(focusTicket) }
    // 设置标签按 ↓ 时进入内容区并聚焦首个分组（与媒体页「标签按↓进首行」一致）；
    // 进入页面默认焦点保持在顶部「设置」标签上，不在这里抢焦点。
    LaunchedEffect(focusTicket) {
        if (focusTicket == consumedFocusTicket) return@LaunchedEffect
        consumedFocusTicket = focusTicket
        // 触摸路径防护：触点在分组/条目上时 goToDetail 已把焦点导向所选分组详情
        //（contentFocused=true），此时票据只是触摸兜底，跳过以免把刚选的分组拉回首个分组
        //（用户实测：点「关于」焦点与选中瞬间跳回「服务器与网络」）。
        if (contentFocused) return@LaunchedEffect
        // ——— 空处点按 / 滑动：锚回「最后聚焦的节点」，不再无条件甩到首个分组 ———
        // 触点落在行间间隙、行左右 12dp 留白、不可聚焦的日志路径小字、卡片边缘时，没有任何
        // 节点会请求焦点（MainScreen 的锚点已在 Press 阶段把焦点抢到 contentAnchor），
        // 于是这里成了唯一落点。此前写死 `groupFocusers[0]` → 用户实测「点设置页空白处焦点
        // 总是跳到服务器与网络」；滑动时同一个根因表现为「划动右栏子菜单后焦点跳走」。
        // 现按记忆锚回：左栏分组 → 该分组；右栏行 → 该行（帧门控重试到落焦成功）。
        // 两条都失效（如刚进页面、右栏行所属分组已切换）才退回当前选中分组；
        // 完全没有记忆时保持原行为：首个分组（「设置」标签首次按 ↓ 进入内容区）。
        val anchor = lastContentAnchor
        if (anchor != null && anchor.startsWith(GROUP_ANCHOR_PREFIX)) {
            val idx = anchor.removePrefix(GROUP_ANCHOR_PREFIX).toIntOrNull()
            val target = idx?.let { groupFocusers.getOrNull(it) }
            if (target != null && target.requestFocusNextFrame()) return@LaunchedEffect
        } else if (anchor != null && rowFocusMap.containsKey(anchor)) {
            rowFocused[anchor] = false
            repeat(10) {
                rowFocusMap[anchor]?.requestFocusNextFrame()
                if (rowFocused[anchor] == true) return@LaunchedEffect
            }
        }
        if (lastContentAnchor == null) {
            groupFocusers[0].requestFocusNextFrame()
        } else {
            groupFocusers[selectedGroupIndex.coerceIn(groupFocusers.indices)]
                .requestFocusNextFrame()
        }
    }

    // ————— 局部可调用组件（共享上面状态，避免大量传参） —————
    @Composable
    fun LeftGroup(idx: Int, title: String, selected: Boolean) {
        val lastIdx = SettingGroup.entries.size - 1
        Box(
            Modifier
                .fillMaxWidth()
                .focusRequester(groupFocusers[idx])
                .onFocusChanged {
                    if (it.isFocused) {
                        selectedGroupIndex = idx
                        // 记下「内容区最后聚焦的节点」，供票据兜底锚回（见 LaunchedEffect(focusTicket)）
                        lastContentAnchor = "$GROUP_ANCHOR_PREFIX$idx"
                    }
                }
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
        /**
         * 可选的第二行小字（说明/警告）。为 null 时**渲染结果与加这个参数之前完全一致**
         * （不额外包 Column），避免影响其余设置行的既有布局与焦点观感。
         */
        subtitle: String? = null,
        /** 禁用态：**仍可聚焦**（理由同 TvButton —— 正持有焦点的节点突然不可聚焦会把页面切走），
         *  只是不响应确定/右键并按灰色渲染值文本。用于「正在等待系统授权界面」期间锁住入口。 */
        enabled: Boolean = true,
        /** 分组末行：按 ↓ 吃掉按键。Compose 向下搜索不到候选时会环绕到整棵树第一个可聚焦元素
         * （= 左栏首个分组「服务器与网络」），必须显式拦截 —— 与 [LeftGroup] 的末项、
         *  [AboutEntry] 的 `isLast` 同规（漏了这一处，用户实测右栏末行按 ↓ 焦点会跳到首个分组）。 */
        bottomEdge: Boolean = false,
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
                .onFocusChanged {
                    rowFocused[focusKey] = it.isFocused
                    // 记下「内容区最后聚焦的节点」，供票据兜底锚回（见 LaunchedEffect(focusTicket)）
                    if (it.isFocused) lastContentAnchor = focusKey
                }
                // tvFocus 必须挂在可聚焦修饰符（clickable）的**上游**：它内部的 onFocusChanged
                // 只能观察到「下游」的焦点节点，写在 clickable 之后会观察不到 → 焦点高亮整行不显示
                // （实测：设置页所有设置行按方向键移动时看不到任何高亮）。
                .tvFocus()
                // 触摸点按：把焦点落到本行。MainScreen 的锚点 Press 处理器（Initial 阶段）会先退出
                // 触摸模式并聚焦锚点，这里 Main 阶段的请求在后、可成功覆盖；这样 150ms 后票据消费
                // 时看到 contentFocused=true，不会强制把焦点拉回首个分组（与上传页记录行同一机制）。
                .pointerInput(focusKey) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        requester.requestFocus()
                    }
                }
                // clickable 必须在 onPreviewKeyEvent 之前：与媒体库网格项一致，
                // clickable 会让元素可聚焦并处理 Enter/Center 激活，onPreviewKeyEvent
                // 放在它之后可以拦截方向键（Left/Right/Up）而不影响点击激活
                .clickable { if (enabled) onClick() }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        // 详情左端按左 → 回到左侧当前分组
                        Key.DirectionLeft -> { backToGroups(); true }
                        // 右键即「进入该项选值」：调出选值/确认/信息弹框（与遥控器确认键一致）；
                        // 同时因行内容已撑满右侧宽，按右也顺带防止环绕到右上角「设置」/标签
                        Key.DirectionRight -> { if (enabled) onClick(); true }
                        // 每组的首行按上 → 回到顶部「设置」标签（与媒体页内容区按上回标签一致）
                        Key.DirectionUp -> { if (topEdge) onFocusTabs(); topEdge }
                        // 分组末行按下：吃掉按键（与 LeftGroup 末项、AboutEntry isLast 同规），
                        // 否则 Compose 环绕到整棵树第一个可聚焦元素 = 左栏「服务器与网络」
                        Key.DirectionDown -> bottomEdge
                        else -> false
                    }
                }
                .padding(horizontal = 10.dp, vertical = 16.dp)
        ) {
            // subtitle 为 null 时，Column 只有一个子节点 → 测量结果与「直接用 Row」逐像素一致，
            // 因此其余设置行的既有布局/焦点观感不受本次改动影响。
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("▸", style = MaterialTheme.typography.bodyLarge, color = OnDarkDim)
                }
                if (subtitle != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDarkDim
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    /**
     * 「App 调试日志」开关下方的小字提示。
     *
     * **刻意不可聚焦**：它只是说明文字，若做成可聚焦节点会给焦点导航凭空多出一站
     * （遥控器用户按 ↓ 会多停一次），也不符合设置页「一行一个动作」的既有习惯。
     * 缩进与 [SettingRow] 的内容缩进对齐（12dp 留白 + 12dp 内边距 = 24dp）。
     */
    @Composable
    fun LogPathHint(dirLabel: String) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 2.dp)
        ) {
            Text(
                "日志文件位置：${dirLabel}（按日期分目录，文件名即写入时刻）",
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkDim
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "查看方式：媒体库「其他」页 → TransView/Downloads/app_log → 当天日期目录 → 打开 .log 文件",
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkDim
            )
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
                .onFocusChanged {
                    rowFocused[focusKey] = it.isFocused
                    // 记下「内容区最后聚焦的节点」，供票据兜底锚回（见 LaunchedEffect(focusTicket)）
                    if (it.isFocused) lastContentAnchor = focusKey
                }
                // tvFocus 放在 focusable 之前（上游），理由同 SettingRow：其 onFocusChanged
                // 只观察下游焦点节点，写在下游会导致条目聚焦时没有任何视觉反馈。
                .tvFocus()
                .focusable()
                // 触摸点按：把焦点落到本条目。与 SettingRow 同一机制——MainScreen 锚点 Press
                // 处理器（Initial 阶段）会先退出触摸模式并聚焦锚点，这里 Main 阶段的请求在后、
                // 可成功覆盖；这样 150ms 后票据消费时 contentFocused=true，不会强制把焦点拉回
                // 首个分组（实测：点「关于」后点击右侧「声明」等条目会跳回「服务器与网络」）。
                .pointerInput(focusKey) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        requester.requestFocus()
                    }
                }
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
            // 跟踪「焦点是否在本内容区」：hasFocus 含子树（左侧分组/右侧详情）。
            // 顶部「设置」标签持有焦点时本 Row 无焦点 → contentFocused=false → 左侧分组不显示高亮。
            .onFocusChanged { contentFocused = it.hasFocus }
            // 返回键必须先在这里拦截：系统派发 Back 时会先清空内容区焦点，等 BackHandler
            // 再判断 contentFocused 已变成 false（实测：Back 一按下 hasFocus 立刻翻转）。
            // 焦点在内容区时按 Back → 回到顶部「设置」标签（设置页保持打开）；
            // 焦点在「设置」标签时本 Row 不在焦点路径上 → 该事件回落到下面的 BackHandler。
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onFocusTabs()
                    true
                } else false
            }
    ) {
        // 左侧分组：整栏可滚动（矮屏上 5 个分组 + 标题会高于可视区，末项「关于」否则被裁掉）。
        // 内容不超出视口时 `fillMaxHeight` 撑满 + `Arrangement.Center` 居中，
        // 与改造前逐像素一致（电视/平板不受影响）；超出时整栏滚动。
        Column(
            Modifier
                .width(280.dp)
                .fillMaxHeight()
                .verticalScroll(leftScrollState),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(18.dp))
            SettingGroup.entries.forEachIndexed { i, g ->
                // 高亮条件追加 contentFocused：焦点在顶部「设置」标签时不显示选中态
                LeftGroup(i, g.title, selected = selectedGroupIndex == i && contentFocused)
            }
        }

        Spacer(Modifier.width(28.dp))

        // 右侧详情：整栏可滚动（理由同左侧 —— 矮屏下「存储与数据」等内容会超出可视区）。
        // 「关于」内容最长，靠上排列；其余分组内容较少，维持垂直居中
        // （`fillMaxHeight` + `Arrangement.Center` 在内容不超出时与改造前逐像素一致）。
        val group = SettingGroup.entries[selectedGroupIndex]
        val aboutMode = group == SettingGroup.ABOUT
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(detailScrollState),
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
                // 卡片贴着内容高度（不再 `weight(1f)` 撑满）：整栏滚动后卡片随内容自然延伸，
                // 「关于」的长内容滚到底能完整看到卡片的圆角底边。
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
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
                                    descriptions = ServerMode.entries.map { it.desc }
                                ) { i -> ServerController.setMode(ServerMode.entries[i]) })
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
                                deviceNameValue,
                                bottomEdge = true
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
                                defaultAspectValue.label,
                                bottomEdge = true
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
                                defaultSortValue.label,
                                bottomEdge = true
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
                                "${g.title}:0", "存储位置",
                                if (storageState.degraded) {
                                    "内部存储（${storageState.preferredLabel}已断开）"
                                } else {
                                    storageState.activeLabel
                                }
                            ) {
                                // 可选存储 = 扫描出的**可写设备**（内部存储恒在首位，U 盘/SD 卡按挂载点列出）。
                                // 每项副标题带上「可用 / 共」容量，一眼能分辨是哪块盘；
                                // 「首选但已拔出」的盘也会列出（标「已断开」，不可选），
                                // 让用户看得到自己选过的盘为什么不见了。
                                val options = storageState.volumes
                                val selectedIdx = options.indexOfFirst {
                                    it.id == storageState.preferredRoot
                                }.coerceAtLeast(0)
                                openChoice("${g.title}:0", ChoiceState(
                                    "存储位置",
                                    options.map {
                                        if (it.available) it.label else "${it.label}（已断开）"
                                    },
                                    selectedIdx,
                                    descriptions = options.map { vol ->
                                        val space = if (vol.totalBytes > 0) {
                                            "可用 ${FileUtils.formatSize(vol.usableBytes)} / " +
                                                "共 ${FileUtils.formatSize(vol.totalBytes)}"
                                        } else {
                                            "容量未知"
                                        }
                                        when {
                                            !vol.available ->
                                                "${vol.displayPath}（已拔出，插回后自动恢复）"
                                            vol.isRemovable ->
                                                "$space；优先写入「${vol.label}」；拔出时自动降级到内部存储（上传不中断），插回自动恢复"
                                            else ->
                                                "$space；始终可用；外接盘拔出时的自动兜底落点"
                                        }
                                    }
                                ) { i ->
                                    val vol = options[i]
                                    // 「已断开」的占位项不可选（弹框里仍列出，仅作展示）
                                    if (!vol.available) return@ChoiceState
                                    // 首选存储按卷根路径 + 盘名一并持久化（盘名供降级提示显示）
                                    SettingsStore.preferredStoragePath = vol.id
                                    SettingsStore.preferredStorageLabel = vol.label
                                    scope.launch {
                                        // 立即重检活动存储（含写探针，IO 线程；可能降级/恢复），
                                        // StateFlow 推送本行 value 与「当前存储」条目自动刷新
                                        val newState = withContext(Dispatchers.IO) { FileLocations.refresh() }
                                        // 媒体库内容可能随活动存储切换，重新对账（互斥，正在同步则跳过）
                                        runCatching { syncManager.sync() }
                                        val msg = when {
                                            !vol.isRemovable -> "已切换为内部存储"
                                            newState.activeIsRemovable -> "已切换为「${vol.label}」"
                                            else -> "未检测到「${vol.label}」，暂用内部存储（降级模式）"
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                })
                            }
                            // 当前实际使用的路径与状态（纯信息展示，不可操作）：
                            // 正常模式（盘名）/ 降级模式（盘名已断开）随插拔自动变化
                            AboutEntry(
                                "${g.title}:1", "当前存储",
                                value = storageState.modeLabel,
                                body = storageState.activeStorage.getRootPath()
                            )
                            SettingRow(
                                "${g.title}:2", "存储空间占用",
                                if (storageComputing) "…" else storageLabel,
                                onClick = refreshStorage
                            )
                            // 「App 调试日志」开关（默认关）：电视端没有终端、拿不到 logcat，
                            // 打开后 AppLogger 把运行日志异步落盘到
                            // <活动沙盒>/TransView/Downloads/app_log/<yyyy-MM-dd>/<HH-mm-ss>.log；
                            // 该目录属于「其他」分类 → 对账后可在「其他」页直接翻看。
                            // focusKey 固定为 :3（:4~:9 为其余各项，ID 稳定、不随行序变化）；
                            // 其下紧随的「详细日志」是它的**子开关**，编号 :9（新增行只追加编号，
                            // 不重排既有 ID）。分组末行仍是 :8「清理不可达索引」（bottomEdge = true）
                            // —— :9 的渲染位置落在 :3 之后、:4 之前，不改变末行。
                            SettingRow(
                                "${g.title}:3", "App 调试日志",
                                if (appLogValue) "开启" else "关闭",
                                subtitle = "记录调试日志（仅供排查问题，长期开启可能影响性能）"
                            ) {
                                openChoice("${g.title}:3", ChoiceState(
                                    "App 调试日志",
                                    listOf("开启", "关闭"),
                                    if (appLogValue) 0 else 1
                                ) { i ->
                                    val on = i == 0
                                    appLogValue = on
                                    SettingsStore.appLogEnabled = on
                                    // 立即生效（无需重启）：开 → 建目录 + 起消费协程；
                                    // 关 → 停消费协程、关文件流、清空内存队列
                                    AppLogger.setEnabled(on)
                                    Toast.makeText(
                                        context,
                                        if (on) "已开启调试日志，写入 $appLogDir"
                                        else "已关闭调试日志",
                                        Toast.LENGTH_LONG
                                    ).show()
                                })
                            }
                            // 「详细日志」开关（默认关，v1.31）：总闸开启后额外落盘 V 级
                            //（UI 操作轨迹、逐次存储探测、逐文件索引等可高频的诊断细节）。
                            // focusKey 取 :9 而非 :4 —— 按本页约定，**新增行一律分配未占用的新编号，
                            // 绝不重排既有 ID**（focusKey 是稳定标识，只用于焦点锚定 / 复位，
                            // 不参与几何导航，故渲染位置与前缀编号不一致是安全的）。
                            SettingRow(
                                "${g.title}:9", "详细日志",
                                if (detailedLogValue) "开启" else "关闭",
                                subtitle = "额外记录操作轨迹等细节；日志量较大，建议仅在排查时开启"
                            ) {
                                openChoice("${g.title}:9", ChoiceState(
                                    "详细日志",
                                    listOf("开启", "关闭"),
                                    if (detailedLogValue) 0 else 1
                                ) { i ->
                                    val on = i == 0
                                    detailedLogValue = on
                                    SettingsStore.detailedLogEnabled = on
                                    // 立即生效：只翻转标志，不重启写盘引擎（见 AppLogger.setDetailed）
                                    AppLogger.setDetailed(on)
                                    Toast.makeText(
                                        context,
                                        if (on) "已开启详细日志" else "已关闭详细日志",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                })
                            }
                            // 小字提示（不可聚焦，不参与焦点导航）：日志落点 + 去哪里看
                            LogPathHint(appLogDir)
                            SettingRow("${g.title}:4", "清空上传记录", "仅清记录") {
                                openConfirm("${g.title}:4", ConfirmState(
                                    "清空上传记录",
                                    "确定清空全部上传记录吗？\n仅删除历史日志，本地文件将全部保留。",
                                    "全部清空"
                                ) {
                                    // Toast 必须放在 launch 内部、suspend 的 clearAll() **之后** ——
                                    // 写在外面会在 DB 删除还没落地时就提示「已清空」（用户报告）。
                                    scope.launch {
                                        uploadRepo.clearAll()
                                        Toast.makeText(context, "已清空上传记录，本地文件已保留", Toast.LENGTH_SHORT).show()
                                    }
                                })
                            }
                            SettingRow("${g.title}:5", "清空播放历史", "清空历史") {
                                openConfirm("${g.title}:5", ConfirmState(
                                    "清空播放历史",
                                    "确定清空全部播放历史吗？\n仅删除播放进度记录，已上传的视频/图片不受影响。",
                                    "全部清空"
                                ) {
                                    scope.launch {
                                        playbackRepo.clearAllHistory()
                                        Toast.makeText(context, "已清空播放历史", Toast.LENGTH_SHORT).show()
                                    }
                                })
                            }
                            SettingRow(
                                "${g.title}:6", "手动触发对账", "刷新媒体库"
                            ) {
                                scope.launch {
                                    val r = syncManager.sync()
                                    // 降级模式跳过了「同步外部删除」（防误删），提示里注明
                                    val extra = if (r.degraded) {
                                        "\n（${FileLocations.storageState.value.preferredLabel}已断开，跳过删除核对，历史记录已保留）"
                                    } else ""
                                    Toast.makeText(
                                        context,
                                        "对账完成：新增 ${r.inserted}，更新 ${r.updated}，" +
                                            "清理失效 ${r.deletedMissing}，空文件夹 ${r.removedEmptyFolders}$extra",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            // 应用缓存（cacheDir）：不在媒体沙盒内，「存储空间占用」统计不到；
                            // 清理时顺带扫一次**遗留的上传临时文件**（带保护窗口，不会打断在途上传）。
                            SettingRow(
                                "${g.title}:7", "清理缓存",
                                if (cacheComputing) "…" else cacheLabel,
                                subtitle = "缩略图等应用缓存与上传残留，可随时清理；不影响已上传的文件"
                            ) {
                                openConfirm("${g.title}:7", ConfirmState(
                                    "清理缓存",
                                    "确定清理应用缓存吗？（当前 $cacheLabel）\n" +
                                        "仅清理缩略图等可再生成的缓存与上传残留，已上传的文件、播放记录不受影响。",
                                    "清理"
                                ) {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            // 先清上传残留（10 分钟保护窗口：正在上传的临时文件不会被删），
                                            // 再清 cacheDir —— 两者互不重叠（upload_tmp 在 files/ 下，不在 cacheDir）
                                            val leftovers = TransHttpServer.purgeOrphanUploadTemps(context)
                                            FileUtils.clearAppCache(context) to leftovers
                                        }
                                        refreshCache()
                                        Toast.makeText(
                                            context,
                                            "已清理缓存，释放 ${FileUtils.formatSize(result.first)}" +
                                                if (result.second > 0) "，另清除 ${result.second} 个上传残留" else "",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                })
                            }
                            // 不可达索引：指向「已彻底不在设备上」的存储的历史记录。
                            // 对账按「读不到 ≠ 被删」原则永不清理这些记录，媒体库又按活动沙盒过滤
                            // 显示不到它们 —— 此前前端**没有任何出口**能清掉，只能一直躺在库里。
                            // 条数按需查询（点开才算），避免每次进页面都全表扫一遍。
                            SettingRow(
                                "${g.title}:8", "清理不可达索引", "清理失效记录",
                                subtitle = "仅清理已不在设备上的存储（如已永久移除的 U 盘）留下的索引",
                                bottomEdge = true
                            ) {
                                scope.launch {
                                    // 卷列表为空 ⇒ 存储状态尚未就绪，此时「不可达」无法判定（见
                                    // MediaRepository.orphanIndexPaths 的空集守卫），直接提示、不弹确认框
                                    if (!mediaRepo.storageReady()) {
                                        Toast.makeText(
                                            context, "存储状态尚未就绪，请稍后再试", Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    val n = mediaRepo.countOrphanIndexes()
                                    if (n <= 0) {
                                        Toast.makeText(
                                            context, "没有不可达的索引记录，无需清理", Toast.LENGTH_SHORT
                                        ).show()
                                        return@launch
                                    }
                                    openConfirm("${g.title}:8", ConfirmState(
                                        "清理不可达索引",
                                        "有 $n 条索引指向已不在设备上的存储，清理会连同其播放历史一并删除。\n" +
                                            "若那块盘只是临时拔出，请先插回再操作 —— 否则插回后需要重新扫描，" +
                                            "且这些文件的播放进度会丢失。",
                                        "清理 $n 条",
                                        destructive = true
                                    ) {
                                        scope.launch {
                                            val removed = mediaRepo.purgeOrphanIndexes()
                                            Toast.makeText(
                                                context, "已清理 $removed 条不可达索引", Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    })
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
        // 选项列表独立滚动状态：换弹框（标题变化）时回到顶部，
        // 打开后由「选中项请求焦点」自带的 bring-into-view 把当前值滚进视野。
        val listScroll = remember(cs.title) { ScrollState(0) }
        Dialog(onDismissRequest = { choiceState = null }) {
            // 选项个数是**可变**的（设备名 8 项、存储卷 N 项、保活策略还带逐项说明），
            // 矮屏上很容易超出屏幕高度 —— 而 Dialog 的内容超出可视区时**只会被裁掉、不会滚动**
            // （用户实测：「设备名称」弹框列了很多名字，后面的看不到也够不到）。
            // 这里用 BoxWithConstraints 量出弹框实际可用高度，给选项列表一个高度上限 +
            // verticalScroll：装得下 → 贴内容高度（外观与改造前逐像素一致）；
            // 装不下 → 列表内滚动（遥控器焦点上下移动自动带进视野，手机可手指拖动）。
            BoxWithConstraints {
                val config = LocalConfiguration.current
                val compact = config.screenHeightDp < COMPACT_SCREEN_HEIGHT_DP
                val outerPadding = if (compact) 18.dp else 28.dp
                // 预留：上下内边距 + 标题行 + 标题下间距（+ 少量余量）
                val chrome = outerPadding * 2 + 38.dp
                // maxHeight 是「本地 dp」—— 内容区已按 [COMPACT_CONTENT_DENSITY_SCALE] 覆盖过密度，
                // 本地 dp 比物理 dp 小。兜底：万一平台给的是无限约束，用物理屏高换算成同一套本地 dp。
                val scale = if (compact) COMPACT_CONTENT_DENSITY_SCALE else 1f
                val available = if (maxHeight == Dp.Infinity) {
                    config.screenHeightDp.dp / scale * 0.92f
                } else {
                    maxHeight * 0.98f
                }
                val listMaxHeight = (available - chrome).coerceAtLeast(140.dp)
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(outerPadding).width(380.dp)) {
                        Text(
                            cs.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                        Column(
                            Modifier
                                .heightIn(max = listMaxHeight)
                                .verticalScroll(listScroll)
                        ) {
                            cs.options.forEachIndexed { i, opt ->
                                OptionRow(
                                    text = opt,
                                    selected = i == cs.selectedIndex,
                                    modifier = if (i == cs.selectedIndex) {
                                        Modifier.focusRequester(selectedOptionFocus)
                                    } else Modifier
                                ) {
                                    choiceState = null
                                    // 操作轨迹（V，仅详细日志）：设置项变更（弹框标题 + 新选值）。
                                    // 覆盖本页所有单选设置，一处即可记住「谁在什么时候改了什么」。
                                    AppLogger.v(TAG, "设置变更：${cs.title} → ${cs.options.getOrNull(i)}")
                                    cs.onPick(i)
                                }
                                // 逐项说明（灰色小字，对齐选项文字起点）：仅当调用方提供了 descriptions 时渲染
                                cs.descriptions?.getOrNull(i)?.let { desc ->
                                    Text(
                                        desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = OnDarkDim,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, end = 4.dp, bottom = 6.dp)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
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
                            // 破坏性 / 清理操作留痕（I）：确认执行时落一条（含标题，如「清理缓存」
                            // 「清理不可达索引」），便于事后核对「谁在何时清过什么」。
                            AppLogger.i(TAG, "确认执行：${cf.title}")
                            cf.onConfirm()
                        }
                        TvButton("取消") { confirmState = null }
                    }
                }
            }
        }
    }

}
