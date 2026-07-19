/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Per-panel control bar that toggles when the thin trigger zone at the
 * top of the panel is clicked (via VR cursor pinch/tap).
 *
 * Provides buttons for:
 * - **Resize**: pinch to scale panel
 * - **Distance**: pinch to adjust forward/back
 * - **Ratio**: pick a new aspect ratio
 * - **Move**: pinch to reposition
 * - **Bind/Unbind**: toggle attachment to main panel
 */
@Composable
fun VrPanelControlBar(
    visible: Boolean,
    isBound: Boolean,
    onResize: () -> Unit,
    onDistance: () -> Unit,
    onMove: () -> Unit,
    onToggleBind: () -> Unit,
    onRatioSelected: (aspectW: Int, aspectH: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRatioPicker by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Column(modifier = modifier.fillMaxWidth()) {
            // Main button bar
            Surface(
                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                tonalElevation = 4.dp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onResize, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.ZoomIn, "Resize", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDistance, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.FitScreen, "Distance", modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { showRatioPicker = !showRatioPicker },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Rounded.AspectRatio, "Ratio", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onMove, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.OpenWith, "Move", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onToggleBind, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (isBound) Icons.Rounded.Link else Icons.Rounded.LinkOff,
                            if (isBound) "Unbind" else "Bind",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // Ratio picker dropdown
            AnimatedVisibility(visible = showRatioPicker) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    tonalElevation = 4.dp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for ((label, w, h) in COMMON_RATIOS) {
                            TextButton(
                                onClick = {
                                    onRatioSelected(w, h)
                                    showRatioPicker = false
                                },
                            ) {
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Wraps panel content with a control bar at the top.
 *
 * The control bar is always visible (semi-transparent) so users can find it.
 * In VR, the Meta SDK cursor system may not route hover events to Compose,
 * so we keep the bar persistently visible rather than relying on hover detection.
 *
 * A 48dp-high trigger strip at the top provides a generous tap target for
 * finger/controller pointing. Tapping it toggles the full control bar.
 */
@Composable
fun VrPanelControlBarHost(
    panelManager: PanelManager,
    panelId: Int,
    content: @Composable () -> Unit,
) {
    var controlBarVisible by remember { mutableStateOf(true) }  // visible by default
    var activeMode by remember { mutableStateOf(PanelControlMode.NONE) }
    val isBound by remember(panelId) { mutableStateOf(panelManager.isPanelBound(panelId)) }

    Box {
        // Main content — push down slightly to leave room for the bar
        Box(Modifier.padding(top = 44.dp)) {
            content()
        }

        // Control bar at the top
        VrPanelControlBar(
            visible = controlBarVisible || activeMode != PanelControlMode.NONE,
            isBound = isBound,
            onResize = { activeMode = PanelControlMode.RESIZE },
            onDistance = { activeMode = PanelControlMode.DISTANCE },
            onMove = { activeMode = PanelControlMode.MOVE },
            onToggleBind = {
                panelManager.togglePanelBind(panelId)
            },
            onRatioSelected = { w, h ->
                panelManager.changePanelRatio(panelId, w, h) {
                    // Content re-render handled by caller
                }
                activeMode = PanelControlMode.NONE
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Toggle strip: tapping this 48dp bar shows/hides the full control bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.15f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    controlBarVisible = !controlBarVisible
                },
        )
    }
}

/** Which panel control operation is currently active. */
enum class PanelControlMode {
    NONE, RESIZE, DISTANCE, MOVE
}

/** Common aspect ratios used in the ratio picker. */
private val COMMON_RATIOS = listOf(
    Triple("16:9", 3840, 2160),
    Triple("4:3", 2880, 2160),
    Triple("21:9", 3840, 1646),
    Triple("1:1", 2160, 2160),
    Triple("9:16", 2160, 3840),
    Triple("3:4", 1620, 2160),
)
