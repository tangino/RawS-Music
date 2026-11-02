package com.rawsmusic.module.data.source.kuwo

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 酷我音乐自定义 DES（kwDES）加解密实现。
 *
 * 酷我音乐客户端使用一种非标准的 DES 变体：
 * - 采用 LSB-first 位序（与标准 DES 相反）；
 * - S 盒采用字节对齐分组；
 * - 默认内置密钥为 "ylzsxkwm"。
 */
object KwDes {

    val DEFAULT_KEY: ByteArray = "ylzsxkwm".toByteArray(Charsets.US_ASCII)

    // E 扩展置换表（48 位输出，表中保留 -1 占位，共 64 长度）
    private val EXPANSION = shortArrayOf(
        31, 0, 1, 2, 3, 4, -1, -1, 3, 4, 5, 6, 7, 8, -1, -1, 7, 8, 9, 10, 11, 12, -1, -1, 11, 12, 13,
        14, 15, 16, -1, -1, 15, 16, 17, 18, 19, 20, -1, -1, 19, 20, 21, 22, 23, 24, -1, -1, 23, 24, 25,
        26, 27, 28, -1, -1, 27, 28, 29, 30, 31, 30, -1, -1
    )

    // 初始置换 IP
    private val INITIAL_PERMUTATION = shortArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29, 21, 13, 5, 63,
        55, 47, 39, 31, 23, 15, 7, 56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2, 60, 52,
        44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6
    )

    // 逆初始置换 IP-1
    private val INVERSE_PERMUTATION = shortArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29,
        36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24
    )

    // 每轮左移位数
    private val SHIFT_SCHEDULE = byteArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    // 左移掩码辅助
    private val SHIFT_MASKS = longArrayOf(0L, 0x100001L, 0x300003L)

    // P 置换
    private val PERMUTATION = shortArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9, 1, 7, 23, 13, 31, 26, 2, 8, 18, 12,
        29, 5, 21, 10, 3, 24
    )

    // PC-1 置换
    private val PC1 = shortArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59,
        51, 43, 35, 62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28,
        20, 12, 4, 27, 19, 11, 3
    )

    // PC-2 置换
    private val PC2 = shortArrayOf(
        13, 16, 10, 23, 0, 4, -1, -1, 2, 27, 14, 5, 20, 9, -1, -1, 22, 18, 11, 3, 25, 7, -1, -1, 15, 6,
        26, 19, 12, 1, -1, -1, 40, 51, 30, 36, 46, 54, -1, -1, 29, 39, 50, 44, 32, 47, -1, -1, 43, 48,
        38, 55, 33, 52, -1, -1, 45, 41, 49, 35, 28, 31, -1, -1
    )

    // S-box 矩阵（8 组 × 64 字节）
    private val S_BOXES = arrayOf(
        byteArrayOf(
            14, 4, 3, 15, 2, 13, 5, 3, 13, 14, 6, 9, 11, 2, 0, 5, 4, 1, 10, 12, 15, 6, 9, 10, 1, 8, 12,
            7, 8, 11, 7, 0, 0, 15, 10, 5, 14, 4, 9, 10, 7, 8, 12, 3, 13, 1, 3, 6, 15, 12, 6, 11, 2, 9,
            5, 0, 4, 2, 11, 14, 1, 7, 8, 13
        ),
        byteArrayOf(
            15, 0, 9, 5, 6, 10, 12, 9, 8, 7, 2, 12, 3, 13, 5, 2, 1, 14, 7, 8, 11, 4, 0, 3, 14, 11, 13,
            6, 4, 1, 10, 15, 3, 13, 12, 11, 15, 3, 6, 0, 4, 10, 1, 7, 8, 4, 11, 14, 13, 8, 0, 6, 2, 15,
            9, 5, 7, 1, 10, 12, 14, 2, 5, 9
        ),
        byteArrayOf(
            10, 13, 1, 11, 6, 8, 11, 5, 9, 4, 12, 2, 15, 3, 2, 14, 0, 6, 13, 1, 3, 15, 4, 10, 14, 9, 7,
            12, 5, 0, 8, 7, 13, 1, 2, 4, 3, 6, 12, 11, 0, 13, 5, 14, 6, 8, 15, 2, 7, 10, 8, 15, 4, 9,
            11, 5, 9, 0, 14, 3, 10, 7, 1, 12
        ),
        byteArrayOf(
            7, 10, 1, 15, 0, 12, 11, 5, 14, 9, 8, 3, 9, 7, 4, 8, 13, 6, 2, 1, 6, 11, 12, 2, 3, 0, 5,
            14, 10, 13, 15, 4, 13, 3, 4, 9, 6, 10, 1, 12, 11, 0, 2, 5, 0, 13, 14, 2, 8, 15, 7, 4, 15,
            1, 10, 7, 5, 6, 12, 11, 3, 8, 9, 14
        ),
        byteArrayOf(
            2, 4, 8, 15, 7, 10, 13, 6, 4, 1, 3, 12, 11, 7, 14, 0, 12, 2, 5, 9, 10, 13, 0, 3, 1, 11, 15,
            5, 6, 8, 9, 14, 14, 11, 5, 6, 4, 1, 3, 10, 2, 12, 15, 0, 13, 2, 8, 5, 11, 8, 0, 15, 7, 14,
            9, 4, 12, 7, 10, 9, 1, 13, 6, 3
        ),
        byteArrayOf(
            12, 9, 0, 7, 9, 2, 14, 1, 10, 15, 3, 4, 6, 12, 5, 11, 1, 14, 13, 0, 2, 8, 7, 13, 15, 5, 4,
            10, 8, 3, 11, 6, 10, 4, 6, 11, 7, 9, 0, 6, 4, 2, 13, 1, 9, 15, 3, 8, 15, 3, 1, 14, 12, 5,
            11, 0, 2, 12, 14, 7, 5, 10, 8, 13
        ),
        byteArrayOf(
            4, 1, 3, 10, 15, 12, 5, 0, 2, 11, 9, 6, 8, 7, 6, 9, 11, 4, 12, 15, 0, 3, 10, 5, 14, 13, 7,
            8, 13, 14, 1, 2, 13, 6, 14, 9, 4, 1, 2, 14, 11, 13, 5, 0, 1, 10, 8, 3, 0, 11, 3, 5, 9, 4,
            15, 2, 7, 8, 12, 15, 10, 7, 6, 12
        ),
        byteArrayOf(
            13, 7, 10, 0, 6, 9, 5, 15, 8, 4, 3, 10, 11, 14, 12, 5, 2, 11, 9, 6, 15, 12, 0, 3, 4, 1, 14,
            13, 1, 2, 7, 8, 1, 2, 12, 15, 10, 4, 0, 3, 13, 14, 6, 9, 7, 8, 9, 6, 15, 1, 5, 12, 3, 10,
            14, 5, 8, 7, 11, 0, 4, 13, 2, 11
        )
    )

    private fun permute(data: Long, mapper: ShortArray): Long {
        var ret = 0L
        for (i in mapper.indices) {
            val v = mapper[i].toInt()
            if (v < 0) continue
            if ((data and (1L shl v)) != 0L) {
                ret = ret or (1L shl i)
            }
        }
        return ret
    }

    private fun deriveKeys(keyInt: Long, roundKeys: LongArray) {
        var key56 = permute(keyInt, PC1)
        for (i in 0 until 16) {
            val shift = SHIFT_SCHEDULE[i].toInt()
            val mask = SHIFT_MASKS[shift]
            key56 = (((key56 and mask) shl (28 - shift)) or ((key56 and mask.inv()) ushr shift))
            roundKeys[i] = permute(key56, PC2)
        }
    }

    private fun f(halfBlock: Long, subkey: Long): Long {
        val expanded = permute(halfBlock, EXPANSION)
        val mixed = expanded xor subkey
        var sOutput = 0L
        for (i in 7 downTo 0) {
            val sixBits = ((mixed ushr (i * 8)) and 0x3FL).toInt()
            val sVal = S_BOXES[i][sixBits].toLong() and 0xFFL
            sOutput = (sOutput shl 4) or sVal
        }
        return permute(sOutput, PERMUTATION)
    }

    private fun encryptBlock(block: Long, roundKeys: LongArray): Long {
        val permuted = permute(block, INITIAL_PERMUTATION)
        var left = permuted and 0xFFFFFFFFL
        var right = (permuted ushr 32) and 0xFFFFFFFFL

        for (i in 0 until 16) {
            val subkey = roundKeys[i]
            val newRight = left xor f(right, subkey)
            left = right
            right = newRight
        }

        val combined = (left shl 32) or right
        return permute(combined, INVERSE_PERMUTATION)
    }

    fun desCrypt(data: ByteArray, key: ByteArray, isDecrypt: Boolean): ByteArray {
        val keyBuffer = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
        val keyInt = keyBuffer.long
        val roundKeys = LongArray(16)
        deriveKeys(keyInt, roundKeys)

        if (isDecrypt) {
            roundKeys.reverse()
        }

        val blockCount = (data.size + 7) / 8
        val output = ByteArray(blockCount * 8)
        val chunkBytes = ByteArray(8)

        for (i in 0 until blockCount) {
            chunkBytes.fill(0)
            val offset = i * 8
            val len = minOf(8, data.size - offset)
            System.arraycopy(data, offset, chunkBytes, 0, len)

            val blockBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)
            val blockInt = blockBuffer.long
            val processed = encryptBlock(blockInt, roundKeys)

            val outBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            outBuffer.putLong(processed)
            System.arraycopy(outBuffer.array(), 0, output, offset, 8)
        }
        return output
    }

    /**
     * kwDES 解密 Base64 编码的密文。
     */
    fun base64Decrypt(msg: String, key: ByteArray = DEFAULT_KEY): String {
        val decoded = Base64.decode(msg.trim(), Base64.DEFAULT)
        val decrypted = desCrypt(decoded, key, isDecrypt = true)

        var end = decrypted.size
        while (end > 0 && decrypted[end - 1] == 0.toByte()) {
            end--
        }
        return String(decrypted, 0, end, Charsets.UTF_8)
    }

    /**
     * 解密酷我返回的 ekey 字段：Base64 解码 -> kwDES 解密 -> 去除 user 前缀。
     */
    fun decryptEkey(rawEkey: String, user: String, key: ByteArray = DEFAULT_KEY): String {
        val decrypted = base64Decrypt(rawEkey, key)
        return if (decrypted.startsWith(user)) {
            decrypted.substring(user.length)
        } else {
            decrypted
        }
    }
}

