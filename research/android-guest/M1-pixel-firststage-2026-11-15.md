# M1 Pixel first-stage init 移植验证 — 实验日志

日期：2026-11-15。任务：用 Pixel 工厂镜像 `init_boot.img` 里的静态 first-stage init，在 PC QEMU virt 上建立原生启动链 `first-stage init → mount → second-stage init`。
目标不是证明 Android 能不能启动（M1 已用 `init second_stage` 到达 apexd），而是验证 Pixel first-stage init 能不能在 QEMU virt 上跑 first-stage mount，并建立可复用的原生链。

约束：不改 PocketVM 仓库；不改 Pixel 工厂镜像 zip；不把 Pixel 二进制嵌进 PocketVM 或任何分发物；不直接移植 Pixel 内核/DTB/Vendor 镜像；不下载；不动 `~/workspace/gsi/` 原始 zip。每次实验留日志；一次只改一个变量。

## P0 — 材料清点（已验证）

### Pixel first-stage init
- 来源：`~/workspace/pixel-images/analysis/ramdisk-init_boot/init`
- ELF 64-bit ARM aarch64，**静态链接**，stripped，3,005,824 B，BuildID `118fa24a…`，SHA-256 前 16 位 `622c3bacd6f0c2c5`
- 字符串证据：含 `init first stage started!`、`FirstStageMount`、`FirstStageDeviceInitializer`、`/first_stage_ramdisk/fstab.`、`/first_stage_ramdisk/system/etc/fstab.`、`Could not bind mount /first_stage_ramdisk to itself`（switch_root.cpp）、`/system/bin/init`（exec 目标）；**不含** GSI 那句 `Second-stage init requires an argument` FATAL，也**不含** `init second stage started!`
- **不含** `/sepolicy`、`/vendor/etc/selinux/precompiled_sepolicy` 字面路径（policy 加载路径待实验确认）；含 `/debug_ramdisk/userdebug_plat_sepolicy.cil`、`/userdebug_plat_sepolicy.cil`、`/sys/fs/selinux`、`/system/etc/selinux/plat_file_contexts` 等 contexts 路径、`enforcing`、`security.selinux`
- 含 `/dev/__properties__`、`/dev/__properties__/property_info`（property area 在 first-stage 里也会初始化）
- 含 `fstab_suffix`、`ro.boot.slot_suffix`、`/proc/bootconfig`、`androidboot.boot_devices`（FirstStageDeviceInitializer 用 boot_devices/part_uuid 找启动设备；QEMU 侧不提供，待看冷插拔是否够用）

### vendor_boot fragment 0 的 first_stage_ramdisk（依赖清单）
- `system/bin/`：linker64、linker_asan64、linker_hwasan64、modprobe、resize2fs、tune2fs
- `system/lib64/`：ld-android.so、libbase.so、libc++.so、libc.so、libdl.so、libext2_*、liblog.so、libm.so、libsparse.so、libz.so
- `system/etc/`：fstab.gem5、fstab.malibu、fstab.malibu-fips
- `lib/firmware/`：usb_phy_fw.bin、usb_phy_fw_2.27.0.bin（Pixel 专属，virt 不需要）
- `avb/`：q/r/s-developer-gsi.avbpubkey
- vendor ramdisk 根：`/sepolicy`（1,245,248 B，policy v30，Pixel 预编译）；plat_file_contexts 等 contexts 在根；**无** `plat_sepolicy_vers.txt`（版本检查是 second-stage selinux_setup 的事，见 M1 E4）

### fstab 研究
- fstab.gem5（模拟器通用 by-name 写法）：system/system_ext/system_dlkm/vendor/vendor_dlkm/product 均为 erofs `wait,first_stage_mount`；/data f2fs `wait,first_stage_mount`。无 /metadata 行。
- fstab.malibu（真机 UFS）：system erofs（另有 ext4 行，注释写明“GSI 是 ext4 时用这行”），设备路径 `/dev/block/platform/3c2d0000.ufs/by-name/*`；/metadata f2fs/ext4；/data f2fs/ext4 latemount。
- **关键**：两个 fstab 都把 system 挂在 `/system`（不是 `/`），靠 first-stage init 的 switch_root 完成 SAR。M1 的 fstab.qemu 同样是 system → `/system` + switch_root 思路（M1 日志已注明，未被真实 first-stage init 验证过）。
- GSI system.img 根布局（debugfs 已验证）：根下 `bin`→`/system/bin`、`etc`→`/system/etc`、`init`→`/system/bin/init` 等绝对软链，**同时**有真实目录 `system/`（内含 apex、app、bin、build.prop、etc、fonts…）。即：挂在 `/system` 后 `/system/bin` 是自指软链（ELOOP），switch_root 到 `/system` 后 `/system` 是真实目录，`/system/bin` 解析正常。这是 SAR + switch_root 能成立的前提。

