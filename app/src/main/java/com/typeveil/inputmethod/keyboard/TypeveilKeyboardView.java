package com.typeveil.inputmethod.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.typeveil.inputmethod.R;

/**
 * Custom KeyboardView with TypeVeil visual indicators.
 * 
 * Handles:
 * - Key drawing with dark theme
 * - Visual feedback for Veil/Unveil keys
 * - Mode indicator (veil mode active → accent border)
 */
public class TypeveilKeyboardView extends KeyboardView {

    private static final String TAG = "TypeveilKeyboardView";

    private Paint keyTextPaint;
    private Paint keyLabelPaint;
    private boolean veilModeActive = false;

    // Key background colors
    private int keyBgColor;
    private int keyPressedColor;
    private int veilKeyColor;
    private int veilKeyActiveColor;

    public TypeveilKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TypeveilKeyboardView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        keyTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        keyTextPaint.setColor(context.getResources().getColor(R.color.veil_text, null));
        keyTextPaint.setTextSize(40f);
        keyTextPaint.setTextAlign(Paint.Align.CENTER);

        keyBgColor = 0xFF2A2A30;
        keyPressedColor = 0xFF3E3E44;
        veilKeyColor = 0xFF00D4AA;
        veilKeyActiveColor = 0xFF8B5CF6;

        setPreviewEnabled(false);
    }

    /** Set whether Veil mode is currently active (affects key colors). */
    public void setVeilModeActive(boolean active) {
        veilModeActive = active;
        // Force redraw of keyboard
        Keyboard kbd = getKeyboard();
        if (kbd != null) {
            setKeyboard(kbd);
        }
    }

    @Override
    protected boolean onLongPress(Keyboard.Key popupKey) {
        // Long-press on Veil key could show contact picker
        if (popupKey.codes[0] == -100) {
            return true; // Consumed — handle in IME
        }
        return super.onLongPress(popupKey);
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        Keyboard keyboard = getKeyboard();
        if (keyboard == null) return;

        Paint paint = keyTextPaint;
        paint.setTypeface(Typeface.DEFAULT_BOLD);

        for (Keyboard.Key key : keyboard.getKeys()) {
            if (key.label != null) {
                String label = key.label.toString();

                // Special coloring for Veil/Unveil keys
                if (key.codes[0] == -100) {
                    // Veil key
                    paint.setColor(veilModeActive ? veilKeyActiveColor : veilKeyColor);
                } else if (key.codes[0] == -101) {
                    // Unveil key
                    paint.setColor(0xFF888888);
                } else {
                    paint.setColor(getResources().getColor(R.color.veil_text, null));
                }

                // Draw label
                float x = key.x + (key.width / 2f);
                float y = key.y + (key.height / 2f) - (paint.descent() + paint.ascent()) / 2;
                canvas.drawText(label.toString(), x, y, paint);
            }
        }
    }
}
