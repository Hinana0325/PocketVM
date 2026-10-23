#!/usr/bin/env python3
"""把工作副本增量同步到 GitHub（走 api.github.com 的 git data API）。

存在的理由：本机 git-over-HTTPS 不通，而这个工作副本根本没有 `.git`
（只有 `.gitignore`），所以 `git push` 和依赖 `git ls-files` 的
`push_via_api.py` 都跑不了。这里用「本地 `git hash-object` 对比远端树」代替索引。

    export GH_TOKEN="$(bash scripts/gh-token.sh)"
    python scripts/gh-sync.py plan              # 只读：列出会新增/修改什么
    python scripts/gh-sync.py push -m "说明"    # 上传并前移 refs/heads/main

`push` 用 base_tree 增量建树：只替换列出的路径，远端独有的文件原样保留，
不删任何东西。每个 blob 上传后都拿服务端返回的 SHA-1 和本地算出的对一遍，
推完再拉一次递归树逐文件复核，避免"推上去了但不是这份内容"。
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
SKIP_DIRS = {".git", ".gradle", "build", ".kotlin", ".idea", ".mimocode", ".qoder",
             ".build-qemu", "dist", "__pycache__"}
SKIP_FILES = {".DS_Found.000", "Thumbs.db"}
SKIP_NAMES = {".DS_Store", "local.properties"}
SKIP_SUFFIX = (".iml", ".apk", ".aab", ".log", ".pyc")


def die(msg):
    print(msg, file=sys.stderr)
    sys.exit(1)


# 仓库里有中文路径，Windows 控制台默认 cp936 会在打印时直接抛异常。
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")


class GitHub:
    def __init__(self, repo, token):
        self.repo, self.token = repo, token

    def call(self, method, path, payload=None, tries=6):
        url = f"{API}/repos/{self.repo}{path}"
        for attempt in range(tries):
            req = urllib.request.Request(
                url,
                data=None if payload is None else json.dumps(payload).encode(),
                headers={"Authorization": f"token {self.token}",
                         "Accept": "application/vnd.github+json",
                         "X-GitHub-Api-Version": "2022-11-28",
                         "User-Agent": "pocketvm-gh-sync"},
                method=method,
            )
            try:
                with urllib.request.urlopen(req, timeout=300) as resp:
                    body = resp.read()
                    return json.loads(body) if body else {}
            except urllib.error.HTTPError as exc:
                detail = exc.read().decode(errors="replace")[:300]
                if exc.code in (400, 401, 403, 404, 422):
                    die(f"{method} {path} -> {exc.code} {detail}")
            except Exception:
                pass
            time.sleep(2 * (attempt + 1))
        die(f"{method} {path} 连续 {tries} 次失败")
        return {}


def local_files(root, extra_skip):
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        rel = os.path.relpath(dirpath, root).replace("\\", "/")
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            if name in SKIP_FILES or name in SKIP_NAMES or name.endswith(SKIP_SUFFIX):
                continue
            path = name if rel == "." else f"{rel}/{name}"
            if path in extra_skip:
                continue
            out.append((path, os.path.join(dirpath, name)))
    return out


def hash_local(paths, root):
    """Raw-bytes blob SHA-1, no text filters — the same thing the API will store.

    Paths go in and come out as UTF-8 bytes on purpose: Windows Python would
    otherwise encode stdin with the console codepage (cp936) and Git for Windows
    would then fail to open any non-ASCII filename.
    """
    inp = "".join(p.replace("\\", "/") + "\n" for p in paths)
    proc = subprocess.run(["git", "hash-object", "--no-filters", "--stdin-paths"],
                          input=inp.encode("utf-8"), capture_output=True, cwd=root)
    if proc.returncode != 0:
        die(f"git hash-object 失败：{proc.stderr.decode('utf-8', 'replace').strip()[:300]}")
    return proc.stdout.decode("utf-8").split()


def mode_for(path, full, remote_mode):
    if remote_mode:
        return remote_mode
    with open(full, "rb") as fh:
        return "100755" if fh.read(2) == b"#!" else "100644"


def plan(gh, root, extra_skip, plan_path):
    head = gh.call("GET", "/git/ref/heads/main")["object"]["sha"]
    commit = gh.call("GET", f"/git/commits/{head}")
    tree = gh.call("GET", f"/git/trees/{commit['tree']['sha']}?recursive=1")
    if tree.get("truncated"):
        die("远端树被截断，请改用分页枚举")
    remote = {e["path"]: (e["sha"], e["mode"]) for e in tree["tree"] if e["type"] == "blob"}

    files = local_files(root, extra_skip)
    shas = hash_local([full for _, full in files], root)
    local = {rel: sha for (rel, _), sha in zip(files, shas)}

    added = sorted(p for p in local if p not in remote)
    changed = sorted(p for p in local if p in remote and local[p] != remote[p][0])
    gone = sorted(p for p in remote if p not in local)

    print(f"远端 HEAD {head[:12]}  {commit['message'].splitlines()[0]}")
    print(f"  ({commit['committer']['date']}, {len(remote)} 个文件)")
    for p in added:
        print(f"  + {p}  {os.path.getsize(os.path.join(root, p.replace('/', os.sep))):,} B")
    for p in changed:
        print(f"  ~ {p}")
    for p in gone:
        print(f"  · {p}  仅存在于远端，push 不会删除它")
    total = sum(os.path.getsize(os.path.join(root, p.replace("/", os.sep)))
                for p in added + changed)
    print(f"待上传 {len(added) + len(changed)} 个文件，{total:,} B；未变 {len(local) - len(added) - len(changed)} 个")

    with open(plan_path, "w", encoding="utf-8") as fh:
        json.dump({"head": head, "base_tree": commit["tree"]["sha"], "added": added,
                   "changed": changed, "gone": gone, "local": local,
                   "remote_modes": {p: m for p, (s, m) in remote.items()}}, fh)
    print(f"计划已写入 {plan_path}")
    return 0


def push(gh, root, message, plan_path, author):
    if not os.path.exists(plan_path):
        die("先跑 plan")
    p = json.load(open(plan_path, encoding="utf-8"))
    head = p["head"]
    live = gh.call("GET", "/git/ref/heads/main")["object"]["sha"]
    if live != head:
        die(f"枚举之后 HEAD 已经动了（{live[:12]} != {head[:12]}），重跑 plan")

    paths = p["added"] + p["changed"]
    if not paths:
        print("没有差异，无需推送")
        return 0
    entries = []
    for rel in sorted(paths, key=lambda x: os.path.getsize(os.path.join(root, x.replace("/", os.sep)))):
        full = os.path.join(root, rel.replace("/", os.sep))
        data = open(full, "rb").read()
        blob = gh.call("POST", "/git/blobs",
                       {"content": base64.b64encode(data).decode(), "encoding": "base64"})
        if blob["sha"] != p["local"][rel]:
            die(f"{rel} 服务端返回 {blob['sha']}，本地算出 {p['local'][rel]}")
        entries.append({"path": rel, "type": "blob", "sha": blob["sha"],
                        "mode": mode_for(rel, full, p["remote_modes"].get(rel))})
        print(f"  blob {rel} ({len(data):,} B)", flush=True)

    tree = gh.call("POST", "/git/trees", {"base_tree": p["base_tree"], "tree": entries})
    commit = gh.call("POST", "/git/commits", {"message": message, "tree": tree["sha"],
                                              "parents": [head], "author": author})
    gh.call("PATCH", "/git/refs/heads/main", {"sha": commit["sha"]})
    print(f"已推送 {commit['sha']}（父 {head[:12]}）")

    got = gh.call("GET", f"/git/trees/{commit['tree']['sha']}?recursive=1")
    remote = {e["path"]: e["sha"] for e in got["tree"] if e["type"] == "blob"}
    want = set(p["local"])
    bad = sorted(x for x in want if remote.get(x) != p["local"][x])
    kept = sorted(set(p["gone"]) & set(remote))
    if bad:
        die(f"复核不通过，{len(bad)} 个文件内容不符：{bad[:5]}")
    print(f"复核通过：{len(want)}/{len(want)} 个本地文件逐字节一致；"
          f"远端独有文件保留 {len(kept)} 个 {kept}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=["plan", "push"])
    ap.add_argument("-m", "--message", default=None, help="提交说明（push 必填）")
    ap.add_argument("--repo", default=os.environ.get("GH_REPO", "Hinana0325/PocketVM"))
    ap.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    ap.add_argument("--skip", action="append", default=[],
                    help="额外跳过的仓库相对路径，可重复（默认已跳过 *.log、build/、*.apk 等）")
    ap.add_argument("--plan-file", default=os.path.join(tempfile.gettempdir(), "pvm-gh-sync-plan.json"))
    a = ap.parse_args()

    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        die('没有凭据。先 export GH_TOKEN="$(bash scripts/gh-token.sh)"')
    gh = GitHub(a.repo, token)
    if a.what == "plan":
        return plan(gh, a.root, set(a.skip), a.plan_file)
    if not a.message:
        die("push 需要 -m \"提交说明\"")
    name = subprocess.run(["git", "config", "--global", "user.name"],
                          capture_output=True, text=True, encoding="utf-8").stdout.strip() or "Hinana"
    email = subprocess.run(["git", "config", "--global", "user.email"],
                           capture_output=True, text=True, encoding="utf-8").stdout.strip() or "Hinana0325@users.noreply.github.com"
    return push(gh, a.root, a.message, a.plan_file, {"name": name, "email": email})


if __name__ == "__main__":
    sys.exit(main())
