# M6：QEMU virt + VirtIO GPU 实验环境恢复与 Boot HAL 边界实验 — 2026-11-15

> 本轮目标：把 M4/M5 的「Android 17 + QEMU virt + VirtIO GPU」实验环境恢复成可重复运行的状态，
> 并对 Pixel vendor 的 Boot HAL 做一次有止损条件的兼容实验。
> 结论：内核与图形设备层恢复成功；用户态阻塞在 Pixel vendor 的 SELinux 设备标签，
> **不是** Android 已启动到桌面。

## 0. 一句话结论

M4 内核（6.18.21-android17-5）在 QEMU 11.1.3 的 `-M virt` 上复现了 VirtIO GPU DRM 初始化；
Android 17 GSI 走过 first-stage → second-stage → apexd-bootstrap → post-fs-data 的
`vdc checkpoint prepareCheckpoint`（成功）；随后被 Pixel Boot HAL 的 SELinux 策略挡住，
zygote / SurfaceFlinger 未启动。

## 1. 内核

| 项 | 值 |
| --- | --- |
| 文件 | `~/Downloads/Image-qemu-virt`（工作副本，未修改） |
| 大小 | 44,329,472 B |
| SHA-256 | `578080b9ba3d56a21219142f7fad161f7f663d95809491899fd2da182e9aa278` |
| 版本 | `6.18.21-android17-5-maybe-dirty-4k`，ARM64，4K 页，clang 22.0.1 |
| 构建 | kleaf，时间戳 1970（可复现构建） |

注意 `-maybe-dirty`：这是带本地改动的构建，具体改动未知；复现时以 SHA 为准。

关键配置（从内核内嵌 IKCONFIG 实测提取）：

```text
CONFIG_DRM=y
CONFIG_DRM_VIRTIO_GPU=y
CONFIG_DRM_VIRTIO_GPU_KMS=y
CONFIG_VIRTIO=y
CONFIG_VIRTIO_PCI=y
CONFIG_VIRTIO_MMIO=y
CONFIG_VIRTIO_BLK=y
CONFIG_VIRTIO_INPUT=y
CONFIG_VIRTIO_NET=y
CONFIG_VIRTIO_CONSOLE=y
CONFIG_ANDROID_BINDER_IPC=y
CONFIG_ANDROID_BINDERFS=y
CONFIG_ASHMEM=y
# CONFIG_FB / CONFIG_VT / CONFIG_DRM_SIMPLEDRM 未设置
# CONFIG_TMPFS_XATTR 未设置（已知缺口）
# CONFIG_DMABUF_HEAPS_SYSTEM 未设置（已知缺口）
```

## 2. 运行环境

- 宿主：Arch Linux，QEMU **11.1.3**（`qemu-system-aarch64`）
- 需要额外安装的 Arch 模块包：`qemu-hw-display-virtio-gpu`、`qemu-hw-display-virtio-gpu-pci`
  （Arch 把 virtio-gpu 拆成独立包，只装 `qemu-system-aarch64` 时 `-device virtio-gpu-pci` 会报无效设备）
- 全部盘用 `virtio-blk-pci` + 固定 PCI 地址；内核 virtio 全内置，ramdisk **不加载任何模块**
  （此前为 Pixel GKI 准备的 6.12 模块与 6.18 内核 vermagic 不匹配，会加载失败）

## 3. 磁盘布局与启动命令

| 设备 | 地址 | 内容 | 挂载点 | 读写 |
| --- | --- | --- | --- | --- |
| vda | 0x2 | GSI `system.img`（ext4） | `/system` | ro |
| vdb | 0x3 | `userdata.img`（ext4，1 GiB） | `/data` | rw |
| vdc | 0x4 | `metadata.img`（ext4，16 MiB） | `/metadata` | rw |
| vdd | 0x5 | `vendor-qemu.img`（EROFS） | `/vendor` | ro |
| vde | 0x7 | `odm.img`（ext4，16 MiB，仅存放 fstab） | 未挂载（v2） | ro |
| vdf | 0x8 | `misc.img`（裸盘，1 MiB 全零） | 由 Boot HAL 直读 | rw |
| — | 0x6 | `virtio-gpu-pci` | — | — |

最终布局**没有 GPT 分区表**：每块盘是一个独立 `virtio-blk-pci` 设备，fstab 直接用 `/dev/block/vdX`。
早期尝试过「单块 GPT 盘 + 分区」与「NVMe 多命名空间」，均因盘号/分区名不稳定而弃用。

完整命令见 `artifacts/m6/run-m4-v2.sh`。摘要：

```bash
qemu-system-aarch64 -M virt,gic-version=3 -cpu max -smp 4 -m 4096 \
  -kernel ~/Downloads/Image-qemu-virt \
  -initrd out/ramdisk-m4-v2.lz4 \
  -append "console=ttyAMA0,115200 androidboot.console=ttyAMA0 androidboot.hardware=qemu \
androidboot.force_normal_boot=1 androidboot.selinux=permissive \
androidboot.verifiedbootstate=orange androidboot.vbmeta.device=none printk.devkmsg=on" \
  -drive file=system.img,...,readonly=on -device virtio-blk-pci,drive=sys,addr=0x2 \
  ...（userdata 0x3 / metadata 0x4 / vendor 0x5 / odm 0x7 / misc 0x8） \
  -device virtio-gpu-pci,addr=0x6 \
  -display none -monitor none -no-reboot -serial file:boot.log
```

