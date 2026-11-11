# 排障

校验器与日志归因共用同一套错误码。界面显示的是中文原因，这里给的是「怎么修」。

## 校验错误（启动前就被拦住）

| 代码 | 原因 | 处理 |
| --- | --- | --- |
| `QEMU_MISSING` | `nativeLibraryDir` 里没有 `libqemu-system-*.so` | 跑 `scripts/install-qemu.sh`，确认 APK 内含 `lib/arm64-v8a/libqemu-system-aarch64.so` |
| `CPU_RANGE` | 核心数不在 1–8 | 改为 4 左右；TCG 不吃多核 |
| `MEM_TOO_SMALL` | < 1024 MB | Windows 装不上，至少 2048 |
| `MEM_EXCEEDS_DEVICE` | > 设备内存 75% | 12 GB 机型建议 4096–6144 |
| `DISK_TOO_SMALL` | < 20 GB | Windows 11 ARM64 展开后约 20 GB |
| `DISK_NOT_WRITABLE` | 虚拟机目录不可写 | 检查 `Android/data/dev.pocketvm/files` 是否被清理工具锁定 |
| `FIRMWARE_MISSING` | `firmware/` 为空 | 导入 `QEMU_EFI.fd`（edk2 aarch64 pflash） |
| `FIRMWARE_IMPLAUSIBLE` | 文件 < 256 KB | 多半是把 `QEMU_EFI.fd` 的 git-lfs 指针文件下下来了 |
| `FIRMWARE_VARS_SIZE_MISMATCH` | vars 与 code 不等长 | 删除 `vms/<id>/vars.fd` 让其按 code 大小重建（会丢启动项） |
| `ISO_NOT_FOUND` | 路径失效（重装/移动） | 重新导入 ISO |
| `VNC_LAN_UNAVAILABLE` | 选了局域网模式；当前 QEMU 构建缺少 DES 加密后端，VNC 密码认证无法启动 | 改回「仅本机」，用 `adb forward` 查看画面；恢复局域网需重建 QEMU 时编入支持 DES 的加密后端 |
| `DISK_FREE_LOW`（警告） | 剩余 < 2 GB | qcow2 精简置备但写时增长 |

## 启动后失败（QEMU 日志归因）

`FailureHints` 用日志正则匹配，按优先级取第一条：

| 日志特征 | 含义 |
| --- | --- |
| `Permission denied` | 从可写目录执行被 SELinux 拒绝；必须以 `lib*.so` 打进 APK |
| `Exec format error` | 打包了 x86_64 或 armv7 版本 |
| `No such file or directory` | 缺动态库或路径错；查 `LD_LIBRARY_PATH` |
| `Failed to get "write" lock` | 上次 QEMU 未退出，残留进程占用磁盘 |
| `Address already in use` | VNC 端口冲突，换 5901+ |
| `Cannot allocate memory` | 内存 + TCG 翻译缓存超限 |
| `No space left on device` | 存储满 |
| `pflash` / `invalid size` | 固件与 vars 不等长 |

退出码：`137` 说明被系统 SIGKILL（内存压力或省电策略），`139` 是段错误，`132` 是 SIGILL
（USB 存储栈，或 0.10.10 之前的 PAC 缺陷——见下）。

## 常见现象

**「虚拟机跑了几十秒到几分钟后自己关闭」**
QEMU 被 SIGILL 杀死（exit=132），App 侧只有 `EXITED_ERROR` + `failureHint`。两个来源：

1. **USB 存储栈**：本机（骁龙 8 Gen3）上 `usb-storage` 起步即崩。把「光驱总线」改成
   `virtio-scsi+AHCI`，或打开编辑页的「USB 总线 → 禁用」（后者连 xHCI 都不创建）。
