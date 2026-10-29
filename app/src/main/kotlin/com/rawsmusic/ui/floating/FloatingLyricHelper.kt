package com.rawsmusic.ui.floating

import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import kotlin.math.abs

/**
 * 悬浮窗歌词智能排版与平滑滚动调度助手：
 * 1. 深度接入 RawSLyricsParser 解析出的真实逐字时间轴 (words / backgroundWords) 与真实行结束时间 (endTime)；
 * 2. 自适应提前滚动：优先依据逐字时间轴最后 2 字的真实起止时间触发切行，并保证绝不在倒数第 2 字开唱前截断当前句；
 * 3. 人声演唱结束感知：优先按逐字最末字 end 或非估算 endTime 精确判定人声收尾，及时展示“♪ 间奏 ♪”或“♪ 尾奏 ♪”；
 * 4. 逐字智能长句断句：结合标点、分词边界与字间换气停顿切分长句，并精确追踪上下半句的实时演唱焦点。
 */
internal object FloatingLyricHelper {

    /** 估算模式下：间奏展示的最小停顿门槛（人声唱完后距离下一句至少还有 3.2 秒） */
    const val MIN_INTERLUDE_GAP_MS = 3200L

    /** 精确时间轴模式（含 words 或真实 endTime）下：间奏展示的最小停顿门槛 */
    const val EXACT_MIN_INTERLUDE_GAP_MS = 2600L

    /** 精确时间轴模式下：人声收尾后的余韵阅读留白 */
    private const val EXACT_VOCAL_FINISH_GRACE_MS = 380L

    /** 估算时间轴模式下：人声收尾后的余韵阅读留白 */
    private const val ESTIMATED_VOCAL_FINISH_GRACE_MS = 800L

    /** 行内相对逐字时间轴判定的容差上限 */
    private const val RELATIVE_WORD_TIMELINE_ALLOWANCE_MS = 2000L

    private val SPLIT_DELIMITERS = charArrayOf(
        ' ', '　', ',', '，', '、', '/', '-', '—', ';', '；', '!', '！', '?', '？', ':', '：'
    )

    data class LyricDisplayState(
        val text1: String,
        val text2: String,
        val isInstrumental: Boolean,
        val stateKey: String,
        val activeLine: LyricLine? = null,
        val nextLine: LyricLine? = null
    )

    data class VocalTiming(
        val endTimeMs: Long,
        val isExact: Boolean
    )

    data class LongLyricSplit(
        val part1: String,
        val part2: String,
        val part2StartMs: Long,
        val isSecondPartActive: Boolean
    )

    /**
     * 将歌词行的逐字时间轴归一化为绝对毫秒时间。
     * 兼容部分 QRC / Syllable 歌词将 word.begin / word.end 存储为相对于 line.timeStamp 的偏移量。
     */
    fun resolveAbsoluteWords(line: LyricLine, nextLine: LyricLine? = null): List<LyricWord> {
        val rawWords = line.words.ifEmpty { line.backgroundWords }
        val validWords = rawWords.filter { it.text.isNotEmpty() && it.begin >= 0L && it.end >= it.begin }
        if (validWords.isEmpty()) return emptyList()

        val baseMs = line.timeStamp.coerceAtLeast(0L)
        val fallbackEndMs = when {
            line.endTime > baseMs -> line.endTime
            nextLine != null && nextLine.timeStamp > baseMs -> nextLine.timeStamp
            else -> baseMs + 5000L
        }
        val lineDurationMs = (fallbackEndMs - baseMs).coerceAtLeast(1L)
        val firstBegin = validWords.minOf { it.begin }
        val lastEnd = validWords.maxOf { maxOf(it.begin, it.end) }

        val looksLineRelative = baseMs > 0L &&
            firstBegin < baseMs &&
            firstBegin <= lineDurationMs + RELATIVE_WORD_TIMELINE_ALLOWANCE_MS &&
            lastEnd <= lineDurationMs + RELATIVE_WORD_TIMELINE_ALLOWANCE_MS

        if (!looksLineRelative) return validWords

        return validWords.map { word ->
            val absBegin = baseMs + word.begin
            val duration = when {
                word.end > word.begin -> word.end - word.begin
                word.duration > 0L -> word.duration
                else -> 1L
            }
            word.copy(
                begin = absBegin,
                end = absBegin + duration,
                duration = duration
            )
        }
    }

