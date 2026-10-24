# PocketVM

在未 Root 的小米 14 Pro（Snapdragon 8 Gen 3 / arm64）上，用 **QEMU TCG** 跑 Windows ARM64。
0.1.x 只做一件事：**把启动链做成可重复验证的**，而不是先堆界面。

当前代码对应计划中的 **P0（执行环境）** 与 **P1 的前半段**：

| 能力 | 状态 |
| --- | --- |
| QEMU 9.2.3 aarch64 静态二进制（TCG + VNC + slirp + pixman） | **已编入 APK**（32 MB，零未解析符号） |
| QEMU 可执行文件探测（真跑 `--version`，不只看文件存在） | 已实现 |
| 配置校验 + 可操作的中文错误 | 已实现 |
| QCOW2 磁盘创建（无 `qemu-img` 时用内置写入器，始终精简置备） | 已实现 |
| UEFI pflash code/vars 管理（校验等长） | 已实现 |
| QEMU 参数构建（virt + NVMe/USB + VNC + QMP） | 已实现，含单元测试 |
| 进程监督、优雅关机（ACPI→quit→kill）、exit code | 已实现 |
| 每次启动的 manifest（argv/固件/磁盘/退出码） | 已实现 |
| 日志尾部读取 + 失败归因 + 诊断包 zip | 已实现 |
| 性能占用仪表（CPU / 每 vCPU / 内存 / I/O，1 Hz 宿主机侧采样） | 已实现，含单测 |
| VNC 服务（端口 / 仅本机 / 局域网 / 密码） | 已实现，App 不内置渲染器 |
| GPU / KVM 加速 | 不在范围 |

## 版本演进（当前 0.10.8 / QEMU 11.1.2 / edk2 2026.08）

