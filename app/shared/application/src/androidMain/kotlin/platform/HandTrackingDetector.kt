/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.ControllerPose
import com.meta.spatial.runtime.Scene
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller

/**
 * Detects the current input mode (controllers vs bare hands) and provides
 * per-frame hand/controller state for panel manipulation and cursor interaction.
 *
 * In controller mode, the existing squeeze/thumbstick logic applies.
 * In hand tracking mode, pinch gestures replace squeeze, and index-finger
 * pointing replaces the controller ray for cursor positioning.
 */
object HandTrackingDetector {

    /** Which kind of input the user is currently providing. */
    enum class InputMode {
        /** Physical controllers paired and active. */
        CONTROLLERS,
        /** Bare-hand tracking active (no controllers). */
        HANDS,
        /** Neither controllers nor tracked hands available. */
        NONE,
    }

    /** Per-frame state for the active input modality. */
    data class HandState(
        val mode: InputMode,
        /** Left hand/controller is engaged (squeezing or pinching). */
        val leftActive: Boolean,
        /** Right hand/controller is engaged (squeezing or pinching). */
        val rightActive: Boolean,
        /** World-space pose of the left input (controller or index fingertip). */
        val leftPose: Pose?,
        /** World-space pose of the right input (controller or index fingertip). */
        val rightPose: Pose?,
        /** Bare-hand pinch gesture detected on left hand. */
        val leftPinching: Boolean,
        /** Bare-hand pinch gesture detected on right hand. */
        val rightPinching: Boolean,
    ) {
        /** True when the user is dragging (either squeeze or pinch is held). */
        val isDragging: Boolean get() = leftActive || rightActive
    }

    /**
     * Inspect the local player avatar and determine the current input mode
     * and hand/controller state.
     *
     * @param avatarBody  the local player's [AvatarBody] component.
     * @param scene       the Meta SDK [Scene] for querying poses.
     * @param timeMs      current time in milliseconds.
     */
    @OptIn(SpatialSDKExperimentalAPI::class)
    fun detect(
        avatarBody: AvatarBody,
        scene: Scene,
        timeMs: Long = System.currentTimeMillis(),
    ): HandState {
        val leftHandEntity = avatarBody.leftHand
        val rightHandEntity = avatarBody.rightHand

        // Try controllers first
        val leftController = leftHandEntity.tryGetComponent<Controller>()
        val rightController = rightHandEntity.tryGetComponent<Controller>()

        val hasLeftController = leftController != null
        val hasRightController = rightController != null
        val hasAnyController = hasLeftController || hasRightController

        if (hasAnyController) {
            // --- Controller mode ---
            val leftSqueeze = leftController?.let {
                it.buttonState and ButtonBits.ButtonSqueezeL != 0
            } ?: false
            val rightSqueeze = rightController?.let {
                it.buttonState and ButtonBits.ButtonSqueezeR != 0
            } ?: false

            return HandState(
                mode = InputMode.CONTROLLERS,
                leftActive = leftSqueeze,
                rightActive = rightSqueeze,
                leftPose = if (leftSqueeze)
                    scene.getControllerPoseAtTime(true, timeMs)?.pose else null,
                rightPose = if (rightSqueeze)
                    scene.getControllerPoseAtTime(false, timeMs)?.pose else null,
                leftPinching = false,
                rightPinching = false,
            )
        }

        // --- Hand tracking mode: check for hand joint data ---
        val leftHandPose = getHandPose(leftHandEntity, scene, isLeft = true, timeMs)
        val rightHandPose = getHandPose(rightHandEntity, scene, isLeft = false, timeMs)

        val leftPinching = detectPinch(leftHandEntity)
        val rightPinching = detectPinch(rightHandEntity)

        val hasAnyHand = leftHandPose != null || rightHandPose != null || leftPinching || rightPinching

        return if (hasAnyHand) {
            HandState(
                mode = InputMode.HANDS,
                leftActive = leftPinching,
                rightActive = rightPinching,
                leftPose = leftHandPose,
                rightPose = rightHandPose,
                leftPinching = leftPinching,
                rightPinching = rightPinching,
            )
        } else {
            HandState(
                mode = InputMode.NONE,
                leftActive = false, rightActive = false,
                leftPose = null, rightPose = null,
                leftPinching = false, rightPinching = false,
            )
        }
    }

