#!/usr/bin/env bash
#
# 用最短的 classpath 直接跑 JUnit，绕开 Gradle 在 Windows 上的命令行长度限制。
#
# 背景：`:app:testDebugUnitTest` 会把整个测试运行时 classpath（本机实测 490 个 jar、
# 路径总长约 81 K 字符）拼进 java 启动参数，而 Windows 的命令行上限是 32767，参数被
# 截断后测试进程根本起不来，报成 `ClassNotFoundException: <某段路径>`。详见 AGENTS.md。
#
# 这个脚本只把「被测类 + 测试类 + junit/hamcrest/kotlin-stdlib」放进 classpath，
# 所以**只能跑不依赖 Android / Hilt / Robolectric 的测试类**（纯 java.io、纯算法那种）。
# 依赖注入或 Android 框架的用例仍然要走 CI。
#
# 用法：
#   scripts/run-jvm-test.sh dev.pocketvm.LogTailReaderTest [更多测试类...]
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID="$ROOT/android"

MAIN_CLASSES="$ANDROID/app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"
TEST_CLASSES="$ANDROID/app/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"

if [ ! -d "$TEST_CLASSES" ]; then
    echo "找不到编译产物：$TEST_CLASSES" >&2
    echo "先跑：cd android && ./gradlew :app:compileDebugUnitTestKotlin" >&2
    exit 1
fi

if [ "$#" -eq 0 ]; then
    echo "用法：$0 <测试类全名> [更多...]" >&2
    echo "例：  $0 dev.pocketvm.LogTailReaderTest" >&2
    exit 1
fi

GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
find_jar() {
    find "$GRADLE_CACHE/$1" -name "$2" 2>/dev/null | grep -v sources | head -1
}

JUNIT="$(find_jar junit/junit 'junit-4*.jar')"
HAMCREST="$(find_jar org.hamcrest 'hamcrest-core-*.jar')"
KOTLIN_STDLIB="$(find_jar org.jetbrains.kotlin/kotlin-stdlib 'kotlin-stdlib-2*.jar')"
# 可选：用到 kotlinx.serialization 的测试（如 SnapshotMetaTest）需要这两个。
SER_JSON="$(find_jar org.jetbrains.kotlinx/kotlinx-serialization-json-jvm 'kotlinx-serialization-json-jvm-*.jar')"
SER_CORE="$(find_jar org.jetbrains.kotlinx/kotlinx-serialization-core-jvm 'kotlinx-serialization-core-jvm-*.jar')"

for jar in "$JUNIT" "$HAMCREST" "$KOTLIN_STDLIB"; do
    if [ -z "$jar" ]; then
        echo "缺少依赖 jar（先跑一次 gradle 让缓存落盘）" >&2
        exit 1
    fi
done

# Windows 的 java 只认 Windows 路径和 ';' 分隔符；MSYS 下用 cygpath 转换。
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        SEP=';'
        winpath() { cygpath -w "$1"; }
        ;;
    *)
        SEP=':'
        winpath() { printf '%s' "$1"; }
        ;;
esac

CP="$(winpath "$MAIN_CLASSES")$SEP$(winpath "$TEST_CLASSES")$SEP$(winpath "$JUNIT")$SEP$(winpath "$HAMCREST")$SEP$(winpath "$KOTLIN_STDLIB")"
[ -n "$SER_JSON" ] && CP="$CP$SEP$(winpath "$SER_JSON")"
[ -n "$SER_CORE" ] && CP="$CP$SEP$(winpath "$SER_CORE")"

exec java -cp "$CP" org.junit.runner.JUnitCore "$@"
