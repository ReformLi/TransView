package com.hpu.transview.ui.settings

import android.content.Context
import android.os.Environment
import com.hpu.transview.model.AspectRatio
import com.hpu.transview.model.SortOrder
import com.hpu.transview.util.Constants

/**
 * 设置页偏好值的持久化存储（SharedPreferences）。
 *
 * 这里保存的是用户在各分组里做出的选择（端口、设备名、倍速、网格列数等），
 * 供设置页展示与记忆。
 *
 * 接线状态（2026-09-14）：**全部已接入运行时**。
 * - 服务器与网络：服务器端口（`ServerController.setPort`，停旧起新）、开机自启（`BootReceiver`）、
 *   设备名称（`TransHttpServer` 渲染上传网页时注入）；
 * - 播放设置：续播提示 / 自动连播 / 默认倍速 / 默认画面比例（`PlayerActivity.onCreate` 读取，
 *   打开视频即生效）；
 * - 界面设置：网格列数 / 默认排序（`LibraryScreen` 进入时读取，返回媒体库即生效）。
 */
object SettingsStore {

    private const val PREFS = "transview_settings"

    /** 设备名称默认值（同时是设置页候选列表首项） */
    const val DEFAULT_DEVICE_NAME = "传视"

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        // 一次性清理 v1.12/v1.13 的 SAF 相关键（v1.14 已彻底移除 SAF，留着只会误导排查）
        runCatching {
            prefs()?.edit()
                ?.remove("saf_tree_uri")
                ?.remove("saf_tree_label")
                ?.remove("manual_storage_path")
                ?.apply()
        }
    }

    private fun prefs(): android.content.SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ——— 服务器与网络 ———

    /** 服务器监听端口（默认 2333，候选见 Constants.ALLOWED_PORTS）。
     *  已接入运行时：ServerController.setPort() 切换后立即生效。
     *  读值时校验候选列表：旧版本存过已移除的端口（8081/8088/8089/9000 等）统一回落默认值。 */
    var serverPort: Int
        get() {
            val v = prefs()?.getInt("server_port", Constants.DEFAULT_PORT) ?: Constants.DEFAULT_PORT
            return if (v in Constants.ALLOWED_PORTS) v else Constants.DEFAULT_PORT
        }
        set(v) { prefs()?.edit()?.putInt("server_port", v)?.apply() }

    /** 开机自启（默认关）。已接入 BootReceiver（监听 BOOT_COMPLETED 拉起前台服务） */
    var bootAutostart: Boolean
        get() = prefs()?.getBoolean("boot_autostart", false) ?: false
        set(v) { prefs()?.edit()?.putBoolean("boot_autostart", v)?.apply() }

    /**
     * 设备名称（默认「传视」）。已接入手机上传网页：
     * TransHttpServer 渲染 index.html 时注入，作为手机端页面标题与页头标题。
     */
    var deviceName: String
        get() = prefs()?.getString("device_name", DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        set(v) { prefs()?.edit()?.putString("device_name", v)?.apply() }

    // ——— 播放设置 ———

    /** 自动续播提示（默认开）。已接入播放器：关闭后打开视频直接续播、不弹询问框。 */
    var autoResumePrompt: Boolean
        get() = prefs()?.getBoolean("auto_resume_prompt", true) ?: true
        set(v) { prefs()?.edit()?.putBoolean("auto_resume_prompt", v)?.apply() }

    /** 自动连播（默认开）。已接入播放器：关闭后一集播完停在片尾，不自动跳下一集。 */
    var autoPlayNext: Boolean
        get() = prefs()?.getBoolean("auto_play_next", true) ?: true
        set(v) { prefs()?.edit()?.putBoolean("auto_play_next", v)?.apply() }

    /** 默认倍速（默认 1.0x）。已接入播放器：作为打开视频时的初始倍速。 */
    var defaultSpeed: Float
        get() = prefs()?.getFloat("default_speed", 1.0f) ?: 1.0f
        set(v) { prefs()?.edit()?.putFloat("default_speed", v)?.apply() }

    /** 默认画面比例（默认原始）。已接入播放器：映射为 PlayerView 的 FIT / FILL / ZOOM。 */
    var defaultAspect: AspectRatio
        get() = prefs()?.getString("default_aspect", AspectRatio.ORIGINAL.name)
            ?.let { runCatching { AspectRatio.valueOf(it) }.getOrNull() }
            ?: AspectRatio.ORIGINAL
        set(v) { prefs()?.edit()?.putString("default_aspect", v.name)?.apply() }

    // ——— 界面设置 ———

    /** 媒体库网格列数（默认 5，合法 4~6）。已接入媒体库 `GridCells.Fixed` 与行首/行尾焦点判定。 */
    var gridColumns: Int
        get() {
            val v = prefs()?.getInt("grid_columns", 5) ?: 5
            return if (v in 4..6) v else 5
        }
        set(v) { prefs()?.edit()?.putInt("grid_columns", v)?.apply() }

    /** 媒体库默认排序方式（默认名称 A-Z）。已接入媒体库排序初值（工具条可临时改，不改本项）。 */
    var defaultSort: SortOrder
        get() = prefs()?.getString("default_sort", SortOrder.NAME_ASC.name)
            ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
            ?: SortOrder.NAME_ASC
        set(v) { prefs()?.edit()?.putString("default_sort", v.name)?.apply() }

    // ——— 存储与数据 ———

    /** 内部存储卷根路径（默认首选）：/storage/emulated/0 */
    private val internalVolumePath: String
        get() = Environment.getExternalStorageDirectory().absolutePath

    /**
     * 首选存储路径（默认内部存储）。已接入 FileLocations 的活动存储判定：
     * 首选卷可用 → 活动存储=首选卷；首选是外接盘但当前不可用（已拔出）→ 活动存储自动降级为
     * 内部存储（降级状态不持久化，每次检测按「首选路径 + 当前可用卷」实时推导，插回即自动恢复）。
     *
     * 存的是**卷根绝对路径**（如 `/storage/emulated/0`、`/storage/XXXX-XXXX`）。兼容旧版本写法：
     * - `INTERNAL` / `USB`（v1.1 的固定枚举名）→ 回落内部存储；
     * - 非 `/` 开头的值（v1.12/v1.13 的 SAF 目录树 `content://…`）→ v1.14 已移除 SAF，
     *   回落内部存储并**就地改写**，避免首选长期指向一个永远不可用的路径而卡在降级态。
     */
    var preferredStoragePath: String
        get() {
            val raw = prefs()?.getString("preferred_storage", null) ?: return internalVolumePath
            val resolved = when {
                raw == "INTERNAL" || raw == "USB" -> internalVolumePath
                !raw.startsWith("/") -> internalVolumePath
                else -> raw
            }
            if (resolved != raw) prefs()?.edit()?.putString("preferred_storage", resolved)?.apply()
            return resolved
        }
        set(v) { prefs()?.edit()?.putString("preferred_storage", v)?.apply() }

    /**
     * 首选存储卷名（盘名/卷标，如「内部存储」「SanDisk」「0000-0000」）。切换首选存储时
     * 与路径一并写入，供降级提示（「{卷名}已断开」）在首选卷不在位时仍能显示盘名。
     */
    var preferredStorageLabel: String
        get() = prefs()?.getString("preferred_storage_label", "内部存储") ?: "内部存储"
        set(v) { prefs()?.edit()?.putString("preferred_storage_label", v)?.apply() }
}