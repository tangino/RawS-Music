package com.rawsmusic.module.player.dsp

import android.util.Log

class NativeDSPEngine {
    private val dspLock = Any()

    private inline fun withDsp(crossinline block: (Long) -> Unit) {
        synchronized(dspLock) {
            val handle = nativeHandle
            if (handle == 0L) return
            try {
                block(handle)
            } catch (t: Throwable) {
                Log.e(TAG, "DSP call failed", t)
            }
        }
    }

    private inline fun <T> withDsp(default: T, crossinline block: (Long) -> T): T {
        return synchronized(dspLock) {
            val handle = nativeHandle
            if (handle == 0L) return default
            try {
                block(handle)
            } catch (t: Throwable) {
                Log.e(TAG, "DSP call failed", t)
                default
            }
        }
    }

    fun setReverbEnabled(enabled: Boolean) = withDsp { nativeSetReverbEnabled(it, enabled) }
    fun setReverbParams(params: ReverbSettings) = withDsp { nativeSetReverbParams(it, params.sanitized().nativeValues()) }
    fun resetReverb() = withDsp { nativeResetReverb(it) }

    private external fun nativeSetReverbEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetReverbParams(handle: Long, params: FloatArray)
    private external fun nativeResetReverb(handle: Long)
    @Volatile
    private var nativeHandle: Long = 0
    var sampleRate: Int = 44100
        private set

    companion object {
        private const val TAG = "NativeDSPEngine"
        private const val MAX_PEQ_FILTERS = 40
        init {
            try {
                System.loadLibrary("rawscoreservice")
                Log.d(TAG, "rawsmusic_dsp library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load rawsmusic_dsp", e)
            }
        }
    }

    fun init(sampleRate: Int, channels: Int) {
        synchronized(dspLock) {
            if (nativeHandle != 0L) {
                val h = nativeHandle
                nativeHandle = 0L
                try {
                    nativeRelease(h)
                } catch (_: Throwable) {}
            }
            this.sampleRate = sampleRate
            nativeHandle = try {
                nativeCreate(sampleRate, channels)
            } catch (t: Throwable) {
                Log.e(TAG, "nativeCreate failed", t)
                0L
            }
            Log.d(TAG, "init: sampleRate=$sampleRate, channels=$channels, handle=$nativeHandle")
        }
    }

    fun setStereoWiden(factor: Float) {
        val safeFactor = if (factor.isFinite()) factor.coerceIn(0f, 1f) else 0f
        withDsp { nativeSetStereoWiden(it, safeFactor) }
    }

    fun setRealtimeStemEnabled(enabled: Boolean) {
        withDsp { nativeSetRealtimeStemEnabled(it, enabled) }
    }

    fun setRealtimeStemMode(mode: Int) {
        withDsp { nativeSetRealtimeStemMode(it, mode.coerceIn(0, 1)) }
    }

    fun setRealtimeStemStrength(strength: Float) {
        withDsp { nativeSetRealtimeStemStrength(it, strength.coerceIn(0f, 1f)) }
    }

    fun setAndroidDvc(enabled: Boolean, gain: Float, noDvcHeadroomDb: Float) {
        withDsp {
            nativeSetAndroidDvc(
                it,
                enabled,
                gain.coerceIn(0f, 1f),
                noDvcHeadroomDb.coerceIn(-24f, 0f),
            )
        }
    }

    /** RawSMusic-owned binaural renderer for regular Android stereo output. */
    fun setAndroidBinauralSpatialEnabled(enabled: Boolean) {
        withDsp { nativeSetAndroidBinauralSpatialEnabled(it, enabled) }
    }

    fun setAndroidBinauralSpatialParameters(intensityPercent: Float, roomPercent: Float) {
        withDsp {
            nativeSetAndroidBinauralSpatialParameters(
                it,
                intensityPercent.coerceIn(0f, 100f),
                roomPercent.coerceIn(0f, 100f)
            )
        }
    }

