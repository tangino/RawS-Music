package com.rawsmusic.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.scene.pages.SimplePageScaffold
import com.rawsmusic.core.ui.systemui.rawStableNavigationBarsPadding
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.online.auth.PlatformAccountManager
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.transfer.OnlineImportOutcome
import com.rawsmusic.module.online.transfer.OnlinePlaylistImportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music

private const val TAG = "PlaylistTransfer"

/**
 * 「导入我的歌单」：把平台账号里的歌单拉出来、多选、导入成本地歌单。
 *
 * ## 拉取结果的三种状态必须分开
 *
 * [PlatformAccountManager.fetchUserPlaylists] 用 `Result` 表达失败，空列表表达「确实没有歌单」。
 * 两者都是「列表为空」，但含义完全相反（一个是故障，一个不是），所以这里用
 * [PlaylistFetchState] 把「加载中 / 失败 / 已加载（可能为空）」分成三个分支，
 * 失败时给重试按钮、空列表给刷新按钮，文案也不同（`..._fetch_failed` vs `..._empty`）。
 *
 * ## 为什么自己起列表页（`SimplePageScaffold` + `LazyColumn`）
 *
 * `SettingsPage` 的 content 本身就是 `verticalScroll` 的 `Column`，在里面再放 `LazyColumn`
 * 会因「无限高约束」直接崩。歌单数量可能上百，所以这里按 §4.5 用 `LazyColumn`，
 * 外壳换成同样合规的 [SimplePageScaffold]（`SmallTopAppBar` 自带状态栏 inset）。
 * 底部固定动作条让「已选 N 个 / 导入」始终可见，不必滚到列表底部。
 *
 * ## 导入是逐个执行并汇总
 *
 * 只调 [OnlinePlaylistImportService.importPlaylist]（幂等：同名合并、按 key 去重），
 * 不自己写落库逻辑。单个歌单失败**不中断**其余歌单，失败项连歌单名一起在结果卡里列出。
 * 页面离开会取消 scope，从而中断未完成的导入 —— 已写入的歌单保持已写入状态（每个歌单是
 * 一次原子的合并写入），不会留下半个歌单。
 */
