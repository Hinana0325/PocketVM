# 第三方组件与许可证声明

PocketVM 自有代码（Kotlin/Compose 管理层、脚本、文档）采用 **MIT**，见 [LICENSE](LICENSE)。
随 APK 分发的执行层、固件与依赖库**不属于 MIT 范围**，各自遵循上游许可证。
本文是工程合规说明，不构成法律意见；最终法律文本与版权人由人类 owner 确认。

## 1. 随 APK/仓库分发的组件

### 1.1 执行层：QEMU 11.1.2（主执行层，Windows/Linux/Android 平台）

| 组件 | 版本 | 许可证 | 在仓库中的形态 |
| --- | --- | --- | --- |
| QEMU | 11.1.2 | GPL-2.0-only | `jniLibs/arm64-v8a/libqemu-system-aarch64.so`、`libqemu-img.so` |
| QEMU 运行时数据（keymaps） | QEMU 11.1.2 | GPL-2.0-only | `assets/qemu/keymaps/` |
| `efi-virtio.rom`（网卡 option ROM） | QEMU pc-bios 随包构建（源自 iPXE） | GPL-2.0-or-later（上游声明） | `assets/qemu/efi-virtio.rom` |
| glib | 2.80.5 | LGPL-2.1-or-later | `libglib-2.0.so` |
| pixman | 0.44.2 | MIT | `libpixman-1.so` |
| libslirp | 4.8.0 | BSD-3-Clause | `libslirp.so` |
| pcre2 | 10.44 | BSD-3-Clause | `libpcre2-8.so` |
| libffi | 3.4.6 | MIT | `libffi.so` |
| libfdt（QEMU 内置 dtc） | 随 QEMU 源码 | GPL-2.0-or-later OR BSD-2-Clause（双许可） | 静态编入执行层 |
| proxy-libintl / libintl | PocketVM 构建的兼容层 | LGPL-2.1-or-later（随上游 Termux proxy-libintl） | `libintl.so` |
| gvdb | 随 glib 构建 | LGPL-2.1-or-later | 构建期使用 |

对 QEMU 源码的本地修改（重建时由仓库内脚本施加）：

- bionic `shm_open` 兼容（`memfd_create` 实现），见 `scripts/build-qemu-android-dynamic.sh`；
- PAC-free 协程切换补丁（`scripts/patch-qemu-pac.py` + `scripts/qemu-pac-shim.S`）；
- 新增 PT_LOAD 与全部段按 16 KB 页对齐（`p_align >= 0x4000`）；
- SONAME / `DT_NEEDED` / `DT_RUNPATH=$ORIGIN` 改写（`patchelf`），使 AGP 能打包为 `lib*.so`。

### 1.2 执行层：ChefKiss Inferno（iOS / iPhone 11 平台）

| 组件 | 版本 | 许可证 | 在仓库中的形态 |
| --- | --- | --- | --- |
| Inferno（QEMU 10.0.2 fork） | 上游提交 `cc4302a` | **GPL-3.0（QEMU 衍生部分）+ AGPL-3.0（项目自有代码）** | `jniLibs/arm64-v8a/libqemu-inferno-system-aarch64.so` |
| libtasn1 | 4.19.0 | LGPL-2.1-or-later | 静态编入 / 依赖 |
| nettle | 3.10.2 | GPL-2.0-or-later OR LGPL-3.0-or-later | 静态编入 / 依赖 |
| gmp | 6.3.0 | LGPL-3.0-or-later OR GPL-2.0-or-later | 静态编入 / 依赖 |
| lzfse | master（构建时快照） | BSD-3-Clause | 静态编入 |
| libpng | 1.6.50 | libpng-2.0 | 静态编入 / 依赖 |

对 Inferno 源码的本地修改与构建步骤见 [docs/inferno-android.md](docs/inferno-android.md)；
PAT 补丁文件为 `scripts/inferno-pvm-pac-shim.S` / `.c`。

### 1.3 执行层：swtpm 0.10.2 + libtpms 0.10.2（TPM 2.0，阶段 2）

