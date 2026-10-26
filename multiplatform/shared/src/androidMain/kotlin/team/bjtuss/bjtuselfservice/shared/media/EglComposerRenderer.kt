package team.bjtuss.bjtuselfservice.shared.media

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 把两张外部纹理（解码器输出到 SurfaceTexture）左右并排画进编码器的输入 Surface。
 *
 * 左半、右半各占画面一半，每一路按比例缩放后居中，不足处留黑边（保持 16:9 不变形）。
 * 画面坐标用 GL 的左下角原点；片元着色器里再把 v 轴翻转回来（SurfaceTexture 的第一行在 v=0）。
 */
internal class EglComposerRenderer(private val encoderSurface: Surface) {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0
    private var rectHandle = 0
    private var canvasWidth = 0
    private var canvasHeight = 0

    private val quad: FloatBuffer = floatBuffer(
        floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        ),
    )

    private val texCoords: FloatBuffer = floatBuffer(
        floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        ),
    )

    fun makeCurrent() {
        if (display == EGL14.EGL_NO_DISPLAY) createEgl()
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw CompositionException("无法切换到 OpenGL 上下文")
        }
        // 编码器 Surface 没有屏幕 VSync；保留同步 BufferQueue 的背压。
        // interval=0 会启用异步投递，生产快于编码器时可丢弃尚未消费的帧。
        EGL14.eglSwapInterval(display, 1)
        // 着色器程序必须在上下文已经 current 之后创建，否则 glCreateShader 直接返回 0。
        if (program == 0) createProgram()
    }

    private fun createEgl() {
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw CompositionException("无法获取 EGL 显示")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw CompositionException("无法初始化 EGL")
        }
        display = eglDisplay
        EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, configCount, 0) || configCount[0] <= 0) {
            throw CompositionException("没有可用的 EGL 配置")
        }
        val config = configs[0] ?: throw CompositionException("没有可用的 EGL 配置")
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
        if (context == EGL14.EGL_NO_CONTEXT) throw CompositionException("无法创建 OpenGL 上下文")
        val surfaceAttributes = intArrayOf(EGL14.EGL_NONE)
        surface = EGL14.eglCreateWindowSurface(display, config, encoderSurface, surfaceAttributes, 0)
        if (surface == EGL14.EGL_NO_SURFACE) throw CompositionException("无法创建编码器输入 Surface")
    }

    private fun createProgram() {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw CompositionException("OpenGL 着色器链接失败：$log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        rectHandle = GLES20.glGetUniformLocation(program, "uRect")
    }

    /** 设定画布尺寸（等于编码器输入尺寸）。 */
    fun setCanvasSize(width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height
    }

    /**
     * 画一帧：左半放 [leftTexture]，右半放 [rightTexture]。
     * [leftSize]/[rightSize] 是解码画面的原始像素尺寸，用来算等比缩放后的黑边。
     */
    fun drawFrame(
        leftTexture: Int,
        leftSize: Pair<Int, Int>,
        rightTexture: Int,
        rightSize: Pair<Int, Int>,
    ) {
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        GLES20.glViewport(0, 0, canvasWidth, canvasHeight)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quad)
        texCoords.position(0)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val halfWidth = canvasWidth / 2
        drawHalf(leftTexture, leftSize, 0, halfWidth)
        drawHalf(rightTexture, rightSize, halfWidth, halfWidth)
    }

    private fun drawHalf(texture: Int, sourceSize: Pair<Int, Int>, xOffset: Int, halfWidth: Int) {
        val sourceWidth = sourceSize.first
        val sourceHeight = sourceSize.second
        val rect = fitRect(sourceWidth, sourceHeight, xOffset, halfWidth, canvasHeight)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform4f(rectHandle, rect[0], rect[1], rect[2], rect[3])
        GLES20.glViewport(xOffset, 0, halfWidth, canvasHeight)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(xOffset, 0, halfWidth, canvasHeight)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    /** 等比缩放后在半区里居中；返回 (x, y, w, h)，y 自下而上。 */
    private fun fitRect(
        sourceWidth: Int,
        sourceHeight: Int,
        xOffset: Int,
        halfWidth: Int,
        canvasHeight: Int,
    ): FloatArray {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return floatArrayOf(xOffset.toFloat(), 0f, halfWidth.toFloat(), canvasHeight.toFloat())
        }
        val scale = minOf(halfWidth.toFloat() / sourceWidth, canvasHeight.toFloat() / sourceHeight)
        val width = sourceWidth * scale
        val height = sourceHeight * scale
        return floatArrayOf(
            xOffset + (halfWidth - width) / 2f,
            (canvasHeight - height) / 2f,
            width,
            height,
        )
    }

    fun setPresentationTime(nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun swapBuffers() {
        if (!EGL14.eglSwapBuffers(display, surface)) {
            throw CompositionException("画面提交失败")
        }
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        surface = EGL14.EGL_NO_SURFACE
        program = 0
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            throw CompositionException("无法创建着色器（glCreateShader 返回 0），GL 错误 ${GLES20.glGetError()}")
        }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        val log = GLES20.glGetShaderInfoLog(shader)
        if (status[0] != GLES20.GL_TRUE) {
            val kind = if (type == GLES20.GL_VERTEX_SHADER) "顶点" else "片元"
            val error = GLES20.glGetError()
            android.util.Log.e(TAG, "shader compile failed ($kind): $log / glError=$error\n$source")
            GLES20.glDeleteShader(shader)
            throw CompositionException("OpenGL $kind 着色器编译失败：$log（GL 错误 $error）")
        }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    private companion object {
        private const val TAG = "EglComposer"
        /** EGL_RECORDABLE_ANDROID：允许把编码器输入 Surface 作为 EGL 窗口。 */
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform vec4 uRect;
            void main() {
                vec2 rel = gl_FragCoord.xy - uRect.xy;
                if (rel.x < 0.0 || rel.y < 0.0 || rel.x > uRect.z || rel.y > uRect.w) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                } else {
                    vec2 uv = rel / uRect.zw;
                    gl_FragColor = texture2D(uTexture, vec2(uv.x, 1.0 - uv.y));
                }
            }
        """
    }
}
