package com.hpu.transview.ui

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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.hpu.transview.model.Category
import com.hpu.transview.model.MainTab
import com.hpu.transview.server.ServerBus
import com.hpu.transview.server.ServerController
import com.hpu.transview.ui.library.LibraryScreen
import com.hpu.transview.ui.settings.SettingsScreen
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
    // 「设置」标签独立的焦点请求器（从设置内容区按上键回到它）
    val settingsFocusRequester = remember { FocusRequester() }

    // 内容区「聚焦首行」请求票据：顶部标签每按一次 ↓ 自增，由内容区页面消费
    // （媒体库 → 网格第一行）。必须显式指定，不能交给 Compose 方向搜索：
    // 工具条按钮在空间上离标签更近，会把焦点吸到「排序」上（用户实测反馈）。
    var contentFocusTicket by remember { mutableIntStateOf(0) }
    // 「设置」标签按 ↓ 进入设置页的首个左侧分组，由 SettingsScreen 消费
    var settingsFocusTicket by remember { mutableIntStateOf(0) }

    // 触摸锚定计数：内容区被点按一次自增一次（见内容区 Box 的 focusable/pointerInput 注释）。
    // tap 手势期间 Compose 会清空焦点，立即 requestFocus 会被随后的清空覆盖（实测：立即请求时
    // 目标卡片的 requestFocus 连试 10 次全部失败），所以统一延后一拍再锚定。
    var touchAnchorTick by remember { mutableIntStateOf(0) }
    val contentAnchor = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    val rootView = LocalView.current
    LaunchedEffect(touchAnchorTick) {
        if (touchAnchorTick == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        // 点按可能已经打开了播放器 / 图片查看器（本页已退到后台），此时不该抢焦点
        if (!windowInfo.isWindowFocused) return@LaunchedEffect
        runCatching { contentAnchor.requestFocus() }
        // 兜底：把焦点票据投给内容区，让焦点继续落到本页第一个可聚焦元素
        // （记录首行 / 网格首项 / 设置首项）。即使锚点没拿到焦点，这一票也不会切页。
        if (showSettings) settingsFocusTicket++ else contentFocusTicket++
    }

    // 省电模式：仅上传页可见时允许服务器运行
    LaunchedEffect(selected) {
        ServerController.setUploadPageVisible(selected == MainTab.UPLOAD)
    }

    // 把焦点送回顶部导航栏「当前选中的标签」：返回键、以及内容区工具条按上键时都用它。
    // 用 remember(selected) 捕获当前标签，避免闭包一直停在初始值。
    val focusSelectedTab: () -> Unit = remember(selected) {
        { runCatching { tabFocusRequesters[selected.ordinal].requestFocus() } }
    }

    // 返回键：先交给内容区（媒体库在子目录时返回上一级，它注册的 BackHandler 优先级更高），
    // 内容区不处理时回到顶部导航栏。
    // 必须用 BackHandler 而非 Modifier.onKeyEvent —— onKeyEvent 只在焦点路径上才收得到事件，
    // 从播放页返回后内容区焦点为空时会漏掉返回键，Activity 被系统直接 finish（表现为「返回键退出 App」）。
    BackHandler { focusSelectedTab() }

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
                "传视 TransView",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(28.dp))
            MainTab.entries.forEachIndexed { index, tab ->
                TabChip(
                    title = tab.title,
                    // 设置页打开时不显示媒体标签的「选中」态，避免「其他」残留高亮
                    selected = !showSettings && tab == selected,
                    onNavigateDown = { contentFocusTicket++ },
                    modifier = Modifier
                        .focusRequester(tabFocusRequesters[index])
                        .onFocusChanged { state ->
                            if (state.isFocused) {
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
                title = "设置",
                selected = showSettings,
                onNavigateDown = { settingsFocusTicket++ },
                modifier = Modifier
                    .focusRequester(settingsFocusRequester)
                    .onFocusChanged { if (it.isFocused && !showSettings) showSettings = true }
            ) { showSettings = true }
            Spacer(Modifier.width(20.dp))
            ServerStatusBadge()
        }

        // 内容区（返回键见上面的 BackHandler）
        // 注：不要在这里挂 `focusProperties { exit = { FocusRequester.Cancel } }` 当「焦点围墙」——
        // 实测它会连带让内容区内的程序化 `requestFocus()`（如首行按上键跳到本页工具条）失效。
        // 边界保护统一走各页面自己的 `onPreviewKeyEvent` 拦截。
        Box(
            Modifier
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
                                // 只在 Press 阶段同步调用才来得及（晚了 touch mode 已生效）
                                rootView.requestFocusFromTouch()
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
                    onFocusTabs = { runCatching { settingsFocusRequester.requestFocus() } }
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
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged { focused = it.isFocused }
            // 标签按「下键」→ 显式交给内容区（见 MainScreen.contentFocusTicket 注释）。
            // 放在 clickable 之前，与页面内卡片的按键处理保持一致。
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    onNavigateDown()
                    true
                } else false
            }
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
            title,
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
            // 首页只显示模式名本身（极速/智能/省电），不带「模式」后缀；设置页等处仍用完整 label
            "· ${mode.label.removeSuffix("模式")}",
            style = MaterialTheme.typography.bodyMedium,
            color = OnDarkDim
        )
    }
}
