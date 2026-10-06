package com.adnan.glasslauncher;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Settings-style page: section list on the RIGHT (driver side, with Back at top-right),
 * content panel on the left. Subclasses fill one section at a time.
 */
abstract class SidebarScreen extends Screen {
    private LinearLayout navList;
    private LinearLayout content;
    private ScrollView contentScroll;
    private final View[] navItems;
    int current = 0;

    SidebarScreen(MainActivity a, int sections) {
        super(a);
        navItems = new View[sections];
    }

    abstract String title();
    abstract String sectionLabel(int i);
    abstract Icons.Glyph sectionGlyph(int i);
    abstract void fillSection(int i, LinearLayout out);
    /** Bottom link in the sidebar (e.g. "Car settings ›"). */
    abstract String linkLabel();
    abstract void onLink();

    @Override
    View build() {
        Context c = a;
        LinearLayout root = Ui.row(c);
        root.setGravity(Gravity.NO_GRAVITY);
        root.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(24));

        FrameLayout panel = new FrameLayout(c);
        panel.setBackground(Ui.glass(24));
        contentScroll = new ScrollView(c);
        contentScroll.setVerticalScrollBarEnabled(false);
        contentScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = Ui.col(c);
        content.setPadding(Ui.u(32), Ui.u(32), Ui.u(32), Ui.u(32));
        contentScroll.addView(content);
        panel.addView(contentScroll, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        root.addView(panel, Ui.lpw(0, Ui.MATCH, 1));
        Ui.rise(panel, 80);

        LinearLayout nav = Ui.col(c);
        LinearLayout head = Ui.row(c);
        head.addView(Ui.text(c, title(), 36, Ui.TEXT, Ui.display(600)), Ui.lpw(0, Ui.WRAP, 1));
        FrameLayout back = Parts.glyphButton(c, Icons.BACK, 56, 16, 24, new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.show(MainActivity.HOME); }
        });
        back.setBackground(Ui.glass(16));
        back.setContentDescription("Back to home");
        head.addView(back, Ui.lp(Ui.u(56), Ui.u(56)));
        nav.addView(head, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 12));

        ScrollView navScroll = new ScrollView(c);
        navScroll.setVerticalScrollBarEnabled(false);
        navScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        navList = Ui.col(c);
        for (int i = 0; i < navItems.length; i++) {
            final int idx = i;
            LinearLayout b = Ui.row(c);
            b.setPadding(Ui.u(20), 0, Ui.u(20), 0);
            b.addView(new Icons.GlyphView(c, sectionGlyph(i), Ui.TEXT_2, 22), Ui.lp(Ui.u(24), Ui.u(24)));
            TextView t = Ui.text(c, sectionLabel(i), 18, Ui.TEXT_2, Ui.body(400));
            b.addView(t, Ui.margins(Ui.lpw(0, Ui.WRAP, 1), 16, 0, 0, 0));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { select(idx); }
            });
            Ui.pressable(b);
            navItems[i] = b;
            navList.addView(b, Ui.margins(Ui.lp(Ui.MATCH, Ui.u(58)), 0, i == 0 ? 0 : 6, 0, 0));
            Ui.rise(b, 40L * i);
        }
        navScroll.addView(navList);
        nav.addView(navScroll, Ui.lpw(Ui.MATCH, 0, 1));

        LinearLayout link = Ui.row(c);
        link.setPadding(Ui.u(20), 0, Ui.u(20), 0);
        link.setBackground(Ui.fill(0, Ui.white(0.14f), 16));
        link.addView(Ui.text(c, linkLabel(), 18, Ui.TEXT, Ui.body(400)), Ui.lpw(0, Ui.WRAP, 1));
        link.addView(new Icons.GlyphView(c, Icons.CHEV_RIGHT, Ui.TEXT_2, 20), Ui.lp(Ui.u(24), Ui.u(24)));
        link.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { onLink(); }
        });
        Ui.pressable(link);
        nav.addView(link, Ui.margins(Ui.lp(Ui.MATCH, Ui.u(60)), 0, 12, 0, 0));
        root.addView(nav, Ui.margins(Ui.lp(Ui.u(320), Ui.MATCH), 24, 0, 0, 0));

        select(current);
        return root;
    }

    void select(int i) {
        current = i;
        for (int k = 0; k < navItems.length; k++) {
            LinearLayout b = (LinearLayout) navItems[k];
            boolean on = k == i;
            b.setBackground(on ? Ui.tile(16) : null);
            ((Icons.GlyphView) b.getChildAt(0)).setColor(on ? Ui.accentLight() : Ui.TEXT_2);
            TextView t = (TextView) b.getChildAt(1);
            t.setTextColor(on ? Ui.TEXT : Ui.TEXT_2);
            t.setTypeface(Ui.body(on ? 600 : 400));
        }
        refresh();
        contentScroll.scrollTo(0, 0);
    }

    /** Rebuilds the visible section (after a change). */
    void refresh() {
        if (content == null) return;
        content.removeAllViews();
        fillSection(current, content);
    }

    // ---- Row builders ---------------------------------------------------------------------
    void heading(LinearLayout out, String s, String sub) {
        out.addView(Ui.text(a, s, 30, Ui.TEXT, Ui.display(600)));
        if (sub != null) {
            TextView t = Ui.multiline(a, sub, 15, Ui.TEXT_2, Ui.body(400));
            out.addView(t, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
        }
        out.addView(Ui.space(a), Ui.lp(1, Ui.u(14)));
    }

    void label(LinearLayout out, String s) {
        TextView t = Ui.text(a, s.toUpperCase(java.util.Locale.US), 12, Ui.MUTED, Ui.body(600));
        t.setLetterSpacing(0.12f);
        out.addView(t, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 6, 18, 0, 10));
    }

    void para(LinearLayout out, String s) {
        TextView t = Ui.multiline(a, s, 15, Ui.TEXT_2, Ui.body(400));
        out.addView(t, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 4, 4, 4, 12));
    }

    private LinearLayout baseRow(String title, String sub) {
        LinearLayout r = Ui.row(a);
        r.setPadding(Ui.u(20), Ui.u(12), Ui.u(16), Ui.u(12));
        r.setMinimumHeight(Ui.u(68));
        r.setBackground(Ui.tile(16));
        LinearLayout tc = Ui.col(a);
        tc.addView(Ui.text(a, title, 18, Ui.TEXT, Ui.body(500)));
        if (sub != null) {
            TextView s = Ui.multiline(a, sub, 13, Ui.MUTED, Ui.body(400));
            tc.addView(s, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 4, 0, 0));
        }
        r.addView(tc, Ui.lpw(0, Ui.WRAP, 1));
        return r;
    }

    private void add(LinearLayout out, View row) {
        out.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 10));
    }

    /** Row that opens something; shows a value and a chevron. */
    LinearLayout nav(LinearLayout out, String title, String sub, String value, View.OnClickListener l) {
        LinearLayout r = baseRow(title, sub);
        if (value != null) {
            TextView v = Ui.text(a, value, 16, Ui.TEXT_2, Ui.body(400));
            v.setGravity(Gravity.RIGHT);
            r.addView(v, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 12, 0, 6, 0));
        }
        if (l != null) {
            r.addView(new Icons.GlyphView(a, Icons.CHEV_RIGHT, Ui.MUTED, 20), Ui.lp(Ui.u(28), Ui.u(28)));
            r.setOnClickListener(l);
            Ui.pressable(r);
        }
        add(out, r);
        return r;
    }

    Widgets.Toggle toggle(LinearLayout out, String title, String sub, boolean on, Widgets.Toggle.OnChange l) {
        LinearLayout r = baseRow(title, sub);
        final Widgets.Toggle t = new Widgets.Toggle(a, on);
        t.setOnChange(l);
        t.setContentDescription(title);
        r.addView(t, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 12, 0, 0, 0));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { t.setChecked(!t.isChecked(), true); }
        });
        add(out, r);
        return t;
    }

    Widgets.Slider slider(LinearLayout out, String title, int max, int value, Widgets.Slider.OnChange l) {
        LinearLayout r = baseRow(title, null);
        Widgets.Slider s = new Widgets.Slider(a, max, value);
        s.setOnChange(l);
        s.setContentDescription(title);
        r.addView(s, Ui.margins(Ui.lp(Ui.u(320), Ui.u(40)), 12, 0, 0, 0));
        add(out, r);
        return s;
    }

    interface Pick { void picked(int i); }

    /** Row with segmented options (Font size S/M/L, units). */
    void segment(LinearLayout out, String title, String sub, String[] opts, int selected, final Pick l) {
        LinearLayout r = baseRow(title, sub);
        LinearLayout seg = Ui.row(a);
        seg.setPadding(Ui.u(4), Ui.u(4), Ui.u(4), Ui.u(4));
        seg.setBackground(Ui.fill(Ui.white(0.06f), Ui.white(0.12f), 14));
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            TextView t = Ui.text(a, opts[i], 15, i == selected ? 0xFFFFFFFF : Ui.TEXT_2, Ui.body(i == selected ? 600 : 400));
            t.setGravity(Gravity.CENTER);
            t.setPadding(Ui.u(16), 0, Ui.u(16), 0);
            if (i == selected) t.setBackground(Ui.fill(Ui.accent(), 0, 11).glow(Ui.accentGlow()));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { l.picked(idx); }
            });
            Ui.pressable(t);
            seg.addView(t, Ui.lp(Ui.WRAP, Ui.u(44)));
        }
        r.addView(seg, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 12, 0, 0, 0));
        add(out, r);
    }

    void button(LinearLayout out, String label, boolean danger, View.OnClickListener l) {
        TextView b = danger ? Parts.outlineButton(a, label, 16, 22, 52, 16, l) : Parts.accentButton(a, label, 16, 22, 52, 16, l);
        if (danger) {
            b.setTextColor(0xFFFF8A80);
            b.setBackground(Ui.fill(Ui.withAlpha(Ui.DANGER, 0x26), Ui.withAlpha(Ui.DANGER, 0x99), 16));
        }
        LinearLayout.LayoutParams p = Ui.margins(Ui.lp(Ui.WRAP, Ui.u(52)), 0, 8, 0, 12);
        out.addView(b, p);
    }

    @Override
    void onShow() {
        a.background().setWallpaperMode(false);
        refresh();
    }
}
