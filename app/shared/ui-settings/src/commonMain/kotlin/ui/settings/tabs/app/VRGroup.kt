package me.him188.ani.app.ui.settings.tabs.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import me.him188.ani.app.platform.VRBackgroundMode
import me.him188.ani.app.ui.settings.framework.components.DropdownItem
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.SwitchItem

@Composable
fun SettingsScope.VRGroup(
    currentMode: VRBackgroundMode,
    spatialAudioEnabled: Boolean,
    stereo3dEnabled: Boolean,
    depthDebugEnabled: Boolean,
    onModeChanged: (VRBackgroundMode) -> Unit,
    onSpatialAudioChanged: (Boolean) -> Unit,
    onStereo3dChanged: (Boolean) -> Unit,
    onDepthDebugChanged: (Boolean) -> Unit,
) {
    var selectedMode by rememberSaveable { mutableStateOf(currentMode) }

    Group(
        title = { Text("背景环境") },
    ) {
        DropdownItem(
            selected = { selectedMode },
            values = { VRBackgroundMode.entries.toList() },
            itemText = { Text(it.label) },
            onSelect = { mode ->
                selectedMode = mode
                onModeChanged(mode)
            },
            title = { Text("背景模式") },
            description = { Text("选择虚拟背景或开启彩透查看周围环境") },
        )
    }
    Group(
        title = { Text("音频") },
    ) {
        SwitchItem(
            checked = spatialAudioEnabled,
            onCheckedChange = onSpatialAudioChanged,
            title = { Text("空间音频 (Spatial Audio)") },
            description = { Text("声音从虚拟面板的位置发出，随面板移动变化") },
        )
    }
    Group(
        title = { Text("3D 转换") },
    ) {
        SwitchItem(
            checked = stereo3dEnabled,
            onCheckedChange = onStereo3dChanged,
            title = { Text("3D 视频转换 (实验)") },
            description = { Text("用 AI 深度模型将视频转为双目立体画面，面板变为 2:1 并排布局") },
        )
        SwitchItem(
            checked = depthDebugEnabled,
            onCheckedChange = onDepthDebugChanged,
            title = { Text("显示深度图 (调试)") },
            description = { Text("右眼显示深度图伪彩色（蓝远红近），用于验证深度与画面是否匹配") },
        )
    }
}
