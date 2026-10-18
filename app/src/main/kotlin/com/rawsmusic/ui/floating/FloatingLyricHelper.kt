package com.rawsmusic.ui.floating

import com.rawsmusic.core.common.model.LyricLine

/**
 * 悬浮窗歌词智能排版与平滑滚动调度助手：
 * 1. 自适应提前滚动：在当前行快唱完（剩余约 2 个字）时提前触发向上平滑滚动；
 * 2. 人声演唱结束感知：当人声唱完而音乐伴奏仍在继续（间奏/Solo/尾奏）时，
 *    及时向上滚动展示“♪ 间奏 ♪”或“♪ 尾奏 ♪”，消除歌词死滞在屏幕上的问题。
 */
internal object FloatingLyricHelper {

    /** 间奏展示的最小停顿门槛：人声唱完后距离下一句至少还有 3.2 秒才触发间奏滚动 */
    const val MIN_INTERLUDE_GAP_MS = 3200L

    data class LyricDisplayState(
        val text1: String,
        val text2: String,
        val isInstrumental: Boolean,
        val stateKey: String
    )

    /**
     * 根据当前行文本智能估算其人声演唱持续时间 (毫秒)。
     * 结合平均单字吐字速度与唱腔尾音留白，保底 2.0s，动态上限最高 9.0s。
     */
    fun estimateVocalDurationMs(text: String, maxAllowedDurationMs: Long = 9000L): Long {
        val clean = text.trim()
        if (clean.isEmpty()) return 2000L
        val charCount = clean.count { !it.isWhitespace() }
        val raw = charCount * 380L + 1200L
        val maxLimit = maxAllowedDurationMs.coerceAtLeast(2000L)
        return raw.coerceIn(2000L, maxLimit.coerceAtMost(9000L))
    }

    /**
     * 计算从当前行切换到下一行时的提前量 (毫秒)。
     * 根据当前行演唱总耗时与字符数，自适应估算最后 2 个字的耗时，
     * 平滑钳制在 [450ms, 850ms] 黄金舒适区间。
     */
    fun calculateAdvanceMs(currentLine: LyricLine, nextLine: LyricLine): Long {
        val duration = nextLine.timeStamp - currentLine.timeStamp
        if (duration <= 0) return 600L
        val text = currentLine.text.trim()
        val charCount = text.count { !it.isWhitespace() }
        return if (duration in 1000L..8000L && charCount > 0) {
            val timePerChar = duration.toFloat() / charCount
            (timePerChar * 2.0f).toLong().coerceIn(450L, 850L)
        } else {
            650L
        }
    }

    /**
     * 辅助索引查找：根据每句尾部提前量获取当前唱的歌词行索引
     */
    fun findActiveLyricIndex(lines: List<LyricLine>, currentPositionMs: Long): Int {
        if (lines.isEmpty()) return -1
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            val triggerTime = if (i == 0) {
                (line.timeStamp - 600L).coerceAtLeast(0L)
            } else {
                val prevLine = lines[i - 1]
                val advance = calculateAdvanceMs(prevLine, line)
                (line.timeStamp - advance).coerceAtLeast(0L)
            }
            if (currentPositionMs >= triggerTime) {
                return i
            }
        }
        return -1
    }

    /**
     * 根据当前播放时间戳，智能计算当前应呈现的字幕内容与滚动状态键。
     * 当 stateKey 发生变化时，UI 层即触发一次垂直平滑向上过渡动效。
     */
    fun resolveLyricState(lines: List<LyricLine>, currentPositionMs: Long): LyricDisplayState {
        if (lines.isEmpty()) {
            return LyricDisplayState("♪ 享受音乐旋律 ♪", "", false, "empty")
        }

        val firstLine = lines.first()
        // 1. 前奏阶段：距离第一句开唱还早 (提前 600ms 入场)
        if (currentPositionMs < firstLine.timeStamp - 600L) {
            return LyricDisplayState("♪ 前奏 ♪", firstLine.text.trim(), true, "intro")
        }

        val index = findActiveLyricIndex(lines, currentPositionMs)
        if (index < 0) {
            return LyricDisplayState("♪ 享受音乐旋律 ♪", "", false, "none")
        }

        val curLine = lines[index]
        val curText = curLine.text.trim().ifBlank { "♪ ♪ ♪" }
        val hasNext = index + 1 < lines.size
        val nextLine = if (hasNext) lines[index + 1] else null
        val nextText = nextLine?.text?.trim() ?: ""

        val maxAllowed = if (hasNext) {
            (nextLine!!.timeStamp - curLine.timeStamp - 1000L).coerceAtLeast(2000L)
        } else {
            9000L
        }
        val vocalDuration = estimateVocalDurationMs(curText, maxAllowed)
        val vocalEndTime = curLine.timeStamp + vocalDuration
        // 人声唱完后给 800ms 的从容阅读时间
        val vocalFinishTime = vocalEndTime + 800L

        // 2. 尾奏阶段：最后一句歌词人声已经唱完，但音乐伴奏仍在继续
        if (!hasNext) {
            if (currentPositionMs >= vocalFinishTime) {
                return LyricDisplayState("♪ 尾奏 ♪", "", true, "outro")
            }
            return LyricDisplayState(curText, "", false, "line_$index")
        }

        // 3. 间奏阶段判定：人声唱完后，距离下一句歌词开唱前至少还有足够长的伴奏时间
        val nextAdvance = calculateAdvanceMs(curLine, nextLine!!)
        val nextTriggerTime = nextLine.timeStamp - nextAdvance
        val interludeRemain = nextTriggerTime - vocalFinishTime

        if (interludeRemain >= MIN_INTERLUDE_GAP_MS) {
            if (currentPositionMs in vocalFinishTime until nextTriggerTime) {
                // 人声唱完，字幕向上滚动为间奏展示，并预告下一句
                return LyricDisplayState("♪ 间奏 ♪", nextText, true, "interlude_$index")
            }
        }

        // 4. 正常正在演唱当前歌词
        return LyricDisplayState(curText, nextText, false, "line_$index")
    }
}
