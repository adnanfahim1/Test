package com.adnan.glasslauncher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Android settings, as shortcuts to the system screens plus the launcher's own options. */
final class SettingsScreen extends SidebarScreen {
    static final int HOME = 0, DISPLAY = 1, SOUND = 2, WEATHER = 3, CONNECTIONS = 4, NETWORK = 5, BLUETOOTH = 6, APPS = 7, ABOUT = 8;
    private static final String[] LABELS = {"Home screen", "Display", "Sound & music", "Weather", "Connections",
            "Wi-Fi & internet", "Bluetooth", "Storage & apps", "About device"};
    private static final Icons.Glyph[] GLYPHS = {Icons.GRID, Icons.SUN, Icons.VOLUME, Icons.CLOUD, Icons.OPEN,
            Icons.WIFI, Icons.BLUETOOTH, Icons.STORAGE, Icons.INFO};

    SettingsScreen(MainActivity a) { super(a, LABELS.length); current = HOME; }

    @Override String title() { return "Settings"; }
    @Override String sectionLabel(int i) { return LABELS[i]; }
    @Override Icons.Glyph sectionGlyph(int i) { return GLYPHS[i]; }
    @Override String linkLabel() { return "Car settings"; }
    @Override void onLink() { a.show(MainActivity.CAR); }

    @Override
    void fillSection(int i, LinearLayout out) {
        switch (i) {
            case HOME: home(out); break;
            case DISPLAY: display(out); break;
            case SOUND: sound(out); break;
            case WEATHER: weather(out); break;
            case CONNECTIONS: connections(out); break;
            case NETWORK: network(out); break;
            case BLUETOOTH: bluetooth(out); break;
            case APPS: apps(out); break;
            default: about(out); break;
        }
    }

