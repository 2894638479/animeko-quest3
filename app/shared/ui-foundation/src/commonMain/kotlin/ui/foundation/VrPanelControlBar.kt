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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.UnfoldLess
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Per-panel control bar with buttons for panel manipulation.
 *
 * When minimized, shows only a small handle. When expanded, shows all buttons:
 * Resize, Distance, Ratio, Move, Bind/Unbind, and Minimize.
 */
@Composable
fun VrPanelControlBar(
    visible: Boolean,
    isBound: Boolean,
    isMainPanel: Boolean,
    onResize: () -> Unit,
    onDistance: () -> Unit,
    onMove: () -> Unit,
    onToggleBind: () -> Unit,
    onRatioSelected: (aspectW: Int, aspectH: Int) -> Unit,
    onToggleVisibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRatioPicker by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    Surface(
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = if (isHovered) 4.dp else 0.dp,
        modifier = modifier
            .hoverable(hoverInteraction)
            .graphicsLayer { alpha = if (isHovered) 0.99f else 0f },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Minimize/expand handle — always visible
            IconButton(
                onClick = {
                    expanded = !expanded
                    if (!expanded) onToggleVisibility()
                },
                modifier = Modifier.size(44.dp).padding(top = 4.dp),
            ) {
                Icon(
                    Icons.Rounded.UnfoldLess,
                    contentDescription = if (expanded) "Minimize" else "Expand",
                    modifier = Modifier.size(24.dp),
                )
            }

            // Expandable button row
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isBound) {
                        IconButton(onClick = onResize, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.ZoomIn, "Resize", modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = onDistance, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.FitScreen, "Distance", modifier = Modifier.size(28.dp))
                        }
                        IconButton(
                            onClick = { showRatioPicker = !showRatioPicker },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Rounded.AspectRatio, "Ratio", modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = onMove, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.OpenWith, "Move", modifier = Modifier.size(28.dp))
                        }
                    }
                    // Main panel: no bind button. Bound panel: show unbind (Link). Unbound: show bind (LinkOff).
                    if (!isMainPanel) {
                        IconButton(onClick = onToggleBind, modifier = Modifier.size(48.dp)) {
                            Icon(
                                if (isBound) Icons.Rounded.Link else Icons.Rounded.LinkOff,
                                if (isBound) "Unbind" else "Bind",
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                }
            }

            // Ratio picker
            AnimatedVisibility(visible = showRatioPicker && expanded) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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

/**
 * Wraps panel content with a control bar at the top.
 *
 * The control bar is always visible by default (a small handle at the panel top).
 * Tapping the handle expands the full button bar.
 */
@Composable
fun VrPanelControlBarHost(
    panel: PanelHandle,
    isMainPanel: Boolean = false,
    content: @Composable () -> Unit,
) {
    var controlBarVisible by remember { mutableStateOf(true) }
    var activeMode by remember { mutableStateOf(PanelControlMode.NONE) }
    var isBound by remember { mutableStateOf(panel.isBound) }

    // Sync with actual state: if mode was ended externally (pinch), reset local state
    if (activeMode != PanelControlMode.NONE && panel.activeMode == PanelControlMode.NONE) {
        activeMode = PanelControlMode.NONE
    }

    Box {
        content()
        VrPanelControlBar(
            visible = controlBarVisible || activeMode != PanelControlMode.NONE,
            isBound = isBound,
            isMainPanel = isMainPanel,
            onResize = {
                panel.startMode(PanelControlMode.RESIZE)
                activeMode = PanelControlMode.RESIZE
            },
            onDistance = {
                panel.startMode(PanelControlMode.DISTANCE)
                activeMode = PanelControlMode.DISTANCE
            },
            onMove = {
                panel.startMode(PanelControlMode.MOVE)
                activeMode = PanelControlMode.MOVE
            },
            onToggleBind = { panel.toggleBind(); isBound = panel.isBound },
            onRatioSelected = { w, h ->
                panel.changeRatio(w, h); activeMode = PanelControlMode.NONE
            },
            onToggleVisibility = { controlBarVisible = !controlBarVisible },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

private val COMMON_RATIOS = listOf(
    Triple("16:9", 3840, 2160),
    Triple("4:3", 2880, 2160),
    Triple("21:9", 3840, 1646),
    Triple("1:1", 2160, 2160),
    Triple("9:16", 2160, 3840),
    Triple("3:4", 1620, 2160),
)
