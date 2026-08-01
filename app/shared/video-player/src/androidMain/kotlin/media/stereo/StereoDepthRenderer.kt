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
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

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
    private val refreshMillis: Long = 1_500L,
    var strength: Float = 1f,
    var debugShowDepth: Boolean = false,
    private val onSurfaceTextureReady: (android.graphics.SurfaceTexture) -> Unit = {},
) : GLSurfaceView.Renderer {

    private val logger = logger<StereoDepthRenderer>()

    // Video input
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    private var videoTexId = 0
    private val texMatrix = FloatArray(16)

    // Depth
    private var depthTexId = 0
    private val pendingDepth = AtomicReference<DepthResult?>(null)
    private var lastDepthSample = 0L

    // FBO for frame sampling
    private var sampleFbo = 0
    private var sampleTex = 0
    private var sampleWidth = 0
    private var sampleHeight = 0

    private var viewportWidth = 1
    private var viewportHeight = 1

    // Shader programs
    private var leftProgram = 0
    private var rightProgram = 0
    private var depthProgram = 0
    private var quadBuffer: FloatBuffer? = null

    // Uniform locations (right eye program)
    private var uVideoRight = -1
    private var uDepth = -1
    private var uStrength = -1
    private var uMaxDisp = -1
    private var uDepthScaleY = -1
    private var uDepthOffsetY = -1
    private var uTexMatrixRight = -1

    // Uniform locations (left eye program)
    private var uVideoLeft = -1
    private var uTexMatrixLeft = -1

    // Depth visualization program
    private var uDepthDepth = -1
    private var uDepthScaleYDepth = -1
    private var uDepthOffsetYDepth = -1

    // Letterboxed content region inside the square depth texture.
    private var depthScaleY = 1f
    private var depthOffsetY = 0f

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
        rightProgram = createProgram(VERTEX_SHADER, FRAG_RIGHT)

        uVideoRight = GLES20.glGetUniformLocation(rightProgram, "uVideo")
        uDepth = GLES20.glGetUniformLocation(rightProgram, "uDepth")
        uStrength = GLES20.glGetUniformLocation(rightProgram, "uStrength")
        uMaxDisp = GLES20.glGetUniformLocation(rightProgram, "uMaxDisp")
        uDepthScaleY = GLES20.glGetUniformLocation(rightProgram, "uDepthScaleY")
        uDepthOffsetY = GLES20.glGetUniformLocation(rightProgram, "uDepthOffsetY")
        uTexMatrixRight = GLES20.glGetUniformLocation(rightProgram, "uTexMatrix")

        uVideoLeft = GLES20.glGetUniformLocation(leftProgram, "uVideo")
        uTexMatrixLeft = GLES20.glGetUniformLocation(leftProgram, "uTexMatrix")

        depthProgram = createProgram(VERTEX_SHADER, FRAG_DEPTH)
        uDepthDepth = GLES20.glGetUniformLocation(depthProgram, "uDepth")
        uDepthScaleYDepth = GLES20.glGetUniformLocation(depthProgram, "uDepthScaleY")
        uDepthOffsetYDepth = GLES20.glGetUniformLocation(depthProgram, "uDepthOffsetY")

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
        }
        surfaceTexture = st
        onSurfaceTextureReady(st)

        logger.info { "StereoDepthRenderer surface created" }
    }

    private var hasVideoFrame = false

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        // SurfaceTexture's transform matrix is undefined (all zeros) before the
        // first frame arrives; a zero matrix would sample the texture corner and
        // render black. Fall back to identity until real frames come in.
        if (texMatrix.all { it == 0f }) {
            Matrix.setIdentityM(texMatrix, 0)
        }

        maybeSampleFrame()

        val half = viewportWidth / 2

        // Left eye: original frame
        GLES20.glViewport(0, 0, half, viewportHeight)
        drawLeft()

        // Right eye: DIBR warped
        GLES20.glViewport(half, 0, viewportWidth - half, viewportHeight)
        drawRight()

        // Upload pending depth (from inference thread)
        uploadPendingDepthIfAny()

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

    private fun drawLeft() {
        GLES20.glUseProgram(leftProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glUniform1i(uVideoLeft, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLeft, 1, false, texMatrix, 0)
        drawQuadRaw(leftProgram)
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
            drawQuadRaw(depthProgram)
            return
        }
        GLES20.glUseProgram(rightProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glUniform1i(uVideoRight, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixRight, 1, false, texMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexId)
        GLES20.glUniform1i(uDepth, 1)
        GLES20.glUniform1f(uStrength, strength)
        GLES20.glUniform1f(uMaxDisp, MAX_DISP)
        GLES20.glUniform1f(uDepthScaleY, depthScaleY)
        GLES20.glUniform1f(uDepthOffsetY, depthOffsetY)
        drawQuadRaw(rightProgram)
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

    /** Copies the current frame to a small bitmap and triggers async depth inference. */
    private fun maybeSampleFrame() {
        // No video frames yet (media source still loading) — skip inference to
        // keep CPU free for the player. Depth texture stays at the placeholder.
        if (!hasVideoFrame) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDepthSample < refreshMillis) return
        lastDepthSample = now

        ensureSampleFbo(SAMPLE_WIDTH, SAMPLE_HEIGHT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sampleFbo)
        GLES20.glViewport(0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
        GLES20.glUseProgram(leftProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexId)
        GLES20.glUniform1i(uVideoLeft, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLeft, 1, false, texMatrix, 0)
        drawQuadRaw(leftProgram)

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

        scope.launch {
            try {
                val result = estimator.estimateDepth(flipped)
                pendingDepth.set(result)
            } catch (e: Throwable) {
                logger.info(e) { "Depth inference failed" }
            } finally {
                flipped.recycle()
            }
        }
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
        val result = pendingDepth.getAndSet(null) ?: return
        val depth = result.depth
        val w = result.width
        val h = result.height
        if (w <= 0 || h <= 0 || w * h != depth.size) return
        // Letterboxed content region (video area inside the square depth map).
        depthScaleY = result.contentScaleY.coerceIn(0.01f, 1f)
        depthOffsetY = result.contentOffsetY.coerceIn(0f, 0.99f)
        // Flip rows vertically: GL texture (0,0) is the bottom-left, but the
        // depth array starts with the top row of the (screen-space) frame.
        // Without this the depth map is upside down and the stereo shape
        // doesn't match the picture.
        val bytes = ByteBuffer.allocateDirect(depth.size)
            .order(ByteOrder.nativeOrder())
        for (row in 0 until h) {
            val srcRow = h - 1 - row
            val base = srcRow * w
            for (col in 0 until w) {
                bytes.put((depth[base + col] * 255f).toInt().toByte())
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
        // 0.08 = 8% of the screen width, a clearly visible pop-out.
        private const val MAX_DISP = 0.08f

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
            varying vec2 vUv;
            void main() {
                float d = texture2D(uDepth, vec2(vUv.x, uDepthOffsetY + vUv.y * uDepthScaleY)).r;
                // blue (far) -> cyan -> yellow -> red (near) colormap
                vec3 col = mix(vec3(0.0, 0.0, 1.0), vec3(1.0, 0.0, 0.0), d);
                gl_FragColor = vec4(col, 1.0);
            }
        """.trimIndent()

        private val FRAG_RIGHT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uVideo;
            uniform sampler2D uDepth;
            uniform mat4 uTexMatrix;
            uniform float uStrength;
            uniform float uMaxDisp;
            uniform float uDepthScaleY;
            uniform float uDepthOffsetY;
            varying vec2 vUv;
            void main() {
                vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;
                // Sample the letterboxed video region inside the square depth map.
                float d = texture2D(uDepth, vec2(uv.x, uDepthOffsetY + uv.y * uDepthScaleY)).r;
                // Forward-only parallax: background (d ~ 0) stays glued to the
                // screen plane; only nearer regions shift right in the right
                // eye so they visibly pop out toward the viewer. No convergence
                // offset — the background never moves, so the scene can't
                // appear closer than the panel.
                float disp = d * uMaxDisp * uStrength;
                uv.x = clamp(uv.x - disp, 0.0, 1.0);
                gl_FragColor = texture2D(uVideo, uv);
            }
        """.trimIndent()

        const val SAMPLE_WIDTH = 320
        const val SAMPLE_HEIGHT = 180
    }
}