    fun setAndroidBinauralSpatialAdvancedParameters(
        brirEnabled: Boolean,
        separationPercent: Float,
        headSizeCentimeters: Float,
        pinnaDetailPercent: Float
    ) {
        withDsp {
            nativeSetAndroidBinauralSpatialAdvancedParameters(
                it,
                brirEnabled,
                separationPercent.coerceIn(0f, 100f),
                headSizeCentimeters.coerceIn(48f, 68f),
                pinnaDetailPercent.coerceIn(0f, 100f)
            )
        }
    }

    fun setAndroidBinauralHeadPose(
        enabled: Boolean,
        quaternionX: Float,
        quaternionY: Float,
        quaternionZ: Float,
        quaternionW: Float
    ) {
        withDsp {
            nativeSetAndroidBinauralHeadPose(
                it,
                enabled,
                quaternionX,
                quaternionY,
                quaternionZ,
                quaternionW
            )
        }
    }

    fun process(buffer: ShortArray, length: Int, channels: Int): Int {
        return withDsp(-1) { nativeProcess(it, buffer, length, channels) }
    }

    fun processFloat(buffer: FloatArray, length: Int, channels: Int): Int {
        return withDsp(-1) { nativeProcessFloat(it, buffer, length, channels) }
    }

    fun processDouble(buffer: DoubleArray, length: Int, channels: Int): Int {
        return withDsp(-1) { nativeProcessDouble(it, buffer, length, channels) }
    }

    fun setDoublePrecisionProcessing(enabled: Boolean) {
        withDsp { nativeSetDoublePrecisionProcessing(it, enabled) }
    }

    fun hasActiveEffects(): Boolean {
        return withDsp(false) { nativeHasActiveEffects(it) }
    }

    // ==========================================
    // 参量均衡器 API
    // ==========================================

    /** 启用/禁用参量均衡器 */
    fun setPEQEnabled(enabled: Boolean) {
        withDsp { nativeSetPEQEnabled(it, enabled) }
    }

    /** Enable the independent graphic-EQ bank. Native enforces PEQ/GEQ exclusivity. */
    fun setGraphicEQEnabled(enabled: Boolean) {
        withDsp { nativeSetGraphicEQEnabled(it, enabled) }
    }

    fun setGraphicEQFilter(
        index: Int,
        frequency: Float,
        gainDB: Float,
        Q: Float,
        enabled: Boolean
    ) {
        if (index !in 0 until MAX_PEQ_FILTERS) return
        withDsp {
            nativeSetGraphicEQFilter(
                it,
                index,
                frequency.coerceIn(20f, 20000f),
                gainDB.coerceIn(-12f, 12f),
                Q.coerceIn(0.05f, 24f),
                enabled
            )
        }
    }

    fun clearGraphicEQFilters() {
        withDsp { nativeClearGraphicEQFilters(it) }
    }

    fun setGraphicEQPreamp(gainDB: Float) {
        withDsp { nativeSetGraphicEQPreamp(it, gainDB.coerceIn(-12f, 12f)) }
    }

    fun setExperimentalGainEnabled(enabled: Boolean) {
        withDsp { nativeSetExperimentalGainEnabled(it, enabled) }
    }

    fun setExperimentalGainDb(gainDb: Float) {
        withDsp { nativeSetExperimentalGainDb(it, gainDb.coerceIn(0f, 30f)) }
    }

    fun setLoudnessBalanceEnabled(enabled: Boolean) {
        withDsp { nativeSetLoudnessBalanceEnabled(it, enabled) }
    }

    fun setLoudnessBalanceParameters(loudnessPercent: Float, balance: Float) {
        withDsp {
            nativeSetLoudnessBalanceParameters(
                it,
                loudnessPercent.coerceIn(0f, 100f),
                balance.coerceIn(-1f, 1f)
            )
        }
    }

    fun setMonoBassEnabled(enabled: Boolean) {
        withDsp { nativeSetMonoBassEnabled(it, enabled) }
    }

    fun setMonoBassParameters(crossoverHz: Float, amountPercent: Float) {
        withDsp {
            nativeSetMonoBassParameters(
                it,
                crossoverHz.coerceIn(60f, 300f),
                amountPercent.coerceIn(0f, 100f)
            )
        }
    }

