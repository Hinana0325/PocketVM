# PocketVM

在未 Root 的小米 14 Pro（Snapdragon 8 Gen 3 / arm64）上，用 **QEMU TCG** 跑 Windows ARM64。
0.1.x 只做一件事：**把启动链做成可重复验证的**，而不是先堆界面。

当前代码对应计划中的 **P0（执行环境）** 与 **P1 的前半段**：

| 能力 | 状态 |
| --- | --- |
| QEMU 9.2.3 aarch64 静态二进制（TCG + VNC + slirp + pixman） | **已编入 APK**（32 MB，零未解析符号） |
| QEMU 可执行文件探测（真跑 `--version`，不只看文件存在） | 已实现 |
| 配置校验 + 可操作的中文错误 | 已实现 |
| QCOW2 磁盘创建（无 `qemu-img` 时用内置写入器，始终精简置备） | 已实现 |
| UEFI pflash code/vars 管理（校验等长） | 已实现 |
| QEMU 参数构建（virt + NVMe/USB + VNC + QMP） | 已实现，含单元测试 |
| 进程监督、优雅关机（ACPI→quit→kill）、exit code | 已实现 |
| 每次启动的 manifest（argv/固件/磁盘/退出码） | 已实现 |
| 日志尾部读取 + 失败归因 + 诊断包 zip | 已实现 |
| VNC 服务（端口 / 仅本机 / 局域网 / 密码） | 已实现，App 不内置渲染器 |
| GPU / KVM 加速 | 不在范围 |

## 为什么 QEMU 必须叫 `libqemu-system-aarch64.so`

Android 10 起 W^X 生效：`filesDir` 里的文件即使 `chmod +x` 也会 `EACCES`。
唯一被允许的可执行路径是 APK 的 `lib/` 目录（`ApplicationInfo.nativeLibraryDir`，只读、可执行）。
所以执行层的打包方式是：

```
app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so   # 其实就是 qemu-system-aarch64
app/src/main/jniLibs/arm64-v8a/libqemu-img.so              # 可选，qemu-img
```

`app/build.gradle.kts` 里必须保留 `jniLibs.useLegacyPackaging = true`，否则 so 不会解压到磁盘，无法 exec。

`QemuEnvironment` 会按 `libqemu-system*` 前缀扫描该目录，逐个执行 `--version`，把成功解析出版本号的那个作为执行层，并记录失败原因。

## 虚拟磁盘：256 GB 只占实际大小

`DiskProvisioner` 只产出 qcow2，**没有 raw 回退**。原因是 `/Android/data` 位于 FUSE
（sdcardfs），稀疏空洞不保证被保留，一个 256 GB 的 raw 文件可能真的吃掉 256 GB 闪存。
qcow2 的稀疏性是格式自带的：文件长度 = 元数据 + 已分配簇，与虚拟容量无关。

优先用 `qemu-img create`；没打包 `qemu-img` 时由 `Qcow2Creator` 直接写 QCOW2 v3 头
（header + refcount table + refcount block + 一张空的 L1 表，共 256 KiB）。
QEMU 在 guest 首次写盘时才分配 L2 表和数据簇，refcount 表不够用时自己扩容。

已用真实 `qemu-img` 交叉验证（单元测试 `Qcow2CreatorTest`）：

```
$ qemu-img info --output=json our-256g.qcow2
"virtual-size": 274877906944, "cluster-size": 65536, "format": "qcow2", "dirty-flag": false
$ qemu-img check our-256g.qcow2
No errors were found on the image.
物理占用：262144 字节（256 KiB）
```

界面里可以直接看到「虚拟盘已用 X MB / 256 GB」，这个数字就是 `disk.qcow2` 的真实长度。

## VNC 服务

这个 App 不显示画面。QEMU 自带的 VNC 服务就是产品对外的「显示接口」，你用任何 RFB 客户端连它。

| 模式 | 绑定 | 用途 |
| --- | --- | --- |
| 仅本机 | `127.0.0.1` | `adb forward tcp:5900 tcp:5900` 后用桌面客户端连 `127.0.0.1:5900`；设备上其他 App 也可访问 |
| 局域网 | `0.0.0.0` | PC 直接连 `手机IP:5900`，控制台里会显示当前 Wi-Fi/USB 共享的 IPv4 |

密码不是可选项而是安全前提：局域网模式的 VNC 认证是 RFB 的 DES 方案，密钥只有 8 个字符，
只能当「防误连」用，不能当安全边界。启动流程是
`-vnc 0.0.0.0:N,password=on` → QMP 握手 → `set_password protocol=vnc`，密码在 UI 宣布
「运行中」之前就已经装好，不存在无密码的窗口期。

