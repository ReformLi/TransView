package com.hpu.transview.ui.library

import android.content.Intent
import android.os.SystemClock
import android.view.ViewConfiguration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.hpu.transview.data.MediaRepository
import com.hpu.transview.data.PlaybackRepository
import com.hpu.transview.data.db.MediaItemEntity
import com.hpu.transview.data.sync.SyncManager
import com.hpu.transview.model.Category
import com.hpu.transview.model.FileEntry
import com.hpu.transview.model.SortOrder
import com.hpu.transview.model.UploadState
import com.hpu.transview.server.UploadBus
import com.hpu.transview.ui.common.TvButton
import com.hpu.transview.ui.common.TypeBadge
import com.hpu.transview.ui.common.requestFocusNextFrame
import com.hpu.transview.ui.common.tvFocus
import com.hpu.transview.ui.image.ImageViewerActivity
import com.hpu.transview.ui.player.PlayerActivity
import com.hpu.transview.ui.theme.OnDarkDim
import com.hpu.transview.util.FileLocations
import com.hpu.transview.util.FileUtils
import com.hpu.transview.util.isImageFile
import com.hpu.transview.util.isVideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 网格列数（电视端多列，严禁单列列表） */
private const val GRID_COLUMNS = 5

/** 焦点还原「返回上级」卡片的哨兵路径 */
private const val FOCUS_UP = "__up__"
/** 进入子目录后聚焦第一个条目（用户反馈：先闪 UpCard 再跳会有可见的两段跳，直接一次落点） */
private const val FOCUS_FIRST = "__first__"

/**
 * 长文件名跑马灯（仅在卡片获得焦点时挂上，见 MediaCard）。
 *
 * `basicMarquee` 会先给子项一个无界宽度约束来测出文本的**真实宽度**，再与自身视口比较，
 * 因此能正确判断「是否溢出」——文本没超宽时它什么也不做，超宽才循环滚动。
 * 两个延迟都调小于默认值（默认各 1200ms），让焦点一到就尽快开始滚。
 */
private val titleMarquee: Modifier = Modifier.basicMarquee(
    initialDelayMillis = 400,
    repeatDelayMillis = 900
)

/**
 * 媒体库页（多列网格卡片）：视频 / 图片 / 其他三个分类共用。
 *
 * - 顶部工具条：路径面包屑（视频 > 甄嬛传）+ 排序 + 手动刷新（对账）
 * - 主体：LazyVerticalGrid 多列卡片。文件夹=图标+名称+文件数；视频=缩略图+名称+播放进度条；
 *   图片=缩略图+名称；其他=通用图标+名称。
 * - 焦点：卡片聚焦放大 1.1 倍 + 高亮边框；从文件夹返回上级时焦点还原到刚才进入的文件夹卡片。
 * - 上下键层级：网格**第一行**按上键 → 本页工具条（排序 / 刷新）；工具条再按上键 → 顶部导航栏的
 *   当前分类标签。中间行按上键仍是网格内上行（交回 Compose 默认焦点搜索）。
 *   反向：标签按下键 → 直接回网格第一行（`focusGridTicket`），**不经过工具条**——
 *   工具条按钮在空间上离标签更近，交给方向搜索会把它吸过去（用户实测「其他」页 ↓ 落在「排序」）。
 *   工具条按下键回网格由「网格顶部留白」保证（详见 LazyVerticalGrid 的 contentPadding）。
 * - 交互：确定键直达动作；菜单键弹出「进入/播放、删除、取消」；删除走二次确认，
 *   先物理删除再删 Room 索引，成功后焦点自动移到下一个卡片。
 * - **焦点安全港**：切换目录 / 返回上级 / 删除前，先 `parkFocusSafe()` 把焦点停到工具条
 *   「排序」上（停靠期间抑制其聚焦高亮，避免过渡闪烁；touch 点按路径无焦点可保护，直接跳过）；
 *   否则承载焦点的卡片被移出组合时，Compose 会把焦点回退到第一个可聚焦元素（顶部「上传」标签），
 *   标签的「聚焦即选中」会把页面直接切走。
 * - **进入子目录焦点直接落第一个条目**（`FOCUS_FIRST`，用户反馈）：不再落「返回上级」——
 *   UpCard 抢焦点会形成「第一个文件闪一下再跳回上级」的可见两段跳；直接聚焦第一个条目
 *   只有一次落点。空目录网格里只剩 UpCard 时改聚焦它。
 */
