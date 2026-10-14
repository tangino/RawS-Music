package com.rawsmusic.core.ui.widget.player.gl

import android.opengl.GLES20
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * 殿堂级量子流体漩涡 (Quantum Vortex / Quantum Flower) 动态分形可视化渲染器。
 * 核心技术：非线性极坐标扭曲场、多频段流体脉动膨胀与四维流形余弦调色。
 */
class QuantumVortexGLRenderer : IAudioRaymarchingRenderer {

    // 全屏四边形顶点 (NDC 坐标系)
    private val quadVertices = floatArrayOf(
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f,
    )

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(quadVertices)
            position(0)
        }

    private var programId = 0
    private var aPositionHandle = -1
    private var uResolutionHandle = -1
    private var uTimeHandle = -1
    private var uFreqsHandle = -1
    private var uColor1Handle = -1
    private var uColor2Handle = -1

    // 视口宽高
    private var width = 1f
    private var height = 1f

    // 时间戳与内部时间推进
    private var lastFrameTime = 0L
    private var internalTime = 0.0f

    // 外部传入状态
    @Volatile override var isPlaying: Boolean = true
    @Volatile var freq0Target: Float = 0.0f
    @Volatile var freq1Target: Float = 0.0f
    @Volatile var freq2Target: Float = 0.0f
    @Volatile var freq3Target: Float = 0.0f

    override var targetBass: Float
        get() = freq0Target
        set(value) {
            freq0Target = value
            freq1Target = (value * 0.8f).coerceIn(0f, 1.5f)
        }

    override var targetMid: Float
        get() = freq2Target
        set(value) { freq2Target = value }

    override var targetTreble: Float
        get() = freq3Target
        set(value) { freq3Target = value }

    // 平滑后的 4 频段能量
    private var freq0 = 0.15f
    private var freq1 = 0.15f
    private var freq2 = 0.15f
    private var freq3 = 0.15f

    // 配色方案（RGB）
    @Volatile override var color1R: Float = 0.0f
    @Volatile override var color1G: Float = 0.9f
    @Volatile override var color1B: Float = 1.0f

    @Volatile override var color2R: Float = 0.8f
    @Volatile override var color2G: Float = 0.2f
    @Volatile override var color2B: Float = 1.0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        initShaders()
        lastFrameTime = SystemClock.uptimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = max(1, width).toFloat()
        this.height = max(1, height).toFloat()
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (programId == 0) return

        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrameTime == 0L) 0.016f else ((now - lastFrameTime) / 1000.0f).coerceIn(0.001f, 0.1f)
        lastFrameTime = now

        // 音频能量阻尼追踪
        val attack = 0.35f
        freq0 += (max(0.08f, freq0Target) - freq0) * attack
        freq1 += (max(0.08f, freq1Target) - freq1) * attack
        freq2 += (max(0.08f, freq2Target) - freq2) * attack
        freq3 += (max(0.08f, freq3Target) - freq3) * attack

        // 播放状态下以音频驱动动态速率流动，暂停时微速漫游
        val speedMultiplier = if (isPlaying) (1.0f + freq0 * 0.75f) else 0.22f
        internalTime += dt * speedMultiplier

        GLES20.glUseProgram(programId)

        // 绑定顶点数据
        if (aPositionHandle >= 0) {
            vertexBuffer.position(0)
            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        }

        // 传递 Uniform 变量
        if (uResolutionHandle >= 0) GLES20.glUniform2f(uResolutionHandle, width, height)
        if (uTimeHandle >= 0) GLES20.glUniform1f(uTimeHandle, internalTime)
        if (uFreqsHandle >= 0) GLES20.glUniform4f(uFreqsHandle, freq0, freq1, freq2, freq3)
        if (uColor1Handle >= 0) GLES20.glUniform3f(uColor1Handle, color1R, color1G, color1B)
        if (uColor2Handle >= 0) GLES20.glUniform3f(uColor2Handle, color2R, color2G, color2B)

        // 绘制全屏四边形
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (aPositionHandle >= 0) {
            GLES20.glDisableVertexAttribArray(aPositionHandle)
        }
    }

    private fun initShaders() {
        val vertexShaderSource = """
            attribute vec4 aPosition;
            void main() {
                gl_Position = aPosition;
            }
        """.trimIndent()

        val fragmentShaderSource = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif

            uniform vec2 uResolution;
            uniform float uTime;
            uniform vec4 uFreqs;
            uniform vec3 uColor1;
            uniform vec3 uColor2;

            // GLES 2.0 兼容的数值稳定双曲正切 (Safe tanh)
            vec2 safe_tanh(vec2 x) {
                vec2 c = clamp(x, -18.0, 18.0);
                vec2 exp2c = exp(2.0 * c);
                return (exp2c - 1.0) / (exp2c + 1.0);
            }

            void main() {
                vec2 v = uResolution.xy;
                // 坐标归一化并以高度比例为基准
                vec2 u = 0.2 * (gl_FragCoord.xy * 2.0 - v) / v.y;
                
                // 音频低频驱动核心呼吸绽放
                float audioScale = 1.0 + uFreqs.x * 0.18;
                u /= audioScale;

                vec4 z = vec4(1.0, 2.0, 3.0, 0.0);
                vec4 o = z;
                
                float a = 0.5;
                float t = uTime;

                for (int i = 1; i < 19; ++i) {
                    float fi = float(i);
                    
                    // 能量累加
                    vec2 sinArg = 1.5 * u / (0.5 - dot(u, u)) - 9.0 * u.yx + t;
                    float denom = length((1.0 + fi * dot(v, v)) * sin(sinArg));
                    o += (1.0 + cos(z + t)) / max(0.0001, denom);

                    // 步进推进与四维流形流动
                    t += 1.0;
                    a += 0.03;
                    v = cos(t - 7.0 * u * pow(a, fi)) - 5.0 * u;

                    // 2x2 旋转矩阵显式构造（确保移动端各种 GPU 驱动无歧义兼容）
                    vec4 rotAng = fi + 0.02 * t - z.wxzw * 11.0;
                    vec4 rotCos = cos(rotAng);
                    mat2 rotM = mat2(rotCos.x, rotCos.y, rotCos.z, rotCos.w);
                    u = rotM * u;

                    // 安全双曲正切非线性流动反馈
                    vec2 tanhIn = 40.0 * dot(u, u) * cos(100.0 * u.yx + t);
                    u += safe_tanh(tanhIn) / 200.0
                       + 0.2 * a * u
                       + cos(4.0 / exp(clamp(dot(o, o) / 100.0, 0.0, 20.0)) + t) / 300.0;
                }

                // 殿堂级色调曲线重映射
                vec4 col = 25.6 / (min(o, 13.0) + 164.0 / max(vec4(0.001), o)) - dot(u, u) / 250.0;

                // 柔和融入当前播放主题色相
                vec3 themeTone = mix(uColor1, uColor2, clamp(col.r * 1.2, 0.0, 1.0));
                col.rgb = mix(col.rgb, col.rgb * themeTone * 1.35, 0.22);

                gl_FragColor = vec4(clamp(col.rgb, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSource)

        if (vertexShader == 0 || fragmentShader == 0) return

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            GLES20.glDeleteProgram(program)
            return
        }

        programId = program
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        uResolutionHandle = GLES20.glGetUniformLocation(program, "uResolution")
        uTimeHandle = GLES20.glGetUniformLocation(program, "uTime")
        uFreqsHandle = GLES20.glGetUniformLocation(program, "uFreqs")
        uColor1Handle = GLES20.glGetUniformLocation(program, "uColor1")
        uColor2Handle = GLES20.glGetUniformLocation(program, "uColor2")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    override fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
    }
}
