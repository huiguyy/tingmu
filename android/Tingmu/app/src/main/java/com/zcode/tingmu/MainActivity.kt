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
    }

    private lateinit var root: FrameLayout
    private lateinit var captionScroll: ScrollView
    private lateinit var captionText: TextView
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
    private val finalized = StringBuilder()
    private var lastVoiceMs = 0L
    private var softPaused = false
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
        captionText = TextView(this)
        captionText.setTextColor(fg)
        captionText.textSize = FONT_SIZES[fontIdx]
        captionText.setTypeface(captionText.typeface, Typeface.BOLD)
        captionText.setPadding(dp(16), dp(56), dp(16), dp(120))
        captionScroll = ScrollView(this)
        captionScroll.isClickable = true
        captionScroll.setBackgroundColor(Color.TRANSPARENT)
        captionScroll.addView(captionText)
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
        captionText.setOnTouchListener(revealOnTouch)
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
        synchronized(finalized) { finalized.setLength(0) }
        captionText.text = ""
        dotLevel = 0f
        liveDot.alpha = 0.15f
    }

    private fun applyTheme() {
        val (bg, fg) = THEMES[themeIdx]
        root.setBackgroundColor(bg)
        captionText.setTextColor(fg)
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
        return models.firstOrNull { it.isCtc && it.id.startsWith("small") }
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

    /** 离线模型（SenseVoice）的识别管线：VAD 切短段 → 整句识别 → 出字。
     *  离线模型没有流式接口，所以字幕是"一段说完才出"；VAD 按 0.6s 停顿切短语以压低延迟。 */
    private fun runOfflineEngine(model: ModelEntry, source: String) {
        var rec: OfflineRecognizer? = null
        var vad: Vad? = null
        val decExec = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            Log.i(TAG, "MODEL_LOADING " + model.id + " src=" + source + " offline=true")
            rec = AsrEngine.buildOfflineRecognizer(model)
            vad = AsrEngine.buildVad(this@MainActivity)
            if (cancelFlag.get()) return
            currentModelId = model.id
            running.set(true)
            starting.set(false)
            Log.i(TAG, "MODEL_LOADED " + model.id + " vad=" + (vad != null))
            ui.post { setPhase("running") }
            val src = if (source == "wav") WavSource.wavSource(this@MainActivity) else MicSource()
            if (src == null) {
                ui.post { Toast.makeText(this@MainActivity, Strings.loadFail, Toast.LENGTH_SHORT).show() }
                return
            }
            src.open()
            val segRec = rec!!
            val sbuf = ArrayList<Float>(16000 * 12)
            var inSpeech = false
            var silenceMs = 0
            var lastEndMs = SystemClock.elapsedRealtime()
            val flush = fun() {
                if (sbuf.size < 16000 / 4) { // <0.25s 忽略
                    sbuf.clear()
                    return
                }
                val samples = FloatArray(sbuf.size) { sbuf[it] }
                sbuf.clear()
                val gap = SystemClock.elapsedRealtime() - lastEndMs
                lastEndMs = SystemClock.elapsedRealtime()
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
                            commitSegment(text, sepFor(gap))
                            ui.post { render("", dotLevel) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "offline decode error", e)
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
                } else if (inSpeech) {
                    silenceMs += 100
                }
                if (inSpeech) for (v in chunk) sbuf.add(v)
                if (inSpeech && silenceMs >= 600) {
                    flush()
                    inSpeech = false
                    silenceMs = 0
                } else if (sbuf.size > 16000 * 12) {
                    flush() // 长句强制切段，避免无限积压
                }
                ui.post { render("", lvl) }
            }
            flush()
            decExec.shutdown()
            try { decExec.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            if (source == "wav" && running.get()) {
                val dur = selftestDurSec()
                val rtf = decodeMsSum / 1000.0 / dur
                val fin = synchronized(finalized) { finalized.toString().trim() }
                Log.i(TAG, "SELFTEST_DONE rtf=" + String.format("%.4f", rtf) + " text=" + fin)
            }
        } catch (e: Exception) {
            Log.e(TAG, "engine error", e)
            ui.post { Toast.makeText(this, e.message ?: Strings.loadFail, Toast.LENGTH_SHORT).show() }
        } finally {
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
        finalized.setLength(0)
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
                    val text = rec.getResult(st).text
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
        // VAD 软断点：说话中断 >=0.6s 就把当前段定格、在断口留一个空格；
        // 停顿超过 2.5s 再把刚才的空格升级成换行（长停顿/换人说话时段落更清楚）。
        // 不依赖模型自身端点（麦克风底噪下 CTC trailing-blank 计数不准，实测会漏断）
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
            // 长停顿：末尾空格升级为换行（只升级一次）
            synchronized(finalized) {
                if (finalized.isNotEmpty() && finalized.last() == ' ') {
                    finalized.setCharAt(finalized.length - 1, '\n')
                }
            }
        }
        ui.post { render(partial, lvl) }
    }

    /** 定格一段已识别文字，末尾留分隔符（短停顿空格 / 长停顿换行，不加标点） */
    private fun commitSegment(text: String, sep: Char) {
        synchronized(finalized) {
            finalized.append(text).append(sep)
            if (finalized.length > 4000) finalized.delete(0, finalized.length - 3000)
        }
        Log.i(TAG, "SEG_COMMIT sep=" + (if (sep == '\n') "\\n" else "space") + " text=" + text)
    }

    private fun sepFor(gapMs: Long) = if (gapMs > 2500) '\n' else ' '

    /** 剔除模型吐出的 <unk>（纯中文模型遇英文会输出它），别把 "UNK" 显示给用户；顺便压掉多余空格 */
    private fun cleanAsrText(t: String): String =
        t.replace("<unk>", "").replace("<UNK>", "").replace(Regex(" {2,}"), " ")

    /** 只发停止信号，绝不在主线程等待（旧版在这里 join 导致"按停止卡死/按不动"） */
    private fun requestStop() {
        cancelFlag.set(true)
        running.set(false)
        starting.set(false)
        engineThread?.interrupt()
    }

    private fun render(partial: String, level: Float) {
        dotLevel = dotLevel * 0.55f + level * 0.45f
        liveDot.alpha = 0.15f + min(1f, max(0f, dotLevel)) * 0.85f
        val shown = synchronized(finalized) { finalized.toString() } + partial
        captionText.text = shown
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
