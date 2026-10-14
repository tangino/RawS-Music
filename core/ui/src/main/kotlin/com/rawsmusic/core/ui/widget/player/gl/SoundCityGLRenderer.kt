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
 * 基于 Inigo Quilez 经典名作移植的 3D 音乐都市 (Sound City) 动态可视化渲染器。
 * 核心技术：体素网格步进 (2D Grid Traversal)、圆角盒子距离场 (udBox)、真实物理光照与软阴影、4 频段音频驱动摩天大楼高度起伏。
 */
class SoundCityGLRenderer : IAudioRaymarchingRenderer {

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
    private var freq0 = 0.0f
    private var freq1 = 0.0f
    private var freq2 = 0.0f
    private var freq3 = 0.0f

    // 配色方案（RGB）
    @Volatile override var color1R: Float = 0.0f
    @Volatile override var color1G: Float = 0.85f
    @Volatile override var color1B: Float = 1.0f

    @Volatile override var color2R: Float = 0.95f
    @Volatile override var color2G: Float = 0.35f
    @Volatile override var color2B: Float = 0.15f

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
        val dt = if (lastFrameTime == 0L) 0.016f else ((now - lastFrameTime) / 1000.0f).coerceIn(0.001f, 0.08f)
        lastFrameTime = now

        // 4 频段音频能量非线性连续物理阻尼平滑 (Attack 迅捷有力，Decay 柔和缓降，彻底消除跳动抽搐)
        val attackRate = 18.0f // 上冲响应速率 (响应节奏鼓点，零顿挫)
        val decayRate = 7.2f   // 下落衰减速率 (平滑物理缓降，防瞬间塌陷抽搐)

        fun smoothFreq(current: Float, target: Float): Float {
            val rate = if (target > current) attackRate else decayRate
            val factor = (1.0f - kotlin.math.exp(-rate * dt)).coerceIn(0.01f, 1.0f)
            return current + (target - current) * factor
        }

        freq0 = smoothFreq(freq0, freq0Target)
        freq1 = smoothFreq(freq1, freq1Target)
        freq2 = smoothFreq(freq2, freq2Target)
        freq3 = smoothFreq(freq3, freq3Target)

        // 播放状态下以标准时间推进，暂停状态下以微速漫游
        val speedMultiplier = if (isPlaying) 1.0f else 0.18f
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

            // 基础高频哈希
            float hash(float n) { 
                return fract(sin(n) * 43758.5453123); 
            }
            float hash2(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
            }

            float maxcomp(vec3 v) { 
                return max(max(v.x, v.y), v.z); 
            }

            // 2D 连续值噪声，用于逼真的木纹和年轮形变
            float noise2d(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                float a = hash2(i);
                float b = hash2(i + vec2(1.0, 0.0));
                float c = hash2(i + vec2(0.0, 1.0));
                float d = hash2(i + vec2(1.0, 1.0));
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
            }

            // 精确带符号圆角立方体距离场 (Inigo Quilez)
            // b 为半宽 (除去圆角半径 r 后的核心半宽)
            float sdRoundBox(vec3 p, vec3 b, float r) {
                vec3 q = abs(p) - b;
                return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r;
            }

            // 4 频段音频驱动木柱高度映射
            vec3 mapH(vec2 pos) {
                vec2 ipos = floor(pos);
                float id = hash2(ipos);
                
                float f = 0.0;
                f += uFreqs.x * clamp(1.0 - abs(id - 0.20) * 4.0, 0.0, 1.0);
                f += uFreqs.y * clamp(1.0 - abs(id - 0.45) * 4.0, 0.0, 1.0);
                f += uFreqs.z * clamp(1.0 - abs(id - 0.70) * 4.0, 0.0, 1.0);
                f += uFreqs.w * clamp(1.0 - abs(id - 0.90) * 4.0, 0.0, 1.0);

                f = pow(clamp(f * 1.5, 0.0, 1.5), 1.6);
                // 基础柱子高度 + 音乐律动提升高度
                float h = 2.6 * f + 0.35 + 0.3 * hash(ipos.x * 3.1 + ipos.y * 7.7);

                return vec3(h, id, f);
            }

