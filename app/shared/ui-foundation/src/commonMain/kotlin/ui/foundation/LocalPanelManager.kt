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

interface PanelManager {
    fun openPanel(entry: PanelEntry, content: @Composable () -> Unit): Int
    fun closePanel(id: Int)

    /** Adjust the scale of a specific panel by ID. */
    fun setPanelScale(id: Int, scale: Float)

    /** Adjust the distance (Z offset) of a specific panel. */
    fun setPanelDistance(id: Int, distance: Float)

    /** Toggle whether the panel is attached to the main panel via [TransformParent]. */
    fun togglePanelBind(id: Int)

    /** True if this panel is bound (child of) the main panel. */
    fun isPanelBound(id: Int): Boolean

    /** Get current panel scale. Returns 1f if panel not found. */
    fun getPanelScale(id: Int): Float

    /** Replace the panel with one of a different [PanelSize] (aspect ratio). */
    fun changePanelRatio(id: Int, widthPx: Int, heightPx: Int, content: @Composable () -> Unit): Int

    enum class PanelSize(val widthPx: Int,val heightPx: Int) {
        WIDE(3840,2160),TALL(2160,3840),SIDE(960,1920);
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
