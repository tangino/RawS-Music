package com.rawsmusic.module.online.auth.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class QQMusicAuthServiceTest {

    private val service = QQMusicAuthService()

    @Test
    fun testCleanJsonp_preservesPureJsonContainingParentheses() {
        val pureJson = """{"code":0,"data":{"disslist":[{"diss_name":"粤语经典(Live)"}]}}"""
        assertEquals(pureJson, service.cleanJsonp(pureJson))
    }

    @Test
    fun testCleanJsonp_unwrapsJsonpCallback() {
        val jsonp = """MusicJsonCallback({"code":0,"data":{"title":"周杰伦(精选)"}})"""
        assertEquals("""{"code":0,"data":{"title":"周杰伦(精选)"}}""", service.cleanJsonp(jsonp))
    }

    @Test
    fun testExtractCookiesInto_keepsBase64EqualsAndFiltersAttributes() {
        val map = mutableMapOf<String, String>()
        service.extractCookiesInto(
            "qm_keyst=Q_H_L_abc123==; Path=/; Domain=.qq.com; Expires=Fri, 01 Jan 2030 00:00:00 GMT; HttpOnly; SameSite=None",
            map,
        )
        assertEquals("Q_H_L_abc123==", map["qm_keyst"])
        assertFalse(map.containsKey("Path"))
        assertFalse(map.containsKey("Domain"))
        assertFalse(map.containsKey("Expires"))
        assertFalse(map.containsKey("SameSite"))
    }
}
