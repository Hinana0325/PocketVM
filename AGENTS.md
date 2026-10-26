# AGENTS.md

PocketVM：在未 Root 的 arm64 Android 手机上用 QEMU TCG 跑 Windows ARM64。Kotlin/Compose 只做配置、状态与进程监督，QEMU 是独立进程——崩溃不该带走 UI。分层与 ADR 见 `docs/architecture.md`，错误码含义见 `docs/troubleshooting.md`。

## 硬约束（动手前先读）

- `android/app/build.gradle.kts` 必须保留 `jniLibs.useLegacyPackaging = true`。Android 10+ 的 W^X 只允许从 `nativeLibraryDir` 执行文件，so 不解压到磁盘就无法 exec；没了这个开关整套启动链就断了。
- 执行层是**改名成 `.so` 的 ELF 可执行文件**（`libqemu-system-aarch64.so`、`libqemu-img.so`），依赖库（glib/pixman/slirp/pcre2/intl）同样。AGP 只打包 `lib*.so`，所以每个依赖都被 patchelf 改过 SONAME 与 DT_NEEDED，并靠 `DT_RUNPATH=$ORIGIN` 互相查找。别当普通共享库处理，也别改名成非 `lib*.so`。
- **不要改成静态链接**。`-static`（即使 `-no-pie`/ET_EXEC）在本机实测 `exit=139`，问题在 bionic 静态运行时自举而非 PIE 标志位。动态 PIE 由 `/system/bin/linker64` 加载，是唯一验证过的形态。
- 只构建 `arm64-v8a`；`minSdk = 30` 是 `memfd_create` 的下限，不要降。
- **执行层 .so 从 0.10.10 起带了「PAC-free 协程切换」补丁**：ARMv9（本机 SM8650）上 bionic 的 `sigsetjmp/siglongjmp` 用**每线程**的 APIA 密钥给 SP/LR 签名，而 QEMU 的 sigaltstack 协程允许在 A 线程创建、在 B 线程首次 enter，`longjmp` 里的 `autiasp` 校验必然失败；FEAT_FPAC 直接 SIGILL，进程以 exit=132 消失（tombstone 停在 `libc longjmp+460`，`x0=x1=3` 即 `COROUTINE_ENTER`）。`scripts/patch-qemu-pac.py` 把 `sigsetjmp/siglongjmp` 的 .plt 槽改跳新增 PT_LOAD 里的 `jmp_save/jmp_restore`（`scripts/qemu-pac-shim.S`）。**重建 QEMU 后必须重新打补丁**（`--verify` 会拦下未打补丁的二进制），否则又会随机崩在 50–300 秒之间。别用 `prctl(PR_PAC_SET_ENABLED_KEYS)` 试图绕过：Linux 的 PAC 钥匙开关是 per-thread 的，管不到 QEMU 自己的线程。
- 没有要求时不要重建 QEMU：需要 Linux + NDK r27c + meson/ninja/patchelf，5–10 分钟 / 24 核，`scripts/build-qemu-android*.sh` 是 bash 脚本，`.build-qemu/` 有几个 GB（已 gitignore）。脚本头部的注释记录了必须踩过的坑，改参数前先读。

## 常用命令

Gradle 根是 `android/`，不是仓库根。全部命令都要先 `cd android`。

```bash
cd android
./gradlew :app:assembleDebug            # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests "dev.pocketvm.QemuCommandBuilderTest"   # 单个测试类
```

- `scripts/verify.sh` 是**提交前唯一需要手工跑的自检**：检查执行层架构、跑单元测试、检查 APK 的 `extractNativeLibs=true`。它是 bash-only，依赖 `file` 与 `aapt2`，按 `${ANDROID_HOME:-/opt/android-sdk}` 找 SDK。在 Windows 上用 Git Bash / WSL，别在 PowerShell 里跑。
- 需要 JDK 17+ 与 Android SDK 35；Windows 下用 `gradlew.bat`。
- 没有 CI、没有 pre-commit 配置。

