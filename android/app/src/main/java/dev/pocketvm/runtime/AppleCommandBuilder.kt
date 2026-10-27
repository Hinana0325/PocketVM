package dev.pocketvm.runtime

import java.io.File

/**
 * iPhone 11（ChefKiss Inferno `t8030` 机型）的启动参数。
 *
 * 与 [QemuCommandBuilder] 完全不同的一套：没有 UEFI pflash、没有 qcow2、没有 ISO，
 * 内核和 DeviceTree 直接从固件目录加载，磁盘是 7 个 NVMe namespace 的 raw 镜像。
 * 参数顺序照 Inferno 官方 `iphone_startup.sh`，少一个 namespace 内核就会在
 * NVMe 初始化阶段 panic。
 */
data class AppleLaunchRequest(
    val executable: String,
    val assetDir: String,
    val cpuCores: Int = 4,
    val memoryMb: Int = 3072,
    val vncPort: Int = 5900,
    val qmpSocketPath: String,
    /** 含 keymaps 的 `-L` 目录；null 时不传。 */
    val dataDir: String? = null,
    val vmName: String = "iPhone 11",
    val extraArgs: List<String> = emptyList(),
)

class AppleCommandBuilder {

    fun build(request: AppleLaunchRequest): List<String> {
        val dir = request.assetDir.trimEnd('/')
        fun path(name: String) = "$dir/$name"
        val args = mutableListOf<String>()
        args += request.executable
        args += listOf("-name", sanitizeName(request.vmName))
        args += listOf(
            "-machine",
            "t8030" +
                ",trustcache=" + escape(path("038-44135-124.dmg.trustcache")) +
                ",ticket=" + escape(path("root_ticket.der")) +
                ",sep-fw=" + escape(path("sep-firmware.n104.RELEASE.new.img4")) +
                ",sep-rom=" + escape(path("AppleSEPROM-Cebu-B1")) +
                ",kaslr-off=true",
        )
        args += listOf("-kernel", path("kernelcache.research.iphone12b"))
        args += listOf("-dtb", path("DeviceTree.n104ap.im4p"))
        // 与官方启动脚本一致：关掉看门狗与一堆 agm 校验，否则 TCG 下启动过程会被
        // watchdog 判死（真机上表现为 wdog panic）。
        args += listOf(
            "-append",
            "tlto_us=-1 mtxspin=-1 agm-genuine=1 agm-authentic=1 agm-trusted=1 " +
                "serial=3 launchd_unsecure_cache=1 wdt=-1",
        )
        args += listOf("-smp", request.cpuCores.coerceIn(1, 8).toString())
        args += listOf("-m", request.memoryMb.coerceAtLeast(1024).toString())
        args += listOf("-drive", "file=${path("sep_nvram")},if=pflash,format=raw")
        args += listOf("-drive", "file=${path("sep_ssc")},if=pflash,format=raw")

        args += nvme("drive.1", path("nvme.1"), nsid = 1, nstype = 1)
        args += nvme("drive.2", path("nvme.2"), nsid = 2, nstype = 2)
        args += nvme("drive.3", path("nvme.3"), nsid = 3, nstype = 3)
        args += nvme("drive.4", path("nvme.4"), nsid = 4, nstype = 4)
        args += listOf("-drive", "file=${path("nvram")},if=none,format=raw,id=nvram")
        args += listOf(
            "-device",
            "apple-nvram,drive=nvram,bus=nvme-bus.0,nsid=5,nstype=5,id=nvram" +
                ",logical_block_size=4096,physical_block_size=4096",
        )
        args += nvme("drive.6", path("nvme.6"), nsid = 6, nstype = 6)
        // nstype 8 = panic log；写成 7 内核会把它当普通分区，启动后找不到 panic log。
        args += nvme("drive.7", path("nvme.7"), nsid = 7, nstype = 8)

        // 串口走 stdio，App 把子进程 stdout 重定向到 run-*.log，控制台直接能看到
        // iOS 内核输出；UEFI 链路用的 unix socket 在这里没有意义。
        args += listOf("-serial", "mon:stdio")
        args += listOf("-vnc", "127.0.0.1:" + (request.vncPort - 5900))
        args += listOf("-display", "none")
        args += listOf("-monitor", "none")
        args += listOf("-qmp", "unix:${request.qmpSocketPath},server=on,wait=off")
        request.dataDir?.let { args += listOf("-L", it) }
        args += request.extraArgs.filter { it.isNotBlank() }
        return args
    }

    private fun nvme(id: String, file: String, nsid: Int, nstype: Int): List<String> = listOf(
        "-drive", "file=$file,if=none,format=raw,id=$id",
        "-device",
        "nvme-ns,drive=$id,bus=nvme-bus.0,nsid=$nsid,nstype=$nstype" +
            ",logical_block_size=4096,physical_block_size=4096",
    )

    /** QEMU 的 `-machine`/`-drive` 解析器按逗号切分，字面逗号写成两个。 */
    private fun escape(value: String): String = value.replace(",", ",,")

    private fun sanitizeName(name: String): String {
        val cleaned = name.replace(',', ' ').trim()
        return cleaned.ifBlank { "iPhone 11" }
    }
}
