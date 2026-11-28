package jp.yoru.clock

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ========================================
// 🕐 アナログ時計 (Compose Canvas 描画)
// ========================================
// 文字盤・目盛り・数字 (12/3/6/9)・3 本の針を描く。針は下位の単位を
// 小数に繰り入れて滑らかに動かす (秒針はミリ秒まで反映する)。
@Composable
internal fun AnalogClock(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = min(size.width, size.height) / 2f - 8.dp.toPx()
        if (radius <= 0f) return@Canvas
        val center = Offset(centerX, centerY)

        drawCircle(color = NightPanel, radius = radius, center = center)

        // 目盛り 60 本 (5 分刻みは長く・太く)
        for (i in 0 until 60) {
            val angle = Math.toRadians((i * 6 - 90).toDouble())
            val isHourTick = i % 5 == 0
            val tickLength = radius * if (isHourTick) 0.12f else 0.06f
            val tickWidth = if (isHourTick) 5.dp.toPx() else 2.dp.toPx()
            val outer = radius - 4.dp.toPx()
            drawLine(
                color = NightSub,
                start = Offset(
                    centerX + cos(angle).toFloat() * (radius - tickLength),
                    centerY + sin(angle).toFloat() * (radius - tickLength)
                ),
                end = Offset(
                    centerX + cos(angle).toFloat() * outer,
                    centerY + sin(angle).toFloat() * outer
                ),
                strokeWidth = tickWidth
            )
        }

        // 数字 12 / 3 / 6 / 9 (ネイティブ Canvas で中央寄せに描く)
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#F2F3FA")
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = radius * 0.16f
            }
            val numberRadius = radius * 0.68f
            val metrics = paint.fontMetrics
            val baselineShift = -(metrics.ascent + metrics.descent) / 2f
            drawText("12", centerX, centerY - numberRadius + baselineShift, paint)
            drawText("3", centerX + numberRadius, centerY + baselineShift, paint)
            drawText("6", centerX, centerY + numberRadius + baselineShift, paint)
            drawText("9", centerX - numberRadius, centerY + baselineShift, paint)
        }

        val now = Calendar.getInstance()
        val secondsWithMs = now.get(Calendar.SECOND) + now.get(Calendar.MILLISECOND) / 1000.0
        val minutesWithSeconds = now.get(Calendar.MINUTE) + secondsWithMs / 60.0
        val hoursWithMinutes = (now.get(Calendar.HOUR_OF_DAY) % 12) + minutesWithSeconds / 60.0

        drawClockHand(centerX, centerY, hoursWithMinutes / 12.0 * 360.0, radius * 0.48f, radius * 0.045f, NightText)
        drawClockHand(centerX, centerY, minutesWithSeconds / 60.0 * 360.0, radius * 0.70f, radius * 0.032f, NightAccent)
        drawClockHand(centerX, centerY, secondsWithMs / 60.0 * 360.0, radius * 0.80f, radius * 0.014f, NightGold)

        drawCircle(color = NightGold, radius = radius * 0.045f, center = center)
    }
}

private fun DrawScope.drawClockHand(
    centerX: Float, centerY: Float, degrees: Double,
    length: Float, halfWidth: Float, color: Color
) {
    val angle = Math.toRadians(degrees - 90)
    drawLine(
        color = color,
        start = Offset(centerX, centerY),
        end = Offset(
            centerX + cos(angle).toFloat() * length,
            centerY + sin(angle).toFloat() * length
        ),
        strokeWidth = halfWidth * 2f,
        cap = StrokeCap.Round
    )
}
