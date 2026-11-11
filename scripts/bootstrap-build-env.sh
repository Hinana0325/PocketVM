#!/usr/bin/env bash
#
# 用户级构建环境引导：JDK 17 + Android SDK 37。
# 不需要 root，不写系统目录，不修改 shell rc；所有文件落在
# ${PVM_BUILD_ROOT:-$HOME/.local/share/pocketvm-build}，删目录即回滚。
#
# 用法：
#   bash scripts/bootstrap-build-env.sh          # 安装/校验工具链
#   source ~/.local/share/pocketvm-build/pocketvm-build-env.sh
#
set -euo pipefail

ROOT="${PVM_BUILD_ROOT:-$HOME/.local/share/pocketvm-build}"
JDK_DIR="$ROOT/jdk"
SDK_DIR="$ROOT/android-sdk"
DL_DIR="$ROOT/downloads"
ENV_FILE="$ROOT/pocketvm-build-env.sh"
MIN_FREE_KB="${PVM_MIN_FREE_KB:-5000000}"

# 固定版本与校验和；升级时同时更新 URL 与 SHA256（可用同名环境变量覆盖）。
JDK_URL="${PVM_JDK_URL:-https://api.adoptium.net/v3/binary/version/jdk-17.0.21%2B9/linux/x64/jdk/hotspot/normal/eclipse}"
JDK_SHA256="${PVM_JDK_SHA256:-8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711}"
CMDLINE_TOOLS_URL="${PVM_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"
CMDLINE_TOOLS_SHA256="${PVM_CMDLINE_TOOLS_SHA256:-2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258}"

SDK_PACKAGES=("platform-tools" "platforms;android-37" "build-tools;36.0.0")

log()  { printf '[bootstrap] %s\n' "$*"; }
fail() { printf '[bootstrap] 错误：%s\n' "$*" >&2; exit 1; }

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || fail "缺少命令 $1，请先安装"
}

for c in curl tar unzip sha256sum; do require_cmd "$c"; done

[ "$(uname -s)" = "Linux" ] || fail "仅支持 Linux（当前 $(uname -s)）"
[ "$(uname -m)" = "x86_64" ] || fail "当前脚本只处理 x86_64 宿主机（实际 $(uname -m)）"

mkdir -p "$ROOT" "$DL_DIR"
FREE_KB="$(df -Pk "$ROOT" | awk 'NR==2 {print $4}')"
[ "$FREE_KB" -ge "$MIN_FREE_KB" ] || fail "磁盘不足：$ROOT 所在分区仅剩 $((FREE_KB / 1024)) MB，需要至少 $((MIN_FREE_KB / 1024)) MB"

download() {
    local url="$1" dest="$2" sha="$3"
    if [ -s "$dest" ] && echo "$sha  $dest" | sha256sum -c --status -; then
        log "复用已下载并校验的 $(basename "$dest")"
        return
    fi
    log "下载 $url"
    rm -f "$dest"
    curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$dest" "$url" \
        || fail "下载失败：$url"
    echo "$sha  $dest" | sha256sum -c --status - \
        || fail "校验和不匹配：$(basename "$dest")（上游可能已更新，请核对 PVM_*_SHA256）"
}

# 1) JDK 17
if [ -x "$JDK_DIR/bin/java" ] && "$JDK_DIR/bin/java" -version 2>&1 | grep -q 'version "17'; then
    log "JDK 已就绪：$("$JDK_DIR/bin/java" -version 2>&1 | head -1)"
else
    download "$JDK_URL" "$DL_DIR/jdk17.tar.gz" "$JDK_SHA256"
    log "解压 JDK 到 $JDK_DIR"
    rm -rf "$JDK_DIR"
    mkdir -p "$JDK_DIR"
    tar -xzf "$DL_DIR/jdk17.tar.gz" -C "$JDK_DIR" --strip-components=1
    "$JDK_DIR/bin/java" -version 2>&1 | grep -q 'version "17' || fail "JDK 解压后不可用"
fi

export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# 2) Android cmdline-tools
SDKMGR="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKMGR" ]; then
    log "cmdline-tools 已就绪：$SDKMGR"
else
    download "$CMDLINE_TOOLS_URL" "$DL_DIR/cmdline-tools.zip" "$CMDLINE_TOOLS_SHA256"
    log "解压 cmdline-tools 到 $SDK_DIR/cmdline-tools/latest"
    rm -rf "$SDK_DIR/cmdline-tools/latest" "$SDK_DIR/cmdline-tools/cmdline-tools"
    mkdir -p "$SDK_DIR/cmdline-tools"
    unzip -q "$DL_DIR/cmdline-tools.zip" -d "$SDK_DIR/cmdline-tools"
    mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# 3) SDK 组件（platform-tools 由 sdkmanager 取最新可用版本）
yes | "$SDKMGR" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true
"$SDKMGR" --sdk_root="$SDK_DIR" "${SDK_PACKAGES[@]}"

# 4) 环境文件（不写 shell rc，显式 source）
cat > "$ENV_FILE" <<EOF
# PocketVM 构建环境（由 scripts/bootstrap-build-env.sh 生成）
export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="\$ANDROID_HOME"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"
EOF

# 5) 校验
[ -x "$SDK_DIR/build-tools/36.0.0/aapt2" ] || fail "缺少 build-tools/36.0.0/aapt2"
[ -x "$SDK_DIR/platform-tools/adb" ] || fail "缺少 platform-tools/adb"
[ -d "$SDK_DIR/platforms/android-37" ] || fail "缺少 platforms/android-37"

log "JDK: $(head -1 <<< "$("$JDK_DIR/bin/java" -version 2>&1)")"
log "aapt2: $("$SDK_DIR/build-tools/36.0.0/aapt2" version 2>&1 | head -1)"
log "adb: $("$SDK_DIR/platform-tools/adb" version | head -1)"
log "占用：JDK $(du -sh "$JDK_DIR" | cut -f1)，SDK $(du -sh "$SDK_DIR" | cut -f1)"
log "完成。后续会话执行：source \"$ENV_FILE\""