## 测试

7 个文件共 82 个用例，全在 `app/src/test/`，纯 JVM：没有 Robolectric、没有 `androidTest` 源集，不需要设备或模拟器。

`Qcow2CreatorTest` 里两个用例通过 `assumeTrue` 依赖宿主机的 `/usr/bin/qemu-img`（还有一个要求 `qemu-io`）：宿主机没装就**静默跳过**。所以「测试全绿」不等于 qcow2 格式真的被交叉校验过。

新增错误码时要同时改三处：`ConfigValidator` 的 issue code、`FailureHints` 的日志归因、`docs/troubleshooting.md` 的对照表。

## 从文件名看不出来的约定

- `VmSession` 是唯一持有 QEMU 进程的对象，挂在 Application 级的 `AppContainer` 上（不在 ViewModel 里），是单例，所以**同时只能跑一台 VM**。UI 只通过密封接口 `VmRuntimeState` 看到执行层。
- `VmSession.start()` 的顺序是契约：probe → validate → 置备存储 → **先写 manifest** → fork → QMP 确认。manifest 在 fork 之前落盘，崩在中间也留得下完整 argv。QMP 握手（而非 `isAlive`）才是「已进入主循环」的可靠信号。
- **QMP 的 unix socket 必须落在内部 `filesDir/internal/run/`**：`Android/data` 走 FUSE，不支持 unix domain socket。VM 磁盘 / ISO / 日志在外部 files 目录。
- VNC 参数用的是**显示号不是端口**：`QemuCommandBuilder.displayIndex()` = port − 5900；校验器把 <5900 判为错误。`password=on` 只是开启认证，密码要在 QMP 握手后 `set_password` 设置。
- pflash 的 code 与 vars 必须**等长**（当前 64 MiB pflash）；<32 MiB 会被判为「误下了 2 MiB 的 `-bios` 镜像」。
- 启动降级阶梯是 `STANDARD → COMPAT → MINIMAL → MINIMAL_NO_MEDIA`（`LaunchProfile.ladder`）。降级只动 TCG/CPU/可选设备，**绝不改磁盘控制器、内存、vCPU 数**——改了会让已装好的 Windows 无法启动。`cortex-a76` 而不是 a72：Windows ARM64 需要 ARMv8.1 LSE 原子指令。
- 磁盘控制器是 NVMe（Windows 安装程序自带 stornvme）。**光驱必须挂两次**：固件只认 virtio-scsi（ARM virt 的 edk2 构建——Ubuntu 版和 QEMU 官方版都一样——**不含 AHCI 驱动**，`map` 里查不到 `ich9-ahci` 上的任何设备），而 Windows 只认 AHCI（自带 storahci，没有 vioscsi）。所以 `virtio-scsi-pci` + `scsi-cd`（bootindex 1，供固件引导）**加上**同一个 ISO 再挂 `ich9-ahci` + `ide-cd`（供 WinPE 读 install.wim）。`usb-storage` 在本机必 SIGILL（exit=132，与介质大小/类型无关——1 MiB 假 CD 也一样）。真机上 `ich9-ahci` 的端口名是 `ahci.0..5`（不是 `ide.*`），写错报 `Bus not found`。
- 别拿 `Image type X64 can't be loaded on AARCH64 UEFI system` 判断光驱是否可见：那条来自 virtio-net 的 **x86 option ROM**（加 `-nic none` 就消失），与光驱无关。曾据此误判"固件能看到 AHCI 光驱"。
- **显示设备必须挂 `ramfb`，不能挂 `virtio-gpu-pci`（2026-10-26 实测更正）**：`VirtioGpuDxe` 的 GOP 没有线性帧缓冲——`Mode->FrameBufferBase=0`、`FrameBufferSize=0`、PixelFormat=3（BGRx）。bootmgfw 的 surface 初始化（RVA 0x32ea0）在 format==3 且 base==0 时跳过映射，把 NULL 写进 surface+0x98；随后清屏例程（RVA 0x30a68 → memcpy RVA 0x3910）往地址 0 逐行拷贝 → Data Abort（ESR `0x96000047`、FAR 0x0、ELR 镜像内 RVA 0x3990）。换 `-device ramfb` 后同一个 ISO 不再崩溃，bootmgfw 正常显示 "Press any key to boot from CD or DVD"，按键后进入 Windows 11 安装程序（真机复现，见 `C:\tmp\pvm-e2e` 的 ramfb 会话截图）。ramfb 没有 Windows/Linux 驱动，但 Windows Setup 与基本显示走 EFI framebuffer，能正常显示；Linux 客户机反而要选 virtio-gpu（ramfb 下内核交接后无控制台）。默认值已改为 `DisplayDevice.RAMFB`。
- 启动 ISO 时会从 ISO 里抽出 El Torito EFI 启动镜像，挂成 bootindex 1 的只读 virtio-blk（见 `IsoBootImage`）。**这条路径无用**：镜像里只有引导程序，没有 `\boot\bcd` 和 `\sources\boot.wim`，它打印「按任意键从 CD 启动」、按键之后直接退回 UEFI Shell。
- **WinPE 启动崩溃的根因已大幅定位（2026-10-25，x86 宿主 QEMU 8.2.2 TCG + App 同款 argv 复现）**：崩溃点比原先判断的更早——不在「Windows 内核初始化」，而在 bootmgfw（Windows Boot Manager，El Torito 里的 BOOTAA64.EFI，2024-03 构建）自身初始化。屏幕停在 EDK2 的 "Start boot option" 菜单（曾误判成 Windows 转圈动画；三配置逐帧零差异实为冻结画面）。`-d int` 全程 trace：约 600 个 IRQ 后写 FAR=0x0 的 Data Abort（ESR `0x25/0x96000047`），随即死在异常向量 `b .`；**故障指令恒定位于 VBAR+0x990**，即 bootmgfw 镜像内同一处空指针写。实验矩阵（均带 `-d int` 插桩）：GICv2（默认）3/3 必崩；GICv3+`its=off` 3/3 必崩（QEMU ≤8.2 reverse bug：`its=off` 后 ACPI 仍描述 ITS，且 TCG 下无 MSI-X）；GICv3+ITS 默认开——唯一一组不崩的运行是早先**无插桩**的 400s 长跑，加插桩后 60s 内即崩（K2/K6）⇒ **崩与不崩随时序漂移，没有哪个 argv 是确定性修复**。已排除：`pauth=off`、`cortex-a76`、`neoverse-n1`、`-smp 1/4`、内存档位（3 GB 下 `highmem` on/off 的 RAM 落位完全相同，差异只在 PA 位宽广告）、磁盘内容（实验盘全空）。**下一步**：① 内存 dump 反汇编 VBAR+0x990 处的故障指令，确认被解引用的空指针是什么（UEFI 配置表/ACPI 指针？）；② 验证 iPXE 维护者记录的机制——「Windows on ARM 假定 EL3/ATF 响应 `smc`，KVM 空转该指令所以能启动，TCG 正确执行反而崩」，若是则需给 App 的 QEMU 固件链加 EL3（TF-A）或找 `smc` 空转化途径；完整调研档案见 `research/win11-arm64-on-qemu/`（F1–F7）。本次 0.10.9 落地的 `-machine virt,highmem=on,gic-version=3` 只是对齐所有外部已验证配置与研究结论（F4[12]：GICv3 是 Windows 在 virt 上唯一相关的中断控制器）的**部分修复**，不是充分修复。**2026-10-26 更正：以上「崩与不崩随时序漂移」的判断作废——真正变量是显示设备。** 所有崩溃会话挂的都是 `virtio-gpu-pci`；改挂 `ramfb` 后，同一份固件、同一个 ISO、同一套 CPU/内存参数不再出现 Data Abort，bootmgfw 正常显示按键提示并进入 Windows 安装程序。GICv3 可保留，但根因是 VirtioGpuDxe 的 GOP 没有线性帧缓冲（见上一条）。
- 新增的错误码、注释、文档、UI 文案一律用中文——仓库现有内容全是中文。

