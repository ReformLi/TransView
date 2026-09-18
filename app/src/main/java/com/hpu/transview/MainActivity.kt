package com.hpu.transview

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hpu.transview.service.ServerService
import com.hpu.transview.ui.MainScreen
import com.hpu.transview.ui.permission.PermissionScreen
import com.hpu.transview.ui.common.BackKeySignal
import com.hpu.transview.ui.common.ProvideTouchMode
import com.hpu.transview.ui.settings.SettingsStore
import com.hpu.transview.ui.theme.TransViewTheme
import com.hpu.transview.util.AppLogger
import com.hpu.transview.util.IntentUtils
import com.hpu.transview.util.NotificationPermission
import com.hpu.transview.util.StoragePermission

class MainActivity : ComponentActivity() {

    private companion object {
        private const val TAG = "MainActivity"
    }

    /** 本次 Back 手势是否已收到配对的 `ACTION_DOWN`（收到才认为这是一个「新的按键」）。 */
    private var backDownArmed = false

    /** 上一次**通过去重闸**的 Back 抬起的事件时间戳（与 `uptimeMillis()` 同基准，用于量重复投递的间隔）。 */
    private var lastBackEventTime = 0L

    /**
     * Back 键「派发链断裂」的兜底（真机电视专项，2026-09-18）。
     *
     * ## 系统原有的 Back 派发链（API 33 以下的旧语义，本项目真机是 Android 10~12）
     * ```
     * ACTION_DOWN → Activity.onKeyDown(KEYCODE_BACK) → event.startTracking()
     * ACTION_UP   → Activity.onKeyUp  且 event.isTracking() && !isCanceled()
     *             → onBackPressed() → OnBackPressedDispatcher → 我们的 BackHandler
     * ```
     * **关键**：只要 `ACTION_DOWN` 没走到 `onKeyDown`，`startTracking()` 就不会被调用，
     * 于是抬起时 `isTracking()` 恒为 false、`onKeyUp` **静默丢弃** —— 整条链断掉，
     * `BackHandler` 永远不执行。而 DOWN 被吞掉的情形在真机上确实存在（触摸模式退出按键被框架
     * 消费、焦点树消费、窗口焦点切换导致 tracking 状态被重置等）；模拟器不产生这种切换，故不复现。
     *
     * ## 症状完全对得上
     * 「**第一次**按返回键完全无副作用（连媒体库的目录都不变），**第二次**才生效」——
     * 第一次按键让设备退出了触摸模式，第二次的 DOWN 才能正常走完整条链。
     * 同一现象在工程里早有旁证：本工程设置页实测到「Back 一按下内容区 `hasFocus` 立刻翻转」，
     * 因而不得不改用 `onPreviewKeyEvent` 提前拦截（`SettingsScreen` 的那段注释）。
     *
     * ## 兜底策略
     * 只在「系统确实不会替我们调 `onBackPressed()`」时才自己调一次并消费该 UP：
     * * `isTracking && !isCanceled` ⇒ 旧路径会正常派发 ⇒ 原样放行，本方法不介入；
     * * 否则 ⇒ 旧路径一定不会派发 ⇒ 由我们补一次，避免这次按键「凭空消失」。
     * 两个分支互斥，正常情况下 `BackHandler` 只会被执行一次 —— 但**前提是这次按键只投递一个抬起**，
     * 见下面的去重闸。
     *
     * ## 去重闸（与兜底配套，缺一不可）
     * 真机遥控器的一次按下**可能投递两个抬起**（该遥控器事件序列与键盘不同 —— OK 键上已实测
     * 「按住只送一串重复 `KeyDown` + 最后一个 `KeyUp`」）。补派发会让每个抬起都变成一次完整的
     * Back 处理，于是出现「**一次按下 = 两步操作**」，这正是 2026-09-18 真机反馈的两条症状：
     * * 焦点在「上传」标签上按**一次**返回 → 先弹「再按一次返回键退出应用」、随即**直接退出应用**；
     * * 文件夹里按**一次**返回 → **连跳两级**、越过本分类根目录，看到沙盒总目录下的 `Pictures`
     *   （「图片总文件夹」本不该从图片页到达；实现侧另有 `LibraryScreen.goUp` 的越界钳位兜底）。
     * 判据（两条**同时**成立才丢弃，见 `dup` 的定义）：
     *  ① 本次抬起**没有配对的按下** —— 一次完整的按下-抬起周期必然先收到 `ACTION_DOWN`；
     *  ② 距上一次处理过的事件 ≤ [BackKeySignal.DEDUP_MS]（150ms）。
     * 第 ① 条不能省：个别电视输入栈会把事件的 `eventTime` 冻结成同一个值（那时间隔恒为 0），
     * 只看间隔会误丢合法的按键。人类也无法在 150ms 内松开再按下返回键，故不误伤「连按两次退出」。
     *
     * 另外这里向 [BackKeySignal] 发一次信号：`BackHandler` 本次可能根本没被执行，
     * 而「按完返回键焦点无人持有」的兜底复检需要一个**无条件**能看到 Back 的触发点，
     * 本方法正是全工程唯一满足该条件的地方。**该信号只对通过去重闸的按键发出**，
     * 否则复检也会被同一次按键触发两次。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // 记录「本次按键确实收到了按下」：没有配对的按下却收到抬起，即为重复投递
                    // （见去重闸判据②；该判据另外要求「距上次处理很近」，故只影响重复投递）。
                    backDownArmed = true
                    AppLogger.d(TAG, "Back 按下（tracking=${event.isTracking}）")
                }
                KeyEvent.ACTION_UP -> {
                    val hadDown = backDownArmed
                    backDownArmed = false
                    // 判据（两条**同时**成立才丢弃）：本次抬起**没有配对的按下**，且距上次处理 ≤ DEDUP_MS。
                    // 「没有配对按下」这一条不能省：个别电视输入栈会把所有事件的 eventTime 冻结成同一个
                    // 值（那时间隔恒为 0），若只看间隔，合法的第二次按下会被误丢 —— 那会让返回键时灵时不灵。
                    val dup = !hadDown &&
                        lastBackEventTime > 0L &&
                        event.eventTime - lastBackEventTime <= BackKeySignal.DEDUP_MS
                    if (dup) {
                        // W 级（无需开启详细日志即落盘）：这一行就是「一次按键被投递两遍」的直接证据。
                        AppLogger.w(
                            TAG,
                            "Back 抬起：同一次按键的重复投递（无配对按下，间隔 " +
                                "${event.eventTime - lastBackEventTime}ms）→ 已丢弃"
                        )
                        return true
                    }
                    lastBackEventTime = event.eventTime
                    BackKeySignal.fire()
                    if (event.isTracking && !event.isCanceled) {
                        // 旧路径会自行派发，放行即可（本方法对该事件不做任何处理）
                        AppLogger.d(TAG, "Back 抬起：走系统旧路径（tracking=true）")
                    } else {
                        AppLogger.i(
                            TAG,
                            "Back 抬起：系统不会派发（tracking=${event.isTracking} " +
                                "canceled=${event.isCanceled}）→ 自行补派发一次"
                        )
                        onBackPressedDispatcher.onBackPressed()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App 恒定深色主题：系统栏固定「深色样式」（浅色前景图标）。默认 auto 跟随系统深浅模式，
        // 手机系统浅色时状态栏图标是深色，压在 App 近黑背景上看不清（时间/电量）——
        // TV 无状态栏不受影响，手机上实测过（用户反馈「顶部导航栏背景变黑、看不清」）。
        // scrim 透明：状态栏/导航栏区域透出 App 背景色（#0E1116），不叠系统灰底。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        AppLogger.d(TAG, "启动前台服务（ServerService）")
        ServerService.start(this)
        setContent {
            TransViewTheme {
                ProvideTouchMode {
                    AppRoot()
                }
            }
        }
    }

    @Composable
    private fun AppRoot() {
        val context = LocalContext.current
        var granted by remember { mutableStateOf(StoragePermission.isGranted(context)) }

        // ——— 通知权限（API 33+）———
        // 前台服务的常驻通知是用户了解「服务器运行中 / 已休眠 / 已暂停」的**唯一**途径。清单早已
        // 声明 POST_NOTIFICATIONS，但此前全工程没有运行时申请 → Android 13+ 上通知被系统静默丢弃。
        //
        // 两个刻意的取舍：
        //  ① 放在**存储授权之后**申请 —— 存储是硬门槛（过不了连主界面都看不到），通知是可选增强，
        //     不该和硬门槛抢用户看到的第一个授权框；
        //  ② 用 SettingsStore.notifPermissionAsked 保证**只主动弹一次** —— 系统对同一权限只会展示
        //     有限次授权框，反复申请只会变成「点了没反应」的无效操作。
        // 申请结果不参与任何逻辑：拒绝只是通知不可见，服务器照常运行，故回调刻意留空。
        val notifLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* 拒绝不影响服务器运行，无需处理 */ }
        LaunchedEffect(granted) {
            if (!granted) return@LaunchedEffect
            if (Build.VERSION.SDK_INT < 33) return@LaunchedEffect
            if (NotificationPermission.isGranted(context)) return@LaunchedEffect
            if (SettingsStore.notifPermissionAsked) return@LaunchedEffect
            SettingsStore.notifPermissionAsked = true
            AppLogger.i(TAG, "首次申请通知权限（POST_NOTIFICATIONS）")
            notifLauncher.launch(NotificationPermission.PERMISSION)
        }

        // 从系统授权页返回后重新检查
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val now = StoragePermission.isGranted(context)
                    // 只在**真的变化**时记录并赋值（赋相同值本来也不会触发重组）：
                    // 能从系统授权页回来就说明用户刚操作过，留痕便于对齐「为什么这时候才进主界面」
                    if (now != granted) {
                        AppLogger.i(TAG, "存储权限状态变更：$granted → $now")
                        granted = now
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (granted) {
            MainScreen()
        } else {
            PermissionScreen(
                onRequestPermission = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        // 部分 ROM hook 了 Instrumentation，隐式 Intent 会静默 NPE，须经 IntentUtils 显式解析组件启动
                        val ok = IntentUtils.startSafely(
                            this,
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            ),
                            "无法打开授权页面"
                        )
                        if (!ok) {
                            AppLogger.w(TAG, "打开「所有文件访问」授权页失败，改跳应用详情页兜底")
                            // 兜底：引导到应用详情页手动开启
                            IntentUtils.startSafely(
                                this,
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName")
                                ),
                                "请手动到 设置 → 应用 → 传视 开启「所有文件访问」"
                            )
                        }
                    } else {
                        StoragePermission.requestLegacy(this@MainActivity)
                    }
                }
            )
        }
    }
}
