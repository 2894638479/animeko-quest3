/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.platform

import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.ControllerPose
import kotlin.math.abs

class ControllerDragger(private val host: Host) {
    interface Host {
        var pose: Pose
        var scale: Float
    }

    sealed interface Status
    class TwoHands(var lastDistance: Float) : Status
    class LeftHand(var relativePose: Pose) : Status
    class RightHand(var relativePose: Pose) : Status
    class Idle : Status

    private inline fun <reified T : Status> checkOrSet(new: () -> T, exists: T.() -> Unit) {
        val s = status
        if (s is T) {
            s.exists()
        } else {
            status = new()
        }
    }

    private var status: Status = Idle()

    companion object {
        private const val MIN_DISTANCE = 0.001f
        private const val THUMB_SCALE_SPEED = 0.02f
        private const val THUMB_MOVE_SPEED = 0.02f
    }

    @OptIn(SpatialSDKExperimentalAPI::class)
    fun drag(left: ControllerPose?, right: ControllerPose?, thumbX: Float = 0f, thumbY: Float = 0f) {
        val leftFiltered = left?.takeIf { it.flags and ControllerPose.LocationValidBit != 0 }
        val rightFiltered = right?.takeIf { it.flags and ControllerPose.LocationValidBit != 0 }
        val leftValid = leftFiltered != null
        val rightValid = rightFiltered != null

        when {
            !leftValid && !rightValid -> checkOrSet({ Idle() }) {}
            leftValid && rightValid -> {
                val leftPose = leftFiltered!!.pose
                val rightPose = rightFiltered!!.pose
                val currentDistance = leftPose.t.minus(rightPose.t).length()
                checkOrSet({ TwoHands(currentDistance) }) {
                    // Guard against division by zero / near-zero
                    if (lastDistance > MIN_DISTANCE && currentDistance > MIN_DISTANCE) {
                        host.scale *= (currentDistance / lastDistance)
                    }
                    lastDistance = currentDistance
                }
            }
            leftValid && !rightValid -> {
                val lp = leftFiltered!!.pose
                checkOrSet({ LeftHand(lp.inverse() * host.pose) }) {
                    var newPose = lp * relativePose
                    if (abs(thumbY) > 0.0001f) {
                        newPose = newPose.apply { t += lp.forward().times(thumbY * THUMB_MOVE_SPEED) }
                    }
                    if (abs(thumbX) > 0.0001f) {
                        host.scale *= (1f + thumbX * THUMB_SCALE_SPEED)
                    }
                    host.pose = newPose
                    if (abs(thumbY) > 0.0001f || abs(thumbX) > 0.0001f) {
                        relativePose = lp.inverse() * host.pose
                    }
                }
            }
            !leftValid && rightValid -> {
                val rp = rightFiltered!!.pose
                checkOrSet({ RightHand(rp.inverse() * host.pose) }) {
                    var newPose = rp * relativePose
                    if (abs(thumbY) > 0.0001f) {
                        newPose = newPose.apply { t += rp.forward().times(thumbY * THUMB_MOVE_SPEED) }
                    }
                    if (abs(thumbX) > 0.0001f) {
                        host.scale *= (1f + thumbX * THUMB_SCALE_SPEED)
                    }
                    host.pose = newPose
                    if (abs(thumbY) > 0.0001f || abs(thumbX) > 0.0001f) {
                        relativePose = rp.inverse() * host.pose
                    }
                }
            }
        }
    }
}
