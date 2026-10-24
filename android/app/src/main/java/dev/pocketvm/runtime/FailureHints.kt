package dev.pocketvm.runtime

/**
 * Turns a QEMU log tail into something a phone user can act on.
 *
 * Every branch here exists because it is a failure mode that actually happens on
 * Android, not because it is theoretically possible.
 */
object FailureHints {

    /**
     * @param configFatal true when the failure comes from the *command line* or
     *   from a path the profiles share, and therefore looks identical for every
     *   acceleration profile. Walking the ladder on one of these only repeats the
     *   same error four more times — see [isConfigFatal].
     */
    private data class Rule(val pattern: Regex, val hint: String, val configFatal: Boolean = false)

    // Order matters: the first matching rule wins, so file-specific patterns must
    // precede the generic "Permission denied" (which really means "cannot exec").
    private val RULES: List<Rule> = listOf(
        Rule(
            Regex("couldn't find \"(.*)\"|library \"(.*)\" not found|CANNOT LINK EXECUTABLE"),
            "动态库缺失。执行层依赖的 libglib/libpixman 等必须与 QEMU 一起放进 jniLibs/arm64-v8a，且 SONAME 必须是 lib*.so。",
            configFatal = true,
        ),
        Rule(
            Regex("Could not open '(.*)': Permission denied"),
            "文件存在但不可读。ISO 需要先导入到应用目录，content:// URI 不能直接交给 QEMU。",
            configFatal = true,
        ),
        Rule(
            Regex("No such file or directory", RegexOption.IGNORE_CASE),
            "缺少文件或动态库。检查固件、ISO、磁盘路径，以及 LD_LIBRARY_PATH 是否指向 nativeLibraryDir。",
            configFatal = true,
        ),
        Rule(
            Regex("cannot execute binary file|Exec format error", RegexOption.IGNORE_CASE),
            "二进制架构不匹配。请确认打包的是 aarch64 版本，而不是 x86_64 或 armv7。",
            configFatal = true,
        ),
        Rule(
            Regex("is not a valid device model name|Parameter 'driver' expects|Bus '.*' not found"),
            "设备模型不可用。这个精简构建没有编入该设备，或设备挂在了不存在的总线上。",
            configFatal = true,
        ),
        // Not fatal: this is precisely what the 兼容/最小 profiles exist to work around.
        Rule(
            Regex("unable to find CPU model|invalid CPU model|Unsupported CPU", RegexOption.IGNORE_CASE),
            "CPU 模型不可用。该构建不支持所选 CPU，请切回「自动」或「标准」模式。",
        ),
        Rule(
            Regex("Invalid parameter|Property '.*' not found|Invalid option", RegexOption.IGNORE_CASE),
            "启动参数不被这个 QEMU 构建支持，完整参数见控制台「查看启动参数」。",
        ),
        Rule(
            Regex("could not initialize|cannot initialize|Failed to initialize", RegexOption.IGNORE_CASE),
            "设备或子系统初始化失败。若是 VNC/显示相关，先检查端口与密码设置。",
        ),
        // QEMU builds its VNC cipher the moment `password=on` is parsed, so a build
        // without a DES-capable crypto backend refuses to start at all: RFB 3.8
        // authenticates with DES. Observed on the Xiaomi 14 Pro as exit=1 before
        // machine init, which is why no other profile can help.
        Rule(
            Regex("Cipher backend does not support DES|no cipher backend|Cipher.*not supported", RegexOption.IGNORE_CASE),
            "这个 QEMU 构建的加密后端不支持 DES，而 RFB 3.8 的 VNC 密码认证就是 DES，所以带密码的 VNC 起不来。" +
                "改用「仅本机」访问并在电脑上做端口转发（adb forward tcp:5900 tcp:5900），" +
                "或在重建执行层时编入支持 DES 的加密后端。",
            configFatal = true,
        ),
        Rule(
            Regex("Failed to bind socket|Failed to create socket|Failed to bind QMP|Operation not permitted"),
            "控制通道创建失败。Unix socket 不能放在 /storage/emulated（FUSE 文件系统），必须放在应用内部存储。",
            configFatal = true,
        ),
        Rule(
            Regex("Permission denied"),
            "权限被拒绝。若发生在启动瞬间，通常是执行层或套接字路径的问题：" +
                "Android 10+ 禁止从可写目录执行文件，Unix socket 也不能放在外部存储。",
            configFatal = true,
        ),
        // Not fatal: shrinking tb-size is exactly the ladder's job.
        Rule(
            Regex("Could not allocate dynamic translator buffer|failed to allocate.*translat|TCG.*exec.*memory", RegexOption.IGNORE_CASE),
            "TCG 无法分配可执行内存（Android 的 W^X/seccomp 限制）。多见于被安全策略收紧的设备，" +
                "可尝试减小 tb-size 或降低内存。",
        ),
        Rule(
            Regex("Failed to lock|Is another process using|Failed to get \"write\" lock"),
            "虚拟磁盘被另一个进程占用。上一次 QEMU 可能没有正常退出，先结束残留进程。",
            configFatal = true,
        ),
        Rule(
            Regex("Address already in use|Could not set up host forwarding"),
            "VNC 端口已被占用。换一个端口，常用区间 5900-5999。",
            configFatal = true,
        ),
        // Not fatal: the minimal profile drops the network and rng devices.
        Rule(
            Regex("Cannot allocate memory|out of memory", RegexOption.IGNORE_CASE),
            "内存不足。降低虚拟机内存，或先关闭后台应用；TCG 还会额外占用内存做代码翻译缓存。",
        ),
        Rule(
            Regex("No space left on device", RegexOption.IGNORE_CASE),
            "存储空间不足。qcow2 是精简置备，但写入时仍会增长。",
            configFatal = true,
        ),
        Rule(
            Regex("failed to find romfile|Could not load PC BIOS|failed to find.*\\.rom", RegexOption.IGNORE_CASE),
            "缺少 QEMU 选项 ROM。ROM 必须放在 -L 数据目录的根下（QEMU 对 BIOS 类型不搜索 pc-bios/ 子目录）。",
            configFatal = true,
        ),
        Rule(
            Regex("requires \\d+ bytes, pflash\\d* block backend provides|pflash.*block backend provides"),
            "固件不是 64 MiB 的 pflash 镜像（当前多半是 2 MiB 的 -bios 文件）。" +
                "virt 的 pflash 固定 64 MiB，请在编辑页重新导入 QEMU_EFI.fd。",
            configFatal = true,
        ),
        Rule(
            Regex("pflash.*(size|mismatch)|invalid size", RegexOption.IGNORE_CASE),
            "UEFI 固件与变量存储大小不一致。两者必须完全等长。",
            configFatal = true,
        ),
        Rule(
            Regex("Could not read keymap|keymaps", RegexOption.IGNORE_CASE),
            "缺少 QEMU 数据目录 (-L)。把 share/qemu 一并打包，或使用静态链接的二进制。",
            configFatal = true,
        ),
        // QEMU's own placeholder (ui/console.c dpy_gfx_replace_surface): the
        // guest cleared the display scanout and never set a new one. Seen on
        // this device after selecting a Linux boot option with virtio-gpu.
        Rule(
            Regex("Display output is not active", RegexOption.IGNORE_CASE),
            "这是 QEMU 的占位画面：客户机（常见于引导器/内核交接 virtio-gpu 时）把显示输出关掉后没有恢复，" +
                "不是故障——Flatcar 等项目在 ARM64 QEMU 上从 GRUB 到登录界面之间本来就会闪一下它。\n" +
                "① 先在 VNC 客户端里按 Ctrl+Alt+F1 强制切换 TTY，画面通常会回来（社区在飞腾 ARM64 上验证）；\n" +
                "② 串口日志才是真相：里面有输出就说明系统还在启动，TCG 下先等几分钟；\n" +
                "③ 想根治：客户机内核命令行加 console=tty0，或在编辑页把显示设备切成 ramfb" +
                "（没有 virtio-gpu 的驱动交接，社区安装 Windows ARM64 都用它）。",
        ),
        // Linux guest-side warning, observed on Ubuntu ARM64 under TCG: the
        // framebuffer console's damage work queue saturates a vCPU because every
        // redraw costs ~100x more than on native silicon. Not a failure — but it
        // is why a graphical installer crawls on this stack.
        Rule(
            Regex("hogged CPU for|WQ_UNBOUND", RegexOption.IGNORE_CASE),
            "客户机内核报告帧缓冲控制台的重绘拖慢了 CPU——TCG 软件模拟下图形控制台的固有开销，不是故障。" +
                "安装阶段优先用文本模式（服务器版安装器默认即是）；装好后禁用 fbcon 或改用串口控制台可缓解。",
        ),
    )

    fun fromLog(logTail: String, exitCode: Int? = null): String? {
        val text = logTail.takeLast(16 * 1024)
        RULES.firstOrNull { it.pattern.containsMatchIn(text) }?.let { return it.hint }
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

    /**
     * Is this failure independent of the acceleration profile?
     *
     * A command-line rejection, a missing file or an unusable socket path produces
     * the same error under 标准/兼容/最小 alike, so continuing down the ladder only
     * multiplies the same failure. On the device, one VNC-password rejection became
     * five identical attempts and a summary that named none of them.
     *
     * Deliberately text-only: signal deaths (exit 132/139) are *not* classified
     * here, because the caller treats those separately (it retries the same profile
     * once without the boot image).
     */
    fun isConfigFatal(logTail: String): Boolean {
        val text = logTail.takeLast(16 * 1024)
        return RULES.any { it.configFatal && it.pattern.containsMatchIn(text) }
    }
}