### M1 现成材料（复用）
- 最终 ramdisk：`~/workspace/bootable-ramdisk/work/m1-e3/m1-final.cpio.gz`（SHA `d95658e6…48bb668d`），已解到 `/tmp/m1final`（P1 基底）
  - `/init` 是 642 B sh wrapper（挂 proc/sys/dev tmpfs、建 /dev/__properties__ 与 /dev/socket、mknod /dev/block/vdd、ext4 vdd 挂到 /dev/__properties__、exec `init second_stage`）
  - `/sepolicy` = GSI 自编 sepolicy-main（522,021 B，v33）
  - `/vendor/etc/selinux/`：plat_sepolicy_vers.txt（37.0）、precompiled_sepolicy、hash、vendor_property_contexts
  - `/first_stage_ramdisk/fstab.qemu` 与 `/fstab.qemu`：system `/dev/block/vda` ext4 ro、`wait,first_stage_mount`；/data vdb ext4、`wait,check,formattable,first_stage_mount,quota`；/metadata vdc ext4、`wait,check,formattable,first_stage_mount`。无 by-name、无 slotselect/avb/logical。
  - `/system/bin/init` = GSI second-stage init（2,653,128 B）；`/system/bin/ueventd`（软链→init）；linker64 + 29 库；`/system/etc/selinux/`（GSI CIL + contexts + mapping）
- 内核：`~/workspace/bootable-ramdisk/Image-qemu-virt`（GKI 6.18.21，44,329,472 B）。**已知缺 `CONFIG_TMPFS_XATTR`**——tmpfs 上 `fsetxattr(security.selinux)` 恒失败；M1 用 128 MiB ext4 vdd 绕过（每轮重建）。Pixel first-stage init 若在 first-stage 做 PropertyInit，同一堵墙会再次出现（待实验确认顺序与是否 FATAL）。
- 磁盘（M1 四盘布局）：vda=`~/workspace/qemu-boot/system.img`（GSI ext4，2,114,777,088 B，ro）；vdb=`~/workspace/bootable-ramdisk/userdata.img`（4 GiB ext4）；vdc=`~/workspace/bootable-ramdisk/metadata.img`（16 MiB ext4）；vdd=`~/workspace/qemu-boot/prop_area.img`（128 MiB ext4，**每轮重建**）。
- QEMU：本机 8.2.2。cmdline（与 M1 相同）：`console=ttyAMA0,115200 androidboot.console=ttyAMA0 androidboot.hardware=qemu androidboot.selinux=permissive androidboot.verifiedbootstate=orange androidboot.vbmeta.device=none printk.devkmsg=on`。`androidboot.hardware=qemu` → fstab 文件名 `fstab.qemu`。

### P1 设计（单变量：只换 /init）
- 基底 = M1 最终 ramdisk 树，**只把 `/init` 换成 Pixel 静态 init**（M1 wrapper 另存为 `init.wrapper.m1` 备查）。其余全部不变：`/sepolicy` 仍是 GSI v33、`/vendor/etc/selinux/*` 不变、fstab.qemu 不变、cmdline 不变、四盘不变。
- 预期观察点（按顺序）：是否打印 `init first stage started!` → 是否读到 `/first_stage_ramdisk/fstab.qemu` → FirstStageDeviceInitializer 是否建出 /dev/block/vda → mount 结果（system/data/metadata 各自成败）→ SELinux/policy 行为 → PropertyInit 行为 → 是否 switch_root → 是否 exec `/system/bin/init second_stage` → second-stage 走到哪。
- 已知风险：PropertyInit 可能因缺 TMPFS_XATTR 而 FATAL（顺序未知，若在 mount 之后仍能看到 mount 结果；若在 mount 之前则 P1 只能证明“init 能跑、读 fstab 前就停”）；/dev/socket 可能无人创建（M1 靠 wrapper mkdir；second-stage GSI init 不自建——若 second-stage 在 StartPropertyService 失败，记为 P1 的精确停点）。

## P1 — 只换 /init（2026-11-15 19:2x，失败：SIGILL，未到 first-stage 逻辑）

ramdisk：`work/m1-pixel/m1-p1.cpio.gz`（10,607,581 B）。基底 = M1 最终 ramdisk 树，仅 `/init` 换成 Pixel 静态 init（SHA `622c3bac…`；M1 wrapper 另存 `init.wrapper.m1`）；vdd 每轮重建。QEMU 命令与 M1 完全相同（`-cpu cortex-a57`，其余不变），串口日志 `work/m1-pixel/p1-serial.log`（316 行）。

结果：**init 未输出任何内容**。内核 `Run /init as init process` 后 56 ms 即 panic：

```text
[    2.627293][    T1] Run /init as init process
[    2.683301][    T1] Kernel panic - not syncing: Attempted to kill init! exitcode=0x00000004
...
[    2.692061][    T1] el0_undef+0x80/0x84
```

exitcode 0x04 = SIGILL（非法指令），`el0_undef`（EL0 未定义指令异常）。即 Pixel first-stage init 在 cortex-a57 上**连第一行日志都没打印就被指令集杀死**——不是逻辑失败，是 CPU 型号不匹配。

