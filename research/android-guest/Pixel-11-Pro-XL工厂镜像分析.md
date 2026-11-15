# Pixel 11 Pro XL 工厂镜像分析

- 分析对象：`~/workspace/pixel-images/kodiak_beta-dp11.261030.003-factory-2cc3cf49.zip`
- 分析日期：2026-11-15
- 分析方式：只读分析，未修改 zip；大镜像只读头信息，未全量解压
- 报告版本：v1.0

## 结论先行

| 结论 | 标签 |
|---|---|
| 镜像完整，SHA-256 与官方一致，15,375,459,935 B | 已验证 |
| Android 17 QPR3 Beta，build DP11.261030.003，安全补丁 2026-10-05，kodiak（Pixel 11 Pro XL） | 已验证 |
| 内核 6.12.81-android16-6，有 4K 与 16K 两个版本 | 已验证 |
| **init_boot.img 里有一个真正的、静态链接的 first-stage init**（3,005,824 B），与 GSI 那个纯 second-stage 的 init 是不同的二进制 | 已验证 |
| 镜像不能直接在 QEMU virt 上启动（设备专属：UFS、Tensor DTB、Pixel 驱动、无 virtio-blk/gpu/net 驱动） | 已验证（strings 检测） |
| first-stage init 可作 M1 的参考与候选替代来源，但直接拿去跑 QEMU virt 仍需适配（fstab、DTB、模块），不是拿来即用 | 推测（未实际启动验证） |
| 系统分区全部是 EROFS；没有 super.img，super 由 fastboot 现场组装 | 已验证 |
| 未做：未刷机、未启动任何镜像、未解包 super/系统分区内容 | — |

**对用户最重要的一句话**：这次下载的工厂镜像里，`init_boot.img` 的 `/init` 是一个**静态链接的真 first-stage init**——正是 M1 实验一直缺的东西（GSI 的 init 是纯 second-stage，WorkBuddy 那个二进制从未验证）。它能不能直接在 QEMU virt 上跑还不确定，但它是一个已经验证存在的、可研究的真品：可以对照它的行为理解 first-stage init，也可以作为候选的替代来源去试。

---

## 1. 内容清单

### 1.1 外层 zip（9 个条目，未解压 15,510,955,014 B）

| 文件 | 大小 | 用途 |
|---|---|---|
| `kodiak_beta-dp11.261030.003/` | 目录 | — |
| `flash-all.sh` / `flash-all.bat` | 1,137 / 1,020 B | 刷机脚本（Linux/macOS / Windows） |
| `flash-base.sh` | 1,080 B | 只刷 bootloader + radio，不刷系统 |
| `image-kodiak_beta-dp11.261030.003.zip` | 15,290,274,305 B | 内层镜像包（47 个文件，见 1.2） |
| `bootloader-kodiak_beta-spacecraft-17.5-16759804.img` | 24,388,820 B | bootloader，FBPK v2 格式 |
| `radio-kodiak_beta-a900a-qpr_260921-260923-m-16421473.img` | 194,191,500 B | 基带（radio/modem），FBPK v1 格式 |
| `pi01.ec.bin` / `gb00.ec.bin` | 各 1,048,576 B | EC（嵌入式控制器）固件 |

### 1.2 内层 image zip（47 个文件，未解压 15,600,055,597 B）

**启动链（重点）**

| 文件 | 大小 | 用途 |
|---|---|---|
| `boot.img` | 67,108,864 B（64 MiB 分区大小） | 内核（GKI），无 ramdisk |
| `init_boot.img` | 8,388,608 B | 通用 ramdisk，含 **first-stage init** |
| `vendor_boot.img` | 67,108,864 B | vendor ramdisk（2 个 fragment）+ bootconfig |
| `vendor_kernel_boot.img` | 67,108,864 B | vendor ramdisk（内核模块）+ DTB |
| `dtbo.img` | 16,777,216 B | DTBO，55 个条目 |
| `kernel_16k` | 19,701,426 B | 16K 页大小内核（LZ4 压缩） |
| `ramdisk_16k.img` | 5,532,073 B | 16K ramdisk fragment（与 vendor_boot fragment 1 的 MD5 完全相同） |
| `pvmfw.img` | 1,048,576 B | pVM 固件（Android boot image 格式） |
| `vbmeta.img` / `vbmeta_system.img` / `vbmeta_vendor.img` | 20,480 / 12,288 / 8,192 B | AVB 验证元数据 |

