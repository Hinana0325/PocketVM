# 打包与推送

从「没有 QEMU」到「GitHub 上有这个提交」的完整链路。硬约束的来由见 [AGENTS.md](../AGENTS.md)，报错含义见 [troubleshooting.md](troubleshooting.md)。

## 0. 一条链

```
build-qemu-android-dynamic.sh   →  改名成 lib*.so 的执行层闭包
install-qemu.sh                 →  落进 android/app/src/main/jniLibs/arm64-v8a/
gradlew :app:assembleDebug      →  app-debug.apk
scripts/verify.sh               →  提交前自检
scripts/wireless-dev.sh         →  装到真机并取一手证据
scripts/gh-sync.py              →  推到 Hinana0325/PocketVM
```

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

`versionCode` / `versionName` 是给用户判断"手机上是哪一版"用的，格式 `0.10.1-qemu11.1.2-dyn`。**换了执行层必须同时改 versionName 里的 qemu 段**，否则日志和 APK 对不上号。

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

`scripts/make_release.py` 建 GitHub Release 并把 debug APK 传成资产——用户从这里下载，不是从 git。`TAG` 与 APK 路径是写死的常量，用之前先改成当前版本。

## 6. 推到 GitHub

`git push` 在这台机器上跑不通：github.com:443 的 git 协议被阻断，而 `api.github.com` 正常。工作副本还**没有 `.git`**（只有 `.gitignore`），所以 `git ls-files` 和依赖它的 `scripts/push_via_api.py` 也用不了。

```bash
export GH_TOKEN="$(bash scripts/gh-token.sh)"   # 从 GCM 现取，不落盘
python scripts/gh-sync.py plan                  # 只读：会新增/修改哪些文件
python scripts/gh-sync.py push -m "提交说明"
```

`gh-sync.py` 的做法：本地用 `git hash-object --no-filters` 算原始字节的 blob SHA-1，与远端递归树逐文件比，只上传差异；用 `base_tree` 增量建树，所以**远端独有的文件原样保留，不删任何东西**；每个 blob 都核对服务端返回的 SHA-1，推完再拉一次树复核。默认跳过 `build/`、`.gradle`、`*.apk`、`*.log`、`.qoder/`、`.mimocode/` 等，额外项用 `--skip 路径` 追加。

## 7. 凭据

- 能用的凭据只有 **Git Credential Manager 里那份账号级 OAuth token**（`scripts/gh-token.sh` 就是取它）。
- `~/.git-credentials` 里那条 `ghp_` 开头的经典 PAT **已被吊销**（API 返 401），别再拿它试。
- token 本身不进仓库：它是账号级的（除 `repo` 外还带 `delete_repo`、`admin:org`、`workflow`），一旦进了 git 历史，即使仓库是私有的，也会随 fork、协作者、镜像和 GitHub 自身的索引扩散，而且提交过的秘密只能靠改写历史来撤销——而它会随 GCM 刷新轮换，写死的那份迟早是错的。要换机器就重新授权一次 GCM，成本是一条命令。
