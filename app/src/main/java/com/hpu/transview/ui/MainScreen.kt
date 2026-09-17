package com.hpu.transview.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.hpu.transview.model.Category
import com.hpu.transview.model.MainTab
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.ServerController
import com.hpu.transview.ui.common.COMPACT_SCREEN_HEIGHT_DP
import com.hpu.transview.ui.common.CompactContentDensity
import com.hpu.transview.ui.common.requestFocusNextFrame
import com.hpu.transview.ui.common.LocalIsTouchMode
import com.hpu.transview.ui.library.LibraryScreen
import com.hpu.transview.ui.settings.SettingsScreen
import com.hpu.transview.ui.theme.OnDarkDim
import com.hpu.transview.ui.theme.SuccessGreen
import com.hpu.transview.ui.theme.DangerRed
import com.hpu.transview.ui.upload.UploadScreen
import com.hpu.transview.util.FileLocations
import com.hpu.transview.util.FileLocations.StorageEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 主界面：顶部四标签导航 + 内容区 */
@Composable
fun MainScreen() {
    var selected by rememberSaveable { mutableStateOf(MainTab.UPLOAD) }
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tabFocusRequesters = remember { MainTab.entries.map { FocusRequester() } }
    // 「设置」标签独立的焦点请求器（从设置内容区按上键回到它）
    val settingsFocusRequester = remember { FocusRequester() }

    // 内容区「聚焦首行」请求票据：顶部标签每按一次 ↓ 自增，由内容区页面消费
    // （媒体库 → 网格第一行）。必须显式指定，不能交给 Compose 方向搜索：
    // 工具条按钮在空间上离标签更近，会把焦点吸到「排序」上（用户实测反馈）。
    var contentFocusTicket by remember { mutableIntStateOf(0) }
    // 「设置」标签按 ↓ 进入设置页的首个左侧分组，由 SettingsScreen 消费
    var settingsFocusTicket by remember { mutableIntStateOf(0) }

    // ——— 返回键退出：焦点在非「上传」标签时返回先回「上传」（默认最左标签，作为退出前的"家"位置）；
    // ——— 焦点在「上传」标签上按返回弹提示，2 秒内再按才真正退出（对齐手机端双击返回习惯）———
    val context = LocalContext.current
    var topBarFocused by remember { mutableStateOf(false) }
    var lastBackTime by remember { mutableLongStateOf(0L) }

    // 触摸锚定计数：内容区被点按一次自增一次（见内容区 Box 的 focusable/pointerInput 注释）。
    // tap 手势期间 Compose 会清空焦点，立即 requestFocus 会被随后的清空覆盖（实测：立即请求时
    // 目标卡片的 requestFocus 连试 10 次全部失败），所以统一延后一拍再锚定。
    var touchAnchorTick by remember { mutableIntStateOf(0) }
    // 触摸手势期间的「聚焦即选中」抑制开关：requestFocusFromTouch() 在 touch mode 下执行的是
    // Compose 焦点搜索，无焦点时从整棵树根部命中第一个可聚焦节点 = 「上传」标签，标签聚焦即选中
    // → 点一下内容区页面被误切回上传（用户实测）。触摸用户不应因焦点注入而切页，故手势期间
    // 抑制标签的聚焦选中，仅保留「点按标签」这一直接交互来切页；手势结束后恢复。
    var touchSuppressingTabSwitch by remember { mutableStateOf(false) }
    val contentAnchor = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    val rootView = LocalView.current
    val config = LocalConfiguration.current
    // 手机横屏：高度方向 dp 较小（通常 < 480），顶部导航栏需紧凑化，否则在矮屏上占去半屏。
    // TV/盒子高度 dp 一般 >= 720，不进入紧凑模式。
    // 判据走共享常量 COMPACT_SCREEN_HEIGHT_DP：与上传页左面板、内容区整体缩放必须同阈值。
    val isCompact = config.screenHeightDp < COMPACT_SCREEN_HEIGHT_DP
    LaunchedEffect(touchAnchorTick) {
        if (touchAnchorTick == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        // 点按可能已经打开了播放器 / 图片查看器（本页已退到后台），此时不该抢焦点
        if (!windowInfo.isWindowFocused) {
            touchSuppressingTabSwitch = false
            return@LaunchedEffect
        }
        touchSuppressingTabSwitch = false
        if (showSettings) {
            // 设置页触摸路径：不抢锚点焦点（触点落在分组/条目上时，点击处理器已把焦点导向
            // 所选分组，这里再 contentAnchor.requestFocus() 会把内容区焦点清掉，导致下方票据
            // 消费时 contentFocused 误判为 false）。只投「首个分组」票据，由设置页消费时校验
            // contentFocused：触点已导向内容则跳过（否则把用户点的「关于」拉回「服务器与网络」），
            // 空处点按（内容区无焦点）才落到首个分组，方向键不至于卡在锚点上。
            settingsFocusTicket++
        } else if (selected == MainTab.UPLOAD) {
            // 上传页触摸路径：不抢锚点焦点（触点落在记录行上时，行的 Press 处理器已把焦点
            // 落到该行，这里再 contentAnchor.requestFocus() 会把 listHasFocus 清掉，导致下方
            // 票据消费时误判为 false）。只投票据，由上传页校验 listHasFocus：触点已导向列表
            // 则跳过（否则把用户点的行拉回第一行），空处点按（列表无焦点）才落到第一行。
            contentFocusTicket++
        } else {
            runCatching { contentAnchor.requestFocus() }
            // 兜底：把焦点票据投给内容区，让焦点继续落到本页第一个可聚焦元素
            // （记录首行 / 网格首项 / 设置首项）。即使锚点没拿到焦点，这一票也不会切页。
            contentFocusTicket++
        }
    }

    // 省电模式：仅上传页可见时允许服务器运行
    LaunchedEffect(selected) {
        ServerController.setUploadPageVisible(selected == MainTab.UPLOAD)
    }

    // ——— 外接盘插拔全局提示（Toast）———
    // 运行期降级/恢复事件来自 ServerService 的插拔广播（去抖重检后发出）；MainScreen 常驻
    // 组合，在这里统一弹 Toast 最简单。App 启动首次检测与设置页主动切换不发事件，
    // 故不会开机误弹。
    LaunchedEffect(Unit) {
        FileLocations.storageEvents.collect { event ->
            when (event) {
                is StorageEvent.UsbDetached ->
                    Toast.makeText(
                        context, "「${event.label}」已断开，已自动切换到内部存储", Toast.LENGTH_LONG
                    ).show()
                is StorageEvent.UsbAttached ->
                    Toast.makeText(
                        context, "「${event.label}」已恢复，正在使用", Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    // 把焦点送回顶部导航栏「当前选中的标签」：返回键、以及内容区工具条按上键时都用它。
    // 用 remember(selected) 捕获当前标签，避免闭包一直停在初始值。
    // 帧门控重试：单次 requestFocus 可能静默失败（焦点停在内容区 / 左侧分组），
    // 失败则 16ms 后再试，最多 10 次（与设置页弹框关闭的聚焦重试同一模式）。
    val focusSelectedTab: () -> Unit = remember(selected) {
        {
            scope.launch {
                val target = tabFocusRequesters[selected.ordinal]
                repeat(10) {
                    if (target.requestFocusNextFrame()) return@launch
                    delay(16)
                }
            }
        }
    }

    // 把焦点送回顶部「设置」标签（设置页内容区按上键时触发），同样带帧门控重试。
    val focusSettingsTab: () -> Unit = {
        scope.launch {
            repeat(10) {
                if (settingsFocusRequester.requestFocusNextFrame()) return@launch
                delay(16)
            }
        }
    }

    // 返回键把焦点送回「上传」标签（选中态由标签 onFocusChanged 自动跟随），带帧门控重试。
    val focusUploadTab: () -> Unit = {
        scope.launch {
            repeat(10) {
                if (tabFocusRequesters[MainTab.UPLOAD.ordinal].requestFocusNextFrame()) return@launch
                delay(16)
            }
        }
    }

    // 返回键分层：① 内容区（媒体库在子目录时返回上一级，它注册的 BackHandler 优先级更高）→
    // ② 焦点在顶部标签栏：非「上传」标签先回「上传」（退出前的"家"位置）；
    //    在「上传」标签上按返回弹提示，2 秒内再按才真正退出（只关界面，服务器服务照常运行）；
    // ③ 焦点在内容区根目录 → 回当前选中标签。
    // 必须用 BackHandler 而非 Modifier.onKeyEvent —— onKeyEvent 只在焦点路径上才收得到事件，
    // 从播放页返回后内容区焦点为空时会漏掉返回键，Activity 被系统直接 finish（表现为「返回键退出 App」）。
    BackHandler {
        when {
            // 焦点在媒体标签（非「上传」）→ 先回「上传」
            topBarFocused && !showSettings && selected != MainTab.UPLOAD -> focusUploadTab()
            // 焦点在「上传」标签 → 双击返回退出（2 秒窗口，离开上传标签即取消确认）
            topBarFocused && !showSettings && selected == MainTab.UPLOAD -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackTime <= 2000L) {
                    (context.findActivity())?.finish()
                } else {
                    lastBackTime = now
                    Toast.makeText(context, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show()
                }
            }
            // 其余（焦点在内容区等）→ 回当前选中标签（原行为）
            else -> focusSelectedTab()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
    ) {
        // 顶部导航栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isCompact) 16.dp else 40.dp, vertical = if (isCompact) 8.dp else 20.dp)
                // 跟踪「焦点是否在标签栏」：返回键据此区分「标签上的返回（回上传/退出）」与
                // 「内容区的返回（回选中标签）」。hasFocus 含子树（四个媒体标签 + 设置标签）。
                // 焦点离开标签栏（进入内容区 / 设置页）→ 取消未完成的「再按一次退出」确认。
                .onFocusChanged { state ->
                    topBarFocused = state.hasFocus
                    if (!state.hasFocus) lastBackTime = 0L
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isCompact) {
                Text(
                    "传视 TransView",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(28.dp))
            }
            MainTab.entries.forEachIndexed { index, tab ->
                TabChip(
                    compact = isCompact,
                    title = tab.title,
                    // 设置页打开时不显示媒体标签的「选中」态，避免「其他」残留高亮
                    selected = !showSettings && tab == selected,
                    onNavigateDown = { contentFocusTicket++ },
                    // 首个媒体标签是整行最左端（非紧凑态左侧的品牌标题不可聚焦）
                    stayOnLeftEdge = index == 0,
                    modifier = Modifier
                        .focusRequester(tabFocusRequesters[index])
                        .onFocusChanged { state ->
                            // 触摸手势期间抑制「聚焦即选中」：requestFocusFromTouch() 的焦点搜索
                            // 会把焦点注入「上传」标签，不能让它误切页面（见上方 flag 注释）。
                            if (state.isFocused && !touchSuppressingTabSwitch) {
                                // 切到非「上传」标签 → 取消未完成的「再按一次退出」确认
                                //（双击窗口只在焦点持续停留在「上传」标签时累计）
                                if (tab != MainTab.UPLOAD) lastBackTime = 0L
                                // 退出设置页必须**无条件**执行，不能塞进 `tab != selected` 条件里：
                                // 从「设置」切回「先前停留的那个标签」时 selected 本来就没变，条件不成立
                                // → showSettings 停在 true，内容区继续显示设置页
                                //（用户实测：焦点已落在「其他」标签、标签态也对，内容却还是设置项）。
                                showSettings = false
                                if (tab != selected) selected = tab
                            }
                        }
                ) { selected = tab; showSettings = false }
                Spacer(Modifier.width(14.dp))
            }
            //「设置」保持原有摆放：与媒体标签隔开、靠内容区右侧（仍是可聚焦的独立子菜单入口）。
            // 功能不变：聚焦即打开设置页、焦点留在其上、按 ↓ 进入设置页选项、内容区按上键回到它。
            Spacer(Modifier.weight(1f))
            TabChip(
                compact = isCompact,
                title = "设置",
                selected = showSettings,
                onNavigateDown = { settingsFocusTicket++ },
                // 「设置」是整行最右端（右侧只剩不可聚焦的状态徽标）：吃掉 →，防环绕到最左标签
                stayOnRightEdge = true,
                modifier = Modifier
                    .focusRequester(settingsFocusRequester)
                    .onFocusChanged {
                        if (it.isFocused && !showSettings && !touchSuppressingTabSwitch) showSettings = true
                    }
            ) { showSettings = true }
            Spacer(Modifier.width(20.dp))
            ServerStatusBadge(compact = isCompact)
        }

        // 内容区（返回键见上面的 BackHandler）
        // 注：不要在这里挂 `focusProperties { exit = { FocusRequester.Cancel } }` 当「焦点围墙」——
        // 实测它会连带让内容区内的程序化 `requestFocus()`（如首行按上键跳到本页工具条）失效。
        // 边界保护统一走各页面自己的 `onPreviewKeyEvent` 拦截。
        // ——— 矮屏（手机横屏）内容区整体等比缩放 ———
        // 顶部导航栏在**覆盖之外**，保持用户认可的尺寸（「标签栏字体和大小正合适」）；
        // 内容区里所有 dp/sp 同步缩到约 0.87，字号与间距/图标/卡片的比例关系不变。
        // 详见 CompactContentDensity 的注释。TV / 平板不做任何覆盖，逐像素不变。
        CompactContentDensity(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // ——— 触摸焦点锚点 ———
                // 触摸 / 鼠标点按内容区后，Compose 会清空焦点并让设备进入 touch mode；
                // 而 touch mode 下**默认可聚焦节点（Modifier.clickable / Modifier.focusable()）
                // 不接受程序化 requestFocus**（实测：点按后让卡片抢焦点，连试 10 次全部失败）。
                // 于是第一次按方向键时焦点必须从整棵树开始搜索，命中第一个可聚焦元素 ——
                // 顶部「上传」标签，而标签是「聚焦即选中」→ 页面被立刻切走。
                // 两处配合修复：
                //  ① Press 阶段调 View.requestFocusFromTouch() —— View 层「在 touch mode 下
                //     请求焦点」的入口，内部会先让 ViewRootImpl 退出 touch mode，之后的
                //     requestFocus 才会生效（这是根本动作，缺了它下面两步都无效）；
                //  ② 内容区挂一个自身无任何视觉的焦点锚点，点按结束后把焦点锚到这里，
                //     随后的方向键就从内容区开始搜索，自然落到本页第一个可聚焦元素
                //     （记录首行 / 网格首项 / 设置首项）。焦点若仍被页面票据接管则更好。
                // 遥控器按键不产生 Press 事件，因此不影响既有的遥控器焦点导航。
                .focusRequester(contentAnchor)
                .focusable()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                // 抑制 flag 必须先于 requestFocusFromTouch() 置位：该调用（或其后的
                                // 框架层焦点搜索）会同步把焦点注入「上传」标签，而标签 onFocusChanged
                                // 是「聚焦即选中」——flag 若晚设，页面会被立刻误切回上传（实测复现）。
                                // 先置位后，本次焦点注入被抑制，页面保持不动；点按标签仍可正常切页
                                //（标签 onClick 不走 flag 判断），flag 由下方 LaunchedEffect 复位。
                                touchSuppressingTabSwitch = true
                                // 只在 Press 阶段同步调用才来得及（晚了 touch mode 已生效）
                                rootView.requestFocusFromTouch()
                                // 该调用的 Compose 焦点搜索会命中「上传」标签（见上方 flag 注释），
                                // 立即把焦点拉回内容锚点，消除标签高亮闪烁；切页由 flag 兜底抑制。
                                runCatching { contentAnchor.requestFocus() }
                                touchAnchorTick++
                            }
                        }
                    }
                }
        ) {
            if (showSettings) {
                // 设置子页面占据内容区（顶部导航栏保持不变）。
                // 返回键经 SettingsScreen 内的 BackHandler 先退出设置页，再回顶部导航栏；
                // focusTicket：设置标签按 ↓ 时进入首个分组；onFocusTabs：内容区按上键回设置标签。
                SettingsScreen(
                    onExit = { showSettings = false; focusSelectedTab() },
                    focusTicket = settingsFocusTicket,
                    onFocusTabs = focusSettingsTab
                )
            } else when (selected) {
                MainTab.UPLOAD -> UploadScreen(
                    onFocusTabs = focusSelectedTab,
                    focusListTicket = contentFocusTicket
                )
                MainTab.VIDEO -> LibraryScreen(Category.VIDEO, onFocusTabs = focusSelectedTab, focusGridTicket = contentFocusTicket)
                MainTab.IMAGE -> LibraryScreen(Category.IMAGE, onFocusTabs = focusSelectedTab, focusGridTicket = contentFocusTicket)
                MainTab.OTHER -> LibraryScreen(Category.OTHER, onFocusTabs = focusSelectedTab, focusGridTicket = contentFocusTicket)
            }
        }
    }
}

