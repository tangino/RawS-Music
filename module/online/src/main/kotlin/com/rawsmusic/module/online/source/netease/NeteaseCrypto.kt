package com.rawsmusic.module.online.source.netease

import android.util.Base64
import java.math.BigInteger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云音乐 weapi / linuxapi 协议加解密核心工具类
 * 参考开源 NeteaseCloudMusicApi 与落雪音乐加密实现
 */
object NeteaseCrypto {

    private const val IV = "0102030405060708"
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val RSA_PUBLIC_KEY = "010001"
    private const val RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f5613acae54f" +
                "638b47d6cb30e5732a5aa4d69d71e3f3bb6f8166f78439b78b9c615792e02385ea5436ac6500c70e4e4b8371034108f4d5a41223d2b7514c4fb9c494" +
                "777f98c0b1702ec14101"

    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /**
     * 生成 16 位随机密钥
     */
    private fun createSecretKey(): String {
        val random = SecureRandom()
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            val idx = random.nextInt(BASE62.length)
            sb.append(BASE62[idx])
        }
        return sb.toString()
    }

    /**
     * AES-128-CBC 加密
     */
    private fun aesEncrypt(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return try {
            java.util.Base64.getEncoder().encodeToString(encrypted)
        } catch (e: Throwable) {
            android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        }
    }

    /**
     * RSA 1024 加密生成 encSecKey
     */
    private fun rsaEncrypt(secretKey: String): String {
        val reversed = secretKey.reversed()
        val biText = BigInteger(1, reversed.toByteArray(Charsets.UTF_8))
        val biPubKey = BigInteger(RSA_PUBLIC_KEY, 16)
        val biModulus = BigInteger(RSA_MODULUS, 16)
        val biEncrypted = biText.modPow(biPubKey, biModulus)
        var hex = biEncrypted.toString(16)
        while (hex.length < 256) {
            hex = "0$hex"
        }
        return hex
    }

    /**
     * weapi 加密
     * @param jsonText 待加密的 JSON 字符串
     * @return Pair<params, encSecKey>
     */
    fun weapi(jsonText: String): Pair<String, String> {
        val secretKey = createSecretKey()
        // 第一次使用预设密钥加密
        val enc1 = aesEncrypt(jsonText, PRESET_KEY)
        // 第二次使用 16 位随机密钥加密
        val params = aesEncrypt(enc1, secretKey)
        // 使用 RSA 加密 16 位随机密钥
        val encSecKey = rsaEncrypt(secretKey)
        return Pair(params, encSecKey)
    }
}
