package dev.pocketvm.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One hostfwd rule: [hostPort] on the phone forwards to [guestPort] inside the
 * VM. TCP only — that covers RDP, SSH and HTTP, which is everything a phone
 * user reaches a headless ARM64 guest for.
 */
@Serializable
data class PortForward(
    val hostPort: Int,
    val guestPort: Int,
)

/**
 * Which display device the guest sees.
 *
 * **ramfb is the default because virtio-gpu cannot boot Windows at all on this
 * firmware.** `VirtioGpuDxe` produces a GOP whose `Mode->FrameBufferBase` and
 * `FrameBufferSize` are both 0 (PixelFormat 3, BGRx): virtio-gpu has no linear
 * framebuffer in guest memory, only 2D blits. Windows' Boot Manager explicitly
 * skips mapping in that case, stores NULL as its destination surface, and the
 * first clear-screen memcpy writes to physical 0 — a same-EL Data Abort (ESR
 * 0x96000047, FAR 0x0) on the firmware exception vector. Measured 2026-10-26 by
 * single-stepping BOOTAA64.EFI with the gdbstub: initializer RVA 0x32ea0,
 * faulting store RVA 0x3990, caller RVA 0x30a68. With `-device ramfb` the same
 * ISO boots to the Windows Setup UI.
 *
 * ramfb is a dumb linear framebuffer: fixed 800x600 by default, raised to
 * 1024x768 via the firmware setup (F10) and persisted in the writable vars
 * pflash. It has no guest driver, which is fine for Windows (Setup and the
 * basic display use the EFI framebuffer) but leaves a Linux guest without a
 * console after handoff — pick virtio-gpu for Linux VMs, where the virtio-gpu
 * DRM driver works.
 */
@Serializable
enum class DisplayDevice(val label: String) {
    /** Dumb linear framebuffer; required for Windows on this firmware. */
    RAMFB("ramfb"),

    /** virtio-gpu-pci: 1280x800 firmware picture; Windows cannot use its GOP. */
    VIRTIO_GPU("virtio-gpu"),
    ;
}

/**
 * Everything PocketVM needs to know about a virtual machine *before* it starts.
 *
 * Paths are stored absolute so that a config can be inspected without resolving
 * the storage layout; the layout still owns the default locations
 * (see [dev.pocketvm.storage.VmStorageLayout]).
 */
