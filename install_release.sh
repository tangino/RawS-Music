#!/bin/sh
#
# RawS-Music 一键安装（Release）—— Linux / macOS
#
# 用法：
#   ./install_release.sh             安装最新生成的 Release APK（若不存在则自动构建）
#   ./install_release.sh --build     强制重新构建 Release APK 并安装（缩写 -b）
#   ./install_release.sh --quick     跳过构建，仅安装现有最新 APK（缩写 -q）
#   ./install_release.sh --launch    安装完成后自动拉起应用（缩写 -l）
#   ./install_release.sh -s <serial> 多设备时指定目标设备序列号
#   ./install_release.sh --clean     先 clean 再构建并安装（缩写 -c）
#   ./install_release.sh -h          显示帮助
#
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

VARIANT="release"
VARIANT_LABEL="Release"
DO_BUILD=0
DO_CLEAN=0
DO_LAUNCH=0
SPECIFIED_SERIAL=""

while [ $# -gt 0 ]; do
    case "$1" in
        -b|--build)    DO_BUILD=1 ;;
        -q|--quick)    DO_BUILD=0 ;;
        -c|--clean)    DO_CLEAN=1; DO_BUILD=1 ;;
        -l|--launch)   DO_LAUNCH=1 ;;
        -s|--serial)
            shift
            if [ $# -eq 0 ]; then
                echo "错误：-s/--serial 需要指定设备序列号" >&2
                exit 2
            fi
            SPECIFIED_SERIAL="$1"
            ;;
        -h|--help)
            cat <<'EOF'
RawS-Music 一键安装（Release）—— Linux / macOS

用法：
  ./install_release.sh             安装最新生成的 Release APK（若不存在则自动构建）
  ./install_release.sh -b, --build 强制重新构建 Release APK 后安装
  ./install_release.sh -q, --quick 跳过构建，直接安装现有最新 APK
  ./install_release.sh -l, --launch 安装完成后自动启动应用
  ./install_release.sh -c, --clean 执行 clean 后重新构建并安装
  ./install_release.sh -s <serial> 多设备连接时指定目标设备序列号
  ./install_release.sh -h, --help  显示帮助信息

环境变量：
  ANDROID_HOME   Android SDK 路径（优先用于定位 adb 工具）
EOF
            exit 0
            ;;
        *)
            echo "未知参数：$1（用 -h 查看使用说明）" >&2
            exit 2
            ;;
    esac
    shift
done

# ---------------------------------------------------------------- 定位 adb
ADB=""
if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
fi

if [ -z "$ADB" ] && [ -f "local.properties" ]; then
    _sdk_dir=$(sed -n 's/^sdk\.dir=\(.*\)$/\1/p' local.properties | tr -d '\r' | sed 's/\\:/:/g; s/\\\\/\//g')
    if [ -n "$_sdk_dir" ] && [ -x "$_sdk_dir/platform-tools/adb" ]; then
        ADB="$_sdk_dir/platform-tools/adb"
    fi
fi

if [ -z "$ADB" ]; then
    for candidate in \
        "$HOME/Library/Android/sdk/platform-tools/adb" \
        "/Volumes/BOOTCAMP/Android/sdk/platform-tools/adb" \
        "$HOME/Android/Sdk/platform-tools/adb" \
        "/usr/local/share/android-sdk/platform-tools/adb"
    do
        if [ -x "$candidate" ]; then
            ADB="$candidate"
            break
        fi
    done
fi

if [ -z "$ADB" ] && command -v adb >/dev/null 2>&1; then
    ADB="adb"
fi

if [ -z "$ADB" ]; then
    echo "❌ 错误：找不到 adb 工具！" >&2
    echo "   请确保 Android SDK 的 platform-tools 目录已加入 PATH，" >&2
    echo "   或者在 local.properties 中正确配置 sdk.dir。" >&2
    exit 1
fi

# ---------------------------------------------------------------- 检查已连接设备
DEVICE_ARGS=""
if [ -n "$SPECIFIED_SERIAL" ]; then
    DEVICE_ARGS="-s $SPECIFIED_SERIAL"
fi

# 获取当前在线设备列表
_devices_output=$("$ADB" devices 2>/dev/null | sed '1d' | grep -v '^[[:space:]]*$' || true)
_online_devices=$(echo "$_devices_output" | grep -w 'device' | awk '{print $1}' || true)
_unauthorized=$(echo "$_devices_output" | grep -w 'unauthorized' | awk '{print $1}' || true)

