/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import android.widget.Toast
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.meta.spatial.core.Entity
import com.meta.spatial.core.PerformanceLevel
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.isdk.ComponentRegistrations
import com.meta.spatial.isdk.IsdkComponentCreationSystem
import com.meta.spatial.isdk.IsdkDefaultCursorSystem
import com.meta.spatial.isdk.IsdkSystem
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.HitInfo
import com.meta.spatial.runtime.InputListener
import com.meta.spatial.runtime.LayerConfig
import com.meta.spatial.runtime.LayerFilters
import com.meta.spatial.runtime.PanelShapeLayerBlendType
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.AvatarSystem
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import me.him188.ani.app.navigation.AniNavigator
import me.him188.ani.app.ui.exprovider.ExternalContentProviderFactory
import me.him188.ani.app.ui.exprovider.LocalExternalContentProvider
import me.him188.ani.app.ui.foundation.LocalPanelManager
import me.him188.ani.app.ui.foundation.PanelControlMode
import me.him188.ani.app.ui.foundation.PanelHandle
import me.him188.ani.app.ui.foundation.PanelManager
import me.him188.ani.app.ui.foundation.VrPanelControlBarHost
import me.him188.ani.app.platform.LocalSpatialAudioHost
import me.him188.ani.app.ui.foundation.PanelManager.PanelEntry
import me.him188.ani.app.ui.foundation.PanelManager.PanelPosition
import me.him188.ani.app.ui.foundation.layout.LocalPlatformWindow
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.Toaster
import me.him188.ani.app.ui.main.AniApp
import me.him188.ani.app.ui.main.AniSubContent
import org.koin.android.ext.android.inject
import java.util.concurrent.ConcurrentHashMap

abstract class BaseVRActivity : AppSystemActivity(), PanelManager, LifecycleOwner, ControllerDragger.Host, SpatialAudioHost {
    lateinit var mainPanelEntity: Entity
    protected var composeContent: (@Composable () -> Unit)? = null

    private val controllerDragger = ControllerDragger(this)
    lateinit var spatialAudio: SpatialAudioManager

    protected val isSceneReady: Boolean get() = ::mainPanelEntity.isInitialized

    // ── ControllerDragger.Host (main panel) ──────────────────────────────────

    override var pose: Pose
        get() = if (::mainPanelEntity.isInitialized)
            mainPanelEntity.tryGetComponent<Transform>(null)?.transform ?: Pose()
        else Pose()
        set(value) { if (::mainPanelEntity.isInitialized) mainPanelEntity.setComponent(Transform(value)) }

    override var scale: Float
        get() = if (::mainPanelEntity.isInitialized)
            mainPanelEntity.tryGetComponent<Scale>(null)?.scale?.x ?: 1f
        else 1f
        set(value) {
            if (!::mainPanelEntity.isInitialized) return
            val clamped = value.coerceIn(0.1f..10f)
            mainPanelEntity.setComponent(Scale(Vector3(clamped)))
            if (::spatialAudio.isInitialized) spatialAudio.updateScale(clamped)
            for (panel in panelByEntity.values) {
                if (panel.isBound) {
                    panel.entity.setComponent(Scale(Vector3(clamped)))
                    panel.entity.setComponent(Transform(calculateRelativePose(panel.entry, clamped)))
                }
            }
        }

    fun setContent(content: @Composable () -> Unit) { composeContent = content }

    private val externalContentProviderFactory: ExternalContentProviderFactory by inject()
    val toaster = object : Toaster {
        override fun toast(text: String) {
            Toast.makeText(this@BaseVRActivity, text, Toast.LENGTH_LONG).show()
        }
    }
    val externalContentProvider by lazy { externalContentProviderFactory.create(this, lifecycleScope) }

    // ── PanelRegistration ────────────────────────────────────────────────────

