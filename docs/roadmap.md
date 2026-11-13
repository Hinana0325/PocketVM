# PocketVM 路线图：移动端通用虚拟机管理平台

> 定稿日期：2026-10-30。定位决定：先做**开发者导向的移动端虚拟化实验室**，
> 普通用户版等硬件加速与 Windows 安装真正跑通后再谈。AI Agent 接口**不做**
> （已明确排除，不在任何阶段中；指远程/云侧控制接口——本地只读、默认关闭、
> 需 token 的调试接口不属于此类，见 `docs/debug-api.md`，已于 2026-11-12 落地）。

## 1. 定位

PocketVM 不只是一个“在手机上运行 Windows 的 QEMU 启动器”，而是 Android
手机上的通用虚拟机管理平台：统一创建、管理、运行不同架构、不同操作系统的
虚拟机。第一版的功能边界按开发者实验室收敛：虚拟机管理、镜像管理、启动
日志、自定义内核与启动参数先行；快照、网络实验室、桌面模式往后放。

目标设备：小米 14 Pro（骁龙 8 Gen 3），未 Root。这是全部取舍的前提。

## 2. 现状基线（2026-11-12 更新；原始基线 2026-10-30）

> 2026-11-12：0.11.0 已发布（Android 17 工具链、Room/DataStore、Hilt/WorkManager、
> Navigation Compose、性能调度、H4 归因与启动报表）；Win11 已在真机启动到桌面；
> 本机只读调试接口已落地；本机与 CI 侧载签名已统一。**同时只能跑一台 VM** 的约束仍在。

- QEMU 11.1.2 + edk2，ARM64 `virt`，TCG 软模拟；`VmSession` / `QemuCommandBuilder` /
  `QmpClient` / `DiskProvisioner` / `FirmwareManager` / `IsoImporter` 已有，QMP 优雅关机、
  SSH 控制台、运行日志（manifest 快照）已有。**同时只能跑一台 VM**（`VmSession` 单例），
  多虚拟机管理是 P3 要打破的约束。
- Windows 11 安装已在真机端到端跑通到 OOBE（2026-11-08：Win11 从磁盘引导进「请稍等」+
  中文语言栏，看门狗 HEALTHY）；此前的「主循环停摆」已定位为 MIUI/HyperOS 的 cgroup
  冻结（非 QEMU 故障），电池设「无限制」后持续解冻，App 已加 H4 采样间隙检测。桌面尚未
  到达，P1 验收（含 minor 升版）仍是人类 owner 的决策（D4）。
- VNC 局域网访问被挡在校验层（`VNC_LAN_UNAVAILABLE`，DES 后端缺失），只有“仅本机 +
  `adb forward`”是通的。2026-10-31 决策 D1=B：下线/隐藏局域网入口并文档化，
  执行层不重建，DES 留到未来重建 QEMU 时再补。
