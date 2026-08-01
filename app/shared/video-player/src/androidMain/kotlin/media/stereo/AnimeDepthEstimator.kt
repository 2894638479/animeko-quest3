/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media.stereo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Anime depth estimation using DepthAnything V2 Small (ONNX, fp16).
 *
 * Input: RGB bitmap (any aspect, stretched to [INPUT_SIZE]x[INPUT_SIZE]).
 * Output: FloatArray of size W*H where each value is relative inverse depth
 * (0 = farthest, 1 = closest), computed via min-max normalization.
 *
 * CPU inference is slow (~hundreds of ms per frame), so callers should run it
 * sparingly (e.g. once per second) and reuse the result between updates.
 */
class AnimeDepthEstimator(private val context: Context) {
    private val environment: OrtEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OrtEnvironment.getEnvironment()
    }
    private val session: OrtSession by lazy {
        val model = context.assets.open("model_fp16.onnx").use { it.readBytes() }
        environment.createSession(model, OrtSession.SessionOptions()).also {
            logger.info { "Loaded depth model. inputs=${it.inputNames}, outputs=${it.outputNames}" }
        }
    }

    private val inputName: String by lazy { session.inputNames.first() }
    private val outputName: String by lazy { session.outputNames.first() }

    /**
     * Runs inference on the given bitmap. Suspends on Dispatchers.Default.
     * @return normalized depth (0..1), size INPUT_SIZE*INPUT_SIZE
     */
    suspend fun estimateDepth(rgb: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()
        val input = preprocess(rgb)
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val depth = extractDepth(result[outputName].get().value)
                // min-max normalize to 0..1 (inverse depth: closer = larger)
                var min = Float.MAX_VALUE
                var max = Float.MIN_VALUE
                for (v in depth) {
                    if (v < min) min = v
                    if (v > max) max = v
                }
                val range = (max - min).coerceAtLeast(1e-6f)
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                logger.info { "Depth inference ok: ${depth.size}px in ${elapsedMs}ms" }
                FloatArray(depth.size) { i ->
                    ((depth[i] - min) / range).coerceIn(0f, 1f)
                }
            }
        }
    }

    /** Extracts a flat depth array from an ONNX output of shape (1,1,H,W) or (1,H,W). */
    private fun extractDepth(value: Any): FloatArray {
        @Suppress("UNCHECKED_CAST")
        return when (value) {
            // 4D: [1][1][H][W] (primitive float[][][][])
            is Array<*> -> (value[0] as Array<*>)[0]?.let { inner ->
                when (inner) {
                    is FloatArray -> inner // [1][1][H*W] flattened
                    is Array<*> -> {
                        // [1][1][H][W]
                        val rows = inner.filterIsInstance<FloatArray>()
                        if (rows.isEmpty()) {
                            @Suppress("UNCHECKED_CAST")
                            (inner as Array<FloatArray>).firstOrNull()?.copyOf() ?: FloatArray(0)
                        } else {
                            val w = rows[0].size
                            val flat = FloatArray(rows.size * w)
                            var i = 0
                            for (row in rows) {
                                row.copyInto(flat, i)
                                i += w
                            }
                            flat
                        }
                    }
                    else -> FloatArray(0)
                }
            } ?: FloatArray(0)

            // 3D: [1][H][W]
            is FloatArray -> value
            else -> FloatArray(0)
        }
    }

    private fun preprocess(rgb: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(rgb, INPUT_SIZE, INPUT_SIZE, true)
        try {
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            // DINOv2 normalization (same as Depth Anything V2)
            return FloatArray(3 * INPUT_SIZE * INPUT_SIZE) { idx ->
                val c = idx / (INPUT_SIZE * INPUT_SIZE)
                val p = idx % (INPUT_SIZE * INPUT_SIZE)
                val color = pixels[p]
                val channel = when (c) {
                    0 -> color shr 16 and 0xff
                    1 -> color shr 8 and 0xff
                    else -> color and 0xff
                }
                (channel / 255f - MEAN[c]) / STD[c]
            }
        } finally {
            if (resized !== rgb) resized.recycle()
        }
    }

    companion object {
        private val logger = logger<AnimeDepthEstimator>()
        // 224 keeps CPU inference fast enough (~100-200ms) to track the video.
        // 518 was too slow: depth lagged behind the changing picture and the
        // stereo effect faded to flat once the scene moved on.
        const val INPUT_SIZE = 224
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/** A small async helper: keeps the latest depth, updated at most every [intervalMillis]. */
class DepthRefresher(
    private val scope: CoroutineScope,
    private val estimator: AnimeDepthEstimator,
    private val intervalMillis: Long = 1_500L,
) {
    private val _depth = AtomicReference<FloatArray?>(null)
    val depth: FloatArray? get() = _depth.get()

    private var lastUpdate = 0L
    private var running = false

    /** Feed the latest frame (already scaled to a small size); schedules inference if due. */
    fun requestUpdate(frame: Bitmap) {
        if (running) return
        val now = System.currentTimeMillis()
        if (now - lastUpdate < intervalMillis) return
        lastUpdate = now
        running = true
        scope.launch {
            try {
                val d = estimator.estimateDepth(frame)
                _depth.set(d)
            } catch (e: Throwable) {
                logger.info(e) { "Depth inference failed" }
            } finally {
                running = false
            }
        }
    }

    fun release() {
        _depth.set(null)
    }

    private companion object {
        private val logger = logger<DepthRefresher>()
    }
}
