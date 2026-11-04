package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.flow.referenceStaticForeground
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackStatus
import com.rawsmusic.module.online.auth.PlatformAccountManager
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.playback.OnlinePlaybackLauncher
import com.rawsmusic.module.online.playback.OnlinePlayResult
import com.rawsmusic.module.online.recommend.GuessLikeOptimizer
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 猜你喜欢 / 私人雷达 页面（支持动态种子、负反馈抑制、以此歌开启电台）
 */
@Composable
internal fun GuessYouLikePage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MiuixTheme.colorScheme
    val colors = themeColors()

    val accounts by PlatformAccountManager.accounts.collectAsState()
    val qqAccount = accounts[OnlinePlatform.QQ]
    val isLoggedIn = qqAccount != null

    var pageIndex by remember { mutableIntStateOf(1) }
    var songs by remember { mutableStateOf<List<OnlineSongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isStartingRadio by remember { mutableStateOf(false) }

    val playbackSnapshot by MusicSourcePlaybackController.snapshot.collectAsState()

    fun loadRecommendSongs(page: Int) {
        scope.launch {
            isLoading = true
            try {
                val currentSeed = playbackSnapshot.currentItem?.remoteId.orEmpty()
                val result = OnlineMusicRepository.getInstance().getRecommendSongs(
                    platform = OnlinePlatform.QQ,
                    page = page,
                    pageSize = 30,
                    seedSongMids = if (currentSeed.isNotBlank()) listOf(currentSeed) else emptyList(),
                )
                songs = result.getOrNull() ?: emptyList()
            } catch (e: Exception) {
                com.rawsmusic.core.common.utils.AppLogger.e("GuessYouLikePage", "加载推荐失败", e)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(pageIndex) {
        if (pageIndex == 1 && OnlinePlaybackLauncher.isRadioActive && OnlinePlaybackLauncher.currentRadioSongs.value.isNotEmpty()) {
            songs = OnlinePlaybackLauncher.currentRadioSongs.value
            isLoading = false
        } else {
            loadRecommendSongs(pageIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .rawStableStatusBarsPadding(),
    ) {
        // 顶部标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = null,
                    tint = colors.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.online_plaza_guess_like_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 顶部专属雷达信息卡片
            item(key = "guess_like_header_card") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(scheme.surfaceContainer.copy(alpha = 0.85f))
                        .border(0.5.dp, scheme.onSurface.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                        .padding(16.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 头像或音乐雷达图标
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(scheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                val avatarUrl = qqAccount?.avatarUrl.orEmpty()
                                if (avatarUrl.isNotBlank()) {
                                    BitmapImage(
                                        key = avatarUrl,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        targetWidth = 128,
                                        targetHeight = 128,
                                        showDefaultArtwork = false,
                                    )
                                } else {
                                    Icon(
                                        imageVector = MiuixIcons.Regular.Music,
                                        contentDescription = null,
                                        tint = scheme.primary,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }

                            Spacer(Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (isLoggedIn) "专属私人雷达" else "精选推荐电台",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = referenceStaticForeground(scheme.onBackground),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(scheme.primary.copy(alpha = 0.12f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(
                                            text = if (isLoggedIn) "QQ 音乐" else "推荐",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = scheme.primary,
                                        )
                                    }
                                }

                                Spacer(Modifier.height(4.dp))

                                Text(
                                    text = if (isLoggedIn) {
                                        "当前用户: ${qqAccount.nickname.ifBlank { qqAccount.userId }} · 基于听歌偏好"
                                    } else {
                                        stringResource(R.string.online_plaza_guess_like_subtitle_guest)
                                    },
                                    fontSize = 12.sp,
                                    color = referenceStaticForeground(scheme.onSurfaceVariantSummary, 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // 操作按钮栏：开启电台与换一批
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = {
                                    if (isStartingRadio) return@Button
                                    isStartingRadio = true
                                    scope.launch {
                                        AppNoticeBus.post(
                                            message = context.getString(R.string.online_plaza_guess_like_loading),
                                            icon = AppNoticeIcon.PLAYLIST,
                                        )
                                        val result = OnlinePlaybackLauncher.launchGuessLikeRadio(
                                            context = context,
                                            platform = OnlinePlatform.QQ,
                                            onSongsLoaded = { newSongs ->
                                                songs = newSongs
                                            },
                                        )
                                        when (result) {
                                            is OnlinePlayResult.Started -> {
                                                AppNoticeBus.post(
                                                    message = context.getString(R.string.online_plaza_guess_like_success),
                                                    icon = AppNoticeIcon.PLAYLIST,
                                                )
                                            }
                                            is OnlinePlayResult.Failed -> {
                                                AppNoticeBus.error(result.reason)
                                            }
                                            OnlinePlayResult.NoSourceInstalled -> {
                                                AppNoticeBus.error("未安装或启用可用音源")
                                            }
                                            OnlinePlayResult.NoMatchFound -> {
                                                AppNoticeBus.error("未匹配到可播放曲目")
                                            }
                                        }
                                        isStartingRadio = false
                                    }
                                },
                                enabled = !isStartingRadio,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    if (isStartingRadio) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = scheme.onPrimary,
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        androidx.compose.foundation.Image(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            colorFilter = ColorFilter.tint(scheme.onPrimary),
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.online_plaza_guess_like_btn),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = scheme.onPrimary,
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    if (!isLoading) {
                                        pageIndex++
                                    }
                                },
                                enabled = !isLoading,
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    androidx.compose.foundation.Image(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        colorFilter = ColorFilter.tint(colors.onSurface),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "换一批",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 歌曲列表区域
            if (isLoading && songs.isEmpty()) {
                item(key = "loading_indicator") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = scheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            } else if (!isLoading && songs.isEmpty()) {
                item(key = "empty_indicator") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "暂无推荐曲目，点击换一批重试",
                            color = colors.secondaryText,
                            fontSize = 13.sp,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = songs,
                    key = { index, song -> "${song.platform.id}_${song.id}_$index" },
                ) { index, song ->
                    val current = playbackSnapshot.currentItem
                    val isPlayingThis = (playbackSnapshot.status == MusicSourcePlaybackStatus.Playing) && (current?.remoteId == song.id || current?.title == song.title)
                    val isResolvingThis = (playbackSnapshot.status == MusicSourcePlaybackStatus.Resolving || playbackSnapshot.status == MusicSourcePlaybackStatus.Preparing) && (current?.remoteId == song.id || current?.title == song.title)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                scope.launch {
                                    OnlinePlaybackLauncher.play(
                                        context = context,
                                        item = song,
                                        queue = songs,
                                        index = index,
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 封面或序号
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(scheme.surfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            val cover = song.coverUrl
                            if (!cover.isNullOrBlank()) {
                                BitmapImage(
                                    key = cover,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    targetWidth = 120,
                                    targetHeight = 120,
                                    showDefaultArtwork = true,
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.secondaryText,
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = song.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = if (isPlayingThis || isResolvingThis) scheme.primary else colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (song.isVip) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .border(0.5.dp, scheme.primary, RoundedCornerShape(3.dp))
                                            .padding(horizontal = 3.dp, vertical = 0.5.dp),
                                    ) {
                                        Text(
                                            text = "VIP",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = scheme.primary,
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(3.dp))

                            Text(
                                text = "${song.artist} · ${song.album.ifBlank { "单曲" }}",
                                fontSize = 11.sp,
                                color = colors.secondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (isPlayingThis || isResolvingThis) {
                            Spacer(Modifier.width(8.dp))
                            if (isResolvingThis) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = scheme.primary,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(scheme.primary),
                                )
                            }
                        }

                        Spacer(Modifier.width(4.dp))

                        // 操作：以此歌为种子开启雷达电台
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable {
                                    scope.launch {
                                        AppNoticeBus.post(
                                            message = "正在以此歌为种子开启雷达电台...",
                                            icon = AppNoticeIcon.PLAYLIST,
                                        )
                                        OnlinePlaybackLauncher.launchGuessLikeRadio(
                                            context = context,
                                            platform = OnlinePlatform.QQ,
                                            seedSong = song,
                                            onSongsLoaded = { newSongs ->
                                                songs = newSongs
                                            },
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(R.drawable.ic_playlist_play),
                                contentDescription = "以此歌开启电台",
                                colorFilter = ColorFilter.tint(colors.secondaryText),
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        // 操作：负反馈（不感兴趣/减少此类推荐）
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable {
                                    GuessLikeOptimizer.recordNegativeFeedback(song.id, song.artist)
                                    songs = songs.filterNot { it.id == song.id }
                                    AppNoticeBus.post(
                                        message = "已为您减少此类推荐",
                                        icon = AppNoticeIcon.INFO,
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.foundation.Image(
                                imageVector = Icons.Default.Close,
                                contentDescription = "减少推荐",
                                colorFilter = ColorFilter.tint(colors.secondaryText.copy(alpha = 0.6f)),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(Modifier.height(180.dp))
            }
        }
    }
}
