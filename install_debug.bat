@echo off
chcp 65001 >nul 2>&1
setlocal EnableExtensions EnableDelayedExpansion

rem ============================================================================
rem  RawS-Music 一键安装（Debug）—— Windows
rem
rem    install_debug.bat               安装最新生成的 Debug APK（若不存在则自动构建）
rem    install_debug.bat -b, --build   强制重新构建 Debug APK 后安装
rem    install_debug.bat -q, --quick   跳过构建，直接安装现有最新 APK
rem    install_debug.bat -l, --launch  安装完成后自动启动应用
rem    install_debug.bat -c, --clean   执行 clean 后重新构建并安装
rem    install_debug.bat -s <serial>   多设备连接时指定目标设备序列号
rem    install_debug.bat -h, --help    显示帮助信息
rem
rem  ⚠️ 编码：本文件保存为 UTF-8 带 BOM。
rem ============================================================================

set "VARIANT=debug"
set "VARIANT_LABEL=Debug"
set "DO_BUILD=0"
set "DO_CLEAN=0"
set "DO_LAUNCH=0"
set "SPECIFIED_SERIAL="

rem ------------------------------------------------------------------ 参数解析
:parse_args
if "%~1"=="" goto :args_done
set "ARG=%~1"
if /i "!ARG!"=="-b"        set "DO_BUILD=1"& goto :next_arg
if /i "!ARG!"=="--build"   set "DO_BUILD=1"& goto :next_arg
if /i "!ARG!"=="-q"        set "DO_BUILD=0"& goto :next_arg
if /i "!ARG!"=="--quick"   set "DO_BUILD=0"& goto :next_arg
if /i "!ARG!"=="-c"        set "DO_CLEAN=1"& set "DO_BUILD=1"& goto :next_arg
if /i "!ARG!"=="--clean"   set "DO_CLEAN=1"& set "DO_BUILD=1"& goto :next_arg
if /i "!ARG!"=="-l"        set "DO_LAUNCH=1"& goto :next_arg
if /i "!ARG!"=="--launch"  set "DO_LAUNCH=1"& goto :next_arg
if /i "!ARG!"=="-s"        goto :parse_serial
if /i "!ARG!"=="--serial"  goto :parse_serial
if /i "!ARG!"=="-h"        goto :usage
if /i "!ARG!"=="--help"    goto :usage

echo 未知参数：!ARG!（用 -h 查看使用说明）
exit /b 2

:parse_serial
shift
if "%~1"=="" (
    echo 错误：-s/--serial 需要指定设备序列号
    exit /b 2
)
set "SPECIFIED_SERIAL=%~1"
goto :next_arg

:next_arg
shift
goto :parse_args

:args_done

rem ------------------------------------------------------------------ 定位工程
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
cd /d "%SCRIPT_DIR%"

rem ------------------------------------------------------------------ 定位 adb
set "ADB="
if defined ANDROID_HOME if exist "!ANDROID_HOME!\platform-tools\adb.exe" set "ADB=!ANDROID_HOME!\platform-tools\adb.exe"

if not defined ADB if exist "local.properties" (
    for /f "tokens=1,* delims==" %%a in (local.properties) do (
        if "%%a"=="sdk.dir" (
            set "_RAW_SDK=%%b"
            set "_RAW_SDK=!_RAW_SDK:\:=:!"
            set "_RAW_SDK=!_RAW_SDK:\\=\!"
            if exist "!_RAW_SDK!\platform-tools\adb.exe" set "ADB=!_RAW_SDK!\platform-tools\adb.exe"
        )
    )
)

if not defined ADB (
    if exist "E:\softwares\Android\sdk\platform-tools\adb.exe" set "ADB=E:\softwares\Android\sdk\platform-tools\adb.exe"
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
)

if not defined ADB (
    where adb.exe >nul 2>&1
    if not errorlevel 1 set "ADB=adb.exe"
)

if not defined ADB (
    echo [X] 错误：找不到 adb 工具！
    echo     请确保 Android SDK 的 platform-tools 已加入 PATH，
    echo     或在 local.properties 中正确配置 sdk.dir。
    exit /b 1
)

rem ------------------------------------------------------------------ 检查设备
set "DEVICE_ARGS="
if defined SPECIFIED_SERIAL set "DEVICE_ARGS=-s %SPECIFIED_SERIAL%"

rem 查询在线设备
set "DEVICE_COUNT=0"
set "FIRST_DEVICE="
set "UNAUTH_FOUND=0"

for /f "skip=1 tokens=1,2" %%i in ('"%ADB%" devices 2^>nul') do (
    if "%%j"=="device" (
        set /a "DEVICE_COUNT+=1"
        if not defined FIRST_DEVICE set "FIRST_DEVICE=%%i"
    )
    if "%%j"=="unauthorized" (
        set "UNAUTH_FOUND=1"
    )
)

