package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.OnlinePortalTab
import com.rawsmusic.core.ui.scene.OnlinePortalTabManager
import com.rawsmusic.core.ui.scene.bottomChromeNestedScroll
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import com.rawsmusic.module.online.model.OnlinePlatform
import com.rawsmusic.module.online.repository.OnlineMusicRepository
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 在线音乐门户。
 *
 * 顶部导航已重构：
 * 1. 顶栏右侧内嵌紧凑型平台下拉切换胶囊，消除整行冗余水平药丸；
 * 2. 主功能 Tab 采用沉浸式分段选择胶囊条（Segmented Capsule Control），消除厚重边框堆叠；
 * 3. 垂直高度压缩超过 50%，将视觉重心留给核心歌单与歌手内容。
 */
@Composable
fun OnlineMusicPortalPage(
    onBack: () -> Unit,
    onOpenPlaylist: (OnlinePlatform, String) -> Unit,
    onOpenArtist: (OnlinePlatform, String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val repository = OnlineMusicRepository.getInstance()
    val currentPlatform by repository.currentPlatform.collectAsState()
    val selectedTab = OnlinePortalTabManager.currentTab

    var showPlatformPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding()
            .bottomChromeNestedScroll(),
    ) {
        // 顶部操作栏：左侧返回 + 标题，右侧网格列数与平台切换器胶囊
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = stringResource(R.string.side_rail_online_music),
                    tint = colors.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.side_rail_online_music),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 2.dp),
            )
            Spacer(Modifier.weight(1f))

            PlatformSelectorChip(
                currentPlatform = currentPlatform,
                onClick = { showPlatformPicker = true },
            )
        }

        // 功能板块分段栏（歌单广场 / 歌手库 / 全网搜歌 / 我的收藏）
        OnlinePortalTabRow(
            currentTab = selectedTab,
            onSelectTab = { OnlinePortalTabManager.selectTab(it) },
        )

        // 主体内容分发
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            OnlinePortalBody(selectedTab, onOpenPlaylist, onOpenArtist)
        }
    }


    // 平台选择对话框
    if (showPlatformPicker) {
        PlatformPickerDialog(
            currentPlatform = currentPlatform,
            onSelect = { repository.switchPlatform(it) },
            onDismiss = { showPlatformPicker = false },
        )
    }
}


/** 顶栏右侧的精巧平台选择胶囊按钮。 */
@Composable
private fun PlatformSelectorChip(
    currentPlatform: OnlinePlatform,
    onClick: () -> Unit,
) {
    val colors = themeColors()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(colors.primary),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = currentPlatform.displayName,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "▾",
            fontSize = 11.sp,
            color = colors.primary.copy(alpha = 0.8f),
        )
    }
}

/** 平台选择弹出框。 */
@Composable
private fun PlatformPickerDialog(
    currentPlatform: OnlinePlatform,
    onSelect: (OnlinePlatform) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = themeColors()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .padding(20.dp),
        ) {
            Text(
                text = "选择音乐平台",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(14.dp))
            OnlinePlatform.entries.forEach { platform ->
                val isSelected = platform == currentPlatform
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable {
                            onSelect(platform)
                            onDismiss()
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = platform.displayName,
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) colors.primary else colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Text(
                            text = "✓",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 现代沉浸式功能分段选择条（Segmented Tab Bar）。 */
@Composable
private fun OnlinePortalTabRow(
    currentTab: OnlinePortalTab,
    onSelectTab: (OnlinePortalTab) -> Unit,
) {
    val colors = themeColors()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(colors.surface.copy(alpha = 0.6f))
            .border(
                width = 0.5.dp,
                color = colors.outline.copy(alpha = 0.12f),
                shape = RoundedCornerShape(19.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OnlinePortalTab.entries.forEach { tab ->
                val isSelected = tab == currentTab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) colors.primary else Color.Transparent)
                        .clickable { onSelectTab(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = painterResource(tab.iconRes),
                            contentDescription = stringResource(tab.labelRes),
                            tint = if (isSelected) colors.onPrimary else colors.secondaryText,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(tab.labelRes),
                            color = if (isSelected) colors.onPrimary else colors.onSurface.copy(alpha = 0.75f),
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 板块内容分发。 */
@Composable
private fun OnlinePortalBody(
    tab: OnlinePortalTab,
    onOpenPlaylist: (OnlinePlatform, String) -> Unit,
    onOpenArtist: (OnlinePlatform, String, String, String) -> Unit,
) {
    when (tab) {
        OnlinePortalTab.Plaza -> OnlinePlazaTab(
            onOpenPlaylist = onOpenPlaylist,
            modifier = Modifier.fillMaxSize(),
        )
        OnlinePortalTab.Artists -> OnlineArtistTab(
            onOpenArtist = onOpenArtist,
            modifier = Modifier.fillMaxSize(),
        )
        OnlinePortalTab.Search -> OnlineSearchTab(modifier = Modifier.fillMaxSize())
        OnlinePortalTab.Favorites -> OnlineFavoritesTab(
            onOpenPlaylist = onOpenPlaylist,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
