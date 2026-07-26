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
import me.him188.ani.app.ui.foundation.PanelManager.PanelEntry
import me.him188.ani.app.ui.foundation.PanelManager.PanelPosition
import me.him188.ani.app.ui.foundation.layout.LocalPlatformWindow
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.Toaster
import me.him188.ani.app.ui.main.AniApp
import me.him188.ani.app.ui.main.AniSubContent
import org.koin.android.ext.android.inject
import java.util.concurrent.ConcurrentHashMap

abstract class BaseVRActivity : AppSystemActivity(), PanelManager, LifecycleOwner, ControllerDragger.Host {
    lateinit var mainPanelEntity: Entity
    protected var composeContent: (@Composable () -> Unit)? = null

    private val controllerDragger = ControllerDragger(this)

    protected val isSceneReady: Boolean get() = ::mainPanelEntity.isInitialized

    // ── ControllerDragger.Host (main panel) ──────────────────────────────────

    override var pose: Pose
        get() = if (::mainPanelEntity.isInitialized)
            mainPanelEntity.tryGetComponent<Transform>(null)?.transform ?: Pose()
        else Pose()
        set(value) {
            if (::mainPanelEntity.isInitialized) mainPanelEntity.setComponent(Transform(value))
        }

    override var scale: Float
        get() = if (::mainPanelEntity.isInitialized)
            mainPanelEntity.tryGetComponent<Scale>(null)?.scale?.x ?: 1f
        else 1f
        set(value) {
            if (!::mainPanelEntity.isInitialized) return
            val clamped = value.coerceIn(0.1f..10f)
            mainPanelEntity.setComponent(Scale(Vector3(clamped)))
            for (panel in panelByEntity.values) {
                if (panel.entity.tryGetComponent<TransformParent>() != null) {
                    panel.entity.setComponent(Scale(Vector3(clamped)))
                    panel.entity.setComponent(
                        Transform(calculateRelativePose(panel.entry, clamped)))
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
                ) {
                    AniApp {
                        AniSubContent(navigator) { content() }
                    }
                }
            }
        }
    }

    class PanelEntryItem(val id: Int) {
        val panels = ConcurrentHashMap<Entity, @Composable () -> Unit>()
    }

    val panelEntries: Map<PanelEntry, PanelEntryItem> = run {
        var id = 1
        PanelEntry.all.associateWith { PanelEntryItem(id++) }
    }

    override fun registerPanels() = panelEntries.map { (entry, item) ->
        PanelRegistration(item.id) {
            config {
                width = entry.size.defaultWidth
                height = entry.size.defaultHeight
                layoutWidthInPx = entry.size.widthPx
                layoutHeightInPx = entry.size.heightPx
                layoutDpi = entry.size.defaultDpi
                includeGlass = false
                layerBlendType = PanelShapeLayerBlendType.ALPHA_BLEND
                layerConfig = LayerConfig(filters = LayerFilters.HIGHEST_QUALITY)
                enableTransparent = true
                alphaMode = AlphaMode.TRANSLUCENT
                themeResourceId = R.style.PanelAppThemeTransparent
            }
            subView { item.panels[it]?.invoke() }
        }
    }

    // ── Scene lifecycle ──────────────────────────────────────────────────────

    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        if (::mainPanelEntity.isInitialized) recenterPanel()
    }

    protected fun recenterPanel() {
        if (!::mainPanelEntity.isInitialized) return
        val viewerPose = scene.getViewerPose()
        val newPosition = viewerPose.t.plus(viewerPose.forward().times(2f))
        mainPanelEntity.setComponent(Transform(Pose(newPosition, viewerPose.q)))
        mainPanelEntity.setComponent(Scale(1f))
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    override fun onSceneReady() {
        super.onSceneReady()
        ComponentRegistrations.all().forEach { registration ->
            try {
                componentManager.registerComponent(
                    registration.clazz, registration.clazz.simpleName ?: "",
                    registration.sendRate, registration.companionObjectInstance)
            } catch (_: Exception) {}
        }
        val isdkSystem = systemManager.tryFindSystem<IsdkSystem>()
            ?: IsdkSystem().also { systemManager.registerSystem(it) }
        if (systemManager.tryFindSystem<IsdkDefaultCursorSystem>() == null)
            systemManager.registerSystem(IsdkDefaultCursorSystem(this, isdkSystem))
        if (systemManager.tryFindSystem<IsdkComponentCreationSystem>() == null)
            systemManager.registerSystem(IsdkComponentCreationSystem())
        if (systemManager.tryFindSystem<AvatarSystem>() == null)
            systemManager.registerSystem(AvatarSystem())

        val entry = PanelEntry(PanelManager.PanelSize.WIDE, PanelPosition.MIDDLE)
        val item = panelEntries[entry]!!
        mainPanelEntity = Entity.create(Panel(item.id), Scale(Vector3(1f)))
        val mainPanel = SpatialPanel(mainPanelEntity, entry, this)
        mainPanel.content = { composeContent?.invoke() }
        panelByEntity[mainPanelEntity] = mainPanel
        item.panels[mainPanelEntity] = {
            VrPanelControlBarHost(mainPanel) { composeContent?.invoke() }
        }
        mainPanels.add(mainPanel)
        recenterPanel()

        systemManager.findSystem<SceneObjectSystem>()
            .getSceneObject(mainPanelEntity)?.thenAccept { o ->
                o.addInputListener(trackInputHand(true))
            }

        scene.enablePassthrough(true)
        scene.setReferenceSpace(ReferenceSpace.LOCAL)
        spatial.setPerformanceLevel(PerformanceLevel.BOOST_HINT)
        scene.setPreferredDisplayRate(120f)
    }

    // ── Shared InputListener factory ─────────────────────────────────────────

    private fun trackInputHand(suppressOnSqueeze: Boolean): InputListener = object : InputListener {
        override fun onInput(receiver: SceneObject, hitInfo: HitInfo,
                             sourceOfInput: Entity, changed: Int,
                             buttonState: Int, downTime: Long): Boolean {
            if (changed != 0) lastInputHandEntity = sourceOfInput
            return suppressOnSqueeze &&
                (buttonState and (ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR)) != 0
        }
    }

    // ── Hittable management ──────────────────────────────────────────────────

    private var lastHittableEnabled: Boolean? = null

    private fun setHittable(enableInteraction: Boolean) {
        if (lastHittableEnabled == enableInteraction) return
        lastHittableEnabled = enableInteraction
        for ((entry, item) in panelEntries) {
            for ((entity, _) in item.panels) {
                if (!enableInteraction && entity.tryGetComponent<TransformParent>() == null) continue
                val hittable = if (!enableInteraction) MeshCollision.NoCollision
                else when (entry.hittable) {
                    PanelManager.PanelHittable.TRUE -> MeshCollision.LineTest
                    PanelManager.PanelHittable.FALSE -> MeshCollision.NoCollision
                }
                try { entity.setComponent(Hittable(hittable)) } catch (_: Exception) {}
            }
        }
    }

    // ── Per-frame tick ───────────────────────────────────────────────────────

    private var lastFrameAvatarBody: AvatarBody? = null
    private var lastInputHandEntity: Entity? = null
    private var lastHandState: HandTrackingDetector.HandState? = null
    private var lastRawPose: Pose? = null
    private var moveRelativePose: Pose? = null
    private var preferLeftHand: Boolean? = null

    @OptIn(SpatialSDKExperimentalAPI::class)
    override fun onSceneTick() {
        super.onSceneTick()
        if (!::mainPanelEntity.isInitialized) return

        val query = Query.where { has(AvatarBody.id) }.eval()
        val localPlayerAvatar = query.firstOrNull {
            it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled
        } ?: return

        val avatarBody = localPlayerAvatar.getComponent<AvatarBody>()
        lastFrameAvatarBody = avatarBody
        val handState = HandTrackingDetector.detect(avatarBody, scene)
        processPanelModes(handState, avatarBody)
        lastHandState = handState

        when (handState.mode) {
            HandTrackingDetector.InputMode.CONTROLLERS -> tickControllers(handState, avatarBody)
            HandTrackingDetector.InputMode.HANDS -> tickHands(handState)
            HandTrackingDetector.InputMode.NONE -> setHittable(true)
        }
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun tickControllers(handState: HandTrackingDetector.HandState, avatarBody: AvatarBody) {
        val leftSqueeze = handState.leftActive
        val rightSqueeze = handState.rightActive
        setHittable(!handState.isDragging)
        val leftPose = handState.leftPose
        val rightPose = handState.rightPose

        val leftTarget = if (leftSqueeze) findOrKeepTarget(leftPose, ref = leftDragTarget) else null
        val rightTarget = if (rightSqueeze) findOrKeepTarget(rightPose, ref = rightDragTarget) else null
        leftDragTarget = leftTarget
        rightDragTarget = rightTarget

        if (leftTarget != null && leftTarget == rightTarget) {
            draggerFor(leftTarget!!).drag(
                leftPose?.let { scene.getControllerPoseAtTime(true, System.currentTimeMillis()) },
                rightPose?.let { scene.getControllerPoseAtTime(false, System.currentTimeMillis()) },
                0f, 0f)
            return
        }
        if (leftSqueeze) routeDrag(leftTarget, leftPose, isLeft = true)
        if (rightSqueeze) routeDrag(rightTarget, rightPose, isLeft = false)

        if (!leftSqueeze && !rightSqueeze) { leftDragTarget = null; rightDragTarget = null }

        // Thumbstick on main panel only
        val leftCtrl = avatarBody.leftHand.tryGetComponent<Controller>()
        val rightCtrl = avatarBody.rightHand.tryGetComponent<Controller>()
        if (leftSqueeze && !rightSqueeze && leftTarget == null && leftCtrl != null)
            thumbstickDrag(leftCtrl, leftPose, isLeft = true)
        else if (rightSqueeze && !leftSqueeze && rightTarget == null && rightCtrl != null)
            thumbstickDrag(rightCtrl, rightPose, isLeft = false)
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun tickHands(handState: HandTrackingDetector.HandState) {
        setHittable(!handState.isDragging)
        val leftPose = handState.leftPose
        val rightPose = handState.rightPose
        val leftActive = handState.leftActive
        val rightActive = handState.rightActive

        val leftTarget = if (leftActive) findOrKeepTarget(leftPose, ref = leftDragTarget) else null
        val rightTarget = if (rightActive) findOrKeepTarget(rightPose, ref = rightDragTarget) else null
        leftDragTarget = leftTarget; rightDragTarget = rightTarget

        if (leftTarget != null && leftTarget == rightTarget) {
            draggerFor(leftTarget!!).drag(
                leftPose?.let { scene.getControllerPoseAtTime(true, System.currentTimeMillis()) },
                rightPose?.let { scene.getControllerPoseAtTime(false, System.currentTimeMillis()) },
                0f, 0f)
            return
        }
        if (leftActive) routeDrag(leftTarget, leftPose, isLeft = true)
        if (rightActive) routeDrag(rightTarget, rightPose, isLeft = false)
        if (!leftActive && !rightActive) { leftDragTarget = null; rightDragTarget = null }
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun routeDrag(target: SpatialPanel?, handPose: Pose?, isLeft: Boolean) {
        val cp = if (isLeft) scene.getControllerPoseAtTime(true, System.currentTimeMillis())
                 else scene.getControllerPoseAtTime(false, System.currentTimeMillis())
        if (target != null) draggerFor(target).drag(cp, null, 0f, 0f)
        else controllerDragger.drag(cp, null, 0f, 0f)
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun thumbstickDrag(ctrl: Controller, handPose: Pose?, isLeft: Boolean) {
        var tx = 0f; var ty = 0f
        if (isLeft) {
            if (ctrl.buttonState and ButtonBits.ButtonThumbLU != 0) ty += 1f
            if (ctrl.buttonState and ButtonBits.ButtonThumbLD != 0) ty -= 1f
            if (ctrl.buttonState and ButtonBits.ButtonThumbLL != 0) tx -= 1f
            if (ctrl.buttonState and ButtonBits.ButtonThumbLR != 0) tx += 1f
        } else {
            if (ctrl.buttonState and ButtonBits.ButtonThumbRU != 0) ty += 1f
            if (ctrl.buttonState and ButtonBits.ButtonThumbRD != 0) ty -= 1f
            if (ctrl.buttonState and ButtonBits.ButtonThumbRL != 0) tx -= 1f
            if (ctrl.buttonState and ButtonBits.ButtonThumbRR != 0) tx += 1f
        }
        if (tx == 0f && ty == 0f) return
        val cp = if (isLeft) scene.getControllerPoseAtTime(true, System.currentTimeMillis())
                 else scene.getControllerPoseAtTime(false, System.currentTimeMillis())
        controllerDragger.drag(cp, null, tx, ty)
    }

    private fun findOrKeepTarget(handPose: Pose?, ref: SpatialPanel?): SpatialPanel? {
        val pos = handPose?.t ?: return ref
        if (ref != null) return ref
        var best: SpatialPanel? = null
        var bestDist = Float.MAX_VALUE
        for (panel in panelByEntity.values) {
            if (panel.entity.tryGetComponent<TransformParent>() != null) continue
            val pp = try { panel.entity.getComponent<Transform>().transform.t }
                catch (_: Exception) { continue }
            val dist = pos.minus(pp).length()
            if (dist < 0.5f && dist < bestDist) { bestDist = dist; best = panel }
        }
        return best
    }

    // ── Panel manipulation (PanelManager + internal helpers) ─────────────────

    private val panelByEntity = ConcurrentHashMap<Entity, SpatialPanel>()
    private val panelDraggers = ConcurrentHashMap<Entity, ControllerDragger>()
    private val mainPanels = mutableSetOf<SpatialPanel>()
    private var leftDragTarget: SpatialPanel? = null
    private var rightDragTarget: SpatialPanel? = null
    private val panelModes = ConcurrentHashMap<Entity, PanelControlMode>()

    private fun draggerFor(panel: SpatialPanel): ControllerDragger =
        panelDraggers.getOrPut(panel.entity) {
            ControllerDragger(object : ControllerDragger.Host {
                override var pose: Pose
                    get() = panel.entity.getComponent<Transform>().transform
                    set(v) { panel.entity.setComponent(Transform(v)) }
                override var scale: Float
                    get() = try { panel.entity.getComponent<Scale>().scale.x } catch (_: Exception) { 1f }
                    set(v) { panel.entity.setComponent(Scale(Vector3(v.coerceIn(0.1f, 10f)))) }
            })
        }

    // ── PanelManager.openPanel ───────────────────────────────────────────────

    override fun openPanel(entry: PanelEntry, content: @Composable (() -> Unit)): PanelHandle {
        if (!::mainPanelEntity.isInitialized) error("scene not ready")
        val item = panelEntries[entry] ?: error("no registration for $entry")
        val mainScale = try { mainPanelEntity.getComponent<Scale>().scale.x }
            catch (_: Exception) { 1f }
        val relPose = calculateRelativePose(entry, mainScale)
        val entity = Entity.create(
            if (entry.hittable == PanelManager.PanelHittable.TRUE) Panel(item.id)
            else Panel(item.id, MeshCollision.NoCollision),
            Scale(Vector3(mainScale)),
            TransformParent(mainPanelEntity),
            Transform(relPose))
        val panel = SpatialPanel(entity, entry, this)
        panel.content = content
        item.panels[entity] = {
            VrPanelControlBarHost(panel) { content() }
        }
        panelByEntity[entity] = panel
        systemManager.findSystem<SceneObjectSystem>()
            .getSceneObject(entity)?.thenAccept { o -> o.addInputListener(trackInputHand(false)) }
        return panel
    }

    internal fun removePanel(panel: SpatialPanel) {
        val entity = panel.entity
        panelByEntity.remove(entity) ?: return
        mainPanels.remove(panel)
        panelDraggers.remove(entity)
        panelModes.remove(entity)
        if (leftDragTarget == panel) leftDragTarget = null
        if (rightDragTarget == panel) rightDragTarget = null
        for (item in panelEntries.values) { item.panels.remove(entity) }
        try { if (entity.tryGetComponent<TransformParent>() != null) entity.removeComponent<TransformParent>() }
            catch (_: Exception) {}
        try { entity.destroy() } catch (_: Exception) {}
    }

    // ── Internal helpers (called by SpatialPanel) ────────────────────────────

    internal fun setPanelMode(panel: SpatialPanel, mode: PanelControlMode) {
        panelModes[panel.entity] = mode
    }

    internal fun clearPanelMode(panel: SpatialPanel) {
        panelModes.remove(panel.entity)
        if (panelModes.isEmpty()) { lastRawPose = null; moveRelativePose = null; preferLeftHand = null }
    }

    internal fun getPanelMode(panel: SpatialPanel): PanelControlMode =
        panelModes[panel.entity] ?: PanelControlMode.NONE

    internal fun swapPanelRatio(panel: SpatialPanel, widthPx: Int, heightPx: Int) {
        val entity = panel.entity
        val oldEntry = panel.entry
        val newSize = PanelManager.PanelSize.entries.find {
            it.widthPx == widthPx && it.heightPx == heightPx
        } ?: return
        val newEntry = PanelManager.PanelEntry(newSize, oldEntry.position, oldEntry.hittable)
        val newItem = panelEntries[newEntry] ?: return
        val oldItem = panelEntries[oldEntry]
        val content = panel.content ?: (oldItem?.panels?.get(entity) ?: return)

        oldItem?.panels?.remove(entity)
        try { entity.removeComponent<Panel>() } catch (_: Exception) {}
        entity.setComponent(
            if (newEntry.hittable == PanelManager.PanelHittable.TRUE) Panel(newItem.id)
            else Panel(newItem.id, MeshCollision.NoCollision))
        newItem.panels[entity] = { VrPanelControlBarHost(panel) { content() } }
        panel.entry = newEntry
    }

    // ── processPanelModes ────────────────────────────────────────────────────

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun processPanelModes(handState: HandTrackingDetector.HandState, avatarBody: AvatarBody) {
        if (panelModes.isEmpty()) {
            lastRawPose = null; moveRelativePose = null; preferLeftHand = null; return
        }
        val leftBtn = avatarBody.leftHand.tryGetComponent<Controller>()?.buttonState ?: 0
        val rightBtn = avatarBody.rightHand.tryGetComponent<Controller>()?.buttonState ?: 0
        val endMask = ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR or
                      ButtonBits.ButtonA or ButtonBits.ButtonB or
                      ButtonBits.ButtonX or ButtonBits.ButtonY
        if (handState.isDragging || (leftBtn and endMask) != 0 || (rightBtn and endMask) != 0) {
            panelModes.clear(); lastRawPose = null; moveRelativePose = null; preferLeftHand = null; return
        }

        val leftPose = scene.getControllerPoseAtTime(true, System.currentTimeMillis())?.pose
            ?: handState.leftPose
        val rightPose = scene.getControllerPoseAtTime(false, System.currentTimeMillis())?.pose
            ?: handState.rightPose
        val activePose: Pose = when { preferLeftHand == true -> leftPose
            preferLeftHand == false -> rightPose; else -> leftPose ?: rightPose } ?: return

        val prevPose = lastRawPose; lastRawPose = activePose
        val dx = if (prevPose != null) activePose.t.x - prevPose.t.x else 0f
        val dz = if (prevPose != null) activePose.t.z - prevPose.t.z else 0f

        for ((entity, mode) in panelModes.toList()) {
            val panel = panelByEntity[entity] ?: continue
            when (mode) {
                PanelControlMode.RESIZE -> panel.setScale((panel.scale + dx).coerceIn(0.1f, 10f))
                PanelControlMode.DISTANCE -> panel.setDistance(dz)
                PanelControlMode.MOVE -> {
                    try {
                        if (moveRelativePose == null) {
                            val loc = entity.getComponent<Transform>().transform
                            moveRelativePose = activePose.inverse() * loc
                        }
                        entity.setComponent(Transform(activePose * (moveRelativePose ?: continue)))
                    } catch (_: Exception) {}
                }
                else -> {}
            }
        }
    }

    // ── PanelManager.openPanel (start mode detection) ────────────────────────

    @OptIn(SpatialSDKExperimentalAPI::class)
    internal fun detectClickingHand(): Boolean? {
        val lastInput = lastInputHandEntity ?: return null
        val ab = lastFrameAvatarBody ?: return null
        return when {
            lastInput == ab.leftHand -> true
            lastInput == ab.rightHand -> false
            else -> null
        }
    }

    // ── Pose calculation ─────────────────────────────────────────────────────

    private fun calculateRelativePose(entry: PanelEntry, scale: Float): Pose {
        val mainW = PanelManager.PanelSize.WIDE.defaultWidth * scale
        val mainH = PanelManager.PanelSize.WIDE.defaultHeight * scale
        val subW = entry.size.defaultWidth * scale
        val subH = entry.size.defaultHeight * scale
        val margin = 0.08f * scale
        val hingePos: Vector3; val offsetPos: Vector3; var rY = 0f; var rX = 0f
        when (entry.position) {
            PanelPosition.LEFT -> {
                hingePos = Vector3(-(mainW / 2 + margin), 0f, 0f); offsetPos = Vector3(-subW / 2, 0f, 0f); rY = -25f }
            PanelPosition.RIGHT -> {
                hingePos = Vector3(mainW / 2 + margin, 0f, 0f); offsetPos = Vector3(subW / 2, 0f, 0f); rY = 25f }
            PanelPosition.TOP -> {
                hingePos = Vector3(0f, mainH / 2 + margin, 0f); offsetPos = Vector3(0f, subH / 2, 0f); rX = -15f }
            PanelPosition.BOTTOM -> {
                hingePos = Vector3(0f, -(mainH / 2 + margin), 0f); offsetPos = Vector3(0f, -subH / 2, 0f); rX = 15f }
            PanelPosition.MIDDLE -> return Pose(Vector3(0f, 0f, -0.2f * scale), Quaternion.fromEuler(0f, 0f, 0f))
        }
        val q = Quaternion.fromEuler(rX, rY, 0f)
        return Pose(hingePos.plus(q.times(offsetPos)).plus(Vector3(0f, 0f, 0.02f * scale)), q)
    }
}
