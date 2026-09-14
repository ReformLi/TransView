# TransView 传视TV — 架构与实现说明

> 版本：v1.3.0　日期：2026-09-12
> 对应需求：README.md（局域网媒体中心与传输工具）
> v1.3 变更：TV 端界面重做 —— 媒体库统一多列网格卡片、上传页左右分栏、播放页图标化控制 + 时间节点反馈。

## 1. 技术栈

| 模块 | 技术 | 版本 |
|:--|:--|:--|
| TV 端 UI | Kotlin + Jetpack Compose（TV 焦点定制） | Kotlin 2.0.0 / BOM 2024.09 |
| HTTP 服务器 | NanoHTTPD（内嵌，前台服务承载） | 2.3.1 |
| 数据库 | Room（媒体索引 / 播放历史 / 上传记录，三表） | 2.6.1 |
| 播放器 | Media3 ExoPlayer + PlayerView | 1.4.0 |
| 图片/缩略图 | Coil（含视频首帧解码） | 2.7.0 |
| 二维码 | ZXing core | 3.5.3 |
| 构建 | AGP 8.5.0 + Gradle 8.9 + KSP | — |

minSdk 21（Android 5.0+ TV/盒子），targetSdk 36。

## 2. 分层架构（高内聚低耦合）

```
com.hpu.transview
├── TransViewApp.kt            应用入口（Coil 配置；启动即触发数据库-文件对账）
├── MainActivity.kt            主界面入口 + 权限门
├── model/                     纯数据模型（枚举/数据类，无依赖）
│   └── Models.kt              MainTab / Category / SortOrder / FileEntry / ServerMode
├── util/                      无状态工具
│   ├── FileUtils.kt           FileLocations（专属沙盒 /sdcard/TransView，越界断言）、
│   │                          文件树遍历、空文件夹递归清理、物理删除、时长提取、
│   │                          目录列举/排序、自然比较、格式化、MIME、外部打开
│   ├── NetUtils.kt            本机 IP 探测、存储权限统一入口（StoragePermission，含写探针）
│   ├── IntentUtils.kt         安全启动 Activity（隐式 Intent 显式化，规避 ROM hook NPE）
│   ├── QrCode.kt              ZXing 二维码生成
│   └── Constants.kt(并入NetUtils) 端口 8080
├── server/                    网络接收层（不依赖 UI）
│   ├── TransHttpServer.kt     NanoHTTPD：上传页/上传接口；记录入 Room + 计数流回写进度
│   │                          + 落盘后立即建媒体索引
│   ├── UploadStorage.kt       落盘规则：分类根目录/同名重命名/文件夹合并/路径消毒
│   ├── UploadBus.kt           上传事件总线（媒体库刷新/空闲计时信号）
│   ├── ServerBus.kt           服务器状态总线（running/hibernated/mode）
│   └── ServerController.kt    智能保活策略引擎（模式+屏幕/播放/页面信号 → 启停状态机）
├── service/
│   └── ServerService.kt       前台服务（dataSync 类型）+ 常驻通知
├── data/                      数据层（Room 唯一通道 = 仓储）
│   ├── db/Entities.kt         media_items（filePath 唯一索引）/ playback_history（外键 CASCADE）
│   │                          / upload_records（纯历史日志）
│   ├── db/Daos.kt             三 DAO，全部 suspend 协程函数 + Flow 观察
│   ├── db/AppDatabase.kt      Room v2（fallbackToDestructiveMigration）
│   ├── MediaRepository.kt     媒体索引仓储（upsert / observeByCategory / 级联删除）
│   ├── PlaybackRepository.kt  播放历史仓储（path 键桥接外键，含自动补建索引）
│   ├── UploadRecordRepository.kt 上传记录仓储（insert/updateState/deleteById/clearAll）
│   └── sync/SyncManager.kt    数据库-物理文件对账引擎（三步，IO 线程，StateFlow 通知）
└── ui/                         Compose 界面层
    ├── theme/                  恒定深色 TV 主题
    ├── common/                 tvFocus 焦点修饰符、TvButton、OptionRow、
    │                           FileTypeIcon（Canvas 手绘）、VideoMeta（时长缓存）
    ├── MainScreen.kt           四标签导航 + 返回键回导航栏
    ├── permission/             存储权限引导页
    ├── upload/UploadScreen.kt  左右分栏：左侧固定服务器面板（二维码+地址+状态）+ 右侧可滚动上传记录列表
    ├── library/LibraryScreen.kt 多列网格卡片（5 列）、文件夹层级、工具条（面包屑+排序+刷新）、菜单/删除键选项
    ├── settings/ServerModeDialog.kt 保活模式选择对话框（极速/智能/省电）
    ├── player/PlayerActivity.kt 播放器（图标化控制栏、时间节点快进反馈、续播/连播/倍速/音轨/字幕）
    ├── player/PlayerWidgets.kt 播放器组件（Canvas 手绘 8 图标、进度条、中央反馈徽标）
    └── image/ImageViewerActivity.kt 图片查看器（缩放/平移/切换）
```