反汇编验证（`aarch64-linux-gnu-objdump -d`，已存 `/tmp/pixel-init.dis`）：
- 入口 0x28d0c0 起即有 `paciasp`（ARMv8.3，HINT 编码，老 CPU 上是 NOP，不致命）。
- 二进制内含 **300+ 条 LSE 原子指令**（ARMv8.1，cortex-a57 是 ARMv8.0、无 LSE）：ldaddal 93、casa 54、ldadd 52、swpl 29、ldapr 29、swpa 28、casal 25、cas 14、casl 10、ldset 9；另有 SVE 指令 1381 条、MTE 相关 10 条。
- 对照：GSI init 仅 11 条 LSE 命中（且能在 cortex-a57 上跑，热路径不经过它们）。
- 结论：Pixel init 是按 Tensor G6（ARMv9）目标编译的，含大量 ARMv8.1+ 指令；cortex-a57 不能执行。**P1 的失败与 fstab、SELinux、property area 均无关**——还没走到那些步骤。

下一步（P2）：单变量改 CPU 为 `-cpu max`（QEMU max 支持 LSE/PAC/BTI/SVE/MTE；PocketVM 审计报告也确认 App 侧用 `cpu max`）。其余全部不变。

## P2 — CPU 改 `-cpu max`（2026-11-15 19:3x，重大进展：first-stage mount 全部成功，到 second-stage 的 SELinux 停下）

与 P1 唯一区别：`-cpu max`（其余不变：同一 ramdisk m1-p1.cpio.gz、四盘、cmdline）。串口日志 `work/m1-pixel/p2-serial.log`（392 行），QEMU 运行 ~24 s 后有序重启退出。

**链路进展（按时间顺序，均为串口日志原文）：**

| 时间 | 事件 | 结果 |
|---|---|---|
| 7.30s | `init: init first stage started!` | ✅ Pixel first-stage init 在 `-cpu max` 上跑起来了 |
| 7.32s | `Unable to open /lib/modules, skipping module loading.` | 非致命（无 Pixel 模块可加载，预期内） |
| 7.38s | `Failed to read vbmeta partitions.` | 非致命（无 vbmeta，预期内） |
| 7.7–8.5s | `/metadata`（vdc）：check_fs 挂载 OK → e2fsck 687ms → `__mount(source=/dev/block/vdc,target=/metadata,type=ext4)=0: Success` | ✅ |
| 8.50s | `Failed to copy /avb into /metadata/gsi/dsu/avb/` | 非致命（DSU 相关） |
| 9.28–19.29s | `partition(s) not found in /sys, waiting for their uevent(s): misc` → 10 秒超时后继续 | 非致命等待（原因未明：fstab 里没有 misc，疑似按默认分区表等待；只造成 10 s 延迟） |
| 19.30s | `DSU not detected, proceeding with normal boot` | — |
| 19.34s | `__mount(source=/dev/block/vda,target=/system,type=ext4)=0: Success` | ✅ system 挂载成功 |
| 19.37s | `Switching root to '/system'` | ✅ switch_root 成功 |
| 19.4–21.0s | `/data`（vdb）：tune2fs 开 quota 失败（`/system/bin/tune2fs` 在 switch_root 后不存在，非致命）→ check_fs OK → e2fsck 1399ms → `__mount(source=/dev/block/vdb,target=/data,type=ext4)=0: Success` | ✅ data 挂载成功 |
| 21.32s | `Skipped setting INIT_AVB_VERSION (not in recovery mode)` | — |
| 22.50s | second-stage init（GSI，BuildId `972f69b5…`）：`ReadDefaultFstab(): failed to find device default fstab` / `Could not read default fstab` | 非致命（GSI 无 fstab，继续） |
| 22.52–22.55s | `Opening SELinux policy` → `No precompiled sepolicy at /vendor/etc/selinux/precompiled_sepolicy` → `Compiling SELinux policy` → `Failed to read /vendor/etc/selinux/plat_sepolicy_vers.txt` → `Unable to open SELinux policy` | ❌ **精确停点** |
| 22.57s | `InitFatalReboot: signal 6`（SetupSelinux+3644，经 libbase abort）→ `Reboot ending, jumping to kernel` | 有序重启，非 panic |

**P2 结论：目标链已走到 `first-stage init → mount（metadata/system/data 全部成功）→ switch_root → second-stage init`，停在 second-stage 的 SetupSelinux。** 停因：switch_root 后 `/vendor` 是 GSI 镜像里那个空目录，ramdisk 的 `/vendor/etc/selinux/*`（precompiled_sepolicy、hash、plat_sepolicy_vers.txt、vendor_property_contexts）留在了旧 root，second-stage init 找不到 policy 来源。first-stage init 本身在 P2 里**没有**加载 policy（日志里无 policy 相关行，与二进制里无 `/sepolicy` 字面路径一致）——policy 加载是 second-stage init 的 `selinux_setup` 阶段的事。
附带确认：first-stage init 在 P2 全程**没有**遇到 property area 问题（PropertyInit 的 FATAL 未出现；该问题要等 SELinux 通过后才会在 second-stage 暴露——M1 已证实它在 second-stage 是 FATAL 的，内核缺 TMPFS_XATTR）。

