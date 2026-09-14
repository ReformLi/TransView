# TransView 传视TV — 架构与实现说明

> 版本：v1.7.1　日期：2026-09-14
> 对应需求：README.md（局域网媒体中心与传输工具）
> v1.7.1 变更：**上传页左面板视觉重做**——访问码由裸文字行改为**色块横条**（主色 14% 底 + 同行居中的标签/码值），
> 标题降为 `labelLarge`、底部提示压成一行、复制按钮收紧，省下的竖向空间全部给二维码
> （720p 实测 220×220 → 283×283 px，占面板宽 80%）（§3.15）。
> v1.7 变更：**上传访问码（最小认证）**——服务器每次启动轮换 6 位访问码（`A-Z`+`0-9`），二维码带码扫码零输入、屏幕大号显示访问码；新增 `GET /verify` 与 `POST /upload` 的 `X-Upload-Token` 校验（未授权 403 且不接收任何文件），网页端 403 自动回门槛并续跑队列（§3.15）；顺带把上传页二维码改为自适应尺寸。v1.6 变更：压缩包服务端解压（§3.14）。v1.4 变更：设置页改造为独立子页面（SettingsScreen + SettingsStore，五分组左组右详情）+ 长按确定键 = 操作菜单（自计时方案，修复键盘 auto-repeat）。v1.3 变更：TV 端界面重做 —— 媒体库统一多列网格卡片、上传页左右分栏、播放页图标化控制 + 时间节点反馈。

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
│   └── Models.kt              MainTab / Category / SortOrder / FileEntry /
│                              ServerMode / AspectRatio（v1.4 设置页画面比例）
├── util/                      无状态工具
│   ├── FileUtils.kt           FileLocations（专属沙盒 /sdcard/TransView，越界断言）、
│   │                          文件树遍历、空文件夹递归清理、物理删除、时长提取、
│   │                          目录列举/排序、自然比较、格式化、MIME、外部打开
│   ├── NetUtils.kt            本机 IP 探测、存储权限统一入口（StoragePermission，含写探针）
│   ├── IntentUtils.kt         安全启动 Activity（隐式 Intent 显式化，规避 ROM hook NPE）
│   ├── QrCode.kt              ZXing 二维码生成
│   └── Constants.kt(并入NetUtils) 默认端口 DEFAULT_PORT=2333、预设端口列表 ALLOWED_PORTS、上传路径
├── server/                    网络接收层（不依赖 UI）
│   ├── TransHttpServer.kt     NanoHTTPD：上传页/访问码校验/上传接口；记录入 Room + 计数流回写进度
│   │                          + 落盘后立即建媒体索引；上传页每次请求注入设备名（__DEVICE_NAME__）；
│   │                          Token 构造时注入（每次启动新实例 = 新码），/upload 前置校验 X-Upload-Token
│   ├── UploadStorage.kt       落盘规则：分类根目录/同名重命名/文件夹合并/路径消毒
│   ├── UploadBus.kt           上传事件总线（媒体库刷新/空闲计时信号）
│   ├── ServerBus.kt           服务器状态总线（running/hibernated/mode/port/token）
│   └── ServerController.kt    智能保活策略引擎（模式+屏幕/播放/页面信号 → 启停状态机；setPort 停旧起新；
│                              访问码内存权威值：tryStart 轮换 / stopServer 销毁）
├── service/
│   ├── ServerService.kt       前台服务（API 34+ specialUse / API 29~33 dataSync）+ 常驻通知
│   └── BootReceiver.kt        开机自启（BOOT_COMPLETED → 读设置决定是否拉起 ServerService）
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
    ├── MainScreen.kt           四标签导航 + 右侧「设置」入口（聚焦即打开设置页）+ 返回键回导航栏
    ├── permission/             存储权限引导页
    ├── upload/UploadScreen.kt  左右分栏：左侧固定服务器面板（二维码带访问码 + 访问码色块 + 地址 + 状态）
    │                           + 右侧可滚动上传记录列表；二维码尺寸随可用空间自适应
    ├── library/LibraryScreen.kt 多列网格卡片（列数由设置决定，4/5/6）、文件夹层级、工具条（面包屑+排序+刷新）、菜单/删除/长按确定选项
    ├── settings/SettingsScreen.kt 设置子页面（v1.4：五分组左组右详情；**九项全部已接通**；
    │                           三类弹框关闭后焦点回原行）
    ├── settings/SettingsStore.kt 设置偏好持久化（SharedPreferences object；端口/自启/设备名/续播提示/
    │                           连播/倍速/画面比例/列数/默认排序，均含默认值）
    ├── player/PlayerActivity.kt 播放器（图标化控制栏、时间节点快进反馈、续播/连播/倍速/画面比例/音轨/字幕）
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
- 顶部工具条：路径面包屑（分类名 > 子文件夹…）+「排序：xxx」按钮 +「刷新」（触发 SyncManager 全量对账；对账期间文案变「对账中…」并置灰——**用 `TvButton(enabled = false)` 的「可聚焦禁用态」**，不能真把它变不可聚焦，否则焦点回退会切页，见 §3.6）。

**排序**：文件夹在前、文件在后，组内各自排序；名称 A-Z/Z-A、时间新→旧/旧→新。

**分类过滤**：视频/图片页只显示对应扩展名；其他页显示**非**视频非图片文件（含音频、文档等，经 FileProvider 调系统应用打开）。

