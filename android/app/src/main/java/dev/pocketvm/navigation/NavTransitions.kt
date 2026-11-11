package dev.pocketvm.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 转场动画：还原旧 AnimatedContent 的滑动 + 淡入淡出方向（见 PocketVmRoot 旧分支）。
 *
 * 前进（进入新页面）：新页从右滑入 + 淡入，旧页向左滑出 + 淡出。
 * 返回（pop 回上一页）：上一页从左滑入 + 淡入，当前页向右滑出 + 淡出。
 *
 * 旧代码的 slide 没有显式 tween（默认 spring），这里照原样不写 tween；
 * fade 用 tween（进入 240ms / 退出 160ms），与旧代码一致。
 * P0 只有两层（首页 ↔ 详情/新建/控制台），返回一律回到首页；P1 加入
 * VmDetail 子页面后，子页面之间的转场方向按同样的前进/返回规则走。
 */
object NavTransitions {

    fun enter(): EnterTransition =
        fadeIn(tween(240)) + slideInHorizontally { it / 8 }

    fun exit(): ExitTransition =
        fadeOut(tween(160)) + slideOutHorizontally { -it / 12 }

    fun popEnter(): EnterTransition =
        fadeIn(tween(240)) + slideInHorizontally { -it / 8 }

    fun popExit(): ExitTransition =
        fadeOut(tween(160)) + slideOutHorizontally { it / 12 }
}
