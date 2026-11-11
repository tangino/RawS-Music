package com.rawsmusic.module.data.playlist

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistTransferStoreTest {

    @Test
    fun `exportPlainText formats one song per line with title and artist`() {
        val playlist = UserPlaylist(
            id = "test",
            name = "精选集",
            songs = listOf(
                song(title = "晴天", artist = "周杰伦"),
                song(title = "海阔天空", artist = "Beyond"),
                song(title = "孤独患者", artist = "陈奕迅"),
            ),
            createdAt = 0L,
            updatedAt = 0L,
        )

        val text = PlaylistTransferStore.exportPlainText(playlist)
        val expected = """
            晴天 - 周杰伦
            海阔天空 - Beyond
            孤独患者 - 陈奕迅
        """.trimIndent()

        assertEquals(expected, text)
    }

    @Test
    fun `exportPlainText handles unknown or blank artist gracefully`() {
        val playlist = UserPlaylist(
            id = "test",
            name = "精选集",
            songs = listOf(
                song(title = "纯音曲目", artist = ""),
                song(title = "未知曲目", artist = "未知歌手"),
                song(title = "英文未知", artist = "Unknown"),
                song(title = "正常曲目", artist = "林俊杰"),
            ),
            createdAt = 0L,
            updatedAt = 0L,
        )

        val text = PlaylistTransferStore.exportPlainText(playlist)
        val expected = """
            纯音曲目
            未知曲目
            英文未知
            正常曲目 - 林俊杰
        """.trimIndent()

        assertEquals(expected, text)
    }

    @Test
    fun `exportPlainText from AudioFiles outputs one song per line`() {
        val audioFiles = listOf(
            AudioFile(
                id = 1L,
                title = "七里香",
                artist = "周杰伦",
                album = "七里香",
                albumId = 1L,
                duration = 300_000L,
                path = "/storage/music/七里香.mp3",
                fileSize = 1000L,
                dateAdded = 0L,
                dateModified = 0L,
            ),
            AudioFile(
                id = 2L,
                title = "追梦赤子心",
                artist = "GALA",
                album = "追梦痴子心",
                albumId = 2L,
                duration = 200_000L,
                path = "/storage/music/追梦赤子心.flac",
                fileSize = 2000L,
                dateAdded = 0L,
                dateModified = 0L,
            ),
        )

        val text = PlaylistTransferStore.exportPlainText(audioFiles)
        val expected = """
            七里香 - 周杰伦
            追梦赤子心 - GALA
        """.trimIndent()

        assertEquals(expected, text)
    }

    @Test
    fun `exportPlainText with empty playlist produces empty string`() {
        val playlist = UserPlaylist(
            id = "empty",
            name = "空歌单",
            songs = emptyList(),
            createdAt = 0L,
            updatedAt = 0L,
        )

        assertEquals("", PlaylistTransferStore.exportPlainText(playlist))
    }

    private fun song(title: String, artist: String) = UserPlaylistSong(
        key = "song|$title|$artist",
        id = 1L,
        title = title,
        artist = artist,
        album = "Album",
        albumId = 0L,
        duration = 180_000L,
        path = "/storage/music/$title.mp3",
        fileSize = 0L,
        format = "mp3",
        sampleRate = 44100,
        bitRate = 320,
        bitsPerSample = 16,
        albumArtPath = "",
        addedAt = 0L,
    )
}

