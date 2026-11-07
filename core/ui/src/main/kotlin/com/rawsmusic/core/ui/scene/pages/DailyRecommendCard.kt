package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalAppBackgroundBackdrop
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.player.rememberCoverAccentColor
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.SettingsSurfaceStyle
import com.rawsmusic.module.online.model.DailyRecommendData
import top.yukonga.miuix.kmp.basic.Text

/**
 * 每日30首（今日私享）主页双列横跨卡片。
 * 完美还原设计稿：左侧为 3D 拟物立体专辑封面（带前后穿插立体光环、封套厚度与微反光），
 * 绝不拉扁变形，双层矢量丝绸流体波浪羽化自适应延伸，并与当前专辑封面主色调实时动态融合。
 */
@Composable
internal fun DailyRecommendCard(
    dailyData: DailyRecommendData,
    editMode: Boolean,
    onRemove: () -> Unit,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onEnterEdit: () -> Unit = {},
    currentCoverKey: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = themeColors()
    val isDark = colors.background.luminance() < 0.5f
    val backdrop = LocalAppBackgroundBackdrop.current
    val material by PersonalizationPreferences.dailyRecommendMaterial.collectAsState()
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()
    val performanceMode by PersonalizationPreferences.performanceMode.collectAsState()
    val cardShape = RoundedCornerShape(28.dp)

    // 权威色调与封面：必须严格从每日推荐歌曲自身获取，绝不从当前播放歌曲中获取
    val coverKey = dailyData.songs.firstOrNull()?.coverUrl
    val extractedAccent = rememberCoverAccentColor(coverKey)
    val accent = if (extractedAccent == Color(0xFF6B5A70)) {
        if (isDark) Color(0xFFD4A373) else Color(0xFFE89842)
    } else {
        extractedAccent
    }

    val cardBorderColor = accent.copy(alpha = if (isDark) 0.35f else 0.22f)
    val cardBg = if (isDark) {
        listOf(
            colors.surface.copy(alpha = 0.90f),
            accent.copy(alpha = 0.22f),
            colors.surface.copy(alpha = 0.92f),
        )
    } else {
        listOf(
            Color(0xFFFFFDF8).copy(alpha = 0.96f),
            accent.copy(alpha = 0.18f),
            accent.copy(alpha = 0.10f),
            Color(0xFFFFFBF2).copy(alpha = 0.94f),
        )
    }

    val isGlass = material == SettingsSurfaceStyle.LIQUID_GLASS && backdrop != null
    val wave1Alpha = if (isGlass) {
        if (isDark) 0.07f else 0.05f
    } else {
        if (isDark) 0.15f else 0.13f
    }
    val wave2Alpha = if (isGlass) {
        if (isDark) 0.12f else 0.09f
    } else {
        if (isDark) 0.24f else 0.20f
    }

    val cardModifier = when {
        material == SettingsSurfaceStyle.LIQUID_GLASS && backdrop != null -> {
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { cardShape },
                    effects = {
                        val minDimension = size.minDimension
                        val liquidBlurDp = (glassSettings.blurRadiusDp * 0.25f)
                            .coerceAtMost(if (performanceMode) 2f else 8f)
                        vibrancy(glassSettings.vibrancyStrength)
                        blur(liquidBlurDp.dp.toPx())
                        lens(
                            refractionHeight = glassSettings.refractionHeightFraction * minDimension * 0.35f,
                            refractionAmount = glassSettings.refractionAmountFraction * minDimension * 1.25f,
                            depthEffect = true,
                            chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(
                            alpha = (0.34f * glassSettings.highlightStrength).coerceIn(0f, 1f),
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 8.dp,
                            color = Color.Black.copy(
                                alpha = (0.12f * glassSettings.shadowStrength).coerceIn(0f, 0.4f),
                            ),
                        )
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = 6.dp,
                            alpha = (0.22f * glassSettings.shadowStrength).coerceIn(0f, 1f),
                        )
                    },
                    onDrawSurface = {
                        drawRect(colors.surface.copy(alpha = if (isDark) 0.16f else 0.24f))
                        drawRect(accent.copy(alpha = if (isDark) 0.08f else 0.06f))
                    },
                )
                .clip(cardShape)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = if (isDark) 0.22f else 0.35f),
                    shape = cardShape,
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onEnterEdit,
                )
        }
        material == SettingsSurfaceStyle.ACRYLIC && backdrop != null -> {
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { cardShape },
                    effects = {
                        vibrancy(0.72f * glassSettings.vibrancyStrength)
                        blur((glassSettings.blurRadiusDp * 1.55f).dp.toPx())
                    },
                    highlight = {
                        Highlight.Default.copy(
                            alpha = (0.20f * glassSettings.highlightStrength).coerceIn(0f, 0.6f),
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 8.dp,
                            color = Color.Black.copy(
                                alpha = (0.16f * glassSettings.shadowStrength).coerceIn(0f, 0.4f),
                            ),
                        )
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = 4.dp,
                            alpha = (0.12f * glassSettings.shadowStrength).coerceIn(0f, 0.5f),
                        )
                    },
                    onDrawSurface = {
                        drawRect(colors.surface.copy(alpha = if (isDark) 0.68f else 0.78f))
                        drawRect(accent.copy(alpha = if (isDark) 0.10f else 0.08f))
                    },
                )
                .clip(cardShape)
                .border(
                    width = 1.dp,
                    color = cardBorderColor,
                    shape = cardShape,
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onEnterEdit,
                )
        }
        else -> {
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .shadow(
                    elevation = if (isDark) 3.dp else 6.dp,
                    shape = cardShape,
                    ambientColor = Color.Black.copy(alpha = 0.06f),
                    spotColor = accent.copy(alpha = 0.25f),
                )
                .clip(cardShape)
                .background(Brush.horizontalGradient(cardBg))
                .border(
                    width = 1.dp,
                    color = cardBorderColor,
                    shape = cardShape,
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onEnterEdit,
                )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 40.dp, bottom = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 底层卡片主体
        Box(
            modifier = cardModifier
        ) {
            // 1. 双层细腻流体丝绸波浪（Canvas 矢量贝塞尔曲线填充，自适应宽屏延展，永不失真）
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height

                // 底层更宽广的柔和扩散波浪
                val wave1 = Path().apply {
                    moveTo(w * 0.25f, h)
                    cubicTo(
                        w * 0.45f, h * 0.92f,
                        w * 0.65f, h * 0.50f,
                        w, h * 0.55f
                    )
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    path = wave1,
                    brush = Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = wave1Alpha),
                            accent.copy(alpha = if (isDark) 0.03f else 0.02f),
                        )
                    ),
                )

                // 顶层丝绸流体波浪
                val wave2 = Path().apply {
                    moveTo(w * 0.38f, h)
                    cubicTo(
                        w * 0.56f, h * 0.78f,
                        w * 0.76f, h * 0.32f,
                        w, h * 0.38f
                    )
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    path = wave2,
                    brush = Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = wave2Alpha),
                            accent.copy(alpha = if (isDark) 0.06f else 0.04f),
                        )
                    ),
                )
            }

            // 2. 飘浮的半透明流体音符（与设计稿位置及透明度完全一致）
            Text(
                text = "♪",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = accent.copy(alpha = if (isDark) 0.55f else 0.48f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = 16.dp, y = (-2).dp)
                    .graphicsLayer { rotationZ = -10f },
            )
            Text(
                text = "♪",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = accent.copy(alpha = if (isDark) 0.50f else 0.42f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = (-36).dp, y = (-8).dp)
                    .graphicsLayer { rotationZ = 8f },
            )

            // 3. 卡片主内容 Row 布局
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 为左侧大封面预留更宽敞的空间，文字整体往右移
                Spacer(Modifier.width(148.dp))

                // 中间偏右：标题与优雅副标题
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "每日推荐",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) colors.onSurface else Color(0xFF2C1810),
                        maxLines = 1,
                    )

                    Spacer(Modifier.height(6.dp))

                    // 优先展示原图的诗意文案：“精选 30首 · 每天与好音乐相遇”
                    Text(
                        text = "精选 30首 · 每天与好音乐相遇",
                        fontSize = 13.sp,
                        color = if (isDark) colors.secondaryText else Color(0xFF8D6E63).copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 编辑模式下的删除小按钮
            AnimatedVisibility(
                visible = editMode,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = (-4).dp, y = 4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .clickable { onRemove() },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.home_card_remove_action),
                        colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }

        // 4. 3D 拟物立体专辑封面：加大至 98dp，至少 1/3（36dp）突破卡片上边沿悬浮在外
        Daily3DCoverBadge(
            coverKey = coverKey,
            accentColor = accent,
            isDark = isDark,
            size = 98.dp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = 18.dp, y = (-36).dp),
        )
    }
}

