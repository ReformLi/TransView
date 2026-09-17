package com.hpu.transview.util

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/** 网络与服务器常量 */
object Constants {
    /** 服务器默认端口（预设列表首项）。用户可在「设置 → 服务器与网络 → 服务器端口」修改，
     *  运行时的真实端口统一取自 SettingsStore.serverPort（经 ServerController 生效）。 */
    const val DEFAULT_PORT = 2333

    /** 设置页预设端口候选（电视遥控器无键盘，用预设免输入；首项 = DEFAULT_PORT）。
     *  存盘值不在本列表内时（旧版本遗留端口）回落到 DEFAULT_PORT。 */
    val ALLOWED_PORTS = listOf(2333, 5210, 8080, 8888, 9527)

    /** 可选端口范围：1024 以下为特权端口，普通应用无 root 无法绑定 */
    val PORT_RANGE = 1024..65535

    const val URL_PATH = "/upload"
}

object NetUtils {

    private const val TAG = "NetUtils"

    /** 获取本机局域网 IPv4（优先 Wi-Fi 网卡），无网络时返回 null */
    fun getLocalIpAddress(): String? {
        val ip = runCatching {
            val candidates = mutableListOf<String>()
            // 原先这里是 `?: return null` 直接静默返回（连异常都不会记），改成抛出让下面的
            // onFailure 统一留痕，否则「接口枚举拿到 null」这种情况在日志里永远是空白
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?: error("NetworkInterface.getNetworkInterfaces() 返回 null")
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
        }.onFailure { AppLogger.w(TAG, "枚举网络接口失败", it) }.getOrNull()
        if (ip == null) {
            // 返回 null 时上传页只显示「无法获取网络地址」占位。留一条 W，
            // 让「网卡没起来 / 该网络只分配了 IPv6 / 接口枚举异常」这三种情况事后可分
            AppLogger.w(TAG, "未找到可用的局域网 IPv4 地址（无网卡，或该网络只分配了 IPv6）")
        }
        return ip
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
}

/**
 * 通知权限统一入口（`POST_NOTIFICATIONS`，Android 13 / API 33 起才是运行时权限；
 * 更低版本恒视为已授予）。
 *
 * 为什么需要：前台服务（[com.hpu.transview.service.ServerService]）的常驻通知是用户了解
 * 服务器状态（运行中 / 已休眠 / 已暂停）的**唯一**途径。清单早已声明该权限，但此前全工程
 * 没有运行时申请 —— Android 13+ 上通知被系统静默丢弃，用户看不到任何服务器状态。
 *
 * 注意：**通知被拒不影响服务器运行**，只是通知不可见，因此申请失败不得阻断任何流程。
 */
object NotificationPermission {

    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

    fun isGranted(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
}
