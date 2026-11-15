#!/bin/bash
# M4 kernel (6.18.21 android17, virtio built-in) + Pixel first-stage/vendor, with virtio-gpu-pci.
set -euo pipefail
ROOT="$HOME/workspace/android-guest"
KERNEL="$HOME/Downloads/Image-qemu-virt"
SYSTEM="$HOME/workspace/gsi/system.img"
LOG="${1:-$ROOT/out/boot-m4.log}"

exec qemu-system-aarch64 \
  -M virt,gic-version=3 -cpu max -smp 4 -m 4096 \
  -kernel "$KERNEL" \
  -initrd "$ROOT/out/ramdisk-nomod.lz4" \
  -append "console=ttyAMA0,115200 androidboot.console=ttyAMA0 androidboot.hardware=qemu androidboot.force_normal_boot=1 androidboot.selinux=permissive androidboot.verifiedbootstate=orange androidboot.vbmeta.device=none printk.devkmsg=on" \
  -drive file="$SYSTEM",format=raw,if=none,id=sys,readonly=on -device virtio-blk-pci,drive=sys,addr=0x2 \
  -drive file="$ROOT/images/userdata.img",format=raw,if=none,id=user -device virtio-blk-pci,drive=user,addr=0x3 \
  -drive file="$ROOT/images/metadata.img",format=raw,if=none,id=meta -device virtio-blk-pci,drive=meta,addr=0x4 \
  -drive file="$ROOT/images/vendor-pixel.img",format=raw,if=none,id=ven,readonly=on -device virtio-blk-pci,drive=ven,addr=0x5 \
  -device virtio-gpu-pci,addr=0x6 \
  -display none -monitor none -no-reboot -serial "file:$LOG"
