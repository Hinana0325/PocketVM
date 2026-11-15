# PocketVM Android Guest 支持技术方案

- 文档性质：架构调研，只做结论，不写代码、不改仓库
- 核查日期：2026-11-15
- 基线：PocketVM main `9f3d8ff`（2026-11-15 刚拉取的新版）；QEMU 11.1.2；宿主小米 14 Pro（骁龙 8 Gen 3，SM8650），未 Root，TCG，无 KVM
- 本文档独立核实了用户转贴的技术建议稿里的说法；凡建议稿写了但本次没核实到的，见文末“未核实清单”
- 修订（2026-11-15）：用户审阅后提出五项修正意见已落实——vendor_boot 解包流程、SurfaceFlinger 根因降级为首要怀疑、SwiftShader EGL/GLES 拆分验证、面向 virt 的 Device/Product 配置、M1–M6 重拆；最小实验就绪情况见 §16
- 再修订（2026-11-15）：用户审阅后四项补充意见已落实——M2/M3 职责明确拆分（M2 只做 Vendor 基础框架，图形栈全归 M3）、boot_completed 验收之外性能目标单独定义并增加实际交互测试、最小实验的 SIGABRT 定位精度要求；Linux 状态一项经仓库核实后**未改**（仓库中无“进入系统”级证据，见 §1 第 1 条）

## 结论标签说明（全文统一使用，绝不混用）

- （已验证）：在真机或主机 QEMU 上实际跑通过，有日志/记录为证。
- （源码确认）：读过仓库源码、本地文件或上游公开源码后确认的事实。
- （文档确认）：读过上游官方文档原文后确认的事实（AOSP / QEMU / Android 开发者文档等）。
- （理论推测）：基于上述事实的推理、尚未验证的判断。
- （未核实）：本次没能核实到的说法，不作为结论依据。

---

## 一、结论先行

### 推荐路线：路线 B（标准 QEMU virt + 自建 Android Vendor 层），不移植 Ranchu

**一句话理由：** Ranchu 是 Google 的下游 QEMU fork，完整移植工作量为“大”，且宿主 GPU 栈在 Android 手机上没有现成构建路径；而路线 B 复用 PocketVM 已有的 virt 机型、QEMU 11.1.2、本地已编好的 android17-6.18 内核与 GSI 拼装，缺的只是一套 Vendor 层（gralloc / HWC / EGL / fstab / sepolicy），这套东西在 AOSP 里已有现成配置可抄（Cuttlefish 的 `guest_swiftshader` 模式就是参考实现）。

### 对建议稿的三处纠正（核实后必须改）

1. **“Linux ARM64 已在真机成功进入系统”——未证实，不能作为前提。** 仓库里最强的 Linux 记录是：真机上 Linux 客户机到过 GRUB（方向键能移动高亮、倒计时停止）、USB 枚举正常、virtio-gpu 出图 1280x800（AGENTS.md“真机实测事实”；README.md:397 验证表里没有 Linux 这一行）。“进入系统/桌面/登录”没有明确记录。本方案只认“到过 GRUB、能出图”，不认“进入系统”。
2. **“先软件渲染，再考虑 VirtIO-GPU”——顺序反了。** 2026-10-28 的 Wear OS 实测（已验证）证明：virtio-gpu 的 DRM 驱动在 virt 机型上能初始化成功，缺的不是 GPU 设备，而是 Vendor 侧的图形 HAL。正确的第一阶段是 **virtio-gpu 2D（只做 KMS 送显）+ 软件 HAL（minigbm + drm_hwcomposer + SwiftShader）**，而不是“不用 virtio-gpu”。但这三个 HAL 组件必须拆成独立验证任务（见 §5.2、M3）：`vulkan.pastel.so` 只是 SwiftShader 的 Vulkan 驱动，不能证明 EGL/GLES 路径已解决；minigbm、drm_hwcomposer、SwiftShader 拼起来不等于方案完成。
3. **“UEFI 或 Android 专用启动链路，分别验证”——同意，但要先定下来：本地 PC 实验走的是 `-kernel` 直启，App 现有链路只走 UEFI pflash，这是两条不同的启动路径。** M1 的第一个任务就是二选一，不要两条线并行烧时间。

### 关键判断（先给结论，论证在后文）

- GSI 到 SurfaceFlinger 却完不成启动，**首要怀疑是 Vendor 图形 HAL 缺失**（没有 gralloc / HWC / EGL，SurfaceFlinger 启动时加载失败可能 abort，init 反复拉起成循环），但**尚未定论为根因**：两组证据各有限制——Wear OS 实验只证明 ranchu HAL 依赖 goldfish_pipe/sync、缺失后崩溃循环；Android 17 GSI 实验只有 SurfaceFlinger SIGABRT、无 tombstone，且其 first-stage init 二进制未经独立验证。两组证据不能互相证明。必须通过 SurfaceFlinger tombstone、logcat、HAL 服务状态确认具体故障后才能定论；根因确认前不启动大规模 HAL 编写。（理论推测——首要怀疑；证据局限见 §3.3）
- **没有现成的、能直接下载的“QEMU virt 用 Android Kernel + Vendor 镜像组合”。** 三个最接近的候选（Cuttlefish、Emulator kernel-ranchu、Waydroid/Redroid）各缺一块，见 §5.4。（文档确认）
- **QEMU 11.1.2 不需要移植任何 Emulator 专用设备模型**就能走路线 B；上游已有的 virtio-blk/net/gpu/input/console/rng 已够用。需要补的是 Guest 侧 Vendor HAL 与宿主侧 GPU 后端（后者短期不可用，见 §6.3）。（文档确认 + 理论推测）
- **第一阶段的目标只能是“能到 boot_completed、静态画面可用”，不是“能流畅用”。** SwiftShader 在 TCG 下是双重 CPU 税，动画与滑动极可能卡顿到不可用；应定位为“验证图形链路通不通”的调试模式。（文档确认 + 理论推测）
- **不要把 KVM 作为前置条件。** 同架构 ARM64 下 TCG 已被 Windows 真机验证可行；Android 的真正卡点全在 Guest 侧，不在加速器。（已验证）

---

## 二、现状基线（以仓库与本地实物为准）

### 2.1 PocketVM 现状

| 项 | 状态 | 依据 |
|---|---|---|
| QEMU | 11.1.2，预编译 `.so` 进仓库，CI 不构建 QEMU | （源码确认）`docs/third-party-sources.md:6`、`scripts/build-qemu-android-dynamic.sh:26` |
| 加速器 | 只用 TCG，无 KVM；MTTCG 只在 STANDARD 档 | （源码确认）新版 QEMU 核查报告 §3；`LaunchProfile.kt:38` |
| 机型 | `virt,highmem=on,gic-version=3`；STANDARD 档 CPU `max`、vCPU 默认 4、内存默认 3072 MB | （源码确认）`QemuCommandBuilder.kt:125`（机型）；`LaunchProfile.kt:38`（STANDARD 档 CPU `max`）；vCPU/内存默认值为新建默认值（新版 QEMU 核查报告 §3） |
| 固件 | edk2 UEFI，双 pflash 64 MiB；TPM2 固件已是默认固件，但 TPM 默认关闭、无 UI 开关 | （源码确认）新版 QEMU 核查报告 §4、§6.5 |
| 显示 | 默认 ramfb；Linux/Android 预设强制 virtio-gpu | （源码确认）`QemuCommandBuilder.kt:177-178`、`CreateVmViewModel.kt:223` |
| VirtIO | net/blk/scsi/rng/gpu/keyboard/tablet；无 vsock、virtio-serial、virtio-fs、balloon | （源码确认）新版 QEMU 核查报告 §5 |
| Windows ARM64 | 2026-11-12 真机到桌面（走审计模式建账户，非正常 OOBE 流程） | （已验证）README.md:399、AGENTS.md“2026-11-12 真机收口” |
| Linux ARM64 | 只证实到 GRUB + 出图 + USB 枚举；“进入系统”未证实 | （已验证（部分））AGENTS.md“真机实测事实”；README.md:397 无 Linux 行 |
| Android 平台位 | `VmPlatform.ANDROID_ARM64` 已存在；新建预设强制 virtio-gpu、内存 ≥4 GB、从磁盘启动、隐藏 ISO/驱动盘/磁盘控制器 | （源码确认）`VmPlatform.kt`、`CreateVmViewModel.kt:204-226` |
| GSI 拼装 | 已落地为实验功能：sparse 展开 + GPT 物理分区盘（metadata/system/vendor/userdata），转 qcow2 | （源码确认）`GsiAssembler.kt:17-24`；UI 标注“只生成 GPT 物理分区盘（不做 dm-super）” |
| 多 VM / 快照 / 调试接口 | 已实现、含单测、真机验证待做 | （源码确认）新版 QEMU 核查报告 §6.6、§6.7、§7.2 |
| App 内存上限（Android 17） | 未验证的最大风险：QEMU 子进程与 App 同 UID，占数 GB 可能触发上限被杀 | （理论推测）AGENTS.md“Android 17”一节 |

### 2.2 Android GSI 现状（两次 PC 实验，必须分开说）

本地有两份不同的 PC QEMU 实验记录，不要混为一谈：

**实验一：2026-10-29，`~/workspace/qemu-boot/BOOT.md`（本机可复核）**

- 输入：内核 `Image-qemu-virt`（Linux 6.18.21-android17-5，ARM64，4K 页，virtio blk/net/console/input/mmio/PCI + virtio-gpu 均内置 `=y`）；initramfs 只有内核模块，无 `/init`、无 fstab（本次修订复核：`initramfs.img` 是 LZ4 压缩 cpio，303 条，全部在 `lib/modules/` 下，无 `/init`、无 fstab）；`boot-virtual-device.img` 只有内核，无 ramdisk；GSI 包只有 system.img/vbmeta.img/build.prop，无内核、无 ramdisk、无 vendor。
- 命令：主机 QEMU 8.2.2，`-machine virt -cpu cortex-a57 -smp 2 -m 2048 -accel tcg,thread=multi -kernel Image-qemu-virt -initrd initramfs.img`，virtio-blk 挂 system/userdata/metadata，virtio-gpu-pci，串口日志。
- 结果（已验证）：内核完整启动，virtio-blk 认出 vda/vdb/vdc，PL011 串口工作；随后 23 次完全相同的 panic 循环：`VFS: Unable to mount root fs on unknown-block(0,0)`。根因是解包后的 initramfs 里没有 `/init`，内核无 init 可执行。结论：**内核与 virtio 链路本身是通的，缺的是 first-stage ramdisk**。

**实验二：2026-11-12，AGENTS.md“GSI 启动链”一节（工件在用户 PC 的 `C:/tmp/pvm-gsi/`，本机无实物）**

- 首阶段 `reboot('bootloader')` 已定位打通：根因不是 ueventd，而是 GPT 的 `PARTNAME` 与 fstab 的 `vdaN` 对不上；最小解是 cmdline 自映射 `androidboot.partition_map=vda1,vda1;…;vda7,vda7`。
- 后续还需改 vendor 的 `fstab.ranchu`（模拟器原版带 `logical`，要求 super 分区）、去掉 `/data` 加密标志、给 `/data` 挂已格式化的 ext4（拼盘时 vda7 是空的）。
- 结果：能跑到 zygote / surfaceflinger，但 **surfaceflinger SIGABRT 循环，未到 boot_completed**；加 virtio-gpu-pci 只把首次崩溃从 50 s 推迟到 148 s，未根除，串口日志无 tombstone。
- **诚实标注**：这次实验用的 first-stage init 二进制是 WorkBuddy 声称交付的（3,283,848 B，10 月 29 日），**未下载、SHA 未独立核实，只是声称**；用户已决定“先不动”。因此“跑到 zygote”这个结论本身可信度打折——它依赖一个未验证的输入。本方案引用它时只作为“卡点在 SurfaceFlinger”的佐证，不作为启动链已打通的证据。

**补充：2026-10-29 的 first-stage ramdisk 组装（`~/workspace/bootable-ramdisk/`，本机可复核，2026-11-15 修订时新查）**

- 产物：`ramdisk.cpio.gz`（9,229,914 B，SHA-256 `a50640cf…`，204 条）：顶层 `/init` 是指向 `/system/bin/init` 的 symlink（system-as-root 形态，非静态 first-stage 二进制）；`/system/bin/init` 是 GSI 自带的 init（2,653,128 B，BuildID `972f69b5…`），另有 bootstrap linker（`/system/bin/bootstrap/linker64`）与 29 个依赖库、ueventd、toybox、toolbox、e2fsck、`/dev` 设备节点、顶层 fstab + fstab.qemu、GSI 自带的 platform sepolicy（`system/etc/selinux/plat_sepolicy.cil`），无 vendor sepolicy trio。另有 `boot.img`（53,567,488 B，由 `assemble-boot.sh` 从该内核 + 该 ramdisk 打包），**从未启动过**。
- 实测结果（已验证，`RAMDISK.md` 与 `smoke-serial.log`）：主机 QEMU 上内核能跑 `/init`，动态链接能工作，但 GSI 的 init 在打印任何自身日志之前就 SIGABRT；`~/workspace/init-debug/DIAGNOSIS.md` 已定位崩溃点——abort 发生在动态链接器完成、跳转到 `_start` 之后，init 自身早期执行期间（libc 运行时启动到 `main()` 开头），不在链接加载、不在静态构造、不在符号解析；**未修复**。
- 下一步定位要求（2026-11-15 用户审阅补充）：在 `DIAGNOSIS.md`、`lddebug-trace.txt` 与崩溃日志的基础上继续分析，定位必须精确到**函数、调用栈、寄存器、触发条件**，重点检查寄存器、调用栈、运行时环境、系统调用返回值，区分 init 二进制自身问题、运行时环境问题、内核接口问题、ramdisk 组装问题；**fstab 与 SELinux 不是这个阶段的首要排查对象**（它们主要影响后续 first-stage mount 与 SELinux setup），不要把排查范围扩散到它们上面。（未核实——待最小实验攻关）
- 与 WorkBuddy 声称的二进制的关系：WorkBuddy 声称的 first-stage init 是 3,283,848 B，磁盘上不存在；这里的 init 是 GSI 自带的 2,653,128 B，两者大小、来源均不同，**不能互相代替**。因此“first-stage init 二进制”仍是缺失项：磁盘上没有静态 first-stage init，没有 vendor_boot.img / init_boot.img 成品（内核树里只有测试数据与构建脚本），只有这份用 GSI 自带 init 组装的 ramdisk——而它的 init 在启动早期即 abort，尚未解决。最小实验的第一步就是复现并攻关这个 abort，见 §11 与 §16。（源码确认 + 已验证）

