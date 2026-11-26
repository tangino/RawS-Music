package com.rawsmusic.module.scanner.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class WebDavHeaderTagReaderTest {

    @Test
    fun testParseFlacHeader_extractsStreamInfoAndVorbisTagsWithoutEmSuffix() {
        val flacHeader = buildSampleFlacHeader(
            sampleRate = 192_000,
            channels = 2,
            bitsPerSample = 24,
            totalSamples = 192_000L * 215L, // 215s
            comments = listOf(
                "TITLE=好胜",
                "ARTIST=2R",
                "ALBUM=Two Of Us",
                "TRACKNUMBER=3/12",
            ),
            precedingPictureBytes = 0,
        )

        val tags = WebDavHeaderTagReader.parseFlacHeader(
            head = flacHeader,
            fileSizeBytes = 112_341_827L,
        )

        assertNotNull(tags)
        assertEquals("好胜", tags!!.title)
        assertEquals("2R", tags.artist)
        assertEquals("Two Of Us", tags.album)
        assertEquals(3, tags.trackNumber)
        assertEquals(192_000, tags.sampleRate)
        assertEquals(24, tags.bitsPerSample)
        assertEquals(2, tags.channelCount)
        assertEquals(215_000L, tags.durationMs)
    }

    @Test
    fun testParseFlacHeader_skipsOversizedPictureAndFetchesVorbisCommentByRange() {
        // 模拟 80KB 超大封面位于 VorbisComment 之前（超出首段 64KB Range）
        val fullData = buildSampleFlacHeader(
            sampleRate = 96_000,
            channels = 2,
            bitsPerSample = 24,
            totalSamples = 96_000L * 240L,
            comments = listOf(
                "TITLE=2 Become 1",
                "ARTIST=Spice Girls",
                "ALBUM=Spice",
            ),
            precedingPictureBytes = 80_000,
        )
        val first64k = fullData.copyOfRange(0, 65_536)

        val tags = WebDavHeaderTagReader.parseFlacHeader(
            head = first64k,
            fileSizeBytes = 120_000_000L,
            rangeFetcher = { offset, length ->
                val start = offset.toInt()
                val end = minOf(fullData.size, start + length)
                if (start in 0 until end) fullData.copyOfRange(start, end) else null
            },
        )

        assertNotNull(tags)
        assertEquals("2 Become 1", tags!!.title)
        assertEquals("Spice Girls", tags.artist)
        assertEquals("Spice", tags.album)
        assertEquals(96_000, tags.sampleRate)
        assertEquals(24, tags.bitsPerSample)
        assertEquals(240_000L, tags.durationMs)
    }

    @Test
    fun testParseId3v2Header_utf8Frames() {
        val out = ByteArrayOutputStream()
        val frames = ByteArrayOutputStream()
        fun writeFrame(id: String, text: String) {
            val payload = byteArrayOf(3) + text.toByteArray(StandardCharsets.UTF_8)
            frames.write(id.toByteArray(StandardCharsets.ISO_8859_1))
            frames.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array())
            frames.write(byteArrayOf(0, 0))
            frames.write(payload)
        }
        writeFrame("TIT2", "21 Guns")
        writeFrame("TPE1", "Green Day")
        writeFrame("TALB", "21st Century Breakdown")
        writeFrame("TRCK", "16")

        val frameBytes = frames.toByteArray()
        out.write("ID3".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(byteArrayOf(3, 0, 0)) // v2.3
        val sz = frameBytes.size
        out.write(
            byteArrayOf(
                ((sz ushr 21) and 0x7F).toByte(),
                ((sz ushr 14) and 0x7F).toByte(),
                ((sz ushr 7) and 0x7F).toByte(),
                (sz and 0x7F).toByte(),
            )
        )
        out.write(frameBytes)

        val tags = WebDavHeaderTagReader.parseId3v2Header(out.toByteArray())
        assertNotNull(tags)
        assertEquals("21 Guns", tags!!.title)
        assertEquals("Green Day", tags.artist)
        assertEquals("21st Century Breakdown", tags.album)
        assertEquals(16, tags.trackNumber)
    }

    @Test
    fun testParseFlacHeader_extractsEmbeddedPictureBlock() {
        val fakeJpeg = ByteArray(2048) { idx ->
            when (idx) {
                0 -> 0xFF.toByte()
                1 -> 0xD8.toByte()
                2 -> 0xFF.toByte()
                else -> (idx and 0xFF).toByte()
            }
        }
        val picPayload = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(3).array()) // Front Cover
            val mime = "image/jpeg".toByteArray(StandardCharsets.ISO_8859_1)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(mime.size).array())
            write(mime)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array()) // desc len
            write(ByteArray(16)) // w, h, depth, colors
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(fakeJpeg.size).array())
            write(fakeJpeg)
        }.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.ISO_8859_1))
        val si = ByteArray(34)
        si[10] = ((44_100 ushr 12) and 0xFF).toByte()
        si[11] = ((44_100 ushr 4) and 0xFF).toByte()
        si[12] = (((44_100 and 0x0F) shl 4) or (1 shl 1)).toByte()
        si[13] = (15 shl 4).toByte()
        writeFlacBlock(out, type = 0, isLast = false, payload = si)

        val vc = ByteArrayOutputStream()
        val vendor = "libFLAC".toByteArray(StandardCharsets.UTF_8)
        vc.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vendor.size).array())
        vc.write(vendor)
        vc.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array())
        val titleEntry = "TITLE=不要墨尔本的翡翠".toByteArray(StandardCharsets.UTF_8)
        vc.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(titleEntry.size).array())
        vc.write(titleEntry)
        writeFlacBlock(out, type = 4, isLast = false, payload = vc.toByteArray())
        writeFlacBlock(out, type = 6, isLast = true, payload = picPayload)

        val tags = WebDavHeaderTagReader.parseFlacHeader(
            head = out.toByteArray(),
            extractCover = true,
        )
        assertNotNull(tags)
        assertEquals("不要墨尔本的翡翠", tags!!.title)
        assertNotNull(tags.coverBytes)
        assertEquals(fakeJpeg.size, tags.coverBytes!!.size)
        assertEquals(0xFF.toByte(), tags.coverBytes!![0])
        assertEquals(0xD8.toByte(), tags.coverBytes!![1])
    }

    @Test
    fun testParseOggHeader_extractsMultiPageVorbisTagsAndEmbeddedCover() {
        val fakeJpeg = ByteArray(2048) { idx ->
            when (idx) {
                0 -> 0xFF.toByte()
                1 -> 0xD8.toByte()
                2 -> 0xFF.toByte()
                else -> (idx and 0xFF).toByte()
            }
        }
        val picPayload = ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(3).array()) // Front Cover
            val mime = "image/jpeg".toByteArray(StandardCharsets.ISO_8859_1)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(mime.size).array())
            write(mime)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array())
            write(ByteArray(16))
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(fakeJpeg.size).array())
            write(fakeJpeg)
        }.toByteArray()
        val picBase64 = java.util.Base64.getEncoder().encodeToString(picPayload)

        // Page 0: Vorbis ID header (44100 Hz, 2ch)
        val idPkt = ByteArrayOutputStream().apply {
            write(1)
            write("vorbis".toByteArray(StandardCharsets.ISO_8859_1))
            write(ByteArray(4)) // version = 0
            write(2) // channels
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(44_100).array())
            write(ByteArray(14))
        }.toByteArray()

        // Comment packet: \x03vorbis + VorbisComment (含 METADATA_BLOCK_PICTURE)
        val commentPkt = ByteArrayOutputStream().apply {
            write(3)
            write("vorbis".toByteArray(StandardCharsets.ISO_8859_1))
            val vendor = "Xiph.Org libVorbis".toByteArray(StandardCharsets.UTF_8)
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vendor.size).array())
            write(vendor)
            val entries = listOf(
                "TITLE=Boyfriend 挚爱",
                "ARTIST=5566",
                "ALBUM=2Nd Album 挚爱",
                "METADATA_BLOCK_PICTURE=$picBase64",
            )
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(entries.size).array())
            for (e in entries) {
                val b = e.toByteArray(StandardCharsets.UTF_8)
                write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(b.size).array())
                write(b)
            }
            write(1) // framing bit
        }.toByteArray()

        fun buildOggPage(body: ByteArray, headerType: Int, segs: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write("OggS".toByteArray(StandardCharsets.ISO_8859_1))
            out.write(0) // version
            out.write(headerType)
            out.write(ByteArray(8)) // granule
            out.write(ByteArray(4)) // serial
            out.write(ByteArray(4)) // seq
            out.write(ByteArray(4)) // crc
            out.write(segs.size)
            out.write(segs)
            out.write(body)
            return out.toByteArray()
        }

        // 把 commentPkt 拆成两页，模拟跨页拉取
        val splitAt = 510 // 2 * 255
        val fullOgg = ByteArrayOutputStream().apply {
            write(buildOggPage(idPkt, headerType = 2, segs = byteArrayOf(idPkt.size.toByte())))
            write(buildOggPage(commentPkt.copyOfRange(0, splitAt), headerType = 0, segs = byteArrayOf(255.toByte(), 255.toByte())))
            val rest = commentPkt.copyOfRange(splitAt, commentPkt.size)
            val restSegs = ByteArrayOutputStream().apply {
                var rem = rest.size
                while (rem >= 255) {
                    write(255)
                    rem -= 255
                }
                write(rem)
            }.toByteArray()
            write(buildOggPage(rest, headerType = 1, segs = restSegs))
        }.toByteArray()

        // 模拟首段 Range 只拿到前 400 字节，后面通过 rangeFetcher 补拉
        val firstSlice = fullOgg.copyOfRange(0, 400)
        val tags = WebDavHeaderTagReader.parseOggHeader(
            head = firstSlice,
            rangeFetcher = { offset, length ->
                val start = offset.toInt()
                val end = minOf(fullOgg.size, start + length)
                if (start in 0 until end) fullOgg.copyOfRange(start, end) else null
            },
            extractCover = true,
        )

        assertNotNull(tags)
        assertEquals("Boyfriend 挚爱", tags!!.title)
        assertEquals("5566", tags.artist)
        assertEquals("2Nd Album 挚爱", tags.album)
        assertEquals(44_100, tags.sampleRate)
        assertEquals(2, tags.channelCount)
        assertNotNull(tags.coverBytes)
        assertEquals(fakeJpeg.size, tags.coverBytes!!.size)
        assertEquals(0xFF.toByte(), tags.coverBytes!![0])
        assertEquals(0xD8.toByte(), tags.coverBytes!![1])
    }

    private fun buildSampleFlacHeader(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        totalSamples: Long,
        comments: List<String>,
        precedingPictureBytes: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.ISO_8859_1))

        // Block 0: STREAMINFO (34 bytes)
        val si = ByteArray(34)
        si[10] = ((sampleRate ushr 12) and 0xFF).toByte()
        si[11] = ((sampleRate ushr 4) and 0xFF).toByte()
        val chBits = (channels - 1) and 0x07
        val bpsBits = (bitsPerSample - 1) and 0x1F
        si[12] = (((sampleRate and 0x0F) shl 4) or (chBits shl 1) or ((bpsBits ushr 4) and 0x01)).toByte()
        si[13] = (((bpsBits and 0x0F) shl 4) or ((totalSamples ushr 32).toInt() and 0x0F)).toByte()
        si[14] = ((totalSamples ushr 24) and 0xFF).toByte()
        si[15] = ((totalSamples ushr 16) and 0xFF).toByte()
        si[16] = ((totalSamples ushr 8) and 0xFF).toByte()
        si[17] = (totalSamples and 0xFF).toByte()
        writeFlacBlock(out, type = 0, isLast = false, payload = si)

        if (precedingPictureBytes > 0) {
            writeFlacBlock(out, type = 6, isLast = false, payload = ByteArray(precedingPictureBytes))
        }

        // Block 4: VORBIS_COMMENT
        val vc = ByteArrayOutputStream()
        val vendor = "reference libFLAC".toByteArray(StandardCharsets.UTF_8)
        vc.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vendor.size).array())
        vc.write(vendor)
        vc.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(comments.size).array())
        for (c in comments) {
            val cb = c.toByteArray(StandardCharsets.UTF_8)
            vc.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cb.size).array())
            vc.write(cb)
        }
        writeFlacBlock(out, type = 4, isLast = true, payload = vc.toByteArray())
        return out.toByteArray()
    }

    private fun writeFlacBlock(out: ByteArrayOutputStream, type: Int, isLast: Boolean, payload: ByteArray) {
        val header0 = (type and 0x7F) or (if (isLast) 0x80 else 0)
        val len = payload.size
        out.write(header0)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(payload)
    }
}
