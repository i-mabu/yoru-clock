package jp.yoru.clock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;

// ===== アナログ時計カスタムView (目盛り・時針・分針・秒針) =====
public final class AnalogClockView extends View {
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint minPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AnalogClockView(Context context) { super(context); init(); }
    public AnalogClockView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        facePaint.setColor(Color.parseColor("#161D38"));
        facePaint.setStyle(Paint.Style.FILL);
        tickPaint.setColor(Color.parseColor("#9AA3C7"));
        hourPaint.setColor(Color.parseColor("#F2F3FA"));
        hourPaint.setStrokeCap(Paint.Cap.ROUND);
        minPaint.setColor(Color.parseColor("#8FA8FF"));
        minPaint.setStrokeCap(Paint.Cap.ROUND);
        secPaint.setColor(Color.parseColor("#FFC46B"));
        secPaint.setStrokeCap(Paint.Cap.ROUND);
        numPaint.setColor(Color.parseColor("#F2F3FA"));
        numPaint.setTextAlign(Paint.Align.CENTER);
        centerPaint.setColor(Color.parseColor("#FFC46B"));
        centerPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f - 8f;
        if (radius <= 0) return;

        canvas.drawCircle(cx, cy, radius, facePaint);

        // 目盛り (60本、5分刻みは長く太く)
        for (int i = 0; i < 60; i++) {
            double ang = Math.toRadians(i * 6 - 90);
            boolean big = i % 5 == 0;
            float len = big ? radius * 0.12f : radius * 0.06f;
            tickPaint.setStrokeWidth(big ? 5f : 2f);
            float x1 = cx + (float) Math.cos(ang) * (radius - len);
            float y1 = cy + (float) Math.sin(ang) * (radius - len);
            float x2 = cx + (float) Math.cos(ang) * (radius - 4f);
            float y2 = cy + (float) Math.sin(ang) * (radius - 4f);
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }

        // 数字 12 / 3 / 6 / 9
        numPaint.setTextSize(radius * 0.16f);
        float nr = radius * 0.68f;
        Paint.FontMetrics fm = numPaint.getFontMetrics();
        float dy = -(fm.ascent + fm.descent) / 2f;
        canvas.drawText("12", cx, cy - nr + dy, numPaint);
        canvas.drawText("3", cx + nr, cy + dy, numPaint);
        canvas.drawText("6", cx, cy + nr + dy, numPaint);
        canvas.drawText("9", cx - nr, cy + dy, numPaint);

        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int min = now.get(Calendar.MINUTE);
        int sec = now.get(Calendar.SECOND);
        int ms = now.get(Calendar.MILLISECOND);

        double secF = sec + ms / 1000.0;
        double minF = min + secF / 60.0;
        double hourF = (hour % 12) + minF / 60.0;

        drawHand(canvas, cx, cy, hourF / 12.0 * 360.0, radius * 0.48f, radius * 0.045f, hourPaint);
        drawHand(canvas, cx, cy, minF / 60.0 * 360.0, radius * 0.70f, radius * 0.032f, minPaint);
        drawHand(canvas, cx, cy, secF / 60.0 * 360.0, radius * 0.80f, radius * 0.014f, secPaint);

        canvas.drawCircle(cx, cy, radius * 0.045f, centerPaint);
    }

    private void drawHand(Canvas canvas, float cx, float cy, double degrees, float len, float width, Paint paint) {
        double ang = Math.toRadians(degrees - 90);
        paint.setStrokeWidth(width * 2f);
        float x = cx + (float) Math.cos(ang) * len;
        float y = cy + (float) Math.sin(ang) * len;
        canvas.drawLine(cx, cy, x, y, paint);
    }
}