**焦点与按键（v1.3 重点）**
- 卡片聚焦：`scale(1.1f)` + 2dp 主色边框 + `zIndex(1f)` 抬升。
- 按键处理 `.onPreviewKeyEvent` **必须放在 `focusRequester`/`clickable` 之前**（放后面会吞掉确定键，导致卡片打不开）。
- 确定键（`combinedClickable`）→ 直达动作（文件夹进入 / 视频播放 / 图片查看 / 其他系统打开）；**菜单键或长按确定键** → 弹「进入/播放、删除、取消」三选项；删除键 → 直接弹二次确认。
  - **长按确定的实现（v1.4 定稿，自计时 + 确认窗口，实测坑 ×3）**：① `combinedClickable` 的 `onLongClick` 对遥控器确定键长按**不生效**（foundation 未处理 FLAG_LONG_PRESS 按键事件，模拟器实测无反应；触摸长按仍有效）。② 不能在**按住期间**（收到 FLAG_LONG_PRESS KeyDown 时）就弹菜单——Dialog 弹出会抢走焦点，手一松的 KeyUp 落到菜单主按钮上直接误触「进入/播放」（真机实测踩过）；菜单必须在**松开瞬间**弹出。③ **不得信任事件时间戳**：真机遥控按住 OK 键只会送来一串重复 KeyDown + 最后一个 KeyUp（`downTime` 保持首次按下时间，`eventTime - downTime` 可靠）；而**键盘（经模拟器）的 auto-repeat 会被输入通路拆成一串独立「按下+抬起」对**，每对时间戳独立，`eventTime - downTime` 恒 ≈0ms——长按被误判为短按，且每次抬起都触发一次点击（=「长按变多次确定」，实测复现）。
    **最终方案**（`MediaCard` / `UpCard` 统一）：`onPreviewKeyEvent` **完全接管**确定键（`DirectionCenter / Enter / NumPadEnter / Spacebar`，置于 `combinedClickable` 之前，触摸路径仍走后者）——DOWN 全部吃掉（阻止 `combinedClickable` 逐对触发点击）并**自行用 `SystemClock.uptimeMillis()` 记录按下起点**（后续 auto-repeat 的 DOWN 只取消待触发的短按、不重置起点）；UP 时按压 ≥ `ViewConfiguration.getLongPressTimeout()` → 弹菜单；短按启动 `CONFIRM_GRACE_MS = 200ms` 确认窗口协程（窗口内无后续按下 = 用户真松手，排除 auto-repeat 中间抬起）才触发打开。焦点离开时（`onFocusChanged !isFocused`）置 `pressing = false` 并取消待触发协程，防悬挂状态。`UpCard` 同款处理防止按住连跳多级目录。
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
- 进度每 2 秒及 onStop/onDestroy 入库；距片尾 <5s 视为看完自动清历史；再次打开 >10s 且 <95% 时弹续播提示（设置页「自动续播提示」关闭则静默续播，见 §3.11）。
- 外挂字幕：同目录同主名 `.srt/.ass/.ssa/.vtt` 自动挂载为 `SubtitleConfiguration`。
- 音轨/字幕选择基于 `player.currentTracks` + `TrackSelectionOverride`；倍速 0.5–2.0；画面比例 FIT/FILL/ZOOM 三档（均见 §3.11）。

**控制栏（`PlayerOverlay`，无文字主按钮）**
- 全为 Canvas 手绘图标（`PlayerWidgets.kt`：`PlayerIconType { PREV, REWIND, PLAY, PAUSE, FORWARD, NEXT, AUDIO, SUBTITLE }`），仅倍速与画面比例用文字按钮（`PlayerTextButton`）；进度条为自绘（缓冲段 + 已播段 + 圆点滑块）。
- 按钮组：上一集 / 快退 / 播放暂停 / 快进 / 下一集 + 倍速 / 比例 / 音轨 / 字幕。

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
- 左右方向键：控制栏/对话框隐藏时才快退/快进（否则交给按钮做焦点导航）；**两种情况下都会刷新 6s 自动隐藏计时**——否则按键导航不续命，用户还在按钮间移动时控制栏就消失了，之后的左右键突然变成快进/快退（见 §3.6）。
- 媒体键：⏪/⏩ 快退/快进；⏯/⏭/⏮ 播放暂停 / 下一集 / 上一集。
- 菜单键 / 上 / 下：显示或收起控制栏（6s 无操作自动隐藏）；返回键：先收控制栏，再退出。
- 「上一集/下一集」无对应集数时置灰但**仍保留焦点**（`PlayerIconButton` 禁用态可聚焦，见 §3.6）。

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
- **`enabled = false` 同样会丢焦点（v1.4 修复）**：同一根因的另一种触发方式——焦点持有者不是「被移出组合」，而是「被移出**焦点候选**」。
  Material3 的 `Button` 在 `enabled = false` 时不再参与焦点搜索；若它**正持有焦点**，Compose 找不到落点，依旧回退到整棵树第一个可聚焦元素（顶部「上传」标签）→ 页面被切走。
  - 现象（修复前）：媒体库工具条聚焦「刷新」按确定键，对账期间按钮 `enabled = !syncing` 变 false，页面立刻被切到「上传」页。
  - 约定：项目里的 `TvButton` **内部恒为 `Button(enabled = true)`**，入参 `enabled` 只控制「是否响应点击 + 是否套禁用配色（`onSurface` 12% / 38%，因 Button 恒 enabled，Material3 不会自动给禁用色，需显式指定）」，所以禁用态仍可聚焦，也仍能挂 `focusRequester` / `onPreviewKeyEvent`。
  - 推论：任何**会随状态变化进入「禁用」且可能持有焦点**的元素（按钮 / 行 / 卡片），要么按此「可聚焦的禁用态」处理，要么走上面的「焦点安全港」先把焦点转走。
  - **播放器控制栏同样已按此处理**（v1.4）：`PlayerWidgets.PlayerIconButton` 的 `clickable` 恒为 `enabled = true`，禁用态自己吞掉确定键，并改用「灰底 + 灰色描边 + 更暗图标」渲染（不能用主色填充——暗图标压在亮蓝上糊成一片）。触发场景：「下一集」被聚焦时按确定键切到**最后一集**，`hasNext` 当场变 false，若按钮同时变不可聚焦，焦点会掉到播放器根节点、控制栏上一个高亮都不剩，后续左右键会直接变成快进/快退。
  - 播放器另有两条与焦点相关的约定：①控制栏显示时**左右键是焦点导航**（隐藏时才是快进/快退）；②左右键**也要刷新自动隐藏计时**——否则用户还在按钮间移动时控制栏就消失，之后的左右键会突然变成快进/快退（实测：连按 5 次右，中途控制栏隐藏，后几次被当成快进而弹出「02:21 / 13:01」徽标）。注意**上/下键是切换控制栏**，不能当「续命」用。
  - 附带收益：即使「抢回焦点」失败，焦点也仍留在本页工具条，不会跨页乱跑。
