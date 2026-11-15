# B1：Cuttlefish Android 17 Vendor 提取与审计 — 2026-11-15

> 任务：从官方 CI 构建 16644040 提取 Cuttlefish ARM64 vendor，核查 HAL / VINTF / SELinux /
> 图形栈 / 启动依赖，判断它能否作为 Pixel 11 Pro XL System 的 QEMU 虚拟硬件 Vendor 基础。
> 本任务不启动 Guest、不修改 M6 基线、不修改 Pixel System、不构建新 Vendor、不关闭 SELinux。

## 0. 结论摘要

1. **Cuttlefish Vendor 不能直接承载 Pixel System。** 核心 HAL 版本能对上，但 Pixel 专有 HAL
   （radio、citadel、aoc、edgetpu 等）缺失，mapper 实例名与分区/SELinux 形态也不同。
2. **图形栈是可复用的关键资产。** Vendor 内同时提供 `vulkan.pastel.so`（SwiftShader，28.7 MB，
   仅依赖 bionic 基础库）与 `vulkan.ranchu.so`（gfxstream），以及 minigbm allocator/mapper 和
   drm_hwcomposer V5。
3. **可以以它为参照裁剪出 QEMU 通用 Vendor**，但建议按组件重新组装（minigbm + drm_hwc +
   pastel + 通用安全 HAL），而不是整包搬用 Cuttlefish vendor。

## 1. 产物与提取（P0 / P1）

### 1.1 来源

- 分支：`aosp-android-latest-release`（CI 上不存在 `aosp-android17-release`）
- 目标：`aosp_cf_arm64_only_phone-userdebug`
- 构建：**16644040**
- 构建页：`https://ci.android.com/builds/submitted/16644040/aosp_cf_arm64_only_phone-userdebug/latest/`
- 镜像包：`aosp_cf_arm64_only_phone-img-16644040.zip`，1,101,174,958 B

包内条目（Range 读取中央目录，实测）：

| 条目 | 大小 |
| --- | --- |
| `boot.img` | 67,108,864 |
| `init_boot.img` | 8,388,608 |
| `vendor_boot.img` | 67,108,864 |
| `super.img` | 1,746,554,644 |
| `vbmeta*.img` | 4–12 KB |
| `userdata.img` | 2,249,012 |

没有独立 `vendor.img`，vendor 在 `super.img` 的动态分区里。

### 1.2 提取过程

1. 从构建产物取 `otatools.zip`（531,408,471 B），解出 `lpunpack` / `simg2img`
   - `lpunpack` SHA-256：`9df5a76368804f062392ca5219dca58408a2d6a5fd648b5640d0ba7535cfc56f`
   - `simg2img` SHA-256：`ad819f36bea5216248335c3670d2d67c78d9a4c587e26d7b1300c2c3eb2bf97a`
2. Range 流式抽取 `super.img`，长度 1,746,554,644 B，ZIP CRC32 `0x50466840` **校验通过**（实测）。
3. `super.img` 是 Android sparse 格式；`simg2img` 转 raw 后为 8,589,934,592 B（稀疏文件）。
4. `lpunpack` 提取分区（slot `_a`）：

| 分区 | 大小 (B) | SHA-256 |
| --- | --- | --- |
| `vendor_a` | 291,229,696 | `1e5188d7724541ec3f859badb2a2e7689e2f836bf0144e776bdec0e1217fbd5c` |
| `system_a` | 959,066,112 | `a695137d0f3d2a4dafb49947c88224db4fae8b0c0d9131a1f2aaa7d662e4a7ad` |
| `system_ext_a` | 258,179,072 | `ab020dbb77d5af9ce2f7c655c7ab90c2f76bcb9b40c603e32e16ab7a01028744` |
| `product_a` | 232,157,184 | `af47f181ce39864da77684e4fe5954d12830120fc0d1a61e1458ee040483d95e` |

