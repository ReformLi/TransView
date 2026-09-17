# TransView 传视 — 架构与实现说明

> 版本：v1.30　日期：2026-09-17
> 对应需求：README.md（局域网媒体中心与传输工具）
> v1.30 变更：**权限配置与适配兼容性专项审查**（详见 §3.27）——
> ① **权限缺口**：`POST_NOTIFICATIONS` 清单早已声明却**从未运行时申请** ⇒ Android 13+ 上通知被静默丢弃，
> 而前台服务常驻通知是用户了解服务器状态（运行中 / 已休眠 / 已暂停）的**唯一**途径。新增
> `util/NotificationPermission` + 在**存储授权通过后**申请一次（存储是硬门槛、通知是可选增强，不抢第一个
> 授权框），`SettingsStore.notifPermissionAsked` 保证只主动弹一次，回调刻意留空（拒绝不影响服务器运行）；
> ② **权限冗余**：删 `ACCESS_WIFI_STATE`（唯一使用者 `NetUtils.wifiManager()` 是死代码，且新系统无定位权限时
> SSID 恒为空占位值）与重复声明的 `ACCESS_NETWORK_STATE`（media3 已声明且 Coil 的 `RealNetworkObserver` 依赖它，
> 删本工程那行 APK 里照样存在，只是不再重复声明）；
> ③ **适配·高危：Android 16 在大屏忽略 `screenOrientation`** —— 官方行为变更：**最小宽度（smallestWidth）≥ 600dp** 的
> 屏幕忽略 screen orientation / aspect ratio / resizability 限制，且 **API 37 将移除 opt-out**。三个 Activity 均锁 `landscape`，
> 而顶部导航栏是**不换行** `Row`、非紧凑态整行约需 **950dp** ⇒ 平板竖屏 / 桌面窄窗口下右侧「设置」标签与
> 状态徽标被**挤出屏幕**。（**手机 sw 360~450dp 与 1080p 电视 sw 540dp 都 < 600dp**，落在官方例外清单
> *Displays smaller than sw600dp* 内，**不受影响**；真正受影响的是平板 / 大折叠内屏 / 桌面窗口模式。
> 注意判据是 smallestWidth 而非屏幕宽度 —— 960dp 是电视的**宽度**，不是它的最小宽度。）修法**双管齐下**：
> (a) 清单声明 `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` 恢复兼容模式（**有保质期的安全网** —— API 37
> 移除，且官方明确 *targetSdk ≥ 36 时该属性并不锁定大屏方向*）；(b) 新增 `TOP_BAR_FULL_WIDTH_DP = 960` +
> `rememberTopBarTight()`，宽度不足时真正收窄顶部栏（→ 约 400dp）。阈值取「严格小于 1080p 电视常见宽度
> 960dp」⇒ 该类电视逐像素不变，**但估算需求 ≈950dp 余量仅约 10dp，待真机实测**；
> 内容区缩放仍由 `CompactContentDensity` 按高度独立负责，两者判据互不影响；
> ④ **`MainActivity` 补 `configChanges`**（另两个 Activity 早有）—— 方向锁失效后旋转 / 改窗口尺寸会重建
> Activity，`showSettings` 等普通 `remember` 状态会被丢掉（`selected` 有 `rememberSaveable` 所以能活）；
> ⑤ **备份规则排除数据库**：`backup_rules.xml` / `data_extraction_rules.xml` 原为 Studio 空模板（= 全量备份），
> 而 `media_items.filePath` 是**卷根绝对路径**，跨设备恢复后全部不可达 ⇒ 排除 `database` / `file` / `external`
> 三个域，只保留 `sharedpref`（媒体库是磁盘索引，启动时由 `SyncManager` 重新对账补齐，排除无副作用）；
> ⑥ **无硬编码分辨率**：全工程扫描确认无绝对分辨率数字与 `displayMetrics` 判布局写法（细节见 §3.27.4）。
> **经核实不改**：`MANAGE_EXTERNAL_STORAGE` 是核心功能唯一可行解（工程硬约束「禁用 SAF / DocumentFile」），
> 保留但需知悉 Google Play 有政策申报要求；FGS 三权限与 `ServerService` 的版本分支严格对应，**不要简化**。
> v1.29 变更：**「清不掉的数据 / 死文件 / 冗余代码」专项清理**（详见 §3.26）——
> ① **上传临时文件永久残留**：`Android/data/<包名>/files/upload_tmp/upload_*.tmp` 在「进程被杀 / 断电」时
> 因 NanoHTTPD 的 `TempFileManager.clear()` 没机会执行而永久留下；该目录不在媒体沙盒内、Android 11+ 对文件
> 管理器也不可见 ⇒ **此前没有任何清理路径**（一次中断的大文件上传可留下 GB 级不可见占用）。新增
> `TransHttpServer.purgeOrphanUploadTemps()`，App 启动（窗口 0，进程刚起必无在途上传）与服务器启动 /
> 设置页清缓存（窗口 10 分钟，避「停服立刻重启」「清理时正在上传」竞态）三处清扫；
> ② **不可达索引无出口**：对账按「读不到 ≠ 被删」永不删「所属卷已不在设备上」的索引、媒体库又显示不到它们，
> 这些记录只能永远躺在库里 → 设置页新增「**清理不可达索引**」（先报条数、确认弹框写明风险、`destructive` 红字，
> 删除时经外键 CASCADE 连带播放历史；判据比「卷在位但已拔出的首选盘」更严格，后者不清理）；
> ③ **应用缓存无入口**：反编译确认 Coil 2.7 未配 `diskCache` 时默认建 `cacheDir/image_cache`
> （`SingletonDiskCache`，目录名常量 `image_cache`），不在媒体沙盒、「存储空间占用」也统计不到 →
> 新增 `FileUtils.cacheSizeBytes/clearAppCache` + 设置页「**清理缓存**」行；
> ④ **上传记录尾部长尾**：表保留 500 条而 UI 只读 200 条 ⇒ 最多 300 条查不到也删不掉单条 →
> `MAX_RECORDS` 与 UI 上限对齐 200；
> ⑤ **批量删索引撞 SQLite 宿主参数上限**（旧版 999）：Room 把 `IN (:paths)` 展开成等量绑定参数，超限整条语句抛异常、
> 被对账兜底 `catch` 吞成「对账异常」⇒「一次删掉上千个文件后僵尸卡片永远清不掉且无提示」→
> `MediaRepository.deleteByPaths` 分批（400/批）；
> ⑥ **冗余清理**：删除整份死文件 `ui/common/VideoMeta.kt`（零引用）、无用资源 `@color/ic_launcher_tint`、
> 8 处未使用 import、工程根目录 4 项临时产物（`apk_info.tmp` / `tv_scroll_mem.py` / `.tmp_test/` / `.trash/`），
> `.gitignore` 补 `*.tmp`、`.tmp_test/`、`.trash/`。
> **经核实无需改**：`.temp_unzip` 已有「自我 purge + 对账兜底」回收链；`app_log` 按天保留 7 天且可在「其他」页
> 直接打开/删除；`Fullscreen` 主题用系统黑底是刻意设计；`backup_rules`/`data_extraction_rules` 是清单引用的空模板。
> v1.28 变更：**代码审查（5 份报告）核实与修复** —— 逐条读码验证后修掉 3 个严重 + 6 个中等 + 4 个轻微问题：
> ① **并发同名上传覆盖丢数据**（`UploadStorage` 的「列目录 → 唯一名 → 移入」非原子 + `FileStorage.moveFileInto` 走
> `renameTo` 在同卷**静默覆盖**已存在目标）→ 加 `synchronized` 名称分配锁 + `moveFileInto` 改为**永不覆盖**
> （目标已存在直接返回 false，`copyTo(overwrite=false)` 兜底且失败清残块）；② **对账在主线程提取视频时长**（ANR 风险）
> → 逐文件 `upsertFile` 循环包进 `withContext(Dispatchers.IO)`；③ **API 21–28 视频时长恒为 0 + native 泄漏**
> （`MediaMetadataRetriever.close()` 是 API 29+，`.use{}` 编译成 `close()` → 低版本 `NoSuchMethodError` 被
> `runCatching` 吞掉，且 `release()` 永不执行）→ `FileUtils.extractVideoDuration` / `VideoMeta.getDuration` 改为
> 显式 `try/catch/finally { release() }`；④ **对账拔盘竞态误删整盘索引** → 删除步前重新 `refresh()`，降级中跳过删除，
> 且 `existsForPath` 增加「外接盘卷根此刻不存在 → 判存在」；⑤ **`finishedPaths` 残留**（重看已看完的集数后进度不再保存）
> → `skipTo` / `重播` 时移出集合；⑥ **`TransHttpServer.bgScope` 泄漏**（智能模式每次停服只 `stop()` 不取消作用域）
> → `stopServer()` 补 `shutdown()`；⑦ **zip 解压空间预算失效**（把「剩余空间」当 `needed` 传入）→ 改传「预估体积 ×1.2」；
> ⑧ **顶部标签栏左右边界未拦截**（行首按左 / 末项按右会环绕到标签、「聚焦即选中」误切页）→ `TabChip` 收
> `stayOn*Edge`；⑨ **设置页「存储空间占用」不自动刷新** → `LaunchedEffect` 改挂 `storageState.activeKey`；
> ⑩ 轻微项：「已清空」Toast 移进 `launch` 内（原先在 suspend 删除之前就提示）、`return@repeat` 改 `for+break`
> （前者等价 `continue`，帧门控重试等于没生效）、续播弹窗异步读库加 `currentFile?.path == path` 守卫（等待中切集会
> 被老文件的续播位置顶掉）、图片查看器横滑切图时同步 `cursorIndex`（否则取景框与主图失同步，按确定跳回旧图）。
> 另：4 处「与需求字面有偏差」经确认属设计取舍，未改动（见 §3.25）。
> v1.27 变更：**修「TV 端上传过程中记录不出现、进度不展示」**——根因不在数据层（insert 在收包前执行、排序 SQL 与 Room Flow 表注册均验证正确），而在 `LazyColumn` 的**按 key 锚定滚动**：数据变化时第一可见行按 key 保持不动，顶部插入的新行落在可视区上方，屏幕上毫无变化，直到滑动 / 重组才可见。修法：新顶行是进行中（state ≤ 1）且焦点不在列表内时 `scrollToItem(0)`；焦点在列表内不强滚（屏外行被回收会丢焦点），交由按「上键」的 bring-into-view。（§3.24）
> v1.26 变更：**上传记录排序改为「进行中置顶、成功沉底」**——手机网页与 TV 端同步。**TV 端**：`UploadRecordDao.observeRecent` 查询改为 `ORDER BY (state <= 1) DESC, time DESC, id DESC`（state：0=等待 1=上传中 2=成功 3=失败；`state <= 1` 布尔值 DESC 让等待 / 上传中分组排最前），组内按时间倒序、同秒以 id 稳定排序。**网页端**：显示顺序与处理队列解耦——`queue` 数组保持 FIFO（`uploadNext` 取首个 wait 项、重试插队等**上传顺序**逻辑不变），仅 `render()` 对**副本**排序：等待 / 上传中置顶、成功 / 失败 / 已取消沉底，组内按入队时间戳 `ts` 倒序；重试刷新 `ts`（算一次新上传）。
> v1.25 变更：**手机端系统栏图标固定为浅色**——修「手机上顶部状态栏背景变黑、时间/电量看不清」。三个 Activity 的裸 `enableEdgeToEdge()` 默认 `auto` 样式跟随系统深浅模式：手机系统浅色时状态栏图标为深色，压在 App 画满全屏的恒定深色背景（#0E1116）上看不清。改为显式 `SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)`（状态栏 + 导航栏）：固定浅色图标、透明 scrim。注意 `dark(scrim)` 参数是必填 `@ColorInt Int` 且无默认值；ImageViewer/Player 已导入 Compose `Color`（无 `TRANSPARENT` 常量），统一全限定写法避免导入冲突。**TV 无状态栏不受影响。**
> v1.24 变更：**上传进度修复补全（计数器跨线程绑定）**——v1.23 把计数挪到套接字输入流后真机仍恒为 0%。根因：NanoHTTPD 2.3.1 的 ServerRunnable（**接收连接线程**）调用 `asyncRunner.exec(createClientHandler(...))`，在 `createClientHandler` 里 `ThreadLocal.set(counter)` 设到了 accept 线程；`handleUpload` 跑在 `asyncRunner` 线程池的**工作线程**上，`ThreadLocal.get()` 恒为 null → 进度监视器（`counter == null` 提前返回）从未启动。修法：绑定改由 `CountingInputStream` 在**每次 `read()` 时**把自己的计数器绑到当前线程——流读取只发生在该连接的工作线程上（keep-alive 循环内），天然与 `handleUpload` 同线程；线程池复用安全（下一条连接的流一 read 即覆盖旧值）。计数流改为 `inner class` 以访问外部 ThreadLocal。
> v1.23 变更：**上传进度不再恒为 0%**——修「客户端进度正常，但 TV 端上传页进度一直停在 0%、成功才跳 100%」。根因：进度原由挂在 `TempFile.open()` 返回流上的 `bytesWritten` 计数器驱动，但 NanoHTTPD 2.3.1 在 `parseBody` 阶段先把**整个请求体**读进内存/暂存文件，再于 `decodeMultipartFormData → saveTmpFile` 用 `new FileOutputStream(tempFile.getName())` **按文件名直接写盘**——文件 part 从不经过 `open()` 返回的流，故计数器恒为 0。修法：改为在**套接字输入流**层计数——重写 `createClientHandler`，把连接输入流包成 `CountingInputStream`（每 read 一字节即累加），共享一个 `AtomicLong`；`handleUpload` 在每个请求开始时清零（keep-alive 同连接多文件依次上传互不干扰），进度监视器改用「已接收字节 / Content-Length」回写百分比。客户端进度（XHR `upload.onprogress`）本就正常，未改动。
> v1.22 变更：**设置页选项弹框可滚动**——修「设备名称等候选较多的弹框超出屏幕、下方选项看不到」。修法：弹框 `Column` 加 `verticalScroll` + `heightIn(max = 可用高度 * 0.8)`。
> v1.21 变更：**设置页焦点兜底锚点化 + 分组末行补齐 ↓ 拦截**——修「点空白处 / 划动右栏后焦点跳到『服务器与网络』」。修法：① 新增 `lastContentAnchor`，触摸票据兜底改为锚回最后聚焦节点；② `SettingRow` 加 `bottomEdge`，四组末行置 `true`（见 §3.1.5 / §3.22）。
> v1.20 变更：**设置页两栏可滚动（矮屏兜底）**——修「手机横屏下设置页滑动不了：左侧末项『关于』看不到；选中『存储与数据』后右侧只能看到『存储空间占用』为止」。根因：设置页左右两栏都是**固定高度布局**（左栏 `Arrangement.Center`、右栏只有「关于」那一组的卡片内部挂了 `verticalScroll`），内容高于可视区时**既裁切又不可滚动** —— 矮屏内容区仅约 370 逻辑 dp，而左栏「设置」标题 + 5 个分组约 366dp、右栏「存储与数据」7 行设置 + 小字提示 + 信息条目远超一屏（居中布局溢出时上下同时被切）。修法：① 左栏 `width(280.dp).fillMaxHeight().verticalScroll(leftScrollState)`；② 右栏 `weight(1f).fillMaxHeight().verticalScroll(detailScrollState)`，滚动状态按分组重建（`remember(selectedGroupIndex) { ScrollState(0) }`）→ 切换分组自动回到顶部；③ 「关于」卡片去掉 `weight(1f)`，改贴内容高度（长内容散在整栏滚动里，滚到底能看到完整圆角底边）。**关键点**：`verticalScroll` 只把 `maxHeight` 放开为 `Infinity`，**`minHeight` 会沿传入约束原样下推**给内层 Column —— 于是「内容装得下就居中（`fillMaxHeight` 提供的 min 高度 + `Arrangement.Center`）、装不下就滚动」两者同时成立，**电视 / 平板内容不超出视口，显示逐像素不变**。触摸可直接拖动；遥控器焦点移到被遮挡的行时由滚动容器的 bring-into-view 自动滚入视野（与「关于」组原有行为一致）。（见 §3.9 / §3.21）
> v1.19 变更：**矮屏（手机横屏）内容区整体等比缩放**——修「除顶部标签栏外，各页面字体与间距偏大、一屏装不下几条、看着散」（上传页访问码色块占比大、记录行只能显示约 4 条、「清空所有记录」突兀；媒体库 / 设置页尤其明显）。根因：页面内部按**电视大屏尺度**书写绝对尺寸（正文 16sp、访问码 24sp、图标 40dp、设置行 padding 16dp、网格间距 18dp）——同一批尺寸在 1080dp 高的电视上占屏高 6%，在 360dp 高的手机横屏上要占 18%。修法：新增 `ui/common/CompactUi.kt`，① `COMPACT_SCREEN_HEIGHT_DP = 480` 作为**唯一阈值**（顶部栏 / 上传页左面板 / 内容区缩放共用）；② `CompactContentDensity` 在**内容区**外层覆盖 `LocalDensity`（density × 0.87）——内容区所有 dp/sp **同步等比**缩小，字号与间距/图标/卡片的比例关系不变（「整块 UI 变小」而非「字变小、留白照旧」）；**顶部导航栏在覆盖之外**，保持用户认可的尺寸；③ `rememberContentWidthDp()` 给出缩放后的**实际**逻辑宽度 —— 媒体库的屏宽收敛值改用实际值，否则配置值 800dp 算得 5 列会把用户设置的「6 列」误压成 5 列（实际可用 919dp 本就能容纳 6 列）；④ 顺带把媒体库焦点边界由 `gridColumns` 改为与 `GridCells.Fixed` 同源的 `effectiveColumns`（原先两者在手机横屏列数收敛时不相等，会错位）。主体文字 16sp → 约 13.9sp，与紧凑顶部栏 `labelLarge`(14sp) 齐平。**TV / 平板与两个独立 Activity（播放器 / 图片查看器）逐像素不变。**（见 §3.1 / §3.20）
> v1.18 变更：**上传页矮屏（手机横屏）紧凑模式** —— 修「手机横屏下左面板二维码被压没、地址被 Ellipsis 截断」。
> 根因：左面板是竖向堆叠、二维码靠 `weight(1f)` 吃剩余高度，而横屏内容区只有 ~300dp（TV ~950dp），
> 原先 ~200dp 的固定项（标题 30 + 访问码牌 48 + 地址 26 + 复制 36 + 两行提示 36 + 间距 ~30）把二维码压到几十 dp；
> 且左面板只占宽度 `0.9/2.9 ≈ 31%`（横屏约 215dp、内部仅 171dp），装不下 16sp 的 `http://192.168.x.x:2333`（~205dp）。
> 修法（判据 `screenHeightDp < 480`，与 `MainScreen.isCompact` **同阈值**）：① `Row` 留白 40/18 → 16/10、
> 左面板 `0.9f:2f` → `0.85f:1f`（占比 46%）；② 省略左上角冗余标题（省 30dp）；③ **地址与复制并排一行、
> `CopyAddressButton(iconOnly = true)` 只留图标**（省 36dp），地址 `bodyMedium` + `maxLines = 2` 允许折行；
> ④ 各段间距 10 → 6、访问码牌内边距 8 → 5、面板竖向留白 16 → 12。合计把二维码拉回 **~145dp**。
> **TV / 平板走原路径，逐像素不变**（见 §3.3 上传页职责 / §3.15 TV 端）。
> v1.17 变更：**「导出存储诊断日志」重构为「App 运行日志本地化」** —— ① 设置 → 存储与数据里原「导出存储诊断日志」
> 入口**移除**，代之以**「App 调试日志」开关**（默认关，值存 `SettingsStore.appLogEnabled`，切换**立即生效**）。
> ② 新增 `util/AppLogger.kt` 单例：`d/i/w/e` 在调用线程**只做两件事**——调一次原生 `android.util.Log`（保住
> logcat，行为与改造前一致）+ 把整行文本 `trySend` 进 `Channel`（容量 4096）后立即返回；**队列满即静默丢弃，
> 绝不阻塞业务线程，调用点零磁盘 IO**。③ 专用 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 消费队列，
> `BufferedWriter`（UTF-8）缓冲写入，**凑满 4KB 或空闲满 1 秒才 flush**（空闲期轻量轮询、不做任何 IO），
> 严禁每条日志都 flush。④ 落盘 `<活动沙盒>/TransView/Downloads/app_log/<yyyy-MM-dd>/<HH-mm-ss>.log`：
> **按天分目录、单文件 2MB 切割（`_1`/`_2` 递增）、启动时只保留最近 7 天**；因位于 `Downloads/` 下，
> 该目录**按 v1.16 的「目录即分类」被当作「其他」分类扫描入库** → **TV 端「其他」页可直接翻看**。
> ⑤ 兜底：全部内部操作 `runCatching`，磁盘满/权限不足/IO 异常**静默丢弃日志**、绝不冒泡到业务代码；
> **U 盘拔出写失败 → 关流清缓存 + 重解析落点 + 限流 30 秒触发 `FileLocations.refresh()`**，活动存储自动降级为
> 内部存储并续写；关开关即取消消费协程（其 `finally` 在 IO 线程 flush/close）、排空内存队列。
> ⑥ `SyncManager`/`BootReceiver`/`ServerService`/`FileLocations`（`FileUtils.kt`）共 8 处 `android.util.Log.*`
> 改走 `AppLogger.*`；**`CrashLogger` 刻意不接入**（它运行在未捕获异常路径上，硬约束是不触碰
> `FileLocations`/Room）。
> v1.16 变更：**对账扫描改为「目录即分类 + 格式严格过滤」（方案 A）并做性能/内存优化** —— ① `SyncManager` 第 3 步
> 不再「全量 `listFiles()` 后按扩展名判类型」，改为**按分类目录扫描**：`Movies/` 只收视频（`mediaType=0`）、
> `Pictures/` 只收图片（`=1`）、`Downloads/` 只收「非视频且非图片」（`=2`），格式不符者**不入库、不展示、
> 绝不删物理文件**；入库类型由**目录**决定（`MediaType.fromCategory`）而非文件扩展名。② 格式判定统一走
> `isValidFormatForCategory`（复用既有扩展名工具），并用 **`File.listFiles(FileFilter)` 在列目录阶段完成过滤**，
> 内存只保留「有效文件 + 全部文件夹」，十万级无关文件不再被构造成数组。③ 扫描顺序 **视频 → 图片 → 其他**
> （媒体库先出内容，「其他」不提取时长），**命中即入库**（边扫边看，不等全部扫完）。④ 进度经 `syncState`
> 推送（`Running(step)`，如「正在扫描：Movies/电视剧（12 项）」），完成后推 `SyncComplete`；媒体库工具条
> 实时显示进度并短暂提示「扫描完成」。⑤ 单个目录读不到（权限/拔盘）→ 跳过该目录继续；`sync()` 整体兜底，
> **任何异常都能正常结束**（绝不冒泡）。⑥ 新增 `hasValidContentIn`：文件夹内（递归）没有任何本分类合法文件时
> **不生成文件夹卡片**（如 `Movies/某某/` 全是 .txt；物理目录保留不删）。⑦ `addedTime` 首插改用**文件系统
> lastModified**，使「按时间排序」对手动拷入的文件也成立。**防误删不变式与降级跳过逻辑完全不变。**
> v1.15 变更：**修复 U 盘上「其他」文件无法打开（FileProvider 路径未覆盖可移动卷）**——「其他」分类文件经
> `FileUtils.openExternal()` → `FileProvider.getUriForFile()` 交给系统应用打开，而 `res/xml/file_paths.xml` 原先只声明
> `<external-path name="external_storage" path="." />`（仅对应内部共享存储 `/storage/emulated/0`），**不含可移动卷**，
> 于是 U 盘上的文件报 `Failed to find configured root that contains /storage/<uuid>/TransView/Downloads/…`。视频 / 图片
> 不受影响——它们走 `Uri.fromFile` 直连 ExoPlayer / Coil，根本不经过 FileProvider。修复：`file_paths.xml` 增加
> `<root-path name="storage_root" path="/storage/" />` 与 `<root-path name="media_rw_root" path="/mnt/media_rw/" />`，
> 覆盖任意卷 uuid 挂载点（provider 仍 `exported=false`，仅经显式 `grantUriPermissions` 授权给目标应用）。同版附带
> **顶部导航栏随屏紧凑化**（手机横屏矮屏时缩留白 / 字号、隐藏品牌标题与状态文字），见 §2.1 与 §3.17。
> v1.14 变更：**彻底移除 SAF，U 盘改用直接文件路径（File API）**——真机（Vidda 电视）实测系统的 SAF 授权框架
> （`ACTION_OPEN_DOCUMENT_TREE`）被屏蔽、**授权根本不可能成功**；而用第三方文件管理器验证，U 盘物理路径
> `/storage/0000-0000` **实际可读可写**。结论是问题不在权限、而在「系统不上报存储卷」，于是回到纯 `java.io.File`：
> ① `FileLocations.getWritableDevices()` **重写为「多来源枚举候选卷根 + 写探针」**——`/storage` 的**目录项**在
> Android 11+ 对第三方应用一律不可读（`listFiles()` 恒 null，API 36 实测；但**已知路径照样可读写**），故候选来自
> ①StorageManager 的 `getDirectory()` ②卷 uuid 推导的 `/storage/<uuid>` 与 `/mnt/media_rw/<uuid>`
> ③`getExternalFilesDirs` 反推卷根 ④`/proc/mounts` 挂载点；再逐个做写探针（建+删 `.transview_write_test`），
> 通过才列出（§3.17）；② `SafStorage` 删除，`IStorage` 收敛为**唯一实现 `FileStorage`**，`StorageFile.path`
> **恒为绝对路径**；上传走 `FileOutputStream`，播放/看图/缩略图一律 `file://`（ExoPlayer / Coil 原生支持、不走 IPC）；
> ③ 设置页删除「添加 U 盘（需授权）」与「手动指定 U 盘路径」两行及其全部 SAF 代码（连带删 `androidx.documentfile`
> 依赖），**存储位置**直接列出自动扫描出的可写设备（副标题带「可用 / 共」，选中即写
> `SettingsStore.preferredStoragePath` + 对账）；④ 降级/恢复与防误删不变式**完全保留**；⑤ 对账新增步骤 **1.5**
> 清理 v1.12/v1.13 遗留的 `content://` 索引（§3.17.1）。
> v1.13 变更：**SAF 授权兼容性兜底 + 手动指定 U 盘路径 + 崩溃日志落盘**——真机「点『添加 U 盘（需授权）』白框一闪、
> App 被弹回桌面」。① 授权启动链路全面加固：双渠道预探测（`resolveActivity` + `queryIntentActivities`，识别
> `frameworkpackagestubs` 占位 Stub）、自定义精简 Intent 契约 `SafeOpenDocumentTree`（去掉 `EXTRA_INITIAL_URI` /
> `PERSISTABLE` 标志）、`ActivityNotFoundException`/`SecurityException`/`Throwable` 三级 try-catch、**授权动作推迟到
> 弹框销毁之后**（避开 Dialog window 与 `startActivity` 的 token 竞态）、忙碌锁 + 5s 超时解锁（§3.17）；② 新增
> **手动指定路径**兜底（`FileLocations.tryManualPath`，以 `java.io.File` 直连用户手填的挂载点，自带候选挂载点扫描，
> 见 §3.17.1）；③ 新增 `CrashLogger`（未捕获异常落盘 `TransView/Downloads/crash_log.txt`，不吞异常，见 §3.18）。
> v1.12 变更：**存储抽象层 + SAF 支持物理隔离的 U 盘**——新增 `storage/` 包（`IStorage` 接口 +
> `FileStorage`（`java.io.File`）/ `SafStorage`（SAF 文档树）双实现），上传落盘 / 媒体库 / 对账 / 播放全部改为
> 依赖 `IStorage`；设置 →「存储与数据」→ 存储位置新增「添加 U 盘（需授权）」（`ACTION_OPEN_DOCUMENT_TREE` +
> 持久化授权），解决「U 盘挂在 `/mnt/media_rw` 仅 root 可见、`StorageVolume.getDirectory()` 返回 null」的电视
> 完全无法读写 U 盘的问题；`media_items.filePath/parentFolder` 兼容存 `content://` 文档 URI（§3.17）。
> v1.11 变更：**存储诊断日志导出**（设置 → 存储与数据 → 导出存储诊断日志）——一键把「外接盘插了却不出现在存储位置」
> 的整条判定链路写成 txt（六渠道扫描 + 逐目录写探针 + 沙盒落点检查 + 逐卷排除原因），落盘
> `TransView/Downloads/storage_diagnosis.txt`（与「其他」分类同路径，对账后可 App 内打开、U 盘模式下可拷出）。
> **该功能已于 v1.17 被「App 调试日志」取代，实现文件 `StorageDiagnosis.kt` 已整体删除**（见 §3.19）。
> v1.10 变更：**播放器交互重做——时间轴优先控制栏（§3.4 全节重写）**——① 控制栏三段结构（内联选择器 → 时间轴 → 按钮行），唤出时焦点落时间轴；② 倍速 / 画面比例改横排胶囊 `SelectorPill` 内联选择器、音轨 / 字幕改右侧滑入 `SettingsPanel`（删除 SpeedDialog / AspectDialog / TrackDialog 与 `anyDialogVisible`）；③ 时间轴可聚焦 + 左右拖动 + 上下换轨 / 边缘同向收起（`focusZone` 纵向轨道状态机）；④ 快进 / 快退反馈升级为缩略图预览卡（`loadThumb`：MediaMetadataRetriever + Mutex 串行 + 10s 分桶缓存 48 帧 + seq 守卫）；⑤ 播完自动连播前先弹下一集预告卡（5 秒倒计时，可立即播放 / 取消）。
> v1.9 变更：**移除「上传与解压」设置组**——`SettingGroup.UPLOAD` 枚举、设置页 UI、`SettingsStore.autoUnzipZip/keepOriginalZip` 两键全部删除；压缩包解压改为固定行为：视频/图片分类 `.zip` 恒解压（`TransHttpServer` 触发条件去掉开关）、解压成功即删原包（`ZipExtractor` 第 4 步固定，失败路径仍一律保留）；设置页回归五分组（§3.9 / §3.14）。
> v1.8.1 变更：**死数据治理**——① 僵尸「上传中」记录：进程被杀后 DB 残留的 RUNNING 记录永久卡在「上传中 xx%」，对账新增 0.5 步 `reapZombieRunning()` 统一标失败（仅在服务器未运行或本进程无在途上传时执行，防误伤活记录，§3.8）；② upload_records 表无限增长：insert 后自动裁剪只留最近 500 条（UI 最多显示 200，200 名之外为纯死数据，§3.8）；③ 删除无调用方的死 DAO 方法（MediaItemDao.getById/count、PlaybackHistoryDao.getPositionByPath）；④ 盘点报告：`addedTime`/`updatedTime` 为无读取方的预留字段（保留，成本可忽略）、`UploadStateCode.WAITING` 为无写入方的防御状态（保留 UI 映射）、`fallbackToDestructiveMigration` 发布 v3 起必须换正式迁移。
> v1.8 变更：**需求级修正（按主流习惯）**——① 访问码**会话内固定**：`tryStart` 首次生成、进程内停启/改端口沿用、进程重启才轮换（原「每次启停轮换」会让熄屏/播放/休眠恢复反复把手机端打回输码界面）（§3.15）；② **传输不中断覆盖全部停服路径**：`apply(false)` 遇在途上传跳过停服，`UploadBus.records` 收集器在最后一个上传结束时补评估归位（原仅空闲休眠顺延）（§3.7）；③ 性能指标限定为增量对账 ≤5 秒、首扫视频时长提取为一次性成本（README §4.1）。
> v1.7.2 变更：**健壮性修复**——上传落盘 `Files.move`（API 26+）改 `renameTo`/`copyTo`（minSdk 21 兼容，§3.1 第 4 步）；对账清理解压工作区跳过最近 10 分钟仍在写入的目录（防误删在途解压，§3.6 第 0 步）；上传进度回写改 `updateProgressIfRunning` 条件更新（迟到进度不覆盖最终状态）；播放器播完清史后挂 `finishedPaths` 守卫拦截 100% 进度复活；UploadBus 只裁剪已结束记录（RUNNING 全保留，防并发上传被休眠误伤）；网页端队列入队时快照分类。
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
├── TransViewApp.kt            应用入口（Coil 配置；崩溃日志安装；启动即触发数据库-文件对账）
├── MainActivity.kt            主界面入口 + 权限门
├── storage/                   存储抽象层（v1.14：收敛为纯 File 单实现）
│   ├── IStorage.kt            IStorage 接口 + StorageFile（name/path/relativePath/parentPath/size/mtime）
│   │                          path = 存储身份，v1.14 起**恒为绝对路径**（如 /storage/0000-0000/TransView/…），
│   │                          同时就是 media_items.filePath/parentFolder 的取值
│   └── FileStorage.kt         **唯一实现**（内部存储与 U 盘同样处理）；resolve() 逐段过滤 .. 防越界；
│                              moveFileInto() 同卷 renameTo 零拷贝、跨卷 copyTo+delete
├── model/                     纯数据模型（枚举/数据类，无依赖）
│   └── Models.kt              MainTab / Category / SortOrder / FileEntry / MediaRef（v1.12，替代裸 File）/
│                              ServerMode / AspectRatio（v1.4 设置页画面比例）
├── util/                      无状态工具
│   ├── FileUtils.kt           FileLocations（活动存储 / getWritableDevices 多来源枚举+写探针 / 降级恢复 /
│   │                          越界断言 / mediaUri 恒 file://）、
│   │                          文件树遍历、空文件夹递归清理、时长提取、
│   │                          目录列举/排序、自然比较、格式化、MIME、mediaUri、外部打开
│   ├── NetUtils.kt            本机 IP 探测、存储权限统一入口（StoragePermission，含写探针）
│   ├── IntentUtils.kt         安全启动 Activity（隐式 Intent 显式化，规避 ROM hook NPE）
│   ├── QrCode.kt              ZXing 二维码生成
│   ├── AppLogger.kt           **App 运行日志本地化（v1.17）**：Channel 无锁队列 + IO 协程消费 + BufferedWriter
│   │                          （满 4KB / 空闲 1 秒 flush）+ 2MB 切割 + 保留 7 天 + U 盘拔出自动降级；
│   │                          调用点零磁盘 IO、满队即丢、全 runCatching；仍调一次原生 Log 保住 logcat（见 §3.19）
│   ├── CrashLogger.kt         未捕获异常落盘 Downloads/crash_log.txt（v1.13；不吞异常、不二次崩溃、128KB 轮转。
│   │                          **刻意不接入 AppLogger** —— 崩溃路径不得触碰 FileLocations/Room）
│   └── Constants.kt(并入NetUtils) 默认端口 DEFAULT_PORT=2333、预设端口列表 ALLOWED_PORTS、上传路径
├── server/                    网络接收层（不依赖 UI）
│   ├── TransHttpServer.kt     NanoHTTPD：上传页/访问码校验/上传接口；记录入 Room + 计数流回写进度
│   │                          + 落盘后立即建媒体索引；上传页每次请求注入设备名（__DEVICE_NAME__）；
│   │                          Token 构造时注入（会话内固定：进程停启沿用同一码），/upload 前置校验 X-Upload-Token
│   ├── UploadStorage.kt       落盘规则：分类根目录/同名重命名/文件夹合并/路径消毒
│   ├── UploadBus.kt           上传事件总线（媒体库刷新/空闲计时信号）
│   ├── ServerBus.kt           服务器状态总线（running/hibernated/mode/port/token）
│   └── ServerController.kt    智能保活策略引擎（模式+屏幕/播放/页面信号 → 启停状态机；setPort 停旧起新；
│                              访问码内存权威值：会话内固定，进程首次启动生成、停启沿用、重启才轮换）
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
│   ├── UploadRecordRepository.kt 上传记录仓储（insert/updateState/updateProgressIfRunning/deleteById/clearAll/reapZombieRunning；insert 后自动裁剪至 500 条）
│   └── sync/SyncManager.kt    数据库-物理文件对账引擎（五步，IO 线程，StateFlow 通知）
└── ui/                         Compose 界面层
    ├── theme/                  恒定深色 TV 主题
    ├── common/                 tvFocus 焦点修饰符、TvButton、OptionRow、
    │                           FileTypeIcon（Canvas 手绘）、VideoMeta（时长缓存）
    │                           **CompactUi.kt 矮屏内容区整体缩放（v1.19）**：CompactContentDensity
    │                           （覆盖 LocalDensity，dp/sp 等比缩到 87%）+ rememberContentWidthDp()
    ├── MainScreen.kt           四标签导航 + 右侧「设置」入口（聚焦即打开设置页）+ 返回键回导航栏
    ├── permission/             存储权限引导页
    ├── upload/UploadScreen.kt  左右分栏：左侧固定服务器面板（二维码带访问码 + 访问码色块 + 地址 + 状态）
    │                           + 右侧可滚动上传记录列表；二维码尺寸随可用空间自适应
    ├── library/LibraryScreen.kt 多列网格卡片（列数由设置决定，4/5/6）、文件夹层级、工具条（面包屑+排序+刷新）、菜单/删除/长按确定选项
    ├── settings/SettingsScreen.kt 设置子页面（v1.4 五分组左组右详情；v1.9 移除「上传与解压」组——
    │                           服务器与网络 / 播放设置 / 界面设置 / 存储与数据 / 关于，**全部已接通**；
    │                           三类弹框关闭后焦点回原行）
    ├── settings/SettingsStore.kt 设置偏好持久化（SharedPreferences object；端口/自启/设备名/续播提示/
    │                           连播/倍速/画面比例/列数/默认排序，均含默认值）
    ├── player/PlayerActivity.kt 播放器（v1.10 时间轴优先控制栏：内联选择器/可拖动时间轴/
    │                           右侧音轨字幕面板/缩略图预览卡/下一集预告卡，续播/连播/倍速/比例）
    ├── player/PlayerWidgets.kt 播放器组件（Canvas 手绘 9 图标含 SETTINGS、进度条聚焦态动画、
    │                           选择器胶囊 SelectorPill、预览卡 PlayerScrubCard、预告卡 PlayerNextCard）
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
4. `UploadStorage.save()`：消毒文件名/相对路径 → 逐级建目录（同名文件夹自动合并）→ 同名冲突加 `(n)` 后缀 → `renameTo` 同卷秒移（跨卷退化为 copy + delete；**刻意不用 `java.nio.file.Files`**——该 API 要求 API 26+，本工程 minSdk 21，低版本会抛 `NoClassDefFoundError` 导致全部上传「保存失败」），返回目标 File。
5. 落盘成功后更新记录状态（成功/失败 + 100%），并在后台协程**立即写入 media_items 索引**（视频经 MediaMetadataRetriever 提时长），`UploadBus` 同时发事件驱动媒体库自动刷新与保活空闲计时；`MediaScannerConnection.scanFile` 通知系统媒体库。

