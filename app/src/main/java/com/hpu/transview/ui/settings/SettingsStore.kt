package com.hpu.transview.ui.settings

import android.content.Context
import com.hpu.transview.model.AspectRatio
import com.hpu.transview.model.SortOrder
import com.hpu.transview.util.Constants

/**
 * 设置页偏好值的持久化存储（SharedPreferences）。
 *
 * 这里保存的是用户在各分组里做出的选择（端口、设备名、倍速、网格列数等），
 * 供设置页展示与记忆。
 *
 * 接线状态（2026-09-14）：
 * - 已接入运行时：服务器端口（`ServerController.setPort`）、开机自启（`BootReceiver`）、
 *   设备名称（`TransHttpServer` 渲染上传网页时注入）；
 * - 仍未接线（字段注释标注「待实现」）：续播提示 / 连播 / 默认倍速 / 默认画面比例、网格列数 / 默认排序。
 */
object SettingsStore {

    private const val PREFS = "transview_settings"

    /** 设备名称默认值（同时是设置页候选列表首项） */
    const val DEFAULT_DEVICE_NAME = "传视TV"

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ——— 服务器与网络 ———

    /** 服务器监听端口（默认 8080）。已接入运行时：ServerController.setPort() 切换后立即生效 */
    var serverPort: Int
        get() {
            val v = prefs()?.getInt("server_port", Constants.DEFAULT_PORT) ?: Constants.DEFAULT_PORT
            return if (v in Constants.PORT_RANGE) v else Constants.DEFAULT_PORT
        }
        set(v) { prefs()?.edit()?.putInt("server_port", v)?.apply() }

    /** 开机自启（默认关）。已接入 BootReceiver（监听 BOOT_COMPLETED 拉起前台服务） */
    var bootAutostart: Boolean
        get() = prefs()?.getBoolean("boot_autostart", false) ?: false
        set(v) { prefs()?.edit()?.putBoolean("boot_autostart", v)?.apply() }

    /**
     * 设备名称（默认「传视TV」）。已接入手机上传网页：
     * TransHttpServer 渲染 index.html 时注入，作为手机端页面标题与页头标题。
     */
    var deviceName: String
        get() = prefs()?.getString("device_name", DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        set(v) { prefs()?.edit()?.putString("device_name", v)?.apply() }

    // ——— 播放设置 ———

    /** 自动续播提示（默认开）。待接入播放器续播弹窗逻辑。 */
    var autoResumePrompt: Boolean
        get() = prefs()?.getBoolean("auto_resume_prompt", true) ?: true
        set(v) { prefs()?.edit()?.putBoolean("auto_resume_prompt", v)?.apply() }

    /** 自动连播（默认开）。待接入播放器连播逻辑。 */
    var autoPlayNext: Boolean
        get() = prefs()?.getBoolean("auto_play_next", true) ?: true
        set(v) { prefs()?.edit()?.putBoolean("auto_play_next", v)?.apply() }

    /** 默认倍速（默认 1.0x）。待接入播放器默认倍速。 */
    var defaultSpeed: Float
        get() = prefs()?.getFloat("default_speed", 1.0f) ?: 1.0f
        set(v) { prefs()?.edit()?.putFloat("default_speed", v)?.apply() }

    /** 默认画面比例（默认原始）。待接入播放器。 */
    var defaultAspect: AspectRatio
        get() = prefs()?.getString("default_aspect", AspectRatio.ORIGINAL.name)
            ?.let { runCatching { AspectRatio.valueOf(it) }.getOrNull() }
            ?: AspectRatio.ORIGINAL
        set(v) { prefs()?.edit()?.putString("default_aspect", v.name)?.apply() }

    // ——— 界面设置 ———

    /** 媒体库网格列数（默认 5）。待接入媒体库 LazyVerticalGrid 列数。 */
    var gridColumns: Int
        get() {
            val v = prefs()?.getInt("grid_columns", 5) ?: 5
            return if (v in 4..6) v else 5
        }
        set(v) { prefs()?.edit()?.putInt("grid_columns", v)?.apply() }

    /** 媒体库默认排序方式（默认名称 A-Z）。待接入媒体库默认排序。 */
    var defaultSort: SortOrder
        get() = prefs()?.getString("default_sort", SortOrder.NAME_ASC.name)
            ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
            ?: SortOrder.NAME_ASC
        set(v) { prefs()?.edit()?.putString("default_sort", v.name)?.apply() }
}