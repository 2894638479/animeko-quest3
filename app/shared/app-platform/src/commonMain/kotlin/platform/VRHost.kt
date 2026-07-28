package me.him188.ani.app.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** Host for VR-specific controls like passthrough and spatial audio. */
interface VRHost {
    var passthroughEnabled: Boolean
    var spatialAudioEnabled: Boolean
}

val LocalVRHost = staticCompositionLocalOf<VRHost?> { null }
