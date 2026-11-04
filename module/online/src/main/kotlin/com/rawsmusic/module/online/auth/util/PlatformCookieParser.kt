package com.rawsmusic.module.online.auth.util

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.auth.model.PlatformAccount
import com.rawsmusic.module.online.model.OnlinePlatform
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.regex.Pattern

/**
 * 平台 Cookie / Token 多格式解析与提取器。
 *
 * 移植并增强自 OrBitPlayer，支持：
 * 1. 抓包导出的 JSON 数组格式（如 EditThisCookie、Postman）: [{"name":"...", "value":"..."}]
 * 2. 键值 JSON 对象: {"uin":"...", "qm_keyst":"..."}
 * 3. 换行、分号、&、制表符、冒号等多分隔符键值对字符串
 * 4. 酷狗特有的复合参数（KuGou、KuGouPercent、kugou、kugou_info）二次 URL 解码展开
 * 5. Unicode 转义字符解码（如酷狗昵称中的 %uXXXX）
 * 6. 关键凭据（QQ 的 uin、qm_keyst、Q_H_L_*；酷狗的 token、userid、kg_mid 等）正则容错探测与补全
 */
object PlatformCookieParser {

    private const val TAG = "PlatformCookieParser"

    /**
     * 将 Unicode 转义（形如 %u4e2d%u6587 或 \u4e2d\u6587）解码为正常字符
     */
    fun decodeUnicodeEscape(input: String): String {
        return try {
            val unicodePercentRegex = Regex("%u([0-9a-fA-F]{4})")
            var text = unicodePercentRegex.replace(input) { m ->
                val hex = m.groupValues[1]
                hex.toInt(16).toChar().toString()
            }
            val unicodeSlashRegex = Regex("\\\\u([0-9a-fA-F]{4})")
            text = unicodeSlashRegex.replace(text) { m ->
                val hex = m.groupValues[1]
                hex.toInt(16).toChar().toString()
            }
            URLDecoder.decode(text, "UTF-8")
        } catch (_: Exception) {
            input
        }
    }

