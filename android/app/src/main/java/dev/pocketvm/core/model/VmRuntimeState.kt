package dev.pocketvm.core.model

/** Lifecycle of the single active VM session. */
sealed interface VmRuntimeState {

    data object Idle : VmRuntimeState

    /**
     * [vmId] is part of every transient state: without it the list screen would
     * show "准备中" on *every* VM card just because one of them is starting.
     * [step] is user-visible progress text, e.g. "创建虚拟磁盘".
     */
    data class Preparing(val vmId: String, val step: String) : VmRuntimeState

    data class Running(
        val vmId: String,
        val pid: Int,
        val startedAtEpochMs: Long,
        val vncPort: Int,
        val logPath: String,
    ) : VmRuntimeState

    data class Stopping(val vmId: String) : VmRuntimeState

    data class Exited(
        val vmId: String,
        val exitCode: Int,
        val hint: String? = null,
        val logPath: String? = null,
    ) : VmRuntimeState

    data class Failed(
        val vmId: String?,
        val reason: String,
        val hint: String? = null,
    ) : VmRuntimeState
}
