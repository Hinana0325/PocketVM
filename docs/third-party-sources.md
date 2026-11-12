# 第三方源码获取与重建说明

本文回答一个问题：**APK 里的 QEMU / Inferno / swtpm / 固件到底来自哪份源码，怎么重建**。
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

## 3. swtpm 0.10.2 + libtpms 0.10.2（TPM 2.0，阶段 2）

- 上游：swtpm <https://github.com/stefanberger/swtpm>（正式标签 `v0.10.2`），
  libtpms <https://github.com/stefanberger/libtpms>（正式标签 `v0.10.2`）。
  许可证均为 BSD-3-Clause（以各自源码树内的 LICENSE 为准）。
- 构建：NDK r27c、API 30、arm64-v8a，构建脚本在仓库外
  `~/workspace/pocketvm-swtpm-poc/build-android.sh`（阶段 1 Native POC 的产物，
  未随仓库分发；重建前先确认它已在干净目录从头跑通过——截至阶段 2 尚未跑过）。
  构建顺序：OpenSSL 3.5.9 → libtpms 0.10.2 → GLib 2.80.5 / json-glib 1.8.0 /
  libtasn1 4.19.0 / pcre2 10.44 / libffi 3.4.6 / GMP 6.3.0 → swtpm 0.10.2。
  Bionic 适配补丁（`index()`/`rindex()` 不存在，改 `strchr()`）在 POC 目录的
  `patches/` 下。
- 随包三文件（`android/app/src/main/jniLibs/arm64-v8a/`）：
  `libswtpm.so`（POC 的 `bin/swtpm` 改名）、`libtpms.so`、
  `libswtpm_libtpms.so`。三者 PT_LOAD 均按 `0x4000` 对齐，均无 RUNPATH；
  swtpm 的 NEEDED 只有 `libswtpm_libtpms.so`、`libtpms.so` 与系统库，
  `libswtpm_libtpms.so` 另要系统自带的 `libz.so`——运行时靠
  `LD_LIBRARY_PATH=nativeLibraryDir` 解析。
- 以下依赖是**静态嵌进**随包 `.so` 的（不是独立分发的文件）：
  GLib 2.80.5（LGPL-2.1-or-later）与 json-glib 1.8.0、libtasn1 4.19.0、
  pcre2 10.44、libffi 3.4.6、GMP 6.3.0 嵌进 `libswtpm_libtpms.so`；
  OpenSSL 3.5.9（Apache-2.0）嵌进 `libtpms.so`。
  静态嵌 LGPL 库的合规义务（是否需提供目标文件以便重新链接）尚未定论，
  见 THIRD_PARTY_NOTICES.md 第 1.3 节末尾；最终法律文本由人类 owner 确认。
- swtpm_setup / swtpm_localca 本阶段不用，未打包。

## 4. edk2 / AAVMF 固件

- 上游：TianoCore edk2 <https://github.com/tianocore/edk2>；平台为 ArmVirtQemu（AAVMF）。
- 随包三份 64 MiB pflash：`QEMU_EFI-2026.08-TPM2-RELEASE.fd.gz`（edk2 2026.08
  自编开 TPM2，默认）与 `QEMU_EFI-2026.08.fd.gz`（edk2 2026.08，普通版）、
  `QEMU_EFI.fd.gz`（edk2 2024.02，兜底）。
- TPM2 版是**自编产物，不是未修改的上游构建**：源码为官方 edk2 标签
  `edk2-stable202608`（commit `2970e569`），按 ArmVirtQemu 平台构建，
  构建参数 `-D TPM2_ENABLE=TRUE -D TPM2_CONFIG_ENABLE=TRUE`（未开 Secure Boot）。
  构建产物与验证报告在仓库外的 `~/workspace/pocketvm-swtpm-poc/edk2-rebuild-2026.08/`。
  另两份（普通版 2026.08 与 2024.02）是**未修改的上游构建产物**；若需对应源码，
  请取 edk2 对应版本标签，按 ArmVirtQemu 平台构建 `QEMU_EFI.fd`。
  许可证均为 BSD-2-Clause-Patent，再分发须保留版权与许可声明。

## 5. 重建后的验证

```bash
# 1. 构建门禁（PAC 补丁、16KB 对齐）
cd android && ./gradlew :app:assemblePerf -PpvmSideBySide=true

# 2. 执行层架构与单测
bash scripts/verify.sh
```

- 真机启动、VNC、Windows 安装等行为**以真机证据为准**，沙箱结论不等于设备结论。
- 重建执行层是对外可见的重大变更：需 owner 确认，且不得跳过 PAC 补丁与 16 KB 对齐。

## 6. 许可证文本

完整许可证文本可在以下位置获取（公开发布前需随包附带全文，见 THIRD_PARTY_NOTICES 第 2 节）：

- GPL-2.0：<https://www.gnu.org/licenses/old-licenses/gpl-2.0.html>
- GPL-3.0：<https://www.gnu.org/licenses/gpl-3.0.html>
- AGPL-3.0：<https://www.gnu.org/licenses/agpl-3.0.html>
- LGPL-2.1：<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>
- LGPL-3.0：<https://www.gnu.org/licenses/lgpl-3.0.html>
- BSD-3-Clause：swtpm / libtpms 各自源码树内的 LICENSE
- MIT：<https://opensource.org/licenses/MIT>
