package me.him188.ani.app.platform

import androidx.compose.runtime.staticCompositionLocalOf

enum class VRBackgroundMode(val label: String, val resId: Int) {
    PASSTHROUGH("彩透", 0),
    BLACK("纯黑", 0),
    DARK_BLUE("深蓝", 0),
    SKYBOX_1("全景图 1", -1), // R.drawable.skydome_1
    SKYBOX_2("全景图 2", -2), // R.drawable.skydome_2
    SKYBOX_3("全景图 3", -3), // R.drawable.skydome_3
    ;
}

/** Host for VR-specific controls like background, passthrough and spatial audio. */
interface VRHost {
    var backgroundMode: VRBackgroundMode
    var passthroughEnabled: Boolean
    var spatialAudioEnabled: Boolean
}

val LocalVRHost = staticCompositionLocalOf<VRHost?> { null }