**系统分区（全部 EROFS，见 §5）**

| 文件 | 大小 |
|---|---|
| `system.img` | 1,416,597,504 B |
| `product.img` | 5,069,811,712 B |
| `vendor.img` | 1,111,818,240 B |
| `system_ext.img` | 487,591,936 B |
| `system_dlkm.img` | 13,193,216 B |
| `vendor_dlkm.img` | 67,182,592 B |
| `system_other.img` | 138,997,760 B |
| `userdata_exp.ai.img` | 6,829,389,372 B（Android sparse 格式） |
| `super_empty.img` | 5,184 B（super 分区元数据占位） |

**Bootloader 固件（fastboot-info 的 hash 描述符覆盖的部分）**

`abl.img`、`bl31.img`、`cap.img`、`cpm.img`、`dbc.img`、`dbl.img`、`dram_init_0~11.img`、`dram_phy.img`、`gc.img`、`gdmc.img`、`gsa_bl1.img`、`gsa_fw.img`、`modem.img`、`tzsw.img`。**没有 super.img**——super 分区由 fastboot 用 `super_empty.img` + 各分区镜像现场组装（见 §5）。

---

## 2. 版本信息

### 2.1 android-info.txt（全文）

```text
require version-baseband=a900a-QPR_260921-260923-M-16421473
require board=spacecraft|kodiak|kodiak_2|grizzly|grizzly_2|cubs|yogi
require version-bootloader=spacecraft-17.5-16759804
```

- 要求基带版本：`a900a-QPR_260921-260923-M-16421473`
- 要求 bootloader 版本：`spacecraft-17.5-16759804`
- 适用主板：spacecraft（代号，Tensor G6 平台）、kodiak（Pixel 11 Pro XL）、kodiak_2、grizzly（Pixel 11 Pro）、grizzly_2、cubs（Pixel 11）、yogi

### 2.2 构建指纹（init_boot ramdisk 内 `system/etc/ramdisk/build.prop`）

```text
ro.bootimage.build.fingerprint=google/kodiak_beta/kodiak:17/DP11.261030.003/16847878:user/release-keys
ro.bootimage.build.id=DP11.261030.003
ro.bootimage.build.version.incremental=16847878
ro.bootimage.build.version.release=17
ro.bootimage.build.version.sdk=37
ro.bootimage.build.date=Thu Nov  5 21:13:19 UTC 2026
ro.product.bootimage.model=Pixel 11 Pro XL
```

- 安全补丁级别：2026-10-05（工厂镜像页标注；vbmeta 的 rollback_index 经换算正是 2026-10-05 00:00 UTC 的 Unix 时间戳 1791158400，互相印证）

### 2.3 内核版本（已验证）

| 内核 | 版本字符串 |
|---|---|
| `boot.img`（4K） | `Linux version 6.12.81-android16-6-g20ed81c930ec-ab16434324-4k` |
| `kernel_16k`（16K） | `Linux version 6.12.81-android16-6-g20ed81c930ec-ab16434324` |

两者同源（同一提交 `g20ed81c930ec`），clang 19.0.1 构建，构建时间 2026-09-24。内核以 LZ4（legacy frame）压缩存放。

---

## 3. boot.img / vendor_boot.img 结构

### 3.1 boot.img —— Android Boot Image v4

- header_version 4，header_size 1584，kernel 20,450,725 B，**ramdisk 0 B**，cmdline 为空，os_version 字段为 0
- 内核在偏移 4096 处，以 LZ4 压缩的 ARM64 Image 存放
- 结论：标准的 GKI 布局——boot.img 只有内核，没有 ramdisk

### 3.2 init_boot.img —— Android Boot Image v4

- header_version 4，header_size 1584，kernel 0 B，ramdisk 3,074,505 B
- os_version 字段解出 Android 17、安全补丁 2026-10
- ramdisk 在偏移 4096 处，LZ4 压缩的 cpio（解压后 5,868,032 B）
- 结论：Android 13+ 的标准布局——通用 ramdisk（含 first-stage init）独立放在 init_boot.img

### 3.3 vendor_boot.img —— Vendor Boot Image v4