**关键解耦点**：
- `UploadBus` / `ServerBus`：进程内 StateFlow 单例，HTTP 服务器（工作线程）与 Compose UI 之间只通过事件流通信，互不持有引用。
- `UploadStorage`：纯落盘逻辑，可独立测试（同名 `(1)(2)` 重命名、`webkitRelativePath` 重建层级、`../` 路径穿越过滤）。
- 三个 Repository：UI / 服务器层 / SyncManager 与 Room DAO 之间的唯一通道，全部 suspend + Dispatchers.IO。
- `SyncManager`：对账引擎单例，经 `syncState` StateFlow 通知 UI，不持有任何界面引用。

## 3. 关键实现

### 3.1 上传链路（大文件安全，数据库驱动）
1. 手机 `POST /upload`（每文件一个请求，XHR `upload.onprogress` 显示百分比）。
2. NanoHTTPD 流式解析 multipart，临时文件写入**外部存储** `Android/data/…/files/upload_tmp`（避免占用内部空间，且与目标目录同卷）。解析前给请求 Content-Type 强制补 `charset=UTF-8`——NanoHTTPD 对不带 charset 的 multipart 头按 US-ASCII 解码，会损坏中文文件名（浏览器 FormData 从不带 charset）。
3. **上传记录先入 Room**（upload_records，状态=上传中）；`CountingOutputStream` 累计写入字节，后台协程 600ms 节流换算百分比（已写字节/Content-Length）回写 DB——TV 端上传页经 Room Flow 实时看到进度条，切换标签页不中断（上传在 HTTP 服务器工作线程进行）。
4. `UploadStorage.save()`：消毒文件名/相对路径 → 逐级建目录（同名文件夹自动合并）→ 同名冲突加 `(n)` 后缀 → `Files.move` 同卷秒移，返回目标 File。
5. 落盘成功后更新记录状态（成功/失败 + 100%），并在后台协程**立即写入 media_items 索引**（视频经 MediaMetadataRetriever 提时长），`UploadBus` 同时发事件驱动媒体库自动刷新与保活空闲计时；`MediaScannerConnection.scanFile` 通知系统媒体库。

### 3.2 存储策略与权限
- **专属沙盒目录（v1.2）**：App 全部存储收在 `/sdcard/TransView/`（Movies / Pictures / Downloads 三个子目录），上传落盘、媒体库扫描、清理删除均只在此沙盒内进行，不触碰系统公共目录（防误扫垃圾文件/越界误删）。`FileLocations.isInsideSandbox`（canonicalPath 前缀断言）是所有破坏性操作（删文件/清空目录/向上删空目录）的前置防线。
- API 30+：`MANAGE_EXTERNAL_STORAGE`，引导用户到系统授权页，`onResume` 复检。
  正式授权未通过时做**写探针**（在沙盒 `/sdcard/TransView/Movies` 建删临时文件）：部分模拟器/ROM（如 MuMu）不强制分区存储但无授权入口，实测可写即放行；真实设备无授权时探测必然失败，行为不变。
- API 21–29：运行时 `WRITE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage`。
- 授权页跳转与「其他」文件打开统一走 `IntentUtils.startSafely`：把隐式 Intent 解析成显式组件再启动，规避部分 ROM hook `Instrumentation` 后对 null component 的 NPE（症状：按钮点了没反应）。
- 用户主动往沙盒目录拷文件（U 盘等）属预期行为：启动/手动刷新对账会正常扫描入库展示。

### 3.3 媒体库（多列网格卡片，v1.3 重做）

**列表来源与刷新**
- 按需列目录（进入文件夹才 listFiles），千级文件无全量扫描压力；上传完成后经 `UploadBus` 事件自动刷新当前列表（新文件已由服务器层直接入库索引）。
- 文件列表走 `MediaRepository.observeByCategory`（Room Flow 实时刷新）；抽屉/文件夹列表来自文件系统（DB 不索引文件夹）。

