@echo off
chcp 65001 >nul 2>&1
setlocal EnableExtensions EnableDelayedExpansion

rem ============================================================================
rem  RawS-Music 一键构建（Release）—— Windows
rem
rem    build_release.bat                构建 Release APK
rem    build_release.bat --clean        先 clean 再构建
rem    build_release.bat --fresh        先停掉所有 Gradle/Kotlin 守护进程并清掉过期的
rem                                     Kotlin 编译输出，再构建（构建报 "Unable to delete
rem                                     directory ... kotlin-classes" 时用这个）
rem    build_release.bat --install      构建完用 adb 安装到已连接设备
rem    build_release.bat --no-daemon    不使用 Gradle 守护进程
rem    build_release.bat -h             显示帮助
rem
rem  本脚本是独立的：不假设当前目录，也不需要其它辅助脚本。
rem  （build_debug.bat 与它保持同一套环境探测逻辑，改一处请同步另一处。）
rem
rem  ⚠️ 关于这个工程的 Release：
rem     - 用的是 debug 签名（app/build.gradle.kts 里 signingConfig = 内置 debug），
rem       所以能直接装到手机，但不能上架、也不适合分发。
rem     - 开了 isMinifyEnabled + isShrinkResources，比 Debug 慢不少，首次可能数分钟。
rem     - 公开仓库不是发行 APK 的完整可复现构建（缺 USB 原生源码，见 docs/BUILDING.md），
rem       本项目通过打包 jniLibs/ 下的预编译 .so 绕过，不需要 NDK/CMake。
rem
rem  ⚠️ 必须 JDK 21：Gradle 8.11.1 跑不了 JDK 25（Android Studio 自带的 jbr 就是 25）。
rem  ⚠️ 本机 clone 缺少 Unix 版 gradlew（仓库只跟踪了 gradlew.bat），脚本只用 .bat。
rem
rem  ⚠️ 编码：本文件必须存为 **UTF-8 带 BOM**，且开头有 `chcp 65001`。
rem     两个条件缺一不可 —— 少了 BOM，cmd 会按当前代码页(936)去读 UTF-8 字节，
rem     多字节序列错位后会截断命令行（实测报出 'IANT' 不是内部命令 这类错）。
rem     带 BOM 时 cmd 一上来就能识别 UTF-8，从 936 或 65001 的控制台启动都正常。
rem     副作用：脚本会把调用方控制台切到 65001（UTF-8），一般无害。
rem ============================================================================

set "TASK=:app:assembleRelease"
set "VARIANT=release"
set "VARIANT_LABEL=Release"
set "DO_CLEAN=0"
set "DO_INSTALL=0"
set "DO_FRESH=0"
set "NO_DAEMON=0"

rem ------------------------------------------------------------------ 参数
:parse_args
if "%~1"=="" goto :args_done
set "ARG=%~1"
if /i "!ARG!"=="--clean"     set "DO_CLEAN=1"
if /i "!ARG!"=="--clean"     goto :next_arg
if /i "!ARG!"=="--fresh"     set "DO_FRESH=1"
if /i "!ARG!"=="--fresh"     goto :next_arg
if /i "!ARG!"=="--install"   set "DO_INSTALL=1"
if /i "!ARG!"=="--install"   goto :next_arg
if /i "!ARG!"=="--no-daemon" set "NO_DAEMON=1"
if /i "!ARG!"=="--no-daemon" goto :next_arg
if /i "!ARG!"=="-h"          goto :usage
if /i "!ARG!"=="--help"      goto :usage
echo 未知参数：!ARG!（用 -h 看用法）
exit /b 2
:next_arg
shift
goto :parse_args

:usage
echo RawS-Music 一键构建（Release）
echo.
echo   build_release.bat                构建 Release APK
echo   build_release.bat --clean        先 clean 再构建
echo   build_release.bat --fresh        先停守护进程 + 清过期 Kotlin 编译输出，再构建
echo                                     （构建报 "Unable to delete directory
echo                                      ... kotlin-classes" 时用这个）
echo   build_release.bat --install      构建完用 adb 安装到已连接设备
echo   build_release.bat --no-daemon    不使用 Gradle 守护进程
echo   build_release.bat -h             显示帮助
echo.
echo 环境变量：
echo   RAWS_JDK_HOME  指定 JDK 21 路径（优先于 JAVA_HOME）
echo   ANDROID_HOME   Android SDK 路径（未设则由 local.properties 的 sdk.dir 提供）
echo.
echo 注意：本工程的 Release 使用 debug 签名，仅供本机安装测试，不可分发。
exit /b 0

