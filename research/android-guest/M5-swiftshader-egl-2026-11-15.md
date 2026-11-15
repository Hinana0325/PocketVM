# M5 SwiftShader + ANGLE 最小图形链路 — 2026-11-15

## 任务范围

本轮严格限定：**E1 属性验证实验 + 构建可行性核查 + E2–E6 构建计划**。
E2–E6 的完整构建**未开始**，需用户批准后逐项实施。

- 路线：A（VirtIO GPU 2D + SwiftShader，Cuttlefish `guest_swiftshader`）。GSI 自带 ANGLE，复用，不重建 ANGLE。
- 不重编 QEMU，不搜索其他图形方案，不改 PocketVM App，不改 Pixel 工厂镜像 zip。
- 每次实验只改一个变量；每轮产物与串口日志保留；区分已验证事实 / 推测 / 未验证事项；不用 SELinux permissive 作为验收方案。

## 基线冻结

| 镜像 | SHA-256（前 16 位） | 状态 |
|---|---|---|
| `work/m1-pixel/vendor-m3-v2.img`（M3 V2，noatsecure 修复） | `57b028966069784a` | **基线冻结，绝不直接改**。副本：`work/m5/vendor-m5-base.img`（同 SHA）、`work/m5/vendor-m5-e1.img`（同 SHA） |
| `work/m1-pixel/vendor-m3.img`（M3 V1，已验证可启动） | `769d420cc219c7b3` | E1b 实验用其副本 `work/m5/vendor-m5-e1-v1.img`（同 SHA） |

**本轮重要发现：M3-V2 基线镜像有缺陷，首次启动验证即失败**（见 §1.1）。M3 报告中“V2 可直接用于下次启动”的判断被证伪。

---

## §1 E1 属性验证实验

### 1.0 实验设计

只加 bootconfig（6 个 `androidboot.*` 参数），不动任何二进制：

```
androidboot.cpuvulkan.version=4202496 androidboot.hardware.gralloc=minigbm
androidboot.hardware.hwcomposer=drm androidboot.hardware.egl=angle
androidboot.hardware.vulkan=pastel androidboot.opengles.version=196609
```

其他命令行与 M1 P5/P6、M3-V1 完全相同（含 `androidboot.selinux=permissive`——P6 已证明该参数被 SetupSelinux 忽略，沿用仅为保持变量单一）。

E1 的验收标准是**属性生效本身**，不是功能验收。预期信号：Abort message 从“找不到 EGL 实现”变成 Vulkan 驱动缺失之类——该变化只能证明 Loader 找到了 ANGLE，**不能证明 ANGLE 能正常初始化**。

### 1.1 E1 run #1（V2 副本）——失败，暴露 V2 基线缺陷

- Vendor：`vendor-m5-e1.img`（V2 副本），QEMU PID 14667（AOSP 预编译 10.2.90）。
- 结果：guest ~48s 即失败：keystore2 SIGABRT ×5 → InitFatalReboot（M2 的失败模式重现）。
- 根因：`init: cannot execv('/vendor/bin/hw/android.hardware.security.keymint-service')... Permission denied`，服务退出码 127，每次重启都重复。**串口日志零 AVC——是 DAC 失败，不是 MAC。**
- 直接比对（debugfs，fsck 修复后的副本）：
  - V1：二进制 mode **100755**，目录 0755，.so 0644。
  - V2：二进制 mode **100750**，目录 **2750**（setgid+0750），.so **0640**；SELinux 标签两者相同（`u:object_r:hal_keymint_default_exec:s0`）。
  - 来源：V2 树（vendor-tree-m3-v2）用普通 `cp -r` 在 umask 0027/0007 下复制，剥掉了 "other" 权限位（M3 日志注记：“`cp -a` 在本机保所有权失败，改用 `cp -r`”）。keymint 服务以非 root、非 65534 组用户运行，0750 → EACCES；0750 目录也挡住非组用户的遍历——多个独立原因造成同一失败。
- 修复（写进日志，供下一轮执行）：在 V2 树中恢复权限（目录 0755、二进制 0755、.so 0644、清除 setgid）后重建镜像；并在构建脚本的校验段加**权限位检查**（现有校验只查标签，不查权限）。
- 副产物观察：vndk-nodef 的 `reboot bootloader` exec 失败（`/system/bin/reboot` 的 SELinux 标签错误，`init.vndk-nodef.rc:3`）——已存在、无害（exec 失败，未重启），但意味着 vndk-nodef 的“缺 VNDK 则重启”安全网是静默失效的。

### 1.2 E1b（V1 副本重跑）——干净实验，结论是否定的

- Vendor：`work/m5/vendor-m5-e1-v1.img`（V1 副本，SHA `769d420cc219c7b3…`）。
- 脚本：`work/m5/run-e1b.sh`；串口日志 `work/m5/m5-e1b-serial.log`（3337 行，guest 0–约 215s，04:11 UTC 手动停止 QEMU）。
- 基线健康确认（与 M3 一致，KeyMint 未被破坏）：
  - second-stage init 启动 guest 17.9s（日志行 442）；
  - apexd-bootstrap：Activated 4 packages，194ms（行 674）——比 M3 的 710s 快得多，因 APEX 会话已持久化在 /data；
  - `keystore.module_hash.sent=true` 等待 180ms（行 1108）；vendor.keymint-default pid 214 正常启动；**keystore2 零 SIGABRT**。