### 3.2 存储策略与权限
- **专属沙盒目录（v1.2）**：App 全部存储收在 `<活动存储>/TransView/`（Movies / Pictures / Downloads 三个子目录），上传落盘、媒体库扫描、清理删除均只在此沙盒内进行，不触碰系统公共目录（防误扫垃圾文件/越界误删）。`FileLocations.isInsideSandbox`（canonicalPath 前缀断言）与 `IStorage` 的 `resolve()`/`safeSegments()` 是破坏性操作的前置防线。
- **活动存储（v1.14）**：沙盒所在卷由「设置 → 存储与数据 → 存储位置」决定（内部存储 / 自动扫描出的可写外接盘），运行时由 `FileLocations.activeStorage: IStorage` 暴露；首选卷写探针失败或已拔出即自动降级到内部存储并广播事件，插回自动恢复。数据层、服务器层、UI 层一律经 `IStorage` 读写，细节见 §3.17。
- API 30+：`MANAGE_EXTERNAL_STORAGE`，引导用户到系统授权页，`onResume` 复检。
  正式授权未通过时做**写探针**（在沙盒 `Movies` 目录建删临时文件）：部分模拟器/ROM（如 MuMu）不强制分区存储但无授权入口，实测可写即放行；真实设备无授权时探测必然失败，行为不变。
