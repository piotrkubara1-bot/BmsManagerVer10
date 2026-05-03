package com.piotrek.bmsmobileviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class BmsChartView extends View {
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final DecimalFormat format = new DecimalFormat("0.00");

    private final List<Float> values = new ArrayList<>();
    private String title = "Chart";
    private int lineColor = 0xFF6EA8FE;

    public BmsChartView(Context context) {
        super(context);
        init();
    }

    public BmsChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(0x334C5F7A);
        gridPaint.setStrokeWidth(1.2f);

        linePaint.setColor(lineColor);
        linePaint.setStrokeWidth(4.0f);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        textPaint.setColor(0xFFDCE7F7);
        textPaint.setTextSize(28.0f);

        fillPaint.setColor(0x111E2A3A);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setTitle(String title) {
        this.title = title == null ? "Chart" : title;
        invalidate();
    }

    public void setLineColor(int color) {
        this.lineColor = color;
        linePaint.setColor(color);
        invalidate();
    }

    public void setValues(List<Float> newValues) {
        values.clear();
        if (newValues != null) {
            for (Float value : newValues) {
                if (value != null && Float.isFinite(value)) {
                    values.add(value);
                }
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        float left = 28;
        float right = width - 20;
        float top = 58;
        float bottom = height - 42;

        canvas.drawRoundRect(0, 0, width, height, 28, 28, fillPaint);
        canvas.drawText(title, left, 36, textPaint);

        for (int i = 0; i < 4; i++) {
            float y = top + ((bottom - top) * i / 3.0f);
            canvas.drawLine(left, y, right, y, gridPaint);
        }

        if (values.isEmpty()) {
            canvas.drawText("No history yet", left, (top + bottom) / 2, textPaint);
            return;
        }

        float min = values.get(0);
        float max = values.get(0);
        for (float value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (Math.abs(max - min) < 0.0001f) {
            max += 1.0f;
            min -= 1.0f;
        }

        path.reset();
        for (int i = 0; i < values.size(); i++) {
            float x = values.size() == 1 ? left : left + ((right - left) * i / (values.size() - 1));
            float normalized = (values.get(i) - min) / (max - min);
            float y = bottom - normalized * (bottom - top);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, linePaint);

        String meta = "min " + format.format(min) + "   max " + format.format(max)
            + "   latest " + format.format(values.get(values.size() - 1));
        canvas.drawText(meta, left, height - 12, textPaint);
    }
}
