#!/bin/bash

# ==============================================================================
# RawS-Music - 版本号修改/升级脚本 (macOS / Linux)
# ==============================================================================

set -e

GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_FILE="$SCRIPT_DIR/app/build.gradle.kts"

if [ ! -f "$GRADLE_FILE" ]; then
    if [ -f "app/build.gradle.kts" ]; then
        GRADLE_FILE="app/build.gradle.kts"
    elif [ -f "RawS-Music/app/build.gradle.kts" ]; then
        GRADLE_FILE="RawS-Music/app/build.gradle.kts"
    else
        echo -e "${RED}[错误] 未找到 app/build.gradle.kts 文件，请确认在 RawS-Music 目录下运行。${NC}"
        exit 1
    fi
fi

# 提取当前 versionCode 和 versionName
CURRENT_CODE=$(grep 'versionCode' "$GRADLE_FILE" | head -n 1 | tr -d ' ' | cut -d'=' -f2)
CURRENT_NAME=$(grep 'versionName' "$GRADLE_FILE" | head -n 1 | tr -d ' "' | cut -d'=' -f2)

if [ -z "$CURRENT_CODE" ] || [ -z "$CURRENT_NAME" ]; then
    echo -e "${RED}[错误] 无法从 $GRADLE_FILE 中解析当前版本号！${NC}"
    exit 1
fi

echo -e "${CYAN}======================================================${NC}"
echo -e "${CYAN}            RawS-Music 版本管理脚本                   ${NC}"
echo -e "${CYAN}======================================================${NC}"
echo -e "当前版本: ${GREEN}versionName = \"$CURRENT_NAME\"${NC} | ${GREEN}versionCode = $CURRENT_CODE${NC}"
echo ""

# 解析语义化版本号 (例如 1.0.13 或带后缀的 1.0.13-release)
RAW_MAJOR=$(echo "$CURRENT_NAME" | cut -d'.' -f1)
RAW_MINOR=$(echo "$CURRENT_NAME" | cut -d'.' -f2)
RAW_PATCH=$(echo "$CURRENT_NAME" | cut -d'.' -f3)

MAJOR=$(echo "$RAW_MAJOR" | grep -oE '^[0-9]+' || true)
MINOR=$(echo "$RAW_MINOR" | grep -oE '^[0-9]+' || true)
PATCH=$(echo "$RAW_PATCH" | grep -oE '^[0-9]+' || true)

MAJOR=${MAJOR:-0}
MINOR=${MINOR:-0}
PATCH=${PATCH:-0}

# 提取后缀（例如 -release 或 -beta）
SUFFIX=$(echo "$CURRENT_NAME" | grep -oE '[-_][a-zA-Z0-9._-]+$' || true)

SUGGEST_PATCH_NAME="${MAJOR}.${MINOR}.$((PATCH + 1))${SUFFIX}"
SUGGEST_MINOR_NAME="${MAJOR}.$((MINOR + 1)).0${SUFFIX}"
SUGGEST_MAJOR_NAME="$((MAJOR + 1)).0.0${SUFFIX}"
SUGGEST_CODE=$((CURRENT_CODE + 1))

NEW_NAME=""
NEW_CODE=""

# 命令行参数解析
# 用法:
# ./bump_version.sh patch
# ./bump_version.sh minor
# ./bump_version.sh major
# ./bump_version.sh 1.0.14-release
# ./bump_version.sh 1.0.14-release 114

if [ $# -ge 1 ]; then
    ARG1="$1"
    ARG2="$2"

    case "$ARG1" in
        patch|p|PATCH)
            NEW_NAME="$SUGGEST_PATCH_NAME"
            NEW_CODE="$SUGGEST_CODE"
            ;;
        minor|m|MINOR)
            NEW_NAME="$SUGGEST_MINOR_NAME"
            NEW_CODE="$SUGGEST_CODE"
            ;;
        major|M|MAJOR)
            NEW_NAME="$SUGGEST_MAJOR_NAME"
            NEW_CODE="$SUGGEST_CODE"
            ;;
        *)
            NEW_NAME="$ARG1"
            if [ -n "$ARG2" ]; then
                NEW_CODE="$ARG2"
            else
                NEW_CODE="$SUGGEST_CODE"
            fi
            ;;
    esac
