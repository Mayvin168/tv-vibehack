package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** The mouse-style arrow the phone remote moves around the TV screen. */
final class PointerView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final float d;

    PointerView(Context context) {
        super(context);
        d = context.getResources().getDisplayMetrics().density;
        fill.setColor(0xFFFFFFFF);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(0xFF000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.6f * d);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        // Classic cursor arrow, tip at the top-left (the view's hotspot).
        float s = d;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 23 * s);
        arrow.lineTo(6.8f * s, 18.2f * s);
        arrow.lineTo(10.6f * s, 26.6f * s);
        arrow.lineTo(14.2f * s, 25.2f * s);
        arrow.lineTo(10.4f * s, 16.8f * s);
        arrow.lineTo(17 * s, 16.8f * s);
        arrow.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, stroke);
    }
}
