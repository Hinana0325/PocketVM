package dev.pocketvm

import dev.pocketvm.core.model.BootStage
import dev.pocketvm.ui.CdPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A3a 卡片显示窗口：阶段覆盖、手动关闭、3 分钟安全超时。 */
class CdPromptTest {

    private val start = 1_000_000L

    @Test
    fun `window covers firmware loading and boot prompt`() {
        assertTrue(CdPrompt.stageInWindow(BootStage.FIRMWARE))
        assertTrue(CdPrompt.stageInWindow(BootStage.LOADING))
        assertTrue(CdPrompt.stageInWindow(BootStage.BOOT_PROMPT))
        assertFalse(CdPrompt.stageInWindow(BootStage.SHELL_RESCUE))
        assertFalse(CdPrompt.stageInWindow(null))
    }

    @Test
    fun `card stays visible when stage advances within the window`() {
        listOf(BootStage.FIRMWARE, BootStage.LOADING, BootStage.BOOT_PROMPT).forEach { stage ->
            assertTrue(
                "stage=$stage",
                CdPrompt.shouldShow(
                    running = true,
                    bootFromCd = true,
                    stage = stage,
                    startedAtEpochMs = start,
                    nowMs = start + 30_000,
                    dismissed = false,
                ),
            )
        }
    }

    @Test
    fun `card hides when not running or not booting from cd`() {
        assertFalse(CdPrompt.shouldShow(false, true, BootStage.LOADING, start, start + 1_000, false))
        assertFalse(CdPrompt.shouldShow(true, false, BootStage.LOADING, start, start + 1_000, false))
        assertFalse(CdPrompt.shouldShow(true, true, BootStage.SHELL_RESCUE, start, start + 1_000, false))
    }

    @Test
    fun `manual close hides the card`() {
        assertFalse(
            CdPrompt.shouldShow(
                running = true,
                bootFromCd = true,
                stage = BootStage.BOOT_PROMPT,
                startedAtEpochMs = start,
                nowMs = start + 60_000,
                dismissed = true,
            ),
        )
    }

    @Test
    fun `safe timeout is 180 seconds from run start`() {
        assertFalse(CdPrompt.expired(start, start + 179_999))
        assertTrue(CdPrompt.expired(start, start + 180_000))
        assertFalse(CdPrompt.expired(0L, Long.MAX_VALUE))
        assertFalse(
            CdPrompt.shouldShow(
                running = true,
                bootFromCd = true,
                stage = BootStage.LOADING,
                startedAtEpochMs = start,
                nowMs = start + 180_000,
                dismissed = false,
            ),
        )
    }
}
