package com.rawsmusic.module.data.source.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.rawsmusic.core.common.utils.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 外部 32-bit Float PCM DSP 桥接契约：由 `:module:player` 注入，
 * 直接将 Media3 解码出的 PCM 升格为高精度浮点送入 RawS 原生 `NativeDSPEngine` 执行单次处理，
 * 避开重复叠跑 DSP 与位深标志错配导致的失真。
 */
interface ExternalPcmDspHook {
    fun onConfigure(sampleRate: Int, channels: Int)
    fun processFloat(samples: FloatArray, sampleCount: Int, channels: Int, sampleRate: Int)
}

/**
 * 挂载在 Media3 `DefaultAudioSink` 上的自定义 `AudioProcessor`。
 *
 * 1. 保持输入与输出 [AudioProcessor.AudioFormat] 完全一致，兼容 Media3 下游内置处理器（避免 `Unhandled input format: encoding=4`）；
 * 2. 内部统一无损升格为 32-bit Float 交给 [ExternalPcmDspHook.processFloat] 单次处理，再精准写回原编码格式；
 * 3. 复用内部 `FloatArray`（零每帧分配）。
 */
@OptIn(UnstableApi::class)
internal class OnlineMedia3DspAudioProcessor(
    private val hookProvider: () -> ExternalPcmDspHook?,
) : BaseAudioProcessor() {

    private var scratchFloats = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        val supported = encoding == C.ENCODING_PCM_16BIT ||
            encoding == C.ENCODING_PCM_24BIT ||
            encoding == C.ENCODING_PCM_32BIT ||
            encoding == C.ENCODING_PCM_FLOAT
        if (!supported || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        runCatching {
            hookProvider()?.onConfigure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        }.onFailure { err ->
            AppLogger.w(TAG, "onConfigure DSP hook failed: ${err.message}")
        }
        // 保持与输入完全一致的编码格式输出，避免 Media3 下游 AudioProcessor 拒收 ENCODING_PCM_FLOAT
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val hook = hookProvider()
        if (hook == null) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val encoding = inputAudioFormat.encoding
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val sampleCount = remaining / bytesPerSample
        if (sampleCount <= 0) return

        if (scratchFloats.size < sampleCount) {
            scratchFloats = FloatArray(sampleCount)
        }

        val startPos = inputBuffer.position()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                var pos = startPos
                for (i in 0 until sampleCount) {
                    val lo = inputBuffer.get(pos).toInt() and 0xFF
                    val hi = inputBuffer.get(pos + 1).toInt()
                    val sample = (hi shl 8) or lo
                    scratchFloats[i] = sample * INV_INT16
                    pos += 2
                }
            }
            C.ENCODING_PCM_24BIT -> {
                var pos = startPos
                for (i in 0 until sampleCount) {
                    var value = (inputBuffer.get(pos).toInt() and 0xFF) or
                        ((inputBuffer.get(pos + 1).toInt() and 0xFF) shl 8) or
                        ((inputBuffer.get(pos + 2).toInt() and 0xFF) shl 16)
                    if ((value and 0x00800000) != 0) {
                        value = value or -0x01000000
                    }
                    scratchFloats[i] = value * INV_INT24
                    pos += 3
                }
            }
            C.ENCODING_PCM_32BIT -> {
                var pos = startPos
                for (i in 0 until sampleCount) {
                    val value = (inputBuffer.get(pos).toInt() and 0xFF) or
                        ((inputBuffer.get(pos + 1).toInt() and 0xFF) shl 8) or
                        ((inputBuffer.get(pos + 2).toInt() and 0xFF) shl 16) or
                        ((inputBuffer.get(pos + 3).toInt() and 0xFF) shl 24)
                    scratchFloats[i] = (value.toDouble() * INV_INT32).toFloat()
                    pos += 4
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val view = inputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                view.get(scratchFloats, 0, sampleCount)
            }
        }
        inputBuffer.position(startPos + sampleCount * bytesPerSample)

        runCatching {
            hook.processFloat(
                samples = scratchFloats,
                sampleCount = sampleCount,
                channels = inputAudioFormat.channelCount,
                sampleRate = inputAudioFormat.sampleRate,
            )
        }.onFailure { err ->
            AppLogger.w(TAG, "processFloat failed, bypassing frame: ${err.message}")
        }

        val outBytes = sampleCount * bytesPerSample
        val out = replaceOutputBuffer(outBytes)
        out.order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                for (i in 0 until sampleCount) {
                    val f = scratchFloats[i]
                    val clamped = if (f.isFinite()) f.coerceIn(-1.0f, 1.0f) else 0f
                    val scaled = if (clamped < 0f) clamped * 32768.0f else clamped * 32767.0f
                    val s = scaled.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    out.putShort(s)
                }
            }
            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until sampleCount) {
                    val f = scratchFloats[i]
                    val clamped = if (f.isFinite()) f.coerceIn(-1.0f, 1.0f) else 0f
                    val scaled = if (clamped < 0f) clamped * 8388608.0f else clamped * 8388607.0f
                    val v = scaled.roundToInt().coerceIn(-8388608, 8388607)
                    out.put((v and 0xFF).toByte())
                    out.put(((v shr 8) and 0xFF).toByte())
                    out.put(((v shr 16) and 0xFF).toByte())
                }
            }
            C.ENCODING_PCM_32BIT -> {
                for (i in 0 until sampleCount) {
                    val f = scratchFloats[i]
                    val clamped = if (f.isFinite()) f.coerceIn(-1.0f, 1.0f).toDouble() else 0.0
                    val scaled = if (clamped < 0.0) clamped * 2147483648.0 else clamped * 2147483647.0
                    val v = scaled.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                    out.putInt(v)
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                for (i in 0 until sampleCount) {
                    val f = scratchFloats[i]
                    out.putFloat(if (f.isFinite()) f.coerceIn(-1.0f, 1.0f) else 0f)
                }
            }
        }
        out.flip()
    }

    override fun onReset() {
        scratchFloats = FloatArray(0)
    }

    private companion object {
        const val TAG = "OnlineMedia3Dsp"
        const val INV_INT16 = 1.0f / 32768.0f
        const val INV_INT24 = 1.0f / 8388608.0f
        const val INV_INT32 = 1.0 / 2147483648.0
    }
}
