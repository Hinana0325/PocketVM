package dev.pocketvm.runtime

import android.os.PowerManager

/**
 * vCPU 亲和性的档位。OFF 表示拓扑读不到或没有大小核可分，不做任何绑核。
 */
enum class AffinityMode { OFF, PERFORMANCE, EFFICIENCY }

/**
 * 温控策略：把 PowerManager 的温控状态映射成 vCPU 的亲和性档位。
 *
 * 纯函数，不碰 Android 服务以外的任何东西，便于单测。
 *
 * 档位含义：
 *  - PERFORMANCE：温控正常，vCPU 跑在性能核上（CpuTopology 的性能掩码）。
 *  - EFFICIENCY：机身发热，vCPU 切到能效核上降功耗，降温后自动恢复。
 *    这是「降档」，不是停机：QMP/VNC 所在的 QEMU 主线程不动，控制面
 *    保持响应，Windows 只是变慢，不会丢状态。
 *  - OFF：没有可用的大小核拓扑，温控再热也不动亲和性，只发警告。
 *
 * 绝不自动暂停 VM：Windows 安装/运行中途被 QMP `stop` 冻住，磁盘可能停在
 * 半写状态；过热时该停就由用户在控制台点停止，App 不替他做这个决定。
 */
object ThermalPolicy {

    fun modeFor(status: Int): AffinityMode = when (status) {
        PowerManager.THERMAL_STATUS_NONE,
        PowerManager.THERMAL_STATUS_LIGHT -> AffinityMode.PERFORMANCE
        // 未知状态码（未来 OEM 新增）默认按发热处理：宁可降档，不可硬扛。
        else -> AffinityMode.EFFICIENCY
    }

    fun label(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "正常"
        PowerManager.THERMAL_STATUS_LIGHT -> "轻微发热"
        PowerManager.THERMAL_STATUS_MODERATE -> "中度发热"
        PowerManager.THERMAL_STATUS_SEVERE -> "严重发热"
        PowerManager.THERMAL_STATUS_CRITICAL -> "危急"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "即将过热关机"
        else -> "未知（$status）"
    }

    fun modeLabel(mode: AffinityMode): String = when (mode) {
        AffinityMode.PERFORMANCE -> "性能核"
        AffinityMode.EFFICIENCY -> "能效核"
        AffinityMode.OFF -> "未绑核"
    }

    /** SEVERE 及以上才值得打扰用户；MODERATE 只静默降档。 */
    fun shouldWarn(status: Int): Boolean = status >= PowerManager.THERMAL_STATUS_SEVERE
}
