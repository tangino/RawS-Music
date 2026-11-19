package com.rawsmusic.module.data.source.kuwo

import kotlin.math.abs
import kotlin.math.tan

/**
 * QMCv2 音频流式解密器（.mflac / .mgg）。
 *
 * 完整流程：
 * 1. ekey（Base64）解码；若带 `QQMusic EncV2,Key:` 前缀，先做两轮 TEA 解密 + 再次 Base64 解码；
 * 2. 前 8 字节与 simpleKey 交织得到 TEA 密钥，对剩余部分做腾讯 TEA-CBC 解密，拼接得到最终音频密钥；
 * 3. 音频密钥长度 > 300 采用分段 RC4 模式，否则采用带位旋转的 Map 模式。
 *
 * 旧实现直接把 ekey 字符串当作密钥，且缺少位旋转与分段 RC4，
 * 导致离线保存的文件仍是密文（无 fLaC 文件头），既无法播放也无法写入内嵌封面。
 */
class QmcCipher private constructor(private val key: ByteArray) {

    private val n = key.size
    private val isRc4 = n > 300

    // RC4 模式预计算：初始 S 盒与哈希基数
    private val rc4Box: ByteArray? = if (isRc4) buildRc4Box() else null
    private val rc4Hash: Long = if (isRc4) computeHashBase() else 0L

    /**
     * 流式原地解密数据块。
     * @param data 待解密的数据字节数组
     * @param offset 当前数据块在整个音频文件中的起始偏移字节位置
     * @param length 实际需解密的字节长度
     */
    fun decrypt(data: ByteArray, offset: Long, length: Int = data.size) {
        if (n == 0 || length <= 0) return
        if (isRc4) decryptRc4(data, offset, length) else decryptMap(data, offset, length)
    }

    // ───────────────────────── Map 模式 ─────────────────────────

    private fun decryptMap(data: ByteArray, offset: Long, length: Int) {
        for (i in 0 until length) {
            data[i] = (data[i].toInt() xor mapMask(offset + i)).toByte()
        }
    }

    private fun mapMask(rawOffset: Long): Int {
        val offset = if (rawOffset > 0x7FFF) rawOffset % 0x7FFF else rawOffset
        val idx = ((offset * offset + 71214) % n).toInt()
        val value = key[idx].toInt() and 0xFF
        val rotate = ((idx and 0x7) + 4) % 8
        return ((value shl rotate) or (value ushr (8 - rotate))) and 0xFF
    }

    // ───────────────────────── RC4 模式 ─────────────────────────

    private fun buildRc4Box(): ByteArray {
        val box = ByteArray(n) { it.toByte() }
        var j = 0
        for (i in 0 until n) {
            j = (j + (box[i].toInt() and 0xFF) + (key[i % n].toInt() and 0xFF)) % n
            val tmp = box[i]
            box[i] = box[j]
            box[j] = tmp
        }
        return box
    }

    private fun computeHashBase(): Long {
        var hash = 1L
        for (i in 0 until n) {
            val v = (key[i].toInt() and 0xFF).toLong()
            if (v == 0L) continue
            val next = (hash * v) and 0xFFFFFFFFL
            if (next == 0L || next <= hash) break
            hash = next
        }
        return hash
    }

    private fun segmentKey(id: Long): Long {
        val seed = key[(id % n).toInt()].toInt() and 0xFF
        if (seed == 0) return 0L
        return (rc4Hash.toDouble() / ((id + 1).toDouble() * seed.toDouble()) * 100.0).toLong()
    }

    private fun decryptRc4(data: ByteArray, startOffset: Long, length: Int) {
        var offset = startOffset
        var processed = 0
        var remaining = length

        if (offset < FIRST_SEGMENT_SIZE) {
            val block = minOf(remaining.toLong(), FIRST_SEGMENT_SIZE - offset).toInt()
            for (i in 0 until block) {
                val mask = key[(segmentKey(offset + i) % n).toInt()]
                data[processed + i] = (data[processed + i].toInt() xor mask.toInt()).toByte()
            }
            offset += block
            processed += block
            remaining -= block
            if (remaining == 0) return
        }

        if (offset % SEGMENT_SIZE != 0L) {
            val block = minOf(remaining.toLong(), SEGMENT_SIZE - offset % SEGMENT_SIZE).toInt()
            encryptSegment(data, processed, block, offset)
            offset += block
            processed += block
            remaining -= block
            if (remaining == 0) return
        }

        while (remaining > SEGMENT_SIZE) {
            encryptSegment(data, processed, SEGMENT_SIZE.toInt(), offset)
            offset += SEGMENT_SIZE
            processed += SEGMENT_SIZE.toInt()
            remaining -= SEGMENT_SIZE.toInt()
        }
        if (remaining > 0) {
            encryptSegment(data, processed, remaining, offset)
        }
    }

    private fun encryptSegment(data: ByteArray, start: Int, length: Int, offset: Long) {
        val box = rc4Box!!.copyOf()
        var j = 0
        var k = 0
        val skip = (offset % SEGMENT_SIZE).toInt() + (segmentKey(offset / SEGMENT_SIZE) and 0x1FFL).toInt()
        var i = -skip
        while (i < length) {
            j = (j + 1) % n
            k = ((box[j].toInt() and 0xFF) + k) % n
            val tmp = box[j]
            box[j] = box[k]
            box[k] = tmp
            if (i >= 0) {
                val idx = ((box[j].toInt() and 0xFF) + (box[k].toInt() and 0xFF)) % n
                data[start + i] = (data[start + i].toInt() xor box[idx].toInt()).toByte()
            }
            i++
        }
    }