    fun setMonoOutputEnabled(enabled: Boolean) {
        withDsp { nativeSetMonoOutputEnabled(it, enabled) }
    }

    fun setDynamicEqEnabled(enabled: Boolean) {
        withDsp { nativeSetDynamicEqEnabled(it, enabled) }
    }

    fun setDynamicEqParameters(
        intensityPercent: Float,
        deEsserPercent: Float,
        deEsserFrequencyHz: Float
    ) {
        withDsp {
            nativeSetDynamicEqParameters(
                it,
                intensityPercent.coerceIn(0f, 100f),
                deEsserPercent.coerceIn(0f, 100f),
                deEsserFrequencyHz.coerceIn(4000f, 10000f)
            )
        }
    }

    fun setMoogLadderEnabled(enabled: Boolean) {
        withDsp { nativeSetMoogLadderEnabled(it, enabled) }
    }

    fun setMoogLadderParameters(
        mode: Int,
        cutoffHz: Float,
        resonancePercent: Float,
        driveDb: Float,
        mixPercent: Float
    ) {
        withDsp {
            nativeSetMoogLadderParameters(
                it,
                mode.coerceIn(0, 4),
                cutoffHz.coerceIn(20f, 20000f),
                resonancePercent.coerceIn(0f, 100f),
                driveDb.coerceIn(0f, 18f),
                mixPercent.coerceIn(0f, 100f)
            )
        }
    }

    /**
     * 设置PEQ滤波器
     * @param index 滤波器索引 (0-39)
     * @param type 滤波器类型 (0=Peak, 1=LowShelf, 2=HighShelf, 3=LowPass, 4=HighPass, 5=BandPass, 6=Notch, 7=PeakAnalog)
     * @param frequency 中心/截止频率 (Hz)
     * @param gainDB 增益 (dB)，建议 -24..24
     * @param Q 品质因数，建议 0.05..24
     * @param enabled 是否启用
     */
    fun setPEQFilter(
        index: Int,
        type: Int,
        frequency: Float,
        gainDB: Float,
        Q: Float,
        enabled: Boolean
    ) {
        if (index !in 0 until MAX_PEQ_FILTERS) return

        val safeType = type.coerceIn(0, 7)
        val safeFrequency = if (frequency.isFinite()) frequency.coerceIn(20f, 20000f) else 1000f
        val safeGainDB = if (gainDB.isFinite()) gainDB.coerceIn(-24f, 24f) else 0f
        val safeQ = if (Q.isFinite()) Q.coerceIn(0.05f, 24f) else 1.414f

        withDsp {
            nativeSetPEQFilter(
                it,
                index,
                safeType,
                safeFrequency,
                safeGainDB,
                safeQ,
                enabled
            )
        }
    }

    /** 移除指定索引的滤波器 */
    fun removePEQFilter(index: Int) {
        if (index !in 0 until MAX_PEQ_FILTERS) return
        withDsp { nativeRemovePEQFilter(it, index) }
    }

    /** 清除所有滤波器 */
    fun clearPEQFilters() {
        withDsp { nativeClearPEQFilters(it) }
    }

    /** 设置实际 DSP 前级增益。负向扩展范围用于 PEQ 自动余量补偿。 */
    fun setPreamp(gainDB: Float) {
        val safeGain = if (gainDB.isFinite()) gainDB.coerceIn(-96f, 12f) else 0f
        withDsp { nativeSetPreamp(it, safeGain) }
    }

    // ==========================================
    // 互馈 (Crossfeed) API
    // ==========================================

    /** 启用/禁用互馈 */
    fun setCrossfeedEnabled(enabled: Boolean) {
        withDsp { nativeSetCrossfeedEnabled(it, enabled) }
    }

    /**
     * 设置互馈参数
     * @param lowCutFreq 高通截止频率 (Hz)，50-1000
     * @param highCutFreq 低通截止频率 (Hz)，500-8000
     * @param attenuationDB 衰减量 (dB)，0-15
     */
    fun setCrossfeedParams(lowCutFreq: Float, highCutFreq: Float, attenuationDB: Float) {
        withDsp { nativeSetCrossfeedParams(it, lowCutFreq, highCutFreq, attenuationDB) }
    }

