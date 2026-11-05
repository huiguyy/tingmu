package com.zcode.tingmu

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import kotlin.math.max

/** 视频字幕音频源（Android 10+）：MediaProjection 捕获其他 App 播放的媒体音频。
 *  系统混音输出按请求格式给（这里 48kHz mono），read() 内 3:1 下采样
 *  （三点平均抗混叠，语音 4kHz 带宽内够用）→ 16k CHUNK 喂引擎。
 *  MediaProjection 生命周期归调用方（Service 停止时 stop()）。 */
class ProjectionSource(private val proj: MediaProjection) : AudioSource {
    private var rec: AudioRecord? = null

    override fun open(): Boolean {
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val r = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(config)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(max(minBuf * 2, CHUNK * 3 * 2 * 4))
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        r.startRecording()
        rec = r
        return true
    }

    override fun read(): FloatArray? {
        val r = rec ?: return null
        val buf = ShortArray(CHUNK * 3) // 48k 下 0.1s
        val n = r.read(buf, 0, buf.size)
        if (n <= 0) return FloatArray(0)
        val out = FloatArray(n / 3)
        for (i in out.indices) {
            val a = buf[i * 3].toInt()
            val b = if (i * 3 + 1 < n) buf[i * 3 + 1].toInt() else a
            val c = if (i * 3 + 2 < n) buf[i * 3 + 2].toInt() else a
            out[i] = ((a + b + c) / 3f) / 32768f
        }
        return out
    }

    override fun level(): Float {
        return 0f
    }

    override fun close() {
        try { rec?.stop() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null
    }
}
