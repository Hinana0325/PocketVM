#!/usr/bin/env bash
# 从 Git Credential Manager 现取 GitHub token，供 GH_TOKEN 使用。
#
#   export GH_TOKEN="$(bash scripts/gh-token.sh)"
#   python scripts/gh-sync.py plan && python scripts/gh-sync.py push -m "提交说明"
#
# 为什么取而不是把 token 写进仓库：这个 token 是账号级的（除 repo 外还带
# delete_repo / admin:org / workflow），泄露掉的不是这一个仓库而是整个账号；
# 而且它会随 GCM 刷新而变，写死在文件里的那份迟早是错的。凭据本来就已经存在
# Windows 凭据管理器里，需要时现取即可，这条命令就是"记录"。
#
# 注意 ~/.git-credentials 里那条 ghp_ 开头的老 PAT 已被吊销（API 返 401），
# 别拿它当凭据用。
set -euo pipefail
printf 'protocol=https\nhost=github.com\n\n' \
  | GCM_INTERACTIVE=never GIT_TERMINAL_PROMPT=0 git credential fill \
  | awk -F= '/^password=/{print $2}'