| 组件 | 版本 | 许可证 | 在仓库中的形态 |
| --- | --- | --- | --- |
| swtpm | 0.10.2 | BSD-3-Clause | `jniLibs/arm64-v8a/libswtpm.so`（POC 的 `bin/swtpm` 改名） |
| libtpms | 0.10.2 | BSD-3-Clause | `jniLibs/arm64-v8a/libtpms.so` |
| libswtpm_libtpms | 0.10.2（随 swtpm） | BSD-3-Clause | `jniLibs/arm64-v8a/libswtpm_libtpms.so` |
| GLib | 2.80.5 | LGPL-2.1-or-later | 静态嵌进 `libswtpm_libtpms.so` |
| json-glib | 1.8.0 | LGPL-2.1-or-later | 静态嵌进 `libswtpm_libtpms.so` |
| libtasn1 | 4.19.0 | LGPL-2.1-or-later | 静态嵌进 `libswtpm_libtpms.so` |
| pcre2 | 10.44 | BSD-3-Clause | 静态嵌进 `libswtpm_libtpms.so` |
| libffi | 3.4.6 | MIT | 静态嵌进 `libswtpm_libtpms.so` |
| GMP | 6.3.0 | LGPL-3.0-or-later OR GPL-2.0-or-later | 静态嵌进 `libswtpm_libtpms.so` |
| OpenSSL | 3.5.9 | Apache-2.0 | 静态嵌进 `libtpms.so` |

构建见 [docs/third-party-sources.md](docs/third-party-sources.md) 第 3 节；Bionic 适配补丁
（`index()`/`rindex()` 改 `strchr()`）在仓库外的 POC 目录 `patches/` 下。

**待人类 owner 确认的合规疑问（不要自行下结论）：** GLib（LGPL-2.1）这次是
静态嵌进 `libswtpm_libtpms.so` 的，不是动态链接。LGPL 对静态链接有额外义务
（通常要提供目标文件或等效方式，让下游能重新链接修改过的库版本），本次分发
是否满足、以及用何种方式满足，尚未定论。公开发布前须由 owner/法务确认，
在确认之前不要把这份声明当成已合规。

### 1.4 固件：edk2 / AAVMF

| 组件 | 版本 | 许可证 | 在仓库中的形态 |
| --- | --- | --- | --- |
| AAVMF（ArmVirtQemu，edk2，TPM2 开启，自编） | 2026.08 + TPM2_ENABLE | BSD-2-Clause-Patent | `assets/firmware/QEMU_EFI-2026.08-TPM2-RELEASE.fd.gz` |
| AAVMF（ArmVirtQemu，edk2） | 2026.08（普通版） | BSD-2-Clause-Patent | `assets/firmware/QEMU_EFI-2026.08.fd.gz` |
| AAVMF（ArmVirtQemu，edk2） | 2024.02（兜底） | BSD-2-Clause-Patent | `assets/firmware/QEMU_EFI.fd.gz` |

随包为 64 MiB pflash 镜像；若替换固件，需同时保留上游版权与许可声明。

### 1.5 Android 侧依赖

| 组件 | 许可证 | 说明 |
| --- | --- | --- |
| AndroidX / Jetpack Compose / Kotlin | Apache-2.0 | Gradle 依赖，按各自上游声明 |
| mwiede JSch | BSD 风格（上游声明） | SSH 控制台依赖 |
| 其他 Maven/Gradle 依赖 | 各自上游许可证 | 完整清单可用 `./gradlew :app:dependencies` 生成，发布前补齐 |

### 1.6 不分发的资源

- Apple iOS 还原资产（kernelcache / DeviceTree / SEP / trustcache / ticket / nvram / NVMe 镜像）
  **不进仓库、不进 APK**，由用户自行导入，PocketVM 不分发。
- Windows / Linux / Android 安装镜像同样由用户自备，PocketVM 不分发。

## 2. 分发义务（重要）

1. **QEMU（GPL-2.0）**：APK 内嵌 QEMU 可执行文件，属于分发 GPL 二进制。分发 APK 时必须能提供
   **完整对应源码**：上游 QEMU 11.1.2 + 上文本地修改 + 构建脚本。源码获取方式见
   [docs/third-party-sources.md](docs/third-party-sources.md)。
2. **Inferno（GPL-3.0 + AGPL-3.0）**：同上，需提供上游 `cc4302a` 源码、全部本地修改与构建说明。
3. **edk2（BSD-2-Clause-Patent）**：保留版权声明、许可条款与免责声明；二进制再分发需附带许可文本。
4. **LGPL 组件**：保留许可证与版权声明；若修改 LGPL 库本身，需提供修改后的源码。
5. 不得移除二进制或数据文件中的版权/许可声明。仓库与发布说明中应保留本文链接。
6. **公开发布前待办**：将完整许可证文本（GPL-2.0、GPL-3.0、AGPL-3.0、LGPL-2.1、LGPL-3.0、
   BSD-2-Clause、BSD-3-Clause、Apache-2.0、MIT 等）放入 `LICENSES/` 目录或随 APK 一同分发。
   当前仓库仅提供链接，尚未附带全文，由 owner/DevOps 在首个公开发布前补齐。

## 3. 联系方式

许可证与源码获取问题请在仓库 Issue 中提出：
<https://github.com/Hinana0325/PocketVM/issues>
