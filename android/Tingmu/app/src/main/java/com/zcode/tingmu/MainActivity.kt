package com.zcode.tingmu

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioRecord
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.Vad
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : Activity() {

    private companion object {
        const val TAG = "TingmuSelfTest"
        /** 短停顿（≥0.6s）：自然换行（间距≈半个字高） */
        const val SEP_HALF = "\n"
        /** 长停顿（>2s）：空一行（间距=一个字高，渲染时用 FixedHeightSpan 精确控高） */
        const val SEP_BLANK = "\n\n"
    }

    private lateinit var root: FrameLayout
    private lateinit var captionScroll: ScrollView
    private lateinit var captionText: TextView
    private lateinit var captionColumn: LinearLayout
    private val segViews = HashMap<Long, TextView>()
    private lateinit var statsText: TextView
    private lateinit var liveDot: View
    private lateinit var controlBar: LinearLayout
    private lateinit var idlePanel: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var startBtn: Button
    private lateinit var selftestBtn: TextView
    private lateinit var captionScreen: FrameLayout
    private lateinit var settingsLink: TextView
    private lateinit var stopBtn: Button

    private val running = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val cancelFlag = AtomicBoolean(false)
    private var engineThread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())
    /** 已定格的段落：双通道下方言模型回填时要就地替换，所以按段存而不是拼成一个大串 */
    private class Seg(val id: Long, var text: String, var sep: String)
    private val segs = ArrayList<Seg>()
    private var nextSegId = 0L
    private var lastVoiceMs = 0L
    private var softPaused = false
    /** 真实静音累计（毫秒），只在有声音时清零；用于 0.6s 断句 / >2s 空行 */
    private var vadSilenceMs = 0L
    private var lastLoggedSegCount = -1
    private var debugLayout = false
    private var lastLayoutLogMs = 0L
    private var decodeMsSum = 0.0
    private var decodeCalls = 0L
    private var maxDecodeMs = 0.0
    private var dotLevel = 0f
    private var fontIdx = 3
    private var themeIdx = 0
    private lateinit var hintText: TextView
    private var currentModelId: String? = null
    private val prefs by lazy { getSharedPreferences("tingmu", MODE_PRIVATE) }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fontIdx = prefs.getInt("fontIdx", fontIdx)
        themeIdx = prefs.getInt("themeIdx", themeIdx) % THEMES.size
        val autoSelftest = intent?.getBooleanExtra("selftest", false) ?: false
        buildUi()
        if (!autoSelftest &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }
        if (autoSelftest) ui.post {
            // 调试用：--es model <ModelEntry.id> 可指定自测模型（如外挂的中英双语包）
            val mid = intent?.getStringExtra("model")
            val all = AsrEngine.discover(this)
            Log.i(TAG, "SELFTEST_MODEL mid=$mid all=" + all.joinToString { it.id })
            startEngine(all.firstOrNull { it.id == mid }, source = "wav")
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val (bg, fg) = THEMES[themeIdx]
        root = FrameLayout(this)
        root.setBackgroundColor(bg)
        captionScreen = FrameLayout(this)
        root.addView(captionScreen, FrameLayout.LayoutParams(-1, -1))
        captionColumn = LinearLayout(this)
        captionColumn.orientation = LinearLayout.VERTICAL
        captionColumn.setPadding(dp(16), dp(56), dp(16), dp(120))
        captionText = TextView(this)
        captionText.setTextColor(fg)
        captionText.textSize = FONT_SIZES[fontIdx]
        captionText.setTypeface(captionText.typeface, Typeface.BOLD)
        captionColumn.addView(captionText)
        captionScroll = ScrollView(this)
        captionScroll.isClickable = true
        captionScroll.setBackgroundColor(Color.TRANSPARENT)
        captionScroll.addView(captionColumn)
        captionScreen.addView(captionScroll, FrameLayout.LayoutParams(-1, -1))
        liveDot = View(this)
        val dotBg = GradientDrawable()
        dotBg.shape = GradientDrawable.OVAL
        dotBg.setColor(Color.argb(255, 10, 132, 255))
        liveDot.background = dotBg
        liveDot.alpha = 0.15f
        captionScreen.addView(liveDot, FrameLayout.LayoutParams(dp(18), dp(18)).apply {
            gravity = Gravity.TOP or Gravity.START
            marginStart = dp(14)
            topMargin = dp(14)
        })
        statsText = TextView(this)
        statsText.setTextColor(fg)
        statsText.alpha = 0.65f
        statsText.textSize = 11f
        statsText.typeface = Typeface.MONOSPACE
        val statsBg = GradientDrawable()
        statsBg.cornerRadius = dp(6).toFloat()
        statsBg.setColor(Color.argb(220, 255, 255, 255))
        statsText.background = statsBg
        statsText.setPadding(dp(10), dp(42), dp(10), 0)
        statsText.visibility = View.GONE
        captionScreen.addView(statsText, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.TOP or Gravity.END
        })

        controlBar = LinearLayout(this)
        controlBar.orientation = LinearLayout.HORIZONTAL
        controlBar.visibility = View.GONE
        controlBar.setPadding(dp(8), dp(4), dp(8), dp(4))
        val barBg = GradientDrawable()
        barBg.cornerRadius = dp(12).toFloat()
        barBg.setColor(Color.argb(200, 60, 60, 60))
        controlBar.background = barBg
        stopBtn = circle("\u25a0", 0xFFFF3B30.toInt()) { toggleRun() }
        controlBar.addView(stopBtn)
        controlBar.addView(circle("?", 0xFF3C3C3C.toInt()) { showModelDialog() })
        controlBar.addView(circle("A-", 0xFF3C3C3C.toInt()) { setFont(fontIdx - 1) })
        controlBar.addView(circle("A+", 0xFF3C3C3C.toInt()) { setFont(fontIdx + 1) })
        controlBar.addView(circle("\u914d", 0xFF3C3C3C.toInt()) { cycleTheme() })
        buildIdle()
        root.addView(controlBar, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(28)
        })
        // 一碰就出：ACTION_DOWN 立即触发；return false 不消费，滚动等原生行为照旧
        val revealOnTouch = View.OnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) showBar()
            false
        }
        captionScroll.setOnTouchListener(revealOnTouch)
        captionColumn.setOnTouchListener(revealOnTouch)
        captionScreen.setOnTouchListener(revealOnTouch)
        idlePanel.setOnTouchListener(revealOnTouch)
        idlePanel.setOnClickListener { showBar() }
        controlBar.bringToFront()
        statsText.bringToFront()
        setPhase("idle")
        setContentView(root)
        applyTheme()
    }

    /** 小圆钮：直径 52dp，停止/模型/字号/配色共用（用户要求：小一点、圆一点） */
    private fun circle(glyph: String, bgColor: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = glyph
        b.textSize = 15f
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.setTextColor(Color.WHITE)
        b.setAllCaps(false)
        b.setPadding(0, 0, 0, 0)
        b.minimumWidth = 0
        b.minimumHeight = 0
        val gd = GradientDrawable()
        gd.shape = GradientDrawable.OVAL
        gd.setColor(bgColor)
        b.background = gd
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        return b
    }

    private fun pill(text: String, txtColor: Int, bgColor: Int, w: Int, h: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 13f
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.setTextColor(txtColor)
        val gd = GradientDrawable()
        gd.cornerRadius = dp(12).toFloat()
        gd.setColor(bgColor)
        b.background = gd
        b.setPadding(dp(10), 0, dp(10), 0)
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(
            if (w > 0) w else LinearLayout.LayoutParams.WRAP_CONTENT,
            if (h > 0) h else LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return b
    }

    private fun buildIdle() {
        idlePanel = LinearLayout(this)
        idlePanel.orientation = LinearLayout.VERTICAL
        idlePanel.gravity = Gravity.CENTER
        // 第一屏明确显示版本名（双语版/快速版），与桌面图标名一致
        titleText = TextView(this)
        titleText.text = getString(R.string.app_name) + "  v" +
            packageManager.getPackageInfo(packageName, 0).versionName
        titleText.textSize = 22f
        titleText.typeface = android.graphics.Typeface.DEFAULT_BOLD
        titleText.gravity = Gravity.CENTER
        idlePanel.addView(titleText, LinearLayout.LayoutParams(-2, -2).apply {
            bottomMargin = dp(26)
        })
        startBtn = pill(Strings.start, Color.WHITE, 0xFF0A84FF.toInt(), dp(260), dp(84)) { toggleRun() }
        startBtn.textSize = 26f
        idlePanel.addView(startBtn, LinearLayout.LayoutParams(dp(260), dp(84)).apply {
            bottomMargin = dp(22)
        })
        hintText = TextView(this)
        val hint = hintText
        hint.text = Strings.hint
        hint.textSize = 15f
        hint.setTextColor(0xFF666666.toInt())
        hint.gravity = Gravity.CENTER
        idlePanel.addView(hint, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(22)
        })
        selftestBtn = TextView(this)
        selftestBtn.text = Strings.selftest
        selftestBtn.textSize = 13f
        selftestBtn.setTextColor(0xFF0A84FF.toInt())
        selftestBtn.gravity = Gravity.CENTER
        selftestBtn.setPadding(0, dp(10), 0, 0)
        selftestBtn.setOnClickListener { toggleRun(source = "wav") }
        idlePanel.addView(selftestBtn, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(10)
        })
        settingsLink = TextView(this)
        settingsLink.text = Strings.settingsLink
        settingsLink.textSize = 15f
        settingsLink.setTextColor(0xFF0A84FF.toInt())
        settingsLink.gravity = Gravity.CENTER
        settingsLink.setPadding(0, dp(16), 0, 0)
        settingsLink.setOnClickListener { showBar() }
        idlePanel.addView(settingsLink, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(6)
        })
        root.addView(idlePanel, FrameLayout.LayoutParams(-1, -1))
    }

    private val hideBar = Runnable {
        controlBar.visibility = View.GONE
        statsText.visibility = View.GONE
    }

    private fun showBar() {
        Log.i(TAG, "SHOWBAR")
        controlBar.visibility = View.VISIBLE
        statsText.visibility = View.VISIBLE
        ui.removeCallbacks(hideBar)
        ui.postDelayed(hideBar, 8000)
    }

    /** 三态切换，唯一改可见性的入口：idle=待机首屏 / running=纯字幕 / busy=加载中 */
    private fun setPhase(phase: String) {
        when (phase) {
            "running" -> {
                idlePanel.visibility = View.GONE
                captionScreen.visibility = View.VISIBLE
                controlBar.visibility = View.GONE
                statsText.visibility = View.GONE
                stopBtn.visibility = View.VISIBLE
                startBtn.isEnabled = true
                selftestBtn.isEnabled = true
                startBtn.text = Strings.start
            }
            else -> { // idle（busy 也停留在此，按钮文字自解释）
                idlePanel.visibility = View.VISIBLE
                captionScreen.visibility = View.GONE
                controlBar.visibility = View.GONE
                stopBtn.visibility = View.GONE // 首屏已有大按钮，圆钮条只留设置类
                startBtn.isEnabled = true
                selftestBtn.isEnabled = true
                startBtn.text = Strings.start
            }
        }
        synchronized(segs) { segs.clear(); nextSegId = 0L }
        for (tv in segViews.values) captionColumn.removeView(tv)
        segViews.clear()
        captionText.text = ""
        dotLevel = 0f
        liveDot.alpha = 0.15f
    }

    private fun applyTheme() {
        val (bg, fg) = THEMES[themeIdx]
        root.setBackgroundColor(bg)
        captionText.setTextColor(fg)
        for (tv in segViews.values) tv.setTextColor(fg)
        statsText.setTextColor(fg)
        titleText.setTextColor(fg)
        val dark = bg != Color.WHITE
        hintText.setTextColor(if (dark) 0xFFCCCCCC.toInt() else 0xFF666666.toInt())
        val statsBg2 = GradientDrawable()
        statsBg2.cornerRadius = dp(6).toFloat()
        statsBg2.setColor(if (dark) 0xCC101418.toInt() else 0xCCFFFFFF.toInt())
        statsText.background = statsBg2
        prefs.edit().putInt("themeIdx", themeIdx).putInt("fontIdx", fontIdx).apply()
    }

    private fun setFont(idx: Int) {
        fontIdx = if (idx < 0) 0 else if (idx >= FONT_SIZES.size) FONT_SIZES.size - 1 else idx
        captionText.textSize = FONT_SIZES[fontIdx]
        for (tv in segViews.values) tv.textSize = FONT_SIZES[fontIdx]
        prefs.edit().putInt("fontIdx", fontIdx).apply()
    }

    private fun cycleTheme() {
        themeIdx = (themeIdx + 1) % THEMES.size
        applyTheme()
    }

    private fun showModelDialog() {
        val models = AsrEngine.discover(this)
        if (models.isEmpty()) {
            Toast.makeText(this, Strings.noModel, Toast.LENGTH_SHORT).show()
            return
        }
        val items = models.map { m ->
            if (m.id == currentModelId) m.label + "  OK" else m.label
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(Strings.chooseModel)
            .setItems(items) { _, which -> startEngine(models[which]) }
            .setNeutralButton(Strings.cancel, null)
            .show()
    }

    private fun defaultModel(): ModelEntry? {
        val models = AsrEngine.discover(this)
        // 每个变体首选的模型前缀：方言版=sense（离线方言），双语版=x-asr，快速版=small
        val pref = getString(R.string.model_pref)
        val hit = models.filter { it.id.startsWith(pref) }
        return hit.firstOrNull { pref != "sense" || it.isOffline }
            ?: hit.firstOrNull()
            ?: models.firstOrNull()
    }

    private var lastToggleMs = 0L

    private fun toggleRun(source: String = "mic") {
        val now = System.currentTimeMillis()
        if (now - lastToggleMs < 400) return // 双击防抖
        lastToggleMs = now
        if (starting.get()) {
            Toast.makeText(this, Strings.busy, Toast.LENGTH_SHORT).show()
            return
        }
        if (running.get()) {
            requestStop()
            return
        }
        val pick = defaultModel() ?: run {
            Toast.makeText(this, Strings.noModel, Toast.LENGTH_SHORT).show()
            return
        }
        startEngine(pick, source)
    }

    /** 方言版双通道：流式小模型先实时出字（第一遍），VAD 判一句说完后
     *  方言离线模型把这句重新解码、就地替换成更准的字幕（第二遍）。
     *  没有可用流式模型时退回"整句出字"单通道。 */
    private fun runOfflineEngine(model: ModelEntry, source: String) {
        var rec: OfflineRecognizer? = null
        var srec: OnlineRecognizer? = null
        var sst: OnlineStream? = null
        var vad: Vad? = null
        val decExec = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            Log.i(TAG, "MODEL_LOADING " + model.id + " src=" + source + " offline=true")
            rec = AsrEngine.buildOfflineRecognizer(model)
            // 第一遍的流式模型：随包内置的 small-ctc（25MB），方言包里有就直接用
            val streamEntry = AsrEngine.discover(this).firstOrNull { it.isCtc && !it.isOffline }
            if (streamEntry != null) {
                srec = AsrEngine.buildRecognizer(streamEntry)
                sst = srec.createStream()
            }
            vad = AsrEngine.buildVad(this@MainActivity)
            if (cancelFlag.get()) return
            currentModelId = model.id
            running.set(true)
            starting.set(false)
            Log.i(TAG, "MODEL_LOADED " + model.id + " vad=" + (vad != null) + " dual=" + (srec != null))
            debugLayout = (source == "wav")
            lastLayoutLogMs = 0L
            ui.post { setPhase("running") }
            val src = if (source == "wav") WavSource.wavSource(this@MainActivity) else MicSource()
            if (src == null) {
                ui.post { Toast.makeText(this@MainActivity, Strings.loadFail, Toast.LENGTH_SHORT).show() }
                return
            }
            src.open()
            val segRec = rec!!
            val streamRec = srec
            val dual = streamRec != null && sst != null
            val sbuf = ArrayList<Float>(16000 * 12)
            var inSpeech = false
            var silenceMs = 0L
            var partial = ""

            // 一句结束：定格第一遍字幕（马上上屏）→ 提交方言模型重解码 → 回来后替换该段
            val flush = fun(forced: Boolean) {
                if (sbuf.size < 16000 / 4) { // <0.25s 忽略
                    sbuf.clear()
                    partial = ""
                    return
                }
                val samples = FloatArray(sbuf.size) { sbuf[it] }
                sbuf.clear()
                val sep = if (forced) SEP_HALF else sepFor(silenceMs)
                if (dual) {
                    var first = ""
                    try {
                        val st = sst
                        if (st != null) {
                            st.inputFinished()
                            while (streamRec!!.isReady(st)) streamRec.decode(st)
                            first = cleanAsrText(streamRec.getResult(st).text)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "stream finalize failed", e)
                    }
                    val id = commitSegment(first, sep)
                    partial = ""
                    decExec.execute {
                        try {
                            val t0 = System.nanoTime()
                            val ost = segRec.createStream()
                            ost.acceptWaveform(samples, 16000)
                            segRec.decode(ost)
                            val text = cleanAsrText(segRec.getResult(ost).text)
                            val ms = (System.nanoTime() - t0) / 1e6
                            decodeMsSum += ms
                            decodeCalls++
                            if (text.isNotBlank()) {
                                replaceSegment(id, text)
                                Log.i(TAG, "SEG_CORRECT text=" + text)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "offline decode error", e)
                        }
                        ui.post { render("", dotLevel) }
                    }
                    // inputFinished 之后旧流不能复用
                    try { sst?.release() } catch (_: Exception) {}
                    sst = streamRec!!.createStream()
                } else {
                    decExec.execute {
                        try {
                            val t0 = System.nanoTime()
                            val ost = segRec.createStream()
                            ost.acceptWaveform(samples, 16000)
                            segRec.decode(ost)
                            val text = cleanAsrText(segRec.getResult(ost).text)
                            val ms = (System.nanoTime() - t0) / 1e6
                            decodeMsSum += ms
                            decodeCalls++
                            if (text.isNotBlank()) {
                                commitSegment(text, sep)
                                ui.post { render("", dotLevel) }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "offline decode error", e)
                        }
                    }
                }
            }
            while (running.get() && !cancelFlag.get()) {
                val chunk = src.read() ?: break
                if (chunk.isEmpty()) {
                    Thread.sleep(10)
                    continue
                }
                var lvl = 0f
                if (vad != null) {
                    var wi = 0
                    while (wi + 512 <= chunk.size) {
                        lvl = max(lvl, vad.compute(chunk.copyOfRange(wi, wi + 512)))
                        wi += 512
                    }
                } else {
                    var s = 0.0
                    for (v in chunk) s += v.toDouble() * v
                    lvl = (kotlin.math.sqrt(s / chunk.size) * 12).toFloat()
                }
                dotLevel = lvl
                if (lvl > 0.4f) {
                    inSpeech = true
                    silenceMs = 0
                } else {
                    // 真实静音累计：断句后继续累计，超过 2s 把上一段升级成空行
                    silenceMs += chunk.size * 1000L / 16000
                }
                if (inSpeech) for (v in chunk) sbuf.add(v)
                // 第一遍流式识别：实时出字
                if (dual) {
                    try {
                        val st = sst
                        if (st != null) {
                            st.acceptWaveform(chunk, 16000)
                            while (streamRec!!.isReady(st)) streamRec.decode(st)
                            partial = cleanAsrText(streamRec.getResult(st).text)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "stream decode failed", e)
                    }
                }
                if (inSpeech && silenceMs >= 600) {
                    flush(false)
                    inSpeech = false
                } else if (sbuf.size > 16000 * 12) {
                    flush(true) // 长句强制切段：不算真实停顿，只换一行
                } else if (!inSpeech && silenceMs > 2000) {
                    upgradeLastSep() // 长停顿：上一段后留空行
                }
                ui.post { render(partial, lvl) }
            }
            flush(false)
            decExec.shutdown()
            try { decExec.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            if (source == "wav" && running.get()) {
                val dur = selftestDurSec()
                val rtf = decodeMsSum / 1000.0 / dur
                val fin = displayText().trim()
                Log.i(TAG, "SELFTEST_DONE rtf=" + String.format("%.4f", rtf) + " text=" + fin)
            }
        } catch (e: Exception) {
            Log.e(TAG, "engine error", e)
            ui.post { Toast.makeText(this, e.message ?: Strings.loadFail, Toast.LENGTH_SHORT).show() }
        } finally {
            try { sst?.release() } catch (_: Exception) {}
            try { srec?.release() } catch (_: Exception) {}
            try { vad?.release() } catch (_: Exception) {}
            try { rec?.release() } catch (_: Exception) {}
            running.set(false)
            starting.set(false)
            decExec.shutdown()
            ui.post { setPhase("idle") }
        }
    }

    private fun startEngine(m: ModelEntry? = null, source: String = "mic") {
        val model = m ?: defaultModel() ?: return
        if (!starting.compareAndSet(false, true)) {
            Toast.makeText(this, Strings.busy, Toast.LENGTH_SHORT).show()
            return
        }
        cancelFlag.set(false)
        val busyLabel = Strings.loading
        startBtn.text = busyLabel
        startBtn.isEnabled = false
        selftestBtn.isEnabled = false
        synchronized(segs) { segs.clear(); nextSegId = 0L }
        vadSilenceMs = 0L
        softPaused = false
        debugLayout = (source == "wav")
        lastLayoutLogMs = 0L
        decodeMsSum = 0.0
        decodeCalls = 0
        maxDecodeMs = 0.0
        dotLevel = 0f
        engineThread = Thread {
            if (model.isOffline) {
                runOfflineEngine(model, source)
                return@Thread
            }
            var rec: OnlineRecognizer? = null
            var st: OnlineStream? = null
            var vad: Vad? = null
            var ar: AudioRecord? = null
            try {
                AsrEngine.ensureBundled(this@MainActivity)
                Log.i(TAG, "MODEL_LOADING " + model.id + " src=" + source)
                rec = AsrEngine.buildRecognizer(model)
                st = rec.createStream()
                vad = AsrEngine.buildVad(this@MainActivity)
                if (cancelFlag.get()) return@Thread // 加载期间被用户停止
                currentModelId = model.id
                running.set(true)
                starting.set(false) // 加载完成即解除 busy——否则运行期点停止会被吞（真机"按不动"根因）
                Log.i(TAG, "MODEL_LOADED " + model.id + " vad=" + (vad != null))
                ui.post { setPhase("running") }
                if (source != "wav") {
                    val micOpen = MicSource()
                    if (!micOpen.open()) {
                        val granted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        val msg = if (granted) Strings.micBusy else Strings.micNoPerm
                        ui.post { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
                        return@Thread
                    }
                }
                val src = if (source == "wav") WavSource.wavSource(this@MainActivity) else MicSource()
                if (src == null) {
                    ui.post { Toast.makeText(this@MainActivity, Strings.loadFail, Toast.LENGTH_SHORT).show() }
                    return@Thread
                }
                src.open()
                var idleReads = 0
                while (running.get() && !cancelFlag.get()) {
                    val chunk = src.read() ?: break
                    if (chunk.isEmpty()) {
                        if (++idleReads > 100) break
                        Thread.sleep(20)
                        continue
                    }
                    idleReads = 0
                    processChunk(rec, st, vad, chunk, src.level())
                }
                if (source == "wav" && running.get()) {
                    try { st?.inputFinished() } catch (_: Exception) {}
                    while (st != null && rec!!.isReady(st)) rec.decode(st)
                    val text = cleanAsrText(rec.getResult(st).text)
                    val durSec = selftestDurSec()
                    val rtf = decodeMsSum / 1000.0 / durSec
                    Log.i(TAG, "SELFTEST_DONE rtf=" + String.format("%.4f", rtf) + " text=" + text)
                }
            } catch (e: Exception) {
                Log.e(TAG, "engine error", e)
                ui.post { Toast.makeText(this@MainActivity, e.message ?: Strings.loadFail, Toast.LENGTH_SHORT).show() }
            } finally {
                try { st?.release() } catch (_: Exception) {}
                try { ar?.release() } catch (_: Exception) {}
                try { vad?.release() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
                running.set(false)
                starting.set(false)
                cancelFlag.set(false)
                ui.post {
                    setPhase("idle")
                    render("", 0f)
                }
            }
        }
        engineThread?.start()
    }


    private var lastSelftestAudioSec = 18.26

    private fun selftestDurSec(): Float {
        val sec = 18.26f
        return sec
    }

    private fun processChunk(rec: OnlineRecognizer, st: OnlineStream, vad: Vad?, chunk: FloatArray, level: Float) {
        // 说话点电平：VAD 概率优先，无 VAD 用 RMS。麦克风与 wav 两路都走这里。
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
        // VAD 软断点：说话中断 >=0.6s 就把当前段定格、断口留 1.5 行；
        // 停顿超过 2s 再把 1.5 行升级成空行（长停顿/换人说话时段落更清楚）。
        // 不依赖模型自身端点（麦克风底噪下 CTC trailing-blank 计数不准，实测会漏断）
        val now = SystemClock.elapsedRealtime()
        if (lvl > 0.4f) {
            lastVoiceMs = now
            vadSilenceMs = 0
            softPaused = false
        } else {
            // 真实静音累计：不因模型端点而重置，否则 >2s 永远到不了
            vadSilenceMs += chunk.size * 1000L / 16000
        }
        val text = cleanAsrText(rec.getResult(st).text)
        var partial = text
        if (rec.isEndpoint(st)) {
            if (text.isNotBlank()) commitSegment(text, sepFor(vadSilenceMs))
            rec.reset(st)
            lastVoiceMs = now
            softPaused = true
            partial = ""
        } else if (!softPaused && vadSilenceMs >= 600 && text.isNotBlank()) {
            softPaused = true
            commitSegment(text, sepFor(vadSilenceMs))
            rec.reset(st)
            partial = ""
        } else if (softPaused && vadSilenceMs > 2000) {
            // 长停顿：上一段末尾升级成空行（幂等，只记一次）
            upgradeLastSep()
        }
        ui.post { render(partial, lvl) }
    }

    /** 定格一段已识别文字，末尾留分隔符（短停顿 1.5 行 / 长停顿空行，不加标点）。
     *  返回段 id：双通道下第二遍离线结果回来后按 id 就地替换。 */
    private fun commitSegment(text: String, sep: String): Long {
        val id = synchronized(segs) {
            val id = nextSegId++
            segs.add(Seg(id, text, sep))
            // 超长时丢最老的段，防止字幕无限占用内存
            var total = segs.sumOf { it.text.length }
            while (segs.size > 1 && total > 4000) {
                total -= segs[0].text.length
                segs.removeAt(0)
            }
            id
        }
        Log.i(TAG, "SEG_COMMIT sep=" + (if (sep == SEP_BLANK) "blank" else "half") + " text=" + text)
        return id
    }

    /** 第二遍（方言模型）结果回填：只替换指定段，用户看到的还是同一行字幕 */
    private fun replaceSegment(id: Long, text: String) {
        synchronized(segs) {
            val s = segs.firstOrNull { it.id == id } ?: return
            s.text = text
        }
    }

    /** 长停顿：把最后一段的分隔符从空格升级为换行 */
    private fun upgradeLastSep() {
        var changed = false
        synchronized(segs) {
            val last = segs.lastOrNull() ?: return
            if (last.sep != SEP_BLANK) {
                last.sep = SEP_BLANK
                changed = true
            }
        }
        if (changed) Log.i(TAG, "SEG_BLANK 静音>2s，上一段后留空行")
    }

    /** 当前全部字幕（已定格段 + 间分隔符），供渲染与自测日志用 */
    private fun displayText(): String = synchronized(segs) {
        buildString {
            for (s in segs) {
                append(s.text)
                append(if (s.sep == SEP_HALF) "\n" else s.sep)
            }
        }
    }

    /** 停顿/断句分隔：短停顿留空格，长停顿（>2s）换行。 */
    private fun sepFor(gapMs: Long) = if (gapMs > 2000) SEP_BLANK else SEP_HALF

    /** 中英文标点：产品要求不显示任何标点，标点位置一律替换成空格（如"你好，今天"→"你好 今天"）。
     *  英文句点做数字保护，"3.14" 不会被拆成 "314"。 */
    private val PUNCT_CJK = Regex("[，。！？；：、（）【】《》〈〉「」『』“”‘’…—～·]")
    private val PUNCT_ASCII = Regex("(?<![0-9])[,.!?;:](?![0-9])")

    /** 剔除模型吐出的 <unk>（纯中文模型遇英文会输出它）、所有中英文标点，并压掉多余空格 */
    private fun cleanAsrText(t: String): String =
        t.replace("<unk>", "").replace("<UNK>", "")
            .replace(PUNCT_CJK, " ")
            .replace(PUNCT_ASCII, " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

    /** 只发停止信号，绝不在主线程等待（旧版在这里 join 导致"按停止卡死/按不动"） */
    private fun requestStop() {
        cancelFlag.set(true)
        running.set(false)
        starting.set(false)
        engineThread?.interrupt()
    }

    /** 段视图：与 partial 同字号/颜色 */
    private fun makeSegView(): TextView {
        val tv = TextView(this)
        tv.setTextColor(THEMES[themeIdx].fg)
        tv.textSize = FONT_SIZES[fontIdx]
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        return tv
    }

    /** 段间额外间距（px）：短停顿=0（正常行距）；长停顿≈0.7×字号（加上行内边距约等于一行） */
    private fun gapPx(sep: String): Int =
        if (sep == SEP_BLANK) (captionText.textSize * 0.70f).toInt() else 0

    private fun render(partial: String, level: Float) {
        dotLevel = dotLevel * 0.55f + level * 0.45f
        liveDot.alpha = 0.15f + min(1f, max(0f, dotLevel)) * 0.85f
        // 每段一个 TextView：短停顿=正常行距（不加上边距），长停顿=隔一行（加约 0.7 字号的边距）。
        // 这样段内换行保持系统正常字距，段间间距精确可控，且随字号缩放。
        val snap = synchronized(segs) { segs.toList() }
        var i = 0
        val ids = HashSet<Long>()
        for (sg in snap) {
            ids.add(sg.id)
            var tv = segViews[sg.id]
            if (tv == null) {
                tv = makeSegView()
                segViews[sg.id] = tv
                captionColumn.addView(tv, i) // 插在"正在说"的 partial 之前
            }
            if (tv.text.toString() != sg.text) tv.text = sg.text
            val lp = (tv.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(-1, -2)
            val want = if (i == 0) 0 else gapPx(snap[i - 1].sep)
            if (lp.topMargin != want) {
                lp.topMargin = want
                tv.layoutParams = lp
            }
            i++
        }
        for (id in segViews.keys.filter { it !in ids }) {
            segViews.remove(id)?.let { captionColumn.removeView(it) }
        }
        if (captionText.text.toString() != partial) captionText.text = partial
        val plp = (captionText.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(-1, -2)
        val pWant = if (snap.isEmpty()) 0 else gapPx(snap.last().sep)
        if (plp.topMargin != pWant) {
            plp.topMargin = pWant
            captionText.layoutParams = plp
        }
        // 自测用：每 300ms 打一次各段视图的位置，用于核对段间距
        if (debugLayout) {
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastLayoutLogMs > 300) {
                lastLayoutLogMs = nowMs
                captionColumn.post {
                    val parts = (0 until captionColumn.childCount).map {
                        val v = captionColumn.getChildAt(it)
                        v.top.toString() + ":" + v.bottom
                    }
                    Log.i(TAG, "LAYOUTV n=" + captionColumn.childCount + " " + parts.joinToString(","))
                }
            }
        }
        captionScroll.post { captionScroll.fullScroll(View.FOCUS_DOWN) }
        val avgMs = if (decodeCalls > 0) decodeMsSum / decodeCalls else 0.0
        val rtf = avgMs / 100.0
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1048576
        statsText.text = "avg " + String.format("%.1f", avgMs) + "ms" +
            " | max " + maxDecodeMs.toLong() + "ms" +
            " | RTF " + String.format("%.2f", rtf) +
            " | native " + nativeMb + "MB" +
            " | " + (currentModelId ?: "-")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, Strings.micNoPerm, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        if ((intent?.getStringExtra("extra_toggle") ?: "") == "mic") {
            toggleRun("mic")
        }
        if (intent?.getBooleanExtra("selftest", false) ?: false) {
            toggleRun("wav")
        }
    }

    override fun onDestroy() {
        requestStop()
        super.onDestroy()
    }
}
