package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.playback.OnlineSourceMediaMapper
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 将网络歌曲添加到本地（混合）歌单的对话框。
 *
 * 展示当前待添加单曲摘要、支持内联快速新建歌单、并列出用户现有的全部歌单（包含默认我喜欢的歌单），
 * 对已收录的歌单展示徽标并去重防护。
 */
@Composable
internal fun OnlineAddToPlaylistDialog(
    song: OnlineSongItem?,
    onDismiss: () -> Unit,
) {
    if (song == null) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlistStore = remember { PlaylistStore.getInstance(context) }
    val playlists by playlistStore.playlists.collectAsState()

    val userPlaylistSong = remember(song) {
        OnlineSourceMediaMapper.toUserPlaylistSong(song)
    }

    var isCreatingPlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var isOperating by remember { mutableStateOf(false) }

    val colors = themeColors()

    // 过滤与排序：我喜欢的置顶，其余自建歌单按原顺序
    val sortedPlaylists = remember(playlists) {
        val (favorites, custom) = playlists.partition { it.isFavorites }
        favorites + custom
    }

    fun isAlreadyIn(playlist: UserPlaylist): Boolean {
        return playlist.songs.any { item ->
            item.key == userPlaylistSong.key ||
                item.path == userPlaylistSong.path ||
                (item.title.equals(userPlaylistSong.title, ignoreCase = true) &&
                    item.artist.equals(userPlaylistSong.artist, ignoreCase = true))
        }
    }

    fun addToTargetPlaylist(playlist: UserPlaylist) {
        if (isOperating) return
        if (isAlreadyIn(playlist)) {
            AppNoticeBus.post(
                message = context.getString(R.string.online_already_in_playlist, playlist.name),
                icon = AppNoticeIcon.PLAYLIST,
            )
            return
        }

        isOperating = true
        scope.launch {
            try {
                playlistStore.addOnlineSongToPlaylist(playlist.id, userPlaylistSong)
                AppNoticeBus.post(
                    message = context.getString(R.string.online_added_to_playlist, song.title, playlist.name),
                    icon = AppNoticeIcon.PLAYLIST,
                )
                onDismiss()
            } finally {
                isOperating = false
            }
        }
    }

    fun createAndAdd(name: String) {
        val clean = name.trim()
        if (clean.isBlank() || isOperating) return
        isOperating = true
        scope.launch {
            try {
                // 如果已有同名，直接取已有歌单；否则创建新歌单
                val existing = playlists.firstOrNull { it.name.equals(clean, ignoreCase = true) }
                val target = existing ?: playlistStore.createPlaylist(clean)
                if (target != null) {
                    playlistStore.addOnlineSongToPlaylist(target.id, userPlaylistSong)
                    AppNoticeBus.post(
                        message = context.getString(R.string.online_added_to_playlist, song.title, target.name),
                        icon = AppNoticeIcon.PLAYLIST,
                    )
                    onDismiss()
                }
            } finally {
                isOperating = false
            }
        }
    }

    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.online_add_to_playlist_title),
        onDismissRequest = { if (!isOperating) onDismiss() },
        renderInRootScaffold = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            // ── 待添加歌曲信息条 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface.copy(alpha = 0.65f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.background),
                    contentAlignment = Alignment.Center,
                ) {
                    val cover = song.coverUrl
                    if (!cover.isNullOrBlank()) {
                        BitmapImage(
                            key = cover,
                            contentDescription = song.title,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                            targetWidth = 128,
                            targetHeight = 128,
                        )
                    } else {
                        Icon(
                            imageVector = MiuixIcons.Regular.Music,
                            contentDescription = null,
                            tint = colors.secondaryText,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        color = colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                        color = colors.secondaryText,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(Modifier.width(8.dp))

                // 平台徽章
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = song.platform.displayName,
                        color = colors.primary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 新建歌单行 / 输入框 ──
            if (!isCreatingPlaylist) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { isCreatingPlaylist = true }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(colors.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.online_create_playlist),
                        color = colors.primary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                val keyboard = LocalSoftwareKeyboardController.current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        singleLine = true,
                        textStyle = TextStyle(color = colors.onSurface, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                keyboard?.hide()
                                createAndAdd(newPlaylistName)
                            },
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.surface)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (newPlaylistName.isBlank()) {
                                    Text(
                                        text = stringResource(R.string.online_create_playlist_hint),
                                        color = colors.secondaryText,
                                        fontSize = 13.sp,
                                    )
                                }
                                inner()
                            }
                        },
                    )

                    Spacer(Modifier.width(8.dp))

                    TextButton(
                        text = stringResource(R.string.online_detail_favorite),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        enabled = newPlaylistName.isNotBlank() && !isOperating,
                        onClick = {
                            keyboard?.hide()
                            createAndAdd(newPlaylistName)
                        },
                    )

                    Spacer(Modifier.width(4.dp))

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable {
                                isCreatingPlaylist = false
                                newPlaylistName = ""
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = colors.secondaryText,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── 现有歌单列表 ──
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(sortedPlaylists, key = { it.id }) { playlist ->
                    val added = isAlreadyIn(playlist)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !added && !isOperating) {
                                addToTargetPlaylist(playlist)
                            }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (playlist.isFavorites) colors.primary.copy(alpha = 0.12f)
                                    else colors.surface.copy(alpha = 0.5f)
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (playlist.isFavorites) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_playlist_favorite_filled),
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = MiuixIcons.Regular.Folder,
                                    contentDescription = null,
                                    tint = colors.secondaryText,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                color = if (added) colors.secondaryText.copy(alpha = 0.55f) else colors.onSurface,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.online_song_count_format, playlist.songs.size),
                                color = colors.secondaryText.copy(alpha = if (added) 0.45f else 0.8f),
                                fontSize = 11.5.sp,
                            )
                        }

                        if (added) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.secondaryText.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.online_already_added),
                                    color = colors.secondaryText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            if (isOperating) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.primary)
                }
            }
        }
    }
}
