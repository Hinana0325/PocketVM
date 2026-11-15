# M4 图形栈适配研究 — PocketVM Android Guest

日期：2026-11-15。任务：图形栈适配研究（只研究、不大规模编译、不修改）。基线：M3（Software KeyMint 已通，keystore2 稳定，post-fs-data 全过，zygote 能启动但被 surfaceflinger onrestart 杀掉）。

结论标签说明：**已验证** = 本机实测/日志/文件核实；**源码确认** = AOSP 源码读到；**文档确认** = 官方文档；**理论推测** = 合理推断未实测；**未核实** = 待实验确认。

---

## 0. 环境核查总览（任务一）

### 0.1 M1–M3 实际用的 QEMU（已验证）

- PC 实验用的是 **AOSP prebuilt QEMU 10.2.90**，全路径 `~/workspace/kernel-android17-6.18/prebuilts/vmm/qemu/x86_64-linux-gnu/bin/qemu-system-aarch64`（M3 日志 §6.1：“QEMU 用全路径 AOSP prebuilt（10.2.90）”；M1 P5/P6 同款命令）。**不是本机 QEMU 8.2.2**。
- 实验命令（M1 P5/P6、M3-V1 同款，仅换 vendor 盘）：
  `-machine virt -cpu max -smp 2 -m 2048 -accel tcg,thread=multi -kernel Image-qemu-virt -initrd m1-p3.cpio.gz -device virtio-gpu-pci … -display none -serial file:… -monitor none -no-reboot`
- 关键：命令里已有 `-device virtio-gpu-pci`（2D），**没有 `-gl`、没有 rutabaga、没有 virgl**；显示后端是 `-display none`。

### 0.2 QEMU 图形设备能力（已验证，`-device help` / `-display help` 实测）

| QEMU | virtio-gpu-pci（2D） | virtio-gpu-gl-pci（virgl） | virtio-gpu-rutabaga-pci（gfxstream） | display 后端 |
|---|---|---|---|---|
| AOSP prebuilt 10.2.90（PC 实验） | ✅ | ✅ | ✅ | none、egl-headless |
| 本机 Debian 8.2.2 | ✅ | ❌ | ❌ | none、curses |
| 手机端 PocketVM QEMU 11.1.2 | ✅（App 已在用，见下） | 未核实 | 未核实 | VNC（App 用 loopback VNC） |

- 手机端核查（已验证一半）：`libqemu-system-aarch64.so` 的字符串里**确实存在** `virtio-gpu-gl`、`virtio-gpu-gl-pci`、`virtio-gpu-rutabaga`、`virtio-gpu-rutabaga-pci`、`virtio-vga-rutabaga` 等设备类型名；但构建脚本 `scripts/build-qemu-android.sh` 的 configure 行是 `--without-default-features --enable-tcg --enable-vnc --enable-slirp --enable-fdt --enable-pixman`，**没有 `--enable-virglrenderer`、没有 `--enable-rutabaga`，也不构建 virglrenderer/rutabaga/mesa 依赖**。字符串存在 ≠ 运行时可用——设备能否真正创建必须在真机上实测（`-device help` 或试启动）。**结论：手机端 -gl/-rutabaga 运行时可用性为未核实；不要据此排实施计划。**
- App 侧（源码确认）：`QemuCommandBuilder.kt` 已有 `DisplayDevice.VIRTIO_GPU -> "virtio-gpu-pci"`（RAMFB 是默认），即手机端 2D virtio-gpu-pci 是现成可用的显示设备选项。

### 0.3 Guest 内核 DRM 驱动（已验证）

`~/workspace/kernel-android17-6.18/out/dist-qemu-virt/.config`（即 `Image-qemu-virt` 的配置）：

```text
CONFIG_DRM=y
CONFIG_DRM_VIRTIO_GPU=y
CONFIG_DRM_VIRTIO_GPU_KMS=y
CONFIG_VIRTIO=y
CONFIG_VIRTIO_PCI=y
CONFIG_VIRTIO_MMIO=y
# CONFIG_DMABUF_HEAPS_SYSTEM is not set   ← 已知缺口（M1 就有，暂不堵图形链）
```

运行时证据（m3-v1-serial.log 第 259–264 行，guest ~2.2s）：

```text
[drm] pci: virtio-gpu-pci detected at 0000:00:06.0
[drm] features: -virgl +edid -resource_blob -host_visible
[drm] features: -context_init
[drm] number of scanouts: 1
[drm] number of cap sets: 0
[drm] Initialized virtio_gpu 0.1.0 for 0000:00:06.0 on minor 0
```

即：**DRM virtio-gpu 驱动已加载，/dev/dri/card0（minor 0）已注册；`-virgl` 说明当前是纯 2D 模式，cap sets 为 0（无 virgl、无 gfxstream capset）**。内核层图形设备没有问题。

### 0.4 GSI 自带图形组件盘点（已验证，debugfs 直接读 system.img）

`/system/lib64` 有：`libEGL.so`、`libGLESv1_CM.so`、`libGLESv2.so`、`libGLESv3.so`（wrapper 库）、**`libEGL_angle.so`、`libGLESv1_CM_angle.so`、`libGLESv2_angle.so`（6.1 MB，ANGLE 实现）**、`libvulkan.so`（loader）、`libgralloctypes.so`（仅类型定义）、composer 接口库（`android.hardware.graphics.composer3-V6-ndk.so`、`composer@2.1–2.4.so`，仅接口）、`libdrm.so`。

