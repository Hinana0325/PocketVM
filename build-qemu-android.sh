#!/usr/bin/env bash
#
# ⚠️ 已废弃：这个静态链接配方在真机上 exit=139（bionic 静态运行时无法自举，
# 与 PIE 标志位无关）。请使用 build-qemu-android-dynamic.sh。保留此文件仅为
# 记录踩过的坑与静态链接的完整参数。
#
# 交叉编译 QEMU 9.2.3 (aarch64-linux-android, TCG only) —— PocketVM 的实际构建脚本。
#
# 这不是「理想流程」，而是本机真实跑通的流程，包含所有踩过的坑：
#   * 交叉 clang 不能用 -D__ANDROID_API__，否则 -Werror 下 -Wmacro-redefined 直接失败
#   * glib 需要 gvdb 子项目（.wrap 走网络，离线环境必须手动补源码）
#   * bionic 没有 shm_open(3)，QEMU 的 memory-backend-shm 会编译失败 → memfd 垫片
#   * bionic 的 memfd_create 是 API 30 符号，target API 必须是 30，否则与 QEMU 自带回退重定义
#   * QEMU 用 $cross-prefix + pkg-config 找 pkg-config，NDK 里没有 → 需要软链
#   * **必须 -static -no-pie**：NDK clang 默认产出 static-PIE，而 bionic 不做
#     自重定位，进程在 main 之前就 SIGSEGV（exit=139）。真机实测过。
#   * --without-default-features 会连 pixman 一起关掉，必须显式 --enable-pixman
#
# 依赖：NDK r27c、meson>=1.2、ninja、python3、pkg-config、autoconf/automake/libtool
#
# 用法：
#   scripts/build-qemu-android.sh                # 全流程，产物在 $WORK/out/
#   NDK=/path/to/ndk scripts/build-qemu-android.sh
#
set -euo pipefail

QEMU_VERSION="${QEMU_VERSION:-9.2.3}"
GLIB_VERSION="${GLIB_VERSION:-2.80.5}"
API="${API:-30}"
WORK="${WORK:-$(pwd)/.build-qemu}"
NDK="${NDK:-${ANDROID_NDK_ROOT:-$WORK/ndk}}"
SRC="$WORK/src"
PREFIX="$WORK/sysroot"
OUT="$WORK/out"
JOBS="${JOBS:-$(nproc)}"

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/aarch64-linux-android${API}-clang"
CXX="$TC/aarch64-linux-android${API}-clang++"
export CC CXX
export AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip"
export CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PATH="$TC:$PATH"

log() { printf '\033[1;33m[qemu]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[err]\033[0m %s\n' "$*" >&2; exit 1; }

fetch() { # fetch <url> <file>
    [ -f "$SRC/$2" ] || curl -sSL --retry 3 -o "$SRC/$2" "$1"
    log "源码 $2"
}

prepare() {
    mkdir -p "$SRC" "$PREFIX" "$OUT" "$WORK/logs"
    [ -x "$CC" ] || die "找不到 $CC（设置 NDK=... 指向 NDK r27+）"
    command -v meson >/dev/null || die "需要 meson"
    command -v ninja >/dev/null || die "需要 ninja"

    fetch "https://gitlab.com/qemu-project/qemu/-/archive/v${QEMU_VERSION}/qemu-v${QEMU_VERSION}.tar.gz" qemu.tar.gz
    fetch "https://gitlab.gnome.org/GNOME/glib/-/archive/${GLIB_VERSION}/glib-${GLIB_VERSION}.tar.gz" glib.tar.gz
    fetch "https://codeload.github.com/PCRE2Project/pcre2/tar.gz/refs/tags/pcre2-10.44" pcre2.tar.gz
    fetch "https://codeload.github.com/libffi/libffi/tar.gz/refs/tags/v3.4.6" libffi.tar.gz
    fetch "https://cairographics.org/releases/pixman-0.44.2.tar.gz" pixman.tar.gz
    fetch "https://codeload.github.com/dgibson/dtc/tar.gz/refs/tags/v1.7.0" dtc.tar.gz
    fetch "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v4.8.0/libslirp-v4.8.0.tar.gz" libslirp.tar.gz
    # glib 的 gvdb 子项目：离线构建必须手动提供，否则 meson 报 "no meson.build file"
    fetch "https://codeload.github.com/GNOME/gvdb/tar.gz/0854af0fdb6d527a8d1999835ac2c5059976c210" gvdb.tar.gz

    for f in qemu glib pcre2 libffi pixman dtc libslirp gvdb; do
        [ -d "$SRC/${f}"* ] || tar -xzf "$SRC/$f.tar.gz" -C "$SRC"
    done

    cat > "$WORK/android-aarch64.ini" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
ranlib = '$RANLIB'
pkgconfig = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
needs_exe_wrapper = true

[built-in options]
c_args = ['-fPIC', '-O2']
cpp_args = ['-fPIC', '-O2']
EOF

    # QEMU 用 "${cross_prefix}pkg-config" 查找，NDK 不自带
    ln -sf "$(command -v pkg-config)" "$TC/aarch64-linux-android-pkg-config"
    for t in ar ranlib strip nm objcopy; do ln -sf "llvm-$t" "$TC/aarch64-linux-android-$t"; done
}

