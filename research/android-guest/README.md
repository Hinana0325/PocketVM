# Android Guest 实验记录

PocketVM Android Guest（Android 作为独立 Guest 平台，QEMU virt + 自建 Vendor 层）的研究与实验记录。路线：Route B（标准 QEMU virt + 自建 Vendor），不移植 Ranchu。

实验在 PC QEMU（AOSP prebuilt QEMU 10.2.90，ARM64 virt，TCG，`-cpu max`）上进行，目标是 Android 17 GSI 启动到 `sys.boot_completed=1`。

## 文档

- [PocketVM-Android-Guest技术方案.md](PocketVM-Android-Guest技术方案.md) — 技术方案（M1–M6 划分，修订两次）
- [Pixel-11-Pro-XL工厂镜像分析.md](Pixel-11-Pro-XL工厂镜像分析.md) — Pixel 11 Pro XL 工厂镜像分析（init_boot.img 的 first-stage init 是关键发现）

## 实验日志（按时间顺序）

| 日志 | 内容 | 结果 |
| --- | --- | --- |
| [M1-step1-2026-11-15.md](M1-step1-2026-11-15.md) | M1 第一步：GSI init second-stage 攻关（E1–E32） | second-stage init + init.rc 运行，终点 `bootstrap-apexd-failed` |
| [M1-pixel-firststage-2026-11-15.md](M1-pixel-firststage-2026-11-15.md) | Pixel first-stage init 移植验证（P1–P6） | 原生链 `first-stage init → mount → second-stage init` 跑通 |
| [M2-vendor-apex-2026-11-15.md](M2-vendor-apex-2026-11-15.md) | M2 Vendor SELinux 标签 + APEX Bootstrap | 标签修复（AVC 27→0）、APEX 通过；Zygote 未启动（KeyMint 缺失） |
| [M3-keymint-2026-11-15.md](M3-keymint-2026-11-15.md) | M3 Software KeyMint HAL（AOSP 源码构建） | keystore2 稳定，post-fs-data 全通；surfaceflinger SIGABRT 循环 |
| [M4-graphics-research-2026-11-15.md](M4-graphics-research-2026-11-15.md) | M4 图形栈适配研究（Cuttlefish 参考） | 根因：找不到 EGL 实现；路线 A（VirtIO GPU 2D + SwiftShader）唯一短期可行 |
| [M5-swiftshader-egl-2026-11-15.md](M5-swiftshader-egl-2026-11-15.md) | M5 SwiftShader + ANGLE 最小图形链路（E1/E1c） | E1c 通过：setprop 桥打通，Abort message 变化；下一步 E2 构建 `vulkan.pastel.so` |

## 当前状态（2026-11-15）

启动链已到：QEMU virt → Pixel first-stage init → mount/switch_root/SELinux enforcing → second-stage init → init.rc → APEX bootstrap → Software KeyMint → post-fs-data → Zygote 启动。当前阻塞：SurfaceFlinger 找不到 EGL 实现而 SIGABRT 循环，System Server 与 `boot_completed` 未到达。

## 注意事项

- Pixel 工厂镜像已从主开发路线退出，仅作启动链参考样本；最终产品由 AOSP 源码构建自己的 virt 专用启动组件，不依赖 Pixel 专有二进制。
- 日志中的实验产物（ramdisk、vendor 镜像、串口日志）不在本仓库中，路径见各日志内文。
