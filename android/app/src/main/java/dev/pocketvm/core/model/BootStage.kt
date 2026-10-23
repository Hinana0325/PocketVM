package dev.pocketvm.core.model

/**
 * How far the guest has got, inferred from outside the machine.
 *
 * PocketVM never renders the guest screen, so the only evidence of progress is
 * the firmware's serial console plus the child process's CPU accounting. Making
 * that explicit is what turns "启动" from a button into an answerable question:
 * without it the user gets a PID and an empty log pane while Windows does — or
 * fails to do — everything off-screen, with no way to tell which.
 *
 * The stages are ordered as a launch actually proceeds, and only ever move
 * forward — see VmSession.publishStage.
 */
enum class BootStage(val label: String, val detail: String) {

    /** QEMU is up; the guest console has not produced a byte yet. */
    FIRMWARE(
        "固件启动中",
        "QEMU 已就绪，等待 UEFI 固件在串口上输出",
    ),

    /** The console is talking, so firmware or a boot loader is executing. */
    LOADING(
        "正在加载引导程序",
        "固件已选中启动项并开始加载安装介质",
    ),

    /**
     * The installer asked for a keypress before booting from the CD and we
     * answered it. This is the step a phone user can never catch by hand.
     */
    BOOT_PROMPT(
        "已应答安装介质提示",
        "安装程序要求「按任意键从 CD 启动」，已自动回车",
    ),

    /** Firmware fell into the UEFI Shell; the EFI boot command was typed in. */
    SHELL_RESCUE(
        "UEFI Shell 自动引导",
        "固件没选中启动项而进了 Shell，已通过串口输入引导命令",
    ),
}