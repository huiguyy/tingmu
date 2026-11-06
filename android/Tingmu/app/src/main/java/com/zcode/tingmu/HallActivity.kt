package com.zcode.tingmu

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 听幕·厅：电视/盒子上的零操作大字幕（子女装好、开机自启、平时透明、说话浮现）。
 *  交互对象是「子女」（装/调试用遥控器），使用者是「老人」（零操作）。
 *  首屏铁律与听幕一致——打开必见大开关；按钮均可聚焦，遥控器 D-pad 可导航。 */
class HallActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private lateinit var root: LinearLayout
    private lateinit var startBtn: Button
    private lateinit var statusText: TextView
    private lateinit var permText: TextView
    private lateinit var permBtn: Button
    private lateinit var fontBtn: Button
    private lateinit var fontDownBtn: Button
    private lateinit var themeBtn: Button
    private lateinit var stayBtn: Button
    private lateinit var sensBtn: Button
    private lateinit var autoStartBox: CheckBox

    private var stayIdx = 1
    private var fontIdx = 4
    private var themeIdx = 1
    private var sensIdx = 1

    private val stays = longArrayOf(5000, 8000, 12000, 20000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("hall_prefs", MODE_PRIVATE)
        stayIdx = prefs.getInt("stay_idx", 1)
        fontIdx = prefs.getInt("font_idx", 4)
        themeIdx = prefs.getInt("theme_idx", 1)
        sensIdx = prefs.getInt("sens_idx", 1)
        buildUi()
        applyTheme()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 自测入口：adb shell am start -n com.zcode.tingmu.hall/com.zcode.tingmu.HallActivity
     *  --ez selftest true [--es wav audio/xxx.wav]；boot=true 由 BootReceiver 传入 */
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra("boot", false) == true) {
            // 开机路径：直接拉服务，不弹 UI（老人零操作）
            startEngine(selftest = false, wav = null)
            finish()
            return
        }
        if (i?.getBooleanExtra("selftest", false) == true) {
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
            text = HallText.title + "  v" + packageManager.getPackageInfo(packageName, 0).versionName
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        startBtn = bigButton(HallText.start)
        startBtn.setOnClickListener { toggleRun() }
        root.addView(startBtn)

        statusText = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, 0) }
        root.addView(statusText)

        permText = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, dp(4)) }
        root.addView(permText)
        permBtn = smallButton(HallText.goGrant)
        permBtn.setOnClickListener { requestOverlay() }
        root.addView(permBtn)

        root.addView(sectionLabel(HallText.settingsTitle))

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
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
        themeBtn = smallButton(HallText.theme)
        themeBtn.setOnClickListener {
            themeIdx = (themeIdx + 1) % THEMES.size
            applyTheme(); savePrefs(); notifyRunning()
        }
        row1.addView(fontDownBtn); row1.addView(fontBtn); row1.addView(themeBtn)
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stayBtn = smallButton(HallText.stay + " " + stays[stayIdx] / 1000 + "s")
        stayBtn.setOnClickListener {
            stayIdx = (stayIdx + 1) % stays.size
            stayBtn.text = HallText.stay + " " + stays[stayIdx] / 1000 + "s"
            savePrefs(); notifyRunning()
        }
        sensBtn = smallButton(HallText.sens + " " + sensLabel(sensIdx))
        sensBtn.setOnClickListener {
            sensIdx = (sensIdx + 1) % HallService.SENS_VALUES.size
            sensBtn.text = HallText.sens + " " + sensLabel(sensIdx)
            savePrefs(); notifyRunning()
        }
        row2.addView(stayBtn); row2.addView(sensBtn)
        root.addView(row2)

        autoStartBox = CheckBox(this).apply {
            text = HallText.autoStart
            textSize = 14f
            isChecked = prefs.getBoolean("autostart", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("autostart", checked).apply()
            }
        }
        root.addView(autoStartBox)

        val saveBtn = smallButton(HallText.save)
        saveBtn.setOnClickListener { savePrefs(); toast(HallText.saved) }
        root.addView(saveBtn)

        root.addView(sectionLabel(HallText.testTitle))
        val envBtn = smallButton(HallText.envTest)
        envBtn.setOnClickListener { startEngine(selftest = true, wav = "audio/test_zh_16k.wav") }
        root.addView(envBtn)
        val pauseBtn = smallButton(HallText.pauseTest)
        pauseBtn.setOnClickListener { startEngine(selftest = true, wav = "audio/pause_test_16k.wav") }
        root.addView(pauseBtn)

        val hint = TextView(this).apply {
            text = HallText.hint
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(hint)
    }

    private fun sensLabel(i: Int): String = when (i) {
        0 -> HallText.sensLow
        2 -> HallText.sensHigh
        else -> HallText.sensMid
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

    private fun applyTheme() {
        val th = THEMES[themeIdx]
        root.setBackgroundColor(th.bg)
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            if (v is TextView) v.setTextColor(th.fg)
            if (v is LinearLayout) {
                for (j in 0 until v.childCount) {
                    val c = v.getChildAt(j)
                    if (c is TextView) c.setTextColor(th.fg)
                }
            }
        }
        autoStartBox.setTextColor(th.fg)
        val runningNow = HallService.isRunning
        startBtn.background = roundRect(if (runningNow) 0xFFCC3322.toInt() else 0xFF0A6CFF.toInt())
        startBtn.textSize = FONT_SIZES[fontIdx] * 0.42f
        startBtn.typeface = Typeface.DEFAULT_BOLD
    }

    private fun roundRect(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = 16 * resources.displayMetrics.density
        d.setColor(color)
        return d
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- 状态与启动 ----------

    private fun refreshStatus() {
        val runningNow = HallService.isRunning
        applyTheme()
        startBtn.text = if (runningNow) HallText.stop else HallText.start
        statusText.text = if (runningNow) HallText.running else HallText.idleHint
        val overlayOk = Settings.canDrawOverlays(this)
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        permText.text = HallText.permOverlay + " " + onOff(overlayOk) + "\n" +
            HallText.permMic + " " + onOff(micOk)
        if (overlayOk && micOk) permBtn.visibility = View.GONE else permBtn.visibility = View.VISIBLE
    }

    private fun onOff(on: Boolean) = if (on) HallText.on else HallText.off

    private fun toggleRun() {
        if (HallService.isRunning) {
            startService(Intent(this, HallService::class.java).setAction(HallService.ACTION_STOP))
            ui.postDelayed({ refreshStatus() }, 600)
            return
        }
        startEngine(selftest = false, wav = null)
    }

    private fun startEngine(selftest: Boolean, wav: String?) {
        if (!Settings.canDrawOverlays(this)) {
            toast(HallText.needOverlay)
            requestOverlay()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
            return
        }
        savePrefs()
        val i = Intent(this, HallService::class.java)
        if (selftest) {
            i.putExtra(HallService.EXTRA_SELFTEST, true)
            wav?.let { i.putExtra(HallService.EXTRA_WAV, it) }
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        ui.postDelayed({ refreshStatus() }, 800)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 屏 v0.8 教训：只认麦克风授权结果，通知权限拒绝不阻塞
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startEngine(selftest = false, wav = null)
        }
        refreshStatus()
    }

    private fun requestOverlay() {
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

    private fun savePrefs() {
        prefs.edit()
            .putInt("stay_idx", stayIdx)
            .putInt("font_idx", fontIdx)
            .putInt("theme_idx", themeIdx)
            .putInt("sens_idx", sensIdx)
            .putLong("stay_ms", stays[stayIdx])
            .apply()
        notifyRunning()
    }

    private fun notifyRunning() {
        if (HallService.isRunning) {
            startService(Intent(this, HallService::class.java).setAction(HallService.ACTION_REFRESH))
        }
    }
}

/** 文案集中 + unicode 转义（工程纪律）。 */
object HallText {
    val title = "\u542c\u5e55\u00b7\u5385" // 听幕·厅
    val start = "\u5f00\u59cb\u5385\u5b57\u5e55" // 开始厅字幕
    val stop = "\u25a0 \u505c\u6b62" // ■ 停止
    val running = "\u8fd0\u884c\u4e2d\uff1a\u6709\u4eba\u8bf4\u8bdd\u81ea\u52a8\u51fa\u5927\u5b57\uff0c\u5b89\u9759\u81ea\u52a8\u6de1\u51fa" // 运行中
    val idleHint = "\u88c5\u597d\u540e\u4e0d\u7528\u7ba1\uff1a\u7535\u89c6\u5f00\u673a\u81ea\u52a8\u5de5\u4f5c\uff0c\u6709\u4eba\u8bf4\u8bdd\u5c4f\u5e55\u51fa\u5927\u5b57" // 装好后不用管
    val hint = "\u5b57\u5e55\u60ac\u6d6e\u5728\u7535\u89c6\u753b\u9762\u4e0a\uff0c\u5e73\u65f6\u5b8c\u5168\u900f\u660e\uff1b\u8bc6\u522b\u5168\u79bb\u7ebf\uff0c\u58f0\u97f3\u4e0d\u51fa\u7535\u89c6\u3002\u62d6\u52a8\u5b57\u5e55\u53ef\u6362\u4f4d\u7f6e\uff0c\u70b9\u5b57\u5e55\u53ef\u8c03\u900f\u660e\u5ea6\u3002" // 说明
    val permOverlay = "\u60ac\u6d6e\u7a97\u6743\u9650" // 悬浮窗权限
    val permMic = "\u9ea6\u514b\u98ce\u6743\u9650" // 麦克风权限
    val goGrant = "\u53bb\u6388\u6743" // 去授权
    val needOverlay = "\u9700\u8981\u60ac\u6d6e\u7a97\u6743\u9650\u624d\u80fd\u5728\u7535\u89c6\u4e0a\u53e0\u52a0\u5b57\u5e55" // 需要悬浮窗权限
    val settingsTitle = "\u8bbe\u7f6e" // 设置
    val testTitle = "\u9a8c\u8bc1" // 验证
    val stay = "\u505c\u7559" // 停留
    val sens = "\u7075\u654f\u5ea6" // 灵敏度
    val sensLow = "\u4f4e" // 低
    val sensMid = "\u4e2d" // 中
    val sensHigh = "\u9ad8" // 高
    val autoStart = "\u5f00\u673a\u81ea\u52a8\u542f\u52a8\uff08\u63a8\u8350\uff09" // 开机自动启动（推荐）
    val save = "\u4fdd\u5b58\u8bbe\u7f6e" // 保存设置
    val saved = "\u5df2\u4fdd\u5b58" // 已保存
    val envTest = "\u81ea\u6d4b\uff1a\u5bf9\u8bdd\u97f3\u9891" // 自测：对话音频
    val pauseTest = "\u81ea\u6d4b\uff1a\u4e24\u53e5\u5e26\u505c\u987f" // 自测：两句带停顿
    val on = "\u5f00" // 开
    val off = "\u5173" // 关
    val theme = "\u914d\u8272" // 配色
}