**没有的**（逐项核实为空）：

- 无 `/system/lib64/egl/`、无 `/system/lib/egl/`、无 `/vendor/lib64/egl/`（vendor-m3-v2.img 里也没有）；
- 无 `libEGL_swiftshader.so`、`libGLESv2_swiftshader.so`、`libGLES_android.so`；
- 无任何 `vulkan.*.so` 驱动（无 `vulkan.pastel.so`、无 `vulkan.ranchu.so`）；
- 无 gralloc 实现（`/system/lib64/hw/` 为空；无 `gralloc.*.so`、`mapper.*.so`、`allocator.*.so`）；
- 无 hwcomposer 实现（无 `hwcomposer.*.so`、`composer.*-service`）；
- `/system/etc/init/` 只有 `surfaceflinger.rc`（233 B）、`gpuservice.rc`、`drmserver_dynamic.rc`，无 composer/gralloc 服务 rc；
- `/system/apex/` 44 个 APEX（与 M2/M3 的 44 一致），**无 `com.android.angle` APEX、无 SwiftShader APEX**。
- vendor-m3-v2.img 的 `build.prop` 只有 `ro.zygote=zygote64` 和 `ro.vendor.build.security_patch=2026-10-05`；**没有 `ro.hardware.egl`、`ro.board.platform`**。cmdline 的 `androidboot.hardware=qemu` 只产生 `ro.hardware=qemu`，不是 `ro.board.platform`。

**结论：GSI 自带 ANGLE 的 EGL/GLES 库文件，但没有任何可被 libEGL loader 发现的 EGL 实现，也没有任何 gralloc/composer HAL 实现。**“内核有 virtio-gpu 驱动”与“Android 图形栈完整”是两回事——后者目前是零。

---

## 1. SurfaceFlinger 崩溃根因报告（任务一核心）

### 1.1 直接原因（已验证，tombstone 为证）

m3-v1-serial.log 里 8 次 surfaceflinger SIGABRT（guest 796.4s、815.0s、836.4s、857.1s、876.2s、896.2s、915.3s、935.6s；每次都是启动后约 19.5s 崩溃，极规律），对应 userdata.img `/tombstones/` 里的 8 个 surfaceflinger tombstone（tombstone_02/05/08/11/14/17/20/23，经 debugfs 从 userdata 副本提取，fsck 修复后读取——原盘未动）。**8 个的 Abort message 完全相同**：

```text
signal 6 (SIGABRT), code -1 (SI_QUEUE)
Abort message: 'couldn't find an OpenGL ES implementation, make sure one of
persist.graphics.egl, ro.hardware.egl and ro.board.platform is set'
```

以第一次为例（tombstone_02：pid 441，tid 492，线程名 RenderEngine，`>>> /system/bin/surfaceflinger <<<`）：

```text
backtrace:
  #00  libc.so (abort)
  #01  liblog.so (__android_log_default_aborter)
  #02  liblog.so (__android_log_assert)
  #03  libEGL.so (android::Loader::open)              ← EGL loader 找不到实现，LOG(FATAL)
  #04  libEGL.so (android::egl_init_drivers)
  #05  libEGL.so (eglGetDisplay)
  #06  surfaceflinger (SkiaGLRenderEngine::create)    ← RenderEngine 创建时调 eglGetDisplay
  #07  surfaceflinger (RenderEngineThreaded::threadMain)
```

串口日志侧的佐证链（m3-v1-serial.log）：surfaceflinger 启动（776.9s，pid 441）→ 约 19s 内无任何 surfaceflinger 自身日志 → `crash_dump64` 活动（790.9s、796.3s）→ `RenderEngine` 进程退出（796.31s）→ surfaceflinger SIGABRT（796.44s）→ onrestart 执行 `restart --only-if-running zygote` → zygote 被 SIGKILL。**zygote 是受害者，不是原因。**

### 1.2 loader 的查找逻辑（源码确认，Loader.cpp master）

`platform/frameworks/native/opengl/libs/EGL/Loader.cpp` 中 `Loader::open()` 的顺序：

1. 若 `GraphicsEnv::shouldUseAngle()` 为真，尝试加载 ANGLE；失败则 FATAL "Failed to load ANGLE."；
2. 尝试 updatable driver APK（无）；
3. 若 `shouldUseNativeDriver()` 为真，读 `ro.hardware.egl`，为空则 FATAL；
4. 默认路径：依次取 `HAL_SUBNAME_KEY_PROPERTIES = {persist.graphics.egl, ro.hardware.egl, ro.board.platform}` 中第一个非空值，用它作后缀在 `/vendor/${LIB}/egl/` 找 `lib{GLES|[EGL|GLESv1_CM|GLESv2]}_${SUFFIX}.so`，**找到第一个非空属性就停，不管成功与否**；
5. 再试无后缀精确名（`/vendor/lib64/egl/libGLES.so` 等）；
6. 仍无：`LOG_ALWAYS_FATAL("couldn't find an OpenGL ES implementation, make sure one of %s, %s and %s is set")`——与 tombstone 一字不差。

