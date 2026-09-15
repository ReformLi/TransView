package com.hpu.transview.ui.player

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hpu.transview.data.PlaybackRepository
import com.hpu.transview.data.sync.SyncManager
import com.hpu.transview.model.AspectRatio
import com.hpu.transview.model.MediaRef
import com.hpu.transview.server.ServerController
import com.hpu.transview.ui.common.OptionRow
import com.hpu.transview.ui.common.TvButton
import com.hpu.transview.ui.settings.SettingsStore
import com.hpu.transview.ui.theme.TransViewTheme
import com.hpu.transview.util.FileLocations
import com.hpu.transview.util.FileUtils
import com.hpu.transview.util.mediaUri
import com.hpu.transview.util.nameIsVideoFile
import com.hpu.transview.util.naturalCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 视频播放器（v1.10 时间轴优先控制栏，对齐 Netflix / tvOS 交互习惯）：
 * - Media3 ExoPlayer，支持外挂字幕（同名 .srt/.ass/.vtt）
 * - 播放进度自动入库（Room），再次打开弹出续播提示
 * - 播完自动连播同目录下一个视频（自然排序，EP2 < EP10）；
 *   自动连播开启时先弹「下一集预告卡」倒计时 5 秒（可立即播放/取消），不再无感硬切
 *
 * 控制栏结构（自上而下）：
 * - 内联选择器（倍速 / 画面比例横排胶囊）：取代旧模态弹框，OK 即选即生效，
 *   Back / 上键关闭并回到来源按钮
 * - 时间轴（控制栏主角）：聚焦时轨道加粗 + 主色光环；左右 = 拖动（中央弹
 *   「快进/快退预览卡」：缩略图 + 目标时间，不再是盲跳）；OK = 播放/暂停
 * - 按钮行：上一集 / 快退 / 播放暂停 / 快进 / 下一集 ｜ 倍速 / 比例 / 设置(⚙)
 * - 设置面板（右侧滑入）：音轨 + 字幕两个分区的可滚动列表，取代旧 TrackDialog，
 *   OK 即选即生效并收起，Back 收起并回到设置按钮
 *
 * 设置页「播放设置」四项在此生效（`SettingsStore`）：
 * - 自动续播提示：开（默认）→ 有历史进度时弹「续播/从头/取消」；关 → 静默从上次位置继续。
 * - 自动连播：开（默认）→ 播完弹预告卡倒计时 5 秒后连播；关 → 播完停在片尾。
 * - 默认倍速 / 默认画面比例：打开播放器时的初始值（会话内可随时改，不回写设置）。
 *
 * 遥控器适配（时间轴优先按键模型）：
 * - 控制栏隐藏：左右 = 快退/快进（手势直控）；OK = 播放/暂停；上/下/菜单 = 唤出控制栏，
 *   **焦点落在时间轴**。
 * - 控制栏可见：时间轴聚焦 → 左右拖动（带缩略图预览）、OK 播放/暂停；上下在
 *   「时间轴 ↔ 按钮行」之间换轨；**最外缘再按同向 = 收起控制栏**（时间轴上按上 /
 *   按钮行上按下）。焦点所在轨道由 [focusZone] 跟踪（各区域 onFocusChanged 上报）。
 * - 选择器 / 设置面板打开期间：不收起、不换轨（各自的边界处理）。
 * - 快退/快进：短按固定 ±10 秒，按住 400ms 进入
 *   变速扫描（2x→4x→8x→16x），反馈为中央预览卡（缩略图 + 目标时间）。
 * - 遥控器 ⏪/⏩/⏯/⏭/⏮ 媒体键不受控制栏状态影响。
 * - 返回键分层（BackHandler，predictive back 兼容）：预告卡 → 设置面板 → 选择器
 *   → 控制栏 → 退出。
 * - 图标按「动作式」显示（图标 = 按下去会发生什么），底栏与中央常驻图标共用
 *   `playPauseIcon()`，两处方向永远一致。
 * - 「上一集/下一集」无对应集数时置灰但**仍保留焦点**（`PlayerIconButton` 恒可聚焦，
 *   禁用态自己吞掉确定键）——否则切到最后一集时按钮当场不可聚焦，焦点会掉到根节点。
 *   详见 ARCHITECTURE §3.6。
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        /** 返回给媒体库：最后播放的视频路径，用于焦点定位 */
        const val EXTRA_RESULT_PATH = "last_viewed_path"
        private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        /** 短按一次的快进/快退步长（固定 10 秒，不做链式累加） */
        private const val SEEK_STEP_MS = 10_000L

        /** 按住超过该时长即进入变速扫描 */
        private const val SEEK_LONG_PRESS_MS = 400L

        /** 扫描节拍与每拍推进时长（步长 = 节拍 × 倍率） */
        private const val SEEK_SCAN_INTERVAL_MS = 150L
        private const val SEEK_SCAN_STEP_MS = 150L

        /** 中央徽标 / 快进预览卡停留时长 */
        private const val BADGE_HOLD_MS = 1_200L

        /** 控制栏无操作自动隐藏时长 */
        private const val OVERLAY_AUTO_HIDE_MS = 6_000L

        /** 下一集预告卡倒计时（秒） */
        private const val NEXT_CARD_COUNTDOWN_SEC = 5

        /**
         * 默认画面比例 → PlayerView 缩放模式（常量定义在 `AspectRatioFrameLayout`，PlayerView 自身没有）：
         * 原始 = FIT（保持比例，留黑边）／拉伸 = FILL（铺满，变形）／裁剪 = ZOOM（铺满，裁边）。
         */
        private fun resizeModeOf(aspect: AspectRatio): Int = when (aspect) {
            AspectRatio.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatio.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatio.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
    }

    lateinit var player: ExoPlayer
        private set
    private lateinit var repository: PlaybackRepository
    /** 同目录连播列表。元素是 [MediaRef]（path 恒为本地绝对路径） */
    private var playlist: List<MediaRef> = emptyList()
    private var currentIndex = 0

    // —— Compose 状态 ——
    var overlayVisible by mutableStateOf(true)
    var showResumeDialog by mutableStateOf(false)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var bufferedMs by mutableStateOf(0L)
    var speed by mutableStateOf(1.0f)
    /** 当前画面比例（打开播放器时取设置页「默认画面比例」，控制栏「比例」可临时改） */
    var aspect by mutableStateOf(AspectRatio.ORIGINAL)
    var currentTitle by mutableStateOf("")
    var lastInteractionTick by mutableStateOf(0)

    /** 最后一个视频已播完：停在末尾等待用户「重播」或按返回离开，不再自动退出 */
    var ended by mutableStateOf(false)

    // —— 时间轴优先控制栏（v1.10）：内联选择器 + 设置面板 + 下一集预告卡 ——

    /** 控制栏上方弹出的内联选择器（倍速 / 画面比例），取代旧的模态弹框 */
    var inlineSelector by mutableStateOf<InlineSelector>(InlineSelector.NONE)

    /** 右侧滑入的「音轨 / 字幕」设置面板，取代旧的 TrackDialog */
    var showSettingsPanel by mutableStateOf(false)

    /** 焦点当前所处的纵向「轨道」：时间轴 / 按钮行 / 选择器 / 面板 / 预告卡。
     *  上下键在轨道之间换轨、在最外缘收起控制栏，都靠它判定。 */
    var focusZone by mutableStateOf(FocusZone.TIMELINE)

    /** 播完且自动连播开启时的「下一集预告卡」（倒计时 5 秒，可取消） */
    var nextCardVisible by mutableStateOf(false)
    var nextCountdownSec by mutableStateOf(5)

    // —— 中央反馈徽标（播放/暂停/起播等瞬时反馈；快进快退走 ScrubCard） ——
    private var badgeIcon by mutableStateOf<PlayerIconType?>(null)
    private var badgeText by mutableStateOf("")
    private var badgeTick by mutableStateOf(0)
    private var badgeTickSeq = 0

    // —— 快进/快退预览卡（缩略图 + 目标时间，取代旧的纯文字徽标） ——
    private var scrubForward by mutableStateOf(true)
    private var scrubText by mutableStateOf("")
    private var scrubTick by mutableStateOf(0)
    private var scrubTickSeq = 0
    private var scrubThumb by mutableStateOf<ImageBitmap?>(null)

    /** 下一集预告卡的封面帧（取下一集开头附近的帧） */
    var nextThumb by mutableStateOf<ImageBitmap?>(null)

    private var pendingResumePosition = 0L

    /**
     * 已看完、待清除播放历史的文件路径。
     * STATE_ENDED 时异步 clear 历史后，周期性 saveProgress（此刻 position=duration）
     * 与 skipTo 前的 saveProgress 都可能把「100% 进度」记录重新写回库里（两次异步写
     * 顺序不保证），导致「看完自动清除历史」失效、卡片残留满格进度条——这里统一拦掉。
     * 重播同一文件时移出集合，恢复正常记录。
     */
    private val finishedPaths = mutableSetOf<String>()

    /**
     * PlayerView 实例。画面比例要直接改它的 `resizeMode`，而 `AndroidView(update=…)` 里读 Compose
     * 状态不会建立订阅（update 非组合作用域，状态变化不会触发重绘），因此保存引用后命令式设置。
     */
    private var playerView: PlayerView? = null

    // —— 快进/快退状态 ——
    private var seekJob: Job? = null
    private var scrubStarted = false

    // —— 缩略图预览（MediaMetadataRetriever，IO 线程 + 互斥串行 + LRU 式缓存） ——
    private val thumbCache = LinkedHashMap<String, ImageBitmap>()
    private val thumbMutex = Mutex()
    private var thumbRetriever: MediaMetadataRetriever? = null
    private var thumbRetrieverPath: String? = null
    private var thumbReqSeq = 0

    private val currentFile: MediaRef? get() = playlist.getOrNull(currentIndex)
    private val hasNext: Boolean get() = currentIndex + 1 < playlist.size
    private val hasPrev: Boolean get() = currentIndex > 0

    /** 内联选择器类型（控制栏上方横排胶囊） */
    enum class InlineSelector { NONE, SPEED, ASPECT }

    /** 控制栏内的纵向焦点轨道 */
    enum class FocusZone { TIMELINE, BUTTONS, SELECTOR, PANEL, CARD }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            // 播放期间暂停 HTTP 服务器，防止低性能设备"边播边传"卡顿；暂停/退出自动恢复
            ServerController.setPlaying(playing)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val file = currentFile
                if (file != null) {
                    // 先挂「已看完」守卫再异步清库：clear 与周期性 saveProgress 是两次
                    // 顺序不保证的异步写，不拦的话 100% 进度记录可能被重新写回
                    finishedPaths += file.path
                    lifecycleScope.launch { repository.clear(file.path) }
                }
                ended = true
                overlayVisible = true
                lastInteractionTick++
                if (hasNext && SettingsStore.autoPlayNext) {
                    // 自动连播开启且还有下一集：弹「下一集预告卡」倒计时 5 秒
                    // （主流做法：给用户反悔/立即播放的机会，而不是无感硬切）
                    showNextCard()
                } else {
                    // 最后一集 / 自动连播关闭：停在片尾由用户决定「重播」还是离开
                    showBadge(
                        null,
                        if (hasNext) "播放结束（自动连播已关闭）" else "播放结束"
                    )
                }
            } else if (ended) {
                // seek / 重播后重新进入准备状态，退出结束态；
                // 同时解除「已看完」守卫——用户回看（如快退到中部）时进度要恢复正常记录。
                // 离开结束态也意味着预告卡（若还在倒计时）作废——用户已经自己行动了。
                ended = false
                hideNextCard()
                currentFile?.let { finishedPaths.remove(it.path) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val file = currentFile
            if (file != null && !FileLocations.existsForPath(file.path)) {
                // 文件被外部删除（FileNotFoundException / 文档 URI 失效兜底）：删索引并提示，
                // UI 经 Room Flow 自动刷新
                lifecycleScope.launch {
                    SyncManager.getInstance(this@PlayerActivity).reportMissingFile(file.path)
                }
                Toast.makeText(this@PlayerActivity, "文件已丢失，已从列表移除", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@PlayerActivity, "播放失败：${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SettingsStore.init(this) // 读取设置页「播放设置」四项（App 启动时已 init，这里兜底）

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !FileLocations.existsForPath(path)) {
            // 入口即发现文件丢失：上报对账引擎清理索引（残留记录随之移除）
            if (path != null) {
                lifecycleScope.launch {
                    SyncManager.getInstance(this@PlayerActivity).reportMissingFile(path)
                }
            }
            Toast.makeText(this, "文件已丢失，已从列表移除", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        repository = PlaybackRepository(this)
        // 同目录连播列表：走活动存储的「兄弟条目」（v1.14 恒为列目录），
        // 对 U 盘与内部存储同样成立（不依赖 File.parentFile，故不会退化成单集播放）
        playlist = FileLocations.siblings(path)
            .filter { !it.isDirectory && nameIsVideoFile(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
            .map { MediaRef(it.path, it.name) }
            .ifEmpty { listOf(MediaRef(path, path.substringAfterLast('/'))) }
        currentIndex = playlist.indexOfFirst { it.path == path }.takeIf { it >= 0 } ?: 0
        postResult()

        player = ExoPlayer.Builder(this).build()

        // 设置页「默认倍速」「默认画面比例」：打开播放器时即生效。
        // 只是「默认值」——本次会话内仍可用控制栏的倍速 / 比例按钮随时改，互不写回设置。
        speed = SettingsStore.defaultSpeed
        player.setPlaybackSpeed(speed)
        aspect = SettingsStore.defaultAspect

        player.addListener(playerListener)
        prepareItem(currentIndex)

        setContent {
            TransViewTheme {
                PlayerScreen()
            }
        }

        // 续播判断
        lifecycleScope.launch {
            val history = repository.get(path)
            if (history != null && history.position > 10_000 &&
                (history.duration <= 0 || history.position < history.duration * 95 / 100)
            ) {
                pendingResumePosition = history.position
                // 「自动续播提示」开启（默认）→ 弹窗询问；关闭 → 静默从上次位置继续播。
                if (SettingsStore.autoResumePrompt) showResumeDialog = true
                else startPlayback(history.position)
            } else {
                startPlayback(0L)
            }
        }

        // 进度自动保存
        lifecycleScope.launch {
            while (isActive) {
                delay(2000)
                saveProgress()
            }
        }
    }

    /**
     * 把当前播放的视频路径写入返回结果（连播/切集时调用）。
     * 必须在 finish() 之前调用——系统在 finish 时就按当时的 result 封装返回值，
     * 拖到 onPause 再 setResult 会来不及（实测拿到 null）。
     */
    private fun postResult() {
        currentFile?.let {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, it.path))
        }
    }

    override fun onStop() {
        player.pause()
        saveProgress()
        super.onStop()
    }

    override fun onDestroy() {
        // 进度保存只在 onStop 做：这里 lifecycleScope 随 DESTROYED 即将取消，
        // launch 出去的保存可能被中途取消，属不可靠冗余（onStop 必然先于 onDestroy 执行）
        ServerController.setPlaying(false)
        player.removeListener(playerListener)
        player.release()
        runCatching { thumbRetriever?.release() }
        thumbRetriever = null
        thumbRetrieverPath = null
        super.onDestroy()
    }

    // ————— 播放控制 —————

    /**
     * 找出同名的外挂字幕（srt/ass/ssa/vtt）。
     *
     * 走活动存储的兄弟条目（v1.14 恒为列目录）。字幕轨以 `file://` URI 交给 ExoPlayer 读取，
     * 与视频同源，同样可用。
     */
    private fun findSubtitleFiles(video: MediaRef): List<MediaItem.SubtitleConfiguration> {
        val base = video.nameWithoutExtension
        return FileLocations.siblings(video.path)
            .filter {
                !it.isDirectory && it.nameWithoutExtension.equals(base, ignoreCase = true) &&
                    it.extension in setOf("srt", "ass", "ssa", "vtt")
            }
            .map { f ->
                val mime = when (f.extension) {
                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                    "vtt" -> MimeTypes.TEXT_VTT
                    else -> MimeTypes.APPLICATION_SS
                }
                MediaItem.SubtitleConfiguration.Builder(mediaUri(f.path))
                    .setMimeType(mime)
                    .setLanguage(f.extension)
                    .build()
            }
    }

    private fun prepareItem(index: Int) {
        val file = playlist.getOrNull(index) ?: return
        currentTitle = file.name
        // file:///绝对路径 由 ExoPlayer 的 DefaultDataSource 直接播（原生支持，开销最小）
        val item = MediaItem.Builder()
            .setUri(mediaUri(file.path))
            .setSubtitleConfigurations(findSubtitleFiles(file))
            .build()
        player.setMediaItem(item)
    }

    /** 从指定位置开始播放 */
    private fun startPlayback(positionMs: Long) {
        if (positionMs > 0) player.seekTo(positionMs)
        player.prepare()
        player.playWhenReady = true
    }

    private fun skipTo(index: Int) {
        if (index !in playlist.indices) return
        saveProgress()
        hideNextCard()
        currentIndex = index
        postResult()
        ended = false
        prepareItem(index)
        player.prepare()
        player.playWhenReady = true
        positionMs = 0
        durationMs = 0
        bufferedMs = 0
    }

    private fun saveProgress() {
        val file = currentFile ?: return
        // 已看完待清历史的文件不再回写进度（见 finishedPaths 注释）
        if (file.path in finishedPaths) return
        val pos = player.currentPosition
        val dur = player.duration
        if (dur > 0 && pos > 1000) {
            lifecycleScope.launch {
                repository.save(file.path, file.name, pos, dur)
            }
        }
    }

    /**
     * 播放 / 暂停图标统一取「动作式」语义：**图标 = 按下去会发生什么**。
     * - 播放中 → ‖（按下会暂停）
     * - 暂停 / 播完 → ▶（按下会播放 / 重播）
     *
     * 底栏按钮和中央常驻图标都走这里，保证两处方向永远一致，不会再出现「看起来反了」。
     */
    private fun playPauseIcon(): PlayerIconType =
        if (isPlaying && !ended) PlayerIconType.PAUSE else PlayerIconType.PLAY

    /**
     * 播放 / 暂停。注意不能用 `player.play()` 之后立刻读 `player.isPlaying` 反推状态 ——
     * 起播瞬间播放器还在 BUFFERING，isPlaying 仍为 false，会导致反馈图标刚好显示反。
     * 这里按「按下前的状态」确定意图：暂停不弹瞬时徽标（由中央常驻图标负责），
     * 起播弹一次纯图标徽标（不带文字，与暂停常驻图标同样居中）。
     */
    private fun togglePlayPause() {
        if (ended) {
            // 播完状态下按播放键 = 重播：解除「已看完」守卫，此后正常记录新进度。
            // 重播意味着放弃预告卡倒计时（否则会边重播边被倒计时切走）
            hideNextCard()
            currentFile?.let { finishedPaths.remove(it.path) }
            ended = false
            player.seekTo(0)
            player.play()
            showBadge(PlayerIconType.PAUSE, "")
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
            showBadge(PlayerIconType.PAUSE, "")
        }
    }

    /**
     * 相对跳转，返回跳转后的目标位置（毫秒）。
     * 注意 [Player.getDuration] 在尚未 prepare 完成时返回 [C.TIME_UNSET]（负数），
     * 直接 coerceIn(0, duration) 会抛 IllegalArgumentException，必须兜底。
     */
    private fun seekBy(deltaMs: Long): Long {
        val duration = player.duration
        val upper = if (duration > 0) duration else Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, upper)
        player.seekTo(target)
        return target
    }

    /**
     * 快进/快退反馈（v1.10 升级为预览卡）：缩略图（异步）+ 方向图标 +「01:21 / 13:01」
     * （目标位置 / 总时长）。用户能看到将要跳到的那一帧，拖动不再是盲跳。
     */
    private fun showSeekBadge(dir: Int, targetMs: Long) {
        val total = player.duration
        val text = if (total > 0) {
            "${FileUtils.formatDuration(targetMs)} / ${FileUtils.formatDuration(total)}"
        } else {
            FileUtils.formatDuration(targetMs)
        }
        scrubForward = dir > 0
        scrubText = text
        scrubTick = ++scrubTickSeq
        // 变速扫描期间（每 150ms 一次）不追帧：retriever 取帧要几十到几百毫秒，
        // 追不上只会白烧 CPU 撑爆缓存；扫描结束/短按落点才加载最终帧
        currentFile?.let { if (!scrubStarted) requestScrubThumb(it, targetMs) }
    }

    // ————— 缩略图预览（快进预览卡 / 下一集预告卡共用） —————

    /** 取 10 秒分桶：预览不需要精确到帧，分桶可显著提高缓存命中 */
    private fun thumbBucket(ms: Long): Long = ms / 10_000L * 10_000L

    /** 请求当前快进位置的预览帧（seq 守卫：慢速解码完成后，只有最新请求才能上屏） */
    private fun requestScrubThumb(file: MediaRef, atMs: Long) {
        val bucket = thumbBucket(atMs)
        val key = "${file.path}#$bucket"
        thumbCache[key]?.let { cached ->
            scrubThumb = cached
            return
        }
        scrubThumb = null // 占位（避免显示错误位置的旧帧）
        val seq = ++thumbReqSeq
        lifecycleScope.launch {
            val bmp = loadThumb(file.path, bucket)
            if (seq == thumbReqSeq) scrubThumb = bmp
        }
    }

    /**
     * 取指定文件、指定时间（10 秒桶）的预览帧：IO 线程 + 互斥串行（MediaMetadataRetriever
     * 非线程安全）+ 进程内缓存（上限 48 帧 ≈ 7MB，超限淘汰最旧）。取帧失败（格式不支持 /
     * 文件损坏）返回 null，调用方显示占位。
     */
    private suspend fun loadThumb(path: String, bucketMs: Long): ImageBitmap? =
        thumbMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val key = "$path#$bucketMs"
                    thumbCache[key]?.let { return@runCatching it }
                    if (thumbRetrieverPath != path) {
                        thumbRetriever?.release()
                        // 绝对路径版本 setDataSource(path)，比 URI 版本更快、也不依赖 ContentResolver
                        val retriever = FileUtils.retrieverFor(this@PlayerActivity, path)
                        thumbRetriever = retriever
                        thumbRetrieverPath = path
                    }
                    val raw = thumbRetriever
                        ?.getFrameAtTime(bucketMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: return@runCatching null
                    // 统一缩到宽 256（高按比例），控制缓存内存占用
                    val scaled = if (raw.width > 256) {
                        val ratio = 256f / raw.width
                        Bitmap.createScaledBitmap(raw, 256, (raw.height * ratio).toInt(), true)
                    } else raw
                    val image = scaled.asImageBitmap()
                    if (thumbCache.size >= 48) {
                        thumbCache.remove(thumbCache.keys.first())
                    }
                    thumbCache[key] = image
                    image
                }.getOrNull()
            }
        }

    // ————— 下一集预告卡 —————

    private fun showNextCard() {
        nextCountdownSec = NEXT_CARD_COUNTDOWN_SEC
        nextCardVisible = true
        nextThumb = null
        // 预告卡封面：取下一集开头附近的帧（不必等它，加载完成自然出现）
        playlist.getOrNull(currentIndex + 1)?.let { next ->
            lifecycleScope.launch { nextThumb = loadThumb(next.path, 5_000L) }
        }
    }

    private fun hideNextCard() {
        nextCardVisible = false
    }

    // ————— 中央反馈徽标 —————

    private fun showBadge(icon: PlayerIconType?, text: String) {
        badgeIcon = icon
        badgeText = text
        badgeTick = ++badgeTickSeq
    }

    // ————— 快退/快进 —————

    /**
     * 按下（含遥控器媒体键）。首次 KeyDown 启动一个协程：
     * 若 [SEEK_LONG_PRESS_MS] 内收到 KeyUp 则判为短按（见 [onSeekUp]），
     * 否则进入变速扫描，倍率每 1.2 秒翻倍直到 16x。
     * 长按期间系统自动重复的 KeyDown 会被 [seekJob] 拦截，不会重复启动。
     */
    private fun onSeekDown(dir: Int) {
        if (seekJob?.isActive == true) return
        seekJob = lifecycleScope.launch {
            delay(SEEK_LONG_PRESS_MS)
            scrubStarted = true
            var rate = 2
            var elapsed = 0L
            while (isActive) {
                val target = seekBy(dir * SEEK_SCAN_STEP_MS * rate)
                showSeekBadge(dir, target)
                elapsed += SEEK_SCAN_INTERVAL_MS
                if (elapsed % 1_200L == 0L) rate = (rate * 2).coerceAtMost(16)
                delay(SEEK_SCAN_INTERVAL_MS)
            }
        }
    }

    private fun onSeekUp(dir: Int) {
        val job = seekJob ?: return
        seekJob = null
        if (!job.isActive) return
        val wasScanning = scrubStarted
        job.cancel()
        scrubStarted = false
        if (wasScanning) {
            // 扫描结束：补一次最终落点的预览帧（扫描期间刻意不追帧，见 showSeekBadge）
            currentFile?.let { requestScrubThumb(it, player.currentPosition) }
            return
        }

        // 短按：固定跳 10 秒（不累加——用户习惯一下一下蹦，链式步长反而难预估落点）
        showSeekBadge(dir, seekBy(dir * SEEK_STEP_MS))
    }

    // ————— 遥控器按键 —————

    /**
     * 时间轴优先按键模型（v1.10，对齐 Netflix / tvOS 习惯）：
     * - 控制栏隐藏：左右 = 快退/快进（手势直控，连续快进不被打断）；OK = 播放/暂停；
     *   上 / 下 / 菜单 = 唤出控制栏，**焦点落在时间轴**（时间轴是主角）。
     * - 控制栏可见：时间轴聚焦时左右 = 拖动（带缩略图预览）、OK = 播放/暂停；
     *   上下 = 在「时间轴 ↔ 按钮行」之间换轨；**最外缘再按同向 = 收起控制栏**
     *   （时间轴上再按上 / 按钮行上再按下，tvOS 边缘收起习惯）。
     * - 内联选择器 / 设置面板打开期间：不收起、不换轨（各自的 handler 处理边界）。
     * - 返回键分层：预告卡 → 设置面板 → 选择器 → 控制栏 → 退出。
     *   统一由 PlayerScreen 里的 BackHandler 处理（predictive back 下返回键不进
     *   KeyEvent 派发链；旧设备未消费的返回键也最终流入 OnBackPressedDispatcher）。
     */
    private fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown

        // 遥控器上的 ⏪ / ⏩ 专用媒体键：始终是快退/快进，不受控制栏状态影响
        when (event.key) {
            Key.MediaFastForward -> {
                if (down) onSeekDown(1) else onSeekUp(1)
                return true
            }
            Key.MediaRewind -> {
                if (down) onSeekDown(-1) else onSeekUp(-1)
                return true
            }
            else -> Unit
        }

        if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
            // 左右键**要续命**自动隐藏计时：否则按键导航不刷新计时，用户还在移动时
            // 控制栏会突然消失，之后的按键语义会突变（实测踩过）。
            if (down) lastInteractionTick++
            // 控制栏可见时交给焦点系统：时间轴聚焦 → 时间轴自身的 handler 把左右键变成拖动；
            // 按钮 / 胶囊 / 面板聚焦 → 焦点导航（各行自带边界 trap）。
            if (overlayVisible || showResumeDialog) return false
            val dir = if (event.key == Key.DirectionRight) 1 else -1
            if (down) onSeekDown(dir) else onSeekUp(dir)
            return true
        }

        if (!down) return false
        lastInteractionTick++

        return when (event.key) {
            // 注意：不处理 Key.Back —— 返回键统一由 PlayerScreen 里的 BackHandler 处理。
            // 实测 API 34+ 上返回键的 DOWN 会正常进 KeyEvent 派发链、UP 会被 ViewRootImpl
            // 转成 OnBackInvoked 回调：若这里也消费 DOWN 会「收起面板 + 退出」双重触发
            //（旧设备上未消费的返回键最终也流入 OnBackPressedDispatcher，BackHandler 通吃）。

            Key.Menu -> when {
                nextCardVisible -> { hideNextCard(); true }
                overlayVisible -> { hideOverlay(); true }
                else -> { openOverlay(); true }
            }

            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                togglePlayPause()
                true
            }

            Key.MediaNext -> {
                if (hasNext) skipTo(currentIndex + 1)
                true
            }

            Key.MediaPrevious -> {
                if (hasPrev) skipTo(currentIndex - 1)
                true
            }

            Key.DirectionUp -> when {
                showResumeDialog -> false
                inlineSelector != InlineSelector.NONE -> { closeSelector(); true }
                showSettingsPanel -> false   // 面板内上下 = 行间导航
                overlayVisible -> {
                    // 时间轴已是最高轨道：再向上 = 收起（预告卡倒计时期间保持控制栏在场）
                    if (focusZone == FocusZone.TIMELINE && !nextCardVisible) hideOverlay() else false
                }
                else -> openOverlay()
            }

            Key.DirectionDown -> when {
                showResumeDialog -> false
                inlineSelector != InlineSelector.NONE -> true  // 选择器下面没有轨道，吞掉防止焦点掉进时间轴
                showSettingsPanel -> false
                overlayVisible -> {
                    // 按钮行已是最低轨道：再向下 = 收起
                    if (focusZone == FocusZone.BUTTONS && !nextCardVisible) hideOverlay() else false
                }
                else -> openOverlay()
            }

            Key.DirectionCenter, Key.Enter -> {
                // 控制栏可见时交给当前聚焦元素（时间轴聚焦 → 时间轴自己处理为播放/暂停）
                if (overlayVisible || showResumeDialog) false else {
                    togglePlayPause()
                    true
                }
            }

            else -> false
        }
    }

    private fun openOverlay(): Boolean {
        overlayVisible = true
        return true
    }

    /** 收起控制栏，顺带关掉内联选择器 / 设置面板（它们是控制栏的子状态） */
    private fun hideOverlay(): Boolean {
        overlayVisible = false
        inlineSelector = InlineSelector.NONE
        showSettingsPanel = false
        return true
    }

    private fun closeSelector(): Boolean {
        inlineSelector = InlineSelector.NONE
        return true
    }

    private fun closeSettingsPanel(): Boolean {
        showSettingsPanel = false
        return true
    }

    // ————— Compose UI —————

    @Composable
    private fun PlayerScreen() {
        val rootFocus = remember { FocusRequester() }
        val timelineFocus = remember { FocusRequester() }
        val selectorFocus = remember { FocusRequester() }
        val speedBtnFocus = remember { FocusRequester() }
        val aspectBtnFocus = remember { FocusRequester() }
        val settingsBtnFocus = remember { FocusRequester() }
        val panelFocus = remember { FocusRequester() }
        val cardPlayFocus = remember { FocusRequester() }

        // 位置 / 缓冲 / 时长刷新
        LaunchedEffect(Unit) {
            while (isActive) {
                positionMs = player.currentPosition
                bufferedMs = player.bufferedPosition
                if (player.duration > 0) durationMs = player.duration
                delay(500)
            }
        }

        // 控制栏无操作自动隐藏。选择器 / 设置面板 / 预告卡 / 续播弹窗在场期间不隐藏
        //（它们都是 key，打开即取消计时、关闭即重新计时）。
        LaunchedEffect(
            overlayVisible, lastInteractionTick, showResumeDialog,
            inlineSelector, showSettingsPanel, nextCardVisible
        ) {
            val pinned = showResumeDialog || nextCardVisible ||
                inlineSelector != InlineSelector.NONE || showSettingsPanel
            if (overlayVisible && !pinned) {
                delay(OVERLAY_AUTO_HIDE_MS)
                val stillPinned = showResumeDialog || nextCardVisible ||
                    inlineSelector != InlineSelector.NONE || showSettingsPanel
                if (overlayVisible && !stillPinned) overlayVisible = false
            }
        }

        // 中央徽标自动消失（纯图标徽标——如起播反馈——也要计时消失）
        LaunchedEffect(badgeTick) {
            if (badgeIcon != null || badgeText.isNotEmpty()) {
                delay(BADGE_HOLD_MS)
                badgeText = ""
                badgeIcon = null
            }
        }

        // 快进/快退预览卡自动消失：期间有新动作会重启本 effect（key = scrubTick），
        // 变速扫描时每 150ms 一跳，卡片自然保持在场。
        LaunchedEffect(scrubTick) {
            if (scrubText.isNotEmpty()) {
                delay(BADGE_HOLD_MS)
                scrubText = ""
            }
        }

        // 焦点归属：续播弹窗自己管焦点 → 控制栏显示则落到时间轴（时间轴是主角）
        // → 否则回到根节点收键
        LaunchedEffect(overlayVisible, showResumeDialog) {
            when {
                showResumeDialog -> Unit
                overlayVisible -> {
                    delay(50)
                    runCatching { timelineFocus.requestFocus() }
                }
                else -> runCatching { rootFocus.requestFocus() }
            }
        }

        // 返回键分层：预告卡 → 设置面板 → 内联选择器 → 控制栏 → 退出。
        // 必须用 BackHandler（OnBackPressedDispatcher）而非在 onKeyEvent 里处理 Key.Back：
        // targetSdk 33+ 在 Android 13+ 上 predictive back 接管返回键，若 onKeyEvent 也消费
        // DOWN 会「收起面板 + 退出」双重触发；未消费的返回键（新旧设备）最终都流入
        // OnBackPressedDispatcher，BackHandler 通吃。
        // 续播弹窗是独立 Dialog 窗口，返回键先由它的 onDismissRequest 处理，不经过这里。
        BackHandler {
            when {
                nextCardVisible -> hideNextCard()
                showSettingsPanel -> closeSettingsPanel()
                inlineSelector != InlineSelector.NONE -> closeSelector()
                overlayVisible -> hideOverlay()
                else -> finish()
            }
        }

        // 内联选择器开/关的焦点流转：开 → 焦点落到当前选中项；
        // 关（选择生效 / Back / 上键）→ 焦点回到来源按钮（倍速/比例），「关了马上能再开」
        var selectorWasOpen by remember { mutableStateOf(false) }
        var lastSelector by remember { mutableStateOf(InlineSelector.NONE) }
        LaunchedEffect(inlineSelector) {
            if (inlineSelector != InlineSelector.NONE) {
                lastSelector = inlineSelector
                selectorWasOpen = true
                delay(50)
                runCatching { selectorFocus.requestFocus() }
            } else if (selectorWasOpen) {
                selectorWasOpen = false
                // 控制栏被一并收起时（Menu/边缘收起）不抢焦点，交给 overlayVisible 的 effect 回根节点
                if (overlayVisible) {
                    val target = if (lastSelector == InlineSelector.SPEED) speedBtnFocus else aspectBtnFocus
                    runCatching { target.requestFocus() }
                }
            }
        }

        // 设置面板开/关的焦点流转：开 → 面板首行；关 → 回到设置按钮
        var panelWasOpen by remember { mutableStateOf(false) }
        LaunchedEffect(showSettingsPanel) {
            if (showSettingsPanel) {
                panelWasOpen = true
                delay(50)
                runCatching { panelFocus.requestFocus() }
            } else if (panelWasOpen) {
                panelWasOpen = false
                if (overlayVisible) runCatching { settingsBtnFocus.requestFocus() }
            }
        }

        // 下一集预告卡：出现 → 焦点落到「立即播放」；消失 → 回到时间轴
        var cardWasVisible by remember { mutableStateOf(false) }
        LaunchedEffect(nextCardVisible) {
            if (nextCardVisible) {
                cardWasVisible = true
                delay(50)
                runCatching { cardPlayFocus.requestFocus() }
            } else if (cardWasVisible) {
                cardWasVisible = false
                if (overlayVisible) runCatching { timelineFocus.requestFocus() }
                else runCatching { rootFocus.requestFocus() }
            }
        }

        // 预告卡倒计时：每秒 -1，归零自动切下一集（取消/切集会翻转 nextCardVisible 取消本协程）
        LaunchedEffect(nextCardVisible) {
            if (nextCardVisible) {
                while (nextCountdownSec > 0) {
                    delay(1_000)
                    nextCountdownSec--
                }
                if (nextCardVisible) skipTo(currentIndex + 1)
            }
        }

        DisposableEffect(Unit) {
            onDispose { saveProgress() }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { onKeyEvent(it) }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        this.player = this@PlayerActivity.player
                        // 初始比例来自设置页「默认画面比例」；运行中由控制栏「比例」按钮命令式改
                        resizeMode = resizeModeOf(aspect)
                    }.also { playerView = it }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 暂停后常驻的中央图标（无背景）：暂停后一直显示，恢复播放即淡出。
            // 图标与底栏按钮同一套「动作式」规则（暂停时显示 ▶，表示按下去会播放）。
            // 有瞬时反馈（快进/快退预览卡/起播图标）时先让位，避免两个图标叠在一起。
            AnimatedVisibility(
                visible = !isPlaying && !ended && badgeIcon == null && badgeText.isEmpty() && scrubText.isEmpty(),
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                PlayerCentralBadge(icon = playPauseIcon(), text = "")
            }

            // 中央瞬时反馈：起播（纯图标，居中同暂停常驻图标）、播放结束等，控制栏不参与（快进/快退走预览卡）
            AnimatedVisibility(
                visible = (badgeIcon != null || badgeText.isNotEmpty()) && scrubText.isEmpty(),
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(240)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                PlayerCentralBadge(icon = badgeIcon, text = badgeText)
            }

            // 快进/快退预览卡：缩略图 + 方向图标 +「目标位置 / 总时长」
            AnimatedVisibility(
                visible = scrubText.isNotEmpty(),
                enter = fadeIn(tween(120)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                PlayerScrubCard(forward = scrubForward, text = scrubText, thumb = scrubThumb)
            }

            // 顶部标题栏
            AnimatedVisibility(
                visible = overlayVisible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(240)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                PlayerTopBar()
            }

            // 右侧滑入的「音轨 / 字幕」设置面板
            AnimatedVisibility(
                visible = showSettingsPanel,
                enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 2 },
                exit = fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 2 },
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                SettingsPanel(panelFocus)
            }

            // 底部控制栏
            AnimatedVisibility(
                visible = overlayVisible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(240)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                PlayerOverlay(
                    timelineFocus = timelineFocus,
                    selectorFocus = selectorFocus,
                    speedBtnFocus = speedBtnFocus,
                    aspectBtnFocus = aspectBtnFocus,
                    settingsBtnFocus = settingsBtnFocus
                )
            }

            // 下一集预告卡（右下、控制栏上方）
            AnimatedVisibility(
                visible = nextCardVisible,
                enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 3 },
                exit = fadeOut(tween(200)) + slideOutHorizontally(tween(220)) { it / 3 },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 200.dp)
            ) {
                Box(Modifier.onFocusChanged { if (it.hasFocus) focusZone = FocusZone.CARD }) {
                    playlist.getOrNull(currentIndex + 1)?.let { next ->
                        PlayerNextCard(
                            title = next.name,
                            indexLabel = "第 ${currentIndex + 2} / ${playlist.size} 个",
                            countdownSec = nextCountdownSec,
                            thumb = nextThumb,
                            playFocus = cardPlayFocus,
                            onPlay = {
                                skipTo(currentIndex + 1)
                                lastInteractionTick++
                            },
                            onCancel = {
                                hideNextCard()
                                showBadge(null, "已取消自动连播")
                            }
                        )
                    }
                }
            }

            if (showResumeDialog) ResumeDialog()
        }
    }

    @Composable
    private fun PlayerTopBar() {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))
                )
                .padding(horizontal = 48.dp, vertical = 26.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "第 ${currentIndex + 1} / ${playlist.size} 个",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Text(
                currentTitle,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (speed != 1.0f) {
                Spacer(Modifier.width(16.dp))
                Text(
                    "${speed}x",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    /**
     * 底部控制栏（时间轴优先，v1.10）：内联选择器 → 时间轴 → 按钮行，自上而下。
     */
    @Composable
    private fun PlayerOverlay(
        timelineFocus: FocusRequester,
        selectorFocus: FocusRequester,
        speedBtnFocus: FocusRequester,
        aspectBtnFocus: FocusRequester,
        settingsBtnFocus: FocusRequester
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000)))
                )
                .padding(horizontal = 48.dp, vertical = 30.dp)
        ) {
            // ——— 内联选择器（倍速/比例）：时间轴上方横排胶囊 ———
            AnimatedVisibility(
                visible = inlineSelector != InlineSelector.NONE,
                enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(200))
            ) {
                InlineSelectorRow(selectorFocus)
            }

            // ——— 时间轴（控制栏主角） ———
            TimelineRow(timelineFocus)

            Spacer(Modifier.height(20.dp))

            // ——— 按钮行 ———
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.onFocusChanged { if (it.hasFocus) focusZone = FocusZone.BUTTONS }
            ) {
                PlayerIconButton(
                    icon = PlayerIconType.PREV,
                    label = "上一集",
                    enabled = hasPrev
                ) {
                    skipTo(currentIndex - 1)
                    lastInteractionTick++
                }

                Spacer(Modifier.width(10.dp))

                PlayerIconButton(PlayerIconType.REWIND, "快退 10 秒") {
                    showSeekBadge(-1, seekBy(-SEEK_STEP_MS))
                    lastInteractionTick++
                }

                Spacer(Modifier.width(10.dp))

                PlayerIconButton(
                    // 图标表示「按下去会发生什么」：播放中显示 ‖（点了会暂停）、暂停/结束显示 ▶（点了会播放）。
                    icon = playPauseIcon(),
                    label = when {
                        ended -> "重播"
                        isPlaying -> "播放中"
                        else -> "已暂停"
                    },
                    size = 60.dp,
                    emphasized = true
                ) {
                    togglePlayPause()
                    lastInteractionTick++
                }

                Spacer(Modifier.width(10.dp))

                PlayerIconButton(PlayerIconType.FORWARD, "快进 10 秒") {
                    showSeekBadge(1, seekBy(SEEK_STEP_MS))
                    lastInteractionTick++
                }

                Spacer(Modifier.width(10.dp))

                PlayerIconButton(
                    icon = PlayerIconType.NEXT,
                    label = "下一集",
                    enabled = hasNext
                ) {
                    skipTo(currentIndex + 1)
                    lastInteractionTick++
                }

                Spacer(Modifier.width(22.dp))
                Box(
                    Modifier
                        .size(width = 1.dp, height = 34.dp)
                        .background(Color.White.copy(alpha = 0.18f))
                )
                Spacer(Modifier.width(22.dp))

                PlayerTextButton(
                    text = if (speed == 1.0f) "倍速" else "${speed}x",
                    modifier = Modifier.focusRequester(speedBtnFocus)
                ) {
                    inlineSelector = InlineSelector.SPEED
                    lastInteractionTick++
                }

                Spacer(Modifier.width(10.dp))

                PlayerTextButton(
                    // 与倍速同一套约定：非默认值时直接显示当前值（原始/拉伸/裁剪）
                    text = if (aspect == AspectRatio.ORIGINAL) "比例" else aspect.label,
                    modifier = Modifier.focusRequester(aspectBtnFocus)
                ) {
                    inlineSelector = InlineSelector.ASPECT
                    lastInteractionTick++
                }

                Spacer(Modifier.width(10.dp))

                PlayerIconButton(
                    icon = PlayerIconType.SETTINGS,
                    label = "音轨与字幕",
                    modifier = Modifier.focusRequester(settingsBtnFocus)
                ) {
                    showSettingsPanel = true
                    lastInteractionTick++
                }

                Spacer(Modifier.weight(1f))
            }
        }
    }

    /** 内联选择器（倍速 / 画面比例）：横排胶囊，OK 即选即生效并收起 */
    @Composable
    private fun InlineSelectorRow(selectorFocus: FocusRequester) {
        Row(
            Modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.hasFocus) focusZone = FocusZone.SELECTOR }
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 必须用 when 精确匹配而非 if/else：关闭时（NONE）AnimatedVisibility 的
            // 收起动画期间内容仍在组合，if/else 会让 NONE 走 else 分支闪现成比例胶囊
            //（实测：倍速弹框按返回/上键的收起动画中瞬间变成比例弹框）。
            when (inlineSelector) {
                InlineSelector.SPEED -> SPEED_OPTIONS.forEach { s ->
                    SelectorPill(
                        text = if (s == 1.0f) "正常" else "${s}x",
                        selected = s == speed,
                        // 打开时焦点落到当前选中项
                        modifier = if (s == speed) Modifier.focusRequester(selectorFocus) else Modifier
                    ) {
                        speed = s
                        player.setPlaybackSpeed(s)
                        closeSelector()
                    }
                }
                InlineSelector.ASPECT -> AspectRatio.entries.forEach { a ->
                    SelectorPill(
                        text = a.label,
                        selected = a == aspect,
                        modifier = if (a == aspect) Modifier.focusRequester(selectorFocus) else Modifier
                    ) {
                        aspect = a
                        playerView?.resizeMode = resizeModeOf(a)
                        closeSelector()
                    }
                }
                InlineSelector.NONE -> Unit
            }
        }
    }

    /**
     * 时间轴行：当前时间 + 进度条 + 总时长。进度条区域是**可聚焦**的（控制栏主角）：
     * 聚焦时轨道加粗 + 主色光环（[PlayerProgressBar] 的 focused 态），
     * 左右 = 拖动（与控制栏隐藏时的快进/快退同一套固定 10 秒步长/变速扫描），
     * OK = 播放/暂停。上下键交给焦点系统在「时间轴 ↔ 按钮行」间换轨。
     */
    @Composable
    private fun TimelineRow(timelineFocus: FocusRequester) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                FileUtils.formatDuration(positionMs),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                modifier = Modifier.width(84.dp)
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .focusRequester(timelineFocus)
                    .onFocusChanged { if (it.isFocused) focusZone = FocusZone.TIMELINE }
                    .focusable()
                    .onPreviewKeyEvent { onTimelineKey(it) },
                contentAlignment = Alignment.Center
            ) {
                PlayerProgressBar(
                    position = positionMs,
                    buffered = bufferedMs,
                    duration = durationMs,
                    modifier = Modifier.fillMaxWidth(),
                    focused = focusZone == FocusZone.TIMELINE
                )
            }
            Text(
                FileUtils.formatDuration(durationMs),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.End,
                modifier = Modifier.width(84.dp)
            )
        }
    }

    /** 时间轴聚焦时的按键：左右 = 快退/快进（复用固定 10 秒步长 + 变速扫描），OK = 播放/暂停 */
    private fun onTimelineKey(event: KeyEvent): Boolean {
        val down = event.type == KeyEventType.KeyDown
        return when (event.key) {
            Key.DirectionLeft, Key.DirectionRight -> {
                val dir = if (event.key == Key.DirectionRight) 1 else -1
                if (down) onSeekDown(dir) else onSeekUp(dir)
                true
            }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (down) togglePlayPause()
                true
            }
            else -> false
        }
    }

    /**
     * 右侧「音轨 / 字幕」设置面板：两个分区纵向排布，轨道多时可滚动。
     * OK 即选即生效并收起；Back 收起不改动。
     */
    @Composable
    private fun SettingsPanel(panelFocus: FocusRequester) {
        // 面板每次打开都重算（内容随组合离开作用域被丢弃）
        val audioGroups = remember { trackGroups(C.TRACK_TYPE_AUDIO) }
        val textGroups = remember { trackGroups(C.TRACK_TYPE_TEXT) }
        val audioTracks = audioGroups.flatMapIndexed { gi, g -> (0 until g.length).map { gi to it } }
        val textTracks = textGroups.flatMapIndexed { gi, g -> (0 until g.length).map { gi to it } }

        Column(
            Modifier
                .width(340.dp)
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF20B0F16))
                .padding(vertical = 16.dp, horizontal = 12.dp)
                .onFocusChanged { if (it.hasFocus) focusZone = FocusZone.PANEL }
                .verticalScroll(rememberScrollState())
        ) {
            PanelSectionHeader(PlayerIconType.AUDIO, "音轨")
            if (audioTracks.isEmpty()) {
                PanelHint("（无可切换音轨）")
            } else {
                audioTracks.forEachIndexed { i, (gi, ti) ->
                    val group = audioGroups[gi]
                    val format = group.getTrackFormat(ti)
                    val label = format.label
                        ?: format.language?.uppercase()
                        ?: format.sampleMimeType?.substringAfterLast('/')
                        ?: "音轨 ${i + 1}"
                    OptionRow(
                        text = label,
                        selected = group.isTrackSelected(ti),
                        modifier = if (i == 0) Modifier.focusRequester(panelFocus) else Modifier
                    ) {
                        selectTrack(C.TRACK_TYPE_AUDIO, group, ti)
                        closeSettingsPanel()
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            PanelSectionHeader(PlayerIconType.SUBTITLE, "字幕")
            OptionRow(
                text = "关闭字幕",
                selected = !hasSelectedTrack(textGroups),
                // 无音轨时面板首个可聚焦行是「关闭字幕」
                modifier = if (audioTracks.isEmpty()) Modifier.focusRequester(panelFocus) else Modifier
            ) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                closeSettingsPanel()
            }
            if (textTracks.isEmpty()) {
                PanelHint("（无内嵌或外挂字幕轨道）")
            } else {
                textTracks.forEachIndexed { i, (gi, ti) ->
                    val group = textGroups[gi]
                    val format = group.getTrackFormat(ti)
                    val label = format.label
                        ?: format.language?.uppercase()
                        ?: format.sampleMimeType?.substringAfterLast('/')
                        ?: "字幕 ${i + 1}"
                    OptionRow(
                        text = label,
                        selected = group.isTrackSelected(ti)
                    ) {
                        selectTrack(C.TRACK_TYPE_TEXT, group, ti)
                        closeSettingsPanel()
                    }
                }
            }
        }
    }

    @Composable
    private fun PanelSectionHeader(icon: PlayerIconType, title: String) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PlayerIconGlyph(icon, MaterialTheme.colorScheme.primary, Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
    }

    @Composable
    private fun PanelHint(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }

    @Composable
    private fun ResumeDialog() {
        TvDialog(onDismiss = { finish() }) {
            Text("续播", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text("上次看到 ${FileUtils.formatDuration(pendingResumePosition)}，是否继续播放？")
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("继续播放") {
                    showResumeDialog = false
                    startPlayback(pendingResumePosition)
                }
                TvButton("从头播放") {
                    showResumeDialog = false
                    startPlayback(0L)
                }
                TvButton("取消") { finish() }
            }
        }
    }

    private fun trackGroups(type: Int): List<Tracks.Group> =
        player.currentTracks.groups.filter { it.type == type }

    private fun hasSelectedTrack(groups: List<Tracks.Group>): Boolean =
        groups.any { g -> (0 until g.length).any { g.isTrackSelected(it) } }

    private fun selectTrack(type: Int, group: Tracks.Group, trackIndex: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    @Composable
    private fun TvDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(28.dp).width(340.dp)) { content() }
            }
        }
    }
}