2. **ARMv9 PAC**（0.10.10 之前的执行层）：bionic 的 `sigsetjmp/siglongjmp` 用**每线程**
   APIA 密钥给 SP/LR 签名，QEMU sigaltstack 协程跨线程首次 enter 时 `autiasp` 校验失败，
   FEAT_FPAC 直接 SIGILL。tombstone 停在 `libc longjmp+460`，`x0=x1=3`（`COROUTINE_ENTER`）。
   0.10.10 起执行层内置无签名协程切换补丁；构建前 `verifyQemuPacPatch` 会按 SHA256 校验，
   重建 QEMU 后忘记补丁会直接构建失败。

**界面显示「QEMU 启动后立即退出」但日志为空**
fork 成功但 QEMU 在打开日志前就死了，常见于 `libqemu-system-aarch64.so` 依赖缺失。
用 `readelf -d` 看 `NEEDED`，或换成静态链接的构建。

**卡在黑屏、QMP 已连接**
UEFI 在跑但没找到可启动设备。检查 ISO 是否被识别为 CD-ROM（控制台里看 argv 是否有
`scsi-cd,drive=cd0`），以及 `-boot order=d`。

**安装程序提示「找不到磁盘」**
选了 virtio 控制器，而安装程序没有 viostor 驱动。改回 NVMe（默认），或先用
virtio-win ISO 加载驱动。

**安装程序停在「这台电脑无法运行 Windows 11」**
缺 TPM 2.0 / Secure Boot。QEMU 需要外挂 swtpm，当前未打包。按 Shift+F10 打开命令行：

```
reg add HKLM\SYSTEM\Setup\LabConfig /v BypassTPMCheck /t REG_DWORD /d 1 /f
reg add HKLM\SYSTEM\Setup\LabConfig /v BypassSecureBootCheck /t REG_DWORD /d 1 /f
```

关掉窗口返回上一步再继续。注意这需要键盘输入能力（P1）。

**虚拟磁盘占满了手机存储**
确认 `disk.qcow2` 是 qcow2 而不是 raw（控制台里看「虚拟盘已用」是否远小于配置容量）。
qcow2 删掉文件即完全回收；guest 内删除文件不会自动缩小镜像，需要 `qemu-img convert`
或 discard/TRIM。

**Windows 安装到一半重启后回到安装界面**
启动顺序仍是 CD 优先。安装完成后在向导里关掉「从 ISO 启动」，或用 `bootFromCd=false`
重新生成配置；UEFI 变量存储里已经有 `BootOrder` 时，pflash 会优先按 NVRAM 走。

**客户端连不上 VNC**
先确认 VM 处于「运行中」——VNC 端口只在 QEMU 进程活着时存在。仅本机模式必须
`adb forward`。局域网模式暂不可用：选了它会在启动前被校验器以 `VNC_LAN_UNAVAILABLE` 拦下。

**「虚拟机卡死几分钟又自己恢复」（系统冻结，2026-11-08 真机证实）**
形态：VNC/QMP 失联、画面定格，但 QEMU 进程活着；过一会儿不操作也自行恢复。
这不是 QEMU 主循环停摆，而是 **MIUI/HyperOS 的 cgroup freezer 把 App 与 QEMU
所在进程组整体冻结**（`cgroup.freeze=1`）。冻结期间所有线程 parked（`main_state=S`、
`syscall=-1`），App 自己的看门狗也同窗停采；解冻即恢复。屏幕亮着、App 有前台
服务（FGS）也可能被冻——PowerKeeper 的省电策略不看这些。

- **App 内检测**：App 的 SELinux 域读不到 `cgroup.freeze`（实测 Permission denied），
  所以看板用**采样间隙**推断：`dt_ms > 3×interval_ms` 记 `process_suspended=true`，
  归因卡显示「H4 · 系统冻结进程」并提示去电池设置设「无限制」。间隙本身不开
  QEMU 异常窗口（冻结不是 QEMU 故障）。
- **shell 侧确认**（adb shell 可读 cgroup，但跨 UID 读不了 `/proc/.../syscall`）：
  `cat /sys/fs/cgroup/apps/uid_<uid>/pid_<appPid>/cgroup.freeze`，`1`=冻结中；
  `cgroup.events` 里 `frozen 1` 同义。uid 看 `ps -A -o UID` 或 `dumpsys package`。