    private View.OnClickListener open(final String action, final String fail) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.startSafe(new Intent(action), fail); }
        };
    }

    // ---- Home screen ----------------------------------------------------------------------
    private void home(LinearLayout out) {
        heading(out, "Home screen", "Pick the layout you see when you press Home. It's saved and opens after every restart.");
        String[] desc = {"Car, map, weather and music together.", "Just your car, big and centred.",
                "A large clock only. No car or widgets.", "Big map with weather and music. No car."};
        int sel = a.prefs().homeLayout();
        LinearLayout row = null;
        for (int k = 0; k < 4; k++) {
            if (k % 2 == 0) {
                row = Ui.row(a);
                row.setGravity(Gravity.NO_GRAVITY);
                out.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 16));
            }
            final int idx = k;
            LinearLayout card = Ui.col(a);
            boolean on = k == sel;
            Ui.GlassDrawable bg = Ui.fill(Ui.white(0.06f), on ? Ui.accent() : Ui.white(0.14f), 22).stroke(on ? Ui.accent() : Ui.white(0.14f), 2);
            if (on) bg.glow(Ui.accentGlow());
            card.setBackground(bg);
            card.setPadding(Ui.u(2), Ui.u(2), Ui.u(2), Ui.u(16));
            Thumb th = new Thumb(a, k);
            th.setClipToOutline(true);
            th.setBackground(Ui.fill(0xFF14151A, 0, 20));
            card.addView(th, Ui.lp(Ui.MATCH, Ui.u(150)));
            LinearLayout tr = Ui.row(a);
            Widgets.Dot radio = new Widgets.Dot(a, on ? Ui.accent() : 0);
            radio.ring(on ? Ui.accent() : Ui.white(0.4f));
            tr.addView(radio, Ui.lp(Ui.u(20), Ui.u(20)));
            tr.addView(Ui.text(a, Prefs.HOME_LAYOUTS[k], 19, Ui.TEXT, Ui.body(600)), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 10, 0, 0, 0));
            card.addView(tr, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 16, 14, 16, 0));
            TextView d = Ui.multiline(a, desc[k], 14, Ui.TEXT_2, Ui.body(400));
            card.addView(d, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 16, 6, 16, 0));
            card.setContentDescription(Prefs.HOME_LAYOUTS[k] + (on ? ", selected" : ""));
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    a.prefs().setHomeLayout(idx);
                    a.homeLayoutChanged();
                    a.toast("Home screen set to " + Prefs.HOME_LAYOUTS[idx]);
                    refresh();
                }
            });
            Ui.pressable(card);
            row.addView(card, Ui.margins(Ui.lpw(0, Ui.WRAP, 1), k % 2 == 0 ? 0 : 8, 0, k % 2 == 0 ? 8 : 0, 0));
        }
    }

    /** Small drawn preview of each home layout. */
    private static final class Thumb extends View {
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private static Bitmap car, wall;
        private static int wallTheme = -1;

        Thumb(Context c, int kind) {
            super(c);
            this.kind = kind;
            if (car == null) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 2;
                car = Widgets.asset(c, "car/noah-still.webp", o);
            }
            if (wallTheme != Ui.themeIndex) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = 4;
                wall = Widgets.asset(c, "wallpapers/wall-" + Ui.THEME_NAMES[Ui.themeIndex] + ".webp", o);
                wallTheme = Ui.themeIndex;
            }
        }

        private void box(Canvas c, float l, float t, float rr, float b, int color) {
            float w = getWidth(), h = getHeight();
            r.set(l * w, t * h, rr * w, b * h);
            p.setColor(color);
            c.drawRoundRect(r, h * 0.05f, h * 0.05f, p);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setShader(null);
            if (kind == 2 && wall != null) {
                r.set(0, 0, w, h);
                c.drawBitmap(wall, null, r, p);
            } else {
                p.setShader(new android.graphics.RadialGradient(w * 0.1f, 0, h * 0.9f, Ui.themeGlow(), 0,
                        android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
            }
            int g = Ui.white(0.10f);
            if (kind != 2) box(c, 0.92f, 0.07f, 0.97f, 0.93f, g);
            if (kind == 0) {
                box(c, 0.04f, 0.07f, 0.36f, 0.66f, g);
                box(c, 0.38f, 0.07f, 0.66f, 0.66f, g);
                box(c, 0.68f, 0.07f, 0.90f, 0.66f, Ui.withAlpha(Ui.accent(), 0x55));
                box(c, 0.04f, 0.72f, 0.90f, 0.93f, g);
                if (car != null) {
                    r.set(0.06f * w, 0.2f * h, 0.34f * w, 0.6f * h);
                    c.drawBitmap(car, null, fit(car, r), p);
                }
            } else if (kind == 1) {
                box(c, 0.04f, 0.07f, 0.90f, 0.93f, g);
                if (car != null) {
                    r.set(0.2f * w, 0.18f * h, 0.74f * w, 0.88f * h);
                    c.drawBitmap(car, null, fit(car, r), p);
                }
            } else if (kind == 2) {
                p.setColor(0xFFFFFFFF);
                p.setTextSize(h * 0.32f);
                p.setTypeface(Ui.display(500));
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("12:45", w * 0.47f, h * 0.6f, p);
                p.setTextSize(h * 0.1f);
                c.drawText("PM", w * 0.69f, h * 0.36f, p);
            } else {
                box(c, 0.04f, 0.07f, 0.30f, 0.66f, g);
                box(c, 0.32f, 0.07f, 0.90f, 0.66f, Ui.withAlpha(Ui.accent(), 0x55));
                box(c, 0.04f, 0.72f, 0.90f, 0.93f, g);
            }
        }

        private RectF fit(Bitmap b, RectF box) {
            float s = Math.min(box.width() / b.getWidth(), box.height() / b.getHeight());
            float dw = b.getWidth() * s, dh = b.getHeight() * s;
            return new RectF(box.centerX() - dw / 2, box.centerY() - dh / 2, box.centerX() + dw / 2, box.centerY() + dh / 2);
        }
    }

    // ---- Display --------------------------------------------------------------------------
    private void display(LinearLayout out) {
        heading(out, "Display", null);
        label(out, "Theme");
        LinearLayout sw = Ui.row(a);
        for (int k = 0; k < Ui.THEME_NAMES.length; k++) {
            final int idx = k;
            boolean on = k == Ui.themeIndex;
            LinearLayout b = Ui.row(a);
            b.setPadding(Ui.u(14), 0, Ui.u(18), 0);
            b.setBackground(on ? Ui.fill(Ui.white(0.14f), Ui.THEME_ACCENTS[k], 999).stroke(Ui.THEME_ACCENTS[k], 2)
                    : Ui.fill(Ui.white(0.06f), Ui.white(0.14f), 999));
            Widgets.Dot dot = new Widgets.Dot(a, Ui.THEME_ACCENTS[k]);
            b.addView(dot, Ui.lp(Ui.u(22), Ui.u(22)));
            b.addView(Ui.text(a, Ui.THEME_NAMES[k], 16, Ui.TEXT, Ui.body(on ? 600 : 400)), Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 10, 0, 0, 0));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { a.applyTheme(idx); }
            });
            Ui.pressable(b);
            sw.addView(b, Ui.margins(Ui.lp(Ui.WRAP, Ui.u(52)), 0, 0, 10, 0));
        }
        out.addView(sw, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, 14));
        segment(out, "Font size", null, Prefs.FONT_LABELS, a.prefs().fontSize(), new Pick() {
            @Override
            public void picked(int i) { a.setFontSize(i); }
        });
        nav(out, "Car name", "Shown on the Dashboard and Car showcase", a.prefs().carName(), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.askText("Car name", a.prefs().carName(), new MainActivity.TextCallback() {
                    @Override
                    public void done(String s) {
                        if (s.length() > 0) { a.prefs().setCarName(s); a.rebuildAll(); refresh(); }
                    }
                });
            }
        });
        nav(out, "Brightness & screen timeout", "Opens the system display settings", null,
                open(Settings.ACTION_DISPLAY_SETTINGS, "Display settings aren't available"));
        label(out, "Whole head unit");
        toggle(out, "Match system wallpaper to theme", "Sets the Android home and lock wallpaper to this theme whenever it changes",
                a.prefs().matchSystemWallpaper(), new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        a.prefs().setMatchSystemWallpaper(on);
                        if (on) a.applySystemTheme();
                    }
                });
        String shadeSub;
        if (!SystemTheme.shadeSupported()) shadeSub = "Needs Android 12 or newer, so the panel stays as it is on this unit";
        else if (!SystemTheme.shadeAllowed(a)) shadeSub = "Run the setup script once over USB to allow this (one permission)";
        else shadeSub = "Colours the notification / quick-settings panel with the theme. Turn off to restore it exactly";
        Widgets.Toggle shade = toggle(out, "Match pull-down panel to theme", shadeSub, a.prefs().matchShade(), new Widgets.Toggle.OnChange() {
            @Override
            public void changed(boolean on) {
                a.setMatchShade(on);
                refresh();
            }
        });
        if (!SystemTheme.shadeAllowed(a) && !a.prefs().matchShade()) shade.setEnabled(SystemTheme.shadeSupported());
        button(out, "Apply theme to system now", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.applySystemTheme(); }
        });
        para(out, "Android lets an app change the system wallpaper, the pull-down panel colour (Android 12+) and on some firmwares dark mode. "
                + "Icons and fonts of other apps can't be changed without root, so they stay as they are.");
    }

    // ---- Sound & music --------------------------------------------------------------------
    private void sound(LinearLayout out) {
        heading(out, "Sound & music", null);
        final MediaHub m = a.media();
        slider(out, "Media volume", m.maxVolume(), m.volume(), new Widgets.Slider.OnChange() {
            @Override
            public void changed(int value, boolean fromUser) { m.setVolume(value); }
        });
        nav(out, "Now playing access", "Lets the launcher show track info and artwork from any music app",
                m.hasAccess() ? "Allowed" : "Not allowed", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { a.askNotificationAccess(); }
                });
        nav(out, "Equalizer", "Opens the system or head unit equalizer", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.openEqualizer(); }
        });
        nav(out, "Sound settings", "Touch sounds, ringtones and other volumes", null,
                open(Settings.ACTION_SOUND_SETTINGS, "Sound settings aren't available"));
        para(out, "Some head units route volume through the vehicle module (MCU). If the slider doesn't change the real loudness, use the steering-wheel or hardware volume keys.");
    }

    // ---- Weather --------------------------------------------------------------------------
    private void weather(LinearLayout out) {
        heading(out, "Weather", "With the Glass Link app on your phone, weather comes from the phone's location and internet. "
                + "Without it, the head unit uses its own internet (your phone's hotspot or Bluetooth tethering).");
        final Prefs p = a.prefs();
        phoneLinkRow(out);
        toggle(out, "Use head unit location", p.useCity() ? "Off: using the city below" : "Needs location permission and a GPS or network fix",
                !p.useCity(), new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean on) {
                        if (on) {
                            p.useDeviceLocation();
                            if (!a.hasLocationPermission()) a.requestLocationPermission();
                            else a.refreshWeather(true);
                        } else if (p.cityName() != null) {
                            p.setCity(p.cityName(), p.cityLat(), p.cityLon());
                            a.refreshWeather(true);
                        } else {
                            askCity();
                        }
                        refresh();
                    }
                });
        nav(out, "City", "Used when the location is off or not available", p.cityName() != null ? p.cityName() : "Not set",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { askCity(); }
                });
        segment(out, "Units", null, new String[]{"°C", "°F"}, p.tempFahrenheit() ? 1 : 0, new Pick() {
            @Override
            public void picked(int i) {
                p.setTempFahrenheit(i == 1);
                a.weatherChanged();
                refresh();
            }
        });
        button(out, "Refresh weather now", false, new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.refreshWeather(true); a.toast("Fetching weather…"); }
        });
        para(out, "Weather data by Open-Meteo.com (CC BY 4.0). Check their terms before any commercial use.");
    }

    private void phoneLinkRow(LinearLayout out) {
        PhoneBridge b = a.bridge();
        boolean on = b != null && b.connected();
        String name = on ? b.phoneName() : null;
        nav(out, "Phone link (Glass Link)", on ? "Weather and turn-by-turn come from your phone over " + b.via()
                        : (b != null ? b.state() + ". " : "") + "Open Glass Link on your phone; it finds the car over Bluetooth or Wi-Fi.",
                on ? (name != null ? name : "Connected") : "Not connected", null);
        if (!on && a.phoneLink().needsPermission()) {
            nav(out, "Allow “Nearby devices”", "Needed for the phone link over Bluetooth (Android 12+)", null, new View.OnClickListener() {
                @Override
                public void onClick(View v) { a.requestBluetoothPermission(); }
            });
        }
    }

    private void askCity() {
        a.askText("Your city", a.prefs().cityName() != null ? a.prefs().cityName() : "", new MainActivity.TextCallback() {
            @Override
            public void done(final String s) {
                if (s.length() == 0) return;
                a.toast("Looking up " + s + "…");
                Weather.geocode(s, new Weather.GeoCallback() {
                    @Override
                    public void done(String name, double lat, double lon, String error) {
                        if (error != null) { a.toast(error); return; }
                        a.prefs().setCity(name, lat, lon);
                        a.refreshWeather(true);
                        a.toast("Weather city set to " + name);
                        refresh();
                    }
                });
            }
        });
    }

    // ---- Connections ----------------------------------------------------------------------
    private void connections(LinearLayout out) {
        heading(out, "Connections", "The head unit apps the launcher opens from its buttons, steering-wheel keys and Car settings. "
                + "They are found automatically; tap one to choose a different app or go back to auto-detect.");
        for (final String role : Vendor.ROLES) {
            android.content.ComponentName cn = Vendor.find(a, a.prefs(), role);
            String name = cn != null ? AppsRepo.labelFor(a, cn.flattenToString()) : null;
            boolean auto = a.prefs().roleApp(role) == null;
            nav(out, Vendor.label(role), name == null ? "Not found on this head unit" : auto ? "Found automatically" : "Chosen by you",
                    name != null ? name : "Choose", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) { a.pickConnection(role, SettingsScreen.this); }
                    });
        }
        String maps = AppsRepo.labelFor(a, a.prefs().mapsApp());
        nav(out, "Navigation app", "Opened by Maps on the rail and the Navigation card", maps != null ? maps : "Ask on first use",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.pickApp("Navigation app", true, new MainActivity.AppCallback() {
                            @Override
                            public void picked(String component, String label) {
                                a.prefs().setMapsApp(component);
                                refresh();
                            }
                        });
                    }
                });
    }

    // ---- Network / Bluetooth / Apps -------------------------------------------------------
    private void network(LinearLayout out) {
        heading(out, "Wi-Fi & internet", "Join your phone's hotspot or any Wi-Fi network. The head unit reconnects to it automatically after that.");
        final Wifi w = a.wifi();
        if (!w.available()) {
            nav(out, "Status", null, a.networkLabel(), null);
            para(out, "This head unit has no Wi-Fi.");
            nav(out, "More network settings", "Mobile data (SIM), tethering, VPN", null,
                    open(Settings.ACTION_WIRELESS_SETTINGS, "Network settings aren't available"));
            return;
        }
        final boolean on = w.enabled();
        toggle(out, "Wi-Fi", Wifi.canControl() ? (on ? "On" : "Off") : "Android 10+ shows the system Wi-Fi panel for this", on,
                new Widgets.Toggle.OnChange() {
                    @Override
                    public void changed(boolean want) {
                        if (Wifi.canControl() && w.setEnabled(want)) {
                            a.toast(want ? "Turning Wi-Fi on…" : "Wi-Fi off");
                            if (want) w.scan();
                        } else {
                            a.openWifiPanel();
                        }
                        a.main().postDelayed(new Runnable() { @Override public void run() { refresh(); } }, 1500);
                    }
                });
        String ssid = on ? w.connectedSsid() : null;
        nav(out, "Connected to", ssid != null ? "Signal " + Wifi.signalLabel(w.connectedLevel()).toLowerCase(java.util.Locale.US) : null,
                ssid != null ? ssid : on ? "Not connected" : "Wi-Fi is off", null);
        nav(out, "Internet", null, a.networkLabel(), null);
        if (on) {
            label(out, "Nearby networks");
            if (!a.hasLocationPermission()) {
                nav(out, "Allow location to list networks", "Android needs location access to show nearby Wi-Fi", null, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { a.requestLocationForWifi(); }
                });
            } else {
                List<Wifi.Network> nets = w.nearby();
                int n = 0;
                for (final Wifi.Network net : nets) {
                    if (n >= 12) break;
                    boolean current = net.ssid.equals(ssid);
                    String sub = (net.secured ? "Secured" : "Open") + " · " + Wifi.signalLabel(net.level)
                            + (!current && w.isSaved(net.ssid) ? " · Saved" : "");
                    nav(out, net.ssid, sub, current ? "Connected" : "Connect", current ? null : new View.OnClickListener() {
                        @Override
                        public void onClick(View v) { joinWifi(w, net); }
                    });
                    n++;
                }
                if (n == 0) para(out, "No networks found yet. Turn on your phone's hotspot, then tap Scan again.");
                button(out, "Scan again", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.toast(w.scan() ? "Scanning…" : "Android limits how often apps can scan; try again in a minute");
                        a.main().postDelayed(new Runnable() { @Override public void run() { refresh(); } }, 3000);
                    }
                });
            }
        }
        label(out, "More");
        nav(out, "Wi-Fi settings", "Saved networks, advanced options", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi settings aren't available"); }
        });
        nav(out, "More network settings", "Mobile data (SIM), tethering, VPN", null,
                open(Settings.ACTION_WIRELESS_SETTINGS, "Network settings aren't available"));
    }

    private void joinWifi(final Wifi w, final Wifi.Network net) {
        if (!Wifi.canControl()) {
            // Android 10+: only the system may join networks.
            a.openWifiPanel();
            return;
        }
        if (!net.secured || w.isSaved(net.ssid)) {
            a.toast(w.connect(net, null) ? "Connecting to " + net.ssid + "…" : "Couldn't connect to " + net.ssid);
            a.main().postDelayed(new Runnable() { @Override public void run() { refresh(); } }, 4000);
            return;
        }
        a.askText(net.ssid, "", true, "Connect", new MainActivity.TextCallback() {
            @Override
            public void done(String pw) {
                if (pw.length() == 0) return;
                a.toast(w.connect(net, pw) ? "Connecting to " + net.ssid + "…" : "Couldn't connect to " + net.ssid);
                a.main().postDelayed(new Runnable() { @Override public void run() { refresh(); } }, 5000);
            }
        });
    }

    /** Wi-Fi state or scan results changed while this page is open. */
    void wifiChanged() {
        if (current == NETWORK) refresh();
    }

    private void bluetooth(LinearLayout out) {
        heading(out, "Bluetooth", null);
        String name = a.phoneName();
        if (a.phoneLink().needsPermission()) {
            nav(out, "Allow “Nearby devices”", "Android 12+ needs this to show which phone is connected", null, new View.OnClickListener() {
                @Override
                public void onClick(View v) { a.requestBluetoothPermission(); }
            });
        }
        nav(out, "Bluetooth", null, a.bluetoothOn() ? "On" : "Off", null);
        nav(out, "Connected phone", null, name != null ? name : "None", null);
        phoneLinkRow(out);
        nav(out, "Pair or manage devices", "Opens the system Bluetooth settings", null, new View.OnClickListener() {
            @Override
            public void onClick(View v) { a.openBluetoothSettings(); }
        });
        para(out, "Calls and phone audio on many head units are handled by the vendor's own Bluetooth app. If pairing here doesn't route calls, use that app from the App drawer.");
    }

    private void apps(LinearLayout out) {
        heading(out, "Storage & apps", null);
        nav(out, "Storage", null, null, open(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "Storage settings aren't available"));
        nav(out, "Apps", "Installed apps, permissions, force stop", null,
                open(Settings.ACTION_APPLICATION_SETTINGS, "App settings aren't available"));
        nav(out, "Default home app", "Switch back to the stock launcher here at any time", null,
                open(Settings.ACTION_HOME_SETTINGS, "Open Settings › Apps › Default apps › Home app"));
        nav(out, "All Android settings", null, null, open(Settings.ACTION_SETTINGS, "Settings aren't available"));
    }

    // ---- About ----------------------------------------------------------------------------
    private void about(LinearLayout out) {
        heading(out, "About device", null);
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        nav(out, "Model", null, Build.MANUFACTURER + " " + Build.MODEL, null);
        nav(out, "Android version", null, Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")", null);
        nav(out, "Build", null, Build.DISPLAY, null);
        nav(out, "Screen", null, dm.widthPixels + " × " + dm.heightPixels + " px · " + dm.densityDpi + " dpi", null);
        nav(out, "Launcher", null, "Glass Launcher " + a.versionName(), null);
        nav(out, "Update from your phone", a.bridge() != null && a.bridge().connected()
                ? "Phone connected: in Glass Link, tap “Send update to car”"
                : "Connect the Glass Link phone app, then tap “Send update to car” in it", null, null);
        nav(out, "Layout scale", "The 1280×720 design fitted to this screen", String.format(java.util.Locale.US, "%.2f×", Ui.scale), null);
        label(out, "Diagnostics");
        toggle(out, "Compatibility mode", "Simpler drawing without animations, for head units whose graphics drivers have problems. "
                + "Turned on automatically if a start fails.", a.liteMode(), new Widgets.Toggle.OnChange() {
            @Override
            public void changed(boolean on) { a.setLiteMode(on); }
        });
        nav(out, "Help & problem report", "Opens Glass Launcher Help: restart modes, reset and a shareable report", null,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { a.startSafe(new android.content.Intent(a, HelpActivity.class), null); }
                });
        final String err = a.prefs().lastError();
        nav(out, "Last problem", err == null ? "None recorded" : "Recorded and recovered from automatically",
                err == null ? "None" : "Show", err == null ? null : new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        new android.app.AlertDialog.Builder(a, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                                .setTitle("Last problem").setMessage(err).setPositiveButton("OK", null)
                                .setNeutralButton("Clear", new android.content.DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        a.prefs().raw().edit().remove("last_error").apply();
                                        refresh();
                                    }
                                }).show();
                    }
                });
        label(out, "Credits");
        para(out, "Car 3D model — ‘Toyota Noah’ by Nieve5677 · CC BY (Sketchfab).");
        para(out, "Fonts: Barlow Semi Condensed and Manrope · SIL Open Font License.");
        para(out, "Weather data by Open-Meteo.com · CC BY 4.0.");
    }

    void open(int section) {
        current = section;
        if (view() != null) select(section);
    }
}