- surfaceflinger 崩溃循环（与 M3 同一模式，更早发生——因 APEX 激活变快）：
  - 首次启动 guest 51.1s，SIGABRT 67.0s；共 **9 次 SIGABRT**（pid 438、507、578、650、725、792、874、948、1020），间隔约 15.8–17.5s；每次 onrestart 杀掉 zygote 再重启。
  - M3 对比：8 次 SIGABRT，间隔约 19.5s，首次崩溃 guest ~776s。
- **决定性证据——tombstone（事后 userdata 副本 + e2fsck 修复后提取，`work/m5/ts-e1b/`）：**
  - tombstone_27（pid 438，RenderEngine 线程）与 tombstone_30（pid 507，RenderEngine 线程），Abort message 与 M3 **一字不差**：
    > `couldn't find an OpenGL ES implementation, make sure one of persist.graphics.egl, ro.hardware.egl and ro.board.platform is set`
  - 另有 4 个 audioserver tombstone（26、28、29、31，缺音频 HAL 的独立问题，不阻塞图形链）。
  - 串口日志中除内核 cmdline 本身（行 31）外，**零 ANGLE / Vulkan / EGL 提及**。
- **E1 结论：未通过。6 个 bootconfig 参数未使 `ro.hardware.egl` 生效，Abort message 没有任何变化。**

### 1.3 根因：bootconfig 到 `ro.hardware.*` 之间缺一座桥（源码确认）

init **不会**自动把 `androidboot.*` 派生为 `ro.hardware.*`。`androidboot.hardware.egl=angle` 只会变成 `ro.boot.hardware.egl=angle`（init 的 cmdline 导入——该路径本身是通的：同机制设置的 `ro.boot.hardware=qemu` 驱动了 fstab 选择与 `init.${ro.hardware}.rc` 导入）。从 `ro.boot.*` 到 `ro.hardware.*` 的复制，必须由 **vendor init.rc 的 `setprop`** 完成——这是 Cuttlefish 与 Emulator 的设备配置责任，不是 init 的通用行为。

证据（全部源码确认）：

1. **Cuttlefish 当前 main** `device/google/cuttlefish/shared/config/init.vendor.rc`（`on early-init`，第 10–20 行）：
   ```rc
   setprop ro.hardware.egl ${ro.boot.hardware.egl}
   setprop ro.hardware.gralloc ${ro.boot.hardware.gralloc}
   setprop ro.hardware.hwcomposer ${ro.boot.hardware.hwcomposer}
   setprop ro.hardware.vulkan ${ro.boot.hardware.vulkan}
   setprop ro.cpuvulkan.version ${ro.boot.cpuvulkan.version}
   ```
   同一写法在 Android 11 的 `shared/config/init.vendor.rc` 与引入该机制的提交 ac65b282 中亦然。
2. **Emulator（goldfish）** `init.ranchu.rc` 同样做法：`setprop ro.hardware.egl ${ro.boot.hardwareegl:-emulation}`、`setprop ro.hardware.vulkan ${ro.boot.hardware.vulkan}`、`setprop ro.opengles.version ${ro.boot.opengles.version}`。
3. **GSI 不做这件事**（已验证：从 `~/workspace/qemu-boot/system.img` 用 debugfs 导出 `/etc/init/hw/init.rc`，55677 B）——全文无任何 `setprop ro.hardware.*`，只有两行 import：
   ```
   import /init.${ro.hardware}.rc
   import /vendor/etc/init/hw/init.${ro.hardware}.rc
   ```
   即 GSI 明确要求 **vendor 提供 `init.${ro.hardware}.rc`**（本实验中 `ro.hardware=qemu` → `/vendor/etc/init/hw/init.qemu.rc`）。
4. **我们的 vendor 没有这个文件**（已验证：`vendor-tree-m3-v2/etc/init/` 下唯一的 .rc 是 `android.hardware.security.keymint-service.rc`；vendor build.prop 只有 `ro.zygote=zygote64` 与 `ro.vendor.build.security_patch`，无任何 `ro.hardware.*` 条目）。
5. **Loader 侧的逻辑闭环**（frameworks/native/opengl/libs/EGL/Loader.cpp，android17-release，源码确认）：`shouldUseAngle()` 为真时先走 ANGLE 路径，失败则 FATAL `Failed to load ANGLE.`；只有三属性全空时才落到 native 路径并触发本实验的 FATAL。Abort message 不变 ⇒ `shouldUseAngle()` 为假 ⇒ surfaceflinger 启动时 `ro.hardware.egl` 为空。结论与 tombstone 证据一致。

