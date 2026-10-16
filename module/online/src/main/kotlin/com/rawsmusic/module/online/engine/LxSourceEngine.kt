package com.rawsmusic.module.online.engine

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.rawsmusic.core.common.utils.AppLogger
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 洛雪自定义源 (LX Music Custom Source) QuickJS 沙箱引擎
 * 移植自 OrBitPlayer，在当前主进程的独立单线程上微秒级执行 JS，无跨进程 IPC 开销。
 */
class LxSourceEngine(
    private val context: Context,
    private val scriptContent: String,
    val scriptId: String = "builtin_lx_script",
    val scriptName: String = "洛雪音源脚本",
) {
    companion object {
        private const val TAG = "LxSourceEngine"
        private var isQuickJsInitialized = false

        fun initQuickJsOnce() {
            if (!isQuickJsInitialized) {
                try {
                    System.loadLibrary("quickjs-android-wrapper")
                    isQuickJsInitialized = true
                    AppLogger.i(TAG, "quickjs-android-wrapper loaded successfully")
                } catch (e: Throwable) {
                    try {
                        System.loadLibrary("quickjs-wrapper")
                        isQuickJsInitialized = true
                        AppLogger.i(TAG, "quickjs-wrapper loaded successfully")
                    } catch (e2: Throwable) {
                        AppLogger.e(TAG, "QuickJS native library load failed", e2)
                    }
                }
            }
        }
    }

    private val runtimeThread = HandlerThread("LxSourceRuntime-$scriptId").apply { start() }
    private val runtimeHandler = Handler(runtimeThread.looper)
    private var quickJsContext: QuickJSContext? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cookieStore = ConcurrentHashMap<String, String>()
    private val runningHttpCalls = ConcurrentHashMap<String, Call>()
    private val pendingRequests = ConcurrentHashMap<String, RequestFuture>()

    private val initLatch = CountDownLatch(1)
    private var isInitialized = false
    private var initError: String? = null
    var supportedSources: JSONObject = JSONObject()
        private set

    private data class RequestFuture(
        val latch: CountDownLatch = CountDownLatch(1),
        var isSuccess: Boolean = false,
        var resultJson: JSONObject? = null,
        var rawResult: Any? = null,
        var errorMsg: String? = null,
    )

    init {
        initQuickJsOnce()
        runtimeHandler.post {
            setupRuntime()
        }
    }

    private fun setupRuntime() {
        try {
            val qjs = QuickJSContext.create()
            quickJsContext = qjs

            // 1. 注册原生加解密与计时函数
            registerNativeCryptoFunctions(qjs)

            // 2. 注册原生核心消息分发桥接
            qjs.globalObject.setProperty("__lx_native_call__", JSCallFunction { args ->
                val action = args.getOrNull(1)?.toString() ?: ""
                val payloadStr = args.getOrNull(2)?.toString() ?: ""
                handleNativeCall(action, payloadStr)
                null
            })

            // 3. 加载 user-api-preload.js
            val preloadJs = loadPreloadJs()
            if (preloadJs.isBlank()) {
                throw IllegalStateException("未找到 assets/script/user-api-preload.js 预加载脚本")
            }
            qjs.evaluate(preloadJs, "user-api-preload.js")

            // 4. 调用 globalThis.lx_setup(...) 注入用户脚本环境
            val setupFunction = qjs.globalObject.getJSFunction("lx_setup")
            if (setupFunction != null) {
                setupFunction.call(
                    scriptId,
                    scriptId,
                    scriptName,
                    "RawS-Music LX Engine",
                    "2.0.0",
                    "RawS",
                    "",
                    scriptContent,
                )
            }

            // 5. 执行音源脚本代码
            qjs.evaluate(scriptContent, "$scriptName.js")
            AppLogger.i(TAG, "Script evaluation completed for $scriptName, awaiting inited event...")
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Failed to setup runtime for $scriptName", e)
            initError = e.message ?: "运行时初始化异常"
            initLatch.countDown()
        }
    }

    private fun loadPreloadJs(): String {
        return try {
            context.assets.open("script/user-api-preload.js").bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading user-api-preload.js from assets", e)
            ""
        }
    }

    private fun handleNativeCall(action: String, payloadStr: String) {
        try {
            val json = if (payloadStr.startsWith("{") || payloadStr.startsWith("[")) {
                JSONObject(payloadStr)
            } else {
                null
            }

            when (action) {
                "init" -> {
                    val status = json?.optBoolean("status", false) ?: false
                    if (status) {
                        isInitialized = true
                        val info = json?.optJSONObject("info")
                        supportedSources = info?.optJSONObject("sources") ?: JSONObject()
                        AppLogger.i(TAG, "Source script $scriptName inited successfully. Sources: $supportedSources")
                    } else {
                        initError = json?.optString("errorMessage", "音源脚本初始化失败")
                        AppLogger.w(TAG, "Source script $scriptName inited with error: $initError")
                    }
                    initLatch.countDown()
                }
                "request" -> {
                    if (json != null) {
                        executeHttpRequest(json)
                    }
                }
                "cancelRequest" -> {
                    val key = payloadStr.trim('"', ' ')
                    runningHttpCalls.remove(key)?.cancel()
                }
                "response" -> {
                    if (json != null) {
                        val key = json.optString("requestKey", "")
                        val future = pendingRequests[key]
                        if (future != null) {
                            future.isSuccess = json.optBoolean("status", false)
                            future.resultJson = json.optJSONObject("result")
                            future.rawResult = json.opt("result")
                            future.errorMsg = if (json.has("errorMessage")) json.optString("errorMessage") else null
                            future.latch.countDown()
                        }
                    }
                }
                "log" -> {
                    AppLogger.d("$TAG-JS", payloadStr)
                }
            }
        } catch (e: Throwable) {
            AppLogger.w(TAG, "Error handling native call action: $action", e)
        }
    }

    private fun executeHttpRequest(reqJson: JSONObject) {
        val requestKey = reqJson.optString("requestKey", "")
        val url = reqJson.optString("url", "")
        val options = reqJson.optJSONObject("options") ?: JSONObject()

        val method = options.optString("method", "GET").uppercase()
        val headersJson = options.optJSONObject("headers")
        val bodyStr = options.optString("body", "")
        val formJson = options.optJSONObject("form")
        val isBinary = options.optBoolean("binary", false)

        val host = try { URL(url).host } catch (_: Exception) { "" }

        val requestBuilder = Request.Builder().url(url)
        val headersBuilder = Headers.Builder()

        headersJson?.keys()?.forEach { key ->
            headersBuilder.add(key, headersJson.optString(key))
        }

        // 附加已保存的 Cookie
        if (host.isNotEmpty() && !headersBuilder.build().names().any { it.equals("Cookie", ignoreCase = true) }) {
            val savedCookie = cookieStore[host]
            if (!savedCookie.isNullOrBlank()) {
                headersBuilder.add("Cookie", savedCookie)
            }
        }
        requestBuilder.headers(headersBuilder.build())

        // 构造 RequestBody
        if (method == "POST" || method == "PUT" || method == "PATCH") {
            if (formJson != null) {
                val formBody = FormBody.Builder()
                formJson.keys().forEach { k ->
                    formBody.add(k, formJson.optString(k))
                }
                requestBuilder.method(method, formBody.build())
            } else if (bodyStr.isNotEmpty()) {
                val contentType = headersBuilder.build()["Content-Type"]?.toMediaTypeOrNull()
                    ?: "application/json; charset=utf-8".toMediaTypeOrNull()
                requestBuilder.method(method, bodyStr.toRequestBody(contentType))
            } else {
                requestBuilder.method(method, "".toRequestBody(null))
            }
        } else {
            requestBuilder.method(method, null)
        }

        val call = okHttpClient.newCall(requestBuilder.build())
        if (requestKey.isNotEmpty()) {
            runningHttpCalls[requestKey] = call
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runningHttpCalls.remove(requestKey)
                sendHttpResponseToJs(requestKey, error = e.message ?: "Network error", response = null)
            }

            override fun onResponse(call: Call, response: Response) {
                runningHttpCalls.remove(requestKey)
                try {
                    // 保存 Set-Cookie
                    if (host.isNotEmpty()) {
                        val setCookies = response.headers("Set-Cookie")
                        if (setCookies.isNotEmpty()) {
                            saveCookies(host, setCookies)
                        }
                    }

                    val respHeaders = JSONObject()
                    for (name in response.headers.names()) {
                        respHeaders.put(name, response.header(name))
                    }

                    val respObj = JSONObject().apply {
                        put("statusCode", response.code)
                        put("statusMessage", response.message)
                        put("headers", respHeaders)
                    }

                    val responseBytes = response.body?.bytes() ?: ByteArray(0)
                    if (isBinary) {
                        respObj.put("rawBase64", Base64.encodeToString(responseBytes, Base64.NO_WRAP))
                        respObj.put("body", "")
                    } else {
                        val bodyText = String(responseBytes, StandardCharsets.UTF_8)
                        respObj.put("body", bodyText)
                    }

                    sendHttpResponseToJs(requestKey, error = null, response = respObj)
                } catch (e: Exception) {
                    sendHttpResponseToJs(requestKey, error = e.message ?: "Response parsing error", response = null)
                }
            }
        })
    }

    private fun saveCookies(host: String, cookieHeaders: List<String>) {
        val map = LinkedHashMap<String, String>()
        cookieStore[host]?.split(";")?.forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                map[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
            }
        }
        for (header in cookieHeaders) {
            val mainPart = header.split(";").firstOrNull() ?: continue
            val idx = mainPart.indexOf('=')
            if (idx > 0) {
                map[mainPart.substring(0, idx).trim()] = mainPart.substring(idx + 1).trim()
            }
        }
        if (map.isNotEmpty()) {
            cookieStore[host] = map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    private fun sendHttpResponseToJs(requestKey: String, error: String?, response: JSONObject?) {
        runtimeHandler.post {
            try {
                val payload = JSONObject().apply {
                    put("requestKey", requestKey)
                    if (error != null) {
                        put("error", error)
                        put("response", JSONObject.NULL)
                    } else {
                        put("error", JSONObject.NULL)
                        put("response", response ?: JSONObject())
                    }
                }
                quickJsContext?.globalObject?.getJSFunction("__lx_native__")?.call(
                    scriptId,
                    "response",
                    payload.toString(),
                )
            } catch (e: Throwable) {
                AppLogger.w(TAG, "Failed to callback HTTP response to JS", e)
            }
        }
    }

    private fun registerNativeCryptoFunctions(qjs: QuickJSContext) {
        val global = qjs.globalObject

        // setTimeout
        global.setProperty("__lx_native_call__set_timeout", JSCallFunction { args ->
            val id = args.getOrNull(0) as? Number ?: 0
            val timeout = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
            runtimeHandler.postDelayed({
                quickJsContext?.globalObject?.getJSFunction("__lx_native__")?.call(
                    scriptId,
                    "__set_timeout__",
                    id.toInt(),
                )
            }, timeout.coerceAtLeast(0L))
            null
        })

        // str2b64
        global.setProperty("__lx_native_call__utils_str2b64", JSCallFunction { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            Base64.encodeToString(str.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        })

        // b642buf
        global.setProperty("__lx_native_call__utils_b642buf", JSCallFunction { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            try {
                val bytes = Base64.decode(str, Base64.NO_WRAP)
                val jsonArr = JSONArray()
                for (b in bytes) {
                    jsonArr.put(b.toInt() and 0xFF)
                }
                jsonArr.toString()
            } catch (_: Exception) {
                "[]"
            }
        })

        // bytes2b64
        global.setProperty("__lx_native_call__utils_bytes2b64", JSCallFunction { args ->
            val str = args.getOrNull(0)?.toString() ?: "[]"
            try {
                val jsonArr = JSONArray(str)
                val bytes = ByteArray(jsonArr.length()) { i ->
                    (jsonArr.optInt(i, 0) and 0xFF).toByte()
                }
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (_: Exception) {
                ""
            }
        })

        // str2md5
        global.setProperty("__lx_native_call__utils_str2md5", JSCallFunction { args ->
            val str = args.getOrNull(0)?.toString() ?: ""
            try {
                val decoded = URLDecoder.decode(str, "UTF-8")
                val digest = MessageDigest.getInstance("MD5").digest(decoded.toByteArray(StandardCharsets.UTF_8))
                digest.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                ""
            }
        })

        // aes_encrypt
        global.setProperty("__lx_native_call__utils_aes_encrypt", JSCallFunction { args ->
            val dataB64 = args.getOrNull(0)?.toString() ?: ""
            val keyB64 = args.getOrNull(1)?.toString() ?: ""
            val ivB64 = args.getOrNull(2)?.toString() ?: ""
            val mode = args.getOrNull(3)?.toString() ?: "aes-128-cbc"

            try {
                val data = Base64.decode(dataB64, Base64.NO_WRAP)
                val key = Base64.decode(keyB64, Base64.NO_WRAP)
                val transformation = if (mode.equals("aes-128-ecb", ignoreCase = true)) {
                    "AES/ECB/PKCS5Padding"
                } else {
                    "AES/CBC/PKCS5Padding"
                }
                val cipher = Cipher.getInstance(transformation)
                val keySpec = SecretKeySpec(key, "AES")
                if (mode.equals("aes-128-cbc", ignoreCase = true)) {
                    val ivBytes = Base64.decode(ivB64, Base64.NO_WRAP)
                    val ivSpec = IvParameterSpec(ivBytes.copyOf(16))
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
                } else {
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec)
                }
                Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
            } catch (e: Exception) {
                AppLogger.w(TAG, "AES encryption failed: ${e.message}")
                ""
            }
        })

        // rsa_encrypt
        global.setProperty("__lx_native_call__utils_rsa_encrypt", JSCallFunction { args ->
            val dataB64 = args.getOrNull(0)?.toString() ?: ""
            val pubKeyB64 = args.getOrNull(1)?.toString() ?: ""
            val padding = args.getOrNull(2)?.toString() ?: "RSA/ECB/NoPadding"

            try {
                var data = Base64.decode(dataB64, Base64.NO_WRAP)
                val cleanKey = pubKeyB64.replace("\\s+".toRegex(), "")
                val keySpec = X509EncodedKeySpec(Base64.decode(cleanKey, Base64.NO_WRAP))
                val pubKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)

                if (pubKey is RSAPublicKey) {
                    val modLen = (pubKey.modulus.bitLength() + 7) / 8
                    if (data.size < modLen) {
                        val padded = ByteArray(modLen)
                        System.arraycopy(data, 0, padded, modLen - data.size, data.size)
                        data = padded
                    }
                }

                val cipher = Cipher.getInstance(padding.ifEmpty { "RSA/ECB/NoPadding" })
                cipher.init(Cipher.ENCRYPT_MODE, pubKey)
                Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
            } catch (e: Exception) {
                AppLogger.w(TAG, "RSA encryption failed: ${e.message}")
                ""
            }
        })

        // zlib_inflate
        global.setProperty("__lx_native_call__utils_zlib_inflate", JSCallFunction { args ->
            val dataB64 = args.getOrNull(0)?.toString() ?: ""
            try {
                val data = Base64.decode(dataB64, Base64.NO_WRAP)
                val inflater = Inflater()
                inflater.setInput(data)
                val outputStream = ByteArrayOutputStream(data.size * 2)
                val buffer = ByteArray(1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && inflater.needsInput()) break
                    outputStream.write(buffer, 0, count)
                }
                inflater.end()
                Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                AppLogger.w(TAG, "zlib inflate failed: ${e.message}")
                ""
            }
        })

        // zlib_deflate
        global.setProperty("__lx_native_call__utils_zlib_deflate", JSCallFunction { args ->
            val dataB64 = args.getOrNull(0)?.toString() ?: ""
            try {
                val data = Base64.decode(dataB64, Base64.NO_WRAP)
                val deflater = Deflater()
                deflater.setInput(data)
                deflater.finish()
                val outputStream = ByteArrayOutputStream(data.size)
                val buffer = ByteArray(1024)
                while (!deflater.finished()) {
                    val count = deflater.deflate(buffer)
                    outputStream.write(buffer, 0, count)
                }
                deflater.end()
                Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                AppLogger.w(TAG, "zlib deflate failed: ${e.message}")
                ""
            }
        })
    }

    /**
     * 同步解析歌曲的真实播放直链 (带有超时控制)
     */
    fun resolveMusicUrl(source: String, musicInfo: JSONObject, quality: String = "128k", timeoutMs: Long = 5000L): String? {
        if (!initLatch.await(10, TimeUnit.SECONDS) || !isInitialized) {
            AppLogger.w(TAG, "Engine not initialized yet: $initError")
            return null
        }

        val requestKey = UUID.randomUUID().toString()
        val future = RequestFuture()
        pendingRequests[requestKey] = future

        val reqData = JSONObject().apply {
            put("source", source)
            put("action", "musicUrl")
            put("info", JSONObject().apply {
                put("type", quality)
                put("musicInfo", musicInfo)
            })
        }

        val nativePayload = JSONObject().apply {
            put("requestKey", requestKey)
            put("data", reqData)
        }

        runtimeHandler.post {
            try {
                quickJsContext?.globalObject?.getJSFunction("__lx_native__")?.call(
                    scriptId,
                    "request",
                    nativePayload.toString(),
                )
            } catch (e: Throwable) {
                future.errorMsg = e.message
                future.latch.countDown()
            }
        }

        val awaited = future.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pendingRequests.remove(requestKey)

        if (!awaited) {
            AppLogger.w(TAG, "resolveMusicUrl timed out for song ${musicInfo.optString("name")} ($quality)")
            return null
        }

        if (!future.isSuccess) {
            AppLogger.w(TAG, "resolveMusicUrl failed: ${future.errorMsg}")
            return null
        }

        val resultJson = future.resultJson
        if (resultJson != null) {
            val dataObj = resultJson.optJSONObject("data")
            val url = dataObj?.optString("url") ?: resultJson.optString("url")
            if (url.isNotBlank() && url.startsWith("http", ignoreCase = true)) {
                return url
            }
        }

        val raw = future.rawResult?.toString() ?: ""
        if (raw.startsWith("http", ignoreCase = true)) {
            return raw
        }

        return null
    }

    fun destroy() {
        runtimeHandler.post {
            try {
                quickJsContext?.destroy()
                quickJsContext = null
            } catch (_: Exception) {}
        }
        runtimeThread.quitSafely()
        runningHttpCalls.values.forEach { it.cancel() }
        runningHttpCalls.clear()
        pendingRequests.values.forEach { it.latch.countDown() }
        pendingRequests.clear()
    }
}