    private fun PanelRegistration.subView(content: @Composable () -> Unit) = view {
        ComposeView(it).apply {
            setViewTreeLifecycleOwner(this@BaseVRActivity as LifecycleOwner)
            setViewTreeViewModelStoreOwner(this@BaseVRActivity as ViewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(this@BaseVRActivity as SavedStateRegistryOwner)
            setViewTreeOnBackPressedDispatcherOwner(this@BaseVRActivity as OnBackPressedDispatcherOwner)
            val navigator = AniNavigator()
            setContent {
                val externalComponentProviderUpdated by rememberUpdatedState(externalContentProvider)
                CompositionLocalProvider(
                    LocalToaster provides toaster,
                    LocalPanelManager provides this@BaseVRActivity,
                    LocalPlatformWindow provides rememberPlatformWindow(),
                    LocalExternalContentProvider provides externalComponentProviderUpdated,
                    LocalSpatialAudioHost provides this@BaseVRActivity,
                ) {
                    AniApp { AniSubContent(navigator) { content() } }
                }
            }
        }
    }

    /** PanelEntry → registration ID. */
    private val regIds: Map<PanelEntry, Int> =
        PanelEntry.all.withIndex().associate { (i, e) -> e to i + 1 }

    override fun registerPanels() = regIds.map { (entry, regId) ->
        PanelRegistration(regId) {
            config {
                width = entry.size.defaultWidth; height = entry.size.defaultHeight
                layoutWidthInPx = entry.size.widthPx; layoutHeightInPx = entry.size.heightPx
                layoutDpi = entry.size.defaultDpi
                includeGlass = false
                layerBlendType = PanelShapeLayerBlendType.ALPHA_BLEND
                layerConfig = LayerConfig(filters = LayerFilters.HIGHEST_QUALITY)
                enableTransparent = true; alphaMode = AlphaMode.TRANSLUCENT
                themeResourceId = R.style.PanelAppThemeTransparent
            }
            subView { panelByEntity[it]?.content?.invoke() }
        }
    }

    // ── Scene lifecycle ──────────────────────────────────────────────────────

    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        if (::mainPanelEntity.isInitialized) recenterPanel()
    }

