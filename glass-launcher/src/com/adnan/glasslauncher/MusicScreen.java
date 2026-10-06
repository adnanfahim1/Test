package com.adnan.glasslauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.session.MediaSession;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Now Playing for whichever app holds the active media session. The right column is the
 * player (Back / Volume at top-right); the left lists the session's queue (when the app
 * exposes one) or the installed music apps.
 */
final class MusicScreen extends Screen {
    private Widgets.Disc disc;
    private TextView title, artist, elapsed, total, emptyNote;
    private Widgets.Slider seek;
    private Icons.GlyphView playGlyph, volGlyph;
    private FrameLayout playBtn;
    private TextView volLabel;
    private LinearLayout volPop;
    private Widgets.Slider volSlider;
    private LinearLayout list;
    private EditText search;
    private TextView[] tabs;
    private View indicator;
    private int tab = 0;
    private String query = "";
    private String listSig = "";

    MusicScreen(MainActivity a) { super(a); }

    @Override
    View build() {
        Context c = a;
        LinearLayout root = Ui.row(c);
        root.setGravity(Gravity.NO_GRAVITY);
        root.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(24));

        View left = buildList(c);
        root.addView(left, Ui.lpw(0, Ui.MATCH, 1));
        FrameLayout player = buildPlayer(c);
        root.addView(player, Ui.margins(Ui.lp(Ui.u(520), Ui.MATCH), 40, 0, 0, 0));
        Ui.rise(player, 0);
        Ui.rise(left, 120);
        return root;
    }

    // ---- Player (right) -------------------------------------------------------------------
    private FrameLayout buildPlayer(Context c) {
        FrameLayout f = new FrameLayout(c);
        LinearLayout col = Ui.col(c);
        col.setGravity(Gravity.CENTER);

        FrameLayout box = new FrameLayout(c);
        box.setBackground(Ui.glass(34));
        disc = new Widgets.Disc(c, 0.4545f, true);
        disc.showArm(true);
        box.addView(disc, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        col.addView(box, Ui.lp(Ui.u(250), Ui.u(250)));

        title = Ui.text(c, "", 34, Ui.TEXT, Ui.display(600));
        title.setGravity(Gravity.CENTER);
        artist = Ui.text(c, "", 18, Ui.MUTED, Ui.body(400));
        artist.setGravity(Gravity.CENTER);
        col.addView(title, Ui.margins(Ui.lp(Ui.u(460), Ui.WRAP), 0, 22, 0, 0));
        col.addView(artist, Ui.margins(Ui.lp(Ui.u(460), Ui.WRAP), 0, 6, 0, 0));

        LinearLayout ctl = Ui.row(c);
        ctl.addView(Parts.ctlButton(c, Icons.REWIND, 56, "Back 10 seconds", new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.media().seekTo(a.media().position() - 10000); }
        }));
        ctl.addView(Ui.space(c), Ui.lpw(0, 1, 1));
        ctl.addView(Parts.ctlButton(c, Icons.PREV, 64, "Previous track", new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.media().prev(); }
        }));
        ctl.addView(Ui.space(c), Ui.lpw(0, 1, 1));
        playBtn = new FrameLayout(c);
        playGlyph = new Icons.GlyphView(c, Icons.PLAY, 0xFFFFFFFF, 34);
        playBtn.addView(playGlyph, Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER));
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.media().togglePlay();
                v.postDelayed(new Runnable() { @Override public void run() { onMedia(); } }, 300);
            }
        });
        Ui.pressable(playBtn);
        ctl.addView(playBtn, Ui.lp(Ui.u(88), Ui.u(88)));
        ctl.addView(Ui.space(c), Ui.lpw(0, 1, 1));
        ctl.addView(Parts.ctlButton(c, Icons.NEXT, 64, "Next track", new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.media().next(); }
        }));
        ctl.addView(Ui.space(c), Ui.lpw(0, 1, 1));
        ctl.addView(Parts.ctlButton(c, Icons.FORWARD, 56, "Forward 10 seconds", new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.media().seekTo(a.media().position() + 10000); }
        }));
        col.addView(ctl, Ui.margins(Ui.lp(Ui.u(460), Ui.WRAP), 0, 22, 0, 0));

        LinearLayout seekRow = Ui.row(c);
        elapsed = Ui.text(c, "0:00", 15, Ui.MUTED, Ui.body(400));
        total = Ui.text(c, "0:00", 15, Ui.MUTED, Ui.body(400));
        total.setGravity(Gravity.RIGHT);
        seek = new Widgets.Slider(c, 1000, 0);
        seek.setContentDescription("Seek");
        seek.setOnRelease(new Widgets.Slider.OnRelease() {
            @Override
            public void released(int value) {
                long d = a.media().duration();
                if (d > 0) a.media().seekTo(d * value / 1000);
            }
        });
        seek.setOnChange(new Widgets.Slider.OnChange() {
            @Override
            public void changed(int value, boolean fromUser) {
                long d = a.media().duration();
                if (d > 0) elapsed.setText(Ui.mmss(d * value / 1000));
            }
        });
        seekRow.addView(elapsed, Ui.lp(Ui.u(48), Ui.WRAP));
        seekRow.addView(seek, Ui.margins(Ui.lpw(0, Ui.u(36), 1), 14, 0, 14, 0));
        seekRow.addView(total, Ui.lp(Ui.u(48), Ui.WRAP));
        col.addView(seekRow, Ui.margins(Ui.lp(Ui.u(460), Ui.WRAP), 0, 22, 0, 0));

        f.addView(col, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        // Top-right column: Back, Volume (pop-out to the left).
        LinearLayout side = Ui.col(c);
        side.addView(Parts.glyphButton(c, Icons.BACK, 56, 16, 24, new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.show(MainActivity.HOME); }
        }));
        side.getChildAt(0).setContentDescription("Back to home");
        LinearLayout volBtn = Ui.col(c);
        volBtn.setGravity(Gravity.CENTER);
        volBtn.setBackground(Ui.fill(Ui.white(0.07f), Ui.white(0.12f), 16));
        volGlyph = new Icons.GlyphView(c, Icons.VOLUME, Ui.TEXT, 22);
        volBtn.addView(volGlyph, Ui.lp(Ui.u(24), Ui.u(24)));
        volLabel = Ui.text(c, "", 11, Ui.TEXT_2, Ui.body(500));
        volLabel.setGravity(Gravity.CENTER);
        volBtn.addView(volLabel, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
        volBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleVolPop(); }
        });
        volBtn.setContentDescription("Volume");
        Ui.pressable(volBtn);
        side.addView(volBtn, Ui.margins(Ui.lp(Ui.u(56), Ui.u(64)), 0, 10, 0, 0));
        f.addView(side, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.RIGHT));

        volPop = Ui.row(c);
        volPop.setPadding(Ui.u(14), Ui.u(12), Ui.u(14), Ui.u(12));
        volPop.setBackground(Ui.fill(0xF0202128, Ui.white(0.18f), 20));
        FrameLayout mute = Parts.glyphButton(c, Icons.MUTE, 48, 14, 22, new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.media().toggleMute(); updateVolume(); }
        });
        mute.setContentDescription("Mute");
        volPop.addView(mute);
        volSlider = new Widgets.Slider(c, a.media().maxVolume(), a.media().volume());
        volSlider.setContentDescription("Volume level");
        volSlider.setOnChange(new Widgets.Slider.OnChange() {
            @Override
            public void changed(int value, boolean fromUser) {
                a.media().setVolume(value);
                updateVolume();
            }
        });
        volPop.addView(volSlider, Ui.margins(Ui.lpw(0, Ui.u(40), 1), 12, 0, 0, 0));
        FrameLayout.LayoutParams vp = Ui.flp(Ui.u(340), Ui.WRAP, Gravity.TOP | Gravity.RIGHT);
        vp.setMargins(0, Ui.u(66), Ui.u(72), 0);
        volPop.setVisibility(View.GONE);
        f.addView(volPop, vp);
        return f;
    }

    private void toggleVolPop() {
        boolean show = volPop.getVisibility() != View.VISIBLE;
        volPop.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            updateVolume();
            Ui.rise(volPop, 0);
            volPop.removeCallbacks(hideVol);
            volPop.postDelayed(hideVol, 6000);
        }
    }

    private final Runnable hideVol = new Runnable() {
        @Override
        public void run() {
            if (volSlider.isDragging()) volPop.postDelayed(this, 3000);
            else volPop.setVisibility(View.GONE);
        }
    };

    private void updateVolume() {
        MediaHub m = a.media();
        boolean muted = m.isMuted();
        volGlyph.set(muted ? Icons.MUTE : Icons.VOLUME);
        volLabel.setText(muted ? "Muted" : String.valueOf(m.volume()));
        volSlider.setMax(m.maxVolume());
        volSlider.setValue(muted ? 0 : m.volume());
    }

    // ---- List (left) ----------------------------------------------------------------------
    private View buildList(Context c) {
        LinearLayout col = Ui.col(c);
        LinearLayout top = Ui.row(c);
        FrameLayout tabBox = new FrameLayout(c);
        LinearLayout tabRow = Ui.row(c);
        String[] names = {"Queue", "Music apps"};
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = Ui.text(c, names[i], 19, Ui.MUTED, Ui.body(400));
            t.setGravity(Gravity.CENTER);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { selectTab(idx, true); }
            });
            tabs[i] = t;
            tabRow.addView(t, Ui.lp(Ui.u(150), Ui.u(48)));
        }
        tabBox.addView(tabRow, Ui.flp(Ui.WRAP, Ui.u(48), Gravity.TOP));
        indicator = new View(c);
        indicator.setBackground(Ui.fill(Ui.accent(), 0, 3).glow(Ui.accentGlow()));
        FrameLayout.LayoutParams ip = Ui.flp(Ui.u(40), Ui.u(5), Gravity.BOTTOM | Gravity.LEFT);
        ip.bottomMargin = Ui.u(4);
        tabBox.addView(indicator, ip);
        top.addView(tabBox, Ui.lp(Ui.WRAP, Ui.u(64)));
        top.addView(Ui.space(c), Ui.lpw(0, 1, 1));

        LinearLayout searchBox = Ui.row(c);
        searchBox.setPadding(Ui.u(16), 0, Ui.u(16), 0);
        searchBox.setBackground(Ui.fill(Ui.white(0.07f), Ui.white(0.10f), 24));
        searchBox.addView(new Icons.GlyphView(c, Icons.SEARCH, Ui.MUTED, 18), Ui.lp(Ui.u(20), Ui.u(20)));
        search = searchField(c, "Search", 16);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int co, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int be, int co) {}
            @Override
            public void afterTextChanged(Editable e) {
                query = e.toString().trim().toLowerCase(Locale.getDefault());
                listSig = "";
                rebuildList();
            }
        });
        searchBox.addView(search, Ui.margins(Ui.lpw(0, Ui.MATCH, 1), 10, 0, 0, 0));
        top.addView(searchBox, Ui.lp(Ui.u(220), Ui.u(48)));
        col.addView(top);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = Ui.col(c);
        list.setPadding(Ui.u(2), Ui.u(2), Ui.u(8), Ui.u(24));
        sv.addView(list);
        col.addView(sv, Ui.margins(Ui.lpw(Ui.MATCH, 0, 1), 0, 22, 0, 0));
        emptyNote = Ui.multiline(c, "", 17, Ui.MUTED, Ui.body(400));
        selectTab(0, false);
        return col;
    }

    static EditText searchField(Context c, String hint, float size) {
        EditText e = new EditText(c);
        e.setBackground(null);
        e.setHint(hint);
        e.setHintTextColor(Ui.FAINT);
        e.setTextColor(Ui.TEXT);
        e.setTypeface(Ui.body(400));
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.uf(size) * Ui.textScale());
        e.setSingleLine(true);
        e.setPadding(0, 0, 0, 0);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        return e;
    }

    private void selectTab(int i, boolean animate) {
        tab = i;
        for (int k = 0; k < tabs.length; k++) {
            tabs[k].setTextColor(k == i ? 0xFFFFFFFF : Ui.MUTED);
            tabs[k].setTypeface(Ui.body(k == i ? 600 : 400));
        }
        float x = Ui.uf(150) * i + (Ui.uf(150) - Ui.uf(40)) / 2f;
        if (animate && !Ui.reduceMotion) indicator.animate().translationX(x).setDuration(450).setInterpolator(Ui.OVERSHOOT).start();
        else indicator.setTranslationX(x);
        listSig = "";
        rebuildList();
    }

    private boolean matches(String s) {
        return query.length() == 0 || (s != null && s.toLowerCase(Locale.getDefault()).contains(query));
    }

    private void rebuildList() {
        if (list == null) return;
        MediaHub m = a.media();
        if (tab == 0) {
            List<MediaSession.QueueItem> q = m.queue();
            long active = m.activeQueueId();
            String sig = "q" + q.size() + ":" + active + ":" + m.packageName() + ":" + m.hasAccess();
            if (sig.equals(listSig)) return;
            listSig = sig;
            list.removeAllViews();
            if (!m.hasAccess()) {
                list.addView(note("Allow notification access to see the queue of the app that's playing.", "Allow access", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { a.askNotificationAccess(); }
                }));
                return;
            }
            if (q.isEmpty()) {
                list.addView(note(m.hasSession()
                        ? "This music app doesn't share its queue. Use the controls on the right, or switch tracks in the app."
                        : "Nothing is playing. Pick a music app under “Music apps”.", null, null));
                return;
            }
            int n = 0;
            for (final MediaSession.QueueItem item : q) {
                MediaDescription d = item.getDescription();
                String t = d.getTitle() != null ? d.getTitle().toString() : "Track";
                String s = d.getSubtitle() != null ? d.getSubtitle().toString() : "";
                if (!matches(t) && !matches(s)) continue;
                boolean playing = item.getQueueId() == active;
                View row = row(t, s, d.getIconBitmap(), null, playing, playing && m.isPlaying(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { a.media().skipToQueueItem(item.getQueueId()); }
                });
                list.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, n == 0 ? 0 : 10, 0, 0));
                if (n < 12) Ui.rise(row, 30L * n);
                n++;
            }
            if (n == 0) list.addView(note("No songs match “" + query + "”", null, null));
        } else {
            final String playingPkg = m.packageName();
            String sig = "a:" + playingPkg + ":" + query + ":" + m.isPlaying();
            if (sig.equals(listSig)) return;
            listSig = sig;
            list.removeAllViews();
            PackageManager pm = a.getPackageManager();
            List<ResolveInfo> apps = musicApps(pm);
            int n = 0;
            for (final ResolveInfo ri : apps) {
                String label = ri.loadLabel(pm).toString();
                if (!matches(label)) continue;
                boolean playing = ri.activityInfo.packageName.equals(playingPkg);
                String sub = playing ? (a.media().isPlaying() ? "Playing now" : "Paused") : "Open app";
                View row = row(label, sub, null, ri.loadIcon(pm), playing, playing && m.isPlaying(), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                        if (!AppsRepo.launch(a, cn)) a.toast("Couldn't open that app");
                    }
                });
                list.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, n == 0 ? 0 : 10, 0, 0));
                if (n < 12) Ui.rise(row, 30L * n);
                n++;
            }
            if (n == 0) list.addView(note(query.length() > 0 ? "No apps match “" + query + "”" : "No music apps found.", null, null));
        }
    }

    /** Launchable apps that declare the music category or a media browser service. */
    static List<ResolveInfo> musicApps(PackageManager pm) {
        Set<String> pkgs = new HashSet<String>();
        try {
            for (ResolveInfo r : pm.queryIntentActivities(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), 0)) {
                pkgs.add(r.activityInfo.packageName);
            }
        } catch (Exception ignored) {}
        try {
            for (ResolveInfo r : pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0)) {
                pkgs.add(r.serviceInfo.packageName);
            }
        } catch (Exception ignored) {}
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(main, 0)) {
            if (pkgs.contains(r.activityInfo.packageName)) out.add(r);
        }
        final Collator col = Collator.getInstance();
        final PackageManager fpm = pm;
        Collections.sort(out, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo x, ResolveInfo y) { return col.compare(x.loadLabel(fpm).toString(), y.loadLabel(fpm).toString()); }
        });
        return out;
    }

    private View note(String text, String btn, View.OnClickListener l) {
        LinearLayout col = Ui.col(a);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.u(20), Ui.u(40), Ui.u(20), Ui.u(20));
        TextView t = Ui.multiline(a, text, 17, Ui.MUTED, Ui.body(400));
        t.setGravity(Gravity.CENTER);
        col.addView(t);
        if (btn != null) col.addView(Parts.accentButton(a, btn, 16, 22, 52, 16, l), Ui.margins(Ui.lp(Ui.WRAP, Ui.u(52)), 0, 18, 0, 0));
        return col;
    }

    private View row(String t, String s, Bitmap art, Drawable icon, boolean current, boolean playing, View.OnClickListener l) {
        Context c = a;
        LinearLayout r = Ui.row(c);
        r.setPadding(Ui.u(10), Ui.u(10), Ui.u(12), Ui.u(10));
        r.setBackground(current ? Ui.fill(Ui.white(0.12f), Ui.withAlpha(Ui.accent(), 0x99), 20)
                : Ui.tile(20));
        FrameLayout artBox = new FrameLayout(c);
        if (art != null) {
            ImageView iv = new ImageView(c);
            iv.setImageBitmap(art);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setBackground(Ui.fill(0xFF202228, 0, 14));
            artBox.addView(iv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        } else if (icon != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(icon);
            iv.setPadding(Ui.u(6), Ui.u(6), Ui.u(6), Ui.u(6));
            iv.setBackground(Ui.fill(Ui.white(0.08f), Ui.white(0.14f), 14));
            artBox.addView(iv, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        } else {
            artBox.addView(new Icons.GlassView(c, "music"), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
        if (playing) {
            View shade = new View(c);
            shade.setBackground(Ui.fill(0x730C0C10, 0, 14));
            artBox.addView(shade, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
            artBox.addView(new Widgets.EqBars(c), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
        r.addView(artBox, Ui.lp(Ui.u(56), Ui.u(56)));
        LinearLayout tc = Ui.col(c);
        tc.addView(Ui.text(c, t, 19, current ? 0xFFFFFFFF : Ui.TEXT, Ui.body(600)));
        tc.addView(Ui.text(c, s, 15, Ui.MUTED, Ui.body(400)), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
        r.addView(tc, Ui.margins(Ui.lpw(0, Ui.WRAP, 1), 16, 0, 0, 0));
        r.addView(new Icons.GlyphView(c, Icons.CHEV_RIGHT, Ui.MUTED, 20), Ui.lp(Ui.u(36), Ui.u(36)));
        r.setMinimumHeight(Ui.u(76));
        r.setOnClickListener(l);
        Ui.pressable(r);
        return r;
    }

    // ---- Updates --------------------------------------------------------------------------
    @Override
    void onShow() {
        a.background().setWallpaperMode(false);
        onMedia();
        updateVolume();
    }

    @Override
    void onMedia() {
        if (title == null) return;
        MediaHub m = a.media();
        boolean playing = m.isPlaying();
        if (!m.hasAccess()) {
            title.setText("Music");
            artist.setText("Controls work; allow access for track info");
        } else if (!m.hasSession()) {
            title.setText("Nothing playing");
            artist.setText("Start music in any app");
        } else {
            String t = m.title();
            title.setText(t != null ? t : "Unknown track");
            String ar = m.artist();
            artist.setText(ar != null ? ar : a.appLabel(m.packageName()));
        }
        disc.setArt(m.art());
        disc.setSpinning(playing);
        playGlyph.set(playing ? Icons.PAUSE : Icons.PLAY);
        playBtn.setContentDescription(playing ? "Pause" : "Play");
        playBtn.setBackground(Ui.fill(Ui.accent(), 0, 44).glow(Ui.accentGlow()));
        seek.setEnabled(m.duration() > 0);
        onTick();
        rebuildList();
    }

    @Override
    void onTick() {
        if (seek == null) return;
        MediaHub m = a.media();
        long d = m.duration(), p = m.position();
        if (!seek.isDragging()) {
            seek.setValue(d > 0 ? (int) (p * 1000 / d) : 0);
            elapsed.setText(Ui.mmss(p));
        }
        total.setText(d > 0 ? Ui.mmss(d) : "--:--");
        if (volPop.getVisibility() != View.VISIBLE) updateVolume();
    }

    @Override
    boolean onBack() {
        if (volPop != null && volPop.getVisibility() == View.VISIBLE) {
            volPop.setVisibility(View.GONE);
            return true;
        }
        return false;
    }
}