**对 M4 计划的修正：E1 只加 bootconfig 是不够的；属性桥（vendor init.rc 的 setprop）是链路的必需组成部分，不是可选优化。** 这不是路线错误——Cuttlefish 的 guest_swiftshader 模式本身就是“bootconfig 参数 + vendor init.rc setprop”两件套；M4 只抄了前半套。

### 1.4 E1c：补桥后重验（本轮未执行，需批准）

- 在 vendor 树新建 `/vendor/etc/init/hw/init.qemu.rc`，内容为 Cuttlefish 的 `on early-init` setprop 块（egl/gralloc/hwcomposer/vulkan/cpuvulkan.version），另加 goldfish  precedent 的 `setprop ro.opengles.version ${ro.boot.opengles.version}`：
  ```rc
  on early-init
      setprop ro.hardware.egl ${ro.boot.hardware.egl}
      setprop ro.hardware.gralloc ${ro.boot.hardware.gralloc}
      setprop ro.hardware.hwcomposer ${ro.boot.hardware.hwcomposer}
      setprop ro.hardware.vulkan ${ro.boot.hardware.vulkan}
      setprop ro.cpuvulkan.version ${ro.boot.cpuvulkan.version}
      setprop ro.opengles.version ${ro.boot.opengles.version}
  ```
  该文件由 GSI init.rc 第 10 行自动 import（`ro.hardware=qemu`），`on early-init` 在 surfaceflinger 启动前触发；无需改任何二进制。
- **先修复 V2 树的权限位**（§1.1：目录 0755、二进制 0755、.so 0644、清 setgid），再重建镜像；重建后校验标签与权限两者。
- 用与 E1b 完全相同的 cmdline 重跑，唯一变量 = 新增的 `init.qemu.rc`。
- 预期信号：tombstone Abort message 从 EGL-loader FATAL 变成 ANGLE/Vulkan 相关（例如 `Failed to load ANGLE.` 或 ANGLE 初始化失败）——**该变化只证明 Loader 走到了 ANGLE 路径，不证明 ANGLE 能初始化**；后者是 E2 的验收。
- 若 E1c 后 message 仍不变，则需检查 `ro.boot.hardware.egl` 本身是否被设置（init 的 cmdline 导入环节）——按目前证据该环节是通的，此分支为低概率。

### 1.5 E1c 执行结果（2026-11-15 04:21–04:25 UTC）——通过

- **Vendor 镜像**：`work/m5/vendor-m5-e1c.img`，SHA-256 `57dd3ee1cd5b4364c063e0a12344a74fd3bfc5509a0ffb2e64ef6cc0612147b8`。构建脚本 `work/m5/build-vendor-e1c.sh`（基于 build-vendor-m3-v2.sh，新增 `/etc/init/hw` 与 `init.qemu.rc` 的标签 + **权限位校验**）：LABEL CHECK OK、PERM CHECK OK（12 个目录 0755、keymint 二进制 0755、其余文件 0644、setgid 0）。树 `work/m5/vendor-tree-e1c`（V2 树副本，权限修复 + 新文件；`cp -a` 在本机保所有权失败，改用 `cp -r` 后逐项 chmod——与 M3 的注记一致）。**冻结基线 `vendor-m3-v2.img` 未动**（重建前后 SHA 均为 `57b028966069784a…`）。
- **运行**：脚本 `work/m5/run-e1c.sh`（与 run-e1b.sh 同款 cmdline、同一 ramdisk `m1-p3.cpio.gz`、同一 GKI/DTB/分区布局；唯一变量 = 新 vendor 镜像）。userdata 用 `userdata-e1b-post.img` 的副本 `work/m5/userdata-e1c.img`（保留 APEX 会话状态，与 E1b 可比；原始 userdata.img 未动）；metadata 同样用副本。串口日志 `work/m5/m5-e1c-serial.log`（3342 行，guest 0–209.4s；QEMU 在 host 约 04:25 停止，日志末行截断于 surfaceflinger 第 9 次重启中——停止机制为 host 侧收尾，未完全追踪；不影响结论，9 次崩溃与全部 tombstone 均已捕获）。
- **init.qemu.rc 被导入并执行（直接证据）**：
  - 日志行 469：`Added '/vendor/etc/init/hw/init.qemu.rc' to import list`（同时行 475–476：`/init.qemu.rc` 不存在，属预期——GSI 会试两条 import 路径）；
  - 日志行 477：`Parsing file /vendor/etc/init/hw/init.qemu.rc...`；
  - 日志行 683：`processing action (early-init) from (/vendor/etc/init/hw/init.qemu.rc:8)`——**on early-init 动作实际执行**。