端口即 display：`5900` 对应 QEMU 的 `:0`，`5901` 对应 `:1`。改端口时 builder 会自动换算。

## 装 Windows 的前提条件

这三件事决定了「插上 ISO 点启动就能装」能否成立：

1. **磁盘必须是 NVMe，光驱必须走 USB。** Windows 安装程序自带 `stornvme` 和 USB 大容量
   存储驱动，但不带 virtio。默认配置就是 `-device nvme` + `-device usb-storage`；
   选 virtio 会更快、支持 TRIM，但安装界面会看不到磁盘，需要手动加载 `viostor`。
2. **Windows 11 要求 TPM 2.0。** QEMU 自身不模拟 TPM，需要外挂 `swtpm`，PocketVM 没有打包，
   所以原版 Windows 11 安装程序会停在「这台电脑无法运行 Windows 11」。
   两条出路：换 Windows 10 ARM64 镜像，或在安装界面按 Shift+F10 用注册表 `LabConfig`
   绕过（需要键盘输入，属于 P1）。
3. **容量与内存。** Windows 11 要求 64 GB 起，装完实际占用约 25–35 GB，建议 128 GB；
   内存低于 4096 MB 时安装程序展开镜像容易失败。

## 构建

```bash
# 需要 JDK 17+、Android SDK 35
cd android
./gradlew :app:assembleDebug      # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest  # 参数构建与校验的单元测试
```

首次运行不需要手动放任何文件：64 MiB 固件随 APK 发布，64 MiB 空白变量存储由 `FirmwareManager` 生成
（edk2 会在首次启动时格式化它）。压缩后这份 64 MiB 镜像在 APK 里只占约 1.2 MB，因为大部分是零。
如果你想换固件（例如 64 MB 的 secboot 版本），在界面里「导入固件」覆盖即可。

```
Android/data/dev.pocketvm/files/
├── firmware/QEMU_EFI.fd     # 从界面“导入固件”，或手动放入
└── images/*.iso             # 界面导入时自动复制到这里
```

## 执行层（QEMU）

APK 里带的是**动态链接（PIE）**的 `libqemu-system-aarch64.so`（约 30 MB）加一组依赖库：

| 文件 | 作用 |
| --- | --- |
| `libqemu-system-aarch64.so` | QEMU 9.2.3 本体（ET_DYN，`PT_INTERP=/system/bin/linker64`） |
| `libqemu-img.so` | `qemu-img`，用于创建/检查 qcow2 |
| `libglib-2.0.so` `libpixman-1.so` `libslirp.so` `libpcre2-8.so` `libintl.so` | 依赖，`RUNPATH=$ORIGIN` |
| `libpvm-probe.so` | 400 KB 静态探针：诊断本机能否执行静态 bionic 二进制 |

> **为什么不是静态链接**：`-static` 产出的二进制在本机（Android 14）实测 `exit=139`，
> 即使改成 `ET_EXEC`（无重定位、16 KB 对齐）依旧 SIGSEGV——问题在 bionic 静态运行时
> 自举，而不是 PIE 标志位。动态链接由 Android 自己的 linker64 加载，是唯一被大规模
> 验证的形态（Termux 的 QEMU 同样如此）。环境卡片里的「静态执行自检」会直接告出
> 这台设备到底能不能跑静态二进制。

版本与构建事实：

| 项 | 值 |
| --- | --- |
| QEMU | 9.2.3 |
| 加速器 | TCG（`thread=multi`），无 KVM |
| 显示 | `-vnc`（RFB 3.8），无 GTK/SDL |
| 网络 | libslirp user-mode |
| 链接 | 动态 PIE（glib 2.80.5 / pixman 0.44.2 / libslirp 4.8.0 / 内置 libfdt / pcre2 / libffi） |
| 段对齐 | 0x4000（兼容 16 KB page size 设备） |
| 运行时数据 | `assets/qemu/keymaps/*`，首次启动解压到 `files/qemu/share`，作为 `-L` |
| UEFI 固件 | `assets/firmware/QEMU_EFI.fd`（edk2 2024.02，**64 MiB pflash**），首次启动释放到 `files/firmware/`，变量存储按同尺寸生成 |

固件自愈：解压标记只有在目录里存在 ≥32 MB 的 `.fd` 时才被采信；旧版残留的 2 MiB
`-bios` 镜像会先被删除再重新释放。变量存储与固件尺寸不一致时会自动重建，不需要
用户手动清数据。

重建（需要 Linux + NDK r27c + patchelf，约 5–10 分钟 / 24 核）：

```bash
NDK=~/android-ndk-r27c scripts/build-qemu-android-dynamic.sh all
scripts/install-qemu.sh .build-qemu/out-jni     # 拷贝全部 lib*.so
cd android && ./gradlew :app:assembleDebug
```

