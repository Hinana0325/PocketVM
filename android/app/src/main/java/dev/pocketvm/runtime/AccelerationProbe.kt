package dev.pocketvm.runtime

import java.io.File
import java.io.FileInputStream
import javax.inject.Inject

/** 探测到的事实（原始信息，不做结论）。 */
data class AccelerationFacts(
    val kvmExists: Boolean,
    val kvmReadable: Boolean,
    val kvmWritable: Boolean,
    /** 尝试以读写打开 /dev/kvm 的错误（null 表示能打开）。 */
    val openError: String?,
    /** /proc/cpuinfo 的 Features 里是否有 hypervisor（说明当前本身跑在虚拟机里）。 */
    val runningUnderHypervisor: Boolean,
    val kernelRelease: String,
)

enum class AccelerationVerdict { AVAILABLE, PRESENT_BUT_DENIED, ABSENT }

/**
 * 硬件加速能力探测（docs/roadmap.md §5：先做成只读诊断，接入决定看探测结果）。
 *
 * 只读：不开设备做 ioctl、不改任何系统设置、不尝试提权。未 Root 的普通 App
 * 即使设备节点存在，SELinux 也通常拒绝打开；把「有节点但打不开」和「根本
 * 没有节点」分开记录，接入评估才有依据。
 */
object AccelerationPolicy {

    fun verdict(facts: AccelerationFacts): AccelerationVerdict = when {
        facts.kvmExists && facts.kvmWritable && facts.openError == null -> AccelerationVerdict.AVAILABLE
        facts.kvmExists -> AccelerationVerdict.PRESENT_BUT_DENIED
        else -> AccelerationVerdict.ABSENT
    }

    fun summary(facts: AccelerationFacts): String = when (verdict(facts)) {
        AccelerationVerdict.AVAILABLE ->
            "/dev/kvm 可读写：设备具备 KVM 能力。但 PocketVM 当前执行层按 TCG 构建，接入 KVM 需要重建执行层并做兼容验证，本次不自动切换。"
        AccelerationVerdict.PRESENT_BUT_DENIED ->
            "/dev/kvm 存在但打不开（${facts.openError ?: "权限不足"}）：未 Root 的 App 受 SELinux/权限限制，" +
                "不能直接使用 KVM。继续使用 TCG 软件模拟。"
        AccelerationVerdict.ABSENT ->
            "没有 /dev/kvm：内核未启用 KVM 或设备不支持。继续使用 TCG 软件模拟。"
    }
}

class AccelerationProbe @Inject constructor() {

    fun probe(): AccelerationFacts {
        val kvm = File("/dev/kvm")
        val exists = kvm.exists()
        var openError: String? = null
        if (exists) {
            openError = runCatching {
                // 只打开并立刻关闭：不读、不 ioctl、不改变设备状态。
                FileInputStream(kvm).use { }
                null
            }.exceptionOrNull()?.let { t -> t.message ?: t.javaClass.simpleName }
        }
        return AccelerationFacts(
            kvmExists = exists,
            kvmReadable = exists && kvm.canRead(),
            kvmWritable = exists && kvm.canWrite(),
            openError = openError,
            runningUnderHypervisor = cpuInfoFeatures()?.contains("hypervisor") == true,
            kernelRelease = runCatching { System.getProperty("os.version").orEmpty() }.getOrDefault(""),
        )
    }

    /** /proc/cpuinfo 的 Features 行；读不到返回 null（不编造）。 */
    private fun cpuInfoFeatures(): String? = runCatching {
        File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("Features") }?.substringAfter(':')
        }
    }.getOrNull()
}