@Serializable
data class VmConfig(
    val id: String,
    val name: String,
    val cpuCores: Int = 4,
    val memoryMb: Int = 3072,
    val diskSizeGb: Int = 64,
    /** Absolute path of the imported Windows ARM64 ISO, or null for a disk-only boot. */
    val isoPath: String? = null,
    /**
     * Absolute path of a VirtIO driver ISO (e.g. Fedora's `virtio-win`) attached on
     * the AHCI bus. Stock WinPE has `storahci` and can read it, then "Load Driver"
     * installs `vioscsi` so it can read an `install.wim` sitting on a virtio-scsi CD.
     * This is what makes Windows installable when USB crashes and virtio-scsi is the
     * only bootable bus. Null = no driver disk.
     */
    val driverIsoPath: String? = null,
    /** Absolute path of the UEFI code flash. Null means "pick the first usable firmware". */
    val firmwareCodePath: String? = null,
    val bootFromCd: Boolean = true,
    /** VNC listen port. QEMU's display number is `vncPort - 5900`. */
    val vncPort: Int = 5900,
    /** Loopback by default. LAN is currently unavailable (no DES backend in
     * the QEMU build); the validator rejects it with VNC_LAN_UNAVAILABLE. */
    val vncAccess: VncAccess = VncAccess.LOOPBACK,
    /**
     * VNC password (max 8 chars, RFB truncates). Stored in plain text next to the
     * disk: this is a LAN convenience secret, not a security boundary.
     */
    val vncPassword: String? = null,
    /** See [StorageController]: NVMe works with the stock Windows installer. */
    val storageController: StorageController = StorageController.NVME,
    /**
     * Which bus the install CD sits on — see [CdTransport] for why this decides
     * whether Windows can be booted at all, not just how fast it reads.
     */
    val cdTransport: CdTransport = CdTransport.AUTO,
    /** Keyboard/tablet over USB (true) or virtio (false). See QemuCommandBuilder. */
    val usbInput: Boolean = true,
    /**
     * 彻底禁用 USB：不创建 xHCI 控制器、USB 键鼠和 USB 光驱，光驱固定走
     * virtio-scsi + AHCI、输入固定走 virtio。给「碰到 USB 栈就 SIGILL」的机型
     * 用。代价是本机 virtio 键盘收不到按键（见 AGENTS.md），禁用后只能看画面
     * 或跑无人值守安装。
     */
    val usbDisabled: Boolean = false,
    /**
     * Display device for the guest — see [DisplayDevice]. ramfb is the default:
     * virtio-gpu's GOP has no linear framebuffer and Windows Boot Manager dies
     * on it before Setup starts.
     */
    val displayDevice: DisplayDevice = DisplayDevice.RAMFB,
    /**
     * Firmware serial console + automatic UEFI Shell recovery. Off means no
     * `-serial` at all; keep it on unless you are debugging something else.
     */
    val serialRecovery: Boolean = true,
    /** NIC on/off. Null follows the launch profile. */
    val enableNet: Boolean? = null,
    /**
     * Published TCP ports, phone → guest (QEMU `hostfwd`). Lets a PC reach a
     * service inside the VM — RDP 3389 for Windows, SSH 22 for Linux — without
     * exposing the whole device. The bind address follows [vncAccess]:
     * 127.0.0.1 for LOOPBACK (pair with `adb forward`), 0.0.0.0 for LAN.
     * Ignored when the NIC is off or the option ROM is missing.
     */
    val portForwards: List<PortForward> = emptyList(),
    /** virtio-rng on/off. Null follows the launch profile. */
    val rng: Boolean? = null,
    /**
     * Boot a carved-out El Torito image instead of the CD. Off by default: that
     * image holds only the loader, so Windows Boot Manager cannot find
     * `sources\boot.wim` on it.
     */
    val useBootImage: Boolean = false,
    /** Overrides the launch profile's CPU model (e.g. `max`, `cortex-a76`). */
    val cpuModel: String? = null,
    /**
     * How the launcher may degrade when the preferred acceleration profile fails.
     * [LaunchMode.AUTO] tries 标准 → 兼容 → 最小 and keeps whichever starts.
     */
    val launchMode: LaunchMode = LaunchMode.AUTO,
    /**
     * Escape hatch for experimentation. Arguments are appended verbatim and are
     * *not* validated, because the whole point is to try unmodelled options.
     */
    val extraArgs: List<String> = emptyList(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    companion object {
        /** Name given to the new-VM draft; also the sentinel we overwrite on ISO import. */
        const val DEFAULT_NAME = "Windows 11 ARM64"

        /**
         * Derive a friendly VM name from an ISO filename (e.g.
         * `ubuntu-25.04-live-server-arm64.iso` → "Ubuntu 25.04"). Returns null for
         * an unrecognised/blank name so callers can fall back to keeping the old one.
         */
        fun nameFromIso(isoPath: String?): String? {
            val base = isoPath?.substringAfterLast('/')?.substringBeforeLast('.', "")
            if (base.isNullOrBlank()) return null
            val os = when {
                base.contains("ubuntu", ignoreCase = true) -> "Ubuntu"
                base.contains("debian", ignoreCase = true) -> "Debian"
                base.contains("fedora", ignoreCase = true) -> "Fedora"
                base.contains("arch", ignoreCase = true) -> "Arch"
                base.contains("alpine", ignoreCase = true) -> "Alpine"
                base.contains("kali", ignoreCase = true) -> "Kali"
                base.contains("windows", ignoreCase = true) || base.contains("win", ignoreCase = true) -> "Windows"
                else -> null
            }
            val ver = Regex("""\d+(\.\d+)*""").find(base)?.value
            return buildString {
                append(os ?: base)
                ver?.let { append(" $it") }
            }.trim().takeIf { it.isNotBlank() }
        }

        fun create(name: String): VmConfig = VmConfig(
            id = UUID.randomUUID().toString(),
            name = name,
        )
    }
}
