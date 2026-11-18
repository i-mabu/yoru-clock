package jp.yoru.clock

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ===== 1. アナログ時計 (Compose Canvas 版: 旧 AnalogClockView と同じ描画) =====
@Composable
internal fun AnalogClock(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(size.width, size.height) / 2f - 8.dp.toPx()
        if (radius <= 0f) return@Canvas

        drawCircle(color = NightPanel, radius = radius, center = Offset(cx, cy))

        // 目盛り (60本、5分刻みは長く太く)
        for (i in 0 until 60) {
            val ang = Math.toRadians((i * 6 - 90).toDouble())
            val big = i % 5 == 0
            val len = if (big) radius * 0.12f else radius * 0.06f
            val w = if (big) 5.dp.toPx() else 2.dp.toPx()
            val x1 = cx + cos(ang).toFloat() * (radius - len)
            val y1 = cy + sin(ang).toFloat() * (radius - len)
            val x2 = cx + cos(ang).toFloat() * (radius - 4.dp.toPx())
            val y2 = cy + sin(ang).toFloat() * (radius - 4.dp.toPx())
            drawLine(NightSub, Offset(x1, y1), Offset(x2, y2), strokeWidth = w)
        }

        // 数字 12 / 3 / 6 / 9 (ネイティブCanvasで中央寄せ描画)
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#F2F3FA")
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = radius * 0.16f
            }
            val nr = radius * 0.68f
            val fm = paint.fontMetrics
            val dy = -(fm.ascent + fm.descent) / 2f
            drawText("12", cx, cy - nr + dy, paint)
            drawText("3", cx + nr, cy + dy, paint)
            drawText("6", cx, cy + nr + dy, paint)
            drawText("9", cx - nr, cy + dy, paint)
        }

        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val sec = now.get(Calendar.SECOND)
        val ms = now.get(Calendar.MILLISECOND)
        // 下位の単位を小数に繰り入れて各針を滑らかに動かす (秒針はミリ秒まで反映)
        val secF = sec + ms / 1000.0
        val minF = minute + secF / 60.0
        val hourF = (hour % 12) + minF / 60.0

        drawHand(cx, cy, hourF / 12.0 * 360.0, radius * 0.48f, radius * 0.045f, NightText)
        drawHand(cx, cy, minF / 60.0 * 360.0, radius * 0.70f, radius * 0.032f, NightAccent)
        drawHand(cx, cy, secF / 60.0 * 360.0, radius * 0.80f, radius * 0.014f, NightGold)

        drawCircle(color = NightGold, radius = radius * 0.045f, center = Offset(cx, cy))
    }
}

private fun DrawScope.drawHand(
    cx: Float, cy: Float, degrees: Double, len: Float, width: Float,
    color: androidx.compose.ui.graphics.Color
) {
    val ang = Math.toRadians(degrees - 90)
    val x = cx + cos(ang).toFloat() * len
    val y = cy + sin(ang).toFloat() * len
    drawLine(
        color = color,
        start = Offset(cx, cy),
        end = Offset(x, y),
        strokeWidth = width * 2f,
        cap = StrokeCap.Round
    )
}
