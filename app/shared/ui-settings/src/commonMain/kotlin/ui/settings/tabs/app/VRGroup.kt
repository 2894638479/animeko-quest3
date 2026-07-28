package me.him188.ani.app.ui.settings.tabs.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.him188.ani.app.ui.settings.framework.components.Group
import me.him188.ani.app.ui.settings.framework.components.SwitchItem

@Composable
fun VRGroup(
    modifier: Modifier = Modifier,
    onPassthroughChanged: ((Boolean) -> Unit)? = null,
    onSpatialAudioChanged: ((Boolean) -> Unit)? = null,
) {
    var passthrough by rememberSaveable { mutableStateOf(true) }
    var spatialAudio by rememberSaveable { mutableStateOf(true) }

    Column(modifier) {
        Group(
            title = { Text("全景视野") },
        ) {
            SwitchItem(
                checked = passthrough,
                onCheckedChange = { checked ->
                    passthrough = checked
                    onPassthroughChanged?.invoke(checked)
                },
                title = { Text("彩透 (Passthrough)") },
                description = { Text("开启后可透过虚拟屏幕看到周围环境") },
            )
        }
        Group(
            title = { Text("音频") },
        ) {
            SwitchItem(
                checked = spatialAudio,
                onCheckedChange = { checked ->
                    spatialAudio = checked
                    onSpatialAudioChanged?.invoke(checked)
                },
                title = { Text("空间音频 (Spatial Audio)") },
                description = { Text("声音从虚拟面板的位置发出，随面板移动变化") },
            )
        }
    }
}
