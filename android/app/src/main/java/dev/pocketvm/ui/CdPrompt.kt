package dev.pocketvm.ui

import dev.pocketvm.core.model.BootStage

/**
 * A3a「安装介质提示」卡的显示窗口（纯函数，便于单测）。
 *
 * 真机回归发现：点一次 Enter 后阶段从 FIRMWARE 前进到 LOADING，但 CD 的
 * “Press any key” 往往更晚才出现；卡片不能随阶段前进自动消失。这里只在
 * 三个引导阶段显示，并用本次运行开始计时的安全窗口兜底，避免整段运行占首屏。
 */
object CdPrompt {

    /** 安全窗口：从本次运行开始算，3 分钟后卡片自动收起。 */
    const val SAFE_TIMEOUT_MS = 180_000L

    private val WINDOW_STAGES = setOf(
        BootStage.FIRMWARE,
        BootStage.LOADING,
        BootStage.BOOT_PROMPT,
    )

    fun stageInWindow(stage: BootStage?): Boolean = stage in WINDOW_STAGES

    fun expired(startedAtEpochMs: Long, nowMs: Long): Boolean =
        startedAtEpochMs > 0L && nowMs - startedAtEpochMs >= SAFE_TIMEOUT_MS

    fun shouldShow(
        running: Boolean,
        bootFromCd: Boolean,
        stage: BootStage?,
        startedAtEpochMs: Long,
        nowMs: Long,
        dismissed: Boolean,
    ): Boolean = running &&
        bootFromCd &&
        stageInWindow(stage) &&
        !dismissed &&
        !expired(startedAtEpochMs, nowMs)
}
