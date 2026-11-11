package dev.pocketvm.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化白名单：H4（系统冻结）的 App 侧缓解。
 *
 * 背景：MIUI/HyperOS 的 PowerKeeper 会用 cgroup freezer 把 App 与 QEMU
 * 所在进程组整体冻结，前台服务 + 唤醒锁都拦不住；唯一有效的办法是把
 * PocketVM 设为电池「无限制」（per-app 白名单）。以前只能靠用户手动进
 * 设置里找，现在 App 可以一键打开系统白名单申请页。
 *
 * 机制：`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 打开系统
 * 弹窗，用户点「允许」即加入白名单；`isIgnoringBatteryOptimizations`
 * 查询当前状态。需要 manifest 声明
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（普通权限，无需运行时申请）。
 * 注意两点：
 *  - 弹窗只是申请，用户仍要点一下「允许」才生效；
 *  - PocketVM 是侧载应用，不走 Play 商店，不受 Play 对该权限的限制政策影响。
 */
object BatteryOptimization {

    fun isWhitelisted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /** 打开系统白名单申请页；从 Application 上下文启动需要 NEW_TASK。 */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
