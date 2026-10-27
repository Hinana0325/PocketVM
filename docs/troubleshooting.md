# 排障

校验器与日志归因共用同一套错误码。界面显示的是中文原因，这里给的是「怎么修」。

## 校验错误（启动前就被拦住）

| 代码 | 原因 | 处理 |
| --- | --- | --- |
| `QEMU_MISSING` | `nativeLibraryDir` 里没有 `libqemu-system-*.so` | 跑 `scripts/install-qemu.sh`，确认 APK 内含 `lib/arm64-v8a/libqemu-system-aarch64.so` |
| `CPU_RANGE` | 核心数不在 1–8 | 改为 4 左右；TCG 不吃多核 |
| `MEM_TOO_SMALL` | < 1024 MB | Windows 装不上，至少 2048 |
| `MEM_EXCEEDS_DEVICE` | > 设备内存 75% | 12 GB 机型建议 4096–6144 |
| `DISK_TOO_SMALL` | < 20 GB | Windows 11 ARM64 展开后约 20 GB |
| `DISK_NOT_WRITABLE` | 虚拟机目录不可写 | 检查 `Android/data/dev.pocketvm/files` 是否被清理工具锁定 |
| `FIRMWARE_MISSING` | `firmware/` 为空 | 导入 `QEMU_EFI.fd`（edk2 aarch64 pflash） |
| `FIRMWARE_IMPLAUSIBLE` | 文件 < 256 KB | 多半是把 `QEMU_EFI.fd` 的 git-lfs 指针文件下下来了 |
| `FIRMWARE_VARS_SIZE_MISMATCH` | vars 与 code 不等长 | 删除 `vms/<id>/vars.fd` 让其按 code 大小重建（会丢启动项） |
| `ISO_NOT_FOUND` | 路径失效（重装/移动） | 重新导入 ISO |
| `VNC_LAN_UNAVAILABLE` | 选了局域网模式；当前 QEMU 构建缺少 DES 加密后端，VNC 密码认证无法启动 | 改回「仅本机」，用 `adb forward` 查看画面；恢复局域网需重建 QEMU 时编入支持 DES 的加密后端 |
| `DISK_FREE_LOW`（警告） | 剩余 < 2 GB | qcow2 精简置备但写时增长 |

## 启动后失败（QEMU 日志归因）

`FailureHints` 用日志正则匹配，按优先级取第一条：

| 日志特征 | 含义 |
| --- | --- |
| `Permission denied` | 从可写目录执行被 SELinux 拒绝；必须以 `lib*.so` 打进 APK |
| `Exec format error` | 打包了 x86_64 或 armv7 版本 |
| `No such file or directory` | 缺动态库或路径错；查 `LD_LIBRARY_PATH` |
| `Failed to get "write" lock` | 上次 QEMU 未退出，残留进程占用磁盘 |
| `Address already in use` | VNC 端口冲突，换 5901+ |
| `Cannot allocate memory` | 内存 + TCG 翻译缓存超限 |
| `No space left on device` | 存储满 |
| `pflash` / `invalid size` | 固件与 vars 不等长 |

退出码：`137` 说明被系统 SIGKILL（内存压力或省电策略），`139` 是段错误，`132` 是 SIGILL
（USB 存储栈，或 0.10.10 之前的 PAC 缺陷——见下）。

## 常见现象

**「虚拟机跑了几十秒到几分钟后自己关闭」**
QEMU 被 SIGILL 杀死（exit=132），App 侧只有 `EXITED_ERROR` + `failureHint`。两个来源：

1. **USB 存储栈**：本机（骁龙 8 Gen3）上 `usb-storage` 起步即崩。把「光驱总线」改成
   `virtio-scsi+AHCI`，或打开编辑页的「USB 总线 → 禁用」（后者连 xHCI 都不创建）。
2. **ARMv9 PAC**（0.10.10 之前的执行层）：bionic 的 `sigsetjmp/siglongjmp` 用**每线程**
   APIA 密钥给 SP/LR 签名，QEMU sigaltstack 协程跨线程首次 enter 时 `autiasp` 校验失败，
   FEAT_FPAC 直接 SIGILL。tombstone 停在 `libc longjmp+460`，`x0=x1=3`（`COROUTINE_ENTER`）。
   0.10.10 起执行层内置无签名协程切换补丁；构建前 `verifyQemuPacPatch` 会按 SHA256 校验，
   重建 QEMU 后忘记补丁会直接构建失败。

**界面显示「QEMU 启动后立即退出」但日志为空**
fork 成功但 QEMU 在打开日志前就死了，常见于 `libqemu-system-aarch64.so` 依赖缺失。
用 `readelf -d` 看 `NEEDED`，或换成静态链接的构建。

**卡在黑屏、QMP 已连接**
UEFI 在跑但没找到可启动设备。检查 ISO 是否被识别为 CD-ROM（控制台里看 argv 是否有
`scsi-cd,drive=cd0`），以及 `-boot order=d`。

**安装程序提示「找不到磁盘」**
选了 virtio 控制器，而安装程序没有 viostor 驱动。改回 NVMe（默认），或先用
virtio-win ISO 加载驱动。

**安装程序停在「这台电脑无法运行 Windows 11」**
缺 TPM 2.0 / Secure Boot。QEMU 需要外挂 swtpm，当前未打包。按 Shift+F10 打开命令行：

```
reg add HKLM\SYSTEM\Setup\LabConfig /v BypassTPMCheck /t REG_DWORD /d 1 /f
reg add HKLM\SYSTEM\Setup\LabConfig /v BypassSecureBootCheck /t REG_DWORD /d 1 /f
```

关掉窗口返回上一步再继续。注意这需要键盘输入能力（P1）。

**虚拟磁盘占满了手机存储**
确认 `disk.qcow2` 是 qcow2 而不是 raw（控制台里看「虚拟盘已用」是否远小于配置容量）。
qcow2 删掉文件即完全回收；guest 内删除文件不会自动缩小镜像，需要 `qemu-img convert`
或 discard/TRIM。

**Windows 安装到一半重启后回到安装界面**
启动顺序仍是 CD 优先。安装完成后在向导里关掉「从 ISO 启动」，或用 `bootFromCd=false`
重新生成配置；UEFI 变量存储里已经有 `BootOrder` 时，pflash 会优先按 NVRAM 走。

**客户端连不上 VNC**
先确认 VM 处于「运行中」——VNC 端口只在 QEMU 进程活着时存在。仅本机模式必须
`adb forward`。局域网模式暂不可用：选了它会在启动前被校验器以 `VNC_LAN_UNAVAILABLE` 拦下。

**VNC 连上但黑屏**
固件还没初始化显卡（启动前 10–30 秒正常），或 `virtio-gpu` 未被 edk2 驱动。
日志里出现 `Display output` 相关字样前不要判断为故障。

**没有画面（App 内预期行为）**
App 不内置渲染器。`adb forward tcp:5900 tcp:5900` 后用桌面 VNC 客户端连
`127.0.0.1:5900`。局域网模式暂不可用，不要切到「局域网」。

## 抓诊断

控制台 → 导出诊断包，得到 zip：`device.txt`、`qemu-probe.txt`、`config.json`、每次运行的
`run-*.log` 与 `run-*.json`（含完整 argv 与退出码）。这比让用户复述现象可靠得多。
