# M2 Vendor 与 APEX Bootstrap — 实验日志

日期：2026-11-15。任务：PocketVM Android Guest M2 阶段——修复 Vendor SELinux 标签，验证 APEX bootstrap，再验证 Zygote / System Server，最后专项验证 PropertyInit。

**结论总览：P0 ✅ 标签修复（AVC 27→0）｜ P1 ✅ APEX bootstrap 通过（44 扫描 / 4 激活 / exit 0）｜ P2 ❌ zygote 未启动（两阻塞已定位：post-fs-data 停滞 + keystore2 无 KeyMint）｜ P3 假说获强证据支持（first-stage 预初始化 property area，定向验证仍欠）。启动链从 P5 的 apexd 阻塞推进到 post-fs-data + keystore2/KeyMint。**

基线（M1 Pixel first-stage 实验 P5/P6，已验证）：`Pixel first-stage init → first-stage mount → switch_root → SELinux policy → second-stage init → init.rc → apexd-bootstrap` 全链路已运行。唯一直接阻塞：vendor.img 没有正确的 SELinux 文件标签，apexd 无法访问 `/vendor/odm/apex`（P5 日志：`Can't open /odm/apex for reading: Permission denied`，27 条同模式 AVC，vendor 分区全是 `unlabeled`）。

约束：不改 PocketVM 仓库；不改 Pixel 工厂镜像 zip（只读）；不把 Pixel 专有二进制放入分发物；不下载；不动 `~/workspace/gsi/` 原始 zip；每次实验只改一个变量；不以 SELinux permissive 作为验收方案（P6 已证明 cmdline 两条路都走不通）。

用户架构判断：下一阶段围绕标准 AOSP Vendor 实现推进，不要继续围绕 Pixel 工厂镜像打补丁；Pixel 镜像已完成参考使命，最终产品由 AOSP 源码构建自己的 virt 专用启动组件。

## 阶段 0 — 标签方案核查（已验证，动手前完成）

### 现状
- vendor.img（P5 版，64 MiB ext4）只含 `/etc/selinux/` 下 6 个文件：`plat_sepolicy_vers.txt`（37.0）、`precompiled_sepolicy`（522,021 B，v33）、3 个 `precompiled_sepolicy.*.sha256`、 `vendor_property_contexts`（377 B）。**无 `/odm` 目录**，无 `vendor_file_contexts`。
- `debugfs ea_list /` 确认：全分区无任何 `security.selinux` xattr（`unlabeled` 的来源）。
- 启动时 init 加载的 file contexts 只有 3 个（P5 日志 23.86s）：`/system/etc/selinux/plat_file_contexts`、`/system_ext/etc/selinux/system_ext_file_contexts`、`/product/etc/selinux/product_file_contexts`——**没有 vendor_file_contexts**，因为 vendor.img 里没有这个文件。
- 启动时 restorecon（P5 日志 25.35s `Running restorecon...`）之后 /vendor 仍是 unlabeled——**经验证：本启动链的 restorecon 不覆盖 /vendor**。因此离线写 xattr 是可靠修复手段，启动时不会被改写。

### 标签映射的依据（全部以本机实物核对，非凭记忆）
- GSI `plat_file_contexts`（system.img 内，启动时实际加载）：
  - `/vendor(/.*)? → vendor_file`（行 392）
  - `/vendor/etc(/.*)? → vendor_configs_file`（行 402）
  - `/odm/apex`、`/vendor/odm/apex`、`/vendor/apex`（`(/[^/]+){0,2}`）`→ vendor_apex_file`（行 467–470）
  - `/vendor/etc/selinux/*` **无专门规则**——按最长匹配会落到 `vendor_configs_file`；Pixel 的 plat_file_contexts 同样无此规则（只在 447–448 行有 `vendor_service_contexts → vendor_service_contexts_file`）。