**布局：`LazyVerticalGrid(GridCells.Fixed(5))`，不提供单列列表**（`ViewMode` 枚举已删除）
- 卡片统一 16:9 缩略图区 + 名称 + 副标题：文件夹=文件夹图标+「N 个文件」；视频=首帧+「时长·大小」；图片=首帧；其他=通用图标+大小。
- 名称与副标题**居中对齐**：卡片 `Column` 用 `horizontalAlignment = CenterHorizontally`，两个 `Text` 再显式 `Modifier.fillMaxWidth() + textAlign = TextAlign.Center`（只设 `textAlign` 而不撑满宽度时，单行文本仍按自身宽度左贴，看不出居中效果）；「返回上级」卡片同款处理。
- **长文件名跑马灯**：文件名的 `Text` 上挂 `Modifier.basicMarquee(...)`，但**仅在该卡片 `focused` 时挂载**（`Modifier.then(if (focused) titleMarquee else Modifier)`）。理由：未聚焦时保留 `TextOverflow.Ellipsis` 的省略号截断（marquee 自己裁剪会变成生硬切断、没有「…」），也让网格里只有一张卡在动。
  `basicMarquee` 会先给子项一个**无界宽度约束**测出文本真实宽度、再与自身视口比较，因此短名自动不滚动；它也正好让上一行的 `fillMaxWidth()` 在无界约束下退化为「按内容宽度包裹」，从而正确测出溢出。
  延迟调小（`initialDelayMillis = 400` / `repeatDelayMillis = 900`，默认各 1200ms）让焦点一到就开始滚。
- 有播放记录的视频卡片在缩略图底部叠加细进度条（`PlaybackRepository.observeAllProgress()` 提供 position/duration 映射）。
- 顶部工具条：路径面包屑（分类名 > 子文件夹…）+「排序：xxx」按钮 +「刷新」（触发 SyncManager 全量对账，同步中禁用并显示「对账中…」）。

**排序**：文件夹在前、文件在后，组内各自排序；名称 A-Z/Z-A、时间新→旧/旧→新。

**分类过滤**：视频/图片页只显示对应扩展名；其他页显示**非**视频非图片文件（含音频、文档等，经 FileProvider 调系统应用打开）。

**焦点与按键（v1.3 重点）**
- 卡片聚焦：`scale(1.1f)` + 2dp 主色边框 + `zIndex(1f)` 抬升。
- 按键处理 `.onPreviewKeyEvent` **必须放在 `focusRequester`/`clickable` 之前**（放后面会吞掉确定键，导致卡片打不开）。
- 确定键（`combinedClickable`）→ 直达动作（文件夹进入 / 视频播放 / 图片查看 / 其他系统打开）；**菜单键或长按确定键** → 弹「进入/播放、删除、取消」三选项；删除键 → 直接弹二次确认。
  - **长按确定的实现（实测坑 ×2）**：① `combinedClickable` 的 `onLongClick` 对遥控器确定键长按**不生效**（foundation 未处理 FLAG_LONG_PRESS 按键事件，模拟器实测无反应；触摸长按仍有效）。② 不能在**按住期间**（收到 FLAG_LONG_PRESS KeyDown 时）就弹菜单——Dialog 弹出会抢走焦点，手一松的 KeyUp 落到菜单主按钮上直接误触「进入/播放」，表现为菜单闪一下文件就被打开（真机实测踩过；模拟器 `input keyevent --longpress` 注入按下/松开间隔极短，KeyUp 仍被卡片消费，测不出此问题）。**最终方案**：按住期间一律放行，在 `MediaCard.onPreviewKeyEvent` 的 **KeyUp** 上按按压时长（`eventTime - downTime`，事件序列全程不变，真机遥控可靠）判定，≥ `ViewConfiguration.getLongPressTimeout()` → 弹菜单并消费该 KeyUp（阻止 `combinedClickable` 触发「打开」）；短按放行 → 正常点击。菜单在**松开瞬间**弹出，无时序竞争。
