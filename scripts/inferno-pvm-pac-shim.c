/*
 * Override bionic's sigsetjmp/siglongjmp with the PAC-free trampoline.
 * Only used on Android; see pvm-pac-shim.S for the why.
 */
#include "qemu/osdep.h"

#include <setjmp.h>

int pvm_pac_jmp_save(void *env);
void pvm_pac_jmp_restore(void *env, int val);

int sigsetjmp(sigjmp_buf env, int savemask)
{
    (void)savemask; /* QEMU always passes 0 */
    return pvm_pac_jmp_save(env);
}

void siglongjmp(sigjmp_buf env, int val)
{
    pvm_pac_jmp_restore(env, val);
}
