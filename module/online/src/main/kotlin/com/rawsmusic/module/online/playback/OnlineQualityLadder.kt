package com.rawsmusic.module.online.playback

/**
 * 音质降级阶梯。
 *
 * 移植自 OrBitPlayer `OnlineAudioSourceManager` 里两处重复出现的 `qualityTryList` 逻辑，
 * 抽成一处以免两处漂移 —— **阶梯顺序本身照搬，未做任何调整**。
 */
object OnlineQualityLadder {

    /** 用户可选音质。 */
    const val FLAC_24BIT = "flac24bit"
    const val FLAC = "flac"
    const val QUALITY_320K = "320k"
    const val QUALITY_128K = "128k"

    /**
     * 按用户偏好给出尝试顺序：从偏好音质开始逐级降级。
     *
     * 注意 `else` 分支（含未识别取值）是 `128k → 320k → flac`，
     * 即**默认从最低音质起步**、优先保证能出声，而不是优先音质。
     */
    fun qualityTryList(preferredQuality: String?): List<String> = when (preferredQuality) {
        FLAC_24BIT -> listOf("flac24bit", "flac", "320k", "128k")
        FLAC -> listOf("flac", "320k", "128k")
        QUALITY_320K -> listOf("320k", "128k")
        else -> listOf("128k", "320k", "flac")
    }
}
