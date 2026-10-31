package dev.pocketvm.ui

/** 素材版本提示的语气；只影响文案颜色，不参与启动校验、不做硬阻断。 */
enum class MediaHintTone { OK, INFO, WARNING }

enum class MediaVersionTag { VERIFIED, KNOWN_BAD, UNVERIFIED }

data class MediaVersionInfo(
    val tag: MediaVersionTag,
    val label: String,
    val detail: String,
    val tone: MediaHintTone,
)

/**
 * A4 素材版本提示（P0-A 批次 1）。
 *
 * 只按**文件名**识别构建号，属于启发式提示：不替代校验器、不阻断保存或启动。
 * 22589 是当前 P1 验证版本；25226 已知必坏；26300 及其他版本标「未验证」。
 */
object MediaHints {

    private val buildNumber = Regex("""(\d{5})""")

    fun classifyIso(fileNameOrPath: String?): MediaVersionInfo? {
        val name = fileNameOrPath?.substringAfterLast('/')?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return null
        val build = buildNumber.find(name)?.value
        return when (build) {
            "22589" -> MediaVersionInfo(
                tag = MediaVersionTag.VERIFIED,
                label = "22589 · 已验证",
                detail = "22589 是社区验证可用的 Windows 11 ARM64 版本。",
                tone = MediaHintTone.OK,
            )
            "25226" -> MediaVersionInfo(
                tag = MediaVersionTag.KNOWN_BAD,
                label = "25226 · 已知必坏",
                detail = "25226 启动即异常，建议改用 22589；仅在新选择时确认一次，不会硬阻断。",
                tone = MediaHintTone.WARNING,
            )
            "26300" -> MediaVersionInfo(
                tag = MediaVersionTag.UNVERIFIED,
                label = "26300 · 未验证",
                detail = "26300 未纳入 P1 验证，可用于探索性冒烟，不计入验收。",
                tone = MediaHintTone.INFO,
            )
            null -> MediaVersionInfo(
                tag = MediaVersionTag.UNVERIFIED,
                label = "未验证（版本未识别）",
                detail = "版本按文件名识别，非内容校验；请确认是 Windows 11 ARM64 安装 ISO。",
                tone = MediaHintTone.INFO,
            )
            else -> MediaVersionInfo(
                tag = MediaVersionTag.UNVERIFIED,
                label = "$build · 未验证",
                detail = "非 22589 版本，未纳入 P1 验证；版本按文件名识别，非内容校验。",
                tone = MediaHintTone.INFO,
            )
        }
    }

    /**
     * Windows 类安装 ISO 未挂驱动盘时给建议；Linux 等其他 ISO 不显示，
     * 避免把 virtio-win 提示强加给不需要它的客户机。
     */
    fun driverAdvice(isoPath: String?, driverIsoPath: String?, vmName: String?): String? {
        if (isoPath == null || driverIsoPath != null) return null
        val isoName = isoPath.substringAfterLast('/')
        if (!looksLikeWindows(isoName, vmName)) return null
        return "未挂 virtio-win 驱动盘：WinPE 读不到 install.wim 时，请在「驱动盘」里挂载驱动 ISO。"
    }

    /** 文件名或虚拟机名含 win 时视为 Windows 安装；只用于提示文案。 */
    fun looksLikeWindows(isoNameOrPath: String?, vmName: String?): Boolean {
        val iso = isoNameOrPath?.substringAfterLast('/')?.lowercase().orEmpty()
        val name = vmName?.lowercase().orEmpty()
        return "win" in iso || "win" in name
    }

    /**
     * 本次「新选择」是否为已知必坏的 25226，需要一次二次确认。
     * 同一个已选路径不重复弹；从 25226 改到别的路径也不弹。
     */
    fun requiresKnownBadConfirmation(newPath: String?, previousPath: String?): Boolean =
        newPath != null &&
            newPath != previousPath &&
            classifyIso(newPath)?.tag == MediaVersionTag.KNOWN_BAD
}
