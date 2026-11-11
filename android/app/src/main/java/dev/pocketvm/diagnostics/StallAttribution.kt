package dev.pocketvm.diagnostics

/**
 * 停摆归因（P1「可观测/归因」的归因半侧）。
 *
 * Track A 已经把主线程与 vCPU 的 stat 状态字符（R/S/D/…）和 /proc syscall
 * 原始行采进看门狗；这里把这些**已采集的证据**解释成一个只读假设结论。
 * 纯函数、不采样、不重算窗口阈值、不复制 [WatchdogDetector] 的判据——
 * 判定停摆与否仍由 T2 负责，本对象只回答「停摆的话，阻塞点指向哪」。
 *
 * 假设矩阵（见 docs/troubleshooting.md）：
 *  - H1：vCPU 持 BQL 后卡在 FUSE 同步 I/O ⇒ 主线程 S+futex 等锁、某 vCPU=D；
 *  - H2：主线程自身卡在 FUSE 同步 I/O ⇒ 主线程=D；
 *  - H3：主线程 S+futex 等锁但无 vCPU=D ⇒ MTTCG/BQL 锁反转或 PAC shim 候选，
 *        需 thread=single 对照（Track B）或 QEMU backtrace（Track C）区分；
 *  - H4：系统冻结（cgroup freezer）⇒ 看门狗采样间隙远超窗口，App 与 QEMU 同组
 *        被挂起；真机（小米 14 Pro / HyperOS）已证实，不是 QEMU 故障；
 *  - 正常 poll / on-CPU / 其他睡眠 / 证据不足：如实告知，不强行归因。
 *
 * 所有建议都是只读对照或后续实验方向，不在 App 内执行任何动作。
 */
enum class StallHypothesis {
    H1_VCPU_FUSE_BQL,
    H2_MAIN_FUSE_IO,
    H3_LOCK_WAIT,
    H4_SYSTEM_FREEZE,
    NORMAL_POLL,
    MAIN_ON_CPU,
    OTHER_SLEEP,
    INSUFFICIENT,
}

data class StallAttribution(
    val hypothesis: StallHypothesis,
    /** 短标签，供 UI 胶囊与摘要使用。 */
    val label: String,
    /** 一句中文结论。 */
    val conclusion: String,
    /** 只读建议（对照方向 / 后续实验），不构成 App 动作。 */
    val suggestion: String,
    val mainState: String?,
    /** 解码后的主线程 syscall 名（futex/ppoll/pwrite64…）；未知为 null。 */
    val mainSyscallName: String?,
)

object StallAttributor {

    /** arm64 通用 syscall 号；只列停摆判读会用到的，其余回退「syscall#N」。 */
    private const val FUTEX = 98
    private const val PPOLL = 73
    private const val EPOLL_PWAIT = 22