build_deps() {
    log "pcre2"
    ( cd "$SRC"/pcre2-* && ./autogen.sh >/dev/null 2>&1 && \
      ./configure --host=aarch64-linux-android --prefix="$PREFIX" --disable-shared \
        --enable-static --disable-pcre2grep --disable-pcre2test >/dev/null && \
      make -j"$JOBS" >/dev/null && make install >/dev/null )

    log "libffi"
    ( cd "$SRC"/libffi-* && ./autogen.sh >/dev/null 2>&1 && \
      ./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
        --prefix="$PREFIX" --disable-shared --enable-static --disable-docs >/dev/null && \
      make -j"$JOBS" >/dev/null && make install >/dev/null )

    log "pixman"
    ( cd "$SRC"/pixman-* && meson setup build-android . --cross-file "$WORK/android-aarch64.ini" \
        --prefix="$PREFIX" --default-library=static --buildtype=release \
        -Dtests=disabled -Ddemos=disabled -Dgtk=disabled -Dlibpng=disabled >/dev/null && \
      ninja -C build-android -j"$JOBS" >/dev/null && ninja -C build-android install >/dev/null )

    log "libfdt"
    ( cd "$SRC"/dtc-* && make -j"$JOBS" CC="$CC" AR="$AR" RANLIB="$RANLIB" \
        CFLAGS="-O2 -fPIC -Ilibfdt" NO_PYTHON=1 NO_YAML=1 libfdt >/dev/null && \
      cp libfdt/libfdt.a "$PREFIX/lib/" && \
      cp libfdt/libfdt.h libfdt/libfdt_env.h libfdt/fdt.h "$PREFIX/include/" && \
      printf 'prefix=%s\nlibdir=${prefix}/lib\nincludedir=${prefix}/include\n\nName: libfdt\nDescription: FDT\nVersion: 1.7.0\nCflags: -I${includedir}\nLibs: -L${libdir} -lfdt\n' "$PREFIX" \
        > "$PREFIX/lib/pkgconfig/libfdt.pc" )

    log "glib（最久，约 5 分钟）"
    ( cd "$SRC"/glib-* && rm -rf build-android subprojects/gvdb/* && \
      tar -xzf "$SRC/gvdb.tar.gz" -C subprojects/gvdb --strip-components=1 && \
      meson setup build-android . --cross-file "$WORK/android-aarch64.ini" --prefix="$PREFIX" \
        --default-library=static --buildtype=release \
        -Dtests=false -Dglib_debug=disabled -Dselinux=disabled -Dlibmount=disabled \
        -Dxattr=false -Dman-pages=disabled -Dintrospection=disabled -Ddocumentation=false \
        -Dnls=disabled -Ddtrace=false -Dsystemtap=false -Dlibelf=disabled >/dev/null && \
      ninja -C build-android -j"$JOBS" >/dev/null && ninja -C build-android install >/dev/null )

    log "libslirp"
    ( cd "$SRC"/libslirp-* && meson setup build-android . --cross-file "$WORK/android-aarch64.ini" \
        --prefix="$PREFIX" --default-library=static --buildtype=release >/dev/null && \
      ninja -C build-android -j"$JOBS" >/dev/null && ninja -C build-android install >/dev/null )
}

patch_qemu() {
    local q="$SRC/qemu-v$QEMU_VERSION"
    # bionic 没有 shm_open(3)：用 memfd 顶替，语义足够（匿名、可 mmap、unlink 无副作用）
    cat > "$q/pocketvm-android-shm.h" <<'HDR'
#ifndef POCKETVM_ANDROID_SHM_H
#define POCKETVM_ANDROID_SHM_H
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
static inline int pocketvm_shm_open(const char *name, int oflag, mode_t mode)
{
    int fd = (int)syscall(__NR_memfd_create, name, 0U);
    (void)mode;
    if (fd < 0) return -1;
    if ((oflag & O_TRUNC) && ftruncate(fd, 0) != 0) { close(fd); return -1; }
    return fd;
}
static inline int pocketvm_shm_unlink(const char *name) { (void)name; return 0; }
#define shm_open pocketvm_shm_open
#define shm_unlink pocketvm_shm_unlink
#endif
HDR
    grep -q pocketvm-android-shm "$q/backends/hostmem-shm.c" || \
        sed -i '1i #include "pocketvm-android-shm.h"' "$q/backends/hostmem-shm.c"
}

build_qemu() {
    local q="$SRC/qemu-v$QEMU_VERSION"
    patch_qemu
    rm -rf "$q/build-android" && mkdir -p "$q/build-android"
    cd "$q/build-android"
    ../configure \
        --cross-prefix="$TC/aarch64-linux-android-" \
        --cc="$CC" --cxx="$CXX" \
        --target-list=aarch64-softmmu \
        --prefix="$OUT" --static --disable-werror \
        --without-default-features \
        --enable-tcg --enable-vnc --enable-slirp --enable-fdt --enable-pixman \
        --disable-docs --disable-tools --disable-guest-agent --disable-plugins \
        --extra-cflags="-I$PREFIX/include -fno-pie" \
        --extra-ldflags="-L$PREFIX/lib -no-pie -Wl,-z,max-page-size=16384" \
        > "$WORK/logs/qemu-configure.log" 2>&1 \
        || die "configure 失败，见 $WORK/logs/qemu-configure.log"

    log "编译 QEMU（约 15-25 分钟）"
    ninja -j"$JOBS" qemu-system-aarch64 > "$WORK/logs/qemu-build.log" 2>&1 \
        || die "编译失败，见 $WORK/logs/qemu-build.log"

    "$STRIP" --strip-unneeded qemu-system-aarch64
    cp qemu-system-aarch64 "$OUT/qemu-system-aarch64"
    log "产物：$OUT/qemu-system-aarch64 ($(du -h "$OUT/qemu-system-aarch64" | cut -f1))"
    log "下一步：scripts/install-qemu.sh $OUT/qemu-system-aarch64"
}

case "${1:-all}" in
    deps) prepare; build_deps ;;
    qemu) prepare; build_qemu ;;
    all)  prepare; build_deps; build_qemu ;;
    *) die "用法：$0 {deps|qemu|all}" ;;
esac
