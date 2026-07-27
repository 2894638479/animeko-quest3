/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 */

package me.him188.ani.app.platform

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import me.him188.ani.app.ui.foundation.PanelControlMode
import me.him188.ani.app.ui.foundation.PanelHandle
import me.him188.ani.app.ui.foundation.PanelManager

class SpatialPanel internal constructor(
    val entity: Entity,
    var entry: PanelManager.PanelEntry,
    private val host: BaseVRActivity,
) : PanelHandle {

    internal var content: (@androidx.compose.runtime.Composable () -> Unit)? = null

    // ── Per-panel state (no global maps) ─────────────────────────────────────

    override var activeMode: PanelControlMode = PanelControlMode.NONE
        internal set

    /** Dragger for when this panel is grabbed independently (unbound). */
    internal val dragger: ControllerDragger by lazy {
        ControllerDragger(object : ControllerDragger.Host {
            override var pose: Pose
                get() = entity.getComponent<Transform>().transform
                set(v) { entity.setComponent(Transform(v)) }
            override var scale: Float
                get() = try { entity.getComponent<Scale>().scale.x } catch (_: Exception) { 1f }
                set(v) {
                    val clamped = v.coerceIn(0.1f, 10f)
                    entity.setComponent(Scale(Vector3(clamped)))
                    host.onMainPanelScaleChanged(clamped, this@SpatialPanel.entity)
                }
        })
    }

    /** For MOVE mode: relative offset from hand to panel. */
    internal var moveRelativePose: Pose? = null

    // ── PanelHandle ──────────────────────────────────────────────────────────

    override fun close() = host.removePanel(this)

    override fun setHittable(enabled: Boolean) {
        val h = if (enabled) {
            if (entry.hittable == PanelManager.PanelHittable.TRUE) MeshCollision.LineTest
            else MeshCollision.NoCollision
        } else MeshCollision.NoCollision
        try { entity.setComponent(Hittable(h)) } catch (_: Exception) {}
    }

    override fun setScale(scale: Float) {
        val clamped = scale.coerceIn(0.1f, 10f)
        entity.setComponent(Scale(Vector3(clamped)))
        host.onMainPanelScaleChanged(clamped, entity)
    }

    override fun setDistance(distance: Float) {
        val t = try { entity.getComponent<Transform>().transform } catch (_: Exception) { Pose() }
        entity.setComponent(Transform(Pose(t.t.plus(t.forward().times(distance)), t.q)))
    }

    override fun toggleBind() {
        if (entity.tryGetComponent<TransformParent>() != null) {
            // Preserve world position: childWorld = parentWorld * childLocal
            val local = entity.getComponent<Transform>().transform
            val parentWorld = host.mainPanelEntity.getComponent<Transform>().transform
            val world = parentWorld * local
            entity.removeComponent<TransformParent>()
            entity.setComponent(Transform(world))
        } else if (entity != host.mainPanelEntity) {
            // Bind: convert world to local relative to main panel
            val world = entity.getComponent<Transform>().transform
            val parentWorld = host.mainPanelEntity.getComponent<Transform>().transform
            val local = parentWorld.inverse() * world
            entity.setComponent(Transform(local))
            entity.setComponent(TransformParent(host.mainPanelEntity))
        }
    }

    override val isBound: Boolean
        get() = entity.tryGetComponent<TransformParent>() != null

    override val scale: Float
        get() = try { entity.getComponent<Scale>().scale.x } catch (_: Exception) { 1f }

    override fun startMode(mode: PanelControlMode) {
        activeMode = mode
        moveRelativePose = null
        setHittable(false)
        host.detectClickingHand()?.let { host.preferLeftHand = it }
    }

    override fun stopMode() {
        activeMode = PanelControlMode.NONE
        moveRelativePose = null
        setHittable(true)
    }

    override fun changeRatio(widthPx: Int, heightPx: Int) {
        host.swapPanelRatio(this, widthPx, heightPx)
    }

    override fun toString(): String = "SpatialPanel(entity=$entity, entry=$entry)"
}