    protected fun recenterPanel() {
        if (!::mainPanelEntity.isInitialized) return
        val vp = scene.getViewerPose()
        mainPanelEntity.setComponent(Transform(Pose(vp.t.plus(vp.forward().times(2f)), vp.q)))
        mainPanelEntity.setComponent(Scale(1f))
        if (::spatialAudio.isInitialized) spatialAudio.updateScale(1f)
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    override fun onSceneReady() {
        super.onSceneReady()
        ComponentRegistrations.all().forEach { reg ->
            try { componentManager.registerComponent(reg.clazz, reg.clazz.simpleName ?: "", reg.sendRate, reg.companionObjectInstance) }
            catch (_: Exception) {}
        }
        val isdk = systemManager.tryFindSystem<IsdkSystem>() ?: IsdkSystem().also { systemManager.registerSystem(it) }
        if (systemManager.tryFindSystem<IsdkDefaultCursorSystem>() == null) systemManager.registerSystem(IsdkDefaultCursorSystem(this, isdk))
        if (systemManager.tryFindSystem<IsdkComponentCreationSystem>() == null) systemManager.registerSystem(IsdkComponentCreationSystem())
        if (systemManager.tryFindSystem<AvatarSystem>() == null) systemManager.registerSystem(AvatarSystem())

        val entry = PanelEntry(PanelManager.PanelSize.WIDE, PanelPosition.MIDDLE)
        val regId = regIds[entry]!!
        mainPanelEntity = Entity.create(Panel(regId), Scale(Vector3(1f)))
        val mainPanel = SpatialPanel(mainPanelEntity, entry, this)
        mainPanel.content = { VrPanelControlBarHost(mainPanel, isMainPanel = true) { composeContent?.invoke() } }
        panelByEntity[mainPanelEntity] = mainPanel
        recenterPanel()

        systemManager.findSystem<SceneObjectSystem>().getSceneObject(mainPanelEntity)?.thenAccept { o ->
            o.addInputListener(trackInputHand(true))
        }

        scene.enablePassthrough(true); scene.setReferenceSpace(ReferenceSpace.LOCAL)
        spatial.setPerformanceLevel(PerformanceLevel.BOOST_HINT); scene.setPreferredDisplayRate(120f)

        // Spatial audio — audio sounds like it comes from the main panel position
        spatialAudio = SpatialAudioManager(scene, mainPanelEntity, entry.size.defaultWidth)
        spatialAudio.registerSystem(systemManager, componentManager)
    }

    /** Call when the media player is ready to pass its audio session ID for spatialization. */
    override fun onPlayerAudioSessionReady(sessionId: Int) {
        if (::spatialAudio.isInitialized) spatialAudio.setAudioSessionId(sessionId)
    }

    // ── Shared InputListener ─────────────────────────────────────────────────

    private fun trackInputHand(suppressOnSqueeze: Boolean) = object : InputListener {
        override fun onInput(receiver: SceneObject, hitInfo: HitInfo,
                             sourceOfInput: Entity, changed: Int,
                             buttonState: Int, downTime: Long): Boolean {
            if (changed != 0) lastInputHandEntity = sourceOfInput
            return suppressOnSqueeze &&
                (buttonState and (ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR)) != 0
        }
    }

    // ── Hittable ─────────────────────────────────────────────────────────────

    private var lastHittableEnabled: Boolean? = null

    private fun setHittable(enableInteraction: Boolean) {
        if (lastHittableEnabled == enableInteraction) return
        lastHittableEnabled = enableInteraction
        for (panel in panelByEntity.values) {
            if (!enableInteraction && panel.isBound) continue
            panel.setHittable(enableInteraction)
        }
        // Also handle the main panel entity directly
        try {
            val h = if (enableInteraction) Hittable(MeshCollision.LineTest)
                    else Hittable(MeshCollision.NoCollision)
            mainPanelEntity.setComponent(h)
        } catch (_: Exception) {}
    }

    // ── Per-frame state ──────────────────────────────────────────────────────

    private var lastFrameAvatarBody: AvatarBody? = null
    private var lastInputHandEntity: Entity? = null
    private var lastHandState: HandTrackingDetector.HandState? = null
    private var lastRawPose: Pose? = null
    internal var preferLeftHand: Boolean? = null
    private var leftDragTarget: SpatialPanel? = null
    private var rightDragTarget: SpatialPanel? = null

    // ── Per-frame tick ───────────────────────────────────────────────────────

    @OptIn(SpatialSDKExperimentalAPI::class)
    override fun onSceneTick() {
        super.onSceneTick()
        if (!::mainPanelEntity.isInitialized) return
        val q = Query.where { has(AvatarBody.id) }.eval()
        val la = q.firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled } ?: return
        val avatarBody = la.getComponent<AvatarBody>()
        lastFrameAvatarBody = avatarBody
        val hs = HandTrackingDetector.detect(avatarBody, scene)
        processPanelModes(hs, avatarBody)
        lastHandState = hs
        when (hs.mode) {
            HandTrackingDetector.InputMode.CONTROLLERS -> tickControllers(hs, avatarBody)
            HandTrackingDetector.InputMode.HANDS -> tickHands(hs)
            HandTrackingDetector.InputMode.NONE -> setHittable(true)
        }
    }

    // ── Controller drag system ────────────────────────────────────────────────
    //
    // Design:
    // - Each unbound panel has its own ControllerDragger, created on first grab.
    // - The main panel uses the activity‑level controllerDragger.
    // - When squeeze is pressed, the controller‑to‑panel relative pose is captured
    //   and maintained during the drag. On release, the panel stays where it is.
    // - Bound panels are skipped by findOrKeepTarget — grabbing near a bound panel
    //   falls through to the main panel dragger (same effect as dragging main panel).
    // - Thumbstick during drag: left/right → adjusts scale, up/down → adjusts
    //   distance along the controller ray direction.
    // - Two hands on the same target: pinch‑to‑zoom via controller distance.

