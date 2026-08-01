package me.him188.ani.app.platform

import androidx.compose.runtime.staticCompositionLocalOf

// Skybox images from PolyHaven (CC0):
// spruit_sunrise.jpg     https://polyhaven.com/a/spruit_sunrise
// small_cathedral_02.jpg https://polyhaven.com/a/small_cathedral_02
// fireplace.jpg          https://polyhaven.com/a/fireplace
// reinforced_concrete_01.jpg https://polyhaven.com/a/reinforced_concrete_01
enum class VRBackgroundMode(val label: String, val resId: Int) {
    PASSTHROUGH("彩透", 0),
    BLACK("纯黑", 0),
    DARK_BLUE("深蓝", 0),
    SKYBOX_1("全景图 1", -1), // R.drawable.skydome_1
    SKYBOX_2("全景图 2", -2), // R.drawable.skydome_2
    SKYBOX_3("全景图 3", -3), // R.drawable.skydome_3
    SKYBOX_4("全景图 4", -4), // R.drawable.skydome_4
    ;
}

/** Host for VR-specific controls like background, passthrough and spatial audio. */
interface VRHost {
    var backgroundMode: VRBackgroundMode
    var passthroughEnabled: Boolean
    var spatialAudioEnabled: Boolean

    /**
     * 3D video conversion: video frames are rendered as a side-by-side stereo
     * pair (depth-based DIBR) and the main panel switches to a 2:1 SBS layout.
     */
    var stereo3dEnabled: Boolean

    /**
     * Debug: render the depth map (blue far -> red near) in the right eye
     * instead of the DIBR view, to verify the depth matches the picture.
     */
    var depthDebugEnabled: Boolean
}

val LocalVRHost = staticCompositionLocalOf<VRHost?> { null }