            // 局部精确法线求解
            vec3 calcLocalNormal(vec3 p, vec3 b, float r) {
                vec2 e = vec2(1.0, -1.0) * 0.003;
                return normalize(e.xyy * sdRoundBox(p + e.xyy, b, r) + 
                                 e.yyx * sdRoundBox(p + e.yyx, b, r) + 
                                 e.yxy * sdRoundBox(p + e.yxy, b, r) + 
                                 e.xxx * sdRoundBox(p + e.xxx, b, r));
            }

            // 场景高度包围盒：顶面提升至 6.2，确保光线从上方射下时光线不会被提前截断
            vec2 boundingVolume(vec2 tminmax, vec3 ro, vec3 rd) {
                float bp = 6.2;
                float tp = (bp - ro.y) / rd.y;
                if (tp > 0.0) {
                    if (ro.y > bp) tminmax.x = max(tminmax.x, tp);
                    else          tminmax.y = min(tminmax.y, tp);
                }
                bp = 0.0;
                tp = (bp - ro.y) / rd.y;
                if (tp > 0.0) {
                    if (ro.y > bp) tminmax.y = min(tminmax.y, tp);
                }
                return tminmax;
            }

            // 体素网格穿透步进 (2D Grid Traversal) - 高性能极致优化
            vec4 trace(vec3 ro, vec3 rd, float tmin, float tmax, out vec3 outNormal, out vec3 outHitP, out vec3 outLocalP) {
                ro += tmin * rd;
                vec2 pos = floor(ro.xz);
                vec3 rdi = 1.0 / rd;
                vec3 rda = abs(rdi);
                vec2 rds = sign(rd.xz);
                vec2 dis = (pos - ro.xz + 0.5 + rds * 0.5) * rdi.xz;
                
                vec4 res = vec4(-1.0);
                
                // 木柱尺寸参数：饱满圆润的倒角与紧密排列 (半宽 0.385 + 圆角半径 0.085 = 0.47，格子宽 1.0)
                const float roundR = 0.085;
                const float halfW = 0.385;
                
                for (int i = 0; i < 22; i++) {
                    vec3 cub = mapH(pos);
                    float h = cub.x;

                    vec2 pr = pos + 0.5 - ro.xz;
                    vec2 mini = (pr - 0.5 * rds) * rdi.xz;
                    float s = max(mini.x, mini.y);
                    if ((tmin + s) > tmax) break;
                    
                    float halfH = max(0.02, h * 0.5 - roundR);
                    vec3 ce = vec3(pos.x + 0.5, h * 0.5, pos.y + 0.5);
                    vec3 rb = vec3(halfW, halfH, halfW);
                    vec3 ra = rb + roundR + 0.02;
                    vec3 rc = ro - ce;
                    float tN = maxcomp(-rdi * rc - rda * ra);
                    float tF = maxcomp(-rdi * rc + rda * ra);
                    
                    if (tN < tF) {
                        float st = max(0.0, tN);
                        float d = 1.0;
                        
                        for (int j = 0; j < 14; j++) {
                            d = sdRoundBox(rc + st * rd, rb, roundR);
                            st += d;
                            if (st > tF || d < 0.002) break;
                        }

                        if (d < 0.005 * (1.0 + 0.06 * st)) {
                            vec3 hitLocal = rc + st * rd;
                            outNormal = calcLocalNormal(hitLocal, rb, roundR);
                            outHitP = ro + st * rd;
                            outLocalP = hitLocal;
                            float isTop = step(0.6, outNormal.y);
                            res = vec4(st, cub.y, cub.z, isTop);
                            break;
                        }
                    }

                    vec2 mm = step(dis.xy, dis.yx);
                    dis += mm * rda.xz;
                    pos += mm * rds;
                }

                res.x += tmin;
                return res;
            }

