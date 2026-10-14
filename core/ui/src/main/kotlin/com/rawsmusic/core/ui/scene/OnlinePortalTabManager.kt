package com.rawsmusic.core.ui.scene

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.pages.themeColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 在线音乐的四个功能板块。
 */
enum class OnlinePortalTab(
    @param:StringRes val labelRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    Plaza(R.string.online_tab_plaza, R.drawable.ic_online_plaza),
    Artists(R.string.online_tab_artists, R.drawable.ic_online_artist),
    Search(R.string.online_tab_search, R.drawable.ic_online_search),
    Favorites(R.string.online_tab_favorites, R.drawable.ic_online_favorite),
}

/**
 * 在线音乐板块及底栏展开状态全局管理器。
 */
object OnlinePortalTabManager {
    var currentTab by mutableStateOf(OnlinePortalTab.Plaza)
    var isExpanded by mutableStateOf(false)

    fun selectTab(tab: OnlinePortalTab) {
        currentTab = tab
        isExpanded = false
    }

    fun toggleExpanded() {
        isExpanded = !isExpanded
    }

    fun dismiss() {
        isExpanded = false
    }
}

/**
 * 在底栏上方向上展开的在线音乐板块选择面板。
 */
@Composable
fun OnlineMusicPortalExpandMenu(
    visible: Boolean,
    currentTab: OnlinePortalTab,
    onSelectTab: (OnlinePortalTab) -> Unit,
    onDismiss: () -> Unit,
    bottomOffset: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()

    if (visible) {
        // 全屏透明遮罩用于点击空白处收起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220)) +
                slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) { it / 2 },
        exit = fadeOut(animationSpec = tween(150)) +
                slideOutVertically(animationSpec = tween(150)) { it / 3 },
        modifier = modifier
            .padding(bottom = bottomOffset + 6.dp)
            .padding(horizontal = 16.dp),
    ) {
        SurfaceBubble(
            colors = colors,
            currentTab = currentTab,
            onSelectTab = onSelectTab,
        )
    }
}

@Composable
private fun SurfaceBubble(
    colors: com.rawsmusic.core.ui.scene.pages.ThemeColors,
    currentTab: OnlinePortalTab,
    onSelectTab: (OnlinePortalTab) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 浮层主体卡片
        Row(
            modifier = Modifier
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface.copy(alpha = 0.96f))
                .border(
                    width = 1.dp,
                    color = colors.primary.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnlinePortalTab.entries.forEach { tab ->
                val isSelected = tab == currentTab
                val itemBg = if (isSelected) colors.primary.copy(alpha = 0.16f) else Color.Transparent
                val itemBorder = if (isSelected) colors.primary.copy(alpha = 0.35f) else Color.Transparent
                val contentColor = if (isSelected) colors.primary else colors.secondaryText

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(itemBg)
                        .border(
                            width = if (isSelected) 0.8.dp else 0.dp,
                            color = itemBorder,
                            shape = RoundedCornerShape(18.dp)
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = stringResource(tab.labelRes),
                        tint = contentColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(tab.labelRes),
                        color = contentColor,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }

        // 底部小指示箭头胶囊，提示来源于底栏
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .size(width = 24.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.primary.copy(alpha = 0.5f))
        )
    }
}
