/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 */

package me.him188.ani.app.platform

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import kotlin.math.abs
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
    val options: PanelManager.PanelOpenOptions = PanelManager.PanelOpenOptions(),
) : PanelHandle {

    /** Child scale = main panel scale × [scaleMultiplier] (video panel = 2f). */
    val scaleMultiplier: Float get() = options.scaleMultiplier

    /** Local-Z override, scaled by the child scale; null = position default. Live-adjustable via [setZOffset]. */
    var zOffsetValue: Float? = options.zOffset

    internal var content: (@androidx.compose.runtime.Composable () -> Unit)? = null

    // ── Per-panel state (no global maps) ─────────────────────────────────────

    override var activeMode: PanelControlMode = PanelControlMode.NONE
        internal set

    /**
     * The panel's current intended edge position relative to the main panel.
     * [entry.position] is the initial value; this field tracks changes after
     * drags and re-binds. `null` when unbound.
     */
    var currentPosition: PanelManager.PanelPosition? = entry.position

    /** Dragger for when this panel is grabbed independently (unbound). */
    internal var dragger: ControllerDragger? = null
        private set

    /** Ensure [dragger] is initialized and reset its state to Idle. */
    @OptIn(SpatialSDKExperimentalAPI::class)
    internal fun resetDragger() {
        dragger?.drag(null, null, 0f, 0f)
    }

    internal fun ensureDragger(): ControllerDragger {
        val d = dragger
        if (d != null) return d
        val newDragger = ControllerDragger(object : ControllerDragger.Host {
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
        dragger = newDragger
        return newDragger
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

    override fun setZOffset(z: Float) {
        zOffsetValue = z
        val mainScale = try { host.mainPanelEntity.getComponent<Scale>().scale.x } catch (_: Exception) { 1f }
        host.applyBoundPanelLayout(this, mainScale)
    }

    override fun toggleBind() {
        if (entity.tryGetComponent<TransformParent>() != null) {
            // Unbind: preserve world position, clear tracked position
            val local = entity.getComponent<Transform>().transform
            val parentWorld = host.mainPanelEntity.getComponent<Transform>().transform
            val world = parentWorld * local
            entity.removeComponent<TransformParent>()
            entity.setComponent(Transform(world))
            currentPosition = null
        } else if (entity != host.mainPanelEntity) {
            // Bind: convert world to local, snap to nearest edge
            val world = entity.getComponent<Transform>().transform
            val parentWorld = host.mainPanelEntity.getComponent<Transform>().transform
            val localPos = (parentWorld.inverse() * world).t
            val nearestPos = findNearestPanelPosition(localPos)
            currentPosition = nearestPos
            val newEntry = PanelManager.PanelEntry(entry.size, nearestPos, entry.hittable)
            entry = newEntry
            val subScale = try { entity.getComponent<Scale>().scale.x } catch (_: Exception) { 1f }
            val mainScale = try { host.mainPanelEntity.getComponent<Scale>().scale.x } catch (_: Exception) { 1f }
            val snappedPose = host.calculateRelativePose(newEntry, subScale, mainScale)
            entity.setComponent(Transform(snappedPose))
            entity.setComponent(TransformParent(host.mainPanelEntity))
        }
    }

    /**
     * Find the closest [PanelManager.PanelPosition] for a point in the main
     * panel's local coordinate space. Compares X and Y distances from the
     * main panel center to decide which edge is nearest.
     */
    private fun findNearestPanelPosition(localPos: Vector3): PanelManager.PanelPosition {
        val ax = abs(localPos.x)
        val ay = abs(localPos.y)
        return if (ax > ay) {
            if (localPos.x < 0) PanelManager.PanelPosition.LEFT else PanelManager.PanelPosition.RIGHT
        } else {
            if (localPos.y > 0) PanelManager.PanelPosition.TOP else PanelManager.PanelPosition.BOTTOM
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
