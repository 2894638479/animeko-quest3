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
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Handle to a spatial panel created via [PanelManager.openPanel].
 * All panel manipulation operations are methods on this handle —
 * no need for integer IDs.
 */
interface PanelHandle {
    fun close()
    fun setHittable(enabled: Boolean)
    fun setScale(scale: Float)
    fun setDistance(distance: Float)
    fun toggleBind()
    val isBound: Boolean
    val scale: Float
    fun startMode(mode: PanelControlMode)
    fun stopMode()
    val activeMode: PanelControlMode
    fun changeRatio(widthPx: Int, heightPx: Int)
}

/**
 * Factory for creating spatial panels. Only exposes [openPanel];
 * all other operations are on the returned [PanelHandle].
 */
interface PanelManager {
    fun openPanel(entry: PanelEntry, content: @Composable () -> Unit): PanelHandle

    enum class PanelSize(val widthPx: Int,val heightPx: Int) {
        WIDE(3840,2160),TALL(2160,3840),SIDE(960,1920),
        R4_3(2880,2160),R21_9(3840,1646),R1_1(2160,2160),
        R9_16(2160,3840),R3_4(1620,2160);
        val ratio get() = widthPx.toDouble() / heightPx
        val defaultWidth get() = widthPx / 1000f
        val defaultHeight get() = heightPx / 1000f
        val defaultDpi get() = heightPx / 5
    }
    enum class PanelPosition {
        LEFT,RIGHT,TOP,BOTTOM,MIDDLE;
    }
    enum class PanelHittable {
        TRUE,FALSE;
    }
    data class PanelEntry(val size: PanelSize,val position: PanelPosition,val hittable: PanelHittable = PanelHittable.TRUE) {
        companion object {
            val all = PanelSize.entries.flatMap {
                PanelPosition.entries.flatMap { pos ->
                    PanelHittable.entries.map { hittable ->
                        PanelEntry(it,pos,hittable)
                    }
                }
            }
        }
    }
}

/**
 * The [PanelManager] for the current VR spatial panel host, or `null` if not
 * running in a VR environment (regular Android / Desktop).
 */
val LocalPanelManager = staticCompositionLocalOf<PanelManager?> { null }

/** Which panel control operation is currently active. */
enum class PanelControlMode { NONE, RESIZE, DISTANCE, MOVE }
