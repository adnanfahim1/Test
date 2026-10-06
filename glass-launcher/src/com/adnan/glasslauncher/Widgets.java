package com.adnan.glasslauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.io.InputStream;

/** Custom-drawn widgets used across screens. */
final class Widgets {
    private Widgets() {}

    static Bitmap asset(Context c, String path, BitmapFactory.Options o) {
        InputStream in = null;
        try {
            in = c.getAssets().open(path);
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    // =====================================================================================
    /** Near-black ground with three radial glows, or the theme wallpaper (Minimal home). */
    static final class Background extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Matrix m = new Matrix();
        private Bitmap wallpaper;
        private boolean useWallpaper;
        private int wallTheme = -1;

        Background(Context c) { super(c); }

        void setWallpaperMode(boolean on) {
            useWallpaper = on;
            if (on && wallTheme != Ui.themeIndex) {
                wallTheme = Ui.themeIndex;
                wallpaper = asset(getContext(), "wallpapers/wall-" + Ui.THEME_NAMES[Ui.themeIndex] + ".webp", null);
            }
            invalidate();
        }

        void themeChanged() {
            if (useWallpaper) { wallTheme = -1; setWallpaperMode(true); } else invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(Ui.BG);
            if (useWallpaper && wallpaper != null) {
                float s = Math.max(w / (float) wallpaper.getWidth(), h / (float) wallpaper.getHeight());
                m.setScale(s, s);
                m.postTranslate((w - wallpaper.getWidth() * s) / 2f, (h - wallpaper.getHeight() * s) / 2f);
                p.setShader(null);
                p.setFilterBitmap(true);
                c.drawBitmap(wallpaper, m, p);
                return;
            }
            float kx = w / 1280f, ky = h / 720f;
            glow(c, 0.10f * w, -0.12f * h, 680 * kx, 460 * ky, Ui.themeGlow(), 0.62f, w, h);
            glow(c, 1.05f * w, 1.10f * h, 700 * kx, 520 * ky, Ui.GLOW_NEUTRAL_1, 0.60f, w, h);
            glow(c, 0.70f * w, 0.20f * h, 520 * kx, 380 * ky, Ui.GLOW_NEUTRAL_2, 0.70f, w, h);
        }

        private void glow(Canvas c, float cx, float cy, float rx, float ry, int color, float stop, int w, int h) {
            RadialGradient g = new RadialGradient(cx, cy, rx, new int[]{color, Ui.withAlpha(color, 0)},
                    new float[]{0f, stop}, Shader.TileMode.CLAMP);
            m.setScale(1f, ry / rx, cx, cy);
            g.setLocalMatrix(m);
            p.setShader(g);
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }
    }

    // =====================================================================================
    /** iOS-style switch: knob slides with overshoot (~380 ms), track glows in the accent. */
    static final class Toggle extends View {
        interface OnChange { void changed(boolean on); }

        private boolean on;
        private float pos;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private OnChange listener;
        private ValueAnimator anim;

        Toggle(Context c, boolean initial) {
            super(c);
            on = initial;
            pos = on ? 1 : 0;
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { setChecked(!on, true); }
            });
        }

        void setOnChange(OnChange l) { listener = l; }
        boolean isChecked() { return on; }

