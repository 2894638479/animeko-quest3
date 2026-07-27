package me.him188.ani.app.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** Implemented by VR activities that support spatial audio. */
interface SpatialAudioHost {
    fun onPlayerAudioSessionReady(sessionId: Int)
}

/** CompositionLocal for accessing [SpatialAudioHost] from composables inside VR panels. */
val LocalSpatialAudioHost = staticCompositionLocalOf<SpatialAudioHost?> { null }
