package dev.pocketvm.core.model

import kotlinx.serialization.Serializable

/** How far the launcher may degrade when the preferred profile fails to start. */
@Serializable
enum class LaunchMode {
    /** Try 标准 → 兼容 → 最小 automatically. */
    AUTO,
    STANDARD,
    COMPAT,
    MINIMAL,
}

/**
 * Acceleration / CPU profile used for one launch attempt.
 *
 * Only knobs that cannot change the guest's storage layout live here: switching
 * the disk controller would make an already-installed Windows unbootable, so a
 * fallback must never do that.
 *
 *  - [accel]: TCG threading and translation cache size. `tb-size` is per-vCPU
 *    code cache; 512 MB fails to allocate on memory-tight or locked-down devices.
 *  - [cpu]: `max` exposes every feature TCG can emulate; older models are a
 *    safer bet when the guest or the host kernel chokes on a feature.
 *  - [rng]/[net]: optional devices. Removing them narrows what can fail during
 *    machine init, at the cost of entropy and networking.
 */
enum class LaunchProfile(
    val label: String,
    val accel: String,
    val cpu: String,
    val rng: Boolean,
    val net: Boolean,
) {
    STANDARD("标准", "tcg,thread=multi,tb-size=512", "max", rng = true, net = true),
    COMPAT("兼容", "tcg,thread=single,tb-size=128", "cortex-a76", rng = false, net = true),
    // cortex-a76 rather than a72: Windows ARM64 requires ARMv8.1 atomics (LSE),
    // which a72 does not have — a rescue profile that cannot boot the guest is
    // worse than useless.
    MINIMAL("最小", "tcg,thread=single,tb-size=64", "cortex-a76", rng = false, net = false),
    ;

    companion object {
        /** Ordered attempts for [mode]; the last one that starts is kept. */
        fun ladder(mode: LaunchMode): List<LaunchProfile> = when (mode) {
            LaunchMode.AUTO -> listOf(STANDARD, COMPAT, MINIMAL)
            LaunchMode.STANDARD -> listOf(STANDARD)
            LaunchMode.COMPAT -> listOf(COMPAT)
            LaunchMode.MINIMAL -> listOf(MINIMAL)
        }
    }
}
