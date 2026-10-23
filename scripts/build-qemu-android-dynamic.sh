#!/usr/bin/env bash
#
# 交叉编译 QEMU (aarch64-linux-android, TCG only) —— **动态链接**版本。
# 默认 11.1.2；产物结构在 9.x/10.x/11.x 间保持兼容。
#
# 为什么要动态链接（static 版本在真机上 exit=139，已实测）：
#   * NDK 默认产物是 PIE(ET_DYN)，由 Android 自己的 /system/bin/linker64 做
#     自重定位；--static 会把这个环节整段拿掉，bionic 静态运行时在部分 ROM 上
#     在 main 之前就 SIGSEGV。
#   * ET_EXEC + -no-pie 重编后 ELF 完全正常（无重定位、16 KB 对齐），真机依旧
#     139 —— 说明问题不在 PIE 标志位，而在静态 bionic 运行时本身。
#   * Termux 的 QEMU 也是动态 PIE，这是 Android 上唯一被大规模验证的形态。
#
# jniLibs 的坑：AGP 只打包 `*.so`，所以所有依赖库必须改名为 lib*.so，
# 同时把 SONAME 和每个 ELF 的 DT_NEEDED 一起改掉，否则 linker 按
# "libglib-2.0.so.0" 找不到名为 "libglib-2.0.so" 的文件。
# 运行时靠 DT_RUNPATH=$ORIGIN 在 nativeLibraryDir 里找同级 .so。
#
# 依赖：NDK r27c、meson>=1.2、ninja、python3、pkg-config、patchelf
#
# 用法：
#   NDK=/path/to/ndk scripts/build-qemu-android-dynamic.sh all
#   产物：$WORK/out-jni/*.so（直接 cp 到 app/src/main/jniLibs/arm64-v8a/）
#
set -euo pipefail
QEMU_VERSION="${QEMU_VERSION:-11.1.2}"
GLIB_VERSION="${GLIB_VERSION:-2.80.5}"
API="${API:-30}"
WORK="${WORK:-$(pwd)/.build-qemu}"
NDK="${NDK:-${ANDROID_NDK_ROOT:-$WORK/ndk}}"
SRC="$WORK/src"
PREFIX="$WORK/sysroot"
OUT="$WORK/out-jni"
JOBS="${JOBS:-$(nproc)}"

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$TC/aarch64-linux-android${API}-clang"
CXX="$TC/aarch64-linux-android${API}-clang++"
export CC CXX
export AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip"
export CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC"
export LDFLAGS="-Wl,-z,max-page-size=16384"
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
export PATH="$TC:$PATH"

log() { printf '\033[1;33m[qemu]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[err]\033[0m %s\n' "$*" >&2; exit 1; }

fetch() { # fetch <url> <file>
    [ -f "$SRC/$2" ] || curl -sSL --retry 3 -o "$SRC/$2" "$1"
    log "源码 $2"
}

# Prefer an already-extracted/cloned tree; GNOME's mirrors are unreachable from
# some networks, so glib is normally a `git clone --depth 1 --branch 2.80.5`.
ensure_src() { # ensure_src <name-glob> <url> <tarball>
    # Trailing slash so only directories match, not the .tar.gz itself.
    if ls -d "$SRC"/$1/ >/dev/null 2>&1; then
        log "复用已存在的 $1"
        return 0
    fi
    fetch "$2" "$3"
}

