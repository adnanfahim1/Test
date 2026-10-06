package com.adnan.glasslauncher;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Design tokens (from tokens.json) plus small view-building helpers.
 *
 * Every size in the app is written in "design pixels" of the 1280x720 prototype
 * and converted with {@link #u(float)}, so the layout fits the head unit's real
 * resolution (1280x720, 1360x800, ...) whatever its density setting is.
 */
final class Ui {
    private Ui() {}

    // ---- Colours -------------------------------------------------------------------------
    static final int BG = 0xFF0E0F12;
    static final int GLOW_NEUTRAL_1 = 0xFF2A2C33;
    static final int GLOW_NEUTRAL_2 = 0xFF1D1E24;
    static final int TEXT = 0xFFF2F3F5;
    static final int TEXT_2 = 0xFFC8CAD1;
    static final int TEXT_E6 = 0xFFE6E7EB;
    static final int MUTED = 0xFFB3B5BD;
    static final int FAINT = 0xFF9A9CA5;
    static final int SUCCESS = 0xFF34D399;
    static final int WARNING = 0xFFFBBF24;
    static final int DANGER = 0xFFC62828;

    static final String[] THEME_NAMES = {"Violet", "Ocean", "Emerald", "Rose"};
    static final int[] THEME_ACCENTS = {0xFF7C5CFF, 0xFF2563EB, 0xFF047857, 0xFFBE185D};
    static final int[] THEME_GLOWS = {0xFF3B3368, 0xFF1F3D73, 0xFF0F4A3F, 0xFF5A1F45};

    static int themeIndex = 0;

    static int accent() { return THEME_ACCENTS[themeIndex]; }
    static int themeGlow() { return THEME_GLOWS[themeIndex]; }
    /** Accent at ~53% alpha, used for glows (the prototype's accent + "88"). */
    static int accentGlow() { return withAlpha(accent(), 0x88); }

    /** Accent lifted towards white, for glyphs on dark glass. */
    static int accentLight() {
        int c = accent();
        return Color.rgb((Color.red(c) + 255 * 2) / 3, (Color.green(c) + 255 * 2) / 3, (Color.blue(c) + 255 * 2) / 3);
    }

    static int white(float alpha) { return Color.argb(Math.round(alpha * 255), 255, 255, 255); }
    static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    // ---- Scale ---------------------------------------------------------------------------
    static float scale = 1f;
    static float fontScale = 1f;
    static boolean reduceMotion = false;
    /** Compatibility mode: software drawing, no animations, no glow effects. */
    static boolean lite = false;

    static void init(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        scale = Math.min(w / 1280f, h / 720f);
        float animScale = 1f;
        try {
            animScale = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Exception ignored) {
        }
        reduceMotion = animScale == 0f || lite;
    }

    /**
     * Re-scales the design to the real usable area (called on every layout size change).
     * Returns true when the scale changed and screens must be rebuilt.
     */
    static boolean setArea(int w, int h) {
        if (w <= 0 || h <= 0) return false;
        // Landscape head units: fit 1280x720 design units. A portrait window still gets a
        // usable layout because the smaller ratio wins.
        float s = Math.min(w / 1280f, h / 720f);
        if (Math.abs(s - scale) < 0.002f) return false;
        scale = s;
        return true;
    }

    /**
     * Text multiplier: the user's font size, plus a boost on small screens (7" 800x480 or
     * 1024x600 units) so labels stay readable while driving.
     */
    static float textScale() {
        float boost = scale < 0.85f ? Math.min(1.3f, 0.85f / scale) : 1f;
        return fontScale * boost;
    }

    static int u(float designPx) { return Math.round(designPx * scale); }
    static float uf(float designPx) { return designPx * scale; }

    // ---- Fonts ---------------------------------------------------------------------------
    private static Typeface barlow500, barlow600, manrope400, manrope500, manrope600, manrope700;

    static void loadFonts(Context c) {
        barlow500 = font(c, "barlow-500");
        barlow600 = font(c, "barlow-600");
        manrope400 = font(c, "manrope-400");
        manrope500 = font(c, "manrope-500");
        manrope600 = font(c, "manrope-600");
        manrope700 = font(c, "manrope-700");
    }

    private static Typeface font(Context c, String name) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/" + name + ".ttf");
        } catch (Exception e) {
            return Typeface.DEFAULT;
        }
    }

    static Typeface display(int weight) { return weight >= 600 ? barlow600 : barlow500; }

    static Typeface body(int weight) {
        if (weight >= 700) return manrope700;
        if (weight >= 600) return manrope600;
        if (weight >= 500) return manrope500;
        return manrope400;
    }

    // ---- Motion --------------------------------------------------------------------------
    static final TimeInterpolator EASE_OUT = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    static final TimeInterpolator OVERSHOOT = new PathInterpolator(0.3f, 1.4f, 0.5f, 1f);
    static final TimeInterpolator DECEL = new DecelerateInterpolator();

    static long dur(long ms) { return reduceMotion ? 0 : ms; }

    /** Press feedback from the motion spec: scale to 0.95 and brighten, spring back in ~220 ms. */
    static void pressable(final View v) {
        v.setClickable(true);
        focusable(v);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                if (reduceMotion) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.85f).setDuration(120).setInterpolator(EASE_OUT).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(EASE_OUT).start();
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    /**
     * Rotary knob / D-pad support: the view can take focus and shows an accent ring while
     * focused (drawn in the view's overlay, so it works on every Android version).
     */
    static void focusable(final View v) {
        v.setFocusable(true);
        final GlassDrawable ring = new GlassDrawable(0, accent(), 0, uf(16)).stroke(accent(), 3);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                try {
                    if (hasFocus) {
                        ring.setBounds(0, 0, view.getWidth(), view.getHeight());
                        view.getOverlay().add(ring);
                    } else {
                        view.getOverlay().remove(ring);
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** "rise" entrance: fade in and move up 14 design px, with a delay for staggering. */
    static void rise(View v, long delayMs) {
        if (reduceMotion) return;
        v.setAlpha(0f);
        v.setTranslationY(uf(14));
        v.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(600).setInterpolator(EASE_OUT).start();
    }

    // ---- Views ---------------------------------------------------------------------------
    static TextView text(Context c, CharSequence s, float sizeDesignPx, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, uf(sizeDesignPx) * textScale());
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static TextView multiline(Context c, CharSequence s, float size, int color, Typeface tf) {
        TextView t = text(c, s, size, color, tf);
        t.setSingleLine(false);
        t.setMaxLines(6);
        t.setLineSpacing(0, 1.3f);
        return t;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams lpw(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, float l, float t, float r, float b) {
        p.setMargins(u(l), u(t), u(r), u(b));
        return p;
    }

    static FrameLayout.LayoutParams flp(int w, int h, int gravity) { return new FrameLayout.LayoutParams(w, h, gravity); }

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    static View space(Context c) { return new View(c); }

    static View gap(Context c, float designPx) {
        View v = new View(c);
        v.setLayoutParams(lp(u(designPx), u(designPx)));
        return v;
    }

    /** Glass panel background: translucent fill, 1px border, top inner highlight. */
    static GlassDrawable glass(float radius) {
        return new GlassDrawable(white(0.06f), white(0.14f), white(0.16f), uf(radius));
    }

    static GlassDrawable tile(float radius) {
        return new GlassDrawable(white(0.07f), white(0.10f), white(0.08f), uf(radius));
    }

    static GlassDrawable fill(int fill, int border, float radius) {
        return new GlassDrawable(fill, border, 0, uf(radius));
    }

    /** Rounded rectangle with fill, hairline border and an optional inset top highlight. */
    static final class GlassDrawable extends Drawable {
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokeP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hiP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float radius;
        private int glowColor = 0;
        private int alpha = 255;

        GlassDrawable(int fill, int stroke, int highlight, float radius) {
            this.radius = radius;
            fillP.setColor(fill);
            strokeP.setStyle(Paint.Style.STROKE);
            strokeP.setStrokeWidth(Math.max(1f, uf(1)));
            strokeP.setColor(stroke);
            hiP.setStyle(Paint.Style.STROKE);
            hiP.setStrokeWidth(Math.max(1f, uf(1)));
            hiP.setColor(highlight);
        }

        GlassDrawable stroke(int color, float width) {
            strokeP.setColor(color);
            strokeP.setStrokeWidth(Math.max(1f, uf(width)));
            invalidateSelf();
            return this;
        }

        GlassDrawable fillColor(int color) {
            fillP.setColor(color);
            invalidateSelf();
            return this;
        }

        /** A soft coloured halo drawn as a few expanding translucent strokes (cheap box-shadow). */
        GlassDrawable glow(int color) {
            glowColor = lite ? 0 : color;
            invalidateSelf();
            return this;
        }

        @Override
        public void draw(Canvas c) {
            float half = strokeP.getStrokeWidth() / 2f;
            r.set(getBounds());
            r.inset(half, half);
            if (glowColor != 0) {
                glowP.setStyle(Paint.Style.STROKE);
                int a = Color.alpha(glowColor);
                for (int i = 1; i <= 4; i++) {
                    glowP.setStrokeWidth(uf(3 * i));
                    glowP.setColor(withAlpha(glowColor, Math.max(0, a / (2 + i * 2))));
                    c.drawRoundRect(r, radius, radius, glowP);
                }
            }
            if (Color.alpha(fillP.getColor()) > 0) c.drawRoundRect(r, radius, radius, fillP);
            if (Color.alpha(strokeP.getColor()) > 0) c.drawRoundRect(r, radius, radius, strokeP);
            if (Color.alpha(hiP.getColor()) > 0) {
                int save = c.save();
                c.clipRect(r.left, r.top, r.right, r.top + radius * 0.6f);
                c.translate(0, uf(1));
                c.drawRoundRect(r, radius, radius, hiP);
                c.restoreToCount(save);
            }
        }

        @Override
        public void getOutline(android.graphics.Outline outline) {
            outline.setRoundRect(getBounds(), radius);
        }

        @Override public void setAlpha(int a) { alpha = a; }
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static String twoDigits(int v) { return v < 10 ? "0" + v : String.valueOf(v); }

    static String mmss(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return (s / 60) + ":" + twoDigits((int) (s % 60));
    }
}
