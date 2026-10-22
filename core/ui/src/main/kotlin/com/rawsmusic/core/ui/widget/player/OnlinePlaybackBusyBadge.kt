package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.isOnlineSongKey
import com.rawsmusic.core.ui.R
import com.rawsmusic.module.data.source.playback.MUSIC_SOURCE_ONLINE_ENCODING_MARKER
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackSnapshot
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackStatus
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text

/**
 * 当前 UI 曲目是否走在线音源链路。
 *
 * 不能只看 `path.startsWith("online://")`：
 * - 寻源完成前 UI 拿到的是虚拟曲目，path 是 `online://<平台>/<远端 id>`；
 * - 真正起播时播放后端会把曲目替换成解析后的直链（http/https）`AudioFile`，
 *   此时 path 里已经没有 `online://`，只剩 [MUSIC_SOURCE_ONLINE_ENCODING_MARKER] 标记；
 * - 本地曲永远不带该标记。
 *
 * 所以「在线」= `online://` key **或**在线编码标记，任一命中即可。只按 path 前缀判断会让
 * 「已解析曲目重新缓冲 / 切音质」这类同样需要提示的场景静默失效。
 */
internal fun isOnlinePlaybackTrack(song: AudioFile?): Boolean {
    if (song == null) return false
    return isOnlineSongKey(song.path) ||
        song.encodingFormat == MUSIC_SOURCE_ONLINE_ENCODING_MARKER
}

/**
 * 在线曲是否正处在「寻源 / 缓冲」窗口。
 *
 * 额外带上 [isOnlinePlaybackTrack] 是为了挡住残留快照：本地曲开始播放时
 * `MusicSourcePlaybackController.resetForLocalPlayback()` 会把快照重置为 Idle，
 * 但 UI 重组可能早于那一步；此时若只看 `snapshot.isBusy`，本地曲会误显示「正在缓冲」。
 */
internal fun isOnlinePlaybackBusy(
    song: AudioFile?,
    snapshot: MusicSourcePlaybackSnapshot,
): Boolean = snapshot.isBusy && isOnlinePlaybackTrack(song)

/** [isOnlinePlaybackBusy] 的布尔口径重载，供只拿到「是否在线」的既有调用点复用。 */
internal fun isOnlinePlaybackBusy(
    isOnlineTrack: Boolean,
    snapshot: MusicSourcePlaybackSnapshot,
): Boolean = isOnlineTrack && snapshot.isBusy

/**
 * 播放页通用的「正在寻源 / 正在缓冲」提示（小号转圈 + 文案）。
 *
 * 所有文案走 `stringResource`，`core:ui` 默认中文、英文在 `values-en/`。转圈用 Miuix 的
 * [CircularProgressIndicator]（不新增 Material3 组件），颜色跟随调用方传入的播放页前景色。
 */
@Composable
internal fun OnlinePlaybackBusyBadge(
    song: AudioFile?,
    color: Color,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 11f,
    spinnerSize: Dp = 11.dp,
    strokeWidth: Dp = 1.6.dp,
) {
    OnlinePlaybackBusyIndicator(
        isOnlineTrack = isOnlinePlaybackTrack(song),
        color = color,
        modifier = modifier,
        fontSizeSp = fontSizeSp,
        spinnerSize = spinnerSize,
        strokeWidth = strokeWidth,
    )
}

/** 与 [OnlinePlaybackBusyBadge] 相同，但由调用方给出已算好的「是否在线曲」判定。 */
@Composable
internal fun OnlinePlaybackBusyIndicator(
    isOnlineTrack: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 11f,
    spinnerSize: Dp = 11.dp,
    strokeWidth: Dp = 1.6.dp,
) {
    if (!isOnlineTrack) return
    val snapshot by MusicSourcePlaybackController.snapshot.collectAsState()
    if (!isOnlinePlaybackBusy(isOnlineTrack, snapshot)) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        CircularProgressIndicator(
            size = spinnerSize,
            strokeWidth = strokeWidth,
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = color,
                backgroundColor = color.copy(alpha = 0.20f),
            ),
        )
        Text(
            text = stringResource(
                if (snapshot.status == MusicSourcePlaybackStatus.Resolving) {
                    R.string.player_online_resolving
                } else {
                    R.string.player_online_buffering
                },
            ),
            color = color,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