关键细节：

- 后缀为 `angle` 时，`load_system_driver` 改去 `/system/lib64` 找（`isSuffixAngle ? SYSTEM_LIB_PATH : VENDOR_LIB_EGL_DIR`）；其他后缀一律去 `/vendor/lib64/egl`。
- `attempt_to_load_angle` 加载的是 `libEGL_angle.so` / `libGLESv1_CM_angle.so` / `libGLESv2_angle.so`（系统 ANGLE 时直接 dlopen 名字；否则走 ANGLE APK namespace）。GSI 的 `/system/lib64` 里这三个文件都在——**但 `shouldUseAngle()` 为假时这条路根本不会走**（`ro.hardware.egl` 未设）。
- 通配符回退（`libGLES_*.so`）只在 `ro.vendor.api_level < __ANDROID_API_U__`（API 35）时才试；GSI 是 API 37，**无通配符回退**。
- 旧的 emulator 软件渲染器路径 `/system/lib/egl/libGLES_android.so` 在新 loader 的 `findLibrary` 里被显式跳过（"always skip the software renderer"），且 GSI 里根本没有这个文件。

### 1.3 排除项（已验证）

- **不是 Composer HAL 故障**：surfaceflinger 死在 RenderEngine 创建（`eglGetDisplay`）阶段，根本还没走到 HWC；整个串口日志里 `hwcomposer` / `gralloc` / `composer` / `minigbm` / `egl` / `vulkan` **零出现**——没有任何 composer 服务启动、查找或失败记录。HWC/gralloc 缺失是**下一道**坎，不是当前这一道。
- **不是内核/DRM 故障**：见 §0.3，virtio-gpu DRM 已初始化，minor 0 已注册。
- **不是 zygote 故障**：zygote 每次都是被 surfaceflinger 的 onrestart 机制 SIGKILL（M3 日志已证实）。
- **不是 SELinux 拦截**：m3-v2 日志 AVC 已清零方向明确；且本次崩溃是 userspace 的 LOG(FATAL)，不是权限拒绝。
- **不是“缺 SwiftShader 就等于缺全部”**：缺的是“loader 能发现的任意 EGL 实现”；ANGLE 文件其实在 GSI 里，只是没人告诉 loader 用它。

### 1.4 附带发现：audioserver SIGSEGV（已验证，独立问题）

其余 tombstone（00/01/03/04/06/07/09/10/12/13/15/16/18/19…）全是 audioserver SIGSEGV，串口日志 788–794s 有对应记录：`Could not find android.hardware.audio.core.IModule/default in the VINTF manifest`。原因是**无 audio HAL**，与 surfaceflinger 无关，不阻塞图形链；记入待办，不在 M4 处理。

### 1.5 根因一句话

**surfaceflinger 的 RenderEngine 线程调 `eglGetDisplay` 时，libEGL loader 按 `persist.graphics.egl` / `ro.hardware.egl` / `ro.board.platform` 三个属性找不到任何 EGL 实现（三个属性全空，且 `/vendor/lib64/egl` 不存在、GSI 无 SwiftShader EGL、无 Vulkan ICD），于是 LOG(FATAL) → SIGABRT；每次重启都撞同一堵墙。**

---

## 2. Cuttlefish 图形栈组件依赖图（任务二）

### 2.1 device_vendor.mk（源码确认，master，`device/google/cuttlefish/shared/graphics/device_vendor.mk`）

| 类别 | 组件 |
|---|---|
| Gfxstream 通用库 | `libandroidemu`、`libOpenglCodecCommon`、`libOpenglSystemCommon`、`libGLESv1_CM_emulation`、`lib_renderControl_enc`、`libEGL_emulation`、`libGLESv2_enc`、`libGLESv2_emulation`、`libGLESv1_enc`、`libGoldfishProfiler` |
| Gfxstream OpenGL | `libEGL_emulation`、`libGLESv1_CM_emulation`、`libGLESv1_enc`、`libGLESv2_emulation`、`libGLESv2_enc` |
| Gfxstream Vulkan | `com.google.cf.vulkan`（仅 `TARGET_VULKAN_SUPPORT=true` 时） |
| Hardware Composer HAL | `com.android.hardware.graphics.composer.drm_hwcomposer`、`com.android.hardware.graphics.composer.ranchu` |
| Gralloc | `com.google.cf.gralloc`、`android.hardware.graphics.allocator-service.minigbm`、`mapper.minigbm` |
| init rc | `device_google_cuttlefish_shared_config_init_graphics_vendor_rc`（`use_init_graphics_vendor_rc=true`） |
| Vendor 属性 | `ro.vendor.hwcomposer.pmem=/dev/block/pmem1`；`ro.vendor.hwc.drm.present_fence_not_reliable=true`；`vendor.hwc.drm.device=/dev/dri/card0` |
| System 属性 | `service.sf.prime_shader_cache=0` |

### 2.2 qemu_manager.cpp 图形配置（源码确认，`host/libs/vm_manager/qemu_manager.cpp`）

