package com.adnan.glasslauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Single HOME activity hosting every screen. */
public class MainActivity extends Activity implements MediaHub.Listener {
    static final int HOME = 0, MUSIC = 1, APPS = 2, SETTINGS = 3, CAR = 4;
    static final int W_LOADING = 0, W_OK = 1, W_NEED_PERMISSION = 2, W_NO_LOCATION = 3, W_OFFLINE = 4, W_ERROR = 5;
    private static final int REQ_LOCATION = 7, REQ_BLUETOOTH = 8, REQ_MUSIC = 9, REQ_WIFI = 10;
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;
    static final String NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS";

    private Prefs prefs;
    private MediaHub media;
    private PhoneLink phone;
    private Widgets.Background background;
    private FrameLayout root;
    private FrameLayout host;
    private Widgets.WheelLoader loader;
    private LinearLayout toast;
    private TextView toastText;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Screen[] screens = new Screen[5];
    private int current = HOME;
    private boolean started;
    private boolean safeMode;
    private boolean lite;
    private long startedAt;
    private boolean touched;
    private int laidOutW, laidOutH;

    private String phoneName;
    private boolean online;
    private Weather.Data weather;
    private PhoneBridge bridge;
    private PhoneBridge.Nav nav;
    private int weatherState = W_LOADING;
    private long lastWeatherTry;
    private LocationListener pendingLocation;

    interface TextCallback { void done(String s); }
    interface AppCallback { void picked(String component, String label); }

    // ---- Lifecycle ------------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CrashGuard.install(this);
        int boot = CrashGuard.beginBoot(this);
        CrashGuard.stage(this, "create");
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        prefs = new Prefs(this);
        Ui.init(this);
        Ui.loadFonts(this);
        Ui.themeIndex = prefs.theme();
        Ui.fontScale = Prefs.FONT_SCALES[prefs.fontSize()];
        media = new MediaHub(this);
        media.setListener(this);
        phone = new PhoneLink(this);
        phone.setOnChange(new Runnable() {
            @Override
            public void run() { updatePhone(); }
        });
        bridge = new PhoneBridge(this);
        bridge.setListener(new PhoneBridge.Listener() {
            @Override
            public void onPhoneWeather(Weather.Data d) {
                weather = d;
                prefs.setWeatherCache(d.toJson());
                setWeatherState(W_OK);
            }

            @Override
            public void onPhoneNav(PhoneBridge.Nav n) {
                nav = n;
                navChanged();
            }

            @Override
            public void onPhoneLink() {
                if (bridge.connected()) refreshWeather(true);
                updatePhone();
                weatherChanged();
            }
        });
        weather = Weather.Data.fromJson(prefs.weatherCache());
        if (weather != null) weatherState = W_OK;
        safeMode = boot >= 2;
        lite = boot >= 1;
        if (lite) Ui.lite = true;
        if (Ui.lite) Ui.reduceMotion = true;

