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
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.audiofx.AudioEffect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Single HOME activity hosting every screen. */
public class MainActivity extends Activity implements MediaHub.Listener {
    static final int HOME = 0, MUSIC = 1, APPS = 2, SETTINGS = 3, CAR = 4;
    static final int W_LOADING = 0, W_OK = 1, W_NEED_PERMISSION = 2, W_NO_LOCATION = 3, W_OFFLINE = 4, W_ERROR = 5;
    private static final int REQ_LOCATION = 7;
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;

    private Prefs prefs;
    private MediaHub media;
    private Widgets.Background background;
    private FrameLayout host;
    private Widgets.WheelLoader loader;
    private LinearLayout toast;
    private TextView toastText;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Screen[] screens = new Screen[5];
    private int current = HOME;
    private boolean started;

    private String phoneName;
    private boolean online;
    private Weather.Data weather;
    private int weatherState = W_LOADING;
    private long lastWeatherTry;
    private LocationListener pendingLocation;

    interface TextCallback { void done(String s); }
    interface AppCallback { void picked(String component, String label); }

    // ---- Lifecycle ------------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        weather = Weather.Data.fromJson(prefs.weatherCache());
        if (weather != null) weatherState = W_OK;

        FrameLayout root = new FrameLayout(this);
        background = new Widgets.Background(this);
        root.addView(background, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        host = new FrameLayout(this);
        root.addView(host, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        loader = new Widgets.WheelLoader(this);
        root.addView(loader, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        buildToast(root);
        setContentView(root);
        hideSystemBars();
        show(HOME, false);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        registerReceivers();
        updatePhone();
        media.start();
        screen(current).onShow();
        main.removeCallbacks(tick);
        main.post(tick);
        refreshWeather(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        main.removeCallbacks(tick);
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        media.stop();
        screen(current).onHide();
        stopLocation();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home always returns to the chosen home layout.
        if (Intent.ACTION_MAIN.equals(intent.getAction())) {
            hideKeyboard(getCurrentFocus());
            if (current != HOME) show(HOME, true);
        }
    }

    @Override
    public void onBackPressed() {
        if (screen(current).onBack()) return;
        if (current != HOME) show(HOME, true);
        // On Home, Back does nothing (this is the launcher).
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            screen(current).onTick();
            if (System.currentTimeMillis() - lastWeatherTry > WEATHER_EVERY_MS) refreshWeather(false);
            main.postDelayed(this, 1000);
        }
    };

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

    private void show(int which, boolean animate) {
        hideKeyboard(getCurrentFocus());
        if (host.getChildCount() > 0) screen(current).onHide();
        current = which;
        Screen s = screen(which);
        View v = s.view();
        if (v.getParent() != null) ((FrameLayout) v.getParent()).removeView(v);
        host.removeAllViews();
        host.addView(v, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        if (started) s.onShow();
        else background.setWallpaperMode(which == HOME && prefs.homeLayout() == 2);
        if (animate && !Ui.reduceMotion) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(280).setInterpolator(Ui.EASE_OUT).start();
            if (which != HOME) loader.play();
        }
    }

    void openSettingsSection(int section) {
        SettingsScreen s = (SettingsScreen) screen(SETTINGS);
        s.current = section;
        show(SETTINGS, true);
        s.select(section);
    }

    Widgets.Background background() { return background; }
    Prefs prefs() { return prefs; }
    MediaHub media() { return media; }

    void homeLayoutChanged() {
        if (screens[HOME] != null) screens[HOME].onHide();
        screens[HOME] = null;
    }

    /** Theme or font size changed: rebuild every screen with the new tokens. */
    void rebuildAll() {
        for (int i = 0; i < screens.length; i++) {
            if (screens[i] != null && i != current) screens[i].invalidate();
        }
        screens[HOME] = null;
        Screen cur = screen(current);
        int section = cur instanceof SidebarScreen ? ((SidebarScreen) cur).current : 0;
        cur.onHide();
        cur.invalidate();
        if (cur instanceof SidebarScreen) ((SidebarScreen) cur).current = section;
        background.themeChanged();
        show(current, false);
    }

    void applyTheme(int idx) {
        prefs.setTheme(idx);
        Ui.themeIndex = idx;
        rebuildAll();
        toast(Ui.THEME_NAMES[idx] + " theme");
    }

    void setFontSize(int i) {
        prefs.setFontSize(i);
        Ui.fontScale = Prefs.FONT_SCALES[i];
        rebuildAll();
    }

    // ---- Media ----------------------------------------------------------------------------
    @Override
    public void onMediaChanged() {
        Screen s = screens[current];
        if (s != null) s.onMedia();
    }

    void askNotificationAccess() {
        if (media.hasAccess()) {
            toast("Access is already on");
            return;
        }
        toast("Turn on “Glass Launcher” in the list");
        startSafe(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                "Open Settings › Apps › Special access › Notification access");
    }

    void openEqualizer() {
        Intent i = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        try {
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(i, 0);
                return;
            }
        } catch (Exception ignored) {
        }
        openVendorSettings();
    }

    String appLabel(String pkg) {
        if (pkg == null) return "";
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    // ---- Launching ------------------------------------------------------------------------
    boolean startSafe(Intent i, String failToast) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            if (failToast != null) toast(failToast);
            return false;
        }
    }

