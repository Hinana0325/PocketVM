/*
 * 无签名的协程上下文保存/恢复，用来替换 bionic 的 sigsetjmp/siglongjmp。
 *
 * 为什么需要：QEMU 的 sigaltstack 协程后端用 sigsetjmp/siglongjmp 换栈。
 * 协程由线程 A 创建、在线程 B 首次 enter 时，bionic 的 jmp_buf 里 SP/LR 是
 * 用 A 线程的 APIA 密钥签名过的；B 线程的密钥不同，longjmp 里的 autiasp
 * 校验失败。ARMv9（本机 SM8650）开了 FEAT_FPAC，失败直接 SIGILL——表现为
 * QEMU 随机消失、exit=132、tombstone 停在 libc longjmp+460。
 *
 * 这里只保存 callee-saved 寄存器（x19-x30、SP、d8-d15），与 bionic 的
 * jmp_buf 布局无关：调用方（QEMU）只把缓冲区当作不透明内存，打过补丁的
 * sigsetjmp 与 siglongjmp 成对使用本布局。缓冲区需要 0xA8 字节，
 * bionic 的 sigjmp_buf 远大于此。
 *
 * 汇编与提取（任意 aarch64 交叉工具链均可）：
 *   aarch64-linux-gnu-gcc -c qemu-pac-shim.S -o shim.o
 *   aarch64-linux-gnu-objcopy -O binary shim.o shim.bin
 * 产出的 shim.bin 与 scripts/patch-qemu-pac.py 内嵌的 SHIM_HEX 应当一致。
 */

.arch armv8.5-a
.text
.p2align 4
.global jmp_save
jmp_save:
    bti jc
    stp x19, x20, [x0, #0x00]
    stp x21, x22, [x0, #0x10]
    stp x23, x24, [x0, #0x20]
    stp x25, x26, [x0, #0x30]
    stp x27, x28, [x0, #0x40]
    stp x29, x30, [x0, #0x50]
    mov x9, sp
    str x9, [x0, #0x60]
    stp d8, d9, [x0, #0x68]
    stp d10, d11, [x0, #0x78]
    stp d12, d13, [x0, #0x88]
    stp d14, d15, [x0, #0x98]
    mov w0, #0
    ret

.p2align 4
.global jmp_restore
jmp_restore:
    bti jc
    mov w10, w1
    cbnz w10, 1f
    mov w10, #1          /* longjmp(buf, 0) 必须返回 1 */
1:  ldp d8, d9, [x0, #0x68]
    ldp d10, d11, [x0, #0x78]
    ldp d12, d13, [x0, #0x88]
    ldp d14, d15, [x0, #0x98]
    ldp x19, x20, [x0, #0x00]
    ldp x21, x22, [x0, #0x10]
    ldp x23, x24, [x0, #0x20]
    ldp x25, x26, [x0, #0x30]
    ldp x27, x28, [x0, #0x40]
    ldp x29, x30, [x0, #0x50]
    ldr x9, [x0, #0x60]
    mov sp, x9
    mov w0, w10
    ret
