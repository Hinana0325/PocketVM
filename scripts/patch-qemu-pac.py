#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给 libqemu-system-aarch64.so 打「PAC-free 协程切换」补丁。

背景：ARMv9 手机上 bionic 的 sigsetjmp/siglongjmp 用每线程的 APIA 密钥给
SP/LR 签名。QEMU sigaltstack 协程后端允许一个协程在 A 线程创建、在 B 线程
首次 enter，此时 longjmp 里的 autiasp 校验必然失败；FEAT_FPAC 直接 SIGILL，
进程以 exit=132 消失，tombstone 停在 libc `longjmp+460`。

做法：
  1. 把 PT_NOTE 程序头改写成文件末尾新增的 RX PT_LOAD（4 KiB）；
     页内是 scripts/qemu-pac-shim.S 汇编出来的 jmp_save / jmp_restore；
  2. 把 sigsetjmp / siglongjmp 的 .plt 槽改成 adrp/add/br x16 直跳 shim。

用法：
  # 从原始（未打补丁）的 .so 生成补丁版
  python scripts/patch-qemu-pac.py --input <原始 libqemu-system-aarch64.so> \
                                   --output <目标 libqemu-system-aarch64.so>

  # 校验仓库里那份已经打好补丁（重建 QEMU 后忘记补丁会在这里报错）
  python scripts/patch-qemu-pac.py --verify android/app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so

