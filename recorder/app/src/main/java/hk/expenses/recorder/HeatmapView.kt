package hk.expenses.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil

/**
 * GitHub-contributions-style month heatmap: one rounded cell per day,
 * shaded by that day's spending relative to the month's heaviest day.
 */
class HeatmapView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var ym: YearMonth = YearMonth.now()
    private var totals: Map<Int, Long> = emptyMap() // dayOfMonth -> spend cents (>= 0)
    var onDayClick: ((Int) -> Unit)? = null

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF818CF8.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(10f)
        color = 0xFF888888.toInt()
    }

    // 0 = no spending, 1..4 = intensity ramp (light → deep red)
    private val ramp = intArrayOf(
        0x14888888, 0x40E5484D, 0x80E5484D.toInt(), 0xC0E5484D.toInt(), 0xFFC62828.toInt()
    )

    private val gap = dp(4f)
    private val headerH = dp(18f)
    private var cell = 0f

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun setData(month: YearMonth, dayTotals: Map<Int, Long>) {
        ym = month
        totals = dayTotals
        requestLayout()
        invalidate()
    }

    private fun offset() = ym.atDay(1).dayOfWeek.value - 1 // Monday-first
    private fun rows() = ceil((offset() + ym.lengthOfMonth()) / 7.0).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        cell = (w - gap * 6) / 7f
        val h = headerH + rows() * (cell + gap)
        setMeasuredDimension(w, h.toInt())
    }

    private fun bucket(day: Int, max: Long): Int {
        val v = totals[day] ?: 0L
        if (v <= 0 || max <= 0) return 0
        return 1 + (3L * v / max).toInt().coerceIn(0, 3)
    }

    override fun onDraw(canvas: Canvas) {
        val labels = arrayOf("M", "T", "W", "T", "F", "S", "S")
        for (i in 0..6) {
            canvas.drawText(labels[i], i * (cell + gap) + cell / 2, headerH - dp(6f), headerPaint)
        }
        val max = totals.values.maxOrNull() ?: 0L
        val today = LocalDate.now()
        val isThisMonth = YearMonth.from(today) == ym
        val r = RectF()
        for (day in 1..ym.lengthOfMonth()) {
            val idx = offset() + day - 1
            val col = idx % 7
            val row = idx / 7
            val x = col * (cell + gap)
            val y = headerH + row * (cell + gap)
            r.set(x, y, x + cell, y + cell)
            val b = bucket(day, max)
            cellPaint.color = ramp[b]
            canvas.drawRoundRect(r, dp(6f), dp(6f), cellPaint)
            if (isThisMonth && day == today.dayOfMonth) {
                canvas.drawRoundRect(r, dp(6f), dp(6f), ringPaint)
            }
            textPaint.color = if (b >= 3) 0xFFFFFFFF.toInt() else 0xFF888888.toInt()
            canvas.drawText(
                day.toString(), x + cell / 2,
                y + cell / 2 - (textPaint.ascent() + textPaint.descent()) / 2,
                textPaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            val col = (event.x / (cell + gap)).toInt().coerceIn(0, 6)
            val row = ((event.y - headerH) / (cell + gap)).toInt()
            val day = row * 7 + col - offset() + 1
            if (day in 1..ym.lengthOfMonth()) {
                onDayClick?.invoke(day)
                performClick()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