- API 21–29：运行时 `WRITE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage`。
- **可移动卷的发现策略（v1.14）**：`MANAGE_EXTERNAL_STORAGE` 已能让 App 读写 U 盘，但**发现**它并不容易——电视 ROM 的 `StorageVolume.getDirectory()` 常为 null，而 `/storage` 的目录项在 Android 11+ 对第三方应用一律不可读。故改为多来源枚举候选卷根 + 写探针（§3.17）。
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
- 顶部 `Row` 左右分栏（按占比自适应）：**左侧固定区**（宽屏 `weight 0.9f : 2f` ≈ 31%；**矮屏紧凑模式 `0.85f : 1f` ≈ 46%**，v1.18，不滚动）= 二维码 + 地址 + 复制按钮 + 服务器状态/唤醒入口；**右侧记录区**（`weight 2f` / 紧凑 `1f`，`LazyColumn` 可滚动）= **纯上传记录列表**（数据库驱动）。
- 每条记录含文件名/大小/进度条百分比/状态（等待中/上传中/成功/失败）/时间/分类六要素；焦点在记录上按**菜单键或删除键**（或右侧「删除」按钮）弹窗确认删除——**仅删 upload_records 日志，本地文件保留**；列表头部提供「清空所有记录」。

### 3.4 播放器（v1.10 时间轴优先控制栏）

**播放列表与进度**
- 同目录视频按**自然排序**（EP2 < EP10）构成播放列表；播完 `STATE_ENDED` 进入结束分支。
- **自动连播改为「预告卡 → 倒计时」两段式（v1.10）**：`hasNext && autoPlayNext` 时不再无感硬切，先 `showNextCard()` 弹右下预告卡（下一集缩略图 + 标题 + 5 秒倒计时，焦点落「立即播放」），倒计时归零 `skipTo(currentIndex+1)`；「取消」/Back/重播/seek 均作废倒计时，取消后停在片尾并徽标「已取消自动连播」。
- **末集（或被连续快进跨过片尾 / 自动连播关闭）**：停在末尾、置 `ended=true`、弹出控制栏并显示「播放结束」（关闭连播时文案区分），等待用户「重播」或按返回离开 —— **不调用 `finish()`**，避免被误当成闪退。
- 进度每 2 秒及 onStop 入库；距片尾 <5s 视为看完自动清历史（`finishedPaths` 守卫拦截清史后的 100% 回写）；再次打开 >10s 且 <95% 时弹续播提示（设置页「自动续播提示」关闭则静默续播，见 §3.11）。
- 外挂字幕：同目录同主名 `.srt/.ass/.ssa/.vtt` 自动挂载为 `SubtitleConfiguration`。
- 音轨/字幕选择基于 `player.currentTracks` + `TrackSelectionOverride`（面板打开时快照重算）；倍速 0.5–2.0；画面比例 FIT/FILL/ZOOM 三档（均见 §3.11）。

**控制栏三段结构（`PlayerOverlay`，v1.10 对齐 Netflix / tvOS）**
自上而下：**内联选择器 → 时间轴 → 按钮行**。图标全为 Canvas 手绘（`PlayerWidgets.kt`：`PlayerIconType { PREV, REWIND, PLAY, PAUSE, FORWARD, NEXT, AUDIO, SUBTITLE, SETTINGS }`）。
- **内联选择器（`InlineSelectorRow` + `SelectorPill`）**：倍速 / 画面比例的横排胶囊，出现在时间轴上方（`expandVertically` 动画）。来源按钮（`PlayerTextButton`「倍速 / 比例」，非默认值直接显示当前值）OK 打开，焦点落**当前选中项**（选中态主色淡填充 + 描边，聚焦态主色实填充 + 放大）；再 OK 即选即生效并收起；Back / 上键收起，焦点回来源按钮（`LaunchedEffect(inlineSelector)` 记录 `lastSelector` 来源并请求回焦）。**替代旧 SpeedDialog / AspectDialog 模态弹框**。
- **时间轴（`TimelineRow`，控制栏主角）**：进度条区域可聚焦（28dp 热区），聚焦态轨道 6→10dp、圆点 7→11dp、主色光环（`PlayerProgressBar(focused=…)` + `animateFloatAsState` 平滑过渡）。聚焦时左右 = 拖动（复用 `onSeekDown/onSeekUp` 固定 10 秒步长 + 变速扫描，反馈走缩略图预览卡）、OK = 播放/暂停；上下交给焦点系统换轨。
- **按钮行**：上一集 / 快退 / 播放暂停（60dp emphasized）/ 快进 / 下一集 ｜ 倍速 / 比例 / 设置（⚙ SETTINGS 图标）。音轨 / 字幕入口合并进 ⚙。
- **右侧设置面板（`SettingsPanel`）**：⚙ 唤出右缘滑入面板（`slideInHorizontally`），内含「音轨 / 字幕」两分区（分区头用 AUDIO / SUBTITLE 手绘图标），`OptionRow` 列表可滚动（`heightIn(max=420dp) + verticalScroll`），字幕区恒有「关闭字幕」；OK 选择即生效并收起、焦点回 ⚙ 按钮；无轨道时占位提示「（无可切换音轨）」。**替代旧 TrackDialog**。
- **下一集预告卡（`PlayerNextCard`）**：右下、控制栏上方；16:9 缩略图 + 标题 +「N 秒后自动播放」+「立即播放 / 取消」（`TvButton`，焦点落「立即播放」）。倒计时协程 `LaunchedEffect(nextCardVisible)` 每秒 -1；`hideNextCard()` 翻转状态即取消协程（取消 / 切集 / 重播 / seek 均走此路径）。

**播放暂停图标统一为「动作式」语义（v1.3 关键决策，沿用）**
- **图标表示按下去会发生什么**：`isPlaying && !ended` → 显示 `PAUSE`(‖)（按下会暂停）；否则显示 `PLAY`(▶)（按下会播放/重播）。
- 单一来源函数 `playPauseIcon()`，**控制栏按钮与中央常驻图标共用**，两处方向永远一致（此前多轮“图标反了”的根因是「状态式」语义 + 两处各自判断）。
- `togglePlayPause()` 按**按下前**的状态决定意图，不在 `player.play()` 后立刻读 `isPlaying` 反推（起播瞬间仍在 BUFFERING，`isPlaying==false`，会显示反）。

**中央视觉反馈**
- **缩略图预览卡（`PlayerScrubCard`，v1.10）**：快进 / 快退时中央显示「16:9 缩略图 + FORWARD/REWIND 图标 + `目标位置 / 总时长`」——用户能看到将要跳到的那一帧，拖动不再是盲跳；缩略图未就绪显示暗色占位。停留 1.2 秒淡出（`LaunchedEffect(scrubTick)`，变速扫描期间每 150ms 重置计时，卡片自然保持在场）。
- **瞬时徽标（`PlayerCentralBadge`，无底色面板）**：起播为**纯图标**（‖，不带「播放中」文字——图标本身已是充分反馈，且与暂停常驻图标同样屏幕正中居中）、结束「播放结束」、取消连播「已取消自动连播」；与预览卡互斥让位。在场判定用 `badgeIcon != null || badgeText.isNotEmpty()`（纯图标徽标 text 为空，不能只判 text）。
- **暂停常驻图标**：`!isPlaying && !ended && badgeIcon == null && badgeText.isEmpty() && scrubText.isEmpty()` 时中央常驻播放图标（无背景），恢复播放淡出。

**缩略图加载（`loadThumb` / `requestScrubThumb`，v1.10）**
- `MediaMetadataRetriever` 取帧：`Dispatchers.IO` + `thumbMutex` 串行（retriever 非线程安全）；单实例复用（同文件不重复 `setDataSource`，切文件才重建）。
- **10 秒分桶缓存**（`thumbBucket`）：预览不需精确到帧，分桶显著提高命中；`LinkedHashMap` 上限 48 帧（宽 256 缩放后 ≈ 7MB），超限淘汰最旧。
- **seq 守卫**（`thumbReqSeq`）：慢速解码完成后只有最新请求才能上屏，防止旧位置的帧闪烁覆盖。
- **变速扫描期间不追帧**（`showSeekBadge` 里 `!scrubStarted` 判定）：扫描每 150ms 一跳、取帧要几十到几百毫秒，追帧只会白烧 CPU 撑爆缓存；扫描结束（`onSeekUp`）与短按落点才请求最终帧。
- 取帧失败（格式不支持 / 文件损坏）返回 null → 占位；`onDestroy` 释放 retriever。

**时间轴优先按键模型（`onKeyEvent` 根节点 `onPreviewKeyEvent` + `focusZone` 状态机）**
- `focusZone`（TIMELINE / BUTTONS / SELECTOR / PANEL / CARD）由各区域 `onFocusChanged` **上报**（而非手动维护），上下键换轨与边缘收起都靠它判定。
- 控制栏隐藏：左右 = 快退/快进（手势直控，连续快进不被打断）；OK = 播放/暂停；上 / 下 / 菜单 = 唤出控制栏，**焦点落时间轴**（`LaunchedEffect(overlayVisible)` → `timelineFocus`，v1.10 前是落播放按钮）。
- 控制栏可见：时间轴聚焦时左右 = 拖动（根节点放行 → 时间轴自身 `onPreviewKeyEvent` 消费）、OK = 播放/暂停；**上下 = 在「时间轴 ↔ 按钮行」间换轨**（放行给焦点系统，物理相邻自动命中）；**最外缘再按同向 = 收起**（时间轴上再按上 / 按钮行上再按下，tvOS 边缘收起习惯；预告卡在场时豁免，防倒计时中途收走控制栏）。
- 内联选择器打开：上键 = 收起（焦点回来源按钮）、下键吞掉（选择器下面没有轨道，防焦点掉进时间轴）、左右放行给胶囊焦点导航。
- 设置面板打开：上下放行（面板内行间导航）；Back 收起。
- 左右键**任何情况下都续命 6s 自动隐藏计时**（否则按键导航不续命，控制栏中途消失、左右键突然变成快进/快退）；选择器 / 面板 / 预告卡 / 续播弹窗在场时**不自动隐藏**（它们是自动隐藏 `LaunchedEffect` 的 key，打开即取消计时）。
- 返回键分层：预告卡 → 设置面板 → 内联选择器 → 控制栏 → 退出。
- 媒体键：⏪/⏩ 快退/快进（不受控制栏状态影响）；⏯/⏭/⏮ 播放暂停 / 下一集 / 上一集。
- 「上一集/下一集」无对应集数时置灰但**仍保留焦点**（`PlayerIconButton` 禁用态可聚焦，见 §3.6）。

