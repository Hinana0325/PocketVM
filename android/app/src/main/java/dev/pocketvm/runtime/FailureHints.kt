package dev.pocketvm.runtime

/**
 * Turns a QEMU log tail into something a phone user can act on.
 *
 * Every branch here exists because it is a failure mode that actually happens on
 * Android, not because it is theoretically possible.
 */
object FailureHints {

    // Order matters: the first matching rule wins, so file-specific patterns must
    // precede the generic "Permission denied" (which really means "cannot exec").
    private val RULES: List<Pair<Regex, String>> = listOf(
        Regex("couldn't find \"(.*)\"|library \"(.*)\" not found|CANNOT LINK EXECUTABLE") to
            "动态库缺失。执行层依赖的 libglib/libpixman 等必须与 QEMU 一起放进 jniLibs/arm64-v8a，且 SONAME 必须是 lib*.so。",
        Regex("Could not open '(.*)': Permission denied") to
            "文件存在但不可读。ISO 需要先导入到应用目录，content:// URI 不能直接交给 QEMU。",
        Regex("No such file or directory", RegexOption.IGNORE_CASE) to
            "缺少文件或动态库。检查固件、ISO、磁盘路径，以及 LD_LIBRARY_PATH 是否指向 nativeLibraryDir。",
        Regex("cannot execute binary file|Exec format error", RegexOption.IGNORE_CASE) to
            "二进制架构不匹配。请确认打包的是 aarch64 版本，而不是 x86_64 或 armv7。",
        Regex("is not a valid device model name|Parameter 'driver' expects|Bus '.*' not found") to
            "设备模型不可用。这个精简构建没有编入该设备，或设备挂在了不存在的总线上。",
        Regex("unable to find CPU model|invalid CPU model|Unsupported CPU", RegexOption.IGNORE_CASE) to
            "CPU 模型不可用。该构建不支持所选 CPU，请切回「自动」或「标准」模式。",
        Regex("Invalid parameter|Property '.*' not found|Invalid option", RegexOption.IGNORE_CASE) to
            "启动参数不被这个 QEMU 构建支持，完整参数见控制台「查看启动参数」。",
        Regex("could not initialize|cannot initialize|Failed to initialize", RegexOption.IGNORE_CASE) to
            "设备或子系统初始化失败。若是 VNC/显示相关，先检查端口与密码设置。",
        Regex("Failed to bind socket|Failed to create socket|Failed to bind QMP|Operation not permitted") to
            "控制通道创建失败。Unix socket 不能放在 /storage/emulated（FUSE 文件系统），必须放在应用内部存储。",
        Regex("Permission denied") to
            "权限被拒绝。若发生在启动瞬间，通常是执行层或套接字路径的问题：" +
                "Android 10+ 禁止从可写目录执行文件，Unix socket 也不能放在外部存储。",
        Regex("Could not allocate dynamic translator buffer|failed to allocate.*translat|TCG.*exec.*memory", RegexOption.IGNORE_CASE) to
            "TCG 无法分配可执行内存（Android 的 W^X/seccomp 限制）。多见于被安全策略收紧的设备，" +
                "可尝试减小 tb-size 或降低内存。",
        Regex("Failed to lock|Is another process using|Failed to get \"write\" lock") to
            "虚拟磁盘被另一个进程占用。上一次 QEMU 可能没有正常退出，先结束残留进程。",
        Regex("Address already in use|Could not set up host forwarding") to
            "VNC 端口已被占用。换一个端口，常用区间 5900-5999。",
        Regex("Cannot allocate memory|out of memory", RegexOption.IGNORE_CASE) to
            "内存不足。降低虚拟机内存，或先关闭后台应用；TCG 还会额外占用内存做代码翻译缓存。",
        Regex("No space left on device", RegexOption.IGNORE_CASE) to
            "存储空间不足。qcow2 是精简置备，但写入时仍会增长。",
        Regex("pflash.*(size|mismatch)|invalid size", RegexOption.IGNORE_CASE) to
            "UEFI 固件与变量存储大小不一致。两者必须完全等长。",
        Regex("Could not read keymap|keymaps", RegexOption.IGNORE_CASE) to
            "缺少 QEMU 数据目录 (-L)。把 share/qemu 一并打包，或使用静态链接的二进制。",
    )

    fun fromLog(logTail: String, exitCode: Int? = null): String? {
        val text = logTail.takeLast(16 * 1024)
        RULES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.let { return it.second }
        return when (exitCode) {
            0 -> null
            137 -> "进程被系统杀死（SIGKILL），通常是内存压力或省电策略。"
            139 -> "QEMU 启动即段错误（SIGSEGV）。若连 --version 都跑不出来，问题在执行层本身：" +
                "静态链接的 bionic 可执行文件在部分设备上无法自举，需要改成动态链接（PIE），" +
                "由 /system/bin/linker64 加载。可先看「静态执行自检」是否也失败来确认。"
            1 -> "QEMU 以通用错误退出，完整原因见日志最后 20 行。"
            else -> null
        }
    }
}