`ConfigureGraphics()` 按 `gpu_mode` 生成 bootconfig 参数（另见 `flags.cc` 的完整 mode 集合：`{auto, custom, drm_virgl, gfxstream, gfxstream_guest_angle, gfxstream_guest_angle_host_swiftshader, gfxstream_guest_angle_host_lavapipe, guest_swiftshader}`）：

| gpu_mode | bootconfig 参数 |
|---|---|
| `guest_swiftshader` | `androidboot.cpuvulkan.version=1.2`（VK 1.2）；`androidboot.hardware.gralloc=minigbm`；`androidboot.hardware.hwcomposer=<instance hwcomposer>`；`androidboot.hardware.egl=angle`；`androidboot.hardware.vulkan=pastel`；`androidboot.opengles.version=196609`（ES 3.1） |
| `drm_virgl` | `cpuvulkan.version=0`；`gralloc=minigbm`；`hwcomposer=ranchu`；`hwcomposer.mode=client`；`egl=mesa`；`opengles.version=196608`（ES 3.0） |
| `gfxstream` / `gfxstream_guest_angle` / `gfxstream_guest_angle_host_swiftshader` | `cpuvulkan.version=0`；`gralloc=minigbm`；`hwcomposer=<instance>`；`hwcomposer.display_finder_mode=drm`；`egl=emulation`（或 `angle`，用 ANGLE 时）；`vulkan=ranchu`；`gltransport=virtio-gpu-asg`（Android 11 才用 `virtio-gpu-pipe`）；`opengles.version=196609` |
| `none` | 无参数 |

`StartCommands()` 里的 QEMU 设备与显示（源码确认）：

| gpu_mode | QEMU GPU 设备 | 显示 |
|---|---|---|
| `guest_swiftshader` 或 QEMU < 6 | `virtio-gpu-pci`（2D） | VNC（`127.0.0.1:<port>`） |
| `drm_virgl` | `virtio-gpu-gl-pci` | `-display egl-headless` + VNC |
| `gfxstream` | `virtio-gpu-rutabaga-pci,capset_names=gfxstream-gles:gfxstream-vulkan:gfxstream-composer,hostmem=256M` | VNC |
| `gfxstream_guest_angle[_host_swiftshader]` | `virtio-gpu-rutabaga-pci,capset_names=gfxstream-vulkan:gfxstream-composer,hostmem=256M` | VNC（HostSwiftShader 时另设 `VK_DRIVER_FILES`/`VK_ICD_FILENAMES` 指向宿主 SwiftShader ICD json） |
| 其他 | — | `-display none` |

设备统一加 `,id=gpu0,xres=<宽>,yres=<高>`；ARM64 的 `androidboot.boot_devices=4010000000.pcie`。另注：Cuttlefish 在 QEMU 上还会挂一串 virtio-console（hvc0 内核日志、hvc1 串口、hvc2 logcat、hvc3 keymaster…）——与图形无关，略。

### 2.3 Cuttlefish GPU 文档（文档确认，source.android.com/docs/devices/cuttlefish/gpu）

- 加速模式：GfxStream（`launch_cvd --gpu_mode=gfxstream`，Guest 的 OpenGL/Vulkan 调用直传宿主）与 Virgl（`--gpu_mode=drm_virgl`，Guest OpenGL 转中间表示再由宿主 virglrenderer 执行）。
- 加速模式要求宿主有：支持 `GL_KHR_surfaceless_context` 的 EGL 驱动、OpenGL ES 驱动、Vulkan 驱动。
- 无加速时（Android 11+ 检测不到宿主 GPU）：回退 SwiftShader 软件渲染（OpenGL + Vulkan 的软件实现，任何宿主都能跑，但比真 GPU 慢得多）。
- **SwiftShader 在 Cuttlefish 语境里是“无 GPU 服务器”的官方保留模式**，不是野路子。

### 2.4 组件依赖图

```text
宿主（PC QEMU 10.2.90 / 手机 QEMU 11.1.2）
  │
  │  -device virtio-gpu-pci            (路线 A: 2D, 已在用)
  │  -device virtio-gpu-gl-pci         (路线 B: virgl, 需宿主 virglrenderer + EGL)
  │  -device virtio-gpu-rutabaga-pci   (路线 C: gfxstream, 需宿主 gfxstream 后端)
  ▼
Guest 内核: DRM virtio_gpu 驱动 (CONFIG_DRM_VIRTIO_GPU=y, 已验证加载)
  │  /dev/dri/card0 (minor 0, 已注册)
  ├───────────────────────────────┬──────────────────────────────────┐
  ▼                               ▼                                  ▼
drm_hwcomposer (HWC HAL)     minigbm (Gralloc)                EGL/GLES 实现
  │  composer.drm_hwcomposer   │  allocator-service.minigbm        │  路线 A: ANGLE(libEGL_angle.so,
  │  vendor.hwc.drm.device=    │  mapper.minigbm                   │    GSI 已有) + vulkan.pastel.so
  │    /dev/dri/card0          │  DMA-BUF 跨进程共享               │    (SwiftShader Vulkan, 缺)
  │  present_fence_not_        ▼                                  │  路线 B: Mesa EGL/GLES (缺)
  │    reliable=true           SurfaceFlinger                     │  路线 C: libEGL_emulation 等
  ▼                               │  SkiaGLRenderEngine              │    (Gfxstream, 缺)
VINTF manifest 声明            │  eglGetDisplay → Loader::open()     ▼
  (allocator/composer)         │  需要 ro.hardware.egl 等属性     Vulkan ICD
  init rc 启动服务             ▼                                  vulkan.pastel.so (A)
  SELinux 上下文           zygote → system_server →            vulkan.ranchu.so (C)
                               boot_completed
```