        root = new FrameLayout(this);
        background = new Widgets.Background(this);
        root.addView(background, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        host = new FrameLayout(this);
        root.addView(host, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        loader = new Widgets.WheelLoader(this);
        root.addView(loader, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        buildToast(root);
        watchInsetsAndSize();
        // Compatibility mode: draw in software (avoids GPU-driver crashes on some head units).
        if (Ui.lite) root.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        CrashGuard.stage(this, "create:window");
        setContentView(root);
        hideSystemBars();
        CrashGuard.stage(this, safeMode ? "create:safe-mode" : "create:home screen");
        if (safeMode) showSafeMode();
        else show(HOME, false);
        applyIntent(getIntent());
        CrashGuard.stage(this, "created");
        if (Ui.lite && !safeMode) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() { toast("Compatibility mode is on · Settings › About to turn it off"); }
            }, 1500);
        }
    }

    private void hideSystemBars() {
        try {
            if (!NewApi.hideStatusBar(getWindow())) {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Pads the content away from any navigation bar the firmware shows, and re-scales the
     * whole UI whenever the usable area changes (different screen, split screen, nav bar
     * shown/hidden, density change), so the layout fits every head unit.
     */
    @SuppressWarnings("deprecation")
    private void watchInsetsAndSize() {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int[] b = NewApi.systemBars(in);
                if (b == null) b = new int[]{in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                        in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom()};
                host.setPadding(b[0], b[1], b[2], b[3]);
                return in;
            }
        });
        host.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int w = r - l - host.getPaddingLeft() - host.getPaddingRight();
                int h = b - t - host.getPaddingTop() - host.getPaddingBottom();
                if (w <= 0 || h <= 0 || (w == laidOutW && h == laidOutH)) return;
                laidOutW = w;
                laidOutH = h;
                if (Ui.setArea(w, h)) {
                    main.post(new Runnable() {
                        @Override
                        public void run() { rebuildAll(); }
                    });
                }
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        laidOutW = laidOutH = 0;
        root.requestLayout();
    }

    // ---- Swipe left on Home opens the app drawer -----------------------------------------
    private float swipeX, swipeY;
    private boolean swipeTracking, swipeTaken;

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        try {
            int act = e.getActionMasked();
            if (act == android.view.MotionEvent.ACTION_DOWN) {
                swipeX = e.getRawX();
                swipeY = e.getRawY();
                swipeTaken = false;
                swipeTracking = current == HOME && !safeMode;
            } else if (swipeTaken) {
                return true;
            } else if (swipeTracking && act == android.view.MotionEvent.ACTION_MOVE) {
                float dx = e.getRawX() - swipeX, dy = e.getRawY() - swipeY;
                if (Widgets.Slider.anyDragging || e.getPointerCount() > 1 || Math.abs(dy) > Ui.u(80)) {
                    swipeTracking = false;
                } else if (dx < -Ui.u(110) && Math.abs(dx) > 2 * Math.abs(dy)) {
                    swipeTracking = false;
                    swipeTaken = true;
                    android.view.MotionEvent cancel = android.view.MotionEvent.obtain(e);
                    cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    show(APPS);
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            swipeTracking = swipeTaken = false;
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        touched = true;
    }

    /** Settings › About: turn compatibility (software drawing, no animations) on or off. */
    void setLiteMode(boolean on) {
        CrashGuard.sp(this).edit().putBoolean(CrashGuard.K_LITE, on).putBoolean(CrashGuard.K_LITE_AUTO, false).commit();
        toast(on ? "Compatibility mode on · restarting" : "Compatibility mode off · restarting");
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(MainActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }, 900);
    }

    boolean liteMode() { return lite; }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        startedAt = System.currentTimeMillis();
        touched = false;
        CrashGuard.stage(this, "start");
        try {
            registerReceivers();
            if (!safeMode) bridge.start();
            phone.start();
            updatePhone();
            media.start();
            if (!safeMode) screen(current).onShow();
        } catch (Throwable t) {
            CrashGuard.report(this, "start", t);
        }
        main.removeCallbacks(tick);
        main.post(tick);
        main.removeCallbacks(stable);
        main.postDelayed(stable, 8000);
        refreshWeather(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { bridge.stop(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        long shown = System.currentTimeMillis() - startedAt;
        if (!touched && shown < 4000 && !isFinishing()) {
            // Moved away within seconds without a touch: usually the firmware forcing its own
            // launcher back. Recorded for the Help screen report.
            android.content.SharedPreferences sp = CrashGuard.sp(this);
            sp.edit().putInt("quick_exits", sp.getInt("quick_exits", 0) + 1)
                    .putString("last_error", "The launcher was sent to the background " + shown + " ms after opening, "
                            + "without any touch (stage \"" + sp.getString(CrashGuard.K_STAGE, "?") + "\"). "
                            + "The head unit firmware is probably bringing back its own launcher.").commit();
        }
        CrashGuard.endBootCleanly(this);
        main.removeCallbacks(tick);
        main.removeCallbacks(stable);
        try { unregisterReceiver(receiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(storageReceiver); } catch (Throwable ignored) {}
        try {
            media.stop();
            phone.stop();
            if (!safeMode) screen(current).onHide();
        } catch (Throwable t) {
            CrashGuard.report(this, "stop", t);
        }
        stopLocation();
    }

    private final Runnable stable = new Runnable() {
        @Override
        public void run() { CrashGuard.markStable(MainActivity.this); }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        boolean handled = applyIntent(intent);
        // Pressing Home always returns to the chosen home layout.
        if (!handled && Intent.ACTION_MAIN.equals(intent.getAction()) && !safeMode) {
            hideKeyboard(getCurrentFocus());
            if (current != HOME) show(HOME, true);
        }
    }

    @Override
    public void onBackPressed() {
        if (safeMode) return;
        if (screen(current).onBack()) return;
        if (current != HOME) show(HOME, true);
        // On Home, Back does nothing (this is the launcher).
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                if (!safeMode) screen(current).onTick();
                if (System.currentTimeMillis() - lastWeatherTry > WEATHER_EVERY_MS) refreshWeather(false);
            } catch (Throwable t) {
                CrashGuard.report(MainActivity.this, "tick", t);
            }
            main.postDelayed(this, 1000);
        }
    };

    // ---- Setup-script / automation interface ----------------------------------------------
    /**
     * Lets the ADB setup script (and any automation app) configure the launcher:
     *   am start -n com.adnan.glasslauncher/.MainActivity --es theme Ocean --ei home_layout 0
     *            --ei font_size 1 --ez apply_system_theme true --es car_name "Toyota Noah"
     * Unknown or invalid values are ignored.
     */
    private boolean applyIntent(Intent i) {
        if (i == null || i.getExtras() == null) return false;
        boolean changed = false;
        try {
            String theme = i.getStringExtra("theme");
            if (theme != null) {
                for (int k = 0; k < Ui.THEME_NAMES.length; k++) {
                    if (Ui.THEME_NAMES[k].equalsIgnoreCase(theme.trim())) {
                        prefs.setTheme(k);
                        Ui.themeIndex = k;
                        changed = true;
                        if (prefs.matchShade()) SystemTheme.applyShade(this, k); // pull-down panel follows
                    }
                }
            }
            if (i.hasExtra("home_layout")) { prefs.setHomeLayout(i.getIntExtra("home_layout", 0)); screens[HOME] = null; changed = true; }
            if (i.hasExtra("font_size")) {
                prefs.setFontSize(i.getIntExtra("font_size", 1));
                Ui.fontScale = Prefs.FONT_SCALES[prefs.fontSize()];
                changed = true;
            }
            String car = i.getStringExtra("car_name");
            if (car != null && car.trim().length() > 0) { prefs.setCarName(car.trim()); changed = true; }
            if (i.getBooleanExtra("apply_system_theme", false)) {
                prefs.setMatchSystemWallpaper(true);
                applySystemTheme();
                changed = true;
            }
            if (i.hasExtra("match_shade")) {
                boolean on = i.getBooleanExtra("match_shade", true);
                prefs.setMatchShade(on);
                if (on) SystemTheme.applyShade(this, Ui.themeIndex); else SystemTheme.restoreShade(this);
            }
            if (i.hasExtra("compat_mode")) {
                boolean on = i.getBooleanExtra("compat_mode", false);
                if (on != lite) { setLiteMode(on); return true; }
            }
            if (i.getBooleanExtra("reset_safe_mode", false)) {
                CrashGuard.markStable(this);
                changed = true;
            }
        } catch (Throwable t) {
            CrashGuard.report(this, "intent", t);
        }
        if (changed && !safeMode) rebuildAll();
        // --es screen home|music|apps|settings|car  (automation / testing)
        String screen = i.getStringExtra("screen");
        if (screen != null && !safeMode) {
            String sc = screen.trim().toLowerCase(Locale.US);
            int target = "music".equals(sc) ? MUSIC : "apps".equals(sc) ? APPS : "settings".equals(sc) ? SETTINGS
                    : "car".equals(sc) ? CAR : HOME;
            int section = i.getIntExtra("settings_section", -1);
            if (target == SETTINGS && section >= 0 && section <= SettingsScreen.ABOUT) openSettingsSection(section);
            else show(target, false);
            return true;
        }
        return changed;
    }

    // ---- Hardware keys (steering wheel, media keys, rotary knob) --------------------------
    /**
     * Steering-wheel and front-panel keys usually arrive as standard Android key codes. Media
     * keys reach the playing app through Android even when another app is in front; while
     * the launcher is in front it handles them itself so the on-screen state updates at once.
     * D-pad / rotary knob keys move the focus ring between controls.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        try {
            switch (code) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    if (up) {
                        boolean playing = media.isPlaying();
                        if (code == KeyEvent.KEYCODE_MEDIA_PLAY && playing) return true;
                        if (code == KeyEvent.KEYCODE_MEDIA_PAUSE && !playing) return true;
                        media.togglePlay();
                        refreshMediaSoon();
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (up) { media.next(); refreshMediaSoon(); }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (up) { media.prev(); refreshMediaSoon(); }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    if (up) media.seekTo(media.position() + 10000);
                    return true;
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    if (up) media.seekTo(media.position() - 10000);
                    return true;
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_VOLUME_MUTE:
                    if (up) refreshMediaSoon();
                    return super.dispatchKeyEvent(e); // the system changes the volume
                case KeyEvent.KEYCODE_MUSIC:
                    if (up && !safeMode) show(MUSIC, true);
                    return true;
                case KeyEvent.KEYCODE_SETTINGS:
                    if (up && !safeMode) show(SETTINGS, true);
                    return true;
                case KeyEvent.KEYCODE_CALL:
                    if (up) openRole(Vendor.PHONE);
                    return true;
                case KeyEvent.KEYCODE_CAMERA:
                    if (up) openRole(Vendor.CAMERA);
                    return true;
                case KeyEvent.KEYCODE_EXPLORER:
                    if (up) openRole(Vendor.BROWSER);
                    return true;
                case KeyEvent.KEYCODE_SEARCH:
                    if (up && !safeMode) show(APPS, true);
                    return true;
                case KeyEvent.KEYCODE_ESCAPE:
                    if (up) onBackPressed();
                    return true;
                default:
                    return super.dispatchKeyEvent(e);
            }
        } catch (Throwable t) {
            CrashGuard.report(this, "key " + code, t);
            return super.dispatchKeyEvent(e);
        }
    }

    private void refreshMediaSoon() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() { onMediaChanged(); }
        }, 250);
    }

    // ---- Screens --------------------------------------------------------------------------
    private Screen screen(int i) {
        if (screens[i] == null) {
            switch (i) {
                case MUSIC: screens[i] = new MusicScreen(this); break;
                case APPS: screens[i] = new AppsScreen(this); break;
                case SETTINGS: screens[i] = new SettingsScreen(this); break;
                case CAR: screens[i] = new CarScreen(this); break;
                default: screens[i] = new HomeScreen(this, prefs.homeLayout()); break;
            }
        }
        return screens[i];
    }

    void show(int which) { show(which, true); }

    /** Shows a screen; if it fails to build, a recovery view is shown instead of crashing. */
    private void show(int which, boolean animate) {
        if (safeMode) return;
        hideKeyboard(getCurrentFocus());
        try {
            if (host.getChildCount() > 0) screen(current).onHide();
        } catch (Throwable t) {
            CrashGuard.report(this, "hide", t);
        }
        current = which;
        View v;
        Screen s = screen(which);
        try {
            v = s.view();
        } catch (Throwable t) {
            CrashGuard.report(this, "build screen " + which, t);
            s.invalidate();
            screens[which] = null;
            v = errorView(which);
        }
        if (v.getParent() != null) ((FrameLayout) v.getParent()).removeView(v);
        host.removeAllViews();
        host.addView(v, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        try {
            if (started && screens[which] != null) s.onShow();
            else background.setWallpaperMode(which == HOME && prefs.homeLayout() == 2);
        } catch (Throwable t) {
            CrashGuard.report(this, "show screen " + which, t);
        }
        if (animate && !Ui.reduceMotion) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(280).setInterpolator(Ui.EASE_OUT).start();
            if (which != HOME) loader.play();
        }
    }

    /** Shown when a screen can't be built (bad data, firmware quirk): never a dead end. */
    private View errorView(final int which) {
        LinearLayout col = Ui.col(this);
        col.setGravity(Gravity.CENTER);
        col.setPadding(Ui.u(40), Ui.u(40), Ui.u(40), Ui.u(40));
        TextView t = Ui.text(this, "This screen couldn't open", 28, Ui.TEXT, Ui.display(600));
        col.addView(t);
        TextView m = Ui.multiline(this, "The launcher kept running. You can reset the screen's settings or go back home.", 16, Ui.TEXT_2, Ui.body(400));
        m.setGravity(Gravity.CENTER);
        col.addView(m, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 12, 0, 20));
        LinearLayout row = Ui.row(this);
        row.addView(Parts.accentButton(this, which == HOME ? "Use Dashboard layout" : "Back to home", 16, 22, 52, 16, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (which == HOME) { prefs.setHomeLayout(0); screens[HOME] = null; }
                show(HOME, true);
            }
        }));
        row.addView(Parts.outlineButton(this, "Open Android settings", 16, 22, 52, 16, new View.OnClickListener() {
            @Override
            public void onClick(View v) { startSafe(new Intent(Settings.ACTION_SETTINGS), null); }
        }), Ui.margins(Ui.lp(Ui.WRAP, Ui.u(52)), 12, 0, 0, 0));
        col.addView(row);
        return col;
    }

    private void showSafeMode() {
        host.removeAllViews();
        background.setWallpaperMode(false);
        host.addView(new SafeModeView(this), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
    }

    void leaveSafeMode(boolean reset) {
        if (reset) prefs.resetAll();
        CrashGuard.markStable(this);
        safeMode = false;
        Ui.themeIndex = prefs.theme();
        Ui.fontScale = Prefs.FONT_SCALES[prefs.fontSize()];
        for (int i = 0; i < screens.length; i++) screens[i] = null;
        show(HOME, true);
        if (started) {
            try { screen(HOME).onShow(); } catch (Throwable t) { CrashGuard.report(this, "safe exit", t); }
        }
    }

    void openSettingsSection(int section) {
        SettingsScreen s = (SettingsScreen) screen(SETTINGS);
        s.current = section;
        show(SETTINGS, true);
        try { s.select(section); } catch (Throwable t) { CrashGuard.report(this, "section", t); }
    }

    Widgets.Background background() { return background; }
    Prefs prefs() { return prefs; }
    MediaHub media() { return media; }
    Handler main() { return main; }

    private Wifi wifi;
    private long wifiRefreshedAt;

    Wifi wifi() {
        if (wifi == null) wifi = new Wifi(this);
        return wifi;
    }

    /** Android 10+: the system Wi-Fi panel (falls back to Wi-Fi settings). */
    void openWifiPanel() {
        if (!NewApi.openWifiPanel(this)) startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi settings aren't available");
    }
    PhoneLink phoneLink() { return phone; }
    boolean inSafeMode() { return safeMode; }

    void homeLayoutChanged() {
        try { if (screens[HOME] != null) screens[HOME].onHide(); } catch (Throwable ignored) {}
        screens[HOME] = null;
    }

    /** Theme, font size or screen size changed: rebuild every screen with the new tokens. */
    void rebuildAll() {
        if (safeMode) return;
        Screen cur = screens[current];
        int section = cur instanceof SidebarScreen ? ((SidebarScreen) cur).current : 0;
        try { if (cur != null) cur.onHide(); } catch (Throwable ignored) {}
        for (int i = 0; i < screens.length; i++) {
            if (screens[i] != null) screens[i].invalidate();
        }
        screens[HOME] = null;
        if (cur instanceof SidebarScreen) ((SidebarScreen) cur).current = section;
        background.themeChanged();
        if (toast != null) toastText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.uf(16) * Ui.textScale());
        show(current, false);
    }

    void applyTheme(int idx) {
        prefs.setTheme(idx);
        Ui.themeIndex = idx;
        rebuildAll();
        toast(Ui.THEME_NAMES[idx] + " theme");
        if (prefs.matchSystemWallpaper()) applySystemTheme();
        if (prefs.matchShade()) toast(SystemTheme.describe(SystemTheme.applyShade(this, idx)));
    }

    /** Turns pull-down panel matching on or off; off restores the original colours. */
    void setMatchShade(boolean on) {
        prefs.setMatchShade(on);
        SystemTheme.Shade r = on ? SystemTheme.applyShade(this, Ui.themeIndex) : SystemTheme.restoreShade(this);
        if (on && r != SystemTheme.Shade.APPLIED) prefs.setMatchShade(false);
        toast(SystemTheme.describe(r));
    }

    void applySystemTheme() {
        SystemTheme.apply(this, Ui.themeIndex, new SystemTheme.Done() {
            @Override
            public void done(String summary) { toast(summary); }
        });
    }

    void setFontSize(int i) {
        prefs.setFontSize(i);
        Ui.fontScale = Prefs.FONT_SCALES[prefs.fontSize()];
        rebuildAll();
    }

    // ---- Media ----------------------------------------------------------------------------
    @Override
    public void onMediaChanged() {
        if (safeMode) return;
        Screen s = screens[current];
        try {
            if (s != null) s.onMedia();
        } catch (Throwable t) {
            CrashGuard.report(this, "media", t);
        }
    }

    void askNotificationAccess() {
        if (media.hasAccess()) {
            toast("Access is already on");
            return;
        }
        toast("Turn on “Glass Launcher” in the list");
        if (!startSafe(new Intent(NOTIFICATION_LISTENER_SETTINGS), null)) {
            startSafe(new Intent(Settings.ACTION_SECURITY_SETTINGS), "Open Settings › Apps › Special access › Notification access");
        }
    }

    void openEqualizer() {
        ComponentName vendorEq = Vendor.find(this, prefs, Vendor.EQ);
        if (vendorEq != null && AppsRepo.launch(this, vendorEq)) return;
        Intent i = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        try {
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(i, 0);
                return;
            }
        } catch (Throwable ignored) {
        }
        openRole(Vendor.CAR_SETTINGS);
    }

    String appLabel(String pkg) {
        if (pkg == null) return "";
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (Throwable e) {
            return pkg;
        }
    }

    String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "";
        }
    }

    // ---- Launching ------------------------------------------------------------------------
    boolean startSafe(Intent i, String failToast) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Throwable e) {
            if (failToast != null) toast(failToast);
            return false;
        }
    }

    /** Opens the app connected to a role (auto-detected or chosen); asks once if none found. */
    void openRole(final String role) {
        ComponentName cn = Vendor.find(this, prefs, role);
        if (cn != null && AppsRepo.launch(this, cn)) return;
        if (Vendor.PHONE.equals(role) && startSafe(new Intent(Intent.ACTION_DIAL), null)) return;
        pickApp(Vendor.label(role), new AppCallback() {
            @Override
            public void picked(String component, String label) {
                prefs.setRoleApp(role, component);
                AppsRepo.launch(MainActivity.this, ComponentName.unflattenFromString(component));
            }
        });
    }

    /** Opens the chosen maps app; asks once which one when none is saved yet. */
    void openMaps() {
        String saved = prefs.mapsApp();
        if (saved != null) {
            ComponentName cn = ComponentName.unflattenFromString(saved);
            if (cn != null && AppsRepo.launch(this, cn)) return;
            prefs.setMapsApp(null); // uninstalled by an update: ask again
        }
        final PackageManager pm = getPackageManager();
        final List<ResolveInfo> launchable = new ArrayList<ResolveInfo>();
        try {
            List<ResolveInfo> geo = pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")), 0);
            List<ResolveInfo> all = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            for (ResolveInfo g : geo) {
                for (ResolveInfo l : all) {
                    if (l.activityInfo.packageName.equals(g.activityInfo.packageName)) { launchable.add(l); break; }
                }
            }
        } catch (Throwable ignored) {
        }
        AppCallback save = new AppCallback() {
            @Override
            public void picked(String component, String label) {
                prefs.setMapsApp(component);
                toast("Navigate will open " + label + ". Change it in Settings › Connections.");
                AppsRepo.launch(MainActivity.this, ComponentName.unflattenFromString(component));
            }
        };
        if (launchable.isEmpty()) {
            pickApp("Choose your maps app", save);
        } else if (launchable.size() == 1) {
            ResolveInfo r = launchable.get(0);
            String comp = new ComponentName(r.activityInfo.packageName, r.activityInfo.name).flattenToString();
            prefs.setMapsApp(comp);
            AppsRepo.launch(this, ComponentName.unflattenFromString(comp));
        } else {
            pickFrom("Choose your maps app", launchable, false, save);
        }
    }

    void openVendorSettings() { openRole(Vendor.CAR_SETTINGS); }

    void openBluetoothSettings() {
        if (phone.needsPermission()) {
            requestBluetoothPermission();
            return;
        }
        if (!startSafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), null)) openRole(Vendor.PHONE);
    }

    void requestBluetoothPermission() {
        NewApi.request(this, new String[]{PhoneLink.PERM_CONNECT}, REQ_BLUETOOTH);
    }

    /** Lets the user pick (or reset to auto-detect) the app for a connection role. */
    void pickConnection(final String role, final SidebarScreen refresh) {
        pickApp(Vendor.label(role), true, new AppCallback() {
            @Override
            public void picked(String component, String label) {
                prefs.setRoleApp(role, component);
                ComponentName cn = Vendor.find(MainActivity.this, prefs, role);
                String name = cn != null ? AppsRepo.labelFor(MainActivity.this, cn.flattenToString()) : null;
                toast(Vendor.label(role) + ": " + (name != null ? name : "not found"));
                if (refresh != null) refresh.refresh();
            }
        });
    }

    // ---- Dialogs --------------------------------------------------------------------------
    void pickApp(String title, AppCallback cb) { pickApp(title, false, cb); }

    /** App chooser; {@code withAuto} adds an "Auto-detect" first row (returns component null). */
    void pickApp(String title, boolean withAuto, AppCallback cb) {
        List<ResolveInfo> others = new ArrayList<ResolveInfo>();
        try {
            List<ResolveInfo> all = getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
            for (ResolveInfo r : all) if (!getPackageName().equals(r.activityInfo.packageName)) others.add(r);
        } catch (Throwable ignored) {
        }
        pickFrom(title, others, withAuto, cb);
    }

    private void pickFrom(String title, List<ResolveInfo> list, final boolean withAuto, final AppCallback cb) {
        final PackageManager pm = getPackageManager();
        final List<ResolveInfo> sorted = new ArrayList<ResolveInfo>(list);
        final Collator col = Collator.getInstance(Locale.getDefault());
        Collections.sort(sorted, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo x, ResolveInfo y) { return col.compare(String.valueOf(x.loadLabel(pm)), String.valueOf(y.loadLabel(pm))); }
        });
        final int off = withAuto ? 1 : 0;
        final String[] labels = new String[sorted.size() + off];
        if (withAuto) labels[0] = "Auto-detect";
        for (int i = 0; i < sorted.size(); i++) labels[i + off] = String.valueOf(sorted.get(i).loadLabel(pm));
        try {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(title)
                    .setItems(labels, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            if (withAuto && which == 0) { cb.picked(null, "Auto-detect"); return; }
                            ResolveInfo r = sorted.get(which - off);
                            cb.picked(new ComponentName(r.activityInfo.packageName, r.activityInfo.name).flattenToString(), labels[which]);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Throwable t) {
            CrashGuard.report(this, "picker", t);
        }
    }

    void askText(String title, String initial, final TextCallback cb) {
        askText(title, initial, false, "Save", cb);
    }

    /** Text dialog; {@code secret} hides the text (Wi-Fi passwords). */
    void askText(String title, String initial, boolean secret, String okLabel, final TextCallback cb) {
        final EditText e = new EditText(this);
        e.setText(initial);
        e.setSingleLine(true);
        e.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (secret) e.setHint("Password");
        e.setSelection(e.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(Ui.u(24), Ui.u(8), Ui.u(24), 0);
        box.addView(e);
        try {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(title)
                    .setView(box)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(okLabel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) { cb.done(secret ? e.getText().toString() : e.getText().toString().trim()); }
                    })
                    .show();
        } catch (Throwable t) {
            CrashGuard.report(this, "text dialog", t);
        }
    }

    void hideKeyboard(View v) {
        if (v == null) return;
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            v.clearFocus();
        } catch (Throwable ignored) {
        }
    }

    // ---- Toast ----------------------------------------------------------------------------
    private void buildToast(FrameLayout root) {
        toast = Ui.row(this);
        toast.setPadding(Ui.u(22), Ui.u(14), Ui.u(22), Ui.u(14));
        toast.setBackground(Ui.fill(0xD916171C, Ui.white(0.18f), 18));
        toast.addView(new Icons.GlyphView(this, Icons.INFO, Ui.accent(), 20), Ui.lp(Ui.u(20), Ui.u(20)));
        toastText = Ui.text(this, "", 16, Ui.TEXT, Ui.body(400));
        toast.addView(toastText, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 10, 0, 0, 0));
        FrameLayout.LayoutParams p = Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        p.bottomMargin = Ui.u(168);
        toast.setVisibility(View.GONE);
        root.addView(toast, p);
    }

    private final Runnable hideToast = new Runnable() {
        @Override
        public void run() {
            toast.animate().alpha(0f).translationY(Ui.uf(16)).setDuration(Ui.dur(250)).withEndAction(new Runnable() {
                @Override
                public void run() { toast.setVisibility(View.GONE); }
            }).start();
        }
    };

    void toast(String msg) {
        ((Icons.GlyphView) toast.getChildAt(0)).setColor(Ui.accent());
        toastText.setText(msg);
        toast.animate().cancel();
        toast.setVisibility(View.VISIBLE);
        if (Ui.reduceMotion) {
            toast.setAlpha(1f);
            toast.setTranslationY(0);
        } else {
            toast.setAlpha(0f);
            toast.setTranslationY(Ui.uf(16));
            toast.animate().alpha(1f).translationY(0).setDuration(350).setInterpolator(Ui.EASE_OUT).start();
        }
        main.removeCallbacks(hideToast);
        main.postDelayed(hideToast, 2200);
    }

    // ---- Phone & network status -----------------------------------------------------------
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            try {
                String a = i.getAction();
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a) || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a)) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    phone.onAcl(d, BluetoothDevice.ACTION_ACL_CONNECTED.equals(a));
                }
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) phone.start();
                boolean wasOnline = online;
                updatePhone();
                if (!wasOnline && online) refreshWeather(true);
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(a)) refreshMediaSoon();
                if (a != null && a.startsWith("android.net.wifi.") && current == SETTINGS && screens[SETTINGS] != null
                        && System.currentTimeMillis() - wifiRefreshedAt > 4000) {
                    wifiRefreshedAt = System.currentTimeMillis();
                    ((SettingsScreen) screens[SETTINGS]).wifiChanged();
                }
                if (Intent.ACTION_MEDIA_MOUNTED.equals(a) || Intent.ACTION_MEDIA_UNMOUNTED.equals(a)
                        || Intent.ACTION_MEDIA_REMOVED.equals(a) || Intent.ACTION_MEDIA_EJECT.equals(a)
                        || Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(a)) {
                    musicLibraryChanged();
                }
            } catch (Throwable t) {
                CrashGuard.report(MainActivity.this, "receiver", t);
            }
        }
    };

    private void registerReceivers() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        f.addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION);
        f.addAction(android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        NewApi.registerReceiver(this, receiver, f);
        // Memory card / USB drive inserted or removed: refresh the song list.
        IntentFilter m = new IntentFilter();
        m.addAction(Intent.ACTION_MEDIA_MOUNTED);
        m.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        m.addAction(Intent.ACTION_MEDIA_REMOVED);
        m.addAction(Intent.ACTION_MEDIA_EJECT);
        m.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        m.addDataScheme("file");
        try { NewApi.registerReceiver(this, storageReceiver, m); } catch (Throwable ignored) {}
    }

    private final BroadcastReceiver storageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            try { receiver.onReceive(c, i); } catch (Throwable ignored) {}
        }
    };

    private void updatePhone() {
        try {
            phoneName = phone.connectedName();
        } catch (Throwable t) {
            phoneName = null;
        }
        online = isOnline();
        Screen s = safeMode ? null : screens[current];
        try {
            if (s != null) s.onPhone();
        } catch (Throwable t) {
            CrashGuard.report(this, "phone", t);
        }
    }

    String phoneName() { return phoneName; }

    boolean bluetoothOn() { return phone.enabled(); }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            return ni != null && ni.isConnected();
        } catch (Throwable e) {
            return true;
        }
    }

    String networkLabel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            if (ni == null || !ni.isConnected()) return "Offline";
            String type = ni.getTypeName();
            if (type == null) return "Online";
            if ("WIFI".equalsIgnoreCase(type)) return "Online · Wi-Fi";
            if ("MOBILE".equalsIgnoreCase(type)) return "Online · Mobile data";
            if ("BLUETOOTH".equalsIgnoreCase(type)) return "Online · Bluetooth tethering";
            return "Online · " + type;
        } catch (Throwable e) {
            return "Unknown";
        }
    }

    // ---- Permissions ----------------------------------------------------------------------
    boolean hasLocationPermission() {
        return NewApi.granted(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                || NewApi.granted(this, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    void requestLocationPermission() {
        NewApi.request(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
    }

    /** Location for listing Wi-Fi networks (doesn't change the weather setting). */
    void requestLocationForWifi() {
        NewApi.request(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_WIFI);
    }

    void requestMusicPermission() {
        NewApi.request(this, new String[]{LocalMusic.permission()}, REQ_MUSIC);
    }

    /** Storage permission changed or a memory card / USB drive was inserted or removed. */
    private void musicLibraryChanged() {
        LocalMusic.invalidate();
        if (safeMode || screens[MUSIC] == null) return;
        try {
            ((MusicScreen) screens[MUSIC]).libraryChanged();
        } catch (Throwable t) {
            CrashGuard.report(this, "library", t);
        }
    }

    // Activity.onRequestPermissionsResult exists from API 23; on 21-22 permissions are granted at install.
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_WIFI) {
            wifi().scan();
            if (screens[SETTINGS] != null) ((SettingsScreen) screens[SETTINGS]).wifiChanged();
            return;
        }
        if (code == REQ_MUSIC) {
            if (!LocalMusic.hasPermission(this)) toast("Without access to music files, songs on the head unit can't be played");
            musicLibraryChanged();
            return;
        }
        if (code == REQ_BLUETOOTH) {
            phone.start();
            updatePhone();
            if (phone.needsPermission()) toast("Without “Nearby devices” the phone status can't be shown");
            return;
        }
        if (code != REQ_LOCATION) return;
        if (hasLocationPermission()) {
            prefs.useDeviceLocation();
            refreshWeather(true);
        } else {
            toast("Without location, set a city in Settings › Weather");
        }
    }

    // ---- Phone link (Glass Link app) --------------------------------------------------------
    PhoneBridge bridge() { return bridge; }

    /** Turn-by-turn from the phone, or null when no route is active. */
    PhoneBridge.Nav nav() {
        PhoneBridge.Nav n = nav;
        if (n == null || !n.active || System.currentTimeMillis() - n.at > 3 * 60 * 1000L) return null;
        return n;
    }

    private void navChanged() {
        if (safeMode) return;
        Screen s = screens[current];
        try {
            if (s != null) s.onNav();
        } catch (Throwable t) {
            CrashGuard.report(this, "nav", t);
        }
    }

    // ---- Weather --------------------------------------------------------------------------
    int weatherState() { return weatherState; }
    Weather.Data weather() { return weather; }

    String formatTemp(double c) {
        if (prefs.tempFahrenheit()) return Math.round(c * 9 / 5 + 32) + "°F";
        return Math.round(c) + "°C";
    }

    void weatherChanged() {
        if (safeMode) return;
        Screen s = screens[current];
        try {
            if (s != null) s.onWeather();
        } catch (Throwable t) {
            CrashGuard.report(this, "weather", t);
        }
        if (screens[HOME] != null && current != HOME) screens[HOME].invalidate();
    }

    private void setWeatherState(int st) {
        weatherState = st;
        weatherChanged();
    }

    void refreshWeather(boolean force) {
        try {
            refreshWeatherUnsafe(force);
        } catch (Throwable t) {
            CrashGuard.report(this, "weather refresh", t);
        }
    }

    private void refreshWeatherUnsafe(boolean force) {
        if (!force && weather != null && System.currentTimeMillis() - weather.fetchedAt < WEATHER_EVERY_MS
                && System.currentTimeMillis() - lastWeatherTry < WEATHER_EVERY_MS) {
            return;
        }
        lastWeatherTry = System.currentTimeMillis();
        if (bridge != null && bridge.connected()) {
            // The phone fetches it with its own location and internet. If it doesn't answer,
            // fall back to the head unit's own internet.
            final long asked = System.currentTimeMillis();
            boolean city = prefs.useCity() && prefs.cityName() != null;
            bridge.requestWeather(city ? prefs.cityName() : null, prefs.cityLat(), prefs.cityLon());
            if (weather == null) setWeatherState(W_LOADING);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (weather == null || weather.fetchedAt < asked - 5 * 60 * 1000L) refreshWeatherFromInternet();
                }
            }, 20000);
            return;
        }
        refreshWeatherFromInternet();
    }

    private void refreshWeatherFromInternet() {
        online = isOnline();
        if (!online) {
            setWeatherState(W_OFFLINE);
            return;
        }
        if (prefs.useCity() && prefs.cityName() != null) {
            if (weather == null) setWeatherState(W_LOADING);
            fetch(prefs.cityLat(), prefs.cityLon(), prefs.cityName());
            return;
        }
        if (!hasLocationPermission()) {
            setWeatherState(weather != null ? W_OK : W_NEED_PERMISSION);
            return;
        }
        Location loc = lastKnownLocation();
        if (loc != null) {
            if (weather == null) setWeatherState(W_LOADING);
            fetchForLocation(loc);
            return;
        }
        if (weather == null) setWeatherState(W_LOADING);
        requestOneFix();
    }

    private Location lastKnownLocation() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    private void requestOneFix() {
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) { setWeatherState(W_NO_LOCATION); return; }
        stopLocation();
        pendingLocation = new LocationListener() {
            @Override
            public void onLocationChanged(Location l) {
                stopLocation();
                fetchForLocation(l);
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        boolean any = false;
        try {
            for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
                if (lm.getAllProviders().contains(p) && lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 0, 0, pendingLocation, Looper.getMainLooper());
                    any = true;
                }
            }
        } catch (Throwable ignored) {
        }
        if (!any) {
            stopLocation();
            setWeatherState(weather != null ? W_OK : W_NO_LOCATION);
            return;
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (pendingLocation != null) {
                    stopLocation();
                    if (weather == null) setWeatherState(W_NO_LOCATION);
                }
            }
        }, 45000);
    }

    private void stopLocation() {
        if (pendingLocation == null) return;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try { if (lm != null) lm.removeUpdates(pendingLocation); } catch (Throwable ignored) {}
        pendingLocation = null;
    }

    private void fetchForLocation(final Location loc) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String place = null;
                try {
                    if (Geocoder.isPresent()) {
                        List<Address> res = new Geocoder(app, Locale.getDefault()).getFromLocation(loc.getLatitude(), loc.getLongitude(), 1);
                        if (res != null && !res.isEmpty()) {
                            Address ad = res.get(0);
                            place = ad.getLocality() != null ? ad.getLocality() : ad.getSubAdminArea() != null ? ad.getSubAdminArea() : ad.getAdminArea();
                        }
                    }
                } catch (Throwable ignored) {
                }
                final String fp = place;
                main.post(new Runnable() {
                    @Override
                    public void run() { fetch(loc.getLatitude(), loc.getLongitude(), fp); }
                });
            }
        }, "geocoder").start();
    }

    private void fetch(double lat, double lon, String place) {
        Weather.fetch(lat, lon, place, new Weather.Callback() {
            @Override
            public void done(Weather.Data data, String error) {
                if (data != null) {
                    weather = data;
                    prefs.setWeatherCache(data.toJson());
                    setWeatherState(W_OK);
                } else {
                    setWeatherState(weather != null ? W_OFFLINE : (isOnline() ? W_ERROR : W_OFFLINE));
                }
            }
        });
    }

    static boolean atLeast(int sdk) { return Build.VERSION.SDK_INT >= sdk; }
}