| 版本 | 关键变化 |
| --- | --- |
| 0.6.x | 执行层改为**动态链接 PIE**（静态 bionic 在本机 self-boot 即 SIGSEGV）；QMP 套接字移到内部 ext4 |
| 0.7–0.8.x | 失败原因取 QEMU 原始错误行；VNC 端口自动避让；pflash 固件自愈；`efi-virtio.rom` 放到 `-L` 根目录 |
| 0.9.0–0.9.1 | 提取 El Torito EFI 引导镜像；修复 **SIGILL（exit=132）**：引导镜像由 `usb-storage` 改挂 `virtio-blk` |
| 0.9.2 | **UEFI Shell 串口自动恢复**：监听固件串口，检测到 `Shell>` 自动输入 `fs0..fs3:\EFI\BOOT\BOOTAA64.EFI` |
| 0.10.0 | **升级 QEMU 11.1.2**（Debian `+ds` 源码 + NDK r27c）；适配新版 meson（pixman/`librt`/dtc/pc-bios），RUNPATH 归一 `$ORIGIN` |
| 0.10.1 | 修正 memfd 垫片：`shm_open` 名字含 `/` 会被 `memfd_create` 拒绝，去掉前导斜杠 |
| 0.10.2 | 合并远程改动（恢复被覆盖的 QEMU 11 适配 + 保留 glib 镜像/libffi 容错）；新增 `-PpvmSideBySide` 并行安装；纠正版本号回退 |
| 0.10.4 | **固件升级到 edk2 2026.08**（原为 2024.02，老了两年半）：内置 3 份可选固件（2026.08 普通版 / 2026.08 Secure Boot 版 / 旧 2024.02），默认选最新普通版；同时把**所有引导相关项做成可选**（光驱总线、输入设备、串口自动恢复、网卡、RNG、是否提取引导镜像、CPU 模型） |
| 0.10.3 | **光驱改走 USB**：edk2 能从 virtio-scsi 光驱引导，但 WinPE 没有 `vioscsi`、读不到 `install.wim`；AHCI 正相反（WinPE 能读，edk2 无驱动引导不了）——**USB 是两边都支持的唯一总线**。新增 `CdTransport`（AUTO/USB/VIRTIO_SCSI），AUTO 先用 USB，若 QEMU 被信号杀死则自动降级 virtio-scsi 重试 |
| 0.10.5 | **UI 重构 + 最终版固件**：界面从两块平铺卡片升级为带底部导航（虚拟机 / 环境 / 关于）的分层界面——列表页渐变 Hero + 聚合统计 + 可展开环境就绪条 + 重做虚拟机卡片（状态色条/规格 chips/磁盘进度条）；控制台页概览/日志双 Tab；新增环境页与关于页。固件定版为 **edk2 2026.08**（原版 2024.02 兜底并列打包，运行时按文件名排序自动优先 2026.08）；修复提取标记升 v6 避免升级短路、移除误删 `QEMU_EFI-*` 的清理逻辑 |
| 0.10.6 | **Windows 安装配方（全网调研落地）**：显示设备可配 ramfb / virtio-gpu——ramfb 是社区三份独立教程一致的安装期显示（virtio-gpu 的驱动交接会清空 scanout，安装途中出现「Display output is not active.」占位画面）；挂 VirtIO 驱动盘时安装 ISO 只走 virtio-scsi（**Windows 无法同时配置两个 ISO 设备，同挂必冻结**，richliu 实测）；占位画面排查补 Ctrl+Alt+F1 / `console=tty0`（社区确认这是引导器→登录界面间的正常过渡）；关于页/控制台补完整 Win11 ARM64 安装配方（22589 可用 25226 必坏、`vioscsi → w11 → ARM64`、TESTSIGNING、NetKVM/viogpudo） |
| 0.10.7 | **性能占用仪表**：控制台新增「性能」Tab，1 Hz 采样 QEMU 进程的 CPU（多线程 TCG 下可超 100%）、每 vCPU 占用、RSS 内存（占手机物理内存比例）、磁盘 I/O（MB/s）与运行时长，附 60 秒 CPU 迷你图。全部宿主机侧采集（`/proc/<pid>/{stat,statm,io}` + QMP `query-cpus-fast`），客户机无需装 agent，Windows 安装过程中也能看 |
| 0.10.8 | **修复子进程 PID 获取（真机上仪表/健康探针静默失效的根因）**：`Process.pid()` 的反射 invoke 在 JDK 16+ 撞模块封装、在 ART 上是隐藏 API，两条反射路都断时 `pidOf` 返回 -1，健康探针与性能仪表会**静默不启动**。新增 `/proc` 扫描兜底（`diagnostics/ChildProcess.kt`：ppid==本进程 且 cmdline 含 qemu 的直接子进程），任何 Linux/ART 上都成立；补 9 个测试：5 个假 /proc 夹具 + 4 个**真内核**集成测试（解析测试 JVM 自身 /proc、采样真实忙等进程、双子进程中按 qemu 名扫描、已回收 PID 返回 null） |

真机（Redmi K70 Pro / SM8650 / Android 16）已达到：QEMU 进程稳定运行、edk2 固件画面出现、VNC 可连。
失败时优先看三处：`run-<id>.log`（QEMU 原始错误行）、`run-<id>.serial.log`（固件串口输出）、诊断包里的 `qemu-probe.txt`。

## 为什么 QEMU 必须叫 `libqemu-system-aarch64.so`

Android 10 起 W^X 生效：`filesDir` 里的文件即使 `chmod +x` 也会 `EACCES`。
唯一被允许的可执行路径是 APK 的 `lib/` 目录（`ApplicationInfo.nativeLibraryDir`，只读、可执行）。
所以执行层的打包方式是：

```
app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so   # 其实就是 qemu-system-aarch64
app/src/main/jniLibs/arm64-v8a/libqemu-img.so              # 可选，qemu-img
```

`app/build.gradle.kts` 里必须保留 `jniLibs.useLegacyPackaging = true`，否则 so 不会解压到磁盘，无法 exec。

`QemuEnvironment` 会按 `libqemu-system*` 前缀扫描该目录，逐个执行 `--version`，把成功解析出版本号的那个作为执行层，并记录失败原因。

## 虚拟磁盘：256 GB 只占实际大小