- **触摸 / 鼠标点按后的焦点锚定（v1.4 修复）**：
  - 现象：用触摸屏 / 鼠标点按内容区后再按一次方向键，焦点命中顶部「上传」标签 → 页面被切走（实测：视频页 tap 网格空白后按一下 ↓，直接跳回上传页）。
  - 根因是两层叠加：① 触摸让设备进入 **touch mode**，Compose 在进入时清空焦点；② **touch mode 下「默认可聚焦节点」（`Modifier.clickable` / `Modifier.focusable()`）不接受程序化 `requestFocus`**——实测点按后让网格卡片抢焦点，`repeat(10)` 连试 10 次**全部失败**。于是第一次方向键只能由 Compose 从整棵树重新搜索，命中整棵树第一个可聚焦元素（顶部标签）。
  - 修法（`MainScreen`，三处配合，缺一不可）：
    1. 内容区 `Box` 在 `Press` 阶段**同步**调用 `rootView.requestFocusFromTouch()`（`rootView = LocalView.current`）。这是 View 层「在 touch mode 下请求焦点」的入口，内部会先让 `ViewRootImpl` 退出 touch mode——**这是根本动作**，缺了它下面两步都会被静默拒绝。
    2. 内容区 `Box` 挂一个自身无任何视觉的焦点锚点（`focusRequester` + `focusable()`），点按结束后把焦点锚到它，随后的方向键就从内容区开始搜索。
    3. 再把焦点票据投给内容区（`contentFocusTicket++` / `settingsFocusTicket++`），复用「标签按 ↓」的已验证路径，焦点落到本页第一个可聚焦元素（记录首行 / 网格首项 / 设置首项）。
  - **时序**：锚定统一延后 150ms（`LaunchedEffect` + `delay`）——tap 手势期间焦点会被持续清空，立即 `requestFocus` 会被覆盖（实测）；150ms 远短于人的连续按键间隔。窗口失焦时跳过（点按可能已打开播放器 / 图片查看器）。
  - 遥控器按键不产生 `PointerEvent`，对既有遥控器导航零影响（已验证回归：返回键 → 标签、标签 ↓ → 首行、首行 ↑ → 工具条、工具条 ↑ → 标签，全部正常）。
- **上传页删除记录的焦点安全港（v1.4 修复）**：
  - 现象：删掉一条上传记录后焦点回退到顶部「上传」标签（与媒体库删卡片同一机制：承载焦点的行被移出组合，Compose 回退到第一个可聚焦元素）。
  - 修法（与媒体库同款：停靠 → 目标抢回）：打开删除确认框的**那一刻**捕获 `deleteHadFocus`——确认框有自己的 window、会取走焦点，等回到 `onConfirm` 再读永远是 `false`（注意这里与媒体库的 `performDelete` 不同：那边把 `hadFocus` 放在 `withContext(IO)` 挂起之后读，靠对话框关闭后的焦点归还拿到 `true`）。
    确认后先把焦点停靠到列表头「清空所有记录」（`showFocusVisual = false` 抑制过渡高亮），再把 `pendingFocusId` 设为**下一条**记录（已是最后一条则退到上一条），由该行 `autoFocus` 在下一帧抢回；列表即将清空时没有落点，交给自然回退（页面仍是「上传」页，不会切走）。另有 `LaunchedEffect(focusParking)` 800ms 超时兜底复位。
  - 实测：删除中间一条 → 焦点落到下一条；删除最后一条 → 焦点退到上一条。
- 顶部标签聚焦即选中（左右键切换）；内容区按返回键 → 焦点回导航栏；再按返回才退出。媒体库内层另有 `BackHandler`：非根目录时先返回上一级。
- **标签焦点回调里「退出设置页」必须无条件执行**：媒体标签早期写法是 `if (isFocused && tab != selected) { selected = tab; showSettings = false }`——把 `showSettings = false` 和「切换标签」绑进了同一个条件。当 `selected` 恰好就是目标标签时条件不成立（典型路径：「其他」页 → 聚焦「设置」→ 按 ← 回「其他」，`selected` 一直是 `OTHER`、根本没变），于是 `showSettings` 停在 `true`，**内容区继续显示设置页，只有标签选中态变了**（用户实测反馈）。修法：焦点落到任一媒体标签即先无条件 `showSettings = false`，再按需更新 `selected`。
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

- 模式经 SharedPreferences 持久化，设置入口在「设置」子页面 →「服务器与网络」→「保活策略」（§3.9），修改立即生效。
- 启停在单一后台执行器串行执行，UI 线程零阻塞；`ServerBus`（StateFlow）同时驱动 UI 与前台服务通知文案（运行中/已暂停/已休眠/已停止）。
- 空闲计时带保护：仍有上传在途（如单个大文件传输超 15 分钟）时顺延，绝不中断传输。
- 上传页在服务器未运行时顶部固定区显示状态面板：省电模式「启动服务器」按钮、智能休眠「唤醒服务器」按钮（<1 秒恢复）、播放/熄屏暂停提示（自动恢复，无需操作）。
- **访问码同生命周期**：启停在 `tryStart` / `stopServer` 两个口子上同时维护「监听 + 访问码」，因此**从休眠/暂停/熄屏恢复也视为一次启动 → 访问码轮换**（用户需重新扫码或重新输入）。见 §3.15。
- 与 README 3.6.1「待机仍可接收」的差异：默认智能模式改为待机暂停接收；需要旧行为请选择极速模式。

### 3.8 数据库与物理文件一致性（SyncManager 对账引擎）

**三表结构**（Room v2，`data/db/Entities.kt`）：

| 表 | 关键列 | 约束 |
|:--|:--|:--|
| media_items | filePath / mediaType(0视频/1图片/2其他) / parentFolder / fileSize / lastModified / duration / addedTime | filePath **唯一索引** |
| playback_history | mediaItemId / position / updatedTime | **外键 → media_items，ON DELETE CASCADE** |
| upload_records | fileName / fileSize / progress / state(0等待/1上传中/2成功/3失败) / category / time | 纯历史日志，删除不触碰物理文件 |

