/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * A VR-safe replacement for [androidx.compose.ui.window.Dialog].
 *
 * On Meta Quest / Horizon OS, the standard [androidx.compose.ui.window.Dialog] crashes
 * with "Window type mismatch" because it tries to create a TYPE_PHONE window inside a
 * TYPE_VR_APPLICATION window context. This composable detects the VR environment and
 * renders content in a spatial panel instead.
 *
 * On non-VR platforms (regular Android, Desktop), it falls back to the normal [Dialog].
 *
 * @param onDismissRequest Called when the user dismisses the dialog (e.g., close button,
 *   click outside, or back gesture).
 * @param position Where to place the panel in VR space (default: [PanelManager.PanelPosition.MIDDLE]).
 * @param content The composable content to render inside the dialog/panel.
 */
@Composable
fun VrPanelDialog(
    onDismissRequest: () -> Unit,
    position: PanelManager.PanelPosition = PanelManager.PanelPosition.MIDDLE,
    content: @Composable () -> Unit,
) {
    val panelManager = LocalPanelManager.current

    if (panelManager != null) {
        // VR environment: render content in a spatial panel
        var panelId by remember { mutableStateOf<Int?>(null) }
        DisposableEffect(Unit) {
            panelId = panelManager.openPanel(
                PanelManager.PanelEntry(
                    PanelManager.PanelSize.SIDE,
                    position,
                    PanelManager.PanelHittable.TRUE,
                ),
            ) {
                content()
            }
            onDispose {
                panelId?.let { id -> panelManager.closePanel(id) }
                onDismissRequest()
            }
        }
    } else {
        // Non-VR: use standard Compose Dialog
        @Suppress("InvisibleMember", "InvisibleReference")
        androidx.compose.ui.window.Dialog(
            onDismissRequest = onDismissRequest,
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
            ),
        ) {
            content()
        }
    }
}