- **KeyMint 完好（V2 权限修复由启动验证）**：`vendor.keymint-default` pid 213；`keystore.module_hash.sent=true` 等待 215ms（行 1102）；**keystore2 零 SIGABRT、零 InitFatalReboot**；串口日志 AVC 拒绝 **0 条**。这是 V2 系镜像首次成功启动 keystore2——M3“V2 可直接用于下次启动”的判断在权限修复后才真正成立。
- **surfaceflinger 仍 SIGABRT 9 次**（pid 438、517、588、661、732、807、882、956、1040，间隔约 16–18s；zygote 被 onrestart 杀 9 次）——与 E1b 同数，属预期：E1c 只移动失败点，不改变结果。
- **决定性证据——Abort message 变了**（事后 userdata 副本 `work/m5/userdata-e1c-post.img`，e2fsck 修复后提取，`work/m5/ts-e1c/`）：E1c 窗口内提取到的 **7 份 surfaceflinger tombstone**（01、04、07、10、23、26、29）**全部**是新 message，旧 message **0 份**：
  > `no suitable EGLConfig found, giving up (format: 1, vendor: Android, version: 1.4 Android META-EGL, extensions: EGL_ANDROID_front_buffer_auto_refresh EGL_ANDROID_get_native_client_buffer EGL_ANDROID_presentation_time EGL_EXT_surface_CTA861_3_metadata EGL_EXT_surface_SMPTE2086_metadata EGL_KHR_get_all_proc_addresses EGL_KHR_swap_buffers_with_damage , Client API: OpenGL_ES)`
- **新失败点精确定位**（tombstone_23，RenderEngine 线程回溯）：
  ```
  SkiaGLRenderEngine::chooseEglConfig(...) → SkiaGLRenderEngine::create(...) → abort
  ```
  即 `eglGetDisplay` 已成功（EGL 实现是 ANGLE——version 字串 `1.4 Android META-EGL` 即 ANGLE 的 EGL 身份），崩溃移到 `eglChooseConfig`：没有任何 EGLConfig 能匹配 format 1。串口日志除 cmdline 外仍零 ANGLE/Vulkan/EGL 提及（与 E1b 相同，tombstone 才是证据载体）。
- **E1c 结论：通过（按用户严格定义）**——属性生效（Loader 走到 ANGLE 路径）+ Abort message 变化。**界限必须写清：这只证明 Loader 走到了 ANGLE 路径且 EGL 初始化到了 eglGetDisplay，不证明 ANGLE 能完全初始化**——没有 Vulkan 后端（vulkan.pastel.so 缺失）就选不出 EGLConfig，这正是 E2 要解决的。E2 的验收仍是：SwiftShader ICD 被 Loader 找到 + ANGLE 初始化成功（能选出 config、RenderEngine 不再于创建时 FATAL）。

---

## §2 构建可行性核查

以下全部为 android17-release 分支的稀疏克隆源码确认（`~/workspace/bootable-ramdisk/work/m5/src-probe/`；分支提交经 ls-remote 核对：swiftshader 9a22bb9d…、minigbm ce79bbee…、drm_hwcomposer 87daba89…）。工具链与 M3 相同：NDK r27c `aarch64-linux-android35-clang++`、平台版 libcxx 头文件、GSI 提取的 .so、aidl 工具、gen-build-list.py、build.sh 模式；M3 的三个坑（§3.5）同样适用。

### 2.1 SwiftShader — 可构建，LLVM 16 是长尾

- **结论：能。** 目标 `vulkan.pastel` 定义于 `external/swiftshader/src/Android.bp:272`（`cc_library_shared`，`vendor:true`，`relative_install_path "hw"` → 安装路径 **`/vendor/lib64/hw/vulkan.pastel.so`**——与 M4 推断的安装路径一致）。另有 `vulkan.pastel.debug` 变体与 ICD JSON 模板 `Vulkan/vk_swiftshader_icd.json.tmpl`。
- 依赖：静态库 `libswiftshadervk_llvm` + `libLLVM16_swiftshader`；共享库 libnativewindow、libhardware、libsync、liblog；静态库 libarect；头文件库 swiftshader_platform_headers、vulkan_headers、hwvulkan_headers；C++ 标准 c++20（已锁定，LLVM 16）；链接参数 `-Wl,-Bsymbolic`。
- 源码体积：完整稀疏检出 648M；其中 llvm-16.0 第三方库 291M、SPIRV-Tools 27M、SPIRV-Headers 4M、marl 908K、astc-encoder 152K；llvm-10.0（203M）与 llvm-subzero 不需要（vulkan.pastel 不用）。SwiftShader 自身源码 113 个 .cpp（M3 量级，小）；llvm-16.0 树 2591 个 .cpp（`libLLVM16_swiftshader` 用的子集，仍是数百个重型编译单元）；SPIRV-Tools 903 个 .cpp（用的子集）。
- **时间预估（诚实估计）：Vulkan 实现本身很小，但 LLVM 16 占主导——本机 4 vCPU / 7GB 预计数小时，有 OOM 风险；用 `-P 2` 构建，像 M3 的 build.sh 那样缓存目标文件。**
- GSI 已提供的链接依赖：libnativewindow.so（68K）、libsync.so（51K）、libvulkan.so（258K，loader）、libEGL_angle.so、libGLESv2_angle.so、libdmabufheap.so（102K）；NDK r27c 自带 Vulkan 头文件（vulkan.h、vk_icd.h、vulkan_android.h）。
- **缺口：`hardware/vulkan.h`（hwvulkan_headers）不在 M3 的 libhardware 克隆里**——E2 需从 platform/hardware/libhardware 单独拉取一个小文件。
- Loader 发现路径（源码确认，frameworks/native/vulkan/libvulkan/driver.cpp，android17-release）：HAL_SUBNAME_KEY_PROPERTIES = {ro.hardware.vulkan, ro.board.platform}；库名 = `"vulkan." + 值 + ".so"` → 经 `android_load_sphal_library` 到 /vendor/lib64/hw/ 的 vulkan.pastel.so；加载失败返回 -ENOENT，Hal::Open 回落到 stub HAL，**不 FATAL**（ANGLE 的 Vulkan 后端随后以自己的错误失败）。

