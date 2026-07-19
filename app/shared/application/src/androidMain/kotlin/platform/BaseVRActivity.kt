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
import me.him188.ani.app.ui.foundation.PanelManager
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
        val entry = PanelEntry(PanelManager.PanelSize.WIDE, PanelPosition.MIDDLE)
        val item = panelEntries[entry]!!
        mainPanelEntity = Entity.create(
            Panel(item.id),
            Scale(Vector3(1f)),
        )
        item.panels[mainPanelEntity] = { composeContent?.invoke() }
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
        val leftController = avatarBody.leftHand.tryGetComponent<Controller>()
        val rightController = avatarBody.rightHand.tryGetComponent<Controller>()

        val leftSqueeze = leftController?.let { it.buttonState and ButtonBits.ButtonSqueezeL != 0 } ?: false
        val rightSqueeze = rightController?.let { it.buttonState and ButtonBits.ButtonSqueezeR != 0 } ?: false

        // Suppress panel interaction while dragging
        val dragging = leftSqueeze || rightSqueeze
        setHittable(!dragging)

        val leftPose = if (leftSqueeze) scene.getControllerPoseAtTime(true, System.currentTimeMillis()) else null
        val rightPose = if (rightSqueeze) scene.getControllerPoseAtTime(false, System.currentTimeMillis()) else null

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

        controllerDragger.drag(leftPose, rightPose, thumbX, thumbY)
    }

    /** Thread-safe entity ID counter. */
    private val nextEntityId = AtomicInteger(0)

    private data class ActivePanel(val entity: Entity, val entry: PanelEntry)

    /** Thread-safe map of active sub-panels. */
    private val entityIdMap = ConcurrentHashMap<Int, ActivePanel>()

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
        item.panels[entity] = content
        val id = nextEntityId.getAndIncrement()
        entityIdMap[id] = ActivePanel(entity, entry)
        return id
    }

    override fun closePanel(id: Int) {
        val active = entityIdMap.remove(id) ?: return
        val entity = active.entity
        try {
            entity.removeComponent<Panel>()
        } catch (_: Exception) {
            // Panel component may already have been removed
        }
        try {
            entity.removeComponent<TransformParent>()
        } catch (_: Exception) {
            // TransformParent may already have been removed
        }
        try {
            entity.destroy()
        } catch (_: Exception) {
            // Entity may already have been destroyed
        }
        for (item in panelEntries.values) {
            item.panels.remove(entity)
        }
    }
}
