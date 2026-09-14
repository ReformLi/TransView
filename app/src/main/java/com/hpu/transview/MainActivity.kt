package com.hpu.transview

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.hpu.transview.ui.theme.TransViewTheme
import com.hpu.transview.util.IntentUtils
import com.hpu.transview.util.StoragePermission

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServerService.start(this)
        setContent {
            TransViewTheme {
                AppRoot()
            }
        }
    }

    @Composable
    private fun AppRoot() {
        val context = LocalContext.current
        var granted by remember { mutableStateOf(StoragePermission.isGranted(context)) }

        // 从系统授权页返回后重新检查
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = StoragePermission.isGranted(context)
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