    /** Read digital thumbstick axes from a controller. Returns (thumbX, thumbY). */
    private fun readThumbstick(ctrl: Controller?, isLeft: Boolean): Pair<Float, Float> {
        if (ctrl == null) return 0f to 0f
        var tx = 0f; var ty = 0f
        val bs = ctrl.buttonState
        if (isLeft) {
            if (bs and ButtonBits.ButtonThumbLU != 0) ty += 1f
            if (bs and ButtonBits.ButtonThumbLD != 0) ty -= 1f
            if (bs and ButtonBits.ButtonThumbLL != 0) tx -= 1f
            if (bs and ButtonBits.ButtonThumbLR != 0) tx += 1f
        } else {
            if (bs and ButtonBits.ButtonThumbRU != 0) ty += 1f
            if (bs and ButtonBits.ButtonThumbRD != 0) ty -= 1f
            if (bs and ButtonBits.ButtonThumbRL != 0) tx -= 1f
            if (bs and ButtonBits.ButtonThumbRR != 0) tx += 1f
        }
        return tx to ty
    }

    /** Resolve which dragger to use. Bound panels redirect to the main panel. */
    private fun draggerFor(target: SpatialPanel?): ControllerDragger =
        if (target != null && target.isBound) controllerDragger
        else target?.ensureDragger() ?: controllerDragger

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun tickControllers(hs: HandTrackingDetector.HandState, ab: AvatarBody) {
        setHittable(!hs.isDragging)
        val ls = hs.leftActive; val rs = hs.rightActive

        // Find targets — bound panels are excluded by findOrKeepTarget
        val lt = if (ls) findOrKeepTarget(hs.leftPose, leftDragTarget) else null
        val rt = if (rs) findOrKeepTarget(hs.rightPose, rightDragTarget) else null
        leftDragTarget = lt; rightDragTarget = rt

        // Read thumbstick values (used only when squeeze is active)
        val lc = ab.leftHand.tryGetComponent<Controller>()
        val rc = ab.rightHand.tryGetComponent<Controller>()

        // Two hands on the same target → pinch‑to‑zoom (no thumbstick)
        if (lt != null && lt == rt && ls && rs) {
            draggerFor(lt).drag(
                scene.getControllerPoseAtTime(true, System.currentTimeMillis()),
                scene.getControllerPoseAtTime(false, System.currentTimeMillis()), 0f, 0f)
            return
        }

        // Left hand dragging
        if (ls) {
            val (tx, ty) = readThumbstick(lc, isLeft = true)
            draggerFor(lt).drag(
                scene.getControllerPoseAtTime(true, System.currentTimeMillis()),
                null, tx, ty)
        }

        // Right hand dragging — let it co-exist with left hand (different targets)
        if (rs) {
            val (tx, ty) = readThumbstick(rc, isLeft = false)
            draggerFor(rt).drag(
                null,
                scene.getControllerPoseAtTime(false, System.currentTimeMillis()),
                tx, ty)
        }

        // Both released → reset all draggers to Idle
        if (!ls && !rs) {
            leftDragTarget = null; rightDragTarget = null
            controllerDragger.drag(null, null, 0f, 0f)
            for (p in panelByEntity.values) p.resetDragger()
        }
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun tickHands(hs: HandTrackingDetector.HandState) {
        setHittable(!hs.isDragging)
        val la = hs.leftActive; val ra = hs.rightActive

        val lt = if (la) findOrKeepTarget(hs.leftPose, leftDragTarget) else null
        val rt = if (ra) findOrKeepTarget(hs.rightPose, rightDragTarget) else null
        leftDragTarget = lt; rightDragTarget = rt

        // Two hands on same target → pinch‑to‑zoom
        if (lt != null && lt == rt && la && ra) {
            draggerFor(lt).drag(
                scene.getControllerPoseAtTime(true, System.currentTimeMillis()),
                scene.getControllerPoseAtTime(false, System.currentTimeMillis()), 0f, 0f)
            return
        }
        if (la) draggerFor(lt).drag(scene.getControllerPoseAtTime(true, System.currentTimeMillis()), null, 0f, 0f)
        if (ra) draggerFor(rt).drag(null, scene.getControllerPoseAtTime(false, System.currentTimeMillis()), 0f, 0f)
        if (!la && !ra) {
            leftDragTarget = null; rightDragTarget = null
            controllerDragger.drag(null, null, 0f, 0f)
            for (p in panelByEntity.values) p.resetDragger()
        }
    }

    private fun findOrKeepTarget(handPose: Pose?, ref: SpatialPanel?): SpatialPanel? {
        val pos = handPose?.t ?: return ref
        if (ref != null) return ref
        var best: SpatialPanel? = null; var bestDist = Float.MAX_VALUE
        for (p in panelByEntity.values) {
            if (p.isBound) continue
            val pp = try { p.entity.getComponent<Transform>().transform.t } catch (_: Exception) { continue }
            val d = pos.minus(pp).length()
            if (d < 0.5f && d < bestDist) { bestDist = d; best = p }
        }
        return best
    }

    // ── Panel registry ───────────────────────────────────────────────────────

    private val panelByEntity = ConcurrentHashMap<Entity, SpatialPanel>()

    // ── PanelManager.openPanel ───────────────────────────────────────────────

    override fun openPanel(entry: PanelEntry, content: @Composable (() -> Unit)): PanelHandle {
        if (!::mainPanelEntity.isInitialized) error("scene not ready")
        val regId = regIds[entry] ?: error("no registration for $entry")
        val ms = try { mainPanelEntity.getComponent<Scale>().scale.x } catch (_: Exception) { 1f }
        val rp = calculateRelativePose(entry, ms)
        val entity = Entity.create(
            if (entry.hittable == PanelManager.PanelHittable.TRUE) Panel(regId)
            else Panel(regId, MeshCollision.NoCollision),
            Scale(Vector3(ms)), TransformParent(mainPanelEntity), Transform(rp))
        val panel = SpatialPanel(entity, entry, this)
        panel.content = { VrPanelControlBarHost(panel, isMainPanel = false) { content() } }
        panelByEntity[entity] = panel
        systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)?.thenAccept { o ->
            o.addInputListener(trackInputHand(false))
        }
        return panel
    }

