package com.zcode.tingmu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** 听幕·屏核心服务，三模式：
 *  env   = 环境字幕（麦克风全量字幕悬浮在任意 App 上）
 *  media = 视频字幕（MediaProjection 捕获手机内播放的媒体音频 → 字幕，Android 10+）
 *  ride  = 乘车省电（KWS 哨兵监听报站触发词 + 站名表，非站名语音不上屏） */
class ScreenService : Service() {
    companion object {
        const val TAG = "TingmuScreen"
        const val ACTION_STOP = "com.zcode.tingmu.screen.ACTION_STOP"
        const val EXTRA_MODE = "mode" // env | media | ride
        const val EXTRA_SELFTEST = "selftest"
        const val EXTRA_WAV = "wav" // selftest 指定 asset 音频
        const val EXTRA_PROJ_CODE = "proj_code" // MediaProjection resultCode
        const val EXTRA_PROJ_DATA = "proj_data" // MediaProjection resultData(Intent)
        const val CHANNEL = "screen_service"
        const val NOTIF_ID = 1

        var isRunning = false
            private set
        var runningMode = ""
            private set

        // selftest 兜底站名表（内置示例线路；真实使用由用户粘贴自己的线路）
        val SELFTEST_STATIONS = listOf("人民广场", "南京东路", "世纪大道", "静安寺")
        val DEFAULT_KEYWORDS = listOf("下一站", "终点站", "请下车", "换乘", "到站", "列车进站")
    }

    private val running = AtomicBoolean(false)
    private var engineThread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var fw: FloatingWindow

    private var mode = "ride"
    private var selftestMode = false
    private var selftestWav = "audio/station_test_16k.wav"
    private var caption: CaptionEngine? = null
    private var projection: MediaProjection? = null

    // —— ride 模式状态（P1 原样保留）——
    private var kws: KeywordSpotter? = null
    private var kwsStream: OnlineStream? = null
    private var rideRec: OnlineRecognizer? = null
    private var rideSrc: AudioSource? = null
    private val ring = ArrayDeque<FloatArray>()
    private var ringSamples = 0
    private val ringCap = 160000
    @Volatile private var collectUntil = 0L
    private var pendingKeyword = ""
    private var lastShownStation = ""
    private var lastShownAt = 0L
    private var lastHintAt = 0L
    private var decodeMsTotal = 0.0
    private var audioMsTotal = 0.0
    private var kwsHitCount = 0
    private var stationMatchCount = 0

    // 配置
    private var keywordWords = DEFAULT_KEYWORDS
    private var stations: List<String> = emptyList()
    private var vibrateOn = true
    private var stayMs = 10000L
    private var fontSp = FONT_SIZES[1]

