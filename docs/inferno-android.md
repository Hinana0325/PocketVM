# Android 版 Inferno（iPhone 11 / t8030）构建说明

PocketVM 的 iOS 机型用 **ChefKiss Inferno**（原 QEMUAppleSilicon）作为第二个执行层。
上游：<https://github.com/ChefKissInc/Inferno>，本仓库构建时用的提交是 `cc4302a`
（基于 QEMU 10.0.2）。

## 许可证与源码可得性

Inferno 是 GPLv3（QEMU 衍生部分）+ AGPLv3（项目自有代码）的混合授权。APK 里分发
`libqemu-inferno-system-aarch64.so` 意味着必须能提供对应源码：上游源码 + 本文件
列出的全部修改即可完整重建。修改只有下面四处，外加两个新增文件
（`scripts/inferno-pvm-pac-shim.S` / `.c`）。

## 对上游源码的修改

1. **bionic 兼容：`shm_open`**
   `backends/hostmem-shm.c` 与 `util/oslib-posix.c` 顶部各加一行
   `#include "pocketvm-android-shm.h"`，文件内容用 `memfd_create` 实现
   `shm_open/shm_unlink`（见旧版 `scripts/build-qemu-android*.sh` 里的同名 heredoc）。

2. **PAC-free 协程切换（必须）**
   把 `scripts/inferno-pvm-pac-shim.S/.c` 拷到 `util/`，并在 `util/meson.build` 末尾加：

   ```meson
   pvm_android = cc.compiles('''
   #ifndef __ANDROID__
   #error not android
   #endif
   int main(void) { return 0; }''')
   if pvm_android
     util_ss.add(files('pvm-pac-shim.c', 'pvm-pac-shim.S'))
   endif
   ```

   原因：bionic 的 `sigsetjmp/siglongjmp` 用每线程 APIA 密钥签名 SP/LR，QEMU 的
   sigaltstack 协程允许跨线程首次 enter，`autiasp` 必然失败，ARMv9 直接 SIGILL。

3. **开机图路径可由环境变量覆盖**
   `hw/display/apple_displaypipe_v4.c` 的 `adp_v4_read_and_draw_boot_splash()`：

   ```c
   const char *pvm_splash = getenv("PVM_BOOTSPLASH");
   path = pvm_splash != NULL && pvm_splash[0] != 0
              ? g_strdup(pvm_splash)
              : get_relocated_path(CONFIG_QEMU_ICONDIR
                             "/hicolor/512x512/apps/CKQEMUBootSplash@2x.png");
   ```

   APK 里没有安装前缀，`get_relocated_path` 会指向只读的 nativeLibraryDir 旁边；
   App 启动时把 `PVM_BOOTSPLASH` 指向解压出来的 assets。

4. **lzfse 依赖漏接线**
   `hw/arm/apple-silicon/meson.build` 末尾追加：

   ```meson
   if liblzfse.found()
     arm_common_ss.add(when: 'CONFIG_APPLE_SOC', if_true: liblzfse)
   endif
   ```

   `boot.c` 直接调用 `lzfse_decode_buffer`，但上游只给 block/dmg 挂了依赖；
   Linux 上有系统库兜底时不明显，Android 链接会 undefined。

## 依赖（全部为 Android/bionic 构建）

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| NDK | r27c | API 30，`-Wl,-z,max-page-size=16384` |
| glib | 2.80.5 | 与主执行层共用，已有 `libglib-2.0.so` |
| pixman / libslirp / pcre2 | 0.44.2 / 4.8.0 / 10.44 | 与主执行层共用 |
| libtasn1 | 4.19.0 | Inferno 必需（IMG4/ASN.1） |
| gmp / nettle | 6.3.0 / 3.10.2 | SEP 的 DRBG |
| lzfse | master | IMG4 payload 解压 |
| libpng16 | 1.6.50 | 开机图解码 |
| proxy-libintl | PocketVM 自带 | glib 的 intl 兜底（`g_libintl_*` 符号） |

## configure

```sh
../configure --cross-prefix=$NDK/.../aarch64-linux-android- \
  --cc=$NDK/.../aarch64-linux-android30-clang \
  --target-list=aarch64-softmmu --prefix=$OUT --enable-tools --disable-werror \
  --without-default-features \
  --enable-tcg --enable-vnc --enable-slirp --enable-fdt=internal --enable-pixman \
  --enable-nettle --enable-lzfse --enable-png --disable-guest-agent \
  --extra-cflags="-I$SYSROOT/include" \
  --extra-ldflags="-L$SYSROOT/lib -Wl,-rpath,\$ORIGIN -Wl,--enable-new-dtags -Wl,-z,max-page-size=16384"
```

子项目 `dtc` 需要 `tests=false`（交叉编译无法运行 treegen）；`keycodemapdb`
与 `util/mlib` 需要在 `subprojects/` 与 `util/mlib/` 准备好源码。

## 打包成 jniLibs

- 二进制 strip 后改名 `libqemu-inferno-system-aarch64.so`；
- `libtasn1 / liblzfse / libpng16 / libgmp / libnettle / libhogweed` 改名为
  `lib*.so`、改 SONAME、把调用方的 `DT_NEEDED` 换成无版本号名字；
- `patchelf --set-rpath '$ORIGIN'`；
- `assets/qemu-ios/` 放 `icons/`（开机图）与 `keymaps/`，首次启动解压到
  `files/qemu/share-ios`，启动时 `-L` 指过去。

## 启动参数与已知限制

参数见 `AppleCommandBuilder`：`-M t8030` + 7 个 NVMe namespace（`nvme.7` 必须是
`nstype=8`）。iOS 资源（kernelcache/SEP/nvme.1）受 Apple EULA 约束，不进 APK，
由用户在编辑页导入还原包 zip。

**已知限制**：旧 restore 镜像与当前 Inferno 的 chip-id 补丁不兼容时，launchd 的
`data-protection` 任务 exit(2)，内核打印
`userspace panic: boot task failure: data-protection`。官方 FAQ 要求用最新代码
重新 restore（companion VM + idevicerestore + 14.0b5 IPSW）并打
InfernoFSPatcher；与 Android 移植无关。