`DiskProvisioner` 只产出 qcow2，**没有 raw 回退**。原因是 `/Android/data` 位于 FUSE
（sdcardfs），稀疏空洞不保证被保留，一个 256 GB 的 raw 文件可能真的吃掉 256 GB 闪存。
qcow2 的稀疏性是格式自带的：文件长度 = 元数据 + 已分配簇，与虚拟容量无关。

优先用 `qemu-img create`；没打包 `qemu-img` 时由 `Qcow2Creator` 直接写 QCOW2 v3 头
（header + refcount table + refcount block + 一张空的 L1 表，共 256 KiB）。
QEMU 在 guest 首次写盘时才分配 L2 表和数据簇，refcount 表不够用时自己扩容。

已用真实 `qemu-img` 交叉验证（单元测试 `Qcow2CreatorTest`）：

```
$ qemu-img info --output=json our-256g.qcow2
"virtual-size": 274877906944, "cluster-size": 65536, "format": "qcow2", "dirty-flag": false
$ qemu-img check our-256g.qcow2
No errors were found on the image.
物理占用：262144 字节（256 KiB）
```

界面里可以直接看到「虚拟盘已用 X MB / 256 GB」，这个数字就是 `disk.qcow2` 的真实长度。

## VNC 服务

这个 App 不显示画面。QEMU 自带的 VNC 服务就是产品对外的「显示接口」，你用任何 RFB 客户端连它。

| 模式 | 绑定 | 用途 |
| --- | --- | --- |
| 仅本机 | `127.0.0.1` | `adb forward tcp:5900 tcp:5900` 后用桌面客户端连 `127.0.0.1:5900`；设备上其他 App 也可访问 |
| 局域网 | `0.0.0.0` | PC 直接连 `手机IP:5900`，控制台里会显示当前 Wi-Fi/USB 共享的 IPv4 |

密码不是可选项而是安全前提：局域网模式的 VNC 认证是 RFB 的 DES 方案，密钥只有 8 个字符，
只能当「防误连」用，不能当安全边界。启动流程是
`-vnc 0.0.0.0:N,password=on` → QMP 握手 → `set_password protocol=vnc`，密码在 UI 宣布
「运行中」之前就已经装好，不存在无密码的窗口期。

端口即 display：`5900` 对应 QEMU 的 `:0`，`5901` 对应 `:1`。改端口时 builder 会自动换算。

## 虚拟机网络与端口转发

客户机网络是 QEMU 用户态 NAT（libslirp，纯用户态、无需 root/TUN）：虚拟机自动获得
`10.0.2.15`，出网走手机的网络。设备模型是 `virtio-net-pci`，依赖 `-L` 数据目录里的
`efi-virtio.rom`；ROM 缺失时启动流程会自动禁用网卡而不是让 VM 起不来。

**端口转发（hostfwd）** 把客户机里的服务发布到手机上，让电脑直接连虚拟机内部：

| 场景 | 配置 | 电脑上的连法 |
| --- | --- | --- |
| Windows 远程桌面 | 手机 `5389` → 客户机 `3389`（预设按钮一键添加） | `adb forward tcp:5389 tcp:5389` 后连 `127.0.0.1:5389` |
| Linux SSH | 手机 `2022` → 客户机 `22` | 同上，`ssh -p 2022 127.0.0.1` |
| 局域网直连 | 访问方式选「局域网」时转发改绑 `0.0.0.0` | 连 `手机IP:手机端口`，无需 adb |

敞口策略与 VNC 一致：「仅本机」下转发只听 `127.0.0.1`，「局域网」下才听 `0.0.0.0`。
手机侧端口从 1024 起——Android 应用以自己的 UID 运行，没有 `CAP_NET_BIND_SERVICE`，
绑定特权端口会直接失败（校验器会报 `FORWARD_HOST_PORT_PRIVILEGED`）。

注意：Windows 安装程序不带 virtio 网卡驱动（`netkvm`），联网前需先从 VirtIO 驱动盘加载；
Linux 内核自带，装上即可上网。

## 性能占用仪表

控制台页的「性能」Tab 回答一个问题：**这台虚拟机正在吃掉手机多少资源**。
采样全部在宿主机侧完成，客户机里不需要装任何 agent，所以 Windows
安装过程中同样看得到：