`vendor_a.img` 为 EROFS（LZ4_0PADDING），只读挂载后 289 个文件。

提取用的临时 `super.img` / `super_raw.img` 已删除；分区镜像保留，哈希如上。

## 2. Vendor 结构（P2）

### 2.1 目录

```text
/vendor
├── apex/           47 个 APEX（HAL、graphics、vulkan、wifi、camera 等）
├── bin/hw/         少量直接安装的 HAL
├── etc/
│   ├── init/
│   ├── selinux/
│   ├── vintf/
│   ├── fstab.cf.ext4.hctr2 / fstab.cf.f2fs.hctr2 / .cts 变体
│   └── ueventd.rc
├── lib64/hw/       gralloc.default.so、mapper.minigbm.so、sensors 等
├── lib64/egl/      libEGL_emulation、libGLESv2_emulation（gfxstream）
└── build.prop
```

`build.prop` 关键项（实测）：

```text
ro.vendor.build.version.sdk=37
ro.vendor.build.version.sdk_full=37.0
ro.hardware.virtual_device=1
ro.vendor.hwcomposer.pmem=/dev/block/pmem1
```

### 2.2 VINTF 组织

`/vendor/etc/vintf/manifest.xml` 只有 sepolicy 版本，**没有 HAL 条目**：

```xml
<manifest version="9.0" type="device" target-level="202604">
    <sepolicy><version>202604</version></sepolicy>
</manifest>
```

`/vendor/etc/vintf/manifest/` 只有 5 个片段（sensors、face、fingerprint、mapper.minigbm、
motioncontext）。**其余 HAL 声明在 APEX 的 `/etc/vintf/*.xml` 里**，安装时合并。

## 3. HAL 实例表

| HAL | 实例 | 版本 | 来源 | 依赖宿主 |
| --- | --- | --- | --- | --- |
| `android.hardware.boot` | `IBootControl/default` | AIDL v1 | `com.android.hardware.boot.apex` | 否 |
| `android.hardware.gatekeeper` | `IGatekeeper/default` | AIDL v1 | `...gatekeeper.nonsecure.apex` | 否 |
| `android.hardware.security.sharedsecret` | `ISharedSecret/gatekeeper` | AIDL v1 | 同上 | 否 |
| `android.hardware.security.keymint` | `IKeyMintDevice/default` | **5** | `...keymint.rust_nonsecure.apex` | 否 |
| `android.hardware.security.keymint` | `IRemotelyProvisionedComponent/default` | 3 | 同上 | 否 |
| `android.hardware.security.secureclock` | `ISecureClock/default` | v1 | 同上 | 否 |
| `android.hardware.security.sharedsecret` | `ISharedSecret/default` | v1 | 同上 | 否 |
| `android.hardware.power` | `IPower/default` | **7** | `com.android.hardware.power.apex` | 否 |
| `android.hardware.power.stats` | `IPowerStats/default` | 2 | 同上 | 否 |
| `android.hardware.health` | `IHealth/default` | 5 | `com.google.cf.health.apex` | 否 |
| `android.hardware.graphics.composer3` | `IComposer/default` | **5** | `...composer.drm_hwcomposer.apex` 或 `.ranchu.apex` | 否 |
| `android.hardware.graphics.allocator` | `IAllocator/default` | **3** | `com.google.cf.gralloc.apex`（minigbm） | 否 |
| `mapper` | `@5.0/minigbm` | 5.0 | vendor manifest 片段 | 否 |
| `android.hardware.sensors` | `ISensors/default` | 3 | `sensors-multihal.xml` | 否 |
| `android.hardware.biometrics.face` | `IFace/default` | 5 | face APEX | 否 |
| `android.hardware.biometrics.fingerprint` | `IFingerprint/default` | 5 | fingerprint APEX | 否 |
| `android.hardware.motioncontext` | `IMotionContext/default` | v1 | 片段 | 否 |
| audio / camera / wifi / usb / uwb / thermal / vibrator / nfc / drm 等 | 各自 APEX | — | `/vendor/apex` | 部分是模拟实现 |