        void setChecked(boolean v, boolean fromUser) {
            if (v == on) return;
            on = v;
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(pos, on ? 1 : 0);
            anim.setDuration(Ui.dur(380));
            anim.setInterpolator(Ui.OVERSHOOT);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) { pos = (Float) a.getAnimatedValue(); invalidate(); }
            });
            anim.start();
            if (fromUser && listener != null) listener.changed(on);
        }

        @Override
        protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.u(60), Ui.u(34)); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            float t = Math.max(0, Math.min(1, pos));
            r.set(0, 0, w, h);
            if (on) {
                p.setStyle(Paint.Style.STROKE);
                for (int i = 1; i <= 3; i++) {
                    p.setStrokeWidth(Ui.uf(3 * i));
                    p.setColor(Ui.withAlpha(Ui.accent(), 0x40 / i));
                    c.drawRoundRect(r, rad, rad, p);
                }
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(blend(Ui.white(0.16f), Ui.accent(), t));
            c.drawRoundRect(r, rad, rad, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, Ui.uf(1)));
            p.setColor(Ui.white(0.2f));
            c.drawRoundRect(r, rad, rad, p);
            float knob = Ui.uf(24), inset = Ui.uf(4);
            float x = inset + pos * (w - knob - inset * 2);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x59000000);
            c.drawCircle(x + knob / 2f, h / 2f + Ui.uf(2), knob / 2f + Ui.uf(1), p);
            p.setColor(Color.WHITE);
            c.drawCircle(x + knob / 2f, h / 2f, knob / 2f, p);
        }
    }

    static int blend(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = Color.alpha(a), rr = Color.red(a), gg = Color.green(a), bb = Color.blue(a);
        return Color.argb(Math.round(ar + (Color.alpha(b) - ar) * t), Math.round(rr + (Color.red(b) - rr) * t),
                Math.round(gg + (Color.green(b) - gg) * t), Math.round(bb + (Color.blue(b) - bb) * t));
    }

    // =====================================================================================
    /** Range slider with accent fill and a white thumb; large touch area for driving. */
    static final class Slider extends View {
        interface OnChange { void changed(int value, boolean fromUser); }
        interface OnRelease { void released(int value); }

        private int max, value;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private OnChange listener;
        private OnRelease release;
        private boolean dragging;
        private float trackH = 6;

        Slider(Context c, int max, int value) {
            super(c);
            this.max = Math.max(1, max);
            this.value = value;
        }

        void setOnChange(OnChange l) { listener = l; }
        void setOnRelease(OnRelease l) { release = l; }
        void setTrackHeight(float designPx) { trackH = designPx; }
        boolean isDragging() { return dragging; }

        void setMax(int m) { max = Math.max(1, m); invalidate(); }

        void setValue(int v) {
            if (dragging) return;
            v = Math.max(0, Math.min(max, v));
            if (v != value) { value = v; invalidate(); }
        }

        int value() { return value; }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    update(e.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    update(e.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    update(e.getX());
                    dragging = false;
                    if (release != null) release.released(value);
                    return true;
                default:
                    return false;
            }
        }

        private void update(float x) {
            float pad = getHeight() / 2f;
            float t = (x - pad) / Math.max(1, getWidth() - 2 * pad);
            int v = Math.round(Math.max(0, Math.min(1, t)) * max);
            if (v != value) {
                value = v;
                invalidate();
                if (listener != null) listener.changed(v, true);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), pad = h / 2f;
            float th = Ui.uf(trackH), cy = h / 2f;
            float x = pad + (w - 2 * pad) * (value / (float) max);
            r.set(pad, cy - th / 2, w - pad, cy + th / 2);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.white(0.16f));
            c.drawRoundRect(r, th / 2, th / 2, p);
            r.set(pad, cy - th / 2, x, cy + th / 2);
            p.setColor(Ui.accent());
            c.drawRoundRect(r, th / 2, th / 2, p);
            float kr = Ui.uf(dragging ? 13 : 11);
            p.setColor(0x55000000);
            c.drawCircle(x, cy + Ui.uf(2), kr + Ui.uf(1), p);
            p.setColor(Color.WHITE);
            c.drawCircle(x, cy, kr, p);
        }
    }

    // =====================================================================================
    /** Thin progress bar (music bar). */
    static final class Progress extends View {
        private float t;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Progress(Context c) { super(c); }

        void set(float v) {
            v = Math.max(0, Math.min(1, v));
            if (Math.abs(v - t) > 0.001f) { t = v; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            r.set(0, 0, w, h);
            p.setColor(Ui.white(0.14f));
            c.drawRoundRect(r, h / 2, h / 2, p);
            if (t > 0) {
                r.set(0, 0, Math.max(h, w * t), h);
                p.setColor(Ui.accent());
                c.drawRoundRect(r, h / 2, h / 2, p);
            }
        }
    }

    // =====================================================================================
    /** Vinyl record (repeating grooves) with album art in the centre; spins 7 s per turn. */
    static final class Disc extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private Bitmap art;
        private BitmapShader artShader;
        private boolean spinning;
        private float angle;
        private long last;
        private final float labelFrac;
        private final boolean spindle;
        private boolean arm;
        private boolean active;
        private float armT = 0f;
        private ValueAnimator armAnim;
        private final Path armPath = new Path();

        Disc(Context c, float labelFrac, boolean spindle) {
            super(c);
            this.labelFrac = labelFrac;
            this.spindle = spindle;
        }

        void showArm(boolean on) { arm = on; invalidate(); }

        void setArt(Bitmap b) {
            if (b == art) return;
            art = b;
            artShader = b != null ? new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
            invalidate();
        }

        void setSpinning(boolean s) {
            if (s == active) return;
            active = s;
            spinning = s && !Ui.reduceMotion;
            last = SystemClock.uptimeMillis();
            if (arm) {
                if (armAnim != null) armAnim.cancel();
                armAnim = ValueAnimator.ofFloat(armT, s ? 1f : 0f);
                armAnim.setDuration(Ui.dur(600));
                armAnim.setInterpolator(Ui.EASE_OUT);
                armAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(ValueAnimator a) { armT = (Float) a.getAnimatedValue(); invalidate(); }
                });
                armAnim.start();
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float discSize = arm ? Math.min(w, h) * 0.79f : Math.min(w, h);
            float cx = w / 2f, cy = h / 2f, R = discSize / 2f;
            if (spinning) {
                long now = SystemClock.uptimeMillis();
                angle = (angle + (now - last) * 360f / 7000f) % 360f;
                last = now;
            }
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x80000000);
            c.drawCircle(cx, cy + Ui.uf(8), R, p);
            p.setColor(0xFF141519);
            c.drawCircle(cx, cy, R, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, R / 50f));
            p.setColor(0xFF202228);
            float step = Math.max(Ui.uf(6) * (R / Ui.uf(99)), 3f);
            for (float rr = R * labelFrac + step; rr < R - 1; rr += step) c.drawCircle(cx, cy, rr, p);
            p.setColor(Ui.white(0.05f));
            p.setStrokeWidth(R * 0.06f);
            c.drawCircle(cx, cy, R - R * 0.03f, p);
            float lr = R * labelFrac;
            c.save();
            c.rotate(angle, cx, cy);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            if (artShader != null) {
                float s = (lr * 2) / Math.min(art.getWidth(), art.getHeight());
                m.setScale(s, s);
                m.postTranslate(cx - art.getWidth() * s / 2f, cy - art.getHeight() * s / 2f);
                artShader.setLocalMatrix(m);
                p.setShader(artShader);
            } else {
                p.setShader(new LinearGradient(cx - lr, cy - lr, cx + lr * 0.4f, cy + lr,
                        0xFFF7B2A8, Ui.accent(), Shader.TileMode.CLAMP));
            }
            c.drawCircle(cx, cy, lr, p);
            p.setShader(null);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, lr * 0.06f));
            p.setColor(Ui.white(0.28f));
            c.drawCircle(cx, cy, lr, p);
            if (spindle) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Ui.white(0.4f));
                c.drawCircle(cx, cy, lr * 0.2f, p);
                p.setColor(0xFF0A0818);
                c.drawCircle(cx, cy, lr * 0.15f, p);
            }
            if (arm) drawArm(c, w, h);
            if (spinning) postInvalidateOnAnimation();
        }

        /** Tonearm from Media.dc.html (110x190 viewBox), swung away when paused. */
        private void drawArm(Canvas c, float w, float h) {
            float aw = w * 86f / 250f, k = aw / 110f;
            float ox = w - Ui.uf(4) * (w / Ui.uf(250)) - aw, oy = Ui.uf(4) * (w / Ui.uf(250));
            c.save();
            c.translate(ox, oy);
            c.scale(k, k);
            c.rotate(-24f * (1f - armT), 62, 23);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A2C33);
            c.drawCircle(80, 30, 18, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(Ui.white(0.25f));
            c.drawCircle(80, 30, 18, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.TEXT_2);
            c.drawCircle(80, 30, 7, p);
            armPath.reset();
            armPath.moveTo(80, 30);
            armPath.lineTo(72, 128);
            armPath.lineTo(50, 166);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(armPath, p);
            c.save();
            c.rotate(-30, 46, 166);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.TEXT_E6);
            c.drawRoundRect(new RectF(34, 158, 58, 174), 4, 4, p);
            c.restore();
            c.restore();
        }
    }

    // =====================================================================================
    /**
     * 360° turntable of the car: 72 WebP frames (5° apart) at 12 fps. Frames are decoded
     * one at a time on a background thread into reused bitmaps, so memory stays ~2 frames.
     */
    static final class CarSpin extends View {
        static final int FRAMES = 72;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private HandlerThread thread;
        private Handler bg;
        private final Handler main = new Handler(Looper.getMainLooper());
        private Bitmap front, back;
        private int frame;
        private boolean running;
        private boolean playing = true;

        CarSpin(Context c) { super(c); }

        boolean isPlaying() { return playing; }

        void setPlaying(boolean v) {
            playing = v && !Ui.reduceMotion;
            if (playing) scheduleNext(0);
        }

        void start() {
            if (running) return;
            running = true;
            thread = new HandlerThread("car-spin");
            thread.start();
            bg = new Handler(thread.getLooper());
            if (Ui.reduceMotion) playing = false;
            scheduleNext(0);
        }

        void stop() {
            running = false;
            if (thread != null) thread.quitSafely();
            thread = null;
            bg = null;
            main.removeCallbacksAndMessages(null);
        }

        private void scheduleNext(long delay) {
            final Handler h = bg;
            if (h == null) return;
            h.removeCallbacksAndMessages(null);
            h.postDelayed(new Runnable() {
                @Override
                public void run() { decode(); }
            }, delay);
        }

        private void decode() {
            final long t0 = SystemClock.uptimeMillis();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            if (back != null) o.inBitmap = back;
            String name = "car/spin/frame_" + Ui.twoDigits(frame) + ".webp";
            Bitmap b = asset(getContext(), name, o);
            if (b == null && o.inBitmap != null) {
                o.inBitmap = null;
                b = asset(getContext(), name, o);
            }
            final Bitmap decoded = b;
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (!running || decoded == null) return;
                    back = front;
                    front = decoded;
                    invalidate();
                    if (playing) {
                        frame = (frame + 1) % FRAMES;
                        long spent = SystemClock.uptimeMillis() - t0;
                        scheduleNext(Math.max(0, 1000 / 12 - spent));
                    }
                }
            });
        }

        @Override
        protected void onDraw(Canvas c) {
            Bitmap b = front;
            if (b == null) return;
            float s = Math.min(getWidth() / (float) b.getWidth(), getHeight() / (float) b.getHeight());
            float dw = b.getWidth() * s, dh = b.getHeight() * s;
            src.set(0, 0, b.getWidth(), b.getHeight());
            dst.set((getWidth() - dw) / 2f, (getHeight() - dh) / 2f, (getWidth() + dw) / 2f, (getHeight() + dh) / 2f);
            c.drawBitmap(b, src, dst, p);
        }
    }

    // =====================================================================================
    /** Static bitmap scaled to fit (car still). */
    static final class Picture extends View {
        private Bitmap bmp;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();

        Picture(Context c, Bitmap b) { super(c); bmp = b; }

        @Override
        protected void onDraw(Canvas c) {
            if (bmp == null) return;
            float s = Math.min(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
            float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s;
            dst.set((getWidth() - dw) / 2f, (getHeight() - dh) / 2f, (getWidth() + dw) / 2f, (getHeight() + dh) / 2f);
            c.drawBitmap(bmp, null, dst, p);
        }
    }

    // =====================================================================================
    /** Decorative map (streets + animated dashed route + position dot). Not real map data. */
    static final class MapArt extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path streets = SvgPath.parse("M-10 120 L210 90M-10 250 L210 300M60 -10 L40 430M150 -10 L170 430M-10 360 L210 340");
        private final Path route = SvgPath.parse("M100 430 L104 330 Q108 290 140 270 L160 255 L156 150 Q154 120 120 116 L70 112");
        private final Matrix m = new Matrix();
        private final Path tmp = new Path();
        private float phase;
        private ValueAnimator anim;
        private final boolean stretch;

        MapArt(Context c, boolean stretch) {
            super(c);
            this.stretch = stretch;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (Ui.reduceMotion) return;
            anim = ValueAnimator.ofFloat(0, 24);
            anim.setDuration(1600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) { phase = (Float) a.getAnimatedValue(); invalidate(); }
            });
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float sx, sy, k;
            if (stretch) {
                sx = w / 200f; sy = h / 420f;
                m.setScale(sx, sy);
                k = Math.min(sx, sy);
            } else {
                k = Math.max(w / 200f, h / 420f);
                m.setScale(k, k);
                m.postTranslate((w - 200 * k) / 2f, (h - 420 * k) / 2f);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.BUTT);
            p.setPathEffect(null);
            p.setStrokeWidth(10 * k);
            p.setColor(Ui.white(0.10f));
            streets.transform(m, tmp);
            c.drawPath(tmp, p);
            route.transform(m, tmp);
            p.setColor(Ui.accent());
            p.setStrokeWidth(6 * k);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setPathEffect(new DashPathEffect(new float[]{14 * k, 10 * k}, -phase * 2 * k));
            c.drawPath(tmp, p);
            p.setPathEffect(null);
            float[] pt = {102, 360};
            m.mapPoints(pt);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            c.drawCircle(pt[0], pt[1], 11 * k, p);
            p.setColor(Ui.accent());
            c.drawCircle(pt[0], pt[1], 6 * k, p);
        }
    }

    // =====================================================================================
    /** Sun / cloud / rain illustration (150x110 viewBox from the prototype). */
    static final class WeatherArt extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path cloud = SvgPath.parse("M38 100h78a24 24 0 0 0 0-48 34 34 0 0 0-64 6 21 21 0 0 0-14 42z");
        private final Path tmp = new Path();
        private final Matrix m = new Matrix();
        private int kind = 1;
        private boolean day = true;
        private float drift;
        private ValueAnimator anim;

        WeatherArt(Context c) { super(c); }

        void set(int kind, boolean day) {
            this.kind = kind;
            this.day = day;
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (Ui.reduceMotion) return;
            anim = ValueAnimator.ofFloat(0, (float) (Math.PI * 2));
            anim.setDuration(6000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) { drift = (float) Math.sin((Float) a.getAnimatedValue()); invalidate(); }
            });
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
        }

        @Override
        protected void onDraw(Canvas c) {
            float k = Math.min(getWidth() / 150f, getHeight() / 110f);
            c.save();
            c.translate((getWidth() - 150 * k) / 2f, (getHeight() - 110 * k) / 2f);
            c.scale(k, k);
            p.setStyle(Paint.Style.FILL);
            if (kind <= 1) {
                p.setColor(day ? 0xFFFFD36B : 0xFFE6E7EB);
                if (kind == 0) c.drawCircle(75, 55, 32, p);
                else c.drawCircle(96, 38, 26, p);
                if (!day) {
                    p.setColor(Ui.BG);
                    c.drawCircle(kind == 0 ? 89 : 106, kind == 0 ? 45 : 30, kind == 0 ? 26 : 21, p);
                }
            } else if (kind == 4) {
                p.setColor(0xFFFFD36B);
                Path bolt = SvgPath.parse("M80 70l-14 22h12l-6 18 20-26H80l8-14z");
                c.drawPath(bolt, p);
            }
            if (kind >= 1) {
                c.save();
                c.translate(-4 * (drift + 1), 0);
                p.setColor(kind >= 2 ? 0xD0DCDFE8 : 0xE0FFFFFF);
                c.drawPath(cloud, p);
                c.restore();
            }
            if (kind == 3) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(5);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(0xFF8FB3FF);
                for (int i = 0; i < 4; i++) c.drawLine(50 + i * 18, 104, 44 + i * 18, 110, p);
            }
            if (kind == 5) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(5);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(Ui.white(0.7f));
                c.drawLine(30, 106, 120, 106, p);
            }
            c.restore();
        }
    }

    // =====================================================================================
    /** Indeterminate ring spinner (accent top). */
    static final class Spinner extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Spinner(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), sw = s / 10f;
            r.set(sw / 2, sw / 2, s - sw / 2, s - sw / 2);
            r.offset((getWidth() - s) / 2f, (getHeight() - s) / 2f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(Ui.white(0.18f));
            c.drawOval(r, p);
            p.setColor(Ui.accent());
            p.setStrokeCap(Paint.Cap.ROUND);
            float a = (SystemClock.uptimeMillis() % 900) / 900f * 360f;
            c.drawArc(r, a - 90, 90, false, p);
            postInvalidateOnAnimation();
        }
    }

    // =====================================================================================
    /** Three bouncing equaliser bars for the playing row. */
    static final class EqBars extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        EqBars(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            float bw = Ui.uf(4), gap = Ui.uf(3), maxH = Ui.uf(20);
            float total = bw * 3 + gap * 2;
            float x = (getWidth() - total) / 2f, cy = getHeight() / 2f;
            long t = SystemClock.uptimeMillis();
            p.setColor(Color.WHITE);
            for (int i = 0; i < 3; i++) {
                float ph = Ui.reduceMotion ? 0.7f : (float) (0.35 + 0.65 * Math.abs(Math.sin((t / 260.0) + i * 1.3)));
                float h = maxH * ph;
                r.set(x, cy - h / 2, x + bw, cy + h / 2);
                c.drawRoundRect(r, bw / 2, bw / 2, p);
                x += bw + gap;
            }
            if (!Ui.reduceMotion) postInvalidateOnAnimation();
        }
    }

    // =====================================================================================
    /**
     * Wheel loader shown when opening Music / Apps / Settings / Car settings: a car wheel
     * rolls in and spins over a light frosted scrim for ~0.6 s, then fades out.
     */
    static final class WheelLoader extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path spokes = SvgPath.parse("M32 15v10M48.2 26.7l-9.5 3.1M42 45.8l-5.9-8.1M22 45.8l5.9-8.1M15.8 26.7l9.5 3.1");
        private float t = 1f;
        private ValueAnimator anim;

        WheelLoader(Context c) {
            super(c);
            setVisibility(GONE);
            setClickable(true);
        }

        void play() {
            if (Ui.reduceMotion) return;
            if (anim != null) anim.cancel();
            setVisibility(VISIBLE);
            setAlpha(1f);
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(750);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    t = (Float) a.getAnimatedValue();
                    setAlpha(t < 0.75f ? 1f : 1f - (t - 0.75f) / 0.25f);
                    invalidate();
                    if (t >= 1f) setVisibility(GONE);
                }
            });
            anim.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0x330E0F12);
            c.drawColor(Ui.white(0.04f));
            float size = Ui.uf(56);
            float roll = Math.min(1f, t / 0.35f);
            float ease = 1f - (1f - roll) * (1f - roll) * (1f - roll);
            float cx = getWidth() / 2f + (1f - ease) * Ui.uf(140), cy = getHeight() / 2f;
            float k = size / 64f;
            c.save();
            c.translate(cx - size / 2f, cy - size / 2f);
            c.scale(k, k);
            c.rotate(-t * 720f, 32, 32);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1B1C21);
            c.drawCircle(32, 32, 28, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(0xFF2E3038);
            c.drawCircle(32, 32, 28, p);
            p.setStrokeWidth(3);
            p.setColor(0xFFCDCFD6);
            c.drawCircle(32, 32, 19, p);
            p.setStrokeWidth(4);
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawPath(spokes, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.accent());
            c.drawCircle(32, 32, 6, p);
            c.restore();
        }
    }

    // =====================================================================================
    /** Round dot (phone status, swatches). */
    static final class Dot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color;
        private boolean pulse;
        private int ring;

        Dot(Context c, int color) { super(c); this.color = color; }

        void set(int color, boolean pulse) {
            this.color = color;
            this.pulse = pulse && !Ui.reduceMotion;
            invalidate();
        }

        void ring(int color) { ring = color; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            int a = 255;
            if (pulse) {
                double ph = (SystemClock.uptimeMillis() % 1000) / 1000.0 * Math.PI * 2;
                a = (int) (255 * (0.675 + 0.325 * Math.cos(ph)));
                postInvalidateOnAnimation();
            }
            if (ring != 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.uf(3));
                p.setColor(ring);
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, r - Ui.uf(1.5f), p);
                r -= Ui.uf(6);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setAlpha(Math.min(a, Color.alpha(color)));
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
        }
    }
}
