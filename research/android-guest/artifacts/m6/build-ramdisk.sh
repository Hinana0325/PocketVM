#!/bin/bash
# Build a QEMU-virt bootable ramdisk for the Android 17 GSI using the
# Pixel first-stage init. Partitions are exposed as NVMe namespaces because
# the Pixel GKI kernel has NVMe built in and virtio-blk only as a module.
set -euo pipefail

ROOT="$HOME/workspace/android-guest"
SRC="$HOME/workspace/pixel-images/extracted"
TREE="$ROOT/ramdisk"
OUT="$ROOT/out"
mkdir -p "$TREE" "$OUT"

# 1. merge init_boot ramdisk + vendor_boot fragment 0
rm -rf "$TREE"
mkdir -p "$TREE"
cp -a "$SRC/init_ramdisk/." "$TREE/"
cp -a "$SRC/vb/." "$TREE/"
# GKI modules (virtio_blk/virtio_pci live here) so first-stage init can load them
if [ "${PVM_MODULES:-1}" = "1" ]; then
mkdir -p "$TREE/lib/modules" "$TREE/system/lib/modules"
cp -a "$SRC/sdlkm/lib/modules/." "$TREE/lib/modules/"
cp -a "$SRC/sdlkm/lib/modules/." "$TREE/system/lib/modules/"
cat > "$TREE/system/lib/modules/modules.load" <<'ML'
virtio_pci_legacy_dev.ko
virtio_pci_modern_dev.ko
virtio_pci.ko
virtio_blk.ko
ML
cp "$TREE/system/lib/modules/modules.load" "$TREE/lib/modules/modules.load"
fi

# 2. QEMU fstab (NVMe namespaces, ext4)
write_fstab() {
  cat > "$1" <<'EOF'
# QEMU virt experiment: partitions are separate NVMe namespaces.
/dev/block/vda   /system     ext4   ro            wait,first_stage_mount
/dev/block/vdb   /data       ext4   noatime,sync  wait,check,formattable,first_stage_mount,quota
/dev/block/vdc   /metadata   ext4   noatime       wait,check,formattable,first_stage_mount
/dev/block/vdd   /vendor     erofs  ro            wait,first_stage_mount
EOF
  if [ "${PVM_ODM:-0}" = "1" ]; then
    echo "/dev/block/vde   /odm        ext4   ro            wait,first_stage_mount" >> "$1"
  fi
}
write_fstab "$TREE/first_stage_ramdisk/fstab.qemu"
write_fstab "$TREE/fstab.qemu"

# 3. repack as newc cpio + legacy LZ4 frame
cd "$TREE"
rm -f "$OUT/${PVM_RAMDISK_NAME:-ramdisk-qemu.lz4}"
find . -print0 | cpio --null -o -H newc --owner root:root --quiet 2>/dev/null | lz4 -l -12 - "$OUT/${PVM_RAMDISK_NAME:-ramdisk-qemu.lz4}" >/dev/null
ls -l "$OUT/${PVM_RAMDISK_NAME:-ramdisk-qemu.lz4}"
echo OK