if [ -z "$_online_devices" ]; then
    echo "❌ 未检测到可用的已连接 Android 设备！" >&2
    if [ -n "$_unauthorized" ]; then
        echo "   ⚠️  检测到设备处于未授权状态（unauthorized）。" >&2
        echo "   👉 请解锁手机屏幕，在弹出的「允许 USB 调试吗？」窗口中勾选并允许调试。" >&2
    else
        echo "   👉 请检查：" >&2
        echo "      1. 手机是否已用数据线连接电脑；" >&2
        echo "      2. 手机是否开启了「开发者选项」和「USB 调试」；" >&2
        echo "      3. 部分机型（如小米/MIUI/HyperOS）需在开发者选项中开启「USB 安装」；" >&2
        echo "      4. 运行 'adb devices' 排查设备连接状态。" >&2
    fi
    exit 1
fi

_device_count=$(echo "$_online_devices" | wc -l | tr -d ' ')
if [ -z "$SPECIFIED_SERIAL" ]; then
    if [ "$_device_count" -gt 1 ]; then
        _first_device=$(echo "$_online_devices" | head -n 1)
        DEVICE_ARGS="-s $_first_device"
        echo "ℹ️  检测到多台已连接设备（共 ${_device_count} 台），默认选择首台设备：$_first_device"
        echo "   （若需安装到其它设备，请使用 -s <serial> 参数指定）"
    else
        _first_device=$(echo "$_online_devices" | head -n 1)
        echo "📱 目标设备：$_first_device"
    fi
else
    echo "📱 目标设备（手动指定）：$SPECIFIED_SERIAL"
fi

# ---------------------------------------------------------------- 检查或构建 APK
APK_DIR="app/build/outputs/apk/$VARIANT"
APK=""
if [ -d "$APK_DIR" ]; then
    APK=$(ls -t "$APK_DIR"/*.apk 2>/dev/null | head -n 1 || true)
fi

if [ "$DO_BUILD" -eq 1 ] || [ -z "$APK" ]; then
    if [ -z "$APK" ]; then
        echo "📦 目录 $APK_DIR 下未找到现有 APK，自动开始构建..."
    else
        echo "🔨 收到构建指令，正在重新构建 $VARIANT_LABEL APK..."
    fi

    BUILD_SCRIPT="./build_release.sh"
    BUILD_OPTS=""
    if [ "$DO_CLEAN" -eq 1 ]; then
        BUILD_OPTS="--clean"
    fi

    if [ -x "$BUILD_SCRIPT" ]; then
        "$BUILD_SCRIPT" $BUILD_OPTS
    elif [ -f "$BUILD_SCRIPT" ]; then
        sh "$BUILD_SCRIPT" $BUILD_OPTS
    else
        echo "❌ 找不到 $BUILD_SCRIPT 构建脚本！" >&2
        exit 1
    fi

    # 重新查找构建出的 APK
    if [ -d "$APK_DIR" ]; then
        APK=$(ls -t "$APK_DIR"/*.apk 2>/dev/null | head -n 1 || true)
    fi
    if [ -z "$APK" ]; then
        echo "❌ 构建后仍未在 $APK_DIR 找到 APK 文件，安装终止。" >&2
        exit 1
    fi
else
    echo "📦 使用现有最新 APK：$APK"
    echo "   （若修改了代码需重新编译，请添加 -b 参数重新构建）"
fi

# ---------------------------------------------------------------- 执行安装
echo
echo "🚀 正在安装 $APK 到设备..."
INSTALL_CMD="\"$ADB\""
[ -n "$DEVICE_ARGS" ] && INSTALL_CMD="$INSTALL_CMD $DEVICE_ARGS"
INSTALL_CMD="$INSTALL_CMD install -r -d \"$APK\""

if eval "$INSTALL_CMD"; then
    echo "============================================================"
    echo "✅ 安装成功！[${VARIANT_LABEL}]"
    echo "============================================================"
else
    echo
    echo "❌ 安装失败！" >&2
    echo "   常见排查：" >&2
    echo "   1. 小米/红米/POCO 手机：需在「开发者选项」中开启「USB 安装」；" >&2
    echo "   2. 签名冲突：如果手机上装有不同签名的历史版本，请先手动卸载手机上的旧版本；" >&2
    echo "   3. 存储空间不足：请检查手机剩余存储容量。" >&2
    exit 1
fi

# ---------------------------------------------------------------- 可选启动应用
if [ "$DO_LAUNCH" -eq 1 ]; then
    echo "▶️  正在启动应用 com.rawsmusic/.MainActivity ..."
    LAUNCH_CMD="\"$ADB\""
    [ -n "$DEVICE_ARGS" ] && LAUNCH_CMD="$LAUNCH_CMD $DEVICE_ARGS"
    LAUNCH_CMD="$LAUNCH_CMD shell am start -n com.rawsmusic/.MainActivity"
    eval "$LAUNCH_CMD" >/dev/null 2>&1 || true
    echo "✨ 应用已在前台启动！"
fi

exit 0