- **进入子目录焦点直接落第一个条目（v1.3，`FOCUS_FIRST`）**：曾设计为落「返回上级」卡片（`FOCUS_UP`），但 UpCard 抢焦点与网格首项渲染存在时序竞争，用户实测看到「第一个文件先亮一下再跳回上级」的可见两段跳，多轮修复（帧门控重试、`hadFocus` 先捕获）仍无法根治；最终按用户意见改为**进入后直接聚焦第一个条目**（`pendingFocusPath = FOCUS_FIRST`，只命中 `index == 0` 的 MediaCard），仅一次落点、无竞争。空目录（网格只剩 UpCard）时由 `LaunchedEffect` 兜底改投 `FOCUS_UP`。「返回上级」卡片仍可经 ← 键到达，行为不变。
- **目录列表异步加载的陈旧过滤（实测踩过）**：`dirEntries` 经 `LaunchedEffect + Dispatchers.IO` 异步加载，切目录后的**第一帧**里它还是旧目录的子文件夹列表；组合时按 `it.file.parentFile == currentDir` **同步过滤**掉陈旧项，否则网格会闪一帧旧目录内容，且 `FOCUS_FIRST` 会错误命中即将被移出组合的旧卡片（焦点随之失控回退）。配套用 `dirEntriesStamp` 记录列表归属目录：`LaunchedEffect` 的 key 是 `entries`（List 的 equals 是**结构比较**，内容不变不重跑），目录列表加载完成必须靠 stamp 变化触发重跑，否则 `FOCUS_FIRST` 的空目录判断会卡死。
- 从文件夹返回上级：`pendingFocusPath` 记录即将离开的文件夹路径 → `gridState.scrollToItem` 滚动定位 → 卡片自身 `FocusRequester` 请求焦点，实现「返回后焦点还原到刚才进入的卡片」。
- **从播放器/查看器返回定位到最后浏览项（v1.3）**：打开图片/视频改走 `rememberLauncherForActivityResult(StartActivityForResult)`；两个 Activity 在**切图/切集的瞬间**（`switchImage`/`selectImage`/`skipTo`）`setResult` 当前文件路径。**坑**：不能拖到 `onPause` 再 `setResult`——系统在 `finish()` 执行时就按当时的 result 封装返回值，`onPause` 里设置会拿到 null（实测踩过）。返回后媒体库把 `pendingFocusPath` 指向该路径，复用既有滚动+聚焦机制。覆盖图片查看器内切图、视频连播自动下一集两种场景。
- 网格内按返回键先「返回上一级」（`BackHandler(enabled = !atRoot)`）；到分类根目录时不拦截，交由 MainScreen 回到顶部导航栏。
- **确定键动过焦点就必须在 KeyUp 执行**（实测踩过）：`UpCard` 曾在 KeyDown 里执行 `onUp()`，而 `goUp()` 经「焦点安全港」把焦点**同步**移到工具条「排序」——同一按压的 KeyUp 是独立事件、派发给当时已聚焦的「排序」，Compose `clickable` 在 KeyUp 激活点击 → 莫名弹出排序弹框（视频/图片/其他三页同组件同现象）。改为 KeyUp 执行后，按压已结束、无后续事件，焦点移动安全。
- 切换文件夹/上传完成时 `dirRefreshKey++` 驱动文件夹列表重列。

**「上传」页职责（v1.1 重构，v1.3 改左右分栏）**
- 顶部 `Row` 左右分栏（按占比自适应）：**左侧固定区**（`weight 0.9f`，不滚动）= 二维码 + 地址 + 复制按钮 + 服务器状态/唤醒入口；**右侧记录区**（`weight 2f`，`LazyColumn` 可滚动）= **纯上传记录列表**（数据库驱动）。
- 每条记录含文件名/大小/进度条百分比/状态（等待中/上传中/成功/失败）/时间/分类六要素；焦点在记录上按**菜单键或删除键**（或右侧「删除」按钮）弹窗确认删除——**仅删 upload_records 日志，本地文件保留**；列表头部提供「清空所有记录」。

### 3.4 播放器（v1.3 图标化控制栏）

**播放列表与进度**
- 同目录视频按**自然排序**（EP2 < EP10）构成播放列表；播完 `STATE_ENDED` 自动下一集。
- **末集（或被连续快进跨过片尾）**：停在末尾、置 `ended=true`、弹出控制栏并显示「播放结束」，等待用户「重播」或按返回离开 —— **不调用 `finish()`**，避免被误当成闪退。
- 进度每 2 秒及 onStop/onDestroy 入库；距片尾 <5s 视为看完自动清历史；再次打开 >10s 且 <95% 时弹续播提示。
- 外挂字幕：同目录同主名 `.srt/.ass/.ssa/.vtt` 自动挂载为 `SubtitleConfiguration`。
- 音轨/字幕选择基于 `player.currentTracks` + `TrackSelectionOverride`；倍速 0.5–2.0。

