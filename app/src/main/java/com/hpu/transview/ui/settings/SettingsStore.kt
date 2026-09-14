package com.hpu.transview.ui.settings

import android.content.Context
import com.hpu.transview.model.AspectRatio
import com.hpu.transview.model.SortOrder

/**
 * 设置页偏好值的持久化存储（SharedPreferences）。
 *
 * 这里保存的是用户在各分组里做出的选择（端口、设备名、倍速、网格列数等），
 * 供设置页展示与记忆。部分参数尚未真正接入运行时（见各字段注释，标注「待实现」），
 * 保存它们是为了后续接线时能拿到用户选择。
 */
object SettingsStore {

    private const val PREFS = "transview_settings"

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private fun prefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ——— 服务器与网络 ———

    /** 服务器端口（默认 8080）。运行时仍固定用 Constants.PORT，此值待接入。 */
    var serverPort: Int
        get() = prefs()?.getInt("server_port", 8080) ?: 8080
        set(v) { prefs()?.edit()?.putInt("server_port", v)?.apply() }

    /** 开机自启（默认关）。待实现收听 BOOT_COMPLETED。 */
    var bootAutostart: Boolean
        get() = prefs()?.getBoolean("boot_autostart", false) ?: false
        set(v) { prefs()?.edit()?.putBoolean("boot_autostart", v)?.apply() }

    /** 设备名称（网页上传页展示名，默认「传视TV」）。待接入上传页。 */
    var deviceName: String
        get() = prefs()?.getString("device_name", "传视TV") ?: "传视TV"
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