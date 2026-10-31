package dev.pocketvm

import dev.pocketvm.ui.MediaHintTone
import dev.pocketvm.ui.MediaHints
import dev.pocketvm.ui.MediaVersionTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A4 素材版本提示的纯函数测试：按文件名识别，不阻断、不做内容校验。 */
class MediaHintsTest {

    @Test
    fun `22589 recognized as verified`() {
        val info = MediaHints.classifyIso("Win11_ARM64_22589.iso")!!
        assertEquals(MediaVersionTag.VERIFIED, info.tag)
        assertEquals(MediaHintTone.OK, info.tone)
        assertTrue(info.label.contains("22589"))
        assertTrue(info.label.contains("已验证"))
    }

    @Test
    fun `25226 recognized as known bad and only warns`() {
        val info = MediaHints.classifyIso("/sdcard/Download/Win11_ARM64_25226.iso")!!
        assertEquals(MediaVersionTag.KNOWN_BAD, info.tag)
        assertEquals(MediaHintTone.WARNING, info.tone)
        assertTrue(info.label.contains("25226"))
        assertTrue(info.detail.contains("不会硬阻断"))
    }

    @Test
    fun `26300 recognized as unverified neutral`() {
        val info = MediaHints.classifyIso("Windows11_ChinaOnly_arm64_26300_9457.iso")!!
        assertEquals(MediaVersionTag.UNVERIFIED, info.tag)
        assertEquals(MediaHintTone.INFO, info.tone)
        assertTrue(info.label.contains("26300"))
        assertTrue(info.label.contains("未验证"))
    }

    @Test
    fun `other build number is unverified but keeps build`() {
        val info = MediaHints.classifyIso("win11-22631.iso")!!
        assertEquals(MediaVersionTag.UNVERIFIED, info.tag)
        assertEquals(MediaHintTone.INFO, info.tone)
        assertTrue(info.label.contains("22631"))
    }

    @Test
    fun `no build number is unverified and mentions filename heuristic`() {
        val info = MediaHints.classifyIso("windows-arm64.iso")!!
        assertEquals(MediaVersionTag.UNVERIFIED, info.tag)
        assertTrue(info.detail.contains("非内容校验"))
    }

    @Test
    fun `missing iso has no hint`() {
        assertNull(MediaHints.classifyIso(null))
        assertNull(MediaHints.classifyIso(""))
        assertNull(MediaHints.classifyIso("   "))
    }

    @Test
    fun `windows iso without driver gets advice`() {
        val advice = MediaHints.driverAdvice(
            isoPath = "/data/iso/Win11_ARM64_22589.iso",
            driverIsoPath = null,
            vmName = "Windows 11 ARM64",
        )!!
        assertTrue(advice.contains("virtio-win"))
    }

    @Test
    fun `driver mounted suppresses advice`() {
        assertNull(
            MediaHints.driverAdvice(
                isoPath = "/data/iso/Win11_ARM64_22589.iso",
                driverIsoPath = "/data/iso/virtio-win-0.1.302.iso",
                vmName = "Windows 11 ARM64",
            ),
        )
    }

    @Test
    fun `linux iso gets no windows driver advice`() {
        assertNull(
            MediaHints.driverAdvice(
                isoPath = "/data/iso/ubuntu-26.04-arm64.iso",
                driverIsoPath = null,
                vmName = "Ubuntu",
            ),
        )
    }

    @Test
    fun `windows name without win in iso still gets advice`() {
        val advice = MediaHints.driverAdvice(
            isoPath = "/data/iso/install-disk.iso",
            driverIsoPath = null,
            vmName = "Win11 ARM64",
        )
        assertTrue(advice != null)
    }

    @Test
    fun `missing iso gives no driver advice`() {
        assertNull(MediaHints.driverAdvice(null, null, "Windows 11 ARM64"))
    }

    @Test
    fun `new 25226 selection requires one confirmation`() {
        assertTrue(MediaHints.requiresKnownBadConfirmation("/iso/Win11_25226.iso", null))
        assertTrue(MediaHints.requiresKnownBadConfirmation("/iso/Win11_25226.iso", "/iso/Win11_22589.iso"))
    }

    @Test
    fun `same or non-bad path does not re-confirm`() {
        assertFalse(MediaHints.requiresKnownBadConfirmation("/iso/Win11_25226.iso", "/iso/Win11_25226.iso"))
        assertFalse(MediaHints.requiresKnownBadConfirmation("/iso/Win11_22589.iso", null))
        assertFalse(MediaHints.requiresKnownBadConfirmation(null, "/iso/Win11_25226.iso"))
    }
}
