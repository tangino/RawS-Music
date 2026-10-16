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
     * 提取歌曲中的核心歌名（去除括号说明与影视剧副标题）
     */
    fun extractCoreTitle(title: String): String {
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
        return base.substringBefore(" - ").substringBefore(" — ").trim()
    }

    /**
     * 提取文本中的特殊版本标签
     */
    private fun extractTags(text: String): Set<String> {
        val lower = text.lowercase()
        return SPECIAL_TAGS.filter { lower.contains(it) }.toSet()
    }

    /**
     * 判断歌曲名称是否一致
     */
    fun isTitleMatched(targetTitle: String, candidateTitle: String): Boolean {
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

        // 3. 核心歌名比对
        val coreTarget = normalizeForCompare(extractCoreTitle(targetTitle))
        val coreCandidate = normalizeForCompare(extractCoreTitle(candidateTitle))
        if (coreTarget.isNotEmpty() && coreTarget == coreCandidate) {
            return true
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
     * 1. 歌名一致
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
        if (!isTitleMatched(targetTitle, candidateTitle)) {
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
     * 从本地曲库中查找最佳匹配的本地音源。
     *
     * @param targetSong 在线歌曲或待匹配歌曲
     * @param librarySongs 本地曲库中的全部歌曲
     * @param toleranceMs 允许的时长误差毫秒数，默认 5000ms
     * @return 匹配成功的本地歌曲；若无匹配则返回 null
     */
    fun findBestLocalMatch(
        targetSong: AudioFile,
        librarySongs: List<AudioFile>,
        toleranceMs: Long = 5000L,
    ): AudioFile? {
        if (librarySongs.isEmpty()) return null
        val targetTitle = targetSong.title
        val targetArtist = targetSong.artist
        val targetDuration = targetSong.duration

        var bestMatch: AudioFile? = null
        var minDurationDiff = Long.MAX_VALUE

        for (candidate in librarySongs) {
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
                val diff = if (targetDuration > 5000L && candidate.duration > 5000L) {
                    abs(targetDuration - candidate.duration)
                } else {
                    0L
                }
                if (diff < minDurationDiff) {
                    minDurationDiff = diff
                    bestMatch = candidate
                    if (diff == 0L) break
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