Vendor 中**没有**：radio（多 slot）、citadel/aoc 相关、edgetpu、`see.hwcrypto`、
`security.timestamp`。

## 4. 图形栈专项（P3）

### 4.1 Vulkan

`com.google.cf.vulkan.apex` 的 payload（37,253,120 B）内含：

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `lib64/hw/vulkan.pastel.so` | 28,763,400 | **SwiftShader Vulkan ICD** |
| `lib64/hw/vulkan.ranchu.so` | 5,218,752 | gfxstream Vulkan |

`vulkan.pastel.so` 的 ELF 依赖（实测，`readelf -d`）只有：
`libnativewindow.so`、`libhardware.so`、`libsync.so`、`liblog.so`、`libc++.so`、`libc.so`、
`libm.so`、`libdl.so`——**不依赖宿主 GL/Vulkan**，是纯 CPU 实现。

未发现 `vk_swiftshader_icd.json`；Android 通过 `ro.hardware.vulkan=pastel` 走
`/vendor/lib64/hw/vulkan.<name>.so` 命名加载，不需要 JSON（与 M5 的 E1c 结论一致）。

### 4.2 EGL

`/vendor/lib64/egl/libEGL_emulation.so` 依赖 `libOpenglCodecCommon` / `libOpenglSystemCommon`
（gfxstream 编码器）——**这是宿主渲染路径，手机上不可用**。

guest 内渲染应走：GSI 自带 ANGLE（`libEGL_angle.so`）+ `vulkan.pastel.so`，
即 M4/M5 的路线 A。Cuttlefish 的 `init_graphics.vendor.rc` 已实现
`ro.hardware.egl/gralloc/hwcomposer/vulkan` 从 bootconfig 拷属性，正是 M5 E1c 手工补的那座桥。

### 4.3 Gralloc / HWC

- `com.google.cf.gralloc.apex`：`android.hardware.graphics.allocator-service.minigbm`、
  `mapper.minigbm.so`、`libminigbm_gralloc.so`、`libdmabufheap.so`
- `com.android.hardware.graphics.composer.drm_hwcomposer.apex`：
  `android.hardware.composer.hwc3-service.drm`，链接 `libdrm`、`composer3-V5-ndk`
- 二进制内含 `generic`、`vendor.hwc.backend_override` 等字符串；源码级结论（M4）是
  `GetBackendByName` 对未知驱动回落 GenericBackend，可覆盖 virtio_gpu 单 scanout
- minigbm 的 virtio_gpu 后端为源码级确认（M4），本机二进制字符串未直接体现

**结论：图形栈具备「纯 guest 软件渲染」路径（pastel + ANGLE + minigbm + drm_hwc），
gfxstream 是默认但不是唯一。**

## 5. SELinux / init / fstab

### 5.1 SELinux（实测）

`vendor_file_contexts` 关键行：

```text
/dev/block/by-name/misc   u:object_r:misc_block_device:s0
/dev/dri/card0            u:object_r:graphics_device:s0
/dev/dri/renderD128       u:object_r:gpu_device:s0
/sys/.../virtio[0-9]+/(block|ndbus[0-9]+)  u:object_r:sysfs_devices_block:s0
```

即 Cuttlefish 的策略是按 **by-name + virtio sysfs** 写的，这正是 M6 里 Pixel 策略
（`hal_bootctl_default` → `vd_device` 拒绝）缺失的部分。

### 5.2 fstab（实测）

`/vendor/etc/fstab.cf.ext4.hctr2` 使用：

```text
system      /system     ... wait,logical,first_stage_mount,slotselect,avb=vbmeta_system
vendor      /vendor     ... wait,logical,first_stage_mount,slotselect,avb=vbmeta
/dev/block/by-name/misc /misc emmc defaults defaults
shared      /mnt/vendor/shared virtiofs nofail
```

