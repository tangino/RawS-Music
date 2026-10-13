package com.rawsmusic.module.data.playlist

import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PlaylistM3u8Codec` 的编解码往返测试。
 *
 * 为什么值得写：这是个**手写的格式编解码器**，错一个分隔符或前缀就会让导出的文件在别的播放器里
 * 读不出来 —— 而这类错误编译期与运行期都不报，只有真去解析才会暴露。往返测试是最便宜的护栏。
 */
class PlaylistM3u8CodecTest {

    @Test
    fun `encode then decode keeps local song fields`() {
        val playlist = playlistOf(
            song(
                key = "path|/music/a.mp3",
                id = 1L,
                title = "夜曲",
                artist = "周杰伦",
                album = "十一月的萧邦",
                durationMs = 227_000L,
                path = "/music/a.mp3",
            ),
        )

        val decoded = PlaylistM3u8Codec.decode(PlaylistM3u8Codec.encode(playlist))

        assertEquals("我的歌单", decoded.title)
        assertEquals(1, decoded.entries.size)
        val entry = decoded.entries.single()
        assertEquals("夜曲", entry.title)
        assertEquals("周杰伦", entry.artist)
        assertEquals(227_000L, entry.durationMs)
        assertEquals("/music/a.mp3", entry.path)
        assertFalse(entry.isOnline)
    }

    @Test
    fun `encode then decode marks online song as online`() {
        val playlist = playlistOf(
            song(
                key = "online://netease/12345",
                id = -777L,
                title = "Online Track",
                artist = "Some Artist",
                album = "",
                durationMs = 180_000L,
                path = "online://netease/12345",
            ),
        )

        val text = PlaylistM3u8Codec.encode(playlist)

        // 导出用 #EXT-RAWS- 前缀（不是 AGENTS 里写的 #EXTRAWS-）。
        assertTrue("应写入自定义标签前缀", text.contains("#EXT-RAWS-ONLINE:netease:12345"))

        val entry = PlaylistM3u8Codec.decode(text).entries.single()
        assertTrue(entry.isOnline)
        assertEquals("netease", entry.onlinePlatformId)
        assertEquals("12345", entry.onlineSongId)
        assertEquals("online://netease/12345", entry.path)
    }

    @Test
    fun `decode accepts the legacy orbit tag prefix`() {
        // 模拟 OrBit 导出的文件：前缀是 #EXT-ORBIT-。
        val text = """
            #EXTM3U
            #PLAYLIST:OrBit 歌单
            #EXT-ORBIT-CREATOR:someone

            #EXTINF:200,Artist A - Song A
            #EXT-ORBIT-ONLINE:qq:ABC123
            online://qq/ABC123

        """.trimIndent()

        val decoded = PlaylistM3u8Codec.decode(text)

        assertEquals("OrBit 歌单", decoded.title)
        assertEquals("someone", decoded.creatorName)
        val entry = decoded.entries.single()
        assertTrue(entry.isOnline)
        assertEquals("qq", entry.onlinePlatformId)
        assertEquals("ABC123", entry.onlineSongId)
        assertEquals("Artist A", entry.artist)
        assertEquals("Song A", entry.title)
    }

    @Test
    fun `decode without a dash separator treats the whole info as title`() {
        val text = """
            #EXTM3U
            #EXTINF:100,JustATitleWithoutSeparator
            /music/b.mp3
        """.trimIndent()

        val entry = PlaylistM3u8Codec.decode(text).entries.single()

        assertEquals("JustATitleWithoutSeparator", entry.title)
        assertEquals("", entry.artist)
        assertEquals("/music/b.mp3", entry.path)
    }

    @Test
    fun `decode tolerates garbage and unknown tags without crashing`() {
        val text = """
            随便一行没有 # 开头也不是路径的东西
            #EXTM3U
            #UNKNOWN-TAG:whatever
            #EXTINF:不是数字,Artist - Title
            /music/c.mp3
        """.trimIndent()

        val decoded = PlaylistM3u8Codec.decode(text)

        // 第一行虽然是垃圾，但按「路径行」处理会产出一个条目；关键是**不崩**且后面的条目仍被解析。
        assertTrue(decoded.entries.isNotEmpty())
        val real = decoded.entries.last()
        assertEquals("Title", real.title)
        assertEquals("Artist", real.artist)
        assertEquals("/music/c.mp3", real.path)
        // 时长解析不出来时归零，而不是抛异常。
        assertEquals(0L, real.durationMs)
    }

    @Test
    fun `sanitizeFileName strips illegal characters`() {
        assertEquals("a_b_c_d", PlaylistM3u8Codec.sanitizeFileName("a/b:c*d"))
        assertEquals("playlist", PlaylistM3u8Codec.sanitizeFileName("   "))
    }

    // --------------------------------------------------------------- 构造工具

    private fun playlistOf(vararg songs: UserPlaylistSong) = UserPlaylist(
        id = "playlist-test",
        name = "我的歌单",
        songs = songs.toList(),
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun song(
        key: String,
        id: Long,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        path: String,
    ) = UserPlaylistSong(
        key = key,
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = 0L,
        duration = durationMs,
        path = path,
        fileSize = 0L,
        format = "mp3",
        sampleRate = 44100,
        bitRate = 320,
        bitsPerSample = 16,
        albumArtPath = "",
        addedAt = 0L,
    )
}
