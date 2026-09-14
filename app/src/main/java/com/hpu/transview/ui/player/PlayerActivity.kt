package com.hpu.transview.ui.player

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.core.net.toUri
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
import com.hpu.transview.server.ServerController
import com.hpu.transview.ui.common.OptionRow
import com.hpu.transview.ui.common.TvButton
import com.hpu.transview.ui.settings.SettingsStore
import com.hpu.transview.ui.theme.TransViewTheme
import com.hpu.transview.util.FileUtils
import com.hpu.transview.util.isVideoFile
import com.hpu.transview.util.naturalCompare
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 视频播放器：
 * - Media3 ExoPlayer，支持外挂字幕（同名 .srt/.ass/.vtt）
 * - 播放进度自动入库（Room），再次打开弹出续播提示
 * - 播完自动连播同目录下一个视频（自然排序，EP2 < EP10）
 *
 * 设置页「播放设置」四项在此生效（`SettingsStore`，2026-09-14 接线）：
 * - 自动续播提示：开（默认）→ 有历史进度时弹「续播/从头/取消」；关 → 静默从上次位置继续。
 * - 自动连播：开（默认）→ 播完自动跳下一集；关 → 播完停在片尾（显示「播放结束」，等用户重播/返回）。
 * - 默认倍速：打开播放器时的初始倍速（本次会话内仍可用倍速按钮随时改）。
 * - 默认画面比例：打开播放器时的初始画面比例（控制栏「比例」按钮可随时改，二者同一状态）。
 *
 * 遥控器适配：
 * - 左/右方向键、遥控器 ⏪/⏩ 媒体键：快退/快进。短按 ±10 秒，连续快按累加步长（上限 60 秒），
 *   按住 400ms 后进入变速扫描（2x→4x→8x→16x），松开停止。反馈显示在屏幕中央（方向图标 +
 *   「01:21 / 13:01」目标时间节点，无底色面板），控制栏不会因此弹出 —— 保证连续快进不被打断。
 * - 图标统一按「动作式」显示 —— 图标表示按下去会发生什么：
 *   播放中显示 ‖（按下会暂停）、暂停显示 ▶（按下会播放）、播完显示 ▶（重播）。
 *   控制栏按钮与中央常驻图标共用 `playPauseIcon()`，两处方向永远一致。
 * - 中键：控制栏隐藏时播放/暂停；控制栏显示时确认当前聚焦按钮
 * - 菜单键 / 上 / 下：切换控制栏
 * - 遥控器 ⏯ / ⏭ / ⏮ 媒体键：播放暂停 / 下一集 / 上一集
 * - 返回键：先收控制栏，再退出
 * - 控制栏显示时的左右键是**焦点导航**（不是快进快退），且会刷新自动隐藏计时——
 *   否则用户还在按钮间移动时控制栏就消失了，之后的左右键会突然变成快进/快退。
 * - 「上一集/下一集」无对应集数时置灰，但**仍保留焦点**（`PlayerIconButton` 恒 `clickable(enabled = true)`，
 *   禁用态自己吞掉确定键 + 用灰色描边表示焦点）——否则在「下一集」上按确定键切到最后一集时，
 *   按钮当场变不可聚焦，焦点会掉到根节点、控制栏上一个高亮都不剩。详见 ARCHITECTURE §3.6。
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        /** 返回给媒体库：最后播放的视频路径，用于焦点定位 */
        const val EXTRA_RESULT_PATH = "last_viewed_path"
        private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        /** 短按一次的快进/快退步长 */
        private const val SEEK_STEP_MS = 10_000L

        /** 连续快按的累加步长上限 */
        private const val SEEK_CHAIN_MAX_MS = 60_000L

        /** 判定「连续快按」的时间窗口 */
        private const val SEEK_CHAIN_WINDOW_MS = 1_000L

        /** 按住超过该时长即进入变速扫描 */
        private const val SEEK_LONG_PRESS_MS = 400L

        /** 扫描节拍与每拍推进时长（步长 = 节拍 × 倍率） */
        private const val SEEK_SCAN_INTERVAL_MS = 150L
        private const val SEEK_SCAN_STEP_MS = 150L

        /** 中央徽标停留时长 */
        private const val BADGE_HOLD_MS = 1_200L

        /** 控制栏无操作自动隐藏时长 */
        private const val OVERLAY_AUTO_HIDE_MS = 6_000L

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
    private var playlist: List<File> = emptyList()
    private var currentIndex = 0

    // —— Compose 状态 ——
    var overlayVisible by mutableStateOf(true)
    var showResumeDialog by mutableStateOf(false)
    var showSpeedDialog by mutableStateOf(false)
    var showAspectDialog by mutableStateOf(false)
    var showAudioDialog by mutableStateOf(false)
    var showSubtitleDialog by mutableStateOf(false)
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

    // —— 中央反馈徽标 ——
    private var badgeIcon by mutableStateOf<PlayerIconType?>(null)
    private var badgeText by mutableStateOf("")
    private var badgeTick by mutableStateOf(0)
    private var badgeTickSeq = 0

    private var pendingResumePosition = 0L

    /**
     * PlayerView 实例。画面比例要直接改它的 `resizeMode`，而 `AndroidView(update=…)` 里读 Compose
     * 状态不会建立订阅（update 非组合作用域，状态变化不会触发重绘），因此保存引用后命令式设置。
     */
    private var playerView: PlayerView? = null

    // —— 快进/快退状态 ——
    private var seekJob: Job? = null
    private var scrubStarted = false
    private var seekAccumMs = 0L
    private var lastSeekAt = 0L

    private val currentFile: File? get() = playlist.getOrNull(currentIndex)
    private val hasNext: Boolean get() = currentIndex + 1 < playlist.size
    private val hasPrev: Boolean get() = currentIndex > 0
    private val anyDialogVisible: Boolean
        get() = showResumeDialog || showSpeedDialog || showAspectDialog ||
            showAudioDialog || showSubtitleDialog

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            // 播放期间暂停 HTTP 服务器，防止低性能设备"边播边传"卡顿；暂停/退出自动恢复
            ServerController.setPlaying(playing)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val file = currentFile
                if (file != null) lifecycleScope.launch { repository.clear(file.absolutePath) }
                // 自动连播开启（默认）且还有下一集 → 直接续播；关闭时即使有下一集也停在片尾，
                // 由用户决定「重播」还是按返回离开（与最后一集的收尾表现一致）。
                if (hasNext && SettingsStore.autoPlayNext) {
                    skipTo(currentIndex + 1)
                } else {
                    // 最后一集播到（或被连续快进跨过）片尾：停在末尾并弹出控制栏，
                    // 由用户决定「重播」或按返回离开 —— 避免直接 finish 被误当成闪退。
                    ended = true
                    overlayVisible = true
                    lastInteractionTick++
                    showBadge(
                        null,
                        if (hasNext) "播放结束（自动连播已关闭）" else "播放结束"
                    )
                }
            } else if (ended) {
                // seek / 重播后重新进入准备状态，退出结束态
                ended = false
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val file = currentFile
            if (file != null && !file.exists()) {
                // 文件被外部删除（FileNotFoundException 兜底）：删索引并提示，UI 经 Room Flow 自动刷新
                lifecycleScope.launch {
                    SyncManager.getInstance(this@PlayerActivity).reportMissingFile(file.absolutePath)
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
        val file = if (path != null) File(path) else null
        if (file == null || !file.isFile) {
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
        playlist = file.parentFile
            ?.listFiles()
            ?.filter { it.isFile && it.isVideoFile() }
            ?.sortedWith { a, b -> naturalCompare(a.name, b.name) }
            ?: listOf(file)
        currentIndex = playlist.indexOfFirst { it.absolutePath == file.absolutePath }.takeIf { it >= 0 } ?: 0
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
            val history = repository.get(file.absolutePath)
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
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, it.absolutePath))
        }
    }

    override fun onStop() {
        player.pause()
        saveProgress()
        super.onStop()
    }

    override fun onDestroy() {
        saveProgress()
        ServerController.setPlaying(false)
        player.removeListener(playerListener)
        player.release()
        super.onDestroy()
    }

    // ————— 播放控制 —————

    private fun findSubtitleFiles(video: File): List<MediaItem.SubtitleConfiguration> {
        val base = video.nameWithoutExtension
        return video.parentFile
            ?.listFiles()
            ?.filter {
                it.isFile && it.nameWithoutExtension.equals(base, ignoreCase = true) &&
                    it.extension.lowercase() in setOf("srt", "ass", "ssa", "vtt")
            }
            ?.map { f ->
                val mime = when (f.extension.lowercase()) {
                    "srt" -> MimeTypes.APPLICATION_SUBRIP
                    "vtt" -> MimeTypes.TEXT_VTT
                    else -> MimeTypes.APPLICATION_SS
                }
                MediaItem.SubtitleConfiguration.Builder(f.toUri())
                    .setMimeType(mime)
                    .setLanguage(f.extension)
                    .build()
            }
            ?: emptyList()
    }

    private fun prepareItem(index: Int) {
        val file = playlist.getOrNull(index) ?: return
        currentTitle = file.name
        val item = MediaItem.Builder()
            .setUri(file.toUri())
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
        val pos = player.currentPosition
        val dur = player.duration
        if (dur > 0 && pos > 1000) {
            lifecycleScope.launch {
                repository.save(file.absolutePath, file.name, pos, dur)
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
     * 起播则弹一次「播放中」徽标。
     */
    private fun togglePlayPause() {
        if (ended) {
            // 播完状态下按播放键 = 重播
            ended = false
            player.seekTo(0)
            player.play()
            showBadge(PlayerIconType.PAUSE, "播放中")
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
            showBadge(PlayerIconType.PAUSE, "播放中")
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
     * 快进/快退反馈：方向图标 + 「01:21 / 13:01」（目标位置 / 总时长），
     * 不再显示「快进 N 秒」，方便直接对到具体时间节点。
     */
    private fun showSeekBadge(dir: Int, targetMs: Long) {
        val total = player.duration
        val text = if (total > 0) {
            "${FileUtils.formatDuration(targetMs)} / ${FileUtils.formatDuration(total)}"
        } else {
            FileUtils.formatDuration(targetMs)
        }
        showBadge(if (dir > 0) PlayerIconType.FORWARD else PlayerIconType.REWIND, text)
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
        if (wasScanning) return

        // 短按：连续快按累加步长 10 → 20 → 30 …（上限 60 秒）
        val now = System.currentTimeMillis()
        seekAccumMs = if (now - lastSeekAt < SEEK_CHAIN_WINDOW_MS) {
            (seekAccumMs + SEEK_STEP_MS).coerceAtMost(SEEK_CHAIN_MAX_MS)
        } else {
            SEEK_STEP_MS
        }
        lastSeekAt = now
        showSeekBadge(dir, seekBy(dir * seekAccumMs))
    }

    // ————— 遥控器按键 —————

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

        // 方向左右键：控制栏显示时交给按钮做焦点导航；隐藏时才是快退/快进。
        // 快进只弹中央徽标、不弹控制栏，因此连续快进不会被打断。
        if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
            // 左右键**也要续命**自动隐藏计时：否则按键导航不刷新计时，用户还在按钮之间移动时
            // 控制栏会突然消失，接下来的左右键变成快进/快退（实测踩过：连按 5 次右，中途控制栏
            // 隐藏，后几次直接被当成快进而弹出「02:21 / 13:01」徽标）。控制栏隐藏时自增无副作用，
            // 因为自动隐藏的 LaunchedEffect 只在 overlayVisible 时生效。
            if (down) lastInteractionTick++
            if (overlayVisible || anyDialogVisible) return false
            val dir = if (event.key == Key.DirectionRight) 1 else -1
            if (down) onSeekDown(dir) else onSeekUp(dir)
            return true
        }

        if (!down) return false
        lastInteractionTick++

        return when (event.key) {
            Key.Back -> {
                // 控制栏可见时先收起，再按才退出
                if (overlayVisible && !anyDialogVisible) {
                    overlayVisible = false
                    true
                } else false
            }

            Key.Menu -> {
                overlayVisible = !overlayVisible
                true
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

            Key.DirectionUp, Key.DirectionDown -> {
                if (anyDialogVisible) false else {
                    overlayVisible = !overlayVisible
                    true
                }
            }

            Key.DirectionCenter, Key.Enter -> {
                // 控制栏显示时交给当前聚焦的按钮
                if (overlayVisible || anyDialogVisible) false else {
                    togglePlayPause()
                    true
                }
            }

            else -> false
        }
    }

    // ————— Compose UI —————

    @Composable
    private fun PlayerScreen() {
        val rootFocus = remember { FocusRequester() }
        val playFocus = remember { FocusRequester() }

        // 位置 / 缓冲 / 时长刷新
        LaunchedEffect(Unit) {
            while (isActive) {
                positionMs = player.currentPosition
                bufferedMs = player.bufferedPosition
                if (player.duration > 0) durationMs = player.duration
                delay(500)
            }
        }

        // 控制栏无操作自动隐藏（对话框打开期间不隐藏）
        LaunchedEffect(
            overlayVisible, lastInteractionTick,
            showResumeDialog, showSpeedDialog, showAspectDialog, showAudioDialog, showSubtitleDialog
        ) {
            if (overlayVisible && !anyDialogVisible) {
                delay(OVERLAY_AUTO_HIDE_MS)
                if (!anyDialogVisible) overlayVisible = false
            }
        }

        // 中央徽标自动消失
        LaunchedEffect(badgeTick) {
            if (badgeText.isNotEmpty()) {
                delay(BADGE_HOLD_MS)
                badgeText = ""
                badgeIcon = null
            }
        }

        // 焦点归属：对话框自己管焦点 → 控制栏显示则落到播放/暂停 → 否则回到根节点收键
        LaunchedEffect(
            overlayVisible,
            showResumeDialog, showSpeedDialog, showAspectDialog, showAudioDialog, showSubtitleDialog
        ) {
            when {
                anyDialogVisible -> Unit
                overlayVisible -> {
                    delay(50)
                    runCatching { playFocus.requestFocus() }
                }
                else -> runCatching { rootFocus.requestFocus() }
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
            // 图标与底栏按钮同一套「动作式」规则（暂停时显示 ▶，表示按下去会播放），
            // 两处方向永远一致，不会看起来像「反了」。
            // 有瞬时反馈（快进/快退/起播）时先让位，避免两个图标叠在一起。
            AnimatedVisibility(
                visible = !isPlaying && !ended && badgeText.isEmpty(),
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                PlayerCentralBadge(icon = playPauseIcon(), text = "")
            }

            // 中央瞬时反馈：快进/快退（目标时间节点）、起播、播放结束，控制栏不参与
            AnimatedVisibility(
                visible = badgeText.isNotEmpty(),
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(240)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                PlayerCentralBadge(icon = badgeIcon, text = badgeText)
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

            // 底部控制栏
            AnimatedVisibility(
                visible = overlayVisible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(240)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                PlayerOverlay(playFocus)
            }

            if (showResumeDialog) ResumeDialog()
            if (showSpeedDialog) SpeedDialog()
            if (showAspectDialog) AspectDialog()
            if (showAudioDialog) TrackDialog(C.TRACK_TYPE_AUDIO)
            if (showSubtitleDialog) TrackDialog(C.TRACK_TYPE_TEXT)
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

    @Composable
    private fun PlayerOverlay(playFocus: FocusRequester) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000)))
                )
                .padding(horizontal = 48.dp, vertical = 30.dp)
        ) {
            // ——— 进度条 + 时间 ———
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    FileUtils.formatDuration(positionMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.width(84.dp)
                )
                PlayerProgressBar(
                    position = positionMs,
                    buffered = bufferedMs,
                    duration = durationMs,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    FileUtils.formatDuration(durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(84.dp)
                )
            }

            Spacer(Modifier.height(22.dp))

            // ——— 图标按钮组 ———
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    emphasized = true,
                    modifier = Modifier.focusRequester(playFocus)
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
                    text = if (speed == 1.0f) "倍速" else "${speed}x"
                ) {
                    showSpeedDialog = true
                    lastInteractionTick++
                }

                Spacer(Modifier.width(10.dp))

                PlayerTextButton(
                    // 与倍速同一套约定：非默认值时直接显示当前值（原始/拉伸/裁剪）
                    text = if (aspect == AspectRatio.ORIGINAL) "比例" else aspect.label
                ) {
                    showAspectDialog = true
                    lastInteractionTick++
                }

                Spacer(Modifier.width(10.dp))

                PlayerIconButton(PlayerIconType.AUDIO, "音轨") {
                    showAudioDialog = true
                    lastInteractionTick++
                }

                Spacer(Modifier.width(10.dp))

                PlayerIconButton(PlayerIconType.SUBTITLE, "字幕") {
                    showSubtitleDialog = true
                    lastInteractionTick++
                }

                Spacer(Modifier.weight(1f))
            }
        }
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

    @Composable
    private fun SpeedDialog() {
        TvDialog(onDismiss = { showSpeedDialog = false }) {
            Text("倍速", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            SPEED_OPTIONS.forEach { s ->
                OptionRow(
                    text = if (s == 1.0f) "正常" else "${s}x",
                    selected = s == speed
                ) {
                    speed = s
                    player.setPlaybackSpeed(s)
                    showSpeedDialog = false
                    showBadge(null, "${s}x")
                }
            }
        }
    }

    /** 画面比例：本次播放会话内临时切换，不回写设置页的「默认画面比例」 */
    @Composable
    private fun AspectDialog() {
        TvDialog(onDismiss = { showAspectDialog = false }) {
            Text(
                "画面比例",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            AspectRatio.entries.forEach { a ->
                OptionRow(text = a.label, selected = a == aspect) {
                    aspect = a
                    playerView?.resizeMode = resizeModeOf(a)
                    showAspectDialog = false
                    showBadge(null, a.label)
                }
            }
        }
    }

    /** 音轨 / 字幕选择（遍历 Player 轨道） */
    @Composable
    private fun TrackDialog(trackType: Int) {
        val isAudio = trackType == C.TRACK_TYPE_AUDIO
        val groups = remember { trackGroups(trackType) }
        TvDialog(onDismiss = { if (isAudio) showAudioDialog = false else showSubtitleDialog = false }) {
            Text(
                if (isAudio) "音轨" else "字幕",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            if (!isAudio) {
                OptionRow("关闭字幕", !hasSelectedTrack(groups)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    showSubtitleDialog = false
                }
            }
            groups.forEachIndexed { groupIndex, group ->
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val label = format.label
                        ?: format.language?.uppercase()
                        ?: format.sampleMimeType?.substringAfterLast('/')
                        ?: "${if (isAudio) "音轨" else "字幕"} ${groupIndex + 1}"
                    OptionRow(
                        text = label,
                        selected = group.isTrackSelected(trackIndex)
                    ) {
                        selectTrack(trackType, group, trackIndex)
                        if (isAudio) showAudioDialog = false else showSubtitleDialog = false
                    }
                }
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