**GSI 本身的构成（本地实物核实）：**

- `~/workspace/gsi/aosp_arm64-exp-DP11.261002.005-16594701-a359a50b.zip`（1,186,717,983 B，Android 17 QPR3 Beta ARM64 无 GMS，SHA-256 `a359a50b…` 已验证）。zip 内只有 system.img（2,114,777,088 B，ext4）、vbmeta.img、build.prop。（源码确认）
- system.img 内 `/init` 是指向 `/system/bin/init` 的 symlink，有 `/system/etc/init/`，但**没有通用的 `fstab.*`**；GSI 的设计就是让设备/vendor 的 ramdisk 提供 first-stage init + fstab。（源码确认）（`qemu-boot/BOOT.md`）
- 内核源码 `~/workspace/kernel-android17-6.18`（44G，android17-6.18），`out/dist-qemu-virt/` 已有编好的 Image 等产物。（源码确认）
- `~/workspace/vendor-sepolicy/` 存在（VENDOR-SEPOLICY.md、verify-policy.sh 等），但**其启动验收未证实**，不能当成“sepolicy 已解决”。（源码确认）

### 2.3 一份直接相关的先行实验：Wear OS 3（2026-10-28，AGENTS.md）

这是仓库里唯一一份“Android 在 QEMU virt 上跑到图形栈之前”的完整记录，对本次调研价值最大：

- 镜像：Wear OS 3（`system-images;android-30;android-wear;arm64-v8a`），内核 5.4.86。
- 能进 Android second stage：ueventd、SELinux、`/system` 与 `/vendor` 挂载、apexd、surfaceflinger 启动，virtio-gpu 的 DRM 驱动初始化成功。（已验证）
- **卡在 vendor 图形 HAL**：`hwcomposer.ranchu.so` / `android.hardware.graphics.mapper@3.0-impl-ranchu.so` 依赖 goldfish_pipe/sync；vendor EGL 只有 `libEGL_emulation`（宿主编码器）；镜像里没有 drm_hwcomposer / minigbm / 软件合成器，SurfaceFlinger 与 zygote 崩溃循环。（已验证）
- 结论原文：“要真正显示，需要移植 Android 图形栈（minigbm + drm_hwcomposer + 软件 GL，或把 goldfish 设备搬进 QEMU），属于多天的平台级工程，不是 App 侧能解决的。”
- 另记：该内核只支持 GICv2，`gic-version=3` 时无任何串口输出，必须 `-M virt -cpu cortex-a57`。这是 5.4 内核的老问题，6.18 GKI 不应有此问题，但 M1 仍需实测确认（见 §11）。

---

## 三、Android 启动链路分析（卡在哪、为什么）

### 3.1 标准链路（AOSP first-stage init 机制）（文档确认）

来源：AOSP `system/core/init/README.md`（Early Init Boot Sequence）与 `first_stage_mount.cpp`。

早期启动分三段：first-stage init → SELinux setup → second-stage init。

- **形态一 system-as-root**：first-stage init 就是 `/system/bin/init` 本体，`/init` 只是指向它的 symlink，不需要挂载 system.img（内核已把它当 rootfs 挂好）。
- **形态二有 ramdisk**：first-stage init 是位于 `/init` 的静态可执行文件，把 system.img 挂到 `/system` 再 switch-root 到 `/`。
- **形态三 recovery 作 ramdisk**：先切到 `/first_stage_ramdisk` 再同形态二。
- 完成后 exec `/system/bin/init selinux_setup`，再 exec `/system/bin/init second_stage`，进入 init.rc。
- first-stage mount 的输入：`ReadFirstStageFstabAndroid()` 先读 DT 的 `/firmware/android` fstab 节点（`ReadFstabFromDt`），失败才回退读文件 fstab（`ReadDefaultFstab`）；读不到时打“Failed to read fstab from device tree”，fstab 为空时“First stage mount skipped”。
- Android 13 的分区变化：通用 ramdisk 从 `boot.img` 移到独立的 `init_boot.img`，`boot.img` 只剩 GKI 内核；厂商 ramdisk 在 `vendor_boot.img`；通用 ramdisk 只含 first-stage `init` 与 build.prop；fstab 文件应移到 `vendor_ramdisk`。

### 3.2 PocketVM 现状对照（逐段卡点）

| 阶段 | 需要什么 | 现状 | 结论 |
|---|---|---|---|
| 内核启动 | GKI 内核 + virtio 驱动 + 串口控制台 | 本地 6.18.21 内核已编好，virtio 全内置；实验一证实内核能完整启动、virtio-blk 认盘、串口工作 | （已验证）通过 |
| first-stage init | `/init`（静态可执行文件）+ fstab（DT 节点或文件） | 有一份已组装的 first-stage ramdisk（`~/workspace/bootable-ramdisk/ramdisk.cpio.gz`，204 条）：顶层 `/init` → GSI 自带的 `/system/bin/init`（2,653,128 B），另有 bootstrap linker/libs、fstab、platform sepolicy；但该 init 在主机 QEMU 冒烟测试中于启动早期 SIGABRT（打印任何自身日志之前），崩溃点已定位、未修复；对应的 boot.img 从未启动过。静态 first-stage init 二进制、vendor_boot.img / init_boot.img 成品在磁盘上均不存在 | （源码确认 + 已验证）ramdisk 存在但 init 早期 abort 未修复；仍是实验一 panic 的直接原因，见 §2.2 补充与 §16。定位精度要求：下一步须精确到函数、调用栈、寄存器、触发条件（依据 `DIAGNOSIS.md`、`lddebug-trace.txt` 与崩溃日志）；fstab 与 SELinux 不是本阶段的首要排查对象 |
| first-stage mount | fstab 能解析到 vda/vdb/vdc，`/data` 已格式化 ext4 | GPT 分区名与 fstab 的 `vdaN` 对不上（已用 `partition_map` cmdline 绕过）；`fstab.ranchu` 带 `logical` 要求 super 分区，但拼盘不做 dm-super；拼盘的 userdata 分区是空的 | （源码确认）三处待修，见 §8 |
| SELinux setup | sepolicy（vendor sepolicy trio） | GSI 不含 vendor sepolicy；本地 `vendor-sepolicy/` 存在但启动验收未证实 | （未核实）启动是否放行 |
| second-stage init | `/system`、`/vendor` 挂载，init.rc 执行 | 实验二跑到 zygote，说明 second stage 曾跑通过（以其未验证的 first-stage 输入为前提） | （理论推测）大概率已过，待 M1 复现确认 |
| zygote / system_server | Binder、ashmem/memfd、系统服务 | 实验二跑到 zygote，说明 Binder 等基础服务曾起来 | （理论推测）同上 |
| SurfaceFlinger | gralloc + HWC + EGL 三件套 | **首要怀疑是缺失**：GSI 无 vendor HAL；Wear OS 实验证实 ranchu HAL 依赖 goldfish_pipe/sync、镜像无 drm_hwcomposer/minigbm/软件合成器时 SurfaceFlinger 与 zygote 崩溃循环；但 Android 17 GSI 实验只有 SIGABRT、无 tombstone，且其 first-stage init 未经独立验证，两组证据不能互相证明 | （理论推测——首要怀疑）卡点待确认：须以 tombstone、logcat、HAL 服务状态确认后才能定论，见 §3.3 |
| boot_completed | `sys.boot_completed=1` | 从未达到；system_server 等图形服务起不来，永远等不到 | （已验证）未达到 |

### 3.3 为什么是 SurfaceFlinger，不是 GPU 设备

AOSP 图形文档（文档确认）（source.android.com/docs/core/graphics）：

- “All of the visible surfaces rendered are composited onto the display by SurfaceFlinger.”
- “SurfaceFlinger is the only service that can modify the content of the display. SurfaceFlinger uses OpenGL and the Hardware Composer (HWC) to compose a group of surfaces.”
- “The graphics memory allocator (Gralloc) is needed to allocate memory requested by image producers.”
- “The Hardware Composer HAL conducts the other half of the work and is the central point for all Android graphics rendering. The HWC must support events, one of which is VSync.”

因此，**首要怀疑**是（理论推测）：GSI 无 vendor 分区 → 无 vendor HAL：没有 gralloc 服务（`android.hardware.graphics.allocator`）、没有 HWC 服务（`android.hardware.graphics.composer`）、没有配套的 EGL/Vulkan 实现与 `androidboot.hardware.*` 属性。SurfaceFlinger 启动时要加载 Gralloc + HWC + 初始化 EGL，任一环节失败即可能 abort/重启；init 将其当 critical 服务反复拉起，表现为 surfaceflinger 循环；同时 `system_server` 依赖的图形服务起不来，`sys.boot_completed` 永远等不到。

**但这只是首要怀疑，不是已定论的根因，证据有两处局限：**（1）Wear OS 实验只证明 ranchu HAL 依赖 goldfish_pipe/sync、缺失后崩溃循环，它用的是 Wear OS 3 镜像与 5.4 内核，不能直接证明 Android 17 GSI 的 SIGABRT 由同一原因引起；（2）Android 17 GSI 实验只有 SurfaceFlinger SIGABRT、无 tombstone，且其 first-stage init 二进制未经独立验证——崩溃甚至可能发生在 init 早期（`~/workspace/init-debug/` 已证实 GSI 自带 init 在启动早期即可 SIGABRT），未必是图形 HAL 加载失败。两组证据不能互相证明。

**确认手段（定论前必须做，不做不准开工写 HAL）：** 取到 SurfaceFlinger 的 tombstone（`/data/tombstones/` 或 logcat 中的 `DEBUG` 段），确认 abort 的调用栈与缺失的库/服务；用 logcat 确认 `android.hardware.graphics.allocator/composer` 服务是否注册、是否报错；检查 HAL 服务状态（`lshal` / `service list`）。只有确认了具体故障点，才能决定补哪一块。**在根因确认前，不启动大规模 HAL 编写。**（理论推测——首要怀疑）

即便如此，有一点可以先定：**只补内核 virtio-gpu 驱动是不够的**——即使根因最终不是 HAL 缺失，SurfaceFlinger 出画面也需要 gralloc + HWC + EGL 三件套（哪怕是 SwiftShader 软件版）。

---

## 四、路线 A：Android Emulator Ranchu 虚拟硬件

### 4.1 Ranchu 是什么

Ranchu 是新一代虚拟板（virtual board），对应 QEMU2 代码库；Goldfish 是旧板，对应 QEMU1（QEMU 1.10 重度修改版）。两者最主要差别：Goldfish 的专用 NAND/eMMC 虚拟设备被 VirtIO Block 取代。（文档确认）来源：`platform/external/qemu` 的 `ANDROID-KERNEL.TXT`、`DEVELOPMENT.md`。

现代 Ranchu 是混合模型：标准 VirtIO（blk/net/gpu/input/console/rng）+ 保留的少量 Goldfish 设备（framebuffer、battery、audio、events/输入）+ 最关键的 goldfish pipe（宿主—客体高速传输，用于 GPU 流、传感器注入等）。系统镜像内表现为 `/dev/block/vda`（virtio-blk）、`ro.hardware=ranchu`、`ro.kernel.qemu=1`，设备定义在 AOSP `device/generic/goldfish/`，含 HAL（audio/camera/sensors/gnss/radio/graphics.composer/gralloc 等，均带 `.ranchu` 后缀）与 `init.ranchu.rc`。（文档确认）

GPU 模拟不靠专用虚拟 GPU 设备，而是靠 QEMU Pipe 把 Guest 的 EGL/GLES 命令传到宿主 GL（EmuGL，`OpenglRender.so`/`EGL_translator.so`/`GLES*_translator.so`），模式有 `host`（默认）、`swiftshader`（软件）、已弃用的 `mesa`、`guest`；`hw.gpu.enabled=false` 时用 Guest 软件渲染，仅 GLES 1.x、很慢。（文档确认）来源：`GPU-EMULATION.TXT`。

**注意文档时效性**：`ANDROID-KERNEL.TXT` 的版本对照表只到 3.4/3.10/3.18，`DEVELOPMENT.md` 仍称 QEMU 2.7.0，均已过时。现代 Ranchu 内核是 GKI + `goldfish_*.ko` 模块。内核源码在 `kernel/goldfish.git`，分支 `android-goldfish-<version>`；构建脚本 `$AOSP/prebuilts/qemu-kernel/build-kernel.sh`，64 位 ARMv8 用 `--arch=arm64`，默认即 ranchu 配置；产物为 `kernel-qemu` 与 `vmlinux-qemu`。（文档确认）

### 4.2 goldfish vs ranchu

| 维度 | Goldfish（旧） | Ranchu（新） |
|---|---|---|
| QEMU 基线 | QEMU1，QEMU 1.10 重度修改版 | QEMU2，基于上游 QEMU 2.x，后持续 rebase |
| 板定义 | `goldfish` 虚拟板 | `ranchu` 虚拟板，基于 `virt` 板演化 |
| 存储 | 专用 Goldfish NAND + eMMC/MMC | **VirtIO Block**（客体内为 `/dev/block/vda`） |
| 网络/控制台 | 专用设备 + qemud（ttyS1） | VirtIO net/console 为主 |
| GPU | goldfish_fb + 经 qemu_pipe 的 EmuGL | **VirtIO GPU** 为主（现代版；另有 gfxstream/virtio-gpu-gfxstream），goldfish_fb 仅启动期保留；pipe 仍保留作 GPU 命令/传感器等高速通道 |
| 其他保留 | — | 仍保留 goldfish pipe、fb、battery、audio、events 等少量 legacy 设备；是“混合体而非彻底替换” |
| 内核 | goldfish 专用内核 | ranchu 配置内核；现代为 GKI + goldfish 模块 |
| 适用镜像 | Cupcake 至 Lollipop 旧 SDK 镜像 | Lollipop 之后、64 位 ARM 等新镜像 |

注意：`goldfish` 一词有三层含义——(a) 旧虚拟板；(b) 遗留设备/驱动名前缀（Ranchu 内核与 HAL 仍大量沿用 `goldfish_*` 命名）；(c) AOSP 设备目录 `device/generic/goldfish/`（Ranchu 产品仍放此处，产品为 `sdk_phone64_arm64`/`emu64a` 等）。不要把“名字含 goldfish”等同于“旧板”。（文档确认）

### 4.3 Ranchu 与上游 QEMU 的关系：是独立 fork，不是机型选项

**结论：是下游独立 fork。**（文档确认）

