package com.rawsmusic.ui.floating

import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingLyricHelperTest {

    @Test
    fun `resolveVocalTiming prefers word timeline even when line endTime is estimated`() {
        val line = LyricLine(
            timeStamp = 10_000L,
            text = "夜空里最亮的星",
            endTime = 20_000L,
            endTimeIsEstimated = true,
            words = listOf(
                LyricWord("夜空里", begin = 10_000L, end = 11_200L),
                LyricWord("最亮的", begin = 11_200L, end = 12_400L),
                LyricWord("星", begin = 12_400L, end = 13_600L)
            )
        )
        val next = LyricLine(timeStamp = 20_000L, text = "能否听清")

        val timing = FloatingLyricHelper.resolveVocalTiming(line, next)
        assertTrue(timing.isExact)
        assertEquals(13_600L, timing.endTimeMs)

        // 人声在 13600ms 结束，加上 380ms 余韵缓冲 = 13980ms 进入间奏
        val stateBeforeFinish = FloatingLyricHelper.resolveLyricState(listOf(line, next), 13_800L)
        assertFalse(stateBeforeFinish.isInstrumental)
        assertEquals("line_0", stateBeforeFinish.stateKey)

        val stateAtInterlude = FloatingLyricHelper.resolveLyricState(listOf(line, next), 14_100L)
        assertTrue(stateAtInterlude.isInstrumental)
        assertEquals("interlude_0", stateAtInterlude.stateKey)
        assertEquals("♪ 间奏 ♪", stateAtInterlude.text1)
    }

    @Test
    fun `resolveVocalTiming uses explicit line endTime when not estimated`() {
        val line = LyricLine(
            timeStamp = 5_000L,
            text = "拉长尾音的短句",
            endTime = 11_500L,
            endTimeIsEstimated = false
        )
        val next = LyricLine(timeStamp = 16_000L, text = "下一句歌词")

        val timing = FloatingLyricHelper.resolveVocalTiming(line, next)
        assertTrue(timing.isExact)
        assertEquals(11_500L, timing.endTimeMs)

        // 若按旧字符估算，7 个字约 3860ms（8860ms 结束），但真实 endTime 为 11500ms，
        // 因此在 10000ms 时歌手仍在唱，绝不应提前误判为间奏
        val stateAt10s = FloatingLyricHelper.resolveLyricState(listOf(line, next), 10_000L)
        assertFalse(stateAt10s.isInstrumental)
        assertEquals("line_0", stateAt10s.stateKey)
    }

    @Test
    fun `resolveAbsoluteWords normalizes line-relative word timestamps`() {
        val line = LyricLine(
            timeStamp = 60_000L,
            text = "天青色等烟雨",
            endTime = 64_000L,
            words = listOf(
                LyricWord("天青色", begin = 0L, end = 1_500L),
                LyricWord("等烟雨", begin = 1_500L, end = 3_200L)
            )
        )

        val normalized = FloatingLyricHelper.resolveAbsoluteWords(line)
        assertEquals(60_000L, normalized[0].begin)
        assertEquals(61_500L, normalized[0].end)
        assertEquals(61_500L, normalized[1].begin)
        assertEquals(63_200L, normalized[1].end)
    }

    @Test
    fun `calculateAdvanceMs never switches before last two characters begin singing`() {
        val line = LyricLine(
            timeStamp = 10_000L,
            text = "这是一句紧凑收尾的歌词",
            endTime = 15_000L,
            words = listOf(
                LyricWord("这是一句", begin = 10_000L, end = 12_500L),
                LyricWord("紧凑收尾的", begin = 12_500L, end = 14_700L),
                LyricWord("歌词", begin = 14_700L, end = 15_000L)
            )
        )
        val next = LyricLine(timeStamp = 15_000L, text = "紧接着下一句")

        val advance = FloatingLyricHelper.calculateAdvanceMs(line, next)
        val triggerTime = next.timeStamp - advance
        // 最后两个字“歌词”在 14700ms 才开唱，切行触发点绝不能早于 14700ms
        assertTrue("Expected triggerTime >= 14700 but was $triggerTime", triggerTime >= 14_700L)
    }

    @Test
    fun `splitLongLyric splits on natural word pause boundary and tracks active half`() {
        val line = LyricLine(
            timeStamp = 20_000L,
            text = "走过漫长的黑夜才看见黎明的微光",
            endTime = 26_000L,
            words = listOf(
                LyricWord("走过", begin = 20_000L, end = 20_800L),
                LyricWord("漫长的", begin = 20_800L, end = 21_800L),
                LyricWord("黑夜", begin = 21_800L, end = 22_800L),
                // 此处有 400ms 换气停顿
                LyricWord("才看见", begin = 23_200L, end = 24_200L),
                LyricWord("黎明的", begin = 24_200L, end = 25_100L),
                LyricWord("微光", begin = 25_100L, end = 26_000L)
            )
        )

        val splitFirstHalf = FloatingLyricHelper.splitLongLyric(
            line = line,
            displayText = line.text,
            currentPositionMs = 22_500L
        )
        assertEquals("走过漫长的黑夜", splitFirstHalf.part1)
        assertEquals("才看见黎明的微光", splitFirstHalf.part2)
        assertEquals(23_200L, splitFirstHalf.part2StartMs)
        assertFalse(splitFirstHalf.isSecondPartActive)

        val splitSecondHalf = FloatingLyricHelper.splitLongLyric(
            line = line,
            displayText = line.text,
            currentPositionMs = 23_300L
        )
        assertTrue(splitSecondHalf.isSecondPartActive)
    }
}