脚本里记录了几个必须踩过的坑（bionic 没有 `shm_open`、`memfd_create` 是 API 30 符号、
`--without-default-features` 会关掉 pixman、glib 的 gvdb 要单独补、jniLibs 只收 `lib*.so`
所以必须用 patchelf 把 SONAME/DT_NEEDED 一起改掉），改参数前先读注释。

### 启动降级（兼容模式）

部分芯片/系统上，标准参数会在机器初始化阶段直接退出（`tb-size` 分配失败、`-cpu max`
暴露的特性不受支持、rng 设备初始化失败等）。启动器因此内置了降级阶梯，每档独立
日志与 manifest：

| 档位 | 加速器 | CPU | 可选设备 |
| --- | --- | --- | --- |
| 标准 | `tcg,thread=multi,tb-size=512` | `max` | virtio-rng + 网络 |
| 兼容 | `tcg,thread=single,tb-size=128` | `cortex-a76` | 网络 |
| 最小 | `tcg,thread=single,tb-size=64` | `cortex-a76` | 无 |

- 默认「自动」：标准 → 兼容 → 最小，某档在 QMP 握手后再稳定 2.5 秒即采用
- **磁盘控制器、内存、vCPU 数不参与降级**：换控制器会让已装好的系统无法启动，
  改内存则可能引入新的失败
- QMP 握手发生在机器初始化之前，所以有 2.5 秒稳定性窗口，避免把"握手成功但随即
  崩溃"误判为启动成功
- 每次尝试写各自的 `run-<id>.log` / `run-<id>.json`，诊断包里能看到完整阶梯
- 界面可手动锁定档位（编辑 → 启动模式），用于排查或避开已知有问题的机型

`cortex-a76` 而不是 `a72`：Windows ARM64 需要 ARMv8.1 的 LSE 原子指令，a72 没有，
一个跑不起来客户机的"救援档"没有意义。

## 目录

```
android/                    Gradle 根（AGP 8.7 / Kotlin 2.1 / Compose）
  app/src/main/java/dev/pocketvm/
    core/model/             VmConfig / RunManifest / 运行状态
    core/config/            配置存储（原子写）、校验器（纯 Kotlin，可单测）
    storage/                目录布局、固件、磁盘置备、ISO 导入
    runtime/                QEMU 探测、参数构建、QMP、进程监督、前台服务
    diagnostics/            日志尾部、manifest、诊断包
    ui/                     Compose 界面（列表 / 向导 / 控制台）
  app/src/test/             纯 JVM 单元测试
scripts/                    原生层构建与安装
docs/                       架构与排障
```

## 设计约束（不从计划里删的部分）

1. **管理层与执行层分离**：Kotlin 只负责配置、状态、监督；QEMU 是独立进程，崩溃不影响 UI。
2. **先 TCG 后 KVM**：不把硬件虚拟化当阻塞条件。TCG 的代价是慢（Windows 启动约 3–8 分钟），这一点不掩饰。
3. **固件按平台配置**：不硬编码 `QEMU_EFI.fd` 的文件大小；vars 由 code 大小推导，二者不等长直接报错。
4. **存储分层**：ISO（只读介质）、QCOW2（可写磁盘）、vars（持久固件状态）互不复用。
5. **每次启动可追溯**：manifest 在 fork 之前写盘，崩溃也能看到当时的完整 argv。

## 验证状态

| 项 | 结论 |
| --- | --- |
| 编译（aarch64 静态链接） | 通过，`nm -u` 为 0 |
| 单元测试 | 37 个通过 |
| APK 结构 | `lib/arm64-v8a/libqemu-system-aarch64.so` + keymaps，`extractNativeLibs=true` |
| 真机启动 QEMU | **未验证**（沙箱无 Android 设备；静态 bionic 二进制也无法在 Linux 用户态模拟下运行） |
| Windows 安装全流程 | **未验证**，依赖真机结果 |

第一次上机时最值得盯的三件事：`QemuEnvironment` 是否报 `QEMU_MISSING`（找不到 .so）、
日志里有没有 `Could not read keymap`、以及 VNC 端口是否真的在监听。

## 已知限制

- VNC 只监听 `127.0.0.1`；查看画面需 `adb forward tcp:5900 tcp:5900`，或等 P1 内置 RFB 客户端。
- ISO 会被完整复制一份（`content://` 无法跨进程传给 QEMU），请预留 5–6 GB。
- 一台设备同一时间只跑一个 VM（`VmSession` 是单例）。
- minSdk 30：执行层以 API 30 为目标编译（`memfd_create` 的下限）。
- 未做快照/回滚；改配置前请自行备份 `disk.qcow2`。
