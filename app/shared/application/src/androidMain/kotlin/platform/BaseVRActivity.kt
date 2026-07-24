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
        // Register hand tracking system if the SDK provides one
        tryRegisterSystem("com.meta.spatial.isdk.IsdkHandTrackingSystem")
        tryRegisterSystem("com.meta.spatial.toolkit.HandTrackingSystem")
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
        sceneObjectSystem.getSceneObject(mainPanelEntity)?.thenAccept { sceneObject ->
            sceneObject.addInputListener(object : InputListener {
                override fun onInput(
                    receiver: SceneObject,
                    hitInfo: HitInfo,
                    sourceOfInput: Entity,
                    changed: Int,
                    buttonState: Int,
                    downTime: Long
                ): Boolean {
                    // Suppress input if Squeeze is held
                    return (buttonState and (ButtonBits.ButtonSqueezeL or ButtonBits.ButtonSqueezeR)) != 0
                }
            })
        }

        scene.enablePassthrough(true)
        scene.setReferenceSpace(ReferenceSpace.LOCAL)

        spatial.setPerformanceLevel(PerformanceLevel.BOOST_HINT)
        scene.setPreferredDisplayRate(120f)

        // Enable hand tracking so the app can be used without controllers.
        // The Meta Spatial SDK v0.11.1 may expose this through multiple APIs.
        enableHandTracking()
    }

    /** Try to register an ECS system by class name via reflection. */
    private fun tryRegisterSystem(className: String) {
        try {
            val clazz = Class.forName(className)
            val instance = clazz.getDeclaredConstructor().newInstance()
            val registerMethod = systemManager.javaClass.getMethod(
                "registerSystem", Class.forName("com.meta.spatial.core.System"),
            )
            registerMethod.invoke(systemManager, instance)
        } catch (_: Exception) {
            // System class not available in this SDK version
        }
    }

    /**
     * Try every known Meta Spatial SDK API to enable hand tracking.
     * The exact API varies by SDK version; we try them all via reflection.
     */
    private fun enableHandTracking() {
        // Ordered list of (class, methodName) pairs to try
        val attempts = listOf(
            // Most common: Scene.enableHandTracking(boolean)
            scene to "enableHandTracking",
            scene to "setHandTrackingEnabled",
            // Spatial context
            spatial to "enableHandTracking",
            spatial to "setHandTrackingEnabled",
            // Maybe AppSystemActivity itself
            this@BaseVRActivity to "enableHandTracking",
            this@BaseVRActivity to "setHandTrackingEnabled",
        )
        for ((target, methodName) in attempts) {
            try {
                target.javaClass.getMethod(methodName, Boolean::class.javaPrimitiveType)
                    .invoke(target, true)
                return // Success — stop trying
            } catch (_: NoSuchMethodException) {
                // Method doesn't exist, try next
            } catch (_: Exception) {
                // Invocation failed, try next
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
        val handState = HandTrackingDetector.detect(avatarBody, scene)

        // Process active panel manipulation modes (resize/distance/move)
        // NOTE: uses lastHandState (previous frame) to compute deltas
        processPanelModes(handState)

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

        controllerDragger.drag(
            if (leftSqueeze) leftCp else null,
            if (rightSqueeze) rightCp else null,
            thumbX, thumbY,
        )
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun tickHands(handState: HandTrackingDetector.HandState) {
        // Suppress panel interaction while pinching (dragging)
        setHittable(!handState.isDragging)

        // For hand tracking, convert hand poses to ControllerPose for the dragger.
        // Hand tracking has no thumbstick — all manipulation is via pinch + hand movement.
        val leftCp = handState.leftPose?.let { pose ->
            scene.getControllerPoseAtTime(true, System.currentTimeMillis())
        }
        val rightCp = handState.rightPose?.let {
            scene.getControllerPoseAtTime(false, System.currentTimeMillis())
        }

        controllerDragger.drag(
            if (handState.leftActive) leftCp else null,
            if (handState.rightActive) rightCp else null,
            thumbX = 0f, thumbY = 0f, // no thumbstick on bare hands
        )
    }

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
    /** Which hand is currently driving the manipulation (true=left, false=right, null=undecided). */
    private var activeHandIsLeft: Boolean? = null

    override fun startPanelMode(id: Int, mode: PanelControlMode) {
        panelModes[id] = mode
    }

    override fun stopPanelMode(id: Int) {
        panelModes.remove(id)
        if (panelModes.isEmpty()) lastRawPose = null
    }

    /**
     * Process per-panel manipulation modes using hand/controller pose.
     * Called every frame from onSceneTick.
     */
    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun processPanelModes(handState: HandTrackingDetector.HandState) {
        if (panelModes.isEmpty()) {
            activeHandIsLeft = null
            lastRawPose = null
            return
        }

        // Pinch/squeeze to end: any grip/pinch gesture → stop all modes
        if (handState.leftActive || handState.rightActive) {
            panelModes.clear()
            activeHandIsLeft = null
            lastRawPose = null
            return
        }

        // Get both hand poses
        val leftPose: Pose? = scene.getControllerPoseAtTime(true, System.currentTimeMillis())?.pose
            ?: handState.leftPose
        val rightPose: Pose? = scene.getControllerPoseAtTime(false, System.currentTimeMillis())?.pose
            ?: handState.rightPose

        // Lock onto one hand; prefer the hand with pose data, then right hand
        if (activeHandIsLeft == null) {
            activeHandIsLeft = if (leftPose != null && rightPose != null) false // prefer right
            else leftPose != null
        }

        val activePose: Pose = if (activeHandIsLeft == true) leftPose else rightPose ?: return

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
                        if (prevPose != null) {
                            val delta = activePose.t.minus(prevPose.t)
                            val curLocal = active.entity.getComponent<Transform>().transform.t
                            active.entity.setComponent(Transform(Pose(
                                curLocal.plus(delta),
                                active.entity.getComponent<Transform>().transform.q,
                            )))
                        }
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
