package com.rawsmusic.module.online.source.podcast

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.model.PodcastEpisode
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 播客 RSS XML 解析器（方向二核心）。
 *
 * 提取播客节目的往期单集、时长、发布时间与真实音频播放直链（MP3 / M4A）。
 */
object PodcastFeedParser {
    private const val TAG = "PodcastFeedParser"

    fun parseEpisodes(
        showId: String,
        showTitle: String,
        defaultCoverUrl: String,
        xmlContent: String,
        limit: Int = Int.MAX_VALUE,
    ): List<PodcastEpisode> {
        val episodes = mutableListOf<PodcastEpisode>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            var insideItem = false

            var title = ""
            var description = ""
            var pubDate = ""
            var durationMs = 0L
            var coverUrl = defaultCoverUrl
            var audioUrl: String? = null
            var guid = ""

            while (eventType != XmlPullParser.END_DOCUMENT && episodes.size < limit) {
                val tagName = parser.name ?: ""

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("item", ignoreCase = true)) {
                            insideItem = true
                            title = ""
                            description = ""
                            pubDate = ""
                            durationMs = 0L
                            coverUrl = defaultCoverUrl
                            audioUrl = null
                            guid = ""
                        } else if (insideItem) {
                            when {
                                tagName.equals("title", ignoreCase = true) -> {
                                    runCatching {
                                        val t = parser.nextText().trim()
                                        if (t.isNotBlank()) title = t
                                    }
                                }
                                tagName.equals("description", ignoreCase = true) ||
                                    tagName.equals("summary", ignoreCase = true) -> {
                                    runCatching {
                                        val text = parser.nextText().trim()
                                        if (description.isEmpty() && text.isNotBlank()) {
                                            description = cleanHtml(text)
                                        }
                                    }
                                }
                                tagName.equals("pubDate", ignoreCase = true) -> {
                                    runCatching {
                                        val p = parser.nextText().trim()
                                        if (p.isNotBlank()) pubDate = p
                                    }
                                }
                                tagName.equals("guid", ignoreCase = true) -> {
                                    runCatching {
                                        val g = parser.nextText().trim()
                                        if (g.isNotBlank()) guid = g
                                    }
                                }
                                tagName.equals("duration", ignoreCase = true) -> {
                                    runCatching {
                                        val dStr = parser.nextText().trim()
                                        durationMs = parseDurationToMs(dStr)
                                    }
                                }
                                tagName.equals("enclosure", ignoreCase = true) ||
                                    tagName.equals("content", ignoreCase = true) -> {
                                    val url = parser.getAttributeValue(null, "url")
                                        ?: parser.getAttributeValue(null, "URL")
                                        ?: parser.getAttributeValue(null, "href")
                                    if (!url.isNullOrBlank() && (audioUrl == null || url.contains(".mp3") || url.contains(".m4a"))) {
                                        audioUrl = url.trim()
                                    }
                                }
                                tagName.equals("image", ignoreCase = true) -> {
                                    val href = parser.getAttributeValue(null, "href")
                                        ?: parser.getAttributeValue(null, "url")
                                    if (!href.isNullOrBlank()) {
                                        coverUrl = href.trim()
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("item", ignoreCase = true)) {
                            insideItem = false
                            if (title.isNotBlank()) {
                                val epId = if (guid.isNotBlank()) "${guid}_${episodes.size}" else "${showId}_${episodes.size}"
                                episodes.add(
                                    PodcastEpisode(
                                        id = epId,
                                        showId = showId,
                                        showTitle = showTitle,
                                        title = title,
                                        description = description,
                                        pubDate = formatPubDate(pubDate),
                                        durationMs = durationMs,
                                        coverUrl = coverUrl.ifBlank { defaultCoverUrl },
                                        audioUrl = audioUrl,
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析播客 RSS XML 异常: ${e.message}", e)
        }
        return episodes
    }

    private fun cleanHtml(html: String): String {
        return html.replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun parseDurationToMs(durationStr: String): Long {
        if (durationStr.isBlank()) return 0L
        return try {
            if (durationStr.contains(":")) {
                val parts = durationStr.split(":")
                var seconds = 0L
                for (part in parts) {
                    seconds = seconds * 60 + (part.toLongOrNull() ?: 0L)
                }
                seconds * 1000L
            } else {
                (durationStr.toLongOrNull() ?: 0L) * 1000L
            }
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatPubDate(rawDate: String): String {
        if (rawDate.isBlank()) return ""
        // 通常形如 "Wed, 01 May 2024 10:00:00 +0000"
        return try {
            val parts = rawDate.split(" ")
            if (parts.size >= 4) {
                "${parts[3]}-${parts[2]}-${parts[1]}"
            } else {
                rawDate
            }
        } catch (e: Exception) {
            rawDate
        }
    }
}

