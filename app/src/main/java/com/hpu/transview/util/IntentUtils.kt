package com.hpu.transview.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast

/**
 * 安全启动 Activity 的统一入口。
 *
 * 部分 ROM（如 MuMu 模拟器）hook 了系统 Instrumentation，
 * 对隐式 Intent（component 为 null）直接调用 getComponent().getClassName() 会 NPE，
 * 导致 startActivity 静默失败。这里先把隐式 Intent 解析为显式组件再启动，规避该问题。
 */
object IntentUtils {

    fun startSafely(context: Context, intent: Intent, failMessage: String): Boolean {
        return try {
            var toStart = intent
            if (toStart.component == null) {
                resolveActivity(context, toStart)?.let { info ->
                    toStart = toStart.setComponent(
                        ComponentName(info.packageName, info.name)
                    )
                }
            }
            context.startActivity(toStart)
            true
        } catch (e: Exception) {
            Toast.makeText(context, failMessage, Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun resolveActivity(context: Context, intent: Intent) = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }.firstOrNull()?.activityInfo
    }.getOrNull()
}