DAO 全部 suspend 协程函数（无 RxJava）；仓储是 UI/服务器层访问 Room 的唯一通道；`PlaybackRepository` 对外保持 path 键调用面，内部桥接外键并在保存进度时自动补建缺失索引。

**四步对账**（`SyncManager.sync()`，全程 Dispatchers.IO，Mutex 串行，严禁阻塞主线程）：
0. **清理解压工作区**：物理清空 `/sdcard/TransView/.temp_unzip/`（`FileUtils.purgeDirectory`）——压缩包解压途中断电 / 进程被杀会留下半个工作目录，不清理会一直占着空间（见 §3.14）。
1. **清理空文件夹**：沙盒内三个分类根目录递归扫描，物理删除空文件夹——子删父空继续向上递归（分类根受 `FileLocations.isRoot` 保护永不删除；入口处 `isInsideSandbox` 断言，越界直接拒绝）。
2. **同步外部删除**（防"有索引无文件"）：遍历 DB 全部 filePath，物理不存在 → 删记录（播放历史经 CASCADE 级联删除）。
3. **同步新增/变更**（防"有文件无索引"）：递归收集**沙盒内**物理文件（用户 U 盘拷入的文件同样入库，属预期行为）→ 无记录的入库（视频时长 MediaMetadataRetriever 提取 + 进程内 ConcurrentHashMap 缓存）；有记录但 fileSize/lastModified 变化的更新。

**触发时机**：Application 启动（appScope 协程，崩溃安全）+ 媒体库菜单键「刷新媒体库（对账）」（协程触发，Toast 汇报四项统计）。完成后 `syncState: StateFlow<SyncState>`（Idle/Running/Done）通知 UI。

**App 内主动删除约定**（FileUtils + LibraryScreen）：先物理删除（`deletePhysicalFile`，连带清理空父目录）→ 成功后才删数据库记录；物理删除失败（返回 false）只弹 Toast **不删记录**，保证数据库永不出现"有索引无文件"。删除入口：焦点在媒体库文件行（列表/网格）按菜单键或删除键 → 弹窗确认（文件夹不可删）。

**异常兜底**（已接入 PlayerActivity）：打开入口或播放错误时发现物理文件不存在 → `SyncManager.reportMissingFile(path)` 删索引，Toast「文件已丢失，已从列表移除」，媒体库列表经 Room Flow 自动刷新。

**媒体库 DB 驱动**（LibraryScreen）：文件列表来自 `MediaRepository.observeByCategory` Room Flow（上传入库/对账/删除均实时刷新，无需手动 re-list）；文件夹层级来自文件系统（DB 不索引文件夹）；视频时长优先取 DB 索引，缺失回退 MediaMetadataRetriever。`media_items.parentFolder` 存**父目录绝对路径**。

**文件操作规范**：全程 `java.io.File`，禁用 SAF/DocumentFile。

### 3.9 设置页（v1.4，SettingsScreen + SettingsStore）

**入口与形态**：原 `ServerModeDialog`（保活模式三选一小弹框）已删除。设置改为**独立子页面**，由 `MainScreen` 的内容区承载（顶部导航栏不变）；导航栏右侧「设置」入口与媒体标签以弹性间距分隔，**聚焦即打开**（`onFocusChanged` 中置 `showSettings = true`，焦点保持在标签上），按 ↓ 经 `settingsFocusTicket` 票据进入内容（与媒体页 `contentFocusTicket` 同一机制）。

**布局**：左侧分组列表（`SettingGroup`：服务器与网络 / 上传与解压 / 播放设置 / 界面设置 / 存储与数据 / 关于）+ 右侧详情面板（`SettingRow` 行：标签 + 当前值 + ▸）。居中布局，聚焦样式复用 `tvFocus()`。
设置项**已全部接通**（v1.4），因此 `SettingRow` 的灰色「待实现」徽标（原 `pending` 参数）已随最后两项接线一并移除。

**焦点规范（v1.4 落地）**：
- 进入默认焦点在左侧首个分组；分组 ↑↓ 切换、→ 进右侧详情（`detailTicket` 驱动）、首分组 ↑ 回「设置」标签。
- 详情行 ← 回左侧当前分组（`groupFocusers` 直接 requestFocus）；每组首行 ↑ 回「设置」标签；首分组 ↑ / 末分组 ↓ / 详情行首 ←、行尾 → 等边缘显式吃掉按键，防环绕到顶部标签切页（与媒体库「左右边界」同规）。
- 弹框**打开时**焦点落在**当前选中项**上（`ChoiceState.selectedIndex` 那一行挂 `FocusRequester` + `LaunchedEffect` 里 `requestFocusNextFrame()`）：否则「● 当前值」与聚焦高亮分别停在两行，视觉上像两个选中项；更实际的风险是用户直接按确定会静默改成**第一项**（实测踩到：网格列数当前 5 列，弹框焦点停在「4 列」，直接确定就把列数改成了 4）。
- 弹框（单选 `ChoiceState` / 确认 `ConfirmState` 两类，同一时刻最多一个）关闭后焦点回原行：`pendingFocusReturn` 记录行 key + `rowFocusMap` 每行 `FocusRequester` + **帧门控重试**（`repeat(10)` 次 `requestFocusNextFrame()`，用 `rowFocused[key]` 状态确认落焦成功——单次请求会被静默丢弃，媒体库同款经验）。
- **「关于」组不使用弹框**（v1.4）：信息条目**直接内联**在右侧详情区。条目用 `AboutEntry`（`focusable()` 但**无 `clickable`**，故按确定 / → 都没有动作，只有焦点高亮）；该组外层 `Arrangement.Top` + Surface `weight(1f)` 撑满剩余高度，卡片内 `verticalScroll(aboutScrollState)`——可聚焦节点在滚动容器内自带 bring-into-view，**焦点上下移动即自动滚动**。条目左右/上下边缘按键与 `SettingRow` 同规（左键回分组、首条 ↑ 回标签、→ 吃掉、末条 ↓ 吃掉防环绕切页）。
- 进入详情的聚焦重试前需**等 `rowFocusMap` 填充**（右侧行在切换分组后才组合渲染，立即请求必然 miss；外层 `repeat(10) + delay(16ms)` 等键出现再走重试）。
- **焦点描边要四边可见，需两个条件同时满足**（v1.4 实测）：
  ① `tvFocus()` 必须挂在可聚焦修饰符（`clickable` / `focusable`）的**上游**——它内部的 `onFocusChanged`
  只能观察下游的焦点节点，写在下游会永远收不到事件，`focused` 恒 false，描边 / 底色 / 缩放全部不生效；
  ② 条目与外层裁剪容器之间要留间隙：条目的描边画在自身边界上，而卡片（带圆角的 `Surface`）会裁剪
  超出部分，条目 `fillMaxWidth()` 与卡片同宽时只剩上下两条描边。做法是
  `.fillMaxWidth().padding(horizontal = 12.dp)` 后再挂 `tvFocus()`，内容内边距同步减少以**保持内容缩进
  不变**（`SettingRow` 12+10=22dp 与改前一致，文本位置不动；`AboutEntry` 12+12=24dp），
  `tvFocus` 的 `scale(1.03)` 向外多占约 8px，12dp 留白足够容纳。