else
    # 交互式选择模式
    echo -e "${BOLD}请选择升级类型:${NC}"
    echo -e "  ${YELLOW}1)${NC} Patch (补丁升级)   : ${BOLD}${SUGGEST_PATCH_NAME}${NC} (versionCode: $SUGGEST_CODE)"
    echo -e "  ${YELLOW}2)${NC} Minor (次版本升级) : ${BOLD}${SUGGEST_MINOR_NAME}${NC} (versionCode: $SUGGEST_CODE)"
    echo -e "  ${YELLOW}3)${NC} Major (主版本升级) : ${BOLD}${SUGGEST_MAJOR_NAME}${NC} (versionCode: $SUGGEST_CODE)"
    echo -e "  ${YELLOW}4)${NC} 自定义版本号输入"
    echo -e "  ${YELLOW}5)${NC} 退出"
    echo ""
    read -p "请输入选项 [1-5] (默认 1): " CHOICE
    CHOICE=${CHOICE:-1}

    case "$CHOICE" in
        1)
            NEW_NAME="$SUGGEST_PATCH_NAME"
            NEW_CODE="$SUGGEST_CODE"
            ;;
        2)
            NEW_NAME="$SUGGEST_MINOR_NAME"
            NEW_CODE="$SUGGEST_CODE"
            ;;
        3)
            NEW_NAME="$SUGGEST_MAJOR_NAME"
            NEW_CODE="$SUGGEST_CODE"
            ;;
        4)
            read -p "请输入新的 versionName (例如 $SUGGEST_PATCH_NAME): " INPUT_NAME
            if [ -z "$INPUT_NAME" ]; then
                echo -e "${RED}[错误] versionName 不能为空！${NC}"
                exit 1
            fi
            NEW_NAME="$INPUT_NAME"

            read -p "请输入新的 versionCode (回车默认 $SUGGEST_CODE): " INPUT_CODE
            NEW_CODE="${INPUT_CODE:-$SUGGEST_CODE}"
            ;;
        5|q|Q)
            echo "已取消操作。"
            exit 0
            ;;
        *)
            echo -e "${RED}[错误] 无效选择！${NC}"
            exit 1
            ;;
    esac
fi

# 确认写入
echo ""
echo -e "${CYAN}------------------------------------------------------${NC}"
echo -e "准备更新版本:"
echo -e "  versionName : ${RED}$CURRENT_NAME${NC} -> ${GREEN}$NEW_NAME${NC}"
echo -e "  versionCode : ${RED}$CURRENT_CODE${NC} -> ${GREEN}$NEW_CODE${NC}"
echo -e "${CYAN}------------------------------------------------------${NC}"

# 跨平台 sed 替换 (兼容 macOS BSD sed 与 Linux GNU sed)
if [[ "$OSTYPE" == "darwin"* ]]; then
    sed -i '' -E "s/(versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1$NEW_CODE/" "$GRADLE_FILE"
    sed -i '' -E "s/(versionName[[:space:]]*=[[:space:]]*\")[^\"]+(\")/\1$NEW_NAME\2/" "$GRADLE_FILE"
else
    sed -i -E "s/(versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1$NEW_CODE/" "$GRADLE_FILE"
    sed -i -E "s/(versionName[[:space:]]*=[[:space:]]*\")[^\"]+(\")/\1$NEW_NAME\2/" "$GRADLE_FILE"
fi

echo -e "${GREEN}✓ 版本号已成功更新到 $GRADLE_FILE ！${NC}"
echo ""