**快进/快退（`onSeekDown` / `onSeekUp`，v1.3 沿用）**
- 首次 `KeyDown` 起协程：400ms 内 `KeyUp` = 短按，否则进入变速扫描（节拍 150ms，倍率每 1.2s 翻倍 2x→4x→8x→16x）。
- 短按固定跳 10 秒（v1.10 后不做链式累加：连按多少下都是一下 10 秒，落点可预估），每次弹预览卡。
- 长按期间系统重复 `KeyDown` 被 `seekJob` 拦截（键盘 auto-repeat 拆成的独立按下/抬起对同样安全）；`seekBy()` 对 `duration` 为 `TIME_UNSET`（负数）时兜底，避免 `coerceIn` 抛异常。
- 快进只弹预览卡、不弹控制栏，**保证连续快进不被打断**。

**焦点流转总表（`PlayerScreen` 内各 `LaunchedEffect`，v1.10）**
- 控制栏显隐切换：显示 → 时间轴；隐藏 → 根节点（常驻焦点，遥控器永不失焦）。
- 内联选择器：开 → 选中项胶囊；关 → 来源按钮（倍速 / 比例）。控制栏被一并收起时（Menu / 边缘）不抢焦点，交给「隐藏 → 根节点」路径。
- 设置面板：开 → 面板首个可聚焦行（首个音轨，无音轨则「关闭字幕」）；关 → ⚙ 按钮。
- 预告卡：出现 → 「立即播放」；消失 → 时间轴（控制栏已隐藏则根节点）。
- 续播弹窗：自己管理焦点，关闭后走「控制栏显示 → 时间轴」。

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
- **传输不中断（统一规则）**：任何原因的停服（熄屏 / 播放 / 离开上传页 / 空闲休眠）遇到在途上传都顺延，传完最后一个文件才停。实现：`apply(false)` 检查 `UploadBus` 仍有 RUNNING 记录即跳过停服；init 里对 `UploadBus.records` 的收集在最后一个上传结束、且当前策略仍要求停止时补一次 `evaluate()` 归位。空闲计时同规则顺延（单个大文件传输超 15 分钟不断流）。
- 上传页在服务器未运行时顶部固定区显示状态面板：省电模式「启动服务器」按钮、智能休眠「唤醒服务器」按钮（<1 秒恢复）、播放/熄屏暂停提示（自动恢复，无需操作）。
- **访问码会话内固定**：进程首次启动服务器时在 `tryStart()` 生成一次，此后停启（熄屏/播放暂停/休眠恢复）与改端口均沿用同一码，进程重启才轮换（用户「边看视频边让家人传文件」不会被反复打回输入访问码界面；旧码随进程死亡失效）。见 §3.15。
- 与 README 3.6.1「待机仍可接收」的差异：默认智能模式改为待机暂停接收；需要旧行为请选择极速模式。

### 3.8 数据库与物理文件一致性（SyncManager 对账引擎）

**三表结构**（Room v2，`data/db/Entities.kt`）：

| 表 | 关键列 | 约束 |
|:--|:--|:--|
| media_items | filePath / mediaType(0视频/1图片/2其他) / parentFolder / fileSize / lastModified / duration / addedTime* | filePath **唯一索引** |
| playback_history | mediaItemId / position / updatedTime* | **外键 → media_items，ON DELETE CASCADE** |
| upload_records | fileName / fileSize / progress / state(0等待/1上传中/2成功/3失败) / category / time | 纯历史日志，删除不触碰物理文件；**自动防死数据**：insert 后裁剪只留最近 500 条（UI 最多显示 200） |

\* `addedTime` 无业务读取方（排序用 `lastModified`），作为审计字段保留；v1.16 起对账首插写入**文件系统 mtime**（手动拷入的文件也有合理时间）。`updatedTime` 仍为预留。

DAO 全部 suspend 协程函数（无 RxJava）；仓储是 UI/服务器层访问 Room 的唯一通道；`PlaybackRepository` 对外保持 path 键调用面，内部桥接外键并在保存进度时自动补建缺失索引。

**五步对账**（`SyncManager.sync()`，全程 Dispatchers.IO，Mutex 串行，严禁阻塞主线程）：
0. **清理解压工作区**：物理清空 `/sdcard/TransView/.temp_unzip/`（`FileUtils.purgeDirectory`）——压缩包解压途中断电 / 进程被杀会留下半个工作目录，不清理会一直占着空间（见 §3.14）。清理前先按「目录树内最新修改时间」跳过**最近 10 分钟仍在写入**的工作区：手动对账可能撞上正在进行的解压（或多台手机并发），一刀清掉会把在途压缩包连同已解出的文件一起误删；被跳过的工作区由 10 分钟后的下一轮对账兜底清掉。
0.5. **清扫僵尸「上传中」记录**（`UploadRecordRepository.reapZombieRunning`）：进程被杀 / 断电时请求线程的兜底收尾没机会执行，DB 会残留永远停在「上传中 xx%」的死记录，统一标失败。**仅在服务器未运行或本进程无在途上传时执行**（`ServerBus.running` + `UploadBus` RUNNING 判定）——手动对账可能撞上活的上传记录，那是活数据不能动；App 启动对账时服务器必然尚未启动，僵尸必被清扫。
1. **清理空文件夹**：沙盒内三个分类根目录递归扫描，物理删除空文件夹——子删父空继续向上递归（分类根受 `FileLocations.isRoot` 保护永不删除；入口处 `isInsideSandbox` 断言，越界直接拒绝）。
2. **同步外部删除**（防"有索引无文件"）：遍历 DB 全部 filePath，物理不存在 → 删记录（播放历史经 CASCADE 级联删除）。
3. **同步新增/变更**（防"有文件无索引"，v1.16 方案 A「目录即分类」）：按分类目录顺序（**视频 → 图片 → 其他**）扫描，每个目录**只收本分类合法格式**，过滤在 `FileUtils.scanCategoryFiles` 内用 `File.listFiles(FileFilter)` 于**列目录阶段**完成（内存只保留「有效文件 + 全部文件夹」）。入库类型由**目录**决定（`MediaType.fromCategory`），不按扩展名猜；`addedTime` 首插取文件系统 mtime。格式不符的文件**只跳过、不删物理文件**。**增量**：路径已存在且 fileSize/lastModified/parentFolder 未变 → 直接跳过（不提取时长、不写库）；命中即入库（不等全部扫完）；单个目录读不到 → 跳过继续；进度经 `syncState` 推 `Running(step)`。用户 U 盘/电脑拷入的文件与上传文件同路径入库（只写 `media_items`，无上传记录/播放历史，属预期）。

**触发时机**：Application 启动（appScope 协程，崩溃安全）+ 媒体库工具条「刷新」+ U 盘插拔（ServerService 去抖重检后）+ 设置页切换存储。对账期间 `syncState: StateFlow<SyncState>`（Idle/Running/SyncComplete）推送进度：`Running(step)` 由媒体库工具条实时显示，终态 `SyncComplete` 触发「扫描完成」短暂提示。`sync()` **永不抛出**（整体 try/catch 兜底）。

**App 内主动删除约定**（FileUtils + LibraryScreen）：先物理删除（`deletePhysicalFile`，连带清理空父目录）→ 成功后才删数据库记录；物理删除失败（返回 false）只弹 Toast **不删记录**，保证数据库永不出现"有索引无文件"。删除入口：焦点在媒体库文件行（列表/网格）按菜单键或删除键 → 弹窗确认（文件夹不可删）。

**异常兜底**（已接入 PlayerActivity）：打开入口或播放错误时发现物理文件不存在 → `SyncManager.reportMissingFile(path)` 删索引，Toast「文件已丢失，已从列表移除」，媒体库列表经 Room Flow 自动刷新。

**媒体库 DB 驱动**（LibraryScreen）：文件列表来自 `MediaRepository.observeByCategory` Room Flow（上传入库/对账/删除均实时刷新，无需手动 re-list）；文件夹层级来自文件系统（DB 不索引文件夹）；视频时长优先取 DB 索引，缺失回退 MediaMetadataRetriever。`media_items.parentFolder` 存**父目录绝对路径**。文件夹卡片（来自文件系统）经 `FileUtils.hasValidContentIn` 过滤：递归判定没有本分类合法文件的文件夹**不出卡片**（物理目录保留，不删）。

**文件操作规范**（v1.14 修订）：读写沙盒一律经 **`IStorage`**（`FileLocations.activeStorage`），
**不再直接 `java.io.File`**。`java.io.File` 只保留在三处：`FileStorage` 内部实现、
「必须落到本地 File」的场景（存储权限写探针、解压工作区）、以及 `getWritableDevices()` 的卷扫描。
接口本身保留是为了让上传落盘 / 媒体库 / 对账 / 播放共用一组语义；但 v1.14 起**只有 `FileStorage` 一个实现**，
`StorageFile.path` 恒为绝对路径，`kindLabel` 恒为 `File`，细节见 §3.17。

### 3.9 设置页（v1.4，SettingsScreen + SettingsStore）

**入口与形态**：原 `ServerModeDialog`（保活模式三选一小弹框）已删除。设置改为**独立子页面**，由 `MainScreen` 的内容区承载（顶部导航栏不变）；导航栏右侧「设置」入口与媒体标签以弹性间距分隔，**聚焦即打开**（`onFocusChanged` 中置 `showSettings = true`，焦点保持在标签上），按 ↓ 经 `settingsFocusTicket` 票据进入内容（与媒体页 `contentFocusTicket` 同一机制）。

**布局**：左侧分组列表（`SettingGroup`：服务器与网络 / 播放设置 / 界面设置 / 存储与数据 / 关于）+ 右侧详情面板（`SettingRow` 行：标签 + 当前值 + ▸）。居中布局，聚焦样式复用 `tvFocus()`。
**两栏各自可滚动（v1.20）**：左栏 `fillMaxHeight().verticalScroll(…)`、右栏 `weight(1f).fillMaxHeight().verticalScroll(…)`——矮屏上两栏内容都可能高于一屏（左栏 5 个分组 + 标题、右栏「存储与数据」9 行，v1.20 当时为 7 行）。内容装得下时内层 Column 的 `minHeight` 由 `fillMaxHeight` 撑满、`Arrangement.Center` 照旧居中（**逐像素与改造前一致**），装不下才滚动；右栏滚动状态随 `selectedGroupIndex` 重建，切换分组回到顶部。详见 §3.21。
设置项**已全部接通**（v1.4），因此 `SettingRow` 的灰色「待实现」徽标（原 `pending` 参数）已随最后两项接线一并移除。

**焦点规范（v1.4 落地）**：
- 进入默认焦点在左侧首个分组；分组 ↑↓ 切换、→ 进右侧详情（`detailTicket` 驱动）、首分组 ↑ 回「设置」标签。
- 详情行 ← 回左侧当前分组（`groupFocusers` 直接 requestFocus）；每组首行 ↑ 回「设置」标签；首分组 ↑ / 末分组 ↓ / 详情行首 ←、行尾 → 等边缘显式吃掉按键，防环绕到顶部标签切页（与媒体库「左右边界」同规）。
- 弹框**打开时**焦点落在**当前选中项**上（`ChoiceState.selectedIndex` 那一行挂 `FocusRequester` + `LaunchedEffect` 里 `requestFocusNextFrame()`）：否则「● 当前值」与聚焦高亮分别停在两行，视觉上像两个选中项；更实际的风险是用户直接按确定会静默改成**第一项**（实测踩到：网格列数当前 5 列，弹框焦点停在「4 列」，直接确定就把列数改成了 4）。
- 弹框（单选 `ChoiceState` / 确认 `ConfirmState` 两类，同一时刻最多一个）关闭后焦点回原行：`pendingFocusReturn` 记录行 key + `rowFocusMap` 每行 `FocusRequester` + **帧门控重试**（`repeat(10)` 次 `requestFocusNextFrame()`，用 `rowFocused[key]` 状态确认落焦成功——单次请求会被静默丢弃，媒体库同款经验）。
- **「关于」组不使用弹框**（v1.4）：信息条目**直接内联**在右侧详情区。条目用 `AboutEntry`（`focusable()` 但**无 `clickable`**，故按确定 / → 都没有动作，只有焦点高亮）；该组右栏 `Arrangement.Top`（内容靠上）——可聚焦节点在滚动容器内自带 bring-into-view，**焦点上下移动即自动滚动**（v1.20 起滚动容器由「卡片内部的 `verticalScroll`」上移为「整栏的 `verticalScroll`」，卡片不再 `weight(1f)` 撑满而是贴内容高度）。条目左右/上下边缘按键与 `SettingRow` 同规（左键回分组、首条 ↑ 回标签、→ 吃掉、末条 ↓ 吃掉防环绕切页）。
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

**触发条件（v1.9 起为两者同时成立，无设置开关）**：分类是视频/图片 + 文件后缀 `.zip`——
分类本身就是意图表达（想保留 zip 原样就选「其他」分类）。任一不满足 → `.zip` 按普通文件落进所选分类目录（「其他」分类永远不解压，直接存 Downloads）。

**流水线**（`server/ZipExtractor.process(temp, name, category)`，`TransHttpServer.receiveZipAndExtract` 以
`runBlocking(Dispatchers.IO)` 调起，不阻塞 UI；上传记录记为「成功」——包已安全收下，解压是后处理）：

1. 上传临时文件 → `.temp_unzip/u<ns>/archive/<原文件名>`（每次上传一个独立工作区，避免并发互踩）；
2. **元数据预估**：`ZipFile` 读中央目录，只累加匹配当前分类条目的未压缩大小；
3. **空间校验**：`预估 × 1.2 > 可用空间` → 拒绝解压，原包保留下载目录；
4. **流式解压**：逐条目 `ZipFile.getInputStream` → 64KiB 缓冲区写工作区 `files/`，保持包内目录结构；
   非本分类条目跳过不落盘；累计写入超可用空间立即 `BudgetExceeded` 中止（防伪造元数据的膨胀包）；
5. **归位**：`UploadStorage.save(file, name, 包内相对目录, category)` —— 同名加 `(1)(2)`、同名文件夹合并、不覆盖，
   随后 `indexMediaAsync` 写入媒体索引；
6. **收尾**：`workspace.deleteRecursively()`；解压成功即删原包（固定行为——解压出的文件与原包内容重复，删包避免双份占空间）。

**为什么用 `ZipFile` 而不是题面要求的 `ZipInputStream`**：① 预估体积必须读中央目录里记录的未压缩大小，
流式读取要把整包解一遍才算得出来；② 中文包名兼容 —— Windows 资源管理器压缩的包用 GBK 编码条目名且不置
UTF-8 标志位，`ZipInputStream` 固定 UTF-8 解码（遇非法字节抛 `ZipException`）整包会解不出来。
读取仍是**逐条目流式**（`getInputStream` 边解压边读），内存由调用方的 64KiB 缓冲区决定。

**安全与兜底**

- **Zip Slip**：条目名走 `UploadStorage.sanitizeRelativePath`（剔除 `..` / 绝对路径 / 隐藏段 / 盘符）+ 落点
  `canonicalPath` 前缀校验，越界条目丢弃。实测 `abs.zip`（`/abs_evil.mp4`）与 `deep.zip`（`good/x/../../../deep_evil.mp4`）
  被 Android `ZipFile` 在打开阶段直接拒绝，沙盒内外均无越界文件产生；即便被放行也会被上面的清洗拦住。
- **失败必保留原包**：未命中目标文件 / 空间不足 / 包损坏 / 归位失败 →
  原包移入 `Downloads` + 中文提示。结果经上传响应 JSON 的 `unzip` / `extracted` / `message` 三个字段回给网页端
  （网页 toast），同时 `Handler(Looper.getMainLooper())` 在电视端弹 Toast。
- **残留清理**：工作区名以 `.` 开头 → `listMediaFilesRecursively` / `listEntries` 天然跳过，解压中途不污染媒体库；
  `SyncManager.sync()` 第 0 步 `FileUtils.purgeDirectory(FileLocations.tempUnzipDir)` 兜底清空（App 启动与手动刷新都会走到）。
- **保留的原包也要立即入库**：失败路径 `Outcome.keptZipPath` 由服务器侧一并 `indexMediaAsync`，否则「其他」页要等下次对账才看得到
  （媒体库由 Room 驱动，不读目录）。