**P3 设计（单变量：加一个 vendor 分区）**：原生链里 vendor 本来就是独立分区（真机上 second-stage init 从 `/vendor/etc/selinux/precompiled_sepolicy` 读 policy）。P3 加第 5 块 virtio-blk（vde，64 MiB ext4 vendor.img，内含 M1 的 `/vendor/etc/selinux/*` 四个文件原样），fstab.qemu 加一行 `/dev/block/vde /vendor ext4 ro wait,first_stage_mount`，ramdisk 其余不变（新包 m1-p3.cpio.gz，仅 fstab 两处改动）。验证点：switch_root 后 /vendor 挂载是否保留、second-stage SetupSelinux 是否通过、随后 PropertyInit 是否撞上 TMPFS_XATTR 墙。

## P3 — 加 vendor 分区（2026-11-15 19:4x，前进一步：vendor 挂载成功，SELinux 在 system_ext hash 校验停下）

与 P2 唯一区别：加第 5 块 virtio-blk（vde，64 MiB ext4 vendor.img，内含 M1 的 `/vendor/etc/selinux/*` 四文件），fstab.qemu 加 vendor 行；ramdisk m1-p3.cpio.gz 其余与 P1 相同。串口日志 `work/m1-pixel/p3-serial.log`（415 行）。

**新进展：** `__mount(source=/dev/block/vde,target=/vendor,type=ext4)=0: Success`（21.28s，switch_root 之后挂载）——**vendor 分区挂载成功且 switch_root 后可见**。second-stage init 这次找到了 precompiled_sepolicy（不再报 "No precompiled sepolicy"），但在下一步停下：

```text
[22.873] Opening SELinux policy
[22.926] Failed to read /vendor/etc/selinux/precompiled_sepolicy.system_ext_sepolicy_and_mapping.sha256: No such file or directory
[22.927] Compiling SELinux policy
[22.969] Missing /vendor/etc/selinux/vendor_sepolicy.cil
[22.971] Unable to open SELinux policy
[22.993] InitFatalReboot: signal 6   (SetupSelinux+3644，GSI init BuildId 972f69b5)
```

**根因（已用 debugfs 验证）：** switch_root 后 `/system_ext` 不再是 M1 ramdisk 里的空目录，而是 GSI 镜像里真实的 system_ext——内含 `system_ext_sepolicy.cil`（280,691 B）和 `system_ext_sepolicy_and_mapping.sha256`（`4bc12011…42bd`）。second-stage init 的 precompiled-policy 校验逻辑是：只要 system_ext 有 sepolicy，就必须用 vendor 侧的 `precompiled_sepolicy.system_ext_sepolicy_and_mapping.sha256` 校验；文件缺失 → 判定预编译 policy 无效 → 回退到从 CIL 编译 → 缺 `vendor_sepolicy.cil` → FATAL。M1 之所以没撞上：M1 ramdisk 的 `/system_ext` 是空目录，校验被跳过。另已确认 plat 侧 hash 一致：镜像 `/system/etc/selinux/plat_sepolicy_and_mapping.sha256` = `f6d4906f…af7ba`，与 M1 vendor 的 plat hash 文件完全相同——P3 的 plat 校验已通过。

**P4 设计（单变量：vendor.img 里补 system_ext hash 文件）**：把镜像 `system_ext_sepolicy_and_mapping.sha256` 的内容（64 hex + 换行，65 B）原样拷为 vendor.img 的 `/etc/selinux/precompiled_sepolicy.system_ext_sepolicy_and_mapping.sha256`。ramdisk 不动（沿用 m1-p3.cpio.gz）。验证点：precompiled policy 校验是否通过、随后 PropertyInit 是否撞上 TMPFS_XATTR 墙。

## P4 — vendor.img 补 system_ext hash（2026-11-15 19:5x，system_ext 校验通过，轮到 product hash）

与 P3 唯一区别：vendor.img 里补 `precompiled_sepolicy.system_ext_sepolicy_and_mapping.sha256`（内容取自镜像 `system_ext_sepolicy_and_mapping.sha256`，`4bc12011…42bd`，65 B）；ramdisk 沿用 m1-p3.cpio.gz。串口日志 `work/m1-pixel/p4-serial.log`（415 行）。

**新进展：** system_ext 校验通过（不再报 system_ext hash），下一缺失项出现：

```text
[23.039] Opening SELinux policy
[23.150] Failed to read /vendor/etc/selinux/precompiled_sepolicy.product_sepolicy_and_mapping.sha256: No such file or directory
[23.150] Compiling SELinux policy
[23.166] Missing /vendor/etc/selinux/vendor_sepolicy.cil
[23.168] Unable to open SELinux policy
[23.189] InitFatalReboot: signal 6   (SetupSelinux+3644，GSI init BuildId 972f69b5)
```

