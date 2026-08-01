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
        // Prefer NNAPI (Quest DSP/GPU) for fast inference; fall back to CPU.
        val nnapiOptions = OrtSession.SessionOptions().apply {
            try {
                addNnapi()
                logger.info { "NNAPI execution provider enabled" }
            } catch (e: Throwable) {
                logger.info(e) { "NNAPI not available" }
            }
        }
        try {
            environment.createSession(model, nnapiOptions).also {
                logger.info { "Loaded depth model with NNAPI/CPU. inputs=${it.inputNames}, outputs=${it.outputNames}" }
            }
        } catch (e: Throwable) {
            logger.info(e) { "NNAPI session failed, falling back to CPU" }
            environment.createSession(model, OrtSession.SessionOptions()).also {
                logger.info { "Loaded depth model (CPU). inputs=${it.inputNames}, outputs=${it.outputNames}" }
            }
        }
    }

    private val inputName: String by lazy { session.inputNames.first() }
    private val outputName: String by lazy { session.outputNames.first() }

    /**
     * Runs inference on the given bitmap. Suspends on Dispatchers.Default.
     * @return normalized depth (0..1) with its (width, height) size.
     */
    suspend fun estimateDepth(rgb: Bitmap): DepthResult = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()
        val (input, h, w) = preprocess(rgb)
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, h.toLong(), w.toLong()),
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val (depth, shape) = extractDepth(result[outputName].get().value)
                // Output shape may be (1,1,H,W) or (1,H,W): last two dims are H,W.
                val outH = if (shape.size >= 2) shape[shape.size - 2] else 0
                val outW = if (shape.size >= 1) shape[shape.size - 1] else 0
                // min-max normalize to 0..1 (inverse depth: closer = larger)
                var min = Float.MAX_VALUE
                var max = Float.MIN_VALUE
                for (v in depth) {
                    if (v < min) min = v
                    if (v > max) max = v
                }
                val range = (max - min).coerceAtLeast(1e-6f)
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                val normalized = FloatArray(depth.size) { i ->
                    ((depth[i] - min) / range).coerceIn(0f, 1f)
                }
                logger.info { "Depth inference ok: ${depth.size}px (${outW}x$outH) in ${elapsedMs}ms" }
                DepthResult(normalized, outW, outH)
            }
        }
    }

    /**
     * Extracts a flat depth array from an ONNX output of any N-D float shape
     * (e.g. (1,1,H,W), (1,H,W), or (H,W)). Recursively descends through
     * leading batch dims, flattens rows into a single FloatArray, and returns
     * the full shape so the caller can restore H and W.
     */
    private fun extractDepth(value: Any): Pair<FloatArray, IntArray> {
        val dims = mutableListOf<Int>()

        fun flatten(a: Any): FloatArray? = when (a) {
            is FloatArray -> a
            is Array<*> -> {
                if (a.isEmpty()) return null
                val first = a[0]
                if (first is FloatArray) {
                    dims.add(a.size)
                    @Suppress("UNCHECKED_CAST")
                    val rows = a as Array<FloatArray>
                    val w = rows[0].size
                    val out = FloatArray(rows.size * w)
                    var i = 0
                    for (row in rows) {
                        row.copyInto(out, i)
                        i += w
                    }
                    out
                } else if (first is Array<*>) {
                    dims.add(a.size)
                    @Suppress("UNCHECKED_CAST")
                    flatten(first)
                } else {
                    null
                }
            }
            else -> null
        }

        val flat = flatten(value) ?: return FloatArray(0) to intArrayOf()
        return flat to dims.toIntArray()
    }

    private fun preprocess(rgb: Bitmap): Triple<FloatArray, Int, Int> {
        // Keep the aspect ratio: the frame is 16:9 and stretching it to a
        // square made the depth map's shapes (e.g. character silhouettes) not
        // match the picture once mapped back to the 16:9 video.
        val scale = minOf(MAX_DIM.toFloat() / rgb.width, MAX_DIM.toFloat() / rgb.height)
        val w = (rgb.width * scale).toInt().coerceAtLeast(1)
        val h = (rgb.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(rgb, w, h, true)
        try {
            val pixels = IntArray(w * h)
            resized.getPixels(pixels, 0, w, 0, 0, w, h)
            // DINOv2 normalization (same as Depth Anything V2)
            val input = FloatArray(3 * w * h) { idx ->
                val c = idx / (w * h)
                val p = idx % (w * h)
                val color = pixels[p]
                val channel = when (c) {
                    0 -> color shr 16 and 0xff
                    1 -> color shr 8 and 0xff
                    else -> color and 0xff
                }
                (channel / 255f - MEAN[c]) / STD[c]
            }
            return Triple(input, h, w)
        } finally {
            if (resized !== rgb) resized.recycle()
        }
    }

    companion object {
        private val logger = logger<AnimeDepthEstimator>()
        // Max side in pixels, aspect-preserving. Keeps CPU/NNAPI fast while
        // keeping enough resolution for character-level depth shapes.
        private const val MAX_DIM = 160
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/** Normalized depth (0..1, closer = larger) plus its spatial size. */
data class DepthResult(val depth: FloatArray, val width: Int, val height: Int)

/** A small async helper: keeps the latest depth, updated at most every [intervalMillis]. */
class DepthRefresher(
    private val scope: CoroutineScope,
    private val estimator: AnimeDepthEstimator,
    private val intervalMillis: Long = 1_500L,
) {
    private val _depth = AtomicReference<DepthResult?>(null)
    val depth: DepthResult? get() = _depth.get()

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