| 指标 | 来源 | 说明 |
| --- | --- | --- |
| 进程 CPU | `/proc/<pid>/stat` utime+stime 增量 | 以**单核 = 100%** 计。多线程 TCG 让多个 vCPU 线程并行翻译，总值超过 100% 是常态；附最近 60 秒迷你图 |
| 每 vCPU 占用 | QMP `query-cpus-fast` 拿线程号 → `/proc/<tid>/stat` | 线程号一次运行内不变，只查一次；可看出负载是否摊在所有 vCPU 上 |
| 内存 | `/proc/<pid>/statm` resident | RSS 含 TCG 翻译缓存，通常高于客户机内存设定；换算成占手机物理内存的比例 |
| 磁盘 I/O | `/proc/<pid>/io` read/write_bytes 增量 | qcow2 文件实际落到块层的速率，即用户感知的读写 |
| 运行时长 | 启动时间戳 | — |

每秒采样一次，随 VM 进程启动而开始、退出即清空。采样器是纯增量数学
（`diagnostics/VmMetrics.kt`），解析器与计算都有单测覆盖——长整型 tick
增量必须转成浮点再除，否则低于单核满载时全会显示 0%。

仪表能出数的前提是拿到 QEMU 子进程的 PID。`Process.pid()` 不能直接信：
反射 invoke 在桌面 JDK 16+ 撞模块封装（`IllegalAccessException`），
在 ART 上 `UNIXProcess` 整个类是隐藏 API——两条反射路都断时，
健康探针与仪表都会静默不启动。因此 `pidOf` 有三级降级：
反射方法 → 反射字段 → `/proc` 扫描（`diagnostics/ChildProcess.kt`：
ppid 等于本进程、cmdline 含 qemu 的直接子进程）。扫描这条路有假 /proc
夹具测试，也有真内核集成测试（见 `VmMetricsRealProcTest`）。

## 装 Windows 11 ARM64（社区验证路径）

这条链路综合了 dariaphoebe、richliu、sekin 三份独立教程在 `virt` 平台上装 Win11
ARM64 的一致结论，PocketVM 的默认配置与回退逻辑就是按它搭的：

1. **磁盘用 NVMe**（安装程序自带 `stornvme`），**光驱默认「自动」**：先走 USB
   （edk2 与 WinPE 都支持的唯一总线），被 SIGILL 杀死后自动整体回退
   virtio-scsi + virtio 输入，并记住这个选择。
2. **显示用 ramfb**（编辑页 → 引导相关 → 显示设备）。virtio-gpu 的驱动交接会让
   QEMU 清空显示输出（`ui/console.c dpy_gfx_replace_surface`），安装途中出现
   「Display output is not active.」占位画面；ramfb 没有这个交接，是社区安装
   Windows ARM64 的一致选择。   代价是固定 800x600——固件界面按 **F10** 进 Setup
   把首选分辨率改成 1024x768，设置写入 vars.fd，重启保留。ramfb 的重绘不完整会留下
   残影，这是已知问题，忽略即可（装好驱动后再切回 virtio-gpu）。
3. **镜像版本**：**22589 可用，25226 已知必坏**（启动即未处理异常）。
4. **驱动盘**：回退到 virtio-scsi 后，WinPE 读不到 virtio-scsi 上的
   `install.wim`，挂一张 virtio-win ISO 作驱动盘（AHCI 总线，WinPE 的 `storahci`
   能读它），在 WinPE「加载驱动」里进 `驱动盘盘符 → vioscsi → w11 → ARM64`
   （认准含 `vioscsi.inf` 的目录）。**挂驱动盘时安装 ISO 只走 virtio-scsi**：
   Windows 无法同时配置两个 ISO 设备，同挂必冻结（richliu 实测）——若加载驱动
   失败，卸掉驱动盘重启，安装 ISO 会恢复 AHCI 副本，无需任何 virtio 驱动即可读盘。