- `DEVELOPMENT.md`：QEMU1 是“基于非常古老的 QEMU 1.10，被大幅修改”；QEMU2 是“基于较新的上游 QEMU（2.7.0），做了少量 Android 专属修改”，且明确写道 Android 专属功能“太大而无法被上游接受”，因此用独立脚本跟踪上游 rebase；经典版“没有精力把 Android 专属改动移植到上游 QEMU”。
- 仓库即 `platform/external/qemu`（分支 `emu-master-dev`），另有 `android/android-emu/`（UI/功能层）、`android/android-emugl/`（EmuGL）、`android/qemu1/`（旧版）与 `android-qemu2-glue/`（QEMU2 胶水层）；QEMU2 二进制接受模拟器专属命令行，非原始 QEMU 选项。

**上游 QEMU 没有的 Ranchu/Goldfish 专属设备（清单）：**

| 设备/组件 | 性质 |
|---|---|
| `ranchu` 机型本身（基于 `virt` 板演化而来） | 上游只有 `virt`，无 `ranchu` 机型；第三方在上游 QEMU 11.1.1 上实测：“Google emulator images on upstream QEMU: no `ranchu` machine → no display, no GPU pipe” |
| `goldfish_device_bus` / pdev_bus（平台总线枚举） | MMIO 平台设备，`hw/android/goldfish/device.c` |
| `goldfish_interrupt_controller` / goldfish PIC（32 路） | `hw/android/goldfish/interrupt.c` |
| `goldfish_timer`、`goldfish_rtc` | 同上 |
| `goldfish_tty`（多实例串口，ttyS0=内核日志、ttyS1=旧 qemud） | 同上 |
| `goldfish_fb`（简单帧缓冲，启动期显示；GPU 启用后仅启动用） | `hw/android/goldfish/fb.c`；新版在 `hw/display/goldfish_fb.c` |
| `goldfish_audio`（44.1kHz 立体声出 / 8kHz 单声道入） | `hw/android/goldfish/audio.c` |
| `goldfish_battery` | `hw/android/goldfish/battery.c` |
| `goldfish_nand` / goldfish MMC（仅 Goldfish 旧板；Ranchu 已用 virtio-blk 取代） | `hw/android/goldfish/`（旧板） |
| `goldfish_events`（键盘/鼠标/触屏输入） | Ranchu 保留的 legacy 设备之一 |
| `goldfish_pipe` / `qemu_pipe`（MMIO 高速宿主—客体复用通道，`/dev/qemu_pipe`→`/dev/goldfish_pipe`，GPU/传感器/相机等走它） | `hw/misc/goldfish_pipe.c`；GPU 模拟“不依赖特定虚拟 GPU 设备，而用 QEMU Pipe” |
| `goldfish_sync`（同步围栏）、`goldfish_dma` / address space（宿主—客体共享内存） | `hw/misc/goldfish_sync.c` 等 |
| `virtio-goldfish-pipe`（非标准 virtio 设备） | 在 fork 内 `android-qemu2-glue/emulation/virtio-goldfish-pipe.cpp`；把 DRM VIRTGPU ioctl 跑在定制的 virglrenderer 实现之上，与宿主 pipe 服务对话，主要用于图形 |
| EmuGL/gfxstream 宿主 GPU 栈、AndroidEmu 库、Qt UI、快照、传感器/电话/电池模拟、qemud | 全部在 `android/` 胶水层，不属于上游 QEMU |

**已在上游 QEMU 的部分（不需移植）：**标准 VirtIO 设备——virtio-blk、virtio-net、virtio-gpu、virtio-input、virtio-console、virtio-rng。Ranchu 的存储/网络/控制台/输入/GPU 基础都可走这些。（文档确认）

**重要区分**（理论推测，需核实）：客体内核侧的 goldfish 驱动（`goldfish_pipe`、`goldfish_battery`、`goldfish_sync` 等）多已 upstream 到 Linux 内核，所以客体驱动不是主要障碍；障碍在**宿主侧 QEMU 设备实现 + pipe 服务 + GPU 翻译层**，这些从未进上游。本次未逐行核对上游 QEMU 11.1.2 的 `hw/` 树，此句为基于 fork 文档的推断，未做源码级穷举。

### 4.4 Emulator ARM64 系统镜像的实际获取途径

**结论：能通过 SDK 直接下载现成镜像，无需自编译 AOSP；但不能直接在上游 QEMU 上用，也不能随 PocketVM 分发。**（文档确认）

- 标准方式：`sdkmanager "system-images;android-<API>;google_apis;arm64-v8a"`（或 `google_apis_playstore` 变体；Play 版无 root，`google_apis` 版为 userdebug、可 `adb root`）。Android Studio 的 AVD Manager“Other Images”页也可下载 ARM64 镜像。
- 官方仓库索引（2026-11-15 实读 live XML `https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml`）确认的包：API 34 Google APIs ARM64 v8a rev 14（`arm64-v8a-34_r14.zip`）；API 33 rev 17；API 36 rev 7、36.1 rev 4；另有 `google_apis_playstore` 与 `google_apis_ps16k`（16KB 页）变体。修订号会随时间变化，使用前应重查该 XML。
- **镜像内容（部分核实）：**AOSP 官方打包脚本证实 emulator 镜像集包含 `system.img`（由 `system-qemu.img` 改名）、`vendor.img`、`ramdisk.img`、`kernel-ranchu`（或 64 位的 `kernel-ranchu-64`）、`userdata.img`、`encryptionkey.img`、`build.prop`、`source.properties`。**未核实**：本次未实际下载并解压任一 SDK zip，未逐项确认 SDK 分发 zip 内是否与上述 AOSP 打包清单完全一致（例如是否含 `vendor.img`/`kernel-ranchu`）。多方次级证据一致指向“包含”，但应以下载解压一次为准。
- **能否脱离 Emulator、在上游 QEMU 上单独用？结论：名义上文件齐全，实际上不行。** 第三方实测总结“Google emulator images on upstream QEMU: no `ranchu` machine → no display, no GPU pipe”；上游 QEMU 只能跑为 `virt` 板构建的镜像。机理：镜像内的 `vendor.img`/HAL 与 `init.ranchu.rc` 期望 ranchu 板（`ro.hardware=ranchu`、`ro.kernel.qemu=1`、goldfish pipe/fb/battery 等）；上游 `virt` 机型无这些设备，`kernel-ranchu` 也会因缺少设备而无法完成图形/输入/电源等初始化。即使内核能到 `/init`、能 `adb`，也不等于能到 `boot_completed`/SurfaceFlinger。另有 AOSP 内部报告佐证：GSI（`generic_arm64`）无 `kernel-ranchu`、`vendor.img`、`userdata.img`，不是完整 emulator 镜像；完整 emulator 镜像需 `sdk_phone64_arm64`（`emu64a`）+ `emu_img_zip` 目标产出。
- **许可风险提示**（文档确认，非法律意见）：SDK 许可协议（上述 XML 开头全文）规定 SDK 仅授权“为兼容实现开发应用”，不得用 SDK 开发另一 SDK、不得复制/修改/再分发 SDK 或其部分（开源许可组件除外）。把 SDK 系统镜像直接内置进 PocketVM 对外分发，存在许可合规问题，需另行评估；自编译 AOSP emulator 镜像（`sdk_phone64_arm64`）是更干净的来源（许可意义上；但它仍是 Emulator 产品配置，需按 §5.4 改造成面向 virt 的产品配置才能作最终产品）。

### 4.5 移植 Ranchu 到 PocketVM 的可行性评估

**总体评级：大。**

- **源码/文档确认——移植面极大：**Ranchu 不是一个可开关的机型，而是一整套 fork：ranchu 机型定义 + `hw/android/goldfish/*` 全套 MMIO 设备 + goldfish pipe 服务 + `android-qemu2-glue` + EmuGL/gfxstream 宿主 GPU 栈 + Qt UI/快照/传感器等 AndroidEmu 库。官方文档自认这些 Android 专属功能“太大而无法被上游接受”，且 QEMU2 基线在文档中仍为 2.7.0，rebase 到 11.1.2 意味着跨越约 9 年上游变迁（QOM/内存/virtio/TCG 接口均已大改），不是简单 cherry-pick。
- **文档确认——最小可用子集可缩小，但仍非小：**若目标不是“完整 Ranchu”，而是“能到 boot_completed 的 Android”，理论上可只用上游已有的 virtio-blk/net/gpu/input/console + GKI/goldfish 内核模块 + 自编译 `virt` 板 AOSP 镜像（或 Cuttlefish 路线），绕开 pipe 与 EmuGL，GPU 用 virtio-gpu + SwiftShader。但这已不属于“移植 Ranchu”，且 vendor HAL/init 需重写（`ro.hardware` 从 ranchu 改为 virt、去掉 goldfish HAL 依赖），相当于另造一条虚拟设备路线。
- **理论推测——宿主侧 GPU 是最大未知数：**PocketVM 宿主是 Android 手机（无 Root、无 KVM、TCG），而 EmuGL/gfxstream 假定宿主有桌面 GL/ANGLE/Metal/Vulkan 与 virglrenderer；Android 宿主上能否提供等价宿主 GL、以及能否把 fork 的桌面构建系统（CMake/Qt/SDL）移植到 Android NDK，均无公开资料证实。TCG 本身不是 blocker（同架构 ARM64 仍可用 TCG，只是慢；PocketVM 跑 Windows 已证明 TCG 路线可行），真正的 blocker 是 GPU 翻译层与 fork 构建移植。
- **未核实项：**未读上游 QEMU 11.1.2 源码确认 ranchu/goldfish 设备是否确实全缺；未实测 SDK 镜像在上游 QEMU 11.1.2 上的实际失败点；未评估 fork 代码量（行数）与 Android NDK 构建可行性。若要把“大”量化为人月，需先做一次源码 diff 统计（fork vs 上游 11.1.2 的 `hw/`、`android/` 增量）与一次最小 virtio-only 启动实验。

---

## 五、路线 B：标准 QEMU virt + 自建 Android Vendor 层

### 5.1 Guest Kernel 要求

权威基准是 AOSP 的 `kernel/configs/android-base.config`（GKI 配置片段）；QEMU virt 专用项来自 Collabora 的 Android-on-QEMU 指南与 AOSP 10 的 virt 实践帖（年代较老，但 virtio 清单至今仍是该组合的唯一成文清单，需以 GKI 片段为准做取舍）。（文档确认）

| 配置 / 驱动 | 作用 |
|---|---|
| `CONFIG_ANDROID=y` | Android 子系统总开关 |
| `CONFIG_ANDROID_BINDER_IPC=y` | Binder IPC，所有 Framework 服务的进程间通信，必需 |
| `CONFIG_ANDROID_BINDERFS=y` + `CONFIG_ANDROID_BINDER_DEVICES="binder,hwbinder,vndbinder"` | 动态 binder 设备节点（/dev/binder、hwbinder、vndbinder 三域） |
| `CONFIG_ASHMEM=y`，并逐步由 `memfd` 替代 | 匿名共享内存；新代码用 `memfd_create()`；`androidboot.use_memfd=true` 可切换 |
| `CONFIG_DMABUF_HEAPS_SYSTEM=y` | DMA-BUF heaps，ION 的替代品，图形 buffer 分配 |
| `CONFIG_DRM_VIRTIO_GPU=y` | virtio-gpu DRM 驱动，图形的前提 |
| `CONFIG_VIRTIO=y`、`CONFIG_VIRTIO_PCI=y`、`CONFIG_VIRTIO_PCI_LEGACY=y`、`CONFIG_VIRTIO_MMIO=y`、`CONFIG_VIRTIO_MMIO_CMDLINE_DEVICES=y` | virtio 总线；virt 机型主要用 MMIO/PCI 两种传输 |
| `CONFIG_VIRTIO_BLK=y` / `CONFIG_BLK_MQ_VIRTIO=y`、`CONFIG_SCSI_VIRTIO=y` | virtio-blk / virtio-scsi 磁盘 |
| `CONFIG_VIRTIO_NET=y` | virtio-net |
| `CONFIG_VIRTIO_INPUT=y` | virtio 键盘/触摸/多点触控 |
| `CONFIG_VIRTIO_CONSOLE=y` | virtio-serial/console（logcat/调试通道） |
| `CONFIG_VIRTIO_BALLOON=y`、`CONFIG_HW_RANDOM_VIRTIO=y`、`CONFIG_VIRTIO_DMA_SHARED_BUFFER=y` | balloon/熵源/virtio DMA 共享缓冲（图形共享缓冲需要后者） |
| `CONFIG_DRM_GEM_SHMEM_HELPER=y` | DRM GEM 共享内存后端（virtio-gpu 依赖） |
| `CONFIG_DM_VERITY=y`、`CONFIG_FS_VERITY=y`、`CONFIG_BLK_DEV_DM=y`、`CONFIG_DM_CRYPT=y` | Verified Boot / 分区校验（GSI/AVB 场景） |
| `CONFIG_TRACE_GPU_MEM=y` | GPU 内存追踪 |

补充：

- 内核版本基线：Android 17 的 ACK 分支是 `android17-6.18`（上游 LTS 6.18）；Android 16 为 `android16-6.12`。GKI 配置片段按版本放在 `kernel/configs/<letter>/android-<ver>/android-base.config`。（文档确认）
- **ION 已死**：不要再找 `CONFIG_ION`；图形内存走 DMA-BUF heaps。（文档确认）
- **低内存**：不要 `CONFIG_ANDROID_LOW_MEMORY_KILLER`，旧版 in-kernel LMK 已废弃；现代 Android 用 userspace `lmkd` + PSI（`CONFIG_PSI`）。GKI 的 android-6.12 `android-base.config` 明确写 `# CONFIG_ANDROID_LOW_MEMORY_KILLER is not set`。（文档确认）
- **virt 机型的 DT**：QEMU `virt` 由 QEMU 生成 DTB 传给 Guest，不需要像真机那样提供厂商 DTB；但 Android 13+ 的 first-stage fstab 是从 DT 的 `/firmware/android` 节点读的（见 §3.1），所以“用 QEMU 的 DT”不等于“fstab 自动有了”——DT 里必须有 Android fstab 节点，或改用文件 fstab 回退路径。（文档确认 + 理论推测）
- **本地内核对照**（源码确认，2026-11-15 修订时已核对 `out/dist-qemu-virt/.config`）：本地 `Image-qemu-virt`（6.18.21-android17-5）已把 virtio blk/net/console/input/mmio/PCI + virtio-gpu 内置 `=y`，实验一证实它们工作；`CONFIG_ANDROID_BINDER_IPC=y`、`CONFIG_ANDROID_BINDERFS=y`、`CONFIG_ASHMEM=y`、`CONFIG_PSI=y`、`CONFIG_VIRTIO_CONSOLE=y`、`CONFIG_DRM_VIRTIO_GPU=y`（含 KMS）均已开。**缺口：`CONFIG_DMABUF_HEAPS_SYSTEM` 明确是 `# CONFIG_DMABUF_HEAPS_SYSTEM is not set`**——图形 buffer 分配用的 DMA-BUF heaps 没开，M1 必须补开重编。其余未逐项核对的配置仍按 §5.1 清单在 M1 复核。

