package dev.pocketvm.core.config

import dev.pocketvm.core.model.VmConfig
import java.io.File

enum class Severity { ERROR, WARNING }

data class Issue(val severity: Severity, val code: String, val message: String)

data class ValidationReport(val issues: List<Issue>) {
    val hasErrors: Boolean get() = issues.any { it.severity == Severity.ERROR }
    val errors: List<Issue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<Issue> get() = issues.filter { it.severity == Severity.WARNING }
}

/**
 * Everything the validator needs, already resolved to the filesystem. Keeping the
 * input flat makes this class pure Kotlin and therefore unit-testable on the JVM
 * without an emulator.
 */
data class ValidationInput(
    val config: VmConfig,
    val firmwareCode: File?,
    val firmwareVars: File?,
    val isoFile: File?,
    val diskParentWritable: Boolean,
    val deviceMemoryMb: Long,
    val freeSpaceBytes: Long,
    val qemuAvailable: Boolean,
    /** Other configured VMs, used to catch port clashes before QEMU fails to bind. */
    val otherVms: List<VmConfig> = emptyList(),
    /** Why the execution layer is unusable (probe output), when known. */
    val qemuDetail: String? = null,
)

/**
 * Produces *actionable* issues instead of a boolean. Each code maps to a hint in
 * docs/troubleshooting.md, so the UI can show "为什么会失败" rather than "启动失败".
 */
class ConfigValidator {

