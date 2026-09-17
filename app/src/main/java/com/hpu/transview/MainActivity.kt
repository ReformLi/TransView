package com.hpu.transview

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