@Composable
fun LibraryScreen(
    category: Category,
    onFocusTabs: () -> Unit = {},
    focusGridTicket: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember(category) { FileLocations.root(category) }

    var currentDir by remember(category) { mutableStateOf(root) }
    var sortOrder by rememberSaveable(category.name) { mutableStateOf(SortOrder.NAME_ASC) }
    var showSortDialog by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    var actionEntry by remember { mutableStateOf<FileEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<FileEntry?>(null) }

    val gridState = rememberLazyGridState()
    // 本页工具条（排序 / 刷新）的焦点入口：网格第一行按「上键」落到这里，
    // 再按一次上键才由工具条把焦点交给顶部导航栏的当前分类标签。
    val toolbarFocus = remember { FocusRequester() }
    // 「焦点过渡停靠中」标记：目录切换 / 返回上级 / 删除时焦点先停靠在工具条「排序」上
    // （1~2 帧后由目标卡片抢回）。停靠期间抑制排序按钮的聚焦高亮，否则用户会看到
    // 「排序闪一下再跳到目标卡片」的感官跳动（用户实测反馈）。
    var focusParking by remember { mutableStateOf(false) }
    // 网格（含卡片）当前是否持有焦点，见网格 modifier 注释
    var gridHasFocus by remember { mutableStateOf(false) }
    // 待聚焦目标路径（文件绝对路径 或 FOCUS_UP）；聚焦完成后置空
    var pendingFocusPath by remember(category) { mutableStateOf<String?>(null) }
    // 打开图片查看器 / 视频播放器：返回时带回「最后浏览/播放的文件路径」，
    // 让列表焦点定位到它（而不是打开时的那张）——图片查看器可切图、视频会连播下一集
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.getStringExtra(ImageViewerActivity.EXTRA_RESULT_PATH)
            ?.let { pendingFocusPath = it }
    }
    // 已消费的「标签按下键」票据。初始化为当前值：切分类标签进来时会重建本页组合，
    // 若从 0 开始会让 LaunchedEffect 立刻跑一次，把焦点从标签抢进网格，
    // 用户就没法继续按 → 切到下一个标签了。
    var consumedFocusTicket by remember(category) { mutableIntStateOf(focusGridTicket) }

    val atRoot = currentDir == root

    // ——— 文件夹列表来自文件系统（DB 不索引文件夹） ———
    var dirRefreshKey by remember { mutableStateOf(0) }
    var dirEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    // 目录列表的「归属戳」：记录 dirEntries 是为哪个目录加载的。切目录后的第一帧里
    // dirEntries 还是旧目录的数据，据此判断加载是否完成（避免 FOCUS_FIRST 误判空目录）。
    var dirEntriesStamp by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(currentDir, dirRefreshKey) {
        dirEntries = withContext(Dispatchers.IO) {
            currentDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.map { FileEntry(it, it.name, true, 0L, it.lastModified()) }
                ?: emptyList()
        }
        dirEntriesStamp = currentDir
    }

    // ——— 文件列表来自数据库（Room Flow 实时刷新） ———
    val mediaRepo = remember { MediaRepository(context) }
    val dbItems by mediaRepo.observeByCategory(category).collectAsState(initial = null)
    val fileEntries = remember(dbItems, currentDir) {
        val items = dbItems ?: return@remember emptyList()
        items.filter { it.parentFolder == currentDir.absolutePath }
            .map { it.toFileEntry() }
    }

    // ——— 播放进度（视频卡片底部细进度条） ———
    val playbackRepo = remember { PlaybackRepository(context) }
    val progressList by playbackRepo.observeAllProgress().collectAsState(initial = emptyList())
    val progressMap = remember(progressList) {
        progressList.associate { p ->
            p.filePath to if (p.duration > 0) p.position.toFloat() / p.duration else 0f
        }
    }

    // ——— 上传完成后刷新文件夹列表（新文件已实时入 DB Flow，文件夹需手动触发） ———
    val uploadRecords by UploadBus.records.collectAsState()
    var lastSyncedUploadId by remember {
        mutableStateOf(UploadBus.records.value.firstOrNull { it.state == UploadState.DONE }?.id ?: 0L)
    }
    LaunchedEffect(uploadRecords) {
        val latestDone = uploadRecords.firstOrNull { it.state == UploadState.DONE }?.id ?: 0L
        if (latestDone > lastSyncedUploadId) {
            lastSyncedUploadId = latestDone
            dirRefreshKey++
        }
    }

    // ——— 排序：文件夹在前，文件在后，组内各自排序 ———
    val cmp: Comparator<FileEntry> = when (sortOrder) {
        SortOrder.NAME_ASC -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortOrder.NAME_DESC -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortOrder.TIME_DESC -> compareByDescending { it.lastModified }
        SortOrder.TIME_ASC -> compareBy { it.lastModified }
    }
    // dirEntries 是异步加载的：切目录后的第一帧里它还是旧目录的子文件夹列表，
    // 必须按 parent 同步过滤掉，否则网格会先闪一帧旧目录内容，且 FOCUS_FIRST
    // 会错误命中旧卡片（该卡片下一帧即被移出组合，焦点会失控回退）。
    val entries = remember(dirEntries, fileEntries, sortOrder, currentDir) {
        dirEntries.filter { it.file.parentFile == currentDir }.sortedWith(cmp) +
            fileEntries.sortedWith(cmp)
    }

    // ——— 文件夹内文件总数（含子层级） ———
    val countInFolder: (String) -> Int = { folderPath ->
        dbItems?.count { it.parentFolder == folderPath || it.parentFolder.startsWith("$folderPath/") } ?: 0
    }

    // ——— 焦点定位：先滚动到目标项，再由卡片自身请求焦点 ———
    // 键含 dirEntriesStamp：entries 结构相等时 LaunchedEffect 不会重跑（List.equals 是
    // 结构比较），目录列表异步加载完成必须靠 stamp 变化触发重跑，否则 FOCUS_FIRST 会卡住。
    LaunchedEffect(pendingFocusPath, entries, dirEntriesStamp) {
        val target = pendingFocusPath ?: return@LaunchedEffect
        if (target == FOCUS_UP) {
            if (!atRoot) runCatching { gridState.scrollToItem(0) }
            return@LaunchedEffect
        }
        if (target == FOCUS_FIRST) {
            // 目录列表还没加载完（异步 IO）：等 stamp 变化触发本 effect 重跑再判断
            if (dirEntriesStamp != currentDir) return@LaunchedEffect
            // 空目录（无子文件夹也无文件）网格里只剩「返回上级」，改聚焦它
            if (entries.isEmpty()) {
                pendingFocusPath = FOCUS_UP
            } else {
                // 子目录里 UpCard 占网格位 0，第一个条目在位 1
                runCatching { gridState.scrollToItem(if (atRoot) 0 else 1) }
            }
            return@LaunchedEffect
        }
        val index = entries.indexOfFirst { it.file.absolutePath == target }
        if (index >= 0) {
            val upOffset = if (atRoot) 0 else 1
            runCatching { gridState.scrollToItem(index + upOffset) }
        }
    }

    // ——— 内容切变时的「焦点安全港」———
    // 承载焦点的卡片会随「切换目录 / 返回上级 / 删除」被移出组合，Compose 此时无法把焦点
    // 交还给它，会回退到整棵树里第一个可聚焦元素 —— 正是顶部导航栏的「上传」标签；
    // 而标签是「聚焦即选中」，页面会被立刻切走（现象：在图片页打开文件夹，直接跳回上传页）。
    // 过渡先把焦点停到工具条「排序」上，再由目标卡片在下一帧把焦点抢回网格；
    // 即使抢回失败，焦点也仍留在本页。停靠期间用 focusParking 抑制「排序」的聚焦高亮
    // （否则过渡帧里排序会高亮一闪再跳走，用户实测感官跳动）。
    val parkFocusSafe: () -> Unit = {
        // touch 点按路径网格没有焦点，无需停靠
        if (gridHasFocus) {
            focusParking = true
            runCatching { toolbarFocus.requestFocus() }
        }
    }
    // 空目录兜底：网格无卡片可聚焦，停到真正的工具条上（页面上唯一可交互处，
    // 此时是真实聚焦而非过渡停靠，聚焦高亮正常显示）
    val parkFocusOnToolbarFallback: () -> Unit = { runCatching { toolbarFocus.requestFocus() } }
    // 停靠超时兜底：目标卡片正常会在 1~2 帧内抢回焦点并清除标记；
    // 若抢回失败，800ms 后恢复排序按钮的聚焦高亮，避免按钮永远不亮
    LaunchedEffect(focusParking) {
        if (focusParking) {
            kotlinx.coroutines.delay(800)
            focusParking = false
        }
    }

    // ——— 顶部标签按「下键」→ 直接落到网格第一行 ———
    // 用户明确要求：本页工具条只能由「网格第一行按 ↑」上来聚焦，
    // 从标签按 ↓ 不该直接落到「排序」上。这件事交给 Compose 方向搜索做不到——
    // 工具条按钮在空间上离标签更近，会被优先命中（实测「其他」页标签 ↓ 落在「排序」）。
    // 这里取「当前视口的第一行首项」而不是列表首项，避免网格已滚动时去聚焦视口外的卡片。
    LaunchedEffect(focusGridTicket) {
        if (focusGridTicket == consumedFocusTicket) return@LaunchedEffect
        consumedFocusTicket = focusGridTicket
        val offset = if (atRoot) 0 else 1
        val firstVisible = gridState.firstVisibleItemIndex
        val target = if (!atRoot && firstVisible == 0) FOCUS_UP
        else entries.getOrNull(firstVisible - offset)?.file?.absolutePath
        // 空目录时网格里没有卡片，退而聚焦本页工具条（页面上唯一可聚焦处）
        if (target != null) pendingFocusPath = target else parkFocusOnToolbarFallback()
    }

    // ——— 进入下一级 / 返回上一级 ———
    val openEntry: (FileEntry) -> Unit = { entry ->
        when {
            entry.isDirectory -> {
                // 先捕获：parkFocusSafe 会同步移动焦点，网格的 onFocusChanged 随即把
                // gridHasFocus 写成 false，之后再读就丢失了（实测踩过）
                val hadFocus = gridHasFocus
                parkFocusSafe()
                currentDir = entry.file
                // 仅遥控器路径做焦点还原；touch 点按路径焦点本来就空，无需还原。
                // 进入子目录后焦点直接落第一个条目（FOCUS_FIRST），不再落「返回上级」：
                // 用户反馈 UpCard 抢焦点会形成「第一个文件闪一下再跳回上级」的可见两段跳。
                if (hadFocus) pendingFocusPath = FOCUS_FIRST
            }
            entry.file.isVideoFile() -> mediaLauncher.launch(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_PATH, entry.file.absolutePath)
            )
            entry.file.isImageFile() -> mediaLauncher.launch(
                Intent(context, ImageViewerActivity::class.java)
                    .putExtra(ImageViewerActivity.EXTRA_PATH, entry.file.absolutePath)
            )
            else -> FileUtils.openExternal(context, entry.file)
        }
    }

    val goUp: () -> Unit = {
        if (!atRoot) {
            // 先捕获（原因同 openEntry：park 会同步移动焦点使 gridHasFocus 变 false）
            val hadFocus = gridHasFocus
            parkFocusSafe()
            val leaving = currentDir
            currentDir = leaving.parentFile ?: root
            // 焦点还原到刚才进入（即将离开）的那个文件夹卡片；touch 路径焦点为空，跳过
            if (hadFocus) pendingFocusPath = leaving.absolutePath
        }
    }

    // ——— 删除：先物理删除，成功后才删 Room 索引，焦点移到下一个卡片 ———
    val performDelete: (FileEntry) -> Unit = { entry ->
        val visible = entries
        val index = visible.indexOfFirst { it.file.absolutePath == entry.file.absolutePath }
        val nextPath = when {
            index < 0 -> null
            index + 1 < visible.size -> visible[index + 1].file.absolutePath
            index - 1 >= 0 -> visible[index - 1].file.absolutePath
            !atRoot -> FOCUS_UP
            else -> null
        }
        pendingDelete = null
        scope.launch {
            val physicalOk = withContext(Dispatchers.IO) {
                FileUtils.deletePhysicalFile(entry.file)
            }
            if (physicalOk) {
                // 先离开这张即将消失的卡片，避免焦点回退到顶部「上传」标签把页面切走。
                // hadFocus 先捕获（原因同 openEntry：park 会同步移动焦点使 gridHasFocus 变 false）
                val hadFocus = gridHasFocus
                parkFocusSafe()
                mediaRepo.deleteByPath(entry.file.absolutePath)
                // touch 路径跳过焦点还原（见 openEntry 注释）
                if (hadFocus) pendingFocusPath = nextPath
                dirRefreshKey++ // 父目录可能被连带清空
                Toast.makeText(context, "已删除「${entry.name}」", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "删除失败，文件可能被占用", Toast.LENGTH_LONG).show()
            }
        }
    }

    val refreshLibrary: () -> Unit = {
        scope.launch {
            syncing = true
            runCatching {
                val result = SyncManager.getInstance(context).sync()
                dirRefreshKey++
                if (result.changed) {
                    Toast.makeText(
                        context,
                        "对账完成：新增 ${result.inserted}，更新 ${result.updated}，" +
                            "清理失效 ${result.deletedMissing}，空文件夹 ${result.removedEmptyFolders}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(context, "媒体库已是最新", Toast.LENGTH_SHORT).show()
                }
            }
            syncing = false
        }
    }

    // ——— 顶部工具条 + 网格 ———
    // 网格项总数（子目录里前面多一张「返回上级」卡片）：用于判断「行尾」右侧是否还有卡片
    val totalGridItems = entries.size + if (atRoot) 0 else 1
    // 网格内按返回键先「返回上一级」；到根目录时不启用，交由 MainScreen 回到顶部导航栏。
    // 用 BackHandler 而非 onPreviewKeyEvent：后者只在焦点路径上才收到事件，焦点为空时会漏掉返回键。
    BackHandler(enabled = !atRoot) { goUp() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
    ) {
        LibraryTopBar(
            crumb = buildCrumb(category, root, currentDir),
            sortOrder = sortOrder,
            syncing = syncing,
            // 工具条上按「上键」→ 顶部导航栏的当前分类标签（视频页 → 「视频」标签）
            upToTabsModifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                    onFocusTabs()
                    true
                } else false
            },
            sortFocusModifier = Modifier
                .focusRequester(toolbarFocus)
                // 工具条最左端按「左」：吃掉按键让焦点留在原地（原因同网格左右边界）
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) true
                    else false
                },
            // 工具条最右端按「右」：同上
            refreshEdgeModifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) true
                else false
            },
            onSortClick = { showSortDialog = true },
            onRefresh = refreshLibrary,
            suppressSortFocusVisual = focusParking
        )

        when {
            dbItems == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            entries.isEmpty() && atRoot -> EmptyHint()
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                state = gridState,
                // hasFocus = 网格自身或其中任一卡片持有焦点。目录切换 / 返回上级 /
                // 删除前据此决定是否需要「焦点安全港」：遥控器（键盘焦点）路径必须先停靠，
                // touch 点按路径焦点本来就是空的（没有可被「移走」的焦点），停靠反而会让
                // 随后的目标卡片 requestFocus 静默失效、焦点卡死在隐形锚点上（实测踩过）。
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onFocusChanged { gridHasFocus = it.hasFocus },
                // 顶部留白：工具条按钮的触控热区会向下溢出，若与首行卡片在垂直方向重叠，
                // 「下键」的方向搜索就找不到落点，焦点会卡死在工具条上。
                contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // 网格左右边界必须显式「吃掉」按键：Compose 的二维焦点搜索在某方向找不到候选时，
                // 会「环绕」到别处的可聚焦元素，而顶部导航栏的标签就在正上方——一旦被环绕命中，
                // 标签的「聚焦即选中」会立刻把页面切走（现象：焦点在卡片上按右键，直接跳回「上传」页）。
                if (!atRoot) {
                    item(key = FOCUS_UP) {
                        UpCard(
                            autoFocus = pendingFocusPath == FOCUS_UP,
                            onAutoFocused = { pendingFocusPath = null; focusParking = false },
                            onUp = goUp,
                            onNavigateUp = {
                                if (gridState.firstVisibleItemIndex == 0) {
                                    runCatching { toolbarFocus.requestFocus() }
                                    true
                                } else false
                            },
                            stayOnLeftEdge = true, // gridIndex 0，必是第一列
                            stayOnRightEdge = totalGridItems <= 1
                        )
                    }
                }
                itemsIndexed(entries, key = { _, it -> it.file.absolutePath }) { index, entry ->
                    // 卡片在网格中的绝对位置（子目录里前面多一张「返回上级」卡片）
                    val gridIndex = index + if (atRoot) 0 else 1
                    MediaCard(
                        entry = entry,
                        childCount = if (entry.isDirectory) countInFolder(entry.file.absolutePath) else 0,
                        progress = progressMap[entry.file.absolutePath],
                        // FOCUS_FIRST 只命中第一个条目（进入子目录后焦点一次落点，不经过 UpCard）
                        autoFocus = pendingFocusPath == entry.file.absolutePath ||
                            (pendingFocusPath == FOCUS_FIRST && index == 0),
                        onAutoFocused = { pendingFocusPath = null; focusParking = false },
                        onOpen = { openEntry(entry) },
                        onMenu = { actionEntry = entry },
                        onDelete = { pendingDelete = entry },
                        // 第一行按「上键」→ 本页工具条（网格已滚到顶才算是第一行，否则交回 Compose 做网格内上行）
                        onNavigateUp = {
                            if (gridIndex < GRID_COLUMNS && gridState.firstVisibleItemIndex == 0) {
                                runCatching { toolbarFocus.requestFocus() }
                                true
                            } else false
                        },
                        stayOnLeftEdge = gridIndex % GRID_COLUMNS == 0,
                        stayOnRightEdge = (gridIndex + 1) % GRID_COLUMNS == 0 ||
                            gridIndex + 1 >= totalGridItems
                    )
                }
            }
        }
    }

    // ——— 菜单键：居中三选项对话框 ———
    actionEntry?.let { entry ->
        Dialog(onDismissRequest = { actionEntry = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp).width(360.dp)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(14.dp))
                    DialogActionRow(primaryActionLabel(entry), primary = true) {
                        actionEntry = null
                        openEntry(entry)
                    }
                    DialogActionRow("删除") {
                        actionEntry = null
                        pendingDelete = entry
                    }
                    DialogActionRow("取消") { actionEntry = null }
                }
            }
        }
    }

    // ——— 二次确认删除 ———
    pendingDelete?.let { entry ->
        Dialog(onDismissRequest = { pendingDelete = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(28.dp).width(420.dp)) {
                    Text(
                        "删除文件",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "确定删除「${entry.name}」吗？\n将删除电视上的物理文件，且无法恢复。",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton("删除文件", onClick = { performDelete(entry) })
                        TvButton("取消", onClick = { pendingDelete = null })
                    }
                }
            }
        }
    }

    // ——— 排序选择 ———
    if (showSortDialog) {
        Dialog(onDismissRequest = { showSortDialog = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(28.dp).width(320.dp)) {
                    Text(
                        "排序",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    SortOrder.entries.forEach { order ->
                        DialogActionRow(order.label, primary = order == sortOrder) {
                            sortOrder = order
                            showSortDialog = false
                        }
                    }
                }
            }
        }
    }
}

