package com.rawsmusic.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rawsmusic.R
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.module.online.auth.PlatformAccountManager
import com.rawsmusic.module.online.auth.model.PlatformAccount
import com.rawsmusic.module.online.auth.model.QrCheckResult
import com.rawsmusic.module.online.auth.model.QrCodeData
import com.rawsmusic.module.online.auth.model.QrStatus
import com.rawsmusic.module.online.auth.util.QrCodeGenerator
import com.rawsmusic.module.online.model.OnlinePlatform
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 平台账号（QQ / 酷狗）设置页。
 *
 * 支持【扫码登录】与【Cookie / Token 登录】两种登录方式。
 * 数据统一走 `:module:online` 的 [PlatformAccountManager]。
 */

private const val TAG = "PlatformAuth"

/** 扫码状态轮询间隔。平台侧过期时间约 2~3 分钟，2 秒一次足够及时又不打爆接口。 */
private const val QR_POLL_INTERVAL_MS = 2_000L

@Composable
fun PlatformAccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by PlatformAccountManager.accounts.collectAsState()

    // 存放登录目标及初始 Tab：first 为平台，second 为 0(扫码) 或 1(Cookie)
    var loginTarget by remember { mutableStateOf<Pair<OnlinePlatform, Int>?>(null) }
    var logoutTarget by remember { mutableStateOf<OnlinePlatform?>(null) }
    var refreshingPlatform by remember { mutableStateOf<OnlinePlatform?>(null) }

    // 「导入我的歌单」入口：跳到该平台的歌单导入页，平台靠 Intent extra 传递。
    val openPlaylistImport: (OnlinePlatform) -> Unit = { platform ->
        context.startActivity(
            Intent(context, PlatformPlaylistImportActivity::class.java)
                .setPackage(context.packageName)
                .putExtra(PlatformPlaylistImportActivity.EXTRA_PLATFORM_ID, platform.id),
        )
    }

    // 显式触发一次懒恢复：确保已持久化的账号发布到 StateFlow
    LaunchedEffect(Unit) {
        PlatformAccountManager.supportedPlatforms().forEach { platform ->
            PlatformAccountManager.getAccount(platform)
        }
    }

    SettingsPage(title = stringResource(R.string.platform_account_title), onBack = onBack) {
        val colors = themeColors()
        Text(
            text = stringResource(R.string.platform_account_page_summary),
            color = colors.secondaryText,
            fontSize = 11.sp,
            fontFamily = appFontFamily(),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )

        PlatformAccountManager.supportedPlatforms().forEach { platform ->
            SettingsCard {
                PlatformAccountCard(
                    platform = platform,
                    account = accounts[platform],
                    refreshing = refreshingPlatform == platform,
                    onQrLogin = {
                        AppLogger.d(TAG, "打开扫码登录: ${platform.displayName}")
                        loginTarget = platform to 0
                    },
                    onCookieLogin = {
                        AppLogger.d(TAG, "打开 Cookie 登录: ${platform.displayName}")
                        loginTarget = platform to 1
                    },
                    onLogout = { logoutTarget = platform },
                    onImportPlaylists = { openPlaylistImport(platform) },
                    onRefresh = {
                        refreshingPlatform = platform
                        scope.launch {
                            try {
                                PlatformAccountManager.refreshAccount(platform)
                                AppNoticeBus.post(
                                    message = context.getString(R.string.platform_account_refresh_success),
                                    icon = AppNoticeIcon.INFO,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                AppLogger.e(TAG, "刷新 ${platform.id} 账号信息失败", error)
                                AppNoticeBus.error(context.getString(R.string.platform_account_refresh_failed))
                            } finally {
                                refreshingPlatform = null
                            }
                        }
                    },
                )
            }
        }
    }

    // 登录弹窗（支持扫码与 Cookie 登录）
    loginTarget?.let { (platform, initialTab) ->
        PlatformAccountLoginDialog(
            platform = platform,
            initialTab = initialTab,
            onDismiss = { loginTarget = null },
        )
    }

    // 退出登录确认弹窗
    logoutTarget?.let { platform ->
        val displayName = platformDisplayName(platform)
        PlatformAccountLogoutDialog(
            displayName = displayName,
            onDismiss = { logoutTarget = null },
            onConfirm = {
                PlatformAccountManager.logout(platform)
                logoutTarget = null
                AppNoticeBus.post(
                    message = context.getString(R.string.platform_account_logout_success, displayName),
                    icon = AppNoticeIcon.INFO,
                )
            },
        )
    }
}