**控制栏（`PlayerOverlay`，无文字主按钮）**
- 全为 Canvas 手绘图标（`PlayerWidgets.kt`：`PlayerIconType { PREV, REWIND, PLAY, PAUSE, FORWARD, NEXT, AUDIO, SUBTITLE }`），仅倍速用文字按钮；进度条为自绘（缓冲段 + 已播段 + 圆点滑块）。
- 按钮组：上一集 / 快退 / 播放暂停 / 快进 / 下一集 + 倍速 / 音轨 / 字幕。

**播放暂停图标统一为「动作式」语义（v1.3 关键决策）**
- **图标表示按下去会发生什么**：`isPlaying && !ended` → 显示 `PAUSE`(‖)（按下会暂停）；否则显示 `PLAY`(▶)（按下会播放/重播）。
- 单一来源函数 `playPauseIcon()`，**控制栏按钮与中央常驻图标共用**，两处方向永远一致（此前多轮“图标反了”的根因是「状态式」语义 + 两处各自判断）。
- `togglePlayPause()` 按**按下前**的状态决定意图，不在 `player.play()` 后立刻读 `isPlaying` 反推（起播瞬间仍在 BUFFERING，`isPlaying==false`，会显示反）。

**中央视觉反馈（`PlayerCentralBadge`，无底色面板）**
- **瞬时徽标**：快进/快退显示 `FORWARD/REWIND` 图标 + 「`目标位置 / 总时长`」（如 `01:21 / 13:01`），**不再显示“快进 N 秒”**；起播显示「播放中」；结束显示「播放结束」。停留 1.2 秒后淡出。
- **暂停常驻图标**：`!isPlaying && !ended && badgeText.isEmpty()` 时中央常驻播放图标（无背景），恢复播放淡出；有瞬时徽标时先让位，避免两图标重叠。

**快进/快退（`onSeekDown` / `onSeekUp`）**
- 首次 `KeyDown` 起协程：400ms 内 `KeyUp` = 短按，否则进入变速扫描（节拍 150ms，倍率每 1.2s 翻倍 2x→4x→8x→16x）。
- 短按连续快按累加步长 10→20→…→60 秒（`SEEK_CHAIN_WINDOW_MS = 1000` 窗口内判定），每次显示目标时间节点。
- 长按期间系统重复 `KeyDown` 被 `seekJob` 拦截；`seekBy()` 对 `duration` 为 `TIME_UNSET`（负数）时兜底，避免 `coerceIn` 抛异常。
- 快进只弹中央徽标、不弹控制栏，**保证连续快进不被打断**。

**遥控器映射**
- 中键：控制栏隐藏时播放/暂停；控制栏显示时交给聚焦按钮。
- 左右方向键：控制栏/对话框隐藏时才快退/快进（否则交给按钮做焦点导航）。
- 媒体键：⏪/⏩ 快退/快进；⏯/⏭/⏮ 播放暂停 / 下一集 / 上一集。
- 菜单键 / 上 / 下：显示或收起控制栏（6s 无操作自动隐藏）；返回键：先收控制栏，再退出。

### 3.5 图片查看器
- 全屏 Coil 展示；中键 1x↔2x 缩放，菜单键循环 1→1.5→2→3x；缩放时方向键平移（边界约束），未缩放时左右切换同目录图片（自然排序）。
- **同目录缩略图轮播（v1.3，固定取景框模式）**：非放大时 ↓ **一次**唤出底部横向缩略图条并直接进入选择模式（`Row + horizontalScroll(enabled=false)`，仅程序化滚动；当前图 3dp 白框标记）——**焦点固定在轮播条容器上，高亮取景框恒定居中不动**；左右键只增减 `cursorIndex`，`LaunchedEffect` 把目标缩略图滚动到屏幕正中（`animateScrollTo(index × 项宽)`，条首尾各留「半屏 − 半图」空白使任意一张都能居中；`coerceIn(0, maxValue)` + 下标钳制实现两端留白、到头划不动）；确定键切换主图且轮播与取景框保持；↑ 或返回键收起并把焦点交还根容器（`requestFocusNextFrame`）。↑ 在轮播未唤出时切换「全屏整洁模式」（隐藏/恢复顶部文件名栏）；返回键经 `BackHandler`（常驻拦截）**分层处理**：轮播可见先收轮播 → 图片放大态先复原 1x（`zoomTo(1f)`）→ 都不是才 `finish()` 退出。
  - 设计取舍：曾做「↓ 唤出 / 再 ↓ 聚焦」两段式，实测用户会停在未聚焦段按左右——该段左右仍是切图（白框移动、缩略图条不动），与取景框心智冲突；合并为单段后左右语义唯一（滑动）。
  - **初始居中两个坑（实测踩过）**：① 唤出首帧 `scrollState.maxValue` 尚未完成布局测量（=0），居中目标被钳制成 0 → 永远定位到第一张；用 `snapshotFlow { maxValue }.first { it > 0 }` 等滚动范围就绪后再滚。② `focusable()` 挂在滚动容器**内侧**时，`requestFocus()` 会触发 bring-into-view 把整条 Row 滚回起点，半路打断居中动画；必须把焦点节点放到滚动容器**外侧**。
