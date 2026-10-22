package dev.pocketvm.storage

import dev.pocketvm.core.model.VmConfig
import dev.pocketvm.runtime.QemuEnvironment
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface DiskResult {
    data class Ready(val file: File, val format: String, val created: Boolean) : DiskResult
    data class Failure(val message: String) : DiskResult
}

/** Result of `qemu-img check` on an image left behind by an unclean shutdown. */
enum class CheckOutcome {
    /** No inconsistency at all. */
    CLEAN,
    /** Leaked clusters or dirty refcounts were repaired in place. */
    REPAIRED,
    /** Corruption we cannot fix; starting the guest would make it worse. */
    BROKEN,
    /** No qemu-img to check with. */
    SKIPPED,
}

data class CheckResult(val outcome: CheckOutcome, val detail: String? = null)

/**
 * Disk creation, always thin.
 *
 * Preferred: `qemu-img create -f qcow2` when the tool is packaged.
 * Fallback: [Qcow2Creator], which writes the same format from Kotlin.
 *
 * There is deliberately **no raw fallback**. A raw 256 GB file inside
 * `/Android/data` sits on FUSE, where sparse holes are not guaranteed to be
 * preserved — the file can silently occupy the full virtual size. qcow2 stays
 * small no matter which filesystem it lands on.
 */
class DiskProvisioner(
    private val qemu: QemuEnvironment,
    private val layout: VmStorageLayout,
) {

    fun ensure(config: VmConfig, disk: File): DiskResult {
        if (disk.isFile && disk.length() > 0) {
            return DiskResult.Ready(disk, detectFormat(disk), created = false)
        }

        disk.parentFile?.mkdirs()
        val qemuImg = qemu.resolveQemuImg(layout)
        if (qemuImg != null && runQemuImg(qemuImg, disk, config.diskSizeGb) == null) {
            return DiskResult.Ready(disk, "qcow2", created = true)
        }
        disk.delete()

        return try {
            Qcow2Creator.create(disk, config.diskSizeGb.toLong() * 1024 * 1024 * 1024)
            DiskResult.Ready(disk, "qcow2", created = true)
        } catch (t: Throwable) {
            DiskResult.Failure("创建虚拟磁盘失败：${t.message}")
        }
    }

    /** Actual bytes on flash — the number the user cares about for a thin disk. */
    fun usedBytes(disk: File): Long = if (disk.isFile) disk.length() else 0L

    /**
     * Consistency check for an image whose last session did not end cleanly.
     *
     * A qcow2 whose process died mid-write usually has leaked clusters, not
     * corruption; `-r leaks` fixes those in place. Real corruption is reported
     * instead of "repaired" so the user can keep the image for recovery rather
     * than booting a guest on top of it.
     */
    fun check(disk: File): CheckResult {
        if (!disk.isFile || disk.length() == 0L) return CheckResult(CheckOutcome.CLEAN)
        val qemuImg = qemu.resolveQemuImg(layout)
        if (qemuImg == null) {
            // No qemu-img in this build: fall back to a header/L1 structural
            // check. It cannot see leaked clusters, but it does catch images
            // truncated by a crash, which is the failure that actually kills a
            // boot. Never report CLEAN here — that would overstate what we know.
            val report = Qcow2Creator.inspect(disk)
            return when (report.verdict) {
                Qcow2Creator.Verdict.OK -> CheckResult(
                    CheckOutcome.SKIPPED,
                    "未打包 qemu-img，仅完成结构校验（未检查泄漏簇）",
                )
                Qcow2Creator.Verdict.BROKEN -> CheckResult(CheckOutcome.BROKEN, report.detail)
                Qcow2Creator.Verdict.UNKNOWN -> CheckResult(CheckOutcome.SKIPPED, report.detail)
            }
        }

        val first = runQemuImgCheck(qemuImg, disk, repair = false)
        if (first.exit == 0) return CheckResult(CheckOutcome.CLEAN)

        val repaired = runQemuImgCheck(qemuImg, disk, repair = true)
        return when {
            repaired.exit == 0 -> CheckResult(
                CheckOutcome.REPAIRED,
                first.output.ifBlank { "已修复泄漏的簇" },
            )
            // 2 = check could not complete, 1 = errors remain.
            else -> CheckResult(
                CheckOutcome.BROKEN,
                repaired.output.ifBlank { "qemu-img check exit=${repaired.exit}" },
            )
        }
    }

    private data class CheckRun(val exit: Int, val output: String)

    private fun runQemuImgCheck(qemuImg: File, disk: File, repair: Boolean): CheckRun {
        val args = buildList {
            add(qemuImg.absolutePath)
            add("check")
            // No -q: the human-readable report is what the failure hint shows.
            if (repair) {
                add("-r")
                add("leaks")
            }
            add(disk.absolutePath)
        }
        return try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .apply { environment().putAll(qemu.childEnvironment(layout)) }
                .start()
            val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            val output = runCatching { outputFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
            if (!finished) {
                process.destroyForcibly()
                CheckRun(-1, "qemu-img check 超时")
            } else {
                // Keep the tail: the summary line ("ERROR ...") is at the end.
                CheckRun(process.exitValue(), output.trim().takeLast(600))
            }
        } catch (t: Throwable) {
            CheckRun(-1, t.message ?: t.javaClass.simpleName)
        }
    }

    /** @return null on success, or the tool output on failure. */
    private fun runQemuImg(qemuImg: File, disk: File, sizeGb: Int): String? {
        return try {
            val process = ProcessBuilder(
                qemuImg.absolutePath,
                "create",
                "-q",
                "-f", "qcow2",
                disk.absolutePath,
                "${sizeGb}G",
            )
                .redirectErrorStream(true)
                .apply { environment().putAll(qemu.childEnvironment(layout)) }
                .start()
            // Drain stdout concurrently: waiting first and reading later can
            // deadlock once the pipe buffer fills, producing a bogus timeout.
            val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            val output = runCatching { outputFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
            when {
                !finished -> {
                    process.destroyForcibly()
                    "qemu-img 超时"
                }
                process.exitValue() != 0 || !disk.isFile -> output.ifBlank { "exit=${process.exitValue()}" }
                else -> null
            }
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    private fun detectFormat(file: File): String = if (Qcow2Creator.isQcow2(file)) "qcow2" else "raw"
}
