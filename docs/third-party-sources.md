# 第三方源码获取与重建说明

本文回答一个问题：**APK 里的 QEMU / Inferno / 固件到底来自哪份源码，怎么重建**。
许可证清单见 [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

## 1. QEMU 11.1.2（主执行层）

### 1.1 上游源码

构建脚本下载的上游归档（`scripts/build-qemu-android-dynamic.sh:73`）：

```
https://gitlab.com/qemu-project/qemu/-/archive/v11.1.2/qemu-v11.1.2.tar.gz
```

上游镜像：<https://gitlab.com/qemu-project/qemu>，发布页：<https://www.qemu.org/download/>。
版本可用环境变量覆盖：`QEMU_VERSION=11.1.2`。

### 1.2 本地修改（重建时必须重新施加）

| 修改 | 位置 | 说明 |
| --- | --- | --- |
| bionic `shm_open` 兼容 | `scripts/build-qemu-android-dynamic.sh` | 用 `memfd_create` 实现 `shm_open/shm_unlink`（注意：名字不能带前导 `/`） |
| PAC-free 协程切换 | `scripts/patch-qemu-pac.py`、`scripts/qemu-pac-shim.S` | 把 `sigsetjmp/siglongjmp` 的 `.plt` 槽改跳新增 PT_LOAD 里的无签名 `jmp_save/jmp_restore`；**重建后必须重打**，否则 ARMv9 上 50–300 秒随机 `exit=132` |
| 16 KB 页对齐 | 同上 + `patch-qemu-pac.py` | 所有 PT_LOAD 的 `p_align >= 0x4000`，兼容天玑 9xxx / Android 15+ |
| SONAME / DT_NEEDED / RUNPATH | `scripts/build-qemu-android-dynamic.sh`、`scripts/install-qemu.sh` | 依赖库改名为 `lib*.so`，`RUNPATH=$ORIGIN`，AGP 才能打包 |

### 1.3 构建与校验

```bash
NDK=~/android-ndk-r27c scripts/build-qemu-android-dynamic.sh all
scripts/install-qemu.sh .build-qemu/out-jni
```

- 依赖：NDK r27c、meson ≥ 1.2、ninja、python3、pkg-config、patchelf；约 5–10 分钟 / 24 核。
- 产物：`android/app/src/main/jniLibs/arm64-v8a/*.so`。
- **构建门禁**：`android/app/build.gradle.kts` 的 `verifyQemuPacPatch` 会按 SHA256 校验执行层。
  当前随包 `libqemu-system-aarch64.so` 的 SHA256 为
  `23f22b576fca0221dc261ed3eb0f887fdcac9b5ded3e98d294d6efad7a2f9f24`；重建后哈希变化属于预期，
  但必须确认已打 PAC 补丁并同步更新门禁值。
- `.build-qemu/` 不随仓库分发（已 gitignore）；随包二进制本身已在仓库中，可直接构建 APK。

### 1.4 依赖源码

`scripts/build-qemu-android-dynamic.sh` 中下载的依赖：

| 依赖 | 版本 | 源码地址 |
| --- | --- | --- |
| glib | 2.80.5 | `https://codeload.github.com/GNOME/glib/tar.gz/refs/tags/2.80.5` |
| pcre2 | 10.44 | `https://codeload.github.com/PCRE2Project/pcre2/tar.gz/refs/tags/pcre2-10.44` |
| libffi | 3.4.6 | `https://codeload.github.com/libffi/libffi/tar.gz/refs/tags/v3.4.6` |
| pixman | 0.44.2 | `https://cairographics.org/releases/pixman-0.44.2.tar.gz` |
| libslirp | 4.8.0 | `https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v4.8.0/libslirp-v4.8.0.tar.gz` |
| gvdb | 提交 `0854af0` | `https://codeload.github.com/GNOME/gvdb/tar.gz/0854af0fdb6d527a8d1999835ac2c5059976c210` |

libfdt/dtc 由 QEMU 源码内置；proxy-libintl 为 PocketVM 构建的 libintl 兼容层，上游为 Termux 生态的
同名库（版本以构建脚本与二进制为准）。

## 2. ChefKiss Inferno（iOS / t8030 执行层）

- 上游：<https://github.com/ChefKissInc/Inferno>，基于 QEMU 10.0.2；
  本仓库构建使用提交 `cc4302a`。
- 许可证：GPL-3.0（QEMU 衍生部分）+ AGPL-3.0（项目自有代码）。
- 本地修改与构建步骤（WSL Alpine、NDK r27c、依赖版本、configure 参数）见
  [inferno-android.md](inferno-android.md)；补丁文件为
  `scripts/inferno-pvm-pac-shim.S` / `.c`。
- 依赖源码：libtasn1 4.19.0、nettle 3.10.2、gmp 6.3.0、lzfse（构建时快照）、
  libpng 1.6.50 —— 均从各自上游发布归档获取，版本以 `docs/inferno-android.md` 为准。

## 3. edk2 / AAVMF 固件

- 上游：TianoCore edk2 <https://github.com/tianocore/edk2>；平台为 ArmVirtQemu（AAVMF）。
- 随包两份 64 MiB pflash：`QEMU_EFI-2026.08.fd.gz`（edk2 2026.08，默认）与
  `QEMU_EFI.fd.gz`（edk2 2024.02，兜底）。
- 这两份是**未修改的上游构建产物**；若需对应源码，请取 edk2 对应版本标签，
  按 ArmVirtQemu 平台构建 `QEMU_EFI.fd`。许可证为 BSD-2-Clause-Patent，
  再分发须保留版权与许可声明。

## 4. 重建后的验证

```bash
# 1. 构建门禁（PAC 补丁、16KB 对齐）
cd android && ./gradlew :app:assemblePerf -PpvmSideBySide=true

# 2. 执行层架构与单测
bash scripts/verify.sh
```

- 真机启动、VNC、Windows 安装等行为**以真机证据为准**，沙箱结论不等于设备结论。
- 重建执行层是对外可见的重大变更：需 owner 确认，且不得跳过 PAC 补丁与 16 KB 对齐。

## 5. 许可证文本

完整许可证文本可在以下位置获取（公开发布前需随包附带全文，见 THIRD_PARTY_NOTICES 第 2 节）：

- GPL-2.0：<https://www.gnu.org/licenses/old-licenses/gpl-2.0.html>
- GPL-3.0：<https://www.gnu.org/licenses/gpl-3.0.html>
- AGPL-3.0：<https://www.gnu.org/licenses/agpl-3.0.html>
- LGPL-2.1：<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>
- LGPL-3.0：<https://www.gnu.org/licenses/lgpl-3.0.html>
- MIT：<https://opensource.org/licenses/MIT>