- 键处理分层：根容器 `onPreviewKeyEvent` 按状态机（`scale`/`carouselVisible`/`carouselFocused`）分发——轮播未聚焦时左右仍切图，聚焦后左右放行给缩略图焦点导航；缩放态（`scale > 1f`）下方向键一律平移，轮播相关按键仅在非放大态生效。

### 3.6 TV 焦点规范
- 通用可点元素（按钮/对话框选项）带 `tvFocus()`：放大 1.03 + 主色描边 + 底色。
- **媒体库网格卡片**（v1.3）：聚焦放大 1.1 + 2dp 主色边框 + `zIndex(1f)` 抬升（避免放大被相邻卡片裁切）；`onPreviewKeyEvent` 必须写在 `focusRequester`/`clickable` **之前**。
- **上下键层级（v1.3，必须显式指定，不能交给就近搜索）**：内容区 → 本页工具条 → 顶部导航栏**当前分类标签**。
  放任默认搜索会出错：内容区在屏幕右侧，其正上方恰是导航栏右上角的「设置」，方向搜索按像素就近就命中「设置」（即「按上键焦点跑到设置去」的根因）；媒体库左侧两列则会穿过不可聚焦的面包屑文字直接命中第一个标签「上传」。
  - 媒体库：网格**第一行**按 ↑ → 本页工具条（排序 / 刷新）→ 再 ↑ → 本页分类标签（视频页 →「视频」）；中间行按 ↑ 仍是网格内上行（`onNavigateUp` 返回 false 交回 Compose）。
  - 媒体库工具条 ↓ 回网格**不靠按键拦截**，而是给网格留 `contentPadding(top = 24.dp)`：按钮热区下沿与首行卡片上沿垂直重叠时，方向搜索会判定「下方无目标」而把焦点卡死在工具条上，用布局让开比用按键硬接可靠。
  - 上传页：记录列表**第一行**按 ↑ → 列表头「清空所有记录」（本页工具条）→ 再 ↑ → 「上传」标签；「上传」↓ 回记录首行。
- **左右边界（v1.3，同样必须显式吃掉按键）**：内容区每行 / 每栏的**最左端与最右端**要拦下左 / 右键（返回 true 消费事件），让焦点留在原地。
  否则 Compose 的二维搜索在该方向找不到候选时会「环绕」到别处的可聚焦元素，而顶部导航栏的标签就在正上方且是「聚焦即选中」——被环绕命中就会直接切页。
  典型现象：视频页只有一个卡片时，焦点在卡片上按**右键**会直接跳回「上传」页（实测复现）。
  - 落地位置：`MediaCard`/`UpCard` 的 `stayOnLeftEdge`/`stayOnRightEdge`（行首左、行尾右）、媒体库工具条「排序」的左端与「刷新」的右端、上传页记录行的右键与「清空所有记录」的右键。
  - **反例（别用）**：不要试图在内容区容器上挂 `focusProperties { exit = { FocusRequester.Cancel } }` 当「焦点围墙」——实测它会让内容区内的**程序化 `requestFocus()`**（首行按上键跳到本页工具条）一并失效，属于副作用不可控的写法。
