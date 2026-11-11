package com.rawsmusic.module.online.repository

import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.model.OnlinePlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class QQPrivateRecommendRepositoryTest {

    @Test
    fun `online playlist model maintains qq platform and attributes`() {
        val playlist = OnlinePlaylist(
            id = "8075336924",
            platform = OnlinePlatform.QQ,
            title = "90后回忆 | MP3时代的流行歌曲",
            coverUrl = "http://qpic.y.qq.com/music_cover/xxx/300",
            playCount = 147_795_829L,
            trackCount = 30,
            creatorName = "听风",
            description = "QQ音乐 · 专属私荐",
            customGroup = "QQ"
        )

        assertEquals("8075336924", playlist.id)
        assertEquals(OnlinePlatform.QQ, playlist.platform)
        assertEquals("90后回忆 | MP3时代的流行歌曲", playlist.title)
        assertEquals("听风", playlist.creatorName)
        assertEquals("QQ音乐 · 专属私荐", playlist.description)
        assertNotNull(playlist.coverUrl)
    }

    @Test
    fun `singleton instance is not null`() {
        val repo = QQPrivateRecommendRepository.getInstance()
        assertNotNull(repo)
        assertNotNull(repo.playlists)
        assertNotNull(repo.isRefreshing)
    }
}

