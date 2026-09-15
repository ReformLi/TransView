package com.hpu.transview.model

/** 顶部导航标签 */
enum class MainTab(val title: String) {
    UPLOAD("上传"),
    VIDEO("视频"),
    IMAGE("图片"),
    OTHER("其他")
}

/** 文件分类：决定存储根目录与上传过滤规则 */
enum class Category {
    VIDEO, IMAGE, OTHER
}

/** 媒体库排序方式 */
enum class SortOrder(val label: String) {
    NAME_ASC("名称 A-Z"),
    NAME_DESC("名称 Z-A"),
    TIME_DESC("时间 新→旧"),
    TIME_ASC("时间 旧→新")
}

/**
 * 媒体库中的一条文件/文件夹（duration 来自数据库索引，视频有效）。
 *
 * [path] 是**存储身份**（也是数据库 `media_items.filePath` 的取值），v1.14 起恒为**绝对路径**，
 * 如 `/storage/0000-0000/TransView/Movies/a.mp4`；构造 Uri 用 `mediaUri(path)`（恒得 `file://`）。
 * 类型判定用扩展名（`FileEntry.isVideoFile()` / `isImageFile()`）。
 */
data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val duration: Long = 0L,
    /** 父节点身份（= 数据库 parentFolder）；用于「当前目录过滤」与进入/返回上级 */
    val parentPath: String = ""
)

/**
 * 播放/查看用的媒体引用（播放器连播列表、图片查看器切图列表的元素）。
 * [path] 同样是存储身份（v1.14 起恒为绝对路径）。
 */
data class MediaRef(val path: String, val name: String) {
    val nameWithoutExtension: String
        get() = name.substringAfterLast('.', "").let { ext ->
            if (ext.isEmpty()) name else name.dropLast(ext.length + 1)
        }

    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

/** 上传状态 */
enum class UploadState(val label: String) {
    RUNNING("上传中"),
    DONE("成功"),
    FAILED("失败")
}

/** TV 端「最近上传」列表中的一条记录（由 HTTP 服务器上报） */
data class UploadRecord(
    val id: Long,
    val name: String,
    val size: Long,
    val state: UploadState,
    val time: Long
)

/** 播放默认画面比例 */
enum class AspectRatio(val label: String) {
    ORIGINAL("原始"),
    STRETCH("拉伸"),
    CROP("裁剪")
}

/** 服务器保活策略模式 */
enum class ServerMode(val label: String, val desc: String) {
    /** 极速：恒运行，无视屏幕休眠/播放/超时 */
    TURBO("极速模式", "始终保活，待机、播放时也能接收上传（最便利，略增后台占用）"),

    /** 智能（默认）：屏幕休眠或播放视频时暂停；15 分钟无上传自动休眠 */
    SMART("智能模式", "屏幕休眠、播放视频时自动暂停，15 分钟无上传自动休眠"),

    /** 省电：仅在上传页手动点击才启动，离开上传页即停止 */
    POWER_SAVER("省电模式", "仅在上传页手动点击启动，离开上传页自动停止")
}

// 存储位置不再用固定枚举：设置页的可选存储按「当前在位卷」动态检测（FileLocations.getWritableDevices），
// 无外接盘时只有「内部存储」，有外接盘（U盘/SD卡）时按盘名列出全部**可写**卷。
// 首选存储按卷根路径持久化（SettingsStore.preferredStoragePath），不再依赖枚举。
