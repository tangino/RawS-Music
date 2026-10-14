#!/bin/sh
#
# RawS-Music 一键构建（Debug）—— Linux / macOS
#
#   ./build_debug.sh                 构建 Debug APK
#   ./build_debug.sh --clean         先 clean 再构建
#   ./build_debug.sh --install       构建完用 adb 安装到已连接设备
#   ./build_debug.sh --no-daemon     不使用 Gradle 守护进程
#   ./build_debug.sh -h              显示帮助
#
# 本脚本是**独立**的：不假设当前目录，也不再需要其它辅助脚本。
# （build_release.sh 与它保持同一套环境探测逻辑，改一处请同步另一处。）
#
# ⚠️ 两个本仓库特有的前提，脚本会替你处理并明确报错：
#   1. **必须 JDK 21**。Gradle 8.11.1 跑不了 JDK 25（本机 Android Studio 自带的 jbr 就是 25）。
#   2. 本机 clone **缺少 Unix 版 `gradlew`**（仓库只跟踪了 gradlew.bat），所以这里不依赖它 ——
#      优先用可执行的 ./gradlew，没有就直接调 gradle-wrapper.jar。
#
set -eu

# ---------------------------------------------------------------- 参数

TASK=":app:assembleDebug"
VARIANT="debug"
VARIANT_LABEL="Debug"
DO_CLEAN=0
DO_INSTALL=0
DO_FRESH=0
NO_DAEMON=0

while [ $# -gt 0 ]; do
    case "$1" in
        --clean)      DO_CLEAN=1 ;;
        --fresh)      DO_FRESH=1 ;;
        --install)    DO_INSTALL=1 ;;
        --no-daemon)  NO_DAEMON=1 ;;
        -h|--help)
            cat <<'EOF'
RawS-Music 一键构建（Debug）—— Linux / macOS

  ./build_debug.sh                 构建 Debug APK
  ./build_debug.sh --clean         先 clean 再构建
  ./build_debug.sh --fresh         先停掉所有 Gradle/Kotlin 守护进程并清掉过期的
                                   Kotlin 编译输出，再构建（构建报 "Unable to delete
                                   directory ... kotlin-classes" 时用这个）
  ./build_debug.sh --install       构建完用 adb 安装到已连接设备
  ./build_debug.sh --no-daemon     不使用 Gradle 守护进程
  ./build_debug.sh -h              显示帮助

环境变量：
  RAWS_JDK_HOME  指定 JDK 21 路径（优先于 JAVA_HOME）
  ANDROID_HOME   Android SDK 路径（未设则由 local.properties 的 sdk.dir 提供）
EOF
            exit 0
            ;;
        *)
            echo "未知参数：$1（用 -h 看用法）" >&2
            exit 2
            ;;
    esac
    shift
done

# ---------------------------------------------------------------- 定位工程

# 无论从哪个目录调用，都切到脚本所在目录（即工程根）。
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "错误：找不到 gradle/wrapper/gradle-wrapper.jar" >&2
    echo "      这个脚本必须放在 RawS-Music 工程根目录（与 gradlew.bat 同级）。" >&2
    echo "      当前目录：$SCRIPT_DIR" >&2
    exit 1
fi

# ---------------------------------------------------------------- JDK 21

# 打印某个 JDK 目录的版本号（无则空）。
jdk_version_of() {
    [ -f "$1/release" ] || return 0
    sed -n 's/^JAVA_VERSION="\(.*\)"$/\1/p' "$1/release" 2>/dev/null | head -n 1
}

# 是 JDK 21 就采纳（设 JAVA_HOME），否则静默跳过。
adopt_jdk_if_21() {
    [ -n "${1:-}" ] || return 0
    [ -x "$1/bin/java" ] || return 0
    case "$(jdk_version_of "$1")" in
        21*) JAVA_HOME="$1"; export JAVA_HOME ;;
    esac
}

# 顺序：显式覆盖 → 现有 JAVA_HOME → java_home -v 21 → 常见安装位置。
adopt_jdk_if_21 "${RAWS_JDK_HOME:-}"
adopt_jdk_if_21 "${JAVA_HOME:-}"

if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    adopt_jdk_if_21 "$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi

