package com.rawsmusic.ui.settings

import android.os.Bundle
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.online.model.OnlinePlatform

/**
 * 「导入我的歌单」独立设置页。
 *
 * 平台从 Intent 带进来（同一平台账号卡片才能指定自己的歌单列表），
 * 因此**不需要** NavScene / ComposeNavHost 注册，与 `PlatformAccountActivity` 同款。
 */
class PlatformPlaylistImportActivity : BaseSettingsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platform = OnlinePlatform.fromId(intent.getStringExtra(EXTRA_PLATFORM_ID))
        if (platform == null) {
            // 缺参数属于调用方 bug：不要退化成「默认导入某个平台」，直接关掉并留日志。
            AppLogger.e("PlaylistTransfer", "缺少平台参数，已关闭歌单导入页")
            finish()
            return
        }
        setContent {
            PlatformPlaylistImportScreen(
                platform = platform,
                onBack = { finish() },
            )
        }
    }

    companion object {
        const val EXTRA_PLATFORM_ID = "platform_playlist_import_platform_id"
    }
}