## 真机实测事实（小米 14 Pro / Android 16 / HyperOS，2026-10-22 实测）

`SoC=QTI SM8650`、arm64-v8a、14.8 GB 内存、4 KB 页。链路验证进度：

- **显示链已验证**：UEFI 起来、virtio-gpu 出图 1280x800、VNC 服务正常。
- **输入链已验证**：`usb-kbd`+`usb-tablet` 挂 `qemu-xhci` 可用；`virtio-keyboard-pci` 在这台固件上**收不到按键**（RFB 与 QMP `send-key` 都试过）。所以输入设备用 USB，不用 virtio。
- **介质**：见上面的光驱结论。
- **降级只换光驱、不换输入（2026-10-25 实测）**：`usb-storage` 起步在本机必 SIGILL（exit=132），但把光驱换成 `virtio-scsi-pci` + `ich9-ahci` 后，`usb-kbd`/`usb-tablet` 仍可用——隔离实验里 VNC 方向键把 GRUB 高亮从第一项移到第三项、倒计时停止，Linux 客户机枚举 USB 后 QEMU 存活；同一台机器上 `virtio-keyboard-pci` 则完全收不到键（方向键无反应、倒计时照走）。0.10.9 的降级曾把 `usbInput` 一起改成 false 并写回 config，等于降级一次后键盘永久失效；现已改为只换光驱、输入保持 USB，仅当同一档再次被信号杀死才把输入降为 virtio。
- **停止路径**：客户机已进内核时正常关机 `exit=0`；若还停在引导器阶段，`quit` 路径可能以 SIGILL（exit=132）退出，`stoppedByUser=true`、`stop.done` 仍会正常到达，不算失败。
- **「虚拟机自己关闭」的根因（2026-10-26 定位，0.10.10 修复）**：未打补丁的执行层在 50–282 秒内随机以 `exit=132` 消失，UI 只显示 `EXITED_ERROR`、`failureHint=null`（FailureHints 有意不把 132/139 归类）。tombstone 恒为 `signal 4 (SIGILL), code 2 (ILL_ILLOPN)`，PC 在 bionic `longjmp+460`（`autiasp`），`pac_enabled_keys=0xf`，`x0=x1=3`，18 帧栈（libc → QEMU 0x10c03ac → coroutine → `sigsuspend`）在 App 启动与独立 shell 启动的运行里逐帧一致。这是 PAC 跨线程恢复失败，与客户机、用户操作、OOM 无关；客户端串口日志停在中途且无 panic。补丁版用同一套 argv 跑 11 分钟不崩，并首次进到 **Windows 11 安装程序窗口**（800x600，ramfb）。
- **VNC 密码不可用**：这个 QEMU 构建的加密后端不支持 DES（RFB 3.8 的 VNC 密码就是 DES），所以 `password=on` 会在启动前 exit=1；而 `ConfigValidator` 强制局域网模式必须设密码 ⇒ **局域网模式不可用**，只有「仅本机 + `adb forward`」是通的。要恢复局域网，得在重建执行层时编入支持 DES 的加密后端。
- **两个很省事的工具链事实**：① shell 能直接执行 APK 里的 `libqemu-system-aarch64.so`（`/data/app/**/lib/arm64/` 是只读+可执行路径，`LD_LIBRARY_PATH` 指过去即可），不用过 App 就能验 QEMU 参数；② 每次重装 APK 都会换掉 `/data/app/~~<hash>/<pkg>-<hash>` 里的哈希，写死的路径立刻过期。

