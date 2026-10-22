package dev.pocketvm.core.model

import kotlinx.serialization.Serializable

/**
 * One record per QEMU launch. Written *before* the process starts, so that a
 * crash still leaves a trace of what was executed, and rewritten after exit with
 * the exit code and the matched failure hint.
 */
@Serializable
data class RunManifest(
    val runId: String,
    val vmId: String,
    val vmName: String,
    val appVersion: String,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long? = null,
    val executablePath: String,
    val executableVersion: String? = null,
    val argv: List<String> = emptyList(),
    val firmwareCodePath: String? = null,
    val firmwareVarsPath: String? = null,
    val diskPath: String? = null,
    val diskFormat: String? = null,
    val isoPath: String? = null,
    val vncPort: Int = 0,
    val vncBindAddress: String? = null,
    /** Never record the password itself; only whether auth was armed. */
    val vncPasswordProtected: Boolean = false,
    val qmpSocketPath: String? = null,
    val logPath: String? = null,
    val exitCode: Int? = null,
    val outcome: Outcome = Outcome.STARTING,
    val failureHint: String? = null,
) {
    enum class Outcome {
        STARTING,
        RUNNING,
        EXITED_OK,
        EXITED_ERROR,
        STOPPED_BY_USER,
        LAUNCH_FAILED,
    }
}