    /**
     * Attempt to obtain a world-space pose for a bare hand.
     *
     * Prefers the index fingertip pose for cursor positioning; falls back
     * to the palm/wrist via [Scene.getControllerPoseAtTime] which may also
     * report hand poses when hand tracking is active.
     */
    @OptIn(SpatialSDKExperimentalAPI::class)
    private fun getHandPose(
        handEntity: Entity,
        scene: Scene,
        isLeft: Boolean,
        timeMs: Long,
    ): Pose? {
        // The Meta SDK may provide hand poses through getControllerPoseAtTime
        // even in hand-tracking mode (using palm position as origin).
        // Also try HandJoints component for index fingertip position.
        val controllerPose: ControllerPose? =
            scene.getControllerPoseAtTime(isLeft, timeMs)
        if (controllerPose != null &&
            (controllerPose.flags and ControllerPose.LocationValidBit) != 0
        ) {
            return controllerPose.pose
        }

        // Fallback: try HandJoints component for index fingertip via reflection
        try {
            val handJointsClass = Class.forName("com.meta.spatial.toolkit.HandJoints")
            val handJointEnum = Class.forName("com.meta.spatial.core.HandJoint")
            val indexTipField = handJointEnum.getField("INDEX_TIP")
            val indexTipValue = indexTipField.get(null)
            val getJointPoseMethod = handJointsClass.getMethod(
                "getJointPose", handJointEnum,
            )
            // Use generic getComponent with runtime class lookup
            val getComponentMethod = Entity::class.java.getMethod(
                "getComponent", Class::class.java,
            )
            val joints = getComponentMethod.invoke(handEntity, handJointsClass)
            if (joints != null) {
                return getJointPoseMethod.invoke(joints, indexTipValue) as? Pose
            }
        } catch (_: Exception) {
            // HandJoints API not available in this SDK version
        }

        return null
    }

    /**
     * Detect a pinch gesture from bare-hand tracking data.
     *
     * A pinch is recognized when the thumb tip and index finger tip are
     * sufficiently close. This uses reflection to access the HandJoints
     * component since the exact class names may vary by SDK version.
     */
    private fun detectPinch(handEntity: Entity): Boolean {
        val controller = handEntity.tryGetComponent<Controller>()
        // If controller is present, check for pinch button bits
        if (controller != null) {
            // Some SDK versions expose pinch through ButtonBits
            try {
                val pinchLField = ButtonBits::class.java.getField("ButtonPinchL")
                val pinchRField = ButtonBits::class.java.getField("ButtonPinchR")
                val pinchL = pinchLField.getInt(null)
                val pinchR = pinchRField.getInt(null)
                if ((controller.buttonState and pinchL) != 0 ||
                    (controller.buttonState and pinchR) != 0
                ) {
                    return true
                }
            } catch (_: Exception) {
                // Pinch button bits not in this SDK version
            }
        }

        // Fallback: geometric pinch detection via HandJoints
        try {
            val handJointsClass = Class.forName("com.meta.spatial.toolkit.HandJoints")
            val handJointEnum = Class.forName("com.meta.spatial.core.HandJoint")
            val thumbTipField = handJointEnum.getField("THUMB_TIP")
            val indexTipField = handJointEnum.getField("INDEX_TIP")
            val thumbTipValue = thumbTipField.get(null)
            val indexTipValue = indexTipField.get(null)

            val getJointPoseMethod = handJointsClass.getMethod(
                "getJointPose", handJointEnum,
            )
            val getComponentMethod = Entity::class.java.getMethod(
                "getComponent", Class::class.java,
            )
            val joints = getComponentMethod.invoke(handEntity, handJointsClass)
            if (joints != null) {
                val thumbPose = getJointPoseMethod.invoke(joints, thumbTipValue) as? Pose
                val indexPose = getJointPoseMethod.invoke(joints, indexTipValue) as? Pose
                if (thumbPose != null && indexPose != null) {
                    val distance = thumbPose.t.minus(indexPose.t).length()
                    return distance < 0.03f // 3 cm pinch threshold
                }
            }
        } catch (_: Exception) {
            // HandJoints not available
        }

        return false
    }
}