/**
 * 3D 拟物立体日历挂件（带前后穿透的真实立体环绕光环）
 */
@Composable
internal fun Daily3DCalendarBadge(
    dayStr: String,
    monthStr: String,
    accentColor: Color,
    isDark: Boolean,
) {
    Box(
        modifier = Modifier.size(width = 66.dp, height = 66.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 第一层：环绕光环的【后半部分】（被日历遮挡在后面）
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-4).dp)
                .size(width = 62.dp, height = 26.dp)
        ) {
            drawArc(
                brush = Brush.horizontalGradient(
                    listOf(
                        accentColor.copy(alpha = 0.20f),
                        Color(0xFFFF8A65).copy(alpha = 0.50f),
                        accentColor.copy(alpha = 0.20f),
                    )
                ),
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // 第二层：3D 台历主体（带纸张立体厚度与金属活页线圈挂孔）
        Box(
            modifier = Modifier
                .size(width = 50.dp, height = 53.dp)
                .graphicsLayer {
                    rotationZ = -5.5f
                    cameraDistance = 14f
                }
        ) {
            // 纸张侧面立体堆叠阴影与厚度
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = 1.dp, y = 1.5.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (isDark) Color(0xFF1C1C1E) else Color(0xFFE8E5DF))
            )

            // 台历主卡片
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(
                        elevation = 5.dp,
                        shape = RoundedCornerShape(11.dp),
                        ambientColor = Color.Black.copy(alpha = 0.12f),
                        spotColor = accentColor.copy(alpha = 0.40f),
                    )
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (isDark) {
                            Color(0xFF262628)
                        } else {
                            Color(0xFFFFFDF8)
                        }
                    )
                    .border(
                        width = 0.8.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White,
                        shape = RoundedCornerShape(11.dp),
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 活页横梁（暖橙红高光渐变）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFFF7A45),
                                    Color(0xFFFA541C),
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    // 3 个立体拱形活页金属/塑料挂环（带微阴影）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .size(width = 4.dp, height = 7.dp)
                                    .shadow(1.dp, RoundedCornerShape(2.dp))
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.98f))
                                    .border(0.5.dp, Color(0xFFE0E0E0), RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }

                // 日历主体：加粗深色日期数字与月份
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = dayStr,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color.White else Color(0xFF37251C),
                            lineHeight = 21.sp,
                        )
                        Text(
                            text = monthStr.uppercase(),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFFA1A1AA) else Color(0xFF795548),
                            lineHeight = 9.sp,
                        )
                    }
                }
            }
        }

        // 第三层：环绕光环的【前半部分】（真正穿过日历正前方，实现空间立体穿透）
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = (-4).dp)
                .size(width = 62.dp, height = 26.dp)
        ) {
            drawArc(
                brush = Brush.horizontalGradient(
                    listOf(
                        accentColor.copy(alpha = 0.35f),
                        Color(0xFFFFAB91).copy(alpha = 0.85f),
                        accentColor.copy(alpha = 0.35f),
                    )
                ),
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 3D 拟物立体专辑封面微章（微倾斜、白边、立体厚度与高光扫光）
 */
@Composable
internal fun Daily3DCoverBadge(
    coverKey: String?,
    accentColor: Color,
    isDark: Boolean,
    size: Dp = 98.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                rotationZ = -5f
                cameraDistance = 16f
            }
    ) {
        // 唱片封套的侧边立体厚度与投影底板
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = 3.dp, y = 3.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (isDark) Color(0xFF141416) else Color(0xFFD4CBBB))
        )

        // 真实的专辑封面图
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(
                    elevation = if (isDark) 6.dp else 12.dp,
                    shape = RoundedCornerShape(18.dp),
                    ambientColor = Color.Black.copy(alpha = 0.20f),
                    spotColor = accentColor.copy(alpha = 0.55f),
                )
                .clip(RoundedCornerShape(18.dp))
                .background(if (isDark) Color(0xFF222224) else Color(0xFFF5EFEB))
                .border(
                    width = 1.2.dp,
                    color = if (isDark) Color.White.copy(alpha = 0.28f) else Color.White,
                    shape = RoundedCornerShape(18.dp),
                )
        ) {
            if (!coverKey.isNullOrBlank()) {
                BitmapImage(
                    key = coverKey,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 200,
                    targetHeight = 200,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    accentColor.copy(alpha = 0.65f),
                                    accentColor.copy(alpha = 0.95f),
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_2_fill),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.90f),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            // 封套玻璃反光扫光层（拟物精致玻璃微反光）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            0.0f to Color.White.copy(alpha = 0.35f),
                            0.35f to Color.White.copy(alpha = 0.08f),
                            0.70f to Color.Transparent,
                        )
                    )
            )
        }
    }
}
