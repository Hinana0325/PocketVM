# Research brief — Windows 11 ARM64 on QEMU

- **Date:** 2026-10-23
- **Depth:** deep (5-8 angles, 2 follow-up rounds, 25+ sources target)
- **Workspace:** `C:\Users\jq200\Desktop\PocketVM-main\research\win11-arm64-on-qemu`

## Refined question

How can Windows on ARM (specifically Windows 11 ARM64) actually be made to boot and install
under QEMU — and what specifically is required to get past the point where WinPE halts during
early kernel initialisation on a stock `-machine virt`?

## Decision at stake

The **PocketVM** project runs QEMU (aarch64, TCG only, no KVM) inside an Android app on an
unrooted Xiaomi 14 Pro (Snapdragon 8 Gen 3). Its stated goal is a successful Windows 11 ARM64
installation. We have already got the install media to boot: the firmware loads the CD, Windows
Setup answers the "Press any key" prompt, `Loading files...` completes, and WinPE takes over.
Then the guest halts.

We need to know whether this is a solvable configuration problem, requires a patched QEMU, or is
not feasible on this platform at all — because that decides whether to keep investing in this
path.

## What we already measured (do not re-derive; treat as inputs)

- Guest executes `0x…637200: 14000000  b #0x…637200` — a `b .` self-loop, ARM64's "cannot
  continue" halt. It is entered by the exception handler after
  `Data Abort, ESR 0x25/0x96000047` (EL1, write, DFSC=0b000111 translation fault level 3),
  `FAR=0x0`, `ELR=0x13c637990`. i.e. Windows boot code wrote to an unmapped address.
- Reproduces **bit-for-bit on an x86_64 PC** with QEMU 11.1.0 (same PC and all general-purpose
  registers over a 70 s window), so it is not device-, CPU-model-, or memory-pressure-specific.
- Unchanged by: `-cpu max` vs `cortex-a76`, `pauth=off`, `-smp 1` vs `4`,
  `tcg,thread=single` vs `multi`, Ubuntu's edk2 vs QEMU's own edk2, `tb-size` 64 vs 512.
- Firmware (ARM virt edk2, both builds) has **no AHCI driver** — an `ide-cd` on `ich9-ahci` is
  invisible to it. It does boot from a `virtio-scsi` CD. Windows/WinPE has no vioscsi, so the ISO
  is currently attached twice (virtio-scsi for the firmware, AHCI for WinPE).
- No TPM is present (`Tpm2SubmitCommand - Tcg2 - Not Found`).

## Scope

**In:** how to run Windows on ARM under QEMU today; which machine types / QEMU versions / forks /
patch sets make it work; what Windows requires from the platform that `virt` does not provide;
practitioner reports of success and of this exact failure; alternatives that get a usable Windows
on ARM VM.

**Out:** x86 Windows under QEMU; generic QEMU performance tuning; Android-app-level UI work.

## Assumptions

- The evaluator cares about a reproducible configuration, not a research prototype.
- Both "it works on hardware-accelerated hosts (KVM/HVF/WHPX)" and "it works under emulation"
  answers are useful, but the distinction must be stated explicitly.
- Russian/Chinese-language sources are acceptable if primary or well-evidenced.

## Angles

- **F1 — Feasibility and known failure modes on stock `-machine virt`.** Is booting Windows 11
  ARM64 on QEMU's standard `virt` machine known to work, and is an early-kernel halt of this shape
  a documented/known failure?
- **F2 — Ready-made projects, scripts and forks.** Existing repos that run/install Windows on ARM
  under QEMU with concrete, reusable configurations (e.g. `raspiduino/waq`,
  `jeremyd2019/winautoconfig`) — what exactly do they do differently?
- **F3 — QEMU upstream and community patches for Windows-on-ARM.** Patch sets adding the ACPI
  tables / devices / machine behaviour Windows needs, their maturity, maintenance status, and
  whether anything landed upstream.
- **F4 — Alternative machine types, firmware and CPU configurations.** `sbsa-ref` and other
  machine models, edk2/ACPI variants, TPM/Secure Boot emulation (swtpm), and which combinations
  Windows accepts. Also whether QEMU version matters (9.x/10.x vs 11.x).
- **F5 — Practitioner experience.** First-hand reports (GitHub issues, forum threads, blog posts):
  who reports a working Windows 11 ARM64 under QEMU, with which configuration, and who reports the
  same halt.
- **F6 — What Windows on ARM requires from the platform.** Microsoft's and analysts' statements on
  the firmware/ACPI/UEFI contract Windows ARM64 assumes (minimum requirements, required ACPI
  objects, why generic `virt` may be insufficient).
- **F7 — Alternative routes to a usable Windows ARM64 instance.** Preparing an image elsewhere
  (offline install, sysprep, disk-image capture) and booting it under QEMU; whether
  Windows 10 ARM64 or Windows Server ARM64 is more permissive than Windows 11; ARM-on-ARM
  hypervisor options.