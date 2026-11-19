package com.rawsmusic.module.data.source.kuwo

/**
 * 纯 Kotlin 实现的宽松 Base64 解码器。
 *
 * 不依赖 android.util.Base64（minSdk 24 下也无法使用 java.util.Base64），
 * 以便酷我 ekey / QMC 密钥派生逻辑可以在 JVM 单元测试中直接验证。
 * 自动忽略空白字符，兼容 URL 安全字符集与缺失补齐符的输入。
 */
internal object KuwoBase64 {

    private val DECODE_TABLE = IntArray(128) { -1 }.apply {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        alphabet.forEachIndexed { index, c -> this[c.code] = index }
        this['-'.code] = 62
        this['_'.code] = 63
    }

    fun decode(input: String): ByteArray = decode(input.toByteArray(Charsets.ISO_8859_1))

    fun decode(input: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(input.size * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (b in input) {
            val c = b.toInt() and 0xFF
            if (c == '='.code) break
            if (c >= 128) continue
            val value = DECODE_TABLE[c]
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