// ————————————————— 顶部工具条 —————————————————

@Composable
private fun LibraryTopBar(
    crumb: String,
    sortOrder: SortOrder,
    syncing: Boolean,
    upToTabsModifier: Modifier,
    sortFocusModifier: Modifier,
    refreshEdgeModifier: Modifier,
    onSortClick: () -> Unit,
    onRefresh: () -> Unit,
    /** true = 焦点正处于「过渡停靠」在本按钮上，按未聚焦渲染以免高亮闪烁 */
    suppressSortFocusVisual: Boolean
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            crumb,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(16.dp))
        TvButton(
            text = "排序：${sortOrder.label}",
            modifier = sortFocusModifier.then(upToTabsModifier),
            showFocusVisual = !suppressSortFocusVisual,
            onClick = onSortClick
        )
        Spacer(Modifier.width(12.dp))
        TvButton(
            text = if (syncing) "对账中…" else "刷新",
            modifier = upToTabsModifier.then(refreshEdgeModifier),
            enabled = !syncing,
            onClick = onRefresh
        )
    }
}

/** 面包屑：分类名 > 子文件夹…（如「视频 > 甄嬛传」） */
private fun buildCrumb(category: Category, root: File, currentDir: File): String = buildString {
    append(
        when (category) {
            Category.VIDEO -> "视频"
            Category.IMAGE -> "图片"
            Category.OTHER -> "其他"
        }
    )
    currentDir.absolutePath.removePrefix(root.absolutePath)
        .split('/')
        .filter { it.isNotEmpty() }
        .forEach { append(" > ").append(it) }
}

