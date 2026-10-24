package com.clean.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** One hairline rounded rectangle that moves from banner to banner. No fill, no glow. */
public class FocusOutline extends View {

    private final RectF current = new RectF();
    private final RectF from = new RectF();
    private final RectF to = new RectF();
    private final RectF strokeRect = new RectF();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float radius;
    private boolean shown;
    private ValueAnimator animator;

    public FocusOutline(Context context, AttributeSet attrs) {
        super(context, attrs);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(getResources().getDimension(R.dimen.focus_stroke));
        stroke.setColor(context.getColor(R.color.text));
        radius = getResources().getDimension(R.dimen.focus_radius);
    }

    public void moveTo(RectF target, boolean animate) {
        if (animator != null) animator.cancel();
        if (!animate || !shown) {
            current.set(target);
            shown = true;
            invalidate();
            return;
        }
        from.set(current);
        to.set(target);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(Motion.FOCUS_MS);
        animator.setInterpolator(Motion.standard());
        animator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            current.set(lerp(from.left, to.left, t), lerp(from.top, to.top, t),
                    lerp(from.right, to.right, t), lerp(from.bottom, to.bottom, t));
            invalidate();
        });
        animator.start();
    }

    public void hide() {
        if (animator != null) animator.cancel();
        shown = false;
        invalidate();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shown || current.isEmpty()) return;
        float half = stroke.getStrokeWidth() / 2f;
        strokeRect.set(current.left + half, current.top + half, current.right - half, current.bottom - half);
        canvas.drawRoundRect(strokeRect, radius, radius, stroke);
    }
}
