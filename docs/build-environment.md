# 构建环境（用户级）

PocketVM 的构建只需要 JDK 17 与 Android SDK 37；QEMU 执行层已入库，**首次构建不需要重建 QEMU**。
本文描述不依赖 root 的本机环境搭建方式，适用于 x86_64 Linux 开发机。

## 路径与版本

| 项 | 值 |
| --- | --- |
| 安装根目录 | `~/.local/share/pocketvm-build`（可用 `PVM_BUILD_ROOT` 覆盖） |
| JDK | Temurin 17.0.21+9（Adoptium，固定 URL + SHA256） |
| Android 命令 | cmdline-tools `commandlinetools-linux-11076708_latest`（固定 SHA256） |
| SDK 平台 | `platforms;android-37.0`（API 37 带 minor 版本，裸的 `android-37` 不存在；可能还在 Preview channel，安装加 `--channel=3`） |
| 构建工具 | `build-tools;36.0.0` |
| 平台工具 | `platform-tools`（sdkmanager 取当前最新，adb 37.0.1） |
| Gradle | 9.3.1（wrapper 自带腾讯镜像） |
| AGP / Kotlin | 9.1.1 / 2.2.10 |

## 一键安装

```bash
bash scripts/bootstrap-build-env.sh
source ~/.local/share/pocketvm-build/pocketvm-build-env.sh
```

脚本行为：

- 只写入 `~/.local/share/pocketvm-build`，不装系统包、不写 shell rc、不需要 sudo；
- 下载物做 SHA256 校验，校验失败即中止；
- 已安装且可用时跳过重复下载；可重复执行；
- 结束前校验 `aapt2`、`adb`、`platforms/android-37.0` 是否存在。

环境变量（`pocketvm-build-env.sh`）：

```bash
export JAVA_HOME="$HOME/.local/share/pocketvm-build/jdk"
export ANDROID_HOME="$HOME/.local/share/pocketvm-build/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

## 构建与自检

```bash
source ~/.local/share/pocketvm-build/pocketvm-build-env.sh
cd android
./gradlew :app:testDebugUnitTest            # 纯 JVM 单测
./gradlew :app:assemblePerf -PpvmSideBySide=true   # 侧载用 APK
cd ..
bash scripts/verify.sh                      # 提交前自检（执行层架构 + 单测 + APK 属性）
```

产物：`android/app/build/outputs/apk/perf/app-perf.apk`。
`verify.sh` 按 `${ANDROID_HOME:-/opt/android-sdk}` 找 SDK；未 source 环境文件时会退回 `/opt/android-sdk` 并报找不到 aapt2。

## 首次构建实测（2026-10-31，main@aa55a6b）

| 项 | 结果 |
| --- | --- |
| 单元测试 | 15 个文件 / 140 个用例，0 失败、0 错误、0 跳过；测试执行 1.216 s（首次构建含依赖下载 9m50s） |
| `:app:assemblePerf -PpvmSideBySide=true` | BUILD SUCCESSFUL，1m44s |
| `scripts/verify.sh` | 通过：执行层 aarch64、单测通过、`extractNativeLibs=true` |
| APK | 57.6 MB，`android/app/build/outputs/apk/perf/app-perf.apk` |

宿主机有 `qemu-img` / `qemu-io`，`Qcow2CreatorTest` 的 10 个用例全部实跑，未因 `assumeTrue` 跳过。

## 磁盘占用

| 项 | 约 |
| --- | --- |
| JDK | 318 MB |
| Android SDK | 608 MB |
| Gradle 发行版 + 依赖缓存 | 1.3 GB（首次构建后实测） |
| worktree + APK 构建产物 | 599 MB |

`/home` 容量偏紧（当前 170 GB 盘已用 92%），清理顺序：
删除 `~/.local/share/pocketvm-build/downloads/*.tar.gz`、`*.zip` → `cd android && ./gradlew --stop && rm -rf ~/.gradle/caches` → 删除不再使用的 worktree。

## 回滚

工具链全部在用户目录，删除即可，不影响系统：

```bash
cd android && ./gradlew --stop
rm -rf ~/.local/share/pocketvm-build
# 如需一并清理 Gradle 缓存：rm -rf ~/.gradle
```

## 边界与已知问题

- 脚本不安装系统包、不配置 shell rc、不处理真机 USB 授权；`adb` 只随 `platform-tools` 安装。
- Gradle wrapper 默认走 `mirrors.cloud.tencent.com`，仓库外的网络策略需允许该域与 `dl.google.com`、`repo.maven.apache.org`。
- `platform-tools` 未固定小版本（sdkmanager 取最新），`aapt2` 与 `platform 37` 固定。
- 首次构建会下载 Gradle 发行版与全部依赖，耗时明显长于后续增量构建。
- 构建前 `verifyQemuPacPatch` 会校验执行层 PAC 补丁 SHA256；重建 QEMU 后忘记补丁会直接构建失败，这是预期门禁。
- APK 的 `perf`/`debug` 变体使用 debug 签名，仅用于侧载；没有配置 release 签名与发布流水线。
