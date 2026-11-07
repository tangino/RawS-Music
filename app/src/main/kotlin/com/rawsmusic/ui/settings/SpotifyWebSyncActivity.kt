package com.rawsmusic.ui.settings

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.scene.pages.appFontFamily
import com.rawsmusic.core.ui.scene.pages.themeColors
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.core.ui.theme.RawSMusicTheme
import com.rawsmusic.module.online.model.PodcastShow
import com.rawsmusic.module.online.model.PodcastSourceType
import com.rawsmusic.module.online.store.PodcastStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 网页端直连抓取提取的播客轻量模型。
 */
private data class DetectedWebPodcast(
    val id: String,
    val title: String,
    val publisher: String,
    val coverUrl: String,
)

/**
 * Spotify 网页端直连免配置同步界面。
 *
 * 彻底绕过 Spotify 开发者模式白名单与 Web API 403 权限限制：
 * 1. 在内置安全 WebView 中直接访问 Spotify 官方个人播客媒体库（open.spotify.com/collection/podcasts）；
 * 2. 注入轻量 DOM 扫描脚本自动识别当前页面展示的播客；
 * 3. 一键提取并批量原子导入到本地收藏列表。
 */
class SpotifyWebSyncActivity : BaseSettingsActivity() {
    private var webView: WebView? = null
    private val detectedPodcasts = mutableStateListOf<DetectedWebPodcast>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RawSMusicTheme {
                SpotifyWebSyncScreen(
                    detectedList = detectedPodcasts,
                    onBack = { finish() },
                    onAttachWebView = { wv -> webView = wv },
                    onTriggerScan = { triggerPageScan() },
                    onLoadUrl = { url -> webView?.loadUrl(url) },
                    onImportAll = { importDetectedShows() },
                )
            }
        }
    }

    private fun triggerPageScan() {
        val wv = webView ?: return
        val scanScript = """
            (function() {
                try {
                    var items = [];
                    // 1. 扫描所有指向 /show/xxx 的卡片或链接
                    var showLinks = document.querySelectorAll('a[href*="/show/"]');
                    showLinks.forEach(function(a) {
                        var href = a.getAttribute('href') || '';
                        var match = href.match(/\/show\/([a-zA-Z0-9]+)/);
                        if (match) {
                            var showId = match[1];
                            var card = a.closest('[data-testid="card-click-handler"]') ||
                                       a.closest('[role="listitem"]') ||
                                       a.closest('div[class*="Card"]') ||
                                       a.parentElement || a;

                            var title = '';
                            var titleElem = card.querySelector('p, h3, div[class*="Text"], span[class*="Text"]');
                            if (titleElem) {
                                title = titleElem.innerText.trim();
                            }
                            if (!title) {
                                title = a.innerText.trim();
                            }

                            var publisher = 'Spotify 播客';
                            var allTexts = card.querySelectorAll('p, span');
                            if (allTexts.length > 1) {
                                var sub = allTexts[1].innerText.trim();
                                if (sub && sub !== title) {
                                    publisher = sub;
                                }
                            }

                            var coverUrl = '';
                            var img = card.querySelector('img');
                            if (img) {
                                coverUrl = img.src || img.getAttribute('data-src') || '';
                            }

                            if (showId && title && items.findIndex(function(it) { return it.id === showId; }) === -1) {
                                items.push({
                                    id: showId,
                                    title: title,
                                    publisher: publisher,
                                    coverUrl: coverUrl
                                });
                            }
                        }
                    });

                    // 2. 扫描单集列表中所属的播客
                    var epLinks = document.querySelectorAll('a[href*="/episode/"]');
                    epLinks.forEach(function(a) {
                        var container = a.closest('[role="row"]') || a.closest('div') || a;
                        var showA = container.querySelector('a[href*="/show/"]');
                        if (showA) {
                            var href = showA.getAttribute('href') || '';
                            var match = href.match(/\/show\/([a-zA-Z0-9]+)/);
                            if (match) {
                                var showId = match[1];
                                var title = showA.innerText.trim();
                                var coverUrl = '';
                                var img = container.querySelector('img');
                                if (img) coverUrl = img.src || '';
                                if (showId && title && items.findIndex(function(it) { return it.id === showId; }) === -1) {
                                    items.push({
                                        id: showId,
                                        title: title,
                                        publisher: 'Spotify 播客',
                                        coverUrl: coverUrl
                                    });
                                }
                            }
                        }
                    });

                    if (window.AndroidBridge && window.AndroidBridge.onShowsExtracted) {
                        window.AndroidBridge.onShowsExtracted(JSON.stringify(items));
                    }
                } catch(e) {
                    console.error("DOM scan error: " + e);
                }
            })();
        """.trimIndent()
        wv.evaluateJavascript(scanScript, null)
    }

    private fun importDetectedShows() {
        if (detectedPodcasts.isEmpty()) {
            AppNoticeBus.post("尚未识别到任何播客，请在网页中浏览至播客页面", AppNoticeIcon.INFO)
            return
        }

        val showsToImport = detectedPodcasts.map { item ->
            PodcastShow(
                id = "spotify_${item.id}",
                title = item.title,
                publisher = item.publisher,
                description = "由 Spotify 网页端直连同步导入",
                coverUrl = item.coverUrl,
                totalEpisodes = 0,
                feedUrl = null,
                sourceType = PodcastSourceType.SPOTIFY,
                category = "Spotify 关注",
            )
        }

        val added = PodcastStore.addFavorites(showsToImport)
        AppNoticeBus.post("网页直连同步成功！已导入 $added 个播客（共检测到 ${showsToImport.size} 个）", AppNoticeIcon.PLAYLIST)
        finish()
    }

    /**
     * JS 通讯桥梁，接收网页扫描到的播客列表。
     */
    inner class WebSyncJsBridge {
        @JavascriptInterface
        fun onShowsExtracted(jsonStr: String) {
            runOnUiThread {
                try {
                    val arr = JSONArray(jsonStr)
                    var countNew = 0
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id").ifBlank { continue }
                        val title = obj.optString("title").ifBlank { continue }
                        val publisher = obj.optString("publisher", "Spotify 播客")
                        val coverUrl = obj.optString("coverUrl")

                        if (detectedPodcasts.none { it.id == id }) {
                            detectedPodcasts.add(
                                DetectedWebPodcast(
                                    id = id,
                                    title = title,
                                    publisher = publisher,
                                    coverUrl = coverUrl,
                                )
                            )
                            countNew++
                        }
                    }
                    if (countNew > 0) {
                        AppLogger.i("SpotifyWebSync", "网页端新增识别到 $countNew 个播客，累计共 ${detectedPodcasts.size} 个")
                    }
                } catch (e: Exception) {
                    AppLogger.w("SpotifyWebSync", "解析网页播客异常: ${e.message}")
                }
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SpotifyWebSyncScreen(
    detectedList: List<DetectedWebPodcast>,
    onBack: () -> Unit,
    onAttachWebView: (WebView) -> Unit,
    onTriggerScan: () -> Unit,
    onLoadUrl: (String) -> Unit,
    onImportAll: () -> Unit,
) {
    val colors = themeColors()
    var webProgress by remember { mutableIntStateOf(0) }
    var currentUrl by remember { mutableStateOf("https://open.spotify.com/collection/podcasts") }
    var webHasError by remember { mutableStateOf(false) }
    var webErrorMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .rawStableStatusBarsPadding(),
    ) {
        // 1. 顶部操作栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = "返回",
                    tint = colors.onSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Spotify 网页端直连同步",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontFamily = appFontFamily(),
                )
                Text(
                    text = "免开发者权限 · 登录即可自动抓取",
                    fontSize = 11.sp,
                    color = Color(0xFF1DB954),
                    fontFamily = appFontFamily(),
                )
            }

            // 手动扫描按钮
            Button(
                onClick = onTriggerScan,
                colors = ButtonDefaults.buttonColors(
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ),
            ) {
                Text(
                    text = "重新扫描",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = appFontFamily(),
                )
            }
        }

        // 2. 快捷快捷跳转胶囊行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SyncQuickTab(
                label = "我的播客库",
                isSelected = currentUrl.contains("/collection/podcasts"),
                onClick = {
                    currentUrl = "https://open.spotify.com/collection/podcasts"
                    onLoadUrl(currentUrl)
                },
            )
            SyncQuickTab(
                label = "已保存单集",
                isSelected = currentUrl.contains("/collection/episodes"),
                onClick = {
                    currentUrl = "https://open.spotify.com/collection/episodes"
                    onLoadUrl(currentUrl)
                },
            )
            SyncQuickTab(
                label = "Spotify 首页",
                isSelected = currentUrl == "https://open.spotify.com/",
                onClick = {
                    currentUrl = "https://open.spotify.com/"
                    onLoadUrl(currentUrl)
                },
            )
        }

        // 3. 页面加载进度条
        if (webProgress in 1..99) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(colors.outline.copy(alpha = 0.2f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = webProgress / 100f)
                        .height(2.5.dp)
                        .background(Color(0xFF1DB954)),
                )
            }
        } else {
            Spacer(Modifier.height(2.5.dp))
        }

        // 4. WebView 容器
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                factory = { ctx ->
                    val activity = ctx as? SpotifyWebSyncActivity
                    WebView(ctx).apply {
                        onAttachWebView(this)

                        // 允许第三方 Cookie
                        val currentWv = this
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(currentWv, true)
                        }

                        // 注入 AndroidBridge
                        activity?.let { act ->
                            addJavascriptInterface(act.WebSyncJsBridge(), "AndroidBridge")
                        }

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                webProgress = newProgress
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { currentUrl = it }
                                webHasError = false
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                url?.let { currentUrl = it }
                                // 页面加载完成后延迟 1.2 秒自动扫描提取 DOM
                                scope.launch {
                                    delay(1200)
                                    onTriggerScan()
                                    // 延迟 3 秒再扫一次（等待瀑布流异步渲染）
                                    delay(2000)
                                    onTriggerScan()
                                }
                            }

                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    webHasError = true
                                    webErrorMessage = "无法加载 Spotify 页面（${error?.description ?: "网络超时，请检查代理"}）"
                                }
                            }
                        }

                        loadUrl(currentUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 网络错误提示浮层
            if (webHasError) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.background.copy(alpha = 0.95f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = webErrorMessage,
                            color = colors.onSurface,
                            fontSize = 14.sp,
                            fontFamily = appFontFamily(),
                        )
                        Button(
                            onClick = {
                                webHasError = false
                                onLoadUrl(currentUrl)
                            },
                            colors = ButtonDefaults.buttonColors(
                                color = Color(0xFF1DB954),
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("重新加载", fontSize = 13.sp, fontFamily = appFontFamily())
                        }
                    }
                }
            }
        }

        // 5. 底部悬浮提取与同步状态栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .border(width = 0.5.dp, color = colors.outline.copy(alpha = 0.15f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (detectedList.isNotEmpty()) Color(0xFF1DB954) else colors.outline),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (detectedList.isNotEmpty()) {
                                "已识别到 ${detectedList.size} 个关注播客"
                            } else {
                                "请在上方登录并浏览播客"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (detectedList.isNotEmpty()) Color(0xFF1DB954) else colors.onSurfaceVariant,
                            fontFamily = appFontFamily(),
                        )
                    }
                    Text(
                        text = if (detectedList.isNotEmpty()) "可向下滚动网页加载更多后点击导入" else "页面加载出播客列表后将自动识别",
                        fontSize = 11.sp,
                        color = colors.secondaryText,
                        fontFamily = appFontFamily(),
                    )
                }

                Spacer(Modifier.width(12.dp))

                // 一键导入按钮
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(
                            if (detectedList.isNotEmpty()) Color(0xFF1DB954) else colors.outline.copy(alpha = 0.3f)
                        )
                        .clickable(enabled = detectedList.isNotEmpty(), onClick = onImportAll)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (detectedList.isNotEmpty()) "一键导入 (${detectedList.size})" else "等待识别",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (detectedList.isNotEmpty()) Color.White else colors.secondaryText,
                        fontFamily = appFontFamily(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncQuickTab(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) Color(0xFF1DB954) else colors.surface)
            .border(
                width = 0.5.dp,
                color = if (isSelected) Color(0xFF1DB954) else colors.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) Color.White else colors.onSurface,
            fontFamily = appFontFamily(),
        )
    }
}