    /**
     * 解析歌词行的真实起唱时间：兼顾行时间戳与首字时间戳。
     */
    fun resolveEffectiveStartMs(line: LyricLine, nextLine: LyricLine? = null): Long {
        val words = resolveAbsoluteWords(line, nextLine)
        val firstWordBegin = words.firstOrNull()?.begin ?: return line.timeStamp
        return if (firstWordBegin in (line.timeStamp - 300L)..(line.timeStamp + 1500L)) {
            firstWordBegin
        } else {
            line.timeStamp
        }
    }

    /**
     * 解析当前行的人声结束时间及是否为精确时间轴：
     * 1. 优先取逐字时间轴 (words / backgroundWords) 的最末字结束时间；
     * 2. 其次取非估算的真实行结束时间 (line.endTime > line.timeStamp && !line.endTimeIsEstimated)；
     * 3. 兜底按字符数智能估算。
     */
    fun resolveVocalTiming(line: LyricLine, nextLine: LyricLine? = null): VocalTiming {
        val nextStartMs = nextLine?.timeStamp?.takeIf { it > line.timeStamp }
        val words = resolveAbsoluteWords(line, nextLine)
        if (words.isNotEmpty()) {
            var wordEndMs = words.maxOf { maxOf(it.begin, it.end) }
            val bgEndMs = line.backgroundEndTime?.takeIf { it > wordEndMs }
                ?: line.backgroundWords.maxOfOrNull { maxOf(it.begin, it.end) }?.takeIf { it > wordEndMs }
            if (bgEndMs != null && (nextStartMs == null || bgEndMs <= nextStartMs)) {
                wordEndMs = bgEndMs
            }
            if (nextStartMs != null) {
                wordEndMs = wordEndMs.coerceAtMost(nextStartMs)
            }
            if (wordEndMs > line.timeStamp) {
                return VocalTiming(endTimeMs = wordEndMs, isExact = true)
            }
        }

        if (!line.endTimeIsEstimated && line.endTime > line.timeStamp) {
            val boundedEnd = if (nextStartMs != null) {
                line.endTime.coerceAtMost(nextStartMs)
            } else {
                line.endTime
            }
            if (boundedEnd > line.timeStamp) {
                return VocalTiming(endTimeMs = boundedEnd, isExact = true)
            }
        }

        val maxAllowed = if (nextStartMs != null) {
            (nextStartMs - line.timeStamp - 1000L).coerceAtLeast(2000L)
        } else {
            9000L
        }
        val estimatedDuration = estimateVocalDurationMs(line.text, maxAllowed)
        val rawEnd = line.timeStamp + estimatedDuration
        val boundedEnd = when {
            nextStartMs != null -> rawEnd.coerceAtMost(nextStartMs)
            line.endTime > line.timeStamp -> rawEnd.coerceAtMost(line.endTime)
            else -> rawEnd
        }
        return VocalTiming(endTimeMs = boundedEnd.coerceAtLeast(line.timeStamp), isExact = false)
    }

    /**
     * 从逐字时间轴末尾向前查找最后 [tailCharTarget] 个非空白字符的起始时间戳。
     * 若整句包含多个分词单元，始终保留首个分词不被计入尾部提前区，防止短句过早切行。
     */
    fun resolveTailCharsStartMs(
        line: LyricLine,
        nextLine: LyricLine? = null,
        tailCharTarget: Int = 2
    ): Long? {
        val words = resolveAbsoluteWords(line, nextLine)
        if (words.isEmpty()) return null
        if (words.size == 1) {
            val w = words.first()
            val dur = (w.end - w.begin).coerceAtLeast(0L)
            return (w.end - (dur * 0.35f).toLong()).coerceAtLeast(line.timeStamp)
        }

        var accumulatedChars = 0
        var tailStartMs = words.last().begin
        for (i in words.lastIndex downTo 1) {
            val word = words[i]
            val nonBlankCount = word.text.count { !it.isWhitespace() }
            if (nonBlankCount == 0) continue
            tailStartMs = word.begin
            accumulatedChars += nonBlankCount
            if (accumulatedChars >= tailCharTarget) break
        }
        return tailStartMs.coerceAtLeast(line.timeStamp)
    }

