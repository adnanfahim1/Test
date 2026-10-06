package com.adnan.glasslauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * The prototype's layered "glass" icons (64x64 viewBox) and the small line glyphs
 * (24x24 viewBox), drawn natively from their SVG path data.
 */
final class Icons {
    private Icons() {}

    // ---- Gradients (tokens.json "iconGradients") ------------------------------------------
    static final int[] G_BLUE = {0xFF8FB3FF, 0xFF3D5AFE};
    static final int[] G_VIOLET = {0xFFD4B8FF, 0xFF7C5CFF};
    static final int[] G_TEAL = {0xFF8FF5DF, 0xFF1FAE9F};
    static final int[] G_SUN = {0xFFFFE7A0, 0xFFFFB547};
    static final int[] G_FROST = {0xE0FFFFFF, 0x6BDCD6FF};
    static final int[] G_DOT = {0xFFA9FFF0, 0xFF2BC4B4};

    static final class Glass {
        final Path back, front, detail, dot;
        final int[] bg, dotFill;

        Glass(String back, int[] bg, String front, String detail, String dot, int[] dotFill) {
            this.back = SvgPath.parse(back);
            this.front = SvgPath.parse(front);
            this.detail = SvgPath.parse(detail);
            this.dot = SvgPath.parse(dot);
            this.bg = bg;
            this.dotFill = dotFill;
        }
    }

    private static String rr(float x, float y, float w, float h, float r) {
        return "M" + (x + r) + " " + y + "h" + (w - 2 * r) + "a" + r + " " + r + " 0 0 1 " + r + " " + r
                + "v" + (h - 2 * r) + "a" + r + " " + r + " 0 0 1 -" + r + " " + r + "h-" + (w - 2 * r)
                + "a" + r + " " + r + " 0 0 1 -" + r + " -" + r + "v-" + (h - 2 * r)
                + "a" + r + " " + r + " 0 0 1 " + r + " -" + r + "z";
    }

    private static String ci(float cx, float cy, float r) {
        return "M" + (cx - r) + " " + cy + "a" + r + " " + r + " 0 1 0 " + (2 * r) + " 0a" + r + " " + r
                + " 0 1 0 -" + (2 * r) + " 0z";
    }

    private static String gear() {
        int n = 8;
        float ro = 24, ri = 18;
        StringBuilder sb = new StringBuilder("M");
        for (int i = 0; i < n * 4; i++) {
            double a = (i / (double) (n * 4)) * Math.PI * 2 - Math.PI / 2;
            float r = (i % 4 == 0 || i % 4 == 1) ? ro : ri;
            if (i > 0) sb.append('L');
            sb.append(32 + r * Math.cos(a)).append(' ').append(32 + r * Math.sin(a));
        }
        return sb.append('z').toString();
    }

    private static final Map<String, Glass> GLASS = new HashMap<String, Glass>();

    static Glass glass(String key) {
        if (GLASS.isEmpty()) buildGlass();
        Glass g = GLASS.get(key);
        return g != null ? g : GLASS.get("apps");
    }

    private static void put(String k, String back, int[] bg, String front, String detail, String dot, int[] dotFill) {
        GLASS.put(k, new Glass(back, bg, front, detail, dot, dotFill));
    }

