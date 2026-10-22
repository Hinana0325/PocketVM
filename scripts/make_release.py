#!/usr/bin/env python3
"""Create a GitHub release for PocketVM and upload the built debug APK.

GitHub's git protocol is unreachable from this sandbox, so the source is pushed
with push_via_api.py; this script handles the *release + asset* half so the APK
is downloadable from the Releases page (the user downloads here, not from git).
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
TAG = "v0.9.2"
APK = "/workspace/PocketVM/android/app/build/outputs/apk/debug/app-debug.apk"
ASSET_NAME = "pocketvm-0.9.2-debug.apk"

BODY = """\
## PocketVM 0.9.2 — UEFI Shell 自动恢复（骁龙备用方案）

本版本**首次**把你之前一直测不到的修复一并送达（此前 0.9.0/0.9.1 的源码未能推送成功，你测到的是旧版本，所以「修了还是失败」）。

### 关键修复
- **SIGILL / exit=132**：引导镜像从 usb-storage 改为 virtio-blk 挂载，消除了某些骁龙机型上 XHCI 链在机器初始化阶段触发的非法指令崩溃。
- **UEFI Shell 自动恢复（本次新增）**：QEMU 启动后后台监听固件串口，一旦检测到 `Shell>` 提示符，自动向串口输入 `fs0..fs3:\\EFI\\BOOT\\BOOTAA64.EFI` 引导命令——不再需要你在手机上手动敲路径（你之前嫌「好难用」的就是这一步）。
- **诊断增强**：串口输出全程写入 `run-*.serial.log` 并纳入「导出诊断包」，真机再次失败时可直接把串口日志发回，便于精确定位。

### 启动方式（不变）
1. 导入 Windows 11 ARM64 的 ISO；
2. 点「启动」，等待固件画面出现后，用任意 RFB 客户端连接 VNC（本机用 `adb forward tcp:5900 tcp:5900`）；
3. 若画面停在 UEFI Shell，App 会自动接管；若仍未启动，点控制台里的「在 Shell 中引导 Windows」手动触发。

> 注意：boot 顺序仍是「引导镜像 → 光盘 → 虚拟盘」，失败时自动降级 标准 → 兼容 → 最小。安装完成后在控制台点「卸载安装介质」，之后直接从虚拟盘启动。
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


def main():
    if not os.path.isfile(APK):
        raise SystemExit(f"APK not found: {APK}")
    # 1. create release
    rel = call(
        "POST",
        f"{API}/releases",
        json={
            "tag_name": TAG,
            "name": "PocketVM 0.9.2 — UEFI Shell 自动恢复",
            "body": BODY,
            "target_commitish": "main",
            "prerelease": False,
        },
    )
    print(f"release {rel['id']} created", flush=True)
    upload = rel["upload_url"].split("{", 1)[0]
    # 2. upload asset
    with open(APK, "rb") as fh:
        data = fh.read()
    headers = dict(HEADERS)
    headers["Content-Type"] = "application/vnd.android.package-archive"
    resp = requests.post(
        upload,
        params={"name": ASSET_NAME},
        headers=headers,
        data=data,
        timeout=600,
    )
    if resp.status_code not in (200, 201):
        raise RuntimeError(f"asset upload failed: {resp.status_code} {resp.text[:300]}")
    print(f"uploaded {ASSET_NAME} ({len(data)//1024} KiB)", flush=True)
    print(f"release url: {rel['html_url']}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