### 2.2 minigbm — 可构建；CONFIG_DMABUF_HEAPS_SYSTEM 缺失不影响

- **结论：能。** 构建目标（android17-release）：
  - allocator 服务 `android.hardware.graphics.allocator-service.minigbm`，定义于 `cros_gralloc/aidl/Android.bp:27`（`cc_binary`，`vendor:true`，→ `/vendor/bin/hw/`，init_rc allocator.rc——服务名 `vendor.graphics.allocator`，user system，group graphics drmrpc，onrestart restart surfaceflinger；VINTF allocator.xml version 3）。依赖：共享库 allocator-V3-ndk、mapper@4.0、libbase、libbinder_ndk、libgralloctypes、libhidlbase、liblog、libminigbm_gralloc、libminigbm_gralloc4_utils；静态库 libaidlcommonsupport；源码 Allocator.cpp + Main.cpp。
  - mapper `mapper.minigbm`，定义于 `cros_gralloc/mapper_stablec/Android.bp:27`（`cc_library_shared`；共享库 allocator-V2-ndk、libminigbm_gralloc；VINTF mapper.minigbm.xml）。
  - `minigbm_cros_gralloc_defaults` 共享库：libcutils、libdmabufheap、libdrm、libgralloctypes、liblog、libnativewindow、libsync；静态库 libarect。
- **缺口：GSI 有 allocator-V2-ndk.so，但没有 allocator-V3-ndk.so**——V3 NDK 代码必须用 aidl 工具从 AIDL 生成（M3 的方法）并随服务一起提供/链接。
- **CONFIG_DMABUF_HEAPS_SYSTEM 问题已回答（源码确认，M4 §7 未核实项之一）：dma_heap 是独立后端（`backend_dma_heap`）；virtgpu.c 完全不引用 dma_heap——它经 virtio-gpu resource ioctl（VIRTGPU_PARAM_RESOURCE_BLOB 等）分配；后端选择按 DRM 驱动名匹配（virtio_gpu → backend_virtgpu）；dma_heap_init 只注册组合并返回 0。缺少 CONFIG_DMABUF_HEAPS_SYSTEM 不阻塞 virtgpu 路径。** GSI 的 libdmabufheap.so 存在（链接依赖可满足）；运行时仅在走 dma_heap 后端的路径上才用。

### 2.3 drm_hwcomposer — 可构建；单 scanout 2D 设备由 GenericBackend 处理

- **结论：能。** 构建目标（android17-release）：服务 `android.hardware.composer.hwc3-service.drm`（`cc_binary`，`vendor:true`，→ `/vendor/bin/hw/`）；VINTF composer3 **version 5**（hwc3-drm.xml）；init rc hwc3-drm.rc（服务名 `vendor.hwcomposer-3`，user system，group graphics drmrpc，capabilities SYS_NICE，onrestart restart surfaceflinger，task_profiles ServiceCapacityLow）。依赖：composer3-V5-ndk、libbinder_ndk、libdrm、libsync、libui、libutils、libbase、libcutils、libhardware、libhidlbase、liblog + 静态库 libaidlcommonsupport、libdisplay_info、libmath。另有 APEX 目标 `com.android.hardware.graphics.composer.drm_hwcomposer`（vendor，soc_specific）——本实验走 vendor.img 直装路线：cc_binary + init_rc + VINTF fragment 直接安装（与 M3 装 keymint 的方式相同）。
- **单 scanout 2D 行为（源码确认，M4 §7 未核实项之一）：`GetBackendByName` 对未知驱动名回落到 "generic"**（virtio_gpu 不在 kClientDevices 列表中：kirin、mediatek-drm、pl111）→ **GenericBackend 处理单 scanout 2D virtio-gpu，无需专用后端。** sdm_backend 仅经 soong 配置开启（非默认）。
- **注意：Composer V5 服务 vs GSI V6 客户端**——Cuttlefish 在 android17-release 上正是配这个 V5 服务与同一 API-37 平台，兼容性为源码级推断，E4 实测验证。

### 2.4 EGL Loader 路径（复核，源码确认）