ramdisk 由 `artifacts/m6/build-ramdisk.sh` 生成：Pixel `init_boot` 的静态 first-stage init
+ `vendor_boot` 第一段 + QEMU 专用 `fstab.qemu`（`/system`、`/data`、`/metadata`、`/vendor`）。

## 4. Boot HAL 兼容实验（方案 1）

### 4.1 先定位 Boot HAL 的依赖（实测，strings）

`vendor/bin/hw/android.hardware.boot-service.default-pixel` 的字符串确认：

- 默认 fstab 查找路径：`/vendor/etc/fstab.<suffix>`、`/odm/etc/fstab.<suffix>`、`/fstab.<suffix>`、
  `/first_stage_ramdisk/fstab.<suffix>`；`suffix` 来自 `fstab_suffix`（缺省用 `ro.hardware=qemu`）
- 需要 fstab 中存在 `/misc` 条目；否则 `InitMiscVirtualAbMessageIfNeeded()` 失败
- 字符串含 `/dev/block/by-name/misc`，但我们的 virtio 盘没有 GPT 分区名，实际使用直连路径

### 4.2 做了两轮

| 版本 | 变更 | 结果 |
| --- | --- | --- |
| v1（`boot-m4.log`） | 只有 4 块盘（无 misc） | `post-fs-data` 的 `vdc checkpoint` 卡住；Boot HAL 报 `failed to find default fstab` |
| v2（`boot-m4-v2.log`） | 重建 vendor，加入 `/vendor/etc/fstab.qemu`（`/dev/block/vdf /misc emmc defaults defaults`，标签 `vendor_configs_file`）；新增 `misc.img`（vdf）与 `odm.img`（vde，未挂载） | `vdc checkpoint prepareCheckpoint` **72ms 成功**；但 Boot HAL 被 SELinux 拒绝，仍然 SIGABRT 循环 |

v2 中 `odm.img` 只用来占住 vde 编号（保持 misc = vdf），**没有挂载**：
第一次尝试把 fstab 放进 `/odm` 并挂载，导致 apexd 扫描 `/odm/apex` 被拒绝（根目录无标签），
触发 `bootstrap-apexd-failed` 重启，该方案已弃用。

### 4.3 决定性证据（v2）

`vdc checkpoint` 成功：

```text
[   22.470172][ T1] init: Command 'exec - system system -- /system/bin/vdc checkpoint prepareCheckpoint'
                    action=post-fs-data (/system/etc/init/hw/init.rc:632) took 72ms and succeeded
```

Boot HAL 找到了 fstab、也解析出了 misc 设备，但 SELinux 拒绝访问：

```text
[   22.507289][ T241] avc: denied { getattr } for comm="android.hardwar" path="/dev/block/vdf"
    dev="tmpfs" ino=513 scontext=u:r:hal_bootctl_default:s0
    tcontext=u:object_r:vd_device:s0 tclass=blk_file permissive=0
[   32.514080][ T253] bootcontrolhal: Check failed: InitMiscVirtualAbMessageIfNeeded()
[   32.666999][ T1] init: Service 'vendor.boot-default' (pid 253) received SIGABRT
```

即：Pixel 的 SELinux 策略要求 misc 设备是 Pixel/UFS 语义的设备标签，
而 virtio 块设备在 AOSP 策略里是 `vd_device`，`hal_bootctl_default` 对它没有 `getattr` 权限。
`androidboot.selinux=permissive` 在 second-stage 被忽略（M1 P6 已证），无法绕过。

### 4.4 止损判定

继续需要修改 Pixel 的 SELinux 策略（或换用 Pixel 的 UFS 设备语义），
属于「为迁就 Pixel vendor 持续堆 Pixel 硬件假设」，按约定停止该分支。

## 5. 已验证 / 未验证

| 项 | 状态 | 证据 |
| --- | --- | --- |
| M4 内核在 QEMU 11.1.3 virt 上启动 | ✅ 已验证 | `boot-m4-v2.log:288-293` |
| VirtIO GPU DRM + `/dev/dri/card0` | ✅ 已验证 | `Initialized virtio_gpu 0.1.0 ... on minor 0` |
| first-stage init / 四盘挂载 / switch_root | ✅ 已验证 | `boot-m4-v2.log:385-423` |
| SELinux 策略加载 | ✅ 已验证 | Pixel vendor 策略编译/加载通过 |
| second-stage init / apexd-bootstrap | ✅ 已验证 | `Activated 4 packages`（`boot-m4-v2.log:825`） |
| post-fs-data 的 `vdc checkpoint` | ✅ 已验证 | 72ms 成功（`boot-m4-v2.log:1039`） |
| Pixel Boot HAL | ❌ 被 SELinux 设备标签挡住 | AVC `hal_bootctl_default` → `vd_device` |
| zygote / system_server / SurfaceFlinger | ❌ 未启动 | 日志无 `starting service 'zygote'` |
| Android 图形栈（EGL/gralloc/HWC） | ❌ 未开始 | 停在 Boot HAL |
| 手机端 QEMU 11.1.2 同链路 | 未验证 | 本轮全部是 PC QEMU 11.1.3 |

