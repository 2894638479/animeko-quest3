/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.foundation.widgets.ModalBottomImeAwareSheet

/**
 * A VR-safe replacement for [androidx.compose.material3.ModalBottomSheet].
 *
 * On Meta Quest / Horizon OS, the standard Material3 [ModalBottomSheet] crashes with
 * "Window type mismatch" (window type 2037 vs TYPE_APPLICATION / TYPE_APPLICATION_PANEL).
 * This composable detects the VR environment and renders content in a spatial panel instead.
 *
 * On non-VR platforms, falls back to [ModalBottomImeAwareSheet].
 *
 * @param onDismissRequest Called when the user dismisses the bottom sheet.
 * @param modifier Modifier applied to the bottom sheet on non-VR platforms.
 *   Ignored in VR (spatial panel handles its own layout).
 * @param content The composable content to render inside the sheet.
 */
@Composable
fun VrSafeModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val panelManager = LocalPanelManager.current

    if (panelManager != null) {
        // VR: render in a 16:9 spatial panel at bottom position
        var panel by remember { mutableStateOf<PanelHandle?>(null) }
        DisposableEffect(Unit) {
            panel = panelManager.openPanel(
                PanelManager.PanelEntry(
                    PanelManager.PanelSize.WIDE,
                    PanelManager.PanelPosition.BOTTOM,
                    PanelManager.PanelHittable.TRUE,
                ),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    content()
                }
            }
            onDispose {
                panel?.close()
                onDismissRequest()
            }
        }
    } else {
        // Non-VR: use ModalBottomImeAwareSheet (avoids Dialog entirely on Android)
        ModalBottomImeAwareSheet(
            onDismiss = onDismissRequest,
            modifier = modifier,
        ) {
            content()
        }
    }
}
