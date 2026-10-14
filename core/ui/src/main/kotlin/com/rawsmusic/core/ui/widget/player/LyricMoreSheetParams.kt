package com.rawsmusic.core.ui.widget.player

import androidx.compose.ui.geometry.Rect
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricTimingEditorTarget
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState

data class LyricMoreArtworkState(
    val currentSong: AudioFile? = null,
    val onAddSongToPlaylist: ((AudioFile) -> Unit)? = null,
    val coverPath: String? = null,
    val artworkTransitionState: PlaybackArtworkTransitionState? = null,
    val artworkAlpha: Float = 1f,
    val onArtworkBoundsChanged: (Rect) -> Unit = {},
)

data class LyricMoreDisplayConfig(
    val displayTranslation: Boolean = false,
    val displayRoma: Boolean = false,
    val blurEnabled: Boolean = false,
    val relativeLineProgressEnabled: Boolean = false,
    val highlightAllEnabled: Boolean = false,
    val fontSizeSp: Int = 28,
    val textPosition: LyricTextPosition = LyricTextPosition.Center,
    val progressStyleValue: Int? = null,
    val landscapeLockEnabled: Boolean? = null,
    val twoColumnAcrylicLayout: Boolean = true,
    val embedded: Boolean = false,
    val fontSizeTitle: String? = null,
    val onTranslationToggle: () -> Unit = {},
    val onRomaToggle: () -> Unit = {},
    val onBlurEnabledChange: (Boolean) -> Unit = {},
    val onRelativeLineProgressEnabledChange: (Boolean) -> Unit = {},
    val onHighlightAllEnabledChange: (Boolean) -> Unit = {},
    val onFontSizeChange: (Int) -> Unit = {},
    val onTextPositionChange: (LyricTextPosition) -> Unit = {},
    val onProgressStyleValueChange: ((Int) -> Unit)? = null,
    val onLandscapeLockEnabledChange: ((Boolean) -> Unit)? = null,
)

data class LyricMoreToolsConfig(
    val onModifyAlbumArt: (() -> Unit)? = null,
    val onSearchLyrico: (() -> Unit)? = null,
    val onOpenInLyrico: (() -> Unit)? = null,
    val onAiTimingPreview: (() -> Unit)? = null,
    val onOpenExternalTimingEditor: ((LyricTimingEditorTarget) -> Unit)? = null,
    val audioVisualizerEnabled: Boolean? = null,
    val onAudioVisualizerEnabledChange: ((Boolean) -> Unit)? = null,
    val audioVisualizerStyleOverride: AudioVisualizerStyle? = null,
    val onAudioVisualizerStyleChangeOverride: ((AudioVisualizerStyle) -> Unit)? = null,
    val onLyricVideo: (() -> Unit)? = null,
    val onLyricPoster: (() -> Unit)? = null,
)