- **修复**：设置 → 应用设置 → 电池 → 把 PocketVM 设为「无限制」（MIUI/HyperOS）。
  实测设完后 `cgroup.freeze` 持续为 0、VNC 常活、看门狗连续增长。`adb shell` 侧
  的 doze/appops 调整拦不住 PowerKeeper 反复冻结，只有 per-app 电池白名单有效。

**VNC 连上但黑屏**
固件还没初始化显卡（启动前 10–30 秒正常），或 `virtio-gpu` 未被 edk2 驱动。
日志里出现 `Display output` 相关字样前不要判断为故障。

**没有画面（App 内预期行为）**
App 不内置渲染器。`adb forward tcp:5900 tcp:5900` 后用桌面 VNC 客户端连
`127.0.0.1:5900`。局域网模式暂不可用，不要切到「局域网」。

## 运行期看门狗（run-*.watchdog.jsonl）

主循环停摆的形态：QEMU 进程活着、VNC 端口仍在 LISTEN、TCP 握手能完成，但主循环
不再 accept，VNC/QMP 一个字节都不回，vCPU 线程继续满核转。`run-*.log` 这时是空的，
App 也可能还显示「运行中」。看门狗每 5 秒采一拍，把这一过程写成时间线。

**位置与打包**

- 设备上：`vms/<vmId>/logs/run-<runId>.watchdog.jsonl`（每次运行独立文件；超过 1 MiB
  轮转一代 `.1`）。
- 诊断包 zip 内：`vm/runs/run-<runId>.watchdog.jsonl`，总是随包导出（不只在异常时）。
- App 内不轮询文件：`VmSession.diag`（快照）与 `VmSession.diagEvents`（最近 20 条事件，
  新→旧）由同一拍数据映射，供「控制台 → 诊断」Tab 使用。

**JSONL 字段（合并门禁以 T4 §6.2 为准）**

| 字段 | 含义 |
| --- | --- |
| `ts_utc` | ISO-8601 UTC 时间戳，对齐主机/设备时钟 |
| `vm_id` / `run_id` / `qemu_pid` | 归属与进程号 |
| `qmp_rtt_ms` / `qmp_ok` / `qmp_status` | `query-status` 往返耗时与结果；超时为 null/false |
| `qmp_error` | QMP 探测失败的显式原因（如 `query-status timeout after 1500 ms`） |
| `vnc_connect_ok` / `vnc_banner` / `vnc_rtt_ms` / `vnc_error` | TCP 是否连上、是否收到 `RFB 003.008` |
| `serial_total_bytes` / `serial_delta_bytes` | 串口日志字节数与本窗口增量；无串口捕获时为 null |
| `main_thread_dticks` / `vcpu_dticks[]` | `/proc/<pid>/task/<tid>/stat` 的 utime+stime 增量（100 Hz 下满核≈500 tick/5 s 窗口）；读不到为 null |
| `main_cpu` / `vcpu_cpu[]` / `vcpu_sum` | 每线程 CPU 占比（单核=100）；优先 schedstat，stat 回退 |
| `main_state` / `vcpu_states[]` | 主线程与各 vCPU 的 stat 第 3 字段状态字符（`R` 运行 / `S` 可中断睡眠 / `D` 不可中断 I/O 睡眠 / `Z` 僵尸）；读不到为 null |
| `main_syscall` / `vcpu_syscalls[]` | `/proc/<pid>/task/<tid>/syscall` 原始行（syscall 号 + 参数 + sp + pc，或 `running`）；SELinux 拒绝时为 null |
| `proc_readable` / `proc_error` | 主线程 /proc 是否可读及失败原因 |
| `dt_ms` / `interval_ms` | 与上一拍的实际间隔 / 期望采样间隔；首拍 `dt_ms=null` |
| `process_suspended` | `dt_ms > 3×interval_ms` 时为 true：进程曾被系统整体挂起（冻结的间接证据，见「系统冻结」条目） |
| `window_anomaly` / `window_id` / `window_samples` | 是否处于异常窗口、第几个窗口、窗口内第几拍 |
| `signals` | 本拍硬信号：`qmp_timeout` / `vnc_no_banner` / `proc_unreadable` / `qmp_status_<状态>` |
| `stall_profile` | `main_idle_vcpu_busy` 表示主线程空闲而 vCPU 仍繁忙（归因线索，不是单独异常） |
| `verdict` | `SAMPLING` / `HEALTHY` / `WATCH` / `SUSPECTED_STALL` / `STALLED` / `UNKNOWN` |

