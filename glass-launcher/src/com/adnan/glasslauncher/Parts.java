package com.adnan.glasslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Building blocks shared by several screens. */
final class Parts {
    private Parts() {}

    static String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening";
    }

    /** 12-hour clock everywhere, "h:mm a". */
    static String time() {
        return DateFormat.format("h:mm a", new Date()).toString().toUpperCase(Locale.US);
    }

    static String timeShort() { return DateFormat.format("h:mm", new Date()).toString(); }

    static String ampm() { return Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 12 ? "AM" : "PM"; }

    static String date() { return DateFormat.format("EEE d MMM", new Date()).toString(); }

    static String dateLong() { return DateFormat.format("EEEE, d MMMM", new Date()).toString(); }

    // =====================================================================================
    /** Square glass button with a glyph (back buttons, ‹ ›, volume). */
    static FrameLayout glyphButton(Context c, Icons.Glyph g, float size, float radius, float glyphSize, View.OnClickListener l) {
        FrameLayout b = new FrameLayout(c);
        b.setBackground(Ui.tile(radius));
        Icons.GlyphView gv = new Icons.GlyphView(c, g, Ui.TEXT, glyphSize);
        b.addView(gv, Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER));
        b.setLayoutParams(Ui.lp(Ui.u(size), Ui.u(size)));
        b.setOnClickListener(l);
        Ui.pressable(b);
        return b;
    }

    /** Accent pill/rounded button with text. */
    static TextView accentButton(Context c, String label, float size, float padH, float height, float radius, View.OnClickListener l) {
        TextView t = Ui.text(c, label, size, 0xFFFFFFFF, Ui.body(600));
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.u(padH), 0, Ui.u(padH), 0);
        t.setBackground(Ui.fill(Ui.accent(), 0, radius).glow(Ui.accentGlow()));
        t.setLayoutParams(Ui.lp(Ui.WRAP, Ui.u(height)));
        t.setOnClickListener(l);
        Ui.pressable(t);
        return t;
    }

    static TextView outlineButton(Context c, String label, float size, float padH, float height, float radius, View.OnClickListener l) {
        TextView t = Ui.text(c, label, size, Ui.TEXT, Ui.body(600));
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.u(padH), 0, Ui.u(padH), 0);
        t.setBackground(Ui.fill(Ui.white(0.08f), Ui.white(0.28f), radius));
        t.setLayoutParams(Ui.lp(Ui.WRAP, Ui.u(height)));
        t.setOnClickListener(l);
        Ui.pressable(t);
        return t;
    }

    static TextView badge(Context c, String s) {
        TextView t = Ui.text(c, s.toUpperCase(Locale.US), 10, Ui.TEXT_E6, Ui.body(600));
        t.setLetterSpacing(0.1f);
        t.setPadding(Ui.u(8), Ui.u(3), Ui.u(8), Ui.u(3));
        t.setBackground(Ui.fill(Ui.white(0.14f), 0, 8));
        return t;
    }

    // =====================================================================================
    /** Header: greeting (left), phone chip, date, 12-hour clock (right). */
    static final class Header extends LinearLayout {
        private final MainActivity a;
        private final TextView greeting, date, clock, chipText;
        private final Widgets.Dot dot;
        private final LinearLayout chip;
        private final boolean minimal;

        Header(MainActivity a, boolean minimal) {
            super(a);
            this.a = a;
            this.minimal = minimal;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.u(8), 0, Ui.u(8), 0);
            greeting = Ui.text(a, "", 18, Ui.TEXT_2, Ui.body(400));
            addView(greeting, Ui.lpw(0, Ui.WRAP, 1));
            if (minimal) greeting.setVisibility(INVISIBLE);

            chip = Ui.row(a);
            chip.setPadding(Ui.u(14), Ui.u(7), Ui.u(14), Ui.u(7));
            dot = new Widgets.Dot(a, Ui.FAINT);
            chip.addView(dot, Ui.lp(Ui.u(8), Ui.u(8)));
            chip.addView(new Icons.GlyphView(a, Icons.PHONE, Ui.TEXT, 16), Ui.margins(Ui.lp(Ui.u(16), Ui.u(16)), 8, 0, 8, 0));
            chipText = Ui.text(a, "No phone", 14, Ui.TEXT, Ui.body(600));
            chip.addView(chipText);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { a.openBluetoothSettings(); }
            });
            Ui.pressable(chip);
            addView(chip);

            date = Ui.text(a, "", 17, Ui.TEXT_E6, Ui.body(400));
            clock = Ui.text(a, "", 22, Ui.TEXT_E6, Ui.display(600));
            if (!minimal) {
                addView(date, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 18, 0, 0, 0));
                addView(clock, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 18, 0, 0, 0));
            }
            update();
        }

        void update() {
            greeting.setText(greeting());
            date.setText(date());
            clock.setText(time());
            String phone = a.phoneName();
            boolean on = phone != null;
            chipText.setText(on ? phone + " connected" : "No phone");
            dot.set(on ? Ui.SUCCESS : Ui.FAINT, false);
            chip.setBackground(Ui.fill(on ? Ui.white(0.14f) : Ui.white(0.06f), Ui.white(0.18f), 999));
            chip.setContentDescription(on ? "Phone connected. Opens Bluetooth settings" : "No phone connected. Opens Bluetooth settings");
        }
    }

    // =====================================================================================
    /** Side rail on the RIGHT edge (88 wide): Maps, Music, Apps, Car, Settings, Home pinned at bottom. */
    static LinearLayout rail(final MainActivity a) {
        LinearLayout r = Ui.col(a);
        r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.setPadding(0, Ui.u(16), 0, Ui.u(16));
        r.setBackground(Ui.glass(28));
        String[][] items = {
                {"maps", "Maps"}, {"music", "Music"}, {"apps", "Apps"}, {"car", "Car"}, {"settings", "Settings"}};
        for (int i = 0; i < items.length; i++) {
            final String key = items[i][0];
            View item = railItem(a, key, items[i][1], false);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("maps".equals(key)) a.openMaps();
                    else if ("music".equals(key)) a.show(MainActivity.MUSIC);
                    else if ("apps".equals(key)) a.show(MainActivity.APPS);
                    else if ("car".equals(key)) a.show(MainActivity.CAR);
                    else a.show(MainActivity.SETTINGS);
                }
            });
            r.addView(item, Ui.margins(Ui.lp(Ui.u(72), Ui.u(72)), 0, i == 0 ? 0 : 10, 0, 0));
        }
        r.addView(Ui.space(a), Ui.lpw(1, 0, 1));
        View home = railItem(a, "home", "Home", true);
        home.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.show(MainActivity.HOME); }
        });
        r.addView(home, Ui.lp(Ui.u(72), Ui.u(72)));
        return r;
    }

    private static View railItem(Context c, String key, String label, boolean current) {
        LinearLayout it = Ui.col(c);
        it.setGravity(Gravity.CENTER);
        Icons.GlassView icon = new Icons.GlassView(c, key);
        it.addView(icon, Ui.lp(Ui.u(38), Ui.u(38)));
        TextView t = Ui.text(c, label, 11, current ? 0xFFFFFFFF : Ui.TEXT_2, Ui.body(current ? 600 : 400));
        t.setGravity(Gravity.CENTER);
        it.addView(t, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 5, 0, 0));
        if (current) it.setBackground(Ui.fill(Ui.white(0.14f), Ui.white(0.22f), 18).glow(Ui.accentGlow()));
        it.setContentDescription(label);
        Ui.pressable(it);
        return it;
    }

    // =====================================================================================
    /** Full-width now-playing bar: disc, title/artist/progress, volume, prev/play/next. */
    static final class MusicBar extends LinearLayout {
        private final MainActivity a;
        private final Widgets.Disc disc;
        private final TextView kicker, title, artist, volLabel;
        private final Widgets.Progress progress;
        private final Widgets.Slider vol;
        private final Icons.GlyphView playGlyph, muteGlyph;
        private final FrameLayout playBtn;

        MusicBar(final MainActivity a) {
            super(a);
            this.a = a;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.u(22), 0, Ui.u(28), 0);
            setBackground(Ui.glass(28));

            OnClickListener openMusic = new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!a.media().hasAccess()) a.askNotificationAccess();
                    else a.show(MainActivity.MUSIC);
                }
            };
            disc = new Widgets.Disc(a, 0.4545f, false);
            disc.setOnClickListener(openMusic);
            disc.setContentDescription("Open music");
            addView(disc, Ui.lp(Ui.u(88), Ui.u(88)));

            LinearLayout info = Ui.col(a);
            info.setOnClickListener(openMusic);
            kicker = Ui.text(a, "NOW PLAYING · TAP TO OPEN", 11, Ui.MUTED, Ui.body(500));
            kicker.setLetterSpacing(0.13f);
            title = Ui.text(a, "", 26, Ui.TEXT, Ui.display(600));
            artist = Ui.text(a, "", 15, Ui.TEXT_2, Ui.body(400));
            progress = new Widgets.Progress(a);
            info.addView(kicker);
            info.addView(title, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
            info.addView(artist, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 6, 0, 0));
            info.addView(progress, Ui.margins(Ui.lp(Ui.MATCH, Ui.u(4)), 0, 12, 0, 0));
            addView(info, Ui.margins(Ui.lpw(0, Ui.WRAP, 1), 22, 0, 0, 0));

            // Volume block
            LinearLayout volBox = Ui.row(a);
            FrameLayout mute = new FrameLayout(a);
            mute.setBackground(Ui.fill(Ui.white(0.07f), 0, 16));
            muteGlyph = new Icons.GlyphView(a, Icons.VOLUME, Ui.TEXT, 24);
            mute.addView(muteGlyph, Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER));
            mute.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { a.media().toggleMute(); update(); }
            });
            mute.setContentDescription("Mute");
            Ui.pressable(mute);
            volBox.addView(mute, Ui.lp(Ui.u(52), Ui.u(52)));
            LinearLayout volCol = Ui.col(a);
            LinearLayout volTop = Ui.row(a);
            volTop.addView(Ui.text(a, "Volume", 12, Ui.MUTED, Ui.body(400)), Ui.lpw(0, Ui.WRAP, 1));
            volLabel = Ui.text(a, "", 12, Ui.MUTED, Ui.body(400));
            volTop.addView(volLabel);
            volCol.addView(volTop);
            vol = new Widgets.Slider(a, a.media().maxVolume(), a.media().volume());
            vol.setOnChange(new Widgets.Slider.OnChange() {
                @Override
                public void changed(int value, boolean fromUser) {
                    a.media().setVolume(value);
                    volLabel.setText(value == 0 ? "Muted" : String.valueOf(value));
                }
            });
            vol.setContentDescription("Volume");
            volCol.addView(vol, Ui.lp(Ui.MATCH, Ui.u(30)));
            volBox.addView(volCol, Ui.margins(Ui.lpw(0, Ui.WRAP, 1), 10, 0, 0, 0));
            addView(volBox, Ui.margins(Ui.lp(Ui.u(260), Ui.WRAP), 22, 0, 0, 0));

            View div = new View(a);
            div.setBackgroundColor(Ui.white(0.14f));
            addView(div, Ui.margins(Ui.lp(Math.max(1, Ui.u(1)), Ui.u(64)), 22, 0, 0, 0));

            LinearLayout ctl = Ui.row(a);
            ctl.addView(ctlButton(a, Icons.PREV, 56, "Previous track", new OnClickListener() {
                @Override
                public void onClick(View v) { a.media().prev(); }
            }));
            playBtn = new FrameLayout(a);
            playBtn.setBackground(Ui.fill(Ui.accent(), 0, 32).glow(Ui.accentGlow()));
            playGlyph = new Icons.GlyphView(a, Icons.PLAY, 0xFFFFFFFF, 26);
            playBtn.addView(playGlyph, Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER));
            playBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.media().togglePlay();
                    postDelayed(new Runnable() { @Override public void run() { update(); } }, 300);
                }
            });
            Ui.pressable(playBtn);
            ctl.addView(playBtn, Ui.margins(Ui.lp(Ui.u(64), Ui.u(64)), 8, 0, 8, 0));
            ctl.addView(ctlButton(a, Icons.NEXT, 56, "Next track", new OnClickListener() {
                @Override
                public void onClick(View v) { a.media().next(); }
            }));
            addView(ctl, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 22, 0, 0, 0));
            update();
        }

        void update() {
            MediaHub m = a.media();
            boolean access = m.hasAccess();
            boolean playing = m.isPlaying();
            if (!access) {
                kicker.setText("MUSIC · TAP TO SET UP");
                title.setText("Show what's playing");
                artist.setText("Allow notification access once to see any music app here");
                disc.setArt(null);
                progress.set(0);
            } else if (!m.hasSession()) {
                kicker.setText("MUSIC");
                title.setText("Nothing playing");
                artist.setText("Start music in any app, or tap to choose one");
                disc.setArt(null);
                progress.set(0);
            } else {
                kicker.setText("NOW PLAYING · TAP TO OPEN");
                String t = m.title();
                title.setText(t != null ? t : "Unknown track");
                String ar = m.artist();
                artist.setText(ar != null ? ar : a.appLabel(m.packageName()));
                Bitmap art = m.art();
                disc.setArt(art);
                long d = m.duration();
                progress.set(d > 0 ? m.position() / (float) d : 0);
            }
            disc.setSpinning(playing);
            playGlyph.set(playing ? Icons.PAUSE : Icons.PLAY);
            playBtn.setContentDescription(playing ? "Pause" : "Play");
            playBtn.setBackground(Ui.fill(Ui.accent(), 0, 32).glow(Ui.accentGlow()));
            int v = m.volume();
            boolean muted = m.isMuted();
            vol.setMax(m.maxVolume());
            vol.setValue(muted ? 0 : v);
            volLabel.setText(muted ? "Muted" : String.valueOf(v));
            muteGlyph.set(muted ? Icons.MUTE : Icons.VOLUME);
        }

        void tick() {
            MediaHub m = a.media();
            if (m.hasSession()) {
                long d = m.duration();
                progress.set(d > 0 ? m.position() / (float) d : 0);
            }
        }
    }

    static FrameLayout ctlButton(Context c, Icons.Glyph g, float size, String desc, View.OnClickListener l) {
        FrameLayout b = new FrameLayout(c);
        b.addView(new Icons.GlyphView(c, g, Ui.TEXT, 26), Ui.flp(Ui.MATCH, Ui.MATCH, Gravity.CENTER));
        b.setLayoutParams(Ui.lp(Ui.u(size), Ui.u(size)));
        b.setOnClickListener(l);
        b.setContentDescription(desc);
        Ui.pressable(b);
        return b;
    }

    // =====================================================================================
    /** Weather card with loading / data / permission / offline states. */
    static final class WeatherCard extends FrameLayout {
        private final MainActivity a;
        private final FrameLayout body;
        private int shownState = -1;
        private long shownAt = -1;

        WeatherCard(MainActivity a) {
            super(a);
            this.a = a;
            setBackground(Ui.glass(28));
            setPadding(Ui.u(24), Ui.u(24), Ui.u(24), Ui.u(24));
            body = new FrameLayout(a);
            addView(body, new LayoutParams(Ui.MATCH, Ui.MATCH));
            update();
        }

        void update() {
            int st = a.weatherState();
            Weather.Data d = a.weather();
            long at = d != null ? d.fetchedAt : 0;
            if (st == shownState && at == shownAt) return;
            shownState = st;
            shownAt = at;
            body.removeAllViews();
            Context c = getContext();
            if (d != null && (st == MainActivity.W_OK || st == MainActivity.W_LOADING || st == MainActivity.W_OFFLINE)) {
                Widgets.WeatherArt art = new Widgets.WeatherArt(c);
                art.set(Weather.art(d.code), d.day);
                body.addView(art, Ui.flp(Ui.u(150), Ui.u(110), Gravity.TOP | Gravity.RIGHT));
                ((LayoutParams) art.getLayoutParams()).setMargins(0, Ui.u(-6), Ui.u(-16), 0);
                LinearLayout col = Ui.col(c);
                TextView temp = Ui.text(c, a.formatTemp(d.tempC), 76, Ui.TEXT, Ui.display(600));
                col.addView(temp);
                LinearLayout placeRow = Ui.row(c);
                String place = d.place != null && d.place.length() > 0 ? d.place : "Your location";
                placeRow.addView(Ui.text(c, place, 17, Ui.TEXT_2, Ui.body(400)), Ui.lp(Ui.WRAP, Ui.WRAP));
                if (st == MainActivity.W_OFFLINE) {
                    placeRow.addView(badge(c, "Offline"), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 8, 0, 0, 0));
                }
                col.addView(placeRow, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 8, 0, 0));
                col.addView(Ui.space(c), Ui.lpw(1, 0, 1));
                col.addView(Ui.text(c, Weather.describe(d.code), 22, Ui.TEXT, Ui.body(600)));
                LinearLayout rows = Ui.col(c);
                if (d.rainPct >= 0) rows.addView(statRow(c, "Chance of rain", d.rainPct + "%"));
                if (d.visibilityKm >= 0) rows.addView(statRow(c, "Visibility", formatKm(d.visibilityKm)), Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
                rows.addView(statRow(c, "Updated", DateFormat.format("h:mm a", new Date(d.fetchedAt)).toString()), Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 8, 0, 0));
                col.addView(rows, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 14, 0, 0));
                body.addView(col, new LayoutParams(Ui.MATCH, Ui.MATCH));
                Ui.rise(col, 0);
                return;
            }
            if (st == MainActivity.W_LOADING) {
                body.addView(centered(c, null, "Fetching weather…", null, null, null, true));
                return;
            }
            String title, msg, btn;
            OnClickListener l;
            if (st == MainActivity.W_NEED_PERMISSION) {
                title = "Weather needs your location";
                msg = "Allow location once, or set a city in Settings › Weather.";
                btn = "Allow location";
                l = new OnClickListener() { @Override public void onClick(View v) { a.requestLocationPermission(); } };
            } else if (st == MainActivity.W_NO_LOCATION) {
                title = "Location not found yet";
                msg = "The head unit has no GPS fix. Set your city instead.";
                btn = "Set city";
                l = new OnClickListener() { @Override public void onClick(View v) { a.openSettingsSection(SettingsScreen.WEATHER); } };
            } else if (st == MainActivity.W_OFFLINE) {
                title = "Connect to the internet for weather";
                msg = "Turn on your phone's hotspot or Bluetooth tethering.";
                btn = "Try again";
                l = new OnClickListener() { @Override public void onClick(View v) { a.refreshWeather(true); } };
            } else {
                title = "Weather unavailable";
                msg = "The weather service didn't answer. It will retry automatically.";
                btn = "Try again";
                l = new OnClickListener() { @Override public void onClick(View v) { a.refreshWeather(true); } };
            }
            body.addView(centered(c, new Widgets.WeatherArt(c), title, msg, btn, l, false));
        }

        private static View statRow(Context c, String k, String v) {
            LinearLayout r = Ui.row(c);
            r.addView(Ui.text(c, k, 15, Ui.TEXT_2, Ui.body(400)), Ui.lpw(0, Ui.WRAP, 1));
            r.addView(Ui.text(c, v, 15, Ui.TEXT, Ui.body(400)));
            return r;
        }
    }

    static String formatKm(double km) {
        if (km >= 10) return Math.round(km) + " km";
        return String.format(Locale.US, "%.1f km", km);
    }

    /** Centered empty-state block: optional art, title, message, button. */
    static View centered(Context c, View art, String title, String msg, String btn, View.OnClickListener l, boolean spinner) {
        LinearLayout col = Ui.col(c);
        col.setGravity(Gravity.CENTER);
        if (spinner) {
            col.addView(new Widgets.Spinner(c), Ui.lp(Ui.u(40), Ui.u(40)));
        } else if (art != null) {
            art.setAlpha(0.55f);
            col.addView(art, Ui.lp(Ui.u(88), Ui.u(64)));
        }
        TextView t = Ui.text(c, title, spinner ? 16 : 19, spinner ? Ui.TEXT_E6 : Ui.TEXT, Ui.body(spinner ? 400 : 600));
        t.setSingleLine(false);
        t.setGravity(Gravity.CENTER);
        col.addView(t, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, spinner ? 14 : 12, 0, 0));
        if (msg != null) {
            TextView m = Ui.multiline(c, msg, 14, Ui.TEXT_2, Ui.body(400));
            m.setGravity(Gravity.CENTER);
            col.addView(m, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 12, 0, 0));
        }
        if (btn != null) {
            col.addView(outlineButton(c, btn, 15, 20, 46, 14, l), Ui.margins(Ui.lp(Ui.WRAP, Ui.u(46)), 0, 18, 0, 0));
        }
        FrameLayout.LayoutParams p = Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.CENTER);
        col.setLayoutParams(p);
        Ui.rise(col, 0);
        return col;
    }

    // =====================================================================================
    /** Navigation card: decorative map and an "Open navigation" button. */
    static final class MapCard extends FrameLayout {
        private final MainActivity a;
        private final TextView appName;

        MapCard(final MainActivity a, boolean stretch) {
            super(a);
            this.a = a;
            setBackground(Ui.glass(28));
            setClipToOutline(true);
            Widgets.MapArt map = new Widgets.MapArt(a, stretch);
            map.setAlpha(0.55f);
            addView(map, new LayoutParams(Ui.MATCH, Ui.MATCH));

            LinearLayout top = Ui.row(a);
            top.setPadding(Ui.u(12), Ui.u(10), Ui.u(12), Ui.u(10));
            top.setBackground(Ui.fill(0x8C0C0C10, 0, 16));
            top.addView(new Icons.GlyphView(a, Icons.NAV, Ui.TEXT, 22), Ui.lp(Ui.u(26), Ui.u(26)));
            LinearLayout tcol = Ui.col(a);
            tcol.addView(Ui.text(a, "Navigation", 17, Ui.TEXT, Ui.body(600)));
            appName = Ui.text(a, "", 12, Ui.TEXT_2, Ui.body(400));
            tcol.addView(appName, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 3, 0, 0));
            top.addView(tcol, Ui.margins(Ui.lpw(0, Ui.WRAP, 1), 10, 0, 0, 0));
            LayoutParams tp = Ui.flp(Ui.MATCH, Ui.WRAP, Gravity.TOP);
            tp.setMargins(Ui.u(14), Ui.u(14), Ui.u(14), 0);
            addView(top, tp);

            LinearLayout btn = Ui.row(a);
            btn.setGravity(Gravity.CENTER);
            btn.setBackground(Ui.fill(Ui.accent(), 0, 16).glow(Ui.accentGlow()));
            btn.addView(new Icons.GlyphView(a, Icons.NAV, 0xFFFFFFFF, 20), Ui.lp(Ui.u(20), Ui.u(20)));
            btn.addView(Ui.text(a, "Open navigation", 17, 0xFFFFFFFF, Ui.body(600)), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 10, 0, 0, 0));
            btn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) { a.openMaps(); }
            });
            Ui.pressable(btn);
            LayoutParams bp = Ui.flp(Ui.MATCH, Ui.u(56), Gravity.BOTTOM);
            bp.setMargins(Ui.u(14), 0, Ui.u(14), Ui.u(14));
            addView(btn, bp);
            update();
        }

        void update() {
            String label = AppsRepo.labelFor(a, a.prefs().mapsApp());
            appName.setText(label != null ? "Opens " + label : "Choose your maps app on first tap");
        }
    }
}
