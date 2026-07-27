package me.him188.ani.app.platform

/** Implemented by VR activities that support spatial audio. */
interface SpatialAudioHost {
    fun onPlayerAudioSessionReady(sessionId: Int)
}