@Composable
fun PlatformPlaylistImportScreen(
    platform: OnlinePlatform,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()
    val platformName = platformDisplayName(platform)

    var fetchToken by remember(platform) { mutableIntStateOf(0) }
    var fetchState by remember(platform) { mutableStateOf<PlaylistFetchState>(PlaylistFetchState.Loading) }
    var selectedIds by remember(platform) { mutableStateOf<Set<String>>(emptySet()) }
    var importing by remember(platform) { mutableStateOf(false) }
    var importTotal by remember(platform) { mutableIntStateOf(0) }
    var importProgress by remember(platform) { mutableIntStateOf(0) }
    var summary by remember(platform) { mutableStateOf<ImportSummary?>(null) }

    LaunchedEffect(platform, fetchToken) {
        fetchState = PlaylistFetchState.Loading
        val result = try {
            PlatformAccountManager.fetchUserPlaylists(platform)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // fetchUserPlaylists 自己已经把异常包成 Result.failure；这里只是兜底，
            // 避免意外抛出时页面永远停在「加载中」。
            Result.failure(error)
        }
        fetchState = result.fold(
            onSuccess = { PlaylistFetchState.Loaded(it) },
            onFailure = { error ->
                AppLogger.e(TAG, "拉取 $platformName 歌单失败", error)
                AppNoticeBus.error(
                    context.getString(R.string.platform_playlist_import_fetch_failed, platformName),
                )
                PlaylistFetchState.Failed
            },
        )
    }

    val playlists = (fetchState as? PlaylistFetchState.Loaded)?.playlists.orEmpty()
    val selectedCount = selectedIds.size

    /** 汇总结果里的失败项要给人话，不能只显示枚举名。 */
    fun failureReason(outcome: OnlineImportOutcome): String = when (outcome) {
        is OnlineImportOutcome.Success -> ""
        OnlineImportOutcome.LinkNotRecognized ->
            context.getString(R.string.platform_playlist_import_reason_link)
        OnlineImportOutcome.PlaylistNotFound ->
            context.getString(R.string.platform_playlist_import_reason_not_found)
        OnlineImportOutcome.EmptyPlaylist ->
            context.getString(R.string.platform_playlist_import_reason_empty)
        is OnlineImportOutcome.Failed -> context.getString(
            R.string.platform_playlist_import_reason_failed,
            outcome.reason.ifBlank { context.getString(R.string.ui_unknown_error) },
        )
    }

    fun startImport() {
        val targets = playlists.filter { it.id in selectedIds }
        if (targets.isEmpty() || importing) return
        summary = null
        importing = true
        importTotal = targets.size
        importProgress = 0
        scope.launch {
            try {
                var successCount = 0
                var trackCount = 0
                val failures = mutableListOf<ImportFailure>()
                targets.forEachIndexed { index, playlist ->
                    importProgress = index + 1
                    val outcome = try {
                        OnlinePlaylistImportService.importPlaylist(context, platform, playlist.id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        AppLogger.e(TAG, "导入歌单「${playlist.title}」异常", error)
                        OnlineImportOutcome.Failed(error.message.orEmpty())
                    }
                    if (outcome is OnlineImportOutcome.Success) {
                        successCount += 1
                        trackCount += outcome.songCount
                    } else {
                        failures += ImportFailure(
                            title = playlist.title.ifBlank { playlist.id },
                            reason = failureReason(outcome),
                        )
                    }
                }
                summary = ImportSummary(successCount, trackCount, failures)
                val message = context.getString(
                    R.string.platform_playlist_import_result_summary,
                    successCount,
                    failures.size,
                )
                if (failures.isEmpty()) {
                    AppNoticeBus.post(message = message, icon = AppNoticeIcon.PLAYLIST)
                } else {
                    AppNoticeBus.error(message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogger.e(TAG, "导入歌单流程中断", error)
                AppNoticeBus.error(context.getString(R.string.platform_playlist_import_interrupted))
            } finally {
                importing = false
            }
        }
    }

    SimplePageScaffold(
        title = stringResource(R.string.platform_playlist_import_title),
        onBack = onBack,
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                text = stringResource(R.string.platform_playlist_import_page_summary, platformName),
                color = colors.secondaryText,
                fontSize = 11.sp,
                fontFamily = appFontFamily(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (val state = fetchState) {
                    PlaylistFetchState.Loading -> ImportCenteredMessage(
                        message = stringResource(R.string.platform_playlist_import_loading),
                        loading = true,
                    )

                    PlaylistFetchState.Failed -> ImportCenteredMessage(
                        message = stringResource(
                            R.string.platform_playlist_import_fetch_failed,
                            platformName,
                        ),
                        actionLabel = stringResource(R.string.platform_playlist_import_retry),
                        onAction = { fetchToken += 1 },
                    )

                    is PlaylistFetchState.Loaded -> if (state.playlists.isEmpty()) {
                        // 「该账号没有歌单」是正常结果，不是错误：给刷新按钮，不给错误提示。
                        ImportCenteredMessage(
                            message = stringResource(R.string.platform_playlist_import_empty),
                            actionLabel = stringResource(R.string.platform_playlist_import_refresh),
                            onAction = { fetchToken += 1 },
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            summary?.let { result ->
                                item { ImportResultCard(result) }
                            }
                            item {
                                SelectionHeader(
                                    selectedCount = selectedCount,
                                    allSelected = state.playlists.isNotEmpty() &&
                                        selectedCount >= state.playlists.size,
                                    enabled = !importing,
                                    onSelectAll = {
                                        selectedIds = state.playlists.mapTo(mutableSetOf()) { it.id }
                                    },
                                    onClearSelection = { selectedIds = emptySet() },
                                )
                            }
                            items(state.playlists) { playlist ->
                                PlaylistSelectRow(
                                    playlist = playlist,
                                    checked = playlist.id in selectedIds,
                                    enabled = !importing,
                                    onToggle = {
                                        selectedIds = if (playlist.id in selectedIds) {
                                            selectedIds - playlist.id
                                        } else {
                                            selectedIds + playlist.id
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            ImportBottomBar(
                selectedCount = selectedCount,
                importing = importing,
                progress = importProgress,
                total = importTotal,
                onImport = { startImport() },
            )
        }
    }
}

/** 平台用户歌单的拉取状态。**空列表不是失败**，所以 `Loaded` 必须和 `Failed` 分开。 */
private sealed interface PlaylistFetchState {
    data object Loading : PlaylistFetchState
    data object Failed : PlaylistFetchState
    data class Loaded(val playlists: List<OnlinePlaylist>) : PlaylistFetchState
}

/** 汇总用的一条失败记录：歌单名 + 人话原因。 */
private data class ImportFailure(
    val title: String,
    val reason: String,
)

/** 一次导入批次的汇总。 */
private data class ImportSummary(
    val successCount: Int,
    val trackCount: Int,
    val failures: List<ImportFailure>,
)

/** 「全选 / 全不选 + 已选 N 个」。 */
@Composable
private fun SelectionHeader(
    selectedCount: Int,
    allSelected: Boolean,
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
) {
    val colors = themeColors()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.platform_playlist_import_selected_count, selectedCount),
            color = colors.secondaryText,
            fontSize = 11.sp,
            fontFamily = appFontFamily(),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.platform_playlist_import_select_all),
                onClick = onSelectAll,
                enabled = enabled && !allSelected,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.platform_playlist_import_clear_selection),
                onClick = onClearSelection,
                enabled = enabled && selectedCount > 0,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 单个歌单行：封面子 + 名称 + 曲目数 + 创建者 + 复选框，整行可点选。 */
@Composable
private fun PlaylistSelectRow(
    playlist: OnlinePlaylist,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .clickable(enabled = enabled) { onToggle() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onToggle,
            enabled = enabled,
        )
        Spacer(Modifier.width(6.dp))
        PlaylistCover(coverUrl = playlist.coverUrl)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.title.ifBlank { playlist.id },
                color = colors.onSurface,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily(),
            )
            Text(
                text = stringResource(
                    R.string.platform_playlist_import_track_count,
                    playlist.trackCount,
                ),
                color = colors.secondaryText,
                fontSize = 11.sp,
                fontFamily = appFontFamily(),
            )
            val creator = playlist.creatorName?.takeIf { it.isNotBlank() }
            Text(
                text = if (creator != null) {
                    stringResource(R.string.platform_playlist_import_creator, creator)
                } else {
                    stringResource(R.string.platform_playlist_import_creator_unknown)
                },
                color = colors.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = appFontFamily(),
            )
        }
    }
}

/**
 * 封面：占位图标先画，[BitmapImage] 覆盖在上面。
 *
 * 这里**不引 Coil** —— `BitmapProvider` 内建支持 http(s) 远程图。`showDefaultArtwork = false`
 * 时远程图加载中/失败都不画任何东西，于是露出下面的占位（而不是崩或闪白）。
 */
@Composable
private fun PlaylistCover(coverUrl: String) {
    val colors = themeColors()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.Music,
            contentDescription = null,
            tint = colors.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
        )
        if (coverUrl.isNotBlank()) {
            BitmapImage(
                key = coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                targetWidth = 144,
                targetHeight = 144,
                showDefaultArtwork = false,
            )
        }
    }
}

/** 导入结果卡：成功/失败计数 + 总曲目数 + 失败清单（含歌单名与原因）。 */
@Composable
private fun ImportResultCard(summary: ImportSummary) {
    val colors = themeColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.platform_playlist_import_result_title),
            color = colors.onSurface,
            fontSize = 15.sp,
            fontFamily = appFontFamily(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                R.string.platform_playlist_import_result_summary,
                summary.successCount,
                summary.failures.size,
            ),
            color = if (summary.failures.isEmpty()) colors.secondaryText else colors.primary,
            fontSize = 13.sp,
            fontFamily = appFontFamily(),
        )
        Text(
            text = stringResource(R.string.platform_playlist_import_result_tracks, summary.trackCount),
            color = colors.secondaryText,
            fontSize = 11.sp,
            fontFamily = appFontFamily(),
        )
        if (summary.failures.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.platform_playlist_import_failures_title),
                color = colors.onSurface,
                fontSize = 13.sp,
                fontFamily = appFontFamily(),
            )
            summary.failures.forEach { failure ->
                Text(
                    text = stringResource(
                        R.string.platform_playlist_import_failure_line,
                        failure.title,
                        failure.reason,
                    ),
                    color = colors.secondaryText,
                    fontSize = 11.sp,
                    fontFamily = appFontFamily(),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 底部固定动作条：进度 + 全宽「导入」按钮（导入中禁用并转圈）。 */
@Composable
private fun ImportBottomBar(
    selectedCount: Int,
    importing: Boolean,
    progress: Int,
    total: Int,
    onImport: () -> Unit,
) {
    val colors = themeColors()
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outline.copy(alpha = 0.15f)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rawStableNavigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (importing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(
                            R.string.platform_playlist_import_progress,
                            progress,
                            total,
                        ),
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        fontFamily = appFontFamily(),
                    )
                }
            }
            TextButton(
                text = stringResource(R.string.platform_playlist_import_action_start),
                onClick = onImport,
                enabled = !importing && selectedCount > 0,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 加载中 / 失败 / 空列表共用的居中提示（失败与空列表带一个动作按钮）。 */
@Composable
private fun ImportCenteredMessage(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    loading: Boolean = false,
) {
    val colors = themeColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(size = 22.dp, strokeWidth = 2.dp)
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = message,
            color = colors.secondaryText,
            fontSize = 13.sp,
            fontFamily = appFontFamily(),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(text = actionLabel, onClick = onAction)
        }
    }
}