    /**
     * 解析各种复杂格式的 Cookie 文本为扁平 Map
     */
    fun parseCookieMap(input: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val text = input.trim()

        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                val jsonArray = JSONArray(text)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val name = obj.optString("name", obj.optString("key", ""))
                    val value = obj.optString("value", "")
                    if (name.isNotBlank()) {
                        map[name] = value
                    }
                }
                if (map.isNotEmpty()) return map
            } catch (_: Exception) {
                // 忽略非标准 JSON，继续走通用文本解析
            }
        } else if (text.startsWith("{") && text.endsWith("}")) {
            try {
                val jsonObj = JSONObject(text)
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = jsonObj.optString(key, "")
                }
                if (map.isNotEmpty()) return map
            } catch (_: Exception) {
                // 忽略非标准 JSON
            }
        }

        // 按行、分号、& 切割键值对
        val lines = text.split("\n", ";", "&")
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.contains("=")) {
                val equalIdx = trimmed.indexOf('=')
                val key = trimmed.substring(0, equalIdx).trim()
                val value = trimmed.substring(equalIdx + 1).trim()
                if (key.isNotEmpty()) {
                    map[key] = value
                }
            } else if (trimmed.contains("\t")) {
                val parts = trimmed.split("\t")
                if (parts.size >= 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    if (key.isNotEmpty()) {
                        map[key] = value
                    }
                }
            } else if (trimmed.contains(":")) {
                val colonIdx = trimmed.indexOf(':')
                val key = trimmed.substring(0, colonIdx).trim()
                val value = trimmed.substring(colonIdx + 1).trim()
                if (key.isNotEmpty()) {
                    map[key] = value
                }
            }
        }

        // 酷狗等平台复合 Cookie 二次展开
        val compoundKeys = listOf("KuGou", "KuGouPercent", "kugou", "kugou_info")
        for (ck in compoundKeys) {
            val encodedVal = map[ck]
            if (!encodedVal.isNullOrBlank()) {
                try {
                    val decoded = URLDecoder.decode(encodedVal, "UTF-8")
                    val subPairs = decoded.split("&", ";")
                    for (sub in subPairs) {
                        val idx = sub.indexOf('=')
                        if (idx > 0) {
                            val k = sub.substring(0, idx).trim()
                            val v = sub.substring(idx + 1).trim()
                            if (k.isNotEmpty() && !map.containsKey(k)) {
                                map[k] = v
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return map
    }

    /**
     * 根据平台提取有效账号模型并做字段标准化探测
     */
    fun parseToAccount(platform: OnlinePlatform, rawInput: String): Result<PlatformAccount> {
        val trimmedInput = rawInput.trim()
        if (trimmedInput.isBlank()) {
            return Result.failure(IllegalArgumentException("输入内容为空"))
        }

        val parsedMap = parseCookieMap(trimmedInput).toMutableMap()

        return when (platform) {
            OnlinePlatform.QQ -> parseQQAccount(trimmedInput, parsedMap)
            OnlinePlatform.KUGOU -> parseKugouAccount(trimmedInput, parsedMap)
            else -> Result.failure(IllegalStateException("暂不支持 ${platform.displayName} 的账号解析"))
        }
    }

    private fun parseQQAccount(rawInput: String, map: MutableMap<String, String>): Result<PlatformAccount> {
        // 1. 探测 Q_H_L_* 关键音乐 Token
        val keyPattern = Regex("(Q_H_L_[A-Za-z0-9_-]+)")
        val keyMatch = keyPattern.find(rawInput)
        if (keyMatch != null) {
            val extractedKey = keyMatch.value
            map["qm_keyst"] = extractedKey
            map["qqmusic_key"] = extractedKey
            map["psrf_musickey_id"] = extractedKey
            map["authst"] = extractedKey
        } else if (rawInput.startsWith("Q_H_L_") || (rawInput.length >= 32 && !rawInput.contains("=") && !rawInput.contains("\t") && !rawInput.contains("&"))) {
            map["qm_keyst"] = rawInput
            map["qqmusic_key"] = rawInput
            map["psrf_musickey_id"] = rawInput
            map["authst"] = rawInput
        }

        // 2. 探测 uin / login_uin / p_uin
        val uinPattern = Regex("(?:uin|p_uin|login_uin|userid|qq|用户|账号)[=:：\\s]*([0-9]{5,12})", RegexOption.IGNORE_CASE)
        val uinMatch = uinPattern.find(rawInput)
        if (uinMatch != null) {
            val extractedUin = uinMatch.groupValues[1]
            if (!map.containsKey("uin")) map["uin"] = extractedUin
            if (!map.containsKey("login_uin")) map["login_uin"] = extractedUin
        }

        var userId = map["uin"] ?: map["p_uin"] ?: map["login_uin"] ?: ""
        userId = userId.replace("^o0*".toRegex(), "")

        // 若仍未获取到，从文本中提取连续 5~11 位纯数字尝试作为 QQ 号
        if (userId.isBlank()) {
            val digitsPattern = Regex("\\b([1-9][0-9]{4,10})\\b")
            val digitsMatch = digitsPattern.find(rawInput)
            if (digitsMatch != null) {
                userId = digitsMatch.value
                map["uin"] = userId
            }
        }

        val hasAuthToken = map.containsKey("qm_keyst") || map.containsKey("qqmusic_key") ||
                map.containsKey("p_skey") || map.containsKey("skey") || map.containsKey("psrf_musickey_id")

        if (userId.isBlank() && !hasAuthToken) {
            return Result.failure(IllegalArgumentException("未在输入中检测到 uin 或 qm_keyst 凭据，请确认粘贴了完整 Cookie"))
        }

        if (userId.isBlank()) {
            userId = "qq_user"
        }

        val nickname = if (userId.matches(Regex("^\\d+$"))) "QQ 用户 $userId" else "QQ 音乐用户"
        val avatarUrl = if (userId.matches(Regex("^\\d+$"))) "https://q1.qlogo.cn/g?b=qq&nk=$userId&s=100" else ""

        val token = map["qm_keyst"] ?: map["qqmusic_key"] ?: map["psrf_musickey_id"] ?: map["skey"] ?: ""

        val account = PlatformAccount(
            platform = OnlinePlatform.QQ,
            userId = userId,
            nickname = nickname,
            avatarUrl = avatarUrl,
            isVip = false,
            vipLevel = "",
            cookies = map,
            tokens = if (token.isNotBlank()) mapOf("token" to token, "uin" to userId) else mapOf("uin" to userId),
            updatedAt = System.currentTimeMillis()
        )
        return Result.success(account)
    }

    private fun parseKugouAccount(rawInput: String, map: MutableMap<String, String>): Result<PlatformAccount> {
        var token = map["token"] ?: map["t"] ?: map["kg_token"] ?: map["login_token"] ?: map["user_token"] ?: map["KuGou"] ?: ""
        var userId = map["KugooID"] ?: map["KugouID"] ?: map["KugouId"] ?: map["userid"] ?: map["kg_mid"] ?: map["uid"] ?: map["mid"] ?: ""

        // 正则提取 token (16 位以上字母数字)
        if (token.isBlank()) {
            val tokenRegex = Regex("""(?:token|kg_token|login_token|user_token|\bt\b)["']?\s*[:=]\s*["']?([a-zA-Z0-9_\-]{16,})["']?""", RegexOption.IGNORE_CASE)
            val match = tokenRegex.find(rawInput)
            if (match != null) {
                token = match.groupValues[1]
                map["token"] = token
            }
        }

        // 正则提取 userid
        if (userId.isBlank()) {
            val userRegex = Regex("""(?:userid|KugooID|KugouID|KugouId|kg_mid|uid|mid)["']?\s*[:=]\s*["']?(\d{4,15})["']?""", RegexOption.IGNORE_CASE)
            val match = userRegex.find(rawInput)
            if (match != null) {
                userId = match.groupValues[1]
                map["userid"] = userId
            }
        }

        // 若输入是单行无空格 16~64 位字符，当作 token
        if (token.isBlank() && rawInput.length in 16..64 && !rawInput.contains("=") && !rawInput.contains(";") && !rawInput.contains("&") && !rawInput.contains(" ")) {
            token = rawInput
            map["token"] = token
        }

        if (token.isBlank() && userId.isBlank()) {
            return Result.failure(IllegalArgumentException("未在输入中检测到 token 或 userid 凭据"))
        }

        if (userId.isBlank()) userId = "0"
        if (token.isNotBlank()) {
            map["token"] = token
            map["t"] = token
        }
        if (userId.isNotBlank() && userId != "0") {
            map["userid"] = userId
            map["KugouID"] = userId
            map["KugooID"] = userId
        }

        val rawNick = map["NickName"] ?: map["UserName"] ?: map["nickname"] ?: ""
        val decodedNick = if (rawNick.isNotBlank()) decodeUnicodeEscape(rawNick).trim() else ""
        val nickname = if (decodedNick.isNotBlank()) decodedNick else "酷狗用户_$userId"

        val account = PlatformAccount(
            platform = OnlinePlatform.KUGOU,
            userId = userId,
            nickname = nickname,
            avatarUrl = "",
            isVip = false,
            vipLevel = "",
            cookies = map,
            tokens = mapOf("token" to token, "t" to token, "userid" to userId),
            updatedAt = System.currentTimeMillis()
        )
        return Result.success(account)
    }
}