**阈值与判定（代码常量：`WatchdogDetector` companion）**

- 采样间隔 `5000 ms`；以下硬信号**连续 2 拍（约 10 秒）**才开窗：
  `qmp_timeout`、`vnc_no_banner`、`proc_unreadable`、`qmp_status_<非 running>`。
- 主线程 `< 5%` 且 vCPU 合计 `≥ 25%` 记为 `main_idle_vcpu_busy`；
  **它单独不是异常**——健康的 QEMU 主线程本来就常驻 `poll()` 睡眠，guest 计算时
  vCPU 满核是常态。
- `SUSPECTED_STALL`：窗口内 QMP 或 VNC 失联；`STALLED`：失联且同时出现
  `main_idle_vcpu_busy`。

**判读方法**

1. 先找 `window_anomaly=true` 的行，看 `window_id` 起止与 `signals`。
2. 典型停摆：`qmp_timeout` + `vnc_no_banner` + `main_cpu` 接近 0 + `vcpu_sum` 高
   + `vcpu_dticks` 仍有大数值 —— 控制面已死、vCPU 还在空转。
3. QMP/VNC 正常、只是 `main_cpu` 低 + `vcpu_sum` 高：**正常** guest 计算。
4. `serial_delta_bytes` 连续为 0 不等于停摆：UEFI/桌面空闲期串口本来就可能不输出；
   必须结合 QMP/VNC。
5. `main_thread_dticks`/`vcpu_dticks` 在部分 Android 16 设备上可能读到线程组聚合值
   （同一数值）；此时以 `main_cpu`/`vcpu_cpu`（schedstat）为准。
6. 采样故障一律是显式字段（`qmp_error` / `vnc_error` / `proc_error`）或 null，
   不会写成 0。
7. **先排除系统冻结**：`process_suspended=true`（或 `dt_ms` 远超 `interval_ms`）
   说明 App 进程自己都被挂起过，该窗口的 VNC/QMP 失联是冻结的结果——直接按
   「系统冻结」条目处理，不要往 QEMU 内部阻塞归因。
8. **阻塞类型判别（停摆归因第一手量）**：确认停摆后看 `main_state` 与 `main_syscall`——
   - `main_state=D`（不可中断 I/O 睡眠）⇒ 主线程卡在同步块 I/O，多半是 FUSE 上的
     qcow2 写/flush；`main_syscall` 通常以 `68`（pwrite64）/`82`（fsync）开头（arm64 号）。
   - `main_state=S` 且 `main_syscall` 以 `98`（futex）开头 ⇒ 卡在锁（MTTCG 的 BQL）；
     再看 `vcpu_states[]` 里是否恰有一个 vCPU 是 `D`——那就是持 BQL 却卡在 FUSE I/O
     的线程，主线程抢不到 BQL 因而永不 accept。
   - `main_state=S` 且 `main_syscall` 是 `73`（ppoll）/`22`（epoll_pwait）⇒ 主线程其实
     在正常 poll，需另查监听 fd 是否还在 poll 集（不是经典停摆）。
   - `main_syscall=null` 表示该设备 SELinux 不放行 `/proc/.../syscall`；此时仅凭
     `main_state` 的 `D`/`S` 也能粗分「I/O 阻塞」与「锁/poll 阻塞」两类。
   异常窗口打开时，`mainState` / `mainSyscall` / `vcpuStates` 也会进 `agent.jsonl`
   的 `watchdog.window_open` 事件，外部不读 zip 即可看到。