prepare() {
    mkdir -p "$SRC" "$PREFIX" "$OUT" "$WORK/logs"
    [ -x "$CC" ] || die "找不到 $CC（设置 NDK=... 指向 NDK r27+）"
    command -v meson >/dev/null || die "需要 meson"
    command -v ninja >/dev/null || die "需要 ninja"
    command -v patchelf >/dev/null || die "需要 patchelf（apt install patchelf）"

    ensure_src "qemu-v${QEMU_VERSION}" "https://gitlab.com/qemu-project/qemu/-/archive/v${QEMU_VERSION}/qemu-v${QEMU_VERSION}.tar.gz" qemu.tar.gz
    # GNOME 自己的源在某些网络下会对每次连接 reset（实测在这里连续失败），所以 glib
    # 走 GNOME 的官方 GitHub 镜像 —— 也就是上面注释里一直在用的那种取法。
    ensure_src "glib-*" "https://codeload.github.com/GNOME/glib/tar.gz/refs/tags/${GLIB_VERSION}" glib.tar.gz
    ensure_src "pcre2-*" "https://codeload.github.com/PCRE2Project/pcre2/tar.gz/refs/tags/pcre2-10.44" pcre2.tar.gz
    ensure_src "libffi-*" "https://codeload.github.com/libffi/libffi/tar.gz/refs/tags/v3.4.6" libffi.tar.gz
    ensure_src "pixman-*" "https://cairographics.org/releases/pixman-0.44.2.tar.gz" pixman.tar.gz
    ensure_src "libslirp-*" "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v4.8.0/libslirp-v4.8.0.tar.gz" libslirp.tar.gz
    ensure_src "gvdb" "https://codeload.github.com/GNOME/gvdb/tar.gz/0854af0fdb6d527a8d1999835ac2c5059976c210" gvdb.tar.gz

    for f in qemu glib pcre2 libffi pixman libslirp; do
        ls -d "$SRC/${f}-"*/ >/dev/null 2>&1 || tar -xf "$SRC/$f.tar."* -C "$SRC"
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
c_link_args = ['-Wl,-z,max-page-size=16384']
cpp_link_args = ['-Wl,-z,max-page-size=16384']
EOF

    ln -sf "$(command -v pkg-config)" "$TC/aarch64-linux-android-pkg-config"
    for t in ar ranlib strip nm objcopy; do ln -sf "llvm-$t" "$TC/aarch64-linux-android-$t"; done
}

build_deps() {
    log "pcre2 (shared)"
    ( cd "$SRC"/pcre2-* && ./autogen.sh >/dev/null 2>&1 && \
      ./configure --host=aarch64-linux-android --prefix="$PREFIX" \
        --enable-shared --disable-static --disable-pcre2grep --disable-pcre2test >/dev/null && \
      make -j"$JOBS" >/dev/null && make install >/dev/null )

    log "libffi (shared)"
    ( cd "$SRC"/libffi-* && ./autogen.sh >/dev/null 2>&1 && \
      ./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
        --prefix="$PREFIX" --enable-shared --disable-static --disable-docs >/dev/null && \
      # 用较新的 automake 重新生成后，libffi 3.4.x 会额外为 --build 宿主再编一份，
      # 并在子目录的 all-configured 目标上失败（目标库此时已经编好）。让 install
      # 决定成败即可：它依赖真实产物，编不出来照样会失败。
      { make -j"$JOBS" >/dev/null 2>&1 || true; } && \
      make install >/dev/null )

    log "pixman (shared)"
    ( cd "$SRC"/pixman-* && rm -rf build-android && \
      meson setup build-android . --cross-file "$WORK/android-aarch64.ini" \
        --prefix="$PREFIX" --default-library=shared --buildtype=release \
        -Dtests=disabled -Ddemos=disabled -Dgtk=disabled -Dlibpng=disabled >/dev/null && \
      ninja -C build-android -j"$JOBS" >/dev/null && ninja -C build-android install >/dev/null )

    log "glib (shared)"
    ( cd "$SRC"/glib-* && \
      [ -d subprojects/gvdb ] || { mkdir -p subprojects && tar -xzf "$SRC/gvdb.tar.gz" -C subprojects && \
        mv subprojects/gvdb-* subprojects/gvdb; }
      rm -rf build-android && \
      meson setup build-android . --cross-file "$WORK/android-aarch64.ini" \
        --prefix="$PREFIX" --default-library=shared --buildtype=release \
        -Dtests=false -Dintrospection=disabled -Ddocumentation=false \
        -Dman-pages=disabled -Dselinux=disabled -Dlibmount=disabled \
        -Dglib_debug=disabled >/dev/null && \
      ninja -C build-android -j"$JOBS" >/dev/null && ninja -C build-android install >/dev/null )

    log "libslirp (shared)"
    ( cd "$SRC"/libslirp-* && rm -rf build-android && \
      meson setup build-android . --cross-file "$WORK/android-aarch64.ini" \
        --prefix="$PREFIX" --default-library=shared --buildtype=release >/dev/null && \
      ninja -C build-android -j"$JOBS" >/dev/null && ninja -C build-android install >/dev/null )
}