    private static void buildGlass() {
        put("apps", rr(8, 8, 22, 22, 7) + rr(34, 34, 22, 22, 7), G_VIOLET, rr(34, 8, 22, 22, 7) + rr(8, 34, 22, 22, 7), "", "", G_DOT);
        put("home", "M32 7a4 4 0 0 1 2.6 1l21 18a3.5 3.5 0 0 1-2.3 6.2H10.7A3.5 3.5 0 0 1 8.4 26l21-18A4 4 0 0 1 32 7z", G_VIOLET, rr(14, 28, 36, 26, 8), "", rr(27, 38, 10, 16, 3), G_BLUE);
        put("maps", "M8 18l16-7 16 7 16-7v36l-16 7-16-7-16 7z", G_BLUE, "M32 12c-7.7 0-13 5.6-13 12.6C19 34 32 48 32 48s13-14 13-23.4C45 17.6 39.7 12 32 12z", "", ci(32, 25, 4.5f), G_DOT);
        put("music", ci(32, 32, 23), G_VIOLET, ci(32, 32, 13), "", ci(32, 32, 4.5f), G_DOT);
        put("phone", rr(9, 9, 46, 46, 15), G_TEAL, "M22 17c2.4-2.4 5.6-2 6.8 1.2l2 5.2c.8 2.2 0 4.2-2 5.4l-1.6 1c2 4.6 5.6 8.4 10.2 10.4l1-1.6c1.2-2 3.2-2.8 5.4-2l5.2 2c3.2 1.2 3.6 4.4 1.2 6.8l-2.6 2.6c-3.4 3.4-10 1.8-17-5.2s-8.6-13.6-5.2-17z", "", "", G_DOT);
        put("radio", rr(7, 19, 50, 36, 11), G_BLUE, rr(12, 25, 24, 24, 8), "M42 30h9M42 37h9M42 44h9M18 19L42 7", ci(24, 37, 5), G_DOT);
        put("camera", rr(7, 19, 50, 36, 11) + rr(21, 11, 22, 12, 5), G_VIOLET, ci(32, 37, 13), "", ci(32, 37, 7.5f) + ci(49, 26, 2.5f), G_BLUE);
        put("link", rr(17, 6, 30, 52, 9), G_BLUE, rr(21, 12, 22, 36, 5), "", ci(32, 52.5f, 2.6f), G_DOT);
        put("bt", ci(32, 32, 23), G_BLUE, "", "M24 24l16 16-8 7V17l8 7-16 16", "", G_DOT);
        put("video", rr(7, 13, 50, 38, 11), G_VIOLET, "M27 22.5c0-2 2.2-3.2 3.8-2.1l11 7.4c1.5 1 1.5 3.2 0 4.2l-11 7.4c-1.6 1.1-3.8-.1-3.8-2.1z", "", "", G_DOT);
        put("gallery", rr(16, 7, 40, 40, 11), G_TEAL, rr(8, 16, 40, 40, 11), "M14 49l9-11 7 7 5-5 8 9", ci(36, 28, 4.5f), G_SUN);
        put("files", "M7 18a7 7 0 0 1 7-7h11l6 6h19a7 7 0 0 1 7 7v24a7 7 0 0 1-7 7H14a7 7 0 0 1-7-7z", G_BLUE, "M12 28a5 5 0 0 1 5-5h36a4 4 0 0 1 4 4.8l-3.8 21.4A6 6 0 0 1 47.3 54H11.5a4 4 0 0 1-4-4.8z", "", "", G_DOT);
        put("browser", ci(32, 32, 23), G_BLUE, "", "M10 32h44M32 10c-8 7-8 37 0 44M32 10c8 7 8 37 0 44M14 21h36M14 43h36", "", G_DOT);
        put("store", rr(9, 20, 46, 36, 11), G_VIOLET, rr(16, 30, 32, 18, 7), "M22 22v-4a10 10 0 0 1 20 0v4", ci(47, 24, 5), G_DOT);
        put("eq", rr(9, 9, 46, 46, 15), G_VIOLET, "", "M21 18v28M32 18v28M43 18v28", ci(21, 38, 5) + ci(32, 24, 5) + ci(43, 34, 5), G_DOT);
        put("weather", ci(40, 24, 13), G_SUN, "M17 52h31a10 10 0 0 0 0-20 14 14 0 0 0-26.6 3.4A8.4 8.4 0 0 0 17 52z", "", "", G_DOT);
        put("calc", rr(13, 6, 38, 52, 11), G_BLUE, rr(18, 11, 28, 12, 5), "", ci(23, 31, 3) + ci(32, 31, 3) + ci(41, 31, 3) + ci(23, 40, 3) + ci(32, 40, 3) + ci(41, 40, 3) + ci(23, 49, 3) + ci(32, 49, 3) + ci(41, 49, 3), G_FROST);
        put("clock", ci(32, 32, 23), G_BLUE, ci(32, 32, 17), "M32 22v10l7 5", "", G_DOT);
        put("settings", gear(), G_VIOLET, ci(32, 32, 10), "", ci(32, 32, 5.5f), G_DOT);
        put("car", "M8 40l4.5-12.5C14 23.5 17 21 21 21h22c4 0 7 2.5 8.5 6.5L56 40v9a3 3 0 0 1-3 3H11a3 3 0 0 1-3-3z", G_BLUE, "M17 31l2.4-5.2c.6-1.3 1.8-2 3.2-2h18.8c1.4 0 2.6.7 3.2 2L47 31z", "", ci(19, 50, 5) + ci(45, 50, 5), G_DOT);
    }