- Policy 允许规则（GSI `plat_sepolicy.cil`，即已加载的 precompiled_sepolicy 的源码）：
  - `(allow domain vendor_file (dir (getattr search)))`——**所有域（含 apexd、ueventd）都可 search vendor_file 目录**。这是 apexd 能遍历 /vendor 的关键。
  - apexd 可 search/read `vendor_apex_file` 的 dir/file/lnk_file；可 search `metadata_file`、`system_data_file` 等。
  - ueventd 对 `vendor_file_type`（`base_typeattr_1105 = (and (vendor_file_type))`）有 dir search/read + file read/map/open；`vendor_configs_file`、`vendor_apex_file` 均属 `vendor_file_type`（CIL typeattributeset 已核对）。
  - init 对 `vendor_file_type` 有 dir search/read + file read/map/open；对 `sepolicy_file`、`file_contexts_file` 有 file read；对 `vendor_file` 有 dir mounton。
  - `vendor_file`、`vendor_configs_file`、`sepolicy_file`、`vendor_apex_file`、`file_contexts_file`、`property_contexts_file` 6 个类型均在 policy 中存在（CIL `(type …)` 各 1 条）。
- `/odm` 解析：system.img 根的 `/odm` 是真实空目录（非符号链接，debugfs 已确认）；ramdisk 的 `/odm` 也是目录。但 P5 日志证明打开 `/odm/apex` 时遍历经过了 vde 根目录（AVC：`name="/" dev="vde" ino=2`），且报错是 EACCES 而非 ENOENT——**已验证：运行态的 /odm 解析到 /vendor/odm**（机制推测为 first-stage init 创建符号链接，待有空时核对；不影响标签方案，因为 /vendor/odm/apex 的标签按 plat 规则 468 仍是 vendor_apex_file）。

### 最终标签映射（离线 xattr + 新增 vendor_file_contexts，二者一致）
| 路径 | 标签 | 依据 |
|---|---|---|
| `/`（即 /vendor） | `vendor_file` | GSI plat 392 |
| `/etc` | `vendor_configs_file` | GSI plat 402 |
| `/etc/selinux` | `sepolicy_file` | 用户指令 + AOSP 策略文件惯例（见“与 plat 规则的差异”） |
| `/etc/selinux/plat_sepolicy_vers.txt` | `sepolicy_file` | 同上 |
| `/etc/selinux/precompiled_sepolicy` | `sepolicy_file` | 同上 |
| `/etc/selinux/precompiled_sepolicy.*.sha256`（×3） | `sepolicy_file` | 同上 |
| `/etc/selinux/vendor_property_contexts` | `vendor_configs_file` | GSI plat 402（init 可读 vendor_configs_file，M1/P5 已证实 PropertyInit 能读它） |
| `/etc/selinux/vendor_file_contexts`（新增） | `file_contexts_file` | 显式自规则；init 有 file_contexts_file 读权限 |
| `/odm`（新增目录） | `vendor_file` | GSI plat 392（`/vendor(/.*)?`） |
| `/odm/apex`（新增目录） | `vendor_apex_file` | GSI plat 468（`/vendor/odm/apex…` 最长匹配）；apexd 可 search/read 该类型 |

**与 plat 规则的差异（已记录，非疏漏）：** GSI 与 Pixel 的 plat_file_contexts 都没有 `/vendor/etc/selinux/* → sepolicy_file` 的规则，按最长匹配这些文件应为 `vendor_configs_file`。本次按用户指令标为 `sepolicy_file`，理由：(1) AOSP 对策略文件用 sepolicy_file 是既定惯例（/system、/odm、/system_ext 的策略文件均如此）；(2) init 对 sepolicy_file 有明确读权限，功能上与 vendor_configs_file 等价；(3) 本启动链的 restorecon 不覆盖 /vendor，离线标签不会被启动时改写。**若将来某次启动 restorecon 开始覆盖 /vendor，这些文件会被重标为 vendor_configs_file——功能上仍可读，不构成阻塞。**

**禁止事项遵守：** 没有把整个 vendor 分区统一标为 vendor_file；每个目录/文件按规则分别标注。