**最大的未决问题：主循环停摆（可复现）**。客户机跑几分钟后，主线程睡眠、TCG 的 vCPU 线程仍在满核转，而 VNC/QMP 不再响应（监听套接字的 `Recv-Q` 堆着没人 accept 的连接）。判读工具上有个坑：**跨 UID 读 `/proc/<pid>/stat` 的 utime/stime 不可靠**（Android 对别的 UID 的聚合值会冻结或为零），要读就逐个读 `/proc/<pid>/task/<tid>/stat`。`wchan` 全是 0、`/proc/<pid>/io`、`/proc/<pid>/stack`、`debuggerd -b` 都被 SELinux/权限拒绝，所以卡死点只能靠 App 内部采。

## 源码比 prose 新（别被过时说明带跑）

- `docs/architecture.md` 的分层图和 ADR-004、以及 `QemuEnvironment.resolveQemuImg()` 的注释都写着「qcow2 失败回退 raw」。**没有 raw 回退**：`DiskProvisioner` 只产出 qcow2，raw 落在 FUSE 上可能真的吃掉全部虚拟容量。以代码为准。
- README 的「已知限制」说 VNC 只监听 `127.0.0.1`，实际存在 `VncAccess.LAN`（校验器要求局域网模式必须设密码）。
- README 写「37 个单元测试」，实际 67 个。
- README 的「验证状态」写明真机启动与 Windows 安装全流程**均未验证**——这是有意的未知项，不要把沙箱里的结论当成设备上的结论。

