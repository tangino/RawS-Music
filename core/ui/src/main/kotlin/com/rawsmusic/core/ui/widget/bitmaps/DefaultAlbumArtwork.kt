package com.rawsmusic.core.ui.widget.bitmaps

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.rawsmusic.core.common.R
import com.rawsmusic.module.data.prefs.AppPreferences

/** Shared policy and renderer for songs that have no real embedded or external artwork. */
object DefaultAlbumArtworkPolicy {
    // 全局取消所有默认封面的显示：无真实封面时保持干净留白并等待在线封面（优先 QQ 音乐）回填
    var enabled by mutableStateOf(false)
        private set

    fun updateEnabled(@Suppress("UNUSED_PARAMETER") value: Boolean) {
        enabled = false
        AppPreferences.AlbumArt.useDefaultArtwork = false
    }
}

fun shouldShowDefaultAlbumArtwork(key: String?, targetWidth: Int, targetHeight: Int): Boolean {
    @Suppress("UNUSED_VARIABLE")
    val ignored = Triple(key, targetWidth, targetHeight)
    return false
}

fun decodeDefaultAlbumArtwork(resources: Resources, targetSide: Int): Bitmap? {
    val source = BitmapFactory.decodeResource(resources, R.drawable.default_album_art) ?: return null
    val side = targetSide.coerceIn(1, 1024)
    if (source.width == side && source.height == side) return source
    val scaled = Bitmap.createScaledBitmap(source, side, side, true)
    if (scaled !== source && !source.isRecycled) source.recycle()
    return scaled
}

@Composable
fun DefaultAlbumArtwork(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit
) {
    // 全局取消默认封面显示
    return
}
