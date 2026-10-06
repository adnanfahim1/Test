package com.adnan.glasslauncher;

import android.graphics.Path;

/**
 * Minimal SVG path-data parser (M L H V C S Q T A Z, absolute and relative), so the
 * prototype's icon paths can be drawn natively at any size without image files.
 */
final class SvgPath {
    private SvgPath() {}

    static Path parse(String d) {
        Path p = new Path();
        if (d == null || d.length() == 0) return p;
        Parser s = new Parser(d);
        float cx = 0, cy = 0, sx = 0, sy = 0, lcx = 0, lcy = 0;
        char cmd = 'M', prev = ' ';
        while (true) {
            s.skipSep();
            if (s.end()) break;
            char ch = s.peek();
            if (Character.isLetter(ch)) {
                cmd = ch;
                s.i++;
            } else if (prev == 'M') {
                cmd = 'L';
            } else if (prev == 'm') {
                cmd = 'l';
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? cx : 0, oy = rel ? cy : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    cx = ox + s.num(); cy = oy + s.num();
                    p.moveTo(cx, cy); sx = cx; sy = cy; lcx = cx; lcy = cy;
                    break;
                }
                case 'L': {
                    cx = ox + s.num(); cy = oy + s.num();
                    p.lineTo(cx, cy); lcx = cx; lcy = cy;
                    break;
                }
                case 'H': {
                    cx = (rel ? cx : 0) + s.num();
                    p.lineTo(cx, cy); lcx = cx; lcy = cy;
                    break;
                }
                case 'V': {
                    cy = (rel ? cy : 0) + s.num();
                    p.lineTo(cx, cy); lcx = cx; lcy = cy;
                    break;
                }
                case 'C': {
                    float x1 = ox + s.num(), y1 = oy + s.num(), x2 = ox + s.num(), y2 = oy + s.num();
                    cx = ox + s.num(); cy = oy + s.num();
                    p.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2;
                    break;
                }
                case 'S': {
                    char pu = Character.toUpperCase(prev);
                    float x1 = (pu == 'C' || pu == 'S') ? 2 * cx - lcx : cx;
                    float y1 = (pu == 'C' || pu == 'S') ? 2 * cy - lcy : cy;
                    float x2 = ox + s.num(), y2 = oy + s.num();
                    cx = ox + s.num(); cy = oy + s.num();
                    p.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2;
                    break;
                }
                case 'Q': {
                    float x1 = ox + s.num(), y1 = oy + s.num();
                    cx = ox + s.num(); cy = oy + s.num();
                    p.quadTo(x1, y1, cx, cy); lcx = x1; lcy = y1;
                    break;
                }
                case 'T': {
                    char pu = Character.toUpperCase(prev);
                    float x1 = (pu == 'Q' || pu == 'T') ? 2 * cx - lcx : cx;
                    float y1 = (pu == 'Q' || pu == 'T') ? 2 * cy - lcy : cy;
                    cx = ox + s.num(); cy = oy + s.num();
                    p.quadTo(x1, y1, cx, cy); lcx = x1; lcy = y1;
                    break;
                }
                case 'A': {
                    float rx = s.num(), ry = s.num(), rot = s.num();
                    boolean large = s.flag(), sweep = s.flag();
                    float x = ox + s.num(), y = oy + s.num();
                    arc(p, cx, cy, x, y, rx, ry, rot, large, sweep);
                    cx = x; cy = y; lcx = cx; lcy = cy;
                    break;
                }
                case 'Z': {
                    p.close(); cx = sx; cy = sy; lcx = cx; lcy = cy;
                    break;
                }
                default:
                    return p;
            }
            prev = cmd;
        }
        return p;
    }

    /** Endpoint-parameterised elliptical arc to cubic Béziers (SVG spec, appendix F.6). */
    private static void arc(Path p, float x0, float y0, float x, float y, float rx, float ry,
                            float angle, boolean large, boolean sweep) {
        if (rx == 0 || ry == 0) { p.lineTo(x, y); return; }
        if (x0 == x && y0 == y) return;
        rx = Math.abs(rx); ry = Math.abs(ry);
        double phi = Math.toRadians(angle % 360);
        double cos = Math.cos(phi), sin = Math.sin(phi);
        double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
        double x1 = cos * dx2 + sin * dy2;
        double y1 = -sin * dx2 + cos * dy2;
        double lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry);
        if (lambda > 1) { double sq = Math.sqrt(lambda); rx *= sq; ry *= sq; }
        double num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1;
        double den = rx * rx * y1 * y1 + ry * ry * x1 * x1;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * (rx * y1 / ry);
        double cyp = coef * -(ry * x1 / rx);
        double cxx = cos * cxp - sin * cyp + (x0 + x) / 2.0;
        double cyy = sin * cxp + cos * cyp + (y0 + y) / 2.0;
        double t1 = angle(1, 0, (x1 - cxp) / rx, (y1 - cyp) / ry);
        double dt = angle((x1 - cxp) / rx, (y1 - cyp) / ry, (-x1 - cxp) / rx, (-y1 - cyp) / ry);
        if (!sweep && dt > 0) dt -= 2 * Math.PI;
        else if (sweep && dt < 0) dt += 2 * Math.PI;
        int segs = (int) Math.ceil(Math.abs(dt) / (Math.PI / 2));
        double delta = dt / segs;
        double t = 4.0 / 3.0 * Math.tan(delta / 4);
        double a = t1;
        for (int i = 0; i < segs; i++) {
            double c1 = Math.cos(a), s1 = Math.sin(a);
            double c2 = Math.cos(a + delta), s2 = Math.sin(a + delta);
            double ex1 = c1 - t * s1, ey1 = s1 + t * c1;
            double ex2 = c2 + t * s2, ey2 = s2 - t * c2;
            p.cubicTo(
                    (float) (cxx + rx * (cos * ex1) - ry * (sin * ey1)), (float) (cyy + rx * (sin * ex1) + ry * (cos * ey1)),
                    (float) (cxx + rx * (cos * ex2) - ry * (sin * ey2)), (float) (cyy + rx * (sin * ex2) + ry * (cos * ey2)),
                    (float) (cxx + rx * (cos * c2) - ry * (sin * s2)), (float) (cyy + rx * (sin * c2) + ry * (cos * s2)));
            a += delta;
        }
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double len = Math.sqrt(ux * ux + uy * uy) * Math.sqrt(vx * vx + vy * vy);
        double ang = Math.acos(Math.max(-1, Math.min(1, dot / len)));
        return (ux * vy - uy * vx) < 0 ? -ang : ang;
    }

    private static final class Parser {
        final String s;
        int i = 0;

        Parser(String s) { this.s = s; }

        boolean end() { return i >= s.length(); }
        char peek() { return s.charAt(i); }

        void skipSep() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') i++;
                else break;
            }
        }

        boolean flag() {
            skipSep();
            char c = s.charAt(i++);
            return c == '1';
        }

        float num() {
            skipSep();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false, exp = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') { i++; continue; }
                if (c == '.' && !dot && !exp) { dot = true; i++; continue; }
                if ((c == 'e' || c == 'E') && !exp) {
                    exp = true; i++;
                    if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                    continue;
                }
                break;
            }
            if (start == i) return 0;
            return Float.parseFloat(s.substring(start, i));
        }
    }
}
