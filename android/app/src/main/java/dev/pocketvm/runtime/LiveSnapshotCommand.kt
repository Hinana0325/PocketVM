package dev.pocketvm.runtime

/** 把 QMP 错误与 HMP 返回文本统一成用户可见的失败原因。 */
internal fun liveSnapshotError(qmpError: String?, monitorOutput: String): String? = when {
    !qmpError.isNullOrBlank() -> qmpError
    monitorOutput.startsWith("Error", ignoreCase = true) -> monitorOutput
    else -> null
}

/**
 * `loadvm` 无论成功失败都尝试恢复客户机运行；恢复失败不能覆盖原始结果。
 * QMP 连接已断开时 `resume` 也会失败，这里同样只保留 `loadvm` 的原始错误。
 */
internal inline fun <T> withGuestResume(resume: () -> Unit, block: () -> T): T {
    try {
        return block()
    } finally {
        runCatching { resume() }
    }
}