    internal fun removePanel(panel: SpatialPanel) {
        val entity = panel.entity
        panelByEntity.remove(entity) ?: return
        if (leftDragTarget == panel) leftDragTarget = null
        if (rightDragTarget == panel) rightDragTarget = null
        try { if (entity.tryGetComponent<TransformParent>() != null) entity.removeComponent<TransformParent>() }
            catch (_: Exception) {}
        try { entity.destroy() } catch (_: Exception) {}
    }

    // ── Ratio swap (internal, called by SpatialPanel) ───────────────────────

    internal fun swapPanelRatio(panel: SpatialPanel, widthPx: Int, heightPx: Int) {
        val entity = panel.entity
        val oldEntry = panel.entry
        val newSize = PanelManager.PanelSize.entries.find { it.widthPx == widthPx && it.heightPx == heightPx } ?: return
        val newEntry = PanelManager.PanelEntry(newSize, oldEntry.position, oldEntry.hittable)
        val newRegId = regIds[newEntry] ?: return
        val c = panel.content ?: return
        try { entity.removeComponent<Panel>() } catch (_: Exception) {}
        entity.setComponent(if (newEntry.hittable == PanelManager.PanelHittable.TRUE) Panel(newRegId)
                            else Panel(newRegId, MeshCollision.NoCollision))
        panel.content = { VrPanelControlBarHost(panel, isMainPanel = false) { c() } }
        panel.entry = newEntry
    }

