package com.rawsmusic.module.online.model

/**
 * 播客来源类型。
 */
enum class PodcastSourceType(val displayName: String) {
    SPOTIFY("Spotify"),
    RSS("RSS / iTunes"),
}

/**
 * 播客节目概览数据模型。
 */
data class PodcastShow(
    val id: String,
    val title: String,
    val publisher: String,
    val description: String,
    val coverUrl: String,
    val totalEpisodes: Int = 0,
    val feedUrl: String? = null,
    val sourceType: PodcastSourceType = PodcastSourceType.SPOTIFY,
    val category: String = "热门推荐",
    val isVideo: Boolean = false,
)

/**
 * 播客单集数据模型。
 */
data class PodcastEpisode(
    val id: String,
    val showId: String,
    val showTitle: String,
    val title: String,
    val description: String,
    val pubDate: String,
    val durationMs: Long = 0L,
    val coverUrl: String,
    val audioUrl: String? = null,
    val isVideo: Boolean = false,
    val videoUrl: String? = null,
)

/**
 * 播客分类标签。
 */
data class PodcastCategory(
    val id: String,
    val name: String,
    val queryKeyword: String,
)

/**
 * 播客播放历史记录项（记录具体单集及关联所属播客专辑，方便回溯与跳转专辑）。
 */
data class PodcastHistoryItem(
    val episode: PodcastEpisode,
    val show: PodcastShow,
    val playedAt: Long = System.currentTimeMillis(),
)