- header_version 4，page_size 2048，header_size 2128
- vendor ramdisk 共 31,166,550 B，分 2 个 fragment（表在 ramdisk 数据之后）：
  - fragment 0：25,634,477 B，platform vendor ramdisk（4K）
  - fragment 1：5,532,073 B，名字为 `16K`，与内层包里的 `ramdisk_16k.img` 的 MD5 完全相同（`df8b3661…`）
- DTB 大小 0（DTB 不在这里，在 vendor_kernel_boot.img）
- bootconfig 84 B，内容为：
  ```text
  androidboot.load_modules_parallel=performance
  androidboot.boot_devices=3c2d0000.ufs
  ```
- cmdline（vendor 部分，Pixel 专属参数较多，节选）：
  ```text
  workqueue.power_efficient=1 binder.impl=rust kasan=off
  ufs.async_probe=1 swiotlb=noforce disable_dma32=on
  arm64.no32bit_el0 bootconfig
  ```

### 3.4 vendor_kernel_boot.img —— Vendor Boot Image v4

- header_version 4，page_size 2048
- vendor ramdisk 5,598,837 B：`lib/modules/` 下 200 个 `.ko`（Pixel/Tensor 专属驱动：aoc_*、google-battery、google-bms 等）+ modules.alias/dep/load/softdep
- DTB 1,075,424 B，位于 ramdisk 之后（首个 FDT 魔数在页边界处；region 内疑似多个 DTB 拼接，未逐个解析）
- bootconfig 0 B，cmdline 为空

### 3.5 其他启动相关镜像

- `dtbo.img`：DTBO 魔数 `0xD7B7AB1E`，55 个条目，页大小 4096，总大小 11,435,765 B
- `pvmfw.img`：Android boot image 格式，pVM（受保护虚拟机）固件
- `vbmeta.img` 三件套：AVB 1.0，算法字段 7，auth block 3328 B，签名数据 3309 B（实测），rollback_index 1791158400（= 2026-10-05 UTC），flags 0，avbtool 1.4.0。描述符（已逐个走查）：4 个 chain partition（boot、init_boot、vbmeta_system、vbmeta_vendor）、5 个 property（各分区 fingerprint，均为 `google/kodiak_beta/kodiak:17/DP11.261030.003/16847878:user/release-keys`）、约 20 个 sha256 hash 描述符（abl、bl31、cap、cpm、dbc、dbl、dram_init_0~11、dram_phy、dtbo、gc、gdmc、gsa_bl1、gsa_fw、tzsw、vendor_boot、vendor_kernel_boot）、1 个 hash-tree 描述符（vendor_dlkm）
  - 注：签名数据 3309 B 与常规 RSA 签名长度（512 B）不符，算法字段的枚举含义未完全确认，此处只报实测值，不下结论

---

## 4. first-stage init（重点）

### 4.1 结论（已验证）

**`init_boot.img` 的 ramdisk 里有一个真正的 first-stage init**：

```text
/init  ELF 64-bit LSB executable, ARM aarch64, 静态链接 (statically linked),
        stripped, BuildID[md5/uuid]=118fa24ab9f2f524c58b6c517b27092b, 3,005,824 B
```

证据（字符串级核实）：

- 含 `init first stage started!`、`system/core/init/first_stage_init.cpp`、`first_stage_device_initializer.cpp`、`first_stage_mount.cpp`
- 含 `FirstStageMount`、`FirstStageDeviceInitializer`、`Init encountered errors starting first stage, aborting`
- 含 `/first_stage_ramdisk/fstab.`、`/first_stage_ramdisk/system/bin/snapuserd` 等 first-stage 路径
- 含 `/system/bin/init`（first-stage 完成后 exec 的 second-stage 入口）
- **不含** `Second-stage init requires an argument to main()`（GSI init 的 FATAL 串）
- **不含** `init second stage started!`（那句话在 second-stage 二进制里）

即：这是一个**只做 first-stage 的 init**，无参数调用时走 FirstStageMain；做完挂载、SELinux、属性区初始化后，再 exec `/system/bin/init second_stage`。这正是 GSI init 所没有的那一半。

### 4.2 与 GSI init 的对照（已验证）

| | GSI 的 `/system/bin/init`（M1 实验用的那个） | 工厂镜像 `init_boot.img` 的 `/init` |
|---|---|---|
| 大小 | 2,653,128 B | 3,005,824 B |
| BuildID | `972f69b5…` | `118fa24a…` |
| 链接方式 | 动态链接（需 linker + 29 个库） | **静态链接（零依赖）** |
| first-stage 入口 | **没有**（main() 无参数即 FATAL abort） | **有**（FirstStageMain） |
| second-stage | 是（纯 second-stage） | 不是（做完 first-stage 后 exec `/system/bin/init`） |