            // 投射高品质连续平滑软阴影 (SDF Raymarched Soft Shadows with Adaptive Marching)
            float calcShadow(vec3 ro, vec3 rd, float k, vec2 fragCoord) {
                float res = 1.0;
                // 引入微小的亚像素随机抖动，打破规则采样步长条纹
                float t = 0.08 + 0.04 * hash2(fragCoord);
                
                const float roundR = 0.085;
                const float halfW = 0.385;

                for (int i = 0; i < 14; i++) {
                    vec3 hp = ro + t * rd;
                    if (hp.y > 6.0 || t > 18.0) break;
                    
                    vec2 gpos = floor(hp.xz);
                    vec3 cub = mapH(gpos);
                    float h = cub.x;
                    
                    float halfH = max(0.02, h * 0.5 - roundR);
                    vec3 center = vec3(gpos.x + 0.5, h * 0.5, gpos.y + 0.5);
                    vec3 localP = hp - center;
                    vec3 rb = vec3(halfW, halfH, halfW);
                    
                    float d = sdRoundBox(localP, rb, roundR);
                    
                    // 击中实体内部，完全处于实影本影区 (Umbra)
                    if (d < 0.001) {
                        return 0.0;
                    }
                    
                    // 基于 Inigo Quilez 改进型半影 (Penumbra) 连续衰减曲线
                    res = min(res, k * d / t);
                    
                    // 自适应步长：在靠近物体时高精度逼近，在空旷处加速推进
                    t += clamp(d, 0.08, 0.5);
                }
                
                // 柔和 Hermite 曲线平滑过度，消除一切生硬阶梯边缘
                res = clamp(res, 0.0, 1.0);
                return res * res * (3.0 - 2.0 * res);
            }

            // 高级程序化原木木纹材质
            vec3 getWoodMaterial(vec3 worldP, vec3 localP, vec3 nor, float woodId, out float outRoughness) {
                vec3 cTeak = vec3(0.85, 0.52, 0.28);
                vec3 cMahogany = vec3(0.52, 0.18, 0.12);
                vec3 cWalnut = vec3(0.25, 0.15, 0.11);
                vec3 cMaple = vec3(0.92, 0.72, 0.48);
                vec3 cCedar = vec3(0.76, 0.42, 0.22);
                
                vec3 baseWood;
                float typeSelect = fract(woodId * 5.73);
                if (typeSelect < 0.22) {
                    baseWood = cTeak;
                } else if (typeSelect < 0.44) {
                    baseWood = cMahogany;
                } else if (typeSelect < 0.65) {
                    baseWood = cWalnut;
                } else if (typeSelect < 0.85) {
                    baseWood = cMaple;
                } else {
                    baseWood = cCedar;
                }

                // 融入当前主题色调
                vec3 albumTint = mix(uColor1, uColor2, fract(woodId * 2.31));
                baseWood = mix(baseWood, baseWood * albumTint * 1.5, 0.18);

                // 程序化木纹年轮
                vec2 centerOffset = vec2(hash(woodId * 11.3) - 0.5, hash(woodId * 23.7) - 0.5) * 0.8;
                vec2 woodUV = localP.xz - centerOffset;
                
                float ringDist = length(woodUV * vec2(1.2, 0.85)) * 16.0;
                float ringNoise = noise2d(woodUV * 8.0 + worldP.y * 0.15) * 3.5;
                float ring = sin(ringDist + ringNoise);
                
                float fiber = sin((localP.x + localP.z) * 45.0 + sin(localP.y * 4.0) * 2.0) * 0.12;
                float grainFactor = 0.88 + 0.18 * ring + fiber;
                
                float edgeDarken = 1.0 - 0.15 * pow(max(abs(localP.x), abs(localP.z)) / 0.46, 3.0);
                
                vec3 finalWood = baseWood * grainFactor * edgeDarken;
                outRoughness = 0.35 + 0.15 * ring;
                return finalWood;
            }