5. **安装假死**：`Getting ready` 超过 10 分钟不动、或看门狗 BSOD，直接重启，
   TCG 下这是常态；提示 `Windows installation cannot proceed` 时
   Shift+F10 → `regedit` → `HKLM\SYSTEM\Setup\Status\ChildCompletion\Setup`
   改成 3 → 重启。
6. **签名**：首次设置（国家/键盘）界面 Shift+F10 开命令提示符，执行
   `bcdedit /set TESTSIGNING ON`——virtio 驱动虽然带签名，不开这项照样装不上。
7. **进桌面后**：从驱动盘把 NetKVM / viostor / vioscsi / viogpudo 的 `.inf`
   （`w11\ARM64` 目录）右键安装；遇签名提示先把 `cert` 目录里的证书装进
   「受信任的发布者」。之后可把显示切回 virtio-gpu（1280x800）。
8. **TPM 2.0**：QEMU 不模拟 TPM，原版 Win11 会停在「这台电脑无法运行
   Windows 11」，Shift+F10 + 注册表 `LabConfig` 绕过（或换 Win10 ARM64）。
9. **容量与内存**：Win11 要求 64 GB 起，建议 128 GB；内存低于 4096 MB 时
   安装程序展开镜像容易失败。

## 构建

```bash
# 需要 JDK 17+、Android SDK 35
cd android
./gradlew :app:assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # 参数构建与校验的单元测试
```

完整的打包 → 真机 → 推送链路（含 GitHub 凭据怎么现取）见 [docs/packaging.md](docs/packaging.md)。

首次运行不需要手动放任何文件：64 MiB 固件随 APK 发布，64 MiB 空白变量存储由 `FirmwareManager` 生成
（edk2 会在首次启动时格式化它）。压缩后这份 64 MiB 镜像在 APK 里只占约 1.2 MB，因为大部分是零。
如果你想换固件（例如 64 MB 的 secboot 版本），在界面里「导入固件」覆盖即可。

```
Android/data/dev.pocketvm/files/
├── firmware/QEMU_EFI.fd     # 从界面“导入固件”，或手动放入
└── images/*.iso             # 界面导入时自动复制到这里
```

## 执行层（QEMU）

APK 里带的是**动态链接（PIE）**的 `libqemu-system-aarch64.so`（约 30 MB）加一组依赖库：

| 文件 | 作用 |
| --- | --- |
| `libqemu-system-aarch64.so` | QEMU 9.2.3 本体（ET_DYN，`PT_INTERP=/system/bin/linker64`） |
| `libqemu-img.so` | `qemu-img`，用于创建/检查 qcow2 |
| `libglib-2.0.so` `libpixman-1.so` `libslirp.so` `libpcre2-8.so` `libintl.so` | 依赖，`RUNPATH=$ORIGIN` |
| `libpvm-probe.so` | 400 KB 静态探针：诊断本机能否执行静态 bionic 二进制 |

> **为什么不是静态链接**：`-static` 产出的二进制在本机（Android 14）实测 `exit=139`，
> 即使改成 `ET_EXEC`（无重定位、16 KB 对齐）依旧 SIGSEGV——问题在 bionic 静态运行时
> 自举，而不是 PIE 标志位。动态链接由 Android 自己的 linker64 加载，是唯一被大规模
> 验证的形态（Termux 的 QEMU 同样如此）。环境卡片里的「静态执行自检」会直接告出
> 这台设备到底能不能跑静态二进制。

版本与构建事实：

| 项 | 值 |
| --- | --- |
| QEMU | 9.2.3 |
| 加速器 | TCG（`thread=multi`），无 KVM |
| 显示 | `-vnc`（RFB 3.8），无 GTK/SDL |
| 网络 | libslirp user-mode |
| 链接 | 动态 PIE（glib 2.80.5 / pixman 0.44.2 / libslirp 4.8.0 / 内置 libfdt / pcre2 / libffi） |
| 段对齐 | 0x4000（兼容 16 KB page size 设备） |
| 运行时数据 | `assets/qemu/keymaps/*`，首次启动解压到 `files/qemu/share`，作为 `-L` |
| UEFI 固件 | `assets/firmware/QEMU_EFI.fd.gz`（Ubuntu `qemu-efi-aarch64`，edk2 2024.02，64 MiB pflash），打包时由 AGP 自动解压，首次启动释放到 `files/firmware/`，变量存储按同尺寸生成 |

