package com.rawsmusic.module.online.playback

/**
 * 歌曲智能匹配与归一化（逐字移植自 OrBitPlayer 的 `OnlineAudioSourceManager.SongMatcher`）。
 *
 * 严格遵守三条匹配规则：
 * 1. 歌名一致（支持标点/全半角归一化、去掉副标题影视信息、区分 Live/伴奏/Remix 等特殊版本标签）
 * 2. 歌手一致（支持多歌手拆分与包含匹配）
 * 3. 时长误差不超过 5 秒（仅当双方都有有效时长时）
 *
 * ⚠️ 这里的阈值与标签表**都是从实际匹配效果反推出来的经验值**，改动会直接影响
 * 「能不能找到正确的那首歌」，不要凭直觉调整。
 */
object OnlinePlaybackMatcher {

    /**
     * 特殊版本标签。命中集合必须**完全相同**才算歌名匹配 ——
     * 这样原曲不会被匹配到 Live / 伴奏 / 试听片段。
     */
    private val SPECIAL_TAGS = listOf(
        "live", "伴奏", "instrumental", "inst", "remix", "cover", "翻唱", "dj", "纯音乐",
        "demo", "片段", "铃声", "ringtone", "慢摇", "变奏", "钢琴版", "吉他版", "八音盒",
        "试听", "试听版", "试听片段", "截取", "audition", "preview", "trial", "sample",
    )

    private val HTML_TAG_REGEX = Regex("<[^>]+>")

    /** 清掉 HTML 标签与常见实体，修剪首尾空白。 */
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

    /** 归一化为可比较形式：小写 + 全角括号转半角 + 去掉空白与常见标点。 */
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

    /** 提取核心歌名：去掉括号说明与影视剧副标题。 */
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

    /** 提取文本中命中的特殊版本标签。 */
    private fun extractTags(text: String): Set<String> {
        val lower = text.lowercase()
        return SPECIAL_TAGS.filter { lower.contains(it) }.toSet()
    }

    /** 歌名是否一致。 */
    fun isTitleMatched(targetTitle: String, candidateTitle: String): Boolean {
        val normTarget = normalizeForCompare(targetTitle)
        val normCandidate = normalizeForCompare(candidateTitle)
        if (normTarget.isEmpty() || normCandidate.isEmpty()) return false

        // 1. 完全一致
        if (normTarget == normCandidate) return true

        // 2. 特殊版本标签必须一致（原曲不该匹配到 Live 版）
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

    /** 拆分多歌手（支持 / \ , ，、 & | + 等分隔符）。 */
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

    /** 歌手是否一致。 */
    fun isArtistMatched(targetArtist: String, candidateArtist: String): Boolean {
        val normTarget = normalizeForCompare(targetArtist)
        val normCandidate = normalizeForCompare(candidateArtist)

        if (normTarget.isEmpty() || normCandidate.isEmpty()) return true
        if (normTarget == normCandidate) return true

        if (normTarget in listOf("未知歌手", "群星", "variousartists", "unknown") ||
            normCandidate in listOf("未知歌手", "群星", "variousartists", "unknown")
        ) {
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
     * 严格综合匹配判定：歌名一致 + 歌手一致 + 时长误差不超过 5 秒。
     */
    fun isMatched(
        targetTitle: String,
        targetArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        targetDurationMs: Long,
        candidateDurationMs: Long,
    ): Boolean {
        if (!isTitleMatched(targetTitle, candidateTitle)) {
            return false
        }

        if (!isArtistMatched(targetArtist, candidateArtist)) {
            return false
        }

        // 两者都有有效时长（>5 秒）时，时长必须与之前返回的严格一致（允许误差2秒左右，即 <= 2500ms）
        if (targetDurationMs > 5000L && candidateDurationMs > 5000L) {
            val diffMs = kotlin.math.abs(targetDurationMs - candidateDurationMs)
            if (diffMs > 2500L) {
                return false
            }
        }

        return true
    }

    /**
     * 匹配优选度打分，用于在多个候选中排序。
     * 满分 100：歌名 50 + 歌手 30 + 时长 20。
     */
    fun calculateMatchScore(
        targetTitle: String,
        targetArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        targetDurationMs: Long,
        candidateDurationMs: Long,
    ): Int {
        var score = 0
        val normTargetTitle = normalizeForCompare(targetTitle)
        val normCandidateTitle = normalizeForCompare(candidateTitle)
        if (normTargetTitle == normCandidateTitle) score += 50 else score += 30

        val normTargetArtist = normalizeForCompare(targetArtist)
        val normCandidateArtist = normalizeForCompare(candidateArtist)
        if (normTargetArtist == normCandidateArtist) score += 30 else score += 15

        if (targetDurationMs > 5000L && candidateDurationMs > 5000L) {
            val diffMs = kotlin.math.abs(targetDurationMs - candidateDurationMs)
            if (diffMs <= 1000L) score += 20
            else if (diffMs <= 2500L) score += 12
            else score -= 30
        } else {
            score += 10
        }
        return score
    }
}
