package com.rawsmusic.helper

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.online.sync.OnlineArtworkResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 封面解析 + 当前封面同步协调器。
 *
 * 管理 CoverUriResolver、封面提取事件监听、当前歌曲封面同步。
 * 第一阶段只拆"封面解析 + 当前封面同步"，
 * 第二阶段再迁入 shared transition bounds。
 */
class CoverCoordinator(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val getCurrentSong: () -> AudioFile?,
    private val onMiniPlayerCoverNeedRefresh: () -> Unit,
    private val onMirrorCoverChanged: (String?) -> Unit,
    private val onCurrentSongArtworkHealed: (AudioFile) -> Unit = {},
) {
    val resolver = CoverUriResolver(context.applicationContext)

    /**
     * 解析歌曲封面 URI。
     */
    fun resolve(song: AudioFile): String {
        return resolver.resolveCoverUri(song).ifBlank { song.coverKey }
    }

    /**
     * 启动封面提取事件监听及在线同源封面自愈同步。
     */
    fun start() {
        resolver.coverExtractedEvent.observe(lifecycleOwner) { event ->
            val (songPath, coverUri) = event ?: return@observe
            if (coverUri.isBlank()) return@observe

            val current = getCurrentSong()
            if (current != null && current.path == songPath) {
                resolver.updateCache(current, coverUri)
                onMiniPlayerCoverNeedRefresh()
                onMirrorCoverChanged(resolve(current).takeIf { it.isNotBlank() })
            }
        }

        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main.immediate) {
            OnlineArtworkResolver.resolvedArtworkEvents.collect { update ->
                val current = getCurrentSong() ?: return@collect
                val newCover = update.metadata.coverUrl.trim()
                if (newCover.isBlank()) return@collect
                val isOnline = current.path.startsWith("online://", ignoreCase = true)
                if (!isOnline) return@collect
                val platformPrefix = if (update.metadata.platformId.isNotBlank()) {
                    "online://${update.metadata.platformId.lowercase()}/"
                } else {
                    "online://"
                }
                val matchesCurrent = current.path.lowercase().startsWith(platformPrefix) &&
                    current.title.equals(update.queryTitle, ignoreCase = true) &&
                    (update.queryArtist.isBlank() || current.artist.equals(update.queryArtist, ignoreCase = true))
                if (matchesCurrent && current.albumArtPath != newCover) {
                    val healed = current.copy(albumArtPath = newCover)
                    resolver.invalidate(current)
                    resolver.updateCache(healed, newCover)
                    onCurrentSongArtworkHealed(healed)
                    onMiniPlayerCoverNeedRefresh()
                    onMirrorCoverChanged(newCover)
                }
            }
        }
    }

    /**
     * 切歌时调用：更新镜像封面。
     */
    fun onCurrentSongChanged(song: AudioFile) {
        val cover = resolve(song)
        onMirrorCoverChanged(cover.ifBlank { null })
    }
}