### 3.15 上传访问码（最小认证，v1.7）

**目标**：在不破坏「扫码即传、零手机安装」的前提下加一道门槛，挡住同网段不知道访问码的设备。
**不做**（最小实现）：过期时间、刷新接口、多用户、权限分级、HTTPS、记住访问码。

**访问码生命周期**（权威值在 `ServerController` 内存，不持久化；经 `ServerBus.token: StateFlow<String?>` 广播给 UI，
与 `port` / `mode` 同套路 —— UI 不持有引擎引用）

| 时机 | 行为 |
|:--|:--|
| 进程首次启动服务器 | `tryStart()` 生成新码（此后进程内停启 / 改端口均沿用） |
| 服务器停止（休眠 / 暂停 / 改端口 / 服务销毁） | 仅停监听；`ServerBus` 置 null（UI 隐藏码与二维码），**码本身保留** |
| 进程重启（含开机自启重新拉起） | 重新生成（旧码随进程死亡失效） |

- 6 位，字符集 `A-Z` + `0-9`，`SecureRandom` 生成（不做易混字符剔除，按需求固定字符集）。
- **先取码、再起服务，起成功才提交**：码在构造时注入 `TransHttpServer`，所以不存在「运行中换码」的竞态；
  启动失败（端口占用）不动已有状态，回滚到旧端口注入的也是同一份码。
- **会话内固定**（v1.8，替代 v1.7 的「每次启停轮换」）：熄屏 / 播放暂停 / 休眠恢复 / 改端口都不换码，
  「边看视频边让家人传文件」不会被反复打回输入访问码界面——与主流投屏/传输工具「会话内记住配对」
  的惯例一致；码与 App 进程同生命周期，安全性不打折。手机端仅在电视 App 重启后才需重新输入。

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

**矮屏（手机横屏）紧凑模式（v1.18）**：判据 `LocalConfiguration.current.screenHeightDp < 480`，
与 `MainScreen` 顶部导航栏的 `isCompact` **同一阈值**（顶部栏紧凑时本页也紧凑，观感一致）。
手机横屏高度 360~410dp、TV 720/1080dp、平板横屏 800dp+，都落在阈值两侧，不会误判。紧凑时按这个顺序让高度：

① `Row` 留白 `horizontal 40 → 16` / `vertical 18 → 10`，左面板权重 `0.9f : 2f → 0.85f : 1f`
（占比 31% → 46%，面板内部宽度 171dp → ~300dp，16sp 的地址（~205dp）终于放得下）；
② 省略左上角标题（顶部导航栏已写着「上传」，二维码本身也自明）—— 省 30dp；
③ **地址与「复制」并排一行**、`CopyAddressButton(iconOnly = true)` 只留图标（左右留白 12 → 8dp）
—— 省 36dp；地址改 `bodyMedium` 且 `maxLines = 2`（窄机型横屏仅 ~640dp 宽时折两行，**宁可占高也不再截成 `…`**）；
④ 各段间距 `10 → 6`、访问码牌内边距 `8 → 5`、面板竖向留白 `16 → 12`。

合计把二维码从「被压到几十 dp」拉回 **~145dp**，可正常扫码。
⚠️ 这里**不要**改成「二维码在左、文字在右」的横向排布：文字列只剩 ~150dp，
16sp 的地址必然被截断（v1.18 推导时算过）——「竖向堆叠 + 加宽面板」才是对的方向。

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

### 3.16 ~~存储诊断日志导出（v1.11）~~（v1.17 已移除）

> 设置 → 存储与数据里的「导出存储诊断日志」入口在 v1.17 被 **「App 调试日志」开关**取代（见 §3.19），
> 其实现文件 `util/StorageDiagnosis.kt` 已于 v1.17 之后**整体删除**，不再保留任何代码。
> 本节仅保留编号占位，以免后续 §3.17 / §3.18 / §3.19 的交叉引用错位。

### 3.17 存储卷发现与纯 File 存储抽象（v1.14）

**要解决的问题**：一部分 Android TV/盒子的 ROM 把 U 盘挂到 `/mnt/media_rw/XXXX-XXXX`（`0700`，仅 root 可读），
`StorageVolume.getDirectory()` 因此返回 `null`。v1.12/v1.13 曾据此引入 SAF（`ACTION_OPEN_DOCUMENT_TREE` +
文档树读写）作为唯一通路，并为「拉起系统选择器」补了六道防护。但**真机（Vidda 电视）实测**：系统把 SAF 的
目录选择器彻底屏蔽，**授权根本不可能成功**；而用第三方文件管理器验证，U 盘物理路径 `/storage/0000-0000`
**实际可读可写**。结论是——问题不在权限，而在**系统不上报存储卷**。v1.14 因此**彻底移除 SAF**，
回到纯 `java.io.File`。

#### 3.17.1 卷发现：多来源枚举 + 写探针（`FileLocations.getWritableDevices`）

**踩过的坑（API 36 实测）**：最初的实现是「直接扫 `/storage/` 子目录」——**行不通**。
Android 11+ 起第三方应用（即便已获 `MANAGE_EXTERNAL_STORAGE`）**读不到 `/storage` 的目录项**，
`File("/storage").listFiles()` 恒返回 `null`（日志实录：`扫描 /storage：-1 项 [读取失败]`）。
但**已知路径照样可读写**。所以策略改为「**先枚举候选卷根，再逐个写探针**」。

候选来源**四路合并、路径去重**（任一路失败不影响其它）：

| # | 来源 | 说明 |
|---|---|---|
| ① | `StorageManager.storageVolumes` 的 `getDirectory()` | API 30+；**电视 ROM 常返回 null** |
| ② | 卷 `uuid` 拼 `/storage/<uuid>`、`/mnt/media_rw/<uuid>` | `getDirectory()` 为 null 时的兜底；Vidda 正是「不上报目录但路径可读写」 |
| ③ | `Context.getExternalFilesDirs(null)` 去掉 `/Android/data/<pkg>/files` 后缀反推 | **不需要任何权限**，且能发现系统承认的每块可移动卷——**最稳的一路** |
| ④ | `/proc/mounts`（退化 `/proc/self/mounts`）的 `/mnt/media_rw/<id>`、`/storage/<id>` | 挂载表是 ROM 唯一藏不掉的证据 |

之后对每个候选做**写探针**（`probeWritable`：卷根建 `.transview_write_test` 再立即删除）——这是唯一可靠的判据：
`File.canWrite()` 在部分 ROM 上对可移动卷**恒返回 false** 而实际能写；反向「`listFiles()` 非空」也不能证明可写。
候选先过 `isDirectory` 过滤（卷已卸载时该路径不存在 → 跳过），探针通过才进 `volumes`。
盘名取 `StorageManager` 的卷描述（按 UUID 匹配），取不到则显示 `U盘 (卷ID)`；**内部存储恒为第一项**。

> **调试**：每轮扫描逐候选打 `StorageDebug`（候选卷根列表 / `canRead` / `canWrite` / `listFiles` 数 / 写探针结果），
> `adb logcat -s StorageDebug` 一眼看出卡在哪一步。

#### 3.17.2 存储抽象：收敛为单一 File 实现

| 类型 | 职责 | 实现要点 |
|---|---|---|
| `IStorage` | 抽象接口 | 两套寻址：**节点**（`nodeFor/relativeOf/parentNode/listChildren/deleteNode/writeNode`，身份即 `StorageFile.path`）与**相对沙盒根的相对路径**（`listFiles/writeFile/deleteFile`，供上传落盘/遍历/对账） |
| `FileStorage` | **唯一实现** | 纯 `java.io.File`；`resolve()` 逐段过滤 `..`/`.`/绝对路径，天然不越界；`moveFileInto()` 同卷 `renameTo` 零拷贝、跨卷 `copyTo`+`delete` |

`SafStorage` 与 `androidx.documentfile` 依赖已删除；`IStorage.isSaf` 字段删除，`kindLabel` 恒为 `File`。

**节点寻址仍是关键**：`StorageFile.path` 既是「存储身份」又是 `media_items.filePath/parentFolder` 的取值，
v1.14 起**恒为绝对路径**（如 `/storage/0000-0000/TransView/Movies/a.mp4`）。UI 导航（进目录/返回上级/删除）、
对账比对、播放器取源全都用这一个字符串 —— 单个实现下更是零分支。

**URI 形态**：`mediaUri(path)` 恒返回 `Uri.fromFile(...)`（`file:///storage/…`）。ExoPlayer 的 `DefaultDataSource`
与 Coil 都原生支持，且**不走 `ContentResolver`、无 IPC**，比 `content://` 更快更稳。
`MediaMetadataRetriever` 用 `setDataSource(path)`（绝对路径版本比 URI 版本更快）。
唯一例外是**「其他」文件的外部打开**：Android 7+ 不允许直接暴露 `file://`，仍走 `FileProvider`。
`FileProvider` 的可用根目录由 `res/xml/file_paths.xml` 声明：**必须同时覆盖内部共享存储与可移动卷**——v1.14 及以前只写了
`<external-path path="." />`（=`/storage/emulated/0`），导致 U 盘（`/storage/<uuid>`）上的「其他」文件打开时报
`Failed to find configured root that contains …`；v1.15 补 `<root-path path="/storage/" />` 与 `<root-path path="/mnt/media_rw/" />`。

#### 3.17.3 降级与恢复（与前版一致，未改动）

`FileLocations.refresh()` 按 `SettingsStore.preferredStoragePath`（**卷根绝对路径**）在本次扫描出的卷里选
**活动存储**；首选卷不在位 → 活动存储降级为内部存储（**数据库记录全部保留**，媒体库按活动存储的
`parentFolder` 过滤展示），插回自动恢复。列表里「首选但当前不在位」的卷以 `盘名（已断开）` 出现且**不可选**，
是降级模式在设置页里的可视证据。

**防误删不变式**：`existsForPath()` 对「所属卷当前不可见」的路径**一律判存在**（「读不到 ≠ 被删了」），
只有卷可用时才做真实探测。否则 U 盘一拔，对账的「同步外部删除」会把整盘索引清空。

#### 3.17.4 移除项与历史数据清理

* **入口移除**：「添加 U 盘（需授权）」（v1.12）与「手动指定 U 盘路径」（v1.13）两行及其全部代码删除。
* **设置键清理**：`SettingsStore.init()` 一次性删除 `saf_tree_uri` / `saf_tree_label` / `manual_storage_path`；
  `preferredStoragePath` 读到**非 `/` 开头**的值（旧的 `content://` 文档树 URI）一律回落内部存储并**就地改写**。
* **索引清理**：`SyncManager.sync()` 新增步骤 **1.5**——删除 `media_items` 中 `filePath` 以 `content://`
  开头的记录（文档树已永久不可达，留着只会变成点不开的僵尸卡片）。**与降级无关**：任何状态都清，
  故放在降级判断**之前**。
### 3.18 崩溃日志落盘（v1.13）

`CrashLogger`（`TransViewApp.onCreate` 最先安装）接管 `Thread.setDefaultUncaughtExceptionHandler`，
把时间 / 线程 / 版本 / 设备 / 异常与完整堆栈追加到 `TransView/Downloads/crash_log.txt`，再**原样委托**
给原处理器（不吞异常，Logcat 与系统崩溃上报不变）。三条硬约束：① 全部 IO 包 `runCatching`；
② 不触碰 `FileLocations` / Room 等（可能正是崩溃源头或依赖初始化顺序），落点直接用 `Environment` 拼内部存储
沙盒（崩溃可能正因为那块 U 盘）；③ 超 128KB 轮转为 `crash_log.old.txt`。文件与「其他」分类同目录 →
重启对账后可在 App 内打开，也可拷到电脑。定位真机「白框闪退」就靠它。

**v1.14 已验证（模拟器 API 36，第二卷 `/storage/0000-0000` 等价于 U 盘）**：卷扫描逐候选日志
（`/storage/0000-0000` 写探针=true、`/mnt/media_rw/0000-0000` 写探针=false 被正确排除）→ 设置页
「存储位置」列出 `SDCARD (0000-0000)` 并显示「可用 509.9MB / 共 510.0MB」→ 选中后
`preferred_storage=/storage/0000-0000` 落盘、当前存储切为 U 盘 → 上传（`/verify` 200 / 错误码 403 /
无 token 403 / 带 token 200）按分类落到 `/storage/0000-0000/TransView/{Movies,Pictures,Downloads}`，
内部存储未被写入 → `media_items` 存绝对路径、`content://` 残留 0 → 媒体库按活动存储过滤展示 →
ExoPlayer 用 `file://` 播放 U 盘视频（MediaCodec 正常解码，无异常）→ `sm unmount` 卷后重启：降级模式
（上传页「⚠️ …已断开」、设置页「降级模式（…），正在使用内部存储」、断开的卷不可选、47 条上传记录全保留）
→ `sm mount` 插回后自动恢复（上传页显示 `SDCARD (0000-0000) (509.9MB 可用)`）→ 删文件后对账把索引一并清掉
（55→52 行，`integrity_check=ok`）。

**未验证项（诚实记录）**：**Vidda 真机未复验**。本机模拟器上 `getDirectory()` 与 `getExternalFilesDirs`
都可用，而 Vidda 的 `getDirectory()` 返回 null —— 那条路径靠「来源 ②（uuid 推导）+ 来源 ④（`/proc/mounts`）」
兜底，逻辑上覆盖，但**需要在真机上确认**（若真机仍发现不了，看 `StorageDebug` 日志里候选卷根列表缺了哪一条即可定位）。
另：模拟器的第二卷是 FUSE 挂载、`canWrite=true`，而 Vidda 上 `canWrite` 大概率为 false —— 写探针正是为这种情况准备的，
但目前只在「探针=false 时被正确排除」这一侧得到验证。

### 3.19 App 运行日志本地化（App 调试日志，v1.17）

**要解决的问题**：电视端**没有终端、拿不到 logcat**。`CrashLogger`（§3.18）只覆盖「崩溃」；
而实际排障中最常见的恰恰是**非崩溃**问题 —— 对账为什么没扫到新文件、服务器为什么没起来、
上传为什么卡在「上传中」、存储什么时候降级的。这些以前完全没有留下任何证据。
v1.11 那个「导出存储诊断日志」（已随 v1.17 一并移除、实现文件删除）虽然是本地取证，但**手动、一次性、只针对存储**，覆盖面太窄。

**做法**：`util/AppLogger.kt` 单例，把运行日志**常态化异步落盘**，由设置项「App 调试日志」开关控制。

#### 3.19.1 设置项与交互

* 设置 → 存储与数据 → **App 调试日志**（`focusKey = "存储与数据:3"`，槽位固定为 `:3`；
  其余三项 `:4/:5/:6`（清空上传记录 / 清空播放历史 / 手动触发对账）的焦点 ID 不受影响）。
* **默认关**。值持久化在 `SettingsStore.appLogEnabled`（键 `app_log_enabled`）。
* 开关实现沿用本项目布尔设置项的既有形态：**两项选择弹框（开启 / 关闭）**，而不是 Android 的 `Switch`
  —— TV 端遥控器上「聚焦 + 确定选值」比「左右拨动 Switch」更稳（也避免引入新的焦点节点）。
* 行内副标题（`SettingRow` 新增可选参数 `subtitle`）：「记录调试日志（仅供排查问题，长期开启可能影响性能）」。
* 开关下方一行**不可聚焦**的小字提示（`LogPathHint`），写明**当前**日志位置与查看路径：
  - `日志文件位置：内部存储/TransView/Downloads/app_log/（按日期分目录，文件名即写入时刻）`
  - `查看方式：媒体库「其他」页 → TransView/Downloads/app_log → 当天日期目录 → 打开 .log 文件`
  - 活动存储是 U 盘时自动换成 `U 盘（<盘名>）/TransView/Downloads/app_log/`（`AppLogger.logDirLabel()`）。
* **立即生效**：切换时同步调用 `AppLogger.setEnabled(on)`，无需重启。
  `TransViewApp.onCreate` 在 `FileLocations.init` **之后**按偏好启动一次（落点依赖活动存储已检测完成）。

#### 3.19.2 落盘结构

```
<活动沙盒>/TransView/Downloads/app_log/       ← 活动存储 = 首选卷可用则首选卷，否则内部存储
    ├── 2026-09-16/                            ← 按天分目录（yyyy-MM-dd）
    │     ├── 14-30-05.log                     ← 会话首个文件，文件名 = 会话启动时刻（HH-mm-ss）
    │     ├── 14-30-05_1.log                   ← 单文件写满 2MB → 同日目录下按序号新建
    │     └── 14-30-05_2.log
    └── 2026-09-15/                            ← 超过 7 天的日期目录在引擎启动时被清理
```

