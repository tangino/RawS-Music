package com.rawsmusic.core.ui.widget.player.gl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier

@Composable
fun TimeTunnelSpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    GLVisualizerSurface(
        rendererFactory = { TimeTunnelGLRenderer() },
        spectrum = spectrum,
        spectrumState = spectrumState,
        visible = visible,
        isPlaying = isPlaying,
        modifier = modifier,
    )
}

@Composable
fun OctgramsSpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    GLVisualizerSurface(
        rendererFactory = { OctgramsGLRenderer() },
        spectrum = spectrum,
        spectrumState = spectrumState,
        visible = visible,
        isPlaying = isPlaying,
        modifier = modifier,
    )
}

@Composable
fun SoundCitySpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    GLVisualizerSurface(
        rendererFactory = { SoundCityGLRenderer() },
        spectrum = spectrum,
        spectrumState = spectrumState,
        visible = visible,
        isPlaying = isPlaying,
        modifier = modifier,
    )
}

@Composable
fun FractalGalaxySpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    GLVisualizerSurface(
        rendererFactory = { FractalGalaxyGLRenderer() },
        spectrum = spectrum,
        spectrumState = spectrumState,
        visible = visible,
        isPlaying = isPlaying,
        modifier = modifier,
    )
}

@Composable
fun QuantumVortexSpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    GLVisualizerSurface(
        rendererFactory = { QuantumVortexGLRenderer() },
        spectrum = spectrum,
        spectrumState = spectrumState,
        visible = visible,
        isPlaying = isPlaying,
        modifier = modifier,
    )
}