**规律已明确：** second-stage init 按顺序校验每一个含 sepolicy 的分区（plat → system_ext → product → …），缺哪一个 vendor 侧 hash 就回退编译、再因缺 vendor_sepolicy.cil 失败。已用 debugfs 核查镜像：`/product/etc/selinux/` 含 `product_sepolicy.cil`（280,691 B）与 `product_sepolicy_and_mapping.sha256`（`4bc12011…42bd`，与 system_ext 的 hash 值相同——两个 CIL 同为 280,691 B，疑似同一份空 policy）；`/odm/etc/selinux` 与镜像内 `/vendor/etc/selinux` 均为空。**因此只剩 product 一个 hash 待补。**

**P5 设计（单变量：vendor.img 里补 product hash）**：加 `precompiled_sepolicy.product_sepolicy_and_mapping.sha256`（内容 `4bc12011…42bd`，65 B）。验证点：precompiled policy 校验是否全部通过、policy 是否加载、随后 PropertyInit 是否撞上 TMPFS_XATTR 墙。

## P5 — vendor.img 补 product hash（2026-11-15 20:0x，✅ 目标链达成：原生链走到 second-stage + apexd，与 M1 同终点）

与 P4 唯一区别：vendor.img 里补 `precompiled_sepolicy.product_sepolicy_and_mapping.sha256`（`4bc12011…42bd`，65 B）；ramdisk 沿用 m1-p3.cpio.gz。串口日志 `work/m1-pixel/p5-serial.log`（845 行），QEMU 运行 ~36 s 后有序重启。

**链路全程（串口日志原文，时间戳为启动后秒数）：**
- 23.12s `Loading SELinux policy` → 内核 policy capability 打印（14 行）→ 23.78s `audit type=1404: enforcing=1 old_enforcing=0` → 23.86s file contexts 加载（plat/system_ext/product）→ **24.30s `init second stage started!`** ✅
- 25.3s properties 加载（`Setting product property…`、`Overriding previous property 'ro.build.flavor'…`）→ 25.35s `Running restorecon...` → 25.43s property service sockets 创建 → 25.59s `Forked subcontext for 'u:r:vendor_init:s0'` → init.rc 解析（缺 `/init.qemu.rc` 与 `/vendor/etc/init`，非致命）→ 26.8s `starting service 'ueventd'...`（pid 81）→ servicemanager / vold / hwservicemanager / audioserver 等陆续启动。
- 27.25s `starting service 'apexd-bootstrap'...`（pid 82）→ **28.78s `apexd-bootstrap: Failed to collect pre-installed APEX files: Can't open /odm/apex for reading: Permission denied`** → 28.87s `Service 'apexd-bootstrap' (pid 82) exited with status 1` → `reboot_on_failure` → 35.24s `reboot: Restarting system with command 'bootloader,bootstrap-apexd-failed'`。

**目标链判定：✅ 达成。`first-stage init → mount（metadata/system/data/vendor 全部成功）→ switch_root → second-stage init → SELinux policy 加载 → init.rc 执行 → ueventd/服务启动 → apexd-bootstrap`。终点与 M1 wrapper 路线完全相同（`bootstrap-apexd-failed`），且是同一条原生链、同一终点。**

**P5 的精确停因（apexd 失败的直接原因）：** vendor 分区（vde）没有任何 SELinux 标签。日志中共 27 条 AVC 拒绝，全部同一模式：`avc: denied { search } … name="/" dev="vde" ino=2 tcontext=u:object_r:unlabeled:s0 … permissive=0`（涉及 vendor_init、ueventd、apexd、servicemanager、hwservicemanager）。vde 是用 `mke2fs -d` 建的裸 ext4，未写 `security.selinux` xattr；GSI 又没有 `vendor_file_contexts`，restorecon 无从标注。apexd 连 `/vendor` 根目录都 search 不了，更读不到 `/odm/apex`（`/odm` → `/vendor/odm`）。