    /** Opens the chosen maps app; asks once which one when none is saved yet. */
    void openMaps() {
        String saved = prefs.mapsApp();
        if (saved != null) {
            ComponentName cn = ComponentName.unflattenFromString(saved);
            if (cn != null && AppsRepo.launch(this, cn)) return;
            prefs.setMapsApp(null);
        }
        final PackageManager pm = getPackageManager();
        List<ResolveInfo> geo = pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")), 0);
        final List<ResolveInfo> launchable = new ArrayList<ResolveInfo>();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = pm.queryIntentActivities(mainIntent, 0);
        for (ResolveInfo g : geo) {
            for (ResolveInfo l : all) {
                if (l.activityInfo.packageName.equals(g.activityInfo.packageName)) { launchable.add(l); break; }
            }
        }
        if (launchable.isEmpty()) {
            pickApp("Choose your maps app", new AppCallback() {
                @Override
                public void picked(String component, String label) {
                    prefs.setMapsApp(component);
                    AppsRepo.launch(MainActivity.this, ComponentName.unflattenFromString(component));
                }
            });
            return;
        }
        if (launchable.size() == 1) {
            ResolveInfo r = launchable.get(0);
            String comp = new ComponentName(r.activityInfo.packageName, r.activityInfo.name).flattenToString();
            prefs.setMapsApp(comp);
            AppsRepo.launch(this, ComponentName.unflattenFromString(comp));
            return;
        }
        pickFrom("Choose your maps app", launchable, new AppCallback() {
            @Override
            public void picked(String component, String label) {
                prefs.setMapsApp(component);
                toast("Navigate will open " + label + ". Change it in Car settings.");
                AppsRepo.launch(MainActivity.this, ComponentName.unflattenFromString(component));
            }
        });
    }

    void openVendorSettings() {
        String saved = prefs.vendorApp();
        if (saved != null) {
            ComponentName cn = ComponentName.unflattenFromString(saved);
            if (cn != null && AppsRepo.launch(this, cn)) return;
            prefs.setVendorApp(null);
        }
        pickApp("Head unit settings app", new AppCallback() {
            @Override
            public void picked(String component, String label) {
                prefs.setVendorApp(component);
                AppsRepo.launch(MainActivity.this, ComponentName.unflattenFromString(component));
            }
        });
    }

    void openBluetoothSettings() {
        startSafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth settings aren't available");
    }

