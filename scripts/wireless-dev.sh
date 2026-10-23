#!/usr/bin/env bash
#
# 无线调试开发环：不插 USB，用 Android 11+ 的「无线调试」完成
# 发现设备 → 连接 → 构建安装 → 启动 → 取一手证据（AgentFeed / 串口日志 / VNC）。
#
# 前置：手机「开发者选项 → 无线调试」已开启，且与主机在同一局域网。
#
# 首次用配对码：无线调试页「使用配对码配对设备」给出 6 位码和一个**配对端口**，
#   ./scripts/wireless-dev.sh pair <IP>:<配对端口> <6位码>
# 之后日常用的连接端口与配对端口不同，本脚本从 mDNS 广告里自己找，不用手填。
#
# 关键点：
# - 端口会飘。手机每次关掉再开「无线调试」，mDNS 里的端口就变，所以 connect
#   一律现取，别把 IP:PORT 抄进任何配置。
# - 局域网模式在真机上不可用（这个 QEMU 构建的加密后端不支持 DES，而校验器强制
#   局域网必须设密码）。所以画面只能走 `adb forward`，vnc 子命令就是做这件事。
# - 重装 APK 会换掉 /data/app/~~<hash>/ 里的哈希，写死的路径立刻过期；本脚本
#   每次都从 `pm path` 重新解析。
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"
APK="$ANDROID/app/build/outputs/apk/debug/app-debug.apk"

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }

find_adb() {
    local cands=(
        "${ANDROID_HOME:-}/platform-tools/adb"
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb"
        "$HOME/AppData/Local/Android/Sdk/platform-tools/adb"
    )
    for c in "${cands[@]}"; do
        [ -x "$c" ] || [ -x "$c.exe" ] && { echo "$c"; return 0; }
    done
    command -v adb 2>/dev/null && return 0
    bad "找不到 adb。设 ANDROID_HOME 或把 platform-tools 加进 PATH。"
    return 1
}

ADB="$(find_adb)" || exit 1
PVM="$ADB"

# 从 mDNS 广告里取所有 _adb-tls-connect._tcp 端点
discover() {
    "$ADB" start-server >/dev/null 2>&1
    printf '  正在局域网里发现设备…\n'
    "$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect\._tcp/ {print $NF}'
}

# 已连接的无线设备（transports 里唯一在线的那台）
current_serial() {
    "$ADB" devices 2>/dev/null | awk '$2=="device" && $1 ~ /^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:/ {print $1; exit}'
}

ensure_connected() {
    local s; s="$(current_serial)"
    if [ -n "$s" ]; then echo "$s"; return 0; fi
    local eps ep
    eps="$(discover)"
    [ -n "$eps" ] || { bad "没发现任何无线调试设备：确认手机开了「无线调试」且与主机同网段。"; return 1; }
    while IFS= read -r ep; do
        [ -z "$ep" ] && continue
        if "$ADB" connect "$ep" >/dev/null 2>&1; then
            s="$(current_serial)"
            [ -n "$s" ] && { ok "已连接 $s"; echo "$s"; return 0; }
        fi
    done <<< "$eps"
    bad "所有端点都连不上。多半是这台主机还没配对过 —— 见脚本头部的 pair 用法。"
    return 1
}

pkg_of() {  # 设备上实际装的包名（可能是 dev.pocketvm 或 .debug 侧载包）
    "$ADB" -s "$1" shell 'pm list packages | grep -oE "^package:dev\.pocketvm(\.debug)?$" | head -1' 2>/dev/null \
        | tr -d '\r' | sed 's/^package://'
}

