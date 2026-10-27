# 架构

## 分层

```
Compose UI (ui/)
    ↓ 只调用 ViewModel
VmViewModel ── 持有 UiState，不碰进程
    ↓
VmSession (runtime/) ── 唯一的进程所有者：校验 → 置备 → fork → 确认 → 监督
    ├─ QemuEnvironment   找到可执行文件并 *真的执行一次* 验证
    ├─ QemuCommandBuilder 纯函数，argv 可单测
    ├─ QmpClient         Unix socket 上的 QMP，用于优雅关机
    ├─ FirmwareManager   pflash code/vars 的等长约束
    ├─ DiskProvisioner   只产出 qcow2，没有 raw 回退
    └─ RunManifestStore  启动前写盘的 argv 快照
```

UI 与执行层的唯一耦合是 `VmRuntimeState` 这个密封接口：

```kotlin
Idle → Preparing(step) → Running(pid, port) → Stopping → Exited(code, hint)
                       ↘ Failed(reason, hint)
```

`VmSession` 放在 `AppContainer`（Application 级），不在 ViewModel 里。旋转屏幕、返回列表、
甚至 Activity 重建都不会碰到 QEMU 进程；UI 只是附着在状态流上。

## 一次启动的时序

| 步骤 | 动作 | 失败时的后果 |
| --- | --- | --- |
| 1 | `QemuEnvironment.resolve()` 扫描 `nativeLibraryDir`，执行 `--version` | `QEMU_MISSING`，附带每个候选的失败原因 |
| 2 | `ConfigValidator` 校验内存/固件/ISO/权限 | 阻止 fork，返回结构化错误列表 |
| 3 | `FirmwareManager.prepare()` 校验 code/vars 等长，缺失则按 code 大小创建 | 不等长 → 明确提示删除 vars 重建 |
| 4 | `DiskProvisioner.ensure()` qemu-img 或内置 QCOW2 写入器，只产出 qcow2 | 置备失败 → 中止，不产生半成品 manifest |
| 5 | 构建 argv | 纯函数，无失败路径 |
| 6 | **写 manifest**（argv / 固件 / 磁盘 / 日志路径） | 崩溃也留痕 |
| 7 | `ProcessBuilder` fork，stdout+stderr 重定向到日志 | fork 失败 → `LAUNCH_FAILED` |
| 8 | 连接 QMP socket（最长 20s）确认 | 连不上说明机器初始化失败，立即归因 |
| 9 | 进入 `Running`，启动监控协程等 `waitFor()` | 退出后写 exit code + hint |

第 8 步是关键设计：进程活着不代表虚拟机活着。QEMU 在 machine init 阶段 abort 时，
进程可能还存活数百毫秒，只看 `isAlive` 会误报「启动成功」。QMP greeting 是第一个
可靠的「已进入主循环」信号。

## 为什么用 QMP 而不是 kill

Windows 客户机写盘时被 SIGKILL，qcow2 的 refcount 表可能停留在不一致状态。
关机顺序：`system_powerdown`（ACPI，等同按电源键）→ 25s 宽限 → `quit` → 5s → `destroy()`。
宽限期给的是「Windows 正在关闭」的时间，不是给 QEMU 的。

## 关键决策

**ADR-001 执行层用独立进程而不是 JNI 内嵌**
QEMU 崩溃（段错误、OOM）不应该带走 UI；独立进程还能拿到真实 exit code 与完整日志。
代价是无法共享内存、需要 Unix socket 通信。

**ADR-002 显示走 VNC 而不是 `-display none` + 自绘**
`virtio-gpu` + VNC 是 QEMU 在无 GPU 环境下的成熟路径，且与后续 RFB 客户端解耦。
VNC 只绑 `127.0.0.1`，不暴露到局域网。

**ADR-003 每台 VM 独立的 pflash vars**
共享 vars 会让两台 VM 的启动项互相污染，且并发写会破坏 flash 镜像。
代价是每台 VM 多占一份固件大小（64 MB）的空间。

**ADR-004 磁盘格式只用 qcow2，不降级**
`qemu-img` 是第二个二进制，打包失败或缺少依赖时不应让整个 MVP 不可用。
检测到没有 `qemu-img` 时由内置写入器直接写 QCOW2 v3 头，始终只产出 qcow2，
没有 raw 回退：raw 落在 FUSE 上可能真的吃掉全部虚拟容量。

## 已知技术债

1. `Screen` 用手写状态而不是 Navigation Compose —— 单 Activity 三屏，暂不值得引入依赖。
2. 日志每 1s 轮询尾部 64 KB，未做增量 offset；VM 长时间运行后建议改成 inotify 或读取偏移。
3. `VmSession` 的监控协程未在进程退出后 join，极端情况下 `Exited` 会晚于 UI 重建。
4. 没有持久化「上次异常退出」标记，冷启动后无法区分「用户停止」与「崩溃」。
