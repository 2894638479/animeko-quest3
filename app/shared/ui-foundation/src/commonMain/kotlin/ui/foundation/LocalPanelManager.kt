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

val LocalPanelManager = staticCompositionLocalOf<PanelManager> { error("no panelManager") }