frameworks/native/opengl/libs/EGL/Loader.cpp（android17-release）：`shouldUseAngle()` 为真 → `attempt_to_load_angle()` → 失败则 `LOG_ALWAYS_FATAL_IF(!hnd, "Failed to load ANGLE.")`；否则走可更新驱动 APK 路径，再走 native 驱动路径（后缀取 ro.hardware.egl；"angle" 后缀 → /system/lib64 的 libEGL_angle.so）。因此 E1c 的预期信号是 tombstone Abort message 脱离“couldn't find an OpenGL ES implementation”——证明 Loader 走到了 ANGLE 路径，**不是** ANGLE 初始化成功。

---

## §3 E2–E6 构建计划

每步的验收都以**日志/命令为准**，文件存在或属性设置成功不算完成。每步只改一个变量；每步产物为独立 vendor 镜像（`vendor-m5-e{N}.img`），回滚 = 换回上一阶段镜像并重跑同一命令。

### E2：SwiftShader Vulkan ICD 被 Loader 找到，ANGLE 初始化成功

工作项（按文件/模块拆分）：
1. 从 platform/hardware/libhardware 拉取 `hardware/vulkan.h`（hwvulkan_headers 缺口）。
2. 稀疏检出 external/swiftshader（android17-release，提交 9a22bb9d…；含 third_party/llvm-16.0、SPIRV-Tools、SPIRV-Headers、marl、astc-encoder；约 648M）。
3. 复用 M3 build.sh 模式交叉编译 `vulkan.pastel`（NDK r27c clang++、平台版 libcxx 头文件、`-nostdinc++`、链接 GSI libc++.so；`-P 2`，缓存目标文件；注意 M3 坑 2：`gen-build-list.py` 的 `"test" in s` 过滤会误删文件，编译单元应为 101 量级概念的完整集合——按 swiftshader 的 Android.bp 实际 srcs 为准）。
4. 安装到 vendor 树：`/vendor/lib64/hw/vulkan.pastel.so` + ICD JSON（`Vulkan/vk_swiftshader_icd.json.tmpl` 生成，指向该路径）；权限 0644；标签按 vulkan HAL 规则（参考 keymint 的标签做法，`hal_*_default` 类模式核对后定）。
5. vendor 镜像：先修复 V2 树权限（§1.1）+ 加 E1c 的 `init.qemu.rc`，再装 vulkan.pastel.so，生成 `vendor-m5-e2.img`。
6. 用 E1b 同款命令重跑（cmdline 6 参数不变）。

验收（以日志为准，缺一不可）：
- tombstone Abort message **不再是**“couldn't find an OpenGL ES implementation…”（E1c 已证明 Loader 走到 ANGLE 路径的前提下）；
- 串口日志或 tombstone 中出现 ANGLE 初始化成功的证据：RenderEngine 线程不再于创建时 FATAL；surfaceflinger 进程**不再**因 RenderEngine 创建失败而 SIGABRT——改为能走到 HWC/Gralloc 缺失的下一道失败（预期：hwcomposer/gralloc 相关错误首次出现），或直接稳定运行；
- `vulkan.pastel.so` 被加载的直接证据（二选一）：tombstone 的加载库列表含该 .so，或日志中 ANGLE/Vulkan 后端初始化行指向 SwiftShader（`SwiftShader` / `pastel` 字样）。
- **不接受**：仅文件存在于 /vendor/lib64/hw/、仅 `ro.hardware.vulkan=pastel` 生效、仅 Abort message 变化——message 变化是 E1c 的验收，不是 E2 的。

回滚：换回 `vendor-m5-e1.img`（E1c 产物）。

### E3：minigbm allocator、mapper 正常工作

工作项：
1. 稀疏检出 minigbm 相关树（android17-release，提交 ce79bbee…）：cros_gralloc（aidl 服务 + mapper_stablec + 共享库）。
2. 交叉编译 `android.hardware.graphics.allocator-service.minigbm` 与 `mapper.minigbm`；用 aidl 工具从 AIDL 生成 allocator-V3-ndk（GSI 缺失，见 §2.2）。
3. 安装：服务 → `/vendor/bin/hw/android.hardware.graphics.allocator-service.minigbm`（0755）；mapper → `/vendor/lib64/hw/mapper.minigbm.so`（0644）；init_rc（allocator.rc）→ `/vendor/etc/init/hw/`；VINTF fragment（allocator.xml v3、mapper.minigbm.xml）→ `/vendor/etc/vintf/manifest/`；标签按 graphics allocator/mapper 规则。
4. 生成 `vendor-m5-e3.img`（基于 E2 镜像，只加 minigbm）。

验收（以日志为准）：
- 串口日志：`vendor.graphics.allocator` 服务启动成功（init `starting service` + 无 SIGABRT/权限拒绝）；`servicemanager` 能查到 `android.hardware.graphics.allocator`（`Found ... in VINTF manifest` 样式行，或 `service list` 类证据）；
- surfaceflinger 日志出现 Gralloc 分配成功的行（分配 buffer 成功，无 `failed to allocate` / mapper 未找到错误）；
- surfaceflinger 不再死于 Gralloc 缺失（失败点继续向后移动到 HWC）。

回滚：换回 `vendor-m5-e2.img`。

