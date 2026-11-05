package com.zcode.tingmu

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.Vad
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/** 听幕·屏识别核心（自听幕 MainActivity 抽离，纯逻辑零 UI）：
 *  任意 AudioSource → 流式 ASR + VAD 软断点 → 段落机 → 回调输出。
 *  听幕本体 MainActivity 不走此类，听幕三版零改动。
 *  VAD 软断点语义与听幕一致：lvl>0.4 判有声；静默 ≥600ms 定格段落留空格；
 *  ≥2500ms 升级换行；模型端点仅兜底（麦克风底噪下 CTC trailing-blank 会漏断）。 */
class CaptionEngine(private val ctx: Context) {
    companion object {
        const val TAG = "TingmuScreen"
    }

    private val running = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    private var engineThread: Thread? = null

    private class Seg(val id: Long, var text: String, var sep: Char)
    private val segs = ArrayList<Seg>()
    private var nextSegId = 0L
    private var lastVoiceMs = 0L
    private var softPaused = false
    private var decodeMsSum = 0.0
    private var decodeCalls = 0L
    private var maxDecodeMs = 0.0
    private var dotLevel = 0f
    var currentModelId: String? = null
        private set

    /** 每 chunk 回调（引擎线程上调；tail=尾部段落+partial 供悬浮窗，full=全文，lvl=EMA 电平） */
    var onCaption: ((tail: String, full: String, lvl: Float) -> Unit)? = null
    /** selftest 收尾（音频源耗尽或 stopAfterSec 到点） */
    var onSelftestDone: ((rtf: Float, text: String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isRunning: Boolean get() = running.get()

    /** 启动引擎线程。selftestAudioSec>0 时音频耗尽后打 SELFTEST_DONE；
     *  stopAfterSec>0 时读满即收尾（音频源持续出样本的场景，如内部音频捕获）。 */
    fun start(model: ModelEntry, source: AudioSource, selftestAudioSec: Float = 0f, stopAfterSec: Float = 0f) {
        if (running.get()) return
        cancelFlag.set(false)
        engineThread = Thread {
            var rec: OnlineRecognizer? = null
            var st: OnlineStream? = null
            var vad: Vad? = null
            var src: AudioSource? = null
            try {
                Log.i(TAG, "ENGINE_LOADING ${model.id}")
                AsrEngine.ensureBundled(ctx)
                rec = AsrEngine.buildRecognizer(model)
                st = rec.createStream()
                vad = buildVadWithTimeout(3000)
                if (cancelFlag.get()) return@Thread
                currentModelId = model.id
                running.set(true)
                Log.i(TAG, "ENGINE_LOADED ${model.id} vad=${vad != null}")
                src = source
                if (!src.open()) throw IllegalStateException("音频源打开失败")
                // WAV selftest 的读取是非实时的（瞬间灌完）→ VAD 的 600ms 静默软断点永不触发，
                // 全程显示未定稿 partial。selftest 时按 100ms/chunk 节流使回放实时化。
                val pace = selftestAudioSec > 0f
                var idleReads = 0
                var samplesSeen = 0L
                while (running.get() && !cancelFlag.get()) {
                    val chunk = try {
                        src.read()
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (chunk == null) break
                    if (chunk.isEmpty()) {
                        idleReads++
                        if (idleReads > 100) break
                        try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                        continue
                    }
                    idleReads = 0
                    samplesSeen += chunk.size
                    processChunk(rec, st, vad, chunk)
                    if (pace) {
                        try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                    }
                    if (stopAfterSec > 0f && samplesSeen >= (stopAfterSec * 16000).toLong()) break
                }
                if (selftestAudioSec > 0f || stopAfterSec > 0f) {
                    try {
                        val r = rec
                        val s = st
                        if (r != null && s != null) {
                            s.inputFinished()
                            while (r.isReady(s)) r.decode(s)
                            val text = displayText() + cleanAsrText(r.getResult(s).text)
                            val audioSec = if (samplesSeen > 0) samplesSeen / 16000f else selftestAudioSec
                            val rtf = if (audioSec > 0f) (decodeMsSum / 1000.0 / audioSec).toFloat() else 0f
                            Log.i(TAG, "SELFTEST_DONE rtf=%.3f text=%s".format(rtf, text))
                            onSelftestDone?.invoke(rtf, text)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "selftest finish fail", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "engine error", e)
                if (running.get()) onError?.invoke(e.message ?: "engine error")
            } finally {
                // 听幕旧版漏 src.close()（AudioRecord 靠 GC 兜底），这里补上
                try { src?.close() } catch (_: Exception) {}
                try { st?.release() } catch (_: Exception) {}
                try { vad?.release() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
                running.set(false)
                cancelFlag.set(false)
            }
        }.also { it.start() }
    }

    /** 只发停止信号，绝不 join（听幕教训：stop 类操作不在调用线程 join） */
    fun stop() {
        cancelFlag.set(true)
        running.set(false)
        engineThread?.interrupt()
    }

    /** 供 Service 的引擎线程等待识别线程自然结束（用户停止/音频耗尽）。
     *  必须在非主线程调用——Service 的 runEngine 线程专用。 */
    fun awaitDone() {
        try {
            engineThread?.join()
        } catch (_: InterruptedException) {
            // 服务线程被 stopEngine interrupt：识别线程已被 stop() 信号终止，直接返回
        }
    }

    /** VAD 构造看门狗：SileroVad 的 native 构造在个别环境（如 x86_64 模拟器 + sherpa-onnx
     *  v2.0.1）会静默挂死（0% CPU 无限等待）。3 秒不返回即降级 RMS 电平——
     *  引擎断句逻辑本来就支持 vad=null（RMS×12 驱动软断点），只是灵敏度略低。
     *  真机 arm64 无此问题（听幕 v1.6 真机 VAD 常年正常）。 */
    private fun buildVadWithTimeout(ms: Long): Vad? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Vad? = null
        Thread {
            try {
                result = AsrEngine.buildVad(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "buildVad fail", e)
            }
            latch.countDown()
        }.start()
        if (latch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)) return result
        Log.w(TAG, "VAD 构造超时(${ms}ms)，降级 RMS 电平")
        return null
    }

    private fun processChunk(rec: OnlineRecognizer, st: OnlineStream, vad: Vad?, chunk: FloatArray) {
        var lvl = 0f
        if (vad != null) {
            var wi = 0
            while (wi + 512 <= chunk.size) {
                lvl = max(lvl, vad.compute(chunk.copyOfRange(wi, wi + 512)))
                wi += 512
            }
        } else {
            var acc = 0.0
            for (v in chunk) acc += v.toDouble() * v
            lvl = (sqrt(acc / chunk.size) * 12f).toFloat()
        }
        st.acceptWaveform(chunk, 16000)
        while (rec.isReady(st)) {
            val t0 = System.nanoTime()
            rec.decode(st)
            val ms = (System.nanoTime() - t0) / 1e6
            decodeMsSum += ms
            decodeCalls++
            maxDecodeMs = max(maxDecodeMs, ms)
        }
        val now = SystemClock.elapsedRealtime()
        if (lvl > 0.4f) {
            lastVoiceMs = now
            softPaused = false
        }
        val gapMs = now - lastVoiceMs
        val text = cleanAsrText(rec.getResult(st).text)
        var partial = text
        if (rec.isEndpoint(st)) {
            if (text.isNotBlank()) commitSegment(text, sepFor(gapMs))
            rec.reset(st)
            lastVoiceMs = now
            softPaused = false
            partial = ""
        } else if (!softPaused && gapMs > 600 && text.isNotBlank()) {
            softPaused = true
            commitSegment(text, sepFor(gapMs))
            rec.reset(st)
            partial = ""
        } else if (softPaused && gapMs > 2500) {
            upgradeLastSep()
        }
        dotLevel = dotLevel * 0.55f + lvl * 0.45f
        onCaption?.invoke(tailShown(partial), displayText() + partial, dotLevel)
    }

    /** 悬浮窗显示用：最后两段 + 当前半句（Live Caption 式）；再截到最新 40 字，
     *  防止连续说话（VAD 不断句）时字幕条无限长占满屏幕。 */
    private fun tailShown(partial: String): String {
        val raw = synchronized(segs) {
            val n = segs.size
            if (n == 0) return partial
            val start = if (n >= 2) n - 2 else 0
            buildString {
                for (i in start until n) {
                    append(segs[i].text)
                    append(segs[i].sep)
                }
            }.toString() + partial
        }
        return if (raw.length > 30) raw.takeLast(30) else raw
    }

    /** 定格一段已识别文字，末尾留分隔符（短停顿空格/长停顿换行）。返回段 id。 */
    private fun commitSegment(text: String, sep: Char): Long {
        val id = synchronized(segs) {
            val id = nextSegId++
            segs.add(Seg(id, text, sep))
            var total = segs.sumOf { it.text.length }
            while (segs.size > 1 && total > 4000) {
                total -= segs[0].text.length
                segs.removeAt(0)
            }
            id
        }
        Log.i(TAG, "SEG_COMMIT sep=" + (if (sep == '\n') "\\n" else "space") + " text=" + text)
        return id
    }

    private fun upgradeLastSep() {
        synchronized(segs) {
            val last = segs.lastOrNull() ?: return
            if (last.sep == ' ') last.sep = '\n'
        }
    }

    /** 当前全部字幕（已定格段+分隔符） */
    fun displayText(): String = synchronized(segs) {
        buildString {
            for (s in segs) {
                append(s.text)
                append(s.sep)
            }
        }
    }

    private fun sepFor(gapMs: Long) = if (gapMs > 2500) '\n' else ' '

    private fun cleanAsrText(t: String): String =
        t.replace("<unk>", "").replace("<UNK>", "").replace(Regex(" {2,}"), " ")

    fun clearText() {
        synchronized(segs) { segs.clear() }
        nextSegId = 0
        lastVoiceMs = 0L
        softPaused = false
        decodeMsSum = 0.0
        decodeCalls = 0L
        maxDecodeMs = 0.0
        dotLevel = 0f
    }

    fun stats(): String {
        val avgMs = if (decodeCalls > 0) decodeMsSum / decodeCalls else 0.0
        val rtf = avgMs / 100.0
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1048576
        return "avg " + String.format("%.1f", avgMs) + "ms" +
            " | max " + maxDecodeMs.toLong() + "ms" +
            " | RTF " + String.format("%.2f", rtf) +
            " | native " + nativeMb + "MB" +
            " | " + (currentModelId ?: "-")
    }
}
