package com.zcode.tingmu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 听幕·厅核心服务：电视/盒子上的零操作大字幕。
 *  平时全透明（触摸穿透），有人说话浮现大字，安静 N 秒淡出；
 *  开机自启（BootReceiver→HallActivity→本服务）；老人全程不碰遥控器。 */
class HallService : Service() {
    companion object {
        const val TAG = "TingmuHall"
        const val ACTION_STOP = "com.zcode.tingmu.hall.ACTION_STOP"
        const val ACTION_REFRESH = "com.zcode.tingmu.hall.ACTION_REFRESH"
        const val EXTRA_SELFTEST = "selftest"
        const val EXTRA_WAV = "wav"
        const val CHANNEL = "hall_service"
        const val NOTIF_ID = 1

        var isRunning = false
            private set

        // selftest 兜底站名表不适用（厅无站名概念），直接全量字幕
        val SENS_VALUES = floatArrayOf(0.5f, 0.4f, 0.3f) // 灵敏度低/中/高（VAD 阈值）
    }

    private val running = AtomicBoolean(false)
    private var engineThread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var fw: FloatingWindow
    private var caption: CaptionEngine? = null

    private var selftestMode = false
    private var selftestWav = "audio/station_test_16k.wav"

    // 配置
    private var fontSp = FONT_SIZES[4] // 厅=大字场景，默认最大档
    private var bgAlpha = 60
    private var stayMs = 8000L // 安静后淡出时长，默认 8s
    private var sensIdx = 1

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
        if (intent?.action == ACTION_REFRESH) {
            if (running.get()) {
                val oldFont = fontSp
                readPrefs()
                if (fontSp != oldFont) fw.applyFontSize(fontSp)
                fw.applyTheme(THEMES[prefs().getInt("theme_idx", 1)])
                Log.i(TAG, "REFRESH_APPLIED font=$fontSp stay=$stayMs")
            }
            return START_NOT_STICKY
        }
        if (running.get()) return START_STICKY
        readPrefs()
        selftestMode = intent?.getBooleanExtra(EXTRA_SELFTEST, false) == true
        intent?.getStringExtra(EXTRA_WAV)?.let { selftestWav = it }
        running.set(true)
        isRunning = true
        startForegroundCompat()
        fw.show(fontSp, THEMES[prefs().getInt("theme_idx", 1)], bgAlpha)
        fw.onClose = {
            Log.i(TAG, "USER_CLOSE_FROM_FLOATING")
            stopEngine()
        }
        engineThread = Thread { runEngine() }.also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        // 屏 v0.8 教训：被系统回收时真正停引擎，只置标志会让录音多挂几个周期
        stopEngine()
        running.set(false)
        if (selftestMode) {
            ui.postDelayed({ fw.remove() }, 15000)
        } else {
            fw.remove()
        }
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("hall_prefs", MODE_PRIVATE)

    private fun readPrefs() {
        val p = prefs()
        fontSp = FONT_SIZES[p.getInt("font_idx", 4).coerceIn(0, FONT_SIZES.size - 1)]
        bgAlpha = p.getInt("bg_alpha", 60).coerceIn(0, 100)
        stayMs = p.getLong("stay_ms", 8000L)
        sensIdx = p.getInt("sens_idx", 1).coerceIn(0, HallService.SENS_VALUES.size - 1)
    }

    private fun runEngine() {
        try {
            Log.i(TAG, "MODEL_LOADING")
            AsrEngine.ensureBundled(this)
            val eng = CaptionEngine(this)
            caption = eng
            eng.vadThreshold = SENS_VALUES[sensIdx]
            eng.onCaption = { full, _ ->
                ui.post {
                    // VAD 显隐状态机：有内容浮现，安静 stayMs 后淡出（INVISIBLE 占位防窗口跳）
                    if (full.isBlank()) {
                        fw.hide()
                    } else {
                        fw.update(full)
                        fw.scheduleHide(stayMs)
                    }
                }
            }
            eng.onError = { msg -> ui.post { toast(msg) } }
            val src: AudioSource
            var selftestSec = 0f
            if (selftestMode) {
                val ws = WavSource.wavSource(this, selftestWav)
                    ?: throw IllegalStateException("测试音频缺失: $selftestWav")
                selftestSec = ws.durationSec()
                src = ws
            } else {
                src = MicSource(this).also { if (!it.open()) throw IllegalStateException("麦克风打开失败（USB 麦请插入后重试）") }
            }
            val model = pickAsrEntry()
            eng.start(model, src, selftestAudioSec = selftestSec)
            Log.i(TAG, "HALL_MODE model=${model.id} sens=${SENS_VALUES[sensIdx]} stay=${stayMs}ms selftest=$selftestMode")
            // 屏 v0.8 同款教训：引擎是异步线程，必须等它自然结束再进 finally 释放，
            // 否则 finally 的 caption?.stop() 会 interrupt 刚启动的引擎
            eng.awaitDone()
        } catch (e: Exception) {
            Log.e(TAG, "engine error", e)
            ui.post { toast("运行失败：${e.message}") }
        } finally {
            try { caption?.stop() } catch (_: Exception) {}
            running.set(false)
            isRunning = false
            if (!selftestMode) ui.post { fw.hide() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun pickAsrEntry(): ModelEntry {
        val all = AsrEngine.discover(this)
        val ctc = all.filter { it.isCtc && !it.isOffline && !it.id.startsWith(KwsEngine.MODEL_DIR) }
        return ctc.firstOrNull { it.id.contains("small") }
            ?: ctc.firstOrNull()
            ?: all.firstOrNull { !it.isOffline && !it.id.startsWith(KwsEngine.MODEL_DIR) }
            ?: throw IllegalStateException("没有可用 ASR 模型")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun startForegroundCompat() {
        val pi = PendingIntent.getService(
            this, 0,
            Intent(this, HallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = getString(R.string.app_name)
        val text = "厅字幕运行中：有人说话自动出大字"
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
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** 只发停止信号，绝不 join（听幕教训）。 */
    private fun stopEngine() {
        running.set(false)
        engineThread?.interrupt()
        caption?.stop()
    }
}