**两个附带发现（均已用日志核对）：**
1. **M1 与 P5 的 SELinux 状态不同（已证实）：** M1 最终日志里 policy 从未载入内核（`Loading SELinux policy` 出现 0 次、capability 行 0 条），且反复出现 `SELinux: SELinux is disabled, skipping restorecon`、零 AVC 记录——因为 M1 wrapper 从未挂载 selinuxfs，second-stage init 的 SetupSelinux 直接跳过，SELinux 实际处于关闭状态。P5 里 Pixel first-stage init 挂载了 selinuxfs，policy 正常载入并切到 enforcing。**即 M1 的 apexd 失败与 SELinux 无关（它是 libdl_android.so/APEX 循环问题），P5 的 apexd 失败则是由 enforcing + 未标注 vendor 造成的——同一终点，不同原因。**
2. **PropertyInit 在 P5 里成功了（未解之谜，待验证）：** 日志证明 property 服务正常工作（25.3s 起大量 `Setting product property`），且全程无 `Failed to initialize property area`。但 vdd 在 P5 里从未被挂载（日志只有 virtio_blk 发现 vdd，无 `EXT4-fs (vdd): mounted` 行），而内核缺 `CONFIG_TMPFS_XATTR` 是 M1 用 gdb 实测过的（tmpfs 上 fsetxattr 392 次 ENOTSUPP）。按 M1 的机理，tmpfs 上的 property area 应当同样 FATAL。最可能的解释：Pixel first-stage init 在 second-stage 之前已预初始化了 property area（P0 证据：二进制含 `/dev/__properties__`、`/dev/__properties__/property_info`、`/dev/socket/property_service` 字串；且 25.27s 出现 `Overriding previous property`——说明 second-stage 启动时 property area 里已有 first-stage 写入的值），second-stage 的 `__system_property_area_init` 发现文件已存在便只做映射、跳过 fsetxattr 步骤。**此为假说，未经定向验证**（需 gdb/strace 跟踪 first-stage init 的 property 初始化，或做一次对照：去掉 first-stage 的 property 预初始化看是否复现 FATAL）。
3. **cmdline 的 `androidboot.selinux=permissive` 在 P5 里未生效**（audit 1404 显示切到 enforcing=1）。原因未明：可能是 second-stage 的 SetupSelinux 在 PropertyInit 之前读不到 `ro.boot.selinux`（property area 尚未由 second-stage 初始化），也可能是它根本不读 cmdline 而默认 enforcing。P6 将用 `enforcing=0` 内核参数做一次对照，判断 permissive 是否可达。

**P6 设计（单变量：cmdline 加 `enforcing=0` 内核参数）**：其余与 P5 完全相同。验证点：audit 1404 是否仍出现 enforcing=1；若保持 permissive，apexd 是否越过 AVC 拒绝、走到下一个失败点（M1 的 APEX 循环）。

## P6 — cmdline 加 `enforcing=0`（2026-11-15 20:1x，permissive 不可达：SetupSelinux 无条件切 enforcing）

与 P5 唯一区别：cmdline 追加 `enforcing=0` 内核参数。串口日志 `work/m1-pixel/p6-serial.log`（833 行）。

**结果：无变化。** audit 1404 仍在 23.86s 出现：`enforcing=1 old_enforcing=0`。AVC 拒绝仍在（14 条，同模式，vde 根目录 unlabeled），apexd-bootstrap 同样在 28.74s 失败（`Can't open /odm/apex for reading: Permission denied`，exit 1），终点仍是 `bootstrap-apexd-failed`。**结论：本链里 permissive 无法通过 cmdline 达成——GSI second-stage init 的 SetupSelinux 在载入 policy 后无条件置 enforcing=1，`androidboot.selinux=permissive` 与 `enforcing=0` 均被忽略。**（另见日志 26.5s：`Parsing file /system/etc/init/zygote_next64.rc` 已解析，但 zygote 因 apexd 先失败而未启动；且 `ro.zygote` 属性在 init.rc 解析时不存在——`Could not expand import … init.${ro.zygote}.rc`，属下一阶段待查项，非当前堵点。）

---

## 最终结论

### 验收问题回答

| 问题 | 答案（日志证据） |
|---|---|
| Pixel first-stage init 能不能在 QEMU virt 上跑 first-stage mount？ | **能。** 条件：`-cpu max`（P1 证明 cortex-a57 上因 LSE 指令 SIGILL；P2 起 `-cpu max` 正常）。 |
| 能挂载哪些分区？ | **全部四个已配分区：** /metadata（vdc）、/system（vda）、/data（vdb）、/vendor（vde，P3 起）。挂载均返回 Success；另有 switch_root 成功。 |
| 能不能交到 second-stage？ | **能。** P5 起 `init second stage started!`（24.30s），SELinux policy 载入、init.rc 解析、ueventd（pid 81）及 servicemanager/vold 等服务启动。 |
| 精确停在哪一步、为什么？ | **停在 `apexd-bootstrap`（second-stage early-init 阶段）。** 原因：vendor 分区无 SELinux 标签（`unlabeled`）+ SELinux enforcing → apexd 无法 search `/vendor` 根目录 → 读不到 `/odm/apex` → `Can't open /odm/apex for reading: Permission denied` → exit 1 → `reboot_on_failure` → 有序重启 `bootstrap-apexd-failed`。证据：P5 日志 27–28s（apexd 行 + AVC 拒绝 27 条）与 P6 对照（cmdline 改 permissive 仍 enforcing、同样失败）。 |
| 目标链是否达成？ | **达成。** `first-stage init → mount → second-stage init` 全链路已跑通；终点与 M1 wrapper 路线完全相同（`bootstrap-apexd-failed`）。未达成的是链路之后的 APEX bootstrap——那是 M2（Vendor 层）的事，不是 first-stage 链本身的问题。 |

### 与 M1 wrapper 路线的对比

