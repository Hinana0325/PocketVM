package dev.pocketvm.storage

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 收集 qemu-img 输出并执行真实超时：输出必须在独立线程读取，否则一个不退出的
 * 子进程会把 `readText()` 永久卡住，后面的 `waitFor(timeout)` 根本没有机会运行。
 */
internal fun runQemuImgProcess(
    process: Process,
    timeoutSec: Long,
    outputGraceMs: Long = 5_000,
): Pair<Int, String> {
    val outputFuture = CompletableFuture.supplyAsync {
        process.inputStream.bufferedReader().use { it.readText() }
    }
    val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        runCatching { process.waitFor(5, TimeUnit.SECONDS) }
        runCatching { outputFuture.get(outputGraceMs, TimeUnit.MILLISECONDS) }
        return -1 to "qemu-img 超时（${timeoutSec}s）"
    }
    val output = runCatching { outputFuture.get(outputGraceMs, TimeUnit.MILLISECONDS) }
        .getOrDefault("")
    return process.exitValue() to output
}
