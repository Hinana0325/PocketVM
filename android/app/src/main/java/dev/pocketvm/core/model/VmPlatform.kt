package dev.pocketvm.core.model

import kotlinx.serialization.Serializable

/**
 * 客户机平台。决定用哪个执行层和哪套启动参数：
 *
 * - [UEFI_ARM64]：现有链路，QEMU 11.1.2 + edk2 pflash + qcow2，跑 Windows / Linux。
 * - [APPLE_T8030]：ChefKiss Inferno（QEMU 10.0.2 fork）的 `t8030` 机型，
 *   模拟 iPhone 11（A13），直接加载 iOS 的 kernelcache / DeviceTree，
 *   磁盘是 7 个 NVMe namespace 的 raw 镜像。没有 UEFI、没有 ISO、没有 qcow2。
 */
@Serializable
enum class VmPlatform(val label: String) {
    UEFI_ARM64("Windows / Linux（UEFI）"),
    APPLE_T8030("iPhone 11（iOS）"),
    ANDROID_ARM64("Android（ARM64）"),
}