- 设置页打开期间媒体标签 `selected = !showSettings && …`——否则上一个媒体标签（如「其他」）残留选中高亮，与「设置」聚焦高亮叠加造成「两个都亮」的误导（实测踩过）。

**已接通项**：保活策略（`ServerController.setMode`，立即生效）、服务器端口（`ServerController.setPort` 停旧起新，见 §3.10）、开机自启（`SettingsStore.bootAutostart` → `BootReceiver`，见 §3.10）、设备名称（`SettingsStore.deviceName` → 上传页注入，见 §3.10）、自动续播提示 / 自动连播 / 默认倍速 / 默认画面比例（见 §3.11）、网格列数 / 默认排序（见 §3.12）、存储空间占用（`FileLocations.sandboxRoot.walkTopDown()` IO 线程统计）、清空上传记录（`UploadRecordRepository.clearAll`）、清空播放历史（`PlaybackDao.clearAllHistory` + `PlaybackRepository` v1.4 新增）、手动触发对账（`SyncManager.sync()`）、关于（应用名 + `BuildConfig.VERSION_NAME` + 声明 / MIT 全文 / 致谢三个常量，**内联条目**不弹框，见 §3.9）。

**SettingsStore**（`ui/settings/SettingsStore.kt`）：SharedPreferences object（`transview_settings`），保存端口 / 开机自启 / 设备名 / 续播提示 / 连播 / 默认倍速 / 默认画面比例（`AspectRatio` 枚举）/ 网格列数 / 默认排序（`SortOrder`）。**九项全部已接入运行时**（v1.4 收尾）。

**设置值 → 界面刷新**：写 SharedPreferences 不会触发 Compose 重组，因此设置页对「副作用不是本地状态」的项统一用本地 `remember` 状态承载显示值（端口/自启/设备名/续播提示/连播/倍速/画面比例/列数/默认排序），先更新界面再落盘；端口这类异步项失败时回滚显示值。

### 3.10 服务器与网络设置接线（v1.4）

**服务器端口**
- 运行时端口唯一来源是 `SettingsStore.serverPort`；`Constants.DEFAULT_PORT = 2333` 为默认值，预设候选为
  `Constants.ALLOWED_PORTS = [2333, 5210, 8080, 8888, 9527]`（遥控器无键盘，预设免输入）。
- 读值校验：存盘端口不在候选列表时（旧版本遗留的 8081/8088/8089/9000）回落 `DEFAULT_PORT`；
  `PORT_RANGE` 校验仍保留在 `ServerController.setPort()` 里兜底。
- `ServerBus` 新增 `port: StateFlow<Int>`，端口变化即时广播；上传页的地址文本与二维码 `LaunchedEffect(ip, port)` 依赖它，改端口后自动重画。
- **NanoHTTPD 端口在构造时固定**，故 `ServerController.setPort(newPort, onResult)` 走「停旧 → 在新端口重建监听」：成功则持久化 + 刷新总线 + 回调 `true`；失败（多为端口被占用）则**用旧端口回滚重建**并回调 `false`，避免「改端口把服务器改没了」。
- 设置页在该回调里才更新显示值，并 Toast 告知成功/回滚。

