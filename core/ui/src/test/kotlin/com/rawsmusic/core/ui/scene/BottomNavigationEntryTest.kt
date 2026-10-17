package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationEntryTest {
    @Test
    fun fourItemSavedOrderIsPreservedExactly() {
        val tags = listOf("home", "audio_effects", "search", "settings")

        assertEquals(
            listOf(
                NavScene.HOME,
                NavScene.AUDIO_EFFECTS,
                NavScene.SEARCH,
                NavScene.SETTINGS,
            ),
            resolveBottomNavigationScenes(tags),
        )
    }

    @Test
    fun configurableEntriesHaveUniqueStableTags() {
        assertEquals(
            customizableBottomNavigationScenes.size,
            customizableBottomNavigationScenes.map { it.tag }.distinct().size,
        )
    }

    @Test
    fun sixItemSavedOrderWithOnlineMusicIsPreservedExactly() {
        val tags = listOf("home", "songs", "audio_effects", "search", "settings", "online_music")

        assertEquals(
            listOf(
                NavScene.HOME,
                NavScene.SONGS,
                NavScene.AUDIO_EFFECTS,
                NavScene.SEARCH,
                NavScene.SETTINGS,
                NavScene.ONLINE_MUSIC,
            ),
            resolveBottomNavigationScenes(tags),
        )
    }
}
