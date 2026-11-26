package com.rawsmusic.module.scanner.webdav

import com.rawsmusic.core.common.utils.AppLogger
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * 轻量级 WebDAV HTTP Range 文件头标签读取器。
 *
 * 仅通过 `Range: bytes=0-65535` 拉取音频文件前 64KB（必要时针对超大内嵌封面后的 FLAC VorbisComment
 * 补拉 16KB），直接解析真实内嵌元数据（歌名/歌手/专辑/音轨号/采样率/位深/声道/精准时长），
 * 彻底避免全量下载 100MB+ Hi-Res 大文件，也避免退化为带 `_EM` 等文件名后缀的粗糙切割结果。
 */
object WebDavHeaderTagReader {
    private const val TAG = "WebDavHeaderTagReader"
    private const val INITIAL_RANGE_BYTES = 65_536
    private const val FOLLOWUP_COMMENT_MAX_BYTES = 32_768
    private const val MAX_EMBEDDED_COVER_BYTES = 2 * 1024 * 1024

    data class HeaderTags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val albumArtist: String = "",
        val trackNumber: Int = 0,
        val durationMs: Long = 0L,
        val sampleRate: Int = 0,
        val bitsPerSample: Int = 0,
        val channelCount: Int = 0,
        val bitRateKbps: Int = 0,
        val coverBytes: ByteArray? = null,
    ) {
        fun hasAnyUsefulInfo(): Boolean =
            title.isNotBlank() || artist.isNotBlank() || album.isNotBlank() ||
                durationMs > 0L || sampleRate > 0 || bitsPerSample > 0 ||
                (coverBytes != null && coverBytes.isNotEmpty())
    }

    /**
     * 通过 HTTP Range 从远端 WebDAV 读取音频文件头并解析标签（可选顺带提取内嵌封面字节）。
     * 若服务端不支持 Range（返回 200 且流过大），只读前 64KB 后立刻关闭连接，绝不下载整首文件。
     */
    fun readHeaderTags(
        client: WebDavClient,
        config: WebDavConfig,
        remoteUrl: String,
        fileSizeBytes: Long = 0L,
        extractCover: Boolean = false,
    ): HeaderTags? {
        val head = fetchRangeBytes(
            client = client,
            config = config,
            remoteUrl = remoteUrl,
            start = 0L,
            maxBytes = INITIAL_RANGE_BYTES,
        ) ?: return null
        if (head.size < 16) return null

        val fetcher: (Long, Int) -> ByteArray? = { offset, length ->
            fetchRangeBytes(client, config, remoteUrl, offset, length)
        }

        // 1. FLAC: 魔数 "fLaC"
        if (head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()
        ) {
            return parseFlacHeader(
                head = head,
                fileSizeBytes = fileSizeBytes,
                rangeFetcher = fetcher,
                extractCover = extractCover,
            )
        }

        // 2. MP3 / AIFF / FLAC-with-ID3: 魔数 "ID3"
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val id3Tags = parseId3v2Header(head, extractCover = extractCover, rangeFetcher = fetcher)
            // 部分非标准 FLAC 文件在 fLaC 前包裹了 ID3v2 标签，尝试继续定位 fLaC
            val id3Size = id3v2TotalSize(head)
            if (id3Size in 10..(head.size - 16) &&
                head[id3Size] == 'f'.code.toByte() &&
                head[id3Size + 1] == 'L'.code.toByte() &&
                head[id3Size + 2] == 'a'.code.toByte() &&
                head[id3Size + 3] == 'C'.code.toByte()
            ) {
                val flacSlice = head.copyOfRange(id3Size, head.size)
                val flacTags = parseFlacHeader(
                    head = flacSlice,
                    fileSizeBytes = fileSizeBytes,
                    rangeFetcher = { offset, length -> fetcher(id3Size.toLong() + offset, length) },
                    extractCover = extractCover && id3Tags?.coverBytes == null,
                )
                return mergeTags(primary = flacTags, fallback = id3Tags)
            }
            return id3Tags
        }

        // 3. MP4 / M4A / ALAC: offset 4..7 == "ftyp"
        if (head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        ) {
            return parseMp4Header(head, extractCover = extractCover)
        }

        // 4. OGG (Ogg Vorbis / Ogg Opus): 魔数 "OggS"
        if (head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() &&
            head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte()
        ) {
            return parseOggHeader(
                head = head,
                fileSizeBytes = fileSizeBytes,
                rangeFetcher = fetcher,
                extractCover = extractCover,
            )
        }

        return null
    }

    /**
     * 轻量提取远端 WebDAV 音频的内嵌封面字节（仅通过 HTTP Range 读取元数据头与封面块）。
     */
    fun readEmbeddedCoverBytes(
        client: WebDavClient,
        config: WebDavConfig,
        remoteUrl: String,
    ): ByteArray? {
        return readHeaderTags(
            client = client,
            config = config,
            remoteUrl = remoteUrl,
            fileSizeBytes = 0L,
            extractCover = true,
        )?.coverBytes?.takeIf { it.size > 128 }
    }

    private fun fetchRangeBytes(
        client: WebDavClient,
        config: WebDavConfig,
        remoteUrl: String,
        start: Long,
        maxBytes: Int,
    ): ByteArray? {
        if (start < 0L || maxBytes <= 0) return null
        val end = start + maxBytes - 1L
        val call = runCatching {
            client.openStreamingCall(
                config = config,
                remotePath = remoteUrl,
                rangeHeader = "bytes=$start-$end",
                headOnly = false,
            )
        }.getOrNull() ?: return null

        return try {
            call.execute().use { resp ->
                if (resp.code != 206 && resp.code != 200) return null
                // 若非首段请求但服务器忽略 Range 返回了 200，则放弃解析避免读错偏移
                if (start > 0L && resp.code != 206) return null
                val stream = resp.body?.byteStream() ?: return null
                val out = ByteArrayOutputStream(minOf(maxBytes, 16_384))
                val buf = ByteArray(8_192)
                var total = 0
                while (total < maxBytes) {
                    val toRead = minOf(buf.size, maxBytes - total)
                    val read = stream.read(buf, 0, toRead)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    total += read
                }
                // 主动取消底层流，防止服务端返回 200 时继续传输大文件剩余字节
                if (resp.code == 200) {
                    runCatching { call.cancel() }
                }
                out.toByteArray()
            }
        } catch (e: Throwable) {
            AppLogger.d(TAG, "Range header read failed: ${e.message}")
            null
        }
    }

    /**
     * 解析 FLAC 元数据块（STREAMINFO + VORBIS_COMMENT + 可选 PICTURE 内嵌封面），
     * 支持遇到大封面块时跳过偏移或按需通过 Range 补拉 VorbisComment / PICTURE。
     */
    internal fun parseFlacHeader(
        head: ByteArray,
        fileSizeBytes: Long = 0L,
        rangeFetcher: ((offset: Long, length: Int) -> ByteArray?)? = null,
        extractCover: Boolean = false,
    ): HeaderTags? {
        if (head.size < 42) return null
        if (head[0] != 'f'.code.toByte() || head[1] != 'L'.code.toByte() ||
            head[2] != 'a'.code.toByte() || head[3] != 'C'.code.toByte()
        ) {
            return null
        }

        var sampleRate = 0
        var channelCount = 0
        var bitsPerSample = 0
        var durationMs = 0L
        var bitRateKbps = 0
        var title = ""
        var artist = ""
        var album = ""
        var albumArtist = ""
        var trackNumber = 0
        var coverBytes: ByteArray? = null
        var coverPictureType = -1

        var pos = 4L
        var blockIndex = 0
        while (blockIndex < 32) {
            blockIndex++
            val headerBytes: ByteArray = if (pos + 4L <= head.size) {
                head.copyOfRange(pos.toInt(), (pos + 4L).toInt())
            } else {
                // 超出首段 64KB（通常因为前面嵌了一张超大封面），按需只读 4 字节块头
                if (rangeFetcher == null || pos > 16L * 1024L * 1024L) break
                rangeFetcher(pos, 4) ?: break
            }
            if (headerBytes.size < 4) break

            val firstByte = headerBytes[0].toInt() and 0xFF
            val isLast = (firstByte and 0x80) != 0
            val blockType = firstByte and 0x7F
            val blockLen = ((headerBytes[1].toInt() and 0xFF) shl 16) or
                ((headerBytes[2].toInt() and 0xFF) shl 8) or
                (headerBytes[3].toInt() and 0xFF)
            val payloadOffset = pos + 4L

            when (blockType) {
                0 -> { // STREAMINFO (固定 34 字节)
                    if (blockLen >= 34 && payloadOffset + 34L <= head.size) {
                        val p = payloadOffset.toInt()
                        val b10 = head[p + 10].toInt() and 0xFF
                        val b11 = head[p + 11].toInt() and 0xFF
                        val b12 = head[p + 12].toInt() and 0xFF
                        val b13 = head[p + 13].toInt() and 0xFF
                        val b14 = head[p + 14].toInt() and 0xFF
                        val b15 = head[p + 15].toInt() and 0xFF
                        val b16 = head[p + 16].toInt() and 0xFF
                        val b17 = head[p + 17].toInt() and 0xFF

                        sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
                        channelCount = ((b12 ushr 1) and 0x07) + 1
                        bitsPerSample = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1
                        val totalSamples = ((b13 and 0x0F).toLong() shl 32) or
                            (b14.toLong() shl 24) or
                            (b15.toLong() shl 16) or
                            (b16.toLong() shl 8) or
                            b17.toLong()
                        if (sampleRate > 0 && totalSamples > 0L) {
                            durationMs = (totalSamples * 1000L) / sampleRate.toLong()
                            if (fileSizeBytes > 0L && durationMs > 0L) {
                                bitRateKbps = ((fileSizeBytes * 8L) / durationMs).toInt().coerceAtLeast(0)
                            }
                        }
                    }
                }
                4 -> { // VORBIS_COMMENT
                    if (blockLen in 8..FOLLOWUP_COMMENT_MAX_BYTES) {
                        val commentBytes: ByteArray? = if (payloadOffset + blockLen <= head.size) {
                            head.copyOfRange(payloadOffset.toInt(), (payloadOffset + blockLen).toInt())
                        } else {
                            rangeFetcher?.invoke(payloadOffset, blockLen)
                        }
                        if (commentBytes != null && commentBytes.size == blockLen) {
                            val comments = parseVorbisComments(commentBytes)
                            title = comments["TITLE"].orEmpty()
                            artist = comments["ARTIST"].orEmpty()
                            album = comments["ALBUM"].orEmpty()
                            albumArtist = comments["ALBUMARTIST"].orEmpty()
                            trackNumber = parseTrackNumber(comments["TRACKNUMBER"])
                        }
                    }
                }
                6 -> { // PICTURE (FLAC 内嵌封面)
                    if (extractCover && coverPictureType != 3 && blockLen in 64..MAX_EMBEDDED_COVER_BYTES) {
                        val picBytes: ByteArray? = if (payloadOffset + blockLen <= head.size) {
                            head.copyOfRange(payloadOffset.toInt(), (payloadOffset + blockLen).toInt())
                        } else {
                            rangeFetcher?.invoke(payloadOffset, blockLen)
                        }
                        if (picBytes != null && picBytes.size == blockLen) {
                            val parsedPic = parseFlacPictureBlock(picBytes)
                            if (parsedPic != null && (coverBytes == null || parsedPic.first == 3)) {
                                coverPictureType = parsedPic.first
                                coverBytes = parsedPic.second
                            }
                        }
                    }
                }
            }

            pos = payloadOffset + blockLen.toLong()
            if (isLast) break
            // 若不需要封面，拿到 STREAMINFO 与 VORBIS_COMMENT 即可提前退出；
            // 若需要封面，等拿到 Front Cover 或任意封面后再退出。
            val hasRequiredCover = !extractCover || coverBytes != null
            if (sampleRate > 0 && title.isNotBlank() && hasRequiredCover) break
        }

        val result = HeaderTags(
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            albumArtist = albumArtist.trim(),
            trackNumber = trackNumber,
            durationMs = durationMs,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            channelCount = channelCount,
            bitRateKbps = bitRateKbps,
            coverBytes = coverBytes,
        )
        return result.takeIf { it.hasAnyUsefulInfo() }
    }

    /**
     * 解析 FLAC PICTURE 元数据块，返回 (pictureType, imageBytes)。
     */
    private fun parseFlacPictureBlock(data: ByteArray): Pair<Int, ByteArray>? {
        if (data.size < 32) return null
        var offset = 0
        val pictureType = readBigEndianInt(data, offset)
        offset += 4
        val mimeLen = readBigEndianInt(data, offset)
        offset += 4
        if (mimeLen < 0 || offset + mimeLen + 4 > data.size) return null
        offset += mimeLen
        val descLen = readBigEndianInt(data, offset)
        offset += 4
        if (descLen < 0 || offset + descLen + 20 > data.size) return null
        offset += descLen
        // 跳过 width(4), height(4), depth(4), colors(4)
        offset += 16
        val imgLen = readBigEndianInt(data, offset)
        offset += 4
        if (imgLen <= 128 || offset + imgLen > data.size) return null
        return pictureType to data.copyOfRange(offset, offset + imgLen)
    }

    private fun parseVorbisComments(data: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        if (data.size < 8) return map
        var offset = 0
        val vendorLen = readLittleEndianInt(data, offset)
        offset += 4
        if (vendorLen < 0 || offset + vendorLen + 4 > data.size) return map
        offset += vendorLen

        val commentCount = readLittleEndianInt(data, offset)
        offset += 4
        if (commentCount <= 0 || commentCount > 256) return map

        for (i in 0 until commentCount) {
            if (offset + 4 > data.size) break
            val len = readLittleEndianInt(data, offset)
            offset += 4
            if (len < 0 || offset + len > data.size) break
            val entry = String(data, offset, len, StandardCharsets.UTF_8)
            offset += len
            val eqIdx = entry.indexOf('=')
            if (eqIdx > 0 && eqIdx < entry.length - 1) {
                val key = entry.substring(0, eqIdx).trim().uppercase()
                val value = entry.substring(eqIdx + 1).trim()
                if (value.isNotEmpty()) {
                    val existing = map[key]
                    // 多 ARTIST 标签（如双人合唱）合并用 " / " 连接
                    if (existing != null && key == "ARTIST" && !existing.contains(value)) {
                        map[key] = "$existing / $value"
                    } else if (existing == null) {
                        map[key] = value
                    }
                }
            }
        }
        return map
    }

    /**
     * 解析 MP3 ID3v2.3 / ID3v2.4 文本帧（TIT2 / TPE1 / TALB / TPE2 / TRCK）及可选 APIC 内嵌封面。
     */
    internal fun parseId3v2Header(
        head: ByteArray,
        extractCover: Boolean = false,
        rangeFetcher: ((offset: Long, length: Int) -> ByteArray?)? = null,
    ): HeaderTags? {
        if (head.size < 20) return null
        if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) {
            return null
        }
        val majorVersion = head[3].toInt() and 0xFF
        if (majorVersion != 3 && majorVersion != 4) return null
        val flags = head[5].toInt() and 0xFF
        val tagSize = readSyncSafeInt(head, 6)
        val totalLimit = 10 + tagSize
        val headLimit = minOf(head.size, totalLimit)
        var pos = 10

        // 跳过可选的 Extended Header
        if ((flags and 0x40) != 0 && pos + 4 <= headLimit) {
            val extSize = if (majorVersion == 4) readSyncSafeInt(head, pos) else readBigEndianInt(head, pos)
            pos += if (majorVersion == 4) extSize else (extSize + 4)
        }

        var title = ""
        var artist = ""
        var album = ""
        var albumArtist = ""
        var trackNumber = 0
        var coverBytes: ByteArray? = null

        while (pos + 10 <= headLimit) {
            val f0 = head[pos].toInt() and 0xFF
            if (f0 == 0) break // Padding
            val frameId = String(head, pos, 4, StandardCharsets.ISO_8859_1)
            val frameSize = if (majorVersion == 4) {
                readSyncSafeInt(head, pos + 4)
            } else {
                readBigEndianInt(head, pos + 4)
            }
            pos += 10
            if (frameSize <= 0) break

            if (frameId == "APIC" && extractCover && coverBytes == null && frameSize in 64..MAX_EMBEDDED_COVER_BYTES) {
                val apicData = if (pos + frameSize <= head.size) {
                    head.copyOfRange(pos, pos + frameSize)
                } else {
                    rangeFetcher?.invoke(pos.toLong(), frameSize)
                }
                if (apicData != null && apicData.size == frameSize) {
                    coverBytes = parseId3ApicFrame(apicData)
                }
            } else if (pos + frameSize <= headLimit) {
                when (frameId) {
                    "TIT2" -> title = decodeId3TextFrame(head, pos, frameSize).ifBlank { title }
                    "TPE1" -> artist = decodeId3TextFrame(head, pos, frameSize).ifBlank { artist }
                    "TALB" -> album = decodeId3TextFrame(head, pos, frameSize).ifBlank { album }
                    "TPE2" -> albumArtist = decodeId3TextFrame(head, pos, frameSize).ifBlank { albumArtist }
                    "TRCK" -> trackNumber = parseTrackNumber(decodeId3TextFrame(head, pos, frameSize))
                }
            }
            if (pos + frameSize > headLimit) break
            pos += frameSize
        }

        val result = HeaderTags(
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            albumArtist = albumArtist.trim(),
            trackNumber = trackNumber,
            coverBytes = coverBytes,
        )
        return result.takeIf { it.hasAnyUsefulInfo() }
    }

    /**
     * 解析 ID3v2 APIC 帧，跳过 encoding + MIME(NUL) + pictureType(1) + description(NUL/NULNUL)，返回图片原始字节。
     */
    private fun parseId3ApicFrame(data: ByteArray): ByteArray? {
        if (data.size < 16) return null
        val encoding = data[0].toInt() and 0xFF
        var idx = 1
        while (idx < data.size && data[idx] != 0.toByte()) idx++
        idx++ // skip MIME NUL
        if (idx >= data.size) return null
        idx++ // skip pictureType
        if (encoding == 1 || encoding == 2) {
            // UTF-16 以 0x00 0x00 结尾
            while (idx + 1 < data.size && !(data[idx] == 0.toByte() && data[idx + 1] == 0.toByte())) {
                idx += 2
            }
            idx += 2
        } else {
            while (idx < data.size && data[idx] != 0.toByte()) idx++
            idx++
        }
        if (idx >= data.size || data.size - idx <= 128) return null
        return data.copyOfRange(idx, data.size)
    }

    private fun id3v2TotalSize(head: ByteArray): Int {
        if (head.size < 10) return 0
        return 10 + readSyncSafeInt(head, 6)
    }

    private fun decodeId3TextFrame(data: ByteArray, offset: Int, length: Int): String {
        if (length <= 1 || offset + length > data.size) return ""
        val encoding = data[offset].toInt() and 0xFF
        val payloadStart = offset + 1
        val payloadLen = length - 1
        val charset: Charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.UTF_8
        }
        return runCatching {
            String(data, payloadStart, payloadLen, charset)
                .replace("\u0000", "")
                .trim()
        }.getOrDefault("")
    }

    /**
     * 轻量扫描 MP4 / M4A 文件头前 64KB 内的 iTunes `ilst` 元数据原子（©nam / ©ART / ©alb / aART / covr）。
     */
    internal fun parseMp4Header(head: ByteArray, extractCover: Boolean = false): HeaderTags? {
        val title = findMp4IlstText(head, byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte()))
        val artist = findMp4IlstText(head, byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte()))
        val album = findMp4IlstText(head, byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte()))
        val albumArtist = findMp4IlstText(head, "aART".toByteArray(StandardCharsets.ISO_8859_1))
        val coverBytes = if (extractCover) findMp4CoverBytes(head) else null
        val result = HeaderTags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            coverBytes = coverBytes,
        )
        return result.takeIf { it.hasAnyUsefulInfo() }
    }

    private fun findMp4CoverBytes(data: ByteArray): ByteArray? {
        val covr = "covr".toByteArray(StandardCharsets.ISO_8859_1)
        if (data.size < 24) return null
        for (i in 4 until data.size - 20) {
            if (data[i] == covr[0] && data[i + 1] == covr[1] &&
                data[i + 2] == covr[2] && data[i + 3] == covr[3]
            ) {
                val dataAtomStart = i + 4
                if (dataAtomStart + 16 > data.size) continue
                if (data[dataAtomStart + 4] == 'd'.code.toByte() &&
                    data[dataAtomStart + 5] == 'a'.code.toByte() &&
                    data[dataAtomStart + 6] == 't'.code.toByte() &&
                    data[dataAtomStart + 7] == 'a'.code.toByte()
                ) {
                    val atomSize = readBigEndianInt(data, dataAtomStart)
                    val imgLen = atomSize - 16
                    val imgStart = dataAtomStart + 16
                    if (imgLen in 129..MAX_EMBEDDED_COVER_BYTES && imgStart + imgLen <= data.size) {
                        return data.copyOfRange(imgStart, imgStart + imgLen)
                    }
                }
            }
        }
        return null
    }

    /**
     * 解析 OGG（Ogg Vorbis / Ogg Opus）文件头：
     * - Page 0 提取采样率与声道数；
     * - Page 1+ 跨页拼接 Comment Header Packet（`\x03vorbis` 或 `OpusTags`），解析 VorbisComment 标签；
     * - 当 [extractCover] 为 true 时自动通过 [rangeFetcher] 补拉完整跨页 Comment Packet，
     *   解码 `METADATA_BLOCK_PICTURE`（FLAC PICTURE Base64）或 `COVERART` 提取内嵌封面。
     */
    internal fun parseOggHeader(
        head: ByteArray,
        fileSizeBytes: Long = 0L,
        rangeFetcher: ((offset: Long, length: Int) -> ByteArray?)? = null,
        extractCover: Boolean = false,
    ): HeaderTags? {
        if (head.size < 32) return null
        if (head[0] != 'O'.code.toByte() || head[1] != 'g'.code.toByte() ||
            head[2] != 'g'.code.toByte() || head[3] != 'S'.code.toByte()
        ) {
            return null
        }

        // OGG 封面经过 Base64 膨胀约 1.33 倍，补拉上限放宽至 3MB
        val maxOggScanBytes = if (extractCover) 3 * 1024 * 1024 else INITIAL_RANGE_BYTES
        val chunkFetchSize = 131_072
        var buf = head

        fun ensureBytes(requiredEnd: Int): Boolean {
            if (requiredEnd <= buf.size) return true
            if (rangeFetcher == null || requiredEnd > maxOggScanBytes) return false
            while (buf.size < requiredEnd && buf.size < maxOggScanBytes) {
                val fetchLen = minOf(chunkFetchSize, maxOggScanBytes - buf.size)
                val more = rangeFetcher(buf.size.toLong(), fetchLen) ?: return false
                if (more.isEmpty()) return false
                buf = buf + more
            }
            return requiredEnd <= buf.size
        }

        var sampleRate = 0
        var channelCount = 0
        val commentPacket = ByteArrayOutputStream(8192)
        var inCommentPacket = false
        var commentPacketComplete = false

        var pos = 0
        var pageIndex = 0
        while (pageIndex < 128 && !commentPacketComplete) {
            if (!ensureBytes(pos + 27)) break
            if (buf[pos] != 'O'.code.toByte() || buf[pos + 1] != 'g'.code.toByte() ||
                buf[pos + 2] != 'g'.code.toByte() || buf[pos + 3] != 'S'.code.toByte()
            ) {
                break
            }
            val nSeg = buf[pos + 26].toInt() and 0xFF
            val segTableStart = pos + 27
            val bodyStart = segTableStart + nSeg
            if (!ensureBytes(bodyStart)) break

            var bodyLen = 0
            for (i in 0 until nSeg) {
                bodyLen += buf[segTableStart + i].toInt() and 0xFF
            }
            val pageEnd = bodyStart + bodyLen
            val hasFullPage = ensureBytes(pageEnd)
            val availableBodyEnd = minOf(pageEnd, buf.size)

            if (pageIndex == 0 && availableBodyEnd > bodyStart + 16) {
                // Ogg Vorbis Identification Header: \x01vorbis (7 bytes) + version(4) + channels(1) + sampleRate(4 LE)
                if (buf[bodyStart] == 1.toByte() &&
                    buf[bodyStart + 1] == 'v'.code.toByte() &&
                    buf[bodyStart + 2] == 'o'.code.toByte() &&
                    buf[bodyStart + 3] == 'r'.code.toByte() &&
                    buf[bodyStart + 4] == 'b'.code.toByte() &&
                    buf[bodyStart + 5] == 'i'.code.toByte() &&
                    buf[bodyStart + 6] == 's'.code.toByte() &&
                    bodyStart + 16 <= availableBodyEnd
                ) {
                    channelCount = buf[bodyStart + 11].toInt() and 0xFF
                    sampleRate = readLittleEndianInt(buf, bodyStart + 12)
                } else if (buf[bodyStart] == 'O'.code.toByte() &&
                    buf[bodyStart + 1] == 'p'.code.toByte() &&
                    buf[bodyStart + 2] == 'u'.code.toByte() &&
                    buf[bodyStart + 3] == 's'.code.toByte() &&
                    buf[bodyStart + 4] == 'H'.code.toByte() &&
                    buf[bodyStart + 5] == 'e'.code.toByte() &&
                    buf[bodyStart + 6] == 'a'.code.toByte() &&
                    buf[bodyStart + 7] == 'd'.code.toByte() &&
                    bodyStart + 16 <= availableBodyEnd
                ) {
                    channelCount = buf[bodyStart + 9].toInt() and 0xFF
                    sampleRate = 48_000
                }
            } else if (pageIndex >= 1) {
                if (!inCommentPacket) {
                    inCommentPacket = true
                }
                var segOff = bodyStart
                for (i in 0 until nSeg) {
                    val segLen = buf[segTableStart + i].toInt() and 0xFF
                    val writable = minOf(segLen, (availableBodyEnd - segOff).coerceAtLeast(0))
                    if (writable > 0) {
                        commentPacket.write(buf, segOff, writable)
                    }
                    segOff += segLen
                    if (segLen < 255) {
                        commentPacketComplete = hasFullPage
                        break
                    }
                }
            }

            if (!hasFullPage) break
            pos = pageEnd
            pageIndex++
        }

        val pkt = commentPacket.toByteArray()
        val commentOffset = when {
            pkt.size >= 7 &&
                pkt[0] == 3.toByte() &&
                pkt[1] == 'v'.code.toByte() &&
                pkt[2] == 'o'.code.toByte() &&
                pkt[3] == 'r'.code.toByte() &&
                pkt[4] == 'b'.code.toByte() &&
                pkt[5] == 'i'.code.toByte() &&
                pkt[6] == 's'.code.toByte() -> 7
            pkt.size >= 8 &&
                pkt[0] == 'O'.code.toByte() &&
                pkt[1] == 'p'.code.toByte() &&
                pkt[2] == 'u'.code.toByte() &&
                pkt[3] == 's'.code.toByte() &&
                pkt[4] == 'T'.code.toByte() &&
                pkt[5] == 'a'.code.toByte() &&
                pkt[6] == 'g'.code.toByte() &&
                pkt[7] == 's'.code.toByte() -> 8
            else -> -1
        }

        var title = ""
        var artist = ""
        var album = ""
        var albumArtist = ""
        var trackNumber = 0
        var coverBytes: ByteArray? = null

        if (commentOffset > 0 && pkt.size > commentOffset + 8) {
            val commentSlice = pkt.copyOfRange(commentOffset, pkt.size)
            val parsed = parseOggVorbisComments(commentSlice, extractCover = extractCover)
            title = parsed.comments["TITLE"].orEmpty()
            artist = parsed.comments["ARTIST"].orEmpty()
            album = parsed.comments["ALBUM"].orEmpty()
            albumArtist = parsed.comments["ALBUMARTIST"].orEmpty()
            trackNumber = parseTrackNumber(parsed.comments["TRACKNUMBER"])
            coverBytes = parsed.coverBytes
        }

        val result = HeaderTags(
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            albumArtist = albumArtist.trim(),
            trackNumber = trackNumber,
            sampleRate = sampleRate,
            channelCount = channelCount,
            coverBytes = coverBytes,
        )
        return result.takeIf { it.hasAnyUsefulInfo() }
    }

    private data class OggCommentResult(
        val comments: Map<String, String>,
        val coverBytes: ByteArray?,
    )

    private fun parseOggVorbisComments(data: ByteArray, extractCover: Boolean): OggCommentResult {
        val map = LinkedHashMap<String, String>()
        var coverBytes: ByteArray? = null
        var coverPictureType = -1
        if (data.size < 8) return OggCommentResult(map, null)

        var offset = 0
        val vendorLen = readLittleEndianInt(data, offset)
        offset += 4
        if (vendorLen < 0 || offset + vendorLen + 4 > data.size) return OggCommentResult(map, null)
        offset += vendorLen

        val commentCount = readLittleEndianInt(data, offset)
        offset += 4
        if (commentCount <= 0 || commentCount > 256) return OggCommentResult(map, null)

        for (i in 0 until commentCount) {
            if (offset + 4 > data.size) break
            val len = readLittleEndianInt(data, offset)
            offset += 4
            if (len < 0 || offset + len > data.size) break

            // 快速识别大体积内嵌封面字段，避免在不需要封面时解码数百 KB 字符串
            val eqRel = indexOfByte(data, offset, offset + len, '='.code.toByte())
            if (eqRel > 0 && eqRel < len - 1) {
                val key = String(data, offset, eqRel, StandardCharsets.ISO_8859_1).trim().uppercase()
                val valStart = offset + eqRel + 1
                val valLen = len - eqRel - 1
                when (key) {
                    "METADATA_BLOCK_PICTURE" -> {
                        if (extractCover && coverPictureType != 3 && valLen in 128..(MAX_EMBEDDED_COVER_BYTES * 2)) {
                            val b64 = String(data, valStart, valLen, StandardCharsets.ISO_8859_1)
                            val decoded = decodeBase64Safe(b64)
                            val parsedPic = decoded?.let { parseFlacPictureBlock(it) }
                            if (parsedPic != null && (coverBytes == null || parsedPic.first == 3)) {
                                coverPictureType = parsedPic.first
                                coverBytes = parsedPic.second
                            }
                        }
                    }
                    "COVERART" -> {
                        if (extractCover && coverBytes == null && valLen in 128..(MAX_EMBEDDED_COVER_BYTES * 2)) {
                            val b64 = String(data, valStart, valLen, StandardCharsets.ISO_8859_1)
                            val decoded = decodeBase64Safe(b64)
                            if (decoded != null && decoded.size > 128) {
                                coverBytes = decoded
                            }
                        }
                    }
                    else -> {
                        if (valLen <= 8192) {
                            val value = String(data, valStart, valLen, StandardCharsets.UTF_8).trim()
                            if (value.isNotEmpty()) {
                                val existing = map[key]
                                if (existing != null && key == "ARTIST" && !existing.contains(value)) {
                                    map[key] = "$existing / $value"
                                } else if (existing == null) {
                                    map[key] = value
                                }
                            }
                        }
                    }
                }
            }
            offset += len
        }
        return OggCommentResult(map, coverBytes)
    }

    private fun indexOfByte(data: ByteArray, start: Int, end: Int, target: Byte): Int {
        val limit = minOf(end, data.size)
        for (i in start until limit) {
            if (data[i] == target) return i - start
        }
        return -1
    }

    /**
     * 零依赖纯 Kotlin Base64 解码器（同时兼容 Android API 24 与纯 JVM 单元测试）。
     */
    private fun decodeBase64Safe(input: String): ByteArray? {
        if (input.isEmpty()) return null
        val out = ByteArrayOutputStream((input.length * 3) / 4)
        var acc = 0
        var bits = 0
        for (ch in input) {
            val v = when (ch) {
                in 'A'..'Z' -> ch.code - 'A'.code
                in 'a'..'z' -> ch.code - 'a'.code + 26
                in '0'..'9' -> ch.code - '0'.code + 52
                '+', '-' -> 62
                '/', '_' -> 63
                '=', ' ', '\r', '\n', '\t' -> continue
                else -> return null
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun findMp4IlstText(data: ByteArray, atomType: ByteArray): String {
        if (atomType.size != 4 || data.size < 24) return ""
        for (i in 4 until data.size - 20) {
            if (data[i] == atomType[0] && data[i + 1] == atomType[1] &&
                data[i + 2] == atomType[2] && data[i + 3] == atomType[3]
            ) {
                // 期望紧跟一个 `data` atom: [size:4]["data":4][typeFlag:4][locale:4][utf8Payload...]
                val dataAtomStart = i + 4
                if (dataAtomStart + 16 > data.size) continue
                if (data[dataAtomStart + 4] == 'd'.code.toByte() &&
                    data[dataAtomStart + 5] == 'a'.code.toByte() &&
                    data[dataAtomStart + 6] == 't'.code.toByte() &&
                    data[dataAtomStart + 7] == 'a'.code.toByte()
                ) {
                    val atomSize = readBigEndianInt(data, dataAtomStart)
                    val textLen = atomSize - 16
                    val textStart = dataAtomStart + 16
                    if (textLen in 1..512 && textStart + textLen <= data.size) {
                        return String(data, textStart, textLen, StandardCharsets.UTF_8)
                            .replace("\u0000", "")
                            .trim()
                    }
                }
            }
        }
        return ""
    }

    private fun mergeTags(primary: HeaderTags?, fallback: HeaderTags?): HeaderTags? {
        if (primary == null) return fallback
        if (fallback == null) return primary
        return HeaderTags(
            title = primary.title.ifBlank { fallback.title },
            artist = primary.artist.ifBlank { fallback.artist },
            album = primary.album.ifBlank { fallback.album },
            albumArtist = primary.albumArtist.ifBlank { fallback.albumArtist },
            trackNumber = primary.trackNumber.takeIf { it > 0 } ?: fallback.trackNumber,
            durationMs = primary.durationMs.takeIf { it > 0L } ?: fallback.durationMs,
            sampleRate = primary.sampleRate.takeIf { it > 0 } ?: fallback.sampleRate,
            bitsPerSample = primary.bitsPerSample.takeIf { it > 0 } ?: fallback.bitsPerSample,
            channelCount = primary.channelCount.takeIf { it > 0 } ?: fallback.channelCount,
            bitRateKbps = primary.bitRateKbps.takeIf { it > 0 } ?: fallback.bitRateKbps,
            coverBytes = primary.coverBytes ?: fallback.coverBytes,
        )
    }

    private fun parseTrackNumber(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        return raw.substringBefore('/').trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun readLittleEndianInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readBigEndianInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readSyncSafeInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }
}