M1 实验的 E2 结论（GSI init 是 second-stage-only）在这里得到了反向印证：Google 的真机启动链就是两个 init 分工，first-stage 在 init_boot.img，second-stage 在 system.img。

### 4.3 init_boot ramdisk 内容清单

```text
/init                                  ← 静态 first-stage init（重点）
/first_stage_ramdisk/                   ← 空目录（运行时由 vendor ramdisk 的同名内容 bind 挂载进来）
/second_stage_resources/                ← 空目录（运行时挂 tmpfs，供 second-stage 用）
/system/bin/modprobe、snapuserd_ramdisk、toolbox_ramdisk
/system/bin/getevent、getprop、setprop、start、stop  → toolbox_ramdisk 的符号链接
/system/bin/snapuserd → snapuserd_ramdisk
/system/etc/init/snapuserd.rc
/system/etc/ramdisk/build.prop
/dev、/metadata、/mnt、/proc、/sys（空挂载点）
/debug_ramdisk（空目录）
```

**没有 fstab**——fstab 不在 init_boot ramdisk，而在 vendor_boot fragment 0 的 `first_stage_ramdisk/system/etc/` 里：

- `fstab.malibu`（真机用）：system 等分区为 erofs（另有 ext4  fallback 行，注释写明“GSI 是 ext4 时用这行”），设备路径为 `/dev/block/platform/3c2d0000.ufs/by-name/*`，/metadata 与 /data 为 f2fs（含 inlinecrypt、fileencryption 参数）
- `fstab.malibu-fips`（FIPS 变体）
- `fstab.gem5`（模拟器用）：`/dev/block/by-name/*` 的通用写法，system 等 erofs，/data f2fs——这是三个 fstab 里最接近“通用块设备”的一个，但它是给 Gem5 模拟器的，不是给 QEMU virt 的

vendor_boot fragment 0 里与 first-stage 相关的其他内容：

- `first_stage_ramdisk/system/bin/`：linker64、linker_asan64、linker_hwasan64、modprobe、resize2fs、tune2fs
- `first_stage_ramdisk/system/lib64/`：ld-android.so、libbase.so、libc++.so、libc.so
- `first_stage_ramdisk/lib/firmware/`：usb_phy_fw.bin、usb_phy_fw_2.27.0.bin
- `first_stage_ramdisk/avb/`：q/r/s-developer-gsi.avbpubkey
- 预编译 sepolicy：`/sepolicy` 1,245,248 B，policy v30（M1 自编的 sepolicy-main 是 v33、522,021 B——版本不同是因为 secilc 版本不同，不代表新旧）
- plat/vendor/product/system_ext 的 file/property/service contexts，prop.default

### 4.4 这个 first-stage init 能不能直接用在 M1 / QEMU virt 上

**先说能用来做什么（已验证的部分）：**

1. **参考价值是确定的**：它是一个真实存在的、静态链接的 first-stage init 实现样本。可以对照它的字符串与行为，确认 first-stage init 应该做什么（读 fstab、FirstStageMount、加载 sepolicy、初始化属性区、挂载 second_stage_resources、exec second-stage init），以及 GSI init 到底缺了哪一半。
2. **候选替代来源**：它是静态链接的，没有动态库依赖，理论上作为 PID 1 跑起来不需要 GSI 那套 linker + 29 个库。这正是 M1 一直想要的“真正的 first-stage init”的一种现成形态。

**再说为什么不能拿来即用（已验证 + 推测）：**

