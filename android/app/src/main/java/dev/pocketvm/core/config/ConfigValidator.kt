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
            code.length() < 256 * 1024 ->
                issues += Issue(
                    Severity.ERROR,
                    "FIRMWARE_IMPLAUSIBLE",
                    "固件只有 ${code.length()} 字节，不像 ARM64 pflash 镜像。",
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

        // LAN without a password is an unauthenticated desktop on the network.
        if (config.vncAccess == dev.pocketvm.core.model.VncAccess.LAN && config.vncPassword.isNullOrBlank()) {
            issues += Issue(
                Severity.ERROR,
                "VNC_LAN_NO_PASSWORD",
                "局域网访问必须设置 VNC 密码，否则网络上的任何人都能直接控制这台虚拟机。",
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
