package com.hpu.transview.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

/**
 * 矮屏（手机横屏）判据：屏幕高度 < 480dp。
 *
 * 顶部导航栏的紧凑化、上传页左面板的紧凑化、内容区的整体缩放**共用这一个阈值** ——
 * 三处判据必须同值，否则会出现「顶部栏已经紧凑了、内容区还是电视尺度」的割裂感。
 * 手机横屏高度在 360~410dp、电视 720/1080dp、平板横屏 800dp+，都落在阈值两侧，不会误判。
 */
const val COMPACT_SCREEN_HEIGHT_DP = 480

/**
 * 内容区紧凑缩放的 density 乘数。`0.87` → 内容区里所有 dp / sp 等比缩到 **87%**。
 *
 * 方向说明（容易搞反）：`LocalDensity.density` 是「1dp = 多少 px」。**调小**它 →
 * 1dp 对应更少 px → 元素物理尺寸变小；同时屏幕 px 总数不变，于是可容纳的逻辑 dp 数**变多**
 * —— 这正是「内容变小、一屏装下更多」。调大则相反（元素变大）。
 *
 * 取值依据：内容区主体文字是 `bodyLarge` / `titleMedium`（16sp），按 0.87 缩放后约 13.9sp，
 * 正好与顶部导航栏在紧凑模式下使用的 `labelLarge`（14sp）**视觉齐平** ——
 * 而顶部标签栏的尺寸是用户明确认可的基准（「字体和大小正合适」）。
 */
const val COMPACT_CONTENT_DENSITY_SCALE = 0.87f

/**
 * 顶部导航栏在**非紧凑态**下完整排布所需的宽度上限（dp）。窄于此值就必须收窄这一行。
 *
 * 非紧凑态整行 ≈ 行内边距 80 + 品牌标题「传视 TransView」约 150 + 标题后间距 28
 * + 5 个媒体标签约 460（每个 30dp×2 padding + 2 个汉字）+ 标签间距 70
 * + 「设置」标签约 92 + 间距 20 + 状态徽标约 151（圆点 + 「服务器运行中」+ 「· 智能」）
 * ≈ **1050dp**，故本行在窄于 1050dp 时必然装不下。
 *
 * 阈值刻意取 **960**（而非 1050）：
 * - 960dp 是 1080p 电视 / 盒子的**标准最小宽度**；取「严格小于」可保证标准电视**逐像素不变**；
 * - 一旦宽度 < 960dp，就说明一定装不下，此时收窄（隐藏品牌标题、标签改用紧凑样式、徽标只留圆点
 *   → 整行降到约 470dp）严格优于「右侧被裁掉」。
 *
 * 为什么必须有这条判据：Android 16（API 36）起，**最小宽度 >= 600dp 的屏幕会忽略
 * `screenOrientation`** —— 本应用三个 Activity 都锁了 landscape，但平板竖屏 / 桌面窄窗口下
 * 仍可能真的变竖屏，届时本行会被挤出屏幕（右侧「设置」标签与状态徽标直接丢失）。
 * Manifest 里的 `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` 只是 API 36 的临时安全网，
 * **API 37 会失效**，所以宽度自适应必须真正具备。
 */
const val TOP_BAR_FULL_WIDTH_DP = 960

/**
 * 矮屏（手机横屏）下把**内容区**整体等比缩小。
 *
 * ## 为什么是「缩放密度」而不是给每个页面加一套 compact 分支
 *
 * 各页面内部是按电视大屏尺度设计的：正文 `bodyLarge`(16sp)、标题 `titleLarge`(22sp)、
 * 上传页访问码 `headlineSmall`(24sp)，以及 `Modifier.size(40.dp)` 的类型图标、
 * `padding(vertical = 16.dp)` 的设置行、`spacedBy(18.dp)` 的网格间距。
 * 这一批**绝对尺寸**在 1080dp 高的电视上只占屏高的 6%，在 360dp 高的手机横屏上却要占 18%，
 * 表现就是用户反馈的「字看着很大、一屏放不下几条、整体很散」。
 *
 * 若给每个页面单独加 compact 分支（字号一套、间距一套），页面多、条目多，漏一处就不一致，
 * 而且「字号缩小了但间距没缩」会让布局显得更空。这里改为**在内容区统一覆盖 [LocalDensity]**：
 * 内容区里所有 dp / sp **同步等比**缩小，字号与间距/图标/卡片的比例关系完全不变，
 * 视觉上是「整块 UI 变小」，而不是「字变小、留白照旧」。
 *
 * ## 只作用于内容区
 *
 * 覆盖包在**内容区**外层，顶部导航栏在覆盖之外 —— 用户明确认可其尺寸（「标签栏字体和大小正合适」），
 * 保持逐像素不变。TV / 平板（高度 >= [COMPACT_SCREEN_HEIGHT_DP]）不做任何覆盖，同样逐像素不变。
 *
 * 注：`LocalConfiguration` 来自 Activity，不受 [LocalDensity] 覆盖影响，
 * 因此各页面内部继续用 `screenHeightDp < COMPACT_SCREEN_HEIGHT_DP` 判定紧凑是安全且一致的；
 * 而 `BoxWithConstraints` 量出的 `maxWidth/maxHeight` 会随密度一起换算，二者仍然自洽
 * （上传页二维码写的是 `minOf(maxWidth, maxHeight)`，换算后实际像素尺寸不变）。
 */