    // ---- Dialogs --------------------------------------------------------------------------
    void pickApp(String title, AppCallback cb) {
        Intent mainIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> all = getPackageManager().queryIntentActivities(mainIntent, 0);
        List<ResolveInfo> others = new ArrayList<ResolveInfo>();
        for (ResolveInfo r : all) if (!getPackageName().equals(r.activityInfo.packageName)) others.add(r);
        pickFrom(title, others, cb);
    }

    private void pickFrom(String title, List<ResolveInfo> list, final AppCallback cb) {
        final PackageManager pm = getPackageManager();
        final List<ResolveInfo> sorted = new ArrayList<ResolveInfo>(list);
        final Collator col = Collator.getInstance(Locale.getDefault());
        Collections.sort(sorted, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo x, ResolveInfo y) { return col.compare(x.loadLabel(pm).toString(), y.loadLabel(pm).toString()); }
        });
        final String[] labels = new String[sorted.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = sorted.get(i).loadLabel(pm).toString();
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        ResolveInfo r = sorted.get(which);
                        cb.picked(new ComponentName(r.activityInfo.packageName, r.activityInfo.name).flattenToString(), labels[which]);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void askText(String title, String initial, final TextCallback cb) {
        final EditText e = new EditText(this);
        e.setText(initial);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setSelection(e.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(Ui.u(24), Ui.u(8), Ui.u(24), 0);
        box.addView(e);
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) { cb.done(e.getText().toString().trim()); }
                })
                .show();
    }

    void hideKeyboard(View v) {
        if (v == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        v.clearFocus();
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
            boolean wasOnline = online;
            updatePhone();
            if (!wasOnline && online) refreshWeather(true);
        }
    };

    private void registerReceivers() {
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(receiver, f);
    }

    private void updatePhone() {
        String name = null;
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null && ad.isEnabled()) {
                Set<BluetoothDevice> bonded = ad.getBondedDevices();
                Method isConnected = BluetoothDevice.class.getMethod("isConnected");
                if (bonded != null) {
                    for (BluetoothDevice d : bonded) {
                        if (Boolean.TRUE.equals(isConnected.invoke(d))) {
                            name = d.getName();
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Hidden API blocked or no permission: show "No phone".
        }
        phoneName = name;
        online = isOnline();
        Screen s = screens[current];
        if (s != null) s.onPhone();
    }

    String phoneName() { return phoneName; }

    boolean bluetoothOn() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            return ad != null && ad.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    String networkLabel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            if (ni == null || !ni.isConnected()) return "Offline";
            String type = ni.getTypeName();
            if ("WIFI".equalsIgnoreCase(type)) return "Online · Wi-Fi";
            if ("MOBILE".equalsIgnoreCase(type)) return "Online · Mobile data";
            if ("BLUETOOTH".equalsIgnoreCase(type)) return "Online · Bluetooth tethering";
            return "Online · " + type;
        } catch (Exception e) {
            return "Unknown";
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
        Screen s = screens[current];
        if (s != null) s.onWeather();
        if (screens[HOME] != null && current != HOME) screens[HOME].invalidate();
    }

    private void setWeatherState(int st) {
        weatherState = st;
        weatherChanged();
    }

    boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void requestLocationPermission() {
        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_LOCATION) return;
        if (hasLocationPermission()) {
            prefs.useDeviceLocation();
            refreshWeather(true);
        } else {
            toast("Without location, set a city in Settings › Weather");
        }
    }

    void refreshWeather(boolean force) {
        if (!force && weather != null && System.currentTimeMillis() - weather.fetchedAt < WEATHER_EVERY_MS
                && System.currentTimeMillis() - lastWeatherTry < WEATHER_EVERY_MS) {
            return;
        }
        lastWeatherTry = System.currentTimeMillis();
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
        } catch (SecurityException ignored) {
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
        } catch (SecurityException ignored) {
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
        try { if (lm != null) lm.removeUpdates(pendingLocation); } catch (Exception ignored) {}
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
                } catch (Exception ignored) {
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
}
