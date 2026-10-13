package com.rawsmusic.module.online.auth.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.rawsmusic.core.common.utils.AppLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 平台账号凭据（cookie / token）的落盘加密。
 *
 * ## 为什么必须加密
 *
 * OrBit 把 QQ / 酷狗的 cookie 与 token **明文**写进 `SharedPreferences`。那几个 cookie 等价于
 * 平台账号的完整登录态（能直接拿去调平台接口），明文落盘意味着 root 设备、备份文件、
 * 或任何能读应用私有目录的东西都能直接盗用账号。所以决策 D7 要求：
 * **MMKV 里只存密文**，密钥由 Android Keystore 保管（密钥本身不出安全硬件/系统密钥库）。
 *
 * ## 设计要点
 *
 * - **AES-GCM**（带认证的加密）：密文被篡改会在解密时抛异常，而不是悄悄解出垃圾。
 * - 每次加密都生成**新的随机 IV**（12 字节），与密文拼接后一起 base64 存进 MMKV。
 *   复用 IV 会让 GCM 彻底失去安全性，所以绝不能固定 IV。
 * - 解密失败**返回 null 而不是抛异常**：Keystore 密钥可能在「恢复出厂/换机恢复备份/清除凭据」
 *   后失效，那时旧密文解不开。这种情况下应当把用户当成**未登录**处理并让他重新扫码，
 *   不能让应用在启动路径上崩溃。
 */
object PlatformCredentialCipher {

    private const val TAG = "PlatformAuth"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /** 带版本号，便于将来轮换密钥而不与旧别名冲突。 */
    private const val KEY_ALIAS = "rawsmusic_platform_auth_v1"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    /** 加密。失败返回 null（调用方应放弃写入，绝不退化成写明文）。 */
    fun encrypt(plainText: String): String? {
        if (plainText.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            // 格式：base64(IV || 密文)
            val combined = ByteArray(iv.size + cipherBytes.size)
            iv.copyInto(combined, 0)
            cipherBytes.copyInto(combined, iv.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (error: Throwable) {
            AppLogger.e(TAG, "凭据加密失败", error)
            null
        }
    }

    /**
     * 解密。
     *
     * @return 明文；密文为空、格式非法、密钥已失效或校验不通过时返回 null。
     */
    fun decrypt(encrypted: String): String? {
        if (encrypted.isEmpty()) return null
        return try {
            val combined = Base64.decode(encrypted, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH_BYTES) return null
            val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
            val cipherBytes = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (error: Throwable) {
            // 不往上抛：见类注释 —— 解不开就当作未登录，让用户重新扫码。
            AppLogger.w(TAG, "凭据解密失败（密钥可能已失效），将按未登录处理：${error.message}")
            null
        }
    }

    /**
     * 删除 Keystore 密钥。删掉之后**所有既有密文都再也解不开**，
     * 因此只在「清除全部平台凭据」这类明确操作里调用。
     */
    fun reset() {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (error: Throwable) {
            AppLogger.e(TAG, "删除平台凭据密钥失败", error)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户认证：登录态要能在后台静默使用，否则每次请求都要解锁屏幕。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
