package com.adnan.glasslauncher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** App drawer: section tabs, search, 2-row horizontal grid, scrollbar and ‹ › buttons. */
final class AppsScreen extends Screen {
    private List<AppsRepo.App> all = new ArrayList<AppsRepo.App>();
    private int section = 0;
    private String query = "";
    private TextView count, pageLabel;
    private TextView[] secTabs;
    private View pill;
    private HorizontalScrollView scroller;
    private Grid grid;
    private View thumb;
    private FrameLayout track;
    private FrameLayout leftBtn, rightBtn;
    private EditText search;
    private TextView empty;

    AppsScreen(MainActivity a) { super(a); }

    @Override
    View build() {
        Context c = a;
        LinearLayout root = Ui.col(c);
        root.setPadding(Ui.u(40), Ui.u(28), Ui.u(40), Ui.u(24));

        // Header: search (left) ... title + count ... back (right).
        LinearLayout head = Ui.row(c);
        LinearLayout searchBox = Ui.row(c);
        searchBox.setPadding(Ui.u(20), 0, Ui.u(20), 0);
        searchBox.setBackground(Ui.fill(Ui.white(0.07f), Ui.white(0.12f), 18));
        searchBox.addView(new Icons.GlyphView(c, Icons.SEARCH, Ui.MUTED, 20), Ui.lp(Ui.u(22), Ui.u(22)));
        search = MusicScreen.searchField(c, "Search apps", 18);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int co, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int be, int co) {}
            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.getDefault());
                fill();
            }
        });
        searchBox.addView(search, Ui.margins(Ui.lpw(0, Ui.MATCH, 1), 12, 0, 0, 0));
        head.addView(searchBox, Ui.lp(Ui.u(360), Ui.u(56)));
        head.addView(Ui.space(c), Ui.lpw(0, 1, 1));
        LinearLayout titleRow = Ui.row(c);
        titleRow.setGravity(Gravity.BOTTOM);
        titleRow.addView(Ui.text(c, "Apps", 38, Ui.TEXT, Ui.display(600)));
        count = Ui.text(c, "", 16, Ui.MUTED, Ui.body(400));
        titleRow.addView(count, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 12, 0, 0, 6));
        head.addView(titleRow);
        FrameLayout back = Parts.glyphButton(c, Icons.BACK, 56, 18, 24, new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.show(MainActivity.HOME); }
        });
        back.setContentDescription("Back to home");
        head.addView(back, Ui.margins(Ui.lp(Ui.u(56), Ui.u(56)), 18, 0, 0, 0));
        root.addView(head);
        Ui.rise(head, 0);

        // Section tabs (right aligned) with sliding pill.
        FrameLayout tabsBox = new FrameLayout(c);
        tabsBox.setPadding(Ui.u(6), Ui.u(6), Ui.u(6), Ui.u(6));
        tabsBox.setBackground(Ui.fill(Ui.white(0.06f), Ui.white(0.12f), 22));
        pill = new View(c);
        pill.setBackground(new Ui.GlassDrawable(Ui.white(0.16f), Ui.white(0.22f), Ui.white(0.25f), Ui.uf(16)));
        tabsBox.addView(pill, Ui.flp(Ui.u(132), Ui.u(52), Gravity.LEFT | Gravity.TOP));
        LinearLayout tabRow = Ui.row(c);
        secTabs = new TextView[AppsRepo.SECTIONS.length];
        for (int i = 0; i < secTabs.length; i++) {
            final int idx = i;
            TextView t = Ui.text(c, AppsRepo.SECTIONS[i], 17, Ui.MUTED, Ui.body(400));
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { selectSection(idx, true); }
            });
            secTabs[i] = t;
            tabRow.addView(t, Ui.lp(Ui.u(132), Ui.u(52)));
        }
        tabsBox.addView(tabRow);
        LinearLayout.LayoutParams tp = Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 22, 0, 0);
        tp.gravity = Gravity.RIGHT;
        root.addView(tabsBox, tp);
        Ui.rise(tabsBox, 80);

        // Grid.
        FrameLayout gridBox = new FrameLayout(c);
        scroller = new HorizontalScrollView(c);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroller.setFillViewport(true);
        grid = new Grid(c);
        scroller.addView(grid, new FrameLayout.LayoutParams(Ui.WRAP, Ui.MATCH));
        scroller.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int ox, int oy) { updateScrollbar(); }
        });
        gridBox.addView(scroller, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        empty = Ui.text(c, "", 18, Ui.MUTED, Ui.body(400));
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        gridBox.addView(empty, Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.CENTER));
        root.addView(gridBox, Ui.margins(Ui.lpw(Ui.MATCH, 0, 1), 0, 22, 0, 0));

        // Bottom: scrollbar, page label, ‹ › (driver side).
        LinearLayout bottom = Ui.row(c);
        track = new FrameLayout(c);
        track.setBackground(Ui.fill(Ui.white(0.12f), 0, 4));
        thumb = new View(c);
        thumb.setBackground(Ui.fill(Ui.accent(), 0, 4).glow(Ui.accentGlow()));
        track.addView(thumb, Ui.flp(Ui.u(100), Ui.MATCH, Gravity.LEFT));
        bottom.addView(track, Ui.lpw(0, Ui.u(8), 1));
        pageLabel = Ui.text(c, "", 15, Ui.MUTED, Ui.body(400));
        bottom.addView(pageLabel, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 18, 0, 0, 0));
        leftBtn = Parts.glyphButton(c, Icons.CHEV_LEFT, 60, 18, 26, new View.OnClickListener() {
            @Override
            public void onClick(View v) { page(-1); }
        });
        leftBtn.setContentDescription("Scroll left");
        rightBtn = Parts.glyphButton(c, Icons.CHEV_RIGHT, 60, 18, 26, new View.OnClickListener() {
            @Override
            public void onClick(View v) { page(1); }
        });
        rightBtn.setContentDescription("Scroll right");
        bottom.addView(leftBtn, Ui.margins(Ui.lp(Ui.u(60), Ui.u(60)), 18, 0, 0, 0));
        bottom.addView(rightBtn, Ui.margins(Ui.lp(Ui.u(60), Ui.u(60)), 10, 0, 0, 0));
        root.addView(bottom, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 18, 0, 0));
        Ui.rise(bottom, 160);

        selectSection(0, false);
        return root;
    }

    private void selectSection(int i, boolean animate) {
        section = i;
        for (int k = 0; k < secTabs.length; k++) {
            secTabs[k].setTextColor(k == i ? 0xFFFFFFFF : Ui.MUTED);
            secTabs[k].setTypeface(Ui.body(k == i ? 600 : 400));
        }
        float x = Ui.uf(132) * i;
        if (animate && !Ui.reduceMotion) pill.animate().translationX(x).setDuration(450).setInterpolator(Ui.OVERSHOOT).start();
        else pill.setTranslationX(x);
        fill();
    }

    private void page(int dir) {
        scroller.smoothScrollBy(dir * Math.max(1, scroller.getWidth() - Ui.u(196)), 0);
    }

    private void fill() {
        if (grid == null) return;
        grid.removeAllViews();
        String sec = AppsRepo.SECTIONS[section];
        int n = 0;
        for (AppsRepo.App app : all) {
            if (section != 0 && !sec.equals(app.section)) continue;
            if (query.length() > 0 && !app.label.toLowerCase(Locale.getDefault()).contains(query)) continue;
            View v = item(app);
            grid.addView(v);
            if (n < 18) {
                v.setAlpha(0f);
                if (Ui.reduceMotion) v.setAlpha(1f);
                else v.animate().alpha(1f).setStartDelay(150 + 30L * n).setDuration(400).start();
            }
            n++;
        }
        count.setText(n == 1 ? "1 app" : n + " apps");
        if (n == 0) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(query.length() > 0 ? "No apps match “" + query + "”"
                    : "No apps in " + sec + " yet. Long-press any app in All to move it here.");
        } else {
            empty.setVisibility(View.GONE);
        }
        scroller.scrollTo(0, 0);
        scroller.post(new Runnable() {
            @Override
            public void run() { updateScrollbar(); }
        });
    }

    private View item(final AppsRepo.App app) {
        Context c = a;
        LinearLayout it = Ui.col(c);
        it.setGravity(Gravity.CENTER);
        FrameLayout tile = new FrameLayout(c);
        tile.setBackground(Ui.glass(30));
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(app.icon(a.getPackageManager()));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(iv, Ui.flp(Ui.u(76), Ui.u(76), Gravity.CENTER));
        it.addView(tile, Ui.lp(Ui.u(108), Ui.u(108)));
        TextView t = Ui.text(c, app.label, 18, Ui.TEXT, Ui.body(600));
        t.setGravity(Gravity.CENTER);
        it.addView(t, Ui.margins(Ui.lp(Ui.u(180), Ui.WRAP), 0, 10, 0, 0));
        it.setContentDescription(app.label);
        it.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!AppsRepo.launch(a, app.component)) a.toast("Couldn't open " + app.label);
            }
        });
        it.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                menu(app);
                return true;
            }
        });
        Ui.pressable(it);
        return it;
    }

    private void menu(final AppsRepo.App app) {
        final String[] opts = {"Move to Drive", "Move to Media", "Move to Connect", "Move to Tools", "Move to System",
                "Reset section", "App info", "Uninstall"};
        new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(app.label + " · " + app.section)
                .setItems(opts, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which <= 4) {
                            app.section = AppsRepo.SECTIONS[which + 1];
                            a.prefs().setSection(app.key(), app.section);
                            a.toast(app.label + " moved to " + app.section);
                            fill();
                        } else if (which == 5) {
                            a.prefs().setSection(app.key(), null);
                            app.section = AppsRepo.guessSection(app);
                            fill();
                        } else if (which == 6) {
                            a.startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)), "App info isn't available");
                        } else {
                            a.startSafe(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)), "This app can't be uninstalled here");
                        }
                    }
                }).show();
    }

    private void updateScrollbar() {
        if (scroller == null || track == null) return;
        int view = scroller.getWidth(), content = grid.getWidth();
        int tw = track.getWidth();
        if (view <= 0 || tw <= 0) return;
        float frac = content > 0 ? Math.min(1f, view / (float) content) : 1f;
        int thumbW = Math.max(Ui.u(48), Math.round(tw * frac));
        int max = Math.max(0, content - view);
        float pos = max > 0 ? scroller.getScrollX() / (float) max : 0;
        ViewGroup.LayoutParams lp = thumb.getLayoutParams();
        if (lp.width != thumbW) { lp.width = thumbW; thumb.setLayoutParams(lp); }
        thumb.setTranslationX((tw - thumbW) * pos);
        int pages = Math.max(1, (int) Math.ceil(content / (double) Math.max(1, view)));
        int pageNo = max > 0 ? Math.min(pages, 1 + Math.round(pos * (pages - 1))) : 1;
        pageLabel.setText("Page " + pageNo + " of " + pages);
        leftBtn.setAlpha(scroller.getScrollX() > 0 ? 1f : 0.4f);
        rightBtn.setAlpha(scroller.getScrollX() < max ? 1f : 0.4f);
        rightBtn.setBackground(scroller.getScrollX() < max ? Ui.fill(Ui.withAlpha(Ui.accent(), 0x66), Ui.white(0.16f), 18) : Ui.tile(18));
    }

    @Override
    void onShow() {
        a.background().setWallpaperMode(false);
        all = AppsRepo.load(a, a.prefs());
        fill();
    }

    @Override
    void onHide() {
        if (search != null && search.getText().length() > 0) search.setText("");
        a.hideKeyboard(search);
    }

    /** Lays children out in 2 rows, column by column (196 wide, 14 row gap, 8 column gap). */
    private static final class Grid extends ViewGroup {
        Grid(Context c) { super(c); }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int h = MeasureSpec.getSize(hSpec);
            int colW = Ui.u(196), gapX = Ui.u(8), gapY = Ui.u(14);
            int cellH = Math.max(0, (h - gapY) / 2);
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(colW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));
            }
            int cols = (n + 1) / 2;
            int w = cols > 0 ? cols * colW + (cols - 1) * gapX : 0;
            setMeasuredDimension(Math.max(w, MeasureSpec.getSize(wSpec) > 0 && MeasureSpec.getMode(wSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(wSpec) : 0), h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int colW = Ui.u(196), gapX = Ui.u(8), gapY = Ui.u(14);
            int cellH = Math.max(0, (getHeight() - gapY) / 2);
            for (int i = 0; i < getChildCount(); i++) {
                int col = i / 2, row = i % 2;
                int x = col * (colW + gapX), y = row * (cellH + gapY);
                getChildAt(i).layout(x, y, x + colW, y + cellH);
            }
        }
    }
}
