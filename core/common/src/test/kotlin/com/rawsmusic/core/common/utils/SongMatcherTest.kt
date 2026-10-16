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
}