| 维度 | M1 wrapper 路线 | Pixel 原生链（本实验） |
|---|---|---|
| first-stage | sh wrapper 手写（mount proc/sys/dev、挂 vdd、exec second_stage） | 真正的 Android first-stage init（Pixel init_boot.img，静态） |
| 挂载 | wrapper 不挂分区；分区挂载全在 second-stage 之后 | first-stage init 按 fstab 挂载 metadata/system/data/vendor，再 switch_root |
| SELinux | **实际关闭**（wrapper 未挂 selinuxfs → SetupSelinux 跳过；policy 未载入内核；零 AVC） | **enforcing**（policy 载入 + file contexts + restorecon + audit 1404） |
| property area | 需 vdd ext4 绕过（内核缺 TMPFS_XATTR，FATAL 已实测） | **无需 vdd 也能通过**（机制未完全解释，见下） |
| init.rc / 服务 | 执行，ueventd pid 81 | 执行，ueventd pid 81，服务更多（servicemanager/vold/hwservicemanager…） |
| 终点 | `bootstrap-apexd-failed`（libdl_android.so/APEX 循环） | `bootstrap-apexd-failed`（vendor 未标注 + enforcing 拒绝） |
| 可复用性 | wrapper 是临时脚手架，不可作为产品链 | **原生链，可复用**：fstab + vendor 分区 + policy 文件即为 M2 的 Vendor 层起点；Pixel 二进制本身不进入分发物，AOSP 源码重建是最终实现 |

**原生链比 wrapper 好在哪：** 它是 Android 真正的启动路径（first-stage mount → switch_root → selinux_setup → second-stage），SELinux 实际生效、分区挂载顺序与真机一致、property area 无需 vdd  workaround；wrapper 只是绕过 first-stage 的临时桥，SELinux 处于关闭态，测不出任何 SELinux 相关问题。
**还差什么：** （1）vendor 分区标注（vendor_file_contexts + security.selinux xattr，属 M2）；（2）apexd/APEX bootstrap（M1 同款堵点，机制在原生链里更清晰）；（3）两个未解子问题（见下）。

### 未解子问题（已定位，未定论）

1. **PropertyInit 为何在 P5 里成功：** vdd 未挂载、内核缺 TMPFS_XATTR（M1 gdb 实测 tmpfs fsetxattr 392 次 ENOTSUPP → FATAL），但 P5 的 property 服务正常工作。领先假说：Pixel first-stage init 在 second-stage 之前预初始化了 property area（证据：二进制含 `/dev/__properties__` 字串；second-stage 启动时已有 `Overriding previous property`，即 area 里已有 first-stage 写入的值），second-stage 的 `__system_property_area_init` 见文件已存在便只映射、跳过 fsetxattr。需定向验证（gdb/strace 跟踪 first-stage 的 property 初始化，或做对照实验）。
2. **SetupSelinux 为何无视 permissive：** `androidboot.selinux=permissive` 与 `enforcing=0` 均被忽略，载入 policy 后必切 enforcing=1。可能原因：SetupSelinux 在 PropertyInit 之前读不到 `ro.boot.selinux`（property area 尚未由 second-stage 初始化）而默认 enforcing；或该版本 init 根本不读 cmdline。需读 GSI init 的 SetupSelinux 反汇编或做 AOSP 源码对照确认。

### 下一步建议

1. **继续攻 vendor 标注 + apexd（M2 范畴），不要回头改 wrapper。** 具体：写 `vendor_file_contexts`，用 `debugfs ea_set`（或 e2fsdroid）给 vendor.img 写 `security.selinux` xattr（根目录及 etc/selinux 至少标为 `vendor_file`/`sepolicy_file`），再跑 P5 同款命令，看 apexd-bootstrap 是否通过；通过后即进入 zygote/system_server 阶段。
2. **不要为 permissive 再花时间。** P6 已证明 cmdline 两条路都走不通；若后续确实需要 permissive 调试，应走 policy 侧（在 vendor policy 里加 allow 规则）或 AOSP 源码侧（改 SetupSelinux 的默认行为），而不是继续调 cmdline。
3. **内核重编（CONFIG_TMPFS_XATTR=y）仍建议做，但不再是 property area 的堵点。** P5 证明原生链不依赖 vdd 绕过；重编的意义是让链路符合 Android 的正式要求（tmpfs xattr 是 AOSP 的既定假设），并消除 M1 机理与 P5 现象之间的不一致——应在 M2 之前或与 vendor 标注并行做，而不是等 apexd 通过后再补。
4. **长期：AOSP 源码重建 first-stage init。** Pixel init_boot.img 只是参考样本（且需 `-cpu max` 才能跑，含 Tensor G6 目标指令）；产品化必须用 AOSP 源码按 virt 目标重编 first-stage init（含 virt fstab、virt DTB、通用 vendor ramdisk），Pixel 二进制绝不进入 PocketVM 分发物。

### 最终可复现的 QEMU 启动命令（P5/P6 同款，vdd 挂着但原生链未使用）

