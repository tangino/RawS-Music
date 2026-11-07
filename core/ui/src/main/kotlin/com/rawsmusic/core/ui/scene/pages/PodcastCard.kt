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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 主界面播客专区卡片。
 *
 * 遵循系统 Miuix 主题色，保持与每日推荐、猜你喜欢一致的高质感卡片视觉。
 */
@Composable
internal fun PodcastCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f

    // 播客标志性翠绿/深空青品牌主色调
    val podcastGreen = if (isDark) Color(0xFF1DB954) else Color(0xFF109D43)
    val cardBorderColor = podcastGreen.copy(alpha = if (isDark) 0.35f else 0.20f)

    val cardBg = if (isDark) {
        listOf(
            colors.surface.copy(alpha = 0.92f),
            podcastGreen.copy(alpha = 0.16f),
            colors.surface.copy(alpha = 0.94f),
        )
    } else {
        listOf(
            Color(0xFFFFFFFF).copy(alpha = 0.96f),
            podcastGreen.copy(alpha = 0.12f),
            Color(0xFFFBFDFA).copy(alpha = 0.94f),
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .padding(top = 10.dp) // 预留破框空间
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(24.dp),
                spotColor = podcastGreen.copy(alpha = 0.25f),
                ambientColor = colors.surface.copy(alpha = 0.10f),
            )
            .background(
                brush = Brush.horizontalGradient(cardBg),
                shape = RoundedCornerShape(24.dp),
            )
            .border(
                width = 1.dp,
                color = cardBorderColor,
                shape = RoundedCornerShape(24.dp),
            )
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 3D 质感播客破框图标
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .offset(y = (-8).dp)
                    .shadow(8.dp, RoundedCornerShape(20.dp), spotColor = podcastGreen.copy(alpha = 0.4f))
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        brush = Brush.linearGradient(
                            listOf(
                                podcastGreen,
                                if (isDark) Color(0xFF121212) else Color(0xFF0D5E29),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 内部发光电波
                Icon(
                    painter = painterResource(R.drawable.ic_side_rail_podcast),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp),
                )
            }

            Spacer(modifier = Modifier.width(18.dp))

            // 中间标题与副标题
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.podcast_card_title),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        fontFamily = appFontFamily(),
                    )
                    // Spotify & RSS 胶囊标签
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(podcastGreen.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Spotify / RSS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = podcastGreen,
                            fontFamily = appFontFamily(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.podcast_card_subtitle),
                    fontSize = 13.sp,
                    color = colors.secondaryText,
                    fontFamily = appFontFamily(),
                    maxLines = 1,
                )
            }

            // 右侧进入药丸箭头图标
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(colors.surface.copy(alpha = 0.8f))
                    .border(1.dp, colors.outline.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = podcastGreen,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

