package com.neko.player;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Drawable masks for the MIUI view material. Kept in Java for AGP 9.1 CanvasSize analysis. */
final class BrandMaskDrawables {
    private BrandMaskDrawables() {}

    static Drawable cat(Drawable vector) { return new CatMask(vector); }
    static Drawable wordmark() { return new Wordmark(); }

    private static final class CatMask extends Drawable {
        private final Drawable vector;
        CatMask(Drawable vector) { this.vector = vector; }
        @Override public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            int checkpoint = canvas.save();
            canvas.translate(bounds.left - bounds.width() * 24f / 60f, bounds.top - bounds.height() * 24f / 60f);
            vector.setBounds(0, 0, (int) (bounds.width() * 108f / 60f), (int) (bounds.height() * 108f / 60f));
            vector.draw(canvas);
            canvas.restoreToCount(checkpoint);
        }
        @Override public void setAlpha(int alpha) { vector.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(@Nullable ColorFilter filter) { vector.setColorFilter(filter); invalidateSelf(); }
        @Override public void setTintList(@Nullable ColorStateList tint) { vector.setTintList(tint); invalidateSelf(); }
        @Deprecated @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class Wordmark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Wordmark() { paint.setColor(Color.WHITE); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); }
        @Override public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            paint.setTextSize(bounds.height());
            float scale = bounds.width() / paint.measureText("Neko Player");
            Paint.FontMetrics metrics = paint.getFontMetrics();
            int checkpoint = canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(scale, 1f);
            canvas.drawText("Neko Player", 0f, (bounds.height() - metrics.ascent - metrics.descent) / 2f, paint);
            canvas.restoreToCount(checkpoint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(@Nullable ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public void setTintList(@Nullable ColorStateList tint) {
            paint.setColorFilter(tint == null ? null : new PorterDuffColorFilter(tint.getDefaultColor(), PorterDuff.Mode.SRC_IN));
            invalidateSelf();
        }
        @Deprecated @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
