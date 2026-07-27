/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 */
package me.him188.ani.app.platform

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.meta.spatial.spatialaudio.AudioSessionId
import com.meta.spatial.spatialaudio.AudioSessionStereoOffsets
import com.meta.spatial.spatialaudio.AudioType
import com.meta.spatial.spatialaudio.SpatialAudioFeature
import com.meta.spatial.spatialaudio.StereoOffsetMode

/**
 * Manages spatial audio via the Meta Spatial SDK's spatial audio feature.
 *
 * How it works:
 * 1. [SpatialAudioFeature.onCreate] initializes the native audio engine
 * 2. The feature registers [com.meta.spatial.spatialaudio.AudioSessionManagerSystem]
 *    as an ECS system with the native handle set
 * 3. Register the Android audio session ID via [SpatialAudioFeature.registerAudioSessionId]
 * 4. Attach an [AudioSessionId] component to the panel entity
 * 5. The ECS system automatically reads the entity's world-space [com.meta.spatial.toolkit.Transform]
 *    every frame and spatializes the audio to that position
 * 6. [AudioSessionStereoOffsets] places left/right channels at the panel's left/right edge centers,
 *    scaled to match the panel size
 */
class SpatialAudioManager(
    private val scene: Scene,
    private val panelEntity: Entity,
    private val panelWidth: Float, // default width in meters (PanelSize.defaultWidth)
) {
    private val feature = SpatialAudioFeature()
    private var audioSessionId: Int = 0
    private var currentScale: Float = 1f
    private var initialized = false

    /**
     * Initialize the spatial audio engine. Call once from BaseVRActivity.onSceneReady().
     */
    fun registerSystem(
        systemManager: com.meta.spatial.core.SystemManager,
        componentManager: com.meta.spatial.core.ComponentManager,
    ) {
        if (initialized) return
        initialized = true

        // Initialize native audio engine — this creates the native audio manager
        // handle and sets it on AudioSessionManagerSystem via setAudioManagerHandle().
        feature.onCreate(null)

        // Register spatial audio ECS components
        feature.componentsToRegister().forEach { reg ->
            try {
                componentManager.registerComponent(
                    reg.clazz, reg.clazz.simpleName ?: "", reg.sendRate, reg.companionObjectInstance)
            } catch (_: Exception) {}
        }

        // Register AudioSessionManagerSystem (already has native handle set by onCreate)
        feature.systemsToRegister().forEach { sys ->
            try {
                systemManager.registerSystem(sys)
            } catch (_: Exception) {}
        }
    }

    /**
     * Set the Android audio session ID from the media player.
     * Call this when ExoPlayer is ready with its audio session.
     */
    fun setAudioSessionId(id: Int) {
        if (id == audioSessionId || id <= 0) return
        audioSessionId = id

        // Register the Android session ID → native ID mapping.
        // AudioSessionManagerSystem.execute() looks up this mapping for each
        // entity's AudioSessionId component to identify the native audio source.
        feature.registerAudioSessionId(id, id)

        // Attach to panel entity so the ECS system knows to spatialize
        // this entity's position using this audio session.
        try {
            panelEntity.setComponent(AudioSessionId(id, AudioType.STEREO))
            applyStereoOffsets()
        } catch (_: Exception) {}
    }

    /**
     * Update stereo offsets to match the panel's current scale.
     * Call when the panel is resized (pinch, resize mode, recenter).
     */
    fun updateScale(scale: Float) {
        if (scale == currentScale || !initialized) return
        currentScale = scale
        if (audioSessionId > 0) {
            try { applyStereoOffsets() } catch (_: Exception) {}
        }
    }

    /**
     * Set stereo channel positions at the center of the panel's left and right edges.
     * Left edge center: (-halfWidth, 0, 0) — right edge center: (halfWidth, 0, 0).
     */
    private fun applyStereoOffsets() {
        val halfWidth = panelWidth * currentScale / 2f
        panelEntity.setComponent(AudioSessionStereoOffsets(
            left = Vector3(-halfWidth, 0f, 0f),
            right = Vector3(halfWidth, 0f, 0f),
            mode = StereoOffsetMode.LOCAL_SPACE,
        ))
    }

    /** Tear down the native audio engine. */
    fun destroy() {
        try {
            feature.onDestroy()
        } catch (_: Exception) {}
    }
}
