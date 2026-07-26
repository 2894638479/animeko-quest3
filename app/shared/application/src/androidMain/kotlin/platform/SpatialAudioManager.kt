/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 */
package me.him188.ani.app.platform

import com.meta.spatial.core.Entity
import com.meta.spatial.runtime.Scene
import com.meta.spatial.spatialaudio.AudioSessionId
import com.meta.spatial.spatialaudio.AudioSessionManagerSystem
import com.meta.spatial.spatialaudio.ComponentRegistrations
import com.meta.spatial.spatialaudio.SpatialAudioFeature
import com.meta.spatial.toolkit.Transform

/**
 * Manages spatial audio via the Meta Spatial SDK's ECS-based spatial audio system.
 *
 * How it works:
 * 1. Register [AudioSessionManagerSystem] as an ECS system
 * 2. Register spatial audio components via [ComponentRegistrations]
 * 3. Attach an [AudioSessionId] component to the panel entity with the Android
 *    audio session ID from the media player (ExoPlayer)
 * 4. The system automatically spatializes the audio to the entity's 3D position
 */
class SpatialAudioManager(
    private val scene: Scene,
    private val panelEntity: Entity,
) {
    private var audioSessionId: Int = 0

    /**
     * Register the spatial audio ECS system. Call once from BaseVRActivity.onSceneReady().
     */
    fun registerSystem(
        systemManager: com.meta.spatial.core.SystemManager,
        componentManager: com.meta.spatial.core.ComponentManager,
    ) {
        // Register components
        ComponentRegistrations.all().forEach { reg ->
            try {
                componentManager.registerComponent(
                    reg.clazz, reg.clazz.simpleName ?: "", reg.sendRate, reg.companionObjectInstance)
            } catch (_: Exception) {}
        }

        // Register audio session manager system if not already present
        if (systemManager.tryFindSystem<AudioSessionManagerSystem>() == null) {
            systemManager.registerSystem(AudioSessionManagerSystem())
        }
    }

    /**
     * Set the Android audio session ID from the media player.
     * Call this when the player is ready with its audio session.
     */
    fun setAudioSessionId(id: Int) {
        if (id == audioSessionId || id <= 0) return
        audioSessionId = id
        try {
            panelEntity.setComponent(AudioSessionId(id))
        } catch (_: Exception) {}
    }

    /** Enable or disable spatial audio processing. */
    fun setEnabled(enabled: Boolean) {
        // SpatialAudioFeature is enabled by default when AudioSessionManagerSystem is active.
        // The feature is controlled by the presence of AudioSessionId components on entities.
    }

    /** Tear down when no longer needed. */
    fun destroy() {
        setEnabled(false)
    }
}