    fun validate(input: ValidationInput): ValidationReport {
        val issues = mutableListOf<Issue>()
        val config = input.config

        if (!input.qemuAvailable) {
            issues += Issue(
                Severity.ERROR,
                "QEMU_MISSING",
                if (input.qemuDetail != null) {
                    // The file exists and was executed — saying "not found" here
                    // sent people looking for a missing file that was present.
                    "执行层存在但无法运行：${input.qemuDetail}"
                } else {
                    "未找到 qemu-system-aarch64。请先把执行层放进 jniLibs/arm64-v8a/。"
                },
            )
        }

        if (config.cpuCores !in 1..8) {
            issues += Issue(Severity.ERROR, "CPU_RANGE", "CPU 核心数必须在 1 到 8 之间。")
        } else if (config.cpuCores > 4) {
            issues += Issue(
                Severity.WARNING,
                "CPU_TCG_SCALING",
                "TCG 下超过 4 个 vCPU 通常不会再变快，反而增加调度开销。",
            )
        }

        val maxMemory = (input.deviceMemoryMb * 0.75).toInt()
        when {
            config.memoryMb < 1024 ->
                issues += Issue(Severity.ERROR, "MEM_TOO_SMALL", "内存低于 1024 MB，Windows 无法安装。")
            config.memoryMb > maxMemory ->
                issues += Issue(
                    Severity.ERROR,
                    "MEM_EXCEEDS_DEVICE",
                    "内存 ${config.memoryMb} MB 超过设备可用内存的 75%（约 $maxMemory MB），系统会杀掉 QEMU。",
                )
            config.memoryMb < 4096 ->
                issues += Issue(
                    Severity.WARNING,
                    "MEM_BELOW_WINDOWS_RECOMMENDED",
                    "Windows ARM64 安装程序建议 4096 MB 以上；当前 ${config.memoryMb} MB 可能在展开镜像阶段失败。",
                )
            // TCG adds a translation cache (tb-size) and a block cache on top of
            // guest RAM; 8 GB on a 12 GB phone is the usual shape of "the system
            // killed QEMU after the installer started".
            config.memoryMb > 6144 ->
                issues += Issue(
                    Severity.WARNING,
                    "MEM_TCG_OVERHEAD",
                    "TCG 还要额外占用代码缓存与磁盘缓存，${config.memoryMb} MB 内存容易被系统回收；" +
                        "启动后频繁被杀请降到 4096-6144 MB。",
                )
        }

        when {
            config.diskSizeGb < 20 ->
                issues += Issue(Severity.ERROR, "DISK_TOO_SMALL", "虚拟磁盘至少 20 GB。")
            config.diskSizeGb < 40 ->
                issues += Issue(Severity.WARNING, "DISK_TIGHT", "Windows 安装后剩余空间很少，建议 40 GB 以上。")
        }

        if (!input.diskParentWritable) {
            issues += Issue(Severity.ERROR, "DISK_NOT_WRITABLE", "虚拟机目录不可写，无法创建或打开虚拟磁盘。")
        }

        when {
            // usableSpace returns 0 both for "full" and "query failed"; either
            // way, provisioning is a gamble.
            input.freeSpaceBytes <= 0L ->
                issues += Issue(Severity.WARNING, "DISK_FREE_UNKNOWN", "无法读取设备剩余空间，磁盘扩容可能失败。")
            input.freeSpaceBytes < 2L * 1024 * 1024 * 1024 ->
                issues += Issue(Severity.WARNING, "DISK_FREE_LOW", "设备剩余空间不足 2 GB，qcow2 扩容会失败。")
        }

        val code = input.firmwareCode
        when {
            code == null ->
                issues += Issue(
                    Severity.ERROR,
                    "FIRMWARE_MISSING",
                    "没有可用的 UEFI 固件。请把 QEMU_EFI.fd 放到 firmware/ 目录。",
                )
            !code.isFile ->
                issues += Issue(Severity.ERROR, "FIRMWARE_NOT_FOUND", "固件文件不存在：${code.absolutePath}")
            code.length() < 32L * 1024 * 1024 ->
                issues += Issue(
                    Severity.ERROR,
                    "FIRMWARE_IMPLAUSIBLE",
                    "固件只有 ${code.length() / (1024 * 1024)} MB。virt 的 pflash 需要 64 MiB，" +
                        "2 MiB 的那份是 -bios 镜像，QEMU 会直接报 " +
                        "\"requires 67108864 bytes\"。请在编辑页重新导入 QEMU_EFI.fd。",
                )
        }

        val vars = input.firmwareVars
        if (code != null && code.isFile && vars != null && vars.isFile && vars.length() != code.length()) {
            issues += Issue(
                Severity.ERROR,
                "FIRMWARE_VARS_SIZE_MISMATCH",
                "UEFI 变量存储大小 (${vars.length()}) 必须与固件 (${code.length()}) 一致，否则 pflash 无法映射。",
            )
        }

        if (config.bootFromCd && input.isoFile == null) {
            issues += Issue(
                Severity.WARNING,
                "ISO_MISSING",
                "未选择安装 ISO，将从虚拟磁盘启动。",
            )
        }
        if (input.isoFile != null && !input.isoFile.isFile) {
            issues += Issue(Severity.ERROR, "ISO_NOT_FOUND", "ISO 文件不存在：${input.isoFile.absolutePath}")
        }

        when {
            // QEMU VNC takes a display number: port 5900 is display :0. Below
            // 5900 there is no valid display, so this is an error, not a warning.
            config.vncPort < 5900 ->
                issues += Issue(Severity.ERROR, "VNC_PORT_INVALID", "VNC 端口不能低于 5900（QEMU 以 5900 为显示号 0）。")
            config.vncPort > 5999 ->
                issues += Issue(Severity.WARNING, "VNC_PORT_RANGE", "VNC 端口超出 5900-5999，可能与系统服务冲突。")
        }

        // LAN mode is unavailable in the current QEMU build: its crypto
        // backend has no DES support, so a password-protected VNC server
        // refuses to start (exit=1) before machine init. Reject it here,
        // at validation time, instead of letting QEMU fail at launch.
        // Even with a password, LAN cannot start in this build, so the
        // password check it used to have is subsumed by this error.
        if (config.vncAccess == dev.pocketvm.core.model.VncAccess.LAN) {
            issues += Issue(
                Severity.ERROR,
                "VNC_LAN_UNAVAILABLE",
                "局域网模式暂不可用：当前 QEMU 构建缺少 DES 加密后端，VNC 密码认证无法启动，" +
                    "设置密码也会启动失败。请改用「仅本机」访问，用 adb forward " +
                    "tcp:<端口> tcp:<端口> 后连 127.0.0.1:<端口>；恢复局域网需重建 QEMU 时编入支持 DES 的加密后端。",
            )
        }

        input.otherVms.firstOrNull { it.id != config.id && it.vncPort == config.vncPort }?.let { clash ->
            issues += Issue(
                Severity.WARNING,
                "VNC_PORT_SHARED",
                "与「${clash.name}」使用同一 VNC 端口 ${config.vncPort}。同一时间只能运行一台，" +
                    "但切换虚拟机时客户端不会察觉连到了另一台。",
            )
        }

        config.portForwards.forEach { forward ->
            when {
                // Android apps run as their own UID without CAP_NET_BIND_SERVICE,
                // so QEMU inherits the same restriction: <1024 cannot be bound.
                forward.hostPort < 1024 ->
                    issues += Issue(
                        Severity.ERROR,
                        "FORWARD_HOST_PORT_PRIVILEGED",
                        "手机侧端口 ${forward.hostPort} 低于 1024，Android 应用无权绑定，QEMU 会直接失败。请换 1024 以上的端口。",
                    )
                forward.hostPort > 65535 || forward.guestPort !in 1..65535 ->
                    issues += Issue(
                        Severity.ERROR,
                        "FORWARD_PORT_RANGE",
                        "端口必须在 1-65535 之间（手机侧 ${forward.hostPort} → 客户机 ${forward.guestPort}）。",
                    )
            }
        }
        config.portForwards
            .groupBy { it.hostPort }
            .filter { it.value.size > 1 }
            .forEach { (port, _) ->
                issues += Issue(
                    Severity.WARNING,
                    "FORWARD_DUPLICATE",
                    "手机侧端口 $port 配置了多条转发，只有第一条会生效。",
                )
            }
        input.otherVms.forEach { other ->
            if (other.id == config.id) return@forEach
            config.portForwards.forEach { forward ->
                if (other.portForwards.any { it.hostPort == forward.hostPort }) {
                    issues += Issue(
                        Severity.WARNING,
                        "FORWARD_PORT_SHARED",
                        "与「${other.name}」复用手机侧端口 ${forward.hostPort}。两台同时运行会有一个绑定失败。",
                    )
                }
            }
        }
        if (config.portForwards.isNotEmpty() && config.enableNet == false) {
            issues += Issue(
                Severity.WARNING,
                "FORWARD_NIC_OFF",
                "已配置端口转发但网卡是关闭的，转发不会生效。",
            )
        }

        // Supervision depends on owning these arguments; a user override would
        // silently break stop/log collection instead of failing loudly.
        val reserved = listOf("-qmp", "-display", "-monitor", "-vnc")
        config.extraArgs.forEach { arg ->
            reserved.firstOrNull { arg == it || arg.startsWith("$it=") }?.let {
                issues += Issue(
                    Severity.WARNING,
                    "RESERVED_ARG",
                    "附加参数 `$arg` 会覆盖 PocketVM 的 $it 控制通道，日志与停止功能可能失效。",
                )
            }
        }

        return ValidationReport(issues)
    }
}