```bash
cd ~/workspace/bootable-ramdisk
# vdd 每轮重建（M1 遗留要求；原生链未使用，但保持与实验一致）
rm -f ~/workspace/qemu-boot/prop_area.img
dd if=/dev/zero of=~/workspace/qemu-boot/prop_area.img bs=1M count=128 status=none
/usr/sbin/mke2fs -t ext4 -F -q ~/workspace/qemu-boot/prop_area.img

qemu-system-aarch64 \
  -machine virt -cpu max -smp 2 -m 2048 -accel tcg,thread=multi \
  -kernel Image-qemu-virt -initrd work/m1-pixel/m1-p3.cpio.gz \
  -append "console=ttyAMA0,115200 androidboot.console=ttyAMA0 androidboot.hardware=qemu androidboot.selinux=permissive androidboot.verifiedbootstate=orange androidboot.vbmeta.device=none printk.devkmsg=on" \
  -drive file=/home/hatch/workspace/qemu-boot/system.img,format=raw,if=none,id=sys,readonly=on -device virtio-blk-pci,drive=sys \
  -drive file=userdata.img,format=raw,if=none,id=user -device virtio-blk-pci,drive=user \
  -drive file=metadata.img,format=raw,if=none,id=meta -device virtio-blk-pci,drive=meta \
  -drive file=/home/hatch/workspace/qemu-boot/prop_area.img,format=raw,if=none,id=prop -device virtio-blk-pci,drive=prop \
  -drive file=/home/hatch/workspace/bootable-ramdisk/work/m1-pixel/vendor.img,format=raw,if=none,id=vendor,readonly=on -device virtio-blk-pci,drive=vendor \
  -device virtio-gpu-pci -device virtio-net-pci,netdev=net0 -netdev user,id=net0 \
  -display none -serial file:work/m1-pixel/p6-serial.log -monitor none -no-reboot
```

### 依赖清单（最终版）

| 文件 | 来源 | 用途 | 大小/SHA |
|---|---|---|---|
| Pixel `/init`（静态） | `init_boot.img` ramdisk（`~/workspace/pixel-images/analysis/ramdisk-init_boot/init`） | first-stage init，PID 1 | 3,005,824 B，SHA-256 `622c3bacd6f0c2c5…`，BuildID `118fa24a…` |
| `fstab.qemu`（新，见下） | 本实验编写（M1 fstab + vendor 行） | first-stage 挂载表，5 条 | 见 `work/m1-pixel/fstab.qemu.final` |
| `precompiled_sepolicy` | M1 成果（secilc 自 GSI CIL 编译，v33） | second-stage policy | 522,021 B（vendor.img 内） |
| `precompiled_sepolicy.plat_sepolicy_and_mapping.sha256` | M1 成果 | plat 校验 | `f6d4906f…af7ba` |
| `precompiled_sepolicy.system_ext_sepolicy_and_mapping.sha256` | GSI 镜像 `/system_ext/etc/selinux/` 提取 | system_ext 校验 | `4bc12011…42bd` |
| `precompiled_sepolicy.product_sepolicy_and_mapping.sha256` | GSI 镜像 `/product/etc/selinux/` 提取 | product 校验 | `4bc12011…42bd` |
| `plat_sepolicy_vers.txt` | M1 成果 | SELinux 版本检查 | 内容 `37.0` |
| `vendor_property_contexts` | M1 成果（GSI） | vendor 属性 contexts | 377 B |
| `vendor.img` | 本实验组装（64 MiB ext4，`mke2fs -d`） | vendor 分区（vde），含以上 6 文件 | `work/m1-pixel/vendor.img` |
| `system.img`（GSI） | `~/workspace/qemu-boot/system.img` | /system（vda，ro） | 2,114,777,088 B |
| `userdata.img` / `metadata.img` | M1 成果 | /data（vdb）、/metadata（vdc） | 4 GiB / 16 MiB ext4 |
| `prop_area.img`（vdd） | 每轮重建 | 本实验未使用（M1 wrapper 遗留） | 128 MiB ext4 |
| `Image-qemu-virt` | `~/workspace/qemu-boot/` | GKI 6.18.21 内核（缺 CONFIG_TMPFS_XATTR） | 44,329,472 B |
| QEMU | 本机 8.2.2 | 宿主模拟器 | `-cpu max` 为必需条件 |

### 新 fstab（最终版，5 条，与 ramdisk 内两份一致）

```text
/dev/block/vda     /system     ext4   ro,barrier=1                                               wait,first_stage_mount
/dev/block/vdb     /data       ext4   noatime,nosuid,nodev,noauto_da_alloc,barrier=1,errors=panic   wait,check,formattable,first_stage_mount,quota
/dev/block/vdc     /metadata   ext4   noatime,nosuid,nodev                                        wait,check,formattable,first_stage_mount
/dev/block/vde     /vendor     ext4   ro                                               wait,first_stage_mount
```
（另：first-stage init 在挂载前会等一个名为 `misc` 的分区 10 秒超时——fstab 里没有 misc，原因未明，仅造成 10 秒延迟，非致命。）