固件与光驱：ARM virt 平台的 edk2 构建**不含 AHCI 驱动** —— 这一点 Ubuntu 版和 QEMU 官方版
（edk2-stable202408）完全一样，两者都看不到挂在 `ich9-ahci` 上的光驱（`map` 查询无 FS、
无 BLK）。固件能用的光驱只有 virtio-scsi（和 USB）；而 Windows 只能认 AHCI（自带 storahci，
没有 vioscsi）。USB 是两边都支持的总线，但它在本机必崩，所以回退到 virtio-scsi 后要么把
同一个 ISO 挂两次（scsi-cd 引导 + AHCI 副本供 WinPE 读 `install.wim`），要么挂一张
virtio-win 驱动盘让 WinPE 加载 `vioscsi`——后者只挂一个安装 ISO，详见「装 Windows 11
ARM64（社区验证路径）」。

> 一条曾经的误判：`Image type X64 can't be loaded on AARCH64 UEFI system` 看起来像是"固件读到了
> 光驱里的 x86 引导程序"，其实它来自 virtio-net 的 x86 option ROM（`efi-virtio.rom`）——
> 加 `-nic none` 后该报错立即消失。别再拿它当光驱可见性的证据。

固件自愈：解压标记（`.extracted-v4`）只在目录里存在 ≥32 MB 的 `.fd` 时才被采信；旧版残留的
2 MiB `-bios` 镜像会先被删除再重新释放。**标记版本一变就无条件重新释放随包固件**，否则
磁盘上那份旧的会永远压过新包，用户还得手动清数据。变量存储与固件尺寸不一致时自动重建。

重建（需要 Linux + NDK r27c + patchelf，约 5–10 分钟 / 24 核）：

```bash
NDK=~/android-ndk-r27c scripts/build-qemu-android-dynamic.sh all
scripts/install-qemu.sh .build-qemu/out-jni     # 拷贝全部 lib*.so
cd android && ./gradlew :app:assembleDebug
```

脚本里记录了几个必须踩过的坑（bionic 没有 `shm_open`、`memfd_create` 是 API 30 符号、
`--without-default-features` 会关掉 pixman、glib 的 gvdb 要单独补、jniLibs 只收 `lib*.so`
所以必须用 patchelf 把 SONAME/DT_NEEDED 一起改掉），改参数前先读注释。

### 启动降级（兼容模式）

部分芯片/系统上，标准参数会在机器初始化阶段直接退出（`tb-size` 分配失败、`-cpu max`
暴露的特性不受支持、rng 设备初始化失败等）。启动器因此内置了降级阶梯，每档独立
日志与 manifest：

| 档位 | 加速器 | CPU | 可选设备 |
| --- | --- | --- | --- |
| 标准 | `tcg,thread=multi,tb-size=512` | `max` | virtio-rng + 网络 |
| 兼容 | `tcg,thread=single,tb-size=128` | `cortex-a76` | 网络 |
| 最小 | `tcg,thread=single,tb-size=64` | `cortex-a76` | 无 |

- 默认「自动」：标准 → 兼容 → 最小，某档在 QMP 握手后再稳定 2.5 秒即采用
- **磁盘控制器、内存、vCPU 数不参与降级**：换控制器会让已装好的系统无法启动，
  改内存则可能引入新的失败
- QMP 握手发生在机器初始化之前，所以有 2.5 秒稳定性窗口，避免把"握手成功但随即
  崩溃"误判为启动成功
- 每次尝试写各自的 `run-<id>.log` / `run-<id>.json`，诊断包里能看到完整阶梯
- 界面可手动锁定档位（编辑 → 启动模式），用于排查或避开已知有问题的机型

`cortex-a76` 而不是 `a72`：Windows ARM64 需要 ARMv8.1 的 LSE 原子指令，a72 没有，
一个跑不起来客户机的"救援档"没有意义。

## 目录

