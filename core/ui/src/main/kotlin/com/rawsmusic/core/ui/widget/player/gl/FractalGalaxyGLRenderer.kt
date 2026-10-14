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
 * 3D 视差滚动分形星系 (Fractal Galaxy) 动态可视化渲染器。
 * 核心技术：双层视差宇宙分形星云 (Kaliset 分形场)、多频段音频能量驱动星云涌动、程序化闪烁繁星星尘。
 */
class FractalGalaxyGLRenderer : IAudioRaymarchingRenderer {

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
    private var uFreqsHandle = -1
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
    private var freq0 = 0.2f
    private var freq1 = 0.2f
    private var freq2 = 0.2f
    private var freq3 = 0.2f

    // 配色方案（RGB）
    @Volatile override var color1R: Float = 1.0f
    @Volatile override var color1G: Float = 0.6f
    @Volatile override var color1B: Float = 0.2f

    @Volatile override var color2R: Float = 0.4f
    @Volatile override var color2G: Float = 0.2f
    @Volatile override var color2B: Float = 0.95f

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

        // 音频能量平滑插值 (有最低保底 0.12f，保证在音乐安静时星系依然有呼吸微光)
        val attack = 0.38f
        freq0 += (max(0.12f, freq0Target) - freq0) * attack
        freq1 += (max(0.12f, freq1Target) - freq1) * attack
        freq2 += (max(0.12f, freq2Target) - freq2) * attack
        freq3 += (max(0.12f, freq3Target) - freq3) * attack

        // 播放状态下速度由低频能量加速流转，暂停状态下以微速漂移
        val speedMultiplier = if (isPlaying) (1.0f + freq0 * 0.8f) else 0.2f
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

