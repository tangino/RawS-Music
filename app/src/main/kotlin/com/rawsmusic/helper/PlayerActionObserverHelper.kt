package com.rawsmusic.helper

import androidx.lifecycle.LifecycleCoroutineScope
import com.rawsmusic.module.player.PlayerEventBus
import kotlinx.coroutines.launch

/**
 * Bridges service-side media commands back to Activity-only presentation state.
 *
 * Transport commands (play / pause / next / previous / stop) are already executed by
 * PlayerService / PlayerController. Re-executing them here advanced the queue twice:
 * a notification, headset or car-hardware "next" was applied once by the service and again
 * by the Activity, so the requested track flashed and playback jumped to the following one.
 *
 * SEEK is the one event the Activity still needs: it re-anchors the lyric clock. The actual
 * controller seek has already been applied by PlayerService before it emits the event.
 */
class PlayerActionObserverHelper(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onSeekAction: () -> Unit,
) {
    fun observe() {
        lifecycleScope.launch {
            PlayerEventBus.events.collect { event ->
                when (event.action) {
                    "com.rawsmusic.action.SEEK" -> onSeekAction()
                }
            }
        }
    }
}
