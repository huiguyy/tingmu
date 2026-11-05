package com.zcode.tingmu

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/** 听幕·屏悬浮字幕：平时完全透明（不遮挡短视频），命中后底部浮现大字，停留后自动淡出。
 *  窗口 NOT_TOUCHABLE——手势全部穿透给下层 App。 */
class FloatingWindow(private val ctx: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var box: FrameLayout? = null
    private var tv: TextView? = null
    private var added = false
    private var hideTask: Runnable? = null
    private var fontSp = 54f
    private var theme = THEMES[1] // 悬浮字幕默认黑底白字（视频上方可读）

    private val dp get() = ctx.resources.displayMetrics.density

    fun show(fontSp: Float, theme: Theme) {
        this.fontSp = fontSp
        this.theme = theme
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
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.y = (72 * dp).toInt()

        val frame = FrameLayout(ctx)
        val text = TextView(ctx)
        text.typeface = Typeface.DEFAULT_BOLD
        text.setShadowLayer(4f, 0f, 0f, Color.argb(120, 0, 0, 0))
        frame.addView(text)
        frame.visibility = android.view.View.GONE
        manager.addView(frame, lp)
        wm = manager
        box = frame
        tv = text
        added = true
        applyStyle()
    }

    private fun applyStyle() {
        val b = box ?: return
        val t = tv ?: return
        val bg = GradientDrawable()
        bg.cornerRadius = 22 * dp
        bg.setColor(theme.bg)
        b.background = bg
        b.setPadding((28 * dp).toInt(), (16 * dp).toInt(), (28 * dp).toInt(), (16 * dp).toInt())
        t.textSize = fontSp
        t.setTextColor(theme.fg)
        // 宽度上限 = 屏宽 - 两侧留白；放不下时自动缩字号（单行优先）
        t.maxWidth = ctx.resources.displayMetrics.widthPixels - (48 * dp).toInt()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            t.setAutoSizeTextTypeUniformWithConfiguration(
                22, fontSp.toInt(), 2, android.util.TypedValue.COMPLEX_UNIT_SP
            )
        }
    }

    /** 命中：更新文本并浮现。 */
    fun update(text: String) {
        ui.post {
            val b = box ?: return@post
            tv?.text = text
            b.visibility = android.view.View.VISIBLE
        }
    }

    /** 停留 ms 后淡出（隐藏窗口内容，窗口保留以便下次秒出）。 */
    fun scheduleHide(ms: Long) {
        hideTask?.let { ui.removeCallbacks(it) }
        val task = Runnable {
            box?.visibility = android.view.View.GONE
        }
        hideTask = task
        ui.postDelayed(task, ms)
    }

    fun hide() {
        hideTask?.let { ui.removeCallbacks(it) }
        ui.post { box?.visibility = android.view.View.GONE }
    }

    fun remove() {
        ui.post {
            hideTask?.let { ui.removeCallbacks(it) }
            try {
                box?.let { wm?.removeView(it) }
            } catch (_: Exception) {
            }
            box = null
            tv = null
            wm = null
            added = false
        }
    }
}
