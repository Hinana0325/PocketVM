/*
 * PAC-free coroutine switch for Android/bionic.
 *
 * QEMU's sigaltstack coroutine backend saves context with sigsetjmp() and
 * switches with siglongjmp(). A coroutine may be created on thread A and
 * first entered on thread B; bionic signs SP/LR in the jmp_buf with the
 * per-thread APIA key, so the autiasp in longjmp fails on B. On ARMv9 with
 * FEAT_FPAC that is an immediate SIGILL (QEMU vanishes with exit=132,
 * tombstone at libc longjmp+460).
 *
 * We replace both calls with our own register-save trampoline. QEMU only
 * ever calls sigsetjmp(env, 0), so the signal mask is intentionally ignored.
 * The buffer needs 0xA8 bytes; bionic's sigjmp_buf is larger.
 */
.arch armv8.5-a
.text
.p2align 4
.global pvm_pac_jmp_save
pvm_pac_jmp_save:
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
.global pvm_pac_jmp_restore
pvm_pac_jmp_restore:
    bti jc
    mov w10, w1
    cbnz w10, 1f
    mov w10, #1          /* longjmp(buf, 0) must return 1 */
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