- CI 已绿（setup-android v4，当前只跑 JVM 单测，不产 APK）。自有代码采用 MIT
  （2026-10-31 决策 D2），随包第三方组件见 `THIRD_PARTY_NOTICES.md`。

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
| VM Manager | 创建、删除、复制、启动虚拟机 | **多机同跑第一版已实现**（2026-11-12）：每 VM 独立 QEMU 进程/VNC 端口 + 内存预算 + 保活引用计数；**VM 复制已实现**（停止状态，qemu-img 转干净 qcow2 + vars/TPM 复制，名称自动去重） |
| Image Manager | 管理 ISO、IMG、QCOW2、GSI | 已有 ISO 导入与 qcow2 置备；**GSI 组件拼装已落地**（2026-11-12，实验）：导入 kernel/ramdisk/system/vendor，sparse 自动展开，拼 GPT 物理分区盘（metadata/system/vendor/userdata）并转 qcow2；**PC QEMU 上首阶段→二阶段→zygote 已打通**（同日实验，见 `.workbuddy/memory/2026-11-12.md`），但拼盘遗漏了格式化 userdata，且显示（surfaceflinger / goldfish 图形 HAL）仍卡住，未到 boot_completed；以上未在产品链路验证；**qcow2 ⇄ raw 转换已落地**（2026-11-13）：`qemu-img convert` 写临时文件再原子替换，失败不动原盘；raw→qcow2 是主场景（raw 在 FUSE 上按虚拟容量占用、且不支持快照），qcow2→raw 会先检查空间；未真机验证 |
| Hardware Manager | CPU、内存、磁盘、网络、显示配置 | 已有配置与校验，需补多架构硬件模板 |
| Acceleration | KVM、Hypervisor、Virtio、图形加速 | **只读探测已实现**（2026-11-12，`AccelerationProbe` + 诊断中心「硬件加速」卡 + 调试接口 `/diagnostics`）；KVM 接入未做，TCG 仍是唯一执行路径 |
| Snapshot | 快照、恢复、备份 | **停止态快照已实现**（2026-11-12）：qcow2 内部快照（秒级，不复制整盘）+ vars.fd / TPM 副本，恢复前自动保险快照；**运行时快照已实现**（2026-11-13）：QMP HMP `savevm`/`loadvm`，不用关机、含内存与设备状态，但**不含** UEFI 变量与 TPM（恢复后保持当前值）；两者均未真机验证 |
| Console | VNC、SPICE、串口、远程桌面 | VNC（仅本机）与 SSH 已有；**客户机串口控制台已实现**（2026-11-12：`-serial unix` 交互式读写，Linux `console=ttyAMA0` 可用，Windows 需开 EMS）；**SPICE 不可用**：执行层未编入 spice-server、App 无客户端，需重建执行层+移植客户端；局域网 VNC 入口已下线（D1=B） |
| Debug API | 本机只读 HTTP：状态/日志/诊断/快照 | 已实现（`127.0.0.1:18080`，默认关，需 token；真机联调待做） |

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
只读诊断，接入决定看探测结果，不预设结论。（2026-11-12：只读探测已落地，见 `android/app/src/main/java/dev/pocketvm/runtime/AccelerationProbe.kt`。）

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
| P0 | 0.1–0.10.36（已结束） | 执行环境与启动链：QEMU ARM64 启动器、UEFI/固件自愈、诊断与控制台；已形成基线 |
| P1 | 0.11.0（已发布，2026-11-12，owner 决策升 minor 并打 tag） | Windows ARM 启动与虚拟磁盘管理；Win11 ARM64 完整安装端到端跑通一次；主循环停摆可观测/归因；以真机证据为准；另含 Android 17 工具链、性能调度、导航迁移（Navigation Compose）、数据层（Room/DataStore）、Hilt/WorkManager 官方技术栈迁移 |
| P2 | 0.12+（待定） | Android GSI 启动与日志分析（真机复验，不以主机 QEMU 结果代替） |
| P3 | 待定 | 多虚拟机管理、快照、网络（NAT/端口转发/多 VM 互联） |
| P4 | 待定 | 硬件加速能力探测与接入（以前一阶段的探测结论为准） |
| P5 | 1.0（待定） | 完整移动虚拟机管理器；桌面模式与虚拟磁盘工具视 P3–P4 余量决定 |

版本节奏（2026-10-31 决策 D4）：0.11.0 是 P1 里程碑；日常合并只升 patch，
P1 验收通过才升 minor 并打 tag，历史 tag 不改。P2 之后的版本号是暂定值，
随阶段启动再确认。

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

- Windows 11 完整安装：已到 OOBE（2026-11-08 真机），桌面与首次设置尚未走完；P1 验收与 minor 升版由人类 owner 拍板（D4）。主循环停摆已定位为系统冻结（H4，非 QEMU 故障），不再是未决问题；真正的 QEMU 内部停摆（H1/H2/H3）尚未在真机复现。
- VNC 局域网恢复：需在重建执行层时补 DES 后端（nettle/gnutls），暂无排期（D1=B 已下线入口）。
- KVM 可用性：以只读探测结果为准，未探测前不承诺。
- 发布签名：当前只有 debug/perf（debug 签名）包，生产签名与发布流水线未落地。
