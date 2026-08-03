/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.danmaku

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import me.him188.ani.app.ui.foundation.LocalPanelManager
import me.him188.ani.app.ui.foundation.PanelHandle
import me.him188.ani.app.ui.foundation.PanelManager
import me.him188.ani.danmaku.ui.DanmakuHost
import me.him188.ani.danmaku.ui.DanmakuHostState
import me.him188.ani.danmaku.ui.DanmakuPresentation
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.isPlaying

/**
 * A [DanmakuHost] that is connected with the [player].
 */
@Composable
fun PlayerDanmakuHost(
    player: MediampPlayer,
    danmakuHostState: DanmakuHostState,
    danmakuEvent: Flow<UIDanmakuEvent>,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(player, danmakuHostState) {
        player.playbackState.collect {
            danmakuHostState.setPaused(!it.isPlaying)
        }
    }
    LaunchedEffect(danmakuEvent, danmakuHostState) {
        danmakuEvent.collect { event ->
            when (event) {
                is UIDanmakuEvent.Add -> {
                    danmakuHostState.trySend(event.presentation)
                }

                is UIDanmakuEvent.Repopulate -> {
                    danmakuHostState.repopulate(event.list, event.currentPositionMillis)
                }
            }
        }
    }


    val panelManager = LocalPanelManager.current ?: return
    // In stereo mode the danmaku sits at the video plane (BEHIND the main panel,
    // in front of the SBS video panel), so it overlays the video instead of
    // floating 0.2 m in front of the whole panel. Reopened when the mode toggles.
    val stereo3d = me.him188.ani.app.platform.LocalVRHost.current?.stereo3dEnabled == true
    var panel by remember { mutableStateOf<PanelHandle?>(null) }
    DisposableEffect(stereo3d) {
        val position =
            if (stereo3d) PanelManager.PanelPosition.BEHIND
            else PanelManager.PanelPosition.MIDDLE
        panel = panelManager.openPanel(
            PanelManager.PanelEntry(PanelManager.PanelSize.WIDE, position, PanelManager.PanelHittable.FALSE),
            options = PanelManager.PanelOpenOptions(
                withControlBar = false, // non-hittable — the control bar is dead UI
                zOffset = if (stereo3d) 0.04f else null,
            ),
        ) {
            DanmakuHost(danmakuHostState, modifier)
        }
        onDispose {
            panel?.close()
        }
    }
}

sealed class UIDanmakuEvent {
    data class Add(
        val presentation: DanmakuPresentation
    ) : UIDanmakuEvent()

    data class Repopulate(
        val list: List<DanmakuPresentation>,
        val currentPositionMillis: Long
    ) : UIDanmakuEvent()
}