/** 导航标签：聚焦即选中（遥控器左右切换，「设置」也作为标签之一） */
@Composable
private fun TabChip(
    title: String,
    selected: Boolean,
    onNavigateDown: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 行首标签：吃掉 ←（见下方按键注释） */
    stayOnLeftEdge: Boolean = false,
    /** 行尾标签：吃掉 → */
    stayOnRightEdge: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val isTouchMode = LocalIsTouchMode.current
    val showFocus = focused && !isTouchMode
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            // 标签按「下键」→ 显式交给内容区（见 MainScreen.contentFocusTicket 注释）。
            // 放在 clickable 之前，与页面内卡片的按键处理保持一致。
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { onNavigateDown(); true }
                    // 顶部标签行按「上」必须吃掉：否则按住上键（遥控器 auto-repeat）在焦点
                    // 回到标签后，剩余的重发 KeyDown 会进入焦点系统向上导航 —— 顶部已无目标，
                    // 焦点回退到整棵树第一个可聚焦元素 = 设置页左侧第一个分组
                    //（用户实测：按上键回「设置」标签后焦点自动掉回设置内容区）。
                    Key.DirectionUp -> true
                    // 标签行**两端**必须显式吃掉越界方向键（v1.28）：Compose 的方向搜索在某个
                    // 方向找不到候选时会「环绕」到另一个可聚焦元素，命中的往往是对端的标签，
                    // 而标签是「聚焦即选中」→ 一命中就切页（最右「设置」按 → 环绕到最左「上传」）。
                    // 媒体库网格卡片、上传页工具条早有同类拦截，标签行此前漏防。
                    Key.DirectionLeft -> stayOnLeftEdge
                    Key.DirectionRight -> stayOnRightEdge
                    else -> false
                }
            }
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    showFocus -> MaterialTheme.colorScheme.primary
                    selected -> MaterialTheme.colorScheme.surfaceVariant
                    else -> Color.Transparent
                }
            )
            .border(
                width = 1.dp,
                color = if (selected || showFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(50)
            )
            .clickable { onClick() }
            .padding(horizontal = if (compact) 14.dp else 30.dp, vertical = if (compact) 6.dp else 10.dp)
    ) {
        Text(
            title,
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            color = if (showFocus) MaterialTheme.colorScheme.onPrimary
            else if (selected) MaterialTheme.colorScheme.primary
            else OnDarkDim
        )
    }
}

/** 右上角服务器状态徽标（运行状态 + 当前模式） */
@Composable
private fun ServerStatusBadge(compact: Boolean = false) {
    val running by ServerBus.running.collectAsState()
    val mode by ServerBus.mode.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (running) SuccessGreen else DangerRed)
        )
        // 紧凑模式（手机横屏窄屏）只保留状态圆点，避免顶部栏横向溢出/占比过大。
        if (!compact) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (running) "服务器运行中" else "服务器已停止",
                style = MaterialTheme.typography.bodyMedium,
                color = if (running) SuccessGreen else DangerRed
            )
            Spacer(Modifier.width(10.dp))
            Text(
                // 首页只显示模式名本身（极速/智能/省电），不带「模式」后缀；设置页等处仍用完整 label
                "· ${mode.label.removeSuffix("模式")}",
                style = MaterialTheme.typography.bodyMedium,
                color = OnDarkDim
            )
        }
    }
}

/** 从任意 Context 向上找到宿主 Activity（返回键退出时 finish 用） */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
