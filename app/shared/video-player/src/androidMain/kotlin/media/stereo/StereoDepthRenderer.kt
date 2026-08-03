/*
 * Copyright (C) 2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.media.stereo

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * GL renderer that draws the video frame as a side-by-side stereo pair:
 *
 *   [ left eye: original ] [ right eye: DIBR warped by depth ]
 *
 * The video is fed by ExoPlayer through a [android.graphics.SurfaceTexture]
 * (created on the GL thread, exposed via [onSurfaceTextureReady]).
 * Depth is estimated asynchronously by [AnimeDepthEstimator] and uploaded
 * as a texture; the DIBR shader shifts each pixel horizontally by an amount
 * proportional to (depth - 0.5), so closer objects pop out toward the viewer.
 */
class StereoDepthRenderer(
    private val scope: CoroutineScope,
    private val estimator: AnimeDepthEstimator,
    var strength: Float = 1f,
    var debugShowDepth: Boolean = false,
    /**
     * When true (default), consecutive raw depth maps are blended with an
     * adaptive EMA and normalized against a running min/max. Set to false to
     * feed the raw per-frame inference straight through — for A/B testing the
     * temporal filter's contribution.
     */
    var temporalFilterEnabled: Boolean = true,
    /**
     * When true, the raw depth is NOT normalized against a running min/max;
     * instead it is multiplied by [FIXED_DEPTH_SCALE] (an absolute mapping).
     */
    var fixedScaleEnabled: Boolean = false,
    private val onSurfaceTextureReady: (android.graphics.SurfaceTexture) -> Unit = {},
) : GLSurfaceView.Renderer {

    private val logger = logger<StereoDepthRenderer>()

    // Video input
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    private var videoTexId = 0
    private val texMatrix = FloatArray(16)

    // Depth
    private var depthTexId = 0
    private val pendingDepth = AtomicReference<DepthWithSeq?>(null)

    // Temporal + normalization stability state (raw domain, see processDepth)
    private var prevRawDepth: FloatArray? = null
    private var prevRawW = 0
    private var prevRawH = 0
    private var rawRangeInitialized = false
    private var rawMinEst = 0f
    private var rawMaxEst = 1f
    private var rawRangeDiagCount = 0 // throttled "Raw depth range" log for calibrating FIXED_DEPTH_SCALE

    // Pipeline: the GL thread copies every frame into the video ring and drops
    // the latest sample (tagged with its seq) into this conflated channel (old
    // frames overwritten); the inference thread runs at full throughput
    // (receive -> infer -> next), so the depth update rate equals the inference
    // rate (~27fps at ~36ms), not a throttled interval.
    private val frameChannel = Channel<FrameSample>(Channel.CONFLATED)

    // Video ring buffer: each live frame is copied into a ring slot with a
    // monotonically increasing seq. The DISPLAY shows the slot whose seq
    // matches the latest uploaded depth (see latestDepthSeq) — so the stereo
    // warp always uses the depth of the exact frame being shown, instead of a
    // fixed-latency guess. The display advances whenever a new depth arrives
    // (~inference rate), which covers the 24fps anime source.
    private val videoRing = Array(RING_SIZE) { RingSlot() }
    private var ringHead = 0 // next slot to overwrite
    private var ringSeqCounter = 0L
    private var latestDepthSeq = -1L // seq of the frame the current depth corresponds to

    /** A video sample handed to the inference thread, tagged with its ring seq. */
    private data class FrameSample(val bitmap: Bitmap, val seq: Long)

    /** An inference result tagged with the seq of the frame it was computed from. */
    private data class DepthWithSeq(val seq: Long, val result: DepthResult)

    /** One slot of the video ring: a full-res RGBA texture + FBO holding a frame. */
    private class RingSlot {
        var tex = 0
        var fbo = 0
        var w = 0
        var h = 0
        var seq = -1L
    }

    init {
        scope.launch(Dispatchers.Default) {
            while (currentCoroutineContext().isActive) {
                val frame = try {
                    frameChannel.receive()
                } catch (e: CancellationException) {
                    break
                } catch (e: Throwable) {
                    continue
                }
                try {
                    val result = estimator.estimateDepth(frame.bitmap)
                    pendingDepth.set(DepthWithSeq(frame.seq, processDepth(result)))
                } catch (e: Throwable) {
                    logger.info(e) { "Depth inference failed" }
                } finally {
                    frame.bitmap.recycle()
                }
            }
        }
    }

    /**
     * Stabilizes the per-frame inference output in the RAW (unnormalized)
     * domain, then normalizes against a running min/max.
     *
     * The model's raw output for a static object is roughly constant frame to
     * frame; what flickers is the per-frame min-max normalization — if any
     * other part of the frame changes, the global max jumps and the same
     * object swings from near to far (the "red -> blue" flicker). So:
     *
     *  1. an adaptive EMA blends the raw values (small deltas smooth inference
     *     noise, large deltas = real motion / cuts pass through cleanly);
     *  2. raw min/max are tracked with a slow-running EMA, giving a stable
     *     mapping so an object's depth no longer depends on this frame's
     *     global extremes;
     *  3. the blended raw is normalized with that stable range.
     *
     * A resolution change or the first frame resets the running state.
     */
    private fun processDepth(incoming: DepthResult): DepthResult {
        val raw = incoming.raw
        val w = incoming.width
        val h = incoming.height

        // Per-frame raw extremes, only used to nudge the running estimates.
        var frameMin = Float.MAX_VALUE
        var frameMax = Float.MIN_VALUE
        for (v in raw) {
            if (v < frameMin) frameMin = v
            if (v > frameMax) frameMax = v
        }
        val prev = prevRawDepth
        val sameSize = prev != null && prev.size == raw.size && prevRawW == w && prevRawH == h
        if (!rawRangeInitialized || !sameSize) {
            rawMinEst = frameMin
            rawMaxEst = frameMax
            rawRangeInitialized = true
        } else {
            rawMinEst += RANGE_ALPHA * (frameMin - rawMinEst)
            rawMaxEst += RANGE_ALPHA * (frameMax - rawMaxEst)
        }
        val range = (rawMaxEst - rawMinEst).coerceAtLeast(1e-6f)

        // Diagnostic (throttled): report the running raw range so FIXED_DEPTH_SCALE
        // can be calibrated — a fixed scale should map a typical rawMax to ~1.0.
        rawRangeDiagCount++
        if (rawRangeDiagCount % 60 == 0) {
            logger.info { "Raw depth range: min=${rawMinEst} max=${rawMaxEst}" }
        }

        val blended: FloatArray
        if (!temporalFilterEnabled || prev == null || prev.size != raw.size) {
            // Filter disabled (A/B test) or no previous frame: feed through.
            blended = raw.copyOf()
        } else {
            blended = FloatArray(raw.size)
            for (i in raw.indices) {
                // delta measured relative to the stable range, so thresholds
                // are scale-free across scenes.
                val diff = abs(raw[i] - prev[i]) / range
                val alpha = when {
                    diff <= TEMPORAL_DIFF_LO -> TEMPORAL_ALPHA_MIN
                    diff >= TEMPORAL_DIFF_HI -> TEMPORAL_ALPHA_MAX
                    else -> TEMPORAL_ALPHA_MIN + (TEMPORAL_ALPHA_MAX - TEMPORAL_ALPHA_MIN) *
                            (diff - TEMPORAL_DIFF_LO) / (TEMPORAL_DIFF_HI - TEMPORAL_DIFF_LO)
                }
                blended[i] = prev[i] + alpha * (raw[i] - prev[i])
            }
        }
        prevRawDepth = blended.copyOf()
        prevRawW = w
        prevRawH = h

        val norm = if (fixedScaleEnabled) {
            // Absolute mapping: no scene-relative min/max, just a fixed gain.
            // Values above 1 clamp (near), below 0 clamp (far).
            FloatArray(raw.size) { i -> (blended[i] * FIXED_DEPTH_SCALE).coerceIn(0f, 1f) }
        } else {
            FloatArray(raw.size) { i -> ((blended[i] - rawMinEst) / range).coerceIn(0f, 1f) }
        }
        return incoming.copy(depth = norm)
    }

    // FBO for frame sampling
    private var sampleFbo = 0
    private var sampleTex = 0
    private var sampleWidth = 0
    private var sampleHeight = 0

    private var viewportWidth = 1
    private var viewportHeight = 1

    // Shader programs
    private var leftProgram = 0
    private var depthProgram = 0
    private var warpProgram = 0
    private var copyProgram = 0 // ring texture -> model-input FBO downscale
    private var quadBuffer: FloatBuffer? = null

    // Left eye program (used to copy the OES frame into the ring + model FBO)
    private var uVideoLeft = -1
    private var uTexMatrixLeft = -1

    // Copy program (samples a regular texture into an FBO)
    private var uTexCopy = -1

    // Forward-mapping mesh (vertices displaced by depth in the vertex shader)
    private var meshVertices: FloatBuffer? = null
    private var meshIndices: ShortBuffer? = null
    private var meshIndexCount = 0

    // Uniform locations (warp program — forward mapping)
    private var warpVideo = -1
    private var warpDepth = -1
    private var warpDisp = -1
    private var warpScaleY = -1
    private var warpOffsetY = -1
    private var warpTexH = -1

    // Depth visualization program
    private var uDepthDepth = -1
    private var uDepthScaleYDepth = -1
    private var uDepthOffsetYDepth = -1
    private var uDepthTexHDepth = -1

    // Letterboxed content region inside the square depth texture.
    private var depthScaleY = 1f
    private var depthOffsetY = 0f
    // Depth texture height (texels), used to clamp samples off the letterbox.
    private var depthTexH = 256f

    // Diagnostic: throttled boundary-vs-interior depth log (see uploadPendingDepthIfAny)
    private var depthDiagCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Video texture (OES)
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        videoTexId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Depth texture (R8)
        val depthIds = IntArray(1)
        GLES20.glGenTextures(1, depthIds, 0)
        depthTexId = depthIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        leftProgram = createProgram(VERTEX_SHADER, FRAG_LEFT)
        uVideoLeft = GLES20.glGetUniformLocation(leftProgram, "uVideo")
        uTexMatrixLeft = GLES20.glGetUniformLocation(leftProgram, "uTexMatrix")

        depthProgram = createProgram(VERTEX_SHADER, FRAG_DEPTH)
        uDepthDepth = GLES20.glGetUniformLocation(depthProgram, "uDepth")
        uDepthScaleYDepth = GLES20.glGetUniformLocation(depthProgram, "uDepthScaleY")
        uDepthOffsetYDepth = GLES20.glGetUniformLocation(depthProgram, "uDepthOffsetY")
        uDepthTexHDepth = GLES20.glGetUniformLocation(depthProgram, "uDepthTexH")

        // Forward-mapping warp: vertices displaced by depth (GPU interpolates
        // the deformation, naturally filling holes instead of reverse-mapping
        // which pulled background into the silhouette). Samples the video ring
        // (a display-ready GL_TEXTURE_2D), not the OES surface.
        warpProgram = createProgram(VERTEX_WARP, FRAG_WARP)
        warpVideo = GLES20.glGetUniformLocation(warpProgram, "uVideo")
        warpDepth = GLES20.glGetUniformLocation(warpProgram, "uDepth")
        warpDisp = GLES20.glGetUniformLocation(warpProgram, "uDisp")
        warpScaleY = GLES20.glGetUniformLocation(warpProgram, "uDepthScaleY")
        warpOffsetY = GLES20.glGetUniformLocation(warpProgram, "uDepthOffsetY")
        warpTexH = GLES20.glGetUniformLocation(warpProgram, "uDepthTexH")

        // Copy program: downsamples a ring frame into the small model-input FBO.
        copyProgram = createProgram(VERTEX_SHADER, FRAG_COPY)
        uTexCopy = GLES20.glGetUniformLocation(copyProgram, "uTex")

        val mesh = createMesh(MESH_COLS, MESH_ROWS)
        meshVertices = mesh.first
        meshIndices = mesh.second
        meshIndexCount = MESH_COLS * MESH_ROWS * 6

        val quad = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        quadBuffer = ByteBuffer.allocateDirect(quad.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(quad); position(0) }

        uploadDepthPlaceholder()

        // Expose the SurfaceTexture to ExoPlayer (must be on GL thread).
        // Track whether any video frame has actually arrived so we can avoid
        // running expensive depth inference (and stalling media loading) while
        // the source is still resolving.
        val st = android.graphics.SurfaceTexture(videoTexId)
        // Give MediaCodec a sane default buffer size in case it doesn't
        // configure the surface itself.
        st.setDefaultBufferSize(1920, 1080)
        st.setOnFrameAvailableListener {
            if (!hasVideoFrame) {
                logger.info { "First video frame arrived on SurfaceTexture" }
            }
            hasVideoFrame = true
            newFramePending = true
        }
        surfaceTexture = st
        onSurfaceTextureReady(st)

        logger.info { "StereoDepthRenderer surface created" }
    }

    private var hasVideoFrame = false

    /** Set when a NEW video frame is queued; gates the ring rotation to the VIDEO frame rate. */
    @Volatile
    private var newFramePending = false

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        // Clear every frame: regions the displaced mesh no longer covers
        // (disocclusion gaps at the leading edge) render as clean black
        // instead of the previous frame's stale content.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        // SurfaceTexture's transform matrix is undefined (all zeros) before the
        // first frame arrives; a zero matrix would sample the texture corner and
        // render black. Fall back to identity until real frames come in.
        if (texMatrix.all { it == 0f }) {
            Matrix.setIdentityM(texMatrix, 0)
        }

        maybeSampleFrame() // copies the live frame into the ring + samples it for inference

        // Upload pending depth (from inference thread) BEFORE drawing so the
        // warp samples the ring frame whose depth just became ready.
        uploadPendingDepthIfAny()

        val half = viewportWidth / 2

        // Left eye: original frame
        GLES20.glViewport(0, 0, half, viewportHeight)
        drawLeft()

        // Right eye: DIBR warped
        GLES20.glViewport(half, 0, viewportWidth - half, viewportHeight)
        drawRight()

        // Debug: log frame count periodically
        frameCount++
        if (frameCount % 120 == 0) {
            logger.info {
                "Stereo renderer frames=$frameCount viewport=${viewportWidth}x$viewportHeight " +
                        "hasVideoFrame=$hasVideoFrame"
            }
        }
    }

    private var frameCount = 0

    /** Forward mapping: vertices shifted by depth so the figure moves as a whole. */
    private fun drawLeft() {
        drawWarp(DISP_CLIP / 2f * strength) // left eye: foreground shifts right (crossed parallax = pop-out)
    }

    private fun drawRight() {
        if (debugShowDepth) {
            // Debug: render the depth map as a blue( far) -> red(near) colormap
            // so we can visually check it matches the picture.
            GLES20.glUseProgram(depthProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
            GLES20.glUniform1i(uDepthDepth, 1)
            GLES20.glUniform1f(uDepthScaleYDepth, depthScaleY)
            GLES20.glUniform1f(uDepthOffsetYDepth, depthOffsetY)
            GLES20.glUniform1f(uDepthTexHDepth, depthTexH)
            drawQuadRaw(depthProgram)
            return
        }
        drawWarp(-DISP_CLIP / 2f * strength) // right eye: foreground shifts left
    }

    /**
     * The ring slot to display: the frame whose depth is currently uploaded
     * (so the warp always uses the depth of the exact frame on screen). Falls
     * back to the newest slot before any depth is ready.
     */
    private fun currentDisplayTexture(): Int {
        for (slot in videoRing) {
            if (slot.tex != 0 && slot.seq == latestDepthSeq) return slot.tex
        }
        val newest = videoRing[(ringHead - 1 + RING_SIZE) % RING_SIZE]
        return newest.tex
    }

    private fun drawWarp(uDisp: Float) {
        GLES20.glUseProgram(warpProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        // The ring texture is already display-ready (the OES was copied into it
        // with the transform matrix applied), so no texMatrix is needed here.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, currentDisplayTexture())
        GLES20.glUniform1i(warpVideo, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
        GLES20.glUniform1i(warpDepth, 1)
        GLES20.glUniform1f(warpDisp, uDisp)
        GLES20.glUniform1f(warpScaleY, depthScaleY)
        GLES20.glUniform1f(warpOffsetY, depthOffsetY)
        GLES20.glUniform1f(warpTexH, depthTexH)
        drawMeshRaw(warpProgram)
    }

    private fun drawMeshRaw(program: Int) {
        val vbuf = meshVertices ?: return
        val ibuf = meshIndices ?: return
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        vbuf.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vbuf)
        vbuf.position(2)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, vbuf)
        ibuf.position(0)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, meshIndexCount, GLES20.GL_UNSIGNED_SHORT, ibuf)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    /** Builds the forward-mapping mesh: clip-space vertices + matching UVs. */
    private fun createMesh(cols: Int, rows: Int): Pair<FloatBuffer, ShortBuffer> {
        val vertices = FloatArray((cols + 1) * (rows + 1) * 4)
        var i = 0
        for (row in 0..rows) {
            for (col in 0..cols) {
                vertices[i++] = col.toFloat() / cols * 2f - 1f // clip x
                vertices[i++] = row.toFloat() / rows * 2f - 1f // clip y (-1 bottom)
                vertices[i++] = col.toFloat() / cols            // uv u
                vertices[i++] = row.toFloat() / rows            // uv v (0 bottom)
            }
        }
        val indices = ShortArray(cols * rows * 6)
        var ii = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val a = (row * (cols + 1) + col).toShort()
                val b = (a + 1).toShort()
                val c = ((row + 1) * (cols + 1) + col).toShort()
                val d = (c + 1).toShort()
                indices[ii++] = a; indices[ii++] = c; indices[ii++] = b
                indices[ii++] = b; indices[ii++] = c; indices[ii++] = d
            }
        }
        val vbuf = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vbuf.put(vertices).position(0)
        val ibuf = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
        ibuf.put(indices).position(0)
        return vbuf to ibuf
    }

    private fun drawQuadRaw(program: Int) {
        val buffer = quadBuffer ?: return
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, buffer)
        buffer.position(2)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    /**
     * Copies the live frame into the next video-ring slot, then downsamples
     * that slot for the inference thread. The sample is tagged with the ring
     * slot's seq so the finished depth can be matched back to the exact frame
     * it came from (see [uploadPendingDepthIfAny] and [currentDisplayTexture]).
     */
    private fun maybeSampleFrame() {
        // No video frames yet (media source still loading) — skip sampling to
        // keep CPU free for the player. Depth texture stays at the placeholder.
        if (!hasVideoFrame) return
        // Rotate the ring ONLY when a new video frame has actually arrived: the
        // render loop runs faster than the video, and copying the same frame
        // into many slots would burn ring capacity and let the depth's frame be
        // evicted (causing flash-back). At video frame rate, RING_SIZE covers
        // many frames of inference latency.
        if (!newFramePending) return
        newFramePending = false

        // 1. Copy the live frame into the next ring slot (full res, display-ready).
        val slot = videoRing[ringHead]
        renderVideoToRing(slot)
        slot.seq = ringSeqCounter++
        ringHead = (ringHead + 1) % RING_SIZE

        // 2. Downscale the ring frame to the model input and read it back.
        ensureSampleFbo(SAMPLE_WIDTH, SAMPLE_HEIGHT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sampleFbo)
        GLES20.glViewport(0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
        GLES20.glUseProgram(copyProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.tex)
        GLES20.glUniform1i(uTexCopy, 0)
        drawQuadRaw(copyProgram)

        val pixels = ByteBuffer.allocateDirect(SAMPLE_WIDTH * SAMPLE_HEIGHT * 4)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val bitmap = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(pixels)
        // GL rows are bottom-up; flip vertically for the network
        val flipped = Bitmap.createBitmap(SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(flipped)
        canvas.scale(1f, -1f)
        canvas.drawBitmap(bitmap, 0f, -SAMPLE_HEIGHT.toFloat(), null)
        bitmap.recycle()

        // CONFLATED: overwrites any unprocessed frame. The seq tags which ring
        // slot this sample's depth corresponds to.
        frameChannel.trySend(FrameSample(flipped, slot.seq))
    }

    /** Renders the live OES frame (with the transform matrix) into the ring slot. */
    private fun renderVideoToRing(slot: RingSlot) {
        ensureRingSlot(slot)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.fbo)
        GLES20.glViewport(0, 0, RING_W, RING_H)
        GLES20.glUseProgram(leftProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glUniform1i(uVideoLeft, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLeft, 1, false, texMatrix, 0)
        drawQuadRaw(leftProgram)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun ensureRingSlot(slot: RingSlot) {
        if (slot.tex != 0 && slot.w == RING_W && slot.h == RING_H) return
        if (slot.tex != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(slot.fbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(slot.tex), 0)
        }
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        slot.tex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, slot.tex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, RING_W, RING_H, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        slot.fbo = fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.fbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, slot.tex, 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        slot.w = RING_W
        slot.h = RING_H
    }

    private fun ensureSampleFbo(w: Int, h: Int) {
        if (sampleFbo != 0 && sampleWidth == w && sampleHeight == h) return
        if (sampleFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(sampleFbo), 0)
            GLES20.glDeleteTextures(1, intArrayOf(sampleTex), 0)
        }
        sampleWidth = w
        sampleHeight = h
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        sampleTex = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sampleTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        sampleFbo = fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sampleFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, sampleTex, 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun uploadDepthPlaceholder() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8, 2, 2, 0,
            GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(byteArrayOf(64, 64, 64, 64)),
        )
    }

    /** Uploads the latest inference result as a depth texture (called on GL thread). */
    private fun uploadPendingDepthIfAny() {
        val entry = pendingDepth.getAndSet(null) ?: return
        // The depth belongs to a specific ring frame; the warp must display
        // exactly that frame so the depth matches the picture.
        latestDepthSeq = entry.seq
        val result = entry.result
        val depth = result.depth
        val w = result.width
        val h = result.height
        if (w <= 0 || h <= 0 || w * h != depth.size) return
        // Letterboxed content region (video area inside the square depth map).
        depthScaleY = result.contentScaleY.coerceIn(0.01f, 1f)
        depthOffsetY = result.contentOffsetY.coerceIn(0f, 0.99f)
        depthTexH = h.toFloat()
        // Flip rows vertically: GL texture (0,0) is the bottom-left, but the
        // depth array starts with the top row of the (screen-space) frame.
        // Without this the depth map is upside down and the stereo shape
        // doesn't match the picture.
        // Horizontal depth smoothing: spreads hard silhouette depth steps over
        // a few columns so adjacent mesh vertices never cross/fold (the max
        // displacement ~MAX_DISP is ~2x a mesh cell). See DEPTH_SMOOTH_KERNEL.
        val smoothed = FloatArray(depth.size)
        val half = DEPTH_SMOOTH_KERNEL / 2
        for (row in 0 until h) {
            val base = row * w
            for (col in 0 until w) {
                var sum = 0f
                var n = 0
                for (k in -half..half) {
                    val c = (col + k).coerceIn(0, w - 1)
                    sum += depth[base + c]
                    n++
                }
                smoothed[base + col] = sum / n
            }
        }

        // Diagnostic (throttled): quantify the letterbox boundary bias that
        // warps the panel top/bottom. Compare mean depth of the top/bottom
        // content rows vs the interior. If top/bot differ from mid by a large
        // margin, the first/last rows are biased by the black bars.
        depthDiagCount++
        if (depthDiagCount % 60 == 0) {
            val ct = (result.contentOffsetY * h).toInt().coerceIn(0, h - 1)
            val cb = ((result.contentOffsetY + result.contentScaleY) * h).toInt().coerceIn(ct + 1, h)
            val edge = CONTENT_EDGE_SKIP.toInt()
            if (cb - ct > edge * 2) {
                var topSum = 0f; var botSum = 0f; var midSum = 0f
                var topN = 0; var botN = 0; var midN = 0
                for (row in ct until cb) {
                    val base = row * w
                    for (col in 0 until w step 4) {
                        val d = smoothed[base + col]
                        when {
                            row < ct + edge -> { topSum += d; topN++ }
                            row >= cb - edge -> { botSum += d; botN++ }
                            else -> { midSum += d; midN++ }
                        }
                    }
                }
                logger.info {
                    "Depth boundary: top=${"%.3f".format(topSum / topN)} " +
                            "bot=${"%.3f".format(botSum / botN)} mid=${"%.3f".format(midSum / midN)} " +
                            "(content rows $ct..$cb)"
                }
            }
        }

        val bytes = ByteBuffer.allocateDirect(depth.size)
            .order(ByteOrder.nativeOrder())
        for (row in 0 until h) {
            val srcRow = h - 1 - row
            val base = srcRow * w
            for (col in 0 until w) {
                bytes.put((smoothed[base + col] * 255f).toInt().toByte())
            }
        }
        bytes.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
        // Row length may not be a multiple of 4 (154 bytes here); the default
        // UNPACK_ALIGNMENT of 4 would pad each row and skew the depth map into
        // diagonal artifacts. Pack tightly.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0,
            GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, bytes,
        )
        logger.info { "Uploaded new depth texture ${w}x$h" }
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $log")
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    companion object {
        // Forward-only parallax: max UV shift for the nearest (d = 1) pixels.
        // Lowered 0.08 -> 0.04: the real MiDaS depth map made the pop-out too
        // strong (foreground visibly offset left vs the source).
        private const val MAX_DISP = 0.04f
        // Horizontal depth blur kernel width (odd). In forward mapping the max
        // vertex displacement (~MAX_DISP = 0.04 clip) is about twice a mesh
        // cell (2/MESH_COLS ≈ 0.021 clip), so at a hard silhouette step the
        // adjacent vertices would cross each other and fold the mesh triangles
        // (torn/seam artifacts). Smoothing spreads each depth step over ~5
        // texture pixels (~2 mesh cells) so adjacent vertices never cross.
        // This is NOT the old reverse-mapping fix ("half character + background"
        // pull-through) — forward mapping already solved that.
        private const val DEPTH_SMOOTH_KERNEL = 5

        // Total clip-space disparity (MAX_DISP uv units = 2x in clip space),
        // split symmetrically: left eye +half, right eye -half.
        private const val DISP_CLIP = MAX_DISP * 2f

        // Adaptive temporal filter: blend alpha ramps from TEMPORAL_ALPHA_MIN
        // (small per-pixel delta = inference noise, heavy smoothing kills
        // flicker) to TEMPORAL_ALPHA_MAX (large delta = real motion / shot
        // change, fast follow avoids ghosting). Delta is measured relative to
        // the running raw range, so these are scale-free across scenes.
        private const val TEMPORAL_ALPHA_MIN = 0.12f
        private const val TEMPORAL_ALPHA_MAX = 0.9f
        private const val TEMPORAL_DIFF_LO = 0.02f
        private const val TEMPORAL_DIFF_HI = 0.25f

        // Running min/max adaptation speed for the stable normalization.
        // 0.1 -> the mapping converges to a changed scene within ~10 frames
        // (~0.4s). Fast enough to track real content change, slow enough that
        // a single frame's global extremes can't swing the colors.
        private const val RANGE_ALPHA = 0.1f

        // Fixed-depth-scale mode: normalized depth = raw × this constant instead
        // of the running min/max normalization. Calibrated from the "Raw depth
        // range" log: this model's typical rawMax is ~850-1050, so 1/1000 maps
        // a typical near point to ~1.0 (raw 60 -> 0.06, 500 -> 0.5, 950 -> 0.95).
        private const val FIXED_DEPTH_SCALE = 0.001f

        // How many depth texels the panel's top/bottom edge skips when sampling
        // depth. MiDaS's receptive field sees the black letterbox bars at the
        // content boundary, biasing the first ~1-2 content rows' depth; the
        // mesh then interpolates that thin biased band into a ~2% shear at the
        // panel top/bottom. Sampling a few texels inside skips the bias without
        // touching the data or fading the edge. (~0.016 * 256 input px)
        private const val CONTENT_EDGE_SKIP = 4f
        private const val MESH_COLS = 96
        private const val MESH_ROWS = 54

        private val VERTEX_WARP = """
            attribute vec4 aPos;
            attribute vec2 aUv;
            uniform sampler2D uDepth;
            uniform float uDisp;
            uniform float uDepthScaleY;
            uniform float uDepthOffsetY;
            uniform float uDepthTexH;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                // Sample depth at this vertex and displace the vertex
                // horizontally. GPU interpolation between displaced vertices
                // deforms the mesh and fills holes naturally — the figure moves
                // as a whole instead of reverse-mapping pulling background into
                // the silhouette.
                //
                // Clamp the depth sample a few texels INSIDE the content region:
                // the panel's top/bottom edge would otherwise sample the exact
                // content/letterbox boundary, where GL_LINEAR blends the black
                // bar's depth and where MiDaS's receptive field biases the
                // first content rows — both warp the top/bottom of the picture.
                // (Left/right are unaffected — 16:9 content spans the full
                // texture width, so no clamp is needed horizontally.)
                float v = uDepthOffsetY + aUv.y * uDepthScaleY;
                v = clamp(v, uDepthOffsetY + $CONTENT_EDGE_SKIP / uDepthTexH, uDepthOffsetY + uDepthScaleY - $CONTENT_EDGE_SKIP / uDepthTexH);
                float d = texture2D(uDepth, vec2(aUv.x, v)).r;
                gl_Position = vec4(aPos.x + d * uDisp, aPos.y, 0.0, 1.0);
            }
        """.trimIndent()

        // The video ring frame is already display-ready (the OES was copied into
        // it with the transform matrix applied), so no texMatrix is needed.
        private val FRAG_WARP = """
            precision mediump float;
            uniform sampler2D uVideo;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uVideo, vUv);
            }
        """.trimIndent()

        // Samples a regular 2D texture into an FBO (ring frame -> model input).
        private val FRAG_COPY = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uTex, vUv);
            }
        """.trimIndent()

        private val VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = aPos;
            }
        """.trimIndent()

        private val FRAG_LEFT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uVideo;
            uniform mat4 uTexMatrix;
            varying vec2 vUv;
            void main() {
                vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
                gl_FragColor = texture2D(uVideo, uv);
            }
        """.trimIndent()

        private val FRAG_DEPTH = """
            precision mediump float;
            uniform sampler2D uDepth;
            uniform float uDepthScaleY;
            uniform float uDepthOffsetY;
            uniform float uDepthTexH;
            varying vec2 vUv;
            void main() {
                // Same clamp as the warp shader so the debug view matches the
                // depth the renderer actually uses (skips the biased boundary).
                float v = uDepthOffsetY + vUv.y * uDepthScaleY;
                v = clamp(v, uDepthOffsetY + $CONTENT_EDGE_SKIP / uDepthTexH, uDepthOffsetY + uDepthScaleY - $CONTENT_EDGE_SKIP / uDepthTexH);
                float d = texture2D(uDepth, vec2(vUv.x, v)).r;
                // blue (far) -> cyan -> yellow -> red (near) colormap
                vec3 col = mix(vec3(0.0, 0.0, 1.0), vec3(1.0, 0.0, 0.0), d);
                gl_FragColor = vec4(col, 1.0);
            }
        """.trimIndent()

        const val SAMPLE_WIDTH = 320
        const val SAMPLE_HEIGHT = 180

        // Video ring: how many display frames we keep, and the copy resolution.
        // Depth inference takes ~36ms (~2-3 frames at 60fps), so 6 slots
        // comfortably cover the latency before a frame's depth is ready.
        private const val RING_SIZE = 6
        private const val RING_W = 1920
        private const val RING_H = 1080
    }
}
