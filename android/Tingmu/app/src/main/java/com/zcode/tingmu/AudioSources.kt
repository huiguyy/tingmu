package com.zcode.tingmu

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

interface AudioSource {
    fun open(): Boolean
    fun read(): FloatArray?
    fun level(): Float
    fun close()
}

const val CHUNK = 1600

class MicSource : AudioSource {
    private var rec: AudioRecord? = null

    override fun open(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf * 2, CHUNK * 2 * 4)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) return false
        r.startRecording()
        rec = r
        return true
    }

    override fun read(): FloatArray? {
        val r = rec ?: return null
        val buf = ShortArray(CHUNK)
        val n = r.read(buf, 0, buf.size)
        if (n <= 0) return FloatArray(0)
        return FloatArray(n) { buf[it] / 32768f }
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

class WavSource(private val pcm: FloatArray) : AudioSource {
    private var off = 0
    private val short = ShortArray(pcm.size) { (pcm[it] * 32767f).toInt().toShort() }

    /** 音频时长（秒），selftest RTF 用 */
    fun durationSec(): Float = pcm.size / 16000f

    override fun open(): Boolean = true

    override fun read(): FloatArray? {
        if (off >= pcm.size) return null
        val end = min(off + CHUNK, pcm.size)
        val c = pcm.copyOfRange(off, end)
        off = end
        return c
    }

    override fun level(): Float {
        if (off >= pcm.size) return 0f
        val end = min(off + CHUNK, pcm.size)
        var s = 0.0
        for (i in off until end) {
            val v = pcm[i].toDouble()
            s += v * v
        }
        return sqrt(s * 12.0 / (end - off)).toFloat()
    }

    override fun close() {}

    companion object {
        /** assetName 缺省 = 听幕自测音频；听幕·屏 selftest 传 audio/station_test_16k.wav 等。 */
        fun wavSource(ctx: android.content.Context, assetName: String = "audio/test_zh_16k.wav"): WavSource? {
            val f = File(ctx.filesDir, assetName.replace('/', '_'))
            if (!f.isFile) {
                try {
                    ctx.assets.open(assetName).use { inp ->
                        f.parentFile?.mkdirs()
                        f.outputStream().use { inp.copyTo(it) }
                    }
                } catch (_: Exception) { return null }
            }
            val pcm = read16kMono(f) ?: return null
            return WavSource(pcm)
        }

        fun read16kMono(path: File): FloatArray? {
            val b = path.readBytes()
            var i = 12
            while (i + 8 <= b.size) {
                val id = String(b, i, 4, Charsets.US_ASCII)
                val sz = (b[i + 4].toInt() and 0xFF) or
                    ((b[i + 5].toInt() and 0xFF) shl 8) or
                    ((b[i + 6].toInt() and 0xFF) shl 16) or
                    ((b[i + 7].toInt() and 0xFF) shl 24)
                if (id == "data") {
                    val end = min(i + 8 + sz, b.size)
                    val out = FloatArray((end - i - 8) / 2)
                    for (k in out.indices) {
                        val lo = b[i + 8 + 2 * k].toInt() and 0xFF
                        val hi = b[i + 8 + 2 * k + 1].toInt() and 0xFF
                        out[k] = (((hi shl 8) or lo).toShort()) / 32768f
                    }
                    return out
                }
                i += 8 + sz + (sz % 2)
            }
            return null
        }
    }
}
