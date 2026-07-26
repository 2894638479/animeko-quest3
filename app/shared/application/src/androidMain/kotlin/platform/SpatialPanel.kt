/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 */

package me.him188.ani.app.platform

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import me.him188.ani.app.ui.foundation.PanelControlMode
import me.him188.ani.app.ui.foundation.PanelHandle
import me.him188.ani.app.ui.foundation.PanelManager

/**
 * Implementation of [PanelHandle] backed by a Meta Spatial SDK [Entity].
 * All manipulation methods operate directly on the entity's ECS components.
 */
class SpatialPanel internal constructor(
    val entity: Entity,
    var entry: PanelManager.PanelEntry,
    private val host: BaseVRActivity,
) : PanelHandle {

    /** Stored content lambda for ratio changes and re-renders. */
    internal var content: (@androidx.compose.runtime.Composable () -> Unit)? = null

    // ── PanelHandle ──────────────────────────────────────────────────────────

    override fun close() = host.removePanel(this)

    override fun setScale(scale: Float) {
        val s = scale.coerceIn(0.1f, 10f)
        entity.setComponent(Scale(Vector3(s)))
    }

    override fun setDistance(distance: Float) {
        val t = try { entity.getComponent<Transform>().transform } catch (_: Exception) { Pose() }
        entity.setComponent(Transform(Pose(t.t.plus(t.forward().times(distance)), t.q)))
    }

    override fun toggleBind() {
        if (entity.tryGetComponent<TransformParent>() != null) {
            entity.removeComponent<TransformParent>()
        } else if (entity != host.mainPanelEntity) {
            // Don't bind main panel to itself
            entity.setComponent(TransformParent(host.mainPanelEntity))
        }
    }

    override val isBound: Boolean
        get() = entity.tryGetComponent<TransformParent>() != null

    override val scale: Float
        get() = try { entity.getComponent<Scale>().scale.x } catch (_: Exception) { 1f }

    override fun startMode(mode: PanelControlMode) {
        host.setPanelMode(this, mode)
    }

    override fun stopMode() {
        host.clearPanelMode(this)
    }

    override val activeMode: PanelControlMode
        get() = host.getPanelMode(this)

    override fun changeRatio(widthPx: Int, heightPx: Int) {
        host.swapPanelRatio(this, widthPx, heightPx)
    }

    override fun toString(): String = "SpatialPanel(entity=$entity, entry=$entry)"
}
