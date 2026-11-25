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
     *
     * 注意：Android ICU 正则中汉字与数字均属 `\w`，若用 `\blive\b` 会漏掉 `(live版)`、`(2023live)`、`歌曲名live`，
     * 因此英文词统一使用 ASCII 字母边界 `(?<![a-zA-Z])...(?![a-zA-Z])`。
     */
    private val LIVE_TAG_REGEX = Regex("""(?i)((?<![a-zA-Z])(live|unplugged)(?![a-zA-Z])|现场版?|演唱会|音乐节|音乐会现场|巡演现场)""")

    private val ALBUM_LIVE_REGEX = Regex(
        """(?i)((?<![a-zA-Z])(live|concert|unplugged|livehouse)(?![a-zA-Z])|演唱会|音乐节|现场版|现场实况|音乐会现场|巡演|巡回演出)"""
    )

    private val VERSION_TAG_GROUPS: List<Pair<String, Regex>> = listOf(
        "live" to LIVE_TAG_REGEX,
        "inst" to Regex("""(?i)(伴奏|纯音乐|(?<![a-zA-Z])(instrumental|off\s*vocal|inst\.?|karaoke)(?![a-zA-Z])|消音版|无人声|卡拉ok)"""),
        "remix" to Regex("""(?i)((?<![a-zA-Z])(remix|dj|mashup|bootleg)(?![a-zA-Z])|慢摇|串烧|蹦迪|电音版|舞曲版)"""),
        "cover" to Regex("""(?i)(翻唱|(?<![a-zA-Z])cover(?![a-zA-Z]))"""),
        "demo" to Regex("""(?i)((?<![a-zA-Z])demo(?![a-zA-Z])|小样)"""),
        "acoustic" to Regex("""(?i)(钢琴版|吉他版|八音盒|(?<![a-zA-Z])acoustic(?![a-zA-Z])|清唱|阿卡贝拉)"""),
        "speed" to Regex("""(?i)(加速版?|降调版?|变速版?|0\.\d+x|1\.\d+x|(?<![a-zA-Z])(nightcore|slowed|sped\s*up)(?![a-zA-Z]))"""),
        "trial" to Regex("""(?i)(片段|铃声|(?<![a-zA-Z])(ringtone|audition|preview|trial|sample)(?![a-zA-Z])|试听|试听版|试听片段|截取)"""),
    )

    private val BRACKET_CONTENT_REGEX = Regex("""[\(\[（【［\{]([^)\]）】］\}]*)[\)\]）】］\}]""")

    private val EXPLICIT_LANG_PATTERNS: List<Pair<String, Regex>> = listOf(
        "cantonese" to Regex("""(?i)(粤语版?|广东话版?|(?<![a-zA-Z])cantonese(?![a-zA-Z]))"""),
        "mandarin" to Regex("""(?i)(国语版?|普通话版?|(?<![a-zA-Z])mandarin(?![a-zA-Z]))"""),
        "english" to Regex("""(?i)(英语版|英文版|(?<![a-zA-Z])english\s*ver(sion|\.)?(?![a-zA-Z]))"""),
        "japanese" to Regex("""(?i)(日语版|日文版|(?<![a-zA-Z])japanese\s*ver(sion|\.)?(?![a-zA-Z]))"""),
        "korean" to Regex("""(?i)(韩语版|韩文版|(?<![a-zA-Z])korean\s*ver(sion|\.)?(?![a-zA-Z]))"""),
        "minnan" to Regex("""(?i)(闽南语版?|台语版?)"""),
    )

    private val BRACKET_SHORT_LANG_PATTERNS: List<Pair<String, Regex>> = listOf(
        "cantonese" to Regex("""(?i)^[\s\-·•]*(粤|粤语|粤语版|广东话|cantonese)[\s\-·•]*$"""),
        "mandarin" to Regex("""(?i)^[\s\-·•]*(国|国语|国语版|普通话|mandarin)[\s\-·•]*$"""),
        "english" to Regex("""(?i)^[\s\-·•]*(英|英文|英语|英文版|英语版|english|eng\s*ver\.?)[\s\-·•]*$"""),
        "japanese" to Regex("""(?i)^[\s\-·•]*(日|日文|日语|日文版|日语版|japanese|jp\s*ver\.?)[\s\-·•]*$"""),
        "korean" to Regex("""(?i)^[\s\-·•]*(韩|韩文|韩语|韩文版|韩语版|korean|kr\s*ver\.?)[\s\-·•]*$"""),
        "minnan" to Regex("""(?i)^[\s\-·•]*(闽|闽南|闽南语|台|台语)[\s\-·•]*$"""),
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

    /** 提取文本中命中的特殊版本标签（归一化分组）。 */
    fun extractTags(text: String): Set<String> {
        val cleaned = cleanText(text)
        if (cleaned.isEmpty()) return emptySet()
        val result = HashSet<String>(2)
        for ((tag, regex) in VERSION_TAG_GROUPS) {
            if (regex.containsMatchIn(cleaned)) {
                result.add(tag)
            }
        }
        return result
    }

    /**
     * 提取歌名中的语种版本标记（如 `(粤)` / `(粤语)` / `(国)` / `(国语)` / `粤语版` 等）。
     * 仅识别括号内标记或显式带「语/版」后缀，避免误伤《中国话》《日不落》《广东爱情故事》等常规歌名。
     */
    fun extractLanguageTags(text: String): Set<String> {
        val cleaned = cleanText(text)
        if (cleaned.isEmpty()) return emptySet()
        val result = HashSet<String>(2)
        for ((lang, regex) in EXPLICIT_LANG_PATTERNS) {
            if (regex.containsMatchIn(cleaned)) {
                result.add(lang)
            }
        }
        BRACKET_CONTENT_REGEX.findAll(cleaned).forEach { match ->
            val inner = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (inner.isNotEmpty()) {
                for ((lang, regex) in BRACKET_SHORT_LANG_PATTERNS) {
                    if (regex.matches(inner)) {
                        result.add(lang)
                    }
                }
            }
        }
        return result
    }

    /** 判断专辑名是否为演唱会/音乐节/Live 现场专辑。 */
    fun isLiveAlbum(album: String?): Boolean {
        val cleaned = cleanText(album)
        if (cleaned.isEmpty()) return false
        return ALBUM_LIVE_REGEX.containsMatchIn(cleaned)
    }

    /** 判断括号或副标题修饰部分是否显式携带 Live 标签（用于核心歌名自身含单词 `live` 时的消歧，如 `Live Forever` vs `Live Forever (Live)`）。 */
    private fun hasModifierLiveTag(rawTitle: String): Boolean {
        val cleaned = cleanText(rawTitle)
        if (cleaned.isEmpty()) return false
        if (BRACKET_CONTENT_REGEX.findAll(cleaned).any { LIVE_TAG_REGEX.containsMatchIn(it.groupValues.getOrNull(1).orEmpty()) }) {
            return true
        }
        val afterDash = cleaned.substringAfter(" - ", "").ifEmpty { cleaned.substringAfter(" — ", "") }
        return afterDash.isNotEmpty() && LIVE_TAG_REGEX.containsMatchIn(afterDash)
    }

    /** 歌名是否一致。 */
    fun isTitleMatched(targetTitle: String, candidateTitle: String): Boolean {
        val normTarget = normalizeForCompare(targetTitle)
        val normCandidate = normalizeForCompare(candidateTitle)
        if (normTarget.isEmpty() || normCandidate.isEmpty()) return false

        // 1. 完全一致
        if (normTarget == normCandidate) return true

        // 2. 特殊版本标签必须一致（原曲不该匹配到 Live / 伴奏 / DJ 版）
        val targetTags = extractTags(targetTitle)
        val candidateTags = extractTags(candidateTitle)
        if (targetTags != candidateTags) {
            return false
        }

        // 2.1 若核心歌名本身包含单词 `live`（如《Live Forever》），校验括号/副标题修饰区 Live 标记必须一致
        val coreTargetRaw = extractCoreTitle(targetTitle)
        if ("live" in targetTags && LIVE_TAG_REGEX.containsMatchIn(coreTargetRaw)) {
            if (hasModifierLiveTag(targetTitle) != hasModifierLiveTag(candidateTitle)) {
                return false
            }
        }

        // 3. 语种版本标记必须对称一致（杜绝 `歌曲名(粤)` 误匹配无括号国语版 `歌曲名`）
        val targetLangs = extractLanguageTags(targetTitle)
        val candidateLangs = extractLanguageTags(candidateTitle)
        if (targetLangs != candidateLangs) {
            return false
        }

        // 4. 核心歌名比对
        val coreTarget = normalizeForCompare(coreTargetRaw)
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
     * 严格综合匹配判定：歌名一致 + 非 Live 源曲严禁匹配演唱会/Live 专辑 + 歌手一致 + 时长误差不超过 5 秒。
     */
    fun isMatched(
        targetTitle: String,
        targetArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        targetDurationMs: Long,
        candidateDurationMs: Long,
        targetAlbum: String = "",
        candidateAlbum: String = "",
    ): Boolean {
        val targetIsLive = ("live" in extractTags(targetTitle)) || isLiveAlbum(targetAlbum)
        val candidateIsLive = ("live" in extractTags(candidateTitle)) || isLiveAlbum(candidateAlbum)
        // 核心铁律：除非源歌曲本身就是 Live，否则绝不允许匹配任何标题或专辑带 Live/演唱会特征的候选
        if (!targetIsLive && candidateIsLive) {
            return false
        }

        if (!isTitleMatched(targetTitle, candidateTitle)) {
            return false
        }

        if (!isArtistMatched(targetArtist, candidateArtist)) {
            return false
        }

        // 两者都有有效时长（>5 秒）时，时长误差不得超过 5 秒 (5000ms)
        if (targetDurationMs > 5000L && candidateDurationMs > 5000L) {
            val diffMs = kotlin.math.abs(targetDurationMs - candidateDurationMs)
            if (diffMs > 5000L) {
                return false
            }
        }

        return true
    }

    /**
     * 匹配优选度打分，用于在多个候选中排序。
     * 核心因子：歌名(50) + 歌手(30) + 时长(20)；次级加权因子：专辑(最高 +12，不做硬淘汰)。
     */
    fun calculateMatchScore(
        targetTitle: String,
        targetArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        targetDurationMs: Long,
        candidateDurationMs: Long,
        targetAlbum: String = "",
        candidateAlbum: String = "",
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
            else if (diffMs <= 3000L) score += 12
            else if (diffMs <= 5000L) score += 6
            else score -= 30
        } else {
            score += 10
        }

        // 次级因子：专辑匹配（仅加分，不淘汰；优先命中原专辑而非合辑/精选集）
        val normTargetAlbum = normalizeForCompare(extractCoreTitle(targetAlbum))
        val normCandidateAlbum = normalizeForCompare(extractCoreTitle(candidateAlbum))
        val unknownAlbums = setOf("未知专辑", "unknownalbum", "unknown", "单曲", "single")
        if (normTargetAlbum.isNotBlank() && normCandidateAlbum.isNotBlank() &&
            normTargetAlbum !in unknownAlbums && normCandidateAlbum !in unknownAlbums
        ) {
            if (normTargetAlbum == normCandidateAlbum) {
                score += 12
            } else if (minOf(normTargetAlbum.length, normCandidateAlbum.length) >= 2 &&
                (normTargetAlbum.contains(normCandidateAlbum) || normCandidateAlbum.contains(normTargetAlbum))
            ) {
                score += 6
            }
        }
        return score
    }
}
