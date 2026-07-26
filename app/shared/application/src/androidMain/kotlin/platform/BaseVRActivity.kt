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
import me.him188.ani.app.ui.foundation.PanelManager
import me.him188.ani.app.ui.foundation.VrPanelControlBarHost
import me.him188.ani.app.ui.foundation.PanelManager.PanelEntry
import me.him188.ani.app.ui.foundation.PanelManager.PanelPosition
import me.him188.ani.app.ui.foundation.layout.LocalPlatformWindow
import me.him188.ani.app.ui.foundation.widgets.LocalToaster
import me.him188.ani.app.ui.foundation.widgets.Toaster
import me.him188.ani.app.ui.main.AniApp
import me.him188.ani.app.ui.main.AniSubContent
import kotlin.math.abs
import org.koin.android.ext.android.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

abstract class BaseVRActivity : AppSystemActivity(), PanelManager, LifecycleOwner, ControllerDragger.Host {
    lateinit var mainPanelEntity: Entity
    protected var composeContent: (@Composable () -> Unit)? = null

    private val controllerDragger = ControllerDragger(this)

    /** Returns true if [mainPanelEntity] has been created in [onSceneReady]. */
    protected val isSceneReady: Boolean get() = ::mainPanelEntity.isInitialized

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
            for (active in entityIdMap.values) {
                active.entity.setComponent(Scale(Vector3(clamped)))
                active.entity.setComponent(Transform(calculateRelativePose(active.entry, clamped)))
            }
        }

    fun setContent(content: @Composable () -> Unit) {
        composeContent = content
    }

    private val externalContentProviderFactory: ExternalContentProviderFactory by inject()
    val toaster = object : Toaster {
        override fun toast(text: String) {
            Toast.makeText(this@BaseVRActivity, text, Toast.LENGTH_LONG).show()
        }
    }
    val externalContentProvider by lazy { externalContentProviderFactory.create(this, lifecycleScope) }

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
                        AniSubContent(navigator) {
                            content()
                        }
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
        PanelEntry.all.associateWith {
            PanelEntryItem(id++)
        }
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
            subView {
                item.panels[it]?.invoke()
            }
        }
    }

    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        if (::mainPanelEntity.isInitialized) {
            recenterPanel()
        }
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
                    registration.clazz,
                    registration.clazz.simpleName ?: "",
                    registration.sendRate,
                    registration.companionObjectInstance,
                )
            } catch (_: Exception) {
                // Component may already be registered; skip silently
            }
        }

        val isdkSystem = systemManager.tryFindSystem<IsdkSystem>() ?: IsdkSystem().also {
            systemManager.registerSystem(it)
        }

        if (systemManager.tryFindSystem<IsdkDefaultCursorSystem>() == null) {
            systemManager.registerSystem(IsdkDefaultCursorSystem(this, isdkSystem))
        }
        if (systemManager.tryFindSystem<IsdkComponentCreationSystem>() == null) {
            systemManager.registerSystem(IsdkComponentCreationSystem())
        }
        if (systemManager.tryFindSystem<AvatarSystem>() == null) {
            systemManager.registerSystem(AvatarSystem())
        }
        val entry = PanelEntry(PanelManager.PanelSize.WIDE, PanelPosition.MIDDLE)
        val item = panelEntries[entry]!!
        mainPanelEntity = Entity.create(
            Panel(item.id),
            Scale(Vector3(1f)),
        )
        val mainId = nextEntityId.getAndIncrement()
        entityIdMap[mainId] = ActivePanel(mainPanelEntity, entry)
        entityToPanelId[mainPanelEntity] = mainId
        boundPanels.add(mainId)
        // Store original main panel content for ratio changes
        panelContents[mainId] = { composeContent?.invoke() }
        item.panels[mainPanelEntity] = {
            VrPanelControlBarHost(panelManager = this@BaseVRActivity, panelId = mainId) {
                composeContent?.invoke()
            }
        }
        recenterPanel()

        val sceneObjectSystem = systemManager.findSystem<SceneObjectSystem>()
        sceneObjectSystem.getSceneObject(mainPanelEntity)?.thenAccept { o ->
            o.addInputListener(trackInputHand(true))
        }

        scene.enablePassthrough(true)
        scene.setReferenceSpace(ReferenceSpace.LOCAL)

        spatial.setPerformanceLevel(PerformanceLevel.BOOST_HINT)
        scene.setPreferredDisplayRate(120f)
    }

    /**
     * Creates an [InputListener] that records which hand triggered a click.
     * Only records on actual button state CHANGES (not hover/move events).
     *
     * @param suppressOnSqueeze if true, returns true when squeeze is held to
     *   prevent the event from reaching Compose (used for main panel drag).
     */
    private fun trackInputHand(suppressOnSqueeze: Boolean): InputListener {
        return object : InputListener {
            override fun onInput(
                receiver: SceneObject,
                hitInfo: HitInfo,
                sourceOfInput: Entity,
                changed: Int,
                buttonState: Int,
                downTime: Long,
            ): Boolean {
                // Only record on actual button presses, not hover/move
                if (changed != 0) {
                    lastInputHandEntity = sourceOfInput
                }
                return suppressOnSqueeze &&
                    (buttonState and (ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR)) != 0
            }
        }
    }

    /** Cached hittable state to avoid per-frame setComponent calls. */
    private var lastHittableEnabled: Boolean? = null

    private fun setHittable(enableInteraction: Boolean) {
        // Only update when state actually changes
        if (lastHittableEnabled == enableInteraction) return
        lastHittableEnabled = enableInteraction

        for ((entry, item) in panelEntries) {
            for ((entity, _) in item.panels) {
                // Don't disable hittable on unbound panels — they can be grabbed independently
                val panelId = entityToPanelId[entity]
                if (!enableInteraction && panelId != null && !boundPanels.contains(panelId)) continue
                val hittable = if (!enableInteraction) {
                    MeshCollision.NoCollision
                } else when (entry.hittable) {
                    PanelManager.PanelHittable.TRUE -> MeshCollision.LineTest
                    PanelManager.PanelHittable.FALSE -> MeshCollision.NoCollision
                }
                try {
                    entity.setComponent(Hittable(hittable))
                } catch (_: Exception) {
                    // Entity may have been destroyed between iteration and setComponent
                }
            }
        }
    }

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

        // Process active panel manipulation modes (resize/distance/move)
        processPanelModes(handState, avatarBody)

        // Cache for next frame's delta computation
        lastHandState = handState

        when (handState.mode) {
            HandTrackingDetector.InputMode.CONTROLLERS -> {
                tickControllers(handState, avatarBody)
            }
            HandTrackingDetector.InputMode.HANDS -> {
                tickHands(handState)
            }
            HandTrackingDetector.InputMode.NONE -> {
                setHittable(true)
            }
        }
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun tickControllers(
        handState: HandTrackingDetector.HandState,
        avatarBody: AvatarBody,
    ) {
        val leftController = avatarBody.leftHand.tryGetComponent<Controller>()
        val rightController = avatarBody.rightHand.tryGetComponent<Controller>()

        val leftSqueeze = handState.leftActive
        val rightSqueeze = handState.rightActive

        // Suppress panel interaction while dragging
        setHittable(!handState.isDragging)

        val leftPose = handState.leftPose
        val rightPose = handState.rightPose

        var thumbX = 0f
        var thumbY = 0f

        // Only process thumbstick from the single active controller
        if (leftSqueeze && !rightSqueeze && leftController != null) {
            if (leftController.buttonState and ButtonBits.ButtonThumbLU != 0) thumbY += 1f
            if (leftController.buttonState and ButtonBits.ButtonThumbLD != 0) thumbY -= 1f
            if (leftController.buttonState and ButtonBits.ButtonThumbLL != 0) thumbX -= 1f
            if (leftController.buttonState and ButtonBits.ButtonThumbLR != 0) thumbX += 1f
        } else if (rightSqueeze && !leftSqueeze && rightController != null) {
            if (rightController.buttonState and ButtonBits.ButtonThumbRU != 0) thumbY += 1f
            if (rightController.buttonState and ButtonBits.ButtonThumbRD != 0) thumbY -= 1f
            if (rightController.buttonState and ButtonBits.ButtonThumbRL != 0) thumbX -= 1f
            if (rightController.buttonState and ButtonBits.ButtonThumbRR != 0) thumbX += 1f
        }
        // Disallow diagonal thumbstick to prevent unintended combined actions
        if (thumbX != 0f && thumbY != 0f) { thumbX = 0f; thumbY = 0f }

        // Convert Pose to ControllerPose for the existing dragger interface
        val leftCp = leftPose?.let { pose ->
            val cp = scene.getControllerPoseAtTime(true, System.currentTimeMillis())
            cp ?: scene.getControllerPoseAtTime(true, System.currentTimeMillis())
        }
        val rightCp = rightPose?.let {
            scene.getControllerPoseAtTime(false, System.currentTimeMillis())
        }

        val target = findUnboundDragTarget(leftPose, rightPose)
        if (target != null) {
            dragUnboundPanel(target, leftPose, rightPose, thumbX, thumbY)
        } else {
            controllerDragger.drag(
                if (leftSqueeze) leftCp else null,
                if (rightSqueeze) rightCp else null,
                thumbX, thumbY,
            )
        }
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun tickHands(handState: HandTrackingDetector.HandState) {
        setHittable(!handState.isDragging)
        val leftPose = handState.leftPose
        val rightPose = handState.rightPose
        val leftCp = leftPose?.let { scene.getControllerPoseAtTime(true, System.currentTimeMillis()) }
        val rightCp = rightPose?.let { scene.getControllerPoseAtTime(false, System.currentTimeMillis()) }

        val target = findUnboundDragTarget(leftPose, rightPose)
        if (target != null) {
            dragUnboundPanel(target, leftPose, rightPose, 0f, 0f)
        } else {
            controllerDragger.drag(
                if (handState.leftActive) leftCp else null,
                if (handState.rightActive) rightCp else null,
                thumbX = 0f, thumbY = 0f,
            )
        }
    }

    /** Find the unbound panel closest to the active controller, within grab range. */
    private fun findUnboundDragTarget(leftPose: Pose?, rightPose: Pose?): Int? {
        val handPos = leftPose?.t ?: rightPose?.t ?: return null
        var bestId: Int? = null
        var bestDist = Float.MAX_VALUE
        for ((id, active) in entityIdMap) {
            if (boundPanels.contains(id)) continue
            val panelPos = try {
                active.entity.getComponent<Transform>().transform.t
            } catch (_: Exception) { continue }
            val dist = handPos.minus(panelPos).length()
            if (dist < 0.5f && dist < bestDist) {
                bestDist = dist
                bestId = id
            }
        }
        return bestId
    }

    /** Apply drag to an unbound panel, maintaining relative pose like the main dragger. */
    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun dragUnboundPanel(id: Int, leftPose: Pose?, rightPose: Pose?, thumbX: Float, thumbY: Float) {
        if (unboundDragTargetId != id) {
            unboundDragTargetId = id
            unboundDragStatus = ControllerDragger.Idle()
        }
        val active = entityIdMap[id] ?: return
        val entity = active.entity
        // Simple single-hand drag for unbound panels
        val pose = leftPose ?: rightPose ?: run {
            unboundDragTargetId = null
            return
        }

        val status = unboundDragStatus
        // Use the same state pattern as ControllerDragger
        if (status is ControllerDragger.LeftHand) {
            entity.setComponent(Transform(pose * status.relativePose))
            if (abs(thumbY) > 0.0001f || abs(thumbX) > 0.0001f) {
                unboundDragStatus = ControllerDragger.LeftHand(pose.inverse() * entity.getComponent<Transform>().transform)
            }
        } else if (status is ControllerDragger.RightHand) {
            entity.setComponent(Transform(pose * status.relativePose))
            if (abs(thumbY) > 0.0001f || abs(thumbX) > 0.0001f) {
                unboundDragStatus = ControllerDragger.RightHand(pose.inverse() * entity.getComponent<Transform>().transform)
            }
        } else {
            // First frame: capture relative pose
            val rel = pose.inverse() * entity.getComponent<Transform>().transform
            unboundDragStatus = ControllerDragger.LeftHand(rel)
        }
    }

    /** Dragger state for unbound panel that is currently being grabbed. */
    private var unboundDragTargetId: Int? = null
    private var unboundDragStatus: ControllerDragger.Status = ControllerDragger.Idle()

    /** Thread-safe entity ID counter. */
    private val nextEntityId = AtomicInteger(0)

    private data class ActivePanel(val entity: Entity, val entry: PanelEntry)

    /** Thread-safe map of active sub-panels. */
    private val entityIdMap = ConcurrentHashMap<Int, ActivePanel>()
    /** Reverse mapping: Entity → panel ID for control bar wiring. */
    private val entityToPanelId = ConcurrentHashMap<Entity, Int>()

    private fun calculateRelativePose(entry: PanelEntry, scale: Float): Pose {
        val mainWidth = PanelManager.PanelSize.WIDE.defaultWidth * scale
        val mainHeight = PanelManager.PanelSize.WIDE.defaultHeight * scale
        val subWidth = entry.size.defaultWidth * scale
        val subHeight = entry.size.defaultHeight * scale
        val margin = 0.08f * scale

        val hingePos: Vector3
        val offsetPos: Vector3
        var rotationY = 0f
        var rotationX = 0f

        when (entry.position) {
            PanelPosition.LEFT -> {
                hingePos = Vector3(-(mainWidth / 2 + margin), 0f, 0f)
                offsetPos = Vector3(-subWidth / 2, 0f, 0f)
                rotationY = -25f
            }
            PanelPosition.RIGHT -> {
                hingePos = Vector3(mainWidth / 2 + margin, 0f, 0f)
                offsetPos = Vector3(subWidth / 2, 0f, 0f)
                rotationY = 25f
            }
            PanelPosition.TOP -> {
                hingePos = Vector3(0f, mainHeight / 2 + margin, 0f)
                offsetPos = Vector3(0f, subHeight / 2, 0f)
                rotationX = -15f
            }
            PanelPosition.BOTTOM -> {
                hingePos = Vector3(0f, -(mainHeight / 2 + margin), 0f)
                offsetPos = Vector3(0f, -subHeight / 2, 0f)
                rotationX = 15f
            }
            PanelPosition.MIDDLE -> {
                return Pose(Vector3(0f, 0f, -0.2f * scale), Quaternion.fromEuler(0f, 0f, 0f))
            }
        }

        val q = Quaternion.fromEuler(rotationX, rotationY, 0f)
        val rotatedOffset = q.times(offsetPos)
        val finalPos = hingePos.plus(rotatedOffset).plus(Vector3(0f, 0f, 0.02f * scale))

        return Pose(finalPos, q)
    }

    override fun openPanel(entry: PanelEntry, content: @Composable (() -> Unit)): Int {
        if (!::mainPanelEntity.isInitialized) return -1
        val item = panelEntries[entry] ?: return -1

        val mainScale = try {
            mainPanelEntity.getComponent<Scale>().scale.x
        } catch (_: Exception) {
            return -1
        }

        val relPose = calculateRelativePose(entry, mainScale)

        val entity = Entity.create(
            if (entry.hittable == PanelManager.PanelHittable.TRUE) Panel(item.id)
            else Panel(item.id, MeshCollision.NoCollision),
            Scale(Vector3(mainScale)),
            TransformParent(mainPanelEntity),
            Transform(relPose),
        )
        val id = nextEntityId.getAndIncrement()
        // Store original content for ratio changes etc.
        panelContents[id] = content
        // Wrap content with control bar host
        item.panels[entity] = {
            VrPanelControlBarHost(
                panelManager = this,
                panelId = id,
            ) {
                content()
            }
        }
        entityIdMap[id] = ActivePanel(entity, entry)
        entityToPanelId[entity] = id
        boundPanels.add(id)
        systemManager.findSystem<SceneObjectSystem>().getSceneObject(entity)?.thenAccept { o ->
            o.addInputListener(trackInputHand(false))
        }
        return id
    }

    override fun closePanel(id: Int) {
        val active = entityIdMap.remove(id) ?: return
        boundPanels.remove(id)
        panelContents.remove(id)
        val entity = active.entity
        entityToPanelId.remove(entity)
        // Remove from panelEntries FIRST so setHittable (render thread) won't touch
        // this entity while we destroy it below.
        for (item in panelEntries.values) {
            item.panels.remove(entity)
        }
        // Detach from parent if the entity has one (main panel never does).
        try {
            if (entity.tryGetComponent<TransformParent>() != null) {
                entity.removeComponent<TransformParent>()
            }
        } catch (_: Exception) {}
        // destroy() cascades: all components including Panel are removed,
        // and the ComposeView is detached by the SDK.
        try {
            entity.destroy()
        } catch (_: Exception) {}
    }

    // ── Panel manipulation (PanelManager extended) ──────────────────────────

    /** Tracks which panel IDs are bound to the main panel. */
    private val boundPanels = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** Stores original (unwrapped) content lambdas keyed by panel ID. */
    private val panelContents = ConcurrentHashMap<Int, @Composable () -> Unit>()
    /** Active panel manipulation modes, processed in onSceneTick. */
    private val panelModes = ConcurrentHashMap<Int, PanelControlMode>()
    /** Cached last HandState for gesture-driven panel modes. */
    private var lastHandState: HandTrackingDetector.HandState? = null
    /** Previous frame's raw hand/controller pose, for delta computation. */
    private var lastRawPose: Pose? = null
    /** For MOVE mode: relative offset from hand to panel, maintained each frame. */
    private var moveRelativePose: Pose? = null
    /** Which hand clicked the button to start the mode (true=left, false=right). */
    private var preferLeftHand: Boolean? = null
    /** Entity of the last hand/controller that triggered an input event. */
    private var lastInputHandEntity: Entity? = null
    /** Avatar body from the most recent frame. */
    private var lastFrameAvatarBody: AvatarBody? = null

    @OptIn(SpatialSDKExperimentalAPI::class)
    override fun startPanelMode(id: Int, mode: PanelControlMode) {
        // Determine which hand clicked from the last input source entity
        val lastInput = lastInputHandEntity
        val ab = lastFrameAvatarBody
        preferLeftHand = when {
            lastInput != null && lastInput == ab?.leftHand -> true
            lastInput != null && lastInput == ab?.rightHand -> false
            else -> null
        }
        panelModes[id] = mode
        if (mode == PanelControlMode.MOVE) {
            moveRelativePose = null
        }
    }

    override fun stopPanelMode(id: Int) {
        panelModes.remove(id)
        if (panelModes.isEmpty()) {
            lastRawPose = null
            moveRelativePose = null
            preferLeftHand = null
        }
    }

    override fun getPanelActiveMode(id: Int): PanelControlMode =
        panelModes[id] ?: PanelControlMode.NONE

    /**
     * Process per-panel manipulation modes using hand/controller pose.
     * Called every frame from onSceneTick.
     */
    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun processPanelModes(handState: HandTrackingDetector.HandState, avatarBody: AvatarBody) {
        if (panelModes.isEmpty()) {
            lastRawPose = null
            moveRelativePose = null
            preferLeftHand = null
            return
        }

        // End mode on grip, trigger, or any button press (hand pinch maps to virtual button)
        val leftBtn = avatarBody.leftHand.tryGetComponent<Controller>()?.buttonState ?: 0
        val rightBtn = avatarBody.rightHand.tryGetComponent<Controller>()?.buttonState ?: 0
        val endMask = ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR or
                      ButtonBits.ButtonA or ButtonBits.ButtonB or
                      ButtonBits.ButtonX or ButtonBits.ButtonY
        if (handState.isDragging || (leftBtn and endMask) != 0 || (rightBtn and endMask) != 0) {
            panelModes.clear()
            lastRawPose = null
            moveRelativePose = null
            preferLeftHand = null
            return
        }

        // Get both hand poses
        val leftPose: Pose? = scene.getControllerPoseAtTime(true, System.currentTimeMillis())?.pose
            ?: handState.leftPose
        val rightPose: Pose? = scene.getControllerPoseAtTime(false, System.currentTimeMillis())?.pose
            ?: handState.rightPose

        // Use the hand that was closest to the panel when mode started
        val activePose: Pose = when {
            preferLeftHand == true -> leftPose
            preferLeftHand == false -> rightPose
            else -> leftPose ?: rightPose
        } ?: return

        val prevPose = lastRawPose
        lastRawPose = activePose
        val dx = if (prevPose != null) activePose.t.x - prevPose.t.x else 0f
        val dz = if (prevPose != null) activePose.t.z - prevPose.t.z else 0f

        for ((id, mode) in panelModes.toList()) {
            val active = entityIdMap[id] ?: continue
            when (mode) {
                PanelControlMode.RESIZE -> {
                    val cur = getPanelScale(id)
                    setPanelScale(id, (cur + dx).coerceIn(0.1f, 10f))
                }
                PanelControlMode.DISTANCE -> {
                    setPanelDistance(id, dz)
                }
                PanelControlMode.MOVE -> {
                    try {
                        // Grab-like: maintain fixed offset from hand to panel (position + rotation)
                        if (moveRelativePose == null) {
                            val panelLocal = active.entity.getComponent<Transform>().transform
                            moveRelativePose = activePose.inverse() * panelLocal
                        }
                        val rel = moveRelativePose ?: continue
                        active.entity.setComponent(Transform(activePose * rel))
                    } catch (_: Exception) {}
                }
                else -> {}
            }
        }
    }

    override fun setPanelScale(id: Int, scale: Float) {
        val active = entityIdMap[id] ?: return
        val clamped = scale.coerceIn(0.1f..10f)
        active.entity.setComponent(Scale(Vector3(clamped)))
    }

    override fun setPanelDistance(id: Int, distance: Float) {
        val active = entityIdMap[id] ?: return
        val currentTransform = active.entity.getComponent<Transform>()
        val currentPose = currentTransform.transform
        // Adjust Z offset relative to parent
        val newPos = currentPose.t.plus(
            currentPose.forward().times(distance),
        )
        active.entity.setComponent(Transform(Pose(newPos, currentPose.q)))
    }

    override fun togglePanelBind(id: Int) {
        val active = entityIdMap[id] ?: return
        // Never allow binding the main panel to itself (circular dependency crash)
        if (active.entity == mainPanelEntity && !boundPanels.contains(id)) return
        if (boundPanels.remove(id)) {
            // Unbind: remove TransformParent if present
            try {
                if (active.entity.tryGetComponent<TransformParent>() != null) {
                    active.entity.removeComponent<TransformParent>()
                }
            } catch (_: Exception) {}
        } else {
            // Bind: re-attach to main panel
            try {
                active.entity.setComponent(TransformParent(mainPanelEntity))
                boundPanels.add(id)
            } catch (_: Exception) {}
        }
    }

    override fun isPanelBound(id: Int): Boolean = boundPanels.contains(id)

    override fun getPanelScale(id: Int): Float {
        val active = entityIdMap[id] ?: return 1f
        return try {
            active.entity.getComponent<Scale>().scale.x
        } catch (_: Exception) {
            1f
        }
    }

    override fun changePanelRatio(
        id: Int,
        widthPx: Int,
        heightPx: Int,
        content: @Composable (() -> Unit),
    ): Int {
        val active = entityIdMap[id] ?: return -1
        val entity = active.entity
        val oldEntry = active.entry
        // Find the pre-registered PanelSize matching the requested resolution
        val newSize = PanelManager.PanelSize.entries.find {
            it.widthPx == widthPx && it.heightPx == heightPx
        } ?: return id

        // Build the new PanelEntry with same position/hittable as before
        val newEntry = PanelManager.PanelEntry(newSize, oldEntry.position, oldEntry.hittable)
        val newItem = panelEntries[newEntry] ?: return id
        val oldItem = panelEntries[oldEntry]

        // Get the original content before we touch anything.
        // Fall back to the existing wrapped content if not in panelContents.
        val actualContent: @Composable () -> Unit = panelContents[id]
            ?: (oldItem?.panels?.get(entity) ?: return id)

        // 1. Remove old content from the old entry's panel map
        oldItem?.panels?.remove(entity)

        // 2. Swap the Panel component to the new registration ID
        //    (keeps entity, Transform, Scale, TransformParent intact)
        try {
            entity.removeComponent<Panel>()
        } catch (_: Exception) {}
        entity.setComponent(
            if (newEntry.hittable == PanelManager.PanelHittable.TRUE) Panel(newItem.id)
            else Panel(newItem.id, MeshCollision.NoCollision),
        )

        // 3. Store the content under the new entry + entity
        newItem.panels[entity] = {
            VrPanelControlBarHost(panelManager = this, panelId = id) {
                actualContent()
            }
        }

        // 4. Update our tracking to reflect the new entry
        entityIdMap[id] = ActivePanel(entity, newEntry)
        return id
    }
}
