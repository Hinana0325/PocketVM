package dev.pocketvm.runtime

/**
 * 多机同跑的内存预算（纯函数）。
 *
 * 手机的物理内存要同时容纳：所有运行中的 guest 内存 + App 自身 + 系统。
 * 预算是保守闸门而不是承诺：设备内存未知（<=0）时不拦；已知时给系统留
 * [RESERVE_MB]。真正 OOM 的判定以系统为准，这里只挡「明显装不下」的启动。
 */
object SessionBudget {

    const val RESERVE_MB = 1024L

    fun blockReason(
        deviceMemoryMb: Long,
        runningMemoryMb: Long,
        requestedMemoryMb: Int,
    ): String? {
        if (deviceMemoryMb <= 0) return null
        val needed = runningMemoryMb + requestedMemoryMb + RESERVE_MB
        if (needed <= deviceMemoryMb) return null
        return "内存不足：设备 ${deviceMemoryMb} MB，已运行 ${runningMemoryMb} MB，" +
            "再开 ${requestedMemoryMb} MB 需要预留 ${RESERVE_MB} MB。请先停掉一台或调低内存。"
    }
}
