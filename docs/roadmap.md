# PocketVM 路线图：移动端通用虚拟机管理平台

> 定稿日期：2026-10-30。定位决定：先做**开发者导向的移动端虚拟化实验室**，
> 普通用户版等硬件加速与 Windows 安装真正跑通后再谈。AI Agent 接口**不做**
> （已明确排除，不在任何阶段中）。

## 1. 定位

PocketVM 不只是一个“在手机上运行 Windows 的 QEMU 启动器”，而是 Android
手机上的通用虚拟机管理平台：统一创建、管理、运行不同架构、不同操作系统的
虚拟机。第一版的功能边界按开发者实验室收敛：虚拟机管理、镜像管理、启动
日志、自定义内核与启动参数先行；快照、网络实验室、桌面模式往后放。

目标设备：小米 14 Pro（骁龙 8 Gen 3），未 Root。这是全部取舍的前提。

## 2. 现状基线（2026-10-30，仓库 main）

- QEMU 11.1.2 + edk2，ARM64 `virt`，TCG 软模拟；`VmSession` / `QemuCommandBuilder` /
  `QmpClient` / `DiskProvisioner` / `FirmwareManager` / `IsoImporter` 已有，QMP 优雅关机、
  SSH 控制台、运行日志（manifest 快照）已有。**同时只能跑一台 VM**（`VmSession` 单例），
  多虚拟机管理是 P3 要打破的约束。
- Windows 11 安装程序窗口已在真机上到达（0.10.10，ramfb），但完整安装流程尚未端到端跑通；
  主循环停摆是最大的未决问题（真机可复现，QMP/VNC 无响应，vCPU 线程空转）。
- VNC 局域网访问被挡在校验层（`VNC_LAN_UNAVAILABLE`，DES 后端缺失），只有“仅本机 +
  `adb forward`”是通的。
- CI 已绿（setup-android v4）。LICENSE 未选。

## 3. 支持的操作系统与架构

| 方向 | 内容 | 优先级 |
| --- | --- | --- |
| Windows ARM | Windows 10/11 ARM、虚拟硬盘管理、UEFI 启动、键盘/鼠标/触控映射 | 优先 |
| Linux | Ubuntu、Debian、Arch、Alpine 等；SSH、终端、桌面环境 | 早期验证 |
| Android | Android GSI 启动与日志分析、多版本测试 | P2，见第 6 节 |
| 其他 | FreeBSD/OpenBSD、DOS、RISC-V Linux、x86_64 模拟 | 扩展，不进 P0–P2 |

## 4. 六大模块与状态

| 模块 | 功能 | 状态 |
| --- | --- | --- |
| VM Manager | 创建、删除、复制、启动虚拟机 | 已有基础，需补复制与多虚拟机管理 |
| Image Manager | 管理 ISO、IMG、QCOW2、GSI | 已有 ISO 导入与 qcow2 置备，需补 GSI 与格式转换 |
| Hardware Manager | CPU、内存、磁盘、网络、显示配置 | 已有配置与校验，需补多架构硬件模板 |
| Acceleration | KVM、Hypervisor、Virtio、图形加速 | 未做；见第 5 节，先探测、后接入 |
| Snapshot | 快照、恢复、备份 | 未做 |
| Console | VNC、SPICE、串口、远程桌面 | VNC 与 SSH 已有；SPICE、串口未做；VNC 局域网受限 |

## 5. 硬件抽象层（最值得做的部分）

```text
PocketVM
├── Virtual Machine
│   ├── CPU / Memory / Display / Storage / Network / Input
├── Virtual Hardware
│   ├── ARM virt
│   ├── x86 PC
│   └── RISC-V virt
└── Hypervisor Backend
    ├── QEMU TCG（现实默认）
    ├── Android KVM（仅当设备与权限实际可用时）
    └── Native virtualization backend（研究）
```

现实约束：小米 14 Pro 的 CPU 支持虚拟化，不等于普通 Android 应用能调用 KVM。
未 Root 条件下默认只有 TCG；硬件加速是一个独立研究项目，**不作为第一个版本
的阻塞条件**。加速能力探测（`/dev/kvm` 是否存在、内核配置、权限）先做成
只读诊断，接入决定看探测结果，不预设结论。

## 6. 与其他项目的关系（项目分离，不可混淆）

- **OriginOS 7 移植是独立项目**，不在 PocketVM 仓库内。PocketVM 的 Android GSI
  支持只是“用 GSI 做启动测试场景”，两者不互相记账。
- x86 主机 QEMU 上的 Android 17 内核/GSI 启动工作（first-stage init、vendor
  sepolicy 定位）结论可作参考，但**搬进手机应用需在真机上重新验证**，不直接
  算作 P2 完成。
- xin-xi-tong（含 13 个 replacement 系统应用）是另一个独立项目，与 PocketVM 无关。

## 7. 分阶段路线

| 阶段 | 版本 | 验收目标 |
| --- | --- | --- |
| P0 | 0.1 | QEMU ARM64 启动器，稳定进入 Linux，启动日志完整可查 |
| P1 | 0.2 | Windows ARM 启动与虚拟磁盘管理；安装流程端到端跑通一次 |
| P2 | 0.3 | Android GSI 启动与日志分析（真机复验，不以主机 QEMU 结果代替） |
| P3 | 0.4 | 多虚拟机管理、快照、网络（NAT/端口转发/多 VM 互联） |
| P4 | 0.5 | 硬件加速能力探测与接入（以前一阶段的探测结论为准） |
| P5 | 1.0 | 完整移动虚拟机管理器；桌面模式与虚拟磁盘工具视 P3–P4 余量决定 |

特色功能（移动桌面模式、虚拟磁盘工具、启动实验室、网络实验室）按上表排期，
不单独插队；其中启动实验室（自定义内核、initramfs、DTB、启动参数）是 P2 的
一部分。

首页形态目标（P5）：正在运行的虚拟机、CPU/内存/存储占用、快速创建、最近运行
的系统、镜像库、启动日志——而不是把 QEMU 命令行直接塞进应用。

## 8. 参考实现

Vectras VM（GitHub 开源，QEMU 安卓前端，多系统、多架构、VNC）是同类产品的
现有实现。动手前先读它的实现，借鉴 VNC 后端、存储权限、后台保活的坑，避免
重造一遍已解决的问题；PocketVM 的差异点在硬件抽象层与开发者实验室工具链，
不在“又一个 QEMU 前端”。

## 9. 未决事项

- LICENSE 未选（只覆盖自有代码，不含 QEMU/edk2 分发义务）。
- 主循环停摆、Windows 11 完整安装：需真机验证，QEMU-only 范围内无法确认。
- KVM 可用性：以只读探测结果为准，未探测前不承诺。
