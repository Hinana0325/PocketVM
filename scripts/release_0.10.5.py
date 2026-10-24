#!/usr/bin/env python3
"""Create the PocketVM v0.10.5 GitHub release and upload the APK + EFI firmware.

Source is pushed separately with gh-sync.py; this only handles the Release +
asset half so the APK and the final edk2 2026.08 firmware are downloadable from
the Releases page.

    export GH_TOKEN="$(bash scripts/gh-token.sh)"   # 或贴一个 repo 权限的 PAT
    python scripts/release_0.10.5.py
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

TAG = "v0.10.5"
APK = "/workspace/PocketVM/android/app/build/outputs/apk/debug/app-debug.apk"
APK_NAME = "pocketvm-0.10.5-debug.apk"
EFI = "/workspace/PocketVM/firmware/QEMU_EFI-2026.08.fd"
EFI_NAME = "QEMU_EFI-2026.08.fd"

BODY = """\
## PocketVM 0.10.5 — UI 重构 + 最终版 edk2 2026.08 固件

本版本把界面从「两块平铺卡片」升级为带底部导航的分层界面，并把固件定为
**edk2 2026.08**（原版 2024.02 作为兜底保留）。QEMU 仍为 11.1.2 动态 PIE。

### UI 重构
- **底部导航 + 顶栏**：虚拟机 / 环境 / 关于 三页切换，新建向导与控制台为独立全屏流。
- **列表页**：渐变 Hero 头 + 聚合统计 + 可展开的环境就绪状态条 + 重做的虚拟机卡片
  （左侧状态色条、规格 chips、磁盘用量进度条、引导配置、失败/异常提示）+ 空状态插画。
- **控制台页**：顶栏显示 VM 名与实时状态；概览 / 日志 两个 Tab，消除卡片墙。
- **新增环境页 / 关于页**：执行环境自检详情、版本与用法说明。
- 保留琥珀色系身份，新增 Hero 渐变、语义色、层级 surface 等主题 token。

### 固件（最终版）
- 默认启用 **edk2 2026.08**（pflash 标准 64 MiB），原版 2024.02 作为兜底并列打包。
- 运行时按文件名排序自动优先 2026.08。
- 修复：提取标记升到 v6（否则升级时旧标记短路、解压不出新固件）；移除会误删
  `QEMU_EFI-*` 固件的清理逻辑。

### 启动方式（不变）
1. 导入 Windows 11 ARM64 的 ISO；
2. 点「启动」，用任意 RFB 客户端连接 VNC（本机 `adb forward tcp:5900 tcp:5900`）；
3. 若停在 UEFI Shell，App 自动接管引导；若仍失败，控制台点「在 Shell 中引导 Windows」。

> 安装完成后在控制台点「卸载安装介质」，之后直接从虚拟盘启动。

### 资产说明
- `pocketvm-0.10.5-debug.apk`：安装包（含 QEMU 11.1.2 原生库 + 双固件）。
- `QEMU_EFI-2026.08.fd`：最终版 EFI 固件（64 MiB），需手动刷写/替换时单独取用。
"""


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
    print(f"uploaded {name} ({len(data) // 1024} KiB)", flush=True)


def main():
    for label, path in (("APK", APK), ("EFI", EFI)):
        if not os.path.isfile(path):
            raise SystemExit(f"{label} not found: {path}")

    rel = call(
        "POST",
        f"{API}/releases",
        json={
            "tag_name": TAG,
            "name": "PocketVM 0.10.5 — UI 重构 + 最终版 edk2 2026.08",
            "body": BODY,
            "target_commitish": "main",
            "prerelease": False,
        },
    )
    print(f"release {rel['id']} created ({TAG})", flush=True)
    upload = rel["upload_url"].split("{", 1)[0]
    upload_asset(upload, APK, APK_NAME, "application/vnd.android.package-archive")
    upload_asset(upload, EFI, EFI_NAME, "application/octet-stream")
    print(f"release url: {rel['html_url']}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