依赖要点（源码确认 + 已验证对照）：

- **EGL 发现链**：`ro.hardware.egl` 等属性（bootconfig `androidboot.hardware.egl` 或 vendor build.prop）→ loader 按后缀找 `/vendor/lib64/egl/libEGL_<后缀>.so`（`angle` 后缀例外，去 `/system/lib64` 找 `libEGL_angle.so`）；ANGLE 还需要 Vulkan 后端才能工作——**只设 `egl=angle` 不够，必须同时有 `vulkan.pastel.so`（路线 A）或 gfxstream Vulkan（路线 C）**。
- **Gralloc 链**：`androidboot.hardware.gralloc=minigbm` → allocator-service.minigbm（AIDL）+ mapper.minigbm；minigbm 通过 DRM dumb buffer / virtio-gpu resource 分配显存，DMA-BUF 句柄跨进程传给 SurfaceFlinger 与 drm_hwcomposer。
- **HWC 链**：`androidboot.hardware.hwcomposer=drm` → composer.drm_hwcomposer 服务打开 `/dev/dri/card0` 做 KMS 送显与合成；`vendor.hwc.drm.device=/dev/dri/card0` 强制只用 virtio-gpu。
- **VINTF**：allocator、mapper、composer 都要在 device manifest 里声明，否则 servicemanager 查找失败（M2 里 audio HAL 的 "Could not find … in the VINTF manifest" 就是同类教训）。
- **init rc 与 SELinux**：每个 HAL 服务都要有 vendor init rc（启动、socket、user/group）、对应的 `*_exec` / `*_data_file` 标签与 allow 规则——按 M2/M3 的标签方法逐项做，不要批量放宽。

---

## 3. 三条路线比较（任务三）

| 维度 | A. VirtIO GPU 2D + SwiftShader（Cuttlefish `guest_swiftshader`） | B. VirtIO GPU + Virgl + minigbm + DRM HWC | C. VirtIO GPU + Gfxstream + Cuttlefish HAL |
|---|---|---|---|
| 适配 Android 17 / SDK 37 | ✅ `guest_swiftshader` 是 Cuttlefish 官方保留模式，`flags.cc` 明确列出；组件全是 AOSP 现成（minigbm、drm_hwcomposer、SwiftShader、ANGLE） | ✅ Cuttlefish `drm_virgl` 官方模式；需 Mesa（external/mesa）EGL/GLES | ✅ Cuttlefish `gfxstream` 官方模式；需 goldfish-opengl（Gfxstream）全套 |
| ARM64 QEMU virt 兼容 | ✅ 已在跑：virtio-gpu-pci + DRM 驱动已验证 | ✅ virtio-gpu-gl-pci 在 virt 机型上是标准 PCI 设备 | ✅ virtio-gpu-rutabaga-pci 在 virt 机型上是标准 PCI 设备 |
| TCG 模式兼容 | ✅ 纯软件渲染，无需宿主 GPU；代价是性能（见下） | ⚠️ Virgl 命令在宿主翻译执行，TCG 下能跑，但宿主仍需 GL 驱动 | ⚠️ Gfxstream 命令在宿主执行，TCG 下能跑，但宿主仍需渲染后端 |
| 需重编 QEMU？ | ❌ PC：10.2.90 已有 virtio-gpu-pci；手机：App 已暴露该设备 | PC：10.2.90 已有 virtio-gpu-gl-pci；**手机：未核实**（构建脚本无 virglrenderer；设备名串在但运行时未验证） | PC：10.2.90 已有 rutabaga 设备；**手机：未核实**（同上，无 rutabaga 依赖构建） |
| 需改 Guest 内核？ | ❌ DRM_VIRTIO_GPU 已=y；`CONFIG_DMABUF_HEAPS_SYSTEM` 未开是已知缺口，但 minigbm 可用 dumb buffer 路径，暂不堵（待验证） | ❌ 同 A（另需 virgl capset 支持，内核已有 `-virgl` 特性位但当前 QEMU 未启用） | ❌ 同 A |
| 需新建 Vendor 图形组件？ | **是，3 件**：① `vulkan.pastel.so`（SwiftShader Vulkan，AOSP external/swiftshader 构建）；② minigbm allocator-service + mapper；③ drm_hwcomposer 服务。ANGLE 已在 GSI，无需构建 | **是，4 件**：Mesa EGL/GLES（external/mesa，量大）+ minigbm + drm_hwcomposer + 宿主 virglrenderer | **是，5 件**：Gfxstream guest 库全套（libEGL_emulation 等）+ `com.google.cf.vulkan` + minigbm + drm_hwcomposer/ranchu + 宿主 gfxstream 后端 |
| 手机端（QEMU 11.1.2 TCG）可移植性 | ✅ **最好**：无宿主 GPU 依赖，无 QEMU 重编，virtio-gpu-pci 已在 App 侧；Vendor 组件一次构建、多 GSI 复用 | ❌ **短期不可行**：宿主（Android 手机）没有 virglrenderer/Mesa 构建路径；virglrenderer 需为 Android 交叉编译 + 宿主 EGL/GL 驱动，工作量大且无先例 | ❌ **短期不可行**：宿主 gfxstream 后端需为 Android 构建（Cuttlefish host package 是 x86_64 Linux 的）；且手机 -rutabaga 设备未验证 |
| 性能预期（理论推测） | 调试模式：SwiftShader 是 CPU 光栅化，TCG 下是“双重 CPU 税”（Guest 指令翻译 + 光栅化）；静态画面/开机动画可能勉强出图，持续动画、滑动、视频极可能卡顿到不可用。**定位：验证图形链路，不追求可用** | 宿主加速：PC 上可用；手机上无宿主 GL 可用，谈不上 | 宿主加速：PC 上最好；手机上无宿主后端，谈不上 |
| 实施量级（理论推测） | 最小：3 个 Vendor 组件 + bootconfig/VINTF/rc/SELinux | 中：Mesa 构建量大 + 宿主 virglrenderer | 大：Gfxstream guest+host 两套 + rutabaga 设备验证 |

