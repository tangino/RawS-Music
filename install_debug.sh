#!/bin/sh
#
# RawS-Music 一键安装（Debug）—— Linux / macOS
#
# 用法：
#   ./install_debug.sh               安装最新生成的 Debug APK（若不存在则自动构建）
#   ./install_debug.sh --build       强制重新构建 Debug APK 并安装（缩写 -b）
#   ./install_debug.sh --quick       跳过构建，仅安装现有最新 APK（缩写 -q）
#   ./install_debug.sh --launch      安装完成后自动拉起应用（缩写 -l）
#   ./install_debug.sh --all         直接安装到所有已连接设备（缩写 -a）
#   ./install_debug.sh -s <serial>   多设备时指定目标设备序列号
#   ./install_debug.sh --clean       先 clean 再构建并安装（缩写 -c）
#   ./install_debug.sh -h            显示帮助
#
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

VARIANT="debug"
VARIANT_LABEL="Debug"
DO_BUILD=0
DO_CLEAN=0
DO_LAUNCH=0
DO_ALL=0
SPECIFIED_SERIAL=""

while [ $# -gt 0 ]; do
    case "$1" in
        -b|--build)    DO_BUILD=1 ;;
        -q|--quick)    DO_BUILD=0 ;;
        -c|--clean)    DO_CLEAN=1; DO_BUILD=1 ;;
        -l|--launch)   DO_LAUNCH=1 ;;
        -a|--all)      DO_ALL=1 ;;
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
RawS-Music 一键安装（Debug）—— Linux / macOS

用法：
  ./install_debug.sh               安装最新生成的 Debug APK（若不存在则自动构建）
  ./install_debug.sh -b, --build   强制重新构建 Debug APK 后安装
  ./install_debug.sh -q, --quick   跳过构建，直接安装现有最新 APK
  ./install_debug.sh -l, --launch  安装完成后自动启动应用
  ./install_debug.sh -a, --all     直接安装到所有已连接设备（跳过交互选择）
  ./install_debug.sh -s <serial>   多设备连接时指定目标设备序列号
  ./install_debug.sh -c, --clean   执行 clean 后重新构建并安装
  ./install_debug.sh -h, --help    显示帮助信息

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

# 决定目标设备列表 TARGET_DEVICES（换行分隔的序列号）
TARGET_DEVICES=""

if [ -n "$SPECIFIED_SERIAL" ]; then
    TARGET_DEVICES="$SPECIFIED_SERIAL"
    echo "📱 目标设备（手动指定）：$SPECIFIED_SERIAL"
elif [ "$DO_ALL" -eq 1 ]; then
    TARGET_DEVICES="$_online_devices"
    echo "📱 目标设备：全部已连接设备（共 ${_device_count} 台）"
elif [ "$_device_count" -eq 1 ]; then
    TARGET_DEVICES="$_online_devices"
    _m=$("$ADB" -s "$TARGET_DEVICES" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)
    _b=$("$ADB" -s "$TARGET_DEVICES" shell getprop ro.product.brand 2>/dev/null | tr -d '\r' || true)
    echo "📱 目标设备：$_b $_m ($TARGET_DEVICES)"
