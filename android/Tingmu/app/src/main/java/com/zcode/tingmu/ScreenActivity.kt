package com.zcode.tingmu

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 听幕·屏：透明字幕层，三模式（环境字幕/视频字幕/乘车报站）。
 *  首屏铁律与听幕一致——打开必见大开关。 */
class ScreenActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var root: LinearLayout
    private lateinit var startBtn: Button
    private lateinit var statusText: TextView
    private lateinit var permText: TextView
    private lateinit var permBtn: Button
    private lateinit var kwEdit: EditText
    private lateinit var stEdit: EditText
    private lateinit var vibBtn: Button
    private lateinit var stayBtn: Button
    private lateinit var fontBtn: Button
    private lateinit var themeBtn: Button
    private lateinit var fontDownBtn: Button
    private lateinit var modeEnvBtn: Button
    private lateinit var modeMediaBtn: Button
    private lateinit var modeRideBtn: Button
    private lateinit var kwLabel: TextView
    private lateinit var stLabel: TextView

    private var vibOn = true
    private var stayIdx = 1
    private var fontIdx = 0
    private var themeIdx = 1
    private var mode = "env"

    private val stays = longArrayOf(5000, 10000, 15000, 20000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("screen_prefs", MODE_PRIVATE)
        vibOn = prefs.getBoolean("vibrate", true)
        stayIdx = prefs.getInt("stay_idx", 1)
        fontIdx = prefs.getInt("font_idx", 0)
        themeIdx = prefs.getInt("theme_idx", 1)
        mode = prefs.getString("mode", "env") ?: "env"
        buildUi()
        // P0-1 修复：编辑框回填已保存的触发词/站名表——否则任何一次启动的 savePrefs()
        // 会用空编辑框把用户粘贴的站名表覆写清空（ride 模式核心数据丢失）
        prefs.getString("keywords", null)?.let { if (it.isNotBlank()) kwEdit.setText(it) }
        prefs.getString("stations", null)?.let { if (it.isNotBlank()) stEdit.setText(it) }
        applyTheme()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 自测入口：adb shell am start -n <pkg>/com.zcode.tingmu.ScreenActivity
     *  --ez selftest true --es mode env|media|ride [--es wav audio/xxx.wav] */
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("selftest", false) == true) {
            i.getStringExtra("mode")?.let { mode = it }
            ui.post { startEngine(selftest = true, wav = i.getStringExtra("wav")) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this).apply {
            text = ScreenText.title + "  v" + packageManager
                .getPackageInfo(packageName, 0).versionName
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        startBtn = bigButton(modeLabel())
        startBtn.setOnClickListener { toggleRun() }
        root.addView(startBtn)

        statusText = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, 0) }
        root.addView(statusText)

        // 模式单选行
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeEnvBtn = modeButton(ScreenText.modeEnv, "env")
        modeMediaBtn = modeButton(ScreenText.modeMedia, "media")
        modeRideBtn = modeButton(ScreenText.modeRide, "ride")
        modeRow.addView(modeEnvBtn); modeRow.addView(modeMediaBtn); modeRow.addView(modeRideBtn)
        root.addView(modeRow)

        permText = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, dp(4)) }
        root.addView(permText)
        permBtn = smallButton(ScreenText.goGrant)
        permBtn.setOnClickListener { requestOverlay() }
        root.addView(permBtn)

        root.addView(sectionLabel(ScreenText.settingsTitle))

        kwLabel = fieldLabel(ScreenText.keywords)
        root.addView(kwLabel)
        kwEdit = multilineEdit(ScreenText.kwHint)
        root.addView(kwEdit)

        stLabel = fieldLabel(ScreenText.stations)
        root.addView(stLabel)
        stEdit = multilineEdit(ScreenText.stHint)
        root.addView(stEdit)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        vibBtn = smallButton(ScreenText.vibrate + " " + onOff(vibOn))
        vibBtn.setOnClickListener {
            vibOn = !vibOn
            vibBtn.text = ScreenText.vibrate + " " + onOff(vibOn)
        }
        stayBtn = smallButton(ScreenText.stay + " " + stays[stayIdx] / 1000 + "s")
        stayBtn.setOnClickListener {
            stayIdx = (stayIdx + 1) % stays.size
            stayBtn.text = ScreenText.stay + " " + stays[stayIdx] / 1000 + "s"
        }
        row1.addView(vibBtn); row1.addView(stayBtn)
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fontDownBtn = smallButton("A-")
        fontDownBtn.setOnClickListener {
            fontIdx = (fontIdx + FONT_SIZES.size - 1) % FONT_SIZES.size
            applyTheme(); savePrefs(); notifyRunning()
        }
        fontBtn = smallButton("A+")
        fontBtn.setOnClickListener {
            fontIdx = (fontIdx + 1) % FONT_SIZES.size
            applyTheme(); savePrefs(); notifyRunning()
        }
        themeBtn = smallButton(ScreenText.theme)
        themeBtn.setOnClickListener {
            themeIdx = (themeIdx + 1) % THEMES.size
            applyTheme(); savePrefs(); notifyRunning()
        }
        row2.addView(fontDownBtn); row2.addView(fontBtn); row2.addView(themeBtn)
        root.addView(row2)
        // 透明度不在设置页调——直接点字幕框上的拉条实时调（v0.7 移入悬浮窗）

        val saveBtn = smallButton(ScreenText.save)
        saveBtn.setOnClickListener { savePrefs(); toast(ScreenText.saved) }
        root.addView(saveBtn)

        root.addView(sectionLabel(ScreenText.testTitle))
        val envBtn = smallButton(ScreenText.envTest)
        envBtn.setOnClickListener { startEngine(selftest = true, wav = "audio/test_zh_16k.wav", forceMode = "env") }
        root.addView(envBtn)
        val mediaBtn = smallButton(ScreenText.mediaTest)
        mediaBtn.setOnClickListener { startEngine(selftest = true, wav = "audio/station_test_16k.wav", forceMode = "media") }
        root.addView(mediaBtn)
        val rideBtn = smallButton(ScreenText.rideTest)
        rideBtn.setOnClickListener { startEngine(selftest = true, wav = "audio/station_test_16k.wav", forceMode = "ride") }
        root.addView(rideBtn)
        val negBtn = smallButton(ScreenText.negTest)
        negBtn.setOnClickListener { startEngine(selftest = true, wav = "audio/test_zh_16k.wav", forceMode = "ride") }
        root.addView(negBtn)

        val hint = TextView(this).apply {
            text = ScreenText.hint
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(hint)
    }

    private fun modeButton(label: String, m: String): Button = smallButton(label).apply {
        setOnClickListener {
            mode = m
            savePrefs()
            refreshStatus()
        }
    }

    private fun modeLabel(): String = when (mode) {
        "media" -> ScreenText.startMedia
        "ride" -> ScreenText.startRide
        else -> ScreenText.startEnv
    }

    /** 设置页改字号/配色后：字幕在运行中就实时生效（Service ACTION_REFRESH），不用重启 */
    private fun notifyRunning() {
        if (ScreenService.isRunning) {
            startService(Intent(this, ScreenService::class.java).setAction(ScreenService.ACTION_REFRESH))
        }
    }

    private fun bigButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        minHeight = dp(76)
        setTextColor(Color.WHITE)
        setPadding(0, dp(14), 0, dp(14))
    }

    private fun smallButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 14f
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    private fun sectionLabel(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun fieldLabel(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 13f
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun multilineEdit(hint: String): EditText = EditText(this).apply {
        setSingleLine(false)
        minLines = 3
        textSize = 14f
        setHint(hint)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun applyTheme() {
        val th = THEMES[themeIdx]
        root.setBackgroundColor(th.bg)
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v is TextView && v !is EditText) v.setTextColor(th.fg)
            if (v is LinearLayout) {
                for (j in 0 until v.childCount) {
                    val c = v.getChildAt(j)
                    if (c is TextView && c !is EditText) c.setTextColor(th.fg)
                }
            }
        }
        val runningNow = ScreenService.isRunning
        startBtn.background = roundRect(if (runningNow) 0xFFCC3322.toInt() else 0xFF0A6CFF.toInt())
        startBtn.textSize = FONT_SIZES[fontIdx] * 0.42f
        startBtn.typeface = Typeface.DEFAULT_BOLD
        // 模式按钮：选中高亮蓝
        modeEnvBtn.background = modeBg("env", runningNow)
        modeMediaBtn.background = modeBg("media", runningNow)
        modeRideBtn.background = modeBg("ride", runningNow)
        for (b in arrayOf(modeEnvBtn, modeMediaBtn, modeRideBtn)) b.setTextColor(Color.WHITE)
        kwEdit.background = roundRect(0x22000000)
        stEdit.background = roundRect(0x22000000)
        kwEdit.setTextColor(th.fg); kwEdit.setHintTextColor(th.fg and 0x66FFFFFF or 0x66000000)
        stEdit.setTextColor(th.fg); stEdit.setHintTextColor(th.fg and 0x66FFFFFF or 0x66000000)
        statusText.textSize = 14f
    }

    private fun modeBg(m: String, runningNow: Boolean): GradientDrawable =
        roundRect(
            when {
                ScreenService.runningMode == m && runningNow -> 0xFFCC3322.toInt()
                mode == m -> 0xFF0A6CFF.toInt()
                else -> 0x33888888
            }
        )

    private fun roundRect(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = 16 * resources.displayMetrics.density
        d.setColor(color)
        return d
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun onOff(on: Boolean) = if (on) ScreenText.on else ScreenText.off

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- 状态与启动 ----------

    private fun refreshStatus() {
        val runningNow = ScreenService.isRunning
        applyTheme()
        startBtn.text = if (runningNow) ScreenText.stop else modeLabel()
        statusText.text = if (runningNow) {
            when (ScreenService.runningMode) {
                "env" -> ScreenText.runningEnv
                "media" -> ScreenText.runningMedia
                else -> ScreenText.runningRide
            }
        } else ScreenText.idleHint
        // ride 独有设置项显隐
        val rideOnly = mode == "ride"
        kwLabel.visibility = if (rideOnly) View.VISIBLE else View.GONE
        stLabel.visibility = if (rideOnly) View.VISIBLE else View.GONE
        kwEdit.visibility = if (rideOnly) View.VISIBLE else View.GONE
        stEdit.visibility = if (rideOnly) View.VISIBLE else View.GONE
        val overlayOk = Settings.canDrawOverlays(this)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        permText.text = ScreenText.permOverlay + " " + onOff(overlayOk) + "\n" +
            ScreenText.permMic + " " + onOff(micOk)
        if (overlayOk && (micOk || mode == "media")) permBtn.visibility = View.GONE
        else permBtn.visibility = View.VISIBLE
    }

    private fun toggleRun() {
        if (ScreenService.isRunning) {
            startService(Intent(this, ScreenService::class.java).setAction(ScreenService.ACTION_STOP))
            ui.postDelayed({ refreshStatus() }, 600)
            return
        }
        startEngine(selftest = false, wav = null)
    }

    private fun startEngine(selftest: Boolean, wav: String?, forceMode: String? = null) {
        forceMode?.let { mode = it }
        if (!Settings.canDrawOverlays(this)) {
            toast(ScreenText.needOverlay)
            requestOverlay()
            return
        }
        // 视频字幕捕获内部音频，不需要麦克风权限；其余模式需要
        if (mode != "media" &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            // P0-2 修复：非 selftest 存 null（原存空串，授权回调 wav != null 误判成自测，
            // 首次授权后点启动报「测试音频缺失」）
            pendingAfterPerm = if (selftest) wav else null
            // P1-4：一并请求通知权限（Android 13+ 不请求则通知栏「停止」按钮不可见）
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
            return
        }
        if (!selftest) {
            if (mode == "ride") {
                val stations = stEdit.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (stations.isEmpty()) {
                    toast(ScreenText.stationsEmpty)
                    return
                }
            }
            savePrefs()
        }
        if (mode == "media") {
            // MediaProjection 系统确认弹窗（Android 14+ 每次启动都会弹，属预期）
            pendingProjSelftest = if (selftest) wav else null
            val mm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mm.createScreenCaptureIntent(), 2)
            return
        }
        launchService(selftest, wav, -1, null)
    }

    private var pendingAfterPerm: String? = null
    private var pendingProjSelftest: String? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) {
            if (resultCode == RESULT_OK && data != null) {
                val wav = pendingProjSelftest
                pendingProjSelftest = null
                launchService(selftest = wav != null, wav = wav, projCode = resultCode, projData = data)
            } else {
                pendingProjSelftest = null
                toast(ScreenText.projDenied)
            }
        }
    }

    private fun launchService(selftest: Boolean, wav: String?, projCode: Int, projData: Intent?) {
        val i = Intent(this, ScreenService::class.java).putExtra(ScreenService.EXTRA_MODE, mode)
        if (selftest) {
            i.putExtra(ScreenService.EXTRA_SELFTEST, true)
            wav?.let { i.putExtra(ScreenService.EXTRA_WAV, it) }
        }
        // MediaProjection 的 resultData 走同进程静态传递（Intent-as-extra 在 startForegroundService 上实测会丢）
        ProjHolder.code = projCode
        ProjHolder.data = projData
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        ui.postDelayed({ refreshStatus() }, 800)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val wav = pendingAfterPerm
            pendingAfterPerm = null
            // 空串与 null 同等对待（历史 bug 防御：空 wav 会把真实启动误判成自测）
            startEngine(selftest = !wav.isNullOrEmpty(), wav = wav?.takeIf { it.isNotEmpty() })
        }
        refreshStatus()
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } catch (_: Exception) {}
            }
        }
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("keywords", kwEdit.text.toString())
            .putString("stations", stEdit.text.toString())
            .putBoolean("vibrate", vibOn)
            .putInt("stay_idx", stayIdx)
            .putInt("font_idx", fontIdx)
            .putInt("theme_idx", themeIdx)
            .putLong("stay_ms", stays[stayIdx])
            .putString("mode", mode)
            .apply()
    }
}

