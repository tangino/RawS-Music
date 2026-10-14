package com.rawsmusic.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.R
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
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

/**
 * 平台账号（QQ / 酷狗）设置页。
 *
 * 数据全部来自 `:module:online` 的 [PlatformAccountManager]（`object`，无需 Context）：
 * 它本身不持有任何 Compose 状态，UI 只观察 [PlatformAccountManager.accounts] 并调用其挂起/同步方法。
 *
 * ⚠️ 这里**不新写扫码协议**，也不自己保存账号 —— `checkQrStatus` 内部在成功时已经加密落盘，
 * 页面只负责提示 + 关弹窗。
 */

private const val TAG = "PlatformAuth"

/** 扫码状态轮询间隔。平台侧过期时间约 2~3 分钟，2 秒一次足够及时又不打爆接口。 */
private const val QR_POLL_INTERVAL_MS = 2_000L

@Composable
fun PlatformAccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by PlatformAccountManager.accounts.collectAsState()

    var qrTarget by remember { mutableStateOf<OnlinePlatform?>(null) }
    var logoutTarget by remember { mutableStateOf<OnlinePlatform?>(null) }
    var refreshingPlatform by remember { mutableStateOf<OnlinePlatform?>(null) }

    // 「导入我的歌单」入口：跳到该平台的歌单导入页，平台靠 Intent extra 传递。
    // 页面本身已经是独立 Activity，所以这里直接 startActivity，不需要新 NavScene。
    val openPlaylistImport: (OnlinePlatform) -> Unit = { platform ->
        context.startActivity(
            Intent(context, PlatformPlaylistImportActivity::class.java)
                .setPackage(context.packageName)
                .putExtra(PlatformPlaylistImportActivity.EXTRA_PLATFORM_ID, platform.id),
        )
    }

    // 必须显式触发一次懒恢复：`accounts` 这个 StateFlow 本身不会读 MMKV，
    // 只有 `getAccount()` 之类的访问器才会 ensureRestored() 并把已登录账号发布出来。
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
                    onLogin = { qrTarget = platform },
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

    // 只在实际需要时组合弹窗：弹窗一旦不再组合，其内部 LaunchedEffect 的轮询协程随之取消。
    qrTarget?.let { platform ->
        PlatformAccountQrDialog(
            platform = platform,
            onDismiss = { qrTarget = null },
        )
    }

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
    onLogin: () -> Unit,
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

    // 「导入我的歌单」：未登录时保持可见但禁用 —— 歌单只能在登录后拉到，
    // 但入口不该被藏起来（隐藏会让用户完全发现不了这个能力）。
    TextButton(
        text = stringResource(R.string.platform_playlist_import_action),
        onClick = onImportPlaylists,
        enabled = account != null,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (account == null) {
            TextButton(
                text = stringResource(R.string.platform_account_action_login),
                onClick = onLogin,
                modifier = Modifier.weight(1f),
            )
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
            // BitmapProvider 自身支持 http(s) 源（内部走 OkHttp + 分层缓存），不需要 Coil。
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
 * 扫码登录弹窗。
 *
 * 轮询取消策略（**防泄漏的关键**）：调用方只在 `qrTarget != null` 时组合本函数，
 * 因此关弹窗 = 本 Composable 离开 composition = [LaunchedEffect] 的协程被取消；
 * 取消会立刻打断内部的 `delay` 并把 `CancellationException` 抛回，轮询循环随之结束。
 * 页面里没有 `rememberCoroutineScope().launch` 的长驻轮询，也就没有需要手动 stop 的 Job。
 */
@Composable
private fun PlatformAccountQrDialog(
    platform: OnlinePlatform,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = themeColors()
    val displayName = platformDisplayName(platform)

    var attempt by remember(platform) { mutableIntStateOf(0) }
    var qrBitmap by remember(platform) { mutableStateOf<Bitmap?>(null) }
    var status by remember(platform) { mutableStateOf<QrStatus?>(null) }
    var polling by remember(platform) { mutableStateOf(false) }

    LaunchedEffect(platform, attempt) {
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

            // 生成二维码是纯 CPU + GC 的活（QQ 是 PNG 解码，酷狗可能是 base64 PNG 或需要 ZXing 编码）。
            qrBitmap = withContext(Dispatchers.Default) { renderQrBitmap(qrData) }
            if (qrBitmap == null) {
                status = QrStatus.ERROR
                AppNoticeBus.error(context.getString(R.string.platform_account_qr_fetch_failed))
                return@LaunchedEffect
            }

            val deadline = System.currentTimeMillis() + qrData.expireTimeMs
            while (isActive) {
                delay(QR_POLL_INTERVAL_MS)
                val result = try {
                    PlatformAccountManager.checkQrStatus(platform, qrData.key)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    AppLogger.e(TAG, "轮询 ${platform.id} 扫码状态失败", error)
                    QrCheckResult(QrStatus.ERROR)
                }
                status = result.status
                when (result.status) {
                    QrStatus.SUCCESS -> {
                        // 账号已由 checkQrStatus 内部落盘，这里只提示 + 关弹窗。
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
                    QrStatus.EXPIRED, QrStatus.ERROR -> return@LaunchedEffect
                    else -> Unit
                }
                // 平台没主动回过期时按有效期兜底，避免无限轮询一个已经失效的 key。
                if (System.currentTimeMillis() >= deadline) {
                    status = QrStatus.EXPIRED
                    return@LaunchedEffect
                }
            }
        } finally {
            polling = false
        }
    }

    // 弹窗被移除时复位按钮可用性标记；轮询协程本身由上面的 LaunchedEffect 取消。
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

    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.platform_account_qr_title, displayName),
        summary = stringResource(R.string.platform_account_qr_summary, displayName),
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(216.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.background),
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
                        text = stringResource(R.string.platform_account_qr_loading),
                        color = colors.secondaryText,
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
            Spacer(Modifier.height(10.dp))
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
        }
    }
}

/** 退出登录是破坏性操作（凭据密文会被删除），单独二次确认。 */
@Composable
private fun PlatformAccountLogoutDialog(
    displayName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = themeColors()
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.platform_account_logout_confirm_title, displayName),
        summary = stringResource(R.string.platform_account_logout_confirm_summary),
        backgroundColor = colors.surface,
        onDismissRequest = onDismiss,
    ) {
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

/**
 * 二维码位图：优先用平台直接给的图片字节（QQ 的 ptqrshow / 酷狗的 base64 PNG），
 * 只有在没有图片字节时才用 [QrCodeGenerator] 把二维码内容编码成 Bitmap。
 */
private fun renderQrBitmap(data: QrCodeData): Bitmap? {
    data.qrImageBytes?.takeIf { it.isNotEmpty() }?.let { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
    }
    val content = data.qrUrl?.takeIf { it.isNotBlank() } ?: return null
    return QrCodeGenerator.createQrBitmap(content = content, size = 512)
}