### 5.2 VirtIO 与 Android HAL 的适配方案

**原则：Guest 侧缺的不是一个内核，而是一整套 vendor——GKI 配置 + fstab（DT 或文件）+ vendor 分区（gralloc/HWC/EGL/sepolicy/init.rc）。**（文档确认 + 理论推测）

| 层 | 组件 | 选型（路线 B） | 说明 |
|---|---|---|---|
| 内核 | virtio-blk/net/gpu/input/console/rng | 上游 QEMU 11.1.2 已有；本地 6.18 内核已内置 | （已验证）virtio-blk 认盘、串口工作 |
| 内核 | binder/binderfs/ashmem→memfd/DMA-BUF heaps | 按 §5.1 清单核对本地内核 `.config`，缺的补开重编 | M1 任务 |
| Vendor | gralloc | **minigbm**（Chromium minigbm，基于 GBM/DMA-BUF） | Cuttlefish/ChromeOS 系虚拟设备的标准 gralloc；`androidboot.hardware.gralloc=minigbm`；以 APEX 形式提供 |
| Vendor | HWC | **drm_hwcomposer**（通用 DRM/KMS 合成器，直接操作 DRM plane/CRTC/connector） | 上游项目，AOSP 以 APEX 提供；`androidboot.hardware.hwcomposer=drm` + `display_finder_mode=drm` |
| Vendor | EGL/Vulkan（阶段 1） | **SwiftShader**（Vulkan ICD 见 `vulkan.pastel.so`；EGL/GLES 另见下文拆分，须独立验证） | Cuttlefish `guest_swiftshader` 模式：`gralloc=minigbm` + `vulkan=pastel` + hwcomposer；但 `vulkan.pastel.so` 只是 SwiftShader 的 Vulkan 驱动，不能证明 EGL/GLES 路径已解决 |
| Vendor | EGL/Vulkan（阶段 2） | **Mesa virgl / Venus**（`vulkan-drivers=virtio`） | Cuttlefish `drm_virgl` 模式；需宿主 virglrenderer + 宿主 GL（见 §6.3，短期不可用） |
| Vendor | EGL/Vulkan（阶段 3） | **gfxstream guest HAL**（`egl=emulation`、`gltransport=virtio-gpu-asg`、`vulkan=ranchu`） | 需宿主 gfxstream + rutabaga（见 §6.3，长期） |
| Vendor | 音频/传感器/电源 | 先 stub（返回空实现），后补 | Cuttlefish 的做法是宿主守护进程提供；手机宿主上先 stub，不阻塞启动 |
| Vendor | sepolicy | 本地 `vendor-sepolicy/` 为起点，启动验收后收紧；先行阶段可用 permissive | （未核实）启动是否放行，见 §3.2 |
| Vendor | fstab + init.rc + props | 文件 fstab 放 vendor ramdisk（回退路径），`ro.hardware` 改为 virt（不用 ranchu） | 见 §8 |

**图形 HAL 必须拆成五个独立验证项（不要拼起来就当完成）：**

| 组件 | 职责 | 本次状态 |
|---|---|---|
| Vulkan ICD | 提供 Vulkan 驱动接口；SwiftShader 对应 `vulkan.pastel.so` | （文档确认）Cuttlefish `guest_swiftshader` 模式有此配置；Android 17 对应的 SwiftShader 构建产物未核对 |
| EGL | 提供 EGL 上下文与显示接口（SurfaceFlinger 创建 EGLContext/EGLSurface 走它） | （未核实）Android 17 的 EGL 实现及其依赖未核对 |
| GLES | 提供 OpenGL ES 渲染接口（SurfaceFlinger 的 GLES 合成路径走它） | （未核实）GLES 实现、SurfaceFlinger 实际采用 GLES 还是 Vulkan 合成路径，均未核对 |
| Gralloc | 分配和管理图形 Buffer；minigbm 需 allocator HAL 与 mapper HAL 各自实现 | （未核实）minigbm 的 allocator 与 mapper HAL 未核对 |
| HWC | 硬件合成接口；drm_hwcomposer 直接操作 DRM plane/CRTC/connector | （未核实）drm_hwcomposer 对 virtio-gpu DRM/KMS 的兼容性未核对 |

另须核对：HAL 用 AIDL 还是 HIDL，以及对应的 VINTF manifest 是否齐全。上述未核实项全部进 §14，M3 逐项验证——minigbm、drm_hwcomposer、SwiftShader 三个拼起来不等于方案完成。（未核实）

**不要用的东西：** goldfish gralloc（旧版 Emulator 遗留路径，经 goldfish-pipe 共享内存）、ranchu HWC 的 HostFrameComposer（需宿主 gfxstream）。这两者都依赖上游没有的 goldfish_pipe，选了就等于变相走路线 A。（文档确认）

### 5.3 图形渲染方案（三阶段）

Cuttlefish 在 Android 17 接受的完整 `--gpu_mode` 集合（`assemble_cvd/flags.cc`）：`{auto, custom, drm_virgl, gfxstream, gfxstream_guest_angle, gfxstream_guest_angle_host_swiftshader, gfxstream_guest_angle_host_lavapipe, guest_swiftshader}`。（文档确认）

| 阶段 | Gralloc | Composer | EGL/Vulkan | 目标 | 现实性 |
|---|---|---|---|---|---|
| 第一阶段：软件渲染 | minigbm | drm（DRM/KMS 送显） | SwiftShader（Vulkan ICD `vulkan.pastel.so`；EGL/GLES 须按 §5.2 五项独立验证） | boot_completed + Launcher 静态画面 | **唯一短期可达的阶段**；virtio-gpu 只做 2D/KMS，不加速 3D；gralloc/HWC/EGL/GLES/Vulkan 五项须在 M3 逐项验证通过，缺一项即不算完成 |
| 第二阶段：VirtIO-GPU 2D + Mesa 加速 | minigbm | drm | Mesa virgl / Venus | 基本 3D 可用 | 需宿主 virglrenderer + 宿主 GL；Android App 进程内无此后端，短期不可用 |
| 第三阶段：GLES / Vulkan 加速（gfxstream） | gfxstream gralloc 或 minigbm | ranchu（HostFrameComposer） | gfxstream guest HAL | 接近可用的图形性能 | 需宿主 aemu + gfxstream + rutabaga + 真实宿主 GLES/Vulkan；长期 |

**SwiftShader 在 TCG 下的性能现实性：**

- Android Emulator 官方文档（`GPU-EMULATION.TXT`）写明：内置 Guest 软件渲染器“very slow and only supports GLES 1.x, so most applications will not run with it properly”；`swiftshader` 模式“generally slower than `-gpu host`”，但可在所有平台工作，且可利用 CPU SIMD、降低 AVD 分辨率会更流畅。（文档确认）
- Cuttlefish 的 `guest_swiftshader` 是官方保留的无 GPU 服务器模式，说明“能跑”是成立的，但那是 **KVM + x86/ARM64 服务器**的前提。（文档确认）
- （理论推测）TCG 下的现实性：SwiftShader 本身是 CPU 光栅化器，每一帧都要在 Guest CPU 上跑大量 SIMD 运算；而 TCG 下 Guest CPU 的每条指令本身已是翻译执行（同一颗手机 CPU 既要翻译 Guest 指令又要跑光栅化），属于“双重 CPU 税”。没有找到任何“ARM64 TCG + SwiftShader 跑 Android 桌面”的实测帧率数据（本次检索无此结果，不应编造数字）。可作出的判断只有：**开机动画/Launcher 静态画面可能勉强出图，任何持续动画、滑动、视频都极可能卡顿到不可用；应把 SwiftShader 定位为“验证图形链路通不通”的调试模式，而非可用模式**。若要可用，必须走到 virgl 或 gfxstream 的宿主加速——而那又回到宿主 GPU 不可达问题。

### 5.4 是否存在现成的 Kernel + Vendor 镜像组合？——没有

**结论：未找到现成组合。**（文档确认）

| 候选 | 为什么不能直接用 |
|---|---|
| Cuttlefish `aosp_cf_arm64_only_phone`（ci.android.com） | Kernel + Vendor 最全，但它是为 `launch_cvd` + Linux 宿主守护进程 + KVM 设计的，不是为裸 `qemu-system-aarch64 -machine virt` 设计的。默认用 **crosvm**，QEMU 只是可选项；设计目标是“use upstream Kernel/QEMU、use virtio devices、leverage kvm for acceleration（thus, it's only available on Linux）”。Cuttlefish 客户机依赖一打 Linux 宿主守护进程（keymint/gatekeeper `secure_env`、`config_server`、经 virtconsole/vsock 的 logcat/tombstone 接收器）；第三方实践明确记录：“Cuttlefish guests depend on a dozen Linux-only host daemons… They cannot boot to a working system on plain QEMU on macOS”、“boots the kernel, but Android hangs waiting for the host services.” |
| Emulator `sdk_phone64_arm64` / kernel-ranchu | Kernel + Vendor 最全，但绑定 Google QEMU 分支的 ranchu 机器，上游 virt 跑不起来（见 §4.4）。把 `-machine type=virt` 强行套在 goldfish 产物上只能到 `/init` 和 adb，`sys.boot_completed` 为空、zygote SIGABRT 循环——与 PocketVM 在 PC QEMU 上看到的症状同构。 |
| Waydroid（LineageOS 20，arm64）/ Redroid（Docker 内 Android，11–15） | 能在上游 virt 里跑，但那是“Linux Guest 内再套 Android 容器”，共享 Guest 内核（需 binder），不是原生 Android Guest；图形走容器路径，与路线 B 要验证的 virtio-gpu 图形栈不是同一条链。 |
| Linaro “Run Android using QEMU” | 该页只讲 Emulator 与 Cuttlefish 两条路，没有提供可下载的 virt 专用 kernel+vendor。 |

**最现实的起点：建立一个真正面向 QEMU virt 的 AOSP Device/Product 配置。**（理论推测）自建 `sdk_phone64_arm64` 仍是 Android Emulator 产品配置，自己编译也不会自动变成适用于上游 QEMU virt 的产品；Cuttlefish 磁盘同样有自己的设备依赖与 Linux 宿主服务。两者只能作参考与对照，不能直接改几个属性就当最终产品。

可复用的部分：AOSP GKI、通用 Android Framework、minigbm、drm_hwcomposer、SwiftShader、AOSP 已有的音频/输入/电源等实现。但产品配置、设备树、fstab、VINTF manifest、init 脚本、sepolicy 必须围绕 `virt` 虚拟硬件设计。另一条对照线是在 ARM64 Linux Guest 内跑 Waydroid/Redroid——但后者验证不了原生 virtio-gpu 图形栈。（理论推测）

---

## 六、两条路线对比与推荐

### 6.1 对比表

| 维度 | 路线 A：Ranchu（移植 fork） | 路线 B：virt + 自建 Vendor |
|---|---|---|
| QEMU 侧工作量 | 大：ranchu 机型 + goldfish 全套 MMIO 设备 + pipe 服务 + glue + EmuGL/gfxstream，跨越约 9 年上游变迁 rebase | 小：上游 11.1.2 已有全部所需 virtio 设备；阶段 1 不需重建 QEMU |
| Guest 内核 | GKI + goldfish 模块（kernel-ranchu，绑定 fork） | 本地 android17-6.18 内核已编好 virtio 全内置；只需核对 binder/ashmem/DMA-BUF 等配置 |
| Vendor HAL | 现成的 ranchu HAL（device/generic/goldfish），但依赖 goldfish_pipe/sync | 需组装：minigbm + drm_hwcomposer + SwiftShader（Cuttlefish `guest_swiftshader` 配置可抄） |
| 系统镜像 | SDK 可直接下载，但许可禁分发、绑定 fork | GSI（已有）+ 自建 vendor；vendor 须按面向 virt 的 Device/Product 配置自建，`sdk_phone64_arm64` 只是 Emulator 产品配置、不能直接当 virt 产品 |
| 图形加速 | EmuGL/gfxstream（宿主 GPU 栈需移植到 Android NDK，无现成路径） | 阶段 1 软件渲染可达；阶段 2/3 同样卡在宿主 GPU 后端 |
| 与现有架构的关系 | 另起一套执行层，与 Windows/Linux 链路并行但互不复用 | 与现有 virt 链路统一，Android 只多一个独立 Profile |
| 最大风险 | fork 移植 + 宿主 GPU 栈移植（双重大工程） | Vendor 层组装 + SwiftShader 在 TCG 下的可用性 |
| 长期维护 | 跟随 Google fork 的 rebase，持续成本高 | 跟随上游 QEMU + AOSP，与 PocketVM 现有维护方式一致 |

### 6.2 推荐：路线 B，理由

1. **复用已有资产最多。** PocketVM 已有 virt 机型、QEMU 11.1.2、本地 6.18 内核（virtio 全内置）、GSI 拼装、Android 平台位与新建预设。路线 B 是在这些资产上补最后一块 Vendor；路线 A 是另起一套。
2. **官方技术栈原则。** PocketVM 已定“应用层全用官方技术栈”（2026-11-12）；路线 B 用的全是上游 QEMU + AOSP 官方组件（minigbm、drm_hwcomposer、SwiftShader、GKI），路线 A 用的是 Google 下游 fork。
3. **风险可分段验证。** 路线 B 的 M1–M3 可以在主机 QEMU 上先验证启动链与图形链路，不动 App 代码；失败成本低。路线 A 在看到任何成果之前就要先投 fork 移植。
4. **不关闭路线 A 的门。** 路线 B 的阶段 3（gfxstream）与 Ranchu 的 GPU 技术同源；将来若宿主 GPU 后端问题解决，可以再评估是否引入 `virtio-goldfish-pipe` 等单个设备，而不是整体移植 fork。
5. **不破坏已跑通的链路。** Android 走独立 Profile（见 §9），Windows/Linux 的启动参数、磁盘格式、显示配置一律不动。