### 新增 vendor_file_contexts（virt 专用最小版，只用 policy 中存在的类型）
写入 vendor.img 的 `/etc/selinux/vendor_file_contexts`，内容：
```text
# virt 专用最小版 vendor_file_contexts（PocketVM Android Guest M2）
# 来源：GSI plat_file_contexts 的 /vendor 规则 + AOSP 策略文件惯例；只用已加载 policy 中存在的类型。
# 启动时 init 的 SelinuxSetup 会加载它（P5 日志只加载了 plat/system_ext/product 三个，因为本文件不存在）。
/vendor(/.*)?                              u:object_r:vendor_file:s0
/vendor/etc(/.*)?                          u:object_r:vendor_configs_file:s0
/vendor/etc/selinux(/.*)?                  u:object_r:sepolicy_file:s0
/vendor/etc/selinux/vendor_file_contexts    u:object_r:file_contexts_file:s0
/vendor/odm/apex(/[^/]+){0,2}              u:object_r:vendor_apex_file:s0
/vendor/apex(/[^/]+){0,2}                  u:object_r:vendor_apex_file:s0
```
（注：`/vendor/odm/apex` 与 `/vendor/apex` 的规则与 GSI plat 467–470 重复，保留是为了让 vendor_file_contexts 自包含、意图明确；最长匹配结果与 plat 一致，无冲突。）

## 阶段 1 — 修复 Vendor SELinux 标签

### V1 实验（m2-v1-serial.log，441 行，~25s）：回归——停在比 P5 更早的位置

与 P5 唯一区别：vendor.img 换成带离线标签的版本（V1 映射：`/etc/selinux` 目录也标为 sepolicy_file）。结果：**比 P5 更早失败**，停在 second-stage 的 PropertyInit：

```text
init: Failed to read /vendor/etc/selinux/plat_sepolicy_vers.txt: Permission denied
init: Could not read vendor SELinux version
init: InitFatalReboot: signal 6   (SelinuxGetVendorAndroidVersion ← PropertyInit ← SecondStageMain)
```

AVC 只有 2 条（P5 是 27 条），但都是新的：

```text
avc: denied { search } ... scontext=u:r:kernel:s0 ... name="selinux" dev="vde" ino=13 tcontext=u:object_r:sepolicy_file:s0 tclass=dir
avc: denied { search } ... scontext=u:r:init:s0   ... name="selinux" dev="vde" ino=13 tcontext=u:object_r:sepolicy_file:s0 tclass=dir
```

**根因（已用 CIL 核对）：目录标签与文件标签的权限不同。** `sepolicy_file` 只有 init 的 file-read 规则，没有任何域的 dir-search 规则（`(allow domain sepolicy_file …)` 不存在；init 也只有 `(allow init sepolicy_file (file …))`）。把 `/etc/selinux` 这个**目录**标为 sepolicy_file 后，init（和 selinux_setup 子进程）连进都进不去，更读不到里面的 `plat_sepolicy_vers.txt`。同时 vendor_file_contexts 也因此没被加载（"Loaded file context from" 仍只有 plat/system_ext/product 3 个）。

**教训：目录必须用“可 search”的类型。** 核对后确认：`(allow domain vendor_configs_file (dir (… search)))`——所有域都可 search vendor_configs_file 目录；`(allow domain vendor_file (dir (getattr search)))` 同理。`sepolicy_file` 只能用于文件，不能用于目录。

### V1 修正（单变量：只改 `/etc/selinux` 目录标签）
- `/etc/selinux` 目录：`sepolicy_file` → **`vendor_configs_file`**（与 GSI plat 规则 402 一致；所有域可 search）。
- 目录内文件不变：策略文件仍为 `sepolicy_file`（init 有明确的 file-read 规则；selinux_setup 子进程在 policy 加载前读 precompiled_sepolicy，不受 enforcing 约束）。
- 新 vendor.img SHA 前 16 位 `3cc95be967843e5e`（V1 版 `5407ac8f43259f41` 留作对照，P5 原版 `bb02f44ef1fbf61a`）。

