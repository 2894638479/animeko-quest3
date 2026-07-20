/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A VR-safe replacement for Material3's [androidx.compose.material3.AlertDialog].
 *
 * Uses [VrPanelDialog] internally to avoid the "Window type mismatch" crash
 * on Meta Quest (window type 2037 vs LayoutParams type 2).
 *
 * Mimics AlertDialog's visual layout: icon, title, text body, and action buttons.
 */
@Composable
fun VrAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit = {},
    dismissButton: @Composable () -> Unit = {},
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    VrPanelDialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon?.let {
                    it()
                    Spacer(Modifier.height(16.dp))
                }
                title?.let {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        it()
                    }
                    Spacer(Modifier.height(16.dp))
                }
                text?.let {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        it()
                    }
                    Spacer(Modifier.height(24.dp))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    dismissButton()
                    confirmButton()
                }
            }
        }
    }
}