@Composable
fun CompactContentDensity(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val compact = LocalConfiguration.current.screenHeightDp < COMPACT_SCREEN_HEIGHT_DP
    val scopedDensity = remember(density, compact) {
        if (compact) {
            Density(
                density = density.density * COMPACT_CONTENT_DENSITY_SCALE,
                fontScale = density.fontScale
            )
        } else {
            density
        }
    }
    CompositionLocalProvider(LocalDensity provides scopedDensity) {
        Box(modifier) { content() }
    }
}

/**
 * 内容区**实际**可用的逻辑宽度（dp）—— 供「按宽度决定个数」的计算使用（如媒体库网格列数）。
 *
 * `LocalConfiguration.screenWidthDp` 来自 Activity 配置，**不随 [CompactContentDensity] 的
 * density 覆盖变化**；而覆盖之后屏幕能容纳的**逻辑 dp 数**其实变多了
 * （= 配置值 / [COMPACT_CONTENT_DENSITY_SCALE]）。媒体库按「宽度 / 150」收敛列数，
 * 若继续用未补偿的配置值，这个收敛值会算小，**把用户设置的列数错误地压掉** ——
 * 手机横屏配置值 800dp 只算得 5 列（`coerceAtMost` 于是把「6 列」设置压成 5 列），
 * 而内容区实际可用 919dp、本就能容纳 6 列。
 *
 * 注：媒体库网格列数取的是 `gridColumns.coerceAtMost(收敛值)`，即**用户设置永远是上限**；
 * 本 helper 只保证「收敛值不误伤设置」，**不会**把 5 列自动变成 6 列。
 *
 * TV / 平板不做覆盖，直接返回配置值（与改造前逐像素一致）。
 */
@Composable
fun rememberContentWidthDp(): Int {
    val config = LocalConfiguration.current
    val compact = config.screenHeightDp < COMPACT_SCREEN_HEIGHT_DP
    val confWidth = config.screenWidthDp
    return remember(compact, confWidth) {
        if (compact) (confWidth / COMPACT_CONTENT_DENSITY_SCALE).roundToInt() else confWidth
    }
}

/**
 * 顶部导航栏是否需要收窄。两种情形（任一成立即收窄）：
 *
 * ① **矮屏**（手机横屏，高度 < [COMPACT_SCREEN_HEIGHT_DP]）—— 原有判据，逐像素不变；
 * ② **非矮屏但宽度不足**（宽度 < [TOP_BAR_FULL_WIDTH_DP]，即平板竖屏 / 桌面窄窗口）——
 *    本次新增的兜底，防 Android 16 起大屏方向锁失效后整行被挤出屏幕。
 *
 * 收窄内容：隐藏品牌标题、标签改用紧凑内边距与字号、状态徽标只留指示圆点（详见 MainScreen）。
 *
 * 注意**只作用于顶部导航栏**：内容区的紧凑缩放由 [CompactContentDensity] 独立负责，两者判据不同、
 * 互不影响 —— 例如平板竖屏（高 1000dp）内容区仍按电视尺度排版，但顶部栏必须收窄才不会溢出。
 */
@Composable
fun rememberTopBarTight(): Boolean {
    val config = LocalConfiguration.current
    val compact = config.screenHeightDp < COMPACT_SCREEN_HEIGHT_DP
    val confWidth = config.screenWidthDp
    return remember(compact, confWidth) {
        compact || confWidth < TOP_BAR_FULL_WIDTH_DP
    }
}
