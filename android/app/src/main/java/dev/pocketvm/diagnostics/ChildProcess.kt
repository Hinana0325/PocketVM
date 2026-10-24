package dev.pocketvm.diagnostics

import java.io.File

/**
 * 找到子进程 QEMU 的 PID，不靠反射。
 *
 * `Process.pid()` 看着是标准答案，其实不是：方法本身是 public 的，但实现类
 * （桌面 JVM 的 `ProcessImpl`、ART 的 `UNIXProcess`）不是，于是反射 invoke 在
 * JDK 16+ 直接撞模块封装（IllegalAccessException），背板 `pid` 字段同样够不着，
 * Android 上整个类还是隐藏 API。所有读 `/proc/<pid>` 下 stat、statm、io 的功能——健康探针、
 * 性能仪表——都会在反射失败的设备上静默失效（pidOf 返回 -1，调用方一律
 * `if (pid <= 0) return`）。
 *
 * 内核免费给出答案：每个进程的 ppid 就是 `/proc/<pid>/stat` 的第 4 个字段，
 * 同 UID 的条目可读，而目标就是「自己的直接子进程 + cmdline 里有 qemu」。
 * 这条路在任何 Linux、任何 ART 上都成立，没有 API 级别和模块的坑。
 */
object ChildProcess {

    /**
     * 从 `/proc/<pid>/stat` 取 ppid（全文件第 4 个字段，即 comm 最后一个 ')'
     * 之后的 index 1，index 0 是 state）。comm 解析约定同 [ProcStats.cpuTicks]。
     */
    fun ppidOf(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        val fields = stat.substring(close + 1).trim().split(' ')
        if (fields.size < 2) return null
        return fields[1].toLongOrNull()?.toInt()
    }

    /**
     * @param parentPid 本进程 PID（Android 上取 `android.os.Process.myPid()`）。
     * @param listDir 列目录，默认读真实 /proc；单测注入假 /proc。
     * @param readFile 读文件文本，默认读真实文件系统。
     * @return 子进程 QEMU 的 PID；没有无歧义匹配时返回 -1。
     */
    fun findChildPid(
        parentPid: Int,
        listDir: (String) -> List<String> = { path ->
            runCatching { File(path).list()?.toList() }.getOrNull() ?: emptyList()
        },
        readFile: (String) -> String? = { path ->
            runCatching { File(path).readText() }.getOrNull()
        },
    ): Int {
        val children = mutableListOf<Int>()
        val qemuChildren = mutableListOf<Int>()
        for (entry in listDir("/proc")) {
            val pid = entry.toIntOrNull() ?: continue // self/irq/... 等非数字项
            val stat = readFile("/proc/$pid/stat") ?: continue
            if (ppidOf(stat) != parentPid) continue
            children += pid
            val cmdline = readFile("/proc/$pid/cmdline")
            if (cmdline != null && cmdline.contains("qemu", ignoreCase = true)) {
                qemuChildren += pid
            }
        }
        return when {
            qemuChildren.size == 1 -> qemuChildren[0]
            // 命名不该是承重墙：App 只直接派生子进程这一个，孤儿子进程按构造
            // 就是 QEMU（cmdline 恰好读不到时走这条）。
            qemuChildren.isEmpty() && children.size == 1 -> children[0]
            // 多个子进程且没有一个像 QEMU：不下结论。
            else -> -1
        }
    }
}
