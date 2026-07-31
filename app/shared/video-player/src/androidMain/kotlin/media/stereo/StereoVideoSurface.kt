/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media.stereo

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * A Compose surface that renders the video as a side-by-side stereo pair.
 *
 * The [GLSurfaceView] exposes a [SurfaceTexture] (via [onSurfaceTextureReady])
 * which the caller should hand to ExoPlayer (`player.setVideoSurfaceTexture`).
 * [onSurfaceTextureReady] is invoked on the GL thread once the surface is
 * created — the caller must forward the SurfaceTexture to the player there
 * (it is safe to call `setVideoSurfaceTexture` from any thread).
 */
@Composable
fun StereoVideoSurface(
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    strength: Float = 1f,
    onSurfaceTextureReady: (SurfaceTexture) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val logger = remember { logger("StereoVideoSurface") }
    val estimator = remember { AnimeDepthEstimator(context) }
    val rendererRef = remember { arrayOfNulls<StereoDepthRenderer>(1) }

    AndroidView(
        factory = { ctx: Context ->
            val r = StereoDepthRenderer(
                scope = scope,
                estimator = estimator,
                onSurfaceTextureReady = { st ->
                    logger.info { "Stereo surface texture ready" }
                    onSurfaceTextureReady(st)
                },
            )
            rendererRef[0] = r
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                setRenderer(r)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        },
        modifier = modifier,
        update = { view ->
            rendererRef[0]?.let { it.strength = strength }
        },
    )
}
