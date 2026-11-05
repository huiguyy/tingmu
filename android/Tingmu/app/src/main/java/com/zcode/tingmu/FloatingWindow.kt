package com.zcode.tingmu

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 听幕·屏悬浮字幕（可交互 + 定宽）：
 *  - 窗口宽度锁定 = 屏宽 - 48dp，不随字幕内容变大变小
 *  - 下方大字两行 = 当前句；上方小字两行 = 历史（滚上去变小字）
 *  - 点字幕 → 控制条浮现（透明度拉条 + ✕），左右拉实时变透明，✕ 关闭字幕
 *  - 手指拖动移屏；横竖屏切换重算宽度并夹回屏内
 *  - 字号直接由 fontSp 控制（不用 autoSize：autoSize 会抢占字号，调字号时当前大字不变） */
class FloatingWindow(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var box: FrameLayout? = null
    private var histText: TextView? = null
    private var tv: TextView? = null
    private var panel: LinearLayout? = null
    private var seek: SeekBar? = null
    private var closeBtn: TextView? = null
    private var added = false
    private var winLp: WindowManager.LayoutParams? = null

    // 窗口基础 flags（setVisible 按可见性追加 NOT_TOUCHABLE）
    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    private var hideTask: Runnable? = null
    private var panelHideTask: Runnable? = null
    private var fontSp = 28f
    private var theme = THEMES[1]
    private var alphaPct = 60

    // 拖动状态
    private var absoluteMode = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    /** 点 ✕ 关闭字幕（Service 注入） */
    var onClose: (() -> Unit)? = null

    /** 透明度拉条停止拖动后回传（Service 存 prefs） */
    var onAlpha: ((Int) -> Unit)? = null

    private val dp get() = ctx.resources.displayMetrics.density
    private fun fixedWidth() = (screenW() - (48 * dp).toInt()).coerceAtLeast(200)

    fun show(fontSp: Float, theme: Theme, alphaPct: Int) {
        this.fontSp = fontSp
        this.theme = theme
        this.alphaPct = alphaPct.coerceIn(0, 100)
        ui.post { ensureAdded() }
    }

    private fun ensureAdded() {
        if (added) {
            applyStyle()
            return
        }
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (android.os.Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            fixedWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            baseFlags,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = (72 * dp).toInt()
        // 初始隐藏态：加触摸穿透（setVisible(true) 时去掉）
        lp.flags = baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        val frame = FrameLayout(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val hist = TextView(ctx).apply {
            typeface = Typeface.DEFAULT
            setShadowLayer(5f, 0f, 2f, Color.argb(200, 0, 0, 0))
            maxLines = 2
            visibility = View.GONE
        }
        val subtitle = TextView(ctx).apply {
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 2f, Color.argb(200, 0, 0, 0))
            maxLines = 2
        }
        val histLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        histLp.bottomMargin = (6 * dp).toInt()
        col.addView(hist, histLp)
        col.addView(subtitle)

        // 控制条：透明度拉条 + ✕（点击字幕浮现，5s 自动收起；INVISIBLE 占位防窗口跳动）
        val ctrl = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(0xD9000000.toInt()) }
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            visibility = View.INVISIBLE
            setTop((6 * dp).toInt())
        }
        val ctrlLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        ctrlLp.topMargin = (8 * dp).toInt()
        val sb = SeekBar(ctx).apply { max = 100; progress = alphaPct }
        val sbLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val close = TextView(ctx).apply {
            this.text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(0xCC555555.toInt()) }
        }
        val closeLp = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
        closeLp.marginStart = (8 * dp).toInt()
        ctrl.addView(sb, sbLp)
        ctrl.addView(close, closeLp)
        col.addView(ctrl, ctrlLp)

        frame.addView(col)
        frame.visibility = View.GONE

        frame.setOnTouchListener { _, e -> handleTouch(e) }
        // overlay 上 View 的 click 机制实测收不到，一律用 touch 判定
        close.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                android.util.Log.i("TingmuScreen", "FW_CLOSE_TAP")
                onClose?.invoke()
            }
            true
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    alphaPct = p.coerceIn(0, 100)
                    applyStyle()
                    schedulePanelHide()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) { schedulePanelHide() }
            override fun onStopTrackingTouch(s: SeekBar?) {
                onAlpha?.invoke(alphaPct)
                schedulePanelHide()
            }
        })

        manager.addView(frame, lp)
        wm = manager
        box = frame
        winLp = lp
        histText = hist
        tv = subtitle
        panel = ctrl
        seek = sb
        closeBtn = close
        added = true
        applyStyle()
    }

    /** 窗口可见性三联动：View 显隐 + 触摸穿透 flag。
     *  overlay 窗口的 touchable region 由窗口 frame 决定，View GONE 也拦触摸——
     *  隐藏时必须加 FLAG_NOT_TOUCHABLE，否则定宽空框会挡住底下 App 的点击。 */
    private fun setVisible(visible: Boolean) {
        val b = box ?: return
        val l = winLp ?: return
        val manager = wm ?: return
        b.visibility = if (visible) View.VISIBLE else View.GONE
        l.flags = if (visible)
            baseFlags
        else
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            manager.updateViewLayout(b, l)
        } catch (_: Exception) {
        }
    }

    private fun applyStyle() {
        val b = box ?: return
        val t = tv ?: return
        val h = histText ?: return
        val bg = GradientDrawable()
        bg.cornerRadius = 22 * dp
        val base = theme.bg and 0x00FFFFFF
        val a = (alphaPct.coerceIn(0, 100) * 255 / 100)
        bg.setColor((a shl 24) or base)
        b.background = bg
        b.setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
        val small = fontSp * 0.62f
        t.textSize = fontSp
        t.setTextColor(theme.fg)
        t.setLineSpacing((5 * dp).toFloat(), 1.18f)
        h.textSize = small
        h.setTextColor((theme.fg and 0x00FFFFFF) or 0xB3000000.toInt())
        h.alpha = 0.7f
        h.setLineSpacing((2 * dp).toFloat(), 1.15f)
    }

    /** 运行中改字号（设置页 A+/A- 经 Service ACTION_REFRESH 调进来，实时生效） */
    fun applyFontSize(sp: Float) {
        fontSp = sp
        ui.post { applyStyle() }
    }

    fun applyTheme(theme: Theme) {
        this.theme = theme
        ui.post { applyStyle() }
    }

    private fun screenW() = ctx.resources.displayMetrics.widthPixels
    private fun screenH() = ctx.resources.displayMetrics.heightPixels

    // ---------- 拖动 / 点击 ----------

    private fun handleTouch(e: MotionEvent): Boolean {
        val b = box ?: return false
        val lp = b.layoutParams as? WindowManager.LayoutParams ?: return false
        val manager = wm ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                downRawX = e.rawX
                downRawY = e.rawY
                if (!absoluteMode) {
                    val loc = IntArray(2)
                    b.getLocationOnScreen(loc)
                    startX = loc[0]
                    startY = loc[1]
                    lp.gravity = Gravity.TOP or Gravity.START
                    lp.x = startX
                    lp.y = startY
                    absoluteMode = true
                    manager.updateViewLayout(b, lp)
                } else {
                    startX = lp.x
                    startY = lp.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && (abs(dx) > 8 * dp || abs(dy) > 8 * dp)) dragging = true
                if (dragging) {
                    val w = b.width.coerceAtLeast(1)
                    val hh = b.height.coerceAtLeast(1)
                    lp.x = (startX + dx).roundToInt().coerceIn(0, max(0, screenW() - w))
                    lp.y = (startY + dy).roundToInt().coerceIn(0, max(0, screenH() - hh))
                    manager.updateViewLayout(b, lp)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) togglePanel()
            }
            MotionEvent.ACTION_CANCEL -> {}
        }
        return true
    }

    /** 点字幕：控制条（透明度拉条+✕）浮现，5s 自动收起。 */
    private fun togglePanel() {
        val p = panel ?: return
        panelHideTask?.let { ui.removeCallbacks(it) }
        p.visibility = if (p.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
        seek?.progress = alphaPct
        if (p.visibility == View.VISIBLE) schedulePanelHide()
    }

    private fun schedulePanelHide() {
        panelHideTask?.let { ui.removeCallbacks(it) }
        val task = Runnable { panel?.visibility = View.INVISIBLE }
        panelHideTask = task
        ui.postDelayed(task, 5000)
    }

    // ---------- 字幕更新 ----------

    /** 大字 current 最多两行（尾部对齐），小字 history 最多两行。
     *  无内容时窗口隐藏并触摸穿透（定宽空框不能挡住底下 App 的点击）。 */
    fun update(history: String, current: String) {
        ui.post {
            val b = box ?: return@post
            if (current.isBlank() && history.isBlank()) {
                tv?.text = ""
                histText?.text = ""
                setVisible(false)
                return@post
            }
            val density = ctx.resources.displayMetrics.density
            val usablePx = fixedWidth() - (52 * density).toInt()
            val colsBig = (usablePx / (fontSp * density)).toInt().coerceIn(6, 60) - 1
            val smallSp = fontSp * 0.62f
            val colsSmall = (usablePx / (smallSp * density)).toInt().coerceIn(8, 80) - 1
            val cur = if (current.length > colsBig * 2) current.takeLast(colsBig * 2) else current
            val hist = if (history.length > colsSmall * 2) history.takeLast(colsSmall * 2) else history
            tv?.text = cur
            val h = histText
            if (h != null) {
                h.text = hist
                h.visibility = if (hist.isBlank()) View.GONE else View.VISIBLE
            }
            if (b.visibility != View.VISIBLE) setVisible(true)
        }
    }

    fun scheduleHide(ms: Long) {
        hideTask?.let { ui.removeCallbacks(it) }
        val task = Runnable {
            panel?.visibility = View.INVISIBLE
            setVisible(false)
        }
        hideTask = task
        ui.postDelayed(task, ms)
    }

    fun hide() {
        hideTask?.let { ui.removeCallbacks(it) }
        ui.post {
            panel?.visibility = View.INVISIBLE
            setVisible(false)
        }
    }

    /** 横竖屏切换：锁定宽度跟随新屏宽，已拖动位置夹回屏内。 */
    fun onConfigChanged() {
        ui.post {
            applyStyle()
            val b = box ?: return@post
            val lp = b.layoutParams as? WindowManager.LayoutParams ?: return@post
            val manager = wm ?: return@post
            lp.width = fixedWidth()
            if (absoluteMode) {
                val w = b.width.coerceAtLeast(1)
                val hh = b.height.coerceAtLeast(1)
                lp.x = lp.x.coerceIn(0, max(0, screenW() - w))
                lp.y = lp.y.coerceIn(0, max(0, screenH() - hh))
            }
            manager.updateViewLayout(b, lp)
        }
    }

    fun remove() {
        ui.post {
            hideTask?.let { ui.removeCallbacks(it) }
            panelHideTask?.let { ui.removeCallbacks(it) }
            try {
                box?.let { wm?.removeView(it) }
            } catch (_: Exception) {
            }
            box = null
            histText = null
            tv = null
            panel = null
            seek = null
            closeBtn = null
            wm = null
            added = false
        }
    }
}