### V2 实验（m2-v2-serial.log，1071 行，~73s）：标签修复成功，APEX bootstrap 通过

与 V1 唯一区别：`/etc/selinux` 目录标签 `sepolicy_file` → `vendor_configs_file`（文件仍为 sepolicy_file）。结果：

- **AVC 拒绝：0 条**（P5 的 27 条、V1 的 2 条全部消失）。
- **vendor_file_contexts 被加载**（"Loaded file context from" 第 4 个，selinux_setup 与 second-stage 各一次）。
- **APEX bootstrap 完全成功**：扫描 44 个预装 APEX；激活 4 个 bootstrap APEX（com.android.i18n / runtime / virt / tzdata），loop 设备挂载 ext4 + erofs 可见；`OnBootstrap done, duration=4076`；`Service 'apexd-bootstrap' exited with status 0`；linkerconfig 生成，/apex/*.rc 解析。
- 但启动链在 keystore2 处终止（见阶段 3）。

### V3 实验（m2-v3-serial.log，988 行，~76s）：vendor 补齐 build.prop + VINTF 清单，zygote 的 rc 导入成功

与 V2 唯一区别：vendor.img 内容增补（重建自 vendor-tree-m2，权限修正为目录 0755/文件 0644）：
- 新增 `/vendor/build.prop`：仅 `ro.zygote=zygote64`（标签 vendor_file）。
- 新增 `/vendor/etc/vintf/manifest.xml`：空 HAL 列表的最小 device manifest（标签 vendor_configs_file）。
- `/odm/apex` 空目录保留；其余标签与 V2 相同。新 vendor.img SHA 前 16 位 `431cfa04eac710dd`（V2 版备份为 vendor-v2.img）。

结果：
- **AVC 拒绝：0 条**（连续两轮零拒绝）。
- **ro.zygote 生效**：`init.zygote64.rc`、`drmserver.zygote64.rc`、`mediaserver.zygote64.rc` 均被导入解析（V2 时因 ro.zygote 缺失而 import 失败）。
- **VINTF 清单解析成功**：`servicemanager: Successfully processed VINTF information`；HAL 查找变为干净的 "not found"（如 `Could not find android.hardware.boot.IBootControl/default … No alternative instances declared in VINTF`），不再是 parse error。DAC 权限修正后 servicemanager 可遍历 /vendor/etc。
- **APEX bootstrap 再次成功**（4 个包，exit 0）。
- 但 zygote 仍未启动、keystore2 仍崩溃（见阶段 2、3）。

### 附带发现（V2/V3 共有，非阻塞）
- `init.vndk-nodef.rc`：GSI 检测到 vendor 无 VNDK，尝试 `exec /system/bin/reboot bootloader`，因 SELinux domain transition 缺失而失败（`File /system/bin/reboot … has incorrect label or no domain transition from u:r:init:s0`）。启动因此未被中断，但这是个隐患：若 domain transition 某天被补上，设备会在启动早期重启。Vendor 层需提供 VNDK 或明确处理。
- `/cache` 只读：fstab 无 /cache 项，挂载后 /cache 落在只读的 system 镜像上，post-fs 的 chown/chmod/mkdir 全部失败（`Read-only file system`）。非致命，但 Vendor fstab 应补 /cache（或确认走 /data/cache）。
- vold：`Failed to open default fstab: No such file or directory`（vendor 无 fstab.* 文件），继续运行。Vendor 应提供 `/vendor/etc/fstab.qemu`。

## 阶段 2 — APEX Bootstrap：✅ 通过

- 扫描：44 个预装 APEX（/system/apex 38 个、/system_ext/apex 6 个；/odm/apex、/vendor/apex、/product/apex 为空或不存在，符合预期）。
- 激活：4 个 bootstrap APEX 挂载成功（loop 设备，ext4 + erofs）。
- 退出码：0；`perform_apex_config` 成功；linkerconfig 生成。
- 结论：P1 完成。vendor 标签问题已解决，不再是启动链阻塞。

## 阶段 3 — Zygote 与 System Server：❌ 未通过（两个独立阻塞）

### 阻塞 A：post-fs-data 动作在 init.rc:681 之后停住（zygote-start 永远等不到 odsign）

证据链（V3 日志 + userdata.img 事后检查）：
1. post-fs-data（init.rc:629）的命令日志停在 54.29s 的 `exec vdc keymaster earlyBootEnded`（init.rc:681）；此后 20 秒内 **没有任何** init action 命令日志（`start logd-reinit`、`restart apexd`、`copy`、`exec_start derive_sdk/art_boot`、`start odsign` 全部缺失），且 `processing action` 行在 54s 后完全消失。
2. 但 init 主循环活着：keystore2 的 5 次重启、servicemanager 的控制消息在此期间正常处理。——说明阻塞点是一个 **会 pump 事件循环的等待**（即 `wait_for_prop`），而不是死锁主线程。
3. post-fs-data 内含 4 个 wait_for_prop：930 行 `ro.casefolding.adjusted`、996 行 `apexd.status`、999 行 `keystore.module_hash.sent`、1032 行 `odsign.key.done`。而 `on zygote-start`（1061 行）又在等 `odsign.verification.done`。
4. 事后检查 userdata.img：`/data/misc/keystore` 存在（681 行之前创建），但 `/data/property`、`/data/apex`、`/data/media`、`/data/misc/odsign`、`/data/misc/bootanim`、`/data/system/environ` **全部不存在**。——动作停在 681 行之后的第一批命令之前：即 `mkdir /data/property 0700 root root encryption=Require`（687 行）或 `load_persist_props`（688 行）。
5. `/data` 的 fstab 行**没有 fileencryption 标志**（纯 ext4，无 FBE）。`mkdir … encryption=Require` 假定 FBE 可用；在本环境它无法完成加密策略设置。确切的内核级阻塞机制尚未用 gdb/strace 定向验证（候选：fscrypt 策略 ioctl 等待密钥、或 load_persist_props 等待异步持久属性加载——init 二进制含 `ro.persistent_properties.ready` 相关字串），**是下一轮首要验证点**。
6. 即使越过 687/688，后面还有三道同类等待：930 行 casefolding（`casefolding_remover` 二进制**在 GSI /system/bin 中存在**，已核实——此前“二进制缺失”的判断是 debugfs 不跟随 /bin → /system/bin 符号链接造成的误判，特此纠正）、996/999 行 apexd.status 与 keystore.module_hash（依赖 keystore2/KeyMint，见阻塞 B）、1032 行 odsign.key.done。

### 阻塞 B：keystore2 无 KeyMint 可用，SIGABRT 循环 → InitFatalReboot（每次运行的硬上限 ~74s）

- keystore2 启动 5 次，每次启动后 ~2–5s 收到 SIGABRT；第 4 次崩溃后 init 触发 InitFatalReboot（`critical window=0`，keystore2.rc 明文写着“crash 4 次即 reboot to bootloader”）。
- 二进制证据（从 system.img 提取 /system/bin/keystore2，strings）：`Trying to find keymint for security level:`（globals.rs:220）、`Terminating due to KeyMint not accepting module info, blocking boot:`、`Failed to get KeyMint device`、`getKeyMintDevice`、`/TEE KeyMint failed to return RootOfTrust info:`。keystore2 在启动时必须向 KeyMint 发送模块信息（对应 post-fs-data 999 行 `wait_for_prop keystore.module_hash.sent true`）；无 KeyMint 则终止。
- 全系统核查：GSI 的 /system/bin、/system_ext/bin、44 个 APEX 中**没有任何 KeyMint HAL 二进制或服务**；VINTF 清单为空。vold 也在反复等待 keystore2 提供的 `android.security.maintenance`（lazy 服务启动失败）。
- tombstone 未能回收：crash_dump64 每次 `exited with status 1`，userdata.img 上无 /data/tombstones 内容（目录由 post-fs-data 创建但 tombstone 未写入）。精确的 abort 位置（哪一行 FATAL）未拿到——但机制已由二进制字串 + 行为双重确认。
- **后果**：每次运行在 ~74s 被强制重启，掩盖了任何需要更长时间才能观察的现象（包括阻塞 A 的超时行为）。修复 KeyMint 是下一阶段的前提。

### 阶段 3 小结
- zygote 服务定义已导入（V3），但 **zygote 从未启动**（无 `starting service 'zygote'`、`app_process64`、`system_server` 日志）。
- servicemanager、hwservicemanager、vold、logd、lmkd、tombstoned、system_suspend、ueventd、prng_seeder、aconfigd 均已启动并稳定运行。
- system_server 未启动是 zygote 未启动的直接后果，不是独立问题。

## 阶段 4 — PropertyInit 专项验证：领先假说得到支持，未完全证明

问题：P5（及 V2/V3）未挂载 vdd 仍能初始化 property area，而 M1 wrapper 必须用 ext4 vdd 绕过内核缺 CONFIG_TMPFS_XATTR。

V2/V3 证据：
- vdd（virtio3，128 MiB）在 5.65s 被内核识别，但**整轮日志中没有任何挂载 vdd 的记录**；property 服务仍正常工作（socket 创建、ro.cold_boot_done 已设置、wait_for_prop 正常）。
- 决定性旁证：second-stage 启动时出现 3 条 `Overriding previous property … with new value …`（27.06s）——说明这些属性在 second-stage 的 PropertyInit 之前**已经存在**，即 first-stage init 已创建并填充了 property area。second-stage 见文件已存在便只映射、跳过 fsetxattr。
- 结论：**领先假说成立的可能性很高**（first-stage init 预初始化 property area），但尚未用 gdb/strace 定向验证 first-stage 创建 /dev/__properties__ 文件的过程；**内核缺 CONFIG_TMPFS_XATTR 的问题只是被绕过、并未解决**——任何需要在运行时新建 property 文件的路径仍会撞上它。M2 的 P3 任务至此为“假说已获强证据支持，定向验证仍欠”。

## 当前启动链架构（V3 终点）

```text
QEMU 8.2.2 virt, -cpu max, TCG, GKI 6.18.21
  └─ Pixel first-stage init (static, init_boot.img)
       ├─ first-stage mount: /metadata /system /data /vendor (vda–vde) OK
       ├─ 预初始化 property area（证据：second-stage 见已有属性值）
       ├─ switch_root → SELinux policy 加载，enforcing=1
       └─ exec /system/bin/init second_stage
            └─ second-stage init (GSI)
                 ├─ init.rc 解析（含 init.zygote64.rc，ro.zygote 已生效）✅
                 ├─ SELinux restorecon，vendor_file_contexts 已加载 ✅
                 ├─ apexd-bootstrap：44 APEX 扫描，4 个激活，exit 0 ✅
                 ├─ servicemanager / hwservicemanager / vold / logd 等启动 ✅
                 ├─ VINTF 清单解析成功（空 HAL 列表）✅
                 ├─ post-fs-data：停在 init.rc:681 之后 ❌（阻塞 A）
                 ├─ keystore2：无 KeyMint，SIGABRT ×4 → InitFatalReboot ~74s ❌（阻塞 B）
                 ├─ zygote：从未启动（被阻塞 A/B 卡住）❌
                 └─ system_server / boot_completed：未到达 ❌
```

与 P5 对比：启动链从“apexd 读不到 /odm/apex”推进到了“post-fs-data + keystore2/KeyMint”；AVC 从 27 条降到 0 条。

## 下一阶段计划（M3 前置 + Vendor 层）

按阻塞顺序（先解开运行时间上限，再解开动作停滞）：

1. **软件 KeyMint HAL（最高优先，M2→M3 的关键路径）**：从 AOSP 源码构建 software KeyMint（system/security/keymint 的软件后端，如 Cuttlefish/模拟器所用），以 vendor HAL 形式提供 + 写入 VINTF manifest。无它则 keystore2 永远 fatal-reboot、FBE 无法启用、post-fs-data 的 keystore.module_hash 等待永远不满足。替代验证（在构建前）：确认 GSI 是否在任何位置藏有 KeyMint（已查 system/bin、system_ext/bin、44 个 APEX——无；Pixel vendor_boot 的 KeyMint 是 Tensor 专有，不可入分发物）。
2. **post-fs-data 停滞定向验证**：gdb/strace 跟踪 init 在 init.rc:687–688 的行为，确认是 `mkdir … encryption=Require` 的 fscrypt ioctl 阻塞还是 `load_persist_props` 等待 `ro.persistent_properties.ready`。若系 FBE 缺失所致，Vendor fstab 需补 fileencryption（但 FBE 本身依赖 KeyMint，见 1）或预建目录；注意 keystore2 的 74s 上限会截断长超时观察，先做 1 再做 2。
3. **Vendor fstab 补齐**：`/vendor/etc/fstab.qemu`（vold 找 fstab 失败的警告），并补 /cache 项（当前 /cache 只读）。
4. **VNDK**：提供最小 VNDK 或处理 init.vndk-nodef.rc 的 reboot 尝试（当前靠 SELinux domain transition 缺失才没重启，是隐患）。
5. **zygote/system_server 验证**：阻塞 A、B 解除后，观察 zygote 启动、fork、system_server 启动、servicemanager/hwservicemanager 稳定性；再进入 M3 图形栈（minigbm + drm_hwcomposer + SwiftShader 五项验证，见技术方案）。
6. **PropertyInit 定向验证（收尾）**：gdb/strace 确认 first-stage 创建 /dev/__properties__ 的过程；评估 CONFIG_TMPFS_XATTR 缺失对其他服务的实际影响（是否需要重编内核加回）。

## 交付物核对（本任务的 6 项）

1. ✅ Vendor SELinux 标签修复报告——见“阶段 1”（V1 回归分析 + V2 修复 + V3 增补）。
2. ✅ APEX bootstrap 实验日志——见“阶段 2”（44 扫描 / 4 激活 / exit 0）。
3. ✅ Zygote / System Server 启动结果——见“阶段 3”（未通过，两阻塞，证据链完整）。
4. ✅ PropertyInit 根因分析——见“阶段 4”（假说获强证据支持，定向验证仍欠）。
5. ✅ 当前启动链架构图——见上。
6. ✅ 下一阶段 HAL 与图形栈实施计划——见上。

## 实验产物清单

| 文件 | 说明 |
|---|---|
| work/m1-pixel/vendor-p5.img | P5 原版 vendor.img（无标签）备份 |
| work/m1-pixel/vendor-m2.img | M2-V1 版（/etc/selinux 误标 sepolicy_file） |
| work/m1-pixel/vendor-v2.img | M2-V2 版（标签修复，0 AVC，APEX 通过）备份 |
| work/m1-pixel/vendor-v3.img | M2-V3 版（+build.prop + vintf 清单 + 权限修正）备份 |
| work/m1-pixel/vendor.img | 当前 = V3 版（SHA 前 16 位 431cfa04eac710dd） |
| work/m1-pixel/vendor-tree-m2/ | V3 版 vendor 源码树（可复现构建） |
| work/m1-pixel/m2-v1-serial.log | V1 串口日志（441 行，回归） |
| work/m1-pixel/m2-v2-serial.log | V2 串口日志（1071 行，APEX 通过） |
| work/m1-pixel/m2-v3-serial.log | V3 串口日志（988 行，zygote rc 导入 + VINTF 成功） |
| work/m1-pixel/m1-p3.cpio.gz | 沿用 P5 的 ramdisk（未改动） |
| work/m1-pixel/fstab.qemu.final | 沿用 P5 的 fstab（未改动） |
