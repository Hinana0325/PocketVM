#!/usr/bin/env python3
"""Create the PocketVM v0.10.6 and v0.10.8 GitHub releases.

Two releases, not three: 0.10.7's performance dashboard turned out to have a
silent PID-acquisition failure on Android (Process.pid() reflection is blocked
by module encapsulation on JDK 16+ and is hidden API on ART), which would have
left the dashboard permanently empty on the real device with no error anywhere.
Rather than ship that, the fix landed as 0.10.8 and the dashboard ships here.
v0.10.6 is published as-is for the historical build (Windows install recipe
only); everyone else should take v0.10.8.

Source is pushed separately with gh-sync.py; this only handles the Release +
asset half so the APK and the final edk2 2026.08 firmware are downloadable from
the Releases page.

    export GH_TOKEN="$(bash scripts/gh-token.sh)"   # 或贴一个 repo 权限的 PAT
    python scripts/release_0.10.6_and_0.10.8.py

坑：target_commitish 只收 40 位完整 SHA 或分支名，12 位缩写 SHA 会报
「tag_name is not a valid tag」（错误信息指错了地方，别去纠结 tag 名）。
"""
import os
import sys
import time
import requests

REPO = "Hinana0325/PocketVM"
TOKEN = os.environ["GH_TOKEN"]
API = f"https://api.github.com/repos/{REPO}"
HEADERS = {
    "Authorization": f"Bearer {TOKEN}",
    "Accept": "application/vnd.github+json",
}

EFI = "/workspace/PocketVM/firmware/QEMU_EFI-2026.08.fd"
EFI_NAME = "QEMU_EFI-2026.08.fd"

BODY_0106 = """\
## PocketVM 0.10.6 — Windows 安装配方（全网调研落地）

综合 dariaphoebe、richliu、sekin 三份独立教程在 `virt` 平台安装 Win11 ARM64
的一致结论，把默认配置与回退逻辑按它搭好：

- **显示设备可配 ramfb / virtio-gpu**：ramfb 是社区一致的安装期显示——virtio-gpu
  的驱动交接会让 QEMU 清空 scanout，安装途中出现「Display output is not active.」
  占位画面；ramfb 固定 800x600，固件界面按 **F10** 进 Setup 改 1024x768，
  设置写入 vars.fd 重启保留（重绘残影是已知问题，装好驱动后可切回 virtio-gpu）。
- **挂 VirtIO 驱动盘时安装 ISO 只走 virtio-scsi**：Windows 无法同时配置两个
  ISO 设备，同挂必冻结（richliu 实测）；加载驱动失败时卸掉驱动盘重启即恢复
  AHCI 副本，无需任何 virtio 驱动即可读盘。
- **WinPE 加载驱动路径**：驱动盘盘符 → `vioscsi → w11 → ARM64`（认准含
  `vioscsi.inf` 的目录）。
- **占位画面排查**：Ctrl+Alt+F1 恢复、串口是真相、`console=tty0` 根治。
- **安装配方全文**（关于页 + 控制台引导卡）：22589 可用 / 25226 必坏、
  安装假死处置（`regedit` 改 `Setup=3`）、`bcdedit /set TESTSIGNING ON`、
  进桌面后装 NetKVM / viostor / vioscsi / viogpudo。

> 建议直接装 **0.10.8**：包含本版本全部内容，另有性能占用仪表，并修复了本版本
> 中「子进程 PID 取不到导致健康探针静默失效」的问题（详见 0.10.8 发布说明）。
"""