**开机自启**
- `service/BootReceiver.kt` 接收 `BOOT_COMPLETED` → 读 `SettingsStore.bootAutostart` → 为真则启动 `ServerService`（是否真正监听仍由保活策略决定）。
- Manifest 声明 `RECEIVE_BOOT_COMPLETED` 权限与 `exported="true"` 的 receiver。
- **前台服务类型（关键，实测踩坑）**：Android 15（API 35）起，`dataSync` / camera / mediaPlayback / phoneCall / mediaProjection / microphone 六类前台服务**禁止**从 `BOOT_COMPLETED` 启动，否则抛 `ForegroundServiceStartNotAllowedException`。因此 service 声明为 `android:foregroundServiceType="dataSync|specialUse"` 并带 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`，运行时按版本选：**API 34+ 用 `specialUse`**（不在受限清单内；顺带规避 dataSync 在 Android 15 上 6 小时/天的时长上限），API 29~33 回退 `dataSync`。
- **异常兜底的位置很关键**：该异常是在**服务内部** `startForeground()` 时抛出的，不是 `startForegroundService()` 的调用点——所以 BootReceiver 里的 `runCatching` 兜不住它。真正的兜底在 `ServerService.onCreate`：`startForegroundCompat` 捕获异常返回 false → 主动 `stopSelf()` 优雅退出。否则进程在 onCreate 阶段 FATAL 崩溃，而 `START_STICKY` 会让 AMS 反复重启服务，形成**开机崩溃循环**（实测：一次开机连崩 3 次）。
- `TransViewApp.onCreate` 全局初始化 `SettingsStore`（`SettingsStore.init`），保证无 Activity 的后台拉起路径也能读到配置。

**设备名称**
- `assets/web/index.html` 的 `<title>` 与 `<h1>` 用 `__DEVICE_NAME__` 占位；`TransHttpServer.serveIndexPage()` **每次请求现读现注入**（含 HTML 转义），改完设备名手机端刷新即生效，无需重启服务器。

### 3.11 播放设置接线（v1.4）

四项均由 `PlayerActivity.onCreate` 起始处读 `SettingsStore`（`SettingsStore.init(this)` 兜底初始化），**打开播放器即生效**，设置页与播放器之间没有跨页实时同步需求（改完下次打开视频即可见）。

**自动续播提示（`autoResumePrompt`，默认开）**
- 续播判断（历史进度 > 10 秒且未到 95%）命中时：开 → `showResumeDialog = true` 弹「继续播放 / 从头播放 / 取消」；关 → 静默 `startPlayback(history.position)` 直接续播。
- 只影响**是否询问**，不影响进度记录本身（进度照旧每 2 秒入库）。

**自动连播（`autoPlayNext`，默认开）**
- `Player.onPlaybackStateChanged(STATE_ENDED)` 里把「有下一集」与开关做与运算：`hasNext && SettingsStore.autoPlayNext` 才 `skipTo(currentIndex + 1)`；关闭时走收尾分支（`ended = true` + 弹控制栏），徽标文案区分「播放结束（自动连播已关闭）」/「播放结束」，用户仍可用「下一集」按钮手动续播。

**默认倍速（`defaultSpeed`）**
- **只是初始值**：`speed = SettingsStore.defaultSpeed` + `player.setPlaybackSpeed(speed)`，本次会话内仍可用控制栏「倍速」随时改，不回写设置。
- 播放器内部倍速选项（0.5~2.0x）比设置页默认值候选（1.0/1.25/1.5）更宽，两者是「默认值」与「运行时可调范围」的关系。

**默认画面比例（`defaultAspect`）**
- `AspectRatio` 三档映射到 `PlayerView` 缩放模式：`ORIGINAL` → `RESIZE_MODE_FIT`（保持比例留黑边）、`STRETCH` → `RESIZE_MODE_FILL`（铺满变形）、`CROP` → `RESIZE_MODE_ZOOM`（铺满裁边）。
- 控制栏新增「比例」按钮（与「倍速」同为 `PlayerTextButton`，非原始值时直接显示当前档位），弹 `AspectDialog` 可在本次会话内临时切换，**不回写**默认值。
- **实现注意**：比例是 `PlayerView.resizeMode`，而 `AndroidView(update = …)` 的 update 块不在组合作用域内读 Compose 状态不会建立订阅（状态变化不会触发它重跑），因此把 `PlayerView` 存进 Activity 字段（`playerView`），初始值在 `factory` 里设置、切档时命令式 `playerView?.resizeMode = …`。
- 对话框显隐需同步加进 `anyDialogVisible`（控制栏自动隐藏与焦点归属的判断依据），否则开着比例弹框时控制栏会自己消失、按键焦点也会跑偏。

### 3.12 界面设置接线（v1.4）

两项都由 `LibraryScreen` 在**组合期直接读** `SettingsStore`（与 `UploadScreen` 读 `deviceName` 同一套路，不引入总线）：设置页会替换掉内容区的媒体库组合（`MainScreen` 的 `if (showSettings) … else when (selected) …`），退出设置回媒体库时必然重新组合，直接读取即能拿到最新值 —— **返回媒体库即生效，无需重启 App**。

**网格列数（`gridColumns`，4/5/6，默认 5）**
- `GridCells.Fixed(gridColumns)`；`gridColumns` 与列数相关的**三处焦点边界判定必须是同一个变量**，否则左右边界会错位：`stayOnLeftEdge = gridIndex % gridColumns == 0`、`stayOnRightEdge = (gridIndex + 1) % gridColumns == 0 || gridIndex + 1 >= totalGridItems`、第一行判断 `gridIndex < gridColumns`。原先的 `private const val GRID_COLUMNS = 5` 已删除。
- 子目录里 UpCard 占网格位 0，`gridIndex` 已含该偏移，故边界判定与根目录同式。
- 注意 `列数` 只在**进入媒体库时**读一次（`val gridColumns = SettingsStore.gridColumns`，普通 `val` 而非 `remember`）——列数是重布局量（`LazyVerticalGrid` 会重建网格），没有必要为它做跨页实时同步。

**默认排序方式（`defaultSort`，默认名称 A-Z）**
- `sortOrder` 初值改为 `SettingsStore.defaultSort`（`rememberSaveable(category.name)`，三个分类各自独立）。
- 语义是**默认值**：进入媒体库时按它开局，用户仍可用工具条「排序」按钮临时改；改动只作用于本次浏览（切标签 / 进设置页都会重建本页组合 → 回到默认值），**不回写** `defaultSort`。

**设置界面**：两项去掉「待实现」徽标，选值后 Toast 提示「返回媒体库即生效」，显示值由本地 `remember` 状态承载（`gridColumnsValue` / `defaultSortValue`）——理由同 §3.9 末段。

### 3.13 手机网页上传队列的取消 / 重试（v1.5）

**网页端（`assets/web/index.html`）**
- 队列项状态机：`wait`（排队）→ `run`（上传中）→ `ok` / `err`；v1.5 新增 `cancel`（已取消，可重试）。每行按状态渲染操作按钮：`wait` / `run` → **取消**，`err` / `cancel` → **重试**，`ok` → 无按钮。
- 取消 `wait`：直接从队列移除（还没开始传，没有残留）；取消 `run`：`xhr.abort()` **真断流**（不是只改 UI），项保留为灰色「已取消」，`onabort` 里落状态 + toast。
- 重试：状态重置为 `wait` 并**插到待上传区最前面**——批量上传时后面还排着一串文件，若只把状态改回 `wait`，用户点了重试要等前面全部传完才有反应。
- `finishOne()` 必须**幂等**：abort 之后部分浏览器会再补一个 `error` / `load` 回调，重复执行会把队列推进两次、**并发跑起两个上传**（用 `done` 标记挡掉）。
- 每个回调开头统一 `if (item.xhr !== xhr) return`：取消后重试会新建 xhr 对象，上一轮迟到的回调不得再改状态。

**服务端（`TransHttpServer.handleUpload`）——中断必须兜底收尾**
- 客户端 `abort` 会让 `parseBody` 抛出 `ResponseException` **之外**的异常（IO 中断）。原先只捕 `ResponseException`，这类异常会冒泡到 `serve()` 变成 500，**且该条上传记录永远停在「上传中」**（`finishRecord` 从未执行过）。
- 修法：接收 + 落盘逻辑抽到 `receiveAndSave(...)`，`handleUpload` 用 `try / catch (e: Exception) / finally { monitorJob.cancel() }` 兜底——任何异常都收尾为 `FAILED` 并返回错误响应。
- 实测：取消一次上传后 DB 中该记录为 `state=3`（失败），**全库无一条 `state=1`（上传中）残留**；`upload_tmp` 临时目录无残留文件（NanoHTTPD 在会话结束时调用 `tempFileManager.clear()`）。

**静态资源路由**
- `GET /icon.svg` / `GET /icon.png` → `serveAsset("web/icon.*", mime)`，从 assets 现读现发（换图后手机端刷新即生效，无需重启服务器）。
- 网页头部标识与浏览器标签页 favicon 共用同一套图形：`assets/web/icon.svg`（矢量，主选）+ `icon.png`（192×192，供不支持 SVG favicon 的浏览器兜底，同时作 `apple-touch-icon`）。

### 3.14 压缩包自动解压（v1.6，ZipExtractor）

**触发条件（三者同时成立）**：分类是视频/图片 + 文件后缀 `.zip` + 设置「上传与解压 → 自动解压压缩包」开启。
任一不满足 → `.zip` 按普通文件落进所选分类目录（「其他」分类永远不解压，直接存 Downloads）。

**流水线**（`server/ZipExtractor.process(temp, name, category)`，`TransHttpServer.receiveZipAndExtract` 以
`runBlocking(Dispatchers.IO)` 调起，不阻塞 UI；上传记录记为「成功」——包已安全收下，解压是后处理）：

1. 上传临时文件 → `.temp_unzip/u<ns>/archive/<原文件名>`（每次上传一个独立工作区，避免并发互踩）；
2. **元数据预估**：`ZipFile` 读中央目录，只累加匹配当前分类条目的未压缩大小；
3. **空间校验**：`预估 × 1.2 > 可用空间` → 拒绝解压，原包保留下载目录；
4. **流式解压**：逐条目 `ZipFile.getInputStream` → 64KiB 缓冲区写工作区 `files/`，保持包内目录结构；
   非本分类条目跳过不落盘；累计写入超可用空间立即 `BudgetExceeded` 中止（防伪造元数据的膨胀包）；
5. **归位**：`UploadStorage.save(file, name, 包内相对目录, category)` —— 同名加 `(1)(2)`、同名文件夹合并、不覆盖，
   随后 `indexMediaAsync` 写入媒体索引；
6. **收尾**：`workspace.deleteRecursively()`；保留开关开启时先把原包 `moveInto(Downloads)` 再删工作区。

**为什么用 `ZipFile` 而不是题面要求的 `ZipInputStream`**：① 预估体积必须读中央目录里记录的未压缩大小，
流式读取要把整包解一遍才算得出来；② 中文包名兼容 —— Windows 资源管理器压缩的包用 GBK 编码条目名且不置
UTF-8 标志位，`ZipInputStream` 固定 UTF-8 解码（遇非法字节抛 `ZipException`）整包会解不出来。
读取仍是**逐条目流式**（`getInputStream` 边解压边读），内存由调用方的 64KiB 缓冲区决定。

**安全与兜底**

- **Zip Slip**：条目名走 `UploadStorage.sanitizeRelativePath`（剔除 `..` / 绝对路径 / 隐藏段 / 盘符）+ 落点
  `canonicalPath` 前缀校验，越界条目丢弃。实测 `abs.zip`（`/abs_evil.mp4`）与 `deep.zip`（`good/x/../../../deep_evil.mp4`）
  被 Android `ZipFile` 在打开阶段直接拒绝，沙盒内外均无越界文件产生；即便被放行也会被上面的清洗拦住。
- **失败必保留原包**（不受保留开关影响）：未命中目标文件 / 空间不足 / 包损坏 / 归位失败 →
  原包移入 `Downloads` + 中文提示。结果经上传响应 JSON 的 `unzip` / `extracted` / `message` 三个字段回给网页端
  （网页 toast），同时 `Handler(Looper.getMainLooper())` 在电视端弹 Toast。
- **残留清理**：工作区名以 `.` 开头 → `listMediaFilesRecursively` / `listEntries` 天然跳过，解压中途不污染媒体库；
  `SyncManager.sync()` 第 0 步 `FileUtils.purgeDirectory(FileLocations.tempUnzipDir)` 兜底清空（App 启动与手动刷新都会走到）。
- **保留原包也要立即入库**：`Outcome.keptZipPath` 由服务器侧一并 `indexMediaAsync`，否则「其他」页要等下次对账才看得到
  （媒体库由 Room 驱动，不读目录）。

**设置项（`SettingGroup.UPLOAD`「上传与解压」）**：`autoUnzipZip`（默认开）、`keepOriginalZip`（默认关 = 解压成功后删包）。

### 3.15 上传访问码（最小认证，v1.7）

**目标**：在不破坏「扫码即传、零手机安装」的前提下加一道门槛，挡住同网段不知道访问码的设备。
**不做**（最小实现）：过期时间、刷新接口、多用户、权限分级、HTTPS、记住访问码。

**访问码生命周期**（权威值在 `ServerController` 内存，不持久化；经 `ServerBus.token: StateFlow<String?>` 广播给 UI，
与 `port` / `mode` 同套路 —— UI 不持有引擎引用）

| 时机 | 行为 |
|:--|:--|
| 服务器启动（首次 / 休眠唤醒 / 熄屏・播放暂停后恢复 / 改端口重启） | `tryStart()` 生成新码 |
| 服务器停止（休眠 / 暂停 / 改端口 / 服务销毁） | `stopServer()` 销毁（置 null） |

- 6 位，字符集 `A-Z` + `0-9`，`SecureRandom` 生成（不做易混字符剔除，按需求固定字符集）。
- **先建码、再起服务，起成功才提交**：码在构造时注入 `TransHttpServer`，所以不存在「运行中换码」的竞态；
  启动失败（端口占用）不动已有状态，回滚到旧端口也能拿到一份与实例匹配的新码。
- 由于是「停旧起新」，**改端口也会轮换访问码**，与「每次启动轮换」的约定一致。

**服务端接口**（`TransHttpServer`）

| 接口 | 校验 | 说明 |
|:--|:--|:--|
| `GET /` | **不校验** | 网页本身得先加载出来，才有地方显示「输入访问码」界面 |
| `GET /verify?token=xxx` | 是 | 匹配 200 `{"status":"ok"}`；缺失/不匹配 403 |
| `GET /icon.svg` `/icon.png` | 否 | 静态图标，无敏感信息 |
| `POST /upload` | 是 | 读请求头 `X-Upload-Token`；缺失或不匹配 → 403 `{"status":"error","message":"认证失败"}` |

- 比对统一 `trim + uppercase`（`checkToken`），与手机端输入框自动转大写对齐；取头走 `headerOf()` 兜一层大小写
  （NanoHTTPD 把头部名统一转小写存表，但不依赖它）。
- **上传的校验放在 `handleUpload` 最前面**：此刻还没建上传记录、也没调 `parseBody` → **请求体一个字节都不落盘**，
  电视端记录列表不会留下任何痕迹（否则未授权设备能靠刷请求把上传记录塞满）。

**TV 端**（`UploadScreen`，v1.7.1 重做左面板视觉）
- 二维码内容 = `http://ip:port/?token=XXXXXX`，扫码后手机端解析 URL 即自动通过，**用户零输入**。
- 访问码做成**色块横条**（`Surface` 铺满面板宽 + `primary.copy(alpha=0.14f)` 底 + 12dp 圆角），
  条内「访问码」小标签（`bodyMedium`）与 6 位码值（`headlineSmall` + 字距 4sp）**同行居中**。
  不用两行竖排：多出的 22dp 高度要由二维码来付，而二维码受竖向预算约束。
  也不用「裸文字行」：小标签紧贴大字会显得像没做完的一行，且无法从上下两条信息里独立出来。