- **内容切变前的「焦点安全港」（v1.3）**：凡是会**移走承载焦点的卡片**的操作（切换目录 / 返回上级 / 删除文件），都必须先把焦点停到**常驻**元素上，再由目标卡片在下一帧把焦点抢回网格。
  - 最终落地为**工具条停靠 + 抑制高亮**：`parkFocusSafe()` 仅在网格确实持有焦点（`gridHasFocus`）时把焦点停到工具条「排序」，同时置 `focusParking = true`；`LibraryTopBar` 依据 `suppressSortFocusVisual` 抑制「排序」按钮的焦点高亮与放大（`TvButton(showFocusVisual = false)`），过渡帧不再「排序闪一下再跳卡片」。目标卡片抢回焦点后经 `onAutoFocused()` 清掉 `focusParking`；另有 `LaunchedEffect(focusParking)` 800ms 超时兜底复位，防按钮永远不亮。
  - 曾改用「隐形锚点」（1dp 透明 `Box` + `focusProperties { enter = Cancel }`）承接过渡，但实测 touch 点按路径下对锚点的 `requestFocus` 会静默失效（连试 8 次无焦点），已回退到工具条停靠方案；空目录兜底仍停真工具条（`parkFocusOnToolbarFallback`）。
  - **`hadFocus` 先捕获规律（实测踩过）**：`parkFocusSafe()` 内的 `toolbarFocus.requestFocus()` 是**同步**移动焦点，网格的 `onFocusChanged` 会在其返回前立即把 `gridHasFocus` 改写为 `false`——之后同函数里再读 `gridHasFocus` 永远拿到 `false`，`pendingFocusPath` 不会被设置，UpCard 不抢焦点、焦点卡死在「排序」。必须在 park **之前** `val hadFocus = gridHasFocus` 捕获到局部变量再判断。`openEntry`（目录分支）与 `goUp()` 均按此落地。
  - touch 点按路径：网格本无焦点（`gridHasFocus == false`），跳过停靠与焦点还原，行为与预期一致。
  - 根因背景：焦点所在卡片被移出组合后，Compose 无法把焦点交还给它，会回退到整棵树里**第一个可聚焦元素**——正是顶部导航栏的「上传」标签；标签是「聚焦即选中」，页面会被立刻切走。
  - 典型现象（修复前）：在图片页按确定键打开文件夹，直接跳回「上传」页（实测复现：`OK` 前焦点在 `windows` 文件夹卡片 `[42,236][280,447]`，`OK` 后焦点变成「上传」标签 `[184,24][308,88]`）。
  - 落地位置：`LibraryScreen` 的 `openEntry`（目录分支）、`goUp()`、`performDelete()`。
  - 附带收益：即使「抢回焦点」失败，焦点也仍留在本页工具条，不会跨页乱跑。
- 顶部标签聚焦即选中（左右键切换）；内容区按返回键 → 焦点回导航栏；再按返回才退出。媒体库内层另有 `BackHandler`：非根目录时先返回上一级。
- 媒体库「返回上级」焦点还原：`pendingFocusPath` + `gridState.scrollToItem` + 卡片 `FocusRequester` 三段式协作；
  请求焦点统一走 `requestFocusNextFrame()`（`withFrameNanos {}` 让出一帧再 `requestFocus()`）——LazyGrid 项刚组合、尚未完成布局时 `requestFocus()` 会抛 `IllegalStateException` 被 `runCatching` 静默吞掉，表现为「焦点还原没反应」。
- 播放器/查看器根节点 `focusable()` 常驻焦点，浮层隐藏后 `FocusRequester` 归位（控制栏显示 → 落到播放/暂停按钮；隐藏 → 回根节点），遥控器永不失焦。

### 3.7 服务器智能保活（ServerController 状态机）

信号源：屏幕开关（SCREEN_OFF/ON 广播，ServerService 转发）、播放状态（PlayerActivity 上报）、
上传页可见性（MainScreen 上报）、上传活动（UploadBus 事件重置空闲计时）、手动唤醒（上传页按钮）。

| 模式 | 运行条件 |
|:--|:--|
| 极速 TURBO | 恒运行，待机/播放/超时均不停止 |
| 智能 SMART（默认） | 未休眠 && 屏幕亮 && 非播放中；15 分钟无上传自动休眠 |
| 省电 POWER_SAVER | 在上传页 && 手动启动；离开上传页即停 |

- 模式经 SharedPreferences 持久化，设置入口在主界面右上角「设置」，修改立即生效。
- 启停在单一后台执行器串行执行，UI 线程零阻塞；`ServerBus`（StateFlow）同时驱动 UI 与前台服务通知文案（运行中/已暂停/已休眠/已停止）。
- 空闲计时带保护：仍有上传在途（如单个大文件传输超 15 分钟）时顺延，绝不中断传输。
- 上传页在服务器未运行时顶部固定区显示状态面板：省电模式「启动服务器」按钮、智能休眠「唤醒服务器」按钮（<1 秒恢复）、播放/熄屏暂停提示（自动恢复，无需操作）。
- 与 README 3.6.1「待机仍可接收」的差异：默认智能模式改为待机暂停接收；需要旧行为请选择极速模式。

