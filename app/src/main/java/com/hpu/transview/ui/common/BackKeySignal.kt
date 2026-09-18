package com.hpu.transview.ui.common

import androidx.compose.runtime.mutableIntStateOf

/**
 * Back 键「已经发生了一次」的信号源 —— 真机上「按返回键后焦点无人持有」的兜底复检入口。
 *
 * ## 为什么需要它
 * 真机电视上，按返回键后会出现「焦点既不在顶部标签栏、也不在内容区」的状态：此后方向键只能交给
 * 框架的几何搜索，表现为 **↑ 恒定落到标签行中间那个标签（「图片」，与当前分类无关）、← → ↓ 全无反应**。
 * 要在这个现场把焦点夺回来，必须先知道「刚刚发生了一次 Back」。
 *
 * 而 `BackHandler` 自身**不能**当触发点：真机上第一次按返回时它根本没被执行 —— 系统旧路径
 * （`Activity.onKeyDown` 里 `startTracking()` → `onKeyUp` 里凭 `isTracking()` 调 `onBackPressed()`）
 * 在 DOWN 被吞掉时整条断链，而这正是需要我们兜底的那一次按键。
 *
 * 因此改由 `MainActivity.dispatchKeyEvent`（全工程唯一**无条件**能看到 Back 的地方）在 Back 抬起时
 * 调用 [fire]，主界面把它当 `LaunchedEffect` 的 key 驱动一次「复检 + 兜底」。
 *
 * ## 为什么是 Compose 状态而不是 Flow
 * 当 `LaunchedEffect` 的 key 用，key 变化时 effect 会用**当前组合里的新闭包**重启 —— 不会出现
 * 「协程长期存活、捕获着旧 `selected` 的过期 lambda」这种坑（用 Flow 收集还得额外处理重放与过期闭包）。
 * 代价是每按一次返回键主界面重组一次，电视端可忽略。
 */
object BackKeySignal {

    /**
     * 同一次物理按键的「重复投递」窗口（毫秒）—— 全工程 Back 键去重统一用这一个值。
     *
     * 真机遥控器的**一次按下可能投递出两个抬起**：该遥控器的事件序列与键盘不同（工程已在 OK 键上实测：
     * 按住只送一串重复 `KeyDown` + 最后一个 `KeyUp`）。重复处理一次的后果都是「一次按下 = 两步操作」：
     * * 标签栏上按一次返回 → 先弹「再按一次退出应用」再**立刻退出应用**；
     * * 文件夹里按一次返回 → **连跳两级**，越过本分类根目录看到沙盒总目录（真机症状：图片页从
     *   `windows` 返回后出现 `Pictures` 这个「图片总文件夹」）。
     *
     * 150ms 远低于人类「松开再按下同一个键」的极限，因此窗口内的第二次按键必是同一次物理按键，
     * 丢弃它是安全的；而正常的「连按两次返回键退出」（间隔通常 250ms 以上）不受影响。
     */
    const val DEDUP_MS = 150L

    /** 累计已观测到的 Back 次数（`0` = 还没按过，兜底方应当跳过）。 */
    val tick = mutableIntStateOf(0)

    /** Back 抬起时调用（由 `MainActivity.dispatchKeyEvent` 触发）。 */
    fun fire() {
        tick.intValue++
    }
}
