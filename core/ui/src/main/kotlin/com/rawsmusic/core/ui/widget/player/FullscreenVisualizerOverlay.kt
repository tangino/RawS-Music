package com.rawsmusic.core.ui.widget.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.module.data.prefs.AppPreferences

/**
 * 全屏沉浸可视化大频谱覆盖层。
 * 独立于 ComposePlayerContainer 函数之外，避免巨大函数闭包内局部变量和 lambda 过多导致 Dalvik/ART 寄存器溢出（VerifyError）。
 */
@Composable
fun FullscreenVisualizerOverlay(
    audioVisualizerEnabled: Boolean,
    audioVisualizerStyle: AudioVisualizerStyle,
    audioSpectrum: FloatArray,
    audioSpectrumState: State<FloatArray>?,
    isPlaying: Boolean,
    currentSong: AudioFile?,
) {
    var isVisualizerMaximized by remember {
        mutableStateOf(AppPreferences.UI.Visualizer.isMaximized)
    }
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.rawsmusic.action.AUDIO_VISUALIZER_SETTING_CHANGED") {
                    isVisualizerMaximized = AppPreferences.UI.Visualizer.isMaximized
                }
            }
        }
        val filter = android.content.IntentFilter("com.rawsmusic.action.AUDIO_VISUALIZER_SETTING_CHANGED")
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    BackHandler(enabled = isVisualizerMaximized) {
        isVisualizerMaximized = false
        AppPreferences.UI.Visualizer.isMaximized = false
    }

    AnimatedVisibility(
        visible = isVisualizerMaximized && audioVisualizerEnabled,
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(250)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(999f)
                .background(Color(0xF00A0A0C))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    isVisualizerMaximized = false
                    AppPreferences.UI.Visualizer.isMaximized = false
                }
        ) {
            SharedRealtimeVisualizerOverlay(
                style = audioVisualizerStyle,
                spectrum = audioSpectrum,
                spectrumState = audioSpectrumState,
                visible = isVisualizerMaximized && audioVisualizerEnabled,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 54.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (currentSong != null) {
                    top.yukonga.miuix.kmp.basic.Text(
                        text = currentSong.title,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    top.yukonga.miuix.kmp.basic.Text(
                        text = currentSong.artist,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                top.yukonga.miuix.kmp.basic.Text(
                    text = stringResource(R.string.visualizer_maximized_tap_exit_hint),
                    color = Color.White.copy(alpha = 0.38f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
