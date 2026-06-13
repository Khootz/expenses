package hk.expenses.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Donut chart for category spending; center shows the month's total. */
class DonutChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    companion object {
        // Shared with the legend rows built in MainActivity (index-matched).
        val PALETTE = intArrayOf(
            0xFF4F76F6.toInt(), 0xFF30A46C.toInt(), 0xFFF7941D.toInt(),
            0xFFE5484D.toInt(), 0xFF7B5EA7.toInt(), 0xFF00B8D9.toInt(),
            0xFFF2C94C.toInt(), 0xFFFF7AB2.toInt(), 0xFF607D8B.toInt(),
            0xFF8D6E63.toInt(), 0xFF26A69A.toInt(), 0xFFAB47BC.toInt()
        )
    }

    data class Slice(val label: String, val cents: Long)

    private var slices: List<Slice> = emptyList()
    private var centerTop = ""
    private var centerSub = ""

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(20f)
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(12f)
        color = 0xFF888888.toInt()
    }
    private val rect = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    fun setData(data: List<Slice>, top: String, sub: String) {
        slices = data.filter { it.cents > 0 }
        centerTop = top
        centerSub = sub
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = dp(26f)
        arcPaint.strokeWidth = stroke
        val size = min(w, h) - stroke - dp(4f)
        rect.set((w - size) / 2, (h - size) / 2, (w + size) / 2, (h + size) / 2)

        // Resolve text color against dark/light theme.
        topPaint.color = if (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        ) 0xFFEEEEEE.toInt() else 0xFF1A1A2E.toInt()

        val total = slices.sumOf { it.cents }
        if (total <= 0) {
            arcPaint.color = 0x14888888
            canvas.drawArc(rect, 0f, 360f, false, arcPaint)
        } else {
            val gapDeg = if (slices.size > 1) 2f else 0f
            var start = -90f
            slices.forEach { s ->
                val sweep = 360f * s.cents / total - gapDeg
                arcPaint.color = Categories.color(s.label)
                canvas.drawArc(rect, start, sweep.coerceAtLeast(1f), false, arcPaint)
                start += sweep + gapDeg
            }
        }

        val cx = w / 2
        val cy = h / 2
        canvas.drawText(centerTop, cx, cy - dp(2f), topPaint)
        canvas.drawText(centerSub, cx, cy + dp(16f), subPaint)
    }
}
