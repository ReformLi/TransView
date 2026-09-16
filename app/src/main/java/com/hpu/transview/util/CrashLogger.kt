package com.hpu.transview.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘（v1.13）。
 *
 * 为什么需要它：电视端没有终端、拿不到 logcat，「白框一闪就退出」这类问题只能靠猜。
 * 这里接管未捕获异常，把堆栈写进沙盒 **`TransView/Downloads/crash_log.txt`** —— 与「其他」分类
 * 同目录，重启后对账即可在 App 内打开查看，也能拔盘/拷到电脑看（与「App 调试日志」同一套路）。
 *
 * 三条硬约束：
 * - **绝不吞异常**：写完仍然交给原处理器（默认行为不变，Logcat / 系统崩溃上报照旧）；
 * - **绝不二次崩溃**：全部 IO 包在 `runCatching` 里，且**不触碰** [FileLocations] / Room 等可能正是
 *   崩溃源头或依赖初始化顺序的东西 —— 落点直接用 `Environment` 拼内部存储沙盒；
 * - **不无限增长**：超过 [MAX_BYTES] 就把旧文件轮转为 `crash_log.old.txt`（只保留上一份）。
 *
 * 为什么固定写内部存储、而不是当前活动存储：崩溃可能正是因为那块 U 盘（拔出/挂载异常），
 * 往它上面写日志大概率失败；内部存储只要有存储权限就一定能写。
 */
object CrashLogger {

    private const val TAG = "TransView"

    /** 日志文件名（落在 `TransView/Downloads/` 下） */
    const val FILE_NAME = "crash_log.txt"

    /** 单文件上限：超出则把当前文件轮转为 `crash_log.old.txt` */
    private const val MAX_BYTES = 128 * 1024L

    @Volatile
    private var installed = false

    /** 安装全局未捕获异常处理器（[com.hpu.transview.TransViewApp.onCreate] 调用；幂等） */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 崩溃日志文件（供设置页/诊断展示；未产生过崩溃时不存在） */
    fun logFile(): File = File(File(Environment.getExternalStorageDirectory(), "TransView/Downloads"), FILE_NAME)

    /** 写一条崩溃记录（[MAX_BYTES] 超限时先轮转旧文件）；任何异常都吞掉，绝不影响原有崩溃流程 */
    @Suppress("DEPRECATION")
    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val file = logFile()
        file.parentFile?.mkdirs()
        if (file.length() > MAX_BYTES) {
            val old = File(file.parentFile, "crash_log.old.txt")
            runCatching { old.delete() }
            runCatching { file.renameTo(old) }
        }
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val version = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName}(${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode})"
        }.getOrDefault("?")
        val block = buildString {
            append("═══════════════════════════════════════\n")
            append("时间：${stamp()}\n")
            append("线程：${thread.name}\n")
            append("版本：$version\n")
            append("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}(SDK ${Build.VERSION.SDK_INT})\n")
            append("异常：${throwable.javaClass.name}: ${throwable.message}\n")
            append("─────────── 堆栈 ───────────\n")
            append(sw.toString())
            append("\n")
        }
        file.appendText(block, Charsets.UTF_8)
        Log.e(TAG, "崩溃已记录到 ${file.absolutePath}", throwable)
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