@Composable
private fun PlatformAccountCard(
    platform: OnlinePlatform,
    account: PlatformAccount?,
    refreshing: Boolean,
    onQrLogin: () -> Unit,
    onCookieLogin: () -> Unit,
    onLogout: () -> Unit,
    onImportPlaylists: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = themeColors()
    val displayName = platformDisplayName(platform)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlatformAccountAvatar(platform = platform, account = account, fallbackLabel = displayName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayName,
                color = colors.onSurface,
                fontSize = 15.sp,
                fontFamily = appFontFamily(),
            )
            if (account == null) {
                Text(
                    text = stringResource(R.string.platform_account_not_logged_in),
                    color = colors.secondaryText,
                    fontSize = 11.sp,
                    fontFamily = appFontFamily(),
                )
            } else {
                Text(
                    text = account.nickname.ifBlank { displayName },
                    color = colors.onSurface,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily(),
                )
                Text(
                    text = stringResource(R.string.platform_account_user_id, account.userId),
                    color = colors.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = appFontFamily(),
                )
                Text(
                    text = platformVipLabel(account),
                    color = if (account.isVip) colors.primary else colors.secondaryText,
                    fontSize = 11.sp,
                    fontFamily = appFontFamily(),
                )
                if (account.isVip && account.vipExpireTime > 0L) {
                    Text(
                        text = stringResource(
                            R.string.platform_account_vip_expire,
                            formatDate(account.vipExpireTime),
                        ),
                        color = colors.secondaryText,
                        fontSize = 11.sp,
                        fontFamily = appFontFamily(),
                    )
                }
            }
        }
    }

    // 「导入我的歌单」：未登录时保持可见但禁用
    TextButton(
        text = stringResource(R.string.platform_playlist_import_action),
        onClick = onImportPlaylists,
        enabled = account != null,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(4.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (account == null) {
            Button(
                onClick = onQrLogin,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.platform_account_action_login),
                    fontFamily = appFontFamily(),
                )
            }
            Button(
                onClick = onCookieLogin,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.platform_account_action_cookie_login),
                    fontFamily = appFontFamily(),
                )
            }
        } else {
            TextButton(
                text = stringResource(R.string.platform_account_action_logout),
                onClick = onLogout,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.platform_account_action_refresh),
                onClick = onRefresh,
                enabled = !refreshing,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PlatformAccountAvatar(
    platform: OnlinePlatform,
    account: PlatformAccount?,
    fallbackLabel: String,
) {
    val colors = themeColors()
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val avatarUrl = account?.avatarUrl.orEmpty()
        if (avatarUrl.isNotBlank()) {
            BitmapImage(
                key = avatarUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                targetWidth = 128,
                targetHeight = 128,
                showDefaultArtwork = false,
            )
        } else {
            Text(
                text = fallbackLabel.take(1),
                color = colors.onPrimaryContainer,
                fontSize = 16.sp,
                fontFamily = appFontFamily(),
            )
        }
    }
}

@Composable
private fun platformVipLabel(account: PlatformAccount): String = when {
    !account.isVip -> stringResource(R.string.platform_account_not_vip)
    account.vipLevel.isNotBlank() -> stringResource(R.string.platform_account_vip_level, account.vipLevel)
    else -> stringResource(R.string.platform_account_vip_member)
}

/** 平台显示名。做成 internal 供歌单导入页复用，避免同一套映射写两份。 */
@Composable
internal fun platformDisplayName(platform: OnlinePlatform): String = stringResource(
    when (platform) {
        OnlinePlatform.QQ -> R.string.platform_account_qq_name
        OnlinePlatform.KUGOU -> R.string.platform_account_kugou_name
        else -> R.string.platform_account_entry_title
    }
)

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))

/**
 * 平台登录弹窗：支持二维码扫码登录与手动填入 Cookie / Token 登录
 *
 * 使用官方 Dialog 实现以确保在任何独立的 Activity 宿主中均能可靠渲染。
 */