1. 它是给 Pixel 11 Pro XL（Tensor G6、UFS）编的：fstab 指向 `3c2d0000.ufs`，bootconfig 指定 `boot_devices=3c2d0000.ufs`，要加载 Pixel 专属内核模块。QEMU virt 上没有这些设备，FirstStageMount 很可能直接失败（推测，需实际启动验证）。
2. 它依赖 vendor_boot 的 first_stage_ramdisk 内容（fstab、linker、基础库、固件）和 DTB；只拷一个 `/init` 不够，要整套 vendor 侧配合。
3. 内核也不匹配：Pixel 内核（6.12.81-android16-6）经 strings 检测，**未发现 virtio-blk / virtio-gpu / virtio-net / virtio-console / virtio-scsi 驱动**（只见到 virtio_net_hdr、vsock 相关字符串与一个 `trusty-virtio.ko` 模块——后者是 Trusty TEE 传输，不是通用 virtio 设备）。Pixel 内核是设备内核，不是 QEMU virt 内核。
4. 版权/分发：这是 Google 发布的专有固件的一部分，直接把它嵌进 PocketVM 再分发，与“拿来研究、对照”不是一回事，需另行考虑（此处不做法律判断，只提醒）。

**比较现实的三种用法（按推荐度排序）：**

1. **对照参考**：用它确认 first-stage init 的行为与接口，指导 M1 自研/自编 first-stage init，或验证 M1 wrapper 的做法是否走偏。
2. **实验性试跑**：把它 + 一份按 virt 改写的 fstab + virt DTB + 通用 vendor ramdisk 拼起来，在 PC QEMU 上试一次，看 FirstStageMount 在哪一步停下。这是一次性实验，不保证成功。
3. **继续 AOSP 源码路线**：从 AOSP 源码编一个真正面向 virt 的 first-stage init——最干净，但工作量最大（M1 报告里已有此评估）。

---

## 5. super.img / 系统镜像

- **没有 super.img**。`fastboot-info.txt` 的流程是：`flash boot / init_boot / dtbo / vendor_kernel_boot / pvmfw / vendor_boot` → `flash vbmeta` → `reboot fastboot` → **`update-super`**（用 `super_empty.img` 重建空的 super 分区元数据）→ 再逐个 `flash system / system_dlkm / system_ext / product / vendor / vendor_dlkm`，`system_other` 刷到另一 slot。
- `super_empty.img`（5,184 B）：super 分区元数据占位文件，内含动态分区名表（system_a/b、system_dlkm_a/b、system_ext_a/b、product_a/b 等）；文件头魔数未完全解出，此处不展开。
- 系统分区镜像格式（只读文件头判定，未解压内容）：
  - `system.img`、`vendor.img`、`product.img`、`system_ext.img`、`system_dlkm.img`、`vendor_dlkm.img`、`system_other.img`：**全部是 EROFS**（魔数 `0xE0F5E1E2`）
  - `userdata_exp.ai.img`（6.83 GB）：**Android sparse 格式**；名字与 fstab.malibu 里 /data 的 `device=exp:...userdata_exp.*` 条目对应，是 userdata 的扩展分区镜像，具体用途未深入验证
- 未做的事：没有 simg2img/lpunpack 等工具（本机没有，且任务要求不联网安装），所以**没有解析 super 分区表、没有挂载/解开任何系统分区**；EROFS 镜像的内容（包括 `/system/bin/init` 这个 second-stage 二进制）**没有提取验证**。

---

## 6. 与用户现有工作的关系

### 6.1 给真机 Pixel 11 Pro XL 刷机（已验证流程，未实际刷机）

- **能用，这是它本来的用途**。前提：已解锁 bootloader；`fastboot -w update` 会清空 userdata（`-w` 就是 wipe）。
- 流程（`flash-all.sh` 全文已核对）：
  1. 要求 fastboot ≥ 33.0.1
  2. `fastboot flash bootloader bootloader-...img` → `reboot-bootloader` → 等 5 秒
  3. `fastboot flash radio radio-...img` → `reboot-bootloader` → 等 5 秒
  4. `fastboot -w update image-kodiak_beta-dp11.261030.003.zip`（刷入全部 47 个镜像并清空数据）
- `flash-base.sh` 只做 2–3 步（bootloader + radio），不刷系统。
- 适用设备：Pixel 11 Pro XL（kodiak）；同包也覆盖 Pixel 11 系列其他机型（cubs、grizzly 等，见 android-info.txt 的 board 列表），但不同机型应使用各自的工厂镜像，不要混用。
- 注意：这是 Beta 版（QPR3 Beta，DP11.261030.003），不是正式版；刷机有风险，操作前备份。

### 6.2 QEMU virt 启动（已验证：不能直接启动）

- **不能直接启动**。原因（已验证）：
  - 内核是 Pixel 设备内核，无 virtio-blk/gpu/net/console/scsi 驱动（strings 检测）
  - DTB 是 Tensor G6 的，不是 virt 的
  - fstab 指向 UFS 设备路径，QEMU virt 上不存在
  - 分区是 EROFS + AVB 链式验证，没有组装好的 super.img
