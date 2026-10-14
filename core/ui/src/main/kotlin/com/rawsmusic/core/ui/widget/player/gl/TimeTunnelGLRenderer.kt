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
 * 基于 OpenGL ES 2.0 / 3.0 的 3D 时空虫洞隧道 (Time Tunnel) 动态可视化渲染器。
 * 核心技术：3D Raymarching 光线步进、分形正弦位移、双层反向漩涡管道、音频低频脉冲加速。
 */
class TimeTunnelGLRenderer : IAudioRaymarchingRenderer {

    // 全屏四边形顶点 (两个三角形拼成的四边形，NDC 坐标系)
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
    @Volatile override var color1R: Float = 0.08f
    @Volatile override var color1G: Float = 0.35f
    @Volatile override var color1B: Float = 0.95f

    @Volatile override var color2R: Float = 0.95f
    @Volatile override var color2G: Float = 0.25f
    @Volatile override var color2B: Float = 0.45f

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

        // 播放状态下速度由低音能量动态加速冲刺，暂停状态下以微速悠然漂浮
        val speedMultiplier = if (isPlaying) {
            0.85f + smoothBass * 1.5f
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

            // 忠实还原原版的 tanh 双曲正切色调映射 (极富胶片质感与光晕通透感)
            vec3 accurateTanh(vec3 x) {
                vec3 ex = exp(clamp(x * 2.0, -12.0, 12.0));
                return (ex - 1.0) / (ex + 1.0);
            }

            // 1. 原版外层大型有机时空管道 (保留原版正弦分形骨架与深蓝-金橙流光)
            vec3 renderOuterTunnel(vec2 uv, float t, vec2 r, float bass) {
                vec3 o = vec3(0.0);
                float z = 0.0;
                float d = 0.0;
                
                // 广角视口透视：以较小边归一化，焦距 0.80 保证整块屏幕被隧道管壁饱满充盈
                vec2 pCoord = (uv * 2.0 - r) / min(r.x, r.y);
                vec3 rayDir = normalize(vec3(pCoord, 0.80));
                
                // 预先提取循环外不变量，大幅减轻移动端 GPU 算力负担
                float sinT = sin(t);
                vec3 pOffset = vec3(t * 0.8, t * 0.4, 0.0);
                float tunnelRadius = 3.0 + bass * 0.4;
                float zSpeed = t * (3.0 + bass * 0.8);
                float rotBase = t * 0.2;
                
                for (int i = 0; i < 34; i++) {
                    if (z > 32.0) break;
                    
                    vec3 q = z * rayDir;
                    q.z += zSpeed;
                    
                    float a = sin(q.z * 0.1) * 0.5 + rotBase;
                    float cosA = cos(a);
                    float sinA = sin(a);
                    q.xy = mat2(cosA, -sinA, sinA, cosA) * q.xy;
                    
                    // 原版标志性的多尺度有机分形扰动
                    vec3 p = q;
                    for (float s = 0.2; s < 5.0; s /= 0.58) {
                        p += (abs(sin(p.yzx * s + pOffset)) - 0.6) / s;
                    }
                    
                    // 管道壁面距离场 (低频律动呼吸)
                    float tunnel = abs(length(q.xy) - tunnelRadius + sin(q.z * 0.4 + sinT) * 1.1) * 0.125;
                    d = 0.005 + tunnel + abs(p.x * 0.012);
                    
                    z += d;
                    
                    // 原版深蓝到金橙梦幻时空漩涡配色
                    vec3 timeVortexCol = mix(
                        vec3(0.05, 0.25, 0.9),
                        vec3(0.95, 0.35, 0.05),
                        0.5 + 0.5 * sin(q.z * 0.15 + t * 0.5)
                    );
                    timeVortexCol += vec3(0.1, 0.4, 0.8) * cos(q.z * 0.3 - t);
                    
                    // 丰满辉光厚度公式 (增强外围可见度，让整屏四周光彩夺目)
                    float glow = exp(-z * 0.05) / (d * 24.0 + 0.003);
                    o += timeVortexCol * glow;
                }
                return o;
            }

            // 2. 原版内层高能反向漩涡光流 (保留洋红-青绿对冲霓虹与反向分形)
            vec3 renderInnerTunnel(vec2 uv, float t, vec2 r, float mid) {
                vec3 o = vec3(0.0);
                float tInner = t * 1.5;
                float z = 0.0;
                float d = 0.0;
                
                vec2 pCoord = (uv * 2.0 - r) / min(r.x, r.y);
                vec3 rayDir = normalize(vec3(pCoord, 0.80));
                
                // 预先提取内层循环外不变量
                vec3 innerOffset = vec3(0.0, tInner * 0.6, tInner * 0.9);
                float innerZSpeed = tInner * 4.5;
                float innerRotBase = -tInner * 0.3;
                
                for (int i = 0; i < 26; i++) {
                    if (z > 25.0) break;
                    
                    vec3 q = z * rayDir;
                    q.z += innerZSpeed;
                    
                    float a = -sin(q.z * 0.15) * 0.7 + innerRotBase;
                    float cosA = cos(a);
                    float sinA = sin(a);
                    q.xy = mat2(cosA, -sinA, sinA, cosA) * q.xy;
                    
                    // 原版内层反向正弦分形
                    vec3 p = q;
                    for (float s = 0.3; s < 4.5; s /= 0.62) {
                        p += (abs(sin(p.zxy * s + innerOffset)) - 0.55) / s;
                    }
                    
                    float tunnel = abs(length(q.xy) - 1.6 + cos(q.z * 0.6 - tInner) * 0.7) * 0.142857;
                    d = 0.006 + tunnel + abs(p.y * 0.015);
                    
                    z += d;
                    
                    // 原版洋红粉紫到青碧荧光霓虹配色
                    vec3 colPattern = mix(
                        vec3(0.8, 0.1, 0.6),
                        vec3(0.1, 0.9, 0.7),
                        0.5 + 0.5 * cos(q.z * 0.2 - tInner)
                    );
                    
                    // 丰满核心辉光公式
                    float glow = exp(-z * 0.07) / (d * 32.0 + 0.004);
                    o += colPattern * glow;
                }
                return o;
            }

            void main() {
                vec2 uv = gl_FragCoord.xy;
                
                // 1. 渲染外层时空虫洞管道
                vec3 bg = renderOuterTunnel(uv, uTime, uResolution, uBass);
                
                // 2. 渲染内层高速反向光流核心
                vec3 fg = renderInnerTunnel(uv, uTime, uResolution, uMid);
                
                // 3. 原版经典合成混合公式
                vec3 finalCol = bg + fg * 0.75;
                
                // 4. 原版标志性 tanh 动态胶片压缩映射
                finalCol = accurateTanh(finalCol * 0.040);
                
                gl_FragColor = vec4(finalCol, 1.0);
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