**停摆归因结论（App 自动判读，只读）**

确认停摆（`SUSPECTED_STALL` / `STALLED`）后，App 把上面第 8 条的人工判读固化成
`StallAttributor`（`diagnostics/StallAttribution.kt`）的纯函数结论，显示在「控制台 →
诊断」的「停摆归因」卡，并写进「复制判读摘要」。它**只解释已采集的证据**，不重算
窗口阈值、不替代真机复现；建议一律是只读对照或后续实验方向，不在 App 内执行动作。
采样间隙触发的 H4 不要求停摆判定——解冻后信号常已恢复 HEALTHY，看板仍会显示
「系统冻结归因」卡。

| 结论标签 | 触发证据 | 含义 / 只读建议 |
| --- | --- | --- |
| `H1 · vCPU 持锁卡 I/O` | 主线程 `S`+futex(98)，且某 vCPU=`D` | vCPU 持 BQL 后卡在 FUSE 同步 I/O，主线程抢不到锁；结论会点名该 vCPU 的 syscall（pwrite64/fsync）。Track B 用 `thread=single` 复现对照 |
| `H2 · 主线程卡 I/O` | 主线程=`D` | 主线程自身阻塞在同步块 I/O（FUSE 上的 qcow2 写/flush）；对照磁盘位置（内部 ext4 vs 外部 FUSE）与 cache 模式 |
| `H3 · 主线程等锁` | 主线程 `S`+futex(98)，无 vCPU=`D` | MTTCG/BQL 锁等待，PAC 协程 shim 也可能 park 主循环；`thread=single` 对照区分锁反转，仍停摆则需 QEMU backtrace（Track C） |
| `H4 · 系统冻结进程` | `process_suspended=true`（采样间隙 >3×窗口），优先于线程证据 | MIUI/HyperOS cgroup freezer 把 App 与 QEMU 同组冻结，非 QEMU 故障（2026-11-08 真机证实）；电池设置设「无限制」，shell 侧查 `cgroup.freeze` 确认 |
| `正常 poll 等待` | 主线程 `S`+ppoll(73)/epoll_pwait(22) | 本拍主线程在正常等事件，未捕获阻塞点；对照异常窗口连续多拍 |
| `主线程在 CPU 上` | 主线程=`R` 或 syscall=`running` | 非阻塞型停摆，可能主循环死循环；需 QEMU 内部 backtrace |
| `主线程其他睡眠` | 主线程 `S`+其他 syscall | 非典型阻塞点；对照完整时间线 |
| `证据不足` | `main_state=null`（SELinux 拒绝）或非常规态 | 无法归因；在能读 /proc 的环境复现 |

`H1`/`H2`/`H3` 是假设编号，不是已证实的根因——收敛仍要在真机复现停摆时读到这些
证据，并按建议做 Track B/C 对照。`H4` 已在真机证实（见「系统冻结」条目）。
syscall 号是 arm64 通用号（`StallAttributor.syscallName`
解码：98=futex、73=ppoll、22=epoll_pwait、68=pwrite64、82=fsync…）。

**停摆时点「停止」**

`VmSession.stop()` 为 `NonCancellable`，QMP 调用均有超时（powerdown/quit 各 3 s），
随后按 25 s 关机等待 → destroy → SIGKILL 逐级降级，最坏约 42 秒，阻塞段全部在
IO 线程，不占主线程。用户停止前会先取消看门狗，关机过程中的 VNC/QMP 失联不会被
记成异常窗口。

## 抓诊断

控制台 → 导出诊断包，得到 zip：`device.txt`、`qemu-probe.txt`、`config.json`、每次运行的
`run-*.log`、`run-*.serial.log`、`run-*.watchdog.jsonl` 与 `run-*.json`（含完整 argv 与退出码）。
这比让用户复述现象可靠得多。

