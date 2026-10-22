#!/usr/bin/env python3
"""Push a local git worktree to GitHub through the REST API.

Needed because git-over-HTTPS (github.com:443) is unreachable from this sandbox
while api.github.com works. Creates an initial commit, uploads every tracked file
as a blob, then builds a tree/commit and moves refs/heads/main.
"""
import base64
import json
import os
import subprocess
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
BRANCH = "main"


def call(method, url, **kwargs):
    last = None
    for attempt in range(6):
        try:
            resp = requests.request(method, url, headers=HEADERS, timeout=300, **kwargs)
            if resp.status_code < 400:
                return resp.json() if resp.content else {}
            last = f"{resp.status_code} {resp.text[:300]}"
            # 409 on an empty repo is expected for the very first commit.
            if resp.status_code in (400, 401, 403, 404, 409, 422):
                raise RuntimeError(last)
        except Exception as exc:  # network flakes are the norm here
            last = str(exc)
        time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"{method} {url} failed: {last}")


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    listing = subprocess.run(
        ["git", "ls-files", "-s"], cwd=root, capture_output=True, text=True, check=True
    ).stdout.splitlines()

    entries = []
    for line in listing:
        meta, path = line.split("\t", 1)
        mode = meta.split()[0]
        entries.append((path, "100755" if mode == "100755" else "100644"))

    print(f"{len(entries)} files", flush=True)

    # 1. Seed the repo: the git data API refuses to work on a completely empty repo.
    try:
        head = call("GET", f"{API}/git/ref/heads/{BRANCH}")
        base_sha = head["object"]["sha"]
        base_tree = call("GET", f"{API}/git/commits/{base_sha}")["tree"]["sha"]
        print(f"existing head {base_sha}", flush=True)
    except Exception:
        seed = base64.b64encode(b"# PocketVM\n").decode()
        call("PUT", f"{API}/contents/.gitignore",
             json={"message": "init", "content": seed, "branch": BRANCH})
        head = call("GET", f"{API}/git/ref/heads/{BRANCH}")
        base_sha = head["object"]["sha"]
        base_tree = call("GET", f"{API}/git/commits/{base_sha}")["tree"]["sha"]
        print(f"seeded head {base_sha}", flush=True)

    # 2. Upload blobs, smallest first so a stall on the big one is obvious late.
    entries.sort(key=lambda e: os.path.getsize(os.path.join(root, e[0])))
    blobs = []
    for path, mode in entries:
        full = os.path.join(root, path)
        with open(full, "rb") as fh:
            data = fh.read()
        blob = call("POST", f"{API}/git/blobs",
                    json={"content": base64.b64encode(data).decode(), "encoding": "base64"})
        blobs.append({"path": path, "mode": mode, "type": "blob", "sha": blob["sha"]})
        print(f"  blob {path} ({len(data)//1024} KiB)", flush=True)

    # 3. Tree, commit, ref.
    tree = call("POST", f"{API}/git/trees",
                json={"base_tree": base_tree, "tree": blobs})
    commit = call("POST", f"{API}/git/commits",
                  json={
                      "message": subprocess.run(["git", "log", "-1", "--format=%B"],
                                                cwd=root, capture_output=True, text=True).stdout.strip(),
                      "tree": tree["sha"],
                      "parents": [base_sha],
                  })
    call("PATCH", f"{API}/git/refs/heads/{BRANCH}", json={"sha": commit["sha"], "force": True})
    print(f"pushed commit {commit['sha']}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
