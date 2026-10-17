@echo off
chcp 65001 >nul 2>&1
setlocal EnableExtensions EnableDelayedExpansion

rem ============================================================================
rem  RawS-Music 一键安装（Release）—— Windows
rem
rem    install_release.bat               安装最新生成的 Release APK（若不存在则自动构建）
rem    install_release.bat -b, --build   强制重新构建 Release APK 后安装
rem    install_release.bat -q, --quick   跳过构建，直接安装现有最新 APK
rem    install_release.bat -l, --launch  安装完成后自动启动应用
rem    install_release.bat -a, --all     直接安装到所有已连接设备
rem    install_release.bat -c, --clean   执行 clean 后重新构建并安装
rem    install_release.bat -s <serial>   多设备连接时指定目标设备序列号
rem    install_release.bat -h, --help    显示帮助信息
rem ============================================================================

set "VARIANT=release"
set "VARIANT_LABEL=Release"
set "DO_BUILD=0"
set "DO_CLEAN=0"
set "DO_LAUNCH=0"
set "DO_ALL=0"
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
if /i "!ARG!"=="-a"        set "DO_ALL=1"& goto :next_arg
if /i "!ARG!"=="--all"     set "DO_ALL=1"& goto :next_arg
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
    for /f "usebackq tokens=1,* delims==" %%a in ("local.properties") do (
        set "KEY=%%a"
        set "VAL=%%b"
        if /i "!KEY!"=="sdk.dir" (
            set "SDK_DIR=!VAL!"
            set "SDK_DIR=!SDK_DIR:\:=:!"
            set "SDK_DIR=!SDK_DIR:\\=\!"
            if exist "!SDK_DIR!\platform-tools\adb.exe" set "ADB=!SDK_DIR!\platform-tools\adb.exe"
        )
    )
)

if not defined ADB (
    for %%p in (
        "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
        "E:\softwares\Android\sdk\platform-tools\adb.exe"
        "D:\Android\sdk\platform-tools\adb.exe"
        "C:\Android\sdk\platform-tools\adb.exe"
    ) do (
        if not defined ADB if exist "%%~p" set "ADB=%%~p"
    )
)

if not defined ADB (
    where adb.exe >nul 2>&1
    if not errorlevel 1 set "ADB=adb"
)

if not defined ADB (
    echo [X] 错误：找不到 adb.exe！
    echo     请确保 Android SDK 的 platform-tools 目录已加入 PATH，
    echo     或者在 local.properties 中正确配置 sdk.dir。
    exit /b 1
)

rem ------------------------------------------------------------------ 检查已连接设备
set "DEVICE_COUNT=0"
set "UNAUTH_FOUND=0"

