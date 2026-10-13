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
import com.rawsmusic.core.ui.systemui.rawStableNavigationBarsPadding
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
 * 在线音乐门户的四个板块。
 *
 * 这是**新功能**，用自定义枚举而非复用音源的 `SourcePortalTab`（那是音源专用枚举）。
 */
internal enum class OnlinePortalTab(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    Plaza(R.string.online_tab_plaza, R.drawable.ic_online_plaza),
    Artists(R.string.online_tab_artists, R.drawable.ic_online_artist),
    Search(R.string.online_tab_search, R.drawable.ic_online_search),
    Favorites(R.string.online_tab_favorites, R.drawable.ic_online_favorite),
}

/**
 * 在线音乐门户（P1-13 只做外壳：标题栏 + 平台切换 + 二级 tab）。
 *
 * **与 `SourceImportPage` 的有意差异**：那个门户背后是一整套音源专用机制
 * （`SourcePortalChrome` / `SourcePortalTab` / `SourcePortalNavigationState` /
 * `SourcePortalPredictiveBack` / 路由转场，合计 1000+ 行）。这里只复用通用能力，
 * 用自定义 tab 枚举 + `rememberSaveable` 承载选中态，避免为第二个门户复制整套专用机制。
 * 若后续确实需要 tab 级转场与预测性返回的同等打磨，应把该机制泛化而不是复制。
 */
@Composable
fun OnlineMusicPortalPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val repository = OnlineMusicRepository.getInstance()
    val currentPlatform by repository.currentPlatform.collectAsState()

    var selectedTab by rememberSaveable { mutableStateOf(OnlinePortalTab.Plaza) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
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
            OnlinePortalBody(selectedTab)
        }

        OnlinePortalBottomBar(
            selectedTab = selectedTab,
            onSelect = { selectedTab = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .rawStableNavigationBarsPadding(),
        )
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
private fun OnlinePortalBody(tab: OnlinePortalTab) {
    when (tab) {
        OnlinePortalTab.Plaza -> OnlinePlazaTab(modifier = Modifier.fillMaxSize())
        OnlinePortalTab.Artists -> OnlineArtistTab(modifier = Modifier.fillMaxSize())
        OnlinePortalTab.Search -> OnlineSearchTab(modifier = Modifier.fillMaxSize())
        OnlinePortalTab.Favorites -> OnlineFavoritesTab(modifier = Modifier.fillMaxSize())
    }
}

/**
 * 二级 tab 栏。
 *
 * 用与 `SourcePortalBottomNavigation` 无毛玻璃分支一致的做法（圆角胶囊 + 选中态底色），
 * 因为着色规则相同、且不依赖 Backdrop。
 */
@Composable
private fun OnlinePortalBottomBar(
    selectedTab: OnlinePortalTab,
    onSelect: (OnlinePortalTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    Row(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surface)
            .padding(4.dp),
    ) {
        OnlinePortalTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (isSelected) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(tab.iconRes),
                    contentDescription = stringResource(tab.labelRes),
                    tint = if (isSelected) colors.primary else colors.onSurface,
                    modifier = Modifier.size(21.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(tab.labelRes),
                    color = if (isSelected) colors.primary else colors.onSurface,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
