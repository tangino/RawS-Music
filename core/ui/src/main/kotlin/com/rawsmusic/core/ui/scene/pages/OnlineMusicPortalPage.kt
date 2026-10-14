package com.rawsmusic.core.ui.scene.pages

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.scene.OnlinePortalTab
import com.rawsmusic.core.ui.scene.OnlinePortalTabManager
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 在线音乐门户。
 *
 * 底部导航已接入全局 BottomChrome 动态选项与向上展开菜单，
 * 避免页面内部底栏与主程序底栏产生重叠覆盖。
 */
@Composable
fun OnlineMusicPortalPage(
    onBack: () -> Unit,
    onOpenPlaylist: (OnlinePlatform, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val repository = OnlineMusicRepository.getInstance()
    val currentPlatform by repository.currentPlatform.collectAsState()

    val selectedTab = OnlinePortalTabManager.currentTab

    Column(
        modifier = modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding(),
    ) {
        SmallTopAppBar(
            title = stringResource(R.string.side_rail_online_music),
            color = Color.Transparent,
            titleColor = colors.onSurface,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = stringResource(R.string.side_rail_online_music),
                        tint = colors.onSurface,
                    )
                }
            },
        )

        OnlinePlatformChipRow(
            selected = currentPlatform,
            onSelect = { repository.switchPlatform(it) },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            OnlinePortalBody(selectedTab, onOpenPlaylist)
        }
    }
}

/** 平台切换 chip 行。直接驱动 [OnlineMusicRepository.currentPlatform]。 */
@Composable
private fun OnlinePlatformChipRow(
    selected: OnlinePlatform,
    onSelect: (OnlinePlatform) -> Unit,
) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnlinePlatform.entries.forEach { platform ->
            val isSelected = platform == selected
            Text(
                text = platform.displayName,
                color = if (isSelected) colors.onPrimaryContainer else colors.secondaryText,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isSelected) colors.primaryContainer else colors.surface)
                    .clickable { onSelect(platform) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 板块内容分发。
 *
 * 四个 tab 各自独立成文件（`OnlinePlazaTab` / `OnlineArtistTab` / `OnlineSearchTab` /
 * `OnlineFavoritesTab`），既满足「新代码单文件 ≤800 行」，也让它们可以分别演进。
 */
@Composable
private fun OnlinePortalBody(
    tab: OnlinePortalTab,
    onOpenPlaylist: (OnlinePlatform, String) -> Unit,
) {
    when (tab) {
        OnlinePortalTab.Plaza -> OnlinePlazaTab(
            onOpenPlaylist = onOpenPlaylist,
            modifier = Modifier.fillMaxSize(),
        )
        OnlinePortalTab.Artists -> OnlineArtistTab(modifier = Modifier.fillMaxSize())
        OnlinePortalTab.Search -> OnlineSearchTab(modifier = Modifier.fillMaxSize())
        OnlinePortalTab.Favorites -> OnlineFavoritesTab(modifier = Modifier.fillMaxSize())
    }
}