    private static final RectF TMP = new RectF();

    private static Shader grad(Path p, int[] colors, float x2) {
        p.computeBounds(TMP, true);
        float w = TMP.width(), h = TMP.height();
        return new LinearGradient(TMP.left, TMP.top, TMP.left + x2 * w, TMP.top + h, colors, null, Shader.TileMode.CLAMP);
    }

    /** Draws a glass icon into a square of {@code size} px whose top-left is the canvas origin. */
    static void drawGlass(Canvas c, Glass g, float size, Paint p) {
        int save = c.save();
        float k = size / 64f;
        c.scale(k, k);
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.FILL);
        p.setShader(grad(g.back, g.bg, g.bg == G_FROST ? 0.3f : 0.4f));
        c.drawPath(g.back, p);
        if (!g.front.isEmpty()) {
            p.setColor(0xFFFFFFFF);
            p.setShader(grad(g.front, G_FROST, 0.3f));
            c.drawPath(g.front, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.8f);
            p.setColor(0xBFFFFFFF);
            c.drawPath(g.front, p);
        }
        if (!g.detail.isEmpty()) {
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xFFFFFFFF);
            c.drawPath(g.detail, p);
        }
        if (!g.dot.isEmpty()) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShader(grad(g.dot, g.dotFill, g.dotFill == G_DOT ? 0f : 0.4f));
            c.drawPath(g.dot, p);
        }
        p.setShader(null);
        c.restoreToCount(save);
    }

    static final class GlassView extends View {
        private Glass glass;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassView(Context c, String key) {
            super(c);
            glass = glass(key);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
            drawGlass(c, glass, s, paint);
        }
    }

    // ---- Line glyphs (24x24) -------------------------------------------------------------
    static final class Glyph {
        final Path path;
        final boolean fill;
        final float stroke;

        Glyph(String d, boolean fill, float stroke) {
            this.path = SvgPath.parse(d);
            this.fill = fill;
            this.stroke = stroke;
        }
    }

    static final Glyph PHONE = new Glyph("M7 2h10v20H7zM11 18h2", false, 1.9f);
    static final Glyph BACK = new Glyph("M19 12H5M11 5l-7 7 7 7", false, 2.2f);
    static final Glyph PLAY = new Glyph("M8 4l12 8-12 8V4z", true, 0);
    static final Glyph PAUSE = new Glyph("M7 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM15 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", true, 0);
    static final Glyph PREV = new Glyph("M19 20L9 12l10-8v16zM4 5a1 1 0 0 1 1-1h.5a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z", true, 0);
    static final Glyph NEXT = new Glyph("M5 4l10 8-10 8V4zM17.5 5a1 1 0 0 1 1-1h.5a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-.5a1 1 0 0 1-1-1z", true, 0);
    static final Glyph VOLUME = new Glyph("M11 5L6 9H2v6h4l5 4V5zM15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14", false, 1.9f);
    static final Glyph MUTE = new Glyph("M11 5L6 9H2v6h4l5 4V5zM22 9l-6 6M16 9l6 6", false, 1.9f);
    static final Glyph NAV = new Glyph("M3 11l19-9-9 19-2-8-8-2z", false, 2f);
    static final Glyph INFO = new Glyph("M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 16v-4M12 8h.01", false, 2f);
    static final Glyph SEARCH = new Glyph("M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.3-4.3", false, 2f);
    static final Glyph MORE = new Glyph("M12 5h.01M12 12h.01M12 19h.01", false, 3.6f);
    static final Glyph CHEV_LEFT = new Glyph("M15 18l-6-6 6-6", false, 2.4f);
    static final Glyph CHEV_RIGHT = new Glyph("M9 18l6-6-6-6", false, 2.4f);
    static final Glyph REWIND = new Glyph("M11 17l-5-5 5-5M18 17l-5-5 5-5", false, 2.2f);
    static final Glyph FORWARD = new Glyph("M13 17l5-5-5-5M6 17l5-5-5-5", false, 2.2f);
    static final Glyph OPEN = new Glyph("M14 3h7v7M10 14L21 3M21 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5", false, 2f);
    static final Glyph CHECK = new Glyph("M5 12l5 5 9-10", false, 2.6f);
    static final Glyph BLUETOOTH = new Glyph("M7 7l10 10-5 5V2l5 5L7 17", false, 1.9f);
    static final Glyph WIFI = new Glyph("M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M2 9a15 15 0 0 1 20 0M12 20h.01", false, 1.9f);
    static final Glyph SUN = new Glyph("M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4", false, 1.9f);
    static final Glyph GRID = new Glyph("M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z", false, 1.9f);
    static final Glyph HOME = new Glyph("M3 11l9-8 9 8M5 10v10h14V10", false, 1.9f);
    static final Glyph CAR = new Glyph("M5 17h14M3 17v-5l2-5h14l2 5v5M7 17v2M17 17v2M6.5 13.5h.01M17.5 13.5h.01", false, 1.9f);
    static final Glyph MUSIC = new Glyph("M9 18V5l12-2v13M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM21 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z", false, 1.9f);
    static final Glyph CAMERA = new Glyph("M3 7h4l2-3h6l2 3h4v13H3zM12 17a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", false, 1.9f);
    static final Glyph WHEEL = new Glyph("M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM3.5 10H10M14 10h6.5M12 14v7", false, 1.9f);
    static final Glyph BULB = new Glyph("M9 18h6M10 22h4M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z", false, 1.9f);
    static final Glyph CHIP = new Glyph("M6 6h12v12H6zM9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4", false, 1.9f);
    static final Glyph SLIDERS = new Glyph("M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6", false, 1.9f);
    static final Glyph BELL = new Glyph("M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0", false, 1.9f);
    static final Glyph PIN = new Glyph("M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21zM12 12a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z", false, 1.9f);
    static final Glyph REFRESH = new Glyph("M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5", false, 2f);
    static final Glyph STORAGE = new Glyph("M4 5h16v6H4zM4 13h16v6H4zM8 8h.01M8 16h.01", false, 1.9f);
    static final Glyph CLOUD = new Glyph("M7 19h10a5 5 0 0 0 .6-10A7 7 0 0 0 4.3 11.5 4 4 0 0 0 7 19z", false, 1.9f);

    static final class GlyphView extends View {
        private Glyph glyph;
        private int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final Path tmp = new Path();
        private float glyphSize;

        GlyphView(Context c, Glyph g, int color, float sizeDesignPx) {
            super(c);
            this.glyph = g;
            this.color = color;
            this.glyphSize = Ui.uf(sizeDesignPx);
        }

        void set(Glyph g) {
            if (g != glyph) { glyph = g; invalidate(); }
        }

        void setColor(int c) {
            if (c != color) { color = c; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas c) {
            if (glyph == null) return;
            float s = Math.min(glyphSize, Math.min(getWidth(), getHeight()));
            float k = s / 24f;
            m.setScale(k, k);
            m.postTranslate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
            glyph.path.transform(m, tmp);
            paint.setColor(color);
            if (glyph.fill) {
                paint.setStyle(Paint.Style.FILL);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(glyph.stroke * k);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
            }
            c.drawPath(tmp, paint);
        }
    }
}
