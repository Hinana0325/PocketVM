#!/usr/bin/env bash
#
# 主机侧自检：执行层命名/架构、单元测试、APK 打包属性。
# 这是提交前唯一需要手工跑的命令。
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
JNI="$ANDROID/app/src/main/jniLibs/arm64-v8a"
FAIL=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=1; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }

echo "== 执行层"
if [ -d "$JNI" ]; then
    found=0
    for f in "$JNI"/libqemu-system*.so; do
        [ -e "$f" ] || continue
        found=1
        arch="$(file -b "$f" 2>/dev/null || echo unknown)"
        case "$arch" in
            *aarch64*) ok "$(basename "$f")  aarch64  $(du -h "$f" | cut -f1)" ;;
            *) bad "$(basename "$f") 架构不是 aarch64：$arch" ;;
        esac
    done
    [ "$found" = 1 ] || warn "jniLibs 为空：应用能装，但启动会报 QEMU_MISSING"
else
    warn "还没有 $JNI，跳过（P0 未打包执行层）"
fi

echo "== 单元测试"
if (cd "$ANDROID" && ./gradlew -q :app:testDebugUnitTest >/tmp/pocketvm-test.log 2>&1); then
    ok "testDebugUnitTest 通过"
else
    bad "testDebugUnitTest 失败，见 /tmp/pocketvm-test.log"
fi

echo "== APK"
APK="$ANDROID/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    AAPT="$(ls -d "${ANDROID_HOME:-/opt/android-sdk}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
    if [ -n "$AAPT" ] && [ -x "$AAPT" ]; then
        MANIFEST_TREE="$("$AAPT" dump xmltree --file AndroidManifest.xml "$APK" 2>/dev/null || true)"
        if [[ "$MANIFEST_TREE" == *extractNativeLibs*"=true"* ]]; then
            ok "extractNativeLibs=true（so 会被解压到可执行路径）"
        else
            bad "extractNativeLibs 不为 true：jniLibs 里的执行文件无法 exec"
        fi
    else
        warn "找不到 aapt2，跳过打包属性检查"
    fi
    ok "APK $(du -h "$APK" | cut -f1)  $APK"
else
    warn "还没构建 APK：cd android && ./gradlew :app:assembleDebug"
fi

echo
[ "$FAIL" = 0 ] && echo "自检通过" || echo "自检存在问题"
exit "$FAIL"