:args_done

rem --------------------------------------------------------------- 定位工程
rem 无论从哪里双击/调用，都切到脚本所在目录（即工程根）。
cd /d "%~dp0"
set "SCRIPT_DIR=%CD%"

if not exist "gradlew.bat" (
    echo 错误：找不到 gradlew.bat
    echo       这个脚本必须放在 RawS-Music 工程根目录。
    echo       当前目录：%SCRIPT_DIR%
    exit /b 1
)

rem ------------------------------------------------------------------ JDK 21
rem 依次尝试：RAWS_JDK_HOME → JAVA_HOME → 常见安装位置。
rem 只有确实是 21 才采纳，避免把 Android Studio 自带的 JDK 25 用进来。
if defined RAWS_JDK_HOME call :try_jdk "%RAWS_JDK_HOME%" && goto :jdk_done
if defined JAVA_HOME     call :try_jdk "%JAVA_HOME%"     && goto :jdk_done

rem 本机已知路径（见 AGENTS.MD §2.1）
call :try_jdk "D:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" && goto :jdk_done
call :try_jdk "D:\Program Files\Eclipse Adoptium\jdk-21.0.4.101-hotspot" && goto :jdk_done

for /d %%d in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do (
    if not defined FOUND21 call :try_jdk "%%~fd" && set "FOUND21=1"
)
for /d %%d in ("%ProgramFiles%\Java\jdk-21*") do (
    if not defined FOUND21 call :try_jdk "%%~fd" && set "FOUND21=1"
)
for /d %%d in ("%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-21*") do (
    if not defined FOUND21 call :try_jdk "%%~fd" && set "FOUND21=1"
)

:jdk_done

rem 读出实际会用到的 Java 版本，便于失败时定位。
set "JV=未知"
if defined JAVA_HOME if exist "%JAVA_HOME%\release" (
    for /f "tokens=2 delims==" %%v in ('findstr /b "JAVA_VERSION=" "%JAVA_HOME%\release" 2^>nul') do set "JV=%%~v"
)

if not "%JV:~0,2%"=="21" (
    echo ⚠️  没能定位 JDK 21（当前 JAVA_HOME=%JAVA_HOME%，版本=%JV%）。
    echo     Gradle 8.11.1 需要 JDK 21，JDK 25 会直接失败。
    echo     解决：set RAWS_JDK_HOME=D:\你的\jdk-21 路径
    echo     若用户级 %%USERPROFILE%%\.gradle\gradle.properties 已配置 org.gradle.java.home，可忽略本提示。
    echo.
)

rem ------------------------------------------------------------- Android SDK
rem AGP 真正读的是 local.properties 里的 sdk.dir；这里只是给 adb 等工具兜底。
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME if exist "E:\softwares\Android\sdk" set "ANDROID_HOME=E:\softwares\Android\sdk"
if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"

rem -------------------------------------------------------------------- 构建
set "GRADLE_ARGS=%TASK% --console=plain"
if "%NO_DAEMON%"=="1" set "GRADLE_ARGS=--no-daemon %GRADLE_ARGS%"

echo ============================================================
echo  RawS-Music 构建（%VARIANT_LABEL%）
echo   工程目录 : %SCRIPT_DIR%
echo   JAVA_HOME: %JAVA_HOME%
echo   Java 版本: %JV%
echo   SDK      : %ANDROID_HOME%
echo   任务     : %GRADLE_ARGS%
if "%DO_CLEAN%"=="1" echo   clean    : 是
if "%DO_FRESH%"=="1" echo   fresh    : 是（先停守护进程 + 清过期编译输出）
echo   提示     : Release 用 debug 签名 + 开启混淆压缩，首次较慢
echo ============================================================
echo.