if [ -z "${JAVA_HOME:-}" ] && command -v brew >/dev/null 2>&1; then
    _brew_jdk_21="$(brew --prefix openjdk@21 2>/dev/null || true)"
    if [ -n "$_brew_jdk_21" ]; then
        adopt_jdk_if_21 "$_brew_jdk_21/libexec/openjdk.jdk/Contents/Home"
    fi
    if [ -z "${JAVA_HOME:-}" ]; then
        _brew_jdk="$(brew --prefix openjdk 2>/dev/null || true)"
        if [ -n "$_brew_jdk" ]; then
            adopt_jdk_if_21 "$_brew_jdk/libexec/openjdk.jdk/Contents/Home"
        fi
    fi
fi

for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME/.sdkman/candidates/java/21"* \
    /usr/lib/jvm/java-21-openjdk* \
    /usr/lib/jvm/temurin-21-jdk* \
    /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
    /Library/Java/JavaVirtualMachines/openjdk-21*.jdk/Contents/Home \
    /Library/Java/JavaVirtualMachines/temurin-21*.jdk/Contents/Home \
    /Library/Java/JavaVirtualMachines/zulu-21*.jdk/Contents/Home
do
    [ -z "${JAVA_HOME:-}" ] || break
    adopt_jdk_if_21 "$candidate"
done

if [ -z "${JAVA_HOME:-}" ]; then
    echo "⚠️  没能自动定位 JDK 21。" >&2
    echo "    Gradle 8.11.1 需要 JDK 21（JDK 25 会直接失败）。" >&2
    echo "    请执行：export RAWS_JDK_HOME=/你的/jdk-21 路径" >&2
    echo "    若 ~/.gradle/gradle.properties 里已配置 org.gradle.java.home，可忽略本提示。" >&2
fi

# ---------------------------------------------------------------- Android SDK

# local.properties 里的 sdk.dir 才是 AGP 真正读的；这里只是给其它工具兜底。
if [ -z "${ANDROID_HOME:-}" ]; then
    for candidate in \
        "$HOME/Library/Android/sdk" \
        "/Volumes/BOOTCAMP/Android/sdk" \
        "$HOME/Android/Sdk" \
        "/usr/local/share/android-sdk"
    do
        if [ -d "$candidate" ]; then ANDROID_HOME="$candidate"; break; fi
    done
fi
if [ -n "${ANDROID_HOME:-}" ]; then
    export ANDROID_HOME
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    # Android 37 预览版/部分 SDK 版本安装目录为 android-37.0，建立软链接以兼容 AGP
    if [ -d "$ANDROID_HOME/platforms/android-37.0" ] && [ ! -e "$ANDROID_HOME/platforms/android-37" ]; then
        ln -s "android-37.0" "$ANDROID_HOME/platforms/android-37" 2>/dev/null || true
    fi
fi

# ---------------------------------------------------------------- 构建

GRADLE_ARGS="$TASK --console=plain"
# 注意：这里刻意用 if 而不是 `[ ... ] && GRADLE_ARGS=...`。
# 后者在 set -e 下属于 "AND-OR 列表"，退出码语义依赖 shell 实现，不够稳妥。
if [ "$NO_DAEMON" -eq 1 ]; then
    GRADLE_ARGS="--no-daemon $GRADLE_ARGS"
fi

echo "============================================================"
echo " RawS-Music 构建（${VARIANT_LABEL}）"
echo "  工程目录 : $SCRIPT_DIR"
echo "  JAVA_HOME: ${JAVA_HOME:-<未设置，交由 Gradle 自行决定>}"
echo "  SDK      : ${ANDROID_HOME:-<未设置，将由 local.properties 提供>}"
echo "  任务     : $GRADLE_ARGS"
if [ "$DO_CLEAN" -eq 1 ]; then echo "  clean    : 是"; fi
if [ "$DO_FRESH" -eq 1 ]; then echo "  fresh    : 是（先停守护进程 + 清过期编译输出）"; fi
echo "============================================================"
echo

# 关键：优先用仓库自带 wrapper（版本锁定 8.11.1），没有就手动拉起 wrapper。
# 这样即使仓库缺 ./gradlew 也能构建，且不会误用到系统里别的 Gradle 版本。
if [ -x "./gradlew" ]; then
    run_gradle() { ./gradlew "$@"; }