cmd="${1:-status}"; shift 2>/dev/null || true
case "$cmd" in
    pair)
        [ $# -ge 2 ] || { echo "用法: $0 pair <IP>:<配对端口> <6位码>"; exit 2; }
        "$ADB" pair "$1" "$2"
        ;;

    discover)
        discover
        ;;

    connect)
        ensure_connected >/dev/null || exit 1
        D="$(current_serial)"
        "$ADB" -s "$D" shell 'getprop ro.build.version.release; getprop ro.product.cpu.abi; getconf PAGE_SIZE' 2>/dev/null | tr -d '\r' \
            | paste -sd' ' - | xargs printf '  \033[32m✓\033[0m %s（Android/ABI/页大小）\n'
        ;;

    install)
        if [ "${1:-}" = "--build" ]; then
            printf '  构建中…\n'
            ( cd "$ANDROID" && (./gradlew.bat -PpvmSideBySide :app:assembleDebug || ./gradlew -PpvmSideBySide :app:assembleDebug) \
                | tail -3 ) || { bad "构建失败"; exit 1; }
        fi
        [ -f "$APK" ] || { bad "没有 $APK，先加 --build"; exit 1; }
        D="$(ensure_connected)" || exit 1
        "$ADB" -s "$D" install -r -t "$APK" | tail -1
        ;;

    launch)
        D="$(ensure_connected)" || exit 1
        P="$(pkg_of "$D")"
        [ -n "$P" ] || { bad "设备上没装 dev.pocketvm"; exit 1; }
        # Activity 类名走 namespace（dev.pocketvm），与 applicationId 的 .debug 侧载后缀无关
        "$ADB" -s "$D" shell "am start -n $P/dev.pocketvm.MainActivity" 2>&1 | tail -1
        sleep 3
        "$ADB" -s "$D" shell "ps -A | grep -c '$P'" 2>/dev/null | tr -d '\r' | xargs printf '  \033[32m✓\033[0m %s 进程数 %s\n' "$P"
        ;;

    log)
        D="$(ensure_connected)" || exit 1
        printf '  AgentFeed（Ctrl-C 退出）\n'
        "$ADB" -s "$D" logcat -v time -s PocketVM:I "${@:-}"
        ;;

    health)
        D="$(ensure_connected)" || exit 1
        P="$(pkg_of "$D")"
        F="/storage/emulated/0/Android/data/$P/files/debug/agent.jsonl"
        "$ADB" -s "$D" shell "tail -n ${1:-6} '$F'" 2>/dev/null | tr -d '\r'
        printf '  --- 最新串口（引导走到哪一步）---\n'
        "$ADB" -s "$D" shell "ls -t /storage/emulated/0/Android/data/$P/files/vms/*/logs/*.serial.log 2>/dev/null | head -1 | xargs tail -c 300" 2>/dev/null | tr -d '\r'
        ;;

    vnc)
        D="$(ensure_connected)" || exit 1
        P="$(pkg_of "$D")"
        PORTS="$("$ADB" -s "$D" shell "ls -t /storage/emulated/0/Android/data/$P/files/vms/*/logs/run-*.json 2>/dev/null | head -1 | xargs grep -o '\"vncPort\": *[0-9]*'" 2>/dev/null | tr -d '\r' | grep -oE '[0-9]+$')"
        [ -n "$PORTS" ] || { bad "读不到 vncPort：VM 可能没在跑"; exit 1; }
        L="${1:-5900}"
        "$ADB" -s "$D" forward "tcp:$L" "tcp:$PORTS" >/dev/null
        ok "VNC 隧道：本机 127.0.0.1:$L → 手机 127.0.0.1:$PORTS（用桌面 VNC 客户端连）"
        ;;

    status|*)
        D="$(current_serial)"
        if [ -z "$D" ]; then warn "当前没有已连接的无线设备"; else
            ok "已连接 $D"
            "$ADB" -s "$D" shell 'getprop ro.product.model' 2>/dev/null | tr -d '\r' | xargs printf '  型号 %s\n'
            pkg_of "$D" | xargs printf '  包名 %s\n'
        fi
        printf '用法: %s {pair|discover|connect|install [--build]|launch|log|health [N]|vnc [本机端口]|status}\n' "$0"
        ;;
esac