**比较结论：路线 A 是唯一短期可达的路线。** 路线 B、C 在 PC 上是成立的官方模式，但它们的“宿主加速”前提在手机上不存在（没有宿主 GL/Vulkan 驱动可接），且手机端 QEMU 是否真有 -gl/-rutabaga 设备尚未验证。路线 A 的短板只是性能，而当前目标是“让 SurfaceFlinger 稳定运行、走到 boot_completed”，不是“流畅用”——性能问题留待以后（宿主加速是远期工作）。

另注（文档确认，Fedora AArch64 文档）：在 QEMU 上跑 Android 时“图形是最主要的问题”，有实践提到需要给 SurfaceFlinger 打 Cuttlefish 相关补丁（`cuttlefish-surfaceflinger.patch`）才能工作。**列为未核实线索**：若 E1（EGL 发现修复）后 RenderEngine 仍失败，再查这条线，不要现在预设。

---

## 4. 推荐的最小实现方案（任务四）

**采用路线 A：Cuttlefish `guest_swiftshader` 模式，最小组件集合。**

### 4.1 目标链（分阶段验收，每阶段独立 Vendor 镜像）

```text
E1  EGL 发现修复 → surfaceflinger 越过 RenderEngine 创建（不再因 "couldn't find an OpenGL ES
    implementation" 崩溃；tombstone 不再出现该 Abort message）
E2  minigbm 接入 → Gralloc 能分配图形缓冲区（allocator-service.minigbm + mapper.minigbm 注册成功）
E3  drm_hwcomposer 接入 → Composer HAL 注册并响应（/dev/dri/card0 打开，HWC 服务存活）
E4  全链 → SurfaceFlinger 稳定运行 → zygote 不再被 onrestart 杀 → system_server 启动 →
    sys.boot_completed=1（静态画面即可；触摸/Launcher 交互是 E4 之后的事）
```

### 4.2 最小组件清单（按依赖顺序）

| # | 组件 | 来源（AOSP，android17-release 分支核对） | 放到 Vendor 的位置（待实施时核对 ABI/依赖） |
|---|---|---|---|
| 1 | `vulkan.pastel.so`（SwiftShader Vulkan ICD） | `external/swiftshader`（与 M3 KeyMint 同样的 NDK/平台头交叉编译思路） | `/vendor/lib64/hw/vulkan.pastel.so`（ro.hardware.vulkan=pastel 的发现路径；实施时核对 libvulkan loader 的实际搜索路径） |
| 2 | ANGLE（已在 GSI） | GSI `/system/lib64/libEGL_angle.so` 等，**无需构建** | 不动；靠 `ro.hardware.egl=angle` 选中 |
| 3 | minigbm allocator-service + mapper | `external/minigbm`；AIDL 服务 `android.hardware.graphics.allocator-service.minigbm`、`mapper.minigbm` | `/vendor/bin/hw/` + VINTF manifest + init rc + SELinux |
| 4 | drm_hwcomposer | `external/drm_hwcomposer`（上游 gitlab.freedesktop.org/drm-hwcomposer/drm-hwcomposer；AOSP submodule `external/drm_hwcomposer`，android17-release 分支已确认） | `/vendor/bin/hw/` + VINTF manifest + init rc + SELinux |
| 5 | bootconfig/属性 | Cuttlefish `ConfigureGraphics()` 的 `guest_swiftshader` 一组 | QEMU `-append` 追加（实验阶段）或 vendor_boot bootconfig（产品化时）；**不要只写 vendor build.prop——`androidboot.*` 须走 cmdline/bootconfig** |

bootconfig 一组（照抄 Cuttlefish `guest_swiftshader`，源码确认）：

```text
androidboot.cpuvulkan.version=4202496
androidboot.hardware.gralloc=minigbm
androidboot.hardware.hwcomposer=drm
androidboot.hardware.egl=angle
androidboot.hardware.vulkan=pastel
androidboot.opengles.version=196609
```

（数值核对：`VK_API_VERSION_1_2 = (1<<22)|(2<<12) = 4202496`；`androidboot.opengles.version=196609` 即 0x30001 = ES 3.1。实施时以 `qemu_manager.cpp` 源码为准再核对一次。）