不依赖 pyelftools：ELF64 头与程序头用 struct 直接解析。
"""

import argparse
import hashlib
import struct
import sys

# 原始 QEMU 11.1.2（edk2 2026.08）执行层的身份。只允许对这个文件打补丁，
# 避免把补丁写到别的构建上、得到一个「能跑但语义不明」的二进制。
ORIG_SIZE = 0x2071388
ORIG_SHA256 = "5d58f119e41aaef2c71319752400fc22104081366e0fd96b5149ee594225b617"

# 打完补丁后的身份，--verify 用它做严格校验。
PATCHED_SIZE = 0x2084000
PATCHED_SHA256 = "23f22b576fca0221dc261ed3eb0f887fdcac9b5ded3e98d294d6efad7a2f9f24"

# 新段：偏移/虚拟地址/长度全部按 16 KiB 对齐。
# 天玑 9xxx / Android 15+ 的 16 KB 页设备要求每个 PT_LOAD 的 p_align ≥ 0x4000，
# 否则 linker64 直接报 empty/misaligned PT_LOAD；原执行层本来就是 0x4000，
# 补丁新增的段不能破坏这一点。
NEW_OFF = 0x2080000      # 新段在文件中的偏移（紧跟原文件末尾）
NEW_VADDR = 0x2140000    # 新段虚拟地址（原 exec 段的 vaddr-offset 差为 0x4000）
NEW_SIZE = 0x4000
NEW_ALIGN = 0x4000
NEW_FLAGS = 5            # PF_R | PF_X

# sigsetjmp / siglongjmp 的 .plt 槽（虚拟地址，不是文件偏移；v2o 会换算）
STUB_SAVE_VA = 0x10D9360
STUB_RESTORE_VA = 0x10D9380

# scripts/qemu-pac-shim.S 的机器码（136 字节）。改了 .S 必须同步这里。
SHIM_HEX = (
    "df2403d5135000a9155801a9176002a9196803a91b7004a91d7805a9e9030091093000f9"
    "08a4066d0aac076d0cb4086d0ebc096d00008052c0035fd61f2003d5df2403d5ea03012a"
    "4a0000352a00805208a4466d0aac476d0cb4486d0ebc496d135040a9155841a9176042a9"
    "196843a91b7044a91d7845a9093040f93f010091e0030a2ac0035fd6"
)

PT_LOAD = 1
PT_NOTE = 4
ELFCLASS64 = 2
ELFDATA2LSB = 1


def die(msg):
    print("错误：" + msg, file=sys.stderr)
    sys.exit(1)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def parse_phdrs(raw):
    if raw[:4] != b"\x7fELF" or raw[4] != ELFCLASS64 or raw[5] != ELFDATA2LSB:
        die("不是 64 位小端 ELF")
    e_phoff, = struct.unpack_from("<Q", raw, 0x20)
    e_phentsize, e_phnum = struct.unpack_from("<HH", raw, 0x36)
    if e_phentsize != 56:
        die("程序头大小异常：%d" % e_phentsize)
    phdrs = []
    for i in range(e_phnum):
        pos = e_phoff + i * e_phentsize
        p_type, p_flags, p_offset, p_vaddr, p_paddr, p_filesz, p_memsz, p_align = \
            struct.unpack_from("<IIQQQQQQ", raw, pos)
        phdrs.append({
            "idx": i, "pos": pos, "type": p_type, "flags": p_flags,
            "off": p_offset, "vaddr": p_vaddr, "filesz": p_filesz,
            "memsz": p_memsz, "align": p_align,
        })
    return phdrs


def v2o(phdrs, va):
    for p in phdrs:
        if p["type"] == PT_LOAD and p["vaddr"] <= va < p["vaddr"] + p["filesz"]:
            return p["off"] + (va - p["vaddr"])
    die("虚拟地址 0x%x 不在任何 PT_LOAD 内" % va)


def enc_adrp(rd, target, pc):
    imm = (target >> 12) - (pc >> 12)
    if not -(1 << 20) <= imm < (1 << 20):
        die("adrp 跳距越界")
    return 0x90000000 | ((imm & 3) << 29) | (((imm >> 2) & 0x7FFFF) << 5) | rd


def enc_add_imm(rd, rn, imm12):
    return 0x91000000 | ((imm12 & 0xFFF) << 10) | (rn << 5) | rd


BR_X16 = 0xD61F0200
NOP = 0xD503201F


def check_original(raw):
    if len(raw) != ORIG_SIZE or sha256(raw) != ORIG_SHA256:
        die(
            "输入不是已知的原始执行层（size=0x%x sha256=%s）。\n"
            "如果这是重建出来的 QEMU，请先确认上面的 SHIM_HEX/桩地址仍适用，"
            "再把新的原始哈希写进本脚本。" % (len(raw), sha256(raw))
        )
    phdrs = parse_phdrs(raw)
    if phdrs[11]["type"] != PT_NOTE:
        die("程序头 #11 不是 PT_NOTE，布局已变化")
    # 16 KB 页设备的硬要求，原执行层本来就满足；补丁不能把它改坏。
    for p in phdrs:
        if p["type"] == PT_LOAD and p["align"] < NEW_ALIGN:
            die("原执行层有 PT_LOAD 的 p_align < 0x4000，16 KB 页设备无法加载")
    for va in (STUB_SAVE_VA, STUB_RESTORE_VA):
        word, = struct.unpack_from("<I", raw, v2o(phdrs, va))
        # adrp x16, ...
        if (word & 0x9F00001F) != 0x90000010:
            die("0x%x 处的 .plt 槽不是 adrp x16，布局已变化" % va)


def build_patched(raw):
    check_original(raw)
    phdrs = parse_phdrs(raw)
    blob = bytes.fromhex(SHIM_HEX)
    if len(blob) > 0xC0:
        die("shim 超过预留空间")

    out = bytearray(raw)
    if len(out) < NEW_OFF:
        out += b"\x00" * (NEW_OFF - len(out))
    page = bytearray(NEW_SIZE)
    page[:len(blob)] = blob
    out += page

    ph = phdrs[11]
    if NEW_OFF % NEW_ALIGN or NEW_VADDR % NEW_ALIGN or NEW_SIZE % NEW_ALIGN:
        die("新增段的偏移/地址/长度不是 16 KB 对齐")
    struct.pack_into(
        "<IIQQQQQQ", out, ph["pos"],
        PT_LOAD, NEW_FLAGS, NEW_OFF, NEW_VADDR, NEW_VADDR,
        NEW_SIZE, NEW_SIZE, NEW_ALIGN,
    )

    save_va = NEW_VADDR
    restore_va = NEW_VADDR + 0x40
    for va, target in ((STUB_SAVE_VA, save_va), (STUB_RESTORE_VA, restore_va)):
        off = v2o(phdrs, va)
        struct.pack_into(
            "<IIII", out, off,
            enc_adrp(16, target, va), enc_add_imm(16, 16, target & 0xFFF),
            BR_X16, NOP,
        )
    return bytes(out)


def verify_patched(raw):
    if len(raw) != PATCHED_SIZE or sha256(raw) != PATCHED_SHA256:
        die(
            "不是预期的补丁版执行层（size=0x%x sha256=%s）。\n"
            "重建 QEMU 后忘记重新打补丁时就会看到这条。"
            % (len(raw), sha256(raw))
        )
    phdrs = parse_phdrs(raw)
    ph = phdrs[11]
    if not (ph["type"] == PT_LOAD and ph["flags"] == NEW_FLAGS
            and ph["off"] == NEW_OFF and ph["vaddr"] == NEW_VADDR
            and ph["filesz"] == NEW_SIZE and ph["align"] == NEW_ALIGN):
        die("程序头 #11 不是预期的新 PT_LOAD")
    for p in phdrs:
        if p["type"] == PT_LOAD and p["align"] < NEW_ALIGN:
            die("有 PT_LOAD 的 p_align < 0x4000，16 KB 页设备无法加载")
    blob = bytes.fromhex(SHIM_HEX)
    if raw[NEW_OFF:NEW_OFF + len(blob)] != blob:
        die("新增页里的 shim 字节不匹配")
    for va, target in ((STUB_SAVE_VA, NEW_VADDR), (STUB_RESTORE_VA, NEW_VADDR + 0x40)):
        words = struct.unpack_from("<IIII", raw, v2o(phdrs, va))
        if words[2] != BR_X16 or words[3] != NOP:
            die("0x%x 处的 .plt 槽没有被改成直跳" % va)
        adrp = words[0]
        imm = ((adrp >> 29) & 3) | (((adrp >> 5) & 0x7FFFF) << 2)
        if imm & (1 << 20):
            imm -= 1 << 21
        if (va & ~0xFFF) + (imm << 12) != (target & ~0xFFF):
            die("0x%x 处的 adrp 目标不对" % va)


def main():
    parser = argparse.ArgumentParser(description="QEMU 执行层 PAC-free 协程补丁")
    parser.add_argument("--input", help="原始 libqemu-system-aarch64.so")
    parser.add_argument("--output", help="输出路径（可与 input 相同）")
    parser.add_argument("--verify", metavar="SO", help="只校验已打补丁的执行层")
    args = parser.parse_args()

    if args.verify:
        with open(args.verify, "rb") as f:
            raw = f.read()
        verify_patched(raw)
        print("校验通过：%s（PAC-free 协程补丁）" % args.verify)
        return
    if not args.input or not args.output:
        parser.error("需要 --input 和 --output，或用 --verify")

    with open(args.input, "rb") as f:
        raw = f.read()
    out = build_patched(raw)
    if sha256(out) != PATCHED_SHA256:
        die("输出哈希与预期不符（size=0x%x sha256=%s），不要发布它"
            % (len(out), sha256(out)))
    with open(args.output, "wb") as f:
        f.write(out)
    print("已生成：%s  size=0x%x  sha256=%s" % (args.output, len(out), sha256(out)))


if __name__ == "__main__":
    main()
