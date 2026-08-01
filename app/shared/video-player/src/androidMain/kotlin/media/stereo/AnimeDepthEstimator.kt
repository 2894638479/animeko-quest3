/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media.stereo

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Depth estimation using MiDaS v2.1 (EfficientNet-Lite3 CNN) via TFLite with
 * the GPU delegate (Vulkan). A CNN runs on the GPU delegate far better than a
 * transformer (DAv2) did on CPU/NNAPI, which is what the Quest needs for
 * real-time depth.
 *
 * The frame is letterboxed into the model's fixed input size, keeping the
 * aspect ratio; [DepthResult] carries the content-region mapping so the DIBR
 * shader can sample only the video area of the square depth texture.
 *
 * Output depth is relative inverse depth (0 = farthest, 1 = closest) after
 * min-max normalization.
 */
class AnimeDepthEstimator(private val context: Context) {
    private val logger = logger<AnimeDepthEstimator>()

    private val gpuDelegate: GpuDelegate by lazy { GpuDelegate() }

    private val interpreter: Interpreter by lazy {
        // TFLite requires a direct ByteBuffer with native byte order, not a
        // heap buffer (ByteBuffer.wrap would throw at Interpreter creation).
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
        val opts = Interpreter.Options()
        try {
            opts.addDelegate(gpuDelegate)
            logger.info { "TFLite GPU delegate added" }
        } catch (e: Throwable) {
            logger.info(e) { "GPU delegate unavailable, running on CPU" }
        }
        Interpreter(model, opts).also {
            logger.info {
                "TFLite model loaded. input=${it.getInputTensor(0).shape().toList()} " +
                        "output=${it.getOutputTensor(0).shape().toList()}"
            }
        }
    }

    private val inputShape: IntArray by lazy { interpreter.getInputTensor(0).shape() }
    private val inputH: Int get() = inputShape[1]
    private val inputW: Int get() = inputShape[2]
    // TFLite default is NHWC ([1,H,W,3]); NCHW would have 3 in dim 1.
    private val isNchw: Boolean get() = inputShape[3] != 3

    private val outputShape: IntArray by lazy { interpreter.getOutputTensor(0).shape() }
    private val outH: Int get() = outputShape[1]
    private val outW: Int get() = outputShape[2]

    /**
     * Runs inference on the given bitmap. Suspends on Dispatchers.Default.
     */
    suspend fun estimateDepth(rgb: Bitmap): DepthResult = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()
        val prep = preprocess(rgb)

        val inputBuffer = ByteBuffer.allocateDirect(prep.input.size * 4).order(ByteOrder.nativeOrder())
        inputBuffer.asFloatBuffer().put(prep.input)
        inputBuffer.rewind()

        val outSize = outputShape.fold(1) { a, b -> a * b }
        val outputBuffer = ByteBuffer.allocateDirect(outSize * 4).order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        val raw = FloatArray(outSize)
        outputBuffer.asFloatBuffer().get(raw)

        // min-max normalize to 0..1 (inverse depth: closer = larger)
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        for (v in raw) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val range = (max - min).coerceAtLeast(1e-6f)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        val normalized = FloatArray(raw.size) { i -> ((raw[i] - min) / range).coerceIn(0f, 1f) }
        logger.info { "Depth inference ok: ${outW}x$outH in ${elapsedMs}ms" }

        DepthResult(
            depth = normalized,
            width = outW,
            height = outH,
            contentScaleX = prep.contentScaleX,
            contentScaleY = prep.contentScaleY,
            contentOffsetX = prep.contentOffsetX,
            contentOffsetY = prep.contentOffsetY,
        )
    }

    private data class Preprocess(
        val input: FloatArray,
        val contentScaleX: Float,
        val contentScaleY: Float,
        val contentOffsetX: Float,
        val contentOffsetY: Float,
    )

    private fun preprocess(rgb: Bitmap): Preprocess {
        val w = inputW
        val h = inputH
        // Aspect-preserving letterbox into the fixed model input size.
        val scale = minOf(w.toFloat() / rgb.width, h.toFloat() / rgb.height)
        val cw = (rgb.width * scale).toInt().coerceAtLeast(1)
        val ch = (rgb.height * scale).toInt().coerceAtLeast(1)
        val offX = (w - cw) / 2
        val offY = (h - ch) / 2

        val canvas = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(canvas).drawBitmap(
            rgb, null,
            android.graphics.Rect(offX, offY, offX + cw, offY + ch), null,
        )
        val pixels = IntArray(w * h)
        canvas.getPixels(pixels, 0, w, 0, 0, w, h)
        canvas.recycle()

        // MiDaS uses the same ImageNet normalization as Depth Anything.
        val input = if (isNchw) {
            FloatArray(3 * w * h) { idx ->
                val c = idx / (w * h)
                val p = idx % (w * h)
                val ch2 = channel(pixels[p], c)
                (ch2 / 255f - MEAN[c]) / STD[c]
            }
        } else {
            FloatArray(w * h * 3) { idx ->
                val p = idx / 3
                val c = idx % 3
                val ch2 = channel(pixels[p], c)
                (ch2 / 255f - MEAN[c]) / STD[c]
            }
        }
        return Preprocess(
            input,
            cw.toFloat() / w,
            ch.toFloat() / h,
            offX.toFloat() / w,
            offY.toFloat() / h,
        )
    }

    private fun channel(color: Int, c: Int): Int = when (c) {
        0 -> color shr 16 and 0xff
        1 -> color shr 8 and 0xff
        else -> color and 0xff
    }

    fun release() {
        try { interpreter.close() } catch (_: Throwable) {}
        try { gpuDelegate.close() } catch (_: Throwable) {}
    }

    companion object {
        private val logger = logger<AnimeDepthEstimator>()
        private const val MODEL_ASSET = "model_opt.tflite"
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

/**
 * Normalized depth (0..1, closer = larger) plus its spatial size and the
 * letterboxed content region inside the square depth texture.
 */
data class DepthResult(
    val depth: FloatArray,
    val width: Int,
    val height: Int,
    val contentScaleX: Float = 1f,
    val contentScaleY: Float = 1f,
    val contentOffsetX: Float = 0f,
    val contentOffsetY: Float = 0f,
)

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
                _depth.set(estimator.estimateDepth(frame))
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
