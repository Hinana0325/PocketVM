# 打包与推送

从「没有 QEMU」到「GitHub 上有这个提交」的完整链路。硬约束的来由见 [AGENTS.md](../AGENTS.md)，报错含义见 [troubleshooting.md](troubleshooting.md)。

## 0. 一条链

```
build-qemu-android-dynamic.sh   →  改名成 lib*.so 的执行层闭包
install-qemu.sh                 →  落进 android/app/src/main/jniLibs/arm64-v8a/
gradlew :app:assemblePerf       →  apk/perf/app-perf.apk（日常）或 app-debug.apk（调试）
scripts/verify.sh               →  提交前自检
scripts/wireless-dev.sh         →  装到真机并取一手证据
git push（需 owner 授权）        →  推送到 origin；日常分支由 PM 合并
```

`scripts/gh-sync.py` / `push_via_api.py` 是旧工作流（仓库曾无 `.git`、git-over-HTTPS 不通）的历史方案，**已废弃**，见第 6 节。

## 1. 执行层（QEMU + 依赖闭包）

```bash
bash scripts/build-qemu-android-dynamic.sh   # 需要 Linux + NDK r27c + meson/ninja/patchelf
bash scripts/install-qemu.sh                 # 产物拷进 jniLibs/arm64-v8a/
```

- 5–10 分钟 / 24 核，`.build-qemu/` 有几个 GB（已 gitignore）。**没让重建就别重建。**
- 脚本头部的注释是必须踩过的坑（SONAME、`DT_RUNPATH=$ORIGIN`、为什么不能 `-static`），改参数前先读。
- 产物是**改名成 `.so` 的 ELF 可执行文件**，AGP 只打包 `lib*.so`，所以每个依赖都被 patchelf 动过。

## 2. APK

```bash
cd android                      # Gradle 根是 android/，不是仓库根
./gradlew :app:assembleDebug    # Windows 下用 gradlew.bat
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17+ 与 Android SDK 35。两个不能碰的地方：

- `jniLibs.useLegacyPackaging = true`：Android 10+ 的 W^X 只允许执行 `nativeLibraryDir` 里的文件，去掉就整条启动链断掉。
- `minSdk = 30`：`memfd_create` 的下限。

`versionCode` / `versionName` 是给用户判断"手机上是哪一版"用的，格式 `<版本>-qemu<QEMU 版本>-edk<edk2 版本>`，当前为 `0.10.28-qemu11.1.2-edk2026.08`。**换了执行层或固件必须同时改对应段**，否则日志和 APK 对不上号；版本号由 PM 在合并时统一升，分支内不要改。

## 3. 提交前自检

```bash
bash scripts/verify.sh
```

检查执行层架构、跑纯 JVM 单元测试、核对 APK 的 `extractNativeLibs=true`。它是 bash-only，依赖 `file` 与 `aapt2`，按 `${ANDROID_HOME:-/opt/android-sdk}` 找 SDK——Windows 上用 Git Bash / WSL，别在 PowerShell 里跑。

## 4. 真机

```bash
bash scripts/wireless-dev.sh pair <IP>:<配对端口> <6位码>   # 一次性
bash scripts/wireless-dev.sh install --build                # 构建 + 安装 + 启动
bash scripts/wireless-dev.sh {discover|connect|launch|log|health [N]|vnc [端口]|status}
```

前提：手机「开发者选项 → 无线调试」开启且与主机同网段。三个会飘的东西脚本都现取，别抄进配置：无线调试的**端口**、`/data/app/~~<hash>/` 里的**包路径哈希**、`run-*.json` 里的 **vncPort**。画面只能走 `adb forward`（局域网 VNC 在这个 QEMU 构建上不可用，原因见 AGENTS.md）。

## 5. 发布 Release（可选）

`scripts/make_release.py` 建 GitHub Release 并把 APK 传成资产——用户从这里下载，不是从 git。注意：脚本里的 `TAG`、`APK`、`ASSET_NAME` 仍是 0.9.2 时代的硬编码常量（T3 审计 H2），直接运行会指向不存在的路径或错版本；使用前必须改成当前版本与产物路径，或等 DevOps 完成参数化。当前 `perf` 构建是日常侧载包（debug 签名、非 debuggable），发布资产用哪个变体要在 Release 说明里写清。发布是对外动作，须 owner 明确授权。

## 6. 推到 GitHub

当前工作副本**有 `.git`**（remote `https://github.com/Hinana0325/PocketVM.git`），`main` 与 `origin/main` 同步，`git ls-remote` 可读。git-over-HTTPS 的**读**是通的；**写**需要 owner 授权的凭据，默认不推送。

正常开发流：成员只在本地分支/worktree 提交，禁止 push；由项目经理合并后再推送。

需要直接推送时（仅在 owner 明确授权后），用一次性 URL，不改 `origin` 配置，token 只经环境变量传递、绝不写入仓库：

```bash
export GITHUB_TOKEN=...   # 由 owner 提供，或经已授权的连接器获取；不要写进 shell 历史
git push https://oauth2:${GITHUB_TOKEN}@github.com/Hinana0325/PocketVM.git main
```

历史方案说明：`scripts/gh-sync.py`（`GH_TOKEN` + api.github.com git data API）与 `scripts/push_via_api.py` 是为「仓库无 `.git`、git 协议不通」的旧环境写的，两个前提现在都不成立，**已废弃，仅作历史参考**；`gh-sync.py` docstring 里「没有 .git」的描述也已过期。不要把它们当现行发布路径。

## 7. 凭据

- 当前环境默认**没有任何可用推送凭据**：无 `gh`、无 credential helper、无 `~/.git-credentials`、无 `GH_TOKEN`。只读 `git ls-remote` 不受影响。
- 需要推送时由 owner 选择一种授权方式：① 直接提供 token（环境变量传入）；② 在平台侧授权 GitHub 连接器。除此之外不要自行生成、缓存或复用凭据。
- `scripts/gh-token.sh` 依赖 Git Credential Manager，且会把 token 打到 stdout——**不要在共享终端或 CI 日志里运行**。当前环境没有 GCM，该脚本取不到 token。
- `~/.git-credentials` 里那条 `ghp_` 开头的经典 PAT 已吊销（API 返 401），别再试。
- token 本身不进仓库：它是账号级的（除 `repo` 外还带 `delete_repo`、`admin:org`、`workflow`），一旦进历史只能改写历史撤销，且会随授权轮换而过期。换机器就重新授权一次。