**动态分区 + by-name + AVB + slotselect + userdata 文件加密**。与 M6 的
「独立 virtio-blk + 直连 vdX」布局不同，复用其 vendor 需要适配分区形态或改 fstab。

### 5.3 init

`/vendor/etc/init/` 只有少量服务（keymint trusty、sensors、face、fingerprint、
motioncontext、seriallogging、`init.cutf_cvm.rc`）；其余服务声明在 APEX 的 `.rc` 里。
`init_graphics.vendor.rc` 的 `on early-init` 把 `ro.boot.hardware.*` 拷到
`ro.hardware.*`，与 M5 E1c 的桥一致。

## 6. Pixel 兼容性审计（P4）

### 6.1 Pixel system 的 VINTF 要求（实测）

从 Pixel 11 Pro XL 工厂镜像 `system.img`（1,416,597,504 B，EROFS）读取
`/system/etc/vintf/compatibility_matrix.202604.xml`：

| HAL | Pixel 要求 | Cuttlefish 提供 | 判定 |
| --- | --- | --- | --- |
| `graphics.allocator` | 1–3 | V3 | ✅ |
| `graphics.composer3` | 4–5 | V5 | ✅ |
| `health` | 3–5 | V5 | ✅ |
| `power` | 5–7 | V7 | ✅ |
| `power.stats` | 2 | V2 | ✅ |
| `mapper` | 5.0 | 5.0（实例 `minigbm`） | ⚠️ 实例名不同 |
| `boot` | 任意 | AIDL v1 | ✅ |
| `gatekeeper` | 任意 | AIDL v1 | ✅ |
| `keymint` | 矩阵未列（Pixel vendor 提供 V5） | V5 | ✅ |
| `rebootescrow` | 有要求 | APEX 存在 | ✅ |
| `security.secretkeeper` | 1–2 | APEX 存在 | ✅ |
| `security.see.hwcrypto` | 有要求 | **无** | ❌ |
| `security.timestamp` | 有要求 | **无** | ❌ |

Pixel system 的 `ro.build.version.sdk_full=37.2`，Cuttlefish vendor 是 `37.0`；VINTF
target-level 都是 202604（实测）。

### 6.2 Pixel vendor 的 HAL 集（实测）

Pixel vendor manifest + 片段共有 35 个 HAL，包含大量 Cuttlefish 没有的：

- `android.hardware.radio.*`（config/data/ims/messaging/modem/network/sap/sim/voice）
- `vendor.mediatek.hardware.mtkradioex.*`
- `com.google.input.*`、`vendor.google.bluetooth_ext`
- `com.google.edgetpu.tachyon`、citadel 相关 keymint/weaver/oemlock/authsecret
- Pixel power/thermal/health（`pixel` / `malibu` 实例）

Cuttlefish 对应项：power/health/keymint/gatekeeper/boot 有通用实现；radio、citadel、
edgetpu、input 扩展没有。

### 6.3 分类

| 类别 | 项目 |
| --- | --- |
| 可直接复用 | composer3 V5、allocator V3、mapper 5.0、power V7、health V5、gatekeeper、boot、rebootescrow、secretkeeper、Vulkan SwiftShader ICD |
| 需要少量适配 | mapper 实例名（`minigbm` vs `pixel`）、`ro.hardware.gralloc/egl/vulkan` 属性、SELinux 设备标签（by-name vs vdX）、fstab 分区形态（dynamic/AVB vs 独立盘）、sdk_full 37.2/37.0 |
| 需要重新构建/移植 | radio 全家族、citadel/aoc/edgetpu 相关 HAL、`see.hwcrypto`、`security.timestamp`、Pixel 专有 audio/camera/sensors |
| 不兼容 | 把 Cuttlefish vendor 原样当 Pixel vendor 使用（缺 HAL、分区/AVB/SELinux 假设不同） |

## 7. 三个问题的回答