/** MediaProjection 授权结果的同进程传递（ScreenActivity → ScreenService）。
 *  Intent-as-extra 在 startForegroundService 上实测会丢，故用静态持有。 */
object ProjHolder {
    var code: Int = -1
    var data: Intent? = null
}

/** 文案集中 + unicode 转义（工程纪律：避免长中文串直写文件）。 */
object ScreenText {
    val title = "\u542c\u5e55\u00b7\u5c4f" // 听幕·屏
    val stop = "\u25a0 \u505c\u6b62" // ■ 停止
    val modeEnv = "\u73af\u5883\u5b57\u5e55" // 环境字幕
    val modeMedia = "\u89c6\u9891\u5b57\u5e55" // 视频字幕
    val modeRide = "\u4e58\u8f66\u62a5\u7ad9" // 乘车报站
    val startEnv = "\u5f00\u59cb\u73af\u5883\u5b57\u5e55" // 开始环境字幕
    val startMedia = "\u5f00\u59cb\u89c6\u9891\u5b57\u5e55" // 开始视频字幕
    val startRide = "\u5f00\u59cb\u4e58\u8f66\u5b57\u5e55" // 开始乘车字幕
    val runningEnv = "\u8fd0\u884c\u4e2d\uff1a\u8eab\u8fb9\u58f0\u97f3\u5b9e\u65f6\u53d8\u5b57\u5e55\uff0c\u5df2\u53e0\u52a0\u5728\u5f53\u524d\u754c\u9762\u4e0a" // 运行中：身边声音实时变字幕
    val runningMedia = "\u8fd0\u884c\u4e2d\uff1a\u6b63\u5728\u7ed9\u624b\u673a\u91cc\u64ad\u653e\u7684\u89c6\u9891\u914d\u5b57\u5e55" // 运行中：正在给手机里播放的视频配字幕
    val runningRide = "\u8fd0\u884c\u4e2d\uff1a\u76d1\u542c\u62a5\u7ad9\uff0c\u53ea\u663e\u793a\u7ad9\u540d" // 运行中：监听报站，只显示站名
    val idleHint = "\u9009\u6a21\u5f0f\u2192\u70b9\u5927\u6309\u94ae\u3002\u5b57\u5e55\u60ac\u6d6e\u5728\u4efb\u610f App \u4e0a\uff0c\u9759\u9ed8\u65f6\u81ea\u52a8\u9690\u85cf" // 选模式→点大按钮
    val hint = "\u73af\u5883\u5b57\u5e55=\u542c\u8eab\u8fb9\u58f0\u97f3\uff1b\u89c6\u9891\u5b57\u5e55=\u542c\u624b\u673a\u91cc\u64ad\u653e\u7684\u58f0\u97f3\uff08\u6bcf\u6b21\u542f\u52a8\u7cfb\u7edf\u4f1a\u5f39\u786e\u8ba4\uff09\uff1b\u4e58\u8f66\u62a5\u7ad9=\u7701\u7535\u76d1\u542c\uff0c\u53ea\u663e\u793a\u7ad9\u540d\u3002\u8bc6\u522b\u5168\u79bb\u7ebf\uff0c\u97f3\u9891\u4e0d\u51fa\u624b\u673a\u3002" // 三模式说明
    val permOverlay = "\u60ac\u6d6e\u7a97\u6743\u9650" // 悬浮窗权限
    val permMic = "\u9ea6\u514b\u98ce\u6743\u9650" // 麦克风权限
    val goGrant = "\u53bb\u6388\u6743" // 去授权
    val needOverlay = "\u9700\u8981\u60ac\u6d6e\u7a97\u6743\u9650\u624d\u80fd\u5728\u89c6\u9891\u4e0a\u53e0\u52a0\u5b57\u5e55" // 需要悬浮窗权限
    val projDenied = "\u672a\u6388\u6743\u5c4f\u5e55\u97f3\u9891\u6355\u83b7" // 未授权屏幕音频捕获
    val settingsTitle = "\u8bbe\u7f6e" // 设置
    val testTitle = "\u9a8c\u8bc1" // 验证
    val keywords = "\u89e6\u53d1\u8bcd\uff08\u6bcf\u884c\u4e00\u4e2a\uff0c\u6c49\u5b57\uff09" // 触发词
    val stations = "\u7ad9\u540d\u8868\uff08\u4e00\u884c\u4e00\u7ad9\uff0c\u6c49\u5b57\uff09" // 站名表
    val kwHint = "\u4e0b\u4e00\u7ad9\n\u7ec8\u70b9\u7ad9\n\u8bf7\u4e0b\u8f66" // 下一站/终点站/请下车
    val stHint = "\u4eba\u6c11\u5e7f\u573a\n\u5357\u4eac\u4e1c\u8def" // 人民广场\n南京东路
    val vibrate = "\u9707\u52a8" // 震动
    val stay = "\u505c\u7559" // 停留
    val save = "\u4fdd\u5b58\u8bbe\u7f6e" // 保存设置
    val saved = "\u5df2\u4fdd\u5b58" // 已保存
    val envTest = "\u81ea\u6d4b\uff1a\u73af\u5883\u5b57\u5e55\uff08\u5185\u7f6e\u5bf9\u8bdd\u97f3\u9891\uff09" // 自测：环境字幕
    val mediaTest = "\u81ea\u6d4b\uff1a\u89c6\u9891\u5b57\u5e55\uff08\u672c\u673a\u81ea\u64ad\u81ea\u6355\uff09" // 自测：视频字幕（本机自播自捕）
    val rideTest = "\u81ea\u6d4b\uff1a\u62a5\u7ad9\u6b63\u4f8b" // 自测：报站正例
    val negTest = "\u8d1f\u4f8b\u81ea\u6d4b\uff1a\u666e\u901a\u5bf9\u8bdd\u4e0d\u5e94\u51fa\u7ad9\u540d" // 负例自测
    val stationsEmpty = "\u7ad9\u540d\u8868\u4e3a\u7a7a\uff0c\u5148\u7c98\u8d34\u4f60\u5e38\u5750\u7ebf\u8def\u7684\u7ad9\u540d" // 站名表为空
    val on = "\u5f00" // 开
    val off = "\u5173" // 关
    val theme = "\u914d\u8272" // 配色
    val bgAlpha = "\u900f\u660e\u5ea6" // 透明度
    val bgClear = "\u5168\u900f" // 全透
}