    /**
     * [suspendGapMs] 非 null 表示看门狗采样间隔远超窗口（进程曾被整体挂起）。
     * 这是系统冻结的间接证据，优先于线程状态——冻结期间所有线程都 parked，
     * 线程证据本身已被冻结污染，不能用来归因 QEMU 内部阻塞。
     */
    fun attribute(
        mainState: String?,
        mainSyscall: String?,
        vcpuStates: List<String?> = emptyList(),
        vcpuSyscalls: List<String?> = emptyList(),
        suspendGapMs: Long? = null,
    ): StallAttribution {
        val nr = syscallNumber(mainSyscall)
        val name = syscallName(nr)
        val onCpu = mainState == "R" || mainSyscall?.trim() == "running"
        val vcpuInD = vcpuStates.any { it == "D" }

        fun result(
            hypothesis: StallHypothesis,
            label: String,
            conclusion: String,
            suggestion: String,
        ) = StallAttribution(hypothesis, label, conclusion, suggestion, mainState, name)

        return when {
            suspendGapMs != null -> result(
                StallHypothesis.H4_SYSTEM_FREEZE,
                "H4 · 系统冻结进程",
                "看门狗采样中断了约 ${suspendGapMs / 1000} 秒（远超正常窗口）：App 进程曾被系统整体挂起。" +
                    "真机证据表明该形态对应 MIUI/HyperOS 的 cgroup 冻结——QEMU 与 App 同组被冻，" +
                    "VNC/QMP 失联是冻结的结果，不是 QEMU 主循环故障；解冻后自行恢复。",
                "到系统电池设置把 PocketVM 设为「无限制」（MIUI/HyperOS：设置→应用设置→电池→无限制）；" +
                    "shell 侧可查 cat /sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze（1=冻结中）。",
            )

            mainState == null -> result(
                StallHypothesis.INSUFFICIENT,
                "证据不足",
                "主线程状态不可读（/proc 被 SELinux 拒绝或进程已退出），无法归因。",
                "在能读 /proc 的环境复现；导出诊断包对照 watchdog.jsonl 的 main_state/main_syscall。",
            )

            mainState == "D" -> result(
                StallHypothesis.H2_MAIN_FUSE_IO,
                "H2 · 主线程卡 I/O",
                "主线程处于 D 态（不可中断睡眠），阻塞在同步 I/O" +
                    (name?.let { "（$it）" } ?: "") +
                    "；磁盘落在 FUSE 上，块设备写回可能卡住主循环。",
                "只读对照 vcpu_states 是否也有 D；Track B 可对照磁盘位置（内部 ext4 vs 外部 FUSE）与 cache 模式。",
            )

            onCpu -> result(
                StallHypothesis.MAIN_ON_CPU,
                "主线程在 CPU 上",
                "主线程在 CPU 上运行（R/running），不是阻塞型停摆；可能是主循环自身死循环，或采样恰逢运行。",
                "对照连续多拍 main_thread_dticks 与 QMP/VNC 信号；若持续 R 且无响应，需 QEMU 内部 backtrace（Track C）。",
            )

            mainState == "S" && nr == FUTEX && vcpuInD -> {
                val dSyscall = vcpuStates.zip(vcpuSyscalls)
                    .firstOrNull { it.first == "D" }
                    ?.second
                    ?.let { syscallName(syscallNumber(it)) }
                result(
                    StallHypothesis.H1_VCPU_FUSE_BQL,
                    "H1 · vCPU 持锁卡 I/O",
                    "主线程 S 态阻塞在 futex（等 BQL/锁），同时有 vCPU 处于 D 态" +
                        (dSyscall?.let { "（$it）" } ?: "（同步 I/O）") +
                        "——典型形态是 vCPU 持锁后卡在 FUSE I/O，主线程拿不到锁。",
                    "Track B 用 thread=single 复现对照；若单线程不再停摆，则指向 MTTCG/BQL 持锁阻塞。",
                )
            }

            mainState == "S" && nr == FUTEX -> result(
                StallHypothesis.H3_LOCK_WAIT,
                "H3 · 主线程等锁",
                "主线程 S 态阻塞在 futex（等锁），但没有 vCPU 处于 D 态；指向 MTTCG/BQL 锁等待，" +
                    "PAC 协程 shim 也可能把主循环 parked。",
                "Track B 用 thread=single 对照区分锁反转；若单线程仍停摆，需 QEMU 内部 backtrace（Track C）定位 PAC shim。",
            )

            mainState == "S" && (nr == PPOLL || nr == EPOLL_PWAIT) -> result(
                StallHypothesis.NORMAL_POLL,
                "正常 poll 等待",
                "主线程在正常 " + (name ?: "poll") + " 等待事件，本拍未捕获阻塞点；" +
                    "停摆可能是间歇的，或阻塞发生在 vCPU/锁侧。",
                "对照异常窗口内连续多拍，捕捉主线程离开 poll 的那一拍；结合 vcpu_states 判读。",
            )

            mainState == "S" -> result(
                StallHypothesis.OTHER_SLEEP,
                "主线程其他睡眠",
                "主线程 S 态睡眠在 " + (name ?: "未知 syscall") + "，非典型停摆阻塞点（既非 futex 也非 poll）。",
                "对照完整 watchdog.jsonl 时间线与 vcpu_states；必要时 Track C 取 QEMU backtrace。",
            )

            else -> result(
                StallHypothesis.INSUFFICIENT,
                "证据不足",
                "主线程状态为 $mainState，非常规运行态，证据不足以归因。",
                "导出诊断包对照 watchdog.jsonl 的完整时间线。",
            )
        }
    }

    /**
     * /proc syscall 原始行形如 `98 0x0 0x7fff … 0xsp 0xpc`，首 token 是十进制
     * syscall 号；线程在 CPU 上时为 `running`。读不到/解析不出为 null，不是 0。
     */
    fun syscallNumber(raw: String?): Int? {
        val first = raw?.trim()?.substringBefore(' ') ?: return null
        if (first.isEmpty() || first == "running") return null
        return first.toIntOrNull()
    }

    /** arm64 syscall 号 → 名；未知号回退 `syscall#N`，null 仍是 null。 */
    fun syscallName(nr: Int?): String? = when (nr) {
        null -> null
        22 -> "epoll_pwait"
        29 -> "ioctl"
        35 -> "unlinkat"
        48 -> "faccessat"
        56 -> "openat"
        57 -> "close"
        62 -> "lseek"
        63 -> "read"
        64 -> "write"
        67 -> "pread64"
        68 -> "pwrite64"
        72 -> "pselect6"
        73 -> "ppoll"
        78 -> "readlinkat"
        80 -> "fdatasync"
        82 -> "fsync"
        98 -> "futex"
        101 -> "nanosleep"
        113 -> "clock_gettime"
        124 -> "sched_yield"
        131 -> "sigaltstack"
        134 -> "rt_sigaction"
        135 -> "rt_sigprocmask"
        172 -> "getpid"
        178 -> "gettid"
        215 -> "munmap"
        220 -> "clone"
        222 -> "mmap"
        226 -> "mprotect"
        233 -> "madvise"
        260 -> "wait4"
        278 -> "getrandom"
        else -> "syscall#$nr"
    }
}