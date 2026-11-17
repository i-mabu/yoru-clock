package jp.yoru.clock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ===== アナログ時計カスタムView (目盛り・時針・分針・秒針) =====
class AnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hourPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val minPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val secPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var density = 1f

    init {
        density = resources.displayMetrics.density
        facePaint.color = Color.parseColor("#161D38")
        facePaint.style = Paint.Style.FILL
        tickPaint.color = Color.parseColor("#9AA3C7")
        hourPaint.color = Color.parseColor("#F2F3FA")
        hourPaint.strokeCap = Paint.Cap.ROUND
        minPaint.color = Color.parseColor("#8FA8FF")
        minPaint.strokeCap = Paint.Cap.ROUND
        secPaint.color = Color.parseColor("#FFC46B")
        secPaint.strokeCap = Paint.Cap.ROUND
        numPaint.color = Color.parseColor("#F2F3FA")
        numPaint.textAlign = Paint.Align.CENTER
        centerPaint.color = Color.parseColor("#FFC46B")
        centerPaint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f - 8f * density
        if (radius <= 0) return

        canvas.drawCircle(cx, cy, radius, facePaint)

        // 目盛り (60本、5分刻みは長く太く)。線幅・余白は density 換算
        for (i in 0 until 60) {
            val ang = Math.toRadians((i * 6 - 90).toDouble())
            val big = i % 5 == 0
            val len = if (big) radius * 0.12f else radius * 0.06f
            tickPaint.strokeWidth = if (big) 5f * density else 2f * density
            val x1 = cx + cos(ang).toFloat() * (radius - len)
            val y1 = cy + sin(ang).toFloat() * (radius - len)
            val x2 = cx + cos(ang).toFloat() * (radius - 4f * density)
            val y2 = cy + sin(ang).toFloat() * (radius - 4f * density)
            canvas.drawLine(x1, y1, x2, y2, tickPaint)
        }

        // 数字 12 / 3 / 6 / 9
        numPaint.textSize = radius * 0.16f
        val nr = radius * 0.68f
        val fm = numPaint.fontMetrics
        val dy = -(fm.ascent + fm.descent) / 2f
        canvas.drawText("12", cx, cy - nr + dy, numPaint)
        canvas.drawText("3", cx + nr, cy + dy, numPaint)
        canvas.drawText("6", cx, cy + nr + dy, numPaint)
        canvas.drawText("9", cx - nr, cy + dy, numPaint)

        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val min = now.get(Calendar.MINUTE)
        val sec = now.get(Calendar.SECOND)
        val ms = now.get(Calendar.MILLISECOND)

        val secF = sec + ms / 1000.0
        val minF = min + secF / 60.0
        val hourF = (hour % 12) + minF / 60.0

        drawHand(canvas, cx, cy, hourF / 12.0 * 360.0, radius * 0.48f, radius * 0.045f, hourPaint)
        drawHand(canvas, cx, cy, minF / 60.0 * 360.0, radius * 0.70f, radius * 0.032f, minPaint)
        drawHand(canvas, cx, cy, secF / 60.0 * 360.0, radius * 0.80f, radius * 0.014f, secPaint)

        canvas.drawCircle(cx, cy, radius * 0.045f, centerPaint)
    }

    private fun drawHand(canvas: Canvas, cx: Float, cy: Float, degrees: Double, len: Float, width: Float, paint: Paint) {
        val ang = Math.toRadians(degrees - 90)
        paint.strokeWidth = width * 2f
        val x = cx + cos(ang).toFloat() * len
        val y = cy + sin(ang).toFloat() * len
        canvas.drawLine(cx, cy, x, y, paint)
    }
}