else
    WRAPPER_JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
    if ! command -v "$WRAPPER_JAVA" >/dev/null 2>&1; then
        echo "错误：找不到 java（JAVA_HOME=${JAVA_HOME:-未设置}）" >&2
        exit 1
    fi
    run_gradle() {
        "$WRAPPER_JAVA" -Dorg.gradle.appname=gradlew \
            -classpath gradle/wrapper/gradle-wrapper.jar \
            org.gradle.wrapper.GradleWrapperMain "$@"
    }
fi

# --fresh：处理「构建报 Unable to delete directory ... kotlin-classes」这类问题。
# 根因是**上一次的 Kotlin 编译守护进程没退干净**，仍占着 app/build/tmp/kotlin-classes/<变体>，
# 于是这次编译清理输出目录时失败。注意 `gradlew --stop` **不会**停 Kotlin 守护进程，得单独杀。
if [ "$DO_FRESH" -eq 1 ]; then
    echo "正在停止 Gradle 守护进程…"
    run_gradle --stop --console=plain || true
    if command -v pkill >/dev/null 2>&1; then
        echo "正在停止 Kotlin 编译守护进程…"
        pkill -f KotlinCompileDaemon >/dev/null 2>&1 || true
    fi
    sleep 3
    echo "正在清理过期的 Kotlin 编译输出…"
    rm -rf "app/build/tmp/kotlin-classes/$VARIANT"
    if [ -e "app/build/tmp/kotlin-classes/$VARIANT" ]; then
        echo "⚠️  目录仍被占用，删不掉：app/build/tmp/kotlin-classes/$VARIANT" >&2
        echo "    请关闭 Android Studio 后重试，或重启系统。" >&2
        exit 1
    fi
    echo
fi

if [ "$DO_CLEAN" -eq 1 ]; then
    run_gradle clean --console=plain || {
        echo
        echo "❌ clean 失败" >&2
        exit 1
    }
fi

set +e
# shellcheck disable=SC2086
run_gradle $GRADLE_ARGS
STATUS=$?
set -e

if [ "$STATUS" -ne 0 ]; then
    echo
    echo "❌ 构建失败（Gradle 退出码 $STATUS）" >&2
    echo "   若是 'Unable to delete directory ... kotlin-classes'，那是 Kotlin 编译守护进程" >&2
    echo "   残留占用导致的，执行： ./build_debug.sh --fresh" >&2
    exit "$STATUS"
fi

# ---------------------------------------------------------------- 产物

# 不硬编码 APK 文件名（上游可能改名），取该变体目录下最新的 .apk。
APK_DIR="app/build/outputs/apk/$VARIANT"
APK=""
if [ -d "$APK_DIR" ]; then
    APK=$(ls -t "$APK_DIR"/*.apk 2>/dev/null | head -n 1 || true)
fi

echo
echo "============================================================"
echo "✅ 构建成功（${VARIANT_LABEL}）"
if [ -n "$APK" ]; then
    echo "  APK  : $SCRIPT_DIR/$APK"
    echo "  大小 : $(du -h "$APK" | cut -f1)"
    echo "  时间 : $(date '+%Y-%m-%d %H:%M:%S')"
else
    echo "  ⚠️ 未在 $APK_DIR 找到 .apk，请检查上面的构建输出。"
fi
echo "============================================================"

# ---------------------------------------------------------------- 安装

if [ "$DO_INSTALL" -eq 1 ]; then
    ADB="adb"
    if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        ADB="$ANDROID_HOME/platform-tools/adb"
    fi

    if ! command -v "$ADB" >/dev/null 2>&1; then
        echo
        echo "⚠️  跳过安装：找不到 adb（设好 ANDROID_HOME 或把 platform-tools 加进 PATH）" >&2
        exit 0
    fi
    if [ -z "$APK" ]; then
        echo
        echo "⚠️  跳过安装：没有找到 APK。" >&2
        exit 0
    fi

    echo
    echo "正在安装到已连接设备…"
    if "$ADB" install -r "$APK"; then
        echo "✅ 安装完成"
    else
        echo "❌ 安装失败（设备是否已连接并授权 USB 调试？）" >&2
        exit 1
    fi
fi

exit 0