rem --fresh：处理「构建报 Unable to delete directory ... kotlin-classes」这类问题。
rem 根因是**上一次的 Kotlin 编译守护进程没退干净**，仍占着 app\build\tmp\kotlin-classes\<变体>，
rem 于是这次编译清理输出目录时失败。`gradlew --stop` **不会**停 Kotlin 守护进程，得单独杀。
if "%DO_FRESH%"=="1" (
    echo 正在停止 Gradle 守护进程…
    call gradlew.bat --stop --console=plain
    echo 正在停止 Kotlin 编译守护进程…
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter 'Name=''java.exe''' | Where-Object { $_.CommandLine -like '*KotlinCompileDaemon*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
    rem 用 ping 而不是 timeout 来等待：timeout 在输出被重定向时会直接报错。
    ping -n 4 127.0.0.1 >nul 2>&1
    echo 正在清理过期的 Kotlin 编译输出…
    if exist "app\build\tmp\kotlin-classes\%VARIANT%" rd /s /q "app\build\tmp\kotlin-classes\%VARIANT%" 2>nul
    if exist "app\build\tmp\kotlin-classes\%VARIANT%" (
        echo.
        echo [X] 目录仍被占用，删不掉：app\build\tmp\kotlin-classes\%VARIANT%
        echo     请关闭 Android Studio 后重试，或重启系统。
        exit /b 1
    )
    echo.
)

if "%DO_CLEAN%"=="1" (
    call gradlew.bat clean --console=plain
    if errorlevel 1 (
        echo.
        echo [X] clean 失败
        exit /b 1
    )
)

call gradlew.bat %GRADLE_ARGS%
set "STATUS=%ERRORLEVEL%"

if not "%STATUS%"=="0" (
    echo.
    echo [X] 构建失败（Gradle 退出码 %STATUS%）
    echo     若是 "Unable to delete directory ... kotlin-classes"，那是 Kotlin 编译守护进程
    echo     残留占用导致的，执行： build_release.bat --fresh
    exit /b %STATUS%
)

rem -------------------------------------------------------------------- 产物
rem 不硬编码 APK 文件名（上游可能改名），取该变体目录下最新的 .apk。
set "APK="
for /f "delims=" %%f in ('dir /b /o-d /s "app\build\outputs\apk\%VARIANT%\*.apk" 2^>nul') do if not defined APK set "APK=%%f"

echo.
echo ============================================================
echo [OK] 构建成功（%VARIANT_LABEL%）
if defined APK (
    for %%s in ("!APK!") do set "APK_BYTES=%%~zs"
    set /a "APK_MB=!APK_BYTES!/1048576"
    echo   APK  : !APK!
    echo   大小 : !APK_MB! MB
    echo   注意 : 该 APK 使用 debug 签名，仅供本机安装测试
) else (
    echo   ⚠️ 未在 app\build\outputs\apk\%VARIANT% 找到 .apk，请检查上面的构建输出。
)
echo ============================================================

rem -------------------------------------------------------------------- 安装
if "%DO_INSTALL%"=="1" (
    set "ADB=adb"
    if defined ANDROID_HOME if exist "!ANDROID_HOME!\platform-tools\adb.exe" set "ADB=!ANDROID_HOME!\platform-tools\adb.exe"

    if not defined APK (
        echo.
        echo ⚠️  跳过安装：没有找到 APK。
        exit /b 0
    )

    echo.
    echo 正在安装到已连接设备…
    "!ADB!" install -r "!APK!"
    if errorlevel 1 (
        echo [X] 安装失败（设备是否已连接并授权 USB 调试？）
        exit /b 1
    )
    echo [OK] 安装完成
)

exit /b 0

rem ============================================================================
rem  子过程：%1 = 候选 JDK 目录。是 JDK 21 就设 JAVA_HOME 并返回 0，否则返回 1。
rem  判断依据是 JDK 自带的 release 文件里的 JAVA_VERSION，比解析 java -version 可靠。
rem ============================================================================
:try_jdk
if "%~1"=="" exit /b 1
if not exist "%~1\bin\java.exe" exit /b 1
if not exist "%~1\release" exit /b 1
set "JVER="
for /f "tokens=2 delims==" %%v in ('findstr /b "JAVA_VERSION=" "%~1\release" 2^>nul') do set "JVER=%%~v"
if not defined JVER exit /b 1
echo !JVER! | findstr /b /r "21\." >nul 2>&1 || exit /b 1
set "JAVA_HOME=%~1"
exit /b 0