### 6.3 virtio-gpu-rutabaga / gfxstream 在 PocketVM 环境下的专项结论

QEMU 官方文档把 virtio-gpu 分为三档后端（文档确认）（https://www.qemu.org/docs/master/system/devices/virtio/virtio-gpu.html）：

- **2D 默认后端**：只做 2D 操作，3D 需 Guest 用软件渲染器（Mesa LLVMpipe/Lavapipe 或 SwiftShader）。
- **virglrenderer（`virtio-gpu-gl`）**：Guest 的 OpenGL 调用先转成 Gallium3D 中间表示，发到宿主，再由宿主 `virglrenderer` 库还原成 OpenGL 调用。默认 Guest OpenGL 限 4.3；开 `hostmem` + `blob=true` 可到 4.6。
- **rutabaga_gfx（`virtio-gpu-rutabaga`）**：以 Rust 库 `rutabaga_gfx` 为多路复用层，底下可接 `virglrenderer` 或 `gfxstream`，并自带 cross-domain Wayland 路径；另有 `vhost-user-gpu` 把图形栈放独立进程。

capset 是 virtio-gpu 的“上下文方言”选择器（文档确认）：`virgl`（默认）、`venus`（Vulkan 直通，需 virglrenderer ≥1.0.0 + `hostmem` + `blob=true`，宿主 Linux 6.13+）、`drm_native_context`、`gfxstream-vulkan`、`cross-domain`、`x-gfxstream-gles` / `x-gfxstream-composer`（**Android Guest 专用、实验性**：GLES 转发 + 合成器转发）。rutabaga 本身不渲染，它是分发器；真正干活的是它底下的 gfxstream 或 virglrenderer。QEMU 文档原文：“With the gfxstream rendering mode, GLES and Vulkan calls are forwarded to the host with minimal modification.”

**在 ARM64 + TCG 下是否可行？**（文档确认）加速器与后端正交。QEMU 文档对 rutabaga 的宿主要求只写了“Linux 6.13+（Vulkan+Wayland 直通）”和 `hostmem`，**没有写“必须 KVM、禁止 TCG”**。virtio-gpu 设备本身不关心 Guest 指令是怎么执行的。因此从协议层面说：ARM64 + TCG + rutabaga **不被文档排除**，QEMU 8.2+ 即有此设备，PocketVM 的 QEMU 11.1.2 在版本上已包含它。Guest 内核前提只有一条：`CONFIG_DRM_VIRTIO_GPU`。

**宿主侧依赖链很长，不是只加一个 QEMU 开关**（文档确认）（crosvm book、Linaro 教程，两者一致）：

1. QEMU 须以 `--enable-rutabaga-gfx`（Meson 选项 `rutabaga_gfx`，依赖 `rutabaga_gfx_ffi` pkg-config）编译。
2. 须先从源码构建三件套：`aemu`（`platform/hardware/google/aemu`，cmake，`-DAEMU_COMMON_BUILD_CONFIG=gfxstream`）；`gfxstream` 宿主库（`platform/hardware/google/gfxstream`，meson）；`rutabaga_gfx` FFI（crosvm 仓库 `rutabaga_gfx/ffi`，需 Rust/cargo；若 pkg-config 没找到 gfxstream，会**静默建成无 gfxstream 功能**的版本，Linaro 教程特别提醒要检查这一步）。
3. 宿主系统依赖（Linaro Debian 清单）：`libdrm, libglm, libstb, libegl, libgles, libvulkan, vulkan-validationlayers-dev`。即 gfxstream 宿主最终要落到一个**真实的宿主 GLES/Vulkan 实现**上。
4. 完整窗口化还要求宿主支持 `dma_buf` 与 `dma_fence`。

**在 Android 手机宿主（无 Root）上是否现实？**（理论推测）短期不现实，是路线 B 的最大风险点：

- 没有现成的 Android-host 构建路径。所有读到的构建文档都是 Debian/Linux 桌面；没有任何文档给出“用 NDK 在 Android 上构建 gfxstream host + rutabaga FFI”的步骤。Mesa 的 gfxstream guest 侧才刚在 2026-01 加 Android 支持，且其提交说明明确写“nobody builds gfxstream_vk + Android via the Android NDK；Using Soong is an absolute requirement”。
- 宿主 GPU 不可达。gfxstream host 最终要调用宿主 GLES/Vulkan。Android 手机上宿主 GPU 驱动只暴露给系统进程/有权限的 App（EGL/Vulkan 需经系统图形栈），QEMU 作为普通 App 进程没有 `/dev/dri/*`、没有 libdrm 通道、没有 Wayland socket；`cross-domain` 的 Wayland 直通在 Android 上根本没有对应物。`wsi=surfaceless/headless` 虽可去掉窗口，但仍需一个能实际执行 GLES/Vulkan 的宿主后端。
- 即使绕过，性能仍是 TCG × 转发两层开销。

**结论：路线 B 若走 rutabaga/gfxstream，第一步不是改 QEMU 命令行，而是先在 Android NDK 环境下把 `aemu + gfxstream host + rutabaga_gfx_ffi` 编出来并接到一个可用的宿主 GLES/Vulkan 后端；在有这一步的实证之前，应按“未验证、短期不可用”处理。** 相比之下，virglrenderer 路径的宿主依赖（virglrenderer + 宿主 GL）同样不存在于 Android App 进程中，问题同构，只是少了 Rust/aemu/gfxstream 三件套。

---

## 七、虚拟硬件清单（Android 专用，建议测试配置）

以下为建议测试配置，不是已验证可用的组合；每项标注了验证状态。不要直接复用 Windows 的全部设备参数。

| 虚拟设备 | 建议 | 依据 / 状态 |
|---|---|---|
| Machine | `virt` | （源码确认）现有链路；Ranchu 机型上游没有，不用 |
| CPU | `cortex-a76` 起步，之后测试 `max` | （理论推测）PocketVM 档位已有 `cortex-a76`（Windows 需 ARMv8.1 LSE）；本地 PC 实验用的是 `cortex-a57`；M1 需验证 `max` 下 GKI 内核是否正常 |
| GIC | GICv3（`gic-version=3`） | （理论推测）现有默认值；注意 Wear OS 旧内核（5.4）只支持 GICv2 的教训，6.18 GKI 应支持 GICv3，M1 实测确认 |
| 内存 | 4 GB 起步，建议测试 6 GB | （源码确认）新建预设已强制 ≥4 GB；6 GB 需过 Android 17 App 内存上限验证（未验证） |
| vCPU | 4 核起步 | （源码确认）现有默认值；Windows 曾因 OOBE 0x3B 从 8 核降到 4 核，Android 先从 4 核起步 |
| 启动方式 | 先 `-kernel` 直启验证，再评估 UEFI 路径 | （理论推测）本地 PC 实验走 `-kernel` 直启；App 现有链路只走 UEFI pflash；M1 二选一 |
| 磁盘 | VirtIO Block（单张 GPT 物理分区盘，或分开挂 system/vendor/userdata/metadata） | （源码确认）GsiAssembler 已产 GPT 盘；实验一证实 virtio-blk 认盘 |
| 网络 | VirtIO Net（SLIRP NAT，同 Windows） | （源码确认）现有链路；Guest 需 virtio-net 驱动（本地内核已内置） |
| 输入 | USB Tablet + Keyboard（先沿用现有默认值）；备选 virtio-input | （源码确认）现有默认值；本地内核 virtio-input 已内置；Android 触屏映射是 M6 任务 |
| 图形 | virtio-gpu-pci（2D/KMS 送显） | （已验证）Wear OS 实验证实 DRM 驱动初始化成功；Windows 的 GOP 缺陷不适用于 Android（Android 走 DRM，不走 GOP） |
| 显示 | 固定 720p 测试分辨率，通过后再试 1080p | （理论推测）SwiftShader 负载随分辨率线性增长，先低后高 |
| 串口 | `-serial unix`（已有，默认开启）+ `console=ttyAMA0` | （源码确认）`QemuCommandBuilder.kt:325`；实验一证实 PL011 串口工作 |
| QMP | unix socket（已有） | （源码确认）`QemuCommandBuilder.kt:308`；用于启停、状态、快照 |
| RNG | virtio-rng（STANDARD 档已有） | （源码确认）避免首次启动熵阻塞 |
| TPM | 默认关闭（与 Windows 一致） | （源码确认）Android 不需要 TPM；`TPM_ENABLED_BY_DEFAULT = false` |
| vsock | 暂不引入 | （理论推测）Cuttlefish 用 vsock 接宿主服务，但裸 QEMU 启动不需要；后续按需评估 |

---

## 八、Kernel / Vendor / System 镜像组成

### 8.1 三件套分工

| 层 | 组成 | 来源 | 状态 |
|---|---|---|---|
| Kernel | `Image`（GKI，android17-6.18，virtio 全内置 + binder/binderfs/ashmem→memfd/DMA-BUF heaps/DRM virtio-gpu） | 本地 `~/workspace/kernel-android17-6.18/out/dist-qemu-virt/Image` | （源码确认）virtio 部分已内置并验证工作；binder/binderfs/ashmem/PSI/virtio-console/DRM virtio-gpu 已确认 `=y`（2026-11-15 修订时核对）；缺口是 `CONFIG_DMABUF_HEAPS_SYSTEM` 未开，M1 补开重编；其余未逐项核对的配置 M1 继续复核 |
| Vendor_boot / first-stage ramdisk | 交付物是 **initramfs.cpio**（first-stage `init` + 动态链接器与依赖库 + fstab（文件形式，vda/vdb/vdc）+ sepolicy + `init.virt.rc`，`ro.hardware=virt`）；`vendor_boot.img` 只是可能的上游容器，见 §9.2 | 自组装：init/linker/libs 取自 GSI system.img（`/system/bin/init` 等文件存在），fstab 与 sepolicy 自写（本地 `vendor-sepolicy/` 为起点）；本地已有一份组装好的 `ramdisk.cpio.gz`（见 §2.2 补充），但其 init 在启动早期 SIGABRT 未修复 | （理论推测）组装路径；ramdisk 存在但 init 未跑通 |
| Vendor 镜像 | vendor.img：gralloc（minigbm）+ HWC（drm_hwcomposer）+ EGL/Vulkan（SwiftShader，五项独立验证，见 §5.2）+ 音频/传感器/电源 stub + `manifest.xml`（VINTF）+ `fstab.virt`——**这是最终组成，分阶段交付：M2 只做基础框架（音频/传感器/电源 stub + VINTF + init + sepolicy），图形 HAL（gralloc/HWC/EGL/Vulkan）是 M3** | 按面向 virt 的 Device/Product 配置自建（见 §5.4）；`sdk_phone64_arm64` 与 Cuttlefish 磁盘只能作参考与对照，不能直接当最终产品；SDK 下载的 vendor.img 仅供实验对照，不可分发 | （理论推测）无现成组合，见 §5.4 |
| System 镜像 | GSI system.img（本地已有 DP11.261002.005） | `~/workspace/gsi/` | （源码确认）已有 |
| Metadata / userdata | metadata 16 MB ext4；userdata 2 GB 起（已格式化 ext4，不是空分区） | GsiAssembler 已产 GPT 盘；但**拼盘的 userdata 分区是空的**，需在拼装后格式化 | （源码确认）AGENTS.md 记“拼盘时 vda7 是空的，magic 0x0” |
| vbmeta | vbmeta.img（本地已有）；启动阶段先 `verifiedbootstate=orange` / `vbmeta.device=none` 绕过校验 | 本地已有 | （源码确认）实验一即用此方式，未测到 Verified Boot |

### 8.2 组装时必须修的三处（来自实验二的待办）

1. **fstab**：`fstab.ranchu` 带 `logical`，要求 super 分区；但 GsiAssembler 不做 dm-super。改写一份不带 `logical` 的 `fstab.virt`，按 GPT 分区名（metadata/system/vendor/userdata）或 `partition_map` 自映射写 `vdaN`。（源码确认）（AGENTS.md）
2. **`/data` 加密标志**：去掉 fstab 里 `/data` 的加密标志，否则首启挂载失败。（源码确认）（AGENTS.md）
3. **userdata 格式化**：拼盘后把 userdata 分区格式化为 ext4（空分区 magic 为 0x0 时 init 挂载失败）。（源码确认）（AGENTS.md）

### 8.3 不要假设的事

- GSI 本身不包含虚拟设备驱动和 HAL（GSI 设计目标就是运行在符合 Treble 规范的实体设备上，依赖设备提供的 Vendor 实现）。（文档确认）Android 官方 GSI 文档：GSI 只能用于 Bootloader 已解锁、完全 Treble 合规、Android 9+ 启动的设备；且“不包含设备制造商的定制”。
- GSI 不含内核、不含 ramdisk、不含 vendor、不含 fstab。（源码确认）本地 zip 内容核实。
- 不要把 Cuttlefish 的 Linux 宿主守护进程当成 QEMU 启动的必需品——那是 Cuttlefish 的设计，不是裸 QEMU 启动的要求。（文档确认）

---

## 九、QEMU 启动参数设计（草案，未验证）

以下参数以现有 `QemuCommandBuilder.build()` 的实际参数为底（§2.1），只加 Android 专用的部分；Windows/Linux 的参数一律不动。**全部是草案，M1 在主机 QEMU 上验证后才能定稿。**

### 9.1 共用底座（与 Windows/Linux 相同，直接复用）

```
-machine virt,highmem=on,gic-version=3
-cpu max                      # 先测 cortex-a76，再测 max
-smp 4
-m 4096
-accel tcg,thread=multi,tb-size=512
-rtc base=localtime
-monitor none
-device virtio-gpu-pci        # Android 强制 virtio-gpu（已有预设）
-device virtio-net-pci,netdev=net0
-netdev user,id=net0
-device virtio-rng-pci,rng=rng0
-object rng-random,filename=/dev/urandom,id=rng0
-device qemu-xhci,id=xhci
-device usb-kbd,bus=xhci.0
-device usb-tablet,bus=xhci.0
-qmp unix:<path>,server=on,wait=off
-serial unix:<path>,server=on,wait=off
```

### 9.2 启动路径二选一（M1 决策点）

**路径一：`-kernel` 直启（优先验证，与本地 PC 实验一致）**

