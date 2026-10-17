package com.rawsmusic.module.online.sync

import com.google.gson.Gson
import com.rawsmusic.core.common.model.UserPlaylist
import com.rawsmusic.core.common.model.UserPlaylistSong
import com.rawsmusic.core.common.model.isOnlineEntry
import com.rawsmusic.core.common.model.isVipSong
import com.rawsmusic.core.common.model.toUserPlaylistSong
import com.rawsmusic.module.data.db.entity.OnlinePlaylistFavoriteEntity
import com.rawsmusic.module.online.model.OnlinePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端 JSON 契约的序列化测试。
 *
 * **为什么要专门测字段名**：这套 JSON 是 OrBit ↔ RawS ↔ Worker 三方共用的交换格式。
 * 字段名写错（比如 `durationMs` 写成 `duration`、`sourcePlatform` 写成 `platform`）
 * **不会编译报错、也不会在本地跑出错** —— 只有在跨端同步时数据才会对不上，
 * 而那时排查成本极高。所以这里直接断言序列化后的**原始字符串**里含有契约要求的键名。
 */
class CloudPlaylistJsonTest {

    private val gson = Gson()

    @Test
    fun `playlist json uses the exact contract field names`() {
        val playlist = UserPlaylist(
            id = "p1",
            name = "我的歌单",
            songs = listOf(localSong()),
            createdAt = 1730000000000L,
            updatedAt = 0L,
        )

        val json = CloudPlaylistJson.buildPlaylistsJson(
            listOf(CloudPlaylistJson.localPlaylist(playlist, groupName = "默认")),
        )

        // 契约 §4 ①：歌单层
        assertTrue("缺 name", json.contains("\"name\""))
        assertTrue("缺 groupName", json.contains("\"groupName\""))
        assertTrue("缺 createdAt", json.contains("\"createdAt\""))
        assertTrue("缺 songs", json.contains("\"songs\""))
        // 契约 §4 ①：歌曲层（逐个断言，任何一个拼错都会让跨端失配）
        listOf(
            "title", "artist", "album", "durationMs", "path", "albumArtUri",
            "coverUrl", "isOnline", "sourcePlatform", "isFavorite", "isDisliked",
        ).forEach { key ->
            assertTrue("缺歌曲字段 $key", json.contains("\"$key\""))
        }
        // 不该出现的名字（防手滑改名）
        assertFalse("不应出现 duration（契约是 durationMs）", json.contains("\"duration\""))
    }

    @Test
    fun `online favorite json uses the exact contract field names`() {
        val json = CloudPlaylistJson.buildOnlineFavoritesJson(
            listOf(
                OnlinePlaylistFavoriteEntity(
                    playlistKey = "netease_123",
                    platform = "netease",
                    remoteId = "123",
                    title = "在线歌单",
                    coverUrl = "https://cover",
                    playCount = 0L,
                    trackCount = 7,
                    creatorName = "creator",
                    creatorAvatarUrl = "https://avatar",
                    description = "desc",
                    groupName = "默认",
                    addedAt = 1L,
                ),
            ),
        )

        listOf(
            "id", "platform", "title", "coverUrl", "trackCount",
            "creatorName", "creatorAvatarUrl", "description", "customGroup",
        ).forEach { key ->
            assertTrue("缺在线收藏字段 $key", json.contains("\"$key\""))
        }
    }

    @Test
    fun `sourcePlatform uses the uppercase enum name, not the internal lowercase id`() {
        val onlineSong = onlineSong()
        val dto = CloudPlaylistJson.songOf(onlineSong, isFavorite = false, isDisliked = false)

        // 契约要的是 NETEASE，不是内部用的 netease。
        assertEquals("NETEASE", dto.sourcePlatform)
        assertTrue(dto.isOnline)

        val localDto = CloudPlaylistJson.songOf(localSong(), isFavorite = true, isDisliked = false)
        assertEquals("", localDto.sourcePlatform)
        assertFalse(localDto.isOnline)
        assertTrue(localDto.isFavorite)
    }