- **能借鉴的部分**：
  - first-stage init 二进制本身（见 §4.4）
  - `fstab.gem5` 的写法（通用 `/dev/block/by-name` 风格）可作 virt 版 fstab 的模板
  - vendor_boot fragment 0 的 first_stage_ramdisk 结构（linker、基础库、fstab 位置）可作组装参考
  - 预编译 sepolicy 的格式与版本（v30）可作对照

### 6.3 PocketVM Android Guest 的 M1 实验

- **现有的 GKI 6.18 + GSI 组合不需要这个镜像也能继续**：M1 第一步已经用 `init second_stage` 绕过了 first-stage 缺失，到达了 second stage，当前终点是 apexd。
- **这个镜像的最大价值是 first-stage init**：M1 缺的一直是一个“真正的 first-stage init”，而 init_boot.img 里恰好有一个已验证的静态版本。它提供了三种可能（见 §4.4），其中“对照参考”是零风险的，“实验性试跑”值得做一次，“AOSP 源码重编”仍是最干净的长期路线。
- **不建议做的事**：不要把 Pixel 内核、Pixel DTB、Pixel vendor 镜像直接搬进 M1 实验——它们是设备专属的，与 virt 不兼容；不要把工厂镜像的二进制不经评估就嵌进 PocketVM 分发。

---

## 7. 注意事项与未完成项

**结论可信度说明**

- 已验证：全部文件清单与大小、版本信息、boot/vendor_boot/init_boot 头结构、ramdisk 解包与内容、first-stage init 的静态链接属性与关键字符串、EROFS/sparse 格式判定、vbmeta 描述符走查、SHA-256（分析后重新核对，仍为 `2cc3cf49…92add`，zip 未被修改）。
- 推测：first-stage init 在 QEMU virt 上的表现（未实际启动）、userdata_exp.ai.img 的具体用途、super_empty.img 文件头魔数的完整解读、vbmeta 签名数据 3309 B 的具体构成（算法字段为 7，按常规枚举不对应 3309 B 签名，此处只报实测值）。
- 未验证：vendor_kernel_boot 的 ramdisk 表位置（多处候选偏移均为零，未定位，不影响结论）；DTB 区域内多个 FDT 的逐个解析；Pixel 内核配置（无 .config，未检查 IKCONFIG）。

**未做的事情**

- 未实际刷机、未启动任何镜像（包括未在 QEMU 里试跑 first-stage init）
- 未解压/挂载 super 及各系统分区镜像（无现成工具且任务要求不联网安装；EROFS 内容未展开，`/system/bin/init` 未提取）
- 未修改 zip；分析产生的中间文件都在 `~/workspace/pixel-images/analysis/` 内（内层 image zip 的一份拷贝、提取的 boot 镜像与解包的 ramdisk）

---

## 附录：分析所用文件位置

- 工厂镜像：`~/workspace/pixel-images/kodiak_beta-dp11.261030.003-factory-2cc3cf49.zip`（15,375,459,935 B，SHA-256 `2cc3cf491c4541630fe260646e31f88d62a868a9ce54b1ea6ef529fbd9392add`）
- 分析目录：`~/workspace/pixel-images/analysis/`
  - `image-kodiak_beta-dp11.261030.003.zip`（内层包拷贝）
  - `boot.img` / `vendor_boot.img` / `vendor_kernel_boot.img` / `init_boot.img` / `dtbo.img` / `kernel_16k` / `ramdisk_16k.img` / `vbmeta*.img` / `pvmfw.img` / `super_empty.img`
  - `android-info.txt` / `fastboot-info.txt` / `flash-all.sh` / `flash-all.bat` / `flash-base.sh`
  - `ramdisk-init_boot/`（init_boot 解包）、`ramdisk-vendor_boot/`（vendor_boot fragment 0 解包）、`ramdisk-vendor_boot-16k/`（fragment 1 解包）、`ramdisk-vendor_kernel_boot/`（模块 ramdisk 解包）
  - `init_boot.cpio` / `vendor_boot_frag0.cpio` / `vendor_boot_frag1.cpio` / `vkb.cpio`（解压后的 cpio）
  - `parse_boot.py`（头解析脚本）