```
-kernel <Image>
-initrd <initramfs.cpio>      # 必须是解包组装后的 cpio，绝不能直接传 vendor_boot.img（见下）
-append "console=ttyAMA0,115200 androidboot.console=ttyAMA0 androidboot.hardware=virt androidboot.selinux=permissive androidboot.verifiedbootstate=orange androidboot.vbmeta.device=none androidboot.hardware.gralloc=minigbm androidboot.hardware.hwcomposer=drm androidboot.hardware.vulkan=pastel"
-drive file=<gpt-disk.qcow2>,format=qcow2,if=none,id=disk0
-device virtio-blk-pci,drive=disk0
```

说明：`-append` 的 `androidboot.hardware.*` 是 Cuttlefish `guest_swiftshader` 模式的属性组合（`gralloc=minigbm` + `vulkan=pastel` + hwcomposer），不是 PocketVM 自创的。（文档确认）`androidboot.selinux=permissive` 只用于先行验证，收紧为 enforcing 是 M2 后的任务。UEFI pflash 在此路径下不挂载（`bootFromCd=false`，已有预设）。

**`vendor_boot.img` 不能直接当 `-initrd` 用（2026-11-15 修订，M1 必须解决）：** `vendor_boot.img` 是 Android Boot Image 容器，内含专用镜像头、vendor ramdisk（可能分多个 fragment）、可能的 DTB 与 bootconfig；QEMU 的 `-initrd` 要的是可直接作初始 RAM 文件系统的内容，即解包后的 cpio initramfs，而不是整个容器文件。正确流程：

```text
vendor_boot.img
      │
      ▼
解包 Android Boot Image（镜像头 + vendor ramdisk fragments + DTB + bootconfig）
      │
      ▼
提取 vendor ramdisk
      │
      ▼
检查 ramdisk fragment（是否完整、有无缺失段）
      │
      ▼
组装完整 initramfs.cpio（first-stage init + linker/libs + fstab + sepolicy + /dev 节点）
      │
      ▼
QEMU -initrd initramfs.cpio
```

组装后还须逐项核对 ramdisk 里的内容：`/init` 是否存在且可执行、fstab 是否覆盖 vda/vdb/vdc、SELinux policy 是否齐全、动态库依赖是否闭合（linker 追踪无缺失）、启动属性（`androidboot.*`、`ro.hardware=virt`）是否正确。以上全部是 **M1** 的任务，不要留到 M2 才发现启动参数不成立。本地现状：磁盘上没有 vendor_boot.img / init_boot.img 成品可供解包（见 §16）；现成可用的起点是 `~/workspace/bootable-ramdisk/ramdisk.cpio.gz`（已组装的 cpio，但其 init 早期 SIGABRT 未修复）与 GSI system.img 里的文件。（源码确认 + 理论推测）

**路径二：UEFI 启动（备选，需另行设计）**

Android 没有标准的 UEFI 启动规范；若走此路，需要内核 EFI stub 或一个能加载 `Image` + ramdisk 的 EFI 应用（如 GRUB2 ARM64 EFI 或自行编写的 `BOOTAA64.EFI`），再由 edk2 引导。工作量大于路径一，且与现有 `boot-virtual-device.img`（v4 header、无 ramdisk）的形态不匹配。**除非 M1 证明路径一在 App 内不可行（例如 App 的启动链路强制要求 UEFI），否则不走路径二。**（理论推测）

### 9.3 磁盘挂法二选一（M1 决策点）

- **方案一（优先）：单张 GPT 物理分区盘**（GsiAssembler 现有产物）：一张 virtio-blk，内含 metadata/system/vendor/userdata；cmdline 加 `androidboot.partition_map=vda1,vda1;…;vda7,vda7` 自映射（实验二已验证的最小解）。（源码确认）
- **方案二：分开挂**：system/vendor/userdata/metadata 各挂一张 virtio-blk（vda/vdb/vdc/vdd），fstab 按 `vdaN` 写；与 Wear OS 实测的磁盘顺序（vda=metadata、vdb=system、vdc=userdata）同构。（已验证）（Wear OS 实验）

---

## 十、需要修改的 PocketVM 模块

**红线：不直接修改现有 Windows / Linux 启动链路；Android 的所有改动走独立 Profile / 独立分支，Windows/Linux 的参数、磁盘格式、显示配置一律不动。**

| 模块 | 改动 | 备注 |
|---|---|---|
| `VmPlatform`（已有 `ANDROID_ARM64`） | 不动枚举值；补 Android 平台的文档注释 | （源码确认）枚举已存在 |
| `LaunchProfile` | 新增 `ANDROID` 档：`tcg,thread=multi,tb-size=512` + CPU `max`（先测 `cortex-a76`）+ 内存下限 4096 + virtio-gpu + net/rng 开 | 新档位，不动现有四档；降级阶梯里 Android 不走 Windows 的 ladder |
| `QemuCommandBuilder` | 新增 Android 启动路径（`-kernel`/`-initrd`/`-append` 或 UEFI 二选一后的胜出者）+ Android 专用参数；以 `platform == ANDROID_ARM64` 为分支条件 | 现有 `build()` 是参数驱动的，加分支不改 Windows/Linux 的参数生成 |
| `VmConfig` / Room | 新增字段：kernel 路径、ramdisk/vendor_boot 路径、gpuMode（`guest_swiftshader` / `drm_virgl` / `gfxstream`）、显示分辨率 | **必须写 `Migration`，绝不能清库**（AGENTS.md 数据层约定：`exportSchema=false`，无 `fallbackToDestructiveMigration`） |
| `CreateVmViewModel` | Android 预设已存在（强制 virtio-gpu、≥4GB、从磁盘启动）；扩展：kernel/ramdisk 导入、gpuMode 选择、vendor 镜像导入 | 在现有 Android 分支上扩展 |
| `GsiAssembler` | 扩展：拼装后格式化 userdata；生成 `fstab.virt`（去 `logical`、去 `/data` 加密标志）；校验 vendor.img 存在 | 现有拼装只做 GPT 盘，明确写了“启动链仍未打通” |
| 新增 `AndroidVendorPackager`（建议新模块） | 组装 initramfs.cpio（M1：first-stage init + linker/libs（取自 GSI system.img）+ fstab + sepolicy；若上游提供 vendor_boot.img，先解包取 vendor ramdisk 再组装，绝不直接传给 `-initrd`）与 vendor.img（M2：Vendor 基础框架，不含图形 HAL；minigbm 等图形组件是 M3）；校验缺失项并报错 | initramfs 组装是 M1，vendor.img 组装是 M2（不含图形 HAL）；不要复用 Windows 的 ISO 导入链路 |
| 新增 `AndroidBootDiagnostics`（建议新模块） | 串口日志按启动阶段打标记（内核启动 / first-stage / second-stage / zygote / surfaceflinger / boot_completed），供诊断页与调试接口展示 | 复用现有串口控制台与 `LogTailReader`，不另起日志通道 |
| `VmSession` | Android 启动路径接入（direct-kernel 或 UEFI 二选一后的胜出者）；启动时序不变（probe → validate → 置备 → manifest → fork → QMP 握手） | 不动 Windows/Linux 的 `start()` 顺序契约 |
| `VmSessionHost` / 多 VM | 不动；Android VM 同样按 vmId 管理，内存预算同样适用 | （源码确认）多机同跑已实现 |
| 调试接口（`dev/pocketvm/debug/`） | 可选：`/diagnostics` 增加 Android 启动阶段字段（复用 `AndroidBootDiagnostics`） | 不动鉴权与红线（只绑 127.0.0.1、默认关、token） |
| 构建脚本 | 阶段 1 不动 QEMU（virtio-gpu 2D 已在 11.1.2 里）；阶段 3 若上 rutabaga，需重建 QEMU（`--enable-rutabaga-gfx`）+ 重打 PAC 补丁 + 更新 Gradle 门禁哈希 + 改 versionName | 重建是远期事项，M1–M4 不触发 |
| 文档 | `docs/roadmap.md` Android 一节、`docs/troubleshooting.md` 增加 Android 专章、AGENTS.md 增加 Android Guest 一节 | 与代码同批更新，不滞后 |

**明确不做的事：** 不移植 Ranchu fork；不自行实现 Android HAL（gralloc/HWC/EGL 一律用 AOSP 现成组件）；不把 KVM 作为前置条件；不改 Windows/Linux 启动链路；不把 SDK 下载的系统镜像内置进 APK 分发。

---

## 十一、分阶段实施计划（M1–M6，2026-11-15 修订重拆）

**总原则：先在主机 QEMU 上验证启动链与图形链路，再接入 App；每阶段都有明确的验收标准，达不到就停，不要把未完成的启动链写进产品。旧的 M1–M5 已废弃，改为下表六阶段——M2 原来同时承担 ramdisk、Vendor 镜像、图形 HAL、SELinux、VINTF、userdata、boot_completed，失败时无法定位是哪一层的问题，现按启动链路逐层拆分。**

### 最小实验（当前最高优先级，不等 M1 全部完成，先做这一步）

**目标：使用现有 Android 17 GKI 6.18 + 正确组装的 first-stage initramfs + QEMU virt，在 PC QEMU 上进入 second stage。**

暂不要求桌面，不要求 GPU 加速，不要求 Launcher。只验证：

```text
QEMU 启动
   ↓
Android Kernel
   ↓
VirtIO 设备识别
   ↓
first-stage init
   ↓
fstab 挂载
   ↓
SELinux setup
   ↓
second-stage init
```

起点与已知障碍（见 §16）：内核 Image 与 GSI 已有；组装好的 `ramdisk.cpio.gz` 已有，但其 init 在启动早期 SIGABRT 未修复——最小实验的第一步就是复现这个 abort，**定位精确到函数、调用栈、寄存器、触发条件**：依据 `DIAGNOSIS.md`、`lddebug-trace.txt` 与崩溃日志，重点检查寄存器、调用栈、运行时环境、系统调用返回值，区分 init 二进制自身问题、运行时环境问题、内核接口问题、ramdisk 组装问题；**fstab 与 SELinux 不是这个阶段的首要排查对象**（它们主要影响后续 first-stage mount 与 SELinux setup），不要把排查范围扩散到它们上面。通过标准：串口日志出现 second-stage init 的 init.rc 执行记录。**最小实验通过之前，不启动 M2 的 Vendor 与图形工作。**

### M1：Kernel、DTB、ramdisk、fstab（主机 QEMU 实验，不碰 App 代码）

任务：

1. 核对本地 6.18 内核 `.config`（§5.1 清单）：binder/binderfs/ashmem→memfd/DRM virtio-gpu/virtio 全家已确认开启；**补开 `CONFIG_DMABUF_HEAPS_SYSTEM` 并重编**（2026-11-15 修订时核对发现未开）；其余未逐项核对的配置继续复核。
2. 组装 first-stage initramfs：**vendor_boot.img 若存在，先按 §9.2 流程解包取 vendor ramdisk、检查 fragment，再组装完整 initramfs.cpio；绝不直接把 vendor_boot.img 传给 `-initrd`**。组装后逐项核对 ramdisk 里的 `/init`、fstab、SELinux policy、动态库依赖、启动属性。本地现状：无 vendor_boot.img 成品可供解包；起点是 `~/workspace/bootable-ramdisk/ramdisk.cpio.gz`，其 init 早期 SIGABRT 未修复——先复现并攻关该 abort（最小实验）。
3. 修 fstab：DT `/firmware/android` 节点或文件 fstab 二选一；修 §8.2 的三处（fstab 去 `logical`、去 `/data` 加密标志、userdata 格式化为 ext4）。
4. 决策启动路径（`-kernel` 直启 vs UEFI）与磁盘挂法（单张 GPT 盘 vs 分开挂），二选一，不并行。
5. 验证 GICv3 + `cortex-a76`/`max` 下 GKI 内核是否正常（Wear OS 的 GICv2 教训只针对 5.4 内核，需在 6.18 上复测）。
6. 对比 Ranchu 与标准 virt 的设备模型、内核驱动、Vendor HAL 对应关系（本方案 §4–§5 即为该对比的结论），形成书面决策：走路线 B。

验收标准：

- 主机 QEMU 上内核启动、virtio 设备枚举、init 进入 second stage，串口日志有明确的阶段标记。
- 形成《AndroidGuestProfile 参数草案》（§7、§9 的定稿版），含 CPU/GIC/内存/磁盘/显示的取值与依据。
- 明确记录哪些内核配置是补开的（附 `.config` diff）。

### M2：Vendor 基础框架、Binder、SELinux（主机 QEMU）

**职责边界（与 M3 明确拆分）：M2 只做 Vendor 基础框架，不引入任何图形组件。** minigbm、drm_hwcomposer、EGL、GLES、Vulkan 一律是 M3 的任务，M2 不提前引入、也不提前验证，避免 M2 失败时无法判断是基础框架问题还是图形组件问题。

任务：

1. 按面向 virt 的 Device/Product 配置自建 vendor.img（见 §5.4）：Vendor 基础框架 + 音频/传感器/电源 stub + VINTF manifest + init 脚本 + sepolicy；`sdk_phone64_arm64` 与 Cuttlefish 磁盘只能作参考与对照，不能直接当最终产品。SDK 下载的 vendor.img 只作实验对照，不进入分发。**本阶段不含 gralloc（minigbm）与任何图形 HAL。**
2. SELinux：本地 `vendor-sepolicy/` 为起点，先 permissive 验证启动放行，逐步收紧；收紧失败则保持 permissive 并如实标注。
3. Binder 域验证：`/dev/binder`、`hwbinder`、`vndbinder` 三域与 binderfs 挂载正常。

验收标准：

- zygote 和 system_server 正常运行（logcat 无反复重启，`service list` 可见核心服务）。
- SELinux permissive 下启动放行；enforcing 收紧进度有书面记录。
- vendor.img 内不含图形 HAL 组件（minigbm / drm_hwcomposer / SwiftShader 均未引入），M2 的通过与否只反映基础框架状态。

### M3：完整图形栈（主机 QEMU）

**职责边界：M3 做完整图形栈，包括 minigbm allocator/mapper、drm_hwcomposer、EGL、GLES、Vulkan。** M2 已交付的 Vendor 基础框架、VINTF、init、sepolicy、Binder、音频/传感器/电源 stub 在此阶段视为已具备，不重复做。

任务：

1. 图形 HAL 五项独立验证（见 §5.2，逐项过，不过不拼）：Vulkan ICD（SwiftShader `vulkan.pastel.so`）、EGL、GLES、Gralloc（minigbm allocator + mapper HAL）、HWC（drm_hwcomposer 对 virtio-gpu DRM/KMS 的兼容性）；另核对 HAL 用 AIDL 还是 HIDL 及对应 VINTF manifest；确认 SurfaceFlinger 实际采用 GLES 还是 Vulkan 合成路径。
2. 图形阶段 1：virtio-gpu 2D（KMS 送显）+ minigbm + drm_hwcomposer + SwiftShader，720p 先行，记录帧率与 CPU 占用；再试 1080p。
3. **根因确认门**：在补 HAL 之前，先按 §3.3 取 SurfaceFlinger tombstone、logcat、HAL 服务状态，确认 abort 的具体故障点；根因未确认不启动大规模 HAL 编写。