* 根目录 = `FileLocations.root(Category.OTHER)` + `app_log`，即**始终跟随活动存储**。
* **与对账的联动（关键）**：`app_log/` 位于 `Downloads/` 之下，v1.16 起对账严格「目录即分类」
  （见 §3.8 / README §3.3.7）—— `.log` 既非视频也非图片 → 归入 **「其他」分类**入库。
  于是 **TV 端「其他」页能直接看到 `app_log` 文件夹并可逐级点进去打开日志**，
  完全不需要电脑或 adb（这正是「本地化」的意义）。U 盘模式下日志天然落在 U 盘，也可拔下来插电脑看。

#### 3.19.3 防卡顿：调用点零磁盘 IO（核心设计）

```kotlin
// 调用线程只做两件事，绝不碰磁盘、绝不阻塞
AppLogger.i(TAG, "…")
  ├─ ① android.util.Log.i(TAG, "…")          // 原生 Log 恒输出 → logcat 行为与改造前一致
  └─ ② queue.trySend("时戳 级别/TAG: 内容")   // Channel(4096) 无锁队列；**满则丢弃，直接返回**

// 专用 IO 协程消费
CoroutineScope(SupervisorJob() + Dispatchers.IO)
  └─ while (isActive) {
        取一条 → BufferedWriter 写入（UTF-8）
        满 4KB 或距上次 flush ≥ 1 秒 → flush
        队列空 → 到点 flush 后 delay(120ms)   // 空闲期不做任何 IO
     } finally { flush + close }              // 关开关被取消时，在 IO 线程安全收尾
```

* **严禁每条都 flush**：靠 `BufferedWriter` + 「满 4KB / 空闲 1 秒」两个阈值，电视端存储 IO 不会被日志打满。
* **为什么空闲时用 `delay` 轮询而不是 `withTimeoutOrNull { queue.receive() }`**：`Channel.receive()`
  在超时取消时**存在丢元素**的语义风险（官方建议用 `select`）。这里选择 120ms 轻量轮询 ——
  开销可忽略（不做任何 IO），却完全没有丢元素的可能。
* **队列满 = 静默丢弃，绝不回压**：这是**故意**的取舍 —— 日志价值远低于业务可用性，
  宁可丢日志也不能让上传 / 对账 / 播放因为写日志而变慢。

#### 3.19.4 切割与历史清理（防无限增长）

* **单文件上限 2MB**（电视端文本文件过大，读取/渲染都会卡）：写满立即 `flush+close`，
  同日目录下按 **`<base>_1.log` / `<base>_2.log`** 递增新建（`<base>` = 会话启动的 `HH-mm-ss`），
  **绝不允许单文件无限追加**。序号选择会跳过已达上限的同名文件，并有 `MAX_SEQ=999` 防御性上限。
* **只保留最近 7 天**的日期目录，更早的物理删除。**双重保险确保不误删**：
  ① 目录名必须严格匹配正则 `\d{4}-\d{2}-\d{2}`（`app_log/` 下的其他东西一律不碰）；
  ② 必须通过 `FileLocations.isInsideSandbox` 断言（沙盒外一律不删）。
  由于 `yyyy-MM-dd` 是定长字典序 == 时间序，按目录名倒排 `drop(7)` 即可，**不需要解析日期**。
  清理范围**仅限 `app_log/` 下的旧日期目录**，绝不影响 `Downloads/` 下的其他文件。

#### 3.19.5 异常兜底与边界

| 场景 | 行为 |
|:--|:--|
| 磁盘满 / 权限不足 / 任何 IO 异常 | 全部 `runCatching` + 本类**内部只用原生 `Log`** 记录，**静默丢弃日志**，异常绝不冒泡到业务代码 |
| **U 盘拔出**（活动存储是 U 盘） | 写失败 → `close()` + 清空缓存的落点 → **下一行重新解析落点**；并（**限流 30 秒**）触发一次 `FileLocations.refresh()`，活动存储自动降级为内部存储 → 日志无缝续写到内部沙盒。**绝不因拔 U 盘而中断或崩溃** |
| 活动存储状态尚未刷新 | `fallbackBaseDir()` 兜底为 `<内部存储>/TransView/Downloads/app_log/`（`Environment` 硬拼，不依赖 `FileLocations`） |
| 关开关 | 取消消费协程（`finally` 在 IO 线程 flush/close 文件流）+ **排空内存队列**（纯内存操作，可在调用线程直接做，不违反「不在调用点做 IO」） |
| 中文日志 | 写盘强制 `Charsets.UTF_8`，不乱码 |
| 日志体积统计 | 用 `utf8Len()` 逐字符估算字节数（不分配临时数组），2MB 判断对中文日志同样准确 |

#### 3.19.6 全局替换与两处刻意的例外

`android.util.Log.*` → `AppLogger.*` 共 **8 处 / 4 个文件**：

| 文件 | 处数 | 说明 |
|:--|:--|:--|
| `util/FileUtils.kt`（`FileLocations`） | 4 | `getWritableDevices` 的候选卷根 / 写探针结果（`TAG = StorageDebug`）；改后 `adb logcat -s StorageDebug` **照旧可用** |
| `service/BootReceiver.kt` | 2 | 开机自启拉起前台服务 / 启动失败 |
| `service/ServerService.kt` | 1 | `startForeground` 失败 |
| `data/sync/SyncManager.kt` | 1 | 对账异常兜底结束 |

**刻意的例外（不得改）**：

* **`CrashLogger`（§3.18）继续直接用 `android.util.Log`**。它运行在「未捕获异常」路径上，硬约束是
  **不触碰 `FileLocations` / Room**（那可能正是崩溃源头）。接入 `AppLogger` 会经
  `FileLocations.root(Category.OTHER)` 解析落点 → 引入对崩溃源头的依赖，属于**把兜底改脆**。
* **`AppLogger` 自身内部失败不走队列**，只用原生 `Log`：否则「写盘失败 → 记日志 → 再写盘失败」会自激。

> **死锁/递归安全说明**：`AppLogger` 与 `FileLocations` 之间存在双向引用
> （`AppLogger` 解析落点读 `FileLocations`；`FileLocations` 的扫描日志走 `AppLogger`），
> 但**不会死锁也不会无限递归** —— 因为 `AppLogger.d/i/w/e` 在调用线程**只 `trySend`（无锁、不等待消费者）**，
> 写失败触发的 `FileLocations.refresh()` 又有 30 秒限流，且只发生在消费侧。

### 3.20 矮屏（手机横屏）内容区整体缩放（v1.19）

**要解决的问题**：手机横屏（屏高 360~410dp）下，除顶部标签栏外各页面都「字大、一屏装不下几条、看着散」
—— 上传页访问码色块占比大、右侧上传记录只能显示约 4 条、「清空所有记录」按钮突兀；媒体库与设置页尤其明显。

**根因**：页面内部全部按**电视大屏尺度**书写绝对尺寸：正文 `bodyLarge` / `titleMedium`(16sp)、
上传页访问码 `headlineSmall`(24sp)、类型图标 `Modifier.size(40.dp)`、设置行 `padding(vertical = 16.dp)`、
网格间距 `spacedBy(18.dp)`。同一批绝对尺寸在 1080dp 高的电视上只占屏高 6%，在 360dp 高的手机横屏上
却要占 18% —— **比例的差异**就是「看起来很大」的全部原因（字号本身是标准 Material 值）。

**修法**（`ui/common/CompactUi.kt`，三处判据统一为一个常量）：

1. `COMPACT_SCREEN_HEIGHT_DP = 480` —— `MainScreen` 顶部栏、`UploadScreen` 左面板、内容区缩放
   **共用同一阈值**，避免「顶部栏已紧凑、内容区还是电视尺度」的割裂感。
2. `CompactContentDensity` —— 在**内容区**外层覆盖 `LocalDensity`（`density × 0.87`）。
   `density` 是「1dp = 多少 px」，**调小**它 → 元素物理尺寸变小，同时屏幕可容纳的逻辑 dp 数变多。
   于是内容区里所有 dp / sp **同步等比**缩小，字号与间距 / 图标 / 卡片的比例关系完全不变
   —— 视觉是「整块 UI 变小」，而不是「字变小、留白照旧」（后者会让布局显得更空）。
   **顶部导航栏在覆盖之外**，保持逐像素不变（它的紧凑形态是用户认可的基准）。
3. `rememberContentWidthDp()` —— 内容区**实际**可用的逻辑宽度。`LocalConfiguration.screenWidthDp`
   来自 Activity 配置、**不随覆盖变化**；媒体库按「宽度 / 150」收敛列数，按配置值算会**偏小**，
   把用户设置的列数错误压掉（配置值 800dp 只算得 5 列 → `coerceAtMost` 把「6 列」设置压成 5 列，
   而内容区实际可用 919dp、本就能容纳 6 列）。媒体库改用它。
   ⚠️ 注意列数语义没变：仍是 `gridColumns.coerceAtMost(收敛值)`，**用户设置永远是上限** ——
   本项**不会**把默认的 5 列自动变多。

**取值依据**：0.87 使内容区主体文字 16sp → 约 13.9sp，与紧凑顶部栏的 `labelLarge`(14sp) 齐平。
要调整力度只需改 `COMPACT_CONTENT_DENSITY_SCALE` 一处。

**顺带修掉的一处隐患**：`LibraryScreen` 的网格列数用 `effectiveColumns`，而「第一行 / 行首 / 行尾」
焦点边界用 `gridColumns` —— 两者在手机横屏（屏宽收敛列数）时并不相等，会让左右边界判定错位。
v1.19 起统一为 `effectiveColumns`（与 `GridCells.Fixed` 同源）。

**哪些东西真的变小、哪些没变**：缩的是「有固定绝对尺寸」的东西 —— 字号、图标、
`padding` / 间距 / 圆角 / 固定宽高的弹框；而**填满可用空间**的元素（网格列、左右分栏）
尺寸由列数 / 权重决定，**基本不变**（媒体库卡片宽高几乎与改造前一致，缩小的是卡片内的文字
与网格间距）。想让手机横屏的卡片更小更密，要在设置里把列数调到 6，或改 `COMPACT_CONTENT_DENSITY_SCALE`。

**不变量**：TV / 平板（屏高 >= 480dp）不做任何覆盖，逐像素与改造前一致；
播放器 / 图片查看器是**独立 Activity**，不经过本覆盖，尺寸一律不变；
`LocalConfiguration` 不受 `LocalDensity` 覆盖影响，因此页面内 `screenHeightDp < 480` 的判定依旧可靠；
`BoxWithConstraints` 量出的 `maxWidth/maxHeight` 会随密度一起换算，二者自洽
（上传页二维码 `minOf(maxWidth, maxHeight)` 换算后实际像素尺寸不变）。

### 3.21 设置页两栏滚动（矮屏兜底，v1.20）

**要解决的问题**：手机横屏（屏高 < 480dp）下设置页**滑动不了** —— 左侧分组列表看不到末项「关于」；
选中「存储与数据」后右侧只能看到「存储空间占用」为止，下面的行看不到也点不到。

**根因**：设置页两栏都是**固定高度布局**，没有任何滚动容器：

- **左栏** `Column(width(280.dp).fillMaxHeight(), verticalArrangement = Center)`：内容（「设置」标题
  `titleLarge` + 5 个分组，每组 `padding(vertical = 15.dp)`×2 + 10dp 间距）合计约 366dp；
  矮屏内容区仅约 370 逻辑 dp（屏高 360~410dp 减去紧凑顶部栏，再按内容区 0.87 缩放换算）。
  一旦超出，`Arrangement.Center` 会把溢出量**平均分给上下两端** → 末尾的「关于」被切到屏幕外，
  且不可滚动、无法聚焦。
- **右栏** `Column(weight(1f).fillMaxHeight(), …)` 里**只有「关于」那一组的卡片**挂了 `verticalScroll`，
  「存储与数据」等组完全没有滚动容器 → 超出部分（App 调试日志 / 清空上传记录 / 清空播放历史 /
  手动触发对账）直接被裁掉。

**修法**（`SettingsScreen.kt`，只改修饰符，不动结构）：

1. 左栏：`.width(280.dp).fillMaxHeight().verticalScroll(leftScrollState)`（`rememberScrollState()`）。
2. 右栏：`.weight(1f).fillMaxHeight().verticalScroll(detailScrollState)`，其中
   `detailScrollState = remember(selectedGroupIndex) { ScrollState(0) }` —— 滚动位置**按分组重建**，
   切换分组回到顶部（不会「切过去就停在半截」）。
3. 「关于」组卡片去掉 `weight(1f)`，改 `fillMaxWidth()` **贴内容高度**：滚动上移到整栏后，
   卡片再撑满剩余高度已无意义（内容在卡片外滚动），贴内容反而让长内容滚到底时能看到完整的圆角底边。

**为什么「装得下仍居中」与「装不下能滚动」可以同时成立**：`verticalScroll` 的
`ScrollingLayoutModifier` 测量子节点时**只把 `maxHeight` 放开为 `Infinity`，`minHeight` 原样下推**。
于是内层 Column 拿到的 `minHeight` 仍是 `fillMaxHeight` / `weight(1f)` 给出的视口高度：

- 内容比视口矮 → Column 高度 = `minHeight` = 视口高度 → `Arrangement.Center` 在剩余空间里居中
  → **与改造前逐像素一致**；
- 内容比视口高 → Column 高度 = 内容高度 → 滚动范围 = 内容高度 − 视口高度，正常滚动。

⚠️ 反过来说：**不能**把 `verticalScroll` 挂在没有高度约束的 Column 上（`minHeight` 为 0，居中失效、
内容在 TV 上会贴顶）。这正是本方案必须保留 `fillMaxHeight()` / `weight(1f)` 的原因。

**顺带获得的能力**：① 触摸可直接拖动滚动（以前完全没有，矮屏上只能干看着被裁掉的内容）；
② 遥控器焦点移到被遮挡的行时，滚动容器的 bring-into-view 自动把它滚入视野 ——
与「关于」组原有行为一致，无需额外代码。

**不变量**：TV / 平板内容不超出视口 → 滚动范围为 0，布局与加滚动前**逐像素一致**；
左右边界按键处理（行首 ←、行尾 →、首行 ↑ 回标签、末行 ↓ 吃掉）与 `contentFocused` /
`BackHandler` 分层均未改动。

### 3.22 设置页焦点兜底锚点化 + 分组末行 ↓ 拦截（v1.21）

**要解决的问题**：手机横屏上两个「焦点莫名跳到服务器与网络（左栏第一个分组）」的入口 ——
①「点击设置页空白处」；②「在右栏划动 / 移到分组末行」。前者还会连带把左栏选中项改成第 0 个、
右栏内容一起切走。

**根因（两条独立路径）**：

1. **触摸点空白 / 滑动**：内容区外层 Box 挂着触摸锚点，它在 `PointerEventPass.Initial` 收到 `Press`
   时**无条件**执行 `rootView.requestFocusFromTouch()` + `contentAnchor.requestFocus()` ——
   焦点在按下瞬间就被抢到锚点，`contentFocused` 随即变 false。**只有触点落在可聚焦节点上**时，
   该节点的 `pointerInput`（Main 阶段，晚于 Initial）才会把焦点抢回内容区。触点落在
   **行间 6dp 间隙、行左右各 12dp 留白、不可聚焦的日志路径小字、卡片边缘**时没人抢回，
   150ms 后的票据兜底成为唯一落点，而它此前**无条件**执行
   `groupFocusers[0].requestFocusNextFrame()` → 左栏「服务器与网络」。
2. **右栏分组末行按 ↓**：`SettingRow` 的 `onPreviewKeyEvent` 只处理 ← / → / ↑，**漏了 ↓**
   （`LeftGroup` 的末项 `Key.DirectionDown -> idx == lastIdx`、`AboutEntry` 的 `isLast` 都有）。
   末行按 ↓ 返回 false → Compose 一维方向搜索向下无候选 → 环绕到**整棵树第一个可聚焦元素**，
   即左栏第一个分组。

**修法**：

1. 新增 `lastContentAnchor: String?`，由两栏的 `onFocusChanged` 维护 —— 左栏分组写 `"@group:$idx"`
   （常量 `GROUP_ANCHOR_PREFIX`），右栏行写 `focusKey`（`"分组标题:序号"`），前缀天然区分两栏。
2. `LaunchedEffect(focusTicket)` 的兜底改为**按记忆锚回**：分组 → `groupFocusers[idx]`；
   右栏行 → 帧门控重试（`repeat(10)` + `rowFocused[key]` 确认）直到落焦；两条都失效才退回
   `groupFocusers[selectedGroupIndex]`；**完全没有记忆**（刚进页面、从标签按 ↓）保持原行为落到首个分组。
3. `SettingRow` 新增 `bottomEdge: Boolean = false`（与既有 `topEdge` 对称），
   `Key.DirectionDown -> bottomEdge`；四组末行（设备名称 / 默认画面比例 / 默认排序方式 /
   手动触发对账）置 `true`。