if "%DEVICE_COUNT%"=="0" (
    echo [X] 未检测到可用的已连接 Android 设备！
    if "%UNAUTH_FOUND%"=="1" (
        echo     ⚠️ 检测到设备处于未授权状态（unauthorized）。
        echo     👉 请解锁手机屏幕，在弹出的「允许 USB 调试吗？」窗口中勾选并允许调试。
    ) else (
        echo     👉 请检查：
        echo        1. 手机是否已用 USB 数据线连接电脑；
        echo        2. 手机是否开启了「开发者选项」和「USB 调试」；
        echo        3. 部分机型（如小米/MIUI/HyperOS）需在开发者选项中开启「USB 安装」；
        echo        4. 运行 'adb devices' 排查设备连接状态。
    )
    exit /b 1
)

if not defined SPECIFIED_SERIAL (
    if %DEVICE_COUNT% gtr 1 (
        set "DEVICE_ARGS=-s !FIRST_DEVICE!"
        echo [i] 检测到多台已连接设备（共 %DEVICE_COUNT% 台），默认选择首台设备：!FIRST_DEVICE!
        echo     （若需安装到其它设备，请使用 -s ^<serial^> 参数指定）
    ) else (
        echo [i] 目标设备：!FIRST_DEVICE!
    )
) else (
    echo [i] 目标设备（手动指定）：%SPECIFIED_SERIAL%
)

rem ------------------------------------------------------------------ 检查或构建 APK
set "APK_DIR=app\build\outputs\apk\%VARIANT%"
set "APK="
for /f "delims=" %%f in ('dir /b /o-d /s "%APK_DIR%\*.apk" 2^>nul') do if not defined APK set "APK=%%f"

if "%DO_BUILD%"=="1" (
    goto :do_build
)
if not defined APK (
    echo [i] 目录 %APK_DIR% 下未找到 APK，自动开始构建...
    goto :do_build
)

echo [i] 使用现有最新 APK：!APK!
echo     （若修改了代码需重新编译，请添加 -b 参数重新构建）
goto :do_install

:do_build
echo 🔨 正在构建 %VARIANT_LABEL% APK...
set "BUILD_OPTS="
if "%DO_CLEAN%"=="1" set "BUILD_OPTS=--clean"

if exist "build_debug.bat" (
    call "build_debug.bat" %BUILD_OPTS%
    if errorlevel 1 (
        echo [X] 构建失败！
        exit /b 1
    )
) else (
    echo [X] 找不到 build_debug.bat 构建脚本！
    exit /b 1
)

rem 重新查找构建出的 APK
set "APK="
for /f "delims=" %%f in ('dir /b /o-d /s "%APK_DIR%\*.apk" 2^>nul') do if not defined APK set "APK=%%f"
if not defined APK (
    echo [X] 构建后仍未在 %APK_DIR% 找到 APK 文件，安装终止。
    exit /b 1
)

:do_install
echo.
echo 🚀 正在安装 !APK! 到设备...
if defined DEVICE_ARGS (
    "%ADB%" %DEVICE_ARGS% install -r -d "!APK!"
) else (
    "%ADB%" install -r -d "!APK!"
)

if errorlevel 1 (
    echo.
    echo [X] 安装失败！
    echo     常见排查：
    echo     1. 小米/红米/POCO 手机：需在「开发者选项」中开启「USB 安装」；
    echo     2. 签名冲突：如果手机上装有不同签名的历史版本，请先手动卸载手机上的旧版本；
    echo     3. 存储空间不足：请检查手机剩余存储容量。
    exit /b 1
)

echo ============================================================
echo [OK] 安装成功！[%VARIANT_LABEL%]
echo ============================================================

rem ------------------------------------------------------------------ 可选启动应用
if "%DO_LAUNCH%"=="1" (
    echo ▶️  正在启动应用 com.rawsmusic/.MainActivity ...
    if defined DEVICE_ARGS (
        "%ADB%" %DEVICE_ARGS% shell am start -n com.rawsmusic/.MainActivity >nul 2>&1
    ) else (
        "%ADB%" shell am start -n com.rawsmusic/.MainActivity >nul 2>&1
    )
    echo ✨ 应用已在前台启动！
)

exit /b 0

:usage
echo RawS-Music 一键安装（Debug）—— Windows
echo.
echo 用法：
echo   install_debug.bat               安装最新生成的 Debug APK（若不存在则自动构建）
echo   install_debug.bat -b, --build   强制重新构建 Debug APK 后安装
echo   install_debug.bat -q, --quick   跳过构建，直接安装现有最新 APK
echo   install_debug.bat -l, --launch  安装完成后自动启动应用
echo   install_debug.bat -c, --clean   执行 clean 后重新构建并安装
echo   install_debug.bat -s ^<serial^>   多设备连接时指定目标设备序列号
echo   install_debug.bat -h, --help    显示帮助信息
echo.
echo 环境变量：
echo   ANDROID_HOME   Android SDK 路径（优先用于定位 adb 工具）
exit /b 0