- 左上角标题用 `labelLarge`（原 `titleMedium`）——面板是「越往下越次要」的信息流，标题不该与访问码抢层级。
- 「复制链接」复制的是**带访问码的完整链接**（粘到手机浏览器等同扫码）；屏幕上显示的是不带码的短地址（手输用），两者用途不同。
- 底部只留一行提示（「手机需连接同一 Wi-Fi」），不再重复「当前模式」（顶部状态区已显示）——
  面板竖向预算直接决定二维码能长多大，每一块高度都是抠着给的。

**面板竖向预算**（改这块前先看这条）：左面板高度 = 屏幕高 − Row 的上下 padding（18dp×2），
内部 = 标题 + 二维码(`weight(1f)`) + 访问码条 + 地址 + 复制按钮 + 一行提示。
二维码吃满剩余高度，所以**动任何一块的高度都会等比反噬二维码**。

**二维码尺寸自适应（顺带修掉的历史问题）**：二维码容器为 `weight(1f)`，内部用 **`BoxWithConstraints` 量出可用空间后
显式取 `min(maxWidth, maxHeight)` 作正方形边长**（实测 1280×720 / density 213 下 283×283 px = 213dp，
占面板宽 80%）。
⚠️ **不要写成 `fillMaxSize().aspectRatio(1f)`**：这个链里 `fillMaxSize` 已经把约束变成「固定」，
而 `aspectRatio` 在 `hasFixedWidth && hasFixedHeight` 时**直接原样返回、不做比例修正** ——
结果是图片按**宽度**撑成正方形、竖向外溢压住下方的访问码（实测踩过，白底方块 171px 高 vs 容器 153px）。
改用 `BoxWithConstraints` 后小屏不溢出、大屏也不会被放大过头。