    /**
     * 计算频率响应曲线
     * @param frequencies 输入频率数组 (Hz)
     * @param magnitudes 输出增益数组 (dB)
     * @param numPoints 采样点数
     */
    fun calcPEQResponse(frequencies: FloatArray, magnitudes: FloatArray, numPoints: Int) {
        val safePoints = numPoints
            .coerceAtMost(frequencies.size)
            .coerceAtMost(magnitudes.size)
            .coerceAtLeast(0)

        if (safePoints <= 0) return

        withDsp { nativeCalcPEQResponse(it, frequencies, magnitudes, safePoints) }
    }

    fun release() {
        synchronized(dspLock) {
            if (nativeHandle != 0L) {
                val h = nativeHandle
                nativeHandle = 0L
                try {
                    nativeRelease(h)
                } catch (_: Throwable) {}
            }
        }
    }

    fun isInitialized(): Boolean = nativeHandle != 0L

    internal fun nativeHandleForBridge(): Long = nativeHandle

    private external fun nativeCreate(sampleRate: Int, channels: Int): Long
    private external fun nativeSetRealtimeStemEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetRealtimeStemMode(handle: Long, mode: Int)
    private external fun nativeSetRealtimeStemStrength(handle: Long, strength: Float)
    private external fun nativeSetAndroidDvc(
        handle: Long,
        enabled: Boolean,
        gain: Float,
        noDvcHeadroomDb: Float,
    )
    private external fun nativeSetStereoWiden(handle: Long, factor: Float)
    private external fun nativeSetAndroidBinauralSpatialEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetAndroidBinauralSpatialParameters(
        handle: Long,
        intensityPercent: Float,
        roomPercent: Float
    )

    private external fun nativeSetAndroidBinauralSpatialAdvancedParameters(
        handle: Long,
        brirEnabled: Boolean,
        separationPercent: Float,
        headSizeCentimeters: Float,
        pinnaDetailPercent: Float
    )
    private external fun nativeSetAndroidBinauralHeadPose(
        handle: Long,
        enabled: Boolean,
        quaternionX: Float,
        quaternionY: Float,
        quaternionZ: Float,
        quaternionW: Float
    )
    private external fun nativeProcess(handle: Long, buffer: ShortArray, length: Int, channels: Int): Int
    private external fun nativeProcessFloat(handle: Long, buffer: FloatArray, length: Int, channels: Int): Int
    private external fun nativeProcessDouble(handle: Long, buffer: DoubleArray, length: Int, channels: Int): Int
    private external fun nativeSetDoublePrecisionProcessing(handle: Long, enabled: Boolean)
    private external fun nativeHasActiveEffects(handle: Long): Boolean
    private external fun nativeRelease(handle: Long)

    // PEQ JNI 方法
    private external fun nativeSetPEQEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetGraphicEQEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetGraphicEQFilter(
        handle: Long,
        index: Int,
        frequency: Float,
        gainDB: Float,
        Q: Float,
        enabled: Boolean
    )
    private external fun nativeClearGraphicEQFilters(handle: Long)
    private external fun nativeSetGraphicEQPreamp(handle: Long, gainDB: Float)
    private external fun nativeSetExperimentalGainEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetExperimentalGainDb(handle: Long, gainDb: Float)
    private external fun nativeSetLoudnessBalanceEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetLoudnessBalanceParameters(handle: Long, loudnessPercent: Float, balance: Float)
    private external fun nativeSetMonoBassEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetMonoBassParameters(handle: Long, crossoverHz: Float, amountPercent: Float)
    private external fun nativeSetMonoOutputEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetDynamicEqEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetDynamicEqParameters(
        handle: Long,
        intensityPercent: Float,
        deEsserPercent: Float,
        deEsserFrequencyHz: Float
    )
    private external fun nativeSetMoogLadderEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetMoogLadderParameters(
        handle: Long,
        mode: Int,
        cutoffHz: Float,
        resonancePercent: Float,
        driveDb: Float,
        mixPercent: Float
    )
    private external fun nativeSetPEQFilter(handle: Long, index: Int, type: Int, frequency: Float, gainDB: Float, Q: Float, enabled: Boolean)
    private external fun nativeRemovePEQFilter(handle: Long, index: Int)
    private external fun nativeClearPEQFilters(handle: Long)
    private external fun nativeCalcPEQResponse(handle: Long, frequencies: FloatArray, magnitudes: FloatArray, numPoints: Int)
    private external fun nativeSetPreamp(handle: Long, gainDB: Float)