验收标准（**性能目标单独定义**，与“能否启动”分开考核）：

- SurfaceFlinger 稳定运行（串口日志无 SIGABRT 循环），Launcher 静态画面稳定 10 分钟以上不崩溃，滑动不触发 SurfaceFlinger 重启。
- 性能数据书面记录（实测值，不是承诺值）：720p 与 1080p 下的帧率、QEMU 进程 CPU 占用、内存占用。**SwiftShader + TCG 的定位仍是“验证图形链路通”的调试模式，不承诺流畅可用；帧率目标是“记录实测值”，不是“达到某一帧率”。**
- 明确结论：阶段 1 是否达到“验证图形链路通”的目标；阶段 2 是否值得启动（需宿主 virglrenderer 可行性实验先行）。
- 五项验证记录齐全，缺一项即不算完成。
- **以下三点写进验收结论，不得混为一谈：`boot_completed` 成功不代表系统可交互；Launcher 显示不代表触控、动画、输入链路可用；720p 出图不代表 1080p 可用。**

### M4：完整 Android 启动（主机 QEMU）

任务：

1. 依次复验 init、zygote、system_server、SurfaceFlinger 全链路，确认 M2–M3 的成果在完整启动中保持稳定。
2. 验证到 `sys.boot_completed=1`，Launcher 可见，`adb` 可用。

验收标准：

- `sys.boot_completed=1`（`adb shell getprop sys.boot_completed` 或串口日志）。
- Launcher 可见（VNC 截图或 `screencap`），`adb` 可用。
- SurfaceFlinger 不再循环（串口日志无 SIGABRT 循环）。
- **实际交互测试（M4 验收必做）：连续滑动、打开设置、启动一个普通 App，记录是否卡死、是否触发服务崩溃、是否出现 ANR。只出静态画面不算通过交互测试；`boot_completed` 成功不代表系统可交互。**

### M5：接入 PocketVM（App 侧）

任务：

1. 将验证成功的启动链路迁移到 Android App 内的 QEMU 11.1.2：AndroidGuestProfile + QemuCommandBuilder Android 路径 + 导入流程（kernel/ramdisk/vendor/system）+ Room 迁移 + 串口启动阶段诊断。
2. 不改 Windows/Linux 启动链路（§10 红线）；CI 通过；Windows/Linux 回归通过（现有真机用例）。

验收标准：

- 真机（小米 14 Pro）上从 App 启动 Android 到 boot_completed，Launcher 可见。
- Android 17 App 内存上限验证通过（4 GB 客户机 + QEMU 开销下 App 不被杀；6 GB 另行记录）。
- cgroup 冻结缓解（电池“无限制”）同样适用，有记录。
- Windows/Linux 回归：现有真机启动/安装流程不受影响。

### M6：性能和外设

任务：输入（触屏映射）、显示（1080p）、网络（复验已通的 SLIRP）、音频（先 stub，后评估 virtio-snd——注意当前 QEMU 构建未编入 virtio-snd，需重建执行层）、剪贴板、GPU 加速评估（virgl/gfxstream 宿主后端可行性实验）。

验收标准：逐项有真机证据（日志/截图/录屏），未达到的项明确写“未达到”，不写成通过。

---

## 十二、技术风险与不可行性分析

| 风险 | 影响 | 缓解 / 决策点 |
|---|---|---|
| 宿主 GPU 不可达（无 `/dev/dri`、无 libdrm、无线上 Wayland） | 阶段 2/3 的 virgl/gfxstream 短期不可用；3D 加速长期无解 | 阶段 1 只做软件渲染；阶段 2/3 的启动条件是“NDK 下编出 aemu + gfxstream host + rutabaga 并接到宿主 GLES/Vulkan 后端”的实证，在此之前不排期 |
| SwiftShader 在 TCG 下只有静态画面可用 | 用户期望落差：能开机、不能流畅用 | M3 用数据定性：明确写清“调试模式”，不承诺可用模式；若静态画面都出不来，暂停 App 接入 |
| Android 17 App 内存上限 | 4–6 GB 客户机可能触发上限，App 被杀（与 cgroup 冻结是两回事） | M5 真机验证；若 4 GB 即被杀，降内存到 3 GB 重测并记录（但低于 4 GB 的 Android 体验会进一步下降，需如实记录） |
| cgroup 冻结（MIUI/HyperOS） | VM 突然停摆（已定位、已有缓解） | 沿用电池“无限制”缓解；App 已有 H4 采样间隙检测 |
| VINTF / Treble 兼容性 | 自建 vendor 必须提供兼容的 manifest，否则 GSI 拒绝启动 | M2 任务：vendor manifest 按 GSI 的 VINTF 要求编写；若兼容性工作量过大，改用自建 AOSP system.img 替代 GSI |
| SELinux enforcing | permissive 先行可启动，但产品化需要 enforcing + 完整 sepolicy | 本地 `vendor-sepolicy/` 为起点；M2 后逐步收紧，收紧失败则保持 permissive 并如实标注 |
| Cuttlefish 的 Linux 宿主守护进程 | 若选 Cuttlefish 磁盘，必须剥离 keymint/gatekeeper/config_server/vsock 服务依赖 | M2 决策：剥离成本计入评估；剥离失败则改走自建 AOSP |
| SDK 镜像许可 | 不能随 PocketVM 分发 | 只作实验对照；分发物一律自建 AOSP 或用户自带 |
| GICv2/v3 与 CPU 型号 | 旧内核（5.4）只支持 GICv2；新 CPU（max）下的 GKI 行为待验证 | M1 实测 GICv3 + cortex-a76/max，不假设 |
| 16KB 页与新 .so | 阶段 3 若重建 QEMU，新编的 rutabaga/gfxstream .so 必须 16KB 对齐并重打 PAC 补丁、更新门禁哈希 | 远期事项，M1–M5 不触发；触发时按现有 QEMU 重建流程走 |
| 工作量低估 | M1–M2 是平台级工程（Wear OS 先例：多天），不是 App 侧小改 | 按 M1–M6 分段验收；任一阶段验收失败即停，不要硬推到 App 里 |

**不可行性分析（什么情况下应判定路线 B 不可行）：**

- 若 M4 在主机 QEMU 上用 `guest_swiftshader` 配置仍无法达到 boot_completed（例如 SurfaceFlinger 仍循环、或 system_server 持续崩溃），且已按 §3.3 完成 tombstone/logcat 确认、排除 fstab/sepolicy/内存三类原因，则应判定“virt + 软件 HAL”路线在当前 QEMU/内核组合下不可行，暂停 App 接入，先解决图形链路，不要把未完成的启动链写进产品。
- 若 M5 真机验证中 Android 17 App 内存上限导致 4 GB 客户机即被杀，且降内存后 Android 无法正常启动，则应判定“手机 App 内跑 Android Guest”在当前宿主约束下不可行，改以主机 QEMU 实验为主，App 接入延期。
- 路线 A 不作为上述情况的备选——它的工作量更大、宿主 GPU 依赖更重，不能解决路线 B 失败的根本原因（Vendor HAL 与宿主 GPU 后端）。

---

## 十三、10 个问题逐个回答

**Q1：为什么当前 GSI 能启动到 SurfaceFlinger，却无法完成启动？**

**首要怀疑是 Vendor 图形 HAL 缺失，但尚未定论为根因。** GSI 无 vendor 分区 → 无 gralloc/HWC/EGL；SurfaceFlinger 启动时加载三者，任一失败即可能 abort，init 反复拉起成循环；system_server 等图形服务起不来，`sys.boot_completed` 永远等不到。佐证有两组，各有限制：Wear OS 实验证实 ranchu HAL 依赖 goldfish_pipe/sync、镜像无 drm_hwcomposer/minigbm/软件合成器时 SurfaceFlinger 与 zygote 崩溃循环（但那是 Wear OS 3 镜像与 5.4 内核）；实验二的 surfaceflinger SIGABRT 循环、串口无 tombstone、virtio-gpu 只把首次崩溃从 50 s 推迟到 148 s（但其 first-stage init 二进制未经独立验证，且 GSI 自带 init 本身在启动早期即可 SIGABRT，见 §2.2 补充）。两组证据不能互相证明。定论前必须取 SurfaceFlinger tombstone、logcat、HAL 服务状态确认具体故障（见 §3.3）；根因确认前不启动大规模 HAL 编写。另注意：实验二能到 zygote 是因为走了 system-as-root 形态（内核直接把 system.img 当 rootfs），绕过了 fstab 问题；卡点后移到了 vendor。（理论推测——首要怀疑；Wear OS 机制部分已验证）

**Q2：当前 QEMU virt 与 Android Guest 的硬件需求具体有哪些差距？**

六项：(1) 无 first-stage ramdisk/vendor_boot（本地 initramfs 只有模块，boot.img 只有内核）；(2) 无 DT `/firmware/android` fstab 节点，需文件 fstab 回退或自定义 DTB；(3) 无 vendor HAL（gralloc/HWC/EGL/音频/传感器/电源）；(4) 无 goldfish_pipe/sync（ranchu HAL 依赖它们，但路线 B 选 minigbm/drm_hwcomposer 可绕开）；(5) vendor sepolicy 未验证启动放行；(6) 无 vsock（Cuttlefish 用，但裸 QEMU 启动不需要）。内核 virtio 驱动与串口已验证工作，不是差距。（源码确认 + 文档确认）

**Q3：是否应该使用 Android Emulator 的 Ranchu，而不是继续使用普通 virt？**

不应该整体移植 Ranchu。Ranchu 是 Google 的下游 QEMU fork（ranchu 机型 + goldfish 全套 MMIO 设备 + pipe 服务 + glue + EmuGL/gfxstream），完整移植工作量为“大”，且宿主 GPU 栈在 Android 手机上没有现成构建路径。应继续用普通 virt，并复用 Ranchu 生态中已上游化的部分（GKI + goldfish 内核模块、minigbm/drm_hwcomposer 的设计思路）。（文档确认）

**Q4：能否直接使用 AOSP Android Emulator ARM64 的系统镜像、内核和 Vendor 组件？**

不能直接用。SDK 镜像可下载（`sdkmanager "system-images;android-<API>;google_apis;arm64-v8a"`，API 34 rev 14 等已在 live XML 中确认），内容据 AOSP 打包脚本含 system/vendor/ramdisk/kernel-ranchu，但：(a) 它们绑定 ranchu 机型与 goldfish 设备，上游 virt 上跑不到完整 Android（第三方在上游 QEMU 11.1.1 上实测：无 ranchu 机型 → 无显示、无 GPU pipe）；(b) SDK 许可禁止随 PocketVM 分发。只能作实验对照；分发物应按面向 virt 的 Device/Product 配置自建（见 §5.4）；`sdk_phone64_arm64` 只是 Emulator 产品配置，自己编译也不会自动变成 virt 产品。（文档确认；zip 内容细节未核实，见 §14）

**Q5：是否存在适合 QEMU ARM64 virt 的现成 Android Kernel + Vendor 镜像组合？**

没有。Cuttlefish（crosvm + Linux 宿主守护进程 + KVM 导向）、Emulator kernel-ranchu（绑定 fork）、Waydroid/Redroid（Linux Guest 内容器，非原生 Guest）各缺一块。最接近的起点是本地已编好的 android17-6.18 内核（virtio 全内置）+ 按面向 virt 的 Device/Product 配置自建 vendor（`sdk_phone64_arm64` 与 Cuttlefish 磁盘只能作参考与对照，不能直接当最终产品）。（文档确认）

**Q6：如何先实现纯软件渲染，再逐步实现 GPU 加速？**

阶段 1：virtio-gpu 2D（KMS 送显）+ minigbm + drm_hwcomposer + SwiftShader（Cuttlefish `guest_swiftshader` 模式），目标 boot_completed + Launcher 静态画面，定位为调试模式。**但图形 HAL 必须拆成五项独立验证（见 §5.2），不要拼起来就当完成**：Vulkan ICD（SwiftShader 的 `vulkan.pastel.so`，只是 Vulkan 驱动）、EGL、GLES、Gralloc（minigbm allocator + mapper HAL）、HWC（drm_hwcomposer）；`vulkan.pastel.so` 的存在不能证明 EGL/GLES 路径已解决；另须核对 HAL 用 AIDL 还是 HIDL、对应 VINTF manifest、SurfaceFlinger 实际采用 GLES 还是 Vulkan 合成路径。阶段 2：Mesa virgl/Venus（`drm_virgl` 模式），需宿主 virglrenderer + 宿主 GL，短期不可用。阶段 3：rutabaga + gfxstream（`x-gfxstream-gles`/`x-gfxstream-composer` capset），需宿主 aemu + gfxstream + rutabaga + 真实宿主 GLES/Vulkan，长期。两个加速阶段的启动条件都是宿主 GPU 后端的实证，在此之前不排期。（文档确认 + 理论推测；EGL/GLES 等五项未核实，见 §14）

**Q7：QEMU 11.1.2 是否需要移植 Android Emulator 的专用设备模型？**

不需要（走路线 B 的前提下）。上游 11.1.2 已有 virtio-blk/net/gpu/input/console/rng，Guest 内核前提只有 `CONFIG_DRM_VIRTIO_GPU`；rutabaga/gfxstream 在协议上与 TCG 不冲突，QEMU 8.2+ 即有此设备。需要补的是 Guest 侧 Vendor HAL 与宿主侧 GPU 后端，不是 QEMU 设备模型。只有选路线 A 才需要移植 goldfish_pipe 等设备。（文档确认）

**Q8：哪些能力必须由 Guest Kernel 提供，哪些必须由 Vendor HAL 提供？**

Kernel 必须提供：binder/binderfs、ashmem→memfd、DMA-BUF heaps、virtio 全家（blk/net/gpu/input/console/rng/balloon）、DRM virtio-gpu、dm-verity、PSI/lmkd、DT fstab 节点或文件 fstab 支持（完整清单见 §5.1）。Vendor HAL 必须提供：gralloc（minigbm）、HWC（drm_hwcomposer）、EGL/Vulkan（SwiftShader/virgl/gfxstream）——**以上图形栈是 M3**；音频/传感器/电源（可先 stub）、init.rc + fstab + sepolicy + `ro.hardware` 等属性——**以上 Vendor 基础框架是 M2**。System 侧（init second stage、zygote、system_server）由 GSI 提供。（文档确认）