```
android/                    Gradle 根（AGP 8.7 / Kotlin 2.1 / Compose）
  app/src/main/java/dev/pocketvm/
    core/model/             VmConfig / RunManifest / 运行状态
    core/config/            配置存储（原子写）、校验器（纯 Kotlin，可单测）
    storage/                目录布局、固件、磁盘置备、ISO 导入
    runtime/                QEMU 探测、参数构建、QMP、进程监督、前台服务
    diagnostics/            日志尾部、manifest、诊断包
    ui/                     Compose 界面（列表 / 向导 / 控制台）
  app/src/test/             纯 JVM 单元测试
scripts/                    原生层构建与安装、无线开发环、GitHub 增量同步
docs/                       架构、排障、打包与推送
```

## 设计约束（不从计划里删的部分）

1. **管理层与执行层分离**：Kotlin 只负责配置、状态、监督；QEMU 是独立进程，崩溃不影响 UI。
2. **先 TCG 后 KVM**：不把硬件虚拟化当阻塞条件。TCG 的代价是慢（Windows 启动约 3–8 分钟），这一点不掩饰。
3. **固件按平台配置**：不硬编码 `QEMU_EFI.fd` 的文件大小；vars 由 code 大小推导，二者不等长直接报错。
4. **存储分层**：ISO（只读介质）、QCOW2（可写磁盘）、vars（持久固件状态）互不复用。
5. **每次启动可追溯**：manifest 在 fork 之前写盘，崩溃也能看到当时的完整 argv。

## 验证状态

| 项 | 结论 |
| --- | --- |
| 编译（aarch64 动态 PIE） | 通过，82 个单元测试全绿 |
| APK 结构 | `lib/arm64-v8a/libqemu-system-aarch64.so` + keymaps，`extractNativeLibs=true` |
| 真机启动 QEMU | **通过**（小米 14 Pro / Android 16）：UEFI 起来、virtio-gpu 出图 1280x800、VNC 服务正常、USB 键盘/数位板输入可用 |
| 安装介质启动 | **通过**：固件从 USB 光驱引导 → Windows 安装程序答完「按任意键」→ `Loading files...` 走完 → WinPE 接管。USB 崩溃回退 virtio-scsi 后：无驱动盘时同一 ISO 双挂（scsi-cd 引导 + AHCI 副本供 WinPE 读 `install.wim`）；挂驱动盘时只走 virtio-cd（两个 ISO 设备同屏会冻结安装） |
| Windows 安装全流程 | **进行中**：USB 整体回退 + 驱动盘 + ramfb 显示 + 单 ISO 设备策略已按社区验证配方就位；上次真机停在 bootmgr 阶段（virtio-scsi 读不到 `install.wim`），待按上方配方挂驱动盘复测。历史上另有一次 WinPE 早期内核 Data Abort（ESR `0x25/0x96000047`，FAR `0x0`，指令 `0x…637200: b .`），PC 上同配置逐位复现，与加速档位/内存/固件无关——那是 USB 光驱时代的配置，当前 virtio-scsi + 驱动盘链路尚未复测该问题 |

第一次上机时最值得盯的三件事：`QemuEnvironment` 是否报 `QEMU_MISSING`（找不到 .so）、
日志里有没有 `Could not read keymap`、以及 VNC 端口是否真的在监听。

## 已知限制

- VNC 只监听 `127.0.0.1`；查看画面需 `adb forward tcp:5900 tcp:5900`。
  **局域网模式（`0.0.0.0`）不可用**：这个 QEMU 构建的加密后端不支持 DES，而 VNC
  的密码认证就是 DES——设了密码 QEMU 会拒绝启动（exit=1）。要恢复局域网，得在重建
  执行层时编入支持 DES 的加密后端（nettle/gnutls）。
- ISO 会被完整复制一份（`content://` 无法跨进程传给 QEMU），请预留 5–6 GB。
- 一台设备同一时间只跑一个 VM（`VmSession` 是单例）。
- minSdk 30：执行层以 API 30 为目标编译（`memfd_create` 的下限）。
- 未做快照/回滚；改配置前请自行备份 `disk.qcow2`。