    // Crossfeed JNI 方法
    private external fun nativeSetCrossfeedEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetCrossfeedParams(handle: Long, lowCutFreq: Float, highCutFreq: Float, attenuationDB: Float)

    // ==========================================
    // 压限器 (Compressor) API
    // ==========================================

    /** 启用/禁用压限器 */
    fun setCompressorEnabled(enabled: Boolean) {
        withDsp { nativeSetCompressorEnabled(it, enabled) }
    }

    /**
     * 获取当前增益衰减量 (GR Meter)
     * @return 增益衰减量 (dB)，正值表示压缩量
     */
    fun getCompressorGR(): Float {
        return withDsp(0.0f) { nativeGetCompressorGR(it) }
    }

    // ==========================================
    // 低音增强 (BassBoost) API
    // ==========================================

    /** 启用/禁用低音增强 */
    fun setBassBoostEnabled(enabled: Boolean) {
        withDsp { nativeSetBassBoostEnabled(it, enabled) }
    }

    /**
     * 设置低音增强参数
     * @param gainDB 增益 (dB)，范围 -12 ~ +12
     * @param frequency 转折频率 (Hz)，范围 50 ~ 500
     */
    fun setBassBoostParams(gainDB: Float, frequency: Float) {
        withDsp { nativeSetBassBoostParams(it, gainDB, frequency) }
    }

    // ==========================================
    // 高音增强 (TrebleBoost) API
    // ==========================================

    /** 启用/禁用高音增强 */
    fun setTrebleBoostEnabled(enabled: Boolean) {
        withDsp { nativeSetTrebleBoostEnabled(it, enabled) }
    }

    /**
     * 设置高音增强参数
     * @param gainDB 增益 (dB)，范围 -12 ~ +12
     * @param frequency 转折频率 (Hz)，范围 2000 ~ 16000
     */
    fun setTrebleBoostParams(gainDB: Float, frequency: Float) {
        withDsp { nativeSetTrebleBoostParams(it, gainDB, frequency) }
    }

    // Compressor JNI 方法
    private external fun nativeSetCompressorEnabled(handle: Long, enabled: Boolean)
    private external fun nativeGetCompressorGR(handle: Long): Float

    // BassBoost JNI 方法
    private external fun nativeSetBassBoostEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetBassBoostParams(handle: Long, gainDB: Float, frequency: Float)

    // TrebleBoost JNI 方法
    private external fun nativeSetTrebleBoostEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetTrebleBoostParams(handle: Long, gainDB: Float, frequency: Float)



    // ==========================================
    // 扬声器外放 API（统一入口）
    // ==========================================

    /**
     * 启用或禁用扬声器外放处理。
     *
     * 这是当前 C++ 实现对应的唯一真实 Native 开关入口。旧控制器使用的
     * setSpeakerOutputElasticityEnabled() 会在 Kotlin 层委托到这里，不再声明第二套 JNI。
     */
    fun setSpeakerOutputEnabled(enabled: Boolean) {
        withDsp { nativeSetSpeakerOutputEnabled(it, enabled) }
    }

    /** 选择扬声器外放模式：0=弹性，1=澎湃，2=宽广。 */
    fun setSpeakerOutputMode(mode: SpeakerOutputEffectController.Mode) {
        withDsp { nativeSetSpeakerOutputMode(it, mode.nativeCode) }
    }