    @Test
    fun `system playlists use the exact contract names and group`() {
        val favorite = CloudPlaylistJson.systemPlaylist(
            CloudPlaylistJson.SYSTEM_PLAYLIST_FAVORITE,
            emptyList(),
        )
        val disliked = CloudPlaylistJson.systemPlaylist(
            CloudPlaylistJson.SYSTEM_PLAYLIST_DISLIKED,
            emptyList(),
        )

        assertEquals("我喜欢的音乐", favorite.name)
        assertEquals("不喜欢的音乐", disliked.name)
        assertEquals("系统", favorite.groupName)
        assertEquals("系统", disliked.groupName)
        // 这两个字面量是跨端互认的锚点，改动即破坏兼容。
        assertEquals("我喜欢的音乐", CloudPlaylistJson.SYSTEM_PLAYLIST_FAVORITE)
        assertEquals("不喜欢的音乐", CloudPlaylistJson.SYSTEM_PLAYLIST_DISLIKED)
        assertEquals("系统", CloudPlaylistJson.SYSTEM_GROUP_NAME)
        assertEquals("默认", CloudPlaylistJson.DEFAULT_GROUP_NAME)
    }

    @Test
    fun `groups json drops default and always keeps system`() {
        val json = CloudPlaylistJson.buildGroupsJson(listOf("默认", "车载", "系统", "  ", "车载"))

        val parsed = gson.fromJson(json, Array<String>::class.java).toList()
        // 「默认」被丢弃（否则 overwrite 模式会把它真的存进云端分组列表）。
        assertFalse(parsed.contains("默认"))
        // 系统分组总是带上（服务端会保留它）。
        assertTrue(parsed.contains("系统"))
        // 去重 + 去空白。
        assertTrue(parsed.contains("车载"))
        assertEquals(1, parsed.count { it == "车载" })
        assertEquals(2, parsed.size)
    }

    @Test
    fun `song built from key alone keeps the identity in path`() {
        // 「不喜欢」只有 song_key、没有元数据；服务端去重键包含 path，所以身份不会丢。
        val dto = CloudPlaylistJson.songOfKey("online://qq/ABC", isFavorite = false, isDisliked = true)

        assertEquals("online://qq/ABC", dto.path)
        assertTrue(dto.isDisliked)
        assertTrue(dto.isOnline)
        assertEquals("QQ", dto.sourcePlatform)
    }

    @Test
    fun `platform wire name round trips`() {
        OnlinePlatform.entries.forEach { platform ->
            val wire = CloudPlaylistJson.platformWireName(platform)
            assertEquals(
                "线上名应与枚举名一致（大写）",
                platform.name,
                wire,
            )
            assertEquals(platform, CloudPlaylistJson.platformFromWireName(wire))
        }
        // 小写也能认（容错），认不出则返回 null 而不是猜。
        assertEquals(OnlinePlatform.NETEASE, CloudPlaylistJson.platformFromWireName("netease"))
        assertEquals(null, CloudPlaylistJson.platformFromWireName("spotify"))
    }

    @Test
    fun `playlists json round trips through gson`() {
        val playlist = UserPlaylist(
            id = "p1",
            name = "混合歌单",
            songs = listOf(localSong(), onlineSong()),
            createdAt = 1730000000000L,
            updatedAt = 0L,
        )

        val json = CloudPlaylistJson.buildPlaylistsJson(
            listOf(CloudPlaylistJson.localPlaylist(playlist, groupName = "车载")),
        )
        val restored = gson.fromJson(json, Array<CloudPlaylistDto>::class.java)

        assertNotNull(restored)
        val one = restored.single()
        assertEquals("混合歌单", one.name)
        assertEquals("车载", one.groupName)
        assertEquals(2, one.songs.size)
        assertEquals("online://netease/12345", one.songs[1].path)
        assertTrue(one.songs[1].isOnline)
        assertEquals("NETEASE", one.songs[1].sourcePlatform)
    }

