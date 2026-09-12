package com.hpu.transview.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/** 网络与服务器常量 */
object Constants {
    const val PORT = 8080
    const val URL_PATH = "/upload"
}

object NetUtils {

    /** 获取本机局域网 IPv4（优先 Wi-Fi 网卡），无网络时返回 null */
    fun getLocalIpAddress(): String? {
        return runCatching {
            val candidates = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                for (addr in ni.interfaceAddresses) {
                    val a = addr.address
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        candidates += a.hostAddress ?: continue
                    }
                }
            }
            // 优先常见局域网段
            candidates.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
                ?: candidates.firstOrNull()
        }.getOrNull()
    }
}

/** 存储权限统一入口：API 30+ 走「所有文件访问」，低版本走传统读写权限 */
object StoragePermission {

    fun isGranted(context: Context): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            if (android.os.Environment.isExternalStorageManager()) return true
            // 部分 ROM/模拟器未强制分区存储（如 MuMu：无授权入口但公共目录实际可写），
            // 用真实写探针判断：能写就放行，真实设备上无授权时探测必然失败，行为不变
            canWritePublicDirs()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 在沙盒 /sdcard/TransView/Movies 建临时文件再删除，实测可写性 */
    private fun canWritePublicDirs(): Boolean = runCatching {
        val dir = FileLocations.root(com.hpu.transview.model.Category.VIDEO)
        if (!(dir.exists() || dir.mkdirs()) || !dir.isDirectory) return false
        val probe = java.io.File(dir, ".transview_probe")
        val ok = probe.createNewFile()
        if (ok) probe.delete()
        ok
    }.getOrDefault(false)

    /** 低版本运行时申请（API 23-29；结果经 onResume 重新检查生效） */
    fun requestLegacy(activity: Context) {
        if (android.os.Build.VERSION.SDK_INT in 23..29) {
            val act = activity as? android.app.Activity ?: return
            act.requestPermissions(
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100
            )
        }
    }

    @Suppress("DEPRECATION")
    fun wifiManager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
}