**Q9：能否建立独立的 AndroidGuestProfile，不影响 Windows 和 Linux？**

能。`VmPlatform.ANDROID_ARM64` 已存在；`CreateVmViewModel` 已有 Android 预设（强制 virtio-gpu、≥4GB、从磁盘启动、隐藏 ISO/驱动盘/磁盘控制器）；`QemuCommandBuilder` 是参数驱动的，新加 Android 分支即可；`VmSessionHost` 已支持多 VM。新增字段（kernel/ramdisk 路径、gpuMode、分辨率）走 Room Migration，不清库。（源码确认）

**Q10：如何在不 Root 的 Android 宿主上实现完整启动？**

(1) 不需要 KVM：同架构 ARM64 下 TCG 已被 Windows 真机验证可行；(2) 不需要 Root：QEMU 作为 App 子进程运行（已验证），文件在应用私有目录，unix socket 在 `filesDir/internal/run/`（FUSE 建不了 socket 的问题已有解）；(3) 真正的卡点全在 Guest 侧：first-stage ramdisk、fstab、Vendor HAL、sepolicy，按 M1–M3 解决；(4) 宿主侧约束要实测：Android 17 App 内存上限（M5 验证）、cgroup 冻结（电池“无限制”缓解已有）、无宿主 GPU（阶段 1 只做软件渲染）。（已验证 + 理论推测）

---

## 十四、未核实清单（建议稿里写了但本次没核实到的 + 本次调研未覆盖的）

1. 建议稿称“Linux ARM64 已在真机成功进入系统”——**未证实**（只到 GRUB + 出图，见 §1）。
2. SDK 系统镜像 zip 的实际内容（是否含 vendor.img/kernel-ranchu）——未下载解压核实，只核对了官方仓库索引 XML 与 AOSP 打包脚本。
3. 上游 QEMU 11.1.2 的 `hw/` 树是否确实全缺 ranchu/goldfish 设备——未逐行核对源码，只依据 fork 文档与第三方实测。
4. 本地 6.18 内核 `.config`——2026-11-15 修订时已部分核对：`CONFIG_ANDROID_BINDER_IPC=y`、`CONFIG_ANDROID_BINDERFS=y`、`CONFIG_ASHMEM=y`、`CONFIG_PSI=y`、`CONFIG_VIRTIO_CONSOLE=y`、`CONFIG_DRM_VIRTIO_GPU=y`（含 KMS）已确认开启；但 `CONFIG_DMABUF_HEAPS_SYSTEM` 明确未开，M1 必须补开重编；其余配置仍未逐项核对，M1 继续复核。
5. 本地 `vendor-sepolicy/` 的启动放行——未验证。
6. 实验二（跑到 zygote）的 first-stage init 二进制——WorkBuddy 声称交付（3,283,848 B），未下载、SHA 未独立核实；2026-11-15 修订时全盘核查确认该二进制在磁盘上不存在。磁盘上现有的 init 是 GSI 自带的 `/system/bin/init`（2,653,128 B，BuildID `972f69b5…`），另有一份用它组装的 `ramdisk.cpio.gz`（见 §2.2 补充），但其 init 在启动早期 SIGABRT 未修复。
7. Ranchu fork 的代码量（行数）与 Android NDK 构建可行性——未评估；若要量化“大”，需先做 fork vs 上游 11.1.2 的 `hw/`、`android/` diff 统计。
8. SwiftShader 在 ARM64 TCG 下的实测帧率——检索无此数据，未编造；M3 必须实测。
9. 建议稿的硬件表（cortex-a76 起步、4 GB 起步、6 GB 测试、1080p/720p）是建议测试配置，不是已验证组合；其中 cortex-a76 与 GICv3 在 GKI 6.18 下的行为待 M1 实测。
10. 音频方案（virtio-snd 是否可用、当前 QEMU 构建是否编入）——本次未核查，M6 任务。
11. VINTF manifest 的具体编写工作量——未评估，M2 任务。
12. 建议稿引用的四篇参考资料均已实际阅读（GSI 文档、ANDROID-KERNEL.TXT、DEVELOPMENT.md、QEMU virtio-gpu 文档）；其中 ANDROID-KERNEL.TXT 与 DEVELOPMENT.md 的内容已过时（QEMU 2.7.0 / 内核 3.18 时代），引用时已按现代 Ranchu（GKI + goldfish 模块）做了区分。
13. 图形 HAL 五项独立验证——Android 17 对应的 SwiftShader 构建产物、EGL/GLES 实现及其依赖、minigbm 的 allocator 与 mapper HAL、drm_hwcomposer 对 virtio-gpu DRM/KMS 的兼容性、SurfaceFlinger 实际采用的 GLES/Vulkan 合成路径、HAL 用 AIDL 还是 HIDL、对应 VINTF manifest——全部未核对，M3 逐项验证。
14. vendor_boot.img 解包细节——磁盘上没有 vendor_boot.img / init_boot.img 成品可供解包，§9.2 的解包流程是按 Android Boot Image 规范写的，未在本地实物上走通过；最小实验的起点是已有的 `ramdisk.cpio.gz`，不是解包 vendor_boot。
15. 面向 virt 的 Device/Product 配置——`sdk_phone64_arm64` 与 Cuttlefish 磁盘能否作为该配置的参考底稿、改动量有多大，未评估；产品配置、设备树、fstab、VINTF manifest、init 脚本、sepolicy 围绕 virt 设计的工作量未评估，M2 任务。

---

## 十五、参考资料

- Android 官方 GSI 文档：https://developer.android.com/topic/generic-system-image
- Android Emulator Kernel / Ranchu 文档：https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android/docs/ANDROID-KERNEL.TXT
- Android Emulator 架构说明：https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android/docs/DEVELOPMENT.md
- Android Emulator Goldfish 虚拟硬件规范：https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android/docs/GOLDFISH-VIRTUAL-HARDWARE.TXT
- Android Emulator GPU 模拟文档：https://android.googlesource.com/platform/external/qemu/+/emu-master-dev/android/docs/GPU-EMULATION.TXT
- QEMU VirtIO-GPU 文档：https://www.qemu.org/docs/master/system/devices/virtio/virtio-gpu.html
- AOSP first-stage init 说明：https://android.googlesource.com/platform/system/core/+/refs/tags/aml_ads_331814200/init/README.md
- AOSP 图形架构：https://source.android.com/docs/core/graphics
- Android 13 通用启动分区：https://source.android.com/docs/core/architecture/partitions/generic-boot
- AOSP GKI 配置片段：https://android.googlesource.com/kernel/configs/
- Cuttlefish QEMU 管理器（gpu_mode 参考实现）：https://android.googlesource.com/device/google/cuttlefish/+/a77657c1c/host/libs/vm_manager/qemu_manager.cpp
- rutabaga_gfx（crosvm book）：https://crosvm.dev/book/appendix/rutabaga_gfx.html
- Linaro：用 rutabaga_gfx 构建 QEMU：https://linaro.atlassian.net/wiki/spaces/ORKO/pages/28985622530/Building+QEMU+with+virtio-gpu+and+rutabaga_gfx
- 上游 QEMU 跑 Emulator 镜像的第三方实测：https://github.com/ccccccccvvm/qemu-android-arm64/blob/HEAD/README.md
- Android 系统镜像仓库索引（live XML）：https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml
- 仓库内依据：`~/workspace/PocketVM`（AGENTS.md、README.md、`QemuCommandBuilder.kt`、`VmPlatform.kt`、`CreateVmViewModel.kt`、`GsiAssembler.kt`、`GptDisk.kt`、`SparseImage.kt`）；`~/workspace/qemu-boot/BOOT.md`；`~/workspace/pocketvm-qemu-audit-2026-11-15-main-9f3d8ff.md`（新版 QEMU 核查报告，尤其 §5、§6.3）；`~/workspace/pocketvm-qemu-audit-2026-11-15.md`（旧版）

---

## 十六、最小实验就绪情况（2026-11-15 核查，只核查、不下载、不启动 QEMU）

最小实验目标：现有 Android 17 GKI 6.18 + 正确组装的 first-stage initramfs + QEMU virt，在 PC QEMU 上进入 second stage（见 §11）。

### 有什么

**`~/workspace/qemu-boot/`（2.1G）：**

| 文件 | 大小 | 用途 / 状态 |
|---|---|---|
| `Image-qemu-virt` | 44,329,472 B | 已编好的 GKI 内核（Linux 6.18.21-android17-5，ARM64，4K 页）；与 `out/dist-qemu-virt/Image` 同一文件（SHA-256 `578080b9…`） |
| `boot-virtual-device.img` | 44,081,152 B | Android bootimg，只有内核、无 ramdisk；不能直接作 `-initrd` 输入 |
| `initramfs.img` | 10,832,567 B | LZ4 压缩 cpio，303 条，**全部在 `lib/modules/` 下，只有内核模块，无 `/init`、无 fstab**；不能作 first-stage ramdisk |
| `system.img` | 2,114,777,088 B | GSI system.img（ext4），与 zip 内同一文件 |
| `metadata.img` | 16,777,216 B | metadata 分区镜像（ext4，16 MiB） |
| `userdata.qcow2` | 196,672 B（虚 4 GiB） | userdata 磁盘（qcow2 v3） |
| `vbmeta.img` | 4,096 B | vbmeta |
| `build.prop` | 5,718 B | GSI build.prop |
| `BOOT.md` / `QEMU-BOOT-CHECKLIST.md` / `FLASH-CHECKLIST-REFERENCE.md` / `serial.log` 等 | — | 实验一的记录与日志 |

**`~/workspace/gsi/`：** 只有原始 zip `aosp_arm64-exp-DP11.261002.005-16594701-a359a50b.zip`（1,186,717,983 B，Android 17 QPR3 Beta ARM64 无 GMS，SHA-256 `a359a50b…` 已验证）；zip 内只有 system.img（2,114,777,088 B）、vbmeta.img、build.prop，无内核、无 ramdisk、无 vendor、无 fstab。（源码确认）

**内核源码 `~/workspace/kernel-android17-6.18`（44G，android17-6.18）：** 顶层没有 `arch/arm64/boot/`（Kleaf/Bazel 布局，产物在 `out/`）；`out/dist-qemu-virt/` 有已编好的 `Image`（44,329,472 B）、`Image.gz` / `.lz4` / `.lzma` / `.zst`、`.config`（230,540 B）、`System.map`、`vmlinux`、`README-QEMU-BUILD.txt`；`out/dist-virtual-device/` 另有 `initramfs.img`（与 qemu-boot 的同一文件，SHA-256 `8ae5e8e2…`，只有内核模块）、`boot.img`（Android bootimg，只有内核）等产物；`out/dist-plain-gki/` 另有 Image 与模块。**三个 dist 目录里都没有可作 first-stage 用的 ramdisk 成品（唯一的 initramfs.img 只有内核模块）。**（源码确认）

**已组装的 first-stage ramdisk（`~/workspace/bootable-ramdisk/`，2026-10-29）：** `ramdisk.cpio.gz`（9,229,914 B，SHA-256 `a50640cf…`，204 条）：顶层 `/init` → GSI 自带的 `/system/bin/init`（2,653,128 B，BuildID `972f69b5…`），另有 bootstrap linker 与 29 个依赖库、ueventd、toybox、toolbox、e2fsck、`/dev` 设备节点、顶层 fstab + fstab.qemu、GSI 自带的 platform sepolicy；另有 `boot.img`（53,567,488 B，由 `assemble-boot.sh` 打包，**从未启动过**）。冒烟测试已证实内核能跑 `/init`、动态链接能工作，但 init 在打印任何自身日志之前 SIGABRT，崩溃点已定位（动态链接器完成后、`_start` 之后、init 自身早期执行期间），**未修复**（详见 `RAMDISK.md`、`~/workspace/init-debug/DIAGNOSIS.md`）。（已验证）

**内核 `.config`（`out/dist-qemu-virt/.config`，2026-11-15 修订时核对）：** `CONFIG_ANDROID_BINDER_IPC=y`、`CONFIG_ANDROID_BINDERFS=y`、`CONFIG_ASHMEM=y`、`CONFIG_PSI=y`、`CONFIG_VIRTIO_CONSOLE=y`、`CONFIG_DRM_VIRTIO_GPU=y`（含 KMS）均已开。（源码确认）

### 缺什么

1. **唯一关键缺项：一个能跑到 second stage 的 first-stage init。** WorkBuddy 声称的 first-stage init 二进制（3,283,848 B）磁盘上不存在，从未下载、SHA 未独立核实（用户 2026-10-31 已决定“先不动”，本次未尝试下载、未联系 WorkBuddy）；磁盘上也没有静态 first-stage init 二进制，没有 vendor_boot.img / init_boot.img 成品（内核树里只有测试数据与构建脚本）。现有的 `ramdisk.cpio.gz` 是用 GSI 自带 init 组装的，而该 init 在启动早期即 SIGABRT 未修复。**没有这个，实验启动不了 first-stage，需要先解决来源或修好现有 init 的 abort，二者必居其一。**（源码确认）
2. `CONFIG_DMABUF_HEAPS_SYSTEM` 未开（`.config` 明确 `# CONFIG_DMABUF_HEAPS_SYSTEM is not set`），M1 必须补开重编。（源码确认）
3. vendor sepolicy trio 缺失（GSI 不含，本地 `vendor-sepolicy/` 启动验收未证实）；fstab 仍是推测写法（`fstab.qemu` 自写，未在成功启动中验证）。（源码确认）
4. userdata 分区是空的（拼盘时未格式化），fstab 的 `/data` 挂载需先格式化（见 §8.2）。（源码确认）

### 结论

最小实验今天就能开始第一步——复现 `ramdisk.cpio.gz` 的 init SIGABRT 并定位可修复的原因（已有 `DIAGNOSIS.md` 的崩溃点定位与 `lddebug-trace.txt`）；定位要求精确到**函数、调用栈、寄存器、触发条件**，重点检查寄存器、调用栈、运行时环境、系统调用返回值；**fstab 与 SELinux 不是这个阶段的首要排查对象**（它们主要影响后续 first-stage mount 与 SELinux setup），不要把排查范围扩散到它们上面。但**在 first-stage init 问题解决之前，实验到不了 second stage**。这一步不需要下载任何东西，不需要 WorkBuddy，只需要对着已有的 ramdisk 与日志干活。
