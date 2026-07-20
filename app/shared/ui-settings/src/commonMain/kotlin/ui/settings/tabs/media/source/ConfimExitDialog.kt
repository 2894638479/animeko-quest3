/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.media.source

import me.him188.ani.app.ui.foundation.VrAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.settings_media_source_continue_editing
import me.him188.ani.app.ui.lang.settings_media_source_discard
import me.him188.ani.app.ui.lang.settings_media_source_discard_changes
import org.jetbrains.compose.resources.stringResource

@Composable
fun rememberConfirmDiscardChangeDialogState(
    onConfirm: () -> Unit,
): ConfirmDiscardChangeDialogState {
    val onConfirmState by rememberUpdatedState(onConfirm)
    return remember {
        ConfirmDiscardChangeDialogState(onConfirmState)
    }
}

@Stable
class ConfirmDiscardChangeDialogState(
    val onConfirm: () -> Unit,
) {
    internal var isVisible by mutableStateOf(false)
        private set

    fun show() {
        isVisible = true
    }

    fun dismissDialog() {
        isVisible = false
    }

    fun confirmDiscard() {
        onConfirm()
        dismissDialog()
    }
}

@Composable
fun ConfirmDiscardChangeDialog(
    state: ConfirmDiscardChangeDialogState,
    modifier: Modifier = Modifier,
) {
    if (state.isVisible) {
        VrAlertDialog(
            onDismissRequest = state::dismissDialog,
            title = { Text(stringResource(Lang.settings_media_source_discard_changes)) },
            confirmButton = {
                TextButton(onClick = state::confirmDiscard) {
                    Text(stringResource(Lang.settings_media_source_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = state::dismissDialog) {
                    Text(stringResource(Lang.settings_media_source_continue_editing))
                }
            },
        )
    }
}