**语义变化**：触摸点空白 / 划空白现在等价于「不改动焦点位置」（锚回原处）。副带效果：
「设置」标签按 ↓ 再次进入内容区时回到**上次停留的位置**，而不是每次都被拉回第一个分组
（首次进入仍是第一个分组，TV 上观感不变）。

**不变量**：TV / 平板与遥控器路径的**既有行为**不受影响 —— 票据兜底只在 `contentFocused == false`
时执行，而遥控器操作全程 `contentFocused == true`。

### 3.23 上传进度计数（v1.23 / v1.24）

**症状**：手机网页进度正常，TV 端上传页进度条一直停在 0%，成功才跳 100%。

**根因（两层）**：

1. **NanoHTTPD 2.3.1 的落盘路径绕过计数器**（v1.23）：`parseBody` 先把整个请求体读进暂存，
   再在 `decodeMultipartFormData → saveTmpFile` 里用 `new FileOutputStream(tempFile.getName())`
   **按文件名直接写盘** —— 文件 part 从不经过 `TempFile.open()` 返回的流，挂在那个流上的 `bytesWritten` 恒为 0。
2. **ThreadLocal 绑错线程**（v1.24）：把计数挪到套接字输入流后真机仍恒 0%。NanoHTTPD 的 ServerRunnable
   （**接收连接线程**）调用 `asyncRunner.exec(createClientHandler(...))`，在 `createClientHandler` 里
   `ThreadLocal.set(counter)` 设到了 accept 线程；`handleUpload` 跑在 `asyncRunner` 线程池的**工作线程**上，
   `ThreadLocal.get()` 恒为 null → 进度监视器（`counter == null` 提前返回）从未启动。

**修法**：把连接输入流包成 `CountingInputStream`（每 read 一字节即累加，共享一个 `AtomicLong`），
并**由计数流在每次 `read()` 时把自己的计数器绑到当前线程** —— 流读取只发生在该连接的工作线程上
（keep-alive 循环内），天然与 `handleUpload` 同线程；线程池复用安全（下一条连接的流一 read 即覆盖旧值）。
`handleUpload` 在每个请求开始时把计数器清零（keep-alive 同连接多文件依次上传互不干扰），
进度监视器改用「已接收字节 / Content-Length」按 600ms 节流回写百分比。
计数流因此改为 `inner class` 以访问外部 `ThreadLocal`。

### 3.24 LazyColumn 按 key 锚定滚动（v1.27）

**症状**：TV 端上传过程中，新记录不出现、进度不展示。

**根因**：上传记录**一请求开始就已插入 DB**（`handleUpload` 先 insert 再收包，排序 SQL 与 Room 生成的
Flow 注册表名均已验证正确），坑在渲染：`LazyColumn` 用 `key = record.id` 后，数据变化时
**按 key 锚定第一可见行**（`updateScrollPositionIfTheFirstItemWasMoved`）—— 新记录插到第 0 位会落在
可视区**上方**，屏幕毫无变化，直到滑动 / 切页重组才看到。机理一直存在（此前列表短 / 空、或没人盯着顶部看），
v1.26 的排序改动让用户第一次盯着顶部看实时上传才暴露。

**修法**：新顶行是进行中（`state <= 1`）且**焦点不在列表内**时 `scrollToItem(0)` 滚到顶部；
焦点在列表内时**不强滚**（`LazyColumn` 回收屏外行会丢焦点），新行交给按「上键」时的 bring-into-view 带进视野。

### 3.25 代码审查修复（v1.28）

对用户提交的 5 份审查报告逐条读码核实后修复。**按危害排序**：

| # | 问题 | 根因 | 修法 |
|---|------|------|------|
| 1 | 并发同名上传**覆盖丢数据** | `UploadStorage` 的「列目录 → 取唯一名 → `moveFileInto`」三步非原子；且同卷 `renameTo`（`rename(2)`）**静默覆盖**已存在目标 | `synchronized(名称分配锁)` 串行化 + 最多 50 次唯一名重试；`FileStorage.moveFileInto` 改为**永不覆盖**：目标已存在直接 `false`，`copyTo(overwrite=false)` 兜底，失败删残块 |
| 2 | 对账**主线程**提取视频时长（ANR） | `SyncManager` 逐文件 `upsertFile` 直接在调用线程跑 | 整段循环包进 `withContext(Dispatchers.IO)` |
| 3 | **API 21–28 时长恒 0 + native 泄漏** | `MediaMetadataRetriever.close()` 是 API 29+，`.use{}` 编译成 `close()` → 低版本 `NoSuchMethodError`，被 `runCatching` 静默吞掉且 `release()` 永不执行 | 改显式 `try/catch/finally { release() }`（`FileUtils.extractVideoDuration`；同时改的 `VideoMeta.getDuration` 在 v1.29 查明是零引用死代码，该文件**已整体删除**，见 §3.26.6） |
| 4 | 对账拔 U 盘**竞态误删整盘索引** | 拔出广播有 2s 去抖，窗口内卷快照仍 `available=true` | 删除步前重新 `refresh()`、降级中跳过删除；`existsForPath` 增加「外接盘卷根此刻不存在 → 判存在」 |
| 5 | `finishedPaths` 残留 | 重看已看完的集数时该路径仍在「已看完」集合里 → 进度不再保存 | `skipTo(index)` / 重播时 `finishedPaths.remove(path)` |
| 6 | `TransHttpServer.bgScope` 泄漏 | 智能模式每次熄屏 / 播放 / 休眠恢复都 stop→start，只 `stop()` 不取消协程作用域 | `ServerController.stopServer()` 补 `runCatching { server?.shutdown() }` |
| 7 | zip 解压**空间预算失效** | 调用处把「剩余可用空间」传成了 `needed` 参数 | 改传「中央目录预估体积 × 1.2」（否则伪造元数据的 zip 能把磁盘写满） |
| 8 | 顶部标签栏**左右边界未拦截** | 首个标签按左 / 末个标签按右会环绕到别处，「聚焦即选中」→ 误切页 | `TabChip` 增 `stayOnLeftEdge` / `stayOnRightEdge`（首项置左、末尾「设置」置右） |
| 9 | 设置页「存储空间占用」不自动刷新 | 只挂了 `LaunchedEffect(Unit)`，进页面算一次；切首选存储 / 降级恢复后显示旧值 | 改挂 `LaunchedEffect(storageState.activeKey)` |
| 10 | 「已清空」Toast 早于异步完成 | Toast 写在 `scope.launch { }` **外面**，suspend 的 `clearAll()` 还没落地就提示 | Toast 移进 `launch` 内、置于 suspend 调用之后 |
| 11 | 弹框关闭后的焦点回退**不早退** | `repeat(10){ …; if (ok) return@repeat }` —— `return@repeat` 只结束**本次迭代**（等价 `continue`），循环仍跑满 10 次；且无 `delay` 时同帧发 10 次请求毫无意义 | 改 `for (i in 0 until 10) { …; delay(16); if (ok) break }` |
| 12 | 续播弹窗异步读库竞态 | 读库期间用户可能已「下一集 / 上一集」切走，回调仍按**老文件**的续播位置 `startPlayback` | 回调首行加 `if (currentFile?.path != path) return@launch` |
| 13 | 图片查看器轮播与横滑**取景框失同步** | 触摸横滑主图走 `switchImage()`（只改 `index`），而取景框跟随 `cursorIndex` | `switchImage()` 内同步 `cursorIndex = next` |

**经核实「不算问题 / 属设计取舍」的 4 处**（未改动）：

1. 播放器时间轴聚焦时 OK / Enter = **播放暂停**（非立即 seek）—— 时间轴整体即「播放暂停键」，拖动才 seek，
   与既有交互约定一致；
2. 时间线加粗 6→10dp、缩放 1.1、120ms（与需求文档写的 4→8px / 1.15 / 200ms 有出入）—— 实测观感更稳，
   且 6→10dp 的相对增幅比 4→8px 更大，不构成弱化；
3. `scanVolumes()` 的 API 21–23 回退路径已随 v1.14「纯 File + 多来源卷发现」重构为 `getWritableDevices()`，
   旧函数不复存在；
4. 媒体库首行按「上键」是**两段式**（内容区 → 工具条 → 顶部标签），设置页是一段式 —— 媒体库有工具条、
   设置页没有，层级本就不同。

### 3.26 死数据 / 不可清理文件 / 冗余代码专项清理（v1.29）

按「**前端清不掉的数据与文件**」与「**没被用到的文件与代码**」两条线逐类排查，结论与处置如下。

#### 3.26.1 上传临时文件永久残留（最严重，可吃 GB 级不可见空间）

| 项 | 说明 |
|---|---|
| 落点 | `Android/data/<包名>/files/upload_tmp/upload_<nanoTime>_<名>.tmp`（[TransHttpServer] 的 `ExternalTempFileManager`；刻意放外部私有目录，与 `/sdcard` 同卷才能 `renameTo` 零拷贝移入沙盒） |
| 正常清理 | NanoHTTPD 在每次连接收尾调 `TempFileManager.clear()` → `created.forEach { it.delete() }` |
| **泄漏窗口** | **进程被杀 / 断电 / 系统回收**时 `clear()` 根本没机会跑 → 半个上传永久留在盘上 |
| 为什么以前无人能清 | ① 该目录**不在媒体沙盒**（`TransView/`）内 —— 对账的 `.temp_unzip` 清理、媒体库扫描、`FileUtils.purgeDirectory`（带 `isInsideSandbox` 断言）全都碰不到它；② **Android 11+ 起 `Android/data/` 对文件管理器不可见**，用户连手动删都做不到；③ 每次 `TransHttpServer` 构造都会 new 一个新的 `ExternalTempFileManager`（`created` 列表为空），上一实例的残留不会被新实例清理 |

**修法**：`TransHttpServer.companion` 新增 `tempDirOf(context)` 与 `purgeOrphanUploadTemps(context, skipActiveWithinMs)`，三处调用：

| 调用点 | 保护窗口 | 依据 |
|---|---|---|
| `TransViewApp.onCreate` | **0** | 进程刚起 ⇒ 本进程内不可能有在途上传（服务器尚未启动），遗留的必是死文件 |
| `TransHttpServer.init`（每次启动服务器） | 10 分钟 | 避开「停服务器 → 立刻再启动」时上一实例工作线程仍在写的瞬间竞态 |
| 设置页「清理缓存」 | 10 分钟 | 避开「用户点清理时正好有上传在途」 |

窗口与 [SyncManager] 保护在途解压工作区的 `ACTIVE_WORKSPACE_GRACE_MS` 同值（同一类竞态）。

#### 3.26.2 不可达索引（Room `media_items` + 级联 `playback_history`）

| 项 | 说明 |
|---|---|
| 场景 | 曾把 U 盘设为存储位置并入库，之后该盘被**永久移除 / 换盘**（或用户改选内部存储后再也不插回） |
| 为什么以前清不掉 | 对账的「同步外部删除」走 [FileLocations.existsForPath]，判据是「读不到 ≠ 被删」——路径**不属于任何已知卷**时也恒返回 `true`；媒体库展示又按活动沙盒过滤 → 这些记录**既看不到、也永不清理** |
| 修法 | `MediaRepository` 新增 `orphanIndexPaths()`：判据换成「路径连**所属卷都不在** `storageState.volumes` 里」——比「卷在位但 `available=false`（首选盘已拔出）」**更严格**，后者插回即可复活，**不在清理范围**。设置页「存储与数据」新增「**清理不可达索引**」：点开先 `countOrphanIndexes()` 报条数（0 条直接 Toast 提示），确认弹框（`destructive = true`）写明风险后 `purgeOrphanIndexes()` 删除（经外键 CASCADE 连带播放历史） |
| **空集守卫**（v1.30 回归自查补） | `volumes` **可能为空**（`FileLocations.refresh()` 在 `appContext == null` 时退化成空列表并写回状态流）⇒ `roots = []` 会让 `none{}` **恒真**、**全表被判成孤儿**：用户点一次确认框就删掉整个索引，并经 CASCADE **清空全部播放历史**（索引可重扫回来，播放进度不可恢复）。故 `orphanIndexPaths()` 开头 `if (roots.isEmpty()) return emptyList()`，另新增 `storageReady()`（= 卷列表非空）；设置页在**弹确认框之前**先问它，未就绪只 Toast「存储状态尚未就绪，请稍后再试」、**不给破坏性确认框**。两道防线叠加后，退化路径下 `purgeOrphanIndexes()` 只会删 0 条 |

> 设计取舍：**没有**做成自动清理。因为「盘只是临时拔出」与「盘已永久移除」在系统层面无法区分，
> 自动删会直接违反本项目的核心不变式。故只提供**用户显式触发 + 二次确认**的出口。

#### 3.26.3 应用缓存（`cacheDir`）无清理入口

反编译确认 **Coil 2.7 未显式配置 `diskCache` 时确实会建默认磁盘缓存**：
`ImageLoader$Builder.build$lambda$34` → `coil.util.SingletonDiskCache.get(context)`，
目录名常量 `DIRECTORY = "image_cache"`（即 `cacheDir/image_cache`，LRU 有上限）。
它不在媒体沙盒里、「存储空间占用」统计不到、前端无入口 → 新增 `FileUtils.cacheSizeBytes(context)` /
`clearAppCache(context)`，设置页新增「**清理缓存**」行（显示当前占用，确认后清理并在同一批里顺带清上传残留）。

**v1.30 回归自查修正（重要）**：初版的 `clearAppCache` 是 `listFiles()?.forEach { it.deleteRecursively() }` ——
这会把 Coil 的 `image_cache` 连同 journal **直接删掉**。而 Coil 的 `DiskCache` 是**进程级单例**
（`SingletonDiskCache`），其 `DiskLruCache` 的 `initialized` 标志**只执行一次**、目录被外部删除后**不会重建**，
`RealDiskCache` 自身也**没有任何 try/catch** ⇒ 内存记账（`lruEntries`/`size`）与磁盘脱节、`journalWriter`
指向已 unlink 的 inode，之后写缓存会抛 `FileNotFoundException`。现改为**先走 Coil 自己的入口**：

```kotlin
// ① 先让 Coil 自己清：内部即 DiskLruCache.evictAll()，会同步清内存记账 + 删条目 + 写 journal
runCatching { Coil.imageLoader(context).diskCache?.clear() }
// ② 再扫 cacheDir 其余子项兜底，但**跳过** image_cache 目录本身（只清它的内容，不删目录）
if (child.name != COIL_DISK_CACHE_DIR) child.deleteRecursively()
```

`COIL_DISK_CACHE_DIR = "image_cache"` 仅用于**跳过**，**绝不**用于直接删除。
> 泛化教训：清理**第三方库**的缓存目录**必须**走它自己暴露的 `clear()`；直接删目录会打坏该库的内存状态
> （本项目的「清理缓存」入口只清 `cacheDir` 一处，但同样的坑适用于任何库自管目录）。

#### 3.26.4 上传记录表留下「看不见也删不掉」的尾巴

`UploadRecordRepository.MAX_RECORDS = 500`，而 UI 读取上限（`UploadRecordDao.observeRecent` 默认 `limit = 200`）
只有 200 —— 第 201~500 条**查不到、也就删不掉单条**（只能「清空全部」）。原先注释称「多留余量供翻查」，
但**根本没有翻查入口**。修法：上限对齐为 200，并在 DAO 注释里写明两者**必须同值**的约束。

#### 3.26.5 批量删索引撞 SQLite 宿主参数上限

`MediaItemDao.deleteByPaths(paths)` 的 `filePath IN (:paths)` 会被 Room 展开成**等量**的 `?` 绑定参数；
条目超过 SQLite 宿主参数上限（旧版为 999）时整条语句直接抛异常。调用方是对账的「同步外部删除」，
异常被 `sync()` 的兜底 `catch` 吞成「对账过程异常，已兜底结束」——表现为
**一次删掉上千个文件（如用电脑清空了 Movies 目录）后，僵尸卡片永远清不掉且毫无提示**。
修法：`MediaRepository.deleteByPaths` 内部按 `SQL_BIND_CHUNK = 400` 分批（`purgeOrphanIndexes` 同理）。

#### 3.26.6 冗余文件与冗余代码（已清除）

| 对象 | 判定依据 | 处置 |
|---|---|---|
| `ui/common/VideoMeta.kt`（整个文件） | `object VideoMeta` / `getDuration()` 全仓库零引用；时长提取实际走 `FileUtils.extractVideoDuration`（对账与上传入库各一处） | **删除** |
| `@color/ic_launcher_tint` | 全仓库零引用（图标是 mipmap webp + 自绘背景 drawable） | 删除 |
| 8 处未使用 import | `IStorage.mediaUri`、`MainScreen.height` / `onKeyEvent` / `Graphics.Color`、`FileTypeIcon.Color`、`TvComponents.View`、`Theme.Color` | 删除（**保留** `getValue` / `setValue` —— 那是 `by remember` 委托的 operator import，删了会编译失败） |
| 工程根目录 4 项 | `apk_info.tmp`（APK 体积排查残留）、`tv_scroll_mem.py`（一次性写记忆的脚本）、`.tmp_test/`（空目录）、`.trash/`（`gradle updateDaemonJvm` 的废弃残留，且 `gradle/gradle-daemon-jvm.properties` 并不存在 ⇒ 无任何作用） | 删除；`.gitignore` 补 `*.tmp`、`.tmp_test/`、`.trash/` |

