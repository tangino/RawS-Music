package com.rawsmusic.module.online.transfer

import android.content.Context
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.online.model.OnlinePlaylist
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlineSongItem
import com.rawsmusic.module.online.playback.OnlineSourceMediaMapper
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import kotlinx.coroutines.CancellationException

/** 在线歌单导入的结果。文案映射交给 UI，数据层不产出用户可见字符串。 */
sealed interface OnlineImportOutcome {
    /** 成功落成本地歌单。 */
    data class Success(
        val playlistName: String,
        val songCount: Int,
    ) : OnlineImportOutcome

    /** 粘贴的文本里认不出任何平台链接。 */
    data object LinkNotRecognized : OnlineImportOutcome

    /** 链接认得出来，但歌单拉不到（已删除 / 需要登录 / 平台改接口）。 */
    data object PlaylistNotFound : OnlineImportOutcome

    /** 歌单是空的，没必要建一个空歌单。 */
    data object EmptyPlaylist : OnlineImportOutcome

    data class Failed(val reason: String) : OnlineImportOutcome
}

/**
 * 把**在线歌单**导入成本地歌单。
 *
 * ## 为什么落成本地歌单而不是只做收藏
 *
 * 收藏（`OnlinePlaylistFavoriteStore`）只是记一个入口，歌单里的曲目每次都要联网重拉；
 * 导入则是把曲目**快照**写进 `user_playlist_songs`，之后离线也能看到歌单结构。
 * 两者用途不同，都要有。
 *
 * ## 曲目以「在线行」落库
 *
 * 每首歌用 [OnlineSourceMediaMapper.toUserPlaylistSong] 转成 `P2-05b` 约定的在线行
 * （`key`/`path` = `online://<platformId>/<songId>`、`id` = 负数虚拟 id），
 * 于是歌单详情页能显示它们、点按能经 `OnlinePlaybackLauncher` 起播。
 *
 * 注意这些行**没有本地文件**，所以 `PlaylistStore.resolveSongs` 会把它们过滤掉 ——
 * 歌单详情页必须用 `playlistEntries` 才能看到在线曲目（见 `P2-05b`）。
 *
 * ## 已知取舍
 *
 * 不做「先试着匹配本地曲库」：那需要把「歌名 + 歌手」匹配逻辑搬到这里（现有实现是
 * `PlaylistTransferStore` 的私有方法）。当前一律落成在线行 —— 播放时由在线解析层去找地址，
 * 行为可预期。若将来要做本地优先匹配，在这里加一层即可，不影响落库格式。
 */
object OnlinePlaylistImportService {

    private const val TAG = "PlaylistTransfer"

    /**
     * 从用户粘贴的分享链接或文本导入。
     *
     * 识别不了平台就直接返回 [OnlineImportOutcome.LinkNotRecognized]，**不猜测**。
     */
    suspend fun importFromLink(context: Context, text: String): OnlineImportOutcome {
        val repository = OnlineMusicRepository.getInstance()
        val parsed = repository.parseLinkOrText(text)
            ?: return OnlineImportOutcome.LinkNotRecognized
        val (platform, playlistId) = parsed
        return importPlaylist(context, platform, playlistId)
    }

    /** 导入指定平台的指定歌单。 */
    suspend fun importPlaylist(
        context: Context,
        platform: OnlinePlatform,
        playlistId: String,
    ): OnlineImportOutcome {
        val repository = OnlineMusicRepository.getInstance()
        return try {
            val detail = repository.getPlaylistDetail(playlistId = playlistId, platform = platform)
                .getOrElse { error ->
                    AppLogger.e(TAG, "拉取在线歌单失败：$platform/$playlistId", error)
                    return OnlineImportOutcome.PlaylistNotFound
                }
            val (playlist, songs) = detail
            if (songs.isEmpty()) return OnlineImportOutcome.EmptyPlaylist

            val onlineRows = songs
                .filter { it.title.isNotBlank() }
                .map(OnlineSourceMediaMapper::toUserPlaylistSong)
            if (onlineRows.isEmpty()) return OnlineImportOutcome.EmptyPlaylist

            val name = playlist.title.ifBlank { platform.displayName }
            val imported = PlaylistStore.getInstance(context).importResolvedPlaylist(
                name = name,
                localSongs = emptyList(),
                onlineSongs = onlineRows,
            )
            if (imported == null) {
                OnlineImportOutcome.Failed("写入本地歌单失败")
            } else {
                AppLogger.i(TAG, "已导入在线歌单「$name」：${onlineRows.size} 首")
                OnlineImportOutcome.Success(
                    playlistName = imported.name,
                    songCount = onlineRows.size,
                )
            }
        } catch (cancelled: CancellationException) {
            // 取消不是失败。
            throw cancelled
        } catch (error: Throwable) {
            AppLogger.e(TAG, "导入在线歌单异常：$platform/$playlistId", error)
            OnlineImportOutcome.Failed(error.message.orEmpty().ifBlank { "导入失败" })
        }
    }

    /**
     * 拉取但**不落库**：给「导入前预览 / 多选」用。
     *
     * 与 [importPlaylist] 分开是为了让 UI 能先展示「这个歌单有 50 首」再让用户确认，
     * 而不是点了就直接写库。
     */
    suspend fun fetchForPreview(
        platform: OnlinePlatform,
        playlistId: String,
    ): Result<Pair<OnlinePlaylist, List<OnlineSongItem>>> =
        OnlineMusicRepository.getInstance().getPlaylistDetail(playlistId = playlistId, platform = platform)
}
