package com.hpu.transview.ui.library

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.hpu.transview.ui.common.tvFocus
import com.hpu.transview.ui.image.ImageViewerActivity
import com.hpu.transview.ui.player.PlayerActivity
import com.hpu.transview.ui.theme.OnDarkDim
import com.hpu.transview.util.FileLocations
import com.hpu.transview.util.FileUtils
import com.hpu.transview.util.isImageFile
import com.hpu.transview.util.isVideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 网格列数（电视端多列，严禁单列列表） */
private const val GRID_COLUMNS = 5

/** 焦点还原「返回上级」卡片的哨兵路径 */
private const val FOCUS_UP = "__up__"

/**
 * 媒体库页（多列网格卡片）：视频 / 图片 / 其他三个分类共用。
 *
 * - 顶部工具条：路径面包屑（视频 > 甄嬛传）+ 排序 + 手动刷新（对账）
 * - 主体：LazyVerticalGrid 多列卡片。文件夹=图标+名称+文件数；视频=缩略图+名称+播放进度条；
 *   图片=缩略图+名称；其他=通用图标+名称。
 * - 焦点：卡片聚焦放大 1.1 倍 + 高亮边框；从文件夹返回上级时焦点还原到刚才进入的文件夹卡片。
 * - 交互：确定键直达动作；菜单键弹出「进入/播放、删除、取消」；删除走二次确认，
 *   先物理删除再删 Room 索引，成功后焦点自动移到下一个卡片。
 */
@Composable
fun LibraryScreen(category: Category) {
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
    // 待聚焦目标路径（文件绝对路径 或 FOCUS_UP）；聚焦完成后置空
    var pendingFocusPath by remember(category) { mutableStateOf<String?>(null) }

    val atRoot = currentDir == root

    // ——— 文件夹列表来自文件系统（DB 不索引文件夹） ———
    var dirRefreshKey by remember { mutableStateOf(0) }
    var dirEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    LaunchedEffect(currentDir, dirRefreshKey) {
        dirEntries = withContext(Dispatchers.IO) {
            currentDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.map { FileEntry(it, it.name, true, 0L, it.lastModified()) }
                ?: emptyList()
        }
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
    val entries = remember(dirEntries, fileEntries, sortOrder) {
        dirEntries.sortedWith(cmp) + fileEntries.sortedWith(cmp)
    }

    // ——— 文件夹内文件总数（含子层级） ———
    val countInFolder: (String) -> Int = { folderPath ->
        dbItems?.count { it.parentFolder == folderPath || it.parentFolder.startsWith("$folderPath/") } ?: 0
    }

    // ——— 焦点定位：先滚动到目标项，再由卡片自身请求焦点 ———
    LaunchedEffect(pendingFocusPath, entries) {
        val target = pendingFocusPath ?: return@LaunchedEffect
        if (target == FOCUS_UP) {
            if (!atRoot) runCatching { gridState.scrollToItem(0) }
            return@LaunchedEffect
        }
        val index = entries.indexOfFirst { it.file.absolutePath == target }
        if (index >= 0) {
            val upOffset = if (atRoot) 0 else 1
            runCatching { gridState.scrollToItem(index + upOffset) }
        }
    }

    // ——— 进入下一级 / 返回上一级 ———
    val openEntry: (FileEntry) -> Unit = { entry ->
        when {
            entry.isDirectory -> {
                currentDir = entry.file
                pendingFocusPath = FOCUS_UP
            }
            entry.file.isVideoFile() -> context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_PATH, entry.file.absolutePath)
            )
            entry.file.isImageFile() -> context.startActivity(
                Intent(context, ImageViewerActivity::class.java)
                    .putExtra(ImageViewerActivity.EXTRA_PATH, entry.file.absolutePath)
            )
            else -> FileUtils.openExternal(context, entry.file)
        }
    }

    val goUp: () -> Unit = {
        if (!atRoot) {
            val leaving = currentDir
            currentDir = leaving.parentFile ?: root
            // 焦点还原到刚才进入（即将离开）的那个文件夹卡片
            pendingFocusPath = leaving.absolutePath
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
                mediaRepo.deleteByPath(entry.file.absolutePath)
                pendingFocusPath = nextPath
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
            onSortClick = { showSortDialog = true },
            onRefresh = refreshLibrary
        )

        when {
            dbItems == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            entries.isEmpty() && atRoot -> EmptyHint()
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                state = gridState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                if (!atRoot) {
                    item(key = FOCUS_UP) {
                        UpCard(
                            autoFocus = pendingFocusPath == FOCUS_UP,
                            onAutoFocused = { pendingFocusPath = null },
                            onUp = goUp
                        )
                    }
                }
                items(entries, key = { it.file.absolutePath }) { entry ->
                    MediaCard(
                        entry = entry,
                        childCount = if (entry.isDirectory) countInFolder(entry.file.absolutePath) else 0,
                        progress = progressMap[entry.file.absolutePath],
                        autoFocus = pendingFocusPath == entry.file.absolutePath,
                        onAutoFocused = { pendingFocusPath = null },
                        onOpen = { openEntry(entry) },
                        onMenu = { actionEntry = entry },
                        onDelete = { pendingDelete = entry }
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
    onSortClick: () -> Unit,
    onRefresh: () -> Unit
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
        TvButton(text = "排序：${sortOrder.label}", onClick = onSortClick)
        Spacer(Modifier.width(12.dp))
        TvButton(
            text = if (syncing) "对账中…" else "刷新",
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

@Composable
private fun MediaCard(
    entry: FileEntry,
    childCount: Int,
    progress: Float?,
    autoFocus: Boolean,
    onAutoFocused: () -> Unit,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onDelete: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatching { focusRequester.requestFocus() }
            onAutoFocused()
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
            .onFocusChanged { focused = it.isFocused }
            .clickable { onOpen() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Menu -> { onMenu(); true }
                    Key.Delete -> { onDelete(); true }
                    else -> false
                }
            }
            .padding(8.dp)
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
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitleOf(entry, childCount),
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UpCard(autoFocus: Boolean, onAutoFocused: () -> Unit, onUp: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatching { focusRequester.requestFocus() }
            onAutoFocused()
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
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyDown) onUp()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onUp() }
            .padding(8.dp)
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
            maxLines = 1
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