### E4：drm_hwcomposer 注册成功，能访问 /dev/dri/card0

工作项：
1. 稀疏检出 drm_hwcomposer（android17-release，提交 87daba89…）。
2. 交叉编译 `android.hardware.composer.hwc3-service.drm`；安装到 `/vendor/bin/hw/`（0755）+ hwc3-drm.rc → `/vendor/etc/init/hw/` + VINTF fragment（hwc3-drm.xml，composer3 v5）→ `/vendor/etc/vintf/manifest/`。
3. vendor build.prop 或 init.qemu.rc 补充 Cuttlefish 的 HWC 相关属性：`ro.vendor.hwcomposer.pmem`、`ro.vendor.hwc.drm.present_fence_not_reliable=true`、`vendor.hwc.drm.device=/dev/dri/card0`（M4 已确认的 Cuttlefish vendor props，按实测调整）。
4. 生成 `vendor-m5-e4.img`（基于 E3 镜像，只加 drm_hwcomposer）。

验收（以日志为准）：
- 串口日志：`vendor.hwcomposer-3` 服务启动成功；servicemanager 能查到 composer3 实例；
- drm_hwcomposer 日志显示打开 `/dev/dri/card0` 成功（`virtio_gpu` 后端识别为 GenericBackend——若出现“unknown backend”之外的回落行即为证据）；
- surfaceflinger 日志出现 HWC 连接成功的行，不再报 hwcomposer 未找到。

回滚：换回 `vendor-m5-e3.img`。

### E5：SurfaceFlinger 稳定、Zygote 不再重启、System Server 启动

工作项：无新增二进制——本步是 E2–E4 集成的验证步；若出现新的缺失 HAL/服务缺口，按缺口逐个补（每次只补一个，重新生成镜像并重跑，缺口清单记入日志）。

验收（以日志为准，缺一不可）：
- surfaceflinger 进程连续运行无 SIGABRT（至少一个完整启动周期内零崩溃；M3/E1b 的对照是每 ~16–19s 一次 SIGABRT）；
- zygote 启动后不再被 surfaceflinger 的 onrestart 杀掉（串口日志无 `restart --only-if-running zygote` 的 onrestart 触发）；
- system_server 启动（`starting service 'system_server'` 且存活，servicemanager/hwservicemanager 稳定）；
- audioserver SIGSEGV 循环为独立问题（缺音频 HAL），不作为 E5 的阻塞项，但须在日志中单独记录其状态。

回滚：换回 `vendor-m5-e4.img`。

### E6：sys.boot_completed=1

工作项：同 E5，无新增二进制；处理启动后期的剩余缺口（Launcher、输入、显示静态帧等），每次只改一个变量。

验收（以日志为准）：
- 串口日志或属性读取证明 `sys.boot_completed=1`；
- 显示静态帧（virtio-gpu 2D 输出一帧，VNC/截屏为证——按当时可用的取证手段）；
- 明确记录：boot_completed ≠ 可交互；Launcher 出现 ≠ 触摸/动画/输入可用；720p ≠ 1080p（M4 已定口径，交互测试与帧率/CPU/内存实测另立任务）。

回滚：换回 `vendor-m5-e5.img`。

---

## §4 未核实事项清单

1. **E1c 已执行并通过**（见 §1.5）：`init.qemu.rc` 的 setprop 桥经启动验证；Abort message 已从 EGL-loader FATAL 变成 `no suitable EGLConfig found`（ANGLE 已加载，卡在 eglChooseConfig）。E2 的前置条件满足。
2. **V2 镜像权限修复已执行并由启动验证**（见 §1.5）：V2 系镜像首次成功启动 keystore2；构建脚本的权限位校验已落地（`work/m5/build-vendor-e1c.sh`）。
3. **ANGLE + SwiftShader 在 API 37 GSI 上的实际初始化**：未验证。ANGLE 版本/命名空间、Vulkan loader 搜索路径、SwiftShader 在 TCG 下的帧率，均无实测数据。
4. **minigbm 在单 scanout 2D 设备上的运行时行为**：后端选择为源码级确认（virtgpu → backend_virtgpu），实际分配未验证。
5. **drm_hwcomposer V5 服务 vs GSI V6 客户端**：Cuttlefish 同平台配对为源码级推断，E4 实测验证。
6. **手机 QEMU 11.1.2 的 -gl / -rutabaga 运行时可用性**：未验证（设备名字符串存在于 libqemu-system-aarch64.so，但构建脚本无 --enable-virglrenderer/--enable-rutabaga，且无 virglrenderer/rutabaga/mesa 依赖）。本路线不依赖它们。
7. **audioserver SIGSEGV 循环**：缺音频 HAL 的独立问题，未定位，未阻塞图形链。
8. **vndk-nodef 安全网静默失效**（§1.1）：`reboot` exec 的 SELinux 标签错误，未修复；与图形链无关，记入待办。
9. **SwiftShader 构建耗时**：LLVM 16 在本机 4 vCPU / 7GB 上预计数小时、有 OOM 风险——为估计值，未实测。
10. **PropertyInit 首阶段预初始化机制**（M2 P3）：假设获强支持（`Overriding previous property`），gdb/strace 定向验证仍欠。