patch_qemu() {
    local q="$SRC/qemu-v$QEMU_VERSION"
    # bionic 没有 shm_open(3)：hostmem-shm.c 编译失败，用 memfd 垫片顶上。
    cat > "$q/pocketvm-android-shm.h" <<'HDR'
#ifndef POCKETVM_ANDROID_SHM_H
#define POCKETVM_ANDROID_SHM_H
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>
static inline int pocketvm_shm_open(const char *name, int oflag, mode_t mode)
{
    /* memfd_create rejects names containing '/', but every POSIX shm name
     * starts with one ("/qemu-<pid>-shm-<n>"), so drop the leading slash. */
    const char *memfd_name = (name && name[0] == '/') ? name + 1 : name;
    int fd = (int)syscall(__NR_memfd_create, memfd_name, 0U);
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
    # Both files call shm_open(3); inject the memfd shim into each.
    for f in backends/hostmem-shm.c util/oslib-posix.c; do
        [ -f "$q/$f" ] || continue
        grep -q pocketvm-android-shm "$q/$f" || \
            sed -i '1i #include "pocketvm-android-shm.h"' "$q/$f"
    done

    # QEMU 10+ hard-requires librt when shm_open is absent. bionic has no librt
    # (shm_open is shimmed / GC'd), so restore the 9.x optional lookup.
    sed -i "s/rt = cc.find_library('rt', required: true)/rt = cc.find_library('rt', required: false)/" "$q/meson.build"

    # The `+ds` tarball ships no prebuilt ppc .dtb and the host has no dtc.
    # PocketVM only builds aarch64-softmmu and never uses these; an empty
    # meson file makes `subdir('dtb')` a no-op.
    if [ -f "$q/pc-bios/dtb/meson.build" ]; then
        : > "$q/pc-bios/dtb/meson.build"
    fi
}

build_qemu() {
    local q="$SRC/qemu-v$QEMU_VERSION"
    patch_qemu

    # `--enable-fdt=internal` builds the bundled dtc, which the Debian `+ds`
    # tarball strips (no submodules). Reuse a dtc tree from an earlier build so
    # configure never has to fetch it from the network.
    if [ ! -d "$q/subprojects/dtc" ]; then
        local dtc_src
        dtc_src="$(ls -d "$SRC"/qemu-*/subprojects/dtc 2>/dev/null | grep -v "$q/subprojects/dtc" | head -1)"
        if [ -n "$dtc_src" ]; then
            cp -r "$dtc_src" "$q/subprojects/dtc"
            rm -f "$q/subprojects/dtc/.meson-subproject-wrap-hash.txt"
            log "复用 dtc 子项目：$dtc_src"
        fi
    fi

    # The Debian `+ds` tarball strips the prebuilt firmware blobs from pc-bios.
    # meson still needs the edk2 .fd.bz2 inputs, so reuse them from an earlier
    # build tree; --disable-install-blobs stops it installing the rest.
    if [ ! -f "$q/pc-bios/edk2-aarch64-code.fd.bz2" ]; then
        local q_real pb_file pb_src
        q_real="$(readlink -f "$q")"
        # Find a tree that actually has the blob, ignoring the current one
        # (which may be reached through a differently named symlink).
        pb_file="$(ls -d "$SRC"/qemu-*/pc-bios/edk2-aarch64-code.fd.bz2 2>/dev/null | grep -v "^$q_real/" | head -1)"
        if [ -n "$pb_file" ]; then
            pb_src="$(dirname "$pb_file")"
            for f in "$pb_src"/*; do
                local b; b="$(basename "$f")"
                [ -e "$q/pc-bios/$b" ] || cp "$f" "$q/pc-bios/$b"
            done
            log "复用 pc-bios 固件：$pb_src"
        fi
    fi

    rm -rf "$q/build-android" && mkdir -p "$q/build-android"
    cd "$q/build-android"
    # 关键差异：没有 --static，保持 PIE；--enable-tools 顺带产出 qemu-img。
    ../configure \
        --cross-prefix="$TC/aarch64-linux-android-" \
        --cc="$CC" --cxx="$CXX" \
        --target-list=aarch64-softmmu \
        --prefix="$OUT" --enable-tools --disable-werror \
        --without-default-features \
        --enable-tcg --enable-vnc --enable-slirp --enable-fdt=internal --enable-pixman \
        --disable-docs --disable-guest-agent --disable-plugins --disable-install-blobs \
        --extra-cflags="-I$PREFIX/include" \
        --extra-ldflags="-L$PREFIX/lib -Wl,-rpath,\$ORIGIN -Wl,--enable-new-dtags -Wl,-z,max-page-size=16384" \
        > "$WORK/logs/qemu-configure.log" 2>&1 \
        || die "configure 失败，见 $WORK/logs/qemu-configure.log"

    log "编译 QEMU + qemu-img（约 15-25 分钟）"
    ninja -j"$JOBS" qemu-system-aarch64 qemu-img > "$WORK/logs/qemu-build.log" 2>&1 \
        || die "编译失败，见 $WORK/logs/qemu-build.log"

    cp qemu-system-aarch64 "$OUT/libqemu-system-aarch64.so"
    cp qemu-img "$OUT/libqemu-img.so"
    "$STRIP" --strip-unneeded "$OUT/libqemu-system-aarch64.so" "$OUT/libqemu-img.so"
    log "产物：$(du -h "$OUT"/libqemu-system-aarch64.so | cut -f1) + $(du -h "$OUT"/libqemu-img.so | cut -f1)"
}

# 递归收集 DT_NEEDED 里来自 $PREFIX 的库，改名 + 改 SONAME + 改调用方。
package_libs() {
    local tmp="$WORK/libmap.txt"
    : > "$tmp"

    # BFS: start from the two executables, then walk每个私有库自己的 NEEDED,
    # otherwise glib's own deps (libintl, libpcre2-8) are missed.
    local queue=("$OUT/libqemu-system-aarch64.so" "$OUT/libqemu-img.so")
    local scanned=""
    while [ ${#queue[@]} -gt 0 ]; do
        local f="${queue[0]}"; queue=("${queue[@]:1}")
        case " $scanned " in *" $f "*) continue ;; esac
        scanned="$scanned $f"
        while read -r need; do
            [ -n "$need" ] || continue
            local real="$PREFIX/lib/$need"
            [ -e "$real" ] || continue
            local resolved; resolved="$(readlink -f "$real")"
            local newname; newname="$(echo "$need" | sed -E 's/\.so(\.[0-9]+)*$/.so/')"
            if ! grep -q "^$newname	" "$tmp"; then
                printf '%s\t%s\t%s\n' "$need" "$newname" "$resolved" >> "$tmp"
                cp -f "$resolved" "$OUT/$newname"
                patchelf --set-soname "$newname" "$OUT/$newname"
                log "打包依赖 $newname（原 $need）"
                queue+=("$OUT/$newname")
            fi
        done < <(readelf -d "$f" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
    done

    [ -s "$tmp" ] || die "没有解析到任何私有依赖，构建可能仍是静态的"

    # 把所有 ELF 的 DT_NEEDED 改成新名字（两遍：库之间也互相引用）
    local all=("$OUT"/lib*.so)
    while IFS=$'\t' read -r need newname _; do
        [ "$need" = "$newname" ] && continue
        for f in "${all[@]}"; do
            patchelf --replace-needed "$need" "$newname" "$f" 2>/dev/null || true
        done
    done < "$tmp"

    # meson embeds the build sysroot in RUNPATH; on-device only $ORIGIN (the
    # native library dir) must remain, so the APK does not carry host paths.
    for f in "$OUT"/lib*.so; do
        case "$(basename "$f")" in libpvm-probe.so) continue ;; esac
        patchelf --set-rpath '$ORIGIN' "$f" 2>/dev/null || true
    done

    log "打包完成："
    ls -la "$OUT"/*.so
}

verify() {
    log "依赖闭包检查"
    local missing=0
    for f in "$OUT"/*.so; do
        while read -r need; do
            case "$need" in
                libc.so|libm.so|libdl.so|liblog.so|libz.so|libandroid.so|libstdc++.so|libc++_shared.so) continue ;;
            esac
            [ -f "$OUT/$need" ] || { echo "  缺失：$(basename "$f") 需要 $need"; missing=1; }
        done < <(readelf -d "$f" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
    done
    [ "$missing" -eq 0 ] || die "依赖不完整，装到设备上会报 library not found"
    for f in "$OUT"/libqemu-system-aarch64.so "$OUT"/libqemu-img.so; do
        echo "--- $(basename "$f")"
        readelf -h "$f" | egrep "Type|Machine"
        readelf -d "$f" | egrep "RUNPATH|RPATH|NEEDED"
    done
}

case "${1:-all}" in
    deps) prepare; build_deps ;;
    qemu) prepare; build_qemu; package_libs; verify ;;
    package) package_libs; verify ;;
    all)  prepare; build_deps; build_qemu; package_libs; verify ;;
    *) die "用法：$0 {deps|qemu|all}" ;;
esac