            // 高精度抗溢出伪随机哈希
            float hash21(vec2 p) {
                p = fract(p * vec2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            // 多层程序化星空：生成细锐璀璨星点与呼吸微光
            float getStars(vec2 uv, float scale, float density, float speed) {
                vec2 p = uv * scale;
                vec2 id = floor(p);
                vec2 f = fract(p) - 0.5;
                float h = hash21(id);
                if (h < density) return 0.0;
                
                vec2 offset = vec2(hash21(id + 1.1), hash21(id + 2.3)) - 0.5;
                float d = length(f - offset * 0.7);
                
                // 极细星核与淡晕
                float star = smoothstep(0.08, 0.015, d) * 1.5 + exp(-d * 14.0) * 0.25;
                float twinkle = sin(uTime * speed + h * 6.28318) * 0.35 + 0.65;
                return star * twinkle * ((h - density) / (1.0 - density));
            }

            // 第一层：核心主星云分形场 (Kaliset 高精度反演分形)
            float field(vec3 p, float s) {
                float strength = 7.0 + 0.03 * log(1.0e-6 + fract(sin(uTime * 0.2) * 4373.11));
                float accum = s * 0.25;
                float prev = 0.0;
                float tw = 0.0;
                for (int i = 0; i < 24; ++i) {
                    float mag = dot(p, p);
                    p = abs(p) / mag + vec3(-0.5, -0.4, -1.5);
                    float w = exp(-float(i) * 0.142857);
                    accum += w * exp(-strength * pow(abs(mag - prev), 2.2));
                    tw += w;
                    prev = mag;
                }
                return max(0.0, 5.0 * accum / tw - 0.7);
            }

            // 第二层：深邃远景视差分形场
            float field2(vec3 p, float s) {
                float strength = 7.0 + 0.03 * log(1.0e-6 + fract(sin(uTime * 0.15) * 4373.11));
                float accum = s * 0.25;
                float prev = 0.0;
                float tw = 0.0;
                for (int i = 0; i < 16; ++i) {
                    float mag = dot(p, p);
                    p = abs(p) / mag + vec3(-0.5, -0.4, -1.5);
                    float w = exp(-float(i) * 0.142857);
                    accum += w * exp(-strength * pow(abs(mag - prev), 2.2));
                    tw += w;
                    prev = mag;
                }
                return max(0.0, 5.0 * accum / tw - 0.7);
            }

            // 余弦全彩宇宙调色板生成器 (Cosine Color Palette)
            vec3 palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
                return a + b * cos(6.28318 * (c * t + d));
            }

            void main() {
                // 使用 max 归一化视口坐标：保持等比无畸变
                vec2 uvs = (gl_FragCoord.xy - 0.5 * uResolution.xy) / max(uResolution.x, uResolution.y);
                vec2 uv = gl_FragCoord.xy / uResolution.xy;

                // 慢速时间流，用于宇宙星云色彩平滑随机演变
                float slowTime = uTime * 0.025;

                // 1. 纯净幽邃深海蓝底色（右侧加强深海靛蓝层次，消除死黑）
                vec3 deepSky = mix(
                    vec3(0.010, 0.025, 0.065), 
                    vec3(0.018, 0.045, 0.115), 
                    clamp(uv.y * 0.6 + uv.x * 0.5, 0.0, 1.0)
                );
                // 随时间微弱演变深空冷暖基调
                deepSky += 0.012 * cos(slowTime * 0.5 + vec3(0.0, 1.2, 2.4));

                // 2. 主星云采样（视差漫游）
                vec3 p = vec3(uvs * 0.36, 0.0) + vec3(0.95, -1.28, 0.0);
                p += 0.08 * vec3(sin(uTime * 0.035), sin(uTime * 0.05), sin(uTime * 0.012));
                
                // 音频能量驱动呼吸脉动
                float audioBoost = 0.95 + uFreqs.x * 0.28;
                float t = field(p, uFreqs.z * 0.3 + 0.12) * audioBoost;

                // 3. 背景第二层星云采样（提供右侧及远景的浩瀚纵深）
                float zoom = 3.6 + sin(uTime * 0.06) * 0.2;
                vec3 p2 = vec3(uvs / zoom, 1.2) + vec3(1.85, -1.25, -0.8);
                p2 += 0.10 * vec3(sin(uTime * 0.03), sin(uTime * 0.04), sin(uTime * 0.01));
                float t2 = field2(p2, uFreqs.w * 0.3 + 0.10);

                // 4. 平滑的构图密度平衡（左侧星云浓郁高光，右侧自然过渡为若隐若现的深蓝/青冷薄雾）
                float leftDense = smoothstep(0.7, -0.4, uvs.x + uvs.y * 0.25);
                float tNeb = max(0.0, t - 0.32) * mix(0.45, 1.0, leftDense);
                float tNeb2 = tNeb * tNeb;
                float tNeb3 = tNeb2 * tNeb;

                // 5. 随时间平滑随机演变的程序化动态调色板
                // 外围薄雾色（冷色调：绿松石/深青/靛蓝/紫罗兰）
                vec3 colDust = palette(slowTime,
                    vec3(0.025, 0.28, 0.40),
                    vec3(0.020, 0.18, 0.22),
                    vec3(1.0, 1.0, 1.0),
                    vec3(0.18, 0.48, 0.72)
                );

                // 中层主体星云色（饱满鲜亮：翡翠绿/青碧/金珀/洋红）
                vec3 colBody = palette(slowTime,
                    vec3(0.12, 0.85, 0.50),
                    vec3(0.12, 0.45, 0.35),
                    vec3(1.0, 1.0, 1.0),
                    vec3(0.12, 0.42, 0.68)
                );

                // 核心亮斑与高光分形脉络（极光荧光色：嫩黄绿/金白/冰青/粉紫）
                vec3 colCore = palette(slowTime,
                    vec3(0.72, 1.00, 0.36),
                    vec3(0.22, 0.32, 0.30),
                    vec3(1.0, 1.0, 1.0),
                    vec3(0.08, 0.38, 0.62)
                );

                vec3 colPeak = vec3(0.96, 1.00, 0.90);

                // 主星云颜色合成
                vec3 nebulaCol = colDust * (tNeb * 0.5) 
                               + colBody * (tNeb2 * 0.95) 
                               + colCore * (tNeb3 * 0.85) 
                               + colPeak * (pow(max(0.0, tNeb - 0.85), 2.2) * 1.5);

                // 6. 右侧与远景第二层深空星云（呈现深海蓝、冷靛与青松石微光，给右半屏注入丰富色彩）
                float t2Neb = max(0.0, t2 - 0.22);
                vec3 colBgDust = palette(slowTime + 0.15,
                    vec3(0.018, 0.18, 0.32),
                    vec3(0.015, 0.12, 0.18),
                    vec3(1.0, 1.0, 1.0),
                    vec3(0.25, 0.55, 0.85)
                );
                vec3 bgNebula = colBgDust * (t2Neb * 0.75) + colDust * (t2Neb * t2Neb * 0.45);

                // 7. 璀璨星空繁星系统（多层细锐钻石星尘，在右侧深蓝深空中格外明亮醒目）
                float starsFine = getStars(uvs, 240.0, 0.925, 2.0);
                float starsBright = getStars(uvs + vec2(0.42, 0.58), 85.0, 0.974, 1.2);
                vec3 starsColor = vec3(0.85, 0.94, 1.0) * starsFine * 1.0 + vec3(0.95, 0.98, 1.0) * starsBright * 1.6;
                starsColor *= (1.0 + uFreqs.w * 0.45);

                // 8. 多层全彩合成
                vec3 finalColor = deepSky + bgNebula + nebulaCol + starsColor;

                // 9. 主题色高级微调（柔和映射）
                vec3 themeTone = mix(uColor1, uColor2, clamp(tNeb * 0.7, 0.0, 1.0));
                finalColor = mix(finalColor, finalColor * themeTone * 1.2, 0.08);

                // 10. 电影级色调映射（防过曝，保留纯净高动态范围）
                finalColor = 1.0 - exp(-finalColor * 1.05);

                // 11. 柔和电影感暗角
                float vignette = uv.x * uv.y * (1.0 - uv.x) * (1.0 - uv.y);
                vignette = clamp(pow(16.0 * vignette, 0.25), 0.0, 1.0);
                finalColor *= vignette;

                gl_FragColor = vec4(finalColor, 1.0);
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
