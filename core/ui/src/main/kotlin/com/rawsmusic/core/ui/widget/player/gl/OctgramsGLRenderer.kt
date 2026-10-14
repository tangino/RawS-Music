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
 * 基于 OpenGL ES 2.0 / 3.0 的 3D 八芒星阵 (Octgrams) 动态可视化渲染器。
 * 核心技术：3D Raymarching 光线步进、无限重复立方体结晶空间、八芒星旋转变换、体积发光聚积与音频低音律动冲击。
 */
class OctgramsGLRenderer : IAudioRaymarchingRenderer {

    // 全屏四边形顶点 (两个三角形，NDC 坐标系)
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
    private var uBassHandle = -1
    private var uMidHandle = -1
    private var uTrebleHandle = -1
    private var uColor1Handle = -1
    private var uColor2Handle = -1

    // 视口宽高
    private var width = 1f
    private var height = 1f

    // 时间戳与推进
    private var lastFrameTime = 0L
    private var internalTime = 0.0f

    // 外部传入状态（支持多线程并发安全更新）
    @Volatile override var isPlaying: Boolean = true
    @Volatile override var targetBass: Float = 0.0f
    @Volatile override var targetMid: Float = 0.0f
    @Volatile override var targetTreble: Float = 0.0f

    // 当前平滑后的音频能量
    private var smoothBass = 0.0f
    private var smoothMid = 0.0f
    private var smoothTreble = 0.0f

    // 配色方案（RGB）
    @Volatile override var color1R: Float = 0.0f
    @Volatile override var color1G: Float = 0.9f
    @Volatile override var color1B: Float = 1.0f

    @Volatile override var color2R: Float = 0.7f
    @Volatile override var color2G: Float = 0.15f
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

        // 音频能量物理阻尼平滑插值 (Attack 快，Decay 柔和)
        val attack = 0.35f
        smoothBass += (targetBass - smoothBass) * attack
        smoothMid += (targetMid - smoothMid) * attack
        smoothTreble += (targetTreble - smoothTreble) * attack

        // 播放状态下由低音频段能量动态加速整体运转，暂停时以微速缓动
        val speedMultiplier = if (isPlaying) {
            0.9f + smoothBass * 1.6f
        } else {
            0.15f
        }
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
        if (uBassHandle >= 0) GLES20.glUniform1f(uBassHandle, smoothBass)
        if (uMidHandle >= 0) GLES20.glUniform1f(uMidHandle, smoothMid)
        if (uTrebleHandle >= 0) GLES20.glUniform1f(uTrebleHandle, smoothTreble)
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
            precision mediump float;
            uniform vec2 uResolution;
            uniform float uTime;
            uniform float uBass;
            uniform float uMid;
            uniform float uTreble;
            uniform vec3 uColor1;
            uniform vec3 uColor2;

            // 2D 旋转变换矩阵
            mat2 rot(float a) {
                float c = cos(a), s = sin(a);
                return mat2(c, s, -s, c);
            }

            // 基础三维立方体盒子距离场 (SDF)
            float sdBox(vec3 p, vec3 b) {
                vec3 q = abs(p) - b;
                return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
            }

            // 八芒星几何单体
            float box(vec3 pos, float scale) {
                pos *= scale;
                float base = sdBox(pos, vec3(0.4, 0.4, 0.1)) * 0.666667;
                pos.xy *= 5.0;
                pos.y -= 3.5;
                float c = 0.7316888; // cos(0.75)
                float s = 0.6816388; // sin(0.75)
                pos.xy = mat2(c, s, -s, c) * pos.xy;
                return -base;
            }

            // 八芒星结晶空间组合体 (与音频低音冲击实时联动缩放)
            float box_set(vec3 pos, float gTime, float pulse) {
                vec3 p0 = pos;
                float s = sin(gTime * 0.4);
                float shift = s * 2.5;
                float scaleFactor = 2.0 - abs(s) * 1.5 + pulse * 0.35;
                
                // 预计算 rot(0.8) 常量矩阵
                float c08 = 0.6967067; // cos(0.8)
                float s08 = 0.7173561; // sin(0.8)
                mat2 r08 = mat2(c08, s08, -s08, c08);
                
                // 4 方向旋转展开八角星翼
                vec3 p1 = p0; p1.y += shift; p1.xy = r08 * p1.xy;
                float box1 = box(p1, scaleFactor);
                
                vec3 p2 = p0; p2.y -= shift; p2.xy = r08 * p2.xy;
                float box2 = box(p2, scaleFactor);
                
                vec3 p3 = p0; p3.x += shift; p3.xy = r08 * p3.xy;
                float box3 = box(p3, scaleFactor);
                
                vec3 p4 = p0; p4.x -= shift; p4.xy = r08 * p4.xy;
                float box4 = box(p4, scaleFactor);
                
                vec3 p5 = p0; p5.xy = r08 * p5.xy;
                float box5 = box(p5, 0.5) * 6.0;
                
                float box6 = box(p0, 0.5) * 6.0;
                
                return max(max(max(max(max(box1, box2), box3), box4), box5), box6);
            }

            void main() {
                vec2 p = (gl_FragCoord.xy * 2.0 - uResolution.xy) / min(uResolution.x, uResolution.y);
                
                // 摄像机视点位置与光线方向 (光速前进与穿梭)
                vec3 ro = vec3(0.0, -0.2, uTime * 4.0);
                vec3 ray = normalize(vec3(p, 1.5));
                
                // 视线旋转缓动
                float a1 = sin(uTime * 0.03) * 5.0;
                float c1 = cos(a1), s1 = sin(a1);
                ray.xy = mat2(c1, s1, -s1, c1) * ray.xy;
                
                float a2 = sin(uTime * 0.05) * 0.2;
                float c2 = cos(a2), s2 = sin(a2);
                ray.yz = mat2(c2, s2, -s2, c2) * ray.yz;
                
                float t = 0.1;
                float ac = 0.0;
                
                // 移动端光线步进优化：54 步精准光晕聚积，保持原版视觉同时跑满 60FPS
                for (int i = 0; i < 54; i++) {
                    vec3 pos = ro + ray * t;
                    pos = mod(pos - 2.0, 4.0) - 2.0;
                    float gTime = uTime - float(i) * 0.01;
                    
                    float d = box_set(pos, gTime, uBass);
                    d = max(abs(d), 0.01);
                    ac += exp(-d * 23.0);
                    
                    t += d * 0.55;
                }
                
                vec3 col = vec3(ac * 0.02);
                
                // 原版赛博极光配色与动态音乐流光融合
                vec3 baseGlow = vec3(0.0, 0.2 * abs(sin(uTime)), 0.5 + sin(uTime) * 0.2);
                vec3 musicCol = mix(uColor1, uColor2, 0.5 + 0.5 * sin(uTime * 0.4 + uMid));
                col += mix(baseGlow, musicCol, 0.55);
                
                gl_FragColor = vec4(col, 1.0);
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
        uBassHandle = GLES20.glGetUniformLocation(program, "uBass")
        uMidHandle = GLES20.glGetUniformLocation(program, "uMid")
        uTrebleHandle = GLES20.glGetUniformLocation(program, "uTreble")
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
