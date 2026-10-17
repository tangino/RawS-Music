package com.rawsmusic.core.common.utils

import com.rawsmusic.core.common.model.AudioFile
import kotlin.math.abs

/**
 * 歌曲智能匹配与归一化工具类
 *
 * 混合歌单的核心特征：当本地存在同名、同歌手且歌曲时长基本一致（允许小许误差）时，优先播放/使用本地音源。
 *
 * 匹配规则：
 * 1. 歌曲名称一致（去除括号、影视剧副标题等干扰，同时严格比对特殊版本标签如 Live/伴奏/Remix）
 * 2. 歌曲作者一致（支持多歌手拆分、包含匹配）
 * 3. 歌曲时长基本一致（两者均具备有效时长时，默认允许 5000ms 误差）
 */
object SongMatcher {

    private val SPECIAL_TAGS = listOf(
        "live", "伴奏", "instrumental", "inst", "remix", "cover", "翻唱", "dj", "纯音乐",
        "demo", "片段", "铃声", "ringtone", "慢摇", "变奏", "钢琴版", "吉他版", "八音盒",
        "试听", "试听版", "试听片段", "截取", "audition", "preview", "trial", "sample"
    )

    private val HTML_TAG_REGEX = Regex("<[^>]+>")

    fun cleanText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.replace(HTML_TAG_REGEX, "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#039;", "'")
            .trim()
    }

    fun normalizeForCompare(str: String): String {
        return cleanText(str)
            .lowercase()
            .replace("（", "(")
            .replace("）", ")")
            .replace("【", "[")
            .replace("】", "]")
            .replace("［", "[")
            .replace("］", "]")
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")
            .replace("·", "")
            .replace("•", "")
            .replace("'", "")
            .replace("\"", "")
            .replace(",", "")
            .replace("，", "")
            .replace(".", "")
            .replace("。", "")
    }

    /**
     * 提取歌曲中的核心歌名（去除括号说明、影视剧副标题以及连接符歌手后缀/前缀）
     */
    fun extractCoreTitle(title: String, artist: String? = null): String {
        val cleaned = cleanText(title)
            .replace("（", "(")
            .replace("）", ")")
            .replace("【", "(")
            .replace("】", ")")
            .replace("［", "(")
            .replace("］", ")")
            .replace("[", "(")
            .replace("]", ")")
        val withoutBracket = cleaned.replace(Regex("\\([^)]*\\)"), "").trim()
        val base = if (withoutBracket.isNotBlank()) withoutBracket else cleaned
        var result = base.substringBefore(" - ").substringBefore(" — ").substringBefore(" – ").trim()

        // 针对带连接符（如“离开我的依赖-王艳薇”或“王艳薇-离开我的依赖”）进行智能歌手剥离
        val normArtist = artist?.let { normalizeForCompare(it) }?.takeIf { it.isNotBlank() }
        val splitDelimiters = charArrayOf('-', '—', '–', '_', '~')
        if (result.any { it in splitDelimiters }) {
            val parts = result.split(*splitDelimiters).map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size == 2) {
                if (normArtist != null) {
                    val p0Norm = normalizeForCompare(parts[0])
                    val p1Norm = normalizeForCompare(parts[1])
                    if (p1Norm == normArtist || normArtist.contains(p1Norm) || p1Norm.contains(normArtist)) {
                        result = parts[0]
                    } else if (p0Norm == normArtist || normArtist.contains(p0Norm) || p0Norm.contains(normArtist)) {
                        result = parts[1]
                    }
                }
            }
        }
        return result.trim()
    }

    /**
     * 提取文本中的特殊版本标签
     */
    private fun extractTags(text: String): Set<String> {
        val lower = text.lowercase()
        return SPECIAL_TAGS.filter { lower.contains(it) }.toSet()
    }

    /**
     * 判断歌曲名称是否一致（支持辅助歌手名剔除）
     */
    fun isTitleMatched(
        targetTitle: String,
        candidateTitle: String,
        targetArtist: String? = null,
        candidateArtist: String? = null,
    ): Boolean {
        val normTarget = normalizeForCompare(targetTitle)
        val normCandidate = normalizeForCompare(candidateTitle)
        if (normTarget.isEmpty() || normCandidate.isEmpty()) return false

        // 1. 完全一致
        if (normTarget == normCandidate) return true

        // 2. 特殊版本标签必须一致（如原歌不是 Live 版，候选歌曲带 Live 则不匹配）
        val targetTags = extractTags(targetTitle)
        val candidateTags = extractTags(candidateTitle)
        if (targetTags != candidateTags) {
            return false
        }

        // 3. 核心歌名比对（带歌手辅助提取）
        val coreTarget = normalizeForCompare(extractCoreTitle(targetTitle, targetArtist ?: candidateArtist))
        val coreCandidate = normalizeForCompare(extractCoreTitle(candidateTitle, candidateArtist ?: targetArtist))
        if (coreTarget.isNotEmpty() && coreTarget == coreCandidate) {
            return true
        }

        // 4. 容错剥离：若其中一个标题包含另一个标题且多出部分正是歌手名（如“离开我的依赖王艳薇”与“离开我的依赖”）
        val possibleArtists = listOfNotNull(targetArtist, candidateArtist)
            .map { normalizeForCompare(it) }
            .filter { it.isNotBlank() && it !in listOf("未知歌手", "群星", "variousartists", "unknown") }

        for (art in possibleArtists) {
            val strippedTarget = normTarget.replace(art, "")
            val strippedCandidate = normCandidate.replace(art, "")
            if (strippedTarget.isNotEmpty() && strippedTarget == strippedCandidate) {
                return true
            }
            if (strippedTarget.isNotEmpty() && (strippedTarget == normCandidate || normTarget == strippedCandidate)) {
                return true
            }
        }

        return false
    }

    /**
     * 解析歌手列表（支持 / , 、 & 等多歌手分割）
     */
    fun splitArtists(artistStr: String): List<String> {
        val cleaned = cleanText(artistStr)
            .replace("（", "(")
            .replace("）", ")")
        val withoutBracket = cleaned.replace(Regex("\\([^)]*\\)"), "").trim()
        val base = if (withoutBracket.isNotBlank()) withoutBracket else cleaned

        return base.split('/', '\\', ',', '，', '、', '&', '|', '+')
            .map { normalizeForCompare(it) }
            .filter { it.isNotBlank() }
    }

    /**
     * 判断歌手名称是否一致
     */
    fun isArtistMatched(targetArtist: String, candidateArtist: String): Boolean {
        val normTarget = normalizeForCompare(targetArtist)
        val normCandidate = normalizeForCompare(candidateArtist)

        if (normTarget.isEmpty() || normCandidate.isEmpty()) return true
        if (normTarget == normCandidate) return true

        if (normTarget in listOf("未知歌手", "群星", "variousartists", "unknown") ||
            normCandidate in listOf("未知歌手", "群星", "variousartists", "unknown")) {
            return true
        }

        val targetList = splitArtists(targetArtist)
        val candidateList = splitArtists(candidateArtist)

        if (targetList.isEmpty() || candidateList.isEmpty()) {
            return normTarget.contains(normCandidate) || normCandidate.contains(normTarget)
        }

        val hasCommon = targetList.any { t ->
            candidateList.any { c ->
                t == c || t.contains(c) || c.contains(t)
            }
        }
        if (hasCommon) return true

        return normTarget.contains(normCandidate) || normCandidate.contains(normTarget)
    }

    /**
     * 严格综合匹配判定：
     * 1. 歌名一致（带歌手辅助提取与容错剥离）
     * 2. 作者一致
     * 3. 时间误差不超过 toleranceMs（默认 5000ms）
     */
    fun isMatched(
        targetTitle: String,
        targetArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        targetDurationMs: Long,
        candidateDurationMs: Long,
        toleranceMs: Long = 5000L,
    ): Boolean {
        // 1. 歌名判定
        if (!isTitleMatched(targetTitle, candidateTitle, targetArtist, candidateArtist)) {
            return false
        }

        // 2. 作者判定
        if (!isArtistMatched(targetArtist, candidateArtist)) {
            return false
        }

        // 3. 时长误差判定：当两者均有有效时长（>5秒）时，时间误差不得超过 toleranceMs
        if (targetDurationMs > 5000L && candidateDurationMs > 5000L) {
            val diffMs = abs(targetDurationMs - candidateDurationMs)
            if (diffMs > toleranceMs) {
                return false
            }
        }

        return true
    }

    /**
     * 计算匹配优选度得分（1:1 还原 OrBitPlayer 的打分算法，并加入无损音质加权）
     */
    fun calculateMatchScore(
        targetTitle: String,
        targetArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        targetDurationMs: Long,
        candidateDurationMs: Long,
        candidate: AudioFile? = null,
    ): Int {
        var score = 0
        val normTargetTitle = normalizeForCompare(targetTitle)
        val normCandidateTitle = normalizeForCompare(candidateTitle)
        if (normTargetTitle == normCandidateTitle) {
            score += 50
        } else {
            score += 35
        }

        val normTargetArtist = normalizeForCompare(targetArtist)
        val normCandidateArtist = normalizeForCompare(candidateArtist)
        if (normTargetArtist == normCandidateArtist) {
            score += 30
        } else if (normTargetArtist.contains(normCandidateArtist) || normCandidateArtist.contains(normTargetArtist)) {
            score += 20
        } else {
            score += 10
        }

        if (targetDurationMs > 5000L && candidateDurationMs > 5000L) {
            val diffMs = abs(targetDurationMs - candidateDurationMs)
            when {
                diffMs <= 1000L -> score += 20
                diffMs <= 3000L -> score += 15
                diffMs <= 5000L -> score += 10
                diffMs <= 10000L -> score += 5
            }
        } else {
            score += 10
        }

        // 音质加权：同等匹配度下优先匹配本地无损/高解析音源
        if (candidate != null) {
            val formatUpper = candidate.format.uppercase()
            when {
                formatUpper.contains("DSD") || formatUpper.contains("DSF") || formatUpper.contains("DFF") || candidate.isDsdFormat -> score += 15
                formatUpper.contains("FLAC") || formatUpper.contains("ALAC") || formatUpper.contains("APE") || formatUpper.contains("WAV") -> score += 10
                formatUpper.contains("AAC") || formatUpper.contains("M4A") -> score += 5
                else -> {}
            }
            if (candidate.bitsPerSample >= 24 || candidate.sampleRate >= 96000) {
                score += 5
            }
        }

        return score
    }

    /**
     * 本地歌曲检索索引：按歌名与核心歌名归类，用于 O(1) 极速定位匹配候选集，
     * 避免 O(N) 对全库歌曲执行全量正则与字符串计算。
     */
    class LocalSongIndex(val songs: List<AudioFile>) {
        private val byCleanTitle = HashMap<String, MutableList<AudioFile>>()
        private val byCoreTitle = HashMap<String, MutableList<AudioFile>>()

        init {
            for (song in songs) {
                if (song.path.startsWith("online://")) continue
                val clean = normalizeForCompare(song.title)
                if (clean.isNotEmpty()) {
                    byCleanTitle.getOrPut(clean) { mutableListOf() }.add(song)
                }
                val core = normalizeForCompare(extractCoreTitle(song.title, song.artist))
                if (core.isNotEmpty() && core != clean) {
                    byCoreTitle.getOrPut(core) { mutableListOf() }.add(song)
                }
                val parts = song.title.split('-', '—', '–', '_', '~')
                    .map { normalizeForCompare(it.trim()) }
                    .filter { it.isNotBlank() }
                for (p in parts) {
                    if (p != clean && p != core) {
                        byCoreTitle.getOrPut(p) { mutableListOf() }.add(song)
                    }
                }
            }
        }

        fun getCandidates(targetTitle: String, targetArtist: String? = null): List<AudioFile> {
            val clean = normalizeForCompare(targetTitle)
            val core = normalizeForCompare(extractCoreTitle(targetTitle, targetArtist))
            val result = LinkedHashSet<AudioFile>()
            if (clean.isNotEmpty()) {
                byCleanTitle[clean]?.let { result.addAll(it) }
                byCoreTitle[clean]?.let { result.addAll(it) }
            }
            if (core.isNotEmpty()) {
                byCleanTitle[core]?.let { result.addAll(it) }
                byCoreTitle[core]?.let { result.addAll(it) }
            }
            val parts = targetTitle.split('-', '—', '–', '_', '~')
                .map { normalizeForCompare(it.trim()) }
                .filter { it.isNotBlank() }
            for (p in parts) {
                byCleanTitle[p]?.let { result.addAll(it) }
                byCoreTitle[p]?.let { result.addAll(it) }
            }
            return if (result.isNotEmpty()) result.toList() else emptyList()
        }
    }

    fun buildIndex(librarySongs: List<AudioFile>): LocalSongIndex = LocalSongIndex(librarySongs)

    /**
     * 从本地曲库中查找最佳匹配的本地音源（加权打分 + 音质优选）。
     *
     * @param targetSong 在线歌曲或待匹配歌曲
     * @param librarySongs 本地曲库中的全部歌曲
     * @param index 可选的本地曲库快速索引，用于批量解析时提升性能
     * @param toleranceMs 允许的时长误差毫秒数，默认 5000ms
     * @return 匹配成功的最佳本地歌曲；若无匹配则返回 null
     */
    fun findBestLocalMatch(
        targetSong: AudioFile,
        librarySongs: List<AudioFile>,
        index: LocalSongIndex? = null,
        toleranceMs: Long = 5000L,
    ): AudioFile? {
        if (librarySongs.isEmpty()) return null
        val targetTitle = targetSong.title
        val targetArtist = targetSong.artist
        val targetDuration = targetSong.duration

        // 优先使用索引获取高概率候选集（通常仅 1~3 首）；若未提供索引且曲库很大则快速过滤
        val candidates = index?.getCandidates(targetTitle, targetArtist) ?: run {
            if (librarySongs.size > 100) {
                val cleanTarget = normalizeForCompare(targetTitle)
                val coreTarget = normalizeForCompare(extractCoreTitle(targetTitle, targetArtist))
                librarySongs.filter { candidate ->
                    if (candidate.path.startsWith("online://")) false
                    else {
                        val cleanCand = normalizeForCompare(candidate.title)
                        cleanCand == cleanTarget || cleanCand == coreTarget ||
                            cleanCand.contains(coreTarget) || coreTarget.contains(cleanCand)
                    }
                }
            } else {
                librarySongs
            }
        }

        if (candidates.isEmpty()) return null

        var bestMatch: AudioFile? = null
        var highestScore = -1
        var minDurationDiff = Long.MAX_VALUE

        for (candidate in candidates) {
            // 跳过在线虚拟歌曲
            if (candidate.path.startsWith("online://")) continue

            if (isMatched(
                    targetTitle = targetTitle,
                    targetArtist = targetArtist,
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.artist,
                    targetDurationMs = targetDuration,
                    candidateDurationMs = candidate.duration,
                    toleranceMs = toleranceMs,
                )
            ) {
                val score = calculateMatchScore(
                    targetTitle = targetTitle,
                    targetArtist = targetArtist,
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.artist,
                    targetDurationMs = targetDuration,
                    candidateDurationMs = candidate.duration,
                    candidate = candidate,
                )
                val diff = if (targetDuration > 5000L && candidate.duration > 5000L) {
                    abs(targetDuration - candidate.duration)
                } else {
                    0L
                }
                if (score > highestScore || (score == highestScore && diff < minDurationDiff)) {
                    highestScore = score
                    minDurationDiff = diff
                    bestMatch = candidate
                }
            }
        }

        // 宽松降级探活：当严格误差（如 5s）未能命中时，对歌名与作者完全相符的本地音频允许最多 20s 误差（兼容不同母带或前导静音）
        if (bestMatch == null && toleranceMs < 20000L) {
            for (candidate in candidates) {
                if (candidate.path.startsWith("online://")) continue
                if (isMatched(
                        targetTitle = targetTitle,
                        targetArtist = targetArtist,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        targetDurationMs = targetDuration,
                        candidateDurationMs = candidate.duration,
                        toleranceMs = 20000L,
                    )
                ) {
                    val score = calculateMatchScore(
                        targetTitle = targetTitle,
                        targetArtist = targetArtist,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        targetDurationMs = targetDuration,
                        candidateDurationMs = candidate.duration,
                        candidate = candidate,
                    )
                    val diff = if (targetDuration > 5000L && candidate.duration > 5000L) {
                        abs(targetDuration - candidate.duration)
                    } else {
                        0L
                    }
                    if (score > highestScore || (score == highestScore && diff < minDurationDiff)) {
                        highestScore = score
                        minDurationDiff = diff
                        bestMatch = candidate
                    }
                }
            }
        }

        if (bestMatch != null) {
            // 若本地音频没有网络封面且待匹配歌曲有网络封面，保留在线高质封面
            val onlineArt = targetSong.albumArtPath.trim()
            if ((onlineArt.startsWith("http://", true) || onlineArt.startsWith("https://", true)) &&
                !bestMatch.albumArtPath.startsWith("http://", true) &&
                !bestMatch.albumArtPath.startsWith("https://", true)
            ) {
                return bestMatch.copy(albumArtPath = onlineArt)
            }
        }

        return bestMatch
    }
}