---

## §5 产物清单

| 产物 | 路径 | 说明 |
|---|---|---|
| 本日志 | `~/workspace/bootable-ramdisk/M5-swiftshader-egl-2026-11-15.md` | E1 过程与结论、可行性核查、E2–E6 计划、未核实清单 |
| E1b 脚本 | `~/workspace/bootable-ramdisk/work/m5/run-e1b.sh` | 与 M3-V1 命令相同，仅 cmdline 加 6 个 bootconfig 参数 |
| E1b 串口日志 | `~/workspace/bootable-ramdisk/work/m5/m5-e1b-serial.log` | 3337 行，guest 0–约 215s；9 次 surfaceflinger SIGABRT |
| E1b 后 userdata 副本 | `~/workspace/bootable-ramdisk/work/m5/userdata-e1b-post.img` | fsck 修复后；原始 userdata.img 未动 |
| E1b tombstone | `~/workspace/bootable-ramdisk/work/m5/ts-e1b/tombstone_27.txt`、`tombstone_30.txt` | surfaceflinger RenderEngine，Abort message 与 M3 相同；另 26/28/29/31 为 audioserver |
| 源码探查目录 | `~/workspace/bootable-ramdisk/work/m5/src-probe/` | swiftshader / minigbm / drm_hwcomposer 的 android17-release 稀疏克隆 |
| E1c 构建脚本 | `~/workspace/bootable-ramdisk/work/m5/build-vendor-e1c.sh` | 基于 build-vendor-m3-v2.sh，新增 init.qemu.rc 标签 + 权限位校验 |
| E1c vendor 树 | `~/workspace/bootable-ramdisk/work/m5/vendor-tree-e1c` | V2 树副本，权限修复（0755/0644、setgid 清除）+ `/etc/init/hw/init.qemu.rc` |
| E1c vendor 镜像 | `~/workspace/bootable-ramdisk/work/m5/vendor-m5-e1c.img` | SHA `57dd3ee1…2147b8`；LABEL CHECK OK、PERM CHECK OK |
| E1c 脚本 | `~/workspace/bootable-ramdisk/work/m5/run-e1c.sh` | 与 E1b 同款命令，唯一变量 = vendor-m5-e1c.img |
| E1c 串口日志 | `~/workspace/bootable-ramdisk/work/m5/m5-e1c-serial.log` | 3342 行，guest 0–209.4s；9 次 surfaceflinger SIGABRT，keystore2 零 SIGABRT |
| E1c 后 userdata 副本 | `~/workspace/bootable-ramdisk/work/m5/userdata-e1c-post.img` | fsck 修复后；原始 userdata.img 与 userdata-e1c.img 未动 |
| E1c tombstone | `~/workspace/bootable-ramdisk/work/m5/ts-e1c/tombstone_01/04/07/10/23/26/29.txt` | surfaceflinger，Abort message 全部为新 message；另 8 份为 audioserver |
| 基线副本 | `~/workspace/bootable-ramdisk/work/m5/vendor-m5-base.img`、`vendor-m5-e1.img` | V2 副本，SHA `57b028966069784a…`；基线本体未动 |
| E1b vendor 副本 | `~/workspace/bootable-ramdisk/work/m5/vendor-m5-e1-v1.img` | V1 副本，SHA `769d420cc219c7b3…` |

---

## 结论摘要

1. **E1 未通过**——bootconfig 参数本身到了内核 cmdline，但 `ro.hardware.egl` 等属性没有生效，Abort message 与 M3 一字不差。根因已定位并源码确认：缺 vendor init.rc 的 `setprop` 桥（Cuttlefish/Emulator 的设备配置责任，GSI 与 init 都不代劳；我们的 vendor 缺 `init.qemu.rc`）。
2. **三个组件均可构建**——SwiftShader（vulkan.pastel.so，LLVM 16 是长尾，预计数小时）、minigbm（allocator + mapper；CONFIG_DMABUF_HEAPS_SYSTEM 缺失不影响 virtgpu 路径）、drm_hwcomposer（hwc3-service.drm，GenericBackend 覆盖单 scanout 2D）。工具链与 M3 相同；各有一处小缺口（hwvulkan_headers、allocator-V3-ndk、V5/V6 兼容待验）。
3. **M3-V2 基线镜像有缺陷**（权限位被 `cp -r` 剥掉，keymint 二进制 0750 导致 execv 拒绝）——修复方案已定，未执行；V2 验证前不可用。
4. **下一步建议（按顺序，已更新）**：(a) E1c 已通过——`init.qemu.rc` setprop 桥 + V2 权限修复经启动验证（见 §1.5），Abort message 已变，新失败点是 eglChooseConfig（缺 Vulkan 后端）；(b) 启动 E2 的 SwiftShader 构建（LLVM 16 编译是长任务，先开起来）；(c) 按 E3→E6 逐项实施，每步以日志为准验收。