for /f "skip=1 tokens=1,2" %%i in ('"%ADB%" devices 2^>nul') do (
    if "%%j"=="device" (
        set /a "DEVICE_COUNT+=1"
        set "DEV_!DEVICE_COUNT!=%%i"
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

set "INSTALL_ALL_FLAG=0"
set "TARGET_DEV_COUNT=0"

if defined SPECIFIED_SERIAL (
    set "TARGET_DEV_COUNT=1"
    set "TARGET_DEV_1=%SPECIFIED_SERIAL%"
    echo [i] 目标设备（手动指定）：%SPECIFIED_SERIAL%
    goto :device_selection_done
)

if "%DO_ALL%"=="1" (
    set "INSTALL_ALL_FLAG=1"
    goto :setup_all_devices
)

if %DEVICE_COUNT% equ 1 (
    set "TARGET_DEV_COUNT=1"
    set "TARGET_DEV_1=!DEV_1!"
    set "D_MODEL=未知型号"
    set "D_BRAND="
    for /f "delims=" %%m in ('"%ADB%" -s !DEV_1! shell getprop ro.product.model 2^>nul') do set "D_MODEL=%%m"
    for /f "delims=" %%b in ('"%ADB%" -s !DEV_1! shell getprop ro.product.brand 2^>nul') do set "D_BRAND=%%b"
    echo [i] 目标设备：!D_BRAND! !D_MODEL! (!DEV_1!)
    goto :device_selection_done
)

rem 多台设备交互选择
echo ============================================================
echo [i] 检测到多台已连接设备（共 %DEVICE_COUNT% 台）：
echo ============================================================
for /l %%k in (1,1,%DEVICE_COUNT%) do (
    set "cur_dev=!DEV_%%k!"
    set "cur_model=未知型号"
    set "cur_brand="
    set "cur_ver=未知"
    for /f "delims=" %%m in ('"%ADB%" -s !cur_dev! shell getprop ro.product.model 2^>nul') do set "cur_model=%%m"
    for /f "delims=" %%b in ('"%ADB%" -s !cur_dev! shell getprop ro.product.brand 2^>nul') do set "cur_brand=%%b"
    for /f "delims=" %%v in ('"%ADB%" -s !cur_dev! shell getprop ro.build.version.release 2^>nul') do set "cur_ver=%%v"
    echo   [%%k] !cur_brand! !cur_model! (Android !cur_ver!) - !cur_dev!
)
echo   [A] 全部设备 (安装到以上所有设备)
echo   [Q] 取消并退出
echo ============================================================

:prompt_choice
set "USER_CHOICE="
set /p "USER_CHOICE=👉 请选择目标设备 [1-%DEVICE_COUNT% / A(全部) / Q(退出)] (默认 A): "
if not defined USER_CHOICE set "USER_CHOICE=A"

if /i "%USER_CHOICE%"=="Q" (
    echo 已取消安装。
    exit /b 0
)

if /i "%USER_CHOICE%"=="A" (
    set "INSTALL_ALL_FLAG=1"
    goto :setup_all_devices
)

rem 验证是否为合法数字
set "CHOSEN_DEV="
for /l %%k in (1,1,%DEVICE_COUNT%) do (
    if "%USER_CHOICE%"=="%%k" set "CHOSEN_DEV=!DEV_%%k!"
)

if defined CHOSEN_DEV (
    set "TARGET_DEV_COUNT=1"
    set "TARGET_DEV_1=!CHOSEN_DEV!"
    echo [i] 已选择设备：!CHOSEN_DEV!
    goto :device_selection_done
) else (
    echo [!] 输入无效，请输入 1 到 %DEVICE_COUNT% 之间的数字，或 A / Q！
    goto :prompt_choice
)

:setup_all_devices
set "TARGET_DEV_COUNT=%DEVICE_COUNT%"
for /l %%k in (1,1,%DEVICE_COUNT%) do (
    set "TARGET_DEV_%%k=!DEV_%%k!"
)
echo [i] 目标设备：全部已连接设备（共 %TARGET_DEV_COUNT% 台）

:device_selection_done

rem ------------------------------------------------------------------ 检查或构建 APK
set "APK_DIR=app\build\outputs\apk\%VARIANT%"
set "APK="
for /f "delims=" %%f in ('dir /b /o-d /s "%APK_DIR%\*.apk" 2^>nul') do if not defined APK set "APK=%%f"

if "%DO_BUILD%"=="1" goto :do_build
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

if exist "build_release.bat" (
    call "build_release.bat" %BUILD_OPTS%
    if errorlevel 1 (
        echo [X] 构建失败！
        exit /b 1
    )
) else (
    echo [X] 找不到 build_release.bat 构建脚本！
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
set "SUCCESS_COUNT=0"
set "FAIL_COUNT=0"

for /l %%k in (1,1,%TARGET_DEV_COUNT%) do (
    set "target=!TARGET_DEV_%%k!"
    set "t_model=未知型号"
    set "t_brand="
    for /f "delims=" %%m in ('"%ADB%" -s !target! shell getprop ro.product.model 2^>nul') do set "t_model=%%m"
    for /f "delims=" %%b in ('"%ADB%" -s !target! shell getprop ro.product.brand 2^>nul') do set "t_brand=%%b"

    if %TARGET_DEV_COUNT% gtr 1 (
        echo ------------------------------------------------------------
        echo [%%k/%TARGET_DEV_COUNT%] 🚀 正在安装 !APK! 到 !t_brand! !t_model! (!target!)...
    ) else (
        echo 🚀 正在安装 !APK! 到 !t_brand! !t_model! (!target!)...
    )

    "%ADB%" -s !target! install -r -d "!APK!"
    if errorlevel 1 (
        echo [X] 设备 !target! 安装失败！
        set /a "FAIL_COUNT+=1"
    ) else (
        echo [OK] 设备 !target! 安装成功！
        set /a "SUCCESS_COUNT+=1"

        if "%DO_LAUNCH%"=="1" (
            echo ▶️  正在启动应用 com.rawsmusic/.MainActivity ...
            "%ADB%" -s !target! shell am start -n com.rawsmusic/.MainActivity >nul 2>&1
            echo ✨ 应用已在前台启动！
        )
    )
)

echo.
echo ============================================================
if %FAIL_COUNT% equ 0 (
    echo [OK] 全部安装成功！[%VARIANT_LABEL%]（共 %SUCCESS_COUNT% 台设备）
    echo ============================================================
    exit /b 0
) else (
    echo [!] 安装完成：%SUCCESS_COUNT% 台成功，%FAIL_COUNT% 台失败！
    echo     常见排查：
    echo     1. 小米/红米/POCO 手机：需在「开发者选项」中开启「USB 安装」；
    echo     2. 签名冲突：如果手机上装有不同签名的历史版本，请先手动卸载旧版本；
    echo     3. 存储空间不足：请检查手机剩余存储容量。
    echo ============================================================
    exit /b 1
)

:usage
echo RawS-Music 一键安装（Release）—— Windows
echo.
echo 用法：
echo   install_release.bat               安装最新生成的 Release APK（若不存在则自动构建）
echo   install_release.bat -b, --build   强制重新构建 Release APK 后安装
echo   install_release.bat -q, --quick   跳过构建，直接安装现有最新 APK
echo   install_release.bat -l, --launch  安装完成后自动启动应用
echo   install_release.bat -a, --all     直接安装到所有已连接设备（跳过交互选择）
echo   install_release.bat -c, --clean   执行 clean 后重新构建并安装
echo   install_release.bat -s ^<serial^>   多设备连接时指定目标设备序列号
echo   install_release.bat -h, --help    显示帮助信息
echo.
echo 环境变量：
echo   ANDROID_HOME   Android SDK 路径（优先用于定位 adb 工具）
exit /b 0