**手机网页**（`assets/web/index.html`）
- 加载时解析 URL：带 `token` → 存 `sessionStorage` 并直接进上传界面（**情况 A：扫码，全程无感**）；
  不带 → 显示**「输入访问码」界面**（**情况 B：手输 IP**）。补充：URL 无 token 但本标签页已验证过（用户按刷新）
  → 复用 `sessionStorage`，不重复输入；**新开标签页**（书签/直连）一律重新过门槛。
- 输入框 `maxlength=6`、`oninput` 自动转大写并剔除非字母数字、回车即提交；「确认」先 `fetch /verify` 预校验再放行。
- 取到访问码后立即 `history.replaceState` 抹掉地址栏里的码：避免刷新/收藏/转发时带着一个可能已失效的码反复进入上传界面，
  也避免访问码随 URL 泄露给被转发到的其他设备。
- 上传请求带 `X-Upload-Token`。
- **403 失效处理**：清 `sessionStorage` → **暂停队列**（`uploadNext` 在无 token 时直接返回，否则剩余文件会被逐个撞成 403 全标失败）
  → 回门槛并提示「认证已失效，请重新输入电视屏幕上显示的访问码」；重新认证成功后 `submitGate` 主动调 `uploadNext()` **让队列续跑**。
- **探针兜底**：服务端读完请求体前就回 403 并关连接时，浏览器（大文件尤其明显）报的是**网络错误而不是 403**。
  `xhr.onerror` 里先探一次 `/verify`：403 → 走失效流程；200 → 按真实连接失败提示；探针失败 → 维持原有「服务器可能已休眠」提示。
  不这样做的话，服务器重启后用户永远看不到「请重新输入访问码」。
  （注意 `finishOne` 定义在 `uploadNext` 内部，`probeAuth` 在 IIFE 作用域，须由调用方把收尾回调传进去。）

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
- [ ] （v1.4 已清空）设置页九项全部接通，无待接线项
- [x] ~~zip 压缩包上传后服务端自动解压并按分类过滤~~（v1.6 已完成，见 §3.14）
- [x] ~~上传访问码（最小认证）~~（v1.7 已完成，见 §3.15）
- [ ] 断点续传（需求 4.4 P2）
- [x] ~~上传中断网时手机端支持「取消/重试」按钮~~（v1.5 已完成，见 §3.13）