#### 3.26.7 经核实「无需清 / 属设计取舍」

- **`.temp_unzip/`**：解压工作区，`ZipExtractor` 每次 `finally { purge() }` 自清，断电残留由每次对账
  `purgeDirectory(skipActiveWithinMs = 10min)` 兜底 —— 已有完整回收链，**不需要**额外入口；
- **`app_log/`**：按天分目录、单文件 2MB 切割、`AppLogger.start()` 清理 7 天前的日期目录；因位于
  `Downloads/` 下会作为「其他」分类入库，**用户可在媒体库「其他」页直接打开或删除**；
- **`Theme.TransView.Fullscreen` 用 `@android:color/black` 而非 `app_bg`**：播放器 / 图片查看器要纯黑底，
  刻意如此，不是漏改；
- **`backup_rules.xml` / `data_extraction_rules.xml`**：v1.29 时判定为「空模板，删掉无收益」而保留。
  **v1.30 重新评估后改为「保留文件、但补上排除规则」** —— 空模板等于**全量备份**，会把 Room 库一起带走，
  而库里的 `filePath` 是卷根绝对路径、跨设备恢复后全部不可达，属于自找麻烦。详见 §3.27.5。
- **`FileUtils.retrieverFor(context, path)` 的 `context` 形参未被使用**：仅 1 处调用、语义上无害，
  改动收益低于噪声，保留（记为可选项）。

### 3.27 权限与适配兼容性审查（v1.30）

审查范围：`AndroidManifest.xml` 全量权限 + **合并后的最终清单**（确认库注入项）+ 全工程权限调用点；
适配侧正则扫描 ≥3 位数的 `.dp/.sp` 与 `1920/1080/1280/720/2160/3840/1440/2560` 等绝对分辨率数字，
并检查 `displayMetrics` / `getRealMetrics` / `LocalDensity` 覆盖 / 资源限定符目录。

#### 3.27.1 权限面盘点（本就干净）

危险 / 特殊权限只有 3 个，全部与核心功能直接相关：

| 权限 | 级别 | 判定 |
| --- | --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | 特殊（所有文件访问） | **必需**：用 `java.io.File` 直读 U 盘卷根。工程硬约束「禁用 SAF / DocumentFile」下无替代方案。⚠️ Google Play 需政策申报（侧载不受限） |
| `WRITE/READ_EXTERNAL_STORAGE` | 危险，`maxSdkVersion=29` | 必需：API 21~29 的传统路径。运行时只申请 WRITE，READ 由 STORAGE 权限组连带授予 |
| `POST_NOTIFICATIONS` | 危险（API 33+） | 必需，但**此前从未申请** → 见 §3.27.2 |

普通权限：`INTERNET`（HTTP 服务器 + Coil）、`RECEIVE_BOOT_COMPLETED`（设置页可开关）、
`FOREGROUND_SERVICE` / `_DATA_SYNC` / `_SPECIAL_USE`。**未申请**相机、定位、通讯录、电话、录音、
蓝牙、健康数据、`AD_ID` —— 一个都没有。

#### 3.27.2 通知权限：声明了却从未申请（功能缺口）

前台服务 `ServerService` 的常驻通知是用户了解「服务器运行中 / 已休眠 / 已暂停」的**唯一**途径
（`updateNotification` 随 `ServerBus` 状态联动）。清单早在 v1.x 就声明了 `POST_NOTIFICATIONS`，
但全工程 grep 只命中清单与文档 —— **没有任何运行时申请**。Android 13+ 上未授权时系统**静默丢弃**通知，
用户既看不到服务器状态，也不知道「15 分钟无上传自动休眠」这类提示。

实现要点（`MainActivity.AppRoot`）：

- **申请时机放在存储授权之后**：存储是硬门槛（`PermissionScreen` 过不了连主界面都看不到），
  通知是可选增强，不该和硬门槛抢用户看到的第一个授权框；
- **只主动弹一次**：`SettingsStore.notifPermissionAsked` 标记。系统对同一权限本就只展示有限次授权框，
  反复申请只会退化成「点了没反应」的无效操作；
- **回调刻意留空**：拒绝只是通知不可见，**服务器照常运行**，不阻断任何流程、不改任何 state；
- `NotificationPermission.isGranted` 在 API < 33 恒返回 true（那些版本没有该运行时权限）。

#### 3.27.3 权限冗余：删掉 `ACCESS_WIFI_STATE` 与重复声明

- **`ACCESS_WIFI_STATE` → 删除**。唯一使用者 `NetUtils.wifiManager()` 是**死代码**（全工程唯一引用就是
  它自己的定义，实际取 IP 走 `NetUtils.getLocalIpAddress()` 的 `NetworkInterface` 枚举，不需要任何权限）。
  且该权限只服务 `getConnectionInfo()`，而新系统上无定位权限时 SSID 恒为空占位值，本就拿不到有用信息。
  合并后清单确认该权限**彻底消失**（无任何库声明它）。
- **`ACCESS_NETWORK_STATE` → 删除本工程声明，但权限仍在**。本工程代码零使用（无 `ConnectivityManager`），
  但 `media3-common` / `media3-exoplayer` 各自声明了它（manifest 合并报告可见），且 **Coil 的
  `RealNetworkObserver` 正依赖它**（靠 media3 顺带满足）。所以删本工程那行只是「清单更诚实」，
  APK 里的权限集合不变 —— 合并后该条目的来源从「本工程」变为 media3。
- **FGS 三权限不动**：`FOREGROUND_SERVICE` / `_DATA_SYNC` / `_SPECIAL_USE` 与
  `ServerService.startForegroundCompat` 的版本分支（<29 无类型 / 29~33 `dataSync` / 34+ `specialUse`）
  严格对应，`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 也已声明。**这套组合是刻意设计，不要简化。**

#### 3.27.4 无硬编码分辨率（结论：确实是干净的）

扫描结果**全是误报**：命中的是缓冲区 `64*1024` / `128*1024`、端口范围 `1024..65535`、二维码默认边长
`480`、以及共享常量 `COMPACT_SCREEN_HEIGHT_DP = 480`。全工程**没有**将 `1920/1080/720/3840` 等绝对值
用于布局，也**没有** `displayMetrics` / `getRealMetrics` 取真实像素判布局的写法（那才是真正的适配杀手）。
尺寸有三个层次，都是密度无关的：

- 全部内联在 Compose 的 `.dp` / `.sp`（无 `dimens.xml`、无 `values-*` 限定符目录）；
- 矮屏整体缩放走 `CompactContentDensity` 覆盖 `LocalDensity`；
- 「按宽度决定个数」走 `rememberContentWidthDp()` + `effectiveColumns`，缩略图走 `aspectRatio(16f/9f)`，
  弹框走 `BoxWithConstraints` 量可用高度 + 列表滚动兜底。

#### 3.27.5 【高危】Android 16 在大屏忽略 `screenOrientation`

官方行为变更原文：*Android 16 (API level 36) ignores screen orientation, aspect ratio, and app
resizability restrictions* —— **判据是 `smallestWidth ≥ 600dp`**，且 **opt-out 将在 API 37 被移除**。
官方同时给出了**例外清单**，明确排除 *Displays smaller than sw600dp*、游戏（`android:appCategory`）
与用户在系统里主动选择应用默认行为者。本项目三个 Activity 全部写死
`android:screenOrientation="landscape"`。

影响分级（关键：**不是所有设备都受影响**）：

| 设备 | 最小宽度 smallestWidth | 是否受影响 |
| --- | --- | --- |
| 手机（含本项目的「矮屏」形态） | 360~450dp | **不受影响** —— sw < 600dp，落在官方例外清单内，且矮屏机制照旧有效 |
| 电视 / 盒子（1080p，标准配置 960×540dp） | **540dp** | **不受影响** —— sw < 600dp，同属官方例外清单。且电视天然不旋转，无论命中与否都无感 |
| 平板 / 大折叠内屏 / 桌面窗口 | ≥ 600dp | **真的会变竖屏 / 任意尺寸窗口** |

> ⚠️ **易错点（本文档 v1.30 初版写错过，勿再照抄）**：判据是 **smallestWidth**（竖屏方向的最短边），
> **不是「屏幕宽度」**。1080p Android TV 的标准配置是 960×**540**dp（密度 2.0）—— **960dp 是它的宽度，
> 540dp 才是它的最小宽度**。初版把 960 当作「电视的最小宽度」并据此判它「命中」，是错的；
> 结论「电视无感」虽然仍成立，但理由必须换成「sw 不够」。

具体后果（`MainScreen` 顶部导航栏）：该行是**不换行、不滚动**的 `Row`（品牌标题 + **4 个媒体标签** +
`Spacer(weight)` + 设置标签 + 状态徽标）。按非紧凑态估算：行内边距 80 + 品牌标题约 160 + 标题后间距 28
+ **4** 个标签约 368（每个 `30.dp`×2 padding + 2 个汉字 @ 16sp）+ 标签间距 56（**循环内**每标签后各一个，
末个也算）+ 设置标签约 92 + 间距 20 + 状态徽标约 150 ≈ **950dp**。
平板竖屏可用宽约 800dp ⇒ **右侧「设置」标签与状态徽标被挤出屏幕**。

> 为什么此前没人发现：收窄态同一行只要约 **400dp**，手机横屏（640dp+）恰好装得下；
> 而 lock landscape 让这一行在电视上永远是宽屏，950dp 的需求从未被触碰。

修法**双管齐下**（缺一不可）：

1. **清单 opt-out（安全网，有保质期）**：`<application><property
   android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY" android:value="true"/>`
   让系统在 API 36 上把应用放回兼容模式、继续尊重旧的横竖屏 / 尺寸限制。
   **不能只靠它**：① API 37 移除该 opt-out；② 官方明确「**桌面窗口模式下即使 opt-out，屏幕方向限制
   也会被覆盖**」，只剩可调整大小性被尊重；③ 官方在 Jetpack Compose 适配指南里另有一句更要紧的表述 ——
   *If your app targets Android 16 (API level 36) or higher, this property doesn't lock the display
   orientation or prevent screen rotation on large displays*，即对**本工程（targetSdk 36）**而言
   它**未必真能锁住大屏方向**。所以下面的代码侧自适应是**必需项**，不是加分项。
2. **代码侧真正具备宽度自适应（治本）**：新增常量与判据（`ui/common/CompactUi.kt`）——
   `TOP_BAR_FULL_WIDTH_DP = 960` 与 `rememberTopBarTight()`：**高度 < 480dp（矮屏）或宽度 < 960dp**
   任一成立即收窄顶部栏。收窄内容 = 隐藏品牌标题 + 标签改紧凑字号与内边距 + 徽标只留指示圆点
   → 整行降到约 **400dp**。
   **阈值为何取 960**：960dp 是 1080p Android TV 的**常见宽度**（标准配置 960×540dp），取「严格小于」
   保证**该类电视不收窄、逐像素不变**（本项目硬要求）。**但这条判据的余量很薄**：估算需求 ≈950dp 与
   960 只差约 10dp，字体度量与 `letterSpacing` 的累积误差都可能吃掉它。
3. **`MainActivity` 补 `configChanges`**：`PlayerActivity` / `ImageViewerActivity` 早已声明
   `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode`，主界面漏了 ——
   方向锁失效后旋转 / 改窗口尺寸会**重建 Activity**。`selected` 用了 `rememberSaveable` 所以能活，
   但 `showSettings` 等普通 `remember` 会被重置（表现为「旋转后莫名退出设置页」）。

**判据分层（重要，别混）**：顶部栏用 `rememberTopBarTight()`（高度 **或** 宽度），
内容区缩放用 `CompactContentDensity`（只看高度）。两者刻意独立 —— 平板竖屏（高约 1000dp）时
内容区仍按电视尺度排版，但顶部栏必须收窄才不会溢出。

> **已知待验（v1.30 唯一未闭环项）**：`TOP_BAR_FULL_WIDTH_DP = 960` 与估算需求（≈950dp）余量仅约
> 10dp，**尚未在真机 1080p 电视上验证右端徽标是否被裁**。当前策略是**保持 960 不动、真机实测再定**
> —— 保持现状对现有电视零风险（不收窄 ⇒ 视觉逐像素不变）。若实测确有裁切，二选一：
> ① 上调阈值（代价：1080p 电视也进入收窄态，改变现有电视视觉）；
> ② 改为**不依赖阈值的自适应**（品牌标题可隐藏、徽标文字可省略，由剩余空间自行压缩）。

#### 3.27.6 备份规则改为排除数据库

`backup_rules.xml` / `data_extraction_rules.xml` 原为 Studio 空模板 ⇒ 等价于**全量备份**。
问题在于库里的 `media_items.filePath` / `parentFolder` 是**卷根绝对路径**
（如 `/storage/0000-0000/TransView/Movies/a.mp4`），只在「当初那台设备 + 那张盘」上成立；
跨设备恢复后全部不可达，媒体库会显示一批点不开的卡片，还得靠设置页「清理不可达索引」善后。
`playback_history` 以 `filePath` 为外键同理，`upload_records` 纯粹是过程日志。

现排除 `database` / `file` / `external` 三个域，**只保留 `sharedpref`**（用户设置）——
媒体库本就是磁盘索引，启动时由 `SyncManager` 重新对账补齐，排除**没有副作用**。
`external` 域必须排除：上传临时目录 `upload_tmp/` 就落在 `getExternalFilesDir` 下，中断的上传可留下
GB 级残留（见 §3.26.1）。`cache` 域（含 Coil 的 `image_cache`）系统本就不备份，无需声明。

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
- [x] ~~上传页手机横屏适配（矮屏紧凑模式：二维码被压没、地址被截断）~~（v1.18 已完成，见 §3.15）
- [x] ~~手机横屏下内容区字体/间距偏大（内容区整体等比缩放 + 媒体库列数收敛）~~（v1.19 已完成，见 §3.20）
- [x] ~~矮屏（手机横屏）设置页内容被裁切且无法滑动（左栏看不到「关于」、右栏只能看到「存储空间占用」）~~（v1.20 已完成，见 §3.21）
- [x] ~~设置页点空白 / 划动右栏后焦点莫名跳到「服务器与网络」（触摸票据兜底硬编码首个分组 + `SettingRow` 漏拦截分组末行 ↓）~~（v1.21 已完成，见 §3.22）
- [x] ~~矮屏（手机横屏）设置页选项弹框（如设备名称）超出屏幕、看不到也够不到~~（v1.22 已完成：选项列表 `verticalScroll` + 高度上限）
- [x] ~~TV 端上传进度一直停在 0%、成功后才变 100%~~（v1.23：NanoHTTPD 2.3.1 把文件 part 按文件名直接写盘、不经过 `TempFile.open()` 的流，原计数恒为 0；改为在**套接字输入流**层用 `CountingInputStream` 计数。v1.24 补全：计数器原在 `createClientHandler`（accept 线程）里绑 ThreadLocal，工作线程读不到，改为由计数流在 read 时绑定当前线程，见 §3.23）
- [ ] 断点续传（需求 4.4 P2）
- [x] ~~上传中断网时手机端支持「取消/重试」按钮~~（v1.5 已完成，见 §3.13）
- [x] ~~U 盘上「其他」文件无法打开（`FileProvider` 路径未覆盖可移动卷）~~（v1.15 已完成，见 §3.17）
- [x] ~~对账扫描改为「目录即分类 + 格式严格过滤」并做扫描性能/内存优化~~（v1.16 已完成，见 §3.3.7）
- [x] ~~「导出存储诊断日志」重构为「App 运行日志本地化」（App 调试日志开关，默认关）~~（v1.17 已完成，见 §3.19）
- [x] ~~「清不掉的数据 / 死文件 / 冗余代码」专项清理~~（v1.29 已完成：上传临时残留三处自动清扫、设置页新增「清理缓存」「清理不可达索引」、上传记录上限与 UI 对齐、`deleteByPaths` 分批、删除死文件与无用资源/import，见 §3.26）
- [ ] （可选）`FileUtils.retrieverFor(context, path)` 的 `context` 形参未被使用，可顺手去掉（收益低，见 §3.26.7）
- [ ] （可选）Room `fallbackToDestructiveMigration()`：潜在隐患非现网缺陷，改用显式 Migration 或 `fallbackToDestructiveMigrationOnDowngrade()` 更稳