Vendor 属性（照抄 device_vendor.mk，源码确认）：

```text
vendor.hwc.drm.device=/dev/dri/card0
ro.vendor.hwc.drm.present_fence_not_reliable=true
service.sf.prime_shader_cache=0
```

### 4.3 为什么不是“只设 ro.hardware.egl=angle 就行”

- ANGLE 需要 Vulkan 后端；GSI 里没有任何 Vulkan ICD。只设 `egl=angle` 会把崩溃从“找不到 EGL 实现”推到“ANGLE 找不到 Vulkan 驱动”。**必须同时补 `vulkan.pastel.so`。**
- 即使 EGL 通了，SurfaceFlinger 接下来会要 Gralloc 分配 buffer（无 minigbm → 下一处崩溃）和 HWC 合成（无 composer → 再下一处）。所以 E1 只是第一块拼图，E2/E3 必须跟上——但**每块单独验证**，不要一次全加上再排错。
- 另一条备选（理论推测，未验证）：原生 SwiftShader EGL（`ro.hardware.egl=swiftshader` + `/vendor/lib64/egl/libEGL_swiftshader.so` + `libGLESv2_swiftshader.so`，external/swiftshader 构建）。若 ANGLE+pastel 路径遇到 ANGLE 版本/namespace 问题，再试这条；不要两条并行。

---

## 5. 实施步骤（按文件、模块、命令拆分）

> 约束重申：不改 M3 的 KeyMint 实现；不重编 QEMU（除非实验证明缺设备）；每轮用独立 Vendor 镜像，保留 `vendor-m3-v2.img` 基线；全程 SELinux enforcing。

### 步骤 0 — 基线冻结与实验脚手架（准备）

- 复制基线：`cp work/m1-pixel/vendor-m3-v2.img work/m1-pixel/vendor-m4-base.img`，记录 SHA。
- 以后每轮：`vendor-m4-e1.img`、`vendor-m4-e2.img`、…，只改一处，串口日志按轮归档（`m4-e1-serial.log`…）。
- 回滚：任意一轮失败，把 QEMU 命令里的 vendor 盘指回 `vendor-m4-base.img` 即回滚。

### 步骤 1 — E1：只加 bootconfig，验证 EGL 发现链（不动任何二进制）

- 改动：QEMU `-append` 追加 `androidboot.hardware.egl=angle androidboot.hardware.vulkan=pastel androidboot.hardware.gralloc=minigbm androidboot.hardware.hwcomposer=drm androidboot.opengles.version=196609 androidboot.cpuvulkan.version=4202496`（其他变量保持 M3-V1 同款）。
- 目的：确认属性能进 guest（`getprop` 视角待实验内验证）、确认崩溃信息是否变化。
- 预期：仍会崩溃（vulkan.pastel.so、minigbm、drm_hwcomposer 都还没有），但 Abort message 应从“找不到 EGL 实现”变为 ANGLE/Vulkan 相关（证明 loader 走到了 ANGLE 路径）。若 message 不变，说明属性没生效，先查属性传递，不往下走。
- 验收：tombstone 的 Abort message 变化（记录新 message）；串口日志出现 ANGLE/Vulkan 相关行。
- 回滚：去掉追加的 cmdline 参数。

### 步骤 2 — 构建 `vulkan.pastel.so`（SwiftShader Vulkan）

- 来源：AOSP `external/swiftshader`（android17-release 分支）；只取 Vulkan ICD 目标，不要整树同步。
- 构建：复用 M3 的交叉编译经验（NDK r27c + 平台版头文件 + GSI libc++，注意 M3 总结的三个坑：libbinder_ndk 过旧、gen-build-list 过滤误杀、`__ndk1` 与 `__1` ABI 不匹配）。
- 集成：`/vendor/lib64/hw/vulkan.pastel.so` + 对应 SELinux 标签（`vendor_file` 体系下按 file-contexts 规则标，不要 blanket 标）；先不加 VINTF（Vulkan ICD 由 libvulkan 直接发现，实施时核对搜索路径）。
- 验收：E1 同款启动不再报 EGL/Vulkan 缺失；RenderEngine 创建通过（tombstone 不再出现该进程的 Abort）；surfaceflinger 存活时间显著超过 19.5s。
- 回滚：vendor 盘换回 E1 镜像。

### 步骤 3 — 构建并接入 minigbm（Gralloc）

- 来源：AOSP `external/minigbm`（android17-release）；目标：`android.hardware.graphics.allocator-service.minigbm` + `mapper.minigbm`。
- 集成：二进制进 `/vendor/bin/hw/`；VINTF device manifest 加 allocator/mapper 声明；vendor init rc 启动服务；SELinux 标签与 allow 规则（参考 M2/M3 的标签方法，逐项核对）。
- 验收：`servicemanager` 能查到 allocator/mapper；surfaceflinger 日志出现 Gralloc 分配成功（或至少不再因无 Gralloc 崩溃）；记录新的崩溃点（若有）作为 E4 输入。
- 回滚：vendor 盘换回 E2 镜像。

### 步骤 4 — 构建并接入 drm_hwcomposer（HWC）