### 3.8 数据库与物理文件一致性（SyncManager 对账引擎）

**三表结构**（Room v2，`data/db/Entities.kt`）：

| 表 | 关键列 | 约束 |
|:--|:--|:--|
| media_items | filePath / mediaType(0视频/1图片/2其他) / parentFolder / fileSize / lastModified / duration / addedTime | filePath **唯一索引** |
| playback_history | mediaItemId / position / updatedTime | **外键 → media_items，ON DELETE CASCADE** |
| upload_records | fileName / fileSize / progress / state(0等待/1上传中/2成功/3失败) / category / time | 纯历史日志，删除不触碰物理文件 |

DAO 全部 suspend 协程函数（无 RxJava）；仓储是 UI/服务器层访问 Room 的唯一通道；`PlaybackRepository` 对外保持 path 键调用面，内部桥接外键并在保存进度时自动补建缺失索引。

**三步对账**（`SyncManager.sync()`，全程 Dispatchers.IO，Mutex 串行，严禁阻塞主线程）：
1. **清理空文件夹**：沙盒内三个分类根目录递归扫描，物理删除空文件夹——子删父空继续向上递归（分类根受 `FileLocations.isRoot` 保护永不删除；入口处 `isInsideSandbox` 断言，越界直接拒绝）。
2. **同步外部删除**（防"有索引无文件"）：遍历 DB 全部 filePath，物理不存在 → 删记录（播放历史经 CASCADE 级联删除）。
3. **同步新增/变更**（防"有文件无索引"）：递归收集**沙盒内**物理文件（用户 U 盘拷入的文件同样入库，属预期行为）→ 无记录的入库（视频时长 MediaMetadataRetriever 提取 + 进程内 ConcurrentHashMap 缓存）；有记录但 fileSize/lastModified 变化的更新。

**触发时机**：Application 启动（appScope 协程，崩溃安全）+ 媒体库菜单键「刷新媒体库（对账）」（协程触发，Toast 汇报四项统计）。完成后 `syncState: StateFlow<SyncState>`（Idle/Running/Done）通知 UI。

**App 内主动删除约定**（FileUtils + LibraryScreen）：先物理删除（`deletePhysicalFile`，连带清理空父目录）→ 成功后才删数据库记录；物理删除失败（返回 false）只弹 Toast **不删记录**，保证数据库永不出现"有索引无文件"。删除入口：焦点在媒体库文件行（列表/网格）按菜单键或删除键 → 弹窗确认（文件夹不可删）。

**异常兜底**（已接入 PlayerActivity）：打开入口或播放错误时发现物理文件不存在 → `SyncManager.reportMissingFile(path)` 删索引，Toast「文件已丢失，已从列表移除」，媒体库列表经 Room Flow 自动刷新。

**媒体库 DB 驱动**（LibraryScreen）：文件列表来自 `MediaRepository.observeByCategory` Room Flow（上传入库/对账/删除均实时刷新，无需手动 re-list）；文件夹层级来自文件系统（DB 不索引文件夹）；视频时长优先取 DB 索引，缺失回退 MediaMetadataRetriever。`media_items.parentFolder` 存**父目录绝对路径**。

**文件操作规范**：全程 `java.io.File`，禁用 SAF/DocumentFile。

## 4. 构建与运行

```bash
# 调试包
./gradlew assembleDebug
# 产物 app/build/outputs/apk/debug/app-debug.apk
```
仓库不含 `local.properties`（`sdk.dir` 按机器自配），Gradle 发行走腾讯镜像、依赖走阿里云镜像（settings.gradle.kts）。

## 5. 已知 TODO（下个迭代）

**v1.1 数据库一致性重构已全部完成（模块 1-5）。**

**功能 TODO：**
- [ ] zip 压缩包上传后服务端自动解压并按分类过滤（需求 3.3.5 备选方案）
- [ ] 开机自启（`RECEIVE_BOOT_COMPLETED`，需求 3.6.2 可选项）
- [ ] 断点续传（需求 4.4 P2）
- [ ] 上传页面 Token 验证（需求 4.5 可选项）
- [ ] 上传中断网时手机端支持「取消/重试」按钮（当前仅标记失败）
- [ ] 服务器端口可配置（当前固定 8080）
