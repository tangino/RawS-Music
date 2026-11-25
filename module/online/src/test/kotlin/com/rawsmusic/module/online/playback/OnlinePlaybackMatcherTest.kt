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
        // 原曲不得匹配到任何形态的 Live 版、伴奏版、翻唱版（含中英/数英紧邻无空格格式）
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天 (Live)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天(live版)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天(2023live)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天live"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("Alive", "Alive (Live)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("Live Forever", "Live Forever (Live)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天 (伴奏)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天 (Remix)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天 (Cover 周杰伦)"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("晴天", "晴天 (试听版)"))

        // 国粤双语版本严格隔离
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("明年今日(粤)", "明年今日"))
        assertFalse(OnlinePlaybackMatcher.isTitleMatched("富士山下", "富士山下(粤语)"))

        // 双方本身都是 Live 版本时允许正常匹配
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("晴天 (Live)", "晴天 (Live 版)"))
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("晴天(live)", "晴天(live版)"))
        // 英文含 live 单词的原版歌曲不应被误判
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("Alive", "Alive"))
    }

    @Test
    fun testLiveAlbumRejectedWhenTargetIsNotLive() {
        // 歌名未写 `(Live)`，但候选专辑为演唱会/Live 专辑时，只要目标歌曲本身不是 Live 一律淘汰
        assertFalse(
            OnlinePlaybackMatcher.isMatched(
                targetTitle = "富士山下",
                targetArtist = "陈奕迅",
                candidateTitle = "富士山下",
                candidateArtist = "陈奕迅",
                targetDurationMs = 259_000L,
                candidateDurationMs = 260_000L,
                targetAlbum = "What's Going On...?",
                candidateAlbum = "DUO 陈奕迅2010演唱会",
            )
        )
        assertFalse(
            OnlinePlaybackMatcher.isMatched(
                targetTitle = "浮夸",
                targetArtist = "陈奕迅",
                candidateTitle = "浮夸",
                candidateArtist = "陈奕迅",
                targetDurationMs = 283_000L,
                candidateDurationMs = 284_000L,
                targetAlbum = "U87",
                candidateAlbum = "Get A Life Live",
            )
        )
        // 若歌单中目标歌曲本身就是 Live（标题带 Live 或专辑是演唱会），则允许匹配 Live 候选
        assertTrue(
            OnlinePlaybackMatcher.isMatched(
                targetTitle = "富士山下 (Live)",
                targetArtist = "陈奕迅",
                candidateTitle = "富士山下 (Live)",
                candidateArtist = "陈奕迅",
                targetDurationMs = 260_000L,
                candidateDurationMs = 261_000L,
                targetAlbum = "",
                candidateAlbum = "DUO 陈奕迅2010演唱会",
            )
        )
    }

    @Test
    fun testTitleMatch_subtitlesAndMovieInfo() {
        // 副标题与影视剧后缀剥离比对
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("借过一下 - 电视剧《鸣龙少年》插曲", "借过一下"))
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("借过一下 — 电视剧《鸣龙少年》插曲", "借过一下"))
        // 无空格连字符书名号出处注释（如《重爱轻友》粤语版）不应被误判为当前歌名的多语种版本冲突
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("你系我嘅", "你系我嘅-《重爱轻友》粤语版"))
        assertTrue(OnlinePlaybackMatcher.isTitleMatched("你系我嘅", "你系我嘅 (《重爱轻友》粤语版)"))
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
    fun testDurationThreshold_fiveSeconds() {
        // 误差 <= 5秒允许匹配
        assertTrue(
            OnlinePlaybackMatcher.isMatched(
                targetTitle = "夜曲",
                targetArtist = "周杰伦",
                candidateTitle = "夜曲",
                candidateArtist = "周杰伦",
                targetDurationMs = 226_000L,
                candidateDurationMs = 229_000L, // 误差 3s
            )
        )

        // 误差 > 5秒（如 6s）严格拦截
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