    /**
     * 提交“弹性”模式的完整参数快照。
     *
     * 参数模型与 SpeakerOutputEffectController 保持一致；校验会在 Kotlin 和 C++
     * 两层分别执行。音频线程只在处理块边界应用新参数。
     */
    fun setSpeakerElasticityParameters(
        parameters: SpeakerOutputEffectController.ElasticityParameters
    ) {
        val safe = parameters.sanitized()
        withDsp {
            nativeSetSpeakerElasticityParameters(
                it,
                safe.strengthPercent,
                safe.detectorLowHz,
                safe.detectorHighHz,
                safe.fastAttackMs,
                safe.fastReleaseMs,
                safe.slowAttackMs,
                safe.slowReleaseMs,
                safe.gainAttackMs,
                safe.gainReleaseMs,
                safe.maxBoostDb,
                safe.noiseGateDb,
                safe.headroomCeiling,
                safe.peakReleaseMs,
                safe.sensitivity
            )
        }
    }

    /** 提交“澎湃”模式的完整参数快照。 */
    fun setSpeakerPowerfulParameters(
        parameters: SpeakerOutputEffectController.PowerfulParameters
    ) {
        val safe = parameters.sanitized()
        withDsp {
            nativeSetSpeakerPowerfulParameters(
                it,
                safe.strengthPercent,
                safe.bodyLowHz,
                safe.bodyHighHz,
                safe.bassBoostDb,
                safe.harmonicPercent,
                safe.compressorThresholdDb,
                safe.compressorRatio,
                safe.compressorAttackMs,
                safe.compressorReleaseMs,
                safe.parallelMixPercent,
                safe.makeupGainDb,
                safe.presenceBoostDb,
                safe.headroomCeiling
            )
        }
    }

    /** 提交“宽广”模式的完整参数快照。 */
    fun setSpeakerWideParameters(
        parameters: SpeakerOutputEffectController.WideParameters
    ) {
        val safe = parameters.sanitized()
        withDsp {
            nativeSetSpeakerWideParameters(
                it,
                safe.strengthPercent,
                safe.crossoverHz,
                safe.widthDb,
                safe.decorrelationPercent,
                safe.bassCenterPercent,
                safe.centerProtectionPercent,
                safe.headroomCeiling
            )
        }
    }

    // ------------------------------------------------------------------
    // 旧版控制器兼容层
    // ------------------------------------------------------------------

    /**
     * 兼容 SpeakerOutputElasticityController 的旧方法名。
     *
     * 仅做 Kotlin 委托，不对应独立 JNI，避免出现 UnsatisfiedLinkError。
     */
    fun setSpeakerOutputElasticityEnabled(enabled: Boolean) {
        setSpeakerOutputEnabled(enabled)
    }

    /**
     * 将旧控制器的参数格式转换为当前统一参数模型。
     *
     * sensitivityPercent 使用指数映射：0%=0.25、约82%=1.92、100%=3.0；
     * peakCeilingDb 从 dBFS 转换为线性峰值；旧接口没有 peakReleaseMs，使用70ms。
     */
    fun setSpeakerOutputElasticityParams(
        strengthPercent: Float,
        detectorLowHz: Float,
        detectorHighHz: Float,
        sensitivityPercent: Float,
        gateThresholdDb: Float,
        fastAttackMs: Float,
        fastReleaseMs: Float,
        slowAttackMs: Float,
        slowReleaseMs: Float,
        gainAttackMs: Float,
        gainReleaseMs: Float,
        maxBoostDb: Float,
        peakCeilingDb: Float
    ) {
        val safeSensitivityPercent = finiteOr(sensitivityPercent, 82f).coerceIn(0f, 100f)
        val normalizedSensitivity = safeSensitivityPercent / 100f
        val mappedSensitivity = (
            0.25 * Math.pow(12.0, normalizedSensitivity.toDouble())
        ).toFloat().coerceIn(0.25f, 3f)

        val safePeakCeilingDb = finiteOr(peakCeilingDb, -0.2f).coerceIn(-6f, -0.1f)
        val linearPeakCeiling = Math.pow(
            10.0,
            safePeakCeilingDb.toDouble() / 20.0
        ).toFloat().coerceIn(0.70f, 0.995f)

        setSpeakerElasticityParameters(
            SpeakerOutputEffectController.ElasticityParameters(
                strengthPercent = strengthPercent,
                detectorLowHz = detectorLowHz,
                detectorHighHz = detectorHighHz,
                fastAttackMs = fastAttackMs,
                fastReleaseMs = fastReleaseMs,
                slowAttackMs = slowAttackMs,
                slowReleaseMs = slowReleaseMs,
                gainAttackMs = gainAttackMs,
                gainReleaseMs = gainReleaseMs,
                maxBoostDb = maxBoostDb,
                noiseGateDb = gateThresholdDb,
                headroomCeiling = linearPeakCeiling,
                peakReleaseMs = 70f,
                sensitivity = mappedSensitivity
            )
        )
    }

