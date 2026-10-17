package com.rawsmusic.core.common.utils

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMatcherTest {

    @Test
    fun testTitleMatch() {
        assertTrue(SongMatcher.isTitleMatched("晴天", "晴天"))
        assertTrue(SongMatcher.isTitleMatched("晴天 (Live)", "晴天 (Live)"))
        assertTrue(SongMatcher.isTitleMatched("晴天 (电视剧《某剧》片尾曲)", "晴天"))
        // 带连接符歌手后缀/前缀智能识别与容错剥离
        assertTrue(SongMatcher.isTitleMatched("离开我的依赖", "离开我的依赖-王艳薇", "王艳薇", "王艳薇"))
        assertTrue(SongMatcher.isTitleMatched("离开我的依赖-王艳薇", "离开我的依赖", "王艳薇", "王艳薇"))
        assertTrue(SongMatcher.isTitleMatched("王艳薇-离开我的依赖", "离开我的依赖", "王艳薇", "王艳薇"))
        // 特殊版本标签不一致不应匹配
        assertFalse(SongMatcher.isTitleMatched("晴天", "晴天 (Live)"))
        assertFalse(SongMatcher.isTitleMatched("晴天 (伴奏)", "晴天"))
    }

    @Test
    fun testArtistMatch() {
        assertTrue(SongMatcher.isArtistMatched("周杰伦", "周杰伦"))
        assertTrue(SongMatcher.isArtistMatched("周杰伦 / 费玉清", "周杰伦"))
        assertTrue(SongMatcher.isArtistMatched("周杰伦, 费玉清", "费玉清"))
        assertFalse(SongMatcher.isArtistMatched("周杰伦", "林俊杰"))
    }

    @Test
    fun testDurationMatch() {
        // 误差在 5 秒 (5000ms) 以内匹配
        assertTrue(
            SongMatcher.isMatched(
                targetTitle = "七里香",
                targetArtist = "周杰伦",
                candidateTitle = "七里香",
                candidateArtist = "周杰伦",
                targetDurationMs = 299000L,
                candidateDurationMs = 302000L, // 差 3 秒
            )
        )

        // 误差超过 5 秒不匹配
        assertFalse(
            SongMatcher.isMatched(
                targetTitle = "七里香",
                targetArtist = "周杰伦",
                candidateTitle = "七里香",
                candidateArtist = "周杰伦",
                targetDurationMs = 299000L,
                candidateDurationMs = 310000L, // 差 11 秒
            )
        )
    }

    @Test
    fun testFindBestLocalMatch() {
        val onlineSong = AudioFile(
            id = -123456L,
            path = "online://netease/123456",
            title = "夜曲",
            artist = "周杰伦",
            album = "十一月的萧邦",
            duration = 226000L,
            albumArtPath = "https://example.com/cover.jpg",
        )

        val localSongs = listOf(
            AudioFile(
                id = 1L,
                path = "/storage/emulated/0/Music/夜曲_其他歌手.mp3",
                title = "夜曲",
                artist = "肖邦",
                album = "古典",
                duration = 226000L,
                albumArtPath = "",
            ),
            AudioFile(
                id = 2L,
                path = "/storage/emulated/0/Music/周杰伦 - 夜曲.flac",
                title = "夜曲",
                artist = "周杰伦",
                album = "十一月的萧邦",
                duration = 227500L, // 误差 1.5 秒
                albumArtPath = "",
            )
        )

        val match = SongMatcher.findBestLocalMatch(onlineSong, localSongs)
        assertNotNull(match)
        assertEquals("/storage/emulated/0/Music/周杰伦 - 夜曲.flac", match!!.path)
        // 继承了在线歌曲的网络封面
        assertEquals("https://example.com/cover.jpg", match.albumArtPath)
    }

    @Test
    fun testFindBestLocalMatchWithArtistSuffix() {
        val onlineSong = AudioFile(
            id = -999888L,
            path = "online://netease/999888",
            title = "离开我的依赖",
            artist = "王艳薇",
            album = "框不住的王艳薇",
            duration = 215000L,
            albumArtPath = "https://example.com/cover2.jpg",
        )

        val localSongs = listOf(
            AudioFile(
                id = 10L,
                path = "/storage/emulated/0/Music/离开我的依赖-王艳薇.flac",
                title = "离开我的依赖-王艳薇",
                artist = "王艳薇",
                album = "框不住的王艳薇",
                duration = 216000L,
                albumArtPath = "",
            )
        )

        val match = SongMatcher.findBestLocalMatch(onlineSong, localSongs)
        assertNotNull(match)
        assertEquals("/storage/emulated/0/Music/离开我的依赖-王艳薇.flac", match!!.path)
        assertEquals("https://example.com/cover2.jpg", match.albumArtPath)
    }

    @Test
    fun testQualityWeightingFlacPreferredOverMp3() {
        val onlineSong = AudioFile(
            id = -100L,
            path = "online://qq/100",
            title = "稻香",
            artist = "周杰伦",
            album = "魔杰座",
            duration = 223000L,
        )

        val localSongs = listOf(
            AudioFile(
                id = 1L,
                path = "/storage/emulated/0/Music/稻香_低清.mp3",
                title = "稻香",
                artist = "周杰伦",
                album = "魔杰座",
                duration = 223000L, // 时长完全一致
                format = "mp3",
                bitRate = 128,
            ),
            AudioFile(
                id = 2L,
                path = "/storage/emulated/0/Music/稻香_无损母带.flac",
                title = "稻香",
                artist = "周杰伦",
                album = "魔杰座",
                duration = 223500L, // 哪怕时长稍微有 500ms 差异
                format = "flac",
                bitsPerSample = 24,
                sampleRate = 96000,
            )
        )

        val match = SongMatcher.findBestLocalMatch(onlineSong, localSongs)
        assertNotNull(match)
        // 验证打分优选：必须优先选中 24bit/96kHz 的 FLAC，而不是 MP3
        assertEquals("/storage/emulated/0/Music/稻香_无损母带.flac", match!!.path)
    }

    @Test
    fun testLocalSongIndexAcceleration() {
        val target = AudioFile(
            id = 100L,
            path = "online://netease/123",
            title = "离开我的依赖-王艳薇",
            artist = "王艳薇",
            duration = 240000L,
        )

        val localLibrary = listOf(
            AudioFile(
                id = 1L,
                path = "/storage/emulated/0/Music/离开我的依赖.flac",
                title = "离开我的依赖",
                artist = "王艳薇",
                duration = 241000L,
                format = "flac",
            ),
            AudioFile(
                id = 2L,
                path = "/storage/emulated/0/Music/完全不相干的一首歌.mp3",
                title = "七里香",
                artist = "周杰伦",
                duration = 300000L,
            )
        )

        val index = SongMatcher.buildIndex(localLibrary)
        val candidates = index.getCandidates("离开我的依赖-王艳薇")
        assertEquals(1, candidates.size)
        assertEquals("/storage/emulated/0/Music/离开我的依赖.flac", candidates.first().path)

        val match = SongMatcher.findBestLocalMatch(target, localLibrary, index)
        assertNotNull(match)
        assertEquals("/storage/emulated/0/Music/离开我的依赖.flac", match?.path)

        // 对完全不存在的歌曲，快速索引返回空候选集，瞬间判定为 null
        val notExist = AudioFile(
            id = 101L,
            path = "online://netease/456",
            title = "不存在的歌曲",
            artist = "未知",
            duration = 200000L,
        )
        val matchNull = SongMatcher.findBestLocalMatch(notExist, localLibrary, index)
        assertNull(matchNull)
    }
}
