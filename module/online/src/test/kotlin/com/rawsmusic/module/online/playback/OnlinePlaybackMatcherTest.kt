package com.rawsmusic.module.online.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证照搬自 OrBitPlayer 的寻源匹配算法（OnlinePlaybackMatcher）。
 */
class OnlinePlaybackMatcherTest {

    @Test
    fun testTitleMatch_normalCase() {
        // 普通歌名完全一致
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("七里香", "七里香"))
        // 标点与繁简空格归一化
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("七里香 (Live)", "七里香(Live)"))
    }

    @Test
    fun testTitleMatch_specialTags_preventCrossVersion() {
        // 原曲不得匹配到 Live 版、伴奏版、翻唱版
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天 (Live)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天 (伴奏)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天 (Remix)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天 (Cover 周杰伦)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天 (试听版)"))

        // 双方都有相同标签则允许匹配
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("晴天 (Live)", "晴天 (Live 版)"))
    }

    @Test
    fun testTitleMatch_subtitlesAndMovieInfo() {
        // 副标题与影视剧后缀剥离比对
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("借过一下 - 电视剧《鸣龙少年》插曲", "借过一下"))
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("借过一下 — 电视剧《鸣龙少年》插曲", "借过一下"))
    }

    @Test
    fun testArtistMatch_splitAndSubset() {
        // 单歌手
        assertTrue(OnlinePlaybackMatcher.isArtistMatched("周杰伦", "周杰伦"))
        // 多歌手斜杠拆分包含
        assertTrue(OnlinePlaybackMatcher.isArtistMatched("周杰伦 / 费玉清", "周杰伦"))
        assertTrue(OnlinePlaybackMatcher.isArtistMatched("周杰伦", "周杰伦 & 费玉清"))
        assertTrue(OnlinePlaybackMatcher.isArtistMatched("Aimer, 泽野弘之", "Aimer"))
        // 未知歌手宽容处理
        assertTrue(OnlinePlaybackMatcher.isArtistMatched("群星", "未知歌手"))
    }

    @Test
    fun testDurationThreshold_twoSeconds() {
        // 误差 <= 2.5秒（允许误差2秒左右）允许匹配
        assertTrue(
            OnlinePlaybackMatcher.isMatched(
                targetTitle = "夜曲",
                targetArtist = "周杰伦",
                candidateTitle = "夜曲",
                candidateArtist = "周杰伦",
                targetDurationMs = 226_000L,
                candidateDurationMs = 228_000L, // 误差 2s
            )
        )

        // 误差 > 2.5秒（如 3s、6s）严格拦截
        assertFalse(
            OnlinePlaybackMatcher.isMatched(
                targetTitle = "夜曲",
                targetArtist = "周杰伦",
                candidateTitle = "夜曲",
                candidateArtist = "周杰伦",
                targetDurationMs = 226_000L,
                candidateDurationMs = 229_000L, // 误差 3s
            )
        )
        assertFalse(
            OnlinePlaybackMatcher.isMatched(
                targetTitle = "夜曲",
                targetArtist = "周杰伦",
                candidateTitle = "夜曲",
                candidateArtist = "周杰伦",
                targetDurationMs = 226_000L,
                candidateDurationMs = 232_000L, // 误差 6s
            )
        )
    }

    @Test
    fun testCalculateMatchScore_ranking() {
        // 完全匹配满分最高
        val exactScore = OnlinePlaybackMatcher.calculateMatchScore(
            targetTitle = "稻香",
            targetArtist = "周杰伦",
            candidateTitle = "稻香",
            candidateArtist = "周杰伦",
            targetDurationMs = 223_000L,
            candidateDurationMs = 223_500L,
        )
        // 歌手包含匹配得分次之
        val partialScore = OnlinePlaybackMatcher.calculateMatchScore(
            targetTitle = "稻香",
            targetArtist = "周杰伦",
            candidateTitle = "稻香",
            candidateArtist = "周杰伦 / 潘儿",
            targetDurationMs = 223_000L,
            candidateDurationMs = 226_000L,
        )
        assertTrue("完全匹配得分应当高于部分匹配得分", exactScore > partialScore)
        assertEquals(100, exactScore) // 50 (歌名完全) + 30 (歌手完全) + 20 (时长<=1s)
    }
}