/** 菜单键主操作文案：文件夹→进入，视频→播放，图片→查看，其他→打开 */
private fun primaryActionLabel(entry: FileEntry): String = when {
    entry.isDirectory -> "进入"
    entry.file.isVideoFile() -> "播放"
    entry.file.isImageFile() -> "查看"
    else -> "打开"
}

// ————————————————— 卡片 —————————————————

/**
 * 确定键短按的确认窗口（ms）。键盘/模拟器按住确定键时，auto-repeat 常被输入
 * 通路拆成一串完整的「按下+抬起」对（每对时间戳独立，间隔通常 30~60ms），必须
 * 等窗口内无后续按下才能判定用户真的松开了；真机遥控短按因此多出这点延迟，无感。
 */
private const val CONFIRM_GRACE_MS = 200L

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaCard(
    entry: FileEntry,
    childCount: Int,
    progress: Float?,
    autoFocus: Boolean,
    onAutoFocused: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onDelete: () -> Unit,
    onNavigateUp: () -> Boolean,
    stayOnLeftEdge: Boolean,
    stayOnRightEdge: Boolean
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // —— 确定键按压状态：自己记录按下时间（键盘/模拟器 auto-repeat 的 downTime
    //    不可信，见下方按键处理确定键分支的注释）——
    var pressing by remember { mutableStateOf(false) }
    var pressStartMs by remember { mutableStateOf(0L) }
    var confirmJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(12.dp)

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            // 帧门控重试：requestFocus 必须在帧回调内发起才生效（帧间隙调用会被静默
            // 丢弃，实测踩过），所以用 requestFocusNextFrame（withFrameNanos 等下一帧）。
            // 成功判定用 focused 状态；成功才清 pendingFocusPath，失败保留状态继续抢。
            repeat(10) {
                runCatching { focusRequester.requestFocusNextFrame() }
                if (focused) {
                    onAutoFocused()
                    return@LaunchedEffect
                }
            }
        }
    }

    Column(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(if (focused) 1.1f else 1f)
            .clip(shape)
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape
            )
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                // 焦点离开（打开菜单/播放器、网格刷新等）：终止未完成的按压，避免悬挂状态
                if (!it.isFocused) {
                    pressing = false
                    confirmJob?.cancel()
                }
            }
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    // 第一行按「上键」→ 本页工具条；非第一行返回 false，交回 Compose 做网格内上行
                    Key.DirectionUp ->
                        if (event.type == KeyEventType.KeyDown) onNavigateUp() else false
                    // 行首按左 / 行尾按右：吃掉按键让焦点留在原地（见 stayOn*Edge 注释）
                    Key.DirectionLeft ->
                        event.type == KeyEventType.KeyDown && stayOnLeftEdge
                    Key.DirectionRight ->
                        event.type == KeyEventType.KeyDown && stayOnRightEdge
                    Key.Menu ->
                        if (event.type == KeyEventType.KeyDown) { onMenu(); true } else false
                    Key.Delete ->
                        if (event.type == KeyEventType.KeyDown) { onDelete(); true } else false
                    // 确定键：短按=打开，长按=菜单（与菜单键等效）。真机遥控按住 OK 键时
                    // 只会送来一串重复 KeyDown + 最后一个 KeyUp，downTime 保持首次按下时间；
                    // 而键盘（经模拟器）的 auto-repeat 常被拆成一串完整「按下+抬起」对，
                    // 每对时间戳独立，eventTime-downTime 恒 ≈0 → 长按被误判为短按，且
                    // 每次抬起都触发一次点击（=「长按变多次确定」）。因此不信任事件时间戳：
                    // - DOWN：全部吃掉（阻止 combinedClickable 逐对触发点击），自己记录按下
                    //   起点；后续（auto-repeat 的）DOWN 只取消待触发的短按，起点不重置
                    // - UP：按压 ≥ 长按阈值 → 弹菜单（必须在松开时弹：按住期间弹会抢焦点，
                    //   松开的 UP 落到菜单按钮上直接误触，真机实测踩过）；短按要等
                    //   CONFIRM_GRACE_MS 内无后续按下（排除 auto-repeat 中间抬起）才触发打开
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        val now = SystemClock.uptimeMillis()
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                confirmJob?.cancel()
                                if (!pressing) {
                                    pressing = true
                                    pressStartMs = now
                                }
                                true
                            }
                            KeyEventType.KeyUp -> {
                                if (!pressing) {
                                    true // 无对应按下的孤立抬起：吃掉防误触
                                } else {
                                    val heldMs = now - pressStartMs
                                    if (heldMs >= ViewConfiguration.getLongPressTimeout()) {
                                        pressing = false
                                        onMenu()
                                    } else {
                                        // 可能只是 auto-repeat 的中间抬起：过确认窗口再真正触发
                                        confirmJob = scope.launch {
                                            delay(CONFIRM_GRACE_MS)
                                            pressing = false
                                            onOpen()
                                        }
                                    }
                                    true
                                }
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
            // 触摸点击/长按：处理触摸事件（键盘事件已被上面的 onPreviewKeyEvent 拦截）
            .combinedClickable(onClick = { onOpen() }, onLongClick = onMenu)
            .padding(8.dp),
        // 标题/副标题居中对齐（用户 2026-09-13 要求：视频、图片、其他页的文件与文件夹名都要居中）
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 缩略图 / 图标区
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            when {
                entry.isDirectory -> TypeBadge(
                    isDirectory = true, isVideo = false, isImage = false, iconSize = 52.dp
                )
                entry.file.isVideoFile() || entry.file.isImageFile() -> AsyncImage(
                    model = entry.file.toUri(),
                    contentDescription = entry.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                else -> TypeBadge(
                    isDirectory = false, isVideo = false, isImage = false, iconSize = 52.dp
                )
            }

            // 有播放记录的视频：底部细进度条
            if (progress != null && progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.35f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                // 聚焦时才滚动：长文件名在本卡片获得焦点后循环跑马灯，能完整看全；
                // 未聚焦保持 Ellipsis 截断（不挂 marquee，避免所有卡片同时滚动分散注意力）。
                .then(if (focused) titleMarquee else Modifier)
        )
        Text(
            subtitleOf(entry, childCount),
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun UpCard(
    autoFocus: Boolean,
    onAutoFocused: () -> Unit,
    onUp: () -> Unit,
    onNavigateUp: () -> Boolean,
    stayOnLeftEdge: Boolean,
    stayOnRightEdge: Boolean
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // —— 同 MediaCard：确定键按压状态（键盘 auto-repeat 时间戳不可信）——
    var pressing by remember { mutableStateOf(false) }
    var pressStartMs by remember { mutableStateOf(0L) }
    var confirmJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(12.dp)

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            // 帧门控重试：requestFocus 必须在帧回调内发起才生效（帧间隙调用会被静默
            // 丢弃，实测踩过），所以用 requestFocusNextFrame（withFrameNanos 等下一帧）。
            // 成功判定用 focused 状态；成功才清 pendingFocusPath，失败保留状态继续抢。
            repeat(10) {
                runCatching { focusRequester.requestFocusNextFrame() }
                if (focused) {
                    onAutoFocused()
                    return@LaunchedEffect
                }
            }
        }
    }

    Column(
        Modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(if (focused) 1.1f else 1f)
            .clip(shape)
            .background(
                if (focused) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .border(
                width = 2.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = shape
            )
            // 同 MediaCard：按键处理放在焦点目标之前，确定键显式执行
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    // 确定键必须在 KeyUp 执行：goUp() 会经「焦点安全港」把焦点同步移到工具条
                    // 「排序」按钮，若在 KeyDown 执行，同一按压的 KeyUp 会派发给已聚焦的
                    // 「排序」（clickable 在 KeyUp 激活点击）→ 莫名弹出排序弹框（实测踩过）。
                    // 且键盘 auto-repeat 会被拆成多个「按下+抬起」对 → DOWN 全吃掉，短按过
                    // 确认窗口才触发 goUp，防止按住时连跳多级目录
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                        val now = SystemClock.uptimeMillis()
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                confirmJob?.cancel()
                                if (!pressing) {
                                    pressing = true
                                    pressStartMs = now
                                }
                                true
                            }
                            KeyEventType.KeyUp -> {
                                if (!pressing) {
                                    true
                                } else {
                                    pressing = false
                                    confirmJob = scope.launch {
                                        delay(CONFIRM_GRACE_MS)
                                        onUp()
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    // 第一行按「上键」→ 本页工具条；非第一行返回 false，交回 Compose 做网格内上行
                    Key.DirectionUp ->
                        if (event.type == KeyEventType.KeyDown) onNavigateUp() else false
                    // 行首按左 / 行尾按右：吃掉按键让焦点留在原地（见 stayOn*Edge 注释）
                    Key.DirectionLeft ->
                        if (event.type == KeyEventType.KeyDown) stayOnLeftEdge else false
                    Key.DirectionRight ->
                        if (event.type == KeyEventType.KeyDown) stayOnRightEdge else false
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                // 焦点离开：终止未完成的按压，避免迟到的短按误触发
                if (!it.isFocused) {
                    pressing = false
                    confirmJob?.cancel()
                }
            }
            .clickable { onUp() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "◀",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "返回上级",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 卡片副标题：文件夹=文件数；视频=时长·大小；其余=大小 */
private fun subtitleOf(entry: FileEntry, childCount: Int): String = when {
    entry.isDirectory -> "$childCount 个文件"
    entry.file.isVideoFile() && entry.duration > 0 ->
        "${FileUtils.formatDuration(entry.duration)} · ${FileUtils.formatSize(entry.size)}"
    else -> FileUtils.formatSize(entry.size)
}

@Composable
private fun DialogActionRow(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .tvFocus(cornerRadius = 8)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (primary) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmptyHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "暂无文件\n切换到「上传」页，用手机扫码传输吧",
            style = MaterialTheme.typography.titleMedium,
            color = OnDarkDim,
            textAlign = TextAlign.Center
        )
    }
}

private fun MediaItemEntity.toFileEntry(): FileEntry = FileEntry(
    file = File(filePath),
    name = fileName,
    isDirectory = false,
    size = fileSize,
    lastModified = lastModified,
    duration = duration
)