            // 主太阳光源方向
            const vec3 sunDir = normalize(vec3(0.72, 0.92, -0.65));
            const vec3 sunCol = vec3(1.65, 1.35, 1.05);
            const vec3 skyCol = vec3(0.18, 0.12, 0.09);
            const vec3 bounceCol = vec3(0.12, 0.08, 0.05);

            // 物理渲染着色
            vec3 render(vec3 ro, vec3 rd, vec2 fragCoord) {
                vec3 col = vec3(0.0);
                vec2 tminmax = vec2(0.0, 36.0);
                tminmax = boundingVolume(tminmax, ro, rd);

                vec3 nor = vec3(0.0);
                vec3 hitP = vec3(0.0);
                vec3 localP = vec3(0.0);
                vec4 res = trace(ro, rd, tminmax.x, tminmax.y, nor, hitP, localP);
                
                if (res.y > -0.5) {
                    float t = res.x;
                    vec3 pos = hitP;
                    float woodId = res.y;

                    float roughness = 0.4;
                    vec3 albedo = getWoodMaterial(pos, localP, nor, woodId, roughness);

                    float shadow = calcShadow(pos + nor * 0.025, sunDir, 3.5, fragCoord);

                    float occ = clamp(pos.y / 2.2, 0.18, 1.0);
                    occ *= (0.65 + 0.35 * max(0.0, nor.y));

                    float nDotL = clamp(dot(nor, sunDir), 0.0, 1.0);
                    vec3 directLight = sunCol * (nDotL * shadow);

                    float skyDiff = clamp(0.5 + 0.5 * nor.y, 0.0, 1.0);
                    vec3 ambientLight = skyCol * (skyDiff * occ);

                    float bounceDiff = clamp(-nor.y, 0.0, 1.0);
                    vec3 bounceLight = bounceCol * (bounceDiff * occ);

                    vec3 hal = normalize(sunDir - rd);
                    float nDotH = clamp(dot(nor, hal), 0.0, 1.0);
                    float specPower = mix(32.0, 12.0, roughness);
                    float specIntensity = pow(nDotH, specPower);
                    float fresnel = pow(clamp(1.0 - dot(-rd, nor), 0.0, 1.0), 4.0);
                    vec3 specular = sunCol * specIntensity * (0.35 + 0.65 * fresnel) * shadow * 0.6;

                    vec3 lighting = directLight + ambientLight + bounceLight;
                    col = albedo * lighting + specular;

                    col *= 1.0 - smoothstep(18.0, 34.0, t);
                }

                return col;
            }

            mat3 setLookAt(vec3 ro, vec3 ta, float cr) {
                vec3 cw = normalize(ta - ro);
                vec3 cp = vec3(sin(cr), cos(cr), 0.0);
                vec3 cu = normalize(cross(cw, cp));
                vec3 cv = normalize(cross(cu, cw));
                return mat3(cu, cv, cw);
            }

            void main() {
                vec2 p = (-uResolution.xy + 2.0 * gl_FragCoord.xy) / uResolution.y;
                float time = 4.0 + 0.18 * uTime;

                vec3 ro = vec3(9.2 * cos(0.25 * time), 5.6 + 1.2 * sin(0.12 * time), 9.2 * sin(0.25 * time));
                vec3 ta = vec3(0.0, 1.2, 0.0);
                float roll = 0.08 * sin(0.15 * time);

                mat3 ca = setLookAt(ro, ta, roll);
                vec3 rd = normalize(ca * vec3(p, 1.65));

                vec3 col = render(ro, rd, gl_FragCoord.xy);
                
                col = col / (1.0 + col * 0.6);
                col = pow(col, vec3(0.4545));

                col = pow(col, vec3(0.92, 0.96, 1.02));

                vec2 q = gl_FragCoord.xy / uResolution.xy;
                col *= 0.35 + 0.65 * pow(16.0 * q.x * q.y * (1.0 - q.x) * (1.0 - q.y), 0.15);

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