## 仓库状态与发布

- 这个工作副本**有 `.git`**（remote `https://github.com/Hinana0325/PocketVM.git`，`user.name=Hinana0325`），历史 commit 正常可查。**推送链路（2026-10-25 实测）**：github.com:443 可达，但沙箱默认无凭据（`gh` 未登录、沙箱 credential helper 不吐 github.com 凭据、agent-gateway 的 token 服务报 `Connector "github" is not authorized`）。授权形式有两种：① 用户在 CodeBuddy 设置页「连接器」授权 GitHub；② **用户直接给出 token**（0.10.9 即由用户提供 token 推送成功）。拿到 token 后用一次性 URL 推：`git push https://oauth2:${GITHUB_TOKEN}@github.com/Hinana0325/PocketVM.git main`（不动 origin 配置，无需事后清理；token 只经环境变量传递，绝不写进仓库）。这是对外可见的显式动作，未经用户确认不要跑。
- **历史陷阱（0.10.9 收敛时实测，勿再踩）**：远端 main 曾被 `push_via_api.py` 逐 commit 重建，与本地旧历史**消息一一对应但 SHA 全不同**（本地 `d4b8989` ≡ 远端 `82484327`：140 个文件 blob 全部一致，仅 `scripts/release_0.10.5.py`、`release_0.10.6_and_0.10.8.py` 的可执行位被推成 755 而本地是 644）；且本地历史缺远端 0.10.5 及 7 个中间 commit（内容已含在后续 commit 里，累积树等价，无实质丢失）。**收敛方式：`git rebase --onto <远端main> <本地对应commit>` 后 fast-forward 推送，切勿 force push**。判断内容是否等价：API 递归比 tree/blob（本地 `git ls-tree -r -t` vs `GET /repos/Hinana0325/PocketVM/git/trees/<sha>?recursive=1`，注意比 mode 不只是 blob SHA）；git 协议 fetch 全历史在沙箱会卡死（>4 min），`--depth` 浅 fetch 或纯 API 比对快得多，push 增量则只要 1 秒。
- `scripts/push_via_api.py` / `gh-sync.py` 是更早的 API 推送方案（读 `GH_TOKEN`、走 api.github.com git data API），为「git-over-HTTPS 不通 + 工作副本无 .git」的时代而写，两个前提现在都不成立；当前同样卡在拿不到 token。别被 gh-sync.py docstring 里「没有 .git」的旧描述误导。