    // ── processPanelModes ────────────────────────────────────────────────────

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun processPanelModes(hs: HandTrackingDetector.HandState, ab: AvatarBody) {
        val active = panelByEntity.values.filter { it.activeMode != PanelControlMode.NONE }
        if (active.isEmpty()) return

        val leftBtn = ab.leftHand.tryGetComponent<Controller>()?.buttonState ?: 0
        val rightBtn = ab.rightHand.tryGetComponent<Controller>()?.buttonState ?: 0
        // Any button press exits mode
        val endMask = ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR or
                ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR or
                ButtonBits.ButtonA or ButtonBits.ButtonB or ButtonBits.ButtonX or ButtonBits.ButtonY
        if (hs.isDragging || (leftBtn and endMask) != 0 || (rightBtn and endMask) != 0) {
            for (p in active) { p.stopMode() }
            lastRawPose = null; preferLeftHand = null; return
        }

        val lp = scene.getControllerPoseAtTime(true, System.currentTimeMillis()).pose
        val rp = scene.getControllerPoseAtTime(false, System.currentTimeMillis()).pose
        val ap: Pose = when (preferLeftHand) {
            true -> lp
            false -> rp
            else -> return
        } ?: return
        val pp = lastRawPose; lastRawPose = ap
        val dx = if (pp != null) ap.t.x - pp.t.x else 0f
        val dz = if (pp != null) ap.t.z - pp.t.z else 0f

        for (p in active) {
            when (p.activeMode) {
                PanelControlMode.RESIZE -> p.setScale((p.scale + dx).coerceIn(0.1f, 10f))
                PanelControlMode.DISTANCE -> p.setDistance(dz)
                PanelControlMode.MOVE -> try {
                    if (p.moveRelativePose == null) p.moveRelativePose = ap.inverse() * p.entity.getComponent<Transform>().transform
                    p.entity.setComponent(Transform(ap * (p.moveRelativePose ?: continue)))
                } catch (_: Exception) {}
                else -> {}
            }
        }
    }

    // ── Scale propagation ───────────────────────────────────────────────────

    /** Called by [SpatialPanel] when its scale changes. Propagates to spatial audio if main panel. */
    internal fun onMainPanelScaleChanged(scale: Float, entity: Entity) {
        if (entity == mainPanelEntity && ::spatialAudio.isInitialized) {
            spatialAudio.updateScale(scale)
        }
    }

    // ── Clicking-hand detection ─────────────────────────────────────────────

    internal fun detectClickingHand(): Boolean? {
        val li = lastInputHandEntity ?: return null
        val ab = lastFrameAvatarBody ?: return null
        return when { li == ab.leftHand -> true; li == ab.rightHand -> false; else -> null }
    }

    // ── Pose helper ─────────────────────────────────────────────────────────

    private fun calculateRelativePose(entry: PanelEntry, scale: Float): Pose {
        val mw = PanelManager.PanelSize.WIDE.defaultWidth * scale
        val mh = PanelManager.PanelSize.WIDE.defaultHeight * scale
        val sw = entry.size.defaultWidth * scale; val sh = entry.size.defaultHeight * scale
        val mg = 0.08f * scale
        val hp: Vector3; val op: Vector3; var ry = 0f; var rx = 0f
        when (entry.position) {
            PanelPosition.LEFT -> { hp = Vector3(-(mw/2+mg),0f,0f); op = Vector3(-sw/2,0f,0f); ry = -25f }
            PanelPosition.RIGHT -> { hp = Vector3(mw/2+mg,0f,0f); op = Vector3(sw/2,0f,0f); ry = 25f }
            PanelPosition.TOP -> { hp = Vector3(0f,mh/2+mg,0f); op = Vector3(0f,sh/2,0f); rx = -15f }
            PanelPosition.BOTTOM -> { hp = Vector3(0f,-(mh/2+mg),0f); op = Vector3(0f,-sh/2,0f); rx = 15f }
            PanelPosition.MIDDLE -> return Pose(Vector3(0f,0f,-0.2f*scale), Quaternion.fromEuler(0f,0f,0f))
        }
        val q = Quaternion.fromEuler(rx, ry, 0f)
        return Pose(hp.plus(q.times(op)).plus(Vector3(0f,0f,0.02f*scale)), q)
    }
}
