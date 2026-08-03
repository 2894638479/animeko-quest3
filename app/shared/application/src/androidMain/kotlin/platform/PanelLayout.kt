/*
 * Copyright (C) 2026 OpenAni and contributors.
 */

package me.him188.ani.app.platform

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import me.him188.ani.app.ui.foundation.PanelManager
import me.him188.ani.app.ui.foundation.PanelManager.PanelEntry
import me.him188.ani.app.ui.foundation.PanelManager.PanelPosition

/**
 * Pure pose math for spatial panels: where a sub-panel sits in the main panel's
 * local space, and how a bound panel's layout is recomputed when the main panel
 * scale changes. No Meta component access, no state — everything derives from
 * the panel entries and scales, so the four call sites in BaseVRActivity that
 * used to recompute bound-panel layout inline now share one code path.
 */
object PanelLayout {

    /**
     * The sub-panel's local pose inside the main panel's space.
     *
     * [mainEntry] is the main panel's current entry (its size drives the edge
     * offsets); [entry] is the sub-panel's entry (size + edge position).
     * [subScale]/[mainScale] are the current scales of both panels.
     *
     * For [PanelPosition.MIDDLE] a [zOffset] (default 0.2) moves the panel
     * toward the viewer (negative local Z); for [PanelPosition.BEHIND] it moves
     * the panel behind the main panel plane (positive local Z). Edge positions
     * keep the original hinge/offset/tilt behavior.
     */
    fun relativePose(
        mainEntry: PanelEntry,
        entry: PanelEntry,
        subScale: Float,
        mainScale: Float = subScale,
        zOffset: Float? = null,
    ): Pose {
        val mw = mainEntry.size.defaultWidth * mainScale
        val mh = mainEntry.size.defaultHeight * mainScale
        val sw = entry.size.defaultWidth * subScale
        val sh = entry.size.defaultHeight * subScale
        val mg = 0.08f * mainScale
        val hp: Vector3
        val op: Vector3
        var ry = 0f
        var rx = 0f
        when (entry.position) {
            PanelPosition.LEFT -> {
                hp = Vector3(-(mw / 2 + mg), 0f, 0f); op = Vector3(-sw / 2, 0f, 0f); ry = -25f
            }

            PanelPosition.RIGHT -> {
                hp = Vector3(mw / 2 + mg, 0f, 0f); op = Vector3(sw / 2, 0f, 0f); ry = 25f
            }

            PanelPosition.TOP -> {
                hp = Vector3(0f, mh / 2 + mg, 0f); op = Vector3(0f, sh / 2, 0f); rx = -15f
            }

            PanelPosition.BOTTOM -> {
                hp = Vector3(0f, -(mh / 2 + mg), 0f); op = Vector3(0f, -sh / 2, 0f); rx = 15f
            }

            PanelPosition.MIDDLE ->
                return Pose(Vector3(0f, 0f, -(zOffset ?: 0.2f) * subScale), Quaternion.fromEuler(0f, 0f, 0f))

            PanelPosition.BEHIND ->
                return Pose(Vector3(0f, 0f, +(zOffset ?: 0.05f) * subScale), Quaternion.fromEuler(0f, 0f, 0f))
        }
        val q = Quaternion.fromEuler(rx, ry, 0f)
        return Pose(hp.plus(q.times(op)).plus(Vector3(0f, 0f, 0.02f * subScale)), q)
    }

    /** The [Scale] + [Transform] a bound sub-panel should have at a given main scale. */
    fun boundPanelLayout(
        mainEntry: PanelEntry,
        entry: PanelEntry,
        subScale: Float,
        mainScale: Float,
        zOffset: Float?,
    ): Pair<Scale, Transform> =
        Scale(Vector3(subScale)) to Transform(relativePose(mainEntry, entry, subScale, mainScale, zOffset))
}
