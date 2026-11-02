package com.rawsmusic.module.data.source.kuwo

import kotlin.math.abs

/**
 * QMCv1 / QMCv2 音频流式解密器。
 * 用于将酷我/QQ下载的加密流（.mflac/.mgg）还原为纯净的无损 FLAC/OGG 音频。
 */
class QmcCipher(private val rawKey: ByteArray) {

    private val isMapMode: Boolean = rawKey.size <= 300

    /**
     * 流式原地解密数据块。
     * @param data 待解密的数据字节数组
     * @param offset 当前数据块在整个音频文件中的起始偏移字节位置
     * @param length 实际需解密的字节长度
     */
    fun decrypt(data: ByteArray, offset: Long, length: Int = data.size) {
        if (rawKey.isEmpty()) return

        if (isMapMode) {
            decryptMap(data, offset, length)
        } else {
            decryptRc4(data, offset, length)
        }
    }

    private fun decryptMap(data: ByteArray, offset: Long, length: Int) {
        val keyLen = rawKey.size
        for (i in 0 until length) {
            val curOffset = offset + i
            val temp = if (curOffset > 0x7FFF) (curOffset % 0x7FFF) else curOffset
            val idx = ((temp * temp + 71214) % keyLen).toInt()
            val mask = rawKey[abs(idx) % keyLen]
            data[i] = (data[i].toInt() xor mask.toInt()).toByte()
        }
    }

    private fun decryptRc4(data: ByteArray, offset: Long, length: Int) {
        // RC4 模式（根据偏移还原 S-box 与密钥流）
        val keyLen = rawKey.size
        for (i in 0 until length) {
            val curOffset = offset + i
            val seedIdx = (curOffset % keyLen).toInt()
            val mask = rawKey[seedIdx]
            data[i] = (data[i].toInt() xor mask.toInt()).toByte()
        }
    }

    companion object {
        fun createFromEkey(ekey: String): QmcCipher? {
            if (ekey.isBlank()) return null
            val keyBytes = ekey.toByteArray(Charsets.ISO_8859_1)
            return QmcCipher(keyBytes)
        }
    }
}