    // ------------------------------------------------- 还原方向（解析与分类）

    @Test
    fun `parse playlists round trips what we uploaded`() {
        val playlist = UserPlaylist(
            id = "p1",
            name = "混合歌单",
            songs = listOf(localSong(), onlineSong()),
            createdAt = 1730000000000L,
            updatedAt = 0L,
        )
        val json = CloudPlaylistJson.buildPlaylistsJson(
            listOf(CloudPlaylistJson.localPlaylist(playlist, groupName = "车载")),
        )

        val parsed = CloudPlaylistJson.parsePlaylists(json).single()

        assertEquals("混合歌单", parsed.name)
        assertEquals("车载", parsed.groupName)
        assertEquals(2, parsed.songs.size)
        assertEquals("夜曲", parsed.songs[0].title)
        assertEquals("/music/a.mp3", parsed.songs[0].path)
        assertFalse(CloudPlaylistJson.isOnlineSong(parsed.songs[0]))
        assertTrue(CloudPlaylistJson.isOnlineSong(parsed.songs[1]))
    }

    @Test
    fun `parse tolerates blank json`() {
        // 云端可能还没写过这一段，不能让还原整体失败。
        assertTrue(CloudPlaylistJson.parsePlaylists(null).isEmpty())
        assertTrue(CloudPlaylistJson.parsePlaylists("").isEmpty())
        assertTrue(CloudPlaylistJson.parseOnlineFavorites(null).isEmpty())
        assertTrue(CloudPlaylistJson.parseGroups(null).isEmpty())
    }

    @Test
    fun `system playlists are recognised by name only`() {
        val favorite = CloudPlaylistJson.systemPlaylist("我喜欢的音乐", emptyList())
        val disliked = CloudPlaylistJson.systemPlaylist("不喜欢的音乐", emptyList())
        val normal = CloudPlaylistDto(
            name = "车载",
            groupName = "系统", // 分组名是「系统」但名字不是那两个 → 仍是普通歌单
            createdAt = 0L,
            songs = emptyList(),
        )

        assertEquals(
            CloudPlaylistJson.SystemPlaylistKind.FAVORITE,
            CloudPlaylistJson.systemKindOf(favorite),
        )
        assertEquals(
            CloudPlaylistJson.SystemPlaylistKind.DISLIKED,
            CloudPlaylistJson.systemKindOf(disliked),
        )
        // 只看名字：分组名对得上也不算系统歌单（overwrite 模式下分组名可能被改）。
        assertNull(CloudPlaylistJson.systemKindOf(normal))
    }

    @Test
    fun `local song key carries the path prefix while online key stays bare`() {
        // 这是最容易写错的一处：两类歌的 song_key 规则不同。
        val local = CloudPlaylistJson.songOf(localSong(), isFavorite = false, isDisliked = false)
        val localKey = CloudPlaylistJson.localSongKeyOf(local)
        assertTrue(
            "本地曲的 key 必须带 path| 前缀（实际=$localKey）",
            localKey.startsWith("path|"),
        )

        val online = CloudPlaylistJson.songOf(onlineSong(), isFavorite = false, isDisliked = false)
        assertEquals(
            "在线曲的 key 就是那个 online:// 串本身",
            "online://netease/12345",
            CloudPlaylistJson.localSongKeyOf(online),
        )
    }

    @Test
    fun `online rows are rebuilt with the core convention`() {
        val dto = CloudPlaylistJson.songOf(onlineSong(), isFavorite = false, isDisliked = false)

        val row = CloudPlaylistJson.toOnlineSongRow(dto)
        assertNotNull(row)
        // key 与 path 必须是同一个串、id 必须是负数虚拟 id（P2-05b 约定）。
        assertEquals("online://netease/12345", row!!.key)
        assertEquals("online://netease/12345", row.path)
        assertTrue("在线行必须是负数虚拟 id", row.id < 0L)
        assertTrue(row.isOnlineEntry())

        // 本地曲不该被当成在线行。
        val localDto = CloudPlaylistJson.songOf(localSong(), isFavorite = false, isDisliked = false)
        assertNull(CloudPlaylistJson.toOnlineSongRow(localDto))
    }

