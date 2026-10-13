# AGENTS.md — RawS-Music 工作区局部规则

> **本目录是移植目标工程。** 完整规范、四大功能移植设计、UI/代码约定、云端契约全部在**上一级目录**：
> - [`../AGENTS.MD`](../AGENTS.MD) —— **权威规范，动手前必读**
> - [`../TODO.md`](../TODO.md) —— 唯一进度来源
> - [`../docs/cloud-sync-contract.md`](../docs/cloud-sync-contract.md) —— 云同步接口契约（做同步前必读）
> - [`../docs/porting-reference.md`](../docs/porting-reference.md) —— 源→目标映射、文件索引、平台端点速查

本文件只重复**最容易踩的局部规则**，不替代上级规范。

## 铁律

1. **OrBitPlayer 的 Compose UI 一律不移植。** 只搬数据/算法/常量，界面 100% 用本项目的 Miuix 体系重写。`../app/` 是只读参考。
2. **不许引入**：Coil / Glide / Hilt / Koin / Dagger / Retrofit / Navigation-Compose / `LazyVerticalGrid` / 新的 SharedPreferences。
3. **DDL/偏好改造**：OrBit 的 `SQLiteOpenHelper` → Room（Entity + DAO + **手写 Migration**）；OrBit 的 SharedPreferences → `AppPreferences`（MMKV）。
4. **网络**：只用 OkHttp（`libs.okhttp`）+ Gson / `org.json`。注意 `:core:common`、`:module:data`、`:module:player` **当前没有 OkHttp 依赖**。
5. **文案**：`stringResource`，禁止硬编码。**注意默认语言不一致**——`core:ui` 默认中文（英文在 `values-en/`），`app` / `module:player` / `module:scanner` 默认**英文**（中文在 `values-zh/`）。
6. **提示与日志**：用 `AppNoticeBus` / `AppLogger`，不要 `Toast` / `Log.*`。
7. **新页面**：`NavScene`（id 顺延，**当前最大 53**，新场景从 **54** 起）→ `ComposeNavHost` 的 `when` → `AppSideRail` 入口；不要页面内自建 `Scaffold`（全应用只有 `MainActivity` 根部一个）。
8. **列曲库/歌曲列表**用 `LibraryListScaffold` + `ComposeVirtualList*`；网格用 `chunked(N)` + `VirtualListCustomProvider`。普通内容列表用 `LazyColumn`。
9. **弹窗**用 `RawMiuixOverlayDialog`。**Miuix 0.9.2 没有 `SuperXxx` 组件**，不要抄旧版示例。
10. **DAO 不返回 `Flow`**（全是 `suspend`）：写入后必须走 Repository 的刷新/发布路径，否则 UI 不更新。
11. **可视化**：只扩展 `core/ui/.../widget/player/ComposeAudioVisualizer.kt` 与相关文件；**不要**新写 FFT、**不要**用 `android.media.audiofx.Visualizer`、**不要**加第二个 `setWaveformConsumerActive(true)` 消费者。
12. **枚举兼容**：`AudioVisualizerStyle` 是 Int 持久化，新形态**只能追加 6..13**，严禁重排 0..5。
13. **Room 迁移**：DB 当前 **version = 6**，`MIGRATION_5_6` 已被上游占用 → 新表走 **`MIGRATION_6_7`**。
14. **原生代码冻结**：本项目**不编译原生源码**，改 `app/src/main/cpp/` **不会生效**（原因见下）。

## 构建（本机）

```powershell
$env:ANDROID_HOME = "E:\softwares\Android\sdk"
cd D:\development\OrBitPlayer\RawS-Music

.\gradlew.bat :core:ui:compileReleaseKotlin --console=plain   # 单模块快速校验
.\gradlew.bat :app:compileReleaseKotlin --console=plain      # 官方推荐的日常检查（约 10 分钟）
.\gradlew.bat assembleRelease --console=plain                # 完整构建
```

macOS：`export ANDROID_HOME=/Volumes/BOOTCAMP/Android/sdk` 后用 `./gradlew`。
JDK **必须 21**（本机在 `D:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`，已配在用户级 `~/.gradle/gradle.properties`；`Android_Studio\jbr` 是 **JDK 25，Gradle 8.11.1 跑不了**）。

## 环境状态：4 个阻塞已全部解决

1. `gradle.properties` 里失效的 `org.gradle.java.home` → **已删除**（JDK 21 配在用户级）。
2. `local.properties` → **已创建**（`sdk.dir=E\:\\softwares\\Android\\sdk`）。
3. catalog 缺 `focus-api` / `shizuku` → **上游已补齐**。
4. 缺 `librawsmusic_ffmpeg.so` → **已就位**，且 `jniLibs/arm64-v8a/` 有官方 1.0.13 的完整预编译 `.so` 集。
5. NDK `27.0.12077973` → **AGP 自动安装**。

## ⚠️ 本项目**不从源码编译原生库**（重要）

公开仓库缺少 **70 个 USB 独占原生源文件**（`CMakeLists.txt` 引用 144 个 `.cpp`，实际只有 74 个），CMake 必然在 `add_library(rawsmusic_usb ...)` 处失败。

→ `app/build.gradle.kts` 的**两处 `externalNativeBuild` 已被注释掉**，改为直接打包 `src/main/jniLibs/arm64-v8a/` 的预编译 `.so`（含 `librawscoreservice.so`，即 USB 独占功能**可用**）。

**推论（写代码时必须记住）**：
- **改 `app/src/main/cpp/` 下的任何东西都不会生效**，不要动它。
- 任何需要改 C++/JNI 的需求都做不了 —— 先确认需求是否真的需要原生改动。
- 恢复原生构建的前提是补齐缺失的 USB 源文件。

详见 [`../AGENTS.MD`](../AGENTS.MD) §2.2 / §9 D13，进度见 [`../TODO.md`](../TODO.md) P0。

## 不要动

`../app/`（OrBitPlayer 源码）、`../server/`（Worker 后端，默认不改）、以及其他无关目录。
在 `RawS-Music/` 内工作前请先 `git checkout feat/orbit-port`，**不要直接改 `main`**。