@Composable
private fun PlatformAccountLoginDialog(
    platform: OnlinePlatform,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = themeColors()
    val displayName = platformDisplayName(platform)

    var currentTab by remember(platform) { mutableIntStateOf(initialTab) } // 0: 扫码, 1: Cookie

    // ====== 扫码登录状态 ======
    var attempt by remember(platform) { mutableIntStateOf(0) }
    var qrBitmap by remember(platform) { mutableStateOf<Bitmap?>(null) }
    var status by remember(platform) { mutableStateOf<QrStatus?>(null) }
    var polling by remember(platform) { mutableStateOf(false) }

    // ====== Cookie 登录状态 ======
    var cookieInput by remember(platform) { mutableStateOf("") }
    var isCookieLoggingIn by remember(platform) { mutableStateOf(false) }
    var cookieError by remember(platform) { mutableStateOf<String?>(null) }

    LaunchedEffect(platform, attempt, currentTab) {
        if (currentTab != 0) return@LaunchedEffect
        qrBitmap = null
        status = null
        polling = true
        try {
            val qrData = try {
                PlatformAccountManager.getQrCode(platform)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogger.e(TAG, "获取 ${platform.id} 登录二维码失败", error)
                null
            }
            if (qrData == null) {
                status = QrStatus.ERROR
                AppNoticeBus.error(context.getString(R.string.platform_account_qr_fetch_failed))
                return@LaunchedEffect
            }

            qrBitmap = withContext(Dispatchers.Default) { renderQrBitmap(qrData) }
            if (qrBitmap == null) {
                status = QrStatus.ERROR
                AppNoticeBus.error(context.getString(R.string.platform_account_qr_fetch_failed))
                return@LaunchedEffect
            }

            val deadline = System.currentTimeMillis() + qrData.expireTimeMs
            var consecutiveErrors = 0
            while (isActive) {
                delay(QR_POLL_INTERVAL_MS)
                val result = try {
                    PlatformAccountManager.checkQrStatus(
                        platform = platform,
                        key = qrData.key,
                        extra = if (platform == OnlinePlatform.QQ) mapOf("qrsig" to qrData.key) else emptyMap(),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    AppLogger.w(TAG, "轮询 ${platform.id} 扫码状态网络抖动", error)
                    consecutiveErrors++
                    if (consecutiveErrors >= 10) {
                        status = QrStatus.ERROR
                        break
                    }
                    continue
                }
                consecutiveErrors = 0
                status = result.status
                when (result.status) {
                    QrStatus.SUCCESS -> {
                        AppNoticeBus.post(
                            message = context.getString(
                                R.string.platform_account_login_success,
                                result.account?.nickname?.takeIf { it.isNotBlank() } ?: displayName,
                            ),
                            icon = AppNoticeIcon.INFO,
                        )
                        onDismiss()
                        return@LaunchedEffect
                    }
                    QrStatus.EXPIRED -> {
                        return@LaunchedEffect
                    }
                    else -> Unit
                }
                if (System.currentTimeMillis() >= deadline) {
                    status = QrStatus.EXPIRED
                    return@LaunchedEffect
                }
            }
        } finally {
            polling = false
        }
    }

    DisposableEffect(platform) {
        onDispose { polling = false }
    }

    val statusRes = when (status) {
        null -> R.string.platform_account_qr_loading
        QrStatus.WAITING -> R.string.platform_account_qr_waiting
        QrStatus.SCANNED -> R.string.platform_account_qr_scanned
        QrStatus.SUCCESS -> R.string.platform_account_qr_success
        QrStatus.EXPIRED -> R.string.platform_account_qr_expired
        QrStatus.ERROR -> R.string.platform_account_qr_error
    }
    val retryable = status == QrStatus.EXPIRED || status == QrStatus.ERROR

    fun handleCookieLogin() {
        val trimmed = cookieInput.trim()
        if (trimmed.isBlank()) {
            cookieError = context.getString(R.string.platform_account_cookie_paste_empty)
            return
        }
        scope.launch {
            isCookieLoggingIn = true
            cookieError = null
            try {
                val result = PlatformAccountManager.loginWithCookies(platform, trimmed)
                result.onSuccess { account ->
                    AppNoticeBus.post(
                        message = context.getString(
                            R.string.platform_account_login_success,
                            account.nickname.ifBlank { displayName },
                        ),
                        icon = AppNoticeIcon.INFO,
                    )
                    onDismiss()
                }.onFailure { err ->
                    cookieError = context.getString(
                        R.string.platform_account_cookie_login_failed,
                        err.message ?: context.getString(R.string.platform_account_qr_error),
                    )
                }
            } finally {
                isCookieLoggingIn = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surface)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 弹窗标题
                Text(
                    text = if (currentTab == 0) {
                        stringResource(R.string.platform_account_qr_title, displayName)
                    } else {
                        stringResource(R.string.platform_account_cookie_dialog_title, displayName)
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontFamily = appFontFamily(),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(4.dp))

                // 弹窗描述
                Text(
                    text = if (currentTab == 0) {
                        stringResource(R.string.platform_account_qr_summary, displayName)
                    } else {
                        stringResource(R.string.platform_account_cookie_dialog_summary)
                    },
                    fontSize = 12.sp,
                    color = colors.secondaryText,
                    fontFamily = appFontFamily(),
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(14.dp))

                // Tab 切换按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { currentTab = 0 },
                        colors = if (currentTab == 0) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(R.string.platform_account_tab_qr),
                            fontFamily = appFontFamily(),
                        )
                    }
                    Button(
                        onClick = { currentTab = 1 },
                        colors = if (currentTab == 1) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(R.string.platform_account_tab_cookie),
                            fontFamily = appFontFamily(),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (currentTab == 0) {
                    // 扫码登录区：强制白底与 padding，解决在深色主题下二维码全黑不可见的致命问题
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(8.dp)
                            .clickable(enabled = retryable) {
                                if (retryable) attempt += 1
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = qrBitmap
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text(
                                text = stringResource(if (status == QrStatus.ERROR) R.string.platform_account_qr_error else R.string.platform_account_qr_loading),
                                color = Color(0xFF666666),
                                fontSize = 12.sp,
                                fontFamily = appFontFamily(),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(statusRes),
                        color = if (status == QrStatus.SUCCESS) colors.primary else colors.secondaryText,
                        fontSize = 13.sp,
                        fontFamily = appFontFamily(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (retryable) {
                            TextButton(
                                text = stringResource(R.string.platform_account_qr_retry),
                                onClick = { attempt += 1 },
                                enabled = !polling,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        TextButton(
                            text = stringResource(R.string.platform_account_qr_close),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    // Cookie / Token 登录区
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(
                                if (platform == OnlinePlatform.QQ) {
                                R.string.platform_account_cookie_hint_qq
                            } else {
                                R.string.platform_account_cookie_hint_kugou
                            },
                            ),
                            color = colors.secondaryText,
                            fontSize = 12.sp,
                            fontFamily = appFontFamily(),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )

                        TextField(
                            value = cookieInput,
                            onValueChange = {
                                cookieInput = it
                                cookieError = null
                            },
                            label = stringResource(R.string.platform_account_cookie_input_label),
                            singleLine = false,
                            enabled = !isCookieLoggingIn,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 96.dp, max = 160.dp),
                        )

                        cookieError?.let { err ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = err,
                                color = Color(0xFFE53935),
                                fontSize = 12.sp,
                                fontFamily = appFontFamily(),
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                text = stringResource(R.string.platform_account_cookie_paste),
                                onClick = {
                                    try {
                                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clip = cb?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                                        if (!clip.isNullOrBlank()) {
                                            cookieInput = clip
                                            cookieError = null
                                        } else {
                                            AppNoticeBus.post(
                                                message = context.getString(R.string.platform_account_cookie_paste_empty),
                                                icon = AppNoticeIcon.INFO,
                                            )
                                        }
                                    } catch (e: Exception) {
                                        cookieError = e.message
                                    }
                                },
                                enabled = !isCookieLoggingIn,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = ::handleCookieLogin,
                                enabled = !isCookieLoggingIn && cookieInput.isNotBlank(),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = if (isCookieLoggingIn) {
                                        stringResource(R.string.platform_account_cookie_verifying)
                                    } else {
                                        stringResource(R.string.platform_account_cookie_verify_btn)
                                    },
                                    fontFamily = appFontFamily(),
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            text = stringResource(R.string.platform_account_qr_close),
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 二维码位图：优先用平台直接给的图片字节（QQ 的 ptqrshow / 酷狗的 base64 PNG），
 * 只有在没有图片字节时才用 [QrCodeGenerator] 把二维码内容编码成 Bitmap。
 */
private fun renderQrBitmap(data: QrCodeData): Bitmap? {
    data.qrImageBytes?.takeIf { it.isNotEmpty() }?.let { bytes ->
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        } catch (_: Exception) {}
    }
    val content = data.qrUrl?.takeIf { it.isNotBlank() } ?: return null
    return QrCodeGenerator.createQrBitmap(content = content, size = 512)
}

/** 退出登录是破坏性操作（凭据密文会被删除），单独二次确认。 */
@Composable
private fun PlatformAccountLogoutDialog(
    displayName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = themeColors()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 380.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surface)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.platform_account_logout_confirm_title, displayName),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontFamily = appFontFamily(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.platform_account_logout_confirm_summary),
                    fontSize = 12.sp,
                    color = colors.secondaryText,
                    fontFamily = appFontFamily(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.platform_account_action_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(R.string.platform_account_action_logout),
                            fontFamily = appFontFamily(),
                        )
                    }
                }
            }
        }
    }
}