else
    # 多台设备，且未通过命令行指定
    if [ -t 0 ]; then
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "📱 检测到多台已连接设备（共 ${_device_count} 台）："
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        _idx=1
        for dev in $_online_devices; do
            _m=$("$ADB" -s "$dev" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "未知型号")
            _b=$("$ADB" -s "$dev" shell getprop ro.product.brand 2>/dev/null | tr -d '\r' || true)
            _v=$("$ADB" -s "$dev" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo "未知")
            printf "  [%d] %-18s (Android %-3s) - 序列号: %s\n" "$_idx" "$_b $_m" "$_v" "$dev"
            _idx=$((_idx + 1))
        done
        echo "  [A] 全部设备 (安装到以上所有设备)"
        echo "  [Q] 取消退出"
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

        while true; do
            printf "👉 请选择目标设备序号 [1-%d / A(全部) / Q(退出)] (默认 A): " "$_device_count"
            read _choice || _choice=""
            case "$_choice" in
                [aA]|"")
                    TARGET_DEVICES="$_online_devices"
                    echo "👉 已选择：全部设备（共 ${_device_count} 台）"
                    break
                    ;;
                [qQ])
                    echo "已取消安装。"
                    exit 0
                    ;;
                *)
                    if echo "$_choice" | grep -q '^[0-9]\+$' && [ "$_choice" -ge 1 ] && [ "$_choice" -le "$_device_count" ]; then
                        TARGET_DEVICES=$(echo "$_online_devices" | sed -n "${_choice}p")
                        echo "👉 已选择设备 [$_choice]：$TARGET_DEVICES"
                        break
                    else
                        echo "⚠️  输入无效，请输入 1 到 $_device_count 之间的数字，或 A / Q"
                    fi
                    ;;
            esac
        done
    else
        # 非交互式终端环境下默认选择首台设备
        TARGET_DEVICES=$(echo "$_online_devices" | head -n 1)
        echo "ℹ️  检测到多台已连接设备（共 ${_device_count} 台），非交互环境下默认选择首台设备：$TARGET_DEVICES"
        echo "   （若需安装到全部设备，请使用 -a 参数；或用 -s <serial> 指定特定设备）"
    fi
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

    BUILD_SCRIPT="./build_debug.sh"
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

# ---------------------------------------------------------------- 执行安装（支持单设备与多设备）
_total_targets=$(echo "$TARGET_DEVICES" | wc -l | tr -d ' ')
_cur_idx=1
_success_count=0
_fail_count=0
_failed_devices=""

echo
for dev in $TARGET_DEVICES; do
    _m=$("$ADB" -s "$dev" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "$dev")
    _b=$("$ADB" -s "$dev" shell getprop ro.product.brand 2>/dev/null | tr -d '\r' || true)

    if [ "$_total_targets" -gt 1 ]; then
        echo "────────────────────────────────────────────────────────────"
        echo "[$_cur_idx/$_total_targets] 🚀 正在安装 $APK 到 $_b $_m ($dev)..."
    else
        echo "🚀 正在安装 $APK 到 $_b $_m ($dev)..."
    fi

    if "$ADB" -s "$dev" install -r -d "$APK"; then
        echo "✅ 设备 $dev 安装成功！"
        _success_count=$((_success_count + 1))

        if [ "$DO_LAUNCH" -eq 1 ]; then
            echo "▶️  正在启动应用 com.rawsmusic/.MainActivity ..."
            "$ADB" -s "$dev" shell am start -n com.rawsmusic/.MainActivity >/dev/null 2>&1 || true
            echo "✨ 应用已在前台启动！"
        fi
    else
        echo "❌ 设备 $dev 安装失败！" >&2
        _fail_count=$((_fail_count + 1))
        _failed_devices="$_failed_devices $dev"
    fi
    _cur_idx=$((_cur_idx + 1))
done

echo
echo "============================================================"
if [ "$_fail_count" -eq 0 ]; then
    echo "✅ 全部安装成功！[${VARIANT_LABEL}]（共 $_success_count 台设备）"
    echo "============================================================"
    exit 0
else
    echo "⚠️  安装完成：$_success_count 台成功，$_fail_count 台失败！"
    echo "   失败设备列表：$_failed_devices"
    echo "   常见排查："
    echo "   1. 小米/红米/POCO 手机：需在「开发者选项」中开启「USB 安装」；"
    echo "   2. 签名冲突：如果手机上装有不同签名的历史版本，请先手动卸载旧版本；"
    echo "   3. 存储空间不足：请检查手机剩余存储容量。"
    echo "============================================================"
    exit 1
fi
