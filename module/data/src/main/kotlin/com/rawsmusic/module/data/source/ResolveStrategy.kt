package com.rawsmusic.module.data.source

/**
 * 在线音频寻源解析调度策略（1:1 还原 OrbitPlayer）
 */
enum class ResolveStrategy(val id: String, val title: String, val desc: String) {
    CLASSIC_POLLING("classic", "经典多源轮询", "多平台与多音源按序逐一轮询，严格流长验证，抗试听与偶发播放异常"),
    MODERN_CONCURRENT("concurrent", "并发智能寻源 (默认)", "多平台并发探活与竞速搜救，寻源响应迅速");

    companion object {
        fun fromId(id: String?): ResolveStrategy {
            return entries.firstOrNull { it.id == id } ?: CLASSIC_POLLING
        }
    }
}
