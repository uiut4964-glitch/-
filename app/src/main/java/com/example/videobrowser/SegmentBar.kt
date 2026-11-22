package com.example.videobrowser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * شريط تقدم بأسلوب برامج إدارة التحميل: الشريط يمثل الملف كاملًا،
 * وكل اتصال يملأ جزءه ابتداءً من موضع بدايته.
 * البيانات: أزواج (بداية، نهاية) بين 0 و 1.
 */
class SegmentBar(ctx: Context) : View(ctx) {
    private var rg = FloatArray(0)
    private var indeterminate = false
    private val pulse = object : Runnable {
        override fun run() {
            if (indeterminate && isShown) {
                invalidate()
                postDelayed(this, 50L)
            }
        }
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDCE3EC.toInt() }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E6FE0.toInt() }
    private val rect = RectF()
    private val clip = Path()

    fun set(r: FloatArray) {
        if (!r.contentEquals(rg)) {
            rg = r
            invalidate()
        }
    }

    fun setIndeterminate(enabled: Boolean) {
        if (indeterminate == enabled) return
        indeterminate = enabled
        removeCallbacks(pulse)
        if (enabled && isShown) post(pulse)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (indeterminate) post(pulse)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pulse)
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = h / 2
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, rad, rad, bg)
        var hasProgressRange = false
        var rangeIndex = 0
        while (rangeIndex + 1 < rg.size) {
            if (rg[rangeIndex + 1] > rg[rangeIndex]) { hasProgressRange = true; break }
            rangeIndex += 2
        }
        if (indeterminate && !hasProgressRange) {
            if (w > 0f) {
                val band = w * 0.24f
                val phase = (android.os.SystemClock.uptimeMillis() % 1100L) / 1100f
                var left = phase * (w + band) - band
                if (layoutDirection == LAYOUT_DIRECTION_RTL) left = w - left - band
                rect.set(left, 0f, left + band, h)
                c.drawRoundRect(rect, rad, rad, fg)
            }
            return
        }
        if (rg.size < 2) return
        val minW = 2f * resources.displayMetrics.density
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        clip.reset()
        clip.addRoundRect(rect, rad, rad, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        var i = 0
        while (i + 1 < rg.size) {
            val a = rg[i].coerceIn(0f, 1f)
            val b = rg[i + 1].coerceIn(0f, 1f)
            if (b > a) {
                var x0 = a * w
                var x1 = maxOf(b * w, x0 + minW)
                if (rtl) { val t0 = w - x1; val t1 = w - x0; x0 = t0; x1 = t1 }
                rect.set(x0, 0f, x1, h)
                c.drawRect(rect, fg)
            }
            i += 2
        }
        c.restore()
    }
}