- 来源：AOSP `external/drm_hwcomposer`（android17-release；上游 freedesktop drm-hwcomposer）。
- 集成：二进制进 `/vendor/bin/hw/`；VINTF manifest 加 composer 声明；vendor init rc；属性 `vendor.hwc.drm.device=/dev/dri/card0` + `ro.vendor.hwc.drm.present_fence_not_reliable=true`；SELinux（drm 设备节点访问规则）。
- 验收：composer 服务注册成功并响应；surfaceflinger 不再因 HWC 缺失失败；`/dev/dri/card0` 被打开（日志或 strace 佐证）。
- 回滚：vendor 盘换回 E3 镜像。

### 步骤 5 — E4 全链验证：SurfaceFlinger → zygote → system_server → boot_completed

- 配置：E1–E4 全部件齐的 vendor 镜像 + 步骤 1 的 bootconfig；其他变量保持 M3-V1 同款。
- 观察：surfaceflinger 存活、zygote 不再被 onrestart 杀、system_server 启动、`sys.boot_completed=1`。
- 验收（按 §4.1 的 E4 标准）；只要求静态画面，不要求触摸/滑动/Launcher 交互（那是下一阶段）。
- 回滚：vendor 盘换回任一上一阶段镜像。

### 步骤 6 — 手机端移植评估（E4 通过后，不提前）

- 在真机上核查手机 QEMU 11.1.2 的 `-device help`（virtio-gpu-gl/rutabaga 是否真可用）；确认 App 的 `DisplayDevice.VIRTIO_GPU` 路径能给 Android Guest 用（当前 App 默认 RAMFB，Android Guest 需要 virtio-gpu-pci + VNC 看画面）。
- 评估 Vendor 组件（vulkan.pastel.so、minigbm、drm_hwcomposer）在手机 QEMU 11.1.2 + TCG 下的表现；性能基线只记录实测值，不承诺目标。
- 只有 E4 在 PC 上通过后才启动这一步。

---

## 6. 每步验收标准及回滚方式（汇总）

| 步骤 | 验收标准 | 回滚方式 |
|---|---|---|
| 0 基线冻结 | `vendor-m4-base.img` SHA 已记录；M3-V1 同款命令能复现（surfaceflinger 8 次同 message 崩溃） | —（基线本身） |
| 1 E1 bootconfig | tombstone Abort message 变化（ANGLE/Vulkan 相关），证明属性生效且 loader 走到 ANGLE 路径 | 去掉 cmdline 追加参数 |
| 2 vulkan.pastel.so | RenderEngine 创建通过；surfaceflinger 存活 > 19.5s；无该进程 Abort | vendor 盘换回 E1 镜像 |
| 3 minigbm | allocator/mapper 在 servicemanager 可查；surfaceflinger 不再因 Gralloc 崩溃 | vendor 盘换回 E2 镜像 |
| 4 drm_hwcomposer | composer 服务注册并响应；/dev/dri/card0 被打开 | vendor 盘换回 E3 镜像 |
| 5 E4 全链 | surfaceflinger 稳定；zygote 不被 onrestart 杀；system_server 启动；`sys.boot_completed=1` | vendor 盘换回任一上一阶段镜像 |
| 6 手机端评估 | 真机 `-device help` 实测结果；App virtio-gpu 路径可用性；性能实测值 | 不涉及（评估类） |

## 7. 未核实事项（留给实施阶段）

1. ANGLE+pastel 路径在 API 37 GSI 上的实际表现（ANGLE 版本、namespace、Vulkan loader 搜索路径）——实施时以实验为准。
2. minigbm 在 `CONFIG_DMABUF_HEAPS_SYSTEM` 未开的内核上能否正常分配（dumb buffer 路径是否够用）。
3. drm_hwcomposer 在只有 1 个 scanout、无 EDID 细节的 virtio-gpu 2D 设备上的行为。
4. 手机 QEMU 11.1.2 的 virtio-gpu-gl/rutabaga 运行时可用性（只核实了字符串存在）。
5. Fedora 文档提到的 `cuttlefish-surfaceflinger.patch` 是否必需。
6. audioserver SIGSEGV（缺 audio HAL）——独立问题，不阻塞图形链，排期另议。
7. SwiftShader 在 TCG 下的实际帧率——无公开实测数据可引用，实施时只记录实测值。

## 8. 交付物对照

- ✅ §1 故障根因报告（tombstone 级证据）。
- ✅ §2 Cuttlefish 组件依赖图。
- ✅ §3 三条路线比较。
- ✅ §4 推荐最小方案（路线 A，Cuttlefish `guest_swiftshader`）。
- ✅ §5 按文件/模块/命令拆分的实施步骤。
- ✅ §6 每步验收标准及回滚方式。

关键证据文件：`~/workspace/bootable-ramdisk/work/m1-pixel/m3-v1-serial.log`（3206 行）；tombstone 副本 `/tmp/ts_*.txt`（已从 userdata 副本提取，**注意：/tmp 是临时区，实施前如需复核请重新从 userdata 提取**；userdata 原盘未动）；`~/workspace/qemu-boot/system.img`（GSI）；`~/workspace/bootable-ramdisk/work/m1-pixel/vendor-m3-v2.img`（基线）。
