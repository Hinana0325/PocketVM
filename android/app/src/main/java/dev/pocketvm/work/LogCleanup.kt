package dev.pocketvm.work

import dev.pocketvm.storage.VmStorageLayout
import java.io.File

/**
 * 周期性日志清理（P3，WorkManager 每天跑一次）。
 *
 * 背景：每次启动都会在 `vms/<id>/logs/` 留下一组运行文件（`run-<id>.log` /
 * `.serial.log` / `.watchdog.jsonl` / `.json` 清单），从来没人删；启动
 * 报表与诊断包每次都全量读/打包，越用越慢、包也越来越大。清理是唯一
 * 适合定时任务的活——它是可延后的本机文件 IO，不用网、不用前台、死了
 * 也能下次再跑。
 *
 * 安全规则（缺一不可，写死在下面的纯函数里，单元测试覆盖）：
 * - 只删「一次运行」的整组文件（按 runId 分组，组内 4 个文件要么全留
 *   要么全删，不会出现「日志删了、清单还在」的半截状态）；
 * - 组里最新的文件也超过 [MAX_AGE_DAYS] 天；
 * - 删完之后该台 VM 还剩至少 [KEEP_LATEST_RUNS] 组（新装、跑得少的
 *   VM 一组都不删）。
 *
 * 正在跑的 VM 不用单独设防：它的日志正在写，组里最新的文件是几分钟
 * 前的，90 天规则本身就把它排除在外——所以这里没有「24 小时内不动」
 * 的额外条件，那条会被 90 天规则完全覆盖，写了也是死代码。
 *
 * 绝不碰：磁盘（disk.qcow2）、vars.fd、config.json、镜像库、固件、
 * AgentFeed、QMP socket（`run/` 在内部存储里，不在这里扫）。
 *
 * 附带扫掉中断导入的临时文件：IsoImporter 写 `*.part`（取消/进程被杀
 * 时没清掉的）、清单写入中断留下的 `*.tmp`，超过 24 小时没动过才删。
 *
 * 影响：删掉的旧运行不再计入启动报表（成功率/中位数只算剩下的最近
 * 20 次以内），诊断包也不会再打包它们——这是有意的，旧数据本来就
 * 不再用于归因。
 */
object LogCleanup {

    /** 一台 VM 最少保留的最近运行组数（不管多旧）。 */
    const val KEEP_LATEST_RUNS = 20

    /** 运行日志超过这个天数才有资格被删。 */
    const val MAX_AGE_DAYS = 90L

    /** 中断导入留下的临时文件，超过这个时间才算垃圾。 */
    const val STALE_TEMP_HOURS = 24L

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val HOUR_MS = 60L * 60 * 1000

    /** 纯数据快照：计划函数只认它，不直接碰文件，单元测试不用真文件。 */
    data class FileInfo(val name: String, val lastModifiedMs: Long, val lengthBytes: Long)

    /** 一次运行的一组文件（4 个后缀，见 [runIdOf]）。 */
    data class RunSet(val runId: String, val files: List<FileInfo>) {
        val newestMs: Long get() = files.maxOfOrNull { it.lastModifiedMs } ?: 0L
    }

    data class Report(
        val deletedFiles: Int,
        val freedBytes: Long,
        val deletedRunSets: Int,
        val deletedTempFiles: Int,
    )

    /** 一次运行的 4 种文件后缀（按最长优先匹配，避免 `.log` 先吞掉 `.serial.log`）。 */
    private val RUN_SUFFIXES = listOf(".serial.log", ".watchdog.jsonl", ".log", ".json")

    /** 文件名 → runId；不是运行日志返回 null（未知文件一律不动）。 */
    fun runIdOf(fileName: String): String? {
        if (!fileName.startsWith("run-")) return null
        val suffix = RUN_SUFFIXES.firstOrNull { fileName.endsWith(it) } ?: return null
        val id = fileName.removePrefix("run-").removeSuffix(suffix)
        return id.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' || c == '-' } }
    }

    /** 把一个目录的文件列表按 runId 分组；非运行日志不进组。 */
    fun groupRunSets(files: List<FileInfo>): List<RunSet> =
        files.mapNotNull { f -> runIdOf(f.name)?.let { it to f } }
            .groupBy({ it.first }, { it.second })
            .map { (id, fs) -> RunSet(id, fs) }

    /**
     * 纯函数：哪些运行集可以删（见文件头的安全规则）。
     * `runs` 是一台 VM 的全部运行集；返回其中可删的子集。
     */
    fun planRunSets(runs: List<RunSet>, nowMs: Long): List<RunSet> {
        if (runs.isEmpty()) return emptyList()
        val sorted = runs.sortedByDescending { it.newestMs }
        val keep = sorted.take(KEEP_LATEST_RUNS).toSet()
        return sorted.filter { run ->
            run !in keep && nowMs - run.newestMs > MAX_AGE_DAYS * DAY_MS
        }
    }

    /** 纯函数：哪些临时文件可以删（`*.part` / `*.tmp`，24 小时没动过）。 */
    fun planTempFiles(files: List<FileInfo>, nowMs: Long): List<FileInfo> =
        files.filter { it.name.endsWith(".part") || it.name.endsWith(".tmp") }
            .filter { nowMs - it.lastModifiedMs > STALE_TEMP_HOURS * HOUR_MS }

    /** 真实跑一次：扫各台 VM 的 logs 目录与导入目录，按 plan 删。 */
    fun run(layout: VmStorageLayout, nowMs: Long = System.currentTimeMillis()): Report {
        var deletedFiles = 0
        var freedBytes = 0L
        var deletedRunSets = 0
        var deletedTempFiles = 0

        fun delete(file: File): Boolean {
            val size = file.length()
            return if (file.delete()) {
                deletedFiles++
                freedBytes += size
                true
            } else {
                false
            }
        }

        fun infos(dir: File): List<FileInfo> =
            dir.listFiles()?.filter { it.isFile }
                ?.map { FileInfo(it.name, it.lastModified(), it.length()) }
                ?: emptyList()

        // 1. 各台 VM 的运行日志（整组删）与日志目录里的 *.tmp。
        layout.vmsDir.listFiles()?.filter { it.isDirectory }?.forEach { vmDir ->
            val logDir = File(vmDir, "logs")
            val files = infos(logDir)
            val doomedSets = planRunSets(groupRunSets(files), nowMs)
            deletedRunSets += doomedSets.size
            val doomedNames = doomedSets.flatMap { it.files.map { f -> f.name } }.toSet()
            logDir.listFiles()?.filter { it.name in doomedNames }?.forEach { delete(it) }
            planTempFiles(files, nowMs).forEach { info ->
                if (delete(File(logDir, info.name))) deletedTempFiles++
            }
        }
        // 2. 导入目录里的 *.part（ISO/固件导入中断留下的）。
        listOf(layout.imagesDir, layout.firmwareDir).forEach { dir ->
            planTempFiles(infos(dir), nowMs).forEach { info ->
                if (delete(File(dir, info.name))) deletedTempFiles++
            }
        }
        // 3. 各台 VM 目录顶层的 *.part（Android 磁盘镜像导入中断留下的 disk.qcow2.part）。
        layout.vmsDir.listFiles()?.filter { it.isDirectory }?.forEach { vmDir ->
            planTempFiles(infos(vmDir), nowMs).forEach { info ->
                if (delete(File(vmDir, info.name))) deletedTempFiles++
            }
        }
        return Report(deletedFiles, freedBytes, deletedRunSets, deletedTempFiles)
    }
}
