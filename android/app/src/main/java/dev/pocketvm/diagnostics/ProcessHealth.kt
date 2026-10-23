package dev.pocketvm.diagnostics

import java.io.File

/**
 * 进程健康探针。
 *
 * `Process.isAlive` 说明不了什么：QEMU 的主循环可以卡死在一个阻塞调用里，
 * 进程还在、监听套接字还在 LISTEN、TCP 也能完成握手，但用户态不再 accept，
 * 客户端连上去一个字节都收不到。外部看到的现象和"正常运行"一模一样。
 *
 * 客观信号只有一个：内核记的 CPU 时间。真在跑的 TCG 客户机会持续消耗 CPU，
 * 卡死的进程是 0。真机上实测到过 4 秒 0 个 tick 的状态，而当时 App 显示的
 * 是「运行中」。
 *
 * 读 `/proc/<pid>/stat` 需要与目标同 UID —— QEMU 是本进程的子进程，满足；
 * 若 ROM 的 SELinux 策略不允许，[sample] 返回 null，判据一并返回 null
 * （即"不下结论"），不会误报。
 */
object ProcessHealth {

    data class Sample(val epochMs: Long, val cpuTicks: Long)

    /**
     * @return utime+stime（内核 tick，通常 100 Hz），进程不存在或不可读时为 null。
     */
    fun sample(pid: Int, epochMs: Long = System.currentTimeMillis()): Sample? {
        if (pid <= 0) return null
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        // comm 字段被括号包着且理论上可含空格，所以从最后一个 ')' 之后开始切。
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(' ')
        // state 是第 3 个字段，所以 utime(14) = fields[11]、stime(15) = fields[12]。
        if (fields.size < 13) return null
        val utime = fields[11].toLongOrNull() ?: return null
        val stime = fields[12].toLongOrNull() ?: return null
        return Sample(epochMs, utime + stime)
    }

    /**
     * 纯判据，便于单测。
     *
     * @param minIntervalMs 两次采样间隔低于此值不下结论：一次调度抖动就足以
     *   让 tick 不推进，那属于噪声而不是卡死。
     * @return true 卡死、false 仍在推进、null 样本不足无法判断。
     */
    fun stalled(prev: Sample?, now: Sample?, minIntervalMs: Long = 5_000): Boolean? {
        if (prev == null || now == null) return null
        if (now.epochMs - prev.epochMs < minIntervalMs) return null
        return now.cpuTicks <= prev.cpuTicks
    }
}