    override fun onCreate() {
        super.onCreate()
        fw = FloatingWindow(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEngine()
            return START_NOT_STICKY
        }
        if (running.get()) return START_STICKY
        readPrefs()
        mode = intent?.getStringExtra(EXTRA_MODE)
            ?: prefs().getString("mode", "ride") ?: "ride"
        selftestMode = intent?.getBooleanExtra(EXTRA_SELFTEST, false) == true
        intent?.getStringExtra(EXTRA_WAV)?.let { selftestWav = it }
        if (!selftestMode && mode == "ride" && stations.isEmpty()) {
            toast("先在设置里粘贴站名表（一行一站）")
            return START_NOT_STICKY
        }
        running.set(true)
        isRunning = true
        runningMode = mode
        // media 模式必须先以 mediaProjection 类型进前台，才能 getMediaProjection（Android 14+ 硬顺序）
        startForegroundCompat()
        fw.show(fontSp, THEMES[prefs().getInt("theme_idx", 1)])
        engineThread = Thread { runEngine(intent) }.also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        if (selftestMode) {
            // selftest：字幕保留 15s 供截图验证，主线程 postDelayed 在 service 销毁后仍执行
            ui.postDelayed({ fw.remove() }, 15000)
        } else {
            fw.remove()
        }
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("screen_prefs", MODE_PRIVATE)

    private fun readPrefs() {
        val p = prefs()
        keywordWords = p.getString("keywords", null)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: DEFAULT_KEYWORDS
        stations = p.getString("stations", null)?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        vibrateOn = p.getBoolean("vibrate", true)
        stayMs = p.getLong("stay_ms", 10000L)
        fontSp = FONT_SIZES[p.getInt("font_idx", 1).coerceIn(0, FONT_SIZES.size - 1)]
    }

    // ---------- 引擎主流程 ----------

    private fun runEngine(intent: Intent?) {
        try {
            when (mode) {
                "env" -> runCaptionMode(selftestWavAsset = if (selftestMode) selftestWav else null, projIntent = null)
                "media" -> runCaptionMode(
                    selftestWavAsset = if (selftestMode) selftestWav else null,
                    projIntent = @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_PROJ_DATA),
                    projCode = intent?.getIntExtra(EXTRA_PROJ_CODE, -1) ?: -1,
                )
                else -> runRideMode()
            }
        } catch (e: Exception) {
            Log.e(TAG, "engine error", e)
            ui.post { toast("运行失败：${e.message}") }
        } finally {
            releaseAll()
            running.set(false)
            isRunning = false
            runningMode = ""
            // selftest 保留字幕供截图验证；真实运行隐藏
            if (!selftestMode) ui.post { fw.hide() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** env（麦克风）/ media（内部音频捕获）共用：CaptionEngine 全量字幕 → 悬浮窗尾部两段。 */
    private fun runCaptionMode(selftestWavAsset: String?, projIntent: Intent?, projCode: Int = -1) {
        val eng = CaptionEngine(this)
        caption = eng
        eng.onCaption = { tail, _, _ ->
            ui.post {
                fw.update(tail)
                fw.scheduleHide(3000) // 3s 无更新自动淡出（每次更新重置）
            }
        }
        eng.onError = { msg -> ui.post { toast(msg) } }
        val src: AudioSource
        var selftestSec = 0f
        var stopAfter = 0f
        if (selftestWavAsset != null && mode == "env") {
            val ws = WavSource.wavSource(this, selftestWavAsset)
                ?: throw IllegalStateException("测试音频缺失: $selftestWavAsset")
            selftestSec = ws.durationSec()
            src = ws
        } else if (mode == "media") {
            if (projIntent == null || projCode < 0) throw IllegalStateException("缺 MediaProjection 授权")
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = pm.getMediaProjection(projCode, projIntent)
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "PROJECTION_STOPPED")
                    stopEngine()
                }
            }, ui)
            src = ProjectionSource(proj)
            if (selftestWavAsset != null) {
                // 自播链路：AudioTrack(USAGE_MEDIA) 播报站 wav → 同一输出被捕获 → 重采样 → 识别
                val playSec = startSelftestPlayback(selftestWavAsset)
                stopAfter = playSec + 1.5f
            }
        } else {
            src = MicSource()
        }
        val model = pickAsrEntry()
        eng.start(model, src, selftestAudioSec = selftestSec, stopAfterSec = stopAfter)
        Log.i(TAG, "CAPTION_MODE mode=$mode model=${model.id} selftest=${selftestWavAsset != null}")
    }

    /** media selftest：自播报站音频，返回播放时长（秒）。 */
    private fun startSelftestPlayback(asset: String): Float {
        val f = File(filesDir, asset.replace('/', '_'))
        val pcm = WavSource.read16kMono(f) ?: return 0f
        val playSec = pcm.size / 16000f
        Thread {
            try {
                Thread.sleep(600) // 等 capture 先建立
                val shorts = ShortArray(pcm.size) { (pcm[it] * 32767f).toInt().toShort() }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(16000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(shorts.size * 2)
                    .build()
                track.write(shorts, 0, shorts.size)
                track.play()
                Thread.sleep((playSec + 2).toLong() * 1000)
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w(TAG, "selftest playback fail", e)
            }
        }.start()
        return playSec
    }

    // ---------- ride 模式（P1 逻辑原样） ----------

    private fun runRideMode() {
        Log.i(TAG, "MODEL_LOADING")
        AsrEngine.ensureBundled(this)
        val kwsDir = File(filesDir, "models/" + KwsEngine.MODEL_DIR)
        if (!kwsDir.resolve("keywords.txt").isFile) throw IllegalStateException("缺 KWS keywords.txt")
        kws = KwsEngine.buildSpotter(kwsDir)
        val runtimeKw = KwsEngine.toRuntimeKeywords(keywordWords)
        kwsStream = if (runtimeKw != null) kws!!.createStream(runtimeKw) else kws!!.createStream()
        if (runtimeKw == null) Log.w(TAG, "触发词含字表外汉字，回落内置 keywords.txt: $keywordWords")
        val asrEntry = pickAsrEntry()
        rideRec = AsrEngine.buildRecognizer(asrEntry)
        rideSrc = if (selftestMode) {
            WavSource.wavSource(this, selftestWav)
                ?: throw IllegalStateException("测试音频缺失: $selftestWav")
        } else {
            MicSource().also { if (!it.open()) throw IllegalStateException("麦克风打开失败") }
        }
        if (selftestMode && stations.isEmpty()) stations = SELFTEST_STATIONS
        Log.i(TAG, "MODEL_LOADED asr=${asrEntry.id} words=${keywordWords.joinToString("|")} stations=${stations.size}")
        rideLoop()
    }

    private fun rideLoop() {
        while (running.get()) {
            val chunk = try {
                rideSrc?.read()
            } catch (_: InterruptedException) {
                break
            }
            if (chunk == null) break
            if (chunk.isEmpty()) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            audioMsTotal += 100.0
            ringPush(chunk)
            val k = kws ?: break
            val ks = kwsStream ?: break
            ks.acceptWaveform(chunk, 16000)
            while (k.isReady(ks)) {
                k.decode(ks)
                val r = k.getResult(ks)
                if (r.keyword.isNotBlank()) {
                    k.reset(ks)
                    if (collectUntil == 0L) onKeywordHit(r.keyword)
                }
            }
            if (collectUntil != 0L && SystemClock.elapsedRealtime() >= collectUntil) flushAsr()
        }
        if (selftestMode) {
            flushAsr()
            val rtf = if (audioMsTotal > 0) decodeMsTotal / audioMsTotal else 0.0
            Log.i(TAG, "SELFTEST_DONE rtf=%.3f hits=%d matches=%d audio=%.1fs".format(rtf, kwsHitCount, stationMatchCount, audioMsTotal / 1000))
        }
    }

    private fun onKeywordHit(keyword: String) {
        kwsHitCount++
        Log.i(TAG, "KWS_HIT keyword=$keyword idx=$kwsHitCount")
        pendingKeyword = keyword
        collectUntil = SystemClock.elapsedRealtime() + 5000
    }

    /** 命中窗口到点：环形缓冲整体灌 ASR → 站名匹配 → 悬浮字幕 + 震动。 */
    private fun flushAsr() {
        collectUntil = 0
        val r = rideRec ?: return
        val snap = ringSnapshot()
        if (snap.isEmpty()) return
        val st = r.createStream()
        try {
            val t0 = SystemClock.elapsedRealtime()
            st.acceptWaveform(snap, 16000)
            st.inputFinished()
            while (r.isReady(st)) r.decode(st)
            val text = cleanText(r.getResult(st).text)
            decodeMsTotal += (SystemClock.elapsedRealtime() - t0).toDouble()
            Log.i(TAG, "ASR_WINDOW text=$text")
            val now = SystemClock.elapsedRealtime()
            val station = matchStation(text)
            if (station != null) {
                if (station == lastShownStation && now - lastShownAt < 30000) {
                    Log.i(TAG, "STATION_DEDUP station=$station")
                } else {
                    lastShownStation = station
                    lastShownAt = now
                    stationMatchCount++
                    Log.i(TAG, "STATION_MATCH station=$station")
                    show("$pendingKeyword：$station", stayMs)
                }
            } else if (pendingKeyword.isNotEmpty() && now - lastHintAt > 15000) {
                lastHintAt = now
                Log.i(TAG, "NO_STATION_MATCH keyword=$pendingKeyword")
                show(pendingKeyword, 4000)
            }
        } finally {
            try { r.reset(st) } catch (_: Exception) {}
            try { st.release() } catch (_: Exception) {}
        }
    }

    /** 短窗 ASR 模型：内置/外挂里第一个流式 CTC（排除 KWS 目录），兜底任意流式模型。 */
    private fun pickAsrEntry(): ModelEntry {
        val all = AsrEngine.discover(this)
        val ctc = all.filter { it.isCtc && !it.isOffline && !it.id.startsWith(KwsEngine.MODEL_DIR) }
        return ctc.firstOrNull { it.id.contains("small") }
            ?: ctc.firstOrNull()
            ?: all.firstOrNull { !it.isOffline && !it.id.startsWith(KwsEngine.MODEL_DIR) }
            ?: throw IllegalStateException("没有可用 ASR 模型")
    }

    // ---------- 工具 ----------

    private fun ringPush(chunk: FloatArray) {
        ring.addLast(chunk)
        ringSamples += chunk.size
        while (ringSamples > ringCap) {
            val head = ring.pollFirst() ?: break
            ringSamples -= head.size
        }
    }

    private fun ringSnapshot(): FloatArray {
        val out = FloatArray(ringSamples)
        var off = 0
        for (c in ring) {
            c.copyInto(out, off)
            off += c.size
        }
        return out
    }

    private fun cleanText(s: String): String = s.replace("<unk>", "").trim()

    private fun normalize(s: String): String =
        s.replace(Regex("[\\s\\p{Punct}，。：:！!？?、,.·]"), "")

    private fun matchStation(text: String): String? {
        val norm = normalize(text)
        if (norm.isEmpty()) return null
        return stations.firstOrNull { st ->
            val n = normalize(st)
            n.isNotEmpty() && norm.contains(n)
        }
    }

    private fun show(text: String, stay: Long) {
        ui.post {
            fw.update(text)
            fw.scheduleHide(stay)
        }
        if (vibrateOn) vibrate()
    }

    private fun vibrate() {
        try {
            val vib = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(0, 120, 120, 120), -1)
            }
        } catch (_: Exception) {
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = getString(R.string.app_name)
        val text = when (mode) {
            "env" -> "正在显示环境声音字幕"
            "media" -> "正在给视频配字幕"
            else -> "正在监听到站广播，报站时屏幕下方浮现字幕"
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        val notif = builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "停止", pi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (mode == "media")
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, notif, type)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun releaseAll() {
        try { rideSrc?.close() } catch (_: Exception) {}
        try { kwsStream?.release() } catch (_: Exception) {}
        try { kws?.release() } catch (_: Exception) {}
        try { rideRec?.release() } catch (_: Exception) {}
        try { caption?.stop() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        rideSrc = null
        kwsStream = null
        kws = null
        rideRec = null
        caption = null
        projection = null
        ring.clear()
        ringSamples = 0
        collectUntil = 0L
    }

    /** 只置位 + interrupt，绝不 join（听幕教训：stop 类操作不在调用线程 join）。 */
    private fun stopEngine() {
        running.set(false)
        engineThread?.interrupt()
        caption?.stop()
    }
}
