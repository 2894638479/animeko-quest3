/*
 * Copyright (C) 2026 OpenAni and contributors.
 */

package me.him188.ani.app.videoplayer.media.stereo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.him188.ani.app.platform.LocalVRHost
import me.him188.ani.app.ui.foundation.PanelManager
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.exoplayer.ExoPlayerMediampPlayer

/**
 * Shared state flowing the player across the two ComposeViews involved in the
 * stereo video panel feature: the MAIN panel's ComposeView (which owns the
 * player and writes [player]) and the video panel's ComposeView (which reads it
 * to bind the ExoPlayer surface). Because the [openPanel] content lambda
 * captures this state object rather than the player, a `replacePlayer` swap
 * (spatial-audio session refresh) never leaves the video panel holding a stale
 * player instance.
 */
class StereoVideoPanelState {
    var player by mutableStateOf<MediampPlayer?>(null)
    var ready by mutableStateOf(false)
}

/**
 * Composed in the MAIN panel's video slot when VR stereo is active. Opens a
 * separate SBS stereo video panel bound BEHIND the main panel, and leaves this
 * slot as an empty transparent placeholder so the stereo video behind shows
 * through. The main panel's Compose controls (top bar, progress bar, danmaku)
 * stay in front of the video.
 */
@Composable
fun StereoVideoPanelHost(
    player: MediampPlayer,
    panelManager: PanelManager,
    modifier: Modifier = Modifier,
) {
    val state = remember { StereoVideoPanelState() }
    LaunchedEffect(player) { state.player = player }

    DisposableEffect(panelManager) {
        val handle = panelManager.openPanel(
            PanelManager.PanelEntry(
                PanelManager.PanelSize.SBS,
                PanelManager.PanelPosition.BEHIND,
                PanelManager.PanelHittable.FALSE,
            ),
            options = PanelManager.PanelOpenOptions(
                scaleMultiplier = 2f, // SBS 1920×1080 at 2× = WIDE 3840×2160 physical size
                withControlBar = false, // passive backdrop, not independently grabbable
            ),
        ) {
            StereoVideoPanelContent(state)
        }
        onDispose { handle.close() }
    }

    // Empty/transparent placeholder keeps the video slot sized; the SBS video
    // panel sits behind and shows through.
    Box(modifier)
}

/** Rendered inside the SBS video panel's ComposeView. */
@Composable
private fun StereoVideoPanelContent(state: StereoVideoPanelState) {
    val host = LocalVRHost.current
    var surfaceTexture by remember { mutableStateOf<android.graphics.SurfaceTexture?>(null) }

    fun currentExo(): ExoPlayerMediampPlayer? {
        val p = state.player ?: return null
        val libass = p as? me.him188.ani.app.videoplayer.media.LibassExoPlayerMediampPlayer
        return libass?.exoMediampPlayer ?: p as? ExoPlayerMediampPlayer
    }

    fun bindSurface(st: android.graphics.SurfaceTexture) {
        val exo = currentExo() ?: return
        // ExoPlayer requires setVideoSurface on the main thread; the
        // SurfaceTexture arrives on the GL thread.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val impl = exo.impl
                android.util.Log.i(
                    "StereoVideoPlayer",
                    "setVideoSurface state=${impl.playbackState} " +
                            "videoSize=${impl.videoSize.width}x${impl.videoSize.height}",
                )
                impl.setVideoSurface(android.view.Surface(st))
            } catch (e: Exception) {
                android.util.Log.w("StereoVideoPlayer", "setVideoSurface failed", e)
            }
        }
    }

    StereoVideoSurface(
        scope = rememberCoroutineScope(),
        modifier = Modifier.fillMaxSize(),
        debugShowDepth = host?.depthDebugEnabled == true,
        temporalFilterEnabled = host?.depthTemporalFilterEnabled == true,
        fixedScaleEnabled = host?.depthFixedScaleEnabled == true,
        parallaxDirProvider = { host?.currentParallaxDir() ?: (1f to 0f) },
        onSurfaceTextureReady = { st ->
            surfaceTexture = st
            state.ready = true
            bindSurface(st)
        },
    )

    // Re-bind the same SurfaceTexture whenever the player instance changes.
    LaunchedEffect(state.player) {
        surfaceTexture?.let { bindSurface(it) }
    }
}