    @Test
    fun `audio file built from cloud song keeps path and drops the foreign id`() {
        val dto = CloudPlaylistJson.songOf(localSong(), isFavorite = false, isDisliked = false)
        val file = CloudPlaylistJson.toAudioFile(dto)

        assertEquals("/music/a.mp3", file.path)
        assertEquals("夜曲", file.title)
        // 云端的 id 属于别的设备，不能带过来（带了会指错歌）。
        assertEquals(0L, file.id)
        // 经 core 转换后的 key 与本地既有规则一致。
        assertTrue(file.toUserPlaylistSong().key.startsWith("path|"))
    }

    @Test
    fun `online favorite dto maps back to a playlist with a lowercase platform id`() {
        val dto = CloudOnlineFavoriteDto(
            id = "123",
            platform = "NETEASE",
            title = "在线歌单",
            coverUrl = "",
            trackCount = 7,
            creatorName = "",
            creatorAvatarUrl = "",
            description = "",
            customGroup = "默认",
        )

        val playlist = CloudPlaylistJson.toOnlinePlaylist(dto)
        assertNotNull(playlist)
        assertEquals("123", playlist!!.id)
        // 契约用大写枚举名，本地模型用小写平台 id —— 这里必须转回来。
        assertEquals(OnlinePlatform.NETEASE, playlist.platform)
        assertEquals("netease", playlist.platform.id)

        // 认不出的平台返回 null，而不是猜一个。
        assertNull(CloudPlaylistJson.toOnlinePlaylist(dto.copy(platform = "spotify")))
    }

    @Test
    fun `parse groups round trips and keeps system`() {
        val json = CloudPlaylistJson.buildGroupsJson(listOf("车载", "默认"))
        val parsed = CloudPlaylistJson.parseGroups(json)
        assertTrue(parsed.contains("车载"))
        assertTrue(parsed.contains("系统"))
        assertFalse(parsed.contains("默认"))
    }

    @Test
    fun `online song vip flag preserves across cloud serialization round trip`() {
        val vipOnlineSong = onlineSong().copy(format = "VIP")
        assertTrue(vipOnlineSong.isVipSong())

        val dto = CloudPlaylistJson.songOf(vipOnlineSong, isFavorite = false, isDisliked = false)
        assertTrue(dto.isVip)

        val json = CloudPlaylistJson.buildPlaylistsJson(listOf(CloudPlaylistJson.systemPlaylist("测试", listOf(dto))))
        val parsedDto = CloudPlaylistJson.parsePlaylists(json).single().songs.single()
        assertTrue(parsedDto.isVip)

        val restoredRow = CloudPlaylistJson.toOnlineSongRow(parsedDto)
        assertNotNull(restoredRow)
        assertTrue(restoredRow!!.isVipSong())
        assertEquals("VIP", restoredRow.format)
    }

    // --------------------------------------------------------------- 构造工具

    private fun localSong() = UserPlaylistSong(
        key = "path|/music/a.mp3",
        id = 1L,
        title = "夜曲",
        artist = "周杰伦",
        album = "十一月的萧邦",
        albumId = 0L,
        duration = 227_000L,
        path = "/music/a.mp3",
        fileSize = 0L,
        format = "mp3",
        sampleRate = 44100,
        bitRate = 320,
        bitsPerSample = 16,
        albumArtPath = "",
        addedAt = 0L,
    )

    private fun onlineSong() = UserPlaylistSong(
        key = "online://netease/12345",
        id = -777L,
        title = "Online Track",
        artist = "Some Artist",
        album = "",
        albumId = 0L,
        duration = 180_000L,
        path = "online://netease/12345",
        fileSize = 0L,
        format = "online",
        sampleRate = 0,
        bitRate = 0,
        bitsPerSample = 0,
        albumArtPath = "",
        addedAt = 0L,
    )
}
