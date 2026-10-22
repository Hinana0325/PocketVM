#!/usr/bin/env bash
#
# 把编译产物放进 jniLibs，并做 Android 侧的必要检查。
#
# 关键点：文件名必须是 lib*.so。Android 只允许从 ApplicationInfo.nativeLibraryDir
# 执行文件，而该目录只会被 PackageManager 填充成这个形态。
#
# 动态链接版本需要把全部依赖库一起放进 jniLibs（QEMU 靠 RUNPATH=$ORIGIN 找它们），
# 所以第一个参数也可以是 build 的 out-jni/ 目录。
#
set -euo pipefail

BIN="${1:-}"
IMG="${2:-}"
[ -n "$BIN" ] || { echo "用法：$0 <qemu-system-aarch64|out-jni 目录> [qemu-img]" >&2; exit 1; }
[ -e "$BIN" ] || { echo "找不到 $BIN" >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/android/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$DEST"

STRIP=""
if [ -n "${ANDROID_NDK_ROOT:-}" ]; then
    STRIP="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
fi

arch_of() { file -b "$1" 2>/dev/null || true; }

install_one() {
    local src="$1" name="$2" dst
    local info; info="$(arch_of "$src")"
    case "$info" in
        *aarch64*|*ARM\ aarch64*) ;;
        *) echo "[warn] $src 不是 aarch64：$info" >&2 ;;
    esac

    dst="$DEST/$name"
    cp -f "$src" "$dst"
    if [ -n "$STRIP" ] && [ -x "$STRIP" ]; then
        "$STRIP" --strip-unneeded "$dst" || echo "[warn] strip 失败，继续" >&2
    fi
    chmod 755 "$dst"
    printf '%-38s %8s  %s\n' "$name" "$(du -h "$dst" | cut -f1)" "$(arch_of "$dst")"
}

if [ -d "$BIN" ]; then
    shopt -s nullglob
    libs=("$BIN"/lib*.so)
    [ ${#libs[@]} -gt 0 ] || { echo "$BIN 里没有 lib*.so" >&2; exit 1; }
    for f in "${libs[@]}"; do
        # libpvm-probe.so 是应用自带的静态探针，不要覆盖。
        [ "$(basename "$f")" = "libpvm-probe.so" ] && continue
        install_one "$f" "$(basename "$f")"
    done
else
    install_one "$BIN" "libqemu-system-aarch64.so"
fi
if [ -n "$IMG" ]; then
    [ -f "$IMG" ] || { echo "找不到 $IMG" >&2; exit 1; }
    install_one "$IMG" "libqemu-img.so"
fi

echo
echo "产物目录：$DEST"
echo "下一步：cd android && ./gradlew :app:assembleDebug"
echo "注意：build.gradle.kts 必须保留 jniLibs.useLegacyPackaging = true，否则 so 不会解压到磁盘。"