## 6. 其他内核候选（同日核查，结论为不可替代）

Android Emulator API 37 ARM64 系统镜像内的 `kernel-ranchu` 也检查过：

- 版本 `6.12.81-android16-6-g4f69fc7b210c-ab16167562-4k`，42,506,752 B
- 配置 `CONFIG_DRM_VIRTIO_GPU` **未设置**，`CONFIG_GOLDFISH` 未设置
- `strings` 中 `virtio_gpu` 出现 0 次

即 Emulator 的 ARM64 内核不能替代 M4 内核做虚拟显示。详见本轮对话记录；
如需落文档可另开一节。

## 7. 产物与哈希

| 产物 | 路径 | 大小 (B) | SHA-256 |
| --- | --- | --- | --- |
| M4 内核 | `~/Downloads/Image-qemu-virt` | 44,329,472 | `578080b9ba3d56a21219142f7fad161f7f663d95809491899fd2da182e9aa278` |
| GSI system | `~/workspace/gsi/system.img` | 2,133,286,912 | `2bc98fa49890e220e7b9b0a3386b07afccdbf6326208320985cc134007857554` |
| userdata | `~/workspace/android-guest/images/userdata.img` | 1,073,741,824 | `f69f8b46236ce8764f21a3b08917abcce52b9180661728825ccb82b5d6729238` |
| metadata | `~/workspace/android-guest/images/metadata.img` | 16,777,216 | `473ff9cfd1d76ed4139f12d1b52c94f58a1651ec269f61c7f30f93548e5cdbb4` |
| vendor（Pixel 原样） | `~/workspace/android-guest/images/vendor-pixel.img` | 1,111,818,240 | `6956d10ce5306ed8fcda475882ef132219e518f8af571c69fbd215f214780d42` |
| vendor（加 fstab） | `~/workspace/android-guest/images/vendor-qemu.img` | 780,984,320 | `7b7db1075c5bdcf133edf00317dfef6d61b9652e66125768a8c897ed624f05ed` |
| odm | `~/workspace/android-guest/images/odm.img` | 16,777,216 | `3cb0303848c6d55d0f454187e206cf27f2c684aeda796bc4492d5eb1b35358bd` |
| misc | `~/workspace/android-guest/images/misc.img` | 1,048,576 | `30e14955ebf1352266dc2ff8067e68104607e750abb9d3b36582b8af909fcb58` |
| ramdisk v1（无模块） | `~/workspace/android-guest/out/ramdisk-nomod.lz4` | 26,768,600 | `3a05f77f500553202a5d78ef69c2bab6fe6aaceded07a7d8c1e6f954ca7dabf8` |
| ramdisk v2（含 misc 布局） | `~/workspace/android-guest/out/ramdisk-m4-v2.lz4` | 26,768,620 | `d16b3e0a2a371bc16537560bf676889c13bc1357a5fbca53a8286786f4a66d2b` |

日志与脚本已存入仓库：

- `artifacts/m6/boot-m4.log.gz`（v1，Pixel vendor 原样，checkpoint 卡住）
- `artifacts/m6/boot-m4-v2.log.gz`（v2，加 fstab+misc，DRM 与 checkpoint 证据）
- `artifacts/m6/build-ramdisk.sh`、`run-m4.sh`、`run-m4-v2.sh`

## 8. 复现步骤（摘要）

1. 准备 `~/Downloads/Image-qemu-virt`（核对 SHA）。
2. 准备 `~/workspace/gsi/system.img`（核对 SHA）。
3. `cd ~/workspace/android-guest`
4. 生成磁盘：`userdata.img`（1 GiB ext4）、`metadata.img`（16 MiB ext4）、`misc.img`（1 MiB 全零）。
5. 重建 vendor：挂载 `vendor-pixel.img`（EROFS）→ `cp -a --preserve=xattr` →
   写入 `/etc/fstab.qemu`（misc 指向 `/dev/block/vdf`，标签 `vendor_configs_file`）→
   `mkfs.erofs -zlz4 vendor-qemu.img <tree>`。
6. `PVM_MODULES=0 PVM_RAMDISK_NAME=ramdisk-m4-v2.lz4 bash build-ramdisk.sh`
7. `bash run-m4-v2.sh out/boot-m4-v2.log`（需要上述 Arch QEMU 显示模块包）

## 9. 下一步

- **P0**：找回 M4 自建 vendor（`vendor-m3-v2.img` / `vendor-m5-e1c.img`），恢复 M4 原始链路；
  它围绕 QEMU virt 构建，不需要迁就 Pixel 的设备与策略假设。
- 不再继续 Pixel vendor 分支（止损）。
- 内核/图形设备层结论可复用：M4 内核 + virtio-gpu-pci 在 QEMU 11.1.3 上稳定工作。
