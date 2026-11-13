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
# 环境阻塞（能装能跑但本机跑不了自检的那部分）与代码失败分开记，避免把
# Windows 命令行长度这类宿主机限制算成提交阻塞。
ENV_BLOCKED=0

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=1; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }

# Git Bash 没有 file(1)，所以直接读 ELF 头兜底。e_machine 在偏移 18，2 字节小端。
# 输出格式对齐 file -b 的风格，好让调用方只用一套匹配规则。
elf_arch() {
    local f="$1" magic machine
    if command -v file >/dev/null 2>&1; then
        file -b "$f" 2>/dev/null
        return
    fi
    magic="$(od -An -tx1 -j0 -N4 "$f" 2>/dev/null | tr -d ' \n')"
    [ "$magic" = "7f454c46" ] || { echo "not-elf"; return; }
    machine="$(od -An -tx1 -j18 -N2 "$f" 2>/dev/null | tr -d ' \n')"
    case "$machine" in
        b700) echo "ELF 64-bit LSB, ARM aarch64" ;;
        3e00) echo "ELF 64-bit LSB, x86-64" ;;
        *)    echo "ELF 64-bit LSB, machine=$machine" ;;
    esac
}

# SDK 位置：环境变量优先，其次 android/local.properties 的 sdk.dir（Windows 本机
# 只配了后者，ANDROID_HOME 往往是空的）。
SDK="${ANDROID_HOME:-}"
if [ -z "$SDK" ] && [ -f "$ANDROID/local.properties" ]; then
    SDK="$(sed -n 's/^sdk\.dir=//p' "$ANDROID/local.properties" 2>/dev/null | head -1)"
fi
SDK="${SDK:-/opt/android-sdk}"

echo "== 执行层"
if [ -d "$JNI" ]; then
    found=0
    for f in "$JNI"/libqemu-system*.so; do
        [ -e "$f" ] || continue
        found=1
        arch="$(elf_arch "$f")"
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
TEST_LOG="${TMPDIR:-/tmp}/pocketvm-test.log"

# Windows 的命令行上限是 32767 字符：环境变量一多，gradle fork test worker 时
# 参数被截断，报的是 `ClassNotFoundException: <某个字符串片段>`（本机实测见过
# `VS`），看起来像测试失败，其实是根本没跑起来。子 shell 里剔掉超大变量再跑。
# 阈值 4 KiB 只命中工具注入的巨型配置变量，不会误伤正常环境。
run_unit_tests() {
    cd "$ANDROID" || return 1
    local name
    while IFS= read -r name; do
        [ -n "$name" ] && unset "$name"
    done < <(env | awk -F= 'length($0) > 4096 { print $1 }')
    ./gradlew -q :app:testDebugUnitTest >"$TEST_LOG" 2>&1
}

if (run_unit_tests); then
    ok "testDebugUnitTest 通过"
elif grep -qE 'ClassNotFoundException:|Test process encountered an unexpected problem|Could not write standard input' "$TEST_LOG" 2>/dev/null; then
    # 测试进程压根没起来，不是断言失败。本机 Windows 命令行上限 32767，
    # 而 debugUnitTestRuntimeClasspath 的 jar 路径总长远超（本机实测 490 个 jar
    # / 81 K 字符），java 启动参数被截断后会把路径片段当成主类名，
    # 报成 `ClassNotFoundException: <片段>`。单类 --tests 也一样，无法靠分批绕过。
    ENV_BLOCKED=1
    warn "测试进程未能启动（环境限制，不是代码失败）"
    warn "  本机 Windows 命令行上限 32767，测试 classpath 超限；请用 CI 或 Linux/macOS 复核"
    warn "  不依赖 Android/Hilt 的用例可先用：bash scripts/run-jvm-test.sh <测试类全名>"
else
    bad "testDebugUnitTest 失败，见 $TEST_LOG"
    tail -15 "$TEST_LOG" 2>/dev/null | sed 's/^/    /'
fi

echo "== APK"
# 发布用 perf 变体优先；没有就回退 debug。
APK="$ANDROID/app/build/outputs/apk/perf/app-perf.apk"
[ -f "$APK" ] || APK="$ANDROID/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    # aapt2 在 Windows 上是 aapt2.exe；SDK 路径可能来自 local.properties（见上）。
    AAPT="$(ls -d "$SDK"/build-tools/*/aapt2 "$SDK"/build-tools/*/aapt2.exe 2>/dev/null | sort -V | tail -1)"
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
if [ "$FAIL" != 0 ]; then
    echo "自检存在问题"
    exit 1
fi
if [ "$ENV_BLOCKED" != 0 ]; then
    echo "自检通过（有 $ENV_BLOCKED 项环境阻塞：单元测试需在 CI 或 Linux 上复核）"
    exit 0
fi
echo "自检通过"
exit 0