    private fun finiteOr(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    // 与 speaker_output_effect.cpp 保持一一对应的 JNI 声明。
    private external fun nativeSetSpeakerOutputEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetSpeakerOutputMode(handle: Long, mode: Int)
    private external fun nativeSetSpeakerElasticityParameters(
        handle: Long,
        strengthPercent: Float,
        detectorLowHz: Float,
        detectorHighHz: Float,
        fastAttackMs: Float,
        fastReleaseMs: Float,
        slowAttackMs: Float,
        slowReleaseMs: Float,
        gainAttackMs: Float,
        gainReleaseMs: Float,
        maxBoostDb: Float,
        noiseGateDb: Float,
        headroomCeiling: Float,
        peakReleaseMs: Float,
        sensitivity: Float
    )
    private external fun nativeSetSpeakerPowerfulParameters(
        handle: Long,
        strengthPercent: Float,
        bodyLowHz: Float,
        bodyHighHz: Float,
        bassBoostDb: Float,
        harmonicPercent: Float,
        compressorThresholdDb: Float,
        compressorRatio: Float,
        compressorAttackMs: Float,
        compressorReleaseMs: Float,
        parallelMixPercent: Float,
        makeupGainDb: Float,
        presenceBoostDb: Float,
        headroomCeiling: Float
    )

    private external fun nativeSetSpeakerWideParameters(
        handle: Long,
        strengthPercent: Float,
        crossoverHz: Float,
        widthDb: Float,
        decorrelationPercent: Float,
        bassCenterPercent: Float,
        centerProtectionPercent: Float,
        headroomCeiling: Float
    )

    // ==========================================
    // 360° 环绕音 (Surround360) API
    // ==========================================

    /** 启用/禁用 360° 环绕音 */
    fun setSurround360Enabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetSurround360Enabled(nativeHandle, enabled)
    }

    /**
     * 设置 360° 环绕音参数
     * @param intensity 效果强度 (0~100)
     * @param azimuthDeg 方位角 (0~360°), 0=前, 90=右, 180=后, 270=左
     */
    fun setSurround360Params(intensity: Float, azimuthDeg: Float) {
        if (nativeHandle == 0L) return
        nativeSetSurround360Params(nativeHandle, intensity, azimuthDeg)
    }

    // ==========================================
    // 360° 全景音 (Panoramic360) API
    // ==========================================

    /** 启用/禁用 360° 全景音 */
    fun setPanoramic360Enabled(enabled: Boolean) {
        if (nativeHandle == 0L) return
        nativeSetPanoramic360Enabled(nativeHandle, enabled)
    }

    /**
     * 设置 360° 全景音参数
     * @param intensity 效果强度 (0~100)
     * @param azimuthDeg 方位角 (0~360°), 0=前, 90=右, 180=后, 270=左
     * @param elevationDeg 仰角 (-90~+90°), 正=上方, 负=下方
     */
    fun setPanoramic360Params(intensity: Float, azimuthDeg: Float, elevationDeg: Float) {
        if (nativeHandle == 0L) return
        nativeSetPanoramic360Params(nativeHandle, intensity, azimuthDeg, elevationDeg)
    }

    // Surround360 JNI 方法
    private external fun nativeSetSurround360Enabled(handle: Long, enabled: Boolean)
    private external fun nativeSetSurround360Params(handle: Long, intensity: Float, azimuthDeg: Float)

    // Panoramic360 JNI 方法
    private external fun nativeSetPanoramic360Enabled(handle: Long, enabled: Boolean)
    private external fun nativeSetPanoramic360Params(handle: Long, intensity: Float, azimuthDeg: Float, elevationDeg: Float)
}
