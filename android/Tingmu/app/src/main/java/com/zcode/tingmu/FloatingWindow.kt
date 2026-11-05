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
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** 听幕·屏悬浮字幕（双层级 + 可交互）：
 *  - 下方大字两行 = 当前句；上方小字两行 = 刚滚上去的历史（滚上去就变小字）
 *  - 背景透明度可调 0=全透 ~ 100=黑底；最多两行、尾部对齐
 *  - 手指拖动移屏；点击字幕 → 右上角 ✕ → 点 ✕ 关闭字幕（onClose）
 *  - 横竖屏切换重算宽度并夹回屏内 */
class FloatingWindow(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var box: FrameLayout? = null
    private var column: LinearLayout? = null
    private var histText: TextView? = null
    private var tv: TextView? = null
    private var closeBtn: TextView? = null
    private var added = false
    private var hideTask: Runnable? = null
    private var closeHideTask: Runnable? = null
    private var fontSp = 28f
    private var theme = THEMES[1]
    private var alphaPct = 70

    // 拖动状态
    private var absoluteMode = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    /** 点 ✕ 关闭字幕（Service 注入） */
    var onClose: (() -> Unit)? = null

    private val dp get() = ctx.resources.displayMetrics.density

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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = (72 * dp).toInt()

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
        col.addView(hist)
        col.addView(subtitle)
        val close = TextView(ctx).apply {
            this.text = "✕"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = 15 * dp; setColor(0xCC000000.toInt()) }
            visibility = View.GONE
        }
        val closeLp = FrameLayout.LayoutParams((30 * dp).toInt(), (30 * dp).toInt())
        closeLp.gravity = Gravity.END or Gravity.TOP
        closeLp.setMargins(0, (-12 * dp).toInt(), (-12 * dp).toInt(), 0)
        frame.addView(col)
        frame.addView(close, closeLp)
        frame.visibility = View.GONE

        frame.setOnTouchListener { _, e -> handleTouch(e) }
        close.setOnClickListener { onClose?.invoke() }

        manager.addView(frame, lp)
        wm = manager
        box = frame
        column = col
        histText = hist
        tv = subtitle
        closeBtn = close
        added = true
        applyStyle()
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
        b.setPadding((28 * dp).toInt(), (16 * dp).toInt(), (28 * dp).toInt(), (16 * dp).toInt())
        val small = fontSp * 0.62f
        t.textSize = fontSp
        t.setTextColor(theme.fg)
        t.maxWidth = screenW() - (48 * dp).toInt()
        h.textSize = small
        h.setTextColor((theme.fg and 0x00FFFFFF) or 0xB3000000.toInt())
        h.maxWidth = screenW() - (48 * dp).toInt()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            t.setAutoSizeTextTypeUniformWithConfiguration(
                22, fontSp.toInt(), 2, android.util.TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    private fun screenW() = ctx.resources.displayMetrics.widthPixels
    private fun screenH() = ctx.resources.displayMetrics.heightPixels

    // ---------- 拖动 / 点击 ----------

    private fun handleTouch(e: MotionEvent): Boolean {
        android.util.Log.i("TingmuScreen", "FW_TOUCH action=${e.actionMasked} raw=${e.rawX.toInt()},${e.rawY.toInt()}")
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
                if (!dragging) toggleCloseBtn()
            }
            MotionEvent.ACTION_CANCEL -> {}
        }
        return true
    }

    /** 点字幕：右上角 ✕ 浮现（5s 后自动隐藏）。 */
    private fun toggleCloseBtn() {
        val c = closeBtn ?: return
        closeHideTask?.let { ui.removeCallbacks(it) }
        c.visibility = if (c.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (c.visibility == View.VISIBLE) {
            val task = Runnable { c.visibility = View.GONE }
            closeHideTask = task
            ui.postDelayed(task, 5000)
        }
    }

    // ---------- 字幕更新 ----------

    /** 双层级：大字 current 最多两行（尾部对齐），小字 history 最多两行（历史滚出）。 */
    fun update(history: String, current: String) {
        ui.post {
            val b = box ?: return@post
            val density = ctx.resources.displayMetrics.density
            val usablePx = screenW() - (104 * density).toInt()
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
            b.visibility = View.VISIBLE
        }
    }

    fun scheduleHide(ms: Long) {
        hideTask?.let { ui.removeCallbacks(it) }
        val task = Runnable {
            box?.visibility = View.GONE
            closeBtn?.visibility = View.GONE
        }
        hideTask = task
        ui.postDelayed(task, ms)
    }

    fun hide() {
        hideTask?.let { ui.removeCallbacks(it) }
        ui.post {
            box?.visibility = View.GONE
            closeBtn?.visibility = View.GONE
        }
    }

    /** 横竖屏切换：重算宽度、把已拖动的窗口夹回屏内。 */
    fun onConfigChanged() {
        ui.post {
            applyStyle()
            val b = box ?: return@post
            val lp = b.layoutParams as? WindowManager.LayoutParams ?: return@post
            val manager = wm ?: return@post
            if (absoluteMode) {
                val w = b.width.coerceAtLeast(1)
                val hh = b.height.coerceAtLeast(1)
                lp.x = lp.x.coerceIn(0, max(0, screenW() - w))
                lp.y = lp.y.coerceIn(0, max(0, screenH() - hh))
                manager.updateViewLayout(b, lp)
            }
        }
    }

    fun remove() {
        ui.post {
            hideTask?.let { ui.removeCallbacks(it) }
            closeHideTask?.let { ui.removeCallbacks(it) }
            try {
                box?.let { wm?.removeView(it) }
            } catch (_: Exception) {
            }
            box = null
            column = null
            histText = null
            tv = null
            closeBtn = null
            wm = null
            added = false
        }
    }
}