    companion object {
        private const val FIRST_SEGMENT_SIZE = 128L
        private const val SEGMENT_SIZE = 5120L
        private const val V2_PREFIX = "QQMusic EncV2,Key:"

        private val V2_KEY_1 = byteArrayOf(
            0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
            0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28,
        )
        private val V2_KEY_2 = byteArrayOf(
            0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
            0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54,
        )

        /** 由 ekey（Base64 文本）派生音频密钥并构造解密器；派生失败返回 null。 */
        fun createFromEkey(ekey: String): QmcCipher? {
            if (ekey.isBlank()) return null
            val derived = runCatching { deriveKey(ekey.trim()) }.getOrNull() ?: return null
            if (derived.isEmpty()) return null
            return QmcCipher(derived)
        }

        internal fun fromRawKey(key: ByteArray): QmcCipher = QmcCipher(key)

        internal fun deriveKey(ekey: String): ByteArray? {
            var raw = KuwoBase64.decode(ekey)
            val prefix = V2_PREFIX.toByteArray(Charsets.US_ASCII)
            if (raw.size > prefix.size && raw.copyOfRange(0, prefix.size).contentEquals(prefix)) {
                raw = deriveKeyV2(raw.copyOfRange(prefix.size, raw.size)) ?: return null
            }
            return deriveKeyV1(raw)
        }

        private fun deriveKeyV1(raw: ByteArray): ByteArray? {
            if (raw.size < 16) return null
            // simpleKey[i] = |tan(106 + i * 0.1)| * 100，与已知常量表 69 56 46 38 2B 20 15 0B 一致
            val simpleKey = ByteArray(8) { i ->
                (abs(tan(106.0 + i * 0.1)) * 100.0).toInt().toByte()
            }
            val teaKey = ByteArray(16)
            for (i in 0 until 8) {
                teaKey[i shl 1] = simpleKey[i]
                teaKey[(i shl 1) + 1] = raw[i]
            }
            val decrypted = decryptTencentTea(raw.copyOfRange(8, raw.size), teaKey) ?: return null
            return raw.copyOfRange(0, 8) + decrypted
        }

        private fun deriveKeyV2(raw: ByteArray): ByteArray? {
            val step1 = decryptTencentTea(raw, V2_KEY_1) ?: return null
            val step2 = decryptTencentTea(step1, V2_KEY_2) ?: return null
            return KuwoBase64.decode(step2)
        }

        /** 腾讯 TEA-CBC 解密（16 轮，2 字节盐 + 7 字节零校验）。 */
        private fun decryptTencentTea(input: ByteArray, key: ByteArray): ByteArray? {
            val saltLen = 2
            val zeroLen = 7
            if (input.size % 8 != 0 || input.size < 16) return null
            val k = IntArray(4) { readIntBE(key, it * 4) }

            var dest = teaDecryptBlock(input.copyOfRange(0, 8), k)
            val padLen = dest[0].toInt() and 0x7
            val outLen = input.size - 1 - padLen - saltLen - zeroLen
            if (outLen < 0) return null
            val out = ByteArray(outLen)

            var ivPrev = ByteArray(8)
            var ivCur = input.copyOfRange(0, 8)
            var inPos = 8
            var destIdx = 1 + padLen

            fun cryptBlock() {
                ivPrev = ivCur
                ivCur = input.copyOfRange(inPos, inPos + 8)
                val mixed = ByteArray(8) { (dest[it].toInt() xor input[inPos + it].toInt()).toByte() }
                dest = teaDecryptBlock(mixed, k)
                inPos += 8
                destIdx = 0
            }

            var salt = 1
            while (salt <= saltLen) {
                if (destIdx < 8) {
                    destIdx++
                    salt++
                } else {
                    if (inPos + 8 > input.size) return null
                    cryptBlock()
                }
            }

            var outPos = 0
            while (outPos < outLen) {
                if (destIdx < 8) {
                    out[outPos] = (dest[destIdx].toInt() xor ivPrev[destIdx].toInt()).toByte()
                    destIdx++
                    outPos++
                } else {
                    if (inPos + 8 > input.size) return null
                    cryptBlock()
                }
            }

            // 校验尾部 7 字节零填充，防止密钥不匹配产生静默乱码
            var zeros = 0
            while (zeros < zeroLen) {
                if (destIdx < 8) {
                    if (((dest[destIdx].toInt() xor ivPrev[destIdx].toInt()) and 0xFF) != 0) return null
                    destIdx++
                    zeros++
                } else {
                    if (inPos + 8 > input.size) return null
                    cryptBlock()
                }
            }
            return out
        }

        private fun teaDecryptBlock(block: ByteArray, k: IntArray): ByteArray {
            var v0 = readIntBE(block, 0)
            var v1 = readIntBE(block, 4)
            val delta = 0x9E3779B9.toInt()
            var sum = delta * 16
            repeat(16) {
                v1 -= ((v0 shl 4) + k[2]) xor (v0 + sum) xor ((v0 ushr 5) + k[3])
                v0 -= ((v1 shl 4) + k[0]) xor (v1 + sum) xor ((v1 ushr 5) + k[1])
                sum -= delta
            }
            val out = ByteArray(8)
            writeIntBE(out, 0, v0)
            writeIntBE(out, 4, v1)
            return out
        }

        private fun readIntBE(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or
                ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or
                (b[off + 3].toInt() and 0xFF)

        private fun writeIntBE(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 24).toByte()
            b[off + 1] = (v ushr 16).toByte()
            b[off + 2] = (v ushr 8).toByte()
            b[off + 3] = v.toByte()
        }
    }
}