    /**
     * 根据当前行文本智能估算其人声演唱持续时间 (毫秒)。
     * 仅在既无逐字时间轴、又无真实行 endTime 时作为兜底。
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
     * 计算从当前行切换到下一行时的提前量 (毫秒)：
     * 1. 若有 words 逐字时间轴：根据最后 2 个字的真实耗时与句间留白计算，且保证切行点不早于倒数第 2 字开唱；
     * 2. 若有真实 endTime (!endTimeIsEstimated)：按真实人声时长 (endTime - timeStamp) 计算单字耗时，排除句间伴奏干扰；
     * 3. 否则回退到按两行时间差与字符数的平滑估算。
     */
    fun calculateAdvanceMs(currentLine: LyricLine, nextLine: LyricLine): Long {
        val totalSpan = nextLine.timeStamp - currentLine.timeStamp
        if (totalSpan <= 0L) return 600L

        val words = resolveAbsoluteWords(currentLine, nextLine)
        if (words.isNotEmpty()) {
            val vocalEnd = resolveVocalTiming(currentLine, nextLine).endTimeMs
            val tailStart = resolveTailCharsStartMs(currentLine, nextLine, tailCharTarget = 2)
                ?.coerceAtMost(vocalEnd)
                ?: vocalEnd
            val tailVocalDuration = (vocalEnd - tailStart).coerceIn(240L, 850L)
            val silentGap = (nextLine.timeStamp - vocalEnd).coerceAtLeast(0L)
            val maxAllowedAdvance = (nextLine.timeStamp - tailStart).coerceAtLeast(220L)

            val desiredAdvance = if (silentGap > 0L) {
                (silentGap.coerceAtMost(500L) + (tailVocalDuration * 0.45f).toLong())
                    .coerceIn(320L, 850L)
            } else {
                tailVocalDuration.coerceIn(280L, 780L)
            }
            return desiredAdvance.coerceAtMost(maxAllowedAdvance).coerceIn(220L, 850L)
        }

        val text = currentLine.text.trim()
        val charCount = text.count { !it.isWhitespace() }

        if (!currentLine.endTimeIsEstimated && currentLine.endTime > currentLine.timeStamp) {
            val realEnd = currentLine.endTime.coerceAtMost(nextLine.timeStamp)
            val vocalDuration = (realEnd - currentLine.timeStamp).coerceAtLeast(1L)
            val silentGap = (nextLine.timeStamp - realEnd).coerceAtLeast(0L)
            val tailDuration = if (charCount > 0) {
                ((vocalDuration.toFloat() / charCount) * 2.0f).toLong().coerceIn(280L, 800L)
            } else {
                500L
            }
            val earliestSwitchTime = currentLine.timeStamp + (vocalDuration * 0.6f).toLong()
            val maxAllowedAdvance = (nextLine.timeStamp - earliestSwitchTime).coerceAtLeast(250L)
            val desiredAdvance = if (silentGap > 0L) {
                (silentGap.coerceAtMost(500L) + (tailDuration * 0.45f).toLong()).coerceIn(320L, 850L)
            } else {
                tailDuration.coerceIn(300L, 800L)
            }
            return desiredAdvance.coerceAtMost(maxAllowedAdvance).coerceIn(250L, 850L)
        }

        return if (totalSpan in 1000L..8000L && charCount > 0) {
            val timePerChar = totalSpan.toFloat() / charCount
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
                val firstStart = resolveEffectiveStartMs(line, lines.getOrNull(1))
                (minOf(line.timeStamp, firstStart) - 600L).coerceAtLeast(0L)
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
        val firstStartMs = minOf(
            firstLine.timeStamp,
            resolveEffectiveStartMs(firstLine, lines.getOrNull(1))
        )
        // 1. 前奏阶段：距离第一句开唱还早 (提前 600ms 入场)
        if (currentPositionMs < firstStartMs - 600L) {
            return LyricDisplayState("♪ 前奏 ♪", firstLine.text.trim(), true, "intro", nextLine = firstLine)
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

        val vocalTiming = resolveVocalTiming(curLine, nextLine)
        val vocalEndTime = vocalTiming.endTimeMs
        val finishGraceMs = if (vocalTiming.isExact) {
            EXACT_VOCAL_FINISH_GRACE_MS
        } else {
            ESTIMATED_VOCAL_FINISH_GRACE_MS
        }
        val vocalFinishTime = vocalEndTime + finishGraceMs

        // 2. 尾奏阶段：最后一句歌词人声已经唱完，但音乐伴奏仍在继续
        if (!hasNext) {
            if (currentPositionMs >= vocalFinishTime) {
                return LyricDisplayState("♪ 尾奏 ♪", "", true, "outro")
            }
            return LyricDisplayState(curText, "", false, "line_$index", activeLine = curLine)
        }

        // 3. 间奏阶段判定：人声唱完后，距离下一句歌词开唱前至少还有足够长的伴奏时间
        val nextAdvance = calculateAdvanceMs(curLine, nextLine!!)
        val nextTriggerTime = nextLine.timeStamp - nextAdvance
        val interludeRemain = nextTriggerTime - vocalFinishTime
        val minInterludeGap = if (vocalTiming.isExact) {
            EXACT_MIN_INTERLUDE_GAP_MS
        } else {
            MIN_INTERLUDE_GAP_MS
        }

        if (interludeRemain >= minInterludeGap) {
            if (currentPositionMs in vocalFinishTime until nextTriggerTime) {
                // 人声唱完，字幕向上滚动为间奏展示，并预告下一句
                return LyricDisplayState(
                    text1 = "♪ 间奏 ♪",
                    text2 = nextText,
                    isInstrumental = true,
                    stateKey = "interlude_$index",
                    nextLine = nextLine
                )
            }
        }

        // 4. 正常正在演唱当前歌词
        return LyricDisplayState(
            text1 = curText,
            text2 = nextText,
            isInstrumental = false,
            stateKey = "line_$index",
            activeLine = curLine,
            nextLine = nextLine
        )
    }

    /**
     * 针对悬浮窗超宽单行歌词进行智能双行切分，并计算下半句的开唱时间与当前激活状态：
     * 1. 优先在句中附近的标点/空格处切分；
     * 2. 若无标点但有 words 逐字时间轴，优先在分词边界且伴随停顿/长音处切分，绝不劈开完整单词；
     * 3. 结合 words 或真实 endTime 精确算出下半句 part2 的开唱时间戳，驱动上下半句高亮切换。
     */
    fun splitLongLyric(
        line: LyricLine?,
        displayText: String = line?.text?.trim().orEmpty(),
        currentPositionMs: Long = 0L,
        nextLine: LyricLine? = null
    ): LongLyricSplit {
        val text = displayText.trim()
        if (text.length < 2) {
            val start = line?.timeStamp ?: 0L
            return LongLyricSplit(text, "", start, false)
        }

        val length = text.length
        val half = length / 2
        val maxOffset = (length * 0.35f).toInt().coerceAtLeast(1)

        // 1. 尝试寻找靠近中点的标点或空格分隔符
        var delimiterIndex = -1
        var minDiff = Int.MAX_VALUE
        for (i in 1 until length - 1) {
            if (text[i] in SPLIT_DELIMITERS) {
                val diff = abs(i - half)
                if (diff < minDiff && diff <= maxOffset) {
                    minDiff = diff
                    delimiterIndex = i
                }
            }
        }

        val words = if (line != null) resolveAbsoluteWords(line, nextLine) else emptyList()
        val wordSpans = if (words.size >= 2) alignWordsToText(text, words) else emptyList()

        val part1: String
        val part2: String
        val splitWordStartMs: Long?

        if (delimiterIndex > 0) {
            val p1 = text.substring(0, delimiterIndex).trim()
            val p2 = text.substring(delimiterIndex + 1).trim()
            if (p1.isNotEmpty() && p2.isNotEmpty()) {
                part1 = p1
                part2 = p2
                splitWordStartMs = wordSpans.firstOrNull { it.start >= delimiterIndex }?.word?.begin
                    ?: wordSpans.lastOrNull { it.end <= delimiterIndex }?.word?.end
            } else {
                part1 = text.substring(0, half).trim()
                part2 = text.substring(half).trim()
                splitWordStartMs = null
            }
        } else if (wordSpans.size >= 2) {
            // 2. 无标点时，利用逐字时间轴的分词边界与呼吸停顿寻找最自然的断句点
            val minCharIdx = (length * 0.25f).toInt().coerceAtLeast(1)
            val maxCharIdx = (length * 0.75f).toInt().coerceAtMost(length - 1)
            var bestBoundaryIdx = -1
            var bestScore = Long.MIN_VALUE

            for (i in 0 until wordSpans.lastIndex) {
                val curSpan = wordSpans[i]
                val nextSpan = wordSpans[i + 1]
                val boundaryChar = nextSpan.start
                if (boundaryChar !in minCharIdx..maxCharIdx) continue

                val distancePenalty = abs(boundaryChar - half) * 140L
                val gapReward = (nextSpan.word.begin - curSpan.word.end).coerceIn(0L, 1200L) * 2L
                val holdReward = (curSpan.word.end - curSpan.word.begin).coerceIn(0L, 1000L) / 2L
                val score = gapReward + holdReward - distancePenalty
                if (score > bestScore) {
                    bestScore = score
                    bestBoundaryIdx = i
                }
            }

            if (bestBoundaryIdx >= 0) {
                val nextSpan = wordSpans[bestBoundaryIdx + 1]
                val cut = nextSpan.start.coerceIn(1, length - 1)
                val p1 = text.substring(0, cut).trim()
                val p2 = text.substring(cut).trim()
                if (p1.isNotEmpty() && p2.isNotEmpty()) {
                    part1 = p1
                    part2 = p2
                    splitWordStartMs = nextSpan.word.begin
                } else {
                    part1 = text.substring(0, half).trim()
                    part2 = text.substring(half).trim()
                    splitWordStartMs = null
                }
            } else {
                part1 = text.substring(0, half).trim()
                part2 = text.substring(half).trim()
                splitWordStartMs = null
            }
        } else {
            part1 = text.substring(0, half).trim()
            part2 = text.substring(half).trim()
            splitWordStartMs = null
        }

        val part2StartMs = when {
            splitWordStartMs != null -> splitWordStartMs
            line != null -> {
                val vocalEnd = resolveVocalTiming(line, nextLine).endTimeMs
                val duration = (vocalEnd - line.timeStamp).coerceAtLeast(1L)
                val p1Chars = part1.count { !it.isWhitespace() }.coerceAtLeast(1)
                val totalChars = (p1Chars + part2.count { !it.isWhitespace() }).coerceAtLeast(1)
                line.timeStamp + (duration * p1Chars / totalChars)
            }
            else -> Long.MAX_VALUE
        }

        return LongLyricSplit(
            part1 = part1,
            part2 = part2,
            part2StartMs = part2StartMs,
            isSecondPartActive = line != null && currentPositionMs >= part2StartMs
        )
    }

    private data class AlignedWordSpan(
        val start: Int,
        val end: Int,
        val word: LyricWord
    )

    private fun alignWordsToText(text: String, words: List<LyricWord>): List<AlignedWordSpan> {
        val result = ArrayList<AlignedWordSpan>(words.size)
        var cursor = 0
        for (word in words) {
            val token = word.text.trim()
            if (token.isEmpty()) continue
            val idx = text.indexOf(token, cursor)
            if (idx < 0) continue
            val end = idx + token.length
            result.add(AlignedWordSpan(idx, end, word))
            cursor = end
        }
        return result
    }
}
