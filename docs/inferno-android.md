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

## 为什么旧镜像起不来（2026-11-12 复核）

真机上稳定复现的失败点（0.10.26 起）：

```
com.apple.xpc.launchd[1] <Emergency>: Boot task failed: data-protection
panic(cpu 1 caller 0xfffffff008128668): "userspace panic: boot task failure:
data-protection - exited due to exit(2)"
```

上游 FAQ 第一条给出的原因：chip-id hack 被移除、换成一条指令补丁后，
**数据卷与 chip identifier 绑定关系变了**，已有 restore 镜像因此打不开。
同一族日志还有上游 issue #227/#230 的 `init_data_protection: Gigalocker file
(/private/xarts/<UUID>.gl) doesn't exist`，指向还原第二阶段（数据迁移）没做完
或系统盘损坏。维护者对 #230/#159 的答复都是「重启几次 + 看 FAQ」，说明
**卡在 SEP accepted IMG4 / skipping unencrypted volume 附近属于偶发**，重启可解；
但 `data-protection` 这种确定性 panic 不是 App 参数能修的。

结论与口径：

- 启动命令本身与官方 `iphone_startup.sh` 逐字对齐（7 个 namespace、
  `apple-nvram` 挂 nsid=5、`nvme.7` 用 `nstype=8`），不是参数拼错。
- **App 侧修不了旧镜像**。唯一正路是按官方 wiki 用最新代码重新 restore：
  companion VM + `idevicerestore --erase --restore-mode -i 0x1122334455667788
  <14.0b5 IPSW> -T root_ticket.der`，一阶段结束后关虚拟机、打
  InfernoFSPatcher，再完成数据迁移。
- 为此 App 在编辑页提供「恢复模式」开关：打开后 `startApple` 挂
  `-initrd <apple>/038-44135-124.dmg`、加
  `boot-mode=enter_recovery,usb-conn-type=ipv4,usb-conn-addr=<IP>,usb-conn-port=<端口>`。
  开关走 `VmConfig.extraArgs` 的哨兵 `-pvm-apple-restore`（地址用
  `-pvm-apple-usb=<IP>:<端口>` 覆盖，默认 `127.0.0.1:8030`），不改 Room 表结构。
  正常启动时这些参数一个都不出现。
- `sep_nvram` 必须**精确 64 KiB**：Inferno 的 `at24c-eeprom` 会按定值校验后盾文件，
  旧还原包给的是 2 KiB。`AppleAssets` 在导入时补零、启动前再做一次幂等自愈
  （`repair()`），否则用户得为 62 KiB 重新导入 24 GB 还原包。
- 别把「能进 iOS 内核 / 能到 data-protection」写成「iOS 已经能启动」。
  `docs/troubleshooting.md` 有按日志特征分流的排障表。