**1）Cuttlefish Vendor 能否直接承载 Pixel System？**
不能。VINTF 核心版本能对上，但 Pixel 专有 HAL 缺失，mapper 实例名、SELinux 标签、
分区/AVB 形态也不一致。直接替换会在 boot 早期或 graphics/telephony 初始化阶段失败。

**2）如果不能，哪些 HAL 和配置必须替换？**
- HAL：radio 全家族、citadel/aoc/edgetpu、`see.hwcrypto`、`security.timestamp`、
  Pixel 专有 power/thermal/health 实例
- 配置：mapper 实例名与 gralloc 属性、fstab（dynamic/AVB/by-name）、SELinux 设备标签、
  AVB/slot 元数据、sdk_full 37.2 与 37.0 的差异

**3）能否以 Cuttlefish Vendor 为基础裁剪出 M4 QEMU virt 通用 Vendor？**
可以，但**建议按组件重组，而不是整包搬用**：
- 保留：minigbm allocator/mapper、drm_hwcomposer V5、`vulkan.pastel.so`、
  keymint nonsecure、gatekeeper、boot、power、health、rebootescrow、secretkeeper
- 去掉：gfxstream（`libEGL_emulation`）、ranchu composer、Cuttlefish 专有服务
  （`cutf_cvm`、camera/v4l2 等）
- 重写：fstab/SELinux 适配我们的分区布局；属性走 bootconfig
- 不要为了兼容关闭 SELinux

## 8. 建议

1. **停止**把 Cuttlefish vendor 原样用于 Pixel System 的尝试。
2. 下一阶段做「QEMU 最小 Vendor 组装」：以本清单为输入，按 M4/M5 路线 A
   （VirtIO GPU 2D + ANGLE + SwiftShader pastel + minigbm + drm_hwc）组装，
   配 M4 内核或 Cuttlefish 6.12 内核。
3. Pixel System 留到通用 Vendor 能带 GSI 到 `boot_completed` 之后，再评估 HAL 补齐。

## 9. 未核实

- `vulkan.pastel.so` 在本链路 QEMU virt 上的实际初始化（仅 ELF 依赖与命名推断）
- minigbm virtio_gpu 后端的运行时行为（源码级确认，未跑）
- drm_hwcomposer GenericBackend 对单 scanout 2D 的运行时表现（源码级确认，未跑）
- `see.hwcrypto` / `security.timestamp` 是否由未检查的 APEX/库提供（本清单未见）
- Cuttlefish vendor 的 APEX 合并后完整 device manifest（需运行时 `dumpsys`/`checkvintf`）
- Pixel system 的 system_ext/product 矩阵合并结果（只读了 system.img 的 202604 矩阵）

## 10. 产物清单

| 产物 | 路径 | 大小 (B) | 备注 |
| --- | --- | --- | --- |
| Cuttlefish img zip | CI 构建 16644040 | 1,101,174,958 | 未落盘，按需重取 |
| `super.img`（sparse） | 已删除 | 1,746,554,644 | CRC32 `0x50466840` 已校验 |
| `vendor_a.img` | `~/workspace/android-guest/cf-artifacts/parts/` | 291,229,696 | SHA-256 见 §1.2 |
| `system_a.img` | 同上 | 959,066,112 | SHA-256 见 §1.2 |
| `system_ext_a.img` | 同上 | 258,179,072 | SHA-256 见 §1.2 |
| `product_a.img` | 同上 | 232,157,184 | SHA-256 见 §1.2 |
| Pixel `system.img` | `~/workspace/android-guest/cf-artifacts/pixel-system.img` | 1,416,597,504 | SHA-256 `1d0441ee…` |
| `lpunpack` | `~/workspace/android-guest/cf-artifacts/bin/` | 183,504 | SHA-256 `9df5a763…` |

审计时的挂载点：`/mnt/cf-vendor`、`/mnt/pixel-vendor`、`/mnt/pixel-system`、`/mnt/ap/*`。
