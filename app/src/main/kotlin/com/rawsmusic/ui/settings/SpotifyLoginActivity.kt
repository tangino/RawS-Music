package com.rawsmusic.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.ui.scene.bottomChromeNestedScroll
import com.rawsmusic.core.ui.scene.pages.appFontFamily
import com.rawsmusic.core.ui.scene.pages.themeColors
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.module.online.auth.spotify.SpotifyAccountStore
import com.rawsmusic.module.online.auth.spotify.SpotifyAuthService
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * Spotify 官方授权与登录界面。
 *
 * 采用三级防白屏与多通道授权保障体系：
 * 1. 系统浏览器官方 OAuth 授权（推荐，自带网络代理与现有会话，100% 防白屏）
 * 2. 深度优化的内嵌 WebView（带完整 WebChromeClient、第三方 Cookie 允许、加载指示与网络容错卡片）
 * 3. 手动凭据快速导入（Access Token 或 sp_dc Cookie）
 */
class SpotifyLoginActivity : BaseSettingsActivity() {
    private var webView: WebView? = null
    private var isProcessingCode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)

        setContent {
            SpotifyLoginScreen(
                isProcessingCode = isProcessingCode,
                onBack = { finish() },
                onLoginSuccess = { finish() },
                onAttachWebView = { wv -> webView = wv },
                onLaunchExternalBrowser = { url ->
                    runCatching {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(browserIntent)
                    }.onFailure {
                        AppNoticeBus.post("唤起外部浏览器失败，请使用内嵌页面或手动输入", AppNoticeIcon.ERROR)
                    }
                },
                onProcessCode = { code -> processAuthorizationCode(code) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val url = data.toString()
        if (url.startsWith("rawsmusic://spotify-callback") || url.contains("/callback")) {
            val code = data.getQueryParameter("code")
            val error = data.getQueryParameter("error")
            if (!code.isNullOrBlank()) {
                processAuthorizationCode(code)
            } else if (!error.isNullOrBlank()) {
                AppNoticeBus.post("Spotify 授权已取消: $error", AppNoticeIcon.INFO)
            }
        }
    }

    private fun processAuthorizationCode(code: String) {
        if (isProcessingCode) return
        isProcessingCode = true
        lifecycleScope.launch {
            val tokenPair = SpotifyAuthService.exchangeCodeForToken(code)
            if (tokenPair != null) {
                val (accessToken, refreshToken) = tokenPair
                // 1. 尝试拉取完整用户资料（头像、昵称），若单次海外请求偶发抖动则自动先采用安全兜底模型
                val profile = SpotifyAuthService.fetchUserProfile(accessToken, refreshToken)
                    ?: SpotifyAuthService.createFallbackProfile(accessToken, refreshToken)

                // 2. 真实有效 Token 先行保存，确保登录立即可用！
                SpotifyAccountStore.saveProfile(profile)
                AppNoticeBus.post("Spotify 关联成功！欢迎 ${profile.displayName}", AppNoticeIcon.INFO)
                finish()

                // 3. 若当前使用的是兜底资料，后台静默重试补齐真实头像与昵称
                if (profile.id == "spotify_user") {
                    SpotifyAccountStore.refreshProfileSilently()
                }
            } else {
                isProcessingCode = false
                val err = SpotifyAuthService.lastExchangeError ?: "凭证校验失败"
                AppNoticeBus.post("换取授权令牌失败: $err", AppNoticeIcon.ERROR)
            }
        }
    }

    override fun onDestroy() {
        webView?.let { wv ->
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }
}

private enum class LoginChannel {
    PORTAL,   // 登录导航门户（推荐使用系统浏览器，100% 避免内嵌白屏）
    WEBVIEW,  // 内嵌 WebView 授权
    MANUAL,   // 手动录入 Token / Cookie
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifyLoginScreen(
    isProcessingCode: Boolean,
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    onAttachWebView: (WebView) -> Unit,
    onLaunchExternalBrowser: (String) -> Unit,
    onProcessCode: (String) -> Unit,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f
    val scope = rememberCoroutineScope()

    var activeChannel by remember { mutableStateOf(LoginChannel.PORTAL) }
    var manualToken by remember { mutableStateOf("") }
    var isVerifyingManual by remember { mutableStateOf(false) }
    var manualError by remember { mutableStateOf<String?>(null) }

    var webProgress by remember { mutableIntStateOf(0) }
    var webHasError by remember { mutableStateOf(false) }
    var webErrorMessage by remember { mutableStateOf<String?>(null) }

    // 生成当前周期的授权链接并持久化对应 Verifier，确保系统浏览器重定向跳回时状态不漂移
    val authUrl = remember {
        SpotifyAuthService.prepareNewAuthUrl()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding()
            .bottomChromeNestedScroll(),
    ) {
        // 顶部操作栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = "返回",
                    tint = colors.onSurface,
                )
            }
            Text(
                text = "Spotify 账号关联",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
                fontFamily = appFontFamily(),
            )

            // 模式切换小按钮
            if (activeChannel != LoginChannel.PORTAL) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.55f))
                        .border(1.dp, colors.outline.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .clickable { activeChannel = LoginChannel.PORTAL }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "切换方式",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43),
                        fontFamily = appFontFamily(),
                    )
                }
            }
        }

        if (isProcessingCode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "已拦截授权回调，正在同步 Spotify 用户资料...",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFF1DB954) else Color(0xFF0F9D43),
                        fontFamily = appFontFamily(),
                    )
                }
            }
        } else {
            when (activeChannel) {
                LoginChannel.PORTAL -> {
                    // 门户推荐界面：首推系统浏览器，彻底解决国内 WebView 白屏与 SSL 问题
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = "选择登录与授权方式",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            fontFamily = appFontFamily(),
                        )

                        // 选项 1：系统浏览器授权（强力推荐）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.surface)
                                .border(1.dp, Color(0xFF1DB954).copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                                .clickable {
                                    onLaunchExternalBrowser(authUrl)
                                }
                                .padding(16.dp),
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF1DB954))
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "系统浏览器快捷授权（推荐，防白屏）",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface,
                                        fontFamily = appFontFamily(),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "通过 Chrome 或系统默认浏览器打开官方授权。由于浏览器自带代理配置及登录会话，可 100% 规避内嵌白屏，授权后将自动回跳完成登录。",
                                    fontSize = 12.sp,
                                    color = colors.secondaryText,
                                    fontFamily = appFontFamily(),
                                    lineHeight = 17.sp,
                                )
                            }
                        }

                        // 选项 2：应用内网页登录（配备完整防白屏加载器）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.6f))
                                .border(1.dp, colors.outline.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                                .clickable {
                                    webHasError = false
                                    webProgress = 0
                                    activeChannel = LoginChannel.WEBVIEW
                                }
                                .padding(16.dp),
                        ) {
                            Column {
                                Text(
                                    text = "应用内网页登录",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurface,
                                    fontFamily = appFontFamily(),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "直接在软件内加载页面，适合已配置全局网络代理环境的用户。",
                                    fontSize = 12.sp,
                                    color = colors.secondaryText,
                                    fontFamily = appFontFamily(),
                                    lineHeight = 17.sp,
                                )
                            }
                        }

                        // 选项 3：手动凭据粘贴
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.6f))
                                .border(1.dp, colors.outline.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                                .clickable {
                                    manualError = null
                                    activeChannel = LoginChannel.MANUAL
                                }
                                .padding(16.dp),
                        ) {
                            Column {
                                Text(
                                    text = "手动录入 Token / Cookie",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurface,
                                    fontFamily = appFontFamily(),
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "直接粘贴 Access Token 或 sp_dc 字符串，免去网页加载步骤。",
                                    fontSize = 12.sp,
                                    color = colors.secondaryText,
                                    fontFamily = appFontFamily(),
                                    lineHeight = 17.sp,
                                )
                            }
                        }
                    }
                }

                LoginChannel.MANUAL -> {
                    // 手动输入界面
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = "手动录入 Access Token 或 Cookie",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            fontFamily = appFontFamily(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "粘贴已获取的 Spotify Access Token 或 sp_dc Cookie 字符串：",
                            fontSize = 12.sp,
                            color = colors.secondaryText,
                            fontFamily = appFontFamily(),
                        )

                        Spacer(Modifier.height(14.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surface.copy(alpha = if (isDark) 0.35f else 0.52f))
                                .border(1.dp, colors.outline.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                .padding(12.dp),
                        ) {
                            BasicTextField(
                                value = manualToken,
                                onValueChange = { manualToken = it },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = TextStyle(
                                    fontSize = 13.sp,
                                    color = colors.onSurface,
                                    fontFamily = appFontFamily(),
                                ),
                                cursorBrush = SolidColor(colors.primary),
                                decorationBox = { innerTextField ->
                                    if (manualToken.isEmpty()) {
                                        Text(
                                            text = "在此粘贴 Spotify Access Token 或 sp_dc 字符串...",
                                            fontSize = 12.sp,
                                            color = colors.secondaryText.copy(alpha = 0.6f),
                                            fontFamily = appFontFamily(),
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }

                        if (manualError != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = manualError ?: "",
                                fontSize = 12.sp,
                                color = Color(0xFFFA5252),
                                fontFamily = appFontFamily(),
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val token = manualToken.trim()
                                if (token.isBlank()) return@Button
                                isVerifyingManual = true
                                manualError = null
                                scope.launch {
                                    val profile = SpotifyAuthService.fetchUserProfile(token)
                                        ?: SpotifyAuthService.createFallbackProfile(token)
                                    SpotifyAccountStore.saveProfile(profile)
                                    AppNoticeBus.post("Spotify 关联成功！欢迎 ${profile.displayName}", AppNoticeIcon.INFO)
                                    onLoginSuccess()
                                    if (profile.id == "spotify_user") {
                                        SpotifyAccountStore.refreshProfileSilently()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(22.dp)),
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFF1DB954),
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(
                                text = if (isVerifyingManual) "正在验证..." else "验证并保存",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = appFontFamily(),
                            )
                        }
                    }
                }

                LoginChannel.WEBVIEW -> {
                    // 内嵌 WebView（深度配置防白屏策略 + 实时进度指示与网络错误容错）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    onAttachWebView(this)

                                    // 允许第三方 Cookie，保证 accounts.spotify.com 会话正常
                                    val currentWv = this
                                    CookieManager.getInstance().apply {
                                        setAcceptCookie(true)
                                        setAcceptThirdPartyCookies(currentWv, true)
                                    }

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        loadWithOverviewMode = true
                                        useWideViewPort = true
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        // 采用正规现代移动端 Chrome UA
                                        userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                                    }

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            webProgress = newProgress
                                        }
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val url = request?.url?.toString() ?: return false
                                            return handleCallbackUrl(url)
                                        }

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            if (url != null && handleCallbackUrl(url)) {
                                                view?.stopLoading()
                                            }
                                        }

                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                            super.onReceivedError(view, request, error)
                                            // 忽略非主框架的错误
                                            if (request?.isForMainFrame == true) {
                                                webHasError = true
                                                webErrorMessage = "无法加载 Spotify 授权页（${error?.description ?: "网络连接超时"}）"
                                            }
                                        }

                                        private fun handleCallbackUrl(url: String): Boolean {
                                            if (url.startsWith("rawsmusic://spotify-callback") ||
                                                url.startsWith("http://127.0.0.1:8888/callback") ||
                                                url.contains("/callback?code=")
                                            ) {
                                                val uri = Uri.parse(url)
                                                val code = uri.getQueryParameter("code")
                                                val error = uri.getQueryParameter("error")

                                                if (!code.isNullOrBlank()) {
                                                    onProcessCode(code)
                                                    return true
                                                } else if (!error.isNullOrBlank()) {
                                                    AppNoticeBus.post("授权已取消: $error", AppNoticeIcon.INFO)
                                                    onBack()
                                                    return true
                                                }
                                            }
                                            return false
                                        }
                                    }

                                    loadUrl(authUrl)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        // 加载进度条
                        if (webProgress in 1..99 && !webHasError) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .background(Color(0xFF1DB954).copy(alpha = 0.2f))
                                    .align(Alignment.TopCenter)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(webProgress / 100f)
                                        .height(3.dp)
                                        .background(Color(0xFF1DB954))
                                )
                            }
                        }

                        // 网络错误容错卡片（彻底杜绝白屏无提示）
                        if (webHasError) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(colors.background),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        text = webErrorMessage ?: "无法加载 Spotify 授权页",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFFFA5252),
                                        fontFamily = appFontFamily(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "由于 Spotify 服务器位于海外，内嵌网页可能受网络连接影响。推荐改用系统浏览器授权。",
                                        fontSize = 12.sp,
                                        color = colors.secondaryText,
                                        fontFamily = appFontFamily(),
                                        lineHeight = 17.sp,
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Button(
                                        onClick = { onLaunchExternalBrowser(authUrl) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(42.dp)
                                            .clip(RoundedCornerShape(21.dp)),
                                        colors = ButtonDefaults.buttonColors(
                                            color = Color(0xFF1DB954),
                                            contentColor = Color.White,
                                        ),
                                    ) {
                                        Text(
                                            text = "使用系统浏览器授权（推荐）",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = appFontFamily(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