BODY_0108 = """\
## PocketVM 0.10.8 — 性能占用仪表 + 真机关键修复

包含 **0.10.6 的 Windows 安装配方**与 **0.10.7 的性能占用仪表**全部内容，
并修复一个会让仪表在真机上永久空状态的根因问题。

### 性能占用仪表（0.10.7 内容）
控制台新增「性能」Tab，1 Hz 宿主机侧采样——客户机无需装任何 agent，
Windows 安装过程中同样看得到：

| 指标 | 来源 |
| --- | --- |
| 进程 CPU（单核=100%，多线程 TCG 下可超 100%）+ 60 秒迷你图 | `/proc/<pid>/stat` utime+stime 增量 |
| 每 vCPU 占用 | QMP `query-cpus-fast` 线程号 → `/proc/<tid>/stat` |
| RSS 内存（含占手机物理内存比例） | `/proc/<pid>/statm` resident |
| 磁盘 I/O（MB/s，块层实际读写） | `/proc/<pid>/io` read/write_bytes 增量 |
| 运行时长 | 启动时间戳 |

### 修复：子进程 PID 获取（0.10.8 新增）
`Process.pid()` 的反射 invoke 在桌面 JDK 16+ 撞模块封装
（`IllegalAccessException`），在 ART 上 `UNIXProcess` 整个类是隐藏 API——
两条反射路都断时 `pidOf` 返回 -1，而健康探针与性能仪表一律
「取不到 PID 就静默不启动」。**没有报错，只是永远不出数**——0.10.7 的
仪表在真机上大概率就是这个下场。

修复：新增 `/proc` 扫描兜底（`diagnostics/ChildProcess.kt`）——找 ppid
等于本进程、cmdline 含 qemu 的直接子进程，任何 Linux/ART 上都成立。
配套 9 个新测试（112 个全绿），其中 4 个是**真内核**集成测试：解析测试
JVM 自身 /proc、对真实忙等进程采样出非零 CPU%、两个真实子进程中按
qemu 名命中正确 PID、已回收 PID 返回 null。

### 资产说明
- `pocketvm-0.10.8-debug.apk`：安装包（含 QEMU 11.1.2 原生库 + 固件）。
- `QEMU_EFI-2026.08.fd`：edk2 2026.08 固件（64 MiB），需手动替换时单独取用。
"""

RELEASES = [
    {
        "tag": "v0.10.6",
        "name": "PocketVM 0.10.6 — Windows 安装配方（全网调研落地）",
        "target": "05c54cf8a8938d21614f30fb10a298dddfb577eb",
        "apk": "/tmp/pvm-release-assets/pocketvm-0.10.6-debug.apk",
        "apk_name": "pocketvm-0.10.6-debug.apk",
        "body": BODY_0106,
    },
    {
        "tag": "v0.10.8",
        "name": "PocketVM 0.10.8 — 性能占用仪表 + 真机关键修复",
        "target": "051d50d94a6b46d521bcf9af74a38a5a3e53b484",
        "apk": "/tmp/pvm-release-assets/pocketvm-0.10.8-debug.apk",
        "apk_name": "pocketvm-0.10.8-debug.apk",
        "body": BODY_0108,
    },
]


def call(method, url, **kwargs):
    last = None
    for attempt in range(5):
        try:
            resp = requests.request(method, url, headers=HEADERS, timeout=300, **kwargs)
            if resp.status_code in (200, 201):
                return resp.json()
            last = f"{resp.status_code} {resp.text[:200]}"
            if resp.status_code in (400, 401, 403, 404, 409, 422):
                raise RuntimeError(last)
        except Exception as exc:
            last = str(exc)
        time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"{method} {url} failed: {last}")


def upload_asset(upload_url, path, name, ctype):
    with open(path, "rb") as fh:
        data = fh.read()
    headers = dict(HEADERS)
    headers["Content-Type"] = ctype
    resp = requests.post(
        upload_url,
        params={"name": name},
        headers=headers,
        data=data,
        timeout=600,
    )
    if resp.status_code not in (200, 201):
        raise RuntimeError(f"asset {name} failed: {resp.status_code} {resp.text[:300]}")
    print(f"  uploaded {name} ({len(data) // 1024} KiB)", flush=True)


def main():
    for path in (EFI, *(r["apk"] for r in RELEASES)):
        if not os.path.isfile(path):
            raise SystemExit(f"asset source not found: {path}")

    for rel_spec in RELEASES:
        rel = call(
            "POST",
            f"{API}/releases",
            json={
                "tag_name": rel_spec["tag"],
                "name": rel_spec["name"],
                "body": rel_spec["body"],
                "target_commitish": rel_spec["target"],
                "prerelease": False,
            },
        )
        print(f"release {rel['id']} created ({rel_spec['tag']})", flush=True)
        upload = rel["upload_url"].split("{", 1)[0]
        upload_asset(upload, rel_spec["apk"], rel_spec["apk_name"], "application/vnd.android.package-archive")
        upload_asset(upload, EFI, EFI_NAME, "application/octet-stream")
        print(f"  url: {rel['html_